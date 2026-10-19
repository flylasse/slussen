package slussen.core.port

import slussen.pluginapi.provider.RequestLimitDeclaration

/** What a Data Provider's part declares (`DataProviderProfile`, ADR-001 §4.1), as O-11 stores it. */
data class DataProviderRegistration(
    val name: String,
    val sourceKey: String,
    val givesStableReference: Boolean,
    val needsBankConsent: Boolean,
    val defaultFetchCooldownMinutes: Int,
    val requestLimit: RequestLimitDeclaration,
) {
    /**
     * The Request Limit O-11 stores for this part at a start (ADR-002 §2, S-31), given the [stored] one of an existing
     * row, or null for a new row.
     */
    fun requestLimitAtStart(stored: StoredRequestLimit?): StoredRequestLimit = when (val declared = requestLimit) {
        // UC-001 BR-007: A Request Limit the Data Provider publishes is set from its part at every start and is not an Operator Setting.
        is RequestLimitDeclaration.Published -> StoredRequestLimit(declared.requests, published = true)
        // UC-001 BR-007: A Request Limit the Data Provider does not publish keeps the Operator's value while it lies in the declared range, otherwise the part's default.
        is RequestLimitDeclaration.NotPublished ->
            if (stored != null && !stored.published && stored.requests in declared.range) {
                stored
            } else {
                StoredRequestLimit(declared.defaultRequests, published = false)
            }
    }
}

/** `DATA_PROVIDER.request_limit` with `request_limit_published` (entity model). */
data class StoredRequestLimit(val requests: Int, val published: Boolean)

/** What a File Pre-processor's part declares (`FileFormatProfile`, ADR-001 §4.3), as O-11 stores it. */
data class FileFormatRegistration(val name: String, val sourceKey: String)

/** `DATA_PROVIDER` (entity model) as stored. */
data class StoredDataProvider(
    val id: Long,
    val name: String,
    val sourceKey: String,
    val givesStableReference: Boolean,
    val needsBankConsent: Boolean,
    /** The Fetch Cooldown, an Operator Setting whose default the part declares (UC-001 BR-006). */
    val fetchCooldownMinutes: Int,
    /**
     * The Request Limit: the limit the Data Provider publishes, set from the part, or, when [requestLimitPublished] is
     * false, an Operator Setting within the range the part declares (FR-017, UC-001 BR-007).
     */
    val requestLimit: Int,
    val requestLimitPublished: Boolean,
)

/** `FILE_FORMAT` (entity model) as stored. */
data class StoredFileFormat(val id: Long, val name: String, val sourceKey: String)

/** The registered rows after O-11. */
data class RegisteredRows(val dataProviders: List<StoredDataProvider>, val fileFormats: List<StoredFileFormat>)

/**
 * O-11 of ADR-002 §3: at every start, before any operation is admitted, one `data_provider` row per Data Provider of
 * the build and one `file_format` row per File Pre-processor, matched by `source_key`, in one transaction. A new
 * `data_provider` row gets the declared default Fetch Cooldown, an existing row keeps the Operator's value; the Request
 * Limit follows [DataProviderRegistration.requestLimitAtStart]. Nothing is ever deleted.
 */
interface PluginRegistryStore {
    suspend fun reconcile(
        dataProviders: List<DataProviderRegistration>,
        fileFormats: List<FileFormatRegistration>,
    ): RegisteredRows
}

/**
 * The Operator Settings kept per Data Provider in `DATA_PROVIDER` (ADR-002 §2, O-13 `SettingsStore`): here the
 * Request Limit of a Data Provider that publishes none (UC-010 BR-002, A11).
 */
interface DataProviderSettingsStore {
    /** Stores [requests] as the Request Limit of the Data Provider [dataProviderId], one update. */
    suspend fun storeRequestLimit(dataProviderId: Long, requests: Int)
}
