package slussen.testing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import slussen.pluginapi.budgetapp.BudgetAppWriter
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Budget App writer a test scripts (ADR-001 §4.2.4), for the Upload of phases C5a and C5b. It passes the same writer
 * contract test as the YNAB writer (`BudgetAppWriterContractTest`, C2 review item 3) and asks the gate where that one
 * does: before the read of the Budget Accounts, before the list, and before each single send after a refused list. A
 * test scripts each item's outcome, an answer lost after the write happened, a list refused as a whole, a refusal of
 * the whole call, a delay before or after a write, and the same for a clearing. Every request is bounded by the request
 * timeout of the context; a cancellation or a timeout gives `Unknown` for every item not answered yet and keeps the
 * outcomes already answered. It keeps every call and every item that reached the Budget App, so that a test can
 * assert that nothing was written twice.
 */
class ScriptedBudgetAppWriter : BudgetAppWriter {
    /** One call of [create] as the writer received it. */
    data class CreateCall(val budgetReference: String, val items: List<CreateItem>, val context: RequestContext)

    /** One call of [markCleared] as the writer received it. */
    data class ClearCall(val budgetReference: String, val counterpartReference: String, val context: RequestContext)

    /** What the Budget App does with one item of a request that reached it. */
    sealed interface Script {
        /** It answers [outcome]; a `Created` item counts as written. */
        data class Answers(val outcome: ItemOutcome) : Script

        /** It writes the item and its answer is lost: the item counts as written and is `Unknown`, as is every item after it. */
        data object WrittenAnswerLost : Script

        /** It never receives the item: the item is `Unknown`, as is every item after it. */
        data object NotReceived : Script
    }

    /** What the Budget App does with one clearing that reached it. */
    sealed interface ClearScript {
        /** It answers [result]; `Cleared` counts as cleared. */
        data class Answers(val result: ClearResult) : ClearScript

        /** It marks the counterpart Cleared and its answer is lost: `Unknown`, and it counts as cleared. */
        data object ClearedAnswerLost : ClearScript

        /** It never receives the clearing: `Unknown`. */
        data object NotReceived : ClearScript
    }

    /** A refusal of a whole call, answered to the read before anything is written. */
    enum class CallRefusal { CredentialsRefused, StoppedByGate }

    val createCalls: MutableList<CreateCall> = CopyOnWriteArrayList()
    val clearCalls: MutableList<ClearCall> = CopyOnWriteArrayList()

    /** The kind of every request the gate let out, in order. */
    val requests: MutableList<RequestKind> = CopyOnWriteArrayList()

    /** Every item a create request carried to the Budget App, in order (a list's items, then each single send). */
    val sent: MutableList<CreateItem> = CopyOnWriteArrayList()

    /** Every item the Budget App holds because of a call of this writer, in the order written. */
    val written: MutableList<CreateItem> = CopyOnWriteArrayList()

    /** Every counterpart reference marked Cleared, in order. */
    val cleared: MutableList<String> = CopyOnWriteArrayList()

    /** How many requests are waiting in a scripted delay. */
    val running: AtomicInteger = AtomicInteger()

    /** The Budget Accounts the Budget holds; an item of any other is `Unknown` and not sent (S-B-79). Null: every item's. */
    var budgetAccounts: Set<String>? = null

    /** What happens to each item; by default it is created with the reference `ref-<Import Identifier>`. */
    var script: (CreateItem) -> Script = { Script.Answers(ItemOutcome.Created("ref-${it.importIdentifier}")) }

    /** When true, the Budget App refuses each list as a whole, and each of its items is sent alone (Q-08). */
    var listRefused: Boolean = false

    /** When set, the calls are refused as a whole at the read before anything is written. */
    var callRefusal: CallRefusal? = null

    /** Runs after the gate admitted a write and before it reaches the Budget App: ending here, nothing of it is written. */
    var beforeWrite: suspend () -> Unit = {}

    /** Runs after a write's items are written and before its answer: ending here loses the answer. */
    var afterWrite: suspend () -> Unit = {}

    /** What the Budget App does with a clearing once the gate admitted it. */
    var clearScript: (counterpartReference: String) -> ClearScript = { ClearScript.Answers(ClearResult.Cleared) }

    /** Runs after the gate admitted a clearing and before it reaches the Budget App: ending here, nothing changes. */
    var beforeClear: suspend () -> Unit = {}

    /** Runs after a clearing reached the Budget App and before its answer: ending here loses the answer. */
    var afterClear: suspend () -> Unit = {}

    override suspend fun create(budgetReference: String, items: List<CreateItem>, context: RequestContext): CreateResult {
        createCalls += CreateCall(budgetReference, items, context)
        if (items.isEmpty()) return CreateResult.Answered(emptyList())
        val outcomes = arrayOfNulls<ItemOutcome>(items.size)
        when (val read = admit(context, RequestKind.BudgetAppRead)) {
            is Admitted.Refused -> return CreateResult.StoppedByGate(read.reason)
            Admitted.Cancelled -> return answered(outcomes)
            Admitted.Yes -> Unit
        }
        when (callRefusal) {
            CallRefusal.CredentialsRefused -> return CreateResult.CredentialsRefused
            CallRefusal.StoppedByGate -> return CreateResult.StoppedByGate(RefusalReason.OperationEnded)
            null -> Unit
        }
        items.forEachIndexed { index, item ->
            if (budgetAccounts?.contains(item.budgetAccountReference) == false) outcomes[index] = ItemOutcome.Unknown
        }
        val toSend = items.indices.filter { outcomes[it] == null }
        if (toSend.isEmpty()) return answered(outcomes)

        when (val list = admit(context, RequestKind.BudgetAppWrite)) {
            is Admitted.Refused -> return CreateResult.StoppedByGate(list.reason)
            Admitted.Cancelled -> return answered(outcomes)
            Admitted.Yes -> Unit
        }
        if (!held(beforeWrite, context)) return answered(outcomes)
        sent += toSend.map { items[it] }
        if (listRefused) {
            sendOneByOne(items, toSend, outcomes, context)
            return answered(outcomes)
        }
        val answers = toSend.associateWith { index -> settle(items[index]) }
        if (!held(afterWrite, context)) return answered(outcomes)
        answers.forEach { (index, outcome) -> outcomes[index] = outcome }
        return answered(outcomes)
    }

    /** Q-08: each item alone, in order, each through the gate; the first that gets no answer ends the sending. */
    private suspend fun sendOneByOne(items: List<CreateItem>, toSend: List<Int>, outcomes: Array<ItemOutcome?>, context: RequestContext) {
        for (index in toSend) {
            if (admit(context, RequestKind.BudgetAppWrite) !is Admitted.Yes) return
            if (!held(beforeWrite, context)) return
            sent += items[index]
            val scripted = script(items[index])
            val outcome = settle(items[index], scripted)
            if (!held(afterWrite, context)) return
            // The answer of this single send was lost or it never arrived: the sending ends, as the YNAB writer's does.
            if (scripted !is Script.Answers) return
            outcomes[index] = outcome
        }
    }

    /** What the Budget App does with [item] once it received it; `Unknown` when its answer is lost or it never arrived. */
    private fun settle(item: CreateItem, scripted: Script = script(item)): ItemOutcome = when (scripted) {
        is Script.Answers -> scripted.outcome.also { if (it is ItemOutcome.Created) written += item }
        Script.WrittenAnswerLost -> ItemOutcome.Unknown.also { written += item }
        Script.NotReceived -> ItemOutcome.Unknown
    }

    override suspend fun markCleared(budgetReference: String, counterpartReference: String, context: RequestContext): ClearResult {
        clearCalls += ClearCall(budgetReference, counterpartReference, context)
        if (admit(context, RequestKind.BudgetAppWrite) !is Admitted.Yes) return ClearResult.NotCleared
        if (!held(beforeClear, context)) return ClearResult.NotCleared
        val result = when (val scripted = clearScript(counterpartReference)) {
            is ClearScript.Answers -> scripted.result.also { if (it == ClearResult.Cleared) cleared += counterpartReference }
            ClearScript.ClearedAnswerLost -> ClearResult.Unknown.also { cleared += counterpartReference }
            ClearScript.NotReceived -> ClearResult.Unknown
        }
        if (!held(afterClear, context)) return ClearResult.Unknown
        return result
    }

    private sealed interface Admitted {
        data object Yes : Admitted

        data class Refused(val reason: RefusalReason) : Admitted

        data object Cancelled : Admitted
    }

    /** Asks the gate, as the YNAB writer does before every request; a cancellation here sends nothing. */
    private suspend fun admit(context: RequestContext, kind: RequestKind): Admitted {
        val admission = try {
            context.gate.admit(RequestSummary(kind, null, null, unattended = false))
        } catch (e: CancellationException) {
            return Admitted.Cancelled
        }
        if (admission is Admission.Refused) return Admitted.Refused(admission.reason)
        if (!currentCoroutineContext().isActive) return Admitted.Cancelled
        requests += kind
        return Admitted.Yes
    }

    /** Runs [step] within the request timeout; false when it timed out or was cancelled. */
    private suspend fun held(step: suspend () -> Unit, context: RequestContext): Boolean {
        running.incrementAndGet()
        return try {
            withTimeout(context.timeout.duration.toMillis()) { step() }
            true
        } catch (e: CancellationException) {
            false
        } finally {
            running.decrementAndGet()
        }
    }

    /** Every item not answered yet is `Unknown`; the outcomes already answered are kept. */
    private fun answered(outcomes: Array<ItemOutcome?>) = CreateResult.Answered(outcomes.map { it ?: ItemOutcome.Unknown })
}
