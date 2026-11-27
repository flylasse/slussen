package slussen.core.comparison

import kotlinx.coroutines.CancellationException
import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonStore
import slussen.core.port.ConnectionStore
import slussen.core.port.DroppedTransferDecision
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StatusChange
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.core.transfer.TransferJournal
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import java.time.Clock
import java.time.LocalDate

/**
 * Compare With the Budget App (UC-003), `:core` `comparison` (ADR-001 §7), as far as the transactions plan's Phases 7a
 * and 7b build it: the read of every Budget a Bank Account is mapped to, stored with its cursor (step 1, O-4, BR-016,
 * BR-020), the Transaction Statuses concluded from that stored copy alone (steps 2-3, O-5, BR-001), the Transfers
 * detected among them and stored with those statuses (step 4, UC-005, `slussen.core.transfer`), the Eligible
 * Transactions (step 5, BR-014), the Decision Journal (step 6), A1 and A2. It runs on its own when the Operator reads
 * the Budget App again, and within a Fetch (UC-001 step 10), always through the operation coordinator (ADR-007 §1); it
 * reads the Budget App only (BR-015). A Comparison left between O-4 and O-5 is concluded from the stored data alone at
 * the next start and whenever its marker is found (ADR-007 §3a, §8); the same conclusions follow a change that the
 * specification follows with statuses given again, through [statusesAgainAfter], the one operation for it (UC-009
 * BR-015, UC-005 BR-016, UC-010 step 5). The Review Decisions are followed in the order of
 * BR-001 (BR-009, BR-010), and a Transfer the Operator decided on is kept, joined or dropped as UC-005 BR-005 and BR-015
 * say. The statuses Sent Records give (BR-017, BR-021) and First Upload Matching (BR-019) come with later phases.
 */
class Comparison(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val connections: ConnectionStore,
    private val copies: BudgetAppCopyStore,
    private val comparisons: ComparisonStore,
    private val coordinator: OperationCoordinator,
    private val settings: OperatorSettingsStore,
    private val journal: DecisionJournal,
    private val clock: Clock,
    /** A1 step 2: the read of each Budget that failed since its last successful read, for the screens. */
    private val notices: ComparisonNotices = ComparisonNotices(),
) {
    /** Trigger: the Operator asks Slussen to read the Budget App again; no Bank is contacted (BR-015). */
    suspend fun readAgain(): ComparisonStart =
        // UC-006 BR-005: While an Upload Plan awaits confirmation or an Upload is written, the Budget App is not read again.
        when (
            val result = coordinator.run(OperationKind.Comparison, StartedBy.Operator) { permit ->
                // ADR-007 §1: the screens show that the Budget App is read; within a Fetch they show the Fetch.
                permit.workingOn(plugins.budgetApp.profile.name)
                compare(permit)
            }
        ) {
            is OperationResult.Ran -> ComparisonStart.Ran(result.value)
            is OperationResult.Refused -> ComparisonStart.Unavailable(result.reason)
        }

    /**
     * ADR-007 §3a, §8: when a Comparison's read is stored and its conclusions are not, concludes it from the stored data
     * alone, without a request, for every Budget read so far. Returns whether one was pending.
     */
    suspend fun recomputePending(): Boolean {
        if (!comparisons.comparisonPending()) return false
        val result = coordinator.run(OperationKind.Comparison, StartedBy.Operator) { concludeFromStoredData() }
        return result is OperationResult.Ran
    }

    // UC-009 BR-015: After a change Slussen gives the stored Bank Transactions their Transaction Status again and detects the Transfers again by the rules of the Comparison, from what it has stored; it reads no Budget App and contacts no Bank or Data Provider, and when it stops between the change and the statuses no Upload Plan is prepared before they are given.
    /**
     * The one operation that follows a change with statuses given again and Transfers detected again from stored data
     * (UC-009 BR-015, UC-010 step 5, UC-005 BR-016; ADR-007 §3a): on the coordinator's lane, admitted as [kind] (§2),
     * it runs [change], whose storage operation (O-13) sets the Comparison marker in its own transaction when it stores
     * something the statuses depend on, and then, when the marker is set, concludes with the Comparison's own rules from
     * the stored copy alone and commits with O-5, which clears it. No Budget is read and the time of each read stays. A
     * change that stores nothing leaves no marker and is followed by nothing; a marker an earlier stop left is concluded
     * too, as §3a asks whenever it is found. When the conclusions cannot be stored now, the marker stays, which keeps
     * every Upload Plan from being prepared (§3) until the next start concludes ([recomputePending]) or a Comparison does.
     * [change] answers a storage that fails itself; what it throws ends the operation.
     */
    suspend fun <T> statusesAgainAfter(kind: OperationKind, change: suspend () -> T): OperationResult<StatusesAgain<T>> =
        coordinator.run(kind, StartedBy.Operator) {
            val changed = change()
            StatusesAgain(changed, statusesGiven = concludedWhenPending())
        }

    /**
     * Concludes from stored data when the Comparison marker is set, and answers whether none is pending after it: the
     * marker itself says so, also when only what follows the conclusions failed.
     */
    private suspend fun concludedWhenPending(): Boolean {
        try {
            if (comparisons.comparisonPending()) concludeFromStoredData()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The marker, read below, says whether the conclusions were stored.
        }
        return try {
            !comparisons.comparisonPending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
    }

    /** Steps 1-6 within the operation that holds [permit]: on its own, or as step 10 of a Fetch (UC-001). */
    internal suspend fun compare(permit: OperationPermit): ComparisonReport {
        val bankAccounts = connections.bankAccounts()
        // Step 1: every served Budget that a Bank Account is mapped to.
        val budgets = connections.budgets().filter { budget -> budget.served && bankAccounts.any { it.mappedInto(budget) } }
        if (budgets.isEmpty()) return ComparisonReport.NothingToCompare
        // UC-014 BR-005: Without entered credentials the Budget App is not contacted; credentials missing is shown.
        val credential = credentials.stored(ServiceKey.budgetApp) ?: return ComparisonReport.CredentialsMissing
        // UC-014 BR-005: A Budget App that refused Slussen's credentials is not contacted until new ones are accepted.
        if (credentials.status(ServiceKey.budgetApp) == CredentialStatus.Refused) return ComparisonReport.CredentialsRefused
        val pendingBefore = comparisons.comparisonPending()
        val context = credentials.context(ServiceKey.budgetApp, permit, credential)
        val reader = plugins.budgetApp.reader
        val listed = when (val read = reader.readBudgets(context)) {
            is ReadResult.Answered -> read.value
            ReadResult.CredentialsRefused -> return refused(read = emptySet(), pendingBefore)
            // A1: the Budget App does not answer, is unavailable or the read is not sent: no Budget can be read.
            is ReadResult.TransientFailure ->
                return noted(ComparisonReport.Compared(budgets.map { notRead(it, read.kind) }, statusesChanged = 0))
            is ReadResult.StoppedByGate -> return noted(ComparisonReport.Compared(budgets.map { notRead(it, null) }, statusesChanged = 0))
        }
        val outcomes = mutableListOf<BudgetReadOutcome>()
        for (budget in budgets) {
            val outcome = readOne(budget, listed, context)
            if (outcome == null) return refused(outcomes.filterIsInstance<BudgetReadOutcome.Read>().map { it.budgetId }.toSet(), pendingBefore)
            outcomes += outcome
        }
        val read = outcomes.filterIsInstance<BudgetReadOutcome.Read>().map { it.budgetId }.toSet()
        val changed = if (read.isEmpty() && !pendingBefore) 0 else concludeWith(read, pendingBefore).size
        return noted(ComparisonReport.Compared(outcomes, changed))
    }

    /**
     * A1 step 2: notes, for the screens, the read of each Budget that failed with its time, and forgets it once a read of
     * that Budget succeeds.
     */
    private fun noted(report: ComparisonReport.Compared): ComparisonReport.Compared {
        val at = clock.instant()
        report.budgets.forEach { outcome ->
            when (outcome) {
                is BudgetReadOutcome.Read -> notices.noteRead(outcome.budgetId)
                is BudgetReadOutcome.NotRead -> notices.noteFailed(outcome.budgetId, BudgetReadFailure(at, outcome.kind))
            }
        }
        return report
    }

    /**
     * Step 1 for one Budget: its changes since the stored cursor, or from the first day of the read period (BR-016),
     * stored with the balances of its Budget Accounts and the next cursor (O-4, BR-020). Null when the Budget App refused
     * Slussen's credentials (A2).
     */
    private suspend fun readOne(budget: StoredBudget, listed: List<BudgetRead>, context: RequestContext): BudgetReadOutcome? {
        val since = copies.changeCursorOf(budget.id)
        // UC-003 BR-016: The first read of a Budget covers the read period; every later read takes all changes since the previous one.
        val from = ComparisonRules.readFrom(settings.current(), plugins.budgetApp.profile.matchingDateWindowDays, today())
        val periodOwed = since != null && copies.periodReadOwed(budget.id)
        return when (val read = readChanges(budget, since, from, periodOwed, context)) {
            is ReadResult.Answered -> {
                val balances = listed.firstOrNull { it.reference == budget.budgetAppReference }
                    ?.budgetAccounts?.associate { it.reference to it.balance }
                    .orEmpty()
                val readAt = clock.instant()
                // UC-003 BR-020: The copy and the point up to which the Budget App was read are stored together.
                when (copies.applyChangeSet(BudgetReadToStore(budget.id, since, read.value.transactions, read.value.next, balances, readAt))) {
                    is ChangeSetApplied.Stored -> BudgetReadOutcome.Read(budget.id, budget.name, readAt)
                    ChangeSetApplied.CursorMoved -> notRead(budget, null)
                }
            }
            ReadResult.CredentialsRefused -> null
            // A1: this Budget cannot be read; its statuses and stored copy are kept, and the other Budgets are read.
            is ReadResult.TransientFailure -> notRead(budget, read.kind)
            is ReadResult.StoppedByGate -> notRead(budget, null)
        }
    }

    /**
     * The changes of [budget] since [since], or from [from] on for its first read (BR-016). When [periodOwed], the read
     * period is read first and the changes since [since] after it, and the two are stored as one read with the later
     * cursor (S-T-18): a read of the period brings the earlier transactions of a Budget Account the Budget gained, and only
     * the changes since the cursor bring what was deleted or merged since the previous read. A failure of either is the
     * failure of the read, and nothing of it is stored.
     */
    private suspend fun readChanges(
        budget: StoredBudget,
        since: ChangeCursor?,
        from: LocalDate,
        periodOwed: Boolean,
        context: RequestContext,
    ): ReadResult<ChangeSet> {
        val reader = plugins.budgetApp.reader
        if (!periodOwed) return reader.readChanges(budget.budgetAppReference, since, from, context)
        val period = reader.readChanges(budget.budgetAppReference, null, from, context)
        if (period !is ReadResult.Answered) return period
        return when (val changes = reader.readChanges(budget.budgetAppReference, since, from, context)) {
            // The changes since the cursor are the later answer: where both report a transaction, theirs is kept.
            is ReadResult.Answered -> ReadResult.Answered(
                ChangeSet(
                    (period.value.transactions + changes.value.transactions).associateBy { it.reference }.values.toList(),
                    changes.value.next,
                ),
            )
            else -> changes
        }
    }

    /**
     * A2: the Budget App refused Slussen's credentials. It is not contacted again until new ones are accepted (UC-014
     * BR-005); the Budgets already stored in this Comparison are concluded, so that no read is left pending.
     */
    private suspend fun refused(read: Set<Long>, pendingBefore: Boolean): ComparisonReport {
        credentials.markRefused(ServiceKey.budgetApp)
        if (read.isNotEmpty() || pendingBefore) concludeWith(read, pendingBefore)
        return ComparisonReport.CredentialsRefused
    }

    /** O-5 for the Budgets [read] now, and for every Budget read before when a Comparison was left pending (ADR-007 §3a). */
    private suspend fun concludeWith(read: Set<Long>, pendingBefore: Boolean): List<StatusChange> {
        val budgetIds = if (pendingBefore) read + connections.budgets().filter { it.lastReadAt != null }.map { it.id } else read
        return conclude(budgetIds)
    }

    /**
     * ADR-007 §3a: within the operation that holds the lane, the conclusions from the stored data alone for every Budget
     * read so far, without a request and with the time of each read unchanged: what [statusesAgainAfter] and
     * [recomputePending] run. A Bank Account mapped into a Budget not read yet is not compared yet (UC-003 A1). It
     * clears the Comparison-pending marker.
     */
    private suspend fun concludeFromStoredData(): List<StatusChange> =
        conclude(connections.budgets().filter { it.lastReadAt != null }.map { it.id }.toSet())

    /**
     * Steps 2-4 and 6 (O-5): the Transaction Statuses and the Transfers from the stored data alone, and the Decision
     * Journal of each changed status, also that of a Transfer Leg (UC-011 BR-001), and of each Transfer Decision the
     * conclusions dropped (UC-005 BR-015). Every conclusion of a Comparison, of [recomputePending] and of
     * [statusesAgainAfter] runs here, so this is the one place that leaves a Budget that owes a read of its read period
     * as it is (BR-016, ADR-007 §3a): the marker is still cleared, and the read of that Budget, which clears what it owes
     * in O-4 before this runs, gives it its statuses.
     */
    private suspend fun conclude(budgetIds: Set<Long>): List<StatusChange> {
        val windows = ComparisonWindows.of(settings.current(), plugins.budgetApp.profile.matchingDateWindowDays, today())
        // The other writes of statuses from stored data, the Review Decisions and Transfer Decisions, are refused on such a Budget (UC-004 A12, UC-005 A11; `ReviewDecisions`, `TransferDecisions`, checked again in O-13's transaction), so this holds for every path.
        // UC-003 BR-016: Until the read of its read period, no conclusion from stored data changes the Transaction Statuses of a Budget that owes it; they stay as the latest Comparison that read it left them.
        val owing = connections.budgets().filter { copies.periodReadOwed(it.id) }
        val owingAccounts = owing.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        var dropped = emptyList<DroppedTransferDecision>()
        val changes = comparisons.applyComparison(budgetIds - owing.map { it.id }.toSet()) { data ->
            // A Transfer Leg judged with a Transfer of another Budget keeps its status while its own Budget owes the read.
            val keptAsTheyAre = data.bankTransactions.filter { transaction ->
                data.bankAccounts.firstOrNull { it.id == transaction.bankAccountId }?.mapping?.budgetAccountId in owingAccounts
            }.map { it.id }.toSet()
            ComparisonRules.conclude(data, windows)
                .let { it.copy(statuses = it.statuses - keptAsTheyAre) }
                .also { dropped = it.dropped }
        }
        journalChanges(changes, dropped)
        return changes
    }

    // UC-011 BR-001: Every change of the Transaction Status Slussen gave a Bank Transaction is recorded, naming its Bank Account and its Import Identifier.
    /**
     * Step 6: one Decision Journal entry per changed Transaction Status, in Slussen's own words, never a payee or memo,
     * and the entries of each Transfer Decision Slussen [dropped].
     */
    private suspend fun journalChanges(changes: List<StatusChange>, dropped: List<DroppedTransferDecision>) {
        if (changes.isEmpty() && dropped.isEmpty()) return
        val bankAccountNames = connections.bankAccounts().associate { it.id to it.name }
        // UC-005 BR-015: Slussen records the dropped Transfer Decision with its reason in the Decision Journal.
        if (dropped.isNotEmpty()) {
            val budgetAccountNames = connections.budgets().flatMap { it.budgetAccounts }.associate { it.id to it.name }
            TransferJournal.recordDropped(journal, dropped, bankAccountNames, budgetAccountNames)
        }
        journal.recordStatusChanges(changes, bankAccountNames)
    }

    private fun notRead(budget: StoredBudget, kind: FailureKind?) =
        BudgetReadOutcome.NotRead(budget.id, budget.name, kind, budget.lastReadAt)

    private fun StoredBankAccount.mappedInto(budget: StoredBudget): Boolean =
        budget.budgetAccounts.any { it.id == mapping?.budgetAccountId }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), clock.zone)
}
