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
import slussen.core.consent.ConsentAsked
import slussen.core.consent.ConsentNotEnded
import slussen.core.operation.OperationKind
import slussen.core.operation.RunningOperation
import slussen.core.port.StartOverRecord
import slussen.core.port.StartedBy
import slussen.core.consent.ConsentToEnd
import slussen.core.consent.NotEndedBecause
import slussen.core.settings.StartOverOutcome
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
    const val CARD_TITLE = "Slussen started over"
    const val EVERY_CONSENT_ENDED = "Everything it stored was deleted. Every Bank Consent was confirmed as ended."
    const val NAMES_NOT_KNOWN = "Everything it stored was deleted. Which Bank Consents were not confirmed as ended is not known: " +
        "check each at its Bank; one not ended stays there until it expires."
    const val LIST_NOT_COMPLETE = " The list may not be complete: check each Bank Consent at its Bank."
    private const val NO_LONGER_USABLE = " The Bank Consents Slussen asked to end are no longer usable: grant them again, or start over again."

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
        is StartOverUnavailable.AnotherOperation -> (why.running?.let(::running) ?: "another operation") + " is running"
        is StartOverUnavailable.ConsentRequestWaiting ->
            "a Consent Request for ${why.bankReference} waits for the Bank's result: cancel it on its Data Provider's page, or wait until its time ends"
        StartOverUnavailable.JournalExportBeingWritten -> "a Journal Export is being written"
        is StartOverUnavailable.Paused -> when (why.reason) {
            slussen.core.operation.OperationRefusal.UploadPlanAwaitingConfirmation -> "an Upload Plan awaits confirmation"
            slussen.core.operation.OperationRefusal.UploadBeingWritten -> "an Upload is being written"
        }
    } + ". Nothing was deleted. Start over once it has ended."

    /** The operation that runs, as a sentence's subject (`#start-over-unavailable`). */
    private fun running(running: RunningOperation): String {
        val bySchedule = running.startedBy == StartedBy.Schedule
        return when (running.kind) {
            OperationKind.Fetch -> "a Fetch of ${running.subject ?: "a Data Provider"}" + if (bySchedule) " that the Fetch Schedule started" else ""
            OperationKind.Comparison -> "a read of ${running.subject ?: "the Budget App"}"
            OperationKind.FileImport -> "an import of a Transaction File for ${running.subject ?: "a Bank Account"}"
            OperationKind.JournalExport -> "the Journal Export's read of ${running.subject ?: "the Budget App"}"
            OperationKind.BankConsent -> "a request to ${running.subject ?: "a Data Provider"} about a Bank Consent"
            else -> Texts.operation(running)
        }
    }

    fun notRecorded(asked: List<ConsentAsked>): String = "Not started over: it could not be recorded. Nothing was deleted." +
        if (asked.isEmpty()) " Try again." else NO_LONGER_USABLE

    fun notReadWhileEnding(asked: List<ConsentAsked>): String = "Not started over: the stored data could not be read. Nothing was deleted. " +
        "Already asked: " + asked.joinToString("; ") { "${it.bankName}, " + (it.notEnded?.let { because -> "not ended: " + why(ConsentNotEnded(it.bankName, it.dataProviderName, because)) } ?: "ended") } +
        "." + NO_LONGER_USABLE

    private fun why(notEnded: ConsentNotEnded): String = when (notEnded.because) {
        NotEndedBecause.EndedAtDataProviderOnly -> "${notEnded.dataProviderName} ended it on its side only"
        NotEndedBecause.NotConfirmed -> "${notEnded.dataProviderName} did not confirm it"
        NotEndedBecause.CredentialsMissing -> "${notEnded.dataProviderName}'s credentials were missing"
        NotEndedBecause.CredentialsRefused -> "${notEnded.dataProviderName}'s credentials were refused"
        NotEndedBecause.HeldBackByGate -> "${notEnded.dataProviderName} was not contacted: its requests were held back"
        NotEndedBecause.NotContactable -> "${notEnded.dataProviderName.ifEmpty { "its Data Provider" }} could not be contacted"
    }

    private val notContacted = setOf(
        NotEndedBecause.CredentialsMissing, NotEndedBecause.CredentialsRefused, NotEndedBecause.HeldBackByGate, NotEndedBecause.NotContactable,
    )

    private fun named(banks: List<ConsentNotEnded>) = banks.joinToString("; ") { "${it.bankName.ifEmpty { "a Bank" }} (${why(it)})" }

    /** The card of `#started-over`, from the record the start read: not confirmed as ended apart from not contacted. */
    fun card(record: StartOverRecord): StartedOverCardUi {
        val banks = record.notEnded ?: return StartedOverCardUi(CARD_TITLE, NAMES_NOT_KNOWN)
        val tail = if (record.complete) "" else LIST_NOT_COMPLETE
        if (banks.isEmpty()) return StartedOverCardUi(CARD_TITLE, (if (record.complete) EVERY_CONSENT_ENDED else "Everything it stored was deleted.") + tail)
        val (notReached, notConfirmed) = banks.partition { it.because in notContacted }
        val parts = listOfNotNull(
            notConfirmed.takeIf { it.isNotEmpty() }?.let { "Not confirmed as ended at the Bank: ${named(it)}." },
            notReached.takeIf { it.isNotEmpty() }?.let { "Not contacted: ${named(it)}." },
        )
        return StartedOverCardUi(
            CARD_TITLE,
            "Everything it stored was deleted. " + parts.joinToString(" ") + " Each stays at its Bank until it expires; you can end it at the Bank." + tail,
        )
    }
}

/**
 * The card of the new installation after Start Over, held in this process's memory only (UC-010 A12 step 4): shown only
 * when this process's start finished a Start Over, with the Banks read from its record ([atStart]); nothing that starts
 * the app can make it show. Closed, it is not shown again.
 */
class StartedOverNotice {
    private val shown = MutableStateFlow<StartedOverCardUi?>(null)
    private var closed = false

    val card: StateFlow<StartedOverCardUi?> = shown.asStateFlow()

    // A12 step 4 of UC-010: the names are read from the record of Start Over by the start that finishes it and shown until the Operator closes them.
    fun atStart(record: StartOverRecord?) {
        if (record == null || closed) return
        shown.value = StartOverTexts.card(record)
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
                    is StartOverOutcome.NotReadWhileEnding -> StartOverTexts.notReadWhileEnding(outcome.asked)
                },
            )
        }
    }
}
