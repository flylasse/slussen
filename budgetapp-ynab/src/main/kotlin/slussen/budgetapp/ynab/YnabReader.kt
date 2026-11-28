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

/**
 * Reading YNAB (ADR-001 §4.2.3, §4.2.5): the Budgets (UC-013), the changes of a Budget (UC-003) and one transaction
 * (ADR-007 §6 step 8), each through the gate as a Budget App read, with refusals as §4.2.5 Refusals reads them.
 */
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
        when (val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null, unattended = false))) {
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
                else -> failedRead(answer)
            }
        }
    }

    /**
     * One transaction by its id (ADR-001 §4.2.3, §4.2.5 Reading one transaction), read as [readChanges] reads one; its
     * `transfer_transaction_id` and `transfer_account_id` give the link from which the Upload resolves a Transfer
     * Counterpart (ADR-007 §6 step 8). A deleted transaction is Deleted: one answer of one transaction cannot show a
     * merge. `404` is NotFound.
     */
    override suspend fun readTransaction(
        budgetReference: String,
        transactionReference: String,
        context: RequestContext,
    ): ReadResult<TransactionLookup> {
        requireNotNull(context.credential) { "The core contacts YNAB only with stored credentials." }
        when (val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null, unattended = false))) {
            is Admission.Refused -> return ReadResult.StoppedByGate(admission.reason)
            Admission.Allowed -> Unit
        }
        val request = authorized(base.ofBudget(budgetReference, "transactions", transactionReference), context).build()
        return when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> ReadResult.TransientFailure(answer.kind)
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> parsedOrNull {
                    transactionOf(Json.parseToJsonElement(answer.body).jsonObject.getValue("data").jsonObject.getValue("transaction").jsonObject)
                }?.let { ReadResult.Answered(TransactionLookup.Found(it)) } ?: ReadResult.TransientFailure(FailureKind.UnreadableAnswer)
                404 -> ReadResult.Answered(TransactionLookup.NotFound)
                else -> failedRead(answer)
            }
        }
    }

    /**
     * A read YNAB did not answer with success (ADR-001 §4.2.5 Refusals): a refusal of the access token is refused
     * credentials (UC-003 A2); YNAB's refusal for another reason is a failed read with that reason (UC-003 A1, S-B-79);
     * every other status is a failure of its kind.
     */
    private fun failedRead(answer: HttpAnswer.Answered): ReadResult<Nothing> = when (answer.refusal()) {
        // UC-014 BR-005: Only an answer that the access token is not accepted is refused credentials (ADR-001 §4.2.5).
        Refusal.OfTheToken -> ReadResult.CredentialsRefused
        // S-B-79: YNAB's refusal for a reason of its own is told with that reason.
        Refusal.ByTheBudgetApp -> ReadResult.TransientFailure(answer.refusalKind()!!)
        null -> ReadResult.TransientFailure(failureOf(answer.status))
    }

    /**
     * The changed transactions of one Budget and the next cursor (ADR-001 §4.2.3, F-3, F-4), in one request to YNAB's
     * transactions endpoint. The cursor is YNAB's server knowledge: the first read (no cursor) asks for the transactions
     * dated from [from] on; every later read asks for every change since that server knowledge, whatever the date of the
     * transaction, so that a deletion or a merge of an older one is found too (UC-003 BR-016). Reading again from the
     * same cursor returns at least the same changes.
     */
    @OptIn(SensitiveApi::class)
    override suspend fun readChanges(
        budgetReference: String,
        since: ChangeCursor?,
        from: LocalDate,
        context: RequestContext,
    ): ReadResult<ChangeSet> {
        val credential = requireNotNull(context.credential) { "The core contacts YNAB only with stored credentials." }
        when (val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null, unattended = false))) {
            is Admission.Refused -> return ReadResult.StoppedByGate(admission.reason)
            Admission.Allowed -> Unit
        }
        val knowledge = since?.value?.toLongOrNull()
        val url = base.resolve("budgets/$budgetReference/transactions")!!.newBuilder().apply {
            // UC-003 BR-016: The first read covers the read period; every later read takes all changes since the previous read.
            if (knowledge == null) addQueryParameter("since_date", from.toString()) else addQueryParameter("last_knowledge_of_server", knowledge.toString())
        }.build()
        val request = Request.Builder().url(url).bearer(credential).build()
        return when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> ReadResult.TransientFailure(answer.kind)
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> changesOf(answer.body)?.let { ReadResult.Answered(it) }
                    ?: ReadResult.TransientFailure(FailureKind.UnreadableAnswer)
                else -> failedRead(answer)
            }
        }
    }

    /**
     * The changes of YNAB's answer in the normalized read model (ADR-001 §4.2.3), or null when the answer cannot be read;
     * each transaction as [transactionOf] reads it. A transaction YNAB reports as deleted that another transaction of
     * the same answer names as its `matched_transaction_id` was merged into that one.
     */
    private fun changesOf(body: String): ChangeSet? = parsedOrNull {
        val data = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
        val transactions = data.getValue("transactions").jsonArray.map { it.jsonObject }
        val mergedInto = transactions.mapNotNull { transaction ->
            transaction.textOrNull("matched_transaction_id")?.let { matched -> matched to transaction.text("id") }
        }.toMap()
        ChangeSet(
            transactions.map { transactionOf(it, mergedInto) },
            ChangeCursor(requireNotNull(data.getValue("server_knowledge").jsonPrimitive.longOrNull).toString()),
        )
    }

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

}
