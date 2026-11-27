package slussen.core.transfer

import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.port.BankAccountType
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.KeptOtherSide
import slussen.core.port.OtherSideKind
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDecisionWrite
import slussen.core.port.TransferType
import slussen.pluginapi.value.Amount
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The Transfer the Operator selected through one of its Transfer Legs (UC-005 step 2), worked out from stored data by the
 * rules of the Comparison: [transfer] as it is stored, with an `id` of 0 for a Guessed Transfer that has no stored
 * Transfer because Slussen proposes no paying Budget Account (A8); its Transfer Legs; whether its Transfer Decision is
 * locked (BR-008); the Transfer Leg that waits for a Review Decision (A9); the Transfer Leg that stays when the Operator
 * chooses another Budget Account, and the Budget Accounts that can be chosen (BR-014, BR-016).
 */
data class SelectedTransfer(
    val transfer: StoredTransfer,
    val legs: List<StoredBankTransaction>,
    val budget: StoredBudget,
    val locked: Boolean,
    val waitsFor: StoredBankTransaction?,
    val stays: StoredBankTransaction,
    val staysIn: StoredBankAccount,
    val offered: List<Long>,
) {
    /** Whether a Transfer is stored for it. */
    val isStored: Boolean get() = transfer.id != TransferDecisionRules.NOT_STORED

    /** BR-016: the other side the Transfer has now, seen from the Transfer Leg that stays; null when Slussen proposes none (A8). */
    val otherSide: Long? get() = if (stays.amount.thousandths < 0) transfer.inflowBudgetAccountId else transfer.outflowBudgetAccountId

    /** A3 step 1: the answer is kept as the Funded By of a credit card Bank Account and as the Transfer Partner of any other. */
    val keptAs: OtherSideKind get() = if (staysIn.type == BankAccountType.CreditCard) OtherSideKind.FundedBy else OtherSideKind.TransferPartner
}

/** What the rules say of the Bank Transaction a Transfer is selected through. */
sealed interface TransferRuled {
    data class Stands(val selected: SelectedTransfer) : TransferRuled

    data class Cannot(val reason: TransferUndecidable) : TransferRuled

    data object NotATransfer : TransferRuled

    data object NoSuchTransaction : TransferRuled
}

/**
 * What the Decision Journal records of one stored Transfer Decision (UC-005 BR-011): the Transfer as it stood before the
 * decision with its Transfer Legs, the Transfer as it is stored now with its Transfer Legs, and the Funded By or Transfer
 * Partner the decision set, with the value it replaces.
 */
data class TransferDecisionFacts(
    val before: StoredTransfer,
    val legsBefore: List<StoredBankTransaction>,
    val after: StoredTransfer,
    val legsAfter: List<StoredBankTransaction>,
    val kept: KeptOtherSide?,
    val keptBefore: Long?,
)

/** What a ruling answers: the outcome for the Operator, and what the Decision Journal records when something is stored. */
data class RuledTransferOutcome(val outcome: TransferOutcome, val facts: TransferDecisionFacts? = null)

/**
 * The rules of the Transfer Decisions (UC-005 steps 2-5, A1-A4, A7-A10, BR-006, BR-008, BR-014, BR-016), `:core`
 * `transfer` (ADR-001 §7): which Transfer a Bank Transaction selects, what each decision stores, and when a decision is
 * refused. Pure: the read model applies them to the stored data it shows, and O-13 applies them inside its transaction to
 * what is stored (ADR-002 §3).
 */
object TransferDecisionRules {
    /** The `id` of a Transfer that is not stored yet: O-13 stores it as a new one. */
    const val NOT_STORED = 0L

    private val DOUBTFUL = setOf(TransactionStatus.NeedsReview, TransactionStatus.PossibleImportByOtherPath, TransactionStatus.DecisionNoLongerHolds)

    private val StoredBankAccount.creditCard: Boolean get() = type == BankAccountType.CreditCard

    /** The Transfer the Bank Transaction [storedBankTransaction] selects in [data], the stored data of its Budget, on the day of [windows]. */
    fun selected(storedBankTransaction: Long, data: ComparisonData, windows: ComparisonWindows): TransferRuled =
        selected(storedBankTransaction, data, windows, ComparisonRules.conclude(data, windows))

    private fun selected(storedBankTransaction: Long, data: ComparisonData, windows: ComparisonWindows, conclusions: ComparisonConclusions): TransferRuled {
        val transaction = data.bankTransactions.firstOrNull { it.id == storedBankTransaction } ?: return TransferRuled.NoSuchTransaction
        val bankAccount = data.bankAccounts.firstOrNull { it.id == transaction.bankAccountId } ?: return TransferRuled.Cannot(TransferUndecidable.BankAccountNotCompared)
        val budget = data.budgets.firstOrNull { budget -> budget.budgetAccounts.any { it.id == bankAccount.mapping?.budgetAccountId } }
            ?: return TransferRuled.Cannot(TransferUndecidable.BankAccountNotCompared)
        // Precondition of UC-005: the Bank Transactions have Transaction Statuses from a Comparison.
        if (transaction.status == null) return TransferRuled.Cannot(TransferUndecidable.NotComparedYet)
        val stored = data.transfers.firstOrNull { it.id == transaction.transferId }
        val transfer = stored ?: withoutProposal(transaction, bankAccount, budget, data, windows) ?: return TransferRuled.NotATransfer
        val legs = if (stored == null) listOf(transaction) else data.bankTransactions.filter { it.transferId == stored.id }.sortedBy { it.id }
        val locked = TransferRules.locked(transfer, legs, data.sentRecords)
        // UC-005 BR-005: A Lonely Transfer without a Transfer Decision whose grace period has ended is an ordinary Bank Transaction, so there is no Transfer to decide on any more, also before the next Comparison says so.
        if (stored != null && stored.decision == TransferDecision.Proposed && !locked && !stillProposed(stored, legs, conclusions)) {
            return TransferRuled.Cannot(TransferUndecidable.NoLongerProposed)
        }
        val stays = staying(transfer, legs, data.bankAccounts)
        val staysIn = data.bankAccounts.firstOrNull { it.id == stays.bankAccountId } ?: return TransferRuled.Cannot(TransferUndecidable.BankAccountNotCompared)
        val sides = TransferDetection.sidesOf(budget, data.bankAccounts)
        return TransferRuled.Stands(
            SelectedTransfer(
                transfer = transfer,
                legs = legs,
                budget = budget,
                locked = locked,
                // UC-005 BR-013: The doubt about a Bank Transaction itself is decided before its Transfer is eligible.
                waitsFor = legs.firstOrNull { conclusions.statuses[it.id] in DOUBTFUL },
                stays = stays,
                staysIn = staysIn,
                // UC-005 BR-014: The other side of a Transfer is always a Budget Account that Slussen fetches, of the same Budget and currency, never a Manually Kept Account.
                offered = budget.budgetAccounts.map { it.id }.filter { it in sides && it != staysIn.mapping?.budgetAccountId },
            ),
        )
    }

    /**
     * Whether the rules of the Comparison, on the stored data today, still give the stored Transfer [stored], which has no
     * Transfer Decision, with the same Transfer Legs and the same two Budget Accounts: a Transfer they no longer give is
     * released by the next Comparison (A5), so the Operator decides on nothing that Slussen no longer proposes.
     */
    private fun stillProposed(stored: StoredTransfer, legs: List<StoredBankTransaction>, conclusions: ComparisonConclusions): Boolean =
        conclusions.transfers.any { proposed ->
            proposed.continues == stored.id && proposed.legs.toSet() == legs.map { it.id }.toSet() &&
                proposed.outflowBudgetAccountId == stored.outflowBudgetAccountId && proposed.inflowBudgetAccountId == stored.inflowBudgetAccountId
        }

    /**
     * UC-005 A8: the Guessed Transfer that [transaction] is by today's rules when Slussen proposes no paying Budget
     * Account for it, which has no stored Transfer: a Card Payment into its Budget Account, with no outflow side.
     */
    private fun withoutProposal(
        transaction: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget,
        data: ComparisonData,
        windows: ComparisonWindows,
    ): StoredTransfer? {
        if (transaction.transferId != null || transaction.id !in ComparisonRules.transfersOf(budget.id, data, windows).withoutProposal) return null
        return StoredTransfer(
            id = NOT_STORED,
            outflowBudgetAccountId = null,
            inflowBudgetAccountId = bankAccount.mapping?.budgetAccountId ?: return null,
            amount = Amount(abs(transaction.amount.thousandths)),
            type = TransferType.CardPayment,
            guessed = true,
            decision = TransferDecision.Proposed,
            decidedAt = null,
        )
    }

    // UC-005 BR-016: When the Operator chooses another Budget Account, one Transfer Leg stays: the one of a Lonely Transfer; otherwise, for a Card Payment, the one in the credit card Bank Account, or the inflow when both sides are credit cards, and for a Bank Transfer the outflow.
    /** The Transfer Leg of [transfer] that stays, among its [legs]. */
    fun staying(transfer: StoredTransfer, legs: List<StoredBankTransaction>, bankAccounts: List<StoredBankAccount>): StoredBankTransaction {
        legs.singleOrNull()?.let { return it }
        val outflow = legs.firstOrNull { it.amount.thousandths < 0 } ?: legs.first()
        val inflow = legs.firstOrNull { it.amount.thousandths >= 0 } ?: legs.last()
        if (transfer.type != TransferType.CardPayment) return outflow
        val outflowIsCard = bankAccounts.firstOrNull { it.id == outflow.bankAccountId }?.creditCard == true
        val inflowIsCard = bankAccounts.firstOrNull { it.id == inflow.bankAccountId }?.creditCard == true
        return if (outflowIsCard && !inflowIsCard) outflow else inflow
    }

    // UC-005 BR-016: Slussen proposes keeping the answer when the Operator chooses the Budget Account Slussen proposed, or answers a first Card Payment for which Slussen proposed none, and proposes not keeping it for another Budget Account.
    /** Whether Slussen proposes keeping [budgetAccountId] as the Funded By or Transfer Partner when the Operator chooses it for [selected]. */
    fun proposesKeeping(selected: SelectedTransfer, budgetAccountId: Long): Boolean = selected.otherSide == null || selected.otherSide == budgetAccountId

    /** Steps 4-5, A1-A4, A7-A10 inside O-13: what deciding [choice] for the Transfer of [storedBankTransaction] at [now] stores. */
    fun decide(
        storedBankTransaction: Long,
        choice: TransferChoice,
        data: ComparisonData,
        windows: ComparisonWindows,
        now: Instant,
    ): Pair<TransferDecisionWrite?, RuledTransferOutcome> {
        fun refused(outcome: TransferOutcome) = null to RuledTransferOutcome(outcome)
        val selected = when (val ruled = selected(storedBankTransaction, data, windows)) {
            is TransferRuled.Stands -> ruled.selected
            is TransferRuled.Cannot -> return refused(TransferOutcome.NotDecidable(ruled.reason))
            TransferRuled.NotATransfer -> return refused(TransferOutcome.NotATransfer)
            TransferRuled.NoSuchTransaction -> return refused(TransferOutcome.NotFound)
        }
        // UC-005 BR-008: Once a Transfer Leg of the Transfer is uploaded, the Transfer Decision is locked.
        if (selected.locked) return refused(TransferOutcome.Locked)
        val transfer = selected.transfer
        // UC-005 BR-013: The doubt about a Transfer Leg is decided first: until it has a Review Decision, the Operator decides on that Transfer Leg or splits the Transfer, and Split is the only way to separate the two Transfer Legs (A9).
        if (choice != TransferChoice.Split && selected.waitsFor != null) return refused(TransferOutcome.WaitsForReviewDecision(selected.waitsFor.id))
        return when (choice) {
            TransferChoice.Confirm -> when {
                // A8 step 2 of UC-005: a Transfer without a Budget Account on its paying side has nothing to confirm; Confirmed needs both sides (entity model `TRANSFER`).
                transfer.outflowBudgetAccountId == null -> refused(TransferOutcome.NothingToConfirm)
                transfer.decision == TransferDecision.Confirmed -> refused(TransferOutcome.Unchanged(transfer.decision))
                // Step 5 of UC-005: confirming removes the Guessed Transfer flag (entity model `TRANSFER`).
                else -> written(selected, transfer.copy(decision = TransferDecision.Confirmed, guessed = false, decidedAt = now), selected.legs, emptyList(), null, data, windows)
            }
            // UC-005 BR-010: A Guessed Transfer that the Operator splits or holds back keeps its flag.
            TransferChoice.Split -> if (transfer.decision == TransferDecision.Split) {
                refused(TransferOutcome.Unchanged(transfer.decision))
            } else {
                written(selected, transfer.copy(decision = TransferDecision.Split, decidedAt = now), selected.legs, emptyList(), null, data, windows)
            }
            TransferChoice.Hold -> if (transfer.decision == TransferDecision.Held) {
                refused(TransferOutcome.Unchanged(transfer.decision))
            } else {
                written(selected, transfer.copy(decision = TransferDecision.Held, decidedAt = now), selected.legs, emptyList(), null, data, windows)
            }
            is TransferChoice.ChooseBudgetAccount -> choose(selected, choice, data, windows, now)
        }
    }

    /** A candidate for the other side with the stored Transfer it is a Transfer Leg of, if any. */
    private data class Candidate(val transaction: StoredBankTransaction, val transfer: StoredTransfer?, val uploaded: Boolean, val transferLocked: Boolean) {
        /** BR-016: it can be paired with: not uploaded, and in no Transfer or in one without a Transfer Decision none of whose Transfer Legs has been uploaded. */
        val free: Boolean get() = !uploaded && (transfer == null || (transfer.decision == TransferDecision.Proposed && !transferLocked))
    }

    /** A3 steps 3-4, A10, BR-016: what choosing the Budget Account of [choice] as the other side of [selected] stores. */
    private fun choose(
        selected: SelectedTransfer,
        choice: TransferChoice.ChooseBudgetAccount,
        data: ComparisonData,
        windows: ComparisonWindows,
        now: Instant,
    ): Pair<TransferDecisionWrite?, RuledTransferOutcome> {
        // UC-005 BR-014: A Budget Account that Slussen does not fetch is never a choice in A3.
        if (choice.budgetAccountId !in selected.offered) return null to RuledTransferOutcome(TransferOutcome.BudgetAccountNotOffered)
        val stays = selected.stays
        val ofChosen = data.bankAccounts.first { !it.retired && it.mapping?.budgetAccountId == choice.budgetAccountId }
        // UC-005 BR-016: The Bank Transactions that could be the other side are those of the Bank Account mapped to the chosen Budget Account that have the opposite amount and are dated within the transfer pairing days of the Transfer Leg that stays.
        val candidates = data.bankTransactions
            .filter { it.bankAccountId == ofChosen.id && it.status != null && it.id != stays.id }
            .filter { TransferRules.couldBeOtherSide(stays, it, windows.transferPairingDays) }
            .map { candidate ->
                val transfer = data.transfers.firstOrNull { it.id == candidate.transferId }
                val legs = data.bankTransactions.filter { transfer != null && it.transferId == transfer.id }
                Candidate(candidate, transfer, TransferRules.uploaded(candidate.id, data.sentRecords), transfer != null && TransferRules.locked(transfer, legs, data.sentRecords))
            }
        // UC-005 BR-016: Among several of the same kind Slussen takes the one with the nearest booked date, then the one it received first.
        val nearestFirst = compareBy<Candidate>(
            { abs(ChronoUnit.DAYS.between(stays.bookedDate, it.transaction.bookedDate)) },
            { it.transaction.firstSeenAt },
            { it.transaction.id },
        )
        val own = candidates.filter { selected.isStored && it.transfer?.id == selected.transfer.id }
        val others = candidates - own.toSet()
        // UC-005 BR-016: Slussen pairs first with the Transfer's own other Transfer Leg; then with one that has not been uploaded and belongs to no Transfer; then with one it takes from a Transfer that has no Transfer Decision and none of whose Transfer Legs has been uploaded.
        val pairsWith = own.firstOrNull { !it.uploaded }
            ?: others.filter { it.free && it.transfer == null }.sortedWith(nearestFirst).firstOrNull()
            ?: others.filter { it.free }.sortedWith(nearestFirst).firstOrNull()
        // UC-005 BR-016: When there is at least one and Slussen can pair with none of them, it refuses the choice: a Lonely Transfer towards the chosen Budget Account would write there what was already written or what that other Transfer writes.
        if (pairsWith == null && candidates.isNotEmpty()) {
            return null to RuledTransferOutcome(TransferOutcome.OtherSideTaken(candidates.sortedWith(nearestFirst).map { taken(it, selected.budget, data) }))
        }
        val ownSide = selected.staysIn.mapping!!.budgetAccountId
        val staysIsOutflow = stays.amount.thousandths < 0
        val after = selected.transfer.copy(
            outflowBudgetAccountId = if (staysIsOutflow) ownSide else choice.budgetAccountId,
            inflowBudgetAccountId = if (staysIsOutflow) choice.budgetAccountId else ownSide,
            type = TransferDetection.typeOf(selected.staysIn, ofChosen),
            // A3 step 4 of UC-005: choosing another Budget Account removes the Guessed Transfer flag.
            guessed = false,
            decision = TransferDecision.BudgetAccountChosen,
            decidedAt = now,
        )
        // UC-005 BR-016: Only when no Bank Transaction could be the other side does the Transfer become a Lonely Transfer towards the chosen Budget Account.
        val legsAfter = listOfNotNull(stays, pairsWith?.transaction).sortedBy { it.id }
        // UC-005 BR-016: A Transfer that loses a Transfer Leg this way is dissolved.
        val dissolved = listOfNotNull(pairsWith?.transfer?.id?.takeIf { it != selected.transfer.id || !selected.isStored })
        // UC-005 BR-016: Only the Operator's answer sets a Funded By or a Transfer Partner, for the Bank Account of the Transfer Leg that stays.
        val kept = KeptOtherSide(selected.staysIn.id, selected.keptAs, choice.budgetAccountId).takeIf { choice.keep }
        if (after == selected.transfer.copy(decidedAt = now) && legsAfter == selected.legs && (kept == null || keptNow(selected.staysIn, kept.kind) == kept.budgetAccountId)) {
            return null to RuledTransferOutcome(TransferOutcome.Unchanged(after.decision))
        }
        return written(selected, after, legsAfter, dissolved, kept, data, windows)
    }

    private fun keptNow(bankAccount: StoredBankAccount, kind: OtherSideKind): Long? =
        if (kind == OtherSideKind.FundedBy) bankAccount.fundedByBudgetAccountId else bankAccount.transferPartnerBudgetAccountId

    /** A10 step 1: why [candidate] cannot be paired with, naming the other Transfer it belongs to. */
    private fun taken(candidate: Candidate, budget: StoredBudget, data: ComparisonData): TakenOtherSide {
        val names = budget.budgetAccounts.associate { it.id to it.name }
        return TakenOtherSide(
            storedBankTransaction = candidate.transaction.id,
            bookedDate = candidate.transaction.bookedDate,
            amount = candidate.transaction.amount,
            uploaded = candidate.uploaded,
            transfer = candidate.transfer?.let { transfer ->
                TransferNamed(
                    outflowBudgetAccountName = transfer.outflowBudgetAccountId?.let { names[it].orEmpty() },
                    inflowBudgetAccountName = names[transfer.inflowBudgetAccountId].orEmpty(),
                    amount = transfer.amount,
                    dates = data.bankTransactions.filter { it.transferId == transfer.id }.map { it.bookedDate }.sorted(),
                    decision = transfer.decision,
                    locked = candidate.transferLocked,
                )
            },
        )
    }

    /** [data] as it is stored once the decision is: [after] with its [legsAfter], without the [dissolved] Transfers, and with what is [kept]. */
    private fun dataAfter(
        data: ComparisonData,
        after: StoredTransfer,
        legsAfter: List<StoredBankTransaction>,
        dissolved: List<Long>,
        kept: KeptOtherSide?,
    ): ComparisonData {
        val legIds = legsAfter.map { it.id }.toSet()
        return data.copy(
            transfers = data.transfers.filterNot { it.id == after.id || it.id in dissolved } + after,
            bankTransactions = data.bankTransactions.map { transaction ->
                when {
                    transaction.id in legIds -> transaction.copy(transferId = after.id)
                    transaction.transferId != null && (transaction.transferId == after.id || transaction.transferId in dissolved) -> transaction.copy(transferId = null)
                    else -> transaction
                }
            },
            bankAccounts = data.bankAccounts.map { account ->
                when {
                    kept == null || account.id != kept.bankAccountId -> account
                    kept.kind == OtherSideKind.FundedBy -> account.copy(fundedByBudgetAccountId = kept.budgetAccountId)
                    else -> account.copy(transferPartnerBudgetAccountId = kept.budgetAccountId)
                }
            },
        )
    }

    // UC-005 BR-010: Each decision gives the Transfer Legs of its Transfer their Transaction Status by the rules of the Comparison.
    /**
     * The write of [after] in place of the selected Transfer, with the statuses the Comparison's own rules give its Transfer
     * Legs once it is stored. A Transfer whose own sides are no longer valid (BR-015) is not decided on: nothing is stored.
     */
    private fun written(
        selected: SelectedTransfer,
        after: StoredTransfer,
        legsAfter: List<StoredBankTransaction>,
        dissolved: List<Long>,
        kept: KeptOtherSide?,
        data: ComparisonData,
        windows: ComparisonWindows,
    ): Pair<TransferDecisionWrite?, RuledTransferOutcome> {
        val conclusions = ComparisonRules.conclude(dataAfter(data, after, legsAfter, dissolved, kept), windows)
        // UC-005 BR-015: A Transfer whose own sides are no longer valid keeps no Transfer Decision: the next Comparison dissolves it and detects again.
        conclusions.dropped.firstOrNull { it.transfer.id == after.id }?.let { return null to RuledTransferOutcome(TransferOutcome.NoLongerValid(it.reason)) }
        val legIds = legsAfter.map { it.id }
        val facts = TransferDecisionFacts(
            before = selected.transfer,
            legsBefore = selected.legs,
            after = after,
            legsAfter = legsAfter,
            kept = kept?.takeIf { keptNow(selected.staysIn, it.kind) != it.budgetAccountId },
            keptBefore = kept?.let { keptNow(selected.staysIn, it.kind) },
        )
        val write = TransferDecisionWrite(after, legIds, dissolved, facts.kept, conclusions.statuses.filterKeys { it in legIds })
        return write to RuledTransferOutcome(TransferOutcome.Stored(after.decision, lonely = legIds.size == 1, kept = facts.kept?.kind, detectedAgain = true), facts)
    }
}
