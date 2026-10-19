package slussen.core.port

import java.time.Instant

/** The `Values:` of `PROVIDER_REQUEST.request_kind`. */
enum class ProviderRequestKind(val text: String) {
    Transactions("Transactions"),
    Balance("Balance"),
    Other("Other"),
}

/** One request Slussen is about to send to a Data Provider (`PROVIDER_REQUEST`). */
data class NewProviderRequest(
    val dataProviderId: Long,
    val bankAccountId: Long?,
    val kind: ProviderRequestKind,
    val unattended: Boolean,
    val sentAt: Instant,
)

/** `PROVIDER_REQUEST` (entity model) as stored. */
data class StoredProviderRequest(
    val dataProviderId: Long,
    val bankAccountId: Long?,
    val kind: ProviderRequestKind,
    val unattended: Boolean,
    val sentAt: Instant,
)

/**
 * The Provider Requests that count against a Data Provider's Request Limit (UC-001 BR-007), kept durably so that a
 * crash after admission counts the request rather than losing it (ADR-001 §4.0).
 */
interface ProviderRequestStore {
    /**
     * In one transaction: counts the Provider Requests of the request's Data Provider sent after [windowStart] and, when
     * that number is below [requestLimit], stores [request] and returns true; otherwise stores nothing and returns
     * false.
     */
    suspend fun admitWithinLimit(request: NewProviderRequest, windowStart: Instant, requestLimit: Int): Boolean

    /** The Provider Requests sent after [since], for the import status (UC-008 BR-006). */
    suspend fun sentSince(since: Instant): List<StoredProviderRequest>
}
