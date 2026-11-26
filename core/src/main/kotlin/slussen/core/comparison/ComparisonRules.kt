package slussen.core.comparison

import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.ComparisonData
import slussen.core.port.EnteredBy
import slussen.core.port.OperatorSettings
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.UnreadableTransactionStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The days a Comparison judges by: the Upload Window and the file upload window (Operator Settings, UC-010), the Budget
 * App's Matching Date Window (declared by its part, C-009), and today in the device's time zone.
 */
data class ComparisonWindows(
    val uploadWindowDays: Int,
    val fileUploadWindowDays: Int,
    val matchingDateWindowDays: Int,
    val today: LocalDate,
) {
    companion object {
        fun of(settings: OperatorSettings, matchingDateWindowDays: Int, today: LocalDate) =
            ComparisonWindows(settings.uploadWindowDays, settings.fileUploadWindowDays, matchingDateWindowDays, today)
    }
}

/**
 * The rules of UC-003 that give a Bank Transaction its Transaction Status, in the order of BR-001, as far as the
 * transactions plan's Phase 7a builds them: without Review Decisions (BR-009, BR-010), Sent Records (BR-017, BR-021)
 * and Transfers (step 4, UC-005), which come with later phases. Pure: O-5 applies them to stored data only.
 */
object ComparisonRules {
    // UC-003 BR-016: The first read covers today back by the longest of the fetch period, the Upload Window and the file upload window, plus the Matching Date Window.
    /** The first day of the first read of a Budget (BR-016); every later read takes all changes since the previous one. */
    fun readFrom(settings: OperatorSettings, matchingDateWindowDays: Int, today: LocalDate): LocalDate {
        val longest = maxOf(settings.fetchPeriodDays, settings.uploadWindowDays, settings.fileUploadWindowDays)
        return today.minusDays((longest + matchingDateWindowDays).toLong())
    }

    /**
     * Steps 2-3: the Transaction Status of each stored Bank Transaction of a Bank Account that is not a Retired Account
     * and is mapped into one of the [data]'s Budgets, by its id. A Bank Transaction stored after its Budget's last
     * successful read, or before any, gets none: it is not compared yet (A1, S-T-08).
     */
    fun conclude(data: ComparisonData, windows: ComparisonWindows): Map<Long, TransactionStatus> {
        val statuses = mutableMapOf<Long, TransactionStatus>()
        data.budgets.forEach { budget ->
            val lastReadAt = budget.lastReadAt ?: return@forEach
            val accountIds = budget.budgetAccounts.map { it.id }.toSet()
            // UC-003 BR-014: The Bank Transactions of a Retired Account are not compared.
            data.bankAccounts.filter { !it.retired && it.mapping?.budgetAccountId in accountIds }.forEach { bankAccount ->
                val budgetAccountId = bankAccount.mapping!!.budgetAccountId
                val held = data.budgetAppTransactions.filter { it.budgetAccountId == budgetAccountId }
                data.bankTransactions
                    .filter { it.bankAccountId == bankAccount.id }
                    // UC-003 BR-001: a Bank Transaction stored since its Budget's last successful read has no Transaction Status yet (A1).
                    .filterNot { it.firstSeenAt.isAfter(lastReadAt) }
                    .forEach { statuses[it.id] = statusOf(it, bankAccount, budget, held, windows) }
            }
        }
        return statuses
    }

    // UC-003 BR-001: The first rule that applies gives the Transaction Status.
    /**
     * The Transaction Status of [transaction] of [bankAccount], mapped into [budget], against [held], the stored copy of
     * its Budget Account's Budget App Transactions.
     */
    fun statusOf(
        transaction: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget,
        held: List<StoredBudgetAppTransaction>,
        windows: ComparisonWindows,
    ): TransactionStatus {
        val sameIdentifier = held.filter { it.importIdentifier == transaction.importIdentifier }
        // UC-003 BR-003: A Bank Transaction whose Import Identifier its Budget Account holds is Already In Budget App.
        if (sameIdentifier.any { it.state == BudgetAppTransactionState.Present }) return TransactionStatus.AlreadyInBudgetApp
        // UC-003 BR-004: A Bank Transaction sent and later deleted in the Budget App is Deleted In Budget App.
        if (sameIdentifier.any { it.state == BudgetAppTransactionState.Deleted }) return TransactionStatus.DeletedInBudgetApp
        // UC-003 BR-005: A Bank Transaction the Budget App merged into a Typed Entry after it was sent is Merged In Budget App.
        if (sameIdentifier.any { it.state == BudgetAppTransactionState.Merged }) return TransactionStatus.MergedInBudgetApp
        // UC-003 BR-018: A Bank Transaction dated before the Start Date of its Bank Account is Before Start Date.
        bankAccount.startDate?.let { startDate -> if (transaction.bookedDate.isBefore(startDate)) return TransactionStatus.BeforeStartDate }
        // UC-003 BR-011: Outside the Upload Window, or the file upload window for a file-imported Bank Account, a Bank Transaction is Too Old or In The Future.
        val windowDays = if (bankAccount.fileFormatId != null) windows.fileUploadWindowDays else windows.uploadWindowDays
        if (transaction.bookedDate.isAfter(windows.today)) return TransactionStatus.InTheFuture
        if (transaction.bookedDate.isBefore(windows.today.minusDays(windowDays.toLong()))) return TransactionStatus.TooOld
        // UC-003 BR-012: A Bank Transaction without a Stable Reference is Needs Review.
        if (transaction.stableReference == null) return TransactionStatus.NeedsReview
        // UC-003 BR-013: No amount is ever converted: a Bank Account in another currency than its Budget's is compared by nothing that compares amounts.
        if (bankAccount.currency != budget.currency) return TransactionStatus.New
        val resembling = held.filter { candidate ->
            candidate.state == BudgetAppTransactionState.Present &&
                candidate.amount == transaction.amount &&
                abs(ChronoUnit.DAYS.between(candidate.transactionDate, transaction.bookedDate)) <= windows.matchingDateWindowDays
        }
        // UC-003 BR-008: A Bank Transaction resembling an Import of Slussen in a form it does not support is Needs Review.
        if (resembling.any { it.enteredBy == EnteredBy.SlussenUnsupportedFormat }) return TransactionStatus.NeedsReview
        // UC-003 BR-007: A Bank Transaction resembling an Import by an Other Import Path is Possible Import By Other Path.
        if (resembling.any { it.enteredBy == EnteredBy.OtherImportPath }) return TransactionStatus.PossibleImportByOtherPath
        // A Transfer Leg follows its Transfer (UC-005 BR-010): Transfer detection comes with transactions plan Phase 7b.
        // UC-003 BR-006: A Bank Transaction for which its Budget Account holds a Typed Entry of the same amount within the Matching Date Window is Match For Typed Entry.
        if (resembling.any { it.enteredBy == EnteredBy.TypedEntry }) return TransactionStatus.MatchForTypedEntry
        return TransactionStatus.New
    }
}

/** UC-003 BR-014 and the counts of UC-008 BR-003: Eligible Transactions and those waiting for a decision. Pure. */
object EligibleTransactions {
    /** The statuses of a Bank Transaction that waits for the Operator's decision (UC-001 BR-014, as far as this build gives them). */
    private val WAITING = setOf(
        TransactionStatus.PossibleImportByOtherPath,
        TransactionStatus.NeedsReview,
        TransactionStatus.DecisionNoLongerHolds,
        TransactionStatus.GuessedTransfer,
        TransactionStatus.TransferCounterpartChanged,
    )

    // UC-003 BR-014: An Eligible Transaction is New or Match For Typed Entry, in its Upload Window, not before its Start Date, of a Bank Account that is neither blocked nor a Retired Account.
    /**
     * Whether a Bank Transaction with [status] of [bankAccount], mapped into [budget], is an Eligible Transaction. The
     * Upload Window and the Start Date are already in its status (BR-011, BR-018); Outcome Unknown and the Transfer Leg
     * that writes its Transfer come with the phases that build Sent Records and Transfers.
     */
    fun isEligible(status: TransactionStatus?, bankAccount: StoredBankAccount, budget: StoredBudget?): Boolean =
        (status == TransactionStatus.New || status == TransactionStatus.MatchForTypedEntry) &&
            !bankAccount.retired &&
            budget != null &&
            // UC-003 BR-013: A Bank Account whose currency differs from its Budget's is blocked from Upload.
            bankAccount.currency == budget.currency

    /**
     * The counts of [bankAccount]: its Eligible Transactions and its Bank Transactions and Unreadable Transactions that wait
     * for a decision. A Bank Transaction not compared yet counts as neither (UC-003 A1, S-T-08).
     */
    fun countsOf(
        bankAccount: StoredBankAccount,
        budget: StoredBudget?,
        transactions: List<StoredBankTransaction>,
        unreadable: List<StoredUnreadableTransaction>,
    ): StoredTransactionCounts = StoredTransactionCounts(
        bankAccountId = bankAccount.id,
        eligible = transactions.count { isEligible(it.status, bankAccount, budget) },
        waitingForDecision = if (bankAccount.retired) {
            0
        } else {
            transactions.count { it.status in WAITING } + unreadable.count { it.status == UnreadableTransactionStatus.NeedsReview }
        },
    )
}
