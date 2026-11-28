package slussen.app.schedule

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.testfixtures.UseCase
import java.time.Duration

/**
 * The Fetch Schedule's work on the device's WorkManager (UC-001 BR-013, ADR-001 §5), under a test's own unique name so
 * that the debug build's own Fetch Schedule is left alone: periodic, a network connection its only constraint, its first
 * run a whole period after it is set, one work at a time, removed at 0. The worker is not run here: a run would fetch.
 */
@DisplayName("UC-001: Fetch Transactions, the Fetch Schedule's work on the device")
class UC001FetchScheduleWorkIT {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "slussen-scheduled-fetch-test"
    private val work = WorkManagerFetchSchedule(context, name)

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @AfterEach
    fun removeTheTestsWork() {
        WorkManager.getInstance(context).cancelUniqueWork(name).result.get()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-001", "BR-013"])
    @DisplayName("BR-001, BR-013: the work is periodic every 12 hours at a Fetch Schedule of 2, waits for a network connection and does not fall due at once")
    fun workIsPeriodicWithANetworkConstraint() = runBlocking {
        val before = System.currentTimeMillis()

        FetchSchedule(work) { 2 }.atStart()

        val info = infos().single()
        assertEquals(Duration.ofHours(12).toMillis(), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.nextScheduleTimeMillis >= before + Duration.ofHours(12).toMillis() - Duration.ofMinutes(1).toMillis())
        assertNotNull(work.scheduled()?.nextAt)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a changed Fetch Schedule replaces the work, so there is never a second; 0 removes it")
    fun changeReplacesAndZeroRemoves() = runBlocking {
        val schedule = FetchSchedule(work) { 2 }
        schedule.atStart()

        schedule.set(1)
        assertEquals(Duration.ofHours(24).toMillis(), infos().single().periodicityInfo?.repeatIntervalMillis)

        schedule.set(0)
        assertTrue(infos().isEmpty())
        assertNull(schedule.next())
    }
}
