package slussen.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
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
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.NumberSetting
import slussen.pluginapi.testfixtures.UseCase

/**
 * The Decision Journal's control on Settings (`settings.html#list-2`, `#journal-limit`, `#clear-journal`,
 * `#journal-near`; UC-010 A2, A3, A4, BR-003, the success postconditions): its switch, its size limit with its current
 * size, its warning threshold and its clearing, each showing its state at its own line before anything is stored and its
 * answer there, also when the journal's database or the settings fail, never a crash (NFR-023).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, the Decision Journal's control on Settings")
class UC010DecisionJournalViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val journal = slice.journalRecorder

    private fun settings() = SettingsViewModel(fixture.services).also { it.load() }

    private fun SettingsViewModel.ui() = state.value!!

    private fun SettingsViewModel.line(control: JournalControlKind) = ui().journal(control)!!

    /** [count] earlier entries of 1,000,000 bytes each, through the core. */
    private fun entries(count: Int) = runBlocking {
        journal.bytesPerEntry = 1_000_000
        repeat(count) { slice.journal.record(JournalEventKind.SessionStart, "Session $it started") }
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002", "BR-003"])
    @DisplayName("Step 1 (#list-2): the Decision Journal and logs show the switch on, the size limit of 50 MB with the current size in MB of one decimal, the warning threshold, Clear Decision Journal… and Detailed Logging, in that order; no line says they cannot be changed here")
    fun theDecisionJournalLinesOfTheMockup() {
        journal.baseBytes = 12_449_999
        val ui = settings().ui()

        val section = ui.sections.single { it.title == "Decision Journal and logs" }
        assertEquals(
            listOf(
                JournalLineUi(JournalControlKind.Switch, "Decision Journal", "On by default", "On", null, null, "journal-switch"),
                JournalLineUi(JournalControlKind.SizeLimit, "Size limit", "MB · default 50 · 10 to 500 · current size 12.4 MB", "50 MB", null, null, "journal-size-limit"),
                JournalLineUi(JournalControlKind.WarningThreshold, "Warning threshold", "Share of the size limit · default 80% · 50% to 95%", "80%", null, null, "journal-warning"),
                JournalLineUi(JournalControlKind.Clear, "Clear Decision Journal…", "Every entry removed; nothing else changes", "", null, null, "journal-clear"),
            ),
            section.items.filterIsInstance<SettingItemUi.Journal>().map { it.line },
        )
        assertTrue(section.items.last() is SettingItemUi.Switch)
        assertNull(section.note)
        assertNull(ui.journalNearLimit)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-003"])
    @DisplayName("A4 (#journal-near): at 80% of its size limit Settings warns at its top with the current size and the limit, and names what keeps more entries")
    fun nearItsLimitSettingsWarns() {
        journal.baseBytes = 41_000_000

        assertEquals(
            JournalWarningUi(
                "Decision Journal nearly full",
                "41.0 MB of 50 MB, the size limit set in Operator Settings. Above it the oldest entries are removed; raise the size " +
                    "limit under Decision Journal and logs to keep more of them.",
            ),
            settings().ui().journalNearLimit,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-004"], requirements = ["NFR-023"])
    @DisplayName("A4, NFR-023: a size the journal's database cannot give is said on the size limit's line; Settings opens and warns of nothing")
    fun aSizeThatCannotBeRead() {
        journal.failsToReadSize = true
        val viewModel = settings()

        assertEquals("MB · default 50 · 10 to 500 · current size could not be read", viewModel.line(JournalControlKind.SizeLimit).hint)
        assertNull(viewModel.ui().journalNearLimit)
    }

    // ---- The switch --------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("Postconditions: a tap on the Decision Journal shows Switching off… at its line before anything is stored; stored, the line shows Off and says that this was its last entry and that its entries stay")
    fun switchingOffShowsItsStateThenItsAnswer() {
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val viewModel = settings()

        viewModel.tapJournal(JournalControlKind.Switch)
        assertEquals("Switching off…", viewModel.line(JournalControlKind.Switch).pending)
        assertTrue(slice.settings.settings.journalOn)
        // Nothing else of the Decision Journal can be started while it is on its way.
        viewModel.tapJournal(JournalControlKind.Clear)
        assertNull(viewModel.ui().journalConfirm)
        hold.complete(Unit)

        val line = viewModel.line(JournalControlKind.Switch)
        assertEquals("Off", line.value)
        assertNull(line.pending)
        assertEquals(
            AnswerUi(
                "Saved: off (on by default). Switching off is its last entry; Slussen records nothing in the Decision Journal from " +
                    "now on, and the entries in it stay.",
                AnswerKind.Done,
            ),
            line.answer,
        )
        assertFalse(slice.settings.settings.journalOn)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Postconditions: switched on again, the line shows On and says that this is its first entry; another setting's answer no longer says recorded while it was off")
    fun switchingOnSaysItIsTheFirstEntry() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Switch)

        viewModel.tapJournal(JournalControlKind.Switch)

        val line = viewModel.line(JournalControlKind.Switch)
        assertEquals("On", line.value)
        assertEquals(
            AnswerUi("Saved: on, the default. Recorded as its first entry; Slussen records in the Decision Journal from now on.", AnswerKind.Done),
            line.answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("Failure postconditions: a switch that cannot be stored is answered at its line with the state that stays and how to try again")
    fun aSwitchThatCannotBeStored() {
        slice.settings.failsToStore = true
        val viewModel = settings()

        viewModel.tapJournal(JournalControlKind.Switch)

        val line = viewModel.line(JournalControlKind.Switch)
        assertEquals("On", line.value)
        assertEquals(AnswerUi("Not saved: Slussen could not store it. Still on, the default.", AnswerKind.Failed, "Tap Decision Journal to try again."), line.answer)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("Precondition: while an Upload Plan awaits confirmation the switch answers why at its line; it stays on")
    fun theSwitchWhileAPlanIsOpen() {
        val viewModel = settings()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        viewModel.tapJournal(JournalControlKind.Switch)

        val answer = viewModel.line(JournalControlKind.Switch).answer!!
        assertEquals(AnswerKind.Failed, answer.kind)
        assertTrue(answer.text.startsWith("Not changed: "), answer.text)
        assertTrue(answer.text.endsWith(" Still on, the default."), answer.text)
        assertEquals("On", viewModel.line(JournalControlKind.Switch).value)
    }

    // ---- A2: the size limit ------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("A2 (#journal-limit): a size limit below the current size asks first, naming both sizes; Lower shows Lowering… before anything is stored, then the line shows the new limit, how many oldest entries were removed and the size now")
    fun aLowerLimitAsksFirstThenRemovesTheOldest() {
        entries(12)
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        assertEquals("New value · MB · default 50", viewModel.ui().journalEditor!!.editor.fieldName)

        viewModel.saveJournal("10")

        assertNull(viewModel.ui().journalEditor)
        assertEquals(
            JournalConfirmUi(
                JournalControlKind.SizeLimit, "Lower the size limit?",
                "The new size limit of the Decision Journal, an Operator Setting, is 10 MB. Its current size, 12.0 MB, is above it, so " +
                    "its oldest entries will be removed.",
                "Lower",
            ),
            viewModel.ui().journalConfirm,
        )
        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
        journal.hold()

        viewModel.confirmJournal()
        assertEquals("Lowering…", viewModel.ui().journalConfirm!!.confirm)
        assertTrue(viewModel.ui().journalConfirm!!.saving)
        viewModel.cancelJournalConfirm()
        assertTrue(viewModel.ui().journalConfirm!!.saving)
        journal.release()

        assertNull(viewModel.ui().journalConfirm)
        val line = viewModel.line(JournalControlKind.SizeLimit)
        assertEquals("10 MB", line.value)
        assertEquals("MB · default 50 · 10 to 500 · current size 10.0 MB", line.hint)
        assertEquals(
            AnswerUi(
                "Saved: 10 MB (50 by default). The Decision Journal's 3 oldest entries were removed; it now takes 10.0 MB. Recorded in the " +
                    "Decision Journal.",
                AnswerKind.Done,
            ),
            line.answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("A2 step 2: Cancel at the question stores and removes nothing; the line says the size limit is unchanged")
    fun aLowerLimitCancelled() {
        entries(12)
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        viewModel.saveJournal("10")

        viewModel.cancelJournalConfirm()

        assertNull(viewModel.ui().journalConfirm)
        assertEquals(AnswerUi("Not changed: still 50 MB, the default. No entry was removed.", AnswerKind.Unchanged), viewModel.line(JournalControlKind.SizeLimit).answer)
        assertEquals(12, journal.entries.size)
        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-023"])
    @DisplayName("BR-004: a lower size limit stored while the journal's database cannot trim says so at its line and when its oldest entries are removed")
    fun aLowerLimitWhoseTrimmingFails() {
        entries(12)
        journal.failsToTrim = true
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        viewModel.saveJournal("10")

        viewModel.confirmJournal()

        assertEquals(
            AnswerUi(
                "Saved: 10 MB (50 by default). The Decision Journal's oldest entries could not be removed now; Slussen removes them " +
                    "after the next entry it records that is not about the Decision Journal's own settings. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            viewModel.line(JournalControlKind.SizeLimit).answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-023"])
    @DisplayName("BR-003: a trimming that fails part-way says how many oldest entries were removed and that the rest could not be now")
    fun aLowerLimitWhoseTrimmingFailsPartWay() {
        entries(12)
        journal.trimFailsAfter = 1
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        viewModel.saveJournal("10")

        viewModel.confirmJournal()

        assertEquals(
            AnswerUi(
                "Saved: 10 MB (50 by default). The Decision Journal's oldest entry was removed; the others above the limit could not " +
                    "be removed now. Slussen removes them after the next entry it records that is not about the Decision Journal's own " +
                    "settings. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            viewModel.line(JournalControlKind.SizeLimit).answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-023"])
    @DisplayName("BR-003: while the Decision Journal is off, a trimming that failed says when its oldest entries really are removed")
    fun aLowerLimitWhoseTrimmingFailsWhileOff() {
        entries(12)
        journal.failsToTrim = true
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Switch)
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        viewModel.saveJournal("10")

        viewModel.confirmJournal()

        assertEquals(
            AnswerUi(
                "Saved: 10 MB (50 by default). The Decision Journal's oldest entries could not be removed now. While it is off they " +
                    "are removed only when its size limit is lowered again; once it is on, after the next entry it records that is not " +
                    "about its own settings.",
                AnswerKind.Done,
            ),
            viewModel.line(JournalControlKind.SizeLimit).answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-023"])
    @DisplayName("BR-004: Settings opens at once while the journal's own thread is busy; the size limit's line says the size is being worked out, and shows it when it is read")
    fun settingsOpensWhileTheJournalIsBusy() {
        journal.baseBytes = 12_449_999
        journal.hold()

        val viewModel = settings()

        assertEquals("MB · default 50 · 10 to 500 · current size being worked out…", viewModel.line(JournalControlKind.SizeLimit).hint)
        assertEquals("50 MB", viewModel.line(JournalControlKind.SizeLimit).value)
        journal.release()
        assertEquals("MB · default 50 · 10 to 500 · current size 12.4 MB", viewModel.line(JournalControlKind.SizeLimit).hint)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-004"], requirements = ["NFR-023"])
    @DisplayName("BR-004: another setting's change is answered at its line while the journal's own thread is busy; its entry waits there")
    fun anotherSettingDoesNotWaitForTheJournal() {
        val viewModel = settings()
        journal.hold()
        viewModel.startEditing(NumberSetting.DefaultWait)

        viewModel.save("9")

        assertNull(viewModel.ui().numberEditor)
        assertTrue(viewModel.ui().line(NumberSetting.DefaultWait)!!.answer!!.text.startsWith("Saved: 9 hours"))
        assertTrue(journal.waiting > 0)
        journal.release()
        assertEquals("Default wait changed from 8 to 9 hours", journal.entries.last().details)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: a higher size limit is stored without a question and says that no entry is removed")
    fun aHigherLimit() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)

        viewModel.saveJournal("100")

        assertNull(viewModel.ui().journalConfirm)
        assertEquals(
            AnswerUi("Saved: 100 MB (50 by default). No entry is removed. Recorded in the Decision Journal.", AnswerKind.Done),
            viewModel.line(JournalControlKind.SizeLimit).answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-002"])
    @DisplayName("A1, BR-002: a size limit outside 10 to 500 MB, or not a whole number, is refused at the field with the range and the limit that stays; the dialog can be used again")
    fun aLimitOutsideTheRangeAtTheField() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)

        viewModel.saveJournal("501")
        assertEquals("501 MB is too large. Enter 10 to 500 MB. Still 50 MB, the default.", viewModel.ui().journalEditor!!.editor.error)
        assertFalse(viewModel.ui().journalEditor!!.editor.saving)
        viewModel.editJournal("ten")
        assertNull(viewModel.ui().journalEditor!!.editor.error)
        viewModel.saveJournal("ten")
        assertEquals("Enter a whole number of MB, 10 to 500. Still 50 MB, the default.", viewModel.ui().journalEditor!!.editor.error)

        viewModel.saveJournal("40")
        assertNull(viewModel.ui().journalEditor)
        assertEquals("40 MB", viewModel.line(JournalControlKind.SizeLimit).value)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("Failure postconditions: a size limit that cannot be stored is answered at its line with the limit that stays, and the typed value is offered again")
    fun aLimitThatCannotBeStored() {
        slice.settings.failsToStore = true
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)

        viewModel.saveJournal("100")

        assertEquals(
            AnswerUi(
                "Not saved: Slussen could not store 100 MB. Still 50 MB, the default; no entry was removed.",
                AnswerKind.Failed,
                "Tap the size limit to try again.",
            ),
            viewModel.line(JournalControlKind.SizeLimit).answer,
        )
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        assertEquals("100", viewModel.ui().journalEditor!!.editor.value)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("Precondition: a lower size limit confirmed while an Upload Plan awaits confirmation is answered at its line; nothing is stored or removed")
    fun aConfirmedLowerLimitWhileAPlanIsOpen() {
        entries(12)
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.SizeLimit)
        viewModel.saveJournal("10")
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        viewModel.confirmJournal()

        assertNull(viewModel.ui().journalConfirm)
        val answer = viewModel.line(JournalControlKind.SizeLimit).answer!!
        assertEquals(AnswerKind.Failed, answer.kind)
        assertTrue(answer.text.endsWith("Still 50 MB, the default; no entry was removed."), answer.text)
        assertEquals(12, journal.entries.size)
    }

    // ---- The warning threshold ----------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-002", "BR-003"])
    @DisplayName("A4, BR-002: the warning threshold refuses 49% at the field, then saves 70% and says at what size Slussen warns and that no entry is removed")
    fun theWarningThreshold() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.WarningThreshold)
        assertEquals("New value · percent · default 80%", viewModel.ui().journalEditor!!.editor.fieldName)

        viewModel.saveJournal("49")
        assertEquals("49% is too low. Enter 50% to 95%. Still 80%, the default.", viewModel.ui().journalEditor!!.editor.error)
        viewModel.saveJournal("70")

        assertEquals("70%", viewModel.line(JournalControlKind.WarningThreshold).value)
        assertEquals(
            AnswerUi(
                "Saved: 70% (80% by default). Slussen warns when the Decision Journal reaches 35.0 MB of its 50 MB; no entry is " +
                    "removed. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            viewModel.line(JournalControlKind.WarningThreshold).answer,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-003"])
    @DisplayName("Step 2: Cancel in the warning threshold's dialog stores nothing and says it is unchanged")
    fun theWarningThresholdCancelled() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.WarningThreshold)

        viewModel.cancelJournal()

        assertNull(viewModel.ui().journalEditor)
        assertEquals(AnswerUi("Not changed: still 80%, the default.", AnswerKind.Unchanged), viewModel.line(JournalControlKind.WarningThreshold).answer)
    }

    // ---- A3: clearing -------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"], requirements = ["NFR-023"])
    @DisplayName("A3 (#clear-journal): Clear asks first; Clear shows Clearing… before anything is removed; cleared, the line says every entry is removed, the size now, that the clearing is its first new entry and that nothing else changed")
    fun clearingAsksFirstThenAnswers() {
        entries(30)
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Clear)
        assertEquals(
            JournalConfirmUi(
                JournalControlKind.Clear, "Clear the Decision Journal?",
                "Every entry is removed and the space it takes is freed. The clearing is recorded as its first new entry. Nothing " +
                    "else changes: no Transaction Status, Upload Plan or Upload depends on the Decision Journal.",
                "Clear",
            ),
            viewModel.ui().journalConfirm,
        )
        journal.hold()

        viewModel.confirmJournal()
        assertEquals("Clearing…", viewModel.ui().journalConfirm!!.confirm)
        assertEquals(30, journal.entries.size)
        journal.release()

        assertNull(viewModel.ui().journalConfirm)
        assertEquals(
            AnswerUi(
                "Cleared: every entry removed and its space freed; it now takes 1.0 MB. The clearing is recorded as its first new " +
                    "entry; nothing else changed.",
                AnswerKind.Done,
            ),
            viewModel.line(JournalControlKind.Clear).answer,
        )
        assertEquals("MB · default 50 · 10 to 500 · current size 1.0 MB", viewModel.line(JournalControlKind.SizeLimit).hint)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("A3: while the Decision Journal is off, the question and the answer say that nothing is recorded of the clearing")
    fun clearingWhileOff() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Switch)
        viewModel.tapJournal(JournalControlKind.Clear)
        assertTrue(viewModel.ui().journalConfirm!!.text.contains("Nothing is recorded of the clearing while the Decision Journal is off."))

        viewModel.confirmJournal()

        assertEquals(
            AnswerUi(
                "Cleared: every entry removed and its space freed; it now takes 0.0 MB. Nothing is recorded of it while the Decision " +
                    "Journal is off; nothing else changed.",
                AnswerKind.Done,
            ),
            viewModel.line(JournalControlKind.Clear).answer,
        )
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"], requirements = ["NFR-023"])
    @DisplayName("A3, NFR-023: a clearing the journal's database cannot do is answered at its line: every entry stays, and how to try again")
    fun aClearingThatFails() {
        entries(4)
        journal.failsToClear = true
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Clear)

        viewModel.confirmJournal()

        assertNull(viewModel.ui().journalConfirm)
        assertEquals(
            AnswerUi("Not cleared: Slussen could not clear the Decision Journal; every entry stays.", AnswerKind.Failed, "Tap Clear Decision Journal… to try again."),
            viewModel.line(JournalControlKind.Clear).answer,
        )
        assertEquals(4, journal.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("A3: Cancel at the question removes nothing and says so at the line")
    fun clearingCancelled() {
        entries(4)
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Clear)

        viewModel.cancelJournalConfirm()

        assertNull(viewModel.ui().journalConfirm)
        assertEquals(AnswerUi("Not cleared: every entry stays.", AnswerKind.Unchanged), viewModel.line(JournalControlKind.Clear).answer)
        assertEquals(4, journal.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"], requirements = ["NFR-023"])
    @DisplayName("Precondition: a clearing confirmed while an Upload is written is answered at its line with why; every entry stays")
    fun clearingWhileAnUploadIsWritten() {
        entries(4)
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.Clear)
        slice.coordinatorStore.mode = CoordinatorMode.Uploading

        viewModel.confirmJournal()

        val answer = viewModel.line(JournalControlKind.Clear).answer!!
        assertEquals(AnswerKind.Failed, answer.kind)
        assertTrue(answer.text.startsWith("Not cleared: "), answer.text)
        assertTrue(answer.text.endsWith(" Every entry stays."), answer.text)
        assertEquals(4, journal.entries.size)
    }

    // ---- One dialog at a time; the settings read again -------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("While another setting's dialog is open the Decision Journal's lines open nothing, and while a Decision Journal question is open no other dialog opens")
    fun oneDialogAtATime() {
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.DefaultWait)
        viewModel.tapJournal(JournalControlKind.Clear)
        viewModel.tapJournal(JournalControlKind.Switch)
        assertNull(viewModel.ui().journalConfirm)
        assertTrue(slice.settings.settings.journalOn)
        viewModel.cancel()

        viewModel.tapJournal(JournalControlKind.Clear)
        viewModel.startEditing(NumberSetting.DefaultWait)
        viewModel.tapDetailedLogging()

        assertNull(viewModel.ui().numberEditor)
        assertNull(viewModel.ui().detailedLoggingStatement)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"], requirements = ["NFR-023"])
    @DisplayName("NFR-023: a change stored when the settings cannot be read again afterwards shows the value stored and says that they could not be read again")
    fun theSettingsCannotBeReadAgain() {
        val viewModel = settings()
        viewModel.tapJournal(JournalControlKind.WarningThreshold)
        slice.settings.beforeStore = { slice.settings.failsToRead = true }

        viewModel.saveJournal("70")

        assertEquals("70%", viewModel.line(JournalControlKind.WarningThreshold).value)
        assertTrue(viewModel.ui().notReadAgain!!.startsWith("The Operator Settings could not be read again"))
    }
}
