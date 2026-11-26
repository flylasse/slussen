package slussen.persistence

import androidx.room.withTransaction
import slussen.core.comparison.BudgetAppCopy
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.BudgetAppTransactionToStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.EnteredBy
import slussen.core.port.StatusChange
import slussen.core.port.StoredBudgetAccount
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferToStore
import slussen.core.port.TransferType
import slussen.persistence.main.BudgetAppTransactionEntity
import slussen.persistence.main.BudgetReadStateEntity
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.TransferDescriptionEntity
import slussen.persistence.main.TransferEntity
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

/** `transfer` read back as the core's [StoredTransfer]; an unknown `Values:` text is unreadable data. */
internal fun TransferEntity.stored() = StoredTransfer(
    id = id,
    outflowBudgetAccountId = outflowBudgetAccountId,
    inflowBudgetAccountId = inflowBudgetAccountId,
    amount = Amount(amount),
    type = valueOfText<TransferType>(transferType, MAIN_PART) { it.text },
    guessed = guessed,
    decision = valueOfText<TransferDecision>(decision, MAIN_PART) { it.text },
    decidedAt = decidedAt,
)

/** `transfer_description` read back as the core's [StoredTransferDescription]. */
internal fun TransferDescriptionEntity.stored() =
    StoredTransferDescription(valueOfText<TransferDescriptionKind>(kind, MAIN_PART) { it.text }, text)

/** A Transfer a Comparison detected for the first time, with the decision Proposed. */
private fun TransferToStore.entity() = TransferEntity(
    outflowBudgetAccountId = outflowBudgetAccountId,
    inflowBudgetAccountId = inflowBudgetAccountId,
    amount = amount.thousandths,
    transferType = type.text,
    guessed = guessed,
    decision = TransferDecision.Proposed.text,
    decidedAt = null,
)

/**
 * O-4 [BudgetAppCopyStore] and O-5 [ComparisonStore] in `slussen.db` (ADR-002 §3), each one transaction on the one
 * database: the stored copy of the Budget App with its cursor, the Transaction Statuses and the Transfers concluded from
 * that copy, and the Comparison-pending marker between the two (ADR-007 §3a).
 */
class RoomComparisonStore(
    private val db: SlussenDatabase,
    private val connections: RoomConnectionStore,
) : BudgetAppCopyStore, ComparisonStore {
    private val dao get() = db.budgetAppCopy()

    override suspend fun changeCursorOf(budgetId: Long): ChangeCursor? = dao.cursorOf(budgetId)?.let(::ChangeCursor)

    override suspend fun owePeriodRead(budgetId: Long) = dao.owePeriodRead(budgetId)

    override suspend fun periodReadOwed(budgetId: Long): Boolean = dao.periodReadOwed(budgetId) ?: false

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
        dao.storeCursor(BudgetReadStateEntity(read.budgetId, read.next.value, periodReadOwed = false))
        dao.storeComparisonPending(true)
        ChangeSetApplied.Stored(records.size)
    }

    override suspend fun budgetAppTransactionsOf(budgetId: Long): List<StoredBudgetAppTransaction> = dao.copyOf(budgetId).map { it.stored() }

    override suspend fun comparisonPending(): Boolean = dao.comparisonPending() ?: false

    override suspend fun applyComparison(
        budgetIds: Set<Long>,
        conclude: (ComparisonData) -> ComparisonConclusions,
    ): List<StatusChange> = db.withTransaction {
        val data = dataOf(budgetIds)
        val conclusions = conclude(data)
        val changes = data.bankTransactions.mapNotNull { stored ->
            val status = conclusions.statuses[stored.id] ?: return@mapNotNull null
            if (status == stored.status) null else StatusChange(stored.id, stored.bankAccountId, stored.importIdentifier, stored.status, status)
        }
        changes.forEach { dao.storeStatus(it.storedBankTransaction, it.to.text) }
        storeTransfers(conclusions.transfers, data.transfers)
        dao.storeComparisonPending(false)
        changes
    }

    override suspend fun comparisonDataOf(budgetIds: Set<Long>): ComparisonData = db.withTransaction { dataOf(budgetIds) }

    /** What O-5 concludes from for [budgetIds], read inside the caller's transaction. */
    private suspend fun dataOf(budgetIds: Set<Long>): ComparisonData {
        val budgets = connections.budgets().filter { it.id in budgetIds }
        val accountIds = budgets.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        val bankAccounts = connections.bankAccounts().filter { it.mapping?.budgetAccountId in accountIds }
        val fetches = db.fetches()
        return ComparisonData(
            budgets = budgets,
            bankAccounts = bankAccounts,
            bankTransactions = bankAccounts.flatMap { account -> fetches.bankTransactionsOf(account.id).map { it.stored() } },
            budgetAppTransactions = budgets.flatMap { budget -> dao.copyOf(budget.id).map { it.stored() } },
            transfers = bankAccounts.flatMap { account -> dao.transfersOf(account.id) }.distinctBy { it.id }.map { it.stored() },
            transferDescriptions = dao.transferDescriptions().map { it.stored() },
        )
    }

    /**
     * The Transfers of O-5, inside its transaction: each of [detected] stored with the decision Proposed, or written over
     * the stored Proposed Transfer it continues, with `bank_transaction.transfer_id` of its Transfer Legs; every one of
     * [stored] with the decision Proposed that none continues no longer holds, so its Transfer Legs are released and it is
     * removed. A Transfer the Operator decided on is never changed here.
     */
    private suspend fun storeTransfers(detected: List<TransferToStore>, stored: List<StoredTransfer>) {
        val proposed = TransferDecision.Proposed.text
        val proposedIds = stored.filter { it.decision == TransferDecision.Proposed }.map { it.id }.toSet()
        val continued = mutableSetOf<Long>()
        detected.forEach { transfer ->
            require(transfer.legs.isNotEmpty()) { "A Transfer has one or two Transfer Legs." }
            require(transfer.continues == null || transfer.continues in proposedIds) { "A Comparison continues only a Transfer with the decision Proposed." }
            val id = transfer.continues?.also {
                dao.updateProposedTransfer(
                    it, transfer.outflowBudgetAccountId, transfer.inflowBudgetAccountId, transfer.amount.thousandths, transfer.type.text,
                    transfer.guessed, proposed,
                )
            } ?: dao.insertTransfer(transfer.entity())
            continued += id
            dao.releaseOtherLegs(id, transfer.legs)
            dao.storeTransferOf(transfer.legs, id)
        }
        stored.filter { it.decision == TransferDecision.Proposed && it.id !in continued }.forEach {
            dao.releaseLegs(it.id)
            dao.deleteProposedTransfer(it.id, proposed)
        }
    }
}
