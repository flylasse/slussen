package slussen.persistence

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
    private fun create(database: Class<*>, version: Int, into: SQLiteConnection = connection) {
        val schema = File(RoomSchemas.committed, "${database.name}/$version.json").readText()
        val token = Regex(""""(tableName|createSql)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        var table = ""
        token.findAll(schema.substringBefore("\"setupQueries\"")).forEach { match ->
            val value = match.groupValues[2].replace("\\\"", "\"")
            if (match.groupValues[1] == "tableName") table = value else into.execSQL(value.replace("\${TABLE_NAME}", table))
        }
        val setup = schema.substringAfter("\"setupQueries\"").substringAfter("[").substringBefore("]")
        Regex(""""((?:[^"\\]|\\.)*)"""").findAll(setup).forEach { into.execSQL(it.groupValues[1].replace("\\\"", "\"")) }
    }

    private fun tables(): List<String> = query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .map { it.single()!! }

    private fun query(sql: String): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        connection.prepare(sql).use { statement ->
            while (statement.step()) {
                rows += (0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) }
            }
        }
        return rows
    }

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
        assertEquals(setOf("left_out_bank_account", "account_balance", "fetch_result") + newInVersion4 + newInVersion5, everything().keys - version1Tables.toSet())
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
        assertEquals(setOf("account_balance", "fetch_result") + newInVersion4 + newInVersion5, everything().keys - version2Tables.toSet())
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
        assertEquals(newInVersion4 + newInVersion5, everything().keys - version3Tables.toSet())
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
        assertEquals(newInVersion5, everything().keys - version4Tables.toSet())
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
    @DisplayName("NFR-017: after the Migration 4 to 5 a Budget has at most one read cursor, and only a stored Budget has one (ADR-002 §2; UC-003 BR-020)")
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
        assertEquals(listOf(listOf("1", "4182")), query("SELECT budget_id, change_cursor FROM budget_read_state"))
    }

    @Test
    @DisplayName("NFR-017: slussen.db created at every committed version migrates, one Migration per version, to a schema Room accepts as the current version (ADR-002 §7)")
    fun everyCommittedVersionMigrates() {
        val current = RoomSchemas.currentOf(SlussenDatabase::class).version
        val delegate = RoomSchemas.openDelegateOf(SlussenDatabase::class)
        RoomSchemas.committedVersions(SlussenDatabase::class).forEach { version ->
            val fresh = BundledSQLiteDriver().open(":memory:")
            try {
                create(SlussenDatabase::class.java, version, into = fresh)
                (version until current).forEach { from ->
                    SlussenMigrations.ALL.single { it.startVersion == from && it.endVersion == from + 1 }.migrate(fresh)
                }
                val validation = delegate.onValidateSchema(fresh)
                assertTrue(validation.isValid, "from version $version: ${validation.expectedFoundMsg}")
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
