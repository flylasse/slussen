package slussen.pluginapi.provider

import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * The Bank Consent operations of a Data Provider that needs one (ADR-001 §4.1, UC-007). The core creates the `state`
 * value and stores only its digest; every request goes through the gate of the context.
 */
public interface BankConsentCapability {
    public suspend fun startConsentRequest(
        bank: String,
        returnAddress: String,
        state: Secret,
        context: RequestContext,
    ): ConsentStart

    public suspend fun completeConsent(answer: ConsentAnswer, handle: Secret?, context: RequestContext): ConsentCompletion

    public suspend fun revoke(session: ConsentSession, context: RequestContext): RevokeResult
}

/** The return from the Bank's Strong Customer Authentication, as the app received it. */
public class ConsentAnswer(public val returned: Secret) {
    override fun toString(): String = "ConsentAnswer(${Secret.MASK})"
}

/** Why a Data Provider or Bank did not start or confirm a Bank Consent. */
public enum class ConsentFailureKind { RefusedByBank, RefusedByDataProvider, CredentialsRefused, AnswerNotUnderstood }

public sealed interface ConsentStart {
    public data class Started(val consentAddress: String, val handle: Secret?) : ConsentStart

    public data class Refused(val kind: ConsentFailureKind) : ConsentStart

    public data class TransientFailure(val kind: FailureKind) : ConsentStart
}

public sealed interface ConsentCompletion {
    public data class Completed(
        val expiresAt: Instant,
        val bankAccounts: List<DeliveredBankAccount>,
        val session: ConsentSession,
    ) : ConsentCompletion

    public data class NotConfirmed(val kind: ConsentFailureKind) : ConsentCompletion

    public data class TransientFailure(val kind: FailureKind) : ConsentCompletion
}

public sealed interface RevokeResult {
    public data object Ended : RevokeResult

    public data object NotConfirmed : RevokeResult
}
