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
import slussen.pluginapi.testfixtures.DataProviderContractTest
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The SimpleFIN Data Provider against the contract tests of ADR-001 §4.4, with invented recorded answers. */
class SimpleFinDataProviderTest : DataProviderContractTest() {
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
