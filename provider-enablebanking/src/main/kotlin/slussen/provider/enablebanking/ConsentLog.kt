package slussen.provider.enablebanking

import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog

/** What the Bank Consent operations log (ADR-006 §1): the operation's outcome, never a value of the consent. */
internal enum class EnableBankingConsentEvent : LogEvent {
    BanksListed,
    BanksNotListed,
    ConsentRequestStarted,
    ConsentRequestNotStarted,
    ConsentCompleted,
    ConsentNotCompleted,
    SessionEnded,
    SessionEndNotConfirmed,
}

/**
 * The log of the Bank Consent operations (UC-007 BR-006, NFR-008, ADR-006 §2). It takes only an event, counts, closed
 * outcome kinds and an HTTP status, never a text, so no token, code, `state`, consent address, session or Bank Account
 * identifier can reach a log line, with Detailed Logging on or off. It lives apart from the code that reveals a value
 * (ADR-006 L-04).
 */
internal class ConsentLog(private val log: SlussenLog) {
    fun outcome(event: EnableBankingConsentEvent, kind: Enum<*>? = null, count: Int? = null) {
        val facts = listOfNotNull(kind?.let { LogFact.Value(it) }, count?.let { LogFact.Count(it.toLong()) })
        log.event(event, *facts.toTypedArray())
    }

    /** With Detailed Logging on: the HTTP status Enable Banking answered with, a number only. */
    fun answered(event: EnableBankingConsentEvent, status: Int) {
        log.detail(event) { "answered with HTTP status $status" }
    }
}
