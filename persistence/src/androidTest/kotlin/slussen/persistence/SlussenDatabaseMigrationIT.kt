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
import slussen.core.port.BankAccountDelivery
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewAccountBalance
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.StoredFetchResult
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.SlussenMigrations
import java.time.Instant

/**
 * Evidence E-06 of ADR-005 §7 (ADR-002 §7, S-39): `slussen.db` created at every committed schema version with Room's
 * `MigrationTestHelper` from the committed schema files, with invented rows in every table, migrated with the
 * hand-written Migrations to the current version and validated by Room, keeping every row and value; a version 1, 2
 * and 3 `slussen.db`, as the Operator's phone held and holds it, opened by the app's own [SlussenDatabases.openMain]
 * and read back; and a new `slussen.db`, which starts with the default Description lists (UC-010 BR-007).
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

    /** Invented rows in every table of version 2: those of version 1 and a Left-Out Bank Account (S-38). */
    private fun SupportSQLiteDatabase.inventedVersion2Data() {
        inventedVersion1Data()
        execSQL("INSERT INTO left_out_bank_account VALUES (1, 1, 'acct-2290', 'Kids Saver ••2290', 1760533200000)")
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

        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, *SlussenMigrations.ALL).use { db ->
            assertEquals(before, db.everything(version1Tables))
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertTrue(db.tables().containsAll(listOf("left_out_bank_account", "account_balance", "fetch_result") + NEW_IN_VERSION_4))
            assertEquals(DEFAULT_DESCRIPTIONS, db.select("SELECT kind, text FROM transfer_description ORDER BY id"))
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, slussen.db at version 2, as the Operator's phone holds it, with rows in every table migrates to the current version, Room validates it, and every row and value is kept (ADR-002 §7)")
    fun version2MigratesKeepingEveryValue() {
        val name = "migration-version-2.db"
        val (version2Tables, before) = helper.createDatabase(name, 2).use { db ->
            db.inventedVersion2Data()
            val tables = db.tables()
            assertTrue(tables.all { db.select("SELECT COUNT(*) FROM `$it`").single().single() != "0" }, "every table holds a row")
            tables to db.everything(tables)
        }

        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, *SlussenMigrations.ALL).use { db ->
            assertEquals(before, db.everything(version2Tables))
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertEquals((listOf("account_balance", "fetch_result") + NEW_IN_VERSION_4).sorted(), db.tables() - version2Tables.toSet())
            assertEquals(DEFAULT_DESCRIPTIONS, db.select("SELECT kind, text FROM transfer_description ORDER BY id"))
        }
    }

    /** Invented rows in every table of version 3: those of version 2, an Account Balance and a Fetch Result (UC-001 steps 8, 11). */
    private fun SupportSQLiteDatabase.inventedVersion3Data() {
        inventedVersion2Data()
        execSQL("INSERT INTO account_balance VALUES (1, 1, 5135280, 'USD', 1760533200000)")
        execSQL("INSERT INTO fetch_result VALUES (1, 1, 'Fetched', 3)")
    }

    @Test
    @DisplayName("NFR-017: E-06, slussen.db at version 3, as the Operator's phone holds it, with rows in every table migrates to the current version, Room validates it, every row and value is kept, and the Description lists hold their defaults (ADR-002 §7)")
    fun version3MigratesKeepingEveryValue() {
        val name = "migration-version-3.db"
        val (version3Tables, before) = helper.createDatabase(name, 3).use { db ->
            db.inventedVersion3Data()
            val tables = db.tables()
            assertTrue(tables.all { db.select("SELECT COUNT(*) FROM `$it`").single().single() != "0" }, "every table holds a row")
            tables to db.everything(tables)
        }

        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, *SlussenMigrations.ALL).use { db ->
            assertEquals(before, db.everything(version3Tables))
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertEquals(NEW_IN_VERSION_4.sorted(), db.tables() - version3Tables.toSet())
            assertEquals(DEFAULT_DESCRIPTIONS, db.select("SELECT kind, text FROM transfer_description ORDER BY id"))
            (NEW_IN_VERSION_4 - "transfer_description").forEach { assertEquals(listOf(listOf("0")), db.select("SELECT COUNT(*) FROM `$it`"), it) }
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, a version 3 slussen.db as the Operator's phone holds it is upgraded by the app itself, never cleared, its data is read back, and the new tables are usable (ADR-002 §7)")
    fun theAppUpgradesAnInstalledVersion3() = runBlocking {
        helper.createDatabase(SlussenDatabases.MAIN_FILE, 3).use { it.inventedVersion3Data() }

        val main = SlussenDatabases.openMain(DeviceFiles.context)
        try {
            val connections = RoomConnectionStore(main.connections())
            assertEquals(listOf("Kestrel Checking"), connections.bankAccounts().map { it.name })
            assertEquals(listOf("acct-2290"), connections.leftOutBankAccounts().map { it.providerReference })
            val fetches = RoomFetchStore(main.fetches())
            assertEquals(listOf(StoredFetchResult(1, FetchResultOutcome.Fetched, 3)), fetches.fetchesOf(1).single().results)
            assertEquals(listOf(Amount(5135280)), RoomImportStatusStore(main.fetches()).accountBalances().map { it.amount })
            val db = main.openHelper.writableDatabase
            assertEquals(DEFAULT_DESCRIPTIONS, db.select("SELECT kind, text FROM transfer_description ORDER BY id"))
            db.execSQL(
                "INSERT INTO bank_transaction (bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, " +
                    "booked_date, amount, currency, payee, memo, first_seen_at, status, transfer_id) VALUES (1, 'TX-0001', 'Supplied', 1, " +
                    "'S1Rexample-identifier-0001', 20380, -42500, 'USD', 'Corner Grocer', NULL, 1760533200000, NULL, NULL)",
            )
            assertEquals(listOf(listOf("1", "TX-0001", null)), db.select("SELECT bank_account_id, stable_reference, status FROM bank_transaction"))
        } finally {
            main.close()
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, a new slussen.db created by the app at the current version starts with the default Card Payment and Bank Transfer Descriptions of UC-010 BR-007 (entity model TRANSFER_DESCRIPTION; ADR-002 §8 step 3)")
    fun aNewDatabaseStartsWithTheDefaultDescriptionLists() {
        val main = SlussenDatabases.openMain(DeviceFiles.context)
        try {
            assertEquals(DEFAULT_DESCRIPTIONS, main.openHelper.readableDatabase.select("SELECT kind, text FROM transfer_description ORDER BY id"))
        } finally {
            main.close()
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, a version 2 slussen.db as the Operator's phone holds it is upgraded by the app itself, never cleared, and its data is read back with the new tables usable (ADR-002 §7)")
    fun theAppUpgradesAnInstalledVersion2() = runBlocking {
        helper.createDatabase(SlussenDatabases.MAIN_FILE, 2).use { it.inventedVersion2Data() }

        val main = SlussenDatabases.openMain(DeviceFiles.context)
        try {
            val connections = RoomConnectionStore(main.connections())
            assertEquals(listOf("Kestrel Checking"), connections.bankAccounts().map { it.name })
            assertEquals(listOf("acct-2290"), connections.leftOutBankAccounts().map { it.providerReference })
            val fetches = RoomFetchStore(main.fetches())
            val status = RoomImportStatusStore(main.fetches())
            assertEquals(listOf(FetchOutcome.Fetched), fetches.fetchesOf(1).map { it.outcome })
            assertTrue(status.accountBalances().isEmpty())
            fetches.storeBankAccountDelivery(
                BankAccountDelivery(1, 1, FetchResultOutcome.Fetched, 0, NewAccountBalance(Amount(5135280), CurrencyCode("USD"), Instant.parse("2026-10-20T12:00:00Z"))),
            )
            assertEquals(listOf(Amount(5135280)), status.accountBalances().map { it.amount })
            assertEquals(listOf(StoredFetchResult(1, FetchResultOutcome.Fetched, 0)), fetches.fetchesOf(1).single().results)
        } finally {
            main.close()
        }
    }

    private companion object {
        /** The schema version of `slussen.db` this build creates and migrates to (ADR-002 §7). */
        const val CURRENT_VERSION = 4

        /** The tables version 4 adds (ADR-002 §1, §2). */
        val NEW_IN_VERSION_4 = listOf("bank_transaction", "budget_app_transaction", "transfer", "transfer_description", "unreadable_transaction")

        /** The default Card Payment Descriptions and Bank Transfer Descriptions of UC-010 BR-007, as (kind, text), in the order the rule lists them. */
        val DEFAULT_DESCRIPTIONS: List<List<String?>> =
            listOf(
                "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY",
                "CRD AUTOPAY", "CITICARDAP",
            ).map { listOf("Card Payment", it) } +
                listOf(
                    "ÖVERFÖRING", "INTERNET TRANSFER", "EXPRESS INTERNET TRANSFER", "AMERICAN EXPRESS TRANSFER", "ONLINE TRANSFER",
                    "TRANSFER", "XFER",
                ).map { listOf("Bank Transfer", it) }
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
