package slussen.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.connection.AdditionProposal
import slussen.core.connection.BudgetChoice
import slussen.core.connection.BudgetsRead
import slussen.core.connection.KnownBank
import slussen.core.connection.ListedBudget
import slussen.core.connection.OfferRemedy
import slussen.core.connection.OfferedForPairing
import slussen.core.connection.PairOutcome
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

/** One service on the credentials step (UC-013 step 1). */
data class CredentialLine(val service: ServiceKey, val name: String, val kind: String, val status: String)

/** One Budget the Budget App lists, with whether the Operator chose it (UC-013 steps 2-3). */
data class BudgetChoiceLine(val reference: String, val name: String, val currency: String, val chosen: Boolean)

/** One Offered Bank Account not yet added (UC-013 step 5, BR-002). */
data class OfferedLine(val offeredBankAccountId: Long, val title: String, val detail: String, val balance: String) {
    /** The last four digits as shown, or null. */
    val digits: String? get() = detail.split(" · ").firstOrNull { it.startsWith("••••") }
}

/** One Source on the pairing step, with what it offers or the remedy when it offers nothing (A3, A6). */
data class SourceSection(
    val name: String,
    val offered: List<OfferedLine>,
    val proposedMatches: List<String>,
    val remedy: String?,
    /** A3: the Fetch of this Data Provider that offers its Bank Accounts (UC-001), or null. */
    val fetchAction: String?,
    val service: ServiceKey,
)

/** A free Budget Account of a chosen Budget (UC-013 step 5, BR-002). */
data class FreeBudgetAccountLine(val budgetAccountId: Long, val title: String, val detail: String, val balance: String)

/**
 * Steps 8-9: what Slussen proposes for a paired Offered Bank Account, its name (BR-004), Bank Account type and Bank
 * (BR-009), and the types and known Banks the Operator chooses from, before it is added at step 10.
 */
data class NamingUi(
    val offeredBankAccountId: Long,
    val budgetAccountId: Long,
    val title: String,
    val proposedName: String,
    val proposedType: BankAccountType,
    val proposedBank: KnownBank?,
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    val note: String,
    /** BR-010: the currency, stated by the Source, or proposed from the Budget Account when the Source states none. */
    val currency: String,
    val currencyStatedBySource: Boolean,
    val currencyNote: String,
)

data class SetupUi(
    val step: SetupStep,
    val credentialLines: List<CredentialLine>,
    val budgetAppAccepted: Boolean,
    val budgets: List<BudgetChoiceLine>,
    val sources: List<SourceSection>,
    val freeBudgetAccounts: List<FreeBudgetAccountLine>,
    val selectedOffered: Long?,
    val naming: NamingUi?,
    val fileFormats: List<Pair<Long, String>>,
    /** A4: the Bank Account types and the Banks Slussen knows, for a Bank Account of a File Format (BR-006, BR-009). */
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
        sources = emptyList(),
        freeBudgetAccounts = emptyList(),
        selectedOffered = null,
        naming = null,
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

    private suspend fun showPairing(message: String?, naming: NamingUi? = current().naming) {
        val pairing = services.connect.pairing()
        mutableState.value = current().copy(
            step = SetupStep.BankAccounts,
            sources = pairing.sources.map { source ->
                SourceSection(
                    name = source.name,
                    offered = source.offered.map(::offeredLine),
                    proposedMatches = source.proposedMatches.map {
                        val line = offeredLine(it.offered)
                        "${line.title} ${it.offered.lastFourDigits?.let { digits -> "••••$digits" }.orEmpty()} · " +
                            "probably ${it.bankAccountName} · confirm in Account Mappings"
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
            freeBudgetAccounts = pairing.freeBudgetAccounts.map {
                FreeBudgetAccountLine(
                    it.budgetAccountId,
                    it.name,
                    "${it.budgetName} · ${it.budgetCurrency.code}",
                    it.balance?.let { balance -> services.formats.amount(balance) }.orEmpty(),
                )
            },
            fileFormats = services.connect.fileFormats().map { it.fileFormatId to it.name },
            types = BankAccountType.entries,
            knownBanks = services.connect.knownBanks(),
            naming = naming,
            message = message,
        )
    }

    fun selectOffered(offeredBankAccountId: Long) {
        mutableState.value = current().copy(selectedOffered = offeredBankAccountId, message = null)
    }

    /** Steps 6-8: the pairing is checked and the name, Bank Account type and Bank are proposed; nothing is added yet. */
    fun pairWith(budgetAccountId: Long) {
        val offered = current().selectedOffered ?: return
        viewModelScope.launch {
            when (val outcome = services.connect.propose(offered, budgetAccountId)) {
                is PairOutcome.Proposed -> {
                    mutableState.value = current().copy(selectedOffered = null)
                    showPairing(message = null, naming = namingOf(outcome.proposal))
                }
                else -> showPairing(message = refusalOf(outcome))
            }
        }
    }

    private fun namingOf(proposal: AdditionProposal): NamingUi {
        val source = current().sources.firstOrNull { section -> section.offered.any { it.offeredBankAccountId == proposal.offeredBankAccountId } }
        val offered = source?.offered?.single { it.offeredBankAccountId == proposal.offeredBankAccountId }
        val budgetAccount = current().freeBudgetAccounts.firstOrNull { it.budgetAccountId == proposal.budgetAccountId }?.title.orEmpty()
        val sourceName = source?.name.orEmpty()
        return NamingUi(
            offeredBankAccountId = proposal.offeredBankAccountId,
            budgetAccountId = proposal.budgetAccountId,
            title = listOfNotNull(offered?.title, offered?.digits).joinToString(" · ") + " → $budgetAccount",
            proposedName = proposal.proposedName,
            proposedType = proposal.proposedType,
            proposedBank = proposal.proposedBank,
            types = proposal.types,
            knownBanks = proposal.knownBanks,
            note = if (proposal.proposedBank != null) {
                "Type and Bank proposed from what $sourceName states · change them if they are wrong"
            } else {
                "$sourceName names no Bank · choose one or name a new one"
            },
            currency = proposal.proposedCurrency.code,
            currencyStatedBySource = proposal.currencyStatedBySource,
            currencyNote = if (proposal.currencyStatedBySource) {
                "Stated by $sourceName"
            } else {
                "$sourceName states no currency · state it, the Budget Account's by default"
            },
        )
    }

    private fun refusalOf(outcome: PairOutcome): String = when (outcome) {
        is PairOutcome.BudgetAccountAlreadyMapped ->
            "${outcome.budgetAccountName} already receives ${outcome.bankAccountName} · not paired"
        is PairOutcome.CurrencyDiffers ->
            "${outcome.budgetAccountName} is in ${outcome.budgetCurrency.code}, ${outcome.bankAccountName} in " +
                "${outcome.bankAccountCurrency.code} · not paired"
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

    private suspend fun showAdded(outcome: PairOutcome) {
        when (outcome) {
            is PairOutcome.Added ->
                showPairing(message = "Bank Account ${outcome.name} added · fetched from the next Fetch", naming = null)
            else -> mutableState.value = current().copy(message = refusalOf(outcome))
        }
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

    /**
     * Steps 9-10: [startDate] is empty or `yyyy-MM-dd`; [type] and [bank] as the Operator confirmed or changed them;
     * [currency] as the Operator states it when the Source states none (BR-010).
     */
    fun saveNaming(name: String, type: BankAccountType?, bank: BankChoice?, startDate: String, currency: String = "") {
        val naming = current().naming ?: return
        val date = startDateOf(startDate).getOrElse {
            mutableState.value = current().copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        val stated = if (naming.currencyStatedBySource) null else currency.trim().uppercase().takeIf { it.isNotEmpty() }
        if (stated != null && !(stated.length == 3 && stated.all { it in 'A'..'Z' })) {
            mutableState.value = current().copy(message = "The currency is an ISO 4217 code of three capital letters, like USD · not added")
            return
        }
        viewModelScope.launch {
            showAdded(
                services.connect.add(
                    naming.offeredBankAccountId, naming.budgetAccountId, name, type, bank, date, stated?.let(::CurrencyCode),
                ),
            )
        }
    }

    /** Step 9: the Operator cancels; nothing is added. */
    fun cancelNaming() {
        viewModelScope.launch { showPairing(message = null, naming = null) }
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
            showAdded(services.connect.addFromTransactionFiles(fileFormatId, type, bank, balanceOfferedBankAccountId, budgetAccountId, name, date))
        }
    }
}
