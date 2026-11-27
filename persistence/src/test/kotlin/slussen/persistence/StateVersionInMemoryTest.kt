package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
    @DisplayName("ADR-007 §6 step 2: on the in-memory fakes every storage operation that changes what an Upload Plan was prepared from (served Budgets, Bank Accounts, Account Mappings, Left-Out Bank Accounts, Manually Kept Accounts, Operator Settings, the stored copy of the Budget App, the conclusions of a Comparison) raises state_version, and a refused one leaves it")
    fun everyStoredChangeRaisesTheStateVersionAndARefusalLeavesIt() = runBlocking {
        val stateVersion = InMemoryStateVersion()
        val connections = InMemoryConnectionStore(stateVersion)
        val registry = InMemoryPluginRegistryStore(stateVersion)
        val copy = InMemoryBudgetAppCopyStore(connections, InMemoryImportStatusStore(connections = connections))

        val steps = StateVersionScenario(
            registry, registry, InMemoryOperatorSettingsStore(stateVersion = stateVersion), connections, connections, copy, copy,
        ) { stateVersion.value }.run()

        assertEquals(StateVersionScenario.OPERATIONS, steps.map { it.operation })
        assertEquals(emptyList<String>(), steps.filter { it.mustRaise != it.rose }.map { "${it.operation}: state_version ${if (it.rose) "rose" else "did not rise"}" })
    }
}
