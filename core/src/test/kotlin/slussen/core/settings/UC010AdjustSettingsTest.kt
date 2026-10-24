package slussen.core.settings

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.operation.OperationRefusal
import slussen.core.plugin.PluginRegistration
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.TestSlice

/**
 * The part of Adjust Settings (UC-010) built so far: the services listed directly at the top of Settings (BR-010, S-40)
 * and the Request Limit of a Data Provider that publishes none, an Operator Setting on the Data Provider's page (S-31).
 * The other Operator Settings come with the slice that builds UC-010.
 */
@DisplayName("UC-010: Adjust Settings, the Request Limit of a Data Provider")
class UC010AdjustSettingsTest {
    private val slice = TestSlice()

    private fun storedLimit(): Int = slice.registry.dataProviders.single { it.id == slice.openBankingId }.requestLimit

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-010"])
    @DisplayName("Step 1: the page of a Data Provider that publishes no Request Limit shows its value with the default and range its part declares; a published one with its source")
    fun pageShowsTheRequestLimit() = runTest {
        val openBanking = slice.settingsOverview.servicePage(slice.openBankingKey)
        val feeds = slice.settingsOverview.servicePage(slice.feedsKey)

        assertEquals(RequestLimitShown.NotPublished(requests = 50, defaultRequests = 50, range = 10..500), openBanking.requestLimit)
        assertEquals(RequestLimitShown.Published(requests = 20, source = "Kestrel Feeds developer guide, an invented source"), feeds.requestLimit)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("Step 1, BR-010: Settings lists the services directly at its top, in the order of setting up: the Budget App, each Data Provider and each File Format of the build, set up or not, then the Account Mappings; each entry comes from what its part declares")
    fun settingsListsTheServicesDirectly() = runTest {
        val top = slice.settingsOverview.entries()

        assertEquals(
            listOf(EntryKind.BudgetApp, EntryKind.DataProvider, EntryKind.DataProvider, EntryKind.DataProvider, EntryKind.FileFormat),
            top.entries.map { it.kind },
        )
        assertEquals(
            listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds", "Aurora Open Banking", "Wren Feeds", "Harbor Health statement"),
            top.entries.map { it.name },
        )
        assertEquals(listOf(false, false, true, false, false), top.entries.map { it.needsBankConsent })
        assertEquals(slice.plugins.fileFormats.single().id, top.entries.last().fileFormatId)
        assertEquals(null, top.entries.last().service)
        assertFalse(top.accountMappings.available)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-006"])
    @DisplayName("Steps 2-6: a value within the declared range is stored, shown on the page, applied from the next request on and recorded in the Decision Journal")
    fun valueWithinTheRangeIsStored() = runTest {
        val outcome = slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80)

        assertEquals(SettingOutcome.Stored(80), outcome)
        assertEquals(80, storedLimit())
        assertEquals(80, slice.plugins.dataProvider(slice.openBankingKey).stored.requestLimit)
        assertEquals(80, slice.settingsOverview.servicePage(slice.openBankingKey).requestLimit?.requests)
        val entry = slice.journalRecorder.entries.single()
        assertEquals(JournalEventKind.ConfigurationChange, entry.eventKind)
        assertTrue("Request Limit" in entry.details && "Aurora Open Banking" in entry.details, entry.details)
        assertTrue("50" in entry.details && "80" in entry.details, entry.details)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: the bounds of the declared range, 10 and 500, are allowed")
    fun boundsOfTheRangeAreAllowed() = runTest {
        assertEquals(SettingOutcome.Stored(10), slice.providerSettings.changeRequestLimit(slice.openBankingKey, 10))
        assertEquals(SettingOutcome.Stored(500), slice.providerSettings.changeRequestLimit(slice.openBankingKey, 500))
        assertEquals(500, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1: a value outside the declared range is refused, the previous value is kept and the range is shown")
    fun valueOutsideTheRangeIsRefused() = runTest {
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80)

        assertEquals(SettingOutcome.OutsideRange(10..500), slice.providerSettings.changeRequestLimit(slice.openBankingKey, 9))
        assertEquals(SettingOutcome.OutsideRange(10..500), slice.providerSettings.changeRequestLimit(slice.openBankingKey, 501))
        assertEquals(80, storedLimit())
        assertEquals(1, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A11: Reset a Request Limit to Its Default", businessRules = ["BR-006"])
    @DisplayName("A11: the Request Limit is reset to the default its part declares, stored and recorded like any other change")
    fun requestLimitIsResetToItsDefault() = runTest {
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80)

        val outcome = slice.providerSettings.resetRequestLimit(slice.openBankingKey)

        assertEquals(SettingOutcome.Stored(50), outcome)
        assertEquals(50, storedLimit())
        assertEquals(2, slice.journalRecorder.entries.size)
        assertTrue("80" in slice.journalRecorder.entries.last().details && "50" in slice.journalRecorder.entries.last().details)
    }

    @Test
    @UseCase(id = "UC-010", businessRules = ["BR-001"])
    @DisplayName("BR-001: a Request Limit the Data Provider publishes is not an Operator Setting: it can be neither changed nor reset")
    fun publishedRequestLimitIsNotAnOperatorSetting() = runTest {
        assertEquals(SettingOutcome.NotAnOperatorSetting, slice.providerSettings.changeRequestLimit(slice.feedsKey, 30))
        assertEquals(SettingOutcome.NotAnOperatorSetting, slice.providerSettings.resetRequestLimit(slice.feedsKey))
        assertEquals(20, slice.registry.dataProviders.single { it.id == slice.feedsId }.requestLimit)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", businessRules = ["BR-006"])
    @DisplayName("BR-006: the Operator's Request Limit is kept at the next start of the same build")
    fun requestLimitIsKept() = runTest {
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 120)

        val restarted = PluginRegistration(slice.registry)
            .register(slice.budgetApp, listOf(slice.feeds, slice.openBanking, slice.wren), listOf(slice.fileFormat))

        assertEquals(120, restarted.dataProvider(slice.openBankingKey).stored.requestLimit)
    }

    @Test
    @UseCase(id = "UC-010")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation or an Upload is written, the Request Limit cannot be changed or reset")
    fun unavailableWhileAPlanAwaitsConfirmation() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val refusal = OperationRefusal.UploadPlanAwaitingConfirmation

        assertEquals(SettingOutcome.Unavailable(refusal), slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80))
        assertEquals(SettingOutcome.Unavailable(refusal), slice.providerSettings.resetRequestLimit(slice.openBankingKey))
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(
            SettingOutcome.Unavailable(OperationRefusal.UploadBeingWritten),
            slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80),
        )
        assertEquals(50, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010", requirements = ["NFR-006"])
    @DisplayName("Postconditions: with the Decision Journal switched off the value is still stored and nothing is recorded")
    fun journalOffStillStores() = runTest {
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)

        assertEquals(SettingOutcome.Stored(70), slice.providerSettings.changeRequestLimit(slice.openBankingKey, 70))
        assertEquals(70, storedLimit())
        assertFalse(slice.journalRecorder.entries.isNotEmpty())
    }
}
