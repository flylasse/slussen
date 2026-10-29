package slussen.core.comparison

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
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StatusChange
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import java.time.Clock
import java.time.LocalDate

/**
 * Compare With the Budget App (UC-003), `:core` `comparison` (ADR-001 §7), as far as the transactions plan's Phase 7a
 * builds it: the read of every Budget a Bank Account is mapped to, stored with its cursor (step 1, O-4, BR-016,
 * BR-020), the Transaction Statuses concluded from that stored copy alone (steps 2-3, O-5, BR-001), the Eligible
 * Transactions (step 5, BR-014), the Decision Journal (step 6), A1 and A2. It runs on its own when the Operator reads
 * the Budget App again, and within a Fetch (UC-001 step 10), always through the operation coordinator (ADR-007 §1); it
 * reads the Budget App only (BR-015). A Comparison left between O-4 and O-5 is concluded from the stored data alone at
 * the next start and whenever its marker is found (ADR-007 §3a, §8). Transfers (step 4), Sent Records (BR-017, BR-021),
 * Review Decisions and First Upload Matching (BR-019) come with later phases.
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
        val result = coordinator.run(OperationKind.Comparison, StartedBy.Operator) {
            conclude(connections.budgets().filter { it.lastReadAt != null }.map { it.id }.toSet())
        }
        return result is OperationResult.Ran
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
                return ComparisonReport.Compared(budgets.map { notRead(it, read.kind) }, statusesChanged = 0)
            is ReadResult.StoppedByGate -> return ComparisonReport.Compared(budgets.map { notRead(it, null) }, statusesChanged = 0)
        }
        val outcomes = mutableListOf<BudgetReadOutcome>()
        for (budget in budgets) {
            val outcome = readOne(budget, listed, context)
            if (outcome == null) return refused(outcomes.filterIsInstance<BudgetReadOutcome.Read>().map { it.budgetId }.toSet(), pendingBefore)
            outcomes += outcome
        }
        val read = outcomes.filterIsInstance<BudgetReadOutcome.Read>().map { it.budgetId }.toSet()
        val changed = if (read.isEmpty() && !pendingBefore) 0 else concludeWith(read, pendingBefore).size
        return ComparisonReport.Compared(outcomes, changed)
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
        return when (val read = plugins.budgetApp.reader.readChanges(budget.budgetAppReference, since, from, context)) {
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

    /** Steps 2-3 and 6 (O-5): the Transaction Statuses from the stored copy alone, and the Decision Journal of each change. */
    private suspend fun conclude(budgetIds: Set<Long>): List<StatusChange> {
        val windows = ComparisonWindows.of(settings.current(), plugins.budgetApp.profile.matchingDateWindowDays, today())
        val changes = comparisons.applyComparison(budgetIds) { data -> ComparisonRules.conclude(data, windows) }
        journalChanges(changes)
        return changes
    }

    // UC-011 BR-001: Every change of the Transaction Status Slussen gave a Bank Transaction is recorded, naming its Bank Account and its Import Identifier.
    /** Step 6: one Decision Journal entry per changed Transaction Status, in Slussen's own words, never a payee or memo. */
    private suspend fun journalChanges(changes: List<StatusChange>) {
        if (changes.isEmpty()) return
        val names = connections.bankAccounts().associate { it.id to it.name }
        changes.forEach { change ->
            val from = change.from?.text ?: "not compared yet"
            journal.record(
                JournalEventKind.StatusChanged,
                "Transaction Status changed from $from to ${change.to.text}",
                change.importIdentifier,
                names.getValue(change.bankAccountId),
            )
        }
    }

    private fun notRead(budget: StoredBudget, kind: FailureKind?) =
        BudgetReadOutcome.NotRead(budget.id, budget.name, kind, budget.lastReadAt)

    private fun StoredBankAccount.mappedInto(budget: StoredBudget): Boolean =
        budget.budgetAccounts.any { it.id == mapping?.budgetAccountId }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), clock.zone)
}
