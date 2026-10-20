package slussen.app.ui.fileformat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.mappings.FreeBudgetAccountLine
import slussen.app.ui.mappings.PickerGroup
import slussen.core.connection.BalanceSource
import slussen.core.connection.FileFormatPage
import slussen.core.connection.KnownBank
import slussen.core.connection.PairOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ServiceKey
import slussen.pluginapi.value.CurrencyCode

/** One Bank Account of the File Format on its page, already mapped (UC-013 A4 step 1). */
data class FileAccountLine(val bankAccountId: Long, val title: String, val detail: String, val trailing: String)

/** An Offered Bank Account that can report the balance of a File Format's Bank Account, also a Left-Out one (BR-006). */
data class BalanceLine(val offeredBankAccountId: Long, val title: String, val detail: String)

/**
 * A4 step 2 (`connections.html#file-account`): the form that adds a Bank Account of the File Format and maps it: its
 * type, its Bank as one choice, the balance source, the Budget Account from the free ones, name, currency and Start Date.
 */
data class FileAccountFormUi(
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    val balanceSources: List<BalanceLine>,
    val budgetAccounts: List<PickerGroup>,
    val chosenBudgetAccountId: Long?,
    val chosenBalanceId: Long?,
    /** BR-010: the currency proposed, from the balance source when it states one, else from the chosen Budget Account. */
    val currency: String,
    val currencyNote: String,
)

/** A File Format's page (UC-013 A4, BR-006; `connections.html`, the File Format page and its state before a Budget is served). */
data class FileFormatUi(
    val name: String,
    val kind: String,
    val bankAccounts: List<FileAccountLine>,
    val canAdd: Boolean,
    /** BR-006: while no Budget is served, why a Bank Account cannot be added yet. */
    val waitNote: String?,
    val form: FileAccountFormUi?,
    val message: String?,
    val footnote: String,
)

/** The page of one File Format, opened from its entry in Settings (UC-013 A4, UC-010 BR-010). */
class FileFormatViewModel(private val services: UiServices, private val fileFormatId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow<FileFormatUi?>(null)
    val state: StateFlow<FileFormatUi?> = mutableState

    private var page: FileFormatPage? = null
    private var formOpen = false
    private var chosenBudgetAccountId: Long? = null
    private var chosenBalanceId: Long? = null
    private var proposedCurrency: CurrencyCode? = null

    fun load() {
        viewModelScope.launch { reload(message = null) }
    }

    private suspend fun reload(message: String?) {
        val shown = services.connect.fileFormatPage(fileFormatId).also { page = it }
        val budgetAppName = services.settingsOverview.servicePage(ServiceKey.budgetApp).name
        mutableState.value = FileFormatUi(
            name = shown.name,
            kind = "File Format · Transaction Files",
            bankAccounts = shown.bankAccounts.map { bankAccount ->
                FileAccountLine(
                    bankAccountId = bankAccount.bankAccountId,
                    title = bankAccount.name,
                    detail = listOfNotNull(
                        bankAccount.bankName,
                        bankAccount.type.text,
                        bankAccount.currency.code,
                        bankAccount.balanceFrom?.let { "balance from ${balanceTitle(it).replace(" · ", " ")}" },
                    ).joinToString(" · "),
                    trailing = "Added → ${bankAccount.budgetAccountName ?: "not mapped"}",
                )
            },
            canAdd = shown.canAdd,
            // UC-013 BR-006: Adding a Bank Account of a File Format waits for a served Budget, since it is mapped as it is added.
            waitNote = if (shown.canAdd) null else "Available once $budgetAppName is set up and a Budget is chosen.",
            form = if (formOpen && shown.canAdd) formOf(shown) else null,
            message = message,
            footnote = "Its Transaction Files are imported from the Bank Account. Its Account Mapping is changed in Account Mappings.",
        )
    }

    private fun balanceTitle(source: BalanceSource): String {
        val offer = source.offered
        return "${source.sourceName} · " + listOfNotNull(offer.nameOrProduct ?: offer.kind, offer.lastFourDigits?.let { "••$it" }).joinToString(" ")
    }

    private fun formOf(shown: FileFormatPage): FileAccountFormUi {
        val formats = services.formats
        val chosenSource = shown.balanceSources.firstOrNull { it.offered.offeredBankAccountId == chosenBalanceId }
        val chosenBudgetAccount = shown.freeBudgetAccounts.firstOrNull { it.budgetAccountId == chosenBudgetAccountId }
        return FileAccountFormUi(
            types = shown.types,
            knownBanks = shown.knownBanks,
            // UC-013 BR-006: The Offered Bank Accounts offered for the balance include the Left-Out ones.
            balanceSources = shown.balanceSources.map { BalanceLine(it.offered.offeredBankAccountId, balanceTitle(it), if (it.leftOut) "not imported" else "") },
            budgetAccounts = shown.freeBudgetAccounts.groupBy { "${it.budgetName} · ${it.budgetCurrency.code}" }.map { (title, accounts) ->
                PickerGroup(title, accounts.map { FreeBudgetAccountLine(it.budgetAccountId, it.name, "${it.budgetName} · ${it.budgetCurrency.code}", it.balance?.let(formats::amount).orEmpty()) })
            },
            chosenBudgetAccountId = chosenBudgetAccountId,
            chosenBalanceId = chosenBalanceId,
            currency = proposedCurrency?.code.orEmpty(),
            currencyNote = when {
                chosenSource?.offered?.currency != null -> "Stated by ${chosenSource.sourceName} for the balance"
                chosenBudgetAccount != null -> "Proposed from the Budget Account ${chosenBudgetAccount.name}"
                else -> "State the currency; proposed from the balance source or the Budget Account"
            },
        )
    }

    /** A4 step 2: the Operator asks to add a Bank Account of this File Format. */
    fun openForm() {
        if (page?.canAdd != true) return
        formOpen = true
        chosenBudgetAccountId = null
        chosenBalanceId = null
        proposedCurrency = null
        viewModelScope.launch { reload(message = null) }
    }

    fun cancelForm() {
        formOpen = false
        viewModelScope.launch { reload(message = null) }
    }

    /** A4 step 2, BR-010: the Budget Account or the balance source chosen; the currency proposed follows them. */
    fun choose(budgetAccountId: Long?, balanceOfferedBankAccountId: Long?) {
        chosenBudgetAccountId = budgetAccountId
        chosenBalanceId = balanceOfferedBankAccountId
        viewModelScope.launch {
            proposedCurrency = services.connect.proposedFileFormatCurrency(budgetAccountId, balanceOfferedBankAccountId)
            reload(message = null)
        }
    }

    /** A4 steps 2-3: [startDate] empty or `yyyy-MM-dd`, [currency] an ISO 4217 code. */
    fun add(type: BankAccountType?, bank: BankChoice?, name: String, currency: String, startDate: String) {
        val date = Texts.startDate(startDate).getOrElse {
            mutableState.value = mutableState.value?.copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        val budgetAccountId = chosenBudgetAccountId ?: run {
            mutableState.value = mutableState.value?.copy(message = "Choose a Budget Account · not added")
            return
        }
        val stated = currency.trim().uppercase().takeIf { it.isNotEmpty() }
        if (stated != null && !Texts.isCurrencyCode(stated)) {
            mutableState.value = mutableState.value?.copy(message = "The currency is an ISO 4217 code of three capital letters, like USD · not added")
            return
        }
        viewModelScope.launch {
            val outcome = services.connect.addFromTransactionFiles(
                fileFormatId, type, bank, chosenBalanceId, budgetAccountId, name, date, stated?.let(::CurrencyCode),
            )
            if (outcome is PairOutcome.Added) {
                val budgetAccount = page?.freeBudgetAccounts?.firstOrNull { it.budgetAccountId == budgetAccountId }?.name.orEmpty()
                formOpen = false
                reload(message = "${outcome.name} added and mapped → $budgetAccount · its transactions come from its Transaction Files")
            } else {
                mutableState.value = mutableState.value?.copy(message = Texts.pairRefusal(outcome))
            }
        }
    }
}
