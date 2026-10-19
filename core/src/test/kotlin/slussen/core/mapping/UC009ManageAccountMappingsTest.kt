package slussen.core.mapping

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.connection.BringIn
import slussen.core.connection.BroughtBack
import slussen.core.connection.BudgetsRead
import slussen.core.connection.KeepOutcome
import slussen.core.connection.KnownBank
import slussen.core.connection.PairOutcome
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.StoredBankAccount
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.TestSlice
import java.time.Instant

/**
 * The parts of Manage Account Mappings (UC-009) built so far: A14, changing the Bank Account type or the Bank of a Bank
 * Account (BR-012, S-32, Phase 7a), and A15, A16, the Left-Out Bank Accounts and the Manually Kept Accounts apart from
 * the Bank Accounts (BR-013, S-38, Phase 8d). The rest of UC-009 comes with the slice that builds it.
 */
@DisplayName("UC-009: Manage Account Mappings, the Bank Account type and the Bank, Left-Out Bank Accounts and Manually Kept Accounts")
class UC009ManageAccountMappingsTest {
    private val slice = TestSlice()
    private var checkingId = 0L
    private var cardId = 0L

    private suspend fun budgetAccountId(name: String): Long =
        slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun bank(name: String): Long = slice.connectionStore.bankNamed(name).id

    private suspend fun bankAccount(id: Long): StoredBankAccount = slice.connectionStore.bankAccounts().single { it.id == id }

    /**
     * Precondition: Kestrel Checking (Checking, Kestrel Bank) with the Transfer Partner Reserve Savings, and Pinecrest
     * Rewards (Credit Card, Pinecrest Card Co.) funded by Checking, both added through UC-013.
     */
    private suspend fun twoBankAccounts() {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
        val kestrel = bank("Kestrel Bank")
        val pinecrest = bank("Pinecrest Card Co.")
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, kestrel, slice.clock.instant()),
                ExampleData.offered("acct-1182", "Rewards", "credit card", USD, "1182", null, pinecrest, slice.clock.instant()),
            ),
        )
        val offered = slice.offeredStore.offered().associate { it.providerReference to it.id }
        checkingId = (
            slice.connect.add(
                offered.getValue("acct-4410"), budgetAccountId("Checking"), "Kestrel Checking", BankAccountType.Checking,
                BankChoice.Known(kestrel), null,
            ) as PairOutcome.Added
            ).bankAccountId
        cardId = (
            slice.connect.add(
                offered.getValue("acct-1182"), budgetAccountId("Rewards Card"), "Pinecrest Rewards", BankAccountType.CreditCard,
                BankChoice.Known(pinecrest), null,
            ) as PairOutcome.Added
            ).bankAccountId
        slice.connectionStore.link(checkingId, transferPartner = budgetAccountId("Reserve Savings"))
        slice.connectionStore.link(cardId, fundedBy = budgetAccountId("Checking"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 1: shows the current type and Bank, the four types and the Banks Slussen knows, and what a type would clear")
    fun showsTheCurrentTypeAndBankAndTheChoices() = runTest {
        twoBankAccounts()

        val shown = slice.mappings.typeAndBank(checkingId)!!

        assertEquals(BankAccountType.Checking, shown.type)
        assertEquals(KnownBank(bank("Kestrel Bank"), "Kestrel Bank"), shown.bank)
        assertEquals("Kestrel Checking", shown.name)
        assertEquals("Kestrel Feeds", shown.sourceName)
        assertEquals(listOf(BankAccountType.Checking, BankAccountType.Savings, BankAccountType.CreditCard, BankAccountType.Other), shown.types)
        assertEquals(listOf("Kestrel Bank", "Pinecrest Card Co."), shown.knownBanks.map { it.name })
        assertEquals(mapOf(BankAccountType.CreditCard to ClearedLink(LinkedBudgetAccount.TransferPartner, "Reserve Savings")), shown.clearedBy)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 steps 2-4: another type and another known Bank are stored without any confirmation, also after sends, and recorded with the old and new values")
    fun typeAndBankAreChangedWithoutConfirmation() = runTest {
        twoBankAccounts()
        slice.connectionStore.bankAccountList.replaceAll {
            if (it.id == checkingId) it.copy(mapping = it.mapping!!.copy(firstSentAt = Instant.parse("2026-10-01T08:00:00Z"))) else it
        }
        val before = bankAccount(checkingId)

        val outcome = slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, BankChoice.Known(bank("Pinecrest Card Co.")))

        assertEquals(TypeAndBankOutcome.Stored, outcome)
        val after = bankAccount(checkingId)
        assertEquals(BankAccountType.Savings, after.type)
        assertEquals(bank("Pinecrest Card Co."), after.bankId)
        assertEquals(before.copy(type = BankAccountType.Savings, bankId = bank("Pinecrest Card Co.")), after)
        val entry = slice.journalRecorder.entries.last()
        assertEquals(JournalEventKind.AccountMappingChange, entry.eventKind)
        listOf("Kestrel Checking", "Checking", "Savings", "Kestrel Bank", "Pinecrest Card Co.").forEach {
            assertTrue(it in entry.details, "$it in ${entry.details}")
        }
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("BR-012: a change of the Bank leaves the Bank the Data Provider names, which decides the Bank Consent, as it is")
    fun changingTheBankKeepsTheDataProvidersBank() = runTest {
        twoBankAccounts()
        val kestrel = bank("Kestrel Bank")
        assertEquals(kestrel, bankAccount(checkingId).providerBankId)

        slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Checking, BankChoice.Named("Harbor Savings Bank"))
        slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Checking, BankChoice.Known(bank("Pinecrest Card Co.")))

        assertEquals(bank("Pinecrest Card Co."), bankAccount(checkingId).bankId)
        assertEquals(kestrel, bankAccount(checkingId).providerBankId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("BR-012: the Operator names a new Bank, which Slussen stores and knows from then on")
    fun aNewBankIsNamed() = runTest {
        twoBankAccounts()

        slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Checking, BankChoice.Named("Harbor Savings Bank"))

        val named = slice.connectionStore.banks().single { it.name == "Harbor Savings Bank" }
        assertEquals(named.id, bankAccount(checkingId).bankId)
        assertTrue(slice.mappings.typeAndBank(cardId)!!.knownBanks.any { it.name == "Harbor Savings Bank" })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 3: turning a Bank Account with a Transfer Partner into a credit card states that it will be cleared and stores nothing until the Operator saves")
    fun creditCardClearsTheTransferPartnerAfterConfirmation() = runTest {
        twoBankAccounts()
        val kestrel = BankChoice.Known(bank("Kestrel Bank"))

        val asked = slice.mappings.changeTypeAndBank(checkingId, BankAccountType.CreditCard, kestrel)

        assertEquals(TypeAndBankOutcome.ClearingToConfirm(ClearedLink(LinkedBudgetAccount.TransferPartner, "Reserve Savings")), asked)
        assertEquals(BankAccountType.Checking, bankAccount(checkingId).type)
        assertEquals(budgetAccountId("Reserve Savings"), bankAccount(checkingId).transferPartnerBudgetAccountId)

        val saved = slice.mappings.changeTypeAndBank(checkingId, BankAccountType.CreditCard, kestrel, clearingConfirmed = true)

        assertEquals(TypeAndBankOutcome.Stored, saved)
        assertEquals(BankAccountType.CreditCard, bankAccount(checkingId).type)
        assertNull(bankAccount(checkingId).transferPartnerBudgetAccountId)
        assertNull(bankAccount(checkingId).fundedByBudgetAccountId)
        assertTrue("Transfer Partner Reserve Savings cleared" in slice.journalRecorder.entries.last().details)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 3: turning a credit card with a Funded By into another Bank Account clears the Funded By once the Operator saves")
    fun otherTypeClearsTheFundedByAfterConfirmation() = runTest {
        twoBankAccounts()
        val pinecrest = BankChoice.Known(bank("Pinecrest Card Co."))

        val asked = slice.mappings.changeTypeAndBank(cardId, BankAccountType.Other, pinecrest)
        val saved = slice.mappings.changeTypeAndBank(cardId, BankAccountType.Other, pinecrest, clearingConfirmed = true)

        assertEquals(TypeAndBankOutcome.ClearingToConfirm(ClearedLink(LinkedBudgetAccount.FundedBy, "Checking")), asked)
        assertEquals(TypeAndBankOutcome.Stored, saved)
        assertEquals(BankAccountType.Other, bankAccount(cardId).type)
        assertNull(bankAccount(cardId).fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14 step 3: a change that keeps a credit card a credit card, or another Bank Account another one, clears nothing and asks nothing")
    fun aChangeThatKeepsTheLinkAsksNothing() = runTest {
        twoBankAccounts()

        val outcome = slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, BankChoice.Known(bank("Kestrel Bank")))
        val bankOnly = slice.mappings.changeTypeAndBank(cardId, BankAccountType.CreditCard, BankChoice.Named("Vesta Sparbank"))

        assertEquals(TypeAndBankOutcome.Stored, outcome)
        assertEquals(TypeAndBankOutcome.Stored, bankOnly)
        assertEquals(budgetAccountId("Reserve Savings"), bankAccount(checkingId).transferPartnerBudgetAccountId)
        assertEquals(budgetAccountId("Checking"), bankAccount(cardId).fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("BR-012: a Bank is chosen or named; an empty name or an unknown Bank changes nothing")
    fun aBankMustBeChosen() = runTest {
        twoBankAccounts()
        val before = bankAccount(checkingId)

        assertEquals(TypeAndBankOutcome.BankNotChosen, slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, BankChoice.Named(" ")))
        assertEquals(TypeAndBankOutcome.BankNotChosen, slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, BankChoice.Known(-1)))
        assertEquals(TypeAndBankOutcome.NotFound, slice.mappings.changeTypeAndBank(-1, BankAccountType.Savings, BankChoice.Known(before.bankId)))
        assertEquals(before, bankAccount(checkingId))
        assertNull(slice.mappings.typeAndBank(-1))
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-012"])
    @DisplayName("BR-012: like every change here, it is not possible while an Upload Plan awaits confirmation or an Upload is written")
    fun lockedWhileAnUploadIsPending() = runTest {
        twoBankAccounts()
        val before = bankAccount(checkingId)
        val kestrel = BankChoice.Known(before.bankId)

        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val planOpen = slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, kestrel)
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        val uploading = slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, kestrel)

        assertEquals(TypeAndBankOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), planOpen)
        assertEquals(TypeAndBankOutcome.Unavailable(OperationRefusal.UploadBeingWritten), uploading)
        assertEquals(before, bankAccount(checkingId))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario")
    @DisplayName("Step 1: each Bank Account is shown with its name, Bank, Bank Account type, Source and the Budget Account it is mapped to")
    fun bankAccountsAreShownWithTheirType() = runTest {
        twoBankAccounts()
        slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Savings, BankChoice.Named("Harbor Savings Bank"))

        val shown = slice.connect.addedBankAccounts().single { it.bankAccountId == checkingId }

        assertEquals(BankAccountType.Savings, shown.type)
        assertEquals("Harbor Savings Bank", shown.bankName)
        assertEquals("Kestrel Feeds", shown.sourceName)
        assertEquals("Checking", shown.budgetAccountName)
    }

    @Test
    @UseCase(id = "UC-009")
    @DisplayName("Postconditions: with the Decision Journal switched off the change is still stored and nothing is recorded")
    fun journalOffStillStores() = runTest {
        twoBankAccounts()
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)
        val recorded = slice.journalRecorder.entries.size

        slice.mappings.changeTypeAndBank(checkingId, BankAccountType.Other, BankChoice.Known(bank("Kestrel Bank")))

        assertEquals(BankAccountType.Other, bankAccount(checkingId).type)
        assertEquals(recorded, slice.journalRecorder.entries.size)
        assertFalse(slice.journalRecorder.entries.any { "Other" in it.details })
    }

    /**
     * Precondition of A15: besides the two added Bank Accounts, Kestrel Feeds offers Kids Saver and Pinecrest Business
     * Visa, which the Operator left out in UC-013 (BR-011).
     */
    private suspend fun twoLeftOut() {
        twoBankAccounts()
        val kestrel = bank("Kestrel Bank")
        val pinecrest = bank("Pinecrest Card Co.")
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-4410", "CHK", "checking", USD, "4410", null, kestrel, slice.clock.instant()),
                ExampleData.offered("acct-1182", "Rewards", "credit card", USD, "1182", null, pinecrest, slice.clock.instant()),
                ExampleData.offered("acct-2290", "Kids Saver", "savings", USD, "2290", null, kestrel, slice.clock.instant()),
                ExampleData.offered("acct-9034", "Pinecrest Business Visa", "credit card", USD, "9034", null, pinecrest, slice.clock.instant()),
            ),
        )
        val shown = slice.connect.pairing().sources.flatMap { it.offered }.map { it.offeredBankAccountId }
        slice.connect.bringIn(shown, emptySet()) as BringIn.Confirmed
    }

    private suspend fun leftOutId(name: String): Long = slice.connectionStore.leftOutBankAccounts().single { it.name == name }.id

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-013"])
    @DisplayName("Step 1, BR-013: apart from the Bank Accounts, the Left-Out Bank Accounts of each Data Provider by the name they had when left out, and the Manually Kept Accounts of each served Budget")
    fun leftOutAndManuallyKeptAreShownApart() = runTest {
        twoLeftOut()
        slice.connect.markManuallyKept(budgetAccountId("Reserve Savings"))

        val apart = slice.mappings.apart()

        assertEquals(listOf("Kids Saver ••2290", "Pinecrest Business Visa ••9034"), apart.leftOut.map { it.name })
        assertTrue(apart.leftOut.all { it.sourceName == "Kestrel Feeds" && it.leftOutAt == slice.clock.instant() })
        assertEquals(listOf("Reserve Savings"), apart.manuallyKept.map { it.name })
        assertEquals("Household", apart.manuallyKept.single().budgetName)
        assertTrue(apart.markable.isEmpty())
        assertEquals(listOf("Kestrel Checking", "Pinecrest Rewards"), slice.connect.addedBankAccounts().map { it.name })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A15: Bring Back a Left-Out Bank Account", businessRules = ["BR-013"])
    @DisplayName("A15: a Left-Out Bank Account is brought back without any confirmation, recorded by name, and offered for pairing again from the latest delivery")
    fun aLeftOutBankAccountIsBroughtBack() = runTest {
        twoLeftOut()

        val outcome = slice.mappings.bringBack(leftOutId("Kids Saver ••2290"))

        assertEquals(BroughtBack.Offered("Kids Saver ••2290", offeredNow = true), outcome)
        assertEquals(listOf("Pinecrest Business Visa ••9034"), slice.mappings.apart().leftOut.map { it.name })
        assertEquals(listOf("Kids Saver"), slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }.offered.map { it.nameOrProduct })
        val recorded = slice.journalRecorder.entries.last()
        assertEquals(JournalEventKind.AccountMappingChange, recorded.eventKind)
        assertTrue("Kids Saver ••2290" in recorded.details && "brought back" in recorded.details, recorded.details)
        assertFalse("acct-2290" in recorded.details)
        assertEquals(2, slice.connectionStore.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A15: Bring Back a Left-Out Bank Account")
    @DisplayName("A15 step 2: a Left-Out Bank Account the latest delivery does not hold is offered from the next delivery that holds it")
    fun broughtBackFromTheNextDelivery() = runTest {
        twoLeftOut()
        slice.offeredStore.replaceOffered(slice.feedsId, null, emptyList())

        assertEquals(BroughtBack.Offered("Pinecrest Business Visa ••9034", offeredNow = false), slice.mappings.bringBack(leftOutId("Pinecrest Business Visa ••9034")))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A16: Mark or Unmark a Manually Kept Account", businessRules = ["BR-013"])
    @DisplayName("A16: a free Budget Account of a served Budget is marked as a Manually Kept Account and unmarked again, each recorded, without any confirmation; unmarked, it is offered for pairing again")
    fun aBudgetAccountIsMarkedAndUnmarked() = runTest {
        twoBankAccounts()
        val reserve = budgetAccountId("Reserve Savings")
        assertEquals(listOf("Reserve Savings"), slice.mappings.apart().markable.map { it.name })

        assertEquals(KeepOutcome.Marked, slice.mappings.setManuallyKept(reserve, manuallyKept = true))
        assertTrue(slice.connect.pairing().freeBudgetAccounts.none { it.budgetAccountId == reserve })
        assertEquals(listOf("Reserve Savings"), slice.mappings.apart().manuallyKept.map { it.name })
        assertEquals(KeepOutcome.Unmarked, slice.mappings.setManuallyKept(reserve, manuallyKept = false))

        assertTrue(slice.connect.pairing().freeBudgetAccounts.any { it.budgetAccountId == reserve })
        assertTrue(slice.mappings.apart().manuallyKept.isEmpty())
        val recorded = slice.journalRecorder.entries.takeLast(2).map { it.details }
        assertTrue(recorded.all { "Reserve Savings" in it }, recorded.toString())
        assertTrue("marked as a Manually Kept Account" in recorded[0] && "no longer a Manually Kept Account" in recorded[1], recorded.toString())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A16: Mark or Unmark a Manually Kept Account", businessRules = ["BR-001", "BR-013"])
    @DisplayName("A16, BR-001: a Budget Account that receives through an Account Mapping, or that belongs to a Budget not served, is not marked; one not marked is not unmarked")
    fun onlyAFreeBudgetAccountIsMarked() = runTest {
        twoBankAccounts()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-care"))
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))

        assertEquals(KeepOutcome.StillReceives("Kestrel Checking"), slice.mappings.setManuallyKept(budgetAccountId("Checking"), manuallyKept = true))
        assertEquals(KeepOutcome.NotFree, slice.mappings.setManuallyKept(budgetAccountId("Health Savings"), manuallyKept = true))
        assertEquals(KeepOutcome.NotFree, slice.mappings.setManuallyKept(-1, manuallyKept = true))
        assertEquals(KeepOutcome.NotManuallyKept, slice.mappings.setManuallyKept(budgetAccountId("Reserve Savings"), manuallyKept = false))
        assertTrue(slice.connectionStore.budgets().flatMap { it.budgetAccounts }.none { it.manuallyKept })
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-013"])
    @DisplayName("BR-013: like every change here, bringing back and marking are not possible while an Upload Plan awaits confirmation or an Upload is written")
    fun bringingBackAndMarkingWaitForTheUpload() = runTest {
        twoLeftOut()
        val kids = leftOutId("Kids Saver ••2290")
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val planOpen = slice.mappings.bringBack(kids)
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        val uploading = slice.mappings.setManuallyKept(budgetAccountId("Reserve Savings"), manuallyKept = true)

        assertEquals(BroughtBack.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), planOpen)
        assertEquals(KeepOutcome.Unavailable(OperationRefusal.UploadBeingWritten), uploading)
        assertEquals(2, slice.mappings.apart().leftOut.size)
        assertTrue(slice.mappings.apart().manuallyKept.isEmpty())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004: a Left-Out Bank Account is never proposed as a likely Bank Account, until it is brought back")
    fun aLeftOutBankAccountIsNeverAProposedMatch() = runTest {
        twoLeftOut()
        val kestrel = bank("Kestrel Bank")
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-2290", "Kids Saver", "savings", USD, "2290", null, kestrel, slice.clock.instant(), proposedBankAccountId = checkingId)),
        )

        assertTrue(slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }.proposedMatches.isEmpty())
        slice.mappings.bringBack(leftOutId("Kids Saver ••2290"))
        assertEquals(listOf("Kestrel Checking"), slice.connect.pairing().sources.single { it.name == "Kestrel Feeds" }.proposedMatches.map { it.bankAccountName })
    }
}
