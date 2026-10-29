package slussen.core.fetch

import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.DeliveredTransaction
import slussen.core.port.DeliveredTransactions
import slussen.core.port.DeliveryStored
import slussen.core.port.NewUnreadableTransaction
import slussen.core.port.Reception
import slussen.core.port.ReceivedBankTransaction
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.TransactionField
import slussen.core.port.UnreadableTransactionReason
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/** A Bank Transaction O-1 inserts, with its `occurrence_number` and Import Identifier (`BANK_TRANSACTION`). */
data class NewBankTransaction(
    val stableReference: String?,
    val referenceKind: StableReferenceKind,
    val occurrenceNumber: Int,
    val importIdentifier: String,
    val bookedDate: LocalDate,
    val amount: Amount,
    val currency: CurrencyCode,
    val payee: String?,
    val memo: String?,
    val firstSeenAt: Instant,
)

/**
 * What O-1 writes for one delivery, decided from what is stored: the new Bank Transactions, the known ones with their
 * changed content ([changed], each the stored one with the new content and its own id and Import Identifier), the
 * Unreadable Transactions to add, and what the core records of it ([stored]).
 */
data class IntakePlan(
    val inserts: List<NewBankTransaction>,
    val changed: List<StoredBankTransaction>,
    val unreadable: List<NewUnreadableTransaction>,
    val stored: DeliveryStored,
)

/**
 * The rules O-1 applies to the transactions of one delivery, inside its storage transaction (ADR-002 §3, ADR-008 §4,
 * §5; UC-001 steps 7-8, A17, BR-016; UC-003 BR-002). Pure: it reads only what it is given, so the storage applies it to
 * what it holds at that moment.
 */
object BankTransactionIntake {
    /** The fields that make two transactions without a Stable Reference identical (ADR-008 §2, UC-002 BR-003). */
    private data class Identical(val bookedDate: LocalDate, val amount: Long, val payee: String, val memo: String)

    /** The readable fields and reason by which Unreadable Transactions are counted (UC-001 BR-016). */
    private data class Readable(
        val bookedDate: LocalDate?,
        val amount: Long?,
        val currency: String?,
        val payee: String?,
        val memo: String?,
        val reason: String,
    )

    private fun identicalOf(bookedDate: LocalDate, amount: Amount, payee: String?, memo: String?) = Identical(
        bookedDate,
        amount.thousandths,
        ImportIdentifierProtocol.normalisedText(payee),
        ImportIdentifierProtocol.normalisedText(memo),
    )

    fun plan(
        storedTransactions: List<StoredBankTransaction>,
        storedUnreadable: List<StoredUnreadableTransaction>,
        delivered: DeliveredTransactions,
        identify: (ImportIdentifierInput) -> String = ImportIdentifierProtocol::importIdentifier,
    ): IntakePlan {
        val sourceKey = delivered.sourceKey
        val byReference = storedTransactions.filter { it.stableReference != null }.associateBy { it.stableReference!! }.toMutableMap()
        // ADR-008 §5: the canonical input of every Import Identifier held, recomputed from the stored row.
        val held = storedTransactions.associate { it.importIdentifier to inputOf(it, sourceKey) }.toMutableMap()
        val storedIdentical = storedTransactions.filter { it.stableReference == null }
            .groupingBy { identicalOf(it.bookedDate, it.amount, it.payee, it.memo) }.eachCount().toMutableMap()
        val identicalAtStart = storedIdentical.toMap()
        val deliveredIdentical = mutableMapOf<Identical, Int>()
        val inserts = mutableListOf<NewBankTransaction>()
        val changed = linkedMapOf<Long, StoredBankTransaction>()
        val unreadable = mutableListOf<NewUnreadableTransaction>()
        val received = mutableListOf<ReceivedBankTransaction>()
        val collisions = mutableListOf<String>()

        delivered.interpretable.forEach { transaction ->
            val reference = transaction.stableReference
            // UC-001 BR-016: a Stable Reference that is not well-formed Unicode cannot be encoded; it is kept, never completed by guessing.
            if (reference != null && !ImportIdentifierProtocol.isEncodable(reference)) {
                unreadable += unreadableOf(transaction, UnreadableTransactionReason.UnreadableStructure)
                return@forEach
            }
            val known = reference?.let { byReference[it] }
            // A second item with a Stable Reference already taken in by this delivery is that same transaction.
            if (known != null && known.id == NOT_STORED_YET) return@forEach
            if (known != null) {
                val changes = changesOf(known, transaction)
                if (changes.isNotEmpty()) {
                    val updated = known.copy(
                        bookedDate = transaction.bookedDate,
                        amount = transaction.amount,
                        currency = transaction.currency,
                        payee = transaction.payee,
                        memo = transaction.memo,
                    )
                    changed[known.id] = updated
                    byReference[reference] = updated
                    received += receivedOf(updated.importIdentifier, Reception.ChangedContent, transaction, changes)
                }
                return@forEach
            }
            val input: ImportIdentifierInput
            val occurrenceNumber: Int
            if (reference != null) {
                input = ImportIdentifierInput.WithStableReference(sourceKey, reference)
                occurrenceNumber = 1
            } else {
                val identical = identicalOf(transaction.bookedDate, transaction.amount, transaction.payee, transaction.memo)
                val position = (deliveredIdentical[identical] ?: 0) + 1
                deliveredIdentical[identical] = position
                // ADR-008 §5: as many identical ones delivered as are stored are the stored ones; only the additional ones are new.
                if (position <= (identicalAtStart[identical] ?: 0)) return@forEach
                // ADR-008 §5: a new one gets the number of stored identical transactions of the Bank Account plus one.
                occurrenceNumber = (storedIdentical[identical] ?: 0) + 1
                input = ImportIdentifierInput.WithoutStableReference(
                    sourceKey, transaction.bookedDate, transaction.amount, transaction.payee, transaction.memo, occurrenceNumber,
                )
            }
            val importIdentifier = identify(input)
            val holder = held[importIdentifier]
            // UC-003 BR-002: an Import Identifier held by a different stored Bank Transaction is a collision, never merged.
            if (holder != null && !holder.contentEquals(ImportIdentifierProtocol.canonicalInput(input))) {
                // UC-001 BR-016: a transaction whose Import Identifier collides is kept as an Unreadable Transaction.
                unreadable += unreadableOf(transaction, UnreadableTransactionReason.ImportIdentifierCollision)
                collisions += importIdentifier
                return@forEach
            }
            inserts += NewBankTransaction(
                stableReference = reference,
                referenceKind = if (reference == null) StableReferenceKind.None else StableReferenceKind.Supplied,
                occurrenceNumber = occurrenceNumber,
                importIdentifier = importIdentifier,
                bookedDate = transaction.bookedDate,
                amount = transaction.amount,
                currency = transaction.currency,
                payee = transaction.payee,
                memo = transaction.memo,
                firstSeenAt = delivered.receivedAt,
            )
            held[importIdentifier] = ImportIdentifierProtocol.canonicalInput(input)
            if (reference == null) {
                val identical = identicalOf(transaction.bookedDate, transaction.amount, transaction.payee, transaction.memo)
                storedIdentical[identical] = (storedIdentical[identical] ?: 0) + 1
            } else {
                byReference[reference] = placeholderOf(reference, importIdentifier, transaction, delivered)
            }
            received += receivedOf(importIdentifier, Reception.FirstTime, transaction, emptyList())
        }

        val added = newUnreadable(storedUnreadable, delivered.unreadable + unreadable)
        return IntakePlan(
            inserts = inserts,
            changed = changed.values.toList(),
            unreadable = added,
            stored = DeliveryStored(received = received, collisions = collisions, unreadableAdded = added.size),
        )
    }

    // UC-001 BR-016: a later Fetch that delivers as many Unreadable Transactions with the same readable fields and reason as are stored adds none; only additional ones are added.
    /** Of [delivered], those beyond the stored ones with the same readable fields and reason, in the order delivered. */
    private fun newUnreadable(stored: List<StoredUnreadableTransaction>, delivered: List<NewUnreadableTransaction>): List<NewUnreadableTransaction> {
        val storedCount = stored.groupingBy { Readable(it.bookedDate, it.amount?.thousandths, it.currency?.code, it.payee, it.memo, it.reason) }
            .eachCount()
        val seen = mutableMapOf<Readable, Int>()
        return delivered.filter { item ->
            val key = Readable(item.bookedDate, item.amount?.thousandths, item.currency?.code, item.payee, item.memo, item.reason.text)
            val position = (seen[key] ?: 0) + 1
            seen[key] = position
            position > (storedCount[key] ?: 0)
        }
    }

    /** The canonical input of a stored Bank Transaction, recomputed from its row (ADR-008 §5). */
    private fun inputOf(stored: StoredBankTransaction, sourceKey: String): ByteArray {
        val reference = stored.stableReference
        val input = if (reference != null) {
            ImportIdentifierInput.WithStableReference(sourceKey, reference)
        } else {
            ImportIdentifierInput.WithoutStableReference(sourceKey, stored.bookedDate, stored.amount, stored.payee, stored.memo, stored.occurrenceNumber)
        }
        return ImportIdentifierProtocol.canonicalInput(input)
    }

    private fun changesOf(known: StoredBankTransaction, delivered: DeliveredTransaction): List<TransactionField> = listOfNotNull(
        TransactionField.BookedDate.takeIf { known.bookedDate != delivered.bookedDate },
        TransactionField.Amount.takeIf { known.amount != delivered.amount },
        TransactionField.Currency.takeIf { known.currency != delivered.currency },
        TransactionField.Payee.takeIf { known.payee != delivered.payee },
        TransactionField.Memo.takeIf { known.memo != delivered.memo },
    )

    private fun receivedOf(
        importIdentifier: String,
        reception: Reception,
        transaction: DeliveredTransaction,
        changes: List<TransactionField>,
    ) = ReceivedBankTransaction(importIdentifier, reception, transaction.bookedDate, transaction.amount, transaction.currency, changes)

    private fun unreadableOf(transaction: DeliveredTransaction, reason: UnreadableTransactionReason) = NewUnreadableTransaction(
        bookedDate = transaction.bookedDate,
        amount = transaction.amount,
        currency = transaction.currency,
        payee = transaction.payee,
        memo = transaction.memo,
        reason = reason,
    )

    /**
     * A transaction inserted by this delivery, as the stored one a second item with the same Stable Reference in the same
     * delivery is compared with; its id is never used, since only stored rows are changed by id.
     */
    private fun placeholderOf(reference: String, importIdentifier: String, transaction: DeliveredTransaction, delivered: DeliveredTransactions) =
        StoredBankTransaction(
            id = NOT_STORED_YET,
            bankAccountId = NOT_STORED_YET,
            stableReference = reference,
            referenceKind = StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = importIdentifier,
            bookedDate = transaction.bookedDate,
            amount = transaction.amount,
            currency = transaction.currency,
            payee = transaction.payee,
            memo = transaction.memo,
            firstSeenAt = delivered.receivedAt,
            status = null,
            transferId = null,
        )

    private const val NOT_STORED_YET = -1L
}
