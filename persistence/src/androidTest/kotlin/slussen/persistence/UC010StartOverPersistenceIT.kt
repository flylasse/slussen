package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.GrantedBankConsent
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.NewConsentRequest
import slussen.core.port.NumberSetting
import slussen.core.port.OperatorSettings
import slussen.core.port.ServiceKey
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.core.port.StartKind
import slussen.persistence.credential.KeystoreKey
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import java.io.File
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Start Over (UC-010 A12, ADR-002 §3 O-17) with Room, the platform's files and the Keystore, on a device or emulator:
 * after Start Over each database holds what a new installation holds, and no file of the old data is left.
 */
@DisplayName("UC-010: Adjust Settings, A12 Start Over, on the device")
class UC010StartOverPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-11-28T10:00:00Z"), ZoneOffset.UTC)
    private val at: Instant = clock.instant()

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    private val noBackup: File get() = DeviceFiles.context.noBackupFilesDir
    private val databases: File get() = SlussenDatabases.mainFile(DeviceFiles.context).parentFile!!

    private fun keyExists(): Boolean = KeyStore.getInstance(KeystoreKey.PROVIDER).apply { load(null) }.containsAlias(KeystoreKey.ALIAS)

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", businessRules = ["BR-001", "BR-007"])
    @DisplayName("A12, O-17: after Start Over each database holds what a new installation holds, a First Upload Matching is open, and no file of the old data is left, the last good copy, the set-aside originals and the Keystore key among them")
    fun afterStartOverEverythingIsAsNewlyInstalled() = runBlocking {
        val used = SlussenStorage.open(DeviceFiles.context, clock)
        used.credentialVault.replace(ServiceKey.budgetApp, Secret("invented-budget-app-token"), at)
        used.connections.bankNamed("Kestrel Bank")
        val request = used.consents.startRequest(NewConsentRequest("Aurora Sparbank (FI)", 2, at, at.plusSeconds(900), "b".repeat(64), null))
        used.consents.consumeAnswer("b".repeat(64), at)
        used.consents.replaceConsent(request.id, GrantedBankConsent(used.connections.banks().single().id, at, at.plusSeconds(86_400 * 90L), ConsentSession("invented-session"), at))
        used.settings.store(SettingChange(SettingValue.Number(NumberSetting.UploadWindow, 14), SettingFollowUp.None))
        used.journalRecorder.record(JournalEntry(at, JournalEventKind.SessionStart, "invented old entry"), keepWithinBytes = null)
        used.journalControl.sizeBytes()
        used.close()
        // A second start makes the last good copy (ADR-002 §8 step 2); originals set aside earlier are planted beside the databases.
        val again = SlussenStorage.open(DeviceFiles.context, clock)
        assertTrue(File(noBackup, "slussen.lastgood.db").exists())
        assertTrue(keyExists())
        File(databases, "slussen.db.unreadable-1-aa").writeText("invented unreadable original")
        File(noBackup, "device.db.unreadable-2-bb").writeText("invented unreadable original")
        File(noBackup, "journal.db.unreadable-3-cc").writeText("invented unreadable original")
        val oldInstallation = File(noBackup, "installation.id").readText()

        val notEnded = listOf(slussen.core.consent.ConsentNotEnded("Aurora Sparbank (FI)", "Kestrel Open Banking", slussen.core.consent.NotEndedBecause.NotConfirmed))
        again.startOver.recordStartOver(notEnded)
        again.close()
        val started = SlussenStorage.open(DeviceFiles.context, clock)

        try {
            assertTrue(started.startState.startedOver)
            assertEquals(slussen.core.port.StartOverRecord(notEnded, complete = true), started.startState.startOver)
            assertEquals(StartKind.NewInstallation, started.startState.startKind)
            assertEquals(listOf(FirstUploadMatchingCause.NewInstallation), started.firstUploadMatching.openChecks().map { it.cause })
            assertEquals(OperatorSettings.DEFAULT, started.settings.current())
            assertTrue(started.settings.transferDescriptions().isNotEmpty(), "the default description lists of a new installation (UC-010 BR-007)")
            assertEquals(emptyList<Any>(), started.connections.banks())
            assertEquals(emptyList<Any>(), started.connections.bankAccounts())
            assertEquals(emptyList<Any>(), started.consents.bankConsents())
            assertEquals(emptyList<Any>(), started.consents.consentRequests())
            assertNull(started.credentialVault.read(ServiceKey.budgetApp))
            assertEquals(0, started.journal.entries().count())
            assertFalse(keyExists(), "the Keystore key of the old credentials is deleted")
            val left = DeviceFiles.everyFileUnder(databases).map { it.name } + DeviceFiles.everyFileUnder(noBackup).map { it.name }
            assertTrue(left.none { "unreadable" in it }, "$left")
            assertFalse(File(noBackup, "slussen.lastgood.db").exists())
            assertFalse(File(noBackup, "slussen.start-intent").exists())
            assertNotEquals(oldInstallation, File(noBackup, "installation.id").readText())
        } finally {
            started.close()
        }
        val next = SlussenStorage.open(DeviceFiles.context, clock)
        assertFalse(next.startState.startedOver)
        assertNull(next.startState.startOver, "a later start shows nothing of the Start Over")
        assertEquals(StartKind.Normal, next.startState.startKind)
        next.close()
    }
}
