package slussen.persistence.start

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartKind
import slussen.core.port.StoredPart
import slussen.core.port.UnreadablePart
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.MutableClock
import java.io.File

/**
 * The start sequence of ADR-002 §6 and the last good copy of ADR-002 §8 on plain files, with a check that reads the first
 * bytes of a file in place of SQLite's (the instrumented `UC008CheckImportStatusPersistenceIT` runs the real check).
 */
class StartSequenceTest {
    @TempDir
    lateinit var root: File

    private val databases by lazy { File(root, "databases").apply { mkdirs() } }
    private val noBackup by lazy { File(root, "no_backup").apply { mkdirs() } }
    private val files by lazy { StartFiles(databases, noBackup) }
    private val readable = DatabaseCheck { !it.readText().startsWith("GARBAGE") }

    private fun sequence() = StartSequence(files, readable, readable, readable, MutableClock())

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("A new installation opens a First Upload Matching with cause New Installation (E-08); the next start is normal")
    fun newInstallationOpensAFirstUploadMatching() {
        val first = sequence().run()

        assertEquals(StartOutcome(StartKind.NewInstallation, FirstUploadMatchingCause.NewInstallation, emptyList()), first)
        assertTrue(files.installationId.exists())
        assertTrue(files.intent.exists())
        sequence().finish()
        files.main.writeText("stored transactions")

        assertEquals(StartOutcome(StartKind.Normal, null, emptyList()), sequence().run())
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("Data restored from a backup, which never restores the installation marker, opens a First Upload Matching with cause Restored Backup")
    fun restoredBackupOpensAFirstUploadMatching() {
        files.main.writeText("restored transactions")

        assertEquals(FirstUploadMatchingCause.RestoredBackup, sequence().run().firstUploadMatchingCause)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", requirements = ["NFR-005"])
    @DisplayName("A1: unreadable Decision-Critical Data is kept unchanged with its journal, the last good copy takes its place, and a First Upload Matching is due")
    fun unreadableMainDataStartsFromTheLastGoodCopy() {
        files.main.writeText("good transactions")
        sequence().run()
        sequence().finish()
        assertEquals("good transactions", files.lastGood.readText())
        files.main.writeText("GARBAGE bytes")
        files.mainJournal.writeText("leftover journal")

        val outcome = sequence().run()

        assertEquals(StartKind.LastGoodCopy, outcome.kind)
        assertEquals(FirstUploadMatchingCause.LastGoodCopy, outcome.firstUploadMatchingCause)
        assertEquals(listOf(UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = true)), outcome.unreadableParts)
        assertEquals("good transactions", files.main.readText())
        val keptAside = databases.listFiles()!!.filter { it.name.startsWith("slussen.db.unreadable-") }
        assertEquals(listOf("GARBAGE bytes"), keptAside.map { it.readText() })
        assertEquals(listOf("leftover journal"), databases.listFiles()!!.filter { it.name.startsWith("slussen.db-journal.unreadable-") }.map { it.readText() })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", requirements = ["NFR-005"])
    @DisplayName("A1: without a last good copy, unreadable Decision-Critical Data starts empty and is still kept unchanged")
    fun unreadableMainDataWithoutLastGoodCopyStartsEmpty() {
        files.installationId.writeText("marker")
        files.main.writeText("GARBAGE only")

        val outcome = sequence().run()

        assertEquals(listOf(UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = false)), outcome.unreadableParts)
        assertFalse(files.main.exists())
        assertEquals(1, databases.listFiles()!!.count { it.name.startsWith("slussen.db.unreadable-") })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", requirements = ["NFR-005"])
    @DisplayName("A1: damage to the Decision Journal or to device.db is only named; it opens no First Upload Matching")
    fun damagedJournalOrDeviceDataOpensNoFirstUploadMatching() {
        files.main.writeText("good transactions")
        files.installationId.writeText("marker")
        files.journal.writeText("GARBAGE journal")
        files.device.writeText("GARBAGE device")

        val outcome = sequence().run()

        assertNull(outcome.firstUploadMatchingCause)
        assertEquals(StartKind.Normal, outcome.kind)
        assertEquals(
            listOf(StoredPart.CredentialsConsentsAndOfferedBankAccounts, StoredPart.DecisionJournal),
            outcome.unreadableParts.map { it.part },
        )
        assertFalse(files.journal.exists())
        assertFalse(files.device.exists())
        assertTrue(noBackup.listFiles()!!.any { it.name.startsWith("journal.db.unreadable-") })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("A start interrupted after its intent was recorded finishes the same work at the next start and never looks normal (E-12)")
    fun interruptedStartResumesFromItsIntent() {
        val interrupted = sequence().run()
        assertEquals(FirstUploadMatchingCause.NewInstallation, interrupted.firstUploadMatchingCause)
        files.main.writeText("created by Room before the process died")

        val resumed = sequence().run()

        assertEquals(FirstUploadMatchingCause.NewInstallation, resumed.firstUploadMatchingCause)
        assertTrue(files.intent.exists())
    }
}
