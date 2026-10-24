package slussen.pluginapi.testfixtures

import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.request.RequestTimeout
import slussen.pluginapi.value.Secret
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A request gate for the contract tests (ADR-001 §4.0, §4.4): it records every [RequestSummary] it is asked about and
 * admits each one, or refuses each one with [refusal] when that is given.
 */
public class RecordingRequestGate(private val refusal: Admission.Refused? = null) : RequestGate {
    private val asked = CopyOnWriteArrayList<RequestSummary>()

    /** Every request the plug-in asked admission for, in order. */
    public val requests: List<RequestSummary> get() = asked.toList()

    override suspend fun admit(request: RequestSummary): Admission {
        asked += request
        return refusal ?: Admission.Allowed
    }

    public companion object {
        /** A gate that refuses every request for the whole service, as a reached Request Limit does. */
        public fun refusing(): RecordingRequestGate =
            RecordingRequestGate(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached))
    }
}

/** A [RequestContext] for the contract tests: the gate, the request timeout and the stored credential, if any. */
public class TestRequestContext(
    override val gate: RequestGate = RecordingRequestGate(),
    timeout: Duration = Duration.ofSeconds(5),
    override val credential: Secret? = null,
) : RequestContext {
    override val timeout: RequestTimeout = RequestTimeout(timeout)
}
