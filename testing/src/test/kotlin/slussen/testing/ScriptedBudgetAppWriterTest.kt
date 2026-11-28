package slussen.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestTimeout
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.Secret
import java.time.Duration
import java.time.LocalDate

/** The scripted Budget App writer for the Upload's tests (phases C5a and C5b) does what a test scripts. */
class ScriptedBudgetAppWriterTest {
    private fun context(gate: RequestGate = RequestGate { Admission.Allowed }) = object : RequestContext {
        override val gate = gate
        override val timeout = RequestTimeout(Duration.ofSeconds(5))
        override val credential = Secret("invented-token")
    }

    private fun item(identifier: String) =
        CreateItem("account-1", identifier, LocalDate.of(2026, 10, 14), Amount(-1000), "Invented Payee", null, cleared = true, transferBudgetAccountReference = null)

    @Test
    @DisplayName("Each item gets its scripted outcome; a created item and one whose answer is lost after the write count as written")
    fun scriptedOutcomesAndLostAnswers() {
        val writer = ScriptedBudgetAppWriter()
        writer.script = { item ->
            when (item.importIdentifier) {
                "id-1" -> ScriptedBudgetAppWriter.Script.Answers(ItemOutcome.Created("ref-1"))
                "id-2" -> ScriptedBudgetAppWriter.Script.WrittenAnswerLost
                "id-3" -> ScriptedBudgetAppWriter.Script.Answers(ItemOutcome.Rejected("Invented reason"))
                else -> ScriptedBudgetAppWriter.Script.NotReceived
            }
        }
        val items = listOf(item("id-1"), item("id-2"), item("id-3"), item("id-4"))
        val result = runBlocking { writer.create("budget-1", items, context()) }
        assertEquals(CreateResult.Answered(listOf(ItemOutcome.Created("ref-1"), ItemOutcome.Unknown, ItemOutcome.Rejected("Invented reason"), ItemOutcome.Unknown)), result)
        assertEquals(listOf("id-1", "id-2"), writer.written.map { it.importIdentifier })
        assertEquals(1, writer.createCalls.size)
    }

    @Test
    @DisplayName("A refusal by the gate or a scripted refusal of the call writes nothing")
    fun refusalsWriteNothing() {
        val writer = ScriptedBudgetAppWriter()
        val refusing = RequestGate { Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded) }
        assertEquals(CreateResult.StoppedByGate(RefusalReason.OperationEnded), runBlocking { writer.create("budget-1", listOf(item("id-1")), context(refusing)) })
        assertEquals(ClearResult.NotCleared, runBlocking { writer.markCleared("budget-1", "ref-9", context(refusing)) })
        writer.callRefusal = ScriptedBudgetAppWriter.CallRefusal.CredentialsRefused
        assertEquals(CreateResult.CredentialsRefused, runBlocking { writer.create("budget-1", listOf(item("id-1")), context()) })
        assertEquals(emptyList<CreateItem>(), writer.written)
        assertEquals(emptyList<String>(), writer.cleared)
    }

    @Test
    @DisplayName("A call cancelled during its delay before the write writes nothing; one cancelled after it is written and its answer lost; both answer Unknown")
    fun delaysAndCancellations() {
        listOf(true, false).forEach { beforeTheWrite ->
            val writer = ScriptedBudgetAppWriter()
            val waiting = CompletableDeferred<Unit>()
            val hold: suspend () -> Unit = { waiting.complete(Unit); awaitCancellation() }
            if (beforeTheWrite) writer.beforeWrite = hold else writer.afterWrite = hold
            var result: CreateResult? = null
            runBlocking {
                val running = launch { result = writer.create("budget-1", listOf(item("id-1")), context()) }
                waiting.await()
                running.cancelAndJoin()
            }
            assertEquals(CreateResult.Answered(listOf(ItemOutcome.Unknown)), result)
            assertEquals(if (beforeTheWrite) 0 else 1, writer.written.size)
        }
    }
}
