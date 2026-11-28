package slussen.core.request

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.connection.BudgetsRead
import slussen.core.consent.CompletionOutcome
import slussen.core.fetch.AccessLimitHeld
import slussen.core.fetch.FetchReport
import slussen.core.fetch.FetchStart
import slussen.core.fetch.ScheduledFetchOutcome
import slussen.core.fetch.UnattendedBank
import slussen.core.fetch.UnattendedUse
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.NewBankAccount
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredFetchResult
import slussen.core.port.StoredProviderRequest
import slussen.core.status.Blocked
import slussen.core.status.BlockedReason
import slussen.core.status.Remedy
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
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
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ScriptedBankConsent
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The Unattended Access limit (UC-001 A13, BR-012; C-006, FR-079; ADR-001 §4.0), on the fakes of `:testing`, with a Data
 * Provider that reads Bank Account by Bank Account through the gate as the Enable Banking part does: the count at its
 * edge for the transactions and for the balance apart, the window moving, two Bank Accounts of one Bank, pages counted
 * as one read, the refusal of one read that leaves the other, the Operator's Fetch after a refused Scheduled Fetch (A22),
 * a failed Fetch still counted, a Data Provider without Bank Consents untouched, and the Blocked Reason (UC-008 BR-006).
 */
@DisplayName("UC-001: Fetch Transactions, the Unattended Access limit")
class UC001UnattendedAccessTest {
    private val slice = TestSlice()
    private val provider = slice.openBanking
    private val consent = slice.bankConsent
    private val key get() = slice.openBankingKey
    private val now: Instant get() = slice.clock.instant()

    /** Pages of each read of transactions the Data Provider answers with. */
    private var pages = 1

    /** True when the Data Provider gives no answer after the gate let out the requests of the first Bank Account. */
    private var noAnswer = false

    /** A gate the Data Provider's reader asks instead of the core's, or null for the core's own. */
    private var gateInstead: ((RequestGate) -> RequestGate)? = null

    /** Runs inside the Fetch, after the core chose what to read and before the first request. */
    private var beforeTheFirstRequest: () -> Unit = {}

    @BeforeEach
    fun ready() = runTest {
        slice.credentials.enter(slussen.core.port.ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
        slice.credentials.enter(key, slice.input("setupKey" to ExampleData.SETUP_KEY))
        consent.startAnswer = { ConsentStart.Started("https://aurora.example/sca", null) }
        grant(AURORA, "aurora-household", "aurora-savings")
        readingEachBankAccount()
    }

    private suspend fun grant(bank: String, vararg references: String) {
        consent.completeAnswer = { _, _ ->
            ConsentCompletion.Completed(
                Instant.parse("2027-04-13T12:00:00Z"),
                references.map { DeliveredBankAccount(it, bank, "Account $it", "current", EUR, null, BankAccountResult.NotRead(NotReadCause.NotSelected)) },
                ConsentSession("session-$bank"),
            )
        }
        slice.bankConsents.start(key, bank, browserAvailable = true)
        val granted = slice.bankConsents.complete(ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=c&state=${consent.stateTexts().last()}"))
        assertTrue(granted is CompletionOutcome.Granted, "$granted")
    }

    private suspend fun bankId(bank: String): Long = slice.connectionStore.bankNamed(bank).id

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun budgetOf(budgetAccount: String): Long =
        slice.connectionStore.budgets().single { budget -> budget.budgetAccounts.any { it.name == budgetAccount } }.id

    private suspend fun add(reference: String, name: String, budgetAccount: String, dataProviderId: Long = slice.openBankingId, aurora: Boolean = true): Long {
        val bank = if (aurora) bankId(AURORA) else null
        return (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-${reference.hashCode().toUInt().toString().padStart(12, '0').take(12)}",
                bank = if (bank == null) BankChoice.Named("Kestrel Bank") else BankChoice.Known(bank),
                providerBankId = bank,
                dataProviderId = dataProviderId,
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
    }

    private suspend fun household() = add("aurora-household", "Household Current", "Current")

    private suspend fun savings() = add("aurora-savings", "Aurora Savings", "Savings")

    /**
     * The Data Provider reads each selected Bank Account as ADR-001 §4.1.1 says: its balance when asked, one request of
     * kind Balance, then its transactions, the first page of kind Transactions and each further page of kind Other, each
     * request through the gate first; a refused balance gives no balance, a refused first page leaves the Bank Account
     * not read.
     */
    private fun readingEachBankAccount() {
        provider.readsEachBankAccount = { request, coreGate ->
            beforeTheFirstRequest()
            val gate = gateInstead?.invoke(coreGate) ?: coreGate
            val unattended = request.presence == OperatorPresence.Unattended || !provider.showsPresence
            val results = mutableListOf<DeliveredBankAccount>()
            var failed = false
            for (read in (request.bankAccounts as BankAccountSelection.Selected).bankAccounts) {
                val reference = read.providerReference
                val bank = AURORA.takeIf { it in request.sessions }
                fun summary(kind: RequestKind) = RequestSummary(kind, bank, reference, unattended)
                if (failed) {
                    results += DeliveredBankAccount(reference, bank, null, null, EUR, null, BankAccountResult.Failed(FailureKind.Timeout))
                    continue
                }
                var balance: Amount? = null
                if (read.read != ReadKind.Transactions && gate.admit(summary(RequestKind.Balance)) == Admission.Allowed) balance = BALANCE
                val result = when {
                    read.read == ReadKind.Balance -> BankAccountResult.Read(emptyList(), balance)
                    gate.admit(summary(RequestKind.Transactions)) != Admission.Allowed -> BankAccountResult.NotRead(NotReadCause.RefusedByGate, balance)
                    else -> {
                        repeat(pages - 1) { gate.admit(summary(RequestKind.Other)) }
                        if (noAnswer) BankAccountResult.Failed(FailureKind.Timeout) else BankAccountResult.Read(listOf(booked()), balance)
                    }
                }
                if (noAnswer) failed = true
                results += DeliveredBankAccount(reference, bank, null, null, EUR, null, result)
            }
            FetchResponse(if (failed) SourceOutcome.TransientFailure(FailureKind.Timeout) else SourceOutcome.Completed, emptyList(), results)
        }
    }

    private fun booked() = ProviderTransaction.Interpretable(null, LocalDate.parse("2026-10-11"), Amount(-12_000), EUR, "Harbor Pharmacy", null, booked = true)

    /** An unattended access of [kind] to [bankAccountId] through [dataProviderId], sent at [at], as the gate stored it. */
    private fun sent(bankAccountId: Long, kind: ProviderRequestKind, at: Instant, dataProviderId: Long = slice.openBankingId) {
        slice.providerRequests.requests += StoredProviderRequest(dataProviderId, bankAccountId, kind, true, at)
    }

    private fun sentHoursAgo(bankAccountId: Long, kind: ProviderRequestKind, vararg hours: Long) =
        hours.forEach { sent(bankAccountId, kind, now.minus(Duration.ofHours(it))) }

    /** When an access sent at [sentAt] has left the window, closed at its start (item 4 of the E6 review): one millisecond, the resolution of storage, after it is 24 hours old. */
    private fun freeAfter(sentAt: Instant): Instant = sentAt.plus(Duration.ofHours(24)).plusMillis(1)

    private suspend fun accesses() = UnattendedAccesses.of(slice.providerRequests, slice.openBankingId, now)

    private suspend fun scheduled(): FetchReport =
        (slice.fetch.scheduledFetch() as ScheduledFetchOutcome.Ran).reports.single { it.service == key }

    private suspend fun operatorFetch(confirmedUnattended: Boolean = false): FetchReport =
        (slice.fetch.fetch(key, confirmedUnattended = confirmedUnattended) as FetchStart.Ran).reports.single()

    private fun asked(): List<BankAccountToRead> = (provider.fetchRequests.last().bankAccounts as BankAccountSelection.Selected).bankAccounts

    private suspend fun lastResults(): Set<StoredFetchResult> = slice.importStatusStore.fetchesOf(slice.openBankingId).first().results.toSet()

    private suspend fun balanceOf(bankAccountId: Long) = slice.importStatusStore.accountBalances().singleOrNull { it.bankAccountId == bankAccountId }

    private suspend fun blockedOf(name: String): List<Blocked> {
        val budget = budgetOf(if (name == "Household Current") "Current" else "Savings")
        return slice.status.workspace(budget).bankAccounts.single { it.name == name }.blocked
    }

    // ---- The count at its edge ------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("BR-012, C-006: with 3 unattended reads of its transactions in the preceding 24 hours a Scheduled Fetch reads them, the 4th; with 4 it leaves them out without asking the Bank for them; with 5 too")
    fun transactionsBelowAtAndAbove() = runTest {
        val household = household()
        slice.providerSettings.changeFetchCooldown(key, 0)
        sentHoursAgo(household, ProviderRequestKind.Transactions, 10, 9, 8)

        assertTrue(scheduled() is FetchReport.Fetched)
        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults())

        slice.clock.advance(Duration.ofHours(1))
        val atLimit = scheduled() as FetchReport.Fetched
        assertEquals(listOf(BankAccountToRead("aurora-household", ReadKind.Balance)), asked())
        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0)), lastResults())
        assertEquals(
            listOf(AccessLimitHeld("Household Current", transactionsFreeAt = freeAfter(now.minus(Duration.ofHours(11))), balanceFreeAt = null)),
            atLimit.accessLimitReached,
        )

        sent(household, ProviderRequestKind.Transactions, now.minus(Duration.ofMinutes(5)))
        slice.clock.advance(Duration.ofMinutes(10))
        scheduled()
        assertEquals(listOf(BankAccountToRead("aurora-household", ReadKind.Balance)), asked(), "with 5 the transactions are left out too")
        assertEquals(5, accesses().used(household, ProviderRequestKind.Transactions))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("BR-012, C-006: the balance is counted apart: with 3 unattended reads of it a Scheduled Fetch reads it, with 4 it reads only the transactions, and stores no balance; with 5 too")
    fun balanceBelowAtAndAbove() = runTest {
        val household = household()
        slice.providerSettings.changeFetchCooldown(key, 0)
        sentHoursAgo(household, ProviderRequestKind.Balance, 10, 9, 8)

        scheduled()
        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(4, accesses().used(household, ProviderRequestKind.Balance))
        assertEquals(BALANCE, balanceOf(household)?.amount)

        slice.importStatusStore.balances.clear()
        slice.clock.advance(Duration.ofHours(1))
        val atLimit = scheduled() as FetchReport.Fetched
        assertEquals(listOf(BankAccountToRead("aurora-household", ReadKind.Transactions)), asked())
        assertNull(balanceOf(household), "no balance is asked for, so none is stored")
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults(), "its transactions were read")
        assertEquals(4, accesses().used(household, ProviderRequestKind.Balance))
        assertEquals(listOf(AccessLimitHeld("Household Current", null, freeAfter(now.minus(Duration.ofHours(11))))), atLimit.accessLimitReached)

        sent(household, ProviderRequestKind.Balance, now.minus(Duration.ofMinutes(5)))
        slice.clock.advance(Duration.ofMinutes(10))
        scheduled()
        assertEquals(listOf(BankAccountToRead("aurora-household", ReadKind.Transactions)), asked())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["NFR-018"])
    @DisplayName("BR-012, NFR-018: an access sent exactly 24 hours ago has left the window; the window moves, and when the oldest access leaves it the next Scheduled Fetch reads again, at the time the report named")
    fun windowMoves() = runTest {
        val household = household()
        sent(household, ProviderRequestKind.Transactions, now.minus(Duration.ofHours(24)).minusMillis(1))
        sentHoursAgo(household, ProviderRequestKind.Transactions, 23, 22, 21)
        assertEquals(3, accesses().used(household, ProviderRequestKind.Transactions), "one sent just before the window's start has left it")
        val atTheStart = now.minus(Duration.ofHours(24))
        sent(household, ProviderRequestKind.Transactions, atTheStart)
        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions), "one sent exactly 24 hours ago is counted: the window is closed at its start")

        val held = scheduled() as FetchReport.Fetched
        val freeAt = freeAfter(atTheStart)
        assertEquals(freeAt, held.accessLimitReached.single().transactionsFreeAt)

        slice.clock.set(freeAt.minusMillis(1))
        assertTrue(accesses().reached(household, ProviderRequestKind.Transactions), "at exactly 24 hours it is still counted")
        slice.clock.set(freeAt)
        assertFalse(accesses().reached(household, ProviderRequestKind.Transactions))
        scheduled()
        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A3: Request Limit Reached", businessRules = ["BR-007"], requirements = ["NFR-018"])
    @DisplayName("BR-007: the Request Limit's own window is unchanged: a request sent exactly 24 hours ago is not counted against it")
    fun requestLimitWindowUnchanged() = runTest {
        slice.providerSettings.changeRequestLimit(key, 10)
        slice.providerRequests.requests.clear()
        val stored = slice.plugins.dataProvider(key).stored
        slice.providerRequests.requests += StoredProviderRequest(slice.openBankingId, null, ProviderRequestKind.Other, false, now.minus(Duration.ofHours(24)))
        repeat(9) { slice.providerRequests.requests += StoredProviderRequest(slice.openBankingId, null, ProviderRequestKind.Other, false, now.minus(Duration.ofHours(1))) }
        val gate = DataProviderGate(stored, slice.providerRequests, slice.importStatusStore, slice.clock, OperationPermit(OperationKind.Fetch))

        assertEquals(Admission.Allowed, gate.admit(RequestSummary(RequestKind.Other, null, null, unattended = false)))
        assertEquals(
            Admission.Refused(RefusalScope.Source, RefusalReason.RequestLimitReached),
            gate.admit(RequestSummary(RequestKind.Other, null, null, unattended = false)),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A13: with five or six counted, access is free again when so many have left the window that fewer than the limit remain")
    fun freeAgainWithMoreThanTheLimit() = runTest {
        val household = household()
        val savings = savings()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 10, 9, 8, 7, 6)
        sentHoursAgo(savings, ProviderRequestKind.Balance, 12, 11, 10, 9, 8, 7)

        assertEquals(freeAfter(now.minus(Duration.ofHours(9))), accesses().freesUpAt(household, ProviderRequestKind.Transactions))
        assertEquals(freeAfter(now.minus(Duration.ofHours(10))), accesses().freesUpAt(savings, ProviderRequestKind.Balance))
        slice.clock.set(freeAfter(now.minus(Duration.ofHours(9))).minusMillis(1))
        assertTrue(accesses().reached(household, ProviderRequestKind.Transactions))
        slice.clock.advance(Duration.ofMillis(1))
        assertFalse(accesses().reached(household, ProviderRequestKind.Transactions))
    }

    // ---- What is refused, and what is still read ----------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-010", "BR-012"])
    @DisplayName("A13, BR-010: of two Bank Accounts of one Bank, the one at the limit for both reads is left out without contacting its Bank and records Access Limit Reached; the other is read in full")
    fun twoBankAccountsOfOneBank() = runTest {
        val household = household()
        val savings = savings()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 10, 9, 8, 7)
        sentHoursAgo(household, ProviderRequestKind.Balance, 10, 9, 8, 7)
        sentHoursAgo(savings, ProviderRequestKind.Transactions, 10, 9, 8)
        val before = slice.providerRequests.requests.size

        val report = scheduled() as FetchReport.Fetched

        assertEquals(listOf(BankAccountToRead("aurora-savings", ReadKind.TransactionsAndBalance)), asked())
        assertEquals(before + 2, slice.providerRequests.requests.size, "the balance and the transactions of the savings only")
        assertTrue(slice.providerRequests.requests.drop(before).all { it.bankAccountId == savings })
        assertEquals(
            setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0), StoredFetchResult(savings, FetchResultOutcome.Fetched, 1)),
            lastResults(),
        )
        val freeAt = freeAfter(now.minus(Duration.ofHours(10)))
        assertEquals(listOf(AccessLimitHeld("Household Current", freeAt, freeAt)), report.accessLimitReached)
        assertTrue(slice.importStatusStore.bankTransactionsOf(household).isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"])
    @DisplayName("A13: when the transactions of the only Bank Account are at the limit, its balance is still read and stored, and the Bank Account records Access Limit Reached with its transactions unchanged")
    fun transactionsRefusedBalanceRead() = runTest {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 10, 9, 8, 7)

        scheduled()

        assertEquals(listOf(BankAccountToRead("aurora-household", ReadKind.Balance)), asked())
        assertEquals(BALANCE, balanceOf(household)?.amount)
        assertTrue(slice.importStatusStore.bankTransactionsOf(household).isEmpty())
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0)), lastResults())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"])
    @DisplayName("A13 step 4: when no read of any Bank Account is left, the Data Provider is not contacted at all, each Bank Account records Access Limit Reached, and nothing is compared")
    fun nothingLeftToRead() = runTest {
        val household = household()
        val savings = savings()
        listOf(household, savings).forEach { id ->
            sentHoursAgo(id, ProviderRequestKind.Transactions, 6, 5, 4, 3)
            sentHoursAgo(id, ProviderRequestKind.Balance, 6, 5, 4, 3)
        }
        val before = slice.providerRequests.requests.size

        val outcome = slice.fetch.scheduledFetch() as ScheduledFetchOutcome.Ran
        val report = outcome.reports.single { it.service == key }

        assertEquals(0, provider.fetches)
        assertEquals(before, slice.providerRequests.requests.size)
        assertTrue(report is FetchReport.NothingToRead, "$report")
        assertEquals(listOf("Household Current", "Aurora Savings"), (report as FetchReport.NothingToRead).accessLimitReached.map { it.bankAccountName })
        assertEquals(
            setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0), StoredFetchResult(savings, FetchResultOutcome.AccessLimitReached, 0)),
            lastResults(),
        )
        assertEquals(FetchOutcome.AccessLimitReached, slice.importStatusStore.fetchesOf(slice.openBankingId).first().outcome)
        assertEquals(StartedBy.Schedule, slice.importStatusStore.fetchesOf(slice.openBankingId).first().startedBy)
        assertNull(outcome.comparison)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("ADR-001 §4.0: the gate of a Scheduled Fetch refuses a read at the limit with scope Bank Account before it is stored, and lets the other kind and further pages out; the gate of an Operator's Fetch refuses none")
    fun gateRefusesOnlyAScheduledRead() = runTest {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 4, 3, 2, 1)
        val stored = slice.plugins.dataProvider(key).stored
        fun gate(scheduled: Boolean) = DataProviderGate(
            stored, slice.providerRequests, slice.importStatusStore, slice.clock, OperationPermit(OperationKind.Fetch),
            FetchReads(mapOf("aurora-household" to household), scheduled),
        )
        fun summary(kind: RequestKind) = RequestSummary(kind, AURORA, "aurora-household", unattended = true)
        val before = slice.providerRequests.requests.size

        assertEquals(
            Admission.Refused(RefusalScope.BankAccount, RefusalReason.UnattendedAccessLimitReached),
            gate(scheduled = true).admit(summary(RequestKind.Transactions)),
        )
        assertEquals(before, slice.providerRequests.requests.size)
        assertEquals(Admission.Allowed, gate(scheduled = true).admit(summary(RequestKind.Balance)))
        assertEquals(Admission.Allowed, gate(scheduled = true).admit(summary(RequestKind.Other)))
        assertEquals(Admission.Allowed, gate(scheduled = false).admit(summary(RequestKind.Transactions)))
        assertEquals(5, accesses().used(household, ProviderRequestKind.Transactions), "the Operator's unattended read is counted all the same")
    }

    // ---- Pages, a failed Fetch ------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-007", "BR-012"])
    @DisplayName("BR-012 (W6-0 item 4): one read of the transactions is one Unattended Access however many pages it takes; every page counts against the Request Limit")
    fun pagesAreOneAccess() = runTest {
        val household = household()
        slice.providerSettings.changeFetchCooldown(key, 0)
        pages = 3

        repeat(4) {
            scheduled()
            slice.clock.advance(Duration.ofHours(1))
        }

        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(4, accesses().used(household, ProviderRequestKind.Balance))
        assertEquals(4 * (1 + 3), slice.providerRequests.requests.count { it.dataProviderId == slice.openBankingId && it.bankAccountId == household })
        scheduled()
        assertEquals(4, provider.fetches, "the 5th Scheduled Fetch leaves both reads out and contacts nothing")
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0)), lastResults())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("BR-012 (E5 review): the accesses a Scheduled Fetch spent are counted also when that Fetch then failed: after four that failed, the fifth leaves the Bank Account out")
    fun failedFetchStillCounted() = runTest {
        val household = household()
        noAnswer = true

        repeat(4) {
            assertTrue(scheduled() is FetchReport.Failed)
            slice.clock.advance(Duration.ofHours(1))
        }

        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(4, accesses().used(household, ProviderRequestKind.Balance))
        scheduled()
        assertEquals(4, provider.fetches)
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0)), lastResults())
    }

    // ---- The Operator's Fetch -------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("BR-012, A22: after a Scheduled Fetch left a Bank Account out, the Operator's Fetch is not refused; A22 shows the same count the limit uses, and his unattended reads are counted")
    fun operatorFetchAfterARefusedScheduledFetch() = runTest {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 6, 5, 4, 3)
        sentHoursAgo(household, ProviderRequestKind.Balance, 6, 5, 4, 3)
        scheduled()
        assertEquals(0, provider.fetches)
        consent.presence = { false }
        provider.showsPresence = false
        slice.clock.advance(Duration.ofMinutes(1))

        val question = operatorFetch()

        assertEquals(
            FetchReport.UnattendedToConfirm(key, "Aurora Open Banking", listOf(UnattendedBank(AURORA, listOf(UnattendedUse("Household Current", 4, 4)))), warningConfirmed = false),
            question,
        )
        val accesses = accesses()
        assertEquals(4, accesses.used(household, ProviderRequestKind.Transactions))
        assertEquals(4, accesses.used(household, ProviderRequestKind.Balance))

        val read = operatorFetch(confirmedUnattended = true)
        assertTrue(read is FetchReport.Fetched, "$read")
        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults())
        assertEquals(5, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(5, accesses().used(household, ProviderRequestKind.Balance))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A22: Fetch at a Bank That Counts It as Unattended", businessRules = ["BR-012"])
    @DisplayName("BR-012: at a Bank whose requests show the Operator's presence, his Fetch after a refused Scheduled Fetch asks nothing, reads both, and adds no Unattended Access")
    fun operatorFetchWithPresenceAddsNone() = runTest {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 6, 5, 4, 3)
        sentHoursAgo(household, ProviderRequestKind.Balance, 6, 5, 4, 3)
        scheduled()

        assertTrue(operatorFetch() is FetchReport.Fetched)
        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(4, accesses().used(household, ProviderRequestKind.Balance))
    }

    // ---- A Data Provider without Bank Consents --------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-012", "BR-013"])
    @DisplayName("BR-013: a Data Provider that needs no Bank Consent is read by a Scheduled Fetch however many unattended requests its Bank Account had, and its requests are not counted as Unattended Accesses")
    fun dataProviderWithoutBankConsentsIsUntouched() = runTest {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        val checking = add("acct-feeds", "Feeds Card", "Card", dataProviderId = slice.feedsId, aurora = false)
        repeat(6) { sent(checking, ProviderRequestKind.Transactions, now.minus(Duration.ofHours(it + 1L)), dataProviderId = slice.feedsId) }
        slice.feeds.fetchAnswer = { request ->
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                (request.bankAccounts as BankAccountSelection.Selected).bankAccounts.map {
                    DeliveredBankAccount(it.providerReference, null, null, null, EUR, null, BankAccountResult.Read(listOf(booked()), null))
                },
            )
        }

        val outcome = slice.fetch.scheduledFetch() as ScheduledFetchOutcome.Ran

        assertTrue(outcome.reports.single { it.service == slice.feedsKey } is FetchReport.Fetched)
        assertEquals(1, slice.feeds.fetches)
        assertEquals(0, UnattendedAccesses.of(slice.providerRequests, slice.openBankingId, now).used(checking, ProviderRequestKind.Transactions))
        // E6 review item 5: its own unattended rows reach the count all the same, and nothing refused it, since it needs no Bank Consent.
        assertTrue(UnattendedAccesses.of(slice.providerRequests, slice.feedsId, now).reached(checking, ProviderRequestKind.Transactions))
        assertEquals(setOf(StoredFetchResult(checking, FetchResultOutcome.Fetched, 1)), slice.importStatusStore.fetchesOf(slice.feedsId).first().results.toSet())
    }

    // ---- E6 review, items 1, 3 and 5 ------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"])
    @DisplayName("A13 step 4 (E6 review item 1): a Scheduled Fetch the limit left out entirely shows the Source Health Access Limit Reached and its recent Fetch waits on the access limit, never on a Back-off")
    fun nothingLeftShowsNoBackOff() = runTest {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 6, 5, 4, 3)
        sentHoursAgo(household, ProviderRequestKind.Balance, 6, 5, 4, 3)
        scheduled()

        val workspace = slice.status.workspace(budgetOf("Current"))
        val source = workspace.sources.single { it.name == "Aurora Open Banking" }
        assertEquals(slussen.core.status.SourceHealth.FetchHealth(FetchOutcome.AccessLimitReached, now, StartedBy.Schedule), source.health)
        val everyBlocked = workspace.sources.flatMap { it.blocked } + workspace.bankAccounts.flatMap { it.blocked }
        assertTrue(everyBlocked.none { it.reason == BlockedReason.WaitingForProvider }, "$everyBlocked")
        assertTrue(slice.importStatusStore.backOffs().isEmpty())
        val recent = slice.status.recentFetches().fetches.first()
        val limit = blockedOf("Household Current").single { it.reason == BlockedReason.AccessLimitReached }
        assertEquals(slussen.core.status.FetchNeed.NotNeeded(slussen.core.status.FetchNote.WaitingOn(listOf(limit))), recent.need)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("ADR-001 §4.0: the gate of a Scheduled Fetch lets the read out while one below the limit is counted, and refuses the next")
    fun gateAllowsOneBelow() = runTest {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 4, 3, 2)
        val gate = DataProviderGate(
            slice.plugins.dataProvider(key).stored, slice.providerRequests, slice.importStatusStore, slice.clock, OperationPermit(OperationKind.Fetch),
            FetchReads(mapOf("aurora-household" to household), scheduled = true, countedAt = now),
        )
        val summary = RequestSummary(RequestKind.Transactions, AURORA, "aurora-household", unattended = true)

        assertEquals(Admission.Allowed, gate.admit(summary))
        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions))
        assertEquals(Admission.Refused(RefusalScope.BankAccount, RefusalReason.UnattendedAccessLimitReached), gate.admit(summary))
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"])
    @DisplayName("E6 review item 3: the core and the gate count at the same time, the start of the Fetch: with the clock set back between the core's choice and the first request, the read the core chose is not refused")
    fun clockSetBackDuringTheFetch() = runTest {
        val household = household()
        sent(household, ProviderRequestKind.Transactions, now.minus(Duration.ofHours(24)).minusMillis(1))
        sentHoursAgo(household, ProviderRequestKind.Transactions, 23, 22, 21)
        beforeTheFirstRequest = { slice.clock.advance(Duration.ofHours(-1)) }

        scheduled()

        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults())
        assertEquals(1, slice.importStatusStore.bankTransactionsOf(household).size)
    }

    /** A gate that refuses the reads of [kind] for the Unattended Access limit and passes the rest to [core]. */
    private fun refusing(kind: RequestKind): (RequestGate) -> RequestGate = { core ->
        RequestGate { if (it.kind == kind) Admission.Refused(RefusalScope.BankAccount, RefusalReason.UnattendedAccessLimitReached) else core.admit(it) }
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"])
    @DisplayName("E6 review item 3: when the gate refuses the balance read for the limit, the transactions are read and stored and the Fetch Result is Fetched; when it refuses the transactions, the balance read is stored and the result is Access Limit Reached")
    fun gateRefusalLeavesTheOtherRead() = runTest {
        val household = household()
        slice.providerSettings.changeFetchCooldown(key, 0)
        gateInstead = refusing(RequestKind.Balance)

        scheduled()

        assertEquals(1, slice.importStatusStore.bankTransactionsOf(household).size)
        assertNull(balanceOf(household))
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults())

        gateInstead = refusing(RequestKind.Transactions)
        slice.clock.advance(Duration.ofHours(1))
        scheduled()

        assertEquals(BALANCE, balanceOf(household)?.amount)
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.AccessLimitReached, 0)), lastResults())
    }

    /** The household at the limit for both reads, at a Bank whose requests show the Operator's presence unless [presence] is false. */
    private suspend fun householdAtTheLimit(presence: Boolean = true): Long {
        val household = household()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 6, 5, 4, 3)
        sentHoursAgo(household, ProviderRequestKind.Balance, 6, 5, 4, 3)
        consent.presence = { presence }
        provider.showsPresence = presence
        return household
    }

    private suspend fun assertReadInFull(report: FetchReport, household: Long) {
        assertTrue(report is FetchReport.Fetched, "$report")
        assertEquals(ReadKind.TransactionsAndBalance, asked().single().read)
        assertEquals(setOf(StoredFetchResult(household, FetchResultOutcome.Fetched, 1)), lastResults())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A11: Fetch of Several Data Providers", businessRules = ["BR-012"])
    @DisplayName("BR-012 (E6 review item 5): the Operator's Fetch of all Data Providers and of one Budget is never refused by the count with both reads at the limit")
    fun operatorNeverRefusedThroughAllOrOneBudget() = runTest {
        val household = householdAtTheLimit()

        val all = (slice.fetch.fetchAll() as FetchStart.Ran).reports.single { it.service == key }
        assertReadInFull(all, household)

        slice.clock.advance(Duration.ofHours(7))
        val budget = (slice.fetch.fetchBudget(budgetOf("Current")) as FetchStart.Ran).reports.single { it.service == key }
        assertReadInFull(budget, household)
        assertEquals(4, accesses().used(household, ProviderRequestKind.Transactions), "reads that showed his presence add none")
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A16: Operator Fetches Despite a Request Limit Warning", businessRules = ["BR-012", "BR-015"])
    @DisplayName("BR-012 (E6 review item 5): after confirming a Request Limit warning (A16) and the question of A22, the Operator's Fetch of one Data Provider is not refused by the count")
    fun operatorNeverRefusedAfterTheConfirmations() = runTest {
        val household = householdAtTheLimit(presence = false)
        slice.importStatusStore.startBackOff(
            slussen.core.port.NewBackOff(slice.openBankingId, null, slussen.core.port.BackOffCause.RequestLimitWarning, now, now.plus(Duration.ofHours(2))),
        )

        assertTrue(operatorFetch() is FetchReport.WarningToConfirm)
        val asked = (slice.fetch.fetch(key, confirmedDespiteWarning = true) as FetchStart.Ran).reports.single()
        assertTrue(asked is FetchReport.UnattendedToConfirm && asked.warningConfirmed, "$asked")
        val read = (slice.fetch.fetch(key, confirmedDespiteWarning = true, confirmedUnattended = true) as FetchStart.Ran).reports.single()

        assertReadInFull(read, household)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("BR-012 (E6 review item 5): the Operator's Fetch of one Data Provider at a Bank that shows his presence is not refused with both reads at the limit")
    fun operatorNeverRefusedThroughOne() = runTest {
        val household = householdAtTheLimit()

        assertReadInFull(operatorFetch(), household)
    }

    // ---- What the import status shows (UC-008 BR-006) -------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-001", scenario = "A13: Unattended Access Limit Reached", businessRules = ["BR-012"], requirements = ["C-006"])
    @DisplayName("A13 step 2, UC-008 BR-006: while a Bank Account's access is not free, the Blocked Reason access limit reached is shown with the time it frees up, also when only its balance is held back; at that time it goes")
    fun blockedWhileNotFree() = runTest {
        val household = household()
        val savings = savings()
        sentHoursAgo(household, ProviderRequestKind.Transactions, 10, 9, 8, 7)
        sentHoursAgo(savings, ProviderRequestKind.Balance, 12, 11, 10, 9)
        scheduled()

        val householdFree = freeAfter(now.minus(Duration.ofHours(10)))
        val savingsFree = freeAfter(now.minus(Duration.ofHours(12)))
        assertTrue(
            Blocked(BlockedReason.AccessLimitReached, Remedy.WaitUntilItEnds, householdFree, "Household Current", accessLimit = AccessLimitHeld("Household Current", householdFree, null))
                in blockedOf("Household Current"),
        )
        assertTrue(
            Blocked(BlockedReason.AccessLimitReached, Remedy.WaitUntilItEnds, savingsFree, "Aurora Savings", accessLimit = AccessLimitHeld("Aurora Savings", null, savingsFree))
                in blockedOf("Aurora Savings"),
        )
        assertEquals(FetchResultOutcome.Fetched, lastResults().single { it.bankAccountId == savings }.outcome, "A13 step 3: only its balance waited")

        slice.clock.set(savingsFree.minusMillis(1))
        assertTrue(blockedOf("Aurora Savings").any { it.reason == BlockedReason.AccessLimitReached })
        slice.clock.set(savingsFree)
        assertTrue(blockedOf("Aurora Savings").none { it.reason == BlockedReason.AccessLimitReached })
        assertTrue(blockedOf("Household Current").any { it.reason == BlockedReason.AccessLimitReached })
        slice.clock.set(householdFree)
        assertTrue(blockedOf("Household Current").none { it.reason == BlockedReason.AccessLimitReached })
    }

    private companion object {
        const val AURORA = "Aurora Sparbank (FI)"
        val BALANCE = Amount(1_234_560)
    }
}
