package slussen.budgetapp.ynab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.value.Amount
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Invented values for the tests of the YNAB writer, each easy to search for in a log line or a `toString()`
 * (ADR-006 §6). No value is of the Operator or of a real Budget.
 */
internal object YnabWriteExamples {
    const val TOKEN = "invented-ynab-token-W8RT2"
    const val BUDGET = "budget-pl4nted-7781"
    const val ACCOUNT = "account-pl4nted-0001"
    const val OTHER_ACCOUNT = "account-pl4nted-0002"
    const val CLOSED_ACCOUNT = "account-pl4nted-closed"
    const val DELETED_BUDGET_ACCOUNT = "account-pl4nted-deleted"
    const val NO_PAYEE_ACCOUNT = "account-pl4nted-nopayee"
    const val MISSING_ACCOUNT = "account-pl4nted-missing"
    const val TRANSFER_PAYEE = "payee-pl4nted-transfer-0002"
    const val ACCOUNT_NAME = "Planted Account Harbor Checking"
    const val OTHER_ACCOUNT_NAME = "Planted Account Harbor Card"
    const val PAYEE_A = "Planted Payee Juniper Mill"
    const val MEMO_A = "Planted memo violet anchor"
    const val PAYEE_C = "Planted Payee Copper Kettle"
    const val MEMO_C = "Planted memo amber gate"
    const val ID_A = "SLS1-pl4ntedimportidentifier0001"
    const val ID_B = "SLS1-pl4ntedimportidentifier0002"
    const val ID_C = "SLS1-pl4ntedimportidentifier0003"
    const val ID_T = "SLS1-pl4ntedimportidentifier0004"
    const val REF_A = "tx-pl4nted-0101"
    const val REF_B = "tx-pl4nted-0102"
    const val REF_C = "tx-pl4nted-0103"
    const val REF_T = "tx-pl4nted-0104"
    const val REF_COUNTERPART = "tx-pl4nted-0105"
    const val AMOUNT_A = -731942L
    const val AMOUNT_B = 58217L
    const val AMOUNT_C = -26453L
    const val AMOUNT_T = -415009L
    const val REFUSAL = "Planted refusal: the date is invalid"

    val DATE_A: LocalDate = LocalDate.of(2026, 10, 14)
    val DATE_B: LocalDate = LocalDate.of(2026, 10, 15)

    val ITEM_A = CreateItem(ACCOUNT, ID_A, DATE_A, Amount(AMOUNT_A), PAYEE_A, MEMO_A, cleared = true, transferBudgetAccountReference = null)
    val ITEM_B = CreateItem(ACCOUNT, ID_B, DATE_B, Amount(AMOUNT_B), null, null, cleared = false, transferBudgetAccountReference = null)
    val ITEM_C = CreateItem(ACCOUNT, ID_C, DATE_B, Amount(AMOUNT_C), PAYEE_C, MEMO_C, cleared = true, transferBudgetAccountReference = null)

    /** A linked transfer from [ACCOUNT] to [OTHER_ACCOUNT]: the outflow side, with a memo and no payee of its own. */
    val ITEM_T = CreateItem(ACCOUNT, ID_T, DATE_A, Amount(AMOUNT_T), null, MEMO_C, cleared = true, transferBudgetAccountReference = OTHER_ACCOUNT)

    /** Every planted value no log line and no `toString()` may show (ADR-006 §2, NFR-007, NFR-008). */
    val planted: List<String> = listOf(
        TOKEN, BUDGET, ACCOUNT, OTHER_ACCOUNT, TRANSFER_PAYEE, ACCOUNT_NAME, OTHER_ACCOUNT_NAME, PAYEE_A, MEMO_A, PAYEE_C, MEMO_C,
        ID_A, ID_B, ID_C, ID_T, REF_A, REF_B, REF_C, REF_T, REF_COUNTERPART, AMOUNT_A.toString(), AMOUNT_B.toString(),
        AMOUNT_C.toString(), AMOUNT_T.toString(), "731942", "58217", "26453", "415009", REFUSAL, "pl4nted",
    )

    /** The body YNAB's documentation gives for one transaction it saved (OpenAPI `TransactionDetail`), invented. */
    fun saved(
        reference: String,
        importIdentifier: String?,
        account: String = ACCOUNT,
        amount: Long = AMOUNT_A,
        cleared: String = "uncleared",
        transferAccount: String? = null,
        transferTransaction: String? = null,
        deleted: Boolean = false,
    ): String = """{"id":"$reference","date":"2026-10-14","amount":$amount,"memo":"$MEMO_A","cleared":"$cleared","approved":false,""" +
        """"flag_color":null,"account_id":"$account","account_name":"$ACCOUNT_NAME","payee_id":"payee-x","payee_name":"$PAYEE_A",""" +
        """"category_id":null,"category_name":null,"transfer_account_id":${text(transferAccount)},""" +
        """"transfer_transaction_id":${text(transferTransaction)},"matched_transaction_id":null,""" +
        """"import_id":${text(importIdentifier)},"deleted":$deleted,"subtransactions":[]}"""

    private fun text(value: String?): String = if (value == null) "null" else "\"$value\""

    /** YNAB's `201` to a list: the saved transactions and the duplicates (OpenAPI `SaveTransactionsResponse`). */
    fun listCreated(saved: List<String>, duplicates: List<String> = emptyList()): MockResponse = MockResponse().setResponseCode(201).setBody(
        """{"data":{"transaction_ids":[],"transactions":[${saved.joinToString(",")}],""" +
            """"duplicate_import_ids":[${duplicates.joinToString(",") { "\"$it\"" }}],"server_knowledge":91}}""",
    )

    /** YNAB's `201` to a single transaction. */
    fun singleCreated(saved: String): MockResponse =
        MockResponse().setResponseCode(201).setBody("""{"data":{"transaction_ids":[],"transaction":$saved,"server_knowledge":92}}""")

    /** YNAB's error answer (the documentation's Errors section: `id`, `name`, `detail`). */
    fun error(status: Int, id: String, name: String, detail: String = REFUSAL): MockResponse =
        MockResponse().setResponseCode(status).setBody("""{"error":{"id":"$id","name":"$name","detail":"$detail"}}""")

    /** YNAB's Budget Accounts of the Budget, with their transfer payees (OpenAPI `Account`). */
    val ACCOUNTS_ANSWER: String = """{"data":{"server_knowledge":5,"accounts":[
        {"id":"$ACCOUNT","name":"$ACCOUNT_NAME","type":"checking","on_budget":true,"closed":false,"balance":0,"transfer_payee_id":"payee-pl4nted-transfer-0001","deleted":false},
        {"id":"$OTHER_ACCOUNT","name":"$OTHER_ACCOUNT_NAME","type":"creditCard","on_budget":true,"closed":false,"balance":0,"transfer_payee_id":"$TRANSFER_PAYEE","deleted":false},
        {"id":"$CLOSED_ACCOUNT","name":"Planted Closed","type":"savings","on_budget":true,"closed":true,"balance":0,"transfer_payee_id":"payee-pl4nted-transfer-0003","deleted":false},
        {"id":"$DELETED_BUDGET_ACCOUNT","name":"Planted Deleted","type":"savings","on_budget":true,"closed":true,"balance":0,"transfer_payee_id":"payee-pl4nted-transfer-0004","deleted":true},
        {"id":"$NO_PAYEE_ACCOUNT","name":"Planted No Payee","type":"savings","on_budget":true,"closed":false,"balance":0,"transfer_payee_id":null,"deleted":false}
    ]}}"""

    /** Every request [server] received that was not taken yet, in order. */
    fun MockWebServer.received(): List<RecordedRequest> = generateSequence { takeRequest(200, TimeUnit.MILLISECONDS) }.toList()

    /** For every transaction a recorded create request asked for, whether it asked for it approved. */
    fun approvedFlagsOf(requests: List<RecordedRequest>): List<Boolean> = requests
        .filter { it.method == "POST" && it.requestUrl!!.encodedPath.endsWith("/transactions") }
        .flatMap { request ->
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val transactions: List<JsonObject> = body["transactions"]?.jsonArray?.map { it.jsonObject } ?: listOf(body.getValue("transaction").jsonObject)
            transactions.map { it["approved"]?.jsonPrimitive?.booleanOrNull ?: true }
        }
}
