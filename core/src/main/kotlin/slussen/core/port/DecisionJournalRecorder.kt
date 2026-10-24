package slussen.core.port

import java.time.Instant

/** The `Values:` of `DECISION_JOURNAL_ENTRY.event_type`. */
enum class JournalEventKind(val text: String) {
    SessionStart("Session Start"),
    ConfigurationChange("Configuration Change"),
    TransactionSeen("Transaction Seen"),
    TransactionChanged("Transaction Changed"),
    StatusChanged("Status Changed"),
    OperatorDecision("Operator Decision"),
    UploadPlan("Upload Plan"),
    Upload("Upload"),
    BudgetAppOutcome("Budget App Outcome"),
    AccountMappingChange("Account Mapping Change"),
    JournalControl("Journal Control"),
}

/**
 * One `DECISION_JOURNAL_ENTRY`. [details] never holds credentials, Bank Consent data, Bank Account numbers or the
 * identifiers Data Providers give Bank Accounts (entity model constraints, NFR-012).
 */
data class JournalEntry(
    val recordedAt: Instant,
    val eventKind: JournalEventKind,
    val details: String,
    val importIdentifier: String? = null,
    val bankAccountName: String? = null,
)

/**
 * Write-only (ADR-001 §3, ADR-002 §9): returns `Unit` and never throws to the caller; a failure is caught, counted and
 * dropped by the implementation (NFR-006).
 */
interface DecisionJournalRecorder {
    fun record(entry: JournalEntry)
}
