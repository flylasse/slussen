package slussen.persistence.main

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

// The tables schema version 6 of `slussen.db` adds (build-out plan §3), each exactly as the entity model states its entity
// (ADR-002 §2): an Optional attribute is a column that may be empty, a `Decimal` is stored in thousandths, a `Date` in days
// and a `DateTime` in milliseconds, a `Values:` attribute as its text, and every reference within `slussen.db` is a foreign
// key with an index. Every other constraint of the entity model is a rule of the core.

/**
 * `REVIEW_DECISION` (entity model): the Operator's current choice for one doubtful Bank Transaction. A Bank Transaction has
 * at most one (unique index); the chosen transaction of an Already Present decision is kept by its Budget App identifier,
 * its Budget Account and its amount, `chosen_amount` in thousandths of the currency unit.
 */
@Entity(
    tableName = "review_decision",
    indices = [Index(value = ["bank_transaction_id"], unique = true), Index(value = ["chosen_budget_account_id"])],
    foreignKeys = [
        ForeignKey(entity = BankTransactionEntity::class, parentColumns = ["id"], childColumns = ["bank_transaction_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["chosen_budget_account_id"]),
    ],
)
data class ReviewDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_transaction_id") val bankTransaction: Long,
    @ColumnInfo(name = "choice") val choice: String,
    @ColumnInfo(name = "chosen_budget_app_reference") val chosenBudgetAppReference: String?,
    @ColumnInfo(name = "chosen_budget_account_id") val chosenBudgetAccountId: Long?,
    @ColumnInfo(name = "chosen_amount") val chosenAmount: Long?,
    @ColumnInfo(name = "decided_at") val decidedAt: Instant,
)

/**
 * `UPLOAD_PLAN` (entity model): the reviewed statement of what Slussen will write to one Budget, or to one Bank Account's
 * Budget Account (`bank_account_id` filled); the totals in thousandths of the Budget's currency unit. That at most one is
 * Awaiting Confirmation is a rule of the core (ADR-007 §2).
 */
@Entity(
    tableName = "upload_plan",
    indices = [Index(value = ["budget_id"]), Index(value = ["bank_account_id"])],
    foreignKeys = [
        ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budget_id"]),
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"]),
    ],
)
data class UploadPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "budget_id") val budgetId: Long,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long?,
    @ColumnInfo(name = "prepared_at") val preparedAt: Instant,
    @ColumnInfo(name = "expires_at") val expiresAt: Instant,
    @ColumnInfo(name = "new_count") val newCount: Int,
    @ColumnInfo(name = "match_count") val matchCount: Int,
    @ColumnInfo(name = "counterpart_count") val counterpartCount: Int,
    @ColumnInfo(name = "total_inflow") val totalInflow: Long,
    @ColumnInfo(name = "total_outflow") val totalOutflow: Long,
    @ColumnInfo(name = "state") val state: String,
)

/**
 * `ELIGIBLE_TRANSACTION` (entity model): a Bank Transaction that an Upload Plan will write, keyed by both (ADR-002 §2), so
 * a plan states a Bank Transaction at most once.
 */
@Entity(
    tableName = "eligible_transaction",
    primaryKeys = ["upload_plan_id", "bank_transaction_id"],
    indices = [Index(value = ["bank_transaction_id"])],
    foreignKeys = [
        ForeignKey(entity = UploadPlanEntity::class, parentColumns = ["id"], childColumns = ["upload_plan_id"]),
        ForeignKey(entity = BankTransactionEntity::class, parentColumns = ["id"], childColumns = ["bank_transaction_id"]),
    ],
)
data class EligibleTransactionEntity(
    @ColumnInfo(name = "upload_plan_id") val uploadPlanId: Long,
    @ColumnInfo(name = "bank_transaction_id") val bankTransaction: Long,
    @ColumnInfo(name = "upload_kind") val uploadKind: String,
    @ColumnInfo(name = "clear_counterpart") val clearCounterpart: Boolean,
)

/**
 * `SENT_RECORD` (entity model): Slussen's own durable record of one transaction it sent to the Budget App, the basis of
 * exactly once; `amount` in thousandths of the currency unit, `booked_date` in days. Within one Budget Account no two share
 * an `import_identifier` (unique index, ADR-002 §4): a retry updates its record and never inserts a second one.
 */
@Entity(
    tableName = "sent_record",
    indices = [
        Index(value = ["budget_account_id", "import_identifier"], unique = true),
        Index(value = ["bank_transaction_id"]),
        Index(value = ["upload_plan_id"]),
    ],
    foreignKeys = [
        ForeignKey(entity = BankTransactionEntity::class, parentColumns = ["id"], childColumns = ["bank_transaction_id"]),
        ForeignKey(entity = UploadPlanEntity::class, parentColumns = ["id"], childColumns = ["upload_plan_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["budget_account_id"]),
    ],
)
data class SentRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_transaction_id") val bankTransaction: Long,
    @ColumnInfo(name = "upload_plan_id") val uploadPlanId: Long,
    @ColumnInfo(name = "budget_account_id") val budgetAccountId: Long,
    @ColumnInfo(name = "import_identifier") val importIdentifier: String,
    @ColumnInfo(name = "occurrence") val occurrence: Int,
    @ColumnInfo(name = "booked_date") val bookedDate: LocalDate,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "sent_as_transfer") val sentAsTransfer: Boolean,
    @ColumnInfo(name = "sent_at") val sentAt: Instant,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "budget_app_reference") val budgetAppReference: String?,
)

/**
 * `TRANSFER_COUNTERPART` (entity model): the other side of a Transfer that the Budget App created itself when Slussen sent
 * one Transfer Leg as a linked transfer. A Sent Record creates at most one, and the Budget App's identifier names one
 * (unique indices).
 */
@Entity(
    tableName = "transfer_counterpart",
    indices = [
        Index(value = ["sent_record_id"], unique = true),
        Index(value = ["budget_app_reference"], unique = true),
        Index(value = ["budget_account_id"]),
        Index(value = ["settled_by_bank_transaction_id"]),
    ],
    foreignKeys = [
        ForeignKey(entity = SentRecordEntity::class, parentColumns = ["id"], childColumns = ["sent_record_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["budget_account_id"]),
        ForeignKey(entity = BankTransactionEntity::class, parentColumns = ["id"], childColumns = ["settled_by_bank_transaction_id"]),
    ],
)
data class TransferCounterpartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sent_record_id") val sentRecordId: Long,
    @ColumnInfo(name = "budget_account_id") val budgetAccountId: Long,
    @ColumnInfo(name = "budget_app_reference") val budgetAppReference: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "cleared_at") val clearedAt: Instant?,
    @ColumnInfo(name = "settled_by_bank_transaction_id") val settledByBankTransaction: Long?,
)

/**
 * The texts of `upload_plan_item.write_kind` (ADR-002 §2): the three kinds of write of ADR-007 §4, and the header line of
 * a plan, which holds the `state_version` and the `change_cursor` the plan captured and no write.
 */
enum class UploadPlanItemKind(val text: String) {
    Header("Header"),
    Create("Create"),
    LinkedTransfer("LinkedTransfer"),
    ClearCounterpart("ClearCounterpart"),
}

/**
 * The technical table `upload_plan_item` of ADR-002 §2: the immutable write set of an Upload Plan (ADR-007 §4), one line
 * per write at its `position`, inserted only when the plan is prepared (O-6) and never updated. The line at
 * [HEADER_POSITION] is the header: `write_kind` Header with the `state_version` of `coordinator_state` and the
 * `change_cursor` of the plan's Budget as captured at preparation, which the check before writing compares (ADR-007 §6).
 * Every value a line does not have is empty: a header has no payload, a clearing line no Import Identifier, and only a
 * retry names the Sent Record it reuses with the outcome that record had.
 */
@Entity(
    tableName = "upload_plan_item",
    primaryKeys = ["upload_plan_id", "position"],
    indices = [
        Index(value = ["bank_transaction_id"]),
        Index(value = ["budget_account_id"]),
        Index(value = ["transfer_budget_account_id"]),
        Index(value = ["transfer_counterpart_id"]),
        Index(value = ["reused_sent_record_id"]),
    ],
    foreignKeys = [
        ForeignKey(entity = UploadPlanEntity::class, parentColumns = ["id"], childColumns = ["upload_plan_id"]),
        ForeignKey(entity = BankTransactionEntity::class, parentColumns = ["id"], childColumns = ["bank_transaction_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["budget_account_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["transfer_budget_account_id"]),
        ForeignKey(entity = TransferCounterpartEntity::class, parentColumns = ["id"], childColumns = ["transfer_counterpart_id"]),
        ForeignKey(entity = SentRecordEntity::class, parentColumns = ["id"], childColumns = ["reused_sent_record_id"]),
    ],
)
data class UploadPlanItemEntity(
    @ColumnInfo(name = "upload_plan_id") val uploadPlanId: Long,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "write_kind") val writeKind: String,
    @ColumnInfo(name = "bank_transaction_id") val bankTransaction: Long? = null,
    @ColumnInfo(name = "budget_account_id") val budgetAccountId: Long? = null,
    @ColumnInfo(name = "import_identifier") val importIdentifier: String? = null,
    @ColumnInfo(name = "occurrence") val occurrence: Int? = null,
    @ColumnInfo(name = "booked_date") val bookedDate: LocalDate? = null,
    @ColumnInfo(name = "amount") val amount: Long? = null,
    @ColumnInfo(name = "payee_sent") val payeeSent: String? = null,
    @ColumnInfo(name = "memo_sent") val memoSent: String? = null,
    @ColumnInfo(name = "cleared") val cleared: Boolean? = null,
    @ColumnInfo(name = "transfer_budget_account_id") val transferBudgetAccountId: Long? = null,
    @ColumnInfo(name = "clear_counterpart") val clearCounterpart: Boolean? = null,
    @ColumnInfo(name = "transfer_counterpart_id") val transferCounterpartId: Long? = null,
    @ColumnInfo(name = "counterpart_reference") val counterpartReference: String? = null,
    @ColumnInfo(name = "reused_sent_record_id") val reusedSentRecordId: Long? = null,
    @ColumnInfo(name = "reused_outcome") val reusedOutcome: String? = null,
    @ColumnInfo(name = "state_version") val stateVersion: Long? = null,
    @ColumnInfo(name = "change_cursor") val changeCursor: String? = null,
) {
    companion object {
        /** The position of the header line of every plan; the writes follow from 1. */
        const val HEADER_POSITION = 0
    }
}

/**
 * `TRANSACTION_FILE` (entity model): a file of transactions the Operator imported for one Bank Account, taken in as a
 * whole or rejected as a whole (UC-002 BR-001).
 */
@Entity(
    tableName = "transaction_file",
    indices = [Index(value = ["bank_account_id"]), Index(value = ["file_format_id"])],
    foreignKeys = [
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"]),
        ForeignKey(entity = FileFormatEntity::class, parentColumns = ["id"], childColumns = ["file_format_id"]),
    ],
)
data class TransactionFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long,
    @ColumnInfo(name = "file_format_id") val fileFormatId: Long,
    @ColumnInfo(name = "imported_at") val importedAt: Instant,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "transaction_count") val transactionCount: Int,
    @ColumnInfo(name = "rejection_reason") val rejectionReason: String?,
)
