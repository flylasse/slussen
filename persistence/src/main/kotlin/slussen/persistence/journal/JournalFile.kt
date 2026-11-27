package slussen.persistence.journal

import slussen.core.port.JournalEntry

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
