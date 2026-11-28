package slussen.provider.enablebanking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.BankOutcome
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.provider.UnreadableReason
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.testfixtures.DeviceRefusal
import slussen.pluginapi.testfixtures.RecordingRequestGate
import slussen.pluginapi.testfixtures.RecordingRequestThreads
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.provider.enablebanking.FetchServer.Companion.balance
import slussen.provider.enablebanking.FetchServer.Companion.entry
import slussen.provider.enablebanking.FetchServer.Companion.error
import slussen.provider.enablebanking.FetchServer.Companion.ok
import java.net.InetAddress
import java.net.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enable Banking's Fetch (UC-001, ADR-001 §4.1.1 "Fetching", "The balance", "Transactions", "Errors in a Fetch", "The
 * Operator's presence") against a local test server with invented answers: what is sent for each Bank and Bank Account,
 * each rule of the mapping with the field present and missing or malformed, paging, each error code with its one
 * outcome, and the headers of the Operator's presence.
 */
@DisplayName("UC-001: Fetch Transactions, Enable Banking's part")
class UC001EnableBankingFetchTest {
    private val server = FetchServer()
    private val now = Instant.parse("2026-11-28T10:00:00Z")
    private val log = CapturingLog()
    private val provider = EnableBankingDataProvider(log, server.url(), OkHttpClient(), Clock.fixed(now, ZoneOffset.UTC), language = { "fi-FI" })

    private val from = LocalDate.of(2026, 10, 29)
    private val to = LocalDate.of(2026, 11, 28)

    /** Aurora Sparbank requires only a header Slussen can give truly; Kuusi Pankki requires the device's address too. */
    private val aurora = BankSessionValue(
        SESSION_AURORA, AURORA, listOf("Psu-User-Agent"),
        mapOf(HOUSEHOLD to UID_HOUSEHOLD, SAVINGS to UID_SAVINGS, CARD to UID_CARD),
        mapOf(HOUSEHOLD to "EUR", SAVINGS to "EUR"),
    )
    private val kuusi = BankSessionValue(SESSION_KUUSI, KUUSI, listOf("Psu-Ip-Address", "Psu-User-Agent"), mapOf(KUUSI_CURRENT to UID_KUUSI), mapOf(KUUSI_CURRENT to "EUR"))
    private val vesta = BankSessionValue(SESSION_VESTA, VESTA, emptyList(), mapOf(VESTA_CURRENT to UID_VESTA), mapOf(VESTA_CURRENT to "SEK"))

    @AfterEach
    fun stopServer() = server.shutdown()

    private fun request(
        vararg read: BankAccountToRead,
        presence: OperatorPresence = OperatorPresence.OperatorPresent,
        sessions: Map<String, ConsentSession> = mapOf(AURORA to aurora.toSession(), KUUSI to kuusi.toSession(), VESTA to vesta.toSession()),
        periodFrom: LocalDate = from,
        periodTo: LocalDate = to,
    ) = FetchRequest(BankAccountSelection.Selected(read.toList()), periodFrom, periodTo, presence, sessions)

    private fun both(reference: String) = BankAccountToRead(reference, ReadKind.TransactionsAndBalance)

    private fun transactionsOnly(reference: String) = BankAccountToRead(reference, ReadKind.Transactions)

    private fun fetch(request: FetchRequest, gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5)): FetchResponse =
        runBlocking(Dispatchers.Default) { provider.fetch(request, TestRequestContext(gate, timeout, ConsentServer.credential())) }

    private fun FetchResponse.of(reference: String): DeliveredBankAccount = bankAccounts.single { it.providerReference == reference }

    private fun FetchResponse.items(reference: String): List<ProviderTransaction> =
        (of(reference).result as BankAccountResult.Read).transactions

    /** The one transaction [entry] becomes, read as the only item of the household's only page. */
    private fun mapped(entry: JsonElement): ProviderTransaction {
        server.page(UID_HOUSEHOLD, null, listOf(entry))
        return fetch(request(transactionsOnly(HOUSEHOLD))).items(HOUSEHOLD).single()
    }

    private fun booked(date: String = "2026-11-20", thousandths: Long, payee: String? = "Harbor Pharmacy", memo: String? = "Card purchase 4411") =
        ProviderTransaction.Interpretable(null, LocalDate.parse(date), Amount(thousandths), EUR, payee, memo, booked = true)

    // ---- Fetching: what is sent --------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("Step 4 (S-B-47): nothing at all is sent when no Bank Account is selected, also when every Bank Account is asked for identification")
    fun nothingIsSentWithoutASelectedBankAccount() {
        val gate = RecordingRequestGate()
        val none = fetch(request(), gate)
        val all = runBlocking {
            provider.fetch(
                FetchRequest(BankAccountSelection.AllForIdentification(ReadKind.Balance), from, to, OperatorPresence.OperatorPresent, mapOf(AURORA to aurora.toSession())),
                TestRequestContext(gate, credential = ConsentServer.credential()),
            )
        }
        assertEquals(FetchResponse(SourceOutcome.Completed, emptyList(), emptyList()), none)
        assertEquals(FetchResponse(SourceOutcome.Completed, emptyList(), emptyList()), all)
        assertEquals(0, server.received.size)
        assertEquals(emptyList<RequestSummary>(), gate.requests)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-007", "BR-009", "BR-012"])
    @DisplayName("Steps 4-5 (S-B-47, S-B-46): for a selected Bank Account the balances and the transactions of the fetch period are asked by its session's uid, each admitted by the gate first with its kind, Bank and Bank Account, no session, details or status filter asked, and the reference that lasts is delivered")
    fun selectedBankAccountIsReadByItsUid() {
        server.balances(UID_HOUSEHOLD, balance("ITBD", "1520.75", lastChange = "2026-11-28T08:15:00.000000+00:00"))
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        val gate = RecordingRequestGate()

        val response = fetch(request(both(HOUSEHOLD)), gate)

        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        assertEquals(emptyList<BankOutcome>(), response.bankOutcomes)
        val delivered = response.of(HOUSEHOLD)
        assertEquals(AURORA, delivered.bankReference)
        assertEquals(CurrencyCode("EUR"), delivered.currency)
        assertEquals(
            BankAccountResult.Read(listOf(booked(thousandths = -12_500)), Amount(1_520_750), Instant.parse("2026-11-28T08:15:00Z")),
            delivered.result,
        )
        val (balances, transactions) = server.received.toList()
        assertEquals("/accounts/$UID_HOUSEHOLD/balances", balances.requestUrl!!.encodedPath)
        assertEquals("/accounts/$UID_HOUSEHOLD/transactions", transactions.requestUrl!!.encodedPath)
        assertEquals(setOf("date_from", "date_to"), transactions.requestUrl!!.queryParameterNames)
        assertEquals("2026-10-29", transactions.requestUrl!!.queryParameter("date_from"))
        assertEquals("2026-11-28", transactions.requestUrl!!.queryParameter("date_to"))
        assertTrue(server.received.all { it.method == "GET" && it.getHeader("Authorization")!!.startsWith("Bearer ") })
        assertEquals(
            listOf(
                RequestSummary(RequestKind.Balance, AURORA, HOUSEHOLD, unattended = false),
                RequestSummary(RequestKind.Transactions, AURORA, HOUSEHOLD, unattended = false),
            ),
            gate.requests,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"])
    @DisplayName("A4, BR-008 (S-B-47): a selected Bank Account no Bank Session holds, as for a Bank without one, is Failed(UnreadableAnswer) and nothing is sent for it; the others are read")
    fun bankAccountWithoutSessionIsNotContacted() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        val gate = RecordingRequestGate()

        val response = fetch(request(transactionsOnly(KUUSI_CURRENT), transactionsOnly(HOUSEHOLD), sessions = mapOf(AURORA to aurora.toSession())), gate)

        assertEquals(BankAccountResult.Failed(FailureKind.UnreadableAnswer), response.of(KUUSI_CURRENT).result)
        assertTrue(response.of(HOUSEHOLD).result is BankAccountResult.Read)
        assertTrue(gate.requests.none { it.bankAccountReference == KUUSI_CURRENT })
        assertTrue(server.requestsOf(UID_KUUSI).isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account")
    @DisplayName("E3 review: a Bank Account delivered without a uid, or whose uid did not fit the Bank Session, is Failed(UnreadableAnswer) without a request")
    fun bankAccountWithoutUidIsNotRead() {
        val response = fetch(request(transactionsOnly("PLANTED-HASH-WITHOUT-UID")))
        assertEquals(BankAccountResult.Failed(FailureKind.UnreadableAnswer), response.of("PLANTED-HASH-WITHOUT-UID").result)
        assertEquals(0, server.received.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: a Bank Account read for its balance only gets no transactions request; one read for its transactions only gets no balances request")
    fun onlyWhatIsAskedIsRead() {
        server.balances(UID_HOUSEHOLD, balance("ITBD", "10.00"))
        server.page(UID_SAVINGS, null, listOf(entry()))
        val response = fetch(request(BankAccountToRead(HOUSEHOLD, ReadKind.Balance), transactionsOnly(SAVINGS)))
        assertEquals(BankAccountResult.Read(emptyList(), Amount(10_000), null), response.of(HOUSEHOLD).result)
        assertEquals(null, (response.of(SAVINGS).result as BankAccountResult.Read).balance)
        assertEquals(listOf("balances"), server.requestsOf(UID_HOUSEHOLD).map { it.requestUrl!!.pathSegments[2] })
        assertEquals(listOf("transactions"), server.requestsOf(UID_SAVINGS).map { it.requestUrl!!.pathSegments[2] })
    }

    // ---- The balance -------------------------------------------------------------------------------------------------

    private fun balanceOf(vararg entries: JsonElement, reference: String = HOUSEHOLD): BankAccountResult {
        server.balances(UID_HOUSEHOLD, *entries)
        server.balances(UID_SAVINGS, *entries)
        return fetch(request(BankAccountToRead(reference, ReadKind.Balance))).of(reference).result
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8 (S-B-47, risk R5): the Account Balance is the interim booked balance, else the closing booked one, never an available, expected, opening or other balance")
    fun bookedBalanceIsChosen() {
        assertEquals(
            Amount(100_000),
            (balanceOf(balance("ITAV", "900.00"), balance("XPCD", "800.00"), balance("CLBD", "50.00"), balance("ITBD", "100.00")) as BankAccountResult.Read).balance,
        )
        assertEquals(
            Amount(50_000),
            (balanceOf(balance("ITAV", "900.00"), balance("OPBD", "1.00"), balance("CLBD", "50.00")) as BankAccountResult.Read).balance,
        )
        assertEquals(
            null,
            (balanceOf(balance("ITAV", "900.00"), balance("XPCD", "800.00"), balance("OPBD", "1.00"), balance("CLAV", "2.00"), balance("INFO", "3.00"), balance("PRCD", "4.00"), balance("OTHR", "5.00")) as BankAccountResult.Read).balance,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A21: Balance in Another Currency")
    @DisplayName("Step 8 (S-B-47): of several of the chosen type, the one in the Bank Account's currency; none when not exactly one is, and a minus sign is kept as money owed")
    fun balanceInTheBankAccountsCurrency() {
        assertEquals(Amount(-25_100), (balanceOf(balance("ITBD", "99.00", "SEK"), balance("ITBD", "-25.10", "EUR")) as BankAccountResult.Read).balance)
        assertEquals(null, (balanceOf(balance("ITBD", "99.00", "SEK")) as BankAccountResult.Read).balance)
        assertEquals(null, (balanceOf(balance("ITBD", "1.00"), balance("ITBD", "2.00")) as BankAccountResult.Read).balance)
        assertEquals(null, (balanceOf(balance("ITBD", "1.0001")) as BankAccountResult.Read).balance)
        assertEquals(null, (balanceOf(balance("ITBD", "one")) as BankAccountResult.Read).balance)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8 (S-T-04): the time reported is last_change_date_time, else the start of reference_date in UTC, else none")
    fun balanceTimeIsReported() {
        fun reportedAt(vararg entries: JsonElement) = (balanceOf(*entries) as BankAccountResult.Read).balanceReportedAt
        assertEquals(Instant.parse("2026-11-27T21:03:00Z"), reportedAt(balance("ITBD", "1.00", lastChange = "2026-11-28T00:03:00+03:00", referenceDate = "2026-11-20")))
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), reportedAt(balance("ITBD", "1.00", referenceDate = "2026-11-27")))
        assertNull(reportedAt(balance("ITBD", "1.00")))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", requirements = ["C-006"])
    @DisplayName("Step 8 (S-B-47, G-39): a Bank Account the Data Provider calls a card, or one of unknown currency, gets no balance, and no balances request is spent on it")
    fun cardGetsNoBalance() {
        server.page(UID_CARD, null, listOf(entry()))
        val response = fetch(request(both(CARD)))
        assertEquals(null, (response.of(CARD).result as BankAccountResult.Read).balance)
        assertEquals(listOf("transactions"), server.requestsOf(UID_CARD).map { it.requestUrl!!.pathSegments[2] })
    }

    // ---- Transactions ------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002 (S-B-47): booked only for BOOK; PDNG, HOLD, SCHD, CNCL and RJCT are passed on as not booked; OTHR or no status is Uninterpretable with booked unknown")
    fun bookedOnlyForBook() {
        assertEquals(true, (mapped(entry(status = "BOOK")) as ProviderTransaction.Interpretable).booked)
        listOf("PDNG", "HOLD", "SCHD", "CNCL", "RJCT").forEach { status ->
            assertEquals(false, (mapped(entry(status = status)) as ProviderTransaction.Interpretable).booked, status)
        }
        listOf("OTHR", null).forEach { status ->
            val item = mapped(entry(status = status)) as ProviderTransaction.Uninterpretable
            assertEquals(null, item.booked, "$status")
            assertEquals(UnreadableReason.UnreadableStructure, item.reason, "$status")
        }
        val pendingWithoutDate = mapped(entry(status = "PDNG", bookingDate = null)) as ProviderTransaction.Uninterpretable
        assertEquals(false, pendingWithoutDate.booked)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("BR-016 (S-B-47): the booking date only, never the value or transaction date; missing is MissingDate, not a date is UnreadableDate")
    fun bookingDateOnly() {
        assertEquals(LocalDate.of(2026, 11, 20), (mapped(entry(bookingDate = "2026-11-20")) as ProviderTransaction.Interpretable).bookedDate)
        val missing = mapped(entry(bookingDate = null)) as ProviderTransaction.Uninterpretable
        assertEquals(UnreadableReason.MissingDate, missing.reason)
        assertNull(missing.bookedDate)
        listOf("2026-11-31", "20.11.2026", "2026-11-20T00:00:00Z", "").forEach { date ->
            assertEquals(UnreadableReason.UnreadableDate, (mapped(entry(bookingDate = date)) as ProviderTransaction.Uninterpretable).reason, date)
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("BR-016 (S-B-47, W6-0 item 6): the amount unsigned with its indicator, CRDT an inflow and DBIT an outflow; a minus sign whatever the indicator, no indicator, more decimals than thousandths or no number is UnreadableAmount; missing is MissingAmount")
    fun amountWithItsIndicator() {
        assertEquals(Amount(1_234_567), (mapped(entry(amount = "1234.567", indicator = "CRDT")) as ProviderTransaction.Interpretable).amount)
        assertEquals(Amount(-40_000), (mapped(entry(amount = "40", indicator = "DBIT")) as ProviderTransaction.Interpretable).amount)
        assertEquals(Amount(0), (mapped(entry(amount = "0.00", indicator = "CRDT")) as ProviderTransaction.Interpretable).amount)
        listOf(
            "-12.50" to "DBIT", "-12.50" to "CRDT", "12.50" to null, "12.50" to "OTHR", "12.5001" to "DBIT", "twelve" to "DBIT",
            "+12.50" to "DBIT", " 12.50" to "DBIT", "1e3" to "DBIT", "12." to "DBIT", ".5" to "DBIT", "" to "DBIT", "99999999999999999999" to "CRDT",
        ).forEach { (amount, indicator) ->
            val item = mapped(entry(amount = amount, indicator = indicator)) as ProviderTransaction.Uninterpretable
            assertEquals(UnreadableReason.UnreadableAmount, item.reason, "$amount $indicator")
            assertNull(item.amount, "$amount $indicator")
        }
        assertEquals(UnreadableReason.MissingAmount, (mapped(entry(amount = null)) as ProviderTransaction.Uninterpretable).reason)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("BR-016 (S-B-47): the currency of the transaction amount; missing is MissingCurrency, and the date and amount that could be read are kept")
    fun currencyOfTheAmount() {
        assertEquals(CurrencyCode("SEK"), (mapped(entry(currency = "SEK")) as ProviderTransaction.Interpretable).currency)
        val missing = mapped(entry(currency = null)) as ProviderTransaction.Uninterpretable
        assertEquals(UnreadableReason.MissingCurrency, missing.reason)
        assertEquals(LocalDate.of(2026, 11, 20), missing.bookedDate)
        assertEquals(Amount(-12_500), missing.amount)
        assertEquals(UnreadableReason.UnreadableStructure, (mapped(entry(currency = "eur")) as ProviderTransaction.Uninterpretable).reason)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016 (S-B-47): the payee is the creditor's name of an outflow and the debtor's name of an inflow, exactly as delivered, never the other party's; none when missing")
    fun payeeIsTheOtherParty() {
        assertEquals("  Harbor  Pharmacy ", (mapped(entry(indicator = "DBIT", creditor = "  Harbor  Pharmacy ", debtor = "Holder")) as ProviderTransaction.Interpretable).payee)
        assertEquals("Employer Oy", (mapped(entry(indicator = "CRDT", creditor = "Holder", debtor = "Employer Oy")) as ProviderTransaction.Interpretable).payee)
        assertNull((mapped(entry(indicator = "DBIT", creditor = null, debtor = "Holder")) as ProviderTransaction.Interpretable).payee)
        assertNull((mapped(entry(indicator = "CRDT", creditor = "Holder", debtor = null)) as ProviderTransaction.Interpretable).payee)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016 (S-B-47): the memo is the remittance lines in their order, joined by one space, each exactly as delivered, permanent once sent; none when there is no line")
    fun memoIsTheRemittanceLines() {
        fun memo(lines: List<String>?) = (mapped(entry(remittance = lines)) as ProviderTransaction.Interpretable).memo
        // The examples of the rule (ADR-001 §4.1.1 "memo"): one line as it is; two lines with one space between them;
        // spaces inside or at the ends of a line kept, never trimmed or joined otherwise; an empty line kept as one.
        assertEquals("Card purchase 4411", memo(listOf("Card purchase 4411")))
        assertEquals("Invoice 2026-118 Ref 7781", memo(listOf("Invoice 2026-118", "Ref 7781")))
        assertEquals(" Rent  November  /Unit 4 ", memo(listOf(" Rent  November ", "/Unit 4 ")))
        assertEquals("first  third", memo(listOf("first", "", "third")))
        assertEquals("Ref,7781;x", memo(listOf("Ref,7781;x")))
        assertNull(memo(emptyList()))
        assertNull(memo(null))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("Q-05 (S-B-47, issue #7): no Stable Reference, also when the entry gives an entry_reference")
    fun noStableReference() {
        assertNull((mapped(entry(entryReference = "PLANTED-ENTRY-REFERENCE-9")) as ProviderTransaction.Interpretable).stableReference)
        assertNull((mapped(entry(entryReference = null)) as ProviderTransaction.Interpretable).stableReference)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("BR-016 (ADR-001 §4.4): an entry that is no object, or whose payee or memo is not text, is Uninterpretable(UnreadableStructure), never completed or dropped")
    fun malformedEntryIsKept() {
        assertEquals(UnreadableReason.UnreadableStructure, (mapped(JsonPrimitive("not an entry")) as ProviderTransaction.Uninterpretable).reason)
        val numberAsLine = buildJsonObject {
            putJsonObject("transaction_amount") { put("amount", "1.00"); put("currency", "EUR") }
            put("credit_debit_indicator", "CRDT")
            put("status", "BOOK")
            put("booking_date", "2026-11-20")
            put("remittance_information", buildJsonArray { add(JsonPrimitive(7)) })
        }
        assertEquals(UnreadableReason.UnreadableStructure, (mapped(numberAsLine) as ProviderTransaction.Uninterpretable).reason)
        val payeeNotText = buildJsonObject {
            putJsonObject("transaction_amount") { put("amount", "1.00"); put("currency", "EUR") }
            put("credit_debit_indicator", "CRDT")
            put("status", "BOOK")
            put("booking_date", "2026-11-20")
            putJsonObject("debtor") { putJsonObject("name") { put("first", "x") } }
        }
        assertEquals(UnreadableReason.UnreadableStructure, (mapped(payeeNotText) as ProviderTransaction.Uninterpretable).reason)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"])
    @DisplayName("ADR-001 §4.4: every entry is passed on in its order, pending and zero-amount ones included; filtering is the core's")
    fun everyEntryIsPassedOn() {
        server.page(UID_HOUSEHOLD, null, listOf(entry(), entry(status = "PDNG"), entry(amount = "0.00"), entry(bookingDate = null)))
        val items = fetch(request(transactionsOnly(HOUSEHOLD))).items(HOUSEHOLD)
        assertEquals(4, items.size)
        assertEquals(false, (items[1] as ProviderTransaction.Interpretable).booked)
        assertEquals(Amount(0), (items[2] as ProviderTransaction.Interpretable).amount)
        assertEquals(UnreadableReason.MissingDate, (items[3] as ProviderTransaction.Uninterpretable).reason)
    }

    // ---- Paging ------------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-007", "BR-012"])
    @DisplayName("S-B-47: each further page is asked with its continuation key and the same other parameters, through the gate as kind Other, also after an empty page that carries a key")
    fun pagesAreFollowed() {
        server.page(UID_HOUSEHOLD, null, listOf(entry(amount = "1.00")), next = "PLANTED-KEY-A")
        server.page(UID_HOUSEHOLD, "PLANTED-KEY-A", emptyList(), next = "PLANTED-KEY-B")
        server.page(UID_HOUSEHOLD, "PLANTED-KEY-B", listOf(entry(amount = "2.00")))
        val gate = RecordingRequestGate()

        val response = fetch(request(transactionsOnly(HOUSEHOLD)), gate)

        assertEquals(listOf(Amount(-1_000), Amount(-2_000)), response.items(HOUSEHOLD).map { (it as ProviderTransaction.Interpretable).amount })
        val pages = server.requestsOf(UID_HOUSEHOLD).map { it.requestUrl!! }
        assertEquals(listOf(null, "PLANTED-KEY-A", "PLANTED-KEY-B"), pages.map { it.queryParameter("continuation_key") })
        pages.forEach {
            assertEquals("2026-10-29", it.queryParameter("date_from"))
            assertEquals("2026-11-28", it.queryParameter("date_to"))
        }
        assertEquals(listOf(RequestKind.Transactions, RequestKind.Other, RequestKind.Other), gate.requests.map { it.kind })
        assertTrue(gate.requests.all { it.bankReference == AURORA && it.bankAccountReference == HOUSEHOLD })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("W6-0 item 5: a continuation key already used in the same read, as when the Bank returns the same page twice, ends it as Failed(Incomplete) with nothing of it; the next Bank Account is read")
    fun usedKeyEndsTheReadIncomplete() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()), next = "PLANTED-KEY-A")
        server.page(UID_HOUSEHOLD, "PLANTED-KEY-A", listOf(entry(amount = "3.00")), next = "PLANTED-KEY-B")
        server.page(UID_HOUSEHOLD, "PLANTED-KEY-B", listOf(entry(amount = "3.00")), next = "PLANTED-KEY-A")
        server.page(UID_SAVINGS, null, listOf(entry()))

        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)))

        assertEquals(BankAccountResult.Failed(FailureKind.Incomplete), response.of(HOUSEHOLD).result)
        assertEquals(3, server.requestsOf(UID_HOUSEHOLD).size, "the used key is not asked again")
        assertTrue(response.of(SAVINGS).result is BankAccountResult.Read)
        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account")
    @DisplayName("S-B-47: WRONG_CONTINUATION_KEY ends the read as Failed(Incomplete), and a key that is not text is an unreadable answer")
    fun wrongContinuationKey() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()), next = "PLANTED-KEY-A")
        server.page(UID_HOUSEHOLD, "PLANTED-KEY-A", error(422, "WRONG_CONTINUATION_KEY"))
        server.page(UID_SAVINGS, null, ok("""{"transactions":[],"continuation_key":7}"""))
        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)))
        assertEquals(BankAccountResult.Failed(FailureKind.Incomplete), response.of(HOUSEHOLD).result)
        assertEquals(BankAccountResult.Failed(FailureKind.UnreadableAnswer), response.of(SAVINGS).result)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("ADR-001 §4.4: a page the gate refuses for that Bank Account ends it as Failed(Incomplete); the other Bank Accounts are read")
    fun refusedPageIsIncomplete() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()), next = "PLANTED-KEY-A")
        server.page(UID_SAVINGS, null, listOf(entry()))
        val gate = RefusingGate { it.kind == RequestKind.Other }

        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)), gate)

        assertEquals(BankAccountResult.Failed(FailureKind.Incomplete), response.of(HOUSEHOLD).result)
        assertTrue(response.of(SAVINGS).result is BankAccountResult.Read)
        assertEquals(1, server.requestsOf(UID_HOUSEHOLD).size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007"])
    @DisplayName("W6-0 item 5: a continuation that never ends is bounded by the Request Limit: the gate's refusal for the whole Data Provider ends that read as Failed(Incomplete) and sends nothing more for any Bank Account")
    fun endlessContinuationIsBoundedByTheRequestLimit() {
        repeat(30) { page -> server.page(UID_HOUSEHOLD, if (page == 0) null else "PLANTED-KEY-$page", listOf(entry()), next = "PLANTED-KEY-${page + 1}") }
        server.page(UID_SAVINGS, null, listOf(entry()))
        val admitted = AtomicInteger()
        val gate = RequestGate {
            if (admitted.incrementAndGet() > 5) Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached) else Admission.Allowed
        }

        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)), gate)

        assertEquals(SourceOutcome.StoppedByGate(RefusalReason.RequestLimitReached), response.sourceOutcome)
        assertEquals(BankAccountResult.Failed(FailureKind.Incomplete), response.of(HOUSEHOLD).result)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource), response.of(SAVINGS).result)
        assertEquals(5, server.received.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("S-B-47: the same history cut into pages differently gives the same transactions in the same order")
    fun sameHistoryInAnotherSplit() {
        val history = listOf(
            entry(amount = "4.00"), entry(amount = "4.00"), entry(amount = "9.10", indicator = "CRDT", debtor = "Employer Oy"),
            entry(bookingDate = "2026-11-21", remittance = listOf("Rent", "November")),
        )
        server.page(UID_HOUSEHOLD, null, history)
        server.page(UID_SAVINGS, null, history.take(1), next = "PLANTED-KEY-1")
        server.page(UID_SAVINGS, "PLANTED-KEY-1", emptyList(), next = "PLANTED-KEY-2")
        server.page(UID_SAVINGS, "PLANTED-KEY-2", history.drop(1).take(2), next = "PLANTED-KEY-3")
        server.page(UID_SAVINGS, "PLANTED-KEY-3", history.drop(3))

        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)))

        assertEquals(response.items(HOUSEHOLD), response.items(SAVINGS))
        assertEquals(4, response.items(SAVINGS).size)
    }

    // ---- Errors in a Fetch ---------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004", "BR-010"])
    @DisplayName("A5 (S-B-47): ASPSP_RATE_LIMIT_EXCEEDED is a request to wait of that Bank only, with no end; its other Bank Accounts are not read, another Bank's are")
    fun bankRateLimitIsABankBackOff() {
        server.page(UID_HOUSEHOLD, null, error(429, "ASPSP_RATE_LIMIT_EXCEEDED"))
        server.page(UID_KUUSI, null, listOf(entry()))

        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS), transactionsOnly(KUUSI_CURRENT)))

        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        assertEquals(listOf<BankOutcome>(BankOutcome.AskedToWait(AURORA, null)), response.bankOutcomes)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank), response.of(HOUSEHOLD).result)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank), response.of(SAVINGS).result)
        assertTrue(server.requestsOf(UID_SAVINGS).isEmpty())
        assertTrue(response.of(KUUSI_CURRENT).result is BankAccountResult.Read)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A9: Bank Consent Refused Before Its Expiry", businessRules = ["BR-010"])
    @DisplayName("A9 (S-B-47): an expired, revoked, closed or unknown session is a Bank Consent the Bank refused, for that Bank only")
    fun sessionErrorsAreAConsentRefused() {
        listOf("EXPIRED_SESSION", "REVOKED_SESSION", "CLOSED_SESSION", "SESSION_DOES_NOT_EXIST").forEach { code ->
            server.balances(UID_HOUSEHOLD, error(401, code))
            server.page(UID_KUUSI, null, listOf(entry()))
            val response = fetch(request(both(HOUSEHOLD), transactionsOnly(KUUSI_CURRENT)))
            assertEquals(listOf<BankOutcome>(BankOutcome.ConsentRefused(AURORA)), response.bankOutcomes, code)
            assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank), response.of(HOUSEHOLD).result, code)
            assertTrue(response.of(KUUSI_CURRENT).result is BankAccountResult.Read, code)
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials")
    @DisplayName("A7 (S-B-47): UNAUTHORIZED_ACCESS and AUTHORIZATION_NOT_PROVIDED refuse the credentials, and nothing more is sent")
    fun credentialsRefused() {
        listOf("UNAUTHORIZED_ACCESS", "AUTHORIZATION_NOT_PROVIDED").forEach { code ->
            server.received.clear()
            server.page(UID_HOUSEHOLD, null, error(401, code))
            val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)))
            assertEquals(SourceOutcome.CredentialsRefused, response.sourceOutcome, code)
            assertEquals(1, server.received.size, code)
        }
        val unreadable = runBlocking { provider.fetch(request(transactionsOnly(HOUSEHOLD)), TestRequestContext(credential = Secret("{}"))) }
        assertEquals(SourceOutcome.CredentialsRefused, unreadable.sourceOutcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-005", "BR-010"])
    @DisplayName("A8, BR-005 (S-B-47): ASPSP_ERROR and ASPSP_TIMEOUT fail that Bank Account as ServiceUnavailable; the PSU header codes and any other code as UnreadableAnswer; the others are read")
    fun bankErrorsFailOneBankAccount() {
        listOf(
            "ASPSP_ERROR" to FailureKind.ServiceUnavailable, "ASPSP_TIMEOUT" to FailureKind.ServiceUnavailable,
            "PSU_HEADER_NOT_PROVIDED" to FailureKind.UnreadableAnswer, "PSU_HEADER_INVALID" to FailureKind.UnreadableAnswer,
            "WRONG_REQUEST_PARAMETERS" to FailureKind.UnreadableAnswer,
        ).forEach { (code, kind) ->
            server.page(UID_HOUSEHOLD, null, error(400, code))
            server.page(UID_SAVINGS, null, listOf(entry()))
            val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)))
            assertEquals(BankAccountResult.Failed(kind), response.of(HOUSEHOLD).result, code)
            assertTrue(response.of(SAVINGS).result is BankAccountResult.Read, code)
            assertEquals(emptyList<BankOutcome>(), response.bankOutcomes)
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5 (S-B-47): another answer 429 asks the whole Data Provider to wait, with no end; nothing more is sent and what was read is kept")
    fun otherRateLimitIsASourceBackOff() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        server.page(UID_SAVINGS, null, MockResponse().setResponseCode(429))
        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS), transactionsOnly(KUUSI_CURRENT)))
        assertEquals(SourceOutcome.AskedToWait(null), response.sourceOutcome)
        assertTrue(response.of(HOUSEHOLD).result is BankAccountResult.Read)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource), response.of(SAVINGS).result)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource), response.of(KUUSI_CURRENT).result)
        assertTrue(server.requestsOf(UID_KUUSI).isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", businessRules = ["BR-005", "BR-010"])
    @DisplayName("A6 (S-B-55): a request with no answer, or the Data Provider's own failing, ends the Fetch for what is not read yet: those Bank Accounts are Failed with that reason and not contacted; what was read completely is delivered")
    fun failureInTransitEndsTheRestOnly() {
        listOf(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) to FailureKind.Timeout,
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) to FailureKind.ConnectionLost,
            MockResponse().setResponseCode(503).setBody("invented outage page") to FailureKind.ServiceUnavailable,
            MockResponse().setResponseCode(500).setBody("""{"message":"invented","error":null}""") to FailureKind.ServiceUnavailable,
        ).forEach { (failing, kind) ->
            server.received.clear()
            server.page(UID_HOUSEHOLD, null, listOf(entry()))
            server.page(UID_KUUSI, null, failing)
            server.page(UID_VESTA, null, listOf(entry(currency = "SEK")))

            val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(KUUSI_CURRENT), transactionsOnly(VESTA_CURRENT)), timeout = Duration.ofMillis(300))

            assertEquals(SourceOutcome.TransientFailure(kind), response.sourceOutcome, "$kind")
            assertTrue(response.of(HOUSEHOLD).result is BankAccountResult.Read, "$kind")
            assertEquals(BankAccountResult.Failed(kind), response.of(KUUSI_CURRENT).result, "$kind")
            assertEquals(BankAccountResult.Failed(kind), response.of(VESTA_CURRENT).result, "$kind")
            assertTrue(server.requestsOf(UID_VESTA).isEmpty(), "$kind")
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-005", "BR-010"])
    @DisplayName("A8 (S-B-55): a failure the Data Provider names by its code fails that Bank Account only, also with a status of the 500s, and the next Bank is read")
    fun namedFailureWithA500FailsOneBankAccount() {
        server.page(UID_KUUSI, null, error(500, "ASPSP_ERROR"))
        server.page(UID_VESTA, null, listOf(entry(currency = "SEK")))
        val response = fetch(request(transactionsOnly(KUUSI_CURRENT), transactionsOnly(VESTA_CURRENT)))
        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        assertEquals(BankAccountResult.Failed(FailureKind.ServiceUnavailable), response.of(KUUSI_CURRENT).result)
        assertTrue(response.of(VESTA_CURRENT).result is BankAccountResult.Read)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-009", "BR-010"])
    @DisplayName("A8, BR-009 (S-B-55): a Bank that does not give the fetch period asked for fails that Bank Account with that reason; nothing is asked again with another period, and the next Bank Account is read")
    fun periodNotGiven() {
        server.page(UID_HOUSEHOLD, null, error(422, "WRONG_TRANSACTIONS_PERIOD"))
        server.page(UID_SAVINGS, null, listOf(entry()))
        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)))
        assertEquals(BankAccountResult.Failed(FailureKind.PeriodNotGiven), response.of(HOUSEHOLD).result)
        assertEquals(1, server.requestsOf(UID_HOUSEHOLD).size)
        assertTrue(response.of(SAVINGS).result is BankAccountResult.Read)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("Step 8 (S-B-55): a failure of the balance request that concerns only the balance gives no Account Balance, and the transactions are still read")
    fun balanceFailureLeavesTheTransactions() {
        listOf(error(400, "ASPSP_ERROR"), error(400, "INVENTED_BALANCE_CODE"), MockResponse().setResponseCode(404), ok("not json")).forEach { failing ->
            server.balances(UID_HOUSEHOLD, failing)
            server.page(UID_HOUSEHOLD, null, listOf(entry()))
            val result = fetch(request(both(HOUSEHOLD))).of(HOUSEHOLD).result
            assertEquals(BankAccountResult.Read(listOf(booked(thousandths = -12_500)), null, null), result)
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5, A9 (S-B-55): a balance request the Bank answers with its rate limit, or with a session that is gone, keeps that meaning: nothing more is asked of that Bank")
    fun balanceAnswerOfTheBankKeepsItsMeaning() {
        server.balances(UID_HOUSEHOLD, error(429, "ASPSP_RATE_LIMIT_EXCEEDED"))
        val waiting = fetch(request(both(HOUSEHOLD)))
        assertEquals(listOf<BankOutcome>(BankOutcome.AskedToWait(AURORA, null)), waiting.bankOutcomes)
        assertEquals(1, server.requestsOf(UID_HOUSEHOLD).size)
        server.received.clear()
        server.balances(UID_HOUSEHOLD, error(401, "EXPIRED_SESSION"))
        val refused = fetch(request(both(HOUSEHOLD)))
        assertEquals(listOf<BankOutcome>(BankOutcome.ConsentRefused(AURORA)), refused.bankOutcomes)
        assertEquals(1, server.requestsOf(UID_HOUSEHOLD).size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5 (S-B-55): the status that asks to wait decides, whatever other code comes with it: the whole Data Provider waits and nothing more is sent")
    fun waitStatusDecides() {
        server.page(UID_HOUSEHOLD, null, error(429, "ASPSP_ERROR"))
        server.page(UID_KUUSI, null, listOf(entry()))
        val response = fetch(request(transactionsOnly(HOUSEHOLD), transactionsOnly(KUUSI_CURRENT)))
        assertEquals(SourceOutcome.AskedToWait(null), response.sourceOutcome)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource), response.of(KUUSI_CURRENT).result)
        assertTrue(server.requestsOf(UID_KUUSI).isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", requirements = ["NFR-016"])
    @DisplayName("ADR-001 §4.0: cancelling the caller cancels the call")
    fun cancellingCancelsTheCall() = runBlocking(Dispatchers.Default) {
        server.page(UID_HOUSEHOLD, null, MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient()
        val part = EnableBankingDataProvider(log, server.url(), http, Clock.fixed(now, ZoneOffset.UTC), language = { "fi-FI" })
        val running = async { part.fetch(request(transactionsOnly(HOUSEHOLD)), TestRequestContext(timeout = Duration.ofSeconds(30), credential = ConsentServer.credential())) }
        withTimeout(5_000) { while (http.dispatcher.runningCallsCount() == 0) delay(10) }
        running.cancel()
        withTimeout(5_000) { while (http.dispatcher.runningCallsCount() > 0) delay(10) }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", requirements = ["NFR-004"])
    @DisplayName("ADR-001 §4.0 (S-36): a Fetch the device refuses ends as a lost connection and leaves no uncaught exception on any thread")
    fun deviceRefusalIsALostConnection() {
        val threads = RecordingRequestThreads()
        try {
            val refusing = OkHttpClient.Builder()
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw DeviceRefusal.exception() })
                .dispatcher(Dispatcher(threads))
                .proxy(Proxy.NO_PROXY)
                .build()
            val part = EnableBankingDataProvider(log, "https://api.data-provider.invalid/".toHttpUrl(), refusing, Clock.fixed(now, ZoneOffset.UTC))
            val response = runBlocking { part.fetch(request(transactionsOnly(HOUSEHOLD)), TestRequestContext(credential = ConsentServer.credential())) }
            assertEquals(SourceOutcome.TransientFailure(FailureKind.ConnectionLost), response.sourceOutcome)
            threads.awaitIdle(Duration.ofSeconds(2))
            assertEquals(emptyList<Throwable>(), threads.uncaught)
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account")
    @DisplayName("E3 review item 5: a redirect is not followed; it is an unreadable answer for that Bank Account")
    fun redirectIsNotFollowed() {
        server.page(UID_HOUSEHOLD, null, MockResponse().setResponseCode(302).setHeader("Location", server.server.url("/accounts/$UID_SAVINGS/transactions").toString()))
        server.page(UID_SAVINGS, null, listOf(entry()))
        val response = fetch(request(transactionsOnly(HOUSEHOLD)))
        assertEquals(BankAccountResult.Failed(FailureKind.UnreadableAnswer), response.of(HOUSEHOLD).result)
        assertTrue(server.requestsOf(UID_SAVINGS).isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("ADR-001 §4.4: a refusal of the gate for one Bank stops that Bank only, and one for one Bank Account that Bank Account only; nothing is sent in the refused scope")
    fun gateRefusalsOfEachScope() {
        server.page(UID_SAVINGS, null, listOf(entry()))
        server.page(UID_KUUSI, null, listOf(entry()))
        val byAccount = fetch(
            request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS)),
            RefusingGate(RefusalScope.BankAccount, RefusalReason.UnattendedAccessLimitReached) { it.bankAccountReference == HOUSEHOLD },
        )
        assertEquals(BankAccountResult.NotRead(NotReadCause.RefusedByGate), byAccount.of(HOUSEHOLD).result)
        assertTrue(byAccount.of(SAVINGS).result is BankAccountResult.Read)

        val byBank = fetch(
            request(transactionsOnly(HOUSEHOLD), transactionsOnly(SAVINGS), transactionsOnly(KUUSI_CURRENT)),
            RefusingGate(RefusalScope.Bank, RefusalReason.BackOff) { it.bankReference == AURORA },
        )
        assertEquals(listOf<BankOutcome>(BankOutcome.StoppedByGate(AURORA, RefusalReason.BackOff)), byBank.bankOutcomes)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank), byBank.of(SAVINGS).result)
        assertTrue(byBank.of(KUUSI_CURRENT).result is BankAccountResult.Read)
        assertTrue(server.requestsOf(UID_HOUSEHOLD).isEmpty())

        server.received.clear()
        val bySource = fetch(request(transactionsOnly(HOUSEHOLD)), RecordingRequestGate.refusing())
        assertEquals(SourceOutcome.StoppedByGate(RefusalReason.RequestLimitReached), bySource.sourceOutcome)
        assertEquals(BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource), bySource.of(HOUSEHOLD).result)
        assertEquals(0, server.received.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", requirements = ["NFR-012", "NFR-008"])
    @DisplayName("NFR-012, NFR-008: no text of the Data Provider, no session, uid, key, Bank Account number or credential appears in a toString() or the log, with Detailed Logging on or off; no payee or memo in the log")
    fun nothingSensitiveLeavesThePart() {
        listOf(true, false).forEach { detailed ->
            server.received.clear()
            val written = CapturingLog(detailed)
            val part = EnableBankingDataProvider(written, server.url(), OkHttpClient(), Clock.fixed(now, ZoneOffset.UTC), language = { "fi-FI" })
            val withNumber = buildJsonObject {
                putJsonObject("transaction_amount") { put("amount", "3.00"); put("currency", "EUR") }
                put("credit_debit_indicator", "DBIT")
                put("status", "BOOK")
                put("booking_date", "2026-11-20")
                putJsonObject("creditor") { put("name", "PLANTED-PAYEE-77") }
                putJsonObject("creditor_account") { put("iban", "FI00PLANTED-OTHER-IBAN-55") }
                put("remittance_information", buildJsonArray { add(JsonPrimitive("PLANTED-MEMO-88")) })
            }
            server.balances(UID_HOUSEHOLD, error(400, "ASPSP_ERROR", "PLANTED-ERROR-TEXT-41"))
            server.page(UID_HOUSEHOLD, null, listOf(withNumber))
            server.page(UID_SAVINGS, null, listOf(entry()), next = "PLANTED-KEY-SECRET")
            server.page(UID_SAVINGS, "PLANTED-KEY-SECRET", error(422, "WRONG_CONTINUATION_KEY", "PLANTED-ERROR-TEXT-42"))
            val response = runBlocking { part.fetch(request(both(HOUSEHOLD), transactionsOnly(SAVINGS)), TestRequestContext(credential = ConsentServer.credential())) }
            assertTrue(response.of(HOUSEHOLD).result is BankAccountResult.Read)
            val shown = listOf(response.toString()) + response.bankAccounts.map { it.toString() } + written.lines
            listOf(
                "PLANTED-ERROR-TEXT-41", "PLANTED-ERROR-TEXT-42", "PLANTED-KEY-SECRET", "FI00PLANTED-OTHER-IBAN-55",
                SESSION_AURORA, UID_HOUSEHOLD, UID_SAVINGS, ConsentServer.APPLICATION_ID,
            ).forEach { planted -> shown.forEach { assertFalse(planted in it, "$planted shown ($detailed): $it") } }
            listOf(HOUSEHOLD, SAVINGS, "PLANTED-PAYEE-77", "PLANTED-MEMO-88").forEach { planted ->
                written.lines.forEach { assertFalse(planted in it, "$planted logged ($detailed): $it") }
            }
        }
    }

    // ---- The Bank Session ----------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"])
    @DisplayName("A4: a Bank Session handed under another Bank's reference than its own is not used, and nothing is sent through it")
    fun sessionUnderAnotherBanksReferenceIsNotUsed() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        val response = fetch(request(transactionsOnly(HOUSEHOLD), sessions = mapOf(KUUSI to aurora.toSession())))
        assertEquals(BankAccountResult.Failed(FailureKind.UnreadableAnswer), response.of(HOUSEHOLD).result)
        assertEquals(0, server.received.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("S-B-55: a Bank Session of the earlier form, without the currencies, is read: its transactions, no Account Balance and no balances request")
    fun earlierSessionIsRead() {
        val earlier = kotlinx.serialization.json.buildJsonObject {
            put("session_id", SESSION_AURORA)
            put("bank", AURORA)
            put("required_psu_headers", buildJsonArray { add(JsonPrimitive("Psu-User-Agent")) })
            putJsonObject("uids") { put(HOUSEHOLD, UID_HOUSEHOLD) }
        }.toString()
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        val response = fetch(request(both(HOUSEHOLD), sessions = mapOf(AURORA to ConsentSession(earlier))))
        assertEquals(BankAccountResult.Read(listOf(booked(thousandths = -12_500)), null, null), response.of(HOUSEHOLD).result)
        assertEquals(listOf("transactions"), server.requestsOf(UID_HOUSEHOLD).map { it.requestUrl!!.pathSegments[2] })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account")
    @DisplayName("S-B-55 (E3 review): a Bank Account the Bank Session could not keep, because its Bank has more than one Bank Session holds, fails with that reason of its own, not as an unreadable answer")
    fun bankAccountNotKeptHasItsOwnReason() {
        val full = BankSessionValue(SESSION_AURORA, AURORA, listOf("Psu-User-Agent"), mapOf(HOUSEHOLD to UID_HOUSEHOLD), notAllKept = true)
        val response = fetch(request(transactionsOnly(SAVINGS), sessions = mapOf(AURORA to full.toSession())))
        assertEquals(BankAccountResult.Failed(FailureKind.NotHeldByBankSession), response.of(SAVINGS).result)
        assertEquals(AURORA, response.of(SAVINGS).bankReference)
        assertEquals(0, server.received.size)
    }

    // ---- The Operator's presence -------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("BR-012 (S-B-46): at a Bank that requires only headers Slussen can give truly, a Fetch the Operator started carries both, never the address or a location, and is attended")
    fun presenceHeadersAtABankThatTakesThem() {
        server.balances(UID_HOUSEHOLD, balance("ITBD", "1.00"))
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        val gate = RecordingRequestGate()
        fetch(request(both(HOUSEHOLD)), gate)
        server.received.forEach { sent ->
            assertEquals("Slussen", sent.getHeader("Psu-User-Agent"))
            assertEquals("fi-FI", sent.getHeader("Psu-Accept-Language"))
            assertNull(sent.getHeader("Psu-Ip-Address"))
            assertNull(sent.getHeader("Psu-Geo-Location"))
        }
        assertTrue(gate.requests.none { it.unattended })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("BR-012 (S-B-46, E3 review item 1): at a Bank that requires a header Slussen cannot give, and in an unattended Fetch, no header of presence is sent and every request is unattended")
    fun noPresenceHeadersOtherwise() {
        server.page(UID_KUUSI, null, listOf(entry()))
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        val gate = RecordingRequestGate()
        fetch(request(transactionsOnly(KUUSI_CURRENT)), gate)
        fetch(request(transactionsOnly(HOUSEHOLD), presence = OperatorPresence.Unattended), gate)
        assertEquals(2, server.received.size)
        server.received.forEach { sent -> assertTrue(sent.headers.names().none { it.startsWith("Psu-", ignoreCase = true) }, "${sent.headers.names()}") }
        assertEquals(2, gate.requests.size)
        assertTrue(gate.requests.all { it.unattended })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"])
    @DisplayName("BR-012 (S-B-55): with a device language that cannot be sent, showsPresence says no and no header of presence is sent, so A22 is asked for what is counted as unattended")
    fun presenceAgreesWithWhatIsSent() {
        listOf("und", "", "  ").forEach { language ->
            server.received.clear()
            val part = EnableBankingDataProvider(log, server.url(), OkHttpClient(), Clock.fixed(now, ZoneOffset.UTC), language = { language })
            server.page(UID_HOUSEHOLD, null, listOf(entry()))
            val gate = RecordingRequestGate()
            runBlocking { part.fetch(request(transactionsOnly(HOUSEHOLD)), TestRequestContext(gate, credential = ConsentServer.credential())) }
            assertFalse(part.bankConsent.showsPresence(aurora.toSession()), "'$language'")
            assertTrue(gate.requests.all { it.unattended }, "'$language'")
            assertTrue(server.received.single().headers.names().none { it.startsWith("Psu-", ignoreCase = true) }, "'$language'")
        }
        assertTrue(provider.bankConsent.showsPresence(aurora.toSession()))
    }

    // ---- The dates asked -----------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009 (E3 review): the dates asked do not run ahead of the Data Provider's own time: with a device clock days ahead, the period ends on the Data Provider's day in UTC, its length kept")
    fun datesDoNotRestOnAClockThatRunsAhead() {
        val ahead = EnableBankingDataProvider(log, server.url(), OkHttpClient(), Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC), language = { "fi-FI" })
        server.balances(UID_HOUSEHOLD, ok("""{"balances":[]}""").setHeader("Date", "Sat, 28 Nov 2026 10:00:00 GMT"))
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        runBlocking {
            ahead.fetch(
                request(both(HOUSEHOLD), periodFrom = LocalDate.of(2026, 11, 1), periodTo = LocalDate.of(2026, 12, 1)),
                TestRequestContext(credential = ConsentServer.credential()),
            )
        }
        val asked = server.requestsOf(UID_HOUSEHOLD).last().requestUrl!!
        assertEquals("2026-10-29", asked.queryParameter("date_from"))
        assertEquals("2026-11-28", asked.queryParameter("date_to"))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: without an earlier answer's time, the period never ends after today in UTC by the device's clock")
    fun datesInUtc() {
        server.page(UID_HOUSEHOLD, null, listOf(entry()))
        fetch(request(transactionsOnly(HOUSEHOLD), periodFrom = LocalDate.of(2026, 10, 30), periodTo = LocalDate.of(2026, 11, 29)))
        val asked = server.requestsOf(UID_HOUSEHOLD).single().requestUrl!!
        assertEquals("2026-10-29", asked.queryParameter("date_from"))
        assertEquals("2026-11-28", asked.queryParameter("date_to"))
    }

    /** A gate that refuses every request [refuses] picks, with [scope] and [reason], and admits the others. */
    private class RefusingGate(
        private val scope: RefusalScope = RefusalScope.BankAccount,
        private val reason: RefusalReason = RefusalReason.UnattendedAccessLimitReached,
        private val refuses: (RequestSummary) -> Boolean,
    ) : RequestGate {
        override suspend fun admit(request: RequestSummary): Admission = if (refuses(request)) Admission.Refused(scope, reason) else Admission.Allowed
    }

    /** A log that writes the detail only while Detailed Logging is on, as the app's adapter does (ADR-006 §1). */
    private class CapturingLog(private val detailed: Boolean = true) : SlussenLog {
        val lines = CopyOnWriteArrayList<String>()

        override fun event(event: LogEvent, vararg facts: LogFact) {
            lines += "$event ${facts.toList()}"
        }

        override fun detail(event: LogEvent, message: () -> String) {
            if (detailed) lines += "$event ${message()}"
        }
    }

    private companion object {
        val EUR = CurrencyCode("EUR")
        const val AURORA = "Aurora Sparbank (FI)"
        const val KUUSI = "Kuusi Pankki (FI)"
        const val VESTA = "Vesta Bank (SE)"
        const val SESSION_VESTA = "PLANTED-SESSION-VESTA-3c"
        const val VESTA_CURRENT = "PLANTED-HASH-VESTA=="
        const val UID_VESTA = "PLANTED-UID-VESTA-05"
        const val SESSION_AURORA = "PLANTED-SESSION-AURORA-1a"
        const val SESSION_KUUSI = "PLANTED-SESSION-KUUSI-2b"
        const val HOUSEHOLD = "PLANTED-HASH-HOUSEHOLD=="
        const val SAVINGS = "PLANTED-HASH-SAVINGS=="
        const val CARD = "PLANTED-HASH-CARD=="
        const val KUUSI_CURRENT = "PLANTED-HASH-KUUSI=="
        const val UID_HOUSEHOLD = "PLANTED-UID-HOUSEHOLD-01"
        const val UID_SAVINGS = "PLANTED-UID-SAVINGS-02"
        const val UID_CARD = "PLANTED-UID-CARD-03"
        const val UID_KUUSI = "PLANTED-UID-KUUSI-04"
    }
}
