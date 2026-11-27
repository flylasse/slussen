package slussen.core.settings

import slussen.core.comparison.Comparison
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.ConnectionStore
import slussen.core.port.DescriptionListChange
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.TransferDescriptionKind
import slussen.core.transfer.TransferDescriptions

/** One entry of a description list as Settings shows it (UC-010 step 1, BR-007): its text, and whether the Operator added it. */
data class DescriptionEntryShown(val text: String, val addedByOperator: Boolean)

/** One description list as Settings shows it: its entries in their stored order, and the default list it can be restored to (A8). */
data class DescriptionListShown(val kind: TransferDescriptionKind, val entries: List<DescriptionEntryShown>, val defaults: List<String>) {
    /** The texts of the entries, in their order. */
    val texts: List<String> get() = entries.map { it.text }
}

/** Both description lists (UC-010 step 1); [journalOn] says whether a change is recorded in the Decision Journal. */
data class DescriptionListsShown(val cardPayment: DescriptionListShown, val bankTransfer: DescriptionListShown, val journalOn: Boolean) {
    fun of(kind: TransferDescriptionKind): DescriptionListShown = if (kind == TransferDescriptionKind.CardPayment) cardPayment else bankTransfer
}

/** What the Operator does to one description list (UC-010 A7, A8). An entry is named by its text as stored. */
sealed interface DescriptionEdit {
    /** A7: adds [text] at the end of the list. */
    data class Add(val text: String) : DescriptionEdit

    /** A7: the entry [entry] becomes [text], in its place. */
    data class Change(val entry: String, val text: String) : DescriptionEdit

    /** A7: removes the entry [entry]. */
    data class Remove(val entry: String) : DescriptionEdit

    /** A8: the list becomes its default list, once the Operator has confirmed it. */
    data object Restore : DescriptionEdit
}

/** What a change of a description list came to (UC-010 A7, A8). */
sealed interface DescriptionOutcome {
    /**
     * A7 steps 2-3, A8 steps 3-4: the list is stored as [entries] and recorded in the Decision Journal; [effect] says
     * whether the Transfers were detected again and the statuses given again from stored data (step 5).
     */
    data class Stored(val entries: List<String>, val effect: SettingEffect) : DescriptionOutcome

    /** BR-008: the entry is empty once the spaces at its start and end are removed. Nothing is stored. */
    data object Empty : DescriptionOutcome

    /** BR-008 (S-B-27): the entry holds no character other than `*`, so it would match nothing. Nothing is stored. */
    data object OnlyStars : DescriptionOutcome

    /** BR-008: the entry, without the spaces at its start and end, is [length] characters, longer than [maxLength]. Nothing is stored. */
    data class TooLong(val length: Int, val maxLength: Int) : DescriptionOutcome

    /** BR-008: the list holds [entry], equal to the one entered without regard to case and accents. Nothing is stored. */
    data class AlreadyInList(val entered: String, val entry: String) : DescriptionOutcome

    /** The list after the change is the list as stored: nothing is stored, raised or recorded. */
    data object Unchanged : DescriptionOutcome

    /** The entry to change or remove is no longer in the list, changed since it was shown. Nothing is stored. */
    data object EntryGone : DescriptionOutcome

    /** Precondition (UC-006 BR-005): an Upload Plan awaits confirmation or an Upload is written. Nothing is stored. */
    data class Unavailable(val reason: OperationRefusal) : DescriptionOutcome

    /** Failure postconditions: the list could not be stored; it stays as it was, and no Transfer is detected again. */
    data object NotStored : DescriptionOutcome
}

/** The rules of UC-010 BR-007 and BR-008 for the description lists. Pure. */
object DescriptionRules {
    /** `TRANSFER_DESCRIPTION.text` is stored with a length of 100 (entity model): the longest entry, in characters. */
    const val MAX_LENGTH: Int = 100

    // UC-010 BR-007: The default Card Payment Descriptions and Bank Transfer Descriptions ship with the build from the repository.
    private val DEFAULT_CARD_PAYMENT = listOf(
        "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY", "CRD AUTOPAY",
        "CITICARDAP",
    )
    private val DEFAULT_BANK_TRANSFER = listOf(
        "ÖVERFÖRING", "INTERNET TRANSFER", "EXPRESS INTERNET TRANSFER", "AMERICAN EXPRESS TRANSFER", "ONLINE TRANSFER", "TRANSFER", "XFER",
    )

    /** The default list of [kind], in the order BR-007 lists it. */
    fun defaults(kind: TransferDescriptionKind): List<String> = when (kind) {
        TransferDescriptionKind.CardPayment -> DEFAULT_CARD_PAYMENT
        TransferDescriptionKind.BankTransfer -> DEFAULT_BANK_TRANSFER
    }

    // UC-010 BR-008: Within one list, no two entries are equal when compared without regard to case and accents.
    /** Whether [a] and [b] are equal entries: compared without regard to case and accents, as an entry matches (UC-005 BR-012). */
    fun same(a: String, b: String): Boolean = TransferDescriptions.withoutCaseAndAccents(a) == TransferDescriptions.withoutCaseAndAccents(b)

    // UC-010 BR-007: An entry that equals no entry of the default list of its own list, compared as BR-008 compares, is shown as added by the Operator; this is worked out each time it is shown and not stored.
    /** Whether [text], an entry of the list [kind], was added by the Operator: it equals no entry of that list's default list. */
    fun addedByOperator(kind: TransferDescriptionKind, text: String): Boolean = defaults(kind).none { same(it, text) }

    /** [edit] applied to [list], the entries of [kind] in their order: the list after it, or why it is refused. */
    fun applied(kind: TransferDescriptionKind, list: List<String>, edit: DescriptionEdit): Applied = when (edit) {
        is DescriptionEdit.Add -> {
            val entry = edit.text.trim()
            refusal(entry, list)?.let { Applied.Refused(it) } ?: Applied.To(list + entry)
        }
        is DescriptionEdit.Change -> {
            val at = list.indexOf(edit.entry)
            val entry = edit.text.trim()
            when {
                at < 0 -> Applied.Refused(DescriptionOutcome.EntryGone)
                else -> refusal(entry, list.filterIndexed { index, _ -> index != at })?.let { Applied.Refused(it) }
                    ?: Applied.To(list.toMutableList().also { it[at] = entry })
            }
        }
        is DescriptionEdit.Remove -> if (edit.entry in list) Applied.To(list - edit.entry) else Applied.Refused(DescriptionOutcome.EntryGone)
        // UC-010 BR-006: The Card Payment Descriptions and the Bank Transfer Descriptions can each be restored to their default list.
        DescriptionEdit.Restore -> Applied.To(defaults(kind))
    }

    // UC-010 BR-008: An entry is a non-empty text no longer than its stored length; spaces at its start and end are removed before it is checked and stored, so that an entry of spaces only is empty.
    /** Why [entry], already without the spaces at its start and end, cannot stand beside [others] in one list; null when it can. */
    private fun refusal(entry: String, others: List<String>): DescriptionOutcome? {
        val length = entry.codePointCount(0, entry.length)
        return when {
            entry.isEmpty() -> DescriptionOutcome.Empty
            // UC-010 BR-008: An entry holds at least one character other than `*`, since an entry of stars only would match nothing.
            entry.all { it == '*' } -> DescriptionOutcome.OnlyStars
            length > MAX_LENGTH -> DescriptionOutcome.TooLong(length, MAX_LENGTH)
            else -> others.firstOrNull { same(it, entry) }?.let { DescriptionOutcome.AlreadyInList(entry, it) }
        }
    }

    /** What [applied] gives. */
    sealed interface Applied {
        data class To(val entries: List<String>) : Applied

        data class Refused(val outcome: DescriptionOutcome) : Applied
    }
}

/**
 * The Card Payment Descriptions and Bank Transfer Descriptions (UC-010 step 1, A7, A8, BR-007 to BR-009), `:core`
 * `settings` (ADR-001 §7). A change is checked as BR-008 says, stored in one storage operation that sets the Comparison
 * marker with it (O-13), recorded with the list before and after (A7 step 3, A8 step 3, UC-011 BR-001), and followed by
 * the Transfers detected again and the statuses given again from stored data through the one operation for it,
 * [Comparison.statusesAgainAfter] (step 5, BR-009): a Transfer with a Transfer Decision is kept or dropped as UC-005
 * BR-015 says, and a Budget that owes a read of its read period is left as it is (UC-003 BR-016).
 */
class DescriptionLists(
    private val store: OperatorSettingsStore,
    private val comparison: Comparison,
    private val connections: ConnectionStore,
    private val copies: BudgetAppCopyStore,
    private val journal: DecisionJournal,
) {
    /** Step 1: both lists in their stored order, each entry with whether the Operator added it, worked out now (BR-007). */
    suspend fun shown(): DescriptionListsShown {
        val stored = store.transferDescriptions()
        fun list(kind: TransferDescriptionKind) = DescriptionListShown(
            kind,
            stored.filter { it.kind == kind }.map { DescriptionEntryShown(it.text, DescriptionRules.addedByOperator(kind, it.text)) },
            DescriptionRules.defaults(kind),
        )
        return DescriptionListsShown(list(TransferDescriptionKind.CardPayment), list(TransferDescriptionKind.BankTransfer), store.current().journalOn)
    }

    /**
     * A7, A8 (after the Operator confirmed it), step 5: [edit] of the list [kind]. A list that would stay as it is stores
     * and records nothing.
     */
    suspend fun edit(kind: TransferDescriptionKind, edit: DescriptionEdit): DescriptionOutcome {
        // UC-010 BR-009: A change to either list, including a restore, applies only to Transfers none of whose Transfer Legs has been uploaded; a Transfer that has a Transfer Decision of the Operator keeps that decision, and the changed lists only decide which Transfers Slussen proposes among the other Bank Transactions not yet uploaded.
        val result = comparison.statusesAgainAfter(OperationKind.OperatorSettingChange) { storeAndRecord(kind, edit) }
        return when (result) {
            is OperationResult.Refused -> DescriptionOutcome.Unavailable(result.reason)
            is OperationResult.Ran -> {
                val outcome = result.value.change
                if (outcome !is DescriptionOutcome.Stored) return outcome
                // UC-003 BR-016: Until the read of its read period, no conclusion from stored data changes the Transaction Statuses of a Budget that owes it; the answer names each such Budget.
                val effect = if (result.value.statusesGiven) {
                    SettingEffect.StatusesGiven(readSafely { budgetsOwingARead(connections, copies) })
                } else {
                    SettingEffect.StatusesPending
                }
                outcome.copy(effect = effect)
            }
        }
    }

    /** Within the operation: checks [edit], stores the list with the marker (O-13) and records it. */
    private suspend fun storeAndRecord(kind: TransferDescriptionKind, edit: DescriptionEdit): DescriptionOutcome {
        val before = readSafely { store.transferDescriptions() }?.filter { it.kind == kind }?.map { it.text } ?: return DescriptionOutcome.NotStored
        val after = when (val applied = DescriptionRules.applied(kind, before, edit)) {
            is DescriptionRules.Applied.Refused -> return applied.outcome
            is DescriptionRules.Applied.To -> applied.entries
        }
        if (after == before) return DescriptionOutcome.Unchanged
        val name = DescriptionJournalTexts.listName(kind)
        // UC-010 BR-006: The list a restore replaces is recorded in the Decision Journal first.
        if (edit == DescriptionEdit.Restore) DescriptionJournalTexts.record(journal, "$name before the default list is restored", listOf(null to before))
        if (!storedSafely { store.store(DescriptionListChange(kind, after)) }) {
            if (edit == DescriptionEdit.Restore) {
                journal.record(JournalEventKind.ConfigurationChange, "$name not restored: the default list could not be stored, and the list stays as recorded before")
            }
            return DescriptionOutcome.NotStored
        }
        // UC-011 BR-001: Every change of an Operator Setting is recorded, with the value before and after.
        when (edit) {
            DescriptionEdit.Restore -> DescriptionJournalTexts.record(journal, "$name restored to the default list", listOf(null to after))
            else -> DescriptionJournalTexts.record(journal, "$name: ${DescriptionJournalTexts.what(edit, after, before)}", listOf("before" to before, "after" to after))
        }
        return DescriptionOutcome.Stored(after, SettingEffect.None)
    }
}

/** How a change of a description list is recorded in the Decision Journal (UC-010 A7 step 3, A8 step 3, UC-011 BR-001). */
internal object DescriptionJournalTexts {
    /** `DECISION_JOURNAL_ENTRY.details` is stored with a length of 4000 (entity model). */
    private const val DETAILS_LENGTH = 4000

    fun listName(kind: TransferDescriptionKind): String = when (kind) {
        TransferDescriptionKind.CardPayment -> "Card Payment Descriptions"
        TransferDescriptionKind.BankTransfer -> "Bank Transfer Descriptions"
    }

    /** What [edit] did, in the journal's words (`"PINECREST PMT" added`). */
    fun what(edit: DescriptionEdit, after: List<String>, before: List<String>): String = when (edit) {
        is DescriptionEdit.Add -> "${quoted(after.last())} added"
        is DescriptionEdit.Change -> "${quoted(edit.entry)} changed to ${quoted(after[before.indexOf(edit.entry)])}"
        is DescriptionEdit.Remove -> "${quoted(edit.entry)} removed"
        DescriptionEdit.Restore -> "restored to the default list"
    }

    /**
     * Records [heading] with [lists], each a label ("before", or none) and its entries, as one Configuration Change, or, when
     * that is longer than an entry's details can hold, as consecutive parts, each naming its place ("part 1 of 2"), that
     * together hold every entry; an entry is never cut.
     */
    suspend fun record(journal: DecisionJournal, heading: String, lists: List<Pair<String?, List<String>>>) {
        val separator = if (lists.first().first == null) ": " else "; "
        val pieces = lists.flatMapIndexed { index, (label, entries) ->
            // An entry is always in quotation marks, so the words "no entry" of an empty list cannot be taken for one.
            val shown = if (entries.isEmpty()) listOf("no entry") else entries.map(::quoted)
            shown.mapIndexed { at, entry -> Piece(if (at == 0) label?.let { "$it: $entry" } ?: entry else entry, startsList = at == 0 && index > 0) }
        }
        val whole = heading + separator + joined(pieces)
        if (whole.length <= DETAILS_LENGTH) {
            journal.record(JournalEventKind.ConfigurationChange, whole)
            return
        }
        val room = DETAILS_LENGTH - (heading + " (part 999 of 999)" + separator).length
        val parts = mutableListOf<List<Piece>>()
        var current = mutableListOf<Piece>()
        for (piece in pieces) {
            if (current.isNotEmpty() && joined(current + piece).length > room) {
                parts += current
                current = mutableListOf()
            }
            current += piece
        }
        parts += current
        parts.forEachIndexed { index, part ->
            journal.record(JournalEventKind.ConfigurationChange, "$heading (part ${index + 1} of ${parts.size})$separator${joined(part.mapIndexed { at, piece -> if (at == 0) piece.copy(startsList = false) else piece })}")
        }
    }

    /**
     * [entry] in quotation marks, each quotation mark within it written twice, so that it cannot be taken for two
     * entries or for the words around it (UC-011 BR-001).
     */
    fun quoted(entry: String): String = "\"" + entry.replace("\"", "\"\"") + "\""

    private data class Piece(val text: String, val startsList: Boolean)

    private fun joined(pieces: List<Piece>): String =
        pieces.mapIndexed { index, piece -> if (index == 0) piece.text else (if (piece.startsList) "; " else " · ") + piece.text }.joinToString("")
}
