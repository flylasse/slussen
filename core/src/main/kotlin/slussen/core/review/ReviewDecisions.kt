package slussen.core.review

import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationResult
import slussen.core.operation.unlessStorageFails
import slussen.core.port.ComparisonData
import slussen.core.port.ComparisonStore
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ReviewChoice
import slussen.core.port.ReviewDecided
import slussen.core.port.ReviewDecisionWrite
import slussen.core.port.ReviewRuling
import slussen.core.port.ReviewStore
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.status.BankAccountTransactions
import slussen.core.status.BudgetAppEntry
import slussen.core.status.OtherLeg
import slussen.core.transfer.TransferJournal
import slussen.pluginapi.value.Amount
import java.time.Clock
import java.time.LocalDate
import kotlin.math.abs

/**
 * Review Doubtful Transactions (UC-004), `:core` `review` (ADR-001 §7), as far as it needs no Sent Record and no Upload
 * Plan: the Review Decision sheet of a Bank Transaction, built from stored data only; Already Present, Import Anyway,
 * Defer and Re-upload, their change and clearing, each one storage operation with the Transaction Statuses that follow
 * from it (O-13, ADR-002 §3), admitted through the operation coordinator also while an Upload Plan awaits confirmation
 * (ADR-007 §2), and recorded in the Decision Journal (BR-005); and deferring an Unreadable Transaction (A9). The rules are
 * [ReviewRules] and the Comparison's own ([ComparisonRules]). What waits for the Uploads: the replacement of an open
 * Upload Plan (A8), the send of a Re-upload and its being used up (BR-003), the lock by a Sent Record (BR-004 is built on
 * the Budget App holding the Bank Transaction's own Import Identifier), and Outcome Unknown (A10, BR-008).
 */
class ReviewDecisions(
    private val connections: ConnectionStore,
    private val reviews: ReviewStore,
    private val comparisons: ComparisonStore,
    private val coordinator: OperationCoordinator,
    private val settings: OperatorSettingsStore,
    private val journal: DecisionJournal,
    private val clock: Clock,
    /** The Budget App's Matching Date Window, declared by its part (C-009). */
    private val matchingDateWindowDays: Int,
    /** Whether the Budget App refuses a reused Import Identifier, declared by its part (UC-006 BR-007). */
    private val budgetAppRefusesReusedImportIdentifier: Boolean,
) {
    private suspend fun windows(): ComparisonWindows =
        ComparisonWindows.of(settings.current(), matchingDateWindowDays, LocalDate.ofInstant(clock.instant(), clock.zone))

    /** The served Budget the Bank Account of [transaction] is mapped into, or null when it is mapped into none. */
    private suspend fun budgetOf(transaction: StoredBankTransaction): StoredBudget? {
        val bankAccount = connections.bankAccounts().firstOrNull { it.id == transaction.bankAccountId }
        return connections.budgets().firstOrNull { budget -> budget.served && budget.budgetAccounts.any { it.id == bankAccount?.mapping?.budgetAccountId } }
    }

    // UC-004 BR-006: The sheet is worked out by the rules of the Comparison from the stored copy of the Budget App Transactions, without reading the Budget App.
    /**
     * Steps 1-2 and 4, A4-A7, A11: the Review Decision sheet of the Bank Transaction [storedBankTransaction], or null when
     * there is none. It reads stored data only and stores nothing.
     */
    suspend fun sheet(storedBankTransaction: Long): ReviewSheet? {
        val transaction = reviews.bankTransaction(storedBankTransaction) ?: return null
        val bankAccounts = connections.bankAccounts()
        val bankAccount = bankAccounts.firstOrNull { it.id == transaction.bankAccountId } ?: return null
        val budget = budgetOf(transaction) ?: return undecidable(transaction, bankAccount, Undecidable.BankAccountNotCompared)
        val windows = windows()
        val data = comparisons.comparisonDataOf(setOf(budget.id))
        val standing = when (val ruled = ReviewRules.standingOf(storedBankTransaction, data, windows, budgetAppRefusesReusedImportIdentifier)) {
            is Ruled.Stands -> ruled.standing
            is Ruled.Cannot -> return undecidable(transaction, bankAccount, ruled.reason)
            Ruled.NoSuchTransaction -> return null
        }
        val names = budget.budgetAccounts.associate { it.id to it.name }
        fun entry(stored: StoredBudgetAppTransaction) = BudgetAppEntry(stored.transactionDate, stored.amount, stored.payee, names[stored.budgetAccountId].orEmpty())
        val held = data.budgetAppTransactions.filter { it.budgetAccountId == bankAccount.mapping?.budgetAccountId }
        return ReviewSheet(
            storedBankTransaction = transaction.id,
            bankAccountId = bankAccount.id,
            bankAccountName = bankAccount.name,
            bookedDate = transaction.bookedDate,
            amount = transaction.amount,
            currency = transaction.currency,
            payee = transaction.payee,
            memo = transaction.memo,
            status = standing.transaction.status,
            statusWithoutDecision = standing.withoutDecision,
            // Step 2: the reason it is doubtful, derived as the Bank Account screen derives it.
            why = standing.own.takeIf { it == TransactionStatus.NeedsReview || it == TransactionStatus.PossibleImportByOtherPath }?.let { own ->
                BankAccountTransactions.doubtOf(transaction, own, data.bankTransactions.filter { it.bankAccountId == bankAccount.id }, held, windows, ::entry)
            },
            decision = standing.decision?.let { decision ->
                DecisionShown(
                    decision.choice, decision.decidedAt,
                    chosen = ComparisonRules.chosenOf(decision, data.budgetAppTransactions)?.let(::entry),
                    noLongerHolds = ReviewRules.whyNoLongerHolds(decision, data.budgetAppTransactions),
                )
            },
            locked = standing.locked,
            choices = standing.choices,
            canClear = standing.canClear,
            resembling = standing.resembling.map { ReviewRules.resemblingOf(it, budget) },
            transfer = transferOf(standing, data, bankAccounts, names),
            undecidable = null,
        )
    }

    /** The sheet of a Bank Transaction that cannot be decided now, with why: what is stored of it, and no choice. */
    private fun undecidable(transaction: StoredBankTransaction, bankAccount: StoredBankAccount, reason: Undecidable) = ReviewSheet(
        storedBankTransaction = transaction.id,
        bankAccountId = bankAccount.id,
        bankAccountName = bankAccount.name,
        bookedDate = transaction.bookedDate,
        amount = transaction.amount,
        currency = transaction.currency,
        payee = transaction.payee,
        memo = transaction.memo,
        status = transaction.status,
        statusWithoutDecision = null,
        why = null,
        decision = null,
        locked = false,
        choices = emptyList(),
        canClear = false,
        resembling = emptyList(),
        transfer = null,
        undecidable = reason,
    )

    // UC-004 BR-009: On a Transfer Leg the Review Decision decides its whole Transfer; after Split each Transfer Leg is decided as an ordinary Bank Transaction.
    /** A11 step 1: the Transfer the Bank Transaction is a Transfer Leg of, with the other Transfer Leg or the side it waits for. */
    private fun transferOf(standing: Standing, data: ComparisonData, bankAccounts: List<StoredBankAccount>, names: Map<Long, String>): TransferOfLeg? {
        val transfer = standing.transfer ?: return null
        val other = data.bankTransactions.firstOrNull { it.transferId == transfer.id && it.id != standing.transaction.id }
        return TransferOfLeg(
            type = transfer.type,
            outflowBudgetAccountName = transfer.outflowBudgetAccountId?.let { names[it].orEmpty() },
            inflowBudgetAccountName = names[transfer.inflowBudgetAccountId].orEmpty(),
            amount = transfer.amount,
            otherLeg = other?.let { leg -> OtherLeg(bankAccounts.firstOrNull { it.id == leg.bankAccountId }?.name.orEmpty(), leg.bookedDate, leg.amount, leg.payee) },
            decision = transfer.decision,
            decidesWholeTransfer = transfer.decision != TransferDecision.Split,
        )
    }

    /**
     * Steps 3-8, A1-A5, A11: the Operator decides [choice] for the Bank Transaction [storedBankTransaction];
     * [chosenBudgetAppReference] names the Budget App Transaction that is the same (step 5), for Already Present.
     */
    suspend fun decide(storedBankTransaction: Long, choice: ReviewChoice, chosenBudgetAppReference: String? = null): ReviewOutcome =
        change(storedBankTransaction) { data, windows ->
            ReviewRules.decide(storedBankTransaction, choice, chosenBudgetAppReference, data, windows, budgetAppRefusesReusedImportIdentifier, clock.instant())
        }

    /** A6: the Operator clears the Review Decision of the Bank Transaction [storedBankTransaction]. */
    suspend fun clear(storedBankTransaction: Long): ReviewOutcome =
        change(storedBankTransaction) { data, windows -> ReviewRules.clear(storedBankTransaction, data, windows, budgetAppRefusesReusedImportIdentifier) }

    /**
     * One Review Decision, change or clearing as one operation: admitted by the coordinator, ruled and stored by O-13 all
     * or nothing with the Transaction Statuses that follow, then recorded in the Decision Journal.
     */
    private suspend fun change(
        storedBankTransaction: Long,
        rule: (ComparisonData, ComparisonWindows) -> Pair<ReviewDecisionWrite?, RuledOutcome>,
    ): ReviewOutcome {
        // ADR-007 §2: admitted also while an Upload Plan awaits confirmation, refused while an Upload is written.
        val result = coordinator.run(OperationKind.ReviewDecision, StartedBy.Operator) {
            var bankAccountNames = emptyMap<Long, String>()
            var budgetAccountNames = emptyMap<Long, String>()
            // NFR-023: O-13 is the last step that can fail, so "not stored" is only ever said of a decision that was not stored.
            val decided = unlessStorageFails<ReviewDecided<RuledOutcome>?>(null) {
                val transaction = reviews.bankTransaction(storedBankTransaction) ?: return@run ReviewOutcome.NotFound
                val budget = budgetOf(transaction) ?: return@run ReviewOutcome.NotDecidable(Undecidable.BankAccountNotCompared)
                val windows = windows()
                bankAccountNames = connections.bankAccounts().associate { it.id to it.name }
                budgetAccountNames = budget.budgetAccounts.associate { it.id to it.name }
                reviews.decide(setOf(budget.id)) { data ->
                    // UC-003 BR-016: Until that read of the Budget no Review Decision is taken on its Bank Transactions (UC-004 A12): refused with its reason, nothing stored.
                    if (budget.id in data.periodReadOwed) return@decide ReviewRuling(null, RuledOutcome(ReviewOutcome.NotDecidable(Undecidable.PeriodReadOwed)))
                    val (write, ruled) = rule(data, windows)
                    ReviewRuling(write, ruled)
                }
            } ?: return@run ReviewOutcome.NotStored
            // UC-004 BR-005: Every Review Decision, change and clearing is recorded in the Decision Journal, and recording never delays or blocks the decision.
            decided.answer.facts?.let { facts ->
                journal.record(JournalEventKind.OperatorDecision, detailsOf(facts, budgetAccountNames), facts.importIdentifier, bankAccountNames[facts.bankAccountId].orEmpty())
                // UC-011 BR-001: Every change of the Transaction Status Slussen gave a Bank Transaction is recorded, also that of another Transfer Leg which the Review Decision changed.
                journal.recordStatusChanges(decided.changes.filterNot { it.storedBankTransaction == storedBankTransaction }, bankAccountNames)
                // UC-005 BR-015: A Transfer Decision the conclusions stored with the Review Decision dropped is recorded with its reason.
                TransferJournal.recordDropped(journal, decided.answer.dropped, bankAccountNames, budgetAccountNames)
            }
            decided.answer.outcome
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> ReviewOutcome.Unavailable(result.reason)
        }
    }

    /** What the Decision Journal says of one Review Decision, in Slussen's own words, never a payee or memo (UC-011 BR-003). */
    private fun detailsOf(facts: DecisionFacts, budgetAccountNames: Map<Long, String>): String {
        val named = facts.named?.let {
            ", naming the Budget App Transaction booked ${it.transactionDate}, ${amountText(it.amount)} in Budget Account ${budgetAccountNames[it.budgetAccountId].orEmpty()}"
        }.orEmpty()
        val what = when {
            facts.after == null -> "Review Decision ${facts.before?.text} cleared"
            facts.before == null -> "Review Decision ${facts.after.text}$named"
            else -> "Review Decision changed from ${facts.before.text} to ${facts.after.text}$named"
        }
        return "$what; Transaction Status Slussen had given: ${facts.hadGiven.text}; Transaction Status now: ${facts.now.text}"
    }

    private fun amountText(amount: Amount): String {
        val thousandths = amount.thousandths
        val rest = (abs(thousandths) % THOUSANDTHS).toString().padStart(3, '0')
        return "${if (thousandths < 0) "-" else ""}${abs(thousandths) / THOUSANDTHS}.$rest"
    }

    // UC-004 BR-007: An Unreadable Transaction is never uploaded; the Operator can only defer it, or clear that decision again.
    /** A9 step 1: the sheet of the Unreadable Transaction [storedUnreadableTransaction], or null when there is none. */
    suspend fun unreadableSheet(storedUnreadableTransaction: Long): UnreadableSheet? {
        val unreadable = reviews.unreadableTransaction(storedUnreadableTransaction) ?: return null
        return UnreadableSheet(
            storedUnreadableTransaction = unreadable.id,
            bankAccountName = connections.bankAccounts().firstOrNull { it.id == unreadable.bankAccountId }?.name.orEmpty(),
            bookedDate = unreadable.bookedDate,
            amount = unreadable.amount,
            currency = unreadable.currency,
            payee = unreadable.payee,
            memo = unreadable.memo,
            reason = unreadable.reason,
            firstSeenAt = unreadable.firstSeenAt,
            status = unreadable.status,
            canDefer = unreadable.status == UnreadableTransactionStatus.NeedsReview,
            canClear = unreadable.status == UnreadableTransactionStatus.Deferred,
        )
    }

    /** A9 steps 2-4: the Operator defers the Unreadable Transaction [storedUnreadableTransaction]. */
    suspend fun deferUnreadable(storedUnreadableTransaction: Long): UnreadableOutcome =
        unreadableStatus(storedUnreadableTransaction, UnreadableTransactionStatus.Deferred)

    /** BR-007: the Operator clears that decision again, which returns it to Needs Review. */
    suspend fun clearUnreadable(storedUnreadableTransaction: Long): UnreadableOutcome =
        unreadableStatus(storedUnreadableTransaction, UnreadableTransactionStatus.NeedsReview)

    // UC-004 BR-007: The Operator can defer an Unreadable Transaction, which takes it out of the transactions waiting for a decision, or clear that decision again.
    private suspend fun unreadableStatus(storedUnreadableTransaction: Long, status: UnreadableTransactionStatus): UnreadableOutcome {
        val result = coordinator.run(OperationKind.ReviewDecision, StartedBy.Operator) {
            unlessStorageFails<UnreadableOutcome>(UnreadableOutcome.NotStored) {
                val unreadable = reviews.unreadableTransaction(storedUnreadableTransaction) ?: return@unlessStorageFails UnreadableOutcome.NotFound
                if (unreadable.status == status) return@unlessStorageFails UnreadableOutcome.Unchanged(status)
                val bankAccountName = connections.bankAccounts().firstOrNull { it.id == unreadable.bankAccountId }?.name.orEmpty()
                if (!reviews.storeUnreadableStatus(storedUnreadableTransaction, status)) return@unlessStorageFails UnreadableOutcome.NotFound
                val what = when (status) {
                    UnreadableTransactionStatus.Deferred ->
                        "Review Decision Defer; Transaction Status Slussen had given: ${unreadable.status.text}; Transaction Status now: ${status.text}"
                    UnreadableTransactionStatus.NeedsReview -> "Review Decision Defer cleared; Transaction Status now: ${status.text}"
                }
                // An Unreadable Transaction is no Bank Transaction and has no Import Identifier, so the entry names its Bank Account in its text.
                // UC-004 BR-005: Every Review Decision, change and clearing is recorded in the Decision Journal.
                journal.record(JournalEventKind.OperatorDecision, "Unreadable Transaction of Bank Account $bankAccountName (${unreadable.reason}): $what")
                UnreadableOutcome.Stored(status)
            }
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> UnreadableOutcome.Unavailable(result.reason)
        }
    }

    private companion object {
        const val THOUSANDTHS = 1000L
    }
}
