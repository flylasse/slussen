package slussen.persistence

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import slussen.core.port.DecisionJournalControl
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalEntry
import slussen.persistence.journal.DecisionJournalEntryEntity
import slussen.persistence.journal.JournalDatabase
import slussen.persistence.journal.JournalEventType
import slussen.persistence.journal.JournalTrimming
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * `journal.db` for the core (ADR-002 §9): the recorder and the Decision Journal's own control. Everything runs on one
 * thread of its own, one task after the other in the order handed over, never inside a transaction of `slussen.db`: a
 * write, the trimming after it, the trimming right after a lower size limit and a clearing. A write returns at once; any
 * failure of it is caught, counted and dropped and never reaches the caller (NFR-006). The size is
 * `page_count × page_size`; the space of removed entries is returned by `PRAGMA incremental_vacuum`, the database being
 * created with `auto_vacuum = INCREMENTAL` ([SlussenDatabases.openJournal]).
 */
class RoomDecisionJournal(private val database: JournalDatabase) : DecisionJournalRecorder, DecisionJournalControl {
    private val dao = database.entries()
    private val dropped = AtomicLong()
    private val notTrimmed = AtomicLong()
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "slussen-journal").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> dropped.incrementAndGet() })

    /** How many entries could not be written. */
    val droppedEntries: Long get() = dropped.get()

    /** How many trimmings after a write could not be done; the next write or a lower size limit tries again. */
    val trimmingsNotDone: Long get() = notTrimmed.get()

    override fun record(entry: JournalEntry, keepWithinBytes: Long?) {
        scope.launch {
            dao.insert(entry.entity())
            if (keepWithinBytes != null) {
                try {
                    trim(keepWithinBytes)
                } catch (failure: Exception) {
                    notTrimmed.incrementAndGet()
                }
            }
        }
    }

    override suspend fun sizeBytes(): Long = withContext(dispatcher) { size() }

    override suspend fun keepWithin(limitBytes: Long): Int = withContext(dispatcher) { trim(limitBytes) }

    override suspend fun clear(firstEntry: JournalEntry?) = withContext(dispatcher) {
        // UC-010 BR-003: A clearing removes every entry and returns their space, all or nothing, with the clearing as the first entry after.
        database.runInTransaction {
            dao.deleteAll()
            if (firstEntry != null) dao.insert(firstEntry.entity())
            returnFreePages()
        }
    }

    private fun trim(limitBytes: Long): Int =
        JournalTrimming.keepWithin(limitBytes, pragma("page_size"), size = ::size, entries = dao::count) { count ->
            database.runInTransaction {
                dao.deleteOldest(count)
                returnFreePages()
            }
        }

    private fun size(): Long = pragma("page_count") * pragma("page_size")

    private fun pragma(name: String): Long =
        database.openHelper.writableDatabase.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0 }

    /** `PRAGMA incremental_vacuum`: every free page returned, so that the size falls (ADR-002 §9). */
    private fun returnFreePages() {
        database.openHelper.writableDatabase.query("PRAGMA incremental_vacuum").use { cursor -> while (cursor.moveToNext()) Unit }
    }

    private fun JournalEntry.entity() = DecisionJournalEntryEntity(
        recordedAt = recordedAt,
        eventType = JournalEventType.entries.single { it.text == eventKind.text },
        importIdentifier = importIdentifier,
        bankAccountName = bankAccountName,
        details = details,
    )
}
