package slussen.app.ui.overview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.Texts
import slussen.app.ui.UiFixture
import slussen.core.operation.OperationKind
import slussen.core.operation.RunningOperation
import slussen.core.port.NumberSetting
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.core.port.StartedBy
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.testfixtures.UseCase

/**
 * On the Overview (UC-001 BR-014, `overview.html#notification-ask`): the one question about notifications, asked when the
 * Overview opens while the Fetch Schedule is above 0, Slussen's notifications are not allowed and the device has no answer
 * of the Operator yet, with nothing Slussen keeps about it; and a Scheduled Fetch shown as running says that the Fetch
 * Schedule started it (ADR-007 §1, NFR-023).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-001: Fetch Transactions, the question about notifications and a Scheduled Fetch on the Overview")
class UC001NotificationQuestionViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    private fun overview() = OverviewViewModel(fixture.services).also { it.load() }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: asked only while the Fetch Schedule is above 0, notifications are not allowed, the device asks before an app shows them and has no answer yet")
    fun askedOnlyWhenEveryConditionHolds() {
        assertTrue(NotificationQuestion.ask(fetchSchedule = 2, allowed = false, deviceAsks = true, deviceHasAnswer = false))
        assertFalse(NotificationQuestion.ask(fetchSchedule = 0, allowed = false, deviceAsks = true, deviceHasAnswer = false))
        assertFalse(NotificationQuestion.ask(fetchSchedule = 2, allowed = true, deviceAsks = true, deviceHasAnswer = false))
        assertFalse(NotificationQuestion.ask(fetchSchedule = 2, allowed = false, deviceAsks = false, deviceHasAnswer = false))
        assertFalse(NotificationQuestion.ask(fetchSchedule = 2, allowed = false, deviceAsks = true, deviceHasAnswer = true))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: the Overview knows the stored Fetch Schedule, so that the question is not asked while it is 0")
    fun overviewCarriesTheFetchSchedule() = runTest {
        slice.enterCredentials()
        assertEquals(2, overview().state.value!!.fetchSchedule)

        slice.settings.store(SettingChange(SettingValue.Number(NumberSetting.FetchSchedule, 0), SettingFollowUp.None))
        assertEquals(0, overview().state.value!!.fetchSchedule)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("ADR-007 §1, NFR-023: while a Scheduled Fetch runs, the Fetch button and an action waiting behind it say that the Fetch Schedule started it")
    fun runningScheduledFetchIsNamed() = runTest {
        slice.enterCredentials()
        val hold = CompletableDeferred<Unit>()
        slice.feeds.beforeAnswer = { hold.await() }
        slice.feeds.fetchAnswer = { FetchResponse(SourceOutcome.Completed, emptyList(), emptyList()) }
        val viewModel = overview()

        val scheduled = async { slice.fetch.scheduledFetch() }
        runCurrent()

        assertEquals("Fetching Kestrel Feeds… · a Scheduled Fetch, started by the Fetch Schedule", viewModel.state.value!!.fetchButton.text)
        assertEquals(
            "Fetching Kestrel Feeds, a Scheduled Fetch started by the Fetch Schedule",
            Texts.operation(RunningOperation(OperationKind.Fetch, "Kestrel Feeds", StartedBy.Schedule)),
        )
        assertEquals("Fetching Kestrel Feeds", Texts.operation(RunningOperation(OperationKind.Fetch, "Kestrel Feeds")))
        hold.complete(Unit)
        scheduled.await()
    }
}
