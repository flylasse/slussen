package slussen.core.operation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import slussen.core.port.CoordinatorMode
import slussen.core.port.CoordinatorStore
import slussen.core.port.StartedBy

/** The operations of this slice that need admission (ADR-007 §2). */
enum class OperationKind {
    /** Entering, changing or removing service credentials (UC-014). */
    ServiceCredentialChange,

    /** Reading the Budgets, choosing served Budgets and adding Bank Accounts (UC-013). */
    BudgetAndBankAccountChange,

    /** Confirming First Upload Matching (UC-008 A9). */
    FirstUploadMatchingConfirmation,

    /** Changing an Operator Setting (UC-010). */
    OperatorSettingChange,

    /** Changing a Bank Account or its Account Mapping (UC-009). */
    AccountMappingChange,

    /** A Fetch the Operator starts, of one Data Provider or of several (UC-001), with its Comparison (step 10). */
    Fetch,

    /** Reading the Budget App again and comparing (UC-003), or concluding a Comparison left pending (ADR-007 §3a). */
    Comparison,

    /** Importing a Transaction File for one Bank Account (UC-002), with its Comparison (step 9); admitted only in Normal. */
    FileImport,

    /** A Review Decision, its change or its clearing, and deferring an Unreadable Transaction (UC-004). */
    ReviewDecision,

    /** A Transfer Decision or its change, with the Funded By or Transfer Partner the Operator keeps from it (UC-005). */
    TransferDecision,

    /**
     * The read of the Budget App for a Journal Export (UC-011 step 3), admitted as a Comparison's read is (ADR-007 §2).
     * Only that read runs on the lane: the export notes the Decision Journal before it and writes the file after it,
     * with the lane free, so that no decision waits for them.
     */
    JournalExport,
}

/** Why the coordinator refused an operation (ADR-007 §2): a typed reason the screen shows. */
enum class OperationRefusal {
    /** An Upload Plan awaits confirmation (UC-006 BR-005). */
    UploadPlanAwaitingConfirmation,

    /** A confirmed Upload Plan is being written (UC-006 BR-005). */
    UploadBeingWritten,
}

/** The result of asking the coordinator to run an operation; nothing is half started (ADR-007 §2). */
sealed interface OperationResult<out T> {
    data class Ran<T>(val value: T) : OperationResult<T>

    data class Refused(val reason: OperationRefusal) : OperationResult<Nothing>
}

/**
 * The result of asking the coordinator for a Scheduled Fetch (UC-001 A12, ADR-007 §2): it ran, it is deferred and
 * remembered until the mode is Normal again, or, asked after a pause ended, none was deferred.
 */
sealed interface ScheduledAdmission<out T> {
    data class Ran<T>(val value: T) : ScheduledAdmission<T>

    /** Deferred while an Upload Plan awaits confirmation or its Upload is written; `scheduled_fetch_deferred` is set. */
    data class Deferred(val reason: OperationRefusal) : ScheduledAdmission<Nothing>

    /** Asked when a pause ended, and no Scheduled Fetch is deferred: nothing runs. */
    data object NoneDeferred : ScheduledAdmission<Nothing>
}

/**
 * The operation that runs on the lane now, and what it works on, such as the Data Provider a Fetch reads ([subject],
 * null until the operation names it). ADR-007 §1: the screen shows what is running.
 */
data class RunningOperation(val kind: OperationKind, val subject: String?)

/**
 * Held by a running operation. Every request gate of the operation asks [active] and refuses once the operation has
 * ended, so that no request leaves after its operation (ADR-001 §4.0).
 */
class OperationPermit internal constructor(val kind: OperationKind, private val working: (String) -> Unit = {}) {
    @Volatile
    var active: Boolean = true
        internal set

    /** Names what the operation works on now, such as the Data Provider a Fetch reads, for the screens (ADR-007 §1). */
    fun workingOn(subject: String) {
        if (active) working(subject)
    }
}

/**
 * The only way to start an operation that reads a Source or the Budget App or writes to storage (ADR-007 §1). One
 * instance per app process, constructed only in `slussen.app.di` (Konsist K-16). Operations run on one exclusive lane.
 */
class OperationCoordinator(private val store: CoordinatorStore) {
    private val lane = Mutex()

    private val runningNow = MutableStateFlow<RunningOperation?>(null)

    /** The operation on the lane, or null while none runs (ADR-007 §1: the screen shows what is running). */
    val running: StateFlow<RunningOperation?> = runningNow.asStateFlow()

    private val endedCount = MutableStateFlow(0L)

    /**
     * How many operations have ended since the start, so that every screen shows the stored data again when one ends,
     * whichever screen started it (NFR-023); unlike [running], no ending is lost when two changes come quickly.
     */
    val ended: StateFlow<Long> = endedCount.asStateFlow()

    private companion object {
        /**
         * ADR-007 §2: the operations admitted while an Upload Plan awaits confirmation. Their storage operation raises
         * `state_version` (ADR-002 §3), so that the plan prepared before the decision is never written unchanged; its
         * replacement by a newly prepared plan (§5, UC-004 A8) comes with the Uploads.
         */
        val ADMITTED_WHILE_A_PLAN_IS_OPEN = setOf(OperationKind.ReviewDecision, OperationKind.TransferDecision)
    }

    suspend fun <T> run(
        kind: OperationKind,
        startedBy: StartedBy,
        block: suspend (OperationPermit) -> T,
    ): OperationResult<T> = lane.withLock {
        // UC-006 BR-005: While an Upload Plan awaits confirmation or an Upload is written, credential changes, choosing Budgets, adding or changing Bank Accounts, changing Operator Settings, a Fetch the Operator starts and confirming First Upload Matching are unavailable.
        when (store.mode()) {
            // ADR-007 §2: a Review Decision and a Transfer Decision are admitted while an Upload Plan awaits confirmation; every other operation of this build is refused.
            CoordinatorMode.PlanOpen -> if (kind !in ADMITTED_WHILE_A_PLAN_IS_OPEN) return OperationResult.Refused(OperationRefusal.UploadPlanAwaitingConfirmation)
            CoordinatorMode.Uploading -> return OperationResult.Refused(OperationRefusal.UploadBeingWritten)
            CoordinatorMode.Normal -> Unit
        }
        // ADR-007 §3: origin Schedule is admitted only as a Scheduled Fetch (runScheduledFetch); it never prepares an Upload Plan.
        check(startedBy == StartedBy.Operator) { "Only the Operator starts the operations of $kind (ADR-007 §2)." }
        OperationResult.Ran(onLane(kind, block))
    }

    /**
     * A Scheduled Fetch (UC-001 A12, ADR-007 §2), origin Schedule: admitted in Normal, where it first records its start,
     * which clears a stored deferral; deferred while an Upload Plan awaits confirmation or its Upload is written, by
     * setting `scheduled_fetch_deferred`, with nothing contacted. One that finds the lane busy waits for it (§1).
     */
    suspend fun <T> runScheduledFetch(block: suspend (OperationPermit) -> T): ScheduledAdmission<T> = lane.withLock {
        // UC-001 BR-013: A Scheduled Fetch that falls due while an Upload Plan awaits confirmation or its Upload is being written starts when that pause ends, as one Scheduled Fetch however many fell due meanwhile.
        when (store.mode()) {
            CoordinatorMode.PlanOpen -> {
                store.deferScheduledFetch()
                return ScheduledAdmission.Deferred(OperationRefusal.UploadPlanAwaitingConfirmation)
            }
            CoordinatorMode.Uploading -> {
                store.deferScheduledFetch()
                return ScheduledAdmission.Deferred(OperationRefusal.UploadBeingWritten)
            }
            CoordinatorMode.Normal -> Unit
        }
        // UC-001 BR-013: The deferral is cleared in the storage transaction that records the start of a Scheduled Fetch.
        store.recordScheduledFetchStart()
        ScheduledAdmission.Ran(onLane(OperationKind.Fetch, block))
    }

    /**
     * The deferred Scheduled Fetch, started once when the mode has returned to Normal in this running process (ADR-007
     * §2): the operation that ends the pause calls for it once it has left the lane. Nothing runs when none is deferred,
     * or when it was started already ([ScheduledAdmission.NoneDeferred]); it stays deferred while the mode is not Normal.
     * A start of Slussen never calls this (UC-008 BR-001, ADR-007 §8).
     */
    suspend fun <T> runDeferredScheduledFetch(block: suspend (OperationPermit) -> T): ScheduledAdmission<T> = lane.withLock {
        if (!store.scheduledFetchDeferred()) return ScheduledAdmission.NoneDeferred
        when (store.mode()) {
            CoordinatorMode.PlanOpen -> return ScheduledAdmission.Deferred(OperationRefusal.UploadPlanAwaitingConfirmation)
            CoordinatorMode.Uploading -> return ScheduledAdmission.Deferred(OperationRefusal.UploadBeingWritten)
            CoordinatorMode.Normal -> Unit
        }
        // UC-001 BR-013: The deferral is cleared in the storage transaction that records the start of a Scheduled Fetch, so that an ended process neither loses it nor runs it twice.
        if (!store.recordScheduledFetchStart()) return ScheduledAdmission.NoneDeferred
        ScheduledAdmission.Ran(onLane(OperationKind.Fetch, block))
    }

    /** Runs [block] holding the lane: shown as running, with its permit ended and the ending counted afterwards. */
    private suspend fun <T> onLane(kind: OperationKind, block: suspend (OperationPermit) -> T): T {
        // ADR-007 §1: the operation on the lane is shown, with what it works on, until it ends.
        runningNow.value = RunningOperation(kind, subject = null)
        val permit = OperationPermit(kind) { subject -> runningNow.value = RunningOperation(kind, subject) }
        try {
            return block(permit)
        } finally {
            permit.active = false
            runningNow.value = null
            endedCount.value = endedCount.value + 1
        }
    }
}
