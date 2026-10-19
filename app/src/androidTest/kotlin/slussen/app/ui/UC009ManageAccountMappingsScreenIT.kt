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
import slussen.app.ui.bankaccounts.BankAccountsScreen
import slussen.app.ui.bankaccounts.BankAccountsViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.connection.BudgetsRead
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-009: Manage Account Mappings, the Bank Account type and the Bank on a device")
class UC009ManageAccountMappingsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14: a Bank Account opens the sheet of its type and Bank, and Save stores another type")
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
        val viewModel = BankAccountsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    BankAccountsScreen(
                        it, onBack = {}, onAdd = {}, onOpenTypeAndBank = viewModel::openTypeAndBank,
                        onSaveTypeAndBank = viewModel::saveTypeAndBank, onConfirmClearing = viewModel::confirmClearing,
                        onCancelClearing = viewModel::cancelClearing, onCancelTypeAndBank = viewModel::cancelTypeAndBank,
                    )
                }
            }
        }

        onNodeWithText("Kestrel Bank · Checking · Kestrel Feeds").performClick()
        onNodeWithText("Savings").performClick()
        onNodeWithText("Save").performClick()

        onNodeWithText("Kestrel Checking: Savings · Kestrel Bank · stored").assertExists()
        onNodeWithText("Kestrel Bank · Savings · Kestrel Feeds").assertExists()
    }
}
