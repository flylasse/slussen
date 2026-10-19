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

    /** Steps 6-10 with everything the Operator accepts as proposed, or the [name] and [startDate] given. */
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
    @DisplayName("Step 5, BR-002 (S-35): after one Offered Bank Account is added, the next Fetch reads only that one, and the Offered Bank Accounts not yet added keep showing the balance the Data Provider gave with the list")
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
    @DisplayName("Step 5: a Budget Account that receives through an Account Mapping is not offered for pairing")
    fun mappedBudgetAccountIsNotOffered() = runTest {
        readyToPair()
        pairAndAdd("acct-4410", "Checking")

        val pairing = slice.connect.pairing()

        assertTrue(pairing.freeBudgetAccounts.none { it.name == "Checking" })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("Steps 6-8: pairing proposes the name, the Bank Account type from the kind and the Bank the Data Provider names, and adds nothing yet")
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
    @DisplayName("Steps 9-10: the Bank Account is added with a generated identifier, how its Source identifies it, the accepted or edited name, type, Bank, Start Date and its Account Mapping, and recorded in the Decision Journal")
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
    @DisplayName("Step 9: the proposed name is accepted and the Start Date left empty")
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
    @DisplayName("Step 9: an empty or too long name is refused and nothing is added; the name never identifies the Bank Account")
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
    @DisplayName("Step 11: the added Bank Account is shown as added and is no longer offered, so each Bank Account comes in through one path")
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

        val unnamed = slice.connect.addFromTransactionFiles(format.fileFormatId, BankAccountType.Savings, kestrel, null, checking, " ", null)
        val untyped = slice.connect.addFromTransactionFiles(format.fileFormatId, null, kestrel, null, checking, "Health Savings", null)
        val noBank = slice.connect.addFromTransactionFiles(format.fileFormatId, BankAccountType.Savings, null, null, checking, "Health Savings", null)
        val outcome = slice.connect.addFromTransactionFiles(format.fileFormatId, BankAccountType.Savings, kestrel, null, checking, "Health Savings", null)

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
    @DisplayName("BR-010: the Bank Account is added with the currency the Operator states at step 9")
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
    @DisplayName("A5, BR-010: a stated currency other than the Budget's is refused at step 9 and nothing is added; no amount is converted")
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
}
