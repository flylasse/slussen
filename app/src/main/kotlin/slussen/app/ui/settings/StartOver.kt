package slussen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.consent.ConsentNotEnded
import slussen.core.consent.ConsentToEnd
import slussen.core.consent.NotEndedBecause
import slussen.core.settings.StartOverOutcome
import slussen.core.settings.StartOverResult
import slussen.core.settings.StartOverStatement
import slussen.core.settings.StartOverUnavailable

/** The statement and confirmation of Start Over (`settings.html#start-over`), like clearing the Decision Journal asks. */
data class StartOverDialogUi(val title: String, val text: String, val confirm: String)

/**
 * The row of Start Over at the end of Settings with its state (`#start-over-running`), its answer (`#start-over-unavailable`,
 * `#start-over-failed`) and its dialog (`#start-over`); [enabled] is false while it runs and once nothing else may run.
 */
data class StartOverUi(
    val dialog: StartOverDialogUi? = null,
    val running: String? = null,
    val answer: String? = null,
    val enabled: Boolean = true,
)

/** The card of the new installation (`#started-over`). */
data class StartedOverCardUi(val title: String, val text: String)

/** Slussen's own words for Start Over (`settings.html#start-over` to `#started-over`). */
object StartOverTexts {
    const val ENTRY = "Start over…"
    const val ENTRY_HINT = "Delete everything Slussen stores and start as newly installed"
    const val RESTARTING = "Starting over: Slussen deletes everything and starts again by itself"
    const val NOTHING_DELETED_YET = "Nothing is deleted yet. Slussen then deletes everything and starts again by itself."
    const val STATEMENT_NOT_READ = "Not started over: the stored data could not be read. Nothing was deleted. Try again."
    const val OPEN_AGAIN = "Everything is ready to be deleted: close Slussen and open it again to finish starting over."
    const val CARD_TITLE = "Slussen started over"
    const val EVERY_CONSENT_ENDED = "Everything it stored was deleted. Every Bank Consent was confirmed as ended."
    const val NAMES_NOT_KNOWN = "Everything it stored was deleted. Which Bank Consents were not confirmed as ended is not known: " +
        "check each at its Bank; one not ended stays there until it expires."

    fun statement(statement: StartOverStatement): StartOverDialogUi {
        val consents = statement.consentsToEnd
        val ending = if (consents.isEmpty()) {
            "No Bank Consent is stored."
        } else {
            "First Slussen asks " + consents.joinToString("; ") { "${it.dataProviderName} to end the Bank Consent of ${it.bankName}" } +
                ". A Bank Consent it cannot end stays at the Bank until it expires; you can end it at the Bank."
        }
        val text = listOf(
            "Slussen deletes everything it stores and starts as newly installed. This cannot be undone.",
            "Deleted: the chosen Budgets and their Budget Accounts; the ${count(statement.bankAccounts, "Bank Account")} with their " +
                "Account Mappings and Start Dates; the Left-Out Bank Accounts; the ${count(statement.bankTransactions, "Bank Transaction")} " +
                "with their Review Decisions and Transfer Decisions; the Sent Record; the credentials of every service; the Bank " +
                "Consents; the Operator Settings; the Decision Journal.",
            ending,
            "Afterwards, as after a new installation, nothing is uploaded until First Upload Matching is confirmed.",
        ).joinToString("\n\n")
        return StartOverDialogUi("Start over?", text, "Delete everything")
    }

    private fun count(n: Int, noun: String) = "%,d %s".format(n, if (n == 1) noun else noun + "s")

    fun asking(toEnd: ConsentToEnd): String = "Asking ${toEnd.dataProviderName} to end the Bank Consent of ${toEnd.bankName}"

    fun notNow(why: StartOverUnavailable): String = "Not started over: " + when (why) {
        is StartOverUnavailable.AnotherOperation -> (why.running?.let { Texts.operation(it) } ?: "another operation") + " is under way"
        is StartOverUnavailable.ConsentRequestWaiting ->
            "a Consent Request for ${why.bankReference} waits for the Bank's result: cancel it on its Data Provider's page, or wait until its time ends"
        StartOverUnavailable.JournalExportBeingWritten -> "a Journal Export is being written"
        is StartOverUnavailable.Paused -> when (why.reason) {
            slussen.core.operation.OperationRefusal.UploadPlanAwaitingConfirmation -> "an Upload Plan awaits confirmation"
            slussen.core.operation.OperationRefusal.UploadBeingWritten -> "an Upload is being written"
        }
    } + ". Nothing was deleted. Start over once it has ended."

    fun notRecorded(asked: List<String>): String = "Not started over: it could not be recorded. Nothing was deleted." +
        if (asked.isEmpty()) " Try again." else " The Bank Consents of ${asked.joinToString(", ")} were asked to end and may no longer be usable: grant them again, or start over again."

    private fun why(notEnded: ConsentNotEnded): String = when (notEnded.because) {
        NotEndedBecause.EndedAtDataProviderOnly -> "${notEnded.dataProviderName} ended it on its side only"
        NotEndedBecause.NotConfirmed -> "${notEnded.dataProviderName} did not confirm it"
        NotEndedBecause.CredentialsMissing -> "${notEnded.dataProviderName}'s credentials were missing"
        NotEndedBecause.CredentialsRefused -> "${notEnded.dataProviderName}'s credentials were refused"
        NotEndedBecause.HeldBackByGate -> "${notEnded.dataProviderName} was not contacted: its requests were held back"
        NotEndedBecause.NotContactable -> "${notEnded.dataProviderName.ifEmpty { "its Data Provider" }} could not be contacted"
    }

    fun card(result: StartOverResult?): StartedOverCardUi = StartedOverCardUi(
        CARD_TITLE,
        when {
            result == null -> NAMES_NOT_KNOWN
            result.notEnded.isEmpty() -> EVERY_CONSENT_ENDED
            else -> "Everything it stored was deleted. Not confirmed as ended at the Bank: " +
                result.notEnded.joinToString("; ") { "${it.bankName.ifEmpty { "a Bank" }} (${why(it)})" } +
                ". Each stays at its Bank until it expires; you can end it at the Bank."
        },
    )
}

/**
 * How the names of the Banks of Start Over reach the new process (ADR-002 §3 O-17 step 3): as text in the intent that
 * starts the app, never in a file. One line per Bank: why, the Data Provider's name and the Bank's name, by tabs.
 */
object StartOverHandOver {
    /** The name of the intent's extra. */
    const val EXTRA = "slussen.start-over.not-ended"

    fun encode(result: StartOverResult): ArrayList<String> =
        ArrayList(result.notEnded.map { listOf(it.because.name, it.dataProviderName, it.bankName).joinToString("\t") { part -> part.replace('\t', ' ') } })

    /** The result handed over, or null when the app was not started by Start Over; a line that cannot be read is named as not confirmed. */
    fun decode(lines: List<String>?): StartOverResult? = lines?.let { handed ->
        StartOverResult(
            handed.map { line ->
                val parts = line.split('\t')
                val because = NotEndedBecause.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: NotEndedBecause.NotConfirmed
                ConsentNotEnded(parts.getOrNull(2) ?: line, parts.getOrNull(1).orEmpty(), because)
            },
        )
    }
}

/**
 * The card of the new installation after Start Over, held in this process's memory only and never stored (UC-010 A12
 * step 4): the names handed over in the intent that started the app ([receive]), or, when a start finished Start Over
 * without them ([servicesStarted]), that they are not known. Closed, it is not shown again.
 */
class StartedOverNotice {
    private val shown = MutableStateFlow<StartedOverCardUi?>(null)
    private var closed = false
    private var received = false

    val card: StateFlow<StartedOverCardUi?> = shown.asStateFlow()

    fun receive(result: StartOverResult?) {
        if (result == null || closed) return
        received = true
        shown.value = StartOverTexts.card(result)
    }

    fun servicesStarted(startedOver: Boolean) {
        if (!startedOver || closed || received) return
        shown.value = StartOverTexts.card(null)
    }

    fun close() {
        closed = true
        shown.value = null
    }
}

/**
 * Start Over on Settings (UC-010 A12): the statement from stored data, the confirmation, and Start Over itself in the
 * process's own scope ([UiServices.processScope]), so that leaving Settings never cancels it. Each state shows at the row
 * before anything is sent, and every answer that leaves the data as it was shows there too (NFR-023).
 */
class StartOverViewModel(private val services: UiServices) : ViewModel() {
    private val current = MutableStateFlow(StartOverUi())
    val state: StateFlow<StartOverUi> = current.asStateFlow()

    /** Step 1: the statement, read from stored data; a failure to read it is the answer. */
    fun open() {
        val startOver = services.startOver ?: return
        if (!current.value.enabled) return
        viewModelScope.launch {
            val statement = try {
                startOver.statement()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            current.update {
                if (statement == null) it.copy(dialog = null, answer = StartOverTexts.STATEMENT_NOT_READ) else it.copy(dialog = StartOverTexts.statement(statement), answer = null)
            }
        }
    }

    /** Step 2: cancelled, nothing deleted. */
    fun cancel() = current.update { it.copy(dialog = null) }

    /** Step 2, confirmed: Start Over runs in the process's scope, its state at the row from before anything is sent. */
    fun confirm() {
        val startOver = services.startOver ?: return
        if (current.value.dialog == null || !current.value.enabled) return
        current.value = StartOverUi(running = Texts.operation(slussen.core.operation.RunningOperation(slussen.core.operation.OperationKind.StartOver, null)), enabled = false)
        services.processScope.launch {
            val outcome = try {
                startOver.startOver(
                    restart = { result ->
                        current.update { it.copy(running = StartOverTexts.RESTARTING) }
                        services.restartForStartOver(result)
                    },
                    notRestarted = { current.value = StartOverUi(answer = StartOverTexts.OPEN_AGAIN, enabled = false) },
                    asking = { toEnd -> current.update { it.copy(running = StartOverTexts.asking(toEnd)) } },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                StartOverOutcome.StoredDataNotRead
            }
            // Only answers that deleted nothing return here (ADR-002 §3 O-17).
            current.value = StartOverUi(
                answer = when (outcome) {
                    is StartOverOutcome.NotNow -> StartOverTexts.notNow(outcome.why)
                    StartOverOutcome.StoredDataNotRead -> StartOverTexts.STATEMENT_NOT_READ
                    is StartOverOutcome.NotRecorded -> StartOverTexts.notRecorded(outcome.asked)
                },
            )
        }
    }
}
