package slussen.app.schedule

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The device's periodic work as a JVM test sees it: at most one Scheduled Fetch work under its unique name, replaced when
 * scheduled again, with the time it next falls due counted from when it was scheduled. Invented times only.
 */
class FakeFetchScheduleWork(private val clock: Clock) : FetchScheduleWork {
    var current: ScheduledFetchWork? = null
        private set

    /** Every call, in order: "schedule every PT12H first after PT12H", "cancel". */
    val calls = mutableListOf<String>()

    /** Makes the next calls fail, as a device whose work cannot be reached would. */
    var fails: Boolean = false

    /** The work as it would be after a start of the device's work in another process (set by a test). */
    fun alreadyScheduled(every: Duration, nextAt: Instant) {
        current = ScheduledFetchWork(every, nextAt)
    }

    override suspend fun scheduled(): ScheduledFetchWork? {
        if (fails) error("The device's work could not be read (invented failure).")
        return current
    }

    override suspend fun schedule(every: Duration, firstAfter: Duration) {
        if (fails) error("The device's work could not be scheduled (invented failure).")
        calls += "schedule every $every first after $firstAfter"
        current = ScheduledFetchWork(every, clock.instant().plus(firstAfter))
    }

    override suspend fun cancel() {
        if (fails) error("The device's work could not be cancelled (invented failure).")
        calls += "cancel"
        current = null
    }
}
