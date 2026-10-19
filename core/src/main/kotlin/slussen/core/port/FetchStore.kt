package slussen.core.port

import java.time.Instant

/** One Fetch as it is recorded (`FETCH`, UC-001 step 11, A1, A2, A3, A5, A6, A7). */
data class NewFetch(
    val dataProviderId: Long,
    val startedAt: Instant,
    val startedBy: StartedBy,
    val outcome: FetchOutcome,
)

/** One Back-off as it is started (`BACK_OFF`, UC-001 A5, A15). */
data class NewBackOff(
    val dataProviderId: Long,
    /** Empty when the whole Data Provider waits. */
    val bankId: Long?,
    val cause: BackOffCause,
    val startsAt: Instant,
    val endsAt: Instant,
)

/**
 * The storage of the Fetches and Back-offs of Fetch Transactions (UC-001) in `slussen.db` (ADR-002 §1). Each method is
 * one transaction. The Bank Transactions, Account Balances and Fetch Results of an identified Bank Account (O-1) come
 * with the slice that builds the Comparison (UC-003).
 */
interface FetchStore {
    /** The Fetches of [dataProviderId] that are kept, most recent first. */
    suspend fun fetchesOf(dataProviderId: Long): List<StoredFetch>

    /**
     * Records [fetch] and keeps only the [keep] most recent Fetches of all Data Providers, in one transaction (`FETCH`
     * constraint, `OPERATOR_SETTINGS.fetch_history_size`).
     */
    suspend fun recordFetch(fetch: NewFetch, keep: Int)

    /** The Back-offs of [dataProviderId], also those that have ended. */
    suspend fun backOffsOf(dataProviderId: Long): List<StoredBackOff>

    suspend fun startBackOff(backOff: NewBackOff)
}
