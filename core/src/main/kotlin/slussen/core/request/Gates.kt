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
 * The Unattended Access limit: at most four accesses to a Bank Account's transactions, and four to its balance, in any
 * 24 hours without the holder present, the limit PSD2 sets (C-006, UC-001 BR-012); not a Slussen choice.
 */
const val UNATTENDED_ACCESS_LIMIT: Int = 4

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
 * The Bank Accounts a Fetch reads, by their reference at the Data Provider in [bankAccountIds], so that an unattended
 * request is kept with the Bank Account it reads (UC-001 BR-012). Every request of a [scheduled] Fetch is unattended
 * (BR-013); in a Fetch the Operator starts, a request is unattended when it does not show the Bank that he is present.
 */
class FetchReads(
    val bankAccountIds: Map<String, Long>,
    val scheduled: Boolean,
    /**
     * The time the Unattended Access limit is counted at: the start of the Fetch, when the core chose what to read, so
     * that the core's choice and the gate's refusal cannot disagree (E6 review item 3); null counts at each request.
     */
    val countedAt: Instant? = null,
)

/**
 * The gate of a Data Provider request (ADR-001 §4.0): it refuses with scope Source while a Back-off holds for the whole
 * Data Provider after it asked Slussen to wait, stores the Provider Request durably before it answers `Allowed`, and
 * refuses with scope Source once the Data Provider's Request Limit is reached in the preceding 24 hours; in a Scheduled
 * Fetch through a Data Provider that needs a Bank Consent, it refuses with scope BankAccount a read of a Bank Account's
 * transactions or balance that reached the Unattended Access limit ([UnattendedAccesses]), before storing it. A credential
 * check is counted as request kind Other with the Operator present (ADR-001 §4.5).
 */
class DataProviderGate(
    private val dataProvider: StoredDataProvider,
    private val requests: ProviderRequestStore,
    private val fetches: FetchStore,
    private val clock: Clock,
    private val permit: OperationPermit,
    /** Set for the requests of a Fetch (UC-001 BR-012, BR-013), null for every other request. */
    private val reads: FetchReads? = null,
) : RequestGate {
    override suspend fun admit(request: RequestSummary): Admission {
        if (!permit.active) return Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded)
        val now = clock.instant()
        // UC-001 BR-004: While the Data Provider asked Slussen to wait, no request is sent to it, also none the Operator starts.
        if (fetches.backOffsOf(dataProvider.id).requestToWaitUntil(now) != null) {
            return Admission.Refused(RefusalScope.Source, RefusalReason.BackOff)
        }
        // UC-001 BR-012: A request that does not show the Bank that the Operator is present is unattended, whatever started it (S-B-46).
        val unattended = reads?.scheduled == true || request.unattended
        // UC-001 BR-013: Every unattended request is kept as an unattended Provider Request, with the Bank Account it reads when it reads one, so that it is counted.
        val stored = NewProviderRequest(
            dataProviderId = dataProvider.id,
            bankAccountId = if (unattended) request.bankAccountReference?.let { reads?.bankAccountIds?.get(it) } else null,
            kind = kindOf(request.kind),
            unattended = unattended,
            sentAt = now,
        )
        // UC-001 BR-012: A Scheduled Fetch reads the transactions of a Bank Account at most 4 times and its balance at most 4 times in any 24 hours, through a Data Provider that needs a Bank Consent; a Fetch the Operator starts is never refused by this count.
        if (limitsUnattendedAccess(stored)) {
            val bankAccountId = stored.bankAccountId!!
            // A13 of UC-001: refused with scope BankAccount before it is stored, so nothing is sent for that read; a further page (kind Other) is no further access.
            // E6 review item 3: counted at the time the core chose what to read, so that the two cannot disagree.
            if (UnattendedAccesses.of(requests, dataProvider.id, reads?.countedAt ?: now).reached(bankAccountId, stored.kind)) {
                return Admission.Refused(RefusalScope.BankAccount, RefusalReason.UnattendedAccessLimitReached)
            }
        }
        // UC-001 BR-007: Slussen sends a Data Provider at most its Request Limit in any rolling 24 hours.
        if (!requests.admitWithinLimit(stored, now.minus(REQUEST_LIMIT_WINDOW), dataProvider.requestLimit)) {
            return Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached)
        }
        return Admission.Allowed
    }

    /** A read of one Bank Account's transactions or balance in a Scheduled Fetch through a Data Provider that needs a Bank Consent. */
    private fun limitsUnattendedAccess(request: NewProviderRequest): Boolean =
        reads?.scheduled == true && dataProvider.needsBankConsent && request.unattended && request.bankAccountId != null &&
            request.kind != ProviderRequestKind.Other

    private fun kindOf(kind: RequestKind): ProviderRequestKind = when (kind) {
        RequestKind.Transactions -> ProviderRequestKind.Transactions
        RequestKind.Balance -> ProviderRequestKind.Balance
        RequestKind.Other, RequestKind.BudgetAppRead, RequestKind.BudgetAppWrite -> ProviderRequestKind.Other
    }
}

/**
 * The gate of the requests of a Fetch through a Data Provider that needs a Bank Consent (ADR-001 §4.0; UC-001 BR-004,
 * A5): it refuses with scope Bank a request to a Bank, named by its reference, whose Back-off with cause Request To Wait
 * holds, so that nothing is sent to that Bank, also one that began during this Fetch; the other requests go to
 * [dataProvider], the Data Provider's own gate. [bankIds] gives the `BANK` of each Bank's reference, its `BANK.name`.
 */
class BankBackOffGate(
    private val dataProvider: RequestGate,
    private val dataProviderId: Long,
    private val bankIds: Map<String, Long>,
    private val fetches: FetchStore,
    private val clock: Clock,
) : RequestGate {
    override suspend fun admit(request: RequestSummary): Admission {
        val bankId = request.bankReference?.let { bankIds[it] }
        if (bankId != null) {
            val now = clock.instant()
            // UC-001 BR-004: A Back-off of one Bank holds for every request to that Bank.
            val waits = fetches.backOffsOf(dataProviderId).any {
                it.bankId == bankId && it.cause == BackOffCause.RequestToWait && it.endsAt.isAfter(now)
            }
            if (waits) return Admission.Refused(RefusalScope.Bank, RefusalReason.BackOff)
        }
        return dataProvider.admit(request)
    }
}
