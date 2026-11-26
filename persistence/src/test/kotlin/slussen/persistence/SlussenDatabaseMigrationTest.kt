package slussen.persistence

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.journal.JournalDatabase
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.SlussenMigrations
import slussen.pluginapi.testfixtures.UseCase
import java.io.File

/**
 * The migration tests of ADR-002 §7 on the JVM, in `gradlew check` (S-39): `slussen.db` is created at every committed
 * schema version from its committed schema file, on SQLite itself (the driver bundled with `androidx.sqlite`), with
 * invented rows in every table; it is migrated with the hand-written Migrations to the current version, and Room's own
 * validation of the current version (the generated open delegate, as on the device) must accept it with every row and
 * value kept. Room's `MigrationTestHelper` needs an Android instrumentation and runs in
 * `SlussenDatabaseMigrationIT` (ADR-005 §7 E-06), owed to a device.
 */
class SlussenDatabaseMigrationTest {
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @AfterEach
    fun close() = connection.close()

    /** Creates the tables, indices and Room's identity row of [version] exactly as its committed schema file states them. */
    private fun create(database: Class<out RoomDatabase>, version: Int, into: SQLiteConnection = connection) =
        RoomSchemas.create(database.kotlin, version, into)

    private fun tables(): List<String> = query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .map { it.single()!! }

    private fun query(sql: String): List<List<String?>> = RoomSchemas.rows(connection, sql)

    /** Every row of every table, by table, columns named, so that a moved or lost value shows. */
    private fun everything(): Map<String, List<List<String?>>> = tables().filter { it != "room_master_table" }.associateWith { table ->
        val columns = query("PRAGMA table_info(`$table`)").map { it[1]!! }
        query("SELECT * FROM `$table` ORDER BY rowid").map { row -> row.mapIndexed { index, value -> "${columns[index]}=$value" } }
    }

    /** Invented rows in every table of version 1 (C-005), one or more each, with their foreign keys satisfied. */
    private fun inventedVersion1Data() {
        listOf(
            "INSERT INTO operator_settings VALUES (1, 14, 30, 30, 8, 6, 30, 30, 20, 50, 4, 5, 3, 48, 14, 1, 50, 80, 80, 0, 1)",
            "INSERT INTO data_provider VALUES (1, 'Kestrel Feeds', 'example-feeds', 1, 0, 60, 20, 1)",
            "INSERT INTO data_provider VALUES (2, 'Aurora Open Banking', 'example-open-banking', 1, 1, 360, 50, 0)",
            "INSERT INTO file_format VALUES (3, 'Harbor Health Trust statement', 'example-hsa')",
            "INSERT INTO bank VALUES (1, 'Kestrel Bank', NULL)",
            "INSERT INTO bank VALUES (2, 'Aurora Bank', 'SE')",
            "INSERT INTO budget VALUES (1, 'budget-household', 'Household', 'USD', 1760529600000, 1)",
            "INSERT INTO budget VALUES (2, 'budget-summer', 'Summer House', 'EUR', NULL, 0)",
            "INSERT INTO budget_account VALUES (1, 1, 'ba-checking', 'Checking', 0, 4140050, 1760529600000)",
            "INSERT INTO budget_account VALUES (2, 1, 'ba-reserve', 'Reserve Savings', 0, 12000000, 1760529600000)",
            "INSERT INTO budget_account VALUES (3, 2, 'ba-current', 'Current', 0, NULL, NULL)",
            "INSERT INTO bank_account VALUES (1, '6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a', 1, 1, 1, NULL, NULL, 'Kestrel Checking', 'USD', " +
                "'Checking', 20332, 'acct-4410', 'fp-acct-4410', NULL, 1760529600000, 0, NULL, 2)",
            "INSERT INTO bank_account VALUES (2, '7a2d3b5f-1c8e-4d2f-8b66-3f9f4c2d1e5b', 2, NULL, NULL, 3, 1, 'Harbor Health Savings', 'USD', " +
                "'Savings', NULL, NULL, NULL, 'acct-0907', 1760529600000, 1, NULL, NULL)",
            "INSERT INTO account_mapping VALUES (1, 1, 1, 1760533200000)",
            "INSERT INTO account_mapping VALUES (2, 2, 2, NULL)",
            "INSERT INTO provider_request VALUES (1, 1, 1, 'Fetch', 0, 1760529600000)",
            "INSERT INTO provider_request VALUES (2, 1, NULL, 'Credential Check', 0, 1760529000000)",
            "INSERT INTO fetch VALUES (1, 1, 1760529600000, 'Operator', 'Fetched')",
            "INSERT INTO back_off VALUES (1, 1, NULL, 'Request To Wait', 1760529600000, 1760558400000)",
            "INSERT INTO recovery_check VALUES (1, 'New Installation', 1760529000000, NULL, NULL)",
            "INSERT INTO coordinator_state VALUES (1, 'Normal', NULL, 7, 0, 0)",
        ).forEach(connection::execSQL)
    }

    @Test
    @DisplayName("NFR-017: slussen.db created at version 1 with rows in every table and migrated to the current version keeps every row and value, and Room accepts the result as the current version (ADR-002 §7)")
    fun version1MigratesToTheCurrentVersionKeepingEveryValue() {
        create(SlussenDatabase::class.java, 1)
        inventedVersion1Data()
        val version1Tables = tables().filter { it != "room_master_table" }
        assertTrue(version1Tables.all { table -> query("SELECT COUNT(*) FROM `$table`").single().single() != "0" }, "every table holds a row")
        val before = everything()

        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        (1 until current).forEach { from -> SlussenMigrations.ALL.single { it.startVersion == from }.migrate(connection) }

        assertEquals(before, everything().filterKeys { it in version1Tables })
        assertEquals(setOf("left_out_bank_account", "account_balance", "fetch_result") + newInVersion4 + newInVersion5 + newInVersion6, everything().keys - version1Tables.toSet())
        assertEquals(defaultDescriptions, query("SELECT kind, text FROM transfer_description ORDER BY id"))
        val validation = RoomSchemas.openDelegateOf(SlussenDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    /** Invented rows in every table of version 2: those of version 1 and a Left-Out Bank Account (S-38). */
    private fun inventedVersion2Data() {
        inventedVersion1Data()
        connection.execSQL("INSERT INTO left_out_bank_account VALUES (1, 1, 'acct-2290', 'Kids Saver ••2290', 1760533200000)")
    }

    @Test
    @DisplayName("NFR-017: slussen.db created at version 2, as the Operator's phone holds it, with rows in every table and migrated to the current version keeps every row and value, and Room accepts the result as the current version (ADR-002 §7)")
    fun version2MigratesToTheCurrentVersionKeepingEveryValue() {
        create(SlussenDatabase::class.java, 2)
        inventedVersion2Data()
        val version2Tables = tables().filter { it != "room_master_table" }
        assertTrue(version2Tables.all { table -> query("SELECT COUNT(*) FROM `$table`").single().single() != "0" }, "every table holds a row")
        val before = everything()

        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        (2 until current).forEach { from -> SlussenMigrations.ALL.single { it.startVersion == from }.migrate(connection) }

        assertEquals(before, everything().filterKeys { it in version2Tables })
        assertEquals(setOf("account_balance", "fetch_result") + newInVersion4 + newInVersion5 + newInVersion6, everything().keys - version2Tables.toSet())
        assertEquals(defaultDescriptions, query("SELECT kind, text FROM transfer_description ORDER BY id"))
        val validation = RoomSchemas.openDelegateOf(SlussenDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    @DisplayName("NFR-017: the Migration 2 to 3 creates account_balance and fetch_result with their indices exactly as the committed version 3 states them")
    fun migrationSqlIsTheCommittedVersion3() {
        val schema = File(RoomSchemas.committed, "${SlussenDatabase::class.java.name}/3.json").readText()
        val expected = listOf("account_balance", "fetch_result").flatMap { table ->
            val entity = schema.substringAfter("\"tableName\": \"$table\"").substringBefore("\"tableName\"")
            Regex(""""createSql"\s*:\s*"([^"]*)"""").findAll(entity).map { it.groupValues[1].replace("\${TABLE_NAME}", table) }.toList()
        }

        val statements = (SlussenMigrations.MIGRATION_2_3 as SlussenMigrations.Statements).statements

        assertEquals(expected, statements)
    }

    @Test
    @DisplayName("NFR-017: after the Migration 2 to 3 a Bank Account has at most one Account Balance and a Fetch at most one Fetch Result per Bank Account, each only for a stored Bank Account and Fetch (entity model ACCOUNT_BALANCE, FETCH_RESULT; ADR-002 §2)")
    fun migratedTablesKeepTheirRules() {
        create(SlussenDatabase::class.java, 2)
        inventedVersion2Data()
        SlussenMigrations.MIGRATION_2_3.migrate(connection)
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("INSERT INTO account_balance (bank_account_id, amount, currency, reported_at) VALUES (1, 5135280, 'USD', 1760533200000)")
        connection.execSQL("INSERT INTO fetch_result (fetch_id, bank_account_id, outcome, transaction_count) VALUES (1, 1, 'Fetched', 0)")

        val secondBalance = runCatching {
            connection.execSQL("INSERT INTO account_balance (bank_account_id, amount, currency, reported_at) VALUES (1, 1, 'USD', 1760533200000)")
        }
        val balanceOfNoBankAccount = runCatching {
            connection.execSQL("INSERT INTO account_balance (bank_account_id, amount, currency, reported_at) VALUES (9, 1, 'USD', 1760533200000)")
        }
        val secondResult = runCatching {
            connection.execSQL("INSERT INTO fetch_result (fetch_id, bank_account_id, outcome, transaction_count) VALUES (1, 1, 'Failed', 0)")
        }
        val resultOfNoFetch = runCatching {
            connection.execSQL("INSERT INTO fetch_result (fetch_id, bank_account_id, outcome, transaction_count) VALUES (9, 1, 'Fetched', 0)")
        }

        assertTrue(secondBalance.isFailure)
        assertTrue(balanceOfNoBankAccount.isFailure)
        assertTrue(secondResult.isFailure)
        assertTrue(resultOfNoFetch.isFailure)
        assertEquals(listOf(listOf("1", "5135280", "USD", "1760533200000")), query("SELECT bank_account_id, amount, currency, reported_at FROM account_balance"))
        assertEquals(listOf(listOf("1", "1", "Fetched", "0")), query("SELECT * FROM fetch_result"))
    }

    /** The table version 5 adds (ADR-002 §2; transactions plan Phase 7a). */
    private val newInVersion5 = setOf("budget_read_state")

    /** The tables version 4 adds (ADR-002 §1, §2; transactions plan Phase 5). */
    private val newInVersion4 = setOf("budget_app_transaction", "transfer", "bank_transaction", "unreadable_transaction", "transfer_description")

    /** The default Card Payment Descriptions and Bank Transfer Descriptions of UC-010 BR-007, as (kind, text), in the order the rule lists them. */
    private val defaultDescriptions: List<List<String?>> =
        listOf(
            "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY",
            "CRD AUTOPAY", "CITICARDAP",
        ).map { listOf("Card Payment", it) } +
            listOf(
                "ÖVERFÖRING", "INTERNET TRANSFER", "EXPRESS INTERNET TRANSFER", "AMERICAN EXPRESS TRANSFER", "ONLINE TRANSFER",
                "TRANSFER", "XFER",
            ).map { listOf("Bank Transfer", it) }

    /** The Migration from [from] to the next version, as the app is given it (ADR-002 §7). */
    private fun migrationFrom(from: Int) = SlussenMigrations.ALL.single { it.startVersion == from && it.endVersion == from + 1 }

    /** Invented rows in every table of version 3: those of version 2, an Account Balance and a Fetch Result (UC-001 steps 8, 11). */
    private fun inventedVersion3Data() {
        inventedVersion2Data()
        connection.execSQL("INSERT INTO account_balance VALUES (1, 1, 5135280, 'USD', 1760533200000)")
        connection.execSQL("INSERT INTO fetch_result VALUES (1, 1, 'Fetched', 3)")
    }

    @Test
    @DisplayName("NFR-017: slussen.db created at version 3, as the Operator's phone holds it, with rows in every table and migrated to the current version keeps every row and value, and Room accepts the result as the current version (ADR-002 §7)")
    fun version3MigratesToTheCurrentVersionKeepingEveryValue() {
        create(SlussenDatabase::class.java, 3)
        inventedVersion3Data()
        val version3Tables = tables().filter { it != "room_master_table" }
        assertTrue(version3Tables.all { table -> query("SELECT COUNT(*) FROM `$table`").single().single() != "0" }, "every table holds a row")
        val before = everything()

        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        (3 until current).forEach { from -> migrationFrom(from).migrate(connection) }

        assertEquals(before, everything().filterKeys { it in version3Tables })
        assertEquals(newInVersion4 + newInVersion5 + newInVersion6, everything().keys - version3Tables.toSet())
        assertEquals(defaultDescriptions, query("SELECT kind, text FROM transfer_description ORDER BY id"))
        (newInVersion4 - "transfer_description").forEach { table -> assertEquals(listOf(listOf("0")), query("SELECT COUNT(*) FROM `$table`"), table) }
        val validation = RoomSchemas.openDelegateOf(SlussenDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    @DisplayName("NFR-017: the Migration 3 to 4 creates the five tables with their indices exactly as the committed version 4 states them")
    fun migrationSqlIsTheCommittedVersion4() {
        val schema = File(RoomSchemas.committed, "${SlussenDatabase::class.java.name}/4.json").readText()
        val expected = newInVersion4.flatMap { table ->
            val entity = schema.substringAfter("\"tableName\": \"$table\"").substringBefore("\"tableName\"")
            Regex(""""createSql"\s*:\s*"([^"]*)"""").findAll(entity).map { it.groupValues[1].replace("\${TABLE_NAME}", table) }.toList()
        }

        val statements = (migrationFrom(3) as SlussenMigrations.Statements).statements

        assertEquals(expected, statements.filter { it.startsWith("CREATE") })
        assertTrue(statements.filterNot { it.startsWith("CREATE") }.all { it.startsWith("INSERT INTO `transfer_description`") })
    }

    @Test
    @UseCase(id = "UC-010", businessRules = ["BR-007"], requirements = ["NFR-017"])
    @DisplayName("BR-007: the Migration 3 to 4 fills the Card Payment Descriptions and Bank Transfer Descriptions with the default lists that ship with the build, and nothing else")
    fun migrationFillsTheDefaultDescriptionLists() {
        create(SlussenDatabase::class.java, 3)
        inventedVersion3Data()

        migrationFrom(3).migrate(connection)

        assertEquals(defaultDescriptions, query("SELECT kind, text FROM transfer_description ORDER BY id"))
        assertEquals((1..defaultDescriptions.size).map { listOf(it.toString()) }, query("SELECT id FROM transfer_description ORDER BY id"))
    }

    @Test
    @DisplayName("NFR-017: after the Migration 3 to 4 a Bank Transaction, Unreadable Transaction, Budget App Transaction and Transfer exist only for a stored Bank Account, Budget Account and Transfer; within one Bank Account no two Bank Transactions share an Import Identifier or a Stable Reference; a Budget App Transaction's identifier is unique; a Bank Transaction has no Transaction Status until a Comparison (entity model; UC-003 A1; ADR-002 §2)")
    fun migratedVersion4TablesKeepTheirRules() {
        create(SlussenDatabase::class.java, 3)
        inventedVersion3Data()
        migrationFrom(3).migrate(connection)
        connection.execSQL("PRAGMA foreign_keys = ON")
        fun budgetAppTransaction(reference: String, budgetAccount: Long) =
            "INSERT INTO budget_app_transaction (budget_account_id, budget_app_reference, transaction_date, amount, payee, memo, import_identifier, " +
                "entered_by, transfer_link_reference, merged_into_reference, cleared, approved, state) " +
                "VALUES ($budgetAccount, '$reference', 20380, -42500, 'Corner Grocer', NULL, NULL, 'Typed Entry', NULL, NULL, 1, 1, 'Present')"
        fun transfer(inflow: Long) =
            "INSERT INTO transfer (outflow_budget_account_id, inflow_budget_account_id, amount, transfer_type, guessed, decision, decided_at) " +
                "VALUES (1, $inflow, 150000, 'Bank Transfer', 0, 'Proposed', NULL)"
        fun bankTransaction(bankAccount: Long, stableReference: String?, importIdentifier: String, transfer: Long? = null) =
            "INSERT INTO bank_transaction (bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                "amount, currency, payee, memo, first_seen_at, status, transfer_id) VALUES ($bankAccount, " +
                "${stableReference?.let { "'$it'" } ?: "NULL"}, '${if (stableReference == null) "None" else "Supplied"}', 1, '$importIdentifier', " +
                "20380, -42500, 'USD', 'Corner Grocer', NULL, 1760533200000, NULL, ${transfer ?: "NULL"})"
        fun unreadableTransaction(bankAccount: Long) =
            "INSERT INTO unreadable_transaction (bank_account_id, booked_date, amount, currency, payee, memo, reason, first_seen_at, status) " +
                "VALUES ($bankAccount, NULL, NULL, NULL, NULL, NULL, 'No amount', 1760533200000, 'Needs Review')"

        connection.execSQL(budgetAppTransaction("ynab-tx-0001", 1))
        connection.execSQL(transfer(2))
        connection.execSQL(bankTransaction(1, "TX-0001", "S1Rexample-identifier-0001"))
        connection.execSQL(bankTransaction(2, "TX-0001", "S1Rexample-identifier-0001"))
        connection.execSQL(bankTransaction(1, null, "S1Hexample-identifier-0002"))
        connection.execSQL(bankTransaction(1, null, "S1Hexample-identifier-0003", transfer = 1))
        connection.execSQL(unreadableTransaction(1))

        val stored = mapOf(
            "a second Budget App Transaction with the same identifier" to budgetAppTransaction("ynab-tx-0001", 2),
            "a Budget App Transaction of no stored Budget Account" to budgetAppTransaction("ynab-tx-0002", 9),
            "a Transfer to no stored Budget Account" to transfer(9),
            "a second Bank Transaction with the same Import Identifier in one Bank Account" to bankTransaction(1, "TX-0002", "S1Rexample-identifier-0001"),
            "a second Bank Transaction with the same Stable Reference in one Bank Account" to bankTransaction(1, "TX-0001", "S1Rexample-identifier-0004"),
            "a Bank Transaction of no stored Bank Account" to bankTransaction(9, "TX-0003", "S1Rexample-identifier-0005"),
            "a Transfer Leg of no stored Transfer" to bankTransaction(1, "TX-0004", "S1Rexample-identifier-0006", transfer = 9),
            "an Unreadable Transaction of no stored Bank Account" to unreadableTransaction(9),
        ).filterValues { sql -> runCatching { connection.execSQL(sql) }.isSuccess }

        assertEquals(emptySet<String>(), stored.keys, "stored although the schema must refuse it")
        assertEquals(
            listOf(listOf("1", "TX-0001", null, null), listOf("2", "TX-0001", null, null), listOf("1", null, null, null), listOf("1", null, null, "1")),
            query("SELECT bank_account_id, stable_reference, status, transfer_id FROM bank_transaction ORDER BY id"),
        )
        assertEquals(listOf(listOf("1", "No amount", "Needs Review")), query("SELECT bank_account_id, reason, status FROM unreadable_transaction"))
        assertEquals(listOf(listOf("1", "ynab-tx-0001")), query("SELECT budget_account_id, budget_app_reference FROM budget_app_transaction"))
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    /**
     * Invented rows in every table of version 4: those of version 3, a Budget App Transaction, a Transfer, a Bank
     * Transaction (one with a Transaction Status, one not compared yet) and an Unreadable Transaction (transactions plan
     * Phase 5); `transfer_description` holds the default lists from the Migration 3 to 4.
     */
    private fun inventedVersion4Data() {
        inventedVersion3Data()
        listOf(
            "INSERT INTO budget_app_transaction VALUES (1, 1, 'ynab-tx-0001', 20375, -42500, 'Corner Grocer', 'PLANTED-MEMO', " +
                "'YNAB:-42500:2026-10-14:1', 'Other Import Path', NULL, NULL, 1, 0, 'Present')",
            "INSERT INTO transfer VALUES (1, 1, 2, 150000, 'Bank Transfer', 0, 'Proposed', NULL)",
            "INSERT INTO bank_transaction VALUES (1, 1, 'TX-0001', 'Supplied', 1, 'SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa', 20375, -42500, 'USD', " +
                "'Corner Grocer', NULL, 1760533200000, 'New', NULL)",
            "INSERT INTO bank_transaction VALUES (2, 1, NULL, 'None', 1, 'SLS1-bbbbbbbbbbbbbbbbbbbbbbbbbb', 20376, -1500, 'USD', " +
                "NULL, NULL, 1760533200000, NULL, 1)",
            "INSERT INTO unreadable_transaction VALUES (1, 1, NULL, NULL, NULL, 'Harbor Fuel', NULL, 'date missing', 1760533200000, 'Needs Review')",
        ).forEach(connection::execSQL)
    }

    @Test
    @DisplayName("NFR-017: slussen.db created at version 4 with rows in every table and migrated to the current version keeps every row and value, and adds budget_read_state empty, so the next read of each Budget is its first (UC-003 BR-016; ADR-002 §7)")
    fun version4MigratesToTheCurrentVersionKeepingEveryValue() {
        create(SlussenDatabase::class.java, 4)
        connection.execSQL("DELETE FROM transfer_description")
        SlussenMigrations.INSERT_DEFAULT_TRANSFER_DESCRIPTIONS.forEach(connection::execSQL)
        inventedVersion4Data()
        val version4Tables = tables().filter { it != "room_master_table" }
        assertTrue(version4Tables.all { table -> query("SELECT COUNT(*) FROM `$table`").single().single() != "0" }, "every table holds a row")
        val before = everything()

        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        (4 until current).forEach { from -> migrationFrom(from).migrate(connection) }

        assertEquals(before, everything().filterKeys { it in version4Tables })
        assertEquals(newInVersion5 + newInVersion6, everything().keys - version4Tables.toSet())
        assertEquals(listOf(listOf("0")), query("SELECT COUNT(*) FROM budget_read_state"))
        val validation = RoomSchemas.openDelegateOf(SlussenDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    @DisplayName("NFR-017: the Migration 4 to 5 creates budget_read_state exactly as the committed version 5 states it")
    fun migrationSqlIsTheCommittedVersion5() {
        val schema = File(RoomSchemas.committed, "${SlussenDatabase::class.java.name}/5.json").readText()
        val expected = newInVersion5.flatMap { table ->
            val entity = schema.substringAfter("\"tableName\": \"$table\"").substringBefore("\"tableName\"")
            Regex(""""createSql"\s*:\s*"([^"]*)"""").findAll(entity).map { it.groupValues[1].replace("\${TABLE_NAME}", table) }.toList()
        }

        assertEquals(expected, (migrationFrom(4) as SlussenMigrations.Statements).statements)
    }

    @Test
    @DisplayName("NFR-017: after the Migration 4 to 5 a Budget has at most one read cursor, only a stored Budget has one, and a cursor owes no read of the period until one is marked (ADR-002 §2; UC-003 BR-016, BR-020)")
    fun migratedVersion5TableKeepsItsRules() {
        create(SlussenDatabase::class.java, 4)
        connection.execSQL("DELETE FROM transfer_description")
        inventedVersion4Data()
        migrationFrom(4).migrate(connection)
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("INSERT INTO budget_read_state (budget_id, change_cursor) VALUES (1, '4182')")

        val twice = runCatching { connection.execSQL("INSERT INTO budget_read_state (budget_id, change_cursor) VALUES (1, '4183')") }
        val noBudget = runCatching { connection.execSQL("INSERT INTO budget_read_state (budget_id, change_cursor) VALUES (9, '1')") }

        assertTrue(twice.isFailure)
        assertTrue(noBudget.isFailure)
        assertEquals(listOf(listOf("1", "4182", "0")), query("SELECT budget_id, change_cursor, period_read_owed FROM budget_read_state"))
    }

    /**
     * The tables version 6 adds, in the order the Migration 5 to 6 creates them (entity model; ADR-002 §1, §2; build-out plan
     * §3): the Review Decisions, the Upload Plans with their Eligible Transactions and write sets, the Sent Record, the
     * Transfer Counterparts and the Transaction Files.
     */
    private val newInVersion6 = listOf(
        "review_decision", "upload_plan", "eligible_transaction", "sent_record", "transfer_counterpart", "upload_plan_item", "transaction_file",
    )

    /**
     * Invented rows in every table of version 5: those of version 4, the read cursor of each Budget, and Transfers with
     * their Transfer Legs: a pair (Transfer 1), a Lonely Transfer that is a Guessed Transfer (2), a pair the Operator
     * confirmed (5), and one that was removed again (9), so that the identifiers of the Transfers have gaps and the last
     * one given names no stored Transfer. Bank Transaction 1 and 7 are ordinary ones, in no Transfer.
     */
    private fun inventedVersion5Data() {
        inventedVersion4Data()
        listOf(
            "INSERT INTO budget_read_state VALUES (1, '4182', 0)",
            "INSERT INTO budget_read_state VALUES (2, '77', 1)",
            "INSERT INTO transfer VALUES (2, 1, 2, 98760, 'Card Payment', 1, 'Proposed', NULL)",
            "INSERT INTO transfer VALUES (5, 2, 1, 5000, 'Bank Transfer', 0, 'Confirmed', 1760540000000)",
            "INSERT INTO transfer VALUES (9, 1, 2, 1000, 'Bank Transfer', 0, 'Proposed', NULL)",
            "DELETE FROM transfer WHERE id = 9",
            "INSERT INTO bank_transaction VALUES (3, 2, 'TX-0103', 'Supplied', 1, 'SLS1-cccccccccccccccccccccccccc', 20377, 150000, 'USD', " +
                "'From Kestrel Checking', NULL, 1760533200000, 'Transfer Leg', 1)",
            "INSERT INTO bank_transaction VALUES (4, 2, 'TX-0104', 'Supplied', 1, 'SLS1-dddddddddddddddddddddddddd', 20378, 98760, 'USD', " +
                "'AUTOPAY', 'PLANTED-MEMO', 1760533200000, 'Guessed Transfer', 2)",
            "INSERT INTO bank_transaction VALUES (5, 2, 'TX-0105', 'Derived', 1, 'SLS1-eeeeeeeeeeeeeeeeeeeeeeeeee', 20379, -5000, 'USD', " +
                "'XFER', NULL, 1760536800000, 'Transfer Leg', 5)",
            "INSERT INTO bank_transaction VALUES (6, 1, 'TX-0106', 'Supplied', 1, 'SLS1-ffffffffffffffffffffffffff', 20380, 5000, 'USD', " +
                "'XFER', NULL, 1760536800000, 'Transfer Leg', 5)",
            "INSERT INTO bank_transaction VALUES (7, 1, NULL, 'None', 2, 'SLS1-gggggggggggggggggggggggggg', 20376, -1500, 'USD', " +
                "NULL, NULL, 1760533200000, 'Needs Review', NULL)",
        ).forEach(connection::execSQL)
    }

    /** A version 5 `slussen.db` with [inventedVersion5Data], its Description lists as the Migration 3 to 4 filled them. */
    private fun version5WithInventedData() {
        create(SlussenDatabase::class.java, 5)
        SlussenMigrations.INSERT_DEFAULT_TRANSFER_DESCRIPTIONS.forEach(connection::execSQL)
        inventedVersion5Data()
    }

    /**
     * Runs the Migrations from [from] to the current version as Room runs them on the device: foreign keys are off (Room
     * switches them on only when the database is open, after every Migration), all Migrations in one exclusive
     * transaction that is rolled back when one fails, and then foreign keys on, as for every later statement of the app.
     */
    private fun migrateAsRoomDoes(from: Int) {
        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        connection.execSQL("PRAGMA foreign_keys = OFF")
        connection.execSQL("BEGIN EXCLUSIVE TRANSACTION")
        try {
            (from until current).forEach { migrationFrom(it).migrate(connection) }
        } catch (e: Exception) {
            connection.execSQL("ROLLBACK TRANSACTION")
            throw e
        }
        connection.execSQL("END TRANSACTION")
        connection.execSQL("PRAGMA foreign_keys = ON")
    }

    /** Every Transfer Leg with the Transfer its `transfer_id` names, every value of that Transfer included: a lost reference loses its line. */
    private fun transferLegsWithTheirTransfers(): List<List<String?>> = query(
        "SELECT bt.id, bt.import_identifier, t.id, t.outflow_budget_account_id, t.inflow_budget_account_id, t.amount, t.transfer_type, " +
            "t.guessed, t.decision, t.decided_at FROM bank_transaction bt JOIN transfer t ON t.id = bt.transfer_id ORDER BY bt.id",
    )

    /** The tables and indices of [of] as SQLite holds them, without the quoting, which a renamed table gets differently. */
    private fun schema(of: SQLiteConnection = connection): List<List<String?>> =
        RoomSchemas.rows(of, "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
            .map { line -> line.map { it?.replace("IF NOT EXISTS ", "")?.replace(Regex("[`\"']"), "") } }

    @Test
    @DisplayName("NFR-017: slussen.db created at version 5, as the Operator's phone holds it, with rows in every table and Transfers with their Transfer Legs, migrated as Room migrates it, keeps every row and value and every reference from a Transfer Leg to its Transfer, adds the seven tables of version 6 empty, and Room accepts the result as the current version (entity model TRANSFER; ADR-002 §7)")
    fun version5MigratesToTheCurrentVersionKeepingEveryValue() {
        version5WithInventedData()
        val version5Tables = tables().filter { it != "room_master_table" }
        assertTrue(version5Tables.all { table -> query("SELECT COUNT(*) FROM `$table`").single().single() != "0" }, "every table holds a row")
        val before = everything()
        val legsBefore = transferLegsWithTheirTransfers()
        assertEquals(listOf("2", "3", "4", "5", "6"), legsBefore.map { it.first() })

        migrateAsRoomDoes(from = 5)

        assertEquals(before, everything().filterKeys { it in version5Tables })
        assertEquals(legsBefore, transferLegsWithTheirTransfers())
        assertEquals(newInVersion6.toSet(), everything().keys - version5Tables.toSet())
        newInVersion6.forEach { table -> assertEquals(listOf(listOf("0")), query("SELECT COUNT(*) FROM `$table`"), table) }
        val validation = RoomSchemas.openDelegateOf(SlussenDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
        assertEquals(listOf(listOf("ok")), query("PRAGMA integrity_check"))
    }

    @Test
    @DisplayName("NFR-017: the Migration 5 to 6 rebuilds transfer by SQLite's procedure for a table other tables refer to (the new table, every row copied with every column named, the old table dropped, the new one renamed, its indices, the foreign key check) and creates the seven new tables, each statement exactly as the committed version 6 states the table or index; no other statement changes a stored row (ADR-002 §7)")
    fun migrationSqlIsTheCommittedVersion6() {
        fun version6(table: String, named: String = table) = RoomSchemas.createSqlOf(SlussenDatabase::class, 6, table, named)
        create(SlussenDatabase::class.java, 5)
        val columns = query("PRAGMA table_info(`transfer`)").joinToString(", ") { "`${it[1]}`" }
        val expected = listOf(
            version6("transfer", named = "_new_transfer").first(),
            "INSERT INTO `_new_transfer` ($columns) SELECT $columns FROM `transfer`",
            "DELETE FROM sqlite_sequence WHERE name = '_new_transfer'",
            "INSERT INTO sqlite_sequence (name, seq) SELECT '_new_transfer', seq FROM sqlite_sequence WHERE name = 'transfer'",
            "DROP TABLE `transfer`",
            "ALTER TABLE `_new_transfer` RENAME TO `transfer`",
        ) + version6("transfer").drop(1) + newInVersion6.flatMap { version6(it) }

        val migration = migrationFrom(5) as SlussenMigrations.Statements

        assertEquals(expected, migration.statements)
        assertEquals(2, version6("transfer").drop(1).size, "both indices of transfer are created again")
        assertEquals(listOf("transfer", "bank_transaction"), migration.foreignKeysChecked)
    }

    @Test
    @DisplayName("NFR-017: after the Migration 5 to 6 a Transfer Leg still names only a stored Transfer and a Transfer with Transfer Legs cannot be removed, so the references point at the rebuilt table; a Transfer may now lack its outflow side and never its inflow side; no stored Transfer lost a side; and a new Transfer gets an identifier above every one a Transfer ever had (entity model TRANSFER; UC-005 A8)")
    fun theRebuiltTransferKeepsItsRulesAndItsTransferLegs() {
        version5WithInventedData()
        migrateAsRoomDoes(from = 5)
        fun transfer(outflow: Long?, inflow: Long?) =
            "INSERT INTO transfer (outflow_budget_account_id, inflow_budget_account_id, amount, transfer_type, guessed, decision, decided_at) " +
                "VALUES (${outflow ?: "NULL"}, ${inflow ?: "NULL"}, 98760, 'Card Payment', 1, 'Proposed', NULL)"
        fun transferLeg(importIdentifier: String, transfer: Long) =
            "INSERT INTO bank_transaction (bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                "amount, currency, payee, memo, first_seen_at, status, transfer_id) VALUES (2, NULL, 'None', 1, '$importIdentifier', 20381, 98760, " +
                "'USD', 'AUTOPAY', NULL, 1760536800000, NULL, $transfer)"

        val stored = mapOf(
            "a Transfer Leg of no stored Transfer" to transferLeg("SLS1-hhhhhhhhhhhhhhhhhhhhhhhhhh", transfer = 9),
            "a Transfer with Transfer Legs removed" to "DELETE FROM transfer WHERE id = 5",
            "a Transfer without its inflow side" to transfer(outflow = 1, inflow = null),
            "a Transfer to no stored Budget Account" to transfer(outflow = 1, inflow = 99),
            "a Transfer from no stored Budget Account" to transfer(outflow = 99, inflow = 2),
        ).filterValues { sql -> runCatching { connection.execSQL(sql) }.isSuccess }
        connection.execSQL(transfer(outflow = null, inflow = 2))
        connection.execSQL(transferLeg("SLS1-iiiiiiiiiiiiiiiiiiiiiiiiii", transfer = 10))

        assertEquals(emptySet<String>(), stored.keys, "stored although the schema must refuse it")
        assertEquals(
            listOf(listOf("1", "1", "2"), listOf("2", "1", "2"), listOf("5", "2", "1"), listOf("10", null, "2")),
            query("SELECT id, outflow_budget_account_id, inflow_budget_account_id FROM transfer ORDER BY id"),
        )
        assertEquals(listOf(listOf("10")), query("SELECT transfer_id FROM bank_transaction WHERE import_identifier = 'SLS1-iiiiiiiiiiiiiiiiiiiiiiiiii'"))
        assertEquals(listOf(listOf("10")), query("SELECT seq FROM sqlite_sequence WHERE name = 'transfer'"))
        assertTrue(query("SELECT name FROM sqlite_master WHERE name LIKE '%new_transfer%'").isEmpty())
        assertTrue(query("SELECT name FROM sqlite_sequence WHERE name LIKE '%new_transfer%'").isEmpty())
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    @DisplayName("NFR-017: a Migration 5 to 6 that fails changes nothing: with foreign keys planted on, SQLite refuses to drop the Transfers their Transfer Legs name, Room rolls the Migration back, and every table, row and value of version 5 is as before (ADR-002 §7)")
    fun aMigrationThatFailsLeavesVersion5AsItWas() {
        version5WithInventedData()
        val before = everything()
        val schemaBefore = schema()

        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("BEGIN EXCLUSIVE TRANSACTION")
        val migrated = runCatching { migrationFrom(5).migrate(connection) }
        connection.execSQL("ROLLBACK TRANSACTION")

        assertTrue(migrated.isFailure, "the planted foreign keys must stop the Migration")
        assertEquals(before, everything())
        assertEquals(schemaBefore, schema())
        assertEquals(listOf(listOf("9")), query("SELECT seq FROM sqlite_sequence WHERE name = 'transfer'"))
    }

    @Test
    @DisplayName("NFR-017: the Migration 5 to 6 ends with the foreign key check of SQLite's procedure: a Transfer Leg that names no stored Transfer, planted in version 5, stops the Migration instead of being carried into version 6, and nothing is changed (ADR-002 §7, §8)")
    fun aReferenceToNoStoredTransferStopsTheMigration() {
        version5WithInventedData()
        connection.execSQL("PRAGMA foreign_keys = OFF")
        connection.execSQL("UPDATE bank_transaction SET transfer_id = 4 WHERE id = 7")
        val before = everything()
        val schemaBefore = schema()

        val migrated = runCatching { migrateAsRoomDoes(from = 5) }

        assertTrue(migrated.exceptionOrNull() is IllegalStateException, "${migrated.exceptionOrNull()}")
        assertTrue(migrated.exceptionOrNull()!!.message!!.contains("bank_transaction"), migrated.exceptionOrNull()!!.message)
        assertEquals(before, everything())
        assertEquals(schemaBefore, schema())
    }

    @Test
    @DisplayName("NFR-017: after the Migration 5 to 6 each new table keeps the rules the entity model and ADR-002 §2 state: one Review Decision per Bank Transaction, one Import Identifier per Budget Account in the Sent Record, one Transfer Counterpart per Sent Record and per Budget App identifier, one line per Bank Transaction in an Upload Plan, every reference only to a stored record, and no value that is filled in when it is left out")
    fun migratedVersion6TablesKeepTheirRules() {
        version5WithInventedData()
        migrateAsRoomDoes(from = 5)
        fun reviewDecision(bankTransaction: Long, chosenBudgetAccount: Long? = null) =
            "INSERT INTO review_decision (bank_transaction_id, choice, chosen_budget_app_reference, chosen_budget_account_id, chosen_amount, decided_at) " +
                "VALUES ($bankTransaction, 'Already Present', 'ynab-tx-0001', ${chosenBudgetAccount ?: "NULL"}, -42500, 1760540000000)"
        fun uploadPlan(budget: Long, bankAccount: Long? = null) =
            "INSERT INTO upload_plan (budget_id, bank_account_id, prepared_at, expires_at, new_count, match_count, counterpart_count, total_inflow, " +
                "total_outflow, state) VALUES ($budget, ${bankAccount ?: "NULL"}, 1760540000000, 1760540600000, 1, 0, 0, 0, 42500, 'Confirmed')"
        fun eligibleTransaction(plan: Long, bankTransaction: Long) =
            "INSERT INTO eligible_transaction (upload_plan_id, bank_transaction_id, upload_kind, clear_counterpart) VALUES ($plan, $bankTransaction, 'New', 0)"
        fun sentRecord(bankTransaction: Long, plan: Long, budgetAccount: Long, importIdentifier: String) =
            "INSERT INTO sent_record (bank_transaction_id, upload_plan_id, budget_account_id, import_identifier, occurrence, booked_date, amount, " +
                "sent_as_transfer, sent_at, outcome, budget_app_reference) VALUES ($bankTransaction, $plan, $budgetAccount, '$importIdentifier', 0, " +
                "20375, -42500, 1, 1760540100000, 'Written', NULL)"
        fun transferCounterpart(sentRecord: Long, reference: String, budgetAccount: Long = 2, settledBy: Long? = null) =
            "INSERT INTO transfer_counterpart (sent_record_id, budget_account_id, budget_app_reference, state, cleared_at, settled_by_bank_transaction_id) " +
                "VALUES ($sentRecord, $budgetAccount, '$reference', 'Uncleared', NULL, ${settledBy ?: "NULL"})"
        fun uploadPlanItem(plan: Long, position: Int, tail: String = "1, 1, NULL, NULL, NULL, NULL") =
            "INSERT INTO upload_plan_item (upload_plan_id, position, write_kind, bank_transaction_id, budget_account_id, transfer_budget_account_id, " +
                "transfer_counterpart_id, reused_sent_record_id, reused_outcome) VALUES ($plan, $position, 'Create', $tail)"
        fun transactionFile(bankAccount: Long, fileFormat: Long) =
            "INSERT INTO transaction_file (bank_account_id, file_format_id, imported_at, outcome, transaction_count, rejection_reason) " +
                "VALUES ($bankAccount, $fileFormat, 1760540000000, 'Imported', 3, NULL)"

        listOf(
            reviewDecision(bankTransaction = 1, chosenBudgetAccount = 1),
            uploadPlan(budget = 1),
            uploadPlan(budget = 1, bankAccount = 2),
            eligibleTransaction(plan = 1, bankTransaction = 1),
            eligibleTransaction(plan = 2, bankTransaction = 1),
            sentRecord(bankTransaction = 1, plan = 1, budgetAccount = 1, importIdentifier = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa"),
            sentRecord(bankTransaction = 1, plan = 2, budgetAccount = 2, importIdentifier = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa"),
            transferCounterpart(sentRecord = 1, reference = "ynab-tx-0201", settledBy = 3),
            "INSERT INTO upload_plan_item (upload_plan_id, position, write_kind, state_version, change_cursor) VALUES (1, 0, 'Header', 12, '4182')",
            uploadPlanItem(plan = 1, position = 1),
            uploadPlanItem(plan = 2, position = 1, tail = "1, 1, 2, 1, 1, 'Rejected'"),
            transactionFile(bankAccount = 2, fileFormat = 3),
        ).forEach(connection::execSQL)

        val stored = mapOf(
            "a second Review Decision of one Bank Transaction" to reviewDecision(bankTransaction = 1),
            "a Review Decision of no stored Bank Transaction" to reviewDecision(bankTransaction = 99),
            "a Review Decision that chose a transaction of no stored Budget Account" to reviewDecision(bankTransaction = 7, chosenBudgetAccount = 99),
            "an Upload Plan of no stored Budget" to uploadPlan(budget = 99),
            "an Upload Plan limited to no stored Bank Account" to uploadPlan(budget = 1, bankAccount = 99),
            "a Bank Transaction twice in one Upload Plan" to eligibleTransaction(plan = 1, bankTransaction = 1),
            "an Eligible Transaction of no stored Upload Plan" to eligibleTransaction(plan = 99, bankTransaction = 7),
            "an Eligible Transaction of no stored Bank Transaction" to eligibleTransaction(plan = 1, bankTransaction = 99),
            "a second Sent Record with the same Import Identifier in one Budget Account" to
                sentRecord(bankTransaction = 7, plan = 1, budgetAccount = 1, importIdentifier = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa"),
            "a Sent Record of no stored Bank Transaction" to sentRecord(bankTransaction = 99, plan = 1, budgetAccount = 1, importIdentifier = "SLS1-jjjjjjjjjjjjjjjjjjjjjjjjjj"),
            "a Sent Record of no stored Upload Plan" to sentRecord(bankTransaction = 7, plan = 99, budgetAccount = 1, importIdentifier = "SLS1-kkkkkkkkkkkkkkkkkkkkkkkkkk"),
            "a Sent Record to no stored Budget Account" to sentRecord(bankTransaction = 7, plan = 1, budgetAccount = 99, importIdentifier = "SLS1-llllllllllllllllllllllllll"),
            "a second Transfer Counterpart of one Sent Record" to transferCounterpart(sentRecord = 1, reference = "ynab-tx-0202"),
            "a second Transfer Counterpart with the same Budget App identifier" to transferCounterpart(sentRecord = 2, reference = "ynab-tx-0201"),
            "a Transfer Counterpart of no stored Sent Record" to transferCounterpart(sentRecord = 99, reference = "ynab-tx-0203"),
            "a Transfer Counterpart in no stored Budget Account" to transferCounterpart(sentRecord = 2, reference = "ynab-tx-0204", budgetAccount = 99),
            "a Transfer Counterpart settled by no stored Bank Transaction" to transferCounterpart(sentRecord = 2, reference = "ynab-tx-0205", settledBy = 99),
            "two writes at one position of an Upload Plan" to uploadPlanItem(plan = 1, position = 1),
            "a write of no stored Upload Plan" to uploadPlanItem(plan = 99, position = 1),
            "a write of no stored Bank Transaction" to uploadPlanItem(plan = 1, position = 2, tail = "99, 1, NULL, NULL, NULL, NULL"),
            "a write to no stored Budget Account" to uploadPlanItem(plan = 1, position = 3, tail = "1, 99, NULL, NULL, NULL, NULL"),
            "a linked write to no stored Budget Account" to uploadPlanItem(plan = 1, position = 4, tail = "1, 1, 99, NULL, NULL, NULL"),
            "a clearing of no stored Transfer Counterpart" to uploadPlanItem(plan = 1, position = 5, tail = "1, 1, NULL, 99, NULL, NULL"),
            "a retry of no stored Sent Record" to uploadPlanItem(plan = 1, position = 6, tail = "1, 1, NULL, NULL, 99, 'Rejected'"),
            "a Transaction File of no stored Bank Account" to transactionFile(bankAccount = 99, fileFormat = 3),
            "a Transaction File of no stored File Format" to transactionFile(bankAccount = 2, fileFormat = 99),
            "a Review Decision without the time it was made" to
                "INSERT INTO review_decision (bank_transaction_id, choice) VALUES (7, 'Defer')",
            "a Sent Record without an outcome" to
                "INSERT INTO sent_record (bank_transaction_id, upload_plan_id, budget_account_id, import_identifier, occurrence, booked_date, amount, " +
                "sent_as_transfer, sent_at) VALUES (7, 1, 1, 'SLS1-mmmmmmmmmmmmmmmmmmmmmmmmmm', 0, 20375, -42500, 0, 1760540100000)",
            "an Upload Plan without a state" to
                "INSERT INTO upload_plan (budget_id, prepared_at, expires_at, new_count, match_count, counterpart_count, total_inflow, total_outflow) " +
                "VALUES (1, 1760540000000, 1760540600000, 0, 0, 0, 0, 0)",
            "an Eligible Transaction without the marker for clearing its Transfer Counterpart" to
                "INSERT INTO eligible_transaction (upload_plan_id, bank_transaction_id, upload_kind) VALUES (1, 7, 'New')",
            "a Transaction File without its number of Bank Transactions" to
                "INSERT INTO transaction_file (bank_account_id, file_format_id, imported_at, outcome) VALUES (2, 3, 1760540000000, 'Imported')",
        ).filterValues { sql -> runCatching { connection.execSQL(sql) }.isSuccess }

        assertEquals(emptySet<String>(), stored.keys, "stored although the schema must refuse it")
        assertEquals(listOf(listOf("1", "1", "Already Present", "ynab-tx-0001", "1", "-42500", "1760540000000")), query("SELECT * FROM review_decision"))
        assertEquals(listOf(listOf("1", "1", "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa"), listOf("2", "2", "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa")), query("SELECT id, budget_account_id, import_identifier FROM sent_record ORDER BY id"))
        assertEquals(listOf(listOf("1", "1", "ynab-tx-0201", "Uncleared", null, "3")), query("SELECT id, sent_record_id, budget_app_reference, state, cleared_at, settled_by_bank_transaction_id FROM transfer_counterpart"))
        assertEquals(listOf(listOf("1", "0", "Header"), listOf("1", "1", "Create"), listOf("2", "1", "Create")), query("SELECT upload_plan_id, position, write_kind FROM upload_plan_item ORDER BY upload_plan_id, position"))
        (newInVersion6 + "transfer").forEach { table ->
            assertEquals(emptyList<List<String?>>(), query("SELECT name, dflt_value FROM pragma_table_info('$table') WHERE dflt_value IS NOT NULL"), "$table fills in no value")
        }
        assertTrue(query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    @DisplayName("NFR-017: slussen.db created at every committed version migrates, one Migration per version, to a schema Room accepts as the current version, with every table and index stated exactly as in a slussen.db created new (ADR-002 §7)")
    fun everyCommittedVersionMigrates() {
        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        val delegate = RoomSchemas.openDelegateOf(SlussenDatabase::class)
        val new = BundledSQLiteDriver().open(":memory:")
        val created = try {
            create(SlussenDatabase::class.java, current, into = new)
            schema(of = new)
        } finally {
            new.close()
        }
        RoomSchemas.committedVersions(SlussenDatabase::class).forEach { version ->
            val fresh = BundledSQLiteDriver().open(":memory:")
            try {
                create(SlussenDatabase::class.java, version, into = fresh)
                (version until current).forEach { from ->
                    SlussenMigrations.ALL.single { it.startVersion == from && it.endVersion == from + 1 }.migrate(fresh)
                }
                val validation = delegate.onValidateSchema(fresh)
                assertTrue(validation.isValid, "from version $version: ${validation.expectedFoundMsg}")
                assertEquals(created, schema(of = fresh), "from version $version: every table and index is the one a new slussen.db gets")
            } finally {
                fresh.close()
            }
        }
        assertEquals(current - 1, SlussenMigrations.ALL.size)
    }

    @Test
    @DisplayName("NFR-017: the Migration 1 to 2 creates left_out_bank_account and its unique index exactly as the committed version 2 states them")
    fun migrationSqlIsTheCommittedVersion2() {
        val schema = File(RoomSchemas.committed, "${SlussenDatabase::class.java.name}/2.json").readText()
        val entity = schema.substringAfter("\"tableName\": \"left_out_bank_account\"").substringBefore("\"tableName\"")
        val expected = Regex(""""createSql"\s*:\s*"([^"]*)"""").findAll(entity)
            .map { it.groupValues[1].replace("\${TABLE_NAME}", "left_out_bank_account") }.toList()

        val statements = (SlussenMigrations.MIGRATION_1_2 as SlussenMigrations.Statements).statements

        assertEquals(expected, statements)
    }

    @Test
    @DisplayName("NFR-017: after the Migration a Data Provider's reference is left out at most once, and only for a stored Data Provider (UC-013 BR-011, ADR-002 §2)")
    fun migratedTableKeepsItsRules() {
        create(SlussenDatabase::class.java, 1)
        inventedVersion1Data()
        SlussenMigrations.MIGRATION_1_2.migrate(connection)
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("INSERT INTO left_out_bank_account (data_provider_id, provider_reference, name, left_out_at) VALUES (1, 'acct-2290', 'Kids Saver ••2290', 1760533200000)")

        val twice = runCatching {
            connection.execSQL("INSERT INTO left_out_bank_account (data_provider_id, provider_reference, name, left_out_at) VALUES (1, 'acct-2290', NULL, 1760533200000)")
        }
        val unknownProvider = runCatching {
            connection.execSQL("INSERT INTO left_out_bank_account (data_provider_id, provider_reference, name, left_out_at) VALUES (9, 'acct-9034', NULL, 1760533200000)")
        }

        assertTrue(twice.isFailure)
        assertTrue(unknownProvider.isFailure)
        assertEquals(listOf(listOf("1", "acct-2290", "Kids Saver ••2290")), query("SELECT data_provider_id, provider_reference, name FROM left_out_bank_account"))
    }

    @Test
    @DisplayName("NFR-017: device.db and journal.db are still at their committed version 1, so no Migration is due for them (ADR-002 §7)")
    fun deviceAndJournalNeedNoMigration() {
        listOf(DeviceDatabase::class, JournalDatabase::class).forEach { database ->
            assertEquals(1, RoomSchemas.currentOf(database).version, database.java.name)
            assertEquals(listOf(1), RoomSchemas.committedVersions(database))
        }
        create(DeviceDatabase::class.java, 1)
        val validation = RoomSchemas.openDelegateOf(DeviceDatabase::class).onValidateSchema(connection)
        assertTrue(validation.isValid, validation.expectedFoundMsg)
        assertFalse(tables().isEmpty())
    }
}
