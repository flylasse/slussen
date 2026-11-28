package slussen.core.fetch

import slussen.core.consent.bankWaitUntil
import slussen.core.port.BankAccountDelivery
import slussen.core.port.ConsentStore
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchResultOutcome
import slussen.core.port.FetchStore
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.ProviderRequestKind
import slussen.core.port.ProviderRequestStore
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredOfferedBankAccount
import slussen.core.request.REQUEST_LIMIT_WINDOW
import slussen.core.request.UNATTENDED_ACCESS_LIMIT
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.ConsentSession
import java.time.Instant

/**
 * UC-007 A6, from stored data (E2 review item 4): [reference], a Bank Account's reference at the Data Provider
 * [dataProviderId], is not among the Offered Bank Accounts its Bank's Bank Consent [consent] delivered, which were kept
 * since it was granted. Only when at least one of them was kept: when none was (they could not be kept, or it covers
 * none), what it covers is not known, and no Bank Account is taken as unrecognised for that. The import status (UC-008
 * BR-006) and the Fetch (UC-001 A10) use this one rule.
 */
internal fun notCoveredBy(consent: StoredBankConsent, dataProviderId: Long, reference: String?, offered: List<StoredOfferedBankAccount>): Boolean {
    val kept = offered.filter { it.dataProviderId == dataProviderId && it.bankId == consent.bankId && !it.offeredAt.isBefore(consent.grantedAt) }
    if (kept.isEmpty()) return false
    return kept.none { it.providerReference == reference }
}

/** A Bank whose Back-off holds (UC-001 A1, A5, BR-004), by its name, with when it ends and the Bank Accounts it holds back. */
data class BankWaiting(val bankName: String, val until: Instant, val bankAccounts: List<String> = emptyList())

/**
 * UC-001 A22: a Bank whose requests cannot show that the Operator is present (BR-012), with how many of the
 * [limit] Unattended Accesses PSD2 allows (C-006) each of its Bank Accounts the Fetch would read has used of its
 * transactions and of its balance in the preceding 24 hours.
 */
data class UnattendedBank(val bankName: String, val bankAccounts: List<UnattendedUse>, val limit: Int = UNATTENDED_ACCESS_LIMIT)

data class UnattendedUse(val bankAccountName: String, val transactionsUsed: Int, val balanceUsed: Int)

/**
 * Step 3 and A4 for a Data Provider that needs a Bank Consent (UC-001; UC-007 BR-009; S-B-47): of the selected Bank
 * Accounts, those read are the ones whose Bank, the Bank the Data Provider names for them (`provider_bank_id`), has a
 * Bank Consent that is valid now, that is not in a Back-off of its own, and whose Bank Consent covers them; the others
 * are left out without contacting their Bank, each with its reason. [sessions] holds the Bank Session of each Bank read,
 * by the Bank's reference, which is its `BANK.name` (ADR-001 §4.1).
 */
internal class ConsentedSelection(
    val readable: List<ConsentedRead>,
    /** A4: no Bank Consent, or one that has expired or that the Bank refused (UC-001 A9). */
    val missingConsent: List<StoredBankAccount>,
    /** UC-007 A6 (E2 review item 4): its Bank's Bank Consent does not cover it. */
    val notRecognised: List<StoredBankAccount>,
    /** UC-001 A1, BR-004: its Bank asked Slussen to wait. */
    val waiting: List<BankWaiting>,
    val bankIds: Map<String, Long>,
) {
    fun sessions(toRead: List<BankAccountToRead>): Map<String, ConsentSession> {
        val references = toRead.map { it.providerReference }.toSet()
        return readable.filter { it.read.providerReference in references }.associate { it.bankName to it.session }
    }

    /** The Bank of a selected Bank Account read through its Bank Session, by its name. */
    fun bankOf(bankAccount: StoredBankAccount): String? = readable.firstOrNull { it.bankAccount.id == bankAccount.id }?.bankName

    /** The Fetch Results of the Bank Accounts left out (A4, A10) for the recorded Fetch [fetchId]; nothing of them changes. */
    fun leftOutResults(fetchId: Long): List<BankAccountDelivery> =
        missingConsent.map { BankAccountDelivery(fetchId, it.id, FetchResultOutcome.NotConsented, 0, balance = null) } +
            notRecognised.map { BankAccountDelivery(fetchId, it.id, FetchResultOutcome.NotRecognised, 0, balance = null) }

    /**
     * A22: the Banks of [toRead] whose requests cannot show that the Operator is present ([capability]), each with the
     * Unattended Accesses its Bank Accounts used in the preceding 24 hours (BR-012), counted from the Provider Requests.
     */
    suspend fun unattendedBanks(
        toRead: List<BankAccountToRead>,
        capability: BankConsentCapability,
        requests: ProviderRequestStore,
        now: Instant,
    ): List<UnattendedBank> {
        val references = toRead.map { it.providerReference }.toSet()
        val reads = readable.filter { it.read.providerReference in references && !capability.showsPresence(it.session) }
        if (reads.isEmpty()) return emptyList()
        val sent = requests.sentSince(now.minus(REQUEST_LIMIT_WINDOW)).filter { it.unattended }
        // UC-001 BR-012: one Unattended Access is one read of a Bank Account's transactions or of its balance; further pages are of kind Other.
        fun used(bankAccountId: Long, kind: ProviderRequestKind) = sent.count { it.bankAccountId == bankAccountId && it.kind == kind }
        return reads.groupBy { it.bankName }.map { (bank, ofBank) ->
            UnattendedBank(
                bank,
                ofBank.map {
                    UnattendedUse(it.bankAccount.name, used(it.bankAccount.id, ProviderRequestKind.Transactions), used(it.bankAccount.id, ProviderRequestKind.Balance))
                },
            )
        }
    }

    companion object {
        suspend fun of(
            dataProviderId: Long,
            selected: List<Pair<StoredBankAccount, BankAccountToRead>>,
            consents: ConsentStore?,
            connections: ConnectionStore,
            offered: OfferedBankAccountStore,
            backOffs: List<StoredBackOff>,
            now: Instant,
        ): ConsentedSelection {
            val banks = connections.banks().associate { it.id to it.name }
            val stored = consents?.bankConsents().orEmpty().filter { it.dataProviderId == dataProviderId }
            val sessions = consents?.bankSessions().orEmpty().associateBy { it.bankConsentId }
            val kept = offered.offered()
            val readable = mutableListOf<ConsentedRead>()
            val missing = mutableListOf<StoredBankAccount>()
            val notRecognised = mutableListOf<StoredBankAccount>()
            val waiting = mutableMapOf<Long, BankWaiting>()
            selected.forEach { (bankAccount, read) ->
                val bankId = bankAccount.providerBankId
                // UC-007 BR-009: the Bank the Data Provider names for the Bank Account decides which Bank Consent covers it, never the Bank the Operator chose.
                val consent = stored.firstOrNull { it.bankId == bankId }
                val session = consent?.let { sessions[it.id] }
                val bankName = bankId?.let { banks[it] }
                when {
                    // UC-001 BR-008: No Bank is contacted without a valid Bank Consent; one the Bank refused counts as expired from then on (A9).
                    consent == null || session == null || bankName == null || !consent.expiredFrom.isAfter(now) -> missing += bankAccount
                    // UC-001 BR-004: While a Bank asked Slussen to wait, it is not contacted, whoever starts the Fetch.
                    backOffs.bankWaitUntil(consent.bankId, now) != null -> {
                        val until = backOffs.bankWaitUntil(consent.bankId, now)!!
                        val earlier = waiting[consent.bankId]
                        waiting[consent.bankId] = BankWaiting(bankName, until, earlier?.bankAccounts.orEmpty() + bankAccount.name)
                    }
                    // UC-007 A6 (E2 review item 4): a Bank Account its Bank's Bank Consent does not cover is not read.
                    notCoveredBy(consent, dataProviderId, read.providerReference, kept) -> notRecognised += bankAccount
                    else -> readable += ConsentedRead(bankAccount, read, bankName, session.session)
                }
            }
            return ConsentedSelection(readable, missing, notRecognised, waiting.values.toList(), banks.entries.associate { it.value to it.key })
        }
    }
}

/** A selected Bank Account read through the Bank Session [session] of its Bank [bankName]. */
internal class ConsentedRead(val bankAccount: StoredBankAccount, val read: BankAccountToRead, val bankName: String, val session: ConsentSession)
