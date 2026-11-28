package slussen.testing

import slussen.core.comparison.BudgetAppCopy
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.OtherSideKind
import slussen.core.port.ReviewDecided
import slussen.core.port.ReviewRuling
import slussen.core.port.ReviewStore
import slussen.core.port.StatusChange
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredReviewDecision
import slussen.core.port.StoredSentRecord
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransferDecided
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferRuling
import slussen.core.port.TransferStore
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.UnreadableTransactionStatus
import slussen.pluginapi.budgetapp.ChangeCursor

/**
 * In-memory fake of O-4 [BudgetAppCopyStore], O-5 [ComparisonStore] and O-13 [ReviewStore] and [TransferStore] ([transferStore]) (ADR-002 §3), all or nothing
 * like the real ones, on the Budgets of [connections] and the Bank Transactions of [transactions], with the stored
 * Transfers ([transfers]), the Review Decisions ([reviewDecisions]) and the description lists ([transferDescriptions],
 * which start as the default lists of UC-010 BR-007, as the real storage does). A test makes O-5 fail with
 * [failsToConclude], as a process that stops between O-4 and O-5 would leave it (ADR-007 §3a), and O-13 with
 * [failsToDecide], as a storage that cannot be written would.
 */
class InMemoryBudgetAppCopyStore(
    private val connections: InMemoryConnectionStore,
    private val transactions: InMemoryImportStatusStore,
) : BudgetAppCopyStore, ComparisonStore, ReviewStore {
    val copy = mutableListOf<StoredBudgetAppTransaction>()
    val reviewDecisions = mutableListOf<StoredReviewDecision>()

    /** The Sent Records a test plants: the Uploads that write them come with their own phases (UC-006); the list of [connections]. */
    val sentRecords: MutableList<StoredSentRecord> get() = connections.sentRecords

    /** Makes every Review Decision and every change of an Unreadable Transaction's status fail before anything is stored. */
    var failsToDecide: Boolean = false
    val cursors = mutableMapOf<Long, ChangeCursor>()
    val periodOwed = mutableSetOf<Long>()
    val transfers: MutableList<StoredTransfer> get() = transactions.transfers
    val transferDescriptions: MutableList<StoredTransferDescription> = DEFAULT_TRANSFER_DESCRIPTIONS.toMutableList()
    private var nextTransferId = 9000L

    /** The Comparison marker (ADR-007 §3a): the one of [connections], which the changes of O-13 `MappingStore` set too. */
    var pending: Boolean
        get() = connections.comparisonPending
        set(value) {
            connections.comparisonPending = value
        }
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
        connections.stateVersion.raise()
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
        val changes = store(data, conclude(data))
        pending = false
        connections.stateVersion.raise()
        return changes
    }

    /**
     * The conclusions of a Comparison as O-5 writes them, for O-5 and O-13: every Transaction Status that changed, each
     * Transfer that holds with the decision Proposed and its Transfer Legs, and every stored Proposed Transfer that no
     * longer holds released and removed. Nothing is written when a conclusion cannot be stored.
     */
    private fun store(data: ComparisonData, conclusions: ComparisonConclusions): List<StatusChange> {
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
        // A Transfer whose Transfer Decision the conclusions drop is removed and its Transfer Legs are released (UC-005 BR-015).
        val droppedIds = conclusions.dropped.map { it.transfer.id }.toSet()
        transactions.bankTransactions.replaceAll { if (it.transferId in droppedIds) it.copy(transferId = null) else it }
        transfers.removeAll { it.id in droppedIds }
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
        // The arriving other side joins the Transfer the Operator decided on, which keeps its Transfer Decision (UC-005 BR-005).
        conclusions.joins.forEach { join ->
            transactions.bankTransactions.replaceAll { if (it.id == join.leg) it.copy(transferId = join.transferId) else it }
        }
        return changes
    }

    /** Makes every Transfer Decision fail before anything is stored, as a storage that cannot be written would. */
    var failsToDecideTransfers: Boolean = false

    /** O-13 [TransferStore] on the same stored data: the Transfer Decisions of UC-005. */
    val transferStore: TransferStore = object : TransferStore {
        override suspend fun <T> decide(budgetIds: Set<Long>, rule: (ComparisonData) -> TransferRuling<T>): TransferDecided<T> = decideTransfer(budgetIds, rule)

        override suspend fun bankTransaction(storedBankTransaction: Long): StoredBankTransaction? =
            transactions.bankTransactions.firstOrNull { it.id == storedBankTransaction }
    }

    private fun <T> decideTransfer(budgetIds: Set<Long>, rule: (ComparisonData) -> TransferRuling<T>): TransferDecided<T> {
        if (failsToDecideTransfers) throw IllegalStateException("The invented storage of this test cannot be written.")
        val data = dataOf(budgetIds)
        val ruling = rule(data)
        val write = ruling.write ?: return TransferDecided(ruling.answer, transferId = null, changes = emptyList())
        // As the Room store refuses it: no Transfer Decision while its Budget owes a read of its read period (UC-003 BR-016).
        check(data.periodReadOwed.isEmpty()) { "No Transfer Decision is stored while its Budget owes a read of its read period." }
        // As the Room store refuses them, before anything is written.
        require(write.legs.size in 1..2 && write.legs.distinct().size == write.legs.size) { "A Transfer has one or two Transfer Legs." }
        require(write.transfer.decision != TransferDecision.Proposed && write.transfer.decidedAt != null) { "O-13 stores a Transfer Decision with its time." }
        require(write.transfer.outflowBudgetAccountId != null || write.transfer.decision in setOf(TransferDecision.Split, TransferDecision.Held)) {
            "Confirmed and Budget Account Chosen need both sides."
        }
        check(write.transfer.id == 0L || transfers.any { it.id == write.transfer.id }) { "The Transfer decided on is stored." }
        check(write.legs.all { leg -> transactions.bankTransactions.any { it.id == leg } }) { "A Transfer Leg is a stored Bank Transaction." }
        check(write.dissolved.all { id -> transfers.any { it.id == id && it.decision == TransferDecision.Proposed } }) {
            "Only a Transfer without a Transfer Decision is dissolved by a choice."
        }
        check(write.kept == null || connections.bankAccountList.any { it.id == write.kept?.bankAccountId }) { "The Funded By or Transfer Partner is kept for a stored Bank Account." }
        val id = if (write.transfer.id == 0L) nextTransferId++ else write.transfer.id
        val stored = write.transfer.copy(id = id)
        val index = transfers.indexOfFirst { it.id == id }
        if (index < 0) transfers += stored else transfers[index] = stored
        transfers.removeAll { it.id in write.dissolved }
        transactions.bankTransactions.replaceAll {
            when {
                it.id in write.legs -> it.copy(transferId = id)
                it.transferId == id || it.transferId in write.dissolved -> it.copy(transferId = null)
                else -> it
            }
        }
        write.kept?.let { kept ->
            connections.bankAccountList.replaceAll { account ->
                when {
                    account.id != kept.bankAccountId -> account
                    kept.kind == OtherSideKind.FundedBy -> account.copy(fundedByBudgetAccountId = kept.budgetAccountId)
                    else -> account.copy(transferPartnerBudgetAccountId = kept.budgetAccountId)
                }
            }
        }
        val changes = data.bankTransactions.mapNotNull { before ->
            val status = write.statuses[before.id] ?: return@mapNotNull null
            if (status == before.status) null else StatusChange(before.id, before.bankAccountId, before.importIdentifier, before.status, status)
        }
        changes.forEach { change ->
            transactions.bankTransactions.replaceAll { if (it.id == change.storedBankTransaction) it.copy(status = change.to) else it }
        }
        pending = true
        connections.stateVersion.raise()
        return TransferDecided(ruling.answer, id, changes)
    }

    override suspend fun <T> decide(budgetIds: Set<Long>, rule: (ComparisonData) -> ReviewRuling<T>): ReviewDecided<T> {
        if (failsToDecide) throw IllegalStateException("The invented storage of this test cannot be written.")
        val data = dataOf(budgetIds)
        val ruling = rule(data)
        val write = ruling.write ?: return ReviewDecided(ruling.answer, emptyList())
        // As the Room store refuses it: no Review Decision while its Budget owes a read of its read period (UC-003 BR-016).
        check(data.periodReadOwed.isEmpty()) { "No Review Decision is stored while its Budget owes a read of its read period." }
        val decision = write.decision
        require(decision == null || decision.storedBankTransaction == write.storedBankTransaction) { "A Review Decision is stored for its own Bank Transaction." }
        // As `slussen.db` refuses them: a decision about no stored Bank Transaction, a chosen transaction in no stored Budget Account, clearing none.
        check(decision == null || transactions.bankTransactions.any { it.id == decision.storedBankTransaction }) { "A Review Decision is about a stored Bank Transaction." }
        check(decision?.chosen == null || connections.budgetList.any { budget -> budget.budgetAccounts.any { it.id == decision.chosen?.budgetAccountId } }) {
            "The chosen Budget App Transaction is in a stored Budget Account."
        }
        check(decision != null || reviewDecisions.any { it.storedBankTransaction == write.storedBankTransaction }) { "The rule clears only a Review Decision that is stored." }
        val changes = store(data, write.conclusions)
        reviewDecisions.removeAll { it.storedBankTransaction == write.storedBankTransaction }
        decision?.let { reviewDecisions += it }
        connections.stateVersion.raise()
        return ReviewDecided(ruling.answer, changes)
    }

    override suspend fun bankTransaction(storedBankTransaction: Long): StoredBankTransaction? =
        transactions.bankTransactions.firstOrNull { it.id == storedBankTransaction }

    override suspend fun unreadableTransaction(storedUnreadableTransaction: Long): StoredUnreadableTransaction? =
        transactions.unreadableTransactions.firstOrNull { it.id == storedUnreadableTransaction }

    override suspend fun storeUnreadableStatus(storedUnreadableTransaction: Long, status: UnreadableTransactionStatus): Boolean {
        if (failsToDecide) throw IllegalStateException("The invented storage of this test cannot be written.")
        if (transactions.unreadableTransactions.none { it.id == storedUnreadableTransaction }) return false
        transactions.unreadableTransactions.replaceAll { if (it.id == storedUnreadableTransaction) it.copy(status = status) else it }
        return true
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
        val mapped = connections.bankAccountList.filter { it.mapping?.budgetAccountId in accountIds }
        val mappedIds = mapped.map { it.id }.toSet()
        val ofMapped = transactions.bankTransactions.filter { it.bankAccountId in mappedIds }
        // A Transfer the Operator decided on is read through its Budget Accounts too, so that it is judged also when no Transfer Leg of it lies in a mapped Bank Account any more (UC-005 BR-015).
        val decidedHere = transfers.filter { it.decision != TransferDecision.Proposed && (it.inflowBudgetAccountId in accountIds || it.outflowBudgetAccountId in accountIds) }
        val transferIds = ofMapped.mapNotNull { it.transferId }.toSet() + decidedHere.map { it.id }
        // A Transfer is judged with all its Transfer Legs (UC-005 BR-015), also one whose Bank Account is mapped elsewhere or not at all now.
        val legsElsewhere = transactions.bankTransactions.filter { it.transferId in transferIds && it.bankAccountId !in mappedIds }
        val bankTransactions = ofMapped + legsElsewhere
        return ComparisonData(
            budgets = budgets,
            bankAccounts = mapped + connections.bankAccountList.filter { account -> account.id !in mappedIds && legsElsewhere.any { it.bankAccountId == account.id } },
            bankTransactions = bankTransactions,
            budgetAppTransactions = copy.filter { it.budgetAccountId in accountIds },
            transfers = transfers.filter { it.id in transferIds },
            transferDescriptions = transferDescriptions.toList(),
            reviewDecisions = reviewDecisions.filter { decision -> bankTransactions.any { it.id == decision.storedBankTransaction } },
            sentRecords = sentRecords.filter { record -> bankTransactions.any { it.id == record.storedBankTransaction } },
            periodReadOwed = budgets.map { it.id }.filter { it in periodOwed }.toSet(),
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
