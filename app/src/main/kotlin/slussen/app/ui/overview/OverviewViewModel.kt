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
import slussen.core.status.RecoveryConfirmation

/** One Budget on the Overview: counts and blocked states only (UC-008 step 1, BR-007). */
data class BudgetLine(
    val budgetId: Long,
    val name: String,
    val currency: String,
    val counts: String,
    val blocked: List<String>,
)

/** UC-008 A10, BR-011 (`overview.html#new-installation`, `#first-run-next`): the one card that leads to Settings. */
data class FirstRunUi(val title: String, val next: String, val then: String?)

/** What the Overview shows when Slussen opens (UC-008 steps 1-3, A1, A7, A8, A10). */
data class OverviewUi(
    val heading: String,
    val budgetAppRead: String,
    val unreadableNotices: List<String>,
    val recoveryCheck: String?,
    val recoveryCause: String?,
    val backupWarning: String?,
    val expiringConsents: List<String>,
    val budgets: List<BudgetLine>,
    val noBankAccountAdded: Boolean,
    val message: String?,
    /** UC-001 A11: the Fetch of all Data Providers is offered. */
    val canFetch: Boolean,
    /** UC-001 A11: one line per Data Provider of the last Fetch started here. */
    val fetchMessages: List<String>,
    /** UC-001 A16: the warning of a Data Provider the Operator confirms before it is fetched, or null. */
    val fetchWarning: FetchWarningUi?,
    /** A10, BR-011: while no Bank Account is added, the only thing shown; null from the first added Bank Account on. */
    val firstRun: FirstRunUi? = null,
)

/** The Overview tab (UC-008), from stored data only. */
class OverviewViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<OverviewUi?>(null)
    val state: StateFlow<OverviewUi?> = mutableState

    private var opened = false

    /** UC-001 A11: one line per Data Provider of the last Fetch started here. */
    private var fetchMessages: List<String> = emptyList()

    /** UC-001 A16: the Data Providers that warned and wait for the Operator's confirmation, first one shown. */
    private var pendingWarnings: List<FetchWarningUi> = emptyList()

    /** UC-008 steps 1-3: the first load of a session records its start; later loads only read the stored status again. */
    fun load() {
        viewModelScope.launch { show(if (opened) services.status.overview() else services.status.open().also { opened = true }) }
    }

    private fun show(overview: Overview) {
        val firstRun = overview.firstRun
        if (firstRun != null) {
            showFirstRun(overview, firstRun)
            return
        }
        val formats = services.formats
        mutableState.value = OverviewUi(
            heading = "Overview · ${formats.dateTime(services.clock.instant())} · device time",
            budgetAppRead = overview.budgets.mapNotNull { it.lastReadAt }.maxOrNull()
                ?.let { "${overview.budgetAppName} · read ${formats.time(it)}" }
                ?: "${overview.budgetAppName} · not read yet",
            unreadableNotices = overview.unreadable.map { notice ->
                "Stored data could not be read: ${Texts.part(notice.part)}" +
                    if (notice.startedFromLastGoodCopy) " · started from its last good copy" else " · started empty"
            },
            recoveryCheck = overview.recoveryCheck?.let { "Uploads blocked: Recovery Check open" },
            recoveryCause = overview.recoveryCheck?.let { Texts.cause(it.cause) },
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
                    blocked = budget.blocked.map { blocked ->
                        if (blocked.reason == BlockedReason.RecoveryCheckOpen) {
                            "Blocked: ${Texts.blockedReason(blocked.reason)}"
                        } else {
                            "Blocked: ${blocked.subject}, ${Texts.blockedReason(blocked.reason)}"
                        }
                    }.distinct(),
                )
            },
            noBankAccountAdded = overview.noBankAccountAdded,
            message = null,
            canFetch = true,
            fetchMessages = fetchMessages,
            fetchWarning = pendingWarnings.firstOrNull(),
        )
    }

    // UC-008 BR-011: While no Bank Account is added, the Overview shows nothing but the card that leads to Settings and names the next thing, not even a Recovery Check.
    private fun showFirstRun(overview: Overview, card: FirstRunCard) {
        val (next, then) = card.next?.let(Texts::next) ?: ("Next: open Settings" to null)
        mutableState.value = OverviewUi(
            heading = "Overview · ${services.formats.dateTime(services.clock.instant())} · device time",
            budgetAppRead = "",
            unreadableNotices = emptyList(),
            recoveryCheck = null,
            recoveryCause = null,
            backupWarning = null,
            expiringConsents = emptyList(),
            budgets = emptyList(),
            noBankAccountAdded = overview.noBankAccountAdded,
            message = null,
            canFetch = false,
            fetchMessages = emptyList(),
            fetchWarning = null,
            firstRun = FirstRunUi("Set up Slussen in Settings", next, then),
        )
    }

    /** UC-001 A11: the Operator starts a Fetch of all Data Providers; the stored status is shown again afterwards. */
    fun fetchAll() {
        viewModelScope.launch {
            when (val start = services.fetch.fetchAll()) {
                is FetchStart.Unavailable -> mutableState.value = mutableState.value?.copy(message = Texts.refusal(start.reason))
                is FetchStart.Ran -> {
                    fetchMessages = start.reports.filterNot { it is FetchReport.WarningToConfirm }
                        .map { Texts.fetchReport(it, services.formats) }
                    pendingWarnings = start.reports.filterIsInstance<FetchReport.WarningToConfirm>()
                        .map { Texts.fetchWarning(it, services.formats) }
                    show(services.status.overview())
                }
            }
        }
    }

    /** UC-001 A16: the Operator confirms the Fetch of the Data Provider that warned. */
    fun confirmFetch() {
        val warning = pendingWarnings.firstOrNull() ?: return
        pendingWarnings = pendingWarnings.drop(1)
        viewModelScope.launch {
            when (val start = services.fetch.fetch(warning.service, confirmedDespiteWarning = true)) {
                is FetchStart.Unavailable -> fetchMessages = fetchMessages + Texts.refusal(start.reason)
                is FetchStart.Ran -> fetchMessages = fetchMessages + start.reports.map { Texts.fetchReport(it, services.formats) }
            }
            show(services.status.overview())
        }
    }

    /** UC-001 A16: the Operator cancels; that Data Provider is not fetched. */
    fun cancelFetch() {
        pendingWarnings = pendingWarnings.drop(1)
        mutableState.value = mutableState.value?.copy(fetchWarning = pendingWarnings.firstOrNull())
    }

    /** UC-008 A9: the confirmation needs Compare With the Budget App (UC-003), which no slice builds yet (O-P2-09). */
    fun confirmRecoveryCheck() {
        viewModelScope.launch {
            val message = when (services.status.confirmRecoveryCheck()) {
                RecoveryConfirmation.NoneOpen -> null
                RecoveryConfirmation.ComparisonNotAvailable ->
                    "The Recovery Check stays open: confirming it needs a Comparison with the Budget App that read every " +
                        "Budget, which this build cannot run yet. Uploads stay blocked."
            }
            mutableState.value = mutableState.value?.copy(message = message)
        }
    }
}
