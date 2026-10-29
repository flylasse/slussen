package slussen.provider.simplefin

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.provider.UnreadableReason
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.SensitiveApi
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A Fetch from SimpleFIN (ADR-001 §4.1, UC-001 step 4): one `GET {access}/accounts` of protocol version 2
 * (`version=2`, O-P2-60), which lists every account of the access credential with its balance. When a Bank Account is
 * read for its transactions, the request asks for the transactions of the fetch period (`start-date`, `end-date`);
 * when only balances are read, it asks for balances only (`balances-only`). SimpleFIN answers every account in that one
 * request, so the gate is asked once, for the whole Data Provider, and a listed account the request does not read
 * carries the balance of the same answer (`NotRead(NotSelected, balance)`, S-35). Each account's `balance-date` is the
 * time of its balance (S-T-04), and the answer's `errlist` is mapped to typed results without its texts (S-T-03, see
 * [errorsOf]).
 *
 * The plug-in passes every item on: it does not filter pending or zero-amount transactions, identify Bank Accounts or
 * start Back-offs, which are rules of the core (ADR-001 §4.1). No text of SimpleFIN's answer leaves this class except
 * the fields of the contract (NFR-012).
 */
internal class SimpleFinFetch(private val http: OkHttpClient, private val allowPlainHttp: Boolean) {
    private val json = Json { ignoreUnknownKeys = true }

    /** An answer the plug-in cannot read as an Account Set. */
    private class UnreadableAccountSet : Exception()

    @OptIn(SensitiveApi::class)
    suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse {
        // The stored credential is an access address that carries its own access; one that is none is refused unsent.
        val access = context.credential?.reveal()?.trim()?.toHttpUrlOrNull()
            ?.takeIf { (it.isHttps || allowPlainHttp) && it.username.isNotEmpty() }
            ?: return stopped(SourceOutcome.CredentialsRefused)
        val readsTransactions = readsTransactions(request.bankAccounts)
        val kind = if (readsTransactions) RequestKind.Transactions else RequestKind.Balance
        val admission = context.gate.admit(RequestSummary(kind, bankReference = null, bankAccountReference = null))
        if (admission is Admission.Refused) return stopped(SourceOutcome.StoppedByGate(admission.reason))
        val call = Request.Builder()
            .url(accountsAddress(access, request, readsTransactions))
            .header("Authorization", Credentials.basic(access.username, access.password))
            .build()
        return when (val answer = http.send(call, context.timeout)) {
            is HttpAnswer.Failed -> stopped(SourceOutcome.TransientFailure(answer.kind))
            is HttpAnswer.Answered -> when (answer.status) {
                in 200..299 -> try {
                    FetchResponse(SourceOutcome.Completed, emptyList(), accountsOf(answer.body, request.bankAccounts))
                } catch (e: UnreadableAccountSet) {
                    stopped(SourceOutcome.TransientFailure(FailureKind.UnreadableAnswer))
                }
                // 403: the access is revoked or not valid; 402: SimpleFIN no longer allows it until it is paid for.
                401, 402, 403 -> stopped(SourceOutcome.CredentialsRefused)
                else -> stopped(
                    SourceOutcome.TransientFailure(
                        if (answer.status >= 500 || answer.status == 429) FailureKind.ServiceUnavailable else FailureKind.UnreadableAnswer,
                    ),
                )
            }
        }
    }

    private fun stopped(outcome: SourceOutcome) = FetchResponse(outcome, emptyList(), emptyList())

    private fun readsTransactions(selection: BankAccountSelection): Boolean = when (selection) {
        is BankAccountSelection.AllForIdentification -> selection.read != ReadKind.Balance
        is BankAccountSelection.Selected -> selection.bankAccounts.any { it.read != ReadKind.Balance }
    }

    /**
     * `{access}/accounts` without the access in the address: with the fetch period as Unix times, the end exclusive
     * (the day after [FetchRequest.periodTo]), or with balances only. SimpleFIN leaves out pending transactions unless
     * asked; Slussen does not ask, since it takes in booked ones only (UC-001 BR-002).
     */
    private fun accountsAddress(access: HttpUrl, request: FetchRequest, readsTransactions: Boolean): HttpUrl =
        access.newBuilder().username("").password("").addPathSegment("accounts").apply {
            // Protocol version 2: without it the server chooses the version (O-P2-60).
            addQueryParameter("version", PROTOCOL_VERSION)
            if (readsTransactions) {
                addQueryParameter("start-date", startOf(request.periodFrom).toString())
                addQueryParameter("end-date", startOf(request.periodTo.plusDays(1)).toString())
            } else {
                addQueryParameter("balances-only", "1")
            }
        }.build()

    private fun startOf(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    /** What the request asks of the account [reference], or null when it does not read it. */
    private fun readOf(selection: BankAccountSelection, reference: String): ReadKind? = when (selection) {
        is BankAccountSelection.AllForIdentification -> selection.read
        is BankAccountSelection.Selected -> selection.bankAccounts.firstOrNull { it.providerReference == reference }?.read
    }

    private fun accountsOf(body: String, selection: BankAccountSelection): List<DeliveredBankAccount> {
        val set = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw UnreadableAccountSet()
        } catch (e: IllegalArgumentException) {
            throw UnreadableAccountSet()
        }
        val accounts = ((set as? JsonObject)?.get("accounts") as? JsonArray) ?: throw UnreadableAccountSet()
        val connections = (set["connections"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { connection -> connection.text("conn_id")?.let { it to connection } }
            .toMap()
        val listed = accounts.map { element ->
            val account = element as? JsonObject ?: throw UnreadableAccountSet()
            val reference = account.text("id") ?: throw UnreadableAccountSet()
            reference to account
        }
        val errors = errorsOf(set["errlist"], listed.map { (reference, account) -> reference to account.text("conn_id") })
        return listed.map { (reference, account) ->
            val currency = account.text("currency")?.let(::currencyOf)
            val read = readOf(selection, reference)
            val balance = account.text("balance")?.let(::amountOf)
            DeliveredBankAccount(
                providerReference = reference,
                bankReference = bankOf(account, connections),
                name = account.text("name"),
                // SimpleFIN describes no kind of account and gives no account number.
                kind = null,
                currency = currency,
                number = null,
                result = when {
                    // S-T-03: an error SimpleFIN reports for the account, or for its connection, replaces what it delivered.
                    reference in errors -> errors.getValue(reference)
                    // Listed, not asked for: the balance SimpleFIN gave with the list in the same answer (S-35).
                    read == null -> BankAccountResult.NotRead(NotReadCause.NotSelected, balance)
                    else -> BankAccountResult.Read(
                        transactions = if (read == ReadKind.Balance) {
                            emptyList()
                        } else {
                            ((account["transactions"] as? JsonArray) ?: JsonArray(emptyList())).map { transactionOf(it, currency) }
                        },
                        balance = if (read == ReadKind.Transactions) null else balance,
                        // S-T-04: the time SimpleFIN says the balance was reported; the core takes the time of the answer without it.
                        balanceReportedAt = if (read == ReadKind.Transactions || balance == null) null else balanceDateOf(account["balance-date"]),
                    )
                },
            )
        }
    }

    /**
     * SimpleFIN's error list (S-T-03, ADR-001 §4.1), protocol version 2: objects with a `code` that name an account
     * (`account_id`) or a connection (`conn_id`, every account of it). `con.auth` is [BankAccountResult.SignInNeeded]
     * (UC-001 A20); `act.failed` and `act.missingdata` are `Failed(Incomplete)` (UC-001 A8); any other code is
     * `Failed(UnreadableAnswer)`. An error that names no account or connection, an entry that is no object or has no
     * code, and an `errlist` that is no array, are ignored. Where several errors name one account, the Bank's sign-in
     * comes first, then `Failed(Incomplete)`. SimpleFIN's checklist asks an application to show the error messages
     * (`msg`); Slussen deviates on purpose and passes no SimpleFIN text on (NFR-012).
     *
     * [accounts] holds each listed account's identifier with its `conn_id`.
     */
    private fun errorsOf(errlist: JsonElement?, accounts: List<Pair<String, String?>>): Map<String, BankAccountResult> {
        val errors = mutableMapOf<String, BankAccountResult>()
        (errlist as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { error ->
            val code = error.text("code") ?: return@forEach
            val account = error.text("account_id")
            val connection = error.text("conn_id")
            val named = accounts.filter { (reference, conn) -> reference == account || (connection != null && conn == connection) }
            val result = when (code) {
                "con.auth" -> BankAccountResult.SignInNeeded
                "act.failed", "act.missingdata" -> BankAccountResult.Failed(FailureKind.Incomplete)
                else -> BankAccountResult.Failed(FailureKind.UnreadableAnswer)
            }
            named.forEach { (reference, _) ->
                val earlier = errors[reference]
                if (earlier == null || rank(result) < rank(earlier)) errors[reference] = result
            }
        }
        return errors
    }

    /** Which of two errors for one account wins: the lower rank. */
    private fun rank(result: BankAccountResult): Int = when (result) {
        BankAccountResult.SignInNeeded -> 0
        BankAccountResult.Failed(FailureKind.Incomplete) -> 1
        else -> 2
    }

    /** `balance-date`, a Unix time in seconds, or null when it is missing, 0 or unreadable. */
    private fun balanceDateOf(element: JsonElement?): Instant? {
        val seconds = (element as? JsonPrimitive)?.longOrNull ?: return null
        if (seconds <= 0L) return null
        return try {
            Instant.ofEpochSecond(seconds)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /**
     * The Bank of an account (O-P2-60): in protocol version 2 its connection (`conn_id`) names it, by `org_name`, else
     * by the connection's own `name`, else by the host of `org_url`; an answer of version 1 names it in the account's
     * `org` object, by `name`, else by `domain`.
     */
    private fun bankOf(account: JsonObject, connections: Map<String, JsonObject>): String? {
        val connection = account.text("conn_id")?.let(connections::get)
        if (connection != null) {
            return connection.nonBlank("org_name") ?: connection.nonBlank("name") ?: connection.nonBlank("org_url")?.let(::hostOf)
        }
        val org = account["org"] as? JsonObject ?: return null
        return org.nonBlank("name") ?: org.nonBlank("domain")
    }

    /** The host of `org_url`, which the protocol describes as the institution's domain name and shows as an address. */
    private fun hostOf(text: String): String? =
        (text.trim().toHttpUrlOrNull() ?: "https://${text.trim()}".toHttpUrlOrNull())?.host

    /**
     * One SimpleFIN transaction: its `id` is its Stable Reference, passed on exactly as delivered (ADR-008 §2); `posted`
     * (a Unix time) gives the booked date in UTC, or, for a pending one that is not posted yet, `transacted_at`. Its
     * `description` is the payee; protocol version 2 gives no payee or memo of its own, so there is no memo (O-P2-60).
     */
    private fun transactionOf(element: JsonElement, currency: CurrencyCode?): ProviderTransaction {
        val item = element as? JsonObject
            ?: return ProviderTransaction.Uninterpretable(null, null, currency, null, null, null, UnreadableReason.UnreadableStructure)
        val pending = (item["pending"] as? JsonPrimitive)?.booleanOrNull == true
        val payee = item.text("description")
        val dateField = listOf("posted", "transacted_at").map { item[it] }
        val dateProblem: UnreadableReason?
        val date: LocalDate?
        val posted = epochOf(dateField[0])
        val transacted = epochOf(dateField[1])
        when {
            posted is Epoch.At -> { date = posted.date; dateProblem = null }
            pending && transacted is Epoch.At -> { date = transacted.date; dateProblem = null }
            posted is Epoch.Unreadable -> { date = null; dateProblem = UnreadableReason.UnreadableDate }
            else -> { date = null; dateProblem = UnreadableReason.MissingDate }
        }
        val amountText = item.text("amount")
        val amount = amountText?.let(::amountOf)
        val amountProblem = when {
            amountText == null -> UnreadableReason.MissingAmount
            amount == null -> UnreadableReason.UnreadableAmount
            else -> null
        }
        val currencyProblem = if (currency == null) UnreadableReason.MissingCurrency else null
        val problem = dateProblem ?: amountProblem ?: currencyProblem
        return if (problem == null) {
            ProviderTransaction.Interpretable(item.text("id"), date!!, amount!!, currency!!, payee, memo = null, booked = !pending)
        } else {
            ProviderTransaction.Uninterpretable(date, amount, currency, payee, memo = null, booked = !pending, reason = problem)
        }
    }

    private sealed interface Epoch {
        data class At(val date: LocalDate) : Epoch

        data object Missing : Epoch

        data object Unreadable : Epoch
    }

    /** A Unix time in seconds; SimpleFIN gives 0 for a transaction that is not posted yet. */
    private fun epochOf(element: JsonElement?): Epoch {
        if (element == null || element is JsonNull) return Epoch.Missing
        val seconds = (element as? JsonPrimitive)?.longOrNull ?: return Epoch.Unreadable
        if (seconds == 0L) return Epoch.Missing
        return try {
            Epoch.At(LocalDate.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC))
        } catch (e: java.time.DateTimeException) {
            Epoch.Unreadable
        }
    }

    /** A decimal text in the currency unit, at most three decimals that are not zero (`Amount`, ADR-001 §4). */
    private fun amountOf(text: String): Amount? {
        if (!DECIMAL.matches(text.trim())) return null
        return try {
            Amount(BigDecimal(text.trim()).movePointRight(3).longValueExact())
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** An ISO 4217 code; SimpleFIN gives an address instead for a currency of its own, which is no currency here. */
    private fun currencyOf(text: String): CurrencyCode? =
        text.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }?.let(::CurrencyCode)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.nonBlank(key: String): String? = text(key)?.takeIf { it.isNotBlank() }

    private companion object {
        /** The SimpleFIN protocol version the plug-in reads: https://www.simplefin.org/protocol.html, `GET /accounts`. */
        const val PROTOCOL_VERSION = "2"

        val DECIMAL = Regex("""[+-]?\d+(\.\d+)?""")
    }
}
