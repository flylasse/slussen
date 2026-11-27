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
import slussen.app.ui.service.ServicePageViewModel
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.NumberSetting
import slussen.core.port.ServiceKey
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

/**
 * The Operator Settings on Settings (`settings.html#list`, `#list-2`, `#out-of-range`, `#saved`, `#saved-read-owed`,
 * `#detailed-logging`, `#fetch-schedule`) and Upload as Cleared on the Budget App's page (UC-010 A9): every editor's
 * states, each answering at its own control (NFR-023), also with a storage that fails, never a crash.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, the editors of the Operator Settings")
class UC010OperatorSettingsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val app = ExampleData.BUDGET_APP_NAME

    private fun settings() = SettingsViewModel(fixture.services).also { it.load() }

    private fun SettingsViewModel.ui() = state.value!!

    private fun SettingsViewModel.line(setting: NumberSetting) = ui().line(setting)!!

    private fun SettingsViewModel.editor() = ui().numberEditor!!.editor

    /** Household, Summer House and Care Fund served, each read once before (each has a cursor). */
    private fun budgetsReadBefore() = runBlocking {
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), slice.clock.instant())
        slice.connectionStore.budgets().forEach { slice.copyStore.cursors[it.id] = ChangeCursor("c-${it.id}") }
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002"])
    @DisplayName("Step 1 (#list, #list-2): the sections of the mockup in order, each Operator Setting with its default, range and value; Transfer detection always on; Detailed Logging off")
    fun settingsShowsTheSectionsOfTheMockup() {
        val ui = settings().ui()

        assertEquals(listOf("Fetching", "Upload", "Transfers", "Status and warnings", "Decision Journal and logs"), ui.sections.map { it.title })
        assertEquals(
            listOf(
                "Fetch Schedule" to "Per 24 hours · default 2 · 0 to 2 (0 is off)",
                "Fetch period" to "Days back a Fetch asks for · default 30 · 7 to 180",
                "Default wait" to "Hours · default 8 · 1 to 48",
                "Request Limit warning pause" to "Hours · default 24 · 1 to 72",
                "Request timeout" to "Seconds · default 30 · 10 to 120",
                "Number of recent Fetches kept" to "Default 60 · 10 to 500",
            ),
            ui.sections[0].items.map { (it as SettingItemUi.Number).line.let { line -> line.name to line.hint } },
        )
        assertEquals(listOf("2", "30 days", "8 h", "24 h", "30 s", "60"), ui.sections[0].items.map { (it as SettingItemUi.Number).line.value })
        assertEquals("Days back, every File Format · default 60 · 7 to 365", ui.line(NumberSetting.FileUploadWindow)!!.hint)
        assertEquals("Days between two Transfer Legs · default 5 · 0 to 30", ui.line(NumberSetting.TransferPairingDays)!!.hint)
        assertEquals("Share of Android's backup size limit · default 80% · 50% to 95%", ui.line(NumberSetting.BackupWarningThreshold)!!.hint)
        assertEquals("80%", ui.line(NumberSetting.BackupWarningThreshold)!!.value)
        assertEquals(SettingItemUi.Shown(ShownSettingUi("Transfer detection", "Not an Operator Setting", "Always on")), ui.sections[2].items.first())
        assertEquals(SwitchLineUi("Detailed Logging", "Off by default", on = false, pending = null, answer = null, tag = "detailed-logging"), ui.detailedLogging)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-023"])
    @DisplayName("Steps 3-5 (#saved): Save of the Upload Window shows Saving… before anything is stored; saved, its line shows 10 days and that every status was given again from stored data; no other line shows an answer")
    fun uploadWindowSavingThenSaved() {
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.UploadWindow)
        assertEquals("New value · days · default 7", viewModel.editor().fieldName)
        viewModel.edit("10")

        viewModel.save("10")
        assertTrue(viewModel.editor().saving)
        assertEquals(7, slice.settings.settings.uploadWindowDays)
        // The dialog cannot be left, and no other opened, while the change is on its way.
        viewModel.cancel()
        viewModel.startEditing(NumberSetting.DefaultWait)
        assertEquals(NumberSetting.UploadWindow, viewModel.ui().numberEditor?.setting)
        hold.complete(Unit)

        assertNull(viewModel.ui().numberEditor)
        assertEquals("10 days", viewModel.line(NumberSetting.UploadWindow).value)
        assertEquals(
            AnswerUi(
                "Saved: 10 days (7 by default). Every Transaction Status given again from stored data, without contacting $app or any " +
                    "Source. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            viewModel.line(NumberSetting.UploadWindow).answer,
        )
        assertTrue(viewModel.ui().sections.flatMap { it.items }.filterIsInstance<SettingItemUi.Number>().filter { it.line.setting != NumberSetting.UploadWindow }.all { it.line.answer == null })
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-023"])
    @DisplayName("Step 5 (#saved-read-owed): a file upload window raised to 120 days answers that Household, Summer House and Care Fund each wait for a read of the Budget App, which gives their statuses, and that nothing is uploaded from them until then")
    fun longerFileUploadWindowSaysTheReadIsOwed() {
        budgetsReadBefore()
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FileUploadWindow)

        viewModel.save("120")

        assertEquals(
            AnswerUi(
                "Saved: 120 days (60 by default). Slussen's copy of $app does not reach back that far: Household, Summer House and " +
                    "Care Fund each wait for a read of $app, which gives their Transaction Statuses; until then they keep their " +
                    "statuses and nothing is uploaded from them. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            viewModel.line(NumberSetting.FileUploadWindow).answer,
        )
        assertEquals("120 days", viewModel.line(NumberSetting.FileUploadWindow).value)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Step 5: a longer fetch period that makes the read period longer says the Budgets wait for a read, not that no status changes")
    fun longerFetchPeriodSaysTheReadIsOwed() {
        budgetsReadBefore()
        val viewModel = settings()
        viewModel.startEditFetchPeriodAndSave("90")

        assertEquals(
            "Saved: 90 days (30 by default). Asked for from the next Fetch that contacts a Data Provider; stored Bank Transactions " +
                "stay. Slussen's copy of $app does not reach back that far: Household, Summer House and Care Fund each wait for a " +
                "read of $app, which gives their Transaction Statuses; until then they keep their statuses and nothing is uploaded " +
                "from them. Recorded in the Decision Journal.",
            viewModel.ui().fetchPeriod?.answer?.text,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Step 5, UC-003 BR-016 (budget.html#period-read-owed): after the read period became longer, the Budget tab says the Budget App must be read first, why, and that nothing is uploaded from it until then; no such line before")
    fun budgetTabShowsThatAReadIsOwed() {
        budgetsReadBefore()
        val household = runBlocking { slice.connectionStore.budgets().single { it.name == "Household" }.id }
        assertNull(slussen.app.ui.budget.BudgetViewModel(fixture.services, household).also { it.load() }.state.value!!.periodReadOwed)
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FileUploadWindow)
        viewModel.save("120")

        val owed = slussen.app.ui.budget.BudgetViewModel(fixture.services, household).also { it.load() }.state.value!!.periodReadOwed

        assertEquals("$app must be read first", owed?.first)
        assertEquals(
            "The read period is now longer than the days Slussen's copy of $app covers: a setting made it longer, or the Budget " +
                "gained a Budget Account. Until $app is read again, the Transaction Statuses stay and nothing is uploaded from " +
                "Household. Read $app again · no Bank Fetch",
            owed?.second,
        )
    }

    private fun SettingsViewModel.startEditFetchPeriodAndSave(text: String) {
        startEditingFetchPeriod()
        saveFetchPeriod(text)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Step 5, ADR-007 §3a: when the statuses cannot be given again now, the line says the value is saved and that no Upload Plan is prepared until they are")
    fun statusesPendingAreSaid() {
        slice.copyStore.failsToConclude = true
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.TransferPairingDays)

        viewModel.save("3")

        assertEquals(
            "Saved: 3 days (5 by default). The Transaction Statuses could not be given again yet; no Upload Plan is prepared until " +
                "they are, at the next start or the next read of $app. Recorded in the Decision Journal.",
            viewModel.line(NumberSetting.TransferPairingDays).answer?.text,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1 (#out-of-range): a value outside the range, or not a whole number, is refused at the field with what is wrong, what to enter and the value that stays, for days, shares and the Fetch Schedule; nothing is stored")
    fun outOfRangeIsRefusedAtTheField() {
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.UploadWindow)
        viewModel.save("90")
        assertEquals("90 days is too many. Enter 1 to 60 days. Still 7 days, the default.", viewModel.editor().error)
        viewModel.save("a week")
        assertEquals("Enter a whole number of days, 1 to 60. Still 7 days, the default.", viewModel.editor().error)
        viewModel.cancel()

        viewModel.startEditing(NumberSetting.BackupWarningThreshold)
        viewModel.save("40")
        assertEquals("40% is too low. Enter 50% to 95%. Still 80%, the default.", viewModel.editor().error)
        viewModel.cancel()

        viewModel.startEditing(NumberSetting.FetchSchedule)
        viewModel.save("3")
        assertEquals("3 is too many. Enter 0 to 2 (0 is off). Still 2 Scheduled Fetches in any 24 hours, the default.", viewModel.editor().error)
        viewModel.edit("1")
        assertNull(viewModel.editor().error)

        assertEquals(slussen.core.port.OperatorSettings.DEFAULT, slice.settings.settings)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6 (#fetch-schedule): the dialog states how many Scheduled Fetches Slussen will make under the value typed, none at 0, and that a Scheduled Fetch never uploads; saved, the line says the same")
    fun fetchScheduleStatesWhatItMeans() {
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FetchSchedule)
        assertEquals(
            "Slussen will make 2 Scheduled Fetches in any 24 hours (the Fetch Schedule, 2 by default, an Operator Setting). A " +
                "Scheduled Fetch only fetches and compares; it never uploads.",
            viewModel.editor().explanation,
        )
        viewModel.edit("0")
        assertEquals(
            "Slussen will make no Scheduled Fetch (the Fetch Schedule, 2 by default, an Operator Setting). A Scheduled Fetch only " +
                "fetches and compares; it never uploads.",
            viewModel.editor().explanation,
        )
        viewModel.edit("1")
        assertTrue(viewModel.editor().explanation.startsWith("Slussen will make one Scheduled Fetch in any 24 hours"))

        viewModel.save("0")

        assertEquals("Off", viewModel.line(NumberSetting.FetchSchedule).value)
        assertEquals(
            "Saved: 0 (off) (2 by default). Slussen will make no Scheduled Fetch (the Fetch Schedule, 2 by default, an Operator " +
                "Setting). A Scheduled Fetch only fetches and compares; it never uploads. Recorded in the Decision Journal.",
            viewModel.line(NumberSetting.FetchSchedule).answer?.text,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Failure postconditions: storing failed; the line keeps the previous value and says so with how to try again, and tapping it offers the value entered")
    fun notSavedKeepsTheValue() {
        slice.settings.failsToStore = true
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.DefaultWait)

        viewModel.save("12")

        assertNull(viewModel.ui().numberEditor)
        assertEquals("8 h", viewModel.line(NumberSetting.DefaultWait).value)
        assertEquals(
            AnswerUi("Not saved: Slussen could not store 12 hours. Still 8 hours, the default.", AnswerKind.Failed, "Tap the default wait to try again."),
            viewModel.line(NumberSetting.DefaultWait).answer,
        )
        viewModel.startEditing(NumberSetting.DefaultWait)
        assertEquals("12", viewModel.editor().value)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023: a value stored while the settings cannot be read again says Saved at its line with the value stored, and that the settings could not be read again; never a crash")
    fun savedButNotReadAgain() {
        val viewModel = settings()
        slice.settings.beforeStore = { slice.settings.failsToRead = true }
        viewModel.startEditing(NumberSetting.RequestTimeout)

        viewModel.save("60")

        assertEquals(60, slice.settings.settings.requestTimeoutSeconds)
        assertEquals("60 s", viewModel.line(NumberSetting.RequestTimeout).value)
        assertEquals(AnswerKind.Done, viewModel.line(NumberSetting.RequestTimeout).answer?.kind)
        assertTrue(viewModel.line(NumberSetting.RequestTimeout).answer!!.text.startsWith("Saved: 60 seconds (30 by default)."))
        assertEquals(
            "The Operator Settings could not be read again from this device; the values shown are the last ones read or stored. Open Settings again to see them.",
            viewModel.ui().notReadAgain,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023: a change whose settings cannot be read is answered Not saved at its line, and Settings says it could not read them again; never a crash")
    fun aChangeThatCannotReadTheSettingsIsNotSaved() {
        val viewModel = settings()
        slice.settings.failsToRead = true
        viewModel.startEditing(NumberSetting.OutdatedBalanceLimit)

        viewModel.save("48")

        assertEquals(AnswerKind.Failed, viewModel.line(NumberSetting.OutdatedBalanceLimit).answer?.kind)
        assertEquals("36 h", viewModel.line(NumberSetting.OutdatedBalanceLimit).value)
        assertTrue(viewModel.ui().notReadAgain != null)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023: Settings that cannot be read when opened says so with Try again, and shows nothing as stored")
    fun settingsNotOpened() {
        slice.settings.failsToRead = true

        val viewModel = settings()

        assertNull(viewModel.state.value)
        assertEquals("Settings could not be read from this device; nothing was changed. Try again.", viewModel.notOpened.value)
        slice.settings.failsToRead = false
        viewModel.load()
        assertNull(viewModel.notOpened.value)
        assertEquals("30 days", viewModel.ui().fetchPeriod?.value)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, Save says why in the dialog, which stays open; nothing is stored")
    fun unavailableWhileAPlanAwaitsConfirmation() {
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.UploadPlanLifetime)
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        viewModel.save("20")

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", viewModel.editor().error)
        assertFalse(viewModel.editor().saving)
        assertEquals(10, slice.settings.settings.uploadPlanMinutes)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 2: Cancel stores and records nothing; the line of that setting says the value is unchanged")
    fun cancelKeepsTheValue() {
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.LonelyTransferGracePeriod)
        viewModel.edit("9")

        viewModel.cancel()

        assertEquals(AnswerUi("Not changed: still 6 days, the default.", AnswerKind.Unchanged), viewModel.line(NumberSetting.LonelyTransferGracePeriod).answer)
        assertEquals(6, slice.settings.settings.lonelyTransferGraceDays)
    }

    // ---- A5: Detailed Logging ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"], requirements = ["NFR-023"])
    @DisplayName("A5 (#detailed-logging): switching on first states what the logs then contain and never contain; Switch on shows Switching on… until the answer is at the line; the log follows the stored value")
    fun detailedLoggingIsSwitchedOnAfterTheStatement() {
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val viewModel = settings()

        viewModel.tapDetailedLogging()
        val statement = viewModel.ui().detailedLoggingStatement!!
        assertEquals("Switch on Detailed Logging?", statement.title)
        assertTrue("Credentials, Bank Consent data and Bank Sessions are never logged, even then." in statement.text)
        assertFalse(slice.settings.settings.detailedLogging)
        viewModel.confirmDetailedLogging()
        assertEquals(StatementUi(statement.title, statement.text, "Switching on…", saving = true), viewModel.ui().detailedLoggingStatement)
        assertEquals("Switching on…", viewModel.ui().detailedLogging?.pending)
        viewModel.cancelDetailedLogging()
        assertTrue(viewModel.ui().detailedLoggingStatement != null)
        hold.complete(Unit)

        assertNull(viewModel.ui().detailedLoggingStatement)
        assertEquals(true, viewModel.ui().detailedLogging?.on)
        assertNull(viewModel.ui().detailedLogging?.pending)
        assertEquals(
            AnswerUi(
                "Saved: on (off by default). Device logs now contain transaction data and Bank Account numbers, on this device only; " +
                    "never credentials or Bank Consent data. Recorded in the Decision Journal.",
                AnswerKind.Done,
            ),
            viewModel.ui().detailedLogging?.answer,
        )
        assertTrue(slice.detailedLoggingApplied)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"])
    @DisplayName("A5: Cancel in the statement stores nothing and says so at the line; switching off needs no statement and shows Switching off… at the line")
    fun detailedLoggingCancelledAndSwitchedOff() {
        val viewModel = settings()
        viewModel.tapDetailedLogging()
        viewModel.cancelDetailedLogging()
        assertEquals(AnswerUi("Not changed: still off, the default.", AnswerKind.Unchanged), viewModel.ui().detailedLogging?.answer)
        assertFalse(slice.settings.settings.detailedLogging)
        viewModel.tapDetailedLogging()
        viewModel.confirmDetailedLogging()
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }

        viewModel.tapDetailedLogging()
        assertNull(viewModel.ui().detailedLoggingStatement)
        assertEquals("Switching off…", viewModel.ui().detailedLogging?.pending)
        hold.complete(Unit)

        assertEquals(false, viewModel.ui().detailedLogging?.on)
        assertEquals(AnswerKind.Done, viewModel.ui().detailedLogging?.answer?.kind)
        assertFalse(slice.detailedLoggingApplied)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", requirements = ["NFR-023"])
    @DisplayName("A5, failure postconditions: Detailed Logging that cannot be stored stays off, also for the log, and the line says so with how to try again")
    fun detailedLoggingNotStored() {
        slice.settings.failsToStore = true
        val viewModel = settings()
        viewModel.tapDetailedLogging()

        viewModel.confirmDetailedLogging()

        assertNull(viewModel.ui().detailedLoggingStatement)
        assertEquals(false, viewModel.ui().detailedLogging?.on)
        assertEquals(
            AnswerUi("Not saved: Slussen could not store it. Still off, the default.", AnswerKind.Failed, "Tap Detailed Logging to try again."),
            viewModel.ui().detailedLogging?.answer,
        )
        assertFalse(slice.detailedLoggingApplied)
    }

    // ---- A9: Upload as Cleared on the Budget App's page -------------------------------------------------------------

    private fun budgetAppPage() = ServicePageViewModel(fixture.services, ServiceKey.budgetApp).also { it.load() }

    @Test
    @UseCase(id = "UC-010", scenario = "A9: Switch Upload as Cleared", businessRules = ["BR-010"], requirements = ["NFR-023"])
    @DisplayName("A9 (BR-010): the Budget App's page shows Upload as Cleared as stored with what a change means; switched off it shows Switching off… at the switch, then the answer there")
    fun uploadAsClearedIsSwitchedOff() {
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val page = budgetAppPage()
        val shown = page.state.value!!.uploadAsCleared!!
        assertTrue(shown.on)
        assertEquals(
            "A change applies only to Upload Plans prepared from now on and changes nothing already in $app. While it is off, " +
                "Slussen marks nothing Cleared, Transfer Counterparts included, and clearing is left to you in $app.",
            shown.statement,
        )

        page.switchUploadAsCleared(false)
        assertEquals("Switching off…", page.state.value!!.uploadAsCleared?.pending)
        assertTrue(slice.settings.settings.uploadAsCleared)
        hold.complete(Unit)

        val after = page.state.value!!.uploadAsCleared!!
        assertFalse(after.on)
        assertNull(after.pending)
        assertEquals(
            AnswerUi("Saved: off (on by default). Applies to Upload Plans prepared from now on. Recorded in the Decision Journal.", AnswerKind.Done),
            after.answer,
        )
        assertFalse(slice.settings.settings.uploadAsCleared)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A9: Switch Upload as Cleared", requirements = ["NFR-023"])
    @DisplayName("A9, failure postconditions: Upload as Cleared that cannot be stored stays on, and the switch says so with how to try again; refused while an Upload Plan awaits confirmation")
    fun uploadAsClearedNotStoredOrRefused() {
        slice.settings.failsToStore = true
        val page = budgetAppPage()

        page.switchUploadAsCleared(false)

        assertEquals(
            ClearedAnswer("Not saved: Slussen could not store it. Still on, the default.", AnswerKind.Failed, "Tap the switch to try again."),
            page.state.value!!.uploadAsCleared!!.let { ClearedAnswer(it.answer!!.text, it.answer!!.kind, it.answer!!.detail) },
        )
        assertTrue(page.state.value!!.uploadAsCleared!!.on)
        slice.settings.failsToStore = false
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        page.switchUploadAsCleared(false)

        assertEquals(
            "Not changed: Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first. Still on, the default.",
            page.state.value!!.uploadAsCleared!!.answer?.text,
        )
        assertTrue(slice.settings.settings.uploadAsCleared)
    }

    private data class ClearedAnswer(val text: String, val kind: AnswerKind, val detail: String?)
}
