package slussen.core.consent

import slussen.core.port.ConnectionStore
import slussen.core.port.ConsentStore
import slussen.core.port.OfferedBankAccountStore

/**
 * The `BANK` rows that an attempt to grant a Bank Consent may leave behind (ADR-002 §3 O-10, W6-0 item 8, E1 review). The
 * Bank's row is created in `slussen.db` right before the Bank Consent is stored in `device.db`, and one transaction
 * cannot span the two; when the Bank Consent is then not stored (refused, a failure, or the end of the process in
 * between), a Bank's row that nothing refers to is removed, so that it is never offered later as a Bank Slussen knows.
 * "Nothing" is no Bank Consent and no Offered Bank Account (`device.db`, checked here), and no Bank Account and no
 * Back-off (`slussen.db`, checked by [ConnectionStore.removeBankIfUnreferenced] in its own transaction).
 */
class LeftBehindBanks(
    private val connections: ConnectionStore,
    private val consents: ConsentStore,
    private val offered: OfferedBankAccountStore,
) {
    /** Removes the Bank [bankId] when nothing refers to it; returns whether it did. */
    suspend fun removeIfUnused(bankId: Long): Boolean {
        if (consents.bankConsents().any { it.bankId == bankId }) return false
        if (offered.offered().any { it.bankId == bankId }) return false
        return connections.removeBankIfUnreferenced(bankId)
    }

    /**
     * At every start, after [slussen.core.connection.BanksOfDeviceData] and before any operation is admitted: removes each
     * Bank named by an answered Consent Request that nothing refers to, which an attempt cut off by the end of the
     * process after the Data Provider completed it and before its Bank Consent was stored left behind. Returns how many it
     * removed.
     */
    suspend fun run(): Int {
        val answered = consents.consentRequests().filter { it.answered }.map { it.bankReference }.toSet()
        if (answered.isEmpty()) return 0
        return connections.banks().filter { it.name in answered }.count { removeIfUnused(it.id) }
    }
}
