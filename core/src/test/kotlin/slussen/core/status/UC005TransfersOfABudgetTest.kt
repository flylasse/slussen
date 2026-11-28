package slussen.core.status

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferType
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The Transfers of one Budget, read-only (UC-005 step 1, A4, A5, A8, BR-010, BR-013 as far as they give what is shown):
 * one Transfer of each kind the detection gives, with its two Budget Accounts, amount, Transfer Legs, why it is guessed
 * and whether it is eligible, derived from stored data after a real Comparison of the slice. The Transfer Decisions
 * (steps 2-6) come with a later phase. Every name, amount, payee and identifier is invented (C-005).
 */
@DisplayName("UC-005: Review Transfers, the Transfers of a Budget")
class UC005TransfersOfABudgetTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private var nextId = 8000L

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** Household (USD: Checking, Rewards Card, Reserve Savings, Travel Card, Joint Checking) and the other Budgets are served. */
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

    private suspend fun household(): Long = slice.connectionStore.budgets().single { it.name == "Household" }.id

    private suspend fun bankAccount(name: String, budgetAccountName: String, type: BankAccountType = BankAccountType.Checking): Long {
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
            currency = USD,
            type = type,
            startDate = null,
            providerReference = "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = false,
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

    private fun stored(
        bankAccountId: Long,
        reference: String?,
        date: LocalDate,
        amount: Amount,
        payee: String? = "PLANTED-PAYEE",
    ): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = if (reference == null) StableReferenceKind.None else StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = reference?.let { ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", it)) }
                ?: "SLS1-${"g".repeat(22)}%04d".format(id % 10000),
            bookedDate = date,
            amount = amount,
            currency = USD,
            payee = payee,
            memo = null,
            firstSeenAt = now.minus(Duration.ofHours(1)),
            status = null,
            transferId = null,
        )
        return id
    }

    private suspend fun compare() {
        val start = slice.comparison.readAgain()
        check(start is ComparisonStart.Ran && start.report is ComparisonReport.Compared) { "The invented Comparison did not run: $start" }
    }

    private suspend fun transfers(): BudgetTransfersView = slice.budgetTransfers.of(household())

    private fun BudgetTransfersView.withLeg(id: Long): TransferItem = transfers.single { item -> item.legs.any { it.storedBankTransaction == id } }

    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct13: LocalDate = LocalDate.of(2026, 10, 13)

    // ---- Step 1: each Transfer with its two Budget Accounts, amount, dates and whether both arrived --------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-010"])
    @DisplayName("Step 1, BR-002, BR-010: a Card Payment and a Bank Transfer that are not guessed are each listed once with their two Budget Accounts, amount and both Transfer Legs with their dates, both arrived, eligible and written through the outflow side; no Guessed Transfer is counted")
    fun pairsAreListedWithBothSides() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val reserve = reserve()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-250, 400))
        val received = stored(card, "TX-2", oct13, amount(250, 400))
        val moved = stored(checking, "TX-3", oct10, amount(-500), payee = "ONLINE TRANSFER TO RESERVE")
        val arrived = stored(reserve, "TX-4", oct10, amount(500), payee = "Deposit")
        stored(checking, "TX-5", oct13, amount(-18), payee = "Corner Grocer")

        compare()
        val view = transfers()

        assertEquals(2, view.transfers.size)
        val cardPayment = view.withLeg(paid)
        assertEquals(TransferType.CardPayment, cardPayment.type)
        assertEquals("Checking" to "Rewards Card", cardPayment.outflowBudgetAccountName to cardPayment.inflowBudgetAccountName)
        assertEquals(amount(250, 400), cardPayment.amount)
        assertEquals(paid to oct12, cardPayment.outflowLeg!!.storedBankTransaction to cardPayment.outflowLeg!!.bookedDate)
        assertEquals(received to oct13, cardPayment.inflowLeg!!.storedBankTransaction to cardPayment.inflowLeg!!.bookedDate)
        assertEquals("Kestrel Rewards", cardPayment.inflowLeg!!.bankAccountName)
        assertTrue(cardPayment.bothArrived)
        assertTrue(cardPayment.stored)
        assertFalse(cardPayment.guessed)
        assertTrue(cardPayment.whyGuessed.isEmpty())
        assertNull(cardPayment.expected)
        // UC-006 BR-009: the Transfer is written through its outflow side.
        assertEquals(paid, (cardPayment.eligibility as TransferEligibility.Eligible).writtenThrough.storedBankTransaction)
        val bankTransfer = view.withLeg(arrived)
        assertEquals(TransferType.BankTransfer, bankTransfer.type)
        assertEquals("Checking" to "Reserve Savings", bankTransfer.outflowBudgetAccountName to bankTransfer.inflowBudgetAccountName)
        assertEquals(moved, (bankTransfer.eligibility as TransferEligibility.Eligible).writtenThrough.storedBankTransaction)
        assertEquals(0, view.guessedTransfers)
        assertEquals(oct10..oct13, view.covers)
        // Guessed first, then newest first: the Card Payment of 10-13 before the Bank Transfer of 10-10.
        assertEquals(listOf(cardPayment, bankTransfer), view.transfers)
    }

    // ---- Guessed Transfers and why (BR-003, A8) -----------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-010"])
    @DisplayName("Step 1, BR-003, BR-010: a pair proposed among more than one candidate is a Guessed Transfer listed with the candidate it was not proposed with; it is not eligible, counted as one Guessed Transfer, and its two Transfer Legs are the Bank Transactions the Budget counts as waiting for a decision (UX-27)")
    fun guessedPairNamesTheOtherCandidate() = runTest {
        ready()
        val checking = checking()
        val rewards = rewardsCard()
        val travel = travelCard()
        slice.connectionStore.link(rewards, fundedBy = budgetAccount("Checking"))
        slice.connectionStore.link(travel, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-100))
        val twoDaysOff = stored(rewards, "TX-2", oct10, amount(100))
        val sameDay = stored(travel, "TX-3", oct12, amount(100))

        compare()
        val view = transfers()

        val guessed = view.withLeg(paid)
        assertEquals(sameDay, guessed.inflowLeg!!.storedBankTransaction)
        assertTrue(guessed.guessed)
        val why = guessed.whyGuessed.single() as WhyGuessed.OtherCandidates
        assertEquals(listOf(twoDaysOff), why.others.map { it.storedBankTransaction })
        assertEquals("Rewards Card" to oct10, why.others.single().budgetAccountName to why.others.single().bookedDate)
        assertEquals(TransferEligibility.Guessed, guessed.eligibility)
        assertEquals(1, view.guessedTransfers)
        assertEquals(2, view.guessedTransferLegs)
        assertEquals(view.guessedTransferLegs, slice.status.workspace(household()).summary.notYetUploaded.waitingForDecision)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"])
    @DisplayName("Step 1, BR-002, BR-003: an outflow matching a Card Payment Description from the Funded By of two credit cards is a Guessed Lonely Transfer towards the card added first, listed with the other card")
    fun guessedLonelyOutflowNamesTheOtherCard() = runTest {
        ready()
        val checking = checking()
        val rewards = rewardsCard()
        val travel = travelCard()
        slice.connectionStore.link(rewards, fundedBy = budgetAccount("Checking"))
        slice.connectionStore.link(travel, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct13, amount(-75), payee = "KESTREL CARD AUTOPAY")

        compare()
        val item = transfers().withLeg(paid)

        assertEquals("Checking" to "Rewards Card", item.outflowBudgetAccountName to item.inflowBudgetAccountName)
        assertNull(item.inflowLeg)
        assertEquals(listOf(WhyGuessed.OtherCards(listOf("Travel Card"))), item.whyGuessed)
        assertEquals(ExpectedOtherSide("Rewards Card", amount(75), oct13.plusDays(6), graceEnded = false), item.expected)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-002"])
    @DisplayName("A8, BR-002: a Card Payment pair into a credit card without a Funded By is a Guessed Transfer that proposes the outflow's Budget Account and says the card has no Funded By")
    fun firstCardPaymentPairSaysNoFundedBy() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-410))
        val received = stored(card, "TX-2", oct13, amount(410), payee = "AUTOPAY RECEIVED")

        compare()
        val item = transfers().withLeg(received)

        assertEquals(paid, item.outflowLeg!!.storedBankTransaction)
        assertEquals("Checking", item.outflowBudgetAccountName)
        assertEquals(listOf(WhyGuessed.NoFundedBy("Kestrel Rewards", payingBankAccounts = null)), item.whyGuessed)
        assertEquals(TransferEligibility.Guessed, item.eligibility)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-002"])
    @DisplayName("A8, O-T-10: a card's inflow matching a Card Payment Description with no Funded By proposes the only Bank Account that is not a credit card; with two such Bank Accounts it proposes none, has no stored Transfer and is listed from its Bank Transaction with the number of possible paying Bank Accounts")
    fun firstCardPaymentAloneProposesTheOnlyPayingSideOrNone() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        val alone = stored(card, "TX-1", oct13, amount(95), payee = "AUTOPAY RECEIVED")

        compare()
        val proposed = transfers().withLeg(alone)
        assertTrue(proposed.stored)
        assertEquals("Checking", proposed.outflowBudgetAccountName)
        assertEquals(listOf(WhyGuessed.NoFundedBy("Kestrel Rewards", payingBankAccounts = null)), proposed.whyGuessed)

        reserve()
        compare()
        val view = transfers()
        val unproposed = view.withLeg(alone)
        assertFalse(unproposed.stored)
        assertNull(unproposed.outflowBudgetAccountName)
        assertNull(unproposed.outflowLeg)
        assertNull(unproposed.expected)
        assertEquals("Rewards Card", unproposed.inflowBudgetAccountName)
        assertEquals(listOf(WhyGuessed.NoFundedBy("Kestrel Rewards", payingBankAccounts = 2)), unproposed.whyGuessed)
        assertEquals(TransferEligibility.Guessed, unproposed.eligibility)
        assertEquals(1, view.guessedTransfers)
        assertEquals(1, view.guessedTransferLegs)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1: a Transfer the latest Comparison stored as guessed, whose ground has changed since (here the card got a Funded By), is still listed as guessed, as the latest Comparison left it, without a reason the current rules no longer give")
    fun guessedAsTheLatestComparisonLeftIt() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        stored(checking, "TX-1", oct12, amount(-410))
        val received = stored(card, "TX-2", oct13, amount(410))
        compare()

        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val item = transfers().withLeg(received)

        assertTrue(item.guessed)
        assertEquals(listOf(WhyGuessed.AsStored), item.whyGuessed)
    }

    // ---- A4 and A5: Lonely Transfers and the grace period (BR-005) --------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-010"])
    @DisplayName("A4, BR-005, BR-010: a Lonely Transfer is listed with its arrived Transfer Leg, the Budget Account of the expected other side and the last day of the grace period by which it must arrive, eligible through its one Transfer Leg; once that day has passed since the Comparison it says so")
    fun lonelyTransferNamesItsExpectedSide() = runTest {
        ready()
        checking()
        val reserve = reserve()
        slice.connectionStore.link(reserve, transferPartner = budgetAccount("Checking"))
        val arrived = stored(reserve, "TX-1", oct13, amount(120), payee = "INTERNET TRANSFER FROM CHECKING")

        compare()
        val item = transfers().withLeg(arrived)

        assertEquals("Checking" to "Reserve Savings", item.outflowBudgetAccountName to item.inflowBudgetAccountName)
        assertNull(item.outflowLeg)
        assertFalse(item.bothArrived)
        assertFalse(item.guessed)
        assertEquals(ExpectedOtherSide("Checking", amount(-120), LocalDate.of(2026, 10, 19), graceEnded = false), item.expected)
        assertEquals(arrived, (item.eligibility as TransferEligibility.Eligible).writtenThrough.storedBankTransaction)

        // The grace period ends 10-19; on 10-20 the Comparison that makes it ordinary has not run yet (review 2a).
        slice.clock.advance(Duration.ofDays(5))
        val ended = transfers().withLeg(arrived)
        assertEquals(true, ended.expected!!.graceEnded)
        assertEquals(TransferEligibility.GraceEnded, ended.eligibility)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period", businessRules = ["BR-005"])
    @DisplayName("A5, BR-005 (O-T-11): a Bank Transaction whose other side did not arrive within the grace period is no Transfer and is listed apart as an ordinary Bank Transaction with the Budget Account it waited for and the last day of the grace period")
    fun otherSideNotArrivedInTime() = runTest {
        ready()
        checking()
        val reserve = reserve()
        slice.connectionStore.link(reserve, transferPartner = budgetAccount("Checking"))
        val late = stored(reserve, "TX-1", LocalDate.of(2026, 10, 8), amount(75), payee = "INTERNET TRANSFER FROM CHECKING")

        compare()
        val view = transfers()

        assertTrue(view.transfers.isEmpty())
        val ordinary = view.noLongerTransfers.single()
        assertEquals(late, ordinary.leg.storedBankTransaction)
        assertEquals(TransactionStatus.New, ordinary.leg.status)
        assertEquals("Checking", ordinary.otherBudgetAccountName)
        assertFalse(ordinary.proposed)
        assertEquals(6 to LocalDate.of(2026, 10, 14), ordinary.graceDays to ordinary.lastDay)
    }

    // ---- BR-010, BR-013: why a Transfer is not eligible --------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: a Transfer with a Transfer Leg that is Too Old, or Before Start Date, is listed as one Transfer that is not eligible because of that Transfer Leg, whose status it names; the other Transfer Leg keeps the status Transfer Leg")
    fun earlierRuleKeepsTheTransferOut() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val old = stored(checking, "TX-1", LocalDate.of(2026, 10, 5), amount(-60))
        val inWindow = stored(card, "TX-2", LocalDate.of(2026, 10, 9), amount(60))

        compare()
        val item = transfers().withLeg(old)
        val keptOut = item.eligibility as TransferEligibility.KeptOutBy
        assertEquals(old to TransactionStatus.TooOld, keptOut.leg.storedBankTransaction to keptOut.leg.status)
        assertEquals(TransactionStatus.TransferLeg, item.inflowLeg!!.status)
        assertEquals(inWindow, item.inflowLeg!!.storedBankTransaction)

        slice.connectionStore.bankAccountList.replaceAll { if (it.id == card) it.copy(startDate = LocalDate.of(2026, 10, 10)) else it }
        slice.settings.settings = slice.settings.settings.copy(uploadWindowDays = 30)
        compare()
        val before = transfers().withLeg(old).eligibility as TransferEligibility.KeptOutBy
        assertEquals(inWindow to TransactionStatus.BeforeStartDate, before.leg.storedBankTransaction to before.leg.status)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A9: Transfer With a Doubtful Transfer Leg", businessRules = ["BR-013"])
    @DisplayName("A9 step 1, BR-013: a Transfer with a Transfer Leg that is Needs Review is listed as waiting for the Review Decision on that Transfer Leg, from either side, with the reason it is doubtful")
    fun doubtfulLegMakesTheTransferWait() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-45))
        val noReference = stored(card, null, oct12, amount(45))

        compare()
        val item = transfers().withLeg(paid)

        val waits = item.eligibility as TransferEligibility.WaitsForReviewDecision
        assertEquals(noReference to TransactionStatus.NeedsReview, waits.leg.storedBankTransaction to waits.leg.status)
        // A9 step 1 (review 1a): with the reason it is doubtful, the one the Bank Account screen derives.
        assertEquals(StatusReason.NoStableReference(identical = 0), waits.why)
        val line = slice.bankAccountTransactions.of(card).weeks.flatMap { it.lines }.single { it.key.id == noReference }
        assertEquals(line.reason, waits.why)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-010"])
    @DisplayName("A8, BR-010 (review 5a): a card's inflow without a proposed paying side that an earlier rule made Before Start Date has no stored Transfer and no status Guessed Transfer, and is still listed as a Guessed Transfer kept out by that status, by the detection's own rules")
    fun firstCardPaymentWithoutProposalBeforeTheStartDate() = runTest {
        ready()
        checking()
        reserve()
        val card = rewardsCard()
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == card) it.copy(startDate = oct13.plusDays(1)) else it }
        val old = stored(card, "TX-1", oct13, amount(95), payee = "AUTOPAY RECEIVED")

        compare()
        val item = transfers().withLeg(old)

        assertEquals(TransactionStatus.BeforeStartDate, item.inflowLeg!!.status)
        assertFalse(item.stored)
        assertTrue(item.guessed)
        assertEquals(listOf(WhyGuessed.NoFundedBy("Kestrel Rewards", payingBankAccounts = 2)), item.whyGuessed)
        assertEquals(old, (item.eligibility as TransferEligibility.KeptOutBy).leg.storedBankTransaction)
        assertEquals(0, transfers().guessedTransferLegs)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010 (review 5a, 6a): a Guessed Transfer one of whose Bank Accounts was retired since the latest Comparison keeps both Transfer Legs until the next one, is not eligible, and only the Transfer Leg the counts count is counted as waiting for a decision")
    fun retiredSinceTheComparison() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-410))
        stored(card, "TX-2", oct13, amount(410))
        compare()
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(retired = true) else it }

        val view = transfers()
        val item = view.withLeg(paid)

        assertTrue(item.bothArrived)
        assertEquals(TransferEligibility.Guessed, item.eligibility)
        assertEquals(1, view.guessedTransferLegs)
        assertEquals(view.guessedTransferLegs, slice.status.workspace(household()).summary.notYetUploaded.waitingForDecision)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1 (review 5a): a Transfer that is not guessed, whose outflow side's Bank Account was retired since the latest Comparison, is listed with both sides and is not eligible until the next Comparison")
    fun notEligibleWhenTheWritingSideIsRetired() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-410))
        stored(card, "TX-2", oct13, amount(410))
        compare()
        assertTrue(transfers().withLeg(paid).eligibility is TransferEligibility.Eligible)

        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(retired = true) else it }

        assertEquals(TransferEligibility.NotEligible, transfers().withLeg(paid).eligibility)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1: a Budget whose compared Bank Transactions hold no Transfer lists none, with the dates its Bank Transactions cover; one not read yet has no dates")
    fun noTransfers() = runTest {
        ready()
        val checking = checking()
        stored(checking, "TX-1", oct10, amount(-18), payee = "Corner Grocer")
        assertNull(transfers().covers)

        stored(checking, "TX-2", oct13, amount(-9), payee = "Kiosk")
        compare()
        val view = transfers()

        assertTrue(view.transfers.isEmpty())
        assertTrue(view.noLongerTransfers.isEmpty())
        assertEquals(oct10..oct13, view.covers)
        assertEquals(0, view.guessedTransfers)
    }
}
