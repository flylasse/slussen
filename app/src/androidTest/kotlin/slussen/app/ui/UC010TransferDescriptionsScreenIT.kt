package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.settings.TransferDescriptionsScreen
import slussen.app.ui.settings.TransferDescriptionsViewModel
import slussen.app.ui.settings.descriptionActionsOf
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.TransferDescriptionKind
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-010: Adjust Settings, the Transfer Descriptions on a device")
class UC010TransferDescriptionsScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-007", "BR-008"], requirements = ["NFR-023"])
    @DisplayName("A7 (transfer-descriptions.html#lists, #edit): Add entry refuses autopay at the field as already in the list, then saves PINECREST PMT, which the Card Payment list shows as added by you with the answer at the list")
    fun anEntryIsAddedOnTheScreen() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = TransferDescriptionsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let { TransferDescriptionsScreen(it, onBack = {}, actions = descriptionActionsOf(viewModel)) }
            }
        }

        onNodeWithText("AUTOPAY").assertExists()
        onNodeWithTag("add-CardPayment").performClick()
        onNodeWithText("Add a Card Payment Description").assertExists()
        onNodeWithTag("description-value").performTextReplacement("autopay")
        onNodeWithTag("save-description").performClick()
        onNodeWithText("Not saved: autopay is already in the list as AUTOPAY. Enter another text.").assertExists()

        onNodeWithTag("description-value").performTextReplacement("PINECREST PMT")
        onNodeWithTag("save-description").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Saved: PINECREST PMT added.", substring = true))
        onNode(hasScrollAction()).performScrollToNode(hasTestTag("entry-CardPayment-9"))
        onNodeWithText("Added by you").assertExists()
        assertEquals(
            "PINECREST PMT",
            fixture.slice.copyStore.transferDescriptions.last { it.kind == TransferDescriptionKind.CardPayment }.text,
        )
    }
}
