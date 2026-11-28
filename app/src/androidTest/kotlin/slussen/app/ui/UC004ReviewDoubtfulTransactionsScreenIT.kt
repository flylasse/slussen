package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.bankaccount.BankAccountScreen
import slussen.app.ui.bankaccount.BankAccountViewModel
import slussen.app.ui.bankaccount.reviewActionsOf
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.TransactionStatus
import slussen.core.status.TransactionKey
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-004: Review Doubtful Transactions, the Review Decision sheet on a device")
class UC004ReviewDoubtfulTransactionsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    @Test
    @UseCase(id = "UC-004", scenario = "A1: Import Anyway", businessRules = ["BR-001", "BR-006"])
    @DisplayName("Steps 1-2, A1 (review-decision.html#possible-import, #decided): a Possible Import By Other Path opened on its Bank Account says why it is doubtful and offers the three choices; Import Anyway answers at the control and the Bank Transaction is New")
    fun importAnywayOnTheSheet() = compose.use {
        val fixture = ScreenFixture()
        val scenario = EveryStatusScenario(fixture.slice)
        runBlocking { scenario.compareEveryStatus() }
        val screen = BankAccountViewModel(fixture.services, scenario.bankAccount("Kestrel Checking"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            screen.load(TransactionKey(unreadable = false, id = scenario.transaction("possible")))
        }
        setContent {
            SlussenTheme {
                BankAccountScreen(
                    screen.state.collectAsState().value, onBack = {}, onOpen = screen::open, onStatuses = screen::openStatuses,
                    onClose = screen::close, review = reviewActionsOf(screen),
                )
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasText("Import Anyway"))
        onNodeWithText("Resembles an Import by another importer in Checking; not proof of a duplicate").assertExists()
        onNodeWithText("Already Present…").assertExists()
        onNodeWithText("Defer").assertExists()
        onNodeWithText("Import Anyway").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Review Decision stored: Import Anyway · Transaction Status now New"))
        onNodeWithText("Review Decision stored: Import Anyway · Transaction Status now New").assertExists()
        assertEquals(TransactionStatus.New, fixture.slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction("possible") }.status)
    }
}
