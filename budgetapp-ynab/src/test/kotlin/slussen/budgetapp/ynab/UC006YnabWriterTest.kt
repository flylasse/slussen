package slussen.budgetapp.ynab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.budgetapp.ynab.YnabWriteExamples.ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.ACCOUNTS_ANSWER
import slussen.budgetapp.ynab.YnabWriteExamples.AMOUNT_T
import slussen.budgetapp.ynab.YnabWriteExamples.BUDGET
import slussen.budgetapp.ynab.YnabWriteExamples.CLOSED_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.DELETED_BUDGET_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.ID_A
import slussen.budgetapp.ynab.YnabWriteExamples.ID_B
import slussen.budgetapp.ynab.YnabWriteExamples.ID_C
import slussen.budgetapp.ynab.YnabWriteExamples.ID_T
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_A
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_B
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_C
import slussen.budgetapp.ynab.YnabWriteExamples.ITEM_T
import slussen.budgetapp.ynab.YnabWriteExamples.MISSING_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.NO_PAYEE_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.OTHER_ACCOUNT
import slussen.budgetapp.ynab.YnabWriteExamples.REFUSAL
import slussen.budgetapp.ynab.YnabWriteExamples.REF_A
import slussen.budgetapp.ynab.YnabWriteExamples.REF_B
import slussen.budgetapp.ynab.YnabWriteExamples.REF_C
import slussen.budgetapp.ynab.YnabWriteExamples.REF_COUNTERPART
import slussen.budgetapp.ynab.YnabWriteExamples.REF_T
import slussen.budgetapp.ynab.YnabWriteExamples.TOKEN
import slussen.budgetapp.ynab.YnabWriteExamples.TRANSFER_PAYEE
import slussen.budgetapp.ynab.YnabWriteExamples.error
import slussen.budgetapp.ynab.YnabWriteExamples.listCreated
import slussen.budgetapp.ynab.YnabWriteExamples.received
import slussen.budgetapp.ynab.YnabWriteExamples.saved
import slussen.budgetapp.ynab.YnabWriteExamples.singleCreated
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.BudgetAppWriter
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.budgetapp.ItemOutcome.AlreadyPresent
import slussen.pluginapi.budgetapp.ItemOutcome.Created
import slussen.pluginapi.budgetapp.ItemOutcome.Rejected
import slussen.pluginapi.budgetapp.ItemOutcome.Unknown
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransactionLookup
import slussen.pluginapi.budgetapp.TransferLink
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.testfixtures.RecordingRequestGate
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.testfixtures.WrittenLog
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.Secret
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The YNAB writer of ADR-001 §4.2.4 and §4.2.5 against a local test server with invented answers: each request's
 * method, path, headers and body, each answer's one outcome, the resend one by one after a refused list (Q-08), the
 * linked transfer through the other Budget Account's transfer payee, marking Cleared, and every way a request can get
 * no answer.
 */
class UC006YnabWriterTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val log = WrittenLog()

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    /** A new test server with nothing queued, for the next case of a test that tries several. */
    private fun fresh() {
        server.shutdown()
        server = MockWebServer().apply { start() }
    }

    private fun writer(): BudgetAppWriter = YnabBudgetApp(log, server.url("/v1/"), http).writer

    private fun context(gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5)) =
        TestRequestContext(gate, timeout, Secret(TOKEN))

    private fun create(items: List<CreateItem>, gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5)): CreateResult =
        runBlocking(Dispatchers.Default) { writer().create(BUDGET, items, context(gate, timeout)) }

    private fun clear(gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5)): ClearResult =
        runBlocking(Dispatchers.Default) { writer().markCleared(BUDGET, REF_COUNTERPART, context(gate, timeout)) }

    private fun answered(vararg outcomes: ItemOutcome) = CreateResult.Answered(outcomes.toList())

    private fun enqueue(vararg answers: MockResponse) = answers.forEach(server::enqueue)

    private fun status(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private val never get() = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

    private val dropped get() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    private fun listBodyOf(vararg transactions: String) = """{"transactions":[${transactions.joinToString(",")}]}"""

    private fun singleBodyOf(transaction: String) = """{"transaction":$transaction}"""

    private val bodyA = """{"account_id":"$ACCOUNT","date":"2026-10-14","amount":-731942,"payee_name":"Planted Payee Juniper Mill",""" +
        """"memo":"Planted memo violet anchor","cleared":"cleared","approved":false,"import_id":"$ID_A"}"""
    private val bodyB = """{"account_id":"$ACCOUNT","date":"2026-10-15","amount":58217,"payee_name":null,"memo":null,""" +
        """"cleared":"uncleared","approved":false,"import_id":"$ID_B"}"""
    private val bodyC = """{"account_id":"$ACCOUNT","date":"2026-10-15","amount":-26453,"payee_name":"Planted Payee Copper Kettle",""" +
        """"memo":"Planted memo amber gate","cleared":"cleared","approved":false,"import_id":"$ID_C"}"""
    private val bodyT = """{"account_id":"$ACCOUNT","date":"2026-10-14","amount":-415009,"payee_id":"$TRANSFER_PAYEE",""" +
        """"memo":"Planted memo amber gate","cleared":"cleared","approved":false,"import_id":"$ID_T"}"""

    private fun assertRequest(request: RecordedRequest, method: String, path: String, body: String?) {
        assertEquals(method, request.method)
        assertEquals(path, request.requestUrl!!.encodedPath)
        assertEquals(null, request.requestUrl!!.query, "no query")
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        if (body != null) {
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    // --- Creating a list -------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-008"], requirements = ["C-009"])
    @DisplayName("BR-008, ADR-001 §4.2.5 Creating a list: the items of one Budget Account go in one POST of a transactions array, byte for byte: Budget Account, date, amount in milliunits, payee or null, memo or null, cleared as the item says, approved false, the Import Identifier; admitted by the gate as a write")
    fun listIsSentByteForByte() {
        enqueue(listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))))
        val gate = RecordingRequestGate()
        val result = create(listOf(ITEM_A, ITEM_B), gate)
        assertEquals(answered(Created(REF_A), Created(REF_B)), result)
        val requests = server.received()
        assertEquals(1, requests.size)
        assertRequest(requests.single(), "POST", "/v1/budgets/$BUDGET/transactions", listBodyOf(bodyA, bodyB))
        assertEquals(listOf(RequestSummary(RequestKind.BudgetAppWrite, null, null, unattended = false)), gate.requests)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-006", "BR-007"])
    @DisplayName("ADR-001 §4.2.5: a 201 is read item by item by Import Identifier: Created with the saved transaction's id in the item's Budget Account, AlreadyPresent(null) among the duplicates, Unknown for an item the answer names in another Budget Account or not at all")
    fun listAnswerIsReadItemByItem() {
        enqueue(listCreated(listOf(saved(REF_B, ID_B), saved(REF_A, ID_A, account = OTHER_ACCOUNT)), duplicates = listOf(ID_C)))
        val fourth = ITEM_C.copy(importIdentifier = "SLS1-pl4ntedimportidentifier0009")
        assertEquals(answered(Unknown, Created(REF_B), AlreadyPresent(null), Unknown), create(listOf(ITEM_A, ITEM_B, ITEM_C, fourth)))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5: two items with the same Import Identifier, or an item both saved and named a duplicate, cannot be told apart and are Unknown")
    fun ambiguousAnswerIsUnknown() {
        enqueue(listCreated(listOf(saved(REF_A, ID_A)), duplicates = listOf(ID_A)))
        assertEquals(answered(Unknown, Unknown), create(listOf(ITEM_A, ITEM_A.copy(date = ITEM_B.date))))
        enqueue(listCreated(listOf(saved(REF_A, ID_A)), duplicates = listOf(ID_A)))
        assertEquals(answered(Unknown), create(listOf(ITEM_A)))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("A8: a 201 that cannot be read, a 200, and every other answer to a list (429, 500, 503, a redirect, 422) make every item Unknown, and nothing is sent again")
    fun otherListAnswersAreUnknown() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            listOf(
                status(201, """{"data":{"transactions":[{"id":"tx-1"}]}}"""),
                status(201, "not json"),
                status(200, """{"data":{"transaction_ids":[],"transactions":[${saved(REF_A, ID_A)}],"duplicate_import_ids":[],"server_knowledge":1}}"""),
                error(429, "429", "too_many_requests"),
                error(500, "500", "internal_server_error"),
                error(503, "503", "service_unavailable"),
                status(302).setHeader("Location", elsewhere.url("/v1/budgets/$BUDGET/transactions").toString()),
                status(307).setHeader("Location", elsewhere.url("/v1/budgets/$BUDGET/transactions").toString()),
                error(422, "422", "unprocessable"),
            ).forEach { answer ->
                fresh()
                enqueue(answer)
                val before = server.requestCount
                assertEquals(answered(Unknown), create(listOf(ITEM_A)), "for $answer")
                assertEquals(before + 1, server.requestCount, "for $answer")
            }
            assertEquals(0, elsewhere.requestCount, "a redirect was followed")
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.2.5 Refusals: a 401 and a 403 with code 403.3 to a list of which nothing was written are CredentialsRefused, and nothing more is sent")
    fun refusedTokenToTheListIsCredentialsRefused() {
        listOf(status(401, """{"error":{"id":"401","name":"not_authorized","detail":"x"}}"""), status(401), error(403, "403.3", "unauthorized_scope")).forEach { answer ->
            enqueue(answer)
            val before = server.requestCount
            assertEquals(CreateResult.CredentialsRefused, create(listOf(ITEM_A, ITEM_B)))
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario")
    @DisplayName("ADR-001 §4.2.4: an empty call sends nothing and answers no outcome")
    fun emptyCallSendsNothing() {
        assertEquals(answered(), create(emptyList()))
        assertEquals(0, server.requestCount)
    }

    // --- What is sent: amounts, Import Identifiers, shortening -------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions", requirements = ["C-009"])
    @DisplayName("C-009: an amount is sent exactly as milliunits up to the largest whole number every reader of the answer holds exactly (RFC 8259 §6); a larger one is Rejected before sending and never rounded, and the other items are sent")
    fun amountIsExactOrRefused() {
        val largest = ITEM_B.copy(amount = Amount(9_007_199_254_740_991))
        val smallest = ITEM_C.copy(amount = Amount(-9_007_199_254_740_991))
        val tooLarge = ITEM_A.copy(amount = Amount(9_007_199_254_740_992))
        val lowest = ITEM_A.copy(importIdentifier = "SLS1-pl4ntedimportidentifier0008", amount = Amount(Long.MIN_VALUE))
        enqueue(listCreated(listOf(saved(REF_B, ID_B), saved(REF_C, ID_C))))
        val result = create(listOf(tooLarge, largest, lowest, smallest)) as CreateResult.Answered
        assertTrue(result.outcomes[0] is Rejected && result.outcomes[2] is Rejected, "${result.outcomes}")
        assertEquals(listOf(Created(REF_B), Created(REF_C)), listOf(result.outcomes[1], result.outcomes[3]))
        val body = server.received().single().body.readUtf8()
        assertTrue(""""amount":9007199254740991,""" in body && """"amount":-9007199254740991,""" in body, body)
        assertFalse(ID_A in body, "a refused item was sent")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions", businessRules = ["BR-007"])
    @DisplayName("BR-007: an Import Identifier is sent exactly as handed, never shortened or altered; one longer than YNAB's 36 characters or blank is Rejected before sending")
    fun importIdentifierIsSentUnchanged() {
        val odd = "SLS1-Ab+/=:._~ \"é\\x-0123456789abcd"
        val longest = "SLS1-" + "z".repeat(31)
        enqueue(listCreated(listOf(saved(REF_A, odd.replace("\\", "\\\\").replace("\"", "\\\"")), saved(REF_B, longest))))
        val result = create(
            listOf(
                ITEM_A.copy(importIdentifier = odd),
                ITEM_B.copy(importIdentifier = longest),
                ITEM_C.copy(importIdentifier = longest + "z"),
                ITEM_C.copy(importIdentifier = "  "),
            ),
        ) as CreateResult.Answered
        assertEquals(listOf(Created(REF_A), Created(REF_B)), result.outcomes.take(2))
        assertTrue(result.outcomes.drop(2).all { it is Rejected }, "${result.outcomes}")
        val body = server.received().single().body.readUtf8()
        assertTrue(""""import_id":"SLS1-Ab+/=:._~ \"é\\x-0123456789abcd"""" in body, body)
        assertTrue(""""import_id":"$longest"}""" in body, body)
        assertFalse(longest + "z" in body)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-015"], requirements = ["C-009"])
    @DisplayName("BR-015: a payee longer than 200 and a memo longer than 500 UTF-16 code units are cut at a code point boundary, never inside a surrogate pair; shorter ones are sent unchanged")
    fun payeeAndMemoAreShortened() {
        val payee = "p".repeat(199) + "😀" + "tail"
        val memo = "m".repeat(500) + "rest"
        val exact = "e".repeat(198) + "😀"
        enqueue(listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))))
        create(listOf(ITEM_A.copy(payee = payee, memo = memo), ITEM_B.copy(payee = exact)))
        val body = server.received().single().body.readUtf8()
        assertTrue(""""payee_name":"${"p".repeat(199)}","memo":"${"m".repeat(500)}",""" in body, body)
        assertTrue(""""payee_name":"$exact",""" in body, body)
    }

    // --- A linked transfer -----------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009, ADR-001 §4.2.5 The transfer payee: a call holding a linked transfer first reads the Budget Accounts once, as a read, then sends the leg with the other Budget Account's transfer payee as payee_id and no payee_name, ordinary items beside it")
    fun linkedTransferGoesThroughTheTransferPayee() {
        enqueue(
            MockResponse().setBody(ACCOUNTS_ANSWER),
            listCreated(listOf(saved(REF_T, ID_T, amount = AMOUNT_T, transferAccount = OTHER_ACCOUNT, transferTransaction = REF_COUNTERPART), saved(REF_A, ID_A))),
        )
        val gate = RecordingRequestGate()
        assertEquals(answered(Created(REF_T), Created(REF_A)), create(listOf(ITEM_T, ITEM_A), gate))
        val (accounts, list) = server.received()
        assertRequest(accounts, "GET", "/v1/budgets/$BUDGET/accounts", null)
        assertRequest(list, "POST", "/v1/budgets/$BUDGET/transactions", listBodyOf(bodyT, bodyA))
        assertEquals(listOf(RequestKind.BudgetAppRead, RequestKind.BudgetAppWrite), gate.requests.map { it.kind })
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A10: Budget App Cannot Link a Transfer")
    @DisplayName("ADR-001 §4.2.2, §4.2.5: a linked transfer whose other Budget Account is missing, deleted, closed, without a transfer payee, or the item's own is Rejected with that reason and not sent; the other items are sent")
    fun unlinkableTransferIsRejectedBeforeSending() {
        val others = listOf(MISSING_ACCOUNT, DELETED_BUDGET_ACCOUNT, CLOSED_ACCOUNT, NO_PAYEE_ACCOUNT, ACCOUNT)
        enqueue(MockResponse().setBody(ACCOUNTS_ANSWER), listCreated(listOf(saved(REF_A, ID_A))))
        val items = others.mapIndexed { n, other -> ITEM_T.copy(importIdentifier = "SLS1-pl4ntedimportidentifier00${50 + n}", transferBudgetAccountReference = other) }
        val result = create(items + ITEM_A) as CreateResult.Answered
        assertTrue(result.outcomes.take(others.size).all { it is Rejected && it.reason.isNotBlank() }, "${result.outcomes}")
        assertEquals(Created(REF_A), result.outcomes.last())
        val (_, list) = server.received()
        assertEquals(listBodyOf(bodyA), list.body.readUtf8())
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5: when the read of the Budget Accounts gets no usable answer, every linked item is Unknown and not sent, and the other items are sent")
    fun failedAccountsReadLeavesLinkedItemsUnknown() {
        listOf(error(503, "503", "service_unavailable"), status(200, "not json"), error(403, "403.1", "subscription_lapsed"), dropped).forEach { answer ->
            fresh()
            enqueue(answer, listCreated(listOf(saved(REF_A, ID_A))))
            assertEquals(answered(Unknown, Created(REF_A)), create(listOf(ITEM_T, ITEM_A)), "for $answer")
            val requests = server.received()
            assertEquals(2, requests.size, "the read is sent once, then the list: for $answer")
            assertEquals(listBodyOf(bodyA), requests[1].body.readUtf8())
        }
        enqueue(error(503, "503", "service_unavailable"))
        assertEquals(answered(Unknown), create(listOf(ITEM_T)))
        assertEquals(1, server.received().size, "with no other item, no list is sent")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.2.5: a refusal of the token to the read of the Budget Accounts is CredentialsRefused and a refusal by the gate StoppedByGate, with nothing written")
    fun refusedAccountsReadWritesNothing() {
        enqueue(status(401))
        assertEquals(CreateResult.CredentialsRefused, create(listOf(ITEM_T, ITEM_A)))
        enqueue(error(403, "403.3", "unauthorized_scope"))
        assertEquals(CreateResult.CredentialsRefused, create(listOf(ITEM_T, ITEM_A)))
        assertEquals(2, server.requestCount)
        assertEquals(CreateResult.StoppedByGate(RefusalReason.OperationEnded), create(listOf(ITEM_T, ITEM_A), refusingFrom(1)))
        assertEquals(2, server.requestCount)
    }

    // --- A refused list, sent again one by one (Q-08) ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("A9, Q-08: to a list refused with 400, 404, 409, 403.1, 403.2, 403.4, 403.5 or an unreadable 403, each item is sent alone, in order, in one POST of a transaction object, byte for byte, and only the one YNAB refuses by itself is Rejected with its reason")
    fun refusedListIsSentAgainOneByOne() {
        listOf(
            error(400, "400", "bad_request"),
            error(404, "404.2", "resource_not_found"),
            error(409, "409", "conflict"),
            error(403, "403.1", "subscription_lapsed"),
            error(403, "403.2", "trial_expired"),
            error(403, "403.4", "data_limit_reached"),
            error(403, "403.5", "email_unconfirmed"),
            status(403, "not json"),
        ).forEach { refusal ->
            fresh()
            enqueue(refusal, singleCreated(saved(REF_A, ID_A)), error(400, "400", "bad_request"), singleCreated(saved(REF_C, ID_C)))
            val gate = RecordingRequestGate()
            assertEquals(answered(Created(REF_A), Rejected(REFUSAL), Created(REF_C)), create(listOf(ITEM_A, ITEM_B, ITEM_C), gate), "for $refusal")
            val requests = server.received()
            assertEquals(4, requests.size, "one list and one single request per item: for $refusal")
            assertEquals(listBodyOf(bodyA, bodyB, bodyC), requests[0].body.readUtf8())
            listOf(bodyA, bodyB, bodyC).forEachIndexed { n, body -> assertRequest(requests[n + 1], "POST", "/v1/budgets/$BUDGET/transactions", singleBodyOf(body)) }
            assertEquals(List(4) { RequestKind.BudgetAppWrite }, gate.requests.map { it.kind }, "every request admitted by the gate as a write")
        }
    }

    /**
     * A list of [ITEM_A], [ITEM_B] and [ITEM_C] refused with 400; [ITEM_A] alone is created, [ITEM_B] alone gets
     * [answer], and [ITEM_C] alone would be created. Returns the result and how many requests were sent.
     */
    private fun resendWhereTheSecondGets(answer: MockResponse, gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5)): Pair<CreateResult, Int> {
        fresh()
        enqueue(error(400, "400", "bad_request"), singleCreated(saved(REF_A, ID_A)), answer, singleCreated(saved(REF_C, ID_C)))
        val result = create(listOf(ITEM_A, ITEM_B, ITEM_C), gate, timeout)
        Thread.sleep(SETTLE_MILLIS)
        return result to server.requestCount
    }

    private fun assertGoesOn(answer: MockResponse, outcome: ItemOutcome) {
        assertEquals(answered(Created(REF_A), outcome, Created(REF_C)) to 4, resendWhereTheSecondGets(answer))
    }

    private fun assertEndsTheSending(answer: MockResponse, gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5)) {
        assertEquals(answered(Created(REF_A), Unknown, Unknown) to 3, resendWhereTheSecondGets(answer, gate, timeout))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Q-08: to a single send, a 201 carrying the item's own Import Identifier in its Budget Account is Created with its id")
    fun singleSendCreated() = assertGoesOn(singleCreated(saved(REF_B, ID_B)), Created(REF_B))

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("W4-0 review item 8: to a single send, a 201 carrying another Import Identifier, none, or another Budget Account is Unknown, and the sending goes on")
    fun singleSendCreatedWithAnotherImportIdentifier() {
        assertGoesOn(singleCreated(saved(REF_B, ID_C)), Unknown)
        server.received()
        assertGoesOn(singleCreated(saved(REF_B, null)), Unknown)
        server.received()
        assertGoesOn(singleCreated(saved(REF_B, ID_B, account = OTHER_ACCOUNT)), Unknown)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-015"])
    @DisplayName("Q-08: to a single send, 409 (the Budget Account already holds the Import Identifier) is AlreadyPresent(null)")
    fun singleSendDuplicate() = assertGoesOn(error(409, "409", "conflict"), AlreadyPresent(null))

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("A9: to a single send, 400 is Rejected with YNAB's error.detail")
    fun singleSendRefused() = assertGoesOn(error(400, "400", "bad_request"), Rejected(REFUSAL))

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("A9: to a single send, a 403 that is not a refusal of the token (403.1, 403.2, 403.4, 403.5) is Rejected with YNAB's error.detail")
    fun singleSendRefusedByTheBudgetApp() {
        listOf("403.1" to "subscription_lapsed", "403.2" to "trial_expired", "403.4" to "data_limit_reached", "403.5" to "email_unconfirmed").forEach { (id, name) ->
            assertGoesOn(error(403, id, name), Rejected(REFUSAL))
            server.received()
        }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("A9: to a single send, a 400 or a 403 whose detail cannot be read is Rejected with a reason that says so")
    fun singleSendRefusedWithoutReadableReason() {
        val (result, sent) = resendWhereTheSecondGets(status(403, "not json"))
        assertEquals(4, sent)
        val outcome = (result as CreateResult.Answered).outcomes[1]
        assertTrue(outcome is Rejected && outcome.reason.isNotBlank(), "was $outcome")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.4, §4.2.5: to a single send, 404 is Unknown, never Rejected, since the Budget Account may not be in the Budget named; the sending goes on")
    fun singleSendNotFound() = assertGoesOn(error(404, "404.2", "resource_not_found"), Unknown)

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5: a refusal of the token (401) to a single send ends the sending: that item and every item not sent yet are Unknown")
    fun singleSendTokenMissing() = assertEndsTheSending(status(401))

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5: a 403.3 (unauthorized_scope) to a single send ends the sending")
    fun singleSendTokenScopeRefused() = assertEndsTheSending(error(403, "403.3", "unauthorized_scope"))

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5 Rate limit: a 429 to a single send, YNAB's rate limit reached part-way, ends the sending and nothing is retried")
    fun singleSendRateLimited() = assertEndsTheSending(error(429, "429", "too_many_requests"))

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5: a 500 or a 503 to a single send ends the sending")
    fun singleSendServerError() {
        assertEndsTheSending(error(500, "500", "internal_server_error"))
        server.received()
        assertEndsTheSending(error(503, "503", "service_unavailable"))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.5: a 201 to a single send that cannot be read ends the sending")
    fun singleSendUnreadable() = assertEndsTheSending(status(201, """{"data":{"transaction":{"id":"""))

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("E3 review item 5: a redirect to a single send is not followed and ends the sending; another status (422) ends it too")
    fun singleSendRedirected() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            assertEndsTheSending(status(307).setHeader("Location", elsewhere.url("/v1/budgets/$BUDGET/transactions").toString()))
            assertEquals(0, elsewhere.requestCount, "the redirect was followed")
        } finally {
            elsewhere.shutdown()
        }
        server.received()
        assertEndsTheSending(error(422, "422", "unprocessable"))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-016"])
    @DisplayName("NFR-016: a single send never answered ends within the request timeout, and that item and the rest are Unknown")
    fun singleSendTimesOut() = assertEndsTheSending(never, timeout = Duration.ofMillis(700))

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-004"])
    @DisplayName("ADR-001 §4.0: a single send whose connection is lost after it arrived ends the sending, and neither the part nor OkHttp sends it again")
    fun singleSendConnectionLost() = assertEndsTheSending(dropped)

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.2.4: a refusal by the gate of a single send, after the list was sent, ends the sending with Unknown, never StoppedByGate")
    fun singleSendStoppedByGate() {
        enqueue(error(400, "400", "bad_request"), singleCreated(saved(REF_A, ID_A)), singleCreated(saved(REF_B, ID_B)))
        assertEquals(answered(Created(REF_A), Unknown, Unknown), create(listOf(ITEM_A, ITEM_B, ITEM_C), refusingFrom(3)))
        assertEquals(2, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.0: a cancellation while a single send waits for its answer ends the sending; that item and the rest are Unknown, and the call is cancelled")
    fun singleSendCancelledAfterItLeft() {
        enqueue(error(400, "400", "bad_request"), singleCreated(saved(REF_A, ID_A)), never)
        val result = cancelledWhen({ server.requestCount >= 3 }) { writer().create(BUDGET, listOf(ITEM_A, ITEM_B, ITEM_C), context(timeout = Duration.ofSeconds(30))) }
        assertEquals(answered(Created(REF_A), Unknown, Unknown), result)
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(3, server.requestCount)
        runBlocking { withTimeout(4_000) { while (http.dispatcher.runningCallsCount() > 0) delay(10) } }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("ADR-001 §4.0: a cancellation before a single send left sends nothing more; that item and the rest are Unknown")
    fun singleSendCancelledBeforeItLeft() {
        enqueue(error(400, "400", "bad_request"), singleCreated(saved(REF_A, ID_A)), singleCreated(saved(REF_B, ID_B)))
        val gate = HoldingFrom(3)
        val result = cancelledWhen({ gate.holding }) { writer().create(BUDGET, listOf(ITEM_A, ITEM_B, ITEM_C), context(gate)) }
        assertEquals(answered(Created(REF_A), Unknown, Unknown), result)
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(2, server.requestCount)
    }

    // --- A list that gets no answer -------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-004"])
    @DisplayName("ADR-001 §4.0: a list whose connection the server drops after receiving it is Unknown for every item and sent once, also on a connection OkHttp kept from the read before it")
    fun droppedListIsSentOnce() {
        enqueue(dropped, listCreated(listOf(saved(REF_A, ID_A), saved(REF_B, ID_B))))
        assertEquals(answered(Unknown, Unknown), create(listOf(ITEM_A, ITEM_B)))
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, server.requestCount, "the list was sent again")
        server.received()
        enqueue(MockResponse().setBody(ACCOUNTS_ANSWER), dropped, listCreated(listOf(saved(REF_T, ID_T), saved(REF_A, ID_A))))
        assertEquals(answered(Unknown, Unknown), create(listOf(ITEM_T, ITEM_A)))
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(3, server.requestCount, "the list was sent again on a new connection")
        fresh()
        enqueue(listCreated(listOf(saved(REF_A, ID_A))), dropped, listCreated(listOf(saved(REF_B, ID_B))))
        assertEquals(answered(Created(REF_A)), create(listOf(ITEM_A)))
        assertEquals(answered(Unknown), create(listOf(ITEM_B)), "the list was sent again on the connection OkHttp kept")
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(2, server.requestCount, "the list was sent again on the connection OkHttp kept")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-016"])
    @DisplayName("NFR-016: a list never answered is Unknown for every item within the request timeout and sent once")
    fun unansweredListIsUnknown() {
        enqueue(never, listCreated(listOf(saved(REF_A, ID_A))))
        assertEquals(answered(Unknown), create(listOf(ITEM_A), timeout = Duration.ofMillis(700)))
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.0: a list cancelled while it waits for the gate sends nothing; one cancelled after it left is Unknown and not sent again")
    fun cancelledList() {
        val gate = HoldingFrom(1)
        assertEquals(answered(Unknown, Unknown), cancelledWhen({ gate.holding }) { writer().create(BUDGET, listOf(ITEM_A, ITEM_B), context(gate)) })
        assertEquals(0, server.requestCount)
        enqueue(never)
        assertEquals(answered(Unknown), cancelledWhen({ server.requestCount >= 1 }) { writer().create(BUDGET, listOf(ITEM_A), context(timeout = Duration.ofSeconds(30))) })
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.2.4: a list refused by the gate, with nothing written before, is StoppedByGate with the gate's reason, and nothing is sent")
    fun gateRefusedList() {
        assertEquals(CreateResult.StoppedByGate(RefusalReason.OperationEnded), create(listOf(ITEM_A), refusingFrom(1)))
        enqueue(MockResponse().setBody(ACCOUNTS_ANSWER))
        assertEquals(CreateResult.StoppedByGate(RefusalReason.OperationEnded), create(listOf(ITEM_T, ITEM_A), refusingFrom(2)))
        assertEquals(1, server.requestCount, "only the read of the Budget Accounts was sent")
    }

    // --- Marking Cleared -------------------------------------------------------------------------------------------

    private fun clearedAnswer(cleared: String, reference: String = REF_COUNTERPART) =
        MockResponse().setBody("""{"data":{"transaction_ids":["$reference"],"transactions":[${saved(reference, null, cleared = cleared)}],"server_knowledge":95}}""")

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-009", "BR-011"])
    @DisplayName("BR-011, ADR-001 §4.2.5 Marking Cleared: one PATCH with one transaction holding only id and cleared, byte for byte, admitted as a write; 200 with it cleared or reconciled is Cleared")
    fun counterpartIsMarkedCleared() {
        enqueue(clearedAnswer("cleared"), clearedAnswer("reconciled"))
        val gate = RecordingRequestGate()
        assertEquals(ClearResult.Cleared, clear(gate))
        assertEquals(ClearResult.Cleared, clear())
        val request = server.received().first()
        assertRequest(request, "PATCH", "/v1/budgets/$BUDGET/transactions", """{"transactions":[{"id":"$REF_COUNTERPART","cleared":"cleared"}]}""")
        assertEquals(listOf(RequestKind.BudgetAppWrite), gate.requests.map { it.kind })
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A11: Marking a Transfer Counterpart Cleared Fails")
    @DisplayName("A11: 200 with the counterpart uncleared or without it, 400, 404, 401 and every 403 are NotCleared; a refusal by the gate and a cancellation before it left are NotCleared and send nothing")
    fun refusedClearingIsNotCleared() {
        listOf(
            clearedAnswer("uncleared"), clearedAnswer("cleared", reference = "tx-other"),
            MockResponse().setBody("""{"data":{"transaction_ids":[],"transactions":[],"server_knowledge":1}}"""),
            error(400, "400", "bad_request"), error(404, "404.2", "resource_not_found"), status(401),
            error(403, "403.1", "subscription_lapsed"), error(403, "403.3", "unauthorized_scope"), status(403, "not json"),
        ).forEach { answer ->
            fresh()
            enqueue(answer)
            assertEquals(ClearResult.NotCleared, clear(), "for $answer")
        }
        val sent = server.requestCount
        assertEquals(ClearResult.NotCleared, clear(refusingFrom(1)))
        val gate = HoldingFrom(1)
        assertEquals(ClearResult.NotCleared, cancelledWhen({ gate.holding }) { writer().markCleared(BUDGET, REF_COUNTERPART, context(gate)) })
        assertEquals(sent, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A11: Marking a Transfer Counterpart Cleared Fails", requirements = ["NFR-016"])
    @DisplayName("A11: every other answer to a clearing (429, 500, a redirect, a 200 that cannot be read), a timeout, a lost connection and a cancellation after it left are Unknown, each sent once")
    fun unsettledClearingIsUnknown() {
        listOf(error(429, "429", "too_many_requests"), error(500, "500", "internal_server_error"), status(302).setHeader("Location", "http://elsewhere.invalid/"), status(200, "not json"), dropped).forEach { answer ->
            fresh()
            enqueue(answer)
            val before = server.requestCount
            assertEquals(ClearResult.Unknown, clear(), "for $answer")
            Thread.sleep(SETTLE_MILLIS)
            assertEquals(before + 1, server.requestCount, "for $answer")
        }
        enqueue(never)
        assertEquals(ClearResult.Unknown, clear(timeout = Duration.ofMillis(700)))
        enqueue(never)
        val before = server.requestCount
        assertEquals(ClearResult.Unknown, cancelledWhen({ server.requestCount > before }) { writer().markCleared(BUDGET, REF_COUNTERPART, context(timeout = Duration.ofSeconds(30))) })
    }

    // --- Reading one transaction -----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009, ADR-001 §4.2.5 Reading one transaction: one GET of the transaction by its id, as a read; the leg comes back with its Import Identifier and its link to the counterpart")
    fun oneTransactionIsReadByItsId() {
        enqueue(MockResponse().setBody("""{"data":{"transaction":${saved(REF_T, ID_T, amount = AMOUNT_T, cleared = "reconciled", transferAccount = OTHER_ACCOUNT, transferTransaction = REF_COUNTERPART)},"server_knowledge":94}}"""))
        val gate = RecordingRequestGate()
        val result = runBlocking { YnabBudgetApp(log, server.url("/v1/"), http).reader.readTransaction(BUDGET, REF_T, context(gate)) }
        val expected = BudgetAppTransactionRead(
            REF_T, ACCOUNT, ITEM_T.date, Amount(AMOUNT_T), YnabWriteExamples.PAYEE_A, YnabWriteExamples.MEMO_A, ID_T, EntryKind.Imported,
            TransferLink(REF_COUNTERPART, OTHER_ACCOUNT), ReadState.Present, cleared = true, approved = false,
        )
        assertEquals(ReadResult.Answered(TransactionLookup.Found(expected)), result)
        assertRequest(server.received().single(), "GET", "/v1/budgets/$BUDGET/transactions/$REF_T", null)
        assertEquals(listOf(RequestKind.BudgetAppRead), gate.requests.map { it.kind })
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("ADR-001 §4.2.5: a deleted transaction reads as Deleted; 404 is NotFound; 401 and 403.3 refused credentials; 503 and 429 ServiceUnavailable; an answer that cannot be read UnreadableAnswer")
    fun oneTransactionAnswers() {
        val reader = YnabBudgetApp(log, server.url("/v1/"), http).reader
        fun read(answer: MockResponse): ReadResult<TransactionLookup> {
            server.enqueue(answer)
            return runBlocking { reader.readTransaction(BUDGET, REF_T, context()) }
        }
        val deleted = read(MockResponse().setBody("""{"data":{"transaction":${saved(REF_T, ID_T, deleted = true)},"server_knowledge":1}}"""))
        assertEquals(ReadState.Deleted, ((deleted as ReadResult.Answered).value as TransactionLookup.Found).transaction.state)
        assertEquals(ReadResult.Answered(TransactionLookup.NotFound), read(error(404, "404.2", "resource_not_found")))
        assertEquals(ReadResult.CredentialsRefused, read(status(401)))
        assertEquals(ReadResult.CredentialsRefused, read(error(403, "403.3", "unauthorized_scope")))
        assertEquals(ReadResult.TransientFailure(FailureKind.ServiceUnavailable), read(error(503, "503", "service_unavailable")))
        assertEquals(ReadResult.TransientFailure(FailureKind.ServiceUnavailable), read(error(429, "429", "too_many_requests")))
        assertEquals(ReadResult.TransientFailure(FailureKind.UnreadableAnswer), read(MockResponse().setBody("""{"data":{}}""")))
    }

    // --- Nothing sensitive leaves ----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("ADR-006 §2, NFR-008: with Detailed Logging off and on, no log line and no toString() of an item or an outcome shows the token, a payee, a memo, an amount, a Budget Account's name, an Import Identifier, a reference or YNAB's reason, through a list, a resend, a transfer, a rejection, a clearing and a read")
    fun noLogLineShowsAPlantedValue() {
        val shown = mutableListOf<String>()
        listOf(false, true).forEach { detailed ->
            log.detailed = detailed
            enqueue(MockResponse().setBody(ACCOUNTS_ANSWER), error(400, "400", "bad_request"), singleCreated(saved(REF_T, ID_T)), error(400, "400", "bad_request", REFUSAL), error(409, "409", "conflict"))
            val result = create(listOf(ITEM_T, ITEM_A, ITEM_B, ITEM_T.copy(transferBudgetAccountReference = CLOSED_ACCOUNT, importIdentifier = ID_C)))
            shown += result.toString()
            shown += (result as CreateResult.Answered).outcomes.map { it.toString() }
            shown += listOf(ITEM_T, ITEM_A, ITEM_B).map { it.toString() }
            enqueue(listCreated(listOf(saved(REF_A, ID_A)), duplicates = listOf(ID_B)), never)
            shown += create(listOf(ITEM_A, ITEM_B)).toString()
            shown += create(listOf(ITEM_C), timeout = Duration.ofMillis(500)).toString()
            enqueue(clearedAnswer("cleared"))
            shown += clear().toString()
            enqueue(MockResponse().setBody("""{"data":{"transaction":${saved(REF_T, ID_T)},"server_knowledge":1}}"""))
            runBlocking { YnabBudgetApp(log, server.url("/v1/"), http).reader.readTransaction(BUDGET, REF_T, context()) }
        }
        shown += log.written
        assertTrue(log.written.isNotEmpty(), "the writer logs what it did")
        YnabWriteExamples.planted.forEach { planted ->
            shown.forEach { text -> assertFalse(planted in text, "a log line or toString() shows a planted value: $text") }
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    /** A gate that admits the requests before the [first]th and refuses that one and every later one. */
    private fun refusingFrom(first: Int): RequestGate {
        val asked = AtomicInteger()
        return RequestGate {
            if (asked.incrementAndGet() >= first) Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded) else Admission.Allowed
        }
    }

    /** A gate that admits the requests before the [first]th and holds that one until its caller is cancelled. */
    private class HoldingFrom(private val first: Int) : RequestGate {
        private val asked = AtomicInteger()

        @Volatile
        var holding: Boolean = false

        override suspend fun admit(request: RequestSummary): Admission {
            if (asked.incrementAndGet() < first) return Admission.Allowed
            holding = true
            awaitCancellation()
        }
    }

    /** Runs [call], cancels it once [started] is true, and returns what it returned. */
    private fun <T> cancelledWhen(started: () -> Boolean, call: suspend () -> T): T? {
        var returned: T? = null
        runBlocking(Dispatchers.Default) {
            val running = launch { returned = call() }
            withTimeout(4_000) { while (!started()) delay(10) }
            running.cancelAndJoin()
        }
        return returned
    }

    private companion object {
        /** How long a test waits after a result for a request that would be sent again. */
        const val SETTLE_MILLIS = 300L
    }
}
