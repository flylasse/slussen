package slussen.app.schedule

import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant

/** The Scheduled Fetch work the device holds: every how long it falls due, and when it falls due next, if the device says. */
data class ScheduledFetchWork(val every: Duration, val nextAt: Instant?)

/**
 * The device's periodic work of the Fetch Schedule (ADR-001 §5), behind an interface so that a JVM test replaces it. One
 * work at most, under one unique name: scheduling again replaces it, counted from then.
 */
interface FetchScheduleWork {
    /** The work scheduled now, or null when none is. */
    suspend fun scheduled(): ScheduledFetchWork?

    /** Schedules the work every [every], its first run [firstAfter] from now, replacing any work scheduled before. */
    suspend fun schedule(every: Duration, firstAfter: Duration)

    /** Removes the work. */
    suspend fun cancel()

    /** No device work: nothing is scheduled (a screen test's services). */
    object None : FetchScheduleWork {
        override suspend fun scheduled(): ScheduledFetchWork? = null

        override suspend fun schedule(every: Duration, firstAfter: Duration) = Unit

        override suspend fun cancel() = Unit
    }
}

/** What to do with the device's work. */
sealed interface ScheduleDecision {
    /** The work stays as it is. */
    data object Keep : ScheduleDecision

    /** The work is scheduled every [every], its first run [firstAfter] from now, replacing any before. */
    data class Schedule(val every: Duration, val firstAfter: Duration) : ScheduleDecision

    /** The work is removed. */
    data object Cancel : ScheduleDecision
}

/** The scheduling decisions of the Fetch Schedule (UC-001 BR-013, S-B-38). Pure. */
object FetchScheduleRules {
    /** The window of the Fetch Schedule, "in any 24 hours" (UC-001 BR-013, UC-010 BR-001). */
    private val DAY: Duration = Duration.ofHours(24)

    // UC-001 BR-013: Scheduled Fetches fall due every 24 hours divided by the Fetch Schedule, evenly spaced, with no clock time.
    /** Every how long a Scheduled Fetch falls due under a Fetch Schedule of [perDay], above 0. */
    fun every(perDay: Int): Duration {
        require(perDay > 0) { "A Fetch Schedule of $perDay makes no Scheduled Fetch." }
        return DAY.dividedBy(perDay.toLong())
    }

    // UC-001 BR-001: Slussen never fetches when the app is opened: the first run of the work falls due a whole period after it is set, never at once.
    private fun scheduleFrom(perDay: Int): ScheduleDecision.Schedule = every(perDay).let { ScheduleDecision.Schedule(it, firstAfter = it) }

    // UC-001 BR-013: The schedule is set by the first start of Slussen while the stored Fetch Schedule is above 0, without the Operator opening Settings; a later start keeps a schedule already set, and 0 removes it.
    /**
     * At a start of Slussen in any process, with the stored Fetch Schedule [perDay] and the work [scheduled] now. Work of
     * another period than the stored value (a change the device did not take) is set again from now, so that no more
     * Scheduled Fetches run than the Fetch Schedule says.
     */
    fun atStart(perDay: Int, scheduled: ScheduledFetchWork?): ScheduleDecision = when {
        perDay <= 0 -> if (scheduled != null) ScheduleDecision.Cancel else ScheduleDecision.Keep
        scheduled == null || scheduled.every != every(perDay) -> scheduleFrom(perDay)
        else -> ScheduleDecision.Keep
    }

    // UC-001 BR-013: The schedule is set again when the Operator changes the Fetch Schedule (UC-010 A6), counted from the time of the change; 0 removes it.
    /** After the Operator stored the Fetch Schedule [perDay] (UC-010 A6). */
    fun changed(perDay: Int): ScheduleDecision = if (perDay <= 0) ScheduleDecision.Cancel else scheduleFrom(perDay)
}

/**
 * The Fetch Schedule's periodic work (UC-001 A12, BR-013), set from the stored Operator Setting [stored] on the device's
 * [work]: at every start, after a change on Settings, and read for the approximate next time Settings shows. Nothing of
 * it is stored by Slussen; what is scheduled is the device's. What the storage or the device throws is answered, never
 * a crash: the start of Slussen goes on.
 */
class FetchSchedule(private val work: FetchScheduleWork, private val stored: suspend () -> Int) {
    /**
     * UC-010 A6, NFR-023: the last change of the Fetch Schedule was stored but the device did not take it; true until a
     * start or a later change has set the work. Held for this process; the next start sets the work from the stored value.
     */
    @Volatile
    var notSet: Boolean = false
        private set

    /** At a start of Slussen (BR-013); false when the stored value or the device's work could not be reached. */
    suspend fun atStart(): Boolean =
        answered { apply(FetchScheduleRules.atStart(stored(), work.scheduled())) }.also { if (it) notSet = false }

    /** After the Operator stored the Fetch Schedule [perDay] (UC-010 A6); false when the device's work could not be set. */
    suspend fun set(perDay: Int): Boolean = answered { apply(FetchScheduleRules.changed(perDay)) }.also { notSet = !it }

    /**
     * When the next Scheduled Fetch falls due, approximately, as the device scheduled it; null when none is, when it is not
     * known, at a stored Fetch Schedule of 0, and for work whose period does not match the stored Fetch Schedule.
     */
    suspend fun next(): Instant? =
        try {
            val perDay = stored()
            work.scheduled()?.takeIf { perDay > 0 && it.every == FetchScheduleRules.every(perDay) }?.nextAt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }

    private suspend fun apply(decision: ScheduleDecision) {
        when (decision) {
            ScheduleDecision.Keep -> Unit
            is ScheduleDecision.Schedule -> work.schedule(decision.every, decision.firstAfter)
            ScheduleDecision.Cancel -> work.cancel()
        }
    }

    private suspend fun answered(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
}
