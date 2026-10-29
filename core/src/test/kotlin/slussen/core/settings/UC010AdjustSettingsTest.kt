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
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredBankTransaction
import slussen.core.port.TransactionStatus
import java.time.LocalDate
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.TestSlice

/**
 * The part of Adjust Settings (UC-010) built so far: the services listed directly at the top of Settings (BR-010, S-40),
 * the fetch period on Settings (O-T-07, S-T-06), and the Fetch Cooldown and the Request Limit of a Data Provider that
 * publishes none, Operator Settings on the Data Provider's page (S-31, UX-D4). The other Operator Settings come with a
 * later slice.
 */
@DisplayName("UC-010: Adjust Settings, the fetch period and the settings of a Data Provider")
class UC010AdjustSettingsTest {
    private val slice = TestSlice()

    private fun storedLimit(): Int = slice.registry.dataProviders.single { it.id == slice.openBankingId }.requestLimit

    private fun storedCooldown(): Int = slice.registry.dataProviders.single { it.id == slice.feedsId }.fetchCooldownMinutes

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

    // ---- The fetch period on Settings (O-T-07) ----------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002"])
    @DisplayName("Step 1, BR-001: Settings shows the fetch period of a new installation, 30 days, with its default and its allowed range of 7 to 180 days")
    fun fetchPeriodIsShownWithItsDefault() = runTest {
        val shown = slice.generalSettings.shown()

        assertEquals(FetchPeriodShown(days = 30, defaultDays = 30, range = 7..180), shown.fetchPeriod)
        assertTrue(shown.journalOn)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-006"])
    @DisplayName("Steps 2-4, 6: a fetch period within the range is stored, shown, and recorded in the Decision Journal as a configuration change with the value before and after")
    fun fetchPeriodWithinTheRangeIsStored() = runTest {
        val outcome = slice.generalSettings.changeFetchPeriod(14)

        assertEquals(SettingOutcome.Stored(14), outcome)
        assertEquals(14, slice.settings.settings.fetchPeriodDays)
        assertEquals(FetchPeriodShown(days = 14, defaultDays = 30, range = 7..180), slice.generalSettings.shown().fetchPeriod)
        val entry = slice.journalRecorder.entries.single()
        assertEquals(JournalEventKind.ConfigurationChange, entry.eventKind)
        assertEquals("Fetch period changed from 30 to 14 days", entry.details)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: the bounds of the fetch period's range, 7 and 180 days, are allowed")
    fun boundsOfTheFetchPeriodAreAllowed() = runTest {
        assertEquals(SettingOutcome.Stored(7), slice.generalSettings.changeFetchPeriod(7))
        assertEquals(SettingOutcome.Stored(180), slice.generalSettings.changeFetchPeriod(180))
        assertEquals(180, slice.settings.settings.fetchPeriodDays)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1: a fetch period of 6 or 181 days is refused with the range; the previous value is kept and nothing is recorded")
    fun fetchPeriodOutsideTheRangeIsRefused() = runTest {
        assertEquals(SettingOutcome.OutsideRange(7..180), slice.generalSettings.changeFetchPeriod(6))
        assertEquals(SettingOutcome.OutsideRange(7..180), slice.generalSettings.changeFetchPeriod(181))

        assertEquals(30, slice.settings.settings.fetchPeriodDays)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5: the fetch period is not one a Transaction Status depends on, so changing it gives no stored Bank Transaction its status again; only the Operator Setting changes")
    fun fetchPeriodChangesNoTransactionStatus() = runTest {
        val planted = listOf(TransactionStatus.New, null).mapIndexed { index, status ->
            StoredBankTransaction(
                id = index + 1L, bankAccountId = 1, stableReference = "ref-$index", referenceKind = StableReferenceKind.Supplied,
                occurrenceNumber = 1, importIdentifier = "R-planted-$index", bookedDate = LocalDate.of(2026, 10, 1 + index),
                amount = ExampleData.amount(-12, 500), currency = ExampleData.USD, payee = "Invented Grocer", memo = null,
                firstSeenAt = slice.clock.instant(), status = status, transferId = null,
            )
        }
        slice.importStatusStore.bankTransactions += planted
        val before = slice.settings.settings

        slice.generalSettings.changeFetchPeriod(14)

        assertEquals(before.copy(fetchPeriodDays = 14), slice.settings.settings)
        assertEquals(planted, slice.importStatusStore.bankTransactions)
        assertEquals(0, slice.feeds.fetches)
        assertEquals(0, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Failure postconditions: a fetch period that cannot be stored keeps the previous value, and nothing is recorded")
    fun fetchPeriodNotStoredKeepsThePreviousValue() = runTest {
        slice.settings.failsToStore = true

        assertEquals(SettingOutcome.NotStored, slice.generalSettings.changeFetchPeriod(14))

        assertEquals(30, slice.settings.settings.fetchPeriodDays)
        assertTrue(slice.journalRecorder.entries.isEmpty())
        assertEquals(30, slice.generalSettings.shown().fetchPeriod.days)
    }

    @Test
    @UseCase(id = "UC-010", requirements = ["NFR-006"])
    @DisplayName("Postconditions: with the Decision Journal switched off the fetch period is still stored, nothing is recorded, and Settings knows it")
    fun fetchPeriodWithTheJournalOff() = runTest {
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)

        assertEquals(SettingOutcome.Stored(21), slice.generalSettings.changeFetchPeriod(21))

        assertEquals(21, slice.settings.settings.fetchPeriodDays)
        assertTrue(slice.journalRecorder.entries.isEmpty())
        assertFalse(slice.generalSettings.shown().journalOn)
    }

    @Test
    @UseCase(id = "UC-010", businessRules = ["BR-006"])
    @DisplayName("BR-006: a stored fetch period is kept; the default of 30 days is only where nothing is stored yet")
    fun storedFetchPeriodIsKept() = runTest {
        slice.settings.settings = slice.settings.settings.copy(fetchPeriodDays = 60)

        assertEquals(FetchPeriodShown(days = 60, defaultDays = 30, range = 7..180), slice.generalSettings.shown().fetchPeriod)
    }

    @Test
    @UseCase(id = "UC-010")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation or an Upload is written, the fetch period cannot be changed")
    fun fetchPeriodUnavailableWhileAPlanAwaitsConfirmation() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertEquals(
            SettingOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation),
            slice.generalSettings.changeFetchPeriod(14),
        )
        assertEquals(30, slice.settings.settings.fetchPeriodDays)
    }

    // ---- The Fetch Cooldown on a Data Provider's page (UX-D4, UX-22) ------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002", "BR-010"])
    @DisplayName("Step 1, BR-010: a Data Provider's page shows its Fetch Cooldown with the default its part declares and the range of 0 to 1440 minutes")
    fun pageShowsTheFetchCooldown() = runTest {
        val page = slice.settingsOverview.servicePage(slice.feedsKey)

        assertEquals(60, page.fetchCooldownMinutes)
        assertEquals(60, page.defaultFetchCooldownMinutes)
        assertEquals(0..1440, page.fetchCooldownRange)
        assertEquals(null, slice.settingsOverview.servicePage(slussen.core.port.ServiceKey.budgetApp).fetchCooldownRange)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Steps 2-6: a Fetch Cooldown within the range is stored, applied to the next Fetch, shown on the page and recorded in the Decision Journal")
    fun fetchCooldownWithinTheRangeIsStored() = runTest {
        val outcome = slice.providerSettings.changeFetchCooldown(slice.feedsKey, 15)

        assertEquals(SettingOutcome.Stored(15), outcome)
        assertEquals(15, storedCooldown())
        assertEquals(15, slice.plugins.dataProvider(slice.feedsKey).stored.fetchCooldownMinutes)
        assertEquals(15, slice.settingsOverview.servicePage(slice.feedsKey).fetchCooldownMinutes)
        val entry = slice.journalRecorder.entries.single()
        assertEquals(JournalEventKind.ConfigurationChange, entry.eventKind)
        assertEquals("Fetch Cooldown of Kestrel Feeds changed from 60 to 15 minutes", entry.details)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1, BR-002: a Fetch Cooldown of 0 (off) and 1440 minutes is allowed; -1 and 1441 are refused with the range and the previous value is kept")
    fun fetchCooldownOutsideTheRangeIsRefused() = runTest {
        assertEquals(SettingOutcome.Stored(0), slice.providerSettings.changeFetchCooldown(slice.feedsKey, 0))
        assertEquals(SettingOutcome.Stored(1440), slice.providerSettings.changeFetchCooldown(slice.feedsKey, 1440))

        assertEquals(SettingOutcome.OutsideRange(0..1440), slice.providerSettings.changeFetchCooldown(slice.feedsKey, -1))
        assertEquals(SettingOutcome.OutsideRange(0..1440), slice.providerSettings.changeFetchCooldown(slice.feedsKey, 1441))
        assertEquals(1440, storedCooldown())
        assertEquals(2, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A10: Reset a Fetch Cooldown to Its Default", businessRules = ["BR-006"])
    @DisplayName("A10: the Fetch Cooldown is reset to the default its part declares, stored and recorded like any other change")
    fun fetchCooldownIsResetToItsDefault() = runTest {
        slice.providerSettings.changeFetchCooldown(slice.feedsKey, 15)

        assertEquals(SettingOutcome.Stored(60), slice.providerSettings.resetFetchCooldown(slice.feedsKey))

        assertEquals(60, storedCooldown())
        assertEquals("Fetch Cooldown of Kestrel Feeds changed from 15 to 60 minutes", slice.journalRecorder.entries.last().details)
    }

    @Test
    @UseCase(id = "UC-010", businessRules = ["BR-006"])
    @DisplayName("BR-006: the Operator's Fetch Cooldown is kept at the next start of the same build")
    fun fetchCooldownIsKept() = runTest {
        slice.providerSettings.changeFetchCooldown(slice.feedsKey, 15)

        val restarted = PluginRegistration(slice.registry)
            .register(slice.budgetApp, listOf(slice.feeds, slice.openBanking, slice.wren), listOf(slice.fileFormat))

        assertEquals(15, restarted.dataProvider(slice.feedsKey).stored.fetchCooldownMinutes)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Failure postconditions: a Fetch Cooldown or a Request Limit that cannot be stored keeps the previous value, also for the next Fetch, and nothing is recorded")
    fun settingOfADataProviderNotStoredKeepsThePreviousValue() = runTest {
        slice.registry.failsToStore = true

        assertEquals(SettingOutcome.NotStored, slice.providerSettings.changeFetchCooldown(slice.feedsKey, 15))
        assertEquals(SettingOutcome.NotStored, slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80))

        assertEquals(60, storedCooldown())
        assertEquals(60, slice.plugins.dataProvider(slice.feedsKey).stored.fetchCooldownMinutes)
        assertEquals(50, storedLimit())
        assertEquals(50, slice.plugins.dataProvider(slice.openBankingKey).stored.requestLimit)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010")
    @DisplayName("Precondition: while an Upload is written, the Fetch Cooldown cannot be changed or reset")
    fun fetchCooldownUnavailableWhileAnUploadIsWritten() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        val refusal = SettingOutcome.Unavailable(OperationRefusal.UploadBeingWritten)

        assertEquals(refusal, slice.providerSettings.changeFetchCooldown(slice.feedsKey, 15))
        assertEquals(refusal, slice.providerSettings.resetFetchCooldown(slice.feedsKey))
        assertEquals(60, storedCooldown())
    }
}
