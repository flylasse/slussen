package slussen.app.ui.consent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import slussen.app.ui.UiServices
import slussen.core.consent.AbandonOutcome
import slussen.core.consent.AnswerNotAccepted
import slussen.core.consent.CompletionOutcome
import slussen.core.port.ServiceKey
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.value.Secret
import java.time.Clock
import java.time.Instant

/** A Data Provider of this build that needs a Bank Consent, by its key and the name its part declares (UC-007 BR-008, K-04). */
data class ConsentProvider(val service: ServiceKey, val name: String)

/** A Consent Request that waits for its answer within its lifetime (UC-007 step 4), from stored data. */
data class WaitingConsent(val service: ServiceKey, val requestId: Long, val bankReference: String, val expiresAt: Instant)

/** What ended a Consent Request, shown as its result (steps 6-10, A1-A4, A8-A10). */
sealed interface ConsentEnd {
    /** The answer, returned at the return address or pasted, as the core completed it. */
    data class Completed(val outcome: CompletionOutcome, val pasted: Boolean) : ConsentEnd

    /**
     * A10: cancelled by the Operator, or [timedOut]: no result arrived within the Consent Request lifetime; [afterStart]:
     * found at a start of Slussen, which had stopped meanwhile (E4 review item 5).
     */
    data class Abandoned(val bankReference: String, val timedOut: Boolean, val afterStart: Boolean = false) : ConsentEnd

    /** A10: Cancel did not delete the Consent Request (NFR-023): it is answered meanwhile, could not be stored, or is paused. */
    data class NotAbandoned(val bankReference: String, val outcome: AbandonOutcome) : ConsentEnd
}

/** The result of the last Consent Request that ended, for the Data Provider [service] (null when not known) and its Bank. */
data class ConsentResult(
    val service: ServiceKey?,
    val bankReference: String?,
    val end: ConsentEnd,
    /** Shown on the Data Provider's page: it goes when the Operator leaves that page (review D4 item 2). */
    val shownOnPage: Boolean = false,
    /** Consent Requests whose lifetime ended without an answer, abandoned once the Operator has seen this, so that it is said once. */
    val forget: List<Long> = emptyList(),
)

/** What [ConsentAnswers] holds for every screen: answers being handed in, the Consent Requests that wait, the last result. */
data class ConsentAnswersState(
    val completing: Int = 0,
    val waiting: List<WaitingConsent> = emptyList(),
    val result: ConsentResult? = null,
    /** The Consent Requests whose Cancel runs (NFR-023). */
    val cancelling: Set<Long> = emptySet(),
    /** E4 review item 1: an address was opened that answers no Consent Request; it changed nothing, and goes when acknowledged. */
    val strayAddress: Boolean = false,
)

/**
 * The answers of the Bank's Strong Customer Authentication for the whole process (UC-007 steps 6-7, A10, BR-002,
 * BR-011), so that what outlives a screen is kept in one place (review D4 items 1-2): each answer, returned at the
 * return address or pasted, is handed to the core once, at once, on [scope] (off the main thread in the app), with the
 * time it arrived taken when it arrived, also when the services are not started yet (a cold start with the answer in
 * the launching intent). Nothing of an answer is kept or logged (BR-006): only the core's outcome is.
 */
class ConsentAnswers(
    private val services: StateFlow<UiServices?>,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val mutableState = MutableStateFlow(ConsentAnswersState())
    val state: StateFlow<ConsentAnswersState> = mutableState

    /** One answer at a time, in the order they arrived, so that a second one sees the result of the first. */
    private val order = Mutex()

    /** An answer at the return address was handed in in this process: a second delivery of it is not the Operator's result. */
    private var returnedBefore = false

    /** An address from the return is being checked: further ones are dropped, so that a flood queues nothing (E4 review item 1). */
    @Volatile
    private var checkingReturned = false

    // UC-007 BR-011: Slussen's return address hands the answer back to Slussen.
    /** Step 6: an answer that reached the activity at the return address. */
    fun returned(address: String) = handIn(address, pastedFor = null)

    // UC-007 BR-011: A pasted address is checked exactly as a returned one.
    /** BR-011: the address the Operator pasted on the page of [service]. */
    fun pasted(service: ServiceKey, address: String) = handIn(address, pastedFor = service)

    private fun handIn(address: String, pastedFor: ServiceKey?) {
        // UC-007 BR-002: the time of arrival decides whether the answer is late, so it is taken now, before anything waits.
        val arrivedAt = clock.instant()
        val returned = pastedFor == null
        if (returned) {
            if (checkingReturned) return
            checkingReturned = true
        }
        val answer = ConsentAnswer(Secret(address))
        mutableState.update { it.copy(completing = it.completing + 1) }
        scope.launch {
            try {
                val started = services.filterNotNull().first()
                order.withLock { completed(started, answer, arrivedAt, pastedFor) }
            } finally {
                if (returned) checkingReturned = false
                mutableState.update { it.copy(completing = it.completing - 1) }
            }
        }
    }

    private suspend fun completed(services: UiServices, answer: ConsentAnswer, arrivedAt: Instant, pastedFor: ServiceKey?) {
        val consents = services.bankConsents ?: return
        val waitingBefore = mutableState.value.waiting
        val outcome = try {
            consents.complete(answer, arrivedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: what still throws comes from the stored data; nothing of the Bank Consent is stored.
            CompletionOutcome.NotStored(bankReference = null)
        }
        val returned = pastedFor == null
        // UC-007 BR-002: a second delivery of an answer handed in before is not shown as the Operator's result.
        val again = returned && returnedBefore && outcome == CompletionOutcome.NotAccepted(AnswerNotAccepted.AlreadyAnswered)
        if (returned) returnedBefore = true
        readWaitingQuietly(services)
        if (again) return
        // UC-007 BR-002: an address from the return that answers no Consent Request is the result of no Bank: it replaces and removes nothing.
        if (returned && outcome is CompletionOutcome.NotAccepted &&
            (outcome.reason == AnswerNotAccepted.Unreadable || outcome.reason == AnswerNotAccepted.NoSuchRequest)
        ) {
            mutableState.update { it.copy(strayAddress = true) }
            return
        }
        val bank = bankOf(outcome)
        val service = pastedFor
            ?: waitingBefore.firstOrNull { it.bankReference == bank }?.service
            ?: waitingBefore.singleOrNull()?.service
            ?: services.consentProviders.singleOrNull()?.service
        val known = bank ?: waitingBefore.singleOrNull { it.service == service }?.bankReference
        mutableState.update { it.copy(result = ConsentResult(service, known, ConsentEnd.Completed(outcome, pasted = !returned))) }
    }

    private fun bankOf(outcome: CompletionOutcome): String? = when (outcome) {
        is CompletionOutcome.Granted -> outcome.bankName
        is CompletionOutcome.NotGranted -> outcome.bankReference
        is CompletionOutcome.NotConfirmed -> outcome.bankReference
        is CompletionOutcome.MayHaveBeenGranted -> outcome.bankReference
        is CompletionOutcome.HeldBackByGate -> outcome.bankReference
        is CompletionOutcome.NotStored -> outcome.bankReference
        is CompletionOutcome.NotAccepted, is CompletionOutcome.NotAvailable -> null
    }

    /**
     * Reads the Consent Requests that wait, from stored data only (UC-008 BR-001); one this process saw wait whose lifetime
     * has ended without a result is shown as not granted (A10).
     */
    suspend fun readWaiting() {
        val started = services.value ?: return
        readWaitingQuietly(started)
    }

    private suspend fun readWaitingQuietly(services: UiServices) {
        val consents = services.bankConsents ?: return
        val now = clock.instant()
        val read = try {
            services.consentProviders.flatMap { provider ->
                consents.page(provider.service).waiting.map { WaitingConsent(provider.service, it.requestId, it.bankReference, it.expiresAt) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: the stored data could not be read; what was shown stays.
            return
        }
        mutableState.update { state ->
            // A10 of UC-007: without Cancel, the Consent Request becomes unanswerable when its lifetime ends, and Slussen shows that the consent was not granted.
            val timedOut = state.waiting.filter { before -> read.none { it.requestId == before.requestId } && now.isAfter(before.expiresAt) }
                .lastOrNull()
            state.copy(
                waiting = read,
                result = timedOut?.let {
                    ConsentResult(it.service, it.bankReference, ConsentEnd.Abandoned(it.bankReference, timedOut = true), forget = listOf(it.requestId))
                } ?: state.result,
            )
        }
    }

    /** Step 4: a Consent Request of [service] was started: an earlier result of it goes, and the waiting ones are read again. */
    fun started(service: ServiceKey) {
        val before = mutableState.value.result
        mutableState.update { state -> state.copy(result = state.result?.takeUnless { it.service == service || it.service == null }) }
        if (mutableState.value.result == null) forget(before)
        scope.launch { readWaiting() }
    }

    /** A10: the Operator cancels while Slussen waits for the result; Cancel shows that it runs, and its answer is the result. */
    fun cancel(service: ServiceKey, requestId: Long) {
        val waiting = mutableState.value.waiting.firstOrNull { it.requestId == requestId } ?: return
        if (requestId in mutableState.value.cancelling) return
        mutableState.update { it.copy(cancelling = it.cancelling + requestId) }
        scope.launch {
            try {
                val started = services.filterNotNull().first()
                val consents = started.bankConsents ?: return@launch
                val outcome = order.withLock {
                    try {
                        consents.abandon(requestId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        AbandonOutcome.NotStored
                    }
                }
                val end = when (outcome) {
                    AbandonOutcome.Abandoned -> ConsentEnd.Abandoned(waiting.bankReference, timedOut = false)
                    else -> ConsentEnd.NotAbandoned(waiting.bankReference, outcome)
                }
                mutableState.update { it.copy(result = ConsentResult(service, waiting.bankReference, end)) }
                readWaitingQuietly(started)
            } finally {
                mutableState.update { it.copy(cancelling = it.cancelling - requestId) }
            }
        }
    }

    /** The Data Provider's page of [service] shows the result: from now on it goes when the Operator leaves that page. */
    fun shown(service: ServiceKey) {
        mutableState.update { state ->
            val result = state.result
            if (result != null && result.service == service && !result.shownOnPage) state.copy(result = result.copy(shownOnPage = true)) else state
        }
    }

    // Review D4 item 2: an answer is shown until the Operator leaves the page that shows it, or dismisses it.
    /** The Operator left the page of [service]: a result it showed goes. */
    fun left(service: ServiceKey) {
        val before = mutableState.value.result
        mutableState.update { state ->
            val goes = state.result?.service == service && state.result.shownOnPage
            state.copy(result = if (goes) null else state.result, strayAddress = false)
        }
        if (before != null && mutableState.value.result == null) forget(before)
    }

    /** E4 review item 1: the Operator acknowledged the line about an address that answers no Consent Request. */
    fun acknowledgeStray() {
        mutableState.update { it.copy(strayAddress = false) }
    }

    /**
     * A10 (E4 review item 5): at a start, from stored data only (UC-008 BR-001): a Consent Request whose lifetime ended
     * while Slussen was stopped is said once, as not granted; once the Operator has seen it, it is abandoned, so that the
     * next start does not say it again. The waiting ones are read as at any time.
     */
    suspend fun atStart() {
        val started = services.value ?: return
        val consents = started.bankConsents ?: return
        val ended = try {
            consents.endedUnanswered()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emptyList()
        }
        val latest = ended.maxByOrNull { it.expiresAt }
        if (latest != null) {
            val service = ServiceKey.dataProvider(latest.dataProviderId)
            mutableState.update { state ->
                state.copy(
                    result = state.result ?: ConsentResult(
                        service, latest.bankReference, ConsentEnd.Abandoned(latest.bankReference, timedOut = true, afterStart = true),
                        forget = ended.map { it.requestId },
                    ),
                )
            }
        }
        readWaitingQuietly(started)
    }

    /** Abandons what a result said once (A10), so that it is not said again; a failure only means it is said again. */
    private fun forget(result: ConsentResult?) {
        val ids = result?.forget.orEmpty()
        if (ids.isEmpty()) return
        scope.launch {
            val consents = services.value?.bankConsents ?: return@launch
            ids.forEach { id ->
                try {
                    consents.abandon(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // NFR-023: kept stored; the next start says it again.
                }
            }
        }
    }

    /** Done, or OK on the banner: the result goes. */
    fun dismiss() {
        val before = mutableState.value.result
        mutableState.update { it.copy(result = null) }
        forget(before)
    }
}
