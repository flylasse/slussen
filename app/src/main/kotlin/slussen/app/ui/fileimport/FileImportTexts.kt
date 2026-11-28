package slussen.app.ui.fileimport

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.core.fileimport.FileImportOutcome
import slussen.core.fileimport.RejectionCause
import slussen.core.fileimport.TakenInTransaction
import slussen.core.operation.RunningOperation
import java.time.Instant
import java.time.LocalDate

/** The words of Import a Transaction File (`docs/mockups/import-file.html`, `connections.html#import-first`; UC-002). */
object FileImportTexts {
    /** Step 1 on the Transaction File Source of a Budget tab (`budget.html#care-fund`). */
    const val IMPORT = "Import Transaction File"

    /** A7 on a File Format's page while no Bank Account of it is added (`connections.html#import-first`). */
    const val IMPORT_FIRST = "Import a Transaction File"
    const val IMPORT_FIRST_NOTE = "Importing the first file adds its Bank Account first, already mapped."

    /** Steps 2-3 (`#choose`): Android's document dialog is open for the Bank Account named below. */
    const val CHOOSE = "Choose a Transaction File"

    /** S-B-34: two or more Bank Accounts of the File Format in one Budget; the Operator chooses the one the file is for. */
    const val WHICH_BANK_ACCOUNT = "For which Bank Account is the file?"
    const val CANCEL = "Cancel"

    /** Step 4: the chosen file is read, off the screen's thread, before anything is converted or stored. */
    const val READING = "Reading the Transaction File…"

    const val WITHDRAW = "Withdraw"
    const val ACCOUNT_MAPPINGS = "Account Mappings"

    /** Steps 4-9: the import has the lane. */
    fun importing(bankAccount: String, budgetAppName: String) = "Importing the Transaction File for $bankAccount… then comparing with $budgetAppName"

    /** ADR-007 §1: the import waits for the lane behind [other]. */
    fun waiting(other: RunningOperation, bankAccount: String) = "Waits for: ${Texts.operation(other)} · then imports the Transaction File for $bankAccount"

    private const val NOTHING_TAKEN_IN = "Nothing taken in · previous import unchanged"

    private fun unchanged(bankAccount: String) = "The stored Bank Transactions of $bankAccount are unchanged."

    /** A1 (`#cancel`). */
    fun noFileChosen(bankAccount: String) = AnswerUi("No file chosen", AnswerKind.Unchanged, unchanged(bankAccount))

    /** NFR-023: no app on the device answers the document dialog. */
    val noApp = AnswerUi("No app on this device can choose a file", AnswerKind.Failed, "Nothing was imported. Install or switch on a files app, then import again.")

    /** NFR-023: the provider of the chosen document fails, the document is gone or its permission was taken back. */
    fun notRead(bankAccount: String) = AnswerUi(
        "The Transaction File could not be read",
        AnswerKind.Failed,
        "Nothing taken in · the stored Bank Transactions of $bankAccount are unchanged. Choose the file again.",
    )

    /** ADR-007 §1: the import was withdrawn while it waited for the lane. */
    fun withdrawn(bankAccount: String) = AnswerUi("Import withdrawn", AnswerKind.Unchanged, unchanged(bankAccount))

    /** The answer of the core's [outcome] for [bankAccount]; [named] names the Bank Account in the count (S-B-34, `#told-apart`). */
    fun answer(outcome: FileImportOutcome, bankAccount: String, named: Boolean, budgetAppName: String, formats: Formats): AnswerUi = when (outcome) {
        is FileImportOutcome.Imported -> {
            val at = formats.dateTime(outcome.importedAt)
            val count = if (named || outcome.takenIn.any { it.identical != null }) "new for ${outcome.bankAccountName}: ${outcome.takenIn.size}" else "new: ${outcome.takenIn.size}"
            val freshness = "Freshness: data as of the Transaction File imported $at"
            // Step 9 of UC-002, A1 of UC-003: a Comparison that could not read the Budget App keeps the import, not compared yet.
            val notCompared = outcome.takenIn.any { it.status == null }
            val compared = if (notCompared) " · $budgetAppName could not be read: the new Bank Transactions are not compared yet" else ""
            AnswerUi("Imported $at · $count", AnswerKind.Done, freshness + compared)
        }
        is FileImportOutcome.Rejected -> {
            val notRecorded = if (outcome.recorded) "" else " · The Source Health does not show this rejection: it could not be recorded."
            when (outcome.cause) {
                // A3 (`#no-importable`).
                RejectionCause.NoImportableTransaction ->
                    AnswerUi("Transaction File rejected: it holds no importable transaction", AnswerKind.Failed, "Only pending or zero amounts · $NOTHING_TAKEN_IN$notRecorded")
                // A6 (`#collision`).
                RejectionCause.ImportIdentifierCollision ->
                    AnswerUi("Transaction File rejected: Import Identifier collision", AnswerKind.Failed, NOTHING_TAKEN_IN + notRecorded)
                // A2 (`#rejected`): the reason as the core returns it, already cut (S-B-36).
                RejectionCause.NotConverted, RejectionCause.NoUsableReference, RejectionCause.OtherCurrency ->
                    AnswerUi("Transaction File rejected as a whole", AnswerKind.Failed, "${outcome.reason} · $NOTHING_TAKEN_IN$notRecorded")
            }
        }
        // A5 (`#retired`).
        is FileImportOutcome.RetiredAccount ->
            AnswerUi("${outcome.bankAccountName} is a Retired Account", AnswerKind.Failed, "Restore it first to import a Transaction File for it.")
        FileImportOutcome.NotAFileImportedBankAccount ->
            AnswerUi("Transaction File not imported", AnswerKind.Failed, "The transactions of $bankAccount do not come from a File Format of this build. Nothing taken in.")
        // ADR-002 §3 O-3, NFR-023: whether the Source Health now shows the failure.
        is FileImportOutcome.NotStored -> AnswerUi(
            "Transaction File not imported",
            AnswerKind.Failed,
            "The file could not be stored; none of its transactions was taken in · " +
                if (outcome.recordedAsRejected) "The Source Health shows the failure." else "The Source Health does not show the failure: it could not be recorded either. Import the file again.",
        )
        is FileImportOutcome.Unavailable -> AnswerUi("Transaction File not imported", AnswerKind.Failed, "${Texts.refusal(outcome.reason)} Nothing taken in.")
    }

    /** Step 10 (`#imported`, `#told-apart`): one Bank Transaction taken in, with its Transaction Status. */
    fun line(taken: TakenInTransaction, formats: Formats, today: LocalDate): TakenInLine {
        val payee = taken.payee.orEmpty()
        val identical = taken.identical?.let {
            if (it.number == 1) "${it.number} of ${it.of} · identical" else "${it.number} of ${it.of}: kept as a separate Bank Transaction, never merged"
        }
        return TakenInLine(
            day = formats.day(taken.bookedDate, today),
            text = listOfNotNull(payee.takeIf { it.isNotEmpty() }, identical).joinToString(" · "),
            amount = formats.difference(taken.amount),
            status = taken.status?.text ?: "not compared yet",
        )
    }

    /** UC-002 BR-006: the Freshness of a file-imported Bank Account or Source. */
    fun freshness(at: Instant?, formats: Formats) =
        at?.let { "Freshness: data as of the Transaction File imported ${formats.dateTime(it)}" } ?: "Freshness: no Transaction File imported yet"
}
