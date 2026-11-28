package slussen.app.ui.fileimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
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

/** NFR-023: the chosen file does not fit into the device's memory; thrown by a [TransactionFileSource] that reads it whole. */
class FileTooLargeException : java.io.IOException("The chosen document does not fit into memory.")

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

/**
 * What the import shows now (`import-file.html`). What runs or waits, from the tap that opens the document dialog to the
 * answer, is shown at every import control ([progress], [forBankAccount], [canWithdraw]); the choice of the Bank Account
 * at [choiceAt] and the answer at [answerAt] only at their own control.
 */
data class FileImportUi(
    /** The control that started what runs now. */
    val runningAt: ImportPlace? = null,
    /** The text of every import control while a step runs: the document dialog, the read, the wait, the import; null otherwise. */
    val progress: String? = null,
    val forBankAccount: String? = null,
    /** ADR-007 §1: while the import waits for the lane behind another operation, Withdraw ends it, at any import control. */
    val canWithdraw: Boolean = false,
    /** S-B-34: the screen whose control asks which Bank Account the file is for; null while none asks. */
    val choiceAt: ImportPlace? = null,
    val choice: List<ImportBankAccount>? = null,
    /** The control the last answer belongs to, until its screen is left or another operation ends. */
    val answerAt: ImportPlace? = null,
    val answer: AnswerUi? = null,
    val lines: List<TakenInLine> = emptyList(),
    /** A5: the remedy of a Retired Account is in the Account Mappings (UC-009). */
    val toAccountMappings: Boolean = false,
) {
    /** An import is under way, from the tap that opens the document dialog on: no second one is started (NFR-023). */
    val busy: Boolean get() = progress != null
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
 * The control at [place], titled [title] while nothing runs: what runs or waits and for which Bank Account, wherever it was
 * started, with Withdraw while it waits; the choice of the Bank Account and the answer only where they belong.
 */
fun FileImportUi.control(place: ImportPlace, title: String = FileImportTexts.IMPORT): FileImportControlUi {
    val own = answerAt == place && !busy
    return FileImportControlUi(
        title = progress ?: title,
        enabled = !busy && choiceAt != place,
        running = busy,
        forBankAccount = forBankAccount.takeIf { busy },
        choice = choice.takeIf { choiceAt == place },
        canWithdraw = canWithdraw,
        answer = answer.takeIf { own },
        lines = if (own) lines else emptyList(),
        toAccountMappings = own && toAccountMappings,
    )
}

/**
 * Import a Transaction File (UC-002) from the screens: one for the whole app, so that no second import starts while one
 * runs, whichever screen started it, and so that every import control shows what runs (NFR-023, ADR-007 §1). The file is
 * chosen in Android's document dialog, which the screen opens for the Bank Account [start] or [choose] gives; its
 * content is read off the screen's thread and handed to the core whole. Every state shows before anything is stored, and
 * every outcome is answered at the control that started it until the Operator leaves that screen or another operation
 * ends; after that the stored Source Health says what was imported.
 */
class TransactionFileImportViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow(FileImportUi())
    val state: StateFlow<FileImportUi> = mutableState

    private var chosen: ImportBankAccount? = null
    private var named = false
    private var running: Job? = null
    private var started = false

    /** How many operations had ended when the shown answer was given: one that ends later ends the answer. */
    private var answeredAt = 0L

    init {
        viewModelScope.launch {
            // `UiServices.ended`: once another operation ends, the stored data speaks, not the answer of an earlier import.
            services.ended.drop(1).collect { count ->
                if (mutableState.value.answer != null && count > answeredAt) {
                    mutableState.value = mutableState.value.copy(answerAt = null, answer = null, lines = emptyList(), toAccountMappings = false)
                }
            }
        }
    }

    /**
     * Step 1 at [place] for one of [bankAccounts]: the Bank Account to open the document dialog for, or null when none is to
     * be opened now: another import is under way, or the Operator first chooses among two or more (S-B-34).
     */
    fun start(place: ImportPlace, bankAccounts: List<ImportBankAccount>): ImportBankAccount? {
        if (mutableState.value.busy || bankAccounts.isEmpty()) return null
        named = bankAccounts.size > 1
        if (bankAccounts.size > 1) {
            mutableState.value = FileImportUi(choiceAt = place, choice = bankAccounts)
            return null
        }
        return choosing(place, bankAccounts.single())
    }

    /** S-B-34: the Bank Account the file is for; true when the document dialog is to be opened for it. */
    fun choose(bankAccount: ImportBankAccount): Boolean {
        val current = mutableState.value
        val place = current.choiceAt ?: return false
        if (current.busy || current.choice?.contains(bankAccount) != true) return false
        choosing(place, bankAccount)
        return true
    }

    /** S-B-34: the choice of the Bank Account is left; nothing is imported. */
    fun cancelChoice() {
        mutableState.value = mutableState.value.copy(choiceAt = null, choice = null)
    }

    /**
     * The Operator left the screen of the places [at] selects: a choice of the Bank Account open there ends, and an answer
     * shown there is no longer shown; what runs goes on and is answered when it ends.
     */
    fun left(at: (ImportPlace) -> Boolean) {
        val current = mutableState.value
        var next = current
        if (current.choiceAt?.let(at) == true) next = next.copy(choiceAt = null, choice = null)
        if (!current.busy && current.answerAt?.let(at) == true) next = next.copy(answerAt = null, answer = null, lines = emptyList(), toAccountMappings = false)
        mutableState.value = next
    }

    private fun choosing(place: ImportPlace, bankAccount: ImportBankAccount): ImportBankAccount {
        chosen = bankAccount
        // Steps 2-3 (`#choose`): every import control waits for the document dialog's answer.
        mutableState.value = FileImportUi(runningAt = place, progress = FileImportTexts.CHOOSE, forBankAccount = bankAccount.name)
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
        mutableState.value = mutableState.value.copy(progress = FileImportTexts.READING, canWithdraw = false)
        running = viewModelScope.launch {
            val content = try {
                file.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (tooLarge: FileTooLargeException) {
                answer(FileImportTexts.tooLarge(bankAccount.name))
                return@launch
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
            val imported = outcome as? FileImportOutcome.Imported
            answer(
                FileImportTexts.answer(outcome, bankAccount.name, named, services.budgetAppName, formats),
                lines = imported?.let { done -> done.takenIn.map { FileImportTexts.line(it, formats, today, FileImportTexts.compared(done)) } }.orEmpty(),
                toAccountMappings = outcome is FileImportOutcome.RetiredAccount,
            )
        }
    }

    private fun answer(answer: AnswerUi, lines: List<TakenInLine> = emptyList(), toAccountMappings: Boolean = false) {
        answeredAt = services.ended.value
        mutableState.value = FileImportUi(answerAt = mutableState.value.runningAt, answer = answer, lines = lines, toAccountMappings = toAccountMappings)
        chosen = null
        started = false
    }
}
