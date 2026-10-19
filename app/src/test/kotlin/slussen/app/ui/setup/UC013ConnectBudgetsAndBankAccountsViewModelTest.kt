package slussen.app.ui.setup

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
import slussen.app.ui.bankaccounts.BankAccountsViewModel
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.LocalDate

@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-013: Connect Budgets and Bank Accounts, the screens")
class UC013ConnectBudgetsAndBankAccountsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val setup = SetupViewModel(fixture.services)

    private fun ui() = setup.state.value!!

    private fun offer() = runBlocking {
        val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", amount(4140, 50), bank, slice.clock.instant()),
                ExampleData.offered("acct-5520", "Aurora Dollar", "savings", USD, "5520", amount(1520), bank, slice.clock.instant()),
            ),
        )
    }

    private fun throughBudgets() {
        runBlocking { slice.enterCredentials() }
        setup.load()
        setup.goTo(SetupStep.Budgets)
        setup.toggleBudget("budget-household")
        setup.toggleBudget("budget-summer")
        setup.saveBudgets()
    }

    private fun offeredId(reference: String) = runBlocking { slice.offeredStore.offered().single { it.providerReference == reference }.id }

    private fun freeId(name: String) = ui().freeBudgetAccounts.single { it.title == name }.budgetAccountId

    private fun kestrel() = runBlocking { BankChoice.Known(slice.connectionStore.bankNamed("Kestrel Bank").id) }

    /** Steps 6-10 with the proposed type and Bank, the [name] and [startDate] given. */
    private fun pairAndSave(reference: String, budgetAccount: String, name: String, startDate: String = "") {
        setup.selectOffered(offeredId(reference))
        setup.pairWith(freeId(budgetAccount))
        val naming = ui().naming!!
        setup.saveNaming(name, naming.proposedType, naming.proposedBank?.let { BankChoice.Known(it.bankId) }, startDate)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Step 1: the first step lists the Budget App and each Data Provider with whether its credentials are accepted")
    fun firstStepListsTheCredentials() {
        setup.load()

        assertEquals(SetupStep.Credentials, ui().step)
        assertEquals(
            listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds", "Aurora Open Banking", "Wren Feeds"),
            ui().credentialLines.map { it.name },
        )
        assertEquals("Budget App", ui().credentialLines.first().kind)
        assertTrue(ui().credentialLines.all { it.status == "Not entered" })
        assertFalse(ui().budgetAppAccepted)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A7: Budget App Credentials Not Entered", businessRules = ["BR-008"])
    @DisplayName("A7: without accepted Budget App credentials the Budgets step says they can be chosen once those are accepted")
    fun budgetsWaitForTheBudgetAppCredentials() {
        setup.load()

        setup.goTo(SetupStep.Budgets)

        assertEquals("Budgets can be chosen once the ${ExampleData.BUDGET_APP_NAME} credentials are accepted.", ui().message)
        assertTrue(ui().budgets.isEmpty())
        assertEquals(0, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Steps 2-4: the Budgets listed by the Budget App are chosen and stored, then the Bank Accounts step follows")
    fun budgetsAreChosen() {
        runBlocking { slice.enterCredentials() }
        setup.load()

        setup.goTo(SetupStep.Budgets)
        assertEquals(
            listOf(BudgetChoiceLine("budget-household", "Household", "USD", false), BudgetChoiceLine("budget-summer", "Summer House", "EUR", false), BudgetChoiceLine("budget-care", "Care Fund", "USD", false)),
            ui().budgets,
        )
        setup.toggleBudget("budget-household")
        setup.saveBudgets()

        assertEquals(SetupStep.BankAccounts, ui().step)
        assertEquals(listOf("Household"), runBlocking { slice.connectionStore.budgets().filter { it.served }.map { it.name } })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials")
    @DisplayName("A1: a Budget App that cannot be read is shown as such and the Budgets stay unchanged")
    fun unreadableBudgetAppIsShown() {
        runBlocking { slice.enterCredentials() }
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)
        setup.load()

        setup.goTo(SetupStep.Budgets)

        assertEquals(
            "${ExampleData.BUDGET_APP_NAME} could not be read: no answer within the request timeout, an Operator Setting · Budgets unchanged",
            ui().message,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials")
    @DisplayName("A1: refused credentials show the Blocked Reason credentials refused with the action that enters them")
    fun refusedCredentialsAreShown() {
        runBlocking { slice.enterCredentials() }
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused
        setup.load()

        setup.goTo(SetupStep.Budgets)

        assertEquals("Blocked: credentials refused · Enter credentials", ui().message)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A2: Stop Serving a Budget That Has Mapped Bank Accounts")
    @DisplayName("A2: leaving out a Budget with mapped Bank Accounts is refused, naming them")
    fun stopServingAMappedBudgetIsRefused() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        pairAndSave("acct-4410", "Checking", "CHK ••4410")
        setup.goTo(SetupStep.Budgets)

        setup.toggleBudget("budget-household")
        setup.saveBudgets()

        assertEquals("Household has mapped Bank Accounts CHK ••4410 · remove their Account Mappings first", ui().message)
        assertEquals(SetupStep.Budgets, ui().step)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 5: each Offered Bank Account is shown with name, kind, currency, last four digits and balance next to the free Budget Accounts")
    fun pairingShowsBothSides() {
        throughBudgets()
        offer()

        setup.goTo(SetupStep.BankAccounts)

        val feeds = ui().sources.single { it.name == "Kestrel Feeds" }
        assertEquals(OfferedLine(offeredId("acct-4410"), "CHK", "checking · USD · ••••4410", "4,140.05"), feeds.offered.first())
        assertNull(feeds.remedy)
        val checking = ui().freeBudgetAccounts.first()
        assertEquals("Checking", checking.title)
        assertEquals("Household · USD", checking.detail)
        assertEquals("4,140.05", checking.balance)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("Steps 6-8: pairing shows the proposed name, Bank Account type and Bank, from what the Data Provider states, and adds nothing yet")
    fun pairingShowsTheProposal() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)

        setup.selectOffered(offeredId("acct-4410"))
        setup.pairWith(freeId("Checking"))

        val naming = ui().naming!!
        assertEquals("CHK ••4410", naming.proposedName)
        assertEquals("CHK · ••••4410 → Checking", naming.title)
        assertEquals(BankAccountType.Checking, naming.proposedType)
        assertEquals("Kestrel Bank", naming.proposedBank?.name)
        assertEquals(listOf("Checking", "Savings", "Credit Card", "Other"), naming.types.map { it.text })
        assertEquals(listOf("Kestrel Bank"), naming.knownBanks.map { it.name })
        assertEquals("Type and Bank proposed from what Kestrel Feeds states · change them if they are wrong", naming.note)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005", "BR-009"])
    @DisplayName("Steps 9-11: the accepted name, type, Bank and Start Date are stored and the Bank Account is listed as added")
    fun pairingNamingAndStartDate() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)

        pairAndSave("acct-4410", "Checking", "Kestrel Checking", "2026-09-01")

        assertNull(ui().naming)
        assertEquals("Bank Account Kestrel Checking added · fetched from the next Fetch", ui().message)
        val stored = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals("Kestrel Checking", stored.name)
        assertEquals(BankAccountType.Checking, stored.type)
        assertEquals(LocalDate.parse("2026-09-01"), stored.startDate)
        assertTrue(ui().sources.single { it.name == "Kestrel Feeds" }.offered.none { it.title == "CHK" })
        val list = BankAccountsViewModel(fixture.services).also { it.load() }.state.value!!.groups
        assertEquals("Household · USD", list.single().title)
        assertEquals("Kestrel Checking", list.single().lines.single().bankAccount)
        assertEquals("Kestrel Bank · Checking · Kestrel Feeds", list.single().lines.single().detail)
        assertEquals("Checking", list.single().lines.single().budgetAccount)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: the Operator changes the type and names a new Bank; without a Bank the Bank Account is not added and the screen says why")
    fun typeAndBankAreConfirmedOrChanged() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        setup.selectOffered(offeredId("acct-4410"))
        setup.pairWith(freeId("Checking"))

        setup.saveNaming("Kestrel Checking", BankAccountType.Savings, null, "")
        assertEquals("Choose a Bank or name a new one · not added", ui().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        setup.saveNaming("Kestrel Checking", null, kestrel(), "")
        assertEquals("Choose the Bank Account type · not added", ui().message)

        setup.saveNaming("Kestrel Checking", BankAccountType.Savings, BankChoice.Named("Kestrel Savings Bank"), "")

        val stored = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals(BankAccountType.Savings, stored.type)
        assertEquals("Kestrel Savings Bank", runBlocking { slice.connectionStore.banks().single { it.id == stored.bankId }.name })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: when the Data Provider names no Bank, none is proposed and the screen asks to choose or name one")
    fun noBankNamedIsShown() {
        throughBudgets()
        runBlocking {
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-2209", "Old Savings", "savings", USD, "2209", null, bankId = null, offeredAt = slice.clock.instant())),
            )
        }
        setup.goTo(SetupStep.BankAccounts)

        setup.selectOffered(offeredId("acct-2209"))
        setup.pairWith(freeId("Reserve Savings"))

        assertNull(ui().naming!!.proposedBank)
        assertEquals("Kestrel Feeds names no Bank · choose one or name a new one", ui().naming!!.note)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("Step 9: a Start Date that is not a date is refused and nothing is stored")
    fun startDateMustBeADate() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)

        pairAndSave("acct-4410", "Checking", "Kestrel Checking", "first of September")

        assertEquals("The Start Date is a date like 2026-09-01, or empty.", ui().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        assertEquals("CHK ••4410", ui().naming?.proposedName)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency")
    @DisplayName("A5: a refused pairing says why: the Budget Account already receives a Bank Account, or another currency")
    fun refusedPairingSaysWhy() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)

        setup.selectOffered(offeredId("acct-5520"))
        setup.pairWith(freeId("Current"))

        assertEquals("Current is in EUR, Aurora Dollar in USD · not paired", ui().message)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A3: No Offered Bank Accounts for a Source")
    @DisplayName("A3: a Source with nothing offered shows the Fetch or Bank Consent that offers its Bank Accounts")
    fun sourceWithoutOfferedBankAccountsShowsTheRemedy() {
        throughBudgets()
        runBlocking { slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }

        setup.goTo(SetupStep.BankAccounts)

        val sources = ui().sources.associateBy { it.name }
        assertEquals("No Bank Accounts offered yet · Fetch Kestrel Feeds (comes with Fetch Transactions)", sources.getValue("Kestrel Feeds").remedy)
        assertEquals(
            "No Bank Accounts offered yet · Grant Bank Consent (comes with Grant Bank Consent)",
            sources.getValue("Aurora Open Banking").remedy,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A6: Offered Bank Account Probably Already Added")
    @DisplayName("A6: an Offered Bank Account that is probably an added one is shown as its proposed match, to confirm in Account Mappings")
    fun probablyAddedBankAccountIsAProposedMatch() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        pairAndSave("acct-4410", "Checking", "Kestrel Checking")
        runBlocking {
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            val added = slice.connectionStore.bankAccounts().single().id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-new", "CHK", "checking", USD, "4410", null, bank, slice.clock.instant(), proposedBankAccountId = added)),
            )
        }

        setup.goTo(SetupStep.BankAccounts)

        assertEquals(
            listOf("CHK ••••4410 · probably Kestrel Checking · confirm in Account Mappings"),
            ui().sources.single { it.name == "Kestrel Feeds" }.proposedMatches,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-009"])
    @DisplayName("A4: a Bank Account of a File Format is added with the type and Bank the Operator chose and the name the Operator enters; nothing is proposed")
    fun bankAccountOfAFileFormat() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        val format = ui().fileFormats.single()
        assertEquals(listOf("Checking", "Savings", "Credit Card", "Other"), ui().types.map { it.text })
        assertEquals(listOf("Kestrel Bank"), ui().knownBanks.map { it.name })

        setup.addFromTransactionFiles(format.first, null, BankChoice.Named("Harbor Health Trust"), null, freeId("Savings"), "Harbor Health Savings", "")
        assertEquals("Choose the Bank Account type · not added", ui().message)
        setup.addFromTransactionFiles(format.first, BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), null, freeId("Savings"), "Harbor Health Savings", "")

        assertEquals("Bank Account Harbor Health Savings added · fetched from the next Fetch", ui().message)
        val stored = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals("Harbor Health Savings", stored.name)
        assertEquals(BankAccountType.Savings, stored.type)
    }

    @Test
    @UseCase(id = "UC-013")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, choosing Budgets shows why it is unavailable")
    fun choosingBudgetsIsUnavailableWhileAPlanAwaitsConfirmation() {
        runBlocking { slice.enterCredentials() }
        setup.load()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        setup.goTo(SetupStep.Budgets)

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", ui().message)
    }
}
