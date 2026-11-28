package slussen.core.transfer

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonReport
import slussen.core.comparison.ComparisonStart
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.OtherSideKind
import slussen.core.port.ReviewChoice
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredSentRecord
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDropReason
import slussen.core.port.TransferType
import slussen.core.review.ReviewOutcome
import slussen.core.status.NotYetUploaded
import slussen.core.status.TransferEligibility
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
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
 * Review Transfers (UC-005), the Transfer Decisions: steps 2-6, A1 Split, A2 Held, A3 Budget Account Chosen with BR-016
 * and the refusal of A10, A4, the decision that completes A8, A9, BR-005 (the grace period and the join of an arriving
 * other side), BR-007, BR-008 (with Sent Records this test plants: no Upload writes one yet), BR-010, BR-011 and BR-015,
 * through the core's entry functions on the fakes of `:testing`. The cases of the review of the Wave 2 specification
 * (`W2-0.md` items 2, 3, 4, 6, 8, 9) each have a test whose name says the case. The detection itself is tested in
 * [UC005ReviewTransfersTest]. Every name, amount, payee and identifier is invented (C-005).
 */
@DisplayName("UC-005: Review Transfers, the Transfer Decisions")
class UC005TransferDecisionsTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private val today: LocalDate = LocalDate.of(2026, 10, 15)
    private var nextId = 7000L

    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct13: LocalDate = LocalDate.of(2026, 10, 13)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** The credentials are accepted and Household (USD: Checking, Rewards Card, Reserve Savings, Travel Card, Joint Checking) is served with the other Budgets. */
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

    private suspend fun reserve() = bankAccount("Reserve Saver", "Reserve Savings", BankAccountType.Savings)

    private suspend fun joint() = bankAccount("Kestrel Joint", "Joint Checking")

    private fun change(bankAccountId: Long, changed: (StoredBankAccount) -> StoredBankAccount) {
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == bankAccountId) changed(it) else it }
    }

    private fun bankAccountOf(id: Long): StoredBankAccount = slice.connectionStore.bankAccountList.single { it.id == id }

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet. */
    private fun stored(bankAccountId: Long, reference: String?, date: LocalDate, amount: Amount, payee: String? = "PLANTED-PAYEE"): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = if (reference == null) StableReferenceKind.None else StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = reference?.let { ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", it)) }
                ?: "SLS1-${"f".repeat(22)}%04d".format(id % 10000),
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

    private fun transaction(id: Long): StoredBankTransaction = slice.importStatusStore.bankTransactions.single { it.id == id }

    private fun status(id: Long): TransactionStatus? = transaction(id).status

    private fun transferOf(id: Long): StoredTransfer? = transaction(id).transferId?.let { transferId -> slice.copyStore.transfers.single { it.id == transferId } }

    private fun legsOf(transfer: StoredTransfer?): Set<Long> = slice.importStatusStore.bankTransactions.filter { it.transferId == transfer?.id }.map { it.id }.toSet()

    private suspend fun compare(): Int = ((slice.comparison.readAgain() as ComparisonStart.Ran).report as ComparisonReport.Compared).statusesChanged

    private suspend fun counts(): NotYetUploaded = slice.status.workspace(household()).summary.notYetUploaded

    private suspend fun eligibleOf(bankAccountId: Long): Int = slice.importStatusStore.transactionCounts().single { it.bankAccountId == bankAccountId }.eligible

    private suspend fun decide(leg: Long, choice: TransferChoice): TransferOutcome = slice.transferDecisions.decide(leg, choice)

    private suspend fun sheet(leg: Long): TransferSheet = slice.transferDecisions.sheet(leg) ?: error("The Bank Transaction $leg is a Transfer Leg of no Transfer.")

    private fun stored(decision: TransferDecision, lonely: Boolean = false, kept: OtherSideKind? = null) =
        TransferOutcome.Stored(decision, lonely, kept, detectedAgain = true)

    /** A Sent Record as an Upload will store it (UC-006), planted: no Upload exists yet. */
    private suspend fun sentRecord(leg: Long, outcome: SentRecordOutcome, asTransfer: Boolean) {
        slice.copyStore.sentRecords += StoredSentRecord(leg, bankAccountOf(transaction(leg).bankAccountId).mapping!!.budgetAccountId, asTransfer, outcome)
    }

    private fun decisionEntries(since: Int): List<JournalEntry> = slice.journalRecorder.entries.drop(since).filter { it.eventKind == JournalEventKind.OperatorDecision }

    /** Kestrel Checking paid 250.00 to Kestrel Rewards, which has no Funded By: a Guessed Transfer with both Transfer Legs (A8). Returns (outflow, inflow). */
    private suspend fun guessedPair(): Pair<Long, Long> {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        return paid to received
    }

    /** The same Card Payment to a card whose Funded By is Checking: a Transfer that is not guessed. Returns (outflow, inflow). */
    private suspend fun plainPair(): Pair<Long, Long> {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        return paid to received
    }

    /**
     * An inflow of 120.00 to Kestrel Rewards, whose Funded By is Checking, that matches a Card Payment Description and
     * whose other side has not arrived: a Lonely Transfer from Checking (A4). Returns (the inflow, Kestrel Checking).
     */
    private suspend fun lonelyCardPayment(date: LocalDate = oct13): Pair<Long, Long> {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val received = stored(card, "TX-1", date, amount(120), payee = "AUTOPAY")
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(setOf(received), legsOf(transferOf(received)))
        return received to checking
    }

    // ---- Steps 2-6: select and confirm ----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-010", "BR-011", "BR-014"], requirements = ["NFR-002"])
    @DisplayName("Steps 2-6, BR-010, BR-011: the selected Guessed Transfer shows the proposed other Budget Account and the other fetched Budget Accounts of the Budget; confirming stores Confirmed with its time, removes the flag, lowers the number of Guessed Transfers, makes the Transfer eligible as one linked transfer through its outflow side, and is journaled once for each Transfer Leg with the Transfer Slussen had proposed")
    fun confirmingAGuessedTransferStoresTheDecisionAndRemovesTheFlag() = runTest {
        val (paid, received) = guessedPair()
        reserve()
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size
        assertEquals(1, slice.budgetTransfers.of(household()).guessedTransfers)
        assertEquals(NotYetUploaded(0, 2), counts())

        val shown = sheet(received)
        assertEquals(TransferType.CardPayment, shown.type)
        assertEquals("Checking" to "Rewards Card", shown.outflowBudgetAccountName to shown.inflowBudgetAccountName)
        assertEquals(amount(250), shown.amount)
        assertEquals(listOf(paid, received), shown.legs.map { it.storedBankTransaction })
        assertTrue(shown.guessed)
        assertEquals(TransferDecision.Proposed, shown.decision)
        assertTrue(shown.canConfirm && shown.canSplit && shown.canHold)
        assertFalse(shown.locked)
        // BR-014: only Budget Accounts Slussen fetches, of the same Budget, other than the card's own: Travel Card and Joint Checking receive through no Account Mapping.
        assertEquals(listOf("Checking", "Reserve Savings"), shown.offered.map { it.name })
        assertEquals(listOf(true, false), shown.offered.map { it.proposed })

        assertEquals(stored(TransferDecision.Confirmed), decide(received, TransferChoice.Confirm))

        val transfer = slice.copyStore.transfers.single()
        assertEquals(TransferDecision.Confirmed, transfer.decision)
        assertEquals(now, transfer.decidedAt)
        assertFalse(transfer.guessed)
        assertEquals(setOf(paid, received), legsOf(transfer))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(1, eligibleOf(transaction(paid).bankAccountId))
        assertEquals(0, slice.budgetTransfers.of(household()).guessedTransfers)
        assertTrue(slice.stateVersion.value > version)
        assertFalse(slice.copyStore.pending)
        val entries = decisionEntries(journaled)
        assertEquals(
            listOf("Kestrel Checking" to transaction(paid).importIdentifier, "Kestrel Rewards" to transaction(received).importIdentifier),
            entries.map { it.bankAccountName to it.importIdentifier },
        )
        val expected = "Transfer Decision Confirmed; the Transfer Slussen had proposed: Card Payment of 250.000 from Budget Account Checking " +
            "to Budget Account Rewards Card, both Transfer Legs arrived, a Guessed Transfer"
        assertEquals(listOf(expected, expected), entries.map { it.details })
        assertTrue(entries.none { "PLANTED-PAYEE" in it.details })
        // The decision is kept across a later Comparison (postcondition).
        assertEquals(0, compare())
        assertEquals(transfer, slice.copyStore.transfers.single())
    }

    // ---- A1: Split ------------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A1: Split Into Ordinary Transactions", businessRules = ["BR-001", "BR-010", "BR-011"])
    @DisplayName("A1, BR-010: splitting stores Split, keeps the Transfer with its Transfer Legs, gives each its status as an ordinary Bank Transaction, eligible on its own, and Slussen forms no other Transfer from them at the next Comparison; a Guessed Transfer keeps its flag but is not counted any more")
    fun splittingMakesEachTransferLegAnOrdinaryBankTransaction() = runTest {
        val (paid, received) = guessedPair()
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored(TransferDecision.Split), decide(paid, TransferChoice.Split))

        val transfer = slice.copyStore.transfers.single()
        assertEquals(TransferDecision.Split, transfer.decision)
        assertTrue(transfer.guessed)
        assertEquals(setOf(paid, received), legsOf(transfer))
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertEquals(NotYetUploaded(2, 0), counts())
        val view = slice.budgetTransfers.of(household())
        assertEquals(0, view.guessedTransfers)
        assertEquals(TransferEligibility.Split, view.transfers.single().eligibility)
        assertEquals(2, decisionEntries(journaled).size)
        // Another inflow to the card that could pair with the outflow, were it not a Transfer Leg of the split Transfer.
        val alsoReceived = stored(transaction(received).bankAccountId, "TX-3", oct12, amount(250))
        compare()
        assertEquals(TransactionStatus.New, status(alsoReceived))
        assertNull(transaction(alsoReceived).transferId)
        assertEquals(listOf(transfer), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(transfer))
    }

    // ---- A2: Held -------------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-007", "BR-010"])
    @DisplayName("A2, BR-007: holding back stores Held and gives both Transfer Legs the status Held Transfer in one step: neither is eligible, as a Transfer or as an ordinary transaction, neither waits for a decision, and a later Comparison keeps both; holding never releases one Transfer Leg")
    fun holdingBackGivesBothTransferLegsHeldTransfer() = runTest {
        val (paid, received) = plainPair()
        assertEquals(NotYetUploaded(1, 0), counts())

        assertEquals(stored(TransferDecision.Held), decide(received, TransferChoice.Hold))

        assertEquals(TransferDecision.Held, slice.copyStore.transfers.single().decision)
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(NotYetUploaded(0, 0), counts())
        assertEquals(TransferEligibility.HeldBack, slice.budgetTransfers.of(household()).transfers.single().eligibility)
        assertEquals(0, compare())
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(setOf(paid, received), legsOf(slice.copyStore.transfers.single()))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-007", "BR-010"])
    @DisplayName("BR-010 (S-B-08): for a held Transfer a status an earlier rule of the Comparison gives a Transfer Leg comes before Held Transfer: a Transfer Leg that is Too Old stays Too Old, the other is Held Transfer, and nothing is eligible")
    fun aHeldTransferLegKeepsTheStatusAnEarlierRuleGaveIt() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        // The Upload Window starts on 8 October: the outflow is dated before it.
        val paid = stored(checking, "TX-1", today.minusDays(8), amount(-250))
        val received = stored(card, "TX-2", today.minusDays(6), amount(250))
        compare()
        assertEquals(TransactionStatus.TooOld, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))

        assertEquals(stored(TransferDecision.Held), decide(received, TransferChoice.Hold))

        assertEquals(TransactionStatus.TooOld, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(NotYetUploaded(0, 0), counts())
    }

    // ---- BR-008: changes until a Transfer Leg is uploaded ------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-010", "BR-011"])
    @DisplayName("BR-008: a Transfer Decision is changed any number of times while no Transfer Leg is uploaded: Held, then Confirmed, then Split, then Confirmed again, each with the statuses of BR-010 and its own journal entries; the same decision again stores nothing")
    fun aTransferDecisionIsChangedAnyNumberOfTimes() = runTest {
        val (paid, received) = plainPair()

        assertEquals(stored(TransferDecision.Held), decide(paid, TransferChoice.Hold))
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        val journaled = slice.journalRecorder.entries.size
        assertEquals(stored(TransferDecision.Confirmed), decide(paid, TransferChoice.Confirm))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(NotYetUploaded(1, 0), counts())
        val changed = decisionEntries(journaled)
        assertEquals(2, changed.size)
        assertTrue(changed.all { it.details.startsWith("Transfer Decision changed from Held to Confirmed; the Transfer as the Operator had decided it: Card Payment of 250.000") })

        val version = slice.stateVersion.value
        val before = slice.journalRecorder.entries.size
        assertEquals(TransferOutcome.Unchanged(TransferDecision.Confirmed), decide(received, TransferChoice.Confirm))
        assertEquals(version, slice.stateVersion.value)
        assertEquals(before, slice.journalRecorder.entries.size)

        assertEquals(stored(TransferDecision.Split), decide(received, TransferChoice.Split))
        assertEquals(NotYetUploaded(2, 0), counts())
        assertEquals(stored(TransferDecision.Confirmed), decide(received, TransferChoice.Confirm))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(setOf(paid, received), legsOf(slice.copyStore.transfers.single()))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A7: Transfer Already Uploaded", businessRules = ["BR-008"])
    @DisplayName("A7, BR-008 (with a planted Sent Record): once a Transfer Leg has a Sent Record that is Written, Already Present, Not Yet Known or Unknown the Transfer Decision is locked: the sheet offers no choice and every decision is refused with nothing stored; a Transfer Leg the Budget App rejected locks nothing")
    fun aTransferDecisionIsLockedOnceATransferLegIsUploaded() = runTest {
        val (paid, received) = plainPair()
        val before = slice.copyStore.transfers.single()

        sentRecord(paid, SentRecordOutcome.Rejected, asTransfer = true)
        assertFalse(sheet(paid).locked)

        listOf(SentRecordOutcome.Written, SentRecordOutcome.AlreadyPresent, SentRecordOutcome.NotYetKnown, SentRecordOutcome.Unknown).forEach { outcome ->
            slice.copyStore.sentRecords.clear()
            sentRecord(paid, outcome, asTransfer = true)
            val version = slice.stateVersion.value

            val shown = sheet(received)
            assertTrue(shown.locked, "$outcome")
            assertFalse(shown.canConfirm || shown.canSplit || shown.canHold)
            assertTrue(shown.offered.isEmpty())
            assertEquals(TransferOutcome.Locked, decide(received, TransferChoice.Confirm))
            assertEquals(TransferOutcome.Locked, decide(received, TransferChoice.Split))
            assertEquals(TransferOutcome.Locked, decide(paid, TransferChoice.Hold))
            assertEquals(TransferOutcome.Locked, decide(paid, TransferChoice.ChooseBudgetAccount(budgetAccount("Checking"), keep = true)))
            assertEquals(version, slice.stateVersion.value)
        }
        assertEquals(before, slice.copyStore.transfers.single())

        slice.copyStore.sentRecords.clear()
        sentRecord(paid, SentRecordOutcome.Rejected, asTransfer = true)
        assertEquals(stored(TransferDecision.Held), decide(received, TransferChoice.Hold))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A7: Transfer Already Uploaded", businessRules = ["BR-008"])
    @DisplayName("BR-008 (with a planted Sent Record): after Split, a Transfer Leg uploaded as an ordinary transaction locks the Transfer Decision too, and a Comparison keeps the split Transfer with both Transfer Legs")
    fun aSplitTransferIsLockedOnceATransferLegIsUploadedAsOrdinary() = runTest {
        val (paid, received) = plainPair()
        assertEquals(stored(TransferDecision.Split), decide(paid, TransferChoice.Split))

        sentRecord(received, SentRecordOutcome.Written, asTransfer = false)

        assertTrue(sheet(paid).locked)
        assertEquals(TransferOutcome.Locked, decide(paid, TransferChoice.Confirm))
        assertEquals(TransferOutcome.Locked, decide(paid, TransferChoice.Hold))
        compare()
        val transfer = slice.copyStore.transfers.single()
        assertEquals(TransferDecision.Split, transfer.decision)
        assertEquals(setOf(paid, received), legsOf(transfer))
        assertEquals(TransactionStatus.New, status(paid))
    }

    // ---- A3, BR-016: another Budget Account -------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-006", "BR-010", "BR-016"])
    @DisplayName("A3, A8, BR-016 (S-B-13): choosing the Budget Account Slussen proposed for a first Card Payment pairs with the Transfer's own other Transfer Leg, stores Budget Account Chosen, removes the flag and, when the Operator keeps the answer, sets the Funded By of the card, which is the Transfer Leg that stays, and journals it; Slussen proposes keeping for its own proposal and not keeping for another Budget Account")
    fun choosingTheProposedBudgetAccountKeepsItAsTheFundedBy() = runTest {
        val (paid, received) = guessedPair()
        reserve()
        val card = transaction(received).bankAccountId
        val journaled = slice.journalRecorder.entries.size

        val shown = sheet(paid)
        assertEquals(received, shown.staysWith)
        assertEquals(OtherSideKind.FundedBy, shown.keptAs)
        assertEquals(listOf("Checking" to true, "Reserve Savings" to false), shown.offered.map { it.name to it.proposesKeeping })

        assertEquals(
            stored(TransferDecision.BudgetAccountChosen, kept = OtherSideKind.FundedBy),
            decide(paid, TransferChoice.ChooseBudgetAccount(budgetAccount("Checking"), keep = true)),
        )

        val transfer = slice.copyStore.transfers.single()
        assertEquals(TransferDecision.BudgetAccountChosen, transfer.decision)
        assertFalse(transfer.guessed)
        assertEquals(budgetAccount("Checking") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(setOf(paid, received), legsOf(transfer))
        assertEquals(budgetAccount("Checking"), bankAccountOf(card).fundedByBudgetAccountId)
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(0, slice.budgetTransfers.of(household()).guessedTransfers)
        val entries = slice.journalRecorder.entries.drop(journaled)
        assertEquals(2, entries.count { it.eventKind == JournalEventKind.OperatorDecision })
        assertEquals(
            listOf("Bank Account Kestrel Rewards: Funded By set to Budget Account Checking, kept from a Transfer Decision; it had none"),
            entries.filter { it.eventKind == JournalEventKind.AccountMappingChange }.map { it.details },
        )
        // The Funded By that was kept is used: the next Card Payment to the card is not guessed.
        val next = stored(card, "TX-9", oct14, amount(40), payee = "AUTOPAY")
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(next))
        assertFalse(transferOf(next)!!.guessed)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A3: Choose Another Budget Account", businessRules = ["BR-016"])
    @DisplayName("A3 step 3, BR-016: when the Bank Account of the chosen Budget Account holds no Bank Transaction with the opposite amount within the transfer pairing days, the Transfer becomes a Lonely Transfer towards the chosen Budget Account; the Transfer Leg the choice released is detected again and is an ordinary Bank Transaction; without the Operator's answer no Funded By is set")
    fun choosingABudgetAccountWithoutACandidateMakesALonelyTransfer() = runTest {
        val (paid, received) = guessedPair()
        val reserve = reserve()
        // Same amount, but 6 days from the card's Transfer Leg with 5 transfer pairing days: no candidate.
        val tooFar = stored(reserve, "TX-5", oct13.minusDays(6), amount(-250))
        compare()
        val card = transaction(received).bankAccountId

        assertEquals(
            stored(TransferDecision.BudgetAccountChosen, lonely = true),
            decide(paid, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false)),
        )

        val transfer = slice.copyStore.transfers.single()
        assertEquals(TransferDecision.BudgetAccountChosen, transfer.decision)
        assertEquals(budgetAccount("Reserve Savings") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(setOf(received), legsOf(transfer))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertNull(transaction(paid).transferId)
        assertEquals(TransactionStatus.New, status(paid))
        assertNull(transaction(tooFar).transferId)
        assertNull(bankAccountOf(card).fundedByBudgetAccountId)
        assertFalse(slice.copyStore.pending)
        // The Lonely Transfer is eligible through its one Transfer Leg, and the released outflow on its own.
        assertEquals(1, eligibleOf(card))
        assertEquals(1, eligibleOf(transaction(paid).bankAccountId))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A3: Choose Another Budget Account", businessRules = ["BR-003", "BR-016"])
    @DisplayName("A3 step 3, BR-016: among the Bank Transactions of the chosen Budget Account that belong to no Transfer, Slussen pairs with the one with the nearest booked date, then the one it received first; the Transfer has both Transfer Legs and its former other Transfer Leg is released")
    fun choosingPairsWithTheNearestFreeBankTransaction() = runTest {
        val (paid, received) = guessedPair()
        val reserve = reserve()
        val far = stored(reserve, "TX-5", oct10, amount(-250))
        val near = stored(reserve, "TX-6", oct14, amount(-250))
        val nearLater = stored(reserve, "TX-7", oct12, amount(-250))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == nearLater) it.copy(firstSeenAt = now.minus(Duration.ofMinutes(30))) else it }
        compare()
        // With three more candidates the card's inflow stays a Guessed Transfer with one of them.
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        val selected = transferOf(received)!!

        assertEquals(stored(TransferDecision.BudgetAccountChosen), decide(received, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false)))

        val transfer = transferOf(received)!!
        assertEquals(selected.id, transfer.id)
        // 14 October and 12 October are both one day from 13 October; TX-6 was received first.
        assertEquals(setOf(received, near), legsOf(transfer))
        assertEquals(budgetAccount("Reserve Savings"), transfer.outflowBudgetAccountId)
        assertEquals(TransactionStatus.TransferLeg, status(near))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        listOf(paid, far, nearLater).forEach { assertNull(transaction(it).transferId, "$it") }
        assertEquals(1, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A3: Choose Another Budget Account", businessRules = ["BR-016"])
    @DisplayName("A3 step 3, BR-016 (S-B-04): when the only Bank Transaction that could be the other side is a Transfer Leg of a Transfer without a Transfer Decision, Slussen takes it from that Transfer, which is dissolved, and detects Transfers again for its other Transfer Leg")
    fun choosingTakesTheOtherSideFromATransferWithoutADecision() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val reserve = reserve()
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        // A Bank Transfer from Reserve Saver to Kestrel Checking, proposed by Slussen, and a card payment of the same amount with no proposal.
        val fromReserve = stored(reserve, "TX-1", oct12, amount(-250))
        val intoChecking = stored(checking, "TX-2", oct12, amount(250), payee = "Online Transfer")
        val toCard = stored(card, "TX-3", oct13, amount(250), payee = "AUTOPAY")
        compare()
        val proposed = transferOf(fromReserve)!!
        assertEquals(setOf(fromReserve, intoChecking), legsOf(proposed))
        assertEquals(TransferDecision.Proposed, proposed.decision)
        assertEquals(TransactionStatus.GuessedTransfer, status(toCard))
        assertNull(transaction(toCard).transferId)

        assertEquals(
            stored(TransferDecision.BudgetAccountChosen),
            decide(toCard, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false)),
        )

        val transfer = transferOf(toCard)!!
        assertEquals(setOf(toCard, fromReserve), legsOf(transfer))
        assertEquals(TransferType.CardPayment, transfer.type)
        assertEquals(budgetAccount("Reserve Savings") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertTrue(slice.copyStore.transfers.none { it.id == proposed.id })
        // The other Transfer Leg of the dissolved Transfer is detected again: with its description and the Transfer Partner it is a Lonely Transfer towards Reserve Savings.
        val again = transferOf(intoChecking)!!
        assertEquals(TransferDecision.Proposed, again.decision)
        assertEquals(setOf(intoChecking), legsOf(again))
        assertEquals(budgetAccount("Reserve Savings") to budgetAccount("Checking"), again.outflowBudgetAccountId to again.inflowBudgetAccountId)
        assertEquals(2, slice.copyStore.transfers.size)
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A10: Other Side Already Decided or Uploaded", businessRules = ["BR-016"])
    @DisplayName("A10, BR-016: when the only Bank Transaction that could be the other side is a Transfer Leg of a Transfer the Operator decided on, the choice is refused: no Transfer Decision is stored, no Funded By is set, and the other Transfer is named with its two Budget Accounts, amount, dates and Transfer Decision; never a Lonely Transfer towards that Budget Account")
    fun choosingIsRefusedWhenTheOtherSideBelongsToADecidedTransfer() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val reserve = reserve()
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        val fromReserve = stored(reserve, "TX-1", oct12, amount(-250))
        val intoChecking = stored(checking, "TX-2", oct12, amount(250), payee = "Online Transfer")
        val toCard = stored(card, "TX-3", oct13, amount(250), payee = "AUTOPAY")
        compare()
        assertEquals(stored(TransferDecision.Confirmed), decide(intoChecking, TransferChoice.Confirm))
        val confirmed = transferOf(fromReserve)!!
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        val refused = decide(toCard, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = true))

        assertEquals(
            TransferOutcome.OtherSideTaken(
                listOf(
                    TakenOtherSide(
                        fromReserve, oct12, amount(-250), uploaded = false,
                        transfer = TransferNamed("Reserve Savings", "Checking", amount(250), listOf(oct12, oct12), TransferDecision.Confirmed, locked = false),
                    ),
                ),
            ),
            refused,
        )
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertNull(transaction(toCard).transferId)
        assertEquals(TransactionStatus.GuessedTransfer, status(toCard))
        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(setOf(fromReserve, intoChecking), legsOf(confirmed))
        assertNull(bankAccountOf(card).fundedByBudgetAccountId)
        // A10 step 2: splitting or holding back the selected Transfer stays possible.
        assertEquals(stored(TransferDecision.Held, lonely = true), decide(toCard, TransferChoice.Hold))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A10: Other Side Already Decided or Uploaded", businessRules = ["BR-008", "BR-016"])
    @DisplayName("A10, BR-016 (W2-0 item 3, with a planted Sent Record): a Bank Transaction that is not uploaded itself but is a Transfer Leg of a Transfer without a Transfer Decision whose other Transfer Leg has been uploaded is never taken: the choice is refused, that Transfer stays as it is, and no Lonely Transfer towards the chosen Budget Account is stored")
    fun choosingIsRefusedWhenTheOtherSideBelongsToAProposedTransferWithAnUploadedLeg() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val reserve = reserve()
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        val fromReserve = stored(reserve, "TX-1", oct12, amount(-250))
        val intoChecking = stored(checking, "TX-2", oct12, amount(250), payee = "Online Transfer")
        val toCard = stored(card, "TX-3", oct13, amount(250), payee = "AUTOPAY")
        compare()
        val proposed = transferOf(fromReserve)!!
        // The Upload wrote the Transfer through its inflow side as a linked transfer: its counterpart in Reserve Savings stands for TX-1.
        sentRecord(intoChecking, SentRecordOutcome.Written, asTransfer = true)

        val refused = decide(toCard, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false))

        assertEquals(
            TransferOutcome.OtherSideTaken(
                listOf(
                    TakenOtherSide(
                        fromReserve, oct12, amount(-250), uploaded = false,
                        transfer = TransferNamed("Reserve Savings", "Checking", amount(250), listOf(oct12, oct12), TransferDecision.Proposed, locked = true),
                    ),
                ),
            ),
            refused,
        )
        assertNull(transaction(toCard).transferId)
        assertEquals(listOf(proposed), slice.copyStore.transfers.toList())
        assertEquals(setOf(fromReserve, intoChecking), legsOf(proposed))
        // A Comparison keeps the uploaded Transfer as it is, too.
        compare()
        assertEquals(setOf(fromReserve, intoChecking), legsOf(proposed))
        assertEquals(listOf(proposed), slice.copyStore.transfers.toList())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A10: Other Side Already Decided or Uploaded", businessRules = ["BR-008", "BR-016"])
    @DisplayName("A10, BR-016 (with a planted Sent Record): when the only Bank Transaction that could be the other side has been uploaded itself, the choice is refused and nothing is stored; a Bank Transaction the Budget App rejected was not written and is paired with")
    fun choosingIsRefusedWhenTheOtherSideWasUploaded() = runTest {
        val (_, received) = guessedPair()
        val reserve = reserve()
        val fromReserve = stored(reserve, "TX-5", oct14, amount(-250))
        compare()
        assertNull(transaction(fromReserve).transferId)
        sentRecord(fromReserve, SentRecordOutcome.Written, asTransfer = false)
        val chosen = TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false)

        assertEquals(TransferOutcome.OtherSideTaken(listOf(TakenOtherSide(fromReserve, oct14, amount(-250), uploaded = true, transfer = null))), decide(received, chosen))
        assertEquals(TransferDecision.Proposed, transferOf(received)!!.decision)
        assertEquals(1, slice.copyStore.transfers.size)

        slice.copyStore.sentRecords.clear()
        sentRecord(fromReserve, SentRecordOutcome.Rejected, asTransfer = false)
        compare()
        assertEquals(stored(TransferDecision.BudgetAccountChosen), decide(received, chosen))
        assertEquals(setOf(received, fromReserve), legsOf(transferOf(received)))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A3: Choose Another Budget Account", businessRules = ["BR-016"])
    @DisplayName("BR-016 (W2-0 item 4): a choice that releases a Transfer Leg stores the decision and marks the Comparison as pending in the same step, so that when Slussen stops before the Transfers are detected again no Upload Plan can be prepared from the released Transfer Leg's old status; concluding from stored data then gives it its status without a read of the Budget App")
    fun aChoiceThatReleasesATransferLegBlocksUploadPlansUntilTransfersAreDetectedAgain() = runTest {
        val (paid, received) = guessedPair()
        reserve()
        val readsBefore = slice.budgetApp.reader.changeReads.size
        slice.copyStore.failsToConclude = true

        val outcome = decide(received, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false))

        // The decision is stored and said to be stored; what could not be done is said too.
        assertEquals(TransferOutcome.Stored(TransferDecision.BudgetAccountChosen, lonely = true, kept = null, detectedAgain = false), outcome)
        assertEquals(TransferDecision.BudgetAccountChosen, transferOf(received)!!.decision)
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertNull(transaction(paid).transferId)
        // The released Transfer Leg still has the status of the Guessed Transfer it left, and the marker blocks every Upload Plan (ADR-007 §3).
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertTrue(slice.copyStore.pending)

        slice.copyStore.failsToConclude = false
        assertTrue(slice.comparison.recomputePending())

        assertFalse(slice.copyStore.pending)
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(readsBefore, slice.budgetApp.reader.changeReads.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A3: Choose Another Budget Account", businessRules = ["BR-006", "BR-014", "BR-016"])
    @DisplayName("BR-016, BR-014: for a Bank Transfer the outflow is the Transfer Leg that stays and the answer is kept as its Bank Account's Transfer Partner; a Budget Account Slussen does not fetch, one of another Budget, a Manually Kept Account or the own Budget Account of the Transfer Leg that stays is not offered and choosing it stores nothing")
    fun forABankTransferTheOutflowStaysAndOnlyFetchedBudgetAccountsAreOffered() = runTest {
        ready()
        val checking = checking()
        val reserve = reserve()
        val joint = joint()
        val sent = stored(checking, "TX-1", oct12, amount(-500), payee = "Online Transfer")
        val arrived = stored(reserve, "TX-2", oct13, amount(500))
        compare()
        assertEquals(TransferType.BankTransfer, transferOf(sent)!!.type)

        val shown = sheet(arrived)
        assertEquals(sent, shown.staysWith)
        assertEquals(OtherSideKind.TransferPartner, shown.keptAs)
        assertEquals(listOf("Reserve Savings", "Joint Checking"), shown.offered.map { it.name })

        val summerHouse = slice.connectionStore.budgets().single { it.name == "Summer House" }.budgetAccounts.first().id
        slice.connectionStore.setManuallyKept(budgetAccount("Travel Card"), true)
        listOf(budgetAccount("Checking"), budgetAccount("Rewards Card"), budgetAccount("Travel Card"), summerHouse, 999_999L).forEach { notOffered ->
            assertEquals(TransferOutcome.BudgetAccountNotOffered, decide(arrived, TransferChoice.ChooseBudgetAccount(notOffered, keep = true)), "$notOffered")
        }
        assertEquals(TransferDecision.Proposed, transferOf(sent)!!.decision)
        assertNull(bankAccountOf(checking).transferPartnerBudgetAccountId)

        assertEquals(
            stored(TransferDecision.BudgetAccountChosen, lonely = true, kept = OtherSideKind.TransferPartner),
            decide(arrived, TransferChoice.ChooseBudgetAccount(budgetAccount("Joint Checking"), keep = true)),
        )

        val transfer = transferOf(sent)!!
        assertEquals(budgetAccount("Checking") to budgetAccount("Joint Checking"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(setOf(sent), legsOf(transfer))
        assertEquals(budgetAccount("Joint Checking"), bankAccountOf(checking).transferPartnerBudgetAccountId)
        assertNull(bankAccountOf(joint).transferPartnerBudgetAccountId)
        assertNull(transaction(arrived).transferId)
    }

    // ---- A4, BR-005: a Lonely Transfer the Operator decided on -------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-010"])
    @DisplayName("A4, BR-005 (S-B-03): a confirmed Lonely Transfer keeps its decision and stays a Lonely Transfer after the grace period, eligible as a linked transfer through its one Transfer Leg; the grace period ends only a Lonely Transfer without a Transfer Decision")
    fun aConfirmedLonelyTransferStaysALonelyTransferAfterTheGracePeriod() = runTest {
        val (received, _) = lonelyCardPayment(date = today.minusDays(6))
        val card = transaction(received).bankAccountId
        val undecided = stored(card, "TX-2", today.minusDays(6), amount(75), payee = "AUTOPAY")
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(undecided))

        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(received, TransferChoice.Confirm))

        // The day after the last day of the grace period.
        slice.clock.advance(Duration.ofDays(1))
        compare()

        val transfer = transferOf(received)!!
        assertEquals(TransferDecision.Confirmed, transfer.decision)
        assertEquals(setOf(received), legsOf(transfer))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(TransactionStatus.New, status(undecided))
        assertNull(transaction(undecided).transferId)
        assertEquals(2, eligibleOf(card))
        val item = slice.budgetTransfers.of(household()).transfers.single()
        assertEquals(TransferDecision.Confirmed, item.decision)
        assertTrue(item.eligibility is TransferEligibility.Eligible)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period", businessRules = ["BR-005"])
    @DisplayName("A5, BR-005: a Lonely Transfer without a Transfer Decision whose grace period ended since the latest Comparison is not decided on any more: it is an ordinary Bank Transaction, so confirming, holding back and choosing store nothing and it has no sheet, and the next Comparison releases it")
    fun aLonelyTransferWhoseGracePeriodEndedIsNotDecidedOn() = runTest {
        val (received, _) = lonelyCardPayment(date = today.minusDays(6))
        val stored = transferOf(received)!!
        val version = slice.stateVersion.value

        // The day after the last day of the grace period, before any Comparison.
        slice.clock.advance(Duration.ofDays(1))

        val notProposed = TransferOutcome.NotDecidable(TransferUndecidable.NoLongerProposed)
        assertEquals(notProposed, decide(received, TransferChoice.Confirm))
        assertEquals(notProposed, decide(received, TransferChoice.Hold))
        assertEquals(notProposed, decide(received, TransferChoice.ChooseBudgetAccount(budgetAccount("Checking"), keep = true)))
        assertNull(slice.transferDecisions.sheet(received))
        assertEquals(stored, transferOf(received))
        assertEquals(version, slice.stateVersion.value)

        compare()
        assertNull(transaction(received).transferId)
        assertEquals(TransactionStatus.New, status(received))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-010"])
    @DisplayName("BR-005 (S-B-03): when the other side of a confirmed Lonely Transfer arrives, also after the grace period, it joins the Transfer as its second Transfer Leg before Slussen proposes any other Transfer for it; the Transfer keeps its Transfer Decision and is written through its outflow side")
    fun theArrivingOtherSideJoinsAConfirmedLonelyTransfer() = runTest {
        val (received, checking) = lonelyCardPayment(date = today.minusDays(6))
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(received, TransferChoice.Confirm))
        val confirmed = transferOf(received)!!
        slice.clock.advance(Duration.ofDays(1))
        compare()
        // The outflow arrives late, 3 days from the card's Transfer Leg, with another inflow to the card it could have paired with.
        val paid = stored(checking, "TX-7", today.minusDays(3), amount(-120))
        val alsoReceived = stored(transaction(received).bankAccountId, "TX-8", today.minusDays(3), amount(120))

        compare()

        assertEquals(confirmed, transferOf(received))
        assertEquals(setOf(received, paid), legsOf(confirmed))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertNull(transaction(alsoReceived).transferId)
        assertEquals(TransactionStatus.New, status(alsoReceived))
        assertEquals(1, eligibleOf(checking))
        // Only the other inflow is eligible on the card: the Transfer is written once, through its outflow side.
        assertEquals(1, eligibleOf(transaction(received).bankAccountId))
        assertEquals(1, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-007"])
    @DisplayName("BR-005 (S-B-03, W2-0 item 2): the Bank Transaction that joins a decided Lonely Transfer may come from a Transfer without a Transfer Decision none of whose Transfer Legs has been uploaded: it leaves that Transfer, whose other Transfer Leg is detected again in the same conclusions, and the Lonely Transfer keeps its decision, here Held, for both Transfer Legs")
    fun theJoiningOtherSideLeavesATransferWithoutADecision() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        // Two Card Payments of 120.00 into the card on the same day and one outflow: Slussen pairs the outflow with the first and leaves the second a Lonely Transfer.
        val first = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        val second = stored(card, "TX-2", oct13, amount(120), payee = "AUTOPAY")
        val paid = stored(checking, "TX-3", oct13, amount(-120))
        compare()
        val proposed = transferOf(paid)!!
        assertEquals(setOf(paid, first), legsOf(proposed))
        assertEquals(TransferDecision.Proposed, proposed.decision)
        assertEquals(setOf(second), legsOf(transferOf(second)))

        // The Operator holds the Lonely Transfer back: the outflow is the other side of a Transfer the Operator decided on, before any Transfer Slussen proposes.
        assertEquals(stored(TransferDecision.Held, lonely = true), decide(second, TransferChoice.Hold))

        val held = transferOf(second)!!
        assertEquals(TransferDecision.Held, held.decision)
        assertEquals(setOf(second, paid), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(second))
        // The Transfer Leg that was left is detected again: a Lonely Transfer from Checking without a Transfer Decision.
        val again = transferOf(first)!!
        assertEquals(TransferDecision.Proposed, again.decision)
        assertEquals(setOf(first), legsOf(again))
        assertFalse(again.guessed)
        assertEquals(TransactionStatus.TransferLeg, status(first))
        assertEquals(2, slice.copyStore.transfers.size)
        assertEquals(0, eligibleOf(checking))
        assertEquals(1, eligibleOf(card))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-008"])
    @DisplayName("BR-005 (W2-0 item 2, with a planted Sent Record): a Bank Transaction that is a Transfer Leg of a Transfer without a Transfer Decision whose other Transfer Leg has been uploaded never joins a decided Lonely Transfer: that Transfer stays as it is, and the Lonely Transfer stays lonely, so that its Budget Account is not written twice")
    fun theOtherSideNeverJoinsFromATransferWithAnUploadedLeg() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val reserve = reserve()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        // Slussen proposed a Bank Transfer from Kestrel Checking to Reserve Saver, and its inflow side was uploaded as a linked transfer.
        val paid = stored(checking, "TX-2", oct13, amount(-120), payee = "Online Transfer")
        val intoReserve = stored(reserve, "TX-3", oct13, amount(120))
        compare()
        val uploaded = transferOf(paid)!!
        assertEquals(setOf(paid, intoReserve), legsOf(uploaded))
        sentRecord(intoReserve, SentRecordOutcome.Written, asTransfer = true)
        // Then a Card Payment into the card arrives alone, and the Operator confirms the Lonely Transfer from Checking.
        val toCard = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        compare()
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(toCard, TransferChoice.Confirm))

        compare()

        val confirmed = transferOf(toCard)!!
        assertEquals(setOf(toCard), legsOf(confirmed))
        assertEquals(uploaded, transferOf(paid))
        assertEquals(setOf(paid, intoReserve), legsOf(uploaded))
        assertEquals(2, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-011", "BR-015"])
    @DisplayName("BR-005, BR-015 (W2-0 item 2, with a planted Sent Record): when the Bank Transaction that would be the other side of a decided Lonely Transfer was already uploaded as an ordinary transaction, it never joins: the Transfer is dissolved, the dropped Transfer Decision is journaled with that reason, and its Transfer Leg is an ordinary Bank Transaction, not a Lonely Transfer towards that Budget Account again")
    fun aLonelyTransferWhoseOtherSideWasUploadedAsOrdinaryIsDissolved() = runTest {
        val (received, checking) = lonelyCardPayment()
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(received, TransferChoice.Confirm))
        val paid = stored(checking, "TX-7", oct14, amount(-120))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == paid) it.copy(status = TransactionStatus.New) else it }
        sentRecord(paid, SentRecordOutcome.Written, asTransfer = false)
        val journaled = slice.journalRecorder.entries.size

        compare()

        assertTrue(slice.copyStore.transfers.isEmpty())
        assertNull(transaction(received).transferId)
        assertNull(transaction(paid).transferId)
        assertEquals(TransactionStatus.New, status(received))
        val dropped = decisionEntries(journaled).single()
        assertEquals("Kestrel Rewards" to transaction(received).importIdentifier, dropped.bankAccountName to dropped.importIdentifier)
        assertEquals(
            "Slussen dropped the Transfer Decision Confirmed because its other side was already uploaded as an ordinary transaction; the Transfer was: " +
                "Card Payment of 120.000 from Budget Account Checking to Budget Account Rewards Card, one Transfer Leg arrived; its Bank Transactions are detected again",
            dropped.details,
        )
        // Nothing of it comes back at the next Comparison.
        compare()
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, status(received))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005"])
    @DisplayName("BR-005 (W2-0 item 6): when one arriving Bank Transaction could join two decided Lonely Transfers, it joins the one whose arrived Transfer Leg has the nearest booked date, and with the same booked date the one the Operator decided on first; the other stays a Lonely Transfer")
    fun ofTwoDecidedLonelyTransfersTheNearestBookedDateThenTheOneDecidedFirstIsJoined() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val far = stored(card, "TX-1", oct10, amount(120), payee = "AUTOPAY")
        val nearDecidedLater = stored(card, "TX-2", oct13, amount(120), payee = "AUTOPAY")
        val nearDecidedFirst = stored(card, "TX-3", oct13, amount(120), payee = "AUTOPAY")
        compare()
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(far, TransferChoice.Confirm))
        slice.clock.advance(Duration.ofMinutes(1))
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(nearDecidedFirst, TransferChoice.Confirm))
        slice.clock.advance(Duration.ofMinutes(1))
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(nearDecidedLater, TransferChoice.Confirm))
        val first = stored(checking, "TX-7", oct13, amount(-120))

        compare()

        assertEquals(setOf(nearDecidedFirst, first), legsOf(transferOf(nearDecidedFirst)))
        assertEquals(setOf(nearDecidedLater), legsOf(transferOf(nearDecidedLater)))
        assertEquals(setOf(far), legsOf(transferOf(far)))

        // The next one joins the Transfer of the same booked date that is left, before the one three days away.
        val second = stored(checking, "TX-8", oct13, amount(-120))
        compare()
        assertEquals(setOf(nearDecidedLater, second), legsOf(transferOf(nearDecidedLater)))
        assertEquals(setOf(far), legsOf(transferOf(far)))
        assertEquals(3, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A1: Split Into Ordinary Transactions", businessRules = ["BR-005", "BR-010"])
    @DisplayName("BR-005, BR-010: after Split nothing joins: the Transfer Leg of a split Lonely Transfer stays an ordinary Bank Transaction when a Bank Transaction that could be its other side arrives, and that one is an ordinary Bank Transaction too")
    fun nothingJoinsASplitLonelyTransfer() = runTest {
        val (received, checking) = lonelyCardPayment()
        assertEquals(stored(TransferDecision.Split, lonely = true), decide(received, TransferChoice.Split))
        assertEquals(TransactionStatus.New, status(received))
        val paid = stored(checking, "TX-7", oct14, amount(-120))

        compare()

        val split = transferOf(received)!!
        assertEquals(TransferDecision.Split, split.decision)
        assertEquals(setOf(received), legsOf(split))
        assertNull(transaction(paid).transferId)
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
    }

    // ---- A8: a first Card Payment for which Slussen proposes no Budget Account ---------------------------------------------

    /** An inflow to Kestrel Rewards without a Funded By, with two Bank Accounts that could pay: a Guessed Transfer without a proposal (A8). */
    private suspend fun firstCardPaymentWithoutProposal(): Long {
        ready()
        checking()
        reserve()
        val card = rewardsCard()
        val toCard = stored(card, "TX-1", oct13, amount(250), payee = "AUTOPAY")
        compare()
        assertEquals(TransactionStatus.GuessedTransfer, status(toCard))
        assertNull(transaction(toCard).transferId)
        assertTrue(slice.copyStore.transfers.isEmpty())
        return toCard
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-010", "BR-016"])
    @DisplayName("A8 step 2 (S-B-05, S-B-13): a Guessed Transfer for which Slussen proposes no paying Budget Account has nothing to confirm; the Operator chooses the paying Budget Account, for which Slussen proposes keeping it as the card's Funded By, and the Transfer is stored as Budget Account Chosen with both sides, without its flag and eligible")
    fun aFirstCardPaymentWithoutAProposalIsCompletedByChoosingTheBudgetAccount() = runTest {
        val toCard = firstCardPaymentWithoutProposal()
        val card = transaction(toCard).bankAccountId

        val shown = sheet(toCard)
        assertNull(shown.outflowBudgetAccountName)
        assertFalse(shown.canConfirm)
        assertTrue(shown.canSplit && shown.canHold)
        assertEquals(listOf("Checking" to true, "Reserve Savings" to true), shown.offered.map { it.name to it.proposesKeeping })
        assertEquals(listOf(false, false), shown.offered.map { it.proposed })
        assertEquals(TransferOutcome.NothingToConfirm, decide(toCard, TransferChoice.Confirm))
        assertTrue(slice.copyStore.transfers.isEmpty())

        assertEquals(
            stored(TransferDecision.BudgetAccountChosen, lonely = true, kept = OtherSideKind.FundedBy),
            decide(toCard, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = true)),
        )

        val transfer = transferOf(toCard)!!
        assertEquals(TransferDecision.BudgetAccountChosen, transfer.decision)
        assertEquals(budgetAccount("Reserve Savings") to budgetAccount("Rewards Card"), transfer.outflowBudgetAccountId to transfer.inflowBudgetAccountId)
        assertEquals(TransferType.CardPayment, transfer.type)
        assertEquals(amount(250), transfer.amount)
        assertFalse(transfer.guessed)
        assertEquals(TransactionStatus.TransferLeg, status(toCard))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(budgetAccount("Reserve Savings"), bankAccountOf(card).fundedByBudgetAccountId)
        assertEquals(0, compare())
        assertEquals(listOf(transfer), slice.copyStore.transfers.toList())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-007", "BR-010", "BR-015"])
    @DisplayName("A8 (S-B-05, W2-0 item 8): a Guessed Transfer without a paying Budget Account that the Operator holds back is stored without that side, with the status Held Transfer; a Funded By set later never fills the empty side, no Bank Transaction joins it, and it stays held back; it still has nothing to confirm")
    fun aHeldTransferWithoutAPayingSideIsNotFilledByALaterFundedBy() = runTest {
        val toCard = firstCardPaymentWithoutProposal()
        val card = transaction(toCard).bankAccountId

        assertEquals(stored(TransferDecision.Held, lonely = true), decide(toCard, TransferChoice.Hold))

        val held = transferOf(toCard)!!
        assertNull(held.outflowBudgetAccountId)
        assertEquals(budgetAccount("Rewards Card"), held.inflowBudgetAccountId)
        assertEquals(TransferDecision.Held, held.decision)
        assertTrue(held.guessed)
        assertEquals(TransactionStatus.HeldTransfer, status(toCard))
        assertEquals(NotYetUploaded(0, 0), counts())
        assertEquals(0, slice.budgetTransfers.of(household()).guessedTransfers)

        // The card gets a Funded By, and the outflow that would be its other side arrives.
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(slice.connectionStore.bankAccountList.single { it.name == "Kestrel Checking" }.id, "TX-2", oct13, amount(-250), payee = "AUTOPAY")
        compare()

        assertEquals(held, transferOf(toCard))
        assertNull(slice.copyStore.transfers.single { it.id == held.id }.outflowBudgetAccountId)
        assertEquals(setOf(toCard), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(toCard))
        // The outflow is detected as any other Bank Transaction: with the Funded By it is a Lonely Transfer of its own, without a Transfer Decision.
        val ofOutflow = transferOf(paid)
        assertNotNull(ofOutflow)
        assertEquals(TransferDecision.Proposed, ofOutflow!!.decision)
        assertEquals(setOf(paid), legsOf(ofOutflow))
        assertEquals(TransferOutcome.NothingToConfirm, decide(toCard, TransferChoice.Confirm))
        assertEquals(TransferDecision.Held, transferOf(toCard)!!.decision)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-010"])
    @DisplayName("A8 (S-B-05): a Guessed Transfer without a paying Budget Account that the Operator splits is stored without that side and its one Transfer Leg is an ordinary Bank Transaction, eligible on its own and never a Guessed Transfer again while the decision stands")
    fun aSplitTransferWithoutAPayingSideIsAnOrdinaryBankTransaction() = runTest {
        val toCard = firstCardPaymentWithoutProposal()

        assertEquals(stored(TransferDecision.Split, lonely = true), decide(toCard, TransferChoice.Split))

        val split = transferOf(toCard)!!
        assertNull(split.outflowBudgetAccountId)
        assertEquals(TransactionStatus.New, status(toCard))
        assertEquals(NotYetUploaded(1, 0), counts())
        assertEquals(0, compare())
        assertEquals(TransactionStatus.New, status(toCard))
        assertEquals(split, transferOf(toCard))
    }

    // ---- A9, BR-013: a Transfer with a doubtful Transfer Leg ---------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "A9: Transfer With a Doubtful Transfer Leg", businessRules = ["BR-013"])
    @DisplayName("A9, BR-013: while a Transfer Leg waits for the Operator's Review Decision, the sheet names it and only Split is taken; once that Transfer Leg is decided Import Anyway the Transfer can be confirmed and is eligible as one linked transfer")
    fun aTransferWithADoubtfulTransferLegTakesOnlySplitUntilTheLegIsDecided() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        // Without a Stable Reference the outflow is Needs Review (UC-003 BR-012); the card has no Funded By, so the Transfer is guessed.
        val paid = stored(checking, null, oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        assertEquals(TransactionStatus.NeedsReview, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))

        val shown = sheet(received)
        assertEquals(paid, shown.waitsForReviewDecision)
        assertFalse(shown.canConfirm || shown.canHold)
        assertTrue(shown.canSplit)
        assertTrue(shown.offered.isEmpty())
        assertEquals(TransferOutcome.WaitsForReviewDecision(paid), decide(received, TransferChoice.Confirm))
        assertEquals(TransferOutcome.WaitsForReviewDecision(paid), decide(received, TransferChoice.Hold))
        assertEquals(TransferOutcome.WaitsForReviewDecision(paid), decide(received, TransferChoice.ChooseBudgetAccount(budgetAccount("Checking"), keep = false)))
        assertEquals(TransferDecision.Proposed, transferOf(received)!!.decision)

        slice.review.decide(paid, ReviewChoice.ImportAnyway)
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(stored(TransferDecision.Confirmed), decide(received, TransferChoice.Confirm))

        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(NotYetUploaded(1, 0), counts())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A9: Transfer With a Doubtful Transfer Leg", businessRules = ["BR-010", "BR-013"])
    @DisplayName("A9 step 2, BR-013: splitting is the way to separate the two Transfer Legs: the doubtful one keeps Needs Review as an ordinary Bank Transaction and the other is New and eligible on its own")
    fun splittingSeparatesADoubtfulTransferLegFromTheOther() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, null, oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()

        assertEquals(stored(TransferDecision.Split), decide(received, TransferChoice.Split))

        assertEquals(TransactionStatus.NeedsReview, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertEquals(NotYetUploaded(1, 1), counts())
    }

    // ---- BR-015: a Transfer Decision when its ground changes ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-015"])
    @DisplayName("BR-015 (S-B-02): a confirmed Transfer keeps its decision, its Transfer Legs and its two Budget Accounts when a Funded By, a Transfer Description or the transfer pairing days change so that Slussen would not propose it any more; nothing is journaled as dropped")
    fun aDecidedTransferIsKeptWhenItsGroundChanges() = runTest {
        ready()
        val checking = checking()
        val reserve = reserve()
        val card = rewardsCard()
        val sent = stored(checking, "TX-1", oct12, amount(-500), payee = "Online Transfer")
        val arrived = stored(reserve, "TX-2", oct13, amount(500))
        val paid = stored(checking, "TX-3", oct12, amount(-250))
        val received = stored(card, "TX-4", oct13, amount(250))
        compare()
        assertEquals(stored(TransferDecision.Confirmed), decide(sent, TransferChoice.Confirm))
        assertEquals(stored(TransferDecision.Confirmed), decide(paid, TransferChoice.Confirm))
        val before = slice.copyStore.transfers.toList()
        val journaled = slice.journalRecorder.entries.size

        // The Bank Transfer Descriptions are emptied, the card's Funded By names another Budget Account, and no day may lie between two Transfer Legs.
        slice.copyStore.transferDescriptions.clear()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Reserve Savings"))
        slice.settings.settings = slice.settings.settings.copy(transferPairingDays = 0)

        assertEquals(0, compare())

        assertEquals(before, slice.copyStore.transfers.toList())
        assertEquals(setOf(sent, arrived), legsOf(transferOf(sent)))
        assertEquals(setOf(paid, received), legsOf(transferOf(paid)))
        listOf(sent, arrived, paid, received).forEach { assertEquals(TransactionStatus.TransferLeg, status(it)) }
        assertEquals(NotYetUploaded(2, 0), counts())
        assertTrue(decisionEntries(journaled).isEmpty())
    }

    /** A held Card Payment from Kestrel Checking to Kestrel Rewards, whose Funded By is Checking. Returns (outflow, inflow). */
    private suspend fun heldPair(): Pair<Long, Long> {
        val (paid, received) = plainPair()
        assertEquals(stored(TransferDecision.Held), decide(paid, TransferChoice.Hold))
        return paid to received
    }

    /** The same Card Payment, confirmed by the Operator. Returns (outflow, inflow). */
    private suspend fun confirmedPair(): Pair<Long, Long> {
        val (paid, received) = plainPair()
        assertEquals(stored(TransferDecision.Confirmed), decide(paid, TransferChoice.Confirm))
        return paid to received
    }

    /** What BR-015 asks of a dropped Transfer Decision Confirmed: the Transfer is gone, its Transfer Legs are released, and the drop is journaled once for each with [reason]. */
    private fun assertDropped(journaled: Int, reason: TransferDropReason, vararg legs: Long) {
        assertTrue(slice.copyStore.transfers.none { it.decision == TransferDecision.Confirmed })
        val entries = decisionEntries(journaled)
        assertEquals(legs.map { transaction(it).importIdentifier }.toSet(), entries.map { it.importIdentifier }.toSet())
        entries.forEach { assertTrue(it.details.startsWith("Slussen dropped the Transfer Decision Confirmed because ${reason.text}; the Transfer was: Card Payment of 250.000"), it.details) }
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-015"])
    @DisplayName("BR-015: when the Bank Account type changes so that a Card Payment has no credit card Bank Account on either side, the Transfer Decision Confirmed is dropped: the Transfer is dissolved, the drop is journaled with its reason for each Transfer Leg, and its Bank Transactions are detected again as any others, with nothing of the decision carried over")
    fun aDecisionIsDroppedWhenTheTypeNoLongerFits() = runTest {
        val (paid, received) = confirmedPair()
        val journaled = slice.journalRecorder.entries.size

        change(transaction(received).bankAccountId) { it.copy(type = BankAccountType.Savings, fundedByBudgetAccountId = null) }
        compare()

        assertDropped(journaled, TransferDropReason.TypeNoLongerFits, paid, received)
        // Two Bank Accounts that are not credit cards, and no Bank Transfer Description matches: two ordinary Bank Transactions.
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertNull(transaction(paid).transferId)
        assertEquals(NotYetUploaded(2, 0), counts())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-015"])
    @DisplayName("BR-015: when the Bank Account of a Transfer Leg is mapped to another Budget Account than the Transfer names for that side, the Transfer Decision Confirmed is dropped and journaled, and Slussen proposes a new Transfer between the Budget Accounts as they are now, without a Transfer Decision")
    fun aDecisionIsDroppedWhenATransferLegIsMappedElsewhere() = runTest {
        val (paid, received) = confirmedPair()
        val journaled = slice.journalRecorder.entries.size
        val held = transferOf(paid)!!

        val jointChecking = budgetAccount("Joint Checking")
        change(transaction(paid).bankAccountId) { it.copy(mapping = it.mapping!!.copy(budgetAccountId = jointChecking)) }
        compare()

        assertDropped(journaled, TransferDropReason.TransferLegMappedElsewhere, paid, received)
        val proposed = transferOf(paid)!!
        assertTrue(proposed.id != held.id)
        assertEquals(TransferDecision.Proposed, proposed.decision)
        assertEquals(budgetAccount("Joint Checking") to budgetAccount("Rewards Card"), proposed.outflowBudgetAccountId to proposed.inflowBudgetAccountId)
        assertEquals(setOf(paid, received), legsOf(proposed))
        // The card's Funded By, Checking, receives through no Account Mapping any more and does not apply: a Guessed Transfer (A8).
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-011", "BR-015"])
    @DisplayName("BR-015: when the Bank Account of a Transfer Leg has no Account Mapping any more, the Transfer Decision Confirmed is dropped and journaled for both Transfer Legs, also the one that is not compared any more; the other Transfer Leg is detected again")
    fun aDecisionIsDroppedWhenATransferLegLosesItsAccountMapping() = runTest {
        val (paid, received) = confirmedPair()
        val journaled = slice.journalRecorder.entries.size

        change(transaction(paid).bankAccountId) { it.copy(mapping = null) }
        compare()

        assertDropped(journaled, TransferDropReason.TransferLegNotMapped, paid, received)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertNull(transaction(paid).transferId)
        assertNull(transaction(received).transferId)
        // An inflow to a card whose Funded By does not apply, without a Card Payment Description: an ordinary Bank Transaction.
        assertEquals(TransactionStatus.New, status(received))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-011", "BR-015"])
    @DisplayName("BR-015: when the Budget Account of the side of a confirmed Lonely Transfer that has not arrived receives through no Account Mapping any more, the Transfer Decision is dropped and journaled, and its Transfer Leg is detected again")
    fun aDecisionIsDroppedWhenTheSideNotArrivedIsNoLongerFetched() = runTest {
        val (received, checking) = lonelyCardPayment()
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(received, TransferChoice.Confirm))
        val journaled = slice.journalRecorder.entries.size

        change(checking) { it.copy(mapping = null) }
        compare()

        assertTrue(slice.copyStore.transfers.isEmpty())
        val dropped = decisionEntries(journaled).single()
        assertEquals(transaction(received).importIdentifier, dropped.importIdentifier)
        assertTrue(dropped.details.startsWith("Slussen dropped the Transfer Decision Confirmed because ${TransferDropReason.OtherSideNotFetched.text}"), dropped.details)
        // The card's Funded By no longer applies and no other Bank Account could pay: a Guessed Transfer without a proposal (A8), which waits for a decision.
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        assertNull(transaction(received).transferId)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-011", "BR-015"])
    @DisplayName("BR-015 (review of B3, item 3): a confirmed Lonely Transfer whose one Transfer Leg's Bank Account loses its Account Mapping is still read: the Transfer Decision is dropped and journaled with that reason, and mapping the Bank Account again does not bring the decision back")
    fun aConfirmedLonelyTransferWhoseTransferLegLosesItsAccountMappingIsDropped() = runTest {
        val (received, _) = lonelyCardPayment()
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(received, TransferChoice.Confirm))
        val card = transaction(received).bankAccountId
        val mapping = bankAccountOf(card).mapping
        val journaled = slice.journalRecorder.entries.size

        change(card) { it.copy(mapping = null) }
        compare()

        assertTrue(slice.copyStore.transfers.isEmpty())
        assertNull(transaction(received).transferId)
        val dropped = decisionEntries(journaled).single()
        assertEquals("Kestrel Rewards" to transaction(received).importIdentifier, dropped.bankAccountName to dropped.importIdentifier)
        assertTrue(dropped.details.startsWith("Slussen dropped the Transfer Decision Confirmed because ${TransferDropReason.TransferLegNotMapped.text}"), dropped.details)

        change(card) { it.copy(mapping = mapping) }
        compare()

        // Detected again as any other Bank Transaction: a Lonely Transfer Slussen proposes, without a Transfer Decision.
        assertEquals(TransferDecision.Proposed, transferOf(received)!!.decision)
        assertEquals(1, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-007", "BR-015"])
    @DisplayName("BR-015, BR-007 (S-B-17): a held Transfer is never dissolved by a change of its ground: when the card's type changes, when the Bank Account of a Transfer Leg is mapped to another Budget Account and when it has no Account Mapping, the Transfer stays Held with its Transfer Legs, nothing of it is eligible and nothing is journaled as dropped; the Operator's own decision ends it: confirming is refused with the reason and splitting makes the Transfer Legs ordinary")
    fun aHeldTransferIsNeverDissolvedWhenItsSidesAreNoLongerValid() = runTest {
        val (paid, received) = heldPair()
        val held = transferOf(paid)!!
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        val journaled = slice.journalRecorder.entries.size
        val jointChecking = budgetAccount("Joint Checking")
        val mapping = bankAccountOf(checking).mapping!!

        suspend fun assertStillHeld(comparedLegs: List<Long>) {
            compare()
            assertEquals(listOf(held), slice.copyStore.transfers.toList())
            assertEquals(setOf(paid, received), legsOf(held))
            comparedLegs.forEach { assertEquals(TransactionStatus.HeldTransfer, status(it), "$it") }
            assertEquals(NotYetUploaded(0, 0), counts())
            assertEquals(0, eligibleOf(checking) + eligibleOf(card))
            assertTrue(decisionEntries(journaled).isEmpty())
        }

        // A Card Payment neither of whose sides is a credit card Bank Account.
        change(card) { it.copy(type = BankAccountType.Savings, fundedByBudgetAccountId = null) }
        assertStillHeld(listOf(paid, received))
        change(card) { it.copy(type = BankAccountType.CreditCard) }
        // The Bank Account of the outflow is mapped to another Budget Account than the Transfer names.
        change(checking) { it.copy(mapping = mapping.copy(budgetAccountId = jointChecking)) }
        assertStillHeld(listOf(paid, received))
        // The Bank Account of the outflow has no Account Mapping: that Transfer Leg is not compared any more.
        change(checking) { it.copy(mapping = null) }
        assertStillHeld(listOf(received))

        assertEquals(TransferOutcome.NoLongerValid(TransferDropReason.TransferLegNotMapped), decide(received, TransferChoice.Confirm))
        assertEquals(TransferDecision.Held, transferOf(received)!!.decision)
        assertEquals(stored(TransferDecision.Split), decide(received, TransferChoice.Split))
        assertEquals(TransactionStatus.New, status(received))
        assertEquals(setOf(paid, received), legsOf(transferOf(received)))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-005", "BR-007", "BR-015"])
    @DisplayName("BR-015, BR-007 (S-B-17, with a planted Sent Record): a held Lonely Transfer stays Held when its other side was already uploaded as an ordinary transaction, which never joins it, and when the Bank Account of its one Transfer Leg loses its Account Mapping: it is not dissolved and its Transfer Leg is never released as an ordinary Bank Transaction")
    fun aHeldLonelyTransferIsNeverDissolved() = runTest {
        val (received, checking) = lonelyCardPayment()
        assertEquals(stored(TransferDecision.Held, lonely = true), decide(received, TransferChoice.Hold))
        val held = transferOf(received)!!
        val journaled = slice.journalRecorder.entries.size
        val paid = stored(checking, "TX-7", oct14, amount(-120))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == paid) it.copy(status = TransactionStatus.New) else it }
        sentRecord(paid, SentRecordOutcome.Written, asTransfer = false)

        compare()

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(setOf(received), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(0, eligibleOf(transaction(received).bankAccountId))

        change(transaction(received).bankAccountId) { it.copy(mapping = null) }
        compare()

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(held.id, transaction(received).transferId)
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertTrue(decisionEntries(journaled).isEmpty())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A1: Split Into Ordinary Transactions", businessRules = ["BR-010", "BR-015"])
    @DisplayName("BR-015, BR-010 (S-B-17): a split Transfer is never dissolved by a change of its ground: when the Bank Account of a Transfer Leg is mapped to another Budget Account, the Transfer stays Split with both Transfer Legs, which stay ordinary Bank Transactions and are detected into no other Transfer, and nothing is journaled as dropped")
    fun aSplitTransferIsNeverDissolvedAndItsTransferLegsJoinNoOtherTransfer() = runTest {
        val (paid, received) = plainPair()
        assertEquals(stored(TransferDecision.Split), decide(paid, TransferChoice.Split))
        val split = transferOf(paid)!!
        val checking = transaction(paid).bankAccountId
        val journaled = slice.journalRecorder.entries.size
        val jointChecking = budgetAccount("Joint Checking")

        change(checking) { it.copy(mapping = it.mapping!!.copy(budgetAccountId = jointChecking)) }
        // Another inflow to the card that Slussen would pair with the outflow, were it released.
        val alsoReceived = stored(transaction(received).bankAccountId, "TX-3", oct12, amount(250))
        compare()

        assertEquals(listOf(split), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(split))
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertEquals(TransactionStatus.New, status(alsoReceived))
        assertNull(transaction(alsoReceived).transferId)
        assertTrue(decisionEntries(journaled).isEmpty())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-007", "BR-010"])
    @DisplayName("BR-007 (S-B-17): holding never releases one Transfer Leg: a Transfer Leg that Slussen sent and that is Deleted In Budget App offers no Re-upload while its Transfer is held back, and a Re-upload decision it already has gives it no status New and no eligibility: it is Deleted In Budget App, the other is Held Transfer and nothing is in an Upload Plan, until the Operator changes the Transfer Decision; after Split the Re-upload applies again")
    fun aTransferLegOfAHeldTransferIsNotReUploaded() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        // The Budget App holds the outflow under its own Import Identifier, deleted: Slussen sent it and it was deleted there.
        slice.budgetApp.reader.changesAnswer = { asked ->
            val deleted = BudgetAppTransactionRead(
                "ynab-1", "ba-checking", oct12, amount(-250), "Card payment", null, transaction(paid).importIdentifier, EntryKind.Imported, null,
                ReadState.Deleted, cleared = true, approved = true,
            )
            ReadResult.Answered(ChangeSet(if (asked.budgetReference == "budget-household") listOf(deleted) else emptyList(), ChangeCursor("k-${slice.budgetApp.reader.changeReads.size}")))
        }
        compare()
        assertEquals(TransactionStatus.DeletedInBudgetApp, status(paid))
        // The Operator chose Re-upload while the Transfer had no Transfer Decision (UC-004 A5).
        assertEquals(ReviewOutcome.Stored(ReviewChoice.ReUpload, TransactionStatus.New), slice.review.decide(paid, ReviewChoice.ReUpload))
        assertEquals(1, eligibleOf(checking))

        assertEquals(stored(TransferDecision.Held), decide(received, TransferChoice.Hold))

        assertEquals(TransactionStatus.DeletedInBudgetApp, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(0, eligibleOf(checking))
        assertEquals(0, eligibleOf(card))
        assertEquals(NotYetUploaded(0, 0), counts())
        val shown = slice.review.sheet(paid)!!
        assertEquals(ReviewChoice.ReUpload, shown.decision?.choice)
        assertEquals(emptyList<ReviewChoice>(), shown.choices)
        assertTrue(shown.canClear)
        assertEquals(ReviewOutcome.NotOffered(emptyList()), slice.review.decide(paid, ReviewChoice.ReUpload))
        compare()
        assertEquals(TransactionStatus.DeletedInBudgetApp, status(paid))
        assertEquals(0, eligibleOf(checking))

        // The Operator changes the Transfer Decision first: after Split the Transfer Leg is an ordinary Bank Transaction and its Re-upload applies.
        assertEquals(stored(TransferDecision.Split), decide(received, TransferChoice.Split))
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertEquals(NotYetUploaded(2, 0), counts())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A10: Other Side Already Decided or Uploaded", businessRules = ["BR-008", "BR-016"])
    @DisplayName("A10, BR-016 (review of B3, item 4, with a planted Sent Record): a Bank Transaction that is a Transfer Leg of a Transfer without a Transfer Decision whose other Transfer Leg has been uploaded as an ordinary transaction is not taken either: one of that Transfer's Transfer Legs has been uploaded, so the choice is refused and nothing is stored")
    fun choosingIsRefusedWhenTheOtherLegOfTheCandidatesTransferWasUploadedAsOrdinary() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val reserve = reserve()
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        val fromReserve = stored(reserve, "TX-1", oct12, amount(-250))
        val intoChecking = stored(checking, "TX-2", oct12, amount(250), payee = "Online Transfer")
        val toCard = stored(card, "TX-3", oct13, amount(250), payee = "AUTOPAY")
        compare()
        val proposed = transferOf(fromReserve)!!
        // The Operator holds the first Card Payment back, for which Slussen proposes no paying Budget Account.
        assertEquals(stored(TransferDecision.Held, lonely = true), decide(toCard, TransferChoice.Hold))
        val held = transferOf(toCard)!!
        sentRecord(intoChecking, SentRecordOutcome.Written, asTransfer = false)

        val refused = decide(toCard, TransferChoice.ChooseBudgetAccount(budgetAccount("Reserve Savings"), keep = false))

        assertEquals(
            TransferOutcome.OtherSideTaken(
                listOf(
                    TakenOtherSide(
                        fromReserve, oct12, amount(-250), uploaded = false,
                        transfer = TransferNamed("Reserve Savings", "Checking", amount(250), listOf(oct12, oct12), TransferDecision.Proposed, locked = true),
                    ),
                ),
            ),
            refused,
        )
        assertEquals(held, transferOf(toCard))
        assertEquals(proposed.id, transaction(fromReserve).transferId)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-008"])
    @DisplayName("BR-005 (review of B3, item 4, with a planted Sent Record): a Bank Transaction that is a Transfer Leg of a Transfer without a Transfer Decision whose other Transfer Leg has been uploaded as an ordinary transaction does not join a decided Lonely Transfer while it is a Transfer Leg of that Transfer; that Comparison releases it, since a Bank Transaction uploaded as ordinary is no Transfer Leg, and as a Transfer Leg of no Transfer it joins at the next one")
    fun theOtherSideDoesNotJoinWhileItsTransferHasALegUploadedAsOrdinary() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        // Two Card Payments of 120.00 into the card and one outflow: Slussen pairs the outflow with the first; the second is a Lonely Transfer.
        val first = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        val second = stored(card, "TX-2", oct13, amount(120), payee = "AUTOPAY")
        val paid = stored(checking, "TX-3", oct13, amount(-120))
        compare()
        val proposed = transferOf(paid)!!
        assertEquals(setOf(paid, first), legsOf(proposed))
        // As stored: the Lonely Transfer is confirmed, and the first Card Payment was uploaded as an ordinary transaction.
        val lonely = transferOf(second)!!
        val confirmed = lonely.copy(decision = TransferDecision.Confirmed, decidedAt = now)
        slice.copyStore.transfers.replaceAll { if (it.id == lonely.id) confirmed else it }
        sentRecord(first, SentRecordOutcome.Written, asTransfer = false)

        compare()

        assertEquals(setOf(second), legsOf(confirmed))
        assertNull(transaction(paid).transferId)
        assertNull(transaction(first).transferId)
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())

        compare()

        assertEquals(setOf(second, paid), legsOf(confirmed))
        assertEquals(TransferDecision.Confirmed, slice.copyStore.transfers.single().decision)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-007", "BR-015"])
    @DisplayName("BR-015 (W2-0 item 9): retiring the Bank Account of a side dissolves no Transfer the Operator decided on: a held Transfer keeps its decision, its Transfer Legs and its Budget Accounts while that Bank Account is a Retired Account, and restoring it brings the Transfer back held, never as a Transfer Slussen proposes and would upload")
    fun retiringABankAccountKeepsAHeldTransferAndRestoringBringsItBackHeld() = runTest {
        val (paid, received) = heldPair()
        val held = transferOf(paid)!!
        val checking = transaction(paid).bankAccountId
        val journaled = slice.journalRecorder.entries.size

        change(checking) { it.copy(retired = true) }
        compare()

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(NotYetUploaded(0, 0), counts())
        assertTrue(decisionEntries(journaled).isEmpty())

        change(checking) { it.copy(retired = false) }
        compare()

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(NotYetUploaded(0, 0), counts())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-015"])
    @DisplayName("BR-015 (W2-0 item 9), UC-003 BR-014: while the Bank Account of one side of a confirmed Transfer is a Retired Account, the Transfer keeps its decision and is in no Upload Plan, from either side, also when the retired side is not the one it is written through; restoring the Bank Account makes it eligible again as the Operator decided it")
    fun aConfirmedTransferWithARetiredSideIsInNoUploadPlanUntilTheBankAccountIsRestored() = runTest {
        val (paid, received) = plainPair()
        assertEquals(stored(TransferDecision.Confirmed), decide(paid, TransferChoice.Confirm))
        val confirmed = transferOf(paid)!!
        val card = transaction(received).bankAccountId
        assertEquals(NotYetUploaded(1, 0), counts())

        change(card) { it.copy(retired = true) }
        compare()

        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(confirmed))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(0, eligibleOf(transaction(paid).bankAccountId))
        assertEquals(NotYetUploaded(0, 0), counts())
        assertEquals(TransferEligibility.RetiredSide, slice.budgetTransfers.of(household()).transfers.single().eligibility)

        change(card) { it.copy(retired = false) }
        compare()

        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(1, eligibleOf(transaction(paid).bankAccountId))
        assertEquals(NotYetUploaded(1, 0), counts())
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005", "BR-015"])
    @DisplayName("BR-015 (W2-0 item 9): a confirmed Lonely Transfer whose side that has not arrived is fed by a Retired Account keeps its decision, is in no Upload Plan and is joined by no Bank Transaction while that Bank Account is retired")
    fun aLonelyTransferTowardsARetiredAccountIsKeptAndInNoUploadPlan() = runTest {
        val (received, checking) = lonelyCardPayment()
        assertEquals(stored(TransferDecision.Confirmed, lonely = true), decide(received, TransferChoice.Confirm))
        val card = transaction(received).bankAccountId
        assertEquals(1, eligibleOf(card))

        change(checking) { it.copy(retired = true) }
        val inRetired = stored(checking, "TX-7", oct14, amount(-120))
        compare()

        val confirmed = transferOf(received)!!
        assertEquals(TransferDecision.Confirmed, confirmed.decision)
        assertEquals(setOf(received), legsOf(confirmed))
        assertNull(transaction(inRetired).transferId)
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(0, eligibleOf(card))
        assertEquals(NotYetUploaded(0, 0), counts())
    }

    // ---- Admission, preconditions and failure postconditions ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("ADR-007 §2: a Transfer Decision is admitted while an Upload Plan awaits confirmation and raises state_version, so that the plan prepared before it can never be written unchanged, and is refused with the reason while an Upload is written")
    fun admittedWhileAPlanIsOpenAndRefusedWhileUploading() = runTest {
        val (paid, _) = plainPair()
        val version = slice.stateVersion.value

        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(TransferOutcome.Unavailable(OperationRefusal.UploadBeingWritten), decide(paid, TransferChoice.Hold))
        assertEquals(TransferDecision.Proposed, transferOf(paid)!!.decision)
        assertEquals(version, slice.stateVersion.value)

        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(stored(TransferDecision.Held), decide(paid, TransferChoice.Hold))
        assertTrue(slice.stateVersion.value > version)
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Precondition: only a Bank Transaction that a Comparison gave a Transaction Status and that is a Transfer Leg of a Transfer, or a Guessed Transfer without a proposal, can be decided on: one not compared yet, an ordinary one and one that does not exist store nothing and have no sheet")
    fun onlyAComparedTransferLegIsDecidedOn() = runTest {
        val (paid, _) = plainPair()
        val ordinary = stored(transaction(paid).bankAccountId, "TX-5", oct13, amount(-18))
        compare()
        val arrivedSince = stored(transaction(paid).bankAccountId, "TX-6", oct13, amount(-19))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == arrivedSince) it.copy(firstSeenAt = now.plus(Duration.ofMinutes(1))) else it }
        val version = slice.stateVersion.value

        assertEquals(TransferOutcome.NotATransfer, decide(ordinary, TransferChoice.Hold))
        assertEquals(TransferOutcome.NotDecidable(TransferUndecidable.NotComparedYet), decide(arrivedSince, TransferChoice.Hold))
        assertEquals(TransferOutcome.NotFound, decide(999_999, TransferChoice.Hold))
        assertNull(slice.transferDecisions.sheet(ordinary))
        assertNull(slice.transferDecisions.sheet(arrivedSince))
        assertNull(slice.transferDecisions.sheet(999_999))
        assertEquals(version, slice.stateVersion.value)
        assertEquals(TransferDecision.Proposed, slice.copyStore.transfers.single().decision)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("Failure postconditions, NFR-023: when the stored data cannot be written, the decision answers that nothing was stored instead of failing: the earlier Transfer Decision, every Transaction Status, the Funded By and state_version are unchanged and nothing is journaled")
    fun aStorageFailureStoresNothing() = runTest {
        val (paid, received) = guessedPair()
        val before = slice.copyStore.transfers.single()
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size
        slice.copyStore.failsToDecideTransfers = true

        assertEquals(TransferOutcome.NotStored, decide(paid, TransferChoice.Confirm))
        assertEquals(TransferOutcome.NotStored, decide(paid, TransferChoice.ChooseBudgetAccount(budgetAccount("Checking"), keep = true)))

        assertEquals(before, slice.copyStore.transfers.single())
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        assertNull(bankAccountOf(transaction(received).bankAccountId).fundedByBudgetAccountId)
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertFalse(slice.copyStore.pending)
        assertEquals(NotYetUploaded(0, 2), counts())
    }
}
