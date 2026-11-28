package slussen.testing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import slussen.core.port.AnswerRejection
import slussen.core.port.ConsentStore
import slussen.core.port.ConsumedAnswer
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.core.port.ReplaceRefusal
import slussen.core.port.ReplacedConsent
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredBankSession
import slussen.core.port.StoredConsentRequest
import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * An in-memory fake of the [ConsentStore] port (O-10 of ADR-002 §3, `device.db` version 2), for tests of the core. Each
 * operation runs under one lock and changes nothing before its checks pass, so it is all or nothing as one transaction
 * of `device.db` is; [ConsentStoreScenario] proves that it answers as the Room store does. [consents] is the list the
 * import status reads ([InMemoryImportStatusStore.consents] in [TestSlice]), so a Bank Consent a test plants there is
 * one of this store's.
 */
class InMemoryConsentStore(val consents: MutableList<StoredBankConsent> = mutableListOf()) : ConsentStore {
    private class Correlation(val requestId: Long, val stateDigest: String, val providerHandle: Secret?)

    private val lock = Mutex()
    private val sessions = mutableListOf<StoredBankSession>()
    private val requests = mutableListOf<StoredConsentRequest>()
    private val correlations = mutableListOf<Correlation>()
    private var lastConsentId = 0L
    private var lastSessionId = 0L
    private var lastRequestId = 0L

    /** Makes O-10 replace fail before it changes anything, as a `device.db` that cannot be written would. */
    var failsToReplace: Boolean = false

    /** Runs as O-10 replace begins, before it changes anything: a test can hold or cancel the completion there. */
    var beforeReplace: suspend () -> Unit = {}

    /** Makes O-10 start fail before it changes anything, as a `device.db` that cannot be written would. */
    var failsToStart: Boolean = false

    /** Abandoning a Consent Request fails, as a storage failure would (NFR-023). */
    var failsToAbandon: Boolean = false

    private fun failing(fails: Boolean) {
        if (fails) throw IllegalStateException("The invented storage of this test cannot be written.")
    }

    /** Makes reading the Bank Consents fail, as a `device.db` that cannot be read would (UC-010 A12). */
    var failsToRead: Boolean = false

    override suspend fun bankConsents(): List<StoredBankConsent> = lock.withLock {
        if (failsToRead) throw IllegalStateException("The invented storage of this test cannot be read.")
        consents.sortedBy { it.id }
    }

    override suspend fun bankSessions(): List<StoredBankSession> = lock.withLock { sessions.toList() }

    override suspend fun consentRequests(): List<StoredConsentRequest> = lock.withLock { requests.toList() }

    private fun requestsOf(bankReference: String, dataProviderId: Long) =
        requests.filter { it.bankReference == bankReference && it.dataProviderId == dataProviderId }

    private fun newerExists(request: StoredConsentRequest) = requestsOf(request.bankReference, request.dataProviderId).any { it.id > request.id }

    private fun deleteRequestWithCorrelation(requestId: Long) {
        correlations.removeAll { it.requestId == requestId }
        requests.removeAll { it.id == requestId }
    }

    private fun consentOf(bankId: Long, dataProviderId: Long) = consents.firstOrNull { it.bankId == bankId && it.dataProviderId == dataProviderId }

    override suspend fun startRequest(request: NewConsentRequest): StoredConsentRequest = lock.withLock {
        failing(failsToStart)
        require(correlations.none { it.stateDigest == request.stateDigest }) { "A Consent Request with this state digest is already stored." }
        requestsOf(request.bankReference, request.dataProviderId).forEach { deleteRequestWithCorrelation(it.id) }
        lastRequestId = maxOf(lastRequestId, requests.maxOfOrNull { it.id } ?: 0) + 1
        val stored = StoredConsentRequest(lastRequestId, request.bankReference, request.dataProviderId, request.startedAt, request.expiresAt, answered = false)
        requests += stored
        correlations += Correlation(stored.id, request.stateDigest, request.providerHandle)
        stored
    }

    override suspend fun requestWithDigest(stateDigest: String): StoredConsentRequest? = lock.withLock {
        correlations.firstOrNull { it.stateDigest == stateDigest }?.let { found -> requests.firstOrNull { it.id == found.requestId } }
    }

    override suspend fun consumeAnswer(stateDigest: String, arrivedAt: Instant): ConsumedAnswer = lock.withLock {
        val correlation = correlations.firstOrNull { it.stateDigest == stateDigest }
        val request = correlation?.let { found -> requests.firstOrNull { it.id == found.requestId } }
            ?: return@withLock ConsumedAnswer.Rejected(AnswerRejection.NoSuchRequest)
        val rejection = when {
            newerExists(request) -> AnswerRejection.NotTheMostRecent
            request.answered -> AnswerRejection.AlreadyAnswered
            arrivedAt.isAfter(request.expiresAt) -> AnswerRejection.LifetimeEnded
            else -> null
        }
        if (rejection != null) return@withLock ConsumedAnswer.Rejected(rejection)
        val answered = request.copy(answered = true)
        requests[requests.indexOf(request)] = answered
        ConsumedAnswer.Accepted(answered, correlation.providerHandle)
    }

    override suspend fun replaceConsent(requestId: Long, granted: GrantedBankConsent): ReplacedConsent {
        beforeReplace()
        return replaceUnderLock(requestId, granted)
    }

    private suspend fun replaceUnderLock(requestId: Long, granted: GrantedBankConsent): ReplacedConsent = lock.withLock {
        failing(failsToReplace)
        val request = requests.firstOrNull { it.id == requestId } ?: return@withLock ReplacedConsent.Refused(ReplaceRefusal.NoSuchRequest)
        if (newerExists(request)) return@withLock ReplacedConsent.Refused(ReplaceRefusal.NewerRequestExists)
        if (!request.answered) return@withLock ReplacedConsent.Refused(ReplaceRefusal.NotAnswered)
        consentOf(granted.bankId, request.dataProviderId)?.let { earlier ->
            sessions.removeAll { it.bankConsentId == earlier.id }
            consents.remove(earlier)
        }
        lastConsentId = maxOf(lastConsentId, consents.maxOfOrNull { it.id } ?: 0) + 1
        lastSessionId = maxOf(lastSessionId, sessions.maxOfOrNull { it.id } ?: 0) + 1
        val consent = StoredBankConsent(lastConsentId, granted.bankId, request.dataProviderId, granted.grantedAt, granted.expiresAt, refusedAt = null)
        val session = StoredBankSession(lastSessionId, consent.id, granted.session, granted.openedAt)
        consents += consent
        sessions += session
        ReplacedConsent.Stored(consent, session)
    }

    override suspend fun abandonRequest(requestId: Long): Boolean = lock.withLock {
        failing(failsToAbandon)
        val request = requests.firstOrNull { it.id == requestId }
        if (request == null || request.answered) return@withLock false
        deleteRequestWithCorrelation(request.id)
        true
    }

    override suspend fun markRefused(bankId: Long, dataProviderId: Long, refusedAt: Instant): Boolean = lock.withLock {
        val consent = consentOf(bankId, dataProviderId) ?: return@withLock false
        if (consent.refusedAt == null || refusedAt.isBefore(consent.refusedAt)) consents[consents.indexOf(consent)] = consent.copy(refusedAt = refusedAt)
        true
    }

    /** The Bank Consents, with their Bank Sessions, of every Bank not among [heldBankIds] removed ([InMemoryDeviceBankReferences]). */
    suspend fun removeConsentsOfBanksOtherThan(heldBankIds: Set<Long>): Int = lock.withLock {
        val gone = consents.filter { it.bankId !in heldBankIds }
        sessions.removeAll { session -> gone.any { it.id == session.bankConsentId } }
        consents.removeAll(gone)
        gone.size
    }

    override suspend fun revoke(bankId: Long, dataProviderId: Long, bankReference: String) = lock.withLock {
        consentOf(bankId, dataProviderId)?.let { consent ->
            sessions.removeAll { it.bankConsentId == consent.id }
            consents.remove(consent)
        }
        requests.filter { it.bankReference == bankReference }.forEach { deleteRequestWithCorrelation(it.id) }
    }
}
