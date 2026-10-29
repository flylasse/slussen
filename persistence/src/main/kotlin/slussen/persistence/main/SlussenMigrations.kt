package slussen.persistence.main

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * The hand-written Migrations of `slussen.db` (ADR-002 §7, S-39): one from every committed schema version to the next.
 * A Migration never deletes a row or a value of stored data and never rewrites an `import_identifier`,
 * `configuration_key` or `occurrence`; destructive fallback is never used (K-09).
 */
object SlussenMigrations {
    // UC-010 BR-007: The default Card Payment Descriptions and Bank Transfer Descriptions ship with the build.
    /**
     * The default lists of UC-010 BR-007, as `transfer_description` (`kind`, `text`), in the order the rule lists them:
     * every Card Payment Description, then every Bank Transfer Description.
     */
    val DEFAULT_TRANSFER_DESCRIPTIONS: List<Pair<String, String>> =
        listOf(
            "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY",
            "CRD AUTOPAY", "CITICARDAP",
        ).map { "Card Payment" to it } +
            listOf(
                "ÖVERFÖRING", "INTERNET TRANSFER", "EXPRESS INTERNET TRANSFER", "AMERICAN EXPRESS TRANSFER", "ONLINE TRANSFER",
                "TRANSFER", "XFER",
            ).map { "Bank Transfer" to it }

    /**
     * One insert per entry of [DEFAULT_TRANSFER_DESCRIPTIONS], in its order, into an empty `transfer_description`: run by
     * [MIGRATION_3_4] and when a new `slussen.db` is created (ADR-002 §8 step 3).
     */
    val INSERT_DEFAULT_TRANSFER_DESCRIPTIONS: List<String> = DEFAULT_TRANSFER_DESCRIPTIONS.map { (kind, text) ->
        "INSERT INTO `transfer_description` (`kind`, `text`) VALUES ('${kind.replace("'", "''")}', '${text.replace("'", "''")}')"
    }

    /**
     * Version 1 (committed at `96712aa`, the Operator's phone) to version 2: adds `left_out_bank_account` with its unique
     * index (S-38), exactly as Room's version 2 schema declares it. No existing table, row or value changes;
     * `budget_account.manually_kept` is already in version 1, false for every Budget Account until the Operator marks it.
     */
    val MIGRATION_1_2: Migration = Statements(
        from = 1,
        to = 2,
        "CREATE TABLE IF NOT EXISTS `left_out_bank_account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`data_provider_id` INTEGER NOT NULL, `provider_reference` TEXT NOT NULL, `name` TEXT, `left_out_at` INTEGER NOT NULL, " +
            "FOREIGN KEY(`data_provider_id`) REFERENCES `data_provider`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_left_out_bank_account_data_provider_id_provider_reference` " +
            "ON `left_out_bank_account` (`data_provider_id`, `provider_reference`)",
    )

    /**
     * Version 2 (committed with Phase 8d, the Operator's phone) to version 3: adds `account_balance` and `fetch_result`
     * with their indices (UC-001 steps 8, 11; Phase 8f), exactly as Room's version 3 schema declares them. No existing
     * table, row or value changes; both tables start empty and are filled by the next Fetch (O-1).
     */
    val MIGRATION_2_3: Migration = Statements(
        from = 2,
        to = 3,
        "CREATE TABLE IF NOT EXISTS `account_balance` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bank_account_id` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `reported_at` INTEGER NOT NULL, " +
            "FOREIGN KEY(`bank_account_id`) REFERENCES `bank_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_account_balance_bank_account_id` ON `account_balance` (`bank_account_id`)",
        "CREATE TABLE IF NOT EXISTS `fetch_result` (`fetch_id` INTEGER NOT NULL, `bank_account_id` INTEGER NOT NULL, " +
            "`outcome` TEXT NOT NULL, `transaction_count` INTEGER NOT NULL, PRIMARY KEY(`fetch_id`, `bank_account_id`), " +
            "FOREIGN KEY(`fetch_id`) REFERENCES `fetch`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`bank_account_id`) REFERENCES `bank_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_fetch_result_bank_account_id` ON `fetch_result` (`bank_account_id`)",
    )

    /**
     * Version 3 (committed with Phase 8f, the Operator's phone) to version 4: adds `budget_app_transaction`, `transfer`,
     * `bank_transaction`, `unreadable_transaction` and `transfer_description` with their indices and foreign keys
     * (transactions plan Phase 5), exactly as Room's version 4 schema declares them, and fills `transfer_description` with
     * the default lists of UC-010 BR-007. No existing table, row or value changes; the other four tables start empty and
     * are filled by the next Fetch (O-1) and the next Comparison (O-4, O-5).
     */
    val MIGRATION_3_4: Migration = Statements(
        3,
        4,
        "CREATE TABLE IF NOT EXISTS `budget_app_transaction` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`budget_account_id` INTEGER NOT NULL, `budget_app_reference` TEXT NOT NULL, `transaction_date` INTEGER NOT NULL, " +
            "`amount` INTEGER NOT NULL, `payee` TEXT, `memo` TEXT, `import_identifier` TEXT, `entered_by` TEXT NOT NULL, " +
            "`transfer_link_reference` TEXT, `merged_into_reference` TEXT, `cleared` INTEGER NOT NULL, `approved` INTEGER NOT NULL, " +
            "`state` TEXT NOT NULL, " +
            "FOREIGN KEY(`budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_app_transaction_budget_app_reference` " +
            "ON `budget_app_transaction` (`budget_app_reference`)",
        "CREATE INDEX IF NOT EXISTS `index_budget_app_transaction_budget_account_id` ON `budget_app_transaction` (`budget_account_id`)",
        "CREATE TABLE IF NOT EXISTS `transfer` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`outflow_budget_account_id` INTEGER NOT NULL, `inflow_budget_account_id` INTEGER NOT NULL, `amount` INTEGER NOT NULL, " +
            "`transfer_type` TEXT NOT NULL, `guessed` INTEGER NOT NULL, `decision` TEXT NOT NULL, `decided_at` INTEGER, " +
            "FOREIGN KEY(`outflow_budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`inflow_budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_transfer_outflow_budget_account_id` ON `transfer` (`outflow_budget_account_id`)",
        "CREATE INDEX IF NOT EXISTS `index_transfer_inflow_budget_account_id` ON `transfer` (`inflow_budget_account_id`)",
        "CREATE TABLE IF NOT EXISTS `bank_transaction` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bank_account_id` INTEGER NOT NULL, `stable_reference` TEXT, `reference_kind` TEXT NOT NULL, " +
            "`occurrence_number` INTEGER NOT NULL, `import_identifier` TEXT NOT NULL, `booked_date` INTEGER NOT NULL, " +
            "`amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `payee` TEXT, `memo` TEXT, `first_seen_at` INTEGER NOT NULL, " +
            "`status` TEXT, `transfer_id` INTEGER, " +
            "FOREIGN KEY(`bank_account_id`) REFERENCES `bank_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`transfer_id`) REFERENCES `transfer`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_transaction_bank_account_id_import_identifier` " +
            "ON `bank_transaction` (`bank_account_id`, `import_identifier`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_transaction_bank_account_id_stable_reference` " +
            "ON `bank_transaction` (`bank_account_id`, `stable_reference`)",
        "CREATE INDEX IF NOT EXISTS `index_bank_transaction_transfer_id` ON `bank_transaction` (`transfer_id`)",
        "CREATE TABLE IF NOT EXISTS `unreadable_transaction` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bank_account_id` INTEGER NOT NULL, `booked_date` INTEGER, `amount` INTEGER, `currency` TEXT, `payee` TEXT, `memo` TEXT, " +
            "`reason` TEXT NOT NULL, `first_seen_at` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
            "FOREIGN KEY(`bank_account_id`) REFERENCES `bank_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_unreadable_transaction_bank_account_id` ON `unreadable_transaction` (`bank_account_id`)",
        "CREATE TABLE IF NOT EXISTS `transfer_description` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`kind` TEXT NOT NULL, `text` TEXT NOT NULL)",
        *INSERT_DEFAULT_TRANSFER_DESCRIPTIONS.toTypedArray(),
    )

    /**
     * Version 4 (committed with transactions plan Phase 5) to version 5: adds the technical table `budget_read_state`
     * (ADR-002 §2) that keeps the Budget App read cursor of each Budget (transactions plan Phase 7a), exactly as Room's
     * version 5 schema declares it. No existing table, row or value changes; it starts empty, so the next read of each
     * Budget is its first (UC-003 BR-016) and is stored with its cursor by O-4.
     */
    val MIGRATION_4_5: Migration = Statements(
        4,
        5,
        "CREATE TABLE IF NOT EXISTS `budget_read_state` (`budget_id` INTEGER NOT NULL, `change_cursor` TEXT NOT NULL, " +
            "PRIMARY KEY(`budget_id`), FOREIGN KEY(`budget_id`) REFERENCES `budget`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    )

    /** Every Migration of `slussen.db`, given to Room when the database is opened (ADR-002 §7). */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    /**
     * A Migration made of SQL statements, run the same way on the platform's SQLite through Room's open helper (the app,
     * the instrumented tests) and on a driver connection (the JVM migration tests).
     */
    class Statements(from: Int, to: Int, vararg statements: String) : Migration(from, to) {
        val statements: List<String> = statements.toList()

        override fun migrate(db: SupportSQLiteDatabase) {
            statements.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            statements.forEach { connection.execSQL(it) }
        }
    }
}
