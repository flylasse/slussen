package slussen.app.platform.restart

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Opens the app again after its process has ended (ADR-002 §3 O-17 step 3): [reopen] starts [RestartActivity] in a
 * process of its own (`:restart`), which ends this process and starts the app's launcher activity, so that the start
 * does not land in the process that ends. Nothing of the result travels with it. [exit] ends this process.
 */
class ProcessRestart(private val context: Context) {
    fun reopen() {
        val restart = Intent(context, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RestartActivity.ENDING_PROCESS, Process.myPid())
        context.startActivity(restart)
    }

    fun exit(): Nothing {
        Runtime.getRuntime().exit(0)
        error("The process did not end.")
    }

    companion object {
        /** The name ending of [RestartActivity]'s own process, in which the app starts nothing of its own. */
        const val PROCESS_SUFFIX = ":restart"

        /** Whether [processName] is [RestartActivity]'s process, where no storage is opened. */
        fun isRestartProcess(processName: String?): Boolean = processName?.endsWith(PROCESS_SUFFIX) == true
    }
}

/**
 * A screenless activity in its own process, not exported: it ends the process that asked for it, starts the app's
 * launcher activity in a new one, and ends its own.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ending = intent.getIntExtra(ENDING_PROCESS, -1)
        if (ending > 0 && ending != Process.myPid()) Process.killProcess(ending)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val ENDING_PROCESS = "slussen.restart.ending-process"
    }
}
