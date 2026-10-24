package slussen.app.ui

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.fileformat.BalanceLine
import slussen.app.ui.fileformat.FileAccountLine
import slussen.app.ui.fileformat.FileFormatViewModel
import slussen.app.ui.mappings.AccountMappingsViewModel
import slussen.app.ui.mappings.FreeBudgetAccountLine
import slussen.app.ui.mappings.MappingsStage
import slussen.app.ui.mappings.SummaryUi
import slussen.app.ui.mappings.ToMapLine
import slussen.app.ui.service.BudgetAccountLine
import slussen.app.ui.service.BudgetChoiceLine
import slussen.app.ui.service.OfferedLineUi
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.service.SnackAction
import slussen.app.ui.service.SnackUi
import slussen.app.ui.settings.EntryLine
import slussen.app.ui.settings.EntryPage
import slussen.app.ui.settings.MappingsEntryLine
import slussen.app.ui.settings.SettingsViewModel
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.ServiceKey
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * Connect Budgets and Bank Accounts (UC-013) on the screens of S-40: the top of Settings, the Budget App's page with the
 * Budgets and "Receives from Slussen", each Data Provider's page with its import switches, the Account Mappings with the
 * Bank Accounts to map and the card of one Bank Account at a time, and the File Format's page.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-013: Connect Budgets and Bank Accounts, the screens")
class UC013ConnectBudgetsAndBankAccountsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val budgetAppName = ExampleData.BUDGET_APP_NAME

    private fun settings() = SettingsViewModel(fixture.services).also { it.load() }.state.value!!

    private val budgetPage by lazy { ServicePageViewModel(fixture.services, ServiceKey.budgetApp).also { it.load() } }
    private val feedsPage by lazy { ServicePageViewModel(fixture.services, slice.feedsKey).also { it.load() } }
    private val mappings by lazy { AccountMappingsViewModel(fixture.services).also { it.load() } }
    private val fileFormat by lazy { FileFormatViewModel(fixture.services, slice.plugins.fileFormats.single().id).also { it.load() } }

    private fun ServicePageViewModel.ui() = state.value!!

    private fun mappingsUi() = mappings.state.value!!

    private fun card() = mappingsUi().card!!

    private fun fileUi() = fileFormat.state.value!!

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

    /** UC-014 and steps 2-4: credentials accepted, Household and Summer House served. */
    private fun throughBudgets() {
        runBlocking { slice.enterCredentials() }
        budgetPage.chooseBudgets()
        budgetPage.toggleBudget("budget-household")
        budgetPage.toggleBudget("budget-summer")
        budgetPage.serveBudgets()
    }

    private fun offeredId(reference: String) = runBlocking { slice.offeredStore.offered().single { it.providerReference == reference }.id }

    private fun budgetAccountId(name: String) = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    private fun kestrel() = runBlocking { BankChoice.Known(slice.connectionStore.bankNamed("Kestrel Bank").id) }

    private fun leftOutReferences() = runBlocking { slice.connectionStore.leftOutBankAccounts().map { it.providerReference } }

    private fun offeredLine(title: String) = feedsPage.ui().offered!!.banks.flatMap { it.rows }.single { it.title == title }

    /** Steps 10-13 on the current card: [budgetAccount] chosen, the proposed type and Bank, the [name] and [startDate] given. */
    private fun addOnCard(budgetAccount: String, name: String, startDate: String = "") {
        mappings.chooseBudgetAccount(budgetAccountId(budgetAccount))
        val card = card()
        mappings.addAndContinue(name, card.proposedType, card.proposedBank?.let { BankChoice.Known(it.bankId) }, startDate)
    }

    private fun offerWithoutCurrency() = runBlocking {
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-9001", "Harbor Joint", "checking", null, "9001", amount(310), null, slice.clock.instant())),
        )
    }

    // ---- Step 1, BR-008, BR-015: the top of Settings ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-015"])
    @DisplayName("Step 1: on the first run Settings lists every service and the File Format, none set up, names the Budget App's credentials as what comes next, and the Account Mappings say why they are not yet available")
    fun firstRunSettings() {
        val ui = settings()

        assertEquals(
            listOf(
                EntryLine(EntryPage.Service(ServiceKey.budgetApp), budgetAppName, "Budget App", "Not set up · next: enter the $budgetAppName credentials", isNext = true),
                EntryLine(EntryPage.Service(slice.feedsKey), "Kestrel Feeds", "Data Provider", "Not set up", isNext = false),
                EntryLine(EntryPage.Service(slice.openBankingKey), "Aurora Open Banking", "Data Provider · needs Bank Consent", "Not set up", isNext = false),
                EntryLine(EntryPage.Service(slice.wrenKey), "Wren Feeds", "Data Provider", "Not set up", isNext = false),
                EntryLine(EntryPage.FileFormat(slice.plugins.fileFormats.single().id), "Harbor Health statement", "File Format · Transaction Files", "No Bank Account", isNext = false),
            ),
            ui.entries,
        )
        assertEquals(MappingsEntryLine(available = false, status = "Not yet: enter the $budgetAppName credentials first", isNext = false), ui.accountMappings)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-015"])
    @DisplayName("BR-008, BR-015: as setting up goes on, Settings names what comes next on the first entry that still needs it, and the Account Mappings become available with the number to map")
    fun settingsFollowTheOrderOfSettingUp() {
        runBlocking { slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN)) }
        assertEquals("Connected · next: choose the Budgets", settings().entries.first().status)
        assertEquals("Not yet: choose a Budget in $budgetAppName first", settings().accountMappings.status)
        budgetPage.chooseBudgets()
        budgetPage.toggleBudget("budget-household")
        budgetPage.serveBudgets()
        assertEquals("1 Budget", settings().entries.first().status)
        assertEquals("Not set up · next: enter its credentials", settings().entries[1].status)
        assertEquals("Not yet: import a Bank Account from a Data Provider", settings().accountMappings.status)
        runBlocking { slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        assertEquals("Nothing offered yet · next: fetch it", settings().entries[1].status)
        offer()

        val ui = settings()

        assertEquals("2 of 2 imported", ui.entries[1].status)
        assertFalse(ui.entries[1].isNext)
        assertEquals(MappingsEntryLine(available = true, status = "2 to map · next", isNext = true), ui.accountMappings)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A7: Budget App Credentials Not Entered", businessRules = ["BR-015"])
    @DisplayName("A7: without accepted Budget App credentials the Budget App's page says the Budgets can be chosen once they are accepted, without contacting it, and the Account Mappings wait")
    fun budgetsWaitForTheBudgetAppCredentials() {
        assertEquals("Budgets can be chosen once the $budgetAppName credentials are accepted.", budgetPage.ui().budgets!!.note)

        budgetPage.chooseBudgets()

        assertEquals("Budgets can be chosen once the $budgetAppName credentials are accepted.", budgetPage.ui().message)
        assertNull(budgetPage.ui().budgets!!.choosing)
        assertEquals(0, slice.budgetApp.reader.budgetReads)
        assertFalse(settings().accountMappings.available)
    }

    // ---- Steps 2-4, A1, A2, A9, BR-014: the Budget App's page -------------------------------------------------------

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Steps 2-3: once the Budget App accepts the token, its page lists the Budgets it returns, none served yet, to tick")
    fun budgetsComeNextOnTheSamePage() {
        budgetPage.startEntering()

        budgetPage.save(mapOf("token" to ExampleData.BUDGET_APP_TOKEN))

        assertEquals(
            listOf(
                BudgetChoiceLine("budget-household", "Household", "USD", false),
                BudgetChoiceLine("budget-summer", "Summer House", "EUR", false),
                BudgetChoiceLine("budget-care", "Care Fund", "USD", false),
            ),
            budgetPage.ui().budgets!!.choosing,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-014"])
    @DisplayName("Steps 3-4, BR-014: the ticked Budgets are served; under each, its Budget Accounts with their balances, each with Receives from Slussen on")
    fun servedBudgetsShowTheirBudgetAccounts() {
        throughBudgets()

        val budgets = budgetPage.ui().budgets!!
        assertNull(budgets.choosing)
        assertEquals(listOf("Household · USD", "Summer House · EUR"), budgets.inUse)
        assertEquals(listOf("Household · USD", "Summer House · EUR"), budgets.budgetAccounts.map { it.title })
        assertEquals(
            BudgetAccountLine(budgetAccountId("Checking"), "Checking", "receives from Slussen", "4,140.05", receives = true),
            budgets.budgetAccounts.first().lines.first(),
        )
        assertEquals(listOf("Household", "Summer House"), runBlocking { slice.connectionStore.budgets().filter { it.served }.map { it.name } })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials")
    @DisplayName("A1: a Budget App that cannot be read, also one the device cannot reach, is shown as such and the Budgets stay unchanged")
    fun unreadableBudgetAppIsShown() {
        runBlocking { slice.enterCredentials() }
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)

        budgetPage.chooseBudgets()
        assertEquals("$budgetAppName could not be read: no answer within the request timeout, an Operator Setting · Budgets unchanged", budgetPage.ui().message)
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.ConnectionLost)
        budgetPage.chooseBudgets()

        assertEquals("$budgetAppName could not be read: the connection was lost · Budgets unchanged", budgetPage.ui().message)
        assertNull(budgetPage.ui().budgets!!.choosing)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials")
    @DisplayName("A1: refused credentials show the Blocked Reason credentials refused with the action that enters them")
    fun refusedCredentialsAreShown() {
        runBlocking { slice.enterCredentials() }
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused

        budgetPage.chooseBudgets()

        assertEquals("Blocked: credentials refused · Enter credentials", budgetPage.ui().message)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A2: Stop Serving a Budget That Has Mapped Bank Accounts")
    @DisplayName("A2: leaving out a Budget with mapped Bank Accounts is refused, naming them")
    fun stopServingAMappedBudgetIsRefused() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))
        addOnCard("Checking", "CHK ••4410")
        budgetPage.chooseBudgets()

        budgetPage.toggleBudget("budget-household")
        budgetPage.serveBudgets()

        assertEquals("Household has mapped Bank Accounts CHK ••4410 · remove their Account Mappings first", budgetPage.ui().message)
        assertTrue(runBlocking { slice.connectionStore.budgets().single { it.name == "Household" }.served })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A9: Mark or Unmark a Budget Account Manually Kept", businessRules = ["BR-014"])
    @DisplayName("A9: Receives from Slussen switched off keeps a Budget Account by hand at once, with no dialog, and Undo switches it on again; a mapped one shows its Account Mapping instead of a switch")
    fun aBudgetAccountIsKeptByHandWithItsSwitch() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))
        addOnCard("Checking", "Kestrel Checking")
        budgetPage.load()

        budgetPage.setReceives(budgetAccountId("Reserve Savings"), receives = false)

        val lines = budgetPage.ui().budgets!!.budgetAccounts.first().lines
        assertEquals(BudgetAccountLine(budgetAccountId("Checking"), "Checking", "receives from Kestrel Checking", "4,140.05", receives = null), lines[0])
        assertEquals(BudgetAccountLine(budgetAccountId("Reserve Savings"), "Reserve Savings", "kept by hand · Manually Kept Account", "12,000.00", receives = false), lines[2])
        assertEquals(SnackUi("Reserve Savings kept by hand · not offered for pairing", SnackAction.Undo), budgetPage.ui().snack)
        assertTrue(runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Reserve Savings" }.manuallyKept })
        budgetPage.undo()

        assertEquals(true, budgetPage.ui().budgets!!.budgetAccounts.first().lines[2].receives)
        assertEquals(SnackUi("Reserve Savings receives from Slussen again · offered for pairing", null), budgetPage.ui().snack)
    }

    // ---- Steps 5-7, A3, A6, A10, A11, A12, BR-002, BR-012: a Data Provider's page ------------------------------------

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-012"])
    @DisplayName("Step 5: a Data Provider's page shows its Offered Bank Accounts grouped by Bank, each with name, kind, currency, last four digits and balance, and its import switch on by default")
    fun dataProviderPageShowsImportSwitchesOnByDefault() {
        throughBudgets()
        offer()

        val offered = feedsPage.ui().offered!!

        assertEquals("Offered Bank Accounts · import is on by default", offered.heading)
        assertEquals(listOf("Kestrel Bank"), offered.banks.map { it.name })
        assertEquals(OfferedLineUi(offeredId("acct-4410"), null, "CHK", "checking · USD · ••4410 · to map", "4,140.05", importOn = true, trailing = null), offered.banks.single().rows.first())
        assertTrue(offered.banks.single().rows.all { it.importOn == true })
        assertNull(offered.emptyText)
        assertNull(offered.nothingNew)
        assertEquals("Imported Bank Accounts are mapped in Account Mappings. Nothing is fetched for them until they are added.", offered.footnote)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-012"])
    @DisplayName("Steps 6-7: switching one off leaves it out at once, with no statement and no confirmation; it shows as not imported, and Undo imports it again")
    fun switchingOffLeavesOutAtOnce() {
        throughBudgets()
        offer()

        feedsPage.switchImport(offeredLine("Aurora Dollar"), on = false)

        assertEquals(listOf("acct-5520"), leftOutReferences())
        val dollar = offeredLine("Aurora Dollar")
        assertEquals(false, dollar.importOn)
        assertEquals("savings · USD · ••5520 · not imported", dollar.detail)
        assertEquals(SnackUi("Aurora Dollar ••5520 not imported · never read", SnackAction.Undo), feedsPage.ui().snack)
        feedsPage.undo()

        assertTrue(leftOutReferences().isEmpty())
        assertEquals(true, offeredLine("Aurora Dollar").importOn)
        assertEquals(SnackUi("Aurora Dollar ••5520 imported · to map in Account Mappings", SnackAction.Map), feedsPage.ui().snack)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A10: Bring Back a Left-Out Bank Account", businessRules = ["BR-011"])
    @DisplayName("A10: switching the import of a Left-Out Bank Account on again brings it back; it is to map, and the page offers Map")
    fun switchingOnBringsBack() {
        throughBudgets()
        offer()
        runBlocking { slice.connect.switchImportOff(offeredId("acct-5520")) }
        feedsPage.load()

        feedsPage.switchImport(offeredLine("Aurora Dollar"), on = true)

        assertTrue(leftOutReferences().isEmpty())
        assertEquals(SnackUi("Aurora Dollar ••5520 imported · to map in Account Mappings", SnackAction.Map), feedsPage.ui().snack)
        assertEquals("savings · USD · ••5520 · to map", offeredLine("Aurora Dollar").detail)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A12: Nothing Brought In", businessRules = ["BR-011", "BR-015"])
    @DisplayName("A12: switching every one off leaves each out at once; Settings shows that the Account Mappings wait for an imported Bank Account")
    fun switchingEveryOneOff() {
        throughBudgets()
        offer()

        feedsPage.switchImport(offeredLine("CHK"), on = false)
        feedsPage.switchImport(offeredLine("Aurora Dollar"), on = false)

        assertEquals(listOf("acct-4410", "acct-5520"), leftOutReferences())
        assertEquals(MappingsEntryLine(available = false, status = "Not yet: import a Bank Account from a Data Provider", isNext = false), settings().accountMappings)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A3: No Offered Bank Accounts for a Source")
    @DisplayName("A3 (P9-05): a Data Provider that offers nothing yet says so in one sentence, with its Fetch for one that needs no Bank Consent")
    fun nothingOfferedYetIsOneSentence() {
        throughBudgets()
        runBlocking { slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        val openBankingPage = ServicePageViewModel(fixture.services, slice.openBankingKey).also { it.load() }

        assertEquals(
            "None yet · a Fetch lists the Bank Accounts Kestrel Feeds offers; no transactions are fetched until a Bank Account is added",
            feedsPage.ui().offered!!.emptyText,
        )
        assertTrue(feedsPage.ui().canFetch)
        // P9-13: the same sentence says plainly that Bank Consents are not in this build yet.
        assertEquals(
            "None yet · a Bank Consent of a Bank lists its Bank Accounts here · Bank Consents are not in this build yet",
            openBankingPage.ui().offered!!.emptyText,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A3: No Offered Bank Accounts for a Source")
    @DisplayName("A3: the Fetch on a Data Provider's page stores the Offered Bank Accounts, which then show with their import switch on")
    fun fetchFromThePageOffersTheBankAccounts() {
        throughBudgets()
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf(DeliveredBankAccount("acct-4410", "Kestrel Bank", "CHK", "checking", USD, null, BankAccountResult.Read(emptyList(), amount(4140, 50)))),
            )
        }

        feedsPage.fetch()

        assertEquals("Fetched 14:00", feedsPage.ui().fetchResult!!.text)
        assertEquals("Bank Accounts offered: 1 · not added: 1", feedsPage.ui().fetchResult!!.detail)
        assertEquals(listOf("CHK"), feedsPage.ui().offered!!.banks.flatMap { it.rows }.map { it.title })
        assertEquals(true, offeredLine("CHK").importOn)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A6: Offered Bank Account Probably Already Added", businessRules = ["BR-012"])
    @DisplayName("A6: an Offered Bank Account that is probably an added one has no import switch and goes to confirmation in Account Mappings; an added one shows where it goes")
    fun probablyAddedAndAddedHaveNoSwitch() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))
        addOnCard("Checking", "Kestrel Checking")
        runBlocking {
            val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
            val added = slice.connectionStore.bankAccounts().single().id
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(
                    ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, bank, slice.clock.instant()),
                    ExampleData.offered("acct-new", "Joint", "savings", USD, "4471", null, bank, slice.clock.instant(), proposedBankAccountId = added),
                ),
            )
        }
        feedsPage.load()

        assertEquals(null, offeredLine("CHK").importOn)
        assertEquals("Added → Checking", offeredLine("CHK").trailing)
        assertEquals(null, offeredLine("Joint").importOn)
        assertEquals("probably Kestrel Checking · confirm in Account Mappings", offeredLine("Joint").trailing)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A11: No Offered Bank Account Left to Decide")
    @DisplayName("A11: when every Offered Bank Account is added or not imported, the page says there is nothing new to import")
    fun nothingNewToImport() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))
        addOnCard("Checking", "Kestrel Checking")
        feedsPage.switchImport(offeredLine("Aurora Dollar"), on = false)

        assertEquals("Nothing new to import · every Offered Bank Account is added, not imported or reports a balance", feedsPage.ui().offered!!.nothingNew)
    }

    // ---- Steps 8-15, A5, A8, BR-004, BR-009, BR-010, BR-013: the Account Mappings ------------------------------------

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-012"])
    @DisplayName("Step 8: the Account Mappings list the Bank Accounts to map first, one line each with name, last four digits, Source, currency and balance")
    fun toMapIsOneLineEach() {
        throughBudgets()
        offer()

        val ui = mappingsUi()

        assertEquals(MappingsStage.List, ui.stage)
        assertEquals("To map · imported, not yet added · 2", ui.toMapTitle)
        assertEquals(ToMapLine(offeredId("acct-4410"), "CHK", "••4410", "Kestrel Feeds · USD", "4,140.05"), ui.toMap.first())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-004", "BR-009", "BR-013"])
    @DisplayName("Steps 9-10: one Bank Account opens its card with its progress, the free Budget Accounts of its currency with name and balance, no proposal while more than one is free, and the proposed name, type and Bank")
    fun theCardShowsTheBankAccountAndItsBudgetAccounts() {
        throughBudgets()
        offer()

        mappings.mapOne(offeredId("acct-4410"))

        val card = card()
        assertEquals(MappingsStage.Card, mappingsUi().stage)
        assertEquals("1 of 1", card.progress)
        assertEquals("CHK", card.title)
        assertEquals("4,140.05", card.balance)
        assertEquals("Kestrel Feeds · Kestrel Bank · checking · USD · ••4410", card.detail)
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
        budgetPage.setReceives(budgetAccountId("Rewards Card"), receives = false)
        budgetPage.setReceives(budgetAccountId("Reserve Savings"), receives = false)

        mappings.mapOne(offeredId("acct-4410"))

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
        mappings.mapOne(offeredId("acct-4410"))

        mappings.openPicker()

        assertTrue(card().pickerOpen)
        assertEquals(listOf("Household · USD", "Summer House · EUR"), card().picker.map { it.title })
        assertEquals(listOf(true, true, true), card().picker[0].budgetAccounts.map { it.choosable })
        assertEquals(
            FreeBudgetAccountLine(budgetAccountId("Current"), "Current", "EUR · not for a USD Bank Account", "3,402.30", choosable = false),
            card().picker[1].budgetAccounts.first(),
        )
        mappings.chooseBudgetAccount(budgetAccountId("Reserve Savings"))
        assertFalse(card().pickerOpen)
        assertEquals(budgetAccountId("Reserve Savings"), card().chosenBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005", "BR-009"])
    @DisplayName("Steps 11-13, 15: the chosen Budget Account, the edited name, type, Bank and Start Date are stored; the summary lists the Bank Account as added, and the Account Mappings show it by Budget")
    fun pairingNamingAndStartDate() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))

        addOnCard("Checking", "Kestrel Checking", "2026-09-01")

        assertEquals(MappingsStage.Summary, mappingsUi().stage)
        assertEquals(SummaryUi(added = listOf("Kestrel Checking → Checking ••4410"), skipped = emptyList()), mappingsUi().summary)
        val stored = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals("Kestrel Checking", stored.name)
        assertEquals(BankAccountType.Checking, stored.type)
        assertEquals(LocalDate.parse("2026-09-01"), stored.startDate)
        assertEquals(budgetAccountId("Checking"), stored.mapping?.budgetAccountId)
        mappings.done()
        val groups = mappingsUi().groups
        assertEquals(MappingsStage.List, mappingsUi().stage)
        assertEquals("Household · USD", groups.single().title)
        assertEquals("Kestrel Checking", groups.single().lines.single().bankAccount)
        assertEquals("Kestrel Bank · Checking · Kestrel Feeds", groups.single().lines.single().detail)
        assertEquals("Checking", groups.single().lines.single().budgetAccount)
        assertEquals(listOf("Aurora Dollar"), mappingsUi().toMap.map { it.title })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario")
    @DisplayName("Steps 9, 14: Map all takes the Bank Accounts to map one at a time; after Add and continue the next follows, with what was added on top")
    fun mapAllTakesThemOneAtATime() {
        throughBudgets()
        offer()
        mappings.mapAll()
        assertEquals("1 of 2", card().progress)

        addOnCard("Checking", "Kestrel Checking")

        assertEquals(MappingsStage.Card, mappingsUi().stage)
        assertEquals("2 of 2", card().progress)
        assertEquals("Aurora Dollar", card().title)
        assertEquals("Kestrel Checking added → Checking", mappingsUi().message)
        assertEquals(listOf("Rewards Card", "Reserve Savings"), card().listed.map { it.title })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: the Operator changes the type and names a new Bank; without a Bank or a type the Bank Account is not added and the card says why")
    fun typeAndBankAreConfirmedOrChanged() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))
        mappings.chooseBudgetAccount(budgetAccountId("Checking"))

        mappings.addAndContinue("Kestrel Checking", BankAccountType.Savings, null, "")
        assertEquals("Choose a Bank or name a new one · not added", mappingsUi().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        mappings.addAndContinue("Kestrel Checking", null, kestrel(), "")
        assertEquals("Choose the Bank Account type · not added", mappingsUi().message)
        assertEquals(MappingsStage.Card, mappingsUi().stage)

        mappings.addAndContinue("Kestrel Checking", BankAccountType.Savings, BankChoice.Named("Kestrel Savings Bank"), "")

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

        mappings.mapOne(offeredId("acct-2209"))

        assertNull(card().proposedBank)
        assertEquals("Kestrel Feeds names no Bank · choose one or name a new one", card().note)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004 (P9-04): a name that already ends with its last four digits is proposed as it is, without them twice")
    fun lastFourDigitsAreNotAddedTwice() {
        throughBudgets()
        runBlocking {
            slice.offeredStore.replaceOffered(
                slice.feedsId,
                null,
                listOf(ExampleData.offered("acct-9034", "Pinecrest Business Visa-9034", "credit card", USD, "9034", amount(-88, 100), null, slice.clock.instant())),
            )
        }

        mappings.mapOne(offeredId("acct-9034"))

        assertEquals("Pinecrest Business Visa-9034", card().proposedName)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("Step 11: a Start Date that is not a date is refused, nothing is stored and the card stays open")
    fun startDateMustBeADate() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))

        addOnCard("Checking", "Kestrel Checking", "first of September")

        assertEquals("The Start Date is a date like 2026-09-01, or empty.", mappingsUi().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        assertEquals("CHK ••4410", card().proposedName)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("Step 11: without a Budget Account chosen nothing is added; the proposal is never applied without the Operator")
    fun aBudgetAccountMustBeChosen() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))

        mappings.addAndContinue("Kestrel Checking", BankAccountType.Checking, kestrel(), "")

        assertEquals("Choose a Budget Account · not added", mappingsUi().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency")
    @DisplayName("A5: a Budget Account that meanwhile receives another Bank Account, or one of another currency, is refused with why and the card stays open to choose again")
    fun refusedPairingSaysWhy() {
        throughBudgets()
        offer()
        mappings.mapAll()
        mappings.chooseBudgetAccount(budgetAccountId("Checking"))
        runBlocking {
            slice.connect.addFromTransactionFiles(
                slice.connect.fileFormats().single().fileFormatId, BankAccountType.Checking, BankChoice.Named("Harbor Bank"), null,
                budgetAccountId("Checking"), "Harbor Joint", null, USD,
            )
        }
        val card = card()

        mappings.addAndContinue("Kestrel Checking", card.proposedType, kestrel(), "")
        assertEquals("Checking already receives Harbor Joint · not added", mappingsUi().message)
        assertEquals("1 of 2", card().progress)
        mappings.openPicker()
        mappings.chooseBudgetAccount(budgetAccountId("Current"))
        mappings.addAndContinue("Kestrel Checking", card.proposedType, kestrel(), "")

        assertEquals("Current is in EUR, Kestrel Checking in USD · not added", mappingsUi().message)
        assertEquals(MappingsStage.Card, mappingsUi().stage)
        assertEquals(1, runBlocking { slice.connectionStore.bankAccounts().size })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A8: Skip for Now", businessRules = ["BR-012"])
    @DisplayName("A8: Skip for now adds nothing and leaves nothing out; the summary lists it as skipped, and it stays at the top of the Account Mappings, to map")
    fun skipForNow() {
        throughBudgets()
        offer()
        mappings.mapAll()

        mappings.skipForNow()
        assertEquals("2 of 2", card().progress)
        addOnCard("Reserve Savings", "Aurora Dollar")

        assertEquals(SummaryUi(added = listOf("Aurora Dollar → Reserve Savings ••5520"), skipped = listOf("CHK ••4410 · still to map")), mappingsUi().summary)
        mappings.done()
        assertEquals(listOf("CHK"), mappingsUi().toMap.map { it.title })
        assertTrue(leftOutReferences().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010", "BR-013"])
    @DisplayName("BR-010: when the Offered Bank Account states no currency, every free Budget Account is listed and step 11 asks for the currency, the chosen Budget Account's proposed, and the Bank Account is added with it")
    fun currencyIsStatedAtStep11() {
        throughBudgets()
        offerWithoutCurrency()
        mappings.mapOne(offeredId("acct-9001"))
        assertEquals("Free Budget Accounts", card().listedHeading)
        assertEquals(6, card().listed.size)

        mappings.chooseBudgetAccount(budgetAccountId("Checking"))

        val card = card()
        assertEquals("USD", card.currency)
        assertFalse(card.currencyStatedBySource)
        assertEquals("Kestrel Feeds states no currency · state it, the Budget Account's by default", card.currencyNote)
        mappings.addAndContinue("Harbor Joint", card.proposedType, BankChoice.Named("Harbor Bank"), "", "USD")
        assertEquals(listOf("Harbor Joint → Checking ••9001"), mappingsUi().summary!!.added)
        assertEquals(USD, runBlocking { slice.connectionStore.bankAccounts().single().currency })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-010"])
    @DisplayName("A5, BR-010: a stated currency other than the Budget's is refused with why; a currency that is no ISO code is not accepted")
    fun statedCurrencyIsChecked() {
        throughBudgets()
        offerWithoutCurrency()
        mappings.mapOne(offeredId("acct-9001"))
        mappings.chooseBudgetAccount(budgetAccountId("Checking"))
        val card = card()

        mappings.addAndContinue("Harbor Joint", card.proposedType, BankChoice.Named("Harbor Bank"), "", "EUR")
        assertEquals("Checking is in USD, Harbor Joint in EUR · not added", mappingsUi().message)

        mappings.addAndContinue("Harbor Joint", card.proposedType, BankChoice.Named("Harbor Bank"), "", "dollars")
        assertEquals("The currency is an ISO 4217 code of three capital letters, like USD · not added", mappingsUi().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: a currency the Data Provider states is shown as stated by it")
    fun currencyStatedBySourceIsShown() {
        throughBudgets()
        offer()
        mappings.mapOne(offeredId("acct-4410"))

        assertEquals("USD", card().currency)
        assertTrue(card().currencyStatedBySource)
        assertEquals("Stated by Kestrel Feeds", card().currencyNote)
    }

    // ---- A4, BR-006, BR-010: the File Format's page -----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006"])
    @DisplayName("A4 step 1: a File Format's page opens before a Budget is served and says that a Bank Account can be added once one is chosen")
    fun fileFormatPageWaitsForABudget() {
        val ui = fileUi()

        assertEquals("Harbor Health statement", ui.name)
        assertEquals("File Format · Transaction Files", ui.kind)
        assertTrue(ui.bankAccounts.isEmpty())
        assertFalse(ui.canAdd)
        assertEquals("Available once $budgetAppName is set up and a Budget is chosen.", ui.waitNote)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-009", "BR-010", "BR-011"])
    @DisplayName("A4 steps 2-3: the form chooses the type, the Bank, the balance source (also a Left-Out one), the Budget Account, the name and the currency, proposed from the balance source; Add adds it already mapped and lists it on the page")
    fun bankAccountOfAFileFormatIsAddedAndMapped() {
        throughBudgets()
        offer()
        runBlocking { slice.connect.switchImportOff(offeredId("acct-5520")) }
        fileFormat.load()

        fileFormat.openForm()
        val form = fileUi().form!!
        assertEquals(listOf("Checking", "Savings", "Credit Card", "Other"), form.types.map { it.text })
        assertEquals(listOf(BalanceLine(offeredId("acct-4410"), "Kestrel Feeds · CHK ••4410", ""), BalanceLine(offeredId("acct-5520"), "Kestrel Feeds · Aurora Dollar ••5520", "not imported")), form.balanceSources)
        assertEquals(listOf("Household · USD", "Summer House · EUR"), form.budgetAccounts.map { it.title })
        fileFormat.choose(budgetAccountId("Reserve Savings"), null)
        assertEquals("USD", fileUi().form!!.currency)
        assertEquals("Proposed from the Budget Account Reserve Savings", fileUi().form!!.currencyNote)
        fileFormat.choose(budgetAccountId("Reserve Savings"), offeredId("acct-5520"))
        assertEquals("Stated by Kestrel Feeds for the balance", fileUi().form!!.currencyNote)
        fileFormat.add(null, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "")
        assertEquals("Choose the Bank Account type · not added", fileUi().message)

        fileFormat.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "2026-06-01")

        val ui = fileUi()
        assertNull(ui.form)
        assertEquals("Harbor Health Savings added and mapped → Reserve Savings · its transactions come from its Transaction Files", ui.message)
        val added = runBlocking { slice.connectionStore.bankAccounts().single() }
        assertEquals(
            listOf(FileAccountLine(added.id, "Harbor Health Savings", "Harbor Health Trust · Savings · USD · balance from Kestrel Feeds Aurora Dollar ••5520", "Added → Reserve Savings")),
            ui.bankAccounts,
        )
        assertTrue(leftOutReferences().isEmpty())
        assertEquals(LocalDate.parse("2026-06-01"), added.startDate)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-010"])
    @DisplayName("A5, BR-010: on the File Format's page a currency other than the Budget's is refused with why, and one that is no ISO code is not accepted")
    fun fileFormatCurrencyIsChecked() {
        throughBudgets()
        fileFormat.load()
        fileFormat.openForm()
        fileFormat.choose(budgetAccountId("Current"), null)

        fileFormat.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "USD", "")
        assertEquals("Current is in EUR, Harbor Health Savings in USD · not added", fileUi().message)
        fileFormat.add(BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), "Harbor Health Savings", "dollars", "")

        assertEquals("The currency is an ISO 4217 code of three capital letters, like USD · not added", fileUi().message)
        assertTrue(runBlocking { slice.connectionStore.bankAccounts().isEmpty() })
        assertTrue(fileUi().form != null)
    }

    // ---- Precondition -------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-013")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, choosing Budgets and switching an import off show why they are unavailable, and nothing is left out")
    fun unavailableWhileAPlanAwaitsConfirmation() {
        throughBudgets()
        offer()
        feedsPage.load()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        budgetPage.chooseBudgets()
        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", budgetPage.ui().message)
        feedsPage.switchImport(offeredLine("CHK"), on = false)

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", feedsPage.ui().message)
        assertTrue(leftOutReferences().isEmpty())
        assertEquals(true, offeredLine("CHK").importOn)
    }
}
