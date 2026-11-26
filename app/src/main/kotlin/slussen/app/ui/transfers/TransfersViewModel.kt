package slussen.app.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.bankaccount.DetailLine
import slussen.core.status.BudgetTransfersView
import slussen.core.status.TransferItem

/** One Transfer of the list (`transfers.html#list`): its kinds, its two Budget Accounts, amount, sides and state. */
data class TransferCardUi(
    /** The Bank Transaction that selects it: its first Transfer Leg. */
    val key: Long,
    val chips: List<String>,
    val guessed: Boolean,
    val budgetAccounts: String,
    val amount: String,
    val sides: List<String>,
    val state: String,
)

/** A Bank Transaction whose other side did not arrive in time (UC-005 A5), listed apart from the Transfers. */
data class NoLongerUi(val title: String, val amount: String, val reason: String)

/** A selected Transfer (`#selected`, `#lonely`, `#first-card-payment`, `#doubtful-leg`), read-only, with Close. */
data class TransferSheetUi(val title: String, val subtitle: String, val details: List<DetailLine>, val closing: String)

/** The Transfers of one Budget (UC-005 step 1; `docs/mockups/transfers.html`). */
data class TransfersUi(
    val title: String,
    /** UC-003 step 7, A1: when the statuses were given. */
    val statuses: String?,
    /** UC-003 A1 step 2: the next action when they are not current. */
    val readAgainNote: String?,
    /** UC-003 A1 step 2, S-T-08: Bank Transactions not compared yet, among which no Transfer is detected yet. */
    val notComparedYet: String?,
    val counts: String,
    /** UX-27: how these counts relate to the Budget's counts of Bank Transactions. */
    val countsNote: String?,
    val note: String,
    /** `#no-transfers`; null while it lists a Transfer. */
    val empty: String?,
    val transfers: List<TransferCardUi>,
    val noLongerTransfers: List<NoLongerUi>,
    /** A selected Transfer the stored data no longer holds; null otherwise. */
    val notListed: String? = null,
    val sheet: TransferSheetUi? = null,
)

/** What the screen shows: the Transfers, and a failure to read them (NFR-023). */
data class TransfersState(val ui: TransfersUi?, val failure: String?)

/**
 * The Transfers of one Budget (UC-005 step 1, A4, A5, A8), read-only and from stored data only (UC-008 BR-001): the list,
 * and one selected Transfer in place of the list. It reads the stored data again whenever an operation ends, whichever
 * screen started it (NFR-023), and catches whatever the read throws.
 */
class TransfersViewModel(private val services: UiServices, private val budgetId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow(TransfersState(ui = null, failure = null))
    val state: StateFlow<TransfersState> = mutableState

    private var view: BudgetTransfersView? = null

    /** The Bank Transaction whose Transfer is selected, or null for the list. */
    private var selected: Long? = null
    private var notListed = false

    init {
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored Transfers it changed are shown again.
            services.ended.drop(1).collect { if (view != null) reload() }
        }
    }

    /**
     * Opens the Transfers: the Transfer of the Bank Transaction [selectLeg] when the Operator came from a Transfer Leg on
     * the Bank Account screen, else the list; never a Transfer left open the last time they were shown.
     */
    fun load(selectLeg: Long? = null) {
        selected = selectLeg
        notListed = false
        publish()
        viewModelScope.launch { reload() }
    }

    /** Opens the Transfer whose first Transfer Leg is [key]. */
    fun open(key: Long) {
        selected = key
        notListed = false
        publish()
    }

    /** System back: closes an open Transfer and answers true, or answers false when none is open. */
    fun back(): Boolean {
        if (selected == null) return false
        close()
        return true
    }

    fun close() {
        selected = null
        notListed = false
        publish()
    }

    private suspend fun reload() {
        try {
            val read = services.transfers.of(budgetId)
            view = read
            // A selected Transfer that the stored data no longer holds: the list, with a line that says so.
            if (selected?.let { itemOf(read, it) } == null && selected != null) {
                selected = null
                notListed = true
            }
            mutableState.value = TransfersState(ui(), failure = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: the stored data could not be read; said on the screen, which keeps what it showed, never a crash.
            mutableState.value = mutableState.value.copy(failure = TransfersTexts.NOT_READ)
        }
    }

    private fun publish() {
        if (view == null) return
        mutableState.value = mutableState.value.copy(ui = ui())
    }

    private fun itemOf(view: BudgetTransfersView, leg: Long): TransferItem? =
        view.transfers.firstOrNull { item -> item.legs.any { it.storedBankTransaction == leg } }

    private fun ui(): TransfersUi? {
        val view = view ?: return null
        val formats = services.formats
        val now = services.clock.instant()
        val today = formats.today(now)
        val open = selected?.let { itemOf(view, it) }
        return TransfersUi(
            title = "${view.budgetName} · Transfers",
            statuses = TransfersTexts.statuses(view, formats, now),
            readAgainNote = Texts.readAgainNote(view.summary, view.budgetAppName),
            notComparedYet = Texts.notComparedYet(view.summary.notComparedYet, view.budgetAppName),
            counts = TransfersTexts.counts(view),
            countsNote = TransfersTexts.countsNote(view),
            note = TransfersTexts.READ_ONLY,
            empty = TransfersTexts.empty(view, formats),
            transfers = view.transfers.map { item ->
                TransferCardUi(
                    key = item.legs.first().storedBankTransaction,
                    chips = TransfersTexts.chips(item),
                    guessed = item.guessed,
                    budgetAccounts = TransfersTexts.budgetAccounts(item),
                    amount = formats.amount(item.amount),
                    sides = TransfersTexts.sides(item, formats, today),
                    state = TransfersTexts.state(item),
                )
            },
            noLongerTransfers = view.noLongerTransfers.map { item ->
                NoLongerUi(TransfersTexts.noLongerTitle(item), formats.difference(item.leg.amount), TransfersTexts.noLongerReason(item, formats, today))
            },
            notListed = if (notListed) TransfersTexts.NO_LONGER_LISTED else null,
            sheet = open?.let { item ->
                TransferSheetUi(
                    title = TransfersTexts.sheetTitle(item, view, formats),
                    subtitle = TransfersTexts.budgetAccounts(item),
                    details = TransfersTexts.details(item, view, formats, today),
                    closing = TransfersTexts.CLOSING,
                )
            },
        )
    }
}
