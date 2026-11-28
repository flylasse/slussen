package slussen.app.schedule

import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import slussen.core.fetch.FetchTransactions

/**
 * What the Fetch Schedule's worker runs (UC-001 A12, BR-013, ADR-001 §5): one Scheduled Fetch through
 * [FetchTransactions.scheduledFetch], with the services [fetch] gives once the start sequence has run in this process,
 * and nothing else of the Fetch; then the New Transaction Notification's update ([notification], A12 step 3), before
 * the work answers, so that a device that suspends the process at once still shows the new number. The start of a
 * deferred Scheduled Fetch when a pause ends belongs to the operation that ends the pause, never to this work (W4-11).
 */
class ScheduledFetchRun(private val fetch: suspend () -> FetchTransactions, private val notification: suspend () -> Unit = {}) {
    /** True when the Scheduled Fetch ended, ran, deferred or switched off; false when what it needs threw. */
    suspend fun run(): Boolean {
        // UC-001 BR-013: Slussen itself repeats no Scheduled Fetch; what the run throws is caught, and the work is not repeated for it.
        val ended = try {
            fetch().scheduledFetch()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
        // UC-001 BR-014: The notification is updated after each Scheduled Fetch, also one that threw.
        try {
            notification()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Unit
        }
        return ended
    }
}

/** The answer of the Fetch Schedule's work to the device (UC-001 BR-005, BR-013). Plain code, so that a JVM test asserts it. */
object ScheduledFetchAnswer {
    // UC-001 BR-005: A Scheduled Fetch that meets a failure is not repeated; the work answers success whatever its run returned or threw, never a retry or a failure, and passes a cancellation on.
    /** Runs [run] and answers success; a cancellation, the device stopping the work, is passed on. */
    suspend fun after(run: suspend () -> Any?): ListenableWorker.Result {
        try {
            run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Unit
        }
        return ListenableWorker.Result.success()
    }
}


