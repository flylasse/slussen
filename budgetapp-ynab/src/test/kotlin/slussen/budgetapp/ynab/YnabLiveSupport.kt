package slussen.budgetapp.ynab

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.fail
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.TransactionLookup
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.Secret
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

/**
 * What the live tests of ADR-005 §7 (E-10) share: the two test-only properties of ADR-003 §4, handed to the test task
 * only with `-PincludeLive`, a YNAB part on YNAB's public interface, and a plain client for the few requests the part
 * does not make (reading the Budget Accounts, deleting a transaction, a transaction entered as by hand). No value of
 * a Budget is written into the repository; nothing here runs unless the Operator runs a test tagged `live`.
 */
internal class YnabLive {
    val token: String = property("slussenLiveTestYnabAccessToken", "a personal access token of the throwaway Budget's YNAB user")
    val budget: String = property("slussenLiveTestYnabBudgetId", "the id of the throwaway YNAB Budget (C-011)")

    /** A tag of this run, so that every Import Identifier and memo a run writes is new and can be found again. */
    val run: String = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMddHHmmss"))

    /** YNAB gives every date in UTC (ADR-001 §4.2.5 Dates); every invented date lies some days before today there. */
    val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
    val app = YnabBudgetApp(SilentLog)

    fun context() = TestRequestContext(timeout = Duration.ofSeconds(30), credential = Secret(token))

    fun importIdentifier(case: Int, n: Int): String = "E10-$run-$case-$n"

    fun item(account: String, case: Int, n: Int, amount: Long, date: LocalDate = today.minusDays(3), cleared: Boolean = false, other: String? = null) =
        CreateItem(account, importIdentifier(case, n), date, Amount(amount), if (other == null) "E10 live payee $case" else null, "E10 $run case $case item $n", cleared, other)

    fun create(vararg items: CreateItem): List<ItemOutcome> = when (val result = runBlocking { app.writer.create(budget, items.toList(), context()) }) {
        is CreateResult.Answered -> result.outcomes
        else -> fail("the write was not answered: $result")
    }

    fun markCleared(reference: String): ClearResult = runBlocking { app.writer.markCleared(budget, reference, context()) }

    fun lookup(reference: String): BudgetAppTransactionRead? =
        when (val result = runBlocking { app.reader.readTransaction(budget, reference, context()) }) {
            is ReadResult.Answered -> (result.value as? TransactionLookup.Found)?.transaction
            else -> fail("the read of one transaction failed: $result")
        }

    fun changes(since: ChangeCursor?, from: LocalDate = today.minusDays(60)): ChangeSet =
        when (val result = runBlocking { app.reader.readChanges(budget, since, from, context()) }) {
            is ReadResult.Answered -> result.value
            else -> fail("the read of changes failed: $result")
        }

    /** The Budget's name and its Budget Accounts (OpenAPI `Account`), read as YNAB answers them. */
    fun accounts(): List<JsonObject> = data(get("budgets/$budget/accounts")).getValue("accounts").jsonArray.map { it.jsonObject }

    /**
     * The open Budget Accounts of the throwaway Budget (C-011: at least three in one currency, one of them for a credit
     * card), refusing to go on unless the Budget's name says it is a throwaway one, so that no live test writes into a
     * Budget of the Operator's money by a mistaken property.
     */
    fun openAccounts(): OpenAccounts {
        val name = data(get("budgets")).getValue("budgets").jsonArray.map { it.jsonObject }.single { it.text("id") == budget }.text("name")
        if (!name.contains("throwaway", ignoreCase = true)) {
            fail<Unit>("The Budget of slussenLiveTestYnabBudgetId is not named as a throwaway Budget: the live tests that write run only on a Budget whose name contains \"throwaway\" (C-011).")
        }
        val open = accounts().filter { it.flag("closed") == false && it.flag("deleted") == false }
        val card = open.firstOrNull { it.text("type") == "creditCard" } ?: fail("The throwaway Budget needs an open credit card Budget Account (ADR-005 §7).")
        val others = open.filter { it.text("type") != "creditCard" }
        if (others.size < 2) fail<Unit>("The throwaway Budget needs at least three open Budget Accounts, one for a credit card (ADR-005 §7).")
        return OpenAccounts(others[0].text("id"), others[1].text("id"), card.text("id"))
    }

    data class OpenAccounts(val first: String, val second: String, val card: String)

    fun transferPayeeOf(account: String): String = accounts().single { it.text("id") == account }.text("transfer_payee_id")

    /** A transaction created without an Import Identifier, which YNAB treats as entered by hand (OpenAPI `import_id`). */
    fun typedEntry(account: String, amount: Long, date: LocalDate, memo: String, payeeId: String? = null): String {
        val payee = if (payeeId == null) """"payee_name":"E10 typed entry"""" else """"payee_id":"$payeeId""""
        val body = """{"transaction":{"account_id":"$account","date":"$date","amount":$amount,$payee,"memo":"$memo","cleared":"uncleared","approved":false}}"""
        return data(created("budgets/$budget/transactions", body, expect = 201)).getValue("transaction").jsonObject.text("id")
    }

    /** A single create as YNAB's documentation describes it, for the status of its answer alone. */
    fun singleCreateStatus(item: CreateItem): Int {
        val body = """{"transaction":{"account_id":"${item.budgetAccountReference}","date":"${item.date}","amount":${item.amount.thousandths},""" +
            """"payee_name":"E10 live payee","memo":null,"cleared":"uncleared","approved":false,"import_id":"${item.importIdentifier}"}}"""
        return call(Request.Builder().url(BASE + "budgets/$budget/transactions").post(body.toRequestBody(JSON))).first
    }

    fun delete(reference: String) {
        val (status, _) = call(Request.Builder().url(BASE + "budgets/$budget/transactions/$reference").delete())
        if (status != 200) fail<Unit>("YNAB answered $status to deleting a transaction")
    }

    fun get(path: String): String = call(Request.Builder().url(BASE + path)).let { (status, body) -> if (status == 200) body else fail("YNAB answered $status to a read") }

    private fun created(path: String, body: String, expect: Int): String =
        call(Request.Builder().url(BASE + path).post(body.toRequestBody(JSON))).let { (status, answer) -> if (status == expect) answer else fail("YNAB answered $status") }

    private fun call(builder: Request.Builder): Pair<Int, String> =
        http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { it.code to it.body!!.string() }

    fun data(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject

    private fun JsonObject.flag(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    /** Polls [check] every [every] until it is not null or [limit] has passed, for a step the Operator takes in YNAB's screens. */
    fun <T> waitFor(what: String, limit: Duration = Duration.ofMinutes(20), every: Duration = Duration.ofSeconds(45), check: () -> T?): T {
        println("E-10, waiting for you (at most ${limit.toMinutes()} minutes): $what")
        val deadline = System.nanoTime() + limit.toNanos()
        while (System.nanoTime() < deadline) {
            check()?.let { return it }
            Thread.sleep(every.toMillis())
        }
        return fail("E-10: not seen within ${limit.toMinutes()} minutes: $what")
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }

    companion object {
        const val BASE = YnabBudgetApp.PUBLIC_INTERFACE
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** ADR-003 §4, ADR-004 §2: a missing property fails with its name and where it goes, never passes silently. */
        fun property(name: String, what: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: fail("The live test needs $name ($what): set it in ~/.gradle/gradle.properties or as ORG_GRADLE_PROJECT_$name, and run with -PincludeLive.")
    }
}
