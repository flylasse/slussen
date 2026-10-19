package slussen.provider.simplefin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.Base64

/**
 * SimpleFIN's credentials (UC-014 BR-002, ADR-001 §4.5): a setup token, which is the Base64 form of a one-time claim
 * address at SimpleFIN Bridge. [check] claims it once and returns the access credential (an address that carries its
 * own access), which is what Slussen keeps; the setup token itself is discarded. A claimed token cannot be claimed
 * again, so SimpleFIN refuses it then.
 */
internal class SimpleFinCredentials(
    private val http: OkHttpClient,
    private val allowPlainHttp: Boolean,
) : CredentialCapability {
    override val form: CredentialForm = CredentialForm(
        listOf(
            CredentialField(
                key = SETUP_TOKEN,
                kind = FieldKind.Secret,
                description = "Setup token · from SimpleFIN Bridge, New connection",
            ),
        ),
    )

    @OptIn(SensitiveApi::class)
    override suspend fun check(input: CredentialInput, context: RequestContext): CredentialCheck {
        val claim = input.values[SETUP_TOKEN]?.let { claimAddressOf(it.reveal()) } ?: return CredentialCheck.Refused
        if (context.gate.admit(RequestSummary(RequestKind.Other, null, null)) != Admission.Allowed) {
            return CredentialCheck.TransientFailure(FailureKind.ServiceUnavailable)
        }
        val request = Request.Builder().url(claim).post(ByteArray(0).toRequestBody()).build()
        return when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> CredentialCheck.TransientFailure(answer.kind)
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> answer.body.trim().takeIf { accessAddressValid(it) }
                    ?.let { CredentialCheck.Accepted(Secret(it)) }
                    ?: CredentialCheck.TransientFailure(FailureKind.UnreadableAnswer)
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

    /** The claim address the token encodes, or null when the token is not one: such a token is refused unsent. */
    private fun claimAddressOf(token: String): HttpUrl? {
        val decoded = try {
            String(Base64.getMimeDecoder().decode(token.trim()), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            return null
        }
        val address = decoded.toHttpUrlOrNull() ?: return null
        return address.takeIf { it.isHttps || allowPlainHttp }
    }

    private fun accessAddressValid(text: String): Boolean =
        text.toHttpUrlOrNull()?.let { it.isHttps || allowPlainHttp } ?: false

    companion object {
        const val SETUP_TOKEN = "setupToken"
    }
}
