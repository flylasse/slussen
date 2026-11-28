package slussen.core.port

import kotlinx.coroutines.Deferred
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

/** What a trimming came to: [removed] oldest entries removed, and whether it is [complete] (within the limit, or none left). */
data class Trimmed(val removed: Int, val complete: Boolean)

/**
 * The Decision Journal's own control (ADR-001 §3, ADR-002 §9; UC-010 A2-A4, BR-003): its size, keeping it within a size
 * limit and clearing it. It reads no entry (Konsist K-07). Every method runs on the journal's own database and thread,
 * after every entry handed to the recorder before it, never inside a transaction of `slussen.db`. [keepWithin] and
 * [clear] only queue and return at once, so that they can be handed over in order while the operation lane is held and
 * awaited after it is released.
 */
interface DecisionJournalControl {
    /** The space the Decision Journal takes on the device: `page_count × page_size` of `journal.db`, in bytes (ADR-002 §9). Throws when it cannot be read. */
    suspend fun sizeBytes(): Long

    /**
     * Queues: while the size is above [limitBytes], the oldest entries (lowest `id`) removed and their space returned,
     * until it is within it or no entry is left; nothing while it is within. A failure part-way keeps what was removed
     * before it and answers [Trimmed.complete] false.
     */
    fun keepWithin(limitBytes: Long): Deferred<Trimmed>

    /**
     * Queues: every entry removed and the space they took returned (UC-010 A3), then [firstEntry], if given, written as the
     * first entry after, all or nothing: when it fails, every entry is kept and what it returns completes with the failure.
     */
    fun clear(firstEntry: JournalEntry?): Deferred<Unit>
}
