package slussen.pluginapi.testfixtures

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import java.time.Duration

/**
 * The contract test every Budget App writer passes (ADR-001 §4.2.4, §4.4; F-2, F-5, F-8): one outcome per item, a held
 * Import Identifier `AlreadyPresent`, nothing sent approved, a write that gets no answer `Unknown` for every item and
 * never sent again, a cancellation before and after the request left, a refusing device, a clearing, and no planted
 * value in a log line or a `toString()`. A Budget App part's tests extend it beside [BudgetAppContractTest]; the
 * scripted writer of `:testing` passes it too, so that the Upload's tests rest on the same behaviour (C2 review item 3).
 */
public abstract class BudgetAppWriterContractTest {
    /** The log the subclass constructs the writer under test with; its Detailed Logging is switched by the tests. */
    protected val log: WrittenLog = WrittenLog()

    /** How many requests the Budget App has received in this test. */
    protected abstract fun requestsReceived(): Int

    /** How many requests of the writer are still running. */
    protected abstract fun runningCalls(): Int

    /**
     * The Budget App creates every item of [WriteScenario.items], all of one Budget Account and none a linked
     * transfer, and answers each with its reference: the outcomes of [expectedCreated] (F-1, F-2).
     */
    protected abstract fun createAnswered(): WriteScenario

    /** What [createAnswered] must return, item by item, from the same recorded answer. */
    protected abstract fun expectedCreated(): List<ItemOutcome>

    /**
     * The Budget App already holds the Import Identifier of at least one item of the Budget Account and creates the
     * others: the outcomes of [expectedWithHeldImportIdentifier] (F-2, UC-006 BR-007).
     */
    protected abstract fun createWithHeldImportIdentifier(): WriteScenario

    /** What [createWithHeldImportIdentifier] must return; each held item is `AlreadyPresent`, never `Rejected`. */
    protected abstract fun expectedWithHeldImportIdentifier(): List<ItemOutcome>

    /**
     * One item names a Budget Account that is not in the Budget the call names; the Budget App would create the
     * others: the outcomes of [expectedWithBudgetAccountNotInTheBudget] (ADR-001 §4.2.4, §4.2.5, S-B-79).
     */
    protected abstract fun createWithBudgetAccountNotInTheBudget(): WriteScenario

    /** What [createWithBudgetAccountNotInTheBudget] must return: that item `Unknown`, never `Rejected`. */
    protected abstract fun expectedWithBudgetAccountNotInTheBudget(): List<ItemOutcome>

    /** The Budget App answers the first write of the call that the stored credential is not valid. */
    protected abstract fun createCredentialsRefused(): WriteScenario

    /** The Budget App accepts the connection of the write and never answers. */
    protected abstract fun createNeverAnswering(): WriteScenario

    /**
     * The Budget App receives the whole write and closes the connection without an answer; were the write sent
     * again, the next answer would create the items, so a resend by the plug-in or its HTTP library shows.
     */
    protected abstract fun createConnectionLostAfterSending(): WriteScenario

    /** A write on a device that refuses network access (ADR-001 §4.0, S-36), its requests running on [threads]. */
    protected abstract fun createOnRefusingDevice(threads: RecordingRequestThreads): WriteScenario

    /**
     * For every transaction the Budget App received a request to create in this test, in order, whether that request
     * asked for it approved (F-5).
     */
    protected abstract fun sentApproved(): List<Boolean>

    /** The Budget App marks the counterpart Cleared. */
    protected abstract fun clearAnswered(): ClearScenario

    /** The Budget App refuses to mark the counterpart Cleared. */
    protected abstract fun clearRefused(): ClearScenario

    /** The Budget App accepts the connection of the clearing and never answers. */
    protected abstract fun clearNeverAnswering(): ClearScenario

    /**
     * The values planted in the items and recorded answers of this test's writes and reads that no log line, in either
     * mode, and no `toString()` may show (ADR-006 §2, NFR-007, NFR-008): payees, memos, amounts as text, Budget
     * Account names, Import Identifiers and the Budget App's references.
     */
    protected abstract fun plantedWriteValues(): List<String>

    private fun create(scenario: WriteScenario, context: TestRequestContext): CreateResult =
        runBlocking(Dispatchers.Default) { scenario.writer.create(scenario.budgetReference, scenario.items, context) }

    private fun clear(scenario: ClearScenario, context: TestRequestContext): ClearResult =
        runBlocking(Dispatchers.Default) { scenario.writer.markCleared(scenario.budgetReference, scenario.counterpartReference, context) }

    private fun allUnknown(scenario: WriteScenario): CreateResult =
        CreateResult.Answered(scenario.items.map { ItemOutcome.Unknown })

    /** Runs [call] in a coroutine, cancels it once [started] is true, and returns what [call] returned, if anything. */
    private fun <T> cancelledOnce(started: () -> Boolean, call: suspend () -> T): T? {
        var returned: T? = null
        runBlocking(Dispatchers.Default) {
            val running = launch { returned = call() }
            withTimeout(slack.toMillis()) { while (!started()) delay(10) }
            running.cancelAndJoin()
        }
        return returned
    }

    /** A gate that records the request and then waits until its caller is cancelled: nothing has left when it is. */
    private class HoldingGate : RequestGate {
        @Volatile
        var asked: Boolean = false

        override suspend fun admit(request: RequestSummary): Admission {
            asked = true
            awaitCancellation()
        }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("ADR-001 §4.2.1 F-2: create answers one outcome per item, in the order of the items, every request admitted by the gate first as a Budget App write or read")
    public fun createdItemsAreCreated() {
        val gate = RecordingRequestGate()
        val scenario = createAnswered()
        val result = create(scenario, TestRequestContext(gate, credential = scenario.credential))
        assertEquals(CreateResult.Answered(expectedCreated()), result)
        assertEquals(scenario.items.size, expectedCreated().size, "one expected outcome per item")
        assertTrue(expectedCreated().all { it is ItemOutcome.Created && it.reference.isNotEmpty() }, "every item is created with a reference")
        assertTrue(requestsReceived() >= 1)
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
        assertTrue(gate.requests.any { it.kind == RequestKind.BudgetAppWrite }, "a write is admitted as a Budget App write")
        assertTrue(gate.requests.all { it.kind == RequestKind.BudgetAppWrite || it.kind == RequestKind.BudgetAppRead }, "${gate.requests}")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-015"])
    @DisplayName("ADR-001 §4.2.1 F-2, NFR-015: an item whose Import Identifier the Budget Account already holds is AlreadyPresent, never Rejected")
    public fun heldImportIdentifierIsAlreadyPresent() {
        val scenario = createWithHeldImportIdentifier()
        val result = create(scenario, TestRequestContext(credential = scenario.credential))
        assertEquals(CreateResult.Answered(expectedWithHeldImportIdentifier()), result)
        assertTrue(expectedWithHeldImportIdentifier().any { it is ItemOutcome.AlreadyPresent }, "the scenario holds a held Import Identifier")
        assertTrue(expectedWithHeldImportIdentifier().none { it is ItemOutcome.Rejected }, "a held Import Identifier is never Rejected")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("C2 review item 2, ADR-001 §4.2.4: an item whose Budget Account is not in the Budget named is not sent and is Unknown, never Rejected; the other items are sent")
    public fun budgetAccountNotInTheBudgetIsNotSent() {
        val scenario = createWithBudgetAccountNotInTheBudget()
        val result = create(scenario, TestRequestContext(credential = scenario.credential))
        assertEquals(CreateResult.Answered(expectedWithBudgetAccountNotInTheBudget()), result)
        assertTrue(expectedWithBudgetAccountNotInTheBudget().any { it == ItemOutcome.Unknown }, "the scenario holds such an item")
        assertTrue(expectedWithBudgetAccountNotInTheBudget().none { it is ItemOutcome.Rejected }, "such an item is never Rejected")
        assertEquals(scenario.items.size - 1, sentApproved().size, "the item was sent")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("ADR-001 §4.2.1 F-5: every transaction is sent to be created waiting for the Operator's approval")
    public fun createdTransactionsAwaitApproval() {
        val scenario = createAnswered()
        create(scenario, TestRequestContext(credential = scenario.credential))
        val approved = sentApproved()
        assertEquals(scenario.items.size, approved.size, "one transaction sent per item")
        assertTrue(approved.none { it }, "a transaction was sent approved")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-016"])
    @DisplayName("ADR-001 §4.0, NFR-016: a write the Budget App never answers ends within the request timeout with Unknown for every item, and is not sent again")
    public fun neverAnsweredWriteIsUnknown() {
        val gate = RecordingRequestGate()
        val scenario = createNeverAnswering()
        val started = System.nanoTime()
        val result = create(scenario, TestRequestContext(gate, timeout = shortTimeout, credential = scenario.credential))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(allUnknown(scenario), result)
        assertTrue(elapsed < shortTimeout.plus(slack), "took $elapsed")
        Thread.sleep(SETTLE.toMillis())
        assertEquals(gate.requests.size, requestsReceived(), "a request was sent that the gate did not admit")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-004"])
    @DisplayName("ADR-001 §4.0: a write whose connection is lost after it was sent is Unknown for every item, and neither the plug-in nor its HTTP library sends it again")
    public fun lostWriteIsUnknownAndSentOnce() {
        val gate = RecordingRequestGate()
        val scenario = createConnectionLostAfterSending()
        val result = create(scenario, TestRequestContext(gate, credential = scenario.credential))
        assertEquals(allUnknown(scenario), result)
        Thread.sleep(SETTLE.toMillis())
        assertEquals(gate.requests.size, requestsReceived(), "a request was sent again without the gate")
        assertEquals(1, gate.requests.count { it.kind == RequestKind.BudgetAppWrite }, "the write was sent once")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.0: a write cancelled before its request left sends nothing, and every item is Unknown")
    public fun writeCancelledBeforeSendingSendsNothing() {
        val scenario = createAnswered()
        val gate = HoldingGate()
        val result = cancelledOnce({ gate.asked }) {
            scenario.writer.create(scenario.budgetReference, scenario.items, TestRequestContext(gate, credential = scenario.credential))
        }
        assertEquals(allUnknown(scenario), result)
        Thread.sleep(SETTLE.toMillis())
        assertEquals(0, requestsReceived(), "a request left after the cancellation")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.0: a write cancelled after its request left is Unknown for every item, the underlying call is cancelled, and nothing is sent again")
    public fun writeCancelledAfterSendingIsUnknown() {
        val scenario = createNeverAnswering()
        val gate = RecordingRequestGate()
        val result = cancelledOnce({ requestsReceived() >= 1 }) {
            scenario.writer.create(scenario.budgetReference, scenario.items, TestRequestContext(gate, timeout = longTimeout, credential = scenario.credential))
        }
        assertEquals(allUnknown(scenario), result)
        runBlocking { withTimeout(slack.toMillis()) { while (runningCalls() > 0) delay(10) } }
        assertEquals(gate.requests.size, requestsReceived())
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.2.4: a write refused by the gate before anything was sent sends nothing and is StoppedByGate with the gate's reason")
    public fun gateRefusedWriteSendsNothing() {
        val scenario = createAnswered()
        val result = create(scenario, TestRequestContext(RecordingRequestGate.refusing(), credential = scenario.credential))
        assertEquals(CreateResult.StoppedByGate(RefusalReason.RequestLimitReached), result)
        assertEquals(0, requestsReceived())
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown")
    @DisplayName("ADR-001 §4.2.1 F-8, §4.2.4: refused credentials of a write of which nothing was written are a typed result")
    public fun refusedCredentialsOfAWriteAreTyped() {
        val scenario = createCredentialsRefused()
        assertEquals(CreateResult.CredentialsRefused, create(scenario, TestRequestContext(credential = scenario.credential)))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", requirements = ["NFR-004"])
    @DisplayName("NFR-004: a write the device refuses ends with Unknown for every item and leaves no uncaught exception on any thread (ADR-001 §4.0, S-36)")
    public fun deviceRefusedWriteIsUnknown() {
        val threads = RecordingRequestThreads()
        try {
            val scenario = createOnRefusingDevice(threads)
            val result = create(scenario, TestRequestContext(credential = scenario.credential))
            threads.awaitIdle(slack)
            assertNoUncaughtExceptionOn(threads)
            assertEquals(allUnknown(scenario), result)
            assertFalse(DeviceRefusal.TEXT in result.toString(), "the refusal's text leaves the plug-in: $result")
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("ADR-001 §4.2.4: markCleared answers Cleared when the Budget App marked the counterpart Cleared, admitted by the gate as a write")
    public fun counterpartIsCleared() {
        val gate = RecordingRequestGate()
        val scenario = clearAnswered()
        assertEquals(ClearResult.Cleared, clear(scenario, TestRequestContext(gate, credential = scenario.credential)))
        assertEquals(requestsReceived(), gate.requests.size)
        assertTrue(gate.requests.all { it.kind == RequestKind.BudgetAppWrite }, "${gate.requests}")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A11: Marking a Transfer Counterpart Cleared Fails")
    @DisplayName("ADR-001 §4.2.4: a clearing the Budget App refuses is NotCleared")
    public fun refusedClearingIsNotCleared() {
        val scenario = clearRefused()
        assertEquals(ClearResult.NotCleared, clear(scenario, TestRequestContext(credential = scenario.credential)))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A11: Marking a Transfer Counterpart Cleared Fails", requirements = ["NFR-016"])
    @DisplayName("ADR-001 §4.0: a clearing the Budget App never answers ends within the request timeout as Unknown; one cancelled after it left is Unknown too")
    public fun unansweredClearingIsUnknown() {
        val scenario = clearNeverAnswering()
        val started = System.nanoTime()
        assertEquals(ClearResult.Unknown, clear(scenario, TestRequestContext(timeout = shortTimeout, credential = scenario.credential)))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < shortTimeout.plus(slack))
        val again = clearNeverAnswering()
        val before = requestsReceived()
        val result = cancelledOnce({ requestsReceived() > before }) {
            again.writer.markCleared(again.budgetReference, again.counterpartReference, TestRequestContext(timeout = longTimeout, credential = again.credential))
        }
        assertEquals(ClearResult.Unknown, result)
        runBlocking { withTimeout(slack.toMillis()) { while (runningCalls() > 0) delay(10) } }
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A11: Marking a Transfer Counterpart Cleared Fails")
    @DisplayName("ADR-001 §4.0: a clearing refused by the gate sends nothing and is not Cleared")
    public fun gateRefusedClearingSendsNothing() {
        val scenario = clearAnswered()
        val result = clear(scenario, TestRequestContext(RecordingRequestGate.refusing(), credential = scenario.credential))
        assertTrue(result != ClearResult.Cleared, "was $result")
        assertEquals(0, requestsReceived())
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("ADR-006 §2, NFR-008: no log line, with Detailed Logging on or off, and no toString() of an item or a result shows a planted payee, memo, amount, Budget Account name, Import Identifier, reference or credential")
    public fun noWriteRevealsAPlantedValue() {
        val shown = mutableListOf<String>()
        listOf(false, true).forEach { detailed ->
            log.detailed = detailed
            listOf(::createAnswered, ::createWithHeldImportIdentifier, ::createCredentialsRefused).forEach { make ->
                val scenario = make()
                shown += scenario.items.map { it.toString() }
                val result = create(scenario, TestRequestContext(credential = scenario.credential))
                shown += result.toString()
                if (result is CreateResult.Answered) shown += result.outcomes.map { it.toString() }
            }
            val cleared = clearAnswered()
            shown += clear(cleared, TestRequestContext(credential = cleared.credential)).toString()
        }
        log.detailed = true
        shown += log.written
        assertTrue(plantedWriteValues().isNotEmpty(), "the test plants no value")
        plantedWriteValues().forEach { planted ->
            shown.forEach { text -> assertFalse(planted in text, "a log line or toString() shows a planted value: $text") }
        }
    }

    private companion object {
        /** How long a test waits after a result for a request that would be sent again. */
        val SETTLE: Duration = Duration.ofMillis(300)
    }
}
