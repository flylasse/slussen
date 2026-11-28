package slussen.persistence.journal

import slussen.core.port.JournalBounds
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.StoredJournalEntry

/**
 * The calls [slussen.persistence.RoomDecisionJournal] makes on `journal.db` (ADR-002 §9), each blocking, each made only
 * on the journal's own thread; behind this interface so that a JVM test can make them fail or hold.
 */
interface JournalFile {
    fun insert(entry: JournalEntry)

    fun entryCount(): Int

    /** `page_size`. */
    fun pageBytes(): Long

    /** `page_count × page_size`. */
    fun sizeBytes(): Long

    /** The [count] oldest entries (lowest `id`) removed and their space returned, in one transaction. */
    fun removeOldest(count: Int)

    /** Every entry removed, [firstEntry] written if given and the space returned, all in one transaction. */
    fun clear(firstEntry: JournalEntry?)

    /** The oldest entry's `id` and time, the newest entry's `id` and how many up to it; null when there is none (UC-011 step 3). */
    fun bounds(): JournalBounds?

    /** At most [limit] entries with an `id` above [afterId] up to [upToId], oldest first, in one short read (UC-011 BR-007). */
    fun entriesAfter(afterId: Long, upToId: Long, limit: Int): List<StoredJournalEntry>

    /** Whether an entry is written, in one statement that may be made from any thread (UC-011 precondition). */
    fun holdsAnEntry(): Boolean
}

/** [JournalFile] on Room: `PRAGMA incremental_vacuum` returns the space of removed entries, `auto_vacuum` being INCREMENTAL. */
class RoomJournalFile(private val database: JournalDatabase) : JournalFile {
    private val dao = database.entries()

    override fun insert(entry: JournalEntry) {
        dao.insert(entry.entity())
    }

    override fun entryCount(): Int = dao.count()

    override fun pageBytes(): Long = pragma("page_size")

    override fun sizeBytes(): Long = pragma("page_count") * pragma("page_size")

    override fun removeOldest(count: Int) {
        database.runInTransaction {
            dao.deleteOldest(count)
            returnFreePages()
        }
    }

    override fun clear(firstEntry: JournalEntry?) {
        // UC-010 BR-003: A clearing removes every entry and returns their space, all or nothing, with the clearing as the first entry after.
        database.runInTransaction {
            dao.deleteAll()
            if (firstEntry != null) dao.insert(firstEntry.entity())
            returnFreePages()
        }
    }

    /** Three statements and no transaction: every write runs on the same journal thread, so none comes between them. */
    override fun bounds(): JournalBounds? {
        val oldest = dao.oldest() ?: return null
        val newest = dao.newestId() ?: oldest.id
        return JournalBounds(oldest.id, oldest.recordedAt, newest, dao.countUpTo(newest))
    }

    override fun entriesAfter(afterId: Long, upToId: Long, limit: Int): List<StoredJournalEntry> =
        dao.page(afterId, upToId, limit).map { StoredJournalEntry(it.id, it.entry()) }

    /** One statement, from any thread: Room gives it a connection of its own. */
    override fun holdsAnEntry(): Boolean = dao.oldestId() != null

    private fun pragma(name: String): Long =
        database.openHelper.writableDatabase.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0 }

    /** `PRAGMA incremental_vacuum`: every free page returned, so that the size falls (ADR-002 §9). */
    private fun returnFreePages() {
        database.openHelper.writableDatabase.query("PRAGMA incremental_vacuum").use { cursor -> while (cursor.moveToNext()) Unit }
    }

    private fun DecisionJournalEntryEntity.entry() = JournalEntry(
        recordedAt = recordedAt,
        eventKind = JournalEventKind.entries.single { it.text == eventType.text },
        details = details,
        importIdentifier = importIdentifier,
        bankAccountName = bankAccountName,
    )

    private fun JournalEntry.entity() = DecisionJournalEntryEntity(
        recordedAt = recordedAt,
        eventType = JournalEventType.entries.single { it.text == eventKind.text },
        importIdentifier = importIdentifier,
        bankAccountName = bankAccountName,
        details = details,
    )
}
