package slussen.app.ui.mappings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.connection.AddedBankAccount
import slussen.core.connection.FreeBudgetAccount
import slussen.core.connection.KnownBank
import slussen.core.connection.PairOutcome
import slussen.core.connection.PairingCard
import slussen.core.mapping.ClearedLink
import slussen.core.mapping.LinkedBudgetAccount
import slussen.core.mapping.TypeAndBank
import slussen.core.mapping.TypeAndBankOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ServiceKey
import slussen.pluginapi.value.CurrencyCode

/** One Bank Account to map, on one line: name, last four digits, Source, currency and balance (UC-009 BR-014, UC-013 BR-002). */
data class ToMapLine(val offeredBankAccountId: Long, val title: String, val digits: String?, val detail: String, val balance: String)

/** One Bank Account → Budget Account line of the Account Mappings. */
data class MappingLine(
    val bankAccountId: Long,
    val bankAccount: String,
    val detail: String,
    val budgetAccount: String,
    val note: String?,
)

data class BudgetGroup(val title: String, val lines: List<MappingLine>)

/** A Left-Out Bank Account under Not imported, leading to its Data Provider's page (UC-009 A15, BR-013, BR-014). */
data class LeftOutLine(val leftOutBankAccountId: Long, val title: String, val service: ServiceKey)

/** A Manually Kept Account under Kept by hand, leading to the Budget App's page (UC-009 A16, BR-013, BR-014). */
data class KeptLine(val budgetAccountId: Long, val title: String, val detail: String)

/** A free Budget Account of a served Budget (UC-013 BR-002, BR-013), with whether it can be chosen for the current Bank Account. */
data class FreeBudgetAccountLine(
    val budgetAccountId: Long,
    val title: String,
    val detail: String,
    val balance: String,
    val choosable: Boolean = true,
)

/** Every free Budget Account of one served Budget, in the full picker (`account-mappings.html#pair-picker`). */
data class PickerGroup(val title: String, val budgetAccounts: List<FreeBudgetAccountLine>)

/**
 * UC-013 steps 9-11 (`account-mappings.html#pair-name`, `#pair-start-date`, `#pair-bank`): the card of the current Bank
 * Account to map, with its progress, the free Budget Accounts of its currency and the one proposed (BR-013), the full
 * picker, the proposed name (BR-004), Bank Account type and Bank (BR-009), the currency (BR-010) and the Start Date.
 */
data class CardUi(
    val offeredBankAccountId: Long,
    val progress: String,
    val title: String,
    val balance: String,
    val detail: String,
    val listedHeading: String,
    val listed: List<FreeBudgetAccountLine>,
    val proposalNote: String,
    val chosenBudgetAccountId: Long?,
    val pickerOpen: Boolean,
    val picker: List<PickerGroup>,
    val proposedName: String,
    val proposedType: BankAccountType,
    val proposedBank: KnownBank?,
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    val note: String,
    /** BR-010: the currency, stated by the Source, or proposed from the chosen Budget Account when the Source states none. */
    val currency: String,
    val currencyStatedBySource: Boolean,
    val currencyNote: String,
)

/** Step 15 (`account-mappings.html#pair-summary`): what was added and what was skipped for now. */
data class SummaryUi(val added: List<String>, val skipped: List<String>)

/** Where the Account Mappings are: the list, the card of one Bank Account at a time, or the summary. */
enum class MappingsStage { List, Card, Summary }

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

/**
 * The Account Mappings (UC-009 step 1, BR-013, BR-014; `account-mappings.html#to-map`, `#map`): the Bank Accounts to map
 * first, one line each, then the Bank Accounts by Budget, then, only when there is one, Not imported and Kept by hand,
 * read-only; the card of one Bank Account at a time and its summary (UC-013 steps 8-15, A5, A8); the Bank Account type
 * and Bank sheet (UC-009 A14).
 */
data class AccountMappingsUi(
    val stage: MappingsStage,
    val toMapTitle: String,
    val toMap: List<ToMapLine>,
    val groups: List<BudgetGroup>,
    val leftOutTitle: String,
    val leftOut: List<LeftOutLine>,
    val keptTitle: String,
    val kept: List<KeptLine>,
    val card: CardUi?,
    val summary: SummaryUi?,
    val typeAndBank: TypeAndBankUi?,
    val message: String?,
)

/**
 * The Account Mappings, opened from their entry in Settings once UC-013 BR-015 holds: the Bank Accounts to map, each
 * opening the card of one Bank Account at a time (UC-013 steps 8-15), the Bank Accounts by Budget with the Bank Account
 * type and Bank of each changed here (UC-009 A14), and Not imported and Kept by hand, which lead to the service pages
 * where they are changed (UC-009 A15, A16, BR-013).
 */
class AccountMappingsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<AccountMappingsUi?>(null)
    val state: StateFlow<AccountMappingsUi?> = mutableState

    /** The choice waiting for the Operator's save after the statement of A14 step 3. */
    private var pending: Pair<BankAccountType, BankChoice>? = null

    fun load() {
        viewModelScope.launch { show(typeAndBank = null, message = null) }
    }

    /** Steps 9-14: the Bank Accounts to map taken one at a time, and what became of each so far. */
    private var queue: List<Long> = emptyList()
    private var position = 0
    private val added = mutableListOf<String>()
    private val skipped = mutableListOf<String>()
    private var currentCard: PairingCard? = null
    private var chosenBudgetAccount: Long? = null
    private var pickerOpen = false

    private fun empty() = AccountMappingsUi(
        stage = MappingsStage.List,
        toMapTitle = "",
        toMap = emptyList(),
        groups = emptyList(),
        leftOutTitle = "",
        leftOut = emptyList(),
        keptTitle = "",
        kept = emptyList(),
        card = null,
        summary = null,
        typeAndBank = null,
        message = null,
    )

    private fun current(): AccountMappingsUi = mutableState.value ?: empty()

    private fun digitsOf(lastFour: String?): String? = lastFour?.let { "••$it" }

    // UC-009 BR-014: The Account Mappings list the Bank Accounts to map first, one line each, then the Bank Accounts by Budget, then the two short read-only lists.
    private suspend fun show(typeAndBank: TypeAndBankUi?, message: String?) {
        val list = services.mappings.list()
        val formats = services.formats
        val groups = list.byBudget.map { budget -> BudgetGroup("${budget.name} · ${budget.currency.code}", budget.bankAccounts.map(::line)) } +
            listOfNotNull(
                list.notMapped.takeIf { it.isNotEmpty() }?.let { BudgetGroup("Not mapped", it.map(::line)) },
                list.retired.takeIf { it.isNotEmpty() }?.let { BudgetGroup("Retired Accounts", it.map(::line)) },
            )
        mutableState.value = current().copy(
            stage = MappingsStage.List,
            toMapTitle = "To map · imported, not yet added · ${list.toMap.size}",
            toMap = list.toMap.map { line ->
                val offer = line.offered
                ToMapLine(
                    offeredBankAccountId = offer.offeredBankAccountId,
                    title = offer.nameOrProduct ?: offer.kind.orEmpty(),
                    digits = digitsOf(offer.lastFourDigits),
                    detail = listOfNotNull(line.sourceName, offer.currency?.code).joinToString(" · "),
                    balance = offer.balance?.let { formats.amount(it) }.orEmpty(),
                )
            },
            groups = groups,
            leftOutTitle = "Not imported · Left-Out Bank Accounts · ${list.leftOut.size}",
            // UC-009 BR-013: A Left-Out Bank Account is listed read-only and leads to its Data Provider's page, where its import is switched on again.
            leftOut = list.leftOut.map { LeftOutLine(it.leftOutBankAccountId, "${it.name} · ${it.sourceName}", ServiceKey.dataProvider(it.dataProviderId)) },
            keptTitle = "Kept by hand · Manually Kept Accounts · ${list.manuallyKept.size}",
            // UC-009 BR-013: A Manually Kept Account is listed read-only and leads to the Budget App's page, where it is marked and unmarked.
            kept = list.manuallyKept.map { KeptLine(it.budgetAccountId, it.name, "${it.budgetName} · ${it.budgetCurrency.code}") },
            card = null,
            summary = null,
            typeAndBank = typeAndBank,
            message = message,
        )
    }

    /** Step 8: the Operator maps every Bank Account to map, one at a time, in the order listed. */
    fun mapAll() {
        viewModelScope.launch { start(current().toMap.map { it.offeredBankAccountId }) }
    }

    /** Step 8: the Operator maps one Bank Account to map. */
    fun mapOne(offeredBankAccountId: Long) {
        viewModelScope.launch { start(listOf(offeredBankAccountId)) }
    }

    private suspend fun start(chosen: List<Long>) {
        queue = chosen
        position = 0
        added.clear()
        skipped.clear()
        showCard(message = null)
    }

    /** Step 9: the card of the current Bank Account to map, or the summary of step 15 after the last one. */
    private suspend fun showCard(message: String?) {
        while (position < queue.size) {
            val card = services.connect.card(queue[position])
            if (card != null) {
                currentCard = card
                // UC-013 BR-013: The only free Budget Account of the currency is proposed; the Operator accepts it or chooses another.
                chosenBudgetAccount = card.proposedBudgetAccountId
                pickerOpen = false
                renderCard(message)
                return
            }
            position++
        }
        currentCard = null
        mutableState.value = current().copy(
            stage = MappingsStage.Summary,
            card = null,
            summary = SummaryUi(added.toList(), skipped.toList()),
            message = message,
        )
    }

    private fun freeLine(free: FreeBudgetAccount) = FreeBudgetAccountLine(
        free.budgetAccountId,
        free.name,
        "${free.budgetName} · ${free.budgetCurrency.code}",
        free.balance?.let { services.formats.amount(it) }.orEmpty(),
    )

    private fun renderCard(message: String?) {
        val card = currentCard ?: return
        val offer = card.offered
        val currency = card.currency?.code
        val chosen = card.allFree.firstOrNull { it.budgetAccountId == chosenBudgetAccount }
        val source = card.sourceName
        mutableState.value = current().copy(
            stage = MappingsStage.Card,
            card = CardUi(
                offeredBankAccountId = offer.offeredBankAccountId,
                progress = "${position + 1} of ${queue.size}",
                title = offer.nameOrProduct ?: offer.kind.orEmpty(),
                balance = offer.balance?.let { services.formats.amount(it) }.orEmpty(),
                detail = listOfNotNull(source, offer.bankName, offer.kind, currency, digitsOf(offer.lastFourDigits)).joinToString(" · "),
                listedHeading = currency?.let { "Free Budget Accounts in $it" } ?: "Free Budget Accounts",
                listed = card.listed.map(::freeLine),
                proposalNote = when {
                    card.proposedBudgetAccountId != null -> "Proposed: the only free Budget Account" + (currency?.let { " in $it" } ?: "")
                    card.listed.isEmpty() -> "No free Budget Account" + (currency?.let { " in $it" } ?: "") +
                        ": choose from all free Budget Accounts, or skip for now"
                    currency == null -> "No proposal: no currency stated and more than one Budget Account is free; choose one"
                    else -> "No proposal: more than one Budget Account in $currency is free; choose one"
                },
                chosenBudgetAccountId = chosenBudgetAccount,
                pickerOpen = pickerOpen,
                picker = card.allFree.groupBy { "${it.budgetName} · ${it.budgetCurrency.code}" }.map { (title, accounts) ->
                    PickerGroup(
                        title,
                        accounts.map { free ->
                            val fits = card.currency == null || free.budgetCurrency == card.currency
                            if (fits) freeLine(free) else freeLine(free).copy(detail = "${free.budgetCurrency.code} · not for a $currency Bank Account", choosable = false)
                        },
                    )
                },
                proposedName = card.proposedName,
                proposedType = card.proposedType,
                proposedBank = card.proposedBank,
                types = card.types,
                knownBanks = card.knownBanks,
                note = if (card.proposedBank != null) {
                    "Type and Bank proposed from what $source states · change them if they are wrong"
                } else {
                    "$source names no Bank · choose one or name a new one"
                },
                currency = currency ?: chosen?.budgetCurrency?.code.orEmpty(),
                currencyStatedBySource = currency != null,
                currencyNote = if (currency != null) "Stated by $source" else "$source states no currency · state it, the Budget Account's by default",
            ),
            message = message,
        )
    }

    /** Step 11: the Operator chooses a Budget Account on the card, also from the full picker. */
    fun chooseBudgetAccount(budgetAccountId: Long) {
        chosenBudgetAccount = budgetAccountId
        pickerOpen = false
        renderCard(message = null)
    }

    fun openPicker() {
        pickerOpen = true
        renderCard(message = current().message)
    }

    fun closePicker() {
        pickerOpen = false
        renderCard(message = current().message)
    }

    /** Steps 11-14: [startDate] is empty or `yyyy-MM-dd`; [currency] as the Operator states it when the Source states none. */
    fun addAndContinue(name: String, type: BankAccountType?, bank: BankChoice?, startDate: String, currency: String = "") {
        val card = currentCard ?: return
        val date = Texts.startDate(startDate).getOrElse {
            mutableState.value = current().copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        val budgetAccountId = chosenBudgetAccount ?: run {
            mutableState.value = current().copy(message = "Choose a Budget Account · not added")
            return
        }
        val stated = if (card.currency != null) null else currency.trim().uppercase().takeIf { it.isNotEmpty() }
        if (stated != null && !Texts.isCurrencyCode(stated)) {
            mutableState.value = current().copy(message = "The currency is an ISO 4217 code of three capital letters, like USD · not added")
            return
        }
        viewModelScope.launch {
            val offer = card.offered
            when (val outcome = services.connect.add(offer.offeredBankAccountId, budgetAccountId, name, type, bank, date, stated?.let(::CurrencyCode))) {
                is PairOutcome.Added -> {
                    val budgetAccount = card.allFree.firstOrNull { it.budgetAccountId == budgetAccountId }?.name.orEmpty()
                    added += listOfNotNull("${outcome.name} → $budgetAccount", digitsOf(offer.lastFourDigits)).joinToString(" ")
                    position++
                    showCard(message = "${outcome.name} added → $budgetAccount")
                }
                // A5 of UC-013 continues at step 11: the card stays open to choose again.
                else -> mutableState.value = current().copy(message = Texts.pairRefusal(outcome))
            }
        }
    }

    /** A8: the current Bank Account to map is skipped for now; it is neither added nor left out and stays to map. */
    fun skipForNow() {
        val card = currentCard ?: return
        skipped += listOfNotNull(card.offered.nameOrProduct ?: card.offered.kind.orEmpty(), digitsOf(card.offered.lastFourDigits)).joinToString(" ") +
            " · still to map"
        position++
        viewModelScope.launch { showCard(message = null) }
    }

    /** Step 15: Done returns to the Account Mappings. */
    fun done() {
        queue = emptyList()
        position = 0
        currentCard = null
        viewModelScope.launch { show(typeAndBank = null, message = null) }
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
