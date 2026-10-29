package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.platform.version.installedAppVersion
import slussen.app.ui.settings.SettingsScreen
import slussen.app.ui.settings.SettingsViewModel
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-010: Adjust Settings, the fetch period, the settings of a Data Provider and the app's version on a device")
class UC010AdjustSettingsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-010", scenario = "A11: Reset a Request Limit to Its Default", businessRules = ["BR-002", "BR-010"])
    @DisplayName("Steps 1-4, A11: a Request Limit the Data Provider does not publish opens its editor with the default and range, and resets to the default")
    fun requestLimitEditorOpensAndResets() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = ServicePageViewModel(fixture.services, fixture.slice.openBankingKey)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                ServicePage(viewModel)
            }
        }

        onNodeWithText("50 per 24 hours · not published by Aurora Open Banking · default 50 · 10 to 500").assertExists()
        onNodeWithText("Change").performClick()
        onNodeWithText("Enter 10 to 500.", substring = true).assertExists()
        onNodeWithText("Reset to default").performClick()

        // NFR-023 (UX-22): the answer is at the Request Limit's line.
        onNodeWithText("Saved: 50 requests in any 24 hours, the default.", substring = true).assertExists()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-001", "BR-002"], requirements = ["NFR-023"])
    @DisplayName("Steps 1-4, 6, A1 (settings.html#fetch-period-edit to #fetch-period-saved): the fetch period of 30 days is changed to 14 on Settings; 200 is refused at the field first, and the saved value shows at the fetch period's line")
    fun fetchPeriodIsChangedOnSettings() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = SettingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                Settings(viewModel)
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasTestTag("fetch-period"))
        onNodeWithText("Days back a Fetch asks for · default 30 · 7 to 180").assertExists()
        onNodeWithText("30 days").assertExists()
        onNodeWithTag("fetch-period").performClick()
        onNodeWithText("The new value is asked for from the next Fetch that contacts a Data Provider.", substring = true).assertExists()

        onNodeWithTag("setting-value").performTextReplacement("200")
        onNodeWithText("Save").performClick()
        onNodeWithText("200 days is too many. Enter 7 to 180 days. Still 30 days, the default.").assertExists()

        onNodeWithTag("setting-value").performTextReplacement("14")
        onNodeWithText("Save").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Saved: 14 days (30 by default).", substring = true))
        onNodeWithText("14 days").assertExists()
        assertEquals(14, fixture.slice.settings.settings.fetchPeriodDays)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-023"])
    @DisplayName("Steps 2-6 (connections.html#fetch-cooldown, #fetch-cooldown-saved): the Fetch Cooldown is changed in its dialog, and its line shows the saved value and the result")
    fun fetchCooldownIsSavedAtItsLine() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = ServicePageViewModel(fixture.services, fixture.slice.feedsKey)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                ServicePage(viewModel)
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasTestTag("change-fetch-cooldown"))
        onNodeWithTag("change-fetch-cooldown").performClick()
        onNodeWithText("Enter 0 to 1440 min; 0 switches it off.", substring = true).assertExists()
        onNodeWithTag("setting-value").performTextReplacement("15")
        onNodeWithText("Save").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Saved: 15 min (60 by default).", substring = true))
        onNodeWithText("15 min · default 60, declared by its part · 0 to 1440 · 0 is off").assertExists()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 1, BR-011: Settings shows the version name of the installed debug build, from Android's package information, and says it is a debug build")
    fun settingsShowsTheInstalledVersion() = compose.use {
        val installed = InstrumentationRegistry.getInstrumentation().targetContext.installedAppVersion()
        assertTrue(installed.debugBuild, "the instrumented tests run the debug variant, slussen.app.debug")
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(installed.versionName), "the version name from VERSION: ${installed.versionName}")
        val viewModel = SettingsViewModel(ScreenFixture(installed).services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let { SettingsScreen(it, onEntry = {}, onAccountMappings = {}, onRecentFetches = {}) }
            }
        }

        // Section titles are drawn in capitals, so the scroll targets the version line itself.
        onNode(hasScrollAction()).performScrollToNode(hasText("${installed.versionName} · debug build"))
        onNodeWithText("${installed.versionName} · debug build").assertExists()
    }
}
