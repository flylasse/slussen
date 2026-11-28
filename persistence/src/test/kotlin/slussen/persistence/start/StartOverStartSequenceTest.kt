package slussen.persistence.start

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slussen.core.consent.ConsentNotEnded
import slussen.core.consent.NotEndedBecause
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartOverRecord
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

    /** Each directory flush, with whether the intent existed at that moment (ADR-002 §6 steps 3 and 6). */
    private val flushes = mutableListOf<Pair<File, Boolean>>()
    private val flush: (File) -> Unit = { flushes += it to files.intent.exists() }

    private fun sequence(delete: (File) -> Boolean = File::delete, deleteKey: () -> Unit = { keyDeletions++ }) =
        StartSequence(files, readable, readable, readable, MutableClock(), deleteFile = delete, deleteCredentialKey = deleteKey, flushDirectory = flush)

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

    private val provider = "Kestrel Open Banking"
    private val notEnded = listOf(
        ConsentNotEnded("Nordvik Bank (SE)", provider, NotEndedBecause.NotConfirmed),
        ConsentNotEnded("Aurora Sparbank (FI)", provider, NotEndedBecause.CredentialsMissing),
    )

    private fun recordStartOver(banks: List<ConsentNotEnded> = emptyList()) =
        runBlocking { FileStartOverStore(files, flush).recordStartOver(banks) }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 2: recording Start Over writes the start intent of a new installation that carries Start Over, and changes nothing else")
    fun recordingWritesTheIntentOnly() {
        storedData()
        val before = slussenFilesLeft()

        recordStartOver()

        assertEquals(listOf(FirstUploadMatchingCause.NewInstallation.text, "Start Over", "0"), files.intent.readLines())
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

        assertEquals(StartOutcome(StartKind.NewInstallation, FirstUploadMatchingCause.NewInstallation, emptyList(), StartOverRecord(emptyList(), complete = true)), outcome)
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
            assertEquals(listOf(FirstUploadMatchingCause.NewInstallation.text, "Start Over", "0"), files.intent.readLines(), "after deletion $failAt")

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

        assertThrows(StartNotFinished::class.java) {
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

    // ---- Review A-11 items 1, 3, 6 -----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 steps 2, 6: the Banks are written with the record in the same write, read by the start that deletes, kept by a start that ended before it finished, and shown by no later start")
    fun theBanksTravelInTheStartIntent() {
        storedData()
        recordStartOver(notEnded)
        assertEquals("2", files.intent.readLines()[2])

        val interrupted = sequence().run()
        assertEquals(StartOverRecord(notEnded, complete = true), interrupted.startOver)
        val resumed = sequence().run()
        assertEquals(StartOverRecord(notEnded, complete = true), resumed.startOver, "the names survive a start that did not finish by itself")

        sequence().finish()
        files.main.writeText("the new installation's data")
        val later = sequence().run()
        assertNull(later.startOver)
        assertFalse(later.startedOver)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 2: a name longer than its stored length, or one that cannot be written as one entry, is left out and counted, never shortened or guessed; the record then says the list is not complete")
    fun aNameThatDoesNotFitIsLeftOutAndCounted() {
        recordStartOver(notEnded + ConsentNotEnded("B".repeat(101), provider, NotEndedBecause.NotConfirmed) + ConsentNotEnded("Two\nLines", provider, NotEndedBecause.NotConfirmed))

        val outcome = sequence().run()

        assertEquals(StartOverRecord(notEnded, complete = false), outcome.startOver)
        assertTrue(files.intent.readText().length < 1_000)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 6: a record whose list is damaged still deletes everything; its list is not known")
    fun aDamagedListStillDeletes() {
        storedData()
        files.intent.writeText("New Installation\nStart Over\nGARBAGE\nNotConfirmed\tx\ty\n")

        val outcome = sequence().run()

        assertTrue(outcome.startedOver)
        assertEquals(StartOverRecord(null, complete = false), outcome.startOver)
        assertEquals(emptyList<String>(), slussenFilesLeft())

        files.intent.writeText("New Installation\nStart Over\n1\nUnknownReason\tx\ty\n")
        assertEquals(StartOverRecord(emptyList(), complete = false), sequence().run().startOver, "an entry that does not fit the form is left out")
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 6: an intent whose first line is garbage deletes nothing, even with the Start Over line after it")
    fun garbageBeforeTheStartOverLineDeletesNothing() {
        storedData()
        val before = slussenFilesLeft().filter { it != files.lastGood.name && it != files.lastGoodTemporary.name }
        files.intent.writeText("GARBAGE\nStart Over\n0\n")

        val outcome = sequence().run()

        assertFalse(outcome.startedOver)
        assertNull(outcome.startOver)
        assertEquals(0, keyDeletions)
        assertTrue(files.device.exists())
        assertTrue(files.journal.exists())
        assertTrue(before.all { name -> slussenFilesLeft().contains(name) || name.startsWith("slussen.db") })
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 6: an intent of the first line alone is an ordinary new installation's: nothing of Start Over, nothing deleted")
    fun theFirstLineAloneIsAnOrdinaryNewInstallation() {
        storedData()
        files.intent.writeText("New Installation\n")

        val outcome = sequence().run()

        assertEquals(FirstUploadMatchingCause.NewInstallation, outcome.firstUploadMatchingCause)
        assertFalse(outcome.startedOver)
        assertEquals("old credentials and Bank Consents", files.device.readText())
        assertEquals(0, keyDeletions)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("ADR-002 §6 steps 3, 6: the directory is flushed after the intent is renamed into place and after it is removed")
    fun theDirectoryIsFlushedAfterTheRenameAndTheRemoval() {
        recordStartOver(notEnded)
        assertEquals(listOf(noBackup to true), flushes, "after the rename of the record of Start Over")
        flushes.clear()

        sequence().run()
        sequence().finish()

        assertTrue(flushes.contains(noBackup to false), "after the removal: $flushes")
        flushes.clear()
        files.main.delete(); files.installationId.delete()
        sequence().run()
        assertTrue(flushes.contains(noBackup to true), "after the rename of an ordinary new installation's intent: $flushes")
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 5: an intent that cannot be removed at the end of the start stops it, as a file that cannot be deleted does; it never stays behind an installation that goes on")
    fun anIntentThatCannotBeRemovedStopsTheStart() {
        recordStartOver(notEnded)
        sequence().run()

        assertThrows(StartNotFinished::class.java) { sequence(delete = { false }).finish() }

        assertTrue(files.intent.exists())
    }
}
