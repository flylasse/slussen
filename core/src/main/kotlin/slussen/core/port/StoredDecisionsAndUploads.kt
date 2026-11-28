package slussen.core.port

import slussen.pluginapi.value.Amount
import java.time.Instant

/** The `Values:` of `REVIEW_DECISION.choice`: what the Operator decided for a doubtful Bank Transaction (UC-004). */
enum class ReviewChoice(val text: String) {
    AlreadyPresent("Already Present"),
    ImportAnyway("Import Anyway"),
    Defer("Defer"),
    ReUpload("Re-upload"),
}

/** The `Values:` of `UPLOAD_PLAN.state`: where an Upload Plan stands (UC-006). */
enum class UploadPlanState(val text: String) {
    AwaitingConfirmation("Awaiting Confirmation"),
    Confirmed("Confirmed"),
    Cancelled("Cancelled"),
    Expired("Expired"),
    ChangedBeforeWriting("Changed Before Writing"),
}

/** The `Values:` of `ELIGIBLE_TRANSACTION.upload_kind`: how an Eligible Transaction is written (UC-006). */
enum class UploadKind(val text: String) {
    New("New"),
    MatchForTypedEntry("Match For Typed Entry"),
    LinkedTransfer("Linked Transfer"),
}

/** The `Values:` of `SENT_RECORD.outcome`: what the Budget App confirmed of one send (UC-006). */
enum class SentRecordOutcome(val text: String) {
    NotYetKnown("Not Yet Known"),
    Written("Written"),
    AlreadyPresent("Already Present"),
    Rejected("Rejected"),
    Unknown("Unknown"),
}

/** The `Values:` of `TRANSFER_COUNTERPART.state`: where a Transfer Counterpart stands (UC-005, UC-006). */
enum class TransferCounterpartState(val text: String) {
    Uncleared("Uncleared"),
    Cleared("Cleared"),
    ChangedInBudgetApp("Changed In Budget App"),
}

/**
 * The Budget App Transaction an Already Present decision names (UC-004 BR-002), as `REVIEW_DECISION` keeps it: its
 * identifier in the Budget App, the Budget Account that held it and its amount when the Operator decided, so that UC-003
 * BR-009 can recheck the decision.
 */
data class ChosenBudgetAppTransaction(val budgetAppReference: String, val budgetAccountId: Long, val amount: Amount)

/**
 * `REVIEW_DECISION` (entity model) as stored: the Operator's current choice for the Bank Transaction
 * [storedBankTransaction]. [chosen] is filled exactly when [choice] is Already Present.
 */
data class StoredReviewDecision(
    val storedBankTransaction: Long,
    val choice: ReviewChoice,
    val chosen: ChosenBudgetAppTransaction?,
    /** The time the Operator made or last changed the decision. */
    val decidedAt: Instant,
)

/**
 * `SENT_RECORD` (entity model) as far as the rules of the Transfers read it: that Slussen sent the Bank Transaction
 * [storedBankTransaction] to [budgetAccountId], as a linked transfer ([sentAsTransfer]) or as an ordinary transaction, and
 * what the Budget App confirmed of it. The Uploads, which write Sent Records, come with their own phases; until then none
 * is stored.
 */
data class StoredSentRecord(
    val storedBankTransaction: Long,
    val budgetAccountId: Long,
    val sentAsTransfer: Boolean,
    val outcome: SentRecordOutcome,
)
