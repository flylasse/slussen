package slussen.app.schedule

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.NumberSetting
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.TestSlice
import java.time.Duration

/**
 * The scheduling decisions of the Fetch Schedule (UC-001 BR-013, S-B-38) on the JVM, behind [FetchScheduleWork], which a
 * test replaces: periodic work evenly spaced from when the Fetch Schedule is set, scheduled by itself at a start when the
 * stored value is above 0 and none is scheduled, replaced when the value changes, cancelled at 0, one at a time. Its
 * first run falls due one period after it is set, never at once, so that opening Slussen never fetches (BR-001).
 */
@DisplayName("UC-001: Fetch Transactions, the Fetch Schedule's periodic work")
class UC001FetchScheduleTest {
    private val slice = TestSlice()
    private val work = FakeFetchScheduleWork(slice.clock)
    private val schedule = FetchSchedule(work) { slice.settings.current().scheduledFetchesPerDay }

    private suspend fun storeFetchSchedule(perDay: Int) =
        slice.settings.store(SettingChange(SettingValue.Number(NumberSetting.FetchSchedule, perDay), SettingFollowUp.None))

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a Scheduled Fetch falls due every 24 hours divided by the Fetch Schedule, every 12 hours at its default of 2")
    fun periodIs24HoursDividedByTheFetchSchedule() {
        assertEquals(Duration.ofHours(12), FetchScheduleRules.every(2))
        assertEquals(Duration.ofHours(24), FetchScheduleRules.every(1))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-001", "BR-013"])
    @DisplayName("BR-001, BR-013: the first start after the build is installed schedules the work by itself, its first run one period later, never at once")
    fun firstStartSchedulesWithoutRunningAtOnce() = runTest {
        assertEquals(2, slice.settings.current().scheduledFetchesPerDay)

        schedule.atStart()

        assertEquals(listOf("schedule every PT12H first after PT12H"), work.calls)
        assertEquals(slice.clock.instant().plus(Duration.ofHours(12)), schedule.next())
        for (perDay in 1..2) {
            val decision = FetchScheduleRules.atStart(perDay, scheduled = null) as ScheduleDecision.Schedule
            assertTrue(decision.firstAfter >= decision.every, "the first run waits a whole period at $perDay")
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a later start keeps the schedule already set, counted from when it was set")
    fun laterStartKeepsTheSchedule() = runTest {
        val nextAt = slice.clock.instant().plus(Duration.ofHours(3))
        work.alreadyScheduled(Duration.ofHours(12), nextAt)

        schedule.atStart()

        assertTrue(work.calls.isEmpty())
        assertEquals(nextAt, schedule.next())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a start that finds work of another period than the stored Fetch Schedule sets it again from now, so no more Scheduled Fetches run than the Fetch Schedule says")
    fun startReplacesWorkOfAnotherPeriod() = runTest {
        storeFetchSchedule(1)
        work.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(3)))

        schedule.atStart()

        assertEquals(listOf("schedule every PT24H first after PT24H"), work.calls)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: at a Fetch Schedule of 0 a start schedules nothing and removes work it finds")
    fun startAtZeroSchedulesNothing() = runTest {
        storeFetchSchedule(0)

        schedule.atStart()
        assertTrue(work.calls.isEmpty())
        assertNull(schedule.next())

        work.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(3)))
        schedule.atStart()
        assertEquals(listOf("cancel"), work.calls)
        assertNull(work.current)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013, UC-010 A6: a changed Fetch Schedule replaces the work, set again from the time of the change; 0 removes it; there is never a second work")
    fun changeReplacesOrCancels() = runTest {
        schedule.atStart()
        slice.clock.advance(Duration.ofHours(5))

        schedule.set(1)
        assertEquals(ScheduledFetchWork(Duration.ofHours(24), slice.clock.instant().plus(Duration.ofHours(24))), work.current)

        schedule.set(2)
        assertEquals(slice.clock.instant().plus(Duration.ofHours(12)), schedule.next())

        schedule.set(0)
        assertNull(work.current)
        assertNull(schedule.next())
        assertEquals(
            listOf(
                "schedule every PT12H first after PT12H",
                "schedule every PT24H first after PT24H",
                "schedule every PT12H first after PT12H",
                "cancel",
            ),
            work.calls,
        )
        assertEquals(ScheduleDecision.Schedule(Duration.ofHours(12), Duration.ofHours(12)), FetchScheduleRules.changed(2))
        assertEquals(ScheduleDecision.Cancel, FetchScheduleRules.changed(0))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-001", "BR-013"])
    @DisplayName("BR-001, BR-013: when the stored Fetch Schedule cannot be read at a start, nothing is scheduled and the start goes on")
    fun unreadableSettingSchedulesNothing() = runTest {
        slice.settings.failsToRead = true

        assertFalse(schedule.atStart())

        assertTrue(work.calls.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: when the device's work cannot be reached, the start goes on and says so; the next time is not known")
    fun unreachableWorkIsAnswered() = runTest {
        work.fails = true

        assertFalse(schedule.atStart())
        assertNull(schedule.next())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: no next time is given for work that does not match the stored Fetch Schedule, nor at 0; a failed change is remembered until a start or a later change sets the work")
    fun nextTimeOnlyForMatchingWork() = runTest {
        work.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(3)))
        storeFetchSchedule(1)
        assertNull(schedule.next())
        storeFetchSchedule(0)
        assertNull(schedule.next())

        work.fails = true
        assertFalse(schedule.set(0))
        work.fails = false
        assertTrue(schedule.notSet)
        assertTrue(schedule.atStart())
        assertFalse(schedule.notSet)
        assertNull(work.current)
    }
}
