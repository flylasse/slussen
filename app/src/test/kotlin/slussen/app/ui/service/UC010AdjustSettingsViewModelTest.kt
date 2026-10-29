package slussen.app.ui.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.AppVersion
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.app.ui.components.SettingEditorUi
import slussen.app.ui.settings.FetchPeriodUi
import slussen.app.ui.settings.ShownSettingUi
import kotlinx.coroutines.CompletableDeferred
import slussen.app.ui.settings.EntryPage
import slussen.app.ui.settings.SettingsViewModel
import slussen.core.port.CoordinatorMode
import slussen.core.port.ServiceKey
import slussen.pluginapi.testfixtures.UseCase

/**
 * The fetch period on Settings (`settings.html#list-this-build`, `#fetch-period-edit` to `#fetch-period-failed`), the
 * Fetch Cooldown and the Request Limit of a Data Provider that publishes none, on its page (`connections.html#fetch-cooldown`,
 * `#fetch-cooldown-saved`, `#request-limit`, `#local-action-states`), each answering at its own line (NFR-023, UX-22), and
 * the app's version on Settings (`settings.html#list-2`).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, the fetch period on Settings, the settings on a Data Provider's page and the app's version")
class UC010AdjustSettingsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    private fun page(service: ServiceKey) = ServicePageViewModel(fixture.services, service).also { it.load() }

    private fun ServicePageViewModel.ui() = state.value!!

    private fun storedLimit() = slice.registry.dataProviders.single { it.id == slice.openBankingId }.requestLimit

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("Step 1: the page shows the Request Limit as not published by the Data Provider, with the default and range its part declares, changeable")
    fun pageShowsTheChangeableRequestLimit() {
        val ui = page(slice.openBankingKey).ui()

        assertEquals("50 per 24 hours · not published by Aurora Open Banking · default 50 · 10 to 500", ui.requestLimit)
        assertTrue(ui.requestLimitEditable)
        assertNull(ui.requestLimitEditor)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Steps 2-6: the editor shows the default and range; a value within it is saved and shown on the page")
    fun requestLimitIsChanged() {
        val page = page(slice.openBankingKey)

        page.startEditingRequestLimit()
        val editor = page.ui().requestLimitEditor!!
        assertEquals("50", editor.value)
        assertEquals(
            "Not published by Aurora Open Banking. Its part declares 50 requests in any 24 hours by default, an Operator Setting. Enter 10 to 500.",
            editor.hint,
        )
        assertEquals("The limit PSD2 sets of 4 Unattended Accesses per Bank Account still applies.", editor.unattendedNote)
        page.saveRequestLimit("80")

        assertNull(page.ui().requestLimitEditor)
        // NFR-023 (UX-22): the answer is at the Request Limit's line, which shows the saved value.
        assertEquals(
            AnswerUi(
                "Saved: 80 requests in any 24 hours (50 by default). Applies from the next request. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            page.ui().requestLimitAnswer,
        )
        assertNull(page.ui().message)
        assertEquals("80 per 24 hours · not published by Aurora Open Banking · default 50 · 10 to 500", page.ui().requestLimit)
        assertEquals(80, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1: a value outside the range, or not a whole number, is refused with the range; the editor stays open and the previous value is kept")
    fun valueOutsideTheRangeIsRefused() {
        val page = page(slice.openBankingKey)
        page.startEditingRequestLimit()

        page.saveRequestLimit("600")
        assertEquals("600 is outside the allowed range · enter 10 to 500 · the Request Limit stays 50", page.ui().requestLimitEditor?.error)
        page.saveRequestLimit("eighty")
        assertEquals("Enter a whole number from 10 to 500 · the Request Limit stays 50", page.ui().requestLimitEditor?.error)
        page.editRequestLimit("80")
        assertNull(page.ui().requestLimitEditor?.error)

        assertEquals("80", page.ui().requestLimitEditor?.value)
        assertEquals(50, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A11: Reset a Request Limit to Its Default", businessRules = ["BR-006"])
    @DisplayName("A11: Reset to default stores the default its part declares")
    fun requestLimitIsResetToItsDefault() {
        val page = page(slice.openBankingKey)
        page.startEditingRequestLimit()
        page.saveRequestLimit("80")
        page.startEditingRequestLimit()

        page.resetRequestLimit()

        assertNull(page.ui().requestLimitEditor)
        assertEquals(
            AnswerUi(
                "Saved: 50 requests in any 24 hours, the default. Applies from the next request. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            page.ui().requestLimitAnswer,
        )
        assertEquals(50, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010", businessRules = ["BR-001"])
    @DisplayName("BR-001: a published Request Limit has no editor")
    fun publishedRequestLimitHasNoEditor() {
        val page = page(slice.feedsKey)

        page.startEditingRequestLimit()

        assertFalse(page.ui().requestLimitEditable)
        assertNull(page.ui().requestLimitEditor)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Steps 2-4: Cancel closes the editor and keeps the value")
    fun cancelKeepsTheValue() {
        val page = page(slice.openBankingKey)
        page.startEditingRequestLimit()

        page.cancelRequestLimit()

        assertNull(page.ui().requestLimitEditor)
        assertEquals(AnswerUi("Not changed: still 50 requests in any 24 hours, the default.", AnswerKind.Unchanged), page.ui().requestLimitAnswer)
        assertEquals(50, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, saving shows why it is unavailable")
    fun unavailableWhileAPlanAwaitsConfirmation() {
        val page = page(slice.openBankingKey)
        page.startEditingRequestLimit()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        page.saveRequestLimit("80")

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", page.ui().requestLimitEditor?.error)
        assertFalse(page.ui().requestLimitEditor!!.saving)
        assertEquals(50, storedLimit())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023 (UX-22): Save of the Request Limit shows Saving… before anything is stored; a value that cannot be stored keeps the previous one, said at its line")
    fun requestLimitSavingAndNotSaved() {
        val hold = CompletableDeferred<Unit>()
        slice.registry.beforeStore = { hold.await() }
        val page = page(slice.openBankingKey)
        page.startEditingRequestLimit()

        page.saveRequestLimit("80")
        assertTrue(page.ui().requestLimitEditor!!.saving)
        assertEquals("80", page.ui().requestLimitEditor!!.value)
        assertEquals(50, storedLimit())
        hold.complete(Unit)
        assertNull(page.ui().requestLimitEditor)

        slice.registry.failsToStore = true
        page.startEditingRequestLimit()
        page.saveRequestLimit("120")

        assertNull(page.ui().requestLimitEditor)
        assertEquals(
            AnswerUi(
                "Not saved: Slussen could not store 120 requests in any 24 hours. Still 80 requests in any 24 hours.",
                AnswerKind.Failed,
                "Tap Change to try again.",
            ),
            page.ui().requestLimitAnswer,
        )
        assertEquals("80 per 24 hours · not published by Aurora Open Banking · default 50 · 10 to 500", page.ui().requestLimit)
        assertEquals(80, storedLimit())
    }

    // ---- The Fetch Cooldown on a Data Provider's page (UX-D4, UX-22) ------------------------------------------------

    private fun storedCooldown() = slice.registry.dataProviders.single { it.id == slice.feedsId }.fetchCooldownMinutes

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-010"])
    @DisplayName("Steps 1-2: a Data Provider's page offers to change its Fetch Cooldown; the dialog shows the value, the default its part declares and the range; the Budget App's page has none")
    fun fetchCooldownEditorShowsDefaultAndRange() {
        val page = page(slice.feedsKey)
        assertTrue(page.ui().fetchCooldownEditable)
        assertFalse(page(ServiceKey.budgetApp).ui().fetchCooldownEditable)

        page.startEditingFetchCooldown()

        assertEquals(
            SettingEditorUi(
                title = "Fetch Cooldown · Kestrel Feeds",
                fieldName = "New value · min · default 60",
                value = "60",
                explanation = "60 min by default, declared by the part of Kestrel Feeds. Enter 0 to 1440 min; 0 switches it off. " +
                    "A Fetch of Kestrel Feeds within the Fetch Cooldown after a successful one is served from stored data.",
                canReset = true,
            ),
            page.ui().fetchCooldownEditor,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-023"])
    @DisplayName("Steps 3-6 (UX-22): Save shows Saving… before anything is stored; saved, the Fetch Cooldown's line shows the new value and says so")
    fun fetchCooldownSavingThenSaved() {
        val hold = CompletableDeferred<Unit>()
        slice.registry.beforeStore = { hold.await() }
        val page = page(slice.feedsKey)
        page.startEditingFetchCooldown()

        page.saveFetchCooldown("15")
        assertTrue(page.ui().fetchCooldownEditor!!.saving)
        assertEquals("15", page.ui().fetchCooldownEditor!!.value)
        assertEquals(60, storedCooldown())
        hold.complete(Unit)

        assertNull(page.ui().fetchCooldownEditor)
        assertEquals("15 min · default 60, declared by its part · 0 to 1440 · 0 is off", page.ui().fetchCooldown)
        assertEquals(
            AnswerUi("Saved: 15 min (60 by default). Applies from the next Fetch. Recorded in the Decision Journal.", AnswerKind.Done),
            page.ui().fetchCooldownAnswer,
        )
        assertNull(page.ui().message)
        assertEquals(15, storedCooldown())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1: a Fetch Cooldown outside 0 to 1440 minutes, or not a whole number, is refused at the field with the range and the value that stays")
    fun fetchCooldownOutsideTheRangeIsRefused() {
        val page = page(slice.feedsKey)
        page.startEditingFetchCooldown()

        page.saveFetchCooldown("1500")
        assertEquals("1500 min is too many. Enter 0 to 1440 min; 0 switches it off. Still 60 min, the default.", page.ui().fetchCooldownEditor?.error)
        page.saveFetchCooldown("-5")
        assertEquals("-5 min is too few. Enter 0 to 1440 min; 0 switches it off. Still 60 min, the default.", page.ui().fetchCooldownEditor?.error)
        page.saveFetchCooldown("an hour")
        assertEquals("Enter a whole number of minutes, 0 to 1440. Still 60 min, the default.", page.ui().fetchCooldownEditor?.error)
        page.editFetchCooldown("30")
        assertNull(page.ui().fetchCooldownEditor?.error)

        assertEquals(60, storedCooldown())
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A10: Reset a Fetch Cooldown to Its Default", businessRules = ["BR-006"])
    @DisplayName("A10: Reset to default stores the default its part declares, and the line says so")
    fun fetchCooldownIsResetToItsDefault() {
        val page = page(slice.feedsKey)
        page.startEditingFetchCooldown()
        page.saveFetchCooldown("15")
        page.startEditingFetchCooldown()

        page.resetFetchCooldown()

        assertNull(page.ui().fetchCooldownEditor)
        assertEquals(
            AnswerUi("Saved: 60 min, the default. Applies from the next Fetch. Recorded in the Decision Journal.", AnswerKind.Done),
            page.ui().fetchCooldownAnswer,
        )
        assertEquals(60, storedCooldown())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Failure postconditions (UX-22): a Fetch Cooldown that cannot be stored keeps the previous value, said at its line with how to try again; Cancel says the value is unchanged")
    fun fetchCooldownNotSavedOrCancelled() {
        slice.registry.failsToStore = true
        val page = page(slice.feedsKey)
        page.startEditingFetchCooldown()

        page.saveFetchCooldown("15")

        assertNull(page.ui().fetchCooldownEditor)
        assertEquals(
            AnswerUi("Not saved: Slussen could not store 15 min. Still 60 min, the default.", AnswerKind.Failed, "Tap Change to try again."),
            page.ui().fetchCooldownAnswer,
        )
        assertEquals("60 min · default 60, declared by its part · 0 to 1440 · 0 is off", page.ui().fetchCooldown)
        page.startEditingFetchCooldown()
        assertEquals("15", page.ui().fetchCooldownEditor?.value)

        page.cancelFetchCooldown()
        assertNull(page.ui().fetchCooldownEditor)
        assertEquals(AnswerUi("Not changed: still 60 min, the default.", AnswerKind.Unchanged), page.ui().fetchCooldownAnswer)
        assertEquals(60, storedCooldown())
    }

    // ---- The fetch period on Settings (O-T-07) ----------------------------------------------------------------------

    private fun settings() = SettingsViewModel(fixture.services).also { it.load() }

    private fun SettingsViewModel.ui() = state.value!!

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002"], requirements = ["NFR-023"])
    @DisplayName("Step 1 (settings.html#list-this-build): Settings shows the fetch period, 30 days by default, 7 to 180, to change; the other Operator Settings with the values that apply, and a line that changing them comes later and that no action is needed now")
    fun settingsShowsTheFetchPeriod() {
        val ui = settings().ui()

        assertEquals(FetchPeriodUi("30 days", "Days back a Fetch asks for · default 30 · 7 to 180", answer = null), ui.fetchPeriod)
        assertNull(ui.fetchPeriodEditor)
        assertEquals(
            listOf(
                ShownSettingUi(
                    "Fetch Schedule",
                    "Scheduled Fetches come in a later version; until then Slussen fetches only when you start a Fetch. No action is needed now.",
                    "none yet",
                ),
                ShownSettingUi("Default wait", "Hours · default 8", "8 h"),
                ShownSettingUi("Request timeout", "Seconds · default 30", "30 s"),
                ShownSettingUi("Number of recent Fetches kept", "Default 60", "60"),
                ShownSettingUi("Upload Window", "Days back that could be uploaded · default 7", "7 days"),
                ShownSettingUi("Transfer pairing days", "Default 5", "5"),
                ShownSettingUi("Lonely Transfer grace period", "Days · default 6", "6 days"),
            ),
            ui.shownSettings,
        )
        assertEquals("Changing these Operator Settings comes in a later version; the values shown apply. No action is needed now.", ui.shownSettingsNote)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002"])
    @DisplayName("Step 2 (#fetch-period-edit): the dialog opens with the current value, the default and the range, and says what the change does and does not do")
    fun fetchPeriodEditorSaysWhatItDoes() {
        val viewModel = settings()

        viewModel.startEditingFetchPeriod()

        assertEquals(
            SettingEditorUi(
                title = "Fetch period",
                fieldName = "New value · days · default 30",
                value = "30",
                explanation = "How many days back a Fetch asks each Data Provider for Bank Transactions, an Operator Setting: " +
                    "30 days by default, 7 to 180. The new value is asked for from the next Fetch that contacts a Data Provider. " +
                    "Bank Transactions already stored stay. Start Dates and the Upload Window do not change, so which Bank " +
                    "Transactions could be uploaded stays the same.",
            ),
            viewModel.ui().fetchPeriodEditor,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-023", "NFR-006"])
    @DisplayName("Steps 3-4, 6 (#fetch-period-saving, #fetch-period-saved): Save shows Saving… before anything is stored; saved at 14 days, the fetch period's line shows the new value and the result, recorded in the Decision Journal")
    fun fetchPeriodSavingThenSaved() {
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val viewModel = settings()
        viewModel.startEditingFetchPeriod()
        viewModel.editFetchPeriod("14")

        viewModel.saveFetchPeriod("14")
        assertEquals(true, viewModel.ui().fetchPeriodEditor?.saving)
        assertEquals("14", viewModel.ui().fetchPeriodEditor?.value)
        assertEquals(30, slice.settings.settings.fetchPeriodDays)
        hold.complete(Unit)

        assertNull(viewModel.ui().fetchPeriodEditor)
        assertEquals(
            FetchPeriodUi(
                "14 days",
                "Days back a Fetch asks for · default 30 · 7 to 180",
                AnswerUi(
                    "Saved: 14 days (30 by default). Asked for from the next Fetch that contacts a Data Provider; stored Bank " +
                        "Transactions stay; no Transaction Status changes. Recorded in the Decision Journal.",
                    AnswerKind.Done,
                ),
            ),
            viewModel.ui().fetchPeriod,
        )
        assertEquals(14, slice.settings.settings.fetchPeriodDays)
        assertEquals("Fetch period changed from 30 to 14 days", slice.journalRecorder.entries.single().details)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 2 (#fetch-period-cancelled): Cancel stores and records nothing; the line says the value is unchanged")
    fun fetchPeriodCancelled() {
        val viewModel = settings()
        viewModel.startEditingFetchPeriod()
        viewModel.editFetchPeriod("14")

        viewModel.cancelFetchPeriod()

        assertNull(viewModel.ui().fetchPeriodEditor)
        assertEquals(AnswerUi("Not changed: still 30 days, the default.", AnswerKind.Unchanged), viewModel.ui().fetchPeriod?.answer)
        assertEquals(30, slice.settings.settings.fetchPeriodDays)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1 (#fetch-period-out-of-range): a value outside 7 to 180 days, or not a whole number, is refused in the dialog at the field: what is wrong, what to enter, the value that stays; a new value clears it")
    fun fetchPeriodOutOfRangeIsRefusedAtTheField() {
        val viewModel = settings()
        viewModel.startEditingFetchPeriod()

        viewModel.saveFetchPeriod("200")
        assertEquals("200 days is too many. Enter 7 to 180 days. Still 30 days, the default.", viewModel.ui().fetchPeriodEditor?.error)
        assertEquals("200", viewModel.ui().fetchPeriodEditor?.value)
        viewModel.saveFetchPeriod("5")
        assertEquals("5 days is too few. Enter 7 to 180 days. Still 30 days, the default.", viewModel.ui().fetchPeriodEditor?.error)
        viewModel.saveFetchPeriod("two weeks")
        assertEquals("Enter a whole number of days, 7 to 180. Still 30 days, the default.", viewModel.ui().fetchPeriodEditor?.error)

        viewModel.editFetchPeriod("14")
        assertNull(viewModel.ui().fetchPeriodEditor?.error)
        assertEquals(30, slice.settings.settings.fetchPeriodDays)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Failure postconditions (#fetch-period-failed): storing failed; the line keeps the previous value and says so, and tapping it tries again with the value entered")
    fun fetchPeriodNotSaved() {
        slice.settings.failsToStore = true
        val viewModel = settings()
        viewModel.startEditingFetchPeriod()

        viewModel.saveFetchPeriod("14")

        assertNull(viewModel.ui().fetchPeriodEditor)
        assertEquals(
            FetchPeriodUi(
                "30 days",
                "Days back a Fetch asks for · default 30 · 7 to 180",
                AnswerUi("Not saved: Slussen could not store 14 days. Still 30 days, the default.", AnswerKind.Failed, "Tap the fetch period to try again."),
            ),
            viewModel.ui().fetchPeriod,
        )
        viewModel.startEditingFetchPeriod()
        assertEquals("14", viewModel.ui().fetchPeriodEditor?.value)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-006"])
    @DisplayName("Postconditions: with the Decision Journal switched off, the saved fetch period does not claim a journal entry")
    fun fetchPeriodSavedWithTheJournalOff() {
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)
        val viewModel = settings()
        viewModel.startEditingFetchPeriod()

        viewModel.saveFetchPeriod("21")

        assertEquals(
            AnswerUi(
                "Saved: 21 days (30 by default). Asked for from the next Fetch that contacts a Data Provider; stored Bank " +
                    "Transactions stay; no Transaction Status changes.",
                AnswerKind.Done,
            ),
            viewModel.ui().fetchPeriod?.answer,
        )
    }

    @Test
    @UseCase(id = "UC-010")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, Save says why in the dialog and nothing is stored")
    fun fetchPeriodUnavailableWhileAPlanAwaitsConfirmation() {
        val viewModel = settings()
        viewModel.startEditingFetchPeriod()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        viewModel.saveFetchPeriod("14")

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", viewModel.ui().fetchPeriodEditor?.error)
        assertEquals(false, viewModel.ui().fetchPeriodEditor?.saving)
        assertEquals(30, slice.settings.settings.fetchPeriodDays)
    }

    // ---- The services at the top of Settings (S-40) -----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("Step 1, BR-010: Settings lists the services directly at its top, the Budget App, each Data Provider and each File Format, each opening its own page, then the Account Mappings")
    fun settingsListsTheServicesDirectly() {
        val ui = SettingsViewModel(fixture.services).also { it.load() }.state.value!!

        assertEquals(
            listOf(
                EntryPage.Service(slussen.core.port.ServiceKey.budgetApp),
                EntryPage.Service(slice.feedsKey),
                EntryPage.Service(slice.openBankingKey),
                EntryPage.Service(slice.wrenKey),
                EntryPage.FileFormat(slice.plugins.fileFormats.single().id),
            ),
            ui.entries.map { it.page },
        )
        assertEquals("File Format · Transaction Files", ui.entries.last().kind)
        assertFalse(ui.accountMappings.available)
    }

    // ---- The app's version on Settings (S-37) -------------------------------------------------------------------

    private fun settings(version: AppVersion) =
        SettingsViewModel(UiFixture(appVersion = version).services).also { it.load() }.state.value!!

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 1, BR-011: Settings shows the version name of the release build the Operator runs, from Android's package information, with no debug label")
    fun settingsShowsTheVersionName() {
        val ui = settings(AppVersion.of(packageName = "slussen.app", versionName = "4.5.6"))

        assertEquals("4.5.6", ui.version)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 1, BR-011: a debug build, told by the .debug suffix of its package name, says that it is a debug build beside its version name")
    fun debugBuildSaysSo() {
        val version = AppVersion.of(packageName = "slussen.app.debug", versionName = "4.5.6")

        assertTrue(version.debugBuild)
        assertEquals("4.5.6 · debug build", settings(version).version)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: a package name that only contains debug is not the debug variant")
    fun onlyTheDebugSuffixMakesADebugBuild() {
        assertFalse(AppVersion.of(packageName = "slussen.debugger", versionName = "4.5.6").debugBuild)
        assertFalse(AppVersion.of(packageName = "slussen.app.debug.other", versionName = "4.5.6").debugBuild)
    }
}
