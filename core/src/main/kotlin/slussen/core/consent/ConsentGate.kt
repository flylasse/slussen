package slussen.core.consent

import slussen.core.operation.OperationPermit
import slussen.core.port.BackOffCause
import slussen.core.port.FetchStore
import slussen.core.port.StoredBackOff
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestSummary
import java.time.Clock
import java.time.Instant

/** When the Back-off with cause Request To Wait of the Bank [bankId] that holds at [now] ends, or null when none holds (UC-001 BR-004). */
internal fun List<StoredBackOff>.bankWaitUntil(bankId: Long, now: Instant): Instant? =
    filter { it.bankId == bankId && it.cause == BackOffCause.RequestToWait && it.endsAt.isAfter(now) }.maxOfOrNull { it.endsAt }

/**
 * The gate of a request of a Bank Consent operation (ADR-001 §4.0, §4.1): it refuses with scope Bank while a Back-off of
 * the Bank [bankId] holds, so that nothing is sent to that Bank, and otherwise asks [dataProvider], the Data Provider's
 * gate, which refuses for a Back-off of the whole Data Provider or a reached Request Limit and counts the request as
 * kind Other with the Operator present. A Bank without a `BANK` row yet ([bankId] null) has no Back-off.
 */
internal class ConsentGate(
    private val dataProvider: RequestGate,
    private val permit: OperationPermit,
    private val dataProviderId: Long,
    private val bankId: Long?,
    private val fetches: FetchStore,
    private val clock: Clock,
) : RequestGate {
    override suspend fun admit(request: RequestSummary): Admission {
        if (!permit.active) return Admission.Refused(RefusalScope.Source, RefusalReason.OperationEnded)
        // A9 of UC-007: a Back-off of the Bank holds for every request of a Bank Consent operation at that Bank (UC-001 BR-004).
        if (bankId != null && fetches.backOffsOf(dataProviderId).bankWaitUntil(bankId, clock.instant()) != null) {
            return Admission.Refused(RefusalScope.Bank, RefusalReason.BackOff)
        }
        return dataProvider.admit(request)
    }
}
