package slussen.app.ui.transfers

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.app.ui.bankaccount.DetailLine
import slussen.core.port.TransactionStatus
import slussen.core.status.BudgetTransfersView
import slussen.core.status.LegShown
import slussen.core.status.NoLongerATransfer
import slussen.core.status.StatusesAsOf
import slussen.core.status.TransferEligibility
import slussen.core.status.TransferItem
import slussen.core.status.WhyGuessed
import java.time.LocalDate

/**
 * The texts of the Transfers of a Budget (UC-005 step 1, A4, A5, A8, BR-010, BR-013; `docs/mockups/transfers.html`), in
 * Slussen's glossary terms, read-only: the Transfer Decisions (steps 2-6) are not in this version.
 */
object TransfersTexts {
    /** NFR-023: the stored Transfers could not be read; said on the screen, never a crash. */
    const val NOT_READ = "The Transfers stored on this device could not be read; nothing was changed. Open the Transfers again to see them."

    /** The way to the Transfers of a Budget, on its tab (`budget.html` → `transfers.html#list`). */
    const val OPEN_TRANSFERS = "Transfers ›"

    /** NFR-023: the entry's counts could not be read; the list says why when it is opened. */
    const val ENTRY_NOT_READ = "The Transfers stored on this device could not be read; open them to try again."

    /** The way from a Transfer Leg on the Bank Account screen to its Transfer. */
    const val OPEN_ITS_TRANSFER = "Its Transfer, with both sides ›"

    /** The one line about the Transfer Decisions, which this version does not have; no decision is needed now. */
    const val READ_ONLY =
        "Read only in this version: confirming, splitting or holding back a Transfer comes in a later version. Nothing is uploaded, and no decision is needed now."

    /** The closing line of a selected Transfer. */
    const val CLOSING = "Nothing is uploaded, and no decision is needed now."

    /** A selected Transfer that the stored data no longer holds, after the stored data was read again. */
    const val NO_LONGER_LISTED =
        "That Bank Transaction is no longer a Transfer Leg as the latest Comparison left it; the list shows the Transfers as they are stored now."

    /** The counting unit of each count, apart (UX-27): Transfers, and of them the Guessed Transfers. */
    fun counts(view: BudgetTransfersView): String =
        "Transfers in ${view.budgetName}: ${view.transfers.size} · of them Guessed Transfers: ${view.guessedTransfers}"

    /** The entry on the Budget tab, which leads to the list and counts what it counts (UX-27). */
    fun entry(view: BudgetTransfersView): String = "Transfers: ${view.transfers.size} · Guessed Transfers: ${view.guessedTransfers}"

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
        "Guessed Transfer".takeIf { item.guessed },
        item.type.text,
        "Lonely Transfer".takeIf { item.expected != null },
    )

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
        // A8 of UC-005: without a Funded By and without exactly one possible paying Bank Account, the paying side is not known.
        if (item.outflowBudgetAccountName == null) {
            val card = item.whyGuessed.filterIsInstance<WhyGuessed.NoFundedBy>().firstOrNull()?.cardName ?: item.inflowBudgetAccountName
            add("Paying side not known: $card has no Funded By")
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
            item.expected != null -> "one side arrived"
            else -> "one side known"
        }
        return "$arrived · ${eligibility(item.eligibility)}"
    }

    private fun eligibility(eligibility: TransferEligibility): String = when (eligibility) {
        is TransferEligibility.Eligible -> "eligible, through the ${eligibility.writtenThrough.bankAccountName} side"
        is TransferEligibility.WaitsForReviewDecision ->
            "waits for the Review Decision on its ${eligibility.leg.bankAccountName} side, which is ${eligibility.leg.status?.text}"
        is TransferEligibility.KeptOutBy -> "not eligible: its ${eligibility.leg.bankAccountName} side is ${statusOf(eligibility.leg)}"
        TransferEligibility.Guessed -> "not eligible until it is confirmed or corrected"
        TransferEligibility.NotEligible -> "not eligible"
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
        val lonely = item.expected != null || !item.stored
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
        if (item.guessed) add(DetailLine("Why not certain", item.whyGuessed.joinToString(" ") { why(it, item, view) }))
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
        is WhyGuessed.NoFundedBy -> when (val paying = reason.payingBankAccounts) {
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
            "No: its ${eligibility.leg.bankAccountName} side is ${eligibility.leg.status?.text}, so the whole Transfer waits for the Review " +
                "Decision on that Transfer Leg. Review Decisions come in a later version."
        is TransferEligibility.KeptOutBy ->
            "No: its ${eligibility.leg.bankAccountName} side is ${statusOf(eligibility.leg)}, so the whole Transfer is in no Upload Plan, " +
                "from either side, while it is."
        TransferEligibility.Guessed -> "No: a Guessed Transfer is in no Upload Plan until it is confirmed or corrected, which comes in a later version."
        TransferEligibility.NotEligible -> "No, as the latest Comparison left it in ${view.budgetName}."
    }

    /** UC-003 step 7, A1: when the statuses were given, as the Budget tab says it. */
    fun statuses(view: BudgetTransfersView, formats: Formats, now: java.time.Instant): String? =
        Texts.statusesAsOf(view.summary.statusesAsOf, view.budgetAppName, formats, now)
}
