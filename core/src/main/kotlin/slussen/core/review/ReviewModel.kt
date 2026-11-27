package slussen.core.review

import slussen.core.operation.OperationRefusal
import slussen.core.port.ReviewChoice
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferType
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.status.BudgetAppEntry
import slussen.core.status.OtherLeg
import slussen.core.status.StatusReason
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/**
 * An Import by an Other Import Path that resembles a Bank Transaction (UC-003 BR-007), as the list of UC-004 step 4
 * shows it: its date, payee, amount and Budget Account; [budgetAppReference] names it when the Operator chooses it.
 */
data class ResemblingImport(
    val budgetAppReference: String,
    val date: LocalDate,
    val payee: String?,
    val amount: Amount,
    val budgetAccountName: String,
)

/** Why an Already Present decision does not hold any more (UC-003 BR-009, A4), the first that applies. */
enum class NoLongerHolds {
    /** The chosen Budget App Transaction is deleted, merged into another one or not in the stored copy any more. */
    NoLongerThere,

    /** It is in another Budget Account than the one recorded with the decision. */
    InAnotherBudgetAccount,

    /** It has another amount than the one recorded with the decision. */
    AnotherAmount,
}

/**
 * The Review Decision a Bank Transaction has (UC-004 A6 step 1): [chosen] is the Budget App Transaction of an Already
 * Present decision as the stored copy holds it now, null when it holds none; [noLongerHolds] says why such a decision
 * does not hold any more, null while it holds and for every other choice.
 */
data class DecisionShown(
    val choice: ReviewChoice,
    val decidedAt: Instant,
    val chosen: BudgetAppEntry?,
    val noLongerHolds: NoLongerHolds?,
)

/**
 * The Transfer a Bank Transaction is a Transfer Leg of (UC-004 A11): its Budget Accounts, the other Transfer Leg, null
 * while that side has not arrived, and whether the Review Decision decides the whole Transfer (BR-009), which it does
 * unless the Transfer is split.
 */
data class TransferOfLeg(
    val type: TransferType,
    /** Null for a Guessed Transfer for which Slussen proposes no paying Budget Account (UC-005 A8). */
    val outflowBudgetAccountName: String?,
    val inflowBudgetAccountName: String,
    /** The amount moved, positive. */
    val amount: Amount,
    val otherLeg: OtherLeg?,
    val decision: TransferDecision,
    val decidesWholeTransfer: Boolean,
)

/** Why no Review Decision can be made on a Bank Transaction now; nothing is stored. */
enum class Undecidable {
    /** Precondition of UC-004: it has no Transaction Status from a Comparison yet (UC-003 A1). */
    NotComparedYet,

    /** Its Bank Account has no Account Mapping into a served Budget or is a Retired Account: it is not compared (UC-003 step 2). */
    BankAccountNotCompared,

    /**
     * It is a Transfer Leg of a Transfer with the Transfer Decision Confirmed, Held or Budget Account Chosen, whose
     * Transfer Legs the Comparison leaves as they are stored until it handles Transfer Decisions (UC-005 BR-010).
     */
    LegOfDecidedTransfer,
}

/**
 * The Review Decision sheet of one Bank Transaction (UC-004 steps 1-2, 4, A4-A7, A10, A11; `review-decision.html`),
 * built from stored data only, by the rules of the Comparison (BR-006).
 */
data class ReviewSheet(
    val storedBankTransaction: Long,
    val bankAccountId: Long,
    val bankAccountName: String,
    val bookedDate: LocalDate,
    val amount: Amount,
    val currency: CurrencyCode,
    val payee: String?,
    val memo: String?,
    /** Its stored Transaction Status, null while it is not compared yet. */
    val status: TransactionStatus?,
    /** BR-006: the Transaction Status it has without its Review Decision; null when [undecidable]. */
    val statusWithoutDecision: TransactionStatus?,
    /** Step 2: the reason it is doubtful, for Needs Review and Possible Import By Other Path; null otherwise. */
    val why: StatusReason?,
    val decision: DecisionShown?,
    /** A7, BR-004: the Review Decision is locked; no choice is offered and it cannot be cleared. */
    val locked: Boolean,
    /** BR-001: the choices its Transaction Status offers, in the order the rule names them. */
    val choices: List<ReviewChoice>,
    /** BR-001, A6: clearing the decision is offered. */
    val canClear: Boolean,
    /** Step 4, BR-002: the Imports by an Other Import Path that resemble it, among which Already Present names one. */
    val resembling: List<ResemblingImport>,
    /** A11: its Transfer, null when it is a Transfer Leg of none. */
    val transfer: TransferOfLeg?,
    val undecidable: Undecidable?,
)

/** What deciding on a Bank Transaction, or clearing its Review Decision, came to (UC-004 steps 3-8, A1-A7, A11). */
sealed interface ReviewOutcome {
    /**
     * Steps 6-8: the Review Decision [decision] is stored, null when it was cleared (A6), and the Bank Transaction has
     * the Transaction Status [status] that follows from it (BR-006).
     */
    data class Stored(val decision: ReviewChoice?, val status: TransactionStatus) : ReviewOutcome

    /**
     * Step 4, BR-002: Already Present names one Budget App Transaction; more than one Import by an Other Import Path
     * resembles the Bank Transaction, or the one named is not among them. Nothing is stored; the Operator chooses from
     * [resembling].
     */
    data class ChooseTransaction(val resembling: List<ResemblingImport>) : ReviewOutcome

    /** BR-001: its Transaction Status does not offer this choice, only [offered]. Nothing is stored. */
    data class NotOffered(val offered: List<ReviewChoice>) : ReviewOutcome

    /** A7, BR-004: the Review Decision is locked, because the Bank Transaction has been uploaded. Nothing is stored. */
    data object Locked : ReviewOutcome

    /** A6: the Bank Transaction has no Review Decision to clear. Nothing is stored. */
    data object NothingToClear : ReviewOutcome

    /** No decision can be made on it now ([reason]). Nothing is stored. */
    data class NotDecidable(val reason: Undecidable) : ReviewOutcome

    /** There is no such Bank Transaction. */
    data object NotFound : ReviewOutcome

    /** ADR-007 §2: an Upload is being written; the decision is refused until it ends. Nothing is stored. */
    data class Unavailable(val reason: OperationRefusal) : ReviewOutcome

    /** NFR-023: the stored data could not be read or written; nothing is changed. */
    data object NotStored : ReviewOutcome
}

/**
 * The sheet of an Unreadable Transaction (UC-004 A9; `review-decision.html#unreadable`): whatever fields of it could be
 * read, its Bank Account and the reason Slussen could not interpret it, with the choice Defer only (BR-007).
 */
data class UnreadableSheet(
    val storedUnreadableTransaction: Long,
    val bankAccountName: String,
    val bookedDate: LocalDate?,
    val amount: Amount?,
    val currency: CurrencyCode?,
    val payee: String?,
    val memo: String?,
    val reason: String,
    val firstSeenAt: Instant,
    val status: UnreadableTransactionStatus,
    /** BR-007: Defer while it is Needs Review, and nothing else ever. */
    val canDefer: Boolean,
    /** BR-007: clearing that decision while it is Deferred. */
    val canClear: Boolean,
)

/** What deferring an Unreadable Transaction, or clearing that decision, came to (UC-004 A9, BR-007). */
sealed interface UnreadableOutcome {
    /** A9 step 3: it has the status [status] now. */
    data class Stored(val status: UnreadableTransactionStatus) : UnreadableOutcome

    /** It already has the status asked for; nothing is stored. */
    data class Unchanged(val status: UnreadableTransactionStatus) : UnreadableOutcome

    data object NotFound : UnreadableOutcome

    /** ADR-007 §2: an Upload is being written. Nothing is stored. */
    data class Unavailable(val reason: OperationRefusal) : UnreadableOutcome

    /** NFR-023: the stored data could not be read or written; nothing is changed. */
    data object NotStored : UnreadableOutcome
}
