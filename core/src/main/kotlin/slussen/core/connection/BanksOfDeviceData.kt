package slussen.core.connection

import slussen.core.port.ConnectionStore
import slussen.core.port.DeviceBankReferences

/**
 * The check of ADR-002 §2 of the references from `device.db` into `slussen.db`, made at every start of every process,
 * after `slussen.db` is opened by whichever path of the start sequence and before anything can create a Bank (E1 review
 * item 1). `device.db` is never backed up and has no last good copy, so it can outlive the `slussen.db` it was written
 * with: after a last good copy, a restored backup or an empty `slussen.db`, a Bank it names may be gone, and its key
 * may be given to the next new Bank.
 */
class BanksOfDeviceData(private val connections: ConnectionStore, private val device: DeviceBankReferences) {
    /** Removes what `device.db` holds of every Bank that `slussen.db` does not hold; returns how many rows it removed. */
    suspend fun run(): Int {
        val held = connections.banks().map { it.id }.toSet()
        // UC-007 BR-009: A Bank Consent covers the Bank Accounts of the Bank where it was granted; one of a Bank slussen.db no longer holds covers none, and never a Bank created later under the same key.
        return device.removeAllNamingBanksOtherThan(held)
    }
}
