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
import slussen.core.port.ReviewChoice
import slussen.core.port.ServiceKey
import slussen.core.review.ResemblingImport
import slussen.core.review.ReviewOutcome
import slussen.core.review.ReviewSheet
import slussen.core.review.UnreadableOutcome
import slussen.core.review.UnreadableSheet
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
data class TransactionSheetUi(
    val title: String,
    val subtitle: String,
    val details: List<DetailLine>,
    val closing: String,
    /** UC-005 step 1: the Bank Transaction to open on the Transfers of its Budget, for a Transfer Leg; null otherwise. */
    val transferLeg: Long? = null,
    /** UC-004: the Review Decision of the line, with its choices and its answer; null while it offers none and has none. */
    val review: ReviewUi? = null,
    /** NFR-023: the Review Decision of the line could not be read; null otherwise. */
    val reviewNotRead: String? = null,
    /** NFR-023: false while a decision of this sheet is on its way: the sheet stays open until its answer is in. */
    val canLeave: Boolean = true,
)

/** One choice of the Review Decision sheet with what it does (`review-decision.html`). */
data class ReviewChoiceUi(val choice: ReviewChoice?, val title: String, val effect: String)

/** UC-004 A5, BR-003: the Re-upload switch, off by default. */
data class ReUploadUi(val on: Boolean, val text: String)

/** One resembling Import the Operator can name as the same (UC-004 step 4, BR-002). */
data class ResemblingUi(val reference: String, val text: String)

/** UC-004 steps 4-5, A3 (`#choose-resembling`, `#one-resembling`): the Budget App Transaction Already Present names. */
data class ChooserUi(val question: String, val options: List<ResemblingUi>, val confirm: String)

/**
 * The Review Decision part of a line's sheet (UC-004; `review-decision.html`): why it is doubtful, its Transfer, the current
 * decision or the lock, the choices of its Transaction Status, Re-upload and clearing, the Budget App Transaction to
 * name, and at the control what it is doing ([pending]) and its answer ([answer]). [enabled] is false while a decision
 * of this sheet is on its way.
 */
data class ReviewUi(
    val lines: List<DetailLine>,
    val choices: List<ReviewChoiceUi>,
    val reUpload: ReUploadUi?,
    val clear: ReviewChoiceUi?,
    val chooser: ChooserUi?,
    val pending: String?,
    val answer: String?,
    val enabled: Boolean,
    val closing: String,
)

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

    // For UC-004: the Review Decision of the open line, read from stored data when the line opens, with what its control shows.
    private var reviewSheet: ReviewSheet? = null
    private var unreadableSheet: UnreadableSheet? = null
    private var reviewNotRead = false
    private var chooser: List<ResemblingImport>? = null
    private var reviewPending: String? = null
    private var reviewAnswer: String? = null

    init {
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored statuses it changed are shown again.
            services.ended.drop(1).collect {
                if (view != null) reload()
                // The Review Decision of the open line too, unless this sheet's own decision is on its way and reads it itself.
                openLine?.let { key -> if (reviewPending == null) readReview(key) }
            }
        }
    }

    /**
     * Opens the Bank Account: its list, or the line [openKey] when the Operator came to decide on it (UC-005 A9), never a
     * sheet left open the last time it was shown.
     */
    fun load(openKey: TransactionKey? = null, returning: Boolean = false) {
        // NFR-023 (review B6 F1, F7): a line whose decision was on its way when the screen was left, and one the Operator
        // comes back to with back, is shown again with its answer; any other opening starts from the list.
        val keep = returning || keepOnReturn || reviewPending != null
        keepOnReturn = false
        if (!keep) {
            openLine = null
            statusesOpen = false
            forgetReview()
        }
        publish()
        viewModelScope.launch {
            reload()
            if (keep) openLine?.let { readReview(it) } else openKey?.let(::open)
        }
    }

    /** Opens the details of the line [key], with its Review Decision (UC-004 step 1); not while a decision of the open line is on its way. */
    fun open(key: TransactionKey) {
        if (reviewPending != null) return
        openLine = key
        statusesOpen = false
        forgetReview()
        publish()
        viewModelScope.launch { readReview(key) }
    }

    /** The decision of the open line was on its way when the screen was left: coming back shows that line with its answer. */
    private var keepOnReturn = false

    /** The screen is left (another screen or tab is shown). */
    fun left() {
        if (reviewPending != null) keepOnReturn = true
    }

    private fun forgetReview() {
        reviewSheet = null
        unreadableSheet = null
        reviewNotRead = false
        chooser = null
        reviewPending = null
        reviewAnswer = null
    }

    /** UC-004 step 2, A6, A7, A9: the Review Decision of the line [key], from stored data only; a read that fails is said in the sheet (NFR-023). */
    private suspend fun readReview(key: TransactionKey) {
        try {
            if (key.unreadable) unreadableSheet = services.review.unreadableSheet(key.id) else reviewSheet = services.review.sheet(key.id)
            reviewNotRead = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reviewSheet = null
            unreadableSheet = null
            reviewNotRead = true
        }
        if (openLine == key) publish()
    }

    // ---- UC-004: the choices of the Review Decision sheet ---------------------------------------------------------------

    /**
     * Steps 3-5, A1-A4, A9-A11: the Operator chooses [choice]. Already Present first shows the Budget App Transaction it
     * would name (A3) or the list to name it from (step 4); nothing is stored until the Operator names it.
     */
    fun choose(choice: ReviewChoice) {
        val key = openLine ?: return
        if (reviewPending != null) return
        if (key.unreadable) {
            if (choice == ReviewChoice.Defer) unreadable(key, "Defer") { services.review.deferUnreadable(key.id) }
            return
        }
        val resembling = reviewSheet?.resembling.orEmpty()
        if (choice == ReviewChoice.AlreadyPresent && resembling.isNotEmpty()) {
            chooser = resembling
            reviewAnswer = null
            publish()
            return
        }
        decide(key, choice.text) { services.review.decide(key.id, choice) }
    }

    /** Step 5: the Operator names the Budget App Transaction [budgetAppReference] that is the same. */
    fun chooseTransaction(budgetAppReference: String) {
        val key = openLine ?: return
        if (reviewPending != null || key.unreadable) return
        decide(key, ReviewChoice.AlreadyPresent.text) { services.review.decide(key.id, ReviewChoice.AlreadyPresent, budgetAppReference) }
    }

    /** Step 5 not taken: nothing is stored. */
    fun cancelChoosing() {
        chooser = null
        publish()
    }

    /** A5, BR-003: Re-upload switched on stores the decision; switched off clears it. */
    fun switchReUpload(on: Boolean) {
        if (on) choose(ReviewChoice.ReUpload) else clearDecision()
    }

    /** A6, BR-007: the Operator clears the decision. */
    fun clearDecision() {
        val key = openLine ?: return
        if (reviewPending != null) return
        if (key.unreadable) {
            unreadable(key, "Clear the decision") { services.review.clearUnreadable(key.id) }
        } else {
            decide(key, "Clear the decision") { services.review.clear(key.id) }
        }
    }

    /**
     * One decision: the control shows at once that it stores, or what it waits for (NFR-023, ADR-007 §1), takes no second
     * tap, then answers in the same place; whatever the call throws is answered there as not stored.
     */
    private fun decide(key: TransactionKey, what: String, call: suspend () -> ReviewOutcome) {
        reviewPending = ReviewDecisionTexts.pending(what, services.running.value)
        reviewAnswer = null
        publish()
        viewModelScope.launch {
            val outcome = try {
                call()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ReviewOutcome.NotStored
            }
            chooser = (outcome as? ReviewOutcome.ChooseTransaction)?.resembling
            reviewAnswer = ReviewDecisionTexts.answer(outcome, view?.budgetAppName.orEmpty())
            reviewPending = null
            readReview(key)
            publish()
        }
    }

    private fun unreadable(key: TransactionKey, what: String, call: suspend () -> UnreadableOutcome) {
        reviewPending = ReviewDecisionTexts.pending(what, services.running.value)
        reviewAnswer = null
        publish()
        viewModelScope.launch {
            val outcome = try {
                call()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UnreadableOutcome.NotStored
            }
            reviewAnswer = ReviewDecisionTexts.unreadableAnswer(outcome)
            reviewPending = null
            readReview(key)
            publish()
        }
    }

    /** The Review Decision part of the open line's sheet, or null while it offers no choice and has no decision. */
    private fun reviewUi(key: TransactionKey, budgetAppName: String): ReviewUi? {
        val formats = services.formats
        val pending = reviewPending
        if (key.unreadable) {
            val sheet = unreadableSheet ?: return null
            return ReviewUi(
                lines = ReviewDecisionTexts.unreadableLines(sheet, budgetAppName) +
                    listOfNotNull(DetailLine("While it is stored", ReviewDecisionTexts.STAYS_OPEN).takeIf { pending != null }),
                choices = listOfNotNull(ReviewChoiceUi(ReviewChoice.Defer, ReviewChoice.Defer.text, ReviewDecisionTexts.unreadableEffect()).takeIf { sheet.canDefer }),
                reUpload = null,
                clear = ReviewChoiceUi(null, "Clear the decision", ReviewDecisionTexts.unreadableClearEffect()).takeIf { sheet.canClear },
                chooser = null,
                pending = pending,
                answer = reviewAnswer,
                enabled = pending == null,
                closing = ReviewDecisionTexts.CLOSING,
            )
        }
        val sheet = reviewSheet ?: return null
        val current = sheet.decision?.choice
        // UC-004 BR-001: a Transaction Status that offers no choice, without a decision and unlocked, shows no Review Decision.
        if (sheet.choices.isEmpty() && current == null && !sheet.locked && sheet.undecidable == null && reviewAnswer == null) return null
        val open = !sheet.locked
        // UC-004 BR-009: While its Transfer is held back, a Transfer Leg offers no Re-upload; Re-upload is shown only as the core offers it, and one already decided is said to send nothing.
        val reUploadShown = open && ReviewChoice.ReUpload in sheet.choices
        val keptReUpload = open && current == ReviewChoice.ReUpload && !reUploadShown
        return ReviewUi(
            lines = ReviewDecisionTexts.lines(sheet, budgetAppName, formats) +
                listOfNotNull(
                    DetailLine("Re-upload", ReviewDecisionTexts.reUploadKept(sheet)).takeIf { keptReUpload },
                    DetailLine("While it is stored", ReviewDecisionTexts.STAYS_OPEN).takeIf { pending != null },
                ),
            // A6: the choices of the status without the decision, the current one apart; Re-upload is its own switch.
            choices = if (open) {
                sheet.choices.filter { it != ReviewChoice.ReUpload && it != current }.map {
                    ReviewChoiceUi(it, ReviewDecisionTexts.choiceTitle(it), ReviewDecisionTexts.effect(it, sheet, budgetAppName))
                }
            } else {
                emptyList()
            },
            reUpload = if (reUploadShown) {
                ReUploadUi(current == ReviewChoice.ReUpload, ReviewDecisionTexts.reUpload(current == ReviewChoice.ReUpload, sheet))
            } else {
                null
            },
            clear = ReviewChoiceUi(null, "Clear the decision", ReviewDecisionTexts.clearEffect(sheet))
                .takeIf { sheet.canClear && !(reUploadShown && current == ReviewChoice.ReUpload) },
            chooser = chooser?.let { options ->
                if (options.size == 1) {
                    ChooserUi("The only resembling Import:", options.map { ResemblingUi(it.budgetAppReference, ReviewDecisionTexts.resembling(it, formats)) }, "Store Already Present")
                } else {
                    ChooserUi("Which Budget App Transaction is the same?", options.map { ResemblingUi(it.budgetAppReference, ReviewDecisionTexts.resembling(it, formats)) }, "This one is the same")
                }
            },
            pending = pending,
            answer = reviewAnswer,
            enabled = pending == null,
            closing = ReviewDecisionTexts.CLOSING,
        )
    }

    /** Opens "What the statuses mean". */
    fun openStatuses() {
        if (reviewPending != null) return
        openLine = null
        statusesOpen = true
        publish()
    }

    /** System back: closes an open sheet and answers true, or answers false when none is open. */
    fun back(): Boolean {
        if (openLine == null && !statusesOpen) return false
        close()
        return true
    }

    /** Closes the open sheet; not while a decision of it is on its way (NFR-023), which the sheet says. */
    fun close() {
        if (reviewPending != null) return
        openLine = null
        statusesOpen = false
        forgetReview()
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
            sheet = openLine?.let { key -> lines.firstOrNull { it.key == key } }?.let { line ->
                BankAccountTexts.sheet(line, view, formats, today).copy(
                    review = reviewUi(line.key, budgetApp),
                    reviewNotRead = ReviewDecisionTexts.NOT_READ.takeIf { reviewNotRead },
                    canLeave = reviewPending == null,
                )
            },
            statusesSheet = if (statusesOpen) BankAccountTexts.statusesSheet(view, statusesAsOf, formats, today) else null,
        )
    }
}
