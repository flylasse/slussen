package slussen.persistence.main

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import slussen.persistence.StorageConverters

/**
 * `slussen.db` (ADR-002 §1): the only database that is backed up and transferred, holding all Decision-Critical
 * Data. The module skeleton holds `operator_settings`; the other tables of ADR-002 §1 come with the slices that use
 * them, within schema version 1 until the first release (ADR-002 §7).
 */
@Database(entities = [OperatorSettingsEntity::class], version = 1, exportSchema = true)
@TypeConverters(StorageConverters::class)
abstract class SlussenDatabase : RoomDatabase()

/** `OPERATOR_SETTINGS` (entity model): exactly one record. Ranges and defaults are rules of the core. */
@Entity(tableName = "operator_settings")
data class OperatorSettingsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "upload_window_days") val uploadWindowDays: Int,
    @ColumnInfo(name = "file_upload_window_days") val fileUploadWindowDays: Int,
    @ColumnInfo(name = "fetch_period_days") val fetchPeriodDays: Int,
    @ColumnInfo(name = "default_wait_hours") val defaultWaitHours: Int,
    @ColumnInfo(name = "request_limit_warning_hours") val requestLimitWarningHours: Int,
    @ColumnInfo(name = "request_timeout_seconds") val requestTimeoutSeconds: Int,
    @ColumnInfo(name = "upload_plan_minutes") val uploadPlanMinutes: Int,
    @ColumnInfo(name = "consent_request_minutes") val consentRequestMinutes: Int,
    @ColumnInfo(name = "fetch_history_size") val fetchHistorySize: Int,
    @ColumnInfo(name = "scheduled_fetches_per_day") val scheduledFetchesPerDay: Int,
    @ColumnInfo(name = "transfer_pairing_days") val transferPairingDays: Int,
    @ColumnInfo(name = "lonely_transfer_grace_days") val lonelyTransferGraceDays: Int,
    @ColumnInfo(name = "outdated_balance_hours") val outdatedBalanceHours: Int,
    @ColumnInfo(name = "consent_warning_days") val consentWarningDays: Int,
    @ColumnInfo(name = "journal_on") val journalOn: Boolean,
    @ColumnInfo(name = "journal_size_limit_mb") val journalSizeLimitMb: Int,
    @ColumnInfo(name = "journal_warning_percent") val journalWarningPercent: Int,
    @ColumnInfo(name = "backup_warning_percent") val backupWarningPercent: Int,
    @ColumnInfo(name = "detailed_logging") val detailedLogging: Boolean,
    @ColumnInfo(name = "upload_as_cleared") val uploadAsCleared: Boolean,
)
