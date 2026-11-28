package slussen.core.upload

import kotlinx.coroutines.awaitCancellation
import slussen.testing.ScriptedBudgetAppWriter
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.testfixtures.BudgetAppWriterContractTest
import slussen.pluginapi.testfixtures.ClearScenario
import slussen.pluginapi.testfixtures.RecordingRequestThreads
import slussen.pluginapi.testfixtures.WriteScenario
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.Secret
import java.time.LocalDate

/**
 * C2 review item 3: the scripted writer of `:testing`, which the Upload's tests rest on, passes the same contract test as the YNAB writer
 * (ADR-001 §4.4), each scenario scripted as the YNAB writer's test server answers it. It lives in `:core`'s tests, since ADR-001 §2
 * lets `:testing` depend on no project in its own tests.
 */
class ScriptedBudgetAppWriterContractTest : BudgetAppWriterContractTest() {
    private val writers = mutableListOf<ScriptedBudgetAppWriter>()

    private fun writer(configure: ScriptedBudgetAppWriter.() -> Unit = {}): ScriptedBudgetAppWriter =
        ScriptedBudgetAppWriter().apply(configure).also { writers += it }

    override fun requestsReceived(): Int = writers.sumOf { it.requests.size }

    override fun runningCalls(): Int = writers.sumOf { it.running.get() }

    private fun item(account: String, identifier: String, payee: String?) =
        CreateItem(account, identifier, LocalDate.of(2026, 10, 14), Amount(-731_942), payee, "Planted memo violet anchor", cleared = true, transferBudgetAccountReference = null)

    private val items = listOf(item(ACCOUNT, ID_A, "Planted Payee Juniper Mill"), item(ACCOUNT, ID_B, null))

    private fun writing(items: List<CreateItem> = this.items, configure: ScriptedBudgetAppWriter.() -> Unit = {}) =
        WriteScenario(writer { budgetAccounts = setOf(ACCOUNT); configure() }, Secret(TOKEN), BUDGET, items)

    override fun createAnswered() = writing()

    override fun expectedCreated(): List<ItemOutcome> = listOf(ItemOutcome.Created("ref-$ID_A"), ItemOutcome.Created("ref-$ID_B"))

    override fun createWithHeldImportIdentifier() = writing {
        script = { if (it.importIdentifier == ID_B) ScriptedBudgetAppWriter.Script.Answers(ItemOutcome.AlreadyPresent(null)) else ScriptedBudgetAppWriter.Script.Answers(ItemOutcome.Created("ref-${it.importIdentifier}")) }
    }

    override fun expectedWithHeldImportIdentifier(): List<ItemOutcome> = listOf(ItemOutcome.Created("ref-$ID_A"), ItemOutcome.AlreadyPresent(null))

    override fun createWithBudgetAccountNotInTheBudget() = writing(listOf(items[0], items[1].copy(budgetAccountReference = "account-pl4nted-elsewhere")))

    override fun expectedWithBudgetAccountNotInTheBudget(): List<ItemOutcome> = listOf(ItemOutcome.Created("ref-$ID_A"), ItemOutcome.Unknown)

    override fun createCredentialsRefused() = writing { callRefusal = ScriptedBudgetAppWriter.CallRefusal.CredentialsRefused }

    override fun createNeverAnswering() = writing { afterWrite = { awaitCancellation() } }

    override fun createConnectionLostAfterSending() = writing { script = { ScriptedBudgetAppWriter.Script.WrittenAnswerLost } }

    override fun createOnRefusingDevice(threads: RecordingRequestThreads) = writing { script = { ScriptedBudgetAppWriter.Script.NotReceived } }

    override fun sentApproved(): List<Boolean> = writers.flatMap { it.sent }.map { false }

    private fun clearing(configure: ScriptedBudgetAppWriter.() -> Unit) = ClearScenario(writer(configure), Secret(TOKEN), BUDGET, COUNTERPART)

    override fun clearAnswered() = clearing {}

    override fun clearRefused() = clearing { clearScript = { ScriptedBudgetAppWriter.ClearScript.Answers(ClearResult.NotCleared) } }

    override fun clearNeverAnswering() = clearing { afterClear = { awaitCancellation() } }

    override fun plantedWriteValues(): List<String> =
        listOf(TOKEN, BUDGET, ACCOUNT, ID_A, ID_B, COUNTERPART, "Planted Payee Juniper Mill", "Planted memo violet anchor", "731942", "pl4nted")

    private companion object {
        const val TOKEN = "invented-scripted-token-K2"
        const val BUDGET = "budget-pl4nted-9001"
        const val ACCOUNT = "account-pl4nted-9001"
        const val ID_A = "SLS1-pl4ntedscriptedidentifier01"
        const val ID_B = "SLS1-pl4ntedscriptedidentifier02"
        const val COUNTERPART = "tx-pl4nted-9105"
    }
}
