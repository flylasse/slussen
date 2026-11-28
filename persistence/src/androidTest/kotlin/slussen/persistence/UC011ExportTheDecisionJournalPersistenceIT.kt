package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.JournalBounds
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.pluginapi.testfixtures.UseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Decision Journal read for the Journal Export on a device or emulator (`journal.db`, ADR-002 §9, UC-011 BR-007): the
 * bounds once every entry handed over is written, pages of entries by `id`, oldest first, up to the bound, and the oldest
 * entries trimmed or all of them cleared between two pages without waiting for the export. Invented entries only (C-005).
 */
@DisplayName("UC-011: Export the Decision Journal, its reads of journal.db on the device")
class UC011ExportTheDecisionJournalPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun entry(number: Int) =
        JournalEntry(now.plusSeconds(number.toLong()), JournalEventKind.StatusChanged, "Status $number", "SLS1-${"a".repeat(26)}", "Kestrel Checking")

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 3, BR-007: an empty journal.db has no bounds; after entries handed over, the bounds name the oldest entry with its time and the newest, and the pages bring them oldest first up to the bound, with every field as recorded")
    fun theBoundsAndThePages() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        assertNull(storage.journalReader.bounds())
        repeat(25) { storage.journalRecorder.record(entry(it), keepWithinBytes = null) }

        val bounds = checkNotNull(storage.journalReader.bounds())
        storage.journalRecorder.record(entry(25), keepWithinBytes = null)
        val first = storage.journalReader.entriesAfter(0, bounds.newestId, 10)
        val rest = storage.journalReader.entriesAfter(first.last().id, bounds.newestId, 100)

        assertEquals(JournalBounds(bounds.oldestId, now, bounds.oldestId + 24, 25), bounds)
        assertEquals((0 until 10).map { entry(it) }, first.map { it.entry })
        assertEquals((10 until 25).map { entry(it) }, rest.map { it.entry })
        assertEquals(true, storage.journalReader.holdsAnEntryNow())
        storage.close()
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-006"])
    @DisplayName("BR-007, UC-010 BR-004: between two pages a trimming removes the oldest entries and a clearing every entry at once; the next page brings the next entry still held and the ids only grow")
    fun aTrimmingAndAClearingBetweenPages() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        repeat(40) { storage.journalRecorder.record(entry(it), keepWithinBytes = null) }
        val bounds = checkNotNull(storage.journalReader.bounds())
        val first = storage.journalReader.entriesAfter(0, bounds.newestId, 10)

        storage.journal.entries().deleteOldest(15)
        assertEquals(first.last().id + 6, storage.journalReader.entriesAfter(first.last().id, bounds.newestId, 10).first().id)
        storage.journalControl.clear(firstEntry = entry(99)).await()

        assertEquals(emptyList<Any>(), storage.journalReader.entriesAfter(first.last().id, bounds.newestId, 10))
        assertEquals(bounds.newestId + 1, checkNotNull(storage.journalReader.bounds()).oldestId)
        storage.close()
    }
}
