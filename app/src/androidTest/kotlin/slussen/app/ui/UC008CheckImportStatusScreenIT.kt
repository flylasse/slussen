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
        fixture.slice.recoveryStore.open(RecoveryCause.NewInstallation, fixture.slice.clock.instant())
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onSetUp = {}, onConfirmRecoveryCheck = viewModel::confirmRecoveryCheck, onRecentFetches = {})
                }
            }
        }

        onNodeWithText("Uploads blocked: Recovery Check open").assertExists()
        onNodeWithText("New installation", substring = true).assertExists()
        onNodeWithText("Recovery Check").performClick()
        onNodeWithText("The Recovery Check stays open", substring = true).assertExists()
    }

    @Test
    @UseCase(id = "UC-008")
    @DisplayName("Precondition: with no Bank Account added, the Overview offers to set Slussen up")
    fun emptyFirstStartOffersSetUp() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onSetUp = {}, onConfirmRecoveryCheck = {}, onRecentFetches = {})
                }
            }
        }

        onNodeWithText("No Bank Account added yet").assertExists()
        onNodeWithText("Set up Slussen").assertIsEnabled()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A6: Operator Takes the Remedy", businessRules = ["BR-006"])
    @DisplayName("A6: a Blocked Reason shows its remedy; enter credentials is available, a remedy of a use case not built yet is not")
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
                        BlockedLine("Blocked: Kestrel Feeds, Fetch failed", RemedyAction("Fetch again", false, null, "comes with Fetch Transactions")),
                    ),
                ),
            ),
            bankAccounts = emptyList(),
        )
        setContent { SlussenTheme { BudgetScreen(ui) { opened = it.service } } }

        onNodeWithText("Fetch again").assertIsNotEnabled()
        onNodeWithText("comes with Fetch Transactions").assertExists()
        onNodeWithText("Enter credentials").performClick()
        assertEquals(ServiceKey.dataProvider(1), opened)
    }
}
