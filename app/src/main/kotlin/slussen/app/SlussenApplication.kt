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
import slussen.app.ui.settings.StartedOverNotice
import slussen.app.ui.AppState
import slussen.app.platform.restart.ProcessRestart
import slussen.persistence.start.StartNotFinished
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

    /**
     * UC-010 A12 step 4: the card of a new installation after Start Over, in this process's memory only; the names it
     * shows reach it in the intent that started the app (ADR-002 §3 O-17 step 3) and are never stored.
     */
    val startedOver: StartedOverNotice = StartedOverNotice()

    /** What the root of the screens reads before any screen: Start Over recorded, or a start that could not finish (ADR-002 §3 O-17). */
    val appState: AppState = AppState()

    /** How many of the app's activities are started: above 0 while the app is in the foreground (ADR-002 §3 O-17 step 3). */
    private val startedActivities = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        // ADR-002 §3 O-17 step 3: the process of the activity that opens Slussen again starts nothing of Slussen's own.
        if (ProcessRestart.isRestartProcess(processNameOrNull())) return
        registerActivityLifecycleCallbacks(ForegroundCount(startedActivities))
        scope.launch {
            val services = try {
                compositionRoot.start(this@SlussenApplication, clock, scope, appState) { startedActivities.get() > 0 }
            } catch (notFinished: StartNotFinished) {
                // ADR-002 §3 O-17 step 6: a start that stops says so and what the Operator can do; it never crashes.
                appState.startNotFinished(notFinished.startingOver)
                return@launch
            }
            startedOver.atStart(services.startOverRecord)
            started.value = services
            // UC-008 BR-001: the Consent Requests that wait, and one whose lifetime ended meanwhile, are read from stored data only.
            consentAnswers.atStart()
        }
    }
}

/** The name of this process, so that the `:restart` process starts nothing of Slussen's own. */
private fun processNameOrNull(): String? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        try {
            java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        } catch (failure: Exception) {
            null
        }
    }

/** Counts the app's started activities, so that Start Over opens the app again only while it is in the foreground. */
private class ForegroundCount(private val started: java.util.concurrent.atomic.AtomicInteger) : Application.ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: android.app.Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: android.app.Activity) {
        started.decrementAndGet()
    }

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
}
