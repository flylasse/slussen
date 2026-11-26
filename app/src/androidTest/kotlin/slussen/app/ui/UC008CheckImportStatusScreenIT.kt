package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.mannodermaus.junit5.compose.createComposeExtension
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.budget.BlockedLine
import slussen.app.ui.budget.BudgetScreen
import slussen.app.ui.budget.BudgetUi
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.budget.SourceCard
import slussen.app.ui.overview.OverviewScreen
import slussen.app.ui.overview.OverviewViewModel
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.ServiceKey
import slussen.pluginapi.testfixtures.UseCase

@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-008: Check Import Status, the screen on a device")
class UC008CheckImportStatusScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009", "BR-006"])
    @DisplayName("A8, A9 (P9-17, overview.html#open-this-build): First Upload Matching is shown with its explanation and reason line; Start First Upload Matching is unavailable while its confirmation is not built, and the build line says that it comes with Uploads and that no action is needed")
    fun firstUploadMatchingIsShownAndCannotStartYet() = compose.use {
        val fixture = ScreenFixture()
        aBankAccountIsAdded(fixture)
        fixture.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, fixture.slice.clock.instant())
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = {}, onStartFirstUploadMatching = viewModel::startFirstUploadMatching, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {})
                }
            }
        }

        // The banner and each Budget card both say it (BR-009, `overview.html#recovery`).
        onAllNodesWithText("Uploads wait for First Upload Matching", substring = true).onFirst().assertExists()
        onNodeWithText("Before Slussen uploads anything", substring = true).assertExists()
        onNodeWithText("This is a new installation.", substring = true).assertExists()
        onNodeWithText("First Upload Matching comes with Uploads in a later version. Nothing is uploaded until then, and no action is needed now.").assertExists()
        onNodeWithText("Start First Upload Matching").assertIsNotEnabled()
        // P9-14: the Fetch stays offered with what it does: it compares with the Budget App; only Uploads are not built.
        onNodeWithText("Fetch all Data Providers").assertIsEnabled()
        onNodeWithText("A Fetch brings in the Bank Transactions and compares them with", substring = true).assertExists()
        onNodeWithText("it does not compare them", substring = true).assertDoesNotExist()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", businessRules = ["BR-012"], requirements = ["NFR-005"])
    @DisplayName("A1, BR-012, A7 (overview.html#damaged, #backup-warning, issue #26): each part that could not be read is named with what it started from and the next action or that none is needed; the backup warning says that no action makes it smaller and none is needed")
    fun noticesNameTheNextAction() = compose.use {
        val fixture = ScreenFixture(
            unreadableParts = listOf(
                slussen.core.port.UnreadablePart(slussen.core.port.StoredPart.DecisionJournal, startedFromLastGoodCopy = false),
                slussen.core.port.UnreadablePart(slussen.core.port.StoredPart.CredentialsConsentsAndOfferedBankAccounts, startedFromLastGoodCopy = false),
            ),
        )
        aBankAccountIsAdded(fixture)
        fixture.slice.backupSize.bytes = (21.3 * 1024 * 1024).toLong()
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = {}, onStartFirstUploadMatching = {}, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {})
                }
            }
        }

        onNodeWithText("Stored data could not be read: Decision Journal").assertExists()
        onNodeWithText("Started empty: the Decision Journal has no last good copy", substring = true).assertExists()
        onNodeWithText("nothing else is affected, no action is needed", substring = true).assertExists()
        onNodeWithText("Stored data could not be read: service credentials, Bank Consents and Offered Bank Accounts").assertExists()
        onNodeWithText("enter the credentials of each service again in Settings", substring = true).assertExists()
        onNodeWithText("last good copy, made at an earlier start", substring = true).assertDoesNotExist()
        onNodeWithText("Uploads wait for First Upload Matching", substring = true).assertDoesNotExist()
        onNodeWithText("Backed-up data nears Android's backup limit").assertExists()
        onNodeWithText("Slussen has no action that makes it smaller, and none is needed", substring = true).assertExists()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011", "BR-012"])
    @DisplayName("A1, A10, BR-011, BR-012 (review 1): stored data that could not be read is named above the first-run card, also with no Bank Account added")
    fun noticeAboveTheFirstRunCard() = compose.use {
        val fixture = ScreenFixture(
            startKind = slussen.core.port.StartKind.LastGoodCopy,
            unreadableParts = listOf(
                slussen.core.port.UnreadablePart(slussen.core.port.StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = false),
            ),
        )
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = {}, onStartFirstUploadMatching = {}, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {})
                }
            }
        }

        onNodeWithText("Stored data could not be read: stored transactions, decisions and settings").assertExists()
        onNodeWithText("set Slussen up again with the card below", substring = true).assertExists()
        onNodeWithText("Set up Slussen in Settings").assertExists()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("A10: with no Bank Account added, the Overview shows only the card that leads to Settings and names the next thing")
    fun firstStartShowsOnlyTheCard() = compose.use {
        val fixture = ScreenFixture()
        fixture.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, fixture.slice.clock.instant())
        var settingsOpened = false
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = { settingsOpened = true }, onStartFirstUploadMatching = {}, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {})
                }
            }
        }

        onNodeWithText("Set up Slussen in Settings").assertExists()
        onNodeWithText("Next: enter the ${slussen.testing.ExampleData.BUDGET_APP_NAME} credentials").assertExists()
        onNodeWithText("Uploads wait for First Upload Matching").assertDoesNotExist()
        onNodeWithText("Fetch all Data Providers").assertDoesNotExist()
        onNodeWithText("Open Settings").performClick()
        org.junit.jupiter.api.Assertions.assertTrue(settingsOpened)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A6: Operator Takes the Remedy", businessRules = ["BR-006"])
    @DisplayName("A6: a Blocked Reason shows its remedy; entering credentials and fetching again are available, granting a Bank Consent is not and says so plainly (P9-13)")
    fun remediesAreShown() = compose.use {
        var opened: ServiceKey? = null
        val ui = BudgetUi(
            title = "Household",
            subtitle = "Budget · USD · from stored data",
            counts = "Eligible 0 · waiting for a decision 0",
            sources = listOf(
                SourceCard(
                    name = "Kestrel Feeds",
                    kind = "Data Provider",
                    health = "Source Health: no Fetch yet",
                    freshness = "Freshness: no data yet",
                    counts = "Not yet uploaded: Eligible 0 · waiting for a decision 0",
                    blocked = listOf(
                        BlockedLine("Blocked: Kestrel Feeds, credentials missing", RemedyAction("Enter credentials", true, ServiceKey.dataProvider(1), null)),
                        BlockedLine("Blocked: Kestrel Feeds, Fetch failed", RemedyAction("Fetch again", true, ServiceKey.dataProvider(1), null)),
                        BlockedLine("Blocked: Aurora Current, missing Bank Consent", RemedyAction("Grant Bank Consent", false, null, "Bank Consents are not in this build yet")),
                    ),
                ),
            ),
            bankAccounts = emptyList(),
            note = "Nothing is uploaded in this version: Uploads, Review Decisions and Transfer Decisions come in a later version. " +
                "No action is needed for them now.",
        )
        setContent { SlussenTheme { BudgetScreen(ui, onRemedy = { opened = it.service }, onStartFirstUploadMatching = {}) } }

        onNodeWithText("Grant Bank Consent").assertIsNotEnabled()
        onNodeWithText("Bank Consents are not in this build yet").assertExists()
        onNodeWithText("Nothing is uploaded in this version", substring = true).assertExists()
        onNodeWithText("Fetch again").assertIsEnabled()
        onNodeWithText("Enter credentials").performClick()
        assertEquals(ServiceKey.dataProvider(1), opened)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Step 1, NFR-023 (FR-040, UC-003, budget.html#ynab-read-states): the Overview's line of the Budget App offers Read again; tapped, it answers on the line with the time of the read, and the Budget shows its statuses as of it")
    fun overviewReadsTheBudgetAppAgain() = compose.use {
        val fixture = ScreenFixture()
        aBankAccountIsAdded(fixture)
        val name = slussen.testing.ExampleData.BUDGET_APP_NAME
        val viewModel = OverviewViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    OverviewScreen(it, onBudget = {}, onOpenSettings = {}, onStartFirstUploadMatching = {}, onRecentFetches = {},
                        onFetchAll = {}, onConfirmFetch = {}, onCancelFetch = {}, onReadAgain = viewModel::readAgain)
                }
            }
        }

        onNodeWithText("$name · not read yet").assertExists()
        onNodeWithText("Read $name again").assertIsEnabled().performClick()
        waitUntil(5_000) { onAllNodesWithText("$name read 14:00 · statuses as of 14:00").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("$name · read 14:00").assertExists()
        onNodeWithText("Statuses as of 14:00").assertExists()
        onNodeWithText("Read $name again").assertIsEnabled()
        assertEquals(0, fixture.slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Step 5, NFR-023 (UC-003 A1, budget.html#budget-app-unreadable, #first-read-failed): a Budget tab whose read failed says so on its line of the Budget App with the time, keeps its statuses from the last successful read, and names the read as the remedy")
    fun budgetTabShowsAFailedRead() = compose.use {
        val fixture = ScreenFixture()
        aBankAccountIsAdded(fixture)
        val name = slussen.testing.ExampleData.BUDGET_APP_NAME
        val care = kotlinx.coroutines.runBlocking { fixture.slice.connectionStore.budgets().single { it.served }.id }
        val viewModel = slussen.app.ui.budget.BudgetViewModel(fixture.services, care)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        setContent {
            SlussenTheme {
                viewModel.state.collectAsState().value?.let {
                    BudgetScreen(it, onRemedy = {}, onStartFirstUploadMatching = {}, onReadAgain = viewModel::readAgain)
                }
            }
        }

        // A successful read first, then one an hour later that fails.
        onNodeWithText("Read $name again").performClick()
        waitUntil(5_000) { onAllNodesWithText("$name read 14:00 · statuses as of 14:00").fetchSemanticsNodes().isNotEmpty() }
        fixture.slice.clock.advance(java.time.Duration.ofHours(1))
        fixture.slice.budgetApp.reader.changesAnswer = {
            slussen.pluginapi.budgetapp.ReadResult.TransientFailure(slussen.pluginapi.request.FailureKind.ServiceUnavailable)
        }
        onNodeWithText("Read $name again").performClick()
        waitUntil(5_000) { onAllNodesWithText("$name not read 15:00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("$name · last read 14:00 · read failed 15:00").assertExists()
        onNodeWithText(
            "Statuses as of 14:00, its last successful read · the read of 15:00 failed: the service is unavailable",
        ).assertExists()
        onNodeWithText("Read $name again to bring them up to date; nothing else is needed.").assertExists()
    }

    /** The Bank Account screen of [bankAccountId], wired as `SlussenScreens` wires it. */
    private fun bankAccountScreen(fixture: ScreenFixture, bankAccountId: Long): slussen.app.ui.bankaccount.BankAccountViewModel {
        val viewModel = slussen.app.ui.bankaccount.BankAccountViewModel(fixture.services, bankAccountId)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        return viewModel
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Steps 4-5 (budget.html → bank-account.html#checking-this-build, review 4a): the Bank Account card of the Budget tab opens its screen, whose list shows each Bank Transaction newest first under week headings with its status and reason; a tap opens its read-only details in place of the list, which system back and Close each close again")
    fun bankAccountListAndOneSheet() = compose.use {
        val fixture = ScreenFixture()
        val scenario = slussen.testing.EveryStatusScenario(fixture.slice)
        kotlinx.coroutines.runBlocking { scenario.compareEveryStatus() }
        val household = kotlinx.coroutines.runBlocking { fixture.slice.connectionStore.budgets().single { it.name == "Household" }.id }
        val budget = slussen.app.ui.budget.BudgetViewModel(fixture.services, household)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { budget.load() }
        val viewModel = bankAccountScreen(fixture, scenario.bankAccount("Kestrel Checking"))
        var opened: Long? = null
        var onBankAccount by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            SlussenTheme {
                if (!onBankAccount) {
                    budget.state.collectAsState().value?.let {
                        BudgetScreen(it, onRemedy = {}, onStartFirstUploadMatching = {}, onBankAccount = { id -> opened = id; onBankAccount = true })
                    }
                } else {
                    slussen.app.ui.bankaccount.BankAccountScreen(
                        viewModel.state.collectAsState().value, onBack = {}, onOpen = viewModel::open, onStatuses = viewModel::openStatuses,
                        onClose = viewModel::close,
                    )
                }
            }
        }

        onNode(hasScrollAction()).performScrollToNode(hasText("Bank Transactions and their statuses ›"))
        onAllNodesWithText("Bank Transactions and their statuses ›").onFirst().performClick()
        assertEquals(scenario.bankAccount("Kestrel Checking"), opened)
        onNodeWithText("Household → Budget Account Checking · Kestrel Bank · Kestrel Feeds").assertExists()
        onNodeWithText("Newest first · 2026-09-29 to 2026-10-16").assertExists()
        onNode(hasScrollAction()).performScrollToNode(hasText("This week · from Monday 10-12".uppercase()))
        onNodeWithText("This week · from Monday 10-12".uppercase()).assertExists()
        onNode(hasScrollAction()).performScrollToNode(hasText("Northwind Power"))
        onNodeWithText("Northwind Power").assertExists()
        onNodeWithText("A possible pairing with your Typed Entry; nothing paired or written yet").assertExists()
        onAllNodesWithText("Match For Typed Entry").onFirst().assertExists()
        onNodeWithText("Northwind Power").performClick()
        onNodeWithText("Northwind Power · −88.40 USD").assertExists()
        onNodeWithText("1 · Northwind 2026-10-11 −88.40 in Budget Account Checking").assertExists()
        onNodeWithText("Newest first · 2026-09-29 to 2026-10-16").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        onNodeWithText("Newest first · 2026-09-29 to 2026-10-16").assertExists()
        onNode(hasScrollAction()).performScrollToNode(hasText("Northwind Power"))
        onNodeWithText("Northwind Power").performClick()
        onNodeWithText("Close").performClick()
        onNodeWithText("Newest first · 2026-09-29 to 2026-10-16").assertExists()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (bank-account.html#fetched-empty, review 2b): a Bank Account whose Fetch succeeded without Bank Transactions says so with the Fetch, not as a failure")
    fun bankAccountFetchedButEmpty() = compose.use {
        val fixture = ScreenFixture()
        val scenario = slussen.testing.EveryStatusScenario(fixture.slice)
        val saver = kotlinx.coroutines.runBlocking {
            scenario.serve()
            scenario.addBankAccount("Bonus Saver", "Reserve Savings", slussen.core.port.BankAccountType.Savings)
        }
        fixture.slice.importStatusStore.fetches += slussen.core.port.StoredFetch(
            77, fixture.slice.feedsId, fixture.slice.clock.instant().minus(java.time.Duration.ofMinutes(15)), slussen.core.port.StartedBy.Operator,
            slussen.core.port.FetchOutcome.Fetched,
            listOf(slussen.core.port.StoredFetchResult(saver, slussen.core.port.FetchResultOutcome.Fetched, 0)),
        )
        val viewModel = bankAccountScreen(fixture, saver)
        setContent {
            SlussenTheme {
                slussen.app.ui.bankaccount.BankAccountScreen(viewModel.state.collectAsState().value, onBack = {}, onOpen = {}, onStatuses = {}, onClose = {})
            }
        }

        onNodeWithText("No Bank Transactions: the Fetch of 2026-10-15 13:45 by Kestrel Feeds succeeded", substring = true).assertExists()
        onNodeWithText("Nothing is wrong and no action is needed", substring = true).assertExists()
        onNodeWithText("Source Health: Fetched 2026-10-15 13:45 · started by you").assertExists()
    }

    /** The Recent Fetches of [viewModel], wired as `SlussenScreens` wires them. */
    @androidx.compose.runtime.Composable
    private fun RecentFetches(
        viewModel: slussen.app.ui.fetches.RecentFetchesViewModel,
        onRemedy: (RemedyAction) -> Unit = {},
        onFetchAll: () -> Unit = {},
    ) = slussen.app.ui.fetches.RecentFetchesScreen(
        state = viewModel.state.collectAsState().value,
        onBack = {},
        onOpen = viewModel::open,
        onClose = viewModel::close,
        onFetchAll = onFetchAll,
        onRemedy = onRemedy,
    )

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5, UC-001 A19, A20 (fetch-history.html#list, #fetch-detail): a Fetch that delivered one Bank Account and not the others says so in its line and that something is still needed now; a tap opens the outcome of each Bank Account with the action that leads to its remedy, which Close and system back close again")
    fun recentFetchDetail() = compose.use {
        val fixture = ScreenFixture()
        val scenario = slussen.testing.EveryStatusScenario(fixture.slice)
        kotlinx.coroutines.runBlocking {
            scenario.serve()
            scenario.addBankAccount("Kestrel Checking", "Checking")
            scenario.addBankAccount("Kestrel Reserve", "Reserve Savings", slussen.core.port.BankAccountType.Savings)
        }
        fixture.slice.importStatusStore.fetches += slussen.core.port.StoredFetch(
            77, fixture.slice.feedsId, fixture.slice.clock.instant().minus(java.time.Duration.ofMinutes(30)), slussen.core.port.StartedBy.Operator,
            slussen.core.port.FetchOutcome.Fetched,
            listOf(
                slussen.core.port.StoredFetchResult(scenario.bankAccount("Kestrel Checking"), slussen.core.port.FetchResultOutcome.Fetched, 2),
                slussen.core.port.StoredFetchResult(scenario.bankAccount("Kestrel Reserve"), slussen.core.port.FetchResultOutcome.NotDelivered, 0),
            ),
        )
        val viewModel = slussen.app.ui.fetches.RecentFetchesViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        var remedy: RemedyAction? = null
        setContent { SlussenTheme { RecentFetches(viewModel, onRemedy = { remedy = it }) } }

        onNodeWithText("2026-10-15 13:30 · Kestrel Feeds · started by you").assertExists()
        onNodeWithText("Bank Accounts fetched: 1 of 2 · Kestrel Reserve: not delivered").assertExists()
        onNodeWithText("Still needed now: Kestrel Reserve, not delivered by Kestrel Feeds (13:30): fetch again").assertExists()
        onNodeWithText("2026-10-15 13:30 · Kestrel Feeds · started by you").performClick()
        onNodeWithText("Fetch of 2026-10-15 13:30").assertExists()
        onNodeWithText("Kestrel Checking").assertExists()
        onNodeWithText("Still needed: Kestrel Reserve, not delivered by Kestrel Feeds (13:30): fetch again").assertExists()
        onNodeWithText("Fetch again").performClick()
        assertEquals(ServiceKey.dataProvider(fixture.slice.feedsId), remedy?.service)
        androidx.test.espresso.Espresso.pressBack()
        onNodeWithText("Fetch of 2026-10-15 13:30").assertDoesNotExist()
        onNodeWithText("2026-10-15 13:30 · Kestrel Feeds · started by you").performClick()
        onNodeWithText("Close").performClick()
        onNodeWithText("Fetch of 2026-10-15 13:30").assertDoesNotExist()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5 (fetch-history.html#empty): without a Fetch the screen says why it is empty and its action leads to the Overview")
    fun recentFetchesEmpty() = compose.use {
        val fixture = ScreenFixture()
        val viewModel = slussen.app.ui.fetches.RecentFetchesViewModel(fixture.services)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.load() }
        var overview = false
        setContent { SlussenTheme { RecentFetches(viewModel, onFetchAll = { overview = true }) } }

        onNodeWithText("No Fetches yet").assertExists()
        onNodeWithText("an Operator Setting (60 now)", substring = true).assertExists()
        onNodeWithText("Fetch all Data Providers on the Overview").performClick()
        assertEquals(true, overview)
    }

    /** UC-008 A10, BR-011: the overview is shown from the first added Bank Account on; here one of the File Format. */
    private fun aBankAccountIsAdded(fixture: ScreenFixture) = kotlinx.coroutines.runBlocking {
        val slice = fixture.slice
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as slussen.core.connection.BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-care"))
        val health = slice.connectionStore.budgets().single { it.served }.budgetAccounts.single().id
        slice.connect.addFromTransactionFiles(
            slice.plugins.fileFormats.single().id, slussen.core.port.BankAccountType.Savings,
            slussen.core.port.BankChoice.Named("Harbor Health Trust"), null, health, "Harbor Health Savings", null, slussen.testing.ExampleData.USD,
        )
    }
}
