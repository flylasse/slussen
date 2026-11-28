package slussen.pluginapi.testfixtures

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi

/** A Consent Request the contract test starts: the Bank's reference and the return address the core would send. */
public class ConsentRequestScenario(public val bank: String, public val returnAddress: String)

/** An answer the contract test completes, with the handle the plug-in gave when the Consent Request was started. */
public class ConsentAnswerScenario(public val answer: ConsentAnswer, public val handle: Secret?)

/**
 * The contract test of [BankConsentCapability] (ADR-001 §4.1, §4.4; S-B-42) that every plug-in with the capability must
 * pass. The subclass constructs the Data Provider against its own local test server (a fresh one per test) and serves
 * invented recorded answers through the hooks. It checks that every request of a Bank Consent operation is admitted by
 * the gate first as kind Other, that a refusal of the gate sends nothing, the outcomes the core relies on (the Banks,
 * `stateOf`, `NotCompleted` apart from an unreadable answer), and that no result, `toString()` or log line reveals a
 * planted value.
 */
public abstract class BankConsentContractTest {
    /** The log the subclass constructs the plug-in with, written as with Detailed Logging on (ADR-006 §2, NFR-008). */
    protected val log: WrittenLog = WrittenLog()

    /** The Data Provider under test, constructed with [log] as the composition root constructs it (ADR-001 §5). */
    protected abstract fun dataProvider(log: SlussenLog): DataProvider

    /** The stored credential the core hands the plug-in with every call. */
    protected abstract fun credential(): Secret

    /** How many requests the test server has received in this test. */
    protected abstract fun requestsReceived(): Int

    /** The server lists at least one Bank for the country returned. */
    protected abstract fun banksListed(): String

    /** The server starts a Consent Request for the Bank of the scenario. */
    protected abstract fun consentStarted(): ConsentRequestScenario

    /** The server completes the Bank Consent for the answer of the scenario. */
    protected abstract fun consentCompleted(): ConsentAnswerScenario

    /** The answer of a Strong Customer Authentication the Operator cancelled or the Bank refused (UC-007 A1). */
    protected abstract fun consentCancelled(): ConsentAnswerScenario

    /** An answer that carries neither a result nor a refusal, which the plug-in cannot read (UC-007 A2). */
    protected abstract fun unreadableAnswer(): ConsentAnswerScenario

    /** The answer the Data Provider returns to the return address for a Consent Request started with [state]. */
    protected abstract fun answerCarrying(state: Secret): ConsentAnswer

    /** An answer that carries the `state` twice, with two values (S-B-42: it answers no one Consent Request). */
    protected abstract fun answerWithTwoStates(): ConsentAnswer

    /** An answer whose `state` is present but empty. */
    protected abstract fun answerWithEmptyState(): ConsentAnswer

    /** An answer that carries a refusal and a result together, which the plug-in cannot read as either. */
    protected abstract fun answerWithErrorAndCode(): ConsentAnswerScenario

    /** The session of a granted Bank Consent; the server ends it when asked. */
    protected abstract fun sessionToEnd(): ConsentSession

    /** Every value planted in this test's answers (session, codes, addresses), which no `toString()` or log line may show. */
    protected abstract fun plantedValues(): List<String>

    private fun capability(): BankConsentCapability =
        requireNotNull(dataProvider(log).bankConsent) { "the Data Provider declares no Bank Consent operations" }

    private fun context(gate: RecordingRequestGate = RecordingRequestGate()) = TestRequestContext(gate, credential = credential())

    private fun <T> call(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { block() }

    private fun assertAdmittedAsOther(gate: RecordingRequestGate) {
        assertTrue(requestsReceived() >= 1, "the service was not asked")
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
        assertTrue(gate.requests.all { it.kind == RequestKind.Other }, "a Bank Consent request is of kind Other: ${gate.requests}")
    }

    @Test
    @DisplayName("ADR-001 §4.1: a Data Provider that needs a Bank Consent declares its Bank Consent operations")
    public fun capabilityIsDeclared() {
        val provider = dataProvider(log)
        assertTrue(provider.profile.needsBankConsent, "needsBankConsent")
        assertNotNull(provider.bankConsent)
    }

    @Test
    @DisplayName("S-B-42: the Banks of a country are listed after the gate admitted every request, each with a reference that is not empty, each reference once")
    public fun banksAreListed() {
        val country = banksListed()
        val gate = RecordingRequestGate()
        val listed = call { capability().banks(country, context(gate)) }
        assertTrue(listed is BankList.Listed, "expected Listed, was $listed")
        val banks = (listed as BankList.Listed).banks
        assertTrue(banks.isNotEmpty())
        assertTrue(banks.all { it.bankReference.isNotBlank() && it.name.isNotBlank() })
        assertEquals(banks.size, banks.map { it.bankReference }.toSet().size, "a reference names exactly one Bank")
        assertAdmittedAsOther(gate)
    }

    @Test
    @DisplayName("ADR-001 §4.0: a refusal of the gate sends nothing and is StoppedByGate, for every operation")
    public fun gateRefusalSendsNothing() {
        val country = banksListed()
        val start = consentStarted()
        val completion = consentCompleted()
        val session = sessionToEnd()
        val refusing = RecordingRequestGate.refusing()
        val capability = capability()
        assertTrue(call { capability.banks(country, context(refusing)) } is BankList.StoppedByGate)
        assertTrue(call { capability.startConsentRequest(start.bank, start.returnAddress, Secret("contract-state"), context(refusing)) } is ConsentStart.StoppedByGate)
        assertTrue(call { capability.completeConsent(completion.answer, completion.handle, context(refusing)) } is ConsentCompletion.StoppedByGate)
        assertTrue(call { capability.revoke(session, context(refusing)) } is RevokeResult.StoppedByGate)
        assertEquals(0, requestsReceived(), "nothing was sent")
    }

    @Test
    @DisplayName("ADR-001 §4.1: a started Consent Request gives the way to the Strong Customer Authentication, after the gate admitted every request")
    public fun consentRequestIsStarted() {
        val start = consentStarted()
        val gate = RecordingRequestGate()
        val started = call { capability().startConsentRequest(start.bank, start.returnAddress, Secret("contract-state"), context(gate)) }
        assertTrue(started is ConsentStart.Started, "expected Started, was $started")
        assertTrue((started as ConsentStart.Started).consentAddress.isNotBlank())
        assertAdmittedAsOther(gate)
    }

    @Test
    @DisplayName("S-B-42: stateOf reads the state the core sent back from the answer, and gives null for an answer without one; it sends nothing")
    public fun stateIsReadFromTheAnswer() {
        val state = Secret("contract-state-5d2b")
        val read = capability().stateOf(answerCarrying(state))
        assertNotNull(read)
        assertEquals(revealed(state), revealed(read!!), "the state read is the one sent")
        assertNull(capability().stateOf(ConsentAnswer(Secret("no-state-in-this-answer"))))
        assertEquals(0, requestsReceived(), "stateOf sends nothing")
    }

    @OptIn(SensitiveApi::class)
    private fun revealed(secret: Secret): String = secret.reveal()

    @Test
    @DisplayName("S-B-42: an answer with two states or an empty one carries no state the core can match; one with a refusal and a result together is never Completed")
    public fun malformedAnswersAreNotRead() {
        val capability = capability()
        assertNull(capability.stateOf(answerWithTwoStates()), "two states")
        assertNull(capability.stateOf(answerWithEmptyState()), "an empty state")
        val both = answerWithErrorAndCode()
        val completed = call { capability.completeConsent(both.answer, both.handle, context()) }
        assertTrue(
            completed == ConsentCompletion.NotCompleted || completed == ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood),
            "an error with a code is not a granted consent: $completed",
        )
    }

    @Test
    @DisplayName("ADR-001 §4.1: a completion gives the expiry, the session and Bank Accounts each with a reference, after the gate admitted every request")
    public fun consentIsCompleted() {
        val completion = consentCompleted()
        val gate = RecordingRequestGate()
        val completed = call { capability().completeConsent(completion.answer, completion.handle, context(gate)) }
        assertTrue(completed is ConsentCompletion.Completed, "expected Completed, was $completed")
        assertTrue((completed as ConsentCompletion.Completed).bankAccounts.all { it.providerReference.isNotBlank() })
        assertAdmittedAsOther(gate)
    }

    @Test
    @DisplayName("S-B-42: a cancelled or refused Strong Customer Authentication is NotCompleted (UC-007 A1); an answer the plug-in cannot read is NotConfirmed(AnswerNotUnderstood) (A2)")
    public fun cancelledIsToldFromUnreadable() {
        val cancelled = consentCancelled()
        val unreadable = unreadableAnswer()
        val capability = capability()
        assertEquals(ConsentCompletion.NotCompleted, call { capability.completeConsent(cancelled.answer, cancelled.handle, context()) })
        assertEquals(
            ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood),
            call { capability.completeConsent(unreadable.answer, unreadable.handle, context()) },
        )
    }

    @Test
    @DisplayName("ADR-001 §4.1: ending a session is Ended or EndedAtDataProvider when the Data Provider confirms, after the gate admitted every request")
    public fun sessionIsEnded() {
        val session = sessionToEnd()
        val gate = RecordingRequestGate()
        val ended = call { capability().revoke(session, context(gate)) }
        assertTrue(ended == RevokeResult.Ended || ended == RevokeResult.EndedAtDataProvider, "was $ended")
        assertAdmittedAsOther(gate)
    }

    @Test
    @DisplayName("S-B-46, S-B-51: showsPresence and registeredReturnAddress send nothing; without credentials no address is registered")
    public fun readsSendNothing() {
        val capability = capability()
        capability.showsPresence(sessionToEnd())
        capability.registeredReturnAddress(credential())
        assertNull(capability.registeredReturnAddress(null))
        assertEquals(0, requestsReceived())
    }

    @Test
    @DisplayName("ADR-006 §2, UC-007 BR-006: no toString() of a result and no log line with Detailed Logging on reveals the state, an address, a code or a session")
    public fun noResultRevealsAConsentValue() {
        val shown = mutableListOf<String>()
        val capability = capability()
        val start = consentStarted()
        shown += call { capability.startConsentRequest(start.bank, start.returnAddress, Secret("contract-state-5d2b"), context()) }.toString()
        val completion = consentCompleted()
        shown += completion.answer.toString()
        val completed = call { capability.completeConsent(completion.answer, completion.handle, context()) }
        shown += completed.toString()
        if (completed is ConsentCompletion.Completed) shown += completed.session.toString()
        shown += call { capability.revoke(sessionToEnd(), context()) }.toString()
        shown += log.written
        (plantedValues() + "contract-state-5d2b").forEach { planted ->
            shown.forEach { text -> assertFalse(planted in text, "a planted value is shown: $text") }
        }
    }
}
