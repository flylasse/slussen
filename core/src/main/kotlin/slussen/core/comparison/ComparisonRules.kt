package slussen.core.comparison

import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.DroppedTransferDecision
import slussen.core.port.EnteredBy
import slussen.core.port.OperatorSettings
import slussen.core.port.ReviewChoice
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredReviewDecision
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferJoin
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.transfer.DetectedTransfer
import slussen.core.transfer.KeptTransfer
import slussen.core.transfer.TransferCandidate
import slussen.core.transfer.TransferDetection
import slussen.core.transfer.TransferRules
import slussen.core.transfer.TransfersDetected
import slussen.core.transfer.TransfersKept
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

/**
 * The statuses of a doubtful Bank Transaction, which it keeps also as a Transfer Leg (UC-003 BR-001, UC-005 BR-013): one
 * that waits for its first Review Decision, and one whose Already Present decision does not hold any more.
 */
private val DOUBTFUL = setOf(TransactionStatus.NeedsReview, TransactionStatus.PossibleImportByOtherPath, TransactionStatus.DecisionNoLongerHolds)

/**
 * What the rules of UC-003 BR-001 conclude for one Bank Transaction by itself, before any Transfer is detected: its
 * [status]; whether a rule that stands before "a Transfer Leg follows its Transfer" gave it ([beforeTransferLeg]), so
 * that it keeps it also as a Transfer Leg (UC-005 BR-010); the status its Review Decision gives the other Transfer
 * Legs of its Transfer ([givesOtherLegs], UC-005 BR-013), null when it gives none; and, when a Re-upload decision makes
 * it a New transaction of its own after it was sent and then deleted or merged, the status it has without that decision
 * ([withoutReUpload]), which it keeps while its Transfer is held back (UC-005 BR-007).
 */
data class OwnStatus(
    val status: TransactionStatus,
    val beforeTransferLeg: Boolean,
    val givesOtherLegs: TransactionStatus? = null,
    val withoutReUpload: TransactionStatus? = null,
)

/**
 * The rules of UC-003 that give a Bank Transaction its Transaction Status, in the order of BR-001: with the Review
 * Decisions (BR-009, BR-010, UC-004 BR-006), the Transfers of step 4 and the Transfer Decisions (UC-005,
 * `slussen.core.transfer`), and without the statuses Sent Records give (BR-017, BR-021), which come with the Uploads. Until then nothing here can tell that a Re-upload decision
 * is used up by the send it caused (UC-004 BR-003): no send exists. Pure: O-5 and O-13 apply them to stored data only.
 */
object ComparisonRules {

    // UC-003 BR-016: The first read covers today back by the longest of the fetch period, the Upload Window and the file upload window, plus the Matching Date Window.
    /** The first day of the first read of a Budget (BR-016); every later read takes all changes since the previous one. */
    fun readFrom(settings: OperatorSettings, matchingDateWindowDays: Int, today: LocalDate): LocalDate =
        today.minusDays((readPeriodDays(settings) + matchingDateWindowDays).toLong())

    /**
     * The longest of the fetch period, the Upload Window and the file upload window (BR-016): the Operator Settings part
     * of the read period, which a change of one of them makes longer or shorter (UC-010 step 5).
     */
    fun readPeriodDays(settings: OperatorSettings): Int = maxOf(settings.fetchPeriodDays, settings.uploadWindowDays, settings.fileUploadWindowDays)

    /**
     * Steps 2-4: the Transaction Status of each stored Bank Transaction of a Bank Account that is not a Retired Account
     * and is mapped into one of the [data]'s Budgets, by its id, and the Transfers among them. A Bank Transaction stored
     * after its Budget's last successful read, or before any, gets none: it is not compared yet (A1, S-T-08). A Transfer
     * the Operator decided on is kept with its Transfer Legs, each of which gets the status UC-005 BR-010 gives it; it is
     * joined by its arriving other side (UC-005 BR-005) and dropped only when its own sides are no longer valid (UC-005
     * BR-015).
     */
    fun conclude(data: ComparisonData, windows: ComparisonWindows): ComparisonConclusions {
        val statuses = mutableMapOf<Long, TransactionStatus>()
        val transfers = mutableListOf<DetectedTransfer>()
        val kept = mutableListOf<KeptTransfer>()
        val dropped = mutableListOf<DroppedTransferDecision>()
        data.budgets.forEach { budget ->
            val concluded = concludeFor(budget, data, windows) ?: return@forEach
            statuses += concluded.statuses
            transfers += concluded.detected.transfers
            kept += concluded.kept.kept
            dropped += concluded.kept.dropped
        }
        // A Transfer whose Transfer Leg lies in another Budget now is judged in both Budgets, with the same answer: it counts once.
        val keptOnce = kept.distinctBy { it.transfer.id }
        return ComparisonConclusions(
            statuses = statuses,
            transfers = TransferDetection.toStore(transfers, data.transfers, keptOnce),
            joins = keptOnce.mapNotNull { keeps -> keeps.joined?.let { TransferJoin(keeps.transfer.id, it.id) } },
            dropped = dropped.distinctBy { it.transfer.id },
        )
    }

    /**
     * Step 4 for the one Budget [budgetId] of [data]: the Transfers that [conclude] detects among its Bank Transactions,
     * with why each Guessed Transfer is guessed, by the same rules on the same stored data, for the screens that show
     * them (UC-005 step 1); nothing is stored. Empty when the Budget has not been read yet.
     */
    fun transfersOf(budgetId: Long, data: ComparisonData, windows: ComparisonWindows): TransfersDetected {
        val budget = data.budgets.firstOrNull { it.id == budgetId } ?: return TransfersDetected()
        return concludeFor(budget, data, windows)?.detected ?: TransfersDetected()
    }

    /** What the rules conclude for one Budget: the statuses, the Transfers detected, and the stored Transfers kept or dropped. */
    private class Concluded(val statuses: Map<Long, TransactionStatus>, val detected: TransfersDetected, val kept: TransfersKept)

    /** The statuses of [budget]'s compared Bank Transactions and the Transfers among them; null before its first read. */
    private fun concludeFor(budget: StoredBudget, data: ComparisonData, windows: ComparisonWindows): Concluded? {
        val lastReadAt = budget.lastReadAt ?: return null
        val decisions = data.reviewDecisions.associateBy { it.storedBankTransaction }
        val statuses = mutableMapOf<Long, TransactionStatus>()
        val accountIds = budget.budgetAccounts.map { it.id }.toSet()
        // UC-003 BR-014: The Bank Transactions of a Retired Account are not compared.
        val bankAccounts = data.bankAccounts.filter { !it.retired && it.mapping?.budgetAccountId in accountIds }
        val compared = linkedMapOf<Long, TransferCandidate>()
        bankAccounts.forEach { bankAccount ->
            val budgetAccountId = bankAccount.mapping!!.budgetAccountId
            val held = data.budgetAppTransactions.filter { it.budgetAccountId == budgetAccountId }
            data.bankTransactions
                .filter { it.bankAccountId == bankAccount.id }
                // UC-003 BR-001: a Bank Transaction stored since its Budget's last successful read has no Transaction Status yet (A1).
                .filterNot { it.firstSeenAt.isAfter(lastReadAt) }
                .forEach { transaction ->
                    val own = ownStatusOf(transaction, bankAccount, budget, held, windows, decisions[transaction.id], data.budgetAppTransactions)
                    statuses[transaction.id] = own.status
                    // UC-005 BR-002: Whether two Bank Transactions form a Transfer does not depend on the Upload Window or on any other rule that gives a Bank Transaction its Transaction Status.
                    compared[transaction.id] = TransferCandidate(
                        transaction, bankAccount, earlier = own.status.takeIf { own.beforeTransferLeg }, givesOtherLegs = own.givesOtherLegs,
                        withoutReUpload = own.withoutReUpload,
                    )
                }
        }
        // UC-005 BR-015: A Transfer that has a Transfer Decision keeps its decision, its Transfer Legs and its two Budget Accounts whenever Slussen detects Transfers again.
        val kept = TransferRules.keep(
            transfers = keptAsStored(data, compared.keys, accountIds),
            allTransactions = data.bankTransactions,
            bankAccounts = data.bankAccounts,
            sentRecords = data.sentRecords,
            compared = compared,
            sides = TransferDetection.sidesOf(budget, bankAccounts),
            pairingDays = windows.transferPairingDays,
        )
        val legsOfKept = kept.legIds
        val free = compared.values.filterNot { it.transaction.id in legsOfKept }
        // UC-005 BR-005: A Bank Transaction that was uploaded as an ordinary transaction is no Transfer Leg and never the other side of a Transfer.
        val (uploadedAsOrdinary, candidates) = free.partition { TransferRules.uploadedAsOrdinary(it.transaction.id, data.sentRecords) }
        // UC-005 BR-001: Slussen always detects Transfers; no setting switches detection off.
        val detected = TransferDetection.detect(
            budget, bankAccounts, candidates, data.transferDescriptions,
            windows.transferPairingDays, windows.lonelyTransferGraceDays, windows.today, uploadedAsOrdinary,
        )
        // UC-003 BR-001: The first rule that applies gives the Transaction Status: a Transfer Leg follows its Transfer before a match for a Typed Entry, and keeps the status an earlier rule gave it.
        statuses += detected.statuses
        // UC-005 BR-010: A Transfer Leg of a Transfer that has a Transfer Decision gets its status from that decision; after Split it keeps its status as an ordinary Bank Transaction, and Slussen forms no other Transfer from it.
        statuses += kept.statuses
        return Concluded(statuses, detected, kept)
    }

    /**
     * The stored Transfers of [data] that the Comparison of one Budget does not detect again but judges as stored
     * ([TransferRules.keep]): each with a Transfer Decision or an uploaded Transfer Leg, of which a Bank Transaction among
     * [compared] is a Transfer Leg or whose inflow side is one of the Budget's [accountIds].
     */
    private fun keptAsStored(data: ComparisonData, compared: Set<Long>, accountIds: Set<Long>): List<StoredTransfer> = data.transfers.filter { transfer ->
        val legs = data.bankTransactions.filter { it.transferId == transfer.id }
        (transfer.decision != TransferDecision.Proposed || TransferRules.locked(transfer, legs, data.sentRecords)) &&
            (transfer.inflowBudgetAccountId in accountIds || legs.any { it.id in compared })
    }

    // UC-003 BR-001: The first rule that applies gives the Transaction Status.
    /**
     * The Transaction Status of [transaction] of [bankAccount], mapped into [budget], against [held], the stored copy of
     * its Budget Account's Budget App Transactions, with its Review Decision [decision], which is rechecked against
     * [copy], the stored copy of every Budget Account of its Budget (BR-009); as an ordinary Bank Transaction, before any
     * Transfer is detected.
     */
    fun statusOf(
        transaction: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget,
        held: List<StoredBudgetAppTransaction>,
        windows: ComparisonWindows,
        decision: StoredReviewDecision? = null,
        copy: List<StoredBudgetAppTransaction> = held,
    ): TransactionStatus = ownStatusOf(transaction, bankAccount, budget, held, windows, decision, copy).status

    // UC-003 BR-001: The first rule that applies gives the Transaction Status, and one with a Review Decision follows that decision only where no rule before it applies.
    /** [statusOf], with whether the status stands before "a Transfer Leg follows its Transfer" and what it gives the other Transfer Legs. */
    fun ownStatusOf(
        transaction: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget,
        held: List<StoredBudgetAppTransaction>,
        windows: ComparisonWindows,
        decision: StoredReviewDecision?,
        copy: List<StoredBudgetAppTransaction>,
    ): OwnStatus {
        fun before(status: TransactionStatus) = OwnStatus(status, beforeTransferLeg = true)
        val sameIdentifier = underItsImportIdentifier(transaction, held)
        // UC-003 BR-003: A Bank Transaction whose Import Identifier its Budget Account holds is Already In Budget App.
        if (sameIdentifier.any { it.state == BudgetAppTransactionState.Present }) return before(TransactionStatus.AlreadyInBudgetApp)
        val sentAndGone = sameIdentifier.any { it.state == BudgetAppTransactionState.Deleted || it.state == BudgetAppTransactionState.Merged }
        // Whether a Re-upload decision is used up by the send it caused (UC-004 BR-003) is built with the Uploads (Wave 4, W4-01):
        // until then no send exists, so this condition reads the decision alone and no Re-upload decision is ever used up.
        // UC-003 BR-001: One Slussen sent that was deleted or merged in the Budget App is Deleted In Budget App or Merged In Budget App, unless the Operator chose Re-upload and that decision is not used up by the send it caused.
        if (decision?.choice != ReviewChoice.ReUpload) {
            // UC-003 BR-004: A Bank Transaction sent and later deleted in the Budget App is Deleted In Budget App.
            if (sameIdentifier.any { it.state == BudgetAppTransactionState.Deleted }) return before(TransactionStatus.DeletedInBudgetApp)
            // UC-003 BR-005: A Bank Transaction the Budget App merged into a Typed Entry after it was sent is Merged In Budget App.
            if (sameIdentifier.any { it.state == BudgetAppTransactionState.Merged }) return before(TransactionStatus.MergedInBudgetApp)
        }
        // UC-003 BR-018: A Bank Transaction dated before the Start Date of its Bank Account is Before Start Date.
        bankAccount.startDate?.let { startDate -> if (transaction.bookedDate.isBefore(startDate)) return before(TransactionStatus.BeforeStartDate) }
        // UC-003 BR-011: Outside the Upload Window, or the file upload window for a file-imported Bank Account, a Bank Transaction is Too Old or In The Future.
        if (transaction.bookedDate.isAfter(windows.today)) return before(TransactionStatus.InTheFuture)
        if (transaction.bookedDate.isBefore(uploadWindowStart(bankAccount, windows))) return before(TransactionStatus.TooOld)
        when (decision?.choice) {
            // UC-003 BR-009: A Review Decision Already Present holds only while the chosen Budget App Transaction exists, is in the Budget Account recorded with the decision and has the amount recorded with it; otherwise the Bank Transaction is Decision No Longer Holds.
            ReviewChoice.AlreadyPresent -> return if (alreadyPresentHolds(decision, copy)) {
                OwnStatus(TransactionStatus.AlreadyInBudgetApp, beforeTransferLeg = true, givesOtherLegs = TransactionStatus.AlreadyInBudgetApp)
            } else {
                before(TransactionStatus.DecisionNoLongerHolds)
            }
            // UC-003 BR-010: A Bank Transaction with the Review Decision Defer stays Deferred, also when the resembling Import disappears.
            ReviewChoice.Defer -> return OwnStatus(TransactionStatus.Deferred, beforeTransferLeg = true, givesOtherLegs = TransactionStatus.Deferred)
            // UC-004 BR-006: Import Anyway gives the status New, except that on a Transfer Leg it lets the Transfer Leg rejoin its Transfer.
            ReviewChoice.ImportAnyway -> return OwnStatus(TransactionStatus.New, beforeTransferLeg = false)
            // UC-004 BR-009: A Transfer Leg that Slussen sent and that was deleted or merged in the Budget App is re-uploaded as a new transaction of its own, not as a linked transfer; a Re-upload of one never sent lets it rejoin its Transfer as Import Anyway does.
            ReviewChoice.ReUpload -> return OwnStatus(
                TransactionStatus.New, beforeTransferLeg = sentAndGone,
                withoutReUpload = when {
                    sameIdentifier.any { it.state == BudgetAppTransactionState.Deleted } -> TransactionStatus.DeletedInBudgetApp
                    sentAndGone -> TransactionStatus.MergedInBudgetApp
                    else -> null
                },
            )
            null -> Unit
        }
        // UC-003 BR-012: A Bank Transaction without a Stable Reference is Needs Review.
        if (transaction.stableReference == null) return before(TransactionStatus.NeedsReview)
        // UC-003 BR-013: No amount is ever converted: a Bank Account in another currency than its Budget's is compared by nothing that compares amounts.
        if (bankAccount.currency != budget.currency) return OwnStatus(TransactionStatus.New, beforeTransferLeg = false)
        val resembling = resembling(transaction, held, windows)
        // UC-003 BR-008: A Bank Transaction resembling an Import of Slussen in a form it does not support is Needs Review.
        if (resembling.any { it.enteredBy == EnteredBy.SlussenUnsupportedFormat }) return before(TransactionStatus.NeedsReview)
        // UC-003 BR-007: A Bank Transaction resembling an Import by an Other Import Path is Possible Import By Other Path.
        if (resembling.any { it.enteredBy == EnteredBy.OtherImportPath }) return before(TransactionStatus.PossibleImportByOtherPath)
        // UC-003 BR-006: A Bank Transaction for which its Budget Account holds a Typed Entry of the same amount within the Matching Date Window is Match For Typed Entry.
        if (resembling.any { it.enteredBy == EnteredBy.TypedEntry }) return OwnStatus(TransactionStatus.MatchForTypedEntry, beforeTransferLeg = false)
        return OwnStatus(TransactionStatus.New, beforeTransferLeg = false)
    }

    // UC-003 BR-009: A Review Decision Already Present holds only while the chosen Budget App Transaction exists, is in the Budget Account recorded with the decision and has the amount recorded with it.
    /** Whether the Already Present [decision] holds against [copy], the stored copy of the Budget's Budget App Transactions. */
    fun alreadyPresentHolds(decision: StoredReviewDecision, copy: List<StoredBudgetAppTransaction>): Boolean {
        val chosen = decision.chosen ?: return false
        val stored = chosenOf(decision, copy) ?: return false
        return stored.state == BudgetAppTransactionState.Present && stored.budgetAccountId == chosen.budgetAccountId && stored.amount == chosen.amount
    }

    /** The Budget App Transaction the Already Present [decision] names, as [copy] holds it now, or null when it holds none. */
    fun chosenOf(decision: StoredReviewDecision, copy: List<StoredBudgetAppTransaction>): StoredBudgetAppTransaction? =
        decision.chosen?.let { chosen -> copy.firstOrNull { it.budgetAppReference == chosen.budgetAppReference } }

    // UC-003 BR-003: A Bank Transaction is found in the Budget App by its Import Identifier, in its Budget Account.
    /** The transactions of [held], the stored copy of a Budget Account, that carry the Import Identifier of [transaction]. */
    fun underItsImportIdentifier(transaction: StoredBankTransaction, held: List<StoredBudgetAppTransaction>): List<StoredBudgetAppTransaction> =
        held.filter { it.importIdentifier == transaction.importIdentifier }

    // UC-003 BR-006: A resembling transaction is present in its Budget Account, with the same amount, dated within the Matching Date Window before or after it.
    /** The present transactions of [held] that resemble [transaction] (BR-006, BR-007, BR-008). */
    fun resembling(
        transaction: StoredBankTransaction,
        held: List<StoredBudgetAppTransaction>,
        windows: ComparisonWindows,
    ): List<StoredBudgetAppTransaction> = held.filter { candidate ->
        candidate.state == BudgetAppTransactionState.Present &&
            candidate.amount == transaction.amount &&
            abs(ChronoUnit.DAYS.between(candidate.transactionDate, transaction.bookedDate)) <= windows.matchingDateWindowDays
    }

    // UC-003 BR-011: The Upload Window is the last 7 days by default, or the file upload window for a Bank Account fed by Transaction Files, Operator Settings both, today included.
    /** The days of the Upload Window that applies to [bankAccount]. */
    fun uploadWindowDays(bankAccount: StoredBankAccount, windows: ComparisonWindows): Int =
        if (bankAccount.fileFormatId != null) windows.fileUploadWindowDays else windows.uploadWindowDays

    /** The first day of the Upload Window that applies to [bankAccount]; a Bank Transaction dated before it is Too Old. */
    fun uploadWindowStart(bankAccount: StoredBankAccount, windows: ComparisonWindows): LocalDate =
        windows.today.minusDays(uploadWindowDays(bankAccount, windows).toLong())
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
     * Transfer Leg, of every Bank Account, and [withRetiredSide] the Transfers that [withARetiredSide] gives. Outcome
     * Unknown comes with the phase that builds Sent Records.
     */
    fun isEligible(
        transaction: StoredBankTransaction,
        bankAccount: StoredBankAccount,
        budget: StoredBudget?,
        transferLegs: List<StoredBankTransaction>,
        withRetiredSide: Set<Long>,
    ): Boolean =
        !bankAccount.retired &&
            budget != null &&
            // UC-003 BR-013: A Bank Account whose currency differs from its Budget's is blocked from Upload.
            bankAccount.currency == budget.currency &&
            when (transaction.status) {
                TransactionStatus.New, TransactionStatus.MatchForTypedEntry -> true
                // UC-005 BR-015: While the Bank Account of one of its sides is a Retired Account, a Transfer that has a Transfer Decision is in no Upload Plan, from either side.
                TransactionStatus.TransferLeg -> transaction.transferId !in withRetiredSide && writesItsTransfer(transaction, transferLegs)
                // UC-005 BR-010: The Transfer Legs of a Guessed Transfer are not eligible until the Operator confirms the Transfer or chooses another Budget Account.
                else -> false
            }

    // UC-005 BR-015: While the Bank Account of one of its sides is a Retired Account, a Transfer that has a Transfer Decision keeps its decision and is in no Upload Plan; restoring the Bank Account brings it back as the Operator decided it.
    /**
     * The ids of the stored [transfers] with a Transfer Decision that have a side whose Bank Account, among [bankAccounts],
     * is a Retired Account: the Bank Account of one of its [transferLegs], or the one mapped to the Budget Account of a
     * side that has not arrived. A Transfer without a Transfer Decision is released by the Comparison that follows the
     * retiring (UC-005 BR-014), so it is not among them.
     */
    fun withARetiredSide(transfers: List<StoredTransfer>, transferLegs: List<StoredBankTransaction>, bankAccounts: List<StoredBankAccount>): Set<Long> =
        transfers
            .filter { it.decision != TransferDecision.Proposed }
            .filter { transfer -> TransferRules.hasRetiredSide(transfer, transferLegs.filter { it.transferId == transfer.id }, bankAccounts) }
            .map { it.id }
            .toSet()

    // UC-003 BR-014: A Transfer Leg is an Eligible Transaction only when it writes its Transfer and no Transfer Leg of that Transfer waits for a Review Decision or has a status that an earlier rule gave it.
    /** Whether [transaction], with the status Transfer Leg, is the Transfer Leg its Transfer is written through. */
    private fun writesItsTransfer(transaction: StoredBankTransaction, transferLegs: List<StoredBankTransaction>): Boolean {
        val transferId = transaction.transferId ?: return false
        val otherLegs = transferLegs.filter { it.transferId == transferId && it.id != transaction.id }
        return keptOutBy(transaction, transferLegs) == null && writesItsSide(transaction, otherLegs)
    }

    /**
     * The Transfer Leg of the Transfer of [transaction], itself included, whose Transaction Status keeps the whole
     * Transfer out of every Upload Plan, or null when none does: a doubtful one first (UC-005 BR-013), then one an earlier
     * rule of the Comparison or a Review Decision gave its status (UC-005 BR-010, BR-013), then a Guessed Transfer's.
     * [transferLegs] are the stored Bank Transactions that are a Transfer Leg, of every Bank Account.
     */
    fun keptOutBy(transaction: StoredBankTransaction, transferLegs: List<StoredBankTransaction>): StoredBankTransaction? {
        val transferId = transaction.transferId ?: return null
        val legs = listOf(transaction) + transferLegs.filter { it.transferId == transferId && it.id != transaction.id }
        // UC-005 BR-013: A Transfer with a Transfer Leg that is Needs Review or Possible Import By Other Path, or whose Already Present decision does not hold any more, is in no Upload Plan, from either side.
        legs.firstOrNull { it.status in DOUBTFUL }?.let { return it }
        // UC-005 BR-010: While a Transfer has a Transfer Leg to which an earlier rule of the Comparison gave its Transaction Status, the whole Transfer is in no Upload Plan, from either side.
        legs.firstOrNull { it.status != TransactionStatus.TransferLeg && it.status != TransactionStatus.GuessedTransfer }?.let { return it }
        // UC-005 BR-010: The Transfer Legs of a Guessed Transfer are not eligible until the Operator confirms the Transfer or chooses another Budget Account.
        return legs.firstOrNull { it.status == TransactionStatus.GuessedTransfer }
    }

    // UC-006 BR-009: A Transfer is written through exactly one Transfer Leg, the outflow side when both Transfer Legs are present.
    /** Whether [transaction] is the Transfer Leg its Transfer is written through, beside its [otherLegs]. */
    fun writesItsSide(transaction: StoredBankTransaction, otherLegs: List<StoredBankTransaction>): Boolean =
        otherLegs.isEmpty() || transaction.amount.thousandths < 0

    /**
     * The counts of [bankAccount]: its Eligible Transactions and its Bank Transactions and Unreadable Transactions that wait
     * for a decision. A Bank Transaction not compared yet counts as neither, and is counted on its own (UC-003 A1, S-T-08). [transferLegs] are the
     * stored Bank Transactions that are a Transfer Leg, of every Bank Account, and [withRetiredSide] the Transfers that
     * [withARetiredSide] gives.
     */
    fun countsOf(
        bankAccount: StoredBankAccount,
        budget: StoredBudget?,
        transactions: List<StoredBankTransaction>,
        unreadable: List<StoredUnreadableTransaction>,
        transferLegs: List<StoredBankTransaction>,
        withRetiredSide: Set<Long>,
    ): StoredTransactionCounts = StoredTransactionCounts(
        bankAccountId = bankAccount.id,
        eligible = transactions.count { isEligible(it, bankAccount, budget, transferLegs, withRetiredSide) },
        waitingForDecision = if (bankAccount.retired) {
            0
        } else {
            transactions.count { it.status in WAITING } + unreadable.count { it.status == UnreadableTransactionStatus.NeedsReview }
        },
        // A1 step 2 of UC-003 (S-T-08): a Bank Transaction stored since its Budget's last successful read is counted as not compared yet.
        notComparedYet = if (bankAccount.retired) 0 else transactions.count { it.status == null },
    )
}
