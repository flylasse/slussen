package slussen.core.port

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

/** `OFFERED_BANK_ACCOUNT` (entity model) as stored in `device.db`; of the number only the last four digits and the fingerprint. */
data class StoredOfferedBankAccount(
    val id: Long,
    val dataProviderId: Long,
    val bankId: Long?,
    val providerReference: String,
    val bankAccountFingerprint: String?,
    val name: String?,
    val bankAccountKind: String?,
    val currency: CurrencyCode?,
    val balance: Amount?,
    val lastFourDigits: String?,
    val proposedBankAccountId: Long?,
    val offeredAt: Instant,
)

/** One Offered Bank Account of a delivery, before it is stored. */
data class NewOfferedBankAccount(
    val bankId: Long?,
    val providerReference: String,
    val bankAccountFingerprint: String?,
    val name: String?,
    val bankAccountKind: String?,
    val currency: CurrencyCode?,
    val balance: Amount?,
    val lastFourDigits: String?,
    val proposedBankAccountId: Long?,
    val offeredAt: Instant,
)

/**
 * The Offered Bank Accounts in `device.db` (ADR-002 §1). They are delivered by a Fetch (UC-001) or a granted Bank
 * Consent (UC-007), which store them with O-2; UC-013 and UC-009 read them without contacting the Data Provider.
 */
interface OfferedBankAccountStore {
    suspend fun offered(): List<StoredOfferedBankAccount>

    /**
     * O-2: replaces the Offered Bank Accounts of [dataProviderId], or of one Bank through it when [bankId] is given, with
     * [delivered], in one transaction (UC-009 BR-009).
     */
    suspend fun replaceOffered(dataProviderId: Long, bankId: Long?, delivered: List<NewOfferedBankAccount>)
}
