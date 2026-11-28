package slussen.testing

import kotlinx.coroutines.CancellationException
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

/**
 * A Budget App writer a test scripts (ADR-001 §4.2.4), for the Upload of phases C5a and C5b: each item's outcome, an
 * answer lost after the write happened, a refusal of the whole call, and a delay before or after the write. Like a
 * real part it asks the gate before each call's write, as a Budget App write, and sends nothing when refused; it keeps
 * every call and every item that reached the Budget App, so that a test can assert that nothing was written twice.
 */
class ScriptedBudgetAppWriter : BudgetAppWriter {
    /** One call of [create] as the writer received it. */
    data class CreateCall(val budgetReference: String, val items: List<CreateItem>, val context: RequestContext)

    /** One call of [markCleared] as the writer received it. */
    data class ClearCall(val budgetReference: String, val counterpartReference: String, val context: RequestContext)

    /** What the Budget App does with one item of a call that reached it. */
    sealed interface Script {
        /** It answers [outcome]; a `Created` item counts as written. */
        data class Answers(val outcome: ItemOutcome) : Script

        /** It writes the item and its answer is lost: the item counts as written and its outcome is `Unknown`. */
        data object WrittenAnswerLost : Script

        /** It never receives the item, and its outcome is `Unknown`, as for a request that got no answer. */
        data object NotReceived : Script
    }

    /** A refusal of a whole call, before anything of it is written. */
    enum class CallRefusal { CredentialsRefused, StoppedByGate }

    val createCalls = mutableListOf<CreateCall>()
    val clearCalls = mutableListOf<ClearCall>()

    /** Every item the Budget App holds because of a call of this writer, in the order written. */
    val written = mutableListOf<CreateItem>()

    /** Every counterpart reference marked Cleared, in order. */
    val cleared = mutableListOf<String>()

    /** What happens to each item; by default it is created with the reference `ref-<Import Identifier>`. */
    var script: (CreateItem) -> Script = { Script.Answers(ItemOutcome.Created("ref-${it.importIdentifier}")) }

    /** When set, the next calls are refused as a whole before anything is written. */
    var callRefusal: CallRefusal? = null

    /** Runs after the gate admitted a call and before anything of it is written: a cancellation here writes nothing. */
    var beforeWrite: suspend () -> Unit = {}

    /** Runs after the call's items are written and before it answers: a cancellation here loses the answer. */
    var afterWrite: suspend () -> Unit = {}

    /** What the Budget App answers to a clearing once the gate admitted it. */
    var clearAnswer: (counterpartReference: String) -> ClearResult = { ClearResult.Cleared }

    override suspend fun create(budgetReference: String, items: List<CreateItem>, context: RequestContext): CreateResult {
        createCalls += CreateCall(budgetReference, items, context)
        val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppWrite, null, null, unattended = false))
        if (admission is Admission.Refused) return CreateResult.StoppedByGate(admission.reason)
        when (callRefusal) {
            CallRefusal.CredentialsRefused -> return CreateResult.CredentialsRefused
            CallRefusal.StoppedByGate -> return CreateResult.StoppedByGate(RefusalReason.OperationEnded)
            null -> Unit
        }
        try {
            beforeWrite()
        } catch (e: CancellationException) {
            return CreateResult.Answered(items.map { ItemOutcome.Unknown })
        }
        val outcomes = items.map { item ->
            when (val scripted = script(item)) {
                is Script.Answers -> scripted.outcome.also { if (it is ItemOutcome.Created) written += item }
                Script.WrittenAnswerLost -> ItemOutcome.Unknown.also { written += item }
                Script.NotReceived -> ItemOutcome.Unknown
            }
        }
        try {
            afterWrite()
        } catch (e: CancellationException) {
            return CreateResult.Answered(items.map { ItemOutcome.Unknown })
        }
        return CreateResult.Answered(outcomes)
    }

    override suspend fun markCleared(budgetReference: String, counterpartReference: String, context: RequestContext): ClearResult {
        clearCalls += ClearCall(budgetReference, counterpartReference, context)
        val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppWrite, null, null, unattended = false))
        if (admission is Admission.Refused) return ClearResult.NotCleared
        return clearAnswer(counterpartReference).also { if (it == ClearResult.Cleared) cleared += counterpartReference }
    }
}
