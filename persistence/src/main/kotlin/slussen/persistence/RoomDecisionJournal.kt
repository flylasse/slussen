package slussen.persistence

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slussen.core.journal.JournalQueue
import slussen.core.port.DecisionJournalControl
import slussen.core.port.DecisionJournalReader
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalBounds
import slussen.core.port.JournalEntry
import slussen.core.port.StoredJournalEntry
import slussen.core.port.Trimmed
import slussen.persistence.journal.JournalFile
import slussen.persistence.journal.JournalTrimming
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * `journal.db` for the core (ADR-002 §9): the recorder, the Decision Journal's own control and the reader of the Journal
 * Export. Everything runs on one thread of its own ([executor]), one task after the other in the order handed over, never
 * inside a transaction of `slussen.db`: a write, the trimming after it, the trimming right after a lower size limit, a
 * clearing, and each read of the export. A write returns at once and nothing of it reaches the caller: that protection
 * is [JournalQueue]'s, which counts what fails.
 *
 * The export (UC-011) reads in short reads, each its own task: the bounds, after every entry handed over before them, so
 * that entries queued and not written yet are inside the bound it notes; then one page of entries at a time. No read
 * transaction is held across two tasks, so a write, trimming or clearing waits at most for one page, never for a whole
 * export; on Android before version 15 a read transaction that spans statements would hold the database's one writing
 * connection, and so every write, for its length.
 */
class RoomDecisionJournal(
    private val file: JournalFile,
    executor: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "slussen-journal").apply { isDaemon = true } },
) : DecisionJournalRecorder, DecisionJournalControl, DecisionJournalReader {
    private val queue = JournalQueue(executor)

    /** How many entries could not be written. */
    val droppedEntries: Long get() = queue.dropped

    /** How many trimmings after a write could not be done in full; the next write or a lower size limit tries again. */
    val trimmingsNotDone: Long get() = queue.trimmingsNotDone

    override fun record(entry: JournalEntry, keepWithinBytes: Long?) {
        queue.write(
            write = { file.insert(entry) },
            trimAfter = keepWithinBytes?.let { limit -> { trim(limit).complete } },
        )
    }

    override suspend fun sizeBytes(): Long = queue.queue { file.sizeBytes() }.await()

    override fun keepWithin(limitBytes: Long): Deferred<Trimmed> = queue.queue { trim(limitBytes) }

    override fun clear(firstEntry: JournalEntry?): Deferred<Unit> = queue.queue { file.clear(firstEntry) }

    override suspend fun bounds(): JournalBounds? = queue.queue { file.bounds() }.await()

    override suspend fun entriesAfter(afterId: Long, upToId: Long, limit: Int): List<StoredJournalEntry> =
        queue.queue { file.entriesAfter(afterId, upToId, limit) }.await()

    /** Read at once on the caller's coroutine, off the journal's thread, so that it waits for no queued task (UC-010 BR-004). */
    override suspend fun holdsAnEntryNow(): Boolean = withContext(Dispatchers.IO) { file.holdsAnEntry() }

    private fun trim(limitBytes: Long): Trimmed =
        JournalTrimming.keepWithin(limitBytes, file.pageBytes(), size = file::sizeBytes, entries = file::entryCount, removeOldest = file::removeOldest)
}
