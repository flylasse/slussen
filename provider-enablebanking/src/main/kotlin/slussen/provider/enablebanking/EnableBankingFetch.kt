package slussen.provider.enablebanking

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.BankOutcome
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.provider.UnreadableReason
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

private fun failed(kind: FailureKind) = BankAccountResult.Failed(kind)

/**
 * Enable Banking's Fetch (UC-001; ADR-001 §4.1, §4.1.1 "Fetching", "The balance", "Transactions", "Errors in a Fetch",
 * "The Operator's presence"). For each selected Bank Account of a Bank whose Bank Session the core handed over, its
 * balances and its transactions of the fetch period, page by page, each request admitted by the gate first; nothing for
 * a Bank without a session, and nothing at all when no Bank Account is selected. Every error is read from the answer's
 * `error` code, not its HTTP status. No contract call throws (ADR-001 §4.0), and no text of an answer leaves it
 * (NFR-012).
 */
internal class EnableBankingFetch(
    private val base: HttpUrl,
    private val http: OkHttpClient,
    private val clock: Clock,
    private val language: () -> String,
    private val log: FetchLog,
) {
    suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse {
        val response = try {
            read(request, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // ADR-001 §4.0: a contract call never throws; no text of the exception leaves the plug-in.
            FetchResponse(SourceOutcome.TransientFailure(FailureKind.ConnectionLost), emptyList(), emptyList())
        }
        log.outcome(response)
        return response
    }

    /** One selected Bank Account with the session that holds it, or none. */
    private class Planned(val read: BankAccountToRead, val session: BankSessionValue?)

    /** How the Fetch of one Data Provider stands while it reads: the results so far and what stopped. */
    private class Progress {
        val results = linkedMapOf<String, DeliveredBankAccount>()
        val bankOutcomes = mutableListOf<BankOutcome>()
        var source: SourceOutcome = SourceOutcome.Completed

        /** The time Enable Banking gave with its latest answer, so that the dates asked do not run ahead of it. */
        var answeredAt: Instant? = null

        val stopped: Boolean get() = source != SourceOutcome.Completed
    }

    private suspend fun read(request: FetchRequest, context: RequestContext): FetchResponse {
        // S-B-47: nothing is sent when no Bank Account is selected; identification needs no Fetch here (the Bank Accounts come with the Bank Consent).
        val selected = (request.bankAccounts as? BankAccountSelection.Selected)?.bankAccounts.orEmpty()
        if (selected.isEmpty()) return FetchResponse(SourceOutcome.Completed, emptyList(), emptyList())
        val signer = RequestSigner.of(context.credential) ?: return FetchResponse(SourceOutcome.CredentialsRefused, emptyList(), emptyList())
        // UC-001 BR-008: only the Bank Sessions the core handed over, each of the Bank it names.
        val sessions = request.sessions.mapNotNull { (bank, session) -> BankSessionValue.read(session)?.takeIf { it.bankReference == bank } }
        val planned = selected.distinctBy { it.providerReference }.map { read ->
            Planned(read, sessions.filter { read.providerReference in it.uids }.singleOrNull())
        }
        val progress = Progress()
        // ADR-001 §4.1.1: a selected Bank Account no Bank Session holds (its Bank has none, or the session kept no uid for it) is not contacted.
        // S-B-55: where a Bank Session could not keep all its Bank Accounts, that is the reason, without a remedy that cannot work.
        val full = sessions.filter { it.notAllKept }
        planned.filter { it.session == null }.forEach {
            progress.results[it.read.providerReference] = if (full.isEmpty()) {
                delivered(it.read, null, failed(FailureKind.UnreadableAnswer))
            } else {
                delivered(it.read, full.singleOrNull()?.bankReference, failed(FailureKind.NotHeldByBankSession))
            }
        }
        val byBank = planned.filter { it.session != null }.groupBy { it.session!!.bankReference }
        for ((_, ofBank) in byBank) {
            readBank(ofBank, request, context, signer, progress)
        }
        return FetchResponse(
            progress.source,
            progress.bankOutcomes,
            selected.distinctBy { it.providerReference }.map { progress.results.getValue(it.providerReference) },
        )
    }

    // UC-001 BR-010: A failure, a request to wait or a refused Bank Consent of one Bank leaves the other Banks' reads unaffected.
    private suspend fun readBank(ofBank: List<Planned>, request: FetchRequest, context: RequestContext, signer: RequestSigner, progress: Progress) {
        val session = ofBank.first().session!!
        val bank = session.bankReference
        // UC-001 BR-012: (S-B-46) the headers of the Operator's presence go all or none; a request without them is unattended.
        val presence = presenceOf(request.presence, session)
        var bankStopped = false
        for (planned in ofBank) {
            val reference = planned.read.providerReference
            when {
                progress.stopped -> progress.results[reference] = delivered(planned.read, bank, notReadAfter(progress.source))
                bankStopped -> progress.results[reference] = delivered(planned.read, bank, BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank))
                else -> {
                    val outcome = readBankAccount(planned.read, session, presence, request, context, signer, progress)
                    when (outcome) {
                        is AccountOutcome.Done -> progress.results[reference] = delivered(planned.read, bank, outcome.result, outcome.currency)
                        is AccountOutcome.BankStopped -> {
                            progress.bankOutcomes += outcome.outcome
                            bankStopped = true
                            progress.results[reference] = delivered(planned.read, bank, outcome.result)
                        }
                        is AccountOutcome.SourceStopped -> {
                            progress.source = outcome.outcome
                            progress.results[reference] = delivered(planned.read, bank, outcome.result)
                        }
                    }
                }
            }
        }
    }

    /**
     * What a Bank Account not read yet is when the whole Data Provider stopped (S-B-55): after a request that got no
     * answer, or the Data Provider's own failing, it is failed with that reason; otherwise not read with the source.
     */
    private fun notReadAfter(source: SourceOutcome): BankAccountResult =
        (source as? SourceOutcome.TransientFailure)?.let { failed(it.kind) } ?: BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource)

    /** What reading one Bank Account came to, and what it stopped beyond it. */
    private sealed interface AccountOutcome {
        class Done(val result: BankAccountResult, val currency: CurrencyCode? = null) : AccountOutcome
        class BankStopped(val outcome: BankOutcome, val result: BankAccountResult) : AccountOutcome
        class SourceStopped(val outcome: SourceOutcome, val result: BankAccountResult) : AccountOutcome
    }

    /** The headers of the Operator's presence a request carries, or none, and whether it is then unattended. */
    private class Presence(val headers: Map<String, String>) {
        val unattended: Boolean get() = headers.isEmpty()
    }

    private fun presenceOf(asked: OperatorPresence, session: BankSessionValue): Presence {
        // ADR-001 §4.1.1: a request of an Unattended Fetch carries no PSU header, as Enable Banking asks for data fetching the application starts itself.
        if (asked == OperatorPresence.Unattended) return Presence(emptyMap())
        return Presence(presenceHeaders(session.psuHeaders, language()).orEmpty())
    }

    private suspend fun readBankAccount(
        read: BankAccountToRead,
        session: BankSessionValue,
        presence: Presence,
        request: FetchRequest,
        context: RequestContext,
        signer: RequestSigner,
        progress: Progress,
    ): AccountOutcome {
        val reference = read.providerReference
        val uid = session.uids.getValue(reference)
        val bank = session.bankReference
        val currency = session.balanceCurrencies[reference]?.let { runCatching { CurrencyCode(it) }.getOrNull() }
        var balance: ChosenBalance? = null
        // ADR-001 §4.1.1 "The balance": a card, or a Bank Account of unknown currency, gets no balance, so none is asked for.
        if (read.read != ReadKind.Transactions && currency != null) {
            val summary = RequestSummary(RequestKind.Balance, bank, reference, presence.unattended)
            when (val step = send(accountAddress(uid, "balances"), summary, presence, context, signer, progress)) {
                is Step.Ok -> balance = chosenBalance(step.body, currency)
                // S-B-55: a failure that concerns only the balance gives no Account Balance, and the transactions are still read.
                is Step.Stop -> if (!step.concernsThisBankAccountOnly) return step.outcome(bank, laterPage = false)
            }
        }
        if (read.read == ReadKind.Balance) return AccountOutcome.Done(BankAccountResult.Read(emptyList(), balance?.amount, balance?.reportedAt), currency)
        return when (val transactions = readTransactions(uid, reference, bank, presence, request, context, signer, progress)) {
            is Pages.Read -> AccountOutcome.Done(BankAccountResult.Read(transactions.items, balance?.amount, balance?.reportedAt), currency)
            is Pages.Stopped -> transactions.outcome
        }
    }

    private sealed interface Pages {
        class Read(val items: List<ProviderTransaction>) : Pages
        class Stopped(val outcome: AccountOutcome) : Pages
    }

    /**
     * The transactions of one Bank Account for the fetch period, page by page (ADR-001 §4.1.1 "Fetching"): while an
     * answer carries a `continuation_key`, also with an empty page, it asks again with that key and the same other
     * parameters, as kind Other; a page the gate refuses, a `WRONG_CONTINUATION_KEY` or a key already used in the same
     * read ends the read as `Failed(Incomplete)` with nothing of it, and the Request Limit bounds the rest.
     */
    private suspend fun readTransactions(
        uid: String,
        reference: String,
        bank: String,
        presence: Presence,
        request: FetchRequest,
        context: RequestContext,
        signer: RequestSigner,
        progress: Progress,
    ): Pages {
        val (from, to) = period(request.periodFrom, request.periodTo, progress.answeredAt)
        val items = mutableListOf<ProviderTransaction>()
        val used = mutableSetOf<String>()
        var key: String? = null
        while (true) {
            // UC-001 BR-012: (S-B-46) one read of a Bank Account's transactions is one access: its first page has its kind, each further page kind Other.
            val kind = if (key == null) RequestKind.Transactions else RequestKind.Other
            val address = accountAddress(uid, "transactions").newBuilder()
                .addQueryParameter("date_from", from.toString())
                .addQueryParameter("date_to", to.toString())
                .apply { key?.let { addQueryParameter("continuation_key", it) } }
                .build()
            val page = when (val step = send(address, RequestSummary(kind, bank, reference, presence.unattended), presence, context, signer, progress)) {
                is Step.Ok -> step.body
                is Step.Stop -> return Pages.Stopped(step.outcome(bank, laterPage = key != null))
            }
            val entries = page["transactions"] as? JsonArray ?: return Pages.Stopped(AccountOutcome.Done(failed(FailureKind.UnreadableAnswer)))
            items += entries.map(::transactionOf)
            val next = when (val given = page["continuation_key"]) {
                null, JsonNull -> null
                else -> given.text() ?: return Pages.Stopped(AccountOutcome.Done(failed(FailureKind.UnreadableAnswer)))
            }?.takeIf { it.isNotEmpty() } ?: return Pages.Read(items)
            // A8 of UC-001 (W6-0 item 5): a continuation key already used in the same read ends it as incomplete.
            if (!used.add(next)) return Pages.Stopped(AccountOutcome.Done(failed(FailureKind.Incomplete)))
            key = next
        }
    }

    /**
     * The fetch period asked, in UTC dates as Enable Banking assumes (ADR-001 §4.1.1): it never ends after today in UTC,
     * by the time Enable Banking gave with its latest answer of this Fetch where that is earlier than the device's clock,
     * so that a clock that runs ahead does not move it (E3 review item 4); when its end moves, its start moves as far,
     * so its length, an Operator Setting (BR-009), is kept.
     */
    private fun period(from: LocalDate, to: LocalDate, answeredAt: Instant?): Pair<LocalDate, LocalDate> {
        val device = clock.instant()
        val now = answeredAt?.takeIf { it.isBefore(device) } ?: device
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (!to.isAfter(today)) return from to to
        val shift = ChronoUnit.DAYS.between(today, to)
        return minOf(from.minusDays(shift), today) to today
    }

    private fun accountAddress(uid: String, what: String): HttpUrl =
        base.newBuilder().addPathSegment("accounts").addPathSegment(uid).addPathSegment(what).build()

    // ---- One request ---------------------------------------------------------------------------------------------------

    private sealed interface Step {
        class Ok(val body: JsonObject) : Step

        /** The request did not give a page or balances; [outcome] says what that ends. */
        class Stop(private val stop: Stopping) : Step {
            fun outcome(bank: String, laterPage: Boolean): AccountOutcome = stop.outcome(bank, laterPage)

            /** A failure the Data Provider named for this Bank Account alone, not for its Bank, the session or the Data Provider. */
            val concernsThisBankAccountOnly: Boolean get() = stop is Stopping.Account
        }
    }

    /** What ends a read, by its scope (ADR-001 §4.1 continuation protocol). */
    private sealed interface Stopping {
        fun outcome(bank: String, laterPage: Boolean): AccountOutcome

        /** A failure of this Bank Account only. */
        class Account(private val kind: FailureKind) : Stopping {
            override fun outcome(bank: String, laterPage: Boolean) = AccountOutcome.Done(failed(kind))
        }

        /** The gate refused: nothing is sent in its scope; a read whose later page is refused is incomplete. */
        class Refused(private val admission: Admission.Refused) : Stopping {
            override fun outcome(bank: String, laterPage: Boolean): AccountOutcome {
                val incomplete = failed(FailureKind.Incomplete)
                return when (admission.scope) {
                    RefusalScope.BankAccount -> AccountOutcome.Done(if (laterPage) incomplete else BankAccountResult.NotRead(NotReadCause.RefusedByGate))
                    // The place of UC-001 A13 (phase E6): a refusal for a reached Unattended Access limit arrives here with scope BankAccount.
                    RefusalScope.Bank -> AccountOutcome.BankStopped(
                        BankOutcome.StoppedByGate(bank, admission.reason),
                        if (laterPage) incomplete else BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank),
                    )
                    RefusalScope.Source -> AccountOutcome.SourceStopped(
                        SourceOutcome.StoppedByGate(admission.reason),
                        if (laterPage) incomplete else BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource),
                    )
                }
            }
        }

        /** UC-001 A5, A9: the Bank asks to wait, or no longer accepts its Bank Consent: that Bank only. */
        class Bank(private val outcome: (String) -> BankOutcome) : Stopping {
            override fun outcome(bank: String, laterPage: Boolean) =
                AccountOutcome.BankStopped(outcome(bank), BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank))
        }

        /** UC-001 A5, A6, A7: the whole Data Provider stops; after a failure in transit this Bank Account is failed with it (S-B-55). */
        class Source(private val outcome: SourceOutcome) : Stopping {
            override fun outcome(bank: String, laterPage: Boolean) = AccountOutcome.SourceStopped(
                outcome,
                (outcome as? SourceOutcome.TransientFailure)?.let { failed(it.kind) } ?: BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource),
            )
        }
    }

    /**
     * Admits [summary] through the gate (ADR-001 §4.0), sends the signed request with the headers of [presence], and reads
     * the answer by its `error` code first (ADR-001 §4.1.1 "Errors in a Fetch"). The client follows no redirect and sends
     * nothing again on its own.
     */
    private suspend fun send(
        address: HttpUrl,
        summary: RequestSummary,
        presence: Presence,
        context: RequestContext,
        signer: RequestSigner,
        progress: Progress,
    ): Step {
        val admission = context.gate.admit(summary)
        if (admission is Admission.Refused) return Step.Stop(Stopping.Refused(admission))
        val request = Request.Builder().url(address).get()
            .header("Authorization", "Bearer ${signer.token(clock.instant())}")
            .apply { presence.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return when (val answer = http.send(request, context.timeout)) {
            // ADR-001 §4.0: a timeout or a lost connection is a TransientFailure of the read.
            is HttpAnswer.Failed -> Step.Stop(Stopping.Source(SourceOutcome.TransientFailure(answer.kind)))
            is HttpAnswer.Answered -> {
                answer.date?.let { progress.answeredAt = it }
                val body = objectOf(answer.body)
                val code = body?.get("error").text()
                if (code != null || answer.status !in 200..299) log.answered(answer.status)
                when {
                    // A5 of UC-001 (S-B-55): the status that asks to wait decides, whatever code comes with it; the Bank's own rate limit is that Bank's.
                    answer.status == 429 && code == BANK_RATE_LIMIT -> Step.Stop(Stopping.Bank { BankOutcome.AskedToWait(it, null) })
                    answer.status == 429 -> Step.Stop(Stopping.Source(SourceOutcome.AskedToWait(null)))
                    code != null -> Step.Stop(stoppingFor(code))
                    answer.status in 200..299 -> body?.let { Step.Ok(it) } ?: Step.Stop(Stopping.Account(FailureKind.UnreadableAnswer))
                    // A6 of UC-001: Enable Banking itself cannot serve now; no Back-off (BR-005).
                    answer.status >= 500 -> Step.Stop(Stopping.Source(SourceOutcome.TransientFailure(FailureKind.ServiceUnavailable)))
                    // A redirect, which is not followed, and any other answer without a code: unreadable for this Bank Account.
                    else -> Step.Stop(Stopping.Account(FailureKind.UnreadableAnswer))
                }
            }
        }
    }

    private fun stoppingFor(code: String): Stopping = when (code) {
        // A5 of UC-001: the Bank's rate limit is a request to wait of that Bank, with no end: the default wait applies (BR-004).
        BANK_RATE_LIMIT -> Stopping.Bank { BankOutcome.AskedToWait(it, null) }
        // A9 of UC-001: the Bank no longer accepts its Bank Consent.
        "EXPIRED_SESSION", "REVOKED_SESSION", "CLOSED_SESSION", "SESSION_DOES_NOT_EXIST" -> Stopping.Bank { BankOutcome.ConsentRefused(it) }
        // A7 of UC-001: the application's credentials are refused.
        "UNAUTHORIZED_ACCESS", "AUTHORIZATION_NOT_PROVIDED" -> Stopping.Source(SourceOutcome.CredentialsRefused)
        // UC-001 BR-005: (A8) the Bank failed for this Bank Account; no Back-off and no retry.
        "ASPSP_ERROR", "ASPSP_TIMEOUT" -> Stopping.Account(FailureKind.ServiceUnavailable)
        "WRONG_CONTINUATION_KEY" -> Stopping.Account(FailureKind.Incomplete)
        // A8 of UC-001 (S-B-55): the Bank does not give the fetch period asked for; nothing is asked again with another period.
        "WRONG_TRANSACTIONS_PERIOD" -> Stopping.Account(FailureKind.PeriodNotGiven)
        // A8 of UC-001: any other code, the PSU header codes included.
        else -> Stopping.Account(FailureKind.UnreadableAnswer)
    }

    // ---- The balance ---------------------------------------------------------------------------------------------------

    private class ChosenBalance(val amount: Amount, val reportedAt: Instant?)

    /**
     * The Account Balance of [answer] (ADR-001 §4.1.1 "The balance", risk R5): the balance of type `ITBD`, else `CLBD`,
     * of the chosen type the one in [currency]; none when that is not exactly one or neither type is given. Its amount
     * keeps its own sign, a minus being money owed; reported at `last_change_date_time`, else the start of
     * `reference_date` in UTC, else none.
     */
    private fun chosenBalance(answer: JsonObject, currency: CurrencyCode): ChosenBalance? {
        val entries = (answer["balances"] as? JsonArray)?.mapNotNull { it.asObject() } ?: return null
        val type = BOOKED_BALANCE_TYPES.firstOrNull { wanted -> entries.any { it["balance_type"].text() == wanted } } ?: return null
        val chosen = entries.filter { it["balance_type"].text() == type && it["balance_amount"].asObject()?.get("currency").text() == currency.code }
            .singleOrNull() ?: return null
        val amount = chosen["balance_amount"].asObject()?.get("amount").text()?.let { signedAmount(it) } ?: return null
        val reportedAt = chosen["last_change_date_time"].text()?.let(::instantOrNull)
            ?: chosen["reference_date"].text()?.let(::dateOrNull)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        return ChosenBalance(amount, reportedAt)
    }

    // ---- Transactions --------------------------------------------------------------------------------------------------

    /**
     * One entry of `transactions` (ADR-001 §4.1.1 "Transactions"; UC-001 BR-002, BR-016), exactly as the rule states it:
     * nothing missing is taken from another field, and what cannot be read is `Uninterpretable` with what could be read.
     */
    private fun transactionOf(element: JsonElement): ProviderTransaction {
        val entry = element.asObject() ?: return unreadable(UnreadableReason.UnreadableStructure)
        // UC-001 BR-002: booked for BOOK only; a pending, held, scheduled, cancelled or rejected one is passed on as not booked.
        val booked = when (entry["status"].text()) {
            "BOOK" -> true
            in NOT_BOOKED -> false
            else -> null
        }
        // UC-001 BR-016: the booking date only, never the value or transaction date.
        val date = entry["booking_date"]
        val bookedDate = date.text()?.let(::dateOrNull)
        val dateReason = when {
            date == null || date is JsonNull -> UnreadableReason.MissingDate
            bookedDate == null -> UnreadableReason.UnreadableDate
            else -> null
        }
        val money = entry["transaction_amount"].asObject()
        val indicator = entry["credit_debit_indicator"].text()
        val given = money?.get("amount")
        // UC-001 BR-016: (W6-0 item 6) the amount without a sign, signed by its indicator; a minus sign, no indicator, more decimals than thousandths or no number is unreadable.
        val amount = given.text()?.let { unsignedAmount(it, indicator) }
        val amountReason = when {
            given == null || given is JsonNull -> UnreadableReason.MissingAmount
            amount == null -> UnreadableReason.UnreadableAmount
            else -> null
        }
        val currencyText = money?.get("currency")
        val currency = currencyText.text()?.let { code -> runCatching { CurrencyCode(code) }.getOrNull() }
        val currencyReason = when {
            currencyText == null || currencyText is JsonNull -> UnreadableReason.MissingCurrency
            currency == null -> UnreadableReason.UnreadableStructure
            else -> null
        }
        // UC-001 BR-016: the payee is the other party's name, exactly as delivered: the creditor of an outflow, the debtor of an inflow.
        val party = when (indicator) {
            "DBIT" -> "creditor"
            "CRDT" -> "debtor"
            else -> null
        }
        val payee = party?.let { textAt(entry, it, "name") } ?: Field.Absent
        // UC-001 BR-016: the memo is the remittance lines in their order, joined by one space, each exactly as delivered; none without a line.
        val memo = memoOf(entry["remittance_information"])
        val structureReason = if (payee is Field.Unreadable || memo is Field.Unreadable) UnreadableReason.UnreadableStructure else null
        val reason = (if (booked == null) UnreadableReason.UnreadableStructure else null)
            ?: dateReason ?: amountReason ?: currencyReason ?: structureReason
        return if (reason == null) {
            // Q-05 (issue #7): Enable Banking gives no Stable Reference, also when the entry gives an `entry_reference`.
            ProviderTransaction.Interpretable(null, bookedDate!!, amount!!, currency!!, payee.value, memo.value, booked!!)
        } else {
            ProviderTransaction.Uninterpretable(bookedDate, amount, currency, payee.value, memo.value, booked, reason)
        }
    }

    /** A text field of an entry: absent, unreadable, or given exactly as delivered. */
    private sealed interface Field {
        val value: String?

        data object Absent : Field {
            override val value: String? = null
        }

        data object Unreadable : Field {
            override val value: String? = null
        }

        class Given(override val value: String) : Field
    }

    private fun textAt(entry: JsonObject, objectName: String, fieldName: String): Field {
        val holder = entry[objectName]
        if (holder == null || holder is JsonNull) return Field.Absent
        val found = holder.asObject() ?: return Field.Unreadable
        val value = found[fieldName]
        if (value == null || value is JsonNull) return Field.Absent
        return value.text()?.let { Field.Given(it) } ?: Field.Unreadable
    }

    private fun memoOf(element: JsonElement?): Field {
        if (element == null || element is JsonNull) return Field.Absent
        val lines = (element as? JsonArray)?.map { it.text() ?: return Field.Unreadable } ?: return Field.Unreadable
        return if (lines.isEmpty()) Field.Absent else Field.Given(lines.joinToString(" "))
    }

    private fun unreadable(reason: UnreadableReason) = ProviderTransaction.Uninterpretable(null, null, null, null, null, null, reason)

    /** [text] as an amount without a sign, signed by [indicator] (CRDT an inflow, DBIT an outflow), or null when unreadable. */
    private fun unsignedAmount(text: String, indicator: String?): Amount? {
        if (!UNSIGNED.matches(text)) return null
        val thousandths = thousandthsOf(text) ?: return null
        return when (indicator) {
            "CRDT" -> Amount(thousandths)
            "DBIT" -> Amount(-thousandths)
            else -> null
        }
    }

    /** [text] as an amount with its own sign (a balance), or null when unreadable. */
    private fun signedAmount(text: String): Amount? {
        if (!SIGNED.matches(text)) return null
        return thousandthsOf(text)?.let(::Amount)
    }

    private fun thousandthsOf(text: String): Long? = try {
        BigDecimal(text).movePointRight(3).longValueExact().takeIf { kotlin.math.abs(it) < MAX_THOUSANDTHS }
    } catch (e: ArithmeticException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    private fun dateOrNull(text: String): LocalDate? = try {
        if (DATE.matches(text)) LocalDate.parse(text) else null
    } catch (e: DateTimeParseException) {
        null
    }

    private fun instantOrNull(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun delivered(read: BankAccountToRead, bank: String?, result: BankAccountResult, currency: CurrencyCode? = null) = DeliveredBankAccount(
        providerReference = read.providerReference,
        bankReference = bank,
        name = null,
        kind = null,
        currency = currency,
        number = null,
        result = result,
    )

    private companion object {
        const val BANK_RATE_LIMIT = "ASPSP_RATE_LIMIT_EXCEEDED"

        /** ADR-001 §4.1.1: interim booked, else closing booked. */
        val BOOKED_BALANCE_TYPES = listOf("ITBD", "CLBD")

        val NOT_BOOKED = setOf("PDNG", "HOLD", "SCHD", "CNCL", "RJCT")

        val UNSIGNED = Regex("""\d+(\.\d{1,3})?""")
        val SIGNED = Regex("""-?\d+(\.\d{1,3})?""")
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        /** The entity model's precision `12,3`: at most 9 digits before the decimal point. */
        const val MAX_THOUSANDTHS = 1_000_000_000_000L
    }
}
