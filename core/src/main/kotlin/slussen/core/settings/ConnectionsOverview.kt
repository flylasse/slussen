package slussen.core.settings

import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.plugin.RegisteredDataProvider
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.ImportStatusStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ServiceKey
import slussen.pluginapi.credential.CredentialField
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The one status line of a service in Connections (UC-014 BR-007). */
sealed interface ServiceStatus {
    /** Credentials accepted; for a Data Provider with the time of its last Fetch, for the Budget App the Budgets in use. */
    data class Connected(val lastFetchAt: Instant?, val budgetsInUse: Int?) : ServiceStatus

    /** The nearest expiry of a Bank Consent of the Data Provider within the consent warning lead time (UC-008 BR-002). */
    data class BankConsentExpires(val expiresAt: Instant) : ServiceStatus

    data object CredentialsRefused : ServiceStatus

    data object CredentialsMissing : ServiceStatus
}

/** One service in Connections. */
data class ServiceLine(val service: ServiceKey, val name: String, val isBudgetApp: Boolean, val status: ServiceStatus)

/** Connections: the services in use, and below them the Data Providers of this build not set up (UC-014 BR-007). */
data class ConnectionsList(val inUse: List<ServiceLine>, val notSetUp: List<ServiceLine>)

/** One Bank Account on a Data Provider's page. */
data class ServiceBankAccount(val bankAccountId: Long, val name: String, val mapped: Boolean, val retired: Boolean)

/** One Budget in use on the Budget App's page. */
data class BudgetInUse(val budgetId: Long, val name: String, val currency: CurrencyCode)

/**
 * The page of one service, generated from what its part declares (UC-010 BR-010, ADR-001 §4.6) and joined with the
 * stored state: its status, its credentials, and for a Data Provider its Fetch Cooldown with the declared default, its
 * Request Limit and its Bank Accounts, for the Budget App the Budgets in use.
 */
data class ServicePage(
    val service: ServiceKey,
    val name: String,
    val isBudgetApp: Boolean,
    val status: ServiceStatus,
    val credentialStatus: CredentialStatus,
    val credentialFields: List<CredentialField>,
    val needsBankConsent: Boolean,
    val fetchCooldownMinutes: Int?,
    val defaultFetchCooldownMinutes: Int?,
    val requestLimit: Int?,
    val bankAccounts: List<ServiceBankAccount>,
    val budgetsInUse: List<BudgetInUse>,
    val supportsClearedMarker: Boolean,
)

/**
 * The Connections read model of `:core` `settings` (ADR-001 §4.6): for each registered plug-in, its declarations joined
 * with the stored state. No screen names a service; everything shown comes from here.
 */
class ConnectionsOverview(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val connections: ConnectionStore,
    private val importStatus: ImportStatusStore,
    private val settings: OperatorSettingsStore,
    private val clock: Clock,
) {
    suspend fun connections(): ConnectionsList {
        val bankAccounts = connections.bankAccounts()
        val budgetsInUse = connections.budgets().count { it.served }
        val budgetApp = ServiceLine(
            ServiceKey.budgetApp,
            plugins.budgetApp.profile.name,
            isBudgetApp = true,
            status = statusOf(ServiceKey.budgetApp, budgetsInUse),
        )
        val inUse = mutableListOf(budgetApp)
        val notSetUp = mutableListOf<ServiceLine>()
        plugins.dataProviders.forEach { provider ->
            val hasBankAccount = bankAccounts.any { it.dataProviderId == provider.stored.id }
            val hasCredentials = credentials.status(provider.key) != CredentialStatus.Missing
            val line = ServiceLine(provider.key, provider.stored.name, isBudgetApp = false, status = statusOf(provider))
            // UC-014 BR-007: A Data Provider is in use when its credentials are entered or it has a Bank Account; otherwise it is listed as not set up.
            if (hasCredentials || hasBankAccount) inUse += line else notSetUp += line
        }
        return ConnectionsList(inUse, notSetUp)
    }

    suspend fun servicePage(service: ServiceKey): ServicePage {
        val form = credentials.form(service).fields
        val credentialStatus = credentials.status(service)
        if (service == ServiceKey.budgetApp) {
            val served = connections.budgets().filter { it.served }
            return ServicePage(
                service = service,
                name = plugins.budgetApp.profile.name,
                isBudgetApp = true,
                status = statusOf(service, served.size),
                credentialStatus = credentialStatus,
                credentialFields = form,
                needsBankConsent = false,
                fetchCooldownMinutes = null,
                defaultFetchCooldownMinutes = null,
                requestLimit = null,
                bankAccounts = emptyList(),
                budgetsInUse = served.map { BudgetInUse(it.id, it.name, it.currency) },
                supportsClearedMarker = plugins.budgetApp.profile.supportsClearedMarker,
            )
        }
        val provider = plugins.dataProvider(service)
        return ServicePage(
            service = service,
            name = provider.stored.name,
            isBudgetApp = false,
            status = statusOf(provider),
            credentialStatus = credentialStatus,
            credentialFields = form,
            needsBankConsent = provider.stored.needsBankConsent,
            fetchCooldownMinutes = provider.stored.fetchCooldownMinutes,
            defaultFetchCooldownMinutes = provider.plugin.profile.defaultFetchCooldownMinutes,
            requestLimit = provider.stored.requestLimit,
            bankAccounts = connections.bankAccounts().filter { it.dataProviderId == provider.stored.id }.map {
                ServiceBankAccount(it.id, it.name, mapped = it.mapping != null, retired = it.retired)
            },
            budgetsInUse = emptyList(),
            supportsClearedMarker = false,
        )
    }

    private suspend fun statusOf(service: ServiceKey, budgetsInUse: Int): ServiceStatus =
        when (credentials.status(service)) {
            // UC-014 BR-007: The Budget App is listed with "credentials missing" until its credentials are accepted.
            CredentialStatus.Missing -> ServiceStatus.CredentialsMissing
            CredentialStatus.Refused -> ServiceStatus.CredentialsRefused
            is CredentialStatus.Accepted -> ServiceStatus.Connected(lastFetchAt = null, budgetsInUse = budgetsInUse)
        }

    private suspend fun statusOf(provider: RegisteredDataProvider): ServiceStatus {
        val credentialStatus = credentials.status(provider.key)
        val fetches = importStatus.recentFetches().filter { it.dataProviderId == provider.stored.id }
        val lastFetch = fetches.maxByOrNull { it.startedAt }
        val acceptedAt = (credentialStatus as? CredentialStatus.Accepted)?.acceptedAt
        return when {
            credentialStatus == CredentialStatus.Missing -> ServiceStatus.CredentialsMissing
            credentialStatus == CredentialStatus.Refused -> ServiceStatus.CredentialsRefused
            // UC-014 BR-005: A Data Provider that refused Slussen's credentials in its last Fetch shows "credentials refused" until new ones are accepted.
            lastFetch != null && lastFetch.outcome == FetchOutcome.CredentialsRefused &&
                (acceptedAt == null || acceptedAt.isBefore(lastFetch.startedAt)) -> ServiceStatus.CredentialsRefused
            else -> nearestConsentExpiry(provider) ?: ServiceStatus.Connected(lastFetch?.startedAt, budgetsInUse = null)
        }
    }

    private suspend fun nearestConsentExpiry(provider: RegisteredDataProvider): ServiceStatus? {
        val now = clock.instant()
        val leadTime = Duration.ofDays(settings.current().consentWarningDays.toLong())
        return importStatus.bankConsents()
            .filter { it.dataProviderId == provider.stored.id }
            .map { it.expiresAt }
            .filter { !it.isAfter(now.plus(leadTime)) }
            .minOrNull()
            ?.let { ServiceStatus.BankConsentExpires(it) }
    }
}
