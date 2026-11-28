package slussen.testing

import slussen.core.port.DeviceBankReferences

/** The fake of [DeviceBankReferences] over the fakes of `device.db`'s Bank Consents and Offered Bank Accounts. */
class InMemoryDeviceBankReferences(
    private val consents: InMemoryConsentStore,
    private val offered: InMemoryOfferedBankAccountStore,
) : DeviceBankReferences {
    override suspend fun removeAllNamingBanksOtherThan(heldBankIds: Set<Long>): Int {
        val consentsRemoved = consents.removeConsentsOfBanksOtherThan(heldBankIds)
        val before = offered.stored.size
        offered.stored.removeAll { it.bankId != null && it.bankId !in heldBankIds }
        return consentsRemoved + before - offered.stored.size
    }
}
