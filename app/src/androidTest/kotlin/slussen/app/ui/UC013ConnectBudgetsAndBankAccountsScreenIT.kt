package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.fileformat.FileFormatScreen
import slussen.app.ui.fileformat.FileFormatViewModel
import slussen.app.ui.mappings.AccountMappingsViewModel
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.settings.SettingsScreen
import slussen.app.ui.settings.SettingsViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.connection.BudgetsRead
import slussen.core.port.ServiceKey
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.TestSlice

/**
 * Connect Budgets and Bank Accounts (UC-013) on a device, on the screens of S-40 (`settings.html#first-run`,
 * `connections.html#ynab-first`, `#simplefin-offered`, `#switch-off`, `#hsa-no-budget`, `account-mappings.html#to-map`,
 * `#pair-name`, `#pair-summary`).
 */
@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-013: Connect Budgets and Bank Accounts, the screens on a device")
class UC013ConnectBudgetsAndBankAccountsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun offer(slice: TestSlice) = runBlocking {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-care"))
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

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-015"])
    @DisplayName("Step 1: on the first run Settings lists every service and the File Format, names what comes next, and the Account Mappings say why they are not yet available")
    fun firstRunSettings() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = SettingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let { SettingsScreen(it, onEntry = {}, onAccountMappings = {}, onRecentFetches = {}) }
            }
        }

        onNodeWithText("Not set up · next: enter the ${ExampleData.BUDGET_APP_NAME} credentials").assertExists()
        onNodeWithText("Harbor Health statement").assertExists()
        onNodeWithText("Not yet: enter the ${ExampleData.BUDGET_APP_NAME} credentials first").assertExists()
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A9: Mark or Unmark a Budget Account Manually Kept", businessRules = ["BR-014"])
    @DisplayName("Step 4, A9: the Budget App's page lists the Budget Accounts with Receives from Slussen on; switching one off keeps it by hand at once and offers Undo")
    fun receivesFromSlussenSwitch() = compose.use {
        val fixture = ScreenFixture()
        offer(fixture.slice)
        val health = runBlocking { fixture.slice.connectionStore.budgets().single { it.served }.budgetAccounts.single().id }
        val viewModel = ServicePageViewModel(fixture.services, ServiceKey.budgetApp)
        onMain { viewModel.load() }
        setContent { SlussenTheme { ServicePage(viewModel) } }

        onNodeWithTag("receives-$health").assertIsOn()
        onNodeWithTag("receives-$health").performClick()

        onNodeWithTag("receives-$health").assertIsOff()
        onNodeWithText("Health Savings kept by hand · not offered for pairing").assertExists()
        onNodeWithText("Undo").performClick()
        onNodeWithTag("receives-$health").assertIsOn()
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-011", "BR-012"])
    @DisplayName("Steps 5-7 (P9-03): a Data Provider's page shows each Offered Bank Account with its balance and its import switch on; switching one off leaves it out at once, with no dialog")
    fun importSwitches() = compose.use {
        val fixture = ScreenFixture()
        offer(fixture.slice)
        val kids = runBlocking { fixture.slice.offeredStore.offered().single { it.providerReference == "acct-2290" }.id }
        val viewModel = ServicePageViewModel(fixture.services, fixture.slice.feedsKey)
        onMain { viewModel.load() }
        setContent { SlussenTheme { ServicePage(viewModel) } }

        onNodeWithText("4,140.05").assertExists()
        onNodeWithTag("import-$kids").assertIsOn()
        onNodeWithTag("import-$kids").performClick()

        onNodeWithTag("import-$kids").assertIsOff()
        onNodeWithText("Kids Saver ••2290 not imported · never read").assertExists()
        onNodeWithText("Undo").assertExists()
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009", "BR-013"])
    @DisplayName("Steps 8-15: the Account Mappings list the Bank Accounts to map; one opens its card with the only free Budget Account proposed, Add and continue adds it and shows the summary")
    fun mapOneBankAccount() = compose.use {
        val fixture = ScreenFixture()
        offer(fixture.slice)
        val viewModel = AccountMappingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent { SlussenTheme { AccountMappings(viewModel) } }

        onNodeWithText("CHK ••4410").performClick()
        onNodeWithText("1 of 1").assertExists()
        onNodeWithText("Proposed: the only free Budget Account in USD").assertExists()
        onNodeWithText("Proposed CHK ••4410").assertExists()
        onNodeWithText("Add and continue").performClick()

        onNodeWithText("Bank Accounts added: 1").assertExists()
        onNodeWithText("CHK ••4410 → Health Savings ••4410").assertExists()
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006"])
    @DisplayName("A4 step 1: the File Format's page opens before a Budget is served and says that a Bank Account can be added once one is chosen")
    fun fileFormatPageWaits() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = FileFormatViewModel(fixture.services, fixture.slice.plugins.fileFormats.single().id)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    FileFormatScreen(it, onBack = {}, onOpenForm = viewModel::openForm, onCancelForm = viewModel::cancelForm, onChoose = viewModel::choose, onAdd = viewModel::add)
                }
            }
        }

        onNodeWithText("Harbor Health statement").assertExists()
        onNodeWithText("Available once ${ExampleData.BUDGET_APP_NAME} is set up and a Budget is chosen.").assertExists()
    }
}
