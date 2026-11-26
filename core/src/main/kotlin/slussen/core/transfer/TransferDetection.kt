package slussen.core.transfer

import slussen.core.port.BankAccountType
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferToStore
import slussen.core.port.TransferType
import slussen.pluginapi.value.Amount
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * A compared Bank Transaction of [bankAccount], which can be a Transfer Leg whatever rule of UC-003 BR-001 gave it its
 * Transaction Status (UC-005 BR-002). [earlier] is the status a rule standing before a Transfer Leg in that order gave
 * it (Too Old, Before Start Date, Needs Review, ...), which it keeps as a Transfer Leg (UC-005 BR-010, BR-013), and
 * null when none did.
 */
data class TransferCandidate(
    val transaction: StoredBankTransaction,
    val bankAccount: StoredBankAccount,
    val earlier: TransactionStatus?,
)

/** Why detection could not settle a Transfer for certain, so that it is a Guessed Transfer (UC-005 BR-002, BR-003, A8). */
sealed interface GuessReason {
    /**
     * BR-003: a Transfer Leg of the pair could pair with more than one Bank Transaction; [others] are the Bank
     * Transactions it was not proposed with, each of which could have taken the place of the Transfer Leg of its own sign.
     */
    data class OtherPairings(val others: List<StoredBankTransaction>) : GuessReason

    /**
     * BR-002, BR-003: an outflow that matches a Card Payment Description, from a Bank Account that is the Funded By of
     * more than one credit card Bank Account; [otherBudgetAccountIds] are the Budget Accounts of the cards not proposed.
     */
    data class OtherCards(val otherBudgetAccountIds: List<Long>) : GuessReason

    /** BR-002, A8: a Card Payment to the credit card Bank Account [cardBankAccountId], which has no Funded By that applies. */
    data class NoFundedBy(val cardBankAccountId: Long) : GuessReason
}

/** One Transfer detected among the Bank Transactions of one Budget: a pair, or a Lonely Transfer with one Transfer Leg. */
data class DetectedTransfer(
    val outflowBudgetAccountId: Long,
    val inflowBudgetAccountId: Long,
    /** The amount moved, positive, in the Budget's currency. */
    val amount: Amount,
    val type: TransferType,
    /** The Transfer Leg of the outflow side, null while it has not arrived. */
    val outflowLeg: StoredBankTransaction?,
    /** The Transfer Leg of the inflow side, null while it has not arrived. */
    val inflowLeg: StoredBankTransaction?,
    /** Why it is a Guessed Transfer; empty when it is not one. */
    val whyGuessed: List<GuessReason> = emptyList(),
) {
    val legs: List<StoredBankTransaction> get() = listOfNotNull(outflowLeg, inflowLeg)

    val guessed: Boolean get() = whyGuessed.isNotEmpty()
}

/**
 * UC-005 A8: a card's inflow that is a Guessed Transfer for which Slussen proposes no Budget Account, so that no
 * Transfer is stored for it (O-T-10): its card [cardBankAccountId] has no Funded By, and the Budget has
 * [payingBankAccounts] Bank Accounts that are not credit cards, not exactly one.
 */
data class WithoutProposal(val cardBankAccountId: Long, val payingBankAccounts: Int)

/**
 * UC-005 A5: the Budget Account of the other side of a Bank Transaction whose grace period ended; [guessed] when it was
 * only proposed (A8, BR-003).
 */
data class LateOtherSide(val budgetAccountId: Long, val guessed: Boolean)

/** What Transfer detection concluded for one Budget (UC-003 step 4). */
data class TransfersDetected(
    val transfers: List<DetectedTransfer> = emptyList(),
    /**
     * The Transaction Status of each Bank Transaction that follows a Transfer, by its id (UC-005 BR-010, BR-013): the
     * Transfer Legs of the [transfers], and a Guessed Transfer for which Slussen proposes no Budget Account as the other
     * side (A8), which is in no stored Transfer.
     */
    val statuses: Map<Long, TransactionStatus> = emptyMap(),
    /** The Guessed Transfers without a proposed other side (A8, O-T-10), by the id of their one Bank Transaction. */
    val withoutProposal: Map<Long, WithoutProposal> = emptyMap(),
)

/**
 * Transfer detection (UC-003 step 4; UC-005 BR-001..BR-006, BR-010, BR-012..BR-014, A4, A5, A8), `:core` `transfer`
 * (ADR-001 §7): which Bank Transactions of one Budget form a Transfer, and the Transaction Status its Transfer Legs
 * follow. Pure rules on stored data, run inside the Comparison's conclusions (ADR-002 §3 O-5). The Transfer Decisions
 * (UC-005 steps 2-6) come with a later initiative.
 */
object TransferDetection {
    /** An outflow and an inflow that could be the two Transfer Legs of one Transfer (BR-002). */
    private data class Pairing(val outflow: TransferCandidate, val inflow: TransferCandidate, val type: TransferType, val first: Boolean)

    /**
     * The other side Slussen knows for a single Bank Transaction (BR-002); [otherSide] is null when it proposes none (A8),
     * among [payingBankAccounts] Bank Accounts that are not credit cards; [whyGuessed] is empty when it is not guessed.
     */
    private data class OneSided(
        val otherSide: Long?,
        val type: TransferType,
        val whyGuessed: List<GuessReason>,
        val payingBankAccounts: Int = 0,
    ) {
        val guessed: Boolean get() = whyGuessed.isNotEmpty()
    }

    private val TransferCandidate.id: Long get() = transaction.id

    private val TransferCandidate.budgetAccountId: Long get() = bankAccount.mapping!!.budgetAccountId

    private val TransferCandidate.outflow: Boolean get() = transaction.amount.thousandths < 0

    private val StoredBankAccount.creditCard: Boolean get() = type == BankAccountType.CreditCard

    /**
     * The Transfers among [candidates], the Bank Transactions of the Bank Accounts [bankAccounts] mapped into [budget],
     * judged by the stored [descriptions], the transfer pairing days [pairingDays] and the Lonely Transfer grace period
     * [graceDays] (Operator Settings) and [today].
     */
    fun detect(
        budget: StoredBudget,
        bankAccounts: List<StoredBankAccount>,
        candidates: List<TransferCandidate>,
        descriptions: List<StoredTransferDescription>,
        pairingDays: Int,
        graceDays: Int,
        today: LocalDate,
    ): TransfersDetected {
        val sides = sidesOf(budget, bankAccounts)
        val legs = candidates.filter { sides[it.bankAccount.mapping?.budgetAccountId]?.id == it.bankAccount.id }
        val pairs = pairsAmong(legs, sides, descriptions, pairingDays)
        val paired = pairs.flatMap { it.legs }.map { it.id }.toSet()
        val statuses = mutableMapOf<Long, TransactionStatus>()
        val withoutProposal = mutableMapOf<Long, WithoutProposal>()
        val lonely = legs.filterNot { it.id in paired }.mapNotNull { single ->
            val known = oneSided(single, sides, descriptions) ?: return@mapNotNull null
            if (graceEnded(single.transaction, graceDays, today)) return@mapNotNull null
            if (known.otherSide == null) {
                statuses[single.id] = statusOf(single, guessed = true)
                withoutProposal[single.id] = WithoutProposal(single.bankAccount.id, known.payingBankAccounts)
                return@mapNotNull null
            }
            DetectedTransfer(
                outflowBudgetAccountId = if (single.outflow) single.budgetAccountId else known.otherSide,
                inflowBudgetAccountId = if (single.outflow) known.otherSide else single.budgetAccountId,
                amount = Amount(abs(single.transaction.amount.thousandths)),
                type = known.type,
                outflowLeg = single.transaction.takeIf { single.outflow },
                inflowLeg = single.transaction.takeUnless { single.outflow },
                whyGuessed = known.whyGuessed,
            )
        }
        val byId = legs.associateBy { it.id }
        val transfers = pairs + lonely
        transfers.forEach { transfer -> transfer.legs.forEach { statuses[it.id] = statusOf(byId.getValue(it.id), transfer.guessed) } }
        return TransfersDetected(transfers, statuses, withoutProposal)
    }

    // UC-005 BR-005: The grace period counts from the booked date of the Transfer Leg that has arrived, the last of its days included.
    /** The last day on which the other side of a Lonely Transfer whose arrived Transfer Leg is [arrived] can arrive. */
    fun lastDayOfGrace(arrived: StoredBankTransaction, graceDays: Int): LocalDate = arrived.bookedDate.plusDays(graceDays.toLong())

    // UC-005 BR-005: A Lonely Transfer whose other Transfer Leg has not arrived within the grace period becomes an ordinary Bank Transaction.
    /** Whether the grace period of a Lonely Transfer whose arrived Transfer Leg is [arrived] has ended on [today]. */
    fun graceEnded(arrived: StoredBankTransaction, graceDays: Int, today: LocalDate): Boolean =
        today.isAfter(lastDayOfGrace(arrived, graceDays))

    /**
     * UC-005 A5 (O-T-11): the Budget Account of the other side of [single], a Bank Transaction of [bankAccount] in no
     * Transfer, when it would be a Lonely Transfer by BR-002 but its grace period has ended, so that it is an ordinary
     * Bank Transaction; null otherwise. The same rules as [detect], on the same stored data.
     */
    fun otherSideAfterGrace(
        single: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget,
        bankAccounts: List<StoredBankAccount>,
        descriptions: List<StoredTransferDescription>,
        graceDays: Int,
        today: LocalDate,
    ): LateOtherSide? {
        val sides = sidesOf(budget, bankAccounts)
        if (sides[bankAccount.mapping?.budgetAccountId]?.id != bankAccount.id) return null
        if (!graceEnded(single, graceDays, today)) return null
        val known = oneSided(TransferCandidate(single, bankAccount, earlier = null), sides, descriptions) ?: return null
        return known.otherSide?.let { LateOtherSide(it, known.guessed) }
    }

    // UC-005 BR-010: The Transfer Legs of a Transfer have the status Transfer Leg, those of a Guessed Transfer the status Guessed Transfer.
    /** The Transaction Status [leg] follows as a Transfer Leg of a Transfer that is [guessed] or not. */
    private fun statusOf(leg: TransferCandidate, guessed: Boolean): TransactionStatus =
        // UC-005 BR-010: A Transfer Leg to which an earlier rule of the Comparison gave its Transaction Status keeps that status and stays a Transfer Leg of its Transfer.
        leg.earlier ?: if (guessed) TransactionStatus.GuessedTransfer else TransactionStatus.TransferLeg

    // UC-005 BR-014: The other side of a Transfer is always a Budget Account Slussen fetches, never a Manually Kept Account or one only a Retired Account is mapped to.
    /**
     * The Budget Accounts of [budget] that can be a side of a Transfer, each with the Bank Account mapped to it: one that
     * a Bank Account that is not a Retired Account is mapped to, in the Budget's currency.
     */
    private fun sidesOf(budget: StoredBudget, bankAccounts: List<StoredBankAccount>): Map<Long, StoredBankAccount> {
        val fetched = budget.budgetAccounts.filterNot { it.manuallyKept }.map { it.id }.toSet()
        return bankAccounts
            .filter { !it.retired && it.mapping?.budgetAccountId in fetched }
            // UC-005 BR-004: Movements between Budgets or between currencies are never Transfers, and no currency is converted.
            .filter { it.currency == budget.currency }
            .associateBy { it.mapping!!.budgetAccountId }
    }

    /**
     * UC-005 BR-006, BR-014: the Budget Account of the Funded By of [bankAccount], a credit card, or of its Transfer
     * Partner otherwise, when it applies in [budget] with its [bankAccounts]; null when none is set or it no longer applies.
     */
    fun otherSideThatApplies(bankAccount: StoredBankAccount, budget: StoredBudget, bankAccounts: List<StoredBankAccount>): Long? {
        val sides = sidesOf(budget, bankAccounts)
        return if (bankAccount.creditCard) fundedBy(bankAccount, sides) else transferPartner(bankAccount, sides)
    }

    // UC-005 BR-006: The Funded By of a credit card Bank Account is used on the Card Payment path, and only while it applies.
    /** The Funded By of [bankAccount] when it is one of the [sides] (BR-014), or null. */
    private fun fundedBy(bankAccount: StoredBankAccount, sides: Map<Long, StoredBankAccount>): Long? =
        bankAccount.fundedByBudgetAccountId.takeIf { bankAccount.creditCard && applies(it, bankAccount, sides) }

    // UC-005 BR-006: The Transfer Partner of a Bank Account that is not a credit card is used on the Bank Transfer path, and only while it applies.
    /** The Transfer Partner of [bankAccount] when it is one of the [sides] (BR-014), or null. */
    private fun transferPartner(bankAccount: StoredBankAccount, sides: Map<Long, StoredBankAccount>): Long? =
        bankAccount.transferPartnerBudgetAccountId.takeIf { !bankAccount.creditCard && applies(it, bankAccount, sides) }

    // UC-005 BR-014: A Funded By or Transfer Partner whose Budget Account lost its last Account Mapping of a Bank Account that is not a Retired Account is kept but not used to detect Transfers.
    private fun applies(budgetAccountId: Long?, bankAccount: StoredBankAccount, sides: Map<Long, StoredBankAccount>): Boolean =
        budgetAccountId != null && budgetAccountId in sides && budgetAccountId != bankAccount.mapping?.budgetAccountId

    /** A Card Payment when one side is a credit card Bank Account, a move between Bank Accounts otherwise (glossary). */
    private fun typeOf(one: StoredBankAccount, other: StoredBankAccount): TransferType =
        if (one.creditCard || other.creditCard) TransferType.CardPayment else TransferType.BankTransfer

    // UC-005 BR-002: Two Bank Transactions form a Transfer when they have equal and opposite amounts, lie in two Bank Accounts of the same Budget and currency, are dated within the transfer pairing days, and are a Card Payment or a Bank Transfer.
    /** [outflow] and [inflow] as the two Transfer Legs of one Transfer, or null when they cannot be. */
    private fun pairing(
        outflow: TransferCandidate,
        inflow: TransferCandidate,
        sides: Map<Long, StoredBankAccount>,
        descriptions: List<StoredTransferDescription>,
        pairingDays: Int,
    ): Pairing? {
        if (outflow.bankAccount.id == inflow.bankAccount.id) return null
        if (outflow.transaction.amount.thousandths != -inflow.transaction.amount.thousandths) return null
        if (abs(ChronoUnit.DAYS.between(outflow.transaction.bookedDate, inflow.transaction.bookedDate)) > pairingDays) return null
        return when {
            // UC-005 BR-002: A pair in which one Bank Account is a credit card is a Card Payment.
            outflow.bankAccount.creditCard || inflow.bankAccount.creditCard -> Pairing(
                outflow, inflow, TransferType.CardPayment,
                first = fundedBy(inflow.bankAccount, sides) == outflow.budgetAccountId || fundedBy(outflow.bankAccount, sides) == inflow.budgetAccountId,
            )
            // UC-005 BR-002: A pair of two other Bank Accounts is a Bank Transfer only when the payee or memo of at least one side matches a Bank Transfer Description.
            TransferDescriptions.matchesAny(descriptions, TransferDescriptionKind.BankTransfer, outflow.transaction) ||
                TransferDescriptions.matchesAny(descriptions, TransferDescriptionKind.BankTransfer, inflow.transaction) -> Pairing(
                outflow, inflow, TransferType.BankTransfer,
                first = transferPartner(outflow.bankAccount, sides) == inflow.budgetAccountId ||
                    transferPartner(inflow.bankAccount, sides) == outflow.budgetAccountId,
            )
            else -> null
        }
    }

    // UC-005 BR-003: A Bank Transaction belongs to at most one Transfer; with more than one candidate Slussen proposes one pairing as a Guessed Transfer: the one in the Funded By or Transfer Partner first, then the nearest booked date, then the one received first.
    /** The pairs among [legs]: each Bank Transaction in at most one, a pair guessed when one of its sides had another candidate. */
    private fun pairsAmong(
        legs: List<TransferCandidate>,
        sides: Map<Long, StoredBankAccount>,
        descriptions: List<StoredTransferDescription>,
        pairingDays: Int,
    ): List<DetectedTransfer> {
        val outflows = legs.filter { it.outflow }
        val inflows = legs.filterNot { it.outflow }
        val pairings = outflows.flatMap { outflow -> inflows.mapNotNull { inflow -> pairing(outflow, inflow, sides, descriptions, pairingDays) } }
        val proposedFirst = compareBy<Pairing>(
            { !it.first },
            { abs(ChronoUnit.DAYS.between(it.outflow.transaction.bookedDate, it.inflow.transaction.bookedDate)) },
            { minOf(it.outflow.transaction.firstSeenAt, it.inflow.transaction.firstSeenAt) },
            { maxOf(it.outflow.transaction.firstSeenAt, it.inflow.transaction.firstSeenAt) },
            { minOf(it.outflow.id, it.inflow.id) },
            { maxOf(it.outflow.id, it.inflow.id) },
        )
        val taken = mutableSetOf<Long>()
        return pairings.sortedWith(proposedFirst).mapNotNull { pairing ->
            if (pairing.outflow.id in taken || pairing.inflow.id in taken) return@mapNotNull null
            taken += pairing.outflow.id
            taken += pairing.inflow.id
            // UC-005 BR-003: When a Transfer Leg could pair with more than one Bank Transaction, the proposed pairing is a Guessed Transfer.
            val others = pairings.filter { it !== pairing && (it.outflow.id == pairing.outflow.id || it.inflow.id == pairing.inflow.id) }
                .map { if (it.outflow.id == pairing.outflow.id) it.inflow.transaction else it.outflow.transaction }
            DetectedTransfer(
                outflowBudgetAccountId = pairing.outflow.budgetAccountId,
                inflowBudgetAccountId = pairing.inflow.budgetAccountId,
                amount = pairing.inflow.transaction.amount,
                type = pairing.type,
                outflowLeg = pairing.outflow.transaction,
                inflowLeg = pairing.inflow.transaction,
                whyGuessed = listOfNotNull(
                    GuessReason.OtherPairings(others).takeIf { others.isNotEmpty() },
                    GuessReason.NoFundedBy(pairing.inflow.bankAccount.id).takeIf { firstCardPayment(pairing, sides) },
                ),
            )
        }
    }

    // UC-005 BR-002: A Card Payment to a credit card Bank Account without a Funded By is a Guessed Transfer, whether its other side has arrived or not (A8).
    /** Whether [pairing] is a Card Payment into a credit card Bank Account that has no Funded By that applies. */
    private fun firstCardPayment(pairing: Pairing, sides: Map<Long, StoredBankAccount>): Boolean =
        pairing.inflow.bankAccount.creditCard && fundedBy(pairing.inflow.bankAccount, sides) == null

    // UC-005 BR-002: A single Bank Transaction whose other side has not arrived is a Lonely Transfer when the Budget Account of the other side is known and its payee or memo matches a description of the right list.
    /** The other side of [single], whose other Transfer Leg has not arrived, or null when it is an ordinary Bank Transaction. */
    private fun oneSided(single: TransferCandidate, sides: Map<Long, StoredBankAccount>, descriptions: List<StoredTransferDescription>): OneSided? {
        val bankAccount = single.bankAccount
        val cardPayment = TransferDescriptions.matchesAny(descriptions, TransferDescriptionKind.CardPayment, single.transaction)
        if (bankAccount.creditCard) {
            if (single.outflow || !cardPayment) return null
            // UC-005 BR-002: An inflow to a credit card Bank Account that matches a Card Payment Description has the card's Funded By as its other side.
            fundedBy(bankAccount, sides)?.let { return OneSided(it, TransferType.CardPayment, whyGuessed = emptyList()) }
            // UC-005 BR-002: A Card Payment to a credit card Bank Account without a Funded By is a Guessed Transfer that proposes the only Bank Account of the Budget that is not a credit card, or no Budget Account (A8).
            val paying = sides.values.filterNot { it.creditCard }
            return OneSided(
                paying.singleOrNull()?.mapping?.budgetAccountId, TransferType.CardPayment, listOf(GuessReason.NoFundedBy(bankAccount.id)), paying.size,
            )
        }
        if (single.outflow && cardPayment) {
            // UC-005 BR-002: An outflow that matches a Card Payment Description has as its other side the credit card whose Funded By is its Budget Account; Slussen never infers a credit card from the paying Bank Account.
            val cards = sides.values.filter { it.creditCard && fundedBy(it, sides) == single.budgetAccountId }.sortedBy { it.id }
            // UC-005 BR-003: When more than one credit card Bank Account has that Funded By, the outflow is a Guessed Transfer.
            if (cards.isNotEmpty()) {
                val others = cards.drop(1).map { it.mapping!!.budgetAccountId }
                return OneSided(
                    cards.first().mapping!!.budgetAccountId, TransferType.CardPayment, listOfNotNull(GuessReason.OtherCards(others).takeIf { others.isNotEmpty() }),
                )
            }
        }
        if (!TransferDescriptions.matchesAny(descriptions, TransferDescriptionKind.BankTransfer, single.transaction)) return null
        // UC-005 BR-002: A Bank Transaction of a Bank Account that is not a credit card that matches a Bank Transfer Description has its Transfer Partner as its other side.
        val partner = transferPartner(bankAccount, sides) ?: return null
        return OneSided(partner, typeOf(bankAccount, sides.getValue(partner)), whyGuessed = emptyList())
    }

    /**
     * The [detected] Transfers as O-5 stores them: each continues the [stored] Transfer with the decision Proposed that one
     * of its Transfer Legs already belongs to, so that a Lonely Transfer whose other side arrives stays the same Transfer.
     * A stored Proposed Transfer that none continues no longer holds.
     */
    fun toStore(detected: List<DetectedTransfer>, stored: List<StoredTransfer>): List<TransferToStore> {
        val proposed = stored.filter { it.decision == TransferDecision.Proposed }.map { it.id }.toSet()
        val continued = mutableSetOf<Long>()
        return detected.map { transfer ->
            val continues = transfer.legs.mapNotNull { it.transferId }.firstOrNull { it in proposed && it !in continued }
            continues?.let { continued += it }
            TransferToStore(
                continues = continues,
                outflowBudgetAccountId = transfer.outflowBudgetAccountId,
                inflowBudgetAccountId = transfer.inflowBudgetAccountId,
                amount = transfer.amount,
                type = transfer.type,
                guessed = transfer.guessed,
                legs = transfer.legs.map { it.id },
            )
        }
    }
}
