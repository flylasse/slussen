package slussen.persistence.device

import androidx.room.migration.Migration
import slussen.persistence.main.SlussenMigrations

/**
 * The hand-written Migrations of `device.db` (ADR-002 §7, S-39): one from every committed schema version to the next.
 * A Migration never deletes a row or a value of stored data; destructive fallback is never used (K-09).
 */
object DeviceMigrations {
    /**
     * Version 1 (committed at the baseline `96712aa`, the Operator's phone) to version 2 (S-B-45): creates
     * `bank_consent`, `bank_session`, `consent_request` and `consent_request_correlation` with their indices and foreign
     * keys, exactly as Room's version 2 schema declares them, and changes nothing else. `service_credential` and
     * `offered_bank_account` keep every row and value; the new tables start empty, since no Bank Consent could be
     * granted before.
     */
    val MIGRATION_1_2: Migration = SlussenMigrations.Statements(
        from = 1,
        to = 2,
        "CREATE TABLE IF NOT EXISTS `bank_consent` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bank_id` INTEGER NOT NULL, " +
            "`data_provider_id` INTEGER NOT NULL, `granted_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `refused_at` INTEGER)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_consent_bank_id_data_provider_id` ON `bank_consent` (`bank_id`, `data_provider_id`)",
        "CREATE TABLE IF NOT EXISTS `bank_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bank_consent_id` INTEGER NOT NULL, " +
            "`session_reference` TEXT NOT NULL, `opened_at` INTEGER NOT NULL, " +
            "FOREIGN KEY(`bank_consent_id`) REFERENCES `bank_consent`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_session_bank_consent_id` ON `bank_session` (`bank_consent_id`)",
        "CREATE TABLE IF NOT EXISTS `consent_request` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bank_reference` TEXT NOT NULL, " +
            "`data_provider_id` INTEGER NOT NULL, `started_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `answered` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `consent_request_correlation` (`consent_request_id` INTEGER NOT NULL, `state_digest` TEXT NOT NULL, " +
            "`provider_handle` TEXT, PRIMARY KEY(`consent_request_id`), " +
            "FOREIGN KEY(`consent_request_id`) REFERENCES `consent_request`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_consent_request_correlation_state_digest` ON `consent_request_correlation` (`state_digest`)",
    )

    /** Every Migration of `device.db`, given to Room when the database is opened (ADR-002 §7). */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
