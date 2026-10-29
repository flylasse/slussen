package slussen.core.status

import slussen.core.credential.CredentialStatusReader
import slussen.core.fetch.BalanceInOtherCurrency
import slussen.core.firstupload.FirstUploadMatchingNotice
import slussen.core.journal.DecisionJournal
import slussen.core.port.BackupSizeMonitor
import slussen.core.port.BankAccountType
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ImportStatusStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.StartState
import slussen.core.port.StartedBy
import slussen.core.port.StoredDataProvider
import slussen.core.port.StoredFileFormat
import slussen.core.port.StoredPart
import slussen.core.port.TransactionFileOutcome
import slussen.core.settings.NextThing
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Instant

/** The Blocked Reasons of UC-008 BR-006. */
enum class BlockedReason {
    MissingBankConsent,
    ExpiredBankConsent,
    UnrecognisedBankAccount,
    WaitingForProvider,
    FailedFetch,
    RequestLimitReached,
    AccessLimitReached,
    CredentialsRefused,
    CredentialsMissing,
    CurrencyMismatch,
    UploadsWaitForFirstUploadMatching,

    /** UC-001 A19: "not delivered by the Data Provider". */
    NotDelivered,

    /** UC-001 A20: "the Bank's sign-in at the Data Provider needs you". */
    SignInNeeded,
}

/** The action that removes a Blocked Reason (UC-008 BR-006, A6). */
enum class Remedy {
    /** Grant Bank Consent (UC-007). */
    GrantBankConsent,

    /** Confirm or identify the Bank Account (UC-009). */
    IdentifyBankAccount,

    /** No action: the reason ends at the time given with it. */
    WaitUntilItEnds,

    /** Fetch again (UC-001). */
    FetchAgain,

    /** Enter the credentials (UC-014). */
    EnterCredentials,

    /** Move the Account Mapping (UC-009 A8). */
    MoveAccountMapping,

    /** "Start First Upload Matching": compare with the Budget App and confirm (BR-006, BR-009, A9). */
    StartFirstUploadMatching,

    /** UC-001 A20: sign in to the Bank again at the Data Provider, then fetch again. */
    SignInAtDataProvider,
}

/**
 * One Blocked Reason of a Source, a Bank Account or a Budget, with its remedy and the time it ends or happened, and the
 * Data Provider it names when its words name one (UC-001 A19, A20).
 */
data class Blocked(
    val reason: BlockedReason,
    val remedy: Remedy,
    val at: Instant?,
    val subject: String,
    val dataProviderName: String? = null,
)

/** Source Health: only the outcome of the last Fetch or Transaction File import (BR-003). */
sealed interface SourceHealth {
    data class FetchHealth(val outcome: FetchOutcome, val at: Instant, val startedBy: StartedBy) : SourceHealth

    data class BankAccountFetchHealth(val outcome: FetchResultOutcome, val at: Instant, val startedBy: StartedBy) :
        SourceHealth

    data class FileImportHealth(val outcome: TransactionFileOutcome, val at: Instant) : SourceHealth

    /** No Fetch or import yet. */
    data object NoneYet : SourceHealth
}

/** How each Account Balance compares with its Budget Account (BR-004, BR-005, A2, A3, A4). */
sealed interface BalanceComparison {
    /** BR-005: the Account Balance minus the Budget Account's balance, with the time each was read. */
    data class Compared(
        val accountBalance: Amount,
        val budgetAccountBalance: Amount,
        val difference: Amount,
        val accountBalanceReadAt: Instant,
        val budgetAccountBalanceReadAt: Instant,
    ) : BalanceComparison

    /** A2: older than the outdated-balance limit; the difference is hidden. */
    data class Outdated(
        val accountBalance: Amount,
        val budgetAccountBalance: Amount?,
        val accountBalanceReadAt: Instant,
    ) : BalanceComparison

    /** A3: the Bank Account's currency differs from its Budget's; the difference is hidden. */
    data class CurrencyMismatch(val bankAccountCurrency: CurrencyCode, val budgetCurrency: CurrencyCode) : BalanceComparison

    /** A4: which balance is not known; no difference. */
    data class NotKnown(
        val accountBalanceKnown: Boolean,
        val budgetAccountBalanceKnown: Boolean,
        val accountBalance: Amount?,
        val budgetAccountBalance: Amount?,
    ) : BalanceComparison
}

/** The Bank Transactions not yet uploaded: Eligible Transactions and those waiting for a decision (BR-003). */
data class NotYetUploaded(val eligible: Int, val waitingForDecision: Int)

/** One Bank Account of the selected Budget (steps 5-8). */
data class BankAccountStatus(
    val bankAccountId: Long,
    val name: String,
    val type: BankAccountType,
    val health: SourceHealth,
    val freshness: Instant?,
    val notYetUploaded: NotYetUploaded,
    val balance: BalanceComparison,
    val blocked: List<Blocked>,
    /** The Data Provider that fetches the Bank Account, whose Fetch removes a failed Fetch (BR-006, A6); null for a file-imported one. */
    val dataProviderId: Long?,
    /** UC-001 A21: the balance its last Fetch reported in another currency and did not store, or null. */
    val balanceInOtherCurrency: BalanceInOtherCurrency? = null,
)

/** One Source of the selected Budget (steps 5-6). */
data class SourceStatus(
    val name: String,
    val isFileFormat: Boolean,
    val health: SourceHealth,
    val freshness: Instant?,
    val notYetUploaded: NotYetUploaded,
    val blocked: List<Blocked>,
    /** A Data Provider that reports only the Account Balance of the Budget's file-imported Bank Accounts (UC-001 BR-011). */
    val balanceOnly: Boolean = false,
)

/** The overview line of one Budget: counts and blocked states only (step 1, BR-007). */
data class BudgetSummary(
    val budgetId: Long,
    val name: String,
    val currency: CurrencyCode,
    val notYetUploaded: NotYetUploaded,
    val blocked: List<Blocked>,
    val lastReadAt: Instant?,
)

/** A Bank Consent that expires within the consent warning lead time (step 2, BR-002), with its renew action. */
data class ExpiringBankConsent(val bankName: String, val dataProviderName: String, val expiresAt: Instant)

/** A7, BR-010: the backed-up data nears Android's backup limit. */
data class BackupWarning(val backedUpBytes: Long, val limitBytes: Long)

/** A1: a stored part that could not be read, and what Slussen started from instead. */
data class UnreadableNotice(val part: StoredPart, val startedFromLastGoodCopy: Boolean)

/** What opening Slussen shows (steps 1-3, A1, A7, A8). */
data class Overview(
    val unreadable: List<UnreadableNotice>,
    /** A8, BR-009: Uploads wait for First Upload Matching; null while none is open. */
    val firstUploadMatching: FirstUploadMatchingNotice?,
    val backupWarning: BackupWarning?,
    val expiringBankConsents: List<ExpiringBankConsent>,
    val budgets: List<BudgetSummary>,
    /** A10: no Bank Account is added yet. */
    val noBankAccountAdded: Boolean,
    val budgetAppName: String,
    /** A10, BR-011: while no Bank Account is added, the one card that leads to Settings, with the next thing to do; else null. */
    val firstRun: FirstRunCard? = null,
)

/** A10, BR-011: the only card shown while no Bank Account is added; it leads to Settings and names [next] (UC-013 BR-008). */
data class FirstRunCard(val next: NextThing?)

/** The workspace of one Budget (steps 4-8, BR-007). */
data class BudgetWorkspace(
    val summary: BudgetSummary,
    val sources: List<SourceStatus>,
    val bankAccounts: List<BankAccountStatus>,
    /** A8, BR-009 (`budget.html#recovery-blocked`): Uploads of this Budget wait for First Upload Matching; null while none is open. */
    val firstUploadMatching: FirstUploadMatchingNotice?,
    val budgetAppName: String,
)

/** One of the most recent Fetches (A5, BR-008). */
data class RecentFetch(val sourceName: String, val startedAt: Instant, val startedBy: StartedBy, val outcome: FetchOutcome)
