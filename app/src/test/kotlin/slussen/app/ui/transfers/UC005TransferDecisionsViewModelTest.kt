package slussen.app.ui.transfers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import slussen.core.operation.OperationKind
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StartedBy
import slussen.core.port.StoredSentRecord
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.transfer.TransferChoice
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * The Transfer Decisions on the Transfers of a Budget (UC-005 steps 2-6, A1-A4, A7-A10; `docs/mockups/transfers.html`,
 * `transfer-budget-account.html`): Confirm, Split, Hold back and choosing another Budget Account with "Keep this
 * answer?", the refusal of A10, a Lonely Transfer, the first Card Payment, a doubtful Transfer Leg and the locked state,
 * each with its state at the control before anything is stored and its answer there. The rules themselves are tested in
 * `UC005TransferDecisionsTest`. Every name and amount is invented (C-005).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-005: Review Transfers, the Transfer Decisions on the screen")
class UC005TransferDecisionsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)

    private fun household(): Long = runBlocking { slice.connectionStore.budgets().single { it.name == "Household" }.id }

    private fun budgetAccount(name: String): Long = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    private fun transfers(open: String? = null) = TransfersViewModel(fixture.services, household()).also { screen ->
        screen.load()
        open?.let { screen.open(scenario.transaction(it)) }
    }

    private fun TransfersViewModel.ui(): TransfersUi = state.value.ui!!

    private fun TransfersViewModel.decision(): TransferDecisionUi = ui().sheet!!.decision!!

    private fun TransferDecisionUi.line(name: String): String = lines.single { it.name == name }.text

    private fun decisionOf(name: String): TransferDecision? {
        val transferId = slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction(name) }.transferId ?: return null
        return slice.copyStore.transfers.single { it.id == transferId }.decision
    }

    private fun status(name: String): TransactionStatus? = slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction(name) }.status

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", businessRules = ["BR-014"])
    @DisplayName("Steps 2-6 (transfers.html#selected, #confirmed): a selected Guessed Transfer offers Confirm, Split, Hold back and another Budget Account, each with what it does; Confirm answers at the control, removes the flag and lowers the count; no line says that decisions come later")
    fun confirmingAGuessedTransfer() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "guessed-pay")

        val decision = screen.decision()
        assertEquals(listOf("Confirm", "Split into ordinary transactions", "Hold back"), decision.actions.map { it.title })
        assertEquals("As Slussen proposes it, Checking → Rewards Card; then eligible for a later Upload Plan as one linked transfer.", decision.actions[0].effect)
        assertEquals("Each Transfer Leg is compared as an ordinary Bank Transaction.", decision.actions[1].effect)
        assertEquals("Neither Transfer Leg is in any Upload Plan until you change it.", decision.actions[2].effect)
        assertEquals("Choose another Budget Account…", decision.choose!!.title)
        assertFalse(screen.ui().note.contains("later version"))
        assertFalse(screen.ui().sheet!!.closing.contains("no decision is needed"))

        screen.decide(TransferAction.Confirm)

        assertEquals("Transfer Decision stored: Confirmed", screen.decision().answer)
        assertEquals(TransferDecision.Confirmed, decisionOf("guessed-pay"))
        assertEquals("Your Transfer Decision", screen.decision().lines.first().name)
        assertEquals("Confirmed", screen.decision().line("Your Transfer Decision"))
        assertEquals(listOf("Split into ordinary transactions", "Hold back"), screen.decision().actions.map { it.title })
        screen.close()
        assertEquals("Transfers in Household: 3 · of them Guessed Transfers: 1", screen.ui().counts)
        val card = screen.ui().transfers.single { it.key == scenario.transaction("guessed-pay") }
        assertEquals(listOf("Card Payment", "Confirmed"), card.chips)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A1: Split Into Ordinary Transactions", businessRules = ["BR-010"])
    @DisplayName("A1 (#split): Split makes each Transfer Leg an ordinary Bank Transaction; the Transfer stays listed as split so that the decision can be changed")
    fun splittingABankTransfer() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "leg-out")

        screen.decide(TransferAction.Split)

        assertEquals("Transfer Decision stored: Split", screen.decision().answer)
        assertEquals(TransferDecision.Split, decisionOf("leg-out"))
        assertEquals(TransactionStatus.New, status("leg-out"))
        assertEquals(TransactionStatus.New, status("leg-in"))
        assertEquals(listOf("Confirm", "Hold back"), screen.decision().actions.map { it.title })
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-007"])
    @DisplayName("A2 (#held): Hold back gives both Transfer Legs Held Transfer; the Transfer shows the decision and offers to change it")
    fun holdingBackABankTransfer() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "leg-out")

        screen.decide(TransferAction.Hold)

        assertEquals(TransactionStatus.HeldTransfer, status("leg-out"))
        assertEquals(TransactionStatus.HeldTransfer, status("leg-in"))
        assertEquals("Held: neither side is in an Upload Plan until you change it", screen.decision().line("Your Transfer Decision"))
        assertEquals(listOf("Confirm", "Split into ordinary transactions"), screen.decision().actions.map { it.title })
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A3: Choose Another Budget Account", businessRules = ["BR-016"])
    @DisplayName("A3 (transfer-budget-account.html#choose-pair, #lonely-result): the Budget Accounts Slussen fetches are offered with the current proposal marked; choosing another than the proposal proposes not keeping the answer, choosing the proposal proposes keeping it, and the Operator's answer decides")
    fun choosingAnotherBudgetAccount() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "guessed-pay")

        screen.openChooser()
        val chooser = screen.decision().chooser!!
        assertEquals("Which Budget Account is the other side?", chooser.question)
        assertEquals(listOf("Checking", "Reserve Savings"), chooser.options.map { it.name })
        assertEquals("Current proposal", chooser.options.first().detail)
        assertNull(chooser.use)

        screen.selectBudgetAccount(budgetAccount("Reserve Savings"))
        assertFalse(screen.decision().chooser!!.keep)
        assertEquals("Keep Reserve Savings as the Funded By of Pinecrest Rewards Card", screen.decision().chooser!!.keepAnswer)
        assertEquals("Use Reserve Savings", screen.decision().chooser!!.use)
        screen.selectBudgetAccount(budgetAccount("Checking"))
        assertTrue(screen.decision().chooser!!.keep)
        screen.selectBudgetAccount(budgetAccount("Reserve Savings"))

        screen.useChosen()

        // The answer is at the control that asked: the sheet of the Transfer, shown through the Transfer Leg that stays.
        assertNull(screen.ui().answer)
        assertEquals(scenario.transaction("guessed-card"), screen.ui().sheet!!.let { screen.selectedLeg() })
        assertEquals("Transfer Decision stored: Budget Account Chosen · a Lonely Transfer towards Reserve Savings; it waits for a side that arrives later", screen.decision().answer)
        val stored = slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction("guessed-card") }
        assertEquals(TransferDecision.BudgetAccountChosen, slice.copyStore.transfers.single { it.id == stored.transferId }.decision)
        assertNull(slice.connectionStore.bankAccountList.single { it.id == scenario.bankAccount("Pinecrest Rewards Card") }.fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-016"])
    @DisplayName("A8 (transfers.html#first-card-payment, transfer-budget-account.html#choose): with no proposal there is nothing to confirm; the paying Budget Account is chosen with keeping it as the card's Funded By proposed, and the answer sets it")
    fun firstCardPaymentWithoutProposal() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "guessed-alone")

        assertEquals(listOf("Split into ordinary transactions", "Hold back"), screen.decision().actions.map { it.title })
        assertEquals("Choose the paying Budget Account…", screen.decision().choose!!.title)
        assertEquals("Required before this Transfer can be in an Upload Plan.", screen.decision().choose!!.effect)

        screen.openChooser()
        assertEquals("Which Budget Account paid Pinecrest Rewards Card?", screen.decision().chooser!!.question)
        screen.selectBudgetAccount(budgetAccount("Checking"))
        assertTrue(screen.decision().chooser!!.keep)
        assertEquals(
            "Used for later Card Payments; you can change it in Account Mappings.",
            screen.decision().chooser!!.keepEffect,
        )
        screen.useChosen()

        assertNull(screen.ui().answer)
        assertEquals(
            "Transfer Decision stored: Budget Account Chosen · a Lonely Transfer towards Checking; it waits for a side that arrives later · Funded By of Pinecrest Rewards Card set to Checking, recorded in the Decision Journal",
            screen.decision().answer,
        )
        assertEquals(budgetAccount("Checking"), slice.connectionStore.bankAccountList.single { it.id == scenario.bankAccount("Pinecrest Rewards Card") }.fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A10: Other Side Already Decided or Uploaded", businessRules = ["BR-016"])
    @DisplayName("A10 (transfer-budget-account.html#refused): a choice whose only candidate is a Transfer Leg of a Transfer the Operator confirmed is refused at the control with that Transfer named and what can be done; nothing is stored, and Show that Transfer opens it")
    fun aChoiceIsRefusedWhenTheOtherSideIsDecided() {
        runBlocking {
            scenario.compareEveryStatus()
            slice.transferDecisions.decide(scenario.transaction("leg-out"), TransferChoice.Confirm)
            scenario.store("card-500", scenario.bankAccount("Pinecrest Rewards Card"), LocalDate.of(2026, 10, 12), amount(500), "AUTOPAY RECEIVED")
            slice.comparison.readAgain()
        }
        val screen = transfers(open = "card-500")
        screen.openChooser()
        screen.selectBudgetAccount(budgetAccount("Checking"))

        screen.useChosen()

        val refused = screen.decision().chooser!!.refused!!
        assertEquals("Not stored: Checking cannot be the other side now", refused.title)
        assertEquals(
            "The Bank Transaction of 2026-10-11, −500.00, is a Transfer Leg of the Transfer Checking → Reserve Savings, 500.00, 2026-10-11, " +
                "which you confirmed. Change that Transfer Decision first, choose another Budget Account, or split or hold back this Transfer. " +
                "No Funded By was set.",
            refused.text,
        )
        assertEquals(TransactionStatus.GuessedTransfer, status("card-500"))
        assertNull(slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction("card-500") }.transferId)

        screen.open(refused.showTransferLeg!!)
        assertEquals("Confirmed", screen.decision().line("Your Transfer Decision"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005"])
    @DisplayName("A4, BR-005 (#lonely): a Lonely Transfer can be confirmed into a later Upload Plan as a linked transfer, and says that, once decided, an arriving other side joins it, also one Slussen had proposed elsewhere")
    fun aLonelyTransferIsConfirmed() {
        runBlocking { scenario.compareEveryStatus(withLonelyTransfer = true) }
        val screen = transfers(open = "lonely-in")

        assertEquals("Into a later Upload Plan as a linked transfer whose other side stays uncleared until it arrives.", screen.decision().actions.first().effect)
        assertEquals(
            TransfersTexts.LONELY_JOINS,
            screen.decision().line("Its other side"),
        )

        screen.decide(TransferAction.Confirm)

        assertEquals("Transfer Decision stored: Confirmed · a Lonely Transfer towards Checking; it waits for a side that arrives later", screen.decision().answer)
        assertEquals(TransferDecision.Confirmed, decisionOf("lonely-in"))
        // S-B-03: a decided Lonely Transfer stays one, with no date by which it must arrive, and says that its other side joins it.
        screen.close()
        val card = screen.ui().transfers.single { it.key == scenario.transaction("lonely-in") }
        assertEquals(listOf("Bank Transfer", "Lonely Transfer", "Confirmed"), card.chips)
        assertEquals("Checking −120.00 · not arrived; it joins this Transfer when it arrives", card.sides.last())
        assertTrue(card.state.startsWith("one side arrived"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A9: Transfer With a Doubtful Transfer Leg", businessRules = ["BR-013"])
    @DisplayName("A9 (#doubtful-leg): a Transfer whose Transfer Leg waits for a Review Decision says why, leads to that Transfer Leg's Review Decision and offers only Split")
    fun aDoubtfulTransferLegLeadsToItsReviewDecision() {
        runBlocking {
            scenario.compareEveryStatus()
            scenario.store("doubtful-out", scenario.bankAccount("Kestrel Checking"), LocalDate.of(2026, 10, 12), amount(-60), "INTERNET TRANSFER TO RESERVE", reference = null)
            scenario.store("doubtful-in", scenario.bankAccount("Kestrel Reserve"), LocalDate.of(2026, 10, 12), amount(60), "INTERNET TRANSFER FROM CHECKING")
            slice.comparison.readAgain()
        }
        val screen = transfers(open = "doubtful-in")

        val decision = screen.decision()
        assertEquals(
            "Waits for your Review Decision on its Kestrel Checking side, which is Needs Review: No Stable Reference, so Slussen cannot recognise " +
                "it again for sure. Decide on that Transfer Leg, or split the Transfer.",
            decision.line("Waits"),
        )
        assertEquals(listOf("Split into ordinary transactions"), decision.actions.map { it.title })
        assertNull(decision.choose)
        assertEquals(scenario.transaction("doubtful-out"), decision.reviewLeg!!.storedBankTransaction)
        assertEquals(scenario.bankAccount("Kestrel Checking"), decision.reviewLeg!!.bankAccountId)
        assertEquals("Decide on the Transfer Leg", decision.reviewLeg!!.title)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A7: Transfer Already Uploaded", businessRules = ["BR-008"])
    @DisplayName("A7, BR-008 (#locked, with a planted Sent Record): a Transfer with an uploaded Transfer Leg shows its Transfer Decision as locked, offers no choice and says what can be done instead")
    fun aLockedTransferOffersNoChoice() {
        runBlocking {
            scenario.compareEveryStatus()
            val checking = slice.connectionStore.bankAccountList.single { it.id == scenario.bankAccount("Kestrel Checking") }
            slice.copyStore.sentRecords += StoredSentRecord(scenario.transaction("leg-out"), checking.mapping!!.budgetAccountId, sentAsTransfer = true, SentRecordOutcome.Written)
        }
        val decision = transfers(open = "leg-out").decision()

        assertEquals(
            "A Transfer Leg of it has been uploaded, so its Transfer Decision can no longer be changed. Correct it in Example Budget App if it is wrong.",
            decision.line("Locked"),
        )
        assertTrue(decision.actions.isEmpty())
        assertNull(decision.choose)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By", businessRules = ["BR-002", "BR-014"])
    @DisplayName("A8, BR-014 (S-B-15, #funded-by-not-applying): a card whose Funded By no longer applies is never said to have none: the Transfer names it, says that it no longer applies and leads to Account Mappings to change or clear it")
    fun aFundedByThatNoLongerAppliesIsNamed() {
        runBlocking {
            scenario.compareEveryStatus()
            val current = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Current" }.id
            slice.connectionStore.link(scenario.bankAccount("Pinecrest Rewards Card"), fundedBy = current)
            slice.comparison.readAgain()
        }
        val screen = transfers()

        val card = screen.ui().transfers.single { it.key == scenario.transaction("guessed-alone") }
        assertEquals("Paying side not known: the Funded By Current no longer applies · choose the paying Budget Account", card.sides.last())
        screen.open(card.key)
        assertEquals(
            "The Funded By of Pinecrest Rewards Card, Current, no longer applies, so Slussen proposes no paying side. Change or clear it in Account Mappings.",
            screen.ui().sheet!!.details.single { it.name == "Why not certain" }.text,
        )
        assertEquals("Change or clear the Funded By…", screen.decision().changeFundedBy)
        // The card's own Bank Account screen says the same, and names where it is changed.
        val cardScreen = slussen.app.ui.bankaccount.BankAccountViewModel(fixture.services, scenario.bankAccount("Pinecrest Rewards Card")).also { it.load() }
        val bankAccount = cardScreen.state.value.ui!!
        assertEquals(
            "Card Payment; its paying side is not known, since its Funded By Current no longer applies",
            bankAccount.weeks.flatMap { it.lines }.single { it.key.id == scenario.transaction("guessed-alone") }.reason,
        )
        assertEquals("Funded By: Current · no longer applies · change or clear it in Account Mappings", bankAccount.otherSide)
    }

    @Test
    @UseCase(id = "UC-005", requirements = ["NFR-023"])
    @DisplayName("NFR-023, failure postconditions: a Transfer Decision that cannot be stored is answered at its control and changes nothing; from the tap on the control shows what it waits for and takes no second tap")
    fun aDecisionThatCannotBeStoredChangesNothing() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "leg-out")
        slice.copyStore.failsToDecideTransfers = true

        screen.decide(TransferAction.Hold)

        assertEquals("Not stored: Slussen could not use the data stored on this device; nothing was changed · try again", screen.decision().answer)
        assertEquals(TransferDecision.Proposed, decisionOf("leg-out"))
        assertTrue(screen.decision().enabled)

        slice.copyStore.failsToDecideTransfers = false
        val gate = CompletableDeferred<Unit>()
        val reading = CoroutineScope(Dispatchers.Unconfined).launch {
            slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { gate.await() }
        }
        screen.decide(TransferAction.Hold)
        assertEquals("Hold back · waits for: Reading the Budget App", screen.decision().pending)
        assertFalse(screen.decision().enabled)
        screen.decide(TransferAction.Split)
        gate.complete(Unit)
        runBlocking { reading.join() }

        assertNull(screen.decision().pending)
        assertEquals(TransferDecision.Held, decisionOf("leg-out"))
    }

    private fun holdTheLane(): Pair<CompletableDeferred<Unit>, kotlinx.coroutines.Job> {
        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { gate.await() } }
        return gate to job
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A2: Hold the Transfer Back", businessRules = ["BR-011"])
    @DisplayName("NFR-023 (review B6 F1): while a Transfer Decision is on its way its Transfer cannot be closed and no other opens, and it says why; the answer lands on its own Transfer only, also when the Operator left the screen meanwhile and comes back")
    fun aTransferIsNotLeftWhileItsDecisionIsOnItsWay() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "leg-out")
        val (gate, job) = holdTheLane()

        screen.decide(TransferAction.Hold)
        screen.close()
        screen.open(scenario.transaction("guessed-pay"))

        assertEquals(scenario.transaction("leg-out"), screen.selectedLeg())
        assertEquals(TransfersTexts.STAYS_OPEN, screen.decision().line("While it is stored"))
        assertFalse(screen.ui().sheet!!.canLeave)
        gate.complete(Unit)
        runBlocking { job.join() }
        assertEquals("Transfer Decision stored: Held", screen.decision().answer)
        assertEquals(TransferDecision.Proposed, decisionOf("guessed-pay"))

        val (gate2, job2) = holdTheLane()
        screen.decide(TransferAction.Split)
        screen.left()
        gate2.complete(Unit)
        runBlocking { job2.join() }
        screen.load()
        assertEquals(scenario.transaction("leg-out"), screen.selectedLeg())
        assertEquals("Transfer Decision stored: Split", screen.decision().answer)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A9: Transfer With a Doubtful Transfer Leg", businessRules = ["BR-013"])
    @DisplayName("Review B6 F7: coming back to the Transfers from where a Transfer led (a Transfer Leg's Review Decision, Account Mappings) shows that Transfer again, not the list")
    fun comingBackShowsTheTransferAgain() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers(open = "guessed-pay")

        screen.load(selectLeg = null, returning = true)

        assertEquals(scenario.transaction("guessed-pay"), screen.selectedLeg())
        assertTrue(screen.ui().sheet!!.decision != null)
        screen.load(selectLeg = null)
        assertNull(screen.ui().sheet)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer", businessRules = ["BR-005"])
    @DisplayName("BR-005, G-24 (review B6 F3): confirming a Lonely Transfer whose other side has arrived in a Transfer Slussen only proposed pairs it at once; the answer says so and that the proposed Transfer was dissolved")
    fun confirmingALonelyTransferPairsItWithAnArrivedSide() {
        runBlocking {
            scenario.compareEveryStatus(withLonelyTransfer = true)
            // Pinecrest Rewards Card is funded by Checking, so out-120 is proposed as the card's payment, not as the Lonely Transfer's other side.
            slice.connectionStore.link(scenario.bankAccount("Pinecrest Rewards Card"), fundedBy = budgetAccount("Checking"))
            scenario.store("card-120", scenario.bankAccount("Pinecrest Rewards Card"), LocalDate.of(2026, 10, 14), amount(120), "AUTOPAY RECEIVED")
            scenario.store("out-120", scenario.bankAccount("Kestrel Checking"), LocalDate.of(2026, 10, 14), amount(-120), "PINECREST AUTOPAY")
            slice.comparison.readAgain()
        }
        fun transferOf(key: String) = slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction(key) }.transferId
        val lonely = transferOf("lonely-in")
        assertTrue(transferOf("out-120") != null && transferOf("out-120") != lonely)
        val screen = transfers(open = "lonely-in")

        screen.decide(TransferAction.Confirm)

        assertEquals(lonely, transferOf("out-120"))
        assertEquals(
            "Transfer Decision stored: Confirmed · paired at once with Kestrel Checking 2026-10-14 −120.00, which had arrived; " +
                "the Transfer Slussen had proposed for it was dissolved",
            screen.decision().answer,
        )
        assertEquals(2, screen.ui().sheet!!.details.count { it.name.endsWith("side") && it.name != "Expected other side" && it.name != "Its other side" })
        assertTrue(screen.decision().lines.none { it.name == "Its other side" })
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A11: Budget Owes a Read of Its Read Period", requirements = ["NFR-023"])
    @DisplayName("A11 (UC-003 BR-016): while the Budget owes a read of its read period, a Transfer Decision is refused at its control with the reason and the read of the Budget App as the next action; nothing is stored")
    fun aDecisionIsRefusedWhileTheBudgetOwesARead() {
        runBlocking { scenario.compareEveryStatus() }
        runBlocking { slice.copyStore.owePeriodRead(household()) }
        val screen = transfers(open = "leg-out")
        val before = status("leg-out")

        screen.decide(TransferAction.Hold)

        assertEquals(
            "Not stored: its Budget owes a read of the Budget App: Slussen's copy does not cover the whole read period. Read the " +
                "Budget App again on the Budget's tab or the Overview; then it can be decided.",
            screen.decision().answer,
        )
        assertEquals(before, status("leg-out"))
    }
}
