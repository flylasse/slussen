package slussen.app.di

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.overview.OverviewViewModel
import slussen.core.connection.BudgetsRead
import slussen.core.plugin.PluginRegistration
import slussen.core.port.CoordinatorMode
import slussen.pluginapi.testfixtures.UseCase

/**
 * UC-001 BR-001 and BR-013 at a start of Slussen, on the fakes of `:testing` as close to `CompositionRoot.start` as the
 * JVM gets: a deferred Scheduled Fetch is stored and the process ended with an Upload Plan open; the start then registers
 * the plug-ins again (O-11), resets the mode to Normal as the start sequence does (ADR-007 §8), concludes a pending
 * Comparison from stored data (`Comparison.recomputePending`, ADR-007 §3a) and opens the first screen, the Overview, on
 * the app's services. No Source and no Budget App is contacted, and the deferral stays set for the next run of the Fetch
 * Schedule or the end of a pause in a running process.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-001: Fetch Transactions, a start of Slussen with a deferred Scheduled Fetch")
class UC001OpeningSlussenKeepsTheDeferralTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-001", "BR-013"])
    @DisplayName("BR-001, BR-013: a start of Slussen and its first screen contact no Source while a Scheduled Fetch is deferred, and the deferral stays set")
    fun startOfSlussenKeepsTheDeferralAndContactsNothing() = runTest {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
        val requestsBefore = slice.providerRequests.requests.size
        val budgetReadsBefore = slice.budgetApp.reader.budgetReads
        val changeReadsBefore = slice.budgetApp.reader.changeReads.size
        // The process ended with an Upload Plan open, a Scheduled Fetch deferred and a Comparison left pending.
        slice.coordinatorStore.scheduledFetchDeferred = true
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        slice.connectionStore.comparisonPending = true

        // The start: the plug-ins registered again, the mode reset by the start sequence, the pending Comparison concluded.
        PluginRegistration(slice.registry).register(slice.budgetApp, listOf(slice.feeds, slice.openBanking, slice.wren), listOf(slice.fileFormat))
        slice.coordinatorStore.mode = CoordinatorMode.Normal
        slice.comparison.recomputePending()
        // The first screen.
        val overview = OverviewViewModel(fixture.services).also { it.load() }

        assertNotNull(overview.state.value)
        assertEquals(0, slice.feeds.fetches + slice.openBanking.fetches + slice.wren.fetches)
        assertEquals(requestsBefore, slice.providerRequests.requests.size)
        assertEquals(budgetReadsBefore, slice.budgetApp.reader.budgetReads)
        assertEquals(changeReadsBefore, slice.budgetApp.reader.changeReads.size)
        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
        assertEquals(0, slice.coordinatorStore.scheduledFetchStarts)
    }
}
