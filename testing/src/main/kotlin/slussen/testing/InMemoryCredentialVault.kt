package slussen.testing

import slussen.core.port.CredentialVault
import slussen.core.port.ServiceKey
import slussen.core.port.StoredCredential
import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * An in-memory fake of the [CredentialVault] port, for tests of the core. A test holds a removal with [beforeRemove], to
 * see it while it runs, and makes it fail with [failsToRemove], as a storage that cannot be written would.
 */
class InMemoryCredentialVault : CredentialVault {
    private val stored = mutableMapOf<ServiceKey, StoredCredential>()
    var beforeRemove: suspend () -> Unit = {}
    var failsToRemove: Boolean = false

    override suspend fun replace(service: ServiceKey, credential: Secret, acceptedAt: Instant) {
        stored[service] = StoredCredential(credential, acceptedAt)
    }

    override suspend fun read(service: ServiceKey): StoredCredential? = stored[service]

    override suspend fun remove(service: ServiceKey) {
        beforeRemove()
        if (failsToRemove) throw IllegalStateException("The invented storage of this test cannot be written.")
        stored.remove(service)
    }
}
