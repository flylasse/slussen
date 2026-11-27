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
import slussen.core.status.WhyGuessed
import slussen.core.transfer.TakenOtherSide
import slussen.core.transfer.TransferChoice
import slussen.core.transfer.TransferOutcome
import slussen.core.transfer.TransferSheet

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

/** A selected Transfer (`#selected`, `#lonely`, `#first-card-payment`, `#doubtful-leg`, `#locked`), with its Transfer Decisions and Close. */
data class TransferSheetUi(
    val title: String,
    val subtitle: String,
    val details: List<DetailLine>,
    val closing: String,
    /** UC-005 steps 3-6: its Transfer Decisions, once read from stored data; null before. */
    val decision: TransferDecisionUi? = null,
    /** NFR-023: its Transfer Decisions could not be read; null otherwise. */
    val decisionNotRead: String? = null,
    /** NFR-023: false while a decision of this Transfer is on its way: it stays open until its answer is in. */
    val canLeave: Boolean = true,
)

/** The Transfer Decisions of steps 4 and A1, A2 (A3 is [TransferDecisionUi.choose]). */
enum class TransferAction { Confirm, Split, Hold }

/** One Transfer Decision with what it does. */
data class TransferActionUi(val action: TransferAction?, val title: String, val effect: String)

/** UC-005 A9 step 2: the way to the Review Decision of the Transfer Leg [storedBankTransaction] on the Bank Account screen of [bankAccountId]. */
data class ReviewLegUi(val storedBankTransaction: Long, val bankAccountId: Long, val title: String)

/** One Budget Account the Operator can choose as the other side (UC-005 step 3, BR-014). */
data class BudgetAccountOptionUi(val budgetAccountId: Long, val name: String, val detail: String?, val chosen: Boolean)

/** UC-005 A10 (`transfer-budget-account.html#refused`): why the choice was not stored, and the Transfer that holds the other side. */
data class RefusedUi(val title: String, val text: String, val showTransferLeg: Long?)

/**
 * UC-005 A3, A8 (`transfer-budget-account.html#choose`, `#choose-pair`, `#partner`): the Budget Accounts to choose from,
 * "Keep this answer?" with the answer Slussen proposes (BR-016), and the refusal of A10.
 */
data class TransferChooserUi(
    val question: String,
    val subtitle: String,
    val options: List<BudgetAccountOptionUi>,
    val justThis: String,
    /** Null until a Budget Account is chosen. */
    val keepAnswer: String?,
    val keepEffect: String,
    val keep: Boolean,
    /** Null until a Budget Account is chosen. */
    val use: String?,
    val refused: RefusedUi?,
)

/**
 * The Transfer Decisions of a selected Transfer (UC-005 steps 3-6, A1-A4, A7-A10): its decision, lock or the Transfer
 * Leg it waits for, the decisions it offers with what each does, the chooser of another Budget Account, and at the
 * control what it is doing ([pending]) and its answer ([answer]). [enabled] is false while a decision is on its way.
 */
data class TransferDecisionUi(
    val lines: List<DetailLine>,
    val actions: List<TransferActionUi>,
    val choose: TransferActionUi?,
    val reviewLeg: ReviewLegUi?,
    /** UC-005 A8 step 1 (S-B-15): the action that changes or clears a Funded By that no longer applies, in Account Mappings. */
    val changeFundedBy: String?,
    val chooser: TransferChooserUi?,
    val pending: String?,
    val answer: String?,
    val enabled: Boolean,
)

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
    /** The answer of a Transfer Decision whose Transfer is no longer listed as it was, shown above the list; null otherwise. */
    val answer: String? = null,
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

    // steps 3-6 of UC-005: the Transfer Decisions of the selected Transfer, read from stored data when it opens, with what its controls show.
    private var decisionSheet: TransferSheet? = null
    private var decisionRead = false
    private var decisionNotRead = false
    private var chooserOpen = false
    private var chosen: Long? = null
    private var keep = false
    private var taken: List<TakenOtherSide>? = null
    private var pending: String? = null
    private var answer: String? = null
    private var listAnswer: String? = null

    init {
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored Transfers it changed are shown again.
            services.ended.drop(1).collect {
                if (view != null) reload()
                // The decisions of the open Transfer too, unless this screen's own decision is on its way and reads them itself.
                selected?.let { leg -> if (pending == null) readDecision(leg) }
            }
        }
    }

    private fun forgetDecision() {
        decisionSheet = null
        decisionRead = false
        decisionNotRead = false
        chooserOpen = false
        chosen = null
        keep = false
        taken = null
        pending = null
        answer = null
    }

    /** Steps 2-3: the Transfer Decisions of the Transfer of [leg], from stored data only; a read that fails is said in the sheet (NFR-023). */
    private suspend fun readDecision(leg: Long) {
        try {
            decisionSheet = services.transferDecisions.sheet(leg)
            decisionNotRead = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            decisionSheet = null
            decisionNotRead = true
        }
        decisionRead = true
        if (selected == leg) publish()
    }

    // ---- UC-005 steps 4-6, A1-A4, A8-A10: the Transfer Decisions --------------------------------------------------------

    /** Step 4, A1, A2, A4: the Operator confirms, splits or holds back the selected Transfer. */
    fun decide(action: TransferAction) {
        val choice = when (action) {
            TransferAction.Confirm -> TransferChoice.Confirm
            TransferAction.Split -> TransferChoice.Split
            TransferAction.Hold -> TransferChoice.Hold
        }
        store(TransfersTexts.actionTitle(action), choice)
    }

    /** A3 step 1, A8: the Budget Accounts to choose from. */
    fun openChooser() {
        if (decisionSheet == null || pending != null) return
        chooserOpen = true
        chosen = null
        taken = null
        answer = null
        publish()
    }

    // UC-005 BR-016: Slussen proposes keeping the answer for its own proposal or a first Card Payment without one, and not keeping it for another Budget Account than its proposal.
    /** A3 step 1: the Operator chooses [budgetAccountId]; "Keep this answer?" takes the answer Slussen proposes for it, which the Operator can change. */
    fun selectBudgetAccount(budgetAccountId: Long) {
        val offered = decisionSheet?.offered?.firstOrNull { it.budgetAccountId == budgetAccountId } ?: return
        chosen = budgetAccountId
        keep = offered.proposesKeeping
        taken = null
        publish()
    }

    /** A3 step 2: the Operator's answer to "Keep this answer?". */
    fun setKeep(keepIt: Boolean) {
        keep = keepIt
        publish()
    }

    /** A3 steps 3-4: the chosen Budget Account is used, with the Operator's answer. */
    fun useChosen() {
        val budgetAccountId = chosen ?: return
        val name = decisionSheet?.offered?.firstOrNull { it.budgetAccountId == budgetAccountId }?.name.orEmpty()
        store("Use $name", TransferChoice.ChooseBudgetAccount(budgetAccountId, keep))
    }

    fun cancelChooser() {
        chooserOpen = false
        chosen = null
        taken = null
        publish()
    }

    /**
     * One Transfer Decision: the control shows at once that it stores, or what it waits for (NFR-023, ADR-007 §1), takes
     * no second tap, then answers in the same place; whatever the call throws is answered there as not stored.
     */
    private fun store(what: String, choice: TransferChoice) {
        val leg = selected ?: return
        val sheet = decisionSheet ?: return
        if (pending != null) return
        pending = TransfersTexts.pending(what, services.running.value)
        answer = null
        listAnswer = null
        publish()
        viewModelScope.launch {
            val outcome = try {
                services.transferDecisions.decide(leg, choice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TransferOutcome.NotStored
            }
            val stays = sheet.legs.firstOrNull { it.storedBankTransaction == sheet.staysWith }
            val chosenName = (choice as? TransferChoice.ChooseBudgetAccount)?.let { chosenChoice -> sheet.offered.firstOrNull { it.budgetAccountId == chosenChoice.budgetAccountId }?.name }
            // The side a Lonely Transfer waits for: the chosen Budget Account, or the one of the Transfer that is not its arrived side's.
            val towards = chosenName ?: sheet.legs.singleOrNull()?.let { only ->
                if (only.budgetAccountName == sheet.inflowBudgetAccountName) sheet.outflowBudgetAccountName else sheet.inflowBudgetAccountName
            }
            val before = view
            when (outcome) {
                is TransferOutcome.OtherSideTaken -> taken = outcome.taken
                is TransferOutcome.Stored -> {
                    chooserOpen = false
                    chosen = null
                    taken = null
                    // A3 step 3: the Transfer Leg that stays is the one the Transfer is shown through from now on.
                    if (choice is TransferChoice.ChooseBudgetAccount) selected = sheet.staysWith
                }
                else -> Unit
            }
            reload()
            selected?.let { readDecision(it) }
            // UC-005 BR-005: A Lonely Transfer with a Transfer Decision is joined by its other side, also one taken from a Transfer without a decision, which is dissolved; the answer says so.
            val paired = (outcome as? TransferOutcome.Stored)?.takeIf { it.lonely && it.decision != slussen.core.port.TransferDecision.Split }?.let {
                decisionSheet?.takeIf { now -> now.legs.size == 2 }?.legs?.firstOrNull { leg -> sheet.legs.none { it.storedBankTransaction == leg.storedBankTransaction } }
            }?.let { joined ->
                val dissolved = before?.transfers.orEmpty().any { item ->
                    item.legs.any { it.storedBankTransaction == joined.storedBankTransaction } && item.legs.none { leg -> sheet.legs.any { it.storedBankTransaction == leg.storedBankTransaction } }
                }
                PairedNow("${joined.bankAccountName} ${services.formats.date(joined.bookedDate)} ${services.formats.difference(joined.amount)}", dissolved)
            }
            val answered = TransfersTexts.answer(outcome, towards, stays?.bankAccountName, chosenName, paired)
            answer = answered
            if (selected == null) listAnswer = answered
            pending = null
            publish()
        }
    }

    /** The Bank Transaction whose Transfer is open, or null for the list. */
    fun selectedLeg(): Long? = selected

    /** The decision of the open Transfer was on its way when the screen was left: coming back shows that Transfer with its answer. */
    private var keepOnReturn = false

    /** The screen is left (another screen or tab is shown). */
    fun left() {
        if (pending != null) keepOnReturn = true
    }

    /**
     * Opens the Transfers: the Transfer of the Bank Transaction [selectLeg] when the Operator came from a Transfer Leg on
     * the Bank Account screen, else the list. [returning]: the Operator came back to them with back (review B6 F7), so the
     * Transfer that was open is shown again; so is one whose decision was on its way when the screen was left (F1).
     */
    fun load(selectLeg: Long? = null, returning: Boolean = false) {
        val keep = returning || keepOnReturn || pending != null
        keepOnReturn = false
        if (!keep) {
            selected = selectLeg
            notListed = false
            listAnswer = null
            forgetDecision()
        }
        publish()
        viewModelScope.launch {
            reload()
            if (!keep || pending == null) selected?.let { readDecision(it) }
        }
    }

    /** Opens the Transfer of the Bank Transaction [key] with its Transfer Decisions (step 2); not while a decision of the open one is on its way. */
    fun open(key: Long) {
        if (pending != null) return
        selected = key
        notListed = false
        listAnswer = null
        forgetDecision()
        publish()
        viewModelScope.launch { readDecision(key) }
    }

    /** System back: closes an open Transfer and answers true, or answers false when none is open. */
    fun back(): Boolean {
        if (selected == null) return false
        close()
        return true
    }

    /** Closes the open Transfer; not while a decision of it is on its way (NFR-023), which the sheet says. */
    fun close() {
        if (pending != null) return
        selected = null
        notListed = false
        listAnswer = null
        forgetDecision()
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
            note = TransfersTexts.DECISIONS_NOTE,
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
            notListed = if (notListed && listAnswer == null) TransfersTexts.NO_LONGER_LISTED else null,
            sheet = open?.let { item ->
                TransferSheetUi(
                    title = TransfersTexts.sheetTitle(item, view, formats),
                    subtitle = TransfersTexts.budgetAccounts(item),
                    details = TransfersTexts.details(item, view, formats, today),
                    closing = TransfersTexts.CLOSING,
                    decision = decisionUi(item, view),
                    decisionNotRead = TransfersTexts.DECISION_NOT_READ.takeIf { decisionNotRead },
                    canLeave = pending == null,
                )
            },
            answer = listAnswer.takeIf { open == null },
        )
    }

    /** The Transfer Decisions of the open Transfer, or null until they are read. */
    private fun decisionUi(item: TransferItem, view: BudgetTransfersView): TransferDecisionUi? {
        if (!decisionRead || decisionNotRead) return null
        val sheet = decisionSheet ?: return TransferDecisionUi(
            lines = listOf(DetailLine("No Transfer Decision now", TransfersTexts.NOT_DECIDABLE_NOW)),
            actions = emptyList(), choose = null, reviewLeg = null, changeFundedBy = null, chooser = null,
            pending = pending, answer = answer, enabled = pending == null,
        )
        val formats = services.formats
        val lines = buildList {
            if (sheet.decision != slussen.core.port.TransferDecision.Proposed) add(DetailLine("Your Transfer Decision", TransfersTexts.decisionText(sheet.decision)))
            // UC-005 BR-008: A Transfer Decision is locked once a Transfer Leg is uploaded; the sheet says so, with what can be done instead.
            if (sheet.locked) add(DetailLine("Locked", TransfersTexts.locked(services.budgetAppName)))
            // A9 step 1 of UC-005: the Transfer Leg that waits for a Review Decision, with why.
            sheet.waitsForReviewDecision?.let { add(DetailLine("Waits", TransfersTexts.waits(item, it))) }
            // UC-005 BR-005: A Lonely Transfer with a Transfer Decision is joined by a Bank Transaction of its other side when one arrives.
            if (pending != null) add(DetailLine("While it is stored", TransfersTexts.STAYS_OPEN))
            if (sheet.legs.size == 1 && sheet.outflowBudgetAccountName != null && !sheet.locked && sheet.decision != slussen.core.port.TransferDecision.Split) {
                add(DetailLine("Its other side", if (sheet.decision == slussen.core.port.TransferDecision.Proposed) TransfersTexts.LONELY_JOINS else TransfersTexts.DECIDED_LONELY_JOINS))
            }
        }
        val actions = listOfNotNull(
            TransferAction.Confirm.takeIf { sheet.canConfirm && sheet.decision != slussen.core.port.TransferDecision.Confirmed },
            TransferAction.Split.takeIf { sheet.canSplit && sheet.decision != slussen.core.port.TransferDecision.Split },
            TransferAction.Hold.takeIf { sheet.canHold && sheet.decision != slussen.core.port.TransferDecision.Held },
        ).map { TransferActionUi(it, TransfersTexts.actionTitle(it), TransfersTexts.actionEffect(it, sheet)) }
        val stays = sheet.legs.firstOrNull { it.storedBankTransaction == sheet.staysWith }
        val staysName = stays?.bankAccountName.orEmpty()
        val chosenOption = sheet.offered.firstOrNull { it.budgetAccountId == chosen }
        return TransferDecisionUi(
            lines = lines,
            actions = actions,
            choose = if (sheet.offered.isNotEmpty()) TransferActionUi(null, TransfersTexts.chooseTitle(sheet), TransfersTexts.chooseEffect(sheet, view.budgetName)) else null,
            reviewLeg = sheet.waitsForReviewDecision?.let { leg ->
                item.legs.firstOrNull { it.storedBankTransaction == leg }?.let { ReviewLegUi(leg, it.bankAccountId, "Decide on the Transfer Leg") }
            },
            changeFundedBy = "Change or clear the Funded By…".takeIf { item.whyGuessed.any { it is WhyGuessed.NoFundedBy && it.fundedByNotApplying != null } },
            chooser = if (chooserOpen) {
                TransferChooserUi(
                    question = TransfersTexts.chooserQuestion(sheet, staysName),
                    subtitle = listOfNotNull(
                        sheet.type.text, staysName, stays?.bookedDate?.let(formats::date), stays?.amount?.let(formats::difference),
                        "${view.budgetName} (${view.currency.code})",
                    ).joinToString(" · "),
                    options = sheet.offered.map { BudgetAccountOptionUi(it.budgetAccountId, it.name, "Current proposal".takeIf { _ -> it.proposed }, it.budgetAccountId == chosen) },
                    justThis = TransfersTexts.justThis(sheet.keptAs, staysName),
                    keepAnswer = chosenOption?.let { TransfersTexts.keepAnswer(sheet.keptAs, staysName, it.name) },
                    keepEffect = TransfersTexts.keepEffect(sheet.keptAs),
                    keep = keep,
                    use = chosenOption?.let { "Use ${it.name}" },
                    refused = taken?.let { list ->
                        RefusedUi(
                            title = "Not stored: ${chosenOption?.name ?: "that Budget Account"} cannot be the other side now",
                            text = TransfersTexts.refused(list, sheet.keptAs, formats),
                            showTransferLeg = list.firstOrNull { it.transfer != null }?.storedBankTransaction,
                        )
                    },
                )
            } else {
                null
            },
            pending = pending,
            answer = answer,
            enabled = pending == null,
        )
    }
}
