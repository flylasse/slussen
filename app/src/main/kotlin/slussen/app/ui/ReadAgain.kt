package slussen.app.ui

import kotlinx.coroutines.CancellationException
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.components.BudgetAppLineUi
import slussen.app.ui.components.FetchButtonUi
import slussen.app.ui.components.FetchResultUi
import slussen.core.operation.OperationKind
import slussen.core.operation.RunningOperation

/**
 * Read the Budget App again (UC-003 trigger, FR-040) as one screen holds it: whether its read is on its way, and the
 * result of its last tap with its remedy (NFR-023, UX-12). The Overview and every Budget tab hold one each.
 */
class ReadAgain(private val services: UiServices) {
    /** True from the tap until the answer is in. */
    var pending: Boolean = false
        private set

    private var result: FetchResultUi? = null
    private var remedy: RemedyAction? = null

    /** The taps of this screen, so that every result is shown as new. */
    private var taps = 0

    /**
     * The line of [budgetAppName] with [status]: from the tap on, before anything is sent, the action shows that it reads, or what it waits
     * for while another operation runs (ADR-007 §1); while another screen's read runs, it cannot be tapped again.
     */
    fun line(status: String, running: RunningOperation?, budgetAppName: String): BudgetAppLineUi {
        val button = when {
            pending && running != null && running.kind != OperationKind.Comparison ->
                FetchButtonUi(Texts.readWaits(budgetAppName, running), enabled = false, running = true)
            pending || running?.kind == OperationKind.Comparison ->
                FetchButtonUi(Texts.reading(budgetAppName), enabled = false, running = true)
            else -> FetchButtonUi(Texts.readAgain(budgetAppName), enabled = true, running = false)
        }
        return BudgetAppLineUi(status, button, result, remedy)
    }

    /** The tap: false when a read of this screen is already on its way. The earlier result goes while this one runs. */
    fun begin(): Boolean {
        if (pending) return false
        pending = true
        taps++
        result = null
        remedy = null
        return true
    }

    /** NFR-023: the stored status could not be read again after the read; the line says so beside its result. */
    fun statusNotRead() {
        result = result?.copy(detail = Texts.STATUS_NOT_READ)
            ?: FetchResultUi(Texts.STATUS_NOT_READ, null, slussen.app.ui.components.FetchResultKind.Failed, taps)
    }

    /** Reads [budgetAppName] again through the coordinator; whatever the read throws is answered at the line (NFR-023). */
    suspend fun run(budgetAppName: String) {
        val tap = taps
        try {
            val start = services.comparison.readAgain()
            val (shown, action) = Texts.readResult(start, budgetAppName, services.formats, services.clock.instant(), tap)
            result = shown
            remedy = action
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result = Texts.readNotCompleted(budgetAppName, tap)
            remedy = null
        } finally {
            pending = false
        }
    }
}
