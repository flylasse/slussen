package slussen.persistence.main

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * Raises `coordinator_state.state_version`, the version of everything an Upload Plan was prepared from (ADR-002 §3,
 * ADR-007 §6 step 2): the Bank Transactions and their statuses, the Review Decisions and Transfer Decisions, the Account
 * Mappings, the Operator Settings, the Transfer Descriptions, the stored copy of the Budget App, the Sent Record, the
 * Transfer Counterparts, the served Budgets and the Bank Accounts. Every storage operation that changes one of them calls
 * it inside its own transaction, so that a confirmation finds the change and writes nothing (UC-006 A5).
 */
interface StateVersionDao {
    @Query("UPDATE coordinator_state SET state_version = state_version + 1 WHERE id = 1")
    suspend fun raiseStateVersion()
}

/**
 * `review_decision`: the plain reads and writes the Review Decisions are built on (UC-004; O-13 comes with its phase).
 * Each write raises `state_version` in its own transaction, also inside a larger one.
 */
@Dao
abstract class ReviewDecisionDao : StateVersionDao {
    @Query("SELECT * FROM review_decision ORDER BY id")
    abstract suspend fun all(): List<ReviewDecisionEntity>

    @Query("SELECT * FROM review_decision WHERE bank_transaction_id = :bankTransaction")
    abstract suspend fun of(bankTransaction: Long): ReviewDecisionEntity?

    @Insert
    protected abstract suspend fun insert(decision: ReviewDecisionEntity): Long

    @Update
    protected abstract suspend fun update(decision: ReviewDecisionEntity): Int

    @Query("DELETE FROM review_decision WHERE bank_transaction_id = :bankTransaction")
    protected abstract suspend fun delete(bankTransaction: Long): Int

    /**
     * The decision of its Bank Transaction, in place of the one stored for it, which keeps its `id`; returns the `id`.
     * A Bank Transaction has at most one Review Decision (entity model).
     */
    @Transaction
    open suspend fun store(decision: ReviewDecisionEntity): Long {
        val known = of(decision.bankTransaction)
        val id = if (known == null) insert(decision.copy(id = 0)) else known.id.also { update(decision.copy(id = it)) }
        raiseStateVersion()
        return id
    }

    /** Removes the decision of [bankTransaction]; true when there was one. */
    @Transaction
    open suspend fun clear(bankTransaction: Long): Boolean {
        if (delete(bankTransaction) == 0) return false
        raiseStateVersion()
        return true
    }
}

/**
 * `upload_plan`, `eligible_transaction`, `upload_plan_item`, `sent_record` and `transfer_counterpart`: the plain reads and
 * writes the Uploads are built on (UC-006; O-6 to O-9 and O-14 come with their phases). A write to the Sent Record or to a
 * Transfer Counterpart raises `state_version` in its own transaction, also inside a larger one. A plan, its Eligible
 * Transactions and its write set are not what a plan is prepared from, so their writes do not; `upload_plan_item` is only
 * inserted and read, never updated (ADR-007 §4).
 */
@Dao
abstract class UploadDao : StateVersionDao {
    @Insert
    abstract suspend fun insertPlan(plan: UploadPlanEntity): Long

    @Query("SELECT * FROM upload_plan WHERE id = :id")
    abstract suspend fun plan(id: Long): UploadPlanEntity?

    @Query("SELECT * FROM upload_plan ORDER BY id")
    abstract suspend fun plans(): List<UploadPlanEntity>

    @Query("SELECT * FROM upload_plan WHERE state = :state ORDER BY id")
    abstract suspend fun plansIn(state: String): List<UploadPlanEntity>

    /** Sets the state of plan [id] to [to], only where it is still [from]; returns how many plans changed, 0 or 1. */
    @Query("UPDATE upload_plan SET state = :to WHERE id = :id AND state = :from")
    abstract suspend fun moveState(id: Long, from: String, to: String): Int

    @Insert
    abstract suspend fun insertEligible(transactions: List<EligibleTransactionEntity>)

    @Query("SELECT * FROM eligible_transaction WHERE upload_plan_id = :uploadPlanId ORDER BY bank_transaction_id")
    abstract suspend fun eligibleOf(uploadPlanId: Long): List<EligibleTransactionEntity>

    @Insert
    abstract suspend fun insertItems(items: List<UploadPlanItemEntity>)

    /** The write set of a plan in its order, the header line first. */
    @Query("SELECT * FROM upload_plan_item WHERE upload_plan_id = :uploadPlanId ORDER BY position")
    abstract suspend fun itemsOf(uploadPlanId: Long): List<UploadPlanItemEntity>

    @Query("SELECT * FROM sent_record ORDER BY id")
    abstract suspend fun sentRecords(): List<SentRecordEntity>

    @Query("SELECT * FROM sent_record WHERE id = :id")
    abstract suspend fun sentRecord(id: Long): SentRecordEntity?

    /** The Sent Records of one Bank Transaction, its first send (`occurrence` 0) first. */
    @Query("SELECT * FROM sent_record WHERE bank_transaction_id = :bankTransaction ORDER BY occurrence, id")
    abstract suspend fun sentRecordsOf(bankTransaction: Long): List<SentRecordEntity>

    @Query("SELECT * FROM sent_record WHERE upload_plan_id = :uploadPlanId ORDER BY id")
    abstract suspend fun sentRecordsOfPlan(uploadPlanId: Long): List<SentRecordEntity>

    /** The one Sent Record that carries [importIdentifier] in [budgetAccountId] (entity model `SENT_RECORD`). */
    @Query("SELECT * FROM sent_record WHERE budget_account_id = :budgetAccountId AND import_identifier = :importIdentifier")
    abstract suspend fun sentRecordIn(budgetAccountId: Long, importIdentifier: String): SentRecordEntity?

    @Insert
    protected abstract suspend fun insert(record: SentRecordEntity): Long

    @Update
    protected abstract suspend fun update(record: SentRecordEntity): Int

    /** A new Sent Record; fails, storing nothing, when its Budget Account already holds its Import Identifier. */
    @Transaction
    open suspend fun insertSentRecord(record: SentRecordEntity): Long {
        val id = insert(record)
        raiseStateVersion()
        return id
    }

    /** The stored Sent Record with the `id` of [record], with every value of [record]; true when it was stored. */
    @Transaction
    open suspend fun updateSentRecord(record: SentRecordEntity): Boolean {
        if (update(record) == 0) return false
        raiseStateVersion()
        return true
    }

    @Query("SELECT * FROM transfer_counterpart ORDER BY id")
    abstract suspend fun transferCounterparts(): List<TransferCounterpartEntity>

    @Query("SELECT * FROM transfer_counterpart WHERE sent_record_id = :sentRecordId")
    abstract suspend fun transferCounterpartOf(sentRecordId: Long): TransferCounterpartEntity?

    @Insert
    protected abstract suspend fun insert(counterpart: TransferCounterpartEntity): Long

    @Update
    protected abstract suspend fun update(counterpart: TransferCounterpartEntity): Int

    /** A new Transfer Counterpart; fails, storing nothing, when its Sent Record or its Budget App identifier has one. */
    @Transaction
    open suspend fun insertTransferCounterpart(counterpart: TransferCounterpartEntity): Long {
        val id = insert(counterpart)
        raiseStateVersion()
        return id
    }

    /** The stored Transfer Counterpart with the `id` of [counterpart], with every value of it; true when it was stored. */
    @Transaction
    open suspend fun updateTransferCounterpart(counterpart: TransferCounterpartEntity): Boolean {
        if (update(counterpart) == 0) return false
        raiseStateVersion()
        return true
    }
}

/** `transaction_file`: the plain reads and writes the import of Transaction Files is built on (UC-002; O-3 comes with its phase). */
@Dao
abstract class TransactionFileDao {
    @Insert
    abstract suspend fun insert(file: TransactionFileEntity): Long

    @Query("SELECT * FROM transaction_file ORDER BY id")
    abstract suspend fun all(): List<TransactionFileEntity>

    /** The Transaction Files of one Bank Account, the latest import first. */
    @Query("SELECT * FROM transaction_file WHERE bank_account_id = :bankAccountId ORDER BY imported_at DESC, id DESC")
    abstract suspend fun of(bankAccountId: Long): List<TransactionFileEntity>
}
