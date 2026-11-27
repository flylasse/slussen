package slussen.core.journal

import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.port.DecisionJournalControl
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettings
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.core.port.StartedBy
import slussen.core.settings.AllowedRanges
import slussen.core.settings.SettingOutcome
import slussen.core.settings.SwitchOutcome
import slussen.core.settings.readSafely
import slussen.core.settings.storedSafely

/**
 * The Decision Journal as Settings shows it (UC-010 step 1, A4): on or off, its size limit in MB, its warning threshold
 * in percent of that limit, and its size in bytes ([sizeBytes], null when the journal's database could not say it).
 */
data class JournalShown(val on: Boolean, val sizeLimitMb: Int, val warningPercent: Int, val sizeBytes: Long?) {
    /** A4: the size has reached the warning threshold of the size limit; never while the size is not known. */
    val nearLimit: Boolean get() = sizeBytes != null && sizeBytes >= JournalRules.warningBytes(sizeLimitMb, warningPercent)
}

/** The sizes of the Decision Journal (ADR-002 §9). Pure. */
object JournalRules {
    /** ADR-002 §9: the size is compared and shown in MB of 1,000,000 bytes, as Android shows file sizes. */
    const val BYTES_PER_MEGABYTE: Long = 1_000_000

    /** A size limit of [megabytes] MB in bytes. */
    fun limitBytes(megabytes: Int): Long = megabytes * BYTES_PER_MEGABYTE

    /** ADR-002 §9: [bytes] in tenths of a MB, to the nearest tenth, for a size shown with one decimal. */
    fun tenthsOfMegabyte(bytes: Long): Long = (bytes + BYTES_PER_MEGABYTE / 20) / (BYTES_PER_MEGABYTE / 10)

    // UC-010 BR-003: Slussen warns when the Decision Journal reaches the warning threshold of its size limit.
    /** The size at which the Decision Journal is near its limit: [percent] of [limitMb] MB, in bytes. */
    fun warningBytes(limitMb: Int, percent: Int): Long = limitBytes(limitMb) * percent / 100
}

/** What removing the oldest entries right after a size limit was stored came to (UC-010 BR-003). */
sealed interface Trimming {
    /** The size limit was raised: no entry is removed (BR-003). */
    data object NoneNeeded : Trimming

    /** [removed] oldest entries were removed (none while within the limit); the size after is [sizeBytes], null when not known. */
    data class Done(val removed: Int, val sizeBytes: Long?) : Trimming

    /** The journal's database could not remove them now; the size limit is stored all the same (BR-004). */
    data object Failed : Trimming
}

/** What a change of the Decision Journal's size limit came to (UC-010 A2, BR-002, BR-003). */
sealed interface SizeLimitOutcome {
    /** Steps 4, 6: stored, recorded while the Decision Journal is on, and followed by [trimming]. */
    data class Stored(val megabytes: Int, val trimming: Trimming) : SizeLimitOutcome

    /**
     * A2 step 1: [megabytes] is below the current size [sizeBytes] (null: the size could not be read), so the oldest
     * entries would be removed; nothing is stored until the Operator confirms.
     */
    data class ConfirmFirst(val megabytes: Int, val sizeBytes: Long?) : SizeLimitOutcome

    /** It is the size limit stored already: nothing is stored, removed or recorded. */
    data class Unchanged(val megabytes: Int) : SizeLimitOutcome

    /** A1: outside the allowed range; the previous size limit is kept. */
    data class OutsideRange(val range: IntRange) : SizeLimitOutcome

    /** Failure postconditions: it could not be stored; the previous size limit is kept and no entry is removed. */
    data object NotStored : SizeLimitOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : SizeLimitOutcome
}

/** What clearing the Decision Journal came to (UC-010 A3). */
sealed interface ClearOutcome {
    /**
     * Step 3: every entry removed and its space freed; the clearing is its first new entry when [recorded] (never while
     * the Decision Journal is off). [sizeBytes] is the size after, null when not known.
     */
    data class Cleared(val recorded: Boolean, val sizeBytes: Long?) : ClearOutcome

    /** The journal's database could not clear it: every entry is kept and nothing is recorded. */
    data object NotCleared : ClearOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : ClearOutcome
}

/**
 * The Decision Journal's own control (UC-010 A2, A3, A4, BR-003, the success postconditions), `:core` `journal`
 * (ADR-001 §7): switching it on and off, its size against its limit, changing the limit and the warning threshold, and
 * clearing it. A setting is stored through O-13 on the operation lane (precondition, UC-006 BR-005); the trimming right
 * after a lower size limit and the clearing run on the journal's own database through [control], after the lane is free,
 * never inside a transaction of `slussen.db`, so that no decision waits for them (UC-011 BR-002, BR-004). What the
 * journal's database cannot do is answered, never thrown.
 */
class JournalControl(
    private val store: OperatorSettingsStore,
    private val journal: DecisionJournal,
    private val control: DecisionJournalControl,
    private val coordinator: OperationCoordinator,
) {
    /** Step 1, A4: the stored values and the size; a size that cannot be read is null. Throws when the settings cannot be read. */
    suspend fun shown(): JournalShown {
        val current = store.current()
        return JournalShown(current.journalOn, current.journalSizeLimitMb, current.journalWarningPercent, readSafely { control.sizeBytes() })
    }

    /** Steps 2, 4, 6 and the success postconditions: the Decision Journal is switched [on] or off. No entry is removed (BR-003). */
    suspend fun switch(on: Boolean): SwitchOutcome {
        val result = coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) {
            val before = readSafely { store.current() } ?: return@run SwitchOutcome.NotStored
            if (before.journalOn == on) return@run SwitchOutcome.Unchanged(on)
            if (!on) {
                // UC-010 BR-003: Switching the Decision Journal off is recorded as its last entry, handed over before journal_on is stored and never refused for it.
                journal.recordOfTheJournalItself("Decision Journal switched off", before, alsoWhileOff = true)
            }
            if (!storedSafely { store.store(SettingChange(SettingValue.JournalOn(on), SettingFollowUp.None)) }) {
                if (!on) journal.recordOfTheJournalItself("Decision Journal not switched off: the change could not be stored, so it stays on", before)
                return@run SwitchOutcome.NotStored
            }
            // UC-010 BR-003: Switching the Decision Journal on is recorded as its first entry after; neither switching removes an entry.
            if (on) journal.recordOfTheJournalItself("Decision Journal switched on", before.copy(journalOn = true))
            SwitchOutcome.Stored(on)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> SwitchOutcome.Unavailable(result.reason)
        }
    }

    /**
     * Steps 2-4, 6, A1, A2: the size limit becomes [megabytes]. Below the current size it is stored only once [confirmed]
     * (A2); a lower limit is followed, right after it is stored, by the oldest entries removed until the Decision Journal
     * is within it, also while it is off; a higher one removes none (BR-003).
     */
    suspend fun changeSizeLimit(megabytes: Int, confirmed: Boolean = false): SizeLimitOutcome {
        val range = AllowedRanges.JOURNAL_SIZE_LIMIT_MB
        // UC-010 BR-002: The Decision Journal size limit lies within its allowed range, a whole number; A1 refuses another value and keeps the previous one.
        if (megabytes !in range) return SizeLimitOutcome.OutsideRange(range)
        if (!confirmed) {
            val current = readSafely { store.current() } ?: return SizeLimitOutcome.NotStored
            if (megabytes < current.journalSizeLimitMb) {
                val size = readSafely { control.sizeBytes() }
                // UC-010 BR-003: A size limit below the current size removes the oldest entries, so A2 asks first; a size that cannot be read asks too.
                if (size == null || size > JournalRules.limitBytes(megabytes)) return SizeLimitOutcome.ConfirmFirst(megabytes, size)
            }
        }
        val result = coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) {
            val before = readSafely { store.current() } ?: return@run null
            if (before.journalSizeLimitMb == megabytes) return@run before
            if (!storedSafely { store.store(SettingChange(SettingValue.JournalSizeLimit(megabytes), SettingFollowUp.None)) }) return@run null
            // UC-011 BR-001: Every change of an Operator Setting is recorded, with the value before and after.
            journal.recordOfTheJournalItself(
                "Decision Journal size limit changed from ${before.journalSizeLimitMb} to $megabytes MB",
                before.copy(journalSizeLimitMb = megabytes),
            )
            before
        }
        val before = when (result) {
            is OperationResult.Refused -> return SizeLimitOutcome.Unavailable(result.reason)
            is OperationResult.Ran -> result.value ?: return SizeLimitOutcome.NotStored
        }
        if (before.journalSizeLimitMb == megabytes) return SizeLimitOutcome.Unchanged(megabytes)
        // UC-010 BR-003: Raising the size limit removes no entry.
        if (megabytes > before.journalSizeLimitMb) return SizeLimitOutcome.Stored(megabytes, Trimming.NoneNeeded)
        return SizeLimitOutcome.Stored(megabytes, trimmedTo(megabytes))
    }

    // UC-010 BR-003: Right after a lower size limit is stored, also while the Decision Journal is off, its oldest entries are removed until it is within the limit.
    /** Runs on the journal's own database after the lane is free; a failure stores nothing back and is answered. */
    private suspend fun trimmedTo(megabytes: Int): Trimming {
        val removed = readSafely { control.keepWithin(JournalRules.limitBytes(megabytes)) } ?: return Trimming.Failed
        return Trimming.Done(removed, readSafely { control.sizeBytes() })
    }

    /** Steps 2-4, 6, A1, A4: the warning threshold becomes [percent] of the size limit. It removes no entry (BR-003). */
    suspend fun changeWarningThreshold(percent: Int): SettingOutcome {
        val range = AllowedRanges.JOURNAL_WARNING_PERCENT
        // UC-010 BR-002: The Decision Journal's warning threshold lies within its allowed range, a whole number; A1 refuses another value.
        if (percent !in range) return SettingOutcome.OutsideRange(range)
        val result = coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) {
            val before = readSafely { store.current() } ?: return@run SettingOutcome.NotStored
            if (before.journalWarningPercent == percent) return@run SettingOutcome.Unchanged(percent)
            if (!storedSafely { store.store(SettingChange(SettingValue.JournalWarningThreshold(percent), SettingFollowUp.None)) }) {
                return@run SettingOutcome.NotStored
            }
            // UC-011 BR-001: Every change of an Operator Setting is recorded, with the value before and after.
            journal.recordOfTheJournalItself(
                "Decision Journal warning threshold changed from ${before.journalWarningPercent}% to $percent%",
                before.copy(journalWarningPercent = percent),
            )
            SettingOutcome.Stored(percent)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> SettingOutcome.Unavailable(result.reason)
        }
    }

    /**
     * A3 step 3: every entry removed and its space freed, the clearing recorded as the first new entry while the
     * Decision Journal is on, nothing else changed (BR-004). The precondition is checked on the lane; the clearing runs on
     * the journal's own database after it, so that no decision waits for it.
     */
    suspend fun clear(): ClearOutcome {
        val checked = coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) { readSafely { store.current() } }
        val current: OperatorSettings = when (checked) {
            is OperationResult.Refused -> return ClearOutcome.Unavailable(checked.reason)
            is OperationResult.Ran -> checked.value ?: return ClearOutcome.NotCleared
        }
        // UC-011 BR-001: Every clearing of the Decision Journal is recorded, as its first new entry, none while it is switched off.
        val first = if (current.journalOn) journal.entry(JournalEventKind.JournalControl, "Decision Journal cleared") else null
        if (!storedSafely { control.clear(first) }) return ClearOutcome.NotCleared
        return ClearOutcome.Cleared(recorded = first != null, sizeBytes = readSafely { control.sizeBytes() })
    }
}
