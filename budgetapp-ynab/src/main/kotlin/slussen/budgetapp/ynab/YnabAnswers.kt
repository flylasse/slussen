package slussen.budgetapp.ynab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.Request
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransferLink
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.SensitiveApi
import java.time.LocalDate

/**
 * What a refusal of YNAB means (ADR-001 §4.2.5 Refusals, from YNAB's documentation, section Errors): only `401` (a
 * token missing, invalid, revoked or expired) and `403` with YNAB's code `403.3` (`unauthorized_scope`) say that the
 * access token is not accepted. Every other `403`, `403.1` (`subscription_lapsed`), `403.2` (`trial_expired`), `403.4`
 * (`data_limit_reached`), `403.5` (`email_unconfirmed`) and one whose code cannot be read, is the Budget App's refusal
 * with its reason. Every other status is neither.
 */
internal enum class Refusal { OfTheToken, ByTheBudgetApp }

internal fun HttpAnswer.Answered.refusal(): Refusal? = when (status) {
    401 -> Refusal.OfTheToken
    403 -> {
        val error = errorOf(body)
        // Either name of the code is enough: a refusal of the token stops more than one of the Budget App does.
        if (error?.textOrNull("id") == "403.3" || error?.textOrNull("name") == "unauthorized_scope") Refusal.OfTheToken else Refusal.ByTheBudgetApp
    }
    else -> null
}

/** YNAB's `error.detail` of an error answer, or null when it cannot be read or is blank. */
internal fun HttpAnswer.Answered.errorDetail(): String? = errorOf(body)?.textOrNull("detail")?.takeIf { it.isNotBlank() }

private fun errorOf(body: String): JsonObject? = parsedOrNull { Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject }

/** [read] of an answer, or null when the answer cannot be read: malformed JSON, a missing field or a field of another type. */
internal inline fun <T> parsedOrNull(read: () -> T): T? = try {
    read()
} catch (e: IllegalArgumentException) {
    // kotlinx' SerializationException and a value of another type are IllegalArgumentExceptions.
    null
} catch (e: IllegalStateException) {
    null
} catch (e: NoSuchElementException) {
    null
} catch (e: java.time.format.DateTimeParseException) {
    null
}

internal fun JsonObject.textOrNull(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

/** A JSON field that must be present and a whole number (YNAB's `int64`). */
internal fun JsonObject.whole(key: String): Long = requireNotNull(getValue(key).jsonPrimitive.longOrNull)

/**
 * One YNAB transaction in the normalized read model (ADR-001 §4.2.3); throws when a field cannot be read. What depends
 * on YNAB's behaviour is decided here (C-009):
 * - amounts are milliunits, thousandths of the currency unit, the precision of [Amount];
 * - `entry`: a transaction with an `import_id` was written by an importer (Imported); one without it that is a side of
 *   a linked transfer (`transfer_transaction_id`) is a side YNAB may have created itself (TransferSide); every other
 *   one was entered by hand (Typed);
 * - `transferLink`: the other side's `transfer_transaction_id` and `transfer_account_id`, on both sides;
 * - `state`: a transaction YNAB reports as `deleted` is Deleted, unless [mergedInto] names the transaction YNAB merged it
 *   into (another transaction of the same answer names it as its `matched_transaction_id`): then it is Merged;
 * - `cleared`: YNAB's `cleared` and `reconciled` are Cleared, `uncleared` is not; `approved` as YNAB reports it.
 */
internal fun transactionOf(transaction: JsonObject, mergedInto: Map<String, String> = emptyMap()): BudgetAppTransactionRead {
    val reference = transaction.text("id")
    val importIdentifier = transaction.textOrNull("import_id")
    val otherSide = transaction.textOrNull("transfer_transaction_id")
    val deleted = transaction["deleted"]?.jsonPrimitive?.booleanOrNull == true
    return BudgetAppTransactionRead(
        reference = reference,
        budgetAccountReference = transaction.text("account_id"),
        date = LocalDate.parse(transaction.text("date")),
        amount = Amount(transaction.whole("amount")),
        payee = transaction.textOrNull("payee_name"),
        memo = transaction.textOrNull("memo"),
        importIdentifier = importIdentifier,
        entry = when {
            importIdentifier != null -> EntryKind.Imported
            otherSide != null -> EntryKind.TransferSide
            else -> EntryKind.Typed
        },
        transferLink = otherSide?.let { TransferLink(it, transaction.text("transfer_account_id")) },
        state = when {
            !deleted -> ReadState.Present
            reference in mergedInto -> ReadState.Merged(mergedInto.getValue(reference))
            else -> ReadState.Deleted
        },
        cleared = transaction.text("cleared") != "uncleared",
        approved = requireNotNull(transaction.getValue("approved").jsonPrimitive.booleanOrNull),
    )
}

/** `<base>budgets/<budget>/<segments>`, each part encoded as one path segment. */
internal fun HttpUrl.ofBudget(budgetReference: String, vararg segments: String): HttpUrl =
    newBuilder().addPathSegment("budgets").addPathSegment(budgetReference).apply { segments.forEach { addPathSegment(it) } }.build()

/**
 * A request to [url] with the stored access token as a bearer token (ADR-001 §4.2.5). The token is revealed only here,
 * into the request's header; this file references no log (ADR-006 §2, L-04).
 */
@OptIn(SensitiveApi::class)
internal fun authorized(url: HttpUrl, context: RequestContext): Request.Builder {
    val credential = requireNotNull(context.credential) { "The core contacts YNAB only with stored credentials." }
    return Request.Builder().url(url).bearer(credential)
}
