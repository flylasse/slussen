package slussen.budgetapp.ynab

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import slussen.pluginapi.budgetapp.BudgetAppWriter
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import kotlin.coroutines.cancellation.CancellationException

/** What the YNAB writer logs (ADR-006 §1): closed events with counts, statuses and closed kinds only. */
internal enum class YnabWriteEvent : LogEvent {
    /** A list was answered; facts: its status and the number of items it carried. */
    ListAnswered,

    /** A list was refused as a whole and each of its items is sent alone (Q-08); fact: the number of items. */
    ListSentAgainOneByOne,

    /** A single send was answered; fact: its status. */
    SingleAnswered,

    /** A request got no answer, so every item it carried is Unknown; fact: how it ended. */
    AnswerLost,

    /** Items were settled without being sent; facts: how many were refused before sending and how many are Unknown. */
    SettledUnsent,

    /** A clearing was answered; fact: its status. */
    ClearingAnswered,
}

/** How a request that got no answer ended; only its name is ever logged. */
internal enum class Lost { Timeout, ConnectionLost, CancelledAfterItLeft, CancelledBeforeItLeft, StoppedByGate }

/**
 * The YNAB writer (ADR-001 §4.2.4, §4.2.5): it sends exactly the items it is handed, each at most once in a list and at
 * most once alone, every request admitted by the gate first, and reports one outcome per item. It decides nothing
 * about what to send: an item it cannot send as it is handed (an amount it cannot send exactly, an Import Identifier
 * YNAB cannot hold, a transfer it cannot link) is `Rejected` before anything is sent; an item whose request got no
 * answer is `Unknown` and is never sent again, by this writer or by its HTTP library (no redirect followed, no retry).
 *
 * A call cancelled while it runs returns its outcomes all the same, every item not answered yet `Unknown`, so that the
 * caller can record them before its own cancellation takes effect at its next suspension (ADR-001 §4.0).
 */
internal class YnabWriter(
    private val base: HttpUrl,
    private val http: OkHttpClient,
    private val log: SlussenLog,
    private val importIdentifierMaxLength: Int,
    private val payeeMaxLength: Int,
    private val memoMaxLength: Int,
) : BudgetAppWriter {

    override suspend fun create(budgetReference: String, items: List<CreateItem>, context: RequestContext): CreateResult {
        requireNotNull(context.credential) { "The core contacts YNAB only with stored credentials." }
        val outcomes = arrayOfNulls<ItemOutcome>(items.size)
        items.forEachIndexed { index, item -> refusalBeforeSending(item)?.let { outcomes[index] = ItemOutcome.Rejected(it) } }

        val payees = mutableMapOf<Int, String>()
        val linked = items.indices.filter { outcomes[it] == null && items[it].transferBudgetAccountReference != null }
        if (linked.isNotEmpty()) {
            when (val read = readTransferPayees(budgetReference, context)) {
                is PayeesRead.Read -> linked.forEach { index ->
                    // UC-006 BR-009: A Transfer is written as one linked transfer through the other Budget Account's transfer payee.
                    when (val payee = read.payeeOf(items[index].transferBudgetAccountReference!!)) {
                        is TransferPayee.Found -> payees[index] = payee.id
                        is TransferPayee.Unusable -> outcomes[index] = ItemOutcome.Rejected(payee.reason)
                    }
                }
                PayeesRead.TokenRefused -> return CreateResult.CredentialsRefused
                is PayeesRead.Stopped -> return CreateResult.StoppedByGate(read.reason)
                PayeesRead.Cancelled -> return answered(outcomes)
                PayeesRead.Unusable -> linked.forEach { outcomes[it] = ItemOutcome.Unknown }
            }
        }

        val toSend = items.indices.filter { outcomes[it] == null }
        val unsent = items.size - toSend.size
        if (unsent > 0) {
            log.event(
                YnabWriteEvent.SettledUnsent,
                LogFact.Count(outcomes.count { it is ItemOutcome.Rejected }.toLong()),
                LogFact.Count(outcomes.count { it == ItemOutcome.Unknown }.toLong()),
            )
        }
        if (toSend.isEmpty()) return answered(outcomes)

        val body = JsonObject(mapOf("transactions" to JsonArray(toSend.map { transactionOf(items[it], payees[it]) })))
        when (val attempt = attempt(context, createRequest(budgetReference, body, context))) {
            is Attempt.Stopped -> return CreateResult.StoppedByGate(attempt.reason)
            is Attempt.NoAnswer -> lost(attempt.how)
            is Attempt.Answered -> {
                log.event(YnabWriteEvent.ListAnswered, LogFact.Count(attempt.answer.status.toLong()), LogFact.Count(toSend.size.toLong()))
                val refusal = attempt.answer.refusal()
                when {
                    attempt.answer.status == 201 -> listOutcomes(attempt.answer.body, items, toSend)?.forEach { (index, outcome) -> outcomes[index] = outcome }
                    // ADR-001 §4.2.4: Refused credentials are answered only when nothing of the call was written.
                    refusal == Refusal.OfTheToken -> return CreateResult.CredentialsRefused
                    attempt.answer.status in LIST_REFUSALS || refusal == Refusal.ByTheBudgetApp -> {
                        log.event(YnabWriteEvent.ListSentAgainOneByOne, LogFact.Count(toSend.size.toLong()))
                        sendOneByOne(budgetReference, items, toSend, payees, outcomes, context)
                    }
                    else -> Unit
                }
            }
        }
        return answered(outcomes)
    }

    /**
     * Q-08 (ADR-001 §4.2.5 A refused list, sent again one by one): each item of the refused list alone, in order, at
     * most once, each through the gate. Only an item YNAB refuses by itself is `Rejected`; the first answer that is not
     * one of the defined answers of a single send ends the sending, and that item and every item not sent yet are
     * `Unknown`.
     */
    private suspend fun sendOneByOne(
        budgetReference: String,
        items: List<CreateItem>,
        toSend: List<Int>,
        payees: Map<Int, String>,
        outcomes: Array<ItemOutcome?>,
        context: RequestContext,
    ) {
        for ((position, index) in toSend.withIndex()) {
            val item = items[index]
            val body = JsonObject(mapOf("transaction" to transactionOf(item, payees[index])))
            val outcome: ItemOutcome? = when (val attempt = attempt(context, createRequest(budgetReference, body, context))) {
                is Attempt.Stopped -> null.also { lost(Lost.StoppedByGate) }
                is Attempt.NoAnswer -> null.also { lost(attempt.how) }
                is Attempt.Answered -> {
                    log.event(YnabWriteEvent.SingleAnswered, LogFact.Count(attempt.answer.status.toLong()))
                    singleOutcome(attempt.answer, item)
                }
            }
            // UC-006 BR-007: An item whose outcome is unknown is never sent again by the writer, nor is any item after it in this call.
            if (outcome == null) {
                toSend.drop(position).forEach { outcomes[it] = ItemOutcome.Unknown }
                return
            }
            outcomes[index] = outcome
        }
    }

    /** The one outcome of a single send's answer (ADR-001 §4.2.5), or null when the answer ends the sending. */
    private fun singleOutcome(answer: HttpAnswer.Answered, item: CreateItem): ItemOutcome? {
        val refusal = answer.refusal()
        return when {
            answer.status == 201 -> parsedOrNull {
                val saved = Json.parseToJsonElement(answer.body).jsonObject.getValue("data").jsonObject.getValue("transaction").jsonObject
                val reference = saved.text("id")
                // W4-0 review item 8: a single transaction's answer counts as created only when it carries the item's own Import Identifier.
                if (saved.textOrNull("import_id") == item.importIdentifier && saved.textOrNull("account_id") == item.budgetAccountReference) {
                    ItemOutcome.Created(reference)
                } else {
                    ItemOutcome.Unknown
                }
            }
            answer.status == 409 -> ItemOutcome.AlreadyPresent(null)
            answer.status == 400 || refusal == Refusal.ByTheBudgetApp -> ItemOutcome.Rejected(answer.errorDetail() ?: UNREADABLE_REASON)
            // ADR-001 §4.2.4: an item refused because its Budget Account may not be in the Budget named is Unknown, never Rejected.
            answer.status == 404 -> ItemOutcome.Unknown
            else -> null
        }
    }

    /**
     * The outcomes of YNAB's `201` to a list, item by item by Import Identifier, or null when the answer cannot be read
     * (then every item stays `Unknown`). An item is `Created` only when exactly one saved transaction of its own Budget
     * Account carries its Import Identifier and the answer does not also name it a duplicate; `AlreadyPresent(null)`
     * only when it is named a duplicate and none of its Budget Account was saved with it; every other item, and every
     * item whose Import Identifier another item of the list shares, cannot be told apart and is `Unknown`.
     */
    private fun listOutcomes(body: String, items: List<CreateItem>, sent: List<Int>): Map<Int, ItemOutcome>? = parsedOrNull {
        val data = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
        require("transactions" in data || "duplicate_import_ids" in data) { "neither saved transactions nor duplicates" }
        val saved = (data["transactions"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject }
            .map { Triple(it.text("id"), it.textOrNull("import_id"), it.text("account_id")) }
        val duplicates = (data["duplicate_import_ids"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content }.toSet()
        val shared = sent.groupBy { items[it].importIdentifier }.filterValues { it.size > 1 }.keys
        sent.associateWith { index ->
            val item = items[index]
            val matches = saved.filter { (_, importIdentifier, account) -> importIdentifier == item.importIdentifier && account == item.budgetAccountReference }
            val duplicate = item.importIdentifier in duplicates
            when {
                item.importIdentifier in shared -> ItemOutcome.Unknown
                matches.size == 1 && !duplicate -> ItemOutcome.Created(matches.single().first)
                matches.isEmpty() && duplicate -> ItemOutcome.AlreadyPresent(null)
                else -> ItemOutcome.Unknown
            }
        }
    }

    /**
     * Why [item] cannot be sent exactly as it is handed, or null. The reason is shown to the Operator (UC-006 A9) and
     * names no value of the item.
     */
    private fun refusalBeforeSending(item: CreateItem): String? = when {
        // C-009: YNAB takes milliunits, the thousandths of Amount; RFC 8259 §6: only a whole number within ±(2^53 − 1) is held exactly by every reader of JSON.
        item.amount.thousandths !in -EXACT_LIMIT..EXACT_LIMIT -> "The amount is too large to be sent to YNAB exactly, so it was not sent."
        item.importIdentifier.isBlank() -> "The transaction has no Import Identifier, so it was not sent."
        item.importIdentifier.length > importIdentifierMaxLength -> "The Import Identifier is longer than YNAB accepts, so it was not sent."
        item.transferBudgetAccountReference == item.budgetAccountReference -> "A transfer to its own Budget Account cannot be linked in YNAB, so it was not sent."
        else -> null
    }

    /**
     * One transaction of a create request, its keys in this order (ADR-001 §4.2.5 Creating a list): `account_id`,
     * `date`, `amount` in milliunits, `payee_name` (or null) for an ordinary item or `payee_id` the other Budget
     * Account's transfer payee for a linked transfer, `memo` (or null), `cleared`, `approved` false, `import_id`. No
     * category, flag or split.
     */
    private fun transactionOf(item: CreateItem, transferPayee: String?): JsonObject = buildJsonObject {
        put("account_id", item.budgetAccountReference)
        put("date", item.date.toString())
        put("amount", item.amount.thousandths)
        if (transferPayee != null) put("payee_id", transferPayee) else put("payee_name", item.payee?.let { shortened(it, payeeMaxLength) })
        put("memo", item.memo?.let { shortened(it, memoMaxLength) })
        put("cleared", if (item.cleared) "cleared" else "uncleared")
        // UC-006 BR-008: Every created transaction waits for the Operator's approval; nothing is ever sent approved.
        put("approved", false)
        // UC-006 BR-007: The Import Identifier is sent exactly as handed, so that YNAB refuses it once it holds it.
        put("import_id", item.importIdentifier)
    }

    override suspend fun markCleared(budgetReference: String, counterpartReference: String, context: RequestContext): ClearResult {
        requireNotNull(context.credential) { "The core contacts YNAB only with stored credentials." }
        // UC-006 BR-011: Marking a Transfer Counterpart Cleared changes nothing of it but its cleared marker.
        val body = JsonObject(mapOf("transactions" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(counterpartReference), "cleared" to JsonPrimitive("cleared")))))))
        val request = authorized(base.ofBudget(budgetReference, "transactions"), context)
            .patch(body.toString().toRequestBody(JSON)).build()
        return when (val attempt = attempt(context, request)) {
            is Attempt.Stopped -> ClearResult.NotCleared.also { lost(Lost.StoppedByGate) }
            is Attempt.NoAnswer -> {
                lost(attempt.how)
                if (attempt.how == Lost.CancelledBeforeItLeft) ClearResult.NotCleared else ClearResult.Unknown
            }
            is Attempt.Answered -> {
                log.event(YnabWriteEvent.ClearingAnswered, LogFact.Count(attempt.answer.status.toLong()))
                when {
                    attempt.answer.status == 200 -> parsedOrNull { clearedIn(attempt.answer.body, counterpartReference) } ?: ClearResult.Unknown
                    attempt.answer.status in CLEAR_REFUSALS || attempt.answer.refusal() != null -> ClearResult.NotCleared
                    else -> ClearResult.Unknown
                }
            }
        }
    }

    /** Cleared when YNAB's `200` names the counterpart `cleared` or `reconciled`, NotCleared when it does not. */
    private fun clearedIn(body: String, counterpartReference: String): ClearResult {
        val data = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject
        val saved = (data["transactions"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject }
        val counterpart = saved.singleOrNull { it.text("id") == counterpartReference }
        return if (counterpart != null && counterpart.text("cleared") in setOf("cleared", "reconciled")) ClearResult.Cleared else ClearResult.NotCleared
    }

    /**
     * The Budget Accounts of the Budget with their transfer payees (ADR-001 §4.2.5 The transfer payee): one read, through
     * the gate as a read.
     */
    private suspend fun readTransferPayees(budgetReference: String, context: RequestContext): PayeesRead {
        val request = authorized(base.ofBudget(budgetReference, "accounts"), context).build()
        return when (val attempt = attempt(context, request, RequestKind.BudgetAppRead)) {
            is Attempt.Stopped -> PayeesRead.Stopped(attempt.reason)
            is Attempt.NoAnswer -> {
                lost(attempt.how)
                if (attempt.how == Lost.CancelledBeforeItLeft || attempt.how == Lost.CancelledAfterItLeft) PayeesRead.Cancelled else PayeesRead.Unusable
            }
            is Attempt.Answered -> when {
                attempt.answer.status == 200 -> parsedOrNull { accountsOf(attempt.answer.body) } ?: PayeesRead.Unusable
                attempt.answer.refusal() == Refusal.OfTheToken -> PayeesRead.TokenRefused
                else -> PayeesRead.Unusable
            }
        }
    }

    private fun accountsOf(body: String): PayeesRead.Read {
        val accounts = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject.getValue("accounts").jsonArray.map { it.jsonObject }
        return PayeesRead.Read(
            accounts.associate { account ->
                account.text("id") to BudgetAccountState(
                    closed = requireNotNull(account.getValue("closed").jsonPrimitive.booleanOrNull),
                    deleted = requireNotNull(account.getValue("deleted").jsonPrimitive.booleanOrNull),
                    transferPayee = account.textOrNull("transfer_payee_id")?.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    private class BudgetAccountState(val closed: Boolean, val deleted: Boolean, val transferPayee: String?)

    private sealed interface TransferPayee {
        data class Found(val id: String) : TransferPayee {
            override fun toString(): String = "Found"
        }

        data class Unusable(val reason: String) : TransferPayee
    }

    private sealed interface PayeesRead {
        class Read(private val accounts: Map<String, BudgetAccountState>) : PayeesRead {
            /** ADR-001 §4.2.2, §4.2.5: a missing, deleted or closed other Budget Account, or one without a transfer payee, cannot be linked. */
            fun payeeOf(otherBudgetAccount: String): TransferPayee {
                val account = accounts[otherBudgetAccount]
                return when {
                    account == null -> TransferPayee.Unusable("The other Budget Account of this transfer is not in the Budget in YNAB, so the transfer was not sent.")
                    account.deleted -> TransferPayee.Unusable("The other Budget Account of this transfer is deleted in YNAB, so the transfer was not sent.")
                    account.closed -> TransferPayee.Unusable("The other Budget Account of this transfer is closed in YNAB, so the transfer was not sent.")
                    account.transferPayee == null -> TransferPayee.Unusable("YNAB offers no transfer to the other Budget Account of this transfer, so it was not sent.")
                    else -> TransferPayee.Found(account.transferPayee)
                }
            }

            override fun toString(): String = "Read"
        }

        data object TokenRefused : PayeesRead

        data class Stopped(val reason: RefusalReason) : PayeesRead

        data object Cancelled : PayeesRead

        data object Unusable : PayeesRead
    }

    /** What one request came to: refused by the gate (nothing left), no answer, or an answer. */
    private sealed interface Attempt {
        data class Stopped(val reason: RefusalReason) : Attempt

        data class NoAnswer(val how: Lost) : Attempt

        data class Answered(val answer: HttpAnswer.Answered) : Attempt
    }

    /**
     * Asks the gate, then sends [request] once (ADR-001 §4.0). A cancellation while the gate decides, or before the
     * request is handed to the HTTP library, is certain to have sent nothing ([Lost.CancelledBeforeItLeft]); one once
     * it is handed over may have sent it ([Lost.CancelledAfterItLeft]).
     */
    private suspend fun attempt(context: RequestContext, request: Request, kind: RequestKind = RequestKind.BudgetAppWrite): Attempt {
        val admission = try {
            context.gate.admit(RequestSummary(kind, null, null, unattended = false))
        } catch (e: CancellationException) {
            return Attempt.NoAnswer(Lost.CancelledBeforeItLeft)
        }
        if (admission is Admission.Refused) return Attempt.Stopped(admission.reason)
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            return Attempt.NoAnswer(Lost.CancelledBeforeItLeft)
        }
        val answer = try {
            http.send(request, context.timeout)
        } catch (e: CancellationException) {
            return Attempt.NoAnswer(Lost.CancelledAfterItLeft)
        }
        return when (answer) {
            is HttpAnswer.Answered -> Attempt.Answered(answer)
            is HttpAnswer.Failed -> Attempt.NoAnswer(if (answer.kind == FailureKind.Timeout) Lost.Timeout else Lost.ConnectionLost)
        }
    }

    private fun lost(how: Lost) = log.event(YnabWriteEvent.AnswerLost, LogFact.Value(how))

    /** A `POST` of [body] to the Budget's transactions (ADR-001 §4.2.5 Paths: the `/budgets` path the reads use). */
    private fun createRequest(budgetReference: String, body: JsonObject, context: RequestContext): Request =
        authorized(base.ofBudget(budgetReference, "transactions"), context).post(body.toString().toRequestBody(JSON)).build()

    /** Every item not answered yet is `Unknown`. */
    private fun answered(outcomes: Array<ItemOutcome?>): CreateResult.Answered =
        CreateResult.Answered(outcomes.map { it ?: ItemOutcome.Unknown })

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** RFC 8259 §6: 2^53 − 1, the largest whole number every reader of a JSON number holds exactly. */
        const val EXACT_LIMIT: Long = 9_007_199_254_740_991

        /** ADR-001 §4.2.5: the answers to a list after which each item is sent alone (with a 403 not of the token). */
        val LIST_REFUSALS = setOf(400, 404, 409)

        /** ADR-001 §4.2.5 Marking Cleared: the answers that are NotCleared (with every 401 and 403). */
        val CLEAR_REFUSALS = setOf(400, 404)

        const val UNREADABLE_REASON = "YNAB refused this transaction and gave no reason that could be read."
    }
}

/** [text] as YNAB accepts it (UC-006 BR-015, ADR-001 §4.2.4): at most [max] UTF-16 code units, cut at a code point boundary. */
internal fun shortened(text: String, max: Int): String {
    if (text.length <= max) return text
    // UC-006 BR-015: A payee or memo longer than YNAB accepts is cut at a code point boundary, never inside a surrogate pair.
    val end = if (Character.isLowSurrogate(text[max]) && Character.isHighSurrogate(text[max - 1])) max - 1 else max
    return text.substring(0, end)
}
