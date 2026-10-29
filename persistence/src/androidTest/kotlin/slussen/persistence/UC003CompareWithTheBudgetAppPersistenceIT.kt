package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountDelivery
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.BudgetReadToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.ChangeSetApplied
import slussen.core.port.DataProviderRegistration
import slussen.core.port.DeliveredTransaction
import slussen.core.port.DeliveredTransactions
import slussen.core.port.EnteredBy
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.NewFetch
import slussen.core.port.StartedBy
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.TransactionStatus
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransferLink
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The stored copy of the Budget App with its cursor (O-4) and the Transaction Statuses concluded from it (O-5) of Compare
 * With the Budget App (UC-003) in `slussen.db` on a device or emulator: every field kept, the cursor stored with its
 * copy, all or nothing, the Comparison-pending marker between the two (ADR-007 §3a), the counts the import status reads,
 * and that they survive a restart (ADR-002 §1, §3). Invented data only (C-005).
 */
@DisplayName("UC-003: Compare With the Budget App, on the device")
class UC003CompareWithTheBudgetAppPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val usd = CurrencyCode("USD")
    private val oct14 = LocalDate.of(2026, 10, 14)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    /** Household (USD) with Checking and Reserve Savings, served, and Kestrel Checking mapped to Checking; returns (budget, bank account). */
    private suspend fun household(storage: SlussenStorage): Pair<Long, Long> {
        val profile = ExampleData.feedsProfile
        val feeds = storage.registry.reconcile(
            listOf(
                DataProviderRegistration(
                    profile.name, profile.sourceKey, profile.givesStableReference, profile.needsBankConsent,
                    profile.defaultFetchCooldownMinutes, profile.requestLimit,
                ),
            ),
            emptyList(),
        ).dataProviders.single().id
        storage.connections.serveBudgets(
            listOf(
                BudgetToServe(
                    "budget-household", "Household", usd,
                    listOf(BudgetAccountToStore("ba-checking", "Checking", Amount(4140050)), BudgetAccountToStore("ba-reserve", "Reserve Savings", Amount(12000000))),
                ),
            ),
            setOf("budget-household"),
            now.minusSeconds(86400),
        )
        val budget = storage.connections.budgets().single()
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
                identifiedAt = now.minusSeconds(86400),
                startDate = null,
                budgetAccountId = budget.budgetAccounts.single { it.name == "Checking" }.id,
            ),
        ) as AddOutcome.Added
        val fetch = storage.fetches.recordFetch(NewFetch(feeds, now.minusSeconds(3600), StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(
            BankAccountDelivery(
                fetch, added.bankAccountId, FetchResultOutcome.Fetched, 2, balance = null,
                transactions = DeliveredTransactions(
                    "example-feeds",
                    now.minusSeconds(3600),
                    listOf(
                        DeliveredTransaction("TX-1", oct14, Amount(-42500), usd, "Corner Grocer", null),
                        DeliveredTransaction("TX-2", oct14, Amount(-8000), usd, "Harbor Fuel", null),
                    ),
                    emptyList(),
                ),
            ),
        )
        return budget.id to added.bankAccountId
    }

    private fun read(
        reference: String,
        amount: Long,
        importIdentifier: String? = null,
        state: ReadState = ReadState.Present,
        link: TransferLink? = null,
        account: String = "ba-checking",
        entry: EntryKind = if (importIdentifier == null) EntryKind.Typed else EntryKind.Imported,
    ) = BudgetAppTransactionRead(reference, account, oct14, Amount(amount), "Corner Grocer", "PLANTED-MEMO", importIdentifier, entry, link, state, cleared = true, approved = false)

    private fun pending(storage: SlussenStorage): Boolean =
        storage.main.openHelper.readableDatabase.query("SELECT comparison_pending FROM coordinator_state WHERE id = 1").use { it.moveToFirst(); it.getInt(0) == 1 }

    private val windows = ComparisonWindows(uploadWindowDays = 7, fileUploadWindowDays = 60, matchingDateWindowDays = 10, today = LocalDate.of(2026, 10, 15))

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016", "BR-020"])
    @DisplayName("Step 1, BR-020 (O-4): the copy is stored with every field, the Budget Account balances, the time of the read, the cursor and the Comparison-pending marker, and read back after a restart")
    fun readIsStoredWithItsCursor() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val (budget, _) = household(storage)

        val applied = storage.comparisons.applyChangeSet(
            BudgetReadToStore(
                budget, null,
                listOf(
                    read("ynab-1", -42500, importIdentifier = "YNAB:-42500:2026-10-14:1"),
                    read("ynab-2", -100000, link = TransferLink("ynab-3", "ba-reserve")),
                    read("ynab-3", 100000, link = TransferLink("ynab-2", "ba-checking"), account = "ba-reserve", entry = EntryKind.TransferSide),
                    read("ynab-4", -8000, state = ReadState.Merged("ynab-1")),
                ),
                ChangeCursor("4182"),
                mapOf("ba-checking" to Amount(4000000), "ba-reserve" to Amount(12500000)),
                now,
            ),
        )
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(ChangeSetApplied.Stored(4), applied)
        val copy = restarted.comparisons.budgetAppTransactionsOf(budget).associateBy { it.budgetAppReference }
        assertEquals(EnteredBy.OtherImportPath, copy.getValue("ynab-1").enteredBy)
        assertEquals("PLANTED-MEMO", copy.getValue("ynab-1").memo)
        assertTrue(copy.getValue("ynab-1").cleared)
        assertFalse(copy.getValue("ynab-1").approved)
        assertEquals("ynab-3", copy.getValue("ynab-2").transferLinkReference)
        assertEquals(BudgetAppTransactionState.Merged, copy.getValue("ynab-4").state)
        assertEquals("ynab-1", copy.getValue("ynab-4").mergedIntoReference)
        assertEquals(ChangeCursor("4182"), restarted.comparisons.changeCursorOf(budget))
        val stored = restarted.connections.budgets().single()
        assertEquals(now, stored.lastReadAt)
        assertEquals(listOf(Amount(4000000), Amount(12500000)), stored.budgetAccounts.map { it.balance })
        assertTrue(pending(restarted))
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016", "BR-020"])
    @DisplayName("BR-016 (O-4): a later read is applied only from the stored cursor; one that started from another cursor stores nothing; a deletion updates the stored transaction by its reference and keeps it")
    fun laterReadIsAppliedFromTheStoredCursor() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val (budget, _) = household(storage)
        storage.comparisons.applyChangeSet(BudgetReadToStore(budget, null, listOf(read("ynab-1", -42500)), ChangeCursor("1"), emptyMap(), now))

        val stale = storage.comparisons.applyChangeSet(
            BudgetReadToStore(budget, ChangeCursor("0"), listOf(read("ynab-9", -1)), ChangeCursor("2"), emptyMap(), now.plusSeconds(60)),
        )
        val later = storage.comparisons.applyChangeSet(
            BudgetReadToStore(budget, ChangeCursor("1"), listOf(read("ynab-1", -42500, state = ReadState.Deleted)), ChangeCursor("2"), emptyMap(), now.plusSeconds(120)),
        )

        assertEquals(ChangeSetApplied.CursorMoved, stale)
        assertEquals(ChangeSetApplied.Stored(1), later)
        val copy = storage.comparisons.budgetAppTransactionsOf(budget)
        assertEquals(listOf("ynab-1"), copy.map { it.budgetAppReference })
        assertEquals(BudgetAppTransactionState.Deleted, copy.single().state)
        assertEquals(ChangeCursor("2"), storage.comparisons.changeCursorOf(budget))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-020"])
    @DisplayName("BR-020 (O-4): a read that cannot be stored stores none of it: no transaction, no balance, no cursor and no Comparison-pending marker")
    fun o4IsAllOrNothing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        household(storage)

        val failed = runCatching {
            storage.comparisons.applyChangeSet(BudgetReadToStore(999, null, emptyList(), ChangeCursor("1"), emptyMap(), now))
        }

        assertTrue(failed.isFailure, "a cursor of a Budget that is not stored is refused by its foreign key")
        assertNull(storage.comparisons.changeCursorOf(999))
        assertFalse(pending(storage))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-006", "BR-014"])
    @DisplayName("Steps 3, 5, 6 (O-5): the Transaction Statuses concluded from the stored copy are stored, the changes returned for the Decision Journal, the Comparison-pending marker cleared, and the counts read back after a restart")
    fun conclusionsAreStoredFromTheCopy() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val (budget, checking) = household(storage)
        assertEquals(listOf(null, null), storage.fetches.bankTransactionsOf(checking).map { it.status })
        storage.comparisons.applyChangeSet(BudgetReadToStore(budget, null, listOf(read("ynab-1", -42500)), ChangeCursor("1"), emptyMap(), now))

        val changes = storage.comparisons.applyComparison(setOf(budget)) { data -> ComparisonRules.conclude(data, windows) }
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(listOf(TransactionStatus.MatchForTypedEntry, TransactionStatus.New), changes.map { it.to })
        assertTrue(changes.all { it.from == null })
        assertEquals(
            listOf(TransactionStatus.MatchForTypedEntry, TransactionStatus.New),
            restarted.fetches.bankTransactionsOf(checking).map { it.status },
        )
        assertFalse(pending(restarted))
        assertEquals(listOf(StoredTransactionCounts(checking, eligible = 2, waitingForDecision = 0)), restarted.importStatus.transactionCounts())
        assertTrue(restarted.comparisons.applyComparison(setOf(budget)) { data -> ComparisonRules.conclude(data, windows) }.isEmpty())
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-020"])
    @DisplayName("ADR-007 §3a (O-5): a Comparison whose conclusions fail leaves no status and the Comparison-pending marker set, so they are concluded again from the stored copy")
    fun o5IsAllOrNothing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val (budget, checking) = household(storage)
        storage.comparisons.applyChangeSet(BudgetReadToStore(budget, null, listOf(read("ynab-1", -42500)), ChangeCursor("1"), emptyMap(), now))

        val failed = runCatching {
            storage.comparisons.applyComparison(setOf(budget)) { data ->
                ComparisonRules.conclude(data, windows).mapValues { (id, status) -> if (id == data.bankTransactions.last().id) error("planted failure") else status }
            }
        }

        assertTrue(failed.isFailure)
        assertEquals(listOf(null, null), storage.fetches.bankTransactionsOf(checking).map { it.status })
        assertTrue(pending(storage))
        storage.close()
    }
}
