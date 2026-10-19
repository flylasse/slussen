package slussen.pluginapi.testfixtures

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A device that refuses network access, for the contract test of ADR-001 §4.0 and §4.4 (S-36): the plug-in test builds
 * its plug-in on an HTTP client whose address lookup throws [exception], as Android does for an app without the
 * network permission.
 */
public object DeviceRefusal {
    /** The invented text of the refusal, which no result of the plug-in may show (NFR-012). */
    public const val TEXT: String = "invented device refusal: permission denied (missing INTERNET permission?)"

    /** What the device's address lookup throws. */
    public fun exception(): SecurityException = SecurityException(TEXT)
}

/**
 * The threads a plug-in's HTTP library runs its requests on in the contract test of a request the device refuses
 * (ADR-001 §4.0, §4.4, S-36). It records every exception that ends a request's task or its thread, which on Android
 * would end the app, so the test can assert that none did. The plug-in test hands it to its HTTP library as the
 * executor of its requests.
 */
public class RecordingRequestThreads private constructor(private val ended: CopyOnWriteArrayList<Throwable>) :
    ThreadPoolExecutor(0, Int.MAX_VALUE, 1, TimeUnit.SECONDS, SynchronousQueue(), RecordingThreadFactory(ended)) {

    public constructor() : this(CopyOnWriteArrayList())

    private val running = AtomicInteger()

    /** Every exception that ended a task or a thread of this executor, in order. */
    public val uncaught: List<Throwable> get() = ended.toList()

    override fun execute(command: Runnable) {
        running.incrementAndGet()
        try {
            super.execute(command)
        } catch (e: RejectedExecutionException) {
            running.decrementAndGet()
            throw e
        }
    }

    override fun afterExecute(task: Runnable?, thrown: Throwable?) {
        try {
            if (thrown != null) record(ended, thrown)
        } finally {
            running.decrementAndGet()
        }
    }

    /** Waits until no task is running or waiting, so that an exception a task throws after its answer is recorded. */
    public fun awaitIdle(timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (running.get() > 0) {
            check(System.nanoTime() < deadline) { "the request threads did not go idle within $timeout" }
            Thread.sleep(10)
        }
    }

    private class RecordingThreadFactory(private val ended: CopyOnWriteArrayList<Throwable>) : ThreadFactory {
        private val number = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            Thread(task, "contract-test-request-${number.incrementAndGet()}").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e -> record(ended, e) }
            }
    }

    private companion object {
        /** The same exception reaches both [afterExecute] and the thread's handler; it is recorded once. */
        fun record(ended: CopyOnWriteArrayList<Throwable>, thrown: Throwable) {
            synchronized(ended) { if (ended.none { it === thrown }) ended += thrown }
        }
    }
}
