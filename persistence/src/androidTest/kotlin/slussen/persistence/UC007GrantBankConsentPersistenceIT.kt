package slussen.persistence

import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.ConsumedAnswer
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.persistence.device.DeviceDatabase
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.testing.ConsentStoreScenario
import java.time.Instant

/**
 * O-10 of ADR-002 §3 on `device.db` version 2 on a device or emulator (UC-007): the steps of [ConsentStoreScenario]
 * give on SQLite exactly what the fake of `:testing` gives on the JVM; each operation is one transaction, so a failure
 * injected before its commit leaves nothing of it visible; the foreign keys of version 2 hold; the start sequence's check of `device.db` version 2 is in
 * `UC008CheckImportStatusPersistenceIT`.
 */
@DisplayName("UC-007: Grant Bank Consent, on the device")
class UC007GrantBankConsentPersistenceIT {
    private lateinit var device: DeviceDatabase
    private lateinit var store: RoomConsentStore

    @BeforeEach
    fun open() {
        DeviceFiles.wipe()
        device = SlussenDatabases.openDevice(DeviceFiles.context)
        store = RoomConsentStore(device.consents())
    }

    @AfterEach
    fun close() {
        if (device.isOpen) device.close()
        DeviceFiles.wipe()
    }

    private val at: Instant = Instant.parse("2026-11-28T10:00:00Z")

    private fun request(digest: Char, bank: String = "Aurora Bank (FI)") =
        NewConsentRequest(bank, 2, at, at.plusSeconds(900), digest.toString().repeat(64), Secret("invented-handle"))

    private fun granted(session: String) = GrantedBankConsent(7, at, at.plusSeconds(86_400 * 180L), ConsentSession(session), at)

    /** Everything of the four tables of version 2, so that any trace of a rolled-back operation shows. */
    private suspend fun everything(): List<Any> = listOf(store.bankConsents(), store.bankSessions().map { it.id to it.bankConsentId }, store.consentRequests())

    private class Injected : RuntimeException("A failure injected before the commit (ADR-002 §3).")

    /** Runs [operation] inside a transaction that fails right after it, before the commit, and returns what is stored after. */
    private suspend fun failingBeforeTheCommit(operation: suspend () -> Unit): List<Any> {
        val failure = runCatching { device.withTransaction { operation(); throw Injected() } }.exceptionOrNull()
        assertTrue(failure is Injected, "$failure")
        return everything()
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A10: Consent Request Abandoned", businessRules = ["BR-002", "BR-003", "BR-007"], requirements = ["NFR-013"])
    @DisplayName("BR-002, BR-003, BR-007, A10 (O-10): every step of the scenario gives on device.db exactly what the fake gives")
    fun theScenarioGivesWhatTheFakeGives() = runBlocking {
        assertEquals(ConsentStoreScenario.EXPECTED, ConsentStoreScenario(store).run())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003", "BR-007"], requirements = ["NFR-013"])
    @DisplayName("ADR-002 §3 (O-10): a failure injected before the commit of start, consume, replace, abandon, mark refused or revoke leaves nothing of that operation visible")
    fun eachOperationIsAllOrNothing() = runBlocking {
        val empty = everything()
        assertEquals(empty, failingBeforeTheCommit { store.startRequest(request('a')) })

        val started = store.startRequest(request('a'))
        val unanswered = everything()
        assertEquals(unanswered, failingBeforeTheCommit { store.startRequest(request('b')) })
        assertEquals(unanswered, failingBeforeTheCommit { store.consumeAnswer("a".repeat(64), at) })
        assertEquals(unanswered, failingBeforeTheCommit { store.abandonRequest(started.id) })

        assertTrue(store.consumeAnswer("a".repeat(64), at) is ConsumedAnswer.Accepted)
        val answered = everything()
        assertEquals(answered, failingBeforeTheCommit { store.replaceConsent(started.id, granted("session-one")) })

        store.replaceConsent(started.id, granted("session-one"))
        val granted = everything()
        assertEquals(granted, failingBeforeTheCommit { store.markRefused(7, 2, at.plusSeconds(60)) })
        assertEquals(granted, failingBeforeTheCommit { store.revoke(7, 2, "Aurora Bank (FI)") })
        assertEquals(1, store.bankConsents().size)
        assertEquals(1, store.bankSessions().size)
    }

    @OptIn(SensitiveApi::class)
    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-006"], requirements = ["NFR-008"])
    @DisplayName("BR-006 (ADR-006 §2): device.db holds the state's digest only, never the state; the Bank Session and the handle read back as stored")
    fun onlyTheDigestOfTheStateIsStored() = runBlocking {
        val state = "PLANTED-STATE-VALUE-9b1f"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(state.toByteArray()).joinToString("") { "%02x".format(it) }
        val started = store.startRequest(NewConsentRequest("Aurora Bank (FI)", 2, at, at.plusSeconds(900), digest, Secret("invented-handle")))
        val accepted = store.consumeAnswer(digest, at) as ConsumedAnswer.Accepted
        store.replaceConsent(started.id, granted("invented-session-reference"))
        device.close()

        val bytes = SlussenDatabases.deviceFile(DeviceFiles.context).readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(state))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains(digest))
        assertEquals("invented-handle", accepted.providerHandle!!.reveal())
        device = SlussenDatabases.openDevice(DeviceFiles.context)
        assertEquals("invented-session-reference", RoomConsentStore(device.consents()).bankSessions().single().session.reveal())
    }
}
