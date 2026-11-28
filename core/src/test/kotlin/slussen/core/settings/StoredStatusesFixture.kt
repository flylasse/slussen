package slussen.core.settings

import slussen.core.comparison.ComparisonReport
import slussen.core.comparison.ComparisonStart
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.TransactionStatus
import slussen.pluginapi.budgetapp.BudgetAccountRead
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.value.Amount
import slussen.core.connection.BudgetsRead
import slussen.testing.ChangesRead
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The stored data the tests of UC-010 step 5 and UC-003 BR-016 start from, on the fakes of `:testing`: Household and
 * Care Fund served, Kestrel Checking mapped to Household's Checking and Care Saver to Care Fund's Health Savings, each
 * with Bank Transactions a Fetch stored, and reads of the Budget App the test scripts. Today is 2026-10-15; with the
 * Upload Window of 7 days a Bank Transaction of 2026-10-06 is Too Old and one of 2026-10-13 New. Every name, amount and
 * identifier is invented (C-005).
 */
class StoredStatusesFixture {
    val slice = TestSlice()
    val now: Instant = slice.clock.instant()
    private var nextId = 8000L

    val oct06: LocalDate = LocalDate.of(2026, 10, 6)
    val oct13: LocalDate = LocalDate.of(2026, 10, 13)

    /** Household answers [householdAnswer]; every other Budget answers no change. Null for Household: no answer scripted yet. */
    var householdAnswer: (ChangesRead) -> ReadResult<ChangeSet> = { ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("h-${it.since?.value}"))) }

    init {
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") householdAnswer(asked)
            else ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("other-${asked.budgetReference}-${slice.budgetApp.reader.changeReads.size}")))
        }
    }

    /** The credentials are accepted and the three Budgets of the example Budget App are served. */
    suspend fun ready() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    suspend fun budgetAccountIdOf(name: String): Long = budgetAccountId(name)

    suspend fun budgetId(name: String): Long = slice.connectionStore.budgets().single { it.name == name }.id

    private suspend fun budgetAccountId(name: String): Long = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    /** A Bank Account mapped to the Budget Account [budgetAccountName]. */
    suspend fun bankAccount(name: String, budgetAccountName: String, type: BankAccountType = BankAccountType.Checking): Long {
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
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccountId(budgetAccountName), null),
        )
        return id
    }

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet. */
    fun stored(bankAccountId: Long, reference: String, date: LocalDate, amount: Amount): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = importIdentifierOf(reference),
            bookedDate = date,
            amount = amount,
            currency = USD,
            payee = "PLANTED-PAYEE",
            memo = null,
            firstSeenAt = now.minus(Duration.ofHours(1)),
            status = null,
            transferId = null,
        )
        return id
    }

    fun importIdentifierOf(reference: String): String =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    fun status(id: Long): TransactionStatus? = slice.importStatusStore.bankTransactions.single { it.id == id }.status

    fun statuses(): Map<Long, TransactionStatus?> = slice.importStatusStore.bankTransactions.associate { it.id to it.status }

    /** A Comparison that reads the Budget App, as the Operator asks for it. */
    suspend fun compare(): ComparisonReport = (slice.comparison.readAgain() as ComparisonStart.Ran).report

    /** A transaction the Budget App holds in Checking under the Import Identifier of the Bank Transaction [reference]. */
    fun inBudgetApp(reference: String, date: LocalDate, amount: Amount, budgetAppReference: String = "ynab-$reference") = BudgetAppTransactionRead(
        reference = budgetAppReference,
        budgetAccountReference = "ba-checking",
        date = date,
        amount = amount,
        payee = "PLANTED-PAYEE",
        memo = null,
        importIdentifier = importIdentifierOf(reference),
        entry = EntryKind.Imported,
        state = ReadState.Present,
        transferLink = null,
        cleared = true,
        approved = true,
    )

    /**
     * Kestrel Checking with a Bank Transaction of 2026-10-13 (New) and one of 2026-10-06 (Too Old), Care Saver with one of
     * 2026-10-06 (Too Old), both Budgets read once (each now has a cursor). Returns (recent, old, careOld).
     */
    suspend fun readOnce(): Triple<Long, Long, Long> {
        ready()
        val checking = bankAccount("Kestrel Checking", "Checking")
        val care = bankAccount("Care Saver", "Health Savings")
        val recent = stored(checking, "TX-RECENT", oct13, amount(-12))
        val old = stored(checking, "TX-OLD", oct06, amount(-30))
        val careOld = stored(care, "TX-CARE-OLD", oct06, amount(-40))
        compare()
        check(status(recent) == TransactionStatus.New && status(old) == TransactionStatus.TooOld && status(careOld) == TransactionStatus.TooOld)
        return Triple(recent, old, careOld)
    }

    /** UC-013: the Budget App lists a new Budget Account "New Card" in Household, and the Operator chooses the Budgets again. */
    suspend fun householdGainsABudgetAccount() {
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.Answered(
            ExampleData.budgetsRead().map { budget ->
                if (budget.reference != "budget-household") budget
                else budget.copy(budgetAccounts = budget.budgetAccounts + BudgetAccountRead("ba-new-card", "New Card", amount(-25)))
            },
        )
        val listed = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(listed.budgets, listed.budgets.map { it.budgetAppReference }.toSet())
    }

    /** What was read from outside Slussen so far: the reads of the Budget App and the Fetches of the Data Provider. */
    fun readsFromOutside(): List<Int> = listOf(slice.budgetApp.reader.budgetReads, slice.budgetApp.reader.changeReads.size, slice.feeds.fetches)
}
