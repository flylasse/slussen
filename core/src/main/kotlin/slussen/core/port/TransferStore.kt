package slussen.core.port

/** A Funded By or a Transfer Partner the Operator keeps from a Transfer Decision (UC-005 A3, BR-006, BR-016). */
enum class OtherSideKind(val text: String) {
    FundedBy("Funded By"),
    TransferPartner("Transfer Partner"),
}

/** UC-005 A3 step 4: the Budget Account [budgetAccountId] kept as the Funded By or the Transfer Partner of [bankAccountId]. */
data class KeptOtherSide(val bankAccountId: Long, val kind: OtherSideKind, val budgetAccountId: Long)

/**
 * What the core rules for one Transfer Decision inside O-13 (ADR-002 §3; UC-005 steps 4-5, A1-A4, A8, BR-016): the
 * Transfer as it is stored from now on ([transfer], with its decision, the time of it, its two sides and its flag; an
 * `id` of 0 stores a new Transfer, for a Guessed Transfer that had none because Slussen proposed no paying Budget
 * Account); its Transfer Legs from now on ([legs], one or two), so that a Transfer Leg it had and that is not among them
 * is released; the Transfers without a Transfer Decision that the choice of another Budget Account took a Transfer Leg
 * from, which are removed with their Transfer Legs released ([dissolved]); the Funded By or Transfer Partner the Operator
 * keeps ([kept]); and the Transaction Status the decision gives each of its Transfer Legs ([statuses], UC-005 BR-010).
 */
data class TransferDecisionWrite(
    val transfer: StoredTransfer,
    val legs: List<Long>,
    val dissolved: List<Long>,
    val kept: KeptOtherSide?,
    val statuses: Map<Long, TransactionStatus>,
)

/**
 * What the core's rule answers inside O-13: [write] is what is stored, null when the rule refuses and nothing is stored;
 * [answer] goes back to the caller either way.
 */
data class TransferRuling<out T>(val write: TransferDecisionWrite?, val answer: T)

/**
 * What O-13 did: the rule's [answer], the id of the Transfer stored ([transferId], null when nothing was stored) and the
 * Transaction Statuses it changed; none when nothing was stored.
 */
data class TransferDecided<out T>(val answer: T, val transferId: Long?, val changes: List<StatusChange>)

/** O-13 `TransferStore.decide` (ADR-002 §3) for the Transfer Decisions of UC-005, in `slussen.db`. */
interface TransferStore {
    /**
     * O-13, all or nothing: reads the [ComparisonData] of [budgetIds] from storage exactly as O-5 reads it, asks [rule]
     * (the core's rules, applied inside the transaction to what is stored) and, when it gives a
     * [TransferRuling.write], stores the Transfer with its Transfer Decision and its sides, removes each dissolved
     * Transfer that still has no Transfer Decision and releases its Transfer Legs, gives the Transfer's Transfer Legs its
     * `transfer_id` and releases every other one it had, sets the Funded By or Transfer Partner the Operator keeps,
     * writes the Transaction Statuses of its Transfer Legs, raises `coordinator_state.state_version` (ADR-007 §6 step 2)
     * and sets `coordinator_state.comparison_pending` (ADR-007 §3a), so that no Upload Plan is prepared before the
     * Transfers are detected again from stored data (UC-005 BR-016): O-5 follows within the same operation, or at the
     * next start when Slussen stops in between. When the rule gives no write, nothing is stored, raised or set.
     */
    suspend fun <T> decide(budgetIds: Set<Long>, rule: (ComparisonData) -> TransferRuling<T>): TransferDecided<T>

    /** The stored Bank Transaction [storedBankTransaction], through which the Operator selects its Transfer, or null when there is none. */
    suspend fun bankTransaction(storedBankTransaction: Long): StoredBankTransaction?
}
