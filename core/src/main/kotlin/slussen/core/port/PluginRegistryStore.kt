package slussen.core.port

/** What a Data Provider's part declares (`DataProviderProfile`, ADR-001 §4.1), as O-11 stores it. */
data class DataProviderRegistration(
    val name: String,
    val sourceKey: String,
    val givesStableReference: Boolean,
    val needsBankConsent: Boolean,
    val defaultFetchCooldownMinutes: Int,
    val requestLimit: Int,
)

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
    /** The Request Limit, always the value the part declares; not an Operator Setting (FR-017). */
    val requestLimit: Int,
)

/** `FILE_FORMAT` (entity model) as stored. */
data class StoredFileFormat(val id: Long, val name: String, val sourceKey: String)

/** The registered rows after O-11. */
data class RegisteredRows(val dataProviders: List<StoredDataProvider>, val fileFormats: List<StoredFileFormat>)

/**
 * O-11 of ADR-002 §3: at every start, before any operation is admitted, one `data_provider` row per Data Provider of
 * the build and one `file_format` row per File Pre-processor, matched by `source_key`, in one transaction. A new
 * `data_provider` row gets the declared default Fetch Cooldown, an existing row keeps the Operator's value;
 * `request_limit` is always set from the part. Nothing is ever deleted.
 */
interface PluginRegistryStore {
    suspend fun reconcile(
        dataProviders: List<DataProviderRegistration>,
        fileFormats: List<FileFormatRegistration>,
    ): RegisteredRows
}
