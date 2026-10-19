package slussen.app.ui.bankaccounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.UiServices
import slussen.core.connection.AddedBankAccount
import slussen.core.port.ServiceKey

/** One Bank Account → Budget Account line of the Bank Accounts and Account Mappings list. */
data class MappingLine(val bankAccount: String, val detail: String, val budgetAccount: String, val note: String?)

data class BudgetGroup(val title: String, val lines: List<MappingLine>)

/** Bank Accounts and Account Mappings, from Settings (UC-013 BR-008: Bank Accounts are added from here later). */
class BankAccountsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<List<BudgetGroup>?>(null)
    val state: StateFlow<List<BudgetGroup>?> = mutableState

    fun load() {
        viewModelScope.launch {
            val budgets = services.connections.servicePage(ServiceKey.budgetApp).budgetsInUse
            val added = services.connect.addedBankAccounts()
            val groups = budgets.mapNotNull { budget ->
                val lines = added.filter { it.budgetName == budget.name && !it.retired }.map(::line)
                if (lines.isEmpty()) null else BudgetGroup("${budget.name} · ${budget.currency.code}", lines)
            }
            val unmapped = added.filter { it.budgetAccountName == null && !it.retired }.map(::line)
            val retired = added.filter { it.retired }.map(::line)
            mutableState.value = groups + listOfNotNull(
                unmapped.takeIf { it.isNotEmpty() }?.let { BudgetGroup("Not mapped", it) },
                retired.takeIf { it.isNotEmpty() }?.let { BudgetGroup("Retired Accounts", it) },
            )
        }
    }

    private fun line(account: AddedBankAccount) = MappingLine(
        bankAccount = account.name,
        detail = "${account.bankName} · ${account.type.text} · ${account.sourceName}",
        budgetAccount = account.budgetAccountName ?: "Not mapped",
        note = account.startDate?.let { "Start Date $it" },
    )
}
