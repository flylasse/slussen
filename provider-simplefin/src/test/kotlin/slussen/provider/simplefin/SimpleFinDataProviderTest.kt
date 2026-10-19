package slussen.provider.simplefin

import kotlinx.coroutines.runBlocking
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
import slussen.pluginapi.testfixtures.FetchScenario
import slussen.pluginapi.testfixtures.RecordedFetch
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

    private fun provider() = SimpleFinDataProvider(SilentLog, http, allowPlainHttp = true)

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

    /** An invented Account Set: three accounts at two organizations, with pending, zero-amount and unreadable items. */
    private fun accountSet(): String = """
        {"errors": [],
         "accounts": [
          {"org": {"domain": "kestrel.example", "name": "Kestrel Bank", "sfin-url": "https://bridge.invalid/simplefin"},
           "id": "ACT-4410", "name": "CHK", "currency": "USD", "balance": "4140.05", "available-balance": "4100.00",
           "balance-date": ${epoch("2026-10-15T06:00:00Z")},
           "transactions": [
             {"id": "TRN-1", "posted": ${epoch("2026-10-14T12:00:00Z")}, "amount": "-12.34", "description": "Corner Grocer"},
             {"id": "TRN-2", "posted": 0, "transacted_at": ${epoch("2026-10-15T09:00:00Z")}, "amount": "-5.00", "description": "Pending Cafe", "pending": true},
             {"id": "TRN-3", "posted": ${epoch("2026-10-13T12:00:00Z")}, "amount": "0.00", "description": "Zero fee"},
             {"id": "TRN-4", "amount": "-7.50", "description": "No date"},
             {"id": "TRN-5", "posted": ${epoch("2026-10-13T12:00:00Z")}, "amount": "lots", "description": "Bad amount"}
           ]},
          {"org": {"domain": "pinecrest.example", "name": "Pinecrest Card Services", "sfin-url": "https://bridge.invalid/simplefin"},
           "id": "ACT-1182", "name": "Pinecrest Rewards Visa", "currency": "USD", "balance": "-1268.40",
           "balance-date": ${epoch("2026-10-15T06:00:00Z")},
           "transactions": [
             {"id": " TRN-Opaque-6 ", "posted": ${epoch("2026-10-12T23:30:00Z")}, "amount": "-49.99", "description": "Fuel stop", "payee": "Fuel Stop Inc", "memo": "Pump 4"}
           ]},
          {"org": {"domain": "kestrel.example", "name": "Kestrel Bank", "sfin-url": "https://bridge.invalid/simplefin"},
           "id": "ACT-7731", "name": "Reserve Saver", "currency": "USD", "balance": "12000.00",
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

    override fun fetchCredentialRefused() = fetching(MockResponse().setResponseCode(403).setBody("invented refusal"))

    override fun fetchUnavailable() = fetching(MockResponse().setResponseCode(503))

    override fun fetchNeverAnswering() = fetching(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun plantedFetchValues(): List<String> = listOf(PLANTED_USER, PLANTED_PASSWORD)

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("Step 4: one request lists every account with its transactions for the fetch period, sent with the access credential and admitted by the gate as a read of transactions")
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
        assertEquals(listOf(RequestSummary(RequestKind.Transactions, null, null)), gate.requests)
        assertEquals(1, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 4, BR-011: where only balances are read, the request asks for balances only and is admitted as a read of the balance")
    fun balancesOnlyWhereOnlyBalancesAreRead() {
        val selections = listOf(
            BankAccountSelection.AllForIdentification(ReadKind.Balance),
            BankAccountSelection.Selected(listOf(BankAccountToRead("ACT-7731", ReadKind.Balance))),
            BankAccountSelection.Selected(emptyList()),
        )
        selections.forEach { selection ->
            val gate = RecordingRequestGate()
            fetch(fetching(MockResponse().setBody(accountSet()), selection), gate)
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("1", sent.requestUrl!!.queryParameter("balances-only"), "$selection")
            assertNull(sent.requestUrl!!.queryParameter("start-date"))
            assertEquals(listOf(RequestSummary(RequestKind.Balance, null, null)), gate.requests)
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5: each account is delivered with its identifier, the Bank its organization names, its name, currency and balance; a listed account the request did not read is NotRead(NotSelected)")
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
        assertEquals(BankAccountResult.NotRead(NotReadCause.NotSelected), response.bankAccounts[2].result)
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
    @DisplayName("Step 5: every transaction is passed on unfiltered, its id as the Stable Reference exactly as delivered; one without a date or with an unreadable amount is Uninterpretable")
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
        assertEquals("Fuel Stop Inc", card.payee)
        assertEquals("Pump 4", card.memo)
        assertEquals(LocalDate.of(2026, 10, 12), card.bookedDate)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 5: an account in a currency that is no ISO 4217 code states no currency, and its transactions are Uninterpretable for the missing currency")
    fun customCurrencyIsNoCurrency() {
        val body = """
            {"errors": [], "accounts": [
              {"org": {"domain": "harbor.example", "name": "Harbor Points", "sfin-url": "https://bridge.invalid/simplefin"},
               "id": "ACT-PTS", "name": "Points", "currency": "https://harbor.example/points", "balance": "120.5",
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
