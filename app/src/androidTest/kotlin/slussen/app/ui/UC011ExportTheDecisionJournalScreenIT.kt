package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.journal.JournalExportScreen
import slussen.app.ui.journal.JournalExportViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.JournalEventKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.InMemoryJournalExportDocument

/**
 * The Journal Export screen on a device (`journal-export.html`, UC-011), as far as it goes without Android's save dialog:
 * a tap on Choose where to save… asks the ViewModel for the name to propose, as the dialog would be opened with it, and
 * the test then hands over the document the dialog would give. Invented data only (C-005).
 */
@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-011: Export the Decision Journal, the screen on a device")
class UC011ExportTheDecisionJournalScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private val place = "slussen-journal-2026-10-15.json"

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("Steps 1-5 (#choose, #complete): codes switched on, Choose where to save… proposes the name, and the export of the document given is complete with its entries and the latest ones")
    fun theExportIsChosenAndComplete() = compose.use {
        val fixture = ScreenFixture()
        runBlocking { repeat(4) { fixture.slice.journal.record(JournalEventKind.SessionStart, "Session $it started") } }
        val viewModel = JournalExportViewModel(fixture.services)
        val proposed = mutableListOf<String>()
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                JournalExportScreen(
                    ui = viewModel.state.collectAsState().value,
                    onBack = {},
                    onCodes = viewModel::setCodes,
                    onChoose = { viewModel.choosePlace()?.let { proposed += it } },
                )
            }
        }

        onNodeWithText("Replace payee and memo text by codes").assertExists()
        onNodeWithTag("export-codes").performClick()
        onNodeWithTag("export-codes").assertIsOn()
        onNodeWithText("Choose where to save…").performClick()
        assertEquals(listOf(place), proposed)
        onNodeWithTag("export-choose").assertIsNotEnabled()
        val document = InMemoryJournalExportDocument()
        onMain { viewModel.export(document, place) }

        onNodeWithText("Journal Export complete").assertExists()
        onNodeWithText("Entries: 4 · $place").assertExists()
        onNodeWithText("Latest entries in the export".uppercase()).assertExists()
        onNodeWithText("Session Start · Session 3 started", substring = true).assertExists()
        assertTrue(document.text.contains("\"codes\":true"))
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written")
    @DisplayName("A1, A3 (#cancelled, #failed): no place chosen says no file was written; a write that fails part-way says the incomplete file was removed")
    fun cancelledAndFailed() = compose.use {
        val fixture = ScreenFixture()
        runBlocking { repeat(300) { fixture.slice.journal.record(JournalEventKind.SessionStart, "Session $it started") } }
        val viewModel = JournalExportViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                JournalExportScreen(viewModel.state.collectAsState().value, onBack = {}, onCodes = viewModel::setCodes, onChoose = { viewModel.choosePlace() })
            }
        }

        onNodeWithText("Choose where to save…").performClick()
        onMain { viewModel.noPlaceChosen() }
        onNodeWithText("No place chosen").assertExists()
        onNodeWithText("No file was written.").assertExists()

        onNodeWithText("Choose where to save…").performClick()
        val document = InMemoryJournalExportDocument().apply { failsAfterCharacters = 1_000 }
        onMain { viewModel.export(document, place) }

        onNodeWithText("Export failed").assertExists()
        onNodeWithText("The incomplete file was removed; no Journal Export was written.", substring = true).assertExists()
        assertTrue(document.removed)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Precondition (#empty): an empty Decision Journal is nothing to export yet, and Choose where to save… cannot be tapped")
    fun nothingToExportYet() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = JournalExportViewModel(fixture.services)
        onMain { viewModel.load() }
        setContent {
            SlussenTheme {
                JournalExportScreen(viewModel.state.collectAsState().value, onBack = {}, onCodes = viewModel::setCodes, onChoose = { viewModel.choosePlace() })
            }
        }

        onNodeWithText("Nothing to export yet").assertExists()
        onNodeWithText("The Decision Journal holds no entry").assertExists()
        onNodeWithTag("export-choose").assertIsNotEnabled()
    }
}
