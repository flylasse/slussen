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
import slussen.app.ui.fetches.RecentFetchesViewModel
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
        assertEquals("${ExampleData.BUDGET_APP_NAME} · not read yet", ui.budgetAppRead)
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

    /** O-P2-09, `first-upload-matching.html#open`: the build state while the Comparison (UC-003) is not built. */
    private val matchingComesLater = "Matching with Example Budget App comes in a later version; until then nothing is uploaded."

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
    @DisplayName("A8, BR-006, BR-009 (P9-17, overview.html#first-added): after a new installation the Overview shows Uploads wait for First Upload Matching with the explanation, the reason line and the action, as plain information; while the Comparison is not built the action is unavailable and the build line says why; each Budget shows the Blocked Reason in the same tone")
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
    @DisplayName("A9: Start First Upload Matching is unavailable while the Comparison is not built (O-P2-09); asked anyway it says why, and First Upload Matching stays open")
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
    @DisplayName("A7: the backup size warning names the current size and Android's limit")
    fun backupWarningIsShown() {
        household()
        slice.backupSize.bytes = (21.3 * 1024 * 1024).toLong()

        assertEquals(
            "Backed-up data nears Android's backup limit: 21.3 MB of 25.0 MB, Android's limit · above it Android backs up nothing",
            overview().backupWarning,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", requirements = ["NFR-005"])
    @DisplayName("A1: a part that could not be read is named in a notice")
    fun unreadablePartIsNamed() {
        val damaged = UiFixture(unreadableParts = listOf(UnreadablePart(StoredPart.DecisionJournal, startedFromLastGoodCopy = false)))
        household(damaged.slice)

        val ui = OverviewViewModel(damaged.services).also { it.load() }.state.value!!

        assertEquals(listOf("Stored data could not be read: Decision Journal · started empty"), ui.unreadableNotices)
        assertNull(ui.firstUploadMatching)
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
    @DisplayName("Steps 5-7: a Budget shows its Sources with Source Health and Freshness apart, and each Account Balance with its difference")
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
        assertEquals("Bank 5,135.28 · Budget App 4,140.05 · Difference +995.23 · Read 13:00 / 14:00", account.balance)
        assertEquals("Checking", account.type)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A2: Account Balance Outdated", businessRules = ["BR-004"])
    @DisplayName("A2: an outdated Account Balance is marked outdated and shows no difference")
    fun outdatedBalanceShowsNoDifference() {
        val checking = household()
        slice.importStatusStore.balances += StoredAccountBalance(checking, amount(5135, 280), USD, slice.clock.instant().minus(Duration.ofHours(40)))

        val account = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!.bankAccounts.single()

        assertEquals("Bank 5,135.28 · Budget App 4,140.05 · Account Balance outdated · difference hidden", account.balance)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A4: Balance Not Known")
    @DisplayName("A4: a balance that is not known is named and no difference is shown")
    fun unknownBalanceIsNamed() {
        household()

        val account = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!.bankAccounts.single()

        assertEquals("Account Balance not known · Budget App 4,140.05", account.balance)
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
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"], requirements = ["NFR-018"])
    @DisplayName("A5: the recent Fetches are listed with Source, local time, who started them and outcome")
    fun recentFetchesAreListed() {
        household()
        slice.importStatusStore.fetches += StoredFetch(1, slice.feedsId, slice.clock.instant(), StartedBy.Schedule, FetchOutcome.Fetched, emptyList())

        val lines = RecentFetchesViewModel(fixture.services).also { it.load() }.state.value!!

        assertEquals(listOf("Kestrel Feeds · 2026-10-15 14:00 · Fetch Schedule · Fetched"), lines)
    }
    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-005"])
    @DisplayName("Steps 6-7 after a Fetch (P9-15): each Bank Account card shows the Source Health and Freshness of its Fetch Result and its Account Balance next to the Budget Account's, with the difference; the tab says that Bank Transactions are not fetched in this build")
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
        assertEquals("Bank 5,135.28 · Budget App 4,140.05 · Difference +995.23 · Read 14:00 / 14:00", account.balance)
        assertTrue(account.blocked.isEmpty())
        assertEquals("Source Health: Fetched 2026-10-15 14:00 · started by you", ui.sources.single().health)
        assertEquals(
            "Bank Transactions are not fetched in this build yet: a Fetch stores the Account Balances, so the counts stay at 0.",
            ui.note,
        )
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
}
