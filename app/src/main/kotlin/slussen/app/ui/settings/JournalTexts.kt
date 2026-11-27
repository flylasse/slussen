package slussen.app.ui.settings

import slussen.app.ui.Texts
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.core.journal.ClearOutcome
import slussen.core.journal.JournalRules
import slussen.core.journal.JournalShown
import slussen.core.journal.SizeLimitOutcome
import slussen.core.journal.Trimming
import slussen.core.port.OperatorSettings
import slussen.core.settings.AllowedRanges
import slussen.core.settings.SettingOutcome
import slussen.core.settings.SwitchOutcome

/**
 * What Settings says of the Decision Journal (UC-010 step 1, A1-A4, BR-003, the postconditions; `settings.html#list-2`,
 * `#journal-limit`, `#clear-journal`, `#journal-near`): its lines, its dialogs and questions, and the answer of each
 * change at its own line, in Slussen's words.
 */
internal object JournalTexts {
    private val defaults = OperatorSettings.DEFAULT
    private val limitRange = AllowedRanges.JOURNAL_SIZE_LIMIT_MB
    private val warningRange = AllowedRanges.JOURNAL_WARNING_PERCENT
    private const val RECORDED = " Recorded in the Decision Journal."

    /** ADR-002 §9: a size in MB of 1,000,000 bytes with one decimal ("12.4 MB"). */
    fun size(bytes: Long): String {
        val tenths = JournalRules.tenthsOfMegabyte(bytes)
        return "${tenths / 10}.${tenths % 10} MB"
    }

    fun tag(control: JournalControlKind): String = when (control) {
        JournalControlKind.Switch -> "journal-switch"
        JournalControlKind.SizeLimit -> "journal-size-limit"
        JournalControlKind.WarningThreshold -> "journal-warning"
        JournalControlKind.Clear -> "journal-clear"
    }

    private fun onOrOff(on: Boolean) = if (on) "on" else "off"

    private fun limit(megabytes: Int) = "$megabytes MB"

    private fun limitWithDefault(megabytes: Int) =
        limit(megabytes) + if (megabytes == defaults.journalSizeLimitMb) ", the default" else " (${defaults.journalSizeLimitMb} by default)"

    private fun warningWithDefault(percent: Int) =
        "$percent%" + if (percent == defaults.journalWarningPercent) ", the default" else " (${defaults.journalWarningPercent}% by default)"

    private fun stillOnOrOff(on: Boolean) = if (on) "Still on, the default." else "Still off (on by default)."

    /** Step 1 (`#list-2`): the four lines of the Decision Journal with the answers and the state of a change on its way. */
    fun lines(shown: JournalShown, answers: Map<JournalControlKind, AnswerUi>, pending: Pair<JournalControlKind, String>?): List<JournalLineUi> {
        fun pendingOf(control: JournalControlKind) = pending?.takeIf { it.first == control }?.second
        val current = shown.sizeBytes?.let { "current size ${size(it)}" } ?: "current size could not be read"
        return listOf(
            JournalLineUi(
                JournalControlKind.Switch, "Decision Journal", "On by default", if (shown.on) "On" else "Off",
                pendingOf(JournalControlKind.Switch), answers[JournalControlKind.Switch], tag(JournalControlKind.Switch),
            ),
            JournalLineUi(
                JournalControlKind.SizeLimit, "Size limit",
                "MB · default ${defaults.journalSizeLimitMb} · ${limitRange.first} to ${limitRange.last} · $current", limit(shown.sizeLimitMb),
                pendingOf(JournalControlKind.SizeLimit), answers[JournalControlKind.SizeLimit], tag(JournalControlKind.SizeLimit),
            ),
            JournalLineUi(
                JournalControlKind.WarningThreshold, "Warning threshold",
                "Share of the size limit · default ${defaults.journalWarningPercent}% · ${warningRange.first}% to ${warningRange.last}%",
                "${shown.warningPercent}%", pendingOf(JournalControlKind.WarningThreshold), answers[JournalControlKind.WarningThreshold],
                tag(JournalControlKind.WarningThreshold),
            ),
            JournalLineUi(
                JournalControlKind.Clear, "Clear Decision Journal…", "Every entry removed; nothing else changes", "",
                pendingOf(JournalControlKind.Clear), answers[JournalControlKind.Clear], tag(JournalControlKind.Clear),
            ),
        )
    }

    /** A4 (`#journal-near`): the size and the limit, and what keeps more entries. */
    fun nearLimit(shown: JournalShown): JournalWarningUi? {
        if (!shown.nearLimit) return null
        val size = shown.sizeBytes ?: return null
        return JournalWarningUi(
            "Decision Journal nearly full",
            "${size(size)} of ${limit(shown.sizeLimitMb)}, the size limit set in Operator Settings. Above it the oldest entries are " +
                "removed; raise the size limit under Decision Journal and logs to keep more of them.",
        )
    }

    // ---- The switch ---------------------------------------------------------------------------------------------------

    fun switchPending(on: Boolean) = if (on) "Switching on…" else "Switching off…"

    fun switched(outcome: SwitchOutcome, wasOn: Boolean): AnswerUi = when (outcome) {
        is SwitchOutcome.Stored -> if (outcome.on) {
            AnswerUi("Saved: on, the default. Recorded as its first entry; Slussen records in the Decision Journal from now on.", AnswerKind.Done)
        } else {
            AnswerUi(
                "Saved: off (on by default). Switching off is its last entry; Slussen records nothing in the Decision Journal from " +
                    "now on, and the entries in it stay.",
                AnswerKind.Done,
            )
        }
        is SwitchOutcome.Unchanged -> AnswerUi("Not changed: ${stillOnOrOff(outcome.on).replaceFirstChar { it.lowercase() }}", AnswerKind.Unchanged)
        SwitchOutcome.NotStored ->
            AnswerUi("Not saved: Slussen could not store it. ${stillOnOrOff(wasOn)}", AnswerKind.Failed, "Tap Decision Journal to try again.")
        is SwitchOutcome.Unavailable -> AnswerUi("Not changed: ${Texts.refusal(outcome.reason)} ${stillOnOrOff(wasOn)}", AnswerKind.Failed)
    }

    // ---- The size limit and the warning threshold ---------------------------------------------------------------------

    fun editorTitle(control: JournalControlKind) = if (control == JournalControlKind.SizeLimit) "Size limit" else "Warning threshold"

    fun fieldName(control: JournalControlKind) =
        if (control == JournalControlKind.SizeLimit) "New value · MB · default ${defaults.journalSizeLimitMb}"
        else "New value · percent · default ${defaults.journalWarningPercent}%"

    fun explanation(control: JournalControlKind, shown: JournalShown): String = if (control == JournalControlKind.SizeLimit) {
        "How much space the Decision Journal may take on this device, an Operator Setting: ${defaults.journalSizeLimitMb} MB by default, " +
            "${limitRange.first} to ${limitRange.last} MB. " +
            (shown.sizeBytes?.let { "It takes ${size(it)} now. " } ?: "Its size could not be read now. ") +
            "Above the limit its oldest entries are removed; a higher limit removes none."
    } else {
        "At what share of its size limit Slussen warns that the Decision Journal is nearly full, an Operator Setting: " +
            "${defaults.journalWarningPercent}% by default, ${warningRange.first}% to ${warningRange.last}%. Changing it removes no entry."
    }

    fun still(control: JournalControlKind, shown: JournalShown): String =
        if (control == JournalControlKind.SizeLimit) "Still ${limitWithDefault(shown.sizeLimitMb)}." else "Still ${warningWithDefault(shown.warningPercent)}."

    /** A1: a value that is not a whole number. */
    fun notANumber(control: JournalControlKind, shown: JournalShown): String =
        if (control == JournalControlKind.SizeLimit) "Enter a whole number of MB, ${limitRange.first} to ${limitRange.last}. ${still(control, shown)}"
        else "Enter a whole number, ${warningRange.first}% to ${warningRange.last}%. ${still(control, shown)}"

    /** A1: outside the allowed range, what to enter and the value that stays. */
    fun outsideRange(control: JournalControlKind, typed: Int, range: IntRange, shown: JournalShown): String =
        if (control == JournalControlKind.SizeLimit) {
            "$typed MB is too ${if (typed > range.last) "large" else "small"}. Enter ${range.first} to ${range.last} MB. ${still(control, shown)}"
        } else {
            "$typed% is too ${if (typed > range.last) "high" else "low"}. Enter ${range.first}% to ${range.last}%. ${still(control, shown)}"
        }

    fun unchanged(control: JournalControlKind, shown: JournalShown): AnswerUi =
        if (control == JournalControlKind.SizeLimit) {
            AnswerUi("Not changed: still ${limitWithDefault(shown.sizeLimitMb)}. No entry was removed.", AnswerKind.Unchanged)
        } else {
            AnswerUi("Not changed: still ${warningWithDefault(shown.warningPercent)}.", AnswerKind.Unchanged)
        }

    /** A2 step 1 (`#journal-limit`): the question before a size limit below the current size is stored. */
    fun lowerQuestion(megabytes: Int, sizeBytes: Long?): JournalConfirmUi = JournalConfirmUi(
        JournalControlKind.SizeLimit, "Lower the size limit?",
        "The new size limit of the Decision Journal, an Operator Setting, is ${limit(megabytes)}. " +
            if (sizeBytes != null) "Its current size, ${size(sizeBytes)}, is above it, so its oldest entries will be removed."
            else "Its current size could not be read; if it is above ${limit(megabytes)}, its oldest entries will be removed.",
        "Lower",
    )

    /** Steps 4, 6, BR-003: the answer of a stored size limit, with what was removed right after it. */
    fun limitStored(outcome: SizeLimitOutcome.Stored, journalOnNow: Boolean): AnswerUi {
        val head = "Saved: ${limitWithDefault(outcome.megabytes)}."
        val body = when (val trimming = outcome.trimming) {
            Trimming.NoneNeeded -> "No entry is removed."
            is Trimming.Done -> {
                val now = trimming.sizeBytes?.let { "it now takes ${size(it)}" } ?: "its size could not be read now"
                when (trimming.removed) {
                    0 -> "No entry was removed: the Decision Journal is within it; " + (trimming.sizeBytes?.let { "it takes ${size(it)}." } ?: "its size could not be read now.")
                    1 -> "The Decision Journal's oldest entry was removed; $now."
                    else -> "The Decision Journal's ${trimming.removed} oldest entries were removed; $now."
                }
            }
            Trimming.Failed -> "The Decision Journal's oldest entries could not be removed now; Slussen removes them after the next entry it records" +
                if (journalOnNow) "." else ", once the Decision Journal is on again, or when the size limit is lowered again."
        }
        return AnswerUi("$head $body" + if (journalOnNow) RECORDED else "", AnswerKind.Done)
    }

    fun limitNotStored(megabytes: Int, shown: JournalShown): AnswerUi = AnswerUi(
        "Not saved: Slussen could not store ${limit(megabytes)}. Still ${limitWithDefault(shown.sizeLimitMb)}; no entry was removed.",
        AnswerKind.Failed,
        "Tap the size limit to try again.",
    )

    fun limitUnavailable(outcome: SizeLimitOutcome.Unavailable, shown: JournalShown): AnswerUi = AnswerUi(
        "Not changed: ${Texts.refusal(outcome.reason)} Still ${limitWithDefault(shown.sizeLimitMb)}; no entry was removed.",
        AnswerKind.Failed,
    )

    /** A4: the answer of a stored warning threshold, with the size at which Slussen now warns. */
    fun warningStored(outcome: SettingOutcome.Stored, shown: JournalShown, journalOnNow: Boolean): AnswerUi {
        val at = size(JournalRules.warningBytes(shown.sizeLimitMb, outcome.value))
        return AnswerUi(
            "Saved: ${warningWithDefault(outcome.value)}. Slussen warns when the Decision Journal reaches $at of its " +
                "${limit(shown.sizeLimitMb)}; no entry is removed." + if (journalOnNow) RECORDED else "",
            AnswerKind.Done,
        )
    }

    fun warningNotStored(percent: Int, shown: JournalShown): AnswerUi = AnswerUi(
        "Not saved: Slussen could not store $percent%. ${still(JournalControlKind.WarningThreshold, shown)}",
        AnswerKind.Failed,
        "Tap the warning threshold to try again.",
    )

    // ---- A3: clearing ------------------------------------------------------------------------------------------------

    /** A3 step 1 (`#clear-journal`): the question before the Decision Journal is cleared. */
    fun clearQuestion(on: Boolean): JournalConfirmUi = JournalConfirmUi(
        JournalControlKind.Clear, "Clear the Decision Journal?",
        "Every entry is removed and the space it takes is freed. " +
            (if (on) "The clearing is recorded as its first new entry. " else "Nothing is recorded of the clearing while the Decision Journal is off. ") +
            "Nothing else changes: no Transaction Status, Upload Plan or Upload depends on the Decision Journal.",
        "Clear",
    )

    fun cleared(outcome: ClearOutcome): AnswerUi = when (outcome) {
        is ClearOutcome.Cleared -> AnswerUi(
            "Cleared: every entry removed and its space freed" + (outcome.sizeBytes?.let { "; it now takes ${size(it)}" } ?: "") + ". " +
                (if (outcome.recorded) "The clearing is recorded as its first new entry; " else "Nothing is recorded of it while the Decision Journal is off; ") +
                "nothing else changed.",
            AnswerKind.Done,
        )
        ClearOutcome.NotCleared ->
            AnswerUi("Not cleared: Slussen could not clear the Decision Journal; every entry stays.", AnswerKind.Failed, "Tap Clear Decision Journal… to try again.")
        is ClearOutcome.Unavailable -> AnswerUi("Not cleared: ${Texts.refusal(outcome.reason)} Every entry stays.", AnswerKind.Failed)
    }

    val clearCancelled = AnswerUi("Not cleared: every entry stays.", AnswerKind.Unchanged)
}
