package slussen.persistence.main

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import slussen.persistence.StorageConverters
import java.time.Instant
import java.time.LocalDate

/**
 * `slussen.db` (ADR-002 §1): the only database that is backed up and transferred, holding all Decision-Critical
 * Data. Phase 7 holds the tables of UC-008, UC-013 and UC-014, Phase 7a adds `data_provider.request_limit_published`
 * (S-31) and `bank_account.provider_bank_id` (S-33); the other tables of ADR-002 §1 come with the slices that use them, within schema version 1 until the first
 * release: a migration is written only from a released schema version (ADR-002 §7).
 */
@Database(
    entities = [
        OperatorSettingsEntity::class,
        DataProviderEntity::class,
        FileFormatEntity::class,
        BankEntity::class,
        BudgetEntity::class,
        BudgetAccountEntity::class,
        BankAccountEntity::class,
        AccountMappingEntity::class,
        ProviderRequestEntity::class,
        RecoveryCheckEntity::class,
        CoordinatorStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(StorageConverters::class)
abstract class SlussenDatabase : RoomDatabase() {
    abstract fun settings(): OperatorSettingsDao

    abstract fun registry(): PluginRegistryDao

    abstract fun connections(): ConnectionDao

    abstract fun providerRequests(): ProviderRequestDao

    abstract fun recovery(): RecoveryDao

    abstract fun coordinator(): CoordinatorDao
}

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

/** `DATA_PROVIDER` (entity model). */
@Entity(
    tableName = "data_provider",
    indices = [Index(value = ["name"], unique = true), Index(value = ["source_key"], unique = true)],
)
data class DataProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "stable_reference") val stableReference: Boolean,
    @ColumnInfo(name = "bank_consent_required") val bankConsentRequired: Boolean,
    @ColumnInfo(name = "fetch_cooldown_minutes") val fetchCooldownMinutes: Int,
    @ColumnInfo(name = "request_limit") val requestLimit: Int,
    @ColumnInfo(name = "request_limit_published") val requestLimitPublished: Boolean,
)

/** `FILE_FORMAT` (entity model). */
@Entity(
    tableName = "file_format",
    indices = [Index(value = ["name"], unique = true), Index(value = ["source_key"], unique = true)],
)
data class FileFormatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
)

/** `BANK` (entity model). */
@Entity(tableName = "bank", indices = [Index(value = ["name"], unique = true)])
data class BankEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "country") val country: String?,
)

/** `BUDGET` (entity model). */
@Entity(tableName = "budget", indices = [Index(value = ["budget_app_reference"], unique = true)])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "budget_app_reference") val budgetAppReference: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Instant?,
    @ColumnInfo(name = "served") val served: Boolean,
)

/** `BUDGET_ACCOUNT` (entity model); `balance` in thousandths of the currency unit (ADR-002 §2). */
@Entity(
    tableName = "budget_account",
    indices = [Index(value = ["budget_app_reference"], unique = true), Index(value = ["budget_id"])],
    foreignKeys = [ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budget_id"])],
)
data class BudgetAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "budget_id") val budgetId: Long,
    @ColumnInfo(name = "budget_app_reference") val budgetAppReference: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "manually_kept") val manuallyKept: Boolean,
    @ColumnInfo(name = "balance") val balance: Long?,
    @ColumnInfo(name = "balance_read_at") val balanceReadAt: Instant?,
)

/** `BANK_ACCOUNT` (entity model); `generated_identifier` has a unique index and never changes (ADR-002 §2). */
@Entity(
    tableName = "bank_account",
    indices = [
        Index(value = ["generated_identifier"], unique = true),
        Index(value = ["bank_id"]),
        Index(value = ["provider_bank_id"]),
        Index(value = ["data_provider_id"]),
        Index(value = ["file_format_id"]),
        Index(value = ["balance_data_provider_id"]),
        Index(value = ["funded_by_budget_account_id"]),
        Index(value = ["transfer_partner_budget_account_id"]),
    ],
    foreignKeys = [
        ForeignKey(entity = BankEntity::class, parentColumns = ["id"], childColumns = ["bank_id"]),
        ForeignKey(entity = BankEntity::class, parentColumns = ["id"], childColumns = ["provider_bank_id"]),
        ForeignKey(entity = DataProviderEntity::class, parentColumns = ["id"], childColumns = ["data_provider_id"]),
        ForeignKey(entity = FileFormatEntity::class, parentColumns = ["id"], childColumns = ["file_format_id"]),
        ForeignKey(entity = DataProviderEntity::class, parentColumns = ["id"], childColumns = ["balance_data_provider_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["funded_by_budget_account_id"]),
        ForeignKey(
            entity = BudgetAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transfer_partner_budget_account_id"],
        ),
    ],
)
data class BankAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "generated_identifier") val generatedIdentifier: String,
    @ColumnInfo(name = "bank_id") val bankId: Long,
    @ColumnInfo(name = "provider_bank_id") val providerBankId: Long?,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long?,
    @ColumnInfo(name = "file_format_id") val fileFormatId: Long?,
    @ColumnInfo(name = "balance_data_provider_id") val balanceDataProviderId: Long?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "bank_account_type") val bankAccountType: String,
    @ColumnInfo(name = "start_date") val startDate: LocalDate?,
    @ColumnInfo(name = "provider_reference") val providerReference: String?,
    @ColumnInfo(name = "bank_account_fingerprint") val bankAccountFingerprint: String?,
    @ColumnInfo(name = "balance_provider_reference") val balanceProviderReference: String?,
    @ColumnInfo(name = "identified_at") val identifiedAt: Instant,
    @ColumnInfo(name = "retired") val retired: Boolean,
    @ColumnInfo(name = "funded_by_budget_account_id") val fundedByBudgetAccountId: Long?,
    @ColumnInfo(name = "transfer_partner_budget_account_id") val transferPartnerBudgetAccountId: Long?,
)

/** `ACCOUNT_MAPPING` (entity model): a Bank Account has at most one, a Budget Account receives through at most one. */
@Entity(
    tableName = "account_mapping",
    indices = [Index(value = ["bank_account_id"], unique = true), Index(value = ["budget_account_id"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["budget_account_id"]),
    ],
)
data class AccountMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long,
    @ColumnInfo(name = "budget_account_id") val budgetAccountId: Long,
    @ColumnInfo(name = "first_sent_at") val firstSentAt: Instant?,
)

/** `PROVIDER_REQUEST` (entity model). */
@Entity(
    tableName = "provider_request",
    indices = [Index(value = ["data_provider_id", "sent_at"]), Index(value = ["bank_account_id"])],
    foreignKeys = [
        ForeignKey(entity = DataProviderEntity::class, parentColumns = ["id"], childColumns = ["data_provider_id"]),
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"]),
    ],
)
data class ProviderRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long?,
    @ColumnInfo(name = "request_kind") val requestKind: String,
    @ColumnInfo(name = "unattended") val unattended: Boolean,
    @ColumnInfo(name = "sent_at") val sentAt: Instant,
)

/** `RECOVERY_CHECK` (entity model). */
@Entity(tableName = "recovery_check")
data class RecoveryCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "cause") val cause: String,
    @ColumnInfo(name = "opened_at") val openedAt: Instant,
    @ColumnInfo(name = "compared_at") val comparedAt: Instant?,
    @ColumnInfo(name = "confirmed_at") val confirmedAt: Instant?,
)

/** The technical table `coordinator_state` of ADR-002 §2: one row. */
@Entity(tableName = "coordinator_state")
data class CoordinatorStateEntity(
    @PrimaryKey val id: Long = SINGLE_RECORD,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "upload_plan_id") val uploadPlanId: Long?,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    @ColumnInfo(name = "scheduled_fetch_deferred") val scheduledFetchDeferred: Boolean,
    @ColumnInfo(name = "comparison_pending") val comparisonPending: Boolean,
) {
    companion object {
        const val SINGLE_RECORD = 1L
    }
}
