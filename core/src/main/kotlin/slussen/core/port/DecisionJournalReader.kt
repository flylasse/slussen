package slussen.core.port

import java.io.Writer
import java.time.Instant

/** One stored `DECISION_JOURNAL_ENTRY` with its `id`; ids only grow, also after a clearing (ADR-002 §9). */
data class StoredJournalEntry(val id: Long, val entry: JournalEntry)

/**
 * What a Journal Export notes of the Decision Journal at its start (UC-011 step 3): the oldest entry's `id` and time, from
 * whose day its dates are worked out (BR-008), the newest entry's `id`, beyond which no entry enters the file (BR-007), and
 * [count], how many entries it holds up to that `id`, read with it, so that the entries removed while the file is
 * written are counted exactly.
 */
data class JournalBounds(val oldestId: Long, val oldestRecordedAt: Instant, val newestId: Long, val count: Int)

/**
 * The Decision Journal read for the Journal Export only (ADR-001 §3, ADR-002 §9; Konsist K-07): nothing else in the core
 * reads an entry. [bounds] and [entriesAfter] are each one short read on the journal's own thread, after every entry handed
 * to the recorder before it, never inside a transaction of `slussen.db`; no call holds the journal's database for longer
 * than its own read, so that no write, trimming or clearing waits for an export (UC-010 BR-004). Each throws when the
 * entries cannot be read.
 */
interface DecisionJournalReader {
    /** The bounds of the Decision Journal once every entry handed over before is written; null when it holds no entry. */
    suspend fun bounds(): JournalBounds?

    /** At most [limit] entries with an `id` above [afterId] up to and including [upToId], oldest first by `id`. */
    suspend fun entriesAfter(afterId: Long, upToId: Long, limit: Int): List<StoredJournalEntry>

    /**
     * Whether the Decision Journal holds an entry written already, read at once, outside the journal's own thread, so
     * that it never waits behind a long trimming or many queued entries; it does not see entries queued and not written
     * yet. Throws when it cannot be read.
     */
    suspend fun holdsAnEntryNow(): Boolean
}

/**
 * The document the Operator chose for a Journal Export (UC-011 step 2), implemented by `:app` on the place Android's file
 * picker gave. Both calls block and are made off the main thread.
 */
interface JournalExportDocument {
    /** Opens the document for writing from its start, emptying it; throws when it cannot be opened. */
    fun open(): Writer

    /** Removes the document (A3 step 1); false when it cannot be removed. */
    fun remove(): Boolean
}
