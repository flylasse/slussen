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
 * Write-only (ADR-001 §3, ADR-002 §9): returns `Unit` at once and never throws to the caller; a failure is caught,
 * counted and dropped by the implementation (NFR-006). The entry is written on the journal's own dispatcher, in the order
 * it was handed over, never inside a transaction of `slussen.db`.
 */
interface DecisionJournalRecorder {
    /**
     * Writes [entry]; then, when [keepWithinBytes] is given, removes the oldest entries while the size of the Decision
     * Journal is above it, as [DecisionJournalControl.keepWithin] does (UC-010 BR-003). Null writes the entry and removes
     * nothing. The recorder never checks `journal_on`: the core decides what is handed to it.
     */
    fun record(entry: JournalEntry, keepWithinBytes: Long?)
}

/**
 * The Decision Journal's own control (ADR-001 §3, ADR-002 §9; UC-010 A2-A4, BR-003): its size, keeping it within a size
 * limit and clearing it. It reads no entry (Konsist K-07). Every method runs on the journal's own database and dispatcher,
 * after every entry handed to the recorder before it, never inside a transaction of `slussen.db`, and throws when the
 * journal's database fails; the core catches that.
 */
interface DecisionJournalControl {
    /** The space the Decision Journal takes on the device: `page_count × page_size` of `journal.db`, in bytes (ADR-002 §9). */
    suspend fun sizeBytes(): Long

    /**
     * While the size is above [limitBytes], removes the oldest entries (lowest `id`) and returns their space, until it is
     * within it or no entry is left; removes nothing while it is within. Returns how many entries were removed.
     */
    suspend fun keepWithin(limitBytes: Long): Int

    /**
     * Removes every entry and returns the space they took (UC-010 A3), then writes [firstEntry], if given, as the first
     * entry after, all or nothing: when it throws, every entry is kept.
     */
    suspend fun clear(firstEntry: JournalEntry?)
}
