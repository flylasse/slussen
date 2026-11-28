package slussen.app.schedule

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.CoordinatorMode
import slussen.core.port.StartedBy
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.TestSlice

/**
 * What the Fetch Schedule's worker runs (UC-001 A12, BR-013, A14), on the fakes of `:testing`: a Scheduled Fetch through
 * `FetchTransactions.scheduledFetch()` and nothing else, never the start of a deferred one (W4-11); what it throws is
 * caught, and the work answers success every time, since Slussen repeats no Scheduled Fetch (BR-005).
 */
@DisplayName("UC-001: Fetch Transactions, the run of the Fetch Schedule's worker")
class UC001ScheduledFetchRunTest {
    private val slice = TestSlice()
    private val run = ScheduledFetchRun { slice.fetch }

    private suspend fun feedsInUse(outcome: SourceOutcome = SourceOutcome.Completed) {
        slice.enterCredentials()
        slice.feeds.fetchAnswer = { FetchResponse(outcome, emptyList(), emptyList()) }
    }

    private suspend fun recordedOfFeeds() = slice.importStatusStore.fetchesOf(slice.feedsId).map { it.startedBy }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("A12, BR-013: the run is one Scheduled Fetch of every Data Provider in use, recorded as started by the Fetch Schedule")
    fun runIsOneScheduledFetch() = runTest {
        feedsInUse()

        assertTrue(run.run())

        assertEquals(1, slice.feeds.fetches)
        assertEquals(listOf(StartedBy.Schedule), recordedOfFeeds())
        assertEquals(1, slice.coordinatorStore.scheduledFetchStarts)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: with no Scheduled Fetch deferred the run still fetches, so it is not the start of a deferred one; with one deferred it starts once, never twice")
    fun runIsNotTheStartOfADeferredOne() = runTest {
        feedsInUse()
        slice.coordinatorStore.scheduledFetchDeferred = true

        run.run()

        assertEquals(1, slice.feeds.fetches)
        assertEquals(1, slice.coordinatorStore.scheduledFetchStarts)
        assertFalse(slice.coordinatorStore.scheduledFetchDeferred)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a run that falls due while an Upload Plan awaits confirmation contacts nothing and leaves the deferral set")
    fun runDuringAPauseIsDeferred() = runTest {
        feedsInUse()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertTrue(run.run())

        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A14: Transient Failure in a Scheduled Fetch", businessRules = ["BR-005", "BR-013"])
    @DisplayName("A14, BR-005: a Data Provider that is unavailable is contacted once; the run ends, and the work answers success so that the device does not repeat it")
    fun failedRunIsNotRepeated() = runTest {
        feedsInUse(SourceOutcome.TransientFailure(FailureKind.ServiceUnavailable))

        assertTrue(run.run())

        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: what storage throws in the run is caught; nothing is contacted and the run ends")
    fun failureIsCaught() = runTest {
        feedsInUse()
        slice.settings.failsToRead = true

        assertFalse(run.run())

        assertEquals(0, slice.feeds.fetches)
        assertFalse(ScheduledFetchRun { error("The services could not be created (invented failure).") }.run())
    }
}
