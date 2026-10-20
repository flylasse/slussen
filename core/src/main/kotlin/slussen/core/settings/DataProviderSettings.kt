package slussen.core.settings

import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.DataProviderSettingsStore
import slussen.core.port.JournalEventKind
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.pluginapi.provider.RequestLimitDeclaration

/** The outcome of changing an Operator Setting of one Data Provider (UC-010 steps 2-6, A1, A11). */
sealed interface SettingOutcome {
    /** Steps 4-6: stored, applied from the next request on, and recorded in the Decision Journal. */
    data class Stored(val value: Int) : SettingOutcome

    /** A1: outside the allowed range; the previous value is kept and the range shown. */
    data class OutsideRange(val range: IntRange) : SettingOutcome

    /** The Data Provider publishes its Request Limit: it is not an Operator Setting (UC-001 BR-007). */
    data object NotAnOperatorSetting : SettingOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : SettingOutcome
}

/**
 * The Operator Settings that belong to one Data Provider and are shown on its page, opened from its entry in Settings
 * (UC-010 step 1, BR-010), `:core` `settings` (ADR-001 §7): here the Request Limit of a Data Provider that publishes
 * none (UC-010 A11, BR-002, UC-001 BR-007).
 */
class DataProviderSettings(
    private val plugins: RegisteredPlugins,
    private val store: DataProviderSettingsStore,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
) {
    /** Steps 2-6, A1: the Request Limit of [service] becomes [requests]. */
    suspend fun changeRequestLimit(service: ServiceKey, requests: Int): SettingOutcome =
        storeRequestLimit(service) { requests }

    /** A11: the Request Limit of [service] goes back to the default its part declares. */
    suspend fun resetRequestLimit(service: ServiceKey): SettingOutcome =
        // UC-010 BR-006: The Request Limit of a Data Provider that publishes none can be reset to the default its part declares, recorded like any other change.
        storeRequestLimit(service) { declared -> declared.defaultRequests }

    private suspend fun storeRequestLimit(
        service: ServiceKey,
        value: (RequestLimitDeclaration.NotPublished) -> Int,
    ): SettingOutcome {
        val provider = plugins.dataProvider(service)
        // UC-001 BR-007: A Request Limit the Data Provider publishes is fixed and is not an Operator Setting.
        val declared = provider.plugin.profile.requestLimit as? RequestLimitDeclaration.NotPublished
            ?: return SettingOutcome.NotAnOperatorSetting
        val requests = value(declared)
        val result = coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) {
            // UC-010 BR-002: The Request Limit of a Data Provider that publishes none lies within the range its part declares.
            if (requests !in declared.range) return@run SettingOutcome.OutsideRange(declared.range)
            val before = provider.stored.requestLimit
            store.storeRequestLimit(provider.stored.id, requests)
            provider.stored = provider.stored.copy(requestLimit = requests)
            journal.record(
                JournalEventKind.ConfigurationChange,
                "Request Limit of ${provider.stored.name} changed from $before to $requests requests in any 24 hours",
            )
            SettingOutcome.Stored(requests)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> SettingOutcome.Unavailable(result.reason)
        }
    }
}
