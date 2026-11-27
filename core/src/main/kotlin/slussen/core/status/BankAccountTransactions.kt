package slussen.core.status

import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.comparison.EligibleTransactions
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.ConnectionStore
import slussen.core.port.EnteredBy
import slussen.core.port.FetchStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferType
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.transfer.TransferDetection
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Where a Bank Account stands (UC-009): mapped into a Budget, without an Account Mapping, or a Retired Account. */
sealed interface BankAccountPlacement {
    /** Mapped to [budgetAccountName] of [budgetName], whose currency is [budgetCurrency]. */
    data class Mapped(val budgetId: Long, val budgetName: String, val budgetCurrency: CurrencyCode, val budgetAccountName: String) :
        BankAccountPlacement

    /** UC-009 A11: no Account Mapping; its history is kept, and it is not compared or offered for Upload. */
    data object NotMapped : BankAccountPlacement

    /** UC-009 A4: a Retired Account; its history is kept, and it is not compared or offered for Upload. */
    data class Retired(val budgetName: String?) : BankAccountPlacement
}

/** Which other side a Bank Account keeps (UC-005 BR-006): the Funded By of a credit card, the Transfer Partner otherwise. */
enum class OtherSideKind { FundedBy, TransferPartner }

/**
 * The Funded By or Transfer Partner of a Bank Account (UC-005 BR-006, BR-014), shown read-only: [budgetAccountName] is
 * null while it is not set; [applies] is false when its Budget Account lost its last Account Mapping of a Bank Account
 * that is not a Retired Account.
 */
data class OtherSideSetting(val kind: OtherSideKind, val budgetAccountName: String?, val applies: Boolean)

/** A Budget App Transaction of the stored copy, as a reason names it (UC-003 BR-020). */
data class BudgetAppEntry(val date: LocalDate, val amount: Amount, val payee: String?, val budgetAccountName: String)

/** The Bank Transaction that is the other Transfer Leg of a pair. */
data class OtherLeg(val bankAccountName: String, val bookedDate: LocalDate, val amount: Amount, val payee: String?)

/**
 * The Transfer a Bank Transaction is a Transfer Leg of, as stored by the latest Comparison (UC-005 BR-002, BR-010):
 * [otherBudgetAccountName] is the other side; [otherLeg] is the other Transfer Leg of a pair, null for a Lonely
 * Transfer, whose other side is expected by [expectedBy] (BR-005); [writesIt] is whether this side carries the Transfer
 * in an Upload Plan (UC-006 BR-009).
 */
data class TransferShown(
    val type: TransferType,
    val guessed: Boolean,
    val otherBudgetAccountName: String,
    val otherLeg: OtherLeg?,
    val expectedBy: LocalDate?,
    val writesIt: Boolean,
    /**
     * The Transfer Leg whose status keeps the whole Transfer out of every Upload Plan (UC-003 BR-014, UC-005 BR-010,
     * BR-013), this side or the other, or null when nothing does.
     */
    val keptOutBy: TransferKeptOut?,
    /** Its Transfer Decision, Proposed while the Operator has made none (UC-005 BR-010). */
    val decision: TransferDecision = TransferDecision.Proposed,
)

/** The Transfer Leg that keeps its Transfer out of every Upload Plan: this side or the other, with its [status]. */
data class TransferKeptOut(val byThisSide: Boolean, val status: TransactionStatus)

/**
 * Why a transaction has its Transaction Status, in what the Comparison compared it with (UC-003 BR-001), derived from the
 * stored data with the Comparison's and the Transfer detection's own rules, never stored (O-T-11, survey §5 guess 7).
 */
sealed interface StatusReason {
    /** UC-003 A1 (S-T-08): stored since its Budget's last successful read, or before any; no Transaction Status yet. */
    data object NotComparedYet : StatusReason

    /** UC-009 A4, A11: the Bank Account is not mapped or is a Retired Account; its last status is kept and not compared again. */
    data object NotComparedAnyMore : StatusReason

    /** UC-003 BR-001: New, nothing in the Budget App matched it at the latest Comparison. */
    data object NoMatch : StatusReason

    /** UC-003 BR-013: New in a Bank Account with a currency mismatch; no amount is compared, nothing is eligible. */
    data class CurrencyMismatch(val bankAccountCurrency: CurrencyCode, val budgetCurrency: CurrencyCode) : StatusReason

    /**
     * UC-005 A5 (O-T-11): an ordinary Bank Transaction that matches a Transfer Description and whose other side,
     * [otherBudgetAccountName], is known, but whose grace period of [graceDays] ended on [lastDay].
     */
    data class OtherSideDidNotArriveInTime(
        val otherBudgetAccountName: String,
        val graceDays: Int,
        val lastDay: LocalDate,
        /** UC-005 A8: the other side was only proposed, as for a card without a Funded By. */
        val proposed: Boolean,
    ) : StatusReason

    /** UC-003 BR-006: the Typed Entries of the same amount within the Matching Date Window of [matchingDateWindowDays]. */
    data class TypedEntries(val entries: List<BudgetAppEntry>, val matchingDateWindowDays: Int) : StatusReason

    /** UC-003 BR-003: its Budget Account holds its Import Identifier, in [entry]. */
    data class HeldInBudgetApp(val entry: BudgetAppEntry?) : StatusReason

    /** UC-003 BR-004: the transaction under its Import Identifier was deleted in the Budget App. */
    data class DeletedInBudgetApp(val entry: BudgetAppEntry?) : StatusReason

    /** UC-003 BR-005: the transaction under its Import Identifier was merged into [into]. */
    data class MergedInBudgetApp(val entry: BudgetAppEntry?, val into: BudgetAppEntry?) : StatusReason

    /** UC-003 BR-007: the Imports by an Other Import Path it resembles. */
    data class ResemblesOtherImport(val imports: List<BudgetAppEntry>, val matchingDateWindowDays: Int) : StatusReason

    /** UC-003 BR-008: the Imports with Slussen's marker in a form it does not support that it resembles. */
    data class ResemblesUnsupportedSlussenImport(val imports: List<BudgetAppEntry>) : StatusReason

    /** UC-003 BR-012: no Stable Reference; [identical] other stored Bank Transactions have the same date, amount and text. */
    data class NoStableReference(val identical: Int) : StatusReason

    /** UC-001 A17, UC-003 BR-012: an Unreadable Transaction, never uploaded, with the [reason] it could not be read. */
    data class Unreadable(val reason: String) : StatusReason

    /** UC-003 BR-018: dated before the Start Date [startDate] of its Bank Account. */
    data class BeforeStartDate(val startDate: LocalDate) : StatusReason

    /**
     * UC-003 BR-011: dated before the Upload Window that applies to it, which starts on [windowStartsOn]
     * ([windowDays] days); [fileUploadWindow] for a Bank Account fed by Transaction Files.
     */
    data class TooOld(val windowStartsOn: LocalDate, val windowDays: Int, val fileUploadWindow: Boolean) : StatusReason

    /** UC-003 BR-011: dated after today. */
    data object InTheFuture : StatusReason

    /** UC-005 BR-010: a Transfer Leg of [transfer]. */
    data class TransferLeg(val transfer: TransferShown) : StatusReason

    /**
     * UC-005 BR-003, A8: a Transfer Leg of the Guessed Transfer [transfer]; null when Slussen proposes no Budget Account
     * as its other side (A8), [cardName] then names the credit card without a Funded By.
     */
    data class GuessedTransfer(val transfer: TransferShown?, val cardName: String?) : StatusReason

    /**
     * A status the stored data no longer explains with the current rules, or one this build does not give (a decision,
     * a Sent Record): shown as the latest Comparison left it.
     */
    data class AsStored(val status: TransactionStatus) : StatusReason
}

/** Which transaction of a Bank Account a line shows. */
data class TransactionKey(val unreadable: Boolean, val id: Long)

/**
 * One transaction of the Bank Account (UC-008 step 5): its Transaction Status, null while it is not compared yet
 * (S-T-08), the reason for it, whether it is an Eligible Transaction (UC-003 BR-014), and the Transfer it belongs to.
 */
data class TransactionLine(
    val key: TransactionKey,
    /** Null for an Unreadable Transaction whose date could not be read. */
    val bookedDate: LocalDate?,
    val amount: Amount?,
    val currency: CurrencyCode?,
    val payee: String?,
    val memo: String?,
    val firstSeenAt: Instant,
    val status: TransactionStatus?,
    val reason: StatusReason,
    val eligible: Boolean,
    val transfer: TransferShown?,
)

/** The headings of the list, newest first (UX-17, UX-D2): a heading, never a filter. */
enum class WeekKind { ThisWeek, LastWeek, Earlier, WithoutDate }

/** One heading of the list with the dates it covers ([to] is null for this week, which reaches to the newest). */
data class TransactionWeek(val kind: WeekKind, val from: LocalDate?, val to: LocalDate?, val lines: List<TransactionLine>)

/** What a Bank Account shows when it holds no transaction at all (`bank-account.html#fetched-empty`, UC-001 BR-002, BR-003). */
sealed interface NothingStored {
    /** The Fetch of [fetchedAt] by [dataProviderName] succeeded and brought none; no Fetch stores the period it asked for. */
    data class FetchedNone(val fetchedAt: Instant, val dataProviderName: String?) : NothingStored

    /** No Fetch of this Bank Account has succeeded yet. */
    data object NotFetchedYet : NothingStored

    /** A Bank Account fed by Transaction Files: the last import at [importedAt] brought none, or none was imported (null). */
    data class NoneImported(val importedAt: Instant?) : NothingStored

    /** A Retired Account, or one without an Account Mapping, that keeps no transaction; nothing is fetched for it. */
    data object NoneKept : NothingStored
}

/**
 * The boundaries "What the statuses mean" names for this Bank Account (`bank-account.html#statuses-this-build`): the
 * Upload Window that applies to it and its first day today, the Matching Date Window of the Budget App, the Lonely
 * Transfer grace period, the Start Date and the fetch period.
 */
data class StatusBoundaries(
    val uploadWindowDays: Int,
    val uploadWindowStartsOn: LocalDate,
    val fileUploadWindow: Boolean,
    val matchingDateWindowDays: Int,
    val lonelyTransferGraceDays: Int,
    val startDate: LocalDate?,
    val fetchPeriodDays: Int,
)

/** One Bank Account with its Bank Transactions and Unreadable Transactions (UC-008 steps 5-8; `bank-account.html`). */
data class BankAccountTransactionsView(
    val bankAccountId: Long,
    /** The name of the Budget App its Bank Transactions are compared with. */
    val budgetAppName: String,
    val name: String,
    val type: BankAccountType,
    val currency: CurrencyCode,
    val bankName: String,
    /** The Data Provider or File Format that brings its Bank Transactions. */
    val sourceName: String?,
    val placement: BankAccountPlacement,
    /** Steps 6-8 for a Bank Account mapped into a served Budget: Source Health, Freshness, counts, balance, Blocked Reasons. */
    val status: BankAccountStatus?,
    /**
     * The summary of its Budget, with when its statuses were given (UC-003 step 7, A1) and the Budget App's credentials;
     * null when it is not mapped into a served Budget.
     */
    val summary: BudgetSummary?,
    val startDate: LocalDate?,
    val otherSide: OtherSideSetting,
    /** The dates the listed Bank Transactions cover, oldest to newest; null when none has a date. */
    val covers: ClosedRange<LocalDate>?,
    val weeks: List<TransactionWeek>,
    /** Null while it holds a transaction. */
    val nothingStored: NothingStored?,
    val boundaries: StatusBoundaries,
)

/**
 * The read model of one Bank Account's transactions (UC-008 step 5), `:core` `status`: built only from stored data
 * (BR-001), newest first with week headings, each with its Transaction Status and the reason for it. The reasons are
 * derived with the Comparison's and the Transfer detection's own pure rules ([ComparisonRules], [TransferDetection],
 * [EligibleTransactions]) on the data a Comparison concludes from, never stored. [firstDayOfWeek] is the device
 * locale's (survey §5 guess 6).
 */
class BankAccountTransactions(
    private val status: ImportStatus,
    private val connections: ConnectionStore,
    private val fetches: FetchStore,
    private val comparisons: ComparisonStore,
    private val settings: OperatorSettingsStore,
    private val matchingDateWindowDays: Int,
    private val clock: Clock,
    private val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** What the reasons of one Bank Account are derived from: the stored data a Comparison concludes from, and its windows. */
    private class Ground(
        val bankAccount: StoredBankAccount,
        val budget: StoredBudget,
        val data: ComparisonData,
        val windows: ComparisonWindows,
        val names: Map<Long, String>,
    ) {
        val held: List<StoredBudgetAppTransaction> = data.budgetAppTransactions.filter { it.budgetAccountId == bankAccount.mapping?.budgetAccountId }
        val transferLegs: List<StoredBankTransaction> = data.bankTransactions.filter { it.transferId != null }

        /** UC-005 BR-015: the Transfers the Operator decided on that have a Retired Account on a side, which are in no Upload Plan. */
        val withRetiredSide: Set<Long> = EligibleTransactions.withARetiredSide(data.transfers, transferLegs, data.bankAccounts)

        fun entry(transaction: StoredBudgetAppTransaction) =
            BudgetAppEntry(transaction.transactionDate, transaction.amount, transaction.payee, names[transaction.budgetAccountId].orEmpty())
    }

    // UC-008 BR-001: Opening a Bank Account shows only stored data; it never contacts a Source, a Bank or the Budget App.
    /** The transactions of [bankAccountId] with their statuses and reasons. */
    suspend fun of(bankAccountId: Long): BankAccountTransactionsView {
        val bankAccounts = connections.bankAccounts()
        val bankAccount = bankAccounts.single { it.id == bankAccountId }
        val budgets = connections.budgets()
        val budget = budgets.firstOrNull { candidate -> candidate.budgetAccounts.any { it.id == bankAccount.mapping?.budgetAccountId } }
        val names = budgets.flatMap { it.budgetAccounts }.associate { it.id to it.name }
        val current = settings.current()
        val today = LocalDate.ofInstant(clock.instant(), clock.zone)
        val windows = ComparisonWindows.of(current, matchingDateWindowDays, today)
        val inBudget = status.bankAccountInBudget(bankAccountId)
        // Step 2 of UC-003: only the Bank Transactions of a Bank Account that is mapped and not a Retired Account are compared.
        val ground = if (!bankAccount.retired && budget != null) {
            Ground(bankAccount, budget, comparisons.comparisonDataOf(setOf(budget.id)), windows, names)
        } else {
            null
        }
        val transactions = ground?.data?.bankTransactions?.filter { it.bankAccountId == bankAccountId } ?: fetches.bankTransactionsOf(bankAccountId)
        val lines = transactions.map { line(it, transactions, ground, bankAccounts) } +
            fetches.unreadableTransactionsOf(bankAccountId).map(::unreadableLine)
        val dated = lines.mapNotNull { it.bookedDate }
        return BankAccountTransactionsView(
            bankAccountId = bankAccount.id,
            budgetAppName = status.budgetAppName,
            name = bankAccount.name,
            type = bankAccount.type,
            currency = bankAccount.currency,
            bankName = connections.banks().firstOrNull { it.id == bankAccount.bankId }?.name.orEmpty(),
            sourceName = inBudget?.sourceName,
            placement = placementOf(bankAccount, budget, names),
            status = inBudget?.status,
            summary = inBudget?.summary,
            startDate = bankAccount.startDate,
            otherSide = otherSideOf(bankAccount, budget, bankAccounts, names),
            covers = if (dated.isEmpty()) null else dated.min()..dated.max(),
            weeks = weeksOf(lines, today),
            nothingStored = if (lines.isEmpty()) nothingStored(bankAccount, inBudget) else null,
            boundaries = StatusBoundaries(
                uploadWindowDays = ComparisonRules.uploadWindowDays(bankAccount, windows),
                uploadWindowStartsOn = ComparisonRules.uploadWindowStart(bankAccount, windows),
                fileUploadWindow = bankAccount.fileFormatId != null,
                matchingDateWindowDays = matchingDateWindowDays,
                lonelyTransferGraceDays = current.lonelyTransferGraceDays,
                startDate = bankAccount.startDate,
                fetchPeriodDays = current.fetchPeriodDays,
            ),
        )
    }

    private fun placementOf(bankAccount: StoredBankAccount, budget: StoredBudget?, names: Map<Long, String>): BankAccountPlacement {
        val mapping = bankAccount.mapping
        return when {
            bankAccount.retired -> BankAccountPlacement.Retired(budget?.name)
            mapping == null || budget == null -> BankAccountPlacement.NotMapped
            else -> BankAccountPlacement.Mapped(budget.id, budget.name, budget.currency, names[mapping.budgetAccountId].orEmpty())
        }
    }

    private fun otherSideOf(
        bankAccount: StoredBankAccount,
        budget: StoredBudget?,
        bankAccounts: List<StoredBankAccount>,
        names: Map<Long, String>,
    ): OtherSideSetting {
        val creditCard = bankAccount.type == BankAccountType.CreditCard
        val set = if (creditCard) bankAccount.fundedByBudgetAccountId else bankAccount.transferPartnerBudgetAccountId
        val applies = set != null && budget != null && TransferDetection.otherSideThatApplies(bankAccount, budget, bankAccounts) == set
        return OtherSideSetting(if (creditCard) OtherSideKind.FundedBy else OtherSideKind.TransferPartner, set?.let { names[it] }, applies)
    }

    private fun line(
        transaction: StoredBankTransaction,
        ofBankAccount: List<StoredBankTransaction>,
        ground: Ground?,
        bankAccounts: List<StoredBankAccount>,
    ): TransactionLine {
        val transfer = ground?.let { transferOf(transaction, it, bankAccounts) }
        val reason = when {
            ground == null -> StatusReason.NotComparedAnyMore
            else -> transaction.status?.let { reasonOf(transaction, it, transfer, ofBankAccount, ground) } ?: StatusReason.NotComparedYet
        }
        return TransactionLine(
            key = TransactionKey(unreadable = false, id = transaction.id),
            bookedDate = transaction.bookedDate,
            amount = transaction.amount,
            currency = transaction.currency,
            payee = transaction.payee,
            memo = transaction.memo,
            firstSeenAt = transaction.firstSeenAt,
            status = transaction.status,
            reason = reason,
            eligible = ground != null && EligibleTransactions.isEligible(transaction, ground.bankAccount, ground.budget, ground.transferLegs, ground.withRetiredSide),
            transfer = transfer,
        )
    }

    // UC-003 BR-012: An Unreadable Transaction is Needs Review, never uploaded, and the Operator can only defer it.
    private fun unreadableLine(unreadable: StoredUnreadableTransaction) = TransactionLine(
        key = TransactionKey(unreadable = true, id = unreadable.id),
        bookedDate = unreadable.bookedDate,
        amount = unreadable.amount,
        currency = unreadable.currency,
        payee = unreadable.payee,
        memo = unreadable.memo,
        firstSeenAt = unreadable.firstSeenAt,
        status = when (unreadable.status) {
            UnreadableTransactionStatus.NeedsReview -> TransactionStatus.NeedsReview
            UnreadableTransactionStatus.Deferred -> TransactionStatus.Deferred
        },
        reason = StatusReason.Unreadable(unreadable.reason),
        eligible = false,
        transfer = null,
    )

    /**
     * UC-003 BR-001: why [transaction] has its stored [status], in what the rule that gave it compared it with. A status
     * the stored data no longer explains (an Operator Setting changed since the latest Comparison) is shown as stored.
     */
    private fun reasonOf(
        transaction: StoredBankTransaction,
        status: TransactionStatus,
        transfer: TransferShown?,
        ofBankAccount: List<StoredBankTransaction>,
        ground: Ground,
    ): StatusReason {
        val bankAccount = ground.bankAccount
        val windows = ground.windows
        val underIt = ComparisonRules.underItsImportIdentifier(transaction, ground.held)
        val resembling = ComparisonRules.resembling(transaction, ground.held, windows)
        fun resemblingBy(enteredBy: EnteredBy) = resembling.filter { it.enteredBy == enteredBy }.map(ground::entry)
        // A status that follows from a Review Decision (UC-004 BR-006, UC-005 BR-013) is shown as stored, never with the reason of a rule that did not give it.
        val decided = ground.data.reviewDecisions.map { it.storedBankTransaction }.toSet()
        val ownDecision = transaction.id in decided
        val decisionInItsTransfer = ownDecision || (transaction.transferId != null && ground.transferLegs.any { it.transferId == transaction.transferId && it.id in decided })
        val heldPresent = underIt.firstOrNull { it.state == BudgetAppTransactionState.Present }
        return when (status) {
            // A currency mismatch is still named: it keeps the Bank Transaction from being eligible whatever the decision (UC-003 BR-013).
            TransactionStatus.New -> if (ownDecision && bankAccount.currency == ground.budget.currency) StatusReason.AsStored(status) else newReason(transaction, ground)
            TransactionStatus.MatchForTypedEntry -> StatusReason.TypedEntries(resemblingBy(EnteredBy.TypedEntry), windows.matchingDateWindowDays)
            TransactionStatus.AlreadyInBudgetApp ->
                if (heldPresent == null && decisionInItsTransfer) StatusReason.AsStored(status) else StatusReason.HeldInBudgetApp(heldPresent?.let(ground::entry))
            TransactionStatus.DeletedInBudgetApp ->
                StatusReason.DeletedInBudgetApp(underIt.firstOrNull { it.state == BudgetAppTransactionState.Deleted }?.let(ground::entry))
            TransactionStatus.MergedInBudgetApp -> {
                val merged = underIt.firstOrNull { it.state == BudgetAppTransactionState.Merged }
                val into = merged?.mergedIntoReference?.let { into -> ground.data.budgetAppTransactions.firstOrNull { it.budgetAppReference == into } }
                StatusReason.MergedInBudgetApp(merged?.let(ground::entry), into?.let(ground::entry))
            }
            TransactionStatus.PossibleImportByOtherPath, TransactionStatus.NeedsReview ->
                doubtOf(transaction, status, ofBankAccount, ground.held, windows, ground::entry)
            TransactionStatus.BeforeStartDate -> bankAccount.startDate?.takeIf { transaction.bookedDate.isBefore(it) }
                ?.let { StatusReason.BeforeStartDate(it) } ?: StatusReason.AsStored(status)
            TransactionStatus.TooOld -> {
                val startsOn = ComparisonRules.uploadWindowStart(bankAccount, windows)
                if (transaction.bookedDate.isBefore(startsOn)) {
                    StatusReason.TooOld(startsOn, ComparisonRules.uploadWindowDays(bankAccount, windows), bankAccount.fileFormatId != null)
                } else {
                    StatusReason.AsStored(status)
                }
            }
            TransactionStatus.InTheFuture -> if (transaction.bookedDate.isAfter(windows.today)) StatusReason.InTheFuture else StatusReason.AsStored(status)
            TransactionStatus.TransferLeg -> transfer
                // UC-005 BR-005: a Lonely Transfer without a Transfer Decision whose grace period ended since the latest Comparison is not shown as still expected.
                ?.takeUnless {
                    it.decision == TransferDecision.Proposed && it.otherLeg == null &&
                        TransferDetection.graceEnded(transaction, windows.lonelyTransferGraceDays, windows.today)
                }
                ?.let { StatusReason.TransferLeg(it) } ?: StatusReason.AsStored(status)
            // UC-005 BR-002: A Card Payment to a credit card Bank Account without a Funded By is a Guessed Transfer; A8 may propose no Budget Account.
            TransactionStatus.GuessedTransfer -> StatusReason.GuessedTransfer(
                transfer,
                cardName = bankAccount.name.takeIf { transfer == null && bankAccount.type == BankAccountType.CreditCard },
            )
            else -> StatusReason.AsStored(status)
        }
    }

    /** New: in a Bank Account with a currency mismatch, after the grace period of a Lonely Transfer (O-T-11), or no match. */
    private fun newReason(transaction: StoredBankTransaction, ground: Ground): StatusReason {
        val bankAccount = ground.bankAccount
        // UC-003 BR-013: No amount is ever converted: a Bank Account in another currency than its Budget's is compared by nothing that compares amounts.
        if (bankAccount.currency != ground.budget.currency) return StatusReason.CurrencyMismatch(bankAccount.currency, ground.budget.currency)
        val graceDays = ground.windows.lonelyTransferGraceDays
        // UC-005 BR-005: A Lonely Transfer whose other Transfer Leg has not arrived within the grace period becomes an ordinary Bank Transaction.
        val otherSide = if (transaction.transferId == null) {
            TransferDetection.otherSideAfterGrace(
                transaction, bankAccount, ground.budget, ground.data.bankAccounts, ground.data.transferDescriptions, graceDays, ground.windows.today,
            )
        } else {
            null
        }
        return otherSide?.let {
            StatusReason.OtherSideDidNotArriveInTime(
                ground.names[it.budgetAccountId].orEmpty(), graceDays, TransferDetection.lastDayOfGrace(transaction, graceDays), proposed = it.guessed,
            )
        } ?: StatusReason.NoMatch
    }


    /** The stored Transfer [transaction] is a Transfer Leg of, as the latest Comparison stored it, or null. */
    private fun transferOf(transaction: StoredBankTransaction, ground: Ground, bankAccounts: List<StoredBankAccount>): TransferShown? {
        val stored: StoredTransfer = ground.data.transfers.firstOrNull { it.id == transaction.transferId } ?: return null
        val otherLegs = ground.data.bankTransactions.filter { it.transferId == stored.id && it.id != transaction.id }
        val otherSide = if (transaction.amount.thousandths < 0) stored.inflowBudgetAccountId else stored.outflowBudgetAccountId
        val other = otherLegs.firstOrNull()
        return TransferShown(
            type = stored.type,
            guessed = stored.guessed,
            otherBudgetAccountName = ground.names[otherSide].orEmpty(),
            otherLeg = other?.let { leg -> OtherLeg(bankAccounts.firstOrNull { it.id == leg.bankAccountId }?.name.orEmpty(), leg.bookedDate, leg.amount, leg.payee) },
            // A4 of UC-005: a Lonely Transfer shows the date by which its other side must arrive.
            // UC-005 BR-005: The grace period ends only a Lonely Transfer that has no Transfer Decision.
            expectedBy = if (other == null && stored.decision == TransferDecision.Proposed) {
                TransferDetection.lastDayOfGrace(transaction, ground.windows.lonelyTransferGraceDays)
            } else {
                null
            },
            decision = stored.decision,
            writesIt = EligibleTransactions.writesItsSide(transaction, otherLegs),
            keptOutBy = EligibleTransactions.keptOutBy(transaction, ground.transferLegs)?.let { leg ->
                leg.status?.let { TransferKeptOut(byThisSide = leg.id == transaction.id, status = it) }
            },
        )
    }

    /**
     * UX-17, UX-D2: newest first under this week, last week and earlier, the weeks starting on [firstDayOfWeek], and
     * the Unreadable Transactions without a readable date last.
     */
    private fun weeksOf(lines: List<TransactionLine>, today: LocalDate): List<TransactionWeek> {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val lastWeek = thisWeek.minusWeeks(1)
        val newestFirst = compareByDescending<TransactionLine> { it.bookedDate }.thenByDescending { it.firstSeenAt }.thenByDescending { it.key.id }
        val dated = lines.filter { it.bookedDate != null }.sortedWith(newestFirst)
        val earlier = dated.filter { it.bookedDate!!.isBefore(lastWeek) }
        return listOfNotNull(
            TransactionWeek(WeekKind.ThisWeek, thisWeek, null, dated.filter { !it.bookedDate!!.isBefore(thisWeek) }),
            TransactionWeek(WeekKind.LastWeek, lastWeek, thisWeek.minusDays(1), dated.filter { it.bookedDate!! in lastWeek..thisWeek.minusDays(1) }),
            TransactionWeek(WeekKind.Earlier, earlier.lastOrNull()?.bookedDate, lastWeek.minusDays(1), earlier),
            TransactionWeek(WeekKind.WithoutDate, null, null, lines.filter { it.bookedDate == null }.sortedByDescending { it.firstSeenAt }),
        ).filter { it.lines.isNotEmpty() }
    }

    // UC-001 BR-002: Only booked transactions are taken in, so a successful Fetch can bring none; that is said, never shown as a failure.
    private fun nothingStored(bankAccount: StoredBankAccount, inBudget: BankAccountInBudget?): NothingStored {
        if (bankAccount.retired || bankAccount.mapping == null) return NothingStored.NoneKept
        if (bankAccount.fileFormatId != null) return NothingStored.NoneImported(inBudget?.status?.freshness)
        // No Fetch stores the period it asked for (`FETCH`), so no dates are named for it.
        val fetchedAt = inBudget?.status?.freshness ?: return NothingStored.NotFetchedYet
        return NothingStored.FetchedNone(fetchedAt, inBudget.sourceName)
    }

    companion object {
        /**
         * Why [transaction], whose stored [status] is Needs Review or Possible Import By Other Path, is doubtful (UC-003
         * BR-007, BR-008, BR-012), against [held], the stored copy of its Budget Account, beside the other Bank Transactions
         * of its Bank Account [ofBankAccount]; [entry] names a Budget App Transaction. Shared by every screen that shows it
         * (UC-008 step 5, UC-005 A9), so that the reason is derived once.
         */
        fun doubtOf(
            transaction: StoredBankTransaction,
            status: TransactionStatus,
            ofBankAccount: List<StoredBankTransaction>,
            held: List<StoredBudgetAppTransaction>,
            windows: ComparisonWindows,
            entry: (StoredBudgetAppTransaction) -> BudgetAppEntry,
        ): StatusReason {
            val resembling = ComparisonRules.resembling(transaction, held, windows)
            fun resemblingBy(enteredBy: EnteredBy) = resembling.filter { it.enteredBy == enteredBy }.map(entry)
            return when (status) {
                TransactionStatus.PossibleImportByOtherPath ->
                    StatusReason.ResemblesOtherImport(resemblingBy(EnteredBy.OtherImportPath), windows.matchingDateWindowDays)
                TransactionStatus.NeedsReview -> when {
                    transaction.stableReference == null -> StatusReason.NoStableReference(identicalTo(transaction, ofBankAccount))
                    resembling.any { it.enteredBy == EnteredBy.SlussenUnsupportedFormat } ->
                        StatusReason.ResemblesUnsupportedSlussenImport(resemblingBy(EnteredBy.SlussenUnsupportedFormat))
                    else -> StatusReason.AsStored(status)
                }
                else -> StatusReason.AsStored(status)
            }
        }

        /** UC-003 BR-002: the other stored Bank Transactions without a Stable Reference with the same date, amount, payee and memo. */
        private fun identicalTo(transaction: StoredBankTransaction, ofBankAccount: List<StoredBankTransaction>): Int = ofBankAccount.count {
            it.id != transaction.id && it.stableReference == null && it.bookedDate == transaction.bookedDate &&
                it.amount == transaction.amount && it.payee == transaction.payee && it.memo == transaction.memo
        }

        /**
         * The Transaction Statuses "What the statuses mean" lists on the Bank Account screen of this build
         * (`bank-account.html#statuses-this-build`): those a Comparison gives without any decision of the Operator. Deferred
         * and Decision No Longer Holds follow from a Review Decision (UC-004) and join the list with the decision screens;
         * no Transfer Decision or Sent Record exists yet, so Outcome Unknown, Held Transfer, Arrived As Transfer Counterpart
         * and Transfer Counterpart Changed do not occur.
         */
        val GIVEN_BY_THIS_BUILD: List<TransactionStatus> = listOf(
            TransactionStatus.New,
            TransactionStatus.MatchForTypedEntry,
            TransactionStatus.AlreadyInBudgetApp,
            TransactionStatus.PossibleImportByOtherPath,
            TransactionStatus.NeedsReview,
            TransactionStatus.TransferLeg,
            TransactionStatus.GuessedTransfer,
            TransactionStatus.DeletedInBudgetApp,
            TransactionStatus.MergedInBudgetApp,
            TransactionStatus.TooOld,
            TransactionStatus.BeforeStartDate,
            TransactionStatus.InTheFuture,
        )
    }
}
