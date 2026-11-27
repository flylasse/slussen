package slussen.core.review

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonStart
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.ChosenBudgetAppTransaction
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.ReviewChoice
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredReviewDecision
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferType
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.status.BudgetAppEntry
import slussen.core.status.NotYetUploaded
import slussen.core.status.OtherLeg
import slussen.core.status.StatusReason
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Review Doubtful Transactions (UC-004) in the core, as far as it needs no Sent Record and no Upload Plan: the main flow,
 * A1-A7, A9 and A11 with BR-001..BR-009, through `ReviewDecisions` on the fakes of `:testing`, with the Transaction
 * Statuses the Comparison's own rules give (UC-003 BR-001, BR-009, BR-010; UC-005 BR-013). A7's lock is built on what the
 * stored copy of the Budget App says (the Bank Transaction's own Import Identifier is held there); A10 only as the choices
 * of BR-001; the send of a Re-upload, the replacement of an open Upload Plan (A8) and a Re-upload decision used up by its
 * send (BR-003) come with the Uploads. Every name, amount and identifier is invented (C-005).
 */
@DisplayName("UC-004: Review Doubtful Transactions")
class UC004ReviewDoubtfulTransactionsTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private val today: LocalDate = LocalDate.of(2026, 10, 15)
    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct13: LocalDate = LocalDate.of(2026, 10, 13)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)
    private var nextId = 6000L

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** The credentials are accepted; Household (USD: Checking, Rewards Card, Reserve Savings), Summer House and Care Fund are served. */
    private suspend fun ready() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    private suspend fun budgetAccount(name: String): Long = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun budget(name: String) = slice.connectionStore.budgets().single { it.name == name }

    /** A Bank Account the Operator added, mapped to the Budget Account [budgetAccountName]. */
    private suspend fun bankAccount(
        name: String,
        budgetAccountName: String,
        type: BankAccountType = BankAccountType.Checking,
        retired: Boolean = false,
    ): Long {
        val id = nextId++
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = slice.feedsId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = name,
            currency = USD,
            type = type,
            startDate = null,
            providerReference = "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = retired,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount(budgetAccountName), null),
        )
        return id
    }

    private suspend fun checking() = bankAccount("Kestrel Checking", "Checking")

    /** The credit card Kestrel Rewards, whose Funded By is Checking, so that a Card Payment is no Guessed Transfer. */
    private suspend fun rewardsCard(): Long {
        val card = bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard)
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        return card
    }

    private fun identifier(reference: String) =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet (S-T-08). */
    private fun stored(
        bankAccountId: Long,
        reference: String?,
        date: LocalDate,
        amount: Amount,
        firstSeenAt: Instant = now.minus(Duration.ofHours(1)),
    ): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = if (reference == null) StableReferenceKind.None else StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = reference?.let(::identifier) ?: "SLS1-${"f".repeat(22)}%04d".format(id % 10000),
            bookedDate = date,
            amount = amount,
            currency = USD,
            payee = "PLANTED-PAYEE-$id",
            memo = "PLANTED-MEMO-$id",
            firstSeenAt = firstSeenAt,
            status = null,
            transferId = null,
        )
        return id
    }

    private fun transaction(id: Long): StoredBankTransaction = slice.importStatusStore.bankTransactions.single { it.id == id }

    private fun status(id: Long): TransactionStatus? = transaction(id).status

    private fun decisionOf(id: Long): StoredReviewDecision? = slice.copyStore.reviewDecisions.singleOrNull { it.storedBankTransaction == id }

    /** An Import by an Other Import Path as the Budget App reports it. */
    private fun otherImport(reference: String, date: LocalDate, amount: Amount, account: String = "ba-checking", payee: String = "Riverside Apothecary", state: ReadState = ReadState.Present) =
        BudgetAppTransactionRead(reference, account, date, amount, payee, null, "YNAB:$reference", EntryKind.Imported, null, state, cleared = true, approved = true)

    /** A transaction Slussen sent, as the Budget App reports it under the Import Identifier of [stableReference]. */
    private fun sent(reference: String, stableReference: String, date: LocalDate, amount: Amount, account: String = "ba-checking", state: ReadState = ReadState.Present) =
        BudgetAppTransactionRead(reference, account, date, amount, "Corner Grocer", null, identifier(stableReference), EntryKind.Imported, null, state, cleared = true, approved = true)

    /** The Budget App answers every read of changes of Household with [transactions]. */
    private fun answerHousehold(vararg transactions: BudgetAppTransactionRead) {
        slice.budgetApp.reader.changesAnswer = { asked ->
            val changed = if (asked.budgetReference == "budget-household") transactions.toList() else emptyList()
            ReadResult.Answered(ChangeSet(changed, ChangeCursor("k-${slice.budgetApp.reader.changeReads.size}")))
        }
    }

    private suspend fun compare() {
        assertTrue(slice.comparison.readAgain() is ComparisonStart.Ran)
    }

    private suspend fun counts(budgetName: String = "Household"): NotYetUploaded = slice.status.workspace(budget(budgetName).id).summary.notYetUploaded

    private suspend fun sheet(id: Long): ReviewSheet = slice.review.sheet(id) ?: error("The Bank Transaction $id has no Review Decision sheet.")

    private fun journaledSince(index: Int): List<JournalEntry> = slice.journalRecorder.entries.drop(index)

    /** Kestrel Checking with one Bank Transaction that resembles the Imports by an Other Import Path [imports]; returns its id. */
    private suspend fun possibleImport(vararg imports: BudgetAppTransactionRead): Long {
        ready()
        val checking = checking()
        val tx = stored(checking, "TX-1", oct14, amount(-18, 600))
        answerHousehold(*imports)
        compare()
        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(tx))
        return tx
    }

    private val sameDay = otherImport("ynab-1", oct13, amount(-18, 600))
    private val laterDay = otherImport("ynab-2", oct14.plusDays(4), amount(-18, 600), payee = "RIVERSIDE APOTH")

    // ---- Main Success Scenario --------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002"])
    @DisplayName("Steps 1-4, BR-001, BR-002: a Possible Import By Other Path is shown with its date, payee, amount, Bank Account and the reason it is doubtful, with the choices Already Present, Import Anyway and Defer and the Imports by an Other Import Path that resemble it, each with its date, payee, amount and Budget Account; nothing is stored by looking")
    fun possibleImportIsShownWithItsChoicesAndTheResemblingImports() = runTest {
        val tx = possibleImport(sameDay, laterDay, otherImport("ynab-3", oct13, amount(-18, 600), account = "ba-reserve"), otherImport("ynab-4", oct13, amount(-18)))
        val version = slice.stateVersion.value

        val shown = sheet(tx)

        assertEquals("Kestrel Checking", shown.bankAccountName)
        assertEquals(oct14, shown.bookedDate)
        assertEquals(amount(-18, 600), shown.amount)
        assertEquals(USD, shown.currency)
        assertEquals(transaction(tx).payee, shown.payee)
        assertEquals(TransactionStatus.PossibleImportByOtherPath, shown.status)
        assertEquals(TransactionStatus.PossibleImportByOtherPath, shown.statusWithoutDecision)
        assertEquals(listOf(ReviewChoice.AlreadyPresent, ReviewChoice.ImportAnyway, ReviewChoice.Defer), shown.choices)
        // Only the Imports of its own Budget Account with its amount resemble it (UC-003 BR-007).
        val resembling = listOf(
            ResemblingImport("ynab-1", oct13, "Riverside Apothecary", amount(-18, 600), "Checking"),
            ResemblingImport("ynab-2", oct14.plusDays(4), "RIVERSIDE APOTH", amount(-18, 600), "Checking"),
        )
        assertEquals(resembling, shown.resembling)
        assertEquals(
            StatusReason.ResemblesOtherImport(resembling.map { BudgetAppEntry(it.date, it.amount, it.payee, it.budgetAccountName) }, matchingDateWindowDays = 10),
            shown.why,
        )
        assertNull(shown.decision)
        assertFalse(shown.locked)
        assertFalse(shown.canClear)
        assertNull(shown.transfer)
        assertNull(shown.undecidable)
        assertEquals(version, slice.stateVersion.value)
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-005", "BR-006"])
    @DisplayName("Steps 3-8, BR-002, BR-005, BR-006: Already Present with the Budget App Transaction the Operator chose among several is stored with its identifier, its Budget Account and its amount; the Bank Transaction is Already In Budget App and waits no more; the Decision Journal records the decision with the status Slussen had given, its Bank Account and Import Identifier and no payee or memo; state_version is raised")
    fun alreadyPresentStoresTheChosenTransaction() = runTest {
        val tx = possibleImport(sameDay, laterDay)
        assertEquals(NotYetUploaded(0, 1), counts())
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        // Step 4: several resemble it, so naming none stores nothing and gives the list to choose from.
        val asked = slice.review.decide(tx, ReviewChoice.AlreadyPresent)
        assertEquals(listOf("ynab-1", "ynab-2"), (asked as ReviewOutcome.ChooseTransaction).resembling.map { it.budgetAppReference })
        assertNull(decisionOf(tx))
        assertEquals(version, slice.stateVersion.value)

        val outcome = slice.review.decide(tx, ReviewChoice.AlreadyPresent, chosenBudgetAppReference = "ynab-2")

        assertEquals(ReviewOutcome.Stored(ReviewChoice.AlreadyPresent, TransactionStatus.AlreadyInBudgetApp), outcome)
        assertEquals(
            StoredReviewDecision(tx, ReviewChoice.AlreadyPresent, ChosenBudgetAppTransaction("ynab-2", budgetAccount("Checking"), amount(-18, 600)), now),
            decisionOf(tx),
        )
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(tx))
        assertEquals(NotYetUploaded(0, 0), counts())
        assertTrue(slice.stateVersion.value > version)
        val entry = journaledSince(journaled).single()
        assertEquals(JournalEventKind.OperatorDecision, entry.eventKind)
        assertEquals(identifier("TX-1"), entry.importIdentifier)
        assertEquals("Kestrel Checking", entry.bankAccountName)
        assertEquals(
            "Review Decision Already Present, naming the Budget App Transaction booked 2026-10-18, -18.600 in Budget Account Checking; " +
                "Transaction Status Slussen had given: Possible Import By Other Path; Transaction Status now: Already In Budget App",
            entry.details,
        )
        assertFalse("PLANTED" in entry.toString() || "RIVERSIDE" in entry.toString())
        // The decision is shown with the transaction it names (A6 step 1).
        val shown = sheet(tx)
        assertEquals(DecisionShown(ReviewChoice.AlreadyPresent, now, BudgetAppEntry(oct14.plusDays(4), amount(-18, 600), "RIVERSIDE APOTH", "Checking"), noLongerHolds = null), shown.decision)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: Already Present names one of the Imports by an Other Import Path that resemble the Bank Transaction: a Budget App Transaction that does not resemble it, or is no such Import, is not stored and the list is given again")
    fun alreadyPresentNamesOnlyAResemblingImport() = runTest {
        val tx = possibleImport(sameDay, laterDay, otherImport("ynab-3", oct13, amount(-18)), sent("ynab-9", "TX-OTHER", oct13, amount(-18, 600)))

        val notResembling = slice.review.decide(tx, ReviewChoice.AlreadyPresent, chosenBudgetAppReference = "ynab-3")
        val slussensOwn = slice.review.decide(tx, ReviewChoice.AlreadyPresent, chosenBudgetAppReference = "ynab-9")
        val unknown = slice.review.decide(tx, ReviewChoice.AlreadyPresent, chosenBudgetAppReference = "ynab-0")

        listOf(notResembling, slussensOwn, unknown).forEach { outcome ->
            assertEquals(listOf("ynab-1", "ynab-2"), (outcome as ReviewOutcome.ChooseTransaction).resembling.map { it.budgetAppReference })
        }
        assertNull(decisionOf(tx))
        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(tx))
    }

    // ---- Alternative flows ------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-004", scenario = "A1: Import Anyway", businessRules = ["BR-006"])
    @DisplayName("A1, BR-006: Import Anyway is stored without a chosen transaction and gives the status New, so that the Bank Transaction is an Eligible Transaction and waits no more")
    fun importAnywayMakesItNewAndEligible() = runTest {
        val tx = possibleImport(sameDay)
        assertEquals(NotYetUploaded(0, 1), counts())

        val outcome = slice.review.decide(tx, ReviewChoice.ImportAnyway)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.New), outcome)
        assertEquals(StoredReviewDecision(tx, ReviewChoice.ImportAnyway, chosen = null, decidedAt = now), decisionOf(tx))
        assertEquals(TransactionStatus.New, status(tx))
        assertEquals(NotYetUploaded(1, 0), counts())
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A2: Defer", businessRules = ["BR-006"])
    @DisplayName("A2, BR-006, UC-003 BR-010: Defer gives the status Deferred: the Bank Transaction is held back, neither eligible nor waiting, also across a later Comparison in which the resembling Import has disappeared")
    fun deferHoldsItBack() = runTest {
        val tx = possibleImport(sameDay)

        val outcome = slice.review.decide(tx, ReviewChoice.Defer)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.Defer, TransactionStatus.Deferred), outcome)
        assertEquals(TransactionStatus.Deferred, status(tx))
        assertEquals(NotYetUploaded(0, 0), counts())
        answerHousehold(otherImport("ynab-1", oct13, amount(-18, 600), state = ReadState.Deleted))
        compare()
        assertEquals(TransactionStatus.Deferred, status(tx))
        assertEquals(ReviewChoice.Defer, decisionOf(tx)?.choice)
        assertEquals(NotYetUploaded(0, 0), counts())
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A3: Exactly One Resembling Transaction", businessRules = ["BR-002"])
    @DisplayName("A3: when only one Import by an Other Import Path resembles the Bank Transaction, Already Present takes it as the chosen one without asking")
    fun theOnlyResemblingTransactionIsTaken() = runTest {
        val tx = possibleImport(sameDay)

        val outcome = slice.review.decide(tx, ReviewChoice.AlreadyPresent)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.AlreadyPresent, TransactionStatus.AlreadyInBudgetApp), outcome)
        assertEquals(ChosenBudgetAppTransaction("ynab-1", budgetAccount("Checking"), amount(-18, 600)), decisionOf(tx)?.chosen)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A4: Transaction Needs Review or Its Transfer Counterpart Changed", businessRules = ["BR-001"])
    @DisplayName("A4, BR-001: Needs Review, for a missing Stable Reference or for a Slussen Import in an unsupported form, is shown with its reason and offers Import Anyway and Defer only: Already Present and Re-upload are refused with nothing stored; Transfer Counterpart Changed offers the same two")
    fun needsReviewOffersImportAnywayAndDefer() = runTest {
        ready()
        val checking = checking()
        val noReference = stored(checking, null, oct14, amount(-12))
        val resemblesOwn = stored(checking, "TX-2", oct13, amount(-29))
        answerHousehold(
            BudgetAppTransactionRead("ynab-5", "ba-checking", oct12, amount(-29), "Streamline Gym", null, "SLS9-unsupported", EntryKind.Imported, null, ReadState.Present, cleared = true, approved = true),
            // Resembles the one without a Stable Reference, which Needs Review all the same (UC-003 BR-001).
            otherImport("ynab-6", oct14, amount(-12)),
        )
        compare()
        val both = listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer)

        assertEquals(TransactionStatus.NeedsReview, sheet(noReference).status)
        assertEquals(both, sheet(noReference).choices)
        assertEquals(StatusReason.NoStableReference(identical = 0), sheet(noReference).why)
        assertEquals(both, sheet(resemblesOwn).choices)
        assertEquals(StatusReason.ResemblesUnsupportedSlussenImport(listOf(BudgetAppEntry(oct12, amount(-29), "Streamline Gym", "Checking"))), sheet(resemblesOwn).why)
        assertEquals(ReviewOutcome.NotOffered(both), slice.review.decide(noReference, ReviewChoice.AlreadyPresent, "ynab-6"))
        assertEquals(ReviewOutcome.NotOffered(both), slice.review.decide(resemblesOwn, ReviewChoice.ReUpload))
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.New), slice.review.decide(noReference, ReviewChoice.ImportAnyway))
        assertEquals(ReviewOutcome.Stored(ReviewChoice.Defer, TransactionStatus.Deferred), slice.review.decide(resemblesOwn, ReviewChoice.Defer))
        assertEquals(NotYetUploaded(1, 0), counts())
        // No Comparison gives Transfer Counterpart Changed before Uploads exist; its choices are the rule's.
        assertEquals(both, ReviewRules.choicesOf(TransactionStatus.TransferCounterpartChanged, noLongerHolds = false, resembles = false, budgetAppRefusesReusedImportIdentifier = true))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A5: Re-upload a Deleted Transaction", businessRules = ["BR-001", "BR-003", "BR-006"])
    @DisplayName("A5, BR-001, BR-003, BR-006 (W2-0 item 5, as far as it is reached without Sent Records): Deleted In Budget App and Merged In Budget App offer Re-upload only, which is off until the Operator chooses it; chosen, the Bank Transaction is New and eligible inside its Upload Window and Too Old outside it; cleared, it is Deleted In Budget App again and stays so at every later Comparison until a new choice")
    fun reUploadOfADeletedOrMergedTransaction() = runTest {
        ready()
        val checking = checking()
        val deleted = stored(checking, "TX-1", oct14, amount(-64, 180))
        val merged = stored(checking, "TX-2", oct14, amount(-20))
        val deletedLongAgo = stored(checking, "TX-3", today.minusDays(40), amount(-7))
        answerHousehold(
            sent("ynab-1", "TX-1", oct14, amount(-64, 180), state = ReadState.Deleted),
            sent("ynab-2", "TX-2", oct14, amount(-20), state = ReadState.Merged("ynab-7")),
            sent("ynab-3", "TX-3", today.minusDays(40), amount(-7), state = ReadState.Deleted),
        )
        compare()
        assertEquals(TransactionStatus.DeletedInBudgetApp, status(deleted))
        assertEquals(TransactionStatus.MergedInBudgetApp, status(merged))

        // Step 1: Re-upload is the only choice, and it is off: no decision, nothing eligible.
        listOf(deleted, merged, deletedLongAgo).forEach { tx ->
            assertEquals(listOf(ReviewChoice.ReUpload), sheet(tx).choices)
            assertNull(sheet(tx).decision)
            assertFalse(sheet(tx).locked)
        }
        assertEquals(ReviewOutcome.NotOffered(listOf(ReviewChoice.ReUpload)), slice.review.decide(deleted, ReviewChoice.ImportAnyway))
        assertEquals(NotYetUploaded(0, 0), counts())

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ReUpload, TransactionStatus.New), slice.review.decide(deleted, ReviewChoice.ReUpload))
        assertEquals(ReviewOutcome.Stored(ReviewChoice.ReUpload, TransactionStatus.New), slice.review.decide(merged, ReviewChoice.ReUpload))
        // BR-003, UC-003 BR-011: a Re-upload is possible only inside the Upload Window.
        assertEquals(ReviewOutcome.Stored(ReviewChoice.ReUpload, TransactionStatus.TooOld), slice.review.decide(deletedLongAgo, ReviewChoice.ReUpload))
        assertEquals(NotYetUploaded(2, 0), counts())
        compare()
        assertEquals(TransactionStatus.New, status(deleted))
        assertEquals(TransactionStatus.New, status(merged))
        assertEquals(TransactionStatus.TooOld, status(deletedLongAgo))

        // Switched off again: only a new choice of the Operator sends it once more.
        assertEquals(ReviewOutcome.Stored(null, TransactionStatus.DeletedInBudgetApp), slice.review.clear(deleted))
        compare()
        assertEquals(TransactionStatus.DeletedInBudgetApp, status(deleted))
        assertEquals(NotYetUploaded(1, 0), counts())
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A5: Re-upload a Deleted Transaction", businessRules = ["BR-001", "BR-002"])
    @DisplayName("A5, BR-001, BR-002, UC-003 A4: Decision No Longer Holds is shown with why the decision does not hold and offers Already Present, Import Anyway, Defer and Re-upload, Already Present only while an Import by an Other Import Path still resembles the Bank Transaction; Re-upload of a Bank Transaction that was never sent makes it New")
    fun decisionNoLongerHoldsOffersItsFourChoices() = runTest {
        ready()
        val checking = checking()
        val stillResembled = stored(checking, "TX-1", oct14, amount(-18, 600))
        val resembledNoMore = stored(checking, "TX-2", oct14, amount(-22, 150))
        answerHousehold(sameDay, laterDay, otherImport("ynab-3", oct13, amount(-22, 150)))
        compare()
        slice.review.decide(stillResembled, ReviewChoice.AlreadyPresent, "ynab-1")
        slice.review.decide(resembledNoMore, ReviewChoice.AlreadyPresent)
        answerHousehold(otherImport("ynab-1", oct13, amount(-18, 600), state = ReadState.Deleted), otherImport("ynab-3", oct13, amount(-22, 150), state = ReadState.Deleted))
        compare()
        assertEquals(TransactionStatus.DecisionNoLongerHolds, status(stillResembled))
        assertEquals(TransactionStatus.DecisionNoLongerHolds, status(resembledNoMore))
        assertEquals(NotYetUploaded(0, 2), counts())

        val first = sheet(stillResembled)
        assertEquals(listOf(ReviewChoice.AlreadyPresent, ReviewChoice.ImportAnyway, ReviewChoice.Defer, ReviewChoice.ReUpload), first.choices)
        assertEquals(listOf("ynab-2"), first.resembling.map { it.budgetAppReference })
        // The chosen Budget App Transaction is still named, as the stored copy keeps it, with why the decision does not hold.
        assertEquals(
            DecisionShown(ReviewChoice.AlreadyPresent, now, chosen = BudgetAppEntry(oct13, amount(-18, 600), "Riverside Apothecary", "Checking"), noLongerHolds = NoLongerHolds.NoLongerThere),
            first.decision,
        )
        assertTrue(first.canClear)
        val second = sheet(resembledNoMore)
        assertEquals(listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer, ReviewChoice.ReUpload), second.choices)
        assertEquals(ReviewOutcome.NotOffered(second.choices), slice.review.decide(resembledNoMore, ReviewChoice.AlreadyPresent, "ynab-3"))
        assertEquals(TransactionStatus.DecisionNoLongerHolds, status(resembledNoMore))

        // Choosing another Budget App Transaction holds again; Re-upload of one never sent is a new transaction.
        assertEquals(ReviewOutcome.Stored(ReviewChoice.AlreadyPresent, TransactionStatus.AlreadyInBudgetApp), slice.review.decide(stillResembled, ReviewChoice.AlreadyPresent))
        assertEquals("ynab-2", decisionOf(stillResembled)?.chosen?.budgetAppReference)
        assertEquals(ReviewOutcome.Stored(ReviewChoice.ReUpload, TransactionStatus.New), slice.review.decide(resembledNoMore, ReviewChoice.ReUpload))
        assertEquals(StoredReviewDecision(resembledNoMore, ReviewChoice.ReUpload, chosen = null, decidedAt = now), decisionOf(resembledNoMore))
        assertEquals(NotYetUploaded(1, 0), counts())
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-001", "BR-004", "BR-006"])
    @DisplayName("A6, BR-001, BR-004, BR-006: a Bank Transaction with a Review Decision shows it and offers the choices of the status it has without the decision, and clearing; the decision is replaced any number of times, one Review Decision per Bank Transaction, and cleared, which gives the status the Comparison gives without a decision")
    fun anEarlierDecisionIsChangedAndCleared() = runTest {
        val tx = possibleImport(sameDay)
        slice.review.decide(tx, ReviewChoice.Defer)
        slice.clock.advance(Duration.ofMinutes(7))

        val shown = sheet(tx)
        assertEquals(DecisionShown(ReviewChoice.Defer, now, chosen = null, noLongerHolds = null), shown.decision)
        assertEquals(TransactionStatus.Deferred, shown.status)
        assertEquals(TransactionStatus.PossibleImportByOtherPath, shown.statusWithoutDecision)
        assertEquals(listOf(ReviewChoice.AlreadyPresent, ReviewChoice.ImportAnyway, ReviewChoice.Defer), shown.choices)
        assertTrue(shown.canClear)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.New), slice.review.decide(tx, ReviewChoice.ImportAnyway))
        assertEquals(listOf(StoredReviewDecision(tx, ReviewChoice.ImportAnyway, null, now.plus(Duration.ofMinutes(7)))), slice.copyStore.reviewDecisions.toList())
        assertEquals(ReviewOutcome.Stored(ReviewChoice.AlreadyPresent, TransactionStatus.AlreadyInBudgetApp), slice.review.decide(tx, ReviewChoice.AlreadyPresent))
        assertEquals(ReviewOutcome.Stored(ReviewChoice.Defer, TransactionStatus.Deferred), slice.review.decide(tx, ReviewChoice.Defer))
        assertEquals(1, slice.copyStore.reviewDecisions.size)
        assertNull(decisionOf(tx)?.chosen)

        val version = slice.stateVersion.value
        assertEquals(ReviewOutcome.Stored(null, TransactionStatus.PossibleImportByOtherPath), slice.review.clear(tx))
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(tx))
        assertEquals(NotYetUploaded(0, 1), counts())
        assertTrue(slice.stateVersion.value > version)
        // Clearing again finds nothing to clear and stores nothing.
        val cleared = slice.stateVersion.value
        assertEquals(ReviewOutcome.NothingToClear, slice.review.clear(tx))
        assertEquals(cleared, slice.stateVersion.value)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-001", "BR-006"])
    @DisplayName("A6, BR-001, BR-006 (S-B-07): the status without the decision is worked out from the stored copy, without reading the Budget App: a Deferred Bank Transaction that nothing resembles any more offers clearing only, and clearing makes it New")
    fun deferredOffersClearingOnlyWhenNothingIsDoubtfulAnyMore() = runTest {
        val tx = possibleImport(sameDay)
        slice.review.decide(tx, ReviewChoice.Defer)
        answerHousehold(otherImport("ynab-1", oct13, amount(-18, 600), state = ReadState.Deleted))
        compare()
        val reads = slice.budgetApp.reader.changeReads.size

        val shown = sheet(tx)

        assertEquals(TransactionStatus.Deferred, shown.status)
        assertEquals(TransactionStatus.New, shown.statusWithoutDecision)
        assertEquals(emptyList<ReviewChoice>(), shown.choices)
        assertTrue(shown.canClear)
        assertEquals(ReviewOutcome.NotOffered(emptyList()), slice.review.decide(tx, ReviewChoice.ImportAnyway))
        assertEquals(ReviewChoice.Defer, decisionOf(tx)?.choice)
        assertEquals(ReviewOutcome.Stored(null, TransactionStatus.New), slice.review.clear(tx))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(reads, slice.budgetApp.reader.changeReads.size)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A7: Transaction Already Uploaded", businessRules = ["BR-004"])
    @DisplayName("A7, BR-004: while the Budget App holds the Bank Transaction under Slussen's own Import Identifier, it is Already In Budget App and its Review Decision is locked: no choice is offered, and neither a decision nor clearing is stored; one without a decision is locked the same way")
    fun anUploadedTransactionIsLocked() = runTest {
        ready()
        val checking = checking()
        val decidedBefore = stored(checking, "TX-1", oct14, amount(-18, 600))
        val undecided = stored(checking, "TX-2", oct14, amount(-20))
        answerHousehold(sameDay)
        compare()
        slice.review.decide(decidedBefore, ReviewChoice.ImportAnyway)
        // The Upload wrote both: the Budget App holds their own Import Identifiers.
        answerHousehold(sent("ynab-8", "TX-1", oct14, amount(-18, 600)), sent("ynab-9", "TX-2", oct14, amount(-20)))
        compare()
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(decidedBefore))
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        listOf(decidedBefore, undecided).forEach { tx ->
            val shown = sheet(tx)
            assertTrue(shown.locked)
            assertEquals(emptyList<ReviewChoice>(), shown.choices)
            assertFalse(shown.canClear)
            ReviewChoice.entries.forEach { choice -> assertEquals(ReviewOutcome.Locked, slice.review.decide(tx, choice, "ynab-1")) }
            assertEquals(ReviewOutcome.Locked, slice.review.clear(tx))
        }
        assertEquals(ReviewChoice.ImportAnyway, sheet(decidedBefore).decision?.choice)
        assertEquals(listOf(StoredReviewDecision(decidedBefore, ReviewChoice.ImportAnyway, null, now)), slice.copyStore.reviewDecisions.toList())
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        // The rule also locks a Transfer Leg that is Arrived As Transfer Counterpart, which no Comparison gives before Uploads exist.
        assertTrue(ReviewRules.locked(TransactionStatus.ArrivedAsTransferCounterpart))
        assertFalse(ReviewRules.locked(TransactionStatus.DeletedInBudgetApp))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A9: Unreadable Transaction", businessRules = ["BR-007"], requirements = ["NFR-002"])
    @DisplayName("A9, BR-007, NFR-002: an Unreadable Transaction is shown with whatever fields could be read, its Bank Account and the reason, with Defer only; deferred, it has the status Deferred and no longer waits for a decision, is never eligible, and the decision is journaled; clearing the decision returns it to Needs Review")
    fun anUnreadableTransactionIsDeferredAndNeverUploaded() = runTest {
        ready()
        val checking = checking()
        val unreadable = nextId++
        slice.importStatusStore.unreadableTransactions += StoredUnreadableTransaction(
            unreadable, checking, bookedDate = null, amount = amount(-7, 250), currency = USD, payee = "PLANTED-PAYEE-KIOSK", memo = null,
            reason = "date missing", firstSeenAt = now.minus(Duration.ofHours(1)), status = UnreadableTransactionStatus.NeedsReview,
        )
        assertEquals(NotYetUploaded(0, 1), counts())
        val journaled = slice.journalRecorder.entries.size
        val version = slice.stateVersion.value

        val shown = slice.review.unreadableSheet(unreadable)
        assertEquals(
            UnreadableSheet(
                unreadable, "Kestrel Checking", bookedDate = null, amount = amount(-7, 250), currency = USD, payee = "PLANTED-PAYEE-KIOSK", memo = null,
                reason = "date missing", firstSeenAt = now.minus(Duration.ofHours(1)), status = UnreadableTransactionStatus.NeedsReview, canDefer = true, canClear = false,
            ),
            shown,
        )

        assertEquals(UnreadableOutcome.Stored(UnreadableTransactionStatus.Deferred), slice.review.deferUnreadable(unreadable))

        assertEquals(UnreadableTransactionStatus.Deferred, slice.importStatusStore.unreadableTransactions.single().status)
        assertEquals(NotYetUploaded(0, 0), counts())
        val entry = journaledSince(journaled).single()
        assertEquals(JournalEventKind.OperatorDecision, entry.eventKind)
        assertEquals("Unreadable Transaction of Bank Account Kestrel Checking (date missing): Review Decision Defer; Transaction Status Slussen had given: Needs Review; Transaction Status now: Deferred", entry.details)
        assertNull(entry.importIdentifier)
        assertFalse("PLANTED" in entry.toString())
        val deferred = slice.review.unreadableSheet(unreadable)
        assertEquals(false to true, deferred?.let { it.canDefer to it.canClear })
        assertEquals(UnreadableOutcome.Unchanged(UnreadableTransactionStatus.Deferred), slice.review.deferUnreadable(unreadable))
        assertEquals(1, journaledSince(journaled).size)

        assertEquals(UnreadableOutcome.Stored(UnreadableTransactionStatus.NeedsReview), slice.review.clearUnreadable(unreadable))
        assertEquals(UnreadableTransactionStatus.NeedsReview, slice.importStatusStore.unreadableTransactions.single().status)
        assertEquals(NotYetUploaded(0, 1), counts())
        assertEquals("Unreadable Transaction of Bank Account Kestrel Checking (date missing): Review Decision Defer cleared; Transaction Status now: Needs Review", journaledSince(journaled).last().details)
        // It is no Bank Transaction: no Review Decision is stored for it, nothing of it is in an Upload Plan, and no plan depends on it.
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertEquals(version, slice.stateVersion.value)
        assertEquals(UnreadableOutcome.NotFound, slice.review.deferUnreadable(unreadable + 1))
        assertNull(slice.review.unreadableSheet(unreadable + 1))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A10: Unknown Outcome Waits for a Decision", businessRules = ["BR-001", "BR-004", "BR-008"])
    @DisplayName("A10, BR-001, BR-004 (W2-0 item 1c): Outcome Unknown offers Import Anyway and Defer when the Budget App does not refuse a reused Import Identifier, so that it waits for a decision, and no choice when it refuses one, and it is never locked; no Comparison gives the status before Uploads exist, so this is the rule alone")
    fun outcomeUnknownThatWaitsOffersImportAnywayAndDeferAndIsNotLocked() {
        val waits = ReviewRules.choicesOf(TransactionStatus.OutcomeUnknown, noLongerHolds = false, resembles = false, budgetAppRefusesReusedImportIdentifier = false)
        val resentOnItsOwn = ReviewRules.choicesOf(TransactionStatus.OutcomeUnknown, noLongerHolds = false, resembles = false, budgetAppRefusesReusedImportIdentifier = true)

        assertEquals(listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer), waits)
        assertEquals(emptyList<ReviewChoice>(), resentOnItsOwn)
        assertFalse(ReviewRules.locked(TransactionStatus.OutcomeUnknown))
    }

    /** Kestrel Checking paid 250.00 to Kestrel Rewards: a Card Payment whose outflow has no Stable Reference. Returns (outflow, inflow). */
    private suspend fun cardPaymentWithADoubtfulLeg(): Pair<Long, Long> {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, null, oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        answerHousehold(otherImport("ynab-1", oct12, amount(-250)))
        compare()
        assertEquals(TransactionStatus.NeedsReview, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(NotYetUploaded(0, 1), counts())
        return paid to received
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A11: Doubtful Transfer Leg", businessRules = ["BR-009"])
    @DisplayName("A11, BR-009: a doubtful Transfer Leg is shown with its Transfer and the other Transfer Leg, and its Review Decision decides the whole Transfer: Import Anyway lets it rejoin, so that the Transfer is eligible as one linked transfer; Defer holds both Transfer Legs back as Deferred; clearing makes the Transfer wait again")
    fun aDecisionOnATransferLegDecidesItsTransfer() = runTest {
        val (paid, received) = cardPaymentWithADoubtfulLeg()

        val shown = sheet(paid)
        assertEquals(
            TransferOfLeg(
                TransferType.CardPayment, "Checking", "Rewards Card", amount(250),
                OtherLeg("Kestrel Rewards", oct13, amount(250), transaction(received).payee), TransferDecision.Proposed, decidesWholeTransfer = true,
            ),
            shown.transfer,
        )
        assertEquals(listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer), shown.choices)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.TransferLeg), slice.review.decide(paid, ReviewChoice.ImportAnyway))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        // One Eligible Transaction: the Transfer, written through its outflow side (UC-006 BR-009).
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(TransactionStatus.NeedsReview, sheet(paid).statusWithoutDecision)

        val journaled = slice.journalRecorder.entries.size
        assertEquals(ReviewOutcome.Stored(ReviewChoice.Defer, TransactionStatus.Deferred), slice.review.decide(paid, ReviewChoice.Defer))
        assertEquals(TransactionStatus.Deferred, status(received))
        assertEquals(NotYetUploaded(0, 0), counts())
        // The decision is journaled for the Transfer Leg it is about, and the changed status of the other Transfer Leg as such (UC-011 BR-001).
        val entries = journaledSince(journaled)
        assertEquals(
            listOf(
                JournalEventKind.OperatorDecision to "Review Decision changed from Import Anyway to Defer; Transaction Status Slussen had given: Needs Review; Transaction Status now: Deferred",
                JournalEventKind.StatusChanged to "Transaction Status changed from Transfer Leg to Deferred",
            ),
            entries.map { it.eventKind to it.details },
        )
        assertEquals(listOf(transaction(paid).importIdentifier, identifier("TX-2")), entries.map { it.importIdentifier })
        assertEquals(listOf("Kestrel Checking", "Kestrel Rewards"), entries.map { it.bankAccountName })
        // The other Transfer Leg is not doubtful itself: its sheet offers nothing, and the decision stays on the doubtful one.
        assertEquals(emptyList<ReviewChoice>(), sheet(received).choices)
        assertFalse(sheet(received).canClear)

        assertEquals(ReviewOutcome.Stored(null, TransactionStatus.NeedsReview), slice.review.clear(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(NotYetUploaded(0, 1), counts())
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A11: Doubtful Transfer Leg", businessRules = ["BR-009"])
    @DisplayName("A11, BR-009: Already Present on a Transfer Leg counts the whole Transfer as already in the Budget App: both Transfer Legs are Already In Budget App and neither is eligible")
    fun alreadyPresentOnATransferLegCountsTheWholeTransfer() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-99))
        val received = stored(card, "TX-2", oct12, amount(99))
        answerHousehold(otherImport("ynab-1", oct12, amount(99), account = "ba-rewards", payee = "Card payment"))
        compare()
        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(received))
        assertEquals(TransactionStatus.TransferLeg, status(paid))

        val outcome = slice.review.decide(received, ReviewChoice.AlreadyPresent)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.AlreadyPresent, TransactionStatus.AlreadyInBudgetApp), outcome)
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(paid))
        assertEquals(NotYetUploaded(0, 0), counts())
        // BR-004: the other Transfer Leg is Already In Budget App through that decision, not because Slussen sent it: nothing is locked.
        assertFalse(sheet(paid).locked)
        assertFalse(sheet(received).locked)
        assertTrue(sheet(received).canClear)
    }

    // ---- The stuck cases of the review of the Wave 2 specification (W2-0 item 1) -------------------------------------

    @Test
    @UseCase(id = "UC-004", scenario = "A5: Re-upload a Deleted Transaction", businessRules = ["BR-004", "BR-009"])
    @DisplayName("BR-004, BR-009 (W2-0 item 1a): a Transfer Leg that Slussen sent and that is Deleted In Budget App is not locked and offers Re-upload like any other Bank Transaction; re-uploaded, it is New and eligible as a transaction of its own Budget Account, and its Transfer is in no Upload Plan as a linked transfer")
    fun aSentTransferLegDeletedInTheBudgetAppIsReUploadedAsItsOwnTransaction() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        answerHousehold(sent("ynab-1", "TX-1", oct12, amount(-250), state = ReadState.Deleted))
        compare()
        assertEquals(TransactionStatus.DeletedInBudgetApp, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertNotNull(transaction(paid).transferId)

        val shown = sheet(paid)
        assertFalse(shown.locked)
        assertEquals(listOf(ReviewChoice.ReUpload), shown.choices)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ReUpload, TransactionStatus.New), slice.review.decide(paid, ReviewChoice.ReUpload))

        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        // Exactly one Eligible Transaction, the re-uploaded one in Kestrel Checking: the Transfer itself is kept out.
        val eligible = slice.importStatusStore.transactionCounts().associate { it.bankAccountId to it.eligible }
        assertEquals(mapOf(checking to 1, card to 0), eligible)
        compare()
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-004", "BR-009"])
    @DisplayName("BR-004, BR-009 (W2-0 item 1b): after Split, the Transfer Leg that Slussen has not sent is never locked, also when the other Transfer Leg was uploaded: it is decided as an ordinary Bank Transaction, and Import Anyway makes it New and eligible on its own")
    fun aSplitTransferLegThatWasNotSentIsNeverLocked() = runTest {
        val (paid, received) = cardPaymentWithADoubtfulLeg()
        // The Operator split the Transfer (UC-005 A1), and the Upload wrote the inflow as an ordinary transaction.
        val split = slice.copyStore.transfers.single().copy(decision = TransferDecision.Split, decidedAt = now)
        slice.copyStore.transfers[0] = split
        answerHousehold(sent("ynab-2", "TX-2", oct13, amount(250), account = "ba-rewards"))
        compare()
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(received))
        assertEquals(TransactionStatus.NeedsReview, status(paid))

        val shown = sheet(paid)
        assertFalse(shown.locked)
        assertEquals(listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer), shown.choices)
        assertEquals(false, shown.transfer?.decidesWholeTransfer)
        assertEquals(TransferDecision.Split, shown.transfer?.decision)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.New), slice.review.decide(paid, ReviewChoice.ImportAnyway))

        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(received))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(listOf(split), slice.copyStore.transfers.toList())
        // The uploaded Transfer Leg itself is locked, as any uploaded Bank Transaction is.
        assertTrue(sheet(received).locked)
    }

    // ---- Business rules ---------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-005"])
    @DisplayName("BR-005: every change and clearing is journaled with the Transaction Status Slussen had given, which is the status without a Review Decision, or Decision No Longer Holds when the decision that is replaced or cleared did not hold any more")
    fun everyChangeIsJournaledWithTheStatusSlussenHadGiven() = runTest {
        val tx = possibleImport(sameDay)
        val journaled = slice.journalRecorder.entries.size
        slice.review.decide(tx, ReviewChoice.AlreadyPresent)
        slice.review.decide(tx, ReviewChoice.Defer)
        slice.review.decide(tx, ReviewChoice.AlreadyPresent)
        val decisions = journaledSince(journaled).map { it.details }
        answerHousehold(otherImport("ynab-1", oct13, amount(-18, 601)), otherImport("ynab-2", oct13, amount(-18, 600)))
        compare()
        assertEquals(TransactionStatus.DecisionNoLongerHolds, status(tx))
        assertEquals(NoLongerHolds.AnotherAmount, sheet(tx).decision?.noLongerHolds)
        val afterComparison = slice.journalRecorder.entries.size

        slice.review.clear(tx)

        val named = ", naming the Budget App Transaction booked 2026-10-13, -18.600 in Budget Account Checking"
        val had = "Transaction Status Slussen had given: Possible Import By Other Path"
        assertEquals(
            listOf(
                "Review Decision Already Present$named; $had; Transaction Status now: Already In Budget App",
                "Review Decision changed from Already Present to Defer; $had; Transaction Status now: Deferred",
                "Review Decision changed from Defer to Already Present$named; $had; Transaction Status now: Already In Budget App",
            ),
            decisions,
        )
        val cleared = journaledSince(afterComparison).single()
        assertEquals(JournalEventKind.OperatorDecision, cleared.eventKind)
        assertEquals(
            "Review Decision Already Present cleared; Transaction Status Slussen had given: Decision No Longer Holds; Transaction Status now: Possible Import By Other Path",
            cleared.details,
        )
        assertEquals(identifier("TX-1"), cleared.importIdentifier)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("BR-005, postcondition: while the Decision Journal is switched off, the Review Decision is stored all the same and nothing is recorded")
    fun aDecisionIsStoredWhileTheDecisionJournalIsOff() = runTest {
        val tx = possibleImport(sameDay)
        val journaled = slice.journalRecorder.entries.size
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.Defer, TransactionStatus.Deferred), slice.review.decide(tx, ReviewChoice.Defer))

        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertEquals(ReviewChoice.Defer, decisionOf(tx)?.choice)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-006"])
    @DisplayName("BR-006: a Review Decision never sets aside a rule that stands before it in the order of the Comparison: a decided Bank Transaction that leaves the Upload Window is Too Old, keeps its Review Decision, shows it and offers clearing only; when the Operator widens the Upload Window the decision is followed again")
    fun aDecisionNeverSetsAsideAnEarlierRule() = runTest {
        ready()
        val checking = checking()
        val tx = stored(checking, "TX-1", today.minusDays(6), amount(-18, 600))
        answerHousehold(otherImport("ynab-1", today.minusDays(6), amount(-18, 600)))
        compare()
        slice.review.decide(tx, ReviewChoice.ImportAnyway)
        assertEquals(TransactionStatus.New, status(tx))
        // Two days later it lies before the Upload Window of 7 days.
        slice.clock.advance(Duration.ofDays(2))
        compare()

        assertEquals(TransactionStatus.TooOld, status(tx))
        val shown = sheet(tx)
        assertEquals(ReviewChoice.ImportAnyway, shown.decision?.choice)
        assertEquals(TransactionStatus.TooOld, shown.statusWithoutDecision)
        assertEquals(emptyList<ReviewChoice>(), shown.choices)
        assertTrue(shown.canClear)
        assertEquals(ReviewOutcome.NotOffered(emptyList()), slice.review.decide(tx, ReviewChoice.Defer))
        assertEquals(NotYetUploaded(0, 0), counts())

        slice.settings.settings = slice.settings.settings.copy(uploadWindowDays = 30)
        compare()
        assertEquals(TransactionStatus.New, status(tx))
        assertEquals(NotYetUploaded(1, 0), counts())
    }

    // ---- Preconditions and failure postconditions ---------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario")
    @DisplayName("Precondition: a Bank Transaction that has no Transaction Status from a Comparison yet, or whose Bank Account is a Retired Account, cannot be decided: nothing is stored and the sheet says why")
    fun onlyAComparedBankTransactionIsDecided() = runTest {
        ready()
        val checking = checking()
        val retired = bankAccount("Old Saver", "Reserve Savings", retired = true)
        val compared = stored(checking, null, oct14, amount(-12))
        answerHousehold()
        compare()
        val arrivedSince = stored(checking, null, oct14, amount(-13), firstSeenAt = now.plus(Duration.ofMinutes(1)))
        val ofRetired = stored(retired, null, oct14, amount(-14))
        assertEquals(TransactionStatus.NeedsReview, status(compared))

        assertEquals(ReviewOutcome.NotDecidable(Undecidable.NotComparedYet), slice.review.decide(arrivedSince, ReviewChoice.ImportAnyway))
        assertEquals(ReviewOutcome.NotDecidable(Undecidable.BankAccountNotCompared), slice.review.decide(ofRetired, ReviewChoice.ImportAnyway))
        assertEquals(ReviewOutcome.NotFound, slice.review.decide(999_999, ReviewChoice.ImportAnyway))
        assertEquals(ReviewOutcome.NotFound, slice.review.clear(999_999))
        assertNull(slice.review.sheet(999_999))

        assertEquals(Undecidable.NotComparedYet, sheet(arrivedSince).undecidable)
        assertEquals(emptyList<ReviewChoice>(), sheet(arrivedSince).choices)
        assertEquals(Undecidable.BankAccountNotCompared, sheet(ofRetired).undecidable)
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertNull(status(arrivedSince))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A11: Doubtful Transfer Leg", businessRules = ["BR-009"])
    @DisplayName("A11: a Transfer Leg of a Transfer the Operator confirmed, held back or gave another Budget Account is not decided yet, with nothing stored: the Comparison leaves such Transfer Legs as they are stored until it handles Transfer Decisions")
    fun aLegOfADecidedTransferIsNotDecidedYet() = runTest {
        val (paid, _) = cardPaymentWithADoubtfulLeg()
        val version = slice.stateVersion.value

        listOf(TransferDecision.Confirmed, TransferDecision.Held, TransferDecision.BudgetAccountChosen).forEach { decision ->
            slice.copyStore.transfers[0] = slice.copyStore.transfers.single().copy(decision = decision, decidedAt = now)

            assertEquals(ReviewOutcome.NotDecidable(Undecidable.LegOfDecidedTransfer), slice.review.decide(paid, ReviewChoice.ImportAnyway))
            assertEquals(Undecidable.LegOfDecidedTransfer, sheet(paid).undecidable)
        }
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertEquals(TransactionStatus.NeedsReview, status(paid))
        assertEquals(version, slice.stateVersion.value)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A8: Upload Plan Awaiting Confirmation")
    @DisplayName("ADR-007 §2: a Review Decision is admitted while an Upload Plan awaits confirmation and raises state_version, so that the plan it was prepared before can never be written unchanged, and is refused with the reason while an Upload is written; the replacement of the plan (A8 step 1) comes with the Uploads")
    fun admittedWhileAPlanIsOpenAndRefusedWhileUploading() = runTest {
        val tx = possibleImport(sameDay)
        val version = slice.stateVersion.value

        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(ReviewOutcome.Unavailable(OperationRefusal.UploadBeingWritten), slice.review.decide(tx, ReviewChoice.Defer))
        assertEquals(ReviewOutcome.Unavailable(OperationRefusal.UploadBeingWritten), slice.review.clear(tx))
        assertNull(decisionOf(tx))
        assertEquals(version, slice.stateVersion.value)

        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(ReviewOutcome.Stored(ReviewChoice.Defer, TransactionStatus.Deferred), slice.review.decide(tx, ReviewChoice.Defer))
        assertTrue(slice.stateVersion.value > version)
        val afterDecision = slice.stateVersion.value
        assertEquals(ReviewOutcome.Stored(null, TransactionStatus.PossibleImportByOtherPath), slice.review.clear(tx))
        assertTrue(slice.stateVersion.value > afterDecision)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A9: Unreadable Transaction", businessRules = ["BR-007"])
    @DisplayName("ADR-007 §2: deferring an Unreadable Transaction is admitted while an Upload Plan awaits confirmation and refused while an Upload is written")
    fun deferringAnUnreadableTransactionIsAdmittedLikeAReviewDecision() = runTest {
        ready()
        val checking = checking()
        val unreadable = nextId++
        slice.importStatusStore.unreadableTransactions += StoredUnreadableTransaction(
            unreadable, checking, oct14, null, null, null, null, "amount missing", now.minus(Duration.ofHours(1)), UnreadableTransactionStatus.NeedsReview,
        )

        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(UnreadableOutcome.Unavailable(OperationRefusal.UploadBeingWritten), slice.review.deferUnreadable(unreadable))
        assertEquals(UnreadableTransactionStatus.NeedsReview, slice.importStatusStore.unreadableTransactions.single().status)
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(UnreadableOutcome.Stored(UnreadableTransactionStatus.Deferred), slice.review.deferUnreadable(unreadable))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", requirements = ["NFR-002"])
    @DisplayName("Failure postconditions, NFR-023: when the stored data cannot be written, the decision answers that nothing was stored instead of failing: the earlier Review Decision, every Transaction Status and state_version are unchanged, nothing is journaled, and no doubtful Bank Transaction became eligible")
    fun aStorageFailureStoresNothing() = runTest {
        val tx = possibleImport(sameDay)
        slice.review.decide(tx, ReviewChoice.Defer)
        val unreadable = nextId++
        slice.importStatusStore.unreadableTransactions += StoredUnreadableTransaction(
            unreadable, transaction(tx).bankAccountId, oct14, null, null, null, null, "amount missing", now.minus(Duration.ofHours(1)), UnreadableTransactionStatus.NeedsReview,
        )
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size
        slice.copyStore.failsToDecide = true

        assertEquals(ReviewOutcome.NotStored, slice.review.decide(tx, ReviewChoice.ImportAnyway))
        assertEquals(ReviewOutcome.NotStored, slice.review.clear(tx))
        assertEquals(UnreadableOutcome.NotStored, slice.review.deferUnreadable(unreadable))

        assertEquals(listOf(StoredReviewDecision(tx, ReviewChoice.Defer, null, now)), slice.copyStore.reviewDecisions.toList())
        assertEquals(TransactionStatus.Deferred, status(tx))
        assertEquals(UnreadableTransactionStatus.NeedsReview, slice.importStatusStore.unreadableTransactions.single().status)
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertEquals(0, counts().eligible)
    }
}
