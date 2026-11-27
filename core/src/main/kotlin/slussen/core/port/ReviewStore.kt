package slussen.core.port

/**
 * What the core rules for one Review Decision inside O-13 (ADR-002 §3): the decision that takes the place of the one
 * stored for the Bank Transaction [storedBankTransaction], or null to remove it, and the [conclusions] of the Comparison
 * that follow from the stored data with that change (UC-004 step 7, BR-006): the Transaction Status of the Bank
 * Transaction and of the other Transfer Legs of its Transfer among them (UC-004 BR-009, UC-005 BR-013). They are the whole
 * conclusions for the Budgets read, with every Transfer that holds, exactly as O-5 takes them: a stored Proposed Transfer
 * they do not continue is released.
 */
data class ReviewDecisionWrite(
    val storedBankTransaction: Long,
    val decision: StoredReviewDecision?,
    val conclusions: ComparisonConclusions,
)

/**
 * What the core's rule answers inside O-13: [write] is what is stored, null when the rule refuses and nothing is stored;
 * [answer] goes back to the caller either way.
 */
data class ReviewRuling<out T>(val write: ReviewDecisionWrite?, val answer: T)

/** What O-13 did: the rule's [answer], and the Transaction Statuses it changed; none when nothing was stored. */
data class ReviewDecided<out T>(val answer: T, val changes: List<StatusChange>)

/**
 * O-13 `ReviewStore.decide` (ADR-002 §3) for `review_decision`, and the status of an Unreadable Transaction the Operator
 * defers (UC-004 A9), in `slussen.db`.
 */
interface ReviewStore {
    /**
     * O-13, all or nothing: reads the [ComparisonData] of [budgetIds] from storage exactly as O-5 reads it, asks [rule]
     * (the core's rules, applied inside the transaction to what is stored) and, when it gives a [ReviewRuling.write],
     * stores or removes the Review Decision of its Bank Transaction, writes every Transaction Status of its conclusions
     * that changed and their Transfers as O-5 does, and raises `coordinator_state.state_version` (ADR-007 §6 step 2).
     * `comparison_pending` is left as it is. When the rule gives no write, nothing is stored and nothing is raised.
     */
    suspend fun <T> decide(budgetIds: Set<Long>, rule: (ComparisonData) -> ReviewRuling<T>): ReviewDecided<T>

    /** The stored Bank Transaction [storedBankTransaction], or null when there is none. */
    suspend fun bankTransaction(storedBankTransaction: Long): StoredBankTransaction?

    /** The stored Unreadable Transaction [storedUnreadableTransaction], or null when there is none. */
    suspend fun unreadableTransaction(storedUnreadableTransaction: Long): StoredUnreadableTransaction?

    /**
     * One update (UC-004 A9, BR-007): gives the Unreadable Transaction [storedUnreadableTransaction] the status [status];
     * true when it was stored, false when there is no such Unreadable Transaction. An Unreadable Transaction is never in
     * an Upload Plan, so `state_version` is left as it is.
     */
    suspend fun storeUnreadableStatus(storedUnreadableTransaction: Long, status: UnreadableTransactionStatus): Boolean
}
