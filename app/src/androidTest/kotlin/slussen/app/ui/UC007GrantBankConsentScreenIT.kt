package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.consent.BankConsentsSection
import slussen.app.ui.consent.BankConsentsViewModel
import slussen.app.ui.consent.ConsentAnswers
import slussen.app.ui.consent.ConsentBanner
import slussen.app.ui.consent.ConsentBannerTexts
import slussen.app.ui.consent.bankConsentActionsOf
import slussen.app.ui.theme.SlussenTheme
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import java.time.Instant

/**
 * Grant Bank Consent (UC-007): the Bank Consents part of a Data Provider's page on a device or emulator, as far as it
 * goes without a browser: the screen is given whether a browser opened, and the answer is handed in as the activity
 * hands it in. What only a device with a browser and a real Bank can show is on the operator's list.
 */
@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-007: Grant Bank Consent, the screens on a device")
class UC007GrantBankConsentScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private val vesta = "Vesta Sparbank (SE)"

    private fun ready(fixture: ScreenFixture, browser: Boolean): Pair<BankConsentsViewModel, ConsentAnswers> {
        val slice = fixture.slice
        runBlocking { slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        slice.bankConsent.banksAnswer = { BankList.Listed(listOf(BankChoice(vesta, vesta), BankChoice("Vestra Bank (SE)", "Vestra Bank (SE)"))) }
        slice.bankConsent.startAnswer = { ConsentStart.Started("https://vesta.example/sca?ticket=t", null) }
        slice.bankConsent.completeAnswer = { _, _ ->
            ConsentCompletion.Completed(Instant.parse("2027-04-12T10:00:00Z"), emptyList(), ConsentSession("session-it"))
        }
        val answers = ConsentAnswers(MutableStateFlow<UiServices?>(fixture.services), CoroutineScope(Dispatchers.Main), slice.clock)
        val viewModel = BankConsentsViewModel(fixture.services, slice.openBankingKey, answers, "SE") { browser }
        onMain { viewModel.load() }
        return viewModel to answers
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-010", "BR-011"])
    @DisplayName("Steps 1-4 (#none-yet, #choose-bank, #grant, #waiting): the return address with Copy, the chooser at the device's country with its search, the sheet, and the waiting card with the paste field")
    fun grantingUpToTheBrowser() = compose.use {
        val fixture = ScreenFixture()
        val (viewModel, _) = ready(fixture, browser = true)
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let { BankConsentsSection(it, bankConsentActionsOf(viewModel) {}) }
            }
        }

        onNodeWithText("No Bank Consent yet.").assertExists()
        onNodeWithText(ExampleData.CONSENT_RETURN_ADDRESS).assertExists()
        onNodeWithText("Copy").assertIsEnabled()
        onNodeWithText("Grant Bank Consent").performClick()
        onNodeWithText("SE · Sweden ›").assertExists()
        onNodeWithTag("bank-search").performTextInput("vesta")
        onNodeWithText("Vestra Bank (SE)").assertDoesNotExist()
        onNodeWithText(vesta).performClick()
        onNodeWithText("Grant Bank Consent · $vesta").assertExists()
        onNodeWithText("Open my Bank").performClick()
        // The browser is not opened here: the screen is told that it opened.
        onMain { viewModel.opened(true) }

        onNodeWithText("Waiting for the result · until 14:15").assertExists()
        onNodeWithText("Use this address").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A11: No Browser on the Device")
    @DisplayName("A11 (#no-browser): without a browser the sheet says so at Open my Bank, and nothing is sent")
    fun noBrowser() = compose.use {
        val fixture = ScreenFixture()
        val (viewModel, _) = ready(fixture, browser = false)
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let { BankConsentsSection(it, bankConsentActionsOf(viewModel) {}) }
            }
        }

        onNodeWithText("Grant Bank Consent").performClick()
        onNodeWithText(vesta).performClick()
        onNodeWithText("Open my Bank").performClick()

        onNodeWithText("A browser is needed to open your Bank's sign-in.").assertExists()
        assertTrue(fixture.slice.bankConsent.starts.isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 6 (D4 lessons): an answer handed in while another screen is shown appears in the banner there, with Open and OK")
    fun theResultOnAnotherScreen() = compose.use {
        val fixture = ScreenFixture()
        val (viewModel, answers) = ready(fixture, browser = true)
        onMain {
            viewModel.openChooser()
            viewModel.chooseBank(BankChoice(vesta, vesta))
            viewModel.openMyBank()
        }
        onMain { viewModel.opened(true) }
        onMain { viewModel.left() }
        val state = fixture.slice.bankConsent.stateTexts().last()
        onMain { answers.returned("${ExampleData.CONSENT_RETURN_ADDRESS}?code=c&state=$state") }
        var opened: slussen.core.port.ServiceKey? = null
        setContent {
            SlussenTheme {
                val shown = answers.state.collectAsState().value
                ConsentBannerTexts.of(shown, fixture.services, current = null)?.let {
                    ConsentBanner(it, onOpen = { service -> opened = service }, onCancel = { _, _ -> }, onDismiss = answers::dismiss)
                }
            }
        }

        onNodeWithText("✓ Bank Consent granted · $vesta").assertExists()
        onNodeWithText("Open").performClick()
        assertEquals(fixture.slice.openBankingKey, opened)
        onNodeWithText("OK").performClick()
        onNodeWithTag("consent-banner").assertDoesNotExist()
    }
}
