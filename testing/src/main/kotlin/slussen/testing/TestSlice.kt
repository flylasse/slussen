package slussen.testing

import kotlinx.coroutines.runBlocking
import slussen.core.comparison.Comparison
import slussen.core.comparison.ComparisonNotices
import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.CredentialRefusals
import slussen.core.credential.ServiceCredentials
import slussen.core.fetch.FetchNotices
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.fetch.FetchTransactions
import slussen.core.firstupload.FirstUploadMatching
import slussen.core.journal.DecisionJournal
import slussen.core.mapping.AccountMappings
import slussen.core.operation.OperationCoordinator
import slussen.core.plugin.PluginRegistration
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.RandomSource
import slussen.core.port.ServiceKey
import slussen.core.port.StartKind
import slussen.core.port.UnreadablePart
import slussen.core.review.ReviewDecisions
import slussen.core.settings.SettingsOverview
import slussen.core.settings.DataProviderSettings
import slussen.core.settings.GeneralSettings
import slussen.core.status.BankAccountTransactions
import slussen.core.status.BudgetTransfers
import slussen.core.status.ImportStatus
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.value.Secret
import java.time.DayOfWeek
import java.util.Random

/**
 * The slices of Phase 7, 7a and 8a wired the way the composition root wires it (ADR-001 §5), on the fakes of `:testing`: the
 * Integration layer of `docs/architecture/stack.md` (Service, on fakes).
 */
class TestSlice(
    startKind: StartKind = StartKind.Normal,
    unreadableParts: List<UnreadablePart> = emptyList(),
    /** Computes the Import Identifiers O-1 stores; a test passes a test-only digest to plant a collision (ADR-008 §5). */
    identify: (ImportIdentifierInput) -> String = ImportIdentifierProtocol::importIdentifier,
) {
    val clock = MutableClock()
    val budgetApp = FakeBudgetApp()
    val feeds = FakeDataProvider(ExampleData.feedsProfile)
    val openBanking = FakeDataProvider(ExampleData.openBankingProfile)
    val wren = FakeDataProvider(ExampleData.wrenProfile)
    val fileFormat = FakeFilePreprocessor()

    /** The one `state_version` every fake of the slice raises, as the tables of `slussen.db` share one (ADR-007 §6 step 2). */
    val stateVersion = InMemoryStateVersion()
    val registry = InMemoryPluginRegistryStore(stateVersion)
    val vault = InMemoryCredentialVault()
    val coordinatorStore = InMemoryCoordinatorStore()
    val journalRecorder = InMemoryDecisionJournal()
    val settings = InMemoryOperatorSettingsStore(stateVersion = stateVersion)
    val providerRequests = InMemoryProviderRequestStore()
    val connectionStore = InMemoryConnectionStore(stateVersion)
    val offeredStore = InMemoryOfferedBankAccountStore()
    val importStatusStore = InMemoryImportStatusStore(identify, connectionStore)
    val copyStore = InMemoryBudgetAppCopyStore(connectionStore, importStatusStore)
    val firstUploadMatchingStore = InMemoryFirstUploadMatchingStore()
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
        plugins, vault, coordinator, journal, settings, providerRequests, importStatusStore, refusals, clock,
    )
    val fetchNotices = FetchNotices()
    val comparisonNotices = ComparisonNotices()
    val comparison = Comparison(
        plugins, credentials, connectionStore, copyStore, copyStore, coordinator, settings, journal, clock, comparisonNotices,
    )
    val fetch = FetchTransactions(
        plugins, credentials, connectionStore, offeredStore, importStatusStore, providerRequests, coordinator, settings, journal,
        clock, fetchNotices, comparison,
    )
    val settingsOverview = SettingsOverview(plugins, credentials, connectionStore, offeredStore, importStatusStore, settings, clock)
    val connect = BudgetsAndBankAccounts(
        plugins, credentials, connectionStore, offeredStore, copyStore, coordinator, journal, random, clock,
    )
    val providerSettings = DataProviderSettings(plugins, registry, coordinator, journal)
    val generalSettings = GeneralSettings(settings, coordinator, journal)
    /** UC-004: the Review Decisions, on the stored data the Comparison concludes from. */
    val review = ReviewDecisions(
        connectionStore, copyStore, copyStore, coordinator, settings, journal, clock,
        plugins.budgetApp.profile.matchingDateWindowDays, plugins.budgetApp.profile.refusesReusedImportIdentifier,
    )
    val mappings = AccountMappings(plugins, connectionStore, connectionStore, offeredStore, coordinator, journal)
    val status = ImportStatus(
        budgetAppName = plugins.budgetApp.profile.name,
        currentDataProviders = { plugins.dataProviders.map { it.stored } },
        fileFormats = plugins.fileFormats,
        connections = connectionStore,
        stored = importStatusStore,
        providerRequests = providerRequests,
        credentials = credentials,
        firstUploadMatching = FirstUploadMatching(firstUploadMatchingStore),
        startState = startState,
        backupSize = backupSize,
        settings = settings,
        journal = journal,
        clock = clock,
        firstRunNext = { settingsOverview.next() },
        fetchNotices = fetchNotices,
        comparisonNotices = comparisonNotices,
    )

    /** UC-008 step 5: the transactions of one Bank Account with their statuses and reasons, weeks from Monday. */
    val bankAccountTransactions = BankAccountTransactions(
        status, connectionStore, importStatusStore, copyStore, settings, plugins.budgetApp.profile.matchingDateWindowDays, clock,
        DayOfWeek.MONDAY,
    )

    /** UC-005 step 1: the Transfers of one Budget with why each is guessed and whether it is eligible. */
    val budgetTransfers = BudgetTransfers(status, connectionStore, copyStore, settings, plugins.budgetApp.profile.matchingDateWindowDays, clock)

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
