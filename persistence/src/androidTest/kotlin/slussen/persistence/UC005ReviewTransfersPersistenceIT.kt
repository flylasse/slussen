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
import slussen.core.port.BudgetReadToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.DataProviderRegistration
import slussen.core.port.DeliveredTransaction
import slussen.core.port.DeliveredTransactions
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.NewFetch
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferToStore
import slussen.core.port.TransferType
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Transfers a Comparison detects (UC-003 step 4, UC-005) as O-5 stores them in `slussen.db` on a device or emulator:
 * the `transfer` rows with the decision Proposed and `bank_transaction.transfer_id`, written with the Transaction Statuses
 * in the one transaction of `applyComparison`, all or nothing; the description lists read from `transfer_description`; a
 * Transfer that no longer holds releasing its Transfer Legs; a decided Transfer never changed; and that they survive a
 * restart (ADR-002 §1, §3). Invented data only (C-005).
 */
@DisplayName("UC-005: Review Transfers, on the device")
class UC005ReviewTransfersPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val usd = CurrencyCode("USD")
    private val oct12 = LocalDate.of(2026, 10, 12)
    private val oct13 = LocalDate.of(2026, 10, 13)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    /** What a test needs of the stored Household: its Budget, its Budget Accounts by name and its two or three Bank Accounts. */
    private data class Household(
        val budget: Long,
        val budgetAccounts: Map<String, Long>,
        val checking: Long,
        val card: Long,
        val reserve: Long,
        val feeds: Long,
    )

    /**
     * Household (USD) with Checking, Rewards Card and Reserve Savings, served; Kestrel Checking, the credit card Kestrel
     * Rewards and Reserve Saver mapped to them; and one read of the Budget App stored at [now], so that Bank Transactions
     * received before it are compared.
     */
    private suspend fun household(storage: SlussenStorage): Household {
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
                    listOf(
                        BudgetAccountToStore("ba-checking", "Checking", Amount(4140050)),
                        BudgetAccountToStore("ba-rewards", "Rewards Card", Amount(-1268400)),
                        BudgetAccountToStore("ba-reserve", "Reserve Savings", Amount(12000000)),
                    ),
                ),
            ),
            setOf("budget-household"),
            now.minusSeconds(86400),
        )
        val budget = storage.connections.budgets().single()
        val budgetAccounts = budget.budgetAccounts.associate { it.name to it.id }

        suspend fun add(identifier: String, name: String, type: BankAccountType, reference: String, budgetAccount: String): Long =
            (
                storage.connections.addBankAccount(
                    NewBankAccount(
                        generatedIdentifier = identifier,
                        bank = BankChoice.Named("Kestrel Bank"),
                        providerBankId = null,
                        dataProviderId = feeds,
                        fileFormatId = null,
                        balanceDataProviderId = null,
                        balanceProviderReference = null,
                        name = name,
                        currency = usd,
                        type = type,
                        providerReference = reference,
                        bankAccountFingerprint = null,
                        identifiedAt = now.minusSeconds(86400),
                        startDate = null,
                        budgetAccountId = budgetAccounts.getValue(budgetAccount),
                    ),
                ) as AddOutcome.Added
                ).bankAccountId

        val checking = add("6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a", "Kestrel Checking", BankAccountType.Checking, "acct-4410", "Checking")
        val card = add("0d9b7c1a-52e4-4f0b-8c3d-7a1e5f2b9c60", "Kestrel Rewards", BankAccountType.CreditCard, "acct-7781", "Rewards Card")
        val reserve = add("b2a4e6c8-1d3f-4a5b-9c7e-0f1a2b3c4d5e", "Reserve Saver", BankAccountType.Savings, "acct-9020", "Reserve Savings")
        storage.comparisons.applyChangeSet(BudgetReadToStore(budget.id, null, emptyList(), ChangeCursor("1"), emptyMap(), now))
        return Household(budget.id, budgetAccounts, checking, card, reserve, feeds)
    }

    /** One Fetch that stored [transactions] for [bankAccount], received an hour before the read of the Budget App. */
    private suspend fun deliver(storage: SlussenStorage, household: Household, bankAccount: Long, vararg transactions: DeliveredTransaction) {
        val fetch = storage.fetches.recordFetch(NewFetch(household.feeds, now.minusSeconds(3600), StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(
            BankAccountDelivery(
                fetch, bankAccount, FetchResultOutcome.Fetched, transactions.size, balance = null,
                transactions = DeliveredTransactions("example-feeds", now.minusSeconds(3600), transactions.toList(), emptyList()),
            ),
        )
    }

    private fun delivered(reference: String, date: LocalDate, thousandths: Long, payee: String = "PLANTED-PAYEE") =
        DeliveredTransaction(reference, date, Amount(thousandths), usd, payee, null)

    private fun windows(today: LocalDate = LocalDate.of(2026, 10, 15)) = ComparisonWindows(
        uploadWindowDays = 7, fileUploadWindowDays = 60, matchingDateWindowDays = 10, transferPairingDays = 5, lonelyTransferGraceDays = 6,
        today = today,
    )

    private suspend fun compare(storage: SlussenStorage, household: Household, today: LocalDate = LocalDate.of(2026, 10, 15)) =
        storage.comparisons.applyComparison(setOf(household.budget)) { data -> ComparisonRules.conclude(data, windows(today)) }

    private suspend fun transfers(storage: SlussenStorage): List<StoredTransfer> = storage.main.budgetAppCopy().transfers().map { it.stored() }

    private suspend fun transaction(storage: SlussenStorage, bankAccount: Long, reference: String): StoredBankTransaction =
        storage.fetches.bankTransactionsOf(bankAccount).single { it.stableReference == reference }

    private fun pending(storage: SlussenStorage): Boolean =
        storage.main.openHelper.readableDatabase.query("SELECT comparison_pending FROM coordinator_state WHERE id = 1").use { it.moveToFirst(); it.getInt(0) == 1 }

    private fun execute(storage: SlussenStorage, sql: String, vararg arguments: Any) =
        storage.main.openHelper.writableDatabase.execSQL(sql, arguments)

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-010"])
    @DisplayName("BR-002, BR-010 (O-5): a detected Transfer is stored with the decision Proposed, its Transfer Legs' transfer_id and their Transaction Statuses, and read back after a restart with the counts of its Bank Accounts")
    fun detectedTransferIsStoredWithItsTransferLegs() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct12, -250400), delivered("TX-2", oct13, -18000))
        deliver(storage, household, household.card, delivered("TX-3", oct13, 250400))

        val changes = compare(storage, household)
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(3, changes.size)
        val transfer = transfers(restarted).single()
        assertEquals(household.budgetAccounts.getValue("Checking"), transfer.outflowBudgetAccountId)
        assertEquals(household.budgetAccounts.getValue("Rewards Card"), transfer.inflowBudgetAccountId)
        assertEquals(Amount(250400), transfer.amount)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertFalse(transfer.guessed)
        assertEquals(TransferDecision.Proposed, transfer.decision)
        assertNull(transfer.decidedAt)
        val paid = transaction(restarted, household.checking, "TX-1")
        val received = transaction(restarted, household.card, "TX-3")
        val groceries = transaction(restarted, household.checking, "TX-2")
        assertEquals(listOf(transfer.id, transfer.id, null), listOf(paid.transferId, received.transferId, groceries.transferId))
        assertEquals(listOf(TransactionStatus.TransferLeg, TransactionStatus.TransferLeg, TransactionStatus.New), listOf(paid.status, received.status, groceries.status))
        assertFalse(pending(restarted))
        // UC-003 BR-014: the Transfer Leg that writes its Transfer and the ordinary transaction; nothing of the inflow side.
        val counts = restarted.importStatus.transactionCounts().associate { it.bankAccountId to it.eligible }
        assertEquals(2, counts.getValue(household.checking))
        assertEquals(0, counts.getValue(household.card))
        assertTrue(compare(restarted, household).isEmpty())
        assertEquals(listOf(transfer), transfers(restarted))
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("BR-012 (O-5): the Card Payment Descriptions and Bank Transfer Descriptions are read from transfer_description: the default lists of a new slussen.db, and an entry stored since")
    fun descriptionListsAreReadFromStorage() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct12, -500000, payee = "Online Transfer to savings"), delivered("TX-2", oct13, -45000, payee = "Sweep 0091 to reserve"))
        deliver(storage, household, household.reserve, delivered("TX-3", oct12, 500000, payee = "Deposit"), delivered("TX-4", oct13, 45000, payee = "Deposit"))
        var read: List<StoredTransferDescription> = emptyList()

        storage.comparisons.applyComparison(setOf(household.budget)) { data ->
            read = data.transferDescriptions
            ComparisonRules.conclude(data, windows())
        }

        assertEquals(16, read.size)
        assertTrue(StoredTransferDescription(TransferDescriptionKind.CardPayment, "AUTOPAY") in read)
        assertTrue(StoredTransferDescription(TransferDescriptionKind.BankTransfer, "ÖVERFÖRING") in read)
        assertEquals(TransferType.BankTransfer, transfers(storage).single().type)
        assertEquals(TransactionStatus.TransferLeg, transaction(storage, household.checking, "TX-1").status)
        assertEquals(TransactionStatus.New, transaction(storage, household.checking, "TX-2").status)

        execute(storage, "INSERT INTO transfer_description (kind, text) VALUES ('Bank Transfer', 'SWEEP * RESERVE')")
        compare(storage, household)

        assertEquals(2, transfers(storage).size)
        assertEquals(TransactionStatus.TransferLeg, transaction(storage, household.checking, "TX-2").status)
        assertEquals(TransactionStatus.TransferLeg, transaction(storage, household.reserve, "TX-4").status)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period", businessRules = ["BR-005"])
    @DisplayName("A4, A5, BR-005 (O-5): a Lonely Transfer is stored with one Transfer Leg; when its other side arrives the same Transfer has two; when the grace period passes without it, its Transfer Leg is released and the Transfer removed")
    fun lonelyTransferIsContinuedOrReleased() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        execute(storage, "UPDATE bank_account SET funded_by_budget_account_id = ? WHERE id = ?", household.budgetAccounts.getValue("Checking"), household.card)
        deliver(storage, household, household.card, delivered("TX-1", oct13, 120000, payee = "AUTOPAY"), delivered("TX-2", oct13, 64000, payee = "PAYMENT - THANK YOU"))

        compare(storage, household)

        val lonely = transfers(storage)
        assertEquals(2, lonely.size)
        assertTrue(lonely.all { it.outflowBudgetAccountId == household.budgetAccounts.getValue("Checking") && !it.guessed })
        val first = transaction(storage, household.card, "TX-1")
        val second = transaction(storage, household.card, "TX-2")
        assertEquals(TransactionStatus.TransferLeg, first.status)
        assertEquals(setOf(first.transferId, second.transferId), lonely.map { it.id }.toSet())

        // The other side of the first arrives; the second is still alone on 20 October, the day after its grace period of
        // 6 days ended, and both are still inside the Upload Window of 7 days.
        deliver(storage, household, household.checking, delivered("TX-3", oct13, -120000))
        compare(storage, household, today = LocalDate.of(2026, 10, 20))

        val kept = transfers(storage).single()
        assertEquals(first.transferId, kept.id)
        assertEquals(kept.id, transaction(storage, household.checking, "TX-3").transferId)
        assertEquals(kept.id, transaction(storage, household.card, "TX-1").transferId)
        val released = transaction(storage, household.card, "TX-2")
        assertNull(released.transferId)
        assertEquals(TransactionStatus.New, released.status)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("O-5 is all or nothing: when a Transfer cannot be stored, no Transaction Status, no Transfer and no transfer_id of this Comparison is stored, and the Comparison-pending marker stays set")
    fun o5StoresTransfersAndStatusesTogetherOrNotAtAll() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct12, -250400))
        deliver(storage, household, household.card, delivered("TX-2", oct13, 250400))

        val failed = runCatching {
            storage.comparisons.applyComparison(setOf(household.budget)) { data ->
                val concluded = ComparisonRules.conclude(data, windows())
                // A Budget Account that is not stored: the foreign key refuses the Transfer after the statuses were written.
                concluded.copy(transfers = concluded.transfers.map { it.copy(inflowBudgetAccountId = 999_999) })
            }
        }

        assertTrue(failed.isFailure)
        assertTrue(transfers(storage).isEmpty())
        val paid = transaction(storage, household.checking, "TX-1")
        assertNull(paid.status)
        assertNull(paid.transferId)
        assertNull(transaction(storage, household.card, "TX-2").status)
        assertTrue(pending(storage))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("O-5: a Transfer that no longer holds releases its Transfer Legs and is removed; a Transfer the Operator decided on is changed neither by a Comparison that no longer detects it nor by one whose conclusions name it, which is refused as a whole")
    fun onlyProposedTransfersAreReleasedOrChanged() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct12, -250400), delivered("TX-2", oct12, -60000))
        deliver(storage, household, household.card, delivered("TX-3", oct13, 250400), delivered("TX-4", oct13, 60000))
        compare(storage, household)
        val decided = transaction(storage, household.checking, "TX-1").transferId!!
        val proposed = transaction(storage, household.checking, "TX-2").transferId!!
        execute(storage, "UPDATE transfer SET decision = 'Held', decided_at = ? WHERE id = ?", now.toEpochMilli(), decided)
        execute(storage, "UPDATE bank_transaction SET status = 'Held Transfer' WHERE transfer_id = ?", decided)

        // With 0 transfer pairing days, no pair dated a day apart is a Transfer any more: nothing is detected.
        val changes = storage.comparisons.applyComparison(setOf(household.budget)) { data ->
            ComparisonRules.conclude(data, windows().copy(transferPairingDays = 0))
        }
        // A conclusion that names the decided Transfer is refused as a whole and does not change it either.
        val refused = runCatching {
            storage.comparisons.applyComparison(setOf(household.budget)) { data ->
                val concluded = ComparisonRules.conclude(data, windows().copy(transferPairingDays = 0))
                val legs = data.bankTransactions.filter { it.transferId == decided }.map { it.id }
                concluded.copy(
                    transfers = listOf(
                        TransferToStore(decided, household.budgetAccounts.getValue("Reserve Savings"), household.budgetAccounts.getValue("Checking"), Amount(1), TransferType.BankTransfer, guessed = true, legs = legs.take(1)),
                    ),
                )
            }
        }

        assertTrue(refused.isFailure)
        val kept = transfers(storage).single()
        assertEquals(decided, kept.id)
        assertEquals(TransferDecision.Held, kept.decision)
        assertEquals(now, kept.decidedAt)
        assertEquals(Amount(250400), kept.amount)
        assertEquals(household.budgetAccounts.getValue("Checking"), kept.outflowBudgetAccountId)
        assertEquals(TransactionStatus.HeldTransfer, transaction(storage, household.checking, "TX-1").status)
        assertEquals(decided, transaction(storage, household.card, "TX-3").transferId)
        assertTrue(transfers(storage).none { it.id == proposed })
        val released = transaction(storage, household.checking, "TX-2")
        assertNull(released.transferId)
        assertEquals(TransactionStatus.New, released.status)
        assertNull(transaction(storage, household.card, "TX-4").transferId)
        assertEquals(2, changes.size)
        storage.close()
    }
}
