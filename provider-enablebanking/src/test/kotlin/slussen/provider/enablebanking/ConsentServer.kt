package slussen.provider.enablebanking

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import slussen.pluginapi.value.Secret
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local test server for Enable Banking's Bank Consent requests (ADR-001 §4.1.1), with invented answers only: each
 * route, a method and a path, answers what the test set for it; a route the test did not set answers 404 with a code
 * no rule maps, so that a request the plug-in should not send shows in the test.
 */
internal class ConsentServer {
    val server = MockWebServer()
    private val routes = mutableMapOf<String, MockResponse>()
    val received = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                return routes["${request.method} ${request.requestUrl!!.encodedPath}"]
                    ?: MockResponse().setResponseCode(404).setBody("""{"message":"not in this test","error":"TEST_ROUTE_NOT_SET"}""")
            }
        }
        server.start()
    }

    fun answer(method: String, path: String, response: MockResponse) {
        routes["$method $path"] = response
    }

    fun answer(method: String, path: String, body: String, status: Int = 200) =
        answer(method, path, MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    fun error(method: String, path: String, status: Int, code: String) =
        answer(method, path, """{"message":"invented message","code":$status,"error":"$code","detail":"invented detail"}""", status)

    fun url(path: String = "/") = server.url(path)

    fun shutdown() = server.shutdown()

    companion object {
        const val APPLICATION_ID = "3c9e2b7a-0000-4000-8000-invented0002"
        const val RETURN_ADDRESS = "invented.app.debug://consent"
        const val STATE = "PLANTED-STATE-q7Z2_xY9-aB4"
        const val CODE = "PLANTED-CODE-1e4c9b"
        const val STARTED_REQUEST_ID = "PLANTED-STARTED-5b0e"
        const val CONSENT_ADDRESS = "https://auth.example/ais/start?sessionid=PLANTED-ADDRESS-77c1"
        const val SESSION_ID = "PLANTED-SESSION-9f3d"
        const val UID_1 = "PLANTED-UID-0001"
        const val UID_4 = "PLANTED-UID-0004"
        const val UID_5 = "PLANTED-UID-0005"
        const val HASH_1 = "PLANTED-HASH-1.E8Gzhnns+4e3/Lqc="
        const val HASH_2 = "PLANTED-HASH-2.W2pd+x/y="
        const val HASH_4 = "PLANTED-HASH-4.Qm9vaw=="
        const val HASH_5 = "PLANTED-HASH-5.QmFuaw=="
        const val IBAN = "FI00PLANTED1234567890"
        const val OTHER_NUMBER = "PLANTED-BBAN-5566"
        const val BANK = "Aurora Sparbank (FI)"
        const val MAXIMUM_VALIDITY_SECONDS = 15_552_000L

        val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val pem: String = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"

        /** The stored credential as `check` keeps it (ADR-001 §4.5), with the registered return address when given. */
        fun credential(returnAddress: String? = null): Secret = Secret(
            buildString {
                append("""{"applicationId":"$APPLICATION_ID","privateKey":""")
                append(kotlinx.serialization.json.JsonPrimitive(pem).toString())
                if (returnAddress != null) append(""","returnAddress":${kotlinx.serialization.json.JsonPrimitive(returnAddress)}""")
                append("}")
            },
        )

        /** Banks of Finland as `GET /aspsps` lists them, with entries the plug-in leaves out. */
        val banksOfFinland: String = """
            {"aspsps":[
             {"name":"Aurora Sparbank","country":"FI","logo":"https://brands.example/aurora/","psu_types":["personal"],"auth_methods":[],
              "maximum_consent_validity":$MAXIMUM_VALIDITY_SECONDS,"beta":false,"required_psu_headers":["Psu-User-Agent"]},
             {"name":"Kuusi Pankki","country":"FI","logo":"https://brands.example/kuusi/","psu_types":["personal"],"auth_methods":[],
              "maximum_consent_validity":7776000,"beta":false,"required_psu_headers":["Psu-Ip-Address","Psu-User-Agent"]},
             {"name":"Old (Mill) Bank","country":"FI","logo":"https://brands.example/mill/","psu_types":["personal"],"auth_methods":[],
              "maximum_consent_validity":86400,"beta":true},
             {"country":"FI","logo":"https://brands.example/nameless/","psu_types":["personal"],"auth_methods":[],"maximum_consent_validity":86400,"beta":false},
             {"name":"${"L".repeat(96)}","country":"FI","logo":"https://brands.example/long/","psu_types":["personal"],"auth_methods":[],"maximum_consent_validity":86400,"beta":false},
             {"name":"No Validity Bank","country":"FI","logo":"https://brands.example/nv/","psu_types":["personal"],"auth_methods":[],"beta":false},
             {"name":"Aurora Sparbank","country":"FI","logo":"https://brands.example/aurora/","psu_types":["personal"],"auth_methods":[],
              "maximum_consent_validity":$MAXIMUM_VALIDITY_SECONDS,"beta":false,"required_psu_headers":["Psu-User-Agent"]}
            ]}
        """.trimIndent()

        val started: String = """{"url":"$CONSENT_ADDRESS","authorization_id":"$STARTED_REQUEST_ID","psu_id_hash":"invented"}"""

        const val VALID_UNTIL = "2027-05-27T09:59:55.000000+00:00"

        /** The answer of `POST /sessions`: five entries, one without `uid`, one without `identification_hash`. */
        val session: String = """
            {"session_id":"$SESSION_ID",
             "accounts":[
              {"uid":"$UID_1","identification_hash":"$HASH_1","identification_hashes":["$HASH_1"],"account_id":{"iban":"$IBAN"},
               "name":"PLANTED HOLDER NAME","details":"Household","product":"Everyday","cash_account_type":"CACC","currency":"EUR"},
              {"identification_hash":"$HASH_2","identification_hashes":["$HASH_2"],"account_id":{"other":{"identification":"$OTHER_NUMBER","scheme_name":"BBAN"}},
               "product":"Savings plus","cash_account_type":"SVGS","currency":"XXX"},
              {"uid":"PLANTED-UID-0003","identification_hashes":[],"account_id":{"iban":"FI00PLANTED0000000003"},"cash_account_type":"CACC","currency":"EUR"},
              {"uid":"$UID_4","identification_hash":"$HASH_4","identification_hashes":["$HASH_4"],"cash_account_type":"CARD","currency":"SEK"},
              {"uid":"$UID_5","identification_hash":"$HASH_5","identification_hashes":["$HASH_5"],"cash_account_type":"TRAN","currency":"EUR","details":"Transit"}
             ],
             "aspsp":{"name":"Aurora Sparbank","country":"FI"},"psu_type":"personal",
             "access":{"valid_until":"$VALID_UNTIL","balances":true,"transactions":true}}
        """.trimIndent()

        /** The answer the Bank's Strong Customer Authentication returns to the return address. */
        fun returned(query: String, address: String = RETURN_ADDRESS) = slussen.pluginapi.provider.ConsentAnswer(Secret("$address?$query"))

        /**
         * Every planted value of these answers that no log line, result or `toString()` may show. The lasting references
         * (`identification_hash`) are delivered as `providerReference`, which a result carries by the contract, so they
         * are searched for in the log only ([plantedInLogs]).
         */
        val planted: List<String> = listOf(
            STATE, CODE, STARTED_REQUEST_ID, "PLANTED-ADDRESS-77c1", SESSION_ID, UID_1, UID_4, UID_5, "PLANTED-UID-0003",
            IBAN, OTHER_NUMBER, "PLANTED HOLDER NAME", APPLICATION_ID, pem.lines()[1].take(40),
        )

        /** What no log line may show, with Detailed Logging on or off (NFR-008, UC-007 BR-006). */
        val plantedInLogs: List<String> = planted + listOf(HASH_1, HASH_2, HASH_4, HASH_5)
    }
}
