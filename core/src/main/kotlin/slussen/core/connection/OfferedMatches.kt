package slussen.core.connection

import slussen.core.port.NewOfferedBankAccount
import slussen.core.port.StoredBankAccount

// UC-009 BR-004: Slussen only proposes a likely Bank Account, for the Operator to confirm, and never when two Bank Accounts are possible or the Bank Account is still identified by its own reference; a Left-Out Bank Account is never proposed.
/**
 * UC-013 A6, UC-009 steps 2-4: the added Bank Account each of [kept], one delivery of a Data Provider, is proposed as a
 * likely match of, by its reference: an Offered Bank Account that is not left out ([leftOut], by reference) and whose
 * Bank Account Fingerprint (ADR-008 §8) equals that of exactly one of [added], the added Bank Accounts of the Data
 * Provider, when it is the only one of the delivery with that fingerprint and that Bank Account's own reference is not
 * delivered. Nothing is identified here: the Operator confirms or rejects the proposal in Manage Account Mappings. A
 * Fetch (UC-001) and a granted Bank Consent (UC-007 step 9, A5) propose by this one rule.
 */
internal fun proposedMatchesOf(added: List<StoredBankAccount>, kept: List<NewOfferedBankAccount>, leftOut: Set<String>): Map<String, Long> {
    val delivered = kept.map { it.providerReference }.toSet()
    return kept.mapNotNull { offer ->
        val fingerprint = offer.bankAccountFingerprint ?: return@mapNotNull null
        if (offer.providerReference in leftOut || kept.count { it.bankAccountFingerprint == fingerprint } != 1) return@mapNotNull null
        if (added.any { it.providerReference == offer.providerReference }) return@mapNotNull null
        val match = added.filter { it.bankAccountFingerprint == fingerprint }.singleOrNull() ?: return@mapNotNull null
        if (match.providerReference in delivered) return@mapNotNull null
        offer.providerReference to match.id
    }.toMap()
}
