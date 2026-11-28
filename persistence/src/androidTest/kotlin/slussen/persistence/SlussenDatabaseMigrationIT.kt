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
import slussen.core.port.StoredTransfer
import slussen.core.port.TransferDecision
import slussen.core.port.TransferType
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.InMemoryConsentStore
import slussen.persistence.main.ReviewDecisionEntity
import slussen.persistence.main.SentRecordEntity
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.SlussenMigrations
import slussen.persistence.main.TransferEntity
import slussen.persistence.main.UploadPlanEntity
import java.time.Instant
import java.time.LocalDate

/**
 * Evidence E-06 of ADR-005 §7 (ADR-002 §7, S-39): `slussen.db` created at every committed schema version with Room's
 * `MigrationTestHelper` from the committed schema files, with invented rows in every table, migrated with the
 * hand-written Migrations to the current version and validated by Room, keeping every row and value; a version 1, 2,
 * 3 and 5 `slussen.db`, as the Operator's phone held and holds it, opened by the app's own [SlussenDatabases.openMain]
 * and read back, the Transfers of version 5 with their Transfer Legs, whose table the Migration 5 to 6 rebuilds; and a
 * new `slussen.db`, which starts with the default Description lists (UC-010 BR-007).
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
            assertTrue(db.tables().containsAll(listOf("left_out_bank_account", "account_balance", "fetch_result") + NEW_IN_VERSION_4 + NEW_IN_VERSION_5 + NEW_IN_VERSION_6))
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
            assertEquals((listOf("account_balance", "fetch_result") + NEW_IN_VERSION_4 + NEW_IN_VERSION_5 + NEW_IN_VERSION_6).sorted(), db.tables() - version2Tables.toSet())
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
            assertEquals((NEW_IN_VERSION_4 + NEW_IN_VERSION_5 + NEW_IN_VERSION_6).sorted(), db.tables() - version3Tables.toSet())
            assertEquals(DEFAULT_DESCRIPTIONS, db.select("SELECT kind, text FROM transfer_description ORDER BY id"))
            (NEW_IN_VERSION_4 - "transfer_description").forEach { assertEquals(listOf(listOf("0")), db.select("SELECT COUNT(*) FROM `$it`"), it) }
        }
    }

    /** Invented rows in every table of version 4: those of version 3 and a stored copy, a Transfer and Bank Transactions (Phase 5). */
    private fun SupportSQLiteDatabase.inventedVersion4Data() {
        inventedVersion3Data()
        execSQL(
            "INSERT INTO budget_app_transaction VALUES (1, 1, 'ynab-tx-0001', 20375, -42500, 'Corner Grocer', 'PLANTED-MEMO', " +
                "'YNAB:-42500:2026-10-14:1', 'Other Import Path', NULL, NULL, 1, 0, 'Present')",
        )
        execSQL("INSERT INTO transfer VALUES (1, 1, 2, 150000, 'Bank Transfer', 0, 'Proposed', NULL)")
        execSQL(
            "INSERT INTO bank_transaction VALUES (1, 1, 'TX-0001', 'Supplied', 1, 'SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa', 20375, -42500, 'USD', " +
                "'Corner Grocer', NULL, 1760533200000, 'New', 1)",
        )
        execSQL("INSERT INTO unreadable_transaction VALUES (1, 1, NULL, NULL, NULL, 'Harbor Fuel', NULL, 'date missing', 1760533200000, 'Needs Review')")
        execSQL("INSERT INTO transfer_description (kind, text) VALUES ('Bank Transfer', 'INVENTED TRANSFER')")
    }

    @Test
    @DisplayName("NFR-017: E-06, slussen.db at version 4 with rows in every table migrates to the current version, Room validates it, every row and value is kept, and budget_read_state starts empty (UC-003 BR-016; ADR-002 §7)")
    fun version4MigratesKeepingEveryValue() {
        val name = "migration-version-4.db"
        val (version4Tables, before) = helper.createDatabase(name, 4).use { db ->
            db.inventedVersion4Data()
            val tables = db.tables()
            assertTrue(tables.all { db.select("SELECT COUNT(*) FROM `$it`").single().single() != "0" }, "every table holds a row")
            tables to db.everything(tables)
        }

        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, *SlussenMigrations.ALL).use { db ->
            assertEquals(before, db.everything(version4Tables))
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertEquals((NEW_IN_VERSION_5 + NEW_IN_VERSION_6).sorted(), db.tables() - version4Tables.toSet())
            assertEquals(listOf(listOf("0")), db.select("SELECT COUNT(*) FROM budget_read_state"))
        }
    }

    /**
     * Invented rows in every table of version 5: those of version 4, a read cursor, a second Bank Account with its Account
     * Mapping, and Transfers with their Transfer Legs: a pair (Transfer 1), a Lonely Transfer that is a Guessed Transfer
     * (2), a pair the Operator confirmed (5), and one that was removed again (9), so that the identifiers of the Transfers
     * have gaps and the last one given names no stored Transfer. Bank Transaction 6 is an ordinary one, in no Transfer.
     */
    private fun SupportSQLiteDatabase.inventedVersion5Data() {
        inventedVersion4Data()
        listOf(
            "INSERT INTO budget_read_state VALUES (1, '4182', 0)",
            "INSERT INTO bank_account VALUES (2, '7a2d3b5f-1c8e-4d2f-8b66-3f9f4c2d1e5b', 1, 1, 1, NULL, NULL, 'Kestrel Reserve', 'USD', " +
                "'Savings', NULL, 'acct-0907', 'fp-acct-0907', NULL, 1760529600000, 0, NULL, NULL)",
            "INSERT INTO account_mapping VALUES (2, 2, 2, NULL)",
            "INSERT INTO transfer VALUES (2, 1, 2, 98760, 'Card Payment', 1, 'Proposed', NULL)",
            "INSERT INTO transfer VALUES (5, 2, 1, 5000, 'Bank Transfer', 0, 'Confirmed', 1760540000000)",
            "INSERT INTO transfer VALUES (9, 1, 2, 1000, 'Bank Transfer', 0, 'Proposed', NULL)",
            "DELETE FROM transfer WHERE id = 9",
            "INSERT INTO bank_transaction VALUES (2, 2, 'TX-0102', 'Supplied', 1, 'SLS1-bbbbbbbbbbbbbbbbbbbbbbbbbb', 20376, 42500, 'USD', " +
                "'From Kestrel Checking', NULL, 1760533200000, 'Transfer Leg', 1)",
            "INSERT INTO bank_transaction VALUES (3, 2, 'TX-0103', 'Supplied', 1, 'SLS1-cccccccccccccccccccccccccc', 20378, 98760, 'USD', " +
                "'AUTOPAY', 'PLANTED-MEMO', 1760533200000, 'Guessed Transfer', 2)",
            "INSERT INTO bank_transaction VALUES (4, 2, 'TX-0104', 'Derived', 1, 'SLS1-dddddddddddddddddddddddddd', 20379, -5000, 'USD', " +
                "'XFER', NULL, 1760536800000, 'Transfer Leg', 5)",
            "INSERT INTO bank_transaction VALUES (5, 1, 'TX-0105', 'Supplied', 1, 'SLS1-eeeeeeeeeeeeeeeeeeeeeeeeee', 20380, 5000, 'USD', " +
                "'XFER', NULL, 1760536800000, 'Transfer Leg', 5)",
            "INSERT INTO bank_transaction VALUES (6, 1, NULL, 'None', 2, 'SLS1-ffffffffffffffffffffffffff', 20376, -1500, 'USD', " +
                "NULL, NULL, 1760533200000, 'Needs Review', NULL)",
        ).forEach(::execSQL)
    }

    /** Every Transfer Leg with the Transfer its `transfer_id` names, every value of that Transfer included: a lost reference loses its line. */
    private fun SupportSQLiteDatabase.transferLegsWithTheirTransfers(): List<List<String?>> = select(
        "SELECT bt.id, bt.import_identifier, t.id, t.outflow_budget_account_id, t.inflow_budget_account_id, t.amount, t.transfer_type, " +
            "t.guessed, t.decision, t.decided_at FROM bank_transaction bt JOIN transfer t ON t.id = bt.transfer_id ORDER BY bt.id",
    )

    @Test
    @DisplayName("NFR-017: E-06, slussen.db at version 5, as the Operator's phone holds it, with rows in every table and Transfers with their Transfer Legs migrates to the current version, Room validates it, every row and value and every reference from a Transfer Leg to its Transfer is kept, and the seven tables of version 6 start empty (entity model TRANSFER; ADR-002 §7)")
    fun version5MigratesKeepingEveryValue() {
        val name = "migration-version-5.db"
        data class Before(val tables: List<String>, val everything: Map<String, List<List<String?>>>, val legs: List<List<String?>>)
        val before = helper.createDatabase(name, 5).use { db ->
            db.inventedVersion5Data()
            val tables = db.tables()
            assertTrue(tables.all { db.select("SELECT COUNT(*) FROM `$it`").single().single() != "0" }, "every table holds a row")
            Before(tables, db.everything(tables), db.transferLegsWithTheirTransfers())
        }
        assertEquals(listOf("1", "2", "3", "4", "5"), before.legs.map { it.first() })

        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, *SlussenMigrations.ALL).use { db ->
            assertEquals(before.everything, db.everything(before.tables))
            assertEquals(before.legs, db.transferLegsWithTheirTransfers())
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertEquals(listOf(listOf("ok")), db.select("PRAGMA integrity_check"))
            assertEquals(NEW_IN_VERSION_6, db.tables() - before.tables.toSet())
            NEW_IN_VERSION_6.forEach { assertEquals(listOf(listOf("0")), db.select("SELECT COUNT(*) FROM `$it`"), it) }
            assertTrue(db.select("SELECT name FROM sqlite_master WHERE name LIKE '%new_transfer%'").isEmpty())
            assertEquals(listOf(listOf("9")), db.select("SELECT seq FROM sqlite_sequence WHERE name = 'transfer'"))
        }
    }

    @Test
    @DisplayName("NFR-017: E-06, a version 5 slussen.db as the Operator's phone holds it is upgraded by the app itself, never cleared: every Transfer is read back with its sides, decision and Transfer Legs; the rebuilt table still refuses a Transfer Leg of no stored Transfer and the removal of a Transfer with Transfer Legs; a Transfer may now lack its outflow side and gets an identifier above every earlier one; and the new tables are usable (entity model TRANSFER; ADR-002 §7)")
    fun theAppUpgradesAnInstalledVersion5() = runBlocking {
        helper.createDatabase(SlussenDatabases.MAIN_FILE, 5).use { it.inventedVersion5Data() }

        val main = SlussenDatabases.openMain(DeviceFiles.context)
        try {
            val connections = RoomConnectionStore(main.connections())
            val data = RoomComparisonStore(main, connections).comparisonDataOf(setOf(1))
            assertEquals(listOf("Kestrel Checking", "Kestrel Reserve"), connections.bankAccounts().map { it.name })
            assertEquals(
                listOf(
                    StoredTransfer(1, 1, 2, Amount(150000), TransferType.BankTransfer, guessed = false, TransferDecision.Proposed, decidedAt = null),
                    StoredTransfer(2, 1, 2, Amount(98760), TransferType.CardPayment, guessed = true, TransferDecision.Proposed, decidedAt = null),
                    StoredTransfer(5, 2, 1, Amount(5000), TransferType.BankTransfer, guessed = false, TransferDecision.Confirmed, Instant.ofEpochMilli(1760540000000)),
                ),
                data.transfers.sortedBy { it.id },
            )
            assertEquals(
                listOf(1L to 1L, 2L to 1L, 3L to 2L, 4L to 5L, 5L to 5L, 6L to null),
                data.bankTransactions.sortedBy { it.id }.map { it.id to it.transferId },
            )
            val db = main.openHelper.writableDatabase
            fun transferLeg(importIdentifier: String, transfer: Long) =
                "INSERT INTO bank_transaction (bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                    "amount, currency, payee, memo, first_seen_at, status, transfer_id) VALUES (2, NULL, 'None', 1, '$importIdentifier', 20381, 98760, " +
                    "'USD', 'AUTOPAY', NULL, 1760536800000, NULL, $transfer)"
            assertTrue(runCatching { db.execSQL(transferLeg("SLS1-gggggggggggggggggggggggggg", transfer = 9)) }.isFailure, "a Transfer Leg of no stored Transfer")
            assertTrue(runCatching { db.execSQL("DELETE FROM transfer WHERE id = 5") }.isFailure, "a Transfer with Transfer Legs removed")
            val withoutOutflow = main.budgetAppCopy().insertTransfer(
                TransferEntity(outflowBudgetAccountId = null, inflowBudgetAccountId = 2, amount = 98760, transferType = "Card Payment", guessed = true, decision = "Proposed", decidedAt = null),
            )
            assertEquals(10L, withoutOutflow)
            db.execSQL(transferLeg("SLS1-hhhhhhhhhhhhhhhhhhhhhhhhhh", transfer = withoutOutflow))
            assertEquals(null, RoomComparisonStore(main, connections).comparisonDataOf(setOf(1)).transfers.single { it.id == withoutOutflow }.outflowBudgetAccountId)
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())

            val uploads = main.uploads()
            val planId = uploads.insertPlan(
                UploadPlanEntity(
                    budgetId = 1, bankAccountId = null, preparedAt = Instant.ofEpochMilli(1760540000000), expiresAt = Instant.ofEpochMilli(1760540600000),
                    newCount = 1, matchCount = 0, counterpartCount = 0, totalInflow = 0, totalOutflow = 42500, state = "Confirmed",
                ),
            )
            val sent = uploads.insertSentRecord(
                SentRecordEntity(
                    bankTransaction = 1, uploadPlanId = planId, budgetAccountId = 1, importIdentifier = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa", occurrence = 0,
                    bookedDate = LocalDate.ofEpochDay(20375), amount = -42500, sentAsTransfer = false, sentAt = Instant.ofEpochMilli(1760540100000),
                    outcome = "Written", budgetAppReference = "ynab-tx-0101",
                ),
            )
            main.reviewDecisions().store(
                ReviewDecisionEntity(bankTransaction = 6, choice = "Defer", chosenBudgetAppReference = null, chosenBudgetAccountId = null, chosenAmount = null, decidedAt = Instant.ofEpochMilli(1760540000000)),
            )
            assertEquals(listOf(sent), uploads.sentRecordsOf(1).map { it.id })
            assertEquals(listOf("Defer"), main.reviewDecisions().all().map { it.choice })
            assertTrue(db.select("PRAGMA foreign_key_check").isEmpty())
            assertEquals(listOf(listOf("ok")), db.select("PRAGMA integrity_check"))
        } finally {
            main.close()
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
            assertEquals(listOf(Amount(5135280)), RoomImportStatusStore(main.fetches(), RoomConnectionStore(main.connections()), main.transactionFiles(), InMemoryConsentStore()).accountBalances().map { it.amount })
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
            val status = RoomImportStatusStore(main.fetches(), RoomConnectionStore(main.connections()), main.transactionFiles(), InMemoryConsentStore())
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
        const val CURRENT_VERSION = 6

        /** The tables version 6 adds (entity model; ADR-002 §1, §2; build-out plan §3). */
        val NEW_IN_VERSION_6 = listOf(
            "eligible_transaction", "review_decision", "sent_record", "transaction_file", "transfer_counterpart", "upload_plan", "upload_plan_item",
        )

        /** The table version 5 adds (ADR-002 §2; transactions plan Phase 7a). */
        val NEW_IN_VERSION_5 = listOf("budget_read_state")

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
