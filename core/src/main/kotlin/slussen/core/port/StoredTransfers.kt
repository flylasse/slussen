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
     * Budget Account (entity model `TRANSFER`, UC-005 A8), which is stored only once the Operator splits it or holds it
     * back: a Comparison stores every Transfer with both sides ([TransferToStore]).
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

/** UC-005 BR-015: why the own sides of a Transfer the Operator decided on are no longer valid, in the words of the Decision Journal. */
enum class TransferDropReason(val text: String) {
    TransferLegNotMapped("the Bank Account of one of its Transfer Legs has no Account Mapping any more"),
    TransferLegMappedElsewhere("the Bank Account of one of its Transfer Legs is mapped to another Budget Account than the Transfer names for that side"),
    OtherSideNotFetched("the Budget Account of its side that has not arrived receives through no Account Mapping any more"),
    TypeNoLongerFits("the Bank Account types of its sides no longer fit a Transfer of its kind"),
    OtherSideUploadedAsOrdinary("its other side was already uploaded as an ordinary transaction"),
}

/**
 * UC-005 BR-005: the stored Bank Transaction [leg], the arriving other side of the Lonely Transfer [transferId] that the
 * Operator decided on, joins it as its second Transfer Leg; the Transfer keeps its Transfer Decision.
 */
data class TransferJoin(val transferId: Long, val leg: Long)

/**
 * UC-005 BR-015: the Transfer Decision of [transfer] that Slussen drops for [reason], with the Transfer Legs it had
 * ([legs]): O-5 removes the Transfer and releases them, and the Decision Journal records it once for each (BR-011).
 */
data class DroppedTransferDecision(val transfer: StoredTransfer, val reason: TransferDropReason, val legs: List<StoredBankTransaction>)

/**
 * What O-5 concludes from the stored data (UC-003 steps 3-4): the Transaction Status of each compared Bank Transaction
 * by its id, and every Transfer that holds among them. A stored Transfer with the decision Proposed that is among the
 * [ComparisonData.transfers] and that none of [transfers] continues no longer holds: O-5 releases its Transfer Legs. A
 * Transfer the Operator decided on is kept as it is stored, except that its arriving other side joins it ([joins],
 * UC-005 BR-005) and that it is removed when its own sides are no longer valid ([dropped], UC-005 BR-015).
 */
data class ComparisonConclusions(
    val statuses: Map<Long, TransactionStatus>,
    val transfers: List<TransferToStore> = emptyList(),
    val joins: List<TransferJoin> = emptyList(),
    val dropped: List<DroppedTransferDecision> = emptyList(),
)
