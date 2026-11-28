package slussen.testing

import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.SensitiveApi
import slussen.pluginapi.value.Secret

/** One Consent Request a [ScriptedBankConsent] was asked to start, as the core handed it over. */
class StartAsked(val bank: String, val returnAddress: String, val state: Secret)

/**
 * A scripted [BankConsentCapability] (ADR-001 §4.1) for tests of the core. Like a real part it asks the gate before each
 * of its requests, as kind Other with the Operator present (ADR-001 §4.0), one request per call, and answers
 * `StoppedByGate` with nothing sent when the gate refuses. A test scripts each answer; one it did not script fails when
 * it is asked. [stateOf] reads `state` from the query of the answer, as a Data Provider adds it to the return address.
 * Every call, state and session is kept for assertions.
 */
class ScriptedBankConsent : BankConsentCapability {
    var banksAnswer: (String) -> BankList = { error("No list of Banks scripted (UC-007 BR-010).") }
    var startAnswer: (StartAsked) -> ConsentStart = { error("No start of a Consent Request scripted (UC-007 step 3).") }

    /** What the Data Provider answers to a completion, given the answer and the handle the core stored. */
    var completeAnswer: (ConsentAnswer, Secret?) -> ConsentCompletion = { _, _ -> error("No completion scripted (UC-007 step 6).") }
    var revokeAnswer: (ConsentSession) -> RevokeResult = { RevokeResult.Ended }
    var presence: (ConsentSession) -> Boolean = { true }

    /** The return address registered with the credentials; none by default, so the core uses the application's own. */
    var registered: (Secret?) -> String? = { null }

    /** Runs after the gate admitted a completion and before [completeAnswer]: a test can hold it here. */
    var beforeCompletion: suspend () -> Unit = {}

    /** Every request the gate let out, in order. */
    val sent = mutableListOf<RequestSummary>()

    /** Every request the plug-in asked the gate for, let out or not. */
    val asked = mutableListOf<RequestSummary>()
    val countries = mutableListOf<String>()
    val starts = mutableListOf<StartAsked>()
    val handlesCompleted = mutableListOf<Secret?>()
    val revoked = mutableListOf<ConsentSession>()
    val contexts = mutableListOf<RequestContext>()

    private suspend fun admitted(context: RequestContext, bank: String?): Admission {
        contexts += context
        val request = RequestSummary(RequestKind.Other, bank, null)
        asked += request
        val admission = context.gate.admit(request)
        if (admission == Admission.Allowed) sent += request
        return admission
    }

    override suspend fun banks(country: String, context: RequestContext): BankList {
        val admission = admitted(context, null)
        if (admission is Admission.Refused) return BankList.StoppedByGate(admission.reason)
        countries += country
        return banksAnswer(country)
    }

    override suspend fun startConsentRequest(bank: String, returnAddress: String, state: Secret, context: RequestContext): ConsentStart {
        val admission = admitted(context, bank)
        if (admission is Admission.Refused) return ConsentStart.StoppedByGate(admission.reason)
        val asked = StartAsked(bank, returnAddress, state)
        starts += asked
        return startAnswer(asked)
    }

    @OptIn(SensitiveApi::class)
    override fun stateOf(answer: ConsentAnswer): Secret? = stateIn(answer.returned.reveal())?.let(::Secret)

    override suspend fun completeConsent(answer: ConsentAnswer, handle: Secret?, context: RequestContext): ConsentCompletion {
        val admission = admitted(context, null)
        if (admission is Admission.Refused) return ConsentCompletion.StoppedByGate(admission.reason)
        handlesCompleted += handle
        beforeCompletion()
        return completeAnswer(answer, handle)
    }

    override suspend fun revoke(session: ConsentSession, context: RequestContext): RevokeResult {
        val admission = admitted(context, null)
        if (admission is Admission.Refused) return RevokeResult.StoppedByGate(admission.reason)
        revoked += session
        return revokeAnswer(session)
    }

    override fun showsPresence(session: ConsentSession): Boolean = presence(session)

    override fun registeredReturnAddress(credential: Secret?): String? = registered(credential)

    /** Each state the core handed over, revealed for a test (a test may reveal; production code of the core may not). */
    @OptIn(SensitiveApi::class)
    fun stateTexts(): List<String> = starts.map { it.state.reveal() }

    companion object {
        /** The value of `state` in the query of [returned], or null when it has none, an empty one, or more than one. */
        fun stateIn(returned: String): String? =
            returned.substringAfter('?', "").split('&').filter { it.startsWith("state=") }.singleOrNull()?.removePrefix("state=")?.takeIf { it.isNotEmpty() }

        /** An answer as a Data Provider returns it to [returnAddress]: the address with [query] added. */
        fun answer(returnAddress: String, query: String): ConsentAnswer = ConsentAnswer(Secret("$returnAddress?$query"))
    }
}
