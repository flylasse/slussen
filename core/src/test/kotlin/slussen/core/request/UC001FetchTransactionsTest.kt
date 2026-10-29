package slussen.core.request

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.connection.BudgetsRead
import slussen.core.credential.CredentialStatus
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.fetch.RequestLimitWarningShown
import slussen.core.operation.OperationRefusal
import slussen.core.port.AddOutcome
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StoredAccountBalance
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredFetchResult
import slussen.core.port.StoredLeftOutBankAccount
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.StableReferenceKind
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.fetch.BalanceInOtherCurrency
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.status.Blocked
import slussen.core.status.BlockedReason
import slussen.core.status.Remedy
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.UnreadableReason
import slussen.core.settings.ServiceStatus
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.BankAccountNumber
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationResult
import slussen.core.operation.RunningOperation
import slussen.core.plugin.PluginRegistration
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.FakeDataProvider
import slussen.testing.TestSlice
import java.time.Duration

/**
 * Fetch Transactions (UC-001) as far as Phase 8a builds it (O-P2-45): the request gate (the Request Limit of BR-007,
 * declared by each Data Provider's part as published or as a default the Operator can change, S-31; Back-offs, A1, A5,
 * A15, A16; the Fetch Cooldown, A2, BR-006), the Fetch with the Operator present, the Offered Bank Accounts with their
 * balance, the identification of the Bank Accounts the Operator added (step 6, A18, BR-011), the Fetch record with its
 * outcome (step 11, A1-A3, A6, A7) and the Fetch of several Data Providers (A11, BR-010); since Phase 8f also the Account
 * Balance (step 8, BR-011) and the Fetch Result (step 11, A2, A5, A8) of each Bank Account a Fetch reads, stored by O-1.
 * Since the transactions plan's Phase 6a the Bank Transactions and Unreadable Transactions (steps 7-9) and since Phase 7a
 * the Comparison that follows (step 10, tested with UC-003); the Scheduled Fetch comes with a later slice.
 */
@DisplayName("UC-001: Fetch Transactions")
class UC001FetchTransactionsTest {
    private val slice = TestSlice()
    private val request = RequestSummary(RequestKind.Transactions, bankReference = null, bankAccountReference = null)

    /** Asks the gate of [service] [times] times within one operation and returns each answer. */
    private suspend fun admit(service: ServiceKey, times: Int = 1): List<Admission> {
        val result = slice.coordinator.run(OperationKind.ServiceCredentialChange, StartedBy.Operator) { permit ->
            val gate = slice.credentials.gate(service, permit)
            List(times) { gate.admit(request) }
        }
        return (result as OperationResult.Ran).value
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: every request admitted is kept as a Provider Request with its time; the Data Provider gets at most its published Request Limit in the preceding 24 hours")
    fun publishedRequestLimitHolds() = runTest {
        val answers = admit(slice.feedsKey, times = 21)

        assertEquals(List(20) { Admission.Allowed }, answers.take(20))
        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), answers.last())
        assertEquals(20, slice.providerRequests.requests.count { it.dataProviderId == slice.feedsId })
        assertTrue(slice.providerRequests.requests.all { it.sentAt == slice.clock.instant() && !it.unattended })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007"])
    @DisplayName("A3: at the Request Limit the gate refuses for the whole Data Provider and stores no Provider Request, so nothing is sent")
    fun requestLimitReachedSendsNothing() = runTest {
        admit(slice.feedsKey, times = 20)

        val refused = admit(slice.feedsKey).single()

        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), refused)
        assertEquals(20, slice.providerRequests.requests.size)
        assertEquals(Admission.Allowed, admit(slice.wrenKey).single())
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"], requirements = ["NFR-018"])
    @DisplayName("BR-007: the count is over the preceding 24 hours, not a calendar day: a request leaves the count exactly 24 hours after it was sent")
    fun countIsOverTheRolling24Hours() = runTest {
        val first = slice.clock.instant()
        admit(slice.feedsKey, times = 20)
        slice.clock.set(first.plus(Duration.ofHours(24)).minusSeconds(1))
        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), admit(slice.feedsKey).single())

        slice.clock.set(first.plus(Duration.ofHours(24)))

        assertEquals(Admission.Allowed, admit(slice.feedsKey).single())
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider that publishes no Request Limit gets the default its part declares, and the value the Operator set holds for the gate")
    fun notPublishedRequestLimitIsTheOperatorsSetting() = runTest {
        val registered = slice.plugins.dataProvider(slice.openBankingKey).stored
        assertEquals(50, registered.requestLimit)
        assertFalse(registered.requestLimitPublished)
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 12)

        val answers = admit(slice.openBankingKey, times = 13)

        assertEquals(List(12) { Admission.Allowed }, answers.take(12))
        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), answers.last())
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: the count survives a restart of the app: a new start counts the Provider Requests already kept")
    fun countSurvivesARestart() = runTest {
        admit(slice.feedsKey, times = 20)

        val restarted = PluginRegistration(slice.registry)
            .register(slice.budgetApp, listOf(slice.feeds, slice.openBanking, slice.wren), listOf(slice.fileFormat))
        val result = slice.coordinator.run(OperationKind.ServiceCredentialChange, StartedBy.Operator) { permit ->
            DataProviderGate(restarted.dataProvider(slice.feedsKey).stored, slice.providerRequests, slice.importStatusStore, slice.clock, permit).admit(request)
        }

        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached), (result as OperationResult.Ran).value)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a published Request Limit is set from the part at every start and is not an Operator Setting")
    fun publishedRequestLimitIsSetFromThePartAtEveryStart() = runTest {
        slice.registry.storeRequestLimit(slice.feedsId, 300)
        val newer = FakeDataProvider(
            ExampleData.feedsProfile.copy(requestLimit = RequestLimitDeclaration.Published(24, ExampleData.FEEDS_LIMIT_SOURCE)),
        )

        val restarted = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(newer), emptyList())

        val stored = restarted.dataProviders.single().stored
        assertEquals(24, stored.requestLimit)
        assertTrue(stored.requestLimitPublished)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Request Limit the Data Provider does not publish keeps the Operator's value at the next start while it lies in the declared range, and otherwise takes the default")
    fun operatorsRequestLimitIsKeptWithinTheDeclaredRange() = runTest {
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 80)
        val sameRange = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(slice.openBanking), emptyList())
        assertEquals(80, sameRange.dataProviders.single().stored.requestLimit)

        val narrower = FakeDataProvider(
            ExampleData.openBankingProfile.copy(requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 40, range = 20..60)),
        )
        val outOfRange = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(narrower), emptyList())

        assertEquals(40, outOfRange.dataProviders.single().stored.requestLimit)
        assertFalse(outOfRange.dataProviders.single().stored.requestLimitPublished)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider whose part stops publishing its limit takes the default its part now declares, as not published")
    fun publishedLimitBecomingNotPublishedTakesTheDefault() = runTest {
        val unpublished = FakeDataProvider(
            ExampleData.feedsProfile.copy(requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 15, range = 10..30)),
        )

        val restarted = PluginRegistration(slice.registry).register(slice.budgetApp, listOf(unpublished), emptyList())

        val stored = restarted.dataProviders.single().stored
        assertEquals(15, stored.requestLimit)
        assertFalse(stored.requestLimitPublished)
    }

    // ---- The Fetch (Phase 8a) ----------------------------------------------------------------------------------

    private val today: LocalDate = LocalDate.of(2026, 10, 15)

    private fun delivered(
        reference: String,
        name: String?,
        balance: Amount? = amount(100),
        bank: String? = "Kestrel Bank",
        kind: String? = "checking",
        currency: CurrencyCode? = USD,
        number: String? = null,
        result: BankAccountResult? = null,
    ) = DeliveredBankAccount(
        providerReference = reference,
        bankReference = bank,
        name = name,
        kind = kind,
        currency = currency,
        number = number?.let(::BankAccountNumber),
        result = result ?: BankAccountResult.Read(emptyList(), balance),
    )

    /** Three Bank Accounts Kestrel Feeds lists, with their balances. */
    private val listed = listOf(
        delivered("acct-4410", "CHK", amount(4140, 50)),
        delivered("acct-7731", "Reserve Saver", amount(12000), kind = "savings"),
        delivered("acct-1182", "Pinecrest Rewards Visa", amount(-1268, 400), bank = "Pinecrest Card Services", kind = "credit card"),
    )

    private fun answerFeeds(outcome: SourceOutcome = SourceOutcome.Completed, accounts: List<DeliveredBankAccount> = listed) {
        slice.feeds.fetchAnswer = { FetchResponse(outcome, emptyList(), accounts) }
    }

    /** The credentials of the Budget App and Kestrel Feeds are accepted, and every Budget is served. */
    private suspend fun ready() {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
    }

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    /** A Bank Account the Operator added for Kestrel Feeds (UC-013), identified by [reference], at [identifiedAt]. */
    private suspend fun addFeedsBankAccount(
        reference: String,
        name: String,
        budgetAccount: String,
        identifiedAt: Instant = slice.clock.instant(),
    ): Long = (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-${reference.takeLast(4).padStart(12, '0')}",
                bank = BankChoice.Named("Kestrel Bank"),
                providerBankId = null,
                dataProviderId = slice.feedsId,
                fileFormatId = null,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = name,
                currency = USD,
                type = BankAccountType.Checking,
                providerReference = reference,
                bankAccountFingerprint = null,
                identifiedAt = identifiedAt,
                startDate = null,
                budgetAccountId = budgetAccountId(budgetAccount),
            ),
        ) as AddOutcome.Added
        ).bankAccountId

    /** A Bank Account whose transactions come from Transaction Files and whose balance Kestrel Feeds reports (BR-011). */
    private suspend fun addFileBankAccount(balanceReference: String, name: String, budgetAccount: String): Long = (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-00000000f11e",
                bank = BankChoice.Named("Harbor Health Trust"),
                providerBankId = null,
                dataProviderId = null,
                fileFormatId = slice.plugins.fileFormats.single().id,
                balanceDataProviderId = slice.feedsId,
                balanceProviderReference = balanceReference,
                name = name,
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

    private suspend fun fetchFeeds(confirmed: Boolean = false): FetchReport =
        (slice.fetch.fetch(slice.feedsKey, confirmed) as FetchStart.Ran).reports.single()

    /** The recorded Fetches of Kestrel Feeds, most recent first. */
    private suspend fun fetchesOfFeeds() = slice.importStatusStore.fetchesOf(slice.feedsId)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-009", "BR-012"])
    @DisplayName("Steps 1-4: the Operator starts a Fetch; with no Bank Account added, Slussen asks, with the Operator present, for the Bank Accounts the Data Provider offers with their balances, for the fetch period")
    fun fetchWithNoBankAccountAddedAsksForTheOfferedBankAccounts() = runTest {
        ready()
        answerFeeds()

        fetchFeeds()

        val request = slice.feeds.fetchRequests.single()
        assertEquals(BankAccountSelection.AllForIdentification(ReadKind.Balance), request.bankAccounts)
        // UC-001 BR-009 (S-T-06): the last 30 days by default.
        assertEquals(today.minusDays(30), request.periodFrom)
        assertEquals(today, request.periodTo)
        assertEquals(OperatorPresence.OperatorPresent, request.presence)
        assertTrue(request.sessions.isEmpty())
        assertEquals(ExampleData.EXCHANGED_ACCESS_CREDENTIAL, slice.feeds.fetchContexts.single().credential?.let { reveal(it) })
    }

    @OptIn(SensitiveApi::class)
    private fun reveal(secret: Secret): String = secret.reveal()

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009 (S-T-06): the fetch period is the Operator Setting, the last 30 days by default for a new installation")
    fun fetchPeriodIsTheOperatorSetting() = runTest {
        assertEquals(30, slussen.core.port.OperatorSettings.DEFAULT.fetchPeriodDays)
        ready()
        answerFeeds()
        slice.settings.settings = slice.settings.settings.copy(fetchPeriodDays = 14)

        fetchFeeds()

        assertEquals(today.minusDays(14), slice.feeds.fetchRequests.single().periodFrom)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006", "BR-009"])
    @DisplayName("BR-009 (S-T-11): a fetch period changed on Settings applies from the next Fetch that contacts the Data Provider; a Fetch served from the Fetch Cooldown asks for nothing")
    fun changedFetchPeriodAppliesFromTheNextFetchThatContacts() = runTest {
        ready()
        answerFeeds()
        fetchFeeds()
        assertEquals(today.minusDays(30), slice.feeds.fetchRequests.single().periodFrom)

        slice.generalSettings.changeFetchPeriod(14)
        slice.clock.advance(Duration.ofMinutes(20))
        assertInstanceOf(FetchReport.ServedFromFetchCooldown::class.java, fetchFeeds())
        assertEquals(1, slice.feeds.fetchRequests.size)

        slice.clock.advance(Duration.ofMinutes(40))
        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
        assertEquals(today.minusDays(14), slice.feeds.fetchRequests.last().periodFrom)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-007", "BR-012"], requirements = ["NFR-016"])
    @DisplayName("Step 4: every request is admitted by the gate and kept as a Provider Request with its time, with the Operator present, under the request timeout")
    fun everyRequestIsKeptAsAProviderRequest() = runTest {
        ready()
        answerFeeds()
        slice.settings.settings = slice.settings.settings.copy(requestTimeoutSeconds = 45)
        val before = slice.providerRequests.requests.size

        fetchFeeds()

        val kept = slice.providerRequests.requests.drop(before).single()
        assertEquals(slice.feedsId, kept.dataProviderId)
        assertEquals(ProviderRequestKind.Balance, kept.kind)
        assertFalse(kept.unattended)
        assertEquals(slice.clock.instant(), kept.sentAt)
        assertEquals(Duration.ofSeconds(45), slice.feeds.fetchContexts.single().timeout.duration)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Steps 5-6: the delivered Bank Accounts are kept as Offered Bank Accounts with name, kind, currency, balance and the Bank the Data Provider names, and UC-013 offers them")
    fun deliveredBankAccountsAreKeptAsOffered() = runTest {
        ready()
        answerFeeds()

        val report = fetchFeeds()

        val offered = slice.offeredStore.offered().filter { it.dataProviderId == slice.feedsId }
        assertEquals(listOf("acct-4410", "acct-7731", "acct-1182"), offered.map { it.providerReference })
        assertEquals(listOf("CHK", "Reserve Saver", "Pinecrest Rewards Visa"), offered.map { it.name })
        assertEquals(listOf("checking", "savings", "credit card"), offered.map { it.bankAccountKind })
        assertEquals(listOf(amount(4140, 50), amount(12000), amount(-1268, 400)), offered.map { it.balance })
        assertTrue(offered.all { it.currency == USD && it.offeredAt == slice.clock.instant() && it.proposedBankAccountId == null })
        val banks = slice.connectionStore.banks().associateBy { it.id }
        assertEquals(listOf("Kestrel Bank", "Kestrel Bank", "Pinecrest Card Services"), offered.map { banks.getValue(it.bankId!!).name })
        assertEquals(
            FetchReport.Fetched(slice.feedsKey, "Kestrel Feeds", slice.clock.instant(), offered = 3, identified = emptyList(), notAdded = 3, waitingUntil = null, warning = null),
            report,
        )
        val pairing = slice.connect.pairing().sources.single { it.dataProviderId == slice.feedsId }
        assertEquals(listOf("CHK", "Reserve Saver", "Pinecrest Rewards Visa"), pairing.offered.map { it.nameOrProduct })
        assertNull(pairing.remedy)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 6: a later delivery replaces the Offered Bank Accounts of the Data Provider; a Data Provider that names no Bank leaves the Bank empty")
    fun laterDeliveryReplacesTheOffered() = runTest {
        ready()
        answerFeeds()
        fetchFeeds()
        slice.plugins.dataProvider(slice.feedsKey).stored = slice.plugins.dataProvider(slice.feedsKey).stored.copy(fetchCooldownMinutes = 0)
        answerFeeds(accounts = listOf(delivered("acct-9001", "Harbor Joint", bank = null)))

        fetchFeeds()

        val offered = slice.offeredStore.offered().single { it.dataProviderId == slice.feedsId }
        assertEquals("acct-9001", offered.providerReference)
        assertNull(offered.bankId)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 6: of a Bank Account number only the last four digits and the one-way fingerprint of ADR-008 §8 are kept, scoped by Data Provider and Bank")
    fun numberIsKeptOnlyAsLastFourDigitsAndFingerprint() = runTest {
        ready()
        answerFeeds(
            accounts = listOf(
                delivered("acct-a", "Joint", number = "12-34 56/78.ab9"),
                delivered("acct-b", "Joint elsewhere", bank = "Pinecrest Card Services", number = "12345678AB9"),
            ),
        )

        fetchFeeds()

        val offered = slice.offeredStore.offered().filter { it.dataProviderId == slice.feedsId }
        assertEquals(listOf("6789", "6789"), offered.map { it.lastFourDigits })
        assertEquals(
            sha256Hex("19:slussen-fingerprint1:113:example-feeds12:Kestrel Bank11:12345678AB9"),
            offered[0].bankAccountFingerprint,
        )
        assertNotEquals(offered[0].bankAccountFingerprint, offered[1].bankAccountFingerprint)
        assertTrue(offered.none { "12345678" in it.toString() })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: the Fetch is recorded with its time, who started it and its outcome; the Data Provider's page shows the last Fetch")
    fun fetchIsRecordedWithItsOutcome() = runTest {
        ready()
        answerFeeds()

        fetchFeeds()

        val fetch = fetchesOfFeeds().single()
        assertEquals(slice.clock.instant(), fetch.startedAt)
        assertEquals(StartedBy.Operator, fetch.startedBy)
        assertEquals(FetchOutcome.Fetched, fetch.outcome)
        assertEquals(ServiceStatus.Connected(slice.clock.instant(), budgetsInUse = null), slice.settingsOverview.servicePage(slice.feedsKey).status)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 3: Slussen reads every Bank Account the Operator added with an Account Mapping that is not a Retired Account, and for its balance only one whose transactions come from Transaction Files")
    fun selectsTheAddedMappedBankAccounts() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val retired = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        val unmapped = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        slice.connectionStore.bankAccountList.replaceAll {
            when (it.id) {
                retired -> it.copy(retired = true)
                unmapped -> it.copy(mapping = null)
                else -> it
            }
        }
        addFileBankAccount("acct-6324", "Harbor Health Savings", "Health Savings")
        answerFeeds(accounts = listed + delivered("acct-6324", "HSA", amount(6310, 200)))

        fetchFeeds()

        assertEquals(
            BankAccountSelection.Selected(
                listOf(
                    BankAccountToRead("acct-4410", ReadKind.TransactionsAndBalance),
                    BankAccountToRead("acct-6324", ReadKind.Balance),
                ),
            ),
            slice.feeds.fetchRequests.single().bankAccounts,
        )
        assertEquals(ProviderRequestKind.Transactions, slice.providerRequests.requests.last().kind)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-009", "BR-011"])
    @DisplayName("Step 4: every selected Bank Account is asked for its transactions and its balance for the fetch period, one whose transactions come from Transaction Files for its balance only (BR-011); the request is kept as a Provider Request of transactions")
    fun selectedBankAccountsAreAskedForTransactionsAndBalance() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        addFileBankAccount("acct-6324", "Harbor Health Savings", "Health Savings")
        answerFeeds(accounts = listed + delivered("acct-6324", "HSA", amount(6310, 200)))
        val before = slice.providerRequests.requests.size

        val report = fetchFeeds() as FetchReport.Fetched

        val selection = slice.feeds.fetchRequests.single().bankAccounts as BankAccountSelection.Selected
        assertEquals(
            listOf(
                BankAccountToRead("acct-4410", ReadKind.TransactionsAndBalance),
                BankAccountToRead("acct-1182", ReadKind.TransactionsAndBalance),
                BankAccountToRead("acct-6324", ReadKind.Balance),
            ),
            selection.bankAccounts,
        )
        assertEquals(today.minusDays(30), slice.feeds.fetchRequests.single().periodFrom)
        assertEquals(listOf(ProviderRequestKind.Transactions), slice.providerRequests.requests.drop(before).map { it.kind })
        assertEquals(listOf("Kestrel Checking", "Pinecrest Rewards Card", "Harbor Health Savings"), report.identified)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 6 (S-35): an Offered Bank Account the request did not ask to read keeps the balance the Data Provider gave with the list, NotRead(NotSelected, balance), or has none when it gave none")
    fun listedBankAccountsKeepTheBalanceGivenWithTheList() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", amount(4140, 50)),
                delivered("acct-7731", "Reserve Saver", kind = "savings", result = BankAccountResult.NotRead(NotReadCause.NotSelected, amount(12000))),
                delivered("acct-1182", "Pinecrest Rewards Visa", kind = "credit card", result = BankAccountResult.NotRead(NotReadCause.NotSelected)),
            ),
        )

        fetchFeeds()

        val offered = slice.offeredStore.offered().filter { it.dataProviderId == slice.feedsId }
        assertEquals(listOf("acct-4410", "acct-7731", "acct-1182"), offered.map { it.providerReference })
        assertEquals(listOf(amount(4140, 50), amount(12000), null), offered.map { it.balance })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 6, BR-011: each selected Bank Account is identified with certainty as one the Operator added, a file-imported one by the identifier its balance Data Provider gives it")
    fun selectedBankAccountsAreIdentified() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        addFileBankAccount("acct-6324", "Harbor Health Savings", "Health Savings")
        answerFeeds(accounts = listed + delivered("acct-6324", "HSA", amount(6310, 200)))

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(listOf("Kestrel Checking", "Harbor Health Savings"), report.identified)
        assertEquals(4, report.offered)
        assertEquals(2, report.notAdded)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A18: Bank Account Not Added")
    @DisplayName("A18: a delivered Bank Account that matches none of the added ones is kept only as an Offered Bank Account that the Operator can add; none of its transactions is used")
    fun bankAccountNotAddedIsOnlyOffered() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(listOf("Kestrel Checking"), report.identified)
        assertEquals(2, report.notAdded)
        val toAdd = slice.connect.pairing().sources.single { it.dataProviderId == slice.feedsId }.offered
        assertEquals(listOf("Reserve Saver", "Pinecrest Rewards Visa"), toAdd.map { it.nameOrProduct })
        assertEquals(1, slice.connectionStore.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A1: while a Back-off with cause Request To Wait has not ended, the Data Provider is not contacted; waiting for the provider is shown with its end and the Fetch recorded as Waiting For Back-off")
    fun backOffHoldsTheFetch() = runTest {
        ready()
        answerFeeds()
        val endsAt = slice.clock.instant().plus(Duration.ofHours(5))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), endsAt)
        val requestsBefore = slice.providerRequests.requests.size

        val report = fetchFeeds()

        assertEquals(FetchReport.WaitingForProvider(slice.feedsKey, "Kestrel Feeds", endsAt), report)
        assertEquals(0, slice.feeds.fetches)
        assertEquals(requestsBefore, slice.providerRequests.requests.size)
        assertEquals(FetchOutcome.WaitingForBackOff, fetchesOfFeeds().single().outcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("BR-004: no action of the Operator ends a Back-off; once it has ended, a Fetch contacts the Data Provider again")
    fun backOffEndsOnlyByItsTime() = runTest {
        ready()
        answerFeeds()
        val endsAt = slice.clock.instant().plus(Duration.ofHours(5))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), endsAt)
        slice.clock.set(endsAt.minusSeconds(1))
        assertInstanceOf(FetchReport.WaitingForProvider::class.java, fetchFeeds(confirmed = true))

        slice.clock.set(endsAt)

        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("BR-004: during a Back-off the gate refuses every request to the Data Provider for the whole Data Provider and keeps no Provider Request")
    fun gateRefusesDuringABackOff() = runTest {
        slice.importStatusStore.backOffList += StoredBackOff(
            slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), slice.clock.instant().plus(Duration.ofHours(1)),
        )

        val refused = admit(slice.feedsKey).single()

        assertEquals(Admission.Refused(RefusalScope.Source, RefusalReason.BackOff), refused)
        assertTrue(slice.providerRequests.requests.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("A2: within the Fetch Cooldown after a successful Fetch, the Data Provider is not contacted; the Fetch is recorded as Served From Fetch Cooldown with when a new Fetch contacts it again")
    fun withinTheFetchCooldownNothingIsSent() = runTest {
        ready()
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(20))

        val report = fetchFeeds()

        assertEquals(
            FetchReport.ServedFromFetchCooldown(slice.feedsKey, "Kestrel Feeds", first, first.plus(Duration.ofMinutes(60)), read = null, requestLimitFreesUpAt = null),
            report,
        )
        assertEquals(1, slice.feeds.fetches)
        assertEquals(listOf(FetchOutcome.ServedFromFetchCooldown, FetchOutcome.Fetched), fetchesOfFeeds().map { it.outcome })
        assertEquals(3, slice.offeredStore.offered().count { it.dataProviderId == slice.feedsId })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006", "BR-007"])
    @DisplayName("A2, BR-006: a Bank Account added since the last successful Fetch is read within the Fetch Cooldown, alone; the read counts against the Request Limit and neither starts nor extends the Fetch Cooldown; once read, the Fetch Cooldown serves it (S-T-02)")
    fun bankAccountAddedSinceIsReadWithinTheCooldown() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(10))
        addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        slice.clock.advance(Duration.ofMinutes(10))
        val requestsBefore = slice.providerRequests.requests.size

        val report = fetchFeeds() as FetchReport.ServedFromFetchCooldown

        assertEquals(
            BankAccountSelection.Selected(listOf(BankAccountToRead("acct-7731", ReadKind.TransactionsAndBalance))),
            slice.feeds.fetchRequests.last().bankAccounts,
        )
        assertEquals(listOf("Kestrel Reserve"), report.read?.identified)
        assertEquals(first.plus(Duration.ofMinutes(60)), report.contactsAgainAt)
        assertEquals(requestsBefore + 1, slice.providerRequests.requests.size)
        assertEquals(FetchOutcome.ServedFromFetchCooldown, fetchesOfFeeds().first().outcome)

        // ADR-002 §2: the read is recorded as Served From Fetch Cooldown, so the Fetch Cooldown still counts from the
        // last Fetch with outcome Fetched. BR-006 (S-T-02): Kestrel Reserve now has a Fetch Result Fetched since it was
        // added, so a later Fetch within the Fetch Cooldown serves it like the others and contacts nobody.
        slice.clock.advance(Duration.ofMinutes(10))
        val third = fetchFeeds() as FetchReport.ServedFromFetchCooldown
        assertNull(third.read)
        assertEquals(first.plus(Duration.ofMinutes(60)), third.contactsAgainAt)
        assertEquals(2, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006", "BR-007"])
    @DisplayName("A2: when the Request Limit is reached, none of the Bank Accounts added since is read and Request Limit reached is shown")
    fun addedSinceAtTheRequestLimitReadsNone() = runTest {
        ready()
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings", identifiedAt = first.plusSeconds(60))
        slice.clock.advance(Duration.ofMinutes(2))
        val used = slice.providerRequests.requests.size
        repeat(20 - used) { admit(slice.feedsKey) }

        val report = fetchFeeds() as FetchReport.ServedFromFetchCooldown

        assertNull(report.read)
        assertEquals(first.plus(Duration.ofHours(24)), report.requestLimitFreesUpAt)
        assertEquals(1, slice.feeds.fetches)
        assertEquals(FetchOutcome.ServedFromFetchCooldown, fetchesOfFeeds().first().outcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("BR-006: once the Fetch Cooldown has ended, a Fetch contacts the Data Provider again")
    fun afterTheCooldownTheDataProviderIsContacted() = runTest {
        ready()
        answerFeeds()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(60))

        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
        assertEquals(2, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("BR-006: a Fetch Cooldown of 0 is switched off, and the Operator's value holds rather than the declared default")
    fun cooldownOfTheOperatorHolds() = runTest {
        ready()
        answerFeeds()
        val provider = slice.plugins.dataProvider(slice.feedsKey)
        provider.stored = provider.stored.copy(fetchCooldownMinutes = 0)
        fetchFeeds()

        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
        provider.stored = provider.stored.copy(fetchCooldownMinutes = 5)
        slice.clock.advance(Duration.ofMinutes(4))
        assertInstanceOf(FetchReport.ServedFromFetchCooldown::class.java, fetchFeeds())
        slice.clock.advance(Duration.ofMinutes(1))
        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("BR-006: a Fetch that delivered no Bank Account does not start a Fetch Cooldown")
    fun fetchWithoutBankAccountsStartsNoCooldown() = runTest {
        ready()
        answerFeeds(accounts = emptyList())
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(5))

        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
        assertEquals(2, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007"], requirements = ["NFR-018"])
    @DisplayName("A3: at the Request Limit the Data Provider is not contacted; Request Limit reached is shown with the time the oldest counted Provider Request leaves the 24-hour window, and the Fetch recorded")
    fun requestLimitReachedHoldsTheFetch() = runTest {
        ready()
        answerFeeds()
        val oldest = slice.providerRequests.requests.single { it.dataProviderId == slice.feedsId }.sentAt
        slice.clock.advance(Duration.ofMinutes(30))
        repeat(19) { admit(slice.feedsKey) }

        val report = fetchFeeds()

        assertEquals(FetchReport.RequestLimitReached(slice.feedsKey, "Kestrel Feeds", oldest.plus(Duration.ofHours(24))), report)
        assertEquals(0, slice.feeds.fetches)
        assertEquals(20, slice.providerRequests.requests.count { it.dataProviderId == slice.feedsId })
        assertEquals(FetchOutcome.RequestLimitReached, fetchesOfFeeds().single().outcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5: when the Data Provider asks Slussen to wait for a period, a Back-off with cause Request To Wait starts for the whole Data Provider until then; the delivered Bank Accounts are kept, and the next Fetch waits")
    fun askedToWaitStartsABackOff() = runTest {
        ready()
        val until = slice.clock.instant().plus(Duration.ofHours(2))
        answerFeeds(outcome = SourceOutcome.AskedToWait(until))

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(until, report.waitingUntil)
        assertEquals(
            StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), until),
            slice.importStatusStore.backOffList.single(),
        )
        assertEquals(3, slice.offeredStore.offered().count { it.dataProviderId == slice.feedsId })
        slice.clock.advance(Duration.ofHours(1))
        assertEquals(FetchReport.WaitingForProvider(slice.feedsKey, "Kestrel Feeds", until), fetchFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("BR-004: when the Data Provider names no period, the Back-off lasts the default wait, an Operator Setting; with nothing delivered the Fetch ends waiting for the provider")
    fun askedToWaitWithoutPeriodTakesTheDefaultWait() = runTest {
        ready()
        slice.settings.settings = slice.settings.settings.copy(defaultWaitHours = 5)
        answerFeeds(outcome = SourceOutcome.AskedToWait(null), accounts = emptyList())

        val report = fetchFeeds()

        val endsAt = slice.clock.instant().plus(Duration.ofHours(5))
        assertEquals(FetchReport.WaitingForProvider(slice.feedsKey, "Kestrel Feeds", endsAt), report)
        assertEquals(endsAt, slice.importStatusStore.backOffList.single().endsAt)
        assertTrue(slice.offeredStore.offered().none { it.dataProviderId == slice.feedsId })
        assertEquals(FetchOutcome.WaitingForBackOff, fetchesOfFeeds().single().outcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", businessRules = ["BR-005"], requirements = ["NFR-004", "NFR-016"])
    @DisplayName("A6: a timeout keeps the stored data unchanged, records the Fetch as Failed with its time and starts no Back-off; the Operator can fetch again at once")
    fun transientFailureIsRecordedWithoutBackOff() = runTest {
        ready()
        answerFeeds()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(outcome = SourceOutcome.TransientFailure(FailureKind.Timeout), accounts = emptyList())
        val offeredBefore = slice.offeredStore.offered()

        val report = fetchFeeds()

        assertEquals(FetchReport.Failed(slice.feedsKey, "Kestrel Feeds", FailureKind.Timeout, slice.clock.instant()), report)
        assertEquals(offeredBefore, slice.offeredStore.offered())
        assertTrue(slice.importStatusStore.backOffList.isEmpty())
        assertEquals(FetchOutcome.Failed, fetchesOfFeeds().first().outcome)
        answerFeeds()
        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials")
    @DisplayName("A7: refused credentials start no Back-off, keep the stored data, record Credentials Refused and show credentials refused with entering them again as the remedy")
    fun refusedCredentialsAreRecorded() = runTest {
        ready()
        answerFeeds(outcome = SourceOutcome.CredentialsRefused, accounts = emptyList())

        val report = fetchFeeds()

        assertEquals(FetchReport.CredentialsRefused(slice.feedsKey, "Kestrel Feeds"), report)
        assertTrue(slice.importStatusStore.backOffList.isEmpty())
        assertTrue(slice.offeredStore.offered().isEmpty())
        assertEquals(FetchOutcome.CredentialsRefused, fetchesOfFeeds().single().outcome)
        assertEquals(CredentialStatus.Refused, slice.credentials.status(slice.feedsKey))
        assertEquals(ServiceStatus.CredentialsRefused, slice.settingsOverview.servicePage(slice.feedsKey).status)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials")
    @DisplayName("A7, UC-014 BR-005: after a refusal the Data Provider is not contacted until new credentials are accepted")
    fun refusedDataProviderIsNotContactedUntilNewCredentials() = runTest {
        ready()
        answerFeeds(outcome = SourceOutcome.CredentialsRefused, accounts = emptyList())
        fetchFeeds()

        assertEquals(FetchReport.CredentialsRefused(slice.feedsKey, "Kestrel Feeds"), fetchFeeds())
        assertEquals(1, slice.feeds.fetches)

        slice.clock.advance(Duration.ofMinutes(1))
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        answerFeeds()
        assertInstanceOf(FetchReport.Fetched::class.java, fetchFeeds())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Precondition, UC-014 BR-005: without accepted credentials the Data Provider is not contacted and credentials missing is shown; nothing is recorded")
    fun credentialsMissingContactsNothing() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))

        val report = fetchFeeds()

        assertEquals(FetchReport.CredentialsMissing(slice.feedsKey, "Kestrel Feeds"), report)
        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.importStatusStore.fetches.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation or an Upload is written, no Fetch starts")
    fun noFetchWhileAPlanIsOpen() = runTest {
        ready()
        answerFeeds()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(FetchStart.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), slice.fetch.fetch(slice.feedsKey))
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(FetchStart.Unavailable(OperationRefusal.UploadBeingWritten), slice.fetch.fetchAll())

        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.importStatusStore.fetches.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A15: Data Provider Warns About Its Request Limit", businessRules = ["BR-015"])
    @DisplayName("A15: a Request Limit warning starts a Back-off with cause Request Limit Warning for the Request Limit warning pause, shown with its end; the delivered Bank Accounts are kept")
    fun requestLimitWarningStartsItsBackOff() = runTest {
        ready()
        answerFeeds(outcome = SourceOutcome.RequestLimitWarning)

        val report = fetchFeeds() as FetchReport.Fetched

        val endsAt = slice.clock.instant().plus(Duration.ofHours(24))
        assertEquals(RequestLimitWarningShown(slice.clock.instant(), endsAt), report.warning)
        assertEquals(
            StoredBackOff(slice.feedsId, null, BackOffCause.RequestLimitWarning, slice.clock.instant(), endsAt),
            slice.importStatusStore.backOffList.single(),
        )
        assertEquals(3, slice.offeredStore.offered().count { it.dataProviderId == slice.feedsId })
        assertEquals(FetchOutcome.Fetched, fetchesOfFeeds().single().outcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A15: Data Provider Warns About Its Request Limit", businessRules = ["BR-015"])
    @DisplayName("BR-015: the pause lasts the Operator Setting from the first warning; a later warning during it does not extend it")
    fun warningPauseCountsFromTheFirstWarning() = runTest {
        ready()
        slice.settings.settings = slice.settings.settings.copy(requestLimitWarningHours = 12)
        val first = slice.clock.instant()
        answerFeeds(outcome = SourceOutcome.RequestLimitWarning)
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))

        val report = fetchFeeds(confirmed = true) as FetchReport.Fetched

        assertEquals(RequestLimitWarningShown(first, first.plus(Duration.ofHours(12))), report.warning)
        assertEquals(1, slice.importStatusStore.backOffList.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A16: Operator Fetches Despite a Request Limit Warning", businessRules = ["BR-015"])
    @DisplayName("A16: during a Request Limit Warning Back-off a Fetch the Operator starts first shows the warning and its end and asks to confirm; nothing is sent until the Operator confirms")
    fun fetchDuringAWarningAsksToConfirm() = runTest {
        ready()
        val warnedAt = slice.clock.instant()
        val endsAt = warnedAt.plus(Duration.ofHours(24))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestLimitWarning, warnedAt, endsAt)
        answerFeeds()

        val asked = fetchFeeds()

        assertEquals(FetchReport.WarningToConfirm(slice.feedsKey, "Kestrel Feeds", RequestLimitWarningShown(warnedAt, endsAt)), asked)
        assertEquals(0, slice.feeds.fetches)
        assertTrue(slice.importStatusStore.fetches.isEmpty())

        val confirmed = fetchFeeds(confirmed = true)

        assertInstanceOf(FetchReport.Fetched::class.java, confirmed)
        assertEquals(1, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A16: Operator Fetches Despite a Request Limit Warning", businessRules = ["BR-004", "BR-015"])
    @DisplayName("BR-015: a request to wait is never overridden by confirming a warning")
    fun requestToWaitIsNotOverriddenByConfirming() = runTest {
        ready()
        answerFeeds()
        val endsAt = slice.clock.instant().plus(Duration.ofHours(24))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestLimitWarning, slice.clock.instant(), endsAt)
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, slice.clock.instant(), endsAt.minusSeconds(3600))

        assertEquals(FetchReport.WaitingForProvider(slice.feedsKey, "Kestrel Feeds", endsAt.minusSeconds(3600)), fetchFeeds(confirmed = true))
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-010"])
    @DisplayName("A11, BR-010: a Fetch of all Data Providers fetches each Data Provider in use in turn under its own gate; a failure of one leaves the next unaffected")
    fun fetchOfAllDataProvidersIsolatesEach() = runTest {
        ready()
        slice.credentials.enter(slice.wrenKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        answerFeeds(outcome = SourceOutcome.TransientFailure(FailureKind.ServiceUnavailable), accounts = emptyList())
        slice.wren.fetchAnswer = { FetchResponse(SourceOutcome.Completed, emptyList(), listOf(delivered("wren-1", "Wren Everyday"))) }

        val reports = (slice.fetch.fetchAll() as FetchStart.Ran).reports

        assertEquals(listOf("Kestrel Feeds", "Wren Feeds"), reports.map { it.dataProviderName })
        assertInstanceOf(FetchReport.Failed::class.java, reports[0])
        assertInstanceOf(FetchReport.Fetched::class.java, reports[1])
        assertEquals(1, slice.offeredStore.offered().count { it.dataProviderId == slice.wrenKey.dataProviderId })
        assertEquals(0, slice.openBanking.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-010"])
    @DisplayName("A11 (P9-16): while the Fetch runs, the operation coordinator shows it with the Data Provider it reads now, one after the other; once it ends nothing runs")
    fun runningFetchNamesTheDataProviderItReads() = runTest {
        ready()
        slice.credentials.enter(slice.wrenKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        val seen = mutableListOf<RunningOperation?>()
        slice.feeds.fetchAnswer = {
            seen += slice.coordinator.running.value
            FetchResponse(SourceOutcome.Completed, emptyList(), listed)
        }
        slice.wren.fetchAnswer = {
            seen += slice.coordinator.running.value
            FetchResponse(SourceOutcome.Completed, emptyList(), listOf(delivered("wren-1", "Wren Everyday")))
        }
        assertNull(slice.coordinator.running.value)

        slice.fetch.fetchAll()

        assertEquals(
            listOf(RunningOperation(OperationKind.Fetch, "Kestrel Feeds"), RunningOperation(OperationKind.Fetch, "Wren Feeds")),
            seen,
        )
        assertNull(slice.coordinator.running.value)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-010"])
    @DisplayName("A11: a Data Provider in use without credentials shows credentials missing; a Data Provider not set up is left out")
    fun fetchOfAllShowsMissingCredentials() = runTest {
        ready()
        answerFeeds()
        slice.credentials.remove(slice.feedsKey)
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")

        val reports = (slice.fetch.fetchAll() as FetchStart.Ran).reports

        assertEquals(listOf(FetchReport.CredentialsMissing(slice.feedsKey, "Kestrel Feeds")), reports)
        assertEquals(0, slice.feeds.fetches + slice.wren.fetches + slice.openBanking.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"])
    @DisplayName("BR-008: a Data Provider that needs a Bank Consent contacts no Bank without a valid one; this build grants none yet, so it is not contacted")
    fun dataProviderNeedingABankConsentIsNotContacted() = runTest {
        ready()
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        val report = (slice.fetch.fetch(slice.openBankingKey) as FetchStart.Ran).reports.single()

        assertEquals(FetchReport.NeedsBankConsent(slice.openBankingKey, "Aurora Open Banking"), report)
        assertEquals(0, slice.openBanking.fetches)
        assertTrue(slice.importStatusStore.fetches.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Failure postconditions: a Fetch writes nothing to the Budget App, prepares no Upload Plan and records no Offered Bank Account in the Decision Journal")
    fun fetchWritesNothingToTheBudgetApp() = runTest {
        ready()
        answerFeeds()
        val journalBefore = slice.journalRecorder.entries.size

        fetchFeeds()

        assertEquals(0, slice.budgetApp.writer.calls)
        assertEquals(CoordinatorMode.Normal, slice.coordinatorStore.mode)
        assertEquals(journalBefore, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: only the most recent Fetches are kept, as many as the Operator Setting says")
    fun onlyTheMostRecentFetchesAreKept() = runTest {
        ready()
        answerFeeds()
        slice.settings.settings = slice.settings.settings.copy(fetchHistorySize = 2)
        repeat(3) {
            fetchFeeds()
            slice.clock.advance(Duration.ofMinutes(61))
        }

        assertEquals(2, slice.importStatusStore.fetches.size)
    }
    // ---- Account Balances and Fetch Results (Phase 8f) ---------------------------------------------------------------

    /** The stored Account Balances, by Bank Account id. */
    private suspend fun balances(): Map<Long, StoredAccountBalance> =
        slice.importStatusStore.accountBalances().associateBy { it.bankAccountId }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Steps 8, 11: the Account Balance of each identified Bank Account is stored with the time the Data Provider reported it, a file-imported one's too (BR-011), and the Fetch records a Fetch Result Fetched for each, with the number of booked Bank Transactions delivered")
    fun accountBalanceAndFetchResultOfEachReadBankAccountAreStored() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        val hsa = addFileBankAccount("acct-6324", "Harbor Health Savings", "Health Savings")
        answerFeeds(accounts = listed + delivered("acct-6324", "HSA", amount(6310, 200)))
        val now = slice.clock.instant()

        fetchFeeds()

        assertEquals(
            mapOf(
                checking to StoredAccountBalance(checking, amount(4140, 50), USD, now),
                card to StoredAccountBalance(card, amount(-1268, 400), USD, now),
                hsa to StoredAccountBalance(hsa, amount(6310, 200), USD, now),
            ),
            balances(),
        )
        val fetch = fetchesOfFeeds().single()
        assertEquals(FetchOutcome.Fetched, fetch.outcome)
        assertEquals(
            listOf(
                StoredFetchResult(checking, FetchResultOutcome.Fetched, transactionCount = 0),
                StoredFetchResult(card, FetchResultOutcome.Fetched, transactionCount = 0),
                StoredFetchResult(hsa, FetchResultOutcome.Fetched, transactionCount = 0),
            ),
            fetch.results.sortedBy { it.bankAccountId },
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8 (S-T-04): the Account Balance is stored with the time the Data Provider says it was reported, or the time Slussen received the answer when the Data Provider gives none")
    fun accountBalanceIsStoredWithTheTimeItWasReported() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val reserve = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        val reportedAt = slice.clock.instant().minus(Duration.ofHours(6))
        val receivedAt = slice.clock.instant().plusSeconds(5)
        slice.feeds.fetchAnswer = {
            // The answer arrives a few seconds after the Fetch started.
            slice.clock.set(receivedAt)
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(
                    delivered("acct-4410", "CHK", result = BankAccountResult.Read(emptyList(), amount(4140, 50), balanceReportedAt = reportedAt)),
                    delivered("acct-7731", "Reserve Saver", result = BankAccountResult.Read(emptyList(), amount(12000), balanceReportedAt = null)),
                ),
            )
        }

        fetchFeeds()

        assertEquals(
            mapOf(
                checking to StoredAccountBalance(checking, amount(4140, 50), USD, reportedAt),
                reserve to StoredAccountBalance(reserve, amount(12000), USD, receivedAt),
            ),
            balances(),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A20: Bank's Sign-In at the Data Provider Needed")
    @DisplayName("A20 steps 1-2: a Bank Account whose Bank's sign-in at the Data Provider is needed keeps its stored Account Balance, starts no Back-off and gets the Fetch Result Sign-In Needed, while the others are stored as usual")
    fun signInNeededKeepsTheStoredAccountBalance() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", result = BankAccountResult.SignInNeeded),
                delivered("acct-1182", "Pinecrest Rewards Visa", amount(-547, 900), bank = "Pinecrest Card Services"),
            ),
        )

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(StoredAccountBalance(checking, amount(4140, 50), USD, first), balances().getValue(checking))
        assertEquals(StoredAccountBalance(card, amount(-547, 900), USD, slice.clock.instant()), balances().getValue(card))
        assertTrue(slice.importStatusStore.backOffList.isEmpty(), "no Back-off starts")
        assertEquals(FetchOutcome.Fetched, fetchesOfFeeds().first().outcome)
        assertEquals(
            listOf(StoredFetchResult(checking, FetchResultOutcome.SignInNeeded, 0), StoredFetchResult(card, FetchResultOutcome.Fetched, 0)),
            fetchesOfFeeds().first().results.sortedBy { it.bankAccountId },
        )
        assertEquals(listOf("Kestrel Checking"), report.signInNeeded)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8: the Account Balance is stored in the currency the Data Provider reports when it is the Bank Account's own, or in the Bank Account's own when it reports none")
    fun accountBalanceKeepsTheReportedCurrency() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val reserve = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", amount(4140, 50), currency = USD),
                delivered("acct-7731", "Reserve Saver", amount(12000), currency = null),
            ),
        )

        fetchFeeds()

        assertEquals(USD, balances().getValue(checking).currency)
        assertEquals(USD, balances().getValue(reserve).currency)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8: a later Fetch replaces the Account Balance, so only the most recent balance of each Bank Account is kept; each Fetch has its own Fetch Result")
    fun laterFetchReplacesTheAccountBalance() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(accounts = listOf(delivered("acct-4410", "CHK", amount(3990, 10))))

        fetchFeeds()

        assertEquals(listOf(StoredAccountBalance(checking, amount(3990, 10), USD, slice.clock.instant())), slice.importStatusStore.accountBalances())
        assertEquals(
            listOf(listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0)), listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0))),
            fetchesOfFeeds().map { it.results },
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8: a read Bank Account for which the Data Provider reports no balance keeps the Account Balance stored before; its Fetch Result is Fetched")
    fun noReportedBalanceKeepsTheStoredOne() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(accounts = listOf(delivered("acct-4410", "CHK", balance = null)))

        fetchFeeds()

        assertEquals(StoredAccountBalance(checking, amount(4140, 50), USD, first), balances().getValue(checking))
        assertEquals(listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0)), fetchesOfFeeds().first().results)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A18: Bank Account Not Added")
    @DisplayName("A18, S-35: an Offered Bank Account that is not added stores no Account Balance and no Fetch Result, though the Data Provider listed its balance; nor does a Left-Out Bank Account, which is not counted as not added")
    fun bankAccountNotAddedStoresNoAccountBalance() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        slice.connectionStore.leftOutList += StoredLeftOutBankAccount(77, slice.feedsId, "acct-1182", "Pinecrest Rewards Visa", slice.clock.instant())
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", amount(4140, 50)),
                delivered("acct-7731", "Reserve Saver", kind = "savings", result = BankAccountResult.NotRead(NotReadCause.NotSelected, amount(12000))),
                delivered("acct-1182", "Pinecrest Rewards Visa", result = BankAccountResult.NotRead(NotReadCause.NotSelected, amount(-1268, 400))),
            ),
        )

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(setOf(checking), balances().keys)
        assertEquals(listOf(checking), fetchesOfFeeds().single().results.map { it.bankAccountId })
        assertEquals(1, report.notAdded)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A10: Bank Account Not Identified With Certainty")
    @DisplayName("A10, UC-009 BR-004: two delivered Bank Accounts with the same reference are neither kept as Offered Bank Accounts nor identified, the Fetch says so and records Ambiguous Bank Account for the Bank Account selected with that reference")
    fun sharedReferenceIdentifiesNeither() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", amount(4140, 50)),
                delivered("acct-4410", "Other CHK", amount(990)),
                delivered("acct-7731", "Reserve Saver", amount(12000), kind = "savings"),
            ),
        )

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(emptyList<String>(), report.identified)
        assertEquals(2, report.sharedReference)
        assertEquals(1, report.offered)
        assertEquals(1, report.notAdded)
        assertFalse(checking in balances().keys)
        // A10 step 3: the selected Bank Account whose reference two delivered Bank Accounts share is Ambiguous Bank Account.
        assertEquals(listOf(StoredFetchResult(checking, FetchResultOutcome.AmbiguousBankAccount, 0)), fetchesOfFeeds().single().results)
        val offered = slice.connect.pairing().sources.single { it.dataProviderId == slice.feedsId }.offered
        assertEquals(listOf("Reserve Saver"), offered.map { it.nameOrProduct })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("A8, BR-010: when the Data Provider reports a failure for one Bank Account, its Account Balance stays unchanged and its Fetch Result is Failed, while the others are stored as usual")
    fun failureForOneBankAccountKeepsItsAccountBalance() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", result = BankAccountResult.Failed(FailureKind.Timeout)),
                delivered("acct-1182", "Pinecrest Rewards Visa", amount(-547, 900), bank = "Pinecrest Card Services"),
            ),
        )

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(StoredAccountBalance(checking, amount(4140, 50), USD, first), balances().getValue(checking))
        assertEquals(StoredAccountBalance(card, amount(-547, 900), USD, slice.clock.instant()), balances().getValue(card))
        assertEquals(
            listOf(StoredFetchResult(checking, FetchResultOutcome.Failed, 0), StoredFetchResult(card, FetchResultOutcome.Fetched, 0)),
            fetchesOfFeeds().first().results.sortedBy { it.bankAccountId },
        )
        assertEquals(FetchOutcome.Fetched, fetchesOfFeeds().first().outcome)
        assertEquals(listOf("Kestrel Checking"), report.failed)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", businessRules = ["BR-005"])
    @DisplayName("A6: a Fetch without a usable answer keeps every stored Account Balance unchanged and records no Fetch Result")
    fun unusableAnswerKeepsTheAccountBalances() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        fetchFeeds()
        val stored = slice.importStatusStore.accountBalances()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(outcome = SourceOutcome.TransientFailure(FailureKind.ServiceUnavailable), accounts = emptyList())

        fetchFeeds()

        assertEquals(stored, slice.importStatusStore.accountBalances())
        assertEquals(FetchOutcome.Failed, fetchesOfFeeds().first().outcome)
        assertTrue(fetchesOfFeeds().first().results.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials")
    @DisplayName("A7: refused credentials keep every stored Account Balance unchanged and record no Fetch Result")
    fun refusedCredentialsKeepTheAccountBalances() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        fetchFeeds()
        val stored = slice.importStatusStore.accountBalances()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(outcome = SourceOutcome.CredentialsRefused, accounts = emptyList())

        fetchFeeds()

        assertEquals(stored, slice.importStatusStore.accountBalances())
        assertEquals(FetchOutcome.CredentialsRefused, fetchesOfFeeds().first().outcome)
        assertTrue(fetchesOfFeeds().first().results.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5: when the Data Provider asks to wait while delivering, the delivered Bank Accounts are stored with their Fetch Result, and the one not delivered keeps its Account Balance with no Fetch Result")
    fun askedToWaitStoresWhatWasDelivered() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(
            outcome = SourceOutcome.AskedToWait(null),
            accounts = listOf(
                delivered("acct-4410", "CHK", amount(4001)),
                delivered("acct-1182", "Pinecrest Rewards Visa", result = BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource)),
            ),
        )

        fetchFeeds()

        assertEquals(StoredAccountBalance(checking, amount(4001), USD, slice.clock.instant()), balances().getValue(checking))
        assertEquals(StoredAccountBalance(card, amount(-1268, 400), USD, first), balances().getValue(card))
        assertEquals(listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0)), fetchesOfFeeds().first().results)
        assertEquals(1, slice.importStatusStore.backOffList.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5 step 2: a selected Bank Account that the answer leaves out while the Data Provider asks to wait is affected by the wait, not Not Delivered: its data is kept and it gets no Fetch Result")
    fun bankAccountLeftOutWhileWaitingIsNotNotDelivered() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(61))
        answerFeeds(outcome = SourceOutcome.AskedToWait(null), accounts = listOf(delivered("acct-4410", "CHK", amount(4001))))

        val report = fetchFeeds() as FetchReport.Fetched

        assertEquals(StoredAccountBalance(card, amount(-1268, 400), USD, first), balances().getValue(card))
        assertEquals(listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0)), fetchesOfFeeds().first().results)
        assertTrue(report.notDelivered.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("A2: within the Fetch Cooldown the Bank Account added since is read and its Account Balance stored, with its Fetch Result on the Fetch Served From Fetch Cooldown; the other Bank Accounts keep theirs from the last Fetch")
    fun withinTheCooldownTheBankAccountAddedSinceStoresItsBalance() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        val first = slice.clock.instant()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(10))
        val reserve = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        slice.clock.advance(Duration.ofMinutes(10))

        fetchFeeds()

        assertEquals(StoredAccountBalance(checking, amount(4140, 50), USD, first), balances().getValue(checking))
        assertEquals(StoredAccountBalance(reserve, amount(12000), USD, slice.clock.instant()), balances().getValue(reserve))
        val served = fetchesOfFeeds().first()
        assertEquals(FetchOutcome.ServedFromFetchCooldown, served.outcome)
        assertEquals(listOf(StoredFetchResult(reserve, FetchResultOutcome.Fetched, 0)), served.results)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: a Fetch that is no longer among the most recent ones is removed with its Fetch Results; the Account Balances stay")
    fun olderFetchesGoWithTheirFetchResults() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        slice.settings.settings = slice.settings.settings.copy(fetchHistorySize = 2)
        repeat(3) {
            fetchFeeds()
            slice.clock.advance(Duration.ofMinutes(61))
        }

        val kept = fetchesOfFeeds()
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.results == listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0)) })
        assertEquals(setOf(checking), balances().keys)
    }

    // ---- Bank Transactions (transactions plan Phase 6a) -------------------------------------------------------------

    private fun tx(
        reference: String?,
        date: LocalDate,
        amount: Amount,
        payee: String? = "Corner Grocer",
        memo: String? = null,
        booked: Boolean = true,
        currency: CurrencyCode = USD,
    ) = ProviderTransaction.Interpretable(reference, date, amount, currency, payee, memo, booked)

    private fun unreadable(
        reason: UnreadableReason,
        date: LocalDate? = null,
        amount: Amount? = null,
        payee: String? = "Harbor Fuel",
        booked: Boolean? = true,
    ) = ProviderTransaction.Uninterpretable(date, amount, USD.takeIf { amount != null }, payee, null, booked, reason)

    /** Kestrel Feeds answers with [transactions] and [balance] for Kestrel Checking (`acct-4410`). */
    private fun answerChecking(vararg transactions: ProviderTransaction, balance: Amount? = amount(4140, 50), currency: CurrencyCode? = USD) {
        answerFeeds(accounts = listOf(delivered("acct-4410", "CHK", currency = currency, result = BankAccountResult.Read(transactions.toList(), balance))))
    }

    /** The next Fetch, after the Fetch Cooldown of the last one has ended. */
    private suspend fun fetchFeedsLater(): FetchReport {
        slice.clock.advance(Duration.ofMinutes(61))
        return fetchFeeds()
    }

    private suspend fun storedTransactions(bankAccountId: Long) = slice.importStatusStore.bankTransactionsOf(bankAccountId)

    private suspend fun storedUnreadable(bankAccountId: Long) = slice.importStatusStore.unreadableTransactionsOf(bankAccountId)

    /** The Decision Journal entries of step 9 among [entries]; the Comparison of step 10 records its own (UC-003 step 6). */
    private fun stepNine(entries: List<JournalEntry>) = entries.filterNot { it.details.startsWith("Transaction Status changed") }

    private fun r(reference: String) = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"], requirements = ["NFR-002"])
    @DisplayName("Steps 7-8, BR-002, BR-003: only booked transactions with an amount other than zero are stored as Bank Transactions; a pending one and one of zero are left out, and the Fetch Result counts the booked ones delivered")
    fun onlyBookedNonZeroTransactionsAreStored() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(
            tx("TX-1", oct14, amount(-64, 180)),
            tx("TX-2", oct14, amount(-12), booked = false),
            tx("TX-3", oct14, Amount(0)),
            tx("TX-4", oct14.minusDays(1), amount(2500), payee = "Example Employer"),
        )

        fetchFeeds()

        assertEquals(listOf("TX-1", "TX-4"), storedTransactions(checking).map { it.stableReference })
        assertEquals(listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 2)), fetchesOfFeeds().single().results)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: a transaction left out while pending is stored once a later Fetch delivers it as booked")
    fun pendingTransactionIsStoredOnceBooked() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(tx("TX-1", oct14, amount(-64, 180), booked = false))
        fetchFeeds()
        assertTrue(storedTransactions(checking).isEmpty())
        answerChecking(tx("TX-1", oct14, amount(-64, 180)))

        fetchFeedsLater()

        assertEquals(listOf("TX-1"), storedTransactions(checking).map { it.stableReference })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", requirements = ["NFR-020"])
    @DisplayName("Step 8, UC-003 BR-002 (S-T-08): a Bank Transaction is stored with its Import Identifier from the Source Key and the Stable Reference, occurrence_number 1, the reference as Supplied, the time first received, the content as delivered, and no Transaction Status until a Comparison has read its Budget (here the Budget App cannot be read, UC-003 A1)")
    fun bankTransactionIsStoredWithItsImportIdentifier() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(tx("TX-1", oct14, amount(-64, 180), payee = "Corner Grocer", memo = "card 0042"))
        slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(FailureKind.Timeout) }
        val receivedAt = slice.clock.instant()

        fetchFeeds()

        val stored = storedTransactions(checking).single()
        assertEquals(checking, stored.bankAccountId)
        assertEquals("TX-1", stored.stableReference)
        assertEquals(StableReferenceKind.Supplied, stored.referenceKind)
        assertEquals(1, stored.occurrenceNumber)
        assertEquals(r("TX-1"), stored.importIdentifier)
        assertEquals(oct14, stored.bookedDate)
        assertEquals(amount(-64, 180), stored.amount)
        assertEquals(USD, stored.currency)
        assertEquals("Corner Grocer", stored.payee)
        assertEquals("card 0042", stored.memo)
        assertEquals(receivedAt, stored.firstSeenAt)
        assertNull(stored.status)
        assertNull(stored.transferId)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 8: a Bank Transaction delivered again unchanged is stored once; one delivered again with changed content is updated in place and keeps its Import Identifier and the time first received")
    fun knownBankTransactionIsUpdatedInPlace() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(tx("TX-1", oct14, amount(-64, 180), payee = "CORNER GROCER 0042"), tx("TX-2", oct14, amount(-5)))
        val firstSeen = slice.clock.instant()
        fetchFeeds()
        answerChecking(tx("TX-1", oct14, amount(-64, 180), payee = "Corner Grocer", memo = "weekly shop"), tx("TX-2", oct14, amount(-5)))

        fetchFeedsLater()

        val stored = storedTransactions(checking)
        assertEquals(2, stored.size)
        val changed = stored.single { it.stableReference == "TX-1" }
        assertEquals("Corner Grocer", changed.payee)
        assertEquals("weekly shop", changed.memo)
        assertEquals(r("TX-1"), changed.importIdentifier)
        assertEquals(firstSeen, changed.firstSeenAt)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", requirements = ["NFR-020"])
    @DisplayName("Step 8, ADR-008 §5: transactions without a Stable Reference that are identical in date, amount, payee and memo get occurrence_number 1, 2, … in the order delivered; a later Fetch that delivers as many adds none, one that delivers more adds only the additional ones")
    fun identicalTransactionsWithoutStableReferenceGetTheirOccurrence() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val coffee = tx(null, oct14, amount(-4, 500), payee = "Harbor Caf\u00e9")
        // ADR-008 §2: the same payee with a combining accent is identical in Normalization Form C.
        val decomposed = coffee.copy(payee = "Harbor Cafe\u0301")
        answerChecking(coffee, decomposed, tx(null, oct14, amount(-9)))
        fetchFeeds()

        val first = storedTransactions(checking)
        assertEquals(listOf(1, 2, 1), first.map { it.occurrenceNumber })
        assertTrue(first.all { it.stableReference == null && it.referenceKind == StableReferenceKind.None })
        val expected = (1..2).map {
            ImportIdentifierProtocol.importIdentifier(
                ImportIdentifierInput.WithoutStableReference("example-feeds", oct14, amount(-4, 500), "Harbor Caf\u00e9", null, it),
            )
        }
        assertEquals(expected, first.take(2).map { it.importIdentifier })

        answerChecking(coffee, coffee, tx(null, oct14, amount(-9)))
        fetchFeedsLater()
        assertEquals(3, storedTransactions(checking).size)

        answerChecking(coffee, coffee.copy(memo = ""), coffee, tx(null, oct14, amount(-9)))
        fetchFeedsLater()
        assertEquals(listOf(1, 2, 1, 3), storedTransactions(checking).map { it.occurrenceNumber })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 9, UC-011 BR-001: each Bank Transaction received for the first time or with changed content is recorded in the Decision Journal with its Bank Account and Import Identifier; one delivered again unchanged is not")
    fun receivedBankTransactionsAreJournaled() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(tx("TX-1", oct14, amount(-64, 180)), tx("TX-2", oct14, amount(-5)))
        val before = slice.journalRecorder.entries.size
        fetchFeeds()

        val firstTime = stepNine(slice.journalRecorder.entries.drop(before))
        assertEquals(listOf(JournalEventKind.TransactionSeen, JournalEventKind.TransactionSeen), firstTime.map { it.eventKind })
        assertEquals(listOf(r("TX-1"), r("TX-2")), firstTime.map { it.importIdentifier })
        assertTrue(firstTime.all { it.bankAccountName == "Kestrel Checking" })
        assertEquals("Bank Transaction received for the first time: booked 2026-10-14, -64.180 USD", firstTime.first().details)

        val beforeSecond = slice.journalRecorder.entries.size
        answerChecking(tx("TX-1", oct14, amount(-64, 180), payee = "Corner Grocer Ltd"), tx("TX-2", oct14, amount(-5)))
        fetchFeedsLater()

        val changed = stepNine(slice.journalRecorder.entries.drop(beforeSecond)).single()
        assertEquals(JournalEventKind.TransactionChanged, changed.eventKind)
        assertEquals(r("TX-1"), changed.importIdentifier)
        assertEquals("Kestrel Checking", changed.bankAccountName)
        assertEquals("Bank Transaction received with changed content (payee): booked 2026-10-14, -64.180 USD", changed.details)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", requirements = ["NFR-012"])
    @DisplayName("Step 9, NFR-012: a Decision Journal entry about a Bank Transaction holds no text the Data Provider delivered and no identifier it gives a Bank Account; with the Decision Journal switched off nothing is recorded")
    fun journalHoldsNoDataProviderText() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(tx("PLANTED-REF-9c1", oct14, amount(-64, 180), payee = "PLANTED-PAYEE-77d", memo = "PLANTED-MEMO-3e0"))
        val before = slice.journalRecorder.entries.size

        fetchFeeds()

        val entries = stepNine(slice.journalRecorder.entries.drop(before))
        assertEquals(1, entries.size)
        listOf("PLANTED", "acct-4410", "CHK").forEach { planted -> assertTrue(entries.none { planted in it.toString() }, planted) }

        slice.settings.settings = slice.settings.settings.copy(journalOn = false)
        answerChecking(tx("TX-NEW", oct14, amount(-1)))
        val whileOff = slice.journalRecorder.entries.size
        fetchFeedsLater()
        assertEquals(whileOff, slice.journalRecorder.entries.size)
        assertEquals(2, storedTransactions(slice.connectionStore.bankAccounts().single().id).size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"], requirements = ["NFR-002"])
    @DisplayName("A17 (S-T-16): a booked transaction in another currency than its Bank Account's is kept as an Unreadable Transaction for review, never stored as a Bank Transaction and never converted")
    fun transactionInAnotherCurrencyIsKeptAsUnreadable() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val receivedAt = slice.clock.instant()
        answerChecking(
            tx("TX-1", oct14, amount(-64, 180)),
            tx("TX-2", oct14, amount(-12, 500), currency = ExampleData.EUR),
        )

        fetchFeeds()

        assertEquals(listOf("TX-1"), storedTransactions(checking).map { it.stableReference })
        val kept = storedUnreadable(checking).single()
        assertEquals(
            StoredUnreadableTransaction(kept.id, checking, oct14, amount(-12, 500), ExampleData.EUR, "Corner Grocer", null, "currency differs from the Bank Account's", receivedAt, UnreadableTransactionStatus.NeedsReview),
            kept,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-002", "BR-016"], requirements = ["NFR-002"])
    @DisplayName("A17, BR-016: a transaction Slussen cannot interpret is kept as an Unreadable Transaction with whatever fields could be read, its reason and the status Needs Review, never as a Bank Transaction; one known to be pending is left out")
    fun uninterpretableTransactionIsKeptAsUnreadable() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val receivedAt = slice.clock.instant()
        answerChecking(
            tx("TX-1", oct14, amount(-64, 180)),
            unreadable(UnreadableReason.MissingDate, amount = amount(-30)),
            unreadable(UnreadableReason.UnreadableAmount, date = oct14, booked = null),
            unreadable(UnreadableReason.MissingAmount, date = oct14, booked = false),
        )

        fetchFeeds()

        assertEquals(1, storedTransactions(checking).size)
        val kept = storedUnreadable(checking)
        assertEquals(
            listOf(
                StoredUnreadableTransaction(kept[0].id, checking, null, amount(-30), USD, "Harbor Fuel", null, "date missing", receivedAt, UnreadableTransactionStatus.NeedsReview),
                StoredUnreadableTransaction(kept[1].id, checking, oct14, null, null, "Harbor Fuel", null, "amount unreadable", receivedAt, UnreadableTransactionStatus.NeedsReview),
            ),
            kept,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("BR-016: a later Fetch that delivers as many Unreadable Transactions with the same readable fields and reason as are stored adds none; when it delivers more, only the additional ones are added; none is ever removed")
    fun unreadableTransactionsAreCountedAgainstTheStoredOnes() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val fuel = unreadable(UnreadableReason.MissingDate, amount = amount(-30))
        answerChecking(fuel, fuel)
        fetchFeeds()
        assertEquals(2, storedUnreadable(checking).size)

        answerChecking(fuel, fuel)
        fetchFeedsLater()
        assertEquals(2, storedUnreadable(checking).size)

        answerChecking(fuel, fuel, fuel, unreadable(UnreadableReason.MissingDate, amount = amount(-31)))
        fetchFeedsLater()
        assertEquals(4, storedUnreadable(checking).size)

        answerChecking()
        fetchFeedsLater()
        assertEquals(4, storedUnreadable(checking).size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"])
    @DisplayName("A17, ADR-008 §2 (B-08): a transaction whose Stable Reference is not well-formed Unicode is kept as an Unreadable Transaction, never stored with a replaced character")
    fun malformedStableReferenceIsUnreadable() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(tx("TX-\uD83E", oct14, amount(-64, 180)))

        fetchFeeds()

        assertTrue(storedTransactions(checking).isEmpty())
        val kept = storedUnreadable(checking).single()
        assertEquals("transaction unreadable", kept.reason)
        assertEquals(amount(-64, 180), kept.amount)
        assertEquals(oct14, kept.bookedDate)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A17: Transaction Cannot Be Interpreted", businessRules = ["BR-016"], requirements = ["NFR-020"])
    @DisplayName("A17, NFR-020, ADR-008 §5: a new transaction whose Import Identifier is held by a different stored Bank Transaction of the same Bank Account is kept as an Unreadable Transaction with the reason Import Identifier collision, never merged; the collision is recorded in the Decision Journal")
    fun importIdentifierCollisionIsKeptAsUnreadable() = runTest {
        val planted = TestSlice(identify = { ImportIdentifierProtocol.importIdentifier(it) { ByteArray(32) } })
        planted.enterCredentials()
        val read = planted.connect.readBudgets() as BudgetsRead.Listed
        planted.connect.chooseBudgets(read.budgets, setOf("budget-household"))
        val budgetAccount = planted.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        val checking = (
            planted.connectionStore.addBankAccount(
                NewBankAccount(
                    generatedIdentifier = "00000000-0000-4000-8000-000000004410",
                    bank = BankChoice.Named("Kestrel Bank"),
                    providerBankId = null,
                    dataProviderId = planted.feedsId,
                    fileFormatId = null,
                    balanceDataProviderId = null,
                    balanceProviderReference = null,
                    name = "Kestrel Checking",
                    currency = USD,
                    type = BankAccountType.Checking,
                    providerReference = "acct-4410",
                    bankAccountFingerprint = null,
                    identifiedAt = planted.clock.instant(),
                    startDate = null,
                    budgetAccountId = budgetAccount,
                ),
            ) as AddOutcome.Added
            ).bankAccountId
        planted.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(delivered("acct-4410", "CHK", result = BankAccountResult.Read(listOf(tx("TX-1", oct14, amount(-64, 180)), tx("TX-2", oct14, amount(-5))), amount(100)))),
            )
        }
        val before = planted.journalRecorder.entries.size

        planted.fetch.fetch(planted.feedsKey)

        val stored = planted.importStatusStore.bankTransactionsOf(checking).single()
        assertEquals("TX-1", stored.stableReference)
        val collided = planted.importStatusStore.unreadableTransactionsOf(checking).single()
        assertEquals("Import Identifier collision", collided.reason)
        assertEquals(amount(-5), collided.amount)
        assertEquals(UnreadableTransactionStatus.NeedsReview, collided.status)
        val entries = stepNine(planted.journalRecorder.entries.drop(before))
        assertEquals(listOf(JournalEventKind.TransactionSeen, JournalEventKind.StatusChanged), entries.map { it.eventKind })
        assertEquals(stored.importIdentifier, entries.last().importIdentifier)
        assertEquals("Kestrel Checking", entries.last().bankAccountName)

        // BR-016: the same collision delivered again adds no second Unreadable Transaction.
        planted.clock.advance(Duration.ofMinutes(61))
        planted.fetch.fetch(planted.feedsKey)
        assertEquals(1, planted.importStatusStore.unreadableTransactionsOf(checking).size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: a Bank Account whose transactions come from Transaction Files stores only its balance from the Data Provider, never a transaction the Data Provider delivers for it")
    fun fileImportedBankAccountStoresNoTransactionFromTheDataProvider() = runTest {
        ready()
        val hsa = addFileBankAccount("acct-6324", "Harbor Health Savings", "Health Savings")
        answerFeeds(
            accounts = listOf(delivered("acct-6324", "HSA", result = BankAccountResult.Read(listOf(tx("TX-H", oct14, amount(-20))), amount(6310, 200)))),
        )

        fetchFeeds()

        assertTrue(storedTransactions(hsa).isEmpty())
        assertEquals(amount(6310, 200), balances().getValue(hsa).amount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("Failure postconditions, BR-010: no transaction of one Bank Account is stored as one of another, and a Bank Account with a failure keeps its stored Bank Transactions unchanged")
    fun transactionsStayWithTheirBankAccount() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", result = BankAccountResult.Read(listOf(tx("TX-1", oct14, amount(-64, 180))), amount(100))),
                delivered("acct-1182", "Visa", result = BankAccountResult.Read(listOf(tx("TX-1", oct14, amount(-12))), amount(-5))),
            ),
        )
        fetchFeeds()
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", result = BankAccountResult.Failed(FailureKind.Incomplete)),
                delivered("acct-1182", "Visa", result = BankAccountResult.Read(listOf(tx("TX-1", oct14, amount(-12)), tx("TX-2", oct14, amount(-3))), amount(-5))),
            ),
        )

        fetchFeedsLater()

        assertEquals(listOf(amount(-64, 180)), storedTransactions(checking).map { it.amount })
        assertEquals(listOf(amount(-12), amount(-3)), storedTransactions(card).map { it.amount })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A19: Selected Bank Account Not Delivered", businessRules = ["BR-010"])
    @DisplayName("A19 steps 1-2: a selected Bank Account the answer does not contain at all keeps its stored Bank Transactions and Account Balance and gets the Fetch Result Not Delivered; the others are stored as usual")
    fun selectedBankAccountNotDeliveredKeepsItsData() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        val reserve = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        answerFeeds(
            accounts = listOf(
                delivered("acct-4410", "CHK", result = BankAccountResult.Read(listOf(tx("TX-1", oct14, amount(-64, 180))), amount(100))),
                delivered("acct-7731", "Reserve", result = BankAccountResult.Read(listOf(tx("TX-R", oct14, amount(50))), amount(12000))),
            ),
        )
        val first = slice.clock.instant()
        fetchFeeds()
        answerFeeds(accounts = listOf(delivered("acct-4410", "CHK", result = BankAccountResult.Read(emptyList(), amount(90)))))

        val report = fetchFeedsLater() as FetchReport.Fetched

        assertEquals(StoredAccountBalance(reserve, amount(12000), USD, first), balances().getValue(reserve))
        assertEquals(listOf("TX-R"), storedTransactions(reserve).map { it.stableReference })
        assertEquals(
            listOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 0), StoredFetchResult(reserve, FetchResultOutcome.NotDelivered, 0)),
            fetchesOfFeeds().first().results.sortedBy { it.bankAccountId },
        )
        assertEquals(listOf("Kestrel Reserve"), report.notDelivered)
        assertEquals(amount(90), balances().getValue(checking).amount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A19: Selected Bank Account Not Delivered")
    @DisplayName("A19 step 3, UC-008 BR-006: the Bank Account not delivered shows the Blocked Reason not delivered by the Data Provider, naming it, with the time of the Fetch and the action that fetches again")
    fun notDeliveredIsShownAsBlockedReason() = runTest {
        ready()
        val reserve = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        answerFeeds(accounts = listOf(delivered("acct-4410", "CHK")))
        val fetchedAt = slice.clock.instant()

        fetchFeeds()

        val household = slice.connectionStore.budgets().single { it.name == "Household" }.id
        val card = slice.status.workspace(household).bankAccounts.single { it.bankAccountId == reserve }
        assertEquals(
            listOf(Blocked(BlockedReason.NotDelivered, Remedy.FetchAgain, fetchedAt, "Kestrel Reserve", dataProviderName = "Kestrel Feeds")),
            card.blocked,
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A20: Bank's Sign-In at the Data Provider Needed", requirements = ["NFR-012"])
    @DisplayName("A20 step 3, UC-008 BR-006, NFR-012: a Bank Account whose Bank's sign-in at the Data Provider is needed shows that Blocked Reason in Slussen's words, naming the Data Provider, with the action to sign in again there and fetch again")
    fun signInNeededIsShownAsBlockedReason() = runTest {
        ready()
        val card = addFeedsBankAccount("acct-1182", "Pinecrest Rewards Card", "Rewards Card")
        answerFeeds(accounts = listOf(delivered("acct-1182", "Visa", result = BankAccountResult.SignInNeeded)))
        val before = slice.journalRecorder.entries.size

        fetchFeeds()

        val household = slice.connectionStore.budgets().single { it.name == "Household" }.id
        val status = slice.status.workspace(household).bankAccounts.single { it.bankAccountId == card }
        assertEquals(
            listOf(Blocked(BlockedReason.SignInNeeded, Remedy.SignInAtDataProvider, null, "Pinecrest Rewards Card", dataProviderName = "Kestrel Feeds")),
            status.blocked,
        )
        assertEquals(before, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A21: Balance in Another Currency")
    @DisplayName("A21: a balance reported in another currency than the Bank Account's is not stored and nothing is converted; the previous Account Balance stays, the Bank Transactions are stored as usual, and the Bank Account shows both currencies until a later Fetch stores its balance")
    fun balanceInAnotherCurrencyIsNotStored() = runTest {
        ready()
        val checking = addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerChecking(balance = amount(5071, 100))
        val first = slice.clock.instant()
        fetchFeeds()
        answerChecking(tx("TX-1", oct14, amount(-64, 180)), balance = amount(4700), currency = ExampleData.EUR)

        val report = fetchFeedsLater() as FetchReport.Fetched

        assertEquals(StoredAccountBalance(checking, amount(5071, 100), USD, first), balances().getValue(checking))
        assertEquals(1, storedTransactions(checking).size)
        val notice = BalanceInOtherCurrency(checking, "Kestrel Checking", "Kestrel Feeds", ExampleData.EUR, USD, slice.clock.instant())
        assertEquals(listOf(notice), report.balancesInOtherCurrency)
        assertEquals(StoredFetchResult(checking, FetchResultOutcome.Fetched, 1), fetchesOfFeeds().first().results.single())
        val household = slice.connectionStore.budgets().single { it.name == "Household" }.id
        assertEquals(notice, slice.status.workspace(household).bankAccounts.single().balanceInOtherCurrency)

        answerChecking(balance = amount(5000))
        fetchFeedsLater()
        assertNull(slice.status.workspace(household).bankAccounts.single().balanceInOtherCurrency)
        assertEquals(amount(5000), balances().getValue(checking).amount)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("BR-006 (S-T-02, #9): within the Fetch Cooldown a Bank Account is read until it has a Fetch Result Fetched since it was added or identified again; a read that failed does not count, a later identification makes it read again")
    fun withinTheCooldownABankAccountIsReadUntilItWasFetched() = runTest {
        ready()
        addFeedsBankAccount("acct-4410", "Kestrel Checking", "Checking")
        answerFeeds()
        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(5))
        val reserve = addFeedsBankAccount("acct-7731", "Kestrel Reserve", "Reserve Savings")
        answerFeeds(accounts = listOf(delivered("acct-7731", "Reserve", result = BankAccountResult.Failed(FailureKind.Timeout))))
        slice.clock.advance(Duration.ofMinutes(5))

        fetchFeeds()
        slice.clock.advance(Duration.ofMinutes(5))
        answerFeeds(accounts = listOf(delivered("acct-7731", "Reserve")))
        val readAgain = fetchFeeds() as FetchReport.ServedFromFetchCooldown

        assertEquals(listOf("Kestrel Reserve"), readAgain.read?.identified)
        assertEquals(3, slice.feeds.fetches)
        slice.clock.advance(Duration.ofMinutes(5))
        assertNull((fetchFeeds() as FetchReport.ServedFromFetchCooldown).read)
        assertEquals(3, slice.feeds.fetches)

        slice.clock.advance(Duration.ofMinutes(5))
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == reserve) it.copy(identifiedAt = slice.clock.instant()) else it }
        slice.clock.advance(Duration.ofMinutes(1))
        assertEquals(listOf("Kestrel Reserve"), (fetchFeeds() as FetchReport.ServedFromFetchCooldown).read?.identified)
        assertEquals(4, slice.feeds.fetches)
    }
}
