package slussen.testing

import kotlinx.coroutines.runBlocking
import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.CredentialRefusals
import slussen.core.credential.ServiceCredentials
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.plugin.PluginRegistration
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.RandomSource
import slussen.core.port.ServiceKey
import slussen.core.port.StartKind
import slussen.core.port.UnreadablePart
import slussen.core.settings.ConnectionsOverview
import slussen.core.status.ImportStatus
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.value.Secret
import java.util.Random

/**
 * The slice of Phase 7 wired the way the composition root wires it (ADR-001 §5), on the fakes of `:testing`: the
 * Integration layer of `docs/architecture/development.md` (Service, on fakes).
 */
class TestSlice(
    startKind: StartKind = StartKind.Normal,
    unreadableParts: List<UnreadablePart> = emptyList(),
) {
    val clock = MutableClock()
    val budgetApp = FakeBudgetApp()
    val feeds = FakeDataProvider(ExampleData.feedsProfile)
    val openBanking = FakeDataProvider(ExampleData.openBankingProfile)
    val wren = FakeDataProvider(ExampleData.wrenProfile)
    val fileFormat = FakeFilePreprocessor()

    val registry = InMemoryPluginRegistryStore()
    val vault = InMemoryCredentialVault()
    val coordinatorStore = InMemoryCoordinatorStore()
    val journalRecorder = InMemoryDecisionJournal()
    val settings = InMemoryOperatorSettingsStore()
    val providerRequests = InMemoryProviderRequestStore()
    val connectionStore = InMemoryConnectionStore()
    val offeredStore = InMemoryOfferedBankAccountStore()
    val importStatusStore = InMemoryImportStatusStore()
    val recoveryStore = InMemoryRecoveryStore()
    val startState = FixedStartState(startKind, unreadableParts)
    val backupSize = FixedBackupSizeMonitor()
    val random = Random(20261015L).let { seeded -> RandomSource { bytes -> seeded.nextBytes(bytes) } }

    val plugins: RegisteredPlugins = runBlocking {
        PluginRegistration(registry).register(budgetApp, listOf(feeds, openBanking, wren), listOf(fileFormat))
    }
    val coordinator = OperationCoordinator(coordinatorStore)
    val journal = DecisionJournal(journalRecorder, settings, clock)
    val refusals = CredentialRefusals()
    val credentials = ServiceCredentials(
        plugins, vault, coordinator, journal, settings, providerRequests, refusals, clock,
    )
    val connections = ConnectionsOverview(plugins, credentials, connectionStore, importStatusStore, settings, clock)
    val connect = BudgetsAndBankAccounts(
        plugins, credentials, connectionStore, offeredStore, coordinator, journal, random, clock,
    )
    val status = ImportStatus(
        budgetAppName = plugins.budgetApp.profile.name,
        dataProviders = plugins.dataProviders.map { it.stored },
        fileFormats = plugins.fileFormats,
        connections = connectionStore,
        stored = importStatusStore,
        providerRequests = providerRequests,
        credentials = credentials,
        recovery = recoveryStore,
        startState = startState,
        backupSize = backupSize,
        settings = settings,
        journal = journal,
        clock = clock,
    )

    val feedsKey: ServiceKey get() = plugins.dataProviders.single { it.plugin === feeds }.key
    val openBankingKey: ServiceKey get() = plugins.dataProviders.single { it.plugin === openBanking }.key
    val wrenKey: ServiceKey get() = plugins.dataProviders.single { it.plugin === wren }.key
    val feedsId: Long get() = feedsKey.dataProviderId!!
    val openBankingId: Long get() = openBankingKey.dataProviderId!!

    fun input(vararg values: Pair<String, String>): CredentialInput =
        CredentialInput(values.associate { (key, value) -> key to Secret(value) })

    /** Enters accepted credentials for the Budget App and the Data Provider [feeds]. */
    suspend fun enterCredentials() {
        credentials.enter(ServiceKey.budgetApp, input("token" to ExampleData.BUDGET_APP_TOKEN))
        credentials.enter(feedsKey, input("setupKey" to ExampleData.SETUP_KEY))
    }
}
