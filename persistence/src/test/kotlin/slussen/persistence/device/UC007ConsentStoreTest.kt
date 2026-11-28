package slussen.persistence.device

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.AnswerRejection
import slussen.core.port.ConsumedAnswer
import slussen.core.port.NewConsentRequest
import slussen.persistence.RoomConsentStore
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.testing.ConsentStoreScenario
import slussen.testing.InMemoryConsentStore
import java.time.Instant

/**
 * O-10 of `device.db` version 2 on the JVM: the bodies of [ConsentDao]'s transactions run on a list-backed subclass of
 * the DAO (the SQLite transaction around each, and its rollback, are proven on a device in
 * `UC007GrantBankConsentPersistenceIT`). The steps of [ConsentStoreScenario] give exactly the same answers on the Room
 * store and on the fake of `:testing` that every core test uses.
 */
@DisplayName("UC-007: Grant Bank Consent, O-10 of device.db on the JVM")
class UC007ConsentStoreTest {
    /** The four tables of version 2 as lists; identifiers are never reused, as with AUTOINCREMENT. */
    internal class ListedConsentDao : ConsentDao() {
        val consentList = mutableListOf<BankConsentEntity>()
        val sessionList = mutableListOf<BankSessionEntity>()
        val requestList = mutableListOf<ConsentRequestEntity>()
        val correlationList = mutableListOf<ConsentRequestCorrelationEntity>()
        private var lastId = mutableMapOf<String, Long>()

        private fun next(table: String): Long = (lastId.getOrDefault(table, 0L) + 1).also { lastId[table] = it }

        override suspend fun consents() = consentList.sortedBy { it.id }

        override suspend fun sessions() = sessionList.sortedBy { it.id }

        override suspend fun requests() = requestList.sortedBy { it.id }

        override suspend fun request(id: Long) = requestList.firstOrNull { it.id == id }

        override suspend fun requestsOf(bankReference: String, dataProviderId: Long) =
            requestList.filter { it.bankReference == bankReference && it.dataProviderId == dataProviderId }.sortedBy { it.id }

        override suspend fun correlationWithDigest(stateDigest: String) = correlationList.firstOrNull { it.stateDigest == stateDigest }

        override suspend fun correlationOf(requestId: Long) = correlationList.firstOrNull { it.consentRequestId == requestId }

        override suspend fun consentOf(bankId: Long, dataProviderId: Long) = consentList.firstOrNull { it.bankId == bankId && it.dataProviderId == dataProviderId }

        override suspend fun insertRequest(request: ConsentRequestEntity): Long = next("consent_request").also { requestList += request.copy(id = it) }

        override suspend fun insertCorrelation(correlation: ConsentRequestCorrelationEntity) {
            check(requestList.any { it.id == correlation.consentRequestId } && correlationList.none { it.stateDigest == correlation.stateDigest || it.consentRequestId == correlation.consentRequestId })
            correlationList += correlation
        }

        override suspend fun insertConsent(consent: BankConsentEntity): Long {
            check(consentList.none { it.bankId == consent.bankId && it.dataProviderId == consent.dataProviderId }) { "unique (bank_id, data_provider_id)" }
            return next("bank_consent").also { consentList += consent.copy(id = it) }
        }

        override suspend fun insertSession(session: BankSessionEntity): Long {
            check(consentList.any { it.id == session.bankConsentId } && sessionList.none { it.bankConsentId == session.bankConsentId })
            return next("bank_session").also { sessionList += session.copy(id = it) }
        }

        override suspend fun setAnswered(requestId: Long) {
            requestList.replaceAll { if (it.id == requestId) it.copy(answered = true) else it }
        }

        override suspend fun setRefusedAt(consentId: Long, refusedAt: Instant) {
            consentList.replaceAll { if (it.id == consentId) it.copy(refusedAt = refusedAt) else it }
        }

        override suspend fun deleteCorrelation(requestId: Long) {
            correlationList.removeAll { it.consentRequestId == requestId }
        }

        override suspend fun deleteRequest(requestId: Long) {
            check(correlationList.none { it.consentRequestId == requestId }) { "a correlation still refers to it" }
            requestList.removeAll { it.id == requestId }
        }

        override suspend fun deleteSessionOf(consentId: Long) {
            sessionList.removeAll { it.bankConsentId == consentId }
        }

        override suspend fun deleteConsent(consentId: Long) {
            check(sessionList.none { it.bankConsentId == consentId }) { "a Bank Session still refers to it" }
            consentList.removeAll { it.id == consentId }
        }
    }

    @Test
    @UseCase(
        id = "UC-007",
        scenario = "A10: Consent Request Abandoned",
        businessRules = ["BR-002", "BR-003", "BR-007"],
        requirements = ["NFR-013"],
    )
    @DisplayName("BR-002, BR-003, BR-007, A10 (O-10): every step of the scenario gives the same answer on the Room store of device.db and on the fake: a Consent Request answered once, within its lifetime, only while it is the most recent of its Bank; a Bank Consent stored only for an answered one, in place of the earlier one with its Bank Session; a refusal kept at its earliest time; Cancel deletes only an unanswered one; revoking removes the Bank Consent, its Bank Session and the Bank's Consent Requests with that Data Provider")
    fun theRoomStoreAnswersAsTheFakeDoes() = runBlocking {
        val room = ConsentStoreScenario(RoomConsentStore(ListedConsentDao())).run()
        val fake = ConsentStoreScenario(InMemoryConsentStore()).run()

        assertEquals(ConsentStoreScenario.EXPECTED, fake)
        assertEquals(ConsentStoreScenario.EXPECTED, room)
    }

    @OptIn(SensitiveApi::class)
    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-006"], requirements = ["NFR-008", "NFR-013"])
    @DisplayName("BR-006 (ADR-006 §2): of the state only its digest is stored; the Bank Session and the handle are kept as their text and read back masked; no entity shows them in its text")
    fun onlyTheDigestIsStoredAndTheSessionIsMasked() = runBlocking {
        val dao = ListedConsentDao()
        val store = RoomConsentStore(dao)
        val digest = "f".repeat(64)
        val started = store.startRequest(
            NewConsentRequest("Aurora Bank (FI)", 2, T0, T0.plusSeconds(900), digest, Secret("PLANTED-HANDLE-41c9")),
        )
        val accepted = store.consumeAnswer(digest, T0) as ConsumedAnswer.Accepted
        store.replaceConsent(started.id, slussen.core.port.GrantedBankConsent(7, T0, T0.plusSeconds(86_400), slussen.pluginapi.provider.ConsentSession("PLANTED-SESSION-7d2e"), T0))

        assertEquals(listOf(digest), dao.correlationList.map { it.stateDigest })
        assertEquals("PLANTED-HANDLE-41c9", accepted.providerHandle!!.reveal())
        assertEquals(listOf("PLANTED-SESSION-7d2e"), dao.sessionList.map { it.sessionReference })
        assertEquals("PLANTED-SESSION-7d2e", store.bankSessions().single().session.reveal())
        val texts = (dao.sessionList + dao.correlationList).map { it.toString() } + store.bankSessions().map { it.toString() } + accepted.toString()
        assertTrue(texts.none { "PLANTED" in it }, "$texts")
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"], requirements = ["NFR-013"])
    @DisplayName("BR-002, A2 (O-10): an answer to a Consent Request that is not the most recent of its Bank and Data Provider is rejected and nothing is changed, also when an earlier one is still stored")
    fun anAnswerToAnOlderRequestIsRejected() = runBlocking {
        val dao = ListedConsentDao()
        // Planted directly: two Consent Requests of one Bank, which O-10 start never leaves; consume checks it all the same.
        val older = dao.insertRequest(ConsentRequestEntity(bankReference = "Aurora Bank (FI)", dataProviderId = 2, startedAt = T0, expiresAt = T0.plusSeconds(900), answered = false))
        dao.insertCorrelation(ConsentRequestCorrelationEntity(older, "a".repeat(64), null))
        val newer = dao.insertRequest(ConsentRequestEntity(bankReference = "Aurora Bank (FI)", dataProviderId = 2, startedAt = T0.plusSeconds(60), expiresAt = T0.plusSeconds(960), answered = false))
        dao.insertCorrelation(ConsentRequestCorrelationEntity(newer, "b".repeat(64), null))
        val store = RoomConsentStore(dao)

        assertEquals(ConsumedAnswer.Rejected(AnswerRejection.NotTheMostRecent), store.consumeAnswer("a".repeat(64), T0.plusSeconds(90)))
        assertFalse(dao.requestList.single { it.id == older }.answered)
        assertTrue(store.consumeAnswer("b".repeat(64), T0.plusSeconds(90)) is ConsumedAnswer.Accepted)
        assertEquals(
            slussen.core.port.ReplacedConsent.Refused(slussen.core.port.ReplaceRefusal.NewerRequestExists),
            store.replaceConsent(older, slussen.core.port.GrantedBankConsent(7, T0, T0.plusSeconds(86_400), slussen.pluginapi.provider.ConsentSession("s"), T0)),
        )
        assertTrue(dao.consentList.isEmpty())
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-11-28T10:00:00Z")
    }
}
