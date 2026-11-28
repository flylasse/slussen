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
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.ItemOutcome
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
import slussen.budgetapp.ynab.YnabWriteExamples.ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.AMOUNT_T
import slussen.budgetapp.ynab.YnabWriteExamples.BUDGET
import slussen.budgetapp.ynab.YnabWriteExamples.ID_A
import slussen.budgetapp.ynab.YnabWriteExamples.ID_B
import slussen.budgetapp.ynab.YnabWriteExamples.ID_T
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_A
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_B
import slussen.budgetapp.ynab.YnabWriteExamples.MEMO_A
import slussen.budgetapp.ynab.YnabWriteExamples.OTHER_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.PAYEE_A
import slussen.budgetapp.ynab.YnabWriteExamples.REF_A
import slussen.budgetapp.ynab.YnabWriteExamples.REF_B
import slussen.budgetapp.ynab.YnabWriteExamples.REF_COUNTERPART
import slussen.budgetapp.ynab.YnabWriteExamples.REF_T
import slussen.budgetapp.ynab.YnabWriteExamples.approvedFlagsOf
import slussen.budgetapp.ynab.YnabWriteExamples.error
import slussen.budgetapp.ynab.YnabWriteExamples.listCreated
import slussen.budgetapp.ynab.YnabWriteExamples.received
import slussen.budgetapp.ynab.YnabWriteExamples.saved
import slussen.pluginapi.testfixtures.BudgetAppContractTest
import slussen.pluginapi.testfixtures.ChangesScenario
import slussen.pluginapi.testfixtures.ClearScenario
import slussen.pluginapi.testfixtures.CredentialScenario
import slussen.pluginapi.testfixtures.DeviceRefusal
import slussen.pluginapi.testfixtures.LookupScenario
import slussen.pluginapi.testfixtures.WriteScenario
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

    private fun writing(vararg answers: MockResponse, items: List<CreateItem> = listOf(ITEM_A, ITEM_B)): WriteScenario {
        answers.forEach(server::enqueue)
        return WriteScenario(app().writer, Secret(PLANTED_TOKEN), BUDGET, items)
    }

    override fun createAnswered() = writing(listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))))

    override fun expectedCreated(): List<ItemOutcome> = listOf(ItemOutcome.Created(REF_A), ItemOutcome.Created(REF_B))

    override fun createWithHeldImportIdentifier() = writing(listCreated(listOf(saved(REF_A, ID_A)), duplicates = listOf(ID_B)))

    override fun expectedWithHeldImportIdentifier(): List<ItemOutcome> = listOf(ItemOutcome.Created(REF_A), ItemOutcome.AlreadyPresent(null))

    override fun createCredentialsRefused() = writing(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun createNeverAnswering() = writing(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    /** The second answer would create both items, so a list sent again by OkHttp or the part would show as Created. */
    override fun createConnectionLostAfterSending() = writing(
        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))),
    )

    override fun createOnRefusingDevice(threads: RecordingRequestThreads) =
        WriteScenario(refusingDevice(threads).writer, Secret(PLANTED_TOKEN), BUDGET, listOf(ITEM_A, ITEM_B))

    override fun sentApproved(): List<Boolean> = approvedFlagsOf(server.received())

    private fun clearing(answer: MockResponse): ClearScenario {
        server.enqueue(answer)
        return ClearScenario(app().writer, Secret(PLANTED_TOKEN), BUDGET, REF_COUNTERPART)
    }

    override fun clearAnswered() = clearing(MockResponse().setBody("""{"data":{"transaction_ids":["$REF_COUNTERPART"],"transactions":[${saved(REF_COUNTERPART, null, cleared = "cleared")}],"server_knowledge":93}}"""))

    override fun clearRefused() = clearing(error(400, "400", "bad_request"))

    override fun clearNeverAnswering() = clearing(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    private fun lookingUp(answer: MockResponse): LookupScenario {
        server.enqueue(answer)
        return LookupScenario(app().reader, Secret(PLANTED_TOKEN), BUDGET, REF_T)
    }

    override fun lookupFound() = lookingUp(
        MockResponse().setBody("""{"data":{"transaction":${saved(REF_T, ID_T, amount = AMOUNT_T, cleared = "cleared", transferAccount = OTHER_ACCOUNT, transferTransaction = REF_COUNTERPART)},"server_knowledge":94}}"""),
    )

    override fun expectedLookup() = BudgetAppTransactionRead(
        REF_T, ACCOUNT, YnabWriteExamples.DATE_A, Amount(AMOUNT_T), PAYEE_A, MEMO_A, ID_T, EntryKind.Imported,
        TransferLink(REF_COUNTERPART, OTHER_ACCOUNT), ReadState.Present, cleared = true, approved = false,
    )

    override fun lookupNotFound() = lookingUp(error(404, "404.2", "resource_not_found"))

    override fun lookupCredentialRefused() = lookingUp(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun lookupNeverAnswering() = lookingUp(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun plantedWriteValues(): List<String> = YnabWriteExamples.planted

    /** The documented refusals that are not of the access token (ADR-001 §4.2.5 Refusals), and a 403 whose code cannot be read. */
    private val refusalsNotOfTheToken: List<MockResponse>
        get() = listOf(
            error(403, "403.1", "subscription_lapsed"),
            error(403, "403.2", "trial_expired"),
            error(403, "403.4", "data_limit_reached"),
            error(403, "403.5", "email_unconfirmed"),
            MockResponse().setResponseCode(403).setBody("not json"),
        )

    @Test
    @UseCase(id = "UC-003", scenario = "A2: Budget App Refuses Slussen's Credentials")
    @DisplayName("ADR-001 §4.2.5 Refusals: a 401 and a 403 with YNAB's code 403.3 (unauthorized_scope) are refused credentials for a read of the Budgets and of changes")
    fun refusalsOfTheTokenAreRefusedCredentials() {
        listOf(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED), error(403, "403.3", "unauthorized_scope")).forEach { answer ->
            server.enqueue(answer)
            assertEquals(ReadResult.CredentialsRefused, runBlocking { app().reader.readBudgets(TestRequestContext(credential = Secret(PLANTED_TOKEN))) })
        }
        server.enqueue(error(403, "403.3", "unauthorized_scope"))
        assertEquals(ReadResult.CredentialsRefused, runBlocking { app().reader.readChanges("budget-0001", null, FROM, TestRequestContext(credential = Secret(PLANTED_TOKEN))) })
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read")
    @DisplayName("W4-0 review item 5, ADR-001 §4.2.5 Refusals: a lapsed subscription, an expired trial, a data limit, an unconfirmed address and a 403 whose code cannot be read are TransientFailure(ServiceUnavailable) of a read, never refused credentials")
    fun otherRefusalsOfAReadAreServiceUnavailable() {
        refusalsNotOfTheToken.forEach { answer ->
            server.enqueue(answer)
            assertEquals(
                ReadResult.TransientFailure(FailureKind.ServiceUnavailable),
                runBlocking { app().reader.readBudgets(TestRequestContext(credential = Secret(PLANTED_TOKEN))) },
            )
        }
        refusalsNotOfTheToken.forEach { answer ->
            server.enqueue(answer)
            assertEquals(
                ReadResult.TransientFailure(FailureKind.ServiceUnavailable),
                runBlocking { app().reader.readChanges("budget-0001", null, FROM, TestRequestContext(credential = Secret(PLANTED_TOKEN))) },
            )
        }
        refusalsNotOfTheToken.forEach { answer ->
            server.enqueue(answer)
            assertEquals(
                ReadResult.TransientFailure(FailureKind.ServiceUnavailable),
                runBlocking { app().reader.readTransaction(BUDGET, REF_T, TestRequestContext(credential = Secret(PLANTED_TOKEN))) },
            )
        }
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A2: Service Cannot Be Reached")
    @DisplayName("W4-0 review item 5: a token check YNAB answers with a refusal that is not of the token (403.1, 403.2, 403.4, 403.5, an unreadable 403) is a TransientFailure(ServiceUnavailable), not Refused; 403.3 is Refused")
    fun otherRefusalsOfATokenCheckAreNotRefusedCredentials() {
        refusalsNotOfTheToken.forEach { answer ->
            server.enqueue(answer)
            assertEquals(CredentialCheck.TransientFailure(FailureKind.ServiceUnavailable), runBlocking { app().credentials.check(tokenInput(), TestRequestContext()) })
        }
        server.enqueue(error(403, "403.3", "unauthorized_scope"))
        assertEquals(CredentialCheck.Refused, runBlocking { app().credentials.check(tokenInput(), TestRequestContext()) })
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read")
    @DisplayName("E3 review item 5, ADR-001 §4.0: a read answered with a redirect does not follow it and is an unreadable answer; a read whose connection is lost is sent once")
    fun readFollowsNoRedirectAndIsSentOnce() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            elsewhere.enqueue(MockResponse().setBody(BUDGETS_ANSWER))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/v1/budgets").toString()))
            assertEquals(
                ReadResult.TransientFailure(FailureKind.UnreadableAnswer),
                runBlocking { app().reader.readBudgets(TestRequestContext(credential = Secret(PLANTED_TOKEN))) },
            )
            assertEquals(0, elsewhere.requestCount, "the redirect's target received a request")
        } finally {
            elsewhere.shutdown()
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(BUDGETS_ANSWER))
        val before = server.requestCount
        assertEquals(
            ReadResult.TransientFailure(FailureKind.ConnectionLost),
            runBlocking { app().reader.readBudgets(TestRequestContext(credential = Secret(PLANTED_TOKEN))) },
        )
        Thread.sleep(300)
        assertEquals(before + 1, server.requestCount, "the read was sent again by the HTTP library")
    }

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
