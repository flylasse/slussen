package slussen.core.operation

import kotlinx.coroutines.CancellationException

/**
 * NFR-023: an action the Operator starts answers a storage that cannot be read or written at its own control instead of
 * failing. Runs [block] and gives [notStored] when the stored data throws within it. A caller that changes stored data
 * makes one storage operation, all or nothing, the last step of [block] that can throw, so that [notStored] means that
 * nothing was changed; a read after that write is made in a call of its own.
 */
internal inline fun <T> unlessStorageFails(notStored: T, block: () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        notStored
    }
