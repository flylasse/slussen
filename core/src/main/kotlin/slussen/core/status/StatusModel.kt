package slussen.core.status

import slussen.core.credential.CredentialStatusReader
import slussen.core.journal.DecisionJournal
import slussen.core.port.BackupSizeMonitor
import slussen.core.port.BankAccountType
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ImportStatusStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.RecoveryCause
import slussen.core.port.RecoveryStore
import slussen.core.port.StartState
import slussen.core.port.StartedBy
import slussen.core.port.StoredDataProvider
import slussen.core.port.StoredFileFormat
import slussen.core.port.StoredPart
import slussen.core.port.TransactionFileOutcome
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
    RecoveryCheckOpen,
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

    /** Compare with the Budget App and confirm the Recovery Check (BR-009). */
    CompareAndConfirm,
}

/** One Blocked Reason of a Source, a Bank Account or a Budget, with its remedy and the time it ends or happened. */
data class Blocked(val reason: BlockedReason, val remedy: Remedy, val at: Instant?, val subject: String)

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
)

/** One Source of the selected Budget (steps 5-6). */
data class SourceStatus(
    val name: String,
    val isFileFormat: Boolean,
    val health: SourceHealth,
    val freshness: Instant?,
    val notYetUploaded: NotYetUploaded,
    val blocked: List<Blocked>,
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

/** A8, BR-009: Uploads are blocked until the Operator confirms the Recovery Check, and why. */
data class RecoveryNotice(val cause: RecoveryCause, val openedAt: Instant)

/** A7, BR-010: the backed-up data nears Android's backup limit. */
data class BackupWarning(val backedUpBytes: Long, val limitBytes: Long)

/** A1: a stored part that could not be read, and what Slussen started from instead. */
data class UnreadableNotice(val part: StoredPart, val startedFromLastGoodCopy: Boolean)

/** What opening Slussen shows (steps 1-3, A1, A7, A8). */
data class Overview(
    val unreadable: List<UnreadableNotice>,
    val recoveryCheck: RecoveryNotice?,
    val backupWarning: BackupWarning?,
    val expiringBankConsents: List<ExpiringBankConsent>,
    val budgets: List<BudgetSummary>,
    /** The precondition is not met: no served Budget has a mapped Bank Account yet (UC-013). */
    val noBankAccountAdded: Boolean,
    val budgetAppName: String,
)

/** The workspace of one Budget (steps 4-8, BR-007). */
data class BudgetWorkspace(
    val summary: BudgetSummary,
    val sources: List<SourceStatus>,
    val bankAccounts: List<BankAccountStatus>,
)

/** One of the most recent Fetches (A5, BR-008). */
data class RecentFetch(val sourceName: String, val startedAt: Instant, val startedBy: StartedBy, val outcome: FetchOutcome)

/** The outcome of asking to confirm the Recovery Check (A9). */
sealed interface RecoveryConfirmation {
    /** No Recovery Check is open. */
    data object NoneOpen : RecoveryConfirmation

    /**
     * A9 step 1: Compare With the Budget App (UC-003) must run first, and no slice has built it yet (O-P2-09); the
     * Recovery Check stays open.
     */
    data object ComparisonNotAvailable : RecoveryConfirmation
}
