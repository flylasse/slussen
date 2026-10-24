package slussen.app.ui.mappings

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.service.ServicePageViewModel
import slussen.core.connection.BudgetsRead
import slussen.core.connection.PairOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD

/**
 * The Account Mappings (`account-mappings.html#map`): the Bank Accounts by Budget with their Bank Account type and Bank,
 * changed in the sheet of `#type-and-bank` (A14), and Not imported and Kept by hand, read-only, leading to the service
 * pages where they are brought back and marked (`#not-imported`, `#brought-back`, `#kept-by-hand`; A15, A16, BR-013,
 * BR-014).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-009: Manage Account Mappings, the Bank Account type and the Bank, Not imported and Kept by hand on the screen")
class UC009ManageAccountMappingsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val screen = AccountMappingsViewModel(fixture.services)
    private var checkingId = 0L

    private fun ui() = screen.state.value!!

    /** Kestrel Checking (Checking, Kestrel Bank) mapped to Checking, with the Transfer Partner Reserve Savings. */
    private fun kestrelChecking() = runBlocking {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
        val kestrel = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.connectionStore.bankNamed("Pinecrest Card Co.")
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, kestrel, slice.clock.instant())),
        )
        val accounts = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.associate { it.name to it.id }
        checkingId = (
            slice.connect.add(
                slice.offeredStore.offered().single().id, accounts.getValue("Checking"), "Kestrel Checking",
                BankAccountType.Checking, BankChoice.Known(kestrel), null,
            ) as PairOutcome.Added
            ).bankAccountId
        slice.connectionStore.link(checkingId, transferPartner = accounts.getValue("Reserve Savings"))
        screen.load()
    }

    private fun bank(name: String) = runBlocking { slice.connectionStore.bankNamed(name).id }

    private fun stored() = runBlocking { slice.connectionStore.bankAccounts().single { it.id == checkingId } }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario")
    @DisplayName("Step 1: each Bank Account is listed with its Bank, its Bank Account type and its Source")
    fun listShowsTheType() {
        kestrelChecking()

        val line = ui().groups.single().lines.single()

        assertEquals(checkingId, line.bankAccountId)
        assertEquals("Kestrel Bank · Checking · Kestrel Feeds", line.detail)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 1: the sheet shows the current type and Bank, the four types, the known Banks and what Credit Card would clear")
    fun sheetShowsTheChoices() {
        kestrelChecking()

        screen.openTypeAndBank(checkingId)

        val sheet = ui().typeAndBank!!
        assertEquals("Kestrel Checking · Kestrel Feeds", sheet.title)
        assertEquals(BankAccountType.Checking, sheet.type)
        assertEquals("Kestrel Bank", sheet.bank.name)
        assertEquals(
            listOf("Checking · current", "Savings", "Credit Card · would clear its Transfer Partner Reserve Savings", "Other"),
            sheet.typeOptions.map { it.text },
        )
        assertEquals(listOf("Kestrel Bank · current", "Pinecrest Card Co."), sheet.bankOptions.map { it.text })
        assertEquals(
            "Changes no Account Mapping, identification or Import Identifier · what was sent stays as it is, so no confirmation is asked",
            sheet.note,
        )
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 steps 2-4: Save stores the type and the Bank, closes the sheet and shows the change in the list")
    fun typeAndBankAreSaved() {
        kestrelChecking()
        screen.openTypeAndBank(checkingId)

        screen.saveTypeAndBank(BankAccountType.Savings, BankChoice.Known(bank("Pinecrest Card Co.")))

        assertNull(ui().typeAndBank)
        assertEquals("Kestrel Checking: Savings · Pinecrest Card Co. · stored", ui().message)
        assertEquals("Pinecrest Card Co. · Savings · Kestrel Feeds", ui().groups.single().lines.single().detail)
        assertEquals(BankAccountType.Savings, stored().type)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 3: Credit Card first states that the Transfer Partner will be cleared; Save clears it, Cancel keeps everything")
    fun clearingIsStatedBeforeSaving() {
        kestrelChecking()
        screen.openTypeAndBank(checkingId)
        val kestrel = BankChoice.Known(bank("Kestrel Bank"))

        screen.saveTypeAndBank(BankAccountType.CreditCard, kestrel)
        assertEquals(
            "Credit Card clears the Transfer Partner Reserve Savings: a Transfer Partner belongs only to a Bank Account that is not a credit card.",
            ui().typeAndBank!!.clearingQuestion,
        )
        assertEquals(BankAccountType.Checking, stored().type)
        screen.cancelClearing()
        assertNull(ui().typeAndBank!!.clearingQuestion)
        assertEquals(BankAccountType.Checking, stored().type)

        screen.saveTypeAndBank(BankAccountType.CreditCard, kestrel)
        screen.confirmClearing()

        assertNull(ui().typeAndBank)
        assertEquals(BankAccountType.CreditCard, stored().type)
        assertNull(stored().transferPartnerBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 2: a new Bank is named; an empty name is refused with why; Cancel closes the sheet")
    fun newBankIsNamed() {
        kestrelChecking()
        screen.openTypeAndBank(checkingId)

        screen.saveTypeAndBank(BankAccountType.Checking, BankChoice.Named(" "))
        assertEquals("Choose a Bank or name a new one", ui().message)
        screen.saveTypeAndBank(BankAccountType.Checking, BankChoice.Named("Harbor Savings Bank"))

        assertEquals("Harbor Savings Bank · Checking · Kestrel Feeds", ui().groups.single().lines.single().detail)
        screen.openTypeAndBank(checkingId)
        screen.cancelTypeAndBank()
        assertNull(ui().typeAndBank)
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-012"])
    @DisplayName("BR-012: while an Upload Plan awaits confirmation, saving shows why it is unavailable")
    fun unavailableWhileAPlanAwaitsConfirmation() {
        kestrelChecking()
        screen.openTypeAndBank(checkingId)
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        screen.saveTypeAndBank(BankAccountType.Savings, BankChoice.Known(bank("Kestrel Bank")))

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", ui().message)
        assertEquals(BankAccountType.Checking, stored().type)
    }

    /** Kestrel Checking added; the import of Kids Saver and Pinecrest Business Visa switched off in UC-013 (BR-011, BR-012). */
    private fun withLeftOut() = runBlocking {
        kestrelChecking()
        val kestrel = slice.connectionStore.bankNamed("Kestrel Bank").id
        val pinecrest = slice.connectionStore.bankNamed("Pinecrest Card Co.").id
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, kestrel, slice.clock.instant()),
                ExampleData.offered("acct-2290", "Kids Saver", "savings", USD, "2290", null, kestrel, slice.clock.instant()),
                ExampleData.offered("acct-9034", "Pinecrest Business Visa", "credit card", USD, "9034", null, pinecrest, slice.clock.instant()),
            ),
        )
        listOf("acct-2290", "acct-9034").forEach { reference ->
            slice.connect.switchImportOff(slice.offeredStore.offered().single { it.providerReference == reference }.id)
        }
        screen.load()
    }

    private fun budgetAccountId(name: String) = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-013", "BR-014"])
    @DisplayName("Step 1, BR-013, BR-014: after the Bank Accounts, two short read-only lists: Not imported, each leading to its Data Provider's page, and Kept by hand, each leading to the Budget App's page")
    fun notImportedAndKeptByHandAreReadOnlyLists() {
        withLeftOut()
        runBlocking { slice.connect.setReceivesFromSlussen(budgetAccountId("Rewards Card"), receives = false) }
        screen.load()

        val ui = ui()
        assertEquals("Not imported · Left-Out Bank Accounts · 2", ui.leftOutTitle)
        assertEquals(
            listOf(
                LeftOutLine(ui.leftOut[0].leftOutBankAccountId, "Kids Saver ••2290 · Kestrel Feeds", slice.feedsKey),
                LeftOutLine(ui.leftOut[1].leftOutBankAccountId, "Pinecrest Business Visa ••9034 · Kestrel Feeds", slice.feedsKey),
            ),
            ui.leftOut,
        )
        assertEquals("Kept by hand · Manually Kept Accounts · 1", ui.keptTitle)
        assertEquals(listOf(KeptLine(budgetAccountId("Rewards Card"), "Rewards Card", "Household · USD")), ui.kept)
        assertTrue(ui.toMap.isEmpty())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-013", "BR-014"])
    @DisplayName("BR-014 (P9-06): with none left out and none kept by hand, neither list is shown")
    fun emptyListsAreNotShown() {
        kestrelChecking()

        assertTrue(ui().leftOut.isEmpty())
        assertTrue(ui().kept.isEmpty())
        assertEquals(1, ui().groups.single().lines.size)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A15: Bring Back a Left-Out Bank Account", businessRules = ["BR-013"])
    @DisplayName("A15: a Left-Out Bank Account shown as not imported is imported again on its Data Provider's page, without any confirmation; back in the Account Mappings it is to map and no longer under Not imported")
    fun bringBackOnTheDataProvidersPage() {
        withLeftOut()
        val kids = ui().leftOut.first()
        val page = ServicePageViewModel(fixture.services, kids.service).also { it.load() }
        val line = page.state.value!!.offered!!.banks.flatMap { it.rows }.single { it.leftOutBankAccountId == kids.leftOutBankAccountId }

        page.switchImport(line, on = true)
        screen.load()

        assertEquals(listOf("Pinecrest Business Visa ••9034 · Kestrel Feeds"), ui().leftOut.map { it.title })
        assertEquals(listOf("Kids Saver"), ui().toMap.map { it.title })
        assertEquals("To map · imported, not yet added · 1", ui().toMapTitle)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A16: Mark or Unmark a Manually Kept Account", businessRules = ["BR-013"])
    @DisplayName("A16: a Budget Account switched off on the Budget App's page is listed as kept by hand; switched on again it leaves the list, with no question asked")
    fun markAndUnmarkOnTheBudgetAppsPage() {
        kestrelChecking()
        val page = ServicePageViewModel(fixture.services, slussen.core.port.ServiceKey.budgetApp).also { it.load() }

        page.setReceives(budgetAccountId("Reserve Savings"), receives = false)
        screen.load()
        assertEquals(listOf("Reserve Savings"), ui().kept.map { it.title })
        page.setReceives(budgetAccountId("Reserve Savings"), receives = true)
        screen.load()

        assertTrue(ui().kept.isEmpty())
        assertTrue(runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.none { it.manuallyKept } })
    }
}
