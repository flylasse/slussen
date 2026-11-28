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
import slussen.app.ui.consent.ConsentAnswers
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

    /** The process's work off the main thread: the start, and each answer of a Bank's sign-in (UC-007). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The device's clock, the one the core's services use. */
    private val clock: Clock = Clock.systemDefaultZone()

    /**
     * The answers of the Bank's Strong Customer Authentication for the whole process (UC-007 steps 6-7, BR-011): each one
     * handed to the core once, off the main thread, with the time it arrived, also before the services have started.
     */
    val consentAnswers: ConsentAnswers = ConsentAnswers(started, scope, clock)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            started.value = compositionRoot.start(this@SlussenApplication, clock, scope)
            // UC-008 BR-001: the Consent Requests that wait are read from stored data only.
            consentAnswers.readWaiting()
        }
    }
}
