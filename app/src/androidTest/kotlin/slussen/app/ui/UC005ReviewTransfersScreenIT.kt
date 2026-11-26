package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.budget.BudgetScreen
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.app.ui.transfers.TransfersScreen
import slussen.app.ui.transfers.TransfersTexts
import slussen.app.ui.transfers.TransfersViewModel
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-005: Review Transfers, the screen on a device")
class UC005ReviewTransfersScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1 (budget.html → transfers.html#list, #selected): the Budget tab's entry, whose counts agree with the list, opens the Transfers; each Transfer shows its two Budget Accounts, sides and state, and a tap opens one read-only in place of the list, which system back and Close each close again")
    fun listAndASelectedTransfer() = compose.use {
        val fixture = ScreenFixture()
        val scenario = EveryStatusScenario(fixture.slice)
        kotlinx.coroutines.runBlocking { scenario.compareEveryStatus(withLonelyTransfer = true) }
        val household = kotlinx.coroutines.runBlocking { fixture.slice.connectionStore.budgets().single { it.name == "Household" }.id }
        val budget = BudgetViewModel(fixture.services, household)
        val transfers = TransfersViewModel(fixture.services, household)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            budget.load()
            transfers.load()
        }
        var onTransfers by mutableStateOf(false)
        setContent {
            SlussenTheme {
                if (!onTransfers) {
                    budget.state.collectAsState().value?.let {
                        BudgetScreen(it, onRemedy = {}, onStartFirstUploadMatching = {}, onTransfers = { onTransfers = true })
                    }
                } else {
                    TransfersScreen(transfers.state.collectAsState().value, onBack = {}, onOpen = transfers::open, onClose = transfers::close)
                }
            }
        }

        onNodeWithText("Transfers: 4 · Guessed Transfers: 2").assertExists()
        onNodeWithText(TransfersTexts.OPEN_TRANSFERS).performClick()
        onNodeWithText("Transfers in Household: 4 · of them Guessed Transfers: 2").assertExists()
        onNodeWithText(TransfersTexts.READ_ONLY).assertExists()
        onNodeWithText("Checking? → Rewards Card").assertExists()
        onNodeWithText("Kestrel Checking −410.00 · 10-12 · arrived").assertExists()
        onNode(hasScrollAction()).performScrollToNode(hasText("Kestrel Reserve +500.00 · 10-11 · arrived"))
        onNodeWithText("both sides arrived · eligible, through the Kestrel Checking side").assertExists()
        onNode(hasScrollAction()).performScrollToNode(hasText("Checking −120.00 · expected by 2026-10-19 · a later Fetch may bring it"))
        onNodeWithText("Checking −120.00 · expected by 2026-10-19 · a later Fetch may bring it").assertExists()

        onNode(hasScrollAction()).performScrollToNode(hasText("Kestrel Reserve +500.00 · 10-11 · arrived"))
        onNodeWithText("Kestrel Reserve +500.00 · 10-11 · arrived").performClick()
        onNodeWithText("Bank Transfer · 500.00 USD").assertExists()
        onNodeWithText("Transfers in Household: 4 · of them Guessed Transfers: 2").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        onNodeWithText("Transfers in Household: 4 · of them Guessed Transfers: 2").assertExists()

        onNode(hasScrollAction()).performScrollToNode(hasText("Checking? → Rewards Card"))
        onNodeWithText("Checking? → Rewards Card").performClick()
        onNodeWithText("Pinecrest Rewards Card has no Funded By, so its paying side is only proposed.").assertExists()
        onNodeWithText("Close").performClick()
        onNodeWithText("Transfers in Household: 4 · of them Guessed Transfers: 2").assertExists()
    }
}
