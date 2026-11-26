package slussen.testing

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
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.UnreadableTransactionReason
import slussen.core.port.UnreadableTransactionStatus
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Household (USD) with Kestrel Checking, Pinecrest Rewards Card (a credit card without a Funded By) and Kestrel Reserve
 * (with the Transfer Partner Checking), whose stored Bank Transactions a real Comparison of the [slice] gives every
 * Transaction Status this build can give (`bank-account.html#checking-this-build`), on 2026-10-15 with the default
 * Operator Settings: the Upload Window starts 10-08, the Matching Date Window is 10 days, the grace period 6 days. Every
 * name, amount and identifier is invented (C-005).
 */
class EveryStatusScenario(val slice: TestSlice) {
    private val now: Instant get() = slice.clock.instant()
    private var nextId = 5000L
    private val bankAccountIds = mutableMapOf<String, Long>()
    private val transactionIds = mutableMapOf<String, Long>()

    /** The Start Date of Kestrel Checking. */
    val startDate: LocalDate = LocalDate.of(2026, 10, 1)

    fun bankAccount(name: String): Long = bankAccountIds.getValue(name)

    /** The stored Bank Transaction planted under [key]. */
    fun transaction(key: String): Long = transactionIds.getValue(key)

    /** The credentials are accepted and Household, Summer House and Care Fund are served, not read yet. */
    suspend fun serve() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    /** A Bank Account the Operator added through Kestrel Feeds, mapped to [budgetAccountName]. */
    suspend fun addBankAccount(
        name: String,
        budgetAccountName: String,
        type: BankAccountType = BankAccountType.Checking,
        startDate: LocalDate? = null,
        currency: CurrencyCode = USD,
    ): Long {
        val id = nextId++
        val budgetAccount = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == budgetAccountName }
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = slice.feedsId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = name,
            currency = currency,
            type = type,
            startDate = startDate,
            providerReference = "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount.id, null),
        )
        bankAccountIds[name] = id
        return id
    }

    fun identifier(reference: String): String =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet (S-T-08), planted under [key]. */
    fun store(
        key: String,
        bankAccountId: Long,
        date: LocalDate,
        amount: Amount,
        payee: String,
        reference: String? = key,
        firstSeenAt: Instant = now.minus(Duration.ofHours(1)),
    ): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = if (reference == null) StableReferenceKind.None else StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = reference?.let(::identifier) ?: "SLS1-${"q".repeat(22)}%04d".format(id % 10000),
            bookedDate = date,
            amount = amount,
            currency = USD,
            payee = payee,
            memo = null,
            firstSeenAt = firstSeenAt,
            status = null,
            transferId = null,
        )
        transactionIds[key] = id
        return id
    }

    /** One transaction of the Budget App as it reads it, in the Budget Account [account]. */
    fun read(
        reference: String,
        date: LocalDate,
        amount: Amount,
        payee: String,
        account: String = "ba-checking",
        importIdentifier: String? = null,
        entry: EntryKind = if (importIdentifier == null) EntryKind.Typed else EntryKind.Imported,
        state: ReadState = ReadState.Present,
    ) = BudgetAppTransactionRead(reference, account, date, amount, payee, null, importIdentifier, entry, null, state, cleared = true, approved = true)

    /** The Budget App answers every read of changes of Household with [transactions]. */
    fun answerHousehold(transactions: List<BudgetAppTransactionRead>) {
        slice.budgetApp.reader.changesAnswer = { asked ->
            val changed = if (asked.budgetReference == "budget-household") transactions else emptyList()
            ReadResult.Answered(ChangeSet(changed, ChangeCursor("k-${slice.budgetApp.reader.changeReads.size}")))
        }
    }

    /**
     * Plants every Bank Transaction, the Unreadable Transaction and the Budget App's copy, reads the Budget App once
     * (a real Comparison, UC-003), and then stores one more Bank Transaction, not compared yet (S-T-08).
     */
    suspend fun compareEveryStatus() {
        serve()
        val checking = addBankAccount("Kestrel Checking", "Checking", startDate = startDate)
        val card = addBankAccount("Pinecrest Rewards Card", "Rewards Card", BankAccountType.CreditCard)
        val reserve = addBankAccount("Kestrel Reserve", "Reserve Savings", BankAccountType.Savings)
        val checkingAccount = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        slice.connectionStore.link(reserve, transferPartner = checkingAccount)

        store("future", checking, day(16), amount(-30), "City Parking")
        store("new", checking, day(14), amount(-64, 180), "Corner Grocer")
        store("possible", checking, day(14), amount(-18, 600), "Riverside Apothecary")
        store("no-reference-1", checking, day(13), amount(-12), "Card purchase", reference = null)
        store("no-reference-2", checking, day(13), amount(-12), "Card purchase", reference = null)
        store("unsupported", checking, day(13), amount(-29), "Streamline Gym")
        store("typed", checking, day(12), amount(-88, 400), "Northwind Power")
        store("deleted", checking, day(12), amount(-23, 500), "Book Nook")
        store("guessed-pay", checking, day(12), amount(-410), "PINECREST AUTOPAY")
        store("leg-out", checking, day(11), amount(-500), "INTERNET TRANSFER TO RESERVE")
        store("already", checking, day(9), amount(-41, 200), "Gas & Go")
        store("merged", checking, day(9), amount(-95), "Bright Smile Dental")
        store("too-old", checking, day(5), amount(-15, 990), "Lumen Streaming")
        store("before-start", checking, LocalDate.of(2026, 9, 29), amount(-4, 800), "Café Brisa")
        store("guessed-card", card, day(13), amount(410), "AUTOPAY RECEIVED")
        store("guessed-alone", card, day(14), amount(33), "AUTOPAY RECEIVED")
        store("leg-in", reserve, day(11), amount(500), "INTERNET TRANSFER FROM CHECKING")
        store("late-side", reserve, day(8), amount(75), "INTERNET TRANSFER FROM CHECKING")
        slice.importStatusStore.unreadableTransactions += StoredUnreadableTransaction(
            id = 4900, bankAccountId = checking, bookedDate = null, amount = amount(-7, 250), currency = USD, payee = "Kiosk Nord",
            memo = null, reason = UnreadableTransactionReason.MissingDate.text, firstSeenAt = now.minus(Duration.ofHours(1)),
            status = UnreadableTransactionStatus.NeedsReview,
        )
        answerHousehold(
            listOf(
                read("y-typed", day(11), amount(-88, 400), "Northwind"),
                read("y-already", day(9), amount(-41, 200), "Gas & Go", importIdentifier = identifier("already")),
                read("y-possible", day(14), amount(-18, 600), "Riverside Apoth.", importIdentifier = "BANK:-18600:2026-10-14:1"),
                read("y-unsupported", day(12), amount(-29), "Streamline", importIdentifier = "SLS9-not-a-form-this-build-makes"),
                read("y-deleted", day(12), amount(-23, 500), "Book Nook", importIdentifier = identifier("deleted"), state = ReadState.Deleted),
                read("y-dental", day(8), amount(-95), "Dentist"),
                read("y-merged", day(9), amount(-95), "Bright Smile", importIdentifier = identifier("merged"), state = ReadState.Merged("y-dental")),
            ),
        )
        check(slice.comparison.readAgain() is ComparisonStart.Ran) { "The invented Comparison of this scenario did not run." }
        slice.clock.advance(Duration.ofMinutes(10))
        store("not-compared", checking, day(15), amount(-32), "Harbor Bakery", firstSeenAt = now)
    }

    private fun day(dayOfOctober: Int): LocalDate = LocalDate.of(2026, 10, dayOfOctober)
}
