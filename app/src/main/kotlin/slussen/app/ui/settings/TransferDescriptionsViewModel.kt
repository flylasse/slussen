package slussen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.core.port.TransferDescriptionKind
import slussen.core.settings.DescriptionEdit
import slussen.core.settings.DescriptionListsShown
import slussen.core.settings.DescriptionOutcome

/** One entry on the screen (`#lists`): its text, whether the Operator added it (BR-007), and the tag the screen tests use. */
data class DescriptionLineUi(val text: String, val addedByYou: Boolean, val tag: String)

/**
 * One list on the screen (`#lists`): its title, its entries in their order, what an empty list means, and the answer of
 * its last change, at the list (NFR-023).
 */
data class DescriptionListUi(
    val kind: TransferDescriptionKind,
    val title: String,
    val lines: List<DescriptionLineUi>,
    val emptyNote: String?,
    val answer: AnswerUi?,
)

/** A7 (`#edit`): the dialog that adds an entry, or changes [entry]; the text as typed, why it was refused, and Saving…. */
data class DescriptionEditorUi(
    val kind: TransferDescriptionKind,
    val entry: String?,
    val title: String,
    val value: String,
    val error: String? = null,
    val saving: Boolean = false,
)

/** A7: what a tap on [entry] offers, Change and Remove; [removing] while its removal is on its way. */
data class EntryChoiceUi(val kind: TransferDescriptionKind, val entry: String, val removing: Boolean)

/** A8 step 1 (`#restore`): the question before a list is restored, with the default list it becomes; [saving] while it is stored. */
data class RestoreUi(val kind: TransferDescriptionKind, val title: String, val text: String, val defaults: String, val saving: Boolean = false)

/** The Transfer Descriptions screen (`transfer-descriptions.html#lists`, `#edit`, `#restore`). */
data class TransferDescriptionsUi(
    val lists: List<DescriptionListUi>,
    val note: String,
    val editor: DescriptionEditorUi? = null,
    val choice: EntryChoiceUi? = null,
    val restore: RestoreUi? = null,
    /** NFR-023: the lists could not be read again after a change; what is shown is what was read before. */
    val notReadAgain: String? = null,
) {
    fun list(kind: TransferDescriptionKind): DescriptionListUi = lists.single { it.kind == kind }
}

/**
 * The Card Payment Descriptions and Bank Transfer Descriptions (UC-010 step 1, A7, A8), reached from Settings. Every
 * change shows its state at its own control before anything is stored, cannot be left while it is on its way, and is
 * answered at the list it changed; what a call throws is answered there, never a crash (NFR-023).
 */
class TransferDescriptionsViewModel(private val services: slussen.app.ui.UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<TransferDescriptionsUi?>(null)
    val state: StateFlow<TransferDescriptionsUi?> = mutableState

    /** NFR-023: why the lists could not be shown, with the next action, while they cannot be read. */
    private val mutableNotOpened = MutableStateFlow<String?>(null)
    val notOpened: StateFlow<String?> = mutableNotOpened

    private var shown: DescriptionListsShown? = null

    /** NFR-023: the answer of the last change of each list, at the list, until the screen is opened again. */
    private val answers = mutableMapOf<TransferDescriptionKind, AnswerUi>()

    /**
     * A text that could not be stored, offered again when the Operator taps Add entry (entry null) or Change on the entry
     * it was to replace (failure postconditions).
     */
    private val retries = mutableMapOf<Pair<TransferDescriptionKind, String?>, String>()

    init {
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored lists are shown again; an open dialog stays.
            services.ended.drop(1).collect { if (mutableState.value != null) readAgain()?.let { publish(notReadAgain = it) } ?: publish() }
        }
    }

    fun load() {
        answers.clear()
        viewModelScope.launch {
            try {
                shown = services.descriptionLists.shown()
                mutableNotOpened.value = null
                val current = mutableState.value
                mutableState.value = TransferDescriptionsUi(lists = emptyList(), note = DescriptionTexts.NOTE, editor = current?.editor, choice = current?.choice, restore = current?.restore)
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (mutableState.value == null) mutableNotOpened.value = LISTS_NOT_OPENED else publish(notReadAgain = LISTS_NOT_READ_AGAIN)
            }
        }
    }

    /** Reads the lists again; returns the line to show when they could not be read, or null. */
    private suspend fun readAgain(): String? =
        try {
            shown = services.descriptionLists.shown()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LISTS_NOT_READ_AGAIN
        }

    // UC-010 BR-007: An entry that equals no entry of the default list of its own list is shown as added by the Operator, worked out each time it is shown.
    /** Shows the lists from [shown] and the answers, keeping the open dialogs unless told otherwise. */
    private fun publish(
        editor: DescriptionEditorUi? = mutableState.value?.editor,
        choice: EntryChoiceUi? = mutableState.value?.choice,
        restore: RestoreUi? = mutableState.value?.restore,
        notReadAgain: String? = null,
    ) {
        val current = mutableState.value ?: return
        val lists = shown ?: return
        mutableState.value = current.copy(
            lists = TransferDescriptionKind.entries.map { kind ->
                val list = lists.of(kind)
                DescriptionListUi(
                    kind = kind,
                    title = DescriptionTexts.listName(kind),
                    lines = list.entries.mapIndexed { index, entry -> DescriptionLineUi(entry.text, entry.addedByOperator, "entry-${kind.name}-$index") },
                    emptyNote = if (list.entries.isEmpty()) DescriptionTexts.empty(kind) else null,
                    answer = answers[kind],
                )
            },
            editor = editor,
            choice = choice,
            restore = restore,
            notReadAgain = notReadAgain,
        )
    }

    private fun busy(ui: TransferDescriptionsUi): Boolean = ui.editor != null || ui.choice != null || ui.restore != null

    /** A7 (`#edit`): the dialog that adds an entry to [kind], with a text that could not be stored offered again. */
    fun startAdding(kind: TransferDescriptionKind) {
        val current = mutableState.value ?: return
        if (busy(current)) return
        mutableState.value = current.copy(editor = DescriptionEditorUi(kind, entry = null, title = DescriptionTexts.addTitle(kind), value = retries[kind to null].orEmpty()))
    }

    /** A7: a tap on [entry] of [kind] offers Change and Remove. */
    fun openEntry(kind: TransferDescriptionKind, entry: String) {
        val current = mutableState.value ?: return
        if (busy(current)) return
        mutableState.value = current.copy(choice = EntryChoiceUi(kind, entry, removing = false))
    }

    /** A7: Change in the choice opens the dialog with the entry as it is stored. */
    fun startChanging() {
        val current = mutableState.value ?: return
        val choice = current.choice?.takeIf { !it.removing } ?: return
        mutableState.value = current.copy(
            choice = null,
            editor = DescriptionEditorUi(
                choice.kind, entry = choice.entry, title = DescriptionTexts.changeTitle(choice.kind), value = retries[choice.kind to choice.entry] ?: choice.entry,
            ),
        )
    }

    /** The choice is closed; nothing is stored. Refused while the removal is on its way. */
    fun cancelChoice() {
        val current = mutableState.value ?: return
        if (current.choice?.removing != false) return
        publish(choice = null)
    }

    /** The Operator types; a refusal at the field goes until Save is tapped again. */
    fun edit(text: String) {
        val current = mutableState.value ?: return
        val open = current.editor ?: return
        if (open.saving) return
        mutableState.value = current.copy(editor = open.copy(value = text, error = null))
    }

    /** The dialog is closed; nothing is stored or recorded. Refused while the change is on its way. */
    fun cancelEditing() {
        val current = mutableState.value ?: return
        val open = current.editor ?: return
        if (open.saving) return
        answers[open.kind] = AnswerUi("Not changed: the list stays as it is.", AnswerKind.Unchanged)
        publish(editor = null)
    }

    /** A7 steps 1-3 (`#edit`): Save shows Saving… before anything is stored; a refusal stays at the field, the answer goes to the list. */
    fun save() {
        val current = mutableState.value ?: return
        val open = current.editor ?: return
        if (open.saving) return
        val edit = if (open.entry == null) DescriptionEdit.Add(open.value) else DescriptionEdit.Change(open.entry, open.value)
        mutableState.value = current.copy(editor = open.copy(error = null, saving = true))
        viewModelScope.launch {
            val outcome = run(open.kind, edit)
            val refusal = DescriptionTexts.refused(outcome) ?: (outcome as? DescriptionOutcome.Unavailable)?.let { Texts.refusal(it.reason) }
            if (refusal != null) {
                // A7 step 1, precondition: refused in the dialog, at the field; nothing was stored.
                val now = mutableState.value ?: return@launch
                now.editor?.takeIf { it.kind == open.kind && it.entry == open.entry }?.let { mutableState.value = now.copy(editor = it.copy(error = refusal, saving = false)) }
                return@launch
            }
            if (outcome is DescriptionOutcome.NotStored) retries[open.kind to open.entry] = open.value else retries.remove(open.kind to open.entry)
            answered(open.kind, edit, outcome)
            publish(editor = null, notReadAgain = readAgain())
        }
    }

    /** A7: Remove in the choice shows Removing… there before anything is stored; the answer goes to the list. */
    fun remove() {
        val current = mutableState.value ?: return
        val choice = current.choice?.takeIf { !it.removing } ?: return
        mutableState.value = current.copy(choice = choice.copy(removing = true))
        viewModelScope.launch {
            val edit = DescriptionEdit.Remove(choice.entry)
            val outcome = run(choice.kind, edit)
            answered(choice.kind, edit, outcome)
            publish(choice = null, notReadAgain = readAgain())
        }
    }

    /** A8 step 1 (`#restore`): the question, showing the default list; a list that is the default already is answered without it. */
    fun askRestore(kind: TransferDescriptionKind) {
        val current = mutableState.value ?: return
        val lists = shown ?: return
        if (busy(current)) return
        val list = lists.of(kind)
        if (list.texts == list.defaults) {
            answers[kind] = AnswerUi("Not changed: this is the default list already.", AnswerKind.Unchanged)
            publish()
            return
        }
        mutableState.value = current.copy(
            restore = RestoreUi(
                kind = kind,
                title = "Restore the default ${DescriptionTexts.listName(kind)}?",
                text = DescriptionTexts.restoreText(list, lists.journalOn),
                defaults = list.defaults.joinToString(" · "),
            ),
        )
    }

    /** A8 step 2: the Operator cancels; nothing is restored or recorded. Refused while the restore is on its way. */
    fun cancelRestore() {
        val current = mutableState.value ?: return
        val restore = current.restore ?: return
        if (restore.saving) return
        answers[restore.kind] = AnswerUi("Not changed: the list stays as it is.", AnswerKind.Unchanged)
        publish(restore = null)
    }

    /** A8 steps 2-4: Restore shows Restoring… before anything is stored; the answer goes to the list. */
    fun confirmRestore() {
        val current = mutableState.value ?: return
        val restore = current.restore?.takeIf { !it.saving } ?: return
        mutableState.value = current.copy(restore = restore.copy(saving = true))
        viewModelScope.launch {
            val outcome = run(restore.kind, DescriptionEdit.Restore)
            answered(restore.kind, DescriptionEdit.Restore, outcome)
            publish(restore = null, notReadAgain = readAgain())
        }
    }

    /** The change through the core; what it throws is a change not stored (NFR-023). */
    private suspend fun run(kind: TransferDescriptionKind, edit: DescriptionEdit): DescriptionOutcome =
        try {
            services.descriptionLists.edit(kind, edit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DescriptionOutcome.NotStored
        }

    /** The answer of [outcome] at the list [kind]. */
    private fun answered(kind: TransferDescriptionKind, edit: DescriptionEdit, outcome: DescriptionOutcome) {
        answers[kind] = when (outcome) {
            is DescriptionOutcome.Stored ->
                AnswerUi(DescriptionTexts.saved(edit, outcome, shown?.journalOn ?: true, services.budgetAppName), AnswerKind.Done)
            DescriptionOutcome.Unchanged -> AnswerUi("Not changed: the list stays as it is.", AnswerKind.Unchanged)
            DescriptionOutcome.EntryGone -> AnswerUi(
                "Not changed: the entry is no longer in the list; it was changed since it was shown. The list shows it as stored now.",
                AnswerKind.Failed,
            )
            is DescriptionOutcome.Unavailable -> AnswerUi("Not changed: ${Texts.refusal(outcome.reason)}", AnswerKind.Failed)
            DescriptionOutcome.NotStored -> DescriptionTexts.notStored(edit).let { (text, detail) -> AnswerUi(text, AnswerKind.Failed, detail) }
            // Refused at the field before; a removal or a restore checks nothing that could be refused there.
            DescriptionOutcome.Empty, DescriptionOutcome.OnlyStars, is DescriptionOutcome.TooLong, is DescriptionOutcome.AlreadyInList ->
                AnswerUi(DescriptionTexts.refused(outcome).orEmpty(), AnswerKind.Failed)
        }
    }

    private companion object {
        const val LISTS_NOT_OPENED = "The description lists could not be read from this device; nothing was changed. Try again."

        const val LISTS_NOT_READ_AGAIN =
            "The description lists could not be read again from this device; the lists shown are the last ones read. Open them again to see them."
    }
}
