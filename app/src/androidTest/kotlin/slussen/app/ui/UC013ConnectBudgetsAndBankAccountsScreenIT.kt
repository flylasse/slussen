package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.setup.SetupScreen
import slussen.app.ui.setup.SetupViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-013: Connect Budgets and Bank Accounts, the screens on a device")
class UC013ConnectBudgetsAndBankAccountsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun de.mannodermaus.junit5.compose.ComposeContext.show(viewModel: SetupViewModel) {
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    SetupScreen(
                        it, onBack = {}, onStep = viewModel::goTo, onCredentials = {}, onToggleBudget = viewModel::toggleBudget,
                        onSaveBudgets = viewModel::saveBudgets, onSelectOffered = viewModel::selectOffered, onPair = viewModel::pairWith,
                        onSaveNaming = viewModel::saveNaming, onCancelNaming = viewModel::cancelNaming, onAddFromFiles = {},
                        onFetch = viewModel::fetch,
                    )
                }
            }
        }
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A7: Budget App Credentials Not Entered", businessRules = ["BR-008"])
    @DisplayName("Step 1, A7: the credentials come first; without the Budget App's, the Budgets cannot be chosen")
    fun credentialsComeFirst() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = SetupViewModel(fixture.services)
        onMain { viewModel.load() }
        show(viewModel)

        onNodeWithText("${ExampleData.BUDGET_APP_NAME} · Budget App").assertExists()
        onNodeWithText("Continue to Budgets").performClick()

        onNodeWithText("Budgets can be chosen once the ${ExampleData.BUDGET_APP_NAME} credentials are accepted.").assertExists()
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-004", "BR-009"])
    @DisplayName("Steps 2-10: Budgets are chosen, an Offered Bank Account is paired with a free Budget Account, its name, type and Bank are proposed and it is added on Save")
    fun budgetsAndPairing() = compose.use {
        val fixture = ScreenFixture()
        val slice = fixture.slice
        runBlocking {
            slice.enterCredentials()
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-4410", "CHK", "checking", ExampleData.USD, "4410", ExampleData.amount(4140, 50), bank, slice.clock.instant())),
            )
        }
        val viewModel = SetupViewModel(fixture.services)
        onMain { viewModel.load() }
        show(viewModel)

        onNodeWithText("Continue to Budgets").performClick()
        onNodeWithText("Household · USD").performClick()
        onNodeWithText("Continue").performClick()
        onNodeWithText("CHK 4,140.05").performClick()
        onNodeWithText("Checking 4,140.05").performClick()

        onNodeWithText("Proposed CHK ••4410").assertExists()
        onNodeWithText("Type and Bank proposed from what Kestrel Feeds states · change them if they are wrong").assertExists()
        onNodeWithText("Kestrel Bank").assertExists()
        onNodeWithText("Save").performClick()

        onNodeWithText("Bank Account CHK ••4410 added · fetched from the next Fetch").assertExists()
    }
}
