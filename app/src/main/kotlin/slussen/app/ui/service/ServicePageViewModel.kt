package slussen.app.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.credential.CredentialOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.credential.RemovalOutcome
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

/**
 * The page of one service (UC-010 BR-010, UC-014 BR-007), generated from its declarations, with its credential form
 * (UC-014 steps 2-6, A1-A5) and the Request Limit setting of a Data Provider that publishes none (UC-010 A11).
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
    val budgetsInUse: List<String>,
    val uploadAsClearedShown: Boolean,
    /** Set when accepted credentials return to Connect Budgets and Bank Accounts, which started this use case (step 6). */
    val returnToSetup: Boolean,
)

/** A service's page in Connections (UC-014). [fromSetup] when Connect Budgets and Bank Accounts started it. */
class ServicePageViewModel(
    private val services: UiServices,
    private val service: ServiceKey,
    private val fromSetup: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ServicePageUi?>(null)
    val state: StateFlow<ServicePageUi?> = mutableState

    private var page: ServicePage? = null

    fun load() {
        viewModelScope.launch { show(services.connections.servicePage(service)) }
    }

    private fun show(
        page: ServicePage,
        entering: Boolean = false,
        message: String? = null,
        removalQuestion: String? = null,
        returnToSetup: Boolean = false,
        requestLimitEditor: RequestLimitEditorUi? = null,
    ) {
        this.page = page
        val formats = services.formats
        val missingOrRefused = page.status == ServiceStatus.CredentialsMissing || page.status == ServiceStatus.CredentialsRefused
        mutableState.value = ServicePageUi(
            name = page.name,
            kind = if (page.isBudgetApp) "Budget App" else if (page.needsBankConsent) "Data Provider · needs Bank Consent" else "Data Provider",
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
            budgetsInUse = page.budgetsInUse.map { "${it.name} · ${it.currency.code}" },
            uploadAsClearedShown = page.supportsClearedMarker,
            returnToSetup = returnToSetup,
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
            val page = services.connections.servicePage(service)
            val message = Texts.outcome(page.name, outcome, services.formats)
            when (outcome) {
                is CredentialOutcome.Accepted -> show(page, message = message, returnToSetup = fromSetup)
                // A1: use case continues at step 2; the form stays open.
                CredentialOutcome.Refused -> show(page, entering = true, message = message)
                // A2, A5: use case ends.
                is CredentialOutcome.NotReached, is CredentialOutcome.RequestLimitReached, is CredentialOutcome.NotSent ->
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
            is SettingOutcome.Stored -> show(services.connections.servicePage(service), message = storedMessage)
            // A1: refused with the allowed range; the editor stays open (use case continues at step 2).
            is SettingOutcome.OutsideRange -> mutableState.value = mutableState.value?.copy(
                message = "$value is outside the allowed range · enter ${outcome.range.first} to ${outcome.range.last} · " +
                    "the Request Limit stays $before",
                requestLimitEditor = editor,
            )
            SettingOutcome.NotAnOperatorSetting -> show(services.connections.servicePage(service), message = "Published by the Data Provider · not a setting")
            is SettingOutcome.Unavailable -> mutableState.value = mutableState.value?.copy(message = Texts.refusal(outcome.reason))
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
            val page = services.connections.servicePage(service)
            when (outcome) {
                RemovalOutcome.Removed -> show(page, message = "Removed · recorded in the Decision Journal")
                is RemovalOutcome.Unavailable -> show(page, message = Texts.refusal(outcome.reason))
            }
        }
    }
}
