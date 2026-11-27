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

        val removed = storage.journalControl.keepWithin(1_000_000)

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

        storage.journalControl.clear(JournalEntry(now, JournalEventKind.JournalControl, "Decision Journal cleared"))

        assertTrue(storage.journalControl.sizeBytes() < full / 10, "size after: ${storage.journalControl.sizeBytes()} of $full")
        assertEquals(1, storage.journal.entries().count())
        assertTrue(checkNotNull(storage.journal.entries().oldestId()) > newestBefore)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-004: with journal.db closed, as a database that fails, a write never throws and an Operator Setting is still stored in slussen.db")
    fun aFailingJournalDatabaseDoesNotStopAStore() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        storage.journal.close()

        storage.journalRecorder.record(entry(0), keepWithinBytes = 1_000_000)
        storage.settings.store(SettingChange(SettingValue.JournalSizeLimit(20), SettingFollowUp.None))

        assertEquals(20, storage.settings.current().journalSizeLimitMb)
        storage.main.close(); storage.device.close()
    }
}
