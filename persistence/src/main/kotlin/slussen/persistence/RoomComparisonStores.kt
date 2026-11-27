package slussen.persistence

import androidx.room.withTransaction
import slussen.core.comparison.BudgetAppCopy
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.BudgetAppTransactionToStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ChosenBudgetAppTransaction
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.EnteredBy
import slussen.core.port.OtherSideKind
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StoredSentRecord
import slussen.core.port.TransferDecided
import slussen.core.port.TransferRuling
import slussen.core.port.TransferStore
import slussen.persistence.main.SentRecordEntity
import slussen.core.port.ReviewChoice
import slussen.core.port.ReviewDecided
import slussen.core.port.ReviewRuling
import slussen.core.port.ReviewStore
import slussen.core.port.StatusChange
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudgetAccount
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredReviewDecision
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferToStore
import slussen.core.port.TransferType
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.UnreadableTransactionStatus
import slussen.persistence.main.BudgetAppTransactionEntity
import slussen.persistence.main.BudgetReadStateEntity
import slussen.persistence.main.ReviewDecisionEntity
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

/**
 * `review_decision` read back as the core's [StoredReviewDecision]; an unknown `Values:` text is unreadable data. The
 * chosen transaction is read only when its three columns are filled, as they are exactly for Already Present (entity model).
 */
internal fun ReviewDecisionEntity.stored(): StoredReviewDecision {
    val reference = chosenBudgetAppReference
    val budgetAccountId = chosenBudgetAccountId
    val amount = chosenAmount
    return StoredReviewDecision(
        storedBankTransaction = bankTransaction,
        choice = valueOfText<ReviewChoice>(choice, MAIN_PART) { it.text },
        chosen = if (reference != null && budgetAccountId != null && amount != null) ChosenBudgetAppTransaction(reference, budgetAccountId, Amount(amount)) else null,
        decidedAt = decidedAt,
    )
}

/** A Review Decision as `review_decision` keeps it; the row of its Bank Transaction keeps its `id` (`ReviewDecisionDao.store`). */
private fun StoredReviewDecision.entity() = ReviewDecisionEntity(
    bankTransaction = storedBankTransaction,
    choice = choice.text,
    chosenBudgetAppReference = chosen?.budgetAppReference,
    chosenBudgetAccountId = chosen?.budgetAccountId,
    chosenAmount = chosen?.amount?.thousandths,
    decidedAt = decidedAt,
)

/** `sent_record` read back as the core's [StoredSentRecord]; an unknown `Values:` text is unreadable data. */
internal fun SentRecordEntity.stored() = StoredSentRecord(
    storedBankTransaction = bankTransaction,
    budgetAccountId = budgetAccountId,
    sentAsTransfer = sentAsTransfer,
    outcome = valueOfText<SentRecordOutcome>(outcome, MAIN_PART) { it.text },
)

/** A Transfer with the Operator's Transfer Decision as `transfer` keeps it (O-13). */
private fun StoredTransfer.entity() = TransferEntity(
    id = id,
    outflowBudgetAccountId = outflowBudgetAccountId,
    inflowBudgetAccountId = inflowBudgetAccountId,
    amount = amount.thousandths,
    transferType = type.text,
    guessed = guessed,
    decision = decision.text,
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
        val changes = storeConclusions(data, conclude(data))
        dao.storeComparisonPending(false)
        changes
    }

    /**
     * The conclusions of a Comparison as O-5 writes them, inside the caller's transaction, for O-5 and O-13: every
     * Transaction Status of [conclusions] that differs from the one stored in [data], and the Transfers. Returns the
     * status changes.
     */
    internal suspend fun storeConclusions(data: ComparisonData, conclusions: ComparisonConclusions): List<StatusChange> {
        val changes = data.bankTransactions.mapNotNull { stored ->
            val status = conclusions.statuses[stored.id] ?: return@mapNotNull null
            if (status == stored.status) null else StatusChange(stored.id, stored.bankAccountId, stored.importIdentifier, stored.status, status)
        }
        changes.forEach { dao.storeStatus(it.storedBankTransaction, it.to.text) }
        // A Transfer whose Transfer Decision the conclusions drop is removed and its Transfer Legs are released (UC-005 BR-015), before the Transfers detected among them are stored.
        conclusions.dropped.forEach { drop ->
            dao.releaseLegs(drop.transfer.id)
            dao.deleteTransfer(drop.transfer.id)
        }
        storeTransfers(conclusions.transfers, data.transfers)
        // The arriving other side joins the Transfer the Operator decided on (UC-005 BR-005), last, so that a Transfer without a decision that it leaves has released it.
        conclusions.joins.forEach { join -> dao.storeTransferOf(listOf(join.leg), join.transferId) }
        return changes
    }

    override suspend fun comparisonDataOf(budgetIds: Set<Long>): ComparisonData = db.withTransaction { dataOf(budgetIds) }

    /** What O-5 and O-13 conclude from for [budgetIds], read inside the caller's transaction. */
    internal suspend fun dataOf(budgetIds: Set<Long>): ComparisonData {
        val budgets = connections.budgets().filter { it.id in budgetIds }
        val accountIds = budgets.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        val allBankAccounts = connections.bankAccounts()
        val mapped = allBankAccounts.filter { it.mapping?.budgetAccountId in accountIds }
        val mappedIds = mapped.map { it.id }.toSet()
        val fetches = db.fetches()
        // A Transfer is judged with all its Transfer Legs (UC-005 BR-015), also one whose Bank Account is mapped elsewhere or not at all now.
        val legsElsewhere = mapped.flatMap { account -> dao.legsOfTransfersOf(account.id) }
            .filter { it.bankAccountId !in mappedIds }
            .distinctBy { it.id }
            .map { it.stored() }
        val elsewhere = allBankAccounts.filter { account -> account.id !in mappedIds && legsElsewhere.any { it.bankAccountId == account.id } }
        return ComparisonData(
            budgets = budgets,
            bankAccounts = mapped + elsewhere,
            bankTransactions = mapped.flatMap { account -> fetches.bankTransactionsOf(account.id).map { it.stored() } } + legsElsewhere,
            budgetAppTransactions = budgets.flatMap { budget -> dao.copyOf(budget.id).map { it.stored() } },
            transfers = mapped.flatMap { account -> dao.transfersOf(account.id) }.distinctBy { it.id }.map { it.stored() },
            transferDescriptions = dao.transferDescriptions().map { it.stored() },
            reviewDecisions = mapped.flatMap { account -> db.reviewDecisions().ofBankAccount(account.id) }.map { it.stored() },
            sentRecords = mapped.flatMap { account -> dao.sentRecordsOf(account.id) }.map { it.stored() } +
                legsElsewhere.flatMap { leg -> dao.sentRecordsOfTransaction(leg.id) }.map { it.stored() },
        )
    }

    /**
     * The Transfers of O-5, inside its transaction: each of [detected] stored with the decision Proposed, or written over
     * the stored Proposed Transfer it continues, with `bank_transaction.transfer_id` of its Transfer Legs; every one of
     * [stored] with the decision Proposed that none continues no longer holds, so its Transfer Legs are released and it is
     * removed. A Transfer the Operator decided on is never changed here: what the conclusions say of one, a drop or a
     * join, is written by [storeConclusions].
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

/**
 * O-13 [ReviewStore] in `slussen.db` (ADR-002 §3): a Review Decision with the Transaction Statuses that follow from it,
 * one transaction on the one database, on the data and with the writes of O-5 ([comparisons]); and the status of an
 * Unreadable Transaction the Operator defers (UC-004 A9).
 */
class RoomReviewStore(
    private val db: SlussenDatabase,
    private val comparisons: RoomComparisonStore,
) : ReviewStore {
    private val dao get() = db.reviewDecisions()

    override suspend fun <T> decide(budgetIds: Set<Long>, rule: (ComparisonData) -> ReviewRuling<T>): ReviewDecided<T> = db.withTransaction {
        val data = comparisons.dataOf(budgetIds)
        val ruling = rule(data)
        val write = ruling.write ?: return@withTransaction ReviewDecided(ruling.answer, emptyList())
        val decision = write.decision
        require(decision == null || decision.storedBankTransaction == write.storedBankTransaction) { "A Review Decision is stored for its own Bank Transaction." }
        // ADR-007 §6 step 2: storing or removing the Review Decision raises `state_version` in this transaction (`ReviewDecisionDao`).
        if (decision == null) {
            check(dao.clear(write.storedBankTransaction)) { "The rule clears only a Review Decision that is stored." }
        } else {
            dao.store(decision.entity())
        }
        ReviewDecided(ruling.answer, comparisons.storeConclusions(data, write.conclusions))
    }

    override suspend fun bankTransaction(storedBankTransaction: Long): StoredBankTransaction? = dao.bankTransaction(storedBankTransaction)?.stored()

    override suspend fun unreadableTransaction(storedUnreadableTransaction: Long): StoredUnreadableTransaction? =
        dao.unreadableTransaction(storedUnreadableTransaction)?.stored()

    override suspend fun storeUnreadableStatus(storedUnreadableTransaction: Long, status: UnreadableTransactionStatus): Boolean =
        dao.storeUnreadableStatus(storedUnreadableTransaction, status.text) > 0
}

/**
 * O-13 [TransferStore] in `slussen.db` (ADR-002 §3): a Transfer Decision with its effect, one transaction on the one
 * database, on the data O-5 reads ([comparisons]): the Transfer with its decision and sides, the Transfer Legs it has from
 * now on and those it releases, a Transfer without a decision that the choice dissolved, the Funded By or Transfer Partner
 * the Operator keeps, the Transaction Statuses of its Transfer Legs, `state_version` and the Comparison-pending marker.
 */
class RoomTransferStore(
    private val db: SlussenDatabase,
    private val comparisons: RoomComparisonStore,
) : TransferStore {
    private val dao get() = db.budgetAppCopy()

    override suspend fun <T> decide(budgetIds: Set<Long>, rule: (ComparisonData) -> TransferRuling<T>): TransferDecided<T> = db.withTransaction {
        val data = comparisons.dataOf(budgetIds)
        val ruling = rule(data)
        val write = ruling.write ?: return@withTransaction TransferDecided(ruling.answer, transferId = null, changes = emptyList())
        val transfer = write.transfer
        require(write.legs.size in 1..2 && write.legs.distinct().size == write.legs.size) { "A Transfer has one or two Transfer Legs." }
        require(transfer.decision != TransferDecision.Proposed && transfer.decidedAt != null) { "O-13 stores a Transfer Decision with its time." }
        // Entity model `TRANSFER`: the decisions Confirmed and Budget Account Chosen need both sides filled.
        require(transfer.outflowBudgetAccountId != null || transfer.decision == TransferDecision.Split || transfer.decision == TransferDecision.Held) {
            "Confirmed and Budget Account Chosen need both sides."
        }
        check(dao.countBankTransactions(write.legs) == write.legs.size) { "A Transfer Leg is a stored Bank Transaction." }
        val id = if (transfer.id == 0L) {
            dao.insertTransfer(transfer.entity())
        } else {
            check(dao.updateTransfer(transfer.entity()) == 1) { "The Transfer decided on is stored." }
            transfer.id
        }
        val proposed = TransferDecision.Proposed.text
        write.dissolved.forEach { dissolved ->
            dao.releaseLegs(dissolved)
            // UC-005 BR-016: A Bank Transaction is taken only from a Transfer that has no Transfer Decision.
            check(dao.deleteTransferWithoutDecision(dissolved, proposed) == 1) { "Only a Transfer without a Transfer Decision is dissolved by a choice." }
        }
        dao.releaseOtherLegs(id, write.legs)
        dao.storeTransferOf(write.legs, id)
        write.kept?.let { kept ->
            val stored = when (kept.kind) {
                OtherSideKind.FundedBy -> dao.storeFundedBy(kept.bankAccountId, kept.budgetAccountId)
                OtherSideKind.TransferPartner -> dao.storeTransferPartner(kept.bankAccountId, kept.budgetAccountId)
            }
            check(stored == 1) { "The Funded By or Transfer Partner is kept for a stored Bank Account." }
        }
        val changes = data.bankTransactions.mapNotNull { before ->
            val status = write.statuses[before.id] ?: return@mapNotNull null
            if (status == before.status) null else StatusChange(before.id, before.bankAccountId, before.importIdentifier, before.status, status)
        }
        changes.forEach { dao.storeStatus(it.storedBankTransaction, it.to.text) }
        // ADR-007 §3a, §6 step 2: the marker and `state_version` in this transaction: no Upload Plan is prepared before the Transfers are detected again (UC-005 BR-016).
        dao.storeComparisonPending(true)
        TransferDecided(ruling.answer, id, changes)
    }

    override suspend fun bankTransaction(storedBankTransaction: Long): StoredBankTransaction? =
        db.reviewDecisions().bankTransaction(storedBankTransaction)?.stored()
}
