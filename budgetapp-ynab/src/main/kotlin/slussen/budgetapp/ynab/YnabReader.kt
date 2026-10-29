package slussen.budgetapp.ynab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransferLink
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

/** Reading YNAB (ADR-001 §4.2.3): the Budgets (UC-013) and the changes of a Budget (UC-003). */
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
        when (val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null))) {
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
                401, 403 -> ReadResult.CredentialsRefused
                else -> ReadResult.TransientFailure(failureOf(answer.status))
            }
        }
    }

    /**
     * The changes of YNAB's answer in the normalized read model (ADR-001 §4.2.3), or null when the answer cannot be read.
     * What depends on YNAB's behaviour is decided here (C-009):
     * - amounts are milliunits, thousandths of the currency unit, the precision of [Amount];
     * - `entry`: a transaction with an `import_id` was written by an importer (Imported); one without it that is a side of
     *   a linked transfer (`transfer_transaction_id`) is a side YNAB may have created itself (TransferSide); every other
     *   one was entered by hand (Typed);
     * - `transferLink`: the other side's `transfer_transaction_id` and `transfer_account_id`, on both sides;
     * - `state`: a transaction YNAB reports as `deleted` is Deleted, unless another transaction of the same answer names it
     *   as its `matched_transaction_id`: then YNAB merged it into that one (Merged);
     * - `cleared`: YNAB's `cleared` and `reconciled` are Cleared, `uncleared` is not; `approved` as YNAB reports it.
     */
    private fun changesOf(body: String): ChangeSet? = try {
        val data = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
        val transactions = data.getValue("transactions").jsonArray.map { it.jsonObject }
        val mergedInto = transactions.mapNotNull { transaction ->
            transaction.textOrNull("matched_transaction_id")?.let { matched -> matched to transaction.text("id") }
        }.toMap()
        ChangeSet(
            transactions.map { transaction ->
                val reference = transaction.text("id")
                val importIdentifier = transaction.textOrNull("import_id")
                val otherSide = transaction.textOrNull("transfer_transaction_id")
                val deleted = transaction["deleted"]?.jsonPrimitive?.booleanOrNull == true
                BudgetAppTransactionRead(
                    reference = reference,
                    budgetAccountReference = transaction.text("account_id"),
                    date = LocalDate.parse(transaction.text("date")),
                    amount = Amount(requireNotNull(transaction.getValue("amount").jsonPrimitive.longOrNull)),
                    payee = transaction.textOrNull("payee_name"),
                    memo = transaction.textOrNull("memo"),
                    importIdentifier = importIdentifier,
                    entry = when {
                        importIdentifier != null -> EntryKind.Imported
                        otherSide != null -> EntryKind.TransferSide
                        else -> EntryKind.Typed
                    },
                    transferLink = otherSide?.let { TransferLink(it, transaction.text("transfer_account_id")) },
                    state = when {
                        !deleted -> ReadState.Present
                        reference in mergedInto -> ReadState.Merged(mergedInto.getValue(reference))
                        else -> ReadState.Deleted
                    },
                    cleared = transaction.text("cleared") != "uncleared",
                    approved = requireNotNull(transaction.getValue("approved").jsonPrimitive.booleanOrNull),
                )
            },
            ChangeCursor(requireNotNull(data.getValue("server_knowledge").jsonPrimitive.longOrNull).toString()),
        )
    } catch (e: IllegalArgumentException) {
        // Malformed JSON, a missing field, an unreadable amount; kotlinx' SerializationException is one too.
        null
    } catch (e: NoSuchElementException) {
        null
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }

    private fun JsonObject.textOrNull(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

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
