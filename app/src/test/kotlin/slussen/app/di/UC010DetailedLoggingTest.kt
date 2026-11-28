package slussen.app.di

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.app.platform.log.LogSink
import slussen.app.platform.log.LogcatLog
import slussen.core.port.SwitchSetting
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.InMemoryOperatorSettingsStore
import slussen.testing.TestSlice

/**
 * UC-010 A5, BR-005, ADR-006 §3: the app's log reads Detailed Logging from the stored Operator Setting, at the start and
 * after every change, through [DetailedLoggingSwitch], as the composition root wires it; off when it cannot be read.
 */
@DisplayName("UC-010: Adjust Settings, the app's log follows the stored Detailed Logging")
class UC010DetailedLoggingTest {
    private enum class ExampleEvent : LogEvent { SomethingHappened }

    private val written = mutableListOf<String>()
    private val switch = DetailedLoggingSwitch()
    private val log = LogcatLog(LogSink { _, _, text -> written += text }) { switch.isOn() }

    private fun detail() = log.detail(ExampleEvent.SomethingHappened) { "invented detail" }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"])
    @DisplayName("A5: at the start the log reads Detailed Logging as stored: on writes details, off writes none")
    fun theLogReadsTheStoredValueAtTheStart() = runTest {
        switch.readFrom(InMemoryOperatorSettingsStore().also { it.settings = it.settings.copy(detailedLogging = true) })
        detail()
        switch.readFrom(InMemoryOperatorSettingsStore())
        detail()

        assertEquals(listOf("ExampleEvent.SomethingHappened invented detail"), written)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"])
    @DisplayName("BR-005: when the stored value cannot be read, Detailed Logging is off for the log, so that no transaction data reaches it by mistake")
    fun anUnreadableSettingLeavesTheLogOff() = runTest {
        switch.set(true)

        switch.readFrom(InMemoryOperatorSettingsStore().also { it.failsToRead = true })
        detail()

        assertTrue(written.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"])
    @DisplayName("A5: a change of Detailed Logging stored on Settings reaches the log at once, as the composition root wires the core's settings to it")
    fun aStoredChangeReachesTheLog() = runTest {
        val slice = TestSlice()
        val settings = slussen.core.settings.GeneralSettings(
            slice.settings, slice.comparison, slice.connectionStore, slice.copyStore, slice.journal,
            applied = { switch.set(it.detailedLogging) },
        )

        settings.switch(SwitchSetting.DetailedLogging, true)
        detail()
        settings.switch(SwitchSetting.DetailedLogging, false)
        detail()

        assertEquals(1, written.size)
    }
}
