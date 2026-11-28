package slussen.app.ui.fileimport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import slussen.app.ui.budget.SourceCard
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.app.ui.fileformat.FileFormatViewModel
import slussen.core.connection.BudgetsRead
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationKind
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.NewBankAccount
import slussen.core.port.StartedBy
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.ReferenceKind
import slussen.pluginapi.fileformat.StandardImportFile
import slussen.pluginapi.fileformat.StandardImportTransaction
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.ExampleData.USD
import java.io.FileNotFoundException
import java.time.LocalDate

/**
 * The screens of Import a Transaction File (`import-file.html`, UC-002) as their ViewModels decide them, on the fakes of
 * `:testing` with the fake File Pre-processor: the control on the Transaction File Source of a Budget tab (S-B-34) and on
 * a File Format's page (A7), each state of the mockup at that control before anything is stored, and each answer there:
 * imported with what was taken in (`#imported`, `#told-apart`), cancelled (`#cancel`), rejected with the File
 * Pre-processor's reason as the core returns it (`#rejected`, `#no-importable`, `#collision`), a Retired Account
 * (`#retired`), a file that cannot be read, a device with no documents app, a store that failed, waiting for another
 * operation and withdrawn (ADR-007 §1); never a crash (NFR-023). Android's document dialog itself only a device shows.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-002: Import a Transaction File, the screens")
class UC002ImportATransactionFileViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val fileFormatId get() = slice.plugins.fileFormats.single().id
    private val oct09: LocalDate = LocalDate.of(2026, 10, 9)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    // ---- Set-up --------------------------------------------------------------------------------------------------

    /** The credentials of the Budget App are accepted and every Budget is served. */
    private fun ready() = runBlocking {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
    }

    private fun budgetId(name: String): Long = runBlocking { slice.connectionStore.budgets().single { it.name == name }.id }

    private fun budgetAccountId(name: String): Long = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    /** A Bank Account whose transactions come from Transaction Files of the fake File Format, mapped to [budgetAccount] (UC-013 A4). */
    private fun fileBankAccount(name: String = "Harbor Health Savings", budgetAccount: String = "Health Savings", suffix: String = "f11e"): Long = runBlocking {
        (
            slice.connectionStore.addBankAccount(
                NewBankAccount(
                    generatedIdentifier = "00000000-0000-4000-8000-00000000$suffix",
                    bank = BankChoice.Named("Harbor Health Trust"),
                    providerBankId = null,
                    dataProviderId = null,
                    fileFormatId = fileFormatId,
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
    }

    /** A line of the Standard Import Format; its Derived reference is the same exactly when two lines are identical. */
    private fun line(date: LocalDate, amount: Long, payee: String = "DEBIT CARD PURCHASE HARBOR PHARMACY") =
        StandardImportTransaction("ref:$date:$amount:$payee", ReferenceKind.Derived, date, Amount(amount), USD, payee, null, true)

    /** The fake File Pre-processor converts the next file into [lines]. */
    private fun converts(vararg lines: StandardImportTransaction) {
        slice.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(lines.toList())) }
    }

    /** The file the document dialog gave: its bytes after [gate], or [failure] as a provider that cannot give it would throw. */
    private class InventedFile(private val failure: Exception? = null, private val gate: CompletableDeferred<Unit>? = null) : TransactionFileSource {
        var reads = 0

        override suspend fun read(): ByteArray {
            reads++
            gate?.await()
            failure?.let { throw it }
            return "an invented file".toByteArray()
        }
    }

    private fun screen() = TransactionFileImportViewModel(fixture.services)

    /** The Transaction File Source of the Care Fund tab, as the Budget tab shows it. */
    private fun careFundSource(): SourceCard {
        val budget = BudgetViewModel(fixture.services, budgetId("Care Fund")).also { it.load() }
        return budget.state.value!!.sources.single { it.fileImport != null }
    }

    /** Import Transaction File on the Care Fund tab, then the document dialog gives [file]. */
    private fun TransactionFileImportViewModel.importOnCareFund(file: TransactionFileSource = InventedFile()) {
        val entry = careFundSource().fileImport!!
        val chosen = start(entry.place, entry.bankAccounts)!!
        assertEquals("Harbor Health Savings", chosen.name)
        import(file)
    }

    private fun TransactionFileImportViewModel.at(place: ImportPlace) = state.value.control(place)

    private fun carePlace() = ImportPlace.Source(budgetId("Care Fund"), fileFormatId)

    private val nothingTakenIn = "Nothing taken in · previous import unchanged"

    // ---- Main Success Scenario -----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Steps 1-3 (#choose, S-B-34): the Transaction File Source of a Budget tab offers Import Transaction File for its Bank Account; from the tap on the control asks for the file of that Bank Account and cannot start a second import")
    fun theSourceOffersTheImport() {
        ready()
        val harbor = fileBankAccount()
        val source = careFundSource()
        val entry = source.fileImport!!
        assertEquals(carePlace(), entry.place)
        assertEquals(listOf(ImportBankAccount(harbor, "Harbor Health Savings")), entry.bankAccounts)
        val screen = screen()
        assertEquals(FileImportControlUi(title = "Import Transaction File", enabled = true), screen.at(entry.place))

        assertEquals(ImportBankAccount(harbor, "Harbor Health Savings"), screen.start(entry.place, entry.bankAccounts))

        assertEquals(
            FileImportControlUi(title = "Choose a Transaction File", enabled = false, running = true, forBankAccount = "Harbor Health Savings"),
            screen.at(entry.place),
        )
        assertNull(screen.start(entry.place, entry.bankAccounts), "no second import while one is under way")
        assertNull(screen.start(ImportPlace.FileFormatPage(fileFormatId), entry.bankAccounts))
        assertFalse(screen.at(ImportPlace.FileFormatPage(fileFormatId)).enabled)
        assertTrue(slice.fileFormat.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("S-B-34: with two Bank Accounts of the File Format in one Budget the Operator chooses the Bank Account first; Cancel leaves the control as it was")
    fun withTwoBankAccountsTheOperatorChooses() {
        ready()
        val harbor = fileBankAccount(budgetAccount = "Reserve Savings")
        val second = fileBankAccount("Harbor Second Savings", budgetAccount = "Checking", suffix = "f12e")
        val budget = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }
        val entry = budget.state.value!!.sources.single { it.fileImport != null }.fileImport!!
        val both = listOf(ImportBankAccount(harbor, "Harbor Health Savings"), ImportBankAccount(second, "Harbor Second Savings"))
        assertEquals(both.sortedBy { it.bankAccountId }, entry.bankAccounts.sortedBy { it.bankAccountId })
        val screen = screen()

        assertNull(screen.start(entry.place, entry.bankAccounts))
        assertEquals(entry.bankAccounts, screen.at(entry.place).choice)
        assertFalse(screen.at(entry.place).enabled)
        screen.cancelChoice()
        assertEquals(FileImportControlUi(title = "Import Transaction File", enabled = true), screen.at(entry.place))

        screen.start(entry.place, entry.bankAccounts)
        assertTrue(screen.choose(ImportBankAccount(second, "Harbor Second Savings")))
        assertEquals("Harbor Second Savings", screen.at(entry.place).forBankAccount)
        assertNull(screen.at(entry.place).choice)
        converts(line(oct14, -12_340))
        screen.import(InventedFile())
        assertEquals(AnswerKind.Done, screen.at(entry.place).answer!!.kind)
        assertEquals(1, runBlocking { slice.importStatusStore.bankTransactionsOf(second) }.size)
        assertTrue(runBlocking { slice.importStatusStore.bankTransactionsOf(harbor) }.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Steps 4-10 (#imported): the file is read before anything is stored and the control says so; then the answer names the import's time and the number taken in, each with its payee and Transaction Status, and the Budget tab shows the import as the Source Health and Freshness of the Bank Account")
    fun theImportIsAnsweredWithWhatItTookIn() {
        ready()
        fileBankAccount()
        val budget = BudgetViewModel(fixture.services, budgetId("Care Fund")).also { it.load() }
        assertEquals("Source Health: no Transaction File imported yet", budget.state.value!!.bankAccounts.single().health)
        converts(line(oct14, -35_000, "Harbor Clinic copay"), line(oct09, 250_000, "Contribution"))
        val gate = CompletableDeferred<Unit>()
        val screen = screen()

        screen.importOnCareFund(InventedFile(gate = gate))

        assertEquals(FileImportControlUi(title = "Reading the Transaction File…", enabled = false, running = true, forBankAccount = "Harbor Health Savings"), screen.at(carePlace()))
        assertTrue(slice.fileFormat.files.isEmpty(), "nothing is converted or stored before the file is read")
        gate.complete(Unit)

        val control = screen.at(carePlace())
        assertEquals(AnswerUi("Imported 2026-10-15 14:00 · new: 2", AnswerKind.Done, "Freshness: data as of the Transaction File imported 2026-10-15 14:00"), control.answer)
        assertEquals(
            listOf(
                TakenInLine("10-14", "Harbor Clinic copay", "−35.00", "New"),
                TakenInLine("10-09", "Contribution", "+250.00", "New"),
            ),
            control.lines,
        )
        assertTrue(control.enabled)
        assertFalse(control.running)
        // UC-002 BR-006, `UiServices.ended`: the tab shows the import once it ends, whichever screen started it.
        val account = budget.state.value!!.bankAccounts.single()
        assertEquals("Source Health: Imported 2026-10-15 14:00 · delivered: 2", account.health)
        assertEquals("Freshness: data as of the Transaction File imported 2026-10-15 14:00", account.freshness)
        val source = budget.state.value!!.sources.single { it.fileImport != null }
        assertEquals("Source Health: Imported 2026-10-15 14:00 · delivered: 2", source.health)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Step 9, UC-003 A1: when the Budget App cannot be read after the import, the import stays and the answer says its new Bank Transactions are not compared yet")
    fun aComparisonThatFailsKeepsTheImport() {
        ready()
        fileBankAccount()
        slice.budgetApp.reader.readBudgetsAnswer = slussen.pluginapi.budgetapp.ReadResult.TransientFailure(slussen.pluginapi.request.FailureKind.Timeout)
        converts(line(oct14, -12_340))
        val screen = screen()

        screen.importOnCareFund()

        val control = screen.at(carePlace())
        assertEquals(
            AnswerUi(
                "Imported 2026-10-15 14:00 · new: 1",
                AnswerKind.Done,
                "Freshness: data as of the Transaction File imported 2026-10-15 14:00 · ${fixture.services.budgetAppName} could not be read: the new Bank Transactions are not compared yet",
            ),
            control.answer,
        )
        assertEquals("not compared yet", control.lines.single().status)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("ADR-007 §1: while the import waits for another operation the control says what it waits for and offers Withdraw; withdrawn, the file is not converted and nothing is stored")
    fun waitingForTheLaneCanBeWithdrawn() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val lane = CompletableDeferred<Unit>()
        val other = CoroutineScope(Dispatchers.Unconfined).launch {
            slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { lane.await() }
        }
        val screen = screen()

        screen.importOnCareFund()

        val waiting = screen.at(carePlace())
        assertEquals("Waits for: Reading the Budget App · then imports the Transaction File for Harbor Health Savings", waiting.title)
        assertTrue(waiting.canWithdraw)
        assertTrue(waiting.running)
        screen.withdraw()

        assertEquals(AnswerUi("Import withdrawn", AnswerKind.Unchanged, "The stored Bank Transactions of Harbor Health Savings are unchanged."), screen.at(carePlace()).answer)
        assertFalse(screen.at(carePlace()).canWithdraw)
        assertTrue(screen.at(carePlace()).enabled)
        lane.complete(Unit)
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
        other.cancel()
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("ADR-007 §1: an import that waited goes on once the other operation ends, and is answered at its control")
    fun anImportThatWaitedGoesOn() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val lane = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Unconfined).launch { slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { lane.await() } }
        val screen = screen()
        screen.importOnCareFund()
        assertTrue(screen.at(carePlace()).canWithdraw)

        lane.complete(Unit)

        assertEquals("Imported 2026-10-15 14:00 · new: 1", screen.at(carePlace()).answer!!.text)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("NFR-023: while the store is on its way the control says the file is imported, Withdraw is not offered and withdrawing does nothing; the answer is kept for the control when the store ends")
    fun theStoreOnItsWayCannotBeLeftWithoutItsAnswer() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val store = CompletableDeferred<Unit>()
        slice.importStatusStore.beforeImportWhole = { store.await() }
        val screen = screen()

        screen.importOnCareFund()

        val importing = screen.at(carePlace())
        assertEquals("Importing the Transaction File for Harbor Health Savings… then comparing with ${fixture.services.budgetAppName}", importing.title)
        assertFalse(importing.canWithdraw)
        screen.withdraw()
        store.complete(Unit)

        assertEquals("Imported 2026-10-15 14:00 · new: 1", screen.at(carePlace()).answer!!.text)
        assertEquals(1, slice.importStatusStore.bankTransactions.size)
    }

    // ---- Alternative flows ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "A1: Operator Cancels the Choice")
    @DisplayName("A1 (#cancel): no file chosen: the control says so and that the stored Bank Transactions of the Bank Account are unchanged; nothing is read or stored")
    fun noFileChosen() {
        ready()
        fileBankAccount()
        val screen = screen()
        val entry = careFundSource().fileImport!!
        screen.start(entry.place, entry.bankAccounts)

        screen.noFileChosen()

        assertEquals(AnswerUi("No file chosen", AnswerKind.Unchanged, "The stored Bank Transactions of Harbor Health Savings are unchanged."), screen.at(entry.place).answer)
        assertTrue(screen.at(entry.place).enabled)
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A1: Operator Cancels the Choice")
    @DisplayName("NFR-023: a device with no app to choose a file is answered at the control, and the control can be tapped again")
    fun noAppToChooseAFile() {
        ready()
        fileBankAccount()
        val screen = screen()
        val entry = careFundSource().fileImport!!
        screen.start(entry.place, entry.bankAccounts)

        screen.noFileCanBeChosen()

        assertEquals(
            AnswerUi("No app on this device can choose a file", AnswerKind.Failed, "Nothing was imported. Install or switch on a files app, then import again."),
            screen.at(entry.place).answer,
        )
        assertTrue(screen.at(entry.place).enabled)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A1: Operator Cancels the Choice")
    @DisplayName("NFR-023: a file whose provider fails, is gone or no longer gives permission is answered at the control; nothing is converted or stored, not even as rejected")
    fun aFileThatCannotBeReadStoresNothing() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val screen = screen()
        val failures = listOf(FileNotFoundException("gone"), SecurityException("no permission any more"), java.io.IOException("the provider failed"), IllegalStateException("the provider failed"))

        failures.forEach { failure ->
            screen.importOnCareFund(InventedFile(failure = failure))

            assertEquals(
                AnswerUi(
                    "The Transaction File could not be read",
                    AnswerKind.Failed,
                    "Nothing taken in · the stored Bank Transactions of Harbor Health Savings are unchanged. Choose the file again.",
                ),
                screen.at(carePlace()).answer,
                failure.toString(),
            )
            assertTrue(screen.at(carePlace()).enabled)
        }
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001", "BR-006"])
    @DisplayName("A2 (#rejected): the File Pre-processor's reason is shown as the core returns it, cut to 200 characters; the Source Health shows the rejection with its reason and the Freshness stays that of the previous import")
    fun aRejectedFileShowsTheReason() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val screen = screen()
        screen.importOnCareFund()
        slice.clock.advance(java.time.Duration.ofMinutes(33))
        val long = "Line 14, column Amount: not a number. " + "x".repeat(250)
        slice.fileFormat.answer = { ConversionResult.Rejected(long) }
        val budget = BudgetViewModel(fixture.services, budgetId("Care Fund")).also { it.load() }

        screen.importOnCareFund()

        val cut = long.take(200)
        assertEquals(AnswerUi("Transaction File rejected as a whole", AnswerKind.Failed, "$cut · $nothingTakenIn"), screen.at(carePlace()).answer)
        assertTrue(screen.at(carePlace()).lines.isEmpty())
        val account = budget.state.value!!.bankAccounts.single()
        assertEquals("Source Health: Rejected 2026-10-15 14:33 · $cut", account.health)
        assertEquals("Freshness: data as of the Transaction File imported 2026-10-15 14:00", account.freshness)
        val source = budget.state.value!!.sources.single { it.fileImport != null }
        assertEquals("Freshness: data as of the Transaction File imported 2026-10-15 14:00", source.freshness)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
    @DisplayName("A2, NFR-023: a rejection that could not be recorded says that the Source Health does not show it")
    fun aRejectionNotRecordedIsSaid() {
        ready()
        fileBankAccount()
        slice.fileFormat.answer = { ConversionResult.Rejected("Line 3, column Date: not a date") }
        slice.importStatusStore.recordRejectedFails = true
        val screen = screen()

        screen.importOnCareFund()

        assertEquals(
            AnswerUi(
                "Transaction File rejected as a whole",
                AnswerKind.Failed,
                "Line 3, column Date: not a date · $nothingTakenIn · The Source Health does not show this rejection: it could not be recorded.",
            ),
            screen.at(carePlace()).answer,
        )
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A3: File Holds No Importable Transaction")
    @DisplayName("A3 (#no-importable): a file with only pending or zero amounts is rejected with that reason at the control")
    fun noImportableTransaction() {
        ready()
        fileBankAccount()
        converts(line(oct14, 0), line(oct09, -12_340).copy(booked = false))
        val screen = screen()

        screen.importOnCareFund()

        assertEquals(AnswerUi("Transaction File rejected: it holds no importable transaction", AnswerKind.Failed, "Only pending or zero amounts · $nothingTakenIn"), screen.at(carePlace()).answer)
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002"])
    @DisplayName("A4 (#told-apart): identical transactions are shown separately, numbered among the identical ones, each Needs Review and never merged")
    fun identicalTransactionsAreShownApart() {
        ready()
        fileBankAccount()
        val refill = line(oct09, -14_000, "Pharmacy refill")
        converts(refill, refill)
        val screen = screen()

        screen.importOnCareFund()

        val control = screen.at(carePlace())
        assertEquals("Imported 2026-10-15 14:00 · new for Harbor Health Savings: 2", control.answer!!.text)
        assertEquals(
            listOf(
                TakenInLine("10-09", "Pharmacy refill · 1 of 2 · identical", "−14.00", "Needs Review"),
                TakenInLine("10-09", "Pharmacy refill · 2 of 2: kept as a separate Bank Transaction, never merged", "−14.00", "Needs Review"),
            ),
            control.lines,
        )
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A5: Retired Bank Account")
    @DisplayName("A5 (#retired): a Bank Account retired since the tab was shown is refused at the control, which says it must be restored first and leads to the Account Mappings; nothing is stored")
    fun aRetiredAccountIsRefused() {
        ready()
        val harbor = fileBankAccount()
        converts(line(oct14, -12_340))
        val screen = screen()
        val entry = careFundSource().fileImport!!
        screen.start(entry.place, entry.bankAccounts)
        runBlocking { slice.connectionStore.setRetired(harbor, true) }

        screen.import(InventedFile())

        val control = screen.at(entry.place)
        assertEquals(AnswerUi("Harbor Health Savings is a Retired Account", AnswerKind.Failed, "Restore it first to import a Transaction File for it."), control.answer)
        assertTrue(control.toAccountMappings)
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A6: Import Identifier Collision", businessRules = ["BR-001"])
    @DisplayName("A6 (#collision): an Import Identifier collision rejects the whole file with that reason at the control")
    fun aCollisionIsAnswered() {
        val planted = UiFixture(identify = { ImportIdentifierProtocol.importIdentifier(it) { ByteArray(32) } })
        val slice = planted.slice
        runBlocking {
            slice.enterCredentials()
            val read = slice.connect.readBudgets() as BudgetsRead.Listed
            slice.connect.chooseBudgets(read.budgets, setOf("budget-care"))
            slice.connectionStore.addBankAccount(
                NewBankAccount(
                    "00000000-0000-4000-8000-00000000f11e", BankChoice.Named("Harbor Health Trust"), null, null,
                    slice.plugins.fileFormats.single().id, null, null, "Harbor Health Savings", USD, BankAccountType.Savings, null, null,
                    slice.clock.instant(), null, slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id,
                ),
            )
        }
        slice.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(listOf(line(oct14, -12_340), line(oct09, -99_000, "Contribution")))) }
        val budget = BudgetViewModel(planted.services, runBlocking { slice.connectionStore.budgets().single { it.name == "Care Fund" }.id }).also { it.load() }
        val entry = budget.state.value!!.sources.single { it.fileImport != null }.fileImport!!
        val screen = TransactionFileImportViewModel(planted.services)
        screen.start(entry.place, entry.bankAccounts)

        screen.import(InventedFile())

        assertEquals(AnswerUi("Transaction File rejected: Import Identifier collision", AnswerKind.Failed, nothingTakenIn), screen.state.value.control(entry.place).answer)
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A7: Bank Account Not Added Yet", businessRules = ["BR-001"])
    @DisplayName("A7 (connections.html#import-first): on the page of a File Format with no Bank Account, Import a Transaction File opens the form of UC-013 A4; after Add the file choice opens for the new Bank Account and the import goes on with the file; Cancel adds nothing")
    fun theFirstFileAddsItsBankAccountFirst() {
        ready()
        val page = FileFormatViewModel(fixture.services, fileFormatId).also { it.load() }
        val first = page.state.value!!
        assertTrue(first.canImportFirst)
        assertEquals("Importing the first file adds its Bank Account first, already mapped.", first.importFirstNote)
        assertEquals("Its Transaction Files are imported from its Transaction File Source on its Budget's tab. Its Account Mapping is changed in Account Mappings.", first.footnote)

        page.openFormToImport()
        assertTrue(page.state.value!!.form != null)
        page.cancelForm()
        assertNull(page.state.value!!.form)
        assertNull(page.state.value!!.importFor)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts() }.isEmpty(), "Cancel ends with nothing added")

        page.openFormToImport()
        page.choose(budgetAccountId("Health Savings"), null)
        page.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "")
        val added = runBlocking { slice.connectionStore.bankAccounts() }.single()
        assertEquals(ImportBankAccount(added.id, "Harbor Health Savings"), page.state.value!!.importFor)
        assertFalse(page.state.value!!.canImportFirst, "later files are imported from the Budget tab")

        val place = ImportPlace.FileFormatPage(fileFormatId)
        val screen = screen()
        val opened = mutableListOf<ImportBankAccount>()
        page.goOnWithImport { bankAccount -> screen.start(place, listOf(bankAccount))?.also { opened += it } != null }
        assertNull(page.state.value!!.importFor)
        assertEquals(listOf(ImportBankAccount(added.id, "Harbor Health Savings")), opened)
        converts(line(oct14, -12_340))
        screen.import(InventedFile())
        assertEquals("Imported 2026-10-15 14:00 · new: 1", screen.at(place).answer!!.text)
        assertEquals(1, runBlocking { slice.importStatusStore.bankTransactionsOf(added.id) }.size)
    }

    // ---- Failures of the storage and the precondition -----------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("NFR-023, ADR-002 §3 O-3: a store that fails part-way takes in nothing; the control says whether the Source Health now shows the failure")
    fun aStoreThatFailsSaysWhetherItWasRecorded() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val screen = screen()
        slice.importStatusStore.importWholeFailsPartWay = true

        screen.importOnCareFund()

        assertEquals(
            AnswerUi("Transaction File not imported", AnswerKind.Failed, "The file could not be stored; none of its transactions was taken in · The Source Health shows the failure."),
            screen.at(carePlace()).answer,
        )
        slice.importStatusStore.importWholeFailsPartWay = true
        slice.importStatusStore.recordRejectedFails = true

        screen.importOnCareFund()

        assertEquals(
            AnswerUi(
                "Transaction File not imported",
                AnswerKind.Failed,
                "The file could not be stored; none of its transactions was taken in · The Source Health does not show the failure: it could not be recorded either. Import the file again.",
            ),
            screen.at(carePlace()).answer,
        )
        assertTrue(slice.importStatusStore.bankTransactions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Precondition (UC-006 BR-005): while an Upload Plan awaits confirmation the import is answered as unavailable at the control; nothing is converted")
    fun unavailableWhileAPlanIsOpen() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val screen = screen()

        screen.importOnCareFund()

        assertEquals(
            AnswerUi("Transaction File not imported", AnswerKind.Failed, "Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first. Nothing taken in."),
            screen.at(carePlace()).answer,
        )
        assertTrue(slice.fileFormat.files.isEmpty())
    }

    // ---- The review of D4 ----------------------------------------------------------------------------------------

    private fun pagePlace() = ImportPlace.FileFormatPage(fileFormatId)

    /** Another operation runs on the lane until the returned deferred is completed. */
    private fun anotherOperationRuns(): CompletableDeferred<Unit> {
        val lane = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Unconfined).launch { slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { lane.await() } }
        return lane
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Review D4 item 1, S-B-34: an open choice of the Bank Account belongs to its screen: every other import control stays enabled, and leaving the screen ends the choice")
    fun anOpenChoiceDisablesNoOtherControl() {
        ready()
        fileBankAccount(budgetAccount = "Reserve Savings")
        fileBankAccount("Harbor Second Savings", budgetAccount = "Checking", suffix = "f12e")
        val household = BudgetViewModel(fixture.services, budgetId("Household")).also { it.load() }.state.value!!.sources.single { it.fileImport != null }.fileImport!!
        val screen = screen()

        screen.start(household.place, household.bankAccounts)

        assertTrue(screen.at(pagePlace()).enabled)
        assertNull(screen.at(pagePlace()).choice)
        screen.left { it == household.place }
        assertNull(screen.at(household.place).choice)
        assertTrue(screen.at(household.place).enabled)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Review D4 item 1, ADR-007 §1: an import that waits shows at every import control what it waits for and for which Bank Account, with Withdraw, and withdrawing there ends it")
    fun aWaitingImportShowsEverywhere() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val lane = anotherOperationRuns()
        val screen = screen()
        screen.importOnCareFund()

        val elsewhere = screen.at(pagePlace())
        assertEquals("Waits for: Reading the Budget App · then imports the Transaction File for Harbor Health Savings", elsewhere.title)
        assertEquals("Harbor Health Savings", elsewhere.forBankAccount)
        assertTrue(elsewhere.running)
        assertTrue(elsewhere.canWithdraw)
        assertFalse(elsewhere.enabled)
        screen.withdraw()

        assertEquals("Import withdrawn", screen.at(carePlace()).answer!!.text)
        lane.complete(Unit)
        assertTrue(slice.fileFormat.files.isEmpty())
        assertTrue(slice.importStatusStore.files.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Review D4 item 1: an import whose store is on its way shows as running at every import control and cannot be withdrawn anywhere")
    fun aStoreOnItsWayShowsEverywhereWithoutWithdraw() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val store = CompletableDeferred<Unit>()
        slice.importStatusStore.beforeImportWhole = { store.await() }
        val screen = screen()
        screen.importOnCareFund()

        val elsewhere = screen.at(pagePlace())
        assertTrue(elsewhere.running)
        assertFalse(elsewhere.canWithdraw)
        assertFalse(elsewhere.enabled)
        assertEquals("Importing the Transaction File for Harbor Health Savings… then comparing with ${fixture.services.budgetAppName}", elsewhere.title)
        screen.withdraw()
        store.complete(Unit)

        assertEquals("Imported 2026-10-15 14:00 · new: 1", screen.at(carePlace()).answer!!.text)
        assertEquals(1, slice.importStatusStore.bankTransactions.size)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Review D4 item 2: the answer with its lines is shown only at its own control, and only until the Operator leaves that screen; then the stored Source Health speaks")
    fun theAnswerEndsWhenItsScreenIsLeft() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val screen = screen()
        screen.importOnCareFund()
        assertEquals(1, screen.at(carePlace()).lines.size)
        assertNull(screen.at(pagePlace()).answer)
        assertTrue(screen.at(pagePlace()).lines.isEmpty())

        screen.left { it == pagePlace() }
        assertTrue(screen.at(carePlace()).answer != null, "leaving another screen keeps it")
        screen.left { it == carePlace() }

        assertNull(screen.at(carePlace()).answer)
        assertTrue(screen.at(carePlace()).lines.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Review D4 item 2, `UiServices.ended`: the answer ends when another operation ends, not when its own import ends")
    fun theAnswerEndsWhenAnotherOperationEnds() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        val screen = screen()
        screen.importOnCareFund()
        assertEquals("Imported 2026-10-15 14:00 · new: 1", screen.at(carePlace()).answer!!.text)

        anotherOperationRuns().complete(Unit)

        assertNull(screen.at(carePlace()).answer)
        assertTrue(screen.at(carePlace()).lines.isEmpty())
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A7: Bank Account Not Added Yet")
    @DisplayName("Review D4 item 3, A7: the import is started before the request to go on is consumed; a start refused because another import runs is answered at the form, the Bank Account stays added")
    fun aRefusedStartAfterAddIsAnsweredAtTheForm() {
        ready()
        val page = FileFormatViewModel(fixture.services, fileFormatId).also { it.load() }
        page.openFormToImport()
        page.choose(budgetAccountId("Health Savings"), null)
        page.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "")
        val added = page.state.value!!.importFor!!
        val asked = mutableListOf<ImportBankAccount>()

        page.goOnWithImport { asked += it; false }

        assertEquals(listOf(added), asked)
        assertNull(page.state.value!!.importFor)
        assertEquals(
            "Harbor Health Savings added and mapped → Health Savings · its transactions come from its Transaction Files · " +
                "Its first file was not imported: another Transaction File import is under way. Import it from its Transaction File Source on its Budget's tab.",
            page.state.value!!.message,
        )
        assertEquals(1, runBlocking { slice.connectionStore.bankAccounts() }.size)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A7: Bank Account Not Added Yet")
    @DisplayName("Review D4 item 3, A7: the file choice opens only while the page that asked is still shown; leaving it before Add ends never opens the dialog at a later visit")
    fun theDialogOpensOnlyWhileThePageIsShown() {
        ready()
        val page = FileFormatViewModel(fixture.services, fileFormatId).also { it.load() }
        page.openFormToImport()
        page.choose(budgetAccountId("Health Savings"), null)
        val lane = anotherOperationRuns()
        page.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "")

        page.left()
        lane.complete(Unit)
        page.load()

        assertEquals(1, runBlocking { slice.connectionStore.bankAccounts() }.size)
        assertNull(page.state.value!!.importFor)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
    @DisplayName("Review D4 item 4: a file too large for the device's memory, at the read or in the conversion, is answered as too large without \"choose the file again\"; nothing is stored and a next import is admitted")
    fun aFileTooLargeIsAnswered() {
        ready()
        fileBankAccount()
        val screen = screen()
        val tooLarge = AnswerUi("The Transaction File is too large for this device", AnswerKind.Failed, "Nothing taken in · the stored Bank Transactions of Harbor Health Savings are unchanged.")

        screen.importOnCareFund(InventedFile(failure = FileTooLargeException()))
        assertEquals(tooLarge, screen.at(carePlace()).answer)

        slice.fileFormat.answer = { throw OutOfMemoryError("an invented lack of memory") }
        screen.importOnCareFund()
        assertEquals(tooLarge, screen.at(carePlace()).answer)
        assertTrue(slice.importStatusStore.files.isEmpty())

        converts(line(oct14, -12_340))
        screen.importOnCareFund()
        assertEquals("Imported 2026-10-15 14:00 · new: 1", screen.at(carePlace()).answer!!.text)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario")
    @DisplayName("Review D4 item 5: when the Budget App was read but the statuses could not be read after the import, the answer says that, not that the Budget App could not be read")
    fun statusesNotReadAreSaidAsSuch() {
        ready()
        fileBankAccount()
        converts(line(oct14, -12_340))
        slice.importStatusStore.beforeImportWhole = { slice.importStatusStore.bankTransactionsReadFails = true }
        val screen = screen()

        screen.importOnCareFund()

        val control = screen.at(carePlace())
        assertEquals(
            "Freshness: data as of the Transaction File imported 2026-10-15 14:00 · The Transaction Statuses could not be read after the import; the Bank Account shows them",
            control.answer!!.detail,
        )
        assertEquals("status not read", control.lines.single().status)
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A7: Bank Account Not Added Yet")
    @DisplayName("Review D4 item 6: the File Format page keeps its form's message when the reload after an operation ends finishes after the message was given")
    fun theFormsMessageSurvivesALateReload() {
        ready()
        val page = FileFormatViewModel(fixture.services, fileFormatId).also { it.load() }
        page.openFormToImport()
        val held = CompletableDeferred<Unit>()
        var holdNext = true
        slice.connectionStore.beforeBanksRead = { if (holdNext) { holdNext = false; held.await() } }
        anotherOperationRuns().complete(Unit)

        page.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "")
        assertEquals("Choose a Budget Account · not added", page.state.value!!.message)
        held.complete(Unit)

        assertEquals("Choose a Budget Account · not added", page.state.value!!.message)
    }
}
