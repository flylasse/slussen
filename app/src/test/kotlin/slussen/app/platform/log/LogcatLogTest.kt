package slussen.app.platform.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.RecordKind

/** The adapter of ADR-006 §3 with a recording sink, so the assertions are about what would reach logcat. */
class LogcatLogTest {
    private enum class ExampleEvent : LogEvent { SomethingHappened }

    private enum class ExampleValue {
        Chosen,
        ;

        override fun toString(): String = "never written"
    }

    private val written = mutableListOf<String>()
    private val sink = LogSink { _, tag, text -> written += "$tag: $text" }

    @Test
    fun anEventIsWrittenByItsEnumNamesAndItsFacts() {
        LogcatLog(sink) { false }.event(
            ExampleEvent.SomethingHappened,
            LogFact.Count(3),
            LogFact.Value(ExampleValue.Chosen),
            LogFact.RecordId(RecordKind.BankTransaction, 42),
        )
        assertEquals(
            listOf("Slussen: ExampleEvent.SomethingHappened count=3 value=ExampleValue.Chosen record=BankTransaction#42"),
            written,
        )
    }

    @Test
    fun aDetailIsNotEvenBuiltWhileDetailedLoggingIsOff() {
        var built = false
        LogcatLog(sink) { false }.detail(ExampleEvent.SomethingHappened) {
            built = true
            "invented detail"
        }
        assertTrue(written.isEmpty())
        assertFalse(built)
    }

    @Test
    fun aDetailIsWrittenWhileDetailedLoggingIsOn() {
        LogcatLog(sink) { true }.detail(ExampleEvent.SomethingHappened) { "invented detail" }
        assertEquals(listOf("Slussen: ExampleEvent.SomethingHappened invented detail"), written)
    }
}
