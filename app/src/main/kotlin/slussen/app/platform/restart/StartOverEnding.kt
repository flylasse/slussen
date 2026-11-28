package slussen.app.platform.restart

import slussen.app.ui.AppState
import slussen.core.settings.StartOverResult

/**
 * ADR-002 §3 O-17 step 3, once Start Over is recorded: every screen shows only that Slussen is starting over, the Fetch
 * Schedule's work and the New Transaction Notification are removed, the app asks to be opened again while it is in the
 * foreground, and the process ends in every case, whatever of that failed. The result is not handed on: the next start
 * reads it from the record.
 */
class StartOverEnding(
    private val state: AppState,
    private val cancelSchedule: suspend () -> Unit,
    private val removeNotification: () -> Unit,
    private val inForeground: () -> Boolean,
    private val reopen: () -> Unit,
    private val exit: () -> Nothing,
) {
    suspend fun end(@Suppress("UNUSED_PARAMETER") result: StartOverResult): Nothing {
        try {
            state.startOverRecorded()
            quietly { cancelSchedule() }
            quietly { removeNotification() }
            // ADR-002 §3 O-17 step 3: opening itself again is tried only while the app is in the foreground.
            if (inForeground()) quietly { reopen() }
        } catch (ended: Throwable) {
            Unit
        }
        // A12 step 4 of UC-010: once Start Over is recorded the process ends in every case.
        exit()
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            Unit
        }
    }
}
