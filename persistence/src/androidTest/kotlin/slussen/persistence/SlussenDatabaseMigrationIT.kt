package slussen.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.NewLeftOutBankAccount
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.SlussenMigrations
import java.time.Instant

/**
 * Evidence E-06 of ADR-005 §7 (ADR-002 §7, S-39): `slussen.db` created at every committed schema version with Room's
 * `MigrationTestHelper` from the committed schema files, with invented rows in every table, migrated with the
 * hand-written Migrations to the current version and validated by Room, keeping every row and value; and a version 1
 * `slussen.db`, as the Operator's phone holds it, opened by the app's own [SlussenDatabases.openMain] and read back.
 * Instrumented, on API 26 and the phone; the same migration also runs on the JVM in `SlussenDatabaseMigrationTest`.
 */
class SlussenDatabaseMigrationIT {
    private val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SlussenDatabase::class.java)

    @BeforeEach
    fun wipe() = DeviceFiles.wipe()

    @AfterEach
    fun clean() = DeviceFiles.wipe()

    private fun SupportSQLiteDatabase.select(sql: String): List<List<String?>> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    private fun SupportSQLiteDatabase.tables(): List<String> =
        select("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name")
            .map { it.single()!! }

    private fun SupportSQLiteDatabase.everything(tables: List<String>): Map<String, List<List<String?>>> =
        tables.associateWith { select("SELECT * FROM `$it` ORDER BY rowid") }

    /** Invented rows in every table of version 1 (C-005), with their foreign keys satisfied. */
    private fun SupportSQLiteDatabase.inventedVersion1Data() {
        listOf(
            "INSERT INTO operator_settings VALUES (1, 14, 30, 30, 8, 6, 30, 30, 20, 50, 4, 5, 3, 48, 14, 1, 50, 80, 80, 0, 1)",
            "INSERT INTO data_provider VALUES (1, 'Kestrel Feeds', 'example-feeds', 1, 0, 60, 20, 1)",
            "INSERT INTO file_format VALUES (3, 'Harbor Health Trust statement', 'example-hsa')",
            "INSERT INTO bank VALUES (1, 'Kestrel Bank', NULL)",
            "INSERT INTO budget VALUES (1, 'budget-household', 'Household', 'USD', 1760529600000, 1)",
            "INSERT INTO budget_account VALUES (1, 1, 'ba-checking', 'Checking', 0, 4140050, 1760529600000)",
            "INSERT INTO budget_account VALUES (2, 1, 'ba-reserve', 'Reserve Savings', 0, 12000000, 1760529600000)",
            "INSERT INTO bank_account VALUES (1, '6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a', 1, 1, 1, NULL, NULL, 'Kestrel Checking', 'USD', " +
                "'Checking', 20332, 'acct-4410', 'fp-acct-4410', NULL, 1760529600000, 0, NULL, 2)",
            "INSERT INTO account_mapping VALUES (1, 1, 1, 1760533200000)",
            "INSERT INTO provider_request VALUES (1, 1, 1, 'Fetch', 0, 1760529600000)",
            "INSERT INTO fetch VALUES (1, 1, 1760529600000, 'Operator', 'Fetched')",
            "INSERT INTO back_off VALUES (1, 1, NULL, 'Request To Wait', 1760529600000, 1760558400000)",
            "INSERT INTO recovery_check VALUES (1, 'New Installation', 1760529000000, NULL, NULL)",
            "INSERT INTO coordinator_state VALUES (1, 'Normal', NULL, 7, 0, 0)",
        ).forEach(::execSQL)
    }

    @Test
    @DisplayName("NFR-017: E-06, slussen.db at version 1 with rows in every table migrates to the current version, Room validates it, and every row and value is kept (ADR-002 §7)")
    fun version1MigratesKeepingEveryValue() {
        val name = "migration-version-1.db"
        val (version1Tables, before) = helper.createDatabase(name, 1).use { db ->
            db.inventedVersion1Data()
            val tables = db.tables()
            assertTrue(tables.all { db.select("SELECT COUNT(*) FROM `$it`").single().single() != "0" }, "every table holds a row")
            tables to db.everything(tables)
        }

        helper.runMigrationsAndValidate(name, 2, true, *SlussenMigrations.ALL).use { db ->
            assertEquals(before, db.everything(version1Tables))
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertTrue("left_out_bank_account" in db.tables())
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, a version 1 slussen.db as the Operator's phone holds it is upgraded by the app itself, never cleared, and its data is read back with the new table usable (ADR-002 §7)")
    fun theAppUpgradesAnInstalledVersion1() = runBlocking {
        helper.createDatabase(SlussenDatabases.MAIN_FILE, 1).use { it.inventedVersion1Data() }

        val main = SlussenDatabases.openMain(DeviceFiles.context)
        try {
            val connections = RoomConnectionStore(main.connections())
            assertEquals(listOf("Kestrel Checking"), connections.bankAccounts().map { it.name })
            assertEquals(listOf("Checking", "Reserve Savings"), connections.budgets().single().budgetAccounts.map { it.name })
            assertFalse(connections.budgets().single().budgetAccounts.any { it.manuallyKept })
            assertTrue(connections.leftOutBankAccounts().isEmpty())
            assertTrue(connections.leaveOut(listOf(NewLeftOutBankAccount(1, "acct-2290", "Kids Saver ••2290", Instant.parse("2026-10-19T12:00:00Z")))))
            assertEquals(listOf("acct-2290"), connections.leftOutBankAccounts().map { it.providerReference })
        } finally {
            main.close()
        }
    }
}
