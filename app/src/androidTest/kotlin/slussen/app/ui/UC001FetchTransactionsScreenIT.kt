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
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

/** Fetch Transactions (UC-001): the Fetch on a Data Provider's page, on a device or emulator. */
@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-001: Fetch Transactions, the screens on a device")
class UC001FetchTransactionsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Steps 1-6, 11: pressing Fetch on the Data Provider's page shows the Offered Bank Accounts delivered")
    fun fetchFromTheDataProvidersPage() = compose.use {
        val fixture = ScreenFixture()
        runBlocking { fixture.slice.enterCredentials() }
        fixture.slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(
                    DeliveredBankAccount(
                        "acct-4410", "Kestrel Bank", "CHK", "checking", ExampleData.USD, null,
                        BankAccountResult.Read(emptyList(), ExampleData.amount(4140, 50)),
                    ),
                ),
            )
        }
        val viewModel = ServicePageViewModel(fixture.services, fixture.slice.feedsKey)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                ServicePage(viewModel)
            }
        }

        onNodeWithText("Fetch Kestrel Feeds").performClick()

        onNodeWithText("Kestrel Feeds · Fetched 14:00 · Bank Accounts offered: 1 · not added: 1").assertExists()
        onNodeWithText("Connected · last Fetch 2026-10-15 14:00").assertExists()
    }
}
