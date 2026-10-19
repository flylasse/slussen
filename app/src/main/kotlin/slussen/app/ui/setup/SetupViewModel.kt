package slussen.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.connection.BringIn
import slussen.core.connection.BroughtBack
import slussen.core.connection.BudgetChoice
import slussen.core.connection.BudgetsRead
import slussen.core.connection.FreeBudgetAccount
import slussen.core.connection.KeepOutcome
import slussen.core.connection.KnownBank
import slussen.core.connection.ListedBudget
import slussen.core.connection.OfferRemedy
import slussen.core.connection.OfferedForPairing
import slussen.core.connection.PairOutcome
import slussen.core.connection.PairingCard
import slussen.core.credential.CredentialStatus
import slussen.core.fetch.FetchStart
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ServiceKey
import slussen.pluginapi.value.CurrencyCode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The first-run order of UC-013 BR-008. */
enum class SetupStep { Credentials, Budgets, BankAccounts }

/**
 * The two pairing steps of UC-013 (`setup.html#pair`): choose what to bring in (steps 5-6), the stated numbers
 * (steps 7-8, A12, A13), one Bank Account at a time (steps 9-14, A8, A9), and the summary (step 15).
 */
enum class PairingStage { Choose, Confirm, Card, Summary }

/** One service on the credentials step (UC-013 step 1). */
data class CredentialLine(val service: ServiceKey, val name: String, val kind: String, val status: String)

/** One Budget the Budget App lists, with whether the Operator chose it (UC-013 steps 2-3). */
data class BudgetChoiceLine(val reference: String, val name: String, val currency: String, val chosen: Boolean)

/** One Offered Bank Account neither added nor left out, with whether the Operator chose to bring it in (step 5, BR-002, BR-012). */
data class OfferedLine(
    val offeredBankAccountId: Long,
    val title: String,
    val detail: String,
    val balance: String,
    val chosen: Boolean = false,
) {
    /** The last four digits as shown, or null. */
    val digits: String? get() = detail.split(" · ").firstOrNull { it.startsWith("••••") }
}

/** The Offered Bank Accounts of one Bank within a Source (step 5, BR-002). */
data class BankGroup(val name: String, val offered: List<OfferedLine>)

/** One Source on the pairing step, grouped by Bank, or the remedy when it offers nothing (A3, A6). */
data class SourceSection(
    val name: String,
    val banks: List<BankGroup>,
    val proposedMatches: List<String>,
    val remedy: String?,
    /** A3: the Fetch of this Data Provider that offers its Bank Accounts (UC-001), or null. */
    val fetchAction: String?,
    val service: ServiceKey,
) {
    /** Every Offered Bank Account of the Source to choose from, in the order shown. */
    val offered: List<OfferedLine> get() = banks.flatMap { it.offered }
}

/** A Left-Out Bank Account under Not imported (step 5, A10, BR-011). */
data class LeftOutLine(val leftOutBankAccountId: Long, val title: String, val detail: String)

/** A free Budget Account of a chosen Budget (BR-002, BR-013), with whether it can be chosen for the current Bank Account. */
data class FreeBudgetAccountLine(
    val budgetAccountId: Long,
    val title: String,
    val detail: String,
    val balance: String,
    val choosable: Boolean = true,
)

/** Every free Budget Account of one served Budget, in the full picker (`setup.html#pair-picker`). */
data class PickerGroup(val title: String, val budgetAccounts: List<FreeBudgetAccountLine>)

/** Steps 7-8, A12: the numbers brought in and left out, stated before anything is left out (BR-012). */
data class StatementUi(val question: String, val broughtIn: String?, val leftOut: String?, val confirmAction: String)

/** A9: the question before a Budget Account is marked as kept by hand (`setup.html#pair-kept-by-hand`). */
data class KeepQuestion(val budgetAccountId: Long, val question: String, val detail: String)

/**
 * Steps 9-11 (`setup.html#pair-name`, `#start-date`): the card of the current Offered Bank Account, with its progress,
 * the free Budget Accounts of its currency and the one proposed (BR-013), the full picker, the proposed name (BR-004),
 * Bank Account type and Bank (BR-009), the currency (BR-010) and the optional Start Date (BR-005).
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
    val keepQuestion: KeepQuestion?,
)

/** Step 15 (`setup.html#pair-summary`): what was added, skipped for now and left out. */
data class SummaryUi(val added: List<String>, val skipped: List<String>, val leftOut: List<String>)

/** An Offered Bank Account that can report the balance of a Bank Account of a File Format, also a Left-Out one (A4, BR-006). */
data class BalanceLine(val offeredBankAccountId: Long, val title: String)

data class SetupUi(
    val step: SetupStep,
    val credentialLines: List<CredentialLine>,
    val budgetAppAccepted: Boolean,
    val budgets: List<BudgetChoiceLine>,
    val stage: PairingStage,
    val sources: List<SourceSection>,
    /** Step 6: "Bring in 5 · leave out 3", the action that states both numbers (BR-012). */
    val bringInAction: String,
    val leftOut: List<LeftOutLine>,
    val leftOutOpen: Boolean,
    /** A11: nothing new to bring in, or null. */
    val nothingLeft: String?,
    val statement: StatementUi?,
    val card: CardUi?,
    val summary: SummaryUi?,
    /** A4: the free Budget Accounts, the Offered Bank Accounts for the balance, the types and the known Banks. */
    val freeBudgetAccounts: List<FreeBudgetAccountLine>,
    val balanceSources: List<BalanceLine>,
    val fileFormats: List<Pair<Long, String>>,
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    val message: String?,
)

/** Set up Slussen: Connect Budgets and Bank Accounts (UC-013), with the credentials of UC-014 first (BR-008). */
class SetupViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<SetupUi?>(null)
    val state: StateFlow<SetupUi?> = mutableState

    private var listed: List<ListedBudget> = emptyList()
    private val chosen = linkedSetOf<String>()

    private fun current(): SetupUi = mutableState.value ?: SetupUi(
        step = SetupStep.Credentials,
        credentialLines = emptyList(),
        budgetAppAccepted = false,
        budgets = emptyList(),
        stage = PairingStage.Choose,
        sources = emptyList(),
        bringInAction = "",
        leftOut = emptyList(),
        leftOutOpen = false,
        nothingLeft = null,
        statement = null,
        card = null,
        summary = null,
        freeBudgetAccounts = emptyList(),
        balanceSources = emptyList(),
        fileFormats = emptyList(),
        types = emptyList(),
        knownBanks = emptyList(),
        message = null,
    )

    fun load() {
        viewModelScope.launch { showCredentials() }
    }

    private suspend fun showCredentials() {
        val steps = services.connect.credentialSteps()
        mutableState.value = current().copy(
            step = SetupStep.Credentials,
            credentialLines = steps.map {
                CredentialLine(it.service, it.name, if (it.isBudgetApp) "Budget App" else "Data Provider", Texts.credentialStatus(it.status, services.formats))
            },
            budgetAppAccepted = steps.first { it.isBudgetApp }.status is CredentialStatus.Accepted,
            message = null,
        )
    }

    fun goTo(step: SetupStep) {
        viewModelScope.launch {
            when (step) {
                SetupStep.Credentials -> showCredentials()
                SetupStep.Budgets -> readBudgets()
                SetupStep.BankAccounts -> showPairing(message = null)
            }
        }
    }

    private fun budgetAppName() = current().credentialLines.firstOrNull { it.kind == "Budget App" }?.name ?: "the Budget App"

    private suspend fun readBudgets() {
        if (current().credentialLines.isEmpty()) showCredentials()
        val name = budgetAppName()
        val message = when (val read = services.connect.readBudgets()) {
            is BudgetsRead.Listed -> {
                listed = read.budgets
                chosen.clear()
                chosen += read.budgets.filter { it.served }.map { it.budgetAppReference }
                null
            }
            BudgetsRead.BudgetAppCredentialsMissing -> "Budgets can be chosen once the $name credentials are accepted."
            BudgetsRead.CredentialsRefused -> "Blocked: credentials refused · Enter credentials"
            is BudgetsRead.CouldNotRead -> "$name could not be read: ${Texts.failure(read.kind)} · Budgets unchanged"
            is BudgetsRead.Unavailable -> Texts.refusal(read.reason)
        }
        mutableState.value = current().copy(step = SetupStep.Budgets, budgets = budgetLines(), message = message)
    }

    private fun budgetLines() = listed.map { BudgetChoiceLine(it.budgetAppReference, it.name, it.currency.code, it.budgetAppReference in chosen) }

    fun toggleBudget(reference: String) {
        if (!chosen.remove(reference)) chosen += reference
        mutableState.value = current().copy(budgets = budgetLines())
    }

    fun saveBudgets() {
        viewModelScope.launch {
            when (val choice = services.connect.chooseBudgets(listed, chosen.toSet())) {
                BudgetChoice.Stored -> showPairing(message = null)
                is BudgetChoice.StillMapped -> mutableState.value = current().copy(
                    message = "${choice.budgetName} has mapped Bank Accounts ${choice.bankAccountNames.joinToString(", ")} · " +
                        "remove their Account Mappings first",
                )
                is BudgetChoice.Unavailable -> mutableState.value = current().copy(message = Texts.refusal(choice.reason))
            }
        }
    }

    private fun offeredLine(offer: OfferedForPairing) = OfferedLine(
        offeredBankAccountId = offer.offeredBankAccountId,
        title = offer.nameOrProduct ?: offer.kind.orEmpty(),
        detail = listOfNotNull(offer.kind, offer.currency?.code, offer.lastFourDigits?.let { "••••$it" }).joinToString(" · "),
        balance = offer.balance?.let { services.formats.amount(it) }.orEmpty(),
    )

    /** Step 6: the Offered Bank Accounts the Operator switched on; none is chosen by default (BR-012). */
    private val switchedOn = linkedSetOf<Long>()

    /** Steps 9-14: the chosen Offered Bank Accounts, one at a time, and what became of each so far. */
    private var queue: List<Long> = emptyList()
    private var position = 0
    private val added = mutableListOf<String>()
    private val skipped = mutableListOf<String>()
    private var leftOutNow: List<String> = emptyList()
    private var currentCard: PairingCard? = null
    private var chosenBudgetAccount: Long? = null
    private var pickerOpen = false
    private var keepQuestion: KeepQuestion? = null

    private fun digitsOf(offer: OfferedForPairing): String? = offer.lastFourDigits?.let { "••••$it" }

    private fun titleOf(offer: OfferedForPairing): String = offer.nameOrProduct ?: offer.kind.orEmpty()

    /** Step 5: the choice of what to bring in, grouped by Source and by Bank, and Not imported below (A10, A11). */
    private suspend fun showPairing(message: String?) {
        val pairing = services.connect.pairing()
        val shown = pairing.sources.flatMap { it.offered }.map { it.offeredBankAccountId }.toSet()
        switchedOn.retainAll(shown)
        val chosenCount = switchedOn.size
        mutableState.value = current().copy(
            step = SetupStep.BankAccounts,
            stage = PairingStage.Choose,
            sources = pairing.sources.map { source ->
                SourceSection(
                    name = source.name,
                    // UC-013 BR-002: The Offered Bank Accounts are shown grouped by Source and by Bank.
                    banks = source.offered.groupBy { it.bankName.orEmpty() }.map { (bank, offers) ->
                        BankGroup(bank, offers.map { offeredLine(it).copy(chosen = it.offeredBankAccountId in switchedOn) })
                    },
                    proposedMatches = source.proposedMatches.map {
                        "${titleOf(it.offered)} ${digitsOf(it.offered).orEmpty()} · probably ${it.bankAccountName} · " +
                            "confirm in Account Mappings · not left out"
                    },
                    service = ServiceKey.dataProvider(source.dataProviderId),
                    // A3: a Source with nothing offered yet offers the Fetch that brings its Bank Accounts (UC-001).
                    fetchAction = if (source.remedy == OfferRemedy.StartFetch) "Fetch ${source.name}" else null,
                    remedy = when (source.remedy) {
                        OfferRemedy.StartFetch -> "No Bank Accounts offered yet"
                        OfferRemedy.GrantBankConsent ->
                            "No Bank Accounts offered yet · Grant Bank Consent (comes with Grant Bank Consent)"
                        null -> null
                    },
                )
            },
            // UC-013 BR-012: The action states both numbers before anything is left out.
            bringInAction = "Bring in $chosenCount · leave out ${shown.size - chosenCount}",
            leftOut = pairing.leftOut.map { LeftOutLine(it.leftOutBankAccountId, it.name, "${it.sourceName} · left out ${services.formats.date(it.leftOutAt)}") },
            nothingLeft = if (pairing.nothingToBringIn) {
                val names = pairing.sources.filter { it.remedy == null }.joinToString(" and ") { it.name }
                "Nothing new to bring in · every Bank Account $names offers is added or left out"
            } else {
                null
            },
            statement = null,
            card = null,
            summary = null,
            freeBudgetAccounts = pairing.freeBudgetAccounts.map(::freeLine),
            balanceSources = services.connect.balanceSources().map { source ->
                BalanceLine(
                    source.offered.offeredBankAccountId,
                    listOfNotNull(source.sourceName, listOfNotNull(titleOf(source.offered), digitsOf(source.offered)).joinToString(" "))
                        .joinToString(" · ") + if (source.leftOut) " · not imported" else "",
                )
            },
            fileFormats = services.connect.fileFormats().map { it.fileFormatId to it.name },
            types = BankAccountType.entries,
            knownBanks = services.connect.knownBanks(),
            message = message,
        )
    }

    private fun freeLine(free: FreeBudgetAccount) = FreeBudgetAccountLine(
        free.budgetAccountId,
        free.name,
        "${free.budgetName} · ${free.budgetCurrency.code}",
        free.balance?.let { services.formats.amount(it) }.orEmpty(),
    )

    /** Step 6: the Operator switches an Offered Bank Account on or off to bring it in. */
    fun toggleBringIn(offeredBankAccountId: Long) {
        if (!switchedOn.remove(offeredBankAccountId)) switchedOn += offeredBankAccountId
        viewModelScope.launch { showPairing(message = null) }
    }

    /** Step 7, A12: the Operator continues; Slussen states both numbers and leaves nothing out yet. */
    fun continueBringIn() {
        viewModelScope.launch {
            val statement = services.connect.bringInStatement(switchedOn.toSet())
            val brought = statement.broughtIn.map(::titleOf)
            val left = statement.leftOut.map(::titleOf)
            // UC-013 BR-012: The numbers brought in and left out are stated before anything is left out.
            val shown = if (brought.isEmpty()) {
                StatementUi(
                    question = "Bring in none, leave out ${left.size}?",
                    broughtIn = "Nothing is added.",
                    leftOut = "Every Offered Bank Account shown becomes Not imported and is never read; bring any of them back later.",
                    confirmAction = "Leave out ${left.size}",
                )
            } else {
                StatementUi(
                    question = "Bring in ${brought.size}, leave out ${left.size}?",
                    broughtIn = "Brought in, one at a time: ${brought.joinToString(", ")}.",
                    leftOut = if (left.isEmpty()) {
                        null
                    } else {
                        "Left out: ${left.joinToString(", ")}. Not imported and never read; bring them back any time under Not " +
                            "imported or in Account Mappings."
                    },
                    confirmAction = "Bring in ${brought.size}",
                )
            }
            mutableState.value = current().copy(stage = PairingStage.Confirm, statement = shown, message = null)
        }
    }

    /** A13: back from the stated numbers; nothing is left out and the choice stays as it was. */
    fun backToChoice() {
        mutableState.value = current().copy(stage = PairingStage.Choose, statement = null)
    }

    /** Steps 8-9: the Operator confirms; the rest are left out and the chosen ones come one at a time. */
    fun confirmBringIn() {
        val shown = current().sources.flatMap { it.offered }.map { it.offeredBankAccountId }
        viewModelScope.launch {
            val before = services.connect.pairing().leftOut.map { it.leftOutBankAccountId }.toSet()
            when (val outcome = services.connect.bringIn(shown, switchedOn.toSet())) {
                is BringIn.Confirmed -> {
                    leftOutNow = services.connect.pairing().leftOut.filter { it.leftOutBankAccountId !in before }.map { it.name }
                    switchedOn.clear()
                    queue = outcome.queue
                    position = 0
                    added.clear()
                    skipped.clear()
                    showCard(message = null)
                }
                BringIn.ShownChanged -> showPairing(message = "The Offered Bank Accounts changed · nothing was left out; choose again")
                is BringIn.Unavailable ->
                    mutableState.value = current().copy(stage = PairingStage.Choose, statement = null, message = Texts.refusal(outcome.reason))
            }
        }
    }

    /** Steps 9-10: the card of the current chosen Offered Bank Account, or the summary of step 15 after the last one. */
    private suspend fun showCard(message: String?) {
        while (position < queue.size) {
            val card = services.connect.card(queue[position])
            if (card != null) {
                currentCard = card
                // UC-013 BR-013: The only free Budget Account of the currency is proposed; the Operator accepts it or chooses another.
                chosenBudgetAccount = card.proposedBudgetAccountId
                pickerOpen = false
                keepQuestion = null
                renderCard(message)
                return
            }
            position++
        }
        currentCard = null
        mutableState.value = current().copy(
            stage = PairingStage.Summary,
            card = null,
            statement = null,
            summary = SummaryUi(added.toList(), skipped.toList(), leftOutNow),
            message = message,
        )
    }

    private fun renderCard(message: String?) {
        val card = currentCard ?: return
        val offer = card.offered
        val currency = card.currency?.code
        val chosen = card.allFree.firstOrNull { it.budgetAccountId == chosenBudgetAccount }
        val source = card.sourceName
        mutableState.value = current().copy(
            stage = PairingStage.Card,
            statement = null,
            card = CardUi(
                offeredBankAccountId = offer.offeredBankAccountId,
                progress = "${position + 1} of ${queue.size}",
                title = titleOf(offer),
                balance = offer.balance?.let { services.formats.amount(it) }.orEmpty(),
                detail = listOfNotNull(source, offer.bankName, offer.kind, currency, digitsOf(offer)).joinToString(" · "),
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
                            if (fits) {
                                freeLine(free)
                            } else {
                                freeLine(free).copy(detail = "${free.budgetCurrency.code} · not for a $currency Bank Account", choosable = false)
                            }
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
                currencyNote = if (currency != null) {
                    "Stated by $source"
                } else {
                    "$source states no currency · state it, the Budget Account's by default"
                },
                keepQuestion = keepQuestion,
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

    /** A9: the Operator asks to keep a listed Budget Account by hand. */
    fun askKeepByHand(budgetAccountId: Long) {
        val free = currentCard?.allFree?.firstOrNull { it.budgetAccountId == budgetAccountId } ?: return
        keepQuestion = KeepQuestion(
            budgetAccountId,
            "Keep ${free.name} by hand?",
            "${free.name} (${free.budgetName} · ${free.budgetCurrency.code}) becomes a Manually Kept Account: you keep it in " +
                "${budgetAppName()} yourself, Slussen never writes to it and no longer offers it for pairing. Unmark it any time " +
                "in Account Mappings. Recorded in the Decision Journal.",
        )
        renderCard(message = null)
    }

    fun cancelKeepByHand() {
        keepQuestion = null
        renderCard(message = null)
    }

    fun confirmKeepByHand() {
        val question = keepQuestion ?: return
        viewModelScope.launch {
            keepQuestion = null
            val name = currentCard?.allFree?.firstOrNull { it.budgetAccountId == question.budgetAccountId }?.name.orEmpty()
            val message = when (val outcome = services.connect.markManuallyKept(question.budgetAccountId)) {
                KeepOutcome.Marked -> null
                is KeepOutcome.StillReceives -> "$name receives ${outcome.bankAccountName} · not kept by hand"
                KeepOutcome.NotFree, KeepOutcome.NotManuallyKept, KeepOutcome.Unmarked -> "$name is not a free Budget Account · not kept by hand"
                is KeepOutcome.Unavailable -> Texts.refusal(outcome.reason)
            }
            if (chosenBudgetAccount == question.budgetAccountId && message == null) chosenBudgetAccount = null
            val kept = chosenBudgetAccount
            // A9 continues at step 10: the card is shown again without the Manually Kept Account.
            currentCard = currentCard?.let { services.connect.card(it.offered.offeredBankAccountId) } ?: currentCard
            chosenBudgetAccount = kept
            renderCard(message)
        }
    }

    /** Steps 11-14: [startDate] is empty or `yyyy-MM-dd`; [currency] as the Operator states it when the Source states none. */
    fun addAndContinue(name: String, type: BankAccountType?, bank: BankChoice?, startDate: String, currency: String = "") {
        val card = currentCard ?: return
        val date = startDateOf(startDate).getOrElse {
            mutableState.value = current().copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        val budgetAccountId = chosenBudgetAccount ?: run {
            mutableState.value = current().copy(message = "Choose a Budget Account · not added")
            return
        }
        val stated = if (card.currency != null) null else currency.trim().uppercase().takeIf { it.isNotEmpty() }
        if (stated != null && !(stated.length == 3 && stated.all { it in 'A'..'Z' })) {
            mutableState.value = current().copy(message = "The currency is an ISO 4217 code of three capital letters, like USD · not added")
            return
        }
        viewModelScope.launch {
            val offer = card.offered
            when (val outcome = services.connect.add(offer.offeredBankAccountId, budgetAccountId, name, type, bank, date, stated?.let(::CurrencyCode))) {
                is PairOutcome.Added -> {
                    val budgetAccount = card.allFree.firstOrNull { it.budgetAccountId == budgetAccountId }?.name.orEmpty()
                    added += listOfNotNull("${outcome.name} → $budgetAccount", digitsOf(offer)).joinToString(" ")
                    position++
                    showCard(message = "${outcome.name} added → $budgetAccount")
                }
                // A5 continues at step 11: the card stays open to choose again.
                else -> mutableState.value = current().copy(message = refusalOf(outcome))
            }
        }
    }

    /** A8: the current Offered Bank Account is skipped for now; it is neither added nor left out. */
    fun skipForNow() {
        val card = currentCard ?: return
        skipped += listOfNotNull(titleOf(card.offered), digitsOf(card.offered)).joinToString(" ") + " · offered again next time"
        position++
        viewModelScope.launch { showCard(message = null) }
    }

    /** Step 15: Done returns to the choice of step 5. */
    fun done() {
        queue = emptyList()
        position = 0
        currentCard = null
        viewModelScope.launch { showPairing(message = null) }
    }

    /** Step 5: Not imported, collapsed or open. */
    fun toggleLeftOut() {
        mutableState.value = current().copy(leftOutOpen = !current().leftOutOpen)
    }

    /** A10: the Operator brings a Left-Out Bank Account back; it is offered again, switched off. */
    fun bringBack(leftOutBankAccountId: Long) {
        viewModelScope.launch {
            val message = when (val outcome = services.connect.bringBack(leftOutBankAccountId)) {
                is BroughtBack.Offered -> if (outcome.offeredNow) {
                    "${outcome.name} brought back · switch it on to bring it in"
                } else {
                    "${outcome.name} brought back · offered for pairing from the next delivery that holds it"
                }
                BroughtBack.NotFound -> null
                is BroughtBack.Unavailable -> Texts.refusal(outcome.reason)
            }
            showPairing(message)
        }
    }

    private fun refusalOf(outcome: PairOutcome): String = when (outcome) {
        is PairOutcome.BudgetAccountAlreadyMapped ->
            "${outcome.budgetAccountName} already receives ${outcome.bankAccountName} · not added"
        is PairOutcome.CurrencyDiffers ->
            "${outcome.budgetAccountName} is in ${outcome.budgetCurrency.code}, ${outcome.bankAccountName} in " +
                "${outcome.bankAccountCurrency.code} · not added"
        PairOutcome.CurrencyNotStated -> "State the currency of the Bank Account · not added"
        PairOutcome.NotOffered -> "Not offered for pairing"
        PairOutcome.NameNotValid -> "A name is needed, at most 100 characters · not added"
        PairOutcome.TypeNotChosen -> "Choose the Bank Account type · not added"
        PairOutcome.BankNotChosen -> "Choose a Bank or name a new one · not added"
        is PairOutcome.Unavailable -> Texts.refusal(outcome.reason)
        is PairOutcome.Proposed, is PairOutcome.Added -> ""
    }

    private fun startDateOf(text: String): Result<LocalDate?> = try {
        Result.success(text.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
    } catch (e: DateTimeParseException) {
        Result.failure(e)
    }

    /** UC-013 A3: the Operator starts the Fetch of a Data Provider that offers nothing yet (UC-001); pairing continues at step 5. */
    fun fetch(service: ServiceKey) {
        viewModelScope.launch {
            val message = when (val start = services.fetch.fetch(service)) {
                is FetchStart.Unavailable -> Texts.refusal(start.reason)
                is FetchStart.Ran -> start.reports.joinToString(" · ") { Texts.fetchReport(it, services.formats) }
            }
            showPairing(message = message)
        }
    }

    /** A4: the Operator chose the File Format, type, Bank, an optional balance source and the Budget Account, and named it. */
    fun addFromTransactionFiles(
        fileFormatId: Long,
        type: BankAccountType?,
        bank: BankChoice?,
        balanceOfferedBankAccountId: Long?,
        budgetAccountId: Long,
        name: String,
        startDate: String,
    ) {
        val date = startDateOf(startDate).getOrElse {
            mutableState.value = current().copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        viewModelScope.launch {
            val outcome = services.connect.addFromTransactionFiles(fileFormatId, type, bank, balanceOfferedBankAccountId, budgetAccountId, name, date)
            if (outcome is PairOutcome.Added) {
                showPairing(message = "Bank Account ${outcome.name} added · fetched from the next Fetch")
            } else {
                mutableState.value = current().copy(message = refusalOf(outcome))
            }
        }
    }
}
