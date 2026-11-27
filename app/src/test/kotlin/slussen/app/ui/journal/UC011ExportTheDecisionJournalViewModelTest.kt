package slussen.app.ui.journal

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.ServiceKey
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.InMemoryJournalExportDocument
import slussen.testing.JsonText
import java.time.Duration

/**
 * The Journal Export screen (`journal-export.html`, UC-011): choosing codes and the place, the export's state at its
 * control from the tap on, before anything is read, and its answer there: complete, complete from the stored copy (A2),
 * cancelled (A1), failed with the file removed or named as incomplete (A3), and nothing to export (precondition); what
 * the export answers is shown, never a crash (NFR-023).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-011: Export the Decision Journal, the screen")
class UC011ExportTheDecisionJournalViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val journal = slice.journalRecorder
    private val document = InMemoryJournalExportDocument()
    private val place = "slussen-journal-2026-10-15.json"

    private fun screen() = JournalExportViewModel(fixture.services).also { it.load() }

    private fun JournalExportViewModel.ui() = state.value

    private fun entries(count: Int) = runBlocking {
        repeat(count) { slice.journal.record(JournalEventKind.SessionStart, "Session $it started") }
    }

    /** The three Budgets served; Household read at 13:00 device time, two days before. */
    private fun served() = runBlocking {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), slice.clock.instant())
        val household = slice.connectionStore.budgetList.indexOfFirst { it.name == "Household" }
        slice.connectionStore.budgetList[household] =
            slice.connectionStore.budgetList[household].copy(lastReadAt = slice.clock.instant().minus(Duration.ofHours(1)))
    }

    /** Through the place the Operator chose: Choose where to save…, then the document of the picker. */
    private fun JournalExportViewModel.exportTo(document: InMemoryJournalExportDocument = this@UC011ExportTheDecisionJournalViewModelTest.document) {
        assertEquals(place, choosePlace())
        export(document, place)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Steps 1-2 (#choose): the screen offers the codes switch, off, and Choose where to save…, which proposes slussen-journal-<date>.json")
    fun theScreenOffersCodesAndThePlace() {
        entries(1)
        val screen = screen()

        assertEquals(
            JournalExportUi(
                intro = "With the Budget App's current transactions of your Budgets.",
                codes = false,
                chooseEnabled = true,
                nothingToExport = null,
                writing = null,
                answer = null,
                latest = emptyList(),
            ),
            screen.ui(),
        )
        assertEquals(place, screen.choosePlace())
        assertFalse(screen.ui().chooseEnabled, "while Android's save dialog is open the control waits for it")
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Precondition (#empty): with no entry in the Decision Journal the screen says there is nothing to export yet and Choose where to save… cannot be tapped")
    fun nothingToExportYet() {
        val screen = screen()

        assertEquals(AnswerUi("Nothing to export yet", AnswerKind.Unchanged, "The Decision Journal holds no entry"), screen.ui().nothingToExport)
        assertFalse(screen.ui().chooseEnabled)
        assertNull(screen.choosePlace())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A1: Operator Cancels")
    @DisplayName("A1 (#cancelled): no place chosen: no file is written and the control says so")
    fun noPlaceChosen() {
        entries(1)
        val screen = screen()
        screen.choosePlace()

        screen.noPlaceChosen()

        assertEquals(AnswerUi("No place chosen", AnswerKind.Unchanged, "No file was written."), screen.ui().answer)
        assertTrue(screen.ui().chooseEnabled)
        assertFalse(document.opened)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Steps 3-5 (#writing, #complete): from the tap on, before anything is read, the control says the Budget App is read and the file written, to the place chosen; then complete with the number of entries and the latest entries")
    fun theExportShowsItsStateThenItsAnswer() {
        served()
        entries(1_233)
        val screen = screen()
        journal.hold()

        screen.exportTo()

        assertEquals(WritingUi("Reading Example Budget App… then writing the Journal Export", "To $place"), screen.ui().writing)
        assertFalse(screen.ui().chooseEnabled)
        assertNull(screen.ui().answer)
        assertFalse(document.opened)
        journal.release()

        assertNull(screen.ui().writing)
        assertEquals(AnswerUi("Journal Export complete", AnswerKind.Done, "Entries: 1,235 · $place"), screen.ui().answer)
        assertEquals(
            listOf(
                "14:00 Session Start · Session 1232 started",
                "14:00 Session Start · Session 1231 started",
                "14:00 Session Start · Session 1230 started",
            ),
            screen.ui().latest,
        )
        assertTrue(screen.ui().chooseEnabled)
        assertFalse(document.removed)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("Step 2, BR-005: with the switch on, the export replaces payee and memo text by codes")
    fun theSwitchChoosesCodes() {
        served()
        entries(1)
        val screen = screen()

        screen.setCodes(true)
        assertTrue(screen.ui().codes)
        screen.exportTo()

        @Suppress("UNCHECKED_CAST")
        val header = (JsonText.parse(document.text) as Map<String, Any?>)["header"] as Map<String, Any?>
        assertEquals(true, header["codes"])
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read")
    @DisplayName("A2 (#stored-copy): complete from the stored copy names each Budget not read, why, and the time of its copy, and the Budgets read now")
    fun completeFromTheStoredCopy() {
        served()
        entries(2)
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") {
                ReadResult.TransientFailure(FailureKind.ConnectionLost)
            } else {
                ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("c")))
            }
        }
        val screen = screen()

        screen.exportTo()

        assertEquals(
            AnswerUi(
                "Journal Export complete, from the stored copy",
                AnswerKind.Done,
                "Entries: 4 · $place · Example Budget App could not be reached for Household: its transactions from the copy of 13:00 · " +
                    "Summer House and Care Fund read now",
            ),
            screen.ui().answer,
        )
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read")
    @DisplayName("A2: without credentials, a Budget never read is named with none of its transactions")
    fun completeWithABudgetNeverRead() {
        served()
        runBlocking { slice.credentials.remove(ServiceKey.budgetApp) }
        entries(1)
        val screen = screen()

        screen.exportTo()

        val answer = screen.ui().answer!!
        assertEquals("Journal Export complete, from the stored copy", answer.title())
        assertTrue(
            answer.detail!!.contains("No Example Budget App credentials are entered for Summer House: never read, none of its transactions are in the export"),
            answer.detail,
        )
    }

    private fun AnswerUi.title() = text

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written")
    @DisplayName("A3 (#failed): a write that fails part-way is answered at the control: the incomplete file was removed and no Journal Export was written")
    fun aWriteThatFailsIsAnswered() {
        served()
        entries(500)
        document.failsAfterCharacters = 2_000
        val screen = screen()

        screen.exportTo()

        assertEquals(
            AnswerUi(
                "Export failed",
                AnswerKind.Failed,
                "The file could not be written completely at the chosen place. The incomplete file was removed; no Journal Export was written.",
            ),
            screen.ui().answer,
        )
        assertTrue(document.removed)
        assertNull(screen.ui().writing)
        assertTrue(screen.ui().latest.isEmpty())
        assertTrue(screen.ui().chooseEnabled)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written")
    @DisplayName("A3 step 1: when the incomplete file cannot be removed, the control names it as incomplete and no Journal Export")
    fun aFileThatCannotBeRemovedIsNamed() {
        served()
        entries(500)
        document.failsAfterCharacters = 2_000
        document.failsToRemove = true
        val screen = screen()

        screen.exportTo()

        assertEquals(
            AnswerUi(
                "Export failed",
                AnswerKind.Failed,
                "The file could not be written completely at the chosen place. The file $place is incomplete and is no Journal " +
                    "Export; remove it at the place you chose.",
            ),
            screen.ui().answer,
        )
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Precondition, UC-006 BR-005: while an Upload Plan awaits confirmation the export is answered as unavailable at the control, and no file is left")
    fun unavailableWhileAPlanIsOpen() {
        served()
        entries(1)
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val screen = screen()

        screen.exportTo()

        assertEquals(
            AnswerUi(
                "Export failed",
                AnswerKind.Failed,
                "Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first. No file was written.",
            ),
            screen.ui().answer,
        )
        assertTrue(document.removed)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written")
    @DisplayName("NFR-023: a Decision Journal that cannot be read is answered at the control; nothing is shown as exported")
    fun aJournalThatCannotBeReadIsAnswered() {
        served()
        entries(1)
        val screen = screen()
        journal.failsToReadEntries = true

        screen.exportTo()

        assertEquals(
            AnswerUi("Export failed", AnswerKind.Failed, "The Decision Journal could not be read. No file was written."),
            screen.ui().answer,
        )
    }
}
