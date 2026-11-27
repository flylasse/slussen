package slussen.persistence.journal

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.JournalBounds
import slussen.core.port.JournalEntry
import slussen.core.port.StoredJournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.NumberSetting
import slussen.core.port.Trimmed
import slussen.core.settings.SettingOutcome
import slussen.persistence.RoomDecisionJournal
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real recorder of `journal.db` ([RoomDecisionJournal], ADR-002 §9, UC-010 BR-004, UC-011 BR-002) on the JVM, on
 * its own thread, with its database calls behind a [JournalFile] a test replaces: a write that throws, one that never
 * returns and a trimming that fails never reach or hold the caller, and each is counted. The Room calls themselves are
 * proven on a device (`UC010DecisionJournalPersistenceIT`).
 */
@DisplayName("UC-010: Adjust Settings, the Decision Journal's recorder on its own thread")
class UC010DecisionJournalRecorderTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val quickly: Duration = Duration.ofSeconds(5)

    private fun entry(number: Int) = JournalEntry(now, JournalEventKind.SessionStart, "Session $number started")

    /** `journal.db` as a list of entries of [entryBytes] each, with every call a test can make fail or hold. */
    private class ReplacedFile(private val entryBytes: Long = 1_000) : JournalFile {
        val entries = mutableListOf<JournalEntry>()
        var insert: (JournalEntry) -> Unit = { entries += it }
        var removeOldest: (Int) -> Unit = { count -> repeat(count) { entries.removeAt(0) } }

        override fun insert(entry: JournalEntry) = insert.invoke(entry)

        override fun entryCount(): Int = entries.size

        override fun pageBytes(): Long = entryBytes

        override fun sizeBytes(): Long = entries.size * entryBytes

        override fun removeOldest(count: Int) = removeOldest.invoke(count)

        override fun clear(firstEntry: JournalEntry?) {
            entries.clear()
            if (firstEntry != null) entries += firstEntry
        }

        override fun bounds(): JournalBounds? = null

        override fun entriesFrom(fromId: Long, upToId: Long, limit: Int): List<StoredJournalEntry> = emptyList()
    }

    /** Waits until every task handed to the journal's thread before it is done. */
    private fun RoomDecisionJournal.flushed() = runBlocking { sizeBytes() }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-004: a write the journal's database throws on returns to the caller at once, throws nothing to it, and is counted as dropped")
    fun aWriteThatThrowsIsCountedAndDropped() {
        val file = ReplacedFile().apply { insert = { throw IllegalStateException("The invented journal database cannot be written.") } }
        val recorder = RoomDecisionJournal(file)

        assertTimeoutPreemptively(quickly) { recorder.record(entry(1), keepWithinBytes = 10_000) }
        recorder.flushed()

        assertEquals(1, recorder.droppedEntries)
        assertEquals(0, recorder.trimmingsNotDone)
        assertTrue(file.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-004: while a write of the journal's database never returns, the caller returns at once: an Operator Setting is stored and recorded through the core, and its entry is written when the write is let go")
    fun aWriteThatNeverReturnsHoldsNoCaller() {
        val letGo = CountDownLatch(1)
        val file = ReplacedFile()
        file.insert = { entry ->
            letGo.await(30, TimeUnit.SECONDS)
            file.entries += entry
        }
        val recorder = RoomDecisionJournal(file)
        val slice = TestSlice(recorder = recorder)

        val outcome = assertTimeoutPreemptively<SettingOutcome>(quickly) {
            runBlocking {
                slice.journal.record(JournalEventKind.SessionStart, "Slussen opened")
                slice.generalSettings.change(NumberSetting.DefaultWait, 9)
            }
        }

        assertEquals(SettingOutcome.Stored(9), outcome)
        assertEquals(9, slice.settings.settings.defaultWaitHours)
        assertTrue(file.entries.isEmpty())
        letGo.countDown()
        recorder.flushed()
        assertEquals(listOf("Slussen opened", "Default wait changed from 8 to 9 hours"), file.entries.map { it.details })
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-003, BR-004: a trimming after a write that the journal's database throws on keeps the entry written and is counted; nothing reaches the caller")
    fun aTrimmingThatThrowsKeepsTheEntryAndIsCounted() {
        val file = ReplacedFile().apply {
            repeat(10) { entries += entry(it) }
            removeOldest = { throw IllegalStateException("The invented journal database cannot remove entries.") }
        }
        val recorder = RoomDecisionJournal(file)

        assertTimeoutPreemptively(quickly) { recorder.record(entry(10), keepWithinBytes = 5_000) }
        recorder.flushed()

        assertEquals(11, file.entries.size)
        assertEquals("Session 10 started", file.entries.last().details)
        assertEquals(1, recorder.trimmingsNotDone)
        assertEquals(0, recorder.droppedEntries)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("BR-003: a trimming that fails part-way answers the oldest entries it removed before it failed, and that it is not complete")
    fun aTrimmingThatFailsPartWaySaysWhatItRemoved() {
        val file = ReplacedFile()
        repeat(100) { file.entries += entry(it) }
        var steps = 0
        file.removeOldest = { count ->
            if (++steps > 1) throw IllegalStateException("The invented journal database fails part-way.")
            repeat(count) { file.entries.removeAt(0) }
        }
        val recorder = RoomDecisionJournal(file)

        val trimmed = runBlocking { recorder.keepWithin(10_000).await() }

        assertEquals(Trimmed(removed = 100 - file.entries.size, complete = false), trimmed)
        assertTrue(trimmed.removed > 0)
        assertEquals("Session ${trimmed.removed} started", file.entries.first().details)
    }
}
