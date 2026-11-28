package slussen.core.request

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonNotices
import slussen.core.comparison.ComparisonReport
import slussen.core.connection.BudgetsRead
import slussen.core.fetch.FetchNotices
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.fetch.FetchTransactions
import slussen.core.fetch.ScheduledFetchOutcome
import slussen.core.fileimport.FileImportOutcome
import slussen.core.firstupload.FirstUploadMatching
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.RunningOperation
import slussen.core.port.AddOutcome
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.NewProviderRequest
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredProviderRequest
import slussen.core.status.BlockedReason
import slussen.core.status.ImportStatus
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.StandardImportFile
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration

/**
 * The Scheduled Fetch of UC-001 (A12, A14, the scheduled half of A1, BR-012's and BR-013's rules for it) and the Fetch of
 * one Budget (A11), in the core on the fakes of `:testing` (phase D5). The coordinator's origin Schedule is admitted in
 * Normal and deferred while an Upload Plan awaits confirmation or is written; Uploads are not built yet, so a test sets
 * those modes in the stored state and plays a later process by building a new coordinator and Fetch on the same stores.
 */
@DisplayName("UC-001: Fetch Transactions, the Scheduled Fetch and the Fetch of one Budget")
class UC001ScheduledFetchTest {
    private val slice = TestSlice()

    // ---- Set-up --------------------------------------------------------------------------------------------------

    private fun delivered(reference: String, name: String, balance: Amount? = amount(100), currency: CurrencyCode? = USD) =
        DeliveredBankAccount(
            providerReference = reference,
            bankReference = "Kestrel Bank",
            name = name,
            kind = "checking",
            currency = currency,
            number = null,
            result = BankAccountResult.Read(emptyList(), balance),
        )

    private fun answerFeeds(outcome: SourceOutcome = SourceOutcome.Completed, vararg accounts: DeliveredBankAccount) {
        val listed = if (accounts.isEmpty()) listOf(delivered("acct-4410", "CHK", amount(4140, 50))) else accounts.toList()
        slice.feeds.fetchAnswer = { FetchResponse(outcome, emptyList(), if (outcome is SourceOutcome.TransientFailure) emptyList() else listed) }
    }

    private fun answerWren() {
        slice.wren.fetchAnswer = { FetchResponse(SourceOutcome.Completed, emptyList(), listOf(delivered("wren-1", "Wren Everyday"))) }
    }

    /** The credentials of the Budget App and Kestrel Feeds are accepted, and every Budget is served. */
    private suspend fun ready() {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
    }

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun budgetId(reference: String): Long =
        slice.connectionStore.budgets().single { it.budgetAppReference == reference }.id

    /** A Bank Account the Operator added for the Data Provider [dataProviderId] (UC-013), mapped to [budgetAccount]. */
    private suspend fun addBankAccount(
        dataProviderId: Long,
        reference: String,
        name: String,
        budgetAccount: String,
        currency: CurrencyCode = USD,
    ): Long = (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-${reference.takeLast(4).padStart(12, '0')}",
                bank = BankChoice.Named("Kestrel Bank"),
                providerBankId = null,
                dataProviderId = dataProviderId,
                fileFormatId = null,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = name,
                currency = currency,
                type = BankAccountType.Checking,
                providerReference = reference,
                bankAccountFingerprint = null,
                identifiedAt = slice.clock.instant(),
                startDate = null,
                budgetAccountId = budgetAccountId(budgetAccount),
            ),
        ) as AddOutcome.Added
        ).bankAccountId

    /** A Bank Account whose transactions come from Transaction Files and whose balance Kestrel Feeds reports (BR-011). */
    private suspend fun addFileBankAccount(balanceReference: String, budgetAccount: String): Long = (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-00000000f11e",
                bank = BankChoice.Named("Harbor Health Trust"),
                providerBankId = null,
                dataProviderId = null,
                fileFormatId = slice.plugins.fileFormats.single().id,
                balanceDataProviderId = slice.feedsId,
                balanceProviderReference = balanceReference,
                name = "Harbor Health Savings",
                currency = USD,
                type = BankAccountType.Savings,
                providerReference = null,
                bankAccountFingerprint = null,
                identifiedAt = slice.clock.instant(),
                startDate = null,
                budgetAccountId = budgetAccountId(budgetAccount),
            ),
        ) as AddOutcome.Added
        ).bankAccountId

    /** Kestrel Feeds with credentials and Kestrel Checking added, mapped to Household's Checking; returns its id. */
    private suspend fun feedsWithChecking(): Long {
        ready()
        val checking = addBankAccount(slice.feedsId, "acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        return checking
    }

    private suspend fun scheduled(): ScheduledFetchOutcome.Ran = slice.fetch.scheduledFetch() as ScheduledFetchOutcome.Ran

    private suspend fun fetchesOfFeeds() = slice.importStatusStore.fetchesOf(slice.feedsId)

    /** Who started each recorded Fetch of Kestrel Feeds and its outcome, most recent first. */
    private suspend fun recordedOfFeeds() = fetchesOfFeeds().map { it.startedBy to it.outcome }

    /** The Fetch of a later process on the same stored data: a new coordinator, nothing held in memory (ADR-007 §8). */
    private fun fetchOfALaterProcess(): FetchTransactions = FetchTransactions(
        slice.plugins, slice.credentials, slice.connectionStore, slice.offeredStore, slice.importStatusStore, slice.providerRequests,
        OperationCoordinator(slice.coordinatorStore), slice.settings, slice.journal, slice.clock, FetchNotices(), slice.comparison,
    )

    /** The import status of a later process: what it shows comes from stored data only (BR-013). */
    private fun statusOfALaterProcess(): ImportStatus = ImportStatus(
        budgetAppName = slice.plugins.budgetApp.profile.name,
        currentDataProviders = { slice.plugins.dataProviders.map { it.stored } },
        fileFormats = slice.plugins.fileFormats,
        connections = slice.connectionStore,
        stored = slice.importStatusStore,
        providerRequests = slice.providerRequests,
        credentials = slice.credentials,
        firstUploadMatching = FirstUploadMatching(slice.firstUploadMatchingStore),
        startState = slice.startState,
        backupSize = slice.backupSize,
        settings = slice.settings,
        journal = slice.journal,
        clock = slice.clock,
        firstRunNext = { slice.settingsOverview.next() },
        fetchNotices = FetchNotices(),
        comparisonNotices = ComparisonNotices(),
        periodReadOwed = { slice.copyStore.periodReadOwed(it) },
    )

    // ---- A12: the Scheduled Fetch ----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-012", "BR-013"])
    @DisplayName("A12 step 1, BR-012, BR-013: a Scheduled Fetch asks without the Operator present, and keeps every request as an unattended Provider Request with the Bank Account it reads")
    fun scheduledFetchIsUnattended() = runTest {
        val checking = feedsWithChecking()

        val ran = scheduled()

        assertInstanceOf(FetchReport.Fetched::class.java, ran.reports.single())
        assertEquals(OperatorPresence.Unattended, slice.feeds.fetchRequests.single().presence)
        assertEquals(
            listOf(StoredProviderRequest(slice.feedsId, checking, ProviderRequestKind.Transactions, unattended = true, sentAt = slice.clock.instant())),
            slice.providerRequests.requests.filter { it.dataProviderId == slice.feedsId && it.kind != ProviderRequestKind.Other },
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a request of a Scheduled Fetch that reads several Bank Accounts at once names none; it is still unattended")
    fun requestForSeveralBankAccountsNamesNone() = runTest {
        feedsWithChecking()
        addBankAccount(slice.feedsId, "acct-7731", "Kestrel Reserve", "Reserve Savings")
        answerFeeds(SourceOutcome.Completed, delivered("acct-4410", "CHK"), delivered("acct-7731", "Reserve Saver"))

        scheduled()

        val request = slice.providerRequests.requests.single { it.dataProviderId == slice.feedsId && it.kind != ProviderRequestKind.Other }
        assertTrue(request.unattended)
        assertNull(request.bankAccountId)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-012"])
    @DisplayName("BR-012: a Fetch the Operator starts is still made with the Operator present, and its requests are not unattended")
    fun operatorsFetchStaysAttended() = runTest {
        feedsWithChecking()

        slice.fetch.fetch(slice.feedsKey)

        assertEquals(OperatorPresence.OperatorPresent, slice.feeds.fetchRequests.single().presence)
        val request = slice.providerRequests.requests.single { it.dataProviderId == slice.feedsId && it.kind != ProviderRequestKind.Other }
        assertFalse(request.unattended)
        assertNull(request.bankAccountId)
        assertEquals(listOf(StartedBy.Operator to FetchOutcome.Fetched), recordedOfFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("A12 step 2: the Fetch is recorded as started by the Fetch Schedule with its outcome for each Bank Account, and the Comparison with the Budget App follows")
    fun scheduledFetchIsRecordedAndCompared() = runTest {
        val checking = feedsWithChecking()

        val ran = scheduled()

        assertEquals(listOf(StartedBy.Schedule to FetchOutcome.Fetched), recordedOfFeeds())
        assertEquals(listOf(checking to FetchResultOutcome.Fetched), fetchesOfFeeds().single().results.map { it.bankAccountId to it.outcome })
        assertInstanceOf(ComparisonReport.Compared::class.java, ran.comparison)
        assertTrue(slice.budgetApp.reader.changeReads.isNotEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("A12 step 4, BR-013: a Scheduled Fetch prepares no Upload Plan and writes nothing to the Budget App; origin Schedule starts no other operation")
    fun scheduledFetchPreparesNoUploadPlan() = runTest {
        feedsWithChecking()

        scheduled()

        assertEquals(0, slice.budgetApp.writer.calls)
        assertEquals(CoordinatorMode.Normal, slice.coordinatorStore.mode)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { slice.coordinator.run(OperationKind.Comparison, StartedBy.Schedule) { } }
        }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013", "BR-010"])
    @DisplayName("BR-013: a Scheduled Fetch fetches every Data Provider in use in turn; one in use without credentials is not contacted and nothing is recorded for it; one not set up is left out")
    fun scheduledFetchFetchesEveryDataProviderInUse() = runTest {
        feedsWithChecking()
        addBankAccount(slice.wrenKey.dataProviderId!!, "wren-1", "Wren Everyday", "Current", ExampleData.EUR)
        answerWren()

        val ran = scheduled()

        assertEquals(
            listOf(FetchReport.CredentialsMissing::class.java, FetchReport.Fetched::class.java),
            ran.reports.sortedBy { it.dataProviderName != "Wren Feeds" }.map { it.javaClass },
        )
        assertEquals(0, slice.wren.fetches)
        assertTrue(slice.importStatusStore.fetchesOf(slice.wrenKey.dataProviderId!!).isEmpty())
        assertEquals(0, slice.openBanking.fetches)
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-007", "BR-013"])
    @DisplayName("BR-007, BR-013: the requests of a Scheduled Fetch count against the Request Limit like any request; at the limit nothing is sent")
    fun scheduledFetchCountsAgainstTheRequestLimit() = runTest {
        feedsWithChecking()
        // The credential check of UC-014 was the first counted request; 18 more leave room for one.
        repeat(18) {
            slice.providerRequests.admitWithinLimit(
                NewProviderRequest(slice.feedsId, null, ProviderRequestKind.Other, unattended = false, sentAt = slice.clock.instant()),
                slice.clock.instant().minus(Duration.ofHours(24)), 20,
            )
        }

        assertInstanceOf(FetchReport.Fetched::class.java, scheduled().reports.single())
        slice.clock.advance(Duration.ofMinutes(61))
        val second = scheduled().reports.single()

        assertInstanceOf(FetchReport.RequestLimitReached::class.java, second)
        assertEquals(1, slice.feeds.requestsSent)
        assertEquals(listOf(StartedBy.Schedule to FetchOutcome.RequestLimitReached, StartedBy.Schedule to FetchOutcome.Fetched), recordedOfFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("BR-006: within the Fetch Cooldown a Scheduled Fetch is answered from the stored data without contacting the Data Provider, and the Comparison still reads the Budget App")
    fun scheduledFetchWithinTheCooldownContactsNothing() = runTest {
        feedsWithChecking()
        slice.fetch.fetch(slice.feedsKey)
        val readsBefore = slice.budgetApp.reader.changeReads.size
        slice.clock.advance(Duration.ofMinutes(30))

        val ran = scheduled()

        assertInstanceOf(FetchReport.ServedFromFetchCooldown::class.java, ran.reports.single())
        assertEquals(1, slice.feeds.requestsSent)
        assertEquals(StartedBy.Schedule to FetchOutcome.ServedFromFetchCooldown, recordedOfFeeds().first())
        assertTrue(slice.budgetApp.reader.changeReads.size > readsBefore)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-015"])
    @DisplayName("A1, BR-015: in a Scheduled Fetch a Back-off with cause Request Limit Warning that has not ended holds: the Data Provider is not contacted, waiting for the provider until its end, recorded Waiting For Back-off")
    fun requestLimitWarningHoldsAScheduledFetch() = runTest {
        feedsWithChecking()
        val endsAt = slice.clock.instant().plus(Duration.ofHours(24))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestLimitWarning, slice.clock.instant(), endsAt)

        val report = scheduled().reports.single()

        assertEquals(FetchReport.WaitingForProvider(slice.feedsKey, "Kestrel Feeds", endsAt), report)
        assertEquals(0, slice.feeds.fetches)
        assertEquals(listOf(StartedBy.Schedule to FetchOutcome.WaitingForBackOff), recordedOfFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A1, BR-004: a Back-off with cause Request To Wait holds for a Scheduled Fetch as for the Operator's")
    fun requestToWaitHoldsAScheduledFetch() = runTest {
        feedsWithChecking()
        val endsAt = slice.clock.instant().plus(Duration.ofHours(8))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), endsAt)

        assertEquals(FetchReport.WaitingForProvider(slice.feedsKey, "Kestrel Feeds", endsAt), scheduled().reports.single())
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A15: Data Provider Warns About Its Request Limit", businessRules = ["BR-015"])
    @DisplayName("A15, BR-015: a warning given to a Scheduled Fetch starts its Back-off, and the next Scheduled Fetch does not contact the Data Provider until it ends")
    fun warningInAScheduledFetchHoldsTheNext() = runTest {
        feedsWithChecking()
        answerFeeds(SourceOutcome.RequestLimitWarning)
        scheduled()
        slice.clock.advance(Duration.ofHours(12))

        assertInstanceOf(FetchReport.WaitingForProvider::class.java, scheduled().reports.single())
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A14: Transient Failure in a Scheduled Fetch", businessRules = ["BR-005", "BR-010"])
    @DisplayName("A14, BR-005: a timeout in a Scheduled Fetch keeps the stored data, records Failed with its time, starts no Back-off and is not repeated; the next Data Provider is still fetched")
    fun transientFailureInAScheduledFetchIsNotRepeated() = runTest {
        feedsWithChecking()
        slice.credentials.enter(slice.wrenKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        answerWren()
        answerFeeds(SourceOutcome.TransientFailure(FailureKind.Timeout))

        val reports = scheduled().reports

        assertEquals(FetchReport.Failed(slice.feedsKey, "Kestrel Feeds", FailureKind.Timeout, slice.clock.instant()), reports[0])
        assertInstanceOf(FetchReport.Fetched::class.java, reports[1])
        assertEquals(1, slice.feeds.fetches)
        assertTrue(slice.importStatusStore.backOffList.isEmpty())
        assertEquals(listOf(StartedBy.Schedule to FetchOutcome.Failed), recordedOfFeeds())
        assertTrue(slice.importStatusStore.balances.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A14: Transient Failure in a Scheduled Fetch", businessRules = ["BR-005"])
    @DisplayName("A14 step 2, BR-005: after a failed Scheduled Fetch the Operator can start a Fetch at once")
    fun operatorCanFetchAfterAFailedScheduledFetch() = runTest {
        feedsWithChecking()
        answerFeeds(SourceOutcome.TransientFailure(FailureKind.ServiceUnavailable))
        scheduled()
        answerFeeds()

        val report = (slice.fetch.fetch(slice.feedsKey) as FetchStart.Ran).reports.single()

        assertInstanceOf(FetchReport.Fetched::class.java, report)
        assertEquals(2, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: after the process that ran a Scheduled Fetch has ended, the Operator sees what it stored: the Fetch with who started it and its outcome, and the failure as the Source's Blocked Reason")
    fun outcomeIsShownFromStoredData() = runTest {
        feedsWithChecking()
        answerFeeds(SourceOutcome.TransientFailure(FailureKind.Timeout))
        val failedAt = slice.clock.instant()
        scheduled()

        val later = statusOfALaterProcess()

        val fetch = later.recentFetches().fetches.single()
        assertEquals(StartedBy.Schedule, fetch.startedBy)
        assertEquals(FetchOutcome.Failed, fetch.outcome)
        assertEquals(failedAt, fetch.startedAt)
        val household = later.workspace(budgetId("budget-household"))
        assertTrue(household.toString().contains(BlockedReason.FailedFetch.name))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: with the Fetch Schedule at 0 Scheduled Fetches are switched off: nothing is contacted, recorded or deferred")
    fun fetchScheduleOfZeroFetchesNothing() = runTest {
        feedsWithChecking()
        slice.settings.settings = slice.settings.settings.copy(scheduledFetchesPerDay = 0)

        assertEquals(ScheduledFetchOutcome.SwitchedOff, slice.fetch.scheduledFetch())
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(ScheduledFetchOutcome.SwitchedOff, slice.fetch.scheduledFetch())

        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.importStatusStore.fetches.isEmpty())
        assertFalse(slice.coordinatorStore.scheduledFetchDeferred)
    }

    // ---- BR-013, ADR-007 §2, §8: the deferral ---------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a Scheduled Fetch that falls due while an Upload Plan awaits confirmation or its Upload is written contacts nothing and is deferred in the stored state")
    fun scheduledFetchIsDeferredDuringAPause() = runTest {
        feedsWithChecking()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertEquals(ScheduledFetchOutcome.Deferred(OperationRefusal.UploadPlanAwaitingConfirmation), slice.fetch.scheduledFetch())
        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(ScheduledFetchOutcome.Deferred(OperationRefusal.UploadBeingWritten), slice.fetch.scheduledFetch())

        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.importStatusStore.fetches.isEmpty())
        assertTrue(slice.providerRequests.requests.none { it.kind != ProviderRequestKind.Other })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: when the pause ends in the running process the deferred Scheduled Fetch starts, as one however many fell due, and never a second time")
    fun deferredScheduledFetchStartsOnceWhenThePauseEnds() = runTest {
        feedsWithChecking()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        repeat(3) { slice.fetch.scheduledFetch() }
        slice.coordinatorStore.mode = CoordinatorMode.Normal

        val started = slice.fetch.scheduledFetchAfterPause()

        assertInstanceOf(FetchReport.Fetched::class.java, (started as ScheduledFetchOutcome.Ran).reports.single())
        assertEquals(listOf(StartedBy.Schedule to FetchOutcome.Fetched), recordedOfFeeds())
        assertFalse(slice.coordinatorStore.scheduledFetchDeferred)
        assertEquals(ScheduledFetchOutcome.NoneDeferred, slice.fetch.scheduledFetchAfterPause())
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: asked while the mode is still not Normal, the deferred Scheduled Fetch stays deferred; with none deferred nothing runs")
    fun deferredScheduledFetchWaitsForNormal() = runTest {
        feedsWithChecking()
        assertEquals(ScheduledFetchOutcome.NoneDeferred, slice.fetch.scheduledFetchAfterPause())
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        slice.fetch.scheduledFetch()

        assertEquals(ScheduledFetchOutcome.Deferred(OperationRefusal.UploadBeingWritten), slice.fetch.scheduledFetchAfterPause())

        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: the deferral is cleared in the storage step that records the start of the Scheduled Fetch, before any request leaves")
    fun deferralIsClearedWhenTheStartIsRecorded() = runTest {
        feedsWithChecking()
        slice.coordinatorStore.scheduledFetchDeferred = true
        var deferredWhenSent: Boolean? = null
        slice.feeds.beforeAnswer = { deferredWhenSent = slice.coordinatorStore.scheduledFetchDeferred }

        scheduled()

        assertEquals(false, deferredWhenSent)
        assertEquals(1, slice.coordinatorStore.scheduledFetchStarts)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-001", "BR-013"])
    @DisplayName("BR-001, BR-013: a deferral is not lost when the process ends: a later start opens Slussen without fetching and keeps it, and the next run of the Fetch Schedule is the one that fetches, once")
    fun deferralIsNotLostWhenTheProcessEnds() = runTest {
        feedsWithChecking()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        slice.fetch.scheduledFetch()
        // The process ends with the plan open; the later start finds the mode Normal again (ADR-007 §8).
        slice.coordinatorStore.mode = CoordinatorMode.Normal
        slice.feeds.fetchAnswer = { error("Opening Slussen must not fetch (UC-001 BR-001).") }
        val laterFetch = fetchOfALaterProcess()

        statusOfALaterProcess().open()

        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
        answerFeeds()
        assertInstanceOf(ScheduledFetchOutcome.Ran::class.java, laterFetch.scheduledFetch())
        assertFalse(slice.coordinatorStore.scheduledFetchDeferred)
        assertEquals(ScheduledFetchOutcome.NoneDeferred, laterFetch.scheduledFetchAfterPause())
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("BR-013: a deferred Scheduled Fetch whose start was recorded is not run twice when its process ends during it")
    fun startedDeferralIsNotRunTwice() = runTest {
        feedsWithChecking()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        slice.fetch.scheduledFetch()
        slice.coordinatorStore.mode = CoordinatorMode.Normal
        slice.feeds.beforeAnswer = { throw CancellationException("The device ended the process.") }
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { slice.fetch.scheduledFetchAfterPause() } }
        slice.feeds.beforeAnswer = {}

        val laterFetch = fetchOfALaterProcess()

        assertEquals(ScheduledFetchOutcome.NoneDeferred, laterFetch.scheduledFetchAfterPause())
        assertEquals(1, slice.feeds.fetches)
        assertFalse(slice.coordinatorStore.scheduledFetchDeferred)
    }

    // ---- ADR-007 §1, §2: a Scheduled Fetch racing another operation -----------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-006", "BR-013"])
    @DisplayName("ADR-007 §1: a Scheduled Fetch that falls due while the Operator's Fetch runs waits for it, then is answered from the Fetch Cooldown without a second request")
    fun scheduledFetchWaitsForTheOperatorsFetch() = runTest {
        feedsWithChecking()
        val hold = CompletableDeferred<Unit>()
        slice.feeds.beforeAnswer = { hold.await() }
        val operators = async { slice.fetch.fetch(slice.feedsKey) }
        runCurrent()

        val scheduledOne = async { slice.fetch.scheduledFetch() }
        runCurrent()

        assertEquals(1, slice.feeds.fetches)
        assertEquals(RunningOperation(OperationKind.Fetch, "Kestrel Feeds"), slice.coordinator.running.value)
        slice.feeds.beforeAnswer = {}
        slice.clock.advance(Duration.ofMinutes(1))
        hold.complete(Unit)
        assertInstanceOf(FetchReport.Fetched::class.java, (operators.await() as FetchStart.Ran).reports.single())
        val ran = scheduledOne.await() as ScheduledFetchOutcome.Ran
        assertInstanceOf(FetchReport.ServedFromFetchCooldown::class.java, ran.reports.single())
        assertEquals(1, slice.feeds.requestsSent)
        assertEquals(listOf(StartedBy.Schedule to FetchOutcome.ServedFromFetchCooldown, StartedBy.Operator to FetchOutcome.Fetched), recordedOfFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-006", "BR-013"])
    @DisplayName("ADR-007 §1: a Fetch the Operator starts while a Scheduled Fetch runs waits for it, then is answered from the Fetch Cooldown")
    fun operatorsFetchWaitsForTheScheduledFetch() = runTest {
        feedsWithChecking()
        val hold = CompletableDeferred<Unit>()
        slice.feeds.beforeAnswer = { hold.await() }
        val scheduledOne = async { slice.fetch.scheduledFetch() }
        runCurrent()

        val operators = async { slice.fetch.fetch(slice.feedsKey) }
        runCurrent()

        assertEquals(1, slice.feeds.fetches)
        slice.feeds.beforeAnswer = {}
        slice.clock.advance(Duration.ofMinutes(1))
        hold.complete(Unit)
        assertInstanceOf(ScheduledFetchOutcome.Ran::class.java, scheduledOne.await())
        assertInstanceOf(FetchReport.ServedFromFetchCooldown::class.java, (operators.await() as FetchStart.Ran).reports.single())
        assertEquals(listOf(StartedBy.Operator to FetchOutcome.ServedFromFetchCooldown, StartedBy.Schedule to FetchOutcome.Fetched), recordedOfFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("ADR-007 §1, §2: an import the Operator starts while a Scheduled Fetch runs waits for the lane and then runs; a Scheduled Fetch that waits behind an import runs after it")
    fun scheduledFetchAndImportTakeTurns() = runTest {
        feedsWithChecking()
        val file = addFileBankAccount("hsa-0042", "Health Savings")
        slice.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(emptyList())) }
        val hold = CompletableDeferred<Unit>()
        slice.feeds.beforeAnswer = { hold.await() }
        val scheduledOne = async { slice.fetch.scheduledFetch() }
        runCurrent()
        var importStarted = false

        val import = async { slice.fileImport.import(file, "an invented file".toByteArray(), started = { importStarted = true }) }
        runCurrent()

        assertFalse(importStarted)
        slice.feeds.beforeAnswer = {}
        slice.clock.advance(Duration.ofMinutes(1))
        hold.complete(Unit)
        assertInstanceOf(ScheduledFetchOutcome.Ran::class.java, scheduledOne.await())
        assertFalse(import.await() is FileImportOutcome.Unavailable)
        assertTrue(importStarted)

        val importHold = CompletableDeferred<Unit>()
        val onTheLane = async { slice.coordinator.run(OperationKind.FileImport, StartedBy.Operator) { importHold.await() } }
        runCurrent()
        slice.clock.advance(Duration.ofHours(2))
        val behind = async { slice.fetch.scheduledFetch() }
        runCurrent()
        assertEquals(1, slice.feeds.fetches)
        importHold.complete(Unit)
        onTheLane.await()
        assertInstanceOf(ScheduledFetchOutcome.Ran::class.java, behind.await())
        assertEquals(2, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-013"])
    @DisplayName("ADR-007 §2: a Scheduled Fetch that waited for the lane while an Upload Plan was opened is deferred when it gets the lane, and contacts nothing")
    fun scheduledFetchWaitingWhileAPlanOpensIsDeferred() = runTest {
        feedsWithChecking()
        val hold = CompletableDeferred<Unit>()
        val opening = async {
            slice.coordinator.run(OperationKind.ReviewDecision, StartedBy.Operator) {
                hold.await()
                slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
            }
        }
        runCurrent()
        val behind = async { slice.fetch.scheduledFetch() }
        runCurrent()

        hold.complete(Unit)
        opening.await()

        assertEquals(ScheduledFetchOutcome.Deferred(OperationRefusal.UploadPlanAwaitingConfirmation), behind.await())
        assertTrue(slice.coordinatorStore.scheduledFetchDeferred)
        assertEquals(0, slice.feeds.fetches)
    }

    // ---- A11: the Fetch of one Budget -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-010"])
    @DisplayName("A11: a Fetch of one Budget fetches only the Data Providers that read a Bank Account mapped into it, and compares")
    fun fetchOfOneBudgetFetchesItsDataProviders() = runTest {
        feedsWithChecking()
        slice.credentials.enter(slice.wrenKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        addBankAccount(slice.wrenKey.dataProviderId!!, "wren-1", "Wren Everyday", "Current", ExampleData.EUR)
        answerWren()

        val household = slice.fetch.fetchBudget(budgetId("budget-household")) as FetchStart.Ran

        assertEquals(listOf("Kestrel Feeds"), household.reports.map { it.dataProviderName })
        assertEquals(0, slice.wren.fetches)
        assertInstanceOf(ComparisonReport.Compared::class.java, household.comparison)

        val summer = slice.fetch.fetchBudget(budgetId("budget-summer")) as FetchStart.Ran
        assertEquals(listOf("Wren Feeds"), summer.reports.map { it.dataProviderName })
        assertEquals(1, slice.feeds.fetches)
        assertEquals(StartedBy.Operator, slice.importStatusStore.fetchesOf(slice.wrenKey.dataProviderId!!).single().startedBy)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-011"])
    @DisplayName("A11, BR-011: a Data Provider that reads only the balance of a Bank Account in the Budget is fetched, reading every Bank Account step 3 selects for it, also one mapped into another Budget")
    fun fetchOfOneBudgetReadsEverySelectedBankAccount() = runTest {
        feedsWithChecking()
        addFileBankAccount("hsa-0042", "Health Savings")
        answerFeeds(SourceOutcome.Completed, delivered("acct-4410", "CHK"), delivered("hsa-0042", "HSA"))

        val care = slice.fetch.fetchBudget(budgetId("budget-care")) as FetchStart.Ran

        assertEquals(listOf("Kestrel Feeds"), care.reports.map { it.dataProviderName })
        assertEquals(
            BankAccountSelection.Selected(
                listOf(BankAccountToRead("acct-4410", ReadKind.TransactionsAndBalance), BankAccountToRead("hsa-0042", ReadKind.Balance)),
            ),
            slice.feeds.fetchRequests.single().bankAccounts,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers")
    @DisplayName("A11, precondition: a Fetch of one Budget does not start while an Upload Plan awaits confirmation; a Budget without a mapped Bank Account fetches nothing")
    fun fetchOfOneBudgetFollowsThePrecondition() = runTest {
        feedsWithChecking()

        assertEquals(FetchStart.Ran(emptyList()), slice.fetch.fetchBudget(budgetId("budget-summer")))
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(FetchStart.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), slice.fetch.fetchBudget(budgetId("budget-household")))
        assertEquals(0, slice.feeds.fetches)
    }
}
