package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
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
@DisplayName("UC-010: Adjust Settings, the Request Limit and the app's version on a device")
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

        onNodeWithText("Request Limit reset to its default 50").assertExists()
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

        onNode(hasScrollAction()).performScrollToNode(hasText("This app"))
        onNodeWithText("${installed.versionName} · debug build").assertExists()
    }
}
