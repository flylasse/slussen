package slussen.core.fetch

import slussen.core.comparison.ComparisonReport
import slussen.core.operation.OperationRefusal
import slussen.core.port.ServiceKey
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What one Fetch of a Data Provider that the Operator started came to (UC-001), as the screens show it. Every report
 * names the Data Provider; a Fetch of several Data Providers (A11) gives one report per Data Provider.
 */
sealed interface FetchReport {
    val service: ServiceKey
    val dataProviderName: String

    /**
     * Steps 4-6 and 11: the Data Provider delivered its Bank Accounts, which are kept as Offered Bank Accounts; the
     * Fetch is recorded as Fetched. [identified] names the Bank Accounts the Operator added that were identified with
     * certainty (step 6, BR-011); [notAdded] counts the delivered Bank Accounts that match none of them, Left-Out Bank
     * Accounts not counted (A18); [sharedReference] counts the delivered Bank Accounts whose reference another one in
     * the same answer has, which are neither kept nor identified (A10, UC-009 BR-004).
     * [waitingUntil] is set when the Data Provider asked Slussen to wait while delivering (A5), [warning] when it warned
     * about its Request Limit (A15). [failed] names the identified Bank Accounts the Data Provider reported a failure
     * for, whose stored data stays unchanged (A8). [notDelivered] names the selected Bank Accounts the answer did not
     * contain at all (A19), [signInNeeded] those whose Bank's sign-in at the Data Provider is needed (A20), and
     * [balancesInOtherCurrency] those whose balance was reported in another currency and not stored (A21).
     */
    data class Fetched(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val fetchedAt: Instant,
        val offered: Int,
        val identified: List<String>,
        val notAdded: Int,
        val waitingUntil: Instant?,
        val warning: RequestLimitWarningShown?,
        val failed: List<String> = emptyList(),
        val sharedReference: Int = 0,
        val notDelivered: List<String> = emptyList(),
        val signInNeeded: List<String> = emptyList(),
        val balancesInOtherCurrency: List<BalanceInOtherCurrency> = emptyList(),
    ) : FetchReport

    /**
     * A2: within the Fetch Cooldown the Data Provider is not contacted, except to read the Bank Accounts added or
     * identified again since the last successful Fetch ([read], when there are any and the Request Limit allows it;
     * [requestLimitFreesUpAt] when the Request Limit holds them back). [lastFetchAt] is the Fetch the stored data comes
     * from; [contactsAgainAt] when a new Fetch contacts the Data Provider again.
     */
    data class ServedFromFetchCooldown(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val lastFetchAt: Instant,
        val contactsAgainAt: Instant,
        val read: Fetched?,
        val requestLimitFreesUpAt: Instant?,
    ) : FetchReport

    /** A1, and A5 when nothing was delivered: "waiting for the provider" until [until]. */
    data class WaitingForProvider(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val until: Instant,
    ) : FetchReport

    /** A3: "Request Limit reached" until the oldest counted Provider Request leaves the 24-hour window. */
    data class RequestLimitReached(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val freesUpAt: Instant,
    ) : FetchReport

    /** A16 step 1: the Data Provider warned about its Request Limit; the Operator confirms the Fetch or cancels it. */
    data class WarningToConfirm(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val warning: RequestLimitWarningShown,
    ) : FetchReport

    /** A6: no usable answer; stored data unchanged, no Back-off, the Operator can fetch again at any time (BR-005). */
    data class Failed(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val kind: FailureKind,
        val failedAt: Instant,
    ) : FetchReport

    /** A7, UC-014 BR-005: "credentials refused", removed by entering the credentials again (UC-014). */
    data class CredentialsRefused(
        override val service: ServiceKey,
        override val dataProviderName: String,
    ) : FetchReport

    /** Precondition, UC-014 BR-005: no credentials entered; "credentials missing", removed by entering them (UC-014). */
    data class CredentialsMissing(
        override val service: ServiceKey,
        override val dataProviderName: String,
    ) : FetchReport

    /**
     * A Data Provider that needs a Bank Consent is not contacted by this build: its Bank Sessions come with the slice
     * that builds Grant Bank Consent (UC-007), and without a valid Bank Consent no Bank is contacted (BR-008).
     */
    data class NeedsBankConsent(
        override val service: ServiceKey,
        override val dataProviderName: String,
    ) : FetchReport

    /** The gate refused a request because the operation had ended; nothing was sent and nothing recorded. */
    data class NotSent(
        override val service: ServiceKey,
        override val dataProviderName: String,
        val reason: RefusalReason,
    ) : FetchReport
}

/**
 * A Request Limit warning of a Data Provider (UC-001 A15, A16, BR-015): when it warned, and when the Request Limit
 * warning pause from the first warning ends; further requests can get Slussen's access to the Data Provider disabled.
 */
data class RequestLimitWarningShown(val warnedAt: Instant, val endsAt: Instant)

/**
 * UC-001 A21: the Data Provider [dataProviderName] reported the balance of [bankAccountName] in [reported], not in the
 * Bank Account's own currency [expected], at the Fetch of [fetchedAt]; it was not stored and nothing was converted.
 */
data class BalanceInOtherCurrency(
    val bankAccountId: Long,
    val bankAccountName: String,
    val dataProviderName: String,
    val reported: CurrencyCode,
    val expected: CurrencyCode,
    val fetchedAt: Instant,
)

/**
 * UC-001 A21 step 2: what the last Fetch of each Bank Account found that nothing stored can show, kept in memory while
 * the app runs. The entity model has no place for a balance that was not stored, so it is shown until a later Fetch
 * stores the Bank Account's balance in its own currency, or the app restarts.
 */
class FetchNotices {
    private val balances = ConcurrentHashMap<Long, BalanceInOtherCurrency>()

    /** The balance of [bankAccountId] that its last Fetch reported in another currency, or null. */
    fun balanceInOtherCurrency(bankAccountId: Long): BalanceInOtherCurrency? = balances[bankAccountId]

    internal fun noteBalanceInOtherCurrency(notice: BalanceInOtherCurrency) {
        balances[notice.bankAccountId] = notice
    }

    internal fun balanceStored(bankAccountId: Long) {
        balances.remove(bankAccountId)
    }
}

/** The outcome of starting a Fetch (UC-001 step 1, A11, precondition). */
sealed interface FetchStart {
    /**
     * One report per Data Provider fetched, in the order of the build (A11), and the Comparison that followed (step
     * 10), or null when the Fetch ended before it (A1, A3, A6, A7).
     */
    data class Ran(val reports: List<FetchReport>, val comparison: ComparisonReport? = null) : FetchStart

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : FetchStart
}
