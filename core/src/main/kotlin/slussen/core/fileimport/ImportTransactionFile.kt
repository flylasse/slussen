package slussen.core.fileimport

import kotlinx.coroutines.CancellationException
import slussen.core.comparison.Comparison
import slussen.core.comparison.ComparisonReport
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchStore
import slussen.core.port.FileImportStore
import slussen.core.port.FileTransaction
import slussen.core.port.JournalEventKind
import slussen.core.port.ReceivedBankTransaction
import slussen.core.port.RejectedTransactionFile
import slussen.core.port.StableReferenceKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankAccount
import slussen.core.port.TransactionFileToImport
import slussen.core.port.WholeImport
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.fileformat.ReferenceKind
import slussen.pluginapi.fileformat.StandardImportTransaction
import slussen.pluginapi.value.Amount
import java.time.Clock
import java.time.Instant

/**
 * Import a Transaction File (UC-002), `:core` `fileimport` (ADR-001 §7): for one Bank Account whose transactions come
 * from Transaction Files, the conversion of the whole file by the File Pre-processor of its File Format into the Standard
 * Import Format (step 4, BR-007), the booked transactions with an amount other than zero (step 5), their references
 * (step 6, BR-002), the file stored whole or rejected whole through O-3 (step 7, BR-001, BR-003, A2, A3, A4, A6), the
 * Decision Journal (step 8), the Comparison with the Budget App in the same operation (step 9, UC-003) and what the
 * screens show of it (step 10, BR-006). A Retired Account is refused (A5). The choice of the file (steps 2-3, A1) and
 * adding a Bank Account first (A7) are the screen's; this is called with the bytes of the chosen file.
 */
class ImportTransactionFile(
    private val plugins: RegisteredPlugins,
    /** The File Pre-processors of this build; each is found by the `source_key` of its File Format (ADR-001 §4.3). */
    private val filePreprocessors: List<FilePreprocessor>,
    private val connections: ConnectionStore,
    private val fileImports: FileImportStore,
    /** Read after the import for the Transaction Status of each Bank Transaction taken in (step 10). */
    private val fetches: FetchStore,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
    private val clock: Clock,
    /** Step 9: the Comparison with the Budget App (UC-003) that follows the import within the same operation. */
    private val comparison: Comparison? = null,
) {
    /** A rejection the core decides, with the reason it shows and stores. */
    private class Rejection(val cause: RejectionCause, val reason: String)

    /** Steps 1-10: the Operator imports [file], the bytes of the chosen Transaction File, for the Bank Account [bankAccountId]. */
    suspend fun import(bankAccountId: Long, file: ByteArray): FileImportOutcome {
        // UC-006 BR-005: While an Upload Plan awaits confirmation or an Upload is written, no Transaction File is imported (ADR-007 §2).
        val result = coordinator.run(OperationKind.FileImport, StartedBy.Operator) { permit -> imported(bankAccountId, file, permit) }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> FileImportOutcome.Unavailable(result.reason)
        }
    }

    private suspend fun imported(bankAccountId: Long, file: ByteArray, permit: OperationPermit): FileImportOutcome {
        val bankAccount = try {
            connections.bankAccounts().firstOrNull { it.id == bankAccountId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return FileImportOutcome.NotStored(recordedAsRejected = false)
        }
        val fileFormat = bankAccount?.fileFormatId?.let { id -> plugins.fileFormats.firstOrNull { it.id == id } }
            ?: return FileImportOutcome.NotAFileImportedBankAccount
        val preprocessor = filePreprocessors.firstOrNull { it.profile.sourceKey == fileFormat.sourceKey }
            ?: return FileImportOutcome.NotAFileImportedBankAccount
        // A5: the import for a Retired Account is refused; it must be restored first (UC-009).
        if (bankAccount.retired) return FileImportOutcome.RetiredAccount(bankAccount.name)
        // ADR-007 §1: the screens show which Bank Account the running import is for.
        permit.workingOn(bankAccount.name)
        val importedAt = clock.instant()

        val converted = when (val conversion = convert(preprocessor, file)) {
            is ConversionResult.Converted -> conversion.file.transactions
            // UC-002 BR-001: A file that cannot be converted completely never brings in part of its transactions (A2).
            is ConversionResult.Rejected -> return reject(bankAccount, fileFormat.id, importedAt, Rejection(RejectionCause.NotConverted, reasonOf(conversion.reason)))
            null -> return reject(bankAccount, fileFormat.id, importedAt, Rejection(RejectionCause.NotConverted, PREPROCESSOR_FAILED))
        }
        unusable(converted, bankAccount)?.let { return reject(bankAccount, fileFormat.id, importedAt, it) }

        // Step 5 (UC-001 BR-002, BR-003): only the booked transactions with an amount other than zero are kept.
        val kept = converted.filter { it.booked && it.amount.thousandths != 0L }
        // A3: no booked transaction with an amount other than zero is left: the file is rejected with that reason.
        if (kept.isEmpty()) return reject(bankAccount, fileFormat.id, importedAt, Rejection(RejectionCause.NoImportableTransaction, NO_IMPORTABLE_TRANSACTION))

        val toImport = TransactionFileToImport(
            bankAccountId = bankAccount.id,
            fileFormatId = fileFormat.id,
            sourceKey = fileFormat.sourceKey,
            importedAt = importedAt,
            transactions = kept.map { fileTransactionOf(it, bankAccount) },
        )
        // UC-002 BR-001: A Transaction File is taken in as a whole or rejected as a whole, in one storage operation (O-3).
        val stored = try {
            fileImports.importWhole(toImport)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // ADR-002 §3 O-3: a failure stores none of it, and the core records the file as rejected in a separate step.
            val recorded = record(RejectedTransactionFile(bankAccount.id, fileFormat.id, importedAt, NOT_STORED))
            return FileImportOutcome.NotStored(recordedAsRejected = recorded)
        }
        val takenIn = when (stored) {
            is WholeImport.Imported -> stored.takenIn
            is WholeImport.Collision -> {
                // ADR-008 §5: a collision is recorded in the Decision Journal as a Status Changed event.
                journal.record(JournalEventKind.StatusChanged, "Transaction File rejected: Import Identifier collision", stored.importIdentifier, bankAccount.name)
                return reject(bankAccount, fileFormat.id, importedAt, Rejection(RejectionCause.ImportIdentifierCollision, COLLISION))
            }
        }
        journalTakenIn(bankAccount, takenIn)

        // Step 9: the stored Bank Transactions are compared with the Budget App in the same operation; a Comparison that
        // fails keeps the import, and its new Bank Transactions are then not compared yet (UC-003 A1).
        val compared: ComparisonReport? = try {
            comparison?.compare(permit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
        val comparisonFailed = comparison != null && compared == null
        return FileImportOutcome.Imported(
            bankAccountName = bankAccount.name,
            delivered = kept.size,
            takenIn = withStatuses(bankAccount.id, takenIn),
            comparison = compared,
            comparisonFailed = comparisonFailed,
        )
    }

    /** Step 4: the whole file converted by the File Pre-processor; null when it fails instead of answering. */
    private fun convert(preprocessor: FilePreprocessor, file: ByteArray): ConversionResult? =
        try {
            preprocessor.convert(file)
        } catch (failure: Exception) {
            null
        } catch (notBuilt: NotImplementedError) {
            null
        }

    /**
     * A2: the first transaction of the converted file that makes the whole file unusable: one without a reference Slussen
     * can use (BR-002), or one in another currency than the Bank Account's. Every transaction of the file is checked, also
     * one step 5 would leave out.
     */
    private fun unusable(converted: List<StandardImportTransaction>, bankAccount: StoredBankAccount): Rejection? {
        converted.forEachIndexed { index, transaction ->
            val position = index + 1
            // UC-002 BR-002: Every transaction of the file has a reference; one that is empty or cannot be encoded rejects the file, never completed by guessing.
            if (transaction.reference.isEmpty() || !ImportIdentifierProtocol.isEncodable(transaction.reference)) {
                return Rejection(RejectionCause.NoUsableReference, "Transaction $position of the file has no reference Slussen can use.")
            }
            // A2 (S-B-32): a transaction in another currency than the Bank Account's rejects the whole file; one without a currency is in it.
            val currency = transaction.currency
            if (currency != null && currency != bankAccount.currency) {
                return Rejection(RejectionCause.OtherCurrency, "Transaction $position of the file is in another currency than the Bank Account's.")
            }
        }
        return null
    }

    /** Step 6: a kept transaction with its reference as the File Pre-processor gives it and its currency (A2). */
    private fun fileTransactionOf(transaction: StandardImportTransaction, bankAccount: StoredBankAccount) = FileTransaction(
        reference = transaction.reference,
        referenceKind = when (transaction.referenceKind) {
            ReferenceKind.Supplied -> StableReferenceKind.Supplied
            ReferenceKind.Derived -> StableReferenceKind.Derived
        },
        bookedDate = transaction.bookedDate,
        amount = transaction.amount,
        currency = transaction.currency ?: bankAccount.currency,
        payee = transaction.payee,
        memo = transaction.memo,
    )

    /** A2 step 2 (S-B-36): the reason shown and stored, its first 200 characters; an empty one is given Slussen's own. */
    private fun reasonOf(given: String): String {
        if (given.isBlank()) return NOT_IN_ITS_FORMAT
        if (given.length <= REASON_MAX_LENGTH) return given
        val end = if (Character.isHighSurrogate(given[REASON_MAX_LENGTH - 1])) REASON_MAX_LENGTH - 1 else REASON_MAX_LENGTH
        return given.substring(0, end)
    }

    /**
     * A2 steps 1-3, A3, A6: the file is rejected as a whole, none of its transactions is taken in, and it is recorded as
     * rejected with its reason; the Bank Transactions of the previous import stay as they are. The reason is never logged
     * here: a File Pre-processor's reason is shown and stored only (ADR-001 §4.3).
     */
    private suspend fun reject(bankAccount: StoredBankAccount, fileFormatId: Long, importedAt: Instant, rejection: Rejection): FileImportOutcome {
        val recorded = record(RejectedTransactionFile(bankAccount.id, fileFormatId, importedAt, rejection.reason))
        return FileImportOutcome.Rejected(bankAccount.name, rejection.cause, rejection.reason, recorded)
    }

    /** The file recorded as rejected; false when the storage cannot be written (NFR-023). */
    private suspend fun record(rejected: RejectedTransactionFile): Boolean =
        try {
            fileImports.recordRejected(rejected)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }

    // UC-011 BR-001: Every Bank Transaction received from a Transaction File for the first time is recorded, naming its Bank Account and its Import Identifier.
    /**
     * Step 8: one Decision Journal entry per Bank Transaction taken in for the first time, in Slussen's own words with the
     * date and the amount, never a payee or memo of the file (NFR-012). Recording never blocks or changes the import
     * (UC-011 BR-002): the Decision Journal hands each entry to its recorder and drops what fails.
     */
    private suspend fun journalTakenIn(bankAccount: StoredBankAccount, takenIn: List<ReceivedBankTransaction>) {
        takenIn.forEach { received ->
            val details = "Bank Transaction received for the first time: booked ${received.bookedDate}, ${amountText(received.amount)} ${received.currency.code}"
            journal.record(JournalEventKind.TransactionSeen, details, received.importIdentifier, bankAccount.name)
        }
    }

    /**
     * Step 10: each Bank Transaction taken in with its Transaction Status as stored now, read in a call of its own after
     * the import; when that read fails, each is shown as not compared yet, and the import stays.
     */
    private suspend fun withStatuses(bankAccountId: Long, takenIn: List<ReceivedBankTransaction>): List<TakenInTransaction> {
        val statuses = try {
            fetches.bankTransactionsOf(bankAccountId).associate { it.importIdentifier to it.status }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emptyMap()
        }
        return takenIn.map { TakenInTransaction(it.importIdentifier, it.bookedDate, it.amount, statuses[it.importIdentifier]) }
    }

    /** An amount as signed units with three decimals, the entity model's precision `12,3`: -12.340. */
    private fun amountText(amount: Amount): String {
        val thousandths = amount.thousandths
        val units = Math.abs(thousandths) / THOUSANDTHS
        val rest = (Math.abs(thousandths) % THOUSANDTHS).toString().padStart(3, '0')
        return "${if (thousandths < 0) "-" else ""}$units.$rest"
    }

    private companion object {
        /** `TRANSACTION_FILE.rejection_reason` holds at most 200 characters (entity model). */
        const val REASON_MAX_LENGTH = 200

        /** Thousandths in one currency unit (ADR-001 §4 `Amount`). */
        const val THOUSANDTHS = 1000L

        const val NOT_IN_ITS_FORMAT = "The file does not follow its File Format."
        const val PREPROCESSOR_FAILED = "The File Pre-processor could not read the file."
        const val NO_IMPORTABLE_TRANSACTION = "The file holds no importable transaction: no booked transaction with an amount other than zero."

        /** A6: the reason the use case names. */
        const val COLLISION = "Import Identifier collision"
        const val NOT_STORED = "The file could not be stored; none of its transactions was taken in."
    }
}
