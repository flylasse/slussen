package slussen.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.settings.SettingsViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-010: Adjust Settings, the editors of the Operator Settings on a device")
class UC010OperatorSettingsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"], requirements = ["NFR-023"])
    @DisplayName("Steps 2-6, A1 (settings.html#out-of-range, #saved): the Upload Window of 7 days refuses 90 at the field, then saves 10, and its own line shows the value and that the statuses were given again")
    fun uploadWindowIsChangedOnSettings() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = SettingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                Settings(viewModel)
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasTestTag("setting-UploadWindow"))
        onNodeWithText("Days back · default 7 · 1 to 60").assertExists()
        onNodeWithTag("setting-UploadWindow").performClick()
        onNodeWithTag("setting-value").performTextReplacement("90")
        onNodeWithText("Save").performClick()
        onNodeWithText("90 days is too many. Enter 1 to 60 days. Still 7 days, the default.").assertExists()

        onNodeWithTag("setting-value").performTextReplacement("10")
        onNodeWithText("Save").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Saved: 10 days (7 by default).", substring = true))
        onNodeWithText("Every Transaction Status given again from stored data", substring = true).assertExists()
        assertEquals(10, fixture.slice.settings.settings.uploadWindowDays)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"], requirements = ["NFR-023"])
    @DisplayName("A5 (settings.html#detailed-logging): Detailed Logging is switched on after the statement of what the logs then contain, and its line says it is on")
    fun detailedLoggingIsSwitchedOnOnSettings() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = SettingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                Settings(viewModel)
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasTestTag("detailed-logging"))
        onNodeWithTag("detailed-logging").performClick()
        onNodeWithText("Switch on Detailed Logging?").assertExists()
        onNodeWithText("Credentials, Bank Consent data and Bank Sessions are never logged", substring = true).assertExists()
        onNodeWithTag("confirm-detailed-logging").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Saved: on (off by default).", substring = true))
        assertTrue(fixture.slice.settings.settings.detailedLogging)
    }
}
