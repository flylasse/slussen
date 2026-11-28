package slussen.app.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import slussen.app.platform.notification.AndroidNotificationDevice
import slussen.app.platform.notification.NewTransactionNotification
import slussen.app.schedule.FetchSchedule
import slussen.app.schedule.WorkManagerFetchSchedule
import slussen.app.platform.version.installedAppVersion
import slussen.app.ui.Formats
import slussen.app.ui.UiServices
import slussen.budgetapp.ynab.YnabBudgetApp
import slussen.core.comparison.Comparison
import slussen.core.comparison.ComparisonNotices
import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.consent.BankConsents
import slussen.app.ui.consent.ConsentProvider
import slussen.app.ui.consent.ConsentReturn
import slussen.core.credential.CredentialRefusals
import slussen.core.credential.ServiceCredentials
import slussen.core.fetch.FetchNotices
import slussen.core.fetch.FetchTransactions
import slussen.core.fileimport.ImportTransactionFile
import slussen.core.firstupload.FirstUploadMatching
import slussen.core.journal.DecisionJournal
import slussen.core.journal.JournalControl
import slussen.core.journal.JournalExport
import slussen.core.mapping.AccountMappings
import slussen.core.operation.OperationCoordinator
import slussen.core.plugin.PluginRegistration
import slussen.core.port.RandomSource
import slussen.core.review.ReviewDecisions
import slussen.core.transfer.TransferDecisions
import slussen.core.settings.SettingsOverview
import slussen.core.settings.DataProviderSettings
import slussen.core.settings.GeneralSettings
import slussen.core.settings.DescriptionLists
import slussen.core.status.BankAccountTransactions
import slussen.core.status.BudgetTransfers
import slussen.core.status.ImportStatus
import slussen.fileformat.hsacsv.HsaCsvFilePreprocessor
import slussen.persistence.SlussenStorage
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.provider.DataProvider
import slussen.provider.enablebanking.EnableBankingDataProvider
import slussen.provider.simplefin.SimpleFinDataProvider
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The only composition root, written by hand (ADR-001 §5): it lists the plug-ins of this build explicitly. Each
 * plug-in receives only the diagnostic [SlussenLog] at construction. It creates the one [OperationCoordinator]
 * (ADR-007 §1) and wires the core's services to the storage of `:persistence`.
 */
class CompositionRoot(private val log: SlussenLog, private val detailedLogging: DetailedLoggingSwitch = DetailedLoggingSwitch()) {
    val plugins: Plugins = Plugins(
        dataProviders = listOf(SimpleFinDataProvider(log), EnableBankingDataProvider(log)),
        budgetApp = YnabBudgetApp(log),
        filePreprocessors = listOf(HsaCsvFilePreprocessor(log)),
    )

    /**
     * Opens the storage (the start sequence of ADR-002 §6 included), registers every plug-in of this build with what its
     * part declares (O-11, ADR-001 §4.1, §4.3) and creates the core's services, before any operation is admitted. It runs
     * in every process of the app, the one a Scheduled Fetch's worker starts without a screen included: there it sets
     * the Fetch Schedule's work at the start (UC-001 BR-013) and keeps the New Transaction Notification following the
     * stored number in [scope] (UC-001 BR-014), and the worker uses the same services.
     */
    suspend fun start(context: Context, clock: Clock, scope: CoroutineScope): UiServices {
        val storage = SlussenStorage.open(context, clock)
        val appVersion = context.installedAppVersion()
        val random = SecureRandom()
        val registered = PluginRegistration(storage.registry).register(
            plugins.budgetApp,
            plugins.dataProviders,
            plugins.filePreprocessors,
        )
        val coordinator = OperationCoordinator(storage.coordinator)
        val journal = DecisionJournal(storage.journalRecorder, storage.settings, clock)
        val credentials = ServiceCredentials(
            registered, storage.credentialVault, coordinator, journal, storage.settings, storage.providerRequests,
            storage.fetches, CredentialRefusals(), clock,
        )
        val fetchNotices = FetchNotices()
        val comparisonNotices = ComparisonNotices()
        val comparison = Comparison(
            registered, credentials, storage.connections, storage.comparisons, storage.comparisons, coordinator, storage.settings,
            journal, clock, comparisonNotices,
        )
        // ADR-007 §3a, §8: a Comparison left between storing its read and its conclusions is concluded from the stored data before any other operation.
        comparison.recomputePending()
        // ADR-006 §3, UC-010 A5: the app's log follows the stored Detailed Logging, read at the start and after every change.
        detailedLogging.readFrom(storage.settings)
        val settingsOverview = SettingsOverview(
            registered, credentials, storage.connections, storage.offered, storage.importStatus, storage.settings, clock,
        )
        val status = ImportStatus(
            budgetAppName = registered.budgetApp.profile.name,
            currentDataProviders = { registered.dataProviders.map { it.stored } },
            fileFormats = registered.fileFormats,
            connections = storage.connections,
            stored = storage.importStatus,
            providerRequests = storage.providerRequests,
            credentials = credentials,
            firstUploadMatching = FirstUploadMatching(storage.firstUploadMatching),
            startState = storage.startState,
            backupSize = storage.backupSize,
            settings = storage.settings,
            journal = journal,
            clock = clock,
            firstRunNext = { settingsOverview.next() },
            fetchNotices = fetchNotices,
            comparisonNotices = comparisonNotices,
            periodReadOwed = { storage.comparisons.periodReadOwed(it) },
            offered = { storage.offered.offered() },
        )
        // UC-001 BR-013: Scheduled Fetches follow the Fetch Schedule, set by the first start of Slussen while the stored value is above 0, kept by a later start; the first run falls due a whole period later, so opening Slussen never fetches (BR-001).
        val fetchSchedule = FetchSchedule(WorkManagerFetchSchedule(context)) { storage.settings.current().scheduledFetchesPerDay }
        fetchSchedule.atStart()
        val notificationDevice = AndroidNotificationDevice(context)
        val notification = NewTransactionNotification(notificationDevice) { status.newTransactionNumber() }
        // UC-001 BR-014: Slussen works the number out from stored data at every start and after every operation of the coordinator.
        scope.launch { notification.follow(coordinator.ended) }
        // UC-007 BR-011: Slussen's own return address is formed from the build's application id, which is the package name; no Data Provider's name is in it (K-04).
        val returnAddress = ConsentReturn.ownReturnAddress(context.packageName)
        // UC-008 BR-001: Grant Bank Consent (UC-007) is constructed without contacting anything; it reads Bank Consent operations only of a Data Provider that needs a Bank Consent.
        val bankConsents = BankConsents(
            registered, credentials, storage.consents, storage.connections, storage.offered, storage.fetches, storage.providerRequests,
            coordinator, storage.settings, clock, RandomSource { random.nextBytes(it) }, returnAddress, log,
        )
        return UiServices(
            credentials = credentials,
            settingsOverview = settingsOverview,
            connect = BudgetsAndBankAccounts(
                registered, credentials, storage.connections, storage.offered, storage.comparisons, coordinator, journal,
                RandomSource { random.nextBytes(it) }, clock,
            ),
            providerSettings = DataProviderSettings(registered, storage.registry, coordinator, journal),
            generalSettings = GeneralSettings(
                storage.settings, comparison, storage.connections, storage.comparisons, journal,
                applied = { detailedLogging.set(it.detailedLogging) },
            ),
            descriptionLists = DescriptionLists(storage.settings, comparison, storage.connections, storage.comparisons, journal),
            // The Decision Journal's own control (UC-010 A2-A4), on journal.db through its own thread (ADR-002 §9).
            journalControl = JournalControl(storage.settings, journal, storage.journalControl, coordinator),
            // For UC-011: the Journal Export, the one reader of journal.db's entries (Konsist K-07), written off the main thread.
            journalExport = JournalExport(
                registered, credentials, storage.connections, storage.comparisons, storage.settings, coordinator, storage.journalReader,
                clock, versionName = appVersion.versionName,
            ),
            mappings = AccountMappings(registered, storage.connections, storage.mappings, storage.offered, coordinator, journal, comparison, clock),
            status = status,
            // Step 5 of UC-008: the weeks of a Bank Account's list start on the device locale's first day (survey §5 guess 6).
            bankAccountTransactions = BankAccountTransactions(
                status, storage.connections, storage.fetches, storage.comparisons, storage.settings,
                registered.budgetApp.profile.matchingDateWindowDays, clock, WeekFields.of(Locale.getDefault()).firstDayOfWeek,
            ),
            transfers = BudgetTransfers(
                status, storage.connections, storage.comparisons, storage.settings, registered.budgetApp.profile.matchingDateWindowDays, clock,
            ),
            fetch = FetchTransactions(
                registered, credentials, storage.connections, storage.offered, storage.fetches, storage.providerRequests,
                coordinator, storage.settings, journal, clock, fetchNotices, comparison, storage.consents,
            ),
            // For UC-002: Import a Transaction File through the File Pre-processor of its File Format, with its Comparison (step 9).
            fileImport = ImportTransactionFile(
                registered, plugins.filePreprocessors, storage.connections, storage.fileImports, storage.fetches, coordinator, journal, clock, comparison,
            ),
            // For UC-004, UC-005: the decisions, each one storage operation admitted by the coordinator (O-13, ADR-007 §2).
            review = ReviewDecisions(
                storage.connections, storage.reviews, storage.comparisons, coordinator, storage.settings, journal, clock,
                registered.budgetApp.profile.matchingDateWindowDays, registered.budgetApp.profile.refusesReusedImportIdentifier,
            ),
            transferDecisions = TransferDecisions(
                storage.connections, storage.transfers, storage.comparisons, comparison, storage.settings, journal, clock,
                registered.budgetApp.profile.matchingDateWindowDays,
            ),
            comparison = comparison,
            formats = Formats(clock.zone),
            clock = clock,
            appVersion = appVersion,
            running = coordinator.running,
            ended = coordinator.ended,
            budgetAppName = registered.budgetApp.profile.name,
            fetchSchedule = fetchSchedule,
            notificationsAllowed = notificationDevice::allowed,
            updateNotification = notification::update,
            bankConsents = bankConsents,
            consentProviders = registered.dataProviders.filter { it.stored.needsBankConsent }.map { ConsentProvider(it.key, it.stored.name) },
            applicationReturnAddress = returnAddress,
        )
    }

}

/** The plug-ins of this build: every Data Provider, the one Budget App and the one File Pre-processor (C-010). */
class Plugins(
    val dataProviders: List<DataProvider>,
    val budgetApp: BudgetApp,
    val filePreprocessors: List<FilePreprocessor>,
)

/**
 * What the app's log reads Detailed Logging from (UC-010 A5, BR-005, ADR-006 §3): the stored Operator Setting, read at
 * the start and set again by every change of it. Off until the stored value is read, its default
 * (`OPERATOR_SETTINGS.detailed_logging`), and off when it cannot be read, so that no transaction data reaches a device
 * log by mistake.
 */
class DetailedLoggingSwitch {
    private val on = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isOn(): Boolean = on.get()

    fun set(value: Boolean) = on.set(value)

    suspend fun readFrom(settings: slussen.core.port.OperatorSettingsStore) {
        on.set(
            try {
                settings.current().detailedLogging
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                false
            },
        )
    }
}
