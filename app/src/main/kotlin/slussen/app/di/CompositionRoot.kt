package slussen.app.di

import android.content.Context
import slussen.app.ui.Formats
import slussen.app.ui.UiServices
import slussen.budgetapp.ynab.YnabBudgetApp
import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.CredentialRefusals
import slussen.core.credential.ServiceCredentials
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.plugin.PluginRegistration
import slussen.core.port.RandomSource
import slussen.core.settings.ConnectionsOverview
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

/**
 * The only composition root, written by hand (ADR-001 §5): it lists the plug-ins of this build explicitly. Each
 * plug-in receives only the diagnostic [SlussenLog] at construction. It creates the one [OperationCoordinator]
 * (ADR-007 §1) and wires the core's services to the storage of `:persistence`.
 */
class CompositionRoot(log: SlussenLog) {
    val plugins: Plugins = Plugins(
        dataProviders = listOf(SimpleFinDataProvider(log), EnableBankingDataProvider(log)),
        budgetApp = YnabBudgetApp(log),
        filePreprocessors = listOf(HsaCsvFilePreprocessor(log)),
    )

    /**
     * Opens the storage (the start sequence of ADR-002 §6 included), registers the plug-ins (O-11) and creates the core's
     * services, before any operation is admitted.
     *
     * A plug-in whose declarations are not written yet is not registered: the `source_key`, the Request Limit and the
     * default Fetch Cooldown of SimpleFIN and Enable Banking, and the File Format of this build, are owed by an Operator
     * decision (Phase 7, Open). Each part is registered as soon as its part declares them; nothing else changes.
     */
    suspend fun start(context: Context, clock: Clock): UiServices {
        val storage = SlussenStorage.open(context, clock)
        val random = SecureRandom()
        val registered = PluginRegistration(storage.registry).register(
            plugins.budgetApp,
            plugins.dataProviders.filter { declared { it.profile } },
            plugins.filePreprocessors.filter { declared { it.profile } },
        )
        val coordinator = OperationCoordinator(storage.coordinator)
        val journal = DecisionJournal(storage.journalRecorder, storage.settings, clock)
        val credentials = ServiceCredentials(
            registered, storage.credentialVault, coordinator, journal, storage.settings, storage.providerRequests,
            CredentialRefusals(), clock,
        )
        return UiServices(
            credentials = credentials,
            connections = ConnectionsOverview(registered, credentials, storage.connections, storage.importStatus, storage.settings, clock),
            connect = BudgetsAndBankAccounts(
                registered, credentials, storage.connections, storage.offered, coordinator, journal,
                RandomSource { random.nextBytes(it) }, clock,
            ),
            status = ImportStatus(
                budgetAppName = registered.budgetApp.profile.name,
                dataProviders = registered.dataProviders.map { it.stored },
                fileFormats = registered.fileFormats,
                connections = storage.connections,
                stored = storage.importStatus,
                providerRequests = storage.providerRequests,
                credentials = credentials,
                recovery = storage.recovery,
                startState = storage.startState,
                backupSize = storage.backupSize,
                settings = storage.settings,
                journal = journal,
                clock = clock,
            ),
            formats = Formats(clock.zone),
            clock = clock,
        )
    }

    /** True when the part has written its declarations; a part still waiting for them throws `NotImplementedError`. */
    private fun declared(profile: () -> Any): Boolean = runCatching { profile() }.isSuccess
}

/** The plug-ins of this build: every Data Provider, the one Budget App and the one File Pre-processor (C-010). */
class Plugins(
    val dataProviders: List<DataProvider>,
    val budgetApp: BudgetApp,
    val filePreprocessors: List<FilePreprocessor>,
)
