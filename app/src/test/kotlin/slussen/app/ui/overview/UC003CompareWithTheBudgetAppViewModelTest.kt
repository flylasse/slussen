package slussen.app.ui.overview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.components.FetchButtonUi
import slussen.app.ui.components.FetchResultKind
import slussen.app.ui.components.FetchResultUi
import slussen.core.connection.PairOutcome
import slussen.core.operation.OperationKind
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.ServiceKey
import slussen.core.port.StableReferenceKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankTransaction
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.Duration
import java.time.LocalDate

/**
 * "Read the Budget App again" (UC-003 trigger, FR-040) on the line of the Budget App of the Overview and of every Budget
 * tab (`overview.html#read-one-failed`, `budget.html#ynab-read-states`, `#budget-app-unreadable`, `#budget-app-config`,
 * `#first-read-failed`): its state at its own control before anything is sent, its result in the same place, a failure
 * as an answer (NFR-023, UX-12), and the statuses of each Budget as of its last successful read (A1 step 2, S-T-08).
 * Every name and amount is invented.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-003: Compare With the Budget App, the screens")
class UC003CompareWithTheBudgetAppViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val name = ExampleData.BUDGET_APP_NAME
    private var nextId = 7000L

    /** Household (USD) with Kestrel Checking, Summer House (EUR) with Aurora Current, Care Fund (USD) without a Bank Account. */
    private fun budgets(): Long = runBlocking {
        runBlocking { slice.enterCredentials() }
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, setOf("budget-household", "budget-summer", "budget-care"), slice.clock.instant())
        val kestrel = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, kestrel, slice.clock.instant())),
        )
        val budgetAccounts = slice.connectionStore.budgets().flatMap { it.budgetAccounts }
        val checking = (
            slice.connect.add(
                slice.offeredStore.offered().single().id, budgetAccounts.single { it.name == "Checking" }.id, "Kestrel Checking",
                BankAccountType.Checking, BankChoice.Known(kestrel), null,
            ) as PairOutcome.Added
            ).bankAccountId
        slice.connectionStore.addBankAccount(
            slussen.core.port.NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-000000000701",
                bank = BankChoice.Named("Aurora Bank"),
                providerBankId = null,
                dataProviderId = slice.feedsId,
                fileFormatId = null,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = "Aurora Current",
                currency = EUR,
                type = BankAccountType.Checking,
                providerReference = "aurora-current",
                bankAccountFingerprint = null,
                identifiedAt = slice.clock.instant(),
                startDate = null,
                budgetAccountId = budgetAccounts.single { it.name == "Current" }.id,
            ),
        )
        checking
    }

    /** A Bank Transaction a Fetch stored at [firstSeenAt], not compared yet (S-T-08). */
    private fun stored(bankAccountId: Long, firstSeenAt: java.time.Instant = slice.clock.instant().minus(Duration.ofHours(1))) {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id, bankAccountId = bankAccountId, stableReference = "TX-$id", referenceKind = StableReferenceKind.Supplied,
            occurrenceNumber = 1, importIdentifier = "SLS1-planted-$id", bookedDate = LocalDate.of(2026, 10, 14),
            amount = amount(-10), currency = USD, payee = "Corner Grocer", memo = null, firstSeenAt = firstSeenAt, status = null,
            transferId = null,
        )
    }

    /** The Budget App answers every Budget but Household, which does not answer in the request timeout. */
    private fun householdDoesNotAnswer() {
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") ReadResult.TransientFailure(FailureKind.Timeout)
            else ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("other-${asked.budgetReference}")))
        }
    }

    private fun overview() = OverviewViewModel(fixture.services).also { it.load() }

    private fun budgetTab(budgetName: String) = BudgetViewModel(
        fixture.services,
        runBlocking { slice.connectionStore.budgets().single { it.name == budgetName }.id },
    ).also { it.load() }

    private val ready = FetchButtonUi("Read $name again", enabled = true, running = false)
    private val enterCredentials = RemedyAction("Enter credentials", available = true, service = ServiceKey.budgetApp, note = null)

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Trigger, step 7 (FR-040, budget.html#ynab-read-states): Read the Budget App again on the Overview reads every Budget without a Bank Fetch; its line shows that it reads before anything is sent, then the time of the read and the statuses as of it")
    fun readAgainOnTheOverview() {
        val checking = budgets()
        stored(checking)
        val viewModel = overview()
        val before = viewModel.state.value!!
        assertEquals("$name · not read yet", before.budgetAppLine.status)
        assertEquals(ready, before.budgetAppLine.button)
        val household = before.budgets.single { it.name == "Household" }
        assertEquals("No Transaction Statuses yet: $name has not been read for this Budget", household.statuses)
        assertEquals("Eligible 0 · waiting for a decision 0 · not compared yet 1", household.counts)
        assertNull(before.budgets.single { it.name == "Care Fund" }.statuses)

        var whileReading: FetchButtonUi? = null
        slice.budgetApp.reader.changesAnswer = { asked ->
            whileReading = viewModel.state.value!!.budgetAppLine.button
            ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("k-${asked.budgetReference}")))
        }
        viewModel.readAgain()

        assertEquals(FetchButtonUi("Reading $name · no Bank Fetch", enabled = false, running = true), whileReading)
        val after = viewModel.state.value!!
        assertEquals(
            FetchResultUi("$name read 14:00 · statuses as of 14:00", "Transaction Statuses changed: 1 · no Bank Fetch", FetchResultKind.Fetched, 1),
            after.budgetAppLine.result,
        )
        assertEquals(ready, after.budgetAppLine.button)
        assertEquals("$name · read 14:00", after.budgetAppLine.status)
        assertNull(after.budgetAppLine.remedy)
        assertEquals("Statuses as of 14:00", after.budgets.single { it.name == "Household" }.statuses)
        assertNull(after.budgets.single { it.name == "Household" }.readAgainNote)
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023 (ADR-007, budget.html#ynab-read-states): pressed while another operation runs, Read the Budget App again shows at once what it waits for, then reads")
    @OptIn(DelicateCoroutinesApi::class)
    fun readAgainWaitsForTheRunningOperation() {
        budgets()
        val viewModel = overview()
        val gate = CompletableDeferred<Unit>()
        GlobalScope.launch(Dispatchers.Unconfined) {
            slice.coordinator.run(OperationKind.Fetch, StartedBy.Operator) { permit ->
                permit.workingOn("Kestrel Feeds")
                gate.await()
            }
        }

        viewModel.readAgain()
        assertEquals(
            FetchButtonUi("Read $name again · waits for: Fetching Kestrel Feeds", enabled = false, running = true),
            viewModel.state.value!!.budgetAppLine.button,
        )
        assertNull(viewModel.state.value!!.budgetAppLine.result)

        gate.complete(Unit)
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { while (viewModel.state.value!!.budgetAppLine.result == null) kotlinx.coroutines.delay(10) }
        }
        assertEquals(FetchResultKind.Fetched, viewModel.state.value!!.budgetAppLine.result!!.kind)
        assertEquals(ready, viewModel.state.value!!.budgetAppLine.button)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-023", "NFR-016"])
    @DisplayName("A1 step 2 (overview.html#read-one-failed, S-T-08): with one Budget not read, the line names it; its statuses keep the time of its last successful read with the read that failed, its Bank Transactions stored since are not compared yet, and the remedy is to read again; the other Budgets show this read")
    fun oneBudgetNotRead() {
        val checking = budgets()
        val viewModel = overview()
        viewModel.readAgain()
        slice.clock.advance(Duration.ofHours(2))
        stored(checking, firstSeenAt = slice.clock.instant())
        householdDoesNotAnswer()

        viewModel.readAgain()

        val ui = viewModel.state.value!!
        assertEquals(
            FetchResultUi(
                "$name read 16:00 · Household not read: no answer within the request timeout, an Operator Setting",
                "Household: statuses as of 14:00 · Read $name again to bring them up to date; nothing else is needed.",
                FetchResultKind.NotFetched,
                2,
            ),
            ui.budgetAppLine.result,
        )
        assertEquals("$name · read 16:00 · Household not read", ui.budgetAppLine.status)
        val household = ui.budgets.single { it.name == "Household" }
        assertEquals(
            "Statuses as of 14:00, its last successful read · the read of 16:00 failed: no answer within the request timeout, an Operator Setting",
            household.statuses,
        )
        assertEquals("Eligible 0 · waiting for a decision 0 · not compared yet 1", household.counts)
        assertEquals("Read $name again to bring them up to date; nothing else is needed.", household.readAgainNote)
        assertEquals("Statuses as of 16:00", ui.budgets.single { it.name == "Summer House" }.statuses)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-023"])
    @DisplayName("A1, S-B-79 (C2 review item 6): a Budget the Budget App refuses for a reason of its own is not read, no status changes, and the Operator is told the reason in the Budget App's line and the Budget's statuses")
    fun refusalOfTheBudgetAppTellsItsReason() {
        val checking = budgets()
        stored(checking)
        val reasons = mapOf(
            FailureKind.RefusedSubscriptionLapsed to "the service refused: the subscription of its account has lapsed",
            FailureKind.RefusedTrialEnded to "the service refused: the trial of its account has ended",
            FailureKind.RefusedDataLimitReached to "the service refused: the request would exceed one of its data limits",
            FailureKind.RefusedAddressUnconfirmed to "the service refused: the email address of its account is not confirmed",
            FailureKind.RefusedOtherReason to "the service refused the request for a reason of its own",
        )
        val viewModel = overview()
        reasons.forEach { (kind, text) ->
            slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(kind) }
            viewModel.readAgain()
            val ui = viewModel.state.value!!
            assertEquals("$name not read 14:00: $text", ui.budgetAppLine.result!!.text)
            assertEquals(FetchResultKind.Failed, ui.budgetAppLine.result!!.kind)
            assertEquals("No Transaction Statuses yet: the read of 14:00 failed: $text", ui.budgets.single { it.name == "Household" }.statuses)
            assertEquals("Eligible 0 · waiting for a decision 0 · not compared yet 1", ui.budgets.single { it.name == "Household" }.counts)
        }
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-023"])
    @DisplayName("A1 (budget.html#ynab-read-states, #first-read-failed): when no Budget can be read, the line says so with its time and the statuses it keeps; before any successful read there are none, and nothing is counted")
    fun noBudgetRead() {
        val checking = budgets()
        stored(checking)
        slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(FailureKind.ServiceUnavailable) }
        val viewModel = overview()

        viewModel.readAgain()

        val ui = viewModel.state.value!!
        assertEquals(
            FetchResultUi(
                "$name not read 14:00: the service is unavailable",
                "Household: no statuses yet · Summer House: no statuses yet · The stored statuses are kept; read again at " +
                    "any time. No Bank Fetch is needed.",
                FetchResultKind.Failed,
                1,
            ),
            ui.budgetAppLine.result,
        )
        assertEquals("$name · read failed 14:00", ui.budgetAppLine.status)
        assertEquals("No Transaction Statuses yet: the read of 14:00 failed: the service is unavailable", ui.budgets.single { it.name == "Household" }.statuses)
        assertEquals("Eligible 0 · waiting for a decision 0 · not compared yet 1", ui.budgets.single { it.name == "Household" }.counts)
        assertEquals(ready, ui.budgetAppLine.button)

        slice.budgetApp.reader.changesAnswer = { ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("k-1"))) }
        viewModel.readAgain()
        slice.clock.advance(Duration.ofMinutes(5))
        slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(FailureKind.ConnectionLost) }
        viewModel.readAgain()
        assertEquals("$name not read 14:05: the connection was lost", viewModel.state.value!!.budgetAppLine.result!!.text)
        assertEquals("$name · last read 14:00 · read failed 14:05", viewModel.state.value!!.budgetAppLine.status)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-023"])
    @DisplayName("A1 (review 2b): when no Budget is read, the line claims no one time for every Budget: each keeps its own last successful read, and each failure keeps its own kind, a read not completed included")
    fun noBudgetReadNamesEachBudget() {
        budgets()
        val viewModel = overview()
        viewModel.readAgain()
        slice.clock.advance(Duration.ofHours(2))
        householdDoesNotAnswer()
        viewModel.readAgain()
        slice.clock.advance(Duration.ofHours(1))
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") ReadResult.TransientFailure(FailureKind.Timeout)
            else ReadResult.StoppedByGate(slussen.pluginapi.request.RefusalReason.OperationEnded)
        }

        viewModel.readAgain()

        val ui = viewModel.state.value!!
        assertEquals(
            FetchResultUi(
                "$name not read 17:00 · Household: no answer within the request timeout, an Operator Setting · " +
                    "Summer House: the read was not completed",
                "Household: statuses as of 14:00 · Summer House: statuses as of 16:00 · The stored statuses are kept; read " +
                    "again at any time. No Bank Fetch is needed.",
                FetchResultKind.Failed,
                3,
            ),
            ui.budgetAppLine.result,
        )
        assertEquals("$name · last read 16:00 · read failed 17:00", ui.budgetAppLine.status)
        assertEquals(
            "Statuses as of 16:00, its last successful read · the read of 17:00 failed: the read was not completed",
            ui.budgets.single { it.name == "Summer House" }.statuses,
        )
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A2: Budget App Refuses Slussen's Credentials", requirements = ["NFR-023"])
    @DisplayName("A2 (review 2a): while the Budget App's credentials are missing or refused, a Budget whose statuses are not current names entering them as the next action, on the Overview and on its tab, never only a read")
    fun credentialsAreTheNextAction() {
        val checking = budgets()
        stored(checking)
        runBlocking { slice.credentials.remove(ServiceKey.budgetApp) }
        val missing = "Enter the credentials of $name in Settings, then read $name again; nothing else is needed."
        assertEquals(missing, overview().state.value!!.budgets.single { it.name == "Household" }.readAgainNote)
        assertEquals(missing, budgetTab("Household").state.value!!.readAgainNote)

        runBlocking { slice.enterCredentials() }
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused
        overview().readAgain()

        val refused = "$name refused Slussen's credentials: enter them again in Settings, then read $name again; nothing else is needed."
        assertEquals(refused, overview().state.value!!.budgets.single { it.name == "Household" }.readAgainNote)
        assertEquals(refused, budgetTab("Household").state.value!!.readAgainNote)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023 (review 4a): when an operation another screen started ends, a screen shows the stored status again: the Overview shows the read the Household tab made")
    fun otherScreensShowTheEndedRead() {
        budgets()
        val viewModel = overview()
        assertEquals("$name · not read yet", viewModel.state.value!!.budgetAppLine.status)

        budgetTab("Household").readAgain()

        val ui = viewModel.state.value!!
        assertEquals("$name · read 14:00", ui.budgetAppLine.status)
        assertEquals("Statuses as of 14:00", ui.budgets.single { it.name == "Household" }.statuses)
        assertEquals(ready, ui.budgetAppLine.button)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023 (review 4b): when the stored status cannot be read after the read of the Budget App, the line answers so on the Overview and on a Budget tab, and the app does not crash")
    fun statusThatCannotBeReadIsAnAnswer() {
        budgets()
        val viewModel = overview()
        val tab = budgetTab("Household")
        slice.budgetApp.reader.changesAnswer = { asked ->
            slice.importStatusStore.failsToRead = true
            ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("k-${asked.budgetReference}")))
        }

        viewModel.readAgain()
        assertEquals(
            "The status stored on this device could not be read; nothing was changed by it. Open the screen again to see it.",
            viewModel.state.value!!.budgetAppLine.result!!.detail,
        )
        assertEquals(ready, viewModel.state.value!!.budgetAppLine.button)

        tab.readAgain()
        assertEquals(
            "The status stored on this device could not be read; nothing was changed by it. Open the screen again to see it.",
            tab.state.value!!.budgetAppLine.result!!.detail,
        )
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A2: Budget App Refuses Slussen's Credentials", requirements = ["NFR-023"])
    @DisplayName("A2 (budget.html#budget-app-config): credentials refused by the Budget App is answered at the line with the remedy to enter them again; credentials missing likewise")
    fun credentialsRefusedOrMissing() {
        budgets()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused
        val viewModel = overview()

        viewModel.readAgain()

        assertEquals(
            FetchResultUi("Credentials refused by $name · enter them again · statuses kept", null, FetchResultKind.Failed, 1),
            viewModel.state.value!!.budgetAppLine.result,
        )
        assertEquals(enterCredentials, viewModel.state.value!!.budgetAppLine.remedy)

        runBlocking { slice.credentials.remove(ServiceKey.budgetApp) }
        viewModel.readAgain()
        assertEquals(
            FetchResultUi("$name not read: credentials missing · enter them", null, FetchResultKind.NotFetched, 2),
            viewModel.state.value!!.budgetAppLine.result,
        )
        assertEquals(enterCredentials, viewModel.state.value!!.budgetAppLine.remedy)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023: with no Bank Account mapped to a served Budget there is nothing to compare, and the line of a Budget tab names the next action; while an Upload Plan awaits confirmation the read is refused with the reason")
    fun nothingToCompareOrRefused() {
        val empty = UiFixture()
        runBlocking {
            empty.slice.enterCredentials()
            val listed = ExampleData.budgetsRead().map { budget ->
                BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
            }
            empty.slice.connectionStore.serveBudgets(listed, setOf("budget-household"), empty.slice.clock.instant())
        }
        // The Overview shows only the first-run card without a Bank Account (UC-008 BR-011); the Budget tab has the line.
        val household = runBlocking { empty.slice.connectionStore.budgets().single { it.name == "Household" }.id }
        val viewModel = BudgetViewModel(empty.services, household).also { it.load() }
        assertEquals("$name · nothing to read: no Bank Account of this Budget is compared", viewModel.state.value!!.budgetAppLine.status)
        viewModel.readAgain()
        assertEquals(
            FetchResultUi("Nothing to compare: no Bank Account is mapped to a Budget · map one in Account Mappings", null, FetchResultKind.NotFetched, 1),
            viewModel.state.value!!.budgetAppLine.result,
        )

        budgets()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val refused = overview()
        refused.readAgain()
        assertEquals(
            FetchResultUi("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", null, FetchResultKind.NotFetched, 1),
            refused.state.value!!.budgetAppLine.result,
        )
        assertEquals(ready, refused.state.value!!.budgetAppLine.button)
        assertEquals(0, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-023"])
    @DisplayName("NFR-023: a read that throws is answered at its line as not read, with the stored statuses kept, and the action can be taken again; the app does not crash")
    fun failureIsAnAnswer() {
        budgets()
        slice.budgetApp.reader.changesAnswer = { error("planted failure") }
        val viewModel = overview()

        viewModel.readAgain()

        assertEquals(
            FetchResultUi(
                "$name not read: the read could not be completed",
                "The stored statuses are kept; read again at any time. No Bank Fetch is needed.",
                FetchResultKind.Failed,
                1,
            ),
            viewModel.state.value!!.budgetAppLine.result,
        )
        assertEquals(ready, viewModel.state.value!!.budgetAppLine.button)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", requirements = ["NFR-023"])
    @DisplayName("A1 (budget.html#budget-app-read, #budget-app-unreadable, #first-read-failed): a Budget tab has its own line of the Budget App with the same action; it shows this Budget's last read and the read that failed since, its statuses and its Bank Transactions not compared yet")
    fun budgetTabReadsAgain() {
        val checking = budgets()
        val tab = budgetTab("Household")
        assertEquals("$name · not read yet", tab.state.value!!.budgetAppLine.status)

        tab.readAgain()
        assertEquals("$name read 14:00 · statuses as of 14:00", tab.state.value!!.budgetAppLine.result!!.text)
        assertEquals("$name · read 14:00", tab.state.value!!.budgetAppLine.status)
        assertEquals("Statuses as of 14:00", tab.state.value!!.statuses)

        slice.clock.advance(Duration.ofHours(2))
        stored(checking, firstSeenAt = slice.clock.instant())
        householdDoesNotAnswer()
        tab.readAgain()

        val ui = tab.state.value!!
        assertEquals("$name · last read 14:00 · read failed 16:00", ui.budgetAppLine.status)
        assertEquals(
            "Statuses as of 14:00, its last successful read · the read of 16:00 failed: no answer within the request timeout, an Operator Setting",
            ui.statuses,
        )
        assertEquals(
            "Not compared yet: 1 Bank Transaction, stored since the last successful read of $name; it has no Transaction Status " +
                "and is counted neither as Eligible nor as waiting for a decision.",
            ui.notComparedYet,
        )
        assertEquals("Read $name again to bring them up to date; nothing else is needed.", ui.readAgainNote)
        assertEquals("Checking · Eligible 0 · waiting for a decision 0 · not compared yet 1", ui.bankAccounts.single().let { "${it.type} · ${it.counts}" })
        assertEquals(0, slice.feeds.fetches)
    }
}
