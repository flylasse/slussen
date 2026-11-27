package slussen.core.port

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/**
 * One transaction of a Transaction File as O-3 counts it against the stored Bank Transactions (UC-002 steps 5-6): a
 * booked one with an amount other than zero, in the Bank Account's currency, with the reference its File Pre-processor
 * gave it and whether the Bank supplied it or the File Pre-processor derived it (BR-002).
 */
data class FileTransaction(
    /** Never empty; opaque like a Stable Reference (ADR-008 §2). */
    val reference: String,
    /** [StableReferenceKind.Supplied] or [StableReferenceKind.Derived], never [StableReferenceKind.None]. */
    val referenceKind: StableReferenceKind,
    val bookedDate: LocalDate,
    val amount: Amount,
    /** The Bank Account's currency: a transaction without one is in it, one in another rejects the file (A2). */
    val currency: CurrencyCode,
    val payee: String?,
    val memo: String?,
) {
    init {
        require(referenceKind != StableReferenceKind.None) { "Every transaction of a Transaction File has a reference (UC-002 BR-002)." }
    }
}

/**
 * What O-3 `FileImportStore.importWhole` is given (ADR-002 §3): the transactions a Transaction File delivered for one
 * Bank Account, in the order of the file, as the `transaction_file` record Imported counts them.
 */
data class TransactionFileToImport(
    val bankAccountId: Long,
    /** `TRANSACTION_FILE.file_format_id`: the File Format of the Bank Account. */
    val fileFormatId: Long,
    /** The Source Key of the File Format, which enters every Import Identifier (ADR-008 §2). */
    val sourceKey: String,
    /** `TRANSACTION_FILE.imported_at`, and the `first_seen_at` of every new Bank Transaction. */
    val importedAt: Instant,
    /** Every transaction the file delivered (UC-002 step 7: `transaction_count` is their number). */
    val transactions: List<FileTransaction>,
)

/** A Transaction File recorded as rejected (UC-002 A2, A3, A6): `transaction_count` 0 and its reason. */
data class RejectedTransactionFile(
    val bankAccountId: Long,
    val fileFormatId: Long,
    val importedAt: Instant,
    /** At most 200 characters (`TRANSACTION_FILE.rejection_reason`), never a value of the file. */
    val reason: String,
)

/** What O-3 did with a Transaction File. */
sealed interface WholeImport {
    /**
     * The file is stored Imported with every new Bank Transaction: [takenIn] are those taken in for the first time, in
     * the order of the file (UC-002 steps 7-8); a transaction counted as a stored one is not among them (BR-003).
     */
    data class Imported(val takenIn: List<ReceivedBankTransaction>) : WholeImport

    /**
     * A new transaction's Import Identifier [importIdentifier] is held by a stored Bank Transaction of the Bank Account
     * (UC-002 A6, ADR-008 §5): nothing of the file is stored, and the core records the file as rejected.
     */
    data class Collision(val importIdentifier: String) : WholeImport
}

/**
 * The storage of Import a Transaction File (UC-002) in `slussen.db` (ADR-002 §1, §3). Each method is one transaction.
 */
interface FileImportStore {
    /**
     * O-3 (ADR-002 §3), all or nothing: counts the transactions of [file] against the stored Bank Transactions of its
     * Bank Account as UC-002 BR-003 says, from what is stored inside the same transaction, by the core's rule
     * ([slussen.core.fileimport.TransactionFileCounting]); then stores every new Bank Transaction with its
     * `occurrence_number` and Import Identifier, the `transaction_file` record Imported with the number the file
     * delivered, and raises `state_version` when a Bank Transaction is new. A collision stores nothing.
     */
    suspend fun importWhole(file: TransactionFileToImport): WholeImport

    /** The `transaction_file` record Rejected with its reason and `transaction_count` 0; nothing else is changed. */
    suspend fun recordRejected(file: RejectedTransactionFile)
}
