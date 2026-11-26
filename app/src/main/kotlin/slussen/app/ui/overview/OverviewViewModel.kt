package slussen.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import slussen.app.ui.ReadAgain
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.components.BudgetAppLineUi
import slussen.core.status.StatusesAsOf
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
    /** UC-003 step 7, A1 step 2 (`overview.html#read-one-failed`): when its statuses were given; null with nothing to compare. */
    val statuses: String? = null,
    /** UC-003 A1 step 2: the remedy of statuses that are not current, a read of the Budget App; null while they are. */
    val readAgainNote: String? = null,
)

/** A notice of the Overview: its [title] and what it means with what comes next, the [detail] (NFR-023). */
data class NoticeUi(val title: String, val detail: String)

/** UC-008 A10, BR-011 (`overview.html#new-installation`, `#first-run-next`): the one card that leads to Settings. */
data class FirstRunUi(val title: String, val next: String, val then: String?)

/** What the Overview shows when Slussen opens (UC-008 steps 1-3, A1, A7, A8, A10). */
data class OverviewUi(
    val heading: String,
    /** FR-040, UC-003 (`overview.html#read-one-failed`): the line of the Budget App with Read again and its answer. */
    val budgetAppLine: BudgetAppLineUi,
    /** A1, BR-012 (`overview.html#damaged`, `#damaged-critical`): one notice for each part that could not be read. */
    val unreadableNotices: List<NoticeUi>,
    /** A8, BR-009: First Upload Matching with its explanation, reason lines and action; null while none is open. */
    val firstUploadMatching: FirstUploadMatchingUi?,
    /** A7, BR-010 (`overview.html#backup-warning`): the backup size warning, or null. */
    val backupWarning: NoticeUi?,
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

    /** NFR-023 (review 6): why the screen could not be shown, with the next action, while its stored data cannot be read. */
    private val mutableNotOpened = MutableStateFlow<String?>(null)
    val notOpened: StateFlow<String?> = mutableNotOpened

    private var opened = false

    /** UC-001 A11, P9-16: the result of the last tap, one per Data Provider. */
    private var fetchResults: List<FetchResultUi> = emptyList()

    /** P9-16: the taps of this screen, so that every result is shown as new. */
    private var taps = 0

    /** UC-001 A16: the Data Providers that warned and wait for the Operator's confirmation, first one shown. */
    private var pendingWarnings: List<FetchWarningUi> = emptyList()

    /** P9-16, ADR-007 §1: the operation that runs now. */
    private var running: RunningOperation? = services.running.value

    /** FR-040: Read the Budget App again, at the line of the Budget App. */
    private val reading = ReadAgain(services)

    /** The line of the Budget App as the stored status gives it, and the Budget App's name. */
    private var budgetAppStatus = ""
    private var budgetAppName = ""

    init {
        viewModelScope.launch {
            services.running.collect { now ->
                running = now
                mutableState.value = mutableState.value?.let {
                    it.copy(fetchButton = fetchButton(it.canFetch), budgetAppLine = budgetAppLine())
                }
            }
        }
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored status it changed is shown again.
            services.ended.drop(1).collect { if (!reading.pending && mutableState.value != null) reload() }
        }
    }

    /** Shows the stored status again; a storage that cannot be read leaves the screen as it is, never a crash (NFR-023). */
    private suspend fun reload() {
        try {
            show(services.status.overview())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Unit
        }
    }

    private fun budgetAppLine() = reading.line(budgetAppStatus, running, budgetAppName)

    /**
     * FR-040, UC-003 trigger: the Operator reads the Budget App again; no Bank is contacted. Its line shows at once that
     * it reads, or what it waits for, then its answer; the stored status is shown again afterwards (NFR-023, UX-12).
     */
    fun readAgain() {
        val current = mutableState.value ?: return
        if (current.firstRun != null || !current.budgetAppLine.button.enabled || !reading.begin()) return
        mutableState.value = current.copy(budgetAppLine = budgetAppLine())
        viewModelScope.launch {
            reading.run(budgetAppName)
            try {
                show(services.status.overview())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // NFR-023: the stored status could not be read again; said at the line, never a crash.
                reading.statusNotRead()
                mutableState.value = mutableState.value?.copy(budgetAppLine = budgetAppLine())
            }
        }
    }

    /** UC-008 steps 1-3: the first load of a session records its start; later loads only read the stored status again. */
    fun load() {
        viewModelScope.launch {
            try {
                show(if (opened) services.status.overview() else services.status.open().also { opened = true })
                mutableNotOpened.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // NFR-023 (review 6): an Overview never shown says why, with the next action; one shown keeps what it shows.
                if (mutableState.value == null) mutableNotOpened.value = NOT_OPENED
            }
        }
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
        val now = services.clock.instant()
        budgetAppName = overview.budgetAppName
        // A1 step 2 of UC-003: the line names the Budgets whose read failed since their last successful read.
        budgetAppStatus = Texts.budgetAppRead(
            overview.budgetAppRead,
            overview.budgets.count { it.statusesAsOf != StatusesAsOf.NothingToCompare },
            overview.budgetAppName,
            formats,
            now,
        )
        mutableState.value = OverviewUi(
            heading = "Overview · ${formats.dateTime(now)} · device time",
            budgetAppLine = budgetAppLine(),
            unreadableNotices = overview.unreadable.map { Texts.unreadableNotice(it, firstUploadMatchingShown = firstUploadMatching != null) },
            // UC-008 BR-009: Every opening shows that Uploads wait for First Upload Matching, as information, never as an error.
            firstUploadMatching = firstUploadMatching?.let { Texts.firstUploadMatching(it, overview.budgetAppName) },
            backupWarning = overview.backupWarning?.let {
                NoticeUi(
                    "Backed-up data nears Android's backup limit",
                    "${formats.megabytes(it.backedUpBytes)} of ${formats.megabytes(it.limitBytes)}, Android's limit · above it " +
                        "Android backs up nothing · ${Texts.NO_BACKUP_ACTION}",
                )
            },
            expiringConsents = overview.expiringBankConsents.map {
                "Bank Consent of ${it.bankName} through ${it.dataProviderName} expires ${formats.date(it.expiresAt)} · Renew"
            },
            budgets = overview.budgets.map { budget ->
                BudgetLine(
                    budgetId = budget.budgetId,
                    name = budget.name,
                    currency = budget.currency.code,
                    // A1 of UC-003 (S-T-08): a Bank Transaction not compared yet is counted neither as Eligible nor as waiting.
                    counts = Texts.counts(budget.notYetUploaded, budget.notComparedYet),
                    blocked = budget.blocked.filterNot { it.reason == BlockedReason.UploadsWaitForFirstUploadMatching }
                        .map { blocked -> "Blocked: ${blocked.subject}, ${Texts.blockedReason(blocked.reason, blocked.dataProviderName)}" }
                        .distinct(),
                    // UC-008 BR-006: The Blocked Reason "Uploads wait for First Upload Matching", in the tone of BR-009.
                    firstUploadMatching = firstUploadMatching?.takeIf {
                        budget.blocked.any { it.reason == BlockedReason.UploadsWaitForFirstUploadMatching }
                    }?.let { FirstUploadMatchingChip(Texts.UPLOADS_WAIT_FOR_FIRST_UPLOAD_MATCHING, it.tone) },
                    statuses = Texts.statusesAsOf(budget.statusesAsOf, overview.budgetAppName, formats, now),
                    readAgainNote = Texts.readAgainNote(budget, overview.budgetAppName),
                )
            },
            noBankAccountAdded = overview.noBankAccountAdded,
            message = null,
            canFetch = true,
            fetchButton = fetchButton(canFetch = true),
            fetchResults = fetchResults,
            fetchWarning = pendingWarnings.firstOrNull(),
            fetchNote = Texts.fetchNote(overview.budgetAppName),
            // Step 2 (P9-13): the renew action needs Grant Bank Consent (UC-007), which this build does not have.
            expiringConsentNote = if (overview.expiringBankConsents.isEmpty()) {
                null
            } else {
                "${Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD}, so it cannot be renewed here."
            },
        )
    }

    // UC-008 BR-011: While no Bank Account is added, the Overview shows nothing but the notices of stored data that could not be read and below them the card that leads to Settings and names the next thing, not even First Upload Matching.
    private fun showFirstRun(overview: Overview, card: FirstRunCard) {
        val (next, then) = card.next?.let(Texts::next) ?: ("Next: open Settings" to null)
        mutableState.value = OverviewUi(
            heading = "Overview · ${services.formats.dateTime(services.clock.instant())} · device time",
            budgetAppLine = BudgetAppLineUi("", FetchButtonUi(Texts.readAgain(overview.budgetAppName), enabled = false, running = false)),
            unreadableNotices = overview.unreadable.map { Texts.unreadableNotice(it, firstUploadMatchingShown = false) },
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
            when (val start = stoppedOr { services.fetch.fetchAll() }) {
                null -> fetchResults = listOf(stopped(tap))
                is FetchStart.Unavailable ->
                    fetchResults = listOf(FetchResultUi(Texts.refusal(start.reason), null, FetchResultKind.NotFetched, tap))
                is FetchStart.Ran -> {
                    fetchResults = start.reports.filterNot { it is FetchReport.WarningToConfirm }.map { onOverview(it, tap) }
                    pendingWarnings = start.reports.filterIsInstance<FetchReport.WarningToConfirm>()
                        .map { Texts.fetchWarning(it, services.formats) }
                }
            }
            reload()
            mutableState.value = mutableState.value?.copy(fetchResults = fetchResults, fetchWarning = pendingWarnings.firstOrNull())
        }
    }

    /** NFR-023: the outcome of [fetch], or null when it stopped because the stored data could not be used. */
    private suspend fun stoppedOr(fetch: suspend () -> FetchStart): FetchStart? =
        try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }

    /** NFR-023: a Fetch that stopped may have stored part of what it read, so the answer says only that it stopped. */
    private fun stopped(tap: Int) = FetchResultUi(
        "The Fetch stopped: Slussen could not use the data stored on this device",
        "Fetch again; Recent Fetches shows what was stored.",
        FetchResultKind.Failed,
        tap,
    )

    /** UC-001 A11 (P9-16): on the Overview each result names its Data Provider. */
    private fun onOverview(report: FetchReport, tap: Int): FetchResultUi =
        Texts.fetchResult(report, services.formats, tap).let { it.copy(text = "${report.dataProviderName} · ${it.text}") }

    /** UC-001 A16: the Operator confirms the Fetch of the Data Provider that warned. */
    fun confirmFetch() {
        val warning = pendingWarnings.firstOrNull() ?: return
        pendingWarnings = pendingWarnings.drop(1)
        val tap = ++taps
        viewModelScope.launch {
            when (val start = stoppedOr { services.fetch.fetch(warning.service, confirmedDespiteWarning = true) }) {
                null -> fetchResults = fetchResults + stopped(tap)
                is FetchStart.Unavailable ->
                    fetchResults = fetchResults + FetchResultUi(Texts.refusal(start.reason), null, FetchResultKind.NotFetched, tap)
                is FetchStart.Ran -> fetchResults = fetchResults + start.reports.map { onOverview(it, tap) }
            }
            reload()
            mutableState.value = mutableState.value?.copy(fetchResults = fetchResults)
        }
    }

    /** UC-001 A16: the Operator cancels; that Data Provider is not fetched. */
    fun cancelFetch() {
        pendingWarnings = pendingWarnings.drop(1)
        mutableState.value = mutableState.value?.copy(fetchWarning = pendingWarnings.firstOrNull())
    }

    /**
     * UC-008 A9: the Operator chooses "Start First Upload Matching". Its confirmation comes with Uploads (A-10), so First
     * Upload Matching stays open and the screen says so, with no action needed.
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
        const val NOT_OPENED =
            "The Overview could not be shown: the status stored on this device could not be read, and nothing was changed. Tap Try again."
    }
}
