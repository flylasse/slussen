package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.connections.ConnectionsViewModel
import slussen.app.ui.connections.SettingsScreen
import slussen.app.ui.service.ServicePageScreen
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.ServiceKey
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-014: Manage Service Credentials, the screens on a device")
class UC014ManageServiceCredentialsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1: Connections lists the services in use with their status and the Data Providers not set up")
    fun connectionsListsTheServices() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = ConnectionsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let { SettingsScreen(it, onService = {}, onBankAccounts = {}, onSetUp = {}) }
            }
        }

        onNodeWithText("${ExampleData.BUDGET_APP_NAME} · Budget App").assertExists()
        onNodeWithText("Credentials missing").assertExists()
        onNodeWithText("Kestrel Feeds").assertExists()
        onNodeWithText("Wren Feeds").assertExists()
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Steps 2-6: the declared form is filled in and the accepted credentials are shown as accepted")
    fun credentialsAreEnteredAndAccepted() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = ServicePageViewModel(fixture.services, ServiceKey.budgetApp, fromSetup = false)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    ServicePageScreen(
                        it, onBack = {}, onStartEntering = viewModel::startEntering, onCancelEntering = viewModel::cancelEntering,
                        onSave = viewModel::save, onRequestRemoval = viewModel::requestRemoval, onCancelRemoval = viewModel::cancelRemoval,
                        onConfirmRemoval = viewModel::confirmRemoval, onChooseBudgets = {},
                    )
                }
            }
        }

        onNodeWithText("Enter credentials").performClick()
        onNodeWithTag("field-token").performTextInput(ExampleData.BUDGET_APP_TOKEN)
        onNodeWithText("Save").performClick()

        onNodeWithText("Accepted by ${ExampleData.BUDGET_APP_NAME}").assertExists()
        onNodeWithText("Remove").assertExists()
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A3: Remove Credentials")
    @DisplayName("A3: removing asks first and states that the service is not contacted until new credentials are entered")
    fun removalAsksFirst() = compose.use {
        val fixture = ScreenFixture()
        kotlinx.coroutines.runBlocking { fixture.slice.enterCredentials() }
        val viewModel = ServicePageViewModel(fixture.services, fixture.slice.feedsKey, fromSetup = false)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    ServicePageScreen(
                        it, onBack = {}, onStartEntering = viewModel::startEntering, onCancelEntering = viewModel::cancelEntering,
                        onSave = viewModel::save, onRequestRemoval = viewModel::requestRemoval, onCancelRemoval = viewModel::cancelRemoval,
                        onConfirmRemoval = viewModel::confirmRemoval, onChooseBudgets = {},
                    )
                }
            }
        }

        onNodeWithText("Remove").performClick()

        onNodeWithText("Kestrel Feeds is not contacted until you enter new ones.", substring = true).assertExists()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Accepted", substring = true).assertExists()
    }
}
