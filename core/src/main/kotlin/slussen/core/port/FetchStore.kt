package slussen.core.port

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
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

/** The balance of one Bank Account as its Data Provider reported it in a Fetch (`ACCOUNT_BALANCE`, UC-001 step 8). */
data class NewAccountBalance(
    val amount: Amount,
    /** The reported currency (`ACCOUNT_BALANCE.currency`). */
    val currency: CurrencyCode,
    /** When the Data Provider reported it. */
    val reportedAt: Instant,
)

/**
 * What O-1 stores for one identified Bank Account a Fetch read (ADR-002 §3; UC-001 steps 8, 11, A8): its Fetch Result
 * (`FETCH_RESULT`) and, when the Data Provider reported one, its Account Balance in place of the one stored before
 * (`ACCOUNT_BALANCE`, the most recent balance). [balance] is null when nothing new is stored: the earlier Account
 * Balance stays unchanged (A8). The Bank Transactions and Unreadable Transactions of O-1 join it with the slice that
 * builds UC-001 steps 7-10 (O-P2-54).
 */
data class BankAccountDelivery(
    /** The recorded Fetch the result belongs to ([FetchStore.recordFetch]). */
    val fetchId: Long,
    val bankAccountId: Long,
    val outcome: FetchResultOutcome,
    /** `FETCH_RESULT.transaction_count`: the booked Bank Transactions delivered. */
    val transactionCount: Int,
    val balance: NewAccountBalance?,
)

/**
 * The storage of the Fetches, Back-offs, Fetch Results and Account Balances of Fetch Transactions (UC-001) in
 * `slussen.db` (ADR-002 §1). Each method is one transaction.
 */
interface FetchStore {
    /** The Fetches of [dataProviderId] that are kept, most recent first, each with its Fetch Results. */
    suspend fun fetchesOf(dataProviderId: Long): List<StoredFetch>

    /**
     * Records [fetch] and keeps only the [keep] most recent Fetches of all Data Providers, with their Fetch Results, in
     * one transaction (`FETCH` constraint, `OPERATOR_SETTINGS.fetch_history_size`). Returns the id of the new Fetch.
     */
    suspend fun recordFetch(fetch: NewFetch, keep: Int): Long

    /**
     * O-1 `FetchStore.storeBankAccountDelivery` (ADR-002 §3), as far as this build stores it: for one identified Bank
     * Account, its Fetch Result for the Fetch [BankAccountDelivery.fetchId] and, when one was reported, its Account
     * Balance in place of the stored one, all or nothing (UC-001 steps 8, 11, A8).
     */
    suspend fun storeBankAccountDelivery(delivery: BankAccountDelivery)

    /** The Back-offs of [dataProviderId], also those that have ended. */
    suspend fun backOffsOf(dataProviderId: Long): List<StoredBackOff>

    suspend fun startBackOff(backOff: NewBackOff)
}
