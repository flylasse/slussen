package slussen.core.mapping

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.connection.BudgetsRead
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
 * The part of Manage Account Mappings (UC-009) that Phase 7a builds: A14, changing the Bank Account type or the Bank of
 * a Bank Account (BR-012, S-32). The rest of UC-009 comes with the slice that builds it.
 */
@DisplayName("UC-009: Manage Account Mappings, the Bank Account type and the Bank")
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
}
