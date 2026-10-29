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

/** Reads the one `OPERATOR_SETTINGS` record (ADR-001 §3). */
interface OperatorSettingsStore {
    suspend fun current(): OperatorSettings
}
