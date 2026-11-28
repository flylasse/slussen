package slussen.core.transfer

import slussen.core.operation.OperationRefusal
import slussen.core.port.OtherSideKind
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDropReason
import slussen.core.port.TransferType
import slussen.pluginapi.value.Amount
import java.time.LocalDate

/** What the Operator decides for a Transfer (UC-005 step 4, A1-A3). */
sealed interface TransferChoice {
    /** Step 4, A4: the Transfer is as Slussen proposes it. */
    data object Confirm : TransferChoice

    /** A1: each Transfer Leg is an ordinary Bank Transaction. */
    data object Split : TransferChoice

    /** A2: neither Transfer Leg is uploaded. */
    data object Hold : TransferChoice

    /**
     * A3: the Budget Account [budgetAccountId] is the other side; [keep] is the Operator's answer to whether it is kept as
     * the Funded By or the Transfer Partner of the Bank Account of the Transfer Leg that stays (BR-016).
     */
    data class ChooseBudgetAccount(val budgetAccountId: Long, val keep: Boolean) : TransferChoice
}

/** Why no Transfer Decision can be made through a Bank Transaction now; nothing is stored. */
enum class TransferUndecidable {
    /** Precondition of UC-005: it has no Transaction Status from a Comparison yet (UC-003 A1). */
    NotComparedYet,

    /** Its Bank Account has no Account Mapping into a served Budget: it is not compared (UC-003 step 2). */
    BankAccountNotCompared,

    /**
     * Its stored Transfer has no Transfer Decision and the rules of the Comparison no longer give it on the stored data
     * today, for example because its grace period ended (UC-005 A5, BR-005): the next Comparison says what it is.
     */
    NoLongerProposed,

    /**
     * UC-005 A11, UC-003 BR-016: its Budget owes a read of its read period, so Slussen's copy of the Budget App lacks
     * transactions of it; no Transfer Decision is taken until the Budget App is read.
     */
    PeriodReadOwed,
}

/** One Transfer Leg as the sheet of a Transfer shows it. */
data class TransferLegShown(
    val storedBankTransaction: Long,
    val bankAccountName: String,
    val budgetAccountName: String,
    val bookedDate: LocalDate,
    val amount: Amount,
    val payee: String?,
)

/**
 * A Budget Account the Operator can choose as the other side (UC-005 step 3, A3, BR-014): [proposed] when it is the other
 * side the Transfer has now; [proposesKeeping] is the answer Slussen proposes to "keep this answer?" (BR-016).
 */
data class OfferedBudgetAccount(val budgetAccountId: Long, val name: String, val proposed: Boolean, val proposesKeeping: Boolean)

/** A10: a Bank Transaction that could be the other side but cannot be paired, and why. */
data class TakenOtherSide(
    val storedBankTransaction: Long,
    val bookedDate: LocalDate,
    val amount: Amount,
    /** It has been uploaded itself. */
    val uploaded: Boolean,
    /** The other Transfer it is a Transfer Leg of, which the Operator decided on or one of whose Transfer Legs has been uploaded; null when it is in none. */
    val transfer: TransferNamed?,
)

/** A10 step 1: the other Transfer, named with its two Budget Accounts, amount, dates and Transfer Decision. */
data class TransferNamed(
    val outflowBudgetAccountName: String?,
    val inflowBudgetAccountName: String,
    val amount: Amount,
    val dates: List<LocalDate>,
    val decision: TransferDecision,
    /** BR-016: one of its Transfer Legs has been uploaded, as a linked transfer or as an ordinary transaction, so no Transfer Leg is taken from it. */
    val locked: Boolean,
)

/**
 * The sheet of one selected Transfer (UC-005 steps 2-3, A4, A7, A8, A9; `transfers.html`, `transfer-budget-account.html`),
 * built from stored data only by the rules of the Comparison.
 */
data class TransferSheet(
    val type: TransferType,
    /** The amount moved, positive. */
    val amount: Amount,
    /** Null when Slussen proposes no paying Budget Account (A8). */
    val outflowBudgetAccountName: String?,
    val inflowBudgetAccountName: String,
    /** One for a Lonely Transfer (A4), two for a pair. */
    val legs: List<TransferLegShown>,
    val guessed: Boolean,
    val decision: TransferDecision,
    /** A7, BR-008: a Transfer Leg has been uploaded; no choice is offered. */
    val locked: Boolean,
    /** A9, BR-013: the Transfer Leg that waits for the Operator's Review Decision, or null; while one does, only Split is offered. */
    val waitsForReviewDecision: Long?,
    /** Step 4: false when there is nothing to confirm because the Transfer has no Budget Account on its paying side (A8), and while it waits or is locked. */
    val canConfirm: Boolean,
    val canSplit: Boolean,
    val canHold: Boolean,
    /** Step 3: the Budget Accounts of the same Budget and currency that Slussen fetches, other than that of the Transfer Leg that stays (BR-014, BR-016); empty while it waits or is locked. */
    val offered: List<OfferedBudgetAccount>,
    /** BR-016: the Transfer Leg that stays when the Operator chooses another Budget Account. */
    val staysWith: Long,
    /** A3 step 1: what the answer is kept as, for the Bank Account of the Transfer Leg that stays. */
    val keptAs: OtherSideKind,
)

/** What a Transfer Decision came to (UC-005 steps 4-6, A1-A4, A7-A10). */
sealed interface TransferOutcome {
    /**
     * Step 5: the Transfer Decision [decision] is stored with its effect. [lonely] when the Transfer has one Transfer Leg
     * now; [kept] is what the Operator's answer set (A3 step 4), or null. [detectedAgain] is false when Slussen could not
     * give the statuses again after storing the decision: the decision stands, and no Upload Plan is prepared before
     * the Transfers are detected again from stored data (BR-016).
     */
    data class Stored(val decision: TransferDecision, val lonely: Boolean, val kept: OtherSideKind?, val detectedAgain: Boolean) : TransferOutcome

    /** The Transfer already has this Transfer Decision; nothing is stored. */
    data class Unchanged(val decision: TransferDecision) : TransferOutcome

    /** A8 step 2: the Transfer has no Budget Account on its paying side, so there is nothing to confirm. Nothing is stored. */
    data object NothingToConfirm : TransferOutcome

    /** A9, BR-013: the Transfer Leg [storedBankTransaction] waits for a Review Decision; only Split is taken until then. Nothing is stored. */
    data class WaitsForReviewDecision(val storedBankTransaction: Long) : TransferOutcome

    /** A7, BR-008: a Transfer Leg has been uploaded; the Transfer Decision is locked. Nothing is stored. */
    data object Locked : TransferOutcome

    /**
     * A10, BR-016: every Bank Transaction that could be the other side in the chosen Budget Account has been uploaded or
     * belongs to another Transfer that the Operator decided on or that is already uploaded. Nothing is stored and no
     * Funded By or Transfer Partner is set.
     */
    data class OtherSideTaken(val taken: List<TakenOtherSide>) : TransferOutcome

    /** BR-014: the chosen Budget Account is not one the Operator can choose for this Transfer. Nothing is stored. */
    data object BudgetAccountNotOffered : TransferOutcome

    /** BR-015: the own sides of the Transfer are no longer valid, for [reason]; the next Comparison detects again. Nothing is stored. */
    data class NoLongerValid(val reason: TransferDropReason) : TransferOutcome

    /** The Bank Transaction is a Transfer Leg of no Transfer as the stored data stand. Nothing is stored. */
    data object NotATransfer : TransferOutcome

    /** No decision can be made through it now ([reason]). Nothing is stored. */
    data class NotDecidable(val reason: TransferUndecidable) : TransferOutcome

    /** There is no such Bank Transaction. */
    data object NotFound : TransferOutcome

    /** ADR-007 §2: an Upload is being written; the decision is refused until it ends. Nothing is stored. */
    data class Unavailable(val reason: OperationRefusal) : TransferOutcome

    /** NFR-023: the stored data could not be read or written; nothing is changed. */
    data object NotStored : TransferOutcome
}
