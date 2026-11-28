package slussen.core.status

import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.core.port.StoredTransactionCounts

/**
 * The number the New Transaction Notification shows (UC-001 BR-014, S-B-39), from stored data only. Pure. The one
 * function that works it out, for the app's process and for the process a Scheduled Fetch runs in alike.
 */
object NewTransactionNumber {
    // UC-001 BR-014: The number is the sum, over every Bank Account that is not a Retired Account, of the counts UC-008 BR-003 shows next to it, Eligible Transactions and those waiting for a decision, so that the notification and the screens never disagree; a Bank Transaction not compared yet is in neither.
    /**
     * The sum of [counts] (Eligible Transactions and those waiting for a decision) over [bankAccounts] that are not a
     * Retired Account and are mapped into one of the served Budgets of [budgets]: the Bank Accounts the Overview and the
     * Budget tabs show a count for (UC-008 BR-003, BR-007). The count of Bank Transactions not compared yet is left out.
     */
    fun of(bankAccounts: List<StoredBankAccount>, budgets: List<StoredBudget>, counts: List<StoredTransactionCounts>): Int {
        val servedBudgetAccounts = budgets.filter { it.served }.flatMap { it.budgetAccounts }.map { it.id }.toSet()
        val shown = bankAccounts
            .filter { !it.retired && it.mapping?.budgetAccountId in servedBudgetAccounts }
            .map { it.id }
            .toSet()
        return counts.filter { it.bankAccountId in shown }.sumOf { it.eligible + it.waitingForDecision }
    }
}
