package slussen.core.status

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.testing.TestSlice
import slussen.core.fetch.FetchStart
import slussen.core.firstupload.FirstUploadMatchingNotice
import slussen.core.firstupload.FirstUploadMatchingStart
import slussen.core.firstupload.FirstUploadMatchingTone
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.FailureKind
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.JournalEventKind
import slussen.core.port.ProviderRequestKind
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredAccountBalance
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFetchResult
import slussen.core.port.StoredPart
import slussen.core.port.StoredProviderRequest
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransactionFile
import slussen.core.port.TransactionFileOutcome
import slussen.core.port.UnreadablePart
import slussen.core.settings.NextThing
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.Duration
import java.time.Instant

@DisplayName("UC-008: Check Import Status")
class UC008CheckImportStatusTest {
    private val slice = TestSlice()
    private val now: Instant get() = slice.clock.instant()

    /** The Request Limit the invented Data Provider Kestrel Feeds publishes (ExampleData.feedsProfile). */
    private val FEEDS_REQUEST_LIMIT = 20

    private var nextId = 1000L
    private val bankAccountIds = mutableMapOf<String, Long>()

    /** Household (USD) and Summer House (EUR) served; Care Fund (USD) served without a mapped Bank Account. */
    private suspend fun household() {
        slice.enterCredentials()
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(
                budget.reference,
                budget.name,
                budget.currency,
                budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) },
            )
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now)
        bankAccount("Kestrel Checking", "Kestrel Bank", slice.feedsId, USD, BankAccountType.Checking, "Checking")
        bankAccount("Pinecrest Rewards Card", "Pinecrest Card Co.", slice.feedsId, USD, BankAccountType.CreditCard, "Rewards Card")
        bankAccount("Aurora Current", "Aurora Bank", slice.openBankingId, EUR, BankAccountType.Checking, "Current")
        bankAccount("Aurora Dollar", "Aurora Bank", slice.openBankingId, USD, BankAccountType.Savings, "Savings")
        val aurora = slice.connectionStore.bankNamed("Aurora Bank").id
        slice.importStatusStore.consents += StoredBankConsent(99, aurora, slice.openBankingId, now.minus(Duration.ofDays(10)), now.plus(Duration.ofDays(100)))
    }

    private suspend fun bankAccount(
        name: String,
        bankName: String,
        dataProviderId: Long?,
        currency: CurrencyCode,
        type: BankAccountType,
        budgetAccountName: String,
        fileFormatId: Long? = null,
    ) {
        val id = nextId++
        val budgetAccount = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == budgetAccountName }
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed(bankName).id,
            providerBankId = null,
            dataProviderId = dataProviderId,
            fileFormatId = fileFormatId,
            balanceDataProviderId = null,
            name = name,
            currency = currency,
            type = type,
            startDate = null,
            providerReference = "ref-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now,
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount.id, null),
        )
        bankAccountIds[name] = id
    }

    private fun id(name: String) = bankAccountIds.getValue(name)

    private suspend fun budgetId(name: String) = slice.connectionStore.budgets().single { it.name == name }.id

    private fun fetch(
        dataProviderId: Long,
        at: Instant,
        outcome: FetchOutcome,
        vararg results: Pair<String, FetchResultOutcome>,
        startedBy: StartedBy = StartedBy.Operator,
    ) {
        slice.importStatusStore.fetches += StoredFetch(
            nextId++,
            dataProviderId,
            at,
            startedBy,
            outcome,
            results.map { (name, result) -> StoredFetchResult(id(name), result, transactionCount = 3) },
        )
    }

    private fun counts(name: String, eligible: Int, waiting: Int) {
        slice.importStatusStore.counts += StoredTransactionCounts(id(name), eligible, waiting)
    }

    private suspend fun workspaceAccount(budget: String, name: String): BankAccountStatus =
        slice.status.workspace(budgetId(budget)).bankAccounts.single { it.name == name }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1: the overview shows every served Budget with its Eligible Transactions, those waiting for a decision and whether anything is blocked")
    fun overviewShowsEveryBudgetWithCounts() = runTest {
        household()
        counts("Kestrel Checking", eligible = 5, waiting = 6)
        counts("Pinecrest Rewards Card", eligible = 2, waiting = 1)
        counts("Aurora Current", eligible = 2, waiting = 0)

        val overview = slice.status.open()

        assertEquals(listOf("Household", "Summer House", "Care Fund"), overview.budgets.map { it.name })
        val household = overview.budgets[0]
        assertEquals(NotYetUploaded(eligible = 7, waitingForDecision = 7), household.notYetUploaded)
        assertEquals(USD, household.currency)
        assertTrue(household.blocked.isEmpty())
        assertEquals(listOf(BlockedReason.CurrencyMismatch), overview.budgets[1].blocked.map { it.reason })
        assertEquals("Aurora Dollar", overview.budgets[1].blocked.single().subject)
        assertFalse(overview.noBankAccountAdded)
        assertEquals(ExampleData.BUDGET_APP_NAME, overview.budgetAppName)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 2: every Bank Consent that expires within the consent warning lead time is shown with its expiry date")
    fun expiringBankConsentsAreShown() = runTest {
        household()
        slice.importStatusStore.consents.clear()
        val aurora = slice.connectionStore.bankNamed("Aurora Bank").id
        val vesta = slice.connectionStore.bankNamed("Vesta Sparbank").id
        slice.importStatusStore.consents += StoredBankConsent(1, aurora, slice.openBankingId, now.minus(Duration.ofDays(170)), now.plus(Duration.ofDays(4)))
        slice.importStatusStore.consents += StoredBankConsent(2, vesta, slice.openBankingId, now.minus(Duration.ofDays(10)), now.plus(Duration.ofDays(9)))

        val warned = slice.status.open().expiringBankConsents

        assertEquals(listOf(ExpiringBankConsent("Aurora Bank", "Aurora Open Banking", now.plus(Duration.ofDays(4)))), warned)
        slice.settings.settings = slice.settings.settings.copy(consentWarningDays = 10)
        assertEquals(listOf("Aurora Bank", "Vesta Sparbank"), slice.status.open().expiringBankConsents.map { it.bankName })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario")
    @DisplayName("Step 3: every opening records the start of the session in the Decision Journal, unless it is switched off")
    fun openingRecordsTheSessionStart() = runTest {
        household()
        val before = slice.journalRecorder.entries.size

        slice.status.open()
        slice.status.open()
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)
        slice.status.open()

        val starts = slice.journalRecorder.entries.drop(before)
        assertEquals(listOf(JournalEventKind.SessionStart, JournalEventKind.SessionStart), starts.map { it.eventKind })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Steps 4-5: a selected Budget shows only its own Sources and Bank Accounts")
    fun workspaceShowsOnlyThatBudget() = runTest {
        household()

        val household = slice.status.workspace(budgetId("Household"))
        val summer = slice.status.workspace(budgetId("Summer House"))

        assertEquals(listOf("Kestrel Checking", "Pinecrest Rewards Card"), household.bankAccounts.map { it.name })
        assertEquals(listOf("Kestrel Feeds"), household.sources.map { it.name })
        assertEquals(listOf("Aurora Current", "Aurora Dollar"), summer.bankAccounts.map { it.name })
        assertEquals(listOf("Aurora Open Banking"), summer.sources.map { it.name })
        assertEquals("Household", household.summary.name)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Step 6: Source Health shows only the last Fetch's outcome, Freshness separately how old the data is, and the counts not yet uploaded")
    fun healthAndFreshnessAreSeparate() = runTest {
        household()
        val fetchedAt = now.minus(Duration.ofHours(30))
        val failedAt = now.minus(Duration.ofHours(2))
        fetch(slice.feedsId, fetchedAt, FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched, "Pinecrest Rewards Card" to FetchResultOutcome.Fetched)
        fetch(slice.feedsId, failedAt, FetchOutcome.Failed, "Kestrel Checking" to FetchResultOutcome.Failed, "Pinecrest Rewards Card" to FetchResultOutcome.Fetched)
        counts("Kestrel Checking", eligible = 5, waiting = 6)
        counts("Pinecrest Rewards Card", eligible = 2, waiting = 1)

        val workspace = slice.status.workspace(budgetId("Household"))

        val source = workspace.sources.single()
        assertEquals(SourceHealth.FetchHealth(FetchOutcome.Failed, failedAt, StartedBy.Operator), source.health)
        assertEquals(fetchedAt, source.freshness)
        assertEquals(NotYetUploaded(7, 7), source.notYetUploaded)
        val checking = workspace.bankAccounts.first()
        assertEquals(SourceHealth.BankAccountFetchHealth(FetchResultOutcome.Failed, failedAt, StartedBy.Operator), checking.health)
        assertEquals(fetchedAt, checking.freshness)
        assertEquals(NotYetUploaded(5, 6), checking.notYetUploaded)
        val card = workspace.bankAccounts[1]
        assertEquals(SourceHealth.BankAccountFetchHealth(FetchResultOutcome.Fetched, failedAt, StartedBy.Operator), card.health)
        assertEquals(failedAt, card.freshness)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Step 6: old data never changes the Source Health; a file-imported Bank Account's health is its last Transaction File import")
    fun oldDataNeverChangesTheHealth() = runTest {
        household()
        val fileFormat = slice.plugins.fileFormats.single().id
        bankAccount("Harbor Health Savings", "Harbor Health Trust", null, USD, BankAccountType.Savings, "Health Savings", fileFormatId = fileFormat)
        val longAgo = now.minus(Duration.ofDays(20))
        fetch(slice.feedsId, longAgo, FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched)
        slice.importStatusStore.files += StoredTransactionFile(id("Harbor Health Savings"), longAgo, TransactionFileOutcome.Imported)

        val checking = workspaceAccount("Household", "Kestrel Checking")
        val harbor = slice.status.workspace(budgetId("Care Fund"))

        assertEquals(SourceHealth.BankAccountFetchHealth(FetchResultOutcome.Fetched, longAgo, StartedBy.Operator), checking.health)
        assertEquals(longAgo, checking.freshness)
        val file = harbor.bankAccounts.single()
        assertEquals(SourceHealth.FileImportHealth(TransactionFileOutcome.Imported, longAgo), file.health)
        assertEquals(longAgo, file.freshness)
        assertTrue(harbor.sources.single().isFileFormat)
        assertEquals("Harbor Health statement", harbor.sources.single().name)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("Step 7: each Account Balance is shown next to its Budget Account's balance, with the difference and the time each was read")
    fun balanceDifferenceIsShown() = runTest {
        household()
        val reportedAt = now.minus(Duration.ofHours(1))
        slice.importStatusStore.balances += StoredAccountBalance(id("Kestrel Checking"), amount(5135, 280), USD, reportedAt)

        val checking = workspaceAccount("Household", "Kestrel Checking")

        assertEquals(
            BalanceComparison.Compared(
                accountBalance = amount(5135, 280),
                budgetAccountBalance = amount(4140, 50),
                difference = amount(995, 230),
                accountBalanceReadAt = reportedAt,
                budgetAccountBalanceReadAt = now,
            ),
            checking.balance,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Step 8: each Blocked Reason is shown with the action that removes it or the time it ends")
    fun blockedReasonsCarryTheirRemedies() = runTest {
        household()
        slice.importStatusStore.consents.clear()
        val vesta = slice.connectionStore.bankNamed("Vesta Sparbank").id
        bankAccount("Vesta Card", "Vesta Sparbank", slice.openBankingId, EUR, BankAccountType.CreditCard, "Card")
        val aurora = slice.connectionStore.bankNamed("Aurora Bank").id
        slice.importStatusStore.consents += StoredBankConsent(1, aurora, slice.openBankingId, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(100)))
        slice.importStatusStore.consents += StoredBankConsent(2, vesta, slice.openBankingId, now.minus(Duration.ofDays(200)), now.minus(Duration.ofDays(4)))
        val failedAt = now.minus(Duration.ofHours(3))
        fetch(slice.feedsId, failedAt, FetchOutcome.Failed, "Kestrel Checking" to FetchResultOutcome.Failed, "Pinecrest Rewards Card" to FetchResultOutcome.NotRecognised)
        slice.credentials.remove(slice.openBankingKey)

        val household = slice.status.workspace(budgetId("Household"))
        val summer = slice.status.workspace(budgetId("Summer House"))

        val checking = household.bankAccounts.single { it.name == "Kestrel Checking" }.blocked.single()
        assertEquals(Blocked(BlockedReason.FailedFetch, Remedy.FetchAgain, failedAt, "Kestrel Checking"), checking)
        val card = household.bankAccounts.single { it.name == "Pinecrest Rewards Card" }.blocked.single()
        assertEquals(BlockedReason.UnrecognisedBankAccount, card.reason)
        assertEquals(Remedy.IdentifyBankAccount, card.remedy)
        val source = summer.sources.single().blocked
        assertEquals(listOf(Blocked(BlockedReason.CredentialsMissing, Remedy.EnterCredentials, null, "Aurora Open Banking")), source)
        val vestaCard = summer.bankAccounts.single { it.name == "Vesta Card" }.blocked
        assertTrue(Blocked(BlockedReason.ExpiredBankConsent, Remedy.GrantBankConsent, now.minus(Duration.ofDays(4)), "Vesta Card") in vestaCard)
        val dollar = summer.bankAccounts.single { it.name == "Aurora Dollar" }.blocked
        assertTrue(Blocked(BlockedReason.CurrencyMismatch, Remedy.MoveAccountMapping, null, "Aurora Dollar") in dollar)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Step 8: a missing Bank Consent, a Back-off and refused credentials are Blocked Reasons with their remedy or end")
    fun moreBlockedReasons() = runTest {
        household()
        slice.importStatusStore.consents.clear()
        slice.clock.advance(Duration.ofHours(2))
        val waitEnds = now.plus(Duration.ofHours(5))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, now.minus(Duration.ofHours(3)), waitEnds)
        fetch(slice.openBankingId, now.minus(Duration.ofHours(1)), FetchOutcome.CredentialsRefused)

        val household = slice.status.workspace(budgetId("Household"))
        val summer = slice.status.workspace(budgetId("Summer House"))

        assertEquals(
            listOf(Blocked(BlockedReason.WaitingForProvider, Remedy.WaitUntilItEnds, waitEnds, "Kestrel Feeds")),
            household.sources.single().blocked,
        )
        assertTrue(
            Blocked(BlockedReason.CredentialsRefused, Remedy.EnterCredentials, null, "Aurora Open Banking") in summer.sources.single().blocked,
        )
        val current = summer.bankAccounts.single { it.name == "Aurora Current" }.blocked
        assertTrue(Blocked(BlockedReason.MissingBankConsent, Remedy.GrantBankConsent, null, "Aurora Current") in current)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-006"], requirements = ["NFR-018"])
    @DisplayName("Step 8: a reached Request Limit or access limit is shown with the time the limit frees up, counted over the preceding 24 hours")
    fun reachedLimitsShowWhenTheyFreeUp() = runTest {
        household()
        slice.providerRequests.requests.clear()
        val oldest = now.minus(Duration.ofHours(20))
        repeat(FEEDS_REQUEST_LIMIT) { index ->
            slice.providerRequests.requests += StoredProviderRequest(slice.feedsId, null, ProviderRequestKind.Other, false, oldest.plus(Duration.ofMinutes(index.toLong())))
        }
        slice.providerRequests.requests += StoredProviderRequest(slice.feedsId, null, ProviderRequestKind.Other, false, now.minus(Duration.ofHours(30)))
        val accessAt = now.minus(Duration.ofHours(10))
        repeat(4) { index ->
            slice.providerRequests.requests += StoredProviderRequest(
                slice.openBankingId, id("Aurora Current"), ProviderRequestKind.Transactions, true, accessAt.plus(Duration.ofMinutes(index.toLong())),
            )
        }
        fetch(slice.openBankingId, now.minus(Duration.ofHours(1)), FetchOutcome.Fetched, "Aurora Current" to FetchResultOutcome.AccessLimitReached, startedBy = StartedBy.Schedule)

        val feeds = slice.status.workspace(budgetId("Household")).sources.single().blocked
        val current = workspaceAccount("Summer House", "Aurora Current").blocked

        assertEquals(listOf(Blocked(BlockedReason.RequestLimitReached, Remedy.WaitUntilItEnds, oldest.plus(Duration.ofHours(24)), "Kestrel Feeds")), feeds)
        assertTrue(Blocked(BlockedReason.AccessLimitReached, Remedy.WaitUntilItEnds, accessAt.plus(Duration.ofHours(24)), "Aurora Current") in current)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", requirements = ["NFR-005"])
    @DisplayName("A1: a part that could not be read is named in a notice; damage to the Decision Journal opens no First Upload Matching")
    fun unreadablePartIsNamed() = runTest {
        val damaged = TestSlice(unreadableParts = listOf(UnreadablePart(StoredPart.DecisionJournal, startedFromLastGoodCopy = false)))

        val overview = damaged.status.open()

        assertEquals(listOf(UnreadableNotice(StoredPart.DecisionJournal, startedFromLastGoodCopy = false)), overview.unreadable)
        assertNull(overview.firstUploadMatching)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", businessRules = ["BR-009"], requirements = ["NFR-005"])
    @DisplayName("A1, BR-009: Decision-Critical Data that could not be read is named, started from the last good copy, and its First Upload Matching is shown as a caution with the reason of its cause")
    fun unreadableDecisionCriticalDataShowsFirstUploadMatching() = runTest {
        val damaged = TestSlice(
            startKind = StartKind.LastGoodCopy,
            unreadableParts = listOf(UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGoodCopy = true)),
        )
        damaged.firstUploadMatchingStore.open(FirstUploadMatchingCause.LastGoodCopy, damaged.clock.instant())

        val overview = damaged.status.open()

        assertEquals(StoredPart.TransactionsDecisionsAndSettings, overview.unreadable.single().part)
        assertTrue(overview.unreadable.single().startedFromLastGoodCopy)
        assertEquals(
            FirstUploadMatchingNotice(
                listOf(FirstUploadMatchingCause.LastGoodCopy), FirstUploadMatchingTone.Caution, damaged.clock.instant(), canStart = false,
            ),
            overview.firstUploadMatching,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A2: Account Balance Outdated", businessRules = ["BR-004"])
    @DisplayName("A2: an Account Balance older than the outdated-balance limit (36 hours by default) hides the difference and is marked outdated")
    fun outdatedBalanceHidesTheDifference() = runTest {
        household()
        val old = now.minus(Duration.ofHours(37))
        slice.importStatusStore.balances += StoredAccountBalance(id("Kestrel Checking"), amount(5135, 280), USD, old)
        slice.importStatusStore.balances += StoredAccountBalance(id("Pinecrest Rewards Card"), amount(-547, 900), USD, now.minus(Duration.ofHours(35)))

        assertEquals(
            BalanceComparison.Outdated(amount(5135, 280), amount(4140, 50), old),
            workspaceAccount("Household", "Kestrel Checking").balance,
        )
        assertTrue(workspaceAccount("Household", "Pinecrest Rewards Card").balance is BalanceComparison.Compared)
        slice.settings.settings = slice.settings.settings.copy(outdatedBalanceHours = 48)
        assertTrue(workspaceAccount("Household", "Kestrel Checking").balance is BalanceComparison.Compared)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A3: Currency Mismatch", businessRules = ["BR-006"])
    @DisplayName("A3: a Bank Account whose currency differs from its Budget's hides the difference and shows currency mismatch")
    fun currencyMismatchHidesTheDifference() = runTest {
        household()
        slice.importStatusStore.balances += StoredAccountBalance(id("Aurora Dollar"), amount(1520), USD, now)

        val dollar = workspaceAccount("Summer House", "Aurora Dollar")

        assertEquals(BalanceComparison.CurrencyMismatch(USD, EUR), dollar.balance)
        assertEquals(BlockedReason.CurrencyMismatch, dollar.blocked.single().reason)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A4: Balance Not Known")
    @DisplayName("A4: a balance that is not known is named and no difference is shown")
    fun unknownBalanceShowsNoDifference() = runTest {
        household()
        slice.importStatusStore.balances += StoredAccountBalance(id("Aurora Current"), amount(3410, 800), EUR, now)
        val summer = slice.connectionStore.budgetList.single { it.name == "Summer House" }
        slice.connectionStore.budgetList.replaceAll { budget ->
            if (budget.id != summer.id) budget else budget.copy(
                budgetAccounts = budget.budgetAccounts.map { it.copy(balance = null, balanceReadAt = null) },
            )
        }

        assertEquals(
            BalanceComparison.NotKnown(accountBalanceKnown = false, budgetAccountBalanceKnown = true, accountBalance = null, budgetAccountBalance = amount(4140, 50)),
            workspaceAccount("Household", "Kestrel Checking").balance,
        )
        assertEquals(
            BalanceComparison.NotKnown(accountBalanceKnown = true, budgetAccountBalanceKnown = false, accountBalance = amount(3410, 800), budgetAccountBalance = null),
            workspaceAccount("Summer House", "Aurora Current").balance,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5: the most recent Fetches, as many as the Operator Setting keeps, with Source, time, who started them and outcome")
    fun recentFetchesAreShown() = runTest {
        household()
        repeat(12) { index ->
            fetch(
                if (index % 2 == 0) slice.feedsId else slice.openBankingId,
                now.minus(Duration.ofHours(index.toLong())),
                if (index == 0) FetchOutcome.Failed else FetchOutcome.Fetched,
                startedBy = if (index == 1) StartedBy.Schedule else StartedBy.Operator,
            )
        }
        slice.settings.settings = slice.settings.settings.copy(fetchHistorySize = 10)

        val recent = slice.status.recentFetches()

        assertEquals(10, recent.size)
        assertEquals(RecentFetch("Kestrel Feeds", now, StartedBy.Operator, FetchOutcome.Failed), recent[0])
        assertEquals(RecentFetch("Aurora Open Banking", now.minus(Duration.ofHours(1)), StartedBy.Schedule, FetchOutcome.Fetched), recent[1])
        assertEquals(recent.sortedByDescending { it.startedAt }, recent)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A7: Backed-Up Data Nears the Backup Limit", businessRules = ["BR-010"], requirements = ["NFR-019", "C-015"])
    @DisplayName("A7: from the backup warning threshold (80% by default) of Android's 25 MB limit, the size and the limit are shown")
    fun backupSizeWarning() = runTest {
        household()
        val limit = 25L * 1024 * 1024
        slice.backupSize.bytes = limit * 79 / 100

        assertNull(slice.status.open().backupWarning)
        slice.backupSize.bytes = limit * 80 / 100
        assertEquals(BackupWarning(limit * 80 / 100, limit), slice.status.open().backupWarning)
        slice.settings.settings = slice.settings.settings.copy(backupWarningPercent = 90)
        assertNull(slice.status.open().backupWarning)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009", "BR-006"])
    @DisplayName("A8, BR-006, BR-009: while First Upload Matching is open, every opening shows it with the reason of its cause, as plain information after a new installation, and each Budget shows Uploads wait for First Upload Matching with the action Start First Upload Matching")
    fun openFirstUploadMatchingIsShown() = runTest {
        household()
        val openedAt = now.minus(Duration.ofDays(1))
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, openedAt)
        val expected = FirstUploadMatchingNotice(
            listOf(FirstUploadMatchingCause.NewInstallation), FirstUploadMatchingTone.Information, openedAt, canStart = false,
        )

        val overview = slice.status.open()

        assertEquals(expected, overview.firstUploadMatching)
        overview.budgets.forEach { budget ->
            assertTrue(
                budget.blocked.contains(
                    Blocked(BlockedReason.UploadsWaitForFirstUploadMatching, Remedy.StartFirstUploadMatching, openedAt, budget.name),
                ),
            )
        }
        assertEquals(expected, slice.status.workspace(budgetId("Household")).firstUploadMatching)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("BR-009: a restore from a device backup is a caution; while several are open, the line of each cause is shown once and a caution wins beside a new installation; with none open nothing is shown")
    fun eachCauseIsShownOnceAndACautionWins() = runTest {
        household()
        assertNull(slice.status.open().firstUploadMatching)
        assertTrue(slice.status.open().budgets.none { budget -> budget.blocked.any { it.reason == BlockedReason.UploadsWaitForFirstUploadMatching } })
        val first = now.minus(Duration.ofDays(3))
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.RestoredBackup, first)
        assertEquals(
            FirstUploadMatchingNotice(listOf(FirstUploadMatchingCause.RestoredBackup), FirstUploadMatchingTone.Caution, first, canStart = false),
            slice.status.open().firstUploadMatching,
        )
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, now.minus(Duration.ofDays(2)))
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.RestoredBackup, now.minus(Duration.ofDays(1)))

        val shown = slice.status.open().firstUploadMatching

        assertEquals(
            FirstUploadMatchingNotice(
                listOf(FirstUploadMatchingCause.RestoredBackup, FirstUploadMatchingCause.NewInstallation),
                FirstUploadMatchingTone.Caution,
                now.minus(Duration.ofDays(1)),
                canStart = false,
            ),
            shown,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("BR-009: two First Upload Matchings of a new installation show its line once, as plain information")
    fun twoNewInstallationsShowOneLine() = runTest {
        household()
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, now.minus(Duration.ofHours(2)))
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, now)

        assertEquals(
            FirstUploadMatchingNotice(listOf(FirstUploadMatchingCause.NewInstallation), FirstUploadMatchingTone.Information, now, canStart = false),
            slice.status.open().firstUploadMatching,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A9: Operator Confirms First Upload Matching", businessRules = ["BR-009"])
    @DisplayName("A9: First Upload Matching cannot be confirmed without a Comparison that read every Budget, which no slice builds yet (O-P2-09); it stays open and nothing is recorded")
    fun firstUploadMatchingStaysOpenWithoutAComparison() = runTest {
        household()
        assertEquals(FirstUploadMatchingStart.NoneOpen, slice.status.startFirstUploadMatching())
        slice.firstUploadMatchingStore.open(FirstUploadMatchingCause.NewInstallation, now)

        val start = slice.status.startFirstUploadMatching()

        assertEquals(FirstUploadMatchingStart.ComparisonNotAvailable, start)
        assertEquals(1, slice.firstUploadMatchingStore.openChecks().size)
        assertTrue(slice.journalRecorder.entries.none { "First Upload Matching" in it.details })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("A10: while no Bank Account is added, opening Slussen gives only the card that leads to Settings, naming the next thing to do, and records the start of the session")
    fun withoutBankAccountsOpeningGivesTheFirstRunCard() = runTest {
        val overview = slice.status.open()

        assertTrue(overview.noBankAccountAdded)
        assertEquals(FirstRunCard(NextThing.EnterBudgetAppCredentials(ExampleData.BUDGET_APP_NAME)), overview.firstRun)
        assertTrue(overview.budgets.isEmpty())
        assertEquals(JournalEventKind.SessionStart, slice.journalRecorder.entries.single().eventKind)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("BR-011: the card names the first thing UC-013 BR-008 recommends that is not yet done, here a Data Provider's Fetch once the Budgets are chosen")
    fun theCardNamesTheNextThing() = runTest {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, setOf("budget-household"), now)

        val overview = slice.status.open()

        assertEquals(FirstRunCard(NextThing.FetchOrGrantBankConsent(listOf("Kestrel Feeds"), emptyList())), overview.firstRun)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A10: No Bank Account Added Yet", businessRules = ["BR-011"])
    @DisplayName("BR-011: from the first added Bank Account on, opening Slussen shows the overview of the main success scenario, without the card")
    fun fromTheFirstAddedBankAccountTheOverviewShows() = runTest {
        household()

        val overview = slice.status.open()

        assertNull(overview.firstRun)
        assertFalse(overview.noBankAccountAdded)
        assertEquals(listOf("Household", "Summer House", "Care Fund"), overview.budgets.map { it.name })
    }

    @Test
    @UseCase(id = "UC-008", businessRules = ["BR-001"])
    @DisplayName("BR-001: opening Slussen and showing a Budget contacts no Source, no Bank and not the Budget App")
    fun openingContactsNothing() = runTest {
        household()
        val reads = slice.budgetApp.reader.budgetReads
        val requests = slice.providerRequests.requests.size

        slice.status.open()
        slice.status.workspace(budgetId("Household"))
        slice.status.recentFetches()

        assertEquals(reads, slice.budgetApp.reader.budgetReads)
        assertEquals(requests, slice.providerRequests.requests.size)
        assertEquals(0, slice.feeds.fetches + slice.openBanking.fetches)
        assertEquals(0, slice.budgetApp.writer.calls)
    }
    @Test
    @UseCase(id = "UC-008", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-005"])
    @DisplayName("Steps 6-7 after a Fetch (UC-001 steps 8, 11): each Bank Account the Fetch read shows its Source Health and Freshness from its Fetch Result, and its Account Balance next to its Budget Account's balance with the difference and the time each was read")
    fun whatAFetchStoredIsShown() = runTest {
        household()
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(
                    DeliveredBankAccount("ref-${id("Kestrel Checking")}", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Read(emptyList(), amount(5135, 280))),
                    DeliveredBankAccount("ref-${id("Pinecrest Rewards Card")}", "Pinecrest Card Co.", "Visa", "credit card", USD, null, BankAccountResult.Failed(FailureKind.Timeout)),
                ),
            )
        }
        val fetchedAt = now

        assertTrue(slice.fetch.fetch(slice.feedsKey) is FetchStart.Ran)
        slice.clock.advance(Duration.ofMinutes(5))

        val checking = workspaceAccount("Household", "Kestrel Checking")
        assertEquals(SourceHealth.BankAccountFetchHealth(FetchResultOutcome.Fetched, fetchedAt, StartedBy.Operator), checking.health)
        assertEquals(fetchedAt, checking.freshness)
        assertEquals(
            BalanceComparison.Compared(
                accountBalance = amount(5135, 280),
                budgetAccountBalance = amount(4140, 50),
                difference = amount(995, 230),
                accountBalanceReadAt = fetchedAt,
                budgetAccountBalanceReadAt = fetchedAt,
            ),
            checking.balance,
        )
        assertTrue(checking.blocked.isEmpty())
        val card = workspaceAccount("Household", "Pinecrest Rewards Card")
        assertEquals(SourceHealth.BankAccountFetchHealth(FetchResultOutcome.Failed, fetchedAt, StartedBy.Operator), card.health)
        assertNull(card.freshness)
        assertEquals(listOf(Blocked(BlockedReason.FailedFetch, Remedy.FetchAgain, fetchedAt, "Pinecrest Rewards Card")), card.blocked)
        assertEquals(slice.feedsId, card.dataProviderId)
        assertEquals(SourceHealth.FetchHealth(FetchOutcome.Fetched, fetchedAt, StartedBy.Operator), slice.status.workspace(budgetId("Household")).sources.single().health)
    }
}
