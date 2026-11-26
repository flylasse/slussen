package slussen.app.ui.bankaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.budget.BlockedLine
import slussen.app.ui.budget.remedyAction
import slussen.core.port.ServiceKey
import slussen.core.status.BankAccountTransactionsView
import slussen.core.status.TransactionKey
import java.time.Instant

/** One transaction of the list: date, payee, the reason for its status, amount and status (`bank-account.html#checking-this-build`). */
data class TransactionLineUi(
    val key: TransactionKey,
    val date: String,
    val payee: String,
    val reason: String,
    val amount: String,
    val status: String,
    val eligible: Boolean,
)

/** One heading of the list with its lines (UX-17, UX-D2). */
data class WeekUi(val heading: String, val lines: List<TransactionLineUi>)

/** One line of a sheet: what it names, and its text. */
data class DetailLine(val name: String, val text: String)

/** The read-only details of one transaction (`#tx-match-this-build`, `#tx-possible-import-this-build`, `#tx-unreadable-this-build`). */
data class TransactionSheetUi(val title: String, val subtitle: String, val details: List<DetailLine>, val closing: String)

/** One Transaction Status in "What the statuses mean", with what it means and what to do now. */
data class StatusMeaningUi(val status: String, val meaning: String, val now: String)

/** "What the statuses mean" (`#statuses-this-build`). */
data class StatusesSheetUi(val title: String, val subtitle: String, val intro: String, val statuses: List<StatusMeaningUi>, val closing: String)

/** One Bank Account's screen (UC-008 steps 5-8; `docs/mockups/bank-account.html`). */
data class BankAccountUi(
    val title: String,
    /** Its type and currency. */
    val kind: String,
    /** Its Budget, Budget Account, Bank and Source; or not mapped, or a Retired Account. */
    val placement: String,
    val placementNote: String?,
    val health: String?,
    val freshness: String?,
    val balance: String?,
    /** UC-008 A2 (UX-24): the remedy of an outdated Account Balance. */
    val balanceRemedy: String?,
    /** UC-001 A21: the balance its last Fetch reported in another currency. */
    val balanceNotice: String?,
    val blocked: List<BlockedLine>,
    /** UC-003 step 7, A1: when its statuses were given, with its counts. */
    val statuses: String?,
    /** UC-003 A1 step 2, S-T-08: its Bank Transactions not compared yet. */
    val notComparedYet: String?,
    /** UC-003 A1 step 2: the next action when its statuses are not current. */
    val readAgainNote: String?,
    val startDate: String?,
    /** UC-005 BR-006: the Funded By or Transfer Partner, read-only. */
    val otherSide: String,
    /** The one line about the parts not built yet, for which no action is needed. */
    val note: String,
    val covers: String?,
    val weeks: List<WeekUi>,
    /** `#fetched-empty`: no transaction at all. */
    val nothingStored: String?,
    val sheet: TransactionSheetUi? = null,
    val statusesSheet: StatusesSheetUi? = null,
)

/** What the screen shows: the Bank Account, and a failure to read it again (NFR-023). */
data class BankAccountState(val ui: BankAccountUi?, val failure: String?)

/**
 * One Bank Account's screen (UC-008 steps 5-8), from stored data only (BR-001): its Bank Transactions and Unreadable
 * Transactions newest first with their Transaction Statuses and reasons, and the read-only details of each. It reads the
 * stored data again whenever an operation ends, whichever screen started it (NFR-023), and catches whatever the read
 * throws.
 */
class BankAccountViewModel(private val services: UiServices, private val bankAccountId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(BankAccountState(ui = null, failure = null))
    val state: StateFlow<BankAccountState> = mutableState

    private var view: BankAccountTransactionsView? = null
    private var openLine: TransactionKey? = null
    private var statusesOpen = false

    init {
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored statuses it changed are shown again.
            services.ended.drop(1).collect { if (view != null) reload() }
        }
    }

    fun load() {
        viewModelScope.launch { reload() }
    }

    /** Opens the read-only details of the line [key]. */
    fun open(key: TransactionKey) {
        openLine = key
        statusesOpen = false
        publish()
    }

    /** Opens "What the statuses mean". */
    fun openStatuses() {
        openLine = null
        statusesOpen = true
        publish()
    }

    fun close() {
        openLine = null
        statusesOpen = false
        publish()
    }

    private suspend fun reload() {
        try {
            view = services.bankAccountTransactions.of(bankAccountId)
            mutableState.value = BankAccountState(ui(), failure = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: the stored data could not be read; said on the screen, which keeps what it showed, never a crash.
            mutableState.value = mutableState.value.copy(failure = BankAccountTexts.NOT_READ)
        }
    }

    private fun publish() {
        if (view == null) return
        mutableState.value = mutableState.value.copy(ui = ui())
    }

    private fun ui(): BankAccountUi? {
        val view = view ?: return null
        val formats = services.formats
        val now: Instant = services.clock.instant()
        val today = formats.today(now)
        val budgetApp = view.budgetAppName
        val account = view.status
        val summary = view.summary
        val statusesAsOf = summary?.let { Texts.statusesAsOf(it.statusesAsOf, budgetApp, formats, now) }
        val lines = view.weeks.flatMap { it.lines }
        return BankAccountUi(
            title = view.name,
            kind = "${formats.type(view.type)} · ${view.currency.code}",
            placement = BankAccountTexts.placement(view),
            placementNote = BankAccountTexts.placementNote(view.placement),
            health = account?.let { Texts.health(it.health, formats) },
            freshness = account?.let { status -> status.freshness?.let { "Freshness: data as of ${formats.dateTime(it)}" } ?: "Freshness: no data yet" },
            balance = account?.let { Texts.balance(it.balance, formats, now) },
            balanceRemedy = account?.let { Texts.balanceRemedy(it.balance) },
            balanceNotice = account?.balanceInOtherCurrency?.let { Texts.balanceInOtherCurrencyNotice(it, formats) },
            blocked = account?.blocked.orEmpty().map { blocked ->
                BlockedLine(
                    Texts.blocked(blocked, formats),
                    // UC-008 BR-006: Each Blocked Reason is shown with the action that removes it, here through the Bank Account's Data Provider.
                    remedyAction(blocked, account?.dataProviderId?.let(ServiceKey::dataProvider)),
                    Texts.advice(blocked),
                )
            },
            statuses = account?.let { status ->
                listOfNotNull(statusesAsOf, Texts.counts(status.notYetUploaded, status.notComparedYet)).joinToString(" · ")
            },
            notComparedYet = account?.let { Texts.notComparedYet(it.notComparedYet, budgetApp) },
            readAgainNote = summary?.let { Texts.readAgainNote(it.copy(notComparedYet = account?.notComparedYet ?: 0), budgetApp) },
            startDate = BankAccountTexts.startDate(view.startDate, formats),
            otherSide = BankAccountTexts.otherSide(view.otherSide),
            note = Texts.UPLOADS_NOT_IN_THIS_BUILD,
            covers = BankAccountTexts.covers(view.covers, formats),
            weeks = view.weeks.map { week ->
                WeekUi(
                    BankAccountTexts.heading(week, formats, today),
                    week.lines.map { line ->
                        TransactionLineUi(
                            key = line.key,
                            date = BankAccountTexts.date(line, formats, today),
                            payee = BankAccountTexts.payee(line),
                            reason = BankAccountTexts.reason(line, view, formats, today),
                            amount = BankAccountTexts.amount(line.amount, formats),
                            status = BankAccountTexts.status(line),
                            eligible = line.eligible,
                        )
                    },
                )
            },
            nothingStored = view.nothingStored?.let { BankAccountTexts.nothingStored(it, formats) },
            sheet = openLine?.let { key -> lines.firstOrNull { it.key == key } }?.let { BankAccountTexts.sheet(it, view, formats, today) },
            statusesSheet = if (statusesOpen) BankAccountTexts.statusesSheet(view, statusesAsOf, formats, today) else null,
        )
    }
}
