package slussen.core.fetch

import slussen.core.operation.OperationRefusal
import slussen.core.port.ServiceKey
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import java.time.Instant

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
     * certainty (step 6, BR-011); [notAdded] counts the delivered Bank Accounts that match none of them (A18).
     * [waitingUntil] is set when the Data Provider asked Slussen to wait while delivering (A5), [warning] when it warned
     * about its Request Limit (A15).
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

/** The outcome of starting a Fetch (UC-001 step 1, A11, precondition). */
sealed interface FetchStart {
    /** One report per Data Provider fetched, in the order of the build (A11). */
    data class Ran(val reports: List<FetchReport>) : FetchStart

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : FetchStart
}
