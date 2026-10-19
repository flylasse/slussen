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

    /** Every Migration of `slussen.db`, given to Room when the database is opened (ADR-002 §7). */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

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
