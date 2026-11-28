package slussen.persistence

import slussen.core.port.ConsentStore
import slussen.core.port.ConsumedAnswer
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.core.port.ReplacedConsent
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredBankSession
import slussen.core.port.StoredConsentRequest
import slussen.persistence.device.BankConsentEntity
import slussen.persistence.device.ConsentDao
import slussen.persistence.device.ConsentRequestEntity
import slussen.persistence.device.stored
import slussen.pluginapi.value.SensitiveApi
import java.time.Instant

/**
 * O-10 (ADR-002 §3) on `device.db` version 2: each write is one transaction of [ConsentDao]. The Bank Session is stored
 * as the revealed text of its `ConsentSession` and the plug-in's handle as its revealed text, only here (ADR-002 §2,
 * ADR-006 §2); of the `state` only its digest is ever given to this store.
 */
class RoomConsentStore(private val dao: ConsentDao) : ConsentStore {
    override suspend fun bankConsents(): List<StoredBankConsent> = dao.consents().map { it.stored() }

    override suspend fun bankSessions(): List<StoredBankSession> = dao.sessions().map { it.stored() }

    override suspend fun consentRequests(): List<StoredConsentRequest> = dao.requests().map { it.stored() }

    @OptIn(SensitiveApi::class)
    override suspend fun startRequest(request: NewConsentRequest): StoredConsentRequest =
        dao.start(
            ConsentRequestEntity(
                bankReference = request.bankReference,
                dataProviderId = request.dataProviderId,
                startedAt = request.startedAt,
                expiresAt = request.expiresAt,
                answered = false,
            ),
            request.stateDigest,
            request.providerHandle?.reveal(),
        ).stored()

    override suspend fun consumeAnswer(stateDigest: String, arrivedAt: Instant): ConsumedAnswer = dao.consume(stateDigest, arrivedAt)

    @OptIn(SensitiveApi::class)
    override suspend fun replaceConsent(requestId: Long, granted: GrantedBankConsent): ReplacedConsent =
        dao.replace(
            requestId,
            // The Data Provider is the Consent Request's; refused_at starts empty (ADR-002 §3 O-10).
            BankConsentEntity(bankId = granted.bankId, dataProviderId = 0, grantedAt = granted.grantedAt, expiresAt = granted.expiresAt, refusedAt = null),
            granted.session.reveal(),
            granted.openedAt,
        )

    override suspend fun abandonRequest(requestId: Long): Boolean = dao.abandon(requestId)

    override suspend fun markRefused(bankId: Long, dataProviderId: Long, refusedAt: Instant): Boolean = dao.markRefused(bankId, dataProviderId, refusedAt)

    override suspend fun revoke(bankId: Long, dataProviderId: Long, bankReference: String) = dao.revoke(bankId, dataProviderId, bankReference)
}
