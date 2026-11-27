package slussen.persistence.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.testfixtures.UseCase

/**
 * The trimming of `journal.db` (ADR-002 §9, UC-010 BR-003) on the JVM, against a model of the file: a size of whole pages
 * (`page_count × page_size`) that holds a fixed overhead and the entries packed into pages, which shrinks only when an
 * emptied page is returned, as `PRAGMA incremental_vacuum` returns it. The Room calls themselves are proven on a device
 * (`UC010DecisionJournalPersistenceIT`).
 */
@DisplayName("UC-010: Adjust Settings, trimming the Decision Journal's database")
class UC010JournalTrimmingTest {
    /** `journal.db` as pages: [overheadPages] for the schema, then the entries of [entryBytes] each, oldest first by id. */
    private class PagedJournal(entries: Int, private val entryBytes: Long, private val overheadPages: Long = 2, val pageBytes: Long = 4_096) {
        val ids = ArrayDeque((1L..entries).toList())
        val batches = mutableListOf<Int>()

        fun size(): Long = (overheadPages + (ids.size * entryBytes + pageBytes - 1) / pageBytes) * pageBytes

        fun removeOldest(count: Int) {
            batches += count
            repeat(count) { ids.removeFirst() }
        }
    }

    private fun PagedJournal.keepWithin(limitBytes: Long): Int =
        JournalTrimming.keepWithin(limitBytes, pageBytes, size = ::size, entries = { ids.size }, removeOldest = ::removeOldest)

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: within its size limit nothing is removed, and at exactly the limit neither")
    fun withinTheLimitNothingIsRemoved() {
        val journal = PagedJournal(entries = 1_000, entryBytes = 512)
        val size = journal.size()

        assertEquals(0, journal.keepWithin(size))
        assertEquals(0, journal.keepWithin(size + 1))

        assertEquals(1_000, journal.ids.size)
        assertTrue(journal.batches.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("BR-003: above the limit the oldest entries (lowest id) are removed until the size is within it, and not one entry more than that needs")
    fun theOldestAreRemovedUntilWithinAndNoMore() {
        val journal = PagedJournal(entries = 10_000, entryBytes = 512)
        val limit = 1_000_000L

        val removed = journal.keepWithin(limit)

        assertTrue(journal.size() <= limit)
        assertEquals(10_000 - removed, journal.ids.size)
        assertEquals((removed + 1L..10_000L).toList(), journal.ids.toList())
        // One entry fewer removed and the file would still be above the limit.
        val oneMore = PagedJournal(entries = journal.ids.size + 1, entryBytes = 512)
        assertTrue(oneMore.size() > limit)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("ADR-002 §9: a large lowering, 500 MB of entries to a limit of 10 MB, takes few steps of the journal's database, not one per entry")
    fun aLargeLoweringTakesFewSteps() {
        val journal = PagedJournal(entries = 125_000, entryBytes = 4_000)

        journal.keepWithin(10_000_000)

        assertTrue(journal.size() <= 10_000_000)
        assertTrue(journal.batches.size < 200, "steps: ${journal.batches.size}")
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("BR-003: when even an empty journal.db is above the limit every entry is removed and the trimming ends")
    fun anOverheadAboveTheLimitEmptiesTheJournalAndEnds() {
        val journal = PagedJournal(entries = 50, entryBytes = 512, overheadPages = 10)

        assertEquals(50, journal.keepWithin(4_096))

        assertTrue(journal.ids.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: entries that share a page are removed until a page is returned, since only a returned page makes the size smaller")
    fun entriesSharingAPageAreRemovedUntilAPageIsReturned() {
        val journal = PagedJournal(entries = 80, entryBytes = 512)
        val limit = journal.size() - 1

        journal.keepWithin(limit)

        assertTrue(journal.size() <= limit)
        assertEquals(72, journal.ids.size)
    }
}
