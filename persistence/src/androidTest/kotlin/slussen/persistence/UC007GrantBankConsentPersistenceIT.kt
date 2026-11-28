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

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-009"])
    @DisplayName("BR-009 (ADR-002 §2): at a start after slussen.db went back, the Bank Consent with its Bank Session and the Offered Bank Accounts of a Bank it no longer holds are removed, those of a Bank it holds stay, a Bank created afterwards under the same key is covered by no Bank Consent, and an ordinary start removes nothing")
    fun deviceDbKeepsNothingOfABankThatIsGone() = runBlocking {
        device.close()
        val clock = java.time.Clock.fixed(at, java.time.ZoneOffset.UTC)
        fun offered(reference: String, bankId: Long) = slussen.core.port.NewOfferedBankAccount(bankId, reference, null, reference, null, null, null, null, null, at)
        suspend fun grant(storage: SlussenStorage, bankId: Long, digest: Char) {
            val started = storage.consents.startRequest(NewConsentRequest("Bank $bankId (FI)", 2, at, at.plusSeconds(900), digest.toString().repeat(64), null))
            storage.consents.consumeAnswer(digest.toString().repeat(64), at)
            storage.consents.replaceConsent(started.id, granted("session-$bankId").copy(bankId = bankId))
        }
        fun SlussenStorage.closeAll() { main.close(); device.close(); journal.close() }

        val first = SlussenStorage.open(DeviceFiles.context, clock)
        val aurora = first.connections.bankNamed("Aurora Bank").id
        val vesta = first.connections.bankNamed("Vesta Sparbank").id
        grant(first, aurora, 'a')
        grant(first, vesta, 'b')
        first.offered.replaceOffered(2, aurora, listOf(offered("acct-aurora", aurora)))
        first.offered.replaceOffered(2, vesta, listOf(offered("acct-vesta", vesta)))
        first.closeAll()

        val ordinary = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(listOf(aurora, vesta), ordinary.consents.bankConsents().map { it.bankId })
        assertEquals(2, ordinary.consents.bankSessions().size)
        assertEquals(listOf("acct-aurora", "acct-vesta"), ordinary.offered.offered().map { it.providerReference })
        // slussen.db as a copy made before Vesta Sparbank was created would hold it: without the Bank, its key free again.
        ordinary.main.openHelper.writableDatabase.apply {
            execSQL("DELETE FROM bank WHERE id = $vesta")
            execSQL("UPDATE sqlite_sequence SET seq = $aurora WHERE name = 'bank'")
        }
        ordinary.closeAll()

        val after = SlussenStorage.open(DeviceFiles.context, clock)
        try {
            assertEquals(listOf(aurora), after.consents.bankConsents().map { it.bankId })
            assertEquals(after.consents.bankConsents().map { it.id }, after.consents.bankSessions().map { it.bankConsentId })
            assertEquals(listOf("acct-aurora"), after.offered.offered().map { it.providerReference })
            val nordlys = after.connections.bankNamed("Nordlys Bank").id
            assertEquals(vesta, nordlys, "the new Bank is given the key of the one that is gone")
            assertTrue(after.importStatus.bankConsents().none { it.bankId == nordlys })
        } finally {
            after.closeAll()
        }
        device = SlussenDatabases.openDevice(DeviceFiles.context)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("ADR-002 §3 (O-10): a Bank is removed only when no Bank Account names it by bank_id or by provider_bank_id and no Back-off names it; each clause on its own, with the foreign keys off so that only the statement decides")
    fun eachClauseOfTheRemovalHolds() = runBlocking {
        device.close()
        val storage = SlussenStorage.open(DeviceFiles.context, java.time.Clock.fixed(at, java.time.ZoneOffset.UTC))
        try {
            val byBank = storage.connections.bankNamed("By Bank").id
            val byProviderBank = storage.connections.bankNamed("By Provider Bank").id
            val byBackOff = storage.connections.bankNamed("By Back-off").id
            val unused = storage.connections.bankNamed("Unused").id
            storage.main.openHelper.writableDatabase.apply {
                execSQL("PRAGMA foreign_keys = OFF")
                fun bankAccount(id: Int, bankId: Long, providerBankId: Long?) = execSQL(
                    "INSERT INTO bank_account (id, generated_identifier, bank_id, provider_bank_id, name, currency, bank_account_type, identified_at, retired) " +
                        "VALUES ($id, '00000000-0000-4000-8000-00000000000$id', $bankId, ${providerBankId ?: "NULL"}, 'Invented $id', 'EUR', 'Checking', 0, 0)",
                )
                bankAccount(1, byBank, null)
                bankAccount(2, byBank, byProviderBank)
                execSQL("INSERT INTO back_off (data_provider_id, bank_id, cause, starts_at, ends_at) VALUES (999, $byBackOff, 'Request To Wait', 0, 1)")
            }
            assertFalse(storage.connections.removeBankIfUnreferenced(byBank))
            assertFalse(storage.connections.removeBankIfUnreferenced(byProviderBank))
            assertFalse(storage.connections.removeBankIfUnreferenced(byBackOff))
            assertTrue(storage.connections.removeBankIfUnreferenced(unused))
            assertEquals(setOf(byBank, byProviderBank, byBackOff), storage.connections.banks().map { it.id }.toSet())
        } finally {
            storage.main.close(); storage.device.close(); storage.journal.close()
        }
        device = SlussenDatabases.openDevice(DeviceFiles.context)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("ADR-002 §3 (O-10): at a start after a process that ended between creating the Bank and storing its Bank Consent, the Bank nothing refers to is removed from slussen.db; a Bank with a Bank Consent stays")
    fun aBankLeftBehindIsRemovedAtTheNextStart() = runBlocking {
        device.close()
        val clock = java.time.Clock.fixed(at, java.time.ZoneOffset.UTC)
        fun SlussenStorage.closeAll() { main.close(); device.close(); journal.close() }

        val first = SlussenStorage.open(DeviceFiles.context, clock)
        // Granted: its Bank has a Bank Consent.
        val granted = first.consents.startRequest(NewConsentRequest("Aurora Bank (FI)", 2, at, at.plusSeconds(900), "a".repeat(64), null))
        first.consents.consumeAnswer("a".repeat(64), at)
        val aurora = first.connections.bankNamed("Aurora Bank (FI)").id
        first.consents.replaceConsent(granted.id, granted("session-aurora").copy(bankId = aurora))
        // Cut off: the answer was taken and the Bank created, and the process ended before the Bank Consent was stored.
        first.consents.startRequest(NewConsentRequest("Vesta Sparbank (SE)", 2, at, at.plusSeconds(900), "b".repeat(64), null))
        first.consents.consumeAnswer("b".repeat(64), at)
        val vesta = first.connections.bankNamed("Vesta Sparbank (SE)").id
        first.closeAll()

        val after = SlussenStorage.open(DeviceFiles.context, clock)
        try {
            assertEquals(listOf(aurora), after.connections.banks().map { it.id })
            assertFalse(after.connections.removeBankIfUnreferenced(vesta), "it is gone already")
            assertEquals(listOf(aurora), after.consents.bankConsents().map { it.bankId })
        } finally {
            after.closeAll()
        }
        device = SlussenDatabases.openDevice(DeviceFiles.context)
    }
}
