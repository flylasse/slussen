package slussen.persistence.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.journal.JournalExport
import slussen.core.journal.JournalExportOutcome
import slussen.core.port.JournalBounds
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.JournalExportDocument
import slussen.core.port.StoredJournalEntry
import slussen.persistence.RoomDecisionJournal
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.TestSlice
import java.io.StringWriter
import java.io.Writer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The real Decision Journal of `journal.db` read for the Journal Export ([RoomDecisionJournal], ADR-002 §9, UC-011
 * BR-007) on the JVM, on its own thread, with its database calls behind a [JournalFile]: the bounds are noted after every
 * entry handed over before them, and a page is one task, so that a clearing handed over while an export is under way runs
 * at once and is not held for the length of the export. The Room queries are proven on a device
 * (`UC011ExportTheDecisionJournalPersistenceIT`).
 */
@DisplayName("UC-011: Export the Decision Journal, its reader on the journal's own thread")
class UC011DecisionJournalReaderTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val quickly: Duration = Duration.ofSeconds(5)

    private fun entry(number: Int) = JournalEntry(now, JournalEventKind.SessionStart, "Session $number started")

    /** `journal.db` as a list of entries with ids that only grow; [insert] can be held. */
    private class IdFile : JournalFile {
        val entries = mutableListOf<StoredJournalEntry>()
        private var nextId = 1L
        var insert: (JournalEntry) -> Unit = { entries += StoredJournalEntry(nextId++, it) }
        val pages = mutableListOf<Int>()

        override fun insert(entry: JournalEntry) = insert.invoke(entry)

        fun add(entry: JournalEntry) {
            entries += StoredJournalEntry(nextId++, entry)
        }

        override fun entryCount(): Int = entries.size

        override fun pageBytes(): Long = 1_000

        override fun sizeBytes(): Long = entries.size * 1_000L

        override fun removeOldest(count: Int) = repeat(count) { entries.removeAt(0) }

        override fun clear(firstEntry: JournalEntry?) {
            entries.clear()
            if (firstEntry != null) add(firstEntry)
        }

        override fun bounds(): JournalBounds? =
            entries.firstOrNull()?.let { JournalBounds(it.id, it.entry.recordedAt, entries.last().id, entries.size) }

        override fun entriesAfter(afterId: Long, upToId: Long, limit: Int): List<StoredJournalEntry> {
            pages += limit
            return entries.filter { it.id > afterId && it.id <= upToId }.take(limit)
        }

        override fun holdsAnEntry(): Boolean = entries.isNotEmpty()
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-006"])
    @DisplayName("Step 3, BR-007: the bounds are read on the journal's own thread after every entry handed over before them, so an entry queued and not written yet is inside the bound")
    fun theBoundsFollowEveryEntryHandedOverBefore() {
        val letGo = CountDownLatch(1)
        val file = IdFile()
        file.add(entry(0))
        file.insert = { entry ->
            letGo.await(30, TimeUnit.SECONDS)
            file.add(entry)
        }
        val journal = RoomDecisionJournal(file)
        journal.record(entry(1), keepWithinBytes = null)

        val bounds = runBlocking {
            val noted = async(Dispatchers.Default) { journal.bounds() }
            Thread.sleep(100)
            assertTrue(noted.isActive, "the bounds wait for the entry handed over before them")
            letGo.countDown()
            noted.await()
        }

        assertEquals(JournalBounds(1, now, 2, 2), bounds)
        assertEquals(listOf(file.entries[1]), runBlocking { journal.entriesAfter(1, 2, 10) })
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-006"])
    @DisplayName("BR-007 (S-B-28), UC-010 BR-004: a clearing handed over while an export is under way runs at once, between two pages; the export completes with the entries written before it and counts the rest as removed while the file was written")
    fun aClearingIsNotHeldByAnExport() {
        val file = IdFile()
        repeat(1_200) { file.add(entry(it)) }
        val journal = RoomDecisionJournal(file)
        val slice = TestSlice(recorder = journal)
        val export = JournalExport(
            slice.plugins, slice.credentials, slice.connectionStore, slice.copyStore, slice.settings, slice.coordinator,
            reader = journal, clock = slice.clock, versionName = "9.8.7", io = EmptyCoroutineContext,
        )
        val firstPageWritten = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val document = object : JournalExportDocument {
            var removed = false

            override fun open(): Writer = object : StringWriter() {
                override fun flush() {
                    super.flush()
                    // The export flushes each page before it reads the next: it waits here, between two pages.
                    if (firstPageWritten.count > 0) {
                        firstPageWritten.countDown()
                        cleared.await(30, TimeUnit.SECONDS)
                    }
                }
            }

            override fun remove(): Boolean {
                removed = true
                return true
            }
        }

        val outcome = runBlocking {
            val running = async(Dispatchers.Default) { export.export(codes = false, document = document) }
            assertTrue(firstPageWritten.await(5, TimeUnit.SECONDS))
            assertTimeoutPreemptively(quickly) { runBlocking { journal.clear(firstEntry = null).await() } }
            cleared.countDown()
            running.await()
        }

        val complete = outcome as JournalExportOutcome.Complete
        assertEquals(JournalExport.PAGE_SIZE, complete.entries)
        assertEquals(1_200 - JournalExport.PAGE_SIZE, complete.removedWhileWritten)
        assertTrue(!document.removed)
        assertTrue(file.entries.isEmpty())
        assertEquals(listOf(JournalExport.PAGE_SIZE, JournalExport.PAGE_SIZE), file.pages)
    }
}
