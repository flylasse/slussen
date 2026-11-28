package slussen.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.core.port.ReplacedConsent
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.device.DeviceMigrations
import slussen.pluginapi.provider.ConsentSession
import java.time.Instant

/**
 * Evidence E-06 of ADR-005 §7 for `device.db` (ADR-002 §7, S-B-45): `device.db` created at version 1 with Room's
 * `MigrationTestHelper` from the committed schema file, with invented rows in both tables, migrated with the
 * hand-written Migration to version 2 and validated by Room, keeping every row and value; and a version 1 `device.db`
 * as the Operator's phone holds it, opened by the app's own [SlussenDatabases.openDevice] and read back, with the new
 * tables usable. Instrumented, on API 26 and the phone; the same Migration runs on the JVM in `DeviceDatabaseMigrationTest`.
 */
@DisplayName("device.db version 1 to 2 (ADR-002 §7), on the device")
class DeviceDatabaseMigrationIT {
    private val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DeviceDatabase::class.java)

    @BeforeEach
    fun wipe() = DeviceFiles.wipe()

    @AfterEach
    fun clean() = DeviceFiles.wipe()

    private fun SupportSQLiteDatabase.select(sql: String): List<List<String?>> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    private fun SupportSQLiteDatabase.everything(tables: List<String>): Map<String, List<List<String?>>> =
        tables.associateWith { select("SELECT * FROM `$it` ORDER BY rowid") }

    /** Invented rows in both tables of version 1 (C-005): credentials of the Budget App and of one Data Provider, two Offered Bank Accounts. */
    private fun SupportSQLiteDatabase.inventedVersion1Data() {
        listOf(
            "INSERT INTO service_credential VALUES (1, NULL, 'Q2lwaGVydGV4dC1vbmU=', 1760529600000)",
            "INSERT INTO service_credential VALUES (2, 1, 'Q2lwaGVydGV4dC10d28=', 1760533200000)",
            "INSERT INTO offered_bank_account VALUES (1, 1, 1, 'acct-4410', 'fp-acct-4410', 'Kestrel Checking', 'checking', 'USD', 5135280, '4410', 1, 1760533200000)",
            "INSERT INTO offered_bank_account VALUES (2, 1, NULL, 'acct-2290', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1760533200000)",
        ).forEach(::execSQL)
    }

    @Test
    @DisplayName("NFR-017: E-06, device.db created at version 1 with rows in both tables and migrated to version 2 keeps every row and value, adds the four tables of Bank Consents empty, and Room validates it (ADR-002 §7, S-B-45)")
    fun version1MigratesKeepingEveryValue() {
        val version1Tables = listOf("service_credential", "offered_bank_account")
        val before = helper.createDatabase(TEST_FILE, 1).use { db ->
            db.inventedVersion1Data()
            db.everything(version1Tables)
        }

        val migrated = helper.runMigrationsAndValidate(TEST_FILE, 2, true, *DeviceMigrations.ALL)

        migrated.use { db ->
            assertEquals(before, db.everything(version1Tables))
            NEW_IN_VERSION_2.forEach { table -> assertEquals(listOf(listOf("0")), db.select("SELECT COUNT(*) FROM `$table`"), table) }
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, a version 1 device.db as the Operator's phone holds it is upgraded by the app itself, never cleared: its Offered Bank Accounts read back, and a Bank Consent with its Bank Session can be stored in the new tables (ADR-002 §7)")
    fun theAppUpgradesAnInstalledVersion1() = runBlocking {
        helper.createDatabase(TEST_FILE, 1).use { it.inventedVersion1Data() }
        val installed = SlussenDatabases.deviceFile(DeviceFiles.context)
        installed.parentFile!!.mkdirs()
        DeviceFiles.context.getDatabasePath(TEST_FILE).copyTo(installed, overwrite = true)

        val device = SlussenDatabases.openDevice(DeviceFiles.context)
        try {
            assertEquals(listOf("acct-4410", "acct-2290"), RoomOfferedBankAccountStore(device.offeredBankAccounts()).offered().map { it.providerReference })
            assertEquals(2, device.serviceCredentials().find(1)!!.id)
            val consents = RoomConsentStore(device.consents())
            val at = Instant.parse("2026-11-28T10:00:00Z")
            val request = consents.startRequest(NewConsentRequest("Aurora Bank (FI)", 1, at, at.plusSeconds(900), "c".repeat(64), null))
            consents.consumeAnswer("c".repeat(64), at)
            val stored = consents.replaceConsent(request.id, GrantedBankConsent(7, at, at.plusSeconds(86_400 * 180L), ConsentSession("invented-session"), at))
            assertTrue(stored is ReplacedConsent.Stored, "$stored")
            assertEquals(listOf(7L), consents.bankConsents().map { it.bankId })
        } finally {
            device.close()
        }
    }

    private companion object {
        /** The file MigrationTestHelper creates in the app's database directory; the installed `device.db` lies in `noBackupFilesDir`. */
        const val TEST_FILE = "device-migration-test.db"

        /** The tables version 2 adds (ADR-002 §1, §2, §7; S-B-45). */
        val NEW_IN_VERSION_2 = listOf("bank_consent", "bank_session", "consent_request", "consent_request_correlation")
    }
}
