package slussen.core.fileimport

import slussen.core.fetch.NewBankTransaction
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.FileTransaction
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredBankTransaction
import slussen.core.port.TransactionFileToImport
import slussen.pluginapi.value.Amount
import java.time.LocalDate

/** What the counting of a Transaction File decided from what is stored (UC-002 BR-003, A6). */
sealed interface FileCounting {
    /** The new Bank Transactions of the file, in the order of the file; every other transaction is a stored one. */
    data class Counted(val inserts: List<NewBankTransaction>) : FileCounting

    /** A new transaction's Import Identifier is already held in the Bank Account (ADR-008 §5): nothing is stored. */
    data class Collision(val importIdentifier: String) : FileCounting
}

/**
 * The one rule by which the transactions of a Transaction File are counted against the stored Bank Transactions of its
 * Bank Account (UC-002 BR-003, ADR-002 §3 O-3, ADR-008 §4, §5). Pure: it reads only what it is given, so the storage,
 * `slussen.db` and the fake of `:testing` alike, applies it inside its transaction to what it holds at that moment and
 * writes only what it returns. It never changes a stored Bank Transaction: a stored Stable Reference, `occurrence_number`
 * and Import Identifier stay as they are, whatever a later file holds (BR-002).
 */
object TransactionFileCounting {
    /** The fields that make two transactions identical (ADR-008 §2: Form C, an absent text as an empty one). */
    private data class Identical(val bookedDate: LocalDate, val amount: Long, val payee: String, val memo: String)

    private fun identicalOf(bookedDate: LocalDate, amount: Amount, payee: String?, memo: String?) = Identical(
        bookedDate,
        amount.thousandths,
        ImportIdentifierProtocol.normalisedText(payee),
        ImportIdentifierProtocol.normalisedText(memo),
    )

    private fun identicalOf(transaction: FileTransaction) =
        identicalOf(transaction.bookedDate, transaction.amount, transaction.payee, transaction.memo)

    // UC-002 BR-003: Slussen counts the transactions of a file against the stored Bank Transactions of the Bank Account in three steps, all inside the storage operation that stores the file, so that no transaction gets a second Import Identifier.
    /**
     * Counts [file] against [stored], the Bank Transactions of its Bank Account as stored now, and gives the new Bank
     * Transactions with their `occurrence_number` and Import Identifier ([identify], ADR-008 §4), or the first collision.
     */
    fun count(
        stored: List<StoredBankTransaction>,
        file: TransactionFileToImport,
        identify: (ImportIdentifierInput) -> String = ImportIdentifierProtocol::importIdentifier,
    ): FileCounting {
        val transactions = file.transactions
        val counted = BooleanArray(transactions.size)

        // UC-002 BR-003: Step 1: for each reference a stored Bank Transaction holds as its Stable Reference, the first transaction of the file with that reference, in the order of the file, is that Bank Transaction.
        val storedReferences = stored.mapNotNull { it.stableReference }.toSet()
        val claimed = mutableSetOf<String>()
        transactions.forEachIndexed { index, transaction ->
            if (transaction.reference in storedReferences && claimed.add(transaction.reference)) counted[index] = true
        }

        val sharedInFile = transactions.groupingBy { it.reference }.eachCount()

        // UC-002 BR-003: Step 2: every other transaction is counted, in the order of the file, against the stored Bank Transactions without a Stable Reference identical to it; each stored one is counted once. Only one with a Supplied reference that no other transaction of the file shares is not counted here: it is new.
        val notCountedYet = stored.filter { it.stableReference == null }
            .groupingBy { identicalOf(it.bookedDate, it.amount, it.payee, it.memo) }.eachCount().toMutableMap()
        transactions.forEachIndexed { index, transaction ->
            if (counted[index]) return@forEachIndexed
            if (transaction.referenceKind == StableReferenceKind.Supplied && sharedInFile.getValue(transaction.reference) == 1) return@forEachIndexed
            val identical = identicalOf(transaction)
            val left = notCountedYet[identical] ?: 0
            if (left > 0) {
                notCountedYet[identical] = left - 1
                counted[index] = true
            }
        }

        // ADR-008 §5: the number of identical transactions without a Stable Reference of the Bank Account, the new ones of this file included.
        // UC-002 BR-003: Step 3: every transaction left is new; it has its reference as its Stable Reference only when no other transaction of the file has that reference, otherwise none, and then the next occurrence_number after the stored identical ones.
        val identicalWithoutReference = stored.filter { it.stableReference == null }
            .groupingBy { identicalOf(it.bookedDate, it.amount, it.payee, it.memo) }.eachCount().toMutableMap()
        // ADR-008 §5: every Import Identifier held in the Bank Account, the stored ones and the new ones of this file.
        val held = stored.map { it.importIdentifier }.toMutableSet()
        val inserts = mutableListOf<NewBankTransaction>()
        transactions.forEachIndexed { index, transaction ->
            if (counted[index]) return@forEachIndexed
            val alone = sharedInFile.getValue(transaction.reference) == 1
            val occurrenceNumber: Int
            val input: ImportIdentifierInput
            if (alone) {
                occurrenceNumber = 1
                input = ImportIdentifierInput.WithStableReference(file.sourceKey, transaction.reference)
            } else {
                val identical = identicalOf(transaction)
                occurrenceNumber = (identicalWithoutReference[identical] ?: 0) + 1
                identicalWithoutReference[identical] = occurrenceNumber
                input = ImportIdentifierInput.WithoutStableReference(
                    file.sourceKey, transaction.bookedDate, transaction.amount, transaction.payee, transaction.memo, occurrenceNumber,
                )
            }
            val importIdentifier = identify(input)
            // UC-002 BR-001: an Import Identifier already held in the Bank Account is a collision; the whole file is rejected and nothing of it is stored (A6).
            if (!held.add(importIdentifier)) return FileCounting.Collision(importIdentifier)
            inserts += NewBankTransaction(
                stableReference = transaction.reference.takeIf { alone },
                referenceKind = if (alone) transaction.referenceKind else StableReferenceKind.None,
                occurrenceNumber = occurrenceNumber,
                importIdentifier = importIdentifier,
                bookedDate = transaction.bookedDate,
                amount = transaction.amount,
                currency = transaction.currency,
                payee = transaction.payee,
                memo = transaction.memo,
                firstSeenAt = file.importedAt,
            )
        }
        return FileCounting.Counted(inserts)
    }
}
