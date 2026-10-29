package slussen.core.port

import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.value.Amount
import java.time.Instant
import java.time.LocalDate

/** The `Values:` of `BUDGET_APP_TRANSACTION.entered_by`: how the transaction came into the Budget App. */
enum class EnteredBy(val text: String) {
    TypedEntry("Typed Entry"),
    Slussen("Slussen"),
    SlussenUnsupportedFormat("Slussen Unsupported Format"),
    OtherImportPath("Other Import Path"),
    TransferCounterpart("Transfer Counterpart"),
}

/** The `Values:` of `BUDGET_APP_TRANSACTION.state`. */
enum class BudgetAppTransactionState(val text: String) {
    Present("Present"),
    Deleted("Deleted"),
    Merged("Merged"),
}

/** `BUDGET_APP_TRANSACTION` (entity model) as stored: Slussen's copy of one transaction as the Budget App last reported it. */
data class StoredBudgetAppTransaction(
    val id: Long,
    val budgetAccountId: Long,
    val budgetAppReference: String,
    val transactionDate: LocalDate,
    val amount: Amount,
    val payee: String?,
    val memo: String?,
    val importIdentifier: String?,
    val enteredBy: EnteredBy,
    val transferLinkReference: String?,
    val mergedIntoReference: String?,
    val cleared: Boolean,
    val approved: Boolean,
    val state: BudgetAppTransactionState,
)

/** One Budget App Transaction as O-4 stores it, upserted by [budgetAppReference] (ADR-002 §3). */
data class BudgetAppTransactionToStore(
    val budgetAccountId: Long,
    val budgetAppReference: String,
    val transactionDate: LocalDate,
    val amount: Amount,
    val payee: String?,
    val memo: String?,
    val importIdentifier: String?,
    val enteredBy: EnteredBy,
    val transferLinkReference: String?,
    val mergedIntoReference: String?,
    val cleared: Boolean,
    val approved: Boolean,
    val state: BudgetAppTransactionState,
)

/**
 * What one read of one Budget brought (UC-003 step 1), for O-4: the changed Budget App Transactions as the plug-in read
 * them, the next cursor, the balance of each Budget Account by its reference, and the time of the read. [startedFrom]
 * is the cursor the read started from, null for the first read of the Budget (BR-016).
 */
data class BudgetReadToStore(
    val budgetId: Long,
    val startedFrom: ChangeCursor?,
    val transactions: List<BudgetAppTransactionRead>,
    val next: ChangeCursor,
    val balances: Map<String, Amount>,
    val readAt: Instant,
)

/** What O-4 did with a read. */
sealed interface ChangeSetApplied {
    /** The read is stored with its cursor; [transactions] Budget App Transactions were written. */
    data class Stored(val transactions: Int) : ChangeSetApplied

    /** The stored cursor is no longer the one the read started from: nothing is stored. */
    data object CursorMoved : ChangeSetApplied
}

/**
 * O-4 `BudgetAppCopyStore.applyChangeSet` (ADR-002 §3) and the reads of the stored copy, in `slussen.db`. The copy and the
 * cursor are stored together, so that nothing the Budget App reported is lost when Slussen stops between two steps
 * (UC-003 BR-020).
 */
interface BudgetAppCopyStore {
    /** The cursor the last read of [budgetId] stored, or null before its first read (UC-003 BR-016). */
    suspend fun changeCursorOf(budgetId: Long): ChangeCursor?

    /**
     * Forgets the cursor of [budgetId], so that its next read covers the read period again (UC-003 BR-016, S-T-18): run
     * before a served Budget gains a Budget Account, whose earlier transactions no cursor-based read would bring.
     */
    suspend fun forgetCursor(budgetId: Long)

    /**
     * O-4, all or nothing: only when the stored cursor of the Budget still equals [BudgetReadToStore.startedFrom], the
     * changed Budget App Transactions upserted by their reference with every field and their `entered_by` as the core
     * decides it ([slussen.core.comparison.BudgetAppCopy]), the balances of the Budget's stored Budget Accounts,
     * `budget.last_read_at`, the next cursor, and `coordinator_state.comparison_pending` set (ADR-007 §3a).
     */
    suspend fun applyChangeSet(read: BudgetReadToStore): ChangeSetApplied

    /** The stored copy of the Budget App Transactions of [budgetId]'s Budget Accounts. */
    suspend fun budgetAppTransactionsOf(budgetId: Long): List<StoredBudgetAppTransaction>
}

/** Everything O-5 computes the Transaction Statuses from: stored data only (ADR-002 §3). */
data class ComparisonData(
    /** The Budgets the Comparison concludes for, with their Budget Accounts and the time of their last read. */
    val budgets: List<StoredBudget>,
    /** The Bank Accounts mapped into those Budgets' Budget Accounts. */
    val bankAccounts: List<StoredBankAccount>,
    /** The stored Bank Transactions of those Bank Accounts. */
    val bankTransactions: List<StoredBankTransaction>,
    /** The stored copy of the Budget App Transactions of those Budgets' Budget Accounts. */
    val budgetAppTransactions: List<StoredBudgetAppTransaction>,
)

/**
 * A Transaction Status that O-5 changed, for the Decision Journal (UC-003 step 6): [storedBankTransaction] is the id of the
 * stored Bank Transaction; [from] is null when it was not compared yet.
 */
data class StatusChange(
    val storedBankTransaction: Long,
    val bankAccountId: Long,
    val importIdentifier: String,
    val from: TransactionStatus?,
    val to: TransactionStatus,
)

/** O-5 `ComparisonStore.applyComparison` (ADR-002 §3) and the Comparison-pending marker (ADR-007 §3a). */
interface ComparisonStore {
    /** Whether a Comparison's read is stored (O-4) and its conclusions are not (O-5). */
    suspend fun comparisonPending(): Boolean

    /**
     * O-5, all or nothing: reads the [ComparisonData] of [budgetIds] from storage, computes the Transaction Statuses with
     * [conclude] (the core's rules, applied inside the transaction to what is stored), writes every status that changed,
     * and clears `comparison_pending`. Returns the changes.
     */
    suspend fun applyComparison(budgetIds: Set<Long>, conclude: (ComparisonData) -> Map<Long, TransactionStatus>): List<StatusChange>
}
