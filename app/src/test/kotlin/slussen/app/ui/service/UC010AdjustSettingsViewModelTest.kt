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
import slussen.app.ui.settings.EntryPage
import slussen.app.ui.settings.SettingsViewModel
import slussen.core.port.CoordinatorMode
import slussen.core.port.ServiceKey
import slussen.pluginapi.testfixtures.UseCase

/**
 * The Request Limit of a Data Provider that publishes none, on its page (`connections.html#request-limit`),
 * and the app's version on Settings (`settings.html#list-2`).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, the Request Limit on a Data Provider's page and the app's version on Settings")
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
        assertEquals("Request Limit 80 stored · an Operator Setting", page.ui().message)
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
        assertEquals("600 is outside the allowed range · enter 10 to 500 · the Request Limit stays 50", page.ui().message)
        page.saveRequestLimit("eighty")
        assertEquals("Enter a whole number from 10 to 500 · the Request Limit stays 50", page.ui().message)

        assertTrue(page.ui().requestLimitEditor != null)
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
        assertEquals("Request Limit reset to its default 50", page.ui().message)
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

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", page.ui().message)
        assertEquals(50, storedLimit())
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
