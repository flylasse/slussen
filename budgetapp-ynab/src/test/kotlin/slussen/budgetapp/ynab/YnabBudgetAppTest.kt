package slussen.budgetapp.ynab

import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.BudgetAccountRead
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransferLink
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.BudgetAppContractTest
import slussen.pluginapi.testfixtures.ChangesScenario
import slussen.pluginapi.testfixtures.CredentialScenario
import slussen.pluginapi.testfixtures.DeviceRefusal
import slussen.pluginapi.testfixtures.ReadScenario
import slussen.pluginapi.testfixtures.RecordingRequestThreads
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.net.InetAddress
import java.net.Proxy
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** The YNAB Budget App against the contract tests of ADR-001 §4.4, with invented recorded answers. */
class YnabBudgetAppTest : BudgetAppContractTest() {
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

    private fun tokenInput() = CredentialInput(mapOf(YnabCredentials.TOKEN to Secret(PLANTED_TOKEN)))

    private fun answering(response: MockResponse): CredentialScenario {
        server.enqueue(response)
        return CredentialScenario(app().credentials, tokenInput())
    }

    override fun budgetApp(log: SlussenLog): BudgetApp = YnabBudgetApp(log)

    override fun credentialsAccepted() = answering(MockResponse().setBody(BUDGETS_ANSWER))

    override fun credentialsRefused() = answering(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun credentialServiceUnavailable() = answering(MockResponse().setResponseCode(503))

    override fun credentialServiceNeverAnswering() =
        answering(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun credentialsThatCannotBeValid() =
        CredentialScenario(app().credentials, CredentialInput(mapOf(YnabCredentials.TOKEN to Secret("  "))))

    override fun requestsReceived(): Int = server.requestCount

    override fun runningCalls(): Int = http.dispatcher.runningCallsCount()

    override fun plantedValues(): List<String> = listOf(PLANTED_TOKEN)

    private fun reading(response: MockResponse): ReadScenario {
        server.enqueue(response)
        return ReadScenario(app().reader, Secret(PLANTED_TOKEN))
    }

    override fun budgetsAnswered() = reading(MockResponse().setBody(BUDGETS_ANSWER))

    override fun expectedBudgets(): List<BudgetRead> = listOf(
        BudgetRead(
            reference = "budget-0001",
            name = "Example Household",
            currency = CurrencyCode("USD"),
            budgetAccounts = listOf(
                BudgetAccountRead("account-0001", "Example Checking", Amount(4140050)),
                BudgetAccountRead("account-0002", "Example Card", Amount(-1268400)),
                BudgetAccountRead("account-0004", "Example Closed Savings", Amount(0)),
            ),
        ),
        BudgetRead("budget-0002", "Example Summer House", CurrencyCode("EUR"), emptyList()),
    )

    override fun budgetsCredentialRefused() = reading(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun budgetsUnavailable() = reading(MockResponse().setResponseCode(503))

    override fun budgetsNeverAnswering() = reading(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    /**
     * ADR-001 §4.0 (S-36): a device that refuses network access, as Android does for an app without the network
     * permission: the address lookup throws, and the requests run on [threads]. No proxy, so the lookup is of the
     * Budget App's own invented host.
     */
    private fun refusingDevice(threads: RecordingRequestThreads) = YnabBudgetApp(
        SilentLog,
        "https://api.budget-app.invalid/v1/".toHttpUrl(),
        OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw DeviceRefusal.exception() })
            .dispatcher(Dispatcher(threads))
            .proxy(Proxy.NO_PROXY)
            .build(),
    )

    override fun credentialsOnRefusingDevice(threads: RecordingRequestThreads) =
        CredentialScenario(refusingDevice(threads).credentials, tokenInput())

    override fun budgetsOnRefusingDevice(threads: RecordingRequestThreads) =
        ReadScenario(refusingDevice(threads).reader, Secret(PLANTED_TOKEN))

    private fun changes(response: MockResponse, since: ChangeCursor? = null): ChangesScenario {
        server.enqueue(response)
        return ChangesScenario(app().reader, Secret(PLANTED_TOKEN), "budget-0001", since, FROM)
    }

    override fun changesAnswered() = changes(MockResponse().setBody(CHANGES_ANSWER))

    override fun expectedChanges(): ChangeSet = ChangeSet(
        listOf(
            BudgetAppTransactionRead(
                "tx-0001", "account-0001", OCT_14, Amount(-42500), "Corner Grocer", "Invented memo", "YNAB:-42500:2026-10-14:1",
                EntryKind.Imported, null, ReadState.Present, cleared = true, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0002", "account-0001", OCT_14, Amount(-8000), "Harbor Fuel", null, null,
                EntryKind.Typed, null, ReadState.Present, cleared = false, approved = false,
            ),
            BudgetAppTransactionRead(
                "tx-0003", "account-0001", OCT_14, Amount(-1500), "Pinecrest Bakery", null, OWN_IDENTIFIER,
                EntryKind.Imported, null, ReadState.Deleted, cleared = false, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0004", "account-0001", OCT_14, Amount(-2750), "Harbor Pharmacy", null, "SLS1-bbbbbbbbbbbbbbbbbbbbbbbbbb",
                EntryKind.Imported, null, ReadState.Merged("tx-0005"), cleared = false, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0005", "account-0001", LocalDate.of(2026, 10, 12), Amount(-2750), "Harbor Pharmacy", null, null,
                EntryKind.Typed, null, ReadState.Present, cleared = true, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0006", "account-0001", OCT_14, Amount(-150000), "Transfer : Example Card", null, "SLS1-cccccccccccccccccccccccccc",
                EntryKind.Imported, TransferLink("tx-0007", "account-0002"), ReadState.Present, cleared = true, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0007", "account-0002", OCT_14, Amount(150000), "Transfer : Example Checking", null, null,
                EntryKind.TransferSide, TransferLink("tx-0006", "account-0001"), ReadState.Present, cleared = false, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0008", "account-0001", OCT_14, Amount(-150000), "Transfer : Example Card", null, null,
                EntryKind.TransferSide, TransferLink("tx-0009", "account-0002"), ReadState.Present, cleared = true, approved = true,
            ),
            BudgetAppTransactionRead(
                "tx-0009", "account-0002", OCT_14, Amount(150000), "Transfer : Example Checking", null, null,
                EntryKind.TransferSide, TransferLink("tx-0008", "account-0001"), ReadState.Present, cleared = true, approved = true,
            ),
        ),
        ChangeCursor("4182"),
    )

    override fun changesCredentialRefused() = changes(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun changesUnavailable() = changes(MockResponse().setResponseCode(503))

    override fun changesNeverAnswering() = changes(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    private fun readChanges(since: ChangeCursor?, body: String = CHANGES_ANSWER): ReadResult<ChangeSet> {
        server.enqueue(MockResponse().setBody(body))
        return runBlocking { app().reader.readChanges("budget-0001", since, FROM, TestRequestContext(credential = Secret(PLANTED_TOKEN))) }
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: the first read of a Budget asks YNAB for its transactions from the first day of the read period, with the stored token")
    fun firstReadAsksFromTheReadPeriod() {
        readChanges(since = null)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/budgets/budget-0001/transactions", request.requestUrl!!.encodedPath)
        assertEquals("2026-08-06", request.requestUrl!!.queryParameter("since_date"))
        assertEquals(null, request.requestUrl!!.queryParameter("last_knowledge_of_server"))
        assertEquals("Bearer $PLANTED_TOKEN", request.getHeader("Authorization"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: every later read asks YNAB for every change since the previous read by its server knowledge, whatever the date of the transaction, and the next cursor is YNAB's new server knowledge")
    fun laterReadAsksForEveryChangeSinceTheCursor() {
        val result = readChanges(since = ChangeCursor("4100"))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("4100", request.requestUrl!!.queryParameter("last_knowledge_of_server"))
        assertEquals(null, request.requestUrl!!.queryParameter("since_date"))
        assertEquals(ChangeCursor("4182"), (result as ReadResult.Answered).value.next)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005", "BR-020"], requirements = ["C-009"])
    @DisplayName("C-009, F-4: YNAB's milliunits are thousandths; reconciled counts as Cleared; a deleted transaction is Deleted, and one deleted that another of the answer names as its matched transaction is Merged into that one")
    fun ynabFieldsAreNormalized() {
        val answer = """
            {"data":{"server_knowledge":7,"transactions":[
              {"id":"tx-0101","date":"2026-10-14","amount":-1234,"memo":null,"cleared":"reconciled","approved":true,
               "account_id":"account-0001","payee_name":null,"import_id":null,"transfer_account_id":null,
               "transfer_transaction_id":null,"matched_transaction_id":null,"deleted":false},
              {"id":"tx-0102","date":"2026-10-13","amount":-500,"memo":null,"cleared":"uncleared","approved":false,
               "account_id":"account-0001","payee_name":"Corner Grocer","import_id":null,"transfer_account_id":null,
               "transfer_transaction_id":null,"matched_transaction_id":null,"deleted":true}
            ]}}
        """
        val read = (readChanges(since = null, body = answer) as ReadResult.Answered).value.transactions

        assertEquals(Amount(-1234), read[0].amount)
        assertEquals(true, read[0].cleared)
        assertEquals(EntryKind.Typed, read[0].entry)
        assertEquals(ReadState.Deleted, read[1].state)
        assertEquals(false, read[1].cleared)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read")
    @DisplayName("A1: an answer YNAB gives that cannot be read is a TransientFailure(UnreadableAnswer), never a partial change set")
    fun unreadableChangesAreATransientFailure() {
        val result = readChanges(since = null, body = """{"data":{"transactions":[{"id":"tx-1"}]}}""")
        assertEquals(ReadResult.TransientFailure(FailureKind.UnreadableAnswer), result)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["C-008"])
    @DisplayName("BR-002: YNAB asks for a personal access token and checks it by listing the Budgets")
    @OptIn(SensitiveApi::class)
    fun tokenIsCheckedByListingTheBudgets() {
        val credentials = app().credentials
        assertEquals(listOf(YnabCredentials.TOKEN), credentials.form.fields.map { it.key })
        assertEquals(FieldKind.Secret, credentials.form.fields.single().kind)
        server.enqueue(MockResponse().setBody(BUDGETS_ANSWER))
        val result = runBlocking { credentials.check(tokenInput(), TestRequestContext()) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/budgets", request.requestUrl!!.encodedPath)
        assertEquals("Bearer $PLANTED_TOKEN", request.getHeader("Authorization"))
        assertEquals(PLANTED_TOKEN, (result as CredentialCheck.Accepted).credential.reveal())
    }

    @Test
    @DisplayName("ADR-001 §4.2.3: Budgets are read with their Budget Accounts and the stored token")
    fun budgetsAreReadWithAccountsAndTheStoredToken() {
        server.enqueue(MockResponse().setBody(BUDGETS_ANSWER))
        runBlocking { app().reader.readBudgets(TestRequestContext(credential = Secret(PLANTED_TOKEN))) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("true", request.requestUrl!!.queryParameter("include_accounts"))
        assertEquals("Bearer $PLANTED_TOKEN", request.getHeader("Authorization"))
    }

    @Test
    @DisplayName("C-009: the profile declares YNAB's numbers and capabilities")
    fun profileDeclaresTheNumbersOfC009() {
        val profile = YnabBudgetApp(SilentLog).profile
        assertEquals(36, profile.importIdentifierMaxLength)
        assertEquals(200, profile.payeeMaxLength)
        assertEquals(500, profile.memoMaxLength)
        assertEquals(10, profile.matchingDateWindowDays)
        assertEquals(true, profile.refusesReusedImportIdentifier)
        assertEquals(true, profile.supportsClearedMarker)
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }

    private companion object {
        const val PLANTED_TOKEN = "invented-ynab-token-Q7ZK3"
        const val UNAUTHORIZED = """{"error":{"id":"401","name":"unauthorized","detail":"invented"}}"""
        val OCT_14: LocalDate = LocalDate.of(2026, 10, 14)

        /** The first day of the read period of BR-016 for an invented today, 2026-10-15, 70 days back. */
        val FROM: LocalDate = LocalDate.of(2026, 8, 6)

        const val OWN_IDENTIFIER = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa"

        /**
         * An invented answer of YNAB's transactions endpoint: an Import, a transaction entered by hand, a deletion, a merge
         * (the deleted Import tx-0004 named as matched by tx-0005), and two linked transfers alike in amount and date.
         */
        const val CHANGES_ANSWER = """
            {"data":{"server_knowledge":4182,"transactions":[
              {"id":"tx-0001","date":"2026-10-14","amount":-42500,"memo":"Invented memo","cleared":"cleared","approved":true,
               "account_id":"account-0001","payee_name":"Corner Grocer","import_id":"YNAB:-42500:2026-10-14:1",
               "transfer_account_id":null,"transfer_transaction_id":null,"matched_transaction_id":null,"deleted":false},
              {"id":"tx-0002","date":"2026-10-14","amount":-8000,"memo":null,"cleared":"uncleared","approved":false,
               "account_id":"account-0001","payee_name":"Harbor Fuel","import_id":null,
               "transfer_account_id":null,"transfer_transaction_id":null,"matched_transaction_id":null,"deleted":false},
              {"id":"tx-0003","date":"2026-10-14","amount":-1500,"memo":null,"cleared":"uncleared","approved":true,
               "account_id":"account-0001","payee_name":"Pinecrest Bakery","import_id":"$OWN_IDENTIFIER",
               "transfer_account_id":null,"transfer_transaction_id":null,"matched_transaction_id":null,"deleted":true},
              {"id":"tx-0004","date":"2026-10-14","amount":-2750,"memo":null,"cleared":"uncleared","approved":true,
               "account_id":"account-0001","payee_name":"Harbor Pharmacy","import_id":"SLS1-bbbbbbbbbbbbbbbbbbbbbbbbbb",
               "transfer_account_id":null,"transfer_transaction_id":null,"matched_transaction_id":null,"deleted":true},
              {"id":"tx-0005","date":"2026-10-12","amount":-2750,"memo":null,"cleared":"cleared","approved":true,
               "account_id":"account-0001","payee_name":"Harbor Pharmacy","import_id":null,
               "transfer_account_id":null,"transfer_transaction_id":null,"matched_transaction_id":"tx-0004","deleted":false},
              {"id":"tx-0006","date":"2026-10-14","amount":-150000,"memo":null,"cleared":"cleared","approved":true,
               "account_id":"account-0001","payee_name":"Transfer : Example Card","import_id":"SLS1-cccccccccccccccccccccccccc",
               "transfer_account_id":"account-0002","transfer_transaction_id":"tx-0007","matched_transaction_id":null,"deleted":false},
              {"id":"tx-0007","date":"2026-10-14","amount":150000,"memo":null,"cleared":"uncleared","approved":true,
               "account_id":"account-0002","payee_name":"Transfer : Example Checking","import_id":null,
               "transfer_account_id":"account-0001","transfer_transaction_id":"tx-0006","matched_transaction_id":null,"deleted":false},
              {"id":"tx-0008","date":"2026-10-14","amount":-150000,"memo":null,"cleared":"cleared","approved":true,
               "account_id":"account-0001","payee_name":"Transfer : Example Card","import_id":null,
               "transfer_account_id":"account-0002","transfer_transaction_id":"tx-0009","matched_transaction_id":null,"deleted":false},
              {"id":"tx-0009","date":"2026-10-14","amount":150000,"memo":null,"cleared":"cleared","approved":true,
               "account_id":"account-0002","payee_name":"Transfer : Example Checking","import_id":null,
               "transfer_account_id":"account-0001","transfer_transaction_id":"tx-0008","matched_transaction_id":null,"deleted":false}
            ]}}
        """

        const val BUDGETS_ANSWER = """
            {"data":{"budgets":[
              {"id":"budget-0001","name":"Example Household","currency_format":{"iso_code":"USD"},
               "accounts":[
                 {"id":"account-0001","name":"Example Checking","balance":4140050,"closed":false,"deleted":false},
                 {"id":"account-0002","name":"Example Card","balance":-1268400,"closed":false,"deleted":false},
                 {"id":"account-0003","name":"Example Deleted","balance":0,"closed":true,"deleted":true},
                 {"id":"account-0004","name":"Example Closed Savings","balance":0,"closed":true,"deleted":false}
               ]},
              {"id":"budget-0002","name":"Example Summer House","currency_format":{"iso_code":"EUR"},"accounts":[]}
            ]}}
        """
    }
}
