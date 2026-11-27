package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.DataProviderRegistration
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.testing.InMemoryBudgetAppCopyStore
import slussen.testing.InMemoryConnectionStore
import slussen.testing.InMemoryImportStatusStore
import slussen.testing.InMemoryOperatorSettingsStore
import slussen.testing.InMemoryPluginRegistryStore
import slussen.testing.InMemoryStateVersion
import slussen.testing.StateVersionScenario

/**
 * `state_version` on the in-memory fakes of the core's ports (ADR-007 §6 step 2): the steps of [StateVersionScenario],
 * which the Room stores run on a device in `StateVersionPersistenceIT`. The fakes stand in for `slussen.db` in every core
 * test, so they must raise the version for the same storage operations as the real storage does.
 */
class StateVersionInMemoryTest {
    @Test
    @DisplayName("ADR-007 §6 step 2: on the in-memory fakes every storage operation that changes what an Upload Plan was prepared from (served Budgets, Bank Accounts, Account Mappings, Left-Out Bank Accounts, Manually Kept Accounts, Operator Settings, the stored copy of the Budget App, the conclusions of a Comparison, a new Bank Transaction, a Review Decision and its clearing) raises state_version, and a refused one, or the status of an Unreadable Transaction, leaves it")
    fun everyStoredChangeRaisesTheStateVersionAndARefusalLeavesIt() = runBlocking {
        val stateVersion = InMemoryStateVersion()
        val connections = InMemoryConnectionStore(stateVersion)
        val registry = InMemoryPluginRegistryStore(stateVersion)
        val transactions = InMemoryImportStatusStore(connections = connections)
        val copy = InMemoryBudgetAppCopyStore(connections, transactions)

        val steps = StateVersionScenario(
            registry, registry, InMemoryOperatorSettingsStore(stateVersion = stateVersion), connections, connections, copy, copy, transactions, copy,
        ) { stateVersion.value }.run()

        assertEquals(StateVersionScenario.OPERATIONS, steps.map { it.operation })
        assertEquals(emptyList<String>(), steps.filter { it.mustRaise != it.rose }.map { "${it.operation}: state_version ${if (it.rose) "rose" else "did not rise"}" })
    }

    @Test
    @DisplayName("UC-001 BR-007: on the in-memory fake, as in slussen.db, the Request Limit of a Data Provider that publishes it is left as it is and state_version is not raised, and the one of a Data Provider that publishes none is stored and raises it")
    fun aPublishedRequestLimitIsLeftAsItIsAndRaisesNothing() = runBlocking {
        val stateVersion = InMemoryStateVersion()
        val registry = InMemoryPluginRegistryStore(stateVersion)
        fun provider(sourceKey: String, limit: RequestLimitDeclaration) = DataProviderRegistration(
            name = sourceKey, sourceKey = sourceKey, givesStableReference = true, needsBankConsent = false, defaultFetchCooldownMinutes = 60, requestLimit = limit,
        )
        val rows = registry.reconcile(
            listOf(
                provider("example-publishing", RequestLimitDeclaration.Published(requests = 4, source = "the invented terms of this test")),
                provider("example-not-publishing", RequestLimitDeclaration.NotPublished(defaultRequests = 20, range = 1..50)),
            ),
            emptyList(),
        ).dataProviders
        val publishing = rows.single { it.sourceKey == "example-publishing" }.id
        val notPublishing = rows.single { it.sourceKey == "example-not-publishing" }.id

        registry.storeRequestLimit(publishing, 30)
        val afterPublished = stateVersion.value
        registry.storeRequestLimit(notPublishing, 30)

        assertEquals(0L, afterPublished)
        assertEquals(1L, stateVersion.value)
        assertEquals(mapOf(publishing to 4, notPublishing to 30), registry.dataProviders.associate { it.id to it.requestLimit })
    }
}
