package slussen.core.status

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredTransactionCounts
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.TestSlice

/**
 * The number of the New Transaction Notification (UC-001 BR-014, S-B-39), worked out in `:core` from stored data only:
 * the sum, over every Bank Account that is not a Retired Account, of the counts UC-008 BR-003 shows next to it, so that
 * the notification and the screens never disagree.
 */
@DisplayName("UC-001: Fetch Transactions, the number of the New Transaction Notification")
class UC001NewTransactionNotificationTest {
    private val slice = TestSlice()
    private var nextId = 2000L
    private val ids = mutableMapOf<String, Long>()

    /** Household (USD) and Summer House (EUR) served, Care Fund not served. */
    private suspend fun budgets(served: Set<String> = setOf("budget-household", "budget-summer")) {
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, served, slice.clock.instant())
    }

    private suspend fun bankAccount(name: String, budgetAccountName: String, currency: CurrencyCode = USD, retired: Boolean = false) {
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
            type = BankAccountType.Checking,
            startDate = null,
            providerReference = "ref-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = slice.clock.instant(),
            retired = retired,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount.id, null),
        )
        ids[name] = id
    }

    private fun counts(name: String, eligible: Int, waiting: Int, notComparedYet: Int = 0) {
        slice.importStatusStore.counts += StoredTransactionCounts(ids.getValue(name), eligible, waiting, notComparedYet)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: the number is the Eligible Transactions plus those waiting for a decision of every Bank Account, the same sum the Overview shows")
    fun numberIsTheSumTheOverviewShows() = runTest {
        budgets()
        bankAccount("Kestrel Checking", "Checking")
        bankAccount("Pinecrest Rewards Card", "Rewards Card")
        bankAccount("Aurora Current", "Current", currency = EUR)
        counts("Kestrel Checking", eligible = 5, waiting = 6)
        counts("Pinecrest Rewards Card", eligible = 2, waiting = 1)
        counts("Aurora Current", eligible = 0, waiting = 3)

        val number = slice.status.newTransactionNumber()

        assertEquals(17, number)
        val overview = slice.status.overview()
        assertEquals(overview.budgets.sumOf { it.notYetUploaded.eligible + it.notYetUploaded.waitingForDecision }, number)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: a Retired Account is not counted, and a Bank Transaction not compared yet is in neither count")
    fun retiredAndNotComparedYetAreNotCounted() = runTest {
        budgets()
        bankAccount("Kestrel Checking", "Checking")
        bankAccount("Old Checking", "Rewards Card", retired = true)
        counts("Kestrel Checking", eligible = 1, waiting = 0, notComparedYet = 4)
        counts("Old Checking", eligible = 7, waiting = 2)

        assertEquals(1, slice.status.newTransactionNumber())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: with nothing waiting the number is 0, so the notification disappears")
    fun nothingWaitingIsZero() = runTest {
        budgets()
        bankAccount("Kestrel Checking", "Checking")

        assertEquals(0, slice.status.newTransactionNumber())
        assertEquals(0, NewTransactionNumber.of(emptyList(), emptyList(), emptyList()))
    }
}
