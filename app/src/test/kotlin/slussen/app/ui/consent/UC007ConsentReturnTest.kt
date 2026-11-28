package slussen.app.ui.consent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.app.ui.UiFixture
import slussen.app.ui.UiServices
import slussen.core.consent.AnswerNotAccepted
import slussen.core.consent.CompletionOutcome
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ScriptedBankConsent
import java.time.Duration
import java.time.Instant

/**
 * UC-007 steps 6-7 and BR-011 as plain code decides them: which intent that reaches the activity is an answer of the
 * Bank's Strong Customer Authentication, and how [ConsentAnswers] hands each answer to the core once, at once, also when
 * Slussen was stopped meanwhile, and never twice. Every value is invented; the planted ones are easy to search for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("UC-007: Grant Bank Consent, the return to Slussen")
class UC007ConsentReturnTest {
    private val debug = "slussen.app.debug"
    private val release = "slussen.app"

    private fun arrived(
        address: String?,
        action: String? = ConsentReturn.VIEW,
        restored: Boolean = false,
        fromRecents: Boolean = false,
    ) = ArrivedIntent(action, address, restored, fromRecents)

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: Slussen's own return address is <application id>://consent, so a debug and a release build each take only their own answers")
    fun theOwnReturnAddressIsFormedFromTheApplicationId() {
        assertEquals("slussen.app://consent", ConsentReturn.ownReturnAddress(release))
        assertEquals("slussen.app.debug://consent", ConsentReturn.ownReturnAddress(debug))

        assertNull(ConsentReturn.answerIn(arrived("slussen.app://consent?code=c&state=s"), debug))
        assertNull(ConsentReturn.answerIn(arrived("slussen.app.debug://consent?code=c&state=s"), release))
        assertEquals("slussen.app://consent?code=c&state=s", ConsentReturn.answerIn(arrived("slussen.app://consent?code=c&state=s"), release))
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 6: a view of the own return address is an answer; the launcher, another address, another host and a view without an address are not")
    fun whichIntentIsAnAnswer() {
        val answer = "slussen.app.debug://consent?code=PLANTED-CODE-11&state=PLANTED-STATE-22"
        assertEquals(answer, ConsentReturn.answerIn(arrived(answer), debug))
        assertEquals("slussen.app.debug://consent", ConsentReturn.answerIn(arrived("slussen.app.debug://consent"), debug))
        assertEquals("slussen.app.debug://consent#state=s", ConsentReturn.answerIn(arrived("slussen.app.debug://consent#state=s"), debug))

        assertNull(ConsentReturn.answerIn(arrived(answer, action = "android.intent.action.MAIN"), debug))
        assertNull(ConsentReturn.answerIn(arrived(null, action = "android.intent.action.MAIN"), debug))
        assertNull(ConsentReturn.answerIn(arrived(null), debug))
        assertNull(ConsentReturn.answerIn(arrived("slussen.app.debug://consented?state=s"), debug))
        assertNull(ConsentReturn.answerIn(arrived("slussen.app.debug://other?state=s"), debug))
        assertNull(ConsentReturn.answerIn(arrived("https://bank.example/return?state=s"), debug))
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: an intent handed again is not an answer again: the activity restored after a configuration change or the end of the process, or the task brought back from recents")
    fun anIntentHandedAgainIsNoAnswer() {
        val answer = "slussen.app.debug://consent?code=c&state=s"
        assertNull(ConsentReturn.answerIn(arrived(answer, restored = true), debug))
        assertNull(ConsentReturn.answerIn(arrived(answer, fromRecents = true), debug))
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-006"], requirements = ["NFR-008"])
    @DisplayName("BR-006: what arrives is never shown by its toString, which a log or a crash report could print")
    fun theAddressIsMasked() {
        val arrived = arrived("slussen.app.debug://consent?code=PLANTED-CODE-33&state=PLANTED-STATE-44")
        assertFalse(arrived.toString().contains("PLANTED"))
        assertFalse(OneTimeText("PLANTED-PASTE-55").toString().contains("PLANTED"))
    }

    // ---- ConsentAnswers: once, at once, off the screen -------------------------------------------------------------

    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val bank = "Vesta Sparbank (SE)"

    private fun scripted() {
        slice.bankConsent.startAnswer = { ConsentStart.Started("https://vesta.example/sca?ticket=PLANTED-TICKET-66", null) }
        slice.bankConsent.completeAnswer = { _, _ ->
            ConsentCompletion.Completed(Instant.parse("2027-04-12T10:00:00Z"), emptyList(), ConsentSession("PLANTED-SESSION-77"))
        }
    }

    private suspend fun startedState(): String {
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        scripted()
        slice.bankConsents.start(slice.openBankingKey, bank, browserAvailable = true)
        return slice.bankConsent.stateTexts().last()
    }

    private fun answerTo(state: String) = "${ExampleData.CONSENT_RETURN_ADDRESS}?code=PLANTED-CODE-88&state=$state"

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-011"])
    @DisplayName("Steps 6-10: an answer that arrives while Slussen runs is handed to the core at once, and its result is kept for the screens")
    fun anAnswerWhileSlussenRuns() = runTest {
        val state = startedState()
        val answers = ConsentAnswers(MutableStateFlow<UiServices?>(fixture.services), CoroutineScope(UnconfinedTestDispatcher(testScheduler)), slice.clock)
        answers.readWaiting()
        assertEquals(listOf(bank), answers.state.value.waiting.map { it.bankReference })

        answers.returned(answerTo(state))

        val result = answers.state.value.result!!
        assertTrue((result.end as ConsentEnd.Completed).outcome is CompletionOutcome.Granted)
        assertEquals(slice.openBankingKey, result.service)
        assertEquals(0, answers.state.value.completing)
        assertTrue(answers.state.value.waiting.isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: after a cold start the answer in the launching intent waits for the services and keeps the time it arrived, so a slow start does not make it late")
    fun anAnswerAtAColdStart() = runTest {
        val state = startedState()
        slice.clock.advance(Duration.ofMinutes(14))
        val services = MutableStateFlow<UiServices?>(null)
        val answers = ConsentAnswers(services, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), slice.clock)

        answers.returned(answerTo(state))
        assertEquals(1, answers.state.value.completing)
        slice.clock.advance(Duration.ofMinutes(5))
        services.value = fixture.services

        val end = answers.state.value.result!!.end as ConsentEnd.Completed
        assertTrue(end.outcome is CompletionOutcome.Granted)
        assertEquals(0, answers.state.value.completing)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: the same answer delivered twice is handed in once more but never shown as \"already answered\": the result of the first stays")
    fun anAnswerDeliveredTwice() = runTest {
        val state = startedState()
        val answers = ConsentAnswers(MutableStateFlow<UiServices?>(fixture.services), CoroutineScope(UnconfinedTestDispatcher(testScheduler)), slice.clock)

        answers.returned(answerTo(state))
        val first = answers.state.value.result
        answers.returned(answerTo(state))

        assertEquals(first, answers.state.value.result)
        assertEquals(1, slice.consentStore.bankConsents().size)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002", "BR-011"])
    @DisplayName("BR-011: an address the Operator pastes takes the same completion; pasted again, it is said to be reused, since he acted")
    fun aPastedAddressTwice() = runTest {
        val state = startedState()
        val answers = ConsentAnswers(MutableStateFlow<UiServices?>(fixture.services), CoroutineScope(UnconfinedTestDispatcher(testScheduler)), slice.clock)

        answers.pasted(slice.openBankingKey, answerTo(state))
        assertTrue(((answers.state.value.result!!.end as ConsentEnd.Completed).outcome) is CompletionOutcome.Granted)
        answers.pasted(slice.openBankingKey, answerTo(state))

        assertEquals(
            CompletionOutcome.NotAccepted(AnswerNotAccepted.AlreadyAnswered),
            (answers.state.value.result!!.end as ConsentEnd.Completed).outcome,
        )
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A10: Consent Request Abandoned", businessRules = ["BR-002"])
    @DisplayName("A10: Cancel deletes the waiting Consent Request and says so; a Consent Request whose lifetime ends without a result is said to be not granted too")
    fun cancelledAndTimedOut() = runTest {
        startedState()
        val answers = ConsentAnswers(MutableStateFlow<UiServices?>(fixture.services), CoroutineScope(UnconfinedTestDispatcher(testScheduler)), slice.clock)
        answers.readWaiting()
        val waiting = answers.state.value.waiting.single()

        answers.cancel(waiting.service, waiting.requestId)

        assertEquals(ConsentEnd.Abandoned(bank, timedOut = false), answers.state.value.result!!.end)
        assertTrue(slice.consentStore.consentRequests().isEmpty())

        slice.bankConsents.start(slice.openBankingKey, bank, browserAvailable = true)
        answers.started(slice.openBankingKey)
        assertNull(answers.state.value.result)
        slice.clock.advance(Duration.ofMinutes(16))
        answers.readWaiting()
        assertEquals(ConsentEnd.Abandoned(bank, timedOut = true), answers.state.value.result!!.end)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-006", "BR-011"], requirements = ["NFR-008"])
    @DisplayName("BR-006: nothing of an answer, returned or pasted, reaches the log or the kept state")
    fun nothingOfTheAnswerIsKept() = runTest {
        val state = startedState()
        val answers = ConsentAnswers(MutableStateFlow<UiServices?>(fixture.services), CoroutineScope(UnconfinedTestDispatcher(testScheduler)), slice.clock)

        answers.returned(answerTo(state))
        answers.pasted(slice.openBankingKey, "${ExampleData.CONSENT_RETURN_ADDRESS}?state=PLANTED-PASTED-99")

        assertFalse(answers.state.value.toString().contains("PLANTED"))
        assertFalse(slice.consentLog.records.any { it.toString().contains("PLANTED") })
    }
}
