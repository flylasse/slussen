package slussen.app.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.core.journal.ExportFailure
import slussen.core.journal.ExportedBudget
import slussen.core.journal.JournalExportOutcome
import slussen.core.port.JournalExportDocument

/** Steps 3-4 (`#writing`): what the export does now, and the place it writes to. */
data class WritingUi(val text: String, val place: String)

/** The Journal Export screen (`journal-export.html`). */
data class JournalExportUi(
    val intro: String,
    /** Step 2: whether payee and memo text are replaced by codes. */
    val codes: Boolean,
    /** Choose where to save… can be tapped: an entry exists and no export or save dialog is under way. */
    val chooseEnabled: Boolean,
    /** Precondition (`#empty`): the Decision Journal holds no entry. */
    val nothingToExport: AnswerUi?,
    /** Steps 3-4, from the tap on. */
    val writing: WritingUi?,
    /** The answer of the last export, at its control: step 5, A1, A2, A3. */
    val answer: AnswerUi?,
    /** Step 5 (`#complete`): the latest entries in the export, newest first, for review. */
    val latest: List<String>,
    /** While the export waits for the lane behind another operation, Withdraw ends it (ADR-007 §1). */
    val canWithdraw: Boolean = false,
)

/**
 * Export the Decision Journal (UC-011), reached from Settings. The place is chosen in Android's save dialog, which the
 * screen opens with the name [choosePlace] proposes; the document it gives is exported to. Every state shows at the
 * control before anything is read, and every outcome is answered there; what the export throws is answered too, never
 * a crash (NFR-023). The export runs in this ViewModel's scope: leaving the screen does not stop it, and the answer is
 * there on return.
 */
class JournalExportViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow(
        JournalExportUi(
            intro = JournalExportTexts.INTRO,
            codes = false,
            chooseEnabled = false,
            nothingToExport = null,
            writing = null,
            answer = null,
            latest = emptyList(),
        ),
    )
    val state: StateFlow<JournalExportUi> = mutableState

    private var hasEntries: Boolean? = null
    private var choosing = false

    /** Precondition: whether the Decision Journal holds an entry; when that cannot be read, the export may be tried and answers itself. */
    fun load() {
        viewModelScope.launch {
            hasEntries = try {
                services.journalExport.hasEntries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            publish()
        }
    }

    /** Step 2: payee and memo text replaced by codes, or not. */
    fun setCodes(on: Boolean) {
        mutableState.value = mutableState.value.copy(codes = on)
    }

    /**
     * Step 2: the name to propose in Android's save dialog, or null when no export can start now. Choose where to save…
     * waits for the dialog's answer.
     */
    fun choosePlace(): String? {
        if (!canChoose()) return null
        choosing = true
        mutableState.value = mutableState.value.copy(answer = null, latest = emptyList())
        publish()
        return services.journalExport.proposedName()
    }

    /** A1 of UC-011: the save dialog was left without a place, and Slussen writes no file. */
    fun noPlaceChosen() {
        choosing = false
        mutableState.value = mutableState.value.copy(answer = AnswerUi(JournalExportTexts.NO_PLACE, AnswerKind.Unchanged, JournalExportTexts.NO_FILE))
        publish()
    }

    /** Steps 3-5, A2, A3: the export to [document], the place the Operator chose, named [place] on the screen. */
    /** NFR-023: the device has no app that lets the Operator choose a place; no file is written. */
    fun noPlaceCanBeChosen() {
        choosing = false
        mutableState.value = mutableState.value.copy(answer = AnswerUi(JournalExportTexts.NO_APP, AnswerKind.Failed, JournalExportTexts.NO_APP_DETAIL))
        publish()
    }

    private var running: Job? = null
    private var withdrawn = false

    /** ADR-007 §1: the export waiting for the lane is withdrawn; the document is removed and no file is written. */
    fun withdraw() {
        if (!mutableState.value.canWithdraw) return
        withdrawn = true
        running?.cancel()
    }

    /**
     * Steps 3-5, A2, A3: the export to [document], the place the Operator chose, shown as [place] until [name], read off
     * the screen's thread, gives the name the document has at its place.
     */
    fun export(document: JournalExportDocument, place: String, name: suspend () -> String? = { null }) {
        choosing = false
        withdrawn = false
        val codes = mutableState.value.codes
        mutableState.value = mutableState.value.copy(
            writing = WritingUi(JournalExportTexts.writing(services.budgetAppName), JournalExportTexts.to(place)),
            answer = null,
            latest = emptyList(),
        )
        publish()
        running = viewModelScope.launch {
            val shown = try {
                name() ?: place
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                place
            }
            mutableState.value = mutableState.value.copy(writing = WritingUi(JournalExportTexts.writing(services.budgetAppName), JournalExportTexts.to(shown)))
            val outcome = try {
                services.journalExport.export(
                    codes,
                    document,
                    waiting = { other ->
                        mutableState.value = mutableState.value.copy(
                            writing = WritingUi(JournalExportTexts.waiting(Texts.operation(other), services.budgetAppName), JournalExportTexts.to(shown)),
                            canWithdraw = true,
                        )
                    },
                    reading = {
                        mutableState.value = mutableState.value.copy(
                            writing = WritingUi(JournalExportTexts.writing(services.budgetAppName), JournalExportTexts.to(shown)),
                            canWithdraw = false,
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                if (withdrawn) answerWithdrawn()
                throw cancelled
            } catch (failure: Exception) {
                JournalExportOutcome.Failed(ExportFailure.StoredDataNotRead, removed = false)
            }
            answer(outcome, shown)
        }
    }

    private fun answerWithdrawn() {
        mutableState.value = mutableState.value.copy(
            writing = null,
            canWithdraw = false,
            answer = AnswerUi(JournalExportTexts.WITHDRAWN, AnswerKind.Unchanged, JournalExportTexts.NO_FILE),
        )
        publish()
    }

    private fun answer(outcome: JournalExportOutcome, place: String) {
        val now = services.clock.instant()
        mutableState.value = when (outcome) {
            is JournalExportOutcome.Complete -> {
                val fromCopy = outcome.budgets.any { it !is ExportedBudget.Read }
                mutableState.value.copy(
                    writing = null,
                    canWithdraw = false,
                    answer = AnswerUi(
                        if (fromCopy) JournalExportTexts.COMPLETE_FROM_COPY else JournalExportTexts.COMPLETE,
                        AnswerKind.Done,
                        JournalExportTexts.complete(outcome.entries, place, outcome.budgets, services.budgetAppName, services.formats, now, outcome.removedWhileWritten),
                    ),
                    latest = outcome.latest.map { JournalExportTexts.entry(it, services.formats) },
                )
            }
            is JournalExportOutcome.Failed -> {
                mutableState.value.copy(
                    writing = null,
                    canWithdraw = false,
                    answer = AnswerUi(JournalExportTexts.FAILED, AnswerKind.Failed, JournalExportTexts.failed(outcome.why, outcome.removed, place)),
                    latest = emptyList(),
                )
            }
        }
        publish()
    }

    private fun canChoose(): Boolean = hasEntries != false && !choosing && mutableState.value.writing == null

    private fun publish() {
        mutableState.value = mutableState.value.copy(
            chooseEnabled = canChoose(),
            nothingToExport = if (hasEntries == false) AnswerUi(JournalExportTexts.NOTHING, AnswerKind.Unchanged, JournalExportTexts.NOTHING_DETAIL) else null,
        )
    }
}
