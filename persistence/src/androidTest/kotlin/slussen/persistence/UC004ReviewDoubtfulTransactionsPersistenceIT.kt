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
import slussen.core.port.ChosenBudgetAppTransaction
import slussen.core.port.ComparisonConclusions
import slussen.core.port.DataProviderRegistration
import slussen.core.port.DeliveredTransaction
import slussen.core.port.DeliveredTransactions
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.NewFetch
import slussen.core.port.NewUnreadableTransaction
import slussen.core.port.ReviewChoice
import slussen.core.port.ReviewDecided
import slussen.core.port.ReviewDecisionWrite
import slussen.core.port.ReviewRuling
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredReviewDecision
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferToStore
import slussen.core.port.TransferType
import slussen.core.port.UnreadableTransactionReason
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.review.ReviewOutcome
import slussen.core.review.ReviewRules
import slussen.core.review.RuledOutcome
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Review Decisions of Review Doubtful Transactions (UC-004) as O-13 `ReviewStore.decide` stores them in `slussen.db`
 * on a device or emulator (ADR-002 §3): the `review_decision` row with the chosen Budget App Transaction, the Transaction
 * Statuses that follow from it, also of the other Transfer Leg, in one transaction, all or nothing, with `state_version`
 * raised; a ruling without a write storing nothing; the status of an Unreadable Transaction; and that they survive a
 * restart (ADR-002 §1). The rules are the core's ([ReviewRules], [ComparisonRules]), applied inside the transaction.
 * Invented data only (C-005).
 */
@DisplayName("UC-004: Review Doubtful Transactions, on the device")
class UC004ReviewDoubtfulTransactionsPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val usd = CurrencyCode("USD")
    private val oct12 = LocalDate.of(2026, 10, 12)
    private val oct13 = LocalDate.of(2026, 10, 13)
    private val oct14 = LocalDate.of(2026, 10, 14)
    private val windows = ComparisonWindows(
        uploadWindowDays = 7, fileUploadWindowDays = 60, matchingDateWindowDays = 10, transferPairingDays = 5, lonelyTransferGraceDays = 6,
        today = LocalDate.of(2026, 10, 15),
    )

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    /** What a test needs of the stored Household: its Budget, its Budget Accounts by name and its two Bank Accounts. */
    private data class Household(val budget: Long, val budgetAccounts: Map<String, Long>, val checking: Long, val card: Long, val feeds: Long)

    /**
     * Household (USD) with Checking and Rewards Card, served; Kestrel Checking and the credit card Kestrel Rewards, whose
     * Funded By is Checking, mapped to them.
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
                    listOf(BudgetAccountToStore("ba-checking", "Checking", Amount(4140050)), BudgetAccountToStore("ba-rewards", "Rewards Card", Amount(-1268400))),
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
        storage.main.openHelper.writableDatabase.execSQL(
            "UPDATE bank_account SET funded_by_budget_account_id = ? WHERE id = ?", arrayOf<Any>(budgetAccounts.getValue("Checking"), card),
        )
        return Household(budget.id, budgetAccounts, checking, card, feeds)
    }

    /** One Fetch that stored [transactions] and [unreadable] for [bankAccount], received an hour before the read of the Budget App. */
    private suspend fun deliver(
        storage: SlussenStorage,
        household: Household,
        bankAccount: Long,
        vararg transactions: DeliveredTransaction,
        unreadable: List<NewUnreadableTransaction> = emptyList(),
    ) {
        val fetch = storage.fetches.recordFetch(NewFetch(household.feeds, now.minusSeconds(3600), StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.storeBankAccountDelivery(
            BankAccountDelivery(
                fetch, bankAccount, FetchResultOutcome.Fetched, transactions.size, balance = null,
                transactions = DeliveredTransactions("example-feeds", now.minusSeconds(3600), transactions.toList(), unreadable),
            ),
        )
    }

    private fun delivered(reference: String?, date: LocalDate, thousandths: Long) = DeliveredTransaction(reference, date, Amount(thousandths), usd, "PLANTED-PAYEE", null)

    /** An Import by an Other Import Path as the Budget App reports it. */
    private fun otherImport(reference: String, date: LocalDate, thousandths: Long, account: String = "ba-checking") = BudgetAppTransactionRead(
        reference, account, date, Amount(thousandths), "Riverside Apothecary", null, "YNAB:$reference", EntryKind.Imported, null, ReadState.Present, cleared = true, approved = true,
    )

    /** O-4 and O-5: the read of the Budget App with [transactions], stored at [now] with the cursor [next], and its conclusions. */
    private suspend fun compare(storage: SlussenStorage, household: Household, next: String, vararg transactions: BudgetAppTransactionRead) {
        val since = storage.comparisons.changeCursorOf(household.budget)
        storage.comparisons.applyChangeSet(BudgetReadToStore(household.budget, since, transactions.toList(), ChangeCursor(next), emptyMap(), now))
        storage.comparisons.applyComparison(setOf(household.budget)) { data -> ComparisonRules.conclude(data, windows) }
    }

    private suspend fun transaction(storage: SlussenStorage, bankAccount: Long, reference: String?): StoredBankTransaction =
        storage.fetches.bankTransactionsOf(bankAccount).single { it.stableReference == reference }

    private suspend fun stateVersion(storage: SlussenStorage): Long = checkNotNull(storage.main.coordinator().stateVersion())

    /** UC-004 steps 3-7 through O-13, with the core's rules applied inside its transaction. */
    private suspend fun decide(storage: SlussenStorage, household: Household, bankTransaction: Long, choice: ReviewChoice, chosen: String? = null): ReviewDecided<RuledOutcome> =
        storage.reviews.decide(setOf(household.budget)) { data ->
            val (write, ruled) = ReviewRules.decide(bankTransaction, choice, chosen, data, windows, budgetAppRefusesReusedImportIdentifier = true, now = now)
            ReviewRuling(write, ruled)
        }

    private suspend fun clear(storage: SlussenStorage, household: Household, bankTransaction: Long): ReviewDecided<RuledOutcome> =
        storage.reviews.decide(setOf(household.budget)) { data ->
            val (write, ruled) = ReviewRules.clear(bankTransaction, data, windows, budgetAppRefusesReusedImportIdentifier = true)
            ReviewRuling(write, ruled)
        }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-006"])
    @DisplayName("Steps 6-7, BR-002, BR-006 (O-13): an Already Present decision is stored in slussen.db with the chosen Budget App Transaction, its Budget Account and its amount, together with the status Already In Budget App, and raises state_version; the Comparison reads it back, it is the same after a restart, and the next Comparison rechecks it (UC-003 BR-009)")
    fun alreadyPresentIsStoredWithItsStatusAndReadBackAfterARestart() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct14, -18_600))
        compare(storage, household, "k-1", otherImport("ynab-1", oct13, -18_600), otherImport("ynab-2", oct14, -18_600))
        val tx = transaction(storage, household.checking, "TX-1")
        assertEquals(TransactionStatus.PossibleImportByOtherPath, tx.status)
        val version = stateVersion(storage)

        val decided = decide(storage, household, tx.id, ReviewChoice.AlreadyPresent, chosen = "ynab-2")

        val expected = StoredReviewDecision(tx.id, ReviewChoice.AlreadyPresent, ChosenBudgetAppTransaction("ynab-2", household.budgetAccounts.getValue("Checking"), Amount(-18_600)), now)
        assertEquals(ReviewOutcome.Stored(ReviewChoice.AlreadyPresent, TransactionStatus.AlreadyInBudgetApp), decided.answer.outcome)
        assertEquals(listOf(TransactionStatus.PossibleImportByOtherPath to TransactionStatus.AlreadyInBudgetApp), decided.changes.map { it.from to it.to })
        assertEquals(listOf(expected), storage.comparisons.comparisonDataOf(setOf(household.budget)).reviewDecisions)
        assertEquals(TransactionStatus.AlreadyInBudgetApp, transaction(storage, household.checking, "TX-1").status)
        assertTrue(stateVersion(storage) > version)
        assertEquals(tx, storage.reviews.bankTransaction(tx.id)?.copy(status = TransactionStatus.PossibleImportByOtherPath))
        storage.close()

        val reopened = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(listOf(expected), reopened.comparisons.comparisonDataOf(setOf(household.budget)).reviewDecisions)
        assertEquals(TransactionStatus.AlreadyInBudgetApp, transaction(reopened, household.checking, "TX-1").status)
        // The next Comparison rechecks the decision against the stored copy (UC-003 BR-009): the chosen transaction has another amount now.
        compare(reopened, household, "k-2", otherImport("ynab-2", oct14, -18_610))
        assertEquals(TransactionStatus.DecisionNoLongerHolds, transaction(reopened, household.checking, "TX-1").status)
        reopened.close()
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-004", "BR-006"])
    @DisplayName("A6, BR-004 (O-13): a Bank Transaction keeps at most one Review Decision: a change replaces it in its own row, clearing removes it and gives the status without a decision, each raising state_version; a ruling that stores nothing (a choice the status does not offer, nothing to clear) leaves the decision, the statuses and state_version as they are")
    fun aDecisionIsReplacedAndClearedAndARefusalStoresNothing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct14, -18_600))
        compare(storage, household, "k-1", otherImport("ynab-1", oct13, -18_600))
        val tx = transaction(storage, household.checking, "TX-1").id
        val dao = storage.main.reviewDecisions()

        decide(storage, household, tx, ReviewChoice.Defer)
        val first = dao.all().single()
        val afterDefer = stateVersion(storage)
        decide(storage, household, tx, ReviewChoice.AlreadyPresent)
        val replaced = dao.all().single()
        val afterChange = stateVersion(storage)
        // Re-upload is not a choice of Possible Import By Other Path: the rule gives no write.
        val refused = decide(storage, household, tx, ReviewChoice.ReUpload)
        val afterRefusal = stateVersion(storage)

        assertEquals(ReviewChoice.Defer.text, first.choice)
        assertEquals(first.id, replaced.id)
        assertEquals(listOf(ReviewChoice.AlreadyPresent.text, "ynab-1", household.budgetAccounts.getValue("Checking"), -18_600L), listOf(replaced.choice, replaced.chosenBudgetAppReference, replaced.chosenBudgetAccountId, replaced.chosenAmount))
        assertTrue(afterChange > afterDefer)
        assertTrue(refused.answer.outcome is ReviewOutcome.NotOffered)
        assertTrue(refused.changes.isEmpty())
        assertEquals(afterChange, afterRefusal)
        assertEquals(listOf(replaced), dao.all())
        assertEquals(TransactionStatus.AlreadyInBudgetApp, transaction(storage, household.checking, "TX-1").status)

        val cleared = clear(storage, household, tx)
        val afterClear = stateVersion(storage)
        val clearedAgain = clear(storage, household, tx)

        assertEquals(ReviewOutcome.Stored(null, TransactionStatus.PossibleImportByOtherPath), cleared.answer.outcome)
        assertTrue(dao.all().isEmpty())
        assertEquals(TransactionStatus.PossibleImportByOtherPath, transaction(storage, household.checking, "TX-1").status)
        assertTrue(afterClear > afterRefusal)
        assertEquals(ReviewOutcome.NothingToClear, clearedAgain.answer.outcome)
        assertEquals(afterClear, stateVersion(storage))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A11: Doubtful Transfer Leg", businessRules = ["BR-009"])
    @DisplayName("A11, BR-009 (O-13, S-17): a Review Decision on a Transfer Leg sets the status of the other Transfer Leg of its Transfer in the same transaction: Deferred for Defer, Already In Budget App while an Already Present decision holds, Transfer Leg again after Import Anyway; the Transfer and its Transfer Legs stay as stored")
    fun aDecisionOnATransferLegSetsTheOtherLegsStatus() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct12, -99_000))
        deliver(storage, household, household.card, delivered("TX-2", oct12, 99_000))
        compare(storage, household, "k-1", otherImport("ynab-1", oct12, 99_000, account = "ba-rewards"))
        val paid = transaction(storage, household.checking, "TX-1")
        val received = transaction(storage, household.card, "TX-2")
        assertEquals(TransactionStatus.TransferLeg to TransactionStatus.PossibleImportByOtherPath, paid.status to received.status)
        assertEquals(paid.transferId, received.transferId)
        val transfers = storage.main.budgetAppCopy().transfers()

        suspend fun statuses() = transaction(storage, household.checking, "TX-1").status to transaction(storage, household.card, "TX-2").status

        val deferred = decide(storage, household, received.id, ReviewChoice.Defer)
        assertEquals(TransactionStatus.Deferred to TransactionStatus.Deferred, statuses())
        assertEquals(setOf(paid.id, received.id), deferred.changes.map { it.storedBankTransaction }.toSet())
        decide(storage, household, received.id, ReviewChoice.AlreadyPresent)
        assertEquals(TransactionStatus.AlreadyInBudgetApp to TransactionStatus.AlreadyInBudgetApp, statuses())
        decide(storage, household, received.id, ReviewChoice.ImportAnyway)
        assertEquals(TransactionStatus.TransferLeg to TransactionStatus.TransferLeg, statuses())
        clear(storage, household, received.id)
        assertEquals(TransactionStatus.TransferLeg to TransactionStatus.PossibleImportByOtherPath, statuses())

        assertEquals(transfers, storage.main.budgetAppCopy().transfers())
        assertEquals(paid.transferId, transaction(storage, household.card, "TX-2").transferId)
        storage.close()
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Failure postcondition (O-13, all or nothing): when the conclusions of a ruling cannot be stored, the Review Decision is not stored either: no review_decision row, every Transaction Status and state_version as before")
    fun aDecisionWhoseConclusionsCannotBeStoredIsNotStored() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(storage, household, household.checking, delivered("TX-1", oct14, -18_600))
        compare(storage, household, "k-1", otherImport("ynab-1", oct13, -18_600))
        val tx = transaction(storage, household.checking, "TX-1").id
        val version = stateVersion(storage)

        // Conclusions that continue a Transfer that is not stored: O-5's writes refuse them after the decision's row was written.
        val failed = runCatching {
            storage.reviews.decide(setOf(household.budget)) {
                ReviewRuling(
                    ReviewDecisionWrite(
                        tx, StoredReviewDecision(tx, ReviewChoice.Defer, chosen = null, decidedAt = now),
                        ComparisonConclusions(
                            statuses = mapOf(tx to TransactionStatus.Deferred),
                            transfers = listOf(TransferToStore(continues = 4711, household.budgetAccounts.getValue("Checking"), household.budgetAccounts.getValue("Rewards Card"), Amount(18_600), TransferType.CardPayment, guessed = false, legs = listOf(tx))),
                        ),
                    ),
                    answer = Unit,
                )
            }
        }

        assertTrue(failed.isFailure)
        assertTrue(storage.main.reviewDecisions().all().isEmpty())
        assertEquals(TransactionStatus.PossibleImportByOtherPath, transaction(storage, household.checking, "TX-1").status)
        assertNull(transaction(storage, household.checking, "TX-1").transferId)
        assertEquals(version, stateVersion(storage))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A9: Unreadable Transaction", businessRules = ["BR-007"])
    @DisplayName("A9, BR-007: the status of an Unreadable Transaction the Operator defers is stored as Deferred and read back after a restart, and Needs Review again when the decision is cleared; it is never in an Upload Plan, so state_version is left as it is; an Unreadable Transaction that is not stored changes nothing")
    fun theStatusOfAnUnreadableTransactionIsStored() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val household = household(storage)
        deliver(
            storage, household, household.checking,
            unreadable = listOf(NewUnreadableTransaction(null, Amount(-7_250), usd, "PLANTED-PAYEE", null, UnreadableTransactionReason.MissingDate)),
        )
        val unreadable = storage.fetches.unreadableTransactionsOf(household.checking).single()
        val version = stateVersion(storage)

        assertEquals(unreadable, storage.reviews.unreadableTransaction(unreadable.id))
        assertTrue(storage.reviews.storeUnreadableStatus(unreadable.id, UnreadableTransactionStatus.Deferred))
        assertFalse(storage.reviews.storeUnreadableStatus(unreadable.id + 1000, UnreadableTransactionStatus.Deferred))
        assertNull(storage.reviews.unreadableTransaction(unreadable.id + 1000))
        assertEquals(version, stateVersion(storage))
        storage.close()

        val reopened = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(unreadable.copy(status = UnreadableTransactionStatus.Deferred), reopened.fetches.unreadableTransactionsOf(household.checking).single())
        assertTrue(reopened.reviews.storeUnreadableStatus(unreadable.id, UnreadableTransactionStatus.NeedsReview))
        assertEquals(unreadable, reopened.reviews.unreadableTransaction(unreadable.id))
        reopened.close()
    }
}
