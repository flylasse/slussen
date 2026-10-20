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
import slussen.core.settings.AccountMappingsEntry
import slussen.core.settings.EntryKind
import slussen.core.settings.EntryStatus
import slussen.core.settings.MappingsWaitFor
import slussen.core.settings.NextThing
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.ServiceKey
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.SourceOutcome
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

    private suspend fun proposal(providerReference: String, budgetAccount: String): AdditionProposal =
        (slice.connect.propose(offeredId(providerReference), budgetAccountId(budgetAccount)) as PairOutcome.Proposed).proposal

    /** Steps 10-13 with everything the Operator accepts as proposed, or the [name] and [startDate] given. */
    private suspend fun pairAndAdd(
        providerReference: String,
        budgetAccount: String,
        name: String? = null,
        startDate: LocalDate? = null,
    ): PairOutcome {
        val proposed = slice.connect.propose(offeredId(providerReference), budgetAccountId(budgetAccount))
        if (proposed !is PairOutcome.Proposed) return proposed
        val p = proposed.proposal
        return slice.connect.add(
            p.offeredBankAccountId,
            p.budgetAccountId,
            name ?: p.proposedName,
            p.proposedType,
            p.proposedBank?.let { BankChoice.Known(it.bankId) },
            startDate,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-015"])
    @DisplayName("Step 1: Settings lists the Budget App, each Data Provider and each File Format of the build, each with its status, then the Account Mappings, not yet available with the reason, and names what comes next")
    fun stepOneListsEveryServiceAndTheAccountMappings() = runTest {
        val top = slice.settingsOverview.entries()

        assertEquals(
            listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds", "Aurora Open Banking", "Wren Feeds", "Harbor Health statement"),
            top.entries.map { it.name },
        )
        assertEquals(
            listOf(EntryKind.BudgetApp, EntryKind.DataProvider, EntryKind.DataProvider, EntryKind.DataProvider, EntryKind.FileFormat),
            top.entries.map { it.kind },
        )
        assertEquals(List(4) { EntryStatus.NotSetUp } + EntryStatus.FileFormatBankAccounts(0), top.entries.map { it.status })
        assertEquals(listOf(true, false, false, false, false), top.entries.map { it.isNext })
        assertEquals(AccountMappingsEntry(available = false, toMap = 0, waitingFor = MappingsWaitFor.BudgetAppCredentials, isNext = false), top.accountMappings)
        assertEquals(NextThing.EnterBudgetAppCredentials(ExampleData.BUDGET_APP_NAME), top.next)
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
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-014"], requirements = ["C-012"])
    @DisplayName("Step 4, BR-014: under each chosen Budget its Budget Accounts with their balances, each that receives through no Account Mapping with Receives from Slussen on, each mapped one with its Account Mapping instead of a switch")
    fun stepFourShowsTheBudgetAccountsWithTheirSwitches() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking", name = "Kestrel Checking")

        val served = slice.connect.servedBudgetAccounts()

        assertEquals(listOf("Household", "Summer House"), served.map { it.name })
        assertEquals(EUR, served[1].currency)
        val household = served.first().budgetAccounts
        assertEquals(listOf("Checking", "Rewards Card", "Reserve Savings"), household.map { it.name })
        assertEquals(BudgetAccountOnPage(budgetAccountId("Checking"), "Checking", amount(4140, 50), receivesFromSlussen = null, mappedFrom = "Kestrel Checking"), household[0])
        assertEquals(BudgetAccountOnPage(budgetAccountId("Rewards Card"), "Rewards Card", amount(-1268, 400), receivesFromSlussen = true, mappedFrom = null), household[1])
        assertTrue(served.flatMap { it.budgetAccounts }.none { it.name == "Health Savings" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 8, BR-002: each Bank Account to map is shown with name, kind, currency, last four digits and balance, next to the free Budget Accounts of the chosen Budgets")
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
    @DisplayName("Step 8, BR-002 (S-35): after one Offered Bank Account is added, the next Fetch reads only that one, and the Bank Accounts to map keep showing the balance the Data Provider gave with the list")
    fun offeredKeepTheirBalanceAfterAnAddition() = runTest {
        readyToPair()
        val listed = listOf(
            Triple("acct-4410", "CHK", amount(4140, 50)),
            Triple("acct-1182", "Pinecrest Rewards Visa", amount(-1268, 400)),
            Triple("acct-7731", "Reserve Saver", amount(12000)),
            Triple("acct-5520", "Aurora Dollar", amount(1520)),
        )
        // A Data Provider that keeps the contract of ADR-001 §4.1: a listed Bank Account not asked for comes with the balance of the list.
        slice.feeds.fetchAnswer = { request ->
            val asked = (request.bankAccounts as BankAccountSelection.Selected).bankAccounts.map { it.providerReference }.toSet()
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listed.map { (reference, name, balance) ->
                    val result = if (reference in asked) {
                        BankAccountResult.Read(emptyList(), balance)
                    } else {
                        BankAccountResult.NotRead(NotReadCause.NotSelected, balance)
                    }
                    DeliveredBankAccount(reference, "Kestrel Bank", name, "savings", USD, null, result)
                },
            )
        }
        assertInstanceOf(PairOutcome.Added::class.java, pairAndAdd("acct-4410", "Checking"))

        slice.fetch.fetch(slice.feedsKey)

        assertEquals(listOf("acct-4410"), (slice.feeds.fetchRequests.single().bankAccounts as BankAccountSelection.Selected).bankAccounts.map { it.providerReference })
        val offered = slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }.offered
        assertEquals(listOf("Pinecrest Rewards Visa", "Reserve Saver", "Aurora Dollar"), offered.map { it.nameOrProduct })
        assertEquals(listOf(amount(-1268, 400), amount(12000), amount(1520)), offered.map { it.balance })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 10, BR-002: a Budget Account that receives through an Account Mapping is not offered for pairing")
    fun mappedBudgetAccountIsNotOffered() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")

        val pairing = slice.connect.pairing()

        assertTrue(pairing.freeBudgetAccounts.none { it.name == "Checking" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("Step 10: pairing proposes the name, the Bank Account type from the kind and the Bank the Data Provider names, and adds nothing yet")
    fun pairingProposesNameTypeAndBank() = runTest {
        readyToPair()

        val proposal = proposal("acct-4410", "Checking")

        assertEquals("CHK ••4410", proposal.proposedName)
        assertEquals(BankAccountType.Checking, proposal.proposedType)
        assertEquals(KnownBank(kestrelBankId(), "Kestrel Bank"), proposal.proposedBank)
        assertEquals(listOf(BankAccountType.Checking, BankAccountType.Savings, BankAccountType.CreditCard, BankAccountType.Other), proposal.types)
        assertEquals(listOf("Kestrel Bank"), proposal.knownBanks.map { it.name })
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
        assertTrue(slice.journalRecorder.entries.none { it.eventKind == JournalEventKind.AccountMappingChange })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004", "BR-005", "BR-009"])
    @DisplayName("Steps 11-13: the Bank Account is added with a generated identifier, how its Source identifies it, the accepted or edited name, type, Bank, Start Date and its Account Mapping, and recorded in the Decision Journal")
    fun confirmedPairingAddsTheBankAccount() = runTest {
        readyToPair()
        slice.clock.advance(Duration.ofMinutes(3))

        val outcome = pairAndAdd("acct-4410", "Checking", name = "Kestrel Checking", startDate = LocalDate.parse("2026-09-01")) as PairOutcome.Added

        val added = slice.connectionStore.bankAccounts().single()
        assertEquals(outcome.bankAccountId, added.id)
        assertEquals(outcome.generatedIdentifier, added.generatedIdentifier)
        assertEquals("Kestrel Checking", outcome.name)
        assertEquals("acct-4410", added.providerReference)
        assertEquals("fp-acct-4410", added.bankAccountFingerprint)
        assertEquals(slice.feedsId, added.dataProviderId)
        assertNull(added.fileFormatId)
        assertEquals(USD, added.currency)
        assertEquals(BankAccountType.Checking, added.type)
        assertEquals(kestrelBankId(), added.bankId)
        assertEquals(kestrelBankId(), added.providerBankId)
        assertEquals(slice.clock.instant(), added.identifiedAt)
        assertEquals("Kestrel Checking", added.name)
        assertEquals(LocalDate.parse("2026-09-01"), added.startDate)
        assertEquals(budgetAccountId("Checking"), added.mapping?.budgetAccountId)
        assertNull(added.mapping?.firstSentAt)
        val addition = slice.journalRecorder.entries.single { it.eventKind == JournalEventKind.AccountMappingChange }
        assertTrue("Kestrel Checking" in addition.details, addition.details)
        assertTrue("Checking" in addition.details && "Kestrel Bank" in addition.details, addition.details)
        assertTrue("2026-09-01" in addition.details, addition.details)
        assertFalse("acct-" in addition.details, addition.details)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-005"])
    @DisplayName("Step 11: the proposed name is accepted and the Start Date left empty")
    fun proposedNameAcceptedWithoutStartDate() = runTest {
        readyToPair()

        pairAndAdd("acct-1182", "Rewards Card")

        val added = slice.connectionStore.bankAccounts().single()
        assertEquals("Pinecrest Rewards Visa ••1182", added.name)
        assertNull(added.startDate)
        assertTrue("no Start Date" in slice.journalRecorder.entries.last().details)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("Step 11: an empty or too long name is refused and nothing is added; the name never identifies the Bank Account")
    fun emptyNameIsNotStored() = runTest {
        readyToPair()
        val p = proposal("acct-4410", "Checking")
        val bank = BankChoice.Known(p.proposedBank!!.bankId)

        assertEquals(PairOutcome.NameNotValid, slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, "  ", p.proposedType, bank, null))
        assertEquals(PairOutcome.NameNotValid, slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, "x".repeat(101), p.proposedType, bank, null))
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: the type is proposed from the kind: credit card, savings and checking; Other when the kind matches none of them or is missing")
    fun typeIsProposedFromTheKind() = runTest {
        readyToPair()
        val bank = kestrelBankId()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, bank, slice.clock.instant()),
                ExampleData.offered("acct-1182", "Rewards", "credit card", USD, "1182", null, bank, slice.clock.instant()),
                ExampleData.offered("acct-7731", "Reserve", "savings", USD, "7731", null, bank, slice.clock.instant()),
                ExampleData.offered("acct-3300", "Loan", "loan", USD, "3300", null, bank, slice.clock.instant()),
                ExampleData.offered("acct-3301", "Other", null, USD, "3301", null, bank, slice.clock.instant()),
            ),
        )

        assertEquals(BankAccountType.Checking, proposal("acct-4410", "Checking").proposedType)
        assertEquals(BankAccountType.CreditCard, proposal("acct-1182", "Checking").proposedType)
        assertEquals(BankAccountType.Savings, proposal("acct-7731", "Checking").proposedType)
        assertEquals(BankAccountType.Other, proposal("acct-3300", "Checking").proposedType)
        assertEquals(BankAccountType.Other, proposal("acct-3301", "Checking").proposedType)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: the Operator changes the proposed type and chooses another known Bank, and the Bank Account is added with them")
    fun operatorChangesTypeAndBank() = runTest {
        readyToPair()
        val pinecrest = slice.connectionStore.bankNamed("Pinecrest Card Co.").id
        val p = proposal("acct-4410", "Checking")

        slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, BankAccountType.Savings, BankChoice.Known(pinecrest), null)

        val added = slice.connectionStore.bankAccounts().single()
        assertEquals(BankAccountType.Savings, added.type)
        assertEquals(pinecrest, added.bankId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: the Bank the Data Provider names is kept apart from the Bank the Operator chooses, which only names and groups the Bank Account")
    fun theDataProvidersBankIsKeptApart() = runTest {
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        readyToPair()
        val aurora = slice.connectionStore.bankNamed("Aurora Bank").id
        val vesta = slice.connectionStore.bankNamed("Vesta Sparbank").id
        slice.offeredStore.replaceOffered(
            slice.openBankingId,
            aurora,
            listOf(ExampleData.offered("eb-4471", "Aurora Current", "current", EUR, "4471", null, aurora, slice.clock.instant())),
        )
        val p = proposal("eb-4471", "Current")

        slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType, BankChoice.Known(vesta), null)

        val added = slice.connectionStore.bankAccounts().single()
        assertEquals(vesta, added.bankId)
        assertEquals(aurora, added.providerBankId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: when the Data Provider names no Bank, the Bank Account keeps none of the Data Provider's")
    fun noProviderBankWhenNoneIsNamed() = runTest {
        readyToPair()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-2209", "Old Savings", "savings", USD, "2209", null, bankId = null, offeredAt = slice.clock.instant())),
        )
        val p = proposal("acct-2209", "Reserve Savings")

        slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType, BankChoice.Named("Kestrel Bank"), null)

        assertNull(slice.connectionStore.bankAccounts().single().providerBankId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: when the Data Provider names no Bank, none is proposed; the Operator names a new one, which Slussen then knows")
    fun noBankNamedTheOperatorNamesOne() = runTest {
        readyToPair()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-2209", "Old Savings", "savings", USD, "2209", null, bankId = null, offeredAt = slice.clock.instant())),
        )
        val p = proposal("acct-2209", "Reserve Savings")

        assertNull(p.proposedBank)
        val outcome = slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType, BankChoice.Named("Kestrel Savings Bank"), null)

        assertInstanceOf(PairOutcome.Added::class.java, outcome)
        val bank = slice.connectionStore.banks().single { it.name == "Kestrel Savings Bank" }
        assertEquals(bank.id, slice.connectionStore.bankAccounts().single().bankId)
        assertTrue(slice.connect.knownBanks().any { it.name == "Kestrel Savings Bank" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: no Bank Account is added without a Bank Account type and a Bank; nothing is stored")
    fun noBankAccountWithoutTypeAndBank() = runTest {
        readyToPair()
        val p = proposal("acct-4410", "Checking")
        val bank = BankChoice.Known(p.proposedBank!!.bankId)

        assertEquals(PairOutcome.TypeNotChosen, slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, null, bank, null))
        assertEquals(PairOutcome.BankNotChosen, slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType, null, null))
        assertEquals(PairOutcome.BankNotChosen, slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType, BankChoice.Named("  "), null))
        assertEquals(PairOutcome.BankNotChosen, slice.connect.add(p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType, BankChoice.Known(-1), null))
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
        assertEquals(listOf("Kestrel Bank"), slice.connectionStore.banks().map { it.name })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", requirements = ["C-012"])
    @DisplayName("Steps 13, 15: the added Bank Account is shown as added and is no longer offered, so each Bank Account comes in through one path")
    fun addedBankAccountIsShownAndNoLongerOffered() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking", name = "Kestrel Checking")

        val list = slice.connect.addedBankAccounts()
        val pairing = slice.connect.pairing()

        val shown = list.single()
        assertEquals("Kestrel Checking", shown.name)
        assertEquals("Kestrel Bank", shown.bankName)
        assertEquals("Kestrel Feeds", shown.sourceName)
        assertEquals("Household", shown.budgetName)
        assertEquals("Checking", shown.budgetAccountName)
        assertEquals(BankAccountType.Checking, shown.type)
        assertTrue(pairing.sources.flatMap { it.offered }.none { it.offeredBankAccountId == offeredId("acct-4410") })
        assertEquals(PairOutcome.NotOffered, slice.connect.propose(offeredId("acct-4410"), budgetAccountId("Rewards Card")))
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
    @UseCase(id = "UC-013", scenario = "A1: Budget App Cannot Be Read or Refuses Slussen's Credentials", requirements = ["NFR-004"])
    @DisplayName("A1: a Budget App that cannot be reached from the device, a lost connection, leaves the Budgets served unchanged and says so")
    fun budgetAppTheDeviceCannotReachLeavesServedBudgetsUnchanged() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        val before = slice.connectionStore.budgets()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.ConnectionLost)

        val read = slice.connect.readBudgets()

        assertEquals(BudgetsRead.CouldNotRead(FailureKind.ConnectionLost), read)
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
        pairAndAdd("acct-4410", "Checking")
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
        assertEquals(OfferRemedy.StartFetch, slice.connect.offeredOf(slice.feedsId).remedy)
        assertEquals(OfferRemedy.GrantBankConsent, slice.connect.offeredOf(slice.openBankingId).remedy)
        assertTrue(slice.connect.offeredOf(slice.feedsId).offered.isEmpty())
        assertFalse(slice.connect.offeredOf(slice.feedsId).nothingNew)
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-009"])
    @DisplayName("A4: a Bank Account of a File Format is added with its File Format, the type and the new Bank the Operator chose, the Offered Bank Account that reports its balance, and the name the Operator enters")
    fun bankAccountOfAFileFormatIsAdded() = runTest {
        readyToPair()
        val format = slice.connect.fileFormats().single()

        val outcome = slice.connect.addFromTransactionFiles(
            fileFormatId = format.fileFormatId,
            type = BankAccountType.Savings,
            bank = BankChoice.Named("Harbor Health Trust"),
            balanceOfferedBankAccountId = offeredId("acct-7731"),
            budgetAccountId = budgetAccountId("Reserve Savings"),
            name = "Harbor Health Savings",
            startDate = LocalDate.parse("2026-08-01"),
            currency = USD,
        ) as PairOutcome.Added

        assertEquals("Harbor Health Savings", outcome.name)
        val added = slice.connectionStore.bankAccounts().single()
        assertEquals("Harbor Health Savings", added.name)
        assertEquals(BankAccountType.Savings, added.type)
        assertEquals(format.fileFormatId, added.fileFormatId)
        assertNull(added.dataProviderId)
        assertEquals(slice.feedsId, added.balanceDataProviderId)
        assertEquals("acct-7731", added.balanceProviderReference)
        assertEquals("Harbor Health Trust", slice.connectionStore.banks().single { it.id == added.bankId }.name)
        assertEquals(kestrelBankId(), added.providerBankId)
        assertEquals(LocalDate.parse("2026-08-01"), added.startDate)
        assertEquals(budgetAccountId("Reserve Savings"), added.mapping?.budgetAccountId)
        assertTrue("Harbor Health Savings" in slice.journalRecorder.entries.last().details)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-009"])
    @DisplayName("A4: without a balance Data Provider the Bank Account of a File Format has none; a known Bank can be chosen; a missing name, type or Bank is refused")
    fun bankAccountOfAFileFormatWithoutBalanceSource() = runTest {
        readyToPair()
        val format = slice.connect.fileFormats().single()
        val kestrel = BankChoice.Known(kestrelBankId())
        val checking = budgetAccountId("Checking")

        val unnamed = slice.connect.addFromTransactionFiles(format.fileFormatId, BankAccountType.Savings, kestrel, null, checking, " ", null, USD)
        val untyped = slice.connect.addFromTransactionFiles(format.fileFormatId, null, kestrel, null, checking, "Health Savings", null, USD)
        val noBank = slice.connect.addFromTransactionFiles(format.fileFormatId, BankAccountType.Savings, null, null, checking, "Health Savings", null, USD)
        val outcome = slice.connect.addFromTransactionFiles(format.fileFormatId, BankAccountType.Savings, kestrel, null, checking, "Health Savings", null, USD)

        assertEquals(PairOutcome.NameNotValid, unnamed)
        assertEquals(PairOutcome.TypeNotChosen, untyped)
        assertEquals(PairOutcome.BankNotChosen, noBank)
        assertInstanceOf(PairOutcome.Added::class.java, outcome)
        val added = slice.connectionStore.bankAccounts().single()
        assertNull(added.balanceDataProviderId)
        assertNull(added.balanceProviderReference)
        assertNull(added.providerBankId)
        assertEquals(kestrelBankId(), added.bankId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-002"])
    @DisplayName("A5: a Budget Account that already receives through an Account Mapping is refused, with why")
    fun alreadyMappedBudgetAccountIsRefused() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")

        val outcome = slice.connect.propose(offeredId("acct-7731"), budgetAccountId("Checking"))

        assertEquals(PairOutcome.BudgetAccountAlreadyMapped("Checking", "CHK ••4410"), outcome)
        assertEquals(1, slice.connectionStore.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency")
    @DisplayName("A5: a Budget Account whose Budget has another currency is refused; no amount is converted")
    fun budgetAccountInAnotherCurrencyIsRefused() = runTest {
        readyToPair()

        val outcome = slice.connect.propose(offeredId("acct-5520"), budgetAccountId("Current"))

        assertEquals(PairOutcome.CurrencyDiffers("Current", EUR, "Aurora Dollar", USD), outcome)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A6: Offered Bank Account Probably Already Added")
    @DisplayName("A6: an Offered Bank Account that is probably an added Bank Account is shown as its proposed match, not as one to add")
    fun probablyAddedBankAccountIsAProposedMatch() = runTest {
        readyToPair()
        val added = pairAndAdd("acct-4410", "Checking") as PairOutcome.Added
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
        assertEquals(PairOutcome.NotOffered, slice.connect.propose(offeredId("acct-4410-new"), budgetAccountId("Rewards Card")))
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A7: Budget App Credentials Not Entered", businessRules = ["BR-008", "BR-015"])
    @DisplayName("A7: without accepted Budget App credentials the Budgets cannot be chosen, the Budget App is not contacted, and the Account Mappings wait for them")
    fun withoutBudgetAppCredentialsNoBudgetIsRead() = runTest {
        val read = slice.connect.readBudgets()

        assertEquals(BudgetsRead.BudgetAppCredentialsMissing, read)
        assertEquals(0, slice.budgetApp.reader.budgetReads)
        assertEquals(MappingsWaitFor.BudgetAppCredentials, slice.settingsOverview.entries().accountMappings.waitingFor)
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
        assertEquals(PairOutcome.Unavailable(refusal), slice.connect.propose(offeredId("acct-4410"), budgetAccountId("Checking")))
        assertEquals(
            PairOutcome.Unavailable(refusal),
            slice.connect.add(offeredId("acct-4410"), budgetAccountId("Checking"), "CHK", BankAccountType.Checking, BankChoice.Named("Kestrel Bank"), null),
        )
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
        assertFalse(slice.connectionStore.budgets().any { it.name == "Care Fund" })
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-003"], requirements = ["NFR-020"])
    @DisplayName("BR-003: every added Bank Account gets its own generated identifier, a random version 4 UUID")
    fun generatedIdentifiersAreRandomVersion4Uuids() = runTest {
        readyToPair()
        val first = pairAndAdd("acct-4410", "Checking") as PairOutcome.Added
        val second = pairAndAdd("acct-1182", "Rewards Card") as PairOutcome.Added

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
    @DisplayName("Failure postconditions: no Bank Account without the Operator's pairing or without a type and Bank, no Budget Account with two Account Mappings, nothing written to the Budget App")
    fun failurePostconditions() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")
        val stale = proposal("acct-1182", "Rewards Card")
        pairAndAdd("acct-7731", "Checking")
        pairAndAdd("acct-5520", "Savings")
        pairAndAdd("acct-7731", "Rewards Card")
        val late = slice.connect.add(
            stale.offeredBankAccountId, stale.budgetAccountId, stale.proposedName, stale.proposedType,
            BankChoice.Known(stale.proposedBank!!.bankId), null,
        )
        assertEquals(PairOutcome.BudgetAccountAlreadyMapped("Rewards Card", "Reserve Saver ••7731"), late)

        val mapped = slice.connectionStore.bankAccounts().mapNotNull { it.mapping?.budgetAccountId }
        assertEquals(mapped.distinct(), mapped)
        assertEquals(2, slice.connectionStore.bankAccounts().size)
        assertTrue(slice.connectionStore.bankAccounts().none { it.providerReference == "acct-1182" })
        assertEquals(0, slice.budgetApp.writer.calls)
    }

    /** An Offered Bank Account whose Data Provider states no currency (BR-010). */
    private suspend fun offerWithoutCurrency() {
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-9001", "Harbor Joint", "checking", null, "9001", amount(310), kestrelBankId(), slice.clock.instant())),
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: an Offered Bank Account that states no currency is paired; Slussen proposes the currency of the chosen Budget Account for the Operator to state")
    fun currencyIsProposedFromTheBudgetAccount() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        offerWithoutCurrency()

        val proposal = proposal("acct-9001", "Checking")

        assertEquals(USD, proposal.proposedCurrency)
        assertFalse(proposal.currencyStatedBySource)
        val inEuro = proposal("acct-9001", "Current")
        assertEquals(EUR, inEuro.proposedCurrency)
        assertFalse(inEuro.currencyStatedBySource)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: the Bank Account is added with the currency the Operator states at step 11")
    fun bankAccountIsAddedWithTheStatedCurrency() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        offerWithoutCurrency()
        val p = proposal("acct-9001", "Checking")

        val outcome = slice.connect.add(
            p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType,
            p.proposedBank?.let { BankChoice.Known(it.bankId) }, null, currency = USD,
        )

        assertInstanceOf(PairOutcome.Added::class.java, outcome)
        assertEquals(USD, slice.connectionStore.bankAccounts().single().currency)
    }

    @Test
    @UseCase(
        id = "UC-013",
        scenario = "A5: Budget Account Already Mapped or in Another Currency",
        businessRules = ["BR-010"],
    )
    @DisplayName("A5, BR-010: a stated currency other than the Budget's is refused at step 12 and nothing is added; no amount is converted")
    fun statedCurrencyOfAnotherBudgetIsRefused() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        offerWithoutCurrency()
        val p = proposal("acct-9001", "Checking")

        val outcome = slice.connect.add(
            p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType,
            p.proposedBank?.let { BankChoice.Known(it.bankId) }, null, currency = EUR,
        )

        assertEquals(PairOutcome.CurrencyDiffers("Checking", USD, p.proposedName, EUR), outcome)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: no Bank Account is added without a currency")
    fun noBankAccountWithoutACurrency() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        offerWithoutCurrency()
        val p = proposal("acct-9001", "Checking")

        val outcome = slice.connect.add(
            p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType,
            p.proposedBank?.let { BankChoice.Known(it.bankId) }, null, currency = null,
        )

        assertEquals(PairOutcome.CurrencyNotStated, outcome)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: a currency the Data Provider states is the Bank Account's; the Operator states one only when it states none")
    fun currencyTheSourceStatesHolds() = runTest {
        readyToPair()
        val p = proposal("acct-4410", "Checking")
        assertEquals(USD, p.proposedCurrency)
        assertTrue(p.currencyStatedBySource)

        slice.connect.add(
            p.offeredBankAccountId, p.budgetAccountId, p.proposedName, p.proposedType,
            p.proposedBank?.let { BankChoice.Known(it.bankId) }, null, currency = EUR,
        )

        assertEquals(USD, slice.connectionStore.bankAccounts().single().currency)
    }

    // S-38 and S-40: imported by default and left out one at a time (steps 5-9, BR-011, BR-012), Manually Kept Accounts
    // (BR-014), the File Format's page (A4) and the entries at the top of Settings (BR-008, BR-015).

    private suspend fun switchOff(vararg references: String) = references.forEach { slice.connect.switchImportOff(offeredId(it)) }

    private suspend fun leftOutReferences(): List<String> = slice.connectionStore.leftOutBankAccounts().map { it.providerReference }

    private suspend fun toMapNames(): List<String?> =
        slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }.offered.map { it.nameOrProduct }

    private suspend fun fileFormatId(): Long = slice.connect.fileFormats().single().fileFormatId

    private suspend fun addHarborHealthSavings(balanceFrom: String? = "acct-7731", budgetAccount: String = "Reserve Savings") =
        slice.connect.addFromTransactionFiles(
            fileFormatId(), BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), balanceFrom?.let { offeredId(it) },
            budgetAccountId(budgetAccount), "Harbor Health Savings", null, USD,
        )

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-011", "BR-012"])
    @DisplayName("Step 5: a Data Provider's page shows each Offered Bank Account with name or product, kind, currency, last four digits, balance and Bank: imported by default, a Left-Out one as not imported, an added one as added without a switch")
    fun stepFiveShowsHowEachOfferedBankAccountStands() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking", name = "Kestrel Checking")
        switchOff("acct-5520")

        val page = slice.connect.offeredOf(slice.feedsId)

        assertEquals("Kestrel Feeds", page.name)
        assertEquals(listOf("CHK", "Pinecrest Rewards Visa", "Reserve Saver", "Aurora Dollar"), page.offered.map { it.offered.nameOrProduct })
        val added = slice.connectionStore.bankAccounts().single()
        assertEquals(ImportState.Added(added.id, "Kestrel Checking", "Checking"), page.offered[0].state)
        assertEquals(listOf(ImportState.ToMap, ImportState.ToMap), page.offered.subList(1, 3).map { it.state })
        assertEquals(ImportState.NotImported(slice.connectionStore.leftOutBankAccounts().single().id), page.offered[3].state)
        assertEquals(listOf(false, true, true, true), page.offered.map { it.hasImportSwitch })
        assertEquals(
            OfferedForPairing(offeredId("acct-1182"), "Pinecrest Rewards Visa", "credit card", USD, "1182", amount(-1268, 400), "Kestrel Bank"),
            page.offered[1].offered,
        )
        assertEquals(3, page.imported)
        assertEquals(4, page.importable)
        assertFalse(page.nothingNew)
        assertNull(page.remedy)
        assertTrue(page.leftOutNotDelivered.isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-012"])
    @DisplayName("Steps 6-7: switching the import of one off makes it a Left-Out Bank Account at once, without a confirmation, remembered by its Data Provider and reference and recorded by name; the others stay imported and to map")
    fun switchingOneOffLeavesItOutAtOnce() = runTest {
        readyToPair()
        slice.clock.advance(Duration.ofMinutes(5))

        val outcome = slice.connect.switchImportOff(offeredId("acct-1182"))

        val leftOut = slice.connectionStore.leftOutBankAccounts().single()
        assertEquals(ImportSwitchedOff.LeftOut(leftOut.id, "Pinecrest Rewards Visa ••1182"), outcome)
        assertEquals("acct-1182", leftOut.providerReference)
        assertEquals(slice.feedsId, leftOut.dataProviderId)
        assertEquals(slice.clock.instant(), leftOut.leftOutAt)
        assertEquals("Pinecrest Rewards Visa ••1182", leftOut.name)
        assertEquals(listOf("CHK", "Reserve Saver", "Aurora Dollar"), toMapNames())
        val recorded = slice.journalRecorder.entries.single { it.eventKind == JournalEventKind.AccountMappingChange }
        assertTrue("Pinecrest Rewards Visa ••1182" in recorded.details, recorded.details)
        assertFalse("acct-" in recorded.details, recorded.details)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-012"])
    @DisplayName("Steps 7-8, BR-012: every Offered Bank Account neither added nor left out is imported by default and a Bank Account to map, one line each with its Source; nothing is left out or fetched without the Operator")
    fun everyOneIsImportedByDefault() = runTest {
        readyToPair()

        val toMap = slice.connect.toMap()

        assertEquals(listOf("CHK", "Pinecrest Rewards Visa", "Reserve Saver", "Aurora Dollar"), toMap.map { it.offered.nameOrProduct })
        assertEquals("4410", toMap.first().offered.lastFourDigits)
        assertTrue(toMap.all { it.sourceName == "Kestrel Feeds" && it.dataProviderId == slice.feedsId })
        assertTrue(slice.connectionStore.leftOutBankAccounts().isEmpty())
        assertTrue(slice.journalRecorder.entries.none { it.eventKind == JournalEventKind.AccountMappingChange })
        assertEquals(0, slice.feeds.fetches)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A12: Nothing Brought In", businessRules = ["BR-011", "BR-015"])
    @DisplayName("A12: switching off every Offered Bank Account leaves each out at once and records each; nothing is to map, and the Account Mappings wait for an imported or added Bank Account")
    fun switchingEveryOneOffLeavesNothingToMap() = runTest {
        readyToPair()

        switchOff("acct-4410", "acct-1182", "acct-7731", "acct-5520")

        assertEquals(listOf("acct-4410", "acct-1182", "acct-7731", "acct-5520"), leftOutReferences())
        assertEquals(4, slice.journalRecorder.entries.count { it.eventKind == JournalEventKind.AccountMappingChange })
        assertTrue(slice.connect.toMap().isEmpty())
        assertEquals(
            AccountMappingsEntry(available = false, toMap = 0, waitingFor = MappingsWaitFor.ImportedBankAccount, isNext = false),
            slice.settingsOverview.entries().accountMappings,
        )
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("BR-012: an added Bank Account, one already left out and one no longer stored have no import switch; switching it off leaves nothing out")
    fun onlyAnImportedOneIsSwitchedOff() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")
        switchOff("acct-1182")

        assertEquals(ImportSwitchedOff.NoSwitch, slice.connect.switchImportOff(offeredId("acct-4410")))
        assertEquals(ImportSwitchedOff.NoSwitch, slice.connect.switchImportOff(offeredId("acct-1182")))
        assertEquals(ImportSwitchedOff.NoSwitch, slice.connect.switchImportOff(-1))
        assertEquals(listOf("acct-1182"), leftOutReferences())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A8: Skip for Now", businessRules = ["BR-012"])
    @DisplayName("A8, BR-012: a Bank Account to map that is skipped for now, or not reached, is neither added nor left out and stays imported and to map")
    fun skippedOnesStayToMap() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")

        assertEquals(listOf("Pinecrest Rewards Visa", "Reserve Saver", "Aurora Dollar"), toMapNames())
        assertTrue(leftOutReferences().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("BR-012: an Offered Bank Account that a later Fetch delivers is imported by default and to map, while the Left-Out ones stay left out")
    fun aLaterDeliveryIsImportedAndTheLeftOutStayLeftOut() = runTest {
        readyToPair()
        switchOff("acct-1182", "acct-7731", "acct-5520")
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf("acct-4410", "acct-1182", "acct-7731", "acct-5520", "acct-3300").map { reference ->
                    DeliveredBankAccount(reference, "Kestrel Bank", "Account $reference", "savings", USD, null, BankAccountResult.NotRead(NotReadCause.NotSelected, amount(10)))
                },
            )
        }

        slice.fetch.fetch(slice.feedsKey)

        assertEquals(listOf("Account acct-4410", "Account acct-3300"), toMapNames())
        assertEquals(listOf("acct-1182", "acct-7731", "acct-5520"), leftOutReferences())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: a Left-Out Bank Account is never read: a Fetch never selects it and its balance is not stored, though it is still in the delivered list")
    fun aLeftOutBankAccountIsNeverRead() = runTest {
        readyToPair()
        switchOff("acct-1182", "acct-7731", "acct-5520")
        pairAndAdd("acct-4410", "Checking")
        slice.feeds.fetchAnswer = { request ->
            val asked = (request.bankAccounts as BankAccountSelection.Selected).bankAccounts.map { it.providerReference }.toSet()
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                listOf("acct-4410", "acct-1182").map { reference ->
                    val result = if (reference in asked) {
                        BankAccountResult.Read(emptyList(), amount(4140, 50))
                    } else {
                        BankAccountResult.NotRead(NotReadCause.NotSelected, amount(-1268, 400))
                    }
                    DeliveredBankAccount(reference, "Kestrel Bank", "Account $reference", "checking", USD, null, result)
                },
            )
        }

        slice.fetch.fetch(slice.feedsKey)

        val selected = (slice.feeds.fetchRequests.single().bankAccounts as BankAccountSelection.Selected).bankAccounts.map { it.providerReference }
        assertEquals(listOf("acct-4410"), selected)
        val leftOutOffer = slice.offeredStore.offered().single { it.providerReference == "acct-1182" }
        assertNull(leftOutOffer.balance)
        assertEquals(listOf("acct-1182", "acct-7731", "acct-5520"), leftOutReferences())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A6: Offered Bank Account Probably Already Added", businessRules = ["BR-011", "BR-012"])
    @DisplayName("A6, BR-011, BR-012: a proposed match has no import switch and is never left out or to map, and a Left-Out Bank Account is never shown as the proposed match of an added Bank Account")
    fun proposedMatchesAndLeftOutStayApart() = runTest {
        readyToPair()
        val added = pairAndAdd("acct-4410", "Checking") as PairOutcome.Added
        val bank = kestrelBankId()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410-new", "CHK", "checking", USD, "4410", null, bank, slice.clock.instant(), proposedBankAccountId = added.bankAccountId),
                ExampleData.offered("acct-1182", "Pinecrest Rewards Visa", "credit card", USD, "1182", null, bank, slice.clock.instant(), proposedBankAccountId = added.bankAccountId),
                ExampleData.offered("acct-3300", "Kestrel Auto Loan", "loan", USD, "3300", null, bank, slice.clock.instant()),
            ),
        )

        val feeds = slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }
        val page = slice.connect.offeredOf(slice.feedsId)
        val switchedOff = slice.connect.switchImportOff(offeredId("acct-4410-new"))
        switchOff("acct-3300")

        assertEquals(listOf("CHK", "Pinecrest Rewards Visa"), feeds.proposedMatches.map { it.offered.nameOrProduct })
        assertEquals(listOf("Kestrel Auto Loan"), feeds.offered.map { it.nameOrProduct })
        assertEquals(ImportState.ProbablyAdded(added.bankAccountId, "CHK ••4410"), page.offered.first().state)
        assertFalse(page.offered.first().hasImportSwitch)
        assertEquals(ImportSwitchedOff.NoSwitch, switchedOff)
        assertEquals(listOf("acct-3300"), leftOutReferences())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A10: Bring Back a Left-Out Bank Account", businessRules = ["BR-011", "BR-012"])
    @DisplayName("A10: switching the import of a Left-Out Bank Account on again brings it back at once, recorded by name, and it is imported and to map again from the latest delivery that holds it")
    fun switchingTheImportOnBringsItBack() = runTest {
        readyToPair()
        switchOff("acct-7731")
        val reserve = slice.connectionStore.leftOutBankAccounts().single()

        val outcome = slice.connect.bringBack(reserve.id)

        assertEquals(BroughtBack.Offered("Reserve Saver ••7731", offeredNow = true), outcome)
        assertTrue(leftOutReferences().isEmpty())
        assertEquals(ImportState.ToMap, slice.connect.offeredOf(slice.feedsId).offered.single { it.offered.nameOrProduct == "Reserve Saver" }.state)
        assertTrue("Reserve Saver" in toMapNames())
        val recorded = slice.journalRecorder.entries.last()
        assertEquals(JournalEventKind.AccountMappingChange, recorded.eventKind)
        assertTrue("Reserve Saver ••7731" in recorded.details && "imported again" in recorded.details, recorded.details)
        assertFalse("acct-7731" in recorded.details)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A10: Bring Back a Left-Out Bank Account", businessRules = ["BR-011"])
    @DisplayName("A10 step 2: a Left-Out Bank Account that the latest delivery no longer holds is still shown on its Data Provider's page, and switched on it is imported from the next delivery that holds it")
    fun broughtBackFromTheNextDelivery() = runTest {
        readyToPair()
        switchOff("acct-5520")
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", amount(4140, 50), kestrelBankId(), slice.clock.instant())),
        )
        val dollar = slice.connectionStore.leftOutBankAccounts().single { it.providerReference == "acct-5520" }

        assertEquals(listOf("Aurora Dollar ••5520"), slice.connect.offeredOf(slice.feedsId).leftOutNotDelivered.map { it.name })
        assertEquals(BroughtBack.Offered("Aurora Dollar ••5520", offeredNow = false), slice.connect.bringBack(dollar.id))
        assertEquals(BroughtBack.NotFound, slice.connect.bringBack(dollar.id))
        assertTrue(slice.connect.offeredOf(slice.feedsId).leftOutNotDelivered.isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("Step 10, BR-013: the card lists the free Budget Accounts of the Offered Bank Account's currency and proposes none when more than one is free; every free Budget Account of each served Budget is reachable")
    fun theCardListsTheFreeBudgetAccountsOfItsCurrency() = runTest {
        readyToPair()

        val card = slice.connect.card(offeredId("acct-4410"))!!

        assertEquals(listOf("Checking", "Rewards Card", "Reserve Savings"), card.listed.map { it.name })
        assertNull(card.proposedBudgetAccountId)
        assertEquals(listOf("Checking", "Rewards Card", "Reserve Savings", "Current", "Savings", "Card"), card.allFree.map { it.name })
        assertEquals("CHK ••4410", card.proposedName)
        assertEquals(BankAccountType.Checking, card.proposedType)
        assertEquals(KnownBank(kestrelBankId(), "Kestrel Bank"), card.proposedBank)
        assertEquals(USD, card.currency)
        assertEquals("Kestrel Feeds", card.sourceName)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("BR-013: when exactly one free Budget Account is of the currency, it is proposed; the proposal is applied only when the Operator adds the Bank Account with it")
    fun theOnlyFreeBudgetAccountOfTheCurrencyIsProposed() = runTest {
        readyToPair()
        pairAndAdd("acct-1182", "Rewards Card")
        pairAndAdd("acct-7731", "Reserve Savings")

        val card = slice.connect.card(offeredId("acct-4410"))!!

        assertEquals(listOf("Checking"), card.listed.map { it.name })
        assertEquals(budgetAccountId("Checking"), card.proposedBudgetAccountId)
        assertEquals(2, slice.connectionStore.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("BR-013: no balance or name is compared to propose one: two free Budget Accounts of the currency, one with the same balance and name, give no proposal")
    fun noBalanceOrNameMatching() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-0001", "Current", "current", EUR, "0001", amount(3402, 300), kestrelBankId(), slice.clock.instant())),
        )

        val card = slice.connect.card(offeredId("acct-0001"))!!

        assertEquals(listOf("Current", "Savings", "Card"), card.listed.map { it.name })
        assertNull(card.proposedBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-010", "BR-013"])
    @DisplayName("BR-013, BR-010: for an Offered Bank Account that states no currency, every free Budget Account is listed, and none is proposed while more than one is free")
    fun withoutACurrencyEveryFreeBudgetAccountIsListed() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        offerWithoutCurrency()

        val card = slice.connect.card(offeredId("acct-9001"))!!

        assertEquals(listOf("Checking", "Rewards Card", "Reserve Savings", "Current", "Savings", "Card"), card.listed.map { it.name })
        assertNull(card.proposedBudgetAccountId)
        assertNull(card.currency)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-013"])
    @DisplayName("Step 10: an Offered Bank Account that is left out, added or no longer stored has no card")
    fun noCardForOneNotOffered() = runTest {
        readyToPair()
        switchOff("acct-1182")
        pairAndAdd("acct-4410", "Checking")

        assertNull(slice.connect.card(offeredId("acct-4410")))
        assertNull(slice.connect.card(offeredId("acct-1182")))
        assertNull(slice.connect.card(-1))
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004 (P9-04): the last four digits are added to the proposed name only when the name or product does not already end with them, so they never appear twice")
    fun lastFourDigitsAreNotAddedTwice() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-9034", "Pinecrest Business Visa-9034", "credit card", USD, "9034", amount(-88, 100), kestrelBankId(), slice.clock.instant()),
                ExampleData.offered("acct-1182", "Pinecrest Rewards Visa", "credit card", USD, "1182", amount(-1268, 400), kestrelBankId(), slice.clock.instant()),
            ),
        )

        assertEquals("Pinecrest Business Visa-9034", slice.connect.card(offeredId("acct-9034"))!!.proposedName)
        assertEquals("Pinecrest Business Visa-9034", proposal("acct-9034", "Rewards Card").proposedName)
        assertEquals("Pinecrest Rewards Visa ••1182", slice.connect.card(offeredId("acct-1182"))!!.proposedName)
        val leftOut = slice.connect.switchImportOff(offeredId("acct-9034")) as ImportSwitchedOff.LeftOut
        assertEquals("Pinecrest Business Visa-9034", leftOut.name)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A9: Mark or Unmark a Budget Account Manually Kept", businessRules = ["BR-002", "BR-013", "BR-014"])
    @DisplayName("A9: Receives from Slussen switched off marks a free Budget Account as a Manually Kept Account at once, recorded in the Decision Journal, no longer offered for pairing or proposed")
    fun switchingReceivesOffMarksIt() = runTest {
        readyToPair()

        val outcome = slice.connect.setReceivesFromSlussen(budgetAccountId("Reserve Savings"), receives = false)

        assertEquals(KeepOutcome.Marked, outcome)
        assertTrue(slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Reserve Savings" }.manuallyKept)
        assertTrue(slice.connect.pairing().freeBudgetAccounts.none { it.name == "Reserve Savings" })
        assertEquals(listOf("Checking", "Rewards Card"), slice.connect.card(offeredId("acct-7731"))!!.listed.map { it.name })
        assertEquals(false, slice.connect.servedBudgetAccounts().first().budgetAccounts.single { it.name == "Reserve Savings" }.receivesFromSlussen)
        val recorded = slice.journalRecorder.entries.last()
        assertTrue("Reserve Savings" in recorded.details && "Manually Kept" in recorded.details, recorded.details)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A9: Mark or Unmark a Budget Account Manually Kept", businessRules = ["BR-014"])
    @DisplayName("A9: Receives from Slussen switched on again unmarks it, recorded in the Decision Journal, and it is offered for pairing again; one not marked is not unmarked")
    fun switchingReceivesOnUnmarksIt() = runTest {
        readyToPair()
        slice.connect.setReceivesFromSlussen(budgetAccountId("Reserve Savings"), receives = false)

        val outcome = slice.connect.setReceivesFromSlussen(budgetAccountId("Reserve Savings"), receives = true)

        assertEquals(KeepOutcome.Unmarked, outcome)
        assertFalse(slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Reserve Savings" }.manuallyKept)
        assertTrue(slice.connect.pairing().freeBudgetAccounts.any { it.name == "Reserve Savings" })
        assertEquals(true, slice.connect.servedBudgetAccounts().first().budgetAccounts.single { it.name == "Reserve Savings" }.receivesFromSlussen)
        val recorded = slice.journalRecorder.entries.last()
        assertTrue("Reserve Savings" in recorded.details && "offered for pairing again" in recorded.details, recorded.details)
        assertEquals(KeepOutcome.NotManuallyKept, slice.connect.setReceivesFromSlussen(budgetAccountId("Checking"), receives = true))
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A9: Mark or Unmark a Budget Account Manually Kept", businessRules = ["BR-014"])
    @DisplayName("BR-014: a Manually Kept Account is never paired, and only a free Budget Account can be marked: not one that receives through an Account Mapping")
    fun aManuallyKeptAccountIsNeverPaired() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")
        slice.connect.setReceivesFromSlussen(budgetAccountId("Rewards Card"), receives = false)

        val mapped = slice.connect.setReceivesFromSlussen(budgetAccountId("Checking"), receives = false)
        val p = slice.connect.card(offeredId("acct-1182"))!!
        val paired = slice.connect.add(
            p.offered.offeredBankAccountId, budgetAccountId("Rewards Card"), p.proposedName, p.proposedType,
            p.proposedBank?.let { BankChoice.Known(it.bankId) }, null,
        )

        assertEquals(KeepOutcome.StillReceives("CHK ••4410"), mapped)
        assertFalse(slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.manuallyKept)
        assertEquals(PairOutcome.NotOffered, paired)
        assertEquals(KeepOutcome.NotFree, slice.connect.setReceivesFromSlussen(budgetAccountId("Rewards Card"), receives = false))
        assertEquals(1, slice.connectionStore.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A11: No Offered Bank Account Left to Decide", businessRules = ["BR-006", "BR-012"])
    @DisplayName("A11: when every stored Offered Bank Account of a Data Provider is added, left out, a proposed match or the balance of a File Format's Bank Account, nothing new is to import and none of them is to map; with none stored it is A3 instead")
    fun nothingNewToImport() = runTest {
        readyToPair()
        assertFalse(slice.connect.offeredOf(slice.feedsId).nothingNew)
        pairAndAdd("acct-4410", "Checking")
        switchOff("acct-1182", "acct-5520")
        addHarborHealthSavings()

        val page = slice.connect.offeredOf(slice.feedsId)

        assertTrue(page.nothingNew)
        assertTrue(slice.connect.toMap().isEmpty())
        assertEquals(ImportState.ReportsBalance("Harbor Health Savings"), page.offered.single { it.offered.nameOrProduct == "Reserve Saver" }.state)
        val empty = TestSlice()
        empty.enterCredentials()
        assertFalse(empty.connect.offeredOf(empty.feedsId).nothingNew)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-011"])
    @DisplayName("A4, BR-006: the Offered Bank Accounts offered for a File Format's balance include the Left-Out ones; choosing one brings it back, recorded by name")
    fun aLeftOutBalanceSourceIsBroughtBack() = runTest {
        readyToPair()
        switchOff("acct-1182", "acct-7731", "acct-5520")

        val sources = slice.connect.balanceSources()
        val outcome = addHarborHealthSavings()

        assertEquals(listOf("CHK", "Pinecrest Rewards Visa", "Reserve Saver", "Aurora Dollar"), sources.map { it.offered.nameOrProduct })
        assertEquals(listOf(false, true, true, true), sources.map { it.leftOut })
        assertInstanceOf(PairOutcome.Added::class.java, outcome)
        assertEquals(listOf("acct-1182", "acct-5520"), leftOutReferences())
        assertTrue(slice.journalRecorder.entries.any { "Reserve Saver ••7731" in it.details && "brought back" in it.details })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-012"])
    @DisplayName("BR-006, BR-012: an Offered Bank Account that reports the balance of a File Format's Bank Account has no import switch, is never to map and never left out")
    fun aBalanceSourceHasNoImportSwitch() = runTest {
        readyToPair()
        addHarborHealthSavings()

        val switchedOff = slice.connect.switchImportOff(offeredId("acct-7731"))

        assertEquals(ImportSwitchedOff.NoSwitch, switchedOff)
        assertFalse(slice.connect.offeredOf(slice.feedsId).offered.single { it.offered.nameOrProduct == "Reserve Saver" }.hasImportSwitch)
        assertTrue(slice.connect.toMap().none { it.offered.nameOrProduct == "Reserve Saver" })
        assertTrue(leftOutReferences().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006"])
    @DisplayName("A4 step 1, BR-006: a File Format's page opens at any time; while no Budget is served, a Bank Account can only be added once one is chosen, and adding is refused")
    fun fileFormatPageWaitsForAServedBudget() = runTest {
        slice.enterCredentials()
        offerFeedsBankAccounts()

        val page = slice.connect.fileFormatPage(fileFormatId())

        assertEquals("Harbor Health statement", page.name)
        assertTrue(page.bankAccounts.isEmpty())
        assertFalse(page.canAdd)
        assertTrue(page.freeBudgetAccounts.isEmpty())
        assertEquals(
            PairOutcome.NotOffered,
            slice.connect.addFromTransactionFiles(fileFormatId(), BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), null, -1, "Harbor Health Savings", null, USD),
        )
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-006", "BR-012"])
    @DisplayName("A4 steps 1-3: a Bank Account added on the File Format's page is listed there with its Bank, type, currency, balance source and Account Mapping, and with the Account Mappings, never among the Bank Accounts to map")
    fun fileFormatPageListsItsBankAccounts() = runTest {
        readyToPair()
        addHarborHealthSavings()

        val page = slice.connect.fileFormatPage(fileFormatId())

        val added = slice.connectionStore.bankAccounts().single()
        val balance = slice.connect.balanceSources().single { it.offered.nameOrProduct == "Reserve Saver" }
        assertEquals(
            listOf(FileFormatBankAccount(added.id, "Harbor Health Savings", "Harbor Health Trust", BankAccountType.Savings, USD, balance, "Reserve Savings")),
            page.bankAccounts,
        )
        assertTrue(page.canAdd)
        assertEquals(listOf("Checking", "Rewards Card", "Current", "Savings", "Card"), page.freeBudgetAccounts.map { it.name })
        assertEquals(listOf(BankAccountType.Checking, BankAccountType.Savings, BankAccountType.CreditCard, BankAccountType.Other), page.types)
        assertTrue(slice.connect.toMap().none { it.offered.nameOrProduct == "Reserve Saver" })
        assertEquals("Reserve Savings", slice.connect.addedBankAccounts().single().budgetAccountName)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A4: Bank Account of a File Format", businessRules = ["BR-010"])
    @DisplayName("BR-010: a File Format's Bank Account has the currency the Operator states, proposed from the Offered Bank Account that reports its balance when that one states one, otherwise from the chosen Budget Account")
    fun fileFormatCurrencyIsProposedAndStated() = runTest {
        readyToPair()

        assertEquals(USD, slice.connect.proposedFileFormatCurrency(budgetAccountId("Current"), offeredId("acct-7731")))
        assertEquals(EUR, slice.connect.proposedFileFormatCurrency(budgetAccountId("Current"), null))
        assertNull(slice.connect.proposedFileFormatCurrency(null, null))
        val outcome = addHarborHealthSavings()

        assertInstanceOf(PairOutcome.Added::class.java, outcome)
        assertEquals(USD, slice.connectionStore.bankAccounts().single().currency)
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-010"])
    @DisplayName("A5, BR-010: a File Format's Bank Account in a currency other than its Budget's is refused, and none is added without a currency; no amount is converted")
    fun fileFormatCurrencyIsChecked() = runTest {
        readyToPair()

        val otherCurrency = addHarborHealthSavings(balanceFrom = null, budgetAccount = "Current")
        val noCurrency = slice.connect.addFromTransactionFiles(
            fileFormatId(), BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), null, budgetAccountId("Reserve Savings"),
            "Harbor Health Savings", null, currency = null,
        )

        assertEquals(PairOutcome.CurrencyDiffers("Current", EUR, "Harbor Health Savings", USD), otherCurrency)
        assertEquals(PairOutcome.CurrencyNotStated, noCurrency)
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-015"])
    @DisplayName("BR-015: the Account Mappings wait first for the Budget App's credentials, then for a chosen Budget, then for an imported or added Bank Account, and are available from then on with the number to map")
    fun accountMappingsBecomeAvailableInOrder() = runTest {
        suspend fun mappings() = slice.settingsOverview.entries().accountMappings

        assertEquals(MappingsWaitFor.BudgetAppCredentials, mappings().waitingFor)
        slice.enterCredentials()
        assertEquals(MappingsWaitFor.ChosenBudget, mappings().waitingFor)
        chooseHouseholdAndSummerHouse()
        assertEquals(MappingsWaitFor.ImportedBankAccount, mappings().waitingFor)
        assertFalse(mappings().available)
        offerFeedsBankAccounts()

        assertEquals(AccountMappingsEntry(available = true, toMap = 4, waitingFor = null, isNext = true), mappings())
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-006", "BR-015"])
    @DisplayName("BR-015: a Bank Account added on a File Format's page makes the Account Mappings available, with nothing to map")
    fun aFileFormatBankAccountMakesTheAccountMappingsAvailable() = runTest {
        slice.enterCredentials()
        chooseHouseholdAndSummerHouse()

        slice.connect.addFromTransactionFiles(
            fileFormatId(), BankAccountType.Savings, BankChoice.Named("Harbor Health Trust"), null, budgetAccountId("Reserve Savings"),
            "Harbor Health Savings", null, USD,
        )

        assertEquals(AccountMappingsEntry(available = true, toMap = 0, waitingFor = null, isNext = false), slice.settingsOverview.entries().accountMappings)
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-015"])
    @DisplayName("BR-015: once available, the Account Mappings stay available when the Budget App's credentials are later refused or removed, so that the added Bank Accounts stay reachable")
    fun accountMappingsStayAvailable() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")
        switchOff("acct-1182", "acct-7731", "acct-5520")
        assertEquals(AccountMappingsEntry(available = true, toMap = 0, waitingFor = null, isNext = false), slice.settingsOverview.entries().accountMappings)

        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused
        slice.connect.readBudgets()
        assertTrue(slice.settingsOverview.entries().accountMappings.available)
        slice.credentials.remove(ServiceKey.budgetApp)

        assertTrue(slice.settingsOverview.entries().accountMappings.available)
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-008"])
    @DisplayName("BR-008: Settings names what comes next on the first entry that still needs it: the Budget App's credentials, the Budgets, a Data Provider's credentials, its Fetch or Bank Consent, then the Bank Accounts to map; nothing once every one is mapped or left out")
    fun whatComesNextFollowsTheOrderOfSettingUp() = runTest {
        suspend fun marked(): List<String> {
            val top = slice.settingsOverview.entries()
            return top.entries.filter { it.isNext }.map { it.name } + listOfNotNull("Account Mappings".takeIf { top.accountMappings.isNext })
        }
        suspend fun next() = slice.settingsOverview.next()

        assertEquals(NextThing.EnterBudgetAppCredentials(ExampleData.BUDGET_APP_NAME), next())
        assertEquals(listOf(ExampleData.BUDGET_APP_NAME), marked())
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        assertEquals(NextThing.ChooseBudgets(ExampleData.BUDGET_APP_NAME), next())
        chooseHouseholdAndSummerHouse()
        assertEquals(NextThing.EnterDataProviderCredentials, next())
        assertEquals(listOf("Kestrel Feeds"), marked())
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        assertEquals(NextThing.FetchOrGrantBankConsent(listOf("Kestrel Feeds"), listOf("Aurora Open Banking")), next())
        assertEquals(listOf("Kestrel Feeds"), marked())
        offerFeedsBankAccounts()
        assertEquals(NextThing.MapBankAccounts(4), next())
        assertEquals(listOf("Account Mappings"), marked())
        pairAndAdd("acct-4410", "Checking")
        switchOff("acct-1182", "acct-7731", "acct-5520")

        assertNull(next())
        assertTrue(marked().isEmpty())
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-011", "BR-014"])
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, nothing is left out, brought back or marked as kept by hand")
    fun leavingOutWaitsForTheUpload() = runTest {
        readyToPair()
        switchOff("acct-5520")
        val dollar = slice.connectionStore.leftOutBankAccounts().single()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val refusal = OperationRefusal.UploadPlanAwaitingConfirmation

        assertEquals(ImportSwitchedOff.Unavailable(refusal), slice.connect.switchImportOff(offeredId("acct-4410")))
        assertEquals(BroughtBack.Unavailable(refusal), slice.connect.bringBack(dollar.id))
        assertEquals(KeepOutcome.Unavailable(refusal), slice.connect.setReceivesFromSlussen(budgetAccountId("Checking"), receives = false))
        assertEquals(listOf("acct-5520"), leftOutReferences())
        assertTrue(slice.connectionStore.budgets().flatMap { it.budgetAccounts }.none { it.manuallyKept })
    }

    @Test
    @UseCase(id = "UC-013")
    @DisplayName("Success postconditions: the added Bank Accounts, the Left-Out Bank Accounts not to map and never read, the imported ones not yet added still to map, the Manually Kept Accounts not offered")
    fun successPostconditions() = runTest {
        readyToPair()
        switchOff("acct-7731", "acct-5520")
        pairAndAdd("acct-4410", "Checking")
        slice.connect.setReceivesFromSlussen(budgetAccountId("Reserve Savings"), receives = false)

        val pairing = slice.connect.pairing()

        assertEquals(listOf("CHK ••4410"), slice.connectionStore.bankAccounts().map { it.name })
        assertEquals(listOf("Pinecrest Rewards Visa"), pairing.sources.single { it.name == "Kestrel Feeds" }.offered.map { it.nameOrProduct })
        assertEquals(listOf("Reserve Saver ••7731", "Aurora Dollar ••5520"), pairing.leftOut.map { it.name })
        assertEquals(listOf("Rewards Card", "Current", "Savings", "Card"), pairing.freeBudgetAccounts.map { it.name })
        assertEquals(0, slice.budgetApp.writer.calls)
    }
}
