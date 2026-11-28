package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.mappings.AccountMappingsViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.connection.BudgetsRead
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ServiceKey
import org.junit.jupiter.api.Assertions.assertEquals
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-009: Manage Account Mappings, the sheet of the Bank Account, the Bank Account type and the Bank, retiring, Not imported and Kept by hand on a device")
class UC009ManageAccountMappingsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("Step 2, A14 (#sheet, #type-and-bank): a Bank Account opens its sheet, whose Bank Account type and Bank opens that sheet, and Save stores another type")
    fun typeIsChangedOnTheSheet() = compose.use {
        val fixture = ScreenFixture()
        val slice = fixture.slice
        runBlocking {
            slice.enterCredentials()
            val read = slice.connect.readBudgets() as BudgetsRead.Listed
            slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-4410", "CHK", "checking", ExampleData.USD, "4410", null, bank, slice.clock.instant())),
            )
            val checking = slice.connectionStore.budgets().single().budgetAccounts.first().id
            slice.connect.add(
                slice.offeredStore.offered().single().id, checking, "Kestrel Checking", BankAccountType.Checking, BankChoice.Known(bank), null,
            )
        }
        val viewModel = AccountMappingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent { SlussenTheme { AccountMappings(viewModel) } }

        onNodeWithText("Kestrel Bank · Checking · Kestrel Feeds").performClick()
        onNodeWithText("Bank Account type and Bank").performClick()
        onNodeWithText("Savings").performClick()
        onNodeWithText("Save").performClick()

        onNodeWithText("Kestrel Checking: Savings · Kestrel Bank · stored").assertExists()
        onNodeWithText("Kestrel Bank · Savings · Kestrel Feeds").assertExists()
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005", "BR-015"])
    @DisplayName("A4 (#map, #sheet, #retire): a line shows its Transfer Partner and state; its sheet's Retire asks first, and Retire stores it, answers above the list and lists it among the Retired Accounts")
    fun retiringAsksFirst() = compose.use {
        val fixture = ScreenFixture()
        val slice = fixture.slice
        runBlocking {
            slice.enterCredentials()
            val read = slice.connect.readBudgets() as BudgetsRead.Listed
            slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-4410", "CHK", "checking", ExampleData.USD, "4410", null, bank, slice.clock.instant())),
            )
            val checking = slice.connectionStore.budgets().single().budgetAccounts.first().id
            slice.connect.add(
                slice.offeredStore.offered().single().id, checking, "Kestrel Checking", BankAccountType.Checking, BankChoice.Known(bank), null,
            )
        }
        val viewModel = AccountMappingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent { SlussenTheme { AccountMappings(viewModel) } }

        onNodeWithText("Transfer Partner: not set").assertExists()
        onNodeWithText("Nothing sent yet · identified").assertExists()
        onNodeWithText("Kestrel Bank · Checking · Kestrel Feeds").performClick()
        onNodeWithText("Retire Bank Account…").performClick()
        onNodeWithText("Retire Kestrel Checking?").assertExists()
        assertEquals(false, runBlocking { slice.connectionStore.bankAccounts().single().retired })
        onNodeWithText("Retire").performClick()

        onNodeWithText("Kestrel Checking: retired · stored · the Transaction Statuses are given again").assertExists()
        onNodeWithText("RETIRED ACCOUNTS").assertExists()
        assertEquals(true, runBlocking { slice.connectionStore.bankAccounts().single().retired })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A15: Bring Back a Left-Out Bank Account", businessRules = ["BR-013", "BR-014"])
    @DisplayName("Step 1, A15, A16: Not imported and Kept by hand are short read-only lists, shown only when not empty, each line leading to the page where it is changed")
    fun notImportedAndKeptByHandLeadToTheirPages() = compose.use {
        val fixture = ScreenFixture()
        val slice = fixture.slice
        runBlocking {
            slice.enterCredentials()
            val read = slice.connect.readBudgets() as BudgetsRead.Listed
            slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-2290", "Kids Saver", "savings", ExampleData.USD, "2290", null, bank, slice.clock.instant())),
            )
            slice.connect.switchImportOff(slice.offeredStore.offered().single().id)
            val reserve = slice.connectionStore.budgets().single().budgetAccounts.single { it.name == "Reserve Savings" }.id
            slice.connect.setReceivesFromSlussen(reserve, receives = false)
        }
        var opened: ServiceKey? = null
        val viewModel = AccountMappingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent { SlussenTheme { AccountMappings(viewModel) { opened = it } } }

        onNodeWithText("NOT IMPORTED · LEFT-OUT BANK ACCOUNTS · 1").assertExists()
        onNodeWithText("KEPT BY HAND · MANUALLY KEPT ACCOUNTS · 1").assertExists()
        onNodeWithText("Bring back").assertDoesNotExist()
        onNodeWithText("Keep by hand").assertDoesNotExist()
        onNodeWithText("Kids Saver ••2290 · Kestrel Feeds").performClick()
        assertEquals(slice.feedsKey, opened)
        onNodeWithText("Reserve Savings").performClick()
        assertEquals(ServiceKey.budgetApp, opened)
    }
}
