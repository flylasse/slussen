package slussen.app.ui.overview

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
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.components.FirstUploadMatchingChip
import slussen.app.ui.components.FirstUploadMatchingUi
import slussen.core.firstupload.FirstUploadMatchingTone
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartedBy
import slussen.core.port.StoredAccountBalance
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFetchResult
import slussen.core.port.StoredPart
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.UnreadablePart
import slussen.core.port.StartKind
import slussen.core.connection.PairOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.NewBankAccount
import slussen.core.port.AddOutcome
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.testing.ExampleData.EUR
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.TestSlice
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.Duration

@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-008: Check Import Status, the screens")
class UC008CheckImportStatusViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    /** Household (USD) with Kestrel Checking paired through Kestrel Feeds, and Summer House (EUR) served. */
    private fun household(slice: TestSlice = this.slice): Long = runBlocking {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, setOf("budget-household", "budget-summer"), slice.clock.instant())
        val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, bank, slice.clock.instant())),
        )
        val checking = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        val added = slice.connect.add(
            slice.offeredStore.offered().single().id, checking, "Kestrel Checking", BankAccountType.Checking, BankChoice.Known(bank), null,
        ) as PairOutcome.Added
        added.bankAccountId
    }

    private fun overview() = OverviewViewModel(fixture.services).also { it.load() }.state.value!!

    private fun budgetId(name: String) = runBlocking { slice.connectionStore.budgets().single { it.name == name }.id }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-018"])
    @DisplayName("Step 1: the Overview shows every Budget with its counts and blocked states, at the device's local time")
    fun overviewShowsEveryBudget() {
        val checking = household()
        slice.importStatusStore.counts += StoredTransactionCounts(checking, eligible = 9, waitingForDecision = 9)

        val ui = overview()

        assertEquals("Overview · 2026-10-15 14:00 · device time", ui.heading)
        assertEquals(listOf("Household", "Summer House"), ui.budgets.map { it.name })
        assertEquals("Eligible 9 · waiting for a decision 9", ui.budgets.first().counts)
        assertEquals("USD", ui.budgets.first().currency)
        assertTrue(ui.budgets.first().blocked.isEmpty())
        assertFalse(ui.noBankAccountAdded)
        assertEquals("${ExampleData.BUDGET_APP_NAME} · not read yet", ui.budgetAppLine.status)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 2: a Bank Consent within the consent warning lead time is shown with its expiry date and the renew action")
    fun expiringBankConsentIsShown() {
        household()
        val aurora = runBlocking { slice.connectionStore.bankNamed("Aurora Bank").id }
        slice.importStatusStore.consents += StoredBankConsent(1, aurora, slice.openBankingId, slice.clock.instant(), slice.clock.instant().plus(Duration.ofDays(4)))

        assertEquals(listOf("Bank Consent of Aurora Bank through Aurora Open Banking expires 2026-10-19 · Renew"), overview().expiringConsents)
    }

    /** UC-008 BR-009: the explanation, the same in every case, with the Budget App's name. */
    private val explanation =
        "Before Slussen uploads anything, it compares the transactions it fetched with what is already in Example Budget App " +
            "and marks every match, so that no transaction ends up in Example Budget App twice. You look at the result and " +
            "confirm; until then nothing is uploaded. Fetching works as usual."

    private val newInstallationLine =
        "This is a new installation. Your earlier importer, Example Budget App's own bank import or you by hand may already " +
            "have added some of these transactions to Example Budget App."
    private val restoredBackupLine =
        "Slussen was restored from a backup. It may have uploaded transactions after the backup was made that its records " +
            "no longer show."
    private val lastGoodCopyLine =
        "Slussen's records could not be read, so it started from its last good copy, or from empty records when it had " +
            "none. Anything uploaded after that copy was made is not in its records."

    /** `overview.html#open-this-build`: the build state while First Upload Matching's confirmation (A9) waits for Uploads. */
    private val matchingComesLater =
        "First Upload Matching comes with Uploads in a later version. Nothing is uploaded until then, and no action is needed now."

    private fun firstUploadMatching(tone: FirstUploadMatchingTone, vararg reasons: String) = FirstUploadMatchingUi(
        title = "Uploads wait for First Upload Matching",
        explanation = explanation,
        reasons = reasons.toList(),
        tone = tone,
        action = "Start First Upload Matching",
        actionAvailable = false,
        buildNote = matchingComesLater,
    )

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009", "BR-006"])
    @DisplayName("A8, BR-006, BR-009 (P9-17, overview.html#first-added, #open-this-build): after a new installation the Overview shows Uploads wait for First Upload Matching with the explanation, the reason line and the action, as plain information; while its confirmation is not built the action is unavailable and the build line says that it comes with Uploads and that no action is needed; each Budget shows the Blocked Reason in the same tone")
    fun firstUploadMatchingOfANewInstallationIsShown() {
        household()
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, slice.clock.instant())

        val ui = overview()

        assertEquals(firstUploadMatching(FirstUploadMatchingTone.Information, newInstallationLine), ui.firstUploadMatching)
        assertEquals(
            FirstUploadMatchingChip("Uploads wait for First Upload Matching", FirstUploadMatchingTone.Information),
            ui.budgets.first().firstUploadMatching,
        )
        assertEquals(emptyList<String>(), ui.budgets.first().blocked)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("BR-009 (overview.html#recovery, #damaged-critical): after a restore the reason line of a restore is a caution; with a last good copy beside a new installation each line is shown once and the caution wins")
    fun firstUploadMatchingAfterARestoreIsACaution() {
        household()
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.RestoredBackup, slice.clock.instant())
        assertEquals(firstUploadMatching(FirstUploadMatchingTone.Caution, restoredBackupLine), overview().firstUploadMatching)
        assertEquals(FirstUploadMatchingTone.Caution, overview().budgets.first().firstUploadMatching!!.tone)

        val other = UiFixture()
        household(other.slice)
        other.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, other.slice.clock.instant())
        other.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.LastGoodCopy, other.slice.clock.instant())
        other.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, other.slice.clock.instant())

        assertEquals(
            firstUploadMatching(FirstUploadMatchingTone.Caution, newInstallationLine, lastGoodCopyLine),
            OverviewViewModel(other.services).also { it.load() }.state.value!!.firstUploadMatching,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("BR-009 (budget.html#recovery-blocked): the Budget tab shows the same First Upload Matching with its explanation, reason line and action")
    fun budgetTabShowsFirstUploadMatching() {
        household()
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.RestoredBackup, slice.clock.instant())

        val ui = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!

        assertEquals(firstUploadMatching(FirstUploadMatchingTone.Caution, restoredBackupLine), ui.firstUploadMatching)
        val none = UiFixture()
        household(none.slice)
        val household = runBlocking { none.slice.connectionStore.budgets().single { it.name == "Household" }.id }
        assertNull(BudgetViewModel(none.services, household).also { it.load() }.state.value!!.firstUploadMatching)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A9: Operator Confirms First Upload Matching", businessRules = ["BR-009"])
    @DisplayName("A9: Start First Upload Matching is unavailable while its confirmation is not built; asked anyway it says that it comes with Uploads and that no action is needed, and First Upload Matching stays open")
    fun startingFirstUploadMatchingIsUnavailable() {
        household()
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, slice.clock.instant())
        val viewModel = OverviewViewModel(fixture.services).also { it.load() }
        assertFalse(viewModel.state.value!!.firstUploadMatching!!.actionAvailable)

        viewModel.startFirstUploadMatching()

        assertEquals(matchingComesLater, viewModel.state.value!!.message)
        assertEquals(1, runBlocking { slice.firstUploadMatchingStore.openChecks().size })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A7: Backed-Up Data Nears the Backup Limit", businessRules = ["BR-010"], requirements = ["NFR-019", "C-015"])
    @DisplayName("A7 (overview.html#backup-warning, issue #26): the backup size warning names the current size and Android's limit, and says that Slussen has no action that makes it smaller and that none is needed")
    fun backupWarningIsShown() {
        household()
        slice.backupSize.bytes = (21.3 * 1024 * 1024).toLong()

        assertEquals(
            NoticeUi(
                "Backed-up data nears Android's backup limit",
                "21.3 MB of 25.0 MB, Android's limit · above it Android backs up nothing · Slussen has no action that makes it " +
                    "smaller, and none is needed: Slussen goes on working, and First Upload Matching covers a restore of an older backup",
            ),
            overview().backupWarning,
        )
    }

    private fun noticesOf(vararg parts: UnreadablePart, startKind: StartKind = StartKind.Normal): List<NoticeUi> {
        val damaged = UiFixture(startKind = startKind, unreadableParts = parts.toList())
        household(damaged.slice)
        return OverviewViewModel(damaged.services).also { it.load() }.state.value!!.unreadableNotices
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", businessRules = ["BR-012"], requirements = ["NFR-005"])
    @DisplayName("A1, BR-012 (overview.html#damaged, issue #26): the Decision Journal is named, started empty, never from a last good copy, with nothing else affected and no action needed; it opens no First Upload Matching")
    fun unreadableDecisionJournal() {
        val damaged = UiFixture(unreadableParts = listOf(UnreadablePart(StoredPart.DecisionJournal, startedFromLastGoodCopy = false)))
        household(damaged.slice)

        val ui = OverviewViewModel(damaged.services).also { it.load() }.state.value!!

        val journal = NoticeUi(
            "Stored data could not be read: Decision Journal",
            "Started empty: the Decision Journal has no last good copy · the unreadable original is kept on this device · " +
                "nothing else is affected, no action is needed",
        )
        assertEquals(listOf(journal), ui.unreadableNotices)
        assertNull(ui.firstUploadMatching)
        // ADR-002 §8.5: even a report that says otherwise never makes the Decision Journal start from a last good copy.
        assertEquals(listOf(journal), noticesOf(UnreadablePart(StoredPart.DecisionJournal, startedFromLastGoodCopy = true)))
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", businessRules = ["BR-012"], requirements = ["NFR-005"])
    @DisplayName("A1, BR-012 (overview.html#damaged, issue #26): the part with the service credentials is named, started empty, with entering the credentials again in Settings as the next action and the Offered Bank Accounts coming back with the next Fetch")
    fun unreadableServiceCredentials() {
        val notices = noticesOf(UnreadablePart(StoredPart.CredentialsConsentsAndOfferedBankAccounts, startedFromLastGoodCopy = false))

        assertEquals(
            listOf(
                NoticeUi(
                    "Stored data could not be read: service credentials, Bank Consents and Offered Bank Accounts",
                    "Started empty: this part has no last good copy · the unreadable original is kept on this device · enter the " +
                        "credentials of each service again in Settings; the Offered Bank Accounts come back with the next Fetch",
                ),
            ),
            notices,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", businessRules = ["BR-009", "BR-012"], requirements = ["NFR-005"])
    @DisplayName("A1, BR-012 (overview.html#damaged-critical, review 2): the stored transactions, decisions and settings started from the last good copy name checking Settings as next, and point to First Upload Matching only while one is open and shown below")
    fun unreadableDecisionCriticalData() {
        val damaged = UiFixture(
            startKind = StartKind.LastGoodCopy,
            unreadableParts = listOf(UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = true)),
        )
        household(damaged.slice)
        damaged.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.LastGoodCopy, damaged.slice.clock.instant())
        val open = OverviewViewModel(damaged.services).also { it.load() }.state.value!!
        val noneOpen = noticesOf(
            UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = true),
            startKind = StartKind.LastGoodCopy,
        )

        val checkSettings = "Started from its last good copy, made at an earlier start · the unreadable original is kept on this device · " +
            "check in Settings that your Budgets, Bank Accounts and Account Mappings are as you left them"
        assertEquals(
            listOf(NoticeUi("Stored data could not be read: stored transactions, decisions and settings", "$checkSettings, and see First Upload Matching below")),
            open.unreadableNotices,
        )
        assertTrue(open.firstUploadMatching != null)
        assertEquals(listOf(NoticeUi("Stored data could not be read: stored transactions, decisions and settings", checkSettings)), noneOpen)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011", "BR-012"], requirements = ["NFR-005"])
    @DisplayName("A1, A10, BR-011, BR-012 (review 1): while no Bank Account is added, the notices of stored data that could not be read stand above the card; started from empty records, the notice says that what was set up before is not in the records and that the card leads to setting Slussen up again")
    fun noticesStandAboveTheFirstRunCard() {
        val damaged = UiFixture(
            startKind = StartKind.LastGoodCopy,
            unreadableParts = listOf(
                UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = false),
                UnreadablePart(StoredPart.DecisionJournal, startedFromLastGoodCopy = false),
            ),
        )
        damaged.slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.LastGoodCopy, damaged.slice.clock.instant())

        val ui = OverviewViewModel(damaged.services).also { it.load() }.state.value!!

        assertTrue(ui.firstRun != null)
        assertNull(ui.firstUploadMatching)
        assertEquals(
            listOf(
                NoticeUi(
                    "Stored data could not be read: stored transactions, decisions and settings",
                    "Started from empty records with every default, as there was no last good copy · the unreadable original is " +
                        "kept on this device · what was set up before is not in these records: set Slussen up again with the card below",
                ),
                NoticeUi(
                    "Stored data could not be read: Decision Journal",
                    "Started empty: the Decision Journal has no last good copy · the unreadable original is kept on this device · " +
                        "nothing else is affected, no action is needed",
                ),
            ),
            ui.unreadableNotices,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("A10: with no Bank Account added, the Overview shows only the card that leads to Settings and names the next thing, not even First Upload Matching a new installation opens")
    fun firstRunShowsOnlyTheCard() {
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, slice.clock.instant())

        val ui = overview()

        assertTrue(ui.noBankAccountAdded)
        assertEquals(
            FirstRunUi(
                "Set up Slussen in Settings",
                "Next: enter the ${ExampleData.BUDGET_APP_NAME} credentials",
                "Then choose the Budgets, connect a Data Provider and map its Bank Accounts.",
            ),
            ui.firstRun,
        )
        assertNull(ui.firstUploadMatching)
        assertTrue(ui.budgets.isEmpty())
        assertTrue(ui.expiringConsents.isEmpty())
        assertFalse(ui.canFetch)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("BR-011: later on the first run the card names the next thing, a Data Provider's Fetch or a Bank Consent; from the first added Bank Account on there is no card")
    fun theCardFollowsWhatComesNext() {
        runBlocking {
            slice.enterCredentials()
            slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
            val listed = ExampleData.budgetsRead().map { budget ->
                BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
            }
            slice.connectionStore.serveBudgets(listed, setOf("budget-household"), slice.clock.instant())
        }

        assertEquals(
            FirstRunUi(
                "Set up Slussen in Settings",
                "Next: fetch Kestrel Feeds",
                "Its Bank Accounts are then imported and mapped in Account Mappings. Aurora Open Banking needs a Bank Consent: Bank Consents are not in this build yet.",
            ),
            overview().firstRun,
        )
        household()
        assertNull(overview().firstRun)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-005"])
    @DisplayName("Steps 5-7 (UX-08, UX-25): a Budget shows its Sources with Source Health and Freshness apart, and each Account Balance with its difference: the Bank balance as of the time the Data Provider reported it, the Budget Account balance read at the time of the Budget App's read")
    fun budgetShowsHealthFreshnessAndBalances() {
        val checking = household()
        val fetchedAt = slice.clock.instant().minus(Duration.ofHours(30))
        val failedAt = slice.clock.instant().minus(Duration.ofHours(1))
        slice.importStatusStore.fetches += StoredFetch(1, slice.feedsId, fetchedAt, StartedBy.Operator, FetchOutcome.Fetched, listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 2)))
        slice.importStatusStore.fetches += StoredFetch(2, slice.feedsId, failedAt, StartedBy.Schedule, FetchOutcome.Failed, emptyList())
        slice.importStatusStore.balances += StoredAccountBalance(checking, amount(5135, 280), USD, failedAt)

        val ui = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!

        assertEquals("Household", ui.title)
        assertEquals("Budget · USD · from stored data", ui.subtitle)
        val source = ui.sources.single()
        assertEquals("Source Health: Failed 2026-10-15 13:00 · Fetch Schedule", source.health)
        assertEquals("Freshness: data as of 2026-10-14 08:00", source.freshness)
        val account = ui.bankAccounts.single()
        assertEquals("Bank balance 5,135.28 as of 13:00 · Budget Account balance 4,140.05 read 14:00 · Difference +995.23", account.balance)
        assertEquals("Checking", account.type)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A2: Account Balance Outdated", businessRules = ["BR-004"])
    @DisplayName("A2 (UX-08, UX-24): an outdated Account Balance is marked outdated and shows no difference, with both times and its remedy: a Fetch of its Data Provider, nothing else")
    fun outdatedBalanceShowsNoDifference() {
        val checking = household()
        slice.importStatusStore.balances += StoredAccountBalance(checking, amount(5135, 280), USD, slice.clock.instant().minus(Duration.ofHours(40)))

        val account = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!.bankAccounts.single()

        assertEquals(
            "Bank balance 5,135.28 as of 10-13 22:00 · Budget Account balance 4,140.05 read 14:00 · Account Balance outdated · difference hidden",
            account.balance,
        )
        assertEquals("Fetch to ask Kestrel Feeds for a newer one; nothing else is needed.", account.balanceRemedy)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A4: Balance Not Known")
    @DisplayName("A4: a balance that is not known is named and no difference is shown")
    fun unknownBalanceIsNamed() {
        household()

        val account = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!.bankAccounts.single()

        assertEquals("Account Balance not known · Budget Account balance 4,140.05 read 14:00 · no difference shown", account.balance)
        assertNull(account.balanceRemedy)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A6: Operator Takes the Remedy", businessRules = ["BR-006"])
    @DisplayName("A6: credentials missing leads to the service's page; a remedy whose use case is not built yet is shown unavailable")
    fun remediesLeadToTheirUseCase() {
        household()
        runBlocking { slice.credentials.remove(slice.feedsKey) }
        val ui = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!

        val missing = ui.sources.single().blocked.single()
        assertEquals("Blocked: Kestrel Feeds, credentials missing", missing.text)
        assertEquals(RemedyAction("Enter credentials", available = true, service = slice.feedsKey, note = null), missing.action)

        slice.importStatusStore.fetches += StoredFetch(
            3, slice.feedsId, slice.clock.instant(), StartedBy.Operator, FetchOutcome.Fetched,
            listOf(StoredFetchResult(runBlocking { slice.connectionStore.bankAccounts().single().id }, FetchResultOutcome.NotRecognised, 0)),
        )
        val unrecognised = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!
            .bankAccounts.single().blocked.single()
        assertEquals(
            RemedyAction("Confirm the Bank Account", available = false, service = null, note = "comes with Manage Account Mappings"),
            unrecognised.action,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-005"])
    @DisplayName("Steps 6-7 after a Fetch (UX-08): each Bank Account card shows the Source Health and Freshness of its Fetch Result and its Account Balance next to the Budget Account's, with the difference; the tab no longer says that the Bank Transactions are not compared, only that nothing is uploaded yet and no action is needed for it")
    fun budgetShowsWhatAFetchStored() {
        household()
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(DeliveredBankAccount("acct-4410", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Read(emptyList(), amount(5135, 280)))),
            )
        }
        OverviewViewModel(fixture.services).also { it.load() }.fetchAll()

        val ui = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!

        val account = ui.bankAccounts.single()
        assertEquals("Source Health: Fetched 2026-10-15 14:00 · started by you", account.health)
        assertEquals("Freshness: data as of 2026-10-15 14:00", account.freshness)
        assertEquals("Bank balance 5,135.28 as of 14:00 · Budget Account balance 4,140.05 read 14:00 · Difference +995.23", account.balance)
        assertTrue(account.blocked.isEmpty())
        assertEquals("Source Health: Fetched 2026-10-15 14:00 · started by you", ui.sources.single().health)
        assertEquals(
            "Nothing is uploaded in this version: Uploads, Review Decisions and Transfer Decisions come in a later version. " +
                "No action is needed for them now.",
            ui.note,
        )
        assertEquals("Statuses as of 14:00", ui.statuses)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("A8 (P9-14, P9-17): with First Upload Matching open, its explanation says in plain words that nothing is uploaded until then and that fetching works as usual, and the Overview still offers the Fetch")
    fun firstUploadMatchingSaysFetchingWorksAsUsual() {
        household()
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, slice.clock.instant())

        val ui = overview()

        assertEquals(explanation, ui.firstUploadMatching!!.explanation)
        assertTrue(ui.canFetch)
        assertTrue(ui.fetchButton.enabled)
        // A Fetch compares with the Budget App since the transactions plan's Phase 7a; only Uploads are not built.
        assertEquals(
            "A Fetch brings in the Bank Transactions and compares them with Example Budget App. Nothing is uploaded in this " +
                "version; no action is needed for Uploads now.",
            ui.fetchNote,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A6: Operator Takes the Remedy", businessRules = ["BR-006"])
    @DisplayName("A6, BR-006 (P9-13, P9-15): a failed Fetch, of the Source or of one Bank Account, offers Fetch again on its Data Provider's page; a missing Bank Consent says plainly that Bank Consents are not in this build")
    fun remediesSayWhatThisBuildDoes() {
        val checking = household()
        slice.importStatusStore.fetches += StoredFetch(
            1, slice.feedsId, slice.clock.instant(), StartedBy.Operator, FetchOutcome.Failed,
            listOf(StoredFetchResult(checking, FetchResultOutcome.Failed, 0)),
        )
        runBlocking {
            slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
            val current = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Current" }.id
            val added = slice.connectionStore.addBankAccount(
                NewBankAccount(
                    generatedIdentifier = "00000000-0000-4000-8000-000000000501",
                    bank = BankChoice.Named("Aurora Bank"),
                    providerBankId = null,
                    dataProviderId = slice.openBankingId,
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
                    budgetAccountId = current,
                ),
            )
            assertTrue(added is AddOutcome.Added)
        }

        val household = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!
        val fetchAgain = RemedyAction("Fetch again", available = true, service = slice.feedsKey, note = null)
        assertEquals(fetchAgain, household.sources.single().blocked.single { it.text.contains("Fetch failed") }.action)
        assertEquals(fetchAgain, household.bankAccounts.single().blocked.single().action)

        val summer = BudgetViewModel(fixture.services, budgetId("Summer House")).also { it.load() }.state.value!!
        assertEquals(
            RemedyAction("Grant Bank Consent", available = false, service = null, note = "Bank Consents are not in this build yet"),
            summer.bankAccounts.single().blocked.single().action,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 2 (P9-13): an expiring Bank Consent is shown with the plain note that Bank Consents are not in this build, so it cannot be renewed here")
    fun expiringBankConsentSaysWhatThisBuildDoes() {
        household()
        val aurora = runBlocking { slice.connectionStore.bankNamed("Aurora Bank").id }
        slice.importStatusStore.consents += StoredBankConsent(1, aurora, slice.openBankingId, slice.clock.instant(), slice.clock.instant().plus(Duration.ofDays(4)))

        assertEquals("Bank Consents are not in this build yet, so it cannot be renewed here.", overview().expiringConsentNote)
    }

    // ---- Step 5: one Bank Account's screen (bank-account.html) --------------------------------------------------------

    private val scenario = slussen.testing.EveryStatusScenario(slice)

    private fun bankAccountScreen(name: String) =
        slussen.app.ui.bankaccount.BankAccountViewModel(fixture.services, scenario.bankAccount(name)).also { it.load() }

    private fun slussen.app.ui.bankaccount.BankAccountViewModel.ui() = state.value.ui!!

    private fun slussen.app.ui.bankaccount.BankAccountUi.line(key: String) =
        weeks.flatMap { it.lines }.single { !it.key.unreadable && it.key.id == scenario.transaction(key) }

    private val budgetApp = ExampleData.BUDGET_APP_NAME

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Steps 4-5 (budget.html → bank-account.html#checking-this-build): a Bank Account card of the Budget tab opens its own screen, which shows its Bank Transactions newest first under week headings with the dates the list covers, each with its status and its reason in the Operator's words, the statuses as of the last read with the counts, the Start Date and the Transfer Partner read-only")
    fun bankAccountScreenShowsTheList() {
        runBlocking { scenario.compareEveryStatus() }
        val card = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!.bankAccounts.first()
        assertEquals(scenario.bankAccount("Kestrel Checking"), card.bankAccountId)

        val ui = bankAccountScreen("Kestrel Checking").ui()

        assertEquals("Kestrel Checking", ui.title)
        assertEquals("Checking · USD", ui.kind)
        assertEquals("Household → Budget Account Checking · Kestrel Bank · Kestrel Feeds", ui.placement)
        assertEquals("Newest first · 2026-09-29 to 2026-10-16", ui.covers)
        assertEquals(
            listOf("This week · from Monday 10-12", "Last week · 10-05 to 10-11", "Earlier · 09-29 to 10-04", "Without a readable date"),
            ui.weeks.map { it.heading },
        )
        assertEquals("Statuses as of 14:00 · Eligible 3 · waiting for a decision 6 · not compared yet 1", ui.statuses)
        assertEquals("Read $budgetApp again to bring them up to date; nothing else is needed.", ui.readAgainNote)
        assertEquals("Start Date 2026-10-01", ui.startDate)
        assertEquals("Transfer Partner: not set · set in a later version", ui.otherSide)
        val typed = ui.line("typed")
        assertEquals(
            listOf("10-12", "Northwind Power", "A possible pairing with your Typed Entry; nothing paired or written yet", "−88.40", "Match For Typed Entry"),
            listOf(typed.date, typed.payee, typed.reason, typed.amount, typed.status),
        )
        assertEquals("No match in $budgetApp at the latest Comparison", ui.line("new").reason)
        assertTrue(ui.line("new").eligible)
        assertEquals("Resembles an Import by another importer in Checking; not proof of a duplicate", ui.line("possible").reason)
        assertEquals("No Stable Reference: same date, amount and text as 1 other, so Slussen cannot tell them apart", ui.line("no-reference-1").reason)
        assertEquals("Resembles a Slussen Import in a form this version does not support", ui.line("unsupported").reason)
        assertEquals("$budgetApp already holds it under Slussen's own Import Identifier", ui.line("already").reason)
        assertEquals("Deleted in $budgetApp under its Import Identifier; not sent again", ui.line("deleted").reason)
        assertEquals("Merged in $budgetApp into your Typed Entry Dentist 10-08 −95.00; not sent again", ui.line("merged").reason)
        assertEquals("Before the Upload Window, which starts 10-08", ui.line("too-old").reason)
        assertEquals("Before the Start Date 2026-10-01 of this Bank Account", ui.line("before-start").reason)
        assertEquals("Dated after today; the Comparison looks at it again once its date comes", ui.line("future").reason)
        assertEquals("One Bank Transfer with Kestrel Reserve 10-11 +500.00; this side would carry it", ui.line("leg-out").reason)
        assertEquals("Card Payment Slussen cannot pair for certain; proposed with Pinecrest Rewards Card 10-13 +410.00", ui.line("guessed-pay").reason)
        val notCompared = ui.line("not-compared")
        assertEquals("Not compared yet", notCompared.status)
        assertEquals("Stored since the last successful read of $budgetApp; compared at the next read", notCompared.reason)
        val unreadable = ui.weeks.last().lines.single()
        assertEquals(listOf("—", "Unreadable Transaction · Kiosk Nord", "Slussen could not read it (date missing); never uploaded", "−7.25", "Needs Review"),
            listOf(unreadable.date, unreadable.payee, unreadable.reason, unreadable.amount, unreadable.status))
        assertNull(ui.nothingStored)
        assertNull(ui.placementNote)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (bank-account.html#reserve-this-build, #home-card-this-build, UC-005 A5, A8, O-T-11): the side the other side carries is not counted as Eligible; a former Lonely Transfer says its other side did not arrive in time; a Guessed Transfer without a proposal says why; the Funded By and Transfer Partner are read-only")
    fun transferLinesSayWhy() {
        runBlocking { scenario.compareEveryStatus() }

        val reserve = bankAccountScreen("Kestrel Reserve").ui()
        val card = bankAccountScreen("Pinecrest Rewards Card").ui()

        assertEquals("One Bank Transfer with Kestrel Checking 10-11 −500.00; the other side would carry it, so this one is not counted as Eligible", reserve.line("leg-in").reason)
        assertFalse(reserve.line("leg-in").eligible)
        assertEquals(
            "Its other side, from Checking, did not arrive within the Lonely Transfer grace period (6 days, an Operator Setting, " +
                "until 10-14): now an ordinary Bank Transaction",
            reserve.line("late-side").reason,
        )
        assertEquals("New", reserve.line("late-side").status)
        assertEquals("Transfer Partner: Checking", reserve.otherSide)
        assertEquals("Card Payment; its paying side is not known, since Pinecrest Rewards Card has no Funded By", card.line("guessed-alone").reason)
        assertEquals("Card Payment Slussen cannot pair for certain; proposed with Kestrel Checking 10-12 −410.00", card.line("guessed-card").reason)
        assertEquals("Funded By: not set · set in a later version", card.otherSide)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (bank-account.html#tx-match-this-build, #tx-possible-import-this-build, #tx-unreadable-this-build): a tap opens the read-only details of a line: the Typed Entry it may pair with, the Import it resembles, what of an Unreadable Transaction could be read; no decision is offered, and Close returns to the list")
    fun linesOpenReadOnlyDetails() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = bankAccountScreen("Kestrel Checking")

        screen.open(screen.ui().line("typed").key)
        val match = screen.ui().sheet!!
        assertEquals("Northwind Power · −88.40 USD", match.title)
        assertEquals("2026-10-12 · Kestrel Checking · Match For Typed Entry", match.subtitle)
        assertEquals(
            listOf(
                slussen.app.ui.bankaccount.DetailLine("What it means", "$budgetApp holds a Typed Entry of the same amount within the Matching Date Window (10 days, a rule of $budgetApp)"),
                slussen.app.ui.bankaccount.DetailLine("Typed Entries with the same amount", "1 · Northwind 2026-10-11 −88.40 in Budget Account Checking"),
                slussen.app.ui.bankaccount.DetailLine("Paired?", "Not yet. $budgetApp pairs them when this Bank Transaction is uploaded, which comes in a later version."),
                slussen.app.ui.bankaccount.DetailLine("Written to $budgetApp", "Nothing: this version writes nothing"),
            ),
            match.details,
        )
        assertEquals("It stays visible with its reason. Nothing is uploaded, and no decision is needed now.", match.closing)

        screen.open(screen.ui().line("possible").key)
        assertEquals(
            slussen.app.ui.bankaccount.DetailLine("Resembles", "Riverside Apoth. 2026-10-14 −18.60 in Budget Account Checking, imported by another importer"),
            screen.ui().sheet!!.details.first(),
        )

        screen.open(screen.ui().weeks.last().lines.single().key)
        val unreadable = screen.ui().sheet!!
        assertEquals("Unreadable Transaction · Kiosk Nord · −7.25 USD", unreadable.title)
        assertEquals("Kestrel Checking · Needs Review", unreadable.subtitle)
        assertEquals(
            listOf("Amount −7.25 · description Kiosk Nord", "date missing", "2026-10-15 13:00", "Never"),
            unreadable.details.take(4).map { it.text },
        )

        screen.close()
        assertNull(screen.ui().sheet)
        assertNull(screen.ui().statusesSheet)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (bank-account.html#statuses-this-build, UX-14, UX-15, UX-16): What the statuses mean lists every Transaction Status this build gives, each with what it means for this Bank Account, the boundary of its date rule, and that no action or decision is needed now")
    fun statusesAreExplained() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = bankAccountScreen("Kestrel Checking")

        screen.openStatuses()

        val sheet = screen.ui().statusesSheet!!
        assertEquals("Kestrel Checking · Statuses as of 14:00", sheet.subtitle)
        assertEquals(
            listOf(
                "New", "Match For Typed Entry", "Already In Budget App", "Possible Import By Other Path", "Needs Review", "Transfer Leg",
                "Guessed Transfer", "Deleted In Budget App", "Merged In Budget App", "Too Old", "Before Start Date", "In The Future",
            ),
            sheet.statuses.map { it.status },
        )
        val tooOld = sheet.statuses.single { it.status == "Too Old" }
        assertEquals(
            "Dated before the Upload Window, which starts 10-08 (the last 7 days, an Operator Setting). Fetched because the fetch " +
                "period (30 days) reaches further back; not offered for Upload.",
            tooOld.meaning,
        )
        assertEquals("no action is needed.", tooOld.now)
        assertEquals("no decision is needed; Review Decisions come in a later version.", sheet.statuses.single { it.status == "Needs Review" }.now)
        assertEquals("Dated before the Start Date of its Bank Account, 2026-10-01. Never offered for Upload.", sheet.statuses.single { it.status == "Before Start Date" }.meaning)
        assertEquals("Nothing is written to $budgetApp in this version.", sheet.closing)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Step 5 (bank-account.html#fetched-empty): a Bank Account whose Fetch succeeded without Bank Transactions names the Fetch, names no dates it did not cover, and says that nothing is wrong; one never fetched says so")
    fun fetchedButEmptyIsSaid() {
        runBlocking {
            scenario.serve()
            scenario.addBankAccount("Bonus Saver", "Reserve Savings", slussen.core.port.BankAccountType.Savings)
        }
        assertEquals(
            "No Bank Transactions yet: no Fetch of this Bank Account has succeeded. A successful Fetch brings them in.",
            bankAccountScreen("Bonus Saver").ui().nothingStored,
        )
        slice.importStatusStore.fetches += StoredFetch(
            77, slice.feedsId, slice.clock.instant().minus(Duration.ofMinutes(15)), StartedBy.Operator, FetchOutcome.Fetched,
            listOf(StoredFetchResult(scenario.bankAccount("Bonus Saver"), FetchResultOutcome.Fetched, 0)),
        )

        val ui = bankAccountScreen("Bonus Saver").ui()

        assertEquals(
            "No Bank Transactions: the Fetch of 2026-10-15 13:45 by Kestrel Feeds succeeded and delivered no booked Bank " +
                "Transaction with an amount other than zero for this Bank Account. A pending purchase appears once it is booked and " +
                "a later Fetch brings it in. Nothing is wrong and no action is needed; fetch again for fresher data.",
            ui.nothingStored,
        )
        assertTrue(ui.weeks.isEmpty())
        assertNull(ui.covers)
        assertEquals("Source Health: Fetched 2026-10-15 13:45 · started by you", ui.health)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A3: Currency Mismatch", businessRules = ["BR-006"])
    @DisplayName("A3 (bank-account.html#currency-mismatch): a Bank Account with a currency mismatch shows the Blocked Reason with its remedy, hides the difference, and shows its Bank Transactions as not eligible while it is blocked")
    fun currencyMismatchIsShown() {
        runBlocking {
            scenario.serve()
            val dollar = scenario.addBankAccount("Aurora Dollar", "Savings", slussen.core.port.BankAccountType.Savings)
            scenario.store("interest", dollar, java.time.LocalDate.of(2026, 10, 13), amount(3, 100), "Interest")
            slice.comparison.readAgain()
        }

        val ui = bankAccountScreen("Aurora Dollar").ui()

        assertEquals("Summer House → Budget Account Savings · Kestrel Bank · Kestrel Feeds", ui.placement)
        assertEquals(listOf("Blocked: Aurora Dollar, currency mismatch"), ui.blocked.map { it.text })
        assertEquals(RemedyAction("Move the Account Mapping", available = false, service = null, note = "comes with Manage Account Mappings"), ui.blocked.single().action)
        assertEquals("Bank Account in USD, Budget in EUR · currency mismatch · difference hidden", ui.balance)
        assertEquals("Shown, but not eligible while the Bank Account is blocked: USD Bank Account, EUR Budget", ui.line("interest").reason)
        assertFalse(ui.line("interest").eligible)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (bank-account.html#unmapped, #retired): a Bank Account without an Account Mapping and a Retired Account keep their history as last compared, say that they are not compared or offered for Upload, and show no Budget's status")
    fun unmappedAndRetiredShowTheirHistory() {
        runBlocking { scenario.compareEveryStatus() }
        val reserve = scenario.bankAccount("Kestrel Reserve")
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == reserve) it.copy(retired = true) else it }

        val retired = bankAccountScreen("Kestrel Reserve").ui()

        assertEquals("Household · Retired Account", retired.placement)
        assertEquals("Retired Account · history kept · not compared or offered for Upload.", retired.placementNote)
        assertNull(retired.statuses)
        assertNull(retired.balance)
        assertEquals("Kept as it was; this Bank Account is not compared or offered for Upload", retired.line("late-side").reason)
        assertEquals("New", retired.line("late-side").status)
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == reserve) it.copy(retired = false, mapping = null) else it }
        val unmapped = bankAccountScreen("Kestrel Reserve").ui()
        assertEquals("Not mapped", unmapped.placement)
        assertEquals(
            "Not mapped · history kept · not compared or offered for Upload. Map it to a Budget Account in Account Mappings in Settings.",
            unmapped.placementNote,
        )
        assertFalse(unmapped.weeks.flatMap { it.lines }.any { it.eligible })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023 (review 4a, 4b): when a read of the Budget App started elsewhere ends, the open Bank Account shows the new statuses at once; a stored state that cannot be read is said on the screen, which keeps what it showed, never a crash")
    fun bankAccountScreenFollowsOperationsAndNeverCrashes() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = bankAccountScreen("Kestrel Checking")
        assertEquals("Not compared yet", screen.ui().line("not-compared").status)

        runBlocking { slice.comparison.readAgain() }

        assertEquals("New", screen.ui().line("not-compared").status)
        assertEquals("Statuses as of 14:10 · Eligible 4 · waiting for a decision 6", screen.ui().statuses)
        assertNull(screen.ui().readAgainNote)

        slice.copyStore.failsToRead = true
        runBlocking { slice.comparison.readAgain() }
        assertEquals(slussen.app.ui.bankaccount.BankAccountTexts.NOT_READ, screen.state.value.failure)
        assertEquals("Kestrel Checking", screen.ui().title)

        val unread = slussen.app.ui.bankaccount.BankAccountViewModel(fixture.services, scenario.bankAccount("Kestrel Checking")).also { it.load() }
        assertNull(unread.state.value.ui)
        assertEquals(slussen.app.ui.bankaccount.BankAccountTexts.NOT_READ, unread.state.value.failure)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("UC-003 A1, A2 (review 2a): a Bank Account with Bank Transactions not compared yet names the next action: a read of the Budget App, or entering its credentials first while they are refused")
    fun notComparedYetNamesTheRealNextAction() {
        runBlocking { scenario.compareEveryStatus() }
        slice.budgetApp.reader.readBudgetsAnswer = slussen.pluginapi.budgetapp.ReadResult.CredentialsRefused
        runBlocking { slice.comparison.readAgain() }

        val ui = bankAccountScreen("Kestrel Checking").ui()

        assertEquals(
            "$budgetApp refused Slussen's credentials: enter them again in Settings, then read $budgetApp again; nothing else is needed.",
            ui.readAgainNote,
        )
        assertEquals(
            "Not compared yet: 1 Bank Transaction, stored since the last successful read of $budgetApp; it has no Transaction Status " +
                "and is counted neither as Eligible nor as waiting for a decision.",
            ui.notComparedYet,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (review 2a, 5b): a Transfer Leg says why its Transfer is or is not in an Upload Plan by the rule the counts use: a Too Old side keeps the whole Transfer out, on both sides' lines and sheets; a Lonely Transfer says when its other side is expected")
    fun transferLegsSayWhyTheyAreOrAreNotEligible() {
        val ids = runBlocking {
            scenario.serve()
            val checking = scenario.addBankAccount("Kestrel Checking", "Checking")
            val reserve = scenario.addBankAccount("Kestrel Reserve", "Reserve Savings", slussen.core.port.BankAccountType.Savings)
            val card = scenario.addBankAccount("Pinecrest Rewards Card", "Rewards Card", slussen.core.port.BankAccountType.CreditCard)
            val checkingAccount = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
            slice.connectionStore.link(card, fundedBy = checkingAccount)
            scenario.store("out", checking, java.time.LocalDate.of(2026, 10, 7), amount(-200), "INTERNET TRANSFER TO RESERVE")
            scenario.store("in", reserve, java.time.LocalDate.of(2026, 10, 9), amount(200), "INTERNET TRANSFER FROM CHECKING")
            scenario.store("lonely", card, java.time.LocalDate.of(2026, 10, 13), amount(120), "AUTOPAY")
            scenario.answerHousehold(emptyList())
            slice.comparison.readAgain()
        }

        val reserve = bankAccountScreen("Kestrel Reserve")
        val checking = bankAccountScreen("Kestrel Checking")
        val card = bankAccountScreen("Pinecrest Rewards Card").ui()

        assertEquals(
            "One Bank Transfer with Kestrel Checking 10-07 −200.00; the other side is Too Old, so the whole Transfer is in no Upload Plan",
            reserve.ui().line("in").reason,
        )
        assertFalse(reserve.ui().line("in").eligible)
        checking.open(checking.ui().line("out").key)
        assertEquals(
            slussen.app.ui.bankaccount.DetailLine(
                "Transfer",
                "One Bank Transfer with Kestrel Reserve 10-09 +200.00; this side is Too Old, so the whole Transfer is in no Upload Plan",
            ),
            checking.ui().sheet!!.details.single { it.name == "Transfer" },
        )
        assertEquals("Lonely Transfer from Checking; its other side is expected by 10-19", card.line("lonely").reason)
        assertTrue(card.line("lonely").eligible)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (review 5b): the details of a New line say why, what the status means and whether it is Eligible; those of a line resembling an unsupported Slussen Import name that Import and why it waits")
    fun generalAndUnsupportedSheetsSayWhy() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = bankAccountScreen("Kestrel Checking")

        screen.open(screen.ui().line("new").key)
        assertEquals(
            listOf(
                slussen.app.ui.bankaccount.DetailLine("Why", "No match in $budgetApp at the latest Comparison"),
                slussen.app.ui.bankaccount.DetailLine("What it means", "The latest Comparison found nothing in $budgetApp that matches it. A later Upload Plan would hold it."),
                slussen.app.ui.bankaccount.DetailLine("Eligible", "Yes, for a later Upload Plan; nothing is uploaded in this version"),
            ),
            screen.ui().sheet!!.details,
        )
        screen.open(screen.ui().line("unsupported").key)
        assertEquals(
            listOf(
                slussen.app.ui.bankaccount.DetailLine("Resembles", "Streamline 2026-10-12 −29.00 in Budget Account Checking, with Slussen's marker in a form this version does not support"),
                slussen.app.ui.bankaccount.DetailLine("Why it waits", "Slussen cannot tell whether that Import is this Bank Transaction, so it never uploads it without your decision."),
                slussen.app.ui.bankaccount.DetailLine("Later", "Review Decisions come in a later version: Import Anyway or Defer"),
            ),
            screen.ui().sheet!!.details,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 5 (review 4a): opening a Bank Account again shows its list, never a sheet left open the last time; back closes an open sheet first")
    fun openingAgainShowsTheList() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = bankAccountScreen("Kestrel Checking")
        screen.open(screen.ui().line("typed").key)

        assertTrue(screen.back())
        assertNull(screen.ui().sheet)
        assertFalse(screen.back())
        screen.openStatuses()
        screen.load()

        assertNull(screen.ui().sheet)
        assertNull(screen.ui().statusesSheet)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-001"], requirements = ["NFR-023"])
    @DisplayName("Step 1, NFR-023 (review 6): an Overview whose stored status cannot be read says so with the next action instead of a crash, and shows once it can be read")
    fun overviewThatCannotBeOpened() {
        household()
        slice.importStatusStore.failsToRead = true

        val viewModel = OverviewViewModel(fixture.services).also { it.load() }

        assertNull(viewModel.state.value)
        assertEquals(
            "The Overview could not be shown: the status stored on this device could not be read, and nothing was changed. Tap Try again.",
            viewModel.notOpened.value,
        )
        slice.importStatusStore.failsToRead = false
        viewModel.load()
        assertNull(viewModel.notOpened.value)
        assertTrue(viewModel.state.value != null)
    }
}
