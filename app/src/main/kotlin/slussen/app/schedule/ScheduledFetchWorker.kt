package slussen.app.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import slussen.app.SlussenApplication

/**
 * The Fetch Schedule's periodic worker (ADR-001 §5, UC-001 A12). It runs in a process where no screen may have been
 * opened: it waits for the services of the one composition root, which `SlussenApplication` starts in every process of
 * the app after the start sequence of ADR-002 §6, and runs one [ScheduledFetchRun].
 */
class ScheduledFetchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as SlussenApplication
        ScheduledFetchRun { application.services.filterNotNull().first().fetch }.run()
        // UC-001 BR-005: A Scheduled Fetch that meets a failure is not repeated; the work answers success every time, so that the device does not repeat it.
        return Result.success()
    }
}
