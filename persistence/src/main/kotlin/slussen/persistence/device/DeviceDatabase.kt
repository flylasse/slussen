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
 * `device.db` (ADR-002 §1): what stays on this installation and is never backed up or transferred. The module
 * skeleton holds `service_credential` (ADR-002 §2a); the tables of Bank Consents, Bank Sessions, Consent Requests and
 * Offered Bank Accounts come with the slices that use them, within schema version 1 until the first release.
 */
@Database(entities = [ServiceCredentialEntity::class], version = 1, exportSchema = true)
@TypeConverters(StorageConverters::class)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun serviceCredentials(): ServiceCredentialDao
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
