package slussen.persistence.device

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import slussen.persistence.StorageConverters
import java.time.Instant

/**
 * `device.db` (ADR-002 §1): what stays on this installation and is never backed up or transferred. Phase 7 holds
 * `service_credential` (ADR-002 §2a) and `offered_bank_account`; the tables of Bank Consents, Bank Sessions and Consent
 * Requests come with the slice that builds UC-007, within schema version 1 until the first release.
 */
@Database(entities = [ServiceCredentialEntity::class, OfferedBankAccountEntity::class], version = 1, exportSchema = true)
@TypeConverters(StorageConverters::class)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun serviceCredentials(): ServiceCredentialDao

    abstract fun offeredBankAccounts(): OfferedBankAccountDao
}

/**
 * `OFFERED_BANK_ACCOUNT` (entity model): of the number only `last_four_digits` and the fingerprint (UC-009 BR-009);
 * `data_provider_id`, `bank_id` and `proposed_bank_account_id` are plain columns across databases (ADR-002 §2).
 */
@Entity(
    tableName = "offered_bank_account",
    indices = [Index(value = ["data_provider_id", "provider_reference"], unique = true)],
)
data class OfferedBankAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "bank_id") val bankId: Long?,
    @ColumnInfo(name = "provider_reference") val providerReference: String,
    @ColumnInfo(name = "bank_account_fingerprint") val bankAccountFingerprint: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "bank_account_kind") val bankAccountKind: String?,
    @ColumnInfo(name = "currency") val currency: String?,
    @ColumnInfo(name = "balance") val balance: Long?,
    @ColumnInfo(name = "last_four_digits") val lastFourDigits: String?,
    @ColumnInfo(name = "proposed_bank_account_id") val proposedBankAccountId: Long?,
    @ColumnInfo(name = "offered_at") val offeredAt: Instant,
)

@Dao
abstract class OfferedBankAccountDao {
    @Query("SELECT * FROM offered_bank_account ORDER BY id")
    abstract suspend fun offered(): List<OfferedBankAccountEntity>

    @Query("DELETE FROM offered_bank_account WHERE data_provider_id = :dataProviderId")
    abstract suspend fun deleteOf(dataProviderId: Long)

    @Query("DELETE FROM offered_bank_account WHERE data_provider_id = :dataProviderId AND bank_id = :bankId")
    abstract suspend fun deleteOf(dataProviderId: Long, bankId: Long)

    @Insert
    abstract suspend fun insert(offered: List<OfferedBankAccountEntity>)

    /** O-2 (ADR-002 §3): the latest delivery of a Data Provider, or of one Bank through it, in place of the earlier one. */
    @Transaction
    open suspend fun replace(dataProviderId: Long, bankId: Long?, delivered: List<OfferedBankAccountEntity>) {
        if (bankId == null) deleteOf(dataProviderId) else deleteOf(dataProviderId, bankId)
        insert(delivered)
    }
}

/**
 * `SERVICE_CREDENTIAL` (entity model): at most one row for the Budget App and one per Data Provider; `data_provider_id`
 * is a plain column across databases (ADR-002 §2). `secret` holds Base64 of IV, ciphertext and tag (ADR-002 §2a).
 */
@Entity(tableName = "service_credential", indices = [Index(value = ["data_provider_id"], unique = true)])
data class ServiceCredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long?,
    @ColumnInfo(name = "secret") val secret: String,
    @ColumnInfo(name = "accepted_at") val acceptedAt: Instant,
) {
    override fun toString(): String = "ServiceCredentialEntity(id=$id, dataProviderId=$dataProviderId)"
}

@Dao
abstract class ServiceCredentialDao {
    /** The credential of one Data Provider, or of the Budget App when [dataProviderId] is null. */
    @Query("SELECT * FROM service_credential WHERE data_provider_id IS :dataProviderId LIMIT 1")
    abstract suspend fun find(dataProviderId: Long?): ServiceCredentialEntity?

    @Query("DELETE FROM service_credential WHERE data_provider_id IS :dataProviderId")
    abstract suspend fun delete(dataProviderId: Long?)

    @Insert
    protected abstract suspend fun insert(entity: ServiceCredentialEntity)

    /** The new row in place of the earlier one of the same service, in one transaction (ADR-002 §3, O-16). */
    @Transaction
    open suspend fun replace(entity: ServiceCredentialEntity) {
        delete(entity.dataProviderId)
        insert(entity)
    }
}
