package slussen.budgetapp.ynab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import slussen.pluginapi.budgetapp.BudgetAccountRead
import slussen.pluginapi.budgetapp.BudgetAppReader
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.TransactionLookup
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.SensitiveApi
import java.time.LocalDate

/** Reading YNAB (ADR-001 §4.2.3). This slice reads the Budgets; the reading of changes comes with UC-003. */
internal class YnabReader(private val base: HttpUrl, private val http: OkHttpClient) : BudgetAppReader {
    /**
     * Every Budget YNAB lists, with its Budget Accounts, their names and balances (YNAB reports amounts in milliunits,
     * thousandths of the currency unit, the precision of [Amount]) and the Budget's currency. YNAB keeps a deleted
     * account in the list with `deleted` true; it no longer exists in the Budget and is left out. A closed account still
     * exists in the Budget and is listed.
     */
    @OptIn(SensitiveApi::class)
    override suspend fun readBudgets(context: RequestContext): ReadResult<List<BudgetRead>> {
        val credential = requireNotNull(context.credential) { "The core contacts YNAB only with stored credentials." }
        when (val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null))) {
            is Admission.Refused -> return ReadResult.StoppedByGate(admission.reason)
            Admission.Allowed -> Unit
        }
        val url = base.resolve("budgets")!!.newBuilder().addQueryParameter("include_accounts", "true").build()
        val request = Request.Builder().url(url).bearer(credential).build()
        return when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> ReadResult.TransientFailure(answer.kind)
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> budgetsOf(answer.body)?.let { ReadResult.Answered(it) }
                    ?: ReadResult.TransientFailure(FailureKind.UnreadableAnswer)
                401, 403 -> ReadResult.CredentialsRefused
                else -> ReadResult.TransientFailure(failureOf(answer.status))
            }
        }
    }

    override suspend fun readTransaction(
        budgetReference: String,
        transactionReference: String,
        context: RequestContext,
    ): ReadResult<TransactionLookup> = TODO("Built by the slice that builds UC-006 (ADR-001 §4.2.3, ADR-007 §6).")

    override suspend fun readChanges(
        budgetReference: String,
        since: ChangeCursor?,
        from: LocalDate,
        context: RequestContext,
    ): ReadResult<ChangeSet> = TODO("Built by the slice that builds UC-003 (ADR-001 §4.2.3).")

    /** The Budgets of YNAB's answer, or null when the answer cannot be read. */
    private fun budgetsOf(body: String): List<BudgetRead>? = try {
        val data = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
        data.getValue("budgets").jsonArray.map { element ->
            val budget = element.jsonObject
            BudgetRead(
                reference = budget.text("id"),
                name = budget.text("name"),
                currency = CurrencyCode(budget.getValue("currency_format").jsonObject.text("iso_code")),
                budgetAccounts = budget.getValue("accounts").jsonArray
                    .map { it.jsonObject }
                    .filterNot { it["deleted"]?.jsonPrimitive?.booleanOrNull == true }
                    .map { account ->
                        BudgetAccountRead(
                            reference = account.text("id"),
                            name = account.text("name"),
                            balance = Amount(requireNotNull(account.getValue("balance").jsonPrimitive.longOrNull)),
                        )
                    },
            )
        }
    } catch (e: IllegalArgumentException) {
        // Malformed JSON, a missing field or an unknown currency code; kotlinx' SerializationException is one too.
        null
    } catch (e: NoSuchElementException) {
        null
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
