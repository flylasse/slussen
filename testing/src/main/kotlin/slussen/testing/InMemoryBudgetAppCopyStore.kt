package slussen.testing

import slussen.core.comparison.BudgetAppCopy
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.StatusChange
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.TransactionStatus
import slussen.pluginapi.budgetapp.ChangeCursor

/**
 * In-memory fake of O-4 [BudgetAppCopyStore] and O-5 [ComparisonStore] (ADR-002 §3), all or nothing like the real ones,
 * on the Budgets of [connections] and the Bank Transactions of [transactions]. A test makes O-5 fail with
 * [failsToConclude], as a process that stops between O-4 and O-5 would leave it (ADR-007 §3a).
 */
class InMemoryBudgetAppCopyStore(
    private val connections: InMemoryConnectionStore,
    private val transactions: InMemoryImportStatusStore,
) : BudgetAppCopyStore, ComparisonStore {
    val copy = mutableListOf<StoredBudgetAppTransaction>()
    val cursors = mutableMapOf<Long, ChangeCursor>()
    var pending: Boolean = false
    var failsToConclude: Boolean = false
    private var nextId = 1L

    override suspend fun changeCursorOf(budgetId: Long): ChangeCursor? = cursors[budgetId]

    override suspend fun forgetCursor(budgetId: Long) {
        cursors.remove(budgetId)
    }

    override suspend fun applyChangeSet(read: BudgetReadToStore): ChangeSetApplied {
        if (cursors[read.budgetId] != read.startedFrom) return ChangeSetApplied.CursorMoved
        val budget = connections.budgetList.single { it.id == read.budgetId }
        val accountIds = budget.budgetAccounts.map { it.id }.toSet()
        val records = BudgetAppCopy.toStore(read.transactions, budget.budgetAccounts, copy.filter { it.budgetAccountId in accountIds })
        records.forEach { record ->
            val index = copy.indexOfFirst { it.budgetAppReference == record.budgetAppReference }
            val stored = StoredBudgetAppTransaction(
                id = copy.getOrNull(index)?.id ?: nextId++,
                budgetAccountId = record.budgetAccountId,
                budgetAppReference = record.budgetAppReference,
                transactionDate = record.transactionDate,
                amount = record.amount,
                payee = record.payee,
                memo = record.memo,
                importIdentifier = record.importIdentifier,
                enteredBy = record.enteredBy,
                transferLinkReference = record.transferLinkReference,
                mergedIntoReference = record.mergedIntoReference,
                cleared = record.cleared,
                approved = record.approved,
                state = record.state,
            )
            if (index < 0) copy += stored else copy[index] = stored
        }
        connections.budgetList.replaceAll { stored ->
            if (stored.id != read.budgetId) {
                stored
            } else {
                stored.copy(
                    lastReadAt = read.readAt,
                    budgetAccounts = stored.budgetAccounts.map { account ->
                        read.balances[account.budgetAppReference]?.let { account.copy(balance = it, balanceReadAt = read.readAt) } ?: account
                    },
                )
            }
        }
        cursors[read.budgetId] = read.next
        pending = true
        return ChangeSetApplied.Stored(records.size)
    }

    override suspend fun budgetAppTransactionsOf(budgetId: Long): List<StoredBudgetAppTransaction> {
        val accountIds = connections.budgetList.single { it.id == budgetId }.budgetAccounts.map { it.id }.toSet()
        return copy.filter { it.budgetAccountId in accountIds }
    }

    override suspend fun comparisonPending(): Boolean = pending

    override suspend fun applyComparison(
        budgetIds: Set<Long>,
        conclude: (ComparisonData) -> Map<Long, TransactionStatus>,
    ): List<StatusChange> {
        if (failsToConclude) throw IllegalStateException("The invented process of this test stops before O-5 is committed.")
        val budgets = connections.budgetList.filter { it.id in budgetIds }
        val accountIds = budgets.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        val bankAccounts = connections.bankAccountList.filter { it.mapping?.budgetAccountId in accountIds }
        val bankAccountIds = bankAccounts.map { it.id }.toSet()
        val data = ComparisonData(
            budgets = budgets,
            bankAccounts = bankAccounts,
            bankTransactions = transactions.bankTransactions.filter { it.bankAccountId in bankAccountIds },
            budgetAppTransactions = copy.filter { it.budgetAccountId in accountIds },
        )
        val statuses = conclude(data)
        val changes = data.bankTransactions.mapNotNull { stored ->
            val status = statuses[stored.id] ?: return@mapNotNull null
            if (status == stored.status) null else StatusChange(stored.id, stored.bankAccountId, stored.importIdentifier, stored.status, status)
        }
        changes.forEach { change ->
            transactions.bankTransactions.replaceAll { if (it.id == change.storedBankTransaction) it.copy(status = change.to) else it }
        }
        pending = false
        return changes
    }
}
