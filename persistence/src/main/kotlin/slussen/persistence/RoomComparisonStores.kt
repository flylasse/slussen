package slussen.persistence

import androidx.room.withTransaction
import slussen.core.comparison.BudgetAppCopy
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.BudgetAppTransactionToStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.EnteredBy
import slussen.core.port.StatusChange
import slussen.core.port.StoredBudgetAccount
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.TransactionStatus
import slussen.persistence.main.BudgetAppTransactionEntity
import slussen.persistence.main.BudgetReadStateEntity
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.stored
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.value.Amount

private const val MAIN_PART = "slussen.db"

/** `budget_app_transaction` read back as the core's [StoredBudgetAppTransaction]; an unknown `Values:` text is unreadable data. */
internal fun BudgetAppTransactionEntity.stored() = StoredBudgetAppTransaction(
    id = id,
    budgetAccountId = budgetAccountId,
    budgetAppReference = budgetAppReference,
    transactionDate = transactionDate,
    amount = Amount(amount),
    payee = payee,
    memo = memo,
    importIdentifier = importIdentifier,
    enteredBy = valueOfText<EnteredBy>(enteredBy, MAIN_PART) { it.text },
    transferLinkReference = transferLinkReference,
    mergedIntoReference = mergedIntoReference,
    cleared = cleared,
    approved = approved,
    state = valueOfText<BudgetAppTransactionState>(state, MAIN_PART) { it.text },
)

private fun BudgetAppTransactionToStore.entity(id: Long) = BudgetAppTransactionEntity(
    id = id,
    budgetAccountId = budgetAccountId,
    budgetAppReference = budgetAppReference,
    transactionDate = transactionDate,
    amount = amount.thousandths,
    payee = payee,
    memo = memo,
    importIdentifier = importIdentifier,
    enteredBy = enteredBy.text,
    transferLinkReference = transferLinkReference,
    mergedIntoReference = mergedIntoReference,
    cleared = cleared,
    approved = approved,
    state = state.text,
)

/**
 * O-4 [BudgetAppCopyStore] and O-5 [ComparisonStore] in `slussen.db` (ADR-002 §3), each one transaction on the one
 * database: the stored copy of the Budget App with its cursor, the Transaction Statuses concluded from that copy, and
 * the Comparison-pending marker between the two (ADR-007 §3a).
 */
class RoomComparisonStore(
    private val db: SlussenDatabase,
    private val connections: RoomConnectionStore,
) : BudgetAppCopyStore, ComparisonStore {
    private val dao get() = db.budgetAppCopy()

    override suspend fun changeCursorOf(budgetId: Long): ChangeCursor? = dao.cursorOf(budgetId)?.let(::ChangeCursor)

    override suspend fun forgetCursor(budgetId: Long) = dao.forgetCursor(budgetId)

    override suspend fun applyChangeSet(read: BudgetReadToStore): ChangeSetApplied = db.withTransaction {
        // UC-003 BR-020: the copy and the point up to which the Budget App was read are stored together, only from the cursor the read started from.
        if (dao.cursorOf(read.budgetId) != read.startedFrom?.value) return@withTransaction ChangeSetApplied.CursorMoved
        val accounts = dao.budgetAccountsOf(read.budgetId)
        val budgetAccounts = accounts.map {
            StoredBudgetAccount(it.id, it.budgetId, it.budgetAppReference, it.name, it.manuallyKept, it.balance?.let(::Amount), it.balanceReadAt)
        }
        val records = BudgetAppCopy.toStore(read.transactions, budgetAccounts, dao.copyOf(read.budgetId).map { it.stored() })
        records.forEach { record ->
            val known = dao.byReference(record.budgetAppReference)
            if (known == null) dao.insert(record.entity(id = 0)) else dao.update(record.entity(known.id))
        }
        accounts.forEach { account ->
            read.balances[account.budgetAppReference]?.let { dao.storeBalance(account.id, it.thousandths, read.readAt) }
        }
        dao.storeLastReadAt(read.budgetId, read.readAt)
        dao.storeCursor(BudgetReadStateEntity(read.budgetId, read.next.value))
        dao.storeComparisonPending(true)
        ChangeSetApplied.Stored(records.size)
    }

    override suspend fun budgetAppTransactionsOf(budgetId: Long): List<StoredBudgetAppTransaction> = dao.copyOf(budgetId).map { it.stored() }

    override suspend fun comparisonPending(): Boolean = dao.comparisonPending() ?: false

    override suspend fun applyComparison(
        budgetIds: Set<Long>,
        conclude: (ComparisonData) -> Map<Long, TransactionStatus>,
    ): List<StatusChange> = db.withTransaction {
        val budgets = connections.budgets().filter { it.id in budgetIds }
        val accountIds = budgets.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        val bankAccounts = connections.bankAccounts().filter { it.mapping?.budgetAccountId in accountIds }
        val fetches = db.fetches()
        val data = ComparisonData(
            budgets = budgets,
            bankAccounts = bankAccounts,
            bankTransactions = bankAccounts.flatMap { account -> fetches.bankTransactionsOf(account.id).map { it.stored() } },
            budgetAppTransactions = budgets.flatMap { budget -> dao.copyOf(budget.id).map { it.stored() } },
        )
        val statuses = conclude(data)
        val changes = data.bankTransactions.mapNotNull { stored ->
            val status = statuses[stored.id] ?: return@mapNotNull null
            if (status == stored.status) null else StatusChange(stored.id, stored.bankAccountId, stored.importIdentifier, stored.status, status)
        }
        changes.forEach { dao.storeStatus(it.storedBankTransaction, it.to.text) }
        dao.storeComparisonPending(false)
        changes
    }
}
