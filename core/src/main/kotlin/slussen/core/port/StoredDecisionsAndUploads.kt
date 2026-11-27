package slussen.core.port

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
