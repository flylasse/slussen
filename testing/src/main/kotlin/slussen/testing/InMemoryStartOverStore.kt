package slussen.testing

import slussen.core.consent.ConsentNotEnded
import slussen.core.port.StartOverStore

/** O-17 step 2 (ADR-002 §3) for tests of the core: whether Start Over was recorded, a hook before it, and a failure. */
class InMemoryStartOverStore : StartOverStore {
    var recorded: Boolean = false
        private set

    /** Makes the record fail, as a start intent that cannot be written would (NFR-023). */
    var fails: Boolean = false

    /** Runs as the record begins: a test can check what had happened before it. */
    var beforeRecord: () -> Unit = {}

    /** The Banks written with the record (ADR-002 §3 O-17 step 2). */
    var recordedNotEnded: List<ConsentNotEnded>? = null
        private set

    override suspend fun recordStartOver(notEnded: List<ConsentNotEnded>) {
        beforeRecord()
        if (fails) throw IllegalStateException("The invented start intent of this test cannot be written.")
        recordedNotEnded = notEnded
        recorded = true
    }
}
