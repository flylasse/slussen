package slussen.core.request

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationResult
import slussen.core.plugin.PluginRegistration
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.FakeDataProvider
import slussen.testing.TestSlice
import java.time.Duration

/**
 * The request gate of Fetch Transactions (UC-001) that Phase 7a builds: the Request Limit of BR-007, declared by each
 * Data Provider's part as published or as a default the Operator can change (S-31), and the refusal of A3 that sends
 * nothing. The Fetch itself, its record (A3 step 3) and the rest of UC-001 come with Phase 8a.
 */
@DisplayName("UC-001: Fetch Transactions, the Request Limit gate")
class UC001FetchTransactionsTest {
    private val slice = TestSlice()
    private val request = RequestSummary(RequestKind.Transactions, bankReference = null, bankAccountReference = null)

    /** Asks the gate of [service] [times] times within one operation and returns each answer. */
    private suspend fun admit(service: ServiceKey, times: Int = 1): List<Admission> {
        val result = slice.coordinator.run(OperationKind.ServiceCredentialChange, StartedBy.Operator) { permit ->
            val gate = slice.credentials.gate(service, permit)
            List(times) { gate.admit(request) }
        }
        return (result as OperationResult.Ran).value
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: every request admitted is kept as a Provider Request with its time; the Data Provider gets at most its published Request Limit in the preceding 24 hours")
    fun publishedRequestLimitHolds() = runTest {
        val answers = admit(slice.feedsKey, times = 21)

        assertEquals(List(20) { Admission.Allowed }, answers.take(20))
        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), answers.last())
        assertEquals(20, slice.providerRequests.requests.count { it.dataProviderId == slice.feedsId })
        assertTrue(slice.providerRequests.requests.all { it.sentAt == slice.clock.instant() && !it.unattended })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007"])
    @DisplayName("A3: at the Request Limit the gate refuses for the whole Data Provider and stores no Provider Request, so nothing is sent")
    fun requestLimitReachedSendsNothing() = runTest {
        admit(slice.feedsKey, times = 20)

        val refused = admit(slice.feedsKey).single()

        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), refused)
        assertEquals(20, slice.providerRequests.requests.size)
        assertEquals(Admission.Allowed, admit(slice.wrenKey).single())
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"], requirements = ["NFR-018"])
    @DisplayName("BR-007: the count is over the preceding 24 hours, not a calendar day: a request leaves the count exactly 24 hours after it was sent")
    fun countIsOverTheRolling24Hours() = runTest {
        val first = slice.clock.instant()
        admit(slice.feedsKey, times = 20)
        slice.clock.set(first.plus(Duration.ofHours(24)).minusSeconds(1))
        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), admit(slice.feedsKey).single())

        slice.clock.set(first.plus(Duration.ofHours(24)))

        assertEquals(Admission.Allowed, admit(slice.feedsKey).single())
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider that publishes no Request Limit gets the default its part declares, and the value the Operator set holds for the gate")
    fun notPublishedRequestLimitIsTheOperatorsSetting() = runTest {
        val registered = slice.plugins.dataProvider(slice.openBankingKey).stored
        assertEquals(50, registered.requestLimit)
        assertFalse(registered.requestLimitPublished)
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 12)

        val answers = admit(slice.openBankingKey, times = 13)

        assertEquals(List(12) { Admission.Allowed }, answers.take(12))
        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), answers.last())
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: the count survives a restart of the app: a new start counts the Provider Requests already kept")
    fun countSurvivesARestart() = runTest {
        admit(slice.feedsKey, times = 20)

        val restarted = PluginRegistration(slice.registry)
            .register(slice.budgetApp, listOf(slice.feeds, slice.openBanking, slice.wren), listOf(slice.fileFormat))
        val result = slice.coordinator.run(OperationKind.ServiceCredentialChange, StartedBy.Operator) { permit ->
            DataProviderGate(restarted.dataProvider(slice.feedsKey).stored, slice.providerRequests, slice.clock, permit).admit(request)
        }

        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), (result as OperationResult.Ran).value)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a published Request Limit is set from the part at every start and is not an Operator Setting")
    fun publishedRequestLimitIsSetFromThePartAtEveryStart() = runTest {
        slice.registry.storeRequestLimit(slice.feedsId, 300)
        val newer = FakeDataProvider(
            ExampleData.feedsProfile.copy(requestLimit = RequestLimitDeclaration.Published(24, ExampleData.FEEDS_LIMIT_SOURCE)),
        )

        val restarted = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(newer), emptyList())

        val stored = restarted.dataProviders.single().stored
        assertEquals(24, stored.requestLimit)
        assertTrue(stored.requestLimitPublished)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Request Limit the Data Provider does not publish keeps the Operator's value at the next start while it lies in the declared range, and otherwise takes the default")
    fun operatorsRequestLimitIsKeptWithinTheDeclaredRange() = runTest {
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80)
        val sameRange = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(slice.openBanking), emptyList())
        assertEquals(80, sameRange.dataProviders.single().stored.requestLimit)

        val narrower = FakeDataProvider(
            ExampleData.openBankingProfile.copy(requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 40, range = 20..60)),
        )
        val outOfRange = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(narrower), emptyList())

        assertEquals(40, outOfRange.dataProviders.single().stored.requestLimit)
        assertFalse(outOfRange.dataProviders.single().stored.requestLimitPublished)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider whose part stops publishing its limit takes the default its part now declares, as not published")
    fun publishedLimitBecomingNotPublishedTakesTheDefault() = runTest {
        val unpublished = FakeDataProvider(
            ExampleData.feedsProfile.copy(requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 15, range = 10..30)),
        )

        val restarted = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(unpublished), emptyList())

        val stored = restarted.dataProviders.single().stored
        assertEquals(15, stored.requestLimit)
        assertFalse(stored.requestLimitPublished)
    }
}
