package slussen.core.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonStart
import slussen.core.consent.CompletionOutcome
import slussen.core.consent.ConsentAsked
import slussen.core.consent.ConsentNotEnded
import slussen.core.consent.ConsentToEnd
import slussen.core.consent.NotEndedBecause
import slussen.core.consent.StartOutcome
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StableReferenceKind
import slussen.core.port.StartKind
import slussen.core.port.StartedBy
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.TransactionStatus
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.Secret
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.ScriptedBankConsent
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * UC-010 A12 Start Over in the core (`slussen.core.settings.StartOver`), on the fakes of `:testing`: the statement from
 * stored data, the admission of ADR-007 §2, the ending of each Bank Consent at its Data Provider before anything is
 * recorded, and the order of ADR-002 §3 O-17 up to the process's end (the deletion itself is the start sequence's,
 * `StartOverStartSequenceTest` in `:persistence`). Every name, amount and identifier is invented (C-005).
 */
@DisplayName("UC-010: Adjust Settings, A12 Start Over")
class UC010StartOverTest {
    private val slice = TestSlice()
    private val consent = slice.bankConsent
    private val now: Instant get() = slice.clock.instant()
    private val key get() = slice.openBankingKey

    private val aurora = "Aurora Sparbank (FI)"
    private val nordvik = "Nordvik Bank (SE)"
    private val expiry: Instant = Instant.parse("2027-04-13T12:00:00Z")
    private var nextId = 7000L

    /** What the restart of the process received; the restart then holds, as a process that ends never returns. */
    private val restarted = CompletableDeferred<StartOverResult>()
    private val restart: suspend (StartOverResult) -> Nothing = { result ->
        restarted.complete(result)
        awaitCancellation()
    }

    @BeforeEach
    fun consentsGranted() = runTest {
        slice.credentials.enter(key, slice.input("setupKey" to ExampleData.SETUP_KEY))
        consent.startAnswer = { ConsentStart.Started("https://bank.example/sca", Secret("handle")) }
        grant(aurora, "PLANTED-SESSION-AURORA")
        grant(nordvik, "PLANTED-SESSION-NORDVIK")
    }

    private suspend fun grant(bank: String, session: String) {
        consent.completeAnswer = { _, _ -> ConsentCompletion.Completed(expiry, emptyList(), ConsentSession(session)) }
        slice.bankConsents.start(key, bank, browserAvailable = true) as StartOutcome.Started
        val answer = ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=c&state=${consent.stateTexts().last()}")
        assertTrue(slice.bankConsents.complete(answer) is CompletionOutcome.Granted)
    }

    private fun revokeAnswers(vararg answers: Pair<String, RevokeResult>) {
        val bySession = answers.toMap()
        consent.revokeAnswer = { session -> bySession.getValue(sessionBank(session)) }
    }

    @OptIn(slussen.pluginapi.value.SensitiveApi::class)
    private fun sessionBank(session: ConsentSession): String = if ("AURORA" in session.reveal()) aurora else nordvik

    private suspend fun bankId(name: String) = slice.connectionStore.banks().single { it.name == name }.id

    /** A Bank Account of Kestrel Feeds with [transactions] stored Bank Transactions. */
    private suspend fun bankAccountWith(transactions: Int): Long {
        val id = nextId++
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = slice.feedsId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = "Kestrel $id",
            currency = USD,
            type = BankAccountType.Checking,
            startDate = null,
            providerReference = "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = null,
        )
        repeat(transactions) { stored(id, "TX-$id-$it", LocalDate.of(2026, 10, 14), amount(-(it + 1).toLong())) }
        return id
    }

    private fun identifier(reference: String) =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    private fun stored(bankAccountId: Long, reference: String, date: LocalDate, amount: Amount): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += transaction(id, bankAccountId, reference, date, amount)
        return id
    }

    private fun transaction(id: Long, bankAccountId: Long, reference: String, date: LocalDate, amount: Amount) = StoredBankTransaction(
        id = id,
        bankAccountId = bankAccountId,
        stableReference = reference,
        referenceKind = StableReferenceKind.Supplied,
        occurrenceNumber = 1,
        importIdentifier = identifier(reference),
        bookedDate = date,
        amount = amount,
        currency = USD,
        payee = "PLANTED-PAYEE-$id",
        memo = null,
        firstSeenAt = now.minus(Duration.ofHours(1)),
        status = null,
        transferId = null,
    )

    /**
     * Runs Start Over in the background, as the process's own scope does, and returns once it restarted the process or
     * answered; the answer stays open while it holds the lane.
     */
    private suspend fun TestScope.startOverInBackground(): CompletableDeferred<StartOverOutcome> {
        val answered = CompletableDeferred<StartOverOutcome>()
        backgroundScope.launch { answered.complete(slice.startOver.startOver(restart = restart)) }
        select<Unit> {
            restarted.onAwait { }
            answered.onAwait { }
        }
        testScheduler.advanceUntilIdle()
        return answered
    }

    // ---- Step 1: the statement --------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 1: the statement counts the stored Bank Accounts and Bank Transactions and names each Bank whose Bank Consent is ended first, from stored data only")
    fun theStatementCountsFromStoredData() = runTest {
        bankAccountWith(transactions = 3)
        bankAccountWith(transactions = 2)
        val sent = consent.sent.size

        val statement = slice.startOver.statement()

        assertEquals(2, statement.bankAccounts)
        assertEquals(5, statement.bankTransactions)
        assertEquals(
            listOf(ConsentToEnd(aurora, ExampleData.openBankingProfile.name), ConsentToEnd(nordvik, ExampleData.openBankingProfile.name)),
            statement.consentsToEnd,
        )
        assertEquals(sent, consent.sent.size, "the statement contacts no Data Provider")
        assertFalse(slice.startOverStore.recorded)
    }

    // ---- Step 3: admission (ADR-007 §2) -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 3, ADR-007 §2: while another operation holds the lane (a Scheduled Fetch among them) Start Over is refused with what runs; nothing is asked or recorded")
    fun refusedWhileAnotherOperationRuns() = runTest {
        val held = CompletableDeferred<Unit>()
        val running = launch {
            slice.coordinator.runScheduledFetch { held.await() }
        }
        testScheduler.advanceUntilIdle()
        val sent = consent.sent.size

        val outcome = slice.startOver.startOver(restart = restart)

        val notNow = (outcome as StartOverOutcome.NotNow).why as StartOverUnavailable.AnotherOperation
        assertEquals(OperationKind.Fetch, notNow.running?.kind)
        assertEquals(StartedBy.Schedule, notNow.running?.startedBy)
        assertEquals(sent, consent.sent.size)
        assertFalse(slice.startOverStore.recorded)
        assertFalse(restarted.isCompleted)
        held.complete(Unit)
        running.join()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 3, UC-006 BR-005: while an Upload Plan awaits confirmation or an Upload is written, Start Over is refused with that reason")
    fun refusedWhileAnUploadPlanIsOpen() = runTest {
        listOf(CoordinatorMode.PlanOpen to OperationRefusal.UploadPlanAwaitingConfirmation, CoordinatorMode.Uploading to OperationRefusal.UploadBeingWritten).forEach { (mode, refusal) ->
            slice.coordinatorStore.mode = mode
            assertEquals(StartOverOutcome.NotNow(StartOverUnavailable.Paused(refusal)), slice.startOver.startOver(restart = restart))
        }
        assertTrue(consent.revoked.isEmpty())
        assertFalse(slice.startOverStore.recorded)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 3, ADR-007 §2: a Consent Request waiting for the Bank's result refuses Start Over, naming its Bank; once its lifetime has ended it no longer does")
    fun refusedWhileAConsentRequestWaits() = runTest {
        slice.bankConsents.start(key, "Waiting Bank (FI)", browserAvailable = true) as StartOutcome.Started

        assertEquals(
            StartOverOutcome.NotNow(StartOverUnavailable.ConsentRequestWaiting("Waiting Bank (FI)")),
            slice.startOver.startOver(restart = restart),
        )
        assertTrue(consent.revoked.isEmpty())

        slice.clock.advance(Duration.ofMinutes(16))
        val answered = startOverInBackground()
        assertTrue(restarted.isCompleted)
        assertFalse(answered.isCompleted)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 3, ADR-007 §2: a Journal Export whose file is being written refuses Start Over")
    fun refusedWhileAJournalExportIsWritten() = runTest {
        val writing = slice.startOverWith(exportUnderWay = { true })

        assertEquals(StartOverOutcome.NotNow(StartOverUnavailable.JournalExportBeingWritten), writing.startOver(restart = restart))
        assertTrue(consent.revoked.isEmpty())
        assertFalse(slice.startOverStore.recorded)
    }

    // ---- Step 4: each Bank Consent ended first ----------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4: every Bank Consent is asked to end through the gate, attended, before anything is recorded; one confirmed as ended is not named, one ended at the Data Provider only or not confirmed is")
    fun eachBankConsentIsEndedFirst() = runTest {
        revokeAnswers(aurora to RevokeResult.Ended, nordvik to RevokeResult.EndedAtDataProvider)
        var recordedAfterEnding = false
        slice.startOverStore.beforeRecord = { recordedAfterEnding = consent.revoked.size == 2 }
        val asked = consent.asked.size

        val answered = startOverInBackground()

        val result = restarted.await()
        assertEquals(listOf(ConsentNotEnded(nordvik, ExampleData.openBankingProfile.name, NotEndedBecause.EndedAtDataProviderOnly)), result.notEnded)
        assertEquals(result.notEnded, slice.startOverStore.recordedNotEnded, "the Banks are written with the record itself (ADR-002 §3 O-17 step 2)")
        assertTrue(recordedAfterEnding, "the obligation is recorded only after every Bank Consent was asked (ADR-002 §3 O-17 steps 1-2)")
        assertTrue(slice.startOverStore.recorded)
        val requests = consent.asked.drop(asked)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.kind == RequestKind.Other && !it.unattended }, "each ending goes through the gate with the Operator present")
        assertFalse(answered.isCompleted, "Start Over keeps the lane until its process ends")
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4: an end not confirmed, a Data Provider that cannot be reached or whose part fails, the gate holding it back and missing credentials do not stop Start Over; each Bank is named with why")
    fun failuresToEndDoNotStopStartOver() = runTest {
        consent.revokeAnswer = { throw IllegalStateException("invented failure of a plug-in") }
        slice.importStatusStore.backOffList += StoredBackOff(slice.openBankingId, bankId(nordvik), BackOffCause.RequestToWait, now, now.plus(Duration.ofHours(2)))

        startOverInBackground()

        assertEquals(
            listOf(
                ConsentNotEnded(aurora, ExampleData.openBankingProfile.name, NotEndedBecause.NotConfirmed),
                ConsentNotEnded(nordvik, ExampleData.openBankingProfile.name, NotEndedBecause.HeldBackByGate),
            ),
            restarted.await().notEnded,
        )
        assertTrue(slice.startOverStore.recorded)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4: without the Data Provider's credentials nothing is sent, and every Bank is named as not contacted")
    fun withoutCredentialsEveryBankIsNamed() = runTest {
        slice.credentials.remove(key)
        val sent = consent.sent.size

        startOverInBackground()

        assertEquals(
            listOf(NotEndedBecause.CredentialsMissing, NotEndedBecause.CredentialsMissing),
            restarted.await().notEnded.map { it.because },
        )
        assertEquals(sent, consent.sent.size)
        assertTrue(slice.startOverStore.recorded)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("A12 step 4: a Data Provider that answers that it did not end the consent is named as not confirmed")
    fun notConfirmedIsNamed() = runTest {
        revokeAnswers(aurora to RevokeResult.NotConfirmed, nordvik to RevokeResult.Ended)

        startOverInBackground()

        assertEquals(listOf(ConsentNotEnded(aurora, ExampleData.openBankingProfile.name, NotEndedBecause.NotConfirmed)), restarted.await().notEnded)
    }

    // ---- ADR-002 §3 O-17: what an ended step leaves -----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 2: when the obligation cannot be recorded, nothing is deleted, the process is not ended and the lane is free again; the Banks asked are said")
    fun anUnrecordedStartOverDeletesNothing() = runTest {
        slice.startOverStore.fails = true

        val outcome = slice.startOver.startOver(restart = restart)

        assertEquals(
            StartOverOutcome.NotRecorded(listOf(ConsentAsked(aurora, provider, null), ConsentAsked(nordvik, provider, null))),
            outcome,
        )
        assertFalse(restarted.isCompleted)
        assertNull(slice.coordinator.running.value)
        assertTrue(slice.coordinator.run(OperationKind.OperatorSettingChange, StartedBy.Operator) { Unit } is OperationResult.Ran)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 1: stored Bank Consents that cannot be read stop Start Over before anything is asked or recorded")
    fun unreadableConsentsStopStartOver() = runTest {
        slice.consentStore.failsToRead = true

        assertEquals(StartOverOutcome.StoredDataNotRead, slice.startOver.startOver(restart = restart))
        assertTrue(consent.revoked.isEmpty())
        assertFalse(slice.startOverStore.recorded)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over")
    @DisplayName("O-17 step 3, ADR-007 §2: once recorded, Start Over keeps the lane: an operation asked for meanwhile, a Scheduled Fetch that falls due included, waits and never runs on the data being deleted")
    fun nothingRunsAfterTheObligationIsRecorded() = runTest {
        startOverInBackground()
        assertTrue(slice.startOverStore.recorded)
        var fetched = false

        backgroundScope.launch { slice.coordinator.runScheduledFetch { fetched = true } }
        testScheduler.advanceUntilIdle()

        assertFalse(fetched)
        assertEquals(OperationKind.StartOver, slice.coordinator.running.value?.kind)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 3: when the app cannot be started again, the Operator is asked to open it again, and the lane stays held")
    fun aFailedRestartKeepsTheLane() = runTest {
        val toFinish = CompletableDeferred<StartOverResult>()
        val answered = CompletableDeferred<StartOverOutcome>()
        backgroundScope.launch {
            answered.complete(slice.startOver.startOver(restart = { error("invented: the app could not be started again") }, notRestarted = { toFinish.complete(it) }))
        }

        assertEquals(emptyList<Any>(), toFinish.await().notEnded)
        testScheduler.advanceUntilIdle()
        assertFalse(answered.isCompleted)
        assertEquals(OperationKind.StartOver, slice.coordinator.running.value?.kind)
    }

    private val provider = ExampleData.openBankingProfile.name

    @Test
    @UseCase(id = "UC-010", scenario = "A12: Start Over", requirements = ["NFR-023"])
    @DisplayName("O-17 step 1: stored data that cannot be read while the Bank Consents are being ended stops Start Over before anything is recorded, and the answer names each Bank already asked and how it went")
    fun aReadThatFailsWhileEndingNamesTheBanksAsked() = runTest {
        revokeAnswers(aurora to RevokeResult.Ended, nordvik to RevokeResult.Ended)
        val ended = consent.revokeAnswer
        consent.revokeAnswer = { session -> ended(session).also { slice.vault.failsToRead = true } }

        val outcome = slice.startOver.startOver(restart = restart)

        assertEquals(StartOverOutcome.NotReadWhileEnding(listOf(ConsentAsked(aurora, provider, null))), outcome)
        assertFalse(slice.startOverStore.recorded)
        assertFalse(restarted.isCompleted)
        assertNull(slice.coordinator.running.value)
    }
}
