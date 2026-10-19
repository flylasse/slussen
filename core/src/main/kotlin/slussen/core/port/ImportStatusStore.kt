package slussen.core.port

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

/** Whether the Operator or the Fetch Schedule started a Fetch (`FETCH.started_by`). */
enum class StartedBy(val text: String) {
    Operator("Operator"),
    Schedule("Schedule"),
}

/** The `Values:` of `FETCH.outcome`. */
enum class FetchOutcome(val text: String) {
    Fetched("Fetched"),
    ServedFromFetchCooldown("Served From Fetch Cooldown"),
    WaitingForBackOff("Waiting For Back-off"),
    RequestLimitReached("Request Limit Reached"),
    Failed("Failed"),
    NotConsented("Not Consented"),
    CredentialsRefused("Credentials Refused"),
}

/** The `Values:` of `FETCH_RESULT.outcome`. */
enum class FetchResultOutcome(val text: String) {
    Fetched("Fetched"),
    Failed("Failed"),
    NotConsented("Not Consented"),
    NotRecognised("Not Recognised"),
    AmbiguousBankAccount("Ambiguous Bank Account"),
    AccessLimitReached("Access Limit Reached"),
}

/** `FETCH` with its `FETCH_RESULT`s (entity model), as stored. */
data class StoredFetch(
    val id: Long,
    val dataProviderId: Long,
    val startedAt: Instant,
    val startedBy: StartedBy,
    val outcome: FetchOutcome,
    val results: List<StoredFetchResult>,
)

data class StoredFetchResult(val bankAccountId: Long, val outcome: FetchResultOutcome, val transactionCount: Int)

/** `ACCOUNT_BALANCE` (entity model) as stored. */
data class StoredAccountBalance(
    val bankAccountId: Long,
    val amount: Amount,
    val currency: CurrencyCode,
    val reportedAt: Instant,
)

/** `BANK_CONSENT` (entity model) as stored in `device.db`. */
data class StoredBankConsent(
    val id: Long,
    val bankId: Long,
    val dataProviderId: Long,
    val grantedAt: Instant,
    val expiresAt: Instant,
)

/** The `Values:` of `BACK_OFF.cause`. */
enum class BackOffCause(val text: String) {
    RequestToWait("Request To Wait"),
    RequestLimitWarning("Request Limit Warning"),
}

/** `BACK_OFF` (entity model) as stored. */
data class StoredBackOff(
    val dataProviderId: Long,
    val bankId: Long?,
    val cause: BackOffCause,
    val startsAt: Instant,
    val endsAt: Instant,
)

/** The `Values:` of `TRANSACTION_FILE.outcome`. */
enum class TransactionFileOutcome(val text: String) {
    Imported("Imported"),
    Rejected("Rejected"),
}

/** `TRANSACTION_FILE` (entity model) as stored. */
data class StoredTransactionFile(
    val bankAccountId: Long,
    val importedAt: Instant,
    val outcome: TransactionFileOutcome,
)

/**
 * The Bank Transactions of one Bank Account that are not yet uploaded, as the latest Comparison left them (UC-003
 * BR-014): its Eligible Transactions and those waiting for a decision.
 */
data class StoredTransactionCounts(
    val bankAccountId: Long,
    val eligible: Int,
    val waitingForDecision: Int,
)

/**
 * The stored data of the import status (UC-008) that the Fetches (UC-001), Transaction File imports (UC-002),
 * Comparisons (UC-003) and Bank Consents (UC-007) leave. Opening Slussen reads only this, never a Source or the Budget
 * App (UC-008 BR-001).
 */
interface ImportStatusStore {
    /** The most recent Fetches, most recent first, as many as are kept (UC-008 BR-008). */
    suspend fun recentFetches(): List<StoredFetch>

    suspend fun accountBalances(): List<StoredAccountBalance>

    suspend fun bankConsents(): List<StoredBankConsent>

    suspend fun backOffs(): List<StoredBackOff>

    /** The latest Transaction File import of each file-imported Bank Account. */
    suspend fun latestTransactionFiles(): List<StoredTransactionFile>

    suspend fun transactionCounts(): List<StoredTransactionCounts>
}
