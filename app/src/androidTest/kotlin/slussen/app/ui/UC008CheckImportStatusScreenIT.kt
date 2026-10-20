package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.mannodermaus.junit5.compose.createComposeExtension
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.budget.BlockedLine
import slussen.app.ui.budget.BudgetScreen
import slussen.app.ui.budget.BudgetUi
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.budget.SourceCard
import slussen.app.ui.overview.OverviewScreen
import slussen.app.ui.overview.OverviewViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.RecoveryCause
import slussen.core.port.ServiceKey
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-008: Check Import Status, the screen on a device")
class UC008CheckImportStatusScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    @Test
    @UseCase(id = "UC-008", scenario = "A8: Recovery Check Open", businessRules = ["BR-009"])
    @DisplayName("A8, A9: the open Recovery Check is shown; asking to confirm it says that it stays open")
    fun recoveryCheckIsShownAndStaysOpen() = compose.use {
        val fixture = ScreenFixture()
        aBankAccountIsAdded(fixture)
        fixture.slice.recoveryStore.open(RecoveryCause.NewInstallation, fixture.slice.clock.instant())
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = {}, onConfirmRecoveryCheck = viewModel::confirmRecoveryCheck, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {})
                }
            }
        }

        onNodeWithText("Uploads blocked: Recovery Check open").assertExists()
        onNodeWithText("New installation", substring = true).assertExists()
        // P9-14: in plain words, only Uploads wait; the Fetch stays offered with what it does in this build.
        onNodeWithText("Only Uploads wait for the Recovery Check; a Fetch still works.").assertExists()
        onNodeWithText("Fetch all Data Providers").assertIsEnabled()
        onNodeWithText("In this build a Fetch lists the Bank Accounts", substring = true).assertExists()
        onNodeWithText("Recovery Check").performClick()
        onNodeWithText("The Recovery Check stays open", substring = true).assertExists()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("A10: with no Bank Account added, the Overview shows only the card that leads to Settings and names the next thing")
    fun firstStartShowsOnlyTheCard() = compose.use {
        val fixture = ScreenFixture()
        fixture.slice.recoveryStore.open(RecoveryCause.NewInstallation, fixture.slice.clock.instant())
        var settingsOpened = false
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = { settingsOpened = true }, onConfirmRecoveryCheck = {}, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {})
                }
            }
        }

        onNodeWithText("Set up Slussen in Settings").assertExists()
        onNodeWithText("Next: enter the ${slussen.testing.ExampleData.BUDGET_APP_NAME} credentials").assertExists()
        onNodeWithText("Uploads blocked: Recovery Check open").assertDoesNotExist()
        onNodeWithText("Fetch all Data Providers").assertDoesNotExist()
        onNodeWithText("Open Settings").performClick()
        org.junit.jupiter.api.Assertions.assertTrue(settingsOpened)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A6: Operator Takes the Remedy", businessRules = ["BR-006"])
    @DisplayName("A6: a Blocked Reason shows its remedy; entering credentials and fetching again are available, granting a Bank Consent is not and says so plainly (P9-13)")
    fun remediesAreShown() = compose.use {
        var opened: ServiceKey? = null
        val ui = BudgetUi(
            title = "Household",
            subtitle = "Budget · USD · from stored data",
            counts = "Eligible 0 · waiting for a decision 0",
            sources = listOf(
                SourceCard(
                    name = "Kestrel Feeds",
                    kind = "Data Provider",
                    health = "Source Health: no Fetch yet",
                    freshness = "Freshness: no data yet",
                    counts = "Not yet uploaded: Eligible 0 · waiting for a decision 0",
                    blocked = listOf(
                        BlockedLine("Blocked: Kestrel Feeds, credentials missing", RemedyAction("Enter credentials", true, ServiceKey.dataProvider(1), null)),
                        BlockedLine("Blocked: Kestrel Feeds, Fetch failed", RemedyAction("Fetch again", true, ServiceKey.dataProvider(1), null)),
                        BlockedLine("Blocked: Aurora Current, missing Bank Consent", RemedyAction("Grant Bank Consent", false, null, "Bank Consents are not in this build yet")),
                    ),
                ),
            ),
            bankAccounts = emptyList(),
            note = "Bank Transactions are not fetched in this build yet: a Fetch stores the Account Balances, so the counts stay at 0.",
        )
        setContent { SlussenTheme { BudgetScreen(ui) { opened = it.service } } }

        onNodeWithText("Grant Bank Consent").assertIsNotEnabled()
        onNodeWithText("Bank Consents are not in this build yet").assertExists()
        onNodeWithText("Bank Transactions are not fetched in this build yet", substring = true).assertExists()
        onNodeWithText("Fetch again").assertIsEnabled()
        onNodeWithText("Enter credentials").performClick()
        assertEquals(ServiceKey.dataProvider(1), opened)
    }

    /** UC-008 A10, BR-011: the overview is shown from the first added Bank Account on; here one of the File Format. */
    private fun aBankAccountIsAdded(fixture: ScreenFixture) = kotlinx.coroutines.runBlocking {
        val slice = fixture.slice
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as slussen.core.connection.BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-care"))
        val health = slice.connectionStore.budgets().single { it.served }.budgetAccounts.single().id
        slice.connect.addFromTransactionFiles(
            slice.plugins.fileFormats.single().id, slussen.core.port.BankAccountType.Savings,
            slussen.core.port.BankChoice.Named("Harbor Health Trust"), null, health, "Harbor Health Savings", null, slussen.testing.ExampleData.USD,
        )
    }
}
