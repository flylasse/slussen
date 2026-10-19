package slussen.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.status.BlockedReason
import slussen.core.status.RecoveryConfirmation

/** One Budget on the Overview: counts and blocked states only (UC-008 step 1, BR-007). */
data class BudgetLine(
    val budgetId: Long,
    val name: String,
    val currency: String,
    val counts: String,
    val blocked: List<String>,
)

/** What the Overview shows when Slussen opens (UC-008 steps 1-3, A1, A7, A8). */
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
)

/** The Overview tab (UC-008), from stored data only. */
class OverviewViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<OverviewUi?>(null)
    val state: StateFlow<OverviewUi?> = mutableState

    private var opened = false

    /** UC-008 steps 1-3: the first load of a session records its start; later loads only read the stored status again. */
    fun load() {
        viewModelScope.launch {
            val overview = if (opened) services.status.overview() else services.status.open().also { opened = true }
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
            )
        }
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
