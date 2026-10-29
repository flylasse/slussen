package slussen.pluginapi.testfixtures

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.budgetapp.BudgetAppReader
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransferLink
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.provider.UnreadableReason
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.Secret
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One credential check the contract tests run: the capability under test and what the Operator entered. */
public class CredentialScenario(
    public val capability: CredentialCapability,
    public val input: CredentialInput,
)

/** One read the contract tests run: the reader under test and the stored credential it is handed. */
public class ReadScenario(
    public val reader: BudgetAppReader,
    public val credential: Secret,
)

/** A short invented request timeout for the cases where the test server never answers. */
private val shortTimeout: Duration = Duration.ofSeconds(1)

/** Enough time for a call that is not cancelled or timed out to be seen as still running. */
private val longTimeout: Duration = Duration.ofSeconds(30)

/** How long a check may take beyond the request timeout before the contract test calls it hung. */
private val slack: Duration = Duration.ofSeconds(4)

/**
 * The credential checks of ADR-001 §4.4 and §4.5, shared by the Data Provider and Budget App contract tests. The
 * subclass serves invented recorded answers from its own local test server (a fresh one per test) through the hooks.
 */
public abstract class CredentialContractTest {
    /**
     * The log the subclass constructs the plug-in under test with, written as with Detailed Logging on, so that every
     * detail message is run and kept (ADR-006 §2, NFR-008).
     */
    protected val log: WrittenLog = WrittenLog()

    /** The service accepts the credentials. */
    protected abstract fun credentialsAccepted(): CredentialScenario

    /** The service answers that the credentials are not valid or not allowed (UC-014 A1). */
    protected abstract fun credentialsRefused(): CredentialScenario

    /** The service answers that it is unavailable (UC-014 A2). */
    protected abstract fun credentialServiceUnavailable(): CredentialScenario

    /** The service accepts the connection and never answers. */
    protected abstract fun credentialServiceNeverAnswering(): CredentialScenario

    /** An entry the part can see cannot be valid before sending anything, such as a malformed token (UC-014 A1). */
    protected abstract fun credentialsThatCannotBeValid(): CredentialScenario

    /** How many requests the test server has received in this test. */
    protected abstract fun requestsReceived(): Int

    /** How many calls of the plug-in's HTTP library are still running. */
    protected abstract fun runningCalls(): Int

    /** Every credential value planted in this test's input and recorded answers, which no `toString()` may show. */
    protected abstract fun plantedValues(): List<String>

    /**
     * A credential check on a device that refuses network access (ADR-001 §4.0, S-36): the capability of a plug-in
     * built on an HTTP client whose address lookup throws [DeviceRefusal.exception] and whose requests run on
     * [threads], with an entry that would be sent.
     */
    protected abstract fun credentialsOnRefusingDevice(threads: RecordingRequestThreads): CredentialScenario

    private fun check(scenario: CredentialScenario, context: TestRequestContext): CredentialCheck =
        runBlocking(Dispatchers.Default) { scenario.capability.check(scenario.input, context) }

    @Test
    @DisplayName("ADR-001 §4.5: accepted credentials come back as Accepted, every request admitted by the gate first")
    public fun acceptedCredentialsAreAdmittedAndAccepted() {
        val gate = RecordingRequestGate()
        val result = check(credentialsAccepted(), TestRequestContext(gate))
        assertTrue(result is CredentialCheck.Accepted, "expected Accepted, was $result")
        assertTrue(requestsReceived() >= 1, "the service was not asked")
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
    }

    @Test
    @DisplayName("ADR-001 §4.5: credentials the service refuses come back as Refused")
    public fun refusedCredentialsAreRefused() {
        val gate = RecordingRequestGate()
        val result = check(credentialsRefused(), TestRequestContext(gate))
        assertEquals(CredentialCheck.Refused, result)
        assertEquals(requestsReceived(), gate.requests.size)
    }

    @Test
    @DisplayName("ADR-001 §4.5: an unavailable service comes back as TransientFailure")
    public fun unavailableServiceIsATransientFailure() {
        val gate = RecordingRequestGate()
        val result = check(credentialServiceUnavailable(), TestRequestContext(gate))
        assertTrue(result is CredentialCheck.TransientFailure, "expected TransientFailure, was $result")
        assertEquals(requestsReceived(), gate.requests.size)
    }

    @Test
    @DisplayName("ADR-001 §4.5: a refusal by the gate sends nothing and comes back as StoppedByGate with the gate's reason")
    public fun gateRefusalSendsNothing() {
        val gate = RecordingRequestGate.refusing()
        val result = check(credentialsAccepted(), TestRequestContext(gate))
        assertEquals(CredentialCheck.StoppedByGate(RefusalReason.RequestLimitReached), result)
        assertEquals(0, requestsReceived(), "nothing is sent after a refusal")
    }

    @Test
    @DisplayName("ADR-001 §4.4, §4.5: an entry that cannot be valid is Refused without calling the gate, so nothing is sent and no Provider Request is counted")
    public fun entryThatCannotBeValidIsRefusedWithoutTheGate() {
        val gate = RecordingRequestGate()
        val result = check(credentialsThatCannotBeValid(), TestRequestContext(gate))
        assertEquals(CredentialCheck.Refused, result)
        assertTrue(gate.requests.isEmpty(), "the gate was asked: ${gate.requests}")
        assertEquals(0, requestsReceived(), "nothing is sent")
    }

    @Test
    @DisplayName("ADR-001 §4.0: a service that never answers ends in TransientFailure(Timeout) within the request timeout")
    public fun neverAnsweringServiceTimesOut() {
        val started = System.nanoTime()
        val result = check(credentialServiceNeverAnswering(), TestRequestContext(timeout = shortTimeout))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(CredentialCheck.TransientFailure(FailureKind.Timeout), result)
        assertTrue(elapsed < shortTimeout.plus(slack), "took $elapsed")
    }

    @Test
    @DisplayName("ADR-001 §4.0: cancelling the caller cancels the underlying call")
    public fun cancellingTheCallerCancelsTheCall() {
        val scenario = credentialServiceNeverAnswering()
        runBlocking(Dispatchers.Default) {
            val running = async(start = CoroutineStart.DEFAULT) {
                scenario.capability.check(scenario.input, TestRequestContext(timeout = longTimeout))
            }
            withTimeout(slack.toMillis()) { while (requestsReceived() < 1) delay(10) }
            running.cancelAndJoin()
            withTimeout(slack.toMillis()) { while (runningCalls() > 0) delay(10) }
        }
        assertEquals(0, runningCalls())
    }

    @Test
    @DisplayName("NFR-004: a credential check the device refuses (an address lookup that throws a SecurityException) ends as TransientFailure(ConnectionLost) and leaves no uncaught exception on any thread (ADR-001 §4.0, §4.4, S-36)")
    public fun deviceRefusedCheckIsALostConnection() {
        val threads = RecordingRequestThreads()
        try {
            val gate = RecordingRequestGate()
            val result = check(credentialsOnRefusingDevice(threads), TestRequestContext(gate))
            threads.awaitIdle(slack)
            assertNoUncaughtException(threads)
            assertEquals(CredentialCheck.TransientFailure(FailureKind.ConnectionLost), result)
            assertFalse(DeviceRefusal.TEXT in result.toString(), "the refusal's text leaves the plug-in: $result")
            assertTrue(gate.requests.isNotEmpty(), "the request was admitted by the gate before it was sent")
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    @DisplayName("ADR-006 §2, NFR-008: no toString() of the input or of a result, and no log line with Detailed Logging on, reveals a planted credential")
    public fun noResultRevealsACredential() {
        val shown = mutableListOf<String>()
        listOf(::credentialsAccepted, ::credentialsRefused, ::credentialServiceUnavailable).forEach { scenario ->
            val s = scenario()
            shown += s.input.toString()
            shown += s.input.values.values.map { it.toString() }
            val result = check(s, TestRequestContext())
            shown += result.toString()
            if (result is CredentialCheck.Accepted) shown += result.credential.toString()
        }
        shown += log.written
        plantedValues().forEach { planted ->
            shown.forEach { text -> assertFalse(planted in text, "a toString() reveals a planted credential: $text") }
        }
    }
}

/**
 * The Data Provider contract test of ADR-001 §4.4. Each plug-in module's tests extend it with invented recorded answers
 * served by a local test server: the credential checks of §4.5 and the declared-number checks. A Data Provider whose
 * Fetch is built also extends [DataProviderFetchContractTest].
 */
public abstract class DataProviderContractTest : CredentialContractTest() {
    /** The Data Provider under test, constructed with [log] as the composition root constructs it (ADR-001 §5). */
    protected abstract fun dataProvider(log: SlussenLog): DataProvider

    @Test
    @DisplayName("ADR-001 §4.4: the declared name and source key are not empty and the default Fetch Cooldown lies in 0 to 1440")
    public fun declaredProfileIsComplete() {
        val profile = dataProvider(NoLog).profile
        assertTrue(profile.name.isNotBlank(), "name")
        assertTrue(profile.sourceKey.isNotBlank(), "source key")
        assertTrue(profile.defaultFetchCooldownMinutes in FETCH_COOLDOWN_RANGE, "default Fetch Cooldown ${profile.defaultFetchCooldownMinutes}")
    }

    @Test
    @DisplayName("ADR-001 §4.4: a published Request Limit lies in 1 to 1000 and names its source; a default lies in its range, inside 1 to 1000")
    public fun declaredRequestLimitLiesInItsRange() {
        when (val limit = dataProvider(NoLog).profile.requestLimit) {
            is RequestLimitDeclaration.Published -> {
                assertTrue(limit.requests in REQUEST_LIMIT_RANGE, "published ${limit.requests}")
                assertTrue(limit.source.isNotBlank(), "a published Request Limit names its source")
            }
            is RequestLimitDeclaration.NotPublished -> {
                assertTrue(limit.range.first in REQUEST_LIMIT_RANGE && limit.range.last in REQUEST_LIMIT_RANGE, "range ${limit.range}")
                assertFalse(limit.range.isEmpty(), "range ${limit.range}")
                assertTrue(limit.defaultRequests in limit.range, "default ${limit.defaultRequests} in ${limit.range}")
            }
        }
    }

    private companion object {
        /** `DATA_PROVIDER.fetch_cooldown_minutes` (entity model): Min 0, Max 1440. */
        val FETCH_COOLDOWN_RANGE = 0..1440

        /** `DATA_PROVIDER.request_limit` (entity model): Min 1, Max 1000. */
        val REQUEST_LIMIT_RANGE = 1..1000
    }
}

/** One Fetch the contract tests run: the Data Provider under test, the request and the stored credential it is handed. */
public class FetchScenario(
    public val dataProvider: DataProvider,
    public val request: FetchRequest,
    public val credential: Secret,
)

/** What a recorded answer holds: every Bank Account it lists, and how many items each read Bank Account delivers. */
public class RecordedFetch(
    /** The `providerReference` of every Bank Account the answer lists, in order. */
    public val bankAccounts: List<String>,
    /** Every item of each read Bank Account, pending and zero-amount ones included, by `providerReference`. */
    public val itemsPerBankAccount: Map<String, Int>,
    /** How many of those items have no readable date or amount. */
    public val uninterpretable: Int,
    /** How many of those items the Data Provider marks as pending, not booked yet (UC-001 BR-002). */
    public val pending: Int,
    /** How many of those items have a readable amount of zero (UC-001 BR-003). */
    public val zeroAmount: Int,
    /** How many of those items have no date at all (UC-001 A17). */
    public val withoutDate: Int,
    /**
     * The time the Data Provider says each read Bank Account's balance was reported, by `providerReference`; null where
     * it gives none (S-T-04, ADR-001 §4.1).
     */
    public val balanceReportedAt: Map<String, Instant?>,
)

/**
 * What a recorded answer holds that reports the Data Provider's own errors for some Banks and Bank Accounts
 * (S-T-03, ADR-001 §4.1), with texts of its own that must not leave the plug-in (NFR-012).
 */
public class RecordedProviderErrors(
    /** The Bank Accounts of the Banks the Operator must sign in to again at the Data Provider (UC-001 A20). */
    public val signInNeeded: Set<String>,
    /** The Bank Accounts the Data Provider reports a failure for, with the kind the plug-in gives it (UC-001 A8). */
    public val failed: Map<String, FailureKind>,
    /** The Bank Accounts of the same answer that the errors do not concern, read as usual. */
    public val read: Set<String>,
    /** Every text of the Data Provider's own errors in the answer. */
    public val texts: List<String>,
)

/**
 * What a recorded answer to a request that reads only some of the Bank Accounts it lists gives with the list (S-35,
 * ADR-001 §4.1).
 */
public class RecordedListedBalances(
    /**
     * The balance the answer gives with the list for each listed Bank Account the request does not ask to read, by
     * `providerReference`; null where it gives none.
     */
    public val balances: Map<String, Amount?>,
    /** How many requests the Data Provider needs for the Bank Accounts the request reads; none more is sent for the others. */
    public val requests: Int,
)

/**
 * The Fetch checks of the Data Provider contract test (ADR-001 §4.0, §4.1, §4.4), for a Data Provider whose Fetch is
 * built. The subclass serves invented recorded answers from its own local test server through the hooks.
 */
public abstract class DataProviderFetchContractTest : DataProviderContractTest() {
    /** The Data Provider answers with the recorded answer of [recordedFetch], which holds pending, zero-amount and unreadable items. */
    protected abstract fun fetchAnswered(): FetchScenario

    protected abstract fun recordedFetch(): RecordedFetch

    /**
     * The Data Provider answers a request of [BankAccountSelection.Selected] that reads only some of the Bank Accounts
     * it lists, with the balances it gives with the list, as [recordedListedBalances] describes.
     */
    protected abstract fun fetchReadingSome(): FetchScenario

    protected abstract fun recordedListedBalances(): RecordedListedBalances

    /**
     * The Data Provider answers with the recorded answer of [recordedProviderErrors], which reports its own errors: a
     * Bank whose sign-in at the Data Provider is needed, and a failure for a Bank Account, each with a text of its own.
     */
    protected abstract fun fetchWithProviderErrors(): FetchScenario

    protected abstract fun recordedProviderErrors(): RecordedProviderErrors

    /** The Data Provider answers that the stored credential is not valid or not allowed. */
    protected abstract fun fetchCredentialRefused(): FetchScenario

    /** The Data Provider answers that it is unavailable. */
    protected abstract fun fetchUnavailable(): FetchScenario

    /** The Data Provider accepts the connection and never answers. */
    protected abstract fun fetchNeverAnswering(): FetchScenario

    /** Every credential, Bank Account number or Bank Session planted in this test's recorded answers and credential. */
    protected abstract fun plantedFetchValues(): List<String>

    /**
     * A Fetch on a device that refuses network access (ADR-001 §4.0, S-36): a Data Provider built on an HTTP client whose
     * address lookup throws [DeviceRefusal.exception] and whose requests run on [threads].
     */
    protected abstract fun fetchOnRefusingDevice(threads: RecordingRequestThreads): FetchScenario

    private fun fetch(scenario: FetchScenario, context: TestRequestContext): FetchResponse =
        runBlocking(Dispatchers.Default) { scenario.dataProvider.fetch(scenario.request, context) }

    @Test
    @DisplayName("ADR-001 §4.4: every request is admitted by the gate first, and every item of a recorded answer appears in the response, pending and zero-amount ones included")
    public fun everyItemIsPassedOn() {
        val gate = RecordingRequestGate()
        val scenario = fetchAnswered()
        val response = fetch(scenario, TestRequestContext(gate, credential = scenario.credential))
        val recorded = recordedFetch()
        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        assertTrue(requestsReceived() >= 1, "the Data Provider was not asked")
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
        assertEquals(recorded.bankAccounts, response.bankAccounts.map { it.providerReference })
        recorded.itemsPerBankAccount.forEach { (reference, items) ->
            val result = response.bankAccounts.single { it.providerReference == reference }.result
            assertTrue(result is BankAccountResult.Read, "$reference was not read: $result")
            assertEquals(items, (result as BankAccountResult.Read).transactions.size, "items of $reference")
        }
        val unreadable = response.bankAccounts.flatMap { (it.result as? BankAccountResult.Read)?.transactions.orEmpty() }
            .count { it is ProviderTransaction.Uninterpretable }
        assertEquals(recorded.uninterpretable, unreadable, "items without a readable date or amount are Uninterpretable")
        assertNoBalanceUnlessNotSelected(response)
    }

    /** Every item the read Bank Accounts of [response] carry. */
    private fun itemsOf(response: FetchResponse): List<ProviderTransaction> =
        response.bankAccounts.flatMap { (it.result as? BankAccountResult.Read)?.transactions.orEmpty() }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("ADR-001 §4.1, BR-002: a pending item is passed on with booked = false; leaving it out is the core's rule")
    public fun pendingItemIsPassedOnNotBooked() {
        val scenario = fetchAnswered()
        val items = itemsOf(fetch(scenario, TestRequestContext(credential = scenario.credential)))
        val notBooked = items.count {
            when (it) {
                is ProviderTransaction.Interpretable -> !it.booked
                is ProviderTransaction.Uninterpretable -> it.booked == false
            }
        }
        assertTrue(recordedFetch().pending >= 1, "the recorded answer holds a pending item")
        assertEquals(recordedFetch().pending, notBooked, "pending items passed on with booked = false")
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("ADR-001 §4.1, BR-003: an item with an amount of zero is passed on; leaving it out is the core's rule")
    public fun zeroAmountIsPassedOn() {
        val scenario = fetchAnswered()
        val items = itemsOf(fetch(scenario, TestRequestContext(credential = scenario.credential)))
        val zero = items.count { it is ProviderTransaction.Interpretable && it.amount == Amount(0) }
        assertTrue(recordedFetch().zeroAmount >= 1, "the recorded answer holds an item with an amount of zero")
        assertEquals(recordedFetch().zeroAmount, zero, "items with an amount of zero passed on")
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("ADR-001 §4.1, A17: an item without a date is passed on as Uninterpretable(MissingDate)")
    public fun itemWithoutADateIsUninterpretable() {
        val scenario = fetchAnswered()
        val items = itemsOf(fetch(scenario, TestRequestContext(credential = scenario.credential)))
        val withoutDate = items.filterIsInstance<ProviderTransaction.Uninterpretable>().count { it.reason == UnreadableReason.MissingDate }
        assertTrue(recordedFetch().withoutDate >= 1, "the recorded answer holds an item without a date")
        assertEquals(recordedFetch().withoutDate, withoutDate, "items without a date are Uninterpretable(MissingDate)")
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("ADR-001 §4.1 (S-T-04), step 8: a read Bank Account carries the time the Data Provider says its balance was reported, or none when it gives none")
    public fun balanceTimeIsCarried() {
        val scenario = fetchAnswered()
        val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
        val recorded = recordedFetch().balanceReportedAt
        assertTrue(recorded.values.any { it != null }, "the recorded answer gives a balance time")
        recorded.forEach { (reference, reportedAt) ->
            val result = response.bankAccounts.single { it.providerReference == reference }.result
            assertTrue(result is BankAccountResult.Read, "$reference was not read: $result")
            assertEquals(reportedAt, (result as BankAccountResult.Read).balanceReportedAt, "balance time of $reference")
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A20: Bank's Sign-In at the Data Provider Needed")
    @DisplayName("ADR-001 §4.1 (S-T-03), A20: the Bank Accounts of a Bank whose sign-in at the Data Provider is needed are SignInNeeded and carry nothing; the other Bank Accounts of the same answer are read as usual")
    public fun signInNeededConcernsThatBanksBankAccountsOnly() {
        val scenario = fetchWithProviderErrors()
        val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
        val recorded = recordedProviderErrors()
        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        assertTrue(recorded.signInNeeded.isNotEmpty() && recorded.read.isNotEmpty(), "the recorded answer concerns some Bank Accounts only")
        recorded.signInNeeded.forEach { reference ->
            assertEquals(BankAccountResult.SignInNeeded, response.bankAccounts.single { it.providerReference == reference }.result, reference)
        }
        recorded.read.forEach { reference ->
            val result = response.bankAccounts.single { it.providerReference == reference }.result
            assertTrue(result is BankAccountResult.Read, "$reference is read as usual: $result")
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("ADR-001 §4.1 (S-T-03), A8: a Bank Account the Data Provider reports a failure for is Failed with its kind and carries none of its transactions; the others are read as usual")
    public fun reportedFailureIsFailedForThatBankAccount() {
        val scenario = fetchWithProviderErrors()
        val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
        val recorded = recordedProviderErrors()
        assertTrue(recorded.failed.isNotEmpty(), "the recorded answer reports a failure for a Bank Account")
        recorded.failed.forEach { (reference, kind) ->
            assertEquals(BankAccountResult.Failed(kind), response.bankAccounts.single { it.providerReference == reference }.result, reference)
        }
        recorded.read.forEach { reference ->
            val result = response.bankAccounts.single { it.providerReference == reference }.result
            assertTrue(result is BankAccountResult.Read, "$reference is read as usual: $result")
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A20: Bank's Sign-In at the Data Provider Needed")
    @DisplayName("NFR-012, ADR-001 §4.1: no text of the Data Provider's own errors appears in any toString() of the response")
    public fun noProviderErrorTextLeavesThePlugin() {
        val scenario = fetchWithProviderErrors()
        val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
        val shown = listOf(response.toString(), response.sourceOutcome.toString()) +
            response.bankOutcomes.map { it.toString() } +
            response.bankAccounts.flatMap { listOf(it.toString(), it.result.toString()) }
        val texts = recordedProviderErrors().texts
        assertTrue(texts.isNotEmpty(), "the recorded answer holds texts of the Data Provider's errors")
        texts.forEach { text -> shown.forEach { assertFalse(text in it, "a toString() shows the Data Provider's text: $it") } }
    }

    @Test
    @DisplayName("ADR-001 §4.1 (S-35): a listed Bank Account the request did not ask to read is NotRead(NotSelected) with the balance the Data Provider gave with the list in the same answer, or none, and no further request is sent for it")
    public fun listedBankAccountsCarryTheirBalance() {
        val gate = RecordingRequestGate()
        val scenario = fetchReadingSome()
        val selection = scenario.request.bankAccounts
        assertTrue(selection is BankAccountSelection.Selected, "the scenario selects Bank Accounts: $selection")
        val asked = (selection as BankAccountSelection.Selected).bankAccounts.map { it.providerReference }.toSet()
        val response = fetch(scenario, TestRequestContext(gate, credential = scenario.credential))
        val recorded = recordedListedBalances()
        assertEquals(SourceOutcome.Completed, response.sourceOutcome)
        val notAsked = response.bankAccounts.filter { it.providerReference !in asked }
        assertEquals(recorded.balances.keys, notAsked.map { it.providerReference }.toSet(), "the listed Bank Accounts not asked for")
        notAsked.forEach { listed ->
            assertEquals(
                BankAccountResult.NotRead(NotReadCause.NotSelected, recorded.balances.getValue(listed.providerReference)),
                listed.result,
                listed.providerReference,
            )
        }
        assertEquals(recorded.requests, requestsReceived(), "no further request is sent for a listed Bank Account")
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
        assertNoBalanceUnlessNotSelected(response)
    }

    /** ADR-001 §4.1 (S-35): `NotRead` for any cause other than NotSelected carries no balance. */
    private fun assertNoBalanceUnlessNotSelected(response: FetchResponse) {
        response.bankAccounts.forEach { delivered ->
            val result = delivered.result
            if (result is BankAccountResult.NotRead && result.cause != NotReadCause.NotSelected) {
                assertEquals(null, result.balance, "${delivered.providerReference}: NotRead(${result.cause}) carries no balance")
            }
        }
    }

    @Test
    @DisplayName("ADR-001 §4.0: a Fetch the gate refuses for the whole Data Provider sends nothing and comes back as StoppedByGate")
    public fun gateRefusedFetchSendsNothing() {
        val scenario = fetchAnswered()
        val response = fetch(scenario, TestRequestContext(RecordingRequestGate.refusing(), credential = scenario.credential))
        assertEquals(SourceOutcome.StoppedByGate(RefusalReason.RequestLimitReached), response.sourceOutcome)
        assertEquals(0, requestsReceived())
    }

    @Test
    @DisplayName("ADR-001 §4.1, §4.5: a refused stored credential is CredentialsRefused, a typed result")
    public fun refusedCredentialIsTyped() {
        val scenario = fetchCredentialRefused()
        val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
        assertEquals(SourceOutcome.CredentialsRefused, response.sourceOutcome)
    }

    @Test
    @DisplayName("ADR-001 §4.1: an unavailable Data Provider is a TransientFailure")
    public fun unavailableDataProviderIsATransientFailure() {
        val scenario = fetchUnavailable()
        val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
        assertTrue(response.sourceOutcome is SourceOutcome.TransientFailure, "was ${response.sourceOutcome}")
    }

    @Test
    @DisplayName("ADR-001 §4.0: a Fetch the Data Provider never answers ends in TransientFailure(Timeout) within the request timeout")
    public fun neverAnsweredFetchTimesOut() {
        val scenario = fetchNeverAnswering()
        val started = System.nanoTime()
        val response = fetch(scenario, TestRequestContext(timeout = shortTimeout, credential = scenario.credential))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(SourceOutcome.TransientFailure(FailureKind.Timeout), response.sourceOutcome)
        assertTrue(elapsed < shortTimeout.plus(slack), "took $elapsed")
    }

    @Test
    @DisplayName("ADR-001 §4.0: cancelling the caller of a Fetch cancels the underlying call")
    public fun cancellingTheFetchCancelsTheCall() {
        val scenario = fetchNeverAnswering()
        runBlocking(Dispatchers.Default) {
            val running = async {
                scenario.dataProvider.fetch(scenario.request, TestRequestContext(timeout = longTimeout, credential = scenario.credential))
            }
            withTimeout(slack.toMillis()) { while (requestsReceived() < 1) delay(10) }
            running.cancelAndJoin()
            withTimeout(slack.toMillis()) { while (runningCalls() > 0) delay(10) }
        }
        assertEquals(0, runningCalls())
    }

    @Test
    @DisplayName("NFR-004: a Fetch the device refuses (an address lookup that throws a SecurityException) ends as TransientFailure(ConnectionLost) and leaves no uncaught exception on any thread (ADR-001 §4.0, §4.4, S-36)")
    public fun deviceRefusedFetchIsALostConnection() {
        val threads = RecordingRequestThreads()
        try {
            val scenario = fetchOnRefusingDevice(threads)
            val response = fetch(scenario, TestRequestContext(credential = scenario.credential))
            threads.awaitIdle(slack)
            assertNoUncaughtException(threads)
            assertEquals(SourceOutcome.TransientFailure(FailureKind.ConnectionLost), response.sourceOutcome)
            assertFalse(DeviceRefusal.TEXT in response.toString(), "the refusal's text leaves the plug-in: $response")
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    @DisplayName("ADR-006 §2: no toString() of a Fetch result reveals the credential, a Bank Account number or a Bank Session")
    public fun noFetchResultRevealsASecret() {
        val scenario = fetchAnswered()
        val shown = fetch(scenario, TestRequestContext(credential = scenario.credential)).toString()
        plantedFetchValues().forEach { planted -> assertFalse(planted in shown, "a toString() reveals $planted") }
    }
}

/** One read of changes the contract tests run: the reader under test, the stored credential, and what it is asked. */
public class ChangesScenario(
    public val reader: BudgetAppReader,
    public val credential: Secret,
    public val budgetReference: String,
    public val since: ChangeCursor?,
    public val from: LocalDate,
)

/**
 * The Budget App contract test of ADR-001 §4.4: the credential checks of §4.5, the length check of ADR-008 §6, the
 * reading of Budgets and of changes of §4.2.3 (F-3, F-4, F-8). F-1, F-2, F-5 and F-7 come with the slice that builds
 * UC-006.
 */
public abstract class BudgetAppContractTest : CredentialContractTest() {
    protected abstract fun budgetApp(log: SlussenLog): BudgetApp

    /** The Budget App lists the Budgets of [expectedBudgets]. */
    protected abstract fun budgetsAnswered(): ReadScenario

    /** What [budgetsAnswered] must return, from the same recorded answer. */
    protected abstract fun expectedBudgets(): List<BudgetRead>

    /** The Budget App answers that the stored credential is not valid. */
    protected abstract fun budgetsCredentialRefused(): ReadScenario

    /** The Budget App answers that it is unavailable. */
    protected abstract fun budgetsUnavailable(): ReadScenario

    /** The Budget App accepts the connection and never answers. */
    protected abstract fun budgetsNeverAnswering(): ReadScenario

    /**
     * A read of the Budgets on a device that refuses network access (ADR-001 §4.0, S-36): a reader built on an HTTP
     * client whose address lookup throws [DeviceRefusal.exception] and whose requests run on [threads].
     */
    protected abstract fun budgetsOnRefusingDevice(threads: RecordingRequestThreads): ReadScenario

    private fun read(scenario: ReadScenario, context: TestRequestContext): ReadResult<List<BudgetRead>> =
        runBlocking(Dispatchers.Default) { scenario.reader.readBudgets(context) }

    @Test
    @DisplayName("ADR-008 §6: the Budget App accepts Import Identifiers at least as long as protocol version 1 makes them")
    public fun importIdentifierLengthCoversTheProtocol() {
        val declared = budgetApp(NoLog).profile.importIdentifierMaxLength
        assertTrue(declared >= IMPORT_IDENTIFIER_V1_LENGTH, "declared $declared")
    }

    @Test
    @DisplayName("ADR-001 §4.2.3: readBudgets returns every Budget with its Budget Accounts, currency and balances")
    public fun budgetsAreRead() {
        val gate = RecordingRequestGate()
        val scenario = budgetsAnswered()
        val result = read(scenario, TestRequestContext(gate, credential = scenario.credential))
        assertEquals(ReadResult.Answered(expectedBudgets()), result)
        assertTrue(requestsReceived() >= 1)
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
    }

    @Test
    @DisplayName("ADR-001 §4.2.1 F-8: refused credentials are a typed result")
    public fun refusedCredentialsAreTyped() {
        val scenario = budgetsCredentialRefused()
        assertEquals(ReadResult.CredentialsRefused, read(scenario, TestRequestContext(credential = scenario.credential)))
    }

    @Test
    @DisplayName("ADR-001 §4.2.3: an unavailable Budget App is a TransientFailure")
    public fun unavailableBudgetAppIsATransientFailure() {
        val scenario = budgetsUnavailable()
        val result = read(scenario, TestRequestContext(credential = scenario.credential))
        assertTrue(result is ReadResult.TransientFailure, "was $result")
    }

    @Test
    @DisplayName("ADR-001 §4.0: a read the Budget App never answers ends in TransientFailure(Timeout)")
    public fun neverAnsweredReadTimesOut() {
        val scenario = budgetsNeverAnswering()
        val started = System.nanoTime()
        val result = read(scenario, TestRequestContext(timeout = shortTimeout, credential = scenario.credential))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(ReadResult.TransientFailure(FailureKind.Timeout), result)
        assertTrue(elapsed < shortTimeout.plus(slack), "took $elapsed")
    }

    @Test
    @DisplayName("NFR-004: a read the device refuses (an address lookup that throws a SecurityException) ends as TransientFailure(ConnectionLost) and leaves no uncaught exception on any thread (ADR-001 §4.0, §4.4, S-36)")
    public fun deviceRefusedReadIsALostConnection() {
        val threads = RecordingRequestThreads()
        try {
            val scenario = budgetsOnRefusingDevice(threads)
            val result = read(scenario, TestRequestContext(credential = scenario.credential))
            threads.awaitIdle(slack)
            assertNoUncaughtException(threads)
            assertEquals(ReadResult.TransientFailure(FailureKind.ConnectionLost), result)
            assertFalse(DeviceRefusal.TEXT in result.toString(), "the refusal's text leaves the plug-in: $result")
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    @DisplayName("ADR-001 §4.0: a read refused by the gate sends nothing")
    public fun gateRefusedReadSendsNothing() {
        val scenario = budgetsAnswered()
        val result = read(scenario, TestRequestContext(RecordingRequestGate.refusing(), credential = scenario.credential))
        assertTrue(result is ReadResult.StoppedByGate, "was $result")
        assertEquals(0, requestsReceived())
    }

    /**
     * The Budget App answers a read of changes with the recorded answer of [expectedChanges]: a transaction entered by
     * hand, an Import, a deletion, a merge, two linked transfers alike in amount and date with both their sides, and
     * transactions that are Cleared and approved and that are not (F-3, F-4). Each call serves the answer once more.
     */
    protected abstract fun changesAnswered(): ChangesScenario

    /** What [changesAnswered] must return, from the same recorded answer. */
    protected abstract fun expectedChanges(): ChangeSet

    /** The Budget App answers a read of changes that the stored credential is not valid. */
    protected abstract fun changesCredentialRefused(): ChangesScenario

    /** The Budget App answers a read of changes that it is unavailable. */
    protected abstract fun changesUnavailable(): ChangesScenario

    /** The Budget App accepts the connection of a read of changes and never answers. */
    protected abstract fun changesNeverAnswering(): ChangesScenario

    private fun readChanges(scenario: ChangesScenario, context: TestRequestContext): ReadResult<ChangeSet> =
        runBlocking(Dispatchers.Default) { scenario.reader.readChanges(scenario.budgetReference, scenario.since, scenario.from, context) }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016", "BR-020"])
    @DisplayName("ADR-001 §4.2.1 F-3, F-4: readChanges returns every changed transaction with every field and the next cursor, every request admitted by the gate first")
    public fun changesAreReadWithEveryField() {
        val gate = RecordingRequestGate()
        val scenario = changesAnswered()
        val result = readChanges(scenario, TestRequestContext(gate, credential = scenario.credential))
        assertEquals(ReadResult.Answered(expectedChanges()), result)
        assertTrue(requestsReceived() >= 1)
        assertEquals(requestsReceived(), gate.requests.size, "every request is admitted by the gate first")
        assertTrue(gate.requests.all { it.kind == RequestKind.BudgetAppRead }, "a read is admitted as a Budget App read")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005", "BR-020"])
    @DisplayName("ADR-001 §4.4, F-4: the recorded answer holds every entry kind, a deletion, a merge with the transaction it went into, and Cleared and approved both ways, and each comes back as the plug-in decides it")
    public fun changesCoverEveryKindAndState() {
        val transactions = expectedChanges().transactions
        assertEquals(EntryKind.entries.toSet(), transactions.map { it.entry }.toSet(), "every entry kind")
        assertTrue(transactions.any { it.state == ReadState.Deleted }, "a deletion")
        val merged = transactions.mapNotNull { it.state as? ReadState.Merged }
        assertTrue(merged.isNotEmpty(), "a merge")
        merged.forEach { into -> assertTrue(transactions.any { it.reference == into.intoReference }, "merged into ${into.intoReference}") }
        assertEquals(setOf(true, false), transactions.map { it.cleared }.toSet(), "Cleared and not")
        assertEquals(setOf(true, false), transactions.map { it.approved }.toSet(), "approved and not")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-020"])
    @DisplayName("ADR-001 §4.4, F-4: both sides of a linked transfer carry each other's references, and two look-alike Transfers stay apart")
    public fun linkedTransferSidesCarryEachOther() {
        val scenario = changesAnswered()
        val read = (readChanges(scenario, TestRequestContext(credential = scenario.credential)) as ReadResult.Answered).value.transactions
        val linked = read.filter { it.transferLink != null }
        assertTrue(linked.size >= 4, "two linked transfers with both sides")
        linked.forEach { side ->
            val link = side.transferLink!!
            val other = read.single { it.reference == link.transactionReference }
            assertEquals(link.budgetAccountReference, other.budgetAccountReference, "${side.reference} names the other side's Budget Account")
            assertEquals(TransferLink(side.reference, side.budgetAccountReference), other.transferLink, "${other.reference} links back")
        }
        val alike = linked.groupBy { Triple(it.budgetAccountReference, it.date, it.amount) }.filterValues { it.size >= 2 }
        assertTrue(alike.isNotEmpty(), "two Transfers alike in Budget Account, date and amount")
        alike.values.forEach { sides -> assertEquals(sides.size, sides.map { it.transferLink }.toSet().size, "look-alike Transfers stay apart") }
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("ADR-001 §4.2.1 F-3: reading again from the same cursor returns at least the same changes, so applying them twice changes nothing")
    public fun readingAgainReturnsAtLeastTheSameChanges() {
        val first = changesAnswered()
        val once = (readChanges(first, TestRequestContext(credential = first.credential)) as ReadResult.Answered).value
        val again = changesAnswered()
        val twice = (readChanges(again, TestRequestContext(credential = again.credential)) as ReadResult.Answered).value
        assertTrue(twice.transactions.containsAll(once.transactions), "the second read misses changes of the first")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A2: Budget App Refuses Slussen's Credentials")
    @DisplayName("ADR-001 §4.2.1 F-8: refused credentials of a read of changes are a typed result")
    public fun refusedCredentialsOfChangesAreTyped() {
        val scenario = changesCredentialRefused()
        assertEquals(ReadResult.CredentialsRefused, readChanges(scenario, TestRequestContext(credential = scenario.credential)))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read")
    @DisplayName("ADR-001 §4.2.3: an unavailable Budget App is a TransientFailure of the read of changes")
    public fun unavailableChangesAreATransientFailure() {
        val scenario = changesUnavailable()
        val result = readChanges(scenario, TestRequestContext(credential = scenario.credential))
        assertTrue(result is ReadResult.TransientFailure, "was $result")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-016"])
    @DisplayName("NFR-016, ADR-001 §4.0: a read of changes the Budget App never answers ends in TransientFailure(Timeout) within the request timeout")
    public fun neverAnsweredChangesTimeOut() {
        val scenario = changesNeverAnswering()
        val started = System.nanoTime()
        val result = readChanges(scenario, TestRequestContext(timeout = shortTimeout, credential = scenario.credential))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(ReadResult.TransientFailure(FailureKind.Timeout), result)
        assertTrue(elapsed < shortTimeout.plus(slack), "took $elapsed")
    }

    @Test
    @DisplayName("ADR-001 §4.0: a read of changes refused by the gate sends nothing")
    public fun gateRefusedChangesSendNothing() {
        val scenario = changesAnswered()
        val result = readChanges(scenario, TestRequestContext(RecordingRequestGate.refusing(), credential = scenario.credential))
        assertTrue(result is ReadResult.StoppedByGate, "was $result")
        assertEquals(0, requestsReceived())
    }

    private companion object {
        /** ADR-008 §6: version 1 of the Import Identifier protocol produces 31 characters. */
        const val IMPORT_IDENTIFIER_V1_LENGTH = 31
    }
}

/**
 * The File Pre-processor contract test of ADR-001 §4.4. This slice holds the declaration check; the conversion checks
 * come with the slice that builds UC-002.
 */
public abstract class FilePreprocessorContractTest {
    protected abstract fun filePreprocessor(log: SlussenLog): FilePreprocessor

    @Test
    @DisplayName("ADR-001 §4.3: the File Pre-processor declares a name and a source key")
    public fun declaredProfileIsComplete() {
        val profile = filePreprocessor(NoLog).profile
        assertTrue(profile.name.isNotBlank(), "name")
        assertTrue(profile.sourceKey.isNotBlank(), "source key")
    }
}

/**
 * ADR-001 §4.0 (S-36): no exception of a request ended a thread it ran on; on Android such an exception ends the app.
 * The message names each exception's type only.
 */
private fun assertNoUncaughtException(threads: RecordingRequestThreads) {
    val uncaught = threads.uncaught
    assertTrue(uncaught.isEmpty(), "an exception ended a request thread: ${uncaught.map { it::class.java.name }}")
}

/** A log that keeps every line it is given as text, detail messages included, as with Detailed Logging on. */
public class WrittenLog : SlussenLog {
    private val lines = mutableListOf<String>()

    public val written: List<String> get() = lines.toList()

    override fun event(event: slussen.pluginapi.diagnostic.LogEvent, vararg facts: slussen.pluginapi.diagnostic.LogFact) {
        lines += "$event ${facts.toList()}"
    }

    override fun detail(event: slussen.pluginapi.diagnostic.LogEvent, message: () -> String) {
        lines += "$event ${message()}"
    }
}

/** A log that writes nothing, for constructing a plug-in only to read its declarations. */
private object NoLog : SlussenLog {
    override fun event(event: slussen.pluginapi.diagnostic.LogEvent, vararg facts: slussen.pluginapi.diagnostic.LogFact) {}

    override fun detail(event: slussen.pluginapi.diagnostic.LogEvent, message: () -> String) {}
}
