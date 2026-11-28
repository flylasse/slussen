package slussen.provider.enablebanking

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.BankAccountNumber
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Enable Banking's Bank Consent operations (UC-007; ADR-001 §4.1, §4.1.1): the Banks of a country, starting a Consent
 * Request, reading the answer, completing it and ending the session. Every request is signed with the application's key
 * (C-008), admitted by the gate first as a request of kind Other with the Operator present (ADR-001 §4.0), and carries
 * no header of the Operator's presence: §4.1.1 states those for the requests of a Fetch only. Every error is read from
 * the answer's `error` code, not its HTTP status, as Enable Banking's FAQ advises. No contract call throws (ADR-001
 * §4.0): a failure that is not an answer is a lost connection.
 */
internal class EnableBankingConsent(
    private val base: HttpUrl,
    private val http: OkHttpClient,
    private val clock: Clock,
    private val log: ConsentLog,
    /** The device's language, so that [showsPresence] says what a Fetch's request sends (S-B-55). */
    private val language: () -> String = { java.util.Locale.getDefault().toLanguageTag() },
) : BankConsentCapability {

    // ---- The Banks (ADR-001 §4.1.1 "Banks and their reference") -----------------------------------------------------

    override suspend fun banks(country: String, context: RequestContext): BankList {
        val listed = guarded({ BankList.TransientFailure(FailureKind.ConnectionLost) }) { listBanks(country, context) }
        when (listed) {
            is BankList.Listed -> log.outcome(EnableBankingConsentEvent.BanksListed, count = listed.banks.size)
            is BankList.Refused -> log.outcome(EnableBankingConsentEvent.BanksNotListed, listed.kind)
            is BankList.TransientFailure -> log.outcome(EnableBankingConsentEvent.BanksNotListed, listed.kind)
            is BankList.StoppedByGate -> log.outcome(EnableBankingConsentEvent.BanksNotListed, listed.reason)
        }
        return listed
    }

    private suspend fun listBanks(country: String, context: RequestContext): BankList {
        // UC-007 BR-010: The Banks are listed by country, an ISO 3166 two-letter code, as Enable Banking takes it.
        if (!COUNTRY.matches(country)) return BankList.Refused(ConsentFailureKind.RefusedByDataProvider)
        val signer = RequestSigner.of(context.credential) ?: return BankList.Refused(ConsentFailureKind.CredentialsRefused)
        val admission = context.gate.admit(attended(null))
        if (admission is Admission.Refused) return BankList.StoppedByGate(admission.reason)
        return when (val listing = listedBanks(country, signer, context)) {
            // UC-007 BR-010: Each Bank is named by its name and country, once, as Enable Banking identifies it.
            is Listing.Read -> BankList.Listed(
                listing.banks.filter { it.country == country }.distinctBy { it.reference }.map { BankChoice(it.reference, it.name) },
            )
            is Listing.Refused -> BankList.Refused(listing.kind)
            is Listing.Failed -> BankList.TransientFailure(listing.kind)
        }
    }

    /** One Bank of `GET /aspsps` this part can name and start: a name, a country and a maximum validity it can read. */
    private class ListedBank(val name: String, val country: String, val maximumValiditySeconds: Long, val psuHeaders: List<String>) {
        val reference: String = BankReference.of(name, country)
    }

    private sealed interface Listing {
        /** [date] is the time Enable Banking gave with its answer, or null when it gave none readably. */
        class Read(val banks: List<ListedBank>, val date: Instant?) : Listing
        class Refused(val kind: ConsentFailureKind) : Listing
        class Failed(val kind: FailureKind) : Listing
    }

    /** `GET /aspsps` with `country`, `psu_type` personal and `service` AIS; the reference describes no paging. */
    private suspend fun listedBanks(country: String, signer: RequestSigner, context: RequestContext): Listing {
        val address = base.newBuilder().addPathSegment("aspsps")
            .addQueryParameter("country", country)
            .addQueryParameter("psu_type", PSU_TYPE)
            .addQueryParameter("service", "AIS")
            .build()
        return when (val reply = exchange(signed(address, signer).get().build(), context, EnableBankingConsentEvent.BanksNotListed)) {
            is Reply.Ok -> (reply.body?.get("aspsps") as? JsonArray)?.let { entries -> Listing.Read(entries.mapNotNull(::bankOf), reply.date) }
                ?: Listing.Failed(FailureKind.UnreadableAnswer)
            is Reply.Declined -> Listing.Failed(FailureKind.UnreadableAnswer)
            is Reply.Refused -> Listing.Refused(
                if (reply.code in CREDENTIALS_REFUSED) ConsentFailureKind.CredentialsRefused else ConsentFailureKind.RefusedByDataProvider,
            )
            is Reply.Failed -> Listing.Failed(reply.kind)
        }
    }

    /** A Bank of the list, or null for an entry this part cannot name within `BANK.name` or start. */
    private fun bankOf(entry: kotlinx.serialization.json.JsonElement): ListedBank? {
        val bank = entry.asObject() ?: return null
        val name = bank["name"].text()?.takeIf { it.isNotBlank() } ?: return null
        val country = bank["country"].text()?.takeIf { COUNTRY.matches(it) } ?: return null
        val validity = (bank["maximum_consent_validity"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: return null
        // An entry whose required headers cannot be read is taken to require one Slussen cannot send (S-B-46).
        val headers = when (val required = bank["required_psu_headers"]) {
            null -> emptyList()
            is JsonArray -> required.map { it.text() ?: UNREADABLE_HEADER }
            else -> listOf(UNREADABLE_HEADER)
        }
        val listed = ListedBank(name, country, validity, headers)
        if (listed.reference.length > BankReference.MAX_LENGTH || BankReference.read(listed.reference) != name to country) return null
        return listed
    }

    // ---- Starting (ADR-001 §4.1.1 "Starting") -----------------------------------------------------------------------

    override suspend fun startConsentRequest(bank: String, returnAddress: String, state: Secret, context: RequestContext): ConsentStart {
        val started = guarded({ ConsentStart.TransientFailure(FailureKind.ConnectionLost) }) { start(bank, returnAddress, state, context) }
        when (started) {
            is ConsentStart.Started -> log.outcome(EnableBankingConsentEvent.ConsentRequestStarted)
            is ConsentStart.Refused -> log.outcome(EnableBankingConsentEvent.ConsentRequestNotStarted, started.kind)
            is ConsentStart.TransientFailure -> log.outcome(EnableBankingConsentEvent.ConsentRequestNotStarted, started.kind)
            is ConsentStart.StoppedByGate -> log.outcome(EnableBankingConsentEvent.ConsentRequestNotStarted, started.reason)
        }
        return started
    }

    @OptIn(SensitiveApi::class)
    private suspend fun start(bank: String, returnAddress: String, state: Secret, context: RequestContext): ConsentStart {
        val (name, country) = BankReference.read(bank) ?: return ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider)
        val signer = RequestSigner.of(context.credential) ?: return ConsentStart.Refused(ConsentFailureKind.CredentialsRefused)
        val listAdmission = context.gate.admit(attended(bank))
        if (listAdmission is Admission.Refused) return ConsentStart.StoppedByGate(listAdmission.reason)
        // UC-007 BR-010: The Bank is looked up again, so that a renamed Bank is refused instead of started under an old name.
        var listedAt: Instant? = null
        val listed = when (val listing = listedBanks(country, signer, context)) {
            is Listing.Read -> listing.banks.filter { it.name == name && it.country == country }.also { listedAt = listing.date }
            is Listing.Refused -> return ConsentStart.Refused(listing.kind)
            is Listing.Failed -> return ConsentStart.TransientFailure(listing.kind)
        }
        if (listed.isEmpty()) return ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider)
        // Two entries of one Bank: the shorter validity never exceeds either, and every header either requires is kept.
        val maximum = listed.minOf { it.maximumValiditySeconds }
        val headers = listed.flatMap { it.psuHeaders }.distinct()
        if (maximum <= context.timeout.duration.seconds) return ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider)
        val authAdmission = context.gate.admit(attended(bank))
        if (authAdmission is Admission.Refused) return ConsentStart.StoppedByGate(authAdmission.reason)
        // The moment is the earlier of the device's clock and the time Enable Banking gave with the list just before, so that a clock that runs ahead does not ask for more than the maximum.
        // UC-007 BR-004: The validity asked is the Bank's declared maximum, counted from the moment the request is sent, less the request timeout, so that it never exceeds the maximum when the request arrives.
        val now = clock.instant()
        val sentAt = listedAt?.takeIf { it.isBefore(now) } ?: now
        val validUntil = sentAt.plusSeconds(maximum).minus(context.timeout.duration).truncatedTo(ChronoUnit.MICROS)
        val body = buildJsonObject {
            putJsonObject("access") { put("valid_until", VALID_UNTIL.format(validUntil)) }
            putJsonObject("aspsp") {
                put("name", name)
                put("country", country)
            }
            put("state", state.reveal())
            put("redirect_url", returnAddress)
            put("psu_type", PSU_TYPE)
        }
        val request = signed(base.newBuilder().addPathSegment("auth").build(), signer).post(json(body)).build()
        return when (val reply = exchange(request, context, EnableBankingConsentEvent.ConsentRequestNotStarted)) {
            is Reply.Ok -> {
                // UC-007 BR-006: The address to open carries a value of the session; it is only handed back, never logged.
                val address = reply.body?.get("url").text()?.takeIf { it.toHttpUrlOrNull()?.isHttps == true }
                    ?: return ConsentStart.TransientFailure(FailureKind.UnreadableAnswer)
                ConsentStart.Started(address, ConsentHandle(bank, reply.body?.get("authorization_id").text(), headers).toSecret())
            }
            is Reply.Declined -> ConsentStart.TransientFailure(FailureKind.UnreadableAnswer)
            is Reply.Refused -> ConsentStart.Refused(
                when (reply.code) {
                    // UC-007 BR-011: Enable Banking does not accept the return address; the core names the address it sent.
                    "REDIRECT_URI_NOT_ALLOWED" -> ConsentFailureKind.ReturnAddressRefused
                    in CREDENTIALS_REFUSED -> ConsentFailureKind.CredentialsRefused
                    else -> ConsentFailureKind.RefusedByDataProvider
                },
            )
            is Reply.Failed -> ConsentStart.TransientFailure(reply.kind)
        }
    }

    // ---- The answer (ADR-001 §4.1.1 "The answer") -------------------------------------------------------------------

    // UC-007 BR-002: The state is read from the answer only when it carries exactly one that is not empty; the core matches it by its digest.
    override fun stateOf(answer: ConsentAnswer): Secret? = try {
        ReturnedAnswer.read(answer)?.all("state")?.singleOrNull()?.takeIf { it.isNotEmpty() }?.let(::Secret)
    } catch (e: Exception) {
        null
    }

    // ---- Completing (ADR-001 §4.1.1 "Completing") -------------------------------------------------------------------

    override suspend fun completeConsent(answer: ConsentAnswer, handle: Secret?, context: RequestContext): ConsentCompletion {
        val completed = guarded({ ConsentCompletion.TransientFailure(FailureKind.ConnectionLost) }) { complete(answer, handle, context) }
        when (completed) {
            is ConsentCompletion.Completed -> log.outcome(EnableBankingConsentEvent.ConsentCompleted, count = completed.bankAccounts.size)
            ConsentCompletion.NotCompleted -> log.outcome(EnableBankingConsentEvent.ConsentNotCompleted)
            is ConsentCompletion.NotConfirmed -> log.outcome(EnableBankingConsentEvent.ConsentNotCompleted, completed.kind)
            is ConsentCompletion.TransientFailure -> log.outcome(EnableBankingConsentEvent.ConsentNotCompleted, completed.kind)
            is ConsentCompletion.StoppedByGate -> log.outcome(EnableBankingConsentEvent.ConsentNotCompleted, completed.reason)
        }
        return completed
    }

    private suspend fun complete(answer: ConsentAnswer, handle: Secret?, context: RequestContext): ConsentCompletion {
        val notUnderstood = ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood)
        val returned = ReturnedAnswer.read(answer) ?: return notUnderstood
        val codes = returned.all("code")
        val errors = returned.all("error")
        // A1 of UC-007: An answer with an error and no code is a cancelled or refused authentication, which are not told apart.
        if (errors.isNotEmpty() && codes.isEmpty()) return ConsentCompletion.NotCompleted
        // A2 of UC-007: An answer with neither, with both, or with a code that is not one is not understood, and nothing is sent.
        val code = codes.singleOrNull()?.takeIf { it.isNotEmpty() && errors.isEmpty() } ?: return notUnderstood
        if (stateOf(answer) == null) return notUnderstood
        val started = ConsentHandle.read(handle) ?: return notUnderstood
        val signer = RequestSigner.of(context.credential) ?: return ConsentCompletion.NotConfirmed(ConsentFailureKind.CredentialsRefused)
        val admission = context.gate.admit(attended(started.bankReference))
        if (admission is Admission.Refused) return ConsentCompletion.StoppedByGate(admission.reason)
        val body = buildJsonObject { put("code", code) }
        val request = signed(base.newBuilder().addPathSegment("sessions").build(), signer).post(json(body)).build()
        return when (val reply = exchange(request, context, EnableBankingConsentEvent.ConsentNotCompleted)) {
            is Reply.Ok -> reply.body?.let { completion(it, started) } ?: run {
                // A8 of UC-007: a session Enable Banking opened that Slussen cannot use is ended there once, so that no consent stays open unknown (S-B-53).
                reply.body?.get("session_id").text()?.takeIf { it.isNotEmpty() }?.let { endUnusable(it, started.bankReference, signer, context) }
                ConsentCompletion.TransientFailure(FailureKind.UnreadableAnswer)
            }
            // A4 of UC-007: a refusal without a code, by a status that does not ask to wait: nothing was granted.
            is Reply.Declined -> ConsentCompletion.NotConfirmed(ConsentFailureKind.RefusedByDataProvider)
            is Reply.Refused -> ConsentCompletion.NotConfirmed(
                when (reply.code) {
                    // A2 of UC-007: an expired, wrong or used code is an answer the Data Provider does not accept.
                    "EXPIRED_AUTHORIZATION_CODE", "WRONG_AUTHORIZATION_CODE", "ALREADY_AUTHORIZED" -> ConsentFailureKind.AnswerNotUnderstood
                    in CREDENTIALS_REFUSED -> ConsentFailureKind.CredentialsRefused
                    // A4 of UC-007: any other refusal.
                    else -> ConsentFailureKind.RefusedByDataProvider
                },
            )
            is Reply.Failed -> ConsentCompletion.TransientFailure(reply.kind)
        }
    }

    /**
     * The completed Bank Consent of [answer], or null when it cannot be read: the session was then perhaps opened, so the
     * completion is a failure in transit (UC-007 A8), never a guess.
     */
    private fun completion(answer: JsonObject, started: ConsentHandle): ConsentCompletion? {
        val sessionId = answer["session_id"].text()?.takeIf { it.isNotEmpty() } ?: return null
        // UC-007 BR-004: The expiry is the session's validity as Enable Banking reports it, which it keeps exactly as asked.
        val expiresAt = answer["access"].asObject()?.get("valid_until").text()?.let(::instantOrNull)?.takeIf { it.isAfter(clock.instant()) }
            ?: return null
        val sessionBank = answer["aspsp"].asObject() ?: return null
        if (sessionBank["name"].text()?.let { name -> sessionBank["country"].text()?.let { BankReference.of(name, it) } } != started.bankReference) return null
        val entries = (answer["accounts"] as? JsonArray)?.mapNotNull { it.asObject() } ?: return null
        // UC-007 BR-009: Each Bank Account is delivered under its lasting reference; an entry without one is not delivered.
        val lasting = entries.mapNotNull { entry -> entry["identification_hash"].text()?.takeIf { it.isNotEmpty() }?.let { it to entry } }
        val twice = lasting.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        // UC-007 BR-009: The session keeps each Bank Account's session identifier by its lasting reference; one without one, or of two with one reference, is offered but cannot be read.
        val readable = lasting.filter { it.first !in twice }.mapNotNull { (reference, entry) ->
            entry["uid"].text()?.takeIf { it.isNotEmpty() }?.let { BankSessionValue.Readable(reference, it, balanceCurrencyOf(entry)) }
        }
        val session = BankSessionValue.fitting(sessionId, started.bankReference, started.psuHeaders, readable) ?: return null
        return ConsentCompletion.Completed(
            expiresAt = expiresAt,
            bankAccounts = lasting.map { (reference, entry) -> delivered(reference, entry, started.bankReference) },
            session = session.toSession(),
        )
    }

    /**
     * Asks Enable Banking once, through the gate with the Operator present, to end the session [sessionId] of a completion
     * that cannot be used. Its result changes nothing of the completion's outcome, and nothing of the session is kept.
     */
    private suspend fun endUnusable(sessionId: String, bank: String, signer: RequestSigner, context: RequestContext) {
        try {
            if (context.gate.admit(attended(bank)) is Admission.Refused) return
            val address = base.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()
            exchange(signed(address, signer).delete().build(), context, EnableBankingConsentEvent.ConsentNotCompleted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The completion's outcome stands whatever became of this request.
        }
    }

    /**
     * The currency a Fetch chooses the Account Balance of [entry] in (ADR-001 §4.1.1 "The balance"), or null when it gives
     * none: for a card (`CARD`), whose balance's sign is not documented, and for an unknown currency (`XXX`, or none).
     */
    private fun balanceCurrencyOf(entry: JsonObject): String? {
        if (entry["cash_account_type"].text() == "CARD") return null
        return entry["currency"].text()?.takeIf { it != "XXX" && CURRENCY.matches(it) }
    }

    private fun delivered(reference: String, entry: JsonObject, bank: String): DeliveredBankAccount {
        val number = entry["account_id"].asObject()?.let { id ->
            id["iban"].text()?.takeIf { it.isNotEmpty() } ?: id["other"].asObject()?.get("identification").text()?.takeIf { it.isNotEmpty() }
        }
        return DeliveredBankAccount(
            providerReference = reference,
            bankReference = bank,
            // Its description, else its product; never `name`, which is the holder's name.
            name = entry["details"].text()?.takeIf { it.isNotBlank() } ?: entry["product"].text()?.takeIf { it.isNotBlank() },
            kind = entry["cash_account_type"].text()?.takeIf { it.isNotEmpty() }?.let { KINDS[it] ?: it },
            // `XXX` is an unknown currency or several (Enable Banking FAQ): none.
            currency = entry["currency"].text()?.takeIf { it != "XXX" }?.let { code -> runCatching { CurrencyCode(code) }.getOrNull() },
            number = number?.let(::BankAccountNumber),
            // No balance is asked for at completion.
            result = BankAccountResult.NotRead(NotReadCause.NotSelected),
        )
    }

    // ---- Revoking (ADR-001 §4.1.1 "Revoking") -----------------------------------------------------------------------

    override suspend fun revoke(session: ConsentSession, context: RequestContext): RevokeResult {
        val ended = guarded({ RevokeResult.NotConfirmed }) { end(session, context) }
        when (ended) {
            RevokeResult.Ended, RevokeResult.EndedAtDataProvider -> log.outcome(EnableBankingConsentEvent.SessionEnded)
            RevokeResult.NotConfirmed -> log.outcome(EnableBankingConsentEvent.SessionEndNotConfirmed)
            is RevokeResult.StoppedByGate -> log.outcome(EnableBankingConsentEvent.SessionEndNotConfirmed, ended.reason)
        }
        return ended
    }

    private suspend fun end(session: ConsentSession, context: RequestContext): RevokeResult {
        val kept = BankSessionValue.read(session) ?: return RevokeResult.NotConfirmed
        val signer = RequestSigner.of(context.credential) ?: return RevokeResult.NotConfirmed
        val admission = context.gate.admit(attended(kept.bankReference))
        if (admission is Admission.Refused) return RevokeResult.StoppedByGate(admission.reason)
        val address = base.newBuilder().addPathSegment("sessions").addPathSegment(kept.sessionId).build()
        // A7 of UC-007: Enable Banking ends its session and closes the Bank's consent only where it can; any error is not confirmed.
        return when (exchange(signed(address, signer).delete().build(), context, EnableBankingConsentEvent.SessionEndNotConfirmed)) {
            is Reply.Ok -> RevokeResult.EndedAtDataProvider
            is Reply.Refused, is Reply.Declined, is Reply.Failed -> RevokeResult.NotConfirmed
        }
    }

    // ---- Presence and the return address ----------------------------------------------------------------------------

    // UC-001 BR-012: A Bank's requests can show the Operator's presence only when it requires no PSU header Slussen cannot truly send (S-B-46).
    override fun showsPresence(session: ConsentSession): Boolean = try {
        BankSessionValue.read(session)?.psuHeaders?.let { presenceHeaders(it, language()) != null } ?: false
    } catch (e: Exception) {
        false
    }

    override fun registeredReturnAddress(credential: Secret?): String? = try {
        RequestSigner.returnAddressOf(credential)
    } catch (e: Exception) {
        null
    }

    // ---- Requests ---------------------------------------------------------------------------------------------------

    private sealed interface Reply {
        /** A successful answer without an error code; [body] is its JSON object, or null when it holds none; [date] its time. */
        class Ok(val body: JsonObject?, val date: Instant?) : Reply

        /** A refusal without an error code: a status in the 400s other than 429, which asks to wait. */
        data object Declined : Reply
        class Refused(val code: String) : Reply
        class Failed(val kind: FailureKind) : Reply
    }

    private fun signed(address: HttpUrl, signer: RequestSigner): Request.Builder =
        Request.Builder().url(address).header("Authorization", "Bearer ${signer.token(clock.instant())}")

    private fun json(body: JsonObject) = body.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON)

    /**
     * Sends [request] and reads what came back: an answer with an `error` code is a refusal by that code, whatever its
     * HTTP status; one without a code is a success when its status says so, a service that cannot serve now for 429 and
     * 5xx, a refusal without a code for another status in the 400s, and an unreadable answer otherwise, a redirect
     * included. No text of the answer leaves this function.
     */
    private suspend fun exchange(request: Request, context: RequestContext, event: EnableBankingConsentEvent): Reply =
        when (val answer = http.send(request, context.timeout)) {
            is HttpAnswer.Failed -> Reply.Failed(answer.kind)
            is HttpAnswer.Answered -> {
                val body = objectOf(answer.body)
                val code = body?.get("error").text()
                if (code != null || answer.status !in 200..299) log.answered(event, answer.status)
                when {
                    code != null -> Reply.Refused(code)
                    answer.status in 200..299 -> Reply.Ok(body, answer.date)
                    answer.status == 429 || answer.status >= 500 -> Reply.Failed(FailureKind.ServiceUnavailable)
                    answer.status in 400..499 -> Reply.Declined
                    else -> Reply.Failed(FailureKind.UnreadableAnswer)
                }
            }
        }

    /**
     * Runs one contract call, which may not throw (ADR-001 §4.0): any exception other than a cancellation is answered
     * with [failed], and no text of it leaves the plug-in (NFR-012).
     */
    private inline fun <T> guarded(failed: () -> T, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        failed()
    }

    // A Bank Consent request is of kind Other and made with the Operator present (ADR-001 §4.0, S-B-46).
    private fun attended(bank: String?) = RequestSummary(RequestKind.Other, bank, null, unattended = false)

    private fun instantOrNull(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        val COUNTRY = Regex("[A-Z]{2}")
        val CURRENCY = Regex("[A-Z]{3}")
        const val PSU_TYPE = "personal"
        val JSON = "application/json".toMediaType()

        /** RFC 3339 with a timezone offset, as Enable Banking's reference gives `access.valid_until`. */
        val VALID_UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'").withZone(ZoneOffset.UTC)

        val CREDENTIALS_REFUSED = setOf("UNAUTHORIZED_ACCESS", "AUTHORIZATION_NOT_PROVIDED")

        /** Stands for a required header the list did not give readably, which no request can carry. */
        const val UNREADABLE_HEADER = "unreadable"

        /** `cash_account_type` as ADR-001 §4.1.1 names it; any other code is delivered as it is. */
        val KINDS = mapOf("CACC" to "current", "SVGS" to "savings", "CARD" to "credit card")
    }
}
