package slussen.persistence.device

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import slussen.core.port.AnswerRejection
import slussen.core.port.ConsumedAnswer
import slussen.core.port.ReplaceRefusal
import slussen.core.port.ReplacedConsent
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredBankSession
import slussen.core.port.StoredConsentRequest
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * `BANK_CONSENT` (entity model), `device.db` version 2 (ADR-002 §7, S-B-45): one Bank Consent per Bank and Data
 * Provider (a unique index on both); `bank_id` and `data_provider_id` are plain columns across databases (ADR-002 §2).
 */
@Entity(tableName = "bank_consent", indices = [Index(value = ["bank_id", "data_provider_id"], unique = true)])
data class BankConsentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: Long,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "granted_at") val grantedAt: Instant,
    @ColumnInfo(name = "expires_at") val expiresAt: Instant,
    @ColumnInfo(name = "refused_at") val refusedAt: Instant?,
)

/**
 * `BANK_SESSION` (entity model): `bank_consent_id` is a real foreign key in `device.db` with a unique index, at most one
 * Bank Session per Bank Consent (ADR-002 §2, §7). `session_reference` is the revealed text of the `ConsentSession`
 * (ADR-002 §2), written and read only inside `:persistence`, never logged (ADR-006 §2): [toString] leaves it out.
 */
@Entity(
    tableName = "bank_session",
    foreignKeys = [ForeignKey(entity = BankConsentEntity::class, parentColumns = ["id"], childColumns = ["bank_consent_id"])],
    indices = [Index(value = ["bank_consent_id"], unique = true)],
)
data class BankSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_consent_id") val bankConsentId: Long,
    @ColumnInfo(name = "session_reference") val sessionReference: String,
    @ColumnInfo(name = "opened_at") val openedAt: Instant,
) {
    override fun toString(): String = "BankSessionEntity(id=$id, bankConsentId=$bankConsentId, openedAt=$openedAt)"
}

/**
 * `CONSENT_REQUEST` (entity model): it names its Bank by `bank_reference` and refers to no `BANK` (S-B-45);
 * `data_provider_id` is a plain column across databases (ADR-002 §2).
 */
@Entity(tableName = "consent_request")
data class ConsentRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_reference") val bankReference: String,
    @ColumnInfo(name = "data_provider_id") val dataProviderId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    @ColumnInfo(name = "expires_at") val expiresAt: Instant,
    @ColumnInfo(name = "answered") val answered: Boolean,
)

/**
 * `consent_request_correlation` (ADR-002 §2): keyed by its Consent Request, a real foreign key; only the SHA-256 digest
 * of the `state`, unique, never the `state` itself; the plug-in's opaque handle, which [toString] leaves out (ADR-006 §2).
 */
@Entity(
    tableName = "consent_request_correlation",
    foreignKeys = [ForeignKey(entity = ConsentRequestEntity::class, parentColumns = ["id"], childColumns = ["consent_request_id"])],
    indices = [Index(value = ["state_digest"], unique = true)],
)
data class ConsentRequestCorrelationEntity(
    @PrimaryKey @ColumnInfo(name = "consent_request_id") val consentRequestId: Long,
    @ColumnInfo(name = "state_digest") val stateDigest: String,
    @ColumnInfo(name = "provider_handle") val providerHandle: String?,
) {
    override fun toString(): String = "ConsentRequestCorrelationEntity(consentRequestId=$consentRequestId)"
}

fun BankConsentEntity.stored() = StoredBankConsent(id, bankId, dataProviderId, grantedAt, expiresAt, refusedAt)

fun BankSessionEntity.stored() = StoredBankSession(id, bankConsentId, ConsentSession(sessionReference), openedAt)

fun ConsentRequestEntity.stored() = StoredConsentRequest(id, bankReference, dataProviderId, startedAt, expiresAt, answered)

/**
 * The storage operations of O-10 (ADR-002 §3) on `device.db`. Each `@Transaction` method is one transaction of
 * `device.db`, all or nothing; its checks read inside it. Deletions go from the referring table to the referred one,
 * since the foreign keys act on nothing by themselves.
 */
@Dao
abstract class ConsentDao {
    @Query("SELECT * FROM bank_consent ORDER BY id")
    abstract suspend fun consents(): List<BankConsentEntity>

    @Query("SELECT * FROM bank_session ORDER BY id")
    abstract suspend fun sessions(): List<BankSessionEntity>

    @Query("SELECT * FROM consent_request ORDER BY id")
    abstract suspend fun requests(): List<ConsentRequestEntity>

    @Query("SELECT * FROM consent_request WHERE id = :id")
    abstract suspend fun request(id: Long): ConsentRequestEntity?

    @Query("SELECT * FROM consent_request WHERE bank_reference = :bankReference AND data_provider_id = :dataProviderId ORDER BY id")
    abstract suspend fun requestsOf(bankReference: String, dataProviderId: Long): List<ConsentRequestEntity>

    @Query("SELECT * FROM consent_request WHERE bank_reference = :bankReference ORDER BY id")
    abstract suspend fun requestsOfBank(bankReference: String): List<ConsentRequestEntity>

    @Query("SELECT * FROM consent_request_correlation WHERE state_digest = :stateDigest")
    abstract suspend fun correlationWithDigest(stateDigest: String): ConsentRequestCorrelationEntity?

    @Query("SELECT * FROM consent_request_correlation WHERE consent_request_id = :requestId")
    abstract suspend fun correlationOf(requestId: Long): ConsentRequestCorrelationEntity?

    @Query("SELECT * FROM bank_consent WHERE bank_id = :bankId AND data_provider_id = :dataProviderId")
    abstract suspend fun consentOf(bankId: Long, dataProviderId: Long): BankConsentEntity?

    @Insert
    abstract suspend fun insertRequest(request: ConsentRequestEntity): Long

    @Insert
    abstract suspend fun insertCorrelation(correlation: ConsentRequestCorrelationEntity)

    @Insert
    abstract suspend fun insertConsent(consent: BankConsentEntity): Long

    @Insert
    abstract suspend fun insertSession(session: BankSessionEntity): Long

    @Query("UPDATE consent_request SET answered = 1 WHERE id = :requestId")
    abstract suspend fun setAnswered(requestId: Long)

    @Query("UPDATE bank_consent SET refused_at = :refusedAt WHERE id = :consentId")
    abstract suspend fun setRefusedAt(consentId: Long, refusedAt: Instant)

    @Query("DELETE FROM consent_request_correlation WHERE consent_request_id = :requestId")
    abstract suspend fun deleteCorrelation(requestId: Long)

    @Query("DELETE FROM consent_request WHERE id = :requestId")
    abstract suspend fun deleteRequest(requestId: Long)

    @Query("DELETE FROM bank_session WHERE bank_consent_id = :consentId")
    abstract suspend fun deleteSessionOf(consentId: Long)

    @Query("DELETE FROM bank_consent WHERE id = :consentId")
    abstract suspend fun deleteConsent(consentId: Long)

    private suspend fun deleteRequestWithCorrelation(requestId: Long) {
        deleteCorrelation(requestId)
        deleteRequest(requestId)
    }

    private suspend fun newerExists(request: ConsentRequestEntity): Boolean =
        requestsOf(request.bankReference, request.dataProviderId).any { it.id > request.id }

    /** O-10 start (UC-007 BR-002; ADR-002 §10): [request] with its correlation, in place of every earlier one of its Bank and Data Provider. */
    @Transaction
    open suspend fun start(request: ConsentRequestEntity, stateDigest: String, providerHandle: String?): ConsentRequestEntity {
        require(correlationWithDigest(stateDigest) == null) { "A Consent Request with this state digest is already stored." }
        requestsOf(request.bankReference, request.dataProviderId).forEach { deleteRequestWithCorrelation(it.id) }
        val id = insertRequest(request.copy(id = 0, answered = false))
        insertCorrelation(ConsentRequestCorrelationEntity(id, stateDigest, providerHandle))
        return checkNotNull(request(id))
    }

    /** O-10 consume (UC-007 step 7, BR-002, A2): the checks and `answered` set, together. */
    @Transaction
    open suspend fun consume(stateDigest: String, arrivedAt: Instant): ConsumedAnswer {
        val correlation = correlationWithDigest(stateDigest) ?: return ConsumedAnswer.Rejected(AnswerRejection.NoSuchRequest)
        val request = request(correlation.consentRequestId) ?: return ConsumedAnswer.Rejected(AnswerRejection.NoSuchRequest)
        // UC-007 BR-002: A Consent Request is valid for at most the Consent Request lifetime, is accepted at most once, and is rejected when it is not the most recent Consent Request for that Bank.
        val rejection = when {
            newerExists(request) -> AnswerRejection.NotTheMostRecent
            request.answered -> AnswerRejection.AlreadyAnswered
            arrivedAt.isAfter(request.expiresAt) -> AnswerRejection.LifetimeEnded
            else -> null
        }
        if (rejection != null) return ConsumedAnswer.Rejected(rejection)
        setAnswered(request.id)
        return ConsumedAnswer.Accepted(request.copy(answered = true).stored(), correlation.providerHandle?.let(::Secret))
    }

    /** O-10 replace (UC-007 step 8, BR-003): the Bank Consent and its Bank Session in place of the earlier ones. */
    @Transaction
    open suspend fun replace(requestId: Long, consent: BankConsentEntity, sessionReference: String, openedAt: Instant): ReplacedConsent {
        val request = request(requestId) ?: return ReplacedConsent.Refused(ReplaceRefusal.NoSuchRequest)
        if (newerExists(request)) return ReplacedConsent.Refused(ReplaceRefusal.NewerRequestExists)
        if (!request.answered) return ReplacedConsent.Refused(ReplaceRefusal.NotAnswered)
        consentOf(consent.bankId, request.dataProviderId)?.let { earlier ->
            deleteSessionOf(earlier.id)
            deleteConsent(earlier.id)
        }
        val stored = consent.copy(id = 0, dataProviderId = request.dataProviderId, refusedAt = null)
        val consentId = insertConsent(stored)
        val session = BankSessionEntity(bankConsentId = consentId, sessionReference = sessionReference, openedAt = openedAt)
        val sessionId = insertSession(session)
        return ReplacedConsent.Stored(stored.copy(id = consentId).stored(), session.copy(id = sessionId).stored())
    }

    /** O-10 abandon (UC-007 A10): one unanswered Consent Request with its correlation. */
    @Transaction
    open suspend fun abandon(requestId: Long): Boolean {
        val request = request(requestId) ?: return false
        if (request.answered) return false
        deleteRequestWithCorrelation(request.id)
        return true
    }

    /** O-10 mark refused (UC-001 A9): `refused_at` set once, the earliest time kept. */
    @Transaction
    open suspend fun markRefused(bankId: Long, dataProviderId: Long, refusedAt: Instant): Boolean {
        val consent = consentOf(bankId, dataProviderId) ?: return false
        if (consent.refusedAt == null || refusedAt.isBefore(consent.refusedAt)) setRefusedAt(consent.id, refusedAt)
        return true
    }

    /** O-10 revoke (UC-007 A7, BR-007): the Bank Consent, its Bank Session and every Consent Request of the Bank. */
    @Transaction
    open suspend fun revoke(bankId: Long, dataProviderId: Long, bankReference: String) {
        consentOf(bankId, dataProviderId)?.let { consent ->
            deleteSessionOf(consent.id)
            deleteConsent(consent.id)
        }
        // UC-007 BR-007: Revoking deletes the Bank Consent, its Bank Session and every Consent Request of the Bank, whichever Data Provider it was started with.
        requestsOfBank(bankReference).forEach { deleteRequestWithCorrelation(it.id) }
    }
}

/**
 * The rows of `device.db` that name a Bank of `slussen.db` by its key (ADR-002 §2): `bank_consent.bank_id` with each
 * Bank Consent's Bank Session, and `offered_bank_account.bank_id`. [removeAllNamingBanksOtherThan] is one transaction.
 */
@Dao
abstract class DeviceBanksDao {
    @Query("SELECT id, bank_id FROM bank_consent ORDER BY id")
    abstract suspend fun consentBanks(): List<IdAndBank>

    @Query("SELECT id, bank_id FROM offered_bank_account WHERE bank_id IS NOT NULL ORDER BY id")
    abstract suspend fun offeredBanks(): List<IdAndBank>

    @Query("DELETE FROM bank_session WHERE bank_consent_id = :consentId")
    abstract suspend fun deleteSessionOf(consentId: Long)

    @Query("DELETE FROM bank_consent WHERE id = :consentId")
    abstract suspend fun deleteConsent(consentId: Long)

    @Query("DELETE FROM offered_bank_account WHERE id = :offeredId")
    abstract suspend fun deleteOffered(offeredId: Long)

    /** Every Bank Consent with its Bank Session and every Offered Bank Account of a Bank not among [heldBankIds], all or nothing. */
    @Transaction
    open suspend fun removeAllNamingBanksOtherThan(heldBankIds: Set<Long>): Int {
        val consents = consentBanks().filter { it.bankId !in heldBankIds }
        consents.forEach {
            deleteSessionOf(it.id)
            deleteConsent(it.id)
        }
        val offered = offeredBanks().filter { it.bankId !in heldBankIds }
        offered.forEach { deleteOffered(it.id) }
        return consents.size + offered.size
    }
}

/** A row's key with the Bank it names. */
data class IdAndBank(@ColumnInfo(name = "id") val id: Long, @ColumnInfo(name = "bank_id") val bankId: Long)
