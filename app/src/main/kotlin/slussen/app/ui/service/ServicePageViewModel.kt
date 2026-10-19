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
import slussen.core.settings.ServicePage
import slussen.core.settings.ServiceStatus
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.value.Secret

/** One credential field as the service's part declares it (UC-014 BR-002). */
data class FieldUi(val key: String, val kind: FieldKind, val description: String)

/**
 * The page of one service (UC-010 BR-010, UC-014 BR-007), generated from its declarations, with its credential form
 * (UC-014 steps 2-6, A1-A4).
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

    fun load() {
        viewModelScope.launch { show(services.connections.servicePage(service)) }
    }

    private fun show(
        page: ServicePage,
        entering: Boolean = false,
        message: String? = null,
        removalQuestion: String? = null,
        returnToSetup: Boolean = false,
    ) {
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
            requestLimit = page.requestLimit?.let {
                "$it per 24 hours · from the Data Provider's own limit, declared by its part · not a setting"
            },
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

    /** Steps 3-6, A1, A2: [values] by the key of each declared field; a File field holds the chosen file's content. */
    fun save(values: Map<String, String>) {
        viewModelScope.launch {
            val input = CredentialInput(values.mapValues { Secret(it.value) })
            val outcome = services.credentials.enter(service, input)
            val page = services.connections.servicePage(service)
            val message = Texts.outcome(page.name, outcome)
            when (outcome) {
                is CredentialOutcome.Accepted -> show(page, message = message, returnToSetup = fromSetup)
                // A1: use case continues at step 2; the form stays open.
                CredentialOutcome.Refused -> show(page, entering = true, message = message)
                // A2: use case ends.
                is CredentialOutcome.NotReached -> show(page, message = message)
                is CredentialOutcome.Unavailable -> show(page, entering = true, message = message)
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
            val page = services.connections.servicePage(service)
            when (outcome) {
                RemovalOutcome.Removed -> show(page, message = "Removed · recorded in the Decision Journal")
                is RemovalOutcome.Unavailable -> show(page, message = Texts.refusal(outcome.reason))
            }
        }
    }
}
