package slussen.core.review

import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.ChosenBudgetAppTransaction
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonData
import slussen.core.port.DroppedTransferDecision
import slussen.core.port.EnteredBy
import slussen.core.port.ReviewChoice
import slussen.core.port.ReviewDecisionWrite
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.StoredReviewDecision
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import java.time.Instant

/**
 * Where one Bank Transaction stands for a Review Decision, worked out from stored data by the rules of the Comparison
 * (UC-004 BR-006): its Review Decision, the Transaction Status the rules give it by itself without that decision ([own],
 * which decides its choices and its lock), the status it has without the decision and with it as the Comparison concludes
 * them today ([withoutDecision], [withDecision]), and the Imports by an Other Import Path that resemble it ([resembling]).
 */
data class Standing(
    val transaction: StoredBankTransaction,
    val bankAccount: StoredBankAccount,
    val budget: StoredBudget,
    val decision: StoredReviewDecision?,
    val own: TransactionStatus,
    val withoutDecision: TransactionStatus,
    val withDecision: TransactionStatus,
    val resembling: List<StoredBudgetAppTransaction>,
    /** The stored Transfer it is a Transfer Leg of, or null. */
    val transfer: StoredTransfer?,
    val locked: Boolean,
    val choices: List<ReviewChoice>,
) {
    /** UC-003 BR-009: its Already Present decision does not hold any more, and no rule before the decision gives its status. */
    val noLongerHolds: Boolean get() = decision != null && withDecision == TransactionStatus.DecisionNoLongerHolds

    /** UC-004 BR-001: clearing is offered for every Review Decision that is not locked. */
    val canClear: Boolean get() = decision != null && !locked
}

/** What the rules say of a Bank Transaction a decision is asked for: where it stands, or why it cannot be decided. */
sealed interface Ruled {
    data class Stands(val standing: Standing) : Ruled

    data class Cannot(val reason: Undecidable) : Ruled

    data object NoSuchTransaction : Ruled
}

/**
 * What the Decision Journal records of one Review Decision, its change or its clearing (UC-004 BR-005): the decision it
 * replaces, the one stored, the Budget App Transaction an Already Present decision names, the Transaction Status Slussen
 * had given and the status the Bank Transaction has now.
 */
data class DecisionFacts(
    val importIdentifier: String,
    val bankAccountId: Long,
    val before: ReviewChoice?,
    val after: ReviewChoice?,
    val named: StoredBudgetAppTransaction?,
    val hadGiven: TransactionStatus,
    val now: TransactionStatus,
)

/** What a ruling answers: the outcome for the Operator, and what the Decision Journal records when something is stored. */
data class RuledOutcome(
    val outcome: ReviewOutcome,
    val facts: DecisionFacts? = null,
    /** UC-005 BR-015: the Transfer Decisions the conclusions stored with the decision drop, for the Decision Journal. */
    val dropped: List<DroppedTransferDecision> = emptyList(),
)

/**
 * The rules of Review Doubtful Transactions (UC-004), `:core` `review` (ADR-001 §7): the choices a Transaction Status
 * offers, the lock, the status without a decision, and what a decision stores. Pure: the read model applies them to the
 * stored data it shows, and O-13 applies them inside its transaction to what is stored (ADR-002 §3).
 */
object ReviewRules {
    // UC-004 BR-001: Each Transaction Status offers its own choices, and one that is not doubtful offers none.
    /**
     * The choices of a Bank Transaction whose status by itself, without its Review Decision, is [own]. [noLongerHolds]:
     * its Already Present decision does not hold any more, which keeps the four choices of Decision No Longer Holds
     * whatever [own] is; [resembles]: at least one Import by an Other Import Path resembles it;
     * [budgetAppRefusesReusedImportIdentifier]: what the Budget App's part declares (UC-006 BR-007).
     */
    fun choicesOf(
        own: TransactionStatus,
        noLongerHolds: Boolean,
        resembles: Boolean,
        budgetAppRefusesReusedImportIdentifier: Boolean,
    ): List<ReviewChoice> = when {
        // UC-004 BR-002: Already Present is offered only while at least one Import by an Other Import Path resembles the Bank Transaction.
        noLongerHolds -> listOfNotNull(ReviewChoice.AlreadyPresent.takeIf { resembles }, ReviewChoice.ImportAnyway, ReviewChoice.Defer, ReviewChoice.ReUpload)
        own == TransactionStatus.PossibleImportByOtherPath -> listOf(ReviewChoice.AlreadyPresent, ReviewChoice.ImportAnyway, ReviewChoice.Defer)
        own == TransactionStatus.NeedsReview || own == TransactionStatus.TransferCounterpartChanged -> listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer)
        own == TransactionStatus.DeletedInBudgetApp || own == TransactionStatus.MergedInBudgetApp -> listOf(ReviewChoice.ReUpload)
        // UC-004 BR-008: Outcome Unknown waits for the Operator's decision only when the Budget App does not refuse a reused Import Identifier.
        own == TransactionStatus.OutcomeUnknown && !budgetAppRefusesReusedImportIdentifier -> listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer)
        else -> emptyList()
    }

    // UC-004 BR-004: A Review Decision is locked while its Bank Transaction is itself Already In Budget App because Slussen sent it.
    /**
     * Whether the Review Decision of a Bank Transaction is locked, from the status [own] the rules give it by itself
     * without its decision: without a decision, only its own Import Identifier held in its Budget Account makes it
     * Already In Budget App (UC-003 BR-003). Neither the Transfer Decision of its Transfer nor the status of another
     * Transfer Leg locks it; Deleted In Budget App, Merged In Budget App and Outcome Unknown never do. BR-004 also locks
     * a Transfer Leg that is Arrived As Transfer Counterpart: no rule gives that status before the Uploads are built
     * (Wave 4), so the second condition is not reached until then; it stands here so that the lock is there when the
     * status is.
     */
    fun locked(own: TransactionStatus): Boolean =
        own == TransactionStatus.AlreadyInBudgetApp || own == TransactionStatus.ArrivedAsTransferCounterpart

    /** [data] with the Review Decision of [storedBankTransaction] replaced by [decision], or removed when it is null. */
    fun withDecision(data: ComparisonData, storedBankTransaction: Long, decision: StoredReviewDecision?): ComparisonData =
        data.copy(reviewDecisions = data.reviewDecisions.filterNot { it.storedBankTransaction == storedBankTransaction } + listOfNotNull(decision))

    // UC-004 BR-006: Slussen works out the status without a decision, and the choices, by the rules of the Comparison from its stored copy, without reading the Budget App.
    /** Where the Bank Transaction [storedBankTransaction] stands in [data], the stored data of its Budget, on the day of [windows]. */
    fun standingOf(storedBankTransaction: Long, data: ComparisonData, windows: ComparisonWindows, budgetAppRefusesReusedImportIdentifier: Boolean): Ruled {
        val transaction = data.bankTransactions.firstOrNull { it.id == storedBankTransaction } ?: return Ruled.NoSuchTransaction
        val bankAccount = data.bankAccounts.firstOrNull { it.id == transaction.bankAccountId } ?: return Ruled.Cannot(Undecidable.BankAccountNotCompared)
        val budget = data.budgets.firstOrNull { budget -> budget.budgetAccounts.any { it.id == bankAccount.mapping?.budgetAccountId } }
        if (bankAccount.retired || budget == null) return Ruled.Cannot(Undecidable.BankAccountNotCompared)
        val transfer = data.transfers.firstOrNull { it.id == transaction.transferId }
        val withDecision = ComparisonRules.conclude(data, windows).statuses[storedBankTransaction] ?: return Ruled.Cannot(Undecidable.NotComparedYet)
        val decision = data.reviewDecisions.firstOrNull { it.storedBankTransaction == storedBankTransaction }
        val withoutDecision = if (decision == null) {
            withDecision
        } else {
            ComparisonRules.conclude(withDecision(data, storedBankTransaction, null), windows).statuses.getValue(storedBankTransaction)
        }
        val held = data.budgetAppTransactions.filter { it.budgetAccountId == bankAccount.mapping?.budgetAccountId }
        val own = ComparisonRules.statusOf(transaction, bankAccount, budget, held, windows)
        // UC-003 BR-007: The Imports by an Other Import Path with the same amount within the Matching Date Window are the ones that resemble it.
        val resembling = ComparisonRules.resembling(transaction, held, windows).filter { it.enteredBy == EnteredBy.OtherImportPath }
        val locked = locked(own)
        val noLongerHolds = decision != null && withDecision == TransactionStatus.DecisionNoLongerHolds
        return Ruled.Stands(
            Standing(
                transaction, bankAccount, budget, decision, own, withoutDecision, withDecision, resembling, transfer, locked,
                choices = if (locked) emptyList() else choicesOf(own, noLongerHolds, resembling.isNotEmpty(), budgetAppRefusesReusedImportIdentifier),
            ),
        )
    }

    /** UC-003 BR-009, A4: why the Already Present [decision] does not hold against [copy], or null while it holds. */
    fun whyNoLongerHolds(decision: StoredReviewDecision, copy: List<StoredBudgetAppTransaction>): NoLongerHolds? {
        if (decision.choice != ReviewChoice.AlreadyPresent || ComparisonRules.alreadyPresentHolds(decision, copy)) return null
        val stored = ComparisonRules.chosenOf(decision, copy)
        return when {
            stored == null || stored.state != BudgetAppTransactionState.Present -> NoLongerHolds.NoLongerThere
            stored.budgetAccountId != decision.chosen?.budgetAccountId -> NoLongerHolds.InAnotherBudgetAccount
            else -> NoLongerHolds.AnotherAmount
        }
    }

    /**
     * Steps 3-7, A1-A3, A5-A7 inside O-13: what deciding [choice] for [storedBankTransaction] at [now] stores, with the Budget
     * App Transaction [chosenBudgetAppReference] the Operator named for Already Present, or null when none was named.
     */
    fun decide(
        storedBankTransaction: Long,
        choice: ReviewChoice,
        chosenBudgetAppReference: String?,
        data: ComparisonData,
        windows: ComparisonWindows,
        budgetAppRefusesReusedImportIdentifier: Boolean,
        now: Instant,
    ): Pair<ReviewDecisionWrite?, RuledOutcome> {
        val standing = when (val ruled = standingOf(storedBankTransaction, data, windows, budgetAppRefusesReusedImportIdentifier)) {
            is Ruled.Stands -> ruled.standing
            is Ruled.Cannot -> return null to RuledOutcome(ReviewOutcome.NotDecidable(ruled.reason))
            Ruled.NoSuchTransaction -> return null to RuledOutcome(ReviewOutcome.NotFound)
        }
        // UC-004 BR-004: From its Upload on the Review Decision is locked: it cannot be changed or cleared.
        if (standing.locked) return null to RuledOutcome(ReviewOutcome.Locked)
        // UC-004 BR-001: Only a choice its Transaction Status offers is stored.
        if (choice !in standing.choices) return null to RuledOutcome(ReviewOutcome.NotOffered(standing.choices))
        val named = if (choice == ReviewChoice.AlreadyPresent) {
            // UC-004 BR-002: An Already Present decision always names one specific Budget App Transaction among those that resemble the Bank Transaction; the only one is taken without asking (A3).
            val among = if (chosenBudgetAppReference == null) {
                standing.resembling.singleOrNull()
            } else {
                standing.resembling.firstOrNull { it.budgetAppReference == chosenBudgetAppReference }
            }
            among ?: return null to RuledOutcome(ReviewOutcome.ChooseTransaction(standing.resembling.map { resemblingOf(it, standing.budget) }))
        } else {
            null
        }
        // UC-004 BR-002: The Budget Account and the amount of the chosen Budget App Transaction are recorded so that UC-003 BR-009 can recheck the decision.
        val chosen = named?.let { ChosenBudgetAppTransaction(it.budgetAppReference, it.budgetAccountId, it.amount) }
        return written(standing, StoredReviewDecision(storedBankTransaction, choice, chosen, decidedAt = now), named, data, windows)
    }

    /** A6 inside O-13: what clearing the Review Decision of [storedBankTransaction] stores. */
    fun clear(
        storedBankTransaction: Long,
        data: ComparisonData,
        windows: ComparisonWindows,
        budgetAppRefusesReusedImportIdentifier: Boolean,
    ): Pair<ReviewDecisionWrite?, RuledOutcome> {
        val standing = when (val ruled = standingOf(storedBankTransaction, data, windows, budgetAppRefusesReusedImportIdentifier)) {
            is Ruled.Stands -> ruled.standing
            is Ruled.Cannot -> return null to RuledOutcome(ReviewOutcome.NotDecidable(ruled.reason))
            Ruled.NoSuchTransaction -> return null to RuledOutcome(ReviewOutcome.NotFound)
        }
        // UC-004 BR-004: From its Upload on the Review Decision is locked: it cannot be changed or cleared.
        if (standing.locked) return null to RuledOutcome(ReviewOutcome.Locked)
        if (standing.decision == null) return null to RuledOutcome(ReviewOutcome.NothingToClear)
        return written(standing, decision = null, named = null, data, windows)
    }

    // UC-004 BR-006: The Bank Transaction has the status that follows from the decision, and clearing gives the status the Comparison gives without a decision.
    /** The write of [decision] in place of the stored one, with the conclusions of the Comparison that follow from it. */
    private fun written(
        standing: Standing,
        decision: StoredReviewDecision?,
        named: StoredBudgetAppTransaction?,
        data: ComparisonData,
        windows: ComparisonWindows,
    ): Pair<ReviewDecisionWrite, RuledOutcome> {
        val id = standing.transaction.id
        val conclusions: ComparisonConclusions = ComparisonRules.conclude(withDecision(data, id, decision), windows)
        val status = conclusions.statuses.getValue(id)
        // UC-004 BR-005: The Transaction Status Slussen had given is the status without a Review Decision, or Decision No Longer Holds when the decision that is replaced or cleared did not hold any more.
        val hadGiven = if (standing.noLongerHolds) TransactionStatus.DecisionNoLongerHolds else standing.withoutDecision
        val facts = DecisionFacts(
            importIdentifier = standing.transaction.importIdentifier,
            bankAccountId = standing.bankAccount.id,
            before = standing.decision?.choice,
            after = decision?.choice,
            named = named,
            hadGiven = hadGiven,
            now = status,
        )
        return ReviewDecisionWrite(id, decision, conclusions) to RuledOutcome(ReviewOutcome.Stored(decision?.choice, status), facts, conclusions.dropped)
    }

    /** One of the Imports by an Other Import Path that resemble a Bank Transaction, as the list of step 4 shows it. */
    fun resemblingOf(stored: StoredBudgetAppTransaction, budget: StoredBudget): ResemblingImport = ResemblingImport(
        stored.budgetAppReference, stored.transactionDate, stored.payee, stored.amount,
        budget.budgetAccounts.firstOrNull { it.id == stored.budgetAccountId }?.name.orEmpty(),
    )
}
