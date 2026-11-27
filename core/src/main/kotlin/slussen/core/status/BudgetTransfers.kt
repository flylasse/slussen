package slussen.core.status

import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.comparison.EligibleTransactions
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.ConnectionStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferType
import slussen.core.transfer.DetectedTransfer
import slussen.core.transfer.GuessReason
import slussen.core.transfer.TransferDetection
import slussen.core.transfer.TransfersDetected
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.LocalDate
import kotlin.math.abs

/** One Transfer Leg, or a Bank Transaction that could have been one, as the Transfers of a Budget show it. */
data class LegShown(
    val storedBankTransaction: Long,
    val bankAccountId: Long,
    val bankAccountName: String,
    val budgetAccountName: String,
    val bookedDate: LocalDate,
    val amount: Amount,
    val payee: String?,
    val memo: String?,
    /** Its stored Transaction Status; null while it is not compared yet (S-T-08). */
    val status: TransactionStatus?,
    /** Its Bank Account was retired since the latest Comparison, which left it here until the next one (UC-009 A4). */
    val retired: Boolean = false,
)

/** Why a Guessed Transfer is guessed (UC-005 BR-002, BR-003, A8), from the Transfer detection's own rules. */
sealed interface WhyGuessed {
    /** BR-003: a Transfer Leg could pair with more than one Bank Transaction; [others] are those it was not proposed with. */
    data class OtherCandidates(val others: List<LegShown>) : WhyGuessed

    /** BR-003: the paying Bank Account is the Funded By of more than one credit card; [budgetAccountNames] are the cards not proposed. */
    data class OtherCards(val budgetAccountNames: List<String>) : WhyGuessed

    /**
     * BR-002, A8: a Card Payment to [cardName], which has no Funded By that applies; [payingBankAccounts] is the number
     * of the Budget's Bank Accounts that are not credit cards when Slussen proposes no paying side for that reason, else
     * null. [fundedByNotApplying] names the Budget Account of the Funded By the card has when one is set that no longer
     * applies (BR-014), which is never called missing (A8 step 1); null when the card has none.
     */
    data class NoFundedBy(val cardName: String, val payingBankAccounts: Int?, val fundedByNotApplying: String? = null) : WhyGuessed

    /** The latest Comparison stored it as guessed, and the rules on the stored data today no longer say why. */
    data object AsStored : WhyGuessed
}

/** Whether a Transfer is in the next Upload Plan as Slussen proposes it, and if not why (UC-003 BR-014, UC-005 BR-010, BR-013). */
sealed interface TransferEligibility {
    /** Eligible, written through [writtenThrough] and counted as its one Eligible Transaction (UC-006 BR-009). */
    data class Eligible(val writtenThrough: LegShown) : TransferEligibility

    /**
     * BR-013, A9: [leg] is Needs Review or Possible Import By Other Path, for the reason [why] (the same the Bank Account
     * screen gives it); the whole Transfer waits for its Review Decision.
     */
    data class WaitsForReviewDecision(val leg: LegShown, val why: StatusReason) : TransferEligibility

    /**
     * BR-010: an earlier rule of the Comparison gave [leg] its status (Too Old, Before Start Date, Already In Budget App,
     * ...), or it is not compared yet, so the whole Transfer is in no Upload Plan, from either side.
     */
    data class KeptOutBy(val leg: LegShown) : TransferEligibility

    /** BR-010: a Guessed Transfer is not eligible until the Operator confirms it or chooses another Budget Account. */
    data object Guessed : TransferEligibility

    /**
     * BR-005: a Lonely Transfer whose grace period ended since the latest Comparison: the next Comparison, which runs
     * before an Upload Plan is prepared, makes its Transfer Leg an ordinary Bank Transaction, so it is never sent as a Transfer.
     */
    data object GraceEnded : TransferEligibility

    /** UC-003 BR-014: not eligible for none of these reasons: its Bank Account is blocked or a Retired Account. */
    data object NotEligible : TransferEligibility

    /** UC-005 A1, BR-010: the Operator split it: each Transfer Leg is an ordinary Bank Transaction, eligible or not on its own. */
    data object Split : TransferEligibility

    /** UC-005 A2, BR-007: the Operator holds it back: neither Transfer Leg is in an Upload Plan, as a Transfer or as an ordinary transaction. */
    data object HeldBack : TransferEligibility

    /** UC-005 BR-015: the Operator decided on it and the Bank Account of one of its sides is a Retired Account: it is in no Upload Plan until that Bank Account is restored. */
    data object RetiredSide : TransferEligibility
}

/**
 * UC-005 A4, BR-005: the other side a Lonely Transfer waits for: [amount], signed as its Bank Transaction would be, in [budgetAccountName], by [by], the last day
 * of the Lonely Transfer grace period; [graceEnded] when that day has passed since the latest Comparison stored it.
 */
data class ExpectedOtherSide(val budgetAccountName: String, val amount: Amount, val by: LocalDate, val graceEnded: Boolean)

/**
 * One Transfer of a Budget (UC-005 step 1): its two Budget Accounts, amount and Transfer Legs, whether it is a Guessed
 * Transfer and why, whether it is eligible and why not, and for a Lonely Transfer the other side it waits for.
 */
data class TransferItem(
    val type: TransferType,
    /** The amount moved, positive, in the Budget's currency. */
    val amount: Amount,
    /** Null when Slussen proposes no paying side (A8, O-T-10). */
    val outflowBudgetAccountName: String?,
    val inflowBudgetAccountName: String,
    /** Null while the outflow side has not arrived, or is not known. */
    val outflowLeg: LegShown?,
    /** Null while the inflow side has not arrived. */
    val inflowLeg: LegShown?,
    /** False for a Guessed Transfer without a proposed other side, which is listed from its Bank Transaction (O-T-10). */
    val stored: Boolean,
    val guessed: Boolean,
    /** Empty when it is not guessed. */
    val whyGuessed: List<WhyGuessed>,
    val eligibility: TransferEligibility,
    /** Null unless it is a Lonely Transfer whose other side is known. */
    val expected: ExpectedOtherSide?,
    /** Its Transfer Decision, Proposed while the Operator has made none (UC-005 steps 4-5, A1-A3). */
    val decision: TransferDecision = TransferDecision.Proposed,
) {
    val legs: List<LegShown> get() = listOfNotNull(outflowLeg, inflowLeg)

    val bothArrived: Boolean get() = outflowLeg != null && inflowLeg != null
}

/**
 * UC-005 A5 (O-T-11): a Bank Transaction that would be a Lonely Transfer towards [otherBudgetAccountName] but whose other
 * side did not arrive within the grace period of [graceDays], which ended on [lastDay]; [proposed] when that other side
 * was only proposed (A8).
 */
data class NoLongerATransfer(val leg: LegShown, val otherBudgetAccountName: String, val proposed: Boolean, val graceDays: Int, val lastDay: LocalDate)

/** The Transfers of one Budget (UC-005 step 1, A4, A5, A8, BR-010; `docs/mockups/transfers.html`). */
data class BudgetTransfersView(
    val budgetId: Long,
    val budgetName: String,
    val currency: CurrencyCode,
    val budgetAppName: String,
    /** When its statuses were given (UC-003 step 7, A1) and the Budget App's credentials. */
    val summary: BudgetSummary,
    /** Guessed Transfers first, then newest first. */
    val transfers: List<TransferItem>,
    /** A5: newest first. */
    val noLongerTransfers: List<NoLongerATransfer>,
    /** The transfer pairing days (an Operator Setting, UC-010). */
    val pairingDays: Int,
    /** The Lonely Transfer grace period in days (an Operator Setting, UC-010). */
    val graceDays: Int,
    /** The dates of the compared Bank Transactions of the Budget, oldest to newest; null when none is compared. */
    val covers: ClosedRange<LocalDate>?,
) {
    // UC-005 BR-010: The number of Guessed Transfers of a Budget counts only those that have no Transfer Decision.
    /** Step 1: the number of Guessed Transfers of the Budget. */
    val guessedTransfers: Int get() = transfers.count { it.guessed && it.decision == TransferDecision.Proposed }

    /**
     * UX-27: the Bank Transactions of these Transfers with the status Guessed Transfer that the Budget counts among those
     * waiting for a decision (UC-008 BR-003): every one but those of a Bank Account retired since, which no count counts.
     */
    val guessedTransferLegs: Int get() = transfers.flatMap { it.legs }.count { it.status == TransactionStatus.GuessedTransfer && !it.retired }
}

/**
 * The read model of the Transfers of one Budget (UC-005 step 1), `:core` `status`: built only from stored data (UC-008
 * BR-001), the Transfers the latest Comparison stored and the Guessed Transfers it gave without a stored Transfer
 * (O-T-10), each with why it is guessed and whether it is eligible. The reasons are derived with the Comparison's and the
 * Transfer detection's own rules ([ComparisonRules.transfersOf], [TransferDetection], [EligibleTransactions]) on the data
 * a Comparison concludes from, never stored and never a second implementation.
 */
class BudgetTransfers(
    private val status: ImportStatus,
    private val connections: ConnectionStore,
    private val comparisons: ComparisonStore,
    private val settings: OperatorSettingsStore,
    private val matchingDateWindowDays: Int,
    private val clock: Clock,
) {
    /** What the Transfers of one Budget are derived from: the stored data a Comparison concludes from, and its windows. */
    private class Ground(
        val budget: StoredBudget,
        val data: ComparisonData,
        val windows: ComparisonWindows,
        val detected: TransfersDetected,
        /** The names of the Budget Accounts of every stored Budget: a Funded By that no longer applies can be one of another Budget (UC-005 BR-014). */
        val everyBudgetAccountName: Map<Long, String>,
    ) {
        val names: Map<Long, String> = budget.budgetAccounts.associate { it.id to it.name }
        private val bankAccountsById: Map<Long, StoredBankAccount> = data.bankAccounts.associateBy { it.id }
        private val accountIds = budget.budgetAccounts.map { it.id }.toSet()

        /** UC-003 BR-014: the Bank Accounts of the Budget that are compared: mapped into it and not Retired Accounts. */
        val compared: List<StoredBankAccount> = data.bankAccounts.filter { !it.retired && it.mapping?.budgetAccountId in accountIds }
        private val comparedIds = compared.map { it.id }.toSet()

        /** Its compared Bank Transactions. */
        val transactions: List<StoredBankTransaction> = data.bankTransactions.filter { it.bankAccountId in comparedIds }
        val transferLegs: List<StoredBankTransaction> = data.bankTransactions.filter { it.transferId != null }

        /** UC-005 BR-015: the Transfers the Operator decided on that have a Retired Account on a side. */
        val withRetiredSide: Set<Long> = EligibleTransactions.withARetiredSide(data.transfers, transferLegs, data.bankAccounts)

        fun bankAccountOf(transaction: StoredBankTransaction): StoredBankAccount = bankAccountsById.getValue(transaction.bankAccountId)

        fun leg(transaction: StoredBankTransaction): LegShown {
            val bankAccount = bankAccountOf(transaction)
            return LegShown(
                storedBankTransaction = transaction.id,
                bankAccountId = bankAccount.id,
                bankAccountName = bankAccount.name,
                budgetAccountName = names[bankAccount.mapping?.budgetAccountId].orEmpty(),
                bookedDate = transaction.bookedDate,
                amount = transaction.amount,
                payee = transaction.payee,
                memo = transaction.memo,
                status = transaction.status,
                retired = bankAccount.retired,
            )
        }

        /** A Budget App Transaction of the stored copy, as a reason names it. */
        fun entry(transaction: StoredBudgetAppTransaction) =
            BudgetAppEntry(transaction.transactionDate, transaction.amount, transaction.payee, names[transaction.budgetAccountId].orEmpty())
    }

    // UC-008 BR-001: The Transfers are shown only from stored data; nothing contacts a Source, a Bank or the Budget App.
    /** The Transfers of the served Budget [budgetId]. */
    suspend fun of(budgetId: Long): BudgetTransfersView {
        val budgets = connections.budgets()
        val budget = budgets.single { it.id == budgetId && it.served }
        val summary = status.summaryOf(budgetId) ?: error("Budget $budgetId is not served")
        val current = settings.current()
        val windows = ComparisonWindows.of(current, matchingDateWindowDays, LocalDate.ofInstant(clock.instant(), clock.zone))
        val data = comparisons.comparisonDataOf(setOf(budgetId))
        val ground = Ground(
            budget, data, windows, ComparisonRules.transfersOf(budgetId, data, windows),
            budgets.flatMap { it.budgetAccounts }.associate { it.id to it.name },
        )
        val storedTransfers = data.transfers.filter { stored ->
            stored.outflowBudgetAccountId in ground.names || stored.inflowBudgetAccountId in ground.names
        }.filter { stored -> data.bankTransactions.any { it.transferId == stored.id } }
        val items = storedTransfers.map { itemOf(it, ground) } + withoutProposal(ground)
        val dated = ground.transactions.filter { it.status != null }.map { it.bookedDate }
        return BudgetTransfersView(
            budgetId = budget.id,
            budgetName = budget.name,
            currency = budget.currency,
            budgetAppName = status.budgetAppName,
            summary = summary,
            transfers = items.sortedWith(
                compareBy<TransferItem> { !it.guessed }
                    .thenByDescending { item -> item.legs.maxOf { it.bookedDate } }
                    .thenBy { item -> item.legs.minOf { it.storedBankTransaction } },
            ),
            noLongerTransfers = noLongerTransfers(ground),
            pairingDays = current.transferPairingDays,
            graceDays = current.lonelyTransferGraceDays,
            covers = if (dated.isEmpty()) null else dated.min()..dated.max(),
        )
    }

    /** One Transfer the latest Comparison stored, with its Transfer Legs as stored. */
    private fun itemOf(stored: StoredTransfer, ground: Ground): TransferItem {
        // Every stored Transfer Leg, also one of a Bank Account retired since, so that a pair is never shown as a Lonely Transfer.
        val legs = ground.data.bankTransactions.filter { it.transferId == stored.id }
        val outflow = legs.firstOrNull { it.amount.thousandths < 0 }
        val inflow = legs.firstOrNull { it.amount.thousandths >= 0 }
        val graceDays = ground.windows.lonelyTransferGraceDays
        // UC-005 BR-005: The grace period ends only a Lonely Transfer that has no Transfer Decision; one the Operator decided on stays a Lonely Transfer.
        val single = legs.singleOrNull()?.takeIf { stored.decision == TransferDecision.Proposed }
        return TransferItem(
            decision = stored.decision,
            type = stored.type,
            amount = stored.amount,
            outflowBudgetAccountName = stored.outflowBudgetAccountId?.let { ground.names[it].orEmpty() },
            inflowBudgetAccountName = ground.names[stored.inflowBudgetAccountId].orEmpty(),
            outflowLeg = outflow?.let(ground::leg),
            inflowLeg = inflow?.let(ground::leg),
            stored = true,
            guessed = stored.guessed,
            whyGuessed = if (stored.guessed) whyGuessed(stored, legs, ground) else emptyList(),
            // UC-005 BR-005: A Lonely Transfer whose other Transfer Leg has not arrived within the grace period becomes an ordinary Bank Transaction, so it is never sent as a Transfer.
            eligibility = if (single != null && TransferDetection.graceEnded(single, graceDays, ground.windows.today)) {
                TransferEligibility.GraceEnded
            } else {
                eligibilityOf(legs, ground, stored)
            },
            // A4 of UC-005: a Lonely Transfer shows the Budget Account of its expected other side and the date by which it must arrive.
            expected = single?.let { arrived ->
                ExpectedOtherSide(
                    budgetAccountName = ground.names[if (arrived.amount.thousandths < 0) stored.inflowBudgetAccountId else stored.outflowBudgetAccountId].orEmpty(),
                    amount = Amount(-arrived.amount.thousandths),
                    by = TransferDetection.lastDayOfGrace(arrived, graceDays),
                    // UC-005 BR-005: The grace period of a Lonely Transfer counts from the booked date of its arrived Transfer Leg, the last of its days included.
                    graceEnded = TransferDetection.graceEnded(arrived, graceDays, ground.windows.today),
                )
            },
        )
    }

    /**
     * Why the stored Guessed Transfer [stored] with its [legs] is guessed: what the detection gives for the same Transfer
     * Legs on the same stored data, or [WhyGuessed.AsStored] when it no longer gives that Transfer as guessed.
     */
    private fun whyGuessed(stored: StoredTransfer, legs: List<StoredBankTransaction>, ground: Ground): List<WhyGuessed> {
        val ids = legs.map { it.id }.toSet()
        val detected: DetectedTransfer = ground.detected.transfers.firstOrNull { detected ->
            detected.legs.map { it.id }.toSet() == ids &&
                detected.outflowBudgetAccountId == stored.outflowBudgetAccountId &&
                detected.inflowBudgetAccountId == stored.inflowBudgetAccountId
        }?.takeIf { it.guessed } ?: return listOf(WhyGuessed.AsStored)
        val byId = ground.data.bankTransactions.associateBy { it.id }
        return detected.whyGuessed.map { reason ->
            when (reason) {
                is GuessReason.OtherPairings -> WhyGuessed.OtherCandidates(reason.others.map { ground.leg(byId[it.id] ?: it) })
                is GuessReason.OtherCards -> WhyGuessed.OtherCards(reason.otherBudgetAccountIds.map { ground.names[it].orEmpty() })
                is GuessReason.NoFundedBy -> {
                    val card = ground.data.bankAccounts.firstOrNull { it.id == reason.cardBankAccountId }
                    WhyGuessed.NoFundedBy(card?.name.orEmpty(), payingBankAccounts = null, fundedByNotApplying = fundedByNotApplying(card, ground))
                }
            }
        }
    }

    /**
     * UC-005 A8 step 1: the name of the Budget Account of the Funded By that [card] has, for a Guessed Transfer the
     * detection gave because no Funded By of the card applies: one that is set then no longer applies (BR-014) and is
     * named, never called missing. Null when the card has none.
     */
    private fun fundedByNotApplying(card: StoredBankAccount?, ground: Ground): String? =
        card?.fundedByBudgetAccountId?.let { ground.everyBudgetAccountName[it].orEmpty() }

    /**
     * UC-005 A8, O-T-10: the Guessed Transfers for which Slussen proposes no Budget Account as the other side, which have
     * no stored Transfer and are listed from their Bank Transaction: one stored with the status Guessed Transfer, and one
     * an earlier rule gave its status (BR-010) that the detection gives as such on the same stored data.
     */
    private fun withoutProposal(ground: Ground): List<TransferItem> = ground.transactions
        .filter { it.transferId == null && it.status != null }
        .filter { transaction ->
            transaction.status == TransactionStatus.GuessedTransfer ||
                (transaction.id in ground.detected.withoutProposal && ground.detected.statuses[transaction.id] == transaction.status)
        }
        .map { transaction ->
            val card = ground.bankAccountOf(transaction)
            val reason = ground.detected.withoutProposal[transaction.id]
            TransferItem(
                type = TransferType.CardPayment,
                amount = Amount(abs(transaction.amount.thousandths)),
                outflowBudgetAccountName = null,
                inflowBudgetAccountName = ground.names[card.mapping?.budgetAccountId].orEmpty(),
                outflowLeg = null,
                inflowLeg = ground.leg(transaction),
                stored = false,
                guessed = true,
                whyGuessed = listOf(reason?.let { WhyGuessed.NoFundedBy(card.name, it.payingBankAccounts, fundedByNotApplying(card, ground)) } ?: WhyGuessed.AsStored),
                eligibility = eligibilityOf(listOf(transaction), ground),
                expected = null,
            )
        }

    /**
     * Whether the Transfer with the stored Transfer Legs [legs] is eligible, by the rule the counts use (UC-003 BR-014):
     * the Transfer Leg that keeps it out of every Upload Plan, or the one it is written through (UC-006 BR-009).
     */
    private fun eligibilityOf(legs: List<StoredBankTransaction>, ground: Ground, stored: StoredTransfer? = null): TransferEligibility {
        // UC-005 BR-010: After Split, each Transfer Leg gets its status as an ordinary Bank Transaction.
        if (stored?.decision == TransferDecision.Split) return TransferEligibility.Split
        // UC-005 BR-015: While the Bank Account of one of its sides is a Retired Account, a Transfer the Operator decided on is in no Upload Plan, from either side.
        if (stored != null && stored.id in ground.withRetiredSide) return TransferEligibility.RetiredSide
        val writing = legs.firstOrNull { leg -> EligibleTransactions.writesItsSide(leg, legs.filter { it.id != leg.id }) } ?: legs.first()
        val keptOut = if (writing.transferId == null) writing.takeIf { it.status != TransactionStatus.TransferLeg } else {
            EligibleTransactions.keptOutBy(writing, ground.transferLegs)
        }
        if (keptOut == null) return eligibleOrNot(writing, ground)
        return when (keptOut.status) {
            // UC-005 BR-013: A Transfer with a Transfer Leg that is Needs Review or Possible Import By Other Path waits for its Review Decision, from either side.
            TransactionStatus.NeedsReview, TransactionStatus.PossibleImportByOtherPath -> {
                // A9 step 1 of UC-005: with the reason the Transfer Leg is doubtful, derived as the Bank Account screen derives it.
                val bankAccount = ground.bankAccountOf(keptOut)
                val why = BankAccountTransactions.doubtOf(
                    keptOut, keptOut.status!!, ground.data.bankTransactions.filter { it.bankAccountId == bankAccount.id },
                    ground.data.budgetAppTransactions.filter { it.budgetAccountId == bankAccount.mapping?.budgetAccountId }, ground.windows, ground::entry,
                )
                TransferEligibility.WaitsForReviewDecision(ground.leg(keptOut), why)
            }
            // UC-005 BR-010: The Transfer Legs of a Guessed Transfer are not eligible until the Operator confirms the Transfer or chooses another Budget Account.
            TransactionStatus.GuessedTransfer -> TransferEligibility.Guessed
            // UC-005 BR-007: A Transfer the Operator holds back keeps both Transfer Legs out of every Upload Plan.
            TransactionStatus.HeldTransfer -> TransferEligibility.HeldBack
            // UC-005 BR-010: While a Transfer has a Transfer Leg to which an earlier rule gave its status, the whole Transfer is in no Upload Plan.
            else -> TransferEligibility.KeptOutBy(ground.leg(keptOut))
        }
    }

    private fun eligibleOrNot(writing: StoredBankTransaction, ground: Ground): TransferEligibility =
        if (EligibleTransactions.isEligible(writing, ground.bankAccountOf(writing), ground.budget, ground.transferLegs, ground.withRetiredSide)) {
            TransferEligibility.Eligible(ground.leg(writing))
        } else {
            TransferEligibility.NotEligible
        }

    /**
     * UC-005 A5 (O-T-11): the New Bank Transactions in no Transfer that would be a Lonely Transfer but whose grace period
     * ended, by the detection's own rule on the same stored data; newest first.
     */
    private fun noLongerTransfers(ground: Ground): List<NoLongerATransfer> {
        val graceDays = ground.windows.lonelyTransferGraceDays
        return ground.transactions
            .filter { it.status == TransactionStatus.New && it.transferId == null }
            .mapNotNull { transaction ->
                // UC-005 BR-005: A Lonely Transfer whose other Transfer Leg has not arrived within the grace period becomes an ordinary Bank Transaction.
                val otherSide = TransferDetection.otherSideAfterGrace(
                    transaction, ground.bankAccountOf(transaction), ground.budget, ground.data.bankAccounts, ground.data.transferDescriptions,
                    graceDays, ground.windows.today,
                ) ?: return@mapNotNull null
                NoLongerATransfer(
                    leg = ground.leg(transaction),
                    otherBudgetAccountName = ground.names[otherSide.budgetAccountId].orEmpty(),
                    proposed = otherSide.guessed,
                    graceDays = graceDays,
                    lastDay = TransferDetection.lastDayOfGrace(transaction, graceDays),
                )
            }
            .sortedWith(compareByDescending<NoLongerATransfer> { it.leg.bookedDate }.thenByDescending { it.leg.storedBankTransaction })
    }
}
