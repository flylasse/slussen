package slussen.app.ui.transfers

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.app.ui.bankaccount.BankAccountTexts
import slussen.app.ui.bankaccount.DetailLine
import slussen.core.port.OtherSideKind
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.operation.OperationRefusal
import slussen.core.operation.RunningOperation
import slussen.core.transfer.TakenOtherSide
import slussen.core.transfer.TransferOutcome
import slussen.core.transfer.TransferSheet
import slussen.core.transfer.TransferUndecidable
import slussen.core.status.BudgetTransfersView
import slussen.core.status.LegShown
import slussen.core.status.NoLongerATransfer
import slussen.core.status.StatusesAsOf
import slussen.core.status.TransferEligibility
import slussen.core.status.TransferItem
import slussen.core.status.WhyGuessed
import java.time.LocalDate

/**
 * The texts of the Transfers of a Budget (UC-005; `docs/mockups/transfers.html`, `transfer-budget-account.html`), in
 * Slussen's glossary terms: the list, a selected Transfer, its Transfer Decisions and every answer of the core at the
 * control that asked. Nothing is uploaded in this version.
 */
/** UC-005 BR-005: the Transfer Leg [leg] (its Bank Account, date and amount) a Lonely Transfer was paired with at once, and whether it left a Transfer Slussen had proposed ([dissolved]). */
data class PairedNow(val leg: String, val dissolved: Boolean)

object TransfersTexts {
    /** NFR-023: the stored Transfers could not be read; said on the screen, never a crash. */
    const val NOT_READ = "The Transfers stored on this device could not be read; nothing was changed. Open the Transfers again to see them."

    /** The way to the Transfers of a Budget, on its tab (`budget.html` → `transfers.html#list`). */
    const val OPEN_TRANSFERS = "Transfers ›"

    /** NFR-023: the entry's counts could not be read; the list says why when it is opened. */
    const val ENTRY_NOT_READ = "The Transfers stored on this device could not be read; open them to try again."

    /** The way from a Transfer Leg on the Bank Account screen to its Transfer. */
    const val OPEN_ITS_TRANSFER = "Its Transfer, with both sides ›"

    /** The one line about the Transfer Decisions (UC-005 step 2) and the Uploads, which this version does not have. */
    const val DECISIONS_NOTE =
        "Open a Transfer to confirm it, split it, hold it back or choose another Budget Account. Nothing is uploaded in this version."

    /** NFR-023: why the Transfer cannot be left while its decision is on its way. */
    const val STAYS_OPEN = "This Transfer stays open until the answer is in; then you can close it or open another."

    /** The closing line of a selected Transfer. */
    const val CLOSING = "A Transfer Decision can be changed until a Transfer Leg is uploaded, and each one is recorded in the Decision Journal. Nothing is uploaded in this version."

    /** NFR-023: the Transfer Decisions of the opened Transfer could not be read; said in the sheet, never a crash. */
    const val DECISION_NOT_READ =
        "The Transfer Decision stored on this device could not be read; nothing was changed. Close the Transfer and open it again to decide."

    /** NFR-023: the answer when the stored data could not be read or written; nothing changed. */
    const val NOT_STORED = "Not stored: Slussen could not use the data stored on this device; nothing was changed · try again"

    /** A selected Transfer that the stored data no longer holds, after the stored data was read again. */
    const val NO_LONGER_LISTED =
        "That Bank Transaction is no longer a Transfer Leg as the latest Comparison left it; the list shows the Transfers as they are stored now."

    /** The counting unit of each count, apart (UX-27): Transfers, and of them the Guessed Transfers. */
    fun counts(view: BudgetTransfersView): String =
        "Transfers in ${view.budgetName}: ${view.transfers.size} · of them Guessed Transfers: ${view.guessedTransfers}"

    /**
     * The entry on the Budget tab, which leads to the list and counts what it counts (UX-27); for a Budget with nothing
     * compared it says so, as the list does, instead of a count of 0.
     */
    fun entry(view: BudgetTransfersView): String = when (view.summary.statusesAsOf) {
        StatusesAsOf.NothingToCompare -> "Transfers: none · no Bank Account of this Budget is compared"
        is StatusesAsOf.NotReadYet -> "Transfers: none detected yet · ${view.budgetAppName} has not been read for ${view.budgetName}"
        is StatusesAsOf.LastRead -> "Transfers: ${view.transfers.size} · Guessed Transfers: ${view.guessedTransfers}"
    }

    /**
     * UX-27: how the Transfers relate to the Budget's counts of Bank Transactions: the Transfer Legs of the Guessed
     * Transfers are among those waiting for a decision, and an eligible Transfer counts once, through the side it is
     * written through (UC-006 BR-009).
     */
    fun countsNote(view: BudgetTransfersView): String? {
        if (view.transfers.isEmpty()) return null
        val legs = view.guessedTransferLegs
        val waiting = when (legs) {
            0 -> null
            1 -> "1 Bank Transaction of these Guessed Transfers is counted among those waiting for a decision."
            else -> "$legs Bank Transactions of these Guessed Transfers are counted among those waiting for a decision."
        }
        return listOfNotNull(waiting, "An eligible Transfer counts as one Eligible Transaction, the side it is written through.").joinToString(" ")
    }

    /** `#no-transfers`: an expected result, not a failure, with the dates it covers; or why nothing can be detected yet. */
    fun empty(view: BudgetTransfersView, formats: Formats): String? {
        if (view.transfers.isNotEmpty()) return null
        val covers = view.covers
        return when {
            view.summary.statusesAsOf is StatusesAsOf.NothingToCompare -> "No Transfers in ${view.budgetName}: no Bank Account of this Budget is compared."
            view.summary.statusesAsOf is StatusesAsOf.NotReadYet ->
                "No Transfers yet: Transfers are detected when Slussen compares the Bank Transactions with ${view.budgetAppName}, " +
                    "and ${view.budgetAppName} has not been read for ${view.budgetName}."
            covers == null -> "No Transfers in ${view.budgetName}: none of its Bank Transactions is compared yet."
            else -> "No Transfers in ${view.budgetName}. Among the stored Bank Transactions of ${formats.date(covers.start)} to " +
                "${formats.date(covers.endInclusive)} Slussen found no Transfer between your Bank Accounts. Nothing is wrong and no " +
                "action is needed; a later Fetch may bring one."
        }
    }

    // ---- One Transfer of the list (`#list`) --------------------------------------------------------------------------

    /** The kinds a Transfer is, as its chips show them. */
    fun chips(item: TransferItem): List<String> = listOfNotNull(
        // UC-005 BR-010: a Guessed Transfer the Operator split or holds back keeps its flag; Confirm and another Budget Account remove it.
        "Guessed Transfer".takeIf { item.guessed },
        item.type.text,
        "Lonely Transfer".takeIf { item.expected != null || decidedLonely(item) },
        // UC-005 BR-010: the Operator's Transfer Decision is shown with the Transfer, which stays listed so that it can be changed.
        decisionChip(item.decision),
    )

    // UC-005 BR-005: A Lonely Transfer with a Transfer Decision keeps it and stays a Lonely Transfer after the grace period, until its other side arrives.
    /** A Lonely Transfer the Operator decided on: one Transfer Leg, both Budget Accounts known, no grace period that ends it. */
    private fun decidedLonely(item: TransferItem): Boolean =
        item.decision != TransferDecision.Proposed && item.decision != TransferDecision.Split && item.legs.size == 1 && item.outflowBudgetAccountName != null

    /** The Budget Account of the side a Lonely Transfer the Operator decided on waits for, with its amount as its Bank Transaction would have it. */
    private fun decidedOtherSide(item: TransferItem, formats: Formats): String? {
        if (!decidedLonely(item)) return null
        val arrivedIsInflow = item.inflowLeg != null
        val name = if (arrivedIsInflow) item.outflowBudgetAccountName else item.inflowBudgetAccountName
        val amount = if (arrivedIsInflow) "−${formats.amount(item.amount)}" else "+${formats.amount(item.amount)}"
        return "$name $amount"
    }

    private fun decisionChip(decision: TransferDecision): String? = when (decision) {
        TransferDecision.Proposed -> null
        TransferDecision.Held -> "Held Transfer"
        else -> decision.text
    }

    /**
     * Its two Budget Accounts, outflow first: `?` stands for a paying side Slussen does not know (A8), and follows the side
     * Slussen only proposes when it could be another (BR-003, A8).
     */
    fun budgetAccounts(item: TransferItem): String {
        val reasons = item.whyGuessed
        val inflowUncertain = reasons.any { reason ->
            reason is WhyGuessed.OtherCards || (reason is WhyGuessed.OtherCandidates && reason.others.any { it.amount.thousandths > 0 })
        }
        val outflowUncertain = reasons.any { reason ->
            reason is WhyGuessed.NoFundedBy || (reason is WhyGuessed.OtherCandidates && reason.others.any { it.amount.thousandths < 0 })
        }
        val outflow = item.outflowBudgetAccountName?.let { if (outflowUncertain) "$it?" else it } ?: "?"
        val inflow = if (inflowUncertain) "${item.inflowBudgetAccountName}?" else item.inflowBudgetAccountName
        return "$outflow → $inflow"
    }

    /** One side of the Transfer as the list shows it: arrived with its date, expected, or not known. */
    fun sides(item: TransferItem, formats: Formats, today: LocalDate): List<String> = buildList {
        item.outflowLeg?.let { add(legLine(it, formats, today)) }
        item.inflowLeg?.let { add(legLine(it, formats, today)) }
        decidedOtherSide(item, formats)?.let { add("$it · not arrived; it joins this Transfer when it arrives") }
        item.expected?.let { expected ->
            add(
                "${expected.budgetAccountName} ${formats.difference(expected.amount)} · " +
                    if (expected.graceEnded) {
                        "not arrived; its grace period ended ${formats.day(expected.by, today)}"
                    } else {
                        "expected by ${formats.date(expected.by)} · a later Fetch may bring it"
                    },
            )
        }
        // A8 of UC-005: without a Funded By that applies and without exactly one possible paying Bank Account, the paying side is not known.
        if (item.outflowBudgetAccountName == null) {
            val reason = item.whyGuessed.filterIsInstance<WhyGuessed.NoFundedBy>().firstOrNull()
            val card = reason?.cardName ?: item.inflowBudgetAccountName
            // S-B-15: a Funded By that is set and no longer applies is named, never called missing.
            add(
                reason?.fundedByNotApplying?.let { "Paying side not known: the Funded By $it no longer applies · choose the paying Budget Account" }
                    ?: "Paying side not known: $card has no Funded By",
            )
        }
    }

    private fun legLine(leg: LegShown, formats: Formats, today: LocalDate): String =
        listOfNotNull(
            "${leg.bankAccountName} ${formats.difference(leg.amount)}",
            formats.day(leg.bookedDate, today),
            "arrived",
            // UC-005 BR-010: A Transfer Leg to which an earlier rule of the Comparison gave its Transaction Status keeps that status and stays a Transfer Leg of its Transfer.
            leg.status?.takeUnless { it == TransactionStatus.TransferLeg || it == TransactionStatus.GuessedTransfer }?.text,
        ).joinToString(" · ")

    /** The state line of a Transfer: whether both sides arrived and whether it is eligible. */
    fun state(item: TransferItem): String {
        val arrived = when {
            item.bothArrived -> "both sides arrived"
            item.expected != null || decidedLonely(item) -> "one side arrived"
            else -> "one side known"
        }
        return "$arrived · ${eligibility(item.eligibility)}"
    }

    private fun eligibility(eligibility: TransferEligibility): String = when (eligibility) {
        is TransferEligibility.Eligible -> "eligible, through the ${eligibility.writtenThrough.bankAccountName} side"
        // A9 step 1 of UC-005: with the reason the Transfer Leg is doubtful.
        is TransferEligibility.WaitsForReviewDecision ->
            "waits for the Review Decision on its ${eligibility.leg.bankAccountName} side, which is ${eligibility.leg.status?.text}: " +
                BankAccountTexts.doubt(eligibility.why)
        is TransferEligibility.KeptOutBy -> "not eligible: its ${eligibility.leg.bankAccountName} side is ${statusOf(eligibility.leg)}"
        TransferEligibility.Guessed -> "not eligible until it is confirmed or corrected"
        TransferEligibility.NotEligible -> "not eligible"
        TransferEligibility.GraceEnded -> "not eligible as a Transfer: the next Comparison makes it an ordinary Bank Transaction"
        TransferEligibility.Split -> "split: each side is an ordinary Bank Transaction"
        TransferEligibility.HeldBack -> "held back: not eligible, from either side"
        TransferEligibility.RetiredSide -> "not eligible while a Bank Account of its sides is a Retired Account"
    }

    private fun statusOf(leg: LegShown): String = leg.status?.text ?: "not compared yet"

    // ---- A5: no longer a Transfer --------------------------------------------------------------------------------------

    fun noLongerTitle(item: NoLongerATransfer): String = "${item.leg.budgetAccountName} · ${item.leg.bankAccountName} · ordinary"

    /** UC-005 A5 (O-T-11): why the Bank Transaction is ordinary now. */
    fun noLongerReason(item: NoLongerATransfer, formats: Formats, today: LocalDate): String {
        val side = if (item.proposed) {
            "No other side arrived in ${item.otherBudgetAccountName}, which Slussen only proposed,"
        } else {
            "Its other side, in ${item.otherBudgetAccountName}, did not arrive"
        }
        return "${formats.day(item.leg.bookedDate, today)} · $side within the Lonely Transfer grace period (${item.graceDays} days, an " +
            "Operator Setting, until ${formats.day(item.lastDay, today)}), so it is an ordinary Bank Transaction, ${item.leg.status?.text ?: "not compared yet"}"
    }

    // ---- A selected Transfer (`#selected`, `#lonely`, `#first-card-payment`, `#doubtful-leg`) --------------------------

    fun sheetTitle(item: TransferItem, view: BudgetTransfersView, formats: Formats): String =
        (chips(item) + "${formats.amount(item.amount)} ${view.currency.code}").joinToString(" · ")

    /** The details of one Transfer, read-only: its sides, why it is guessed, what a Lonely Transfer waits for, eligibility. */
    fun details(item: TransferItem, view: BudgetTransfersView, formats: Formats, today: LocalDate): List<DetailLine> = buildList {
        val lonely = item.expected != null || !item.stored || decidedLonely(item)
        item.outflowLeg?.let { add(DetailLine(if (lonely) "Arrived" else proposedName(item, "Outflow side", outflow = true), legDetail(it, formats))) }
        item.inflowLeg?.let { add(DetailLine(if (lonely) "Arrived" else proposedName(item, "Inflow side", outflow = false), legDetail(it, formats))) }
        item.whyGuessed.filterIsInstance<WhyGuessed.OtherCandidates>().flatMap { it.others }.forEach {
            add(DetailLine("Also possible", legDetail(it, formats)))
        }
        // A4 of UC-005: the expected other side, its Budget Account and the date by which it must arrive.
        item.expected?.let { expected ->
            add(DetailLine("Expected other side", "${formats.difference(expected.amount)} in Budget Account ${expected.budgetAccountName} · not arrived yet"))
            if (expected.graceEnded) {
                add(
                    DetailLine(
                        "Grace period",
                        "It ended ${formats.date(expected.by)} (${view.graceDays} days, an Operator Setting) without the other side. The next " +
                            "Comparison, which a Fetch or reading ${view.budgetAppName} again runs, makes this an ordinary Bank Transaction.",
                    ),
                )
            } else {
                add(DetailLine("Must arrive by", "${formats.date(expected.by)}, the end of the Lonely Transfer grace period (${view.graceDays} days, an Operator Setting)"))
                add(
                    DetailLine(
                        "What happens",
                        "A later Fetch may bring the other side; then both are one Transfer. If none has arrived by ${formats.date(expected.by)}, " +
                            "the next Comparison after that day makes this an ordinary Bank Transaction.",
                    ),
                )
            }
        }
        // UC-005 BR-005: with a Transfer Decision a Lonely Transfer is not ended by its grace period; its other side joins it on arrival.
        decidedOtherSide(item, formats)?.let {
            add(DetailLine("Expected other side", "$it · not arrived yet. With your Transfer Decision it stays a Lonely Transfer, also after its grace period, until its other side arrives and joins it."))
        }
        (item.eligibility as? TransferEligibility.WaitsForReviewDecision)?.let { add(DetailLine("Why it is doubtful", BankAccountTexts.doubt(it.why))) }
        when {
            item.guessed && item.decision == TransferDecision.Proposed -> add(DetailLine("Why not certain", item.whyGuessed.joinToString(" ") { why(it, item, view) }))
            // UC-005 BR-010: a Guessed Transfer the Operator split or holds back keeps its flag; the decision is what applies to it.
            item.guessed -> add(DetailLine("Guessed", "Slussen could only guess this Transfer; your Transfer Decision is what applies to it."))
        }
        add(DetailLine("Eligible", eligibleDetail(item.eligibility, view)))
    }

    /** The name of a side: "Proposed …" when Slussen only proposes it (BR-003, A8). */
    private fun proposedName(item: TransferItem, name: String, outflow: Boolean): String {
        val proposed = item.whyGuessed.any { reason ->
            when (reason) {
                is WhyGuessed.NoFundedBy -> outflow
                is WhyGuessed.OtherCandidates -> reason.others.any { (it.amount.thousandths < 0) == outflow }
                else -> false
            }
        }
        return if (proposed) "Proposed ${name.lowercase()}" else name
    }

    private fun legDetail(leg: LegShown, formats: Formats): String = listOfNotNull(
        leg.bankAccountName,
        "Budget Account ${leg.budgetAccountName}",
        formats.date(leg.bookedDate),
        formats.difference(leg.amount),
        leg.payee ?: leg.memo,
        leg.status?.text ?: "not compared yet",
    ).joinToString(" · ")

    /** Why the Transfer is guessed, in the rule's words (UC-005 BR-002, BR-003, A8). */
    private fun why(reason: WhyGuessed, item: TransferItem, view: BudgetTransfersView): String = when (reason) {
        is WhyGuessed.OtherCandidates ->
            (if (reason.others.size == 1) "Another Bank Transaction" else "${reason.others.size} other Bank Transactions") +
                " of the same amount ${if (reason.others.size == 1) "lies" else "lie"} within the transfer pairing days (${view.pairingDays} days, an Operator " +
                "Setting), so Slussen cannot pair it for certain. It proposed the pairing with the Funded By or Transfer Partner first, " +
                "then the nearest booked date, then the Bank Transaction received first."
        is WhyGuessed.OtherCards ->
            "${item.outflowBudgetAccountName} is the Funded By of more than one credit card, so the card is only proposed: " +
                "${item.inflowBudgetAccountName}, the one added first; it could also be ${reason.budgetAccountNames.joinToString(", ")}."
        // UC-005 BR-014: A Funded By that loses its ground is kept but no longer applies, and is shown as no longer applying, never as missing.
        is WhyGuessed.NoFundedBy -> if (reason.fundedByNotApplying != null) {
            "The Funded By of ${reason.cardName}, ${reason.fundedByNotApplying}, no longer applies, so " +
                (if (reason.payingBankAccounts == null) "its paying side is only proposed" else "Slussen proposes no paying side") +
                ". Change or clear it in Account Mappings."
        } else when (val paying = reason.payingBankAccounts) {
            null -> "${reason.cardName} has no Funded By, so its paying side is only proposed."
            0 -> "${reason.cardName} has no Funded By, and no Bank Account of ${view.budgetName} that is not a credit card could pay it, so Slussen proposes no paying side."
            else -> "${reason.cardName} has no Funded By, and $paying Bank Accounts of ${view.budgetName} that are not credit cards could pay it, so Slussen proposes none of them."
        }
        WhyGuessed.AsStored ->
            "The latest Comparison stored it as a Guessed Transfer. On the data stored now the rules no longer give it as one, so it is " +
                "shown as that Comparison left it until the next Comparison."
    }

    /** UC-003 BR-014, UC-005 BR-010, BR-013: whether the Transfer is eligible, and the rule that keeps it out. */
    private fun eligibleDetail(eligibility: TransferEligibility, view: BudgetTransfersView): String = when (eligibility) {
        is TransferEligibility.Eligible ->
            "Yes, for a later Upload Plan, as one linked transfer written through its ${eligibility.writtenThrough.bankAccountName} side, " +
                "which is counted as one Eligible Transaction. Nothing is uploaded in this version."
        is TransferEligibility.WaitsForReviewDecision ->
            "No: its ${eligibility.leg.bankAccountName} side is ${eligibility.leg.status?.text}, so the whole Transfer waits for your Review " +
                "Decision on that Transfer Leg; decide on it, or split the Transfer."
        is TransferEligibility.KeptOutBy ->
            "No: its ${eligibility.leg.bankAccountName} side is ${statusOf(eligibility.leg)}, so the whole Transfer is in no Upload Plan, " +
                "from either side, while it is."
        TransferEligibility.Guessed -> "No: a Guessed Transfer is in no Upload Plan until you confirm it or choose another Budget Account."
        TransferEligibility.NotEligible -> "No, as the latest Comparison left it in ${view.budgetName}."
        TransferEligibility.GraceEnded ->
            "No: its grace period ended without the other side, so the next Comparison makes this an ordinary Bank Transaction with a " +
                "Transaction Status of its own; it is never sent as a Transfer."
        TransferEligibility.Split ->
            "Not as a Transfer: it is split, so each side has a Transaction Status of its own as an ordinary Bank Transaction."
        TransferEligibility.HeldBack -> "No: it is held back, so neither side is in an Upload Plan, as a Transfer or as an ordinary transaction."
        TransferEligibility.RetiredSide ->
            "No: a Bank Account of its sides is a Retired Account. It keeps its Transfer Decision and is in no Upload Plan, from either side, until that Bank Account is restored."
    }

    /** UC-003 step 7, A1: when the statuses were given, as the Budget tab says it. */
    fun statuses(view: BudgetTransfersView, formats: Formats, now: java.time.Instant): String? =
        Texts.statusesAsOf(view.summary.statusesAsOf, view.budgetAppName, formats, now)

    // ---- The Transfer Decisions of a selected Transfer (UC-005 steps 2-6, A1-A4, A7-A10) ---------------------------------

    fun actionTitle(action: TransferAction): String = when (action) {
        TransferAction.Confirm -> "Confirm"
        TransferAction.Split -> "Split into ordinary transactions"
        TransferAction.Hold -> "Hold back"
    }

    /** What [action] does, beside it (`#selected`, `#lonely`, `#held`). */
    fun actionEffect(action: TransferAction, sheet: TransferSheet): String {
        val lonely = sheet.legs.size == 1
        return when (action) {
            TransferAction.Confirm -> if (lonely) {
                "Into a later Upload Plan as a linked transfer whose other side stays uncleared until it arrives."
            } else {
                "As Slussen proposes it, ${sheet.outflowBudgetAccountName} → ${sheet.inflowBudgetAccountName}; then eligible for a later Upload Plan as one linked transfer."
            }
            TransferAction.Split -> if (lonely) "An ordinary Bank Transaction." else "Each Transfer Leg is compared as an ordinary Bank Transaction."
            TransferAction.Hold -> if (lonely) "It is in no Upload Plan until you change it." else "Neither Transfer Leg is in any Upload Plan until you change it."
        }
    }

    // A8 step 2 of UC-005: With no Budget Account on the paying side there is nothing to confirm: the Operator chooses it, splits or holds back.
    fun chooseTitle(sheet: TransferSheet): String =
        if (sheet.outflowBudgetAccountName == null) "Choose the paying Budget Account…" else "Choose another Budget Account…"

    fun chooseEffect(sheet: TransferSheet, budgetName: String): String =
        if (sheet.outflowBudgetAccountName == null) {
            "Required before this Transfer can be in an Upload Plan."
        } else {
            "One of the other Budget Accounts of $budgetName that Slussen fetches."
        }

    fun decisionText(decision: TransferDecision): String = when (decision) {
        TransferDecision.Proposed -> "None: Slussen proposes it"
        TransferDecision.Confirmed -> "Confirmed"
        TransferDecision.Split -> "Split: each side is an ordinary Bank Transaction"
        TransferDecision.Held -> "Held: neither side is in an Upload Plan until you change it"
        TransferDecision.BudgetAccountChosen -> "Budget Account Chosen"
    }

    /** UC-005 A7, BR-008: the lock, with what the Operator can do instead. */
    fun locked(budgetAppName: String): String =
        "A Transfer Leg of it has been uploaded, so its Transfer Decision can no longer be changed. Correct it in $budgetAppName if it is wrong."

    /** UC-005 A9 step 1: the Transfer Leg that waits for a Review Decision, with why, and what can be done. */
    fun waits(item: TransferItem?, leg: Long): String {
        val waiting = item?.eligibility as? TransferEligibility.WaitsForReviewDecision
        val name = (waiting?.leg ?: item?.legs?.firstOrNull { it.storedBankTransaction == leg })?.bankAccountName
        val side = name?.let { "its $it side" } ?: "one of its Transfer Legs"
        val why = waiting?.let { ", which is ${it.leg.status?.text}: ${BankAccountTexts.doubt(it.why)}" }.orEmpty()
        return "Waits for your Review Decision on $side$why. Decide on that Transfer Leg, or split the Transfer."
    }

    // UC-005 BR-005: A Lonely Transfer with a Transfer Decision is joined by its arriving other side, also one taken from a Transfer Slussen only proposed (G-24).
    const val LONELY_JOINS =
        "If a Bank Transaction of its other side has arrived, confirming it, holding it back or choosing its Budget Account pairs it with " +
            "that one at once, also one Slussen had proposed in another Transfer, which is then dissolved; otherwise one that arrives later joins it."

    /** The same for a Lonely Transfer the Operator decided on already. */
    const val DECIDED_LONELY_JOINS =
        "A Bank Transaction of its other side that arrives later joins it, also one Slussen had paired in another Transfer it only " +
            "proposed; that Transfer is then dissolved."

    const val NOT_DECIDABLE_NOW = "No Transfer Decision can be taken on it as the stored data stand now; the next Comparison says what it is."

    /** NFR-023: what the control shows from the tap on, before anything is stored: that it stores, or what it waits for. */
    fun pending(what: String, running: RunningOperation?): String =
        running?.let { "$what · waits for: ${Texts.operation(it)}" } ?: "$what · storing…"

    fun refusal(reason: OperationRefusal): String = when (reason) {
        OperationRefusal.UploadPlanAwaitingConfirmation -> "Not stored: unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first."
        OperationRefusal.UploadBeingWritten -> "Not stored: unavailable while an Upload is being written."
    }

    /**
     * The answer at the control for every outcome of a Transfer Decision (UC-005 steps 4-6, A1-A4, A7-A10). [towards] is
     * the Budget Account of the side a Lonely Transfer waits for; [keptFor] the Bank Account whose Funded By or Transfer
     * Partner an answer kept, and [keptName] the Budget Account kept.
     */
    fun answer(outcome: TransferOutcome, towards: String?, keptFor: String?, keptName: String?, paired: PairedNow? = null): String = when (outcome) {
        is TransferOutcome.Stored -> buildString {
            append("Transfer Decision stored: ${outcome.decision.text}")
            when {
                // UC-005 BR-005: the side that had arrived joined at once; a Transfer Slussen had proposed for it is dissolved.
                paired != null -> append(" · paired at once with ${paired.leg}, which had arrived" + if (paired.dissolved) "; the Transfer Slussen had proposed for it was dissolved" else "")
                outcome.lonely && outcome.decision != TransferDecision.Split && towards != null ->
                    append(" · a Lonely Transfer towards $towards; it waits for a side that arrives later")
            }
            outcome.kept?.let { append(" · ${it.text} of $keptFor set to $keptName, recorded in the Decision Journal") }
            // ADR-007 §3a: the decision stands; the Comparison marker keeps every Upload Plan from being prepared until the Transfers are detected again.
            if (!outcome.detectedAgain) append(" · the other Transfers are detected again when Slussen next starts or compares; no Upload Plan is prepared before")
        }
        is TransferOutcome.Unchanged -> "It has the Transfer Decision ${outcome.decision.text} already; nothing was stored."
        TransferOutcome.NothingToConfirm ->
            "Not stored: Slussen proposes no paying Budget Account, so there is nothing to confirm. Choose it, split or hold back the Transfer."
        is TransferOutcome.WaitsForReviewDecision ->
            "Not stored: a Transfer Leg waits for your Review Decision; decide on it, or split the Transfer."
        TransferOutcome.Locked -> "Not stored: a Transfer Leg has been uploaded, so the Transfer Decision is locked."
        is TransferOutcome.OtherSideTaken -> "Not stored: that Budget Account cannot be the other side now."
        TransferOutcome.BudgetAccountNotOffered -> "Not stored: that Budget Account cannot be chosen for this Transfer now; choose one of those shown."
        is TransferOutcome.NoLongerValid -> "Not stored: its own sides are no longer valid (${outcome.reason.text}); the next Comparison detects it again."
        TransferOutcome.NotATransfer -> "Not stored: as the stored data stand now, it is no longer a Transfer."
        is TransferOutcome.NotDecidable -> "Not stored: " + when (outcome.reason) {
            TransferUndecidable.NotComparedYet -> "it is not compared yet; read the Budget App again first."
            TransferUndecidable.BankAccountNotCompared -> "its Bank Account is not mapped into a Budget Slussen serves, so it is not compared."
            TransferUndecidable.NoLongerProposed -> "the stored data today no longer give this Transfer as Slussen proposed it; the next Comparison says what it is."
            TransferUndecidable.PeriodReadOwed ->
                "its Budget owes a read of the Budget App: Slussen's copy does not cover the whole read period. Read the Budget App " +
                    "again on the Budget's tab or the Overview; then it can be decided."
        }
        TransferOutcome.NotFound -> "Not stored: this Bank Transaction is no longer stored on this device."
        is TransferOutcome.Unavailable -> refusal(outcome.reason)
        TransferOutcome.NotStored -> NOT_STORED
    }

    // ---- Choosing another Budget Account (`transfer-budget-account.html`) ---------------------------------------------

    fun chooserQuestion(sheet: TransferSheet, stays: String): String =
        if (sheet.outflowBudgetAccountName == null && sheet.keptAs == OtherSideKind.FundedBy) "Which Budget Account paid $stays?" else "Which Budget Account is the other side?"

    fun justThis(kind: OtherSideKind, stays: String): String = "Just this Transfer · the ${kind.text} of $stays stays as it is."

    fun keepAnswer(kind: OtherSideKind, stays: String, name: String): String = "Keep $name as the ${kind.text} of $stays"

    fun keepEffect(kind: OtherSideKind): String = when (kind) {
        OtherSideKind.FundedBy -> "Used for later Card Payments; you can change it in Account Mappings."
        OtherSideKind.TransferPartner -> "Used first when a Bank Transfer pairing is guessed; you can change it in Account Mappings."
    }

    /** UC-005 A10 step 1-2: why the chosen Budget Account cannot be the other side, naming what holds it, and what can be done. */
    fun refused(taken: List<TakenOtherSide>, kind: OtherSideKind, formats: Formats): String {
        val each = taken.joinToString(" ") { candidate ->
            val what = "The Bank Transaction of ${formats.date(candidate.bookedDate)}, ${formats.difference(candidate.amount)},"
            val transfer = candidate.transfer
            when {
                transfer != null ->
                    "$what is a Transfer Leg of the Transfer ${transfer.outflowBudgetAccountName ?: "?"} → ${transfer.inflowBudgetAccountName}, " +
                        "${formats.amount(transfer.amount)}, ${transfer.dates.distinct().joinToString(" and ") { formats.date(it) }}, " +
                        if (transfer.locked) "one of whose Transfer Legs has been uploaded." else "which you ${past(transfer.decision)}."
                else -> "$what has been uploaded already."
            }
        }
        val canChange = taken.any { it.transfer != null && !it.transfer!!.locked }
        val next = if (canChange) {
            "Change that Transfer Decision first, choose another Budget Account, or split or hold back this Transfer."
        } else {
            "Choose another Budget Account, or split or hold back this Transfer."
        }
        return "$each $next No ${kind.text} was set."
    }

    private fun past(decision: TransferDecision): String = when (decision) {
        TransferDecision.Confirmed -> "confirmed"
        TransferDecision.Split -> "split"
        TransferDecision.Held -> "hold back"
        TransferDecision.BudgetAccountChosen -> "gave another Budget Account"
        TransferDecision.Proposed -> "did not decide on"
    }
}
