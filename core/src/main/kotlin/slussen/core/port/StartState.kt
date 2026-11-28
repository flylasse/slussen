package slussen.core.port

import java.time.Instant

/** How this start began (ADR-002 §6). */
enum class StartKind { Normal, NewInstallation, RestoredBackup, LastGoodCopy }

/** The three stored parts of ADR-002 §1, named for the Operator in ADR-002 §8. */
enum class StoredPart {
    /** `slussen.db`: stored transactions, decisions and settings; holds all Decision-Critical Data. */
    TransactionsDecisionsAndSettings,

    /** `device.db`: service credentials, Bank Consents and Offered Bank Accounts. */
    CredentialsConsentsAndOfferedBankAccounts,

    /** `journal.db`: the Decision Journal. */
    DecisionJournal,
}

/** A part that could not be read at this start, and what Slussen started it from instead (UC-008 A1). */
data class UnreadablePart(val part: StoredPart, val startedFromLastGoodCopy: Boolean)

/** The result of the start sequence of ADR-002 §6, reported by `:persistence` (ADR-001 §3). */
interface StartState {
    val startKind: StartKind
    val unreadableParts: List<UnreadablePart>

    /** This start finished a Start Over (UC-010 A12, ADR-002 §3 O-17): the start of a new installation. */
    val startedOver: Boolean get() = false
}

/** The size of the data Slussen includes in device backups, and the limit Android sets (ADR-002 §5, C-015). */
interface BackupSizeMonitor {
    /** The size of everything that is backed up: `slussen.db` only. */
    fun backedUpBytes(): Long

    /** Android's Auto Backup limit, 25 MB (C-015); a number Android sets, not Slussen. */
    val limitBytes: Long
}

/**
 * The `Values:` of `FIRST_UPLOAD_MATCHING.cause` (stored in the table `recovery_check`, ADR-002 §2), which decides the
 * reason shown for First Upload Matching (UC-008 BR-009).
 */
enum class FirstUploadMatchingCause(val text: String) {
    LastGoodCopy("Last Good Copy"),
    RestoredBackup("Restored Backup"),
    NewInstallation("New Installation"),
}

/** `FIRST_UPLOAD_MATCHING` (entity model) as stored. */
data class StoredFirstUploadMatching(
    val id: Long,
    val cause: FirstUploadMatchingCause,
    val openedAt: Instant,
    val comparedAt: Instant?,
    val confirmedAt: Instant?,
)

/** The First Upload Matchings (ADR-002 §3, O-12). The start sequence of `:persistence` opens them (ADR-002 §6). */
interface FirstUploadMatchingStore {
    /** Every First Upload Matching without `confirmed_at`, oldest first. */
    suspend fun openChecks(): List<StoredFirstUploadMatching>
}

/** The coordinator's mode, kept in `coordinator_state` so that a restart sees it (ADR-007 §2). */
enum class CoordinatorMode { Normal, PlanOpen, Uploading }

/**
 * Reads the coordinator's mode and keeps the deferral of a Scheduled Fetch (ADR-002 §2, `coordinator_state`;
 * ADR-007 §2, §8).
 */
interface CoordinatorStore {
    suspend fun mode(): CoordinatorMode

    /**
     * `scheduled_fetch_deferred`: a Scheduled Fetch fell due while an Upload Plan awaited confirmation or its Upload was
     * written, and no Scheduled Fetch has started since (UC-001 BR-013).
     */
    suspend fun scheduledFetchDeferred(): Boolean

    /** Sets `scheduled_fetch_deferred`; one flag however many Scheduled Fetches fall due meanwhile (UC-001 BR-013). */
    suspend fun deferScheduledFetch()

    /**
     * Records the start of a Scheduled Fetch: in one storage transaction, clears `scheduled_fetch_deferred` and returns
     * whether it was set, so that an ended process neither loses a deferral nor runs it twice (ADR-007 §2, §8).
     */
    suspend fun recordScheduledFetchStart(): Boolean
}
