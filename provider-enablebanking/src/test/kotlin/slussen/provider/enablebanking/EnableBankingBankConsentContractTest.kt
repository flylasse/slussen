package slussen.provider.enablebanking

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.testfixtures.BankConsentContractTest
import slussen.pluginapi.testfixtures.ConsentAnswerScenario
import slussen.pluginapi.testfixtures.ConsentRequestScenario
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.provider.enablebanking.ConsentServer.Companion.BANK
import slussen.provider.enablebanking.ConsentServer.Companion.CODE
import slussen.provider.enablebanking.ConsentServer.Companion.RETURN_ADDRESS
import slussen.provider.enablebanking.ConsentServer.Companion.returned
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase E2's contract test of the Bank Consent operations (ADR-001 §4.4), run against the real Enable Banking part
 * through a local test server with invented answers, the malformed answers included (E2's review).
 */
@DisplayName("ADR-001 §4.4: Enable Banking's Bank Consent operations pass the contract test")
class EnableBankingBankConsentContractTest : BankConsentContractTest() {
    private val server = ConsentServer()
    private val now = Instant.parse("2026-11-28T10:00:00Z")
    private val handle = ConsentHandle(BANK, ConsentServer.STARTED_REQUEST_ID, listOf("Psu-User-Agent")).toSecret()

    @AfterEach
    fun stopServer() = server.shutdown()

    override fun dataProvider(log: SlussenLog): DataProvider =
        EnableBankingDataProvider(log, server.url(), okhttp3.OkHttpClient(), Clock.fixed(now, ZoneOffset.UTC))

    override fun credential(): Secret = ConsentServer.credential()

    override fun requestsReceived(): Int = server.received.size

    override fun banksListed(): String {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        return "FI"
    }

    override fun consentStarted(): ConsentRequestScenario {
        server.answer("GET", "/aspsps", ConsentServer.banksOfFinland)
        server.answer("POST", "/auth", ConsentServer.started)
        return ConsentRequestScenario(BANK, RETURN_ADDRESS)
    }

    override fun consentCompleted(): ConsentAnswerScenario {
        server.answer("POST", "/sessions", ConsentServer.session)
        return ConsentAnswerScenario(returned("code=$CODE&state=contract-c"), handle)
    }

    override fun consentCancelled() =
        ConsentAnswerScenario(returned("error=access_denied&error_description=invented+refusal&state=contract-a"), handle)

    override fun unreadableAnswer() = ConsentAnswerScenario(returned("state=contract-b"), handle)

    @OptIn(SensitiveApi::class)
    override fun answerCarrying(state: Secret): ConsentAnswer =
        returned("code=$CODE&state=" + URLEncoder.encode(state.reveal(), Charsets.UTF_8))

    override fun answerWithTwoStates(): ConsentAnswer = returned("state=one&code=$CODE&state=two")

    override fun answerWithEmptyState(): ConsentAnswer = returned("code=$CODE&state=")

    override fun answerWithErrorAndCode() = ConsentAnswerScenario(returned("error=access_denied&code=$CODE&state=contract-d"), handle)

    override fun sessionToEnd(): ConsentSession {
        server.answer("DELETE", "/sessions/${ConsentServer.SESSION_ID}", "{}")
        return BankSessionValue(ConsentServer.SESSION_ID, BANK, listOf("Psu-User-Agent"), mapOf(ConsentServer.HASH_1 to ConsentServer.UID_1)).toSession()
    }

    override fun plantedValues(): List<String> = ConsentServer.planted
}
