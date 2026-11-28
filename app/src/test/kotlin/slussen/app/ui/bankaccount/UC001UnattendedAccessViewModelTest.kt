package slussen.app.ui.bankaccount

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.overview.OverviewViewModel
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFetchResult
import slussen.core.port.StoredProviderRequest
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import java.time.Duration

/**
 * What the Overview and the Bank Account screen show while a Bank Account's Unattended Access limit is reached (UC-001
 * A13 step 2, BR-012; UC-008 BR-006): the Blocked Reason "access limit reached" with the time the limit frees up, and
 * that only Scheduled Fetches are held back; and that it goes when access is free again. Every name is invented (C-005).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-001: Fetch Transactions, the Unattended Access limit on the screens")
class UC001UnattendedAccessViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)

    /** Kestrel Reserve, read through the Data Provider that needs a Bank Consent, whose transactions a Scheduled Fetch left out. */
    private fun reserveAtTheLimit(): Long = runBlocking {
        scenario.serve()
        val id = scenario.addBankAccount("Kestrel Reserve", "Reserve Savings")
        val index = slice.connectionStore.bankAccountList.indexOfFirst { it.id == id }
        slice.connectionStore.bankAccountList[index] = slice.connectionStore.bankAccountList[index].copy(dataProviderId = slice.openBankingId)
        val now = slice.clock.instant()
        listOf(10L, 9L, 8L, 7L).forEach { hours ->
            slice.providerRequests.requests += StoredProviderRequest(slice.openBankingId, id, ProviderRequestKind.Transactions, true, now.minus(Duration.ofHours(hours)))
        }
        slice.importStatusStore.fetches += StoredFetch(
            1, slice.openBankingId, now.minus(Duration.ofHours(1)), StartedBy.Schedule, FetchOutcome.Fetched,
            listOf(StoredFetchResult(id, FetchResultOutcome.AccessLimitReached, 0)),
        )
        id
    }

    private fun bankAccountScreen(id: Long) = BankAccountViewModel(fixture.services, id).also { it.load() }.state.value.ui!!

    private fun overviewBlocked(): List<String> =
        OverviewViewModel(fixture.services).also { it.load() }.state.value!!.budgets.flatMap { it.blocked }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A13 step 2, UC-008 BR-006: while the limit holds, the Overview names the Bank Account as blocked, and the Bank Account screen gives the time it frees up and says that only Scheduled Fetches are held back")
    fun blockedWhileTheLimitHolds() {
        val id = reserveAtTheLimit()

        assertTrue("Blocked: Kestrel Reserve, access limit reached" in overviewBlocked(), "${overviewBlocked()}")
        val line = bankAccountScreen(id).blocked.single { "access limit reached" in it.text }
        // The oldest of the four counted reads, 10 hours ago (02:00 UTC, 04:00 in the device's zone), leaves the 24 hours at 04:00 the next day.
        assertEquals("Blocked: Kestrel Reserve, access limit reached · until 2026-10-16 04:00", line.text)
        assertEquals(
            "PSD2 allows 4 reads of its transactions and 4 of its balance in 24 hours without you present; until then " +
                "Scheduled Fetches leave out the reads that reached it, and its stored data stays unchanged. A Fetch you " +
                "start is not held back by this; the Bank may still refuse it.",
            line.advice,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["NFR-018"])
    @DisplayName("A13, UC-008 BR-006: when the oldest counted read leaves the 24 hours, access is free and the Blocked Reason goes from both screens, though the Fetch Result still records Access Limit Reached")
    fun goneWhenFree() {
        val id = reserveAtTheLimit()

        slice.clock.advance(Duration.ofHours(14))

        assertTrue(overviewBlocked().none { "access limit reached" in it }, "${overviewBlocked()}")
        assertTrue(bankAccountScreen(id).blocked.none { "access limit reached" in it.text })
    }
}
