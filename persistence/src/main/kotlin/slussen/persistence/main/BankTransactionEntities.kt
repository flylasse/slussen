package slussen.persistence.main

import slussen.core.fetch.NewBankTransaction
import slussen.core.port.NewUnreadableTransaction
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.UnreadableTransactionStatus
import slussen.persistence.UnreadableStoredData
import slussen.persistence.valueOfText
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

private const val MAIN_PART = "slussen.db"

/** A currency read back that is not three capital letters is unreadable data (ADR-002 §8). */
private fun currencyOf(text: String): CurrencyCode =
    try {
        CurrencyCode(text)
    } catch (e: IllegalArgumentException) {
        throw UnreadableStoredData(MAIN_PART)
    }

/** `bank_transaction` read back as the core's [StoredBankTransaction]; an unknown `Values:` text is unreadable data. */
internal fun BankTransactionEntity.stored() = StoredBankTransaction(
    id = id,
    bankAccountId = bankAccountId,
    stableReference = stableReference,
    referenceKind = valueOfText<StableReferenceKind>(referenceKind, MAIN_PART) { it.text },
    occurrenceNumber = occurrenceNumber,
    importIdentifier = importIdentifier,
    bookedDate = bookedDate,
    amount = Amount(amount),
    currency = currencyOf(currency),
    payee = payee,
    memo = memo,
    firstSeenAt = firstSeenAt,
    status = status?.let { text -> valueOfText<TransactionStatus>(text, MAIN_PART) { it.text } },
    transferId = transferId,
)

/** A stored Bank Transaction with changed content, written back to its own row by id. */
internal fun StoredBankTransaction.entity() = BankTransactionEntity(
    id = id,
    bankAccountId = bankAccountId,
    stableReference = stableReference,
    referenceKind = referenceKind.text,
    occurrenceNumber = occurrenceNumber,
    importIdentifier = importIdentifier,
    bookedDate = bookedDate,
    amount = amount.thousandths,
    currency = currency.code,
    payee = payee,
    memo = memo,
    firstSeenAt = firstSeenAt,
    status = status?.text,
    transferId = transferId,
)

/** A new Bank Transaction of [bankAccountId], with no Transaction Status until a Comparison (UC-003 A1, S-T-08). */
internal fun NewBankTransaction.entity(bankAccountId: Long) = BankTransactionEntity(
    bankAccountId = bankAccountId,
    stableReference = stableReference,
    referenceKind = referenceKind.text,
    occurrenceNumber = occurrenceNumber,
    importIdentifier = importIdentifier,
    bookedDate = bookedDate,
    amount = amount.thousandths,
    currency = currency.code,
    payee = payee,
    memo = memo,
    firstSeenAt = firstSeenAt,
    status = null,
    transferId = null,
)

/** `unreadable_transaction` read back as the core's [StoredUnreadableTransaction]. */
internal fun UnreadableTransactionEntity.stored() = StoredUnreadableTransaction(
    id = id,
    bankAccountId = bankAccountId,
    bookedDate = bookedDate,
    amount = amount?.let(::Amount),
    currency = currency?.let(::currencyOf),
    payee = payee,
    memo = memo,
    reason = reason,
    firstSeenAt = firstSeenAt,
    status = valueOfText<UnreadableTransactionStatus>(status, MAIN_PART) { it.text },
)

/** A new Unreadable Transaction of [bankAccountId], with the status Needs Review (UC-001 A17, BR-016). */
internal fun NewUnreadableTransaction.entity(bankAccountId: Long, firstSeenAt: Instant) = UnreadableTransactionEntity(
    bankAccountId = bankAccountId,
    bookedDate = bookedDate,
    amount = amount?.thousandths,
    currency = currency?.code,
    payee = payee,
    memo = memo,
    reason = reason.text,
    firstSeenAt = firstSeenAt,
    status = UnreadableTransactionStatus.NeedsReview.text,
)
