package slussen.core.fileimport

import slussen.core.comparison.ComparisonReport
import slussen.core.operation.OperationRefusal
import slussen.core.port.TransactionStatus
import slussen.pluginapi.value.Amount
import java.time.Instant
import java.time.LocalDate

/** Why a Transaction File was rejected as a whole (UC-002 A2, A3, A6). */
enum class RejectionCause {
    /** A2: the File Pre-processor could not convert the whole file. */
    NotConverted,

    /** A2: a transaction of the file has no reference Slussen can use (BR-002). */
    NoUsableReference,

    /** A2: a transaction of the file is in another currency than the Bank Account's. */
    OtherCurrency,

    /** A3: no booked transaction with an amount other than zero is left. */
    NoImportableTransaction,

    /** A6: an Import Identifier of the file collides with that of a different stored Bank Transaction. */
    ImportIdentifierCollision,
}

/**
 * A4 step 1: a Bank Transaction without a Stable Reference is numbered [number] among the [of] stored Bank Transactions of
 * its Bank Account that are identical to it in date, amount, payee and memo (UC-003 BR-002).
 */
data class IdenticalPosition(val number: Int, val of: Int)

/** One Bank Transaction an import took in for the first time, with the Transaction Status it has now (step 10). */
data class TakenInTransaction(
    val importIdentifier: String,
    val bookedDate: LocalDate,
    val amount: Amount,
    /** Empty while it is not compared yet (UC-003 A1). */
    val status: TransactionStatus?,
    /** The payee as stored, for the list of step 10; null when the file gave none or the stored data could not be read. */
    val payee: String? = null,
    /** A4: its number among the identical ones when it has no Stable Reference; null when it has one. */
    val identical: IdenticalPosition? = null,
)

/** What an import of a Transaction File came to, as the screens show it (UC-002 step 10, A2, A3, A5, A6). */
sealed interface FileImportOutcome {
    /**
     * Steps 7-10: the file is stored Imported. [delivered] is every booked transaction with an amount other than zero
     * it delivered, those already stored included (`transaction_count`); [takenIn] those taken in for the first time,
     * worked out at this import and not stored, each with its Transaction Status after the Comparison. [comparison] is
     * what the Comparison of step 9 came to, null when it could not run at all ([comparisonFailed]); the import stays
     * stored either way and its new Bank Transactions are then not compared yet (UC-003 A1).
     */
    data class Imported(
        val bankAccountName: String,
        /** When the file was recorded as imported: its Source Health and Freshness (BR-006). */
        val importedAt: Instant,
        val delivered: Int,
        val takenIn: List<TakenInTransaction>,
        val comparison: ComparisonReport?,
        val comparisonFailed: Boolean,
    ) : FileImportOutcome

    /**
     * A2, A3, A6: the whole file is rejected and none of its transactions is taken in. [reason] is shown, at most 200
     * characters and never a value of the file from the core's own rules; [recorded] says whether the file was stored as
     * rejected with it (NFR-023: a storage that cannot be written is said at the control).
     */
    data class Rejected(val bankAccountName: String, val cause: RejectionCause, val reason: String, val recorded: Boolean) : FileImportOutcome

    /** A5: the Bank Account is a Retired Account; it must be restored first (UC-009). Nothing is stored. */
    data class RetiredAccount(val bankAccountName: String) : FileImportOutcome

    /** Step 1: no Bank Account of this build whose transactions come from Transaction Files has this id. */
    data object NotAFileImportedBankAccount : FileImportOutcome

    /**
     * NFR-023: the stored data could not be read or written; nothing of the file is taken in. [recordedAsRejected] says
     * whether the file was then recorded as rejected (ADR-002 §3 O-3), so that the screen can say whether the Source
     * Health shows the failure; false also when nothing was written because the stored data could not be read.
     */
    data class NotStored(val recordedAsRejected: Boolean) : FileImportOutcome

    /**
     * NFR-023: the file does not fit into the device's memory for its conversion; nothing is stored, not even as rejected,
     * and the lane is released.
     */
    data object TooLarge : FileImportOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written; nothing is started. */
    data class Unavailable(val reason: OperationRefusal) : FileImportOutcome
}
