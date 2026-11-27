package slussen.app.ui.settings

import slussen.core.port.NumberSetting
import slussen.core.settings.NumberSettingShown
import slussen.core.settings.SettingEffect

/**
 * What Settings says of each whole-number Operator Setting (UC-010 step 1, A1, A6; `settings.html#list`, `#list-2`,
 * `#out-of-range`, `#saved`, `#saved-read-owed`, `#fetch-schedule`): its name and hint as the mockup writes them, its
 * value with its unit, and what a change does from step 4 on.
 */
internal object SettingTexts {
    /** The line's title. */
    fun name(setting: NumberSetting): String = when (setting) {
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

    /** The setting as a sentence names it, for "Tap the … to try again". */
    fun inText(setting: NumberSetting): String = when (setting) {
        NumberSetting.UploadWindow, NumberSetting.FetchSchedule, NumberSetting.RequestLimitWarningPause, NumberSetting.UploadPlanLifetime,
        NumberSetting.ConsentRequestLifetime, NumberSetting.LonelyTransferGracePeriod,
        -> name(setting)
        else -> name(setting).replaceFirstChar { it.lowercase() }
    }

    /** The test tag of the line; the fetch period keeps the one its screen test taps. */
    fun tag(setting: NumberSetting): String = if (setting == NumberSetting.FetchPeriod) "fetch-period" else "setting-${setting.name}"

    private enum class Measure { Days, Hours, Seconds, Minutes, Count, Percent, Schedule }

    private fun unitOf(setting: NumberSetting): Measure = when (setting) {
        NumberSetting.UploadWindow, NumberSetting.FileUploadWindow, NumberSetting.FetchPeriod, NumberSetting.LonelyTransferGracePeriod,
        NumberSetting.ConsentWarningLeadTime,
        -> Measure.Days
        NumberSetting.DefaultWait, NumberSetting.RequestLimitWarningPause, NumberSetting.OutdatedBalanceLimit -> Measure.Hours
        NumberSetting.RequestTimeout -> Measure.Seconds
        NumberSetting.UploadPlanLifetime, NumberSetting.ConsentRequestLifetime -> Measure.Minutes
        NumberSetting.RecentFetchesKept, NumberSetting.TransferPairingDays -> Measure.Count
        NumberSetting.BackupWarningThreshold -> Measure.Percent
        NumberSetting.FetchSchedule -> Measure.Schedule
    }

    /** The value at the right of the line ("30 days", "8 h", "80%", "2", "Off"). */
    fun shortValue(setting: NumberSetting, value: Int): String = when (unitOf(setting)) {
        Measure.Days -> "$value days"
        Measure.Hours -> "$value h"
        Measure.Seconds -> "$value s"
        Measure.Minutes -> "$value min"
        Measure.Count -> "$value"
        Measure.Percent -> "$value%"
        Measure.Schedule -> if (value == 0) "Off" else "$value"
    }

    /** The value in an answer ("14 days", "12 hours", "1 Scheduled Fetch in any 24 hours"). */
    fun value(setting: NumberSetting, value: Int): String = when (unitOf(setting)) {
        Measure.Days -> "$value days"
        Measure.Hours -> "$value hours"
        Measure.Seconds -> "$value seconds"
        Measure.Minutes -> "$value minutes"
        Measure.Count -> if (setting == NumberSetting.TransferPairingDays) "$value days" else "$value"
        Measure.Percent -> "$value%"
        Measure.Schedule -> if (value == 0) "0 (off)" else if (value == 1) "1 Scheduled Fetch in any 24 hours" else "$value Scheduled Fetches in any 24 hours"
    }

    /** The range as the hint and a refusal write it ("7 to 180", "50% to 95%", "0 to 2 (0 is off)"). */
    fun range(setting: NumberSetting, range: IntRange): String = when (unitOf(setting)) {
        Measure.Percent -> "${range.first}% to ${range.last}%"
        Measure.Schedule -> "${range.first} to ${range.last} (0 is off)"
        else -> "${range.first} to ${range.last}"
    }

    /** The unit a refusal names after the range ("Enter 7 to 180 days."). */
    private fun rangeUnit(setting: NumberSetting): String = when (unitOf(setting)) {
        Measure.Days -> " days"
        Measure.Hours -> " hours"
        Measure.Seconds -> " seconds"
        Measure.Minutes -> " minutes"
        Measure.Count -> if (setting == NumberSetting.TransferPairingDays) " days" else ""
        Measure.Percent, Measure.Schedule -> ""
    }

    /** The field of the dialog ("New value · days · default 30", "New value · default 60"). */
    fun fieldName(shown: NumberSettingShown): String {
        val unit = when (unitOf(shown.setting)) {
            Measure.Days -> "days · "
            Measure.Hours -> "hours · "
            Measure.Seconds -> "seconds · "
            Measure.Minutes -> "minutes · "
            Measure.Percent -> "percent · "
            Measure.Count -> if (shown.setting == NumberSetting.TransferPairingDays) "days · " else ""
            Measure.Schedule -> "per 24 hours · "
        }
        return "New value · ${unit}default ${bare(shown.setting, shown.defaultValue)}"
    }

    /** The default in a hint and an answer, without its unit except a share ("default 30", "80% by default"). */
    fun bare(setting: NumberSetting, value: Int): String = if (unitOf(setting) == Measure.Percent) "$value%" else "$value"

    /** The hint under the name (`settings.html#list`, `#list-2`). */
    fun hint(shown: NumberSettingShown): String {
        val setting = shown.setting
        val what = when (setting) {
            NumberSetting.UploadWindow -> "Days back · "
            NumberSetting.FileUploadWindow -> "Days back, every File Format · "
            NumberSetting.FetchPeriod -> "Days back a Fetch asks for · "
            NumberSetting.FetchSchedule -> "Per 24 hours · "
            NumberSetting.DefaultWait, NumberSetting.RequestLimitWarningPause, NumberSetting.OutdatedBalanceLimit -> "Hours · "
            NumberSetting.RequestTimeout -> "Seconds · "
            NumberSetting.UploadPlanLifetime, NumberSetting.ConsentRequestLifetime -> "Minutes · "
            NumberSetting.RecentFetchesKept -> ""
            NumberSetting.TransferPairingDays -> "Days between two Transfer Legs · "
            NumberSetting.LonelyTransferGracePeriod -> "Days · "
            NumberSetting.ConsentWarningLeadTime -> "Days before expiry · "
            NumberSetting.BackupWarningThreshold -> "Share of Android's backup size limit · "
        }
        val default = if (what.isEmpty()) "Default" else "default"
        return "$what$default ${bare(setting, shown.defaultValue)} · ${range(setting, shown.range)}"
    }

    fun still(shown: NumberSettingShown): String =
        "Still ${value(shown.setting, shown.value)}" + if (shown.value == shown.defaultValue) ", the default." else "."

    /** A1 (`#out-of-range`): what is wrong, what to enter, and the value that stays. */
    fun outsideRange(shown: NumberSettingShown, typed: Int, range: IntRange): String {
        val setting = shown.setting
        val tooWhat = when (unitOf(setting)) {
            Measure.Percent -> if (typed > range.last) "high" else "low"
            else -> if (typed > range.last) "many" else "few"
        }
        val typedText = when (unitOf(setting)) {
            Measure.Schedule, Measure.Count -> if (setting == NumberSetting.TransferPairingDays) "$typed days" else "$typed"
            else -> value(setting, typed)
        }
        return "$typedText is too $tooWhat. Enter ${range(setting, range)}${rangeUnit(setting)}. ${still(shown)}"
    }

    /** A1: a value that is not a whole number. */
    fun notANumber(shown: NumberSettingShown): String {
        val of = when (unitOf(shown.setting)) {
            Measure.Days -> "a whole number of days, "
            Measure.Hours -> "a whole number of hours, "
            Measure.Seconds -> "a whole number of seconds, "
            Measure.Minutes -> "a whole number of minutes, "
            Measure.Count -> if (shown.setting == NumberSetting.TransferPairingDays) "a whole number of days, " else "a whole number, "
            Measure.Percent -> "a whole number, "
            Measure.Schedule -> "a whole number, "
        }
        return "Enter $of${range(shown.setting, shown.range)}. ${still(shown)}"
    }

    /** A6 (`#fetch-schedule`): what the Fetch Schedule of [value] means. */
    fun fetchSchedule(value: Int?, default: Int): String {
        val will = when (value) {
            null -> "Slussen makes as many Scheduled Fetches in any 24 hours as the Fetch Schedule says"
            0 -> "Slussen will make no Scheduled Fetch"
            1 -> "Slussen will make one Scheduled Fetch in any 24 hours"
            else -> "Slussen will make $value Scheduled Fetches in any 24 hours"
        }
        return "$will (the Fetch Schedule, $default by default, an Operator Setting). A Scheduled Fetch only fetches and compares; it never uploads."
    }

    /** Step 2: what the dialog says the setting is and what a change does. */
    fun explanation(shown: NumberSettingShown): String {
        val setting = shown.setting
        val defaults = "${value(setting, shown.defaultValue)} by default, ${range(setting, shown.range)}${rangeUnit(setting)}"
        val statusesAgain = "Every stored Bank Transaction gets its Transaction Status again under the new value, from stored data."
        return when (setting) {
            NumberSetting.FetchPeriod ->
                "How many days back a Fetch asks each Data Provider for Bank Transactions, an Operator Setting: " +
                    "${shown.defaultValue} days by default, ${shown.range.first} to ${shown.range.last}. The new value is asked for " +
                    "from the next Fetch that contacts a Data Provider. Bank Transactions already stored stay. Start Dates and the " +
                    "Upload Window do not change, so which Bank Transactions could be uploaded stays the same."
            NumberSetting.FetchSchedule -> fetchSchedule(shown.value, shown.defaultValue)
            NumberSetting.UploadWindow ->
                "How many days back a Bank Transaction a Data Provider fetched could be uploaded, an Operator Setting: $defaults. $statusesAgain"
            NumberSetting.FileUploadWindow ->
                "How many days back a Bank Transaction from a Transaction File could be uploaded, an Operator Setting: $defaults. $statusesAgain"
            NumberSetting.TransferPairingDays ->
                "How many days apart two Transfer Legs can be and still be paired, an Operator Setting: $defaults. $statusesAgain"
            NumberSetting.LonelyTransferGracePeriod ->
                "How many days a Transfer Leg waits for its other side before it is a Lonely Transfer, an Operator Setting: $defaults. $statusesAgain"
            NumberSetting.DefaultWait ->
                "How long Slussen waits before it asks a Data Provider again when the Data Provider names no time, an Operator Setting: $defaults."
            NumberSetting.RequestLimitWarningPause ->
                "How long Slussen pauses after warning that the Request Limit is near, an Operator Setting: $defaults."
            NumberSetting.RequestTimeout -> "How long Slussen waits for a service to answer a request, an Operator Setting: $defaults."
            NumberSetting.UploadPlanLifetime -> "How long an Upload Plan waits for your confirmation, an Operator Setting: $defaults."
            NumberSetting.ConsentRequestLifetime -> "How long a Consent Request waits for the Bank's answer, an Operator Setting: $defaults."
            NumberSetting.RecentFetchesKept -> "How many of the most recent Fetches Recent Fetches keeps, an Operator Setting: $defaults."
            NumberSetting.OutdatedBalanceLimit ->
                "How old a Bank's balance can be before its difference is hidden as outdated, an Operator Setting: $defaults."
            NumberSetting.ConsentWarningLeadTime ->
                "How many days before a Bank Consent expires Slussen warns, an Operator Setting: $defaults."
            NumberSetting.BackupWarningThreshold ->
                "At what share of the backup size limit Android sets Slussen warns, an Operator Setting: $defaults."
        } + applies(setting, null)?.let { " $it" }.orEmpty()
    }

    /** Step 4: from when the new value applies; null for one that a Transaction Status depends on (step 5 says it). */
    private fun applies(setting: NumberSetting, value: Int?): String? = when (setting) {
        NumberSetting.DefaultWait, NumberSetting.RequestLimitWarningPause ->
            "It applies from the next Fetch; a wait Slussen stored earlier keeps the time it was stored with."
        NumberSetting.RequestTimeout -> "It applies from the next request."
        NumberSetting.UploadPlanLifetime -> "It applies to Upload Plans prepared from now on."
        NumberSetting.ConsentRequestLifetime -> "It applies to Consent Requests made from now on; one made earlier keeps its expiry."
        NumberSetting.RecentFetchesKept ->
            "Recent Fetches shows at most ${value ?: "that many"} from now on; older ones are removed at the next Fetch, and none removed comes back."
        NumberSetting.OutdatedBalanceLimit, NumberSetting.ConsentWarningLeadTime, NumberSetting.BackupWarningThreshold ->
            "It applies from the next time Slussen shows its status."
        else -> null
    }

    /** Steps 4-6 (`#saved`, `#saved-read-owed`, `#fetch-period-saved`): the answer of a stored value at its line. */
    fun saved(shown: NumberSettingShown, effect: SettingEffect, journalOn: Boolean, budgetAppName: String): String {
        val setting = shown.setting
        val value = shown.value
        val head = "Saved: ${value(setting, value)}" +
            if (value == shown.defaultValue) ", the default." else " (${bare(setting, shown.defaultValue)} by default)."
        val body = when (setting) {
            NumberSetting.FetchPeriod -> when (effect) {
                is SettingEffect.PeriodReadOwed -> "Asked for from the next Fetch that contacts a Data Provider; stored Bank Transactions stay. " +
                    readOwed(effect, budgetAppName)
                else -> "Asked for from the next Fetch that contacts a Data Provider; stored Bank Transactions stay; no Transaction Status changes."
            }
            NumberSetting.FetchSchedule -> fetchSchedule(value, shown.defaultValue)
            else -> when (effect) {
                SettingEffect.None -> (applies(setting, value)?.let { "$it " }.orEmpty()) + "No Transaction Status changes."
                is SettingEffect.StatusesGiven -> statusesGiven(effect.waitingForRead, budgetAppName)
                SettingEffect.StatusesPending ->
                    "The Transaction Statuses could not be given again yet; no Upload Plan is prepared until they are, at the next " +
                        "start or the next read of $budgetAppName."
                is SettingEffect.PeriodReadOwed -> readOwed(effect, budgetAppName)
            }
        }
        return "$head $body" + if (journalOn) " Recorded in the Decision Journal." else ""
    }

    /** UC-010 step 5 (`#saved`): the statuses given again, naming every Budget left out because it owes a read (UC-003 BR-016). */
    private fun statusesGiven(waiting: List<String>?, budgetAppName: String): String {
        val given = "given again from stored data, without contacting $budgetAppName or any Source"
        return when {
            waiting == null -> "Transaction Statuses $given, except those of each Budget that owes a read of $budgetAppName, which wait for that read."
            waiting.isEmpty() -> "Every Transaction Status $given."
            waiting.size == 1 -> "Transaction Statuses $given, except those of ${waiting.single()}, which wait for the read of $budgetAppName it owes."
            else -> "Transaction Statuses $given, except those of ${waiting.dropLast(1).joinToString(", ")} and ${waiting.last()}, which wait " +
                "for the read of $budgetAppName each owes."
        }
    }

    /** UC-010 step 5, UC-003 BR-016 (`#saved-read-owed`): which Budgets wait for a read of the Budget App, and what it gives. */
    private fun readOwed(effect: SettingEffect.PeriodReadOwed, budgetAppName: String): String {
        val budgets = effect.budgets
        val who = when {
            budgets == null -> "each Budget read before waits"
            budgets.size == 1 -> "${budgets.single()} waits"
            else -> budgets.dropLast(1).joinToString(", ") + " and ${budgets.last()} each wait"
        }
        val single = budgets?.size == 1
        return "Slussen's copy of $budgetAppName does not reach back that far: $who for a read of $budgetAppName, which gives " +
            (if (single) "its" else "their") + " Transaction Statuses; until then " + (if (single) "it keeps its" else "they keep their") +
            " statuses and nothing is uploaded from " + (if (single) "it." else "them.")
    }
}
