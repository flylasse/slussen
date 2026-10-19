package slussen.core.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.testing.TestSlice
import slussen.core.credential.CredentialStatus
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.ServiceKey
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import java.time.Duration
import java.time.LocalDate

@DisplayName("UC-013: Connect Budgets and Bank Accounts")
class UC013ConnectBudgetsAndBankAccountsTest {
    private val slice = TestSlice()

    private suspend fun kestrelBankId() = slice.connectionStore.bankNamed("Kestrel Bank").id

    /** The Offered Bank Accounts a first Fetch of the Data Provider stored (UC-001, A3). */
    private suspend fun offerFeedsBankAccounts() {
        val bank = kestrelBankId()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", amount(4140, 50), bank, slice.clock.instant()),
                ExampleData.offered("acct-1182", "Pinecrest Rewards Visa", "credit card", USD, "1182", amount(-1268, 400), bank, slice.clock.instant()),
                ExampleData.offered("acct-7731", "Reserve Saver", "savings", USD, "7731", amount(12000), bank, slice.clock.instant()),
                ExampleData.offered("acct-5520", "Aurora Dollar", "savings", USD, "5520", amount(1520), bank, slice.clock.instant()),
            ),
        )
    }

    private suspend fun chooseHouseholdAndSummerHouse() {
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer"))
    }

    private suspend fun offeredId(providerReference: String): Long =
        slice.offeredStore.offered().single { it.providerReference == providerReference }.id

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun readyToPair() {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        offerFeedsBankAccounts()
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Step 1: shows for the Budget App and each Data Provider whether its credentials are entered and accepted")
    fun credentialStepsShowEachService() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))

        val steps = slice.connect.credentialSteps()

        assertEquals(
            listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds", "Aurora Open Banking", "Wren Feeds"),
            steps.map { it.name },
        )
        assertTrue(steps.first().isBudgetApp)
        assertInstanceOf(CredentialStatus.Accepted::class.java, steps.first().status)
        assertTrue(steps.drop(1).all { it.status == CredentialStatus.Missing })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Step 2: reads the Budgets from the Budget App and marks the Budgets Slussen already serves")
    fun readsBudgetsAndMarksTheServedOnes() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()

        val read = slice.connect.readBudgets() as BudgetsRead.Listed

        assertEquals(listOf("Household", "Summer House", "Care Fund"), read.budgets.map { it.name })
        assertEquals(listOf(true, true, false), read.budgets.map { it.served })
        assertEquals(EUR, read.budgets[1].currency)
        assertEquals(ExampleData.BUDGET_APP_TOKEN, slice.budgetApp.reader.contexts.last().credential?.let { revealed(it) })
    }

    @OptIn(slussen.pluginapi.value.SensitiveApi::class)
    private fun revealed(secret: slussen.pluginapi.value.Secret) = secret.reveal()

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Steps 3-4: the chosen Budgets are stored as served, with their Budget Accounts and balances")
    fun chosenBudgetsAreStoredWithTheirBudgetAccounts() = runTest {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed

        val choice = slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))

        assertEquals(BudgetChoice.Stored, choice)
        val stored = slice.connectionStore.budgets()
        assertEquals(listOf("Household"), stored.filter { it.served }.map { it.name })
        val accounts = stored.single().budgetAccounts
        assertEquals(listOf("Checking", "Rewards Card", "Reserve Savings"), accounts.map { it.name })
        assertEquals(amount(-1268, 400), accounts[1].balance)
        assertEquals(slice.clock.instant(), accounts[1].balanceReadAt)
        assertEquals(1, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 5: each Offered Bank Account not yet added is shown with name, kind, currency, last four digits and balance, next to the free Budget Accounts of the chosen Budgets")
    fun pairingShowsBothSides() = runTest {
        readyToPair()

        val pairing = slice.connect.pairing()

        val feeds = pairing.sources.single { it.name == "Kestrel Feeds" }
        val checking = feeds.offered.first()
        assertEquals("CHK", checking.nameOrProduct)
        assertEquals("checking", checking.kind)
        assertEquals(USD, checking.currency)
        assertEquals("4410", checking.lastFourDigits)
        assertEquals(amount(4140, 50), checking.balance)
        assertEquals("Kestrel Bank", checking.bankName)
        assertNull(feeds.remedy)
        assertEquals(
            listOf("Checking", "Rewards Card", "Reserve Savings", "Current", "Savings", "Card"),
            pairing.freeBudgetAccounts.map { it.name },
        )
        assertEquals(amount(4140, 50), pairing.freeBudgetAccounts.first().balance)
        assertTrue(pairing.freeBudgetAccounts.none { it.budgetName == "Care Fund" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 5: a Budget Account that receives through an Account Mapping is not offered for pairing")
    fun mappedBudgetAccountIsNotOffered() = runTest {
        readyToPair()
        slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking"))

        val pairing = slice.connect.pairing()

        assertTrue(pairing.freeBudgetAccounts.none { it.name == "Checking" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"])
    @DisplayName("Steps 6-8: pairing adds the Bank Account with a generated identifier, how its Source identifies it, a proposed name and its Account Mapping")
    fun pairingAddsTheBankAccount() = runTest {
        readyToPair()
        slice.clock.advance(Duration.ofMinutes(3))

        val outcome = slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")) as PairOutcome.Added

        assertEquals("CHK ••4410", outcome.proposedName)
        val added = slice.connectionStore.bankAccounts().single()
        assertEquals(outcome.bankAccountId, added.id)
        assertEquals(outcome.generatedIdentifier, added.generatedIdentifier)
        assertEquals("acct-4410", added.providerReference)
        assertEquals("fp-acct-4410", added.bankAccountFingerprint)
        assertEquals(slice.feedsId, added.dataProviderId)
        assertNull(added.fileFormatId)
        assertEquals(USD, added.currency)
        assertEquals(BankAccountType.Checking, added.type)
        assertEquals(slice.clock.instant(), added.identifiedAt)
        assertEquals("CHK ••4410", added.name)
        assertEquals(budgetAccountId("Checking"), added.mapping?.budgetAccountId)
        assertNull(added.mapping?.firstSentAt)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005"])
    @DisplayName("Steps 9-10: the accepted or edited name and the optional Start Date are stored and the addition is recorded in the Decision Journal")
    fun nameAndStartDateAreStoredAndJournaled() = runTest {
        readyToPair()
        val checking = slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")) as PairOutcome.Added
        val card = slice.connect.pair(offeredId("acct-1182"), budgetAccountId("Rewards Card")) as PairOutcome.Added

        val edited = slice.connect.completeAddition(checking.bankAccountId, "Kestrel Checking", LocalDate.parse("2026-09-01"))
        val accepted = slice.connect.completeAddition(card.bankAccountId, card.proposedName, null)

        assertEquals(AdditionOutcome.Stored, edited)
        assertEquals(AdditionOutcome.Stored, accepted)
        val stored = slice.connectionStore.bankAccounts().associateBy { it.id }
        assertEquals("Kestrel Checking", stored.getValue(checking.bankAccountId).name)
        assertEquals(LocalDate.parse("2026-09-01"), stored.getValue(checking.bankAccountId).startDate)
        assertEquals("Pinecrest Rewards Visa ••1182", stored.getValue(card.bankAccountId).name)
        assertNull(stored.getValue(card.bankAccountId).startDate)
        val additions = slice.journalRecorder.entries.filter { it.eventKind == JournalEventKind.AccountMappingChange }
        assertEquals(2, additions.size)
        assertTrue("Kestrel Checking" in additions.first().details)
        assertTrue("2026-09-01" in additions.first().details)
        additions.forEach { assertFalse("acct-" in it.details, it.details) }
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("Step 9: an empty name is not stored; the name never identifies the Bank Account")
    fun emptyNameIsNotStored() = runTest {
        readyToPair()
        val added = slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")) as PairOutcome.Added

        assertEquals(AdditionOutcome.NameNotValid, slice.connect.completeAddition(added.bankAccountId, "  ", null))
        assertEquals(AdditionOutcome.NameNotValid, slice.connect.completeAddition(added.bankAccountId, "x".repeat(101), null))
        assertEquals("CHK ••4410", slice.connectionStore.bankAccounts().single().name)
        assertEquals(added.generatedIdentifier, slice.connectionStore.bankAccounts().single().generatedIdentifier)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", requirements = ["C-012"])
    @DisplayName("Step 11: the added Bank Account is shown as added and is no longer offered, so each Bank Account comes in through one path")
    fun addedBankAccountIsShownAndNoLongerOffered() = runTest {
        readyToPair()
        val added = slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")) as PairOutcome.Added
        slice.connect.completeAddition(added.bankAccountId, "Kestrel Checking", null)

        val list = slice.connect.addedBankAccounts()
        val pairing = slice.connect.pairing()

        val shown = list.single()
        assertEquals("Kestrel Checking", shown.name)
        assertEquals("Kestrel Bank", shown.bankName)
        assertEquals("Kestrel Feeds", shown.sourceName)
        assertEquals("Household", shown.budgetName)
        assertEquals("Checking", shown.budgetAccountName)
        assertTrue(pairing.sources.flatMap { it.offered }.none { it.offeredBankAccountId == offeredId("acct-4410") })
        assertEquals(PairOutcome.NotOffered, slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Rewards Card")))
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials")
    @DisplayName("A1: a Budget App that cannot be read leaves the Budgets served unchanged and says so")
    fun unreadableBudgetAppLeavesServedBudgetsUnchanged() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        val before = slice.connectionStore.budgets()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)

        val read = slice.connect.readBudgets()

        assertEquals(BudgetsRead.CouldNotRead(FailureKind.Timeout), read)
        assertEquals(before, slice.connectionStore.budgets())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials")
    @DisplayName("A1: a Budget App that refuses the credentials shows the Blocked Reason credentials refused")
    fun refusingBudgetAppShowsCredentialsRefused() = runTest {
        slice.enterCredentials()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused

        assertEquals(BudgetsRead.CredentialsRefused, slice.connect.readBudgets())
        assertEquals(CredentialStatus.Refused, slice.credentials.status(ServiceKey.budgetApp))
        assertTrue(slice.connectionStore.budgets().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A2: Stop Serving a Budget That Has Mapped Bank Accounts", businessRules = ["BR-001"])
    @DisplayName("A2: stopping to serve a Budget with mapped Bank Accounts is refused, naming them")
    fun stopServingAMappedBudgetIsRefused() = runTest {
        readyToPair()
        slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking"))
        val read = slice.connect.readBudgets() as BudgetsRead.Listed

        val choice = slice.connect.chooseBudgets(read.budgets, setOf("budget-summer"))

        assertEquals(BudgetChoice.StillMapped("Household", listOf("CHK ••4410")), choice)
        assertTrue(slice.connectionStore.budgets().single { it.name == "Household" }.served)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("BR-001: a Budget into which no Bank Account is mapped stops being served when the Operator leaves it out")
    fun anUnmappedBudgetStopsBeingServed() = runTest {
        readyToPair()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed

        val choice = slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))

        assertEquals(BudgetChoice.Stored, choice)
        assertFalse(slice.connectionStore.budgets().single { it.name == "Summer House" }.served)
        assertTrue(slice.connect.pairing().freeBudgetAccounts.none { it.budgetName == "Summer House" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A3: No Offered Bank Accounts for a Source")
    @DisplayName("A3: a Source with no stored Offered Bank Account offers a Fetch, or a Bank Consent for a Data Provider that needs one")
    fun sourceWithoutOfferedBankAccountsOffersTheRemedy() = runTest {
        slice.enterCredentials()
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        chooseHouseholdAndSummerHouse()

        val sources = slice.connect.pairing().sources.associateBy { it.name }

        assertEquals(OfferRemedy.StartFetch, sources.getValue("Kestrel Feeds").remedy)
        assertEquals(OfferRemedy.GrantBankConsent, sources.getValue("Aurora Open Banking").remedy)
        assertTrue(sources.getValue("Kestrel Feeds").offered.isEmpty())
        assertFalse("Wren Feeds" in sources)
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006"])
    @DisplayName("A4: a Bank Account of a File Format is added with its File Format, Bank, the Offered Bank Account that reports its balance and the name the Operator enters")
    fun bankAccountOfAFileFormatIsAdded() = runTest {
        readyToPair()
        val format = slice.connect.fileFormats().single()

        val outcome = slice.connect.addFromTransactionFiles(
            fileFormatId = format.fileFormatId,
            bankName = "Harbor Health Trust",
            balanceOfferedBankAccountId = offeredId("acct-7731"),
            budgetAccountId = budgetAccountId("Reserve Savings"),
            name = "Harbor Health Savings",
        ) as PairOutcome.Added

        assertEquals("Harbor Health Savings", outcome.proposedName)
        val added = slice.connectionStore.bankAccounts().single()
        assertEquals("Harbor Health Savings", added.name)
        assertEquals(format.fileFormatId, added.fileFormatId)
        assertNull(added.dataProviderId)
        assertEquals(slice.feedsId, added.balanceDataProviderId)
        assertEquals("acct-7731", added.balanceProviderReference)
        assertEquals("Harbor Health Trust", slice.connectionStore.banks().single { it.id == added.bankId }.name)
        assertEquals(budgetAccountId("Reserve Savings"), added.mapping?.budgetAccountId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006"])
    @DisplayName("A4: without a balance Data Provider the Bank Account of a File Format has none, and an empty name is refused")
    fun bankAccountOfAFileFormatWithoutBalanceSource() = runTest {
        readyToPair()
        val format = slice.connect.fileFormats().single()

        val unnamed = slice.connect.addFromTransactionFiles(format.fileFormatId, "Harbor Health Trust", null, budgetAccountId("Checking"), " ")
        val outcome = slice.connect.addFromTransactionFiles(format.fileFormatId, "Harbor Health Trust", null, budgetAccountId("Checking"), "Health Savings")

        assertEquals(PairOutcome.NameNotValid, unnamed)
        assertInstanceOf(PairOutcome.Added::class.java, outcome)
        val added = slice.connectionStore.bankAccounts().single()
        assertNull(added.balanceDataProviderId)
        assertNull(added.balanceProviderReference)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-002"])
    @DisplayName("A5: a Budget Account that already receives through an Account Mapping is refused, with why")
    fun alreadyMappedBudgetAccountIsRefused() = runTest {
        readyToPair()
        slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking"))

        val outcome = slice.connect.pair(offeredId("acct-7731"), budgetAccountId("Checking"))

        assertEquals(PairOutcome.BudgetAccountAlreadyMapped("Checking", "CHK ••4410"), outcome)
        assertEquals(1, slice.connectionStore.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency")
    @DisplayName("A5: a Budget Account whose Budget has another currency is refused; no amount is converted")
    fun budgetAccountInAnotherCurrencyIsRefused() = runTest {
        readyToPair()

        val outcome = slice.connect.pair(offeredId("acct-5520"), budgetAccountId("Current"))

        assertEquals(PairOutcome.CurrencyDiffers("Current", EUR, "Aurora Dollar", USD), outcome)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A6: Offered Bank Account Probably Already Added")
    @DisplayName("A6: an Offered Bank Account that is probably an added Bank Account is shown as its proposed match, not as one to add")
    fun probablyAddedBankAccountIsAProposedMatch() = runTest {
        readyToPair()
        val added = slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")) as PairOutcome.Added
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered(
                    "acct-4410-new", "CHK", "checking", USD, "4410", amount(4140, 50), kestrelBankId(),
                    slice.clock.instant(), proposedBankAccountId = added.bankAccountId,
                ),
            ),
        )

        val feeds = slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }

        assertTrue(feeds.offered.isEmpty())
        assertEquals("CHK ••4410", feeds.proposedMatches.single().bankAccountName)
        assertEquals(PairOutcome.NotOffered, slice.connect.pair(offeredId("acct-4410-new"), budgetAccountId("Rewards Card")))
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A7: Budget App Credentials Not Entered", businessRules = ["BR-008"])
    @DisplayName("A7: without accepted Budget App credentials the Budgets cannot be chosen and the Budget App is not contacted")
    fun withoutBudgetAppCredentialsNoBudgetIsRead() = runTest {
        val read = slice.connect.readBudgets()

        assertEquals(BudgetsRead.BudgetAppCredentialsMissing, read)
        assertEquals(0, slice.budgetApp.reader.budgetReads)
    }

    @Test
    @UseCase(id = "UC-013")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, Budgets cannot be read or chosen and no Bank Account can be added")
    fun connectingWaitsForTheUpload() = runTest {
        readyToPair()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val refusal = OperationRefusal.UploadPlanAwaitingConfirmation

        assertEquals(BudgetsRead.Unavailable(refusal), slice.connect.readBudgets())
        assertEquals(BudgetChoice.Unavailable(refusal), slice.connect.chooseBudgets(read.budgets, setOf("budget-care")))
        assertEquals(PairOutcome.Unavailable(refusal), slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")))
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
        assertFalse(slice.connectionStore.budgets().any { it.name == "Care Fund" })
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-003"], requirements = ["NFR-020"])
    @DisplayName("BR-003: every added Bank Account gets its own generated identifier, a random version 4 UUID")
    fun generatedIdentifiersAreRandomVersion4Uuids() = runTest {
        readyToPair()
        val first = slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking")) as PairOutcome.Added
        val second = slice.connect.pair(offeredId("acct-1182"), budgetAccountId("Rewards Card")) as PairOutcome.Added

        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        assertTrue(uuid.matches(first.generatedIdentifier), first.generatedIdentifier)
        assertTrue(uuid.matches(second.generatedIdentifier), second.generatedIdentifier)
        assertNotEquals(first.generatedIdentifier, second.generatedIdentifier)
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-008"])
    @DisplayName("BR-008: the first run goes credentials, then Budgets, then Bank Accounts: nothing can be paired before a Budget is chosen")
    fun firstRunOrder() = runTest {
        slice.enterCredentials()
        offerFeedsBankAccounts()

        val beforeBudgets = slice.connect.pairing()

        assertTrue(beforeBudgets.freeBudgetAccounts.isEmpty())
        chooseHouseholdAndSummerHouse()
        assertTrue(slice.connect.pairing().freeBudgetAccounts.isNotEmpty())
    }

    @Test
    @UseCase(id = "UC-013")
    @DisplayName("Failure postconditions: no Bank Account without the Operator's pairing, no Budget Account with two Account Mappings, nothing written to the Budget App")
    fun failurePostconditions() = runTest {
        readyToPair()
        slice.connect.pair(offeredId("acct-4410"), budgetAccountId("Checking"))
        slice.connect.pair(offeredId("acct-7731"), budgetAccountId("Checking"))
        slice.connect.pair(offeredId("acct-5520"), budgetAccountId("Savings"))

        val mapped = slice.connectionStore.bankAccounts().mapNotNull { it.mapping?.budgetAccountId }
        assertEquals(mapped.distinct(), mapped)
        assertEquals(1, slice.connectionStore.bankAccounts().size)
        assertEquals(0, slice.budgetApp.writer.calls)
    }
}
