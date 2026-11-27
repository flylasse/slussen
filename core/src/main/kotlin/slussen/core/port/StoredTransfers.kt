package slussen.core.port

import slussen.pluginapi.value.Amount
import java.time.Instant

/** The `Values:` of `TRANSFER.transfer_type`: a Card Payment or a move between Bank Accounts. */
enum class TransferType(val text: String) {
    CardPayment("Card Payment"),
    BankTransfer("Bank Transfer"),
}

/** The `Values:` of `TRANSFER.decision`: Proposed until the Operator decides (UC-005), then the Transfer Decision. */
enum class TransferDecision(val text: String) {
    Proposed("Proposed"),
    Confirmed("Confirmed"),
    Split("Split"),
    Held("Held"),
    BudgetAccountChosen("Budget Account Chosen"),
}

/** The `Values:` of `TRANSFER_DESCRIPTION.kind`: which list an entry belongs to. */
enum class TransferDescriptionKind(val text: String) {
    CardPayment("Card Payment"),
    BankTransfer("Bank Transfer"),
}

/**
 * `TRANSFER` (entity model) as stored. Its Transfer Legs are the stored Bank Transactions whose `transfer_id` names it:
 * one for a Lonely Transfer, two for a pair.
 */
data class StoredTransfer(
    val id: Long,
    /**
     * The Budget Account of the outflow side; empty only for a Guessed Transfer for which Slussen proposes no paying
     * Budget Account (entity model `TRANSFER`, UC-005 A8). No Transfer is stored without it yet: a Comparison stores
     * every Transfer with both sides ([TransferToStore]).
     */
    val outflowBudgetAccountId: Long?,
    val inflowBudgetAccountId: Long,
    /** The amount moved, positive, in the Budget's currency. */
    val amount: Amount,
    val type: TransferType,
    val guessed: Boolean,
    val decision: TransferDecision,
    val decidedAt: Instant?,
)

/** `TRANSFER_DESCRIPTION` (entity model) as stored: one entry of the Card Payment or Bank Transfer Descriptions. */
data class StoredTransferDescription(val kind: TransferDescriptionKind, val text: String)

/**
 * One Transfer a Comparison detected (UC-003 step 4, UC-005 BR-002), as O-5 stores it with the decision Proposed:
 * [continues] is the stored Transfer it is, when one of its [legs] already belonged to it, and null for a new one.
 */
data class TransferToStore(
    val continues: Long?,
    val outflowBudgetAccountId: Long,
    val inflowBudgetAccountId: Long,
    val amount: Amount,
    val type: TransferType,
    val guessed: Boolean,
    /** The ids of the stored Bank Transactions that are its Transfer Legs: one for a Lonely Transfer, two for a pair. */
    val legs: List<Long>,
)

/**
 * What O-5 concludes from the stored data (UC-003 steps 3-4): the Transaction Status of each compared Bank Transaction
 * by its id, and every Transfer that holds among them. A stored Transfer with the decision Proposed that is among the
 * [ComparisonData.transfers] and that none of [transfers] continues no longer holds: O-5 releases its Transfer Legs.
 */
data class ComparisonConclusions(
    val statuses: Map<Long, TransactionStatus>,
    val transfers: List<TransferToStore> = emptyList(),
)
