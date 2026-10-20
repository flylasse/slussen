package slussen.app.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.connection.BroughtBack
import slussen.core.connection.BudgetAccountOnPage
import slussen.core.connection.BudgetChoice
import slussen.core.connection.BudgetsRead
import slussen.core.connection.ImportState
import slussen.core.connection.ImportSwitchedOff
import slussen.core.connection.KeepOutcome
import slussen.core.connection.ListedBudget
import slussen.core.connection.OfferRemedy
import slussen.core.connection.OfferedOfProvider
import slussen.core.connection.OfferedOnPage
import slussen.core.credential.CredentialOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.credential.RemovalOutcome
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.port.ServiceKey
import slussen.core.settings.RequestLimitShown
import slussen.core.settings.ServicePage
import slussen.core.settings.SettingOutcome
import slussen.core.settings.ServiceStatus
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.value.Secret

/** One credential field as the service's part declares it (UC-014 BR-002). */
data class FieldUi(val key: String, val kind: FieldKind, val description: String)

/**
 * Editing the Request Limit of a Data Provider that publishes none (UC-010 steps 2-4, A1, A11;
 * `connections.html#request-limit`).
 */
data class RequestLimitEditorUi(
    val value: String,
    val defaultRequests: Int,
    val hint: String,
    /** Shown for a Data Provider that needs a Bank Consent: the PSD2 limit still applies (C-006). */
    val unattendedNote: String?,
)

/** One Budget the Budget App lists, with whether the Operator chose it (UC-013 steps 2-3; `connections.html#choose-budgets`). */
data class BudgetChoiceLine(val reference: String, val name: String, val currency: String, val chosen: Boolean)

/** One Budget Account under its Budget, with "Receives from Slussen" or its Account Mapping (UC-013 step 4, BR-014). */
data class BudgetAccountLine(
    val budgetAccountId: Long,
    val name: String,
    val detail: String,
    val balance: String,
    /** The switch "Receives from Slussen"; null when it receives through an Account Mapping, shown instead. */
    val receives: Boolean?,
)

data class BudgetAccountsGroup(val title: String, val lines: List<BudgetAccountLine>)

/** The Budgets on the Budget App's page: in use, their choice, and the Budget Accounts of each (UC-013 steps 2-4, A1, A2, A7). */
data class BudgetsSectionUi(
    val inUse: List<String>,
    /** A7: why the Budgets cannot be chosen yet, or null. */
    val note: String?,
    /** Steps 2-3: the Budgets the Budget App lists while the Operator chooses, or null. */
    val choosing: List<BudgetChoiceLine>?,
    val budgetAccounts: List<BudgetAccountsGroup>,
)

/** One Offered Bank Account on its Data Provider's page (UC-013 step 5, BR-002, BR-012; a Data Provider's page of `connections.html`). */
data class OfferedLineUi(
    val offeredBankAccountId: Long?,
    val leftOutBankAccountId: Long?,
    val title: String,
    val detail: String,
    val balance: String,
    /** The import switch: on, off, or null when it has none (added, a proposed match, a balance source). */
    val importOn: Boolean?,
    /** What stands instead of a switch: where an added one goes, a proposed match, a balance source. */
    val trailing: String?,
)

data class OfferedBankGroup(val name: String, val rows: List<OfferedLineUi>)

/** The Offered Bank Accounts on a Data Provider's page (UC-013 steps 5-7, A3, A6, A10, A11). */
data class OfferedSectionUi(
    val heading: String,
    val banks: List<OfferedBankGroup>,
    /** A10 step 2: Left-Out Bank Accounts the latest delivery does not hold. */
    val notDelivered: List<OfferedLineUi>,
    /** A3: one sentence while nothing is offered (P9-05), or null. */
    val emptyText: String?,
    /** A11: nothing new to import, or null. */
    val nothingNew: String?,
    val footnote: String?,
)

/** What a switch did, with the action that follows it (`connections.html#switch-off`, `#switch-on`, `#kept-switch`). */
enum class SnackAction { Undo, Map }

data class SnackUi(val text: String, val action: SnackAction?)

/**
 * The page of one service (UC-010 BR-010, UC-014 BR-007), generated from its declarations, with its credential form
 * (UC-014 steps 2-6, A1-A5), the Request Limit setting of a Data Provider that publishes none (UC-010 A11), for a Data
 * Provider its Fetch (UC-001) and its Offered Bank Accounts with their import switches (UC-013 steps 5-7), and for the
 * Budget App the Budgets and their Budget Accounts with "Receives from Slussen" (UC-013 steps 2-4, A9).
 */
data class ServicePageUi(
    val name: String,
    val kind: String,
    val status: String,
    val blocked: String?,
    val credentials: String,
    val fields: List<FieldUi>,
    val entering: Boolean,
    val removalQuestion: String?,
    val canRemove: Boolean,
    val message: String?,
    val fetchCooldown: String?,
    val requestLimit: String?,
    /** True only for a Request Limit the Data Provider does not publish (UC-001 BR-007). */
    val requestLimitEditable: Boolean,
    val requestLimitEditor: RequestLimitEditorUi?,
    val bankAccounts: List<String>,
    val uploadAsClearedShown: Boolean,
    /** The Budget App's page: its Budgets and their Budget Accounts; null on a Data Provider's page. */
    val budgets: BudgetsSectionUi?,
    /** A Data Provider's page: its Offered Bank Accounts; null on the Budget App's page. */
    val offered: OfferedSectionUi?,
    val snack: SnackUi?,
    /** A Data Provider's page offers its Fetch (UC-001 step 1). */
    val canFetch: Boolean,
    /** UC-001 A16: the warning the Operator confirms before the Fetch is made, or null. */
    val fetchWarning: FetchWarningUi?,
    /** UC-001 BR-008 (P9-13): why the Fetch is unavailable in this build, or null when it is offered. */
    val fetchUnavailable: String? = null,
)

/** UC-001 A16 (`budget.html#request-limit-warning`): the warning, its end and the risk, before the Operator confirms. */
data class FetchWarningUi(val service: ServiceKey, val title: String, val text: String, val risk: String)

/** A service's page, opened from its entry in Settings (UC-014, UC-013, UC-010 BR-010). */
class ServicePageViewModel(
    private val services: UiServices,
    private val service: ServiceKey,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ServicePageUi?>(null)
    val state: StateFlow<ServicePageUi?> = mutableState

    private var page: ServicePage? = null

    /** The Budget App's page: the Budgets and their Budget Accounts; a Data Provider's page: its Offered Bank Accounts. */
    private var budgetsSection: BudgetsSectionUi? = null
    private var offeredSection: OfferedSectionUi? = null
    private var snack: SnackUi? = null

    /** Steps 2-3: the Budgets the Budget App listed and the ones ticked, while the Operator chooses. */
    private var listed: List<ListedBudget> = emptyList()
    private val chosen = linkedSetOf<String>()
    private var choosing = false

    /** What Undo does: the last switch, switched back. */
    private var undoAction: (suspend () -> Unit)? = null

    fun load() {
        snack = null
        undoAction = null
        viewModelScope.launch { reload() }
    }

    /** Reads the page, and what UC-013 shows on it, again. */
    private suspend fun reload(message: String? = null) {
        show(pageWithSections(), message = message)
    }

    private suspend fun pageWithSections(): ServicePage = services.settingsOverview.servicePage(service).also { page ->
        if (page.isBudgetApp) {
            val served = services.connect.servedBudgetAccounts()
            budgetsSection = BudgetsSectionUi(
                inUse = page.budgetsInUse.map { "${it.name} · ${it.currency.code}" },
                // A7 of UC-013: the Budgets can be chosen once the Budget App's credentials are accepted.
                note = if (page.credentialStatus == CredentialStatus.Missing) "Budgets can be chosen once the ${page.name} credentials are accepted." else null,
                choosing = if (choosing) budgetLines() else null,
                budgetAccounts = served.map { budget ->
                    BudgetAccountsGroup("${budget.name} · ${budget.currency.code}", budget.budgetAccounts.map(::budgetAccountLine))
                },
            )
        } else {
            offeredSection = offeredSection(services.connect.offeredOf(requireNotNull(service.dataProviderId)), page.name)
        }
    }

    private fun budgetLines() = listed.map { BudgetChoiceLine(it.budgetAppReference, it.name, it.currency.code, it.budgetAppReference in chosen) }

    // UC-013 BR-014: Each Budget Account that receives through no Account Mapping has the switch "Receives from Slussen"; a mapped one shows its Account Mapping instead.
    private fun budgetAccountLine(account: BudgetAccountOnPage) = BudgetAccountLine(
        budgetAccountId = account.budgetAccountId,
        name = account.name,
        detail = when {
            account.mappedFrom != null -> "receives from ${account.mappedFrom}"
            account.receivesFromSlussen == true -> "receives from Slussen"
            else -> "kept by hand · Manually Kept Account"
        },
        balance = account.balance?.let { services.formats.amount(it) }.orEmpty(),
        receives = account.receivesFromSlussen,
    )

    private fun offeredSection(offered: OfferedOfProvider, name: String): OfferedSectionUi {
        val rows = offered.offered.map { it to offeredLine(it) }
        return OfferedSectionUi(
            heading = "Offered Bank Accounts · import is on by default",
            // UC-013 BR-002: The Offered Bank Accounts are shown on their Data Provider's page grouped by Bank.
            banks = rows.groupBy({ it.first.offered.bankName.orEmpty() }, { it.second }).map { (bank, lines) -> OfferedBankGroup(bank, lines) },
            notDelivered = offered.leftOutNotDelivered.map {
                OfferedLineUi(null, it.leftOutBankAccountId, it.name, "not in the latest delivery · not imported", "", importOn = false, trailing = null)
            },
            // A3 of UC-013 (P9-05): one sentence while nothing is offered.
            emptyText = when (offered.remedy) {
                OfferRemedy.StartFetch -> "None yet · a Fetch lists the Bank Accounts $name offers; no transactions are fetched until a Bank Account is added"
                // P9-13: a Bank Consent shows the Bank Accounts, and this build has none yet.
                OfferRemedy.GrantBankConsent ->
                    "None yet · a Bank Consent of a Bank lists its Bank Accounts here · ${Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD}"
                null -> null
            },
            nothingNew = if (offered.nothingNew) "Nothing new to import · every Offered Bank Account is added, not imported or reports a balance" else null,
            footnote = if (offered.offered.isEmpty()) {
                null
            } else {
                "Imported Bank Accounts are mapped in Account Mappings. Nothing is fetched for them until they are added."
            },
        )
    }

    // UC-013 BR-012: An imported Offered Bank Account has its import switch on, a Left-Out one off; an added one, a proposed match and a balance source have none.
    private fun offeredLine(on: OfferedOnPage): OfferedLineUi {
        val offer = on.offered
        val detail = listOfNotNull(offer.kind, offer.currency?.code, offer.lastFourDigits?.let { "••$it" }).joinToString(" · ")
        val balance = offer.balance?.let { services.formats.amount(it) }.orEmpty()
        val title = offer.nameOrProduct ?: offer.kind.orEmpty()
        return when (val state = on.state) {
            ImportState.ToMap -> OfferedLineUi(offer.offeredBankAccountId, null, title, "$detail · to map", balance, importOn = true, trailing = null)
            is ImportState.NotImported ->
                OfferedLineUi(offer.offeredBankAccountId, state.leftOutBankAccountId, title, "$detail · not imported", balance, importOn = false, trailing = null)
            is ImportState.Added ->
                OfferedLineUi(offer.offeredBankAccountId, null, title, detail, balance, importOn = null, trailing = "Added → ${state.budgetAccountName ?: "not mapped"}")
            is ImportState.ProbablyAdded -> OfferedLineUi(
                offer.offeredBankAccountId, null, title, detail, balance, importOn = null,
                trailing = "probably ${state.bankAccountName} · confirm in Account Mappings",
            )
            is ImportState.ReportsBalance ->
                OfferedLineUi(offer.offeredBankAccountId, null, title, detail, balance, importOn = null, trailing = "balance of ${state.bankAccountName}")
        }
    }

    private fun show(
        page: ServicePage,
        entering: Boolean = false,
        message: String? = null,
        removalQuestion: String? = null,
        requestLimitEditor: RequestLimitEditorUi? = null,
        fetchWarning: FetchWarningUi? = null,
    ) {
        this.page = page
        val formats = services.formats
        val missingOrRefused = page.status == ServiceStatus.CredentialsMissing || page.status == ServiceStatus.CredentialsRefused
        mutableState.value = ServicePageUi(
            name = page.name,
            kind = (if (page.isBudgetApp) "Budget App" else if (page.needsBankConsent) "Data Provider · needs Bank Consent" else "Data Provider") +
                if (page.status == ServiceStatus.NotSetUp) " · not set up" else "",
            status = Texts.serviceStatus(page.status, formats),
            // Refused or missing credentials show their Blocked Reason with the action that enters them (UC-014 BR-005).
            blocked = if (missingOrRefused) {
                val reason = if (page.status == ServiceStatus.CredentialsRefused) "credentials refused" else "credentials missing"
                "Blocked: $reason · Enter credentials"
            } else {
                null
            },
            credentials = Texts.credentialStatus(page.credentialStatus, formats),
            fields = page.credentialFields.map { FieldUi(it.key, it.kind, it.description) },
            entering = entering,
            removalQuestion = removalQuestion,
            canRemove = page.credentialStatus != CredentialStatus.Missing,
            message = message,
            fetchCooldown = page.fetchCooldownMinutes?.let {
                "$it min · default ${page.defaultFetchCooldownMinutes}, declared by its part · 0 to 1440 · 0 is off"
            },
            requestLimit = page.requestLimit?.let { limit ->
                when (limit) {
                    is RequestLimitShown.Published ->
                        "${limit.requests} per 24 hours · the Data Provider's own limit, published by ${page.name} · " +
                            "source: ${limit.source} · not a setting"
                    is RequestLimitShown.NotPublished ->
                        "${limit.requests} per 24 hours · not published by ${page.name} · default ${limit.defaultRequests} · " +
                            "${limit.range.first} to ${limit.range.last}"
                }
            },
            requestLimitEditable = page.requestLimit is RequestLimitShown.NotPublished,
            requestLimitEditor = requestLimitEditor,
            bankAccounts = page.bankAccounts.map { account ->
                account.name + when {
                    account.retired -> " · Retired Account"
                    !account.mapped -> " · not mapped"
                    else -> ""
                }
            },
            uploadAsClearedShown = page.supportsClearedMarker,
            budgets = if (page.isBudgetApp) budgetsSection else null,
            offered = if (page.isBudgetApp) null else offeredSection,
            snack = snack,
            // UC-001 BR-008: No Bank is contacted without a valid Bank Consent; this build grants none, so the Fetch is unavailable (P9-13).
            canFetch = !page.isBudgetApp && !page.needsBankConsent,
            fetchWarning = fetchWarning,
            fetchUnavailable = if (!page.isBudgetApp && page.needsBankConsent) "${Texts.cannotFetchWithoutBankConsents(page.name)}." else null,
        )
    }

    /** Step 2: show the form its part declares. */
    fun startEntering() {
        mutableState.value = mutableState.value?.copy(entering = true, message = null)
    }

    /** A4: the Operator does not enter the credentials; the earlier ones stay. */
    fun cancelEntering() {
        mutableState.value = mutableState.value?.copy(entering = false, message = null)
    }

    /** Steps 3-6, A1, A2, A5: [values] by the key of each declared field; a File field holds the chosen file's content. */
    fun save(values: Map<String, String>) {
        viewModelScope.launch {
            val input = CredentialInput(values.mapValues { Secret(it.value) })
            val outcome = services.credentials.enter(service, input)
            val page = pageWithSections()
            val message = Texts.outcome(page.name, outcome, services.formats)
            when (outcome) {
                is CredentialOutcome.Accepted -> {
                    show(page, message = message)
                    // Steps 1-2 of UC-013: once the Budget App accepts its credentials, the Budgets come next on the same page.
                    if (page.isBudgetApp && page.budgetsInUse.isEmpty()) readBudgets(keptMessage = message)
                }
                // A1: use case continues at step 2; the form stays open.
                CredentialOutcome.Refused -> show(page, entering = true, message = message)
                // A2, A5: use case ends.
                is CredentialOutcome.NotReached, is CredentialOutcome.RequestLimitReached, is CredentialOutcome.NotSent,
                is CredentialOutcome.WaitingForProvider ->
                    show(page, message = message)
                is CredentialOutcome.Unavailable -> show(page, entering = true, message = message)
            }
        }
    }

    /** UC-010 step 2: open the editor of a Request Limit the Data Provider does not publish; a published one has none. */
    fun startEditingRequestLimit() {
        val current = mutableState.value ?: return
        val limit = page?.requestLimit as? RequestLimitShown.NotPublished ?: return
        val name = current.name
        mutableState.value = current.copy(
            message = null,
            requestLimitEditor = RequestLimitEditorUi(
                value = limit.requests.toString(),
                defaultRequests = limit.defaultRequests,
                hint = "Not published by $name. Its part declares ${limit.defaultRequests} requests in any 24 hours by default, " +
                    "an Operator Setting. Enter ${limit.range.first} to ${limit.range.last}.",
                unattendedNote = if (page?.needsBankConsent == true) {
                    "The limit PSD2 sets of 4 Unattended Accesses per Bank Account still applies."
                } else {
                    null
                },
            ),
        )
    }

    /** UC-010 steps 2-6, A1: [text] must be a whole number within the declared range (BR-002). */
    fun saveRequestLimit(text: String) {
        val limit = page?.requestLimit as? RequestLimitShown.NotPublished ?: return
        val range = "${limit.range.first} to ${limit.range.last}"
        val value = text.trim().toIntOrNull()
        if (value == null) {
            mutableState.value = mutableState.value?.copy(message = "Enter a whole number from $range · the Request Limit stays ${limit.requests}")
            return
        }
        viewModelScope.launch {
            showSetting(services.providerSettings.changeRequestLimit(service, value), "Request Limit $value stored · an Operator Setting", value)
        }
    }

    /** UC-010 A11: back to the default its part declares. */
    fun resetRequestLimit() {
        val limit = page?.requestLimit as? RequestLimitShown.NotPublished ?: return
        viewModelScope.launch {
            showSetting(
                services.providerSettings.resetRequestLimit(service),
                "Request Limit reset to its default ${limit.defaultRequests}",
                limit.defaultRequests,
            )
        }
    }

    /** UC-010 step 2: the Operator cancels; the value stays. */
    fun cancelRequestLimit() {
        mutableState.value = mutableState.value?.copy(requestLimitEditor = null)
    }

    private suspend fun showSetting(outcome: SettingOutcome, storedMessage: String, value: Int) {
        val editor = mutableState.value?.requestLimitEditor
        val before = (page?.requestLimit as? RequestLimitShown.NotPublished)?.requests
        when (outcome) {
            is SettingOutcome.Stored -> show(pageWithSections(), message = storedMessage)
            // A1: refused with the allowed range; the editor stays open (use case continues at step 2).
            is SettingOutcome.OutsideRange -> mutableState.value = mutableState.value?.copy(
                message = "$value is outside the allowed range · enter ${outcome.range.first} to ${outcome.range.last} · " +
                    "the Request Limit stays $before",
                requestLimitEditor = editor,
            )
            SettingOutcome.NotAnOperatorSetting -> show(pageWithSections(), message = "Published by the Data Provider · not a setting")
            is SettingOutcome.Unavailable -> mutableState.value = mutableState.value?.copy(message = Texts.refusal(outcome.reason))
        }
    }

    /** UC-001 step 1: the Operator starts a Fetch of this Data Provider, when the page offers it (P9-13). */
    fun fetch() {
        if (mutableState.value?.canFetch == true) startFetch(confirmedDespiteWarning = false)
    }

    /** UC-001 A16 step 2: the Operator confirms the Fetch despite the warning. */
    fun confirmFetch() = startFetch(confirmedDespiteWarning = true)

    /** UC-001 A16 step 2: the Operator cancels; nothing is sent. */
    fun cancelFetch() {
        mutableState.value = mutableState.value?.copy(fetchWarning = null)
    }

    private fun startFetch(confirmedDespiteWarning: Boolean) {
        viewModelScope.launch {
            val start = services.fetch.fetch(service, confirmedDespiteWarning)
            val page = pageWithSections()
            when (start) {
                is FetchStart.Unavailable -> show(page, message = Texts.refusal(start.reason))
                is FetchStart.Ran -> when (val report = start.reports.single()) {
                    is FetchReport.WarningToConfirm -> show(page, fetchWarning = Texts.fetchWarning(report, services.formats))
                    else -> show(page, message = Texts.fetchReport(report, services.formats))
                }
            }
        }
    }

    /** A3 step 1: ask the Operator to confirm, stating that the service is not contacted until new credentials are entered. */
    fun requestRemoval() {
        val current = mutableState.value ?: return
        mutableState.value = current.copy(
            removalQuestion = "Remove ${current.name} credentials? ${current.name} is not contacted until you enter new ones.",
        )
    }

    /** A3 step 2: the Operator cancels; nothing changes. */
    fun cancelRemoval() {
        mutableState.value = mutableState.value?.copy(removalQuestion = null)
    }

    /** A3 steps 2-5: the Operator confirms. */
    fun confirmRemoval() {
        viewModelScope.launch {
            val outcome = services.credentials.remove(service)
            val page = pageWithSections()
            when (outcome) {
                RemovalOutcome.Removed -> show(page, message = "Removed · recorded in the Decision Journal")
                is RemovalOutcome.Unavailable -> show(page, message = Texts.refusal(outcome.reason))
            }
        }
    }

    /** UC-013 step 2: the Operator asks to choose the Budgets; Slussen reads them from the Budget App. */
    fun chooseBudgets() {
        viewModelScope.launch { readBudgets(keptMessage = null) }
    }

    private suspend fun readBudgets(keptMessage: String?) {
        val name = page?.name.orEmpty()
        val message = when (val read = services.connect.readBudgets()) {
            is BudgetsRead.Listed -> {
                listed = read.budgets
                chosen.clear()
                chosen += read.budgets.filter { it.served }.map { it.budgetAppReference }
                choosing = true
                keptMessage
            }
            BudgetsRead.BudgetAppCredentialsMissing -> "Budgets can be chosen once the $name credentials are accepted."
            BudgetsRead.CredentialsRefused -> "Blocked: credentials refused · Enter credentials"
            is BudgetsRead.CouldNotRead -> "$name could not be read: ${Texts.failure(read.kind)} · Budgets unchanged"
            is BudgetsRead.Unavailable -> Texts.refusal(read.reason)
        }
        reload(message)
    }

    /** Step 3: the Operator ticks or unticks a Budget. */
    fun toggleBudget(reference: String) {
        if (!chosen.remove(reference)) chosen += reference
        budgetsSection = budgetsSection?.copy(choosing = budgetLines())
        mutableState.value = mutableState.value?.copy(budgets = budgetsSection)
    }

    /** Steps 3-4, A2: the ticked Budgets are served; refused while a Budget left out has mapped Bank Accounts. */
    fun serveBudgets() {
        viewModelScope.launch {
            val message = when (val choice = services.connect.chooseBudgets(listed, chosen.toSet())) {
                BudgetChoice.Stored -> {
                    choosing = false
                    null
                }
                is BudgetChoice.StillMapped ->
                    "${choice.budgetName} has mapped Bank Accounts ${choice.bankAccountNames.joinToString(", ")} · remove their Account Mappings first"
                is BudgetChoice.Unavailable -> Texts.refusal(choice.reason)
            }
            reload(message)
        }
    }

    fun cancelChoosing() {
        choosing = false
        viewModelScope.launch { reload() }
    }

    /** A9, BR-014: "Receives from Slussen" switched off marks the Budget Account at once, with no dialog; on unmarks it. */
    fun setReceives(budgetAccountId: Long, receives: Boolean) {
        viewModelScope.launch { applyReceives(budgetAccountId, receives) }
    }

    private suspend fun applyReceives(budgetAccountId: Long, receives: Boolean) {
        val name = services.connect.servedBudgetAccounts().flatMap { it.budgetAccounts }.firstOrNull { it.budgetAccountId == budgetAccountId }?.name.orEmpty()
        var message: String? = null
        when (val outcome = services.connect.setReceivesFromSlussen(budgetAccountId, receives)) {
            KeepOutcome.Marked -> {
                snack = SnackUi("$name kept by hand · not offered for pairing", SnackAction.Undo)
                undoAction = { applyReceives(budgetAccountId, receives = true) }
            }
            KeepOutcome.Unmarked -> {
                snack = SnackUi("$name receives from Slussen again · offered for pairing", null)
                undoAction = null
            }
            is KeepOutcome.StillReceives -> message = "$name receives ${outcome.bankAccountName} · not kept by hand"
            KeepOutcome.NotFree, KeepOutcome.NotManuallyKept -> Unit
            is KeepOutcome.Unavailable -> message = Texts.refusal(outcome.reason)
        }
        reload(message)
    }

    /** Steps 6-7, A10, BR-012: the import switch of one Offered Bank Account: off leaves it out at once, on brings it back. */
    fun switchImport(offered: OfferedLineUi, on: Boolean) {
        viewModelScope.launch {
            if (on) {
                offered.leftOutBankAccountId?.let { switchOn(it) }
                return@launch
            }
            val offeredBankAccountId = offered.offeredBankAccountId ?: return@launch
            var message: String? = null
            when (val outcome = services.connect.switchImportOff(offeredBankAccountId)) {
                is ImportSwitchedOff.LeftOut -> {
                    snack = SnackUi("${outcome.name} not imported · never read", SnackAction.Undo)
                    undoAction = { switchOn(outcome.leftOutBankAccountId) }
                }
                ImportSwitchedOff.NoSwitch -> Unit
                is ImportSwitchedOff.Unavailable -> message = Texts.refusal(outcome.reason)
            }
            reload(message)
        }
    }

    // UC-013 BR-011: Switching the import of a Left-Out Bank Account on again brings it back; it is imported and to map.
    private suspend fun switchOn(leftOutBankAccountId: Long) {
        var message: String? = null
        when (val outcome = services.connect.bringBack(leftOutBankAccountId)) {
            is BroughtBack.Offered -> {
                snack = if (outcome.offeredNow) {
                    SnackUi("${outcome.name} imported · to map in Account Mappings", SnackAction.Map)
                } else {
                    SnackUi("${outcome.name} imported from the next delivery that holds it", null)
                }
                undoAction = null
            }
            BroughtBack.NotFound -> Unit
            is BroughtBack.Unavailable -> message = Texts.refusal(outcome.reason)
        }
        reload(message)
    }

    /** The action of the last switch: Undo switches it back. */
    fun undo() {
        val action = undoAction ?: return
        undoAction = null
        viewModelScope.launch { action() }
    }

    fun dismissSnack() {
        snack = null
        undoAction = null
        mutableState.value = mutableState.value?.copy(snack = null)
    }
}
