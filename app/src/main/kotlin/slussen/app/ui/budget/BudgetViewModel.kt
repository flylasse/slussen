package slussen.app.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Formats
import slussen.app.ui.ReadAgain
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.components.BudgetAppLineUi
import slussen.app.ui.components.FetchButtonUi
import slussen.core.operation.RunningOperation
import slussen.app.ui.components.FirstUploadMatchingUi
import slussen.core.port.ServiceKey
import slussen.core.status.Blocked
import slussen.core.status.NotYetUploaded
import slussen.core.status.Remedy
import java.time.Instant

/**
 * The action that removes a Blocked Reason (UC-008 A6). Only [available] actions start their use case; an action whose
 * use case no slice has built yet is shown with [note] instead.
 */
data class RemedyAction(val title: String, val available: Boolean, val service: ServiceKey?, val note: String?)

/** One Blocked Reason with its action, and the advice it carries (UC-001 A19, A20). */
data class BlockedLine(val text: String, val action: RemedyAction?, val advice: String? = null)

/** One Source of the Budget (UC-008 steps 5-6, BR-003). */
data class SourceCard(
    val name: String,
    val kind: String,
    val health: String,
    val freshness: String,
    /** Null for a Source of the balance only, which brings no Bank Transaction to upload. */
    val counts: String?,
    val blocked: List<BlockedLine>,
)

/** One Bank Account of the Budget (UC-008 steps 5-8, A2-A4). */
data class BankAccountCard(
    val name: String,
    val type: String,
    val health: String,
    val freshness: String,
    val counts: String,
    val balance: String,
    val blocked: List<BlockedLine>,
    /** UC-001 A21: the balance its last Fetch reported in another currency, or null. */
    val balanceNotice: String? = null,
    /** UC-008 A2 (UX-24): the remedy of an outdated Account Balance, a Fetch of its Data Provider; null otherwise. */
    val balanceRemedy: String? = null,
)

/** One Budget's workspace (UC-008 steps 4-8, BR-007). */
data class BudgetUi(
    val title: String,
    val subtitle: String,
    val counts: String,
    val sources: List<SourceCard>,
    val bankAccounts: List<BankAccountCard>,
    /** `budget.html#household-this-build`: the one line about the parts not built yet, for which no action is needed. */
    val note: String? = null,
    /** UC-008 A8, BR-006, BR-009 (`budget.html#recovery-blocked`): Uploads wait for First Upload Matching; null while none is open. */
    val firstUploadMatching: FirstUploadMatchingUi? = null,
    /** FR-040, UC-003 (`budget.html`, the states of Read again): the line of the Budget App with Read again and its answer. */
    val budgetAppLine: BudgetAppLineUi = BudgetAppLineUi("", FetchButtonUi("", enabled = false, running = false)),
    /** UC-003 step 7, A1 step 2: when this Budget's statuses were given, and a read that failed since; null with nothing to compare. */
    val statuses: String? = null,
    /** UC-003 A1 step 2, S-T-08 (`budget.html#first-read-failed`): its Bank Transactions not compared yet; null when none is. */
    val notComparedYet: String? = null,
    /** UC-003 A1 step 2: the remedy of statuses that are not current, a read of the Budget App; null while they are. */
    val readAgainNote: String? = null,
)

/** A Budget tab (UC-008 steps 4-8), from stored data only. */
class BudgetViewModel(private val services: UiServices, private val budgetId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow<BudgetUi?>(null)
    val state: StateFlow<BudgetUi?> = mutableState

    /** ADR-007 §1: the operation that runs now. */
    private var running: RunningOperation? = services.running.value

    /** FR-040: Read the Budget App again, at the line of the Budget App of this tab. */
    private val reading = ReadAgain(services)

    /** The line of the Budget App as the stored status gives it, and the Budget App's name. */
    private var budgetAppStatus = ""
    private var budgetAppName = ""

    init {
        viewModelScope.launch {
            services.running.collect { now ->
                running = now
                mutableState.value = mutableState.value?.copy(budgetAppLine = budgetAppLine())
            }
        }
    }

    private fun budgetAppLine() = reading.line(budgetAppStatus, running, budgetAppName)

    /**
     * FR-040, UC-003 trigger: the Operator reads the Budget App again from this tab; every Budget is read and no Bank is
     * contacted. Its line shows at once that it reads, or what it waits for, then its answer (NFR-023, UX-12).
     */
    fun readAgain() {
        val current = mutableState.value ?: return
        if (!current.budgetAppLine.button.enabled || !reading.begin()) return
        mutableState.value = current.copy(budgetAppLine = budgetAppLine())
        viewModelScope.launch {
            reading.run(budgetAppName)
            show()
        }
    }

    fun load() {
        viewModelScope.launch { show() }
    }

    private suspend fun show() {
        run {
            val formats = services.formats
            val now = services.clock.instant()
            val workspace = services.status.workspace(budgetId)
            budgetAppName = workspace.budgetAppName
            budgetAppStatus = Texts.budgetAppReadOf(workspace.summary.statusesAsOf, workspace.budgetAppName, formats, now)
            val serviceByName = services.settingsOverview.entries().entries
                .mapNotNull { entry -> entry.service?.let { entry.name to it } }.toMap()
            fun lines(blocked: List<Blocked>, dataProvider: ServiceKey?) =
                blocked.map { BlockedLine(Texts.blocked(it, formats), action(it, serviceByName[it.subject] ?: dataProvider), Texts.advice(it)) }
            mutableState.value = BudgetUi(
                title = workspace.summary.name,
                subtitle = "Budget · ${workspace.summary.currency.code} · from stored data",
                counts = Texts.counts(workspace.summary.notYetUploaded, workspace.summary.notComparedYet),
                sources = workspace.sources.map { source ->
                    SourceCard(
                        name = source.name,
                        kind = when {
                            source.isFileFormat -> "Transaction Files"
                            source.balanceOnly -> "Data Provider · Account Balance only"
                            else -> "Data Provider"
                        },
                        health = Texts.health(source.health, formats),
                        freshness = freshness(source.freshness, formats),
                        // UC-001 BR-011: a Source of the balance only brings no Bank Transaction to upload.
                        counts = if (source.balanceOnly) null else "Not yet uploaded: " + counts(source.notYetUploaded),
                        blocked = lines(source.blocked, dataProvider = null),
                    )
                },
                bankAccounts = workspace.bankAccounts.map { account ->
                    BankAccountCard(
                        name = account.name,
                        type = formats.type(account.type),
                        health = Texts.health(account.health, formats),
                        freshness = freshness(account.freshness, formats),
                        counts = Texts.counts(account.notYetUploaded, account.notComparedYet),
                        balance = Texts.balance(account.balance, formats, now),
                        blocked = lines(account.blocked, account.dataProviderId?.let(ServiceKey::dataProvider)),
                        balanceNotice = account.balanceInOtherCurrency?.let { Texts.balanceInOtherCurrencyNotice(it, formats) },
                        balanceRemedy = Texts.balanceRemedy(account.balance),
                    )
                },
                // `budget.html#household-this-build`: the one line about Uploads and the decisions, which are not built yet.
                note = Texts.UPLOADS_NOT_IN_THIS_BUILD,
                // UC-008 BR-009: The Budget shows that its Uploads wait for First Upload Matching, as information, never as an error.
                firstUploadMatching = workspace.firstUploadMatching?.let { Texts.firstUploadMatching(it, workspace.budgetAppName) },
                budgetAppLine = budgetAppLine(),
                statuses = Texts.statusesAsOf(workspace.summary.statusesAsOf, workspace.budgetAppName, formats, now),
                notComparedYet = Texts.notComparedYet(workspace.summary.notComparedYet, workspace.budgetAppName),
                readAgainNote = if (Texts.waitsForARead(workspace.summary)) Texts.readAgainNote(workspace.budgetAppName) else null,
            )
        }
    }

    private fun counts(counts: NotYetUploaded) = Texts.counts(counts, notComparedYet = 0)

    private fun freshness(at: Instant?, formats: Formats) =
        at?.let { "Freshness: data as of ${formats.dateTime(it)}" } ?: "Freshness: no data yet"

    /**
     * UC-008 A6: the remedy of a Blocked Reason starts its use case; of those, this build has Manage Service Credentials
     * and Fetch Transactions, both on the service's page in Settings ([service]: the service the Blocked Reason names,
     * or the Data Provider of the Bank Account it names). A remedy this build does not have says so plainly (P9-13).
     */
    private fun action(blocked: Blocked, service: ServiceKey?): RemedyAction? {
        val title = Texts.remedy(blocked.remedy) ?: return null
        return when (blocked.remedy) {
            Remedy.EnterCredentials -> RemedyAction(title, available = true, service = service, note = null)
            // UC-008 BR-006: A failed Fetch, a Bank Account not delivered and the Bank's sign-in (after signing in) are removed by fetching again.
            Remedy.FetchAgain, Remedy.SignInAtDataProvider -> RemedyAction(title, available = service != null, service = service, note = null)
            Remedy.GrantBankConsent -> RemedyAction(title, available = false, service = null, note = Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD)
            Remedy.IdentifyBankAccount, Remedy.MoveAccountMapping ->
                RemedyAction(title, available = false, service = null, note = "comes with Manage Account Mappings")
            // A9 of UC-008: the confirmation of First Upload Matching comes with Uploads; no action is needed now.
            Remedy.StartFirstUploadMatching ->
                RemedyAction(title, available = false, service = null, note = "comes with Uploads in a later version; no action is needed now")
            Remedy.WaitUntilItEnds -> null
        }
    }
}
