package slussen.app.ui.settings

import slussen.core.port.TransferDescriptionKind
import slussen.core.settings.DescriptionEdit
import slussen.core.settings.DescriptionListShown
import slussen.core.settings.DescriptionOutcome
import slussen.core.settings.DescriptionRules
import slussen.core.settings.SettingEffect

/** The words of the Transfer Descriptions screen (`transfer-descriptions.html#lists`, `#edit`, `#restore`; UC-010 A7, A8). */
object DescriptionTexts {
    fun listName(kind: TransferDescriptionKind): String = when (kind) {
        TransferDescriptionKind.CardPayment -> "Card Payment Descriptions"
        TransferDescriptionKind.BankTransfer -> "Bank Transfer Descriptions"
    }

    /** One entry of [kind], as the dialog's title names it. */
    private fun oneOf(kind: TransferDescriptionKind): String = when (kind) {
        TransferDescriptionKind.CardPayment -> "Card Payment Description"
        TransferDescriptionKind.BankTransfer -> "Bank Transfer Description"
    }

    fun addTitle(kind: TransferDescriptionKind): String = "Add a ${oneOf(kind)}"

    fun changeTitle(kind: TransferDescriptionKind): String = "Change a ${oneOf(kind)}"

    const val NOTE: String = "Matches anywhere in payee or memo · * = any characters"

    const val FIELD: String = "Text of a payee or memo · at most 100 characters · * = any characters"

    // UC-010 BR-008: A list may hold no entry.
    /** What a list that holds no entry means (UC-005 BR-002): which Transfers Slussen then cannot propose by a payee or memo. */
    fun empty(kind: TransferDescriptionKind): String = when (kind) {
        TransferDescriptionKind.CardPayment ->
            "No entry: no payee or memo marks a Card Payment whose other side has not arrived, so Slussen proposes none of those."
        TransferDescriptionKind.BankTransfer -> "No entry: no payee or memo marks a Bank Transfer, so Slussen proposes none."
    }

    /** Settings (`settings.html#list`): the line of a list with its number of entries. */
    fun settingsHint(list: DescriptionListShown): String = "Entries: ${list.entries.size} · defaults ship with the build"

    /** A7 step 1, BR-008 (`#edit` Not saved): why the entry typed is refused, at the field, with what to enter. */
    fun refused(outcome: DescriptionOutcome): String? = when (outcome) {
        DescriptionOutcome.Empty -> "Not saved: an entry cannot be empty. Enter the text a payee or memo holds."
        DescriptionOutcome.OnlyStars ->
            "Not saved: an entry of only * matches nothing. Enter the text a payee or memo holds; * stands for any characters within it."
        is DescriptionOutcome.TooLong ->
            "Not saved: an entry is at most ${outcome.maxLength} characters, and this one has ${outcome.length}. Enter a shorter text."
        is DescriptionOutcome.AlreadyInList ->
            if (outcome.entered == outcome.entry) {
                "Not saved: ${outcome.entry} is already in the list. Enter another text."
            } else {
                "Not saved: ${outcome.entered} is already in the list as ${outcome.entry}. Enter another text."
            }
        else -> null
    }

    /** A7 steps 2-3, A8 steps 3-4, UC-010 step 5: the answer of a stored change at its list. */
    fun saved(edit: DescriptionEdit, outcome: DescriptionOutcome.Stored, journalOn: Boolean, budgetAppName: String): String {
        val head = when (edit) {
            is DescriptionEdit.Add -> "Saved: ${outcome.entries.last()} added."
            is DescriptionEdit.Change -> "Saved: ${edit.entry} changed to ${edit.text.trim()}."
            is DescriptionEdit.Remove -> "Saved: ${edit.entry} removed." + if (outcome.entries.isEmpty()) " The list holds no entry now." else ""
            DescriptionEdit.Restore -> "Saved: the default list is restored, ${outcome.entries.size} entries."
        }
        val journal = when {
            !journalOn -> ""
            edit == DescriptionEdit.Restore -> " Recorded in the Decision Journal, with the list before it."
            else -> " Recorded in the Decision Journal."
        }
        return "$head ${detectedAgain(outcome.effect, budgetAppName)}$journal"
    }

    /** UC-010 step 5, BR-009: the Transfers detected again from stored data, naming every Budget that waits for its read (UC-003 BR-016). */
    private fun detectedAgain(effect: SettingEffect, budgetAppName: String): String {
        val given = "The Transfers among the Bank Transactions not yet uploaded were detected again and their Transaction Statuses given " +
            "again from stored data, without contacting $budgetAppName or any Source"
        return when (effect) {
            is SettingEffect.StatusesGiven -> {
                val waiting = effect.waitingForRead
                when {
                    waiting == null -> "$given, except in each Budget that owes a read of $budgetAppName, which waits for that read."
                    waiting.isEmpty() -> "$given."
                    waiting.size == 1 -> "$given, except in ${waiting.single()}, which waits for the read of $budgetAppName it owes."
                    else -> "$given, except in ${waiting.dropLast(1).joinToString(", ")} and ${waiting.last()}, which wait for the read of " +
                        "$budgetAppName each owes."
                }
            }
            else -> "The Transfers could not be detected again yet; no Upload Plan is prepared until they are, at the next start or the " +
                "next read of $budgetAppName."
        }
    }

    /** Failure postconditions: the change could not be stored; the list stays, with how to try again. */
    fun notStored(edit: DescriptionEdit): Pair<String, String> = when (edit) {
        is DescriptionEdit.Add ->
            "Not saved: Slussen could not store ${edit.text.trim()}. The list stays as it was." to "Tap Add entry to try again."
        is DescriptionEdit.Change ->
            "Not saved: Slussen could not store ${edit.text.trim()}. ${edit.entry} stays as it was." to "Tap ${edit.entry} to try again."
        is DescriptionEdit.Remove ->
            "Not removed: Slussen could not store the list. ${edit.entry} stays in it." to "Tap ${edit.entry} to try again."
        DescriptionEdit.Restore ->
            "Not restored: Slussen could not store the default list. The list stays as it was." to "Tap Restore the default list to try again."
    }

    /** A8 step 1 (`#restore`): the question, naming what the current list has beyond the default list and lacks of it. */
    fun restoreText(list: DescriptionListShown, journalOn: Boolean): String {
        val added = list.entries.filter { it.addedByOperator }.map { it.text }
        val missing = list.defaults.filter { default -> list.texts.none { DescriptionRules.same(it, default) } }
        val differs = listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "with ${andJoined(it)}" },
            missing.takeIf { it.isNotEmpty() }?.let { "without ${andJoined(it)}" },
        )
        val current = if (differs.isEmpty()) "Your current list" else "Your current list, ${differs.joinToString(" and ")},"
        val recorded = if (journalOn) " is recorded in the Decision Journal first." else " is not recorded: the Decision Journal is switched off."
        return "$current$recorded Then the list becomes:"
    }

    private fun andJoined(texts: List<String>): String = if (texts.size == 1) texts.single() else texts.dropLast(1).joinToString(", ") + " and " + texts.last()
}
