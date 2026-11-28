package slussen.app.platform.restart

import android.content.Context
import android.content.Intent

/**
 * Ends the app's process and starts the app again (ADR-002 §3 O-17 step 3): the launching intent carries [handOver], the
 * text the new process shows once and never stores. Android starts the new activity in a new process once this one has
 * ended; nothing of this process's memory reaches it.
 */
class ProcessRestart(private val context: Context) {
    fun restart(extra: String, handOver: ArrayList<String>): Nothing {
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) { "The app has no launcher activity." }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        launch.putStringArrayListExtra(extra, handOver)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
        error("The process did not end.")
    }
}
