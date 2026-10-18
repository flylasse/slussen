package slussen.testing

import slussen.core.port.CredentialVault
import slussen.core.port.ServiceKey
import slussen.core.port.StoredCredential
import slussen.pluginapi.value.Secret
import java.time.Instant

/** An in-memory fake of the [CredentialVault] port, for tests of the core. */
class InMemoryCredentialVault : CredentialVault {
    private val stored = mutableMapOf<ServiceKey, StoredCredential>()

    override suspend fun replace(service: ServiceKey, credential: Secret, acceptedAt: Instant) {
        stored[service] = StoredCredential(credential, acceptedAt)
    }

    override suspend fun read(service: ServiceKey): StoredCredential? = stored[service]

    override suspend fun remove(service: ServiceKey) {
        stored.remove(service)
    }
}
