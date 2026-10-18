package slussen.app

import android.app.Application
import slussen.app.di.CompositionRoot
import slussen.app.platform.log.AndroidLogSink
import slussen.app.platform.log.LogcatLog

/** The app process. It owns the one composition root (ADR-001 §5). */
class SlussenApplication : Application() {
    /**
     * Created on first use. Until the Operator Settings are stored (UC-010), Detailed Logging is off, its default in
     * `OPERATOR_SETTINGS.detailed_logging`.
     */
    val compositionRoot: CompositionRoot by lazy {
        CompositionRoot(LogcatLog(AndroidLogSink, detailedLogging = { false }))
    }
}
