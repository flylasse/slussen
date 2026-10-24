package slussen.app.ui.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.components.FetchButtonUi
import slussen.app.ui.components.FetchResultKind
import slussen.app.ui.components.FetchResultUi
import slussen.app.ui.overview.OverviewViewModel
import slussen.core.port.BackOffCause
import slussen.core.port.CoordinatorMode
import slussen.core.port.ServiceKey
import slussen.core.port.StoredBackOff
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.Duration

/**
 * Fetch Transactions (UC-001), the screens: the Fetch action on a Data Provider's page and the Fetch of
 * all Data Providers on the Overview (`connections.html`, `overview.html`, `budget.html#request-limit-warning`). The
 * rules themselves are tested in `UC001FetchTransactionsTest` of `:core`.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-001: Fetch Transactions, the screens")
class UC001FetchTransactionsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    private fun page(service: ServiceKey) = ServicePageViewModel(fixture.services, service).also { it.load() }

    private fun ServicePageViewModel.ui() = state.value!!

    private fun answerFeeds(outcome: SourceOutcome = SourceOutcome.Completed, accounts: Int = 3) {
        val listed = listOf(
            DeliveredBankAccount("acct-4410", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Read(emptyList(), amount(4140, 50))),
            DeliveredBankAccount("acct-7731", "Kestrel Bank", "Reserve Saver", "savings", USD, null, BankAccountResult.Read(emptyList(), amount(12000))),
            DeliveredBankAccount("acct-1182", "Kestrel Bank", "Rewards", "credit card", USD, null, BankAccountResult.Read(emptyList(), amount(-1268, 400))),
        ).take(accounts)
        slice.feeds.fetchAnswer = { FetchResponse(outcome, emptyList(), listed) }
    }

    /**
     * UC-008 A10, BR-011: the Overview offers the Fetch of all Data Providers only from the first added Bank Account on;
     * here a Bank Account of the File Format, so that no Data Provider's delivery changes.
     */
    private fun aBankAccountIsAdded() = runBlocking {
        val listed = ExampleData.budgetsRead().map { budget ->
            slussen.core.port.BudgetToServe(
                budget.reference, budget.name, budget.currency,
                budget.budgetAccounts.map { slussen.core.port.BudgetAccountToStore(it.reference, it.name, it.balance) },
            )
        }
        slice.connectionStore.serveBudgets(listed, setOf("budget-care"), slice.clock.instant())
        val health = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id
        slice.connect.addFromTransactionFiles(
            slice.plugins.fileFormats.single().id, slussen.core.port.BankAccountType.Savings,
            slussen.core.port.BankChoice.Named("Harbor Health Trust"), null, health, "Harbor Health Savings", null, ExampleData.USD,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Step 1: a Data Provider's page offers the Fetch action; the Budget App's page does not; opening a page fetches nothing")
    fun dataProviderPageOffersTheFetch() {
        assertTrue(page(slice.feedsKey).ui().canFetch)
        assertFalse(page(ServiceKey.budgetApp).ui().canFetch)
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Steps 1-6, 11 (P9-16): fetching from the page shows the result of the tap, Fetched with its time, and what was fetched; the page shows the last Fetch")
    fun fetchFromThePageShowsTheResult() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        val page = page(slice.feedsKey)
        assertEquals(FetchButtonUi("Fetch Kestrel Feeds", enabled = true, running = false), page.ui().fetchButton)
        assertNull(page.ui().fetchResult)

        page.fetch()

        assertEquals(
            FetchResultUi("Fetched 14:00", "Bank Accounts offered: 3 · not added: 3", FetchResultKind.Fetched, tap = 1),
            page.ui().fetchResult,
        )
        assertEquals("Connected · last Fetch 2026-10-15 14:00", page.ui().status)
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A1: a Back-off shows waiting for the provider with the time it ends, and no retry that skips the wait")
    fun backOffIsShown() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        slice.importStatusStore.backOffList += StoredBackOff(
            slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), slice.clock.instant().plus(Duration.ofHours(5)),
        )
        val page = page(slice.feedsKey)

        page.fetch()

        assertEquals(
            FetchResultUi("Not fetched: waiting for the provider until 2026-10-15 19:00", null, FetchResultKind.NotFetched, tap = 1),
            page.ui().fetchResult,
        )
        assertNull(page.ui().fetchWarning)
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("A2 (P9-16): a tap within the Fetch Cooldown gets its own result: not fetched again, the data comes from the last Fetch, fresh until a Fetch contacts the Data Provider again")
    fun cooldownIsShown() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        val page = page(slice.feedsKey)
        page.fetch()
        slice.clock.advance(Duration.ofMinutes(20))

        page.fetch()

        assertEquals(
            FetchResultUi(
                "Not fetched again: the Fetch of 14:00 is still fresh until 15:00 (Fetch Cooldown)", null, FetchResultKind.Cooldown, tap = 2,
            ),
            page.ui().fetchResult,
        )
        page.fetch()
        assertEquals(3, page.ui().fetchResult!!.tap)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007"])
    @DisplayName("A3: at the Request Limit the page shows Request Limit reached with the time it frees up")
    fun requestLimitIsShown() {
        runBlocking { repeat(20) { slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) } }
        val page = page(slice.feedsKey)

        page.fetch()

        assertEquals(
            FetchResultUi("Not fetched: Request Limit reached · frees up from 2026-10-16 14:00", null, FetchResultKind.NotFetched, tap = 1),
            page.ui().fetchResult,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", businessRules = ["BR-005"])
    @DisplayName("A6: a failed Fetch is shown with its time, the stored data kept, and Fetch again offered")
    fun failureIsShown() {
        runBlocking { slice.enterCredentials() }
        answerFeeds(SourceOutcome.TransientFailure(FailureKind.Timeout), accounts = 0)
        val page = page(slice.feedsKey)

        page.fetch()

        assertEquals(
            FetchResultUi(
                "Failed 14:00: no answer within the request timeout, an Operator Setting",
                "Stored data unchanged · you can fetch again at any time",
                FetchResultKind.Failed,
                tap = 1,
            ),
            page.ui().fetchResult,
        )
        assertTrue(page.ui().canFetch)
        assertTrue(page.ui().fetchButton!!.enabled)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials")
    @DisplayName("A7: refused credentials show credentials refused with the action that enters them again")
    fun refusedCredentialsAreShown() {
        runBlocking { slice.enterCredentials() }
        answerFeeds(SourceOutcome.CredentialsRefused, accounts = 0)
        val page = page(slice.feedsKey)

        page.fetch()

        assertEquals(
            FetchResultUi("Not fetched: credentials refused · enter them again", null, FetchResultKind.Failed, tap = 1),
            page.ui().fetchResult,
        )
        assertEquals("Blocked: credentials refused · Enter credentials", page.ui().blocked)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Precondition: without credentials the page shows credentials missing; while an Upload Plan awaits confirmation it says why the Fetch is unavailable")
    fun missingCredentialsAndOpenPlanAreShown() {
        val page = page(slice.feedsKey)
        page.fetch()
        assertEquals(
            FetchResultUi("Not fetched: credentials missing · enter them", null, FetchResultKind.NotFetched, tap = 1),
            page.ui().fetchResult,
        )

        runBlocking { slice.enterCredentials() }
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        page.fetch()
        assertEquals(
            FetchResultUi(
                "Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", null, FetchResultKind.NotFetched, tap = 2,
            ),
            page.ui().fetchResult,
        )
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A16: Operator Fetches Despite a Request Limit Warning", businessRules = ["BR-015"])
    @DisplayName("A16: during a Request Limit warning the page asks to confirm with the warning, its end and the risk; cancelling sends nothing, confirming fetches")
    fun warningAsksToConfirm() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        slice.importStatusStore.backOffList += StoredBackOff(
            slice.feedsId, null, BackOffCause.RequestLimitWarning, slice.clock.instant(), slice.clock.instant().plus(Duration.ofHours(24)),
        )
        val page = page(slice.feedsKey)

        page.fetch()

        val warning = page.ui().fetchWarning!!
        assertEquals("Fetch Kestrel Feeds despite its warning?", warning.title)
        assertEquals(
            "Kestrel Feeds warned at 14:00 that Slussen is near its Request Limit. Scheduled Fetches leave it alone until " +
                "2026-10-16 14:00 (the Request Limit warning pause, an Operator Setting).",
            warning.text,
        )
        assertEquals("Further requests can get Slussen's access to Kestrel Feeds disabled.", warning.risk)
        page.cancelFetch()
        assertNull(page.ui().fetchWarning)
        assertEquals(0, slice.feeds.fetches)

        page.fetch()
        page.confirmFetch()

        assertNull(page.ui().fetchWarning)
        assertEquals(1, slice.feeds.fetches)
        assertEquals(
            FetchResultUi("Fetched 14:00", "Bank Accounts offered: 3 · not added: 3", FetchResultKind.Fetched, tap = 3),
            page.ui().fetchResult,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A15: Data Provider Warns About Its Request Limit", businessRules = ["BR-015"])
    @DisplayName("A15: a warning during the Fetch is shown with its end and the risk")
    fun warningDuringTheFetchIsShown() {
        runBlocking { slice.enterCredentials() }
        answerFeeds(SourceOutcome.RequestLimitWarning)
        val page = page(slice.feedsKey)

        page.fetch()

        assertEquals(
            FetchResultUi(
                "Fetched 14:00",
                "Bank Accounts offered: 3 · not added: 3 · Kestrel Feeds warned that Slussen is near its Request Limit · " +
                    "Scheduled Fetches leave it alone until 2026-10-16 14:00 · further requests can get Slussen's access " +
                    "to Kestrel Feeds disabled",
                FetchResultKind.Fetched,
                tap = 1,
            ),
            page.ui().fetchResult,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-010"])
    @DisplayName("A11: the Overview fetches all Data Providers in use and shows one line per Data Provider")
    fun overviewFetchesAllDataProviders() {
        runBlocking {
            slice.enterCredentials()
            slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        }
        answerFeeds()
        aBankAccountIsAdded()
        val overview = OverviewViewModel(fixture.services).also { it.load() }
        assertTrue(overview.state.value!!.canFetch)

        overview.fetchAll()

        assertEquals(
            listOf(
                FetchResultUi("Kestrel Feeds · Fetched 14:00", "Bank Accounts offered: 3 · not added: 3", FetchResultKind.Fetched, tap = 1),
                // P9-13: said plainly, without naming a feature this build does not have.
                FetchResultUi(
                    "Aurora Open Banking · Not fetched: Bank Consents are not in this build yet; Aurora Open Banking cannot fetch until they are",
                    null,
                    FetchResultKind.NotFetched,
                    tap = 1,
                ),
            ),
            overview.state.value!!.fetchResults,
        )
        assertEquals(0, slice.openBanking.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A16: Operator Fetches Despite a Request Limit Warning", businessRules = ["BR-015"])
    @DisplayName("A11, A16: a Data Provider that warned asks for confirmation from the Overview too; confirming fetches that Data Provider")
    fun overviewAsksToConfirmAWarning() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        slice.importStatusStore.backOffList += StoredBackOff(
            slice.feedsId, null, BackOffCause.RequestLimitWarning, slice.clock.instant(), slice.clock.instant().plus(Duration.ofHours(24)),
        )
        aBankAccountIsAdded()
        val overview = OverviewViewModel(fixture.services).also { it.load() }

        overview.fetchAll()
        assertEquals("Fetch Kestrel Feeds despite its warning?", overview.state.value!!.fetchWarning!!.title)
        overview.confirmFetch()

        assertNull(overview.state.value!!.fetchWarning)
        assertEquals(
            listOf(FetchResultUi("Kestrel Feeds · Fetched 14:00", "Bank Accounts offered: 3 · not added: 3", FetchResultKind.Fetched, tap = 2)),
            overview.state.value!!.fetchResults,
        )
    }
    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 1 (P9-16, budget.html#fetch-states): while the Fetch runs, its button shows Fetching with the Data Provider, with progress, and is disabled, here and on the Overview; another tap starts nothing; the earlier result goes; afterwards the button is ready again with the result of the tap")
    fun runningFetchIsShownOnItsButton() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        aBankAccountIsAdded()
        val gate = CompletableDeferred<Unit>()
        val page = page(slice.feedsKey)
        val overview = OverviewViewModel(fixture.services).also { it.load() }
        page.fetch()
        slice.clock.advance(Duration.ofHours(2))
        slice.feeds.beforeAnswer = { gate.await() }

        page.fetch()

        assertEquals(FetchButtonUi("Fetching Kestrel Feeds…", enabled = false, running = true), page.ui().fetchButton)
        assertNull(page.ui().fetchResult)
        assertEquals(FetchButtonUi("Fetching Kestrel Feeds…", enabled = false, running = true), overview.state.value!!.fetchButton)
        page.fetch()
        overview.fetchAll()
        assertEquals(2, slice.feeds.fetches)

        gate.complete(Unit)

        assertEquals(FetchButtonUi("Fetch Kestrel Feeds", enabled = true, running = false), page.ui().fetchButton)
        assertEquals(FetchButtonUi("Fetch all Data Providers", enabled = true, running = false), overview.state.value!!.fetchButton)
        assertEquals(
            FetchResultUi("Fetched 16:00", "Bank Accounts offered: 3 · not added: 3", FetchResultKind.Fetched, tap = 2),
            page.ui().fetchResult,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-010"])
    @DisplayName("A11 (P9-16): while the Overview fetches all Data Providers, its button shows the Data Provider being fetched and is disabled; the results of the tap follow, one per Data Provider")
    fun overviewShowsWhichDataProviderIsFetched() {
        runBlocking { slice.enterCredentials() }
        answerFeeds()
        aBankAccountIsAdded()
        val gate = CompletableDeferred<Unit>()
        slice.feeds.beforeAnswer = { gate.await() }
        val overview = OverviewViewModel(fixture.services).also { it.load() }
        assertEquals(FetchButtonUi("Fetch all Data Providers", enabled = true, running = false), overview.state.value!!.fetchButton)

        overview.fetchAll()

        assertEquals(FetchButtonUi("Fetching Kestrel Feeds…", enabled = false, running = true), overview.state.value!!.fetchButton)
        assertEquals(emptyList<FetchResultUi>(), overview.state.value!!.fetchResults)
        gate.complete(Unit)
        assertEquals(FetchButtonUi("Fetch all Data Providers", enabled = true, running = false), overview.state.value!!.fetchButton)
        assertEquals(
            listOf(FetchResultUi("Kestrel Feeds · Fetched 14:00", "Bank Accounts offered: 3 · not added: 3", FetchResultKind.Fetched, tap = 1)),
            overview.state.value!!.fetchResults,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"])
    @DisplayName("A4, BR-008 (P9-13): the page of a Data Provider that needs a Bank Consent says plainly that Bank Consents are not in this build, and its Fetch is unavailable with that reason")
    fun bankConsentProviderCannotFetchInThisBuild() {
        runBlocking { slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        val page = page(slice.openBankingKey)

        assertFalse(page.ui().canFetch)
        assertEquals("Bank Consents are not in this build yet; Aurora Open Banking cannot fetch until they are.", page.ui().fetchUnavailable)
        assertEquals("None yet · a Bank Consent of a Bank lists its Bank Accounts here · Bank Consents are not in this build yet", page.ui().offered!!.emptyText)
        page.fetch()
        assertEquals(0, slice.openBanking.fetches)
        assertNull(page(slice.feedsKey).ui().fetchUnavailable)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers")
    @DisplayName("A11 (P9-14): the Overview says in one line what a Fetch does in this build: it lists the Bank Accounts with their balances and stores the Account Balances, no Bank Transactions yet")
    fun overviewSaysWhatAFetchDoesInThisBuild() {
        runBlocking { slice.enterCredentials() }
        aBankAccountIsAdded()

        val ui = OverviewViewModel(fixture.services).also { it.load() }.state.value!!

        assertEquals(
            "In this build a Fetch lists the Bank Accounts each Data Provider offers and stores the Account Balance of each " +
                "Bank Account you added; Bank Transactions are not fetched yet.",
            ui.fetchNote,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("A8: a Fetch that delivered the others names the Bank Account the Data Provider reported a failure for, whose stored data stays unchanged")
    fun failureForOneBankAccountIsNamed() {
        runBlocking { slice.enterCredentials() }
        aFeedsBankAccountIsAdded()
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(DeliveredBankAccount("acct-4410", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Failed(FailureKind.Timeout))),
            )
        }
        val page = page(slice.feedsKey)

        page.fetch()

        assertEquals(
            FetchResultUi(
                "Fetched 14:00",
                "Bank Accounts offered: 1 · added and identified: 1 · failed: Kestrel Checking, stored data unchanged",
                FetchResultKind.Fetched,
                tap = 1,
            ),
            page.ui().fetchResult,
        )
    }

    /** Kestrel Checking, added from Kestrel Feeds's Offered Bank Account acct-4410 into Household's Checking (UC-013). */
    private fun aFeedsBankAccountIsAdded() = runBlocking {
        val listed = ExampleData.budgetsRead().map { budget ->
            slussen.core.port.BudgetToServe(
                budget.reference, budget.name, budget.currency,
                budget.budgetAccounts.map { slussen.core.port.BudgetAccountToStore(it.reference, it.name, it.balance) },
            )
        }
        slice.connectionStore.serveBudgets(listed, setOf("budget-household"), slice.clock.instant())
        val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.offeredStore.replaceOffered(
            slice.feedsId, null, listOf(ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, bank, slice.clock.instant())),
        )
        val checking = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        slice.connect.add(
            slice.offeredStore.offered().single().id, checking, "Kestrel Checking", slussen.core.port.BankAccountType.Checking,
            slussen.core.port.BankChoice.Known(bank), null,
        )
    }
}
