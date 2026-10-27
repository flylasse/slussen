package slussen.provider.simplefin

import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.testfixtures.CredentialScenario
import slussen.pluginapi.testfixtures.DataProviderFetchContractTest
import slussen.pluginapi.testfixtures.DeviceRefusal
import slussen.pluginapi.testfixtures.FetchScenario
import slussen.pluginapi.testfixtures.RecordedFetch
import slussen.pluginapi.testfixtures.RecordedListedBalances
import slussen.pluginapi.testfixtures.RecordingRequestThreads
import slussen.pluginapi.testfixtures.RecordingRequestGate
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.provider.UnreadableReason
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.net.InetAddress
import java.net.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The SimpleFIN Data Provider against the contract tests of ADR-001 §4.4, with invented recorded answers served by a
 * local MockWebServer; the real SimpleFIN Bridge is never called.
 */
class SimpleFinDataProviderTest : DataProviderFetchContractTest() {
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

    private fun provider() = SimpleFinDataProvider(log, http, allowPlainHttp = true)

    private fun setupToken(): String =
        Base64.getEncoder().encodeToString(server.url(CLAIM_PATH).toString().toByteArray(Charsets.UTF_8))

    private fun accessAddress(): String =
        server.url("/simplefin").newBuilder().username(PLANTED_USER).password(PLANTED_PASSWORD).build().toString()

    private fun input(token: String = setupToken()) = CredentialInput(mapOf(SimpleFinCredentials.SETUP_TOKEN to Secret(token)))

    private fun answering(response: MockResponse): CredentialScenario {
        server.enqueue(response)
        return CredentialScenario(provider().credentials, input())
    }

    override fun dataProvider(log: SlussenLog): DataProvider = SimpleFinDataProvider(log)

    override fun credentialsAccepted() = answering(MockResponse().setBody(accessAddress()))

    override fun credentialsRefused() = answering(MockResponse().setResponseCode(403).setBody("invented refusal"))

    override fun credentialServiceUnavailable() = answering(MockResponse().setResponseCode(503))

    override fun credentialServiceNeverAnswering() = answering(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun credentialsThatCannotBeValid() = CredentialScenario(provider().credentials, input("not base64 !!"))

    override fun requestsReceived(): Int = server.requestCount

    override fun runningCalls(): Int = http.dispatcher.runningCallsCount()

    override fun plantedValues(): List<String> = listOf(setupToken(), CLAIM_PATH, PLANTED_USER, PLANTED_PASSWORD)

    /**
     * ADR-001 §4.0 (S-36): a device that refuses network access, as Android does for an app without the network
     * permission: the address lookup throws, and the requests run on [threads]. No proxy, so the lookup is of the
     * service's own invented host.
     */
    private fun refusingDevice(threads: RecordingRequestThreads) = SimpleFinDataProvider(
        SilentLog,
        OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw DeviceRefusal.exception() })
            .dispatcher(Dispatcher(threads))
            .proxy(Proxy.NO_PROXY)
            .build(),
        allowPlainHttp = false,
    )

    override fun credentialsOnRefusingDevice(threads: RecordingRequestThreads): CredentialScenario {
        val claim = Base64.getEncoder().encodeToString("https://bridge.invalid$CLAIM_PATH".toByteArray(Charsets.UTF_8))
        return CredentialScenario(refusingDevice(threads).credentials, input(claim))
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["C-008"])
    @DisplayName("BR-002: the setup token is exchanged once for the access credential, which is what Slussen keeps")
    @OptIn(SensitiveApi::class)
    fun setupTokenIsExchangedForTheAccessCredential() {
        val credentials = provider().credentials
        assertEquals(listOf(SimpleFinCredentials.SETUP_TOKEN), credentials.form.fields.map { it.key })
        assertEquals(FieldKind.Secret, credentials.form.fields.single().kind)
        server.enqueue(MockResponse().setBody(accessAddress()))
        val result = runBlocking { credentials.check(input(), TestRequestContext()) }
        val claim = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", claim.method)
        assertEquals(CLAIM_PATH, claim.requestUrl!!.encodedPath)
        assertEquals(0L, claim.bodySize)
        assertEquals(accessAddress(), (result as CredentialCheck.Accepted).credential.reveal())
        assertEquals(1, server.requestCount, "the setup token is claimed once")
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused", businessRules = ["BR-002"])
    @DisplayName("A1: a token that is not a SimpleFIN setup token is refused without contacting anything")
    fun malformedTokenIsRefusedUnsent() {
        listOf("not base64 !!", Base64.getEncoder().encodeToString("no address".toByteArray())).forEach { token ->
            val result = runBlocking { provider().credentials.check(input(token), TestRequestContext()) }
            assertEquals(CredentialCheck.Refused, result)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("BR-002: a claim address without https is refused unsent")
    fun plainHttpClaimIsRefusedInTheBuild() {
        val built = SimpleFinDataProvider(SilentLog)
        val result = runBlocking { built.credentials.check(input(), TestRequestContext()) }
        assertEquals(CredentialCheck.Refused, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("ADR-001 §4.1: SimpleFIN needs no Bank Consent")
    fun needsNoBankConsent() {
        assertNull(SimpleFinDataProvider(SilentLog).bankConsent)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: SimpleFIN declares the Request Limit it publishes, 24 in any 24 hours, with its source; fixed, not an Operator Setting")
    fun declaresThePublishedRequestLimit() {
        val limit = SimpleFinDataProvider(SilentLog).profile.requestLimit as RequestLimitDeclaration.Published
        assertEquals(24, limit.requests)
        assertTrue("https://beta-bridge.simplefin.org/info/developers" in limit.source, limit.source)
        assertTrue("SimpleFIN Bridge Developer Guide" in limit.source, limit.source)
    }

    @Test
    @UseCase(id = "UC-012", businessRules = ["BR-011"])
    @DisplayName("BR-011: SimpleFIN's part declares its name, source key and default Fetch Cooldown of 60 minutes (ADR-001 §4.1, O-P2-42)")
    fun declaresItsProfile() {
        val profile = SimpleFinDataProvider(SilentLog).profile
        assertEquals("SimpleFIN", profile.name)
        assertEquals("simplefin", profile.sourceKey)
        assertEquals(60, profile.defaultFetchCooldownMinutes)
        assertEquals(false, profile.needsBankConsent)
    }

    // ---- Fetch (UC-001) -------------------------------------------------------------------------------------------

    private val periodFrom = LocalDate.of(2026, 8, 16)
    private val periodTo = LocalDate.of(2026, 10, 15)

    private fun epoch(text: String): Long = Instant.parse(text).epochSecond

    private fun storedAccess(): Secret = Secret(accessAddress())

    private fun request(selection: BankAccountSelection) =
        FetchRequest(selection, periodFrom, periodTo, OperatorPresence.OperatorPresent, emptyMap())

    private val readBoth = BankAccountSelection.Selected(
        listOf(
            BankAccountToRead("ACT-4410", ReadKind.TransactionsAndBalance),
            BankAccountToRead("ACT-1182", ReadKind.TransactionsAndBalance),
        ),
    )

    /**
     * An invented Account Set of SimpleFIN protocol version 2: three accounts through two connections, one connection
     * naming its organization (`org_name`), one only by its own `name`; with pending, zero-amount and unreadable items.
     */
    private fun accountSet(): String = """
        {"errlist": [],
         "connections": [
          {"conn_id": "CON-KESTREL-01", "name": "Kestrel Bank - Joint", "org_id": "ORG-KESTREL", "org_name": "Kestrel Bank",
           "org_url": "https://kestrel.example", "sfin_url": "https://bridge.invalid/simplefin"},
          {"conn_id": "CON-PINE-02", "name": "Pinecrest Card Services", "org_id": "ORG-PINE",
           "org_url": "https://pinecrest.example", "sfin_url": "https://bridge.invalid/simplefin"}
         ],
         "accounts": [
          {"id": "ACT-4410", "name": "CHK", "conn_id": "CON-KESTREL-01", "currency": "USD", "balance": "4140.05",
           "available-balance": "4100.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")},
           "transactions": [
             {"id": "TRN-1", "posted": ${epoch("2026-10-14T12:00:00Z")}, "amount": "-12.34", "description": "Corner Grocer"},
             {"id": "TRN-2", "posted": 0, "transacted_at": ${epoch("2026-10-15T09:00:00Z")}, "amount": "-5.00", "description": "Pending Cafe", "pending": true},
             {"id": "TRN-3", "posted": ${epoch("2026-10-13T12:00:00Z")}, "amount": "0.00", "description": "Zero fee"},
             {"id": "TRN-4", "amount": "-7.50", "description": "No date"},
             {"id": "TRN-5", "posted": ${epoch("2026-10-13T12:00:00Z")}, "amount": "lots", "description": "Bad amount"}
           ]},
          {"id": "ACT-1182", "name": "Pinecrest Rewards Visa", "conn_id": "CON-PINE-02", "currency": "USD", "balance": "-1268.40",
           "balance-date": ${epoch("2026-10-15T06:00:00Z")},
           "transactions": [
             {"id": " TRN-Opaque-6 ", "posted": ${epoch("2026-10-12T23:30:00Z")}, "amount": "-49.99", "description": "Fuel Stop Inc pump 4",
              "extra": {"category": "fuel"}}
           ]},
          {"id": "ACT-7731", "name": "Reserve Saver", "conn_id": "CON-KESTREL-01", "currency": "USD", "balance": "12000.00",
           "balance-date": ${epoch("2026-10-15T06:00:00Z")}, "transactions": []}
         ]}
    """.trimIndent()

    private fun fetching(response: MockResponse, selection: BankAccountSelection = readBoth): FetchScenario {
        server.enqueue(response)
        return FetchScenario(provider(), request(selection), storedAccess())
    }

    private fun fetch(scenario: FetchScenario, gate: RecordingRequestGate = RecordingRequestGate()) =
        runBlocking { scenario.dataProvider.fetch(scenario.request, TestRequestContext(gate, credential = scenario.credential)) }

    override fun fetchAnswered() = fetching(MockResponse().setBody(accountSet()))

    override fun recordedFetch() = RecordedFetch(
        bankAccounts = listOf("ACT-4410", "ACT-1182", "ACT-7731"),
        itemsPerBankAccount = mapOf("ACT-4410" to 5, "ACT-1182" to 1),
        uninterpretable = 2,
    )

    /** The core's request since O-P2-54: one added account, for its balance only. */
    private val readOneBalance = BankAccountSelection.Selected(listOf(BankAccountToRead("ACT-4410", ReadKind.Balance)))

    override fun fetchReadingSome() = fetching(MockResponse().setBody(accountSet()), readOneBalance)

    override fun recordedListedBalances() = RecordedListedBalances(
        balances = mapOf("ACT-1182" to Amount(-1_268_400), "ACT-7731" to Amount(12_000_000)),
        requests = 1,
    )

    override fun fetchCredentialRefused() = fetching(MockResponse().setResponseCode(403).setBody("invented refusal"))

    override fun fetchUnavailable() = fetching(MockResponse().setResponseCode(503))

    override fun fetchNeverAnswering() = fetching(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun plantedFetchValues(): List<String> = listOf(PLANTED_USER, PLANTED_PASSWORD)

    override fun fetchOnRefusingDevice(threads: RecordingRequestThreads) = FetchScenario(
        refusingDevice(threads),
        request(readBoth),
        Secret("https://$PLANTED_USER:$PLANTED_PASSWORD@bridge.invalid/simplefin"),
    )

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("Step 4: one request of protocol version 2 lists every account with its transactions for the fetch period, sent with the access credential and admitted by the gate as a read of transactions")
    fun fetchAsksForTheAccountsWithTheirTransactions() {
        val gate = RecordingRequestGate()

        fetch(fetchAnswered(), gate)

        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", sent.method)
        assertEquals("/simplefin/accounts", sent.requestUrl!!.encodedPath)
        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("$PLANTED_USER:$PLANTED_PASSWORD".toByteArray())
        assertEquals(expectedAuth, sent.getHeader("Authorization"))
        assertEquals(epoch("2026-08-16T00:00:00Z").toString(), sent.requestUrl!!.queryParameter("start-date"))
        assertEquals(epoch("2026-10-16T00:00:00Z").toString(), sent.requestUrl!!.queryParameter("end-date"))
        assertNull(sent.requestUrl!!.queryParameter("balances-only"))
        assertNull(sent.requestUrl!!.queryParameter("account"))
        assertEquals("2", sent.requestUrl!!.queryParameter("version"))
        assertEquals(listOf(RequestSummary(RequestKind.Transactions, null, null)), gate.requests)
        assertEquals(1, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 4, BR-011: where only balances are read, as the core asks every selected Bank Account until steps 7-10 are built (O-P2-54), the request of protocol version 2 asks for balances only and is admitted as a read of the balance")
    fun balancesOnlyWhereOnlyBalancesAreRead() {
        val selections = listOf(
            BankAccountSelection.AllForIdentification(ReadKind.Balance),
            BankAccountSelection.Selected(listOf(BankAccountToRead("ACT-7731", ReadKind.Balance))),
            readOneBalance,
            BankAccountSelection.Selected(emptyList()),
        )
        selections.forEach { selection ->
            val gate = RecordingRequestGate()
            fetch(fetching(MockResponse().setBody(accountSet()), selection), gate)
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("1", sent.requestUrl!!.queryParameter("balances-only"), "$selection")
            assertNull(sent.requestUrl!!.queryParameter("start-date"))
            assertNull(sent.requestUrl!!.queryParameter("end-date"))
            assertEquals("2", sent.requestUrl!!.queryParameter("version"), "$selection")
            assertEquals(listOf(RequestSummary(RequestKind.Balance, null, null)), gate.requests)
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5: each account is delivered with its identifier, the Bank its connection names, its name, currency and balance; a listed account the request did not read is NotRead(NotSelected) with the balance of the same answer (S-35)")
    fun accountsAreDelivered() {
        val response = fetch(fetchAnswered())

        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        assertTrue(response.bankOutcomes.isEmpty())
        val checking = response.bankAccounts[0]
        assertEquals("ACT-4410", checking.providerReference)
        assertEquals("Kestrel Bank", checking.bankReference)
        assertEquals("CHK", checking.name)
        assertNull(checking.kind)
        assertEquals(CurrencyCode("USD"), checking.currency)
        assertNull(checking.number)
        assertEquals(Amount(4_140_050), (checking.result as BankAccountResult.Read).balance)
        assertEquals("Pinecrest Card Services", response.bankAccounts[1].bankReference)
        assertEquals(BankAccountResult.NotRead(NotReadCause.NotSelected, Amount(12_000_000)), response.bankAccounts[2].result)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5: an identification read delivers every account with its balance and no transactions")
    fun identificationReadDeliversEveryBalance() {
        val response = fetch(fetching(MockResponse().setBody(accountSet()), BankAccountSelection.AllForIdentification(ReadKind.Balance)))

        assertEquals(
            listOf(Amount(4_140_050), Amount(-1_268_400), Amount(12_000_000)),
            response.bankAccounts.map { (it.result as BankAccountResult.Read).balance },
        )
        assertTrue(response.bankAccounts.all { (it.result as BankAccountResult.Read).transactions.isEmpty() })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5: every transaction is passed on unfiltered, its id as the Stable Reference exactly as delivered, its description as the payee and no memo; one without a date or with an unreadable amount is Uninterpretable")
    fun transactionsArePassedOn() {
        val response = fetch(fetchAnswered())

        val items = (response.bankAccounts[0].result as BankAccountResult.Read).transactions
        val usd = CurrencyCode("USD")
        assertEquals(
            ProviderTransaction.Interpretable("TRN-1", LocalDate.of(2026, 10, 14), Amount(-12_340), usd, "Corner Grocer", null, booked = true),
            items[0],
        )
        assertEquals(
            ProviderTransaction.Interpretable("TRN-2", LocalDate.of(2026, 10, 15), Amount(-5_000), usd, "Pending Cafe", null, booked = false),
            items[1],
        )
        assertEquals(Amount(0), (items[2] as ProviderTransaction.Interpretable).amount)
        assertEquals(
            ProviderTransaction.Uninterpretable(null, Amount(-7_500), usd, "No date", null, booked = true, reason = UnreadableReason.MissingDate),
            items[3],
        )
        assertEquals(UnreadableReason.UnreadableAmount, (items[4] as ProviderTransaction.Uninterpretable).reason)
        val card = (response.bankAccounts[1].result as BankAccountResult.Read).transactions.single() as ProviderTransaction.Interpretable
        assertEquals(" TRN-Opaque-6 ", card.stableReference)
        assertEquals("Fuel Stop Inc pump 4", card.payee)
        assertNull(card.memo)
        assertEquals(LocalDate.of(2026, 10, 12), card.bookedDate)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5: an account in a currency that is no ISO 4217 code states no currency, and its transactions are Uninterpretable for the missing currency")
    fun customCurrencyIsNoCurrency() {
        val body = """
            {"errlist": [],
             "connections": [{"conn_id": "CON-PTS", "name": "Harbor Points", "org_id": "ORG-PTS", "sfin_url": "https://bridge.invalid/simplefin"}],
             "accounts": [
              {"id": "ACT-PTS", "name": "Points", "conn_id": "CON-PTS", "currency": "https://harbor.example/points", "balance": "120.5",
               "balance-date": ${epoch("2026-10-15T06:00:00Z")},
               "transactions": [{"id": "P-1", "posted": ${epoch("2026-10-14T12:00:00Z")}, "amount": "10", "description": "Bonus"}]}
            ]}
        """.trimIndent()
        val selection = BankAccountSelection.Selected(listOf(BankAccountToRead("ACT-PTS", ReadKind.TransactionsAndBalance)))

        val account = fetch(fetching(MockResponse().setBody(body), selection)).bankAccounts.single()

        assertNull(account.currency)
        val item = (account.result as BankAccountResult.Read).transactions.single() as ProviderTransaction.Uninterpretable
        assertEquals(UnreadableReason.MissingCurrency, item.reason)
        assertEquals(Amount(10_000), item.amount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials")
    @DisplayName("A7: SimpleFIN answering that the access is not allowed (payment required) is CredentialsRefused; a stored credential that is no access address is refused unsent")
    fun accessNotAllowedIsCredentialsRefused() {
        assertEquals(SourceOutcome.CredentialsRefused, fetch(fetching(MockResponse().setResponseCode(402))).sourceOutcome)

        val gate = RecordingRequestGate()
        val unsent = runBlocking {
            provider().fetch(request(readBoth), TestRequestContext(gate, credential = Secret("no address")))
        }

        assertEquals(SourceOutcome.CredentialsRefused, unsent.sourceOutcome)
        assertTrue(gate.requests.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably")
    @DisplayName("A6: an answer that cannot be read is TransientFailure(UnreadableAnswer); no error text leaves the plug-in")
    fun unreadableAnswerIsATransientFailure() {
        val response = fetch(fetching(MockResponse().setBody("<html>invented outage page</html>")))

        assertEquals(SourceOutcome.TransientFailure(FailureKind.UnreadableAnswer), response.sourceOutcome)
        assertTrue(response.bankAccounts.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (O-P2-60): the Bank of each account is its connection's org_name, else the connection's name, else the host of its org_url; an account with the org object of protocol version 1 takes its name, else its domain")
    fun bankComesFromTheConnection() {
        val body = """
            {"errlist": [],
             "connections": [
              {"conn_id": "CON-A", "name": "Kestrel Bank - Joint", "org_id": "ORG-A", "org_name": "Kestrel Bank", "org_url": "https://kestrel.example", "sfin_url": "https://bridge.invalid/simplefin"},
              {"conn_id": "CON-B", "name": "Pinecrest Card Services", "org_id": "ORG-B", "org_name": "  ", "sfin_url": "https://bridge.invalid/simplefin"},
              {"conn_id": "CON-C", "org_id": "ORG-C", "org_url": "https://harbor.example/home", "sfin_url": "https://bridge.invalid/simplefin"},
              {"conn_id": "CON-D", "org_id": "ORG-D", "org_url": "lighthouse.example", "sfin_url": "https://bridge.invalid/simplefin"}
             ],
             "accounts": [
              {"id": "ACT-A", "name": "A", "conn_id": "CON-A", "currency": "USD", "balance": "1.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-B", "name": "B", "conn_id": "CON-B", "currency": "USD", "balance": "2.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-C", "name": "C", "conn_id": "CON-C", "currency": "USD", "balance": "3.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-D", "name": "D", "conn_id": "CON-D", "currency": "USD", "balance": "4.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-E", "name": "E", "org": {"domain": "aurora.example", "name": "Aurora Savings", "sfin-url": "https://bridge.invalid/simplefin"},
               "currency": "USD", "balance": "5.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-F", "name": "F", "org": {"domain": "meadow.example", "sfin-url": "https://bridge.invalid/simplefin"},
               "currency": "USD", "balance": "6.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-G", "name": "G", "conn_id": "CON-UNKNOWN", "currency": "USD", "balance": "7.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")}}
             ]}
        """.trimIndent()

        val response = fetch(fetching(MockResponse().setBody(body), BankAccountSelection.AllForIdentification(ReadKind.Balance)))

        assertEquals(
            listOf("Kestrel Bank", "Pinecrest Card Services", "harbor.example", "lighthouse.example", "Aurora Savings", "meadow.example", null),
            response.bankAccounts.map { it.bankReference },
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (O-P2-60): the payee is the transaction's description only and there is no memo, also when an answer carries attributes named payee or memo that protocol version 2 does not define")
    fun payeeIsTheDescriptionOnly() {
        val body = """
            {"errlist": [],
             "connections": [{"conn_id": "CON-A", "name": "Kestrel Bank", "org_id": "ORG-A", "sfin_url": "https://bridge.invalid/simplefin"}],
             "accounts": [
              {"id": "ACT-A", "name": "A", "conn_id": "CON-A", "currency": "USD", "balance": "1.00", "balance-date": ${epoch("2026-10-15T06:00:00Z")},
               "transactions": [
                {"id": "T-1", "posted": ${epoch("2026-10-14T12:00:00Z")}, "amount": "-3.10", "description": "Harbor Bakery",
                 "payee": "Not the payee", "memo": "Not a memo", "extra": {"memo": "Not a memo either"}}
               ]}
             ]}
        """.trimIndent()
        val selection = BankAccountSelection.Selected(listOf(BankAccountToRead("ACT-A", ReadKind.TransactionsAndBalance)))

        val item = (fetch(fetching(MockResponse().setBody(body), selection)).bankAccounts.single().result as BankAccountResult.Read)
            .transactions.single() as ProviderTransaction.Interpretable

        assertEquals("Harbor Bakery", item.payee)
        assertNull(item.memo)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 4 (S-35): a listed account the request did not read whose balance cannot be read is NotRead(NotSelected) with no balance, from the same one request")
    fun listedAccountWithoutAReadableBalanceHasNone() {
        val body = """
            {"errlist": [],
             "connections": [{"conn_id": "CON-A", "name": "Kestrel Bank", "org_id": "ORG-A", "sfin_url": "https://bridge.invalid/simplefin"}],
             "accounts": [
              {"id": "ACT-4410", "name": "CHK", "conn_id": "CON-A", "currency": "USD", "balance": "4140.05", "balance-date": ${epoch("2026-10-15T06:00:00Z")}},
              {"id": "ACT-9", "name": "Odd", "conn_id": "CON-A", "currency": "USD", "balance": "n/a", "balance-date": ${epoch("2026-10-15T06:00:00Z")}}
             ]}
        """.trimIndent()

        val response = fetch(fetching(MockResponse().setBody(body), readOneBalance))

        assertEquals(BankAccountResult.Read(emptyList(), Amount(4_140_050)), response.bankAccounts[0].result)
        assertEquals(BankAccountResult.NotRead(NotReadCause.NotSelected, null), response.bankAccounts[1].result)
        assertEquals(1, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-012", businessRules = ["BR-005"])
    @DisplayName("BR-005: SimpleFIN declares a Stable Reference, each transaction's id, unique within its account (ADR-001 §4.1, S-34)")
    fun declaresAStableReference() {
        assertEquals(true, SimpleFinDataProvider(SilentLog).profile.givesStableReference)
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }

    private companion object {
        const val CLAIM_PATH = "/claim/invented-claim-Z9X4"
        const val PLANTED_USER = "invented-user-K2"
        const val PLANTED_PASSWORD = "invented-password-P5"
    }
}
