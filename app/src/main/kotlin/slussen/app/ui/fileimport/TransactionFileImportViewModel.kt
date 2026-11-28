package slussen.app.ui.fileimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.UiServices
import slussen.app.ui.components.AnswerUi
import slussen.core.fileimport.FileImportOutcome

/** Where an import of a Transaction File was started, so that its state and answer show at that control (NFR-023). */
sealed interface ImportPlace {
    /** S-B-34: the Transaction File Source of the File Format [fileFormatId] on the tab of the Budget [budgetId]. */
    data class Source(val budgetId: Long, val fileFormatId: Long) : ImportPlace

    /** A7: the page of the File Format [fileFormatId], while no Bank Account of it is added (`connections.html#import-first`). */
    data class FileFormatPage(val fileFormatId: Long) : ImportPlace
}

/** A Bank Account a Transaction File can be imported for (UC-002 step 1). */
data class ImportBankAccount(val bankAccountId: Long, val name: String)

/** Step 1 on a Transaction File Source: the place and the Bank Accounts of this Budget whose files it imports. */
data class FileImportEntry(val place: ImportPlace, val bankAccounts: List<ImportBankAccount>)

/**
 * Step 4: the file the Operator chose, as the core asks for it: its whole content, read off the screen's thread. It throws
 * when the file cannot be read (its provider fails, the document is gone, its permission was taken back).
 */
fun interface TransactionFileSource {
    suspend fun read(): ByteArray
}

/** Step 10 (`#imported`, `#told-apart`): one Bank Transaction taken in for the first time, with its Transaction Status. */
data class TakenInLine(val day: String, val text: String, val amount: String, val status: String)

/** What the import shows now, at the control of [place] (`import-file.html`). */
data class FileImportUi(
    val place: ImportPlace? = null,
    /** S-B-34: the Bank Accounts to choose from before the file; null while no choice is asked. */
    val choice: List<ImportBankAccount>? = null,
    /** The text of the control while a step runs: the document dialog, the read, the wait, the import; null otherwise. */
    val progress: String? = null,
    val forBankAccount: String? = null,
    /** ADR-007 §1: while the import waits for the lane behind another operation, Withdraw ends it. */
    val canWithdraw: Boolean = false,
    val answer: AnswerUi? = null,
    val lines: List<TakenInLine> = emptyList(),
    /** A5: the remedy of a Retired Account is in the Account Mappings (UC-009). */
    val toAccountMappings: Boolean = false,
) {
    /** An import is under way, from the tap on: no second one is started (NFR-023). */
    val busy: Boolean get() = choice != null || progress != null
}

/** One control that imports a Transaction File, as [FileImportUi.control] gives it for its place. */
data class FileImportControlUi(
    val title: String,
    val enabled: Boolean,
    val running: Boolean = false,
    val forBankAccount: String? = null,
    val choice: List<ImportBankAccount>? = null,
    val canWithdraw: Boolean = false,
    val answer: AnswerUi? = null,
    val lines: List<TakenInLine> = emptyList(),
    val toAccountMappings: Boolean = false,
)

/**
 * The control at [place], labelled [title] while nothing runs there: the state of an import started there and its answer,
 * or, while one runs elsewhere, a control that cannot be tapped.
 */
fun FileImportUi.control(place: ImportPlace, title: String = FileImportTexts.IMPORT): FileImportControlUi {
    if (this.place != place) return FileImportControlUi(title, enabled = !busy)
    return FileImportControlUi(
        title = progress ?: title,
        enabled = !busy,
        running = progress != null,
        forBankAccount = forBankAccount.takeIf { busy },
        choice = choice,
        canWithdraw = canWithdraw,
        answer = answer,
        lines = lines,
        toAccountMappings = toAccountMappings,
    )
}

/**
 * Import a Transaction File (UC-002) from the screens: one for the whole app, so that no second import starts while one
 * runs, whichever screen started it, and so that leaving a screen never loses the answer, which is shown at the control
 * it was started from on return (NFR-023). The file is chosen in Android's document dialog, which the screen opens for
 * the Bank Account [start] or [choose] gives; its content is read off the screen's thread and handed to the core whole.
 * Every state shows at the control before anything is stored, and every outcome is answered there, never a crash.
 */
class TransactionFileImportViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow(FileImportUi())
    val state: StateFlow<FileImportUi> = mutableState

    private var chosen: ImportBankAccount? = null
    private var named = false
    private var running: Job? = null
    private var started = false

    /**
     * Step 1 at [place] for one of [bankAccounts]: the Bank Account to open the document dialog for, or null when none is to
     * be opened now: another import is under way, or the Operator first chooses among two or more (S-B-34).
     */
    fun start(place: ImportPlace, bankAccounts: List<ImportBankAccount>): ImportBankAccount? {
        if (mutableState.value.busy || bankAccounts.isEmpty()) return null
        named = bankAccounts.size > 1
        if (bankAccounts.size > 1) {
            mutableState.value = FileImportUi(place = place, choice = bankAccounts)
            return null
        }
        return choosing(place, bankAccounts.single())
    }

    /** S-B-34: the Bank Account the file is for; true when the document dialog is to be opened for it. */
    fun choose(bankAccount: ImportBankAccount): Boolean {
        val current = mutableState.value
        val place = current.place ?: return false
        if (current.choice?.contains(bankAccount) != true) return false
        choosing(place, bankAccount)
        return true
    }

    /** S-B-34: the choice of the Bank Account is left; nothing is imported. */
    fun cancelChoice() {
        if (mutableState.value.choice == null) return
        mutableState.value = FileImportUi(place = mutableState.value.place)
    }

    private fun choosing(place: ImportPlace, bankAccount: ImportBankAccount): ImportBankAccount {
        chosen = bankAccount
        // Steps 2-3 (`#choose`): the control waits for the document dialog's answer.
        mutableState.value = FileImportUi(place = place, progress = FileImportTexts.CHOOSE, forBankAccount = bankAccount.name)
        return bankAccount
    }

    /** A1: the document dialog was left without a file; the stored Bank Transactions stay unchanged. */
    fun noFileChosen() {
        val bankAccount = chosen ?: return
        answer(FileImportTexts.noFileChosen(bankAccount.name))
    }

    /** NFR-023: the device has no app that answers the document dialog; nothing is imported. */
    fun noFileCanBeChosen() {
        if (chosen == null) return
        answer(FileImportTexts.noApp)
    }

    /** ADR-007 §1: the import that waits for the lane is withdrawn; once it has the lane it goes on to its answer. */
    fun withdraw() {
        if (!mutableState.value.canWithdraw || started) return
        running?.cancel()
    }

    /**
     * Steps 4-10, A2-A6: the import of [file], the document the dialog gave, for the Bank Account it was opened for. The
     * file is read first, off the screen's thread; a read that fails stores nothing, not even a rejection (NFR-023).
     */
    fun import(file: TransactionFileSource) {
        val bankAccount = chosen ?: return
        if (mutableState.value.progress != FileImportTexts.CHOOSE) return
        started = false
        progress(FileImportTexts.READING)
        running = viewModelScope.launch {
            val content = try {
                file.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                answer(FileImportTexts.notRead(bankAccount.name))
                return@launch
            }
            val outcome = try {
                services.fileImport.import(
                    bankAccount.bankAccountId,
                    content,
                    waiting = { other ->
                        mutableState.value = mutableState.value.copy(progress = FileImportTexts.waiting(other, bankAccount.name), canWithdraw = true)
                    },
                    started = {
                        started = true
                        mutableState.value = mutableState.value.copy(progress = FileImportTexts.importing(bankAccount.name, services.budgetAppName), canWithdraw = false)
                    },
                )
            } catch (cancelled: CancellationException) {
                if (!started) answer(FileImportTexts.withdrawn(bankAccount.name))
                throw cancelled
            } catch (failure: Exception) {
                FileImportOutcome.NotStored(recordedAsRejected = false)
            }
            val formats = services.formats
            val today = formats.today(services.clock.instant())
            answer(
                FileImportTexts.answer(outcome, bankAccount.name, named, services.budgetAppName, formats),
                lines = (outcome as? FileImportOutcome.Imported)?.takenIn?.map { FileImportTexts.line(it, formats, today) }.orEmpty(),
                toAccountMappings = outcome is FileImportOutcome.RetiredAccount,
            )
        }
    }

    private fun progress(text: String) {
        mutableState.value = mutableState.value.copy(progress = text, canWithdraw = false, answer = null, lines = emptyList())
    }

    private fun answer(answer: AnswerUi, lines: List<TakenInLine> = emptyList(), toAccountMappings: Boolean = false) {
        mutableState.value = FileImportUi(place = mutableState.value.place, answer = answer, lines = lines, toAccountMappings = toAccountMappings)
        chosen = null
        started = false
    }
}
