package slussen.provider.enablebanking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.net.URLDecoder
import java.security.PrivateKey
import java.time.Instant

/** The text of a JSON string value, or null when [element] is none or not a string. */
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** [element] as an object, or null. */
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/** The JSON object [body] holds, or null when it holds none. */
internal fun objectOf(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}

/** A list of strings, or null when [element] is not an array of strings only. */
private fun JsonElement?.texts(): List<String>? =
    (this as? JsonArray)?.map { it.text() ?: return null }

/**
 * A Bank's reference (ADR-001 §4.1.1, S-B-42): Enable Banking identifies a Bank (an ASPSP) by its name and country
 * together, so the reference is the name, a space and the country in parentheses (`Example Bank (FI)`); the name and
 * country are read back from the last parentheses.
 */
internal object BankReference {
    /** `BANK.name` (entity model): 100 characters. A Bank whose reference is longer is left out of the list. */
    const val MAX_LENGTH = 100

    private val form = Regex("""^(.+) \(([A-Z]{2})\)$""", RegexOption.DOT_MATCHES_ALL)

    fun of(name: String, country: String): String = "$name ($country)"

    /** The name and country of [reference], or null when it names no Bank of this form. */
    fun read(reference: String): Pair<String, String>? {
        val found = form.matchEntire(reference) ?: return null
        val name = found.groupValues[1]
        return if (name.isBlank()) null else name to found.groupValues[2]
    }
}

/**
 * The plug-in's opaque handle of a started Consent Request (ADR-001 §4.1.1): the Bank's reference, Enable Banking's
 * `authorization_id` and the Bank's `required_psu_headers`. Masked in [toString]; the core stores it only in
 * `device.db` (ADR-002 §2).
 */
internal class ConsentHandle(val bankReference: String, val requestId: String?, val psuHeaders: List<String>) {
    fun toSecret(): Secret = Secret(
        buildJsonObject {
            put(BANK, bankReference)
            requestId?.let { put(REQUEST_ID, it) }
            put(PSU_HEADERS, JsonArray(psuHeaders.map(::JsonPrimitive)))
        }.toString(),
    )

    override fun toString(): String = "ConsentHandle(****)"

    companion object {
        private const val BANK = "bank"
        private const val REQUEST_ID = "authorization_id"
        private const val PSU_HEADERS = "required_psu_headers"

        /** The handle [secret] holds, or null when it holds none this part made. */
        @OptIn(SensitiveApi::class)
        fun read(secret: Secret?): ConsentHandle? {
            val kept = secret?.let { objectOf(it.reveal()) } ?: return null
            val bank = kept[BANK].text()?.takeIf { BankReference.read(it) != null } ?: return null
            val headers = kept[PSU_HEADERS].texts() ?: return null
            return ConsentHandle(bank, kept[REQUEST_ID].text(), headers)
        }
    }
}

/**
 * What the Bank Session holds (`BANK_SESSION.session_reference`, ADR-001 §4.1.1, S-B-42): Enable Banking's `session_id`,
 * the Bank's reference, the Bank's required PSU headers, and the session's own identifier (`uid`) of each delivered Bank
 * Account by its lasting reference (`identification_hash`), so that a Fetch sends no request to learn them. Masked in
 * [toString].
 */
internal class BankSessionValue(
    val sessionId: String,
    val bankReference: String,
    val psuHeaders: List<String>,
    val uids: Map<String, String>,
) {
    fun toSession(): ConsentSession = ConsentSession(encoded())

    private fun encoded(): String = buildJsonObject {
        put(SESSION_ID, sessionId)
        put(BANK, bankReference)
        put(PSU_HEADERS, JsonArray(psuHeaders.map(::JsonPrimitive)))
        put(UIDS, JsonObject(uids.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    override fun toString(): String = "BankSessionValue(****)"

    companion object {
        /** `BANK_SESSION.session_reference` (entity model, S-B-45): 4000 characters. */
        const val MAX_LENGTH = 4000

        private const val SESSION_ID = "session_id"
        private const val BANK = "bank"
        private const val PSU_HEADERS = "required_psu_headers"
        private const val UIDS = "uids"

        /**
         * The Bank Session with as many of [uids], in their order, as fit within [MAX_LENGTH]; a Bank Account whose `uid`
         * does not fit is delivered but cannot be read, as one without `uid` (ADR-001 §4.1.1). Null when not even the
         * session itself fits.
         */
        fun fitting(sessionId: String, bankReference: String, psuHeaders: List<String>, uids: List<Pair<String, String>>): BankSessionValue? {
            var kept = BankSessionValue(sessionId, bankReference, psuHeaders, emptyMap())
            if (kept.encoded().length > MAX_LENGTH) return null
            for (uid in uids) {
                val more = BankSessionValue(sessionId, bankReference, psuHeaders, kept.uids + uid)
                if (more.encoded().length <= MAX_LENGTH) kept = more
            }
            return kept
        }

        /** What [session] holds, or null when it holds no Bank Session this part made. */
        @OptIn(SensitiveApi::class)
        fun read(session: ConsentSession): BankSessionValue? {
            val kept = objectOf(session.reveal()) ?: return null
            val id = kept[SESSION_ID].text()?.takeIf { it.isNotEmpty() } ?: return null
            val bank = kept[BANK].text()?.takeIf { BankReference.read(it) != null } ?: return null
            val headers = kept[PSU_HEADERS].texts() ?: return null
            val uids = kept[UIDS].asObject()?.mapValues { (_, uid) -> uid.text() ?: return null } ?: return null
            return BankSessionValue(id, bank, headers, uids)
        }
    }
}

/**
 * The parameters Enable Banking adds to the query of the return address (ADR-001 §4.1.1: `code` and `state`, or `error`
 * and `error_description`), decoded, in their order; a parameter may come more than once. Null for an answer whose
 * query cannot be decoded.
 */
internal class ReturnedAnswer private constructor(private val parameters: List<Pair<String, String>>) {
    fun all(name: String): List<String> = parameters.filter { it.first == name }.map { it.second }

    override fun toString(): String = "ReturnedAnswer(****)"

    companion object {
        @OptIn(SensitiveApi::class)
        fun read(answer: slussen.pluginapi.provider.ConsentAnswer): ReturnedAnswer? {
            val query = answer.returned.reveal().substringBefore('#').substringAfter('?', "")
            return try {
                ReturnedAnswer(
                    query.split('&').filter { it.isNotEmpty() }.map { part ->
                        decoded(part.substringBefore('=')) to decoded(part.substringAfter('=', ""))
                    },
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun decoded(text: String): String = URLDecoder.decode(text, Charsets.UTF_8)
    }
}

/**
 * The stored credential of Enable Banking (ADR-001 §4.5) as the requests need it: the application id and its private
 * key, which sign each request. Null when the credential cannot be read, so nothing is sent.
 */
internal class RequestSigner private constructor(private val applicationId: String, private val key: PrivateKey) {
    fun token(now: Instant): String = SignedRequestToken.sign(applicationId, key, now)

    override fun toString(): String = "RequestSigner(****)"

    companion object {
        @OptIn(SensitiveApi::class)
        fun of(credential: Secret?): RequestSigner? {
            val kept = credential?.let { objectOf(it.reveal()) } ?: return null
            val applicationId = kept[EnableBankingCredentials.APPLICATION_ID].text()?.takeIf { it.isNotBlank() } ?: return null
            val key = kept[EnableBankingCredentials.PRIVATE_KEY].text()?.let(PrivateKeyFile::read) ?: return null
            return RequestSigner(applicationId, key)
        }

        /** The return address kept with the credential (S-B-51), or null when none, an empty one, or none readable. */
        @OptIn(SensitiveApi::class)
        fun returnAddressOf(credential: Secret?): String? =
            credential?.let { objectOf(it.reveal()) }?.get(EnableBankingCredentials.RETURN_ADDRESS).text()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
