package slussen.app.ui.journal

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.core.journal.BudgetNotRead
import slussen.core.journal.ExportFailure
import slussen.core.journal.ExportedBudget
import slussen.core.port.JournalEntry
import slussen.pluginapi.request.FailureKind
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.util.Locale

/** The texts of the Journal Export screen (`journal-export.html`, UC-011), in Slussen's own words. */
object JournalExportTexts {
    const val TITLE = "Journal Export"
    const val HEADING = "Export the Decision Journal"
    const val INTRO = "With the Budget App's current transactions of your Budgets."
    const val CODES = "Replace payee and memo text by codes"
    const val CODES_HINT = "The same text always gets the same code within one export."
    const val CHOOSE = "Choose where to save…"
    const val LATEST = "Latest entries in the export"
    const val NOTHING = "Nothing to export yet"
    const val NOTHING_DETAIL = "The Decision Journal holds no entry"
    const val NO_PLACE = "No place chosen"
    const val NO_FILE = "No file was written."
    const val COMPLETE = "Journal Export complete"
    const val COMPLETE_FROM_COPY = "Journal Export complete, from the stored copy"
    const val FAILED = "Export failed"

    private val count = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.ROOT))

    /** Steps 3-4 (`#writing`): shown from the tap on, before anything is read. */
    fun writing(budgetAppName: String): String = "Reading $budgetAppName… then writing the Journal Export"

    fun to(place: String): String = "To $place"

    /** Step 5, A2 (`#complete`, `#stored-copy`): the entries, the place and, for each Budget not read now, where its transactions come from. */
    fun complete(entries: Int, place: String, budgets: List<ExportedBudget>, budgetAppName: String, formats: Formats, now: Instant): String {
        val notRead = budgets.filter { it !is ExportedBudget.Read }
        val parts = mutableListOf("Entries: ${count.format(entries)} · $place")
        notRead.forEach { budget ->
            parts += when (budget) {
                is ExportedBudget.FromStoredCopy ->
                    "${why(budget.why, budgetAppName)} for ${budget.name}: its transactions from the copy of ${formats.moment(budget.lastReadAt, now)}"
                is ExportedBudget.NeverRead ->
                    "${why(budget.why, budgetAppName)} for ${budget.name}: never read, none of its transactions are in the export"
                is ExportedBudget.Read -> error("A Budget read now is not named here.")
            }
        }
        val read = budgets.filterIsInstance<ExportedBudget.Read>().map { it.name }
        if (notRead.isNotEmpty() && read.isNotEmpty()) parts += "${names(read)} read now"
        return parts.joinToString(" · ")
    }

    /** UC-011 A2 step 2: why a Budget was not read, in Slussen's words, never the Budget App's. */
    fun why(why: BudgetNotRead, budgetAppName: String): String = when (why) {
        BudgetNotRead.CredentialsMissing -> "No $budgetAppName credentials are entered"
        BudgetNotRead.CredentialsRefused -> "$budgetAppName refused Slussen's credentials"
        is BudgetNotRead.NoAnswer ->
            if (why.kind == FailureKind.ConnectionLost) "$budgetAppName could not be reached" else "$budgetAppName could not be read: ${Texts.failure(why.kind)}"
        BudgetNotRead.NotSent -> "The read of $budgetAppName was not completed"
    }

    /** A3 and the other outcomes that leave no Journal Export, with what became of the document at [place]. */
    fun failed(why: ExportFailure, removed: Boolean, place: String): String {
        val reason = when (why) {
            ExportFailure.NothingToExport -> "The Decision Journal holds no entry."
            is ExportFailure.Unavailable -> Texts.refusal(why.reason)
            ExportFailure.JournalNotRead -> "The Decision Journal could not be read."
            ExportFailure.StoredDataNotRead -> "Slussen's stored data could not be read."
            ExportFailure.JournalChangedWhileWritten ->
                "The Decision Journal was trimmed or cleared while it was written, so the file could not hold it; export again."
            ExportFailure.NotWritten -> "The file could not be written completely at the chosen place."
        }
        val began = why == ExportFailure.NotWritten || why == ExportFailure.JournalChangedWhileWritten
        val document = when {
            !removed -> "The file $place is incomplete and is no Journal Export; remove it at the place you chose."
            began -> "The incomplete file was removed; no Journal Export was written."
            else -> NO_FILE
        }
        return "$reason $document"
    }

    /** One of the latest entries (`#complete`): its time and kind, its Bank Account and Import Identifier when it names them, and its details. */
    fun entry(entry: JournalEntry, formats: Formats): String {
        val names = if (entry.bankAccountName != null) " · ${entry.bankAccountName} · ${entry.importIdentifier}:" else " ·"
        return "${formats.time(entry.recordedAt)} ${entry.eventKind.text}$names ${entry.details}"
    }

    private fun names(names: List<String>): String =
        if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
}
