package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.service.ServicePageScreen
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-010: Adjust Settings, the Request Limit on a device")
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
        val viewModel = ServicePageViewModel(fixture.services, fixture.slice.openBankingKey, fromSetup = false)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    ServicePageScreen(
                        it, onBack = {}, onStartEntering = viewModel::startEntering, onCancelEntering = viewModel::cancelEntering,
                        onSave = viewModel::save, onRequestRemoval = viewModel::requestRemoval, onCancelRemoval = viewModel::cancelRemoval,
                        onConfirmRemoval = viewModel::confirmRemoval, onChooseBudgets = {},
                        onEditRequestLimit = viewModel::startEditingRequestLimit, onSaveRequestLimit = viewModel::saveRequestLimit,
                        onResetRequestLimit = viewModel::resetRequestLimit, onCancelRequestLimit = viewModel::cancelRequestLimit,
                        onFetch = viewModel::fetch, onConfirmFetch = viewModel::confirmFetch, onCancelFetch = viewModel::cancelFetch,
                    )
                }
            }
        }

        onNodeWithText("50 per 24 hours · not published by Aurora Open Banking · default 50 · 10 to 500").assertExists()
        onNodeWithText("Change").performClick()
        onNodeWithText("Enter 10 to 500.", substring = true).assertExists()
        onNodeWithText("Reset to default").performClick()

        onNodeWithText("Request Limit reset to its default 50").assertExists()
    }
}
