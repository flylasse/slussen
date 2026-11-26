package slussen.core.transfer

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonReport
import slussen.core.comparison.ComparisonStart
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.JournalEventKind
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferType
import slussen.core.status.NotYetUploaded
import slussen.core.status.StatusReason
import slussen.core.status.TransferShown
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Review Transfers (UC-005) as far as the transactions plan's Phase 7b builds it: Transfers detected inside the
 * Comparison's conclusions (UC-003 step 4) from stored data, with BR-001..BR-006, BR-010, BR-012..BR-014, A4, A5 and A8
 * as far as they give a Transaction Status, the stored Transfers with the decision Proposed, the Eligible Transactions
 * and counts (UC-003 BR-014) and the Decision Journal (UC-011 BR-001). The Transfer Decisions (steps 2-6, A1-A3, A9,
 * BR-007, BR-008, BR-011), A6, A7 and BR-009 are later initiatives, and every screen is Phase 7c. Every name, amount,
 * payee and identifier is invented (C-005).
 */
@DisplayName("UC-005: Review Transfers")
class UC005ReviewTransfersTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private val today: LocalDate = LocalDate.of(2026, 10, 15)
    private var nextId = 7000L

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /**
     * The credentials are accepted and Household (USD: Checking, Rewards Card, Reserve Savings, Travel Card, Joint
     * Checking), Summer House (EUR) and Care Fund (USD: Health Savings) are served.
     */
    private suspend fun ready() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            val more = if (budget.name == "Household") {
                listOf(BudgetAccountToStore("ba-travel", "Travel Card", amount(-90)), BudgetAccountToStore("ba-joint", "Joint Checking", amount(700)))
            } else {
                emptyList()
            }
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) } + more)
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    private suspend fun budgetAccount(name: String): Long = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun budget(name: String) = slice.connectionStore.budgets().single { it.name == name }

    /** A Bank Account the Operator added, mapped to the Budget Account [budgetAccountName]. */
    private suspend fun bankAccount(
        name: String,
        budgetAccountName: String,
        type: BankAccountType = BankAccountType.Checking,
        currency: CurrencyCode = USD,
        retired: Boolean = false,
    ): Long {
        val id = nextId++
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = slice.feedsId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = name,
            currency = currency,
            type = type,
            startDate = null,
            providerReference = "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = retired,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount(budgetAccountName), null),
        )
        return id
    }

    private suspend fun checking() = bankAccount("Kestrel Checking", "Checking")

    private suspend fun rewardsCard() = bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard)

    private suspend fun travelCard() = bankAccount("Kestrel Travel", "Travel Card", BankAccountType.CreditCard)

    private suspend fun reserve() = bankAccount("Reserve Saver", "Reserve Savings", BankAccountType.Savings)

    private suspend fun joint() = bankAccount("Kestrel Joint", "Joint Checking")

    private fun change(bankAccountId: Long, changed: (StoredBankAccount) -> StoredBankAccount) {
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == bankAccountId) changed(it) else it }
    }

    private fun identifier(reference: String) =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet (S-T-08). */
    private fun stored(
        bankAccountId: Long,
        reference: String?,
        date: LocalDate,
        amount: Amount,
        payee: String? = "PLANTED-PAYEE",
        memo: String? = null,
        firstSeenAt: Instant = now.minus(Duration.ofHours(1)),
        currency: CurrencyCode = USD,
    ): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = if (reference == null) StableReferenceKind.None else StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = reference?.let(::identifier) ?: "SLS1-${"f".repeat(22)}%04d".format(id % 10000),
            bookedDate = date,
            amount = amount,
            currency = currency,
            payee = payee,
            memo = memo,
            firstSeenAt = firstSeenAt,
            status = null,
            transferId = null,
        )
        return id
    }

    private fun transaction(id: Long): StoredBankTransaction = slice.importStatusStore.bankTransactions.single { it.id == id }

    private fun status(id: Long): TransactionStatus? = transaction(id).status

    /** The stored Transfer the Bank Transaction [id] is a Transfer Leg of (`bank_transaction.transfer_id`), or null. */
    private fun transferOf(id: Long): StoredTransfer? = transaction(id).transferId?.let { transferId -> slice.copyStore.transfers.single { it.id == transferId } }

    private fun legsOf(transfer: StoredTransfer): Set<Long> = slice.importStatusStore.bankTransactions.filter { it.transferId == transfer.id }.map { it.id }.toSet()

    private suspend fun compare(): Int = ((slice.comparison.readAgain() as ComparisonStart.Ran).report as ComparisonReport.Compared).statusesChanged

    private suspend fun counts(budgetName: String): NotYetUploaded = slice.status.workspace(budget(budgetName).id).summary.notYetUploaded

    private suspend fun eligibleOf(bankAccountId: Long): Int = slice.importStatusStore.transactionCounts().single { it.bankAccountId == bankAccountId }.eligible

    /** The Budget App answers every read of changes of Household with [transactions]. */
    private fun answerHousehold(vararg transactions: BudgetAppTransactionRead) {
        slice.budgetApp.reader.changesAnswer = { asked ->
            val changed = if (asked.budgetReference == "budget-household") transactions.toList() else emptyList()
            ReadResult.Answered(ChangeSet(changed, ChangeCursor("k-${slice.budgetApp.reader.changeReads.size}")))
        }
    }

    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct13: LocalDate = LocalDate.of(2026, 10, 13)

    // ---- Detecting a Transfer (BR-001, BR-002) ----------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002", "BR-010"])
    @DisplayName("BR-001, BR-002, BR-010: an outflow and an equal inflow in two Bank Accounts of one Budget, one of them a credit card with its Funded By, are one Card Payment with two Transfer Legs, stored as Proposed and not guessed, without any description and with no setting to switch detection on; only the outflow side is eligible")
    fun cardPaymentPairIsOneTransfer() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        // UC-005 A8 (S-T-20): without a Funded By the Card Payment would be a Guessed Transfer.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-250, 400))
        val received = stored(card, "TX-2", oct13, amount(250, 400))
        val groceries = stored(checking, "TX-3", oct13, amount(-18))

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(TransactionStatus.New, status(groceries))
        val transfer = slice.copyStore.transfers.single()
        assertEquals(transfer, transferOf(paid))
        assertEquals(transfer, transferOf(received))
        assertNull(transferOf(groceries))
        assertEquals(budgetAccount("Checking"), transfer.outflowBudgetAccountId)
        assertEquals(budgetAccount("Rewards Card"), transfer.inflowBudgetAccountId)
        assertEquals(amount(250, 400), transfer.amount)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertFalse(transfer.guessed)
        assertEquals(TransferDecision.Proposed, transfer.decision)
        assertNull(transfer.decidedAt)
        // UC-003 BR-014: the Transfer Leg that writes its Transfer (the outflow side) and the ordinary transaction.
        assertEquals(2, eligibleOf(checking))
        assertEquals(0, eligibleOf(card))
        assertEquals(NotYetUploaded(2, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: a pair of two Bank Accounts that are not credit cards is a Bank Transfer only when the payee or memo of at least one side matches a Bank Transfer Description; without one both stay ordinary Bank Transactions")
    fun bankTransferNeedsADescription() = runTest {
        ready()
        val checking = checking()
        val reserve = reserve()
        val moved = stored(checking, "TX-1", oct12, amount(-500), payee = "Online Transfer to savings 4410")
        val arrived = stored(reserve, "TX-2", oct12, amount(500), payee = "Deposit")
        val byMemo = stored(checking, "TX-3", oct13, amount(75), payee = null, memo = "xfer from reserve")
        val byMemoOther = stored(reserve, "TX-4", oct13, amount(-75), payee = "Withdrawal")
        val unmarked = stored(checking, "TX-5", oct12, amount(-320), payee = "Withdrawal")
        val unmarkedOther = stored(reserve, "TX-6", oct12, amount(320), payee = "Deposit")

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(moved))
        assertEquals(TransactionStatus.TransferLeg, status(arrived))
        val transfer = transferOf(moved)!!
        assertEquals(transfer, transferOf(arrived))
        assertEquals(TransferType.BankTransfer, transfer.type)
        assertEquals(budgetAccount("Checking") to budgetAccount("Reserve Savings"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        val back = transferOf(byMemo)!!
        assertEquals(back, transferOf(byMemoOther))
        assertEquals(budgetAccount("Reserve Savings") to budgetAccount("Checking"), back.outflowBudgetAccountId to back.inflowBudgetAccountId)
        assertEquals(TransactionStatus.New, status(unmarked))
        assertEquals(TransactionStatus.New, status(unmarkedOther))
        assertNull(transferOf(unmarked))
        assertEquals(2, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002: the two sides are dated at most 5 days apart (5 by default, a number the Operator can change), have equal and opposite amounts and lie in two different Bank Accounts; 6 days apart, another amount or the same Bank Account is no Transfer")
    fun pairingDaysAmountsAndBankAccounts() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        // UC-005 A8 (S-T-20): without a Funded By the Card Payment would be a Guessed Transfer.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val fiveOut = stored(checking, "TX-1", today.minusDays(5), amount(-100))
        val fiveIn = stored(card, "TX-2", today, amount(100))
        val sixOut = stored(checking, "TX-3", today.minusDays(6), amount(-200))
        val sixIn = stored(card, "TX-4", today, amount(200))
        val otherAmountOut = stored(checking, "TX-5", oct12, amount(-300))
        val otherAmountIn = stored(card, "TX-6", oct12, amount(300, 10))
        val sameAccountOut = stored(card, "TX-7", oct12, amount(-45))
        val sameAccountIn = stored(card, "TX-8", oct12, amount(45))

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(fiveOut))
        assertEquals(TransactionStatus.TransferLeg, status(fiveIn))
        listOf(sixOut, sixIn, otherAmountOut, otherAmountIn, sameAccountOut, sameAccountIn).forEach {
            assertEquals(TransactionStatus.New, status(it))
            assertNull(transferOf(it))
        }

        slice.settings.settings = slice.settings.settings.copy(transferPairingDays = 6)
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(sixOut))
        assertEquals(transferOf(sixOut), transferOf(sixIn))

        slice.settings.settings = slice.settings.settings.copy(transferPairingDays = 0)
        compare()
        assertEquals(TransactionStatus.New, status(fiveOut))
        assertEquals(TransactionStatus.New, status(sixIn))
        assertTrue(slice.copyStore.transfers.isEmpty())
    }

    // ---- One Transfer per Bank Transaction (BR-003) -----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-010"])
    @DisplayName("BR-003, BR-010: a Transfer Leg that could pair with more than one Bank Transaction belongs to one Transfer only, a Guessed Transfer proposed with the nearest booked date; its Transfer Legs are Guessed Transfer, wait for a decision and are not eligible")
    fun moreThanOneCandidateIsAGuessedTransferWithTheNearestDate() = runTest {
        ready()
        val checking = checking()
        val rewards = rewardsCard()
        val travel = travelCard()
        val paid = stored(checking, "TX-1", oct12, amount(-100))
        val twoDaysOff = stored(rewards, "TX-2", oct10, amount(100))
        val sameDay = stored(travel, "TX-3", oct12, amount(100))

        compare()

        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(sameDay))
        assertEquals(TransactionStatus.New, status(twoDaysOff))
        val transfer = slice.copyStore.transfers.single()
        assertTrue(transfer.guessed)
        assertEquals(setOf(paid, sameDay), legsOf(transfer))
        assertEquals(budgetAccount("Travel Card"), transfer.inflowBudgetAccountId)
        assertNull(transferOf(twoDaysOff))
        // Nothing of a Guessed Transfer is eligible; the Bank Transaction left over is an ordinary one.
        assertEquals(NotYetUploaded(1, 2), counts("Household"))
        assertEquals(0, eligibleOf(checking))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-006"])
    @DisplayName("BR-003, BR-006: among several candidates Slussen proposes first the Bank Transaction in the Funded By of the credit card for a Card Payment, and in the Transfer Partner of the Bank Account for a Bank Transfer, before the nearest booked date")
    fun fundedByAndTransferPartnerComeFirst() = runTest {
        ready()
        val checking = checking()
        val joint = joint()
        val reserve = reserve()
        val rewards = rewardsCard()
        // A Card Payment: the card is paid from Joint Checking by default, though the outflow of Checking is nearer.
        slice.connectionStore.link(rewards, fundedBy = budgetAccount("Joint Checking"))
        val cardPaid = stored(rewards, "TX-1", oct12, amount(640))
        val fromChecking = stored(checking, "TX-2", oct12, amount(-640))
        val fromJoint = stored(joint, "TX-3", oct10, amount(-640))
        // A Bank Transfer: money moves between Checking and Reserve Savings by default, though Joint Checking is nearer.
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        val sent = stored(checking, "TX-4", oct12, amount(-55), payee = "INTERNET TRANSFER")
        val toJoint = stored(joint, "TX-5", oct12, amount(55))
        val toReserve = stored(reserve, "TX-6", oct13, amount(55))

        compare()

        val cardPayment = transferOf(cardPaid)!!
        assertEquals(setOf(cardPaid, fromJoint), legsOf(cardPayment))
        assertTrue(cardPayment.guessed)
        assertEquals(TransactionStatus.New, status(fromChecking))
        val bankTransfer = transferOf(sent)!!
        assertEquals(setOf(sent, toReserve), legsOf(bankTransfer))
        assertTrue(bankTransfer.guessed)
        assertEquals(TransferType.BankTransfer, bankTransfer.type)
        assertEquals(TransactionStatus.New, status(toJoint))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: with the same booked date Slussen proposes the Bank Transaction it received first; every Bank Transaction belongs to at most one Transfer")
    fun receivedFirstBreaksTheTie() = runTest {
        ready()
        val checking = checking()
        val rewards = rewardsCard()
        val travel = travelCard()
        val paid = stored(checking, "TX-1", oct12, amount(-100))
        val later = stored(rewards, "TX-2", oct12, amount(100), firstSeenAt = now.minus(Duration.ofHours(2)))
        val first = stored(travel, "TX-3", oct12, amount(100), firstSeenAt = now.minus(Duration.ofHours(9)))
        val paidAgain = stored(checking, "TX-4", oct12, amount(-100), firstSeenAt = now.minus(Duration.ofMinutes(30)))

        compare()

        assertEquals(setOf(paid, first), legsOf(transferOf(first)!!))
        assertEquals(setOf(paidAgain, later), legsOf(transferOf(later)!!))
        assertEquals(2, slice.copyStore.transfers.size)
        assertTrue(slice.copyStore.transfers.all { it.guessed })
        assertEquals(4, slice.importStatusStore.bankTransactions.count { it.status == TransactionStatus.GuessedTransfer })
    }

    // ---- Never across Budgets or currencies (BR-004) ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004: a movement between two Budgets, or between two currencies, is never a Transfer: each side stays an ordinary Bank Transaction and no amount is converted, also with a matching description and a Transfer Partner in the other Budget")
    fun neverAcrossBudgetsOrCurrencies() = runTest {
        ready()
        val checking = checking()
        val health = bankAccount("Harbor Health Savings", "Health Savings", BankAccountType.Savings)
        val euroCard = bankAccount("Aurora Card", "Card", BankAccountType.CreditCard, currency = EUR)
        val euroInDollarBudget = bankAccount("Aurora Euro Saver", "Reserve Savings", BankAccountType.Savings, currency = EUR)
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Health Savings"))
        val toOtherBudget = stored(checking, "TX-1", oct12, amount(-300), payee = "ONLINE TRANSFER")
        val inOtherBudget = stored(health, "TX-2", oct12, amount(300), payee = "ONLINE TRANSFER")
        val toOtherCurrency = stored(checking, "TX-3", oct12, amount(-80))
        val inOtherCurrency = stored(euroCard, "TX-4", oct12, amount(80), currency = EUR)
        val toMismatched = stored(checking, "TX-5", oct12, amount(-60), payee = "XFER")
        val inMismatched = stored(euroInDollarBudget, "TX-6", oct12, amount(60), payee = "XFER", currency = EUR)

        compare()

        listOf(toOtherBudget, inOtherBudget, toOtherCurrency, inOtherCurrency, toMismatched, inMismatched).forEach {
            assertEquals(TransactionStatus.New, status(it))
            assertNull(transferOf(it))
        }
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(amount(-80) to USD, transaction(toOtherCurrency).let { it.amount to it.currency })
        assertEquals(amount(80) to EUR, transaction(inOtherCurrency).let { it.amount to it.currency })
    }

    // ---- The other side is a Budget Account Slussen fetches (BR-014) ------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-014"])
    @DisplayName("BR-014: the other side of a Transfer is never a Budget Account only a Retired Account is mapped to, nor a Manually Kept Account: a movement to or from it stays an ordinary Bank Transaction")
    fun otherSideIsNeverARetiredOrManuallyKeptAccount() = runTest {
        ready()
        val checking = checking()
        val retiredCard = bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard, retired = true)
        slice.connectionStore.setManuallyKept(budgetAccount("Reserve Savings"), true)
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        val paid = stored(checking, "TX-1", oct12, amount(-80))
        val onRetired = stored(retiredCard, "TX-2", oct12, amount(80))
        val toManuallyKept = stored(checking, "TX-3", oct12, amount(-900), payee = "ONLINE TRANSFER")

        compare()

        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(toManuallyKept))
        assertNull(status(onRetired))
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(NotYetUploaded(2, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-006", "BR-014"])
    @DisplayName("BR-014, BR-006: a Transfer Partner or Funded By whose Budget Account lost its last Account Mapping of a Bank Account that is not a Retired Account is kept but not used to detect Transfers, until such a Bank Account is mapped to it again")
    fun fundedByOrTransferPartnerThatNoLongerAppliesIsNotUsed() = runTest {
        ready()
        val checking = checking()
        val reserve = reserve()
        val card = rewardsCard()
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        slice.connectionStore.link(card, fundedBy = budgetAccount("Reserve Savings"))
        change(reserve) { it.copy(retired = true) }
        val sent = stored(checking, "TX-1", oct13, amount(-150), payee = "ONLINE TRANSFER")
        val paid = stored(card, "TX-2", oct13, amount(40), payee = "AUTOPAY")

        compare()

        assertEquals(TransactionStatus.New, status(sent))
        assertNull(transferOf(sent))
        // The Funded By is not used: the card is treated as one without a Funded By (A8), never paired with Reserve Savings.
        assertNotEquals(TransactionStatus.TransferLeg, status(paid))
        assertTrue(slice.copyStore.transfers.none { it.outflowBudgetAccountId == budgetAccount("Reserve Savings") })
        assertEquals(budgetAccount("Reserve Savings"), slice.connectionStore.bankAccounts().single { it.id == checking }.transferPartnerBudgetAccountId)

        change(reserve) { it.copy(retired = false) }
        compare()

        assertEquals(TransactionStatus.TransferLeg, status(sent))
        assertEquals(budgetAccount("Reserve Savings"), transferOf(sent)!!.inflowBudgetAccountId)
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(budgetAccount("Reserve Savings"), transferOf(paid)!!.outflowBudgetAccountId)
    }

    // ---- A4: Lonely Transfer (BR-002) -------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-002", "BR-006", "BR-010"])
    @DisplayName("A4, BR-002: an inflow to a credit card that matches a Card Payment Description and whose other side has not arrived is a Lonely Transfer from the card's Funded By; its one Transfer Leg writes the Transfer and is eligible; without a matching description it is an ordinary Bank Transaction")
    fun lonelyCardPaymentOnTheCardSide() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(card, "TX-1", oct13, amount(120), payee = "Automatic Payment - Thank You")
        val refund = stored(card, "TX-2", oct13, amount(31), payee = "Harbor Outfitters refund")

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(paid))
        val transfer = slice.copyStore.transfers.single()
        assertEquals(setOf(paid), legsOf(transfer))
        assertEquals(budgetAccount("Checking") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(amount(120), transfer.amount)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertFalse(transfer.guessed)
        assertEquals(TransactionStatus.New, status(refund))
        assertEquals(2, eligibleOf(card))
        assertEquals(NotYetUploaded(2, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-002", "BR-003"])
    @DisplayName("A4, BR-002: an outflow that matches a Card Payment Description is a Lonely Transfer to the credit card whose Funded By is its Budget Account; with more than one such credit card it is a Guessed Transfer; Slussen never infers a credit card from the paying Bank Account")
    fun lonelyCardPaymentOnTheFundingSide() = runTest {
        ready()
        val checking = checking()
        val joint = joint()
        val rewards = rewardsCard()
        val travel = travelCard()
        slice.connectionStore.link(rewards, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct13, amount(-410), payee = "CITI AUTOPAY 20261013")
        val notInferred = stored(joint, "TX-2", oct13, amount(-77), payee = "CITI AUTOPAY 20261013")

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(paid))
        val transfer = transferOf(paid)!!
        assertEquals(budgetAccount("Checking") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertFalse(transfer.guessed)
        // Joint Checking is the Funded By of no credit card: the outflow stays an ordinary Bank Transaction.
        assertEquals(TransactionStatus.New, status(notInferred))
        assertNull(transferOf(notInferred))

        slice.connectionStore.link(travel, fundedBy = budgetAccount("Checking"))
        compare()

        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertTrue(transferOf(paid)!!.guessed)
        assertEquals(budgetAccount("Checking"), transferOf(paid)!!.outflowBudgetAccountId)
        assertEquals(1, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-002", "BR-006"])
    @DisplayName("A4, BR-002, BR-006: a Bank Transaction of a Bank Account that is not a credit card that matches a Bank Transfer Description is a Lonely Transfer towards its Transfer Partner; without a Transfer Partner, or on a credit card, it is an ordinary Bank Transaction")
    fun lonelyBankTransferTowardsTheTransferPartner() = runTest {
        ready()
        val checking = checking()
        val joint = joint()
        val card = rewardsCard()
        reserve()
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        val sent = stored(checking, "TX-1", oct13, amount(-500), payee = "Express Internet Transfer")
        val received = stored(checking, "TX-2", oct12, amount(70), payee = null, memo = "XFER 0091")
        val noPartner = stored(joint, "TX-3", oct13, amount(-65), payee = "Express Internet Transfer")
        val onCard = stored(card, "TX-4", oct13, amount(-12), payee = "Balance transfer fee")

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(sent))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        val out = transferOf(sent)!!
        assertEquals(budgetAccount("Checking") to budgetAccount("Reserve Savings"), out.outflowBudgetAccountId to out.inflowBudgetAccountId)
        assertEquals(TransferType.BankTransfer, out.type)
        val back = transferOf(received)!!
        assertEquals(budgetAccount("Reserve Savings") to budgetAccount("Checking"), back.outflowBudgetAccountId to back.inflowBudgetAccountId)
        assertEquals(amount(70), back.amount)
        assertEquals(TransactionStatus.New, status(noPartner))
        assertEquals(TransactionStatus.New, status(onCard))
        assertEquals(2, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-002", "BR-010"])
    @DisplayName("A4, BR-010: when the other side of a Lonely Transfer arrives, the same Transfer has two Transfer Legs and the outflow side writes it")
    fun otherSideOfALonelyTransferArrives() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        compare()
        val lonely = transferOf(paid)!!
        assertEquals(1, eligibleOf(card))

        slice.clock.advance(Duration.ofHours(6))
        val arrived = stored(checking, "TX-2", oct12, amount(-120), firstSeenAt = slice.clock.instant().minus(Duration.ofMinutes(5)))
        compare()

        val pair = slice.copyStore.transfers.single()
        assertEquals(lonely.id, pair.id)
        assertEquals(setOf(paid, arrived), legsOf(pair))
        assertEquals(TransactionStatus.TransferLeg, status(arrived))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(0, eligibleOf(card))
        assertEquals(1, eligibleOf(checking))
    }

    // ---- A5: the grace period (BR-005) ------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period", businessRules = ["BR-005"], requirements = ["NFR-002"])
    @DisplayName("A5, BR-005, NFR-002: a Lonely Transfer whose other Transfer Leg has not arrived within 6 days (6 by default, a number the Operator can change) becomes an ordinary Bank Transaction: its Transfer no longer holds, it is released and stays shown with a status")
    fun lonelyTransferBecomesOrdinaryAfterTheGracePeriod() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(card, "TX-1", today.minusDays(6), amount(120), payee = "AUTOPAY")

        compare()
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(1, slice.copyStore.transfers.size)

        slice.clock.advance(Duration.ofDays(1))
        val before = slice.journalRecorder.entries.size
        compare()

        assertEquals(TransactionStatus.New, status(paid))
        assertNull(transaction(paid).transferId)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals("Transaction Status changed from Transfer Leg to New", slice.journalRecorder.entries.drop(before).single().details)
        assertEquals(1, eligibleOf(card))

        slice.settings.settings = slice.settings.settings.copy(lonelyTransferGraceDays = 7)
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(paid))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period", businessRules = ["BR-005", "BR-006"])
    @DisplayName("A5 step 1 (O-T-11): on its Bank Account, a Lonely Transfer shows the other side it expects and the last day of the grace period; the day after, the same Bank Transaction is shown as an ordinary one with the reason that its other side did not arrive in time, derived and stored nowhere; an ordinary payment that matches no description has no such reason")
    fun lateOtherSideIsShownWithItsReason() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(card, "TX-1", today.minusDays(6), amount(120), payee = "AUTOPAY")
        val refund = stored(card, "TX-2", today.minusDays(6), amount(15), payee = "Harbor Outfitters refund")
        compare()

        fun line(id: Long) = runBlocking { slice.bankAccountTransactions.of(card) }.weeks.flatMap { it.lines }.single { it.key.id == id }
        val lonely = (line(paid).reason as StatusReason.TransferLeg).transfer
        assertEquals(TransferShown(TransferType.CardPayment, false, "Checking", otherLeg = null, expectedBy = today, writesIt = true, keptOutBy = null), lonely)

        slice.clock.advance(Duration.ofDays(1))
        compare()

        assertEquals(TransactionStatus.New, line(paid).status)
        assertEquals(StatusReason.OtherSideDidNotArriveInTime("Checking", graceDays = 6, lastDay = today, proposed = false), line(paid).reason)
        assertNull(line(paid).transfer)
        assertEquals(StatusReason.NoMatch, line(refund).reason)
        // BR-006, A8: without a Funded By, and with two Bank Accounts that could pay, the card's inflow has no known other side, so no such reason.
        joint()
        slice.connectionStore.link(card, fundedBy = null)
        assertEquals(StatusReason.NoMatch, line(paid).reason)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period", businessRules = ["BR-002", "BR-005"])
    @DisplayName("A5 step 1 (O-T-11, review 2c): when the other side was only proposed (A8: a card without a Funded By and one Bank Account that could pay), the reason says that it was proposed, not that it is the other side")
    fun proposedOtherSideIsSaidToBeProposed() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        val paid = stored(card, "TX-1", today.minusDays(7), amount(120), payee = "AUTOPAY")
        compare()

        val line = slice.bankAccountTransactions.of(card).weeks.flatMap { it.lines }.single { it.key.id == paid }

        assertEquals(StatusReason.OtherSideDidNotArriveInTime("Checking", graceDays = 6, lastDay = today.minusDays(1), proposed = true), line.reason)
    }

    // ---- A8: the first Card Payment without a Funded By -------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-002", "BR-010"])
    @DisplayName("A8: an inflow to a credit card without a Funded By that matches a Card Payment Description is a Guessed Transfer that proposes the Budget Account of the only Bank Account in the Budget that is not a credit card; it waits for a decision and is not eligible")
    fun firstCardPaymentProposesTheOnlyOtherBankAccount() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        travelCard()
        val paid = stored(card, "TX-1", oct13, amount(200), payee = "PAYMENT - THANK YOU")
        val refund = stored(card, "TX-2", oct13, amount(15), payee = "Harbor Outfitters refund")
        // A8 starts only from the card's own Bank Transaction, never from an outflow of the paying side.
        val payingSide = stored(checking, "TX-3", oct13, amount(-333), payee = "PAYMENT - THANK YOU")

        compare()

        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        val transfer = slice.copyStore.transfers.single()
        assertEquals(setOf(paid), legsOf(transfer))
        assertTrue(transfer.guessed)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertEquals(budgetAccount("Checking") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(TransactionStatus.New, status(refund))
        assertEquals(TransactionStatus.New, status(payingSide))
        assertEquals(NotYetUploaded(2, 1), counts("Household"))
        assertEquals(1, eligibleOf(card))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-002"])
    @DisplayName("A8: with more than one Bank Account in the Budget that is not a credit card, or none, the Guessed Transfer proposes no Budget Account: the Bank Transaction is Guessed Transfer and no other side is stored")
    fun firstCardPaymentProposesNoBudgetAccountWhenThereIsNotExactlyOne() = runTest {
        ready()
        checking()
        joint()
        val card = rewardsCard()
        val paid = stored(card, "TX-1", oct13, amount(200), payee = "ACH PMT 77120")

        compare()

        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertNull(transaction(paid).transferId)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(NotYetUploaded(0, 1), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-002", "BR-010"])
    @DisplayName("A8 (S-T-20): a Card Payment to a credit card without a Funded By is a Guessed Transfer also when both sides have arrived: it has both Transfer Legs, proposes the Budget Account of the outflow, waits for a decision and is not eligible; once the card has that Funded By it is not guessed")
    fun pairedCardPaymentToACardWithoutFundedByIsGuessed() = runTest {
        ready()
        val checking = checking()
        joint()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250, 400))
        val received = stored(card, "TX-2", oct13, amount(250, 400))

        compare()

        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        val transfer = slice.copyStore.transfers.single()
        assertEquals(setOf(paid, received), legsOf(transfer))
        assertTrue(transfer.guessed)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertEquals(budgetAccount("Checking") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(NotYetUploaded(0, 2), counts("Household"))

        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        compare()

        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertFalse(slice.copyStore.transfers.single().guessed)
        assertEquals(NotYetUploaded(1, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-010"])
    @DisplayName("BR-002, BR-010 (S-T-21): a Transfer stays one Transfer when a Transfer Leg leaves the Upload Window: that Transfer Leg is Too Old, the other keeps the status Transfer Leg, the stored Transfer and its Transfer Legs are the same, and nothing of it is eligible; when the Operator widens the Upload Window it is eligible again")
    fun transferStaysTogetherWhenATransferLegLeavesTheUploadWindow() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", today.minusDays(7), amount(-120))
        val received = stored(card, "TX-2", today.minusDays(5), amount(120))
        compare()
        val detected = slice.copyStore.transfers.single()
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(1, eligibleOf(checking))

        // The Operator did not upload: a day later the outflow side is outside the Upload Window of 7 days.
        slice.clock.advance(Duration.ofDays(1))
        compare()

        assertEquals(TransactionStatus.TooOld, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(listOf(detected), slice.copyStore.transfers)
        assertEquals(setOf(paid, received), legsOf(detected))
        assertEquals(0, eligibleOf(checking))
        assertEquals(0, eligibleOf(card))
        assertEquals(NotYetUploaded(0, 0), counts("Household"))

        // Two days later both sides are outside it: still the same Transfer.
        slice.clock.advance(Duration.ofDays(2))
        compare()
        assertEquals(TransactionStatus.TooOld, status(received))
        assertEquals(setOf(paid, received), legsOf(slice.copyStore.transfers.single()))

        slice.settings.settings = slice.settings.settings.copy(uploadWindowDays = 14)
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(listOf(detected), slice.copyStore.transfers)
        assertEquals(1, eligibleOf(checking))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-010"])
    @DisplayName("BR-002, BR-010 (S-T-21): two Bank Transactions that are already Too Old when first compared are still one Transfer, with both Transfer Legs Too Old and nothing eligible")
    fun oldBankTransactionsAreStillOneTransfer() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", today.minusDays(20), amount(-300))
        val received = stored(card, "TX-2", today.minusDays(19), amount(300))

        compare()

        assertEquals(TransactionStatus.TooOld, status(paid))
        assertEquals(TransactionStatus.TooOld, status(received))
        assertEquals(setOf(paid, received), legsOf(slice.copyStore.transfers.single()))
        assertEquals(NotYetUploaded(0, 0), counts("Household"))
    }

    // ---- Matching a Transfer Description (BR-012) -------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", businessRules = ["BR-012"])
    @DisplayName("BR-012: an entry matches when its text occurs anywhere in the payee or memo, without regard to case and accents, in either spelling")
    fun descriptionMatchesAnywhereWithoutRegardToCaseAndAccents() {
        assertTrue(TransferDescriptions.matches("ÖVERFÖRING", "Överföring till sparkonto"))
        assertTrue(TransferDescriptions.matches("ÖVERFÖRING", "REF 88 OVERFORING 4410"))
        assertTrue(TransferDescriptions.matches("OVERFORING", "överföring"))
        assertTrue(TransferDescriptions.matches("Online Transfer", "WEB ONLINE TRANSFER TO 4410"))
        assertTrue(TransferDescriptions.matches("XFER", "branch xfer"))
        assertFalse(TransferDescriptions.matches("ÖVERFÖRING", "Overdraft fee"))
        assertFalse(TransferDescriptions.matches("XFER", "X-FER"))
        assertFalse(TransferDescriptions.matches("XFER", null))
        assertFalse(TransferDescriptions.matches("XFER", ""))
    }

    @Test
    @UseCase(id = "UC-005", businessRules = ["BR-012"])
    @DisplayName("BR-012: a * in an entry stands for any characters, as in AMERICAN EXPRESS * PMT; no other character has a special meaning")
    fun starStandsForAnyCharactersAndNothingElseIsSpecial() {
        assertTrue(TransferDescriptions.matches("AMERICAN EXPRESS * PMT", "American Express ACH Pmt 20261013"))
        assertTrue(TransferDescriptions.matches("AMERICAN EXPRESS * PMT", "AMERICAN EXPRESS  PMT"))
        assertTrue(TransferDescriptions.matches("AUTO*PAY", "CRD AUTOMATIC PAYMENT"))
        assertFalse(TransferDescriptions.matches("AMERICAN EXPRESS * PMT", "PMT TO AMERICAN EXPRESS"))
        assertFalse(TransferDescriptions.matches("AMERICAN EXPRESS * PMT", "AMERICAN EXPRESS TRAVEL"))
        // A dot, a question mark, a bracket and a hyphen are ordinary characters.
        assertFalse(TransferDescriptions.matches("A.TOPAY", "AUTOPAY"))
        assertTrue(TransferDescriptions.matches("A.TOPAY", "bank a.topay"))
        assertFalse(TransferDescriptions.matches("AUTOPA?", "AUTOPAY"))
        assertFalse(TransferDescriptions.matches("[A-Z]+", "AUTOPAY"))
        assertTrue(TransferDescriptions.matches("PAYMENT - THANK", "Payment - Thank You"))
        assertFalse(TransferDescriptions.matches("PAYMENT - THANK", "Payment Thank You"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-012"])
    @DisplayName("BR-012: the Card Payment Descriptions and Bank Transfer Descriptions are the stored lists: an entry the Operator's list holds is used with its *, in payee or memo, and one it no longer holds is not")
    fun theStoredListsAreUsed() = runTest {
        ready()
        val checking = checking()
        val reserve = reserve()
        slice.copyStore.transferDescriptions.removeAll { it.kind == TransferDescriptionKind.BankTransfer }
        slice.copyStore.transferDescriptions += StoredTransferDescription(TransferDescriptionKind.BankTransfer, "SWEEP * RÉSERVE")
        val swept = stored(checking, "TX-1", oct12, amount(-45), payee = null, memo = "Sweep 0091 to reserve")
        val sweptIn = stored(reserve, "TX-2", oct12, amount(45), payee = "Deposit")
        val oldEntry = stored(checking, "TX-3", oct13, amount(-46), payee = "ONLINE TRANSFER")
        val oldEntryIn = stored(reserve, "TX-4", oct13, amount(46), payee = "ONLINE TRANSFER")
        // A Card Payment Description does not make a pair of two Bank Accounts that are not credit cards a Bank Transfer.
        val wrongList = stored(checking, "TX-5", oct13, amount(-47), payee = "AUTOPAY")
        val wrongListIn = stored(reserve, "TX-6", oct13, amount(47), payee = "AUTOPAY")

        compare()

        assertEquals(TransactionStatus.TransferLeg, status(swept))
        assertEquals(transferOf(swept), transferOf(sweptIn))
        listOf(oldEntry, oldEntryIn, wrongList, wrongListIn).forEach { assertEquals(TransactionStatus.New, status(it)) }
        assertEquals(1, slice.copyStore.transfers.size)
    }

    // ---- Statuses and eligibility of Transfer Legs (BR-010, BR-013; UC-003 BR-001, BR-014) ---------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-010", "BR-013"])
    @DisplayName("BR-013, UC-003 BR-001: a Transfer Leg that is Needs Review or Possible Import By Other Path keeps that status and its Transfer waits: the other Transfer Leg is Transfer Leg and nothing of the Transfer is eligible, from either side")
    fun doubtfulTransferLegKeepsItsStatusAndItsTransferWaits() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        // UC-005 A8 (S-T-20): without a Funded By the Card Payment would be a Guessed Transfer.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val noReference = stored(checking, null, oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        val paid = stored(checking, "TX-3", oct12, amount(-99))
        val resembling = stored(card, "TX-4", oct12, amount(99))
        answerHousehold(
            BudgetAppTransactionRead(
                "ynab-1", "ba-rewards", oct12, amount(99), "Card payment", null, "YNAB:99000:2026-10-12:1", EntryKind.Imported, null,
                ReadState.Present, cleared = true, approved = true,
            ),
        )

        compare()

        assertEquals(TransactionStatus.NeedsReview, status(noReference))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(setOf(noReference, received), legsOf(transferOf(received)!!))
        assertEquals(TransactionStatus.PossibleImportByOtherPath, status(resembling))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(setOf(paid, resembling), legsOf(transferOf(paid)!!))
        assertFalse(slice.copyStore.transfers.any { it.guessed })
        // UC-003 BR-014: no Transfer Leg of a Transfer that waits is eligible; the two doubtful ones wait for a decision.
        assertEquals(NotYetUploaded(0, 2), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010, UC-003 BR-001: a Transfer Leg that an earlier rule of the Comparison gave its status (Too Old, Already In Budget App) keeps that status and stays a Transfer Leg of its Transfer, of which nothing is eligible; a Transfer Leg follows its Transfer before a match for a Typed Entry")
    fun transferLegStandsWhereTheOrderOfTheComparisonPutsIt() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        // UC-005 A8 (S-T-20): without a Funded By the Card Payment would be a Guessed Transfer.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val tooOld = stored(checking, "TX-1", today.minusDays(8), amount(-70))
        val inWindow = stored(card, "TX-2", today.minusDays(4), amount(70))
        val alreadyThere = stored(checking, "TX-3", oct12, amount(-71))
        val itsOtherSide = stored(card, "TX-4", oct12, amount(71))
        val typedToo = stored(checking, "TX-5", oct12, amount(-72))
        val typedOtherSide = stored(card, "TX-6", oct12, amount(72))
        answerHousehold(
            BudgetAppTransactionRead(
                "ynab-1", "ba-checking", oct12, amount(-71), "Card payment", null, identifier("TX-3"), EntryKind.Imported, null,
                ReadState.Present, cleared = true, approved = true,
            ),
            BudgetAppTransactionRead(
                "ynab-2", "ba-checking", oct12, amount(-72), "Card payment", null, null, EntryKind.Typed, null, ReadState.Present,
                cleared = false, approved = true,
            ),
        )

        compare()

        assertEquals(TransactionStatus.TooOld, status(tooOld))
        assertEquals(TransactionStatus.TransferLeg, status(inWindow))
        assertEquals(setOf(tooOld, inWindow), legsOf(transferOf(tooOld)!!))
        assertEquals(TransactionStatus.AlreadyInBudgetApp, status(alreadyThere))
        assertEquals(TransactionStatus.TransferLeg, status(itsOtherSide))
        assertEquals(setOf(alreadyThere, itsOtherSide), legsOf(transferOf(alreadyThere)!!))
        assertEquals(TransactionStatus.TransferLeg, status(typedToo))
        assertEquals(TransactionStatus.TransferLeg, status(typedOtherSide))
        assertEquals(3, slice.copyStore.transfers.size)
        // UC-003 BR-014: only the Transfer none of whose Transfer Legs has a status of an earlier rule is eligible, through its outflow side.
        assertEquals(1, eligibleOf(checking))
        assertEquals(0, eligibleOf(card))
    }

    // ---- Stored with the statuses (O-5), released, journaled --------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-004"], requirements = ["NFR-002"])
    @DisplayName("BR-002, BR-004, NFR-002: a Transfer that no longer holds releases its Transfer Legs, which stay shown with a status: when a Transfer Leg changed, when the Bank Account type no longer makes it a Card Payment, and when an Account Mapping moved to another Budget")
    fun transferThatNoLongerHoldsReleasesItsTransferLegs() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val travel = travelCard()
        // UC-005 A8 (S-T-20): without a Funded By the Card Payment would be a Guessed Transfer.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        slice.connectionStore.link(travel, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        val paidTravel = stored(checking, "TX-3", oct12, amount(-60))
        val receivedTravel = stored(travel, "TX-4", oct12, amount(60))
        compare()
        assertEquals(2, slice.copyStore.transfers.size)
        val kept = transferOf(paidTravel)!!

        // A Transfer Leg changed: a later delivery corrected its amount (UC-001 step 8).
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == received) it.copy(amount = amount(251)) else it }
        compare()

        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertNull(transaction(paid).transferId)
        assertNull(transaction(received).transferId)
        assertEquals(listOf(kept), slice.copyStore.transfers)

        // The Bank Account is no credit card any more (UC-009 A14), and no Bank Transfer Description matches.
        change(travel) { it.copy(type = BankAccountType.Savings) }
        compare()
        assertEquals(TransactionStatus.New, status(paidTravel))
        assertEquals(TransactionStatus.New, status(receivedTravel))
        assertTrue(slice.copyStore.transfers.isEmpty())

        change(travel) { it.copy(type = BankAccountType.CreditCard) }
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(paidTravel))

        // The Account Mapping of the card moved into another Budget (UC-009 A8): never a Transfer across Budgets.
        val healthSavings = budgetAccount("Health Savings")
        change(travel) { it.copy(mapping = it.mapping!!.copy(budgetAccountId = healthSavings)) }
        compare()
        assertEquals(TransactionStatus.New, status(paidTravel))
        assertEquals(TransactionStatus.New, status(receivedTravel))
        assertNull(transaction(receivedTravel).transferId)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(4, slice.importStatusStore.bankTransactions.count { it.status != null })
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("Step 4 of UC-003, UC-011 BR-001: a later Comparison keeps the same stored Transfer and records nothing again; every changed Transaction Status of a Transfer Leg is recorded with its Bank Account and Import Identifier and no payee")
    fun transferIsKeptAcrossComparisonsAndItsStatusesAreJournaled() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        // UC-005 A8 (S-T-20): without a Funded By the Card Payment would be a Guessed Transfer.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        stored(checking, "TX-1", oct12, amount(-250), payee = "PLANTED-PAYEE AUTOPAY")
        stored(card, "TX-2", oct13, amount(250), payee = "PLANTED-PAYEE PAYMENT - THANK YOU")
        val before = slice.journalRecorder.entries.size

        val changed = compare()

        assertEquals(2, changed)
        val entries = slice.journalRecorder.entries.drop(before)
        assertEquals(listOf(JournalEventKind.StatusChanged, JournalEventKind.StatusChanged), entries.map { it.eventKind })
        assertEquals(setOf("Kestrel Checking" to identifier("TX-1"), "Kestrel Rewards" to identifier("TX-2")), entries.map { it.bankAccountName to it.importIdentifier }.toSet())
        assertTrue(entries.all { it.details == "Transaction Status changed from not compared yet to Transfer Leg" })
        assertFalse(entries.any { "PLANTED" in it.toString() })
        val transfer = slice.copyStore.transfers.single()

        val again = slice.journalRecorder.entries.size
        assertEquals(0, compare())

        assertEquals(listOf(transfer), slice.copyStore.transfers)
        assertEquals(again, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Precondition: only a Bank Transaction that a Comparison has given a Transaction Status can be a Transfer Leg; while the Budget App cannot be read, a newly stored pair is not compared yet and no Transfer is stored")
    fun onlyComparedBankTransactionsAreTransferLegs() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(FailureKind.Timeout) }

        slice.comparison.readAgain()

        assertNull(status(paid))
        assertNull(status(received))
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(NotYetUploaded(0, 0), counts("Household"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Postcondition: a Transfer the Operator decided on is kept across a later Comparison: detection changes neither the Transfer nor its Transfer Legs")
    fun decidedTransferIsNotChangedByAComparison() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        val held = slice.copyStore.transfers.single().copy(decision = TransferDecision.Held, decidedAt = now)
        slice.copyStore.transfers[0] = held
        slice.importStatusStore.bankTransactions.replaceAll { if (it.transferId == held.id) it.copy(status = TransactionStatus.HeldTransfer) else it }
        // Even when the pair no longer holds as Slussen would detect it.
        change(card) { it.copy(type = BankAccountType.Savings) }

        assertEquals(0, compare())

        assertEquals(listOf(held), slice.copyStore.transfers)
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(held.id, transaction(paid).transferId)
        assertEquals(NotYetUploaded(0, 0), counts("Household"))
    }
}
