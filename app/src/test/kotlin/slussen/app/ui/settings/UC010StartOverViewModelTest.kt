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
import slussen.core.settings.StartOverUnavailable
import slussen.core.port.StartOverRecord
import slussen.core.operation.RunningOperation
import slussen.core.operation.OperationKind
import slussen.core.port.StartedBy
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
        assertTrue("a change of an Operator Setting is running" in answer, answer)
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
        assertEquals(
            "Not started over: it could not be recorded. Nothing was deleted. The Bank Consents Slussen asked to end are no longer usable: grant them again, or start over again.",
            answer,
        )
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
    @DisplayName("O-17 step 1 (`#start-over-failed`): a read that fails while the Bank Consents are being ended names the Banks already asked and how each went")
    fun aReadThatFailsWhileEnding() = runTest {
        slice.bankConsent.revokeAnswer = { RevokeResult.Ended }
        slice.bankConsents.start(slice.openBankingKey, "Nordvik Bank (SE)", browserAvailable = true)
        slice.bankConsent.completeAnswer = { _, _ -> ConsentCompletion.Completed(Instant.parse("2027-04-13T12:00:00Z"), emptyList(), ConsentSession("t")) }
        slice.bankConsents.complete(ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=c&state=${slice.bankConsent.stateTexts().last()}"))
        slice.bankConsent.revokeAnswer = { RevokeResult.Ended.also { slice.vault.failsToRead = true } }
        val vm = viewModel()
        vm.open(); vm.confirm()

        assertEquals(
            "Not started over: the stored data could not be read. Nothing was deleted. Already asked: $aurora, ended. " +
                "The Bank Consents Slussen asked to end are no longer usable: grant them again, or start over again.",
            vm.state.value.answer,
        )
    }

    // ---- The new installation's card (`#started-over`) --------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4 (`#started-over`): a start that finished Start Over names each Bank from its record, not confirmed apart from not contacted, until OK; nothing else can make it show")
    fun theCardNamesTheBanksUntilClosed() {
        val notice = StartedOverNotice()
        notice.atStart(StartOverRecord(listOf(
            ConsentNotEnded("Nordvik Bank (SE)", provider, NotEndedBecause.NotConfirmed),
            ConsentNotEnded(aurora, provider, NotEndedBecause.CredentialsMissing),
        ), complete = true))

        val card = checkNotNull(notice.card.value)
        assertEquals("Slussen started over", card.title)
        assertEquals(
            "Everything it stored was deleted. Not confirmed as ended at the Bank: Nordvik Bank (SE) ($provider did not confirm it). " +
                "Not contacted: $aurora ($provider's credentials were missing). Each stays at its Bank until it expires; you can end it at the Bank.",
            card.text,
        )
        notice.close()
        assertNull(notice.card.value)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4: an ordinary start shows no card, whatever started the app; a list not known or not complete is said so")
    fun theCardOnlyAfterStartOver() {
        val ordinary = StartedOverNotice()
        ordinary.atStart(null)
        assertNull(ordinary.card.value, "an activity start with any extra on an ordinary installation shows no card: no extra is read")

        val allEnded = StartedOverNotice().apply { atStart(StartOverRecord(emptyList(), complete = true)) }
        assertEquals(StartOverTexts.EVERY_CONSENT_ENDED, allEnded.card.value?.text)
        val notKnown = StartedOverNotice().apply { atStart(StartOverRecord(null, complete = false)) }
        assertEquals(StartOverTexts.NAMES_NOT_KNOWN, notKnown.card.value?.text)
        val partly = StartedOverNotice().apply { atStart(StartOverRecord(listOf(ConsentNotEnded(aurora, provider, NotEndedBecause.NotConfirmed)), complete = false)) }
        assertTrue(partly.card.value!!.text.endsWith(StartOverTexts.LIST_NOT_COMPLETE), partly.card.value?.text)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("A12 step 3 (`#start-over-unavailable`): the refusal for another operation reads as the mockup's")
    fun theRefusalReads() {
        assertEquals(
            "Not started over: a Fetch of SimpleFIN that the Fetch Schedule started is running. Nothing was deleted. Start over once it has ended.",
            StartOverTexts.notNow(StartOverUnavailable.AnotherOperation(RunningOperation(OperationKind.Fetch, "SimpleFIN", StartedBy.Schedule))),
        )
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
