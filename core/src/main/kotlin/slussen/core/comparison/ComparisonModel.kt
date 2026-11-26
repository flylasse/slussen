package slussen.core.comparison

import slussen.core.operation.OperationRefusal
import slussen.pluginapi.request.FailureKind
import java.time.Instant

/** What the read of one Budget came to in a Comparison (UC-003 step 1, A1). */
sealed interface BudgetReadOutcome {
    val budgetId: Long
    val budgetName: String

    /** Step 1: the Budget was read at [readAt] and its stored copy replaced; its Bank Transactions were compared. */
    data class Read(override val budgetId: Long, override val budgetName: String, val readAt: Instant) : BudgetReadOutcome

    /**
     * A1: the Budget App did not answer, was unavailable or refused the read of this Budget ([kind], null when the read
     * was not sent). Its Transaction Statuses and stored copy are those of the last successful read at [lastReadAt],
     * null before any; a Bank Transaction stored since then is not compared yet.
     */
    data class NotRead(
        override val budgetId: Long,
        override val budgetName: String,
        val kind: FailureKind?,
        val lastReadAt: Instant?,
    ) : BudgetReadOutcome
}

/** What a Comparison came to (UC-003), as the screens show it. */
sealed interface ComparisonReport {
    /**
     * Steps 1-7 for every Budget that a Bank Account is mapped to: each Budget read or not (A1), and how many
     * Transaction Statuses changed (step 6).
     */
    data class Compared(val budgets: List<BudgetReadOutcome>, val statusesChanged: Int) : ComparisonReport

    /** A2: the Budget App refused Slussen's credentials; every status and stored copy is kept; "credentials refused". */
    data object CredentialsRefused : ComparisonReport

    /** UC-014 BR-005: no credentials of the Budget App are entered, so it is not contacted; "credentials missing". */
    data object CredentialsMissing : ComparisonReport

    /** No served Budget has a mapped Bank Account: there is nothing to compare and the Budget App is not contacted. */
    data object NothingToCompare : ComparisonReport
}

/** The outcome of asking for a Comparison on its own (the Operator reads the Budget App again). */
sealed interface ComparisonStart {
    data class Ran(val report: ComparisonReport) : ComparisonStart

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : ComparisonStart
}
