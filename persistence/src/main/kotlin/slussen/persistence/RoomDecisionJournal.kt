package slussen.persistence

import kotlinx.coroutines.Deferred
import slussen.core.journal.JournalQueue
import slussen.core.port.DecisionJournalControl
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalEntry
import slussen.core.port.Trimmed
import slussen.persistence.journal.JournalFile
import slussen.persistence.journal.JournalTrimming
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * `journal.db` for the core (ADR-002 §9): the recorder and the Decision Journal's own control. Everything runs on one
 * thread of its own ([executor]), one task after the other in the order handed over, never inside a transaction of
 * `slussen.db`: a write, the trimming after it, the trimming right after a lower size limit and a clearing. A write
 * returns at once and nothing of it reaches the caller: that protection is [JournalQueue]'s, which counts what fails.
 */
class RoomDecisionJournal(
    private val file: JournalFile,
    executor: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "slussen-journal").apply { isDaemon = true } },
) : DecisionJournalRecorder, DecisionJournalControl {
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

    private fun trim(limitBytes: Long): Trimmed =
        JournalTrimming.keepWithin(limitBytes, file.pageBytes(), size = file::sizeBytes, entries = file::entryCount, removeOldest = file::removeOldest)
}
