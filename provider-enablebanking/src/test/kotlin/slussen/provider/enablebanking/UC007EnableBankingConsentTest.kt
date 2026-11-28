package slussen.provider.enablebanking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.RevokeResult
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
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.provider.enablebanking.ConsentServer.Companion.APPLICATION_ID
import slussen.provider.enablebanking.ConsentServer.Companion.BANK
import slussen.provider.enablebanking.ConsentServer.Companion.CODE
import slussen.provider.enablebanking.ConsentServer.Companion.CONSENT_ADDRESS
import slussen.provider.enablebanking.ConsentServer.Companion.HASH_1
import slussen.provider.enablebanking.ConsentServer.Companion.HASH_2
import slussen.provider.enablebanking.ConsentServer.Companion.HASH_4
import slussen.provider.enablebanking.ConsentServer.Companion.HASH_5
import slussen.provider.enablebanking.ConsentServer.Companion.RETURN_ADDRESS
import slussen.provider.enablebanking.ConsentServer.Companion.SESSION_ID
import slussen.provider.enablebanking.ConsentServer.Companion.STATE
import slussen.provider.enablebanking.ConsentServer.Companion.UID_1
import slussen.provider.enablebanking.ConsentServer.Companion.UID_4
import slussen.provider.enablebanking.ConsentServer.Companion.UID_5
import slussen.provider.enablebanking.ConsentServer.Companion.returned
import java.net.InetAddress
import java.net.Proxy
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * Enable Banking's Bank Consent operations (UC-007, ADR-001 §4.1.1) against a local test server with invented answers:
 * each request as it is sent, byte for byte where the specification gives its form, and each answer and error code with
 * its one outcome.
 */
@DisplayName("UC-007: Grant Bank Consent, Enable Banking's part")
class UC007EnableBankingConsentTest {
    private val server = ConsentServer()
    private val now = Instant.parse("2026-11-28T10:00:00Z")
    private val log = CapturingLog(detailed = true)
    private val provider = EnableBankingDataProvider(log, server.url(), OkHttpClient(), Clock.fixed(now, ZoneOffset.UTC))
    private val consent: BankConsentCapability = provider.bankConsent
    private val handle = ConsentHandle(BANK, ConsentServer.STARTED_REQUEST_ID, listOf("Psu-User-Agent")).toSecret()
    private val granting = returned("code=$CODE&state=$STATE")

    @AfterEach
    fun stopServer() = server.shutdown()

    private fun context(gate: RequestGate = RecordingRequestGate(), timeout: Duration = Duration.ofSeconds(5), credential: Secret? = ConsentServer.credential()) =
        TestRequestContext(gate, timeout, credential)

    private fun <T> call(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { block() }

    private fun only(): RecordedRequest = server.received.single()

    private fun attended(bank: String?) = RequestSummary(RequestKind.Other, bank, null, unattended = false)

    private fun assertSignedAndWithoutPresence(request: RecordedRequest) {
        val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
        val parts = token.split('.')
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(ConsentServer.keys.public)
            update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
            verify(Base64.getUrlDecoder().decode(parts[2]))
        }
        assertTrue(verified, "signed with the application's key")
        assertTrue(String(Base64.getUrlDecoder().decode(parts[0])).contains(APPLICATION_ID))
        assertTrue(request.headers.names().none { it.startsWith("Psu-", ignoreCase = true) }, "no header of the Operator's presence: ${request.headers.names()}")
    }

    @OptIn(SensitiveApi::class)
    private fun revealed(secret: Secret): String = secret.reveal()

    // ---- The getter --------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-008"])
    @DisplayName("BR-008: the part declares its Bank Consent operations, and reading them, also from the part the app builds, never fails")
    fun bankConsentIsDeclared() {
        val built = EnableBankingDataProvider(CapturingLog(detailed = false))
        assertNotNull(built.bankConsent)
        assertTrue(built.bankConsent === built.bankConsent)
        assertTrue(built.profile.needsBankConsent)
    }

    // ---- The Banks ---------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: the Banks of a country are one signed GET /aspsps with the country, personal and AIS, admitted as a request of kind Other with the Operator present, without a header of presence")
    fun banksAreOneRequest() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        val gate = RecordingRequestGate()

        call { consent.banks("FI", context(gate)) }

        val sent = only()
        assertEquals("GET", sent.method)
        assertEquals("/aspsps?country=FI&psu_type=personal&service=AIS", sent.path)
        assertEquals(0L, sent.bodySize)
        assertSignedAndWithoutPresence(sent)
        assertEquals(listOf(attended(null)), gate.requests)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: each Bank's reference is its name and country; a Bank without a name, with a reference longer than a Bank's name holds, or without a maximum validity is left out, and each reference is listed once")
    fun banksAreListedByNameAndCountry() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)

        val listed = call { consent.banks("FI", context()) }

        assertEquals(
            BankList.Listed(
                listOf(
                    BankChoice("Aurora Sparbank (FI)", "Aurora Sparbank"),
                    BankChoice("Kuusi Pankki (FI)", "Kuusi Pankki"),
                    BankChoice("Old (Mill) Bank (FI)", "Old (Mill) Bank"),
                ),
            ),
            listed,
        )
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: an empty list is a list without Banks; a list that cannot be read is an unreadable answer, never a guess")
    fun emptyAndMalformedLists() {
        server.answer("GET", "/aspsps", """{"aspsps":[]}""")
        assertEquals(BankList.Listed(emptyList()), call { consent.banks("FI", context()) })
        listOf("not json", "[]", """{"aspsps":{}}""", """{"banks":[]}""", "").forEach { body ->
            server.answer("GET", "/aspsps", body)
            assertEquals(BankList.TransientFailure(FailureKind.UnreadableAnswer), call { consent.banks("FI", context()) }, body)
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A3: Data Provider Refuses", businessRules = ["BR-010"])
    @DisplayName("A3, A8: a refusal of the list is read from its error code, not its status; an answer without a code that says the service cannot serve is ServiceUnavailable")
    fun banksErrors() {
        val cases = listOf(
            Triple(401, "UNAUTHORIZED_ACCESS", BankList.Refused(ConsentFailureKind.CredentialsRefused)),
            Triple(500, "AUTHORIZATION_NOT_PROVIDED", BankList.Refused(ConsentFailureKind.CredentialsRefused)),
            Triple(422, "WRONG_REQUEST_PARAMETERS", BankList.Refused(ConsentFailureKind.RefusedByDataProvider)),
            Triple(200, "ACCESS_DENIED", BankList.Refused(ConsentFailureKind.RefusedByDataProvider)),
        )
        cases.forEach { (status, code, expected) ->
            server.error("GET", "/aspsps", status, code)
            assertEquals(expected, call { consent.banks("FI", context()) }, code)
        }
        server.answer("GET", "/aspsps", MockResponse().setResponseCode(503).setBody("<html>invented outage</html>"))
        assertEquals(BankList.TransientFailure(FailureKind.ServiceUnavailable), call { consent.banks("FI", context()) })
        server.answer("GET", "/aspsps", MockResponse().setResponseCode(404).setBody("invented"))
        assertEquals(BankList.TransientFailure(FailureKind.UnreadableAnswer), call { consent.banks("FI", context()) })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A3: Data Provider Refuses", businessRules = ["BR-010"])
    @DisplayName("BR-010: a country that is not two capital letters, or a stored credential that cannot be read, is refused without asking the gate or sending anything")
    fun banksRefusedUnsent() {
        val gate = RecordingRequestGate()
        assertEquals(BankList.Refused(ConsentFailureKind.RefusedByDataProvider), call { consent.banks("fi", context(gate)) })
        assertEquals(BankList.Refused(ConsentFailureKind.RefusedByDataProvider), call { consent.banks("FIN", context(gate)) })
        assertEquals(BankList.Refused(ConsentFailureKind.CredentialsRefused), call { consent.banks("FI", context(gate, credential = null)) })
        assertEquals(BankList.Refused(ConsentFailureKind.CredentialsRefused), call { consent.banks("FI", context(gate, credential = Secret("{}"))) })
        assertEquals(emptyList<RequestSummary>(), gate.requests)
        assertEquals(0, server.received.size)
    }

    // ---- Starting ----------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-011"], requirements = ["C-007"])
    @DisplayName("Step 3, BR-004: starting asks the list again, then sends POST /auth with the Bank, personal, the return address, the state and the Bank's maximum validity less the request timeout, each admitted with the Operator present")
    fun startSendsTheListThenAuth() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        server.answer("POST", "/auth", ConsentServer.started)
        val gate = RecordingRequestGate()

        val started = call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context(gate)) }

        val (list, auth) = server.received.toList()
        assertEquals("GET", list.method)
        assertEquals("/aspsps?country=FI&psu_type=personal&service=AIS", list.path)
        assertSignedAndWithoutPresence(list)
        assertEquals("POST", auth.method)
        assertEquals("/auth", auth.path)
        assertEquals("application/json", auth.getHeader("Content-Type"))
        assertSignedAndWithoutPresence(auth)
        assertEquals(
            """{"access":{"valid_until":"2027-05-27T09:59:55.000000+00:00"},"aspsp":{"name":"Aurora Sparbank","country":"FI"},""" +
                """"state":"$STATE","redirect_url":"$RETURN_ADDRESS","psu_type":"personal"}""",
            auth.body.readUtf8(),
        )
        assertEquals(listOf(attended(BANK), attended(BANK)), gate.requests)
        assertTrue(started is ConsentStart.Started, "was $started")
        started as ConsentStart.Started
        assertEquals(CONSENT_ADDRESS, started.consentAddress)
        val kept = ConsentHandle.read(started.handle)!!
        assertEquals(BANK, kept.bankReference)
        assertEquals(ConsentServer.STARTED_REQUEST_ID, kept.requestId)
        assertEquals(listOf("Psu-User-Agent"), kept.psuHeaders)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004: the validity asked never exceeds the Bank's maximum when the request arrives: it is counted from the moment it is sent, less the request timeout, and never rounded up")
    fun validityIsTheBanksMaximumLessTheTimeout() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        server.answer("POST", "/auth", ConsentServer.started)
        val ticking = EnableBankingDataProvider(log, server.url(), OkHttpClient(), Clock.fixed(Instant.parse("2026-11-28T10:00:00.987654321Z"), ZoneOffset.UTC))

        call { ticking.bankConsent.startConsentRequest("Kuusi Pankki (FI)", RETURN_ADDRESS, Secret(STATE), context(timeout = Duration.ofSeconds(30))) }

        val body = server.received.last().body.readUtf8()
        assertTrue(body.startsWith("""{"access":{"valid_until":"2027-02-26T09:59:30.987654+00:00"}"""), body)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004: the validity is counted from the earlier of the device's clock and the time Enable Banking gave with the list of Banks just before, else from the device's clock")
    fun validityCountsFromTheEarlierClock() {
        fun asked(device: String, date: String?): String {
            server.received.clear()
            val listing = MockResponse().setHeader("Content-Type", "application/json").setBody(ConsentServer.banksOfFinland)
            server.answer("GET", "/aspsps", if (date == null) listing else listing.setHeader("Date", date))
            server.answer("POST", "/auth", ConsentServer.started)
            val clocked = EnableBankingDataProvider(log, server.url(), OkHttpClient(), Clock.fixed(Instant.parse(device), ZoneOffset.UTC))
            call { clocked.bankConsent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) }
            return server.received.last().body.readUtf8().substringAfter("\"valid_until\":\"").substringBefore('"')
        }
        assertEquals("2027-05-27T09:59:55.000000+00:00", asked("2026-11-28T10:05:00Z", "Sat, 28 Nov 2026 10:00:00 GMT"), "the device's clock ahead")
        assertEquals("2027-05-27T09:54:55.000000+00:00", asked("2026-11-28T09:55:00Z", "Sat, 28 Nov 2026 10:00:00 GMT"), "the device's clock behind")
        assertEquals("2027-05-27T10:04:55.000000+00:00", asked("2026-11-28T10:05:00Z", null), "no time given")
        assertEquals("2027-05-27T10:04:55.000000+00:00", asked("2026-11-28T10:05:00Z", "yesterday"), "a time that cannot be read")
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A3: Data Provider Refuses", businessRules = ["BR-010"])
    @DisplayName("A3: a Bank that is no longer listed, as after a rebrand, is refused before anything is started; a reference that names no Bank is refused unsent")
    fun bankNoLongerListedIsRefused() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        server.answer("POST", "/auth", ConsentServer.started)

        assertEquals(ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider), call { consent.startConsentRequest("Renamed Bank (FI)", RETURN_ADDRESS, Secret(STATE), context()) })
        assertEquals(ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider), call { consent.startConsentRequest("No Validity Bank (FI)", RETURN_ADDRESS, Secret(STATE), context()) })
        assertEquals(listOf("GET", "GET"), server.received.map { it.method })
        listOf("Aurora Sparbank", "Aurora Sparbank (fi)", "(FI)", " (FI)", "").forEach { reference ->
            assertEquals(ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider), call { consent.startConsentRequest(reference, RETURN_ADDRESS, Secret(STATE), context()) }, reference)
        }
        assertEquals(2, server.received.size)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A3: Data Provider Refuses", businessRules = ["BR-011"])
    @DisplayName("A3, BR-011: each refusal of the start is read from its error code: a refused return address, the Bank or the request refused, the credentials refused")
    fun startErrors() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        val cases = listOf(
            "REDIRECT_URI_NOT_ALLOWED" to ConsentStart.Refused(ConsentFailureKind.ReturnAddressRefused),
            "WRONG_ASPSP_PROVIDED" to ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider),
            "WRONG_REQUEST_PARAMETERS" to ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider),
            "UNAUTHORIZED_ACCESS" to ConsentStart.Refused(ConsentFailureKind.CredentialsRefused),
            "AUTHORIZATION_NOT_PROVIDED" to ConsentStart.Refused(ConsentFailureKind.CredentialsRefused),
            "ASPSP_ERROR" to ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider),
        )
        cases.forEach { (code, expected) ->
            server.error("POST", "/auth", 422, code)
            assertEquals(expected, call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) }, code)
        }
        server.error("GET", "/aspsps", 401, "UNAUTHORIZED_ACCESS")
        assertEquals(ConsentStart.Refused(ConsentFailureKind.CredentialsRefused), call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached", businessRules = ["BR-006"])
    @DisplayName("A8: a start Enable Banking cannot serve, or whose answer gives no address of https to open, is not started; nothing is guessed")
    fun startNotReadable() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        val cases = listOf(
            MockResponse().setResponseCode(500).setBody("invented") to ConsentStart.TransientFailure(FailureKind.ServiceUnavailable),
            MockResponse().setResponseCode(429).setBody("{}") to ConsentStart.TransientFailure(FailureKind.ServiceUnavailable),
            MockResponse().setBody("""{"authorization_id":"x"}""") to ConsentStart.TransientFailure(FailureKind.UnreadableAnswer),
            MockResponse().setBody("""{"url":""}""") to ConsentStart.TransientFailure(FailureKind.UnreadableAnswer),
            MockResponse().setBody("""{"url":"http://auth.example/plain"}""") to ConsentStart.TransientFailure(FailureKind.UnreadableAnswer),
            MockResponse().setBody("""{"url":"javascript:alert(1)"}""") to ConsentStart.TransientFailure(FailureKind.UnreadableAnswer),
            MockResponse().setBody("not json") to ConsentStart.TransientFailure(FailureKind.UnreadableAnswer),
        )
        cases.forEach { (response, expected) ->
            server.answer("POST", "/auth", response)
            assertEquals(expected, call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) })
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A9: Held Back by the Request Gate")
    @DisplayName("A9: a refusal of the gate at the second request of a start sends no POST /auth")
    fun gateRefusesTheSecondRequest() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        server.answer("POST", "/auth", ConsentServer.started)
        var asked = 0
        val gate = RequestGate { if (++asked == 1) Admission.Allowed else Admission.Refused(RefusalScope.Bank, RefusalReason.BackOff) }

        val started = call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context(gate)) }

        assertEquals(ConsentStart.StoppedByGate(RefusalReason.BackOff), started)
        assertEquals(listOf("GET"), server.received.map { it.method })
    }

    // ---- The answer --------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002", "BR-011"])
    @DisplayName("BR-002: the state is read from the query of the address returned, decoded, at the application's own address and at an https one; two, an empty one, none or one that cannot be decoded give none")
    fun stateIsRead() {
        assertEquals(STATE, revealed(consent.stateOf(granting)!!))
        assertEquals("a b/c", revealed(consent.stateOf(returned("code=x&state=a%20b%2Fc", "https://household.example/back"))!!))
        assertEquals(STATE, revealed(consent.stateOf(returned("state=$STATE#fragment=y"))!!))
        listOf("state=one&code=x&state=two", "code=x&state=", "code=x", "code=x&state=%zz", "").forEach { query ->
            assertNull(consent.stateOf(returned(query)), query)
        }
        assertNull(consent.stateOf(slussen.pluginapi.provider.ConsentAnswer(Secret("no query at all"))))
        assertNull(consent.stateOf(returned("code=x#state=$STATE")), "a state given only in the fragment")
        assertEquals(0, server.received.size)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A1: Authentication Not Completed")
    @DisplayName("A1, A2: an answer with an error is NotCompleted; one with neither code nor error, with both, or with two codes is not understood; none sends anything")
    fun answersThatCompleteNothing() {
        val gate = RecordingRequestGate()
        assertEquals(ConsentCompletion.NotCompleted, call { consent.completeConsent(returned("error=access_denied&error_description=invented&state=$STATE"), handle, context(gate)) })
        assertEquals(ConsentCompletion.NotCompleted, call { consent.completeConsent(returned("state=$STATE&error=server_error"), handle, context(gate)) })
        listOf("state=$STATE", "error=access_denied&code=$CODE&state=$STATE", "code=a&code=b&state=$STATE", "code=&state=$STATE", "code=$CODE", "code=$CODE&state=a&state=b").forEach { query ->
            assertEquals(ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood), call { consent.completeConsent(returned(query), handle, context(gate)) }, query)
        }
        assertEquals(ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood), call { consent.completeConsent(granting, null, context(gate)) })
        assertEquals(ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood), call { consent.completeConsent(granting, Secret("not a handle"), context(gate)) })
        assertEquals(emptyList<RequestSummary>(), gate.requests)
        assertEquals(0, server.received.size)
    }

    // ---- Completing --------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("Step 6: completing sends POST /sessions with the code, signed and without a header of presence, admitted for the Bank with the Operator present")
    fun completeSendsTheCode() {
        server.answer("POST", "/sessions", ConsentServer.session)
        val gate = RecordingRequestGate()

        call { consent.completeConsent(granting, handle, context(gate)) }

        val sent = only()
        assertEquals("POST", sent.method)
        assertEquals("/sessions", sent.path)
        assertEquals("application/json", sent.getHeader("Content-Type"))
        assertEquals("""{"code":"$CODE"}""", sent.body.readUtf8())
        assertSignedAndWithoutPresence(sent)
        assertEquals(listOf(attended(BANK)), gate.requests)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("Steps 7-8, BR-004: the session's validity as Enable Banking reports it, each entry under its lasting reference with its description, kind, currency and number; an entry without one is not delivered")
    fun completionIsRead() {
        server.answer("POST", "/sessions", ConsentServer.session)

        val completed = call { consent.completeConsent(granting, handle, context()) } as ConsentCompletion.Completed

        assertEquals(Instant.parse("2027-05-27T09:59:55Z"), completed.expiresAt)
        val delivered = completed.bankAccounts
        assertEquals(listOf(HASH_1, HASH_2, HASH_4, HASH_5), delivered.map { it.providerReference })
        assertTrue(delivered.all { it.bankReference == BANK && it.result == BankAccountResult.NotRead(NotReadCause.NotSelected) })
        assertEquals(listOf("Household", "Savings plus", null, "Transit"), delivered.map { it.name })
        assertEquals(listOf("current", "savings", "credit card", "TRAN"), delivered.map { it.kind })
        assertEquals(listOf(CurrencyCode("EUR"), null, CurrencyCode("SEK"), CurrencyCode("EUR")), delivered.map { it.currency })
        assertEquals(listOf(ConsentServer.IBAN, ConsentServer.OTHER_NUMBER, null, null), delivered.map { it.number?.delivered })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-009"], requirements = ["NFR-008"])
    @DisplayName("Step 8: the Bank Session holds the session, the Bank's required headers and each entry's session identifier by its lasting reference; an entry without one is offered but cannot be read")
    fun sessionKeepsWhatAFetchNeeds() {
        server.answer("POST", "/sessions", ConsentServer.session)

        val completed = call { consent.completeConsent(granting, handle, context()) } as ConsentCompletion.Completed

        val kept = BankSessionValue.read(completed.session)!!
        assertEquals(SESSION_ID, kept.sessionId)
        assertEquals(BANK, kept.bankReference)
        assertEquals(listOf("Psu-User-Agent"), kept.psuHeaders)
        assertEquals(mapOf(HASH_1 to UID_1, HASH_4 to UID_4, HASH_5 to UID_5), kept.uids)
        assertEquals(ConsentSession.MASK, completed.session.toString())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-009"], requirements = ["NFR-008"])
    @DisplayName("Phase E5 (ADR-001 §4.1.1 \"The balance\"): the Bank Session keeps the currency a Fetch chooses each readable Bank Account's balance in, none for a card or an unknown currency")
    fun sessionKeepsTheCurrencyOfEachBalance() {
        server.answer("POST", "/sessions", ConsentServer.session)

        val completed = call { consent.completeConsent(granting, handle, context()) } as ConsentCompletion.Completed

        assertEquals(mapOf(HASH_1 to "EUR", HASH_5 to "EUR"), BankSessionValue.read(completed.session)!!.balanceCurrencies)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("Step 8: two entries with one lasting reference are both delivered and neither can be read; what does not fit the Bank Session's length is offered but not readable")
    fun sessionStaysWithinItsLength() {
        val twins = ConsentServer.session.replace(HASH_5, HASH_4)
        server.answer("POST", "/sessions", twins)
        val withTwins = call { consent.completeConsent(granting, handle, context()) } as ConsentCompletion.Completed
        assertEquals(listOf(HASH_1, HASH_2, HASH_4, HASH_4), withTwins.bankAccounts.map { it.providerReference })
        assertEquals(mapOf(HASH_1 to UID_1), BankSessionValue.read(withTwins.session)!!.uids)

        val many = (1..60).joinToString(",") { """{"uid":"${"u".repeat(36)}$it","identification_hash":"${"h".repeat(60)}$it","cash_account_type":"CACC","currency":"EUR"}""" }
        server.answer("POST", "/sessions", """{"session_id":"$SESSION_ID","accounts":[$many],"aspsp":{"name":"Aurora Sparbank","country":"FI"},"psu_type":"personal","access":{"valid_until":"${ConsentServer.VALID_UNTIL}"}}""")
        val crowded = call { consent.completeConsent(granting, handle, context()) } as ConsentCompletion.Completed
        assertEquals(60, crowded.bankAccounts.size)
        @OptIn(SensitiveApi::class)
        val stored = crowded.session.reveal()
        assertTrue(stored.length <= BankSessionValue.MAX_LENGTH, "${stored.length}")
        val uids = BankSessionValue.read(crowded.session)!!.uids
        assertTrue(uids.size in 1 until 60, "${uids.size}")
        uids.forEach { (reference, uid) -> assertEquals(reference.removePrefix("h".repeat(60)), uid.removePrefix("u".repeat(36))) }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A4: Data Provider Does Not Confirm the Consent")
    @DisplayName("A2, A4: each refusal of the completion is read from its error code: an expired, wrong or used code is not understood, the credentials refused, another code a refusal")
    fun completionErrors() {
        val cases = listOf(
            "EXPIRED_AUTHORIZATION_CODE" to ConsentFailureKind.AnswerNotUnderstood,
            "WRONG_AUTHORIZATION_CODE" to ConsentFailureKind.AnswerNotUnderstood,
            "ALREADY_AUTHORIZED" to ConsentFailureKind.AnswerNotUnderstood,
            "UNAUTHORIZED_ACCESS" to ConsentFailureKind.CredentialsRefused,
            "AUTHORIZATION_NOT_PROVIDED" to ConsentFailureKind.CredentialsRefused,
            "ASPSP_ERROR" to ConsentFailureKind.RefusedByDataProvider,
            "WRONG_REQUEST_PARAMETERS" to ConsentFailureKind.RefusedByDataProvider,
        )
        cases.forEach { (code, kind) ->
            server.error("POST", "/sessions", 400, code)
            assertEquals(ConsentCompletion.NotConfirmed(kind), call { consent.completeConsent(granting, handle, context()) }, code)
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8: a completion Enable Banking cannot serve, or whose answer cannot be read, is never completed by guessing")
    fun completionNotReadable() {
        val good = ConsentServer.session
        val cases = listOf(
            MockResponse().setResponseCode(502).setBody("invented") to FailureKind.ServiceUnavailable,
            MockResponse().setBody("not json") to FailureKind.UnreadableAnswer,
            MockResponse().setBody(good.replace(""""session_id":"$SESSION_ID",""", "")) to FailureKind.UnreadableAnswer,
            MockResponse().setBody(good.replace(ConsentServer.VALID_UNTIL, "next spring")) to FailureKind.UnreadableAnswer,
            MockResponse().setBody(good.replace(ConsentServer.VALID_UNTIL, "2026-11-28T09:00:00+00:00")) to FailureKind.UnreadableAnswer,
            MockResponse().setBody(good.replace(""""name":"Aurora Sparbank","country":"FI"""", """"name":"Kuusi Pankki","country":"FI"""")) to FailureKind.UnreadableAnswer,
            MockResponse().setBody(good.replace(""""accounts":[""", """"accounts":{"x":[""").replace("""],
             "aspsp"""", """]},
             "aspsp"""")) to FailureKind.UnreadableAnswer,
        )
        cases.forEachIndexed { index, (response, kind) ->
            server.answer("POST", "/sessions", response)
            assertEquals(ConsentCompletion.TransientFailure(kind), call { consent.completeConsent(granting, handle, context()) }, "case $index")
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8: a completion Enable Banking accepted but Slussen cannot use ends that session once, through the gate with the Operator present, and still answers as before; nothing of it is kept (S-B-53)")
    fun unusableCompletionEndsItsSession() {
        val good = ConsentServer.session
        val unusable = listOf(
            good.replace(ConsentServer.VALID_UNTIL, "next spring"),
            good.replace(ConsentServer.VALID_UNTIL, "2026-11-28T09:00:00+00:00"),
            good.replace(""""name":"Aurora Sparbank","country":"FI"""", """"name":"Kuusi Pankki","country":"FI""""),
            """{"session_id":"$SESSION_ID","accounts":{"x":[]},"aspsp":{"name":"Aurora Sparbank","country":"FI"},"psu_type":"personal","access":{"valid_until":"${ConsentServer.VALID_UNTIL}"}}""",
        )
        server.answer("DELETE", "/sessions/$SESSION_ID", "{}")
        unusable.forEachIndexed { index, body ->
            server.received.clear()
            server.answer("POST", "/sessions", body)
            val gate = RecordingRequestGate()
            assertEquals(ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer), call { consent.completeConsent(granting, handle, context(gate)) }, "case $index")
            assertEquals(listOf("POST /sessions", "DELETE /sessions/$SESSION_ID"), server.received.map { "${it.method} ${it.path}" }, "case $index")
            assertEquals(listOf(attended(BANK), attended(BANK)), gate.requests, "case $index")
        }
        server.received.clear()
        server.answer("POST", "/sessions", good.replace(""""session_id":"$SESSION_ID",""", "").replace(ConsentServer.VALID_UNTIL, "next spring"))
        assertEquals(ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer), call { consent.completeConsent(granting, handle, context()) })
        assertEquals(listOf("POST"), server.received.map { it.method }, "no session to end without its identifier")
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8: ending an unusable session that fails, or that the gate holds back, leaves the outcome as it is")
    fun unusableCompletionOutcomeStays() {
        server.answer("POST", "/sessions", ConsentServer.session.replace(ConsentServer.VALID_UNTIL, "next spring"))
        server.error("DELETE", "/sessions/$SESSION_ID", 500, "ASPSP_ERROR")
        assertEquals(ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer), call { consent.completeConsent(granting, handle, context()) })
        server.answer("DELETE", "/sessions/$SESSION_ID", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer), call { consent.completeConsent(granting, handle, context()) })
        server.received.clear()
        var asked = 0
        val gate = RequestGate { if (++asked == 1) Admission.Allowed else Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached) }
        assertEquals(ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer), call { consent.completeConsent(granting, handle, context(gate)) })
        assertEquals(listOf("POST"), server.received.map { it.method })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A4: Data Provider Does Not Confirm the Consent")
    @DisplayName("A4: a completion refused without a code, by a status in the 400s other than the one that asks to wait, is a refusal: nothing was granted")
    fun completionRefusedWithoutACode() {
        listOf(400, 401, 403, 404, 422).forEach { status ->
            server.answer("POST", "/sessions", MockResponse().setResponseCode(status).setBody("invented"))
            assertEquals(ConsentCompletion.NotConfirmed(ConsentFailureKind.RefusedByDataProvider), call { consent.completeConsent(granting, handle, context()) }, "$status")
        }
        server.answer("POST", "/sessions", MockResponse().setResponseCode(429).setBody("{}"))
        assertEquals(ConsentCompletion.TransientFailure(FailureKind.ServiceUnavailable), call { consent.completeConsent(granting, handle, context()) })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8: a redirect of the start or the completion is an unreadable answer, and nothing is sent to its target")
    fun redirectsAreNotFollowed() {
        val elsewhere = ConsentServer()
        try {
            server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
            server.answer("POST", "/auth", MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/auth").toString()))
            server.answer("POST", "/sessions", MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/sessions").toString()))
            assertEquals(ConsentStart.TransientFailure(FailureKind.UnreadableAnswer), call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) })
            assertEquals(ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer), call { consent.completeConsent(granting, handle, context()) })
            assertEquals(0, elsewhere.received.size, "the redirect's target received nothing")
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8: a start whose connection is closed before an answer is a lost connection, sent once; one answered too slowly ends within the request timeout")
    fun startLostOrSlow() {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        server.answer("POST", "/auth", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ConsentStart.TransientFailure(FailureKind.ConnectionLost), call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) })
        assertEquals(1, server.received.count { it.method == "POST" }, "no request is sent again without the gate")
        server.answer("POST", "/auth", MockResponse().setBody(ConsentServer.started).setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(
            ConsentStart.TransientFailure(FailureKind.Timeout),
            call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context(timeout = Duration.ofMillis(500))) },
        )
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8, NFR-016: a completion that is never answered ends within the request timeout as a timeout")
    fun completionTimesOut() {
        server.answer("POST", "/sessions", MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val completed = call { consent.completeConsent(granting, handle, context(timeout = Duration.ofMillis(500))) }

        assertEquals(ConsentCompletion.TransientFailure(FailureKind.Timeout), completed)
    }

    // ---- Revoking ----------------------------------------------------------------------------------------------------

    private val session = BankSessionValue(SESSION_ID, BANK, listOf("Psu-User-Agent"), mapOf(HASH_1 to UID_1)).toSession()

    @Test
    @UseCase(id = "UC-007", scenario = "A7: Revoke a Bank Consent", businessRules = ["BR-007"])
    @DisplayName("A7: revoking sends DELETE /sessions/{session}, signed, admitted for the Bank with the Operator present; its success ends the session at Enable Banking, which ends the Bank's consent only where it can")
    fun revokeEndsTheSession() {
        server.answer("DELETE", "/sessions/$SESSION_ID", "{}")
        val gate = RecordingRequestGate()

        val ended = call { consent.revoke(session, context(gate)) }

        val sent = only()
        assertEquals("DELETE", sent.method)
        assertEquals("/sessions/$SESSION_ID", sent.path)
        assertEquals(0L, sent.bodySize)
        assertSignedAndWithoutPresence(sent)
        assertEquals(listOf(attended(BANK)), gate.requests)
        assertEquals(RevokeResult.EndedAtDataProvider, ended)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A7: Revoke a Bank Consent", businessRules = ["BR-007"])
    @DisplayName("A7: any error, also for a session that already ended, a lost connection or a session that cannot be read, is NotConfirmed")
    fun revokeNotConfirmed() {
        listOf("SESSION_DOES_NOT_EXIST", "CLOSED_SESSION", "EXPIRED_SESSION", "UNAUTHORIZED_ACCESS").forEach { code ->
            server.error("DELETE", "/sessions/$SESSION_ID", 404, code)
            assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(session, context()) }, code)
        }
        server.answer("DELETE", "/sessions/$SESSION_ID", MockResponse().setResponseCode(500))
        assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(session, context()) })
        server.answer("DELETE", "/sessions/$SESSION_ID", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(session, context()) })
        val sentBefore = server.received.size
        assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(ConsentSession("not a session"), context()) })
        assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(session, context(credential = null)) })
        assertEquals(sentBefore, server.received.size)
    }

    // ---- Presence and the return address -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"])
    @DisplayName("BR-012: a Bank's requests can show the Operator's presence only when it requires no PSU header other than the user agent and the language; a session that cannot be read cannot")
    fun presenceFollowsTheBanksRequiredHeaders() {
        fun shows(headers: List<String>) = consent.showsPresence(BankSessionValue(SESSION_ID, BANK, headers, emptyMap()).toSession())
        assertTrue(shows(emptyList()))
        assertTrue(shows(listOf("Psu-User-Agent")))
        assertTrue(shows(listOf("psu-accept-language", "Psu-User-Agent")))
        assertFalse(shows(listOf("Psu-Ip-Address")))
        assertFalse(shows(listOf("Psu-User-Agent", "Psu-Geo-Location")))
        assertFalse(consent.showsPresence(ConsentSession("not a session")))
        assertEquals(0, server.received.size)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: the return address registered with the application is an optional text field kept with the credentials; empty, missing or unreadable means none, so the core uses the application's own (UC-007 BR-011)")
    fun returnAddressIsKeptWithTheCredentials() {
        val field = provider.credentials.form.fields.single { it.key == EnableBankingCredentials.RETURN_ADDRESS }
        assertEquals(FieldKind.Text, field.kind)
        assertTrue(field.optional)
        server.answer("GET", "/application", """{"name":"Invented household app"}""")
        fun checked(address: String?): Secret {
            val values = mapOf(EnableBankingCredentials.APPLICATION_ID to Secret(APPLICATION_ID), EnableBankingCredentials.PRIVATE_KEY to Secret(ConsentServer.pem)) +
                (address?.let { mapOf(EnableBankingCredentials.RETURN_ADDRESS to Secret(it)) } ?: emptyMap())
            return (call { provider.credentials.check(CredentialInput(values), TestRequestContext()) } as CredentialCheck.Accepted).credential
        }
        assertEquals("https://household.example/back", consent.registeredReturnAddress(checked(" https://household.example/back ")))
        assertNull(consent.registeredReturnAddress(checked("   ")))
        assertNull(consent.registeredReturnAddress(checked(null)))
        assertEquals("https://household.example/back", consent.registeredReturnAddress(ConsentServer.credential("https://household.example/back")))
        assertNull(consent.registeredReturnAddress(ConsentServer.credential()))
        assertNull(consent.registeredReturnAddress(null))
        assertNull(consent.registeredReturnAddress(Secret("not json")))
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused", businessRules = ["BR-002"])
    @DisplayName("A1: a return address that is not an address with a scheme and without whitespace is refused without contacting Enable Banking; the application's kind of address and an https one pass")
    fun returnAddressIsChecked() {
        server.answer("GET", "/application", """{"name":"Invented household app"}""")
        fun checked(address: String) = call {
            provider.credentials.check(
                CredentialInput(
                    mapOf(
                        EnableBankingCredentials.APPLICATION_ID to Secret(APPLICATION_ID),
                        EnableBankingCredentials.PRIVATE_KEY to Secret(ConsentServer.pem),
                        EnableBankingCredentials.RETURN_ADDRESS to Secret(address),
                    ),
                ),
                TestRequestContext(),
            )
        }
        assertEquals(CredentialCheck.Refused, checked("my bank page"))
        assertEquals(CredentialCheck.Refused, checked("household.example/back"))
        assertEquals(CredentialCheck.Refused, checked("https://household.example/my page"))
        assertEquals(0, server.received.size, "nothing is sent for an address that cannot be valid")
        assertTrue(checked("invented.app://consent") is CredentialCheck.Accepted)
        assertTrue(checked("https://household.example/back") is CredentialCheck.Accepted)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-005"])
    @DisplayName("BR-005: a stored credential that cannot be read is refused at every operation without sending anything")
    fun unreadableCredentialSendsNothing() {
        val gate = RecordingRequestGate()
        val broken = context(gate, credential = Secret("""{"applicationId":"x","privateKey":"not a key"}"""))
        assertEquals(ConsentStart.Refused(ConsentFailureKind.CredentialsRefused), call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), broken) })
        assertEquals(ConsentCompletion.NotConfirmed(ConsentFailureKind.CredentialsRefused), call { consent.completeConsent(granting, handle, broken) })
        assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(session, broken) })
        assertEquals(emptyList<RequestSummary>(), gate.requests)
        assertEquals(0, server.received.size)
    }

    // ---- Nothing sensitive leaves, nothing is thrown -----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-006"], requirements = ["NFR-008", "NFR-012"])
    @DisplayName("BR-006, NFR-008: no log line, with Detailed Logging on or off, and no toString() of a result shows the token, code, state, consent address, session or a Bank Account's identifier")
    fun nothingSensitiveIsLogged() {
        listOf(true, false).forEach { detailed ->
            val written = CapturingLog(detailed)
            val logged = EnableBankingDataProvider(written, server.url(), OkHttpClient(), Clock.fixed(now, ZoneOffset.UTC)).bankConsent
            val shown = mutableListOf<String>()
            val gate = RecordingRequestGate()
            val registered = "https://household.example/PLANTED-RETURN-3d1f"
            fun context(gate: RequestGate) = context(gate, credential = ConsentServer.credential(registered))
            server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
            server.answer("POST", "/auth", ConsentServer.started)
            server.answer("POST", "/sessions", ConsentServer.session)
            server.answer("DELETE", "/sessions/$SESSION_ID", "{}")
            shown += call { logged.banks("FI", context(gate)) }.toString()
            shown += logged.registeredReturnAddress(ConsentServer.credential(registered)).orEmpty().replace(registered, "")
            val started = call { logged.startConsentRequest(BANK, registered, Secret(STATE), context(gate)) }
            shown += started.toString()
            shown += (started as ConsentStart.Started).handle.toString()
            val completed = call { logged.completeConsent(granting, started.handle, context(gate)) }
            shown += completed.toString()
            shown += call { logged.revoke((completed as ConsentCompletion.Completed).session, context(gate)) }.toString()
            server.error("POST", "/auth", 422, "REDIRECT_URI_NOT_ALLOWED")
            server.error("POST", "/sessions", 400, "WRONG_AUTHORIZATION_CODE")
            server.error("DELETE", "/sessions/$SESSION_ID", 404, "SESSION_DOES_NOT_EXIST")
            shown += call { logged.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context(gate)) }.toString()
            shown += call { logged.completeConsent(granting, handle, context(gate)) }.toString()
            shown += call { logged.completeConsent(returned("error=access_denied&state=$STATE"), handle, context(gate)) }.toString()
            shown += call { logged.revoke(session, context(gate)) }.toString()
            shown += gate.requests.toString()
            val tokens = server.received.mapNotNull { it.getHeader("Authorization")?.removePrefix("Bearer ") }
            assertTrue(written.lines.isNotEmpty(), "the operations are logged")
            assertEquals(detailed, written.lines.any { it.contains("HTTP status") })
            (ConsentServer.plantedInLogs + tokens + "PLANTED-RETURN-3d1f").forEach { planted ->
                written.lines.forEach { line -> assertFalse(planted in line, "logged with Detailed Logging $detailed: $line") }
            }
            (ConsentServer.planted + tokens + "PLANTED-RETURN-3d1f").forEach { planted ->
                shown.forEach { text -> assertFalse(planted in text, "shown: $text") }
            }
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached", requirements = ["NFR-004"])
    @DisplayName("A8, NFR-004: every operation the device refuses ends as a lost connection or not confirmed, throws nothing, and leaves no uncaught exception on any thread (ADR-001 §4.0, S-36)")
    fun deviceRefusalThrowsNothing() {
        val threads = RecordingRequestThreads()
        try {
            val refusing = OkHttpClient.Builder()
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw DeviceRefusal.exception() })
                .dispatcher(Dispatcher(threads))
                .proxy(Proxy.NO_PROXY)
                .build()
            val refused = EnableBankingDataProvider(log, "https://api.data-provider.invalid/".toHttpUrl(), refusing, Clock.fixed(now, ZoneOffset.UTC)).bankConsent
            val lost = FailureKind.ConnectionLost
            assertEquals(BankList.TransientFailure(lost), call { refused.banks("FI", context()) })
            assertEquals(ConsentStart.TransientFailure(lost), call { refused.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context()) })
            assertEquals(ConsentCompletion.TransientFailure(lost), call { refused.completeConsent(granting, handle, context()) })
            assertEquals(RevokeResult.NotConfirmed, call { refused.revoke(session, context()) })
            threads.awaitIdle(Duration.ofSeconds(2))
            assertEquals(emptyList<Throwable>(), threads.uncaught)
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("ADR-001 §4.0: a gate that fails is answered as a failure of the operation, never as an exception out of the part")
    fun failingGateThrowsNothing() {
        val failing = RequestGate { throw IllegalStateException("invented failure of the gate") }
        assertEquals(BankList.TransientFailure(FailureKind.ConnectionLost), call { consent.banks("FI", context(failing)) })
        assertEquals(ConsentStart.TransientFailure(FailureKind.ConnectionLost), call { consent.startConsentRequest(BANK, RETURN_ADDRESS, Secret(STATE), context(failing)) })
        assertEquals(ConsentCompletion.TransientFailure(FailureKind.ConnectionLost), call { consent.completeConsent(granting, handle, context(failing)) })
        assertEquals(RevokeResult.NotConfirmed, call { consent.revoke(session, context(failing)) })
        assertEquals(0, server.received.size)
    }

    /** A log that writes the detail only while Detailed Logging is on, as the app's adapter does (ADR-006 §1). */
    private class CapturingLog(private val detailed: Boolean) : SlussenLog {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun event(event: LogEvent, vararg facts: LogFact) {
            lines += "$event ${facts.toList()}"
        }

        override fun detail(event: LogEvent, message: () -> String) {
            if (detailed) lines += "$event ${message()}"
        }
    }
}
