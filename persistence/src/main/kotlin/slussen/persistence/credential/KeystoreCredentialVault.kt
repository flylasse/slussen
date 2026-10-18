package slussen.persistence.credential

import slussen.core.port.CredentialVault
import slussen.core.port.ServiceKey
import slussen.core.port.StoredCredential
import slussen.persistence.device.ServiceCredentialDao
import slussen.persistence.device.ServiceCredentialEntity
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.time.Instant

/**
 * The credential vault of ADR-002 §2a, behind the core's port [CredentialVault] (ADR-001 §3): only `:persistence`
 * encrypts and decrypts; the plaintext exists only as a [Secret] in memory. Each `service_credential` row of
 * `device.db` holds one ciphertext of [CredentialCipher].
 */
class KeystoreCredentialVault(
    private val credentials: ServiceCredentialDao,
    private val cipher: CredentialCipher = CredentialCipher(KeystoreKey::getOrCreate),
) : CredentialVault {
    @OptIn(SensitiveApi::class)
    override suspend fun replace(service: ServiceKey, credential: Secret, acceptedAt: Instant) {
        val sealed = cipher.encrypt(credential.reveal())
        credentials.replace(
            ServiceCredentialEntity(dataProviderId = service.dataProviderId, secret = sealed, acceptedAt = acceptedAt),
        )
    }

    override suspend fun read(service: ServiceKey): StoredCredential? {
        val stored = credentials.find(service.dataProviderId) ?: return null
        return try {
            StoredCredential(Secret(cipher.decrypt(stored.secret)), stored.acceptedAt)
        } catch (e: CredentialUnreadable) {
            // ADR-002 §2a: a ciphertext that cannot be decrypted is treated as missing credentials.
            credentials.delete(service.dataProviderId)
            null
        }
    }

    override suspend fun remove(service: ServiceKey) {
        credentials.delete(service.dataProviderId)
    }
}
