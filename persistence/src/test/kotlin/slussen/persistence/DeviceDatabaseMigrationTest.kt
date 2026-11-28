package slussen.persistence

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.device.DeviceMigrations
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.SlussenMigrations

/**
 * The migration tests of ADR-002 §7 for `device.db` on the JVM, in `gradlew check` (S-39, S-B-45): `device.db` created
 * at version 1 from its committed schema file, on SQLite itself, with invented rows in both its tables, migrated with
 * the hand-written Migration to version 2 as Room migrates it, and accepted by Room's own validation of version 2 with
 * every row and value kept. `MigrationTestHelper` runs the same on a device in `DeviceDatabaseMigrationIT`.
 */
class DeviceDatabaseMigrationTest {
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterEach
    fun close() = connection.close()

    private fun query(sql: String, on: SQLiteConnection = connection): List<List<String?>> = RoomSchemas.rows(on, sql)

    private fun tables(on: SQLiteConnection = connection): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name", on)
            .map { it.single()!! }

    /** Every row of every table, by table, columns named, so that a moved or lost value shows. */
    private fun everything(): Map<String, List<List<String?>>> = tables().associateWith { table ->
        val columns = query("PRAGMA table_info(`$table`)").map { it[1]!! }
        query("SELECT * FROM `$table` ORDER BY rowid").map { row -> row.mapIndexed { index, value -> "${columns[index]}=$value" } }
    }

    /** The tables and indices as SQLite holds them, without the quoting. */
    private fun schema(of: SQLiteConnection = connection): List<List<String?>> =
        query("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name", of)
            .map { line -> line.map { it?.replace("IF NOT EXISTS ", "")?.replace(Regex("[`\"']"), "") } }

    /** Invented rows in both tables of version 1, as the Operator's phone holds them: credentials of the Budget App and one Data Provider, two Offered Bank Accounts. */
    private fun version1WithInventedData() {
        RoomSchemas.create(DeviceDatabase::class, 1, connection)
        listOf(
            "INSERT INTO service_credential VALUES (1, NULL, 'Q2lwaGVydGV4dC1vbmU=', 1760529600000)",
            "INSERT INTO service_credential VALUES (2, 1, 'Q2lwaGVydGV4dC10d28=', 1760533200000)",
            "INSERT INTO offered_bank_account VALUES (1, 1, 1, 'acct-4410', 'fp-acct-4410', 'Kestrel Checking', 'checking', 'USD', 5135280, '4410', 1, 1760533200000)",
            "INSERT INTO offered_bank_account VALUES (2, 1, NULL, 'acct-2290', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1760533200000)",
        ).forEach(connection::execSQL)
    }

    /** As Room runs a Migration on the device: foreign keys off, one exclusive transaction rolled back on a failure, then foreign keys on. */
    private fun migrateAsRoomDoes() {
        connection.execSQL("PRAGMA foreign_keys = OFF")
        connection.execSQL("BEGIN EXCLUSIVE TRANSACTION")
        try {
            DeviceMigrations.MIGRATION_1_2.migrate(connection)
        } catch (e: Exception) {
            connection.execSQL("ROLLBACK TRANSACTION")
            throw e
        }
        connection.execSQL("END TRANSACTION")
        connection.execSQL("PRAGMA foreign_keys = ON")
    }

    @Test
    @DisplayName("NFR-017: device.db created at version 1, as the Operator's phone holds it, with rows in both tables, migrated as Room migrates it, keeps every service credential and Offered Bank Account with every value, adds bank_consent, bank_session, consent_request and consent_request_correlation empty, and Room accepts the result as version 2 (ADR-002 §7, S-B-45)")
    fun version1MigratesToVersion2KeepingEveryValue() {
        version1WithInventedData()
        val version1Tables = tables()
        assertEquals(listOf("offered_bank_account", "service_credential"), version1Tables)
        val before = everything()

        migrateAsRoomDoes()

        assertEquals(before, everything().filterKeys { it in version1Tables })
        assertEquals(NEW_IN_VERSION_2.sorted(), tables() - version1Tables.toSet())
        NEW_IN_VERSION_2.forEach { table -> assertEquals(listOf(listOf("0")), query("SELECT COUNT(*) FROM `$table`"), table) }
        assertEquals(2, RoomSchemas.currentOf(DeviceDatabase::class).version)
        val validation = RoomSchemas.openDelegateOf(DeviceDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
        assertEquals(listOf(listOf("ok")), query("PRAGMA integrity_check"))
    }

    @Test
    @DisplayName("NFR-017: the Migration 1 to 2 of device.db creates the four tables of version 2 with their indices and foreign keys, each statement exactly as the committed version 2 states it, and no other statement (ADR-002 §7)")
    fun migrationSqlIsTheCommittedVersion2() {
        val expected = NEW_IN_VERSION_2.flatMap { RoomSchemas.createSqlOf(DeviceDatabase::class, 2, it) }

        val migration = DeviceMigrations.MIGRATION_1_2 as SlussenMigrations.Statements

        assertEquals(expected, migration.statements)
        assertEquals(emptyList<String>(), migration.foreignKeysChecked)
        assertEquals(listOf(1 to 2), DeviceMigrations.ALL.map { it.startVersion to it.endVersion })
    }

    @Test
    @DisplayName("NFR-017: device.db created at every committed version migrates to a schema Room accepts as the current version, with every table and index stated exactly as in a device.db created new (ADR-002 §7)")
    fun everyCommittedVersionMigrates() {
        val current = RoomSchemas.currentOf(DeviceDatabase::class).version
        val created = BundledSQLiteDriver().open(":memory:").use { new ->
            RoomSchemas.create(DeviceDatabase::class, current, new)
            schema(of = new)
        }
        RoomSchemas.committedVersions(DeviceDatabase::class).forEach { version ->
            BundledSQLiteDriver().open(":memory:").use { fresh ->
                RoomSchemas.create(DeviceDatabase::class, version, fresh)
                (version until current).forEach { from -> DeviceMigrations.ALL.single { it.startVersion == from && it.endVersion == from + 1 }.migrate(fresh) }
                val validation = RoomSchemas.openDelegateOf(DeviceDatabase::class).onValidateSchema(fresh)
                assertTrue(validation.isValid, "from version $version: ${validation.expectedFoundMsg}")
                assertEquals(created, schema(of = fresh), "from version $version")
            }
        }
        assertEquals(current - 1, DeviceMigrations.ALL.size)
    }

    @Test
    @DisplayName("NFR-017, BR-002: after the Migration a Bank has at most one Bank Consent per Data Provider, a Bank Consent at most one Bank Session, a Bank Session and a correlation only for a stored Bank Consent and Consent Request, a state digest is stored once, and no column fills in a value by itself (entity model BANK_CONSENT, BANK_SESSION, CONSENT_REQUEST; ADR-002 §2, §7)")
    fun migratedTablesKeepTheirRules() {
        version1WithInventedData()
        migrateAsRoomDoes()
        listOf(
            "INSERT INTO bank_consent VALUES (1, 7, 2, 1764324000000, 1779876000000, NULL)",
            "INSERT INTO bank_session VALUES (1, 1, 'invented-session', 1764324000000)",
            "INSERT INTO consent_request VALUES (1, 'Aurora Bank (FI)', 2, 1764324000000, 1764324900000, 1)",
            "INSERT INTO consent_request_correlation VALUES (1, '${"a".repeat(64)}', NULL)",
            "INSERT INTO consent_request VALUES (2, 'Vesta Sparbank (SE)', 2, 1764324000000, 1764324900000, 0)",
        ).forEach(connection::execSQL)
        fun refused(sql: String) = runCatching { connection.execSQL(sql) }.isFailure

        assertTrue(refused("INSERT INTO bank_consent VALUES (2, 7, 2, 1764324000000, 1779876000000, NULL)"), "a second Bank Consent of the Bank and Data Provider")
        assertTrue(refused("INSERT INTO bank_session VALUES (2, 1, 'another-session', 1764324000000)"), "a second Bank Session of the Bank Consent")
        assertTrue(refused("INSERT INTO bank_session VALUES (3, 9, 'no-consent', 1764324000000)"), "a Bank Session of no stored Bank Consent")
        assertTrue(refused("INSERT INTO consent_request_correlation VALUES (2, '${"a".repeat(64)}', NULL)"), "a state digest stored twice")
        assertTrue(refused("INSERT INTO consent_request_correlation VALUES (9, '${"b".repeat(64)}', NULL)"), "a correlation of no stored Consent Request")
        assertTrue(refused("DELETE FROM bank_consent WHERE id = 1"), "a Bank Consent its Bank Session refers to")
        assertTrue(refused("INSERT INTO consent_request (bank_reference, data_provider_id, started_at, expires_at) VALUES ('Aurora Bank (FI)', 2, 1, 2)"), "answered has no default")
        connection.execSQL("INSERT INTO bank_consent VALUES (3, 7, 3, 1764324000000, 1779876000000, NULL)")
        NEW_IN_VERSION_2.forEach { table ->
            assertEquals(emptyList<List<String?>>(), query("SELECT name, dflt_value FROM pragma_table_info('$table') WHERE dflt_value IS NOT NULL"), "$table fills in no value")
        }
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    @DisplayName("NFR-017: slussen.db stays at its committed version 6 and journal.db at its committed version 1 in this build: device.db version 2 changes no other database (ADR-002 §7, S-B-45)")
    fun theOtherDatabasesAreUntouched() {
        assertEquals(6, RoomSchemas.currentOf(SlussenDatabase::class).version)
        assertEquals((1..6).toList(), RoomSchemas.committedVersions(SlussenDatabase::class))
        assertEquals(5, SlussenMigrations.ALL.size)
    }

    private companion object {
        /** The tables version 2 adds (ADR-002 §1, §2, §7; S-B-45), in the order Room's schema states them. */
        val NEW_IN_VERSION_2 = listOf("bank_consent", "bank_session", "consent_request", "consent_request_correlation")
    }
}
