package slussen.app.ui.bankaccount

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.core.operation.OperationRefusal
import slussen.core.operation.RunningOperation
import slussen.core.port.ReviewChoice
import slussen.core.port.TransactionStatus
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.review.NoLongerHolds
import slussen.core.review.ResemblingImport
import slussen.core.review.ReviewOutcome
import slussen.core.review.ReviewSheet
import slussen.core.review.TransferOfLeg
import slussen.core.review.Undecidable
import slussen.core.review.UnreadableOutcome
import slussen.core.review.UnreadableSheet

/**
 * The texts of the Review Decision sheet (UC-004; `docs/mockups/review-decision.html`), in Slussen's glossary terms:
 * why a Bank Transaction is doubtful, what each choice of its Transaction Status does, the current decision, the lock,
 * and every answer of the core at the control that asked. Nothing here is uploaded: the decision decides what a later
 * Upload Plan holds.
 */
object ReviewDecisionTexts {
    /** NFR-023: the Review Decision of the opened line could not be read; said in the sheet, never a crash. */
    const val NOT_READ = "The Review Decision stored on this device could not be read; nothing was changed. Close the line and open it again to decide."

    /** The closing line of the Review Decision part. */
    const val CLOSING = "A Review Decision can be changed until the Bank Transaction is uploaded, and each one is recorded in the Decision Journal. " +
        "Nothing is uploaded in this version."

    /** NFR-023: the answer when the stored data could not be read or written; nothing changed. */
    const val NOT_STORED = "Not stored: Slussen could not use the data stored on this device; nothing was changed · try again"

    fun choiceTitle(choice: ReviewChoice): String = when (choice) {
        ReviewChoice.AlreadyPresent -> "Already Present…"
        else -> choice.text
    }

    // UC-004 BR-009: On a Transfer Leg the decision applies to its whole Transfer; after Split it is decided as an ordinary Bank Transaction.
    /** What [choice] does, as the sheet says it beside the choice (`#possible-import`, `#needs-review`, `#transfer-leg`, `#outcome-unknown`). */
    fun effect(choice: ReviewChoice, sheet: ReviewSheet, budgetAppName: String): String {
        val whole = sheet.transfer?.decidesWholeTransfer == true
        val replaces = sheet.decision != null
        val unknown = sheet.statusWithoutDecision == TransactionStatus.OutcomeUnknown
        val text = when (choice) {
            ReviewChoice.AlreadyPresent -> when {
                whole -> "The whole Transfer counts as already in $budgetAppName; neither side is proposed for an Upload Plan."
                sheet.decision?.noLongerHolds != null -> "Choose another Budget App Transaction; it is then Already In Budget App and never sent."
                else -> "Pick the same Budget App Transaction; it is then Already In Budget App and never sent."
            }
            ReviewChoice.ImportAnyway -> when {
                whole -> "This Transfer Leg rejoins its Transfer, which is then eligible as one linked transfer."
                // UC-004 BR-008: an unknown outcome is sent again under the same Import Identifier; the Operator checks the Budget App first.
                unknown -> "Sent again in a later Upload Plan under the same Import Identifier; check $budgetAppName first."
                else -> "Eligible for a later Upload Plan as a new transaction."
            }
            ReviewChoice.Defer -> when {
                whole -> "The whole Transfer is held back until you change it."
                unknown -> "Held back, still Outcome Unknown, until you change it."
                else -> "Held back, Deferred, until you change it."
            }
            ReviewChoice.ReUpload -> reUpload(on = false, whole)
        }
        return if (replaces) "Replace the decision: ${text.replaceFirstChar { it.lowercase() }}" else text
    }

    // UC-004 BR-003: Re-upload is chosen per Bank Transaction, is off by default, and sends it only inside the Upload Window.
    /** The switch of Re-upload (`#reupload`, `#reupload-merged`, `#no-longer-holds`). */
    fun reUpload(on: Boolean, whole: Boolean): String = when {
        on && whole -> "Re-upload · on: this Transfer Leg rejoins its Transfer. Switch it off to clear the decision."
        on -> "Re-upload · on: sent again as a new transaction under a new Import Identifier, inside the Upload Window. Switch it off to clear the decision."
        whole -> "Re-upload · off; switch it on to let this Transfer Leg rejoin its Transfer."
        else -> "Re-upload · off; switch it on to send it again as a new transaction, inside the Upload Window."
    }

    /** UC-004 A6, BR-006: clearing gives the status the Comparison gives without a decision. */
    fun clearEffect(sheet: ReviewSheet): String = "Back to Slussen's status: ${sheet.statusWithoutDecision?.text ?: "the one without a decision"}."

    /** The lines of the Review Decision part, before its choices. */
    fun lines(sheet: ReviewSheet, budgetAppName: String, formats: Formats): List<DetailLine> = buildList {
        sheet.undecidable?.let { add(DetailLine("No Review Decision now", undecidable(it, budgetAppName))) }
        // step 2 of UC-004: the reason it is doubtful, as the Bank Account screen gives it.
        sheet.why?.let { add(DetailLine("Why it is doubtful", BankAccountTexts.doubt(it))) }
        sheet.transfer?.let { transfer ->
            add(DetailLine("Its Transfer", transferText(transfer, formats)))
            add(
                DetailLine(
                    "Decides",
                    if (transfer.decidesWholeTransfer) {
                        "This decision decides the whole Transfer."
                    } else {
                        "Its Transfer is split, so this Bank Transaction is decided as an ordinary one."
                    },
                ),
            )
        }
        sheet.decision?.let { decision ->
            val chosen = decision.chosen?.let { " · names ${listOfNotNull(it.payee, formats.date(it.date), formats.difference(it.amount)).joinToString(" ")} in Budget Account ${it.budgetAccountName}" }.orEmpty()
            add(DetailLine("Current Review Decision", "${decision.choice.text}, made ${formats.dateTime(decision.decidedAt)}$chosen"))
            decision.noLongerHolds?.let { add(DetailLine("No longer holds", noLongerHolds(it, budgetAppName))) }
            // UC-004 BR-006: a rule before the decision gives the status; the decision is kept and followed again later.
            val status = sheet.status
            if (status == TransactionStatus.BeforeStartDate || status == TransactionStatus.TooOld || status == TransactionStatus.InTheFuture) {
                add(
                    DetailLine(
                        "Kept",
                        "It is ${status.text}, a rule that comes before your decision. Slussen keeps the decision and follows it again once that rule no longer applies.",
                    ),
                )
            }
        }
        // UC-004 BR-004: A Review Decision is locked while its Bank Transaction is Already In Budget App because Slussen sent it; the sheet says so, with what can be done instead.
        if (sheet.locked) {
            add(
                DetailLine(
                    "Locked",
                    "Locked: $budgetAppName holds this Bank Transaction under its own Import Identifier, so its Review Decision can no longer " +
                        "be changed or cleared. If it should not be there, change or delete it in $budgetAppName; once it is deleted there, it can be re-uploaded here.",
                ),
            )
        }
    }

    private fun transferText(transfer: TransferOfLeg, formats: Formats): String {
        val head = "${transfer.type.text} ${transfer.outflowBudgetAccountName ?: "?"} → ${transfer.inflowBudgetAccountName}, ${formats.amount(transfer.amount)}."
        val other = transfer.otherLeg?.let { " Other Transfer Leg: ${it.bankAccountName}, ${formats.date(it.bookedDate)}, ${formats.difference(it.amount)}." }
            ?: " Its other side has not arrived."
        return head + other
    }

    private fun noLongerHolds(why: NoLongerHolds, budgetAppName: String): String = when (why) {
        NoLongerHolds.NoLongerThere -> "The chosen Budget App Transaction is no longer in $budgetAppName: it was deleted, merged into another one or is not read any more."
        NoLongerHolds.InAnotherBudgetAccount -> "The chosen Budget App Transaction is in another Budget Account now than when you chose it."
        NoLongerHolds.AnotherAmount -> "The chosen Budget App Transaction has another amount now than when you chose it."
    }

    private fun undecidable(reason: Undecidable, budgetAppName: String): String = when (reason) {
        Undecidable.NotComparedYet -> "It has no Transaction Status yet. Read $budgetAppName again to compare it; then it can be decided."
        Undecidable.BankAccountNotCompared ->
            "Its Bank Account is not mapped into a Budget Slussen serves, or is a Retired Account, so it is not compared. Map or restore it in Account Mappings."
    }

    /** One resembling Import, as the list of step 4 shows it (BR-002): payee, amount, date, Budget Account. */
    fun resembling(resembling: ResemblingImport, formats: Formats): String =
        listOfNotNull(resembling.payee, formats.amount(resembling.amount)).joinToString(" · ") +
            " · ${formats.date(resembling.date)} · Budget Account ${resembling.budgetAccountName} · Other Import Path"

    /** NFR-023: what the control shows from the tap on, before anything is stored: that it stores, or what it waits for. */
    fun pending(what: String, running: RunningOperation?): String =
        running?.let { "$what · waits for: ${Texts.operation(it)}" } ?: "$what · storing…"

    fun refusal(reason: OperationRefusal): String = when (reason) {
        OperationRefusal.UploadPlanAwaitingConfirmation -> "Not stored: unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first."
        OperationRefusal.UploadBeingWritten -> "Not stored: unavailable while an Upload is being written."
    }

    /** The answer at the control, for every outcome of a Review Decision (UC-004 steps 6-8, A1-A7, A11). */
    fun answer(outcome: ReviewOutcome, budgetAppName: String): String = when (outcome) {
        is ReviewOutcome.Stored -> outcome.decision?.let { "Review Decision stored: ${it.text} · Transaction Status now ${outcome.status.text}" }
            ?: "Review Decision cleared · Transaction Status now ${outcome.status.text}"
        is ReviewOutcome.ChooseTransaction -> "Not stored yet: choose which Budget App Transaction is the same."
        is ReviewOutcome.NotOffered -> "Not stored: its Transaction Status, as stored now, does not offer that choice; the choices it offers are shown."
        ReviewOutcome.Locked -> "Not stored: the Review Decision is locked, because $budgetAppName holds this Bank Transaction under its own Import Identifier."
        ReviewOutcome.NothingToClear -> "Nothing to clear: it has no Review Decision now."
        is ReviewOutcome.NotDecidable -> "Not stored: ${undecidable(outcome.reason, budgetAppName).replaceFirstChar { it.lowercase() }}"
        ReviewOutcome.NotFound -> "Not stored: this Bank Transaction is no longer stored on this device."
        is ReviewOutcome.Unavailable -> refusal(outcome.reason)
        ReviewOutcome.NotStored -> NOT_STORED
    }

    // ---- An Unreadable Transaction (UC-004 A9, BR-007) ---------------------------------------------------------------

    fun unreadableLines(sheet: UnreadableSheet, budgetAppName: String): List<DetailLine> = listOf(
        DetailLine("Uploaded", "Never uploaded: Slussen could not read what to send. If you want it in $budgetAppName, type it in there."),
    )

    fun unreadableEffect(): String = "Takes it out of the transactions waiting for a decision; it is never uploaded."

    fun unreadableClearEffect(): String = "Back to Needs Review, among the transactions waiting for a decision."

    fun unreadableAnswer(outcome: UnreadableOutcome): String = when (outcome) {
        is UnreadableOutcome.Stored -> if (outcome.status == UnreadableTransactionStatus.Deferred) {
            "Review Decision stored: Defer · now Deferred"
        } else {
            "Review Decision cleared · now Needs Review"
        }
        is UnreadableOutcome.Unchanged -> "It is ${outcome.status.text} already; nothing was stored."
        UnreadableOutcome.NotFound -> "Not stored: this Unreadable Transaction is no longer stored on this device."
        is UnreadableOutcome.Unavailable -> refusal(outcome.reason)
        UnreadableOutcome.NotStored -> NOT_STORED
    }
}
