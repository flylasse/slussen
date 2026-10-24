package slussen.core.request

import slussen.core.operation.OperationPermit
import slussen.core.port.BackOffCause
import slussen.core.port.FetchStore
import slussen.core.port.NewProviderRequest
import slussen.core.port.ProviderRequestKind
import slussen.core.port.ProviderRequestStore
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredDataProvider
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.request.RequestTimeout
import slussen.pluginapi.value.Secret
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The [RequestContext] the core hands a plug-in with every call (ADR-001 §4.0). */
class ServiceRequestContext(
    override val gate: RequestGate,
    override val timeout: RequestTimeout,
    override val credential: Secret?,
) : RequestContext

/**
 * The window over which a Request Limit is counted: the preceding 24 hours, the window the Data Providers' own limits
 * use (NFR-018, glossary Request Limit). A number set by the Data Providers, not by Slussen.
 */
val REQUEST_LIMIT_WINDOW: Duration = Duration.ofHours(24)

/**
 * The gate of a Budget App request (ADR-001 §4.0): it counts nothing, because the specification sets no limit, but
 * refuses every request once the operation that admitted it has ended or been cancelled.
 */
class BudgetAppGate(private val permit: OperationPermit) : RequestGate {
    override suspend fun admit(request: RequestSummary): Admission =
        if (permit.active) Admission.Allowed else Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded)
}

/**
 * When the Back-off of the whole Data Provider with cause Request To Wait that holds at [now] ends, or null when none
 * holds (UC-001 BR-004). A Back-off of one Bank, and one with cause Request Limit Warning, which holds for Scheduled
 * Fetches only (BR-015), are not counted here.
 */
fun List<StoredBackOff>.requestToWaitUntil(now: Instant): Instant? =
    filter { it.bankId == null && it.cause == BackOffCause.RequestToWait && it.endsAt.isAfter(now) }.maxOfOrNull { it.endsAt }

/** The Back-off of the whole Data Provider with cause Request Limit Warning that holds at [now], or null (BR-015). */
fun List<StoredBackOff>.requestLimitWarningAt(now: Instant): StoredBackOff? =
    filter { it.bankId == null && it.cause == BackOffCause.RequestLimitWarning && it.endsAt.isAfter(now) }.minByOrNull { it.startsAt }

/**
 * When the Request Limit of [dataProvider] frees up again, that is when the oldest counted Provider Request leaves the
 * 24-hour window (UC-001 A3), or null while fewer than the Request Limit were sent in the preceding 24 hours.
 */
suspend fun requestLimitFreesUpAt(requests: ProviderRequestStore, dataProvider: StoredDataProvider, now: Instant): Instant? {
    val sent = requests.sentSince(now.minus(REQUEST_LIMIT_WINDOW))
        .filter { it.dataProviderId == dataProvider.id }
        .map { it.sentAt }
        .sorted()
    if (sent.size < dataProvider.requestLimit) return null
    return sent[sent.size - dataProvider.requestLimit].plus(REQUEST_LIMIT_WINDOW)
}

/**
 * The gate of a Data Provider request (ADR-001 §4.0): it refuses with scope Source while a Back-off holds for the whole
 * Data Provider after it asked Slussen to wait, stores the Provider Request durably before it answers `Allowed`, and
 * refuses with scope Source once the Data Provider's Request Limit is reached in the preceding 24 hours. A credential
 * check is counted as request kind Other with the Operator present (ADR-001 §4.5).
 */
class DataProviderGate(
    private val dataProvider: StoredDataProvider,
    private val requests: ProviderRequestStore,
    private val fetches: FetchStore,
    private val clock: Clock,
    private val permit: OperationPermit,
) : RequestGate {
    override suspend fun admit(request: RequestSummary): Admission {
        if (!permit.active) return Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded)
        val now = clock.instant()
        // UC-001 BR-004: While the Data Provider asked Slussen to wait, no request is sent to it, also none the Operator starts.
        if (fetches.backOffsOf(dataProvider.id).requestToWaitUntil(now) != null) {
            return Admission.Refused(RefusalScope.Source, RefusalReason.BackOff)
        }
        val stored = NewProviderRequest(
            dataProviderId = dataProvider.id,
            bankAccountId = null,
            kind = kindOf(request.kind),
            unattended = false,
            sentAt = now,
        )
        // UC-001 BR-007: Slussen sends a Data Provider at most its Request Limit in any rolling 24 hours.
        if (!requests.admitWithinLimit(stored, now.minus(REQUEST_LIMIT_WINDOW), dataProvider.requestLimit)) {
            return Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached)
        }
        return Admission.Allowed
    }

    private fun kindOf(kind: RequestKind): ProviderRequestKind = when (kind) {
        RequestKind.Transactions -> ProviderRequestKind.Transactions
        RequestKind.Balance -> ProviderRequestKind.Balance
        RequestKind.Other, RequestKind.BudgetAppRead, RequestKind.BudgetAppWrite -> ProviderRequestKind.Other
    }
}
