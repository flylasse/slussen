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
 * Data. Version 1, as committed at `96712aa`, holds the tables of UC-008, UC-013, UC-014 and the Fetch of UC-001; it is
 * final, because the Operator's phone holds it (ADR-002 §7, S-39). Version 2 adds `left_out_bank_account` (S-38) with
 * the hand-written [SlussenMigrations.MIGRATION_1_2]; version 3 adds `account_balance` and `fetch_result` (UC-001 steps
 * 8, 11; Phase 8f) with [SlussenMigrations.MIGRATION_2_3]; version 4 adds `budget_app_transaction`, `transfer`,
 * `bank_transaction`, `unreadable_transaction` and `transfer_description` (transactions plan Phase 5) with
 * [SlussenMigrations.MIGRATION_3_4], which fills the default Description lists. Every later schema change, also a table a
 * later slice adds, raises the version by one, commits its schema file and adds a Migration from the version before.
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
        LeftOutBankAccountEntity::class,
        ProviderRequestEntity::class,
        FetchEntity::class,
        FetchResultEntity::class,
        BackOffEntity::class,
        AccountBalanceEntity::class,
        BudgetAppTransactionEntity::class,
        TransferEntity::class,
        BankTransactionEntity::class,
        UnreadableTransactionEntity::class,
        TransferDescriptionEntity::class,
        FirstUploadMatchingEntity::class,
        CoordinatorStateEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(StorageConverters::class)
abstract class SlussenDatabase : RoomDatabase() {
    abstract fun settings(): OperatorSettingsDao

    abstract fun registry(): PluginRegistryDao

    abstract fun connections(): ConnectionDao

    abstract fun providerRequests(): ProviderRequestDao

    abstract fun fetches(): FetchDao

    abstract fun firstUploadMatching(): FirstUploadMatchingDao

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

// UC-013 BR-007: The Left-Out Bank Accounts are kept in the backed-up slussen.db, like the Bank Accounts, across a reinstall, restore or upgrade.
/**
 * `LEFT_OUT_BANK_ACCOUNT` (entity model, S-38): an Offered Bank Account the Operator chose not to import, remembered by
 * its Data Provider and the Data Provider's reference in the backed-up `slussen.db` (ADR-002 §2), while the Offered Bank
 * Accounts it filters live in `device.db`. Within one Data Provider a reference is left out at most once.
 */
@Entity(
    tableName = "left_out_bank_account",
    indices = [Index(value = ["data_provider_id", "provider_reference"], unique = true)],
    foreignKeys = [ForeignKey(entity = DataProviderEntity::class, parentColumns = ["id"], childColumns = ["data_provider_id"])],
)
data class LeftOutBankAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "provider_reference") val providerReference: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "left_out_at") val leftOutAt: Instant,
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

/** `FETCH` (entity model); its Fetch Results are the `fetch_result` rows O-1 stores for it. */
@Entity(
    tableName = "fetch",
    indices = [Index(value = ["data_provider_id", "started_at"])],
    foreignKeys = [ForeignKey(entity = DataProviderEntity::class, parentColumns = ["id"], childColumns = ["data_provider_id"])],
)
data class FetchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    @ColumnInfo(name = "started_by") val startedBy: String,
    @ColumnInfo(name = "outcome") val outcome: String,
)

/**
 * `FETCH_RESULT` (entity model): the outcome of one Fetch for one Bank Account, keyed by both (ADR-002 §2), stored by O-1
 * (UC-001 step 11) and removed with its Fetch when the Fetch leaves the kept history.
 */
@Entity(
    tableName = "fetch_result",
    primaryKeys = ["fetch_id", "bank_account_id"],
    indices = [Index(value = ["bank_account_id"])],
    foreignKeys = [
        ForeignKey(entity = FetchEntity::class, parentColumns = ["id"], childColumns = ["fetch_id"]),
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"]),
    ],
)
data class FetchResultEntity(
    @ColumnInfo(name = "fetch_id") val fetchId: Long,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "transaction_count") val transactionCount: Int,
)

/**
 * `ACCOUNT_BALANCE` (entity model): the most recent balance of a Bank Account as its Data Provider reported it, `amount`
 * in thousandths of the currency unit (ADR-002 §2). A Bank Account has at most one (`BANK_ACCOUNT ||--o| ACCOUNT_BALANCE`),
 * which O-1 replaces (UC-001 step 8).
 */
@Entity(
    tableName = "account_balance",
    indices = [Index(value = ["bank_account_id"], unique = true)],
    foreignKeys = [ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"])],
)
data class AccountBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "reported_at") val reportedAt: Instant,
)

/**
 * `BUDGET_APP_TRANSACTION` (entity model): Slussen's stored copy of one transaction of a Budget Account as last read from
 * the Budget App, with every attribute, the Transfer link, merge target, Cleared and approved included (ADR-002 §2, S-12);
 * `amount` in thousandths of the currency unit; `budget_app_reference` unique. Written only by O-4 (ADR-002 §3).
 */
@Entity(
    tableName = "budget_app_transaction",
    indices = [Index(value = ["budget_app_reference"], unique = true), Index(value = ["budget_account_id"])],
    foreignKeys = [ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["budget_account_id"])],
)
data class BudgetAppTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "budget_account_id") val budgetAccountId: Long,
    @ColumnInfo(name = "budget_app_reference") val budgetAppReference: String,
    @ColumnInfo(name = "transaction_date") val transactionDate: LocalDate,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "payee") val payee: String?,
    @ColumnInfo(name = "memo") val memo: String?,
    @ColumnInfo(name = "import_identifier") val importIdentifier: String?,
    @ColumnInfo(name = "entered_by") val enteredBy: String,
    @ColumnInfo(name = "transfer_link_reference") val transferLinkReference: String?,
    @ColumnInfo(name = "merged_into_reference") val mergedIntoReference: String?,
    @ColumnInfo(name = "cleared") val cleared: Boolean,
    @ColumnInfo(name = "approved") val approved: Boolean,
    @ColumnInfo(name = "state") val state: String,
)

/**
 * `TRANSFER` (entity model): a movement of money between two Budget Accounts of one Budget, with its Transfer Legs in
 * `bank_transaction.transfer_id`; `amount` in thousandths of the currency unit. That both sides differ and lie in one
 * Budget is a rule of the core (ADR-002 §2).
 */
@Entity(
    tableName = "transfer",
    indices = [Index(value = ["outflow_budget_account_id"]), Index(value = ["inflow_budget_account_id"])],
    foreignKeys = [
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["outflow_budget_account_id"]),
        ForeignKey(entity = BudgetAccountEntity::class, parentColumns = ["id"], childColumns = ["inflow_budget_account_id"]),
    ],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "outflow_budget_account_id") val outflowBudgetAccountId: Long,
    @ColumnInfo(name = "inflow_budget_account_id") val inflowBudgetAccountId: Long,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "transfer_type") val transferType: String,
    @ColumnInfo(name = "guessed") val guessed: Boolean,
    @ColumnInfo(name = "decision") val decision: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Instant?,
)

/**
 * `BANK_TRANSACTION` (entity model): one booked, non-zero transaction of a Bank Account, `amount` in thousandths of the
 * currency unit, `booked_date` in days (ADR-002 §2). Within one Bank Account no two share an `import_identifier` or a
 * `stable_reference` (unique indices; SQLite lets several empty `stable_reference` values stand). `status` is empty
 * until a Comparison has read its Budget (UC-003 A1, S-T-08); `transfer_id` is empty for an ordinary transaction.
 */
@Entity(
    tableName = "bank_transaction",
    indices = [
        Index(value = ["bank_account_id", "import_identifier"], unique = true),
        Index(value = ["bank_account_id", "stable_reference"], unique = true),
        Index(value = ["transfer_id"]),
    ],
    foreignKeys = [
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"]),
        ForeignKey(entity = TransferEntity::class, parentColumns = ["id"], childColumns = ["transfer_id"]),
    ],
)
data class BankTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long,
    @ColumnInfo(name = "stable_reference") val stableReference: String?,
    @ColumnInfo(name = "reference_kind") val referenceKind: String,
    @ColumnInfo(name = "occurrence_number") val occurrenceNumber: Int,
    @ColumnInfo(name = "import_identifier") val importIdentifier: String,
    @ColumnInfo(name = "booked_date") val bookedDate: LocalDate,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "payee") val payee: String?,
    @ColumnInfo(name = "memo") val memo: String?,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Instant,
    @ColumnInfo(name = "status") val status: String?,
    @ColumnInfo(name = "transfer_id") val transferId: Long?,
)

/**
 * `UNREADABLE_TRANSACTION` (entity model): a delivered transaction Slussen cannot interpret, with whatever fields could
 * be read and the reason; never removed by a later Fetch or by housekeeping (ADR-002 §10).
 */
@Entity(
    tableName = "unreadable_transaction",
    indices = [Index(value = ["bank_account_id"])],
    foreignKeys = [ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["bank_account_id"])],
)
data class UnreadableTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_account_id") val bankAccountId: Long,
    @ColumnInfo(name = "booked_date") val bookedDate: LocalDate?,
    @ColumnInfo(name = "amount") val amount: Long?,
    @ColumnInfo(name = "currency") val currency: String?,
    @ColumnInfo(name = "payee") val payee: String?,
    @ColumnInfo(name = "memo") val memo: String?,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Instant,
    @ColumnInfo(name = "status") val status: String,
)

/**
 * `TRANSFER_DESCRIPTION` (entity model): one entry of the Card Payment Descriptions or Bank Transfer Descriptions. That
 * no two entries of one `kind` are equal without regard to case and accents is a rule of the core (UC-010 BR-008),
 * which an index of exact texts cannot state (ADR-002 §2).
 */
@Entity(tableName = "transfer_description")
data class TransferDescriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "text") val text: String,
)

/** `BACK_OFF` (entity model); `bank_id` empty when the whole Data Provider waits. */
@Entity(
    tableName = "back_off",
    indices = [Index(value = ["data_provider_id"]), Index(value = ["bank_id"])],
    foreignKeys = [
        ForeignKey(entity = DataProviderEntity::class, parentColumns = ["id"], childColumns = ["data_provider_id"]),
        ForeignKey(entity = BankEntity::class, parentColumns = ["id"], childColumns = ["bank_id"]),
    ],
)
data class BackOffEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "bank_id") val bankId: Long?,
    @ColumnInfo(name = "cause") val cause: String,
    @ColumnInfo(name = "starts_at") val startsAt: Instant,
    @ColumnInfo(name = "ends_at") val endsAt: Instant,
)

/**
 * `FIRST_UPLOAD_MATCHING` (entity model), stored in the table `recovery_check` of committed schema version 1 with the
 * attribute names as columns: the one exception to the naming rule of ADR-002 §2 (S-41); no schema version for the name.
 */
@Entity(tableName = "recovery_check")
data class FirstUploadMatchingEntity(
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
