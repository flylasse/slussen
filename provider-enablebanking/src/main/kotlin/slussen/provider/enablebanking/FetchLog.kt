package slussen.provider.enablebanking

import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome

/** What the Fetch logs (ADR-006 §1): its outcome, never a value of a Bank Session, a Bank Account or an answer. */
internal enum class EnableBankingFetchEvent : LogEvent {
    FetchCompleted,
    FetchStopped,
    FetchAnswered,
}

/**
 * The log of the Fetch (NFR-008, NFR-012, ADR-006 §2). It takes only events, counts, closed outcome kinds and an HTTP
 * status, never a text, so no session, uid, reference, continuation key or text of Enable Banking can reach a log line,
 * with Detailed Logging on or off. It lives apart from the code that reveals a value (ADR-006 L-04).
 */
internal class FetchLog(private val log: SlussenLog) {
    fun outcome(response: FetchResponse) {
        val read = response.bankAccounts.count { it.result is BankAccountResult.Read }
        val facts = listOf(LogFact.Count(read.toLong()), LogFact.Count(response.bankAccounts.size.toLong()))
        when (val source = response.sourceOutcome) {
            SourceOutcome.Completed -> log.event(EnableBankingFetchEvent.FetchCompleted, *facts.toTypedArray())
            else -> log.event(EnableBankingFetchEvent.FetchStopped, LogFact.Value(kindOf(source)), *facts.toTypedArray())
        }
    }

    /** With Detailed Logging on: the HTTP status Enable Banking answered with, a number only. */
    fun answered(status: Int) {
        log.detail(EnableBankingFetchEvent.FetchAnswered) { "answered with HTTP status $status" }
    }

    private fun kindOf(source: SourceOutcome): Enum<*> = when (source) {
        SourceOutcome.Completed -> Stop.Completed
        is SourceOutcome.AskedToWait -> Stop.AskedToWait
        SourceOutcome.RequestLimitWarning -> Stop.RequestLimitWarning
        is SourceOutcome.TransientFailure -> source.kind
        SourceOutcome.CredentialsRefused -> Stop.CredentialsRefused
        is SourceOutcome.StoppedByGate -> source.reason
    }

    private enum class Stop { Completed, AskedToWait, RequestLimitWarning, CredentialsRefused }
}
