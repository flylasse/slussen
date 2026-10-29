package slussen.core.comparison

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.credential.CredentialStatus
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.EnteredBy
import slussen.core.port.JournalEventKind
import slussen.core.port.ServiceKey
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.TransactionStatus
import slussen.core.status.Blocked
import slussen.core.status.BlockedReason
import slussen.core.status.NotYetUploaded
import slussen.core.status.Remedy
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.budgetapp.TransferLink
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.budgetapp.BudgetAccountRead
import slussen.core.connection.BudgetsRead
import slussen.testing.ChangesRead
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Compare With the Budget App (UC-003) as far as the transactions plan's Phase 7a builds it: the read of every Budget a
 * Bank Account is mapped to with its read period and cursor (step 1, BR-016, BR-020), the Transaction Status of each
 * Bank Transaction in the order of BR-001 for BR-002..BR-007, BR-011..BR-016 and BR-018 (steps 2-3, A3, A7), the
 * Eligible Transactions (step 5, BR-014), the Decision Journal (step 6), A1, A2, and the Comparison after a Fetch (UC-001
 * step 10) and on its own, through the operation coordinator, with the Comparison-pending marker (ADR-007 §3a). Transfer
 * detection (step 4) is Phase 7b; Review Decisions, Sent Records and First Upload Matching are later initiatives.
 * Every name, amount and identifier is invented (C-005).
 */
@DisplayName("UC-003: Compare With the Budget App")
class UC003CompareWithTheBudgetAppTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private val today: LocalDate = LocalDate.of(2026, 10, 15)
    private var nextId = 5000L

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** The credentials of the Budget App and Kestrel Feeds are accepted; Household (USD), Summer House (EUR) and Care Fund (USD) are served. */
    private suspend fun ready() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    private suspend fun budgetAccount(name: String) = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }

    private suspend fun budget(name: String) = slice.connectionStore.budgets().single { it.name == name }

    /** A Bank Account the Operator added, mapped to the Budget Account [budgetAccountName]. */
    private suspend fun bankAccount(
        name: String,
        budgetAccountName: String,
        currency: CurrencyCode = USD,
        startDate: LocalDate? = null,
        fileFormat: Boolean = false,
        retired: Boolean = false,
    ): Long {
        val id = nextId++
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = if (fileFormat) null else slice.feedsId,
            fileFormatId = if (fileFormat) slice.plugins.fileFormats.single().id else null,
            balanceDataProviderId = null,
            name = name,
            currency = currency,
            type = BankAccountType.Checking,
            startDate = startDate,
            providerReference = if (fileFormat) null else "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = retired,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount(budgetAccountName).id, null),
        )
        return id
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
            payee = "PLANTED-PAYEE-${id}",
            memo = null,
            firstSeenAt = firstSeenAt,
            status = null,
            transferId = null,
        )
        return id
    }

    private fun status(id: Long): TransactionStatus? = slice.importStatusStore.bankTransactions.single { it.id == id }.status

    /** One Budget App Transaction as the Budget App reports it in the read model of ADR-001 §4.2.3. */
    private fun read(
        reference: String,
        date: LocalDate,
        amount: Amount,
        account: String = "ba-checking",
        importIdentifier: String? = null,
        entry: EntryKind = if (importIdentifier == null) EntryKind.Typed else EntryKind.Imported,
        state: ReadState = ReadState.Present,
        link: TransferLink? = null,
        cleared: Boolean = true,
        approved: Boolean = true,
        payee: String? = "Corner Grocer",
        memo: String? = null,
    ) = BudgetAppTransactionRead(reference, account, date, amount, payee, memo, importIdentifier, entry, link, state, cleared, approved)

    /** The Budget App answers every read of changes of Household with [transactions] and the cursor [next]. */
    private fun answerHousehold(vararg transactions: BudgetAppTransactionRead, next: String = "k-1") {
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") {
                ReadResult.Answered(ChangeSet(transactions.toList(), ChangeCursor(next)))
            } else {
                ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("other-${asked.budgetReference}")))
            }
        }
    }

    private suspend fun compare(): ComparisonReport = (slice.comparison.readAgain() as ComparisonStart.Ran).report

    private suspend fun counts(budgetName: String): NotYetUploaded = slice.status.workspace(budget(budgetName).id).summary.notYetUploaded

    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    // ---- Main Success Scenario --------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016", "BR-020"])
    @DisplayName("Step 1, BR-016, BR-020: the first read of a Budget covers today back by the longest of the fetch period, the Upload Window and the file upload window plus the Matching Date Window; the copy keeps every field with the cursor, the balances and the time of the read")
    fun firstReadStoresTheCopyWithItsCursor() = runTest {
        ready()
        bankAccount("Kestrel Checking", "Checking")
        answerHousehold(
            read("ynab-1", oct14, amount(-42, 500), importIdentifier = "YNAB:-42500:2026-10-14:1", cleared = false, approved = false, memo = "PLANTED-MEMO-1"),
            read("ynab-2", oct14, amount(-12), state = ReadState.Merged("ynab-3")),
            read("ynab-3", oct14, amount(-12), link = TransferLink("ynab-9", "ba-reserve")),
        )

        val report = compare()

        // 30-day fetch period, 7-day Upload Window, 60-day file upload window: 60 + the Matching Date Window of 10 = 70 days.
        assertEquals(listOf(ChangesRead("budget-household", null, today.minusDays(70))), slice.budgetApp.reader.changeReads.filter { it.budgetReference == "budget-household" })
        val household = budget("Household")
        val copy = slice.copyStore.budgetAppTransactionsOf(household.id).associateBy { it.budgetAppReference }
        val checking = budgetAccount("Checking").id
        assertEquals(
            StoredBudgetAppTransaction(
                copy.getValue("ynab-1").id, checking, "ynab-1", oct14, amount(-42, 500), "Corner Grocer", "PLANTED-MEMO-1",
                "YNAB:-42500:2026-10-14:1", EnteredBy.OtherImportPath, null, null, cleared = false, approved = false, BudgetAppTransactionState.Present,
            ),
            copy.getValue("ynab-1"),
        )
        assertEquals(BudgetAppTransactionState.Merged, copy.getValue("ynab-2").state)
        assertEquals("ynab-3", copy.getValue("ynab-2").mergedIntoReference)
        assertEquals("ynab-9", copy.getValue("ynab-3").transferLinkReference)
        assertEquals(ChangeCursor("k-1"), slice.copyStore.changeCursorOf(household.id))
        assertEquals(now, household.lastReadAt)
        assertEquals(amount(4140, 50), budgetAccount("Checking").balance)
        assertEquals(now, budgetAccount("Checking").balanceReadAt)
        assertFalse(slice.copyStore.pending)
        val outcome = (report as ComparisonReport.Compared).budgets.single { it.budgetName == "Household" }
        assertEquals(BudgetReadOutcome.Read(household.id, "Household", now), outcome)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016 (S-T-18): when a served Budget gains a Budget Account, its cursor is forgotten and the next read covers the read period again, so the new Budget Account's earlier transactions are in the copy")
    fun budgetGainingABudgetAccountIsReadAgain() = runTest {
        ready()
        bankAccount("Kestrel Checking", "Checking")
        val household = budget("Household").id
        val onNewAccount = read("ynab-new", oct14, amount(-25), account = "ba-new-card")
        answerHousehold(onNewAccount, read("ynab-1", oct14, amount(-8)), next = "k-1")
        compare()
        assertEquals(listOf("ynab-1"), slice.copyStore.budgetAppTransactionsOf(household).map { it.budgetAppReference })
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.Answered(
            ExampleData.budgetsRead().map { budget ->
                if (budget.reference != "budget-household") budget
                else budget.copy(budgetAccounts = budget.budgetAccounts + BudgetAccountRead("ba-new-card", "New Card", amount(-25)))
            },
        )
        val listed = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(listed.budgets, listed.budgets.map { it.budgetAppReference }.toSet())

        assertNull(slice.copyStore.changeCursorOf(household))
        answerHousehold(onNewAccount, read("ynab-1", oct14, amount(-8)), next = "k-2")
        compare()

        assertEquals(null, slice.budgetApp.reader.changeReads.last { it.budgetReference == "budget-household" }.since)
        assertEquals(setOf("ynab-1", "ynab-new"), slice.copyStore.budgetAppTransactionsOf(household).map { it.budgetAppReference }.toSet())
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016", "BR-020"])
    @DisplayName("BR-016: every later read takes all changes since the previous read from the stored cursor; a transaction reported deleted is kept with the state Deleted, never removed")
    fun laterReadTakesTheChangesSinceTheCursor() = runTest {
        ready()
        bankAccount("Kestrel Checking", "Checking")
        answerHousehold(read("ynab-1", oct14, amount(-8)), next = "k-1")
        compare()
        answerHousehold(read("ynab-1", oct14, amount(-8), state = ReadState.Deleted), next = "k-2")

        compare()

        val reads = slice.budgetApp.reader.changeReads.filter { it.budgetReference == "budget-household" }
        assertEquals(listOf(null, ChangeCursor("k-1")), reads.map { it.since })
        val copy = slice.copyStore.budgetAppTransactionsOf(budget("Household").id).single()
        assertEquals(BudgetAppTransactionState.Deleted, copy.state)
        assertEquals(ChangeCursor("k-2"), slice.copyStore.changeCursorOf(budget("Household").id))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-014"])
    @DisplayName("Steps 2-3, 5: a Bank Transaction nothing in its Budget Account resembles is New and an Eligible Transaction; the counts of the import status come from the Comparison")
    fun unmatchedBankTransactionIsNewAndEligible() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tx = stored(checking, "TX-1", oct14, amount(-64, 180))
        answerHousehold(read("ynab-1", oct14, amount(-99)))
        assertEquals(NotYetUploaded(0, 0), counts("Household"))

        compare()

        assertEquals(TransactionStatus.New, status(tx))
        assertEquals(NotYetUploaded(1, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario")
    @DisplayName("Step 6, UC-011 BR-001: every changed Transaction Status is recorded with its Bank Account and Import Identifier and no payee; a status that stays the same is not recorded again")
    fun changedStatusesAreJournaled() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        stored(checking, "TX-1", oct14, amount(-64, 180))
        answerHousehold()
        val before = slice.journalRecorder.entries.size

        compare()

        val entry = slice.journalRecorder.entries.drop(before).single()
        assertEquals(JournalEventKind.StatusChanged, entry.eventKind)
        assertEquals(identifier("TX-1"), entry.importIdentifier)
        assertEquals("Kestrel Checking", entry.bankAccountName)
        assertEquals("Transaction Status changed from not compared yet to New", entry.details)
        assertFalse("PLANTED" in entry.toString())

        val again = slice.journalRecorder.entries.size
        answerHousehold(read("ynab-5", oct14.minusDays(3), amount(-64, 180)), next = "k-2")
        compare()
        val changed = slice.journalRecorder.entries.drop(again).single()
        assertEquals("Transaction Status changed from New to Match For Typed Entry", changed.details)

        val third = slice.journalRecorder.entries.size
        compare()
        assertEquals(third, slice.journalRecorder.entries.size)
    }

    // ---- The order of BR-001 ----------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002", "BR-003", "BR-011"])
    @DisplayName("BR-003: a Bank Transaction whose Import Identifier its Budget Account holds is Already In Budget App and never eligible, also outside the Upload Window (BR-011); one held in another Budget Account is not")
    fun importIdentifierHeldIsAlreadyInBudgetApp() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val held = stored(checking, "TX-1", oct14, amount(-64, 180))
        val heldOld = stored(checking, "TX-2", today.minusDays(40), amount(-7))
        val elsewhere = stored(checking, "TX-3", oct14, amount(-3))
        answerHousehold(
            read("ynab-1", oct14, amount(-64, 180), importIdentifier = identifier("TX-1")),
            read("ynab-2", today.minusDays(40), amount(-7), importIdentifier = identifier("TX-2")),
            read("ynab-3", oct14, amount(-3), account = "ba-reserve", importIdentifier = identifier("TX-3")),
        )

        compare()

        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(held))
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(heldOld))
        assertEquals(TransactionStatus.New, status(elsewhere))
        assertEquals(NotYetUploaded(1, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-004", "BR-005"])
    @DisplayName("BR-004, BR-005: a Bank Transaction whose Import Identifier its Budget Account held in a transaction since deleted is Deleted In Budget App, one merged into a Typed Entry Merged In Budget App; neither is eligible")
    fun deletedAndMergedInTheBudgetApp() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val deleted = stored(checking, "TX-1", oct14, amount(-64, 180))
        val merged = stored(checking, "TX-2", oct14, amount(-20))
        answerHousehold(
            read("ynab-1", oct14, amount(-64, 180), importIdentifier = identifier("TX-1"), state = ReadState.Deleted),
            read("ynab-2", oct14, amount(-20), importIdentifier = identifier("TX-2"), state = ReadState.Merged("ynab-7")),
            read("ynab-7", oct14.minusDays(1), amount(-20)),
        )

        compare()

        assertEquals(TransactionStatus.DeletedInBudgetApp, status(deleted))
        assertEquals(TransactionStatus.MergedInBudgetApp, status(merged))
        assertEquals(NotYetUploaded(0, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A7: Transaction Before the Start Date", businessRules = ["BR-018"])
    @DisplayName("A7, BR-018: a Bank Transaction dated before the Start Date of its Bank Account is Before Start Date, also when it is outside the Upload Window, and never eligible; one dated on the Start Date is not; a Bank Account without a Start Date has no such limit")
    fun beforeTheStartDate() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking", startDate = today.minusDays(2))
        val before = stored(checking, "TX-1", today.minusDays(3), amount(-10))
        val longBefore = stored(checking, "TX-2", today.minusDays(40), amount(-11))
        val onIt = stored(checking, "TX-3", today.minusDays(2), amount(-12))
        val reserve = bankAccount("Reserve Saver", "Reserve Savings")
        val noLimit = stored(reserve, "TX-4", today.minusDays(3), amount(-13))
        answerHousehold()

        compare()

        assertEquals(TransactionStatus.BeforeStartDate, status(before))
        assertEquals(TransactionStatus.BeforeStartDate, status(longBefore))
        assertEquals(TransactionStatus.New, status(onIt))
        assertEquals(TransactionStatus.New, status(noLimit))
        assertEquals(NotYetUploaded(2, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-011"])
    @DisplayName("BR-011: only a Bank Transaction dated within the Upload Window (7 days by default) and not after today can be eligible; one older is Too Old, one after today In The Future, and the window is the Operator Setting")
    fun uploadWindow() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val lastDay = stored(checking, "TX-1", today.minusDays(7), amount(-10))
        val tooOld = stored(checking, "TX-2", today.minusDays(8), amount(-11))
        val future = stored(checking, "TX-3", today.plusDays(1), amount(-12))
        val todays = stored(checking, "TX-4", today, amount(-13))
        answerHousehold()

        compare()

        assertEquals(TransactionStatus.New, status(lastDay))
        assertEquals(TransactionStatus.TooOld, status(tooOld))
        assertEquals(TransactionStatus.InTheFuture, status(future))
        assertEquals(TransactionStatus.New, status(todays))
        assertEquals(NotYetUploaded(2, 0), counts("Household"))

        slice.settings.settings = slice.settings.settings.copy(uploadWindowDays = 10)
        compare()
        assertEquals(TransactionStatus.New, status(tooOld))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-011"])
    @DisplayName("BR-011: a Bank Transaction from a Transaction File uses the file upload window (60 days by default) instead of the Upload Window")
    fun fileUploadWindow() = runTest {
        ready()
        val health = bankAccount("Harbor Health Savings", "Reserve Savings", fileFormat = true)
        val inside = stored(health, "TX-1", today.minusDays(60), amount(-10))
        val outside = stored(health, "TX-2", today.minusDays(61), amount(-11))
        answerHousehold()

        compare()

        assertEquals(TransactionStatus.New, status(inside))
        assertEquals(TransactionStatus.TooOld, status(outside))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-012"])
    @DisplayName("BR-012: a Bank Transaction without a Stable Reference is Needs Review, waits for a decision and is never eligible")
    fun withoutStableReferenceNeedsReview() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val noReference = stored(checking, null, oct14, amount(-10))
        answerHousehold()

        compare()

        assertEquals(TransactionStatus.NeedsReview, status(noReference))
        assertEquals(NotYetUploaded(0, 1), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-006"], requirements = ["C-009"])
    @DisplayName("BR-006, C-009: a Bank Transaction whose Budget Account holds a Typed Entry of the same amount within the Matching Date Window (10 days for YNAB) before or after it is Match For Typed Entry and eligible; 11 days apart, another amount or another Budget Account is New")
    fun matchForTypedEntry() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tenBefore = stored(checking, "TX-1", oct14, amount(-50))
        val tenAfter = stored(checking, "TX-2", oct14, amount(-51))
        val eleven = stored(checking, "TX-3", oct14, amount(-52))
        val otherAmount = stored(checking, "TX-4", oct14, amount(-53))
        val otherAccount = stored(checking, "TX-5", oct14, amount(-54))
        answerHousehold(
            read("ynab-1", oct14.minusDays(10), amount(-50)),
            read("ynab-2", oct14.plusDays(10), amount(-51)),
            read("ynab-3", oct14.minusDays(11), amount(-52)),
            read("ynab-4", oct14, amount(-53, 1)),
            read("ynab-5", oct14, amount(-54), account = "ba-reserve"),
        )

        compare()

        assertEquals(TransactionStatus.MatchForTypedEntry, status(tenBefore))
        assertEquals(TransactionStatus.MatchForTypedEntry, status(tenAfter))
        assertEquals(TransactionStatus.New, status(eleven))
        assertEquals(TransactionStatus.New, status(otherAmount))
        assertEquals(TransactionStatus.New, status(otherAccount))
        assertEquals(NotYetUploaded(5, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Bank Transaction resembling an Import of an Other Import Path (same amount, within the Matching Date Window) is Possible Import By Other Path, held back from eligibility and waiting for a decision")
    fun possibleImportByOtherPath() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val resembling = stored(checking, "TX-1", oct14, amount(-42, 500))
        answerHousehold(read("ynab-1", oct14.minusDays(2), amount(-42, 500), importIdentifier = "YNAB:-42500:2026-10-12:1"))

        compare()

        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(resembling))
        assertEquals(NotYetUploaded(0, 1), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-008"])
    @DisplayName("BR-008: an Import whose Import Identifier begins with Slussen's marker is stored as Slussen's own, Slussen Unsupported Format when its form is not one this build produces, never Other Import Path; a Bank Transaction resembling an unsupported one is Needs Review, one resembling a supported one of another transaction is not held back")
    fun ownUploadsRecognised() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val resemblesUnsupported = stored(checking, "TX-1", oct14, amount(-30))
        val resemblesOwn = stored(checking, "TX-2", oct14, amount(-31))
        answerHousehold(
            read("ynab-1", oct14, amount(-30), importIdentifier = "SLS9-not-a-form-this-build-makes"),
            read("ynab-2", oct14, amount(-31), importIdentifier = identifier("TX-OTHER")),
        )

        compare()

        val copy = slice.copyStore.budgetAppTransactionsOf(budget("Household").id).associateBy { it.budgetAppReference }
        assertEquals(EnteredBy.SlussenUnsupportedFormat, copy.getValue("ynab-1").enteredBy)
        assertEquals(EnteredBy.Slussen, copy.getValue("ynab-2").enteredBy)
        assertEquals(TransactionStatus.NeedsReview, status(resemblesUnsupported))
        assertEquals(TransactionStatus.New, status(resemblesOwn))
        assertEquals(NotYetUploaded(1, 1), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-001", "BR-006", "BR-007", "BR-008", "BR-012"])
    @DisplayName("BR-001: the first rule that applies gives the status: no Stable Reference before a resemblance; an unsupported own Import before an Other Import Path; an Other Import Path before a Typed Entry")
    fun orderOfTheComparison() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val noReference = stored(checking, null, oct14, amount(-60))
        val unsupportedFirst = stored(checking, "TX-2", oct14, amount(-61))
        val otherPathFirst = stored(checking, "TX-3", oct14, amount(-62))
        answerHousehold(
            read("ynab-1", oct14, amount(-60), importIdentifier = "YNAB:-60000:2026-10-14:1"),
            read("ynab-2", oct14, amount(-61), importIdentifier = "SLS-unsupported"),
            read("ynab-3", oct14, amount(-61), importIdentifier = "YNAB:-61000:2026-10-14:1"),
            read("ynab-4", oct14, amount(-62), importIdentifier = "YNAB:-62000:2026-10-14:1"),
            read("ynab-5", oct14, amount(-62)),
        )

        compare()

        assertEquals(TransactionStatus.NeedsReview, status(noReference))
        assertEquals(TransactionStatus.NeedsReview, status(unsupportedFirst))
        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(otherPathFirst))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-020"])
    @DisplayName("BR-020, ADR-001 §4.2.3: the core decides how each transaction came into the Budget App from its entry kind: the other side of a linked transfer to a Slussen transaction is a Transfer Counterpart, any other entered by hand a Typed Entry")
    fun entryKindsDecideEnteredBy() = runTest {
        ready()
        bankAccount("Kestrel Checking", "Checking")
        answerHousehold(
            read("ynab-1", oct14, amount(-100), importIdentifier = identifier("TX-1"), link = TransferLink("ynab-2", "ba-reserve")),
            read("ynab-2", oct14, amount(100), account = "ba-reserve", entry = EntryKind.TransferSide, link = TransferLink("ynab-1", "ba-checking")),
            read("ynab-3", oct14, amount(-40), link = TransferLink("ynab-4", "ba-reserve"), entry = EntryKind.TransferSide),
            read("ynab-4", oct14, amount(40), account = "ba-reserve", entry = EntryKind.TransferSide, link = TransferLink("ynab-3", "ba-checking")),
            read("ynab-5", oct14, amount(-1), account = "ba-unknown"),
        )

        compare()

        val copy = slice.copyStore.budgetAppTransactionsOf(budget("Household").id).associateBy { it.budgetAppReference }
        assertEquals(EnteredBy.Slussen, copy.getValue("ynab-1").enteredBy)
        assertEquals(EnteredBy.TransferCounterpart, copy.getValue("ynab-2").enteredBy)
        assertEquals("ynab-1", copy.getValue("ynab-2").transferLinkReference)
        assertEquals(EnteredBy.TypedEntry, copy.getValue("ynab-3").enteredBy)
        assertEquals(EnteredBy.TypedEntry, copy.getValue("ynab-4").enteredBy)
        assertFalse("ynab-5" in copy, "a transaction of a Budget Account Slussen does not hold is not stored")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A3: Currency Mismatch", businessRules = ["BR-013", "BR-014"])
    @DisplayName("A3, BR-013: a Bank Account whose currency differs from its Budget's is blocked with the reason shown; none of its Bank Transactions is eligible, no amount is compared or converted, and the other Bank Accounts are compared as usual")
    fun currencyMismatch() = runTest {
        ready()
        val euro = bankAccount("Aurora Euro", "Checking", currency = EUR)
        val mismatched = stored(euro, "TX-1", oct14, amount(-50))
        val reserve = bankAccount("Reserve Saver", "Reserve Savings")
        val usual = stored(reserve, "TX-2", oct14, amount(-50))
        answerHousehold(read("ynab-1", oct14, amount(-50)), read("ynab-2", oct14, amount(-50), account = "ba-reserve"))

        compare()

        assertEquals(TransactionStatus.New, status(mismatched))
        assertEquals(TransactionStatus.MatchForTypedEntry, status(usual))
        assertEquals(NotYetUploaded(1, 0), counts("Household"))
        val blocked = slice.status.workspace(budget("Household").id).bankAccounts.single { it.name == "Aurora Euro" }.blocked
        assertTrue(blocked.any { it.reason == BlockedReason.CurrencyMismatch && it.remedy == Remedy.MoveAccountMapping }, "$blocked")
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-014"])
    @DisplayName("BR-014, postcondition: the Bank Transactions of a Retired Account are not compared and never eligible")
    fun retiredAccountIsNotCompared() = runTest {
        ready()
        val retired = bankAccount("Old Checking", "Checking", retired = true)
        val tx = stored(retired, "TX-1", oct14, amount(-50))
        answerHousehold()

        compare()

        assertNull(status(tx))
    }

    // ---- Alternative flows ------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read")
    @DisplayName("A1 (S-T-08): a Bank Transaction stored before any successful read of its Budget has no Transaction Status and is counted neither as eligible nor as waiting")
    fun notComparedYetBeforeAnyRead() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tx = stored(checking, null, oct14, amount(-10))
        slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(FailureKind.Timeout) }

        val report = compare()

        assertNull(status(tx))
        assertEquals(NotYetUploaded(0, 0), counts("Household"))
        val household = (report as ComparisonReport.Compared).budgets.single { it.budgetName == "Household" }
        assertEquals(BudgetReadOutcome.NotRead(budget("Household").id, "Household", FailureKind.Timeout, lastReadAt = null), household)
        assertNull(slice.copyStore.changeCursorOf(budget("Household").id))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-016"])
    @DisplayName("A1, NFR-016: when one Budget cannot be read in the request timeout, its statuses and stored copy are kept, a Bank Transaction stored since its last read is not compared yet, and the other Budgets are compared")
    fun oneBudgetCannotBeRead() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val earlier = stored(checking, "TX-1", oct14, amount(-10))
        val summer = bankAccount("Aurora Current", "Current", currency = EUR)
        val summerTx = stored(summer, "TX-9", oct14, amount(-9))
        answerHousehold(read("ynab-1", oct14.minusDays(1), amount(-10)))
        compare()
        assertEquals(TransactionStatus.MatchForTypedEntry, status(earlier))
        val firstRead = budget("Household").lastReadAt

        slice.clock.advance(Duration.ofHours(2))
        val since = stored(checking, "TX-2", oct14, amount(-20), firstSeenAt = slice.clock.instant())
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") ReadResult.TransientFailure(FailureKind.Timeout)
            else ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("summer-2")))
        }
        val report = compare() as ComparisonReport.Compared

        assertEquals(TransactionStatus.MatchForTypedEntry, status(earlier))
        assertNull(status(since))
        assertEquals(firstRead, budget("Household").lastReadAt)
        assertEquals(ChangeCursor("k-1"), slice.copyStore.changeCursorOf(budget("Household").id))
        assertEquals(1, slice.copyStore.budgetAppTransactionsOf(budget("Household").id).size)
        assertEquals(TransactionStatus.New, status(summerTx))
        assertInstanceOf(BudgetReadOutcome.NotRead::class.java, report.budgets.single { it.budgetName == "Household" })
        assertInstanceOf(BudgetReadOutcome.Read::class.java, report.budgets.single { it.budgetName == "Summer House" })
        assertEquals(java.time.Duration.ofSeconds(30), slice.budgetApp.reader.contexts.last().timeout.duration)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read")
    @DisplayName("A1: when the Budget App cannot be read at all, no Budget is read and nothing changes")
    fun noBudgetCanBeRead() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tx = stored(checking, "TX-1", oct14, amount(-10))
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.ServiceUnavailable)

        val report = compare() as ComparisonReport.Compared

        assertNull(status(tx))
        assertTrue(report.budgets.all { it is BudgetReadOutcome.NotRead })
        assertTrue(slice.budgetApp.reader.changeReads.isEmpty())
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A2: Budget App Refuses Slussen's Credentials")
    @DisplayName("A2: when the Budget App refuses Slussen's credentials, every status and stored copy is kept, \"credentials refused\" is shown with the action to enter them again, and the Budget App is not contacted again until they are")
    fun credentialsRefused() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tx = stored(checking, "TX-1", oct14, amount(-10))
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused

        val report = compare()

        assertEquals(ComparisonReport.CredentialsRefused, report)
        assertNull(status(tx))
        assertEquals(CredentialStatus.Refused, slice.credentials.status(ServiceKey.budgetApp))
        val blocked = slice.status.workspace(budget("Household").id).summary.blocked
        assertTrue(blocked.contains(Blocked(BlockedReason.CredentialsRefused, Remedy.EnterCredentials, null, ExampleData.BUDGET_APP_NAME)), "$blocked")

        val reads = slice.budgetApp.reader.budgetReads
        assertEquals(ComparisonReport.CredentialsRefused, compare())
        assertEquals(reads, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A2: Budget App Refuses Slussen's Credentials")
    @DisplayName("A2: a refusal of one Budget's read keeps every status of the Budgets not yet compared")
    fun credentialsRefusedForAChangeRead() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tx = stored(checking, "TX-1", oct14, amount(-10))
        slice.budgetApp.reader.changesAnswer = { ReadResult.CredentialsRefused }

        assertEquals(ComparisonReport.CredentialsRefused, compare())
        assertNull(status(tx))
        assertEquals(CredentialStatus.Refused, slice.credentials.status(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-015"])
    @DisplayName("BR-015, failure postcondition: a Comparison reads the Budget App only: it never writes to it and never contacts a Data Provider")
    fun comparingNeverWrites() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        stored(checking, "TX-1", oct14, amount(-10))
        answerHousehold(read("ynab-1", oct14, amount(-10)))
        val providerRequests = slice.providerRequests.requests.size

        compare()

        assertEquals(0, slice.budgetApp.writer.calls)
        assertEquals(0, slice.feeds.fetches)
        assertEquals(providerRequests, slice.providerRequests.requests.size)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario")
    @DisplayName("Trigger: without a served Budget that a Bank Account is mapped to there is nothing to compare, and without entered credentials the Budget App is not contacted (UC-014 BR-005)")
    fun nothingToCompareOrNoCredentials() = runTest {
        ready()
        assertEquals(ComparisonReport.NothingToCompare, compare())
        bankAccount("Kestrel Checking", "Checking")
        slice.credentials.remove(ServiceKey.budgetApp)

        assertEquals(ComparisonReport.CredentialsMissing, compare())
        assertEquals(0, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario")
    @DisplayName("Precondition (UC-006 BR-005, ADR-007 §2): while an Upload Plan awaits confirmation the Budget App is not read again")
    fun notWhileAPlanIsOpen() = runTest {
        ready()
        bankAccount("Kestrel Checking", "Checking")
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertEquals(ComparisonStart.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), slice.comparison.readAgain())
        assertEquals(0, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-020"])
    @DisplayName("ADR-007 §3a: a Comparison stopped between storing its read and its conclusions leaves the Comparison-pending marker; at the next start the statuses are computed from the stored copy alone, without a request, and the marker cleared")
    fun pendingComparisonIsConcludedFromStoredData() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val tx = stored(checking, "TX-1", oct14, amount(-10))
        answerHousehold(read("ynab-1", oct14, amount(-10)))
        slice.copyStore.failsToConclude = true

        runCatching { compare() }

        assertTrue(slice.copyStore.pending)
        assertNull(status(tx))
        slice.copyStore.failsToConclude = false
        val reads = slice.budgetApp.reader.changeReads.size

        assertTrue(slice.comparison.recomputePending())

        assertEquals(TransactionStatus.MatchForTypedEntry, status(tx))
        assertFalse(slice.copyStore.pending)
        assertEquals(reads, slice.budgetApp.reader.changeReads.size)
        assertFalse(slice.comparison.recomputePending())
    }

    // ---- UC-001 step 10 ---------------------------------------------------------------------------------------------

    /** Kestrel Feeds answers with [transactions] for the Bank Account `acct-<id>`. */
    private fun answerFeeds(bankAccountId: Long, vararg transactions: ProviderTransaction) {
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(DeliveredBankAccount("acct-$bankAccountId", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Read(transactions.toList(), amount(100)))),
            )
        }
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario")
    @DisplayName("Trigger, UC-001 step 10: a Fetch reaches its Comparison in the same operation, so the Bank Transactions it stored have their Transaction Status when it ends")
    fun fetchReachesTheComparison() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        answerFeeds(checking, ProviderTransaction.Interpretable("TX-1", oct14, amount(-64, 180), USD, "Corner Grocer", null, booked = true))
        answerHousehold()

        val start = slice.fetch.fetch(slice.feedsKey) as FetchStart.Ran

        assertInstanceOf(FetchReport.Fetched::class.java, start.reports.single())
        assertInstanceOf(ComparisonReport.Compared::class.java, start.comparison)
        assertEquals(listOf(TransactionStatus.New), slice.importStatusStore.bankTransactionsOf(checking).map { it.status })
        assertEquals(NotYetUploaded(1, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario")
    @DisplayName("Trigger, UC-001 A6, A2: a Fetch that ends without an answer reaches no Comparison; one served from the Fetch Cooldown still reads the Budget App")
    fun failedFetchReachesNoComparison() = runTest {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        slice.feeds.fetchAnswer = { FetchResponse(SourceOutcome.TransientFailure(FailureKind.Timeout), emptyList(), emptyList()) }

        val failed = slice.fetch.fetch(slice.feedsKey) as FetchStart.Ran

        assertNull(failed.comparison)
        assertEquals(0, slice.budgetApp.reader.budgetReads)

        answerFeeds(checking)
        slice.offeredStore.replaceOffered(slice.feedsId, null, listOf(ExampleData.offered("acct-$checking", "CHK", "checking", USD, null, null, null, now)))
        slice.fetch.fetch(slice.feedsKey)
        slice.clock.advance(Duration.ofMinutes(5))
        val served = slice.fetch.fetch(slice.feedsKey) as FetchStart.Ran

        assertInstanceOf(FetchReport.ServedFromFetchCooldown::class.java, served.reports.single())
        assertInstanceOf(ComparisonReport.Compared::class.java, served.comparison)
    }
}
