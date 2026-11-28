package slussen.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.di.CompositionRoot
import slussen.app.di.DetailedLoggingSwitch
import slussen.app.platform.log.AndroidLogSink
import slussen.app.platform.log.LogcatLog
import slussen.app.ui.UiServices
import java.time.Clock

/**
 * The app process. It owns the one composition root (ADR-001 §5) and starts it once, off the main thread, in every
 * process of the app: one the Operator opens and one a Scheduled Fetch's worker starts without a screen alike.
 */
class SlussenApplication : Application() {
    /** What the log reads Detailed Logging from: the stored Operator Setting (UC-010 A5, ADR-006 §3). */
    private val detailedLogging = DetailedLoggingSwitch()

    /** Created on first use; the log follows the stored Detailed Logging, off until it is read (its default). */
    val compositionRoot: CompositionRoot by lazy {
        CompositionRoot(LogcatLog(AndroidLogSink, detailedLogging = detailedLogging::isOn), detailedLogging)
    }

    private val started = MutableStateFlow<UiServices?>(null)

    /** The core's services, once the storage is open and the plug-ins are registered. */
    val services: StateFlow<UiServices?> = started

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            started.value = compositionRoot.start(this@SlussenApplication, Clock.systemDefaultZone(), scope)
        }
    }
}
