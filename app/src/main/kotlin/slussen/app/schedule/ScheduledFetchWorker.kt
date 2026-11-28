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
 * the app after the start sequence of ADR-002 §6, runs one [ScheduledFetchRun] and answers as [ScheduledFetchAnswer] decides.
 */
class ScheduledFetchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as SlussenApplication
        val run = ScheduledFetchRun(
            fetch = { application.services.filterNotNull().first().fetch },
            notification = { application.services.filterNotNull().first().updateNotification() },
        )
        return ScheduledFetchAnswer.after { run.run() }
    }
}
