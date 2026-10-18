package slussen.app.platform.log

import android.util.Log
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog

/** Where a formatted record goes: a function of priority, tag and text (ADR-006 §3). */
fun interface LogSink {
    fun write(priority: Int, tag: String, text: String)
}

/** The production sink: `android.util.Log.println` with the tag `Slussen` (ADR-006 §3). */
object AndroidLogSink : LogSink {
    override fun write(priority: Int, tag: String, text: String) {
        Log.println(priority, tag, text)
    }
}

/**
 * The one implementation of [SlussenLog] (ADR-006 §3). An event is written as `<enum class simple name>.<constant
 * name>` with its facts; a `LogFact.Value` by its constant's name, never by `toString()`. [detailedLogging] is read at
 * every `detail` call, so it follows every change of the Operator Setting.
 */
class LogcatLog(
    private val sink: LogSink,
    private val detailedLogging: () -> Boolean,
) : SlussenLog {
    override fun event(event: LogEvent, vararg facts: LogFact) {
        val text = buildString {
            append(name(event))
            facts.forEach { append(' ').append(format(it)) }
        }
        sink.write(Log.INFO, TAG, text)
    }

    override fun detail(event: LogEvent, message: () -> String) {
        if (!detailedLogging()) return
        sink.write(Log.DEBUG, TAG, name(event) + " " + message())
    }

    private fun name(event: LogEvent): String {
        val constant = event as? Enum<*> ?: return event.javaClass.simpleName
        return constant.declaringJavaClass.simpleName + "." + constant.name
    }

    private fun format(fact: LogFact): String = when (fact) {
        is LogFact.Count -> "count=${fact.value}"
        is LogFact.Elapsed -> "elapsed=${fact.value}"
        is LogFact.At -> "at=${fact.value}"
        is LogFact.Value -> "value=${fact.value.declaringJavaClass.simpleName}.${fact.value.name}"
        is LogFact.RecordId -> "record=${fact.kind.name}#${fact.id}"
    }

    companion object {
        const val TAG = "Slussen"
    }
}
