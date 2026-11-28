package slussen.testing

import kotlinx.coroutines.runBlocking
import slussen.core.comparison.Comparison
import slussen.core.comparison.ComparisonNotices
import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.consent.BankConsents
import slussen.core.consent.LeftBehindBanks
import slussen.core.credential.CredentialRefusals
import slussen.core.credential.ServiceCredentials
import slussen.core.fetch.FetchNotices
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.fetch.FetchTransactions
import slussen.core.fileimport.ImportTransactionFile
import slussen.core.firstupload.FirstUploadMatching
import slussen.core.journal.DecisionJournal
import slussen.core.journal.JournalControl
import slussen.core.journal.JournalExport
import slussen.core.mapping.AccountMappings
import slussen.core.operation.OperationCoordinator
import slussen.core.plugin.PluginRegistration
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.RandomSource
import slussen.core.port.ServiceKey
import slussen.core.port.StartKind
import slussen.core.port.UnreadablePart
import slussen.core.review.ReviewDecisions
import slussen.core.settings.SettingsOverview
import slussen.core.settings.DataProviderSettings
import slussen.core.settings.GeneralSettings
import slussen.core.settings.DescriptionLists
import slussen.core.status.BankAccountTransactions
import slussen.core.status.BudgetTransfers
import slussen.core.status.ImportStatus
import slussen.core.transfer.TransferDecisions
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.value.Secret
import java.time.DayOfWeek
import java.util.Random
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The slices of Phase 7, 7a and 8a wired the way the composition root wires it (ADR-001 §5), on the fakes of `:testing`: the
 * Integration layer of `docs/architecture/stack.md` (Service, on fakes).
 */
class TestSlice(
    startKind: StartKind = StartKind.Normal,
    unreadableParts: List<UnreadablePart> = emptyList(),
    /** Computes the Import Identifiers O-1 stores; a test passes a test-only digest to plant a collision (ADR-008 §5). */
    identify: (ImportIdentifierInput) -> String = ImportIdentifierProtocol::importIdentifier,
    /** The recorder the core records through; the fake [journalRecorder] unless a test gives the real one of `:persistence`. */
    recorder: DecisionJournalRecorder? = null,
    /**
     * The File Pre-processor the slice registers and imports through; the fake [fileFormat] unless a test gives a real
     * one of a `:fileformat-*` module (UC-002 with a real File Format).
     */
    filePreprocessor: slussen.pluginapi.fileformat.FilePreprocessor? = null,
) {
    val clock = MutableClock()
    val budgetApp = FakeBudgetApp()
    val feeds = FakeDataProvider(ExampleData.feedsProfile)
    /** The Bank Consent operations of [openBanking], the Data Provider of the slice that needs a Bank Consent (UC-007). */
    val bankConsent = ScriptedBankConsent()
    val openBanking = FakeDataProvider(ExampleData.openBankingProfile, bankConsent = bankConsent)
    val wren = FakeDataProvider(ExampleData.wrenProfile)
    val fileFormat = FakeFilePreprocessor()
    private val registeredFilePreprocessor: slussen.pluginapi.fileformat.FilePreprocessor = filePreprocessor ?: fileFormat

    /** The one `state_version` every fake of the slice raises, as the tables of `slussen.db` share one (ADR-007 §6 step 2). */
    val stateVersion = InMemoryStateVersion()
    val registry = InMemoryPluginRegistryStore(stateVersion)
    val vault = InMemoryCredentialVault()
    val coordinatorStore = InMemoryCoordinatorStore()
    val journalRecorder = InMemoryDecisionJournal()
    val providerRequests = InMemoryProviderRequestStore()
    val connectionStore = InMemoryConnectionStore(stateVersion)
    val offeredStore = InMemoryOfferedBankAccountStore()
    val importStatusStore = InMemoryImportStatusStore(identify, connectionStore)

    /** O-10 of `device.db` (UC-007); its Bank Consents are the ones the import status reads. */
    val consentStore = InMemoryConsentStore(importStatusStore.consents)
    val copyStore = InMemoryBudgetAppCopyStore(connectionStore, importStatusStore)
    val settings = InMemoryOperatorSettingsStore(stateVersion = stateVersion, connections = connectionStore, copies = copyStore, coordinator = coordinatorStore)
    val firstUploadMatchingStore = InMemoryFirstUploadMatchingStore()
    val startState = FixedStartState(startKind, unreadableParts)
    val backupSize = FixedBackupSizeMonitor()
    val random = Random(20261015L).let { seeded -> RandomSource { bytes -> seeded.nextBytes(bytes) } }

    val plugins: RegisteredPlugins = runBlocking {
        PluginRegistration(registry).register(budgetApp, listOf(feeds, openBanking, wren), listOf(registeredFilePreprocessor))
    }
    val coordinator = OperationCoordinator(coordinatorStore)
    val journal = DecisionJournal(recorder ?: journalRecorder, settings, clock)

    /** UC-010 A2-A4: the Decision Journal's own control, on the same fake journal database as the recorder. */
    val journalControl = JournalControl(settings, journal, journalRecorder, coordinator)
    val refusals = CredentialRefusals()
    val credentials = ServiceCredentials(
        plugins, vault, coordinator, journal, settings, providerRequests, importStatusStore, refusals, clock,
    )
    /**
     * UC-011: the Journal Export, reading the fake journal database the recorder writes, on the test's own thread
     * (no dispatcher of its own), written by an invented build of version 9.8.7.
     */
    val journalExport = JournalExport(
        plugins, credentials, connectionStore, copyStore, settings, coordinator, journalRecorder, clock, versionName = "9.8.7",
        io = EmptyCoroutineContext,
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
    /** UC-002: Import a Transaction File, through the fake File Pre-processor [fileFormat] or the one given, with its Comparison (step 9). */
    val fileImport = ImportTransactionFile(
        plugins, listOf(registeredFilePreprocessor), connectionStore, importStatusStore, importStatusStore, coordinator, journal, clock, comparison,
    )
    val settingsOverview = SettingsOverview(plugins, credentials, connectionStore, offeredStore, importStatusStore, settings, clock)
    val connect = BudgetsAndBankAccounts(
        plugins, credentials, connectionStore, offeredStore, copyStore, coordinator, journal, random, clock,
    )
    val providerSettings = DataProviderSettings(plugins, registry, coordinator, journal)
    /** What the app's log reads Detailed Logging from (ADR-006): the value the last change stored, from the stored record. */
    var detailedLoggingApplied: Boolean = settings.settings.detailedLogging
    val generalSettings = GeneralSettings(settings, comparison, connectionStore, copyStore, journal, applied = { detailedLoggingApplied = it.detailedLogging })
    /** UC-010 A7, A8: the Card Payment Descriptions and Bank Transfer Descriptions, on the stored copy's lists. */
    val descriptionLists = DescriptionLists(settings, comparison, connectionStore, copyStore, journal)
    /** UC-004: the Review Decisions, on the stored data the Comparison concludes from. */
    val review = ReviewDecisions(
        connectionStore, copyStore, copyStore, coordinator, settings, journal, clock,
        plugins.budgetApp.profile.matchingDateWindowDays, plugins.budgetApp.profile.refusesReusedImportIdentifier,
    )
    /** UC-005: the Transfer Decisions, on the stored data the Comparison concludes from. */
    val transferDecisions = TransferDecisions(
        connectionStore, copyStore.transferStore, copyStore, comparison, settings, journal, clock, plugins.budgetApp.profile.matchingDateWindowDays,
    )
    /** The log the Bank Consent operations write to, kept for tests that search it (UC-007 BR-006). */
    val consentLog = RecordingSlussenLog(detailedLogging = true)

    /** UC-007: Grant Bank Consent, with the return address of an invented application id (BR-011). */
    val bankConsents = BankConsents(
        plugins, credentials, consentStore, connectionStore, offeredStore, importStatusStore, providerRequests, coordinator,
        settings, clock, random, ExampleData.CONSENT_RETURN_ADDRESS, consentLog,
    )

    /** The removal at a start of the Banks an attempt to grant a Bank Consent left behind (ADR-002 §3 O-10). */
    val leftBehindBanks = LeftBehindBanks(connectionStore, consentStore, offeredStore)
    val mappings = AccountMappings(plugins, connectionStore, connectionStore, offeredStore, coordinator, journal, comparison, clock)
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
        periodReadOwed = { copyStore.periodReadOwed(it) },
        offered = { offeredStore.offered() },
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
