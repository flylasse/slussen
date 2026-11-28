package slussen.provider.enablebanking

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local test server for Enable Banking's Fetch requests (ADR-001 §4.1.1), with invented answers only: the balances of
 * an account by its `uid`, and each page of its transactions by the `continuation_key` asked for (none for the first).
 * A request the test did not set answers 404 with a code no rule names, so that a request the plug-in should not send
 * shows in the test.
 */
internal class FetchServer {
    val server = MockWebServer()
    val received = CopyOnWriteArrayList<RecordedRequest>()
    private val balances = mutableMapOf<String, MockResponse>()
    private val pages = mutableMapOf<Pair<String, String?>, MockResponse>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                val url = request.requestUrl!!
                val segments = url.pathSegments
                val found = if (request.method == "GET" && segments.size == 3 && segments[0] == "accounts") {
                    when (segments[2]) {
                        "balances" -> balances[segments[1]]
                        "transactions" -> pages[segments[1] to url.queryParameter("continuation_key")]
                        else -> null
                    }
                } else {
                    null
                }
                return found ?: MockResponse().setResponseCode(404).setBody("""{"message":"not in this test","error":"TEST_ROUTE_NOT_SET"}""")
            }
        }
        server.start()
    }

    fun balances(uid: String, response: MockResponse) {
        balances[uid] = response
    }

    fun balances(uid: String, vararg entries: JsonElement) = balances(uid, ok(buildJsonObject { put("balances", JsonArray(entries.toList())) }.toString()))

    fun page(uid: String, key: String?, response: MockResponse) {
        pages[uid to key] = response
    }

    /** One page of transactions of [uid], asked with [key], that holds [entries] and carries [next] as its continuation key. */
    fun page(uid: String, key: String?, entries: List<JsonElement>, next: String? = null) =
        page(uid, key, ok(pageBody(entries, next)))

    fun url() = server.url("/")

    /** The requests of [uid], in order, as `<method> <path>?<query>`. */
    fun requestsOf(uid: String): List<RecordedRequest> = received.filter { it.requestUrl!!.pathSegments.getOrNull(1) == uid }

    fun shutdown() = server.shutdown()

    companion object {
        fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

        fun error(status: Int, code: String, text: String = "invented error text") = MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"message":"$text","code":$status,"error":"$code","detail":"$text"}""")

        fun pageBody(entries: List<JsonElement>, next: String?) = buildJsonObject {
            put("transactions", JsonArray(entries))
            put("continuation_key", next?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()

        /**
         * One entry of `transactions` as Enable Banking's reference describes it, with every field the mapping reads given
         * unless the test leaves it out (null leaves the field out).
         */
        fun entry(
            status: String? = "BOOK",
            bookingDate: String? = "2026-11-20",
            amount: String? = "12.50",
            indicator: String? = "DBIT",
            currency: String? = "EUR",
            creditor: String? = "Harbor Pharmacy",
            debtor: String? = "Household Holder",
            remittance: List<String>? = listOf("Card purchase 4411"),
            valueDate: String? = "2026-11-21",
            transactionDate: String? = "2026-11-19",
            entryReference: String? = "invented-entry-reference-1",
        ): JsonElement = buildJsonObject {
            entryReference?.let { put("entry_reference", it) }
            if (amount != null || currency != null) {
                putJsonObject("transaction_amount") {
                    amount?.let { put("amount", it) }
                    currency?.let { put("currency", it) }
                }
            }
            creditor?.let { putJsonObject("creditor") { put("name", it) } }
            debtor?.let { putJsonObject("debtor") { put("name", it) } }
            indicator?.let { put("credit_debit_indicator", it) }
            status?.let { put("status", it) }
            bookingDate?.let { put("booking_date", it) }
            valueDate?.let { put("value_date", it) }
            transactionDate?.let { put("transaction_date", it) }
            remittance?.let { lines -> put("remittance_information", JsonArray(lines.map(::JsonPrimitive))) }
        }

        /** One entry of `balances`. */
        fun balance(type: String, amount: String, currency: String = "EUR", lastChange: String? = null, referenceDate: String? = null): JsonElement =
            buildJsonObject {
                put("name", "invented balance name")
                putJsonObject("balance_amount") {
                    put("currency", currency)
                    put("amount", amount)
                }
                put("balance_type", type)
                lastChange?.let { put("last_change_date_time", it) }
                referenceDate?.let { put("reference_date", it) }
            }
    }
}
