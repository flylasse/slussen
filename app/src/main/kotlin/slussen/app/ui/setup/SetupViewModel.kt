package slussen.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.connection.AdditionOutcome
import slussen.core.connection.BudgetChoice
import slussen.core.connection.BudgetsRead
import slussen.core.connection.ListedBudget
import slussen.core.connection.OfferRemedy
import slussen.core.connection.OfferedForPairing
import slussen.core.connection.PairOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.port.ServiceKey
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The first-run order of UC-013 BR-008. */
enum class SetupStep { Credentials, Budgets, BankAccounts }

/** One service on the credentials step (UC-013 step 1). */
data class CredentialLine(val service: ServiceKey, val name: String, val kind: String, val status: String)

/** One Budget the Budget App lists, with whether the Operator chose it (UC-013 steps 2-3). */
data class BudgetChoiceLine(val reference: String, val name: String, val currency: String, val chosen: Boolean)

/** One Offered Bank Account not yet added (UC-013 step 5, BR-002). */
data class OfferedLine(val offeredBankAccountId: Long, val title: String, val detail: String, val balance: String)

/** One Source on the pairing step, with what it offers or the remedy when it offers nothing (A3, A6). */
data class SourceSection(
    val name: String,
    val offered: List<OfferedLine>,
    val proposedMatches: List<String>,
    val remedy: String?,
)

/** A free Budget Account of a chosen Budget (UC-013 step 5, BR-002). */
data class FreeBudgetAccountLine(val budgetAccountId: Long, val title: String, val detail: String, val balance: String)

/** Steps 9-10: the proposed or entered name and the optional Start Date of a Bank Account just added. */
data class NamingUi(val bankAccountId: Long, val proposedName: String)

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
                    remedy = when (source.remedy) {
                        OfferRemedy.StartFetch -> "No Bank Accounts offered yet · Fetch ${source.name} (comes with Fetch Transactions)"
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
            naming = naming,
            message = message,
        )
    }

    fun selectOffered(offeredBankAccountId: Long) {
        mutableState.value = current().copy(selectedOffered = offeredBankAccountId, message = null)
    }

    fun pairWith(budgetAccountId: Long) {
        val offered = current().selectedOffered ?: return
        viewModelScope.launch { showPaired(services.connect.pair(offered, budgetAccountId)) }
    }

    private suspend fun showPaired(outcome: PairOutcome) {
        when (outcome) {
            is PairOutcome.Added -> {
                mutableState.value = current().copy(selectedOffered = null)
                showPairing(message = null, naming = NamingUi(outcome.bankAccountId, outcome.proposedName))
            }
            else -> showPairing(message = refusalOf(outcome))
        }
    }

    private fun refusalOf(outcome: PairOutcome): String = when (outcome) {
        is PairOutcome.BudgetAccountAlreadyMapped ->
            "${outcome.budgetAccountName} already receives ${outcome.bankAccountName} · not paired"
        is PairOutcome.CurrencyDiffers ->
            "${outcome.budgetAccountName} is in ${outcome.budgetCurrency.code}, ${outcome.bankAccountName} in " +
                "${outcome.bankAccountCurrency.code} · not paired"
        PairOutcome.CurrencyNotKnown -> "The Data Provider delivered no currency for this Bank Account · not paired"
        PairOutcome.BankNotKnown -> "The Data Provider named no Bank for this Bank Account · not paired"
        PairOutcome.NotOffered -> "Not offered for pairing"
        PairOutcome.NameNotValid -> "A name is needed, at most 100 characters"
        is PairOutcome.Unavailable -> Texts.refusal(outcome.reason)
        is PairOutcome.Added -> ""
    }

    /** Steps 9-10: [startDate] is empty or `yyyy-MM-dd`. */
    fun saveNaming(name: String, startDate: String) {
        val naming = current().naming ?: return
        val date = try {
            startDate.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            mutableState.value = current().copy(message = "The Start Date is a date like 2026-09-01, or empty.")
            return
        }
        viewModelScope.launch {
            when (val outcome = services.connect.completeAddition(naming.bankAccountId, name, date)) {
                AdditionOutcome.Stored -> showPairing(message = "Bank Account $name added · fetched from the next Fetch", naming = null)
                AdditionOutcome.NameNotValid -> mutableState.value = current().copy(message = "A name is needed, at most 100 characters.")
                is AdditionOutcome.Unavailable -> mutableState.value = current().copy(message = Texts.refusal(outcome.reason))
            }
        }
    }

    /** A4. */
    fun addFromTransactionFiles(
        fileFormatId: Long,
        bankName: String,
        balanceOfferedBankAccountId: Long?,
        budgetAccountId: Long,
        name: String,
    ) {
        viewModelScope.launch {
            showPaired(services.connect.addFromTransactionFiles(fileFormatId, bankName, balanceOfferedBankAccountId, budgetAccountId, name))
        }
    }
}
