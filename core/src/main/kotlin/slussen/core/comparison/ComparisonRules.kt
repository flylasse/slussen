package slussen.core.comparison

import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.ComparisonConclusions
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
import slussen.core.port.TransferDecision
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.transfer.DetectedTransfer
import slussen.core.transfer.TransferCandidate
import slussen.core.transfer.TransferDetection
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The days a Comparison judges by: the Upload Window, the file upload window, the transfer pairing days and the Lonely
 * Transfer grace period (Operator Settings, UC-010), the Budget App's Matching Date Window (declared by its part,
 * C-009), and today in the device's time zone.
 */
data class ComparisonWindows(
    val uploadWindowDays: Int,
    val fileUploadWindowDays: Int,
    val matchingDateWindowDays: Int,
    val transferPairingDays: Int,
    val lonelyTransferGraceDays: Int,
    val today: LocalDate,
) {
    companion object {
        fun of(settings: OperatorSettings, matchingDateWindowDays: Int, today: LocalDate) = ComparisonWindows(
            uploadWindowDays = settings.uploadWindowDays,
            fileUploadWindowDays = settings.fileUploadWindowDays,
            matchingDateWindowDays = matchingDateWindowDays,
            transferPairingDays = settings.transferPairingDays,
            lonelyTransferGraceDays = settings.lonelyTransferGraceDays,
            today = today,
        )
    }
}

/** The statuses of a doubtful Bank Transaction, which it keeps also as a Transfer Leg (UC-003 BR-001, UC-005 BR-013). */
private val DOUBTFUL = setOf(TransactionStatus.NeedsReview, TransactionStatus.PossibleImportByOtherPath)

/**
 * The rules of UC-003 that give a Bank Transaction its Transaction Status, in the order of BR-001, as far as the
 * transactions plan's Phases 7a and 7b build them: with the Transfers of step 4 (UC-005, `slussen.core.transfer`), and
 * without Review Decisions (BR-009, BR-010) and Sent Records (BR-017, BR-021), which come with later initiatives. Pure:
 * O-5 applies them to stored data only.
 */
object ComparisonRules {
    /** The statuses the rules after a Transfer Leg in BR-001 give: no rule before it concluded the Bank Transaction. */
    private val AFTER_TRANSFER_LEG = setOf(TransactionStatus.MatchForTypedEntry, TransactionStatus.New)

    // UC-003 BR-016: The first read covers today back by the longest of the fetch period, the Upload Window and the file upload window, plus the Matching Date Window.
    /** The first day of the first read of a Budget (BR-016); every later read takes all changes since the previous one. */
    fun readFrom(settings: OperatorSettings, matchingDateWindowDays: Int, today: LocalDate): LocalDate {
        val longest = maxOf(settings.fetchPeriodDays, settings.uploadWindowDays, settings.fileUploadWindowDays)
        return today.minusDays((longest + matchingDateWindowDays).toLong())
    }

    /**
     * Steps 2-4: the Transaction Status of each stored Bank Transaction of a Bank Account that is not a Retired Account
     * and is mapped into one of the [data]'s Budgets, by its id, and the Transfers among them. A Bank Transaction stored
     * after its Budget's last successful read, or before any, gets none: it is not compared yet (A1, S-T-08). A Transfer
     * Leg of a Transfer the Operator decided on is left as it is stored: Transfer Decisions come with a later initiative.
     */
    fun conclude(data: ComparisonData, windows: ComparisonWindows): ComparisonConclusions {
        val statuses = mutableMapOf<Long, TransactionStatus>()
        val transfers = mutableListOf<DetectedTransfer>()
        val decided = data.transfers.filter { it.decision != TransferDecision.Proposed }.map { it.id }.toSet()
        data.budgets.forEach { budget ->
            val lastReadAt = budget.lastReadAt ?: return@forEach
            val accountIds = budget.budgetAccounts.map { it.id }.toSet()
            // UC-003 BR-014: The Bank Transactions of a Retired Account are not compared.
            val bankAccounts = data.bankAccounts.filter { !it.retired && it.mapping?.budgetAccountId in accountIds }
            val candidates = mutableListOf<TransferCandidate>()
            bankAccounts.forEach { bankAccount ->
                val budgetAccountId = bankAccount.mapping!!.budgetAccountId
                val held = data.budgetAppTransactions.filter { it.budgetAccountId == budgetAccountId }
                data.bankTransactions
                    .filter { it.bankAccountId == bankAccount.id }
                    // UC-003 BR-001: a Bank Transaction stored since its Budget's last successful read has no Transaction Status yet (A1).
                    .filterNot { it.firstSeenAt.isAfter(lastReadAt) }
                    .filterNot { it.transferId in decided }
                    .forEach { transaction ->
                        val status = statusOf(transaction, bankAccount, budget, held, windows)
                        statuses[transaction.id] = status
                        // UC-005 BR-002: Whether two Bank Transactions form a Transfer does not depend on the Upload Window or on any other rule that gives a Bank Transaction its Transaction Status.
                        candidates += TransferCandidate(transaction, bankAccount, earlier = status.takeUnless { it in AFTER_TRANSFER_LEG })
                    }
            }
            // UC-005 BR-001: Slussen always detects Transfers; no setting switches detection off.
            val detected = TransferDetection.detect(
                budget, bankAccounts, candidates, data.transferDescriptions,
                windows.transferPairingDays, windows.lonelyTransferGraceDays, windows.today,
            )
            // UC-003 BR-001: The first rule that applies gives the Transaction Status: a Transfer Leg follows its Transfer before a match for a Typed Entry, and keeps the status an earlier rule gave it.
            statuses += detected.statuses
            transfers += detected.transfers
        }
        return ComparisonConclusions(statuses, TransferDetection.toStore(transfers, data.transfers))
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
     * Whether [transaction] of [bankAccount], mapped into [budget], is an Eligible Transaction. The Upload Window and the
     * Start Date are already in its status (BR-011, BR-018); [transferLegs] are the stored Bank Transactions that are a
     * Transfer Leg, of every Bank Account. Outcome Unknown comes with the phase that builds Sent Records.
     */
    fun isEligible(
        transaction: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget?,
        transferLegs: List<StoredBankTransaction>,
    ): Boolean =
        !bankAccount.retired &&
            budget != null &&
            // UC-003 BR-013: A Bank Account whose currency differs from its Budget's is blocked from Upload.
            bankAccount.currency == budget.currency &&
            when (transaction.status) {
                TransactionStatus.New, TransactionStatus.MatchForTypedEntry -> true
                TransactionStatus.TransferLeg -> writesItsTransfer(transaction, transferLegs)
                // UC-005 BR-010: The Transfer Legs of a Guessed Transfer are not eligible until the Operator confirms the Transfer or chooses another Budget Account.
                else -> false
            }

    // UC-003 BR-014: A Transfer Leg is an Eligible Transaction only when it writes its Transfer and no Transfer Leg of that Transfer waits for a Review Decision or has a status that an earlier rule gave it.
    /** Whether [transaction], with the status Transfer Leg, is the Transfer Leg its Transfer is written through. */
    private fun writesItsTransfer(transaction: StoredBankTransaction, transferLegs: List<StoredBankTransaction>): Boolean {
        val transferId = transaction.transferId ?: return false
        val otherLegs = transferLegs.filter { it.transferId == transferId && it.id != transaction.id }
        // UC-005 BR-013: A Transfer with a Transfer Leg that is Needs Review or Possible Import By Other Path is in no Upload Plan, from either side.
        if (otherLegs.any { it.status in DOUBTFUL }) return false
        // UC-005 BR-010: While a Transfer has a Transfer Leg to which an earlier rule of the Comparison gave its Transaction Status, the whole Transfer is in no Upload Plan, from either side.
        if (otherLegs.any { it.status != TransactionStatus.TransferLeg }) return false
        // UC-006 BR-009: A Transfer is written through exactly one Transfer Leg, the outflow side when both Transfer Legs are present.
        return otherLegs.isEmpty() || transaction.amount.thousandths < 0
    }

    /**
     * The counts of [bankAccount]: its Eligible Transactions and its Bank Transactions and Unreadable Transactions that wait
     * for a decision. A Bank Transaction not compared yet counts as neither (UC-003 A1, S-T-08). [transferLegs] are the
     * stored Bank Transactions that are a Transfer Leg, of every Bank Account.
     */
    fun countsOf(
        bankAccount: StoredBankAccount,
        budget: StoredBudget?,
        transactions: List<StoredBankTransaction>,
        unreadable: List<StoredUnreadableTransaction>,
        transferLegs: List<StoredBankTransaction>,
    ): StoredTransactionCounts = StoredTransactionCounts(
        bankAccountId = bankAccount.id,
        eligible = transactions.count { isEligible(it, bankAccount, budget, transferLegs) },
        waitingForDecision = if (bankAccount.retired) {
            0
        } else {
            transactions.count { it.status in WAITING } + unreadable.count { it.status == UnreadableTransactionStatus.NeedsReview }
        },
    )
}
