package slussen.app.ui.mappings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import slussen.core.mapping.IdentificationSheet
import slussen.core.mapping.IdentifyOutcome
import slussen.core.mapping.LinkedBudgetAccount
import slussen.core.mapping.MappingChangeOutcome
import slussen.core.mapping.MoveOutcome
import slussen.core.port.OtherSideKind
import slussen.core.port.SentThrough
import slussen.core.mapping.TypeAndBank
import slussen.core.mapping.TypeAndBankOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ServiceKey
import slussen.pluginapi.value.CurrencyCode

/** One Bank Account to map, on one line: name, last four digits, Source, currency and balance (UC-009 BR-014, UC-013 BR-002). */
data class ToMapLine(val offeredBankAccountId: Long, val title: String, val digits: String?, val detail: String, val balance: String)

/** One Bank Account → Budget Account line of the Account Mappings (UC-009 step 1). */
data class MappingLine(
    val bankAccountId: Long,
    val bankAccount: String,
    val detail: String,
    val budgetAccount: String,
    val note: String?,
    /** Its Funded By when it is a credit card, its Transfer Partner otherwise, and whether it no longer applies. */
    val otherSide: String? = null,
    /** Whether transactions were sent through its Account Mapping, and whether it is identified. */
    val state: String? = null,
)

/** The actions of the sheet of the Bank Account of one Bank Account (`account-mappings.html#sheet`). */
enum class MappingAction { Rename, StartDate, TypeAndBank, Move, Remove, Identify, OtherSide, Retire, Restore }

data class MappingActionUi(val action: MappingAction, val title: String, val detail: String?)

/** UC-009 step 2 (`#sheet`): every action for one Bank Account. */
data class BankAccountSheetUi(val bankAccountId: Long, val title: String, val subtitle: String, val actions: List<MappingActionUi>)

/** One choice of an editor: a Budget Account or an Offered Bank Account, with what it is ([tag], such as Proposed or Current). */
data class ChoiceLineUi(val id: Long, val title: String, val detail: String?, val tag: String? = null)

/** The editors behind the actions of the sheet of the Bank Account. */
sealed interface MappingEditorUi {
    val bankAccountId: Long

    /** A12 (`#rename`). */
    data class Rename(override val bankAccountId: Long, val name: String, val detail: String) : MappingEditorUi

    /** A13 (`#start-date`); [current] is empty while none is set. */
    data class StartDate(override val bankAccountId: Long, val name: String, val current: String) : MappingEditorUi

    /** A8 (`#move`, `#map-again`): the Budget Accounts it can be mapped to. */
    data class Move(override val bankAccountId: Long, val title: String, val subtitle: String, val choices: List<ChoiceLineUi>) : MappingEditorUi

    /** A9 (`#funded-by`): the Budget Accounts of its Budget that Slussen fetches; Clear removes the value. */
    data class OtherSide(override val bankAccountId: Long, val title: String, val current: String, val choices: List<ChoiceLineUi>) : MappingEditorUi

    /** Steps 2-3, A6, A10 (`#confirm`, `#identify-again`, `#no-offered`): the Offered Bank Accounts of its Source, or [none]. */
    data class Identify(
        override val bankAccountId: Long,
        val title: String,
        val subtitle: String,
        val options: List<ChoiceLineUi>,
        /** A3: the proposal can be rejected. */
        val canReject: Boolean,
        /** A10: why nothing can be identified now; null while Offered Bank Accounts are stored. */
        val none: String?,
    ) : MappingEditorUi
}

/** A question asked before anything is stored (`#retire`, `#move-confirm`, `#remove`, `#identify-after-sends`). */
data class QuestionUi(val title: String, val lines: List<String>, val confirm: String)

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
    /** UC-009 step 2: the sheet of the Bank Account of one Bank Account; null on the list. */
    val bankAccountSheet: BankAccountSheetUi? = null,
    val editor: MappingEditorUi? = null,
    val question: QuestionUi? = null,
    /** NFR-023: what the control that started a change shows until its answer, before anything is stored. */
    val pending: String? = null,
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

    /** Opens the Account Mappings: the list, never a sheet left open the last time they were shown; a read that fails is said (NFR-023). */
    fun load() {
        confirming = null
        viewModelScope.launch {
            try {
                show(typeAndBank = null, message = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = current().copy(message = AccountMappingsTexts.NOT_READ)
            }
        }
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
        listed = (list.byBudget.flatMap { it.bankAccounts } + list.notMapped + list.retired).associateBy { it.bankAccountId }
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
            // The list: no sheet of a Bank Account, editor or question left open from before (NFR-023, review A-2 4a).
            bankAccountSheet = null,
            editor = null,
            question = null,
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

    /**
     * UC-009 step 1: each Bank Account with its name, its Bank, its Bank Account type, its Source, its Start Date, its
     * Funded By or Transfer Partner, whether anything was sent through its Account Mapping and whether it is identified.
     */
    private fun line(account: AddedBankAccount) = MappingLine(
        bankAccountId = account.bankAccountId,
        bankAccount = account.name,
        detail = "${account.bankName} · ${account.type.text} · ${account.sourceName}",
        budgetAccount = account.budgetAccountName ?: "Not mapped",
        note = account.startDate?.let { "Start Date $it" },
        otherSide = AccountMappingsTexts.otherSide(account.otherSide),
        state = AccountMappingsTexts.state(account),
    )

    // ---- UC-009 step 2, A4-A13: the sheet of the Bank Account and its changes -------------------------------------------------------------

    /** The Bank Accounts of the list as the core gave them, by id, for the sheet of the Bank Account. */
    private var listed: Map<Long, AddedBankAccount> = emptyMap()

    /** What a question confirms when the Operator says yes. */
    private var confirming: (() -> Unit)? = null

    private fun budgetAppName() = services.budgetAppName

    /** Step 2 (`#sheet`): the Operator opens one Bank Account; its actions follow what it is now. */
    fun openSheet(bankAccountId: Long) {
        viewModelScope.launch {
            val account = listed[bankAccountId] ?: return@launch
            val identifiable = try {
                services.mappings.identification(bankAccountId) != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = current().copy(message = AccountMappingsTexts.NOT_READ)
                return@launch
            }
            mutableState.value = current().copy(bankAccountSheet = bankAccountSheetOf(account, identifiable), editor = null, question = null, message = null, pending = null)
        }
    }

    private fun bankAccountSheetOf(account: AddedBankAccount, identifiable: Boolean): BankAccountSheetUi {
        val mapped = account.budgetAccountName != null
        val kind = account.otherSide?.kind ?: if (account.type == BankAccountType.CreditCard) OtherSideKind.FundedBy else OtherSideKind.TransferPartner
        return BankAccountSheetUi(
            bankAccountId = account.bankAccountId,
            title = "${account.name} → ${account.budgetAccountName ?: "not mapped"}",
            subtitle = listOfNotNull(account.bankName, account.sourceName, account.startDate?.let { "Start Date $it" }, "Retired Account".takeIf { account.retired })
                .joinToString(" · "),
            actions = listOfNotNull(
                MappingActionUi(MappingAction.Rename, "Rename", null),
                MappingActionUi(MappingAction.StartDate, "Start Date", account.startDate?.toString() ?: "Not set"),
                MappingActionUi(MappingAction.TypeAndBank, "Bank Account type and Bank", "${account.type.text} · ${account.bankName}"),
                MappingActionUi(MappingAction.Move, if (mapped) "Move Account Mapping…" else "Map it to a Budget Account…", null),
                MappingActionUi(MappingAction.Remove, "Remove Account Mapping…", null).takeIf { mapped },
                MappingActionUi(MappingAction.Identify, if (account.waitsForConfirmation) "Confirm the Bank Account…" else "Identify again…", null).takeIf { identifiable },
                MappingActionUi(MappingAction.OtherSide, "${kind.text}…", AccountMappingsTexts.otherSideDetail(account.otherSide)),
                if (account.retired) MappingActionUi(MappingAction.Restore, "Restore ${account.name}", null) else MappingActionUi(MappingAction.Retire, "Retire Bank Account…", null),
            ),
        )
    }

    fun closeSheet() {
        confirming = null
        mutableState.value = current().copy(bankAccountSheet = null, editor = null, question = null, message = null, pending = null)
    }

    /** Step 2: the Operator chooses one action of the sheet of the Bank Account. */
    fun startAction(action: MappingAction) {
        val sheet = current().bankAccountSheet ?: return
        if (current().pending != null) return
        val id = sheet.bankAccountId
        val account = listed[id] ?: return
        when (action) {
            MappingAction.Rename -> editor(MappingEditorUi.Rename(id, account.name, "${account.bankName} · ${account.sourceName}"))
            MappingAction.StartDate -> editor(MappingEditorUi.StartDate(id, account.name, account.startDate?.toString().orEmpty()))
            MappingAction.TypeAndBank -> openTypeAndBank(id)
            MappingAction.Move -> viewModelScope.launch { openMove(id) }
            MappingAction.Remove -> remove(id, account.name, confirmed = null)
            MappingAction.Identify -> viewModelScope.launch { openIdentify(id) }
            MappingAction.OtherSide -> viewModelScope.launch { openOtherSide(id) }
            // A4 of UC-009: retiring asks first and says what it means.
            MappingAction.Retire -> ask(
                QuestionUi("Retire ${account.name}?", listOf("Not fetched, compared or uploaded until you restore it. History kept."), "Retire"),
            ) { change("Retiring", account.name, "retired", AccountMappingsTexts.STATUSES) { services.mappings.retire(id) } }
            MappingAction.Restore -> change("Restoring", account.name, "restored", AccountMappingsTexts.STATUSES) { services.mappings.restore(id) }
        }
    }

    private fun editor(editor: MappingEditorUi) {
        confirming = null
        mutableState.value = current().copy(editor = editor, question = null, message = null)
    }

    private fun ask(question: QuestionUi, onConfirm: () -> Unit) {
        confirming = onConfirm
        mutableState.value = current().copy(question = question, message = null)
    }

    /** The Operator confirms the question; nothing was stored before. */
    fun confirmQuestion() {
        if (current().pending != null) return
        confirming?.invoke()
    }

    /** The Operator cancels the question; nothing is stored. */
    fun cancelQuestion() {
        confirming = null
        mutableState.value = current().copy(question = null, bankAccountSheet = null, editor = null, message = null)
    }

    fun cancelEditor() {
        confirming = null
        mutableState.value = current().copy(editor = null, question = null, message = null)
    }

    /** A12: the Operator saves a new name. */
    fun saveName(name: String) {
        val editor = current().editor as? MappingEditorUi.Rename ?: return
        val trimmed = name.trim()
        change("Storing", editor.name, "renamed", follows = null, answerName = trimmed) { services.mappings.rename(editor.bankAccountId, name) }
    }

    /** A13: the Operator saves a Start Date, `yyyy-MM-dd`. */
    fun saveStartDate(text: String) {
        val editor = current().editor as? MappingEditorUi.StartDate ?: return
        val date = Texts.startDate(text).getOrElse {
            mutableState.value = current().copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        change("Storing", editor.name, date?.let { "Start Date $it" } ?: "Start Date cleared", AccountMappingsTexts.STATUSES) {
            services.mappings.setStartDate(editor.bankAccountId, date)
        }
    }

    /** A13: the Operator clears the Start Date. */
    fun clearStartDate() {
        val editor = current().editor as? MappingEditorUi.StartDate ?: return
        change("Storing", editor.name, "Start Date cleared", AccountMappingsTexts.STATUSES) { services.mappings.setStartDate(editor.bankAccountId, null) }
    }

    /** A9 step 1. */
    private suspend fun openOtherSide(id: Long) {
        val shown = readOrSay { services.mappings.otherSide(id) } ?: return
        editor(
            MappingEditorUi.OtherSide(
                id, "${shown.kind.text} of ${shown.name}", AccountMappingsTexts.otherSideDetail(shown.current),
                shown.choices.map { ChoiceLineUi(it.budgetAccountId, it.budgetAccountName, it.bankAccountName) },
            ),
        )
    }

    /** A9 steps 2-4: [budgetAccountId] is the new Funded By or Transfer Partner, or null to clear it. */
    fun saveOtherSide(budgetAccountId: Long?) {
        val editor = current().editor as? MappingEditorUi.OtherSide ?: return
        val account = listed[editor.bankAccountId] ?: return
        val kind = account.otherSide?.kind ?: if (account.type == BankAccountType.CreditCard) OtherSideKind.FundedBy else OtherSideKind.TransferPartner
        val stored = budgetAccountId?.let { id -> "${kind.text} ${editor.choices.firstOrNull { it.id == id }?.title.orEmpty()}" } ?: "${kind.text} cleared"
        change("Storing", account.name, stored, "the Transfers are detected again", kind = kind) { services.mappings.setOtherSide(editor.bankAccountId, budgetAccountId) }
    }

    /** A8 step 1. */
    private suspend fun openMove(id: Long) {
        val sheet = readOrSay { services.mappings.moveSheet(id) } ?: return
        val mapped = sheet.currentBudgetAccountName != null
        editor(
            MappingEditorUi.Move(
                id,
                if (mapped) "Move ${sheet.name} to…" else "Map ${sheet.name} to…",
                "Budget Accounts with no Account Mapping, in its currency",
                sheet.choices.map { ChoiceLineUi(it.budgetAccountId, it.budgetAccountName, listOfNotNull(it.budgetName, it.balance?.let(services.formats::amount)).joinToString(" · ")) },
            ),
        )
    }

    /** A8 steps 2-5: the Operator chooses [budgetAccountId]; after a written send, the numbers are stated and confirmed first. */
    fun moveTo(budgetAccountId: Long, confirmed: SentThrough? = null) {
        val editor = current().editor as? MappingEditorUi.Move ?: return
        val account = listed[editor.bankAccountId] ?: return
        val newBudgetAccount = editor.choices.firstOrNull { it.id == budgetAccountId }?.title
        val stored = if (account.budgetAccountName != null) "Account Mapping moved to ${newBudgetAccount.orEmpty()}" else "mapped to ${newBudgetAccount.orEmpty()}"
        moved(
            if (account.budgetAccountName != null) "Moving" else "Mapping", account.name, stored, newBudgetAccount,
            call = { services.mappings.move(editor.bankAccountId, budgetAccountId, confirmed) },
        ) { through ->
            // A8 step 3 of UC-009: the numbers are stated, and the move is stored only on Confirm, with these numbers.
            ask(
                QuestionUi(
                    "Move ${account.name} to ${newBudgetAccount.orEmpty()}?",
                    AccountMappingsTexts.moveStatement(through, account.budgetAccountName.orEmpty(), newBudgetAccount.orEmpty(), budgetAppName()),
                    "Confirm",
                ),
            ) { moveTo(budgetAccountId, through) }
        }
    }

    /** A11: the Operator removes the Account Mapping; after a written send, the numbers are stated and confirmed first. */
    private fun remove(id: Long, name: String, confirmed: SentThrough?) {
        val account = listed[id] ?: return
        moved("Removing", name, "Account Mapping removed", account.budgetAccountName, call = { services.mappings.remove(id, confirmed) }) { through ->
            // A11 step 1 of UC-009: the numbers are stated, and the removal is stored only on Remove, with these numbers.
            ask(
                QuestionUi(
                    "Remove the Account Mapping of $name?",
                    AccountMappingsTexts.removeStatement(through, name, account.budgetAccountName.orEmpty(), budgetAppName()),
                    "Remove",
                ),
            ) { remove(id, name, through) }
        }
    }

    /** Steps 2-3, A6, A10. */
    private suspend fun openIdentify(id: Long) {
        val sheet: IdentificationSheet = readOrSay { services.mappings.identification(id) } ?: return
        val proposed = sheet.proposed
        val account = listed[id]
        val confirming = proposed != null && account?.identified != true
        editor(
            MappingEditorUi.Identify(
                bankAccountId = id,
                title = if (confirming) "Confirm the Bank Account for ${sheet.name}" else "Identify ${sheet.name} again",
                subtitle = "Offered by ${sheet.sourceName} · the current identification stays in use until you confirm another",
                options = listOfNotNull(proposed).map { ChoiceLineUi(it.offeredBankAccountId, AccountMappingsTexts.offeredTitle(it), AccountMappingsTexts.offeredDetail(it), "Proposed") } +
                    sheet.others.map { ChoiceLineUi(it.offeredBankAccountId, AccountMappingsTexts.offeredTitle(it), AccountMappingsTexts.offeredDetail(it), "Current".takeIf { _ -> it.current }) },
                canReject = proposed != null,
                // A10 of UC-009: nothing offered is stored; the current identification stays in use.
                none = if (sheet.noneStored) {
                    "Nothing offered yet · it can be identified after the next Fetch of ${sheet.sourceName}; the current identification stays in use."
                } else {
                    null
                },
            ),
        )
    }

    /** Steps 4-7, A1, A6, A7: the Operator confirms [offeredBankAccountId]; after a written send, the numbers are stated and confirmed first. */
    fun identifyWith(offeredBankAccountId: Long, confirmed: SentThrough? = null) {
        val editor = current().editor as? MappingEditorUi.Identify ?: return
        val account = listed[editor.bankAccountId] ?: return
        run("Identifying") {
            val outcome = try {
                services.mappings.identify(editor.bankAccountId, offeredBankAccountId, confirmed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                IdentifyOutcome.NotStored
            }
            when (outcome) {
                is IdentifyOutcome.ToConfirm -> ask(
                    QuestionUi("Identify ${account.name} again?", AccountMappingsTexts.identifyStatement(outcome.sentThrough, account.name, budgetAppName()), "Continue"),
                ) { identifyWith(offeredBankAccountId, outcome.sentThrough) }
                IdentifyOutcome.Identified, IdentifyOutcome.Rejected -> backToList(AccountMappingsTexts.identifyAnswer(outcome, account.name))
                else -> mutableState.value = current().copy(message = AccountMappingsTexts.identifyAnswer(outcome, account.name))
            }
        }
    }

    /** A3: the Operator rejects the proposal. */
    fun rejectProposal() {
        val editor = current().editor as? MappingEditorUi.Identify ?: return
        val proposal = editor.options.firstOrNull { it.tag == "Proposed" } ?: return
        val account = listed[editor.bankAccountId] ?: return
        run("Rejecting") {
            val outcome = try {
                services.mappings.reject(editor.bankAccountId, proposal.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                IdentifyOutcome.NotStored
            }
            if (outcome == IdentifyOutcome.Rejected) {
                backToList(AccountMappingsTexts.identifyAnswer(outcome, account.name))
            } else {
                mutableState.value = current().copy(message = AccountMappingsTexts.identifyAnswer(outcome, account.name))
            }
        }
    }

    /** A read for an editor; a storage that cannot be read is said at the sheet of the Bank Account, never a crash (NFR-023). */
    private suspend fun <T> readOrSay(read: suspend () -> T?): T? = try {
        read().also { if (it == null) mutableState.value = current().copy(message = "Not shown: this Bank Account is no longer stored on this device") }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        mutableState.value = current().copy(message = AccountMappingsTexts.NOT_READ)
        null
    }

    /**
     * One change: the control shows at once what it does, or what it waits for (NFR-023, ADR-007 §1), takes no second tap,
     * then answers; a stored change returns to the list with its answer, a refusal stays at the control with its answer.
     */
    private fun run(doing: String, block: suspend () -> Unit) {
        if (current().pending != null) return
        val running = services.running.value
        mutableState.value = current().copy(pending = running?.let { "$doing · waits for: ${Texts.operation(it)}" } ?: "$doing…", message = null)
        viewModelScope.launch {
            try {
                block()
            } finally {
                mutableState.value = current().copy(pending = null)
            }
        }
    }

    /** Back to the list with [answer], the sheet of the Bank Account, editor and question closed. */
    private suspend fun backToList(answer: String) {
        confirming = null
        mutableState.value = current().copy(bankAccountSheet = null, editor = null, question = null)
        reshow(answer)
    }

    /** The list read again with [answer]; a read that fails keeps the answer and says so (NFR-023). */
    private suspend fun reshow(answer: String) {
        try {
            show(typeAndBank = null, message = answer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = current().copy(message = "$answer · ${AccountMappingsTexts.NOT_READ}")
        }
    }

    private fun change(
        doing: String,
        name: String,
        stored: String,
        follows: String?,
        kind: OtherSideKind? = null,
        answerName: String = name,
        call: suspend () -> MappingChangeOutcome,
    ) = run(doing) {
        val outcome = try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MappingChangeOutcome.NotStored
        }
        val answer = AccountMappingsTexts.changeAnswer(outcome, if (outcome is MappingChangeOutcome.Stored) answerName else name, stored, follows, kind)
        if (outcome is MappingChangeOutcome.Stored) backToList(answer) else mutableState.value = current().copy(message = answer)
    }

    /** A move, a new Account Mapping or a removal; [toConfirm] asks the numbers the core states before it stores anything. */
    private fun moved(doing: String, name: String, stored: String, newBudgetAccount: String?, call: suspend () -> MoveOutcome, toConfirm: (SentThrough) -> Unit) =
        run(doing) {
            val outcome = try {
                call()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                MoveOutcome.NotStored
            }
            when (outcome) {
                is MoveOutcome.ToConfirm -> toConfirm(outcome.sentThrough)
                is MoveOutcome.Stored -> backToList(AccountMappingsTexts.moveAnswer(outcome, name, stored, newBudgetAccount))
                else -> mutableState.value = current().copy(message = AccountMappingsTexts.moveAnswer(outcome, name, stored, newBudgetAccount))
            }
        }

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
            /** A14 step 4: stored; [after] says what is still to follow, when the Transfers could not be detected again now. */
            suspend fun stored(after: String) {
                pending = null
                val bankName = when (bank) {
                    is BankChoice.Known -> sheet.bankOptions.firstOrNull { it.value.bankId == bank.bankId }?.value?.name.orEmpty()
                    is BankChoice.Named -> bank.name.trim()
                }
                val name = sheet.title.substringBefore(" · ")
                show(typeAndBank = null, message = "$name: ${type.text} · $bankName · stored$after")
            }
            when (val outcome = services.mappings.changeTypeAndBank(sheet.bankAccountId, type, bank, clearingConfirmed)) {
                TypeAndBankOutcome.Stored -> stored(after = "")
                // Stored with the Transfers still to detect: the core keeps the Comparison marker that blocks every Upload Plan until they are.
                TypeAndBankOutcome.StoredNotDetectedYet ->
                    stored(after = " · the Transfers are detected again when Slussen next starts or compares; no Upload Plan is prepared before")
                // NFR-023: a storage that cannot be read or written is answered at the control; the sheet stays for another try.
                TypeAndBankOutcome.NotStored ->
                    mutableState.value = mutableState.value?.copy(message = "Not stored: Slussen could not use the data stored on this device · try again")
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
