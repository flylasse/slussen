package slussen.provider.enablebanking

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialField
import slussen.pluginapi.credential.CredentialForm
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.time.Clock

/**
 * Enable Banking's credentials (UC-014 BR-002, ADR-001 §4.5, C-008): the application id and the private key file of the
 * household's own registered Enable Banking application. [check] signs a request with the key and asks Enable Banking
 * for the application; what Slussen keeps is both values together.
 */
internal class EnableBankingCredentials(
    private val base: HttpUrl,
    private val http: OkHttpClient,
    private val clock: Clock,
) : CredentialCapability {
    override val form: CredentialForm = CredentialForm(
        listOf(
            CredentialField(
                key = APPLICATION_ID,
                kind = FieldKind.Text,
                description = "Application id · of your own registered Enable Banking application",
            ),
            CredentialField(
                key = PRIVATE_KEY,
                kind = FieldKind.File,
                description = "Private key file · of that application, chosen with the device's file picker",
            ),
        ),
    )

    @OptIn(SensitiveApi::class)
    override suspend fun check(input: CredentialInput, context: RequestContext): CredentialCheck {
        val applicationId = input.values[APPLICATION_ID]?.reveal()?.trim().orEmpty()
        val pem = input.values[PRIVATE_KEY]?.reveal().orEmpty()
        val key = PrivateKeyFile.read(pem)
        if (applicationId.isEmpty() || key == null) return CredentialCheck.Refused
        val admission = context.gate.admit(RequestSummary(RequestKind.Other, null, null))
        // UC-014 BR-002: When the gate refuses the check, as at the Request Limit, nothing is sent and the refusal is returned, never a failure to reach the service.
        if (admission is Admission.Refused) return CredentialCheck.StoppedByGate(admission.reason)
        val token = SignedRequestToken.sign(applicationId, key, clock.instant())
        val request = Request.Builder()
            .url(base.resolve("application")!!)
            .header("Authorization", "Bearer $token")
            .build()
        return when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> CredentialCheck.TransientFailure(answer.kind)
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> CredentialCheck.Accepted(
                    Secret(
                        buildJsonObject {
                            put(APPLICATION_ID, applicationId)
                            put(PRIVATE_KEY, pem)
                        }.toString(),
                    ),
                )
                401, 403 -> CredentialCheck.Refused
                else -> CredentialCheck.TransientFailure(
                    if (answer.status >= 500 || answer.status == 429) {
                        FailureKind.ServiceUnavailable
                    } else {
                        FailureKind.UnreadableAnswer
                    },
                )
            }
        }
    }

    companion object {
        const val APPLICATION_ID = "applicationId"
        const val PRIVATE_KEY = "privateKey"
    }
}
