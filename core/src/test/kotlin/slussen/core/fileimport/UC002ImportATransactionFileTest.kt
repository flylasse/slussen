package slussen.core.fileimport

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import slussen.core.comparison.BudgetReadOutcome
import slussen.core.comparison.ComparisonReport
import slussen.core.connection.BudgetsRead
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.RunningOperation
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.NewBankAccount
import slussen.core.port.ReviewChoice
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredReviewDecision
import slussen.core.port.StoredTransactionFile
import slussen.core.port.TransactionFileOutcome
import slussen.core.port.TransactionStatus
import slussen.core.status.SourceHealth
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.ReferenceKind
import slussen.pluginapi.fileformat.StandardImportFile
import slussen.pluginapi.fileformat.StandardImportTransaction
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.TestSlice
import java.time.Duration
import java.time.LocalDate

/**
 * Import a Transaction File (UC-002) in the core, on the fakes of `:testing` (the Integration layer of
 * `docs/architecture/stack.md`), through a fake File Pre-processor that each test fills: steps 4-10, A2-A6 and BR-001 to
 * BR-007. The counting of a file against the stored Bank Transactions (BR-003, survey D risk R1) has one test per case,
 * each asserting the Import Identifier and Stable Reference of every stored Bank Transaction before and after. A1 (the
 * Operator cancels the choice) and A7 (the Bank Account is added first) are the screen's (phase D4): the core is called
 * only with a chosen file and an added Bank Account.
 */
@DisplayName("UC-002: Import a Transaction File")
class UC002ImportATransactionFileTest {
    private val slice = TestSlice()
    private val sourceKey = "example-format"
    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    // ---- Set-up --------------------------------------------------------------------------------------------------

    /** The credentials of the Budget App are accepted and every Budget is served. */
    private suspend fun ready() {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
    }

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    /** A Bank Account whose transactions come from Transaction Files of the fake File Format (UC-013 A4). */
    private suspend fun fileBankAccount(name: String = "Harbor Health Savings", budgetAccount: String = "Health Savings", suffix: String = "f11e"): Long = (
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-00000000$suffix",
                bank = BankChoice.Named("Harbor Health Trust"),
                providerBankId = null,
                dataProviderId = null,
                fileFormatId = slice.plugins.fileFormats.single().id,
                balanceDataProviderId = null,
                balanceProviderReference = null,
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

    /**
     * A line of the Standard Import Format. Its Derived reference is made from its date, amount, payee and memo, as the
     * HSA CSV export's recipe makes it (ADR-001 §4.3.1): two lines have the same reference exactly when they are identical.
     */
    private fun line(
        date: LocalDate,
        amount: Long,
        payee: String? = "DEBIT CARD PURCHASE HARBOR PHARMACY",
        memo: String? = null,
        reference: String? = null,
        kind: ReferenceKind = ReferenceKind.Derived,
        currency: CurrencyCode? = USD,
        booked: Boolean = true,
    ) = StandardImportTransaction(reference ?: "ref:$date:$amount:${payee.orEmpty()}:${memo.orEmpty()}", kind, date, Amount(amount), currency, payee, memo, booked)

    /** The fake File Pre-processor converts the next file into [lines]. */
    private fun converts(vararg lines: StandardImportTransaction) {
        slice.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(lines.toList())) }
    }

    private suspend fun import(bankAccountId: Long, vararg lines: StandardImportTransaction): FileImportOutcome {
        converts(*lines)
        slice.clock.advance(Duration.ofMinutes(5))
        return slice.fileImport.import(bankAccountId, "an invented file".toByteArray())
    }

    /** What must never change of a stored Bank Transaction (ADR-008 §4, UC-002 BR-002): its identity, as stored. */
    private data class Identity(
        val id: Long,
        val bankAccountId: Long,
        val importIdentifier: String,
        val stableReference: String?,
        val referenceKind: StableReferenceKind,
        val occurrenceNumber: Int,
    )

    /** The identity of every stored Bank Transaction of every Bank Account, in the order stored. */
    private fun identities(): List<Identity> = slice.importStatusStore.bankTransactions.map {
        Identity(it.id, it.bankAccountId, it.importIdentifier, it.stableReference, it.referenceKind, it.occurrenceNumber)
    }

    private fun r(reference: String) = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference(sourceKey, reference))

    private fun f(t: StandardImportTransaction, occurrence: Int) = ImportIdentifierProtocol.importIdentifier(
        ImportIdentifierInput.WithoutStableReference(sourceKey, t.bookedDate, t.amount, t.payee, t.memo, occurrence),
    )

    /** The Transaction Files stored for [bankAccountId], in the order stored. */
    private fun filesOf(bankAccountId: Long): List<StoredTransactionFile> = slice.importStatusStore.files.filter { it.bankAccountId == bankAccountId }

    private fun firstTimeEntries(): List<JournalEntry> = slice.journalRecorder.entries.filter { it.eventKind == JournalEventKind.TransactionSeen }

    private fun imported(outcome: FileImportOutcome): FileImportOutcome.Imported = assertInstanceOf(FileImportOutcome.Imported::class.java, outcome)

    private fun rejected(outcome: FileImportOutcome): FileImportOutcome.Rejected = assertInstanceOf(FileImportOutcome.Rejected::class.java, outcome)

    // ---- Main Success Scenario -----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-007"], requirements = ["NFR-020"])
    @DisplayName("Steps 4-8, BR-002, BR-007: the whole file is converted by the File Pre-processor of the Bank Account's File Format; each transaction is stored with its reference as its Stable Reference, recorded as Derived, its Import Identifier from the Source Key and that reference, occurrence_number 1, the time of the import and no Transaction Status before the Comparison; the file is recorded as imported with the number it delivered")
    fun transactionsOfTheFileAreStored() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)
        val pharmacy = line(oct14, -12_340)
        val contribution = line(oct12, 150_000, payee = "PARTIC CONTR CURRENT PARTICIPANT CUR YR")
        val supplied = line(oct10, 4_500, payee = "DIVIDEND RECEIVED", memo = "(EXMPX)", reference = "BANK-REF-7", kind = ReferenceKind.Supplied)

        val outcome = imported(import(harbor, pharmacy, contribution, supplied))
        val importedAt = slice.clock.instant()

        assertEquals(1, slice.fileFormat.files.size)
        assertEquals("an invented file", String(slice.fileFormat.files.single()))
        val stored = slice.importStatusStore.bankTransactionsOf(harbor)
        assertEquals(listOf(pharmacy.reference, contribution.reference, "BANK-REF-7"), stored.map { it.stableReference })
        assertEquals(listOf(StableReferenceKind.Derived, StableReferenceKind.Derived, StableReferenceKind.Supplied), stored.map { it.referenceKind })
        assertEquals(listOf(r(pharmacy.reference), r(contribution.reference), r("BANK-REF-7")), stored.map { it.importIdentifier })
        assertTrue(stored.all { it.occurrenceNumber == 1 && it.firstSeenAt == importedAt && it.status == null && it.transferId == null })
        assertEquals(listOf(oct14, oct12, oct10), stored.map { it.bookedDate })
        assertEquals(listOf(Amount(-12_340), Amount(150_000), Amount(4_500)), stored.map { it.amount })
        assertTrue(stored.all { it.currency == USD })
        assertEquals(listOf("DEBIT CARD PURCHASE HARBOR PHARMACY", "PARTIC CONTR CURRENT PARTICIPANT CUR YR", "DIVIDEND RECEIVED"), stored.map { it.payee })
        assertEquals(listOf(null, null, "(EXMPX)"), stored.map { it.memo })
        assertEquals(listOf(StoredTransactionFile(harbor, importedAt, TransactionFileOutcome.Imported, transactionCount = 3)), filesOf(harbor))
        assertEquals("Harbor Health Savings", outcome.bankAccountName)
        assertEquals(3, outcome.delivered)
        assertEquals(stored.map { it.importIdentifier }, outcome.takenIn.map { it.importIdentifier })
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", requirements = ["NFR-002"])
    @DisplayName("Step 5: only the booked transactions with an amount other than zero are kept; a pending one and one of zero are left out and not counted as delivered")
    fun onlyBookedNonZeroTransactionsAreKept() = runTest {
        ready()
        val harbor = fileBankAccount()

        val outcome = imported(import(harbor, line(oct14, -12_340), line(oct14, -2_000, booked = false), line(oct12, 0), line(oct10, 1_200, payee = "INTEREST EARNED")))

        assertEquals(listOf(Amount(-12_340), Amount(1_200)), slice.importStatusStore.bankTransactionsOf(harbor).map { it.amount })
        assertEquals(2, outcome.delivered)
        assertEquals(2, filesOf(harbor).single().transactionCount)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Steps 8-10, BR-006: each Bank Transaction taken in for the first time is recorded in the Decision Journal with its Bank Account and Import Identifier; the Comparison follows in the same operation and gives each its Transaction Status, which the result shows with the number taken in; the import is the Source Health and Freshness of the Bank Account")
    fun importIsJournaledComparedAndShown() = runTest {
        ready()
        val harbor = fileBankAccount()
        val pharmacy = line(oct14, -12_340)
        val clinic = line(oct12, -25_000, payee = "DISTRIBUTION EXAMPLE CLINIC", memo = "Office visit")
        val readsBefore = slice.budgetApp.reader.budgetReads

        val outcome = imported(import(harbor, pharmacy, clinic))
        val importedAt = slice.clock.instant()

        val seen = firstTimeEntries()
        assertEquals(listOf(r(pharmacy.reference), r(clinic.reference)), seen.map { it.importIdentifier })
        assertTrue(seen.all { it.bankAccountName == "Harbor Health Savings" })
        assertEquals("Bank Transaction received for the first time: booked 2026-10-14, -12.340 USD", seen.first().details)
        assertFalse(seen.any { "PHARMACY" in it.details || "Office visit" in it.details }, "NFR-012: no payee or memo in the Decision Journal")
        assertEquals(readsBefore + 1, slice.budgetApp.reader.budgetReads)
        assertInstanceOf(ComparisonReport.Compared::class.java, outcome.comparison)
        assertFalse(outcome.comparisonFailed)
        assertEquals(listOf(TransactionStatus.New, TransactionStatus.New), outcome.takenIn.map { it.status })
        assertEquals(listOf(TransactionStatus.New, TransactionStatus.New), slice.importStatusStore.bankTransactionsOf(harbor).map { it.status })
        assertEquals(listOf(oct14, oct12), outcome.takenIn.map { it.bookedDate })
        assertEquals(listOf(Amount(-12_340), Amount(-25_000)), outcome.takenIn.map { it.amount })
        val workspace = slice.status.workspace(slice.connectionStore.budgets().single { it.name == "Care Fund" }.id)
        val account = workspace.bankAccounts.single()
        assertEquals(SourceHealth.FileImportHealth(TransactionFileOutcome.Imported, importedAt, delivered = 2), account.health)
        assertEquals(importedAt, account.freshness)
        assertEquals(importedAt, outcome.importedAt)
        assertEquals(listOf("DEBIT CARD PURCHASE HARBOR PHARMACY", "DISTRIBUTION EXAMPLE CLINIC"), outcome.takenIn.map { it.payee })
        assertEquals(listOf<IdenticalPosition?>(null, null), outcome.takenIn.map { it.identical })
        val source = workspace.sources.single { it.isFileFormat }
        assertEquals(slice.plugins.fileFormats.single().id, source.fileFormatId)
        assertEquals(slice.plugins.fileFormats.single().id, account.fileFormatId)
        assertEquals(SourceHealth.FileImportHealth(TransactionFileOutcome.Imported, importedAt, delivered = 2), source.health)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("BR-005: a Transaction File brings in no Account Balance; nothing is written to the Budget App and no Data Provider is contacted")
    fun aFileBringsNoBalance() = runTest {
        ready()
        val harbor = fileBankAccount()
        val balancesBefore = slice.importStatusStore.accountBalances()

        imported(import(harbor, line(oct14, -12_340)))

        assertEquals(balancesBefore, slice.importStatusStore.accountBalances())
        assertEquals(0, slice.budgetApp.writer.calls)
        assertEquals(0, slice.feeds.fetches + slice.openBanking.fetches + slice.wren.fetches)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Step 9 (UC-003 A1): when the Budget App cannot be read after the import, the import stays stored and its new Bank Transactions are not compared yet")
    fun anUnreadBudgetAppKeepsTheImport() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)

        val outcome = imported(import(harbor, line(oct14, -12_340)))

        val compared = assertInstanceOf(ComparisonReport.Compared::class.java, outcome.comparison)
        assertTrue(compared.budgets.all { it is BudgetReadOutcome.NotRead })
        assertFalse(outcome.comparisonFailed)
        assertEquals(listOf<TransactionStatus?>(null), outcome.takenIn.map { it.status })
        assertEquals(1, slice.importStatusStore.bankTransactionsOf(harbor).size)
        assertEquals(TransactionFileOutcome.Imported, filesOf(harbor).single().outcome)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Step 9: a Comparison that fails after the import, as a storage that stops before its conclusions would, keeps the import; the result says that it was not compared")
    fun aFailedComparisonKeepsTheImport() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.copyStore.failsToConclude = true

        val outcome = imported(import(harbor, line(oct14, -12_340)))

        assertTrue(outcome.comparisonFailed)
        assertNull(outcome.comparison)
        assertEquals(listOf<TransactionStatus?>(null), outcome.takenIn.map { it.status })
        assertEquals(listOf(r(line(oct14, -12_340).reference)), slice.importStatusStore.bankTransactionsOf(harbor).map { it.importIdentifier })
        assertEquals(TransactionFileOutcome.Imported, filesOf(harbor).single().outcome)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Step 8, UC-011 BR-001: a Decision Journal that cannot record, or is switched off, neither blocks nor changes the import")
    fun theDecisionJournalNeverBlocksTheImport() = runTest {
        val failing = TestSlice(
            recorder = object : DecisionJournalRecorder {
                override fun record(entry: JournalEntry, keepWithinBytes: Long?) = throw IllegalStateException("The invented journal of this test cannot be written.")
            },
        )
        failing.enterCredentials()
        val read = failing.connect.readBudgets() as BudgetsRead.Listed
        failing.connect.chooseBudgets(read.budgets, setOf("budget-care"))
        val id = (
            failing.connectionStore.addBankAccount(
                NewBankAccount(
                    "00000000-0000-4000-8000-00000000f11e", BankChoice.Named("Harbor Health Trust"), null, null,
                    failing.plugins.fileFormats.single().id, null, null, "Harbor Health Savings", USD, BankAccountType.Savings, null, null,
                    failing.clock.instant(), null, failing.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id,
                ),
            ) as AddOutcome.Added
            ).bankAccountId
        failing.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(listOf(line(oct14, -12_340)))) }

        val outcome = failing.fileImport.import(id, ByteArray(0))

        assertEquals(1, imported(outcome).takenIn.size)
        assertTrue(failing.journal.dropped > 0)

        ready()
        val harbor = fileBankAccount()
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)
        val entriesBefore = slice.journalRecorder.entries.size

        imported(import(harbor, line(oct14, -12_340)))

        assertEquals(entriesBefore, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Precondition (UC-006 BR-005, ADR-007 §2): while an Upload Plan awaits confirmation or an Upload is written, the import is refused with the reason, the file is not converted and nothing is stored; while it runs, the screens show which Bank Account it imports for")
    fun importIsAdmittedOnlyInNormal() = runTest {
        ready()
        val harbor = fileBankAccount()
        converts(line(oct14, -12_340))

        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val planOpen = slice.fileImport.import(harbor, ByteArray(0))
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        val uploading = slice.fileImport.import(harbor, ByteArray(0))

        assertEquals(FileImportOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), planOpen)
        assertEquals(FileImportOutcome.Unavailable(OperationRefusal.UploadBeingWritten), uploading)
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())

        slice.coordinatorStore.mode = CoordinatorMode.Normal
        var running: RunningOperation? = null
        slice.fileFormat.answer = {
            running = slice.coordinator.running.value
            ConversionResult.Converted(StandardImportFile(listOf(line(oct14, -12_340))))
        }
        imported(slice.fileImport.import(harbor, ByteArray(0)))
        assertEquals(RunningOperation(OperationKind.FileImport, "Harbor Health Savings"), running)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("ADR-007 §1: an import that has to wait for the lane behind another operation is told which, and told when it has the lane, before the file is converted; one that waits for nothing is told only that it has the lane")
    fun theImportSaysWhatItWaitsFor() = runTest {
        ready()
        val harbor = fileBankAccount()
        converts(line(oct14, -12_340))
        val signals = mutableListOf<String>()

        imported(slice.fileImport.import(harbor, ByteArray(0), waiting = { signals += "waits for ${it.kind}" }, started = { signals += "started" }))
        assertEquals(listOf("started"), signals)

        signals.clear()
        val lane = kotlinx.coroutines.CompletableDeferred<Unit>()
        val other = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            slice.coordinator.run(OperationKind.Comparison, slussen.core.port.StartedBy.Operator) { lane.await() }
        }
        val waiting = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async {
            slice.fileImport.import(harbor, ByteArray(0), waiting = { signals += "waits for ${it.kind}" }, started = {
                signals += "started, converted before: ${slice.fileFormat.files.size}"
            })
        }
        assertEquals(listOf("waits for Comparison"), signals)
        lane.complete(Unit)
        imported(waiting.await())
        other.join()
        assertEquals(listOf("waits for Comparison", "started, converted before: 1"), signals)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
    @DisplayName("BR-001, NFR-023: a file too large for the device's memory in its conversion is answered as too large; nothing is stored, not even as rejected, and the lane is free for the next operation")
    fun aFileTooLargeForMemoryIsAnswered() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.fileFormat.answer = { throw OutOfMemoryError("an invented lack of memory") }

        val outcome = slice.fileImport.import(harbor, ByteArray(0))

        assertEquals(FileImportOutcome.TooLarge, outcome)
        assertTrue(slice.importStatusStore.files.isEmpty())
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
        assertNull(slice.coordinator.running.value)
        converts(line(oct14, -12_340))
        imported(slice.fileImport.import(harbor, ByteArray(0)))
    }

    // ---- Alternative flows ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
    @DisplayName("A2, BR-001: a file the File Pre-processor rejects is rejected as a whole with its reason; it is recorded as rejected with that reason and 0 transactions, and the Bank Transactions of the previous import are unchanged")
    fun aRejectedConversionRejectsTheWholeFile() = runTest {
        ready()
        val harbor = fileBankAccount()
        imported(import(harbor, line(oct10, -12_340)))
        val before = identities()
        slice.fileFormat.answer = { ConversionResult.Rejected("Line 7, column Amount ($): not a number") }
        slice.clock.advance(Duration.ofMinutes(5))

        val outcome = rejected(slice.fileImport.import(harbor, ByteArray(0)))

        assertEquals(RejectionCause.NotConverted, outcome.cause)
        assertEquals("Line 7, column Amount ($): not a number", outcome.reason)
        assertTrue(outcome.recorded)
        assertEquals(before, identities())
        val rejectedFile = filesOf(harbor).last()
        assertEquals(StoredTransactionFile(harbor, slice.clock.instant(), TransactionFileOutcome.Rejected, 0, "Line 7, column Amount ($): not a number"), rejectedFile)
        val workspace = slice.status.workspace(slice.connectionStore.budgets().single { it.name == "Care Fund" }.id)
        assertEquals(
            SourceHealth.FileImportHealth(TransactionFileOutcome.Rejected, slice.clock.instant(), rejectionReason = "Line 7, column Amount ($): not a number"),
            workspace.bankAccounts.single().health,
        )
        assertEquals(filesOf(harbor).first().importedAt, workspace.bankAccounts.single().freshness, "BR-006: Freshness from the last imported file")
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
    @DisplayName("A2 (S-B-36): the reason is shown and stored as its first 200 characters; an empty reason, or a File Pre-processor that fails, still rejects the file with a reason of Slussen's own")
    fun theReasonIsCutAndAlwaysGiven() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.fileFormat.answer = { ConversionResult.Rejected("x".repeat(250)) }
        val long = rejected(slice.fileImport.import(harbor, ByteArray(0)))
        slice.fileFormat.answer = { ConversionResult.Rejected(" ") }
        val empty = rejected(slice.fileImport.import(harbor, ByteArray(0)))
        slice.fileFormat.answer = { throw IllegalArgumentException("an invented failure of this test") }
        val failed = rejected(slice.fileImport.import(harbor, ByteArray(0)))

        assertEquals("x".repeat(200), long.reason)
        assertEquals("x".repeat(200), filesOf(harbor)[0].rejectionReason)
        assertEquals("The file does not follow its File Format.", empty.reason)
        assertEquals("The File Pre-processor could not read the file.", failed.reason)
        assertFalse("invented" in failed.reason)
        assertTrue(listOf(long, empty, failed).all { it.cause == RejectionCause.NotConverted && it.recorded })
        assertEquals(3, filesOf(harbor).count { it.outcome == TransactionFileOutcome.Rejected && it.transactionCount == 0 })
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
    @DisplayName("A2 (S-B-32): a transaction in another currency than the Bank Account's rejects the whole file; a transaction without a currency is in the Bank Account's")
    fun anotherCurrencyRejectsTheFile() = runTest {
        ready()
        val harbor = fileBankAccount()

        val outcome = rejected(import(harbor, line(oct14, -12_340), line(oct12, -5_000, currency = EUR)))

        assertEquals(RejectionCause.OtherCurrency, outcome.cause)
        assertEquals("Transaction 2 of the file is in another currency than the Bank Account's.", outcome.reason)
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
        assertEquals(TransactionFileOutcome.Rejected, filesOf(harbor).single().outcome)

        imported(import(harbor, line(oct14, -12_340, currency = null)))
        assertEquals(USD, slice.importStatusStore.bankTransactionsOf(harbor).single().currency)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001", "BR-002"])
    @DisplayName("A2, BR-002: a transaction whose reference is empty, or not well-formed Unicode, has no reference Slussen can use, and the whole file is rejected")
    fun aTransactionWithoutAUsableReferenceRejectsTheFile() = runTest {
        ready()
        val harbor = fileBankAccount()

        val empty = rejected(import(harbor, line(oct14, -12_340), line(oct12, -5_000, reference = "")))
        val broken = rejected(import(harbor, line(oct14, -12_340, reference = "REF-\uD800")))

        assertEquals(RejectionCause.NoUsableReference, empty.cause)
        assertEquals("Transaction 2 of the file has no reference Slussen can use.", empty.reason)
        assertEquals(RejectionCause.NoUsableReference, broken.cause)
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A3: File Holds No Importable Transaction")
    @DisplayName("A3: a file with no booked transaction with an amount other than zero is rejected with that reason and recorded as rejected; the stored Bank Transactions are unchanged")
    fun aFileWithoutImportableTransactionsIsRejected() = runTest {
        ready()
        val harbor = fileBankAccount()
        imported(import(harbor, line(oct10, -12_340)))
        val before = identities()

        val pendingOnly = rejected(import(harbor, line(oct14, -2_000, booked = false), line(oct14, 0)))
        val empty = rejected(import(harbor))

        assertEquals(RejectionCause.NoImportableTransaction, pendingOnly.cause)
        assertEquals("The file holds no importable transaction: no booked transaction with an amount other than zero.", pendingOnly.reason)
        assertEquals(RejectionCause.NoImportableTransaction, empty.cause)
        assertEquals(before, identities())
        assertEquals(listOf(TransactionFileOutcome.Imported, TransactionFileOutcome.Rejected, TransactionFileOutcome.Rejected), filesOf(harbor).map { it.outcome })
        assertEquals(listOf(1, 0, 0), filesOf(harbor).map { it.transactionCount })
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A5: Retired Bank Account")
    @DisplayName("A5: the import for a Retired Account is refused with its name, the file is not converted, and nothing is stored, no Transaction File either")
    fun aRetiredAccountIsRefused() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.connectionStore.setRetired(harbor, true)
        converts(line(oct14, -12_340))

        val outcome = slice.fileImport.import(harbor, ByteArray(0))

        assertEquals(FileImportOutcome.RetiredAccount("Harbor Health Savings"), outcome)
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Step 1: a Bank Account whose transactions do not come from Transaction Files, or that is not stored, has no import; nothing is converted or stored")
    fun onlyAFileImportedBankAccountIsImportedFor() = runTest {
        ready()
        val feedsAccount = (
            slice.connectionStore.addBankAccount(
                NewBankAccount(
                    "00000000-0000-4000-8000-000000004410", BankChoice.Named("Kestrel Bank"), null, slice.feedsId, null, null, null,
                    "Kestrel Checking", USD, BankAccountType.Checking, "acct-4410", null, slice.clock.instant(), null, budgetAccountId("Checking"),
                ),
            ) as AddOutcome.Added
            ).bankAccountId
        converts(line(oct14, -12_340))

        assertEquals(FileImportOutcome.NotAFileImportedBankAccount, slice.fileImport.import(feedsAccount, ByteArray(0)))
        assertEquals(FileImportOutcome.NotAFileImportedBankAccount, slice.fileImport.import(999, ByteArray(0)))
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A6: Import Identifier Collision", businessRules = ["BR-001"], requirements = ["NFR-020"])
    @DisplayName("A6, BR-001 (ADR-008 §5): an Import Identifier of the file that a different stored Bank Transaction holds, or another new one of the same file, rejects the whole file with the reason \"Import Identifier collision\"; none of its transactions is stored, the file is recorded as rejected and the collision is recorded in the Decision Journal")
    fun aCollisionRejectsTheWholeFile() = runTest {
        val planted = TestSlice(identify = { ImportIdentifierProtocol.importIdentifier(it) { ByteArray(32) } })
        planted.enterCredentials()
        val read = planted.connect.readBudgets() as BudgetsRead.Listed
        planted.connect.chooseBudgets(read.budgets, setOf("budget-care"))
        val harbor = (
            planted.connectionStore.addBankAccount(
                NewBankAccount(
                    "00000000-0000-4000-8000-00000000f11e", BankChoice.Named("Harbor Health Trust"), null, null,
                    planted.plugins.fileFormats.single().id, null, null, "Harbor Health Savings", USD, BankAccountType.Savings, null, null,
                    planted.clock.instant(), null, planted.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id,
                ),
            ) as AddOutcome.Added
            ).bankAccountId
        suspend fun importInto(vararg lines: StandardImportTransaction): FileImportOutcome {
            planted.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(lines.toList())) }
            return planted.fileImport.import(harbor, ByteArray(0))
        }
        val first = line(oct10, -12_340)
        val second = line(oct12, -5_000)

        val withinOneFile = rejected(importInto(first, second))
        assertTrue(planted.importStatusStore.bankTransactions.isEmpty())
        imported(importInto(first))
        val before = planted.importStatusStore.bankTransactions.toList()
        val againstAStoredOne = rejected(importInto(first, second))

        assertEquals(RejectionCause.ImportIdentifierCollision, withinOneFile.cause)
        assertEquals("Import Identifier collision", withinOneFile.reason)
        assertEquals(RejectionCause.ImportIdentifierCollision, againstAStoredOne.cause)
        assertEquals(before, planted.importStatusStore.bankTransactions)
        assertEquals(
            listOf(TransactionFileOutcome.Rejected, TransactionFileOutcome.Imported, TransactionFileOutcome.Rejected),
            planted.importStatusStore.files.map { it.outcome },
        )
        assertEquals(listOf("Import Identifier collision", null, "Import Identifier collision"), planted.importStatusStore.files.map { it.rejectionReason })
        val collisions = planted.journalRecorder.entries.filter { it.eventKind == JournalEventKind.StatusChanged && "collision" in it.details }
        assertEquals(2, collisions.size)
        assertTrue(collisions.all { it.bankAccountName == "Harbor Health Savings" && it.importIdentifier == before.single().importIdentifier })
    }

    // ---- BR-001: all or nothing ----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("BR-001 (O-3): a storage that fails part-way through the import stores none of its transactions and no imported Transaction File; the file is recorded as rejected in a separate step, the result says that nothing was stored, and nothing is journaled or compared")
    fun aStorageThatFailsPartWayStoresNothing() = runTest {
        ready()
        val harbor = fileBankAccount()
        imported(import(harbor, line(oct10, -12_340)))
        val before = identities()
        val versionBefore = slice.stateVersion.value
        val entriesBefore = slice.journalRecorder.entries.size
        val readsBefore = slice.budgetApp.reader.budgetReads
        slice.importStatusStore.importWholeFailsPartWay = true

        val outcome = import(harbor, line(oct10, -12_340), line(oct12, -5_000), line(oct14, -7_000))

        assertEquals(FileImportOutcome.NotStored(recordedAsRejected = true), outcome)
        assertEquals(before, identities())
        assertEquals(versionBefore, slice.stateVersion.value)
        assertEquals(listOf(TransactionFileOutcome.Imported, TransactionFileOutcome.Rejected), filesOf(harbor).map { it.outcome })
        assertEquals("The file could not be stored; none of its transactions was taken in.", filesOf(harbor).last().rejectionReason)
        assertEquals(entriesBefore, slice.journalRecorder.entries.size)
        assertEquals(readsBefore, slice.budgetApp.reader.budgetReads)

        slice.importStatusStore.recordRejectedFails = true
        slice.fileFormat.answer = { ConversionResult.Rejected("Line 3: an invented reason") }
        val notRecorded = rejected(slice.fileImport.import(harbor, ByteArray(0)))
        assertFalse(notRecorded.recorded)
        assertEquals(2, filesOf(harbor).size)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("BR-001 (review of D2): when the import fails part-way and recording the file as rejected fails too, the result says that it was not recorded; nothing of the file is stored")
    fun aFailedStoreSaysWhetherTheRejectionWasRecorded() = runTest {
        ready()
        val harbor = fileBankAccount()
        slice.importStatusStore.importWholeFailsPartWay = true
        slice.importStatusStore.recordRejectedFails = true

        val outcome = import(harbor, line(oct10, -12_340), line(oct12, -5_000))

        assertEquals(FileImportOutcome.NotStored(recordedAsRejected = false), outcome)
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
        assertTrue(filesOf(harbor).isEmpty())
    }

    // ---- BR-003 and A4: counting a file against the stored Bank Transactions (survey D risk R1) -------------------

    @Nested
    @DisplayName("BR-003, A4: counting a file against the stored Bank Transactions, so that no transaction gets a second Import Identifier")
    inner class Counting {
        private val pharmacy = line(oct14, -12_340)
        private val contribution = line(oct12, 150_000, payee = "PARTIC CONTR CURRENT PARTICIPANT CUR YR")
        private val interest = line(oct10, 1_200, payee = "INTEREST EARNED", memo = "(EXMPX)")
        private val fee = line(oct10, -500, payee = "MONTHLY FEE")

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"])
        @DisplayName("BR-003: the same file imported twice changes nothing but the Transaction Files: no transaction is taken in again, every Import Identifier and Stable Reference stays, nothing is journaled and state_version stays")
        fun theSameFileTwiceChangesNothing() = runTest {
            ready()
            val harbor = fileBankAccount()
            val file = arrayOf(pharmacy, contribution, interest, interest)
            imported(import(harbor, *file))
            val before = identities()
            val statusesBefore = slice.importStatusStore.bankTransactions.map { it.status }
            val versionBefore = slice.stateVersion.value
            val seenBefore = firstTimeEntries().size
            // The Comparison of step 9 stores its read and conclusions with their own state_version (O-4, O-5); without a read, only O-3 is left.
            slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)

            val again = imported(import(harbor, *file))

            assertEquals(
                listOf(
                    Triple(r(pharmacy.reference), pharmacy.reference, 1),
                    Triple(r(contribution.reference), contribution.reference, 1),
                    Triple(f(interest, 1), null, 1),
                    Triple(f(interest, 2), null, 2),
                ),
                before.map { Triple(it.importIdentifier, it.stableReference, it.occurrenceNumber) },
            )
            assertEquals(before, identities())
            assertEquals(statusesBefore, slice.importStatusStore.bankTransactions.map { it.status })
            assertEquals(versionBefore, slice.stateVersion.value)
            assertEquals(seenBefore, firstTimeEntries().size)
            assertTrue(again.takenIn.isEmpty())
            assertEquals(4, again.delivered)
            assertEquals(listOf(4, 4), filesOf(harbor).map { it.transactionCount })
            assertEquals(listOf(TransactionFileOutcome.Imported, TransactionFileOutcome.Imported), filesOf(harbor).map { it.outcome })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-003"])
        @DisplayName("BR-003: an overlapping later export brings only the transactions not stored yet; the earlier ones keep their Import Identifier and Stable Reference, and one the later file no longer holds stays stored")
        fun anOverlappingLaterFileBringsOnlyTheNewOnes() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, contribution, interest, fee))
            val before = identities()

            val later = imported(import(harbor, pharmacy, contribution, interest))

            assertEquals(before, identities().take(3))
            assertEquals(4, identities().size)
            assertEquals(Identity(identities()[3].id, harbor, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1), identities()[3])
            assertEquals(listOf(r(pharmacy.reference)), later.takenIn.map { it.importIdentifier })
            assertEquals(3, later.delivered)
            assertTrue(slice.importStatusStore.bankTransactions.any { it.importIdentifier == r(fee.reference) }, "a transaction no longer in the later file stays stored")
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002", "BR-003"])
        @DisplayName("A4, BR-002: two identical lines on one day in one file are two Bank Transactions without a Stable Reference, numbered 1 and 2 among the identical ones, each Needs Review; never merged")
        fun twoIdenticalLinesInOneFile() = runTest {
            ready()
            val harbor = fileBankAccount()
            assertTrue(identities().isEmpty())

            val outcome = imported(import(harbor, pharmacy, interest, interest))

            assertEquals(
                listOf(
                    Identity(1, harbor, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
                    Identity(2, harbor, f(interest, 1), null, StableReferenceKind.None, 1),
                    Identity(3, harbor, f(interest, 2), null, StableReferenceKind.None, 2),
                ),
                identities(),
            )
            assertEquals(listOf(TransactionStatus.New, TransactionStatus.NeedsReview, TransactionStatus.NeedsReview), outcome.takenIn.map { it.status })
            // Step 10 (`import-file.html#told-apart`): each new one is shown with its number among the identical ones.
            assertEquals(listOf(null, IdenticalPosition(1, 2), IdenticalPosition(2, 2)), outcome.takenIn.map { it.identical })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002", "BR-003"])
        @DisplayName("BR-003 step 1, step 3: a later file with two identical lines, of which one was stored from an earlier file with a Stable Reference, brings in the second one only, without a Stable Reference and Needs Review; the stored one keeps its Stable Reference and Import Identifier")
        fun twoIdenticalWhenOneWasStoredWithAStableReference() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, interest))
            val before = identities()
            assertEquals(listOf(Identity(1, harbor, r(interest.reference), interest.reference, StableReferenceKind.Derived, 1)), before)

            val later = imported(import(harbor, pharmacy, interest, interest))

            assertEquals(
                before + listOf(
                    Identity(2, harbor, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
                    Identity(3, harbor, f(interest, 1), null, StableReferenceKind.None, 1),
                ),
                identities(),
            )
            assertEquals(listOf(TransactionStatus.New, TransactionStatus.NeedsReview), later.takenIn.map { it.status })
            assertEquals(TransactionStatus.New, slice.importStatusStore.bankTransactions.first().status)

            imported(import(harbor, pharmacy, interest, interest))
            assertEquals(3, identities().size)
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-003"])
        @DisplayName("BR-003 step 2, the reverse: a later file that holds one of two identical transactions stored without a Stable Reference brings in none; the one left in it gets no Stable Reference")
        fun oneOfTwoStoredWithoutAStableReference() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, interest, interest))
            val before = identities()
            assertEquals(listOf(null, null), before.map { it.stableReference })

            val later = imported(import(harbor, pharmacy, interest))

            assertEquals(before, identities().take(2))
            assertEquals(listOf(r(pharmacy.reference)), later.takenIn.map { it.importIdentifier })
            assertEquals(3, identities().size)
            assertTrue(slice.importStatusStore.bankTransactions.none { it.stableReference == interest.reference })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-003"])
        @DisplayName("BR-003: three identical lines over two files: with one stored with a Stable Reference, a file of three brings two, numbered 1 and 2 without one; with two stored without one, a file of three brings the third, numbered 3; the same file again brings none")
        fun threeIdenticalLinesOverTwoFiles() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, interest))
            val first = identities()

            val three = imported(import(harbor, interest, interest, interest))
            imported(import(harbor, interest, interest, interest))

            assertEquals(
                first + listOf(
                    Identity(2, harbor, f(interest, 1), null, StableReferenceKind.None, 1),
                    Identity(3, harbor, f(interest, 2), null, StableReferenceKind.None, 2),
                ),
                identities(),
            )
            assertEquals(2, three.takenIn.size)

            val other = fileBankAccount("Harbor Reserve", "Reserve Savings", "f12e")
            imported(import(other, fee, fee))
            val two = identities().filter { it.bankAccountId == other }
            val third = imported(import(other, fee, fee, fee))
            imported(import(other, fee, fee, fee))

            val ofOther = identities().filter { it.bankAccountId == other }
            assertEquals(two, ofOther.take(2))
            assertEquals(listOf(f(fee, 1), f(fee, 2), f(fee, 3)), ofOther.map { it.importIdentifier })
            assertEquals(listOf(1, 2, 3), ofOther.map { it.occurrenceNumber })
            assertTrue(ofOther.all { it.stableReference == null })
            assertEquals(listOf(f(fee, 3)), third.takenIn.map { it.importIdentifier })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"])
        @DisplayName("BR-003: a line whose reference a Bank Transaction of another Bank Account holds is new for its own Bank Account, with that reference as its Stable Reference; the other Bank Account's transaction is unchanged")
        fun aReferenceHeldByAnotherBankAccount() = runTest {
            ready()
            val harbor = fileBankAccount()
            val other = fileBankAccount("Harbor Reserve", "Reserve Savings", "f12e")
            imported(import(other, pharmacy))
            val before = identities()

            val outcome = imported(import(harbor, pharmacy))

            assertEquals(before, identities().take(1))
            assertEquals(Identity(2, harbor, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1), identities()[1])
            assertEquals(1, outcome.takenIn.size)
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-003"])
        @DisplayName("BR-003: a stored Bank Transaction that carries a Review Decision, or is a Transfer Leg, is counted as stored: it keeps its Import Identifier, Stable Reference, Transaction Status, Transfer and Review Decision; only the new line is taken in")
        fun aDecidedTransactionOrATransferLegIsKept() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, contribution, interest, interest))
            val (decided, leg) = slice.importStatusStore.bankTransactions.let { it[0] to it[1] }
            slice.importStatusStore.bankTransactions.replaceAll {
                when (it.id) {
                    decided.id -> it.copy(status = TransactionStatus.Deferred)
                    leg.id -> it.copy(status = TransactionStatus.TransferLeg, transferId = 77)
                    else -> it
                }
            }
            val decision = StoredReviewDecision(decided.id, ReviewChoice.Defer, null, slice.clock.instant())
            slice.copyStore.reviewDecisions += decision
            slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)
            val before = identities()
            val kept = slice.importStatusStore.bankTransactions.toList()

            val later = imported(import(harbor, pharmacy, contribution, interest, interest))

            assertEquals(before, identities().take(3))
            assertEquals(kept, slice.importStatusStore.bankTransactions.take(3))
            assertEquals(listOf(decision), slice.copyStore.reviewDecisions)
            assertEquals(listOf(r(pharmacy.reference)), later.takenIn.map { it.importIdentifier })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-003"])
        @DisplayName("BR-003 (S-B-33): a transaction of a later file counted as a stored one by its Supplied reference leaves it as stored, also with a changed payee or memo, and is not journaled again")
        fun aSuppliedReferenceWithChangedContentLeavesTheStoredOne() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, line(oct10, -500, payee = "MONTHLY FEE", reference = "BANK-9", kind = ReferenceKind.Supplied)))
            val kept = slice.importStatusStore.bankTransactions.toList()
            val seen = firstTimeEntries().size

            val later = imported(import(harbor, line(oct10, -500, payee = "MONTHLY ACCOUNT FEE", memo = "changed", reference = "BANK-9", kind = ReferenceKind.Supplied)))

            assertEquals(kept, slice.importStatusStore.bankTransactions)
            assertTrue(later.takenIn.isEmpty())
            assertEquals(seen, firstTimeEntries().size)
            assertTrue(slice.journalRecorder.entries.none { it.eventKind == JournalEventKind.TransactionChanged })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-003"])
        @DisplayName("BR-003 step 2: a transaction with a Supplied reference that no stored Bank Transaction holds is new, also when stored ones without a Stable Reference are identical to it; two of one file that share a Supplied reference are new without one")
        fun aSuppliedReferenceIsNotCountedAgainstIdenticalOnes() = runTest {
            ready()
            val harbor = fileBankAccount()
            imported(import(harbor, fee, fee))
            val before = identities()
            val supplied = line(oct10, -500, payee = "MONTHLY FEE", reference = "BANK-9", kind = ReferenceKind.Supplied)
            val shared = line(oct12, -800, payee = "WIRE FEE", reference = "BANK-10", kind = ReferenceKind.Supplied)

            imported(import(harbor, supplied, shared, shared))

            assertEquals(before, identities().take(2))
            assertEquals(
                listOf(
                    Identity(3, harbor, r("BANK-9"), "BANK-9", StableReferenceKind.Supplied, 1),
                    Identity(4, harbor, f(shared, 1), null, StableReferenceKind.None, 1),
                    Identity(5, harbor, f(shared, 2), null, StableReferenceKind.None, 2),
                ),
                identities().drop(2),
            )
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002", "BR-003"])
        @DisplayName("BR-003 step 2 (review of D2): a file with two identical lines of one Supplied reference imported three times brings both in at the first import, without a Stable Reference, and nothing new ever after")
        fun twoLinesOfOneSuppliedReferenceImportedThreeTimes() = runTest {
            ready()
            val harbor = fileBankAccount()
            val wire = line(oct12, -800, payee = "WIRE FEE", reference = "BANK-10", kind = ReferenceKind.Supplied)

            imported(import(harbor, wire, wire))
            val first = identities()
            val second = imported(import(harbor, wire, wire))
            val third = imported(import(harbor, wire, wire))

            assertEquals(
                listOf(
                    Identity(1, harbor, f(wire, 1), null, StableReferenceKind.None, 1),
                    Identity(2, harbor, f(wire, 2), null, StableReferenceKind.None, 2),
                ),
                first,
            )
            assertEquals(first, identities())
            assertTrue(second.takenIn.isEmpty() && third.takenIn.isEmpty())
            assertEquals(listOf(2, 2, 2), filesOf(harbor).map { it.transactionCount })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002", "BR-003"])
        @DisplayName("BR-003 steps 1-3 (review of D2): with one Bank Transaction stored with the Stable Reference BANK-9, a later file that holds BANK-9 twice, imported three times, brings one more at its first import, without a Stable Reference, and then nothing; the stored one keeps its Stable Reference")
        fun aStoredSuppliedReferenceTwiceInALaterFile() = runTest {
            ready()
            val harbor = fileBankAccount()
            val fee = line(oct10, -500, payee = "MONTHLY FEE", reference = "BANK-9", kind = ReferenceKind.Supplied)
            imported(import(harbor, fee))
            val stored = identities()
            assertEquals(listOf(Identity(1, harbor, r("BANK-9"), "BANK-9", StableReferenceKind.Supplied, 1)), stored)

            val first = imported(import(harbor, fee, fee))
            val afterFirst = identities()
            val second = imported(import(harbor, fee, fee))
            val third = imported(import(harbor, fee, fee))

            assertEquals(stored + Identity(2, harbor, f(fee, 1), null, StableReferenceKind.None, 1), afterFirst)
            assertEquals(1, first.takenIn.size)
            assertEquals(afterFirst, identities())
            assertTrue(second.takenIn.isEmpty() && third.takenIn.isEmpty())
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002", "BR-003"])
        @DisplayName("BR-003 step 2 (review of D2): two lines of one Supplied reference whose content differs are two Bank Transactions without a Stable Reference, each the first of its identical ones; the same file again brings neither in")
        fun twoLinesOfOneSuppliedReferenceWithDifferentContent() = runTest {
            ready()
            val harbor = fileBankAccount()
            val small = line(oct12, -800, payee = "WIRE FEE", reference = "BANK-11", kind = ReferenceKind.Supplied)
            val large = line(oct12, -900, payee = "WIRE FEE", reference = "BANK-11", kind = ReferenceKind.Supplied)

            imported(import(harbor, small, large))
            val first = identities()
            val again = imported(import(harbor, small, large))

            assertEquals(
                listOf(
                    Identity(1, harbor, f(small, 1), null, StableReferenceKind.None, 1),
                    Identity(2, harbor, f(large, 1), null, StableReferenceKind.None, 1),
                ),
                first,
            )
            assertEquals(first, identities())
            assertTrue(again.takenIn.isEmpty())
        }
    }
}
