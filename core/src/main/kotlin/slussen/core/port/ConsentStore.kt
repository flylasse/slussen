package slussen.core.port

import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.value.Secret
import java.time.Instant

/**
 * `CONSENT_REQUEST` (entity model) as stored in `device.db`: one attempt to grant or renew the Bank Consent of the Bank
 * named [bankReference] with the Data Provider [dataProviderId]. It refers to no `BANK` (ADR-002 §7, S-B-45).
 */
data class StoredConsentRequest(
    val id: Long,
    /** The Data Provider's reference of the Bank, its name and country (ADR-001 §4.1), at most 100 characters. */
    val bankReference: String,
    val dataProviderId: Long,
    val startedAt: Instant,
    /** The time after which an answer to it is rejected (UC-007 BR-002). */
    val expiresAt: Instant,
    /** True once an answer to it has been accepted (O-10 consume). */
    val answered: Boolean,
)

/**
 * A Consent Request to start (O-10 start) with its correlation (`consent_request_correlation`, ADR-002 §2): only the
 * SHA-256 digest of the `state` value, in hex, never the `state` itself (ADR-006 §2), and the plug-in's opaque handle.
 */
data class NewConsentRequest(
    val bankReference: String,
    val dataProviderId: Long,
    val startedAt: Instant,
    val expiresAt: Instant,
    val stateDigest: String,
    val providerHandle: Secret?,
)

/** `BANK_SESSION` (entity model) as stored in `device.db`; [session] is masked like a [Secret] (ADR-006 §2). */
data class StoredBankSession(
    val id: Long,
    val bankConsentId: Long,
    val session: ConsentSession,
    val openedAt: Instant,
)

/** A granted Bank Consent with its Bank Session, to store in place of the earlier ones of its Bank (O-10 replace). */
data class GrantedBankConsent(
    /** The `BANK` the core found or created for the Consent Request's `bank_reference` right before (ADR-002 §3 O-10). */
    val bankId: Long,
    val grantedAt: Instant,
    /** The expiry the Bank or Data Provider reported (UC-007 BR-004). */
    val expiresAt: Instant,
    val session: ConsentSession,
    val openedAt: Instant,
)

/** What O-10 consume found for an answer, by the digest of the `state` it carries (UC-007 step 7, BR-002, A2). */
sealed interface ConsumedAnswer {
    /** The answer is accepted: its Consent Request is now answered, and [providerHandle] is the handle stored with it. */
    data class Accepted(val request: StoredConsentRequest, val providerHandle: Secret?) : ConsumedAnswer

    /** The answer is rejected and nothing is changed. */
    data class Rejected(val reason: AnswerRejection) : ConsumedAnswer
}

/** Why an answer to a Consent Request is rejected (UC-007 BR-002, A2). */
enum class AnswerRejection {
    /** No stored Consent Request has the digest of the answer's `state`: it was abandoned, revoked, replaced, or never started here. */
    NoSuchRequest,

    /** A newer Consent Request of the same Bank and Data Provider was started. */
    NotTheMostRecent,

    /** The answer arrived after the Consent Request lifetime ended. */
    LifetimeEnded,

    /** An answer to it was accepted before. */
    AlreadyAnswered,
}

/** What O-10 replace did (ADR-001 §4.1; UC-007 BR-002, BR-003). */
sealed interface ReplacedConsent {
    /** The Bank Consent and its Bank Session are stored in place of the earlier ones of the Bank and Data Provider. */
    data class Stored(val consent: StoredBankConsent, val session: StoredBankSession) : ReplacedConsent

    /** Nothing is stored and the earlier Bank Consent is unchanged. */
    data class Refused(val reason: ReplaceRefusal) : ReplacedConsent
}

enum class ReplaceRefusal {
    /** The Consent Request is no longer stored (abandoned, revoked or replaced by a newer one). */
    NoSuchRequest,

    /** A newer Consent Request of the same Bank and Data Provider exists. */
    NewerRequestExists,

    /** No answer to the Consent Request was accepted (O-10 consume). */
    NotAnswered,
}

/**
 * O-10 of ADR-002 §3, the Bank Consents, Bank Sessions and Consent Requests in `device.db` (UC-007). Each write is one
 * transaction of `device.db`, all or nothing. The Bank of a Consent Request is its `bank_reference`; the Bank of a Bank
 * Consent is a `BANK` row of `slussen.db`, a plain column across the two databases that the core checks (ADR-002 §2).
 */
interface ConsentStore {
    suspend fun bankConsents(): List<StoredBankConsent>

    suspend fun bankSessions(): List<StoredBankSession>

    suspend fun consentRequests(): List<StoredConsentRequest>

    /**
     * Start: stores [request] with its correlation, in place of every earlier Consent Request of the same Bank and Data
     * Provider, which become unanswerable (ADR-002 §10, UC-007 BR-002). A `state_digest` already stored is refused
     * with an [IllegalArgumentException] and nothing is stored.
     */
    suspend fun startRequest(request: NewConsentRequest): StoredConsentRequest

    /**
     * Consume: finds the Consent Request whose correlation holds [stateDigest] and accepts the answer only when it is
     * the most recent of its Bank and Data Provider, [arrivedAt] does not lie after its `expires_at`, and it is not
     * answered; then sets `answered`, together with the checks.
     */
    suspend fun consumeAnswer(stateDigest: String, arrivedAt: Instant): ConsumedAnswer

    /**
     * Replace: stores [granted] for the Bank and Data Provider of the answered Consent Request [requestId], with
     * `refused_at` empty, in place of the earlier Bank Consent and its Bank Session; refused when the request is gone,
     * not answered, or a newer Consent Request of its Bank and Data Provider exists.
     */
    suspend fun replaceConsent(requestId: Long, granted: GrantedBankConsent): ReplacedConsent

    /** Abandon: deletes the unanswered Consent Request [requestId] with its correlation; false, changing nothing, when it is answered or gone (UC-007 A10). */
    suspend fun abandonRequest(requestId: Long): Boolean

    /**
     * Mark refused: sets `refused_at` of the Bank Consent of [bankId] and [dataProviderId] to [refusedAt], once,
     * keeping the earliest time (UC-001 A9); false when the Bank has no Bank Consent with that Data Provider.
     */
    suspend fun markRefused(bankId: Long, dataProviderId: Long, refusedAt: Instant): Boolean

    /**
     * Revoke: deletes the Bank Consent of [bankId] and [dataProviderId] with its Bank Session, and every Consent
     * Request of the Bank named [bankReference] (its `BANK.name`), whichever Data Provider it was started with, with
     * its correlation, also when the Data Provider did not confirm the end (UC-007 BR-007, A7; ADR-002 §3 O-10).
     */
    suspend fun revoke(bankId: Long, dataProviderId: Long, bankReference: String)
}

/**
 * The rows of `device.db` that name a Bank of `slussen.db` by its key, a plain column across the two databases that the
 * core checks (ADR-002 §2): `bank_consent.bank_id`, with each Bank Consent's Bank Session, and
 * `offered_bank_account.bank_id`. No other table of `device.db` names a Bank by its key (a Consent Request names it by
 * its reference).
 */
interface DeviceBankReferences {
    /**
     * Removes, in one transaction of `device.db`, every Bank Consent with its Bank Session and every Offered Bank
     * Account whose Bank is not among [heldBankIds]; an Offered Bank Account that names no Bank stays. Returns how many
     * Bank Consents and Offered Bank Accounts it removed.
     */
    suspend fun removeAllNamingBanksOtherThan(heldBankIds: Set<Long>): Int
}
