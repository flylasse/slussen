package slussen.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.mannodermaus.junit5.compose.createComposeExtension
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import slussen.app.ui.budget.BudgetScreen
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.fileformat.FileFormatScreen
import slussen.app.ui.fileformat.FileFormatViewModel
import slussen.app.ui.fileimport.FileImportActions
import slussen.app.ui.fileimport.FileImportControl
import slussen.app.ui.fileimport.FileImportTexts
import slussen.app.ui.fileimport.ImportPlace
import slussen.app.ui.fileimport.ImportTarget
import slussen.app.ui.fileimport.TransactionFileImportViewModel
import slussen.app.ui.fileimport.TransactionFileSource
import slussen.app.ui.fileimport.control
import slussen.app.ui.theme.SlussenTheme
import slussen.core.connection.BudgetsRead
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.NewBankAccount
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.ReferenceKind
import slussen.pluginapi.fileformat.StandardImportFile
import slussen.pluginapi.fileformat.StandardImportTransaction
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.ExampleData.USD
import java.time.LocalDate

/**
 * The screens of Import a Transaction File on a device (`import-file.html`, UC-002), as far as they go without Android's
 * document dialog: a tap on the control asks the ViewModel for the Bank Account to open the dialog for, as `SlussenScreens`
 * would open it, and the test then hands over the file the dialog would give. What the dialog itself shows, a real
 * documents provider and a device without a documents app only a device run by hand shows. Invented data only (C-005).
 */
@OptIn(ExperimentalTestApi::class)
@DisplayName("UC-002: Import a Transaction File, the screens on a device")
class UC002ImportATransactionFileScreenIT {
    @JvmField
    @RegisterExtension
    val compose = createComposeExtension()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private val invented = TransactionFileSource { "an invented file".toByteArray() }

    /** The three Budgets served and, unless [withBankAccount] is false, a Bank Account of the fake File Format in Care Fund. */
    private fun ready(fixture: ScreenFixture, withBankAccount: Boolean = true): Long = runBlocking {
        val slice = fixture.slice
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
        if (withBankAccount) {
            slice.connectionStore.addBankAccount(
                NewBankAccount(
                    "00000000-0000-4000-8000-00000000f11e", BankChoice.Named("Harbor Health Trust"), null, null,
                    slice.plugins.fileFormats.single().id, null, null, "Harbor Health Savings", USD, BankAccountType.Savings, null, null,
                    slice.clock.instant(), null, slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id,
                ),
            ) as AddOutcome.Added
        }
        slice.connectionStore.budgets().single { it.name == "Care Fund" }.id
    }

    private fun converts(fixture: ScreenFixture, vararg lines: StandardImportTransaction) {
        fixture.slice.fileFormat.answer = { ConversionResult.Converted(StandardImportFile(lines.toList())) }
    }

    private fun line(amount: Long, payee: String) =
        StandardImportTransaction("ref:$amount:$payee", ReferenceKind.Derived, LocalDate.of(2026, 10, 14), Amount(amount), USD, payee, null, true)

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Steps 1-10 (#choose, #imported): Import Transaction File on the Care Fund tab asks for the file of its Bank Account, cannot be tapped again while it runs, and answers with what it took in and its Transaction Status")
    fun theFileIsImportedFromTheBudgetTab() = compose.use {
        val fixture = ScreenFixture()
        val careFund = ready(fixture)
        converts(fixture, line(-35_000, "Harbor Clinic copay"))
        val budget = BudgetViewModel(fixture.services, careFund)
        val screen = TransactionFileImportViewModel(fixture.services)
        val opened = mutableListOf<ImportTarget>()
        onMain { budget.load() }
        setContent {
            SlussenTheme {
                val ui = screen.state.collectAsState().value
                budget.state.collectAsState().value?.let { state ->
                    BudgetScreen(state, onRemedy = {}, onStartFirstUploadMatching = {}, fileImport = { entry ->
                        FileImportControl(
                            ui.control(entry.place),
                            FileImportActions(
                                onImport = { screen.start(entry.place, entry.bankAccounts)?.let { opened += it } },
                                onChoose = {}, onCancelChoice = {}, onWithdraw = screen::withdraw, onAccountMappings = {},
                            ),
                        )
                    })
                }
            }
        }

        onAllNodesWithText("Source Health: no Transaction File imported yet", substring = true).onFirst().assertExists()
        onNodeWithText(FileImportTexts.IMPORT).performClick()
        assertEquals(listOf("Harbor Health Savings"), opened.map { it.name })
        onNodeWithTag("file-import").assertIsNotEnabled()
        onNodeWithText(FileImportTexts.CHOOSE).assertExists()
        onMain { screen.import(invented) }

        onNodeWithText("Imported 2026-10-15 14:00 · new: 1").assertExists()
        onNodeWithText("Harbor Clinic copay").assertExists()
        onAllNodesWithText("New").onFirst().assertExists()
        onNodeWithTag("file-import").assertIsEnabled()
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A1: Operator Cancels the Choice")
    @DisplayName("A1, A2 (#cancel, #rejected): no file chosen says the stored Bank Transactions are unchanged; a rejected file shows the File Pre-processor's reason at the control")
    fun cancelledAndRejected() = compose.use {
        val fixture = ScreenFixture()
        val careFund = ready(fixture)
        fixture.slice.fileFormat.answer = { ConversionResult.Rejected("Line 14, column Amount: not a number") }
        val place = ImportPlace.Source(careFund, fixture.slice.plugins.fileFormats.single().id)
        val target = runBlocking { ImportTarget(fixture.slice.connectionStore.bankAccounts().single().id, "Harbor Health Savings") }
        val screen = TransactionFileImportViewModel(fixture.services)
        setContent {
            SlussenTheme {
                FileImportControl(
                    screen.state.collectAsState().value.control(place),
                    FileImportActions(onImport = { screen.start(place, listOf(target)) }, onChoose = {}, onCancelChoice = {}, onWithdraw = {}, onAccountMappings = {}),
                )
            }
        }

        onNodeWithText(FileImportTexts.IMPORT).performClick()
        onMain { screen.noFileChosen() }
        onNodeWithText("No file chosen").assertExists()
        onNodeWithText("The stored Bank Transactions of Harbor Health Savings are unchanged.").assertExists()

        onNodeWithText(FileImportTexts.IMPORT).performClick()
        onMain { screen.import(invented) }
        onNodeWithText("Transaction File rejected as a whole").assertExists()
        onNodeWithText("Line 14, column Amount: not a number · Nothing taken in · previous import unchanged").assertExists()
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A7: Bank Account Not Added Yet")
    @DisplayName("A7 (connections.html#import-first): the page of a File Format with no Bank Account offers Import a Transaction File, which opens the form of adding its Bank Account first")
    fun theFirstFileStartsFromTheFileFormatPage() = compose.use {
        val fixture = ScreenFixture()
        ready(fixture, withBankAccount = false)
        val fileFormatId = fixture.slice.plugins.fileFormats.single().id
        val page = FileFormatViewModel(fixture.services, fileFormatId)
        val screen = TransactionFileImportViewModel(fixture.services)
        val place = ImportPlace.FileFormatPage(fileFormatId)
        onMain { page.load() }
        setContent {
            SlussenTheme {
                page.state.collectAsState().value?.let { ui ->
                    FileFormatScreen(
                        ui, onBack = {}, onOpenForm = page::openForm, onCancelForm = page::cancelForm, onChoose = page::choose, onAdd = page::add,
                        fileImport = { showButton ->
                            FileImportControl(
                                screen.state.collectAsState().value.control(place, FileImportTexts.IMPORT_FIRST),
                                FileImportActions(onImport = page::openFormToImport, onChoose = {}, onCancelChoice = {}, onWithdraw = {}, onAccountMappings = {}),
                                showButton = showButton,
                            )
                        },
                    )
                }
            }
        }

        onNodeWithText(FileImportTexts.IMPORT_FIRST_NOTE).assertExists()
        onNodeWithText(FileImportTexts.IMPORT_FIRST).performClick()
        onNodeWithText("Add a Bank Account".uppercase()).assertExists()
    }
}
