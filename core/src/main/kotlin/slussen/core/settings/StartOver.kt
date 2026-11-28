package slussen.core.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import slussen.core.consent.BankConsents
import slussen.core.consent.ConsentAsked
import slussen.core.consent.ConsentNotEnded
import slussen.core.consent.ConsentToEnd
import slussen.core.operation.AloneAdmission
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.RunningOperation
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchStore
import slussen.core.port.StartOverStore

/** UC-010 A12 step 1: what Start Over states before the Operator confirms, from stored data only. */
data class StartOverStatement(
    val bankAccounts: Int,
    val bankTransactions: Int,
    /** The Bank Consents Slussen first asks their Data Provider to end (step 4). */
    val consentsToEnd: List<ConsentToEnd>,
)

/** UC-010 A12 step 3: what is under way, so that Start Over cannot be done now; nothing was deleted. */
sealed interface StartOverUnavailable {
    /** An Upload Plan awaits confirmation or an Upload is being written (UC-006 BR-005). */
    data class Paused(val reason: OperationRefusal) : StartOverUnavailable

    /** Another operation runs or waits on the lane (ADR-007 §2): [running] is the one that runs, if known. */
    data class AnotherOperation(val running: RunningOperation?) : StartOverUnavailable

    /** A Consent Request waits for the Bank's result within its lifetime (UC-007 A10). */
    data class ConsentRequestWaiting(val bankReference: String) : StartOverUnavailable

    /** A Journal Export's file is being written (UC-011). */
    data object JournalExportBeingWritten : StartOverUnavailable
}

/** What the new installation shows once (UC-010 A12 step 4): each Bank whose Bank Consent was not confirmed as ended. */
data class StartOverResult(val notEnded: List<ConsentNotEnded>)

/** An answer of Start Over that the running process gives: nothing was deleted (ADR-002 §3 O-17). */
sealed interface StartOverOutcome {
    data class NotNow(val why: StartOverUnavailable) : StartOverOutcome

    /** The stored data could not be read: nothing was asked, recorded or deleted. */
    data object StoredDataNotRead : StartOverOutcome

    /**
     * O-17 step 2 failed: nothing was deleted. [asked] names the Banks whose Bank Consent was asked to end and how each
     * went; those asked may no longer be usable.
     */
    data class NotRecorded(val asked: List<ConsentAsked>) : StartOverOutcome

    /** The stored data could not be read while the Bank Consents were being ended: nothing recorded or deleted; [asked] as for [NotRecorded]. */
    data class NotReadWhileEnding(val asked: List<ConsentAsked>) : StartOverOutcome
}

/**
 * Start Over (UC-010 A12, FR-107), as ADR-002 §3 O-17 orders it in the running process: on the lane, alone (ADR-007 §2),
 * each Bank Consent is first asked to end at its Data Provider (step 1); then the obligation is recorded (step 2); then
 * [startOver]'s `restart` ends the process's own state and starts the app again (step 3), and the next start deletes
 * everything (steps 4-5). After step 2 the lane is never released, so nothing runs on the old data again.
 */
class StartOver(
    private val connections: ConnectionStore,
    private val fetches: FetchStore,
    private val bankConsents: BankConsents,
    private val coordinator: OperationCoordinator,
    private val store: StartOverStore,
    /** Whether a Journal Export is being written now (UC-011), which runs partly with the lane free. */
    private val exportUnderWay: () -> Boolean,
) {
    /** Step 1: the numbers and the Bank Consents to end, from stored data only; nothing is contacted. Throws when it cannot be read. */
    suspend fun statement(): StartOverStatement {
        val bankAccounts = connections.bankAccounts()
        // A12 step 1 of UC-010: the statement gives the number of Bank Accounts and Bank Transactions, counted from stored data.
        return StartOverStatement(
            bankAccounts = bankAccounts.size,
            bankTransactions = bankAccounts.sumOf { fetches.bankTransactionsOf(it.id).size },
            consentsToEnd = bankConsents.toEnd(),
        )
    }

    /**
     * Steps 3-4 after the Operator confirmed. Returns only when nothing was deleted: [restart] is called once the
     * obligation is recorded and never returns, as the process it ends; when it throws, [notRestarted] is told, and the
     * lane stays held until the process ends. [asking] is told each Bank Consent before it is asked to end.
     */
    suspend fun startOver(
        restart: suspend (StartOverResult) -> Nothing,
        notRestarted: (StartOverResult) -> Unit = {},
        asking: (ConsentToEnd) -> Unit = {},
    ): StartOverOutcome {
        // ADR-007 §2: a Journal Export whose file is being written refuses Start Over.
        if (exportUnderWay()) return StartOverOutcome.NotNow(StartOverUnavailable.JournalExportBeingWritten)
        val admitted = coordinator.runAlone(OperationKind.StartOver) { permit ->
            if (exportUnderWay()) return@runAlone StartOverOutcome.NotNow(StartOverUnavailable.JournalExportBeingWritten)
            val waiting = readOrNull { bankConsents.waiting() } ?: return@runAlone StartOverOutcome.StoredDataNotRead
            // ADR-007 §2: a Consent Request unanswered within its lifetime refuses Start Over, so that no Bank's result arrives for a consent Slussen could no longer end.
            waiting.firstOrNull()?.let { return@runAlone StartOverOutcome.NotNow(StartOverUnavailable.ConsentRequestWaiting(it.bankReference)) }
            val asked = mutableListOf<ConsentAsked>()
            // ADR-002 §3 O-17 step 1: every Bank Consent is asked to end before anything is recorded or deleted.
            val notEnded = readOrNull { bankConsents.endEveryBankConsent(permit, asking) { asked += it } }
                ?: return@runAlone if (asked.isEmpty()) StartOverOutcome.StoredDataNotRead else StartOverOutcome.NotReadWhileEnding(asked.toList())
            // ADR-002 §3 O-17 step 2: the obligation is recorded durably with the Banks to name; from here every start finishes Start Over.
            try {
                store.recordStartOver(notEnded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@runAlone StartOverOutcome.NotRecorded(asked)
            }
            val result = StartOverResult(notEnded)
            // ADR-002 §3 O-17 step 3: the process ends without releasing the lane, so nothing runs on the old data again.
            try {
                restart(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notRestarted(result)
            }
            awaitCancellation()
        }
        return when (admitted) {
            is AloneAdmission.Ran -> admitted.value
            is AloneAdmission.Refused -> StartOverOutcome.NotNow(StartOverUnavailable.Paused(admitted.reason))
            is AloneAdmission.Busy -> StartOverOutcome.NotNow(StartOverUnavailable.AnotherOperation(admitted.running))
        }
    }

    private suspend fun <T : Any> readOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
}
