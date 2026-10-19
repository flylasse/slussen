package slussen.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.di.CompositionRoot
import slussen.app.platform.log.AndroidLogSink
import slussen.app.platform.log.LogcatLog
import slussen.app.ui.UiServices
import java.time.Clock

/** The app process. It owns the one composition root (ADR-001 §5) and starts it once, off the main thread. */
class SlussenApplication : Application() {
    /**
     * Created on first use. Until the Operator Settings can be changed (UC-010), Detailed Logging is off, its default in
     * `OPERATOR_SETTINGS.detailed_logging`.
     */
    val compositionRoot: CompositionRoot by lazy {
        CompositionRoot(LogcatLog(AndroidLogSink, detailedLogging = { false }))
    }

    private val started = MutableStateFlow<UiServices?>(null)

    /** The core's services, once the storage is open and the plug-ins are registered. */
    val services: StateFlow<UiServices?> = started

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            started.value = compositionRoot.start(this@SlussenApplication, Clock.systemDefaultZone())
        }
    }
}
