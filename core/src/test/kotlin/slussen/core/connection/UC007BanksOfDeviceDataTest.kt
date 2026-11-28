package slussen.core.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.core.port.StoredBank
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredOfferedBankAccount
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.InMemoryDeviceBankReferences
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant

/**
 * The check of ADR-002 §2 at every start: `device.db` names Banks of `slussen.db` by their key, across the two
 * databases, so after `slussen.db` went back (its last good copy, a restored backup, or empty) a Bank Consent or an
 * Offered Bank Account could name a Bank that is gone, or one created later under the same key. [BanksOfDeviceData]
 * removes every row of `device.db` that names a Bank `slussen.db` does not hold (E1 review item 1).
 */
@DisplayName("UC-007: Grant Bank Consent, the Banks that device.db names")
class UC007BanksOfDeviceDataTest {
    private val slice = TestSlice()
    private val now: Instant get() = slice.clock.instant()
    private val device = InMemoryDeviceBankReferences(slice.consentStore, slice.offeredStore)
    private val check = BanksOfDeviceData(slice.connectionStore, device)

    private fun offered(id: Long, bankId: Long?) = StoredOfferedBankAccount(
        id, slice.openBankingId, bankId, "ref-$id", null, "Offered $id", null, null, null, null, null, now,
    )

    private suspend fun grant(bankId: Long, digit: Char) {
        val request = slice.consentStore.startRequest(NewConsentRequest("Bank $bankId (FI)", slice.openBankingId, now, now.plusSeconds(900), digit.toString().repeat(64), null))
        slice.consentStore.consumeAnswer(digit.toString().repeat(64), now)
        slice.consentStore.replaceConsent(request.id, GrantedBankConsent(bankId, now, now.plus(Duration.ofDays(180)), ConsentSession("invented-$bankId"), now))
    }

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-009"])
    @DisplayName("BR-009: at a start after slussen.db went back, the Bank Consent with its Bank Session and the Offered Bank Accounts of a Bank slussen.db no longer holds are removed; those of a Bank it holds, and those that name no Bank, stay")
    fun rowsOfABankThatIsGoneAreRemoved() = runTest {
        val kept = slice.connectionStore.bankNamed("Aurora Bank").id
        val gone = slice.connectionStore.bankNamed("Vesta Sparbank").id
        grant(kept, 'a')
        grant(gone, 'b')
        slice.offeredStore.stored += listOf(offered(1, kept), offered(2, gone), offered(3, null))
        // slussen.db started from a copy made before Vesta Sparbank was created.
        slice.connectionStore.bankList.removeAll { it.id == gone }

        check.run()

        assertEquals(listOf(kept), slice.consentStore.bankConsents().map { it.bankId })
        assertEquals(slice.consentStore.bankConsents().map { it.id }, slice.consentStore.bankSessions().map { it.bankConsentId })
        assertEquals(listOf(1L, 3L), slice.offeredStore.offered().map { it.id })
    }

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-009"])
    @DisplayName("BR-009: a Bank created after the start under the key of a Bank that is gone is covered by no Bank Consent and offers no Bank Account")
    fun aNewBankUnderTheSameKeyInheritsNothing() = runTest {
        val gone = slice.connectionStore.bankNamed("Vesta Sparbank").id
        grant(gone, 'b')
        slice.offeredStore.stored += offered(2, gone)
        slice.connectionStore.bankList.removeAll { it.id == gone }

        check.run()
        slice.connectionStore.bankList += StoredBank(gone, "Nordlys Bank", "NO")

        assertEquals(emptyList<StoredBankConsent>(), slice.consentStore.bankConsents())
        assertEquals(emptyList<StoredOfferedBankAccount>(), slice.offeredStore.offered())
        assertEquals(emptyList<Any>(), slice.importStatusStore.bankConsents())
    }

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-009"])
    @DisplayName("BR-009: at an ordinary start, where slussen.db holds every Bank that device.db names, nothing is removed")
    fun nothingIsRemovedAtAnOrdinaryStart() = runTest {
        val aurora = slice.connectionStore.bankNamed("Aurora Bank").id
        val vesta = slice.connectionStore.bankNamed("Vesta Sparbank").id
        grant(aurora, 'a')
        grant(vesta, 'b')
        slice.offeredStore.stored += listOf(offered(1, aurora), offered(2, vesta), offered(3, null))
        val consents = slice.consentStore.bankConsents()
        val sessions = slice.consentStore.bankSessions().map { it.id }
        val offered = slice.offeredStore.offered()

        check.run()

        assertEquals(consents, slice.consentStore.bankConsents())
        assertEquals(sessions, slice.consentStore.bankSessions().map { it.id })
        assertEquals(offered, slice.offeredStore.offered())
    }
}
