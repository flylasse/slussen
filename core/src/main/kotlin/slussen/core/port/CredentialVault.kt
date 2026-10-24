package slussen.core.port

import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * The port through which the core keeps the service credential of one service (ADR-001 §3): encrypted, stored, read
 * and deleted by `:persistence` with the non-exportable Android Keystore key (ADR-002 §2a). The plaintext exists only
 * as a [Secret] in memory.
 */
interface CredentialVault {
    /** Stores [credential], which the service accepted at [acceptedAt], in place of the earlier one of [service]. */
    suspend fun replace(service: ServiceKey, credential: Secret, acceptedAt: Instant)

    /**
     * The stored credential of [service], or null when there is none. A stored credential that cannot be decrypted
     * (for example because the Keystore key is gone) is deleted and read as none (ADR-002 §2a).
     */
    suspend fun read(service: ServiceKey): StoredCredential?

    /** Deletes the stored credential of [service], if any. */
    suspend fun remove(service: ServiceKey)
}

/**
 * Which service a credential belongs to: one Data Provider by its `DATA_PROVIDER.id`, or the Budget App when
 * [dataProviderId] is null (`SERVICE_CREDENTIAL.data_provider_id`, "empty for the Budget App").
 */
data class ServiceKey(val dataProviderId: Long?) {
    companion object {
        val budgetApp: ServiceKey = ServiceKey(null)

        fun dataProvider(id: Long): ServiceKey = ServiceKey(id)
    }
}

/** A credential read back from the vault, with the time the service accepted it (`SERVICE_CREDENTIAL.accepted_at`). */
data class StoredCredential(val credential: Secret, val acceptedAt: Instant)
