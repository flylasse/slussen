package slussen.budgetapp.ynab

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

/**
 * YNAB's credentials (UC-014 BR-002, ADR-001 §4.5): a personal access token, checked by listing the Budgets. The token
 * is what Slussen keeps.
 */
internal class YnabCredentials(private val base: HttpUrl, private val http: OkHttpClient) : CredentialCapability {
    override val form: CredentialForm = CredentialForm(
        listOf(
            CredentialField(
                key = TOKEN,
                kind = FieldKind.Secret,
                description = "Personal access token · from YNAB developer settings",
            ),
        ),
    )

    @OptIn(SensitiveApi::class)
    override suspend fun check(input: CredentialInput, context: RequestContext): CredentialCheck {
        val token = input.values[TOKEN]
        if (token == null || token.reveal().isBlank()) return CredentialCheck.Refused
        if (context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null)) != Admission.Allowed) {
            return CredentialCheck.TransientFailure(FailureKind.ServiceUnavailable)
        }
        val request = Request.Builder().url(base.resolve("budgets")!!).bearer(token).build()
        return when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> CredentialCheck.TransientFailure(answer.kind)
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> CredentialCheck.Accepted(token)
                401, 403 -> CredentialCheck.Refused
                else -> CredentialCheck.TransientFailure(failureOf(answer.status))
            }
        }
    }

    companion object {
        const val TOKEN = "token"
    }
}

@SensitiveApi
internal fun Request.Builder.bearer(token: Secret): Request.Builder = header("Authorization", "Bearer " + token.reveal())

/** A status that is neither success nor a refusal of the credentials. */
internal fun failureOf(status: Int): FailureKind =
    if (status >= 500 || status == 429) FailureKind.ServiceUnavailable else FailureKind.UnreadableAnswer
