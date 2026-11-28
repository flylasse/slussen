package slussen.persistence.start

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slussen.core.connection.BudgetsRead
import slussen.core.fetch.FetchStart
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.NewBankAccount
import slussen.core.port.TransactionStatus
import slussen.core.settings.StartOverOutcome
import slussen.core.settings.StartOverResult
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.provider.BankAccountResult
import slussen.core.port.BankChoice
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.MutableClock
import slussen.testing.TestSlice
import java.io.File
import java.time.LocalDate

/**
 * FR-107's last clause, "afterwards nothing is uploaded before First Upload Matching", as far as a JVM test can run it:
 * Start Over itself on the core's fakes with the start intent on real files (ADR-002 §3 O-17 steps 1-2), the start
 * sequence that deletes and starts as a new installation (steps 4-5), and a new installation that opens the First Upload
 * Matching the outcome names, fetches the same Bank Transactions again and compares them with the Budget App that holds
 * the earlier Uploads. Every name, amount and identifier is invented (C-005).
 */
@DisplayName("UC-010: Adjust Settings, A12 Start Over and First Upload Matching")
class UC010StartOverFirstUploadMatchingTest {
    @TempDir
    lateinit var root: File

    private val files by lazy { StartFiles(File(root, "databases").apply { mkdirs() }, File(root, "no_backup").apply { mkdirs() }) }
    private val readable = DatabaseCheck { true }
    private val oct14 = LocalDate.of(2026, 10, 14)

    private fun identifier(reference: String) =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    /** Credentials, Budgets served, Kestrel Checking mapped, and the Data Provider and the Budget App answering. */
    private suspend fun TestSlice.inUse(): Long {
        enterCredentials()
        val read = connect.readBudgets() as BudgetsRead.Listed
        connect.chooseBudgets(read.budgets, setOf("budget-household"))
        val checking = connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        val id = (
            connectionStore.addBankAccount(
                NewBankAccount(
                    generatedIdentifier = "00000000-0000-4000-8000-%012d".format(System.identityHashCode(this).toLong()),
                    bank = BankChoice.Named("Kestrel Bank"), providerBankId = null, dataProviderId = feedsId, fileFormatId = null,
                    balanceDataProviderId = null, balanceProviderReference = null, name = "Kestrel Checking", currency = USD,
                    type = BankAccountType.Checking, providerReference = "acct-4410", bankAccountFingerprint = null,
                    identifiedAt = clock.instant(), startDate = null, budgetAccountId = checking,
                ),
            ) as AddOutcome.Added
            ).bankAccountId
        val transactions = listOf(
            ProviderTransaction.Interpretable("TX-UPLOADED-1", oct14, amount(-64, 180), USD, "Corner Grocer", null, true),
            ProviderTransaction.Interpretable("TX-UPLOADED-2", oct14, amount(-12), USD, "Harbor Fuel", null, true),
        )
        feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed, emptyList(),
                listOf(DeliveredBankAccount("acct-4410", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Read(transactions, amount(100)))),
            )
        }
        // The Budget App holds what Slussen uploaded before Start Over, under the Import Identifiers no build can change (ADR-008).
        budgetApp.reader.changesAnswer = { asked ->
            val held = if (asked.budgetReference == "budget-household") {
                listOf("TX-UPLOADED-1" to amount(-64, 180), "TX-UPLOADED-2" to amount(-12)).mapIndexed { i, (reference, money) ->
                    BudgetAppTransactionRead("ynab-$i", "ba-checking", oct14, money, "Corner Grocer", null, identifier(reference), EntryKind.Imported, null, ReadState.Present, true, true)
                }
            } else {
                emptyList()
            }
            ReadResult.Answered(ChangeSet(held, ChangeCursor("k-${asked.budgetReference}")))
        }
        return id
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4, UC-006 BR-017: after Start Over itself, with the same Bank Transactions fetched again, a First Upload Matching holds every Upload back and those Slussen had uploaded are Already In Budget App, never Eligible")
    fun afterStartOverNothingIsEligibleBeforeFirstUploadMatching() = runTest {
        val old = TestSlice()
        val oldAccount = old.inUse()
        assertTrue(old.fetch.fetch(old.feedsKey) is FetchStart.Ran)
        assertEquals(2, old.importStatusStore.bankTransactionsOf(oldAccount).size)
        files.main.writeText("the old slussen.db with its Sent Records")
        files.device.writeText("the old device.db with its credentials")
        files.installationId.writeText("old-installation")

        // ADR-002 §3 O-17 steps 1-3: Start Over itself, recorded in the start intent on real files.
        val restarted = CompletableDeferred<StartOverResult>()
        val answered = CompletableDeferred<StartOverOutcome>()
        backgroundScope.launch {
            answered.complete(
                old.startOverWith(store = FileStartOverStore(files)) { false }
                    .startOver(restart = { restarted.complete(it); awaitCancellation() }),
            )
        }
        select<Unit> {
            restarted.onAwait { }
            answered.onAwait { }
        }
        assertTrue(restarted.isCompleted, "Start Over was recorded and the process ended")

        // Steps 4-5: the next start deletes and starts as a new installation.
        val outcome = StartSequence(files, readable, readable, readable, MutableClock()).run()
        assertTrue(outcome.startedOver)
        assertEquals(FirstUploadMatchingCause.NewInstallation, outcome.firstUploadMatchingCause)
        assertTrue(!files.main.exists() && !files.device.exists())

        val fresh = TestSlice(startKind = outcome.kind)
        fresh.firstUploadMatchingStore.open(outcome.firstUploadMatchingCause!!, fresh.clock.instant())
        val account = fresh.inUse()
        assertNotNull(fresh.status.overview().firstUploadMatching, "before any Comparison a First Upload Matching holds every Upload back")

        assertTrue(fresh.fetch.fetch(fresh.feedsKey) is FetchStart.Ran)

        assertEquals(
            listOf(TransactionStatus.AlreadyInBudgetApp, TransactionStatus.AlreadyInBudgetApp),
            fresh.importStatusStore.bankTransactionsOf(account).map { it.status },
        )
        val household = fresh.connectionStore.budgets().single { it.name == "Household" }
        assertEquals(0, fresh.status.workspace(household.id).summary.notYetUploaded.eligible)
        val open = fresh.firstUploadMatchingStore.openChecks().single()
        assertEquals(FirstUploadMatchingCause.NewInstallation, open.cause)
        assertNull(open.confirmedAt, "Uploads wait until the Operator confirms First Upload Matching (ADR-007 §3)")
    }
}
