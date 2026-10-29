package slussen.core.settings

import kotlinx.coroutines.CancellationException
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationResult
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettings
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.StartedBy

/** The fetch period as Settings shows it: its value, its default and its allowed range (UC-010 step 1, BR-001, BR-002). */
data class FetchPeriodShown(val days: Int, val defaultDays: Int, val range: IntRange)

/**
 * The Operator Settings that apply to everything, as Settings shows them (UC-010 step 1): the fetch period, which the
 * Operator can change, and the others with the values that apply. [journalOn] says whether a change is recorded in
 * the Decision Journal.
 */
data class GeneralSettingsShown(val fetchPeriod: FetchPeriodShown, val values: OperatorSettings, val journalOn: Boolean)

/** The allowed ranges of the Operator Settings this build lets the Operator change (UC-010 BR-002). */
object AllowedRanges {
    // UC-010 BR-002: The fetch period lies between 7 and 180 days.
    val FETCH_PERIOD_DAYS: IntRange = 7..180

    // UC-010 BR-002: The Fetch Cooldown of each Data Provider lies between 0 and 1440 minutes; 0 switches it off.
    val FETCH_COOLDOWN_MINUTES: IntRange = 0..1440
}

/**
 * The Operator Settings that apply to everything (UC-010), `:core` `settings` (ADR-001 §7): here the fetch period, the
 * one of them this build lets the Operator change (O-T-07). It changes no Transaction Status (UC-010 step 5) and applies
 * from the next Fetch that contacts a Data Provider (UC-001 BR-009, S-T-11).
 */
class GeneralSettings(
    private val store: OperatorSettingsStore,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
) {
    /** Step 1: the fetch period with its default and range, and the other Operator Settings with their values. */
    suspend fun shown(): GeneralSettingsShown {
        val current = store.current()
        return GeneralSettingsShown(
            // UC-010 BR-001: The fetch period is 30 days by default; a stored value is never changed by the default (BR-006).
            fetchPeriod = FetchPeriodShown(current.fetchPeriodDays, OperatorSettings.DEFAULT.fetchPeriodDays, AllowedRanges.FETCH_PERIOD_DAYS),
            values = current,
            journalOn = current.journalOn,
        )
    }

    /** Steps 2-4, 6, A1: the fetch period becomes [days]. */
    suspend fun changeFetchPeriod(days: Int): SettingOutcome {
        val result = coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) {
            // UC-010 BR-002: The fetch period lies between 7 and 180 days, a whole number.
            if (days !in AllowedRanges.FETCH_PERIOD_DAYS) return@run SettingOutcome.OutsideRange(AllowedRanges.FETCH_PERIOD_DAYS)
            val before = store.current().fetchPeriodDays
            if (!storedSafely { store.storeFetchPeriod(days) }) return@run SettingOutcome.NotStored
            journal.record(JournalEventKind.ConfigurationChange, "Fetch period changed from $before to $days days")
            SettingOutcome.Stored(days)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> SettingOutcome.Unavailable(result.reason)
        }
    }
}

/**
 * UC-010 failure postconditions: a value that cannot be stored leaves every Operator Setting with its previous value;
 * the screen says so at the control that changed it (NFR-023). True when [store] completed.
 */
internal suspend fun storedSafely(store: suspend () -> Unit): Boolean =
    try {
        store()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
