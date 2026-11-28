package slussen.pluginapi.provider

import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * The Bank Consent operations of a Data Provider that needs one (ADR-001 §4.1, UC-007). The core creates the `state`
 * value and stores only its digest; every request goes through the gate of the context, as a request of kind Other
 * with the Operator present (ADR-001 §4.0). A refusal of the gate sends nothing in its scope and is answered
 * `StoppedByGate`.
 */
public interface BankConsentCapability {
    /**
     * The Banks the Data Provider reaches in [country], an ISO 3166 two-letter code (S-B-42, UC-007 BR-010): one
     * request through the gate, sent when the Operator opens the choice of a Bank or chooses another country, never
     * stored.
     */
    public suspend fun banks(country: String, context: RequestContext): BankList

    /**
     * Starts a Consent Request for the Bank [bank], one of the references [banks] gives, with [returnAddress] and the
     * core's [state]. The plug-in asks for the longest Bank Consent the Data Provider declares for that Bank, never a
     * length of Slussen's own (C-007, UC-007 BR-004).
     */
    public suspend fun startConsentRequest(
        bank: String,
        returnAddress: String,
        state: Secret,
        context: RequestContext,
    ): ConsentStart

    /**
     * The `state` value [answer] carries, as the Data Provider returns it, or null when it carries none; the core digests
     * it and finds the Consent Request by that digest only (S-B-42). Sends nothing.
     */
    public fun stateOf(answer: ConsentAnswer): Secret?

    public suspend fun completeConsent(answer: ConsentAnswer, handle: Secret?, context: RequestContext): ConsentCompletion

    public suspend fun revoke(session: ConsentSession, context: RequestContext): RevokeResult

    /**
     * Whether a request of a Fetch the Operator starts can show this Bank that the Operator is present, from what the
     * plug-in keeps in [session] (S-B-46, UC-001 A22). Sends nothing.
     */
    public fun showsPresence(session: ConsentSession): Boolean

    /**
     * The return address the Operator entered with the Data Provider's credentials (ADR-001 §4.5, S-B-51), or null when
     * he entered none; the core then uses the application's own (UC-007 BR-011). Sends nothing.
     */
    public fun registeredReturnAddress(credential: Secret?): String?
}

/** The Banks of one country (S-B-42, UC-007 BR-010). */
public sealed interface BankList {
    public data class Listed(val banks: List<BankChoice>) : BankList

    public data class Refused(val kind: ConsentFailureKind) : BankList

    public data class TransientFailure(val kind: FailureKind) : BankList

    /** The gate refused the request, so nothing was sent. */
    public data class StoppedByGate(val reason: RefusalReason) : BankList
}

/**
 * One Bank the Data Provider reaches: [bankReference] names exactly one Bank of the Data Provider, also where two Banks of
 * one name are in two countries, is the same in the Bank list, in every Bank Account delivered and in every
 * `BankOutcome`, and is the name Slussen shows for the Bank (`BANK.name`); [name] is the name to show in the list.
 */
public data class BankChoice(val bankReference: String, val name: String)

/** The return from the Bank's Strong Customer Authentication, as the app received it or the Operator pasted it. */
public class ConsentAnswer(public val returned: Secret) {
    override fun toString(): String = "ConsentAnswer(${Secret.MASK})"
}

/** Why a Data Provider or Bank did not start or confirm a Bank Consent. */
public enum class ConsentFailureKind {
    RefusedByBank,
    RefusedByDataProvider,
    CredentialsRefused,
    AnswerNotUnderstood,

    /** The Data Provider does not accept the return address (UC-007 BR-011, A3). */
    ReturnAddressRefused,
}

public sealed interface ConsentStart {
    /**
     * The Consent Request is started: [consentAddress] is the way to the Bank's Strong Customer Authentication, which
     * carries a value of the session and is never logged (UC-007 BR-006), so it is masked in [toString]; [handle] is the
     * plug-in's opaque value the core stores with the Consent Request.
     */
    public data class Started(val consentAddress: String, val handle: Secret?) : ConsentStart {
        override fun toString(): String = "Started(consentAddress=${Secret.MASK}, handle=$handle)"
    }

    public data class Refused(val kind: ConsentFailureKind) : ConsentStart

    public data class TransientFailure(val kind: FailureKind) : ConsentStart

    /** The gate refused the request, so nothing was sent. */
    public data class StoppedByGate(val reason: RefusalReason) : ConsentStart
}

public sealed interface ConsentCompletion {
    /**
     * The Bank Consent is granted until [expiresAt], the expiry the Data Provider reports with the completion
     * (UC-007 BR-004). [bankAccounts] are the Bank Accounts it covers, each under a `providerReference` that lasts across
     * Bank Sessions, renewals and reinstalls (S-B-42, ADR-001 §4.1).
     */
    public data class Completed(
        val expiresAt: Instant,
        val bankAccounts: List<DeliveredBankAccount>,
        val session: ConsentSession,
    ) : ConsentCompletion

    /** The Operator cancelled the Strong Customer Authentication or the Bank refused it (UC-007 A1). */
    public data object NotCompleted : ConsentCompletion

    public data class NotConfirmed(val kind: ConsentFailureKind) : ConsentCompletion

    public data class TransientFailure(val kind: FailureKind) : ConsentCompletion

    /** The gate refused the request, so nothing was sent. */
    public data class StoppedByGate(val reason: RefusalReason) : ConsentCompletion
}

public sealed interface RevokeResult {
    /** The Data Provider confirmed the end of the consent at the Bank (UC-007 A7). */
    public data object Ended : RevokeResult

    /** The Data Provider ended its session and ends the consent at the Bank only where the Bank allows it. */
    public data object EndedAtDataProvider : RevokeResult

    public data object NotConfirmed : RevokeResult

    /** The gate refused the request, so nothing was sent. */
    public data class StoppedByGate(val reason: RefusalReason) : RevokeResult
}
