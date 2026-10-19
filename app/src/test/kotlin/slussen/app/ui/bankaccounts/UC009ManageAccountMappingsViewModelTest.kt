package slussen.app.ui.bankaccounts

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.setup.FreeBudgetAccountLine
import slussen.app.ui.setup.LeftOutLine
import slussen.core.connection.BringIn
import slussen.core.connection.BudgetsRead
import slussen.core.connection.PairOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD

/**
 * Changing the Bank Account type or the Bank on the Bank Accounts screen (`account-mappings.html#type-and-bank`), and
 * Not imported and Kept by hand apart from the Bank Accounts (`#not-imported`, `#brought-back`, `#kept-by-hand`,
 * `#unmark-kept`).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-009: Manage Account Mappings, the Bank Account type and the Bank, Not imported and Kept by hand on the screen")
class UC009ManageAccountMappingsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val screen = BankAccountsViewModel(fixture.services)
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

    /** Kestrel Checking added; Kids Saver and Pinecrest Business Visa left out in UC-013 (BR-011). */
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
        val shown = slice.connect.pairing().sources.flatMap { it.offered }.map { it.offeredBankAccountId }
        slice.connect.bringIn(shown, emptySet()) as BringIn.Confirmed
        screen.load()
    }

    private fun budgetAccountId(name: String) = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("Step 1, BR-013: below the Bank Accounts and apart from them, Not imported with the Left-Out Bank Accounts and Kept by hand with the Manually Kept Accounts")
    fun notImportedAndKeptByHandAreShownApart() {
        withLeftOut()
        runBlocking { slice.connect.markManuallyKept(budgetAccountId("Rewards Card")) }
        screen.load()

        val apart = ui().apart
        assertEquals("Not imported · 2", apart.leftOutTitle)
        assertEquals(
            listOf(
                LeftOutLine(apart.leftOut[0].leftOutBankAccountId, "Kids Saver ••2290", "Kestrel Feeds · left out 2026-10-15"),
                LeftOutLine(apart.leftOut[1].leftOutBankAccountId, "Pinecrest Business Visa ••9034", "Kestrel Feeds · left out 2026-10-15"),
            ),
            apart.leftOut,
        )
        assertEquals("Kept by hand · 1", apart.keptTitle)
        assertEquals(listOf(KeptLine(budgetAccountId("Rewards Card"), "Rewards Card", "Household · USD · kept by hand · not offered for pairing")), apart.manuallyKept)
        assertEquals(listOf(FreeBudgetAccountLine(budgetAccountId("Reserve Savings"), "Reserve Savings", "Household · USD", "12,000.00")), apart.markable)
        assertEquals(listOf("Kestrel Checking"), ui().groups.flatMap { it.lines }.map { it.bankAccount })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A15: Bring Back a Left-Out Bank Account", businessRules = ["BR-013"])
    @DisplayName("A15: Bring back asks nothing, removes it from Not imported and says it is offered for pairing again")
    fun bringBackFromTheList() {
        withLeftOut()

        screen.bringBack(ui().apart.leftOut.first().leftOutBankAccountId)

        assertEquals("Kids Saver ••2290 brought back · offered for pairing again", ui().message)
        assertEquals(listOf("Pinecrest Business Visa ••9034"), ui().apart.leftOut.map { it.title })
        assertEquals("Not imported · 1", ui().apart.leftOutTitle)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A15: Bring Back a Left-Out Bank Account")
    @DisplayName("A15 step 2: brought back while the latest delivery does not hold it, it is offered from the next delivery that holds it")
    fun bringBackFromTheNextDelivery() {
        withLeftOut()
        runBlocking { slice.offeredStore.replaceOffered(slice.feedsId, null, emptyList()) }

        screen.bringBack(ui().apart.leftOut.first().leftOutBankAccountId)

        assertEquals("Kids Saver ••2290 brought back · offered for pairing from the next delivery that holds it", ui().message)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A16: Mark or Unmark a Manually Kept Account", businessRules = ["BR-013"])
    @DisplayName("A16: Keep by hand marks a free Budget Account at once; Unmark asks first, Cancel keeps it, Unmark offers it for pairing again")
    fun markAndUnmark() {
        kestrelChecking()

        screen.markManuallyKept(budgetAccountId("Reserve Savings"))
        assertEquals("Reserve Savings kept by hand · not offered for pairing", ui().message)
        assertEquals(listOf("Reserve Savings"), ui().apart.manuallyKept.map { it.title })
        screen.askUnmark(budgetAccountId("Reserve Savings"))
        assertEquals(
            UnmarkQuestion(budgetAccountId("Reserve Savings"), "Unmark Reserve Savings?", "Reserve Savings is offered for pairing again. Nothing in ${ExampleData.BUDGET_APP_NAME} changes."),
            ui().apart.unmarkQuestion,
        )
        screen.cancelUnmark()
        assertNull(ui().apart.unmarkQuestion)
        assertEquals(1, ui().apart.manuallyKept.size)
        screen.askUnmark(budgetAccountId("Reserve Savings"))
        screen.confirmUnmark()

        assertEquals("Reserve Savings unmarked · offered for pairing again", ui().message)
        assertTrue(ui().apart.manuallyKept.isEmpty())
        assertNull(ui().apart.unmarkQuestion)
        assertEquals(listOf("Rewards Card", "Reserve Savings"), ui().apart.markable.map { it.title })
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-013"])
    @DisplayName("BR-013: while an Upload Plan awaits confirmation, Bring back and Keep by hand show why they are unavailable")
    fun apartChangesAreUnavailableWhileAPlanAwaitsConfirmation() {
        withLeftOut()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        screen.bringBack(ui().apart.leftOut.first().leftOutBankAccountId)
        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", ui().message)
        screen.markManuallyKept(budgetAccountId("Reserve Savings"))

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", ui().message)
        assertEquals(2, ui().apart.leftOut.size)
        assertTrue(ui().apart.manuallyKept.isEmpty())
    }
}
