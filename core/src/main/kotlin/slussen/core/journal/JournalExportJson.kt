package slussen.core.journal

import slussen.core.port.JournalEntry
import slussen.pluginapi.value.Amount
import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** One of the Budget App's transactions as a Journal Export holds it (UC-011 BR-008), before codes are given. */
internal data class ExportedTransaction(
    val budget: String,
    val budgetAccount: String,
    val date: LocalDate,
    val payee: String?,
    val memo: String?,
    val amount: Amount,
    /** Slussen's Import Identifier, only when it begins with Slussen's marker (UC-003 BR-008). */
    val importIdentifier: String?,
    /** An import of an Other Import Path, whose Import Identifier is not exported (BR-007). */
    val importedByOtherPath: Boolean,
)

// UC-011 BR-005: The same payee or memo text always gets the same code within one Journal Export, numbered within it.
/**
 * The codes of one Journal Export (ADR-008, "Payee and memo codes of a Journal Export"): numbered in order of first
 * appearance, `P1`, `P2`, … for payees and `M1`, `M2`, … for memos, the same text always getting the same code within
 * this export. The table from text to code lives only in this object, which is discarded with the export.
 */
internal class ExportCodes {
    private val payees = HashMap<String, String>()
    private val memos = HashMap<String, String>()

    fun payee(text: String?): String? = text?.let { payees.getOrPut(it) { "P${payees.size + 1}" } }

    fun memo(text: String?): String? = text?.let { memos.getOrPut(it) { "M${memos.size + 1}" } }
}

// UC-011 BR-003: A Journal Export holds no credentials, Bank Consent data, Bank Account numbers, identifiers Data Providers give Bank Accounts or Data Provider error texts: it writes only names, dates, amounts, Slussen's Import Identifiers and the entries.
/**
 * Writes a Journal Export as one JSON file (UC-011 BR-007), part by part in the order of BR-007, straight to [out], so
 * that no part is held in memory longer than it takes to write it: the header, the entries, the Budget App's
 * transactions and the closing part. A file without its closing part is incomplete. It writes only what it is given;
 * what may be given is decided by [JournalExport].
 */
internal class JournalExportJson(private val out: Writer) {
    private var entries = 0
    private var transactions = 0

    // UC-011 BR-007: The header says what the file is, the version that wrote it, the time, whether codes replace payee and memo, the first date and where each served Budget's transactions come from.
    fun header(versionName: String, exportedAt: Instant, codes: Boolean, firstDate: LocalDate, budgets: List<ExportedBudget>) {
        out.write("{\n\"header\":{")
        field("file", FILE_IS)
        out.write(",")
        field("versionName", versionName)
        out.write(",")
        field("exportedAt", exportedAt.toString())
        out.write(",\"codes\":")
        out.write(codes.toString())
        out.write(",")
        field("firstDate", firstDate.toString())
        out.write(",\"budgets\":[")
        budgets.forEachIndexed { index, budget ->
            if (index > 0) out.write(",")
            budget(budget)
        }
        out.write("]},\n\"entries\":[")
    }

    private fun budget(budget: ExportedBudget) {
        out.write("\n{")
        field("name", budget.name)
        out.write(",")
        field("currency", budget.currency.code)
        out.write(",")
        when (budget) {
            is ExportedBudget.Read -> {
                field("source", "Read at the export")
                out.write(",")
                field("readAt", budget.readAt.toString())
            }
            is ExportedBudget.FromStoredCopy -> {
                field("source", "Stored copy")
                out.write(",")
                field("lastReadAt", budget.lastReadAt.toString())
                out.write(",")
                field("reason", reason(budget.why))
            }
            is ExportedBudget.NeverRead -> {
                field("source", "None")
                out.write(",")
                field("reason", "${reason(budget.why)} It was never read, so none of its transactions are in this export.")
            }
        }
        out.write("}")
    }

    /** One entry as the Decision Journal holds it: its time, kind, Bank Account and Import Identifier, and its details. */
    fun entry(entry: JournalEntry) {
        if (entries > 0) out.write(",")
        out.write("\n{")
        field("recordedAt", entry.recordedAt.toString())
        out.write(",")
        field("kind", entry.eventKind.text)
        out.write(",")
        field("bankAccount", entry.bankAccountName)
        out.write(",")
        field("importIdentifier", entry.importIdentifier)
        out.write(",")
        field("details", entry.details)
        out.write("}")
        entries++
    }

    /** Ends the entries and begins the Budget App's transactions. */
    fun entriesDone() {
        out.write("],\n\"budgetAppTransactions\":[")
    }

    // UC-011 BR-006: A Journal Export holds the recorded facts and the Budget App's transactions only, and computes no measure.
    fun transaction(transaction: ExportedTransaction) {
        if (transactions > 0) out.write(",")
        out.write("\n{")
        field("budget", transaction.budget)
        out.write(",")
        field("budgetAccount", transaction.budgetAccount)
        out.write(",")
        field("date", transaction.date.toString())
        out.write(",")
        field("payee", transaction.payee)
        out.write(",")
        field("memo", transaction.memo)
        out.write(",\"amount\":")
        out.write(decimal(transaction.amount))
        out.write(",")
        field("importIdentifier", transaction.importIdentifier)
        out.write(",\"importedByOtherPath\":")
        out.write(transaction.importedByOtherPath.toString())
        out.write("}")
        transactions++
    }

    // UC-011 BR-007: A Journal Export ends with a closing part that counts its entries, the Budget App's transactions and the entries noted at step 3 that were removed while the file was written; a file without it is incomplete.
    fun closing(removedWhileWritten: Int) {
        out.write("],\n\"closing\":{\"entries\":$entries,\"budgetAppTransactions\":$transactions,\"entriesRemovedWhileWritten\":$removedWhileWritten}\n}\n")
    }

    val entriesWritten: Int get() = entries
    val transactionsWritten: Int get() = transactions

    private fun field(name: String, value: String?) {
        string(name)
        out.write(":")
        if (value == null) out.write("null") else string(value)
    }

    /** [text] as a JSON string (RFC 8259 §7): quotes, backslashes and every control character escaped. */
    private fun string(text: String) {
        out.write("\"")
        for (c in text) {
            when {
                c == '"' -> out.write("\\\"")
                c == '\\' -> out.write("\\\\")
                c == '\n' -> out.write("\\n")
                c == '\r' -> out.write("\\r")
                c == '\t' -> out.write("\\t")
                c < ' ' || c == ' ' || c == ' ' -> out.write("\\u%04x".format(c.code))
                else -> out.write(c.code)
            }
        }
        out.write("\"")
    }

    companion object {
        const val FILE_IS = "Slussen Journal Export: the Decision Journal with the Budget App's transactions of the served Budgets"

        /** An amount in the Budget's currency with the decimals it needs, negative for an outflow (BR-008). */
        fun decimal(amount: Amount): String = BigDecimal.valueOf(amount.thousandths, 3).stripTrailingZeros().toPlainString()

        // A2 of UC-011: why a Budget's transactions come from the stored copy, or are not in the export, in Slussen's own words, never the Budget App's.
        fun reason(why: BudgetNotRead): String = when (why) {
            BudgetNotRead.CredentialsMissing -> "No credentials for the Budget App are entered, so it was not contacted."
            BudgetNotRead.CredentialsRefused ->
                "The Budget App refused Slussen's credentials, so it was not read; enter new ones on its page in Settings."
            is BudgetNotRead.NoAnswer -> when (why.kind) {
                slussen.pluginapi.request.FailureKind.Timeout -> "The Budget App did not answer within the request timeout, an Operator Setting."
                slussen.pluginapi.request.FailureKind.ConnectionLost -> "The Budget App could not be reached: the connection was lost."
                slussen.pluginapi.request.FailureKind.ServiceUnavailable -> "The Budget App could not serve the read then."
                slussen.pluginapi.request.FailureKind.UnreadableAnswer -> "The Budget App's answer could not be read."
                slussen.pluginapi.request.FailureKind.Incomplete -> "The Budget App's answer was incomplete."
                // Kinds of a Data Provider's read through a Bank Session; the Budget App gives neither.
                slussen.pluginapi.request.FailureKind.PeriodNotGiven, slussen.pluginapi.request.FailureKind.NotHeldByBankSession ->
                    "The Budget App's answer could not be read."
                // S-B-79: the Budget App's refusal for a reason of its own, told with that reason.
                slussen.pluginapi.request.FailureKind.RefusedSubscriptionLapsed -> "The Budget App refused the read: the subscription of its account has lapsed."
                slussen.pluginapi.request.FailureKind.RefusedTrialEnded -> "The Budget App refused the read: the trial of its account has ended."
                slussen.pluginapi.request.FailureKind.RefusedDataLimitReached -> "The Budget App refused the read: it would exceed a data limit of the Budget App."
                slussen.pluginapi.request.FailureKind.RefusedAddressUnconfirmed -> "The Budget App refused the read: the email address of its account is not confirmed."
                slussen.pluginapi.request.FailureKind.RefusedOtherReason -> "The Budget App refused the read for a reason of its own."
            }
            BudgetNotRead.NotSent -> "The read of the Budget App was not completed."
        }
    }
}
