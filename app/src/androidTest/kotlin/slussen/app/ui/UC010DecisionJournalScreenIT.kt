package slussen.app.ui

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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.settings.SettingsViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.JournalEventKind
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-010: Adjust Settings, the Decision Journal's control on a device")
class UC010DecisionJournalScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("A2, A3 (settings.html#journal-limit, #clear-journal): the size limit lowered below the current size asks first and removes the oldest entries; Clear asks first and its line says every entry is removed")
    fun theSizeLimitIsLoweredAndTheJournalCleared() = compose.use {
        val fixture = ScreenFixture()
        val journal = fixture.slice.journalRecorder
        journal.bytesPerEntry = 1_000_000
        runBlocking { repeat(12) { fixture.slice.journal.record(JournalEventKind.SessionStart, "Session $it started") } }
        val viewModel = SettingsViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                Settings(viewModel)
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasTestTag("journal-size-limit"))
        onNodeWithText("MB · default 50 · 10 to 500 · current size 12.0 MB").assertExists()
        onNodeWithTag("journal-size-limit").performClick()
        onNodeWithTag("setting-value").performTextReplacement("10")
        onNodeWithText("Save").performClick()
        onNodeWithText("Lower the size limit?").assertExists()
        onNodeWithTag("confirm-journal").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Saved: 10 MB (50 by default).", substring = true))
        onNodeWithText("The Decision Journal's 3 oldest entries were removed; it now takes 10.0 MB.", substring = true).assertExists()
        assertEquals(10, fixture.slice.settings.settings.journalSizeLimitMb)

        onNode(hasScrollAction()).performScrollToNode(hasTestTag("journal-clear"))
        onNodeWithTag("journal-clear").performClick()
        onNodeWithText("Clear the Decision Journal?").assertExists()
        onNodeWithTag("confirm-journal").performClick()

        onNode(hasScrollAction()).performScrollToNode(hasText("Cleared: every entry removed", substring = true))
        assertEquals(listOf("Decision Journal cleared"), journal.entries.map { it.details })
    }
}
