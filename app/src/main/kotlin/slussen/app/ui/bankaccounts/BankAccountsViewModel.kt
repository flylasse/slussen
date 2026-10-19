package slussen.app.ui.bankaccounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.setup.FreeBudgetAccountLine
import slussen.app.ui.setup.LeftOutLine
import slussen.core.connection.AddedBankAccount
import slussen.core.connection.BroughtBack
import slussen.core.connection.KeepOutcome
import slussen.core.connection.KnownBank
import slussen.core.mapping.ClearedLink
import slussen.core.mapping.LinkedBudgetAccount
import slussen.core.mapping.TypeAndBank
import slussen.core.mapping.TypeAndBankOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ServiceKey

/** One Bank Account → Budget Account line of the Bank Accounts and Account Mappings list. */
data class MappingLine(
    val bankAccountId: Long,
    val bankAccount: String,
    val detail: String,
    val budgetAccount: String,
    val note: String?,
)

data class BudgetGroup(val title: String, val lines: List<MappingLine>)

/** One choice of the sheet, with what it means (`account-mappings.html#type-and-bank`). */
data class Option<T>(val value: T, val text: String)

/** UC-009 A14: the sheet that changes the Bank Account type or the Bank of one Bank Account (BR-012). */
data class TypeAndBankUi(
    val bankAccountId: Long,
    val title: String,
    val type: BankAccountType,
    val bank: KnownBank,
    val typeOptions: List<Option<BankAccountType>>,
    val bankOptions: List<Option<KnownBank>>,
    val note: String,
    /** A14 step 3: what saving would clear, asked before anything is stored. */
    val clearingQuestion: String?,
)

/** A Manually Kept Account under Kept by hand (`account-mappings.html#kept-by-hand`, UC-009 BR-013). */
data class KeptLine(val budgetAccountId: Long, val title: String, val detail: String)

/** UC-009 A16: the question before a Manually Kept Account is unmarked (`account-mappings.html#unmark-kept`). */
data class UnmarkQuestion(val budgetAccountId: Long, val question: String, val detail: String)

/**
 * UC-009 step 1, BR-013 (`account-mappings.html#map`, `#not-imported`, `#kept-by-hand`): apart from the Bank Accounts,
 * Not imported (the Left-Out Bank Accounts) and Kept by hand (the Manually Kept Accounts), with the free Budget
 * Accounts that can be marked.
 */
data class ApartUi(
    val leftOutTitle: String,
    val leftOut: List<LeftOutLine>,
    val keptTitle: String,
    val manuallyKept: List<KeptLine>,
    val markable: List<FreeBudgetAccountLine>,
    val unmarkQuestion: UnmarkQuestion?,
)

data class BankAccountsUi(val groups: List<BudgetGroup>, val typeAndBank: TypeAndBankUi?, val message: String?, val apart: ApartUi)

/**
 * Bank Accounts and Account Mappings, from Settings (UC-013 BR-008: Bank Accounts are added from here later), with the
 * Bank Account type and Bank of each one changed here (UC-009 A14), and Not imported and Kept by hand apart from them
 * (UC-009 A15, A16, BR-013).
 */
class BankAccountsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<BankAccountsUi?>(null)
    val state: StateFlow<BankAccountsUi?> = mutableState

    /** The choice waiting for the Operator's save after the statement of A14 step 3. */
    private var pending: Pair<BankAccountType, BankChoice>? = null

    fun load() {
        viewModelScope.launch { show(typeAndBank = null, message = null) }
    }

    private suspend fun show(typeAndBank: TypeAndBankUi?, message: String?) {
        val budgets = services.connections.servicePage(ServiceKey.budgetApp).budgetsInUse
        val added = services.connect.addedBankAccounts()
        val groups = budgets.mapNotNull { budget ->
            val lines = added.filter { it.budgetName == budget.name && !it.retired }.map(::line)
            if (lines.isEmpty()) null else BudgetGroup("${budget.name} · ${budget.currency.code}", lines)
        }
        val unmapped = added.filter { it.budgetAccountName == null && !it.retired }.map(::line)
        val retired = added.filter { it.retired }.map(::line)
        mutableState.value = BankAccountsUi(
            groups = groups + listOfNotNull(
                unmapped.takeIf { it.isNotEmpty() }?.let { BudgetGroup("Not mapped", it) },
                retired.takeIf { it.isNotEmpty() }?.let { BudgetGroup("Retired Accounts", it) },
            ),
            typeAndBank = typeAndBank,
            message = message,
            apart = apartUi(),
        )
    }

    /** UC-009 step 1, BR-013: Not imported and Kept by hand, apart from the Bank Accounts. */
    private suspend fun apartUi(): ApartUi {
        val apart = services.mappings.apart()
        return ApartUi(
            leftOutTitle = "Not imported · ${apart.leftOut.size}",
            leftOut = apart.leftOut.map { LeftOutLine(it.leftOutBankAccountId, it.name, "${it.sourceName} · left out ${services.formats.date(it.leftOutAt)}") },
            keptTitle = "Kept by hand · ${apart.manuallyKept.size}",
            manuallyKept = apart.manuallyKept.map {
                KeptLine(it.budgetAccountId, it.name, "${it.budgetName} · ${it.budgetCurrency.code} · kept by hand · not offered for pairing")
            },
            markable = apart.markable.map {
                FreeBudgetAccountLine(
                    it.budgetAccountId,
                    it.name,
                    "${it.budgetName} · ${it.budgetCurrency.code}",
                    it.balance?.let { balance -> services.formats.amount(balance) }.orEmpty(),
                )
            },
            unmarkQuestion = null,
        )
    }

    private fun keptName(budgetAccountId: Long): String = mutableState.value?.apart?.let { apart ->
        apart.manuallyKept.firstOrNull { it.budgetAccountId == budgetAccountId }?.title
            ?: apart.markable.firstOrNull { it.budgetAccountId == budgetAccountId }?.title
    }.orEmpty()

    /** UC-009 A15: the Operator brings a Left-Out Bank Account back, without any confirmation (BR-013). */
    fun bringBack(leftOutBankAccountId: Long) {
        viewModelScope.launch {
            val message = when (val outcome = services.mappings.bringBack(leftOutBankAccountId)) {
                is BroughtBack.Offered -> if (outcome.offeredNow) {
                    "${outcome.name} brought back · offered for pairing again"
                } else {
                    "${outcome.name} brought back · offered for pairing from the next delivery that holds it"
                }
                BroughtBack.NotFound -> null
                is BroughtBack.Unavailable -> Texts.refusal(outcome.reason)
            }
            show(typeAndBank = null, message = message)
        }
    }

    /** UC-009 A16: the Operator marks a free Budget Account as kept by hand, without any confirmation (BR-013). */
    fun markManuallyKept(budgetAccountId: Long) {
        viewModelScope.launch {
            val name = keptName(budgetAccountId)
            val message = when (val outcome = services.mappings.setManuallyKept(budgetAccountId, manuallyKept = true)) {
                KeepOutcome.Marked -> "$name kept by hand · not offered for pairing"
                is KeepOutcome.StillReceives -> "$name receives ${outcome.bankAccountName} · not kept by hand"
                KeepOutcome.NotFree, KeepOutcome.NotManuallyKept, KeepOutcome.Unmarked -> "$name is not a free Budget Account · not kept by hand"
                is KeepOutcome.Unavailable -> Texts.refusal(outcome.reason)
            }
            show(typeAndBank = null, message = message)
        }
    }

    /** UC-009 A16: the Operator asks to unmark a Manually Kept Account; it is unmarked once confirmed. */
    fun askUnmark(budgetAccountId: Long) {
        viewModelScope.launch {
            val name = keptName(budgetAccountId)
            val budgetApp = services.connect.credentialSteps().firstOrNull { it.isBudgetApp }?.name ?: "the Budget App"
            mutableState.value = mutableState.value?.let {
                it.copy(
                    apart = it.apart.copy(
                        unmarkQuestion = UnmarkQuestion(budgetAccountId, "Unmark $name?", "$name is offered for pairing again. Nothing in $budgetApp changes."),
                    ),
                )
            }
        }
    }

    fun cancelUnmark() {
        mutableState.value = mutableState.value?.let { it.copy(apart = it.apart.copy(unmarkQuestion = null)) }
    }

    fun confirmUnmark() {
        val question = mutableState.value?.apart?.unmarkQuestion ?: return
        viewModelScope.launch {
            val name = keptName(question.budgetAccountId)
            val message = when (val outcome = services.mappings.setManuallyKept(question.budgetAccountId, manuallyKept = false)) {
                KeepOutcome.Unmarked -> "$name unmarked · offered for pairing again"
                is KeepOutcome.Unavailable -> Texts.refusal(outcome.reason)
                else -> "$name is not kept by hand"
            }
            show(typeAndBank = null, message = message)
        }
    }

    /** UC-009 step 1: each Bank Account with its name, its Bank, its Bank Account type and its Source. */
    private fun line(account: AddedBankAccount) = MappingLine(
        bankAccountId = account.bankAccountId,
        bankAccount = account.name,
        detail = "${account.bankName} · ${account.type.text} · ${account.sourceName}",
        budgetAccount = account.budgetAccountName ?: "Not mapped",
        note = account.startDate?.let { "Start Date $it" },
    )

    /** A14 step 1. */
    fun openTypeAndBank(bankAccountId: Long) {
        viewModelScope.launch {
            val shown = services.mappings.typeAndBank(bankAccountId) ?: return@launch
            pending = null
            mutableState.value = mutableState.value?.copy(typeAndBank = sheetOf(shown), message = null)
        }
    }

    private fun sheetOf(shown: TypeAndBank) = TypeAndBankUi(
        bankAccountId = shown.bankAccountId,
        title = "${shown.name} · ${shown.sourceName}",
        type = shown.type,
        bank = shown.bank,
        typeOptions = shown.types.map { type ->
            val note = when {
                type == shown.type -> " · current"
                shown.clearedBy[type] != null -> " · would clear its ${linkText(shown.clearedBy.getValue(type))}"
                else -> ""
            }
            Option(type, type.text + note)
        },
        bankOptions = shown.knownBanks.map { Option(it, it.name + if (it == shown.bank) " · current" else "") },
        note = "Changes no Account Mapping, identification or Import Identifier · what was sent stays as it is, so no confirmation is asked",
        clearingQuestion = null,
    )

    private fun linkText(cleared: ClearedLink): String = when (cleared.link) {
        LinkedBudgetAccount.FundedBy -> "Funded By ${cleared.budgetAccountName}"
        LinkedBudgetAccount.TransferPartner -> "Transfer Partner ${cleared.budgetAccountName}"
    }

    /** A14 steps 2-4. */
    fun saveTypeAndBank(type: BankAccountType, bank: BankChoice) {
        save(type, bank, clearingConfirmed = false)
    }

    /** A14 step 3: the Operator saves after the statement of what is cleared. */
    fun confirmClearing() {
        val (type, bank) = pending ?: return
        save(type, bank, clearingConfirmed = true)
    }

    /** A14 step 3: the Operator cancels; nothing changes. */
    fun cancelClearing() {
        pending = null
        mutableState.value = mutableState.value?.let { it.copy(typeAndBank = it.typeAndBank?.copy(clearingQuestion = null)) }
    }

    /** A14 step 2: the Operator cancels and the use case ends. */
    fun cancelTypeAndBank() {
        pending = null
        mutableState.value = mutableState.value?.copy(typeAndBank = null)
    }

    private fun save(type: BankAccountType, bank: BankChoice, clearingConfirmed: Boolean) {
        val sheet = mutableState.value?.typeAndBank ?: return
        viewModelScope.launch {
            when (val outcome = services.mappings.changeTypeAndBank(sheet.bankAccountId, type, bank, clearingConfirmed)) {
                TypeAndBankOutcome.Stored -> {
                    pending = null
                    val bankName = when (bank) {
                        is BankChoice.Known -> sheet.bankOptions.firstOrNull { it.value.bankId == bank.bankId }?.value?.name.orEmpty()
                        is BankChoice.Named -> bank.name.trim()
                    }
                    val name = sheet.title.substringBefore(" · ")
                    show(typeAndBank = null, message = "$name: ${type.text} · $bankName · stored")
                }
                is TypeAndBankOutcome.ClearingToConfirm -> {
                    pending = type to bank
                    mutableState.value = mutableState.value?.copy(
                        typeAndBank = sheet.copy(clearingQuestion = clearingQuestion(type, outcome.cleared)),
                    )
                }
                TypeAndBankOutcome.BankNotChosen -> mutableState.value = mutableState.value?.copy(message = "Choose a Bank or name a new one")
                TypeAndBankOutcome.NotFound -> show(typeAndBank = null, message = null)
                is TypeAndBankOutcome.Unavailable -> mutableState.value = mutableState.value?.copy(message = Texts.refusal(outcome.reason))
            }
        }
    }

    private fun clearingQuestion(type: BankAccountType, cleared: ClearedLink): String = when (cleared.link) {
        LinkedBudgetAccount.TransferPartner ->
            "${type.text} clears the Transfer Partner ${cleared.budgetAccountName}: a Transfer Partner belongs only to a Bank Account " +
                "that is not a credit card."
        LinkedBudgetAccount.FundedBy ->
            "${type.text} clears the Funded By ${cleared.budgetAccountName}: a Funded By belongs only to a credit card."
    }
}
