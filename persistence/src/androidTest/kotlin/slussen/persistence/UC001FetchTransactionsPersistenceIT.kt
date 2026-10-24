package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.AddOutcome
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountDelivery
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.DataProviderRegistration
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewAccountBalance
import slussen.core.port.NewBankAccount
import slussen.core.port.NewBackOff
import slussen.core.port.NewFetch
import slussen.core.port.StartedBy
import slussen.core.port.StoredAccountBalance
import slussen.core.port.StoredFetchResult
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Fetches, Fetch Results, Account Balances and Back-offs of Fetch Transactions (UC-001) in `slussen.db` on a device
 * or emulator: what the import status reads back, O-1 all or nothing, the housekeeping of the Fetch history, and that
 * they survive a restart (ADR-002 §1, §3).
 */
@DisplayName("UC-001: Fetch Transactions, on the device")
class UC001FetchTransactionsPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private suspend fun registeredFeeds(storage: SlussenStorage): Long {
        val profile = ExampleData.feedsProfile
        val rows = storage.registry.reconcile(
            listOf(
                DataProviderRegistration(
                    profile.name, profile.sourceKey, profile.givesStableReference, profile.needsBankConsent,
                    profile.defaultFetchCooldownMinutes, profile.requestLimit,
                ),
            ),
            emptyList(),
        )
        return rows.dataProviders.single().id
    }

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: a recorded Fetch is read back with its time, who started it and its outcome, most recent first, also after a restart")
    fun recordedFetchesSurviveARestart() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        storage.fetches.recordFetch(NewFetch(feeds, now, StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.recordFetch(NewFetch(feeds, now.plusSeconds(600), StartedBy.Operator, FetchOutcome.ServedFromFetchCooldown), keep = 60)
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(
            listOf(FetchOutcome.ServedFromFetchCooldown, FetchOutcome.Fetched),
            restarted.fetches.fetchesOf(feeds).map { it.outcome },
        )
        assertEquals(listOf(now.plusSeconds(600), now), restarted.importStatus.recentFetches().map { it.startedAt })
        assertEquals(StartedBy.Operator, restarted.importStatus.recentFetches().first().startedBy)
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: only the most recent Fetches are kept, as many as the Operator Setting says, in the same transaction")
    fun onlyTheMostRecentFetchesAreKept() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)

        repeat(4) { storage.fetches.recordFetch(NewFetch(feeds, now.plusSeconds(it * 60L), StartedBy.Operator, FetchOutcome.Fetched), keep = 2) }

        assertEquals(listOf(now.plusSeconds(180), now.plusSeconds(120)), storage.fetches.fetchesOf(feeds).map { it.startedAt })
        storage.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5, BR-004: a Back-off is kept with its cause and end and survives a restart, so a restart does not end it")
    fun backOffSurvivesARestart() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        val endsAt = now.plus(Duration.ofHours(8))
        storage.fetches.startBackOff(NewBackOff(feeds, null, BackOffCause.RequestToWait, now, endsAt))
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        val backOff = restarted.fetches.backOffsOf(feeds).single()
        assertEquals(BackOffCause.RequestToWait, backOff.cause)
        assertEquals(endsAt, backOff.endsAt)
        assertEquals(null, backOff.bankId)
        assertEquals(listOf(backOff), restarted.importStatus.backOffs())
        restarted.close()
    }
    /** Kestrel Checking added for Kestrel Feeds and mapped to Household's Checking; returns its id. */
    private suspend fun addedChecking(storage: SlussenStorage, feeds: Long): Long {
        val usd = CurrencyCode("USD")
        storage.connections.serveBudgets(
            listOf(BudgetToServe("budget-household", "Household", usd, listOf(BudgetAccountToStore("ba-checking", "Checking", Amount(4140050))))),
            setOf("budget-household"),
            now,
        )
        val checking = storage.connections.budgets().single().budgetAccounts.single().id
        val added = storage.connections.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a",
                bank = BankChoice.Named("Kestrel Bank"),
                providerBankId = null,
                dataProviderId = feeds,
                fileFormatId = null,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = "Kestrel Checking",
                currency = usd,
                type = BankAccountType.Checking,
                providerReference = "acct-4410",
                bankAccountFingerprint = null,
                identifiedAt = now,
                startDate = null,
                budgetAccountId = checking,
            ),
        )
        return (added as AddOutcome.Added).bankAccountId
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Steps 8, 11 (O-1): the Fetch Result and the Account Balance of a Bank Account are stored together and read back with the Fetch, also after a restart; a later one replaces the Account Balance")
    fun bankAccountDeliveryIsStoredAndReadBack() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        val checking = addedChecking(storage, feeds)
        val first = storage.fetches.recordFetch(NewFetch(feeds, now, StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(
            BankAccountDelivery(first, checking, FetchResultOutcome.Fetched, 0, NewAccountBalance(Amount(5135280), CurrencyCode("USD"), now)),
        )
        val later = now.plusSeconds(3700)
        val second = storage.fetches.recordFetch(NewFetch(feeds, later, StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(
            BankAccountDelivery(second, checking, FetchResultOutcome.Fetched, 0, NewAccountBalance(Amount(3990010), CurrencyCode("USD"), later)),
        )
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(listOf(StoredAccountBalance(checking, Amount(3990010), CurrencyCode("USD"), later)), restarted.importStatus.accountBalances())
        assertEquals(
            listOf(listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0)), listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0))),
            restarted.importStatus.recentFetches().map { it.results },
        )
        assertEquals(restarted.importStatus.recentFetches(), restarted.fetches.fetchesOf(feeds))
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account")
    @DisplayName("A8 (O-1): a Fetch Result Failed keeps the stored Account Balance; a second Fetch Result for the same Fetch and Bank Account stores nothing, all or nothing")
    fun failedResultKeepsTheBalanceAndO1IsAllOrNothing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        val checking = addedChecking(storage, feeds)
        val first = storage.fetches.recordFetch(NewFetch(feeds, now, StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(
            BankAccountDelivery(first, checking, FetchResultOutcome.Fetched, 0, NewAccountBalance(Amount(5135280), CurrencyCode("USD"), now)),
        )
        val second = storage.fetches.recordFetch(NewFetch(feeds, now.plusSeconds(3700), StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(BankAccountDelivery(second, checking, FetchResultOutcome.Failed, 0, balance = null))

        val twice = runCatching {
            storage.fetches.storeBankAccountDelivery(
                BankAccountDelivery(second, checking, FetchResultOutcome.Fetched, 0, NewAccountBalance(Amount(1), CurrencyCode("USD"), now)),
            )
        }

        assertTrue(twice.isFailure)
        assertEquals(listOf(StoredAccountBalance(checking, Amount(5135280), CurrencyCode("USD"), now)), storage.importStatus.accountBalances())
        assertEquals(listOf(StoredFetchResult(checking, FetchResultOutcome.Failed, 0)), storage.fetches.fetchesOf(feeds).first().results)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: a Fetch that leaves the kept history goes with its Fetch Results in the same transaction; the Account Balance stays")
    fun olderFetchesGoWithTheirFetchResults() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        val checking = addedChecking(storage, feeds)

        repeat(3) {
            val at = now.plusSeconds(it * 3700L)
            val id = storage.fetches.recordFetch(NewFetch(feeds, at, StartedBy.Operator, FetchOutcome.Fetched), keep = 2)
            storage.fetches.storeBankAccountDelivery(
                BankAccountDelivery(id, checking, FetchResultOutcome.Fetched, 0, NewAccountBalance(Amount(1000L * it), CurrencyCode("USD"), at)),
            )
        }

        assertEquals(2, storage.importStatus.recentFetches().size)
        assertTrue(storage.importStatus.recentFetches().all { it.results.size == 1 })
        assertEquals(listOf(Amount(2000)), storage.importStatus.accountBalances().map { it.amount })
        val rows = storage.main.openHelper.readableDatabase.query("SELECT COUNT(*) FROM fetch_result").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2, rows)
        storage.close()
    }
}
