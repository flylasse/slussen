package slussen.pluginapi.credential

import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.value.Secret

/**
 * The service credentials of one Data Provider or of the Budget App (ADR-001 §4.5, S-24): the form the Operator fills
 * in and how the service checks it. The core stores the accepted credential encrypted on the device (ADR-002 §2a).
 */
public interface CredentialCapability {
    /** The fields the Operator fills in, each with what it is and where to get it. */
    public val form: CredentialForm

    /** Contacts the service through the gate; `context.credential` is null here. */
    public suspend fun check(input: CredentialInput, context: RequestContext): CredentialCheck
}

public data class CredentialForm(val fields: List<CredentialField>)

public data class CredentialField(val key: String, val kind: FieldKind, val description: String)

/** File is chosen with the device's file picker. */
public enum class FieldKind { Text, Secret, File }

/** What the Operator typed or chose, by the key of each field; every value is a [Secret]. */
public class CredentialInput(public val values: Map<String, Secret>) {
    override fun toString(): String = "CredentialInput(${values.keys})"
}

public sealed interface CredentialCheck {
    /** What the core stores, encrypted; need not be what the Operator typed. */
    public data class Accepted(val credential: Secret) : CredentialCheck

    /** UC-014 A1. */
    public data object Refused : CredentialCheck

    /** UC-014 A2. */
    public data class TransientFailure(val kind: FailureKind) : CredentialCheck

    /** The gate refused the check, so nothing was sent; a reached Request Limit is UC-014 A5 (ADR-001 §4.5). */
    public data class StoppedByGate(val reason: RefusalReason) : CredentialCheck
}
