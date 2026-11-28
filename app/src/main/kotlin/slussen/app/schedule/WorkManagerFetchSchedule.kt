package slussen.app.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * The Fetch Schedule's periodic work on WorkManager (ADR-001 §5, ADR-005 §1): one unique periodic work [name], replaced
 * when scheduled again (so never two at once), with a network connection as its only constraint (UC-001 BR-013). The
 * device may start a run later than it falls due, for example to save battery, and one it skipped is not made up.
 */
class WorkManagerFetchSchedule(context: Context, private val name: String = UNIQUE_NAME) : FetchScheduleWork {
    private val manager = WorkManager.getInstance(context)

    override suspend fun scheduled(): ScheduledFetchWork? = withContext(Dispatchers.IO) {
        val info = manager.getWorkInfosForUniqueWork(name).get().firstOrNull { !it.state.isFinished } ?: return@withContext null
        val every = info.periodicityInfo?.repeatIntervalMillis ?: return@withContext null
        ScheduledFetchWork(Duration.ofMillis(every), info.nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE }?.let(Instant::ofEpochMilli))
    }

    override suspend fun schedule(every: Duration, firstAfter: Duration) {
        // UC-001 BR-013: A Scheduled Fetch starts only while the device has a network connection; without one it waits for one.
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<ScheduledFetchWorker>(every)
            .setInitialDelay(firstAfter)
            .setConstraints(constraints)
            .build()
        withContext(Dispatchers.IO) {
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request).result.get()
        }
    }

    override suspend fun cancel() {
        withContext(Dispatchers.IO) { manager.cancelUniqueWork(name).result.get() }
    }

    companion object {
        /** The one name of the Fetch Schedule's work. */
        const val UNIQUE_NAME: String = "slussen-scheduled-fetch"
    }
}
