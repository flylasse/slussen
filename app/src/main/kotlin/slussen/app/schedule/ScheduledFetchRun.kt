package slussen.app.schedule

import kotlinx.coroutines.CancellationException
import slussen.core.fetch.FetchTransactions

/**
 * What the Fetch Schedule's worker runs (UC-001 A12, BR-013, ADR-001 §5): one Scheduled Fetch through
 * [FetchTransactions.scheduledFetch], with the services [fetch] gives once the start sequence has run in this process,
 * and nothing else of the Fetch. The start of a deferred Scheduled Fetch when a pause ends belongs to the operation that
 * ends the pause, never to this work (W4-11).
 */
class ScheduledFetchRun(private val fetch: suspend () -> FetchTransactions) {
    // UC-001 BR-013: Slussen itself repeats no Scheduled Fetch; what the run throws is caught, and the work is not repeated for it.
    /** True when the Scheduled Fetch ended, ran, deferred or switched off; false when what it needs threw. */
    suspend fun run(): Boolean =
        try {
            fetch().scheduledFetch()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
}
