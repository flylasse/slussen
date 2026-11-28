package slussen.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.core.consent.CompletionOutcome
import slussen.core.consent.ConsentNotEnded
import slussen.core.consent.NotEndedBecause
import slussen.core.consent.StartOutcome
import slussen.core.port.CoordinatorMode
import slussen.core.settings.StartOverResult
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.testing.ExampleData
import slussen.testing.ScriptedBankConsent
import java.time.Instant

/**
 * Start Over on Settings (`settings.html#start-over` to `#started-over`): the statement and its confirmation, each state
 * at its own row before and after anything is sent, every answer that leaves the data as it was, and the card of the new
 * installation that names the Banks. The rules themselves are `UC010StartOverTest`'s.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, A12 Start Over, the screen")
class UC010StartOverViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val provider = ExampleData.openBankingProfile.name
    private val aurora = "Aurora Sparbank (FI)"

    @BeforeEach
    fun aBankConsent() = runTest {
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        slice.bankConsent.startAnswer = { ConsentStart.Started("https://bank.example/sca", Secret("handle")) }
        slice.bankConsent.completeAnswer = { _, _ -> ConsentCompletion.Completed(Instant.parse("2027-04-13T12:00:00Z"), emptyList(), ConsentSession("s")) }
        slice.bankConsents.start(slice.openBankingKey, aurora, browserAvailable = true) as StartOutcome.Started
        val answer = ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=c&state=${slice.bankConsent.stateTexts().last()}")
        assertTrue(slice.bankConsents.complete(answer) is CompletionOutcome.Granted)
    }

    private fun viewModel() = StartOverViewModel(fixture.services)

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 steps 1-2: the row opens the statement with the numbers and the Bank Consents to end; Cancel deletes nothing and asks nothing")
    fun theStatementAndCancel() = runTest {
        val vm = viewModel()
        assertNull(vm.state.value.dialog)

        vm.open()

        val dialog = checkNotNull(vm.state.value.dialog)
        assertEquals("Start over?", dialog.title)
        assertTrue("0 Bank Accounts" in dialog.text, dialog.text)
        assertTrue("0 Bank Transactions" in dialog.text, dialog.text)
        assertTrue("$provider to end the Bank Consent of $aurora" in dialog.text, dialog.text)
        assertTrue("stays at the Bank until it expires" in dialog.text)
        assertTrue("nothing is uploaded until First Upload Matching is confirmed" in dialog.text)
        assertEquals("Delete everything", dialog.confirm)
        vm.cancel()
        assertNull(vm.state.value.dialog)
        assertTrue(slice.bankConsent.revoked.isEmpty())
        assertFalse(slice.startOverStore.recorded)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("A12 step 4, NFR-023: once confirmed, the row says what is asked before anything is sent and that nothing is deleted yet; the process is then started again with the Banks to name")
    fun confirmedShowsItsStateAtItsControl() = runTest {
        val seen = mutableListOf<String?>()
        slice.bankConsent.revokeAnswer = {
            seen += viewModelRunning
            RevokeResult.EndedAtDataProvider
        }
        val vm = viewModel().also { model = it }
        vm.open()

        vm.confirm()

        assertNull(vm.state.value.dialog)
        assertEquals(listOf("Asking $provider to end the Bank Consent of $aurora"), seen)
        assertEquals(listOf(StartOverResult(listOf(ConsentNotEnded(aurora, provider, NotEndedBecause.EndedAtDataProviderOnly)))), fixture.restarted)
        assertEquals(StartOverTexts.RESTARTING, vm.state.value.running)
        assertFalse(vm.state.value.enabled)
        assertTrue(slice.startOverStore.recorded)
    }

    private var model: StartOverViewModel? = null
    private val viewModelRunning: String? get() = model?.state?.value?.running

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("A12 step 3: while another operation runs the answer at the row names it, and nothing was deleted")
    fun notNowWhileAnotherOperationRuns() = runTest {
        val held = CompletableDeferred<Unit>()
        val running = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launchHeld(held)
        val vm = viewModel()
        vm.open()

        vm.confirm()

        val answer = vm.state.value.answer.orEmpty()
        assertTrue(answer.startsWith("Not started over:"), answer)
        assertTrue("a change of an Operator Setting" in answer, answer)
        assertTrue("Nothing was deleted" in answer, answer)
        assertNull(vm.state.value.running)
        assertTrue(vm.state.value.enabled)
        assertFalse(slice.startOverStore.recorded)
        held.complete(Unit)
        running.join()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchHeld(held: CompletableDeferred<Unit>) = this.launch {
        slice.coordinator.run(slussen.core.operation.OperationKind.OperatorSettingChange, slussen.core.port.StartedBy.Operator) { held.await() }
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("A12 step 3: a Consent Request that waits, a Journal Export being written and an open Upload Plan each have their answer")
    fun notNowAnswers() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val vm = viewModel()
        vm.open(); vm.confirm()
        assertTrue("an Upload Plan awaits confirmation" in vm.state.value.answer.orEmpty(), vm.state.value.answer)
        slice.coordinatorStore.mode = CoordinatorMode.Normal

        slice.bankConsents.start(slice.openBankingKey, "Waiting Bank (FI)", browserAvailable = true)
        vm.open(); vm.confirm()
        assertTrue("a Consent Request for Waiting Bank (FI) waits for the Bank's result" in vm.state.value.answer.orEmpty(), vm.state.value.answer)

        assertEquals(
            "Not started over: a Journal Export is being written. Nothing was deleted. Start over once it has ended.",
            StartOverTexts.notNow(slussen.core.settings.StartOverUnavailable.JournalExportBeingWritten),
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 2 (`#start-over-failed`): an obligation that cannot be recorded is answered at the row: nothing deleted, the Bank Consents asked named")
    fun notRecorded() = runTest {
        slice.startOverStore.fails = true
        val vm = viewModel()
        vm.open(); vm.confirm()

        val answer = vm.state.value.answer.orEmpty()
        assertTrue(answer.startsWith("Not started over: it could not be recorded."), answer)
        assertTrue("Nothing was deleted" in answer)
        assertTrue(aurora in answer, answer)
        assertTrue(vm.state.value.enabled)
        assertTrue(fixture.restarted.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("NFR-023: stored data that cannot be read for the statement is answered at the row, with no dialog")
    fun statementNotRead() = runTest {
        slice.consentStore.failsToRead = true
        val vm = viewModel()

        vm.open()

        assertNull(vm.state.value.dialog)
        assertEquals(StartOverTexts.STATEMENT_NOT_READ, vm.state.value.answer)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 3 (`#start-over-failed`): when the app cannot be started again, the row asks the Operator to close Slussen and open it again, and stays unavailable")
    fun notRestarted() = runTest {
        fixture.restartFails = true
        val vm = viewModel()
        vm.open(); vm.confirm()

        assertEquals(StartOverTexts.OPEN_AGAIN, vm.state.value.answer)
        assertNull(vm.state.value.running)
        assertFalse(vm.state.value.enabled)
    }

    // ---- The new installation's card (`#started-over`) --------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4 (`#started-over`): the new installation names each Bank whose Bank Consent was not confirmed as ended, with why, until OK, and keeps nothing after")
    fun theCardNamesTheBanksUntilClosed() {
        val notice = StartedOverNotice()
        notice.receive(StartOverHandOver.decode(StartOverHandOver.encode(StartOverResult(listOf(
            ConsentNotEnded("Nordvik Bank (SE)", provider, NotEndedBecause.NotConfirmed),
            ConsentNotEnded(aurora, provider, NotEndedBecause.CredentialsMissing),
        )))))
        notice.servicesStarted(startedOver = true)

        val card = checkNotNull(notice.card.value)
        assertEquals("Slussen started over", card.title)
        assertTrue("Nordvik Bank (SE) ($provider did not confirm it)" in card.text, card.text)
        assertTrue("$aurora ($provider's credentials were missing)" in card.text, card.text)
        assertTrue("stays at its Bank until it expires" in card.text)
        notice.close()
        assertNull(notice.card.value)
        notice.servicesStarted(startedOver = true)
        assertNull(notice.card.value, "closed means not shown again in this process")
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4: every Bank Consent ended is said so; a start that finished Start Over without the names (a process that ended between) says that they are not known")
    fun theCardWithoutNames() {
        val allEnded = StartedOverNotice()
        allEnded.receive(StartOverHandOver.decode(StartOverHandOver.encode(StartOverResult(emptyList()))))
        assertEquals(StartOverTexts.EVERY_CONSENT_ENDED, allEnded.card.value?.text)

        val lost = StartedOverNotice()
        lost.servicesStarted(startedOver = true)
        assertEquals(StartOverTexts.NAMES_NOT_KNOWN, lost.card.value?.text)
        lost.receive(StartOverResult(emptyList()))
        assertEquals(StartOverTexts.EVERY_CONSENT_ENDED, lost.card.value?.text, "names that arrive after the start replace the line")

        val normal = StartedOverNotice()
        normal.servicesStarted(startedOver = false)
        assertNull(normal.card.value)
        assertNull(StartOverHandOver.decode(null))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4, UC-001 BR-013: Start Over cancels the Scheduled Fetches the Fetch Schedule had set; the new installation's first start sets them again, a whole period from then")
    fun theFetchScheduleIsCancelledAndSetAgain() = runTest {
        fixture.services.fetchSchedule.atStart()
        assertTrue(fixture.scheduleWork.current != null)

        assertTrue(fixture.services.fetchSchedule.startedOver())

        assertNull(fixture.scheduleWork.current)
        assertEquals("cancel", fixture.scheduleWork.calls.last())
        fixture.services.fetchSchedule.atStart()
        assertEquals("schedule every PT12H first after PT12H", fixture.scheduleWork.calls.last())
    }
}
