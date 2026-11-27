package slussen.persistence.main

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import slussen.core.fileimport.FileCounting
import slussen.core.fileimport.TransactionFileCounting
import slussen.core.identity.ImportIdentifierInput
import slussen.core.port.ReceivedBankTransaction
import slussen.core.port.Reception
import slussen.core.port.TransactionFileOutcome
import slussen.core.port.TransactionFileToImport
import slussen.core.port.WholeImport

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
 * `review_decision`: the reads and writes O-13 `ReviewStore.decide` is built on (UC-004, ADR-002 §3), with the Bank
 * Transaction a decision is about and the Unreadable Transaction the Operator defers (UC-004 A9). Each write of a Review
 * Decision raises `state_version` in its own transaction, also inside a larger one; the status of an Unreadable
 * Transaction, which is never in an Upload Plan, does not.
 */
@Dao
abstract class ReviewDecisionDao : StateVersionDao {
    @Query("SELECT * FROM review_decision ORDER BY id")
    abstract suspend fun all(): List<ReviewDecisionEntity>

    /** The Review Decisions of the Bank Transactions of [bankAccountId]. */
    @Query("SELECT * FROM review_decision WHERE bank_transaction_id IN (SELECT id FROM bank_transaction WHERE bank_account_id = :bankAccountId) ORDER BY id")
    abstract suspend fun ofBankAccount(bankAccountId: Long): List<ReviewDecisionEntity>

    @Query("SELECT * FROM bank_transaction WHERE id = :id")
    abstract suspend fun bankTransaction(id: Long): BankTransactionEntity?

    @Query("SELECT * FROM unreadable_transaction WHERE id = :id")
    abstract suspend fun unreadableTransaction(id: Long): UnreadableTransactionEntity?

    /** Gives the Unreadable Transaction [id] the status [status]; returns how many were changed, 0 or 1. */
    @Query("UPDATE unreadable_transaction SET status = :status WHERE id = :id")
    abstract suspend fun storeUnreadableStatus(id: Long, status: String): Int

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

/**
 * `transaction_file` and the Bank Transactions a Transaction File brings (UC-002): the plain reads and writes, and O-3
 * `FileImportStore.importWhole` (ADR-002 §3), all or nothing in one transaction of `slussen.db`.
 */
@Dao
abstract class TransactionFileDao : StateVersionDao {
    @Insert
    abstract suspend fun insert(file: TransactionFileEntity): Long

    @Query("SELECT * FROM transaction_file ORDER BY id")
    abstract suspend fun all(): List<TransactionFileEntity>

    /** The Transaction Files of one Bank Account, the latest import first. */
    @Query("SELECT * FROM transaction_file WHERE bank_account_id = :bankAccountId ORDER BY imported_at DESC, id DESC")
    abstract suspend fun of(bankAccountId: Long): List<TransactionFileEntity>

    /**
     * For each Bank Account, its latest Transaction File, and, when that one was rejected, also its latest imported one
     * (UC-002 BR-006), latest first within each Bank Account.
     */
    @Query(
        "SELECT * FROM transaction_file AS f WHERE f.id = (SELECT l.id FROM transaction_file AS l WHERE l.bank_account_id = f.bank_account_id " +
            "ORDER BY l.imported_at DESC, l.id DESC LIMIT 1) OR f.id = (SELECT i.id FROM transaction_file AS i WHERE i.bank_account_id = " +
            "f.bank_account_id AND i.outcome = 'Imported' ORDER BY i.imported_at DESC, i.id DESC LIMIT 1) " +
            "ORDER BY f.bank_account_id, f.imported_at DESC, f.id DESC",
    )
    abstract suspend fun latest(): List<TransactionFileEntity>

    @Query("SELECT * FROM bank_transaction WHERE bank_account_id = :bankAccountId ORDER BY id")
    abstract suspend fun bankTransactionsOf(bankAccountId: Long): List<BankTransactionEntity>

    @Insert
    protected abstract suspend fun insertBankTransactions(transactions: List<BankTransactionEntity>)

    /**
     * O-3 (ADR-002 §3; UC-002 BR-001, BR-003, A6): reads the Bank Transactions of the file's Bank Account as stored now,
     * counts the file against them by the core's rule ([TransactionFileCounting], the one the fake of `:testing` applies)
     * with [identify] for the Import Identifiers, and, unless that finds a collision, inserts every new Bank Transaction,
     * raises `state_version` when one is new (ADR-007 §6) and inserts the `transaction_file` record Imported last, all in
     * this one transaction. A collision writes nothing; any failure rolls all of it back. No stored Bank Transaction is
     * changed.
     */
    @Transaction
    open suspend fun importWhole(file: TransactionFileToImport, identify: (ImportIdentifierInput) -> String): WholeImport {
        val stored = bankTransactionsOf(file.bankAccountId).map { it.stored() }
        val counting = TransactionFileCounting.count(stored, file, identify)
        if (counting is FileCounting.Collision) return WholeImport.Collision(counting.importIdentifier)
        val inserts = (counting as FileCounting.Counted).inserts
        if (inserts.isNotEmpty()) {
            insertBankTransactions(inserts.map { it.entity(file.bankAccountId) })
            raiseStateVersion()
        }
        insert(
            TransactionFileEntity(
                bankAccountId = file.bankAccountId,
                fileFormatId = file.fileFormatId,
                importedAt = file.importedAt,
                outcome = TransactionFileOutcome.Imported.text,
                transactionCount = file.transactions.size,
                rejectionReason = null,
            ),
        )
        return WholeImport.Imported(
            inserts.map { ReceivedBankTransaction(it.importIdentifier, Reception.FirstTime, it.bookedDate, it.amount, it.currency) },
        )
    }
}
