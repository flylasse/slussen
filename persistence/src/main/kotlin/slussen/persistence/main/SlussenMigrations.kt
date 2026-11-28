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
            "`period_read_owed` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`budget_id`), FOREIGN KEY(`budget_id`) REFERENCES `budget`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    )

    /**
     * Version 5 (committed with transactions plan Phase 7a, the Operator's phone) to version 6 (build-out plan §3): lets
     * `transfer.outflow_budget_account_id` be empty (S-B-05) and adds `review_decision`, `upload_plan`,
     * `eligible_transaction`, `sent_record`, `transfer_counterpart`, `upload_plan_item` and `transaction_file` with their
     * indices and foreign keys, exactly as Room's version 6 schema declares them. The seven tables start empty.
     *
     * SQLite cannot change a column of a table in place, so `transfer`, which `bank_transaction.transfer_id` refers to,
     * is rebuilt by the procedure SQLite documents for a table other tables refer to ("Making Other Kinds Of Table Schema
     * Changes", `ALTER TABLE`), the one Room generates for its own table rebuilds: the new table is created under another
     * name, every row is copied with every column named and its `id` kept, the old table is dropped, the new one takes its
     * name, the two indices are created again, and `PRAGMA foreign_key_check` on `transfer` and `bank_transaction` must
     * find nothing ([Statements.foreignKeysChecked]). The order matters: renaming the old table away first would make
     * SQLite 3.26 and later point `bank_transaction.transfer_id` at the renamed old table. Foreign keys are off while Room
     * runs a Migration (the generated database switches them on only when it is open), which the procedure requires:
     * with them on, SQLite refuses to drop `transfer` while a Transfer Leg names a Transfer, and Room rolls the Migration
     * back. `bank_transaction` is not touched, so every `transfer_id` names the same `id` after the rebuild.
     *
     * `sqlite_sequence` is carried over as well: it holds the highest `id` a Transfer ever had, also of one a Comparison
     * removed since, and the copy alone would restart from the highest one still stored. No row and no value is deleted or
     * rewritten (ADR-002 §7).
     */
    val MIGRATION_5_6: Migration = Statements(
        5,
        6,
        "CREATE TABLE IF NOT EXISTS `_new_transfer` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`outflow_budget_account_id` INTEGER, `inflow_budget_account_id` INTEGER NOT NULL, `amount` INTEGER NOT NULL, " +
            "`transfer_type` TEXT NOT NULL, `guessed` INTEGER NOT NULL, `decision` TEXT NOT NULL, `decided_at` INTEGER, " +
            "FOREIGN KEY(`outflow_budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`inflow_budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "INSERT INTO `_new_transfer` (`id`, `outflow_budget_account_id`, `inflow_budget_account_id`, `amount`, " +
            "`transfer_type`, `guessed`, `decision`, `decided_at`) SELECT `id`, `outflow_budget_account_id`, " +
            "`inflow_budget_account_id`, `amount`, `transfer_type`, `guessed`, `decision`, `decided_at` FROM `transfer`",
        "DELETE FROM sqlite_sequence WHERE name = '_new_transfer'",
        "INSERT INTO sqlite_sequence (name, seq) SELECT '_new_transfer', seq FROM sqlite_sequence WHERE name = 'transfer'",
        "DROP TABLE `transfer`",
        "ALTER TABLE `_new_transfer` RENAME TO `transfer`",
        "CREATE INDEX IF NOT EXISTS `index_transfer_outflow_budget_account_id` ON `transfer` (`outflow_budget_account_id`)",
        "CREATE INDEX IF NOT EXISTS `index_transfer_inflow_budget_account_id` ON `transfer` (`inflow_budget_account_id`)",
        "CREATE TABLE IF NOT EXISTS `review_decision` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bank_transaction_id` INTEGER NOT NULL, `choice` TEXT NOT NULL, `chosen_budget_app_reference` TEXT, " +
            "`chosen_budget_account_id` INTEGER, `chosen_amount` INTEGER, `decided_at` INTEGER NOT NULL, " +
            "FOREIGN KEY(`bank_transaction_id`) REFERENCES `bank_transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`chosen_budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_decision_bank_transaction_id` ON `review_decision` (`bank_transaction_id`)",
        "CREATE INDEX IF NOT EXISTS `index_review_decision_chosen_budget_account_id` ON `review_decision` (`chosen_budget_account_id`)",
        "CREATE TABLE IF NOT EXISTS `upload_plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`budget_id` INTEGER NOT NULL, `bank_account_id` INTEGER, `prepared_at` INTEGER NOT NULL, " +
            "`expires_at` INTEGER NOT NULL, `new_count` INTEGER NOT NULL, `match_count` INTEGER NOT NULL, " +
            "`counterpart_count` INTEGER NOT NULL, `total_inflow` INTEGER NOT NULL, `total_outflow` INTEGER NOT NULL, " +
            "`state` TEXT NOT NULL, " +
            "FOREIGN KEY(`budget_id`) REFERENCES `budget`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`bank_account_id`) REFERENCES `bank_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_budget_id` ON `upload_plan` (`budget_id`)",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_bank_account_id` ON `upload_plan` (`bank_account_id`)",
        "CREATE TABLE IF NOT EXISTS `eligible_transaction` (`upload_plan_id` INTEGER NOT NULL, " +
            "`bank_transaction_id` INTEGER NOT NULL, `upload_kind` TEXT NOT NULL, `clear_counterpart` INTEGER NOT NULL, " +
            "PRIMARY KEY(`upload_plan_id`, `bank_transaction_id`), " +
            "FOREIGN KEY(`upload_plan_id`) REFERENCES `upload_plan`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`bank_transaction_id`) REFERENCES `bank_transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_eligible_transaction_bank_transaction_id` ON `eligible_transaction` (`bank_transaction_id`)",
        "CREATE TABLE IF NOT EXISTS `sent_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bank_transaction_id` INTEGER NOT NULL, `upload_plan_id` INTEGER NOT NULL, " +
            "`budget_account_id` INTEGER NOT NULL, `import_identifier` TEXT NOT NULL, `occurrence` INTEGER NOT NULL, " +
            "`booked_date` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `sent_as_transfer` INTEGER NOT NULL, " +
            "`sent_at` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `budget_app_reference` TEXT, " +
            "FOREIGN KEY(`bank_transaction_id`) REFERENCES `bank_transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`upload_plan_id`) REFERENCES `upload_plan`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sent_record_budget_account_id_import_identifier` ON `sent_record` (`budget_account_id`, " +
            "`import_identifier`)",
        "CREATE INDEX IF NOT EXISTS `index_sent_record_bank_transaction_id` ON `sent_record` (`bank_transaction_id`)",
        "CREATE INDEX IF NOT EXISTS `index_sent_record_upload_plan_id` ON `sent_record` (`upload_plan_id`)",
        "CREATE TABLE IF NOT EXISTS `transfer_counterpart` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sent_record_id` INTEGER NOT NULL, `budget_account_id` INTEGER NOT NULL, `budget_app_reference` TEXT NOT NULL, " +
            "`state` TEXT NOT NULL, `cleared_at` INTEGER, `settled_by_bank_transaction_id` INTEGER, " +
            "FOREIGN KEY(`sent_record_id`) REFERENCES `sent_record`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`settled_by_bank_transaction_id`) REFERENCES `bank_transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transfer_counterpart_sent_record_id` ON `transfer_counterpart` (`sent_record_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transfer_counterpart_budget_app_reference` ON `transfer_counterpart` (`budget_app_reference`)",
        "CREATE INDEX IF NOT EXISTS `index_transfer_counterpart_budget_account_id` ON `transfer_counterpart` (`budget_account_id`)",
        "CREATE INDEX IF NOT EXISTS `index_transfer_counterpart_settled_by_bank_transaction_id` ON `transfer_counterpart` (`settled_by_bank_transaction_id`)",
        "CREATE TABLE IF NOT EXISTS `upload_plan_item` (`upload_plan_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
            "`write_kind` TEXT NOT NULL, `bank_transaction_id` INTEGER, `budget_account_id` INTEGER, " +
            "`import_identifier` TEXT, `occurrence` INTEGER, `booked_date` INTEGER, `amount` INTEGER, `payee_sent` TEXT, " +
            "`memo_sent` TEXT, `cleared` INTEGER, `transfer_budget_account_id` INTEGER, `clear_counterpart` INTEGER, " +
            "`transfer_counterpart_id` INTEGER, `counterpart_reference` TEXT, `reused_sent_record_id` INTEGER, " +
            "`reused_outcome` TEXT, `state_version` INTEGER, `change_cursor` TEXT, PRIMARY KEY(`upload_plan_id`, " +
            "`position`), " +
            "FOREIGN KEY(`upload_plan_id`) REFERENCES `upload_plan`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`bank_transaction_id`) REFERENCES `bank_transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`transfer_budget_account_id`) REFERENCES `budget_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`transfer_counterpart_id`) REFERENCES `transfer_counterpart`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`reused_sent_record_id`) REFERENCES `sent_record`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_item_bank_transaction_id` ON `upload_plan_item` (`bank_transaction_id`)",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_item_budget_account_id` ON `upload_plan_item` (`budget_account_id`)",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_item_transfer_budget_account_id` ON `upload_plan_item` (`transfer_budget_account_id`)",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_item_transfer_counterpart_id` ON `upload_plan_item` (`transfer_counterpart_id`)",
        "CREATE INDEX IF NOT EXISTS `index_upload_plan_item_reused_sent_record_id` ON `upload_plan_item` (`reused_sent_record_id`)",
        "CREATE TABLE IF NOT EXISTS `transaction_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bank_account_id` INTEGER NOT NULL, `file_format_id` INTEGER NOT NULL, `imported_at` INTEGER NOT NULL, " +
            "`outcome` TEXT NOT NULL, `transaction_count` INTEGER NOT NULL, `rejection_reason` TEXT, " +
            "FOREIGN KEY(`bank_account_id`) REFERENCES `bank_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`file_format_id`) REFERENCES `file_format`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_transaction_file_bank_account_id` ON `transaction_file` (`bank_account_id`)",
        "CREATE INDEX IF NOT EXISTS `index_transaction_file_file_format_id` ON `transaction_file` (`file_format_id`)",
        foreignKeysChecked = listOf("transfer", "bank_transaction"),
    )

    /** Every Migration of `slussen.db`, given to Room when the database is opened (ADR-002 §7). */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

    /**
     * A Migration made of SQL statements, run the same way on the platform's SQLite through Room's open helper (the app,
     * the instrumented tests) and on a driver connection (the JVM migration tests). After the statements it runs SQLite's
     * `PRAGMA foreign_key_check` on each of [foreignKeysChecked], the tables a rebuilt table and its references touch, and
     * fails, so that Room rolls the whole Migration back, when a reference names no stored record.
     */
    class Statements(from: Int, to: Int, vararg statements: String, val foreignKeysChecked: List<String> = emptyList()) : Migration(from, to) {
        val statements: List<String> = statements.toList()

        override fun migrate(db: SupportSQLiteDatabase) {
            statements.forEach(db::execSQL)
            foreignKeysChecked.forEach { table ->
                db.query("PRAGMA foreign_key_check($table)").use { violations -> check(violations.count == 0) { brokenReference(table) } }
            }
        }

        override fun migrate(connection: SQLiteConnection) {
            statements.forEach { connection.execSQL(it) }
            foreignKeysChecked.forEach { table ->
                connection.prepare("PRAGMA foreign_key_check($table)").use { violations -> check(!violations.step()) { brokenReference(table) } }
            }
        }

        private fun brokenReference(table: String) =
            "The Migration $startVersion to $endVersion would leave a reference of $table to no stored record; nothing is changed (ADR-002 §7)."
    }
}
