package slussen.core.settings

import kotlinx.coroutines.CancellationException
import slussen.core.comparison.Comparison
import slussen.core.comparison.ComparisonRules
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.NumberSetting
import slussen.core.port.OperatorSettings
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.core.port.SwitchSetting

/** The fetch period as Settings shows it: its value, its default and its allowed range (UC-010 step 1, BR-001, BR-002). */
data class FetchPeriodShown(val days: Int, val defaultDays: Int, val range: IntRange)

/** One whole-number Operator Setting as Settings shows it: its value, its default and its allowed range (UC-010 step 1, BR-001, BR-002). */
data class NumberSettingShown(val setting: NumberSetting, val value: Int, val defaultValue: Int, val range: IntRange)

/**
 * The Operator Settings that apply to everything, as Settings shows them (UC-010 step 1): the fetch period, each
 * whole-number Operator Setting with its default and range ([numbers]), and every value as stored ([values]).
 * [journalOn] says whether a change is recorded in the Decision Journal.
 */
data class GeneralSettingsShown(
    val fetchPeriod: FetchPeriodShown,
    val values: OperatorSettings,
    val journalOn: Boolean,
    val numbers: List<NumberSettingShown> = emptyList(),
) {
    fun number(setting: NumberSetting): NumberSettingShown = numbers.single { it.setting == setting }
}

/** The allowed ranges of the Operator Settings (UC-010 BR-002); every value is a whole number. */
object AllowedRanges {
    // UC-010 BR-002: The fetch period lies between 7 and 180 days.
    val FETCH_PERIOD_DAYS: IntRange = 7..180

    // UC-010 BR-002: The Fetch Cooldown of each Data Provider lies between 0 and 1440 minutes; 0 switches it off.
    val FETCH_COOLDOWN_MINUTES: IntRange = 0..1440

    // UC-010 BR-002: The Decision Journal size limit lies between 10 and 500 MB.
    val JOURNAL_SIZE_LIMIT_MB: IntRange = 10..500

    // UC-010 BR-002: The Decision Journal's warning threshold lies between 50% and 95% of its size limit.
    val JOURNAL_WARNING_PERCENT: IntRange = 50..95

    /** The allowed range of [setting]. */
    fun of(setting: NumberSetting): IntRange = when (setting) {
        // UC-010 BR-002: The Upload Window lies between 1 and 60 days.
        NumberSetting.UploadWindow -> 1..60
        // UC-010 BR-002: The file upload window lies between 7 and 365 days.
        NumberSetting.FileUploadWindow -> 7..365
        NumberSetting.FetchPeriod -> FETCH_PERIOD_DAYS
        // UC-010 BR-002: The Fetch Schedule lies between 0 and 2 Scheduled Fetches in any 24 hours; 0 switches them off.
        NumberSetting.FetchSchedule -> 0..2
        // UC-010 BR-002: The default wait lies between 1 and 48 hours.
        NumberSetting.DefaultWait -> 1..48
        // UC-010 BR-002: The Request Limit warning pause lies between 1 and 72 hours.
        NumberSetting.RequestLimitWarningPause -> 1..72
        // UC-010 BR-002: The request timeout lies between 10 and 120 seconds.
        NumberSetting.RequestTimeout -> 10..120
        // UC-010 BR-002: The Upload Plan lifetime lies between 1 and 60 minutes.
        NumberSetting.UploadPlanLifetime -> 1..60
        // UC-010 BR-002: The Consent Request lifetime lies between 5 and 30 minutes.
        NumberSetting.ConsentRequestLifetime -> 5..30
        // UC-010 BR-002: The number of recent Fetches kept lies between 10 and 500.
        NumberSetting.RecentFetchesKept -> 10..500
        // UC-010 BR-002: The transfer pairing days and the Lonely Transfer grace period lie between 0 and 30 days.
        NumberSetting.TransferPairingDays, NumberSetting.LonelyTransferGracePeriod -> 0..30
        // UC-010 BR-002: The outdated-balance limit lies between 1 and 720 hours.
        NumberSetting.OutdatedBalanceLimit -> 1..720
        // UC-010 BR-002: The consent warning lead time lies between 1 and 60 days.
        NumberSetting.ConsentWarningLeadTime -> 1..60
        // UC-010 BR-002: The backup warning threshold lies between 50% and 95% of the backup size limit.
        NumberSetting.BackupWarningThreshold -> 50..95
    }
}

/** What a change of an Operator Setting did besides storing it (UC-010 step 5). */
sealed interface SettingEffect {
    /** No Transaction Status depends on the setting, and the read period of the Budget App is not longer: none changes. */
    data object None : SettingEffect

    /**
     * Step 5: every stored Bank Transaction of a Budget read before has its Transaction Status again under the new value,
     * except those of the Budgets of [waitingForRead] (by name), which owe a read of their read period and keep their
     * statuses until it (UC-003 BR-016). Empty when none was left out; null when which ones could not be read back.
     */
    data class StatusesGiven(val waitingForRead: List<String>? = emptyList()) : SettingEffect

    /**
     * Step 5: the value is stored, but the statuses could not be given again now; no Upload Plan is prepared until they
     * are, at the next start or with the next Comparison (ADR-007 §3a).
     */
    data object StatusesPending : SettingEffect

    /**
     * Step 5, UC-003 BR-016: the read period is longer, so each of [budgets] (the served Budgets read before, by name,
     * never empty) owes a read of the Budget App; their statuses stay as the latest Comparison left them until that read.
     * Null when which ones could not be read back. When no Budget has been read yet, none owes a read and the effect is
     * [None]: each gets the longer period at its first read.
     */
    data class PeriodReadOwed(val budgets: List<String>?) : SettingEffect
}

/** What changing an Operator Setting that is on or off came to (UC-010 A5, A9). */
sealed interface SwitchOutcome {
    /** Step 4, 6: stored, applied from now on, and recorded in the Decision Journal. */
    data class Stored(val on: Boolean) : SwitchOutcome

    /** Failure postconditions: the value could not be stored; the previous one is kept and nothing is recorded. */
    data object NotStored : SwitchOutcome

    /** It is [on] already: nothing is stored, raised or recorded, and it is answered as unchanged. */
    data class Unchanged(val on: Boolean) : SwitchOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : SwitchOutcome
}

/** The rules of UC-010 that decide what a changed Operator Setting is followed by. Pure. */
object SettingRules {
    /**
     * UC-010 step 5: the Operator Settings of this kind that a Transaction Status depends on; the Card Payment and Bank
     * Transfer Descriptions are lists, edited apart (A7, A8).
     */
    private val STATUSES_DEPEND_ON = setOf(
        NumberSetting.UploadWindow,
        NumberSetting.FileUploadWindow,
        NumberSetting.TransferPairingDays,
        NumberSetting.LonelyTransferGracePeriod,
    )

    /** Whether a Transaction Status depends on [setting] (step 5). */
    fun statusesDependOn(setting: NumberSetting): Boolean = setting in STATUSES_DEPEND_ON

    // UC-003 BR-016: After the Operator makes the longest of the fetch period, the Upload Window and the file upload window longer, every served Budget read before is read again over the read period under the new values, and until that read no conclusion from stored data changes its Transaction Statuses.
    /**
     * What the storage operation of the change from [before] to [value] writes with it (ADR-002 §3 O-13, UC-010 step 5):
     * a read of the period owed when the read period becomes longer, also by a setting a Transaction Status depends on
     * (no statuses are given again then); otherwise the statuses again when one depends on the setting and its value
     * changes; otherwise nothing.
     */
    fun followUpOf(value: SettingValue, before: OperatorSettings): SettingFollowUp {
        val after = value.appliedTo(before)
        return when {
            ComparisonRules.readPeriodDays(after) > ComparisonRules.readPeriodDays(before) -> SettingFollowUp.PeriodReadOwed
            value is SettingValue.Number && statusesDependOn(value.setting) -> SettingFollowUp.StatusesAgain
            else -> SettingFollowUp.None
        }
    }
}

/**
 * The Operator Settings that apply to everything (UC-010), `:core` `settings` (ADR-001 §7): every whole-number one and
 * Detailed Logging, on Settings, and Upload as Cleared, on the Budget App's page. A change is checked against its range
 * (BR-002, A1), stored with what follows it in one storage operation (O-13), recorded in the Decision Journal with the
 * value before and after (step 6, UC-011 BR-001), and, when a Transaction Status depends on it, followed by the statuses
 * given again from stored data through the one operation for it, [Comparison.statusesAgainAfter] (step 5). [applied]
 * hears every value stored, so that what reads a setting outside the core (Detailed Logging, ADR-006) follows it.
 */
class GeneralSettings(
    private val store: OperatorSettingsStore,
    private val comparison: Comparison,
    private val connections: ConnectionStore,
    private val copies: BudgetAppCopyStore,
    private val journal: DecisionJournal,
    private val applied: (OperatorSettings) -> Unit = {},
) {
    /** Step 1: every whole-number Operator Setting with its default and range, and every value as stored. */
    suspend fun shown(): GeneralSettingsShown {
        val current = store.current()
        return GeneralSettingsShown(
            // UC-010 BR-001: The fetch period is 30 days by default; a stored value is never changed by the default (BR-006).
            fetchPeriod = FetchPeriodShown(current.fetchPeriodDays, OperatorSettings.DEFAULT.fetchPeriodDays, AllowedRanges.FETCH_PERIOD_DAYS),
            values = current,
            journalOn = current.journalOn,
            numbers = NumberSetting.entries.map { setting ->
                // UC-010 BR-001: Each Operator Setting has its default, the value of a new installation only (BR-006).
                NumberSettingShown(setting, setting.valueIn(current), setting.valueIn(OperatorSettings.DEFAULT), AllowedRanges.of(setting))
            },
        )
    }

    /** Steps 2-6, A1 for the fetch period. */
    suspend fun changeFetchPeriod(days: Int): SettingOutcome = change(NumberSetting.FetchPeriod, days)

    /** Steps 2-6, A1, A6: [setting] becomes [value]. */
    suspend fun change(setting: NumberSetting, value: Int): SettingOutcome {
        val range = AllowedRanges.of(setting)
        val result = comparison.statusesAgainAfter(OperationKind.OperatorSettingChange) {
            // UC-010 BR-002: Each Operator Setting lies within its allowed range, a whole number; A1 refuses another value and keeps the previous one.
            if (value !in range) return@statusesAgainAfter SettingOutcome.OutsideRange(range) to SettingFollowUp.None
            when (
                val stored = storeAndRecord(SettingValue.Number(setting, value)) { before ->
                    "${SettingTexts.journalName(setting)} changed from ${SettingTexts.bare(setting, setting.valueIn(before))} to " +
                        SettingTexts.withUnit(setting, value)
                }
            ) {
                is Storing.Stored -> SettingOutcome.Stored(value) to stored.followUp
                Storing.Unchanged -> SettingOutcome.Unchanged(value) to SettingFollowUp.None
                Storing.NotStored -> SettingOutcome.NotStored to SettingFollowUp.None
            }
        }
        return when (result) {
            is OperationResult.Refused -> SettingOutcome.Unavailable(result.reason)
            is OperationResult.Ran -> {
                val (outcome, followUp) = result.value.change
                if (outcome !is SettingOutcome.Stored) return outcome
                outcome.copy(effect = effectOf(followUp, result.value.statusesGiven))
            }
        }
    }

    /** Steps 2, 4, 6, A5, A9: [setting] is switched [on] or off. No Transaction Status depends on it (step 5). */
    suspend fun switch(setting: SwitchSetting, on: Boolean): SwitchOutcome {
        val result = comparison.statusesAgainAfter(OperationKind.OperatorSettingChange) {
            when (storeAndRecord(SettingValue.Switch(setting, on)) { "${SettingTexts.journalName(setting)} switched ${if (on) "on" else "off"}" }) {
                is Storing.Stored -> SwitchOutcome.Stored(on)
                Storing.Unchanged -> SwitchOutcome.Unchanged(on)
                Storing.NotStored -> SwitchOutcome.NotStored
            }
        }
        return when (result) {
            is OperationResult.Ran -> result.value.change
            is OperationResult.Refused -> SwitchOutcome.Unavailable(result.reason)
        }
    }

    /** What [storeAndRecord] did. */
    private sealed interface Storing {
        data class Stored(val followUp: SettingFollowUp) : Storing

        /** The value is the one stored: nothing is stored, raised or recorded. */
        data object Unchanged : Storing

        data object NotStored : Storing
    }

    /**
     * Steps 4 and 6 within the operation: stores [value] with what follows it (O-13) and records the change with the
     * value before and after. A value equal to the stored one is not a change: nothing is stored or recorded; nor when it
     * could not be stored.
     */
    private suspend fun storeAndRecord(value: SettingValue, details: (OperatorSettings) -> String): Storing {
        val before = readSafely { store.current() } ?: return Storing.NotStored
        if (value.appliedTo(before) == before) return Storing.Unchanged
        val followUp = SettingRules.followUpOf(value, before)
        if (!storedSafely { store.store(SettingChange(value, followUp)) }) return Storing.NotStored
        applied(value.appliedTo(before))
        // UC-011 BR-001: Every change of an Operator Setting is recorded, with the value before and after.
        journal.record(JournalEventKind.ConfigurationChange, details(before))
        return Storing.Stored(followUp)
    }

    private suspend fun effectOf(followUp: SettingFollowUp, statusesGiven: Boolean): SettingEffect = when (followUp) {
        SettingFollowUp.None -> SettingEffect.None
        SettingFollowUp.StatusesAgain ->
            if (statusesGiven) SettingEffect.StatusesGiven(readSafely { budgetsOwingARead() }) else SettingEffect.StatusesPending
        SettingFollowUp.PeriodReadOwed -> when (val owing = readSafely { budgetsOwingARead() }) {
            null -> SettingEffect.PeriodReadOwed(null)
            else -> if (owing.isEmpty()) SettingEffect.None else SettingEffect.PeriodReadOwed(owing)
        }
    }

    private suspend fun budgetsOwingARead(): List<String> = budgetsOwingARead(connections, copies)
}

/** UC-003 BR-016: the served Budgets that owe a read of their read period, by name. */
internal suspend fun budgetsOwingARead(connections: ConnectionStore, copies: BudgetAppCopyStore): List<String> =
    connections.budgets().filter { it.served && copies.periodReadOwed(it.id) }.map { it.name }

/** The names and units of the Operator Settings as the Decision Journal records them (UC-011 BR-001). */
object SettingTexts {
    fun journalName(setting: NumberSetting): String = when (setting) {
        NumberSetting.UploadWindow -> "Upload Window"
        NumberSetting.FileUploadWindow -> "File upload window"
        NumberSetting.FetchPeriod -> "Fetch period"
        NumberSetting.FetchSchedule -> "Fetch Schedule"
        NumberSetting.DefaultWait -> "Default wait"
        NumberSetting.RequestLimitWarningPause -> "Request Limit warning pause"
        NumberSetting.RequestTimeout -> "Request timeout"
        NumberSetting.UploadPlanLifetime -> "Upload Plan lifetime"
        NumberSetting.ConsentRequestLifetime -> "Consent Request lifetime"
        NumberSetting.RecentFetchesKept -> "Number of recent Fetches kept"
        NumberSetting.TransferPairingDays -> "Transfer pairing days"
        NumberSetting.LonelyTransferGracePeriod -> "Lonely Transfer grace period"
        NumberSetting.OutdatedBalanceLimit -> "Outdated-balance limit"
        NumberSetting.ConsentWarningLeadTime -> "Consent warning lead time"
        NumberSetting.BackupWarningThreshold -> "Backup warning threshold"
    }

    fun journalName(setting: SwitchSetting): String = when (setting) {
        SwitchSetting.DetailedLogging -> "Detailed Logging"
        SwitchSetting.UploadAsCleared -> "Upload as Cleared"
    }

    /** [value] of [setting] without its unit, except a share in percent ("7", "80%"). */
    fun bare(setting: NumberSetting, value: Int): String = if (setting == NumberSetting.BackupWarningThreshold) "$value%" else "$value"

    /** [value] of [setting] with its unit, as the journal records it ("7 days", "8 hours", "80%"). */
    fun withUnit(setting: NumberSetting, value: Int): String = when (setting) {
        NumberSetting.UploadWindow, NumberSetting.FileUploadWindow, NumberSetting.FetchPeriod, NumberSetting.TransferPairingDays,
        NumberSetting.LonelyTransferGracePeriod, NumberSetting.ConsentWarningLeadTime,
        -> "$value days"
        NumberSetting.DefaultWait, NumberSetting.RequestLimitWarningPause, NumberSetting.OutdatedBalanceLimit -> "$value hours"
        NumberSetting.RequestTimeout -> "$value seconds"
        NumberSetting.UploadPlanLifetime, NumberSetting.ConsentRequestLifetime -> "$value minutes"
        NumberSetting.FetchSchedule -> "$value Scheduled Fetches in any 24 hours"
        NumberSetting.RecentFetchesKept -> "$value"
        NumberSetting.BackupWarningThreshold -> "$value%"
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

/** What [read] returned, or null when the storage could not be read. */
internal suspend fun <T : Any> readSafely(read: suspend () -> T): T? =
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
