package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.ServiceKey
import slussen.persistence.credential.KeystoreCredentialVault
import slussen.persistence.credential.KeystoreKey
import slussen.persistence.device.ServiceCredentialEntity
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.security.KeyStore
import java.time.Instant
import javax.crypto.SecretKey

/**
 * Evidence E-13 of ADR-005 §7 on a device or emulator: service credentials are stored only as ciphertext in
 * `device.db` under a non-exportable Android Keystore key, `device.db` lies where Android never backs up, and a
 * ciphertext that cannot be decrypted is treated as missing credentials. The backup and restore with `adb shell bmgr`
 * is the Operator's phone procedure of E-13.
 */
@DisplayName("UC-014: Manage Service Credentials, on the device")
class UC014ManageServiceCredentialsPersistenceIT {
    private val planted = "PLANTED-DEVICE-CREDENTIAL-3e7d"
    private val acceptedAt = Instant.parse("2026-10-15T12:00:00Z")

    @BeforeEach
    fun freshInstallation() {
        DeviceFiles.wipe()
        KeyStore.getInstance(KeystoreKey.PROVIDER).apply { load(null) }.deleteEntry(KeystoreKey.ALIAS)
    }

    @OptIn(SensitiveApi::class)
    private fun reveal(secret: Secret) = secret.reveal()

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-003"], requirements = ["NFR-021"])
    @DisplayName("E-13: an accepted credential is stored only as ciphertext in device.db and reads back unchanged")
    fun credentialIsStoredOnlyAsCiphertext() = runBlocking {
        val device = SlussenDatabases.openDevice(DeviceFiles.context)
        val vault = KeystoreCredentialVault(device.serviceCredentials())

        vault.replace(ServiceKey.budgetApp, Secret(planted), acceptedAt)

        val stored = device.serviceCredentials().find(null)!!
        assertFalse(planted in stored.secret)
        assertEquals(planted, reveal(vault.read(ServiceKey.budgetApp)!!.credential))
        device.close()
        val fileBytes = SlussenDatabases.deviceFile(DeviceFiles.context).readBytes()
        assertFalse(String(fileBytes, Charsets.ISO_8859_1).contains(planted))
        assertFalse(String(fileBytes, Charsets.UTF_16LE).contains(planted))
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-003"], requirements = ["NFR-021"])
    @DisplayName("E-13: the key is an Android Keystore key whose material cannot be exported")
    fun keyIsANonExportableKeystoreKey() {
        val key = KeystoreKey.getOrCreate()

        val stored = KeyStore.getInstance(KeystoreKey.PROVIDER).apply { load(null) }.getKey(KeystoreKey.ALIAS, null)
        assertTrue(stored is SecretKey)
        assertNull(key.encoded)
        assertNull((stored as SecretKey).encoded)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-009", "NFR-021"])
    @DisplayName("E-13: device.db lies in noBackupFilesDir, which Android never backs up or transfers")
    fun deviceDatabaseIsNeverBackedUp() {
        val device = SlussenDatabases.deviceFile(DeviceFiles.context).canonicalFile
        val noBackup = DeviceFiles.context.noBackupFilesDir.canonicalFile

        assertEquals(noBackup, device.parentFile)
        assertFalse(device.path.startsWith(SlussenDatabases.mainFile(DeviceFiles.context).parentFile!!.canonicalPath + "/"))
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-004", "BR-005"], requirements = ["NFR-021"])
    @DisplayName("E-13: a planted ciphertext that cannot be decrypted is treated as missing credentials and deleted")
    fun undecryptableCiphertextIsMissing() = runBlocking {
        val device = SlussenDatabases.openDevice(DeviceFiles.context)
        val vault = KeystoreCredentialVault(device.serviceCredentials())
        KeystoreKey.getOrCreate()
        device.serviceCredentials().replace(
            ServiceCredentialEntity(dataProviderId = 7, secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", acceptedAt = acceptedAt),
        )

        assertNull(vault.read(ServiceKey.dataProvider(7)))
        assertNull(device.serviceCredentials().find(7))
        device.close()
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-004"], requirements = ["NFR-021"])
    @DisplayName("E-13: when the Keystore key is gone, as after a reinstall, the stored credential is missing")
    fun credentialWithoutItsKeyIsMissing() = runBlocking {
        val device = SlussenDatabases.openDevice(DeviceFiles.context)
        val vault = KeystoreCredentialVault(device.serviceCredentials())
        vault.replace(ServiceKey.budgetApp, Secret(planted), acceptedAt)

        KeyStore.getInstance(KeystoreKey.PROVIDER).apply { load(null) }.deleteEntry(KeystoreKey.ALIAS)

        assertNull(vault.read(ServiceKey.budgetApp))
        device.close()
    }
}
