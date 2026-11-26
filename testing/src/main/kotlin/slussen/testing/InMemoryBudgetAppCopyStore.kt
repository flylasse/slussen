package slussen.testing

import slussen.core.comparison.BudgetAppCopy
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.StatusChange
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.pluginapi.budgetapp.ChangeCursor

/**
 * In-memory fake of O-4 [BudgetAppCopyStore] and O-5 [ComparisonStore] (ADR-002 §3), all or nothing like the real ones,
 * on the Budgets of [connections] and the Bank Transactions of [transactions], with the stored Transfers ([transfers])
 * and the description lists ([transferDescriptions], which start as the default lists of UC-010 BR-007, as the real
 * storage does). A test makes O-5 fail with [failsToConclude], as a process that stops between O-4 and O-5 would leave
 * it (ADR-007 §3a).
 */
class InMemoryBudgetAppCopyStore(
    private val connections: InMemoryConnectionStore,
    private val transactions: InMemoryImportStatusStore,
) : BudgetAppCopyStore, ComparisonStore {
    val copy = mutableListOf<StoredBudgetAppTransaction>()
    val cursors = mutableMapOf<Long, ChangeCursor>()
    val periodOwed = mutableSetOf<Long>()
    val transfers = mutableListOf<StoredTransfer>()
    val transferDescriptions: MutableList<StoredTransferDescription> = DEFAULT_TRANSFER_DESCRIPTIONS.toMutableList()
    private var nextTransferId = 9000L
    var pending: Boolean = false
    var failsToConclude: Boolean = false
    private var nextId = 1L

    override suspend fun changeCursorOf(budgetId: Long): ChangeCursor? = cursors[budgetId]

    override suspend fun owePeriodRead(budgetId: Long) {
        if (budgetId in cursors) periodOwed += budgetId
    }

    override suspend fun periodReadOwed(budgetId: Long): Boolean = budgetId in periodOwed

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
        periodOwed -= read.budgetId
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
        conclude: (ComparisonData) -> ComparisonConclusions,
    ): List<StatusChange> {
        if (failsToConclude) throw IllegalStateException("The invented process of this test stops before O-5 is committed.")
        val data = dataOf(budgetIds)
        val conclusions = conclude(data)
        val proposedIds = data.transfers.filter { it.decision == TransferDecision.Proposed }.map { it.id }.toSet()
        conclusions.transfers.forEach { detected ->
            require(detected.legs.isNotEmpty()) { "A Transfer has one or two Transfer Legs." }
            require(detected.continues == null || detected.continues in proposedIds) { "A Comparison continues only a Transfer with the decision Proposed." }
        }
        val changes = data.bankTransactions.mapNotNull { stored ->
            val status = conclusions.statuses[stored.id] ?: return@mapNotNull null
            if (status == stored.status) null else StatusChange(stored.id, stored.bankAccountId, stored.importIdentifier, stored.status, status)
        }
        changes.forEach { change ->
            transactions.bankTransactions.replaceAll { if (it.id == change.storedBankTransaction) it.copy(status = change.to) else it }
        }
        val continued = mutableSetOf<Long>()
        conclusions.transfers.forEach { detected ->
            val id = detected.continues ?: nextTransferId++
            continued += id
            val stored = StoredTransfer(
                id, detected.outflowBudgetAccountId, detected.inflowBudgetAccountId, detected.amount, detected.type, detected.guessed,
                TransferDecision.Proposed, decidedAt = null,
            )
            val index = transfers.indexOfFirst { it.id == id }
            if (index < 0) transfers += stored else transfers[index] = stored
            transactions.bankTransactions.replaceAll {
                when {
                    it.id in detected.legs -> it.copy(transferId = id)
                    it.transferId == id -> it.copy(transferId = null)
                    else -> it
                }
            }
        }
        // A Transfer that no longer holds releases its Transfer Legs; one the Operator decided on is never changed here.
        val released = data.transfers.filter { it.decision == TransferDecision.Proposed && it.id !in continued }.map { it.id }.toSet()
        transactions.bankTransactions.replaceAll { if (it.transferId in released) it.copy(transferId = null) else it }
        transfers.removeAll { it.id in released }
        pending = false
        return changes
    }

    /** Makes every read of [comparisonDataOf] fail, as a storage that cannot be read would (NFR-023). */
    var failsToRead: Boolean = false

    override suspend fun comparisonDataOf(budgetIds: Set<Long>): ComparisonData {
        if (failsToRead) throw IllegalStateException("The invented storage of this test cannot be read.")
        return dataOf(budgetIds)
    }

    private fun dataOf(budgetIds: Set<Long>): ComparisonData {
        val budgets = connections.budgetList.filter { it.id in budgetIds }
        val accountIds = budgets.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        val bankAccounts = connections.bankAccountList.filter { it.mapping?.budgetAccountId in accountIds }
        val bankAccountIds = bankAccounts.map { it.id }.toSet()
        val bankTransactions = transactions.bankTransactions.filter { it.bankAccountId in bankAccountIds }
        val transferIds = bankTransactions.mapNotNull { it.transferId }.toSet()
        return ComparisonData(
            budgets = budgets,
            bankAccounts = bankAccounts,
            bankTransactions = bankTransactions,
            budgetAppTransactions = copy.filter { it.budgetAccountId in accountIds },
            transfers = transfers.filter { it.id in transferIds },
            transferDescriptions = transferDescriptions.toList(),
        )
    }

    companion object {
        /** The default lists of UC-010 BR-007, in the order the rule lists them, as a new `slussen.db` holds them. */
        val DEFAULT_TRANSFER_DESCRIPTIONS: List<StoredTransferDescription> =
            listOf(
                "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY",
                "CRD AUTOPAY", "CITICARDAP",
            ).map { StoredTransferDescription(TransferDescriptionKind.CardPayment, it) } +
                listOf(
                    "ÖVERFÖRING", "INTERNET TRANSFER", "EXPRESS INTERNET TRANSFER", "AMERICAN EXPRESS TRANSFER", "ONLINE TRANSFER",
                    "TRANSFER", "XFER",
                ).map { StoredTransferDescription(TransferDescriptionKind.BankTransfer, it) }
    }
}
