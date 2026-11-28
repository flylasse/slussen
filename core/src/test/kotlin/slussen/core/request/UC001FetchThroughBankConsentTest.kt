package slussen.core.request

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.connection.BudgetsRead
import slussen.core.consent.CompletionOutcome
import slussen.core.fetch.BankWaiting
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.fetch.ScheduledFetchOutcome
import slussen.core.fetch.UnattendedBank
import slussen.core.fetch.UnattendedUse
import slussen.core.operation.OperationKind
import slussen.core.port.AddOutcome
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBackOff
import slussen.core.port.NewBankAccount
import slussen.core.port.NewProviderRequest
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StableReferenceKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredFetchResult
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankOutcome
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RefusalScope
import slussen.pluginapi.request.RequestGate
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ScriptedBankConsent
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Fetch Transactions (UC-001) through a Data Provider that needs a Bank Consent (S-B-47; UC-007 BR-009), on the fakes of
 * `:testing`: the Bank Sessions handed to the Fetch, A4, A5 for one Bank, A9, A10 for a Bank Account its Bank Consent
 * does not cover, A22, the Fetch Cooldown (BR-006 as amended), and the same history fetched twice.
 */
@DisplayName("UC-001: Fetch Transactions through a Bank Consent")
class UC001FetchThroughBankConsentTest {
    private val slice = TestSlice()
    private val provider = slice.openBanking
    private val consent = slice.bankConsent
    private val key get() = slice.openBankingKey
    private val now: Instant get() = slice.clock.instant()

    private val expiry = Instant.parse("2027-04-13T12:00:00Z")

    @BeforeEach
    fun ready() = runTest {
        slice.credentials.enter(slussen.core.port.ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
        slice.credentials.enter(key, slice.input("setupKey" to ExampleData.SETUP_KEY))
        consent.startAnswer = { ConsentStart.Started("https://aurora.example/sca", null) }
    }

    private fun offered(reference: String, bank: String) = DeliveredBankAccount(
        reference, bank, "Account $reference", "current", EUR, null, BankAccountResult.NotRead(NotReadCause.NotSelected),
    )

    /** UC-007: a Bank Consent of [bank] granted until [until], covering [references], with its Bank Session `session-<bank>`. */
    private suspend fun grant(bank: String, vararg references: String, until: Instant = expiry) {
        consent.completeAnswer = { _, _ ->
            ConsentCompletion.Completed(until, references.map { offered(it, bank) }, ConsentSession("session-$bank"))
        }
        slice.bankConsents.start(key, bank, browserAvailable = true)
        val granted = slice.bankConsents.complete(ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=c&state=${consent.stateTexts().last()}"))
        assertTrue(granted is CompletionOutcome.Granted, "$granted")
    }

    private suspend fun bankId(bank: String): Long = slice.connectionStore.bankNamed(bank).id

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    /** UC-013: a Bank Account the Operator added for the Data Provider, at the Bank it names. */
    private suspend fun add(reference: String, name: String, bank: String, budgetAccount: String): Long = (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-${reference.hashCode().toUInt().toString().padStart(12, '0').take(12)}",
                bank = BankChoice.Known(bankId(bank)),
                providerBankId = bankId(bank),
                dataProviderId = slice.openBankingId,
                fileFormatId = null,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = name,
                currency = EUR,
                type = BankAccountType.Checking,
                providerReference = reference,
                bankAccountFingerprint = null,
                identifiedAt = now,
                startDate = null,
                budgetAccountId = budgetAccountId(budgetAccount),
            ),
        ) as AddOutcome.Added
        ).bankAccountId

    private fun booked(date: String, units: Long, payee: String = "Harbor Pharmacy", memo: String? = "Card purchase") =
        ProviderTransaction.Interpretable(null, LocalDate.parse(date), Amount(units * 1000), EUR, payee, memo, booked = true)

    /** The Data Provider reads each selected Bank Account with [transactions] of its reference and no balance. */
    private fun answering(transactions: (String) -> List<ProviderTransaction> = { emptyList() }, balance: Amount? = null) {
        provider.fetchAnswer = { request ->
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                selectedOf(request).map { reference ->
                    DeliveredBankAccount(reference, bankOf(request, reference), null, null, EUR, null, BankAccountResult.Read(transactions(reference), balance))
                },
            )
        }
    }

    private fun selectedOf(request: FetchRequest) = (request.bankAccounts as BankAccountSelection.Selected).bankAccounts.map { it.providerReference }

    private fun bankOf(request: FetchRequest, reference: String): String? = when {
        reference.startsWith("aurora") -> AURORA
        reference.startsWith("vesta") -> VESTA
        else -> null
    }.takeIf { it in request.sessions }

    private suspend fun fetch(confirmedUnattended: Boolean = false): FetchReport =
        (slice.fetch.fetch(key, confirmedUnattended = confirmedUnattended) as FetchStart.Ran).reports.single()

    private suspend fun lastResults(): List<StoredFetchResult> = slice.importStatusStore.fetchesOf(slice.openBankingId).first().results

    // ---- The Bank Sessions handed to the Fetch -------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-012"])
    @DisplayName("Steps 3-4 (S-B-47): the Fetch hands over the Bank Session of each Bank with a valid Bank Consent, by the Bank's reference, and selects only its Bank Accounts, with the Operator present")
    fun sessionsAreHandedOver() = runTest {
        grant(AURORA, "aurora-household")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        answering()

        val report = fetch()

        assertTrue(report is FetchReport.Fetched, "$report")
        val asked = provider.fetchRequests.single()
        assertEquals(setOf(AURORA), asked.sessions.keys)
        @OptIn(slussen.pluginapi.value.SensitiveApi::class)
        assertEquals("session-$AURORA", asked.sessions.getValue(AURORA).reveal())
        assertEquals(listOf("aurora-household"), selectedOf(asked))
        assertEquals(OperatorPresence.OperatorPresent, asked.presence)
        assertEquals(listOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 0)), lastResults())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008", "BR-010"])
    @DisplayName("A4, BR-008: a Bank Account whose Bank has no Bank Consent is left out without contacting its Bank, with the outcome Not Consented; the Bank Accounts of another Bank are read")
    fun bankWithoutConsentIsNotContacted() = runTest {
        grant(AURORA, "aurora-household")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        val vesta = add("vesta-savings", "Vesta Savings", VESTA, "Savings")
        answering()

        val report = fetch() as FetchReport.Fetched

        assertEquals(listOf("Vesta Savings"), report.missingConsent)
        assertEquals(setOf(AURORA), provider.fetchRequests.single().sessions.keys)
        assertEquals(listOf("aurora-household"), selectedOf(provider.fetchRequests.single()))
        assertEquals(
            setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 0), StoredFetchResult(vesta, FetchResultOutcome.NotConsented, 0)),
            lastResults().toSet(),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"])
    @DisplayName("A4: when no Bank Account is left to read, nothing is sent and the Fetch is recorded Not Consented; with none added, nothing is recorded")
    fun nothingLeftToRead() = runTest {
        assertEquals(FetchReport.NothingToRead(key, "Aurora Open Banking"), fetch())
        assertTrue(slice.importStatusStore.fetchesOf(slice.openBankingId).isEmpty())

        grant(AURORA, "aurora-household")
        add("vesta-savings", "Vesta Savings", VESTA, "Savings")
        val report = fetch() as FetchReport.NothingToRead

        assertEquals(listOf("Vesta Savings"), report.missingConsent)
        assertEquals(0, provider.fetches)
        assertEquals(FetchOutcome.NotConsented, slice.importStatusStore.fetchesOf(slice.openBankingId).first().outcome)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"], requirements = ["C-007"])
    @DisplayName("A4, BR-008: a Bank Consent that has expired, or that the Bank refused (A9), is no valid Bank Consent: its Bank is not contacted")
    fun expiredOrRefusedConsentIsNotUsed() = runTest {
        grant(AURORA, "aurora-household", until = now.plus(Duration.ofDays(1)))
        add("aurora-household", "Household Current", AURORA, "Current")
        slice.clock.advance(Duration.ofDays(2))
        assertTrue(fetch() is FetchReport.NothingToRead)

        grant(AURORA, "aurora-household")
        slice.consentStore.markRefused(bankId(AURORA), slice.openBankingId, now)
        assertEquals(listOf("Household Current"), (fetch() as FetchReport.NothingToRead).missingConsent)
        assertEquals(0, provider.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A10: Bank Account Not Identified With Certainty", businessRules = ["BR-008"])
    @DisplayName("A10 (E2 review item 4): a Bank Account its Bank's Bank Consent does not cover, shown as an unrecognised Bank Account, is not read, with the outcome Not Recognised")
    fun unrecognisedBankAccountIsNotRead() = runTest {
        grant(AURORA, "aurora-household")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        val gone = add("aurora-closed", "Closed Account", AURORA, "Card")
        answering()

        val report = fetch() as FetchReport.Fetched

        assertEquals(listOf("Closed Account"), report.notRecognised)
        assertEquals(listOf("aurora-household"), selectedOf(provider.fetchRequests.single()))
        assertEquals(
            setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 0), StoredFetchResult(gone, FetchResultOutcome.NotRecognised, 0)),
            lastResults().toSet(),
        )
    }

    // ---- What the Data Provider answered ------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004", "BR-010"])
    @DisplayName("A5 for one Bank, BR-004: a Bank that asks to wait gets a Back-off of that Bank only, for the default wait; another Bank's results are stored, and the next Fetch does not contact that Bank")
    fun bankAskingToWaitGetsItsOwnBackOff() = runTest {
        grant(AURORA, "aurora-household")
        grant(VESTA, "vesta-savings")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        val savings = add("vesta-savings", "Vesta Savings", VESTA, "Savings")
        provider.fetchAnswer = { request ->
            FetchResponse(
                SourceOutcome.Completed,
                listOf(BankOutcome.AskedToWait(AURORA, null)),
                listOf(
                    DeliveredBankAccount("aurora-household", AURORA, null, null, EUR, null, BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank)),
                    DeliveredBankAccount("vesta-savings", VESTA, null, null, EUR, null, BankAccountResult.Read(listOf(booked("2026-10-10", -5)), null)),
                ),
            )
        }

        val report = fetch() as FetchReport.Fetched

        val wait = slice.importStatusStore.backOffsOf(slice.openBankingId).single()
        assertEquals(bankId(AURORA), wait.bankId)
        assertEquals(BackOffCause.RequestToWait, wait.cause)
        assertEquals(now.plus(Duration.ofHours(slice.settings.current().defaultWaitHours.toLong())), wait.endsAt)
        assertEquals(listOf(BankWaiting(AURORA, wait.endsAt, listOf("Household Current"))), report.banksWaiting)
        assertEquals(listOf(StoredFetchResult(savings, FetchResultOutcome.Fetched, 1)), lastResults())
        assertTrue(slice.importStatusStore.bankTransactionsOf(household).isEmpty())
        assertEquals(1, slice.importStatusStore.bankTransactionsOf(savings).size)

        // After the Fetch Cooldown, within the Bank's Back-off: only the other Bank is contacted.
        slice.clock.advance(Duration.ofHours(7))
        answering()
        val later = fetch() as FetchReport.Fetched
        val next = provider.fetchRequests.last()
        assertEquals(2, provider.fetches)
        assertEquals(setOf(VESTA), next.sessions.keys)
        assertEquals(listOf("vesta-savings"), selectedOf(next))
        assertEquals(listOf("Household Current"), later.banksWaiting.single().bankAccounts)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("BR-004: the gate of such a Fetch refuses with scope Bank a request to a Bank in its Back-off, and passes the others to the Data Provider's gate")
    fun gateRefusesABankInItsBackOff() = runTest {
        grant(AURORA, "aurora-household")
        val aurora = bankId(AURORA)
        slice.importStatusStore.startBackOff(NewBackOff(slice.openBankingId, aurora, BackOffCause.RequestToWait, now, now.plus(Duration.ofHours(8))))
        val passed = mutableListOf<RequestSummary>()
        val gate = BankBackOffGate(RequestGate { passed += it; Admission.Allowed }, slice.openBankingId, mapOf(AURORA to aurora), slice.importStatusStore, slice.clock)

        assertEquals(
            Admission.Refused(RefusalScope.Bank, RefusalReason.BackOff),
            gate.admit(RequestSummary(RequestKind.Transactions, AURORA, "aurora-household", unattended = false)),
        )
        assertEquals(Admission.Allowed, gate.admit(RequestSummary(RequestKind.Transactions, VESTA, "vesta-savings", unattended = false)))
        assertEquals(listOf(VESTA), passed.map { it.bankReference })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A9: Bank Consent Refused Before Its Expiry", businessRules = ["BR-010"])
    @DisplayName("A9: a Bank that no longer accepts its Bank Consent has it treated as expired from now on, starts no Back-off, and its Bank Accounts keep their data with the outcome Not Consented; the next Fetch does not contact it")
    fun refusedConsentIsExpiredFromNowOn() = runTest {
        grant(AURORA, "aurora-household")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                listOf(BankOutcome.ConsentRefused(AURORA)),
                listOf(DeliveredBankAccount("aurora-household", AURORA, null, null, EUR, null, BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank))),
            )
        }

        val report = fetch() as FetchReport.Fetched

        assertEquals(listOf(AURORA), report.consentRefused)
        val stored = slice.consentStore.bankConsents().single()
        assertEquals(now, stored.refusedAt)
        assertTrue(stored.refusedByBank)
        assertTrue(slice.importStatusStore.backOffsOf(slice.openBankingId).isEmpty())
        assertEquals(listOf(StoredFetchResult(household, FetchResultOutcome.NotConsented, 0)), lastResults())

        val fetchesBefore = provider.fetches
        assertEquals(listOf("Household Current"), (fetch() as FetchReport.NothingToRead).missingConsent)
        assertEquals(fetchesBefore, provider.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("A8 (E3 review): a Bank Account the Data Provider could not read is Failed with its kind, shown to the Operator, and keeps its data; the others are stored")
    fun failureOfOneBankAccount() = runTest {
        grant(AURORA, "aurora-household", "aurora-savings")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        val savings = add("aurora-savings", "Aurora Savings", AURORA, "Savings")
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(
                    DeliveredBankAccount("aurora-household", AURORA, null, null, EUR, null, BankAccountResult.Failed(FailureKind.UnreadableAnswer)),
                    DeliveredBankAccount("aurora-savings", AURORA, null, null, EUR, null, BankAccountResult.Read(listOf(booked("2026-10-11", 20)), null)),
                ),
            )
        }

        val report = fetch() as FetchReport.Fetched

        assertEquals(listOf("Household Current"), report.failed)
        assertEquals(mapOf("Household Current" to FailureKind.UnreadableAnswer), report.failedWith)
        assertEquals(
            setOf(StoredFetchResult(household, FetchResultOutcome.Failed, 0), StoredFetchResult(savings, FetchResultOutcome.Fetched, 1)),
            lastResults().toSet(),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007", "BR-010"])
    @DisplayName("BR-007 (W6-0 item 5): when the gate stops the rest of the Fetch for the Request Limit, what was read before is stored, the rest keeps its data, and the report says the Request Limit stopped it")
    fun requestLimitStopsTheRestOnly() = runTest {
        grant(AURORA, "aurora-household", "aurora-savings")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        val savings = add("aurora-savings", "Aurora Savings", AURORA, "Savings")
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.StoppedByGate(RefusalReason.RequestLimitReached),
                emptyList(),
                listOf(
                    DeliveredBankAccount("aurora-household", AURORA, null, null, EUR, null, BankAccountResult.Read(listOf(booked("2026-10-11", 20)), null)),
                    DeliveredBankAccount("aurora-savings", AURORA, null, null, EUR, null, BankAccountResult.Failed(FailureKind.Incomplete)),
                ),
            )
        }

        val report = fetch() as FetchReport.Fetched

        assertEquals(RefusalReason.RequestLimitReached, report.stoppedByGate)
        assertEquals(FetchOutcome.Fetched, slice.importStatusStore.fetchesOf(slice.openBankingId).first().outcome)
        assertEquals(1, slice.importStatusStore.bankTransactionsOf(household).size)
        assertTrue(slice.importStatusStore.bankTransactionsOf(savings).isEmpty())
        assertEquals(
            setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1), StoredFetchResult(savings, FetchResultOutcome.Failed, 0)),
            lastResults().toSet(),
        )
    }

    // ---- A failure in transit (S-B-55) ----------------------------------------------------------------------------------

    private fun delivered(reference: String, bank: String, result: BankAccountResult) = DeliveredBankAccount(reference, bank, null, null, EUR, null, result)

    /** Aurora's household read completely; Vesta's two Bank Accounts got no answer. */
    private suspend fun threeBankAccounts(): Triple<Long, Long, Long> {
        grant(AURORA, "aurora-household")
        grant(VESTA, "vesta-savings", "vesta-current")
        return Triple(
            add("aurora-household", "Household Current", AURORA, "Current"),
            add("vesta-savings", "Vesta Savings", VESTA, "Savings"),
            add("vesta-current", "Vesta Current", VESTA, "Card"),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", businessRules = ["BR-005", "BR-006", "BR-010"])
    @DisplayName("A6 (S-B-55): what was read completely before a request got no answer is stored; the Bank Accounts not read are failed with that reason, told per Bank Account; the Fetch Cooldown starts because one was read")
    fun readBeforeAFailureInTransitIsStored() = runTest {
        val (household, savings, current) = threeBankAccounts()
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.TransientFailure(FailureKind.Timeout),
                emptyList(),
                listOf(
                    delivered("aurora-household", AURORA, BankAccountResult.Read(listOf(booked("2026-10-10", -5)), null)),
                    delivered("vesta-savings", VESTA, BankAccountResult.Failed(FailureKind.Timeout)),
                    delivered("vesta-current", VESTA, BankAccountResult.Failed(FailureKind.Timeout)),
                ),
            )
        }
        val requestsBefore = slice.providerRequests.requests.size

        val report = fetch() as FetchReport.Fetched

        assertEquals(1, slice.importStatusStore.bankTransactionsOf(household).size)
        assertEquals(mapOf("Vesta Savings" to FailureKind.Timeout, "Vesta Current" to FailureKind.Timeout), report.failedWith)
        assertEquals(FetchOutcome.Fetched, slice.importStatusStore.fetchesOf(slice.openBankingId).first().outcome)
        assertEquals(
            setOf(
                StoredFetchResult(household, FetchResultOutcome.Fetched, 1),
                StoredFetchResult(savings, FetchResultOutcome.Failed, 0),
                StoredFetchResult(current, FetchResultOutcome.Failed, 0),
            ),
            lastResults().toSet(),
        )
        assertEquals(requestsBefore + 1, slice.providerRequests.requests.size)
        slice.clock.advance(Duration.ofMinutes(5))
        assertTrue(fetch() is FetchReport.ServedFromFetchCooldown, "BR-006: a Fetch that read a Bank Account starts the Fetch Cooldown")
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A6: Data Provider Does Not Answer Usably", businessRules = ["BR-005", "BR-006"])
    @DisplayName("A6 (S-B-55): when nothing was read before the failure in transit, the Fetch is Failed, each Bank Account failed with its reason, and no Fetch Cooldown starts")
    fun failureInTransitBeforeAnyRead() = runTest {
        val (household, savings, current) = threeBankAccounts()
        provider.fetchAnswer = { request ->
            FetchResponse(
                SourceOutcome.TransientFailure(FailureKind.ConnectionLost),
                emptyList(),
                selectedOf(request).map { delivered(it, if (it.startsWith("aurora")) AURORA else VESTA, BankAccountResult.Failed(FailureKind.ConnectionLost)) },
            )
        }

        val report = fetch()

        assertEquals(FetchReport.Failed(key, "Aurora Open Banking", FailureKind.ConnectionLost, now), report)
        assertEquals(FetchOutcome.Failed, slice.importStatusStore.fetchesOf(slice.openBankingId).first().outcome)
        assertEquals(setOf(household, savings, current).map { StoredFetchResult(it, FetchResultOutcome.Failed, 0) }.toSet(), lastResults().toSet())
        answering()
        slice.clock.advance(Duration.ofMinutes(5))
        assertTrue(fetch() is FetchReport.Fetched)
        assertEquals(2, provider.fetches)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A8: Failure for One Bank Account", businessRules = ["BR-010"])
    @DisplayName("A8 (S-B-55): a Bank whose Bank Account fails with a code of its own leaves the next Bank's read stored")
    fun namedFailureLeavesTheNextBank() = runTest {
        val (household, savings, current) = threeBankAccounts()
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(
                    delivered("aurora-household", AURORA, BankAccountResult.Failed(FailureKind.PeriodNotGiven)),
                    delivered("vesta-savings", VESTA, BankAccountResult.Read(listOf(booked("2026-10-10", 7)), null)),
                    delivered("vesta-current", VESTA, BankAccountResult.Read(emptyList(), null)),
                ),
            )
        }

        val report = fetch() as FetchReport.Fetched

        assertEquals(mapOf("Household Current" to FailureKind.PeriodNotGiven), report.failedWith)
        assertEquals(
            setOf(
                StoredFetchResult(household, FetchResultOutcome.Failed, 0),
                StoredFetchResult(savings, FetchResultOutcome.Fetched, 1),
                StoredFetchResult(current, FetchResultOutcome.Fetched, 0),
            ),
            lastResults().toSet(),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A7: Data Provider Refuses Slussen's Credentials", businessRules = ["BR-010"])
    @DisplayName("A7 (S-B-55): what was read before the Data Provider refused the credentials is stored; the Fetch is recorded Credentials Refused and the credentials are marked refused")
    fun readBeforeCredentialsRefusedIsStored() = runTest {
        val (household, savings, _) = threeBankAccounts()
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.CredentialsRefused,
                emptyList(),
                listOf(
                    delivered("aurora-household", AURORA, BankAccountResult.Read(listOf(booked("2026-10-10", -5)), null)),
                    delivered("vesta-savings", VESTA, BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource)),
                    delivered("vesta-current", VESTA, BankAccountResult.NotRead(NotReadCause.StoppedWithTheSource)),
                ),
            )
        }

        assertEquals(FetchReport.CredentialsRefused(key, "Aurora Open Banking"), fetch())

        assertEquals(1, slice.importStatusStore.bankTransactionsOf(household).size)
        assertTrue(slice.importStatusStore.bankTransactionsOf(savings).isEmpty())
        assertEquals(FetchOutcome.CredentialsRefused, slice.importStatusStore.fetchesOf(slice.openBankingId).first().outcome)
        assertEquals(slussen.core.credential.CredentialStatus.Refused, slice.credentials.status(key))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5 (S-B-55): a Bank's Back-off counts from the time of the Bank's answer, not from the start of the Fetch")
    fun bankBackOffCountsFromTheAnswer() = runTest {
        grant(AURORA, "aurora-household")
        add("aurora-household", "Household Current", AURORA, "Current")
        val started = now
        provider.beforeAnswer = { slice.clock.advance(Duration.ofSeconds(40)) }
        provider.fetchAnswer = {
            FetchResponse(SourceOutcome.Completed, listOf(BankOutcome.AskedToWait(AURORA, null)), listOf(delivered("aurora-household", AURORA, BankAccountResult.NotRead(NotReadCause.StoppedWithItsBank))))
        }

        fetch()

        val wait = slice.importStatusStore.backOffsOf(slice.openBankingId).single()
        assertEquals(started.plusSeconds(40), wait.startsAt)
        assertEquals(started.plusSeconds(40).plus(Duration.ofHours(slice.settings.current().defaultWaitHours.toLong())), wait.endsAt)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A9: Bank Consent Refused Before Its Expiry", businessRules = ["BR-010"])
    @DisplayName("A5, A9: an outcome the Data Provider gives for a Bank the Fetch handed no Bank Session for changes nothing of that Bank")
    fun outcomeOfABankNotHandedIsIgnored() = runTest {
        grant(AURORA, "aurora-household")
        grant(VESTA, "vesta-savings")
        add("aurora-household", "Household Current", AURORA, "Current")
        provider.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                listOf(BankOutcome.AskedToWait(VESTA, null), BankOutcome.ConsentRefused(VESTA)),
                listOf(delivered("aurora-household", AURORA, BankAccountResult.Read(emptyList(), null))),
            )
        }

        fetch()

        assertTrue(slice.importStatusStore.backOffsOf(slice.openBankingId).isEmpty())
        assertTrue(slice.consentStore.bankConsents().none { it.refusedAt != null })
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A4: Bank Without Valid Bank Consent", businessRules = ["BR-008"])
    @DisplayName("S-B-55: with a Bank Consent granted and none of its Bank Accounts added, nothing is sent and the report says that a Bank Consent is granted")
    fun grantedButNoneAdded() = runTest {
        grant(AURORA, "aurora-household")
        assertEquals(FetchReport.NothingToRead(key, "Aurora Open Banking", bankConsentGranted = true), fetch())
        assertEquals(0, provider.fetches)
    }

    // ---- The Offered Bank Accounts and the Fetch Cooldown ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("ADR-001 §4.1 (S-B-42): the Fetch replaces no Offered Bank Account of a Data Provider that needs a Bank Consent")
    fun offeredBankAccountsAreKept() = runTest {
        grant(AURORA, "aurora-household", "aurora-savings")
        add("aurora-household", "Household Current", AURORA, "Current")
        val before = slice.offeredStore.offered()
        answering()

        fetch()

        assertEquals(before, slice.offeredStore.offered())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A2: Within the Fetch Cooldown", businessRules = ["BR-006"])
    @DisplayName("BR-006 (W6-0 item 1): a Fetch that read at least one Bank Account starts the Fetch Cooldown; one that read none does not")
    fun fetchCooldownStartsWhenABankAccountWasRead() = runTest {
        grant(AURORA, "aurora-household")
        add("aurora-household", "Household Current", AURORA, "Current")
        provider.fetchAnswer = {
            FetchResponse(SourceOutcome.Completed, emptyList(), listOf(DeliveredBankAccount("aurora-household", AURORA, null, null, EUR, null, BankAccountResult.Failed(FailureKind.ServiceUnavailable))))
        }
        fetch()
        slice.clock.advance(Duration.ofMinutes(5))
        answering()
        assertTrue(fetch() is FetchReport.Fetched, "a Fetch that read nothing starts no Fetch Cooldown")
        assertEquals(2, provider.fetches)

        slice.clock.advance(Duration.ofMinutes(5))
        assertTrue(fetch() is FetchReport.ServedFromFetchCooldown)
        assertEquals(2, provider.fetches)
    }

    // ---- A22 and attendance -----------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A22: before a Fetch the Operator starts at a Bank that counts it as unattended, Slussen says so with the Unattended Accesses each Bank Account used and asks; nothing is sent until he confirms")
    fun unattendedBankIsConfirmedFirst() = runTest {
        grant(AURORA, "aurora-household")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        consent.presence = { false }
        provider.showsPresence = false
        repeat(2) { slice.providerRequests.admitWithinLimit(NewProviderRequest(slice.openBankingId, household, ProviderRequestKind.Transactions, true, now.minus(Duration.ofHours(it + 1L))), now.minus(Duration.ofDays(1)), 50) }
        slice.providerRequests.admitWithinLimit(NewProviderRequest(slice.openBankingId, household, ProviderRequestKind.Other, true, now.minus(Duration.ofHours(1))), now.minus(Duration.ofDays(1)), 50)
        slice.providerRequests.admitWithinLimit(NewProviderRequest(slice.openBankingId, household, ProviderRequestKind.Balance, true, now.minus(Duration.ofHours(30))), now.minus(Duration.ofDays(2)), 50)
        answering()

        val asked = fetch()

        assertEquals(
            FetchReport.UnattendedToConfirm(key, "Aurora Open Banking", listOf(UnattendedBank(AURORA, listOf(UnattendedUse("Household Current", 2, 0)))), warningConfirmed = false),
            asked,
        )
        assertEquals(0, provider.fetches)

        assertTrue(fetch(confirmedUnattended = true) is FetchReport.Fetched)
        assertEquals(1, provider.fetches)
        // E3 review item 1, BR-012: the request sent without a sign of the Operator's presence is unattended and stored with its Bank Account.
        val sent = slice.providerRequests.requests.last()
        assertTrue(sent.unattended)
        assertEquals(household, sent.bankAccountId)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"])
    @DisplayName("A22: at a Bank whose requests show the Operator's presence, nothing is asked; a Scheduled Fetch never asks and reads unattended")
    fun noQuestionWhenPresenceIsShownOrScheduled() = runTest {
        grant(AURORA, "aurora-household")
        add("aurora-household", "Household Current", AURORA, "Current")
        answering()
        assertTrue(fetch() is FetchReport.Fetched)

        consent.presence = { false }
        slice.clock.advance(Duration.ofDays(1))
        val scheduled = slice.fetch.scheduledFetch() as ScheduledFetchOutcome.Ran
        assertTrue(scheduled.reports.single() is FetchReport.Fetched, "${scheduled.reports}")
        assertEquals(OperatorPresence.Unattended, provider.fetchRequests.last().presence)
        assertEquals(StartedBy.Schedule, slice.importStatusStore.fetchesOf(slice.openBankingId).first().startedBy)
    }

    // ---- The same history fetched twice ---------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("Q-05, ADR-008 §4: without a Stable Reference, the same history fetched twice over overlapping periods, also in another order, gives the same Import Identifiers and no second Bank Transaction; two identical transactions stay two")
    fun sameHistoryTwiceGivesNoSecondBankTransaction() = runTest {
        grant(AURORA, "aurora-household")
        val household = add("aurora-household", "Household Current", AURORA, "Current")
        val first = listOf(booked("2026-10-01", -4), booked("2026-10-01", -4), booked("2026-10-03", 900, payee = "Employer Oy", memo = null))
        answering(transactions = { first })
        fetch()
        val stored = slice.importStatusStore.bankTransactionsOf(household)
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.stableReference == null && it.referenceKind == StableReferenceKind.None }, "$stored")

        slice.clock.advance(Duration.ofDays(7))
        val second = listOf(booked("2026-10-03", 900, payee = "Employer Oy", memo = null), booked("2026-10-01", -4), booked("2026-10-08", -12), booked("2026-10-01", -4))
        answering(transactions = { second })
        assertTrue(fetch(confirmedUnattended = true) is FetchReport.Fetched)

        val after = slice.importStatusStore.bankTransactionsOf(household)
        assertEquals(4, after.size)
        assertEquals(stored.map { it.importIdentifier }.toSet(), after.map { it.importIdentifier }.toSet() - after.single { it.bookedDate == LocalDate.parse("2026-10-08") }.importIdentifier)
        assertEquals(after.size, after.map { it.importIdentifier }.toSet().size)
    }

    private companion object {
        const val AURORA = "Aurora Sparbank (FI)"
        const val VESTA = "Vesta Bank (SE)"
    }
}
