package slussen.app.ui.bankaccount

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
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.overview.OverviewViewModel
import slussen.core.operation.OperationKind
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.ReviewChoice
import slussen.core.port.StartedBy
import slussen.core.port.TransactionStatus
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.TransferDecision
import slussen.core.transfer.TransferChoice
import slussen.core.status.TransactionKey
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * The Review Decision sheet on a Bank Transaction of the Bank Account screen (UC-004; `docs/mockups/review-decision.html`):
 * the choices its Transaction Status offers, Already Present with the Budget App Transaction it names, change and clear,
 * Re-upload, the locked state and an Unreadable Transaction, each with its state at the control before anything is
 * stored and its answer there. The rules themselves are tested in `UC004ReviewDoubtfulTransactionsTest`. Every name and
 * amount is invented (C-005).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-004: Review Doubtful Transactions, the Review Decision sheet")
class UC004ReviewDoubtfulTransactionsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)

    private fun compared() = runBlocking { scenario.compareEveryStatus() }

    private fun checking(open: String? = null): BankAccountViewModel =
        BankAccountViewModel(fixture.services, scenario.bankAccount("Kestrel Checking")).also { screen ->
            screen.load()
            open?.let { screen.open(key(it)) }
        }

    private fun key(name: String) = TransactionKey(unreadable = false, id = scenario.transaction(name))

    private fun BankAccountViewModel.review(): ReviewUi = state.value.ui!!.sheet!!.review!!

    private fun ReviewUi.line(name: String): String = lines.single { it.name == name }.text

    private fun BankAccountViewModel.statusOf(name: String): String =
        state.value.ui!!.weeks.flatMap { it.lines }.single { it.key == key(name) }.status

    private fun stored(name: String): TransactionStatus? = slice.importStatusStore.bankTransactions.single { it.id == scenario.transaction(name) }.status

    private fun decisionsJournaled(): Int = slice.journalRecorder.entries.count { it.eventKind == JournalEventKind.OperatorDecision }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("Steps 1-2 (review-decision.html#possible-import): a Possible Import By Other Path shows why it is doubtful and the three choices of BR-001, each with what it does; no line says that decisions come later")
    fun possibleImportOffersItsThreeChoices() {
        compared()

        val screen = checking(open = "possible")
        val review = screen.review()

        assertEquals("Resembles an Import by another importer in Checking; not proof of a duplicate", review.line("Why it is doubtful"))
        assertEquals(listOf("Already Present…", "Import Anyway", "Defer"), review.choices.map { it.title })
        assertEquals("Pick the same Budget App Transaction; it is then Already In Budget App and never sent.", review.choices[0].effect)
        assertEquals("Eligible for a later Upload Plan as a new transaction.", review.choices[1].effect)
        assertEquals("Held back, Deferred, until you change it.", review.choices[2].effect)
        assertNull(review.clear)
        assertNull(review.reUpload)
        assertNull(review.answer)
        assertTrue(review.enabled)
        val sheet = screen.state.value.ui!!.sheet!!
        assertTrue(sheet.details.none { it.name == "Later" })
        assertFalse(sheet.closing.contains("no decision is needed"))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A3: Exactly One Resembling Transaction", businessRules = ["BR-002", "BR-005", "BR-006"])
    @DisplayName("A3, steps 6-8 (#one-resembling, #decided): with exactly one resembling Import, Already Present shows it before anything is stored; Store Already Present stores it, answers at the control with the status that follows, shows it in the list and records it in the Decision Journal")
    fun alreadyPresentWithTheOnlyResemblingImport() {
        compared()
        val screen = checking(open = "possible")
        val journaled = decisionsJournaled()

        screen.choose(ReviewChoice.AlreadyPresent)

        val chooser = screen.review().chooser!!
        assertEquals("The only resembling Import:", chooser.question)
        assertEquals(listOf("Riverside Apoth. · −18.60 · 2026-10-14 · Budget Account Checking · Other Import Path"), chooser.options.map { it.text })
        assertEquals("Store Already Present", chooser.confirm)
        assertEquals(TransactionStatus.PossibleImportByOtherPath, stored("possible"))

        screen.chooseTransaction(chooser.options.single().reference)

        assertEquals("Review Decision stored: Already Present · Transaction Status now Already In Budget App", screen.review().answer)
        assertNull(screen.review().chooser)
        assertEquals("Already In Budget App", screen.statusOf("possible"))
        assertEquals(TransactionStatus.AlreadyInBudgetApp, stored("possible"))
        assertEquals(journaled + 1, decisionsJournaled())
        assertTrue(screen.review().line("Current Review Decision").startsWith("Already Present, made "))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Steps 3-5 (#choose-resembling): with several resembling Imports the Operator picks the one that is the same from a list with date, payee, amount and Budget Account; Cancel stores nothing")
    fun alreadyPresentNamesOneOfSeveral() {
        compared()
        runBlocking {
            scenario.answerHousehold(
                listOf(scenario.read("y-possible-2", LocalDate.of(2026, 10, 13), amount(-18, 600), "RIVERSIDE APOTH", importIdentifier = "BANK:-18600:2026-10-13:1")),
            )
            slice.comparison.readAgain()
        }
        val screen = checking(open = "possible")

        screen.choose(ReviewChoice.AlreadyPresent)
        val chooser = screen.review().chooser!!
        assertEquals("Which Budget App Transaction is the same?", chooser.question)
        assertEquals(2, chooser.options.size)
        assertEquals("This one is the same", chooser.confirm)
        screen.cancelChoosing()
        assertNull(screen.review().chooser)
        assertEquals(TransactionStatus.PossibleImportByOtherPath, stored("possible"))

        screen.choose(ReviewChoice.AlreadyPresent)
        val second = screen.review().chooser!!.options.single { it.text.startsWith("RIVERSIDE APOTH") }
        screen.chooseTransaction(second.reference)

        assertEquals(TransactionStatus.AlreadyInBudgetApp, stored("possible"))
        assertEquals("y-possible-2", slice.copyStore.reviewDecisions.single().chosen!!.budgetAppReference)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A4: Transaction Needs Review or Its Transfer Counterpart Changed", businessRules = ["BR-001", "BR-006"])
    @DisplayName("A4, A1 (#needs-review): a Needs Review without a Stable Reference says why and offers Import Anyway and Defer only; Import Anyway makes it New")
    fun needsReviewOffersImportAnywayAndDefer() {
        compared()
        val screen = checking(open = "no-reference-1")

        assertEquals(
            "No Stable Reference: same date, amount and text as 1 other, so Slussen cannot tell them apart",
            screen.review().line("Why it is doubtful"),
        )
        assertEquals(listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer), screen.review().choices.map { it.choice })

        screen.choose(ReviewChoice.ImportAnyway)

        assertEquals("Review Decision stored: Import Anyway · Transaction Status now New", screen.review().answer)
        assertEquals(TransactionStatus.New, stored("no-reference-1"))
        assertEquals("New", screen.statusOf("no-reference-1"))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-001", "BR-006"])
    @DisplayName("A2, A6 (#change): a Deferred Bank Transaction shows its current Review Decision and the choices of the status it has without it, with clearing, which gives the status back")
    fun aDecisionIsChangedAndCleared() {
        compared()
        val screen = checking(open = "unsupported")
        screen.choose(ReviewChoice.Defer)
        assertEquals(TransactionStatus.Deferred, stored("unsupported"))

        screen.open(key("unsupported"))
        val review = screen.review()
        assertTrue(review.line("Current Review Decision").startsWith("Defer, made "))
        assertEquals(listOf(ReviewChoice.ImportAnyway), review.choices.map { it.choice })
        assertEquals("Replace the decision: eligible for a later Upload Plan as a new transaction.", review.choices.single().effect)
        assertEquals("Clear the decision", review.clear!!.title)
        assertEquals("Back to Slussen's status: Needs Review.", review.clear!!.effect)
        assertEquals("Held back by your Review Decision until you change it", screen.state.value.ui!!.weeks.flatMap { it.lines }.single { it.key == key("unsupported") }.reason)

        screen.clearDecision()

        assertEquals("Review Decision cleared · Transaction Status now Needs Review", screen.review().answer)
        assertEquals(TransactionStatus.NeedsReview, stored("unsupported"))
        assertNull(screen.review().clear)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A5: Re-upload a Deleted Transaction", businessRules = ["BR-003"])
    @DisplayName("A5 (#reupload): a Deleted In Budget App Bank Transaction offers Re-upload, off; switched on it is New, switched off again it is Deleted In Budget App")
    fun reUploadIsSwitchedOnAndOff() {
        compared()
        val screen = checking(open = "deleted")

        val off = screen.review().reUpload!!
        assertFalse(off.on)
        assertEquals("Re-upload · off; switch it on to send it again as a new transaction, inside the Upload Window.", off.text)
        assertTrue(screen.review().choices.isEmpty())

        screen.switchReUpload(on = true)
        assertEquals("Review Decision stored: Re-upload · Transaction Status now New", screen.review().answer)
        assertTrue(screen.review().reUpload!!.on)
        assertEquals(TransactionStatus.New, stored("deleted"))

        screen.switchReUpload(on = false)
        assertEquals("Review Decision cleared · Transaction Status now Deleted In Budget App", screen.review().answer)
        assertEquals(TransactionStatus.DeletedInBudgetApp, stored("deleted"))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A7: Transaction Already Uploaded", businessRules = ["BR-004"])
    @DisplayName("A7, BR-004 (#locked): a Bank Transaction whose own Import Identifier the Budget App holds shows its Review Decision as locked, offers no choice and no clearing, and says what can be done instead")
    fun aLockedDecisionOffersNoChoice() {
        compared()
        runBlocking {
            slice.review.decide(scenario.transaction("deleted"), ReviewChoice.ReUpload)
            scenario.answerHousehold(
                listOf(scenario.read("y-deleted", LocalDate.of(2026, 10, 12), amount(-23, 500), "Book Nook", importIdentifier = scenario.identifier("deleted"), state = ReadState.Present)),
            )
            slice.comparison.readAgain()
        }

        val review = checking(open = "deleted").review()

        assertEquals(
            "Locked: Example Budget App holds this Bank Transaction under its own Import Identifier, so its Review Decision can no longer be changed " +
                "or cleared. If it should not be there, change or delete it in Example Budget App; once it is deleted there, it can be re-uploaded here.",
            review.line("Locked"),
        )
        assertTrue(review.line("Current Review Decision").startsWith("Re-upload, made "))
        assertTrue(review.choices.isEmpty())
        assertNull(review.clear)
        assertNull(review.reUpload)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A9: Unreadable Transaction", businessRules = ["BR-007"])
    @DisplayName("A9, BR-007 (#unreadable): an Unreadable Transaction offers Defer only; deferring takes it out of those waiting for a decision, and clearing it brings it back to Needs Review")
    fun anUnreadableTransactionIsDeferredOnly() {
        compared()
        val screen = checking()
        screen.open(TransactionKey(unreadable = true, id = 4900))

        assertEquals(listOf("Defer"), screen.review().choices.map { it.title })
        assertEquals("Never uploaded: Slussen could not read what to send. If you want it in Example Budget App, type it in there.", screen.review().line("Uploaded"))
        screen.choose(ReviewChoice.Defer)

        assertEquals("Review Decision stored: Defer · now Deferred", screen.review().answer)
        assertEquals(UnreadableTransactionStatus.Deferred, slice.importStatusStore.unreadableTransactions.single().status)
        assertTrue(screen.review().choices.isEmpty())
        screen.clearDecision()
        assertEquals("Review Decision cleared · now Needs Review", screen.review().answer)
        assertEquals(UnreadableTransactionStatus.NeedsReview, slice.importStatusStore.unreadableTransactions.single().status)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A11: Doubtful Transfer Leg", businessRules = ["BR-009"])
    @DisplayName("A11, BR-009 (#transfer-leg): a doubtful Transfer Leg also shows its Transfer with the other Transfer Leg, says that the decision decides the whole Transfer and what each choice does to it")
    fun aDoubtfulTransferLegDecidesItsTransfer() {
        compared()
        runBlocking {
            scenario.store("doubtful-out", scenario.bankAccount("Kestrel Checking"), LocalDate.of(2026, 10, 12), amount(-60), "INTERNET TRANSFER TO RESERVE", reference = null)
            scenario.store("doubtful-in", scenario.bankAccount("Kestrel Reserve"), LocalDate.of(2026, 10, 12), amount(60), "INTERNET TRANSFER FROM CHECKING")
            slice.comparison.readAgain()
        }
        val screen = checking(open = "doubtful-out")
        val review = screen.review()

        assertEquals("Bank Transfer Checking → Reserve Savings, 60.00. Other Transfer Leg: Kestrel Reserve, 2026-10-12, +60.00.", review.line("Its Transfer"))
        assertEquals("This decision decides the whole Transfer.", review.line("Decides"))
        assertEquals(
            listOf("This Transfer Leg rejoins its Transfer, which is then eligible as one linked transfer.", "The whole Transfer is held back until you change it."),
            review.choices.map { it.effect },
        )

        screen.choose(ReviewChoice.Defer)

        assertEquals(TransactionStatus.Deferred, stored("doubtful-out"))
        assertEquals(TransactionStatus.Deferred, stored("doubtful-in"))
    }

    @Test
    @UseCase(id = "UC-004")
    @DisplayName("NFR-023, failure postconditions: a decision that cannot be stored is answered at its control, changes nothing and leaves the choices for another try; a sheet that cannot be read says so")
    fun aDecisionThatCannotBeStoredChangesNothing() {
        compared()
        val screen = checking(open = "possible")
        slice.copyStore.failsToDecide = true

        screen.choose(ReviewChoice.ImportAnyway)

        assertEquals("Not stored: Slussen could not use the data stored on this device; nothing was changed · try again", screen.review().answer)
        assertEquals(TransactionStatus.PossibleImportByOtherPath, stored("possible"))
        assertTrue(screen.review().enabled)
        assertEquals(3, screen.review().choices.size)

        slice.copyStore.failsToDecide = false
        slice.copyStore.failsToRead = true
        screen.open(key("no-reference-1"))
        assertEquals(ReviewDecisionTexts.NOT_READ, screen.state.value.ui!!.sheet!!.reviewNotRead)
        assertNull(screen.state.value.ui!!.sheet!!.review)
    }

    @Test
    @UseCase(id = "UC-004")
    @DisplayName("NFR-023, ADR-007 §1: from the tap on, before anything is stored, the control shows what it waits for and takes no second tap; then its answer; while an Upload is written it answers why it cannot run")
    fun theControlShowsItsStateFirst() {
        compared()
        val screen = checking(open = "possible")
        val gate = CompletableDeferred<Unit>()
        val reading = CoroutineScope(Dispatchers.Unconfined).launch {
            slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { gate.await() }
        }

        screen.choose(ReviewChoice.ImportAnyway)

        assertEquals("Import Anyway · waits for: Reading the Budget App", screen.review().pending)
        assertFalse(screen.review().enabled)
        screen.choose(ReviewChoice.Defer)
        gate.complete(Unit)
        runBlocking { reading.join() }

        assertNull(screen.review().pending)
        assertEquals("Review Decision stored: Import Anyway · Transaction Status now New", screen.review().answer)
        assertEquals(TransactionStatus.New, stored("possible"))

        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        screen.choose(ReviewChoice.Defer)
        assertEquals("Not stored: unavailable while an Upload is being written.", screen.review().answer)
        assertEquals(TransactionStatus.New, stored("possible"))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Step 7 (#decided): the counts that wait for a decision lead to where it is taken: the Overview to the Budget, the Budget tab to its Bank Accounts and Transfers, a Bank Account card to its own screen; a decision lowers them")
    fun theCountsLeadToWhereTheDecisionIsTaken() {
        compared()
        val household = runBlocking { slice.connectionStore.budgets().single { it.name == "Household" }.id }
        fun tab() = BudgetViewModel(fixture.services, household).also { it.load() }.state.value!!

        assertEquals(
            "8 transactions wait for your decision: open a Bank Account below to decide on its own, and the Transfers for the Guessed Transfers.",
            tab().decide,
        )
        // Review B6 F8: a card counts only what its Bank Account screen decides, and leads to the Transfers for its Guessed Transfers.
        assertEquals("Decide on the 5 waiting for your Review Decision ›", tab().bankAccounts.single { it.name == "Kestrel Checking" }.opens)
        assertEquals("1 in a Guessed Transfer waits for your Transfer Decision: open the Transfers ›", tab().bankAccounts.single { it.name == "Kestrel Checking" }.inTransfers)
        assertEquals("Bank Transactions and their statuses ›", tab().bankAccounts.single { it.name == "Pinecrest Rewards Card" }.opens)
        assertEquals("2 in Guessed Transfers wait for your Transfer Decision: open the Transfers ›", tab().bankAccounts.single { it.name == "Pinecrest Rewards Card" }.inTransfers)
        assertEquals("Bank Transactions and their statuses ›", tab().bankAccounts.single { it.name == "Kestrel Reserve" }.opens)
        assertNull(tab().bankAccounts.single { it.name == "Kestrel Reserve" }.inTransfers)
        val overview = OverviewViewModel(fixture.services).also { it.load() }
        assertEquals("Waiting for your decision: open Household to decide.", overview.state.value!!.budgets.single { it.name == "Household" }.decide)

        checking(open = "possible").choose(ReviewChoice.ImportAnyway)

        assertEquals("Decide on the 4 waiting for your Review Decision ›", tab().bankAccounts.single { it.name == "Kestrel Checking" }.opens)
    }

    private fun holdTheLane(): Pair<CompletableDeferred<Unit>, kotlinx.coroutines.Job> {
        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { slice.coordinator.run(OperationKind.Comparison, StartedBy.Operator) { gate.await() } }
        return gate to job
    }

    private fun BankAccountViewModel.openTitle(): String? = state.value.ui!!.sheet?.title

    @Test
    @UseCase(id = "UC-004", scenario = "A6: Change or Clear an Earlier Decision", businessRules = ["BR-005"])
    @DisplayName("NFR-023 (review B6 F1): while a decision is on its way its sheet cannot be closed and no other line opens, and it says why; its answer lands on its own line only, also when the Operator left the screen meanwhile and comes back")
    fun aSheetIsNotLeftWhileItsDecisionIsOnItsWay() {
        compared()
        val screen = checking(open = "possible")
        val possibleTitle = screen.openTitle()
        val (gate, job) = holdTheLane()

        screen.choose(ReviewChoice.ImportAnyway)
        screen.close()
        screen.open(key("no-reference-1"))

        assertEquals(possibleTitle, screen.openTitle())
        assertEquals(ReviewDecisionTexts.STAYS_OPEN, screen.review().line("While it is stored"))
        assertFalse(screen.state.value.ui!!.sheet!!.canLeave)
        gate.complete(Unit)
        runBlocking { job.join() }
        assertEquals("Review Decision stored: Import Anyway · Transaction Status now New", screen.review().answer)
        assertTrue(screen.state.value.ui!!.sheet!!.canLeave)
        assertEquals(TransactionStatus.NeedsReview, stored("no-reference-1"))

        // Left while the decision runs, the screen comes back to that line with its answer.
        screen.open(key("no-reference-1"))
        val noReference = screen.openTitle()
        val (gate2, job2) = holdTheLane()
        screen.choose(ReviewChoice.Defer)
        screen.left()
        gate2.complete(Unit)
        runBlocking { job2.join() }
        screen.load()
        assertEquals(noReference, screen.openTitle())
        assertEquals("Review Decision stored: Defer · Transaction Status now Deferred", screen.review().answer)
        // Coming back after a call that had ended before leaving opens the list, as before.
        screen.left()
        screen.load()
        assertNull(screen.openTitle())
    }

    /** Kestrel Checking −500.00 and Kestrel Reserve +500.00, the Bank Transfer of the scenario, held back by the Operator. */
    private fun heldBankTransfer() {
        compared()
        runBlocking { slice.transferDecisions.decide(scenario.transaction("leg-out"), TransferChoice.Hold) }
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A11: Doubtful Transfer Leg", businessRules = ["BR-009"])
    @DisplayName("BR-009, S-B-17 (review B6 F2): a doubtful Transfer Leg of a held Transfer offers no Re-upload, and its choices say that it stays in no Upload Plan while the Transfer is held back")
    fun aTransferLegOfAHeldTransferReleasesNothing() {
        heldBankTransfer()
        runBlocking {
            scenario.answerHousehold(listOf(scenario.read("y-out", LocalDate.of(2026, 10, 11), amount(-500), "Transfer out", importIdentifier = "BANK:-500000:2026-10-11:1")))
            slice.comparison.readAgain()
        }
        val review = checking(open = "leg-out").review()

        assertEquals(TransactionStatus.PossibleImportByOtherPath, stored("leg-out"))
        assertEquals(listOf(ReviewChoice.AlreadyPresent, ReviewChoice.ImportAnyway, ReviewChoice.Defer), review.choices.map { it.choice })
        assertNull(review.reUpload)
        assertEquals(
            "This Transfer Leg stays in no Upload Plan while you hold its Transfer back; this decision applies once you change that Transfer Decision.",
            review.choices.single { it.choice == ReviewChoice.ImportAnyway }.effect,
        )
        assertTrue(review.choices.none { "eligible" in it.effect || "rejoins" in it.effect })
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A5: Re-upload a Deleted Transaction", businessRules = ["BR-009"])
    @DisplayName("BR-009 (review B6 F2): a Transfer Leg that is Deleted In Budget App offers Re-upload as a new transaction of its own Budget Account, not as a linked transfer; once its Transfer is held back, a Re-upload it has is not shown as on and nothing is offered but clearing")
    fun aDeletedTransferLegIsReUploadedAsANewTransaction() {
        compared()
        runBlocking {
            scenario.answerHousehold(
                listOf(scenario.read("y-out", LocalDate.of(2026, 10, 11), amount(-500), "Transfer out", importIdentifier = scenario.identifier("leg-out"), state = ReadState.Deleted)),
            )
            slice.comparison.readAgain()
        }
        val screen = checking(open = "leg-out")

        assertEquals(TransactionStatus.DeletedInBudgetApp, stored("leg-out"))
        assertEquals(
            "Re-upload · off; switch it on to send it again as a new transaction of its own Budget Account, not as a linked transfer, inside the Upload Window.",
            screen.review().reUpload!!.text,
        )
        screen.switchReUpload(on = true)
        assertEquals(TransactionStatus.New, stored("leg-out"))

        runBlocking { slice.transferDecisions.decide(scenario.transaction("leg-in"), TransferChoice.Hold) }
        screen.open(key("leg-out"))
        val held = screen.review()
        assertEquals(TransferDecision.Held, slice.copyStore.transfers.single { it.id == slice.importStatusStore.bankTransactions.single { t -> t.id == scenario.transaction("leg-out") }.transferId }.decision)
        assertNull(held.reUpload)
        assertTrue(held.choices.isEmpty())
        assertEquals(
            "Kept, but it sends nothing while you hold its Transfer back; change that Transfer Decision first.",
            held.line("Re-upload"),
        )
        assertTrue(held.clear != null)
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A5: Re-upload a Deleted Transaction", businessRules = ["BR-001", "BR-002"])
    @DisplayName("UC-003 A4, BR-001 (#no-longer-holds): an Already Present decision whose Budget App Transaction was deleted is Decision No Longer Holds, says why, and offers Import Anyway, Defer and Re-upload, and Already Present only while an Import still resembles it")
    fun aDecisionThatNoLongerHolds() {
        compared()
        val screen = checking(open = "possible")
        screen.choose(ReviewChoice.AlreadyPresent)
        screen.chooseTransaction(screen.review().chooser!!.options.single().reference)
        runBlocking {
            scenario.answerHousehold(
                listOf(scenario.read("y-possible", LocalDate.of(2026, 10, 14), amount(-18, 600), "Riverside Apoth.", importIdentifier = "BANK:-18600:2026-10-14:1", state = ReadState.Deleted)),
            )
            slice.comparison.readAgain()
        }

        screen.open(key("possible"))
        val review = screen.review()

        assertEquals(TransactionStatus.DecisionNoLongerHolds, stored("possible"))
        assertEquals(
            "The chosen Budget App Transaction is no longer in Example Budget App: it was deleted, merged into another one or is not read any more.",
            review.line("No longer holds"),
        )
        assertEquals(listOf(ReviewChoice.ImportAnyway, ReviewChoice.Defer), review.choices.map { it.choice })
        assertFalse(review.reUpload!!.on)
        screen.choose(ReviewChoice.Defer)
        assertEquals(TransactionStatus.Deferred, stored("possible"))
    }

    @Test
    @UseCase(id = "UC-004", scenario = "A7: Transaction Already Uploaded", businessRules = ["BR-001", "BR-002", "BR-004"])
    @DisplayName("BR-001, BR-002, BR-004: a sheet the stored data changed under is answered by the core's own refusal at the control (locked, not offered, choose the Budget App Transaction), stores nothing, and shows the choices as they are now")
    fun refusalsOfTheCoreAreAnsweredAtTheControl() {
        compared()
        val screen = checking(open = "deleted")
        slice.copyStore.copy.replaceAll { if (it.budgetAppReference == "y-deleted") it.copy(state = BudgetAppTransactionState.Present) else it }
        screen.switchReUpload(on = true)
        assertEquals("Not stored: the Review Decision is locked, because Example Budget App holds this Bank Transaction under its own Import Identifier.", screen.review().answer)
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())

        screen.open(key("possible"))
        screen.choose(ReviewChoice.AlreadyPresent)
        screen.chooseTransaction("y-not-among-them")
        assertEquals("Not stored yet: choose which Budget App Transaction is the same.", screen.review().answer)
        assertEquals(listOf("y-possible"), screen.review().chooser!!.options.map { it.reference })
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())

        slice.copyStore.copy.removeAll { it.budgetAppReference == "y-possible" }
        screen.chooseTransaction("y-possible")
        assertEquals("Not stored: its Transaction Status, as stored now, does not offer that choice; the choices it offers are shown.", screen.review().answer)
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertTrue(screen.state.value.ui!!.sheet!!.review!!.choices.isEmpty())
    }
}
