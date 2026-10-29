package slussen.core.settings

import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.plugin.RegisteredDataProvider
import slussen.core.plugin.RegisteredPlugins
import slussen.core.connection.ConnectionReads
import slussen.core.connection.ImportState
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.ImportStatusStore
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ServiceKey
import slussen.pluginapi.credential.CredentialField
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The status of a service on its page (UC-014 BR-007, BR-005). */
sealed interface ServiceStatus {
    /** UC-014 BR-007: neither credentials nor a Bank Account; for the Budget App neither credentials nor a served Budget. */
    data object NotSetUp : ServiceStatus

    /** Credentials accepted; for a Data Provider with the time of its last Fetch, for the Budget App the Budgets in use. */
    data class Connected(val lastFetchAt: Instant?, val budgetsInUse: Int?) : ServiceStatus

    /** The nearest expiry of a Bank Consent of the Data Provider within the consent warning lead time (UC-008 BR-002). */
    data class BankConsentExpires(val expiresAt: Instant) : ServiceStatus

    data object CredentialsRefused : ServiceStatus

    /** Removed or lost while the service has a Bank Account or serves a Budget (UC-014 A3, BR-007). */
    data object CredentialsMissing : ServiceStatus
}

/** What kind of part of this build an entry at the top of Settings stands for (UC-010 BR-010). */
enum class EntryKind { BudgetApp, DataProvider, FileFormat }

/** The one status line of an entry at the top of Settings (UC-014 BR-007). */
sealed interface EntryStatus {
    /** Neither credentials nor a Bank Account; for the Budget App neither credentials nor a served Budget. */
    data object NotSetUp : EntryStatus

    data object CredentialsRefused : EntryStatus

    /** Credentials removed or lost while the Data Provider has [bankAccounts] Bank Accounts, or, with null, while the Budget App serves a Budget. */
    data class CredentialsMissing(val bankAccounts: Int?) : EntryStatus

    /** The Budget App with accepted credentials and the number of Budgets it serves. */
    data class BudgetsServed(val count: Int) : EntryStatus

    /** A Data Provider with accepted credentials that offers no Bank Account yet: fetch it, or grant a Bank Consent (UC-013 A3). */
    data class NothingOffered(val needsBankConsent: Boolean) : EntryStatus

    /** How many of a Data Provider's Offered Bank Accounts are imported or added, with the time of its last Fetch. */
    data class Imported(val imported: Int, val of: Int, val lastFetchAt: Instant?) : EntryStatus

    /** The nearest expiry of a Bank Consent of the Data Provider within the consent warning lead time (UC-008 BR-002). */
    data class BankConsentExpires(val expiresAt: Instant) : EntryStatus

    /** A File Format with the number of its Bank Accounts. */
    data class FileFormatBankAccounts(val count: Int) : EntryStatus
}

/** One entry at the top of Settings: a service or a File Format of this build, opening its page (UC-014 BR-007, UC-010 BR-010). */
data class SettingsEntry(
    val kind: EntryKind,
    /** The Budget App or the Data Provider; null for a File Format. */
    val service: ServiceKey?,
    /** The File Format; null for a service. */
    val fileFormatId: Long?,
    val name: String,
    val needsBankConsent: Boolean,
    val status: EntryStatus,
    /** UC-013 BR-008: this entry is the first that still needs what comes next. */
    val isNext: Boolean,
)

/** What the Account Mappings wait for before they are available (UC-013 BR-015), the first thing missing. */
enum class MappingsWaitFor { BudgetAppCredentials, ChosenBudget, ImportedBankAccount }

/** The Account Mappings entry at the top of Settings (UC-013 BR-015, UC-009 BR-014). */
data class AccountMappingsEntry(
    val available: Boolean,
    val toMap: Int,
    /** Null once available. */
    val waitingFor: MappingsWaitFor?,
    val isNext: Boolean,
)

/** UC-013 BR-008, UC-008 BR-011: the next thing to do in setting up Slussen, the first in the order of setting up that is not done. */
sealed interface NextThing {
    data class EnterBudgetAppCredentials(val budgetAppName: String) : NextThing

    data class ChooseBudgets(val budgetAppName: String) : NextThing

    /** No Data Provider has credentials yet. */
    data object EnterDataProviderCredentials : NextThing

    /** Fetch one of [toFetch] or grant a Bank Consent in one of [toGrant], so that it offers its Bank Accounts (UC-013 A3). */
    data class FetchOrGrantBankConsent(val toFetch: List<String>, val toGrant: List<String>) : NextThing

    /** Map the [count] imported Bank Accounts in the Account Mappings. */
    data class MapBankAccounts(val count: Int) : NextThing
}

/** The top of Settings: one entry per service and File Format, in the order of setting up, then the Account Mappings. */
data class SettingsEntries(val entries: List<SettingsEntry>, val accountMappings: AccountMappingsEntry, val next: NextThing?)

/** One Bank Account on a Data Provider's page. */
data class ServiceBankAccount(val bankAccountId: Long, val name: String, val mapped: Boolean, val retired: Boolean)

/**
 * The Request Limit on a Data Provider's page (UC-010 BR-010, ADR-001 §4.6): the limit the Data Provider publishes,
 * read-only with its source, or the Operator's value with the default and range its part declares.
 */
sealed interface RequestLimitShown {
    val requests: Int

    data class Published(override val requests: Int, val source: String) : RequestLimitShown

    data class NotPublished(override val requests: Int, val defaultRequests: Int, val range: IntRange) : RequestLimitShown
}

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
    val requestLimit: RequestLimitShown?,
    val bankAccounts: List<ServiceBankAccount>,
    val budgetsInUse: List<BudgetInUse>,
    val supportsClearedMarker: Boolean,
    /** UC-010 BR-002: the allowed range of the Fetch Cooldown, in minutes; null on the Budget App's page. */
    val fetchCooldownRange: IntRange? = null,
    /** UC-014 BR-005: when the service refused Slussen's credentials at a Fetch, the time of that Fetch, or null. */
    val credentialsRefusedAt: Instant? = null,
    /** Whether a change on this page is recorded in the Decision Journal (UC-010 step 6, postconditions). */
    val journalOn: Boolean = true,
)

/**
 * The Settings read model of `:core` `settings` (ADR-001 §4.6, S-40): for each registered plug-in, its declarations
 * joined with the stored state, as the entries at the top of Settings and the page of each service. No screen names a
 * service; everything shown comes from here.
 */
class SettingsOverview(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val connections: ConnectionStore,
    private val offered: OfferedBankAccountStore,
    private val importStatus: ImportStatusStore,
    private val settings: OperatorSettingsStore,
    private val clock: Clock,
) {
    private val reads = ConnectionReads(plugins, connections, offered)

    /** UC-014 BR-007, UC-010 BR-010, UC-013 BR-008, BR-015: the entries at the top of Settings. */
    suspend fun entries(): SettingsEntries {
        val snapshot = reads.snapshot()
        val budgetAppStatus = credentials.status(ServiceKey.budgetApp)
        val served = snapshot.budgets.count { it.served }
        val toMap = reads.toMap(snapshot).size
        val next = nextThing(snapshot, budgetAppStatus, served, toMap)
        val providers = plugins.dataProviders.map { provider -> provider to entryStatus(provider, snapshot) }
        val nextProvider = when (next) {
            NextThing.EnterDataProviderCredentials -> providers.firstOrNull()?.first
            is NextThing.FetchOrGrantBankConsent ->
                providers.firstOrNull { it.second is EntryStatus.NothingOffered }?.first
                    ?: providers.firstOrNull { credentials.status(it.first.key) is CredentialStatus.Accepted }?.first
            else -> null
        }
        val budgetAppEntry = SettingsEntry(
            kind = EntryKind.BudgetApp,
            service = ServiceKey.budgetApp,
            fileFormatId = null,
            name = plugins.budgetApp.profile.name,
            needsBankConsent = false,
            status = when {
                // UC-014 BR-007: The Budget App is not set up while it has neither credentials nor a served Budget.
                budgetAppStatus == CredentialStatus.Missing && served == 0 -> EntryStatus.NotSetUp
                budgetAppStatus == CredentialStatus.Missing -> EntryStatus.CredentialsMissing(bankAccounts = null)
                budgetAppStatus == CredentialStatus.Refused -> EntryStatus.CredentialsRefused
                else -> EntryStatus.BudgetsServed(served)
            },
            isNext = next is NextThing.EnterBudgetAppCredentials || next is NextThing.ChooseBudgets,
        )
        val providerEntries = providers.map { (provider, status) ->
            SettingsEntry(
                kind = EntryKind.DataProvider,
                service = provider.key,
                fileFormatId = null,
                name = provider.stored.name,
                needsBankConsent = provider.stored.needsBankConsent,
                status = status,
                isNext = provider === nextProvider,
            )
        }
        val formatEntries = plugins.fileFormats.map { format ->
            SettingsEntry(
                kind = EntryKind.FileFormat,
                service = null,
                fileFormatId = format.id,
                name = format.name,
                needsBankConsent = false,
                status = EntryStatus.FileFormatBankAccounts(snapshot.bankAccounts.count { it.fileFormatId == format.id }),
                isNext = false,
            )
        }
        // UC-013 BR-015: The Account Mappings are available once a Budget is served and a Bank Account is added or imported to map; until then the first thing missing is the reason.
        val waitingFor = when {
            served == 0 && budgetAppStatus !is CredentialStatus.Accepted -> MappingsWaitFor.BudgetAppCredentials
            served == 0 -> MappingsWaitFor.ChosenBudget
            snapshot.bankAccounts.isEmpty() && toMap == 0 -> MappingsWaitFor.ImportedBankAccount
            else -> null
        }
        return SettingsEntries(
            // UC-010 BR-010: Settings lists the services directly at its top, in the order of setting up: the Budget App, each Data Provider, each File Format.
            entries = listOf(budgetAppEntry) + providerEntries + formatEntries,
            accountMappings = AccountMappingsEntry(
                available = waitingFor == null,
                toMap = toMap,
                waitingFor = waitingFor,
                isNext = next is NextThing.MapBankAccounts,
            ),
            next = next,
        )
    }

    /**
     * UC-013 BR-008: the first thing in the order of setting up that is not done: the Budget App's credentials, the
     * Budgets, a Data Provider's credentials, its Fetch or a Bank Consent so that it offers its Bank Accounts, then the
     * Bank Accounts to map; nothing once no Bank Account is to map and at least one is added.
     */
    private suspend fun nextThing(snapshot: ConnectionReads.Snapshot, budgetAppStatus: CredentialStatus, served: Int, toMap: Int): NextThing? {
        val budgetAppName = plugins.budgetApp.profile.name
        return when {
            budgetAppStatus !is CredentialStatus.Accepted -> NextThing.EnterBudgetAppCredentials(budgetAppName)
            served == 0 -> NextThing.ChooseBudgets(budgetAppName)
            toMap > 0 -> NextThing.MapBankAccounts(toMap)
            snapshot.bankAccounts.isNotEmpty() -> null
            else -> {
                val connected = plugins.dataProviders.filter { credentials.status(it.key) is CredentialStatus.Accepted }
                if (connected.isEmpty()) {
                    NextThing.EnterDataProviderCredentials
                } else {
                    NextThing.FetchOrGrantBankConsent(
                        toFetch = connected.filter { !it.stored.needsBankConsent }.map { it.stored.name },
                        toGrant = connected.filter { it.stored.needsBankConsent }.map { it.stored.name },
                    )
                }
            }
        }
    }

    // UC-014 BR-007: Each Data Provider's entry has one status line: not set up, credentials refused or missing, a Bank Consent's expiry, nothing offered yet, or how many of its Offered Bank Accounts are imported.
    private suspend fun entryStatus(provider: RegisteredDataProvider, snapshot: ConnectionReads.Snapshot): EntryStatus {
        val bankAccounts = snapshot.bankAccounts.count { it.dataProviderId == provider.stored.id }
        val offeredStates = snapshot.offered.filter { it.dataProviderId == provider.stored.id }.map { reads.stateOf(it, snapshot) }
        return when (val status = statusOf(provider)) {
            ServiceStatus.NotSetUp -> EntryStatus.NotSetUp
            ServiceStatus.CredentialsMissing -> EntryStatus.CredentialsMissing(bankAccounts)
            ServiceStatus.CredentialsRefused -> EntryStatus.CredentialsRefused
            is ServiceStatus.BankConsentExpires -> EntryStatus.BankConsentExpires(status.expiresAt)
            is ServiceStatus.Connected -> if (offeredStates.isEmpty()) {
                EntryStatus.NothingOffered(provider.stored.needsBankConsent)
            } else {
                EntryStatus.Imported(
                    imported = offeredStates.count { it == ImportState.ToMap || it is ImportState.Added },
                    of = offeredStates.count { it == ImportState.ToMap || it is ImportState.Added || it is ImportState.NotImported },
                    lastFetchAt = status.lastFetchAt,
                )
            }
        }
    }

    /** UC-013 BR-008, UC-008 BR-011: the next thing to do in setting up Slussen, or null when nothing is left to do. */
    suspend fun next(): NextThing? = entries().next

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
                journalOn = settings.current().journalOn,
            )
        }
        val provider = plugins.dataProvider(service)
        val status = statusOf(provider)
        return ServicePage(
            service = service,
            name = provider.stored.name,
            isBudgetApp = false,
            status = status,
            credentialStatus = credentialStatus,
            credentialFields = form,
            needsBankConsent = provider.stored.needsBankConsent,
            fetchCooldownMinutes = provider.stored.fetchCooldownMinutes,
            defaultFetchCooldownMinutes = provider.plugin.profile.defaultFetchCooldownMinutes,
            requestLimit = requestLimitShown(provider),
            bankAccounts = connections.bankAccounts().filter { it.dataProviderId == provider.stored.id }.map {
                ServiceBankAccount(it.id, it.name, mapped = it.mapping != null, retired = it.retired)
            },
            budgetsInUse = emptyList(),
            supportsClearedMarker = false,
            fetchCooldownRange = AllowedRanges.FETCH_COOLDOWN_MINUTES,
            credentialsRefusedAt = if (status == ServiceStatus.CredentialsRefused) lastFetchRefusedAt(provider) else null,
            journalOn = settings.current().journalOn,
        )
    }

    /** UC-014 BR-005: the time of the last Fetch of [provider], when it was refused for its credentials. */
    private suspend fun lastFetchRefusedAt(provider: RegisteredDataProvider): Instant? =
        importStatus.recentFetches()
            .filter { it.dataProviderId == provider.stored.id }
            .maxByOrNull { it.startedAt }
            ?.takeIf { it.outcome == FetchOutcome.CredentialsRefused }
            ?.startedAt

    // UC-010 BR-010: A Data Provider's page shows its Request Limit: published with its source and not changeable, or the Operator's value with the default and range its part declares.
    private fun requestLimitShown(provider: RegisteredDataProvider): RequestLimitShown =
        when (val declared = provider.plugin.profile.requestLimit) {
            is RequestLimitDeclaration.Published -> RequestLimitShown.Published(provider.stored.requestLimit, declared.source)
            is RequestLimitDeclaration.NotPublished ->
                RequestLimitShown.NotPublished(provider.stored.requestLimit, declared.defaultRequests, declared.range)
        }

    private suspend fun statusOf(service: ServiceKey, budgetsInUse: Int): ServiceStatus =
        when (credentials.status(service)) {
            // UC-014 BR-007: The Budget App is not set up while it has neither credentials nor a served Budget, and "credentials missing" when they were removed or lost while it serves one.
            CredentialStatus.Missing -> if (budgetsInUse == 0) ServiceStatus.NotSetUp else ServiceStatus.CredentialsMissing
            CredentialStatus.Refused -> ServiceStatus.CredentialsRefused
            is CredentialStatus.Accepted -> ServiceStatus.Connected(lastFetchAt = null, budgetsInUse = budgetsInUse)
        }

    private suspend fun statusOf(provider: RegisteredDataProvider): ServiceStatus {
        val credentialStatus = credentials.status(provider.key)
        val fetches = importStatus.recentFetches().filter { it.dataProviderId == provider.stored.id }
        val lastFetch = fetches.maxByOrNull { it.startedAt }
        val acceptedAt = (credentialStatus as? CredentialStatus.Accepted)?.acceptedAt
        return when {
            // UC-014 BR-007: A Data Provider is not set up while it has neither credentials nor a Bank Account, and "credentials missing" when they were removed or lost while it has one.
            credentialStatus == CredentialStatus.Missing ->
                if (connections.bankAccounts().any { it.dataProviderId == provider.stored.id }) ServiceStatus.CredentialsMissing else ServiceStatus.NotSetUp
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
