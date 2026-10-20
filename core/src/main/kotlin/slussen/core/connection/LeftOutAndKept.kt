package slussen.core.connection

import slussen.core.journal.DecisionJournal
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.JournalEventKind
import slussen.core.port.KeptOutcome
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.StoredBudget
import slussen.core.port.StoredLeftOutBankAccount

/**
 * The Left-Out Bank Accounts and the Manually Kept Accounts of UC-013 (A9, A10, BR-011, BR-014), which UC-009 A15 and
 * A16 also use since S-40: how they are brought back, marked and unmarked, and how each change is recorded in the
 * Decision Journal, by name and never by the Data Provider's reference (NFR-012), with the storage operation of O-15.
 */
internal class LeftOutAndKept(
    private val plugins: RegisteredPlugins,
    private val offered: OfferedBankAccountStore,
    private val journal: DecisionJournal,
) {
    fun sourceName(dataProviderId: Long): String =
        plugins.dataProviders.firstOrNull { it.stored.id == dataProviderId }?.stored?.name.orEmpty()

    /** Steps 6-7 and A12 of UC-013: a Left-Out Bank Account recorded by its name. */
    suspend fun recordLeftOut(name: String?, dataProviderId: Long) {
        journal.record(
            JournalEventKind.AccountMappingChange,
            "Offered Bank Account ${name.orEmpty()} of ${sourceName(dataProviderId)} left out, its import switched off: not imported and never read until imported again",
        )
    }

    suspend fun recordBroughtBack(name: String?, dataProviderId: Long) {
        journal.record(
            JournalEventKind.AccountMappingChange,
            "Left-Out Bank Account ${name.orEmpty()} of ${sourceName(dataProviderId)} brought back: imported again",
        )
    }

    /**
     * UC-013 A10, UC-009 A15: [remove] deletes the `left_out_bank_account`; it is then offered again from the Data
     * Provider's latest delivery when that holds it, otherwise from the next delivery that does.
     */
    suspend fun bringBack(leftOutBankAccountId: Long, remove: suspend (Long) -> StoredLeftOutBankAccount?): BroughtBack {
        // UC-013 BR-011: A Left-Out Bank Account is offered again only when the Operator brings it back, recorded by its name.
        val removed = remove(leftOutBankAccountId) ?: return BroughtBack.NotFound
        recordBroughtBack(removed.name, removed.dataProviderId)
        val offeredNow = offered.offered().any { it.dataProviderId == removed.dataProviderId && it.providerReference == removed.providerReference }
        return BroughtBack.Offered(removed.name.orEmpty(), offeredNow)
    }

    /**
     * UC-013 A9, UC-009 A16: marks [budgetAccountId] of [budgets] when [manuallyKept], else unmarks it, through [store],
     * and records the change.
     */
    suspend fun setManuallyKept(
        budgets: List<StoredBudget>,
        budgetAccountId: Long,
        manuallyKept: Boolean,
        store: suspend (Long, Boolean) -> KeptOutcome,
    ): KeepOutcome {
        val budget = budgets.firstOrNull { b -> b.budgetAccounts.any { it.id == budgetAccountId } }
        val account = budget?.budgetAccounts?.single { it.id == budgetAccountId }
        if (manuallyKept) {
            // UC-013 BR-014: Only a free Budget Account of a served Budget, not yet a Manually Kept Account, is marked.
            if (budget == null || account == null || !budget.served || account.manuallyKept) return KeepOutcome.NotFree
        } else if (account == null || !account.manuallyKept) {
            return KeepOutcome.NotManuallyKept
        }
        return when (val outcome = store(budgetAccountId, manuallyKept)) {
            KeptOutcome.Stored -> {
                journal.record(
                    JournalEventKind.AccountMappingChange,
                    if (manuallyKept) {
                        "Budget Account ${account.name} of Budget ${budget.name} marked as a Manually Kept Account: kept by hand, never offered for pairing"
                    } else {
                        "Budget Account ${account.name} of Budget ${budget.name} no longer a Manually Kept Account: offered for pairing again"
                    },
                )
                if (manuallyKept) KeepOutcome.Marked else KeepOutcome.Unmarked
            }
            // UC-009 BR-001: A Budget Account that receives through an Account Mapping is not a Manually Kept Account.
            is KeptOutcome.StillReceives -> KeepOutcome.StillReceives(outcome.bankAccountName)
            KeptOutcome.NotFound -> KeepOutcome.NotFree
        }
    }
}
