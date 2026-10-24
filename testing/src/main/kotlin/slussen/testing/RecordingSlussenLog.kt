package slussen.testing

import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog

/**
 * A fake of the diagnostic contract (ADR-006 §1) that keeps what it was given, for tests of the core and the plug-ins.
 * Tests of what reaches logcat use the real adapter with a recording sink instead (ADR-006 §6).
 */
class RecordingSlussenLog(private val detailedLogging: Boolean = false) : SlussenLog {
    data class Recorded(val event: LogEvent, val facts: List<LogFact>, val detail: String?)

    private val recorded = mutableListOf<Recorded>()

    val records: List<Recorded> get() = recorded.toList()

    override fun event(event: LogEvent, vararg facts: LogFact) {
        recorded += Recorded(event, facts.toList(), detail = null)
    }

    override fun detail(event: LogEvent, message: () -> String) {
        if (detailedLogging) recorded += Recorded(event, emptyList(), detail = message())
    }
}
