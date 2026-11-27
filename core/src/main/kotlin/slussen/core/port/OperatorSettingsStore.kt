package slussen.core.port

/**
 * `OPERATOR_SETTINGS` (entity model): the values the Operator can change inside the app, each with its default. Exactly
 * one record exists; it starts with every default ([OperatorSettings.DEFAULT]). The ranges are checked by the slice
 * that builds UC-010, which changes them.
 */
data class OperatorSettings(
    val uploadWindowDays: Int,
    val fileUploadWindowDays: Int,
    val fetchPeriodDays: Int,
    val defaultWaitHours: Int,
    val requestLimitWarningHours: Int,
    val requestTimeoutSeconds: Int,
    val uploadPlanMinutes: Int,
    val consentRequestMinutes: Int,
    val fetchHistorySize: Int,
    val scheduledFetchesPerDay: Int,
    val transferPairingDays: Int,
    val lonelyTransferGraceDays: Int,
    val outdatedBalanceHours: Int,
    val consentWarningDays: Int,
    val journalOn: Boolean,
    val journalSizeLimitMb: Int,
    val journalWarningPercent: Int,
    val backupWarningPercent: Int,
    val detailedLogging: Boolean,
    val uploadAsCleared: Boolean,
) {
    companion object {
        /**
         * Every default the entity model names for `OPERATOR_SETTINGS`; each is a number the Operator can change. Only a
         * new installation starts with it: a value already stored is never changed by a new default (fetch period 30
         * days since S-T-06, 60 before).
         */
        val DEFAULT: OperatorSettings = OperatorSettings(
            uploadWindowDays = 7,
            fileUploadWindowDays = 60,
            fetchPeriodDays = 30,
            defaultWaitHours = 8,
            requestLimitWarningHours = 24,
            requestTimeoutSeconds = 30,
            uploadPlanMinutes = 10,
            consentRequestMinutes = 15,
            fetchHistorySize = 60,
            scheduledFetchesPerDay = 2,
            transferPairingDays = 5,
            lonelyTransferGraceDays = 6,
            outdatedBalanceHours = 36,
            consentWarningDays = 7,
            journalOn = true,
            journalSizeLimitMb = 50,
            journalWarningPercent = 80,
            backupWarningPercent = 80,
            detailedLogging = false,
            uploadAsCleared = true,
        )
    }
}

/**
 * A whole-number Operator Setting that applies to everything (UC-010 step 1, BR-001, BR-002), with the field of
 * `OPERATOR_SETTINGS` that holds it. The Decision Journal's size limit and warning threshold belong to the Decision
 * Journal's own control (UC-010 A2, A4, BR-003) and are not listed here.
 */
enum class NumberSetting(private val field: (OperatorSettings) -> Int, private val withValue: (OperatorSettings, Int) -> OperatorSettings) {
    UploadWindow({ it.uploadWindowDays }, { s, v -> s.copy(uploadWindowDays = v) }),
    FileUploadWindow({ it.fileUploadWindowDays }, { s, v -> s.copy(fileUploadWindowDays = v) }),
    FetchPeriod({ it.fetchPeriodDays }, { s, v -> s.copy(fetchPeriodDays = v) }),
    FetchSchedule({ it.scheduledFetchesPerDay }, { s, v -> s.copy(scheduledFetchesPerDay = v) }),
    DefaultWait({ it.defaultWaitHours }, { s, v -> s.copy(defaultWaitHours = v) }),
    RequestLimitWarningPause({ it.requestLimitWarningHours }, { s, v -> s.copy(requestLimitWarningHours = v) }),
    RequestTimeout({ it.requestTimeoutSeconds }, { s, v -> s.copy(requestTimeoutSeconds = v) }),
    UploadPlanLifetime({ it.uploadPlanMinutes }, { s, v -> s.copy(uploadPlanMinutes = v) }),
    ConsentRequestLifetime({ it.consentRequestMinutes }, { s, v -> s.copy(consentRequestMinutes = v) }),
    RecentFetchesKept({ it.fetchHistorySize }, { s, v -> s.copy(fetchHistorySize = v) }),
    TransferPairingDays({ it.transferPairingDays }, { s, v -> s.copy(transferPairingDays = v) }),
    LonelyTransferGracePeriod({ it.lonelyTransferGraceDays }, { s, v -> s.copy(lonelyTransferGraceDays = v) }),
    OutdatedBalanceLimit({ it.outdatedBalanceHours }, { s, v -> s.copy(outdatedBalanceHours = v) }),
    ConsentWarningLeadTime({ it.consentWarningDays }, { s, v -> s.copy(consentWarningDays = v) }),
    BackupWarningThreshold({ it.backupWarningPercent }, { s, v -> s.copy(backupWarningPercent = v) }),
    ;

    /** The value of this Operator Setting in [settings]. */
    fun valueIn(settings: OperatorSettings): Int = field(settings)

    /** [settings] with this Operator Setting at [value] and every other one as it is. */
    fun appliedTo(settings: OperatorSettings, value: Int): OperatorSettings = withValue(settings, value)
}

/**
 * An Operator Setting that is on or off (UC-010 A5, A9). Switching the Decision Journal on or off belongs to the Decision
 * Journal's own control (UC-010 postconditions, BR-003) and is not listed here.
 */
enum class SwitchSetting(private val field: (OperatorSettings) -> Boolean, private val withValue: (OperatorSettings, Boolean) -> OperatorSettings) {
    DetailedLogging({ it.detailedLogging }, { s, v -> s.copy(detailedLogging = v) }),
    UploadAsCleared({ it.uploadAsCleared }, { s, v -> s.copy(uploadAsCleared = v) }),
    ;

    fun valueIn(settings: OperatorSettings): Boolean = field(settings)

    fun appliedTo(settings: OperatorSettings, on: Boolean): OperatorSettings = withValue(settings, on)
}

/** The new value of one Operator Setting. */
sealed interface SettingValue {
    /** [settings] with this value and every other Operator Setting as it is. */
    fun appliedTo(settings: OperatorSettings): OperatorSettings

    data class Number(val setting: NumberSetting, val value: Int) : SettingValue {
        override fun appliedTo(settings: OperatorSettings): OperatorSettings = setting.appliedTo(settings, value)
    }

    data class Switch(val setting: SwitchSetting, val on: Boolean) : SettingValue {
        override fun appliedTo(settings: OperatorSettings): OperatorSettings = setting.appliedTo(settings, on)
    }
}

/**
 * What the storage operation of a changed Operator Setting writes with it, in its own transaction (ADR-002 §3 O-13,
 * ADR-007 §3a); the core decides which ([slussen.core.settings.SettingRules]).
 */
enum class SettingFollowUp {
    /** No Transaction Status depends on the setting, and the read period is not longer: only the value is stored. */
    None,

    /** UC-010 step 5: `coordinator_state.comparison_pending` is set, so that the statuses are given again from stored data. */
    StatusesAgain,

    /**
     * UC-003 BR-016, UC-010 step 5: the read period is longer, so `budget_read_state.period_read_owed` is set for every
     * served Budget that has a `change_cursor`, on its existing row only, and no `comparison_pending` of its own.
     */
    PeriodReadOwed,
}

/** One changed Operator Setting with what its storage operation writes with it. */
data class SettingChange(val value: SettingValue, val followUp: SettingFollowUp)

/** Reads and changes the one `OPERATOR_SETTINGS` record (ADR-001 §3, ADR-002 §3 O-13 `SettingsStore`). */
interface OperatorSettingsStore {
    suspend fun current(): OperatorSettings

    /**
     * O-13 `SettingsStore.*`: stores [change] in one transaction, all or nothing: the one value, `state_version` raised,
     * and what [SettingChange.followUp] names (UC-010 steps 4-5, UC-003 BR-016). Throws when it cannot be stored, and
     * then nothing of it is stored.
     */
    suspend fun store(change: SettingChange)
}
