package slussen.core.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * The protection every Decision Journal recorder shares (ADR-002 §9, UC-011 BR-002, UC-010 BR-004): what the journal's
 * database does runs on the journal's own [executor], one task after the other in the order handed over. A write
 * returns to its caller at once and never throws to it; a write that fails is counted and dropped there, a trimming
 * after it that fails is counted apart, and the entry stays written. `:persistence` gives it the journal's own thread;
 * the fake of `:testing` an executor a test can hold, so that both fail the same way: later, never in the caller.
 */
class JournalQueue(private val executor: Executor) {
    private val droppedCount = AtomicLong()
    private val notTrimmedCount = AtomicLong()

    /** How many entries could not be written. */
    val dropped: Long get() = droppedCount.get()

    /** How many trimmings after a write could not be done in full. */
    val trimmingsNotDone: Long get() = notTrimmedCount.get()

    /**
     * Hands [write] over, followed by [trimAfter] when given (true when it is done in full), and returns at once. Neither
     * runs in the caller, and nothing they throw reaches it.
     */
    fun write(write: () -> Unit, trimAfter: (() -> Boolean)?) {
        try {
            executor.execute {
                // UC-010 BR-004: A failure to record an entry never fails or delays an Operator action: it is counted and dropped on the journal's own thread.
                try {
                    write()
                } catch (failure: Exception) {
                    droppedCount.incrementAndGet()
                    return@execute
                }
                if (trimAfter == null) return@execute
                val done = try {
                    trimAfter()
                } catch (failure: Exception) {
                    false
                }
                if (!done) notTrimmedCount.incrementAndGet()
            }
        } catch (rejected: RejectedExecutionException) {
            droppedCount.incrementAndGet()
        }
    }

    /**
     * Hands [task] over after every task handed over before it, and returns at once; its result, or what it threw, is
     * awaited from what this returns (a task that throws anything, an Error too, completes it with that failure), by a caller that may wait for the journal (the Decision Journal's own control).
     */
    fun <T> queue(task: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        try {
            executor.execute {
                // Any Throwable, an Error too, completes the result, so that a caller waiting for it is always answered.
                try {
                    result.complete(task())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            result.completeExceptionally(rejected)
        }
        return result
    }
}
