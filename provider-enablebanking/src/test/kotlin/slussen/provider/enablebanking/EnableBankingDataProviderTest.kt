package slussen.provider.enablebanking

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The Enable Banking Data Provider against the contract tests of ADR-001 §4.4, with an invented key and answers. */
class EnableBankingDataProviderTest : DataProviderContractTest() {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val now = Instant.parse("2026-10-18T09:00:00Z")

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun provider() =
        EnableBankingDataProvider(SilentLog, server.url("/"), http, Clock.fixed(now, ZoneOffset.UTC))

    private fun input(pem: String = pkcs8Pem) = CredentialInput(
        mapOf(
            EnableBankingCredentials.APPLICATION_ID to Secret(APPLICATION_ID),
            EnableBankingCredentials.PRIVATE_KEY to Secret(pem),
        ),
    )

    private fun answering(response: MockResponse): CredentialScenario {
        server.enqueue(response)
        return CredentialScenario(provider().credentials, input())
    }

    override fun dataProvider(log: SlussenLog): DataProvider = EnableBankingDataProvider(log)

    override fun credentialsAccepted() = answering(MockResponse().setBody("""{"name":"Invented household app"}"""))

    override fun credentialsRefused() = answering(MockResponse().setResponseCode(401).setBody("invented refusal"))

    override fun credentialServiceUnavailable() = answering(MockResponse().setResponseCode(503))

    override fun credentialServiceNeverAnswering() = answering(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun requestsReceived(): Int = server.requestCount

    override fun runningCalls(): Int = http.dispatcher.runningCallsCount()

    override fun plantedValues(): List<String> = listOf(APPLICATION_ID, keyBody(pkcs8Pem).take(40))

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["C-008"])
    @DisplayName("BR-002: the application id and key file are checked with a request signed by that key")
    @OptIn(SensitiveApi::class)
    fun applicationIsCheckedWithASignedRequest() {
        val credentials = provider().credentials
        assertEquals(
            listOf(EnableBankingCredentials.APPLICATION_ID to FieldKind.Text, EnableBankingCredentials.PRIVATE_KEY to FieldKind.File),
            credentials.form.fields.map { it.key to it.kind },
        )
        server.enqueue(MockResponse().setBody("""{"name":"Invented household app"}"""))
        val result = runBlocking { credentials.check(input(), TestRequestContext()) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/application", request.requestUrl!!.encodedPath)
        assertSignedByTheApplication(request.getHeader("Authorization")!!.removePrefix("Bearer "))
        val kept = Json.parseToJsonElement((result as CredentialCheck.Accepted).credential.reveal()).jsonObject
        assertEquals(APPLICATION_ID, kept.getValue(EnableBankingCredentials.APPLICATION_ID).jsonPrimitive.content)
        assertEquals(pkcs8Pem, kept.getValue(EnableBankingCredentials.PRIVATE_KEY).jsonPrimitive.content)
    }

    @Test
    @DisplayName("BR-002: a key file in the PKCS#1 form is read too")
    fun pkcs1KeyFileIsRead() {
        server.enqueue(MockResponse().setBody("{}"))
        val result = runBlocking { provider().credentials.check(input(pkcs1Pem), TestRequestContext()) }
        assertTrue(result is CredentialCheck.Accepted, "was $result")
        assertSignedByTheApplication(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")!!.removePrefix("Bearer "))
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused", businessRules = ["BR-002"])
    @DisplayName("A1: a file that holds no private key is refused without contacting Enable Banking")
    fun unreadableKeyFileIsRefusedUnsent() {
        listOf("", "not a key", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----").forEach { pem ->
            val result = runBlocking { provider().credentials.check(input(pem), TestRequestContext()) }
            assertEquals(CredentialCheck.Refused, result)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: Enable Banking publishes no Request Limit, so its part declares a default of 50 the Operator can change between 10 and 500")
    fun declaresADefaultRequestLimit() {
        assertEquals(
            RequestLimitDeclaration.NotPublished(defaultRequests = 50, range = 10..500),
            EnableBankingDataProvider(SilentLog).profile.requestLimit,
        )
    }

    @Test
    @UseCase(id = "UC-012", businessRules = ["BR-011"])
    @DisplayName("BR-011: Enable Banking's part declares its name, source key, a Bank Consent and a default Fetch Cooldown of 360 minutes (ADR-001 §4.1, O-P2-42)")
    fun declaresItsProfile() {
        val profile = EnableBankingDataProvider(SilentLog).profile
        assertEquals("Enable Banking", profile.name)
        assertEquals("enable-banking", profile.sourceKey)
        assertEquals(360, profile.defaultFetchCooldownMinutes)
        assertEquals(true, profile.needsBankConsent)
    }

    private fun assertSignedByTheApplication(token: String) {
        val parts = token.split('.')
        assertEquals(3, parts.size)
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]))).jsonObject
        val claims = Json.parseToJsonElement(String(decoder.decode(parts[1]))).jsonObject
        assertEquals("RS256", header.getValue("alg").jsonPrimitive.content)
        assertEquals(APPLICATION_ID, header.getValue("kid").jsonPrimitive.content)
        assertEquals("enablebanking.com", claims.getValue("iss").jsonPrimitive.content)
        assertEquals("api.enablebanking.com", claims.getValue("aud").jsonPrimitive.content)
        assertEquals(now.epochSecond, claims.getValue("iat").jsonPrimitive.long)
        assertTrue(claims.getValue("exp").jsonPrimitive.long - now.epochSecond <= 86_400)
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(keys.public)
            update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
            verify(decoder.decode(parts[2]))
        }
        assertTrue(verified, "the signature verifies with the application's public key")
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }

    private companion object {
        const val APPLICATION_ID = "3c9e2b7a-0000-4000-8000-invented0001"

        val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val pkcs8Pem: String = pem("PRIVATE KEY", keys.private.encoded)

        val pkcs1Pem: String = pem("RSA PRIVATE KEY", pkcs1Of(keys.private.encoded))

        fun pem(kind: String, der: ByteArray): String =
            "-----BEGIN $kind-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
                "\n-----END $kind-----\n"

        fun keyBody(pem: String): String = pem.lines().drop(1).first()

        /** The PKCS#1 key inside a PKCS#8 one: the content of its octet string, after version and algorithm. */
        fun pkcs1Of(pkcs8: ByteArray): ByteArray {
            var at = 0
            fun skipHeader(): Int {
                at++ // tag
                val first = pkcs8[at++].toInt() and 0xff
                if (first < 0x80) return first
                var size = 0
                repeat(first and 0x7f) { size = (size shl 8) or (pkcs8[at++].toInt() and 0xff) }
                return size
            }
            skipHeader() // the outer sequence
            val version = skipHeader()
            at += version
            val algorithm = skipHeader()
            at += algorithm
            val size = skipHeader() // the octet string
            return pkcs8.copyOfRange(at, at + size)
        }
    }
}
