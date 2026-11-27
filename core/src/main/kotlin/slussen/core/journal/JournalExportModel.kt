package slussen.core.journal

import slussen.core.operation.OperationRefusal
import slussen.core.port.JournalEntry
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

/** Why the Budget App was not read for one served Budget of a Journal Export (UC-011 A2), in Slussen's own words. */
sealed interface BudgetNotRead {
    /** No credentials for the Budget App are entered, so it is not contacted (UC-014 BR-005). */
    data object CredentialsMissing : BudgetNotRead

    /** The Budget App refused Slussen's credentials, before this export or during its read (UC-014 BR-005). */
    data object CredentialsRefused : BudgetNotRead

    /** The Budget App did not answer, could not be reached or its answer could not be used. */
    data class NoAnswer(val kind: FailureKind) : BudgetNotRead

    /** The read was not sent: the operation it belonged to had ended (ADR-001 §4.0). */
    data object NotSent : BudgetNotRead
}

/** Where one served Budget's transactions in a Journal Export come from (UC-011 BR-007, A2 step 2). */
sealed interface ExportedBudget {
    val name: String
    val currency: CurrencyCode

    /** Read from the Budget App at the export, at [readAt], and not stored (BR-002). */
    data class Read(override val name: String, override val currency: CurrencyCode, val readAt: Instant) : ExportedBudget

    /** A2: from Slussen's stored copy, as it was at the last successful read of the Budget, at [lastReadAt]. */
    data class FromStoredCopy(
        override val name: String,
        override val currency: CurrencyCode,
        val lastReadAt: Instant,
        val why: BudgetNotRead,
    ) : ExportedBudget

    /** A2: never read, so none of its transactions are in the export. */
    data class NeverRead(override val name: String, override val currency: CurrencyCode, val why: BudgetNotRead) : ExportedBudget
}

/** Why no Journal Export was written. */
sealed interface ExportFailure {
    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : ExportFailure

    /** The Decision Journal could not be read. */
    data object JournalNotRead : ExportFailure

    /** Slussen's own stored data (the served Budgets, the Operator Settings or the stored copy) could not be read. */
    data object StoredDataNotRead : ExportFailure

    /** A3: the file could not be written completely at the chosen place. */
    data object NotWritten : ExportFailure
}

/** What a Journal Export came to (UC-011 step 5, A3). */
sealed interface JournalExportOutcome {
    /**
     * Step 5: the Journal Export is complete with [entries] entries and [transactions] of the Budget App's transactions,
     * the sources of its served Budgets ([budgets]) and, for review, its [latest] entries, newest first.
     * [removedWhileWritten] of the entries noted at step 3 were removed, by the size limit or a clearing, before they were
     * written, and are not in it (BR-007).
     */
    data class Complete(
        val entries: Int,
        val transactions: Int,
        val budgets: List<ExportedBudget>,
        val latest: List<JournalEntry>,
        val removedWhileWritten: Int = 0,
    ) : JournalExportOutcome

    /**
     * No Journal Export was written, for [why]. The document Slussen was given is removed ([removed]); when it cannot be
     * removed it is incomplete and no Journal Export (A3 step 1).
     */
    data class Failed(val why: ExportFailure, val removed: Boolean) : JournalExportOutcome
}
