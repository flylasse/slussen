package slussen.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.service.FetchWarningUi
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.status.BlockedReason
import slussen.core.status.FirstRunCard
import slussen.core.status.Overview
import slussen.core.firstupload.FirstUploadMatchingStart
import slussen.core.operation.OperationKind
import slussen.core.operation.RunningOperation
import slussen.app.ui.components.FetchButtonUi
import slussen.app.ui.components.FetchResultKind
import slussen.app.ui.components.FetchResultUi
import slussen.app.ui.components.FirstUploadMatchingChip
import slussen.app.ui.components.FirstUploadMatchingUi

/** One Budget on the Overview: counts and blocked states only (UC-008 step 1, BR-007). */
data class BudgetLine(
    val budgetId: Long,
    val name: String,
    val currency: String,
    val counts: String,
    val blocked: List<String>,
    /** BR-006, BR-009 (`overview.html#recovery`): "Uploads wait for First Upload Matching" in its tone, or null. */
    val firstUploadMatching: FirstUploadMatchingChip? = null,
)

/** UC-008 A10, BR-011 (`overview.html#new-installation`, `#first-run-next`): the one card that leads to Settings. */
data class FirstRunUi(val title: String, val next: String, val then: String?)

/** What the Overview shows when Slussen opens (UC-008 steps 1-3, A1, A7, A8, A10). */
data class OverviewUi(
    val heading: String,
    val budgetAppRead: String,
    val unreadableNotices: List<String>,
    /** A8, BR-009: First Upload Matching with its explanation, reason lines and action; null while none is open. */
    val firstUploadMatching: FirstUploadMatchingUi?,
    val backupWarning: String?,
    val expiringConsents: List<String>,
    val budgets: List<BudgetLine>,
    val noBankAccountAdded: Boolean,
    val message: String?,
    /** UC-001 A11: the Fetch of all Data Providers is offered. */
    val canFetch: Boolean,
    /** P9-16: the Fetch button, "Fetching <Data Provider>…" with progress and disabled while a Fetch runs. */
    val fetchButton: FetchButtonUi,
    /** UC-001 A11, P9-16: the result of the last tap, one per Data Provider. */
    val fetchResults: List<FetchResultUi>,
    /** UC-001 A16: the warning of a Data Provider the Operator confirms before it is fetched, or null. */
    val fetchWarning: FetchWarningUi?,
    /** A10, BR-011: while no Bank Account is added, the only thing shown; null from the first added Bank Account on. */
    val firstRun: FirstRunUi? = null,
    /** P9-14: one line saying what a Fetch does in this build, next to the Fetch action. */
    val fetchNote: String? = null,
    /** Step 2 (P9-13): under the expiring Bank Consents, why they cannot be renewed in this build. */
    val expiringConsentNote: String? = null,
)

/** The Overview tab (UC-008), from stored data only. */
class OverviewViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<OverviewUi?>(null)
    val state: StateFlow<OverviewUi?> = mutableState

    private var opened = false

    /** UC-001 A11, P9-16: the result of the last tap, one per Data Provider. */
    private var fetchResults: List<FetchResultUi> = emptyList()

    /** P9-16: the taps of this screen, so that every result is shown as new. */
    private var taps = 0

    /** UC-001 A16: the Data Providers that warned and wait for the Operator's confirmation, first one shown. */
    private var pendingWarnings: List<FetchWarningUi> = emptyList()

    /** P9-16, ADR-007 §1: the operation that runs now. */
    private var running: RunningOperation? = services.running.value

    init {
        viewModelScope.launch {
            services.running.collect { now ->
                running = now
                mutableState.value = mutableState.value?.let { it.copy(fetchButton = fetchButton(it.canFetch)) }
            }
        }
    }

    /** UC-008 steps 1-3: the first load of a session records its start; later loads only read the stored status again. */
    fun load() {
        viewModelScope.launch { show(if (opened) services.status.overview() else services.status.open().also { opened = true }) }
    }

    /** P9-16 (`budget.html#fetch-states`): "Fetching <Data Provider>…", disabled, while a Fetch runs. */
    private fun fetchButton(canFetch: Boolean): FetchButtonUi {
        val now = running
        return if (now != null && now.kind == OperationKind.Fetch) {
            FetchButtonUi(Texts.fetching(now.subject), enabled = false, running = true)
        } else {
            FetchButtonUi(FETCH_ALL, enabled = canFetch, running = false)
        }
    }

    private fun show(overview: Overview) {
        val firstRun = overview.firstRun
        if (firstRun != null) {
            showFirstRun(overview, firstRun)
            return
        }
        val formats = services.formats
        val firstUploadMatching = overview.firstUploadMatching
        mutableState.value = OverviewUi(
            heading = "Overview · ${formats.dateTime(services.clock.instant())} · device time",
            budgetAppRead = overview.budgets.mapNotNull { it.lastReadAt }.maxOrNull()
                ?.let { "${overview.budgetAppName} · read ${formats.time(it)}" }
                ?: "${overview.budgetAppName} · not read yet",
            unreadableNotices = overview.unreadable.map { notice ->
                "Stored data could not be read: ${Texts.part(notice.part)}" +
                    if (notice.startedFromLastGoodCopy) " · started from its last good copy" else " · started empty"
            },
            // UC-008 BR-009: Every opening shows that Uploads wait for First Upload Matching, as information, never as an error.
            firstUploadMatching = firstUploadMatching?.let { Texts.firstUploadMatching(it, overview.budgetAppName) },
            backupWarning = overview.backupWarning?.let {
                "Backed-up data nears Android's backup limit: ${formats.megabytes(it.backedUpBytes)} of " +
                    "${formats.megabytes(it.limitBytes)}, Android's limit · above it Android backs up nothing"
            },
            expiringConsents = overview.expiringBankConsents.map {
                "Bank Consent of ${it.bankName} through ${it.dataProviderName} expires ${formats.date(it.expiresAt)} · Renew"
            },
            budgets = overview.budgets.map { budget ->
                BudgetLine(
                    budgetId = budget.budgetId,
                    name = budget.name,
                    currency = budget.currency.code,
                    counts = "Eligible ${budget.notYetUploaded.eligible} · waiting for a decision " +
                        "${budget.notYetUploaded.waitingForDecision}",
                    blocked = budget.blocked.filterNot { it.reason == BlockedReason.UploadsWaitForFirstUploadMatching }
                        .map { blocked -> "Blocked: ${blocked.subject}, ${Texts.blockedReason(blocked.reason)}" }
                        .distinct(),
                    // UC-008 BR-006: The Blocked Reason "Uploads wait for First Upload Matching", in the tone of BR-009.
                    firstUploadMatching = firstUploadMatching?.takeIf {
                        budget.blocked.any { it.reason == BlockedReason.UploadsWaitForFirstUploadMatching }
                    }?.let { FirstUploadMatchingChip(Texts.UPLOADS_WAIT_FOR_FIRST_UPLOAD_MATCHING, it.tone) },
                )
            },
            noBankAccountAdded = overview.noBankAccountAdded,
            message = null,
            canFetch = true,
            fetchButton = fetchButton(canFetch = true),
            fetchResults = fetchResults,
            fetchWarning = pendingWarnings.firstOrNull(),
            fetchNote = Texts.FETCH_IN_THIS_BUILD,
            // Step 2 (P9-13): the renew action needs Grant Bank Consent (UC-007), which this build does not have.
            expiringConsentNote = if (overview.expiringBankConsents.isEmpty()) {
                null
            } else {
                "${Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD}, so it cannot be renewed here."
            },
        )
    }

    // UC-008 BR-011: While no Bank Account is added, the Overview shows nothing but the card that leads to Settings and names the next thing, not even First Upload Matching.
    private fun showFirstRun(overview: Overview, card: FirstRunCard) {
        val (next, then) = card.next?.let(Texts::next) ?: ("Next: open Settings" to null)
        mutableState.value = OverviewUi(
            heading = "Overview · ${services.formats.dateTime(services.clock.instant())} · device time",
            budgetAppRead = "",
            unreadableNotices = emptyList(),
            firstUploadMatching = null,
            backupWarning = null,
            expiringConsents = emptyList(),
            budgets = emptyList(),
            noBankAccountAdded = overview.noBankAccountAdded,
            message = null,
            canFetch = false,
            fetchButton = fetchButton(canFetch = false),
            fetchResults = emptyList(),
            fetchWarning = null,
            firstRun = FirstRunUi("Set up Slussen in Settings", next, then),
        )
    }

    /** UC-001 A11: the Operator starts a Fetch of all Data Providers; the stored status is shown again afterwards. */
    fun fetchAll() {
        if (mutableState.value?.fetchButton?.enabled != true) return
        val tap = ++taps
        // P9-16: the result of the earlier tap goes while this one runs.
        fetchResults = emptyList()
        mutableState.value = mutableState.value?.copy(fetchResults = fetchResults, message = null)
        viewModelScope.launch {
            when (val start = services.fetch.fetchAll()) {
                is FetchStart.Unavailable ->
                    fetchResults = listOf(FetchResultUi(Texts.refusal(start.reason), null, FetchResultKind.NotFetched, tap))
                is FetchStart.Ran -> {
                    fetchResults = start.reports.filterNot { it is FetchReport.WarningToConfirm }.map { onOverview(it, tap) }
                    pendingWarnings = start.reports.filterIsInstance<FetchReport.WarningToConfirm>()
                        .map { Texts.fetchWarning(it, services.formats) }
                }
            }
            show(services.status.overview())
        }
    }

    /** UC-001 A11 (P9-16): on the Overview each result names its Data Provider. */
    private fun onOverview(report: FetchReport, tap: Int): FetchResultUi =
        Texts.fetchResult(report, services.formats, tap).let { it.copy(text = "${report.dataProviderName} · ${it.text}") }

    /** UC-001 A16: the Operator confirms the Fetch of the Data Provider that warned. */
    fun confirmFetch() {
        val warning = pendingWarnings.firstOrNull() ?: return
        pendingWarnings = pendingWarnings.drop(1)
        val tap = ++taps
        viewModelScope.launch {
            when (val start = services.fetch.fetch(warning.service, confirmedDespiteWarning = true)) {
                is FetchStart.Unavailable ->
                    fetchResults = fetchResults + FetchResultUi(Texts.refusal(start.reason), null, FetchResultKind.NotFetched, tap)
                is FetchStart.Ran -> fetchResults = fetchResults + start.reports.map { onOverview(it, tap) }
            }
            show(services.status.overview())
        }
    }

    /** UC-001 A16: the Operator cancels; that Data Provider is not fetched. */
    fun cancelFetch() {
        pendingWarnings = pendingWarnings.drop(1)
        mutableState.value = mutableState.value?.copy(fetchWarning = pendingWarnings.firstOrNull())
    }

    /**
     * UC-008 A9: the Operator chooses "Start First Upload Matching". Step 1 needs Compare With the Budget App (UC-003),
     * which no slice builds yet (O-P2-09), so First Upload Matching stays open and the screen says why.
     */
    fun startFirstUploadMatching() {
        viewModelScope.launch {
            val message = when (services.status.startFirstUploadMatching()) {
                FirstUploadMatchingStart.NoneOpen -> null
                FirstUploadMatchingStart.ComparisonNotAvailable -> mutableState.value?.firstUploadMatching?.buildNote
            }
            mutableState.value = mutableState.value?.copy(message = message)
        }
    }

    private companion object {
        const val FETCH_ALL = "Fetch all Data Providers"
    }
}
