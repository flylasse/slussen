package slussen.core.plugin

import slussen.core.port.DataProviderRegistration
import slussen.core.port.FileFormatRegistration
import slussen.core.port.PluginRegistryStore
import slussen.core.port.ServiceKey
import slussen.core.port.StoredDataProvider
import slussen.core.port.StoredFileFormat
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.provider.DataProvider

/**
 * A Data Provider of this build with the row O-11 stored for it. [stored] follows the Operator Settings the Operator
 * changes while the app runs (UC-010), so that every gate and page of this process sees the stored value.
 */
class RegisteredDataProvider(stored: StoredDataProvider, val plugin: DataProvider) {
    @Volatile
    var stored: StoredDataProvider = stored
        internal set

    val key: ServiceKey get() = ServiceKey.dataProvider(stored.id)
}

/** The plug-ins of this build after O-11, in the order the composition root lists them (ADR-001 §5). */
class RegisteredPlugins(
    val budgetApp: BudgetApp,
    val dataProviders: List<RegisteredDataProvider>,
    val fileFormats: List<StoredFileFormat>,
) {
    fun dataProvider(key: ServiceKey): RegisteredDataProvider =
        dataProviders.firstOrNull { it.stored.id == key.dataProviderId }
            ?: throw IllegalArgumentException("No Data Provider of this build has the id ${key.dataProviderId}.")

    /** Every service whose credentials the Operator enters: the Budget App first, then each Data Provider. */
    val serviceKeys: List<ServiceKey> get() = listOf(ServiceKey.budgetApp) + dataProviders.map { it.key }

    fun nameOf(key: ServiceKey): String =
        if (key == ServiceKey.budgetApp) budgetApp.profile.name else dataProvider(key).stored.name
}

/**
 * Registers the plug-ins of this build at every start, before any operation is admitted (ADR-002 §2, O-11): each Data
 * Provider and File Pre-processor by its declared `source_key`, with the Request Limit (published, or a default with its
 * range) and the default Fetch Cooldown its part declares.
 */
class PluginRegistration(private val store: PluginRegistryStore) {
    suspend fun register(
        budgetApp: BudgetApp,
        dataProviders: List<DataProvider>,
        filePreprocessors: List<FilePreprocessor>,
    ): RegisteredPlugins {
        val declared = dataProviders.map { plugin ->
            val profile = plugin.profile
            // UC-012 BR-011: The Request Limit, published or a default the Operator can change, and the default Fetch Cooldown of each Data Provider are declared by its part, not given to the build.
            plugin to DataProviderRegistration(
                name = profile.name,
                sourceKey = profile.sourceKey,
                givesStableReference = profile.givesStableReference,
                needsBankConsent = profile.needsBankConsent,
                defaultFetchCooldownMinutes = profile.defaultFetchCooldownMinutes,
                requestLimit = profile.requestLimit,
            )
        }
        val formats = filePreprocessors.map { FileFormatRegistration(it.profile.name, it.profile.sourceKey) }
        val rows = store.reconcile(declared.map { it.second }, formats)
        val registered = declared.map { (plugin, registration) ->
            RegisteredDataProvider(rows.dataProviders.single { it.sourceKey == registration.sourceKey }, plugin)
        }
        return RegisteredPlugins(budgetApp, registered, rows.fileFormats)
    }
}
