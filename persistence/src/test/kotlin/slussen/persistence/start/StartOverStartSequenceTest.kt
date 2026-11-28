package slussen.persistence.start

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.MutableClock
import java.io.File

/**
 * Start Over at the start sequence (ADR-002 §3 O-17 steps 2, 4 and 5), on plain files, with a check that reads the first
 * bytes of a file in place of SQLite's: what is deleted, in which order, and what the next start finds and does after a
 * start that ended at each deletion. The instrumented `UC010StartOverPersistenceIT` runs it with Room and the Keystore.
 */
@DisplayName("UC-010: Adjust Settings, A12 Start Over at the start sequence")
class StartOverStartSequenceTest {
    @TempDir
    lateinit var root: File

    private val databases by lazy { File(root, "databases").apply { mkdirs() } }
    private val noBackup by lazy { File(root, "no_backup").apply { mkdirs() } }
    private val files by lazy { StartFiles(databases, noBackup) }
    private val readable = DatabaseCheck { !it.readText().startsWith("GARBAGE") }
    private var keyDeletions = 0

    /** The library's own database, which Start Over leaves to WorkManager (ADR-002 §1). */
    private val workManagerDatabase by lazy { File(noBackup, "androidx.work.workdb") }

    private fun sequence(delete: (File) -> Boolean = File::delete, deleteKey: () -> Unit = { keyDeletions++ }) =
        StartSequence(files, readable, readable, readable, MutableClock(), deleteFile = delete, deleteCredentialKey = deleteKey)

    /** Everything Slussen stores on an installation in use, with an original of each database set aside, and the library's own file. */
    private fun storedData() {
        files.main.writeText("old transactions and Sent Records")
        files.mainJournal.writeText("old rollback journal")
        File(databases, "slussen.db-wal").writeText("old log")
        File(databases, "slussen.db.unreadable-1-aa").writeText("GARBAGE old original")
        File(databases, "slussen.db-journal.unreadable-1-aa").writeText("old journal of the original")
        files.device.writeText("old credentials and Bank Consents")
        files.deviceJournal.writeText("old device journal")
        File(noBackup, "device.db.unreadable-2-bb").writeText("GARBAGE old device original")
        files.journal.writeText("old Decision Journal")
        File(noBackup, "journal.db-shm").writeText("old index")
        File(noBackup, "journal.db.unreadable-3-cc").writeText("GARBAGE old journal original")
        files.installationId.writeText("old-installation")
        files.lastGood.writeText("old last good copy")
        files.lastGoodTemporary.writeText("old partial copy")
        workManagerDatabase.writeText("the library's records")
    }

    private fun slussenFilesLeft(): List<String> =
        (databases.listFiles()!! + noBackup.listFiles()!!).map { it.name }
            .filter { it != workManagerDatabase.name && it != files.intent.name && it != files.installationId.name }
            .sorted()

    private fun recordStartOver() = runBlocking { FileStartOverStore(files).recordStartOver() }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 2: recording Start Over writes the start intent of a new installation that carries Start Over, and changes nothing else")
    fun recordingWritesTheIntentOnly() {
        storedData()
        val before = slussenFilesLeft()

        recordStartOver()

        assertEquals(listOf(FirstUploadMatchingCause.NewInstallation.text, "Start Over"), files.intent.readLines())
        assertFalse(files.intentTemporary.exists())
        assertEquals(before, slussenFilesLeft())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 steps 4-5, UC-006 BR-017: the next start deletes every file of the old data before any database is opened, the Keystore key with them, and starts as a new installation with a First Upload Matching")
    fun theNextStartDeletesEverythingAndIsANewInstallation() {
        storedData()
        recordStartOver()

        val outcome = sequence().run()

        assertEquals(StartOutcome(StartKind.NewInstallation, FirstUploadMatchingCause.NewInstallation, emptyList(), startedOver = true), outcome)
        assertEquals(emptyList<String>(), slussenFilesLeft(), "no file of the old data is left, the last good copy and the set-aside originals among them")
        assertNotEquals("old-installation", files.installationId.readText(), "a new installation marker")
        assertEquals(1, keyDeletions)
        assertEquals("the library's records", workManagerDatabase.readText(), "WorkManager's own database is the library's")
        assertTrue(files.intent.exists(), "the intent stays until the First Upload Matching is committed (ADR-002 §6 step 6)")

        sequence().finish()
        files.main.writeText("the new installation's data")
        assertEquals(StartOutcome(StartKind.Normal, null, emptyList()), sequence().run())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 4: a start that ends at any deletion keeps the intent, so the next start deletes again from the beginning and is a new installation, never a normal start on part of the old data")
    fun aStartEndedAtEachDeletionIsFinishedByTheNext() {
        storedData()
        recordStartOver()
        var deletions = 0
        sequence(delete = { deletions++; it.delete() }).run()
        assertTrue(deletions >= 12, "every file of the old data is one deletion: $deletions")

        for (failAt in 0 until deletions) {
            root.listFiles()!!.forEach { it.deleteRecursively() }
            databases.mkdirs(); noBackup.mkdirs()
            storedData()
            recordStartOver()
            var count = 0
            val ended = assertThrows(IllegalStateException::class.java) {
                sequence(delete = { file -> if (count++ == failAt) error("invented: the process ended") else file.delete() }).run()
            }
            assertTrue("ended" in ended.message.orEmpty())
            assertEquals(listOf(FirstUploadMatchingCause.NewInstallation.text, "Start Over"), files.intent.readLines(), "after deletion $failAt")

            val next = sequence().run()

            assertEquals(StartKind.NewInstallation, next.kind, "after deletion $failAt")
            assertEquals(FirstUploadMatchingCause.NewInstallation, next.firstUploadMatchingCause)
            assertTrue(next.startedOver)
            assertEquals(emptyList<String>(), slussenFilesLeft(), "after deletion $failAt")
        }
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 4: a file that is still there after its deletion stops the start with the intent kept; no database is opened over part of the old data")
    fun aFileThatStaysStopsTheStart() {
        storedData()
        recordStartOver()

        assertThrows(IllegalStateException::class.java) {
            sequence(delete = { file -> if (file.name == "device.db") false else file.delete() }).run()
        }

        assertFalse(files.lastGood.exists(), "the last good copy goes first, so that no start can bring the old data back")
        assertFalse(files.main.exists())
        assertTrue(files.device.exists())
        assertTrue(files.intent.exists())
        assertEquals(StartKind.NewInstallation, sequence().run().kind)
        assertFalse(files.device.exists())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 4: a Keystore key that is not deleted does not stop the start: with device.db gone it encrypts nothing")
    fun aKeyNotDeletedDoesNotStopTheStart() {
        storedData()
        recordStartOver()

        val outcome = sequence(deleteKey = { error("invented: the Keystore refused") }).run()

        assertTrue(outcome.startedOver)
        assertEquals(emptyList<String>(), slussenFilesLeft())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 5: a start that ended after the deletion, with the new databases already created, deletes again and is still a new installation")
    fun aStartEndedAfterTheDeletionIsFinishedByTheNext() {
        storedData()
        recordStartOver()
        sequence().run()
        files.main.writeText("created by Room before the process ended")
        files.device.writeText("created by Room")

        val next = sequence().run()

        assertTrue(next.startedOver)
        assertEquals(FirstUploadMatchingCause.NewInstallation, next.firstUploadMatchingCause)
        assertFalse(files.main.exists())
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("ADR-002 §6: an ordinary new installation's intent, without Start Over, deletes nothing at its resumed start")
    fun anOrdinaryIntentDeletesNothing() {
        files.device.writeText("credentials kept while slussen.db was cleared")
        files.installationId.writeText("marker")
        sequence().run()

        val resumed = sequence().run()

        assertFalse(resumed.startedOver)
        assertEquals("credentials kept while slussen.db was cleared", files.device.readText())
        assertEquals(0, keyDeletions)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 2: when the intent cannot be written, recording fails and nothing is deleted")
    fun anIntentThatCannotBeWrittenRecordsNothing() {
        storedData()
        val before = slussenFilesLeft()
        files.intentTemporary.mkdirs()
        File(files.intentTemporary, "blocker").writeText("x")

        assertThrows(Exception::class.java) { recordStartOver() }

        assertFalse(files.intent.exists())
        assertEquals(before, slussenFilesLeft().filter { it != files.intentTemporary.name })
    }
}
