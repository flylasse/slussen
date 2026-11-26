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

    suspend fun <T> run(
        kind: OperationKind,
        startedBy: StartedBy,
        block: suspend (OperationPermit) -> T,
    ): OperationResult<T> = lane.withLock {
        // UC-006 BR-005: While an Upload Plan awaits confirmation or an Upload is written, credential changes, choosing Budgets, adding or changing Bank Accounts, changing Operator Settings, a Fetch the Operator starts and confirming First Upload Matching are unavailable.
        when (store.mode()) {
            CoordinatorMode.PlanOpen -> return OperationResult.Refused(OperationRefusal.UploadPlanAwaitingConfirmation)
            CoordinatorMode.Uploading -> return OperationResult.Refused(OperationRefusal.UploadBeingWritten)
            CoordinatorMode.Normal -> Unit
        }
        check(startedBy == StartedBy.Operator) { "Only the Operator starts the operations of $kind (ADR-007 §2)." }
        // ADR-007 §1: the operation on the lane is shown, with what it works on, until it ends.
        runningNow.value = RunningOperation(kind, subject = null)
        val permit = OperationPermit(kind) { subject -> runningNow.value = RunningOperation(kind, subject) }
        try {
            OperationResult.Ran(block(permit))
        } finally {
            permit.active = false
            runningNow.value = null
        }
    }
}
