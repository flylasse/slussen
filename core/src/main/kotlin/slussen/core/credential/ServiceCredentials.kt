package slussen.core.credential

import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.CredentialVault
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.request.BudgetAppGate
import slussen.core.request.DataProviderGate
import slussen.core.request.ServiceRequestContext
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialForm
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestTimeout
import slussen.pluginapi.value.Secret
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The credential status of one service, from stored data only (UC-014 BR-005, BR-007). */
sealed interface CredentialStatus {
    data class Accepted(val acceptedAt: Instant) : CredentialStatus

    /** The service refused the stored credentials; Slussen does not contact it until new ones are entered. */
    data object Refused : CredentialStatus

    /** No credentials are stored. */
    data object Missing : CredentialStatus
}

/** Reads the credential status of a service; the import status uses it without seeing a plug-in (UC-008 BR-001). */
fun interface CredentialStatusReader {
    suspend fun status(service: ServiceKey): CredentialStatus
}

/** The outcome of entering credentials (UC-014 steps 2-6, A1, A2, precondition). */
sealed interface CredentialOutcome {
    /** Step 4-6: stored, encrypted, in place of the earlier ones. */
    data class Accepted(val acceptedAt: Instant) : CredentialOutcome

    /** A1: nothing stored, the earlier credentials unchanged. */
    data object Refused : CredentialOutcome

    /** A2: the service did not answer within the request timeout or was unavailable; nothing stored. */
    data class NotReached(val kind: FailureKind) : CredentialOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : CredentialOutcome
}

/** The outcome of removing credentials (UC-014 A3). */
sealed interface RemovalOutcome {
    data object Removed : RemovalOutcome

    data class Unavailable(val reason: OperationRefusal) : RemovalOutcome
}

/**
 * The refusals of stored credentials that Slussen saw in this process (UC-014 BR-005): a service that refused them is not
 * contacted again until new credentials are accepted. A Data Provider's refusal during a Fetch is also stored as the
 * Fetch outcome Credentials Refused (UC-001 A7).
 */
class CredentialRefusals {
    private val refused = mutableSetOf<ServiceKey>()

    @Synchronized
    fun markRefused(service: ServiceKey) {
        refused += service
    }

    @Synchronized
    fun clear(service: ServiceKey) {
        refused -= service
    }

    @Synchronized
    fun isRefused(service: ServiceKey): Boolean = service in refused
}

/**
 * Manage Service Credentials (UC-014): entering, checking, replacing and removing the service credentials of the
 * Budget App and of each Data Provider, kept only on the device and encrypted (ADR-001 §4.5, ADR-002 §2a).
 */
class ServiceCredentials(
    private val plugins: RegisteredPlugins,
    private val vault: CredentialVault,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
    private val settings: OperatorSettingsStore,
    private val providerRequests: ProviderRequestStore,
    private val refusals: CredentialRefusals,
    private val clock: Clock,
) : CredentialStatusReader {
    private fun capability(service: ServiceKey): CredentialCapability =
        if (service == ServiceKey.budgetApp) plugins.budgetApp.credentials else plugins.dataProvider(service).plugin.credentials

    // UC-014 BR-002: Each part declares the credentials it needs, with what each is and where to get it.
    fun form(service: ServiceKey): CredentialForm = capability(service).form

    override suspend fun status(service: ServiceKey): CredentialStatus {
        val stored = vault.read(service) ?: return CredentialStatus.Missing
        return if (refusals.isRefused(service)) CredentialStatus.Refused else CredentialStatus.Accepted(stored.acceptedAt)
    }

    /** The stored credential of [service] for the [RequestContext] of a call, or null when there is none. */
    suspend fun stored(service: ServiceKey): Secret? = vault.read(service)?.credential

    /** Records that [service] refused the stored credentials (UC-014 BR-005, UC-013 A1). */
    fun markRefused(service: ServiceKey) = refusals.markRefused(service)

    /** The gate of a request to [service] within the operation that holds [permit] (ADR-001 §4.0). */
    fun gate(service: ServiceKey, permit: OperationPermit): RequestGate =
        if (service == ServiceKey.budgetApp) {
            BudgetAppGate(permit)
        } else {
            DataProviderGate(plugins.dataProvider(service).stored, providerRequests, clock, permit)
        }

    /** The request context of a call to [service], with the request timeout Operator Setting (NFR-016). */
    suspend fun context(service: ServiceKey, permit: OperationPermit, credential: Secret?): RequestContext =
        ServiceRequestContext(
            gate = gate(service, permit),
            timeout = RequestTimeout(Duration.ofSeconds(settings.current().requestTimeoutSeconds.toLong())),
            credential = credential,
        )

    suspend fun enter(service: ServiceKey, input: CredentialInput): CredentialOutcome {
        val result = coordinator.run(OperationKind.ServiceCredentialChange, StartedBy.Operator) { permit ->
            // UC-014 BR-002: The credentials are checked with the service the way its part declares.
            when (val check = capability(service).check(input, context(service, permit, credential = null))) {
                is CredentialCheck.Accepted -> {
                    val acceptedAt = clock.instant()
                    // UC-014 BR-003: Credentials are stored only on the device, encrypted with a key that never leaves the device.
                    vault.replace(service, check.credential, acceptedAt)
                    refusals.clear(service)
                    journal.record(
                        JournalEventKind.ConfigurationChange,
                        "Service credentials of ${plugins.nameOf(service)} changed",
                    )
                    CredentialOutcome.Accepted(acceptedAt)
                }
                // UC-014 BR-005: Refused credentials replace nothing; the earlier ones stay unchanged.
                CredentialCheck.Refused -> CredentialOutcome.Refused
                is CredentialCheck.TransientFailure -> CredentialOutcome.NotReached(check.kind)
            }
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> CredentialOutcome.Unavailable(result.reason)
        }
    }

    suspend fun remove(service: ServiceKey): RemovalOutcome {
        val result = coordinator.run(OperationKind.ServiceCredentialChange, StartedBy.Operator) {
            vault.remove(service)
            refusals.clear(service)
            journal.record(JournalEventKind.ConfigurationChange, "Service credentials of ${plugins.nameOf(service)} removed")
        }
        return when (result) {
            is OperationResult.Ran -> RemovalOutcome.Removed
            is OperationResult.Refused -> RemovalOutcome.Unavailable(result.reason)
        }
    }
}
