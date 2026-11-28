package slussen.app.ui.bankaccount

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.overview.OverviewViewModel
import slussen.core.consent.CompletionOutcome
import slussen.core.fetch.ScheduledFetchOutcome
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StoredProviderRequest
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.EveryStatusScenario
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ScriptedBankConsent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * What the Overview and the Bank Account screen show while a Bank Account's Unattended Access limit is reached (UC-001
 * A13 step 2, BR-012; UC-008 BR-006), after a Scheduled Fetch on the fakes of `:testing`: the Blocked Reason "access limit
 * reached" with the time the limit frees up, which read waits (its transactions, its Account Balance or both, as the
 * stored data shows), that only Scheduled Fetches wait and that a Fetch the Operator starts reads it; and that it goes
 * when access is free again. Every name is invented (C-005).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-001: Fetch Transactions, the Unattended Access limit on the screens")
class UC001UnattendedAccessViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)
    private val now: Instant get() = slice.clock.instant()

    /** Summer House served, Aurora's Bank Consent granted, and its Household Current added through Aurora Open Banking. */
    private fun household(): Long = runBlocking {
        scenario.serve()
        // Kestrel Feeds, in use with its credentials, delivers nothing in the Scheduled Fetch.
        slice.feeds.fetchAnswer = { FetchResponse(SourceOutcome.Completed, emptyList(), emptyList()) }
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        slice.bankConsent.startAnswer = { ConsentStart.Started("https://aurora.example/sca", null) }
        slice.bankConsent.completeAnswer = { _, _ ->
            ConsentCompletion.Completed(
                Instant.parse("2027-04-13T12:00:00Z"),
                listOf(DeliveredBankAccount(REFERENCE, AURORA, "Current", "current", EUR, null, BankAccountResult.NotRead(NotReadCause.NotSelected))),
                ConsentSession("session-aurora"),
            )
        }
        slice.bankConsents.start(slice.openBankingKey, AURORA, browserAvailable = true)
        val granted = slice.bankConsents.complete(ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=c&state=${slice.bankConsent.stateTexts().last()}"))
        assertTrue(granted is CompletionOutcome.Granted, "$granted")
        val bank = slice.connectionStore.bankNamed(AURORA).id
        val current = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Current" }.id
        (
            slice.connectionStore.addBankAccount(
                NewBankAccount(
                    generatedIdentifier = "00000000-0000-4000-8000-000000000777",
                    bank = BankChoice.Known(bank),
                    providerBankId = bank,
                    dataProviderId = slice.openBankingId,
                    fileFormatId = null,
                    balanceDataProviderId = null,
                    balanceProviderReference = null,
                    name = "Household Current",
                    currency = EUR,
                    type = BankAccountType.Checking,
                    providerReference = REFERENCE,
                    bankAccountFingerprint = null,
                    identifiedAt = now,
                    startDate = null,
                    budgetAccountId = current,
                ),
            ) as AddOutcome.Added
            ).bankAccountId
    }

    /** The Data Provider reads the balance, then the transactions, each through the gate, as the Enable Banking part does. */
    private fun reading() {
        slice.openBanking.readsEachBankAccount = { request, gate ->
            val results = (request.bankAccounts as BankAccountSelection.Selected).bankAccounts.map { read ->
                fun summary(kind: RequestKind) = RequestSummary(kind, AURORA, read.providerReference, unattended = true)
                val balance = if (read.read != ReadKind.Transactions && gate.admit(summary(RequestKind.Balance)) == Admission.Allowed) BALANCE else null
                val result = when {
                    read.read == ReadKind.Balance -> BankAccountResult.Read(emptyList(), balance)
                    gate.admit(summary(RequestKind.Transactions)) != Admission.Allowed -> BankAccountResult.NotRead(NotReadCause.RefusedByGate, balance)
                    else -> BankAccountResult.Read(listOf(BOOKED), balance)
                }
                DeliveredBankAccount(read.providerReference, AURORA, null, null, EUR, null, result)
            }
            FetchResponse(SourceOutcome.Completed, emptyList(), results)
        }
    }

    private fun sent(id: Long, kind: ProviderRequestKind, vararg hoursAgo: Long) = hoursAgo.forEach {
        slice.providerRequests.requests += StoredProviderRequest(slice.openBankingId, id, kind, true, now.minus(Duration.ofHours(it)))
    }

    private fun scheduledFetch() = runBlocking { assertTrue(slice.fetch.scheduledFetch() is ScheduledFetchOutcome.Ran) }

    private fun lastResult(id: Long) = runBlocking { slice.importStatusStore.fetchesOf(slice.openBankingId).first().results.single { it.bankAccountId == id }.outcome }

    private fun balanceOf(id: Long) = runBlocking { slice.importStatusStore.accountBalances().singleOrNull { it.bankAccountId == id } }

    private fun transactionsOf(id: Long) = runBlocking { slice.importStatusStore.bankTransactionsOf(id) }

    private fun limitLine(id: Long) = BankAccountViewModel(fixture.services, id).also { it.load() }.state.value.ui!!.blocked.singleOrNull { "access limit reached" in it.text }

    private fun overviewBlocked(): List<String> =
        OverviewViewModel(fixture.services).also { it.load() }.state.value!!.budgets.flatMap { it.blocked }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A13 step 2, UC-008 BR-006: when only its transactions wait, the Overview names the Bank Account as blocked, and the Bank Account screen says that Scheduled Fetches wait to read its transactions until the time access is free, while its balance was read")
    fun transactionsWait() {
        val id = household()
        reading()
        sent(id, ProviderRequestKind.Transactions, 10, 9, 8, 7)

        scheduledFetch()

        assertEquals(FetchResultOutcome.AccessLimitReached, lastResult(id))
        assertEquals(BALANCE, balanceOf(id)?.amount, "the balance was read and stored")
        assertTrue(transactionsOf(id).isEmpty(), "no transactions were read")
        assertTrue("Blocked: Household Current, access limit reached" in overviewBlocked(), "${overviewBlocked()}")
        val line = limitLine(id)!!
        // The oldest of the four counted reads, 10 hours ago (02:00 UTC, 04:00 in the device's zone), leaves the 24 hours just after 04:00 the next day.
        assertEquals("Blocked: Household Current, access limit reached · until 2026-10-16 04:00", line.text)
        assertEquals(
            "Scheduled Fetches wait until 2026-10-16 04:00 to read its transactions: PSD2 allows 4 reads of a Bank " +
                "Account's transactions in 24 hours without you present. A Fetch you start reads them; the Bank may still refuse it.",
            line.advice,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A13, UC-008 BR-006: when only its balance waits, its Fetch Result stays Fetched with its transactions stored, and the screen says that Scheduled Fetches wait to read its Account Balance")
    fun balanceWaits() {
        val id = household()
        reading()
        sent(id, ProviderRequestKind.Balance, 10, 9, 8, 7)

        scheduledFetch()

        assertEquals(FetchResultOutcome.Fetched, lastResult(id))
        assertEquals(1, transactionsOf(id).size, "the transactions were read and stored")
        assertNull(balanceOf(id), "no balance was asked for")
        val line = limitLine(id)!!
        assertEquals(
            "Scheduled Fetches wait until 2026-10-16 04:00 to read its Account Balance: PSD2 allows 4 reads of a Bank " +
                "Account's balance in 24 hours without you present. A Fetch you start reads it; the Bank may still refuse it.",
            line.advice,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A13 step 4, UC-008 BR-006: when both wait, nothing is read and the screen gives each read's own time; at the later one the Blocked Reason goes from both screens")
    fun bothWaitAndGo() {
        val id = household()
        reading()
        sent(id, ProviderRequestKind.Transactions, 10, 9, 8, 7)
        sent(id, ProviderRequestKind.Balance, 8, 7, 6, 5)

        scheduledFetch()

        assertEquals(FetchResultOutcome.AccessLimitReached, lastResult(id))
        assertNull(balanceOf(id))
        assertTrue(transactionsOf(id).isEmpty())
        assertEquals(0, slice.openBanking.fetches, "the Data Provider was not contacted")
        val line = limitLine(id)!!
        assertEquals("Blocked: Household Current, access limit reached · until 2026-10-16 06:00", line.text)
        assertEquals(
            "Scheduled Fetches wait until 2026-10-16 04:00 to read its transactions and until 2026-10-16 06:00 to read its " +
                "Account Balance: PSD2 allows 4 reads of each in 24 hours without you present. A Fetch you start reads both; " +
                "the Bank may still refuse it.",
            line.advice,
        )

        slice.clock.advance(Duration.ofHours(16).plusMillis(1))
        assertTrue(overviewBlocked().none { "access limit reached" in it }, "${overviewBlocked()}")
        assertNull(limitLine(id))
    }

    private companion object {
        const val AURORA = "Aurora Sparbank (FI)"
        const val REFERENCE = "aurora-household"
        val BALANCE = Amount(1_234_560)
        val BOOKED = ProviderTransaction.Interpretable(null, LocalDate.parse("2026-10-11"), Amount(-12_000), EUR, "Harbor Pharmacy", null, booked = true)
    }
}
