package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.pluginapi.testfixtures.UseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Decision Journal's own database (`journal.db`, ADR-002 §9) on a device or emulator: its size as
 * `page_count × page_size`, the oldest entries removed until it is within a size limit and their space returned, a
 * clearing with its first entry, and that none of it reaches or waits on `slussen.db`. Invented entries only (C-005).
 */
@DisplayName("UC-010: Adjust Settings, the Decision Journal's database on the device")
class UC010DecisionJournalPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** About 4 KB of details, the most an entry holds (`DECISION_JOURNAL_ENTRY.details`, 4000), with no payee or memo. */
    private fun entry(number: Int) = JournalEntry(now, JournalEventKind.SessionStart, "Session $number started ".padEnd(4_000, '.'))

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    /** [count] entries handed to the recorder, removing nothing after them; the size read after waits for every one. */
    private suspend fun SlussenStorage.filled(count: Int): Long {
        repeat(count) { journalRecorder.record(entry(it), keepWithinBytes = null) }
        return journalControl.sizeBytes()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("BR-003: right after a lower size limit the oldest entries are removed and their space returned until journal.db is within it; the newest stay")
    fun theOldestEntriesAreRemovedUntilWithinTheLimit() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val full = storage.filled(1_000)
        assertTrue(full > 3_000_000, "size: $full")
        val newest = checkNotNull(storage.journal.entries().newestId())

        val trimmed = storage.journalControl.keepWithin(1_000_000).await()
        val removed = trimmed.removed

        assertTrue(trimmed.complete)
        assertTrue(removed > 0)
        assertTrue(storage.journalControl.sizeBytes() <= 1_000_000)
        assertEquals(1_000 - removed, storage.journal.entries().count())
        assertEquals(newest, storage.journal.entries().newestId())
        assertEquals(newest - storage.journal.entries().count() + 1, storage.journal.entries().oldestId())
        storage.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: an entry handed over with a limit is written and followed by the oldest removed until within; one handed over without removes none")
    fun aWriteIsFollowedByTheTrimmingItWasHandedOverWith() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val full = storage.filled(500)
        val limit = full / 2

        storage.journalRecorder.record(entry(500), keepWithinBytes = null)
        assertTrue(storage.journalControl.sizeBytes() > limit)
        assertEquals(501, storage.journal.entries().count())
        storage.journalRecorder.record(entry(501), keepWithinBytes = limit)

        assertTrue(storage.journalControl.sizeBytes() <= limit)
        assertTrue(storage.journal.entries().count() < 502)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("A3 step 3: clearing removes every entry and returns their space; its first entry follows, with an id above every id given before")
    fun clearingFreesTheSpaceAndKeepsIdsGrowing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val full = storage.filled(500)
        val newestBefore = checkNotNull(storage.journal.entries().newestId())

        storage.journalControl.clear(JournalEntry(now, JournalEventKind.JournalControl, "Decision Journal cleared")).await()

        assertTrue(storage.journalControl.sizeBytes() < full / 10, "size after: ${storage.journalControl.sizeBytes()} of $full")
        assertEquals(1, storage.journal.entries().count())
        assertTrue(checkNotNull(storage.journal.entries().oldestId()) > newestBefore)
        storage.close()
    }

    /** Makes every insert or every delete of `journal.db` fail, as a database that fails would, with SQLite's own error. */
    private fun SlussenStorage.failing(statement: String) {
        journal.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER failing_$statement BEFORE $statement ON decision_journal_entry BEGIN SELECT RAISE(ABORT, 'invented failure'); END",
        )
    }

    private fun SlussenStorage.notFailing(statement: String) {
        journal.openHelper.writableDatabase.execSQL("DROP TRIGGER failing_$statement")
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-004: while journal.db refuses every write, a write throws nothing, an Operator Setting is still stored in slussen.db, the entry is counted as dropped and the journal holds exactly what it held before")
    fun aWriteTheDatabaseRefusesIsDroppedAndCounted() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        storage.filled(3)
        storage.failing("INSERT")

        storage.journalRecorder.record(entry(3), keepWithinBytes = 1_000_000)
        storage.settings.store(SettingChange(SettingValue.JournalSizeLimit(20), SettingFollowUp.None))
        storage.journalControl.sizeBytes()

        assertEquals(20, storage.settings.current().journalSizeLimitMb)
        assertEquals(1, storage.journalDatabase.droppedEntries)
        assertEquals(3, storage.journal.entries().count())
        storage.notFailing("INSERT")
        storage.journalRecorder.record(entry(4), keepWithinBytes = null)
        storage.journalControl.sizeBytes()
        assertEquals(4, storage.journal.entries().count())
        storage.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-003, BR-004: while journal.db refuses every removal, an entry handed over with a limit it is above is written, the trimming not done is counted, and no entry is removed")
    fun aTrimmingTheDatabaseRefusesKeepsTheEntry() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val full = storage.filled(100)
        storage.failing("DELETE")

        storage.journalRecorder.record(entry(100), keepWithinBytes = full / 2)
        storage.journalControl.sizeBytes()

        assertEquals(101, storage.journal.entries().count())
        assertEquals(1, storage.journalDatabase.trimmingsNotDone)
        assertEquals(0, storage.journalDatabase.droppedEntries)
        val trimmed = storage.journalControl.keepWithin(full / 2).await()
        assertEquals(slussen.core.port.Trimmed(removed = 0, complete = false), trimmed)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"], requirements = ["NFR-006"])
    @DisplayName("ADR-002 §1, §9: a journal.db an earlier build kept in write-ahead logging opens in TRUNCATE with its entries, no -wal file is left, and its size still falls when entries are removed")
    fun aJournalInWriteAheadLoggingOpensWithItsEntries() = runBlocking {
        val path = SlussenDatabases.journalFile(DeviceFiles.context).path
        val earlier = androidx.room.Room.databaseBuilder(DeviceFiles.context, slussen.persistence.journal.JournalDatabase::class.java, path)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        val earlierRecorder = RoomDecisionJournal(slussen.persistence.journal.RoomJournalFile(earlier))
        repeat(300) { earlierRecorder.record(entry(it), keepWithinBytes = null) }
        earlierRecorder.sizeBytes()
        earlier.close()

        val storage = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(300, storage.journal.entries().count())
        assertEquals(false, java.io.File("$path-wal").exists())
        val mode = storage.journal.openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst(); it.getString(0) }
        assertEquals("truncate", mode.lowercase())
        val before = storage.journalControl.sizeBytes()
        assertTrue(storage.journalControl.keepWithin(before / 2).await().complete)
        assertTrue(storage.journalControl.sizeBytes() <= before / 2)
        storage.close()
    }
}
