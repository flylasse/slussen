package slussen.persistence.journal

/**
 * How the oldest entries of `journal.db` are removed until it is within a size limit (ADR-002 §9). Pure, so that the JVM
 * tests prove it; [slussen.persistence.RoomDecisionJournal] gives it the file's size, its number of entries and the
 * removal of the oldest ones with the space they took returned.
 */
object JournalTrimming {
    // UC-010 BR-003: Entries are removed only while the size is above the size limit, oldest first, until it is within the limit.
    /**
     * Removes the oldest entries while [size] is above [limitBytes], or until none is left, and returns how many it
     * removed. A step removes at most half of what the average entry suggests is over the limit, less one page, so that a
     * large lowering takes few steps and the last ones remove one entry at a time: with entries of one size no entry is
     * removed that the limit did not need. [pageBytes] is `page_size`; the size falls only when a page is returned.
     */
    fun keepWithin(limitBytes: Long, pageBytes: Long, size: () -> Long, entries: () -> Int, removeOldest: (Int) -> Unit): Int {
        var removed = 0
        var current = size()
        while (current > limitBytes) {
            val left = entries()
            if (left == 0) break
            val over = current - limitBytes
            val perEntry = (current / left).coerceAtLeast(1)
            val step = ((over - pageBytes) / perEntry / 2).coerceIn(1L, left.toLong()).toInt()
            removeOldest(step)
            removed += step
            current = size()
        }
        return removed
    }
}
