package slussen.core.credential

import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.request.RequestTimeout
import slussen.pluginapi.value.Secret

/**
 * UC-014 A1 at the field (E4 review item 3): which optional fields of a refused entry the part's own check refused,
 * before anything was sent. The part says only that the entry was refused, so the entry is checked once more without the
 * optional values, behind a gate that lets nothing out (ADR-001 §4.0: a refused request is answered `StoppedByGate` with
 * nothing sent): when the rest passes the part's own check, the optional values were what it refused. A refusal that
 * came after a request was sent is the Data Provider's refusal of the credentials, at no field.
 */
object RefusedFields {
    suspend fun of(capability: CredentialCapability, input: CredentialInput, timeout: RequestTimeout, sentAnything: Boolean): Set<String> {
        // UC-014 BR-002: an entry Slussen can see cannot be valid is refused before anything is sent; a refusal by the service is a refusal of the credentials.
        if (sentAnything) return emptySet()
        // The screen leaves an optional field out of the entry when it is empty, so a key given is a value filled in.
        val filled = capability.form.fields.filter { it.optional }.map { it.key }.filter { it in input.values }.toSet()
        if (filled.isEmpty()) return emptySet()
        val without = CredentialInput(input.values.filterKeys { it !in filled })
        val probe = try {
            capability.check(without, NothingLeavesContext(timeout))
        } catch (failure: Exception) {
            return emptySet()
        }
        return if (probe is CredentialCheck.StoppedByGate) filled else emptySet()
    }

    /** A context whose gate refuses every request, so that nothing is sent and no Provider Request is counted. */
    private class NothingLeavesContext(override val timeout: RequestTimeout) : RequestContext {
        override val gate: RequestGate = object : RequestGate {
            override suspend fun admit(request: RequestSummary): Admission = Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded)
        }
        override val credential: Secret? = null
    }
}
