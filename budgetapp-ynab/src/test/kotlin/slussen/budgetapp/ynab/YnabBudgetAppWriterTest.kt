package slussen.budgetapp.ynab

import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import slussen.budgetapp.ynab.YnabWriteExamples.ACCOUNTS_ANSWER
import slussen.budgetapp.ynab.YnabWriteExamples.BUDGET
import slussen.budgetapp.ynab.YnabWriteExamples.ID_A
import slussen.budgetapp.ynab.YnabWriteExamples.ID_B
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_A
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_B
import slussen.budgetapp.ynab.YnabWriteExamples.MISSING_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.REF_A
import slussen.budgetapp.ynab.YnabWriteExamples.REF_B
import slussen.budgetapp.ynab.YnabWriteExamples.REF_COUNTERPART
import slussen.budgetapp.ynab.YnabWriteExamples.TOKEN
import slussen.budgetapp.ynab.YnabWriteExamples.approvedFlagsOf
import slussen.budgetapp.ynab.YnabWriteExamples.error
import slussen.budgetapp.ynab.YnabWriteExamples.listCreated
import slussen.budgetapp.ynab.YnabWriteExamples.received
import slussen.budgetapp.ynab.YnabWriteExamples.saved
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.testfixtures.BudgetAppWriterContractTest
import slussen.pluginapi.testfixtures.ClearScenario
import slussen.pluginapi.testfixtures.DeviceRefusal
import slussen.pluginapi.testfixtures.RecordingRequestThreads
import slussen.pluginapi.testfixtures.WriteScenario
import slussen.pluginapi.value.Secret
import java.net.InetAddress
import java.net.Proxy

/** The YNAB writer against the writer contract test of ADR-001 §4.4, with invented answers of a local test server. */
class YnabBudgetAppWriterTest : BudgetAppWriterContractTest() {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun app(): YnabBudgetApp = YnabBudgetApp(log, server.url("/v1/"), http)

    override fun requestsReceived(): Int = server.requestCount

    override fun runningCalls(): Int = http.dispatcher.runningCallsCount()

    /** The read of the Budget Accounts the writer sends first (S-B-79), then [answers]. */
    private fun writing(vararg answers: MockResponse, items: List<CreateItem> = listOf(ITEM_A, ITEM_B)): WriteScenario {
        server.enqueue(MockResponse().setBody(ACCOUNTS_ANSWER))
        answers.forEach(server::enqueue)
        return WriteScenario(app().writer, Secret(TOKEN), BUDGET, items)
    }

    override fun createAnswered() = writing(listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))))

    override fun expectedCreated(): List<ItemOutcome> = listOf(ItemOutcome.Created(REF_A), ItemOutcome.Created(REF_B))

    override fun createWithHeldImportIdentifier() = writing(listCreated(listOf(saved(REF_A, ID_A)), duplicates = listOf(ID_B)))

    override fun expectedWithHeldImportIdentifier(): List<ItemOutcome> = listOf(ItemOutcome.Created(REF_A), ItemOutcome.AlreadyPresent(null))

    override fun createWithBudgetAccountNotInTheBudget() =
        writing(listCreated(listOf(saved(REF_A, ID_A))), items = listOf(ITEM_A, ITEM_B.copy(budgetAccountReference = MISSING_ACCOUNT)))

    override fun expectedWithBudgetAccountNotInTheBudget(): List<ItemOutcome> = listOf(ItemOutcome.Created(REF_A), ItemOutcome.Unknown)

    override fun createCredentialsRefused() = writing(MockResponse().setResponseCode(401))

    override fun createNeverAnswering() = writing(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    /** The second answer would create both items, so a list sent again by OkHttp or the part would show as Created. */
    override fun createConnectionLostAfterSending() = writing(
        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))),
    )

    override fun createOnRefusingDevice(threads: RecordingRequestThreads): WriteScenario {
        val refusing = OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw DeviceRefusal.exception() })
            .dispatcher(Dispatcher(threads))
            .proxy(Proxy.NO_PROXY)
            .build()
        val writer = YnabBudgetApp(log, "https://api.budget-app.invalid/v1/".toHttpUrl(), refusing).writer
        return WriteScenario(writer, Secret(TOKEN), BUDGET, listOf(ITEM_A, ITEM_B))
    }

    override fun sentApproved(): List<Boolean> = approvedFlagsOf(server.received())

    private fun clearing(answer: MockResponse): ClearScenario {
        server.enqueue(answer)
        return ClearScenario(app().writer, Secret(TOKEN), BUDGET, REF_COUNTERPART)
    }

    override fun clearAnswered() =
        clearing(MockResponse().setBody("""{"data":{"transaction_ids":["$REF_COUNTERPART"],"transactions":[${saved(REF_COUNTERPART, null, cleared = "cleared")}],"server_knowledge":93}}"""))

    override fun clearRefused() = clearing(error(400, "400", "bad_request"))

    override fun clearNeverAnswering() = clearing(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun plantedWriteValues(): List<String> = YnabWriteExamples.planted
}
