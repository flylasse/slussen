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

    private fun budgetAccountId(name: String) = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    private fun kestrel() = runBlocking { BankChoice.Known(slice.connectionStore.bankNamed("Kestrel Bank").id) }

    private fun card() = ui().card!!

    private fun leftOutReferences() = runBlocking { slice.connectionStore.leftOutBankAccounts().map { it.providerReference } }

    /** Steps 5-9: switches on the Offered Bank Accounts of [references], continues and confirms the stated numbers. */
    private fun bringIn(vararg references: String) {
        setup.goTo(SetupStep.BankAccounts)
        references.forEach { setup.toggleBringIn(offeredId(it)) }
        setup.continueBringIn()
        setup.confirmBringIn()
    }

    /** Steps 10-13 on the current card: [budgetAccount] chosen, the proposed type and Bank, the [name] and [startDate] given. */
    private fun addOnCard(budgetAccount: String, name: String, startDate: String = "") {
        setup.chooseBudgetAccount(budgetAccountId(budgetAccount))
        val card = card()
        setup.addAndContinue(name, card.proposedType, card.proposedBank?.let { BankChoice.Known(it.bankId) }, startDate)
    }

    /** Steps 5-13 for one Offered Bank Account. */
    private fun pairAndSave(reference: String, budgetAccount: String, name: String, startDate: String = "") {
        bringIn(reference)
        addOnCard(budgetAccount, name, startDate)
    }

    private fun offerWithoutCurrency() = runBlocking {
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-9001", "Harbor Joint", "checking", null, "9001", amount(310), null, slice.clock.instant())),
        )
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
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials", requirements = ["NFR-004"])
    @DisplayName("A1: a Budget App the device cannot reach, a lost connection, is shown as not read and the Budgets stay unchanged")
    fun budgetAppTheDeviceCannotReachIsShown() {
        runBlocking { slice.enterCredentials() }
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.ConnectionLost)
        setup.load()

        setup.goTo(SetupStep.Budgets)

        assertEquals("${ExampleData.BUDGET_APP_NAME} could not be read: the connection was lost · Budgets unchanged", ui().message)
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
        pairAndSave("acct-4410", "Checking", "CHK ••4410")
        setup.goTo(SetupStep.Budgets)

        setup.toggleBudget("budget-household")
        setup.saveBudgets()

        assertEquals("Household has mapped Bank Accounts CHK ••4410 · remove their Account Mappings first", ui().message)
        assertEquals(SetupStep.Budgets, ui().step)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-012"])
    @DisplayName("Step 5: the Offered Bank Accounts are grouped by Source and by Bank, each with name, kind, currency, last four digits and balance, none switched on")
    fun stepFiveGroupsBySourceAndBankWithNoneChosen() {
        throughBudgets()
        offer()

        setup.goTo(SetupStep.BankAccounts)

        assertEquals(PairingStage.Choose, ui().stage)
        val feeds = ui().sources.single { it.name == "Kestrel Feeds" }
        assertEquals(listOf("Kestrel Bank"), feeds.banks.map { it.name })
        assertEquals(OfferedLine(offeredId("acct-4410"), "CHK", "checking · USD · ••••4410", "4,140.05", chosen = false), feeds.offered.first())
        assertTrue(feeds.offered.none { it.chosen })
        assertNull(feeds.remedy)
        assertEquals("Bring in 0 · leave out 2", ui().bringInAction)
        assertTrue(ui().leftOut.isEmpty())
        assertFalse(ui().leftOutOpen)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("Step 6: switching an Offered Bank Account on or off updates the action, which states both numbers")
    fun stepSixUpdatesBothNumbers() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)

        setup.toggleBringIn(offeredId("acct-4410"))
        assertEquals("Bring in 1 · leave out 1", ui().bringInAction)
        assertTrue(ui().sources.single { it.name == "Kestrel Feeds" }.offered.first().chosen)
        setup.toggleBringIn(offeredId("acct-4410"))

        assertEquals("Bring in 0 · leave out 2", ui().bringInAction)
        assertTrue(leftOutReferences().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-012"])
    @DisplayName("Steps 7-9: continuing states both numbers by name and leaves nothing out; confirming leaves the rest out and opens the first card")
    fun stepsSevenToNineStateConfirmAndStart() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        setup.toggleBringIn(offeredId("acct-4410"))

        setup.continueBringIn()

        assertEquals(PairingStage.Confirm, ui().stage)
        assertEquals(
            StatementUi(
                question = "Bring in 1, leave out 1?",
                broughtIn = "Brought in, one at a time: CHK.",
                leftOut = "Left out: Aurora Dollar. Not imported and never read; bring them back any time under Not imported or in Account Mappings.",
                confirmAction = "Bring in 1",
            ),
            ui().statement,
        )
        assertTrue(leftOutReferences().isEmpty())
        setup.confirmBringIn()
        assertEquals(PairingStage.Card, ui().stage)
        assertEquals("1 of 1", card().progress)
        assertEquals(listOf("acct-5520"), leftOutReferences())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A13: Go Back Before Confirming", businessRules = ["BR-012"])
    @DisplayName("A13: Back from the stated numbers leaves nothing out and keeps the choice as it was")
    fun backKeepsTheChoice() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        setup.toggleBringIn(offeredId("acct-4410"))
        setup.continueBringIn()

        setup.backToChoice()

        assertEquals(PairingStage.Choose, ui().stage)
        assertNull(ui().statement)
        assertTrue(ui().sources.single { it.name == "Kestrel Feeds" }.offered.first().chosen)
        assertEquals("Bring in 1 · leave out 1", ui().bringInAction)
        assertTrue(leftOutReferences().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A12: Nothing Brought In", businessRules = ["BR-011", "BR-012"])
    @DisplayName("A12: continuing with every switch off states that none is brought in and how many are left out; confirming leaves them all out and shows the summary")
    fun nothingChosenLeavesAllOut() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)

        setup.continueBringIn()
        assertEquals(
            StatementUi(
                question = "Bring in none, leave out 2?",
                broughtIn = "Nothing is added.",
                leftOut = "Every Offered Bank Account shown becomes Not imported and is never read; bring any of them back later.",
                confirmAction = "Leave out 2",
            ),
            ui().statement,
        )
        setup.confirmBringIn()

        assertEquals(PairingStage.Summary, ui().stage)
        assertEquals(SummaryUi(added = emptyList(), skipped = emptyList(), leftOut = listOf("CHK ••4410", "Aurora Dollar ••5520")), ui().summary)
        assertEquals(listOf("acct-4410", "acct-5520"), leftOutReferences())
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-004", "BR-009", "BR-013"])
    @DisplayName("Step 10: the card shows the Bank Account with its progress, the free Budget Accounts of its currency with name and balance, no proposal while more than one is free, and the proposed name, type and Bank")
    fun theCardShowsTheBankAccountAndItsBudgetAccounts() {
        throughBudgets()
        offer()

        bringIn("acct-4410")

        val card = card()
        assertEquals("1 of 1", card.progress)
        assertEquals("CHK", card.title)
        assertEquals("4,140.05", card.balance)
        assertEquals("Kestrel Feeds · Kestrel Bank · checking · USD · ••••4410", card.detail)
        assertEquals("Free Budget Accounts in USD", card.listedHeading)
        assertEquals(listOf("Checking", "Rewards Card", "Reserve Savings"), card.listed.map { it.title })
        assertEquals(FreeBudgetAccountLine(budgetAccountId("Checking"), "Checking", "Household · USD", "4,140.05"), card.listed.first())
        assertEquals("No proposal: more than one Budget Account in USD is free; choose one", card.proposalNote)
        assertNull(card.chosenBudgetAccountId)
        assertEquals("CHK ••4410", card.proposedName)
        assertEquals(BankAccountType.Checking, card.proposedType)
        assertEquals("Kestrel Bank", card.proposedBank?.name)
        assertEquals(listOf("Checking", "Savings", "Credit Card", "Other"), card.types.map { it.text })
        assertEquals(listOf("Kestrel Bank"), card.knownBanks.map { it.name })
        assertEquals("Type and Bank proposed from what Kestrel Feeds states · change them if they are wrong", card.note)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("BR-013: the only free Budget Account of the currency is proposed and chosen on the card, and the card says why")
    fun theOnlyFreeOneIsProposed() {
        throughBudgets()
        offer()
        runBlocking {
            slice.connect.markManuallyKept(budgetAccountId("Rewards Card"))
            slice.connect.markManuallyKept(budgetAccountId("Reserve Savings"))
        }

        bringIn("acct-4410")

        assertEquals(listOf("Checking"), card().listed.map { it.title })
        assertEquals(budgetAccountId("Checking"), card().chosenBudgetAccountId)
        assertEquals("Proposed: the only free Budget Account in USD", card().proposalNote)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-013"])
    @DisplayName("Step 10: the full picker shows every free Budget Account of each served Budget; those of another currency are shown but cannot be chosen")
    fun theFullPickerShowsEveryFreeBudgetAccount() {
        throughBudgets()
        offer()
        bringIn("acct-4410")

        setup.openPicker()

        assertTrue(card().pickerOpen)
        assertEquals(listOf("Household · USD", "Summer House · EUR"), card().picker.map { it.title })
        assertEquals(listOf(true, true, true), card().picker[0].budgetAccounts.map { it.choosable })
        assertEquals(
            FreeBudgetAccountLine(budgetAccountId("Current"), "Current", "EUR · not for a USD Bank Account", "3,402.30", choosable = false),
            card().picker[1].budgetAccounts.first(),
        )
        setup.chooseBudgetAccount(budgetAccountId("Reserve Savings"))
        assertFalse(card().pickerOpen)
        assertEquals(budgetAccountId("Reserve Savings"), card().chosenBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005", "BR-009"])
    @DisplayName("Steps 11-13, 15: the chosen Budget Account, the edited name, type, Bank and Start Date are stored, and the summary lists the Bank Account as added")
    fun pairingNamingAndStartDate() {
        throughBudgets()
        offer()

        pairAndSave("acct-4410", "Checking", "Kestrel Checking", "2026-09-01")

        assertEquals(PairingStage.Summary, ui().stage)
        assertEquals(listOf("Kestrel Checking → Checking ••••4410"), ui().summary!!.added)
        assertEquals(listOf("Aurora Dollar ••5520"), ui().summary!!.leftOut)
        val stored = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals("Kestrel Checking", stored.name)
        assertEquals(BankAccountType.Checking, stored.type)
        assertEquals(LocalDate.parse("2026-09-01"), stored.startDate)
        assertEquals(budgetAccountId("Checking"), stored.mapping?.budgetAccountId)
        val list = BankAccountsViewModel(fixture.services).also { it.load() }.state.value!!.groups
        assertEquals("Household · USD", list.single().title)
        assertEquals("Kestrel Checking", list.single().lines.single().bankAccount)
        assertEquals("Kestrel Bank · Checking · Kestrel Feeds", list.single().lines.single().detail)
        assertEquals("Checking", list.single().lines.single().budgetAccount)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario")
    @DisplayName("Step 14: after Add and continue the next chosen Offered Bank Account follows, with what was added on top")
    fun theNextCardFollows() {
        throughBudgets()
        offer()
        bringIn("acct-4410", "acct-5520")

        addOnCard("Checking", "Kestrel Checking")

        assertEquals(PairingStage.Card, ui().stage)
        assertEquals("2 of 2", card().progress)
        assertEquals("Aurora Dollar", card().title)
        assertEquals("Kestrel Checking added → Checking", ui().message)
        assertEquals(listOf("Rewards Card", "Reserve Savings"), card().listed.map { it.title })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: the Operator changes the type and names a new Bank; without a Bank or a type the Bank Account is not added and the card says why")
    fun typeAndBankAreConfirmedOrChanged() {
        throughBudgets()
        offer()
        bringIn("acct-4410")
        setup.chooseBudgetAccount(budgetAccountId("Checking"))

        setup.addAndContinue("Kestrel Checking", BankAccountType.Savings, null, "")
        assertEquals("Choose a Bank or name a new one · not added", ui().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        setup.addAndContinue("Kestrel Checking", null, kestrel(), "")
        assertEquals("Choose the Bank Account type · not added", ui().message)
        assertEquals(PairingStage.Card, ui().stage)

        setup.addAndContinue("Kestrel Checking", BankAccountType.Savings, BankChoice.Named("Kestrel Savings Bank"), "")

        val stored = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals(BankAccountType.Savings, stored.type)
        assertEquals("Kestrel Savings Bank", runBlocking { slice.connectionStore.banks().single { it.id == stored.bankId }.name })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: when the Data Provider names no Bank, none is proposed and the card asks to choose or name one")
    fun noBankNamedIsShown() {
        throughBudgets()
        runBlocking {
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-2209", "Old Savings", "savings", USD, "2209", null, bankId = null, offeredAt = slice.clock.instant())),
            )
        }

        bringIn("acct-2209")

        assertNull(card().proposedBank)
        assertEquals("Kestrel Feeds names no Bank · choose one or name a new one", card().note)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("Step 11: a Start Date that is not a date is refused, nothing is stored and the card stays open")
    fun startDateMustBeADate() {
        throughBudgets()
        offer()

        pairAndSave("acct-4410", "Checking", "Kestrel Checking", "first of September")

        assertEquals("The Start Date is a date like 2026-09-01, or empty.", ui().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        assertEquals("CHK ••4410", card().proposedName)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("Step 11: without a Budget Account chosen nothing is added; the proposal is never applied without the Operator")
    fun aBudgetAccountMustBeChosen() {
        throughBudgets()
        offer()
        bringIn("acct-4410")

        setup.addAndContinue("Kestrel Checking", BankAccountType.Checking, kestrel(), "")

        assertEquals("Choose a Budget Account · not added", ui().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency")
    @DisplayName("A5: a Budget Account that meanwhile receives another Bank Account, or one of another currency, is refused with why and the card stays open to choose again")
    fun refusedPairingSaysWhy() {
        throughBudgets()
        offer()
        bringIn("acct-4410", "acct-5520")
        setup.chooseBudgetAccount(budgetAccountId("Checking"))
        runBlocking {
            slice.connect.addFromTransactionFiles(
                slice.connect.fileFormats().single().fileFormatId, BankAccountType.Checking, BankChoice.Named("Harbor Bank"), null,
                budgetAccountId("Checking"), "Harbor Joint", null,
            )
        }
        val card = card()

        setup.addAndContinue("Kestrel Checking", card.proposedType, kestrel(), "")
        assertEquals("Checking already receives Harbor Joint · not added", ui().message)
        assertEquals("1 of 2", card().progress)
        setup.openPicker()
        setup.chooseBudgetAccount(budgetAccountId("Current"))
        setup.addAndContinue("Kestrel Checking", card.proposedType, kestrel(), "")

        assertEquals("Current is in EUR, Kestrel Checking in USD · not added", ui().message)
        assertEquals(PairingStage.Card, ui().stage)
        assertEquals(1, runBlocking { slice.connectionStore.bankAccounts().size })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A9: Mark a Budget Account Manually Kept", businessRules = ["BR-014"])
    @DisplayName("A9: Kept by hand asks first; Cancel changes nothing, Keep by hand marks it, and it leaves the list of the card")
    fun aBudgetAccountIsKeptByHandFromTheCard() {
        throughBudgets()
        offer()
        bringIn("acct-4410")

        setup.askKeepByHand(budgetAccountId("Reserve Savings"))
        assertEquals(
            KeepQuestion(
                budgetAccountId("Reserve Savings"),
                "Keep Reserve Savings by hand?",
                "Reserve Savings (Household · USD) becomes a Manually Kept Account: you keep it in ${ExampleData.BUDGET_APP_NAME} yourself, " +
                    "Slussen never writes to it and no longer offers it for pairing. Unmark it any time in Account Mappings. " +
                    "Recorded in the Decision Journal.",
            ),
            card().keepQuestion,
        )
        setup.cancelKeepByHand()
        assertNull(card().keepQuestion)
        assertEquals(3, card().listed.size)
        setup.askKeepByHand(budgetAccountId("Reserve Savings"))
        setup.confirmKeepByHand()

        assertEquals(listOf("Checking", "Rewards Card"), card().listed.map { it.title })
        assertNull(card().keepQuestion)
        assertTrue(runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Reserve Savings" }.manuallyKept })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A8: Skip for Now", businessRules = ["BR-012"])
    @DisplayName("A8: Skip for now adds nothing and leaves nothing out; the summary lists it as skipped and it is offered again at step 5")
    fun skipForNow() {
        throughBudgets()
        offer()
        bringIn("acct-4410", "acct-5520")

        setup.skipForNow()
        assertEquals("2 of 2", card().progress)
        addOnCard("Reserve Savings", "Aurora Dollar")

        assertEquals(SummaryUi(added = listOf("Aurora Dollar → Reserve Savings ••••5520"), skipped = listOf("CHK ••••4410 · offered again next time"), leftOut = emptyList()), ui().summary)
        setup.done()
        assertEquals(PairingStage.Choose, ui().stage)
        assertEquals(listOf("CHK"), ui().sources.single { it.name == "Kestrel Feeds" }.offered.map { it.title })
        assertTrue(leftOutReferences().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A10: Bring Back a Left-Out Bank Account", businessRules = ["BR-011"])
    @DisplayName("A10: Not imported, opened, lists the Left-Out Bank Accounts with their Source and date; Bring back offers one again, switched off")
    fun bringBackFromNotImported() {
        throughBudgets()
        offer()
        bringIn()
        setup.done()
        assertEquals(2, ui().leftOut.size)

        setup.toggleLeftOut()
        assertTrue(ui().leftOutOpen)
        assertEquals(LeftOutLine(ui().leftOut[1].leftOutBankAccountId, "Aurora Dollar ••5520", "Kestrel Feeds · left out 2026-10-15"), ui().leftOut[1])
        setup.bringBack(ui().leftOut[1].leftOutBankAccountId)

        assertEquals("Aurora Dollar ••5520 brought back · switch it on to bring it in", ui().message)
        assertEquals(listOf("Aurora Dollar"), ui().sources.single { it.name == "Kestrel Feeds" }.offered.map { it.title })
        assertFalse(ui().sources.single { it.name == "Kestrel Feeds" }.offered.single().chosen)
        assertEquals(listOf("CHK ••4410"), ui().leftOut.map { it.title })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A11: No Offered Bank Account Left to Decide")
    @DisplayName("A11: when every Offered Bank Account is added or left out, the step says there is nothing new to bring in")
    fun nothingNewToBringIn() {
        throughBudgets()
        offer()

        pairAndSave("acct-4410", "Checking", "Kestrel Checking")
        setup.done()

        assertEquals("Nothing new to bring in · every Bank Account Kestrel Feeds offers is added or left out", ui().nothingLeft)
        assertEquals(listOf("Aurora Dollar ••5520"), ui().leftOut.map { it.title })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A3: No Offered Bank Accounts for a Source")
    @DisplayName("A3: a Source with nothing offered shows the Fetch or Bank Consent that offers its Bank Accounts")
    fun sourceWithoutOfferedBankAccountsShowsTheRemedy() {
        throughBudgets()
        runBlocking { slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }

        setup.goTo(SetupStep.BankAccounts)

        val sources = ui().sources.associateBy { it.name }
        assertEquals("No Bank Accounts offered yet", sources.getValue("Kestrel Feeds").remedy)
        assertEquals("Fetch Kestrel Feeds", sources.getValue("Kestrel Feeds").fetchAction)
        assertNull(sources.getValue("Aurora Open Banking").fetchAction)
        assertEquals(
            "No Bank Accounts offered yet · Grant Bank Consent (comes with Grant Bank Consent)",
            sources.getValue("Aurora Open Banking").remedy,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A6: Offered Bank Account Probably Already Added")
    @DisplayName("A6: an Offered Bank Account that is probably an added one is shown apart, without a switch, to confirm in Account Mappings and never left out")
    fun probablyAddedBankAccountIsAProposedMatch() {
        throughBudgets()
        offer()
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

        val feeds = ui().sources.single { it.name == "Kestrel Feeds" }
        assertEquals(listOf("CHK ••••4410 · probably Kestrel Checking · confirm in Account Mappings · not left out"), feeds.proposedMatches)
        assertTrue(feeds.offered.isEmpty())
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
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-011"])
    @DisplayName("A4, BR-006: the balance can come from any Offered Bank Account, also a Left-Out one, which is then brought back")
    fun aLeftOutBalanceSourceIsBroughtBack() {
        throughBudgets()
        offer()
        bringIn("acct-4410")
        setup.done()
        val format = ui().fileFormats.single()

        assertEquals(listOf("Kestrel Feeds · CHK ••••4410", "Kestrel Feeds · Aurora Dollar ••••5520 · not imported"), ui().balanceSources.map { it.title })
        setup.addFromTransactionFiles(format.first, BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), offeredId("acct-5520"), freeId("Reserve Savings"), "Harbor Health Savings", "")

        assertEquals("Bank Account Harbor Health Savings added · fetched from the next Fetch", ui().message)
        assertTrue(leftOutReferences().isEmpty())
        assertTrue(ui().leftOut.isEmpty())
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

    @Test
    @UseCase(id = "UC-013", scenario = "A3: No Offered Bank Accounts for a Source")
    @DisplayName("A3: starting the Fetch from the pairing step stores the Offered Bank Accounts and pairing continues at step 5 with them")
    fun fetchFromThePairingStepOffersTheBankAccounts() {
        throughBudgets()
        setup.goTo(SetupStep.BankAccounts)
        slice.feeds.fetchAnswer = {
            slussen.pluginapi.provider.FetchResponse(
                slussen.pluginapi.provider.SourceOutcome.Completed,
                emptyList(),
                listOf(
                    slussen.pluginapi.provider.DeliveredBankAccount(
                        "acct-4410", "Kestrel Bank", "CHK", "checking", USD, null,
                        slussen.pluginapi.provider.BankAccountResult.Read(emptyList(), amount(4140, 50)),
                    ),
                ),
            )
        }

        setup.fetch(slice.feedsKey)

        val feeds = ui().sources.single { it.name == "Kestrel Feeds" }
        assertEquals(listOf("CHK"), feeds.offered.map { it.title })
        assertEquals("4,140.05", feeds.offered.single().balance)
        assertNull(feeds.fetchAction)
        assertEquals("Kestrel Feeds · Fetched 14:00 · Bank Accounts offered: 1 · not added: 1", ui().message)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010", "BR-013"])
    @DisplayName("BR-010: when the Offered Bank Account states no currency, every free Budget Account is listed and step 11 asks for the currency, the chosen Budget Account's proposed, and the Bank Account is added with it")
    fun currencyIsStatedAtStep11() {
        throughBudgets()
        offerWithoutCurrency()
        bringIn("acct-9001")
        assertEquals("Free Budget Accounts", card().listedHeading)
        assertEquals(6, card().listed.size)

        setup.chooseBudgetAccount(budgetAccountId("Checking"))

        val card = card()
        assertEquals("USD", card.currency)
        assertFalse(card.currencyStatedBySource)
        assertEquals("Kestrel Feeds states no currency · state it, the Budget Account's by default", card.currencyNote)
        setup.addAndContinue("Harbor Joint", card.proposedType, BankChoice.Named("Harbor Bank"), "", "USD")
        assertEquals(listOf("Harbor Joint → Checking ••••9001"), ui().summary!!.added)
        assertEquals(USD, runBlocking { slice.connectionStore.bankAccounts().single().currency })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-010"])
    @DisplayName("A5, BR-010: a stated currency other than the Budget's is refused with why; a currency that is no ISO code is not accepted")
    fun statedCurrencyIsChecked() {
        throughBudgets()
        offerWithoutCurrency()
        bringIn("acct-9001")
        setup.chooseBudgetAccount(budgetAccountId("Checking"))
        val card = card()

        setup.addAndContinue("Harbor Joint", card.proposedType, BankChoice.Named("Harbor Bank"), "", "EUR")
        assertEquals("Checking is in USD, Harbor Joint in EUR · not added", ui().message)

        setup.addAndContinue("Harbor Joint", card.proposedType, BankChoice.Named("Harbor Bank"), "", "dollars")
        assertEquals("The currency is an ISO 4217 code of three capital letters, like USD · not added", ui().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: a currency the Data Provider states is shown as stated by it")
    fun currencyStatedBySourceIsShown() {
        throughBudgets()
        offer()
        bringIn("acct-4410")

        assertEquals("USD", card().currency)
        assertTrue(card().currencyStatedBySource)
        assertEquals("Stated by Kestrel Feeds", card().currencyNote)
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-012"])
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, confirming the stated numbers shows why it is unavailable and leaves nothing out")
    fun leavingOutIsUnavailableWhileAPlanAwaitsConfirmation() {
        throughBudgets()
        offer()
        setup.goTo(SetupStep.BankAccounts)
        setup.continueBringIn()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        setup.confirmBringIn()

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", ui().message)
        assertEquals(PairingStage.Choose, ui().stage)
        assertTrue(leftOutReferences().isEmpty())
    }
}
