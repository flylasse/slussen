package slussen.core.transfer

import slussen.core.port.BankAccountType
import slussen.core.port.DroppedTransferDecision
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredSentRecord
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDropReason
import slussen.core.port.TransferJoin
import slussen.core.port.TransferType
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * A stored Transfer that a Comparison keeps as it is stored (UC-005 BR-008, BR-015): one with a Transfer Decision whose
 * own sides are still valid, or one with an uploaded Transfer Leg. [legs] are its Transfer Legs after this Comparison,
 * with [joined], the arriving other side of a Lonely Transfer, among them (BR-005).
 */
data class KeptTransfer(
    val transfer: StoredTransfer,
    val legs: List<StoredBankTransaction>,
    val joined: StoredBankTransaction? = null,
)

/**
 * What a Comparison concludes for the stored Transfers it does not detect again in one Budget (UC-005 BR-005, BR-010,
 * BR-015): those it keeps, the Transfer Decisions it drops, and the Transaction Status of each compared Transfer Leg of a
 * kept Transfer that follows its Transfer.
 */
data class TransfersKept(
    val kept: List<KeptTransfer> = emptyList(),
    val dropped: List<DroppedTransferDecision> = emptyList(),
    val statuses: Map<Long, TransactionStatus> = emptyMap(),
) {
    /** The Bank Transactions that are Transfer Legs of a kept Transfer: Slussen forms no other Transfer from them. */
    val legIds: Set<Long> get() = kept.flatMap { it.legs }.map { it.id }.toSet()

    val joins: List<TransferJoin> get() = kept.mapNotNull { kept -> kept.joined?.let { TransferJoin(kept.transfer.id, it.id) } }
}

/**
 * The rules for a Transfer that has a Transfer Decision or an uploaded Transfer Leg (UC-005 BR-005, BR-007, BR-008,
 * BR-010, BR-015), `:core` `transfer` (ADR-001 §7): what "uploaded" means, the lock, when its own sides are no longer
 * valid, which arriving Bank Transaction joins a Lonely Transfer the Operator decided on, and the Transaction Status of
 * its Transfer Legs. Pure rules on stored data, run inside the Comparison's conclusions (ADR-002 §3 O-5) and inside a
 * Transfer Decision (O-13). They read the Sent Records, of which none is stored until the Uploads are built.
 */
object TransferRules {
    private val StoredBankAccount.creditCard: Boolean get() = type == BankAccountType.CreditCard

    private fun counts(record: StoredSentRecord, storedBankTransaction: Long): Boolean =
        record.storedBankTransaction == storedBankTransaction && record.outcome != SentRecordOutcome.Rejected

    // UC-005 BR-008: A Transfer Leg counts as uploaded once its Sent Record has the outcome Written, Already Present, Not Yet Known or Unknown; one the Budget App rejected was not written and locks nothing.
    /** Whether Slussen sent the Bank Transaction [storedBankTransaction] and the Budget App did not reject it. */
    fun uploaded(storedBankTransaction: Long, sentRecords: List<StoredSentRecord>): Boolean =
        sentRecords.any { counts(it, storedBankTransaction) }

    /** Whether [storedBankTransaction] was uploaded as a linked transfer. */
    private fun uploadedAsTransfer(storedBankTransaction: Long, sentRecords: List<StoredSentRecord>): Boolean =
        sentRecords.any { counts(it, storedBankTransaction) && it.sentAsTransfer }

    // UC-005 BR-005: A Bank Transaction that was uploaded as an ordinary transaction is no Transfer Leg and never the other side of a Transfer.
    /** Whether [storedBankTransaction] was uploaded as an ordinary transaction and never as a linked transfer. */
    fun uploadedAsOrdinary(storedBankTransaction: Long, sentRecords: List<StoredSentRecord>): Boolean =
        sentRecords.any { counts(it, storedBankTransaction) && !it.sentAsTransfer } && !uploadedAsTransfer(storedBankTransaction, sentRecords)

    // UC-005 BR-008: A Transfer Decision can be changed until a Transfer Leg of the Transfer is uploaded, as a linked transfer or, after Split, as an ordinary transaction; from then on it is locked.
    /** Whether the Transfer Decision of [transfer], with its stored Transfer Legs [legs], is locked. */
    fun locked(transfer: StoredTransfer, legs: List<StoredBankTransaction>, sentRecords: List<StoredSentRecord>): Boolean =
        legs.any { uploadedAsTransfer(it.id, sentRecords) } ||
            (transfer.decision == TransferDecision.Split && legs.any { uploaded(it.id, sentRecords) })

    /** The Budget Account of the side of [transfer] that [leg] lies on: the outflow side for an outflow. */
    fun sideOf(transfer: StoredTransfer, leg: StoredBankTransaction): Long? =
        if (leg.amount.thousandths < 0) transfer.outflowBudgetAccountId else transfer.inflowBudgetAccountId

    /** The Budget Accounts of the sides of [transfer] whose Transfer Leg has not arrived, by its [legs]. */
    fun sidesNotArrived(transfer: StoredTransfer, legs: List<StoredBankTransaction>): List<Long> = listOfNotNull(
        transfer.outflowBudgetAccountId.takeIf { legs.none { it.amount.thousandths < 0 } },
        transfer.inflowBudgetAccountId.takeIf { legs.none { it.amount.thousandths >= 0 } },
    )

    /** The Bank Accounts of the sides of [transfer]: those of its [legs], and those mapped to a side that has not arrived. */
    private fun bankAccountsOfSides(transfer: StoredTransfer, legs: List<StoredBankTransaction>, bankAccounts: List<StoredBankAccount>): List<StoredBankAccount> {
        val notArrived = sidesNotArrived(transfer, legs)
        return bankAccounts.filter { account -> legs.any { it.bankAccountId == account.id } || account.mapping?.budgetAccountId in notArrived }
    }

    // UC-005 BR-015: Slussen drops a Transfer Decision only when the Transfer's own sides are no longer valid.
    /**
     * Why the own sides of [transfer], with its stored Transfer Legs [legs], are no longer valid against the stored
     * [bankAccounts], or null while they are valid. A change of a Funded By, a Transfer Partner, a Transfer Description,
     * the transfer pairing days or the grace period is never a reason.
     */
    fun whyNoLongerValid(transfer: StoredTransfer, legs: List<StoredBankTransaction>, bankAccounts: List<StoredBankAccount>): TransferDropReason? {
        legs.forEach { leg ->
            val mapping = bankAccounts.firstOrNull { it.id == leg.bankAccountId }?.mapping
            // UC-005 BR-015: The decision is dropped when the Bank Account of a Transfer Leg has no Account Mapping, or is mapped to another Budget Account than the one the Transfer names for that side.
            if (mapping == null) return TransferDropReason.TransferLegNotMapped
            if (mapping.budgetAccountId != sideOf(transfer, leg)) return TransferDropReason.TransferLegMappedElsewhere
        }
        // UC-005 BR-015: The decision is dropped when the Budget Account of a side that has not arrived receives through no Account Mapping any more.
        if (sidesNotArrived(transfer, legs).any { side -> bankAccounts.none { it.mapping?.budgetAccountId == side } }) {
            return TransferDropReason.OtherSideNotFetched
        }
        val anyCreditCard = bankAccountsOfSides(transfer, legs, bankAccounts).any { it.creditCard }
        // UC-005 BR-015: The decision is dropped when, after a change of a Bank Account type, the Transfer is a Card Payment neither of whose sides is a credit card Bank Account, or a Bank Transfer one of whose sides is.
        if ((transfer.type == TransferType.CardPayment) != anyCreditCard) return TransferDropReason.TypeNoLongerFits
        return null
    }

    // UC-005 BR-015: While the Bank Account of one of its sides is a Retired Account, a Transfer with a Transfer Decision keeps its decision, its Transfer Legs and its Budget Accounts and is in no Upload Plan, from either side.
    /** Whether the Bank Account of a side of [transfer], with its stored Transfer Legs [legs], is a Retired Account. */
    fun hasRetiredSide(transfer: StoredTransfer, legs: List<StoredBankTransaction>, bankAccounts: List<StoredBankAccount>): Boolean =
        bankAccountsOfSides(transfer, legs, bankAccounts).any { it.retired }

    /** Whether [other] has the opposite amount of [arrived] and is dated within [pairingDays] of it (BR-002). */
    fun couldBeOtherSide(arrived: StoredBankTransaction, other: StoredBankTransaction, pairingDays: Int): Boolean =
        other.amount.thousandths == -arrived.amount.thousandths &&
            abs(ChronoUnit.DAYS.between(arrived.bookedDate, other.bookedDate)) <= pairingDays

    // UC-005 BR-005: When the Bank Account of the missing side holds a Bank Transaction with the opposite amount within the transfer pairing days that was uploaded as an ordinary transaction, the other side is written already.
    /**
     * Whether the other side of [arrived], a single Transfer Leg, is written already: [ofOtherSide], the Bank
     * Transactions of the Bank Account of that side, hold one that could be its other side and was uploaded as an
     * ordinary transaction.
     */
    fun otherSideWrittenAlready(
        arrived: StoredBankTransaction,
        ofOtherSide: List<StoredBankTransaction>,
        sentRecords: List<StoredSentRecord>,
        pairingDays: Int,
    ): Boolean = ofOtherSide.any { couldBeOtherSide(arrived, it, pairingDays) && uploadedAsOrdinary(it.id, sentRecords) }

    /** A Bank Transaction that could join [lonely] as its second Transfer Leg (BR-005). */
    private data class Joining(val lonely: KeptTransfer, val arrived: StoredBankTransaction, val candidate: TransferCandidate)

    /**
     * What a Comparison concludes for the stored Transfers of one Budget that it does not detect again: [transfers], each
     * with a Transfer Decision or an uploaded Transfer Leg. [allTransactions] are the stored Bank Transactions the
     * Comparison reads and [bankAccounts] the stored Bank Accounts, of every Budget; [compared] are the Bank Transactions
     * of this Budget that have a Transaction Status in this Comparison, by id, and [sides] the Budget Accounts of this
     * Budget that can be a side of a Transfer, each with its Bank Account (BR-014).
     */
    fun keep(
        transfers: List<StoredTransfer>,
        allTransactions: List<StoredBankTransaction>,
        bankAccounts: List<StoredBankAccount>,
        sentRecords: List<StoredSentRecord>,
        compared: Map<Long, TransferCandidate>,
        sides: Map<Long, StoredBankAccount>,
        pairingDays: Int,
    ): TransfersKept {
        val dropped = mutableListOf<DroppedTransferDecision>()
        val kept = mutableListOf<KeptTransfer>()
        val joinable = mutableListOf<KeptTransfer>()
        transfers.forEach { transfer ->
            val legs = allTransactions.filter { it.transferId == transfer.id }
            // UC-005 BR-015: A Transfer one of whose Transfer Legs has been uploaded is never dissolved or changed.
            if (locked(transfer, legs, sentRecords)) {
                kept += KeptTransfer(transfer, legs)
                return@forEach
            }
            val split = transfer.decision == TransferDecision.Split
            val reason = when {
                // UC-005 BR-005: A Bank Transaction that was uploaded as an ordinary transaction is no Transfer Leg: the ground of a Transfer that names it as one is no longer valid.
                !split && legs.any { uploadedAsOrdinary(it.id, sentRecords) } -> TransferDropReason.OtherSideUploadedAsOrdinary
                else -> whyNoLongerValid(transfer, legs, bankAccounts)
            }
            if (reason != null) {
                dropped += DroppedTransferDecision(transfer, reason, legs)
                return@forEach
            }
            val keeps = KeptTransfer(transfer, legs)
            // UC-005 BR-005: A Lonely Transfer with the Transfer Decision Confirmed, Held or Budget Account Chosen is joined by its arriving other side; after Split nothing joins.
            val lonelyAndNotSplit = !split && legs.size == 1
            // UC-005 BR-015: While the Bank Account of one of its sides is a Retired Account, the Transfer keeps its Transfer Legs as they are.
            val retiredSide = hasRetiredSide(transfer, legs, bankAccounts)
            // A Transfer stored without a Budget Account on its paying side names no side a Bank Transaction could join (entity model `TRANSFER`, UC-005 A8).
            if (lonelyAndNotSplit && !retiredSide && transfer.outflowBudgetAccountId != null) {
                joinable += keeps
            } else {
                kept += keeps
            }
        }
        val legsOfKept = (kept + joinable).flatMap { it.legs }.map { it.id }.toSet()
        // UC-005 BR-005: The Bank Transaction that joins has the opposite amount, lies in the Bank Account mapped to the Budget Account of the missing side within the transfer pairing days, has not been uploaded and is a Transfer Leg of no Transfer that has a Transfer Decision or an uploaded Transfer Leg.
        val joinings = joinable.flatMap { lonely ->
            val arrived = lonely.legs.single()
            val otherSide = sides[sidesNotArrived(lonely.transfer, lonely.legs).single()] ?: return@flatMap emptyList()
            compared.values
                .filter { it.bankAccount.id == otherSide.id && couldBeOtherSide(arrived, it.transaction, pairingDays) }
                .filter { it.transaction.id !in legsOfKept && !uploaded(it.transaction.id, sentRecords) }
                .map { Joining(lonely, arrived, it) }
        }
        // UC-005 BR-005: Of several Bank Transactions the one with the nearest booked date joins, then the one Slussen received first; a Bank Transaction that could join more than one Lonely Transfer joins the one whose arrived Transfer Leg has the nearest booked date, then the one the Operator decided on first.
        val nearestFirst = compareBy<Joining>(
            { abs(ChronoUnit.DAYS.between(it.arrived.bookedDate, it.candidate.transaction.bookedDate)) },
            { it.lonely.transfer.decidedAt ?: Instant.MAX },
            { it.candidate.transaction.firstSeenAt },
            { it.lonely.transfer.id },
            { it.candidate.transaction.id },
        )
        val joined = mutableMapOf<Long, StoredBankTransaction>()
        joinings.sortedWith(nearestFirst).forEach { joining ->
            val transferId = joining.lonely.transfer.id
            if (transferId in joined || joining.candidate.transaction in joined.values) return@forEach
            joined[transferId] = joining.candidate.transaction
        }
        joinable.forEach { lonely ->
            val joins = joined[lonely.transfer.id]
            val arrived = lonely.legs.single()
            val otherSide = sidesNotArrived(lonely.transfer, lonely.legs).single()
            val ofOtherSide = bankAccounts.filter { it.mapping?.budgetAccountId == otherSide }.flatMap { account -> allTransactions.filter { it.bankAccountId == account.id } }
            when {
                joins != null -> kept += lonely.copy(legs = lonely.legs + joins, joined = joins)
                // UC-005 BR-015: The decision is dropped when the other side of a Lonely Transfer was already uploaded as an ordinary transaction and no Bank Transaction can join.
                otherSideWrittenAlready(arrived, ofOtherSide, sentRecords, pairingDays) ->
                    dropped += DroppedTransferDecision(lonely.transfer, TransferDropReason.OtherSideUploadedAsOrdinary, lonely.legs)
                else -> kept += lonely
            }
        }
        val statuses = mutableMapOf<Long, TransactionStatus>()
        kept.forEach { keeps ->
            // UC-005 BR-010: After Split, each Transfer Leg gets its status as an ordinary Bank Transaction.
            if (keeps.transfer.decision == TransferDecision.Split) return@forEach
            val legs = keeps.legs.mapNotNull { compared[it.id] }
            legs.forEach { leg -> statuses[leg.transaction.id] = statusOfLeg(keeps.transfer, leg, legs.filter { it !== leg }) }
        }
        return TransfersKept(kept, dropped, statuses)
    }

    // UC-005 BR-010: For a Transfer that has a Transfer Decision, the first that applies gives each Transfer Leg its status: a status an earlier rule of the Comparison gives it; the status the Review Decision on another Transfer Leg gives it (BR-013); Held Transfer when the Transfer is held back; and otherwise Transfer Leg.
    /** The Transaction Status [leg] follows as a Transfer Leg of the kept [transfer], which is not split, beside its [otherLegs]. */
    fun statusOfLeg(transfer: StoredTransfer, leg: TransferCandidate, otherLegs: List<TransferCandidate>): TransactionStatus =
        leg.earlier ?: otherLegs.firstNotNullOfOrNull { it.givesOtherLegs } ?: when {
            // UC-005 BR-007: A Transfer the Operator holds back keeps both Transfer Legs out of every Upload Plan, with the status Held Transfer.
            transfer.decision == TransferDecision.Held -> TransactionStatus.HeldTransfer
            // UC-005 BR-010: The Transfer Legs of a Guessed Transfer without a Transfer Decision have the status Guessed Transfer.
            transfer.decision == TransferDecision.Proposed && transfer.guessed -> TransactionStatus.GuessedTransfer
            else -> TransactionStatus.TransferLeg
        }
}
