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
 * the Bank's reference, the Bank's required PSU headers, the session's own identifier (`uid`) of each delivered Bank
 * Account by its lasting reference (`identification_hash`), so that a Fetch sends no request to learn them, and, by the
 * same reference, the currency of each such Bank Account whose balance a Fetch may give ([balanceCurrencies]: one whose
 * `cash_account_type` is not `CARD` and whose currency is known), so that the Fetch chooses the Account Balance in the
 * Bank Account's currency and asks no balance it cannot give. Masked in [toString].
 */
internal class BankSessionValue(
    val sessionId: String,
    val bankReference: String,
    val psuHeaders: List<String>,
    val uids: Map<String, String>,
    val balanceCurrencies: Map<String, String> = emptyMap(),
    /** True when a Bank Account the Bank Consent covers could not be kept with its `uid` within [MAX_LENGTH] (S-B-55). */
    val notAllKept: Boolean = false,
) {
    fun toSession(): ConsentSession = ConsentSession(encoded())

    private fun encoded(): String = buildJsonObject {
        put(SESSION_ID, sessionId)
        put(BANK, bankReference)
        put(PSU_HEADERS, JsonArray(psuHeaders.map(::JsonPrimitive)))
        put(UIDS, JsonObject(uids.mapValues { JsonPrimitive(it.value) }))
        put(BALANCE_CURRENCIES, JsonObject(balanceCurrencies.mapValues { JsonPrimitive(it.value) }))
        if (notAllKept) put(NOT_ALL_KEPT, true)
    }.toString()

    override fun toString(): String = "BankSessionValue(****)"

    /** One delivered Bank Account the session can read: its lasting reference, its `uid`, and the currency its balance is chosen in, if any. */
    class Readable(val reference: String, val uid: String, val balanceCurrency: String?)

    companion object {
        /** `BANK_SESSION.session_reference` (entity model, S-B-45): 4000 characters. */
        const val MAX_LENGTH = 4000

        private const val SESSION_ID = "session_id"
        private const val BANK = "bank"
        private const val PSU_HEADERS = "required_psu_headers"
        private const val UIDS = "uids"
        private const val BALANCE_CURRENCIES = "balance_currencies"
        private const val NOT_ALL_KEPT = "not_all_kept"

        /**
         * The Bank Session with as many of [readable], in their order, as fit within [MAX_LENGTH] (S-B-55): each with its
         * `uid` and its balance currency, else with its `uid` only (read, with no Account Balance); a Bank Account whose
         * `uid` does not fit is delivered but cannot be read, and the session then says that not all were kept. Null when
         * not even the session itself fits.
         */
        fun fitting(sessionId: String, bankReference: String, psuHeaders: List<String>, readable: List<Readable>): BankSessionValue? {
            // The mark is counted in from the start, so that setting it never makes the session too long.
            var kept = BankSessionValue(sessionId, bankReference, psuHeaders, emptyMap(), notAllKept = true)
            if (kept.encoded().length > MAX_LENGTH) return null
            var dropped = false
            for (account in readable) {
                val uids = kept.uids + (account.reference to account.uid)
                val withCurrency = account.balanceCurrency?.let {
                    BankSessionValue(sessionId, bankReference, psuHeaders, uids, kept.balanceCurrencies + (account.reference to it), notAllKept = true)
                }
                val withoutCurrency = BankSessionValue(sessionId, bankReference, psuHeaders, uids, kept.balanceCurrencies, notAllKept = true)
                kept = listOfNotNull(withCurrency, withoutCurrency).firstOrNull { it.encoded().length <= MAX_LENGTH } ?: kept.also { dropped = true }
            }
            return BankSessionValue(sessionId, bankReference, psuHeaders, kept.uids, kept.balanceCurrencies, notAllKept = dropped)
        }

        /**
         * What [session] holds, or null when it holds no Bank Session this part made. A session kept before the
         * currencies were kept holds none, so a Fetch gives no balance through it until the Bank Consent is renewed.
         */
        @OptIn(SensitiveApi::class)
        fun read(session: ConsentSession): BankSessionValue? {
            val kept = objectOf(session.reveal()) ?: return null
            val id = kept[SESSION_ID].text()?.takeIf { it.isNotEmpty() } ?: return null
            val bank = kept[BANK].text()?.takeIf { BankReference.read(it) != null } ?: return null
            val headers = kept[PSU_HEADERS].texts() ?: return null
            val uids = kept[UIDS].asObject()?.mapValues { (_, uid) -> uid.text() ?: return null } ?: return null
            val currencies = kept[BALANCE_CURRENCIES]?.let { element ->
                element.asObject()?.mapValues { (_, currency) -> currency.text() ?: return null } ?: return null
            }.orEmpty()
            val notAllKept = (kept[NOT_ALL_KEPT] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
            return BankSessionValue(id, bank, headers, uids, currencies, notAllKept)
        }
    }
}

/** The headers of the Operator's presence Slussen can give truly (ADR-001 §4.1.1, S-B-46), in lower case. */
internal val PRESENCE_HEADERS = setOf("psu-user-agent", "psu-accept-language")

/** The application's name, as `Psu-User-Agent` gives it (ADR-001 §4.1.1, S-B-55). */
internal const val USER_AGENT = "Slussen"

/** The language tag of a device that gives no language (`Locale.ROOT`). */
private const val UNDETERMINED_LANGUAGE = "und"

/**
 * UC-001 BR-012 (S-B-46, S-B-55): the headers of the Operator's presence a request to a Bank that requires
 * [requiredHeaders] carries, with the device's [language], or null when it can carry none: when the Bank requires a
 * header Slussen cannot truly send, or the device gives no language. A Bank requires all of its headers or none. What
 * `showsPresence` says and what a request sends both come from this one rule.
 */
internal fun presenceHeaders(requiredHeaders: List<String>, language: String): Map<String, String>? {
    if (!requiredHeaders.all { it.lowercase() in PRESENCE_HEADERS }) return null
    val sent = language.trim().takeIf { it.isNotEmpty() && it != UNDETERMINED_LANGUAGE } ?: return null
    // ADR-001 §4.1.1: never Psu-Ip-Address (Slussen asks no third party for the device's address) and never Psu-Geo-Location (NFR-022).
    return mapOf("Psu-User-Agent" to USER_AGENT, "Psu-Accept-Language" to sent)
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
