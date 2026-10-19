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
 * A Fetch from SimpleFIN (ADR-001 §4.1, UC-001 step 4): one `GET {access}/accounts`, which lists every account of the
 * access credential. When a Bank Account is read for its transactions, the request asks for the transactions of the
 * fetch period (`start-date`, `end-date`); when only balances are read, it asks for balances only (`balances-only`).
 * SimpleFIN answers every account in that one request, so the gate is asked once, for the whole Data Provider.
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
        return accounts.map { element ->
            val account = element as? JsonObject ?: throw UnreadableAccountSet()
            val reference = account.text("id") ?: throw UnreadableAccountSet()
            val org = account["org"] as? JsonObject
            val currency = account.text("currency")?.let(::currencyOf)
            val read = readOf(selection, reference)
            DeliveredBankAccount(
                providerReference = reference,
                bankReference = org?.text("name") ?: org?.text("domain"),
                name = account.text("name"),
                // SimpleFIN describes no kind of account and gives no account number.
                kind = null,
                currency = currency,
                number = null,
                result = when (read) {
                    null -> BankAccountResult.NotRead(NotReadCause.NotSelected)
                    else -> BankAccountResult.Read(
                        transactions = if (read == ReadKind.Balance) {
                            emptyList()
                        } else {
                            ((account["transactions"] as? JsonArray) ?: JsonArray(emptyList())).map { transactionOf(it, currency) }
                        },
                        balance = if (read == ReadKind.Transactions) null else account.text("balance")?.let(::amountOf),
                    )
                },
            )
        }
    }

    /**
     * One SimpleFIN transaction: its `id` is its Stable Reference, passed on exactly as delivered (ADR-008 §2); `posted`
     * (a Unix time) gives the booked date in UTC, or, for a pending one that is not posted yet, `transacted_at`.
     */
    private fun transactionOf(element: JsonElement, currency: CurrencyCode?): ProviderTransaction {
        val item = element as? JsonObject
            ?: return ProviderTransaction.Uninterpretable(null, null, currency, null, null, null, UnreadableReason.UnreadableStructure)
        val pending = (item["pending"] as? JsonPrimitive)?.booleanOrNull == true
        val payee = item.text("payee") ?: item.text("description")
        val memo = item.text("memo")
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
            ProviderTransaction.Interpretable(item.text("id"), date!!, amount!!, currency!!, payee, memo, booked = !pending)
        } else {
            ProviderTransaction.Uninterpretable(date, amount, currency, payee, memo, booked = !pending, reason = problem)
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

    private companion object {
        val DECIMAL = Regex("""[+-]?\d+(\.\d+)?""")
    }
}
