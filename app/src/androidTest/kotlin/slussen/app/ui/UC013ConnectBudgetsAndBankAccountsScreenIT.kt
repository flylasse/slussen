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
import slussen.app.ui.setup.pairingActionsOf
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
                        onSaveBudgets = viewModel::saveBudgets, pairing = pairingActionsOf(viewModel),
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
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-004", "BR-009", "BR-012", "BR-013"])
    @DisplayName("Steps 2-15: Budgets are chosen; an Offered Bank Account, none switched on, is brought in after the stated numbers; on its card the only free Budget Account is proposed, and Add and continue adds it and shows the summary")
    fun budgetsAndPairing() = compose.use {
        val fixture = ScreenFixture()
        val slice = fixture.slice
        runBlocking {
            slice.enterCredentials()
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(
                    ExampleData.offered("acct-4410", "CHK", "checking", ExampleData.USD, "4410", ExampleData.amount(4140, 50), bank, slice.clock.instant()),
                    ExampleData.offered("acct-2290", "Kids Saver", "savings", ExampleData.USD, "2290", ExampleData.amount(310), bank, slice.clock.instant()),
                ),
            )
        }
        val viewModel = SetupViewModel(fixture.services)
        onMain { viewModel.load() }
        show(viewModel)

        onNodeWithText("Continue to Budgets").performClick()
        onNodeWithText("Care Fund · USD").performClick()
        onNodeWithText("Continue").performClick()
        onNodeWithText("Bring in 0 · leave out 2").assertExists()
        onNodeWithText("CHK 4,140.05").performClick()
        onNodeWithText("Bring in 1 · leave out 1").performClick()
        onNodeWithText("Bring in 1, leave out 1?").assertExists()
        onNodeWithText("Bring in 1").performClick()

        onNodeWithText("1 of 1").assertExists()
        onNodeWithText("Proposed: the only free Budget Account in USD").assertExists()
        onNodeWithText("Proposed CHK ••4410").assertExists()
        onNodeWithText("Type and Bank proposed from what Kestrel Feeds states · change them if they are wrong").assertExists()
        onNodeWithText("Add and continue").performClick()

        onNodeWithText("Bank Accounts added: 1").assertExists()
        onNodeWithText("CHK ••4410 → Health Savings ••••4410").assertExists()
        onNodeWithText("Kids Saver ••2290").assertExists()
    }
}
