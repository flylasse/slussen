package slussen.app.platform.restart

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.app.ui.AppState
import slussen.app.ui.RootScreen
import slussen.core.settings.StartOverResult
import slussen.pluginapi.testfixtures.UseCase

/**
 * ADR-002 §3 O-17 step 3 as plain code decides it: from the record on, the process-wide state shows only that Slussen is
 * starting over, and the process ends in every case. Whether the small activity in its own process really opens the app
 * again, and that the device lets it while Slussen is in the foreground, only a device can show (the end checklist).
 */
@DisplayName("UC-010: Adjust Settings, A12 Start Over, the end of the process")
class UC010StartOverEndingTest {
    private class Exited : RuntimeException()

    private val steps = mutableListOf<String>()
    private val state = AppState()

    private fun ending(foreground: Boolean = true, failing: String? = null) = StartOverEnding(
        state = state,
        cancelSchedule = { steps += "schedule"; if (failing == "schedule") error("invented") },
        removeNotification = { steps += "notification"; if (failing == "notification") error("invented") },
        inForeground = { foreground },
        reopen = { steps += "reopen"; if (failing == "reopen") error("invented: the device refused") },
        exit = { steps += "exit"; throw Exited() },
    )

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 3: every screen shows that Slussen is starting over before anything else, then the work and the notification go, the app asks to be opened again and the process ends")
    fun theOrder() = runTest {
        assertEquals(RootScreen.Screens, state.root(servicesStarted = true))
        state.whenStartingOver { steps += "starting over shown" }

        assertThrows(Exited::class.java) { kotlinx.coroutines.runBlocking { ending().end(StartOverResult(emptyList())) } }

        assertEquals(listOf("starting over shown", "schedule", "notification", "reopen", "exit"), steps)
        assertEquals(RootScreen.StartingOver, state.root(servicesStarted = true))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 3: the process ends in every case: when the work, the notification or opening the app again fails")
    fun endsInEveryCase() {
        listOf("schedule", "notification", "reopen").forEach { failing ->
            steps.clear()
            assertThrows(Exited::class.java) { kotlinx.coroutines.runBlocking { ending(failing = failing).end(StartOverResult(emptyList())) } }
            assertEquals("exit", steps.last(), "when $failing fails")
        }
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 3: when the app is not in the foreground it does not try to open itself again, and the process still ends")
    fun notInTheForeground() {
        assertThrows(Exited::class.java) { kotlinx.coroutines.runBlocking { ending(foreground = false).end(StartOverResult(emptyList())) } }

        assertFalse("reopen" in steps)
        assertEquals("exit", steps.last())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 steps 3, 6: the root of the screens shows starting over above everything, a start that could not finish above opening, and the screens only once started")
    fun theRootOfTheScreens() {
        val opening = AppState()
        assertEquals(RootScreen.Opening, opening.root(servicesStarted = false))
        opening.startNotFinished(startingOver = true)
        assertEquals(RootScreen.StartNotFinished, opening.root(servicesStarted = false))
        assertTrue("could not finish starting over" in opening.startFailure.value!!)
        assertTrue("remove" in opening.startFailure.value!! && "device's settings" in opening.startFailure.value!!)
        opening.startOverRecorded()
        assertEquals(RootScreen.StartingOver, opening.root(servicesStarted = true))
    }
}
