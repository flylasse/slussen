package slussen.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the root of the screens shows (ADR-002 §3 O-17 steps 3, 6). */
enum class RootScreen {
    /** From the record of Start Over until the process ends: only that Slussen is starting over (`settings.html#starting-over`). */
    StartingOver,

    /** The start stopped before any stored data was opened (`settings.html#start-over-not-finished`). */
    StartNotFinished,

    /** The services are not started yet. */
    Opening,

    /** The screens of the app. */
    Screens,
}

/**
 * The state of the whole app process that the root of the screens reads before any screen (ADR-002 §3 O-17): that Start
 * Over is recorded and the process ends, or that the start could not finish. Not one screen's state, so that no other
 * control can be reached in either case.
 */
class AppState {
    private val starting = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    private val listeners = mutableListOf<() -> Unit>()

    val startingOver: StateFlow<Boolean> = starting.asStateFlow()

    /** The text of a start that could not finish, or null. */
    val startFailure: StateFlow<String?> = failure.asStateFlow()

    /** Runs [listener] once Start Over is recorded, before the process's own state ends (a test sees the order). */
    fun whenStartingOver(listener: () -> Unit) {
        listeners += listener
    }

    // A12 step 4 of UC-010: from the record of Start Over until the process ends, every screen shows only that Slussen is starting over.
    fun startOverRecorded() {
        starting.value = true
        listeners.forEach { it() }
    }

    /** The start stopped: a file of the old data or the start intent could not be deleted; [startingOver] while a Start Over was being finished. */
    fun startNotFinished(startingOver: Boolean) {
        failure.value = if (startingOver) AppTexts.START_OVER_NOT_FINISHED else AppTexts.START_NOT_FINISHED
    }

    fun root(servicesStarted: Boolean): RootScreen = when {
        starting.value -> RootScreen.StartingOver
        failure.value != null -> RootScreen.StartNotFinished
        !servicesStarted -> RootScreen.Opening
        else -> RootScreen.Screens
    }
}

/** Slussen's own words at the root of the screens (`settings.html#starting-over`, `#start-over-not-finished`). */
object AppTexts {
    const val STARTING_OVER = "Slussen is starting over. It closes and opens again by itself, then deletes everything it stored and " +
        "starts as newly installed. If it does not open again, open Slussen: it finishes starting over then."
    const val START_OVER_NOT_FINISHED = "Slussen could not finish starting over. Nothing can be used until it has. Open Slussen " +
        "again; if this stays, remove Slussen's data in the device's settings for the app."
    const val START_NOT_FINISHED = "Slussen could not finish opening its stored data. Nothing can be used until it has. Open " +
        "Slussen again; if this stays, remove Slussen's data in the device's settings for the app."
}
