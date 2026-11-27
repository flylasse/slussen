package slussen.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
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
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.TransferDescriptionKind.BankTransfer
import slussen.core.port.TransferDescriptionKind.CardPayment
import slussen.core.settings.DescriptionEdit
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

/**
 * The Transfer Descriptions screen (`transfer-descriptions.html#lists`, `#edit`, `#restore`) and its two lines on
 * Settings (`settings.html#list`): every action shows its state at its own control before anything is stored, and its
 * answer at the list it changed (NFR-023), also with a storage that fails, never a crash.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, the Transfer Descriptions screen")
class UC010TransferDescriptionsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val app = ExampleData.BUDGET_APP_NAME

    private fun screen() = TransferDescriptionsViewModel(fixture.services).also { it.load() }

    private fun TransferDescriptionsViewModel.ui() = state.value!!

    private fun stored(kind: slussen.core.port.TransferDescriptionKind) = slice.copyStore.transferDescriptions.filter { it.kind == kind }.map { it.text }

    private val defaultCardPayment = listOf(
        "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY", "CRD AUTOPAY", "CITICARDAP",
    )

    private val statusesGiven =
        "The Transfers among the Bank Transactions not yet uploaded were detected again and their Transaction Statuses given again from " +
            "stored data, without contacting $app or any Source."

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1 (#lists): both lists in their order, the entry the Operator added marked Added by you and no other, each with Add entry and Restore, and how an entry matches")
    fun theListsShowWhichEntriesWereAdded() {
        runBlocking { slice.descriptionLists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT")) }

        val ui = screen().ui()

        val card = ui.list(CardPayment)
        assertEquals("Card Payment Descriptions", card.title)
        assertEquals(defaultCardPayment + "PINECREST PMT", card.lines.map { it.text })
        assertEquals(listOf("PINECREST PMT"), card.lines.filter { it.addedByYou }.map { it.text })
        assertEquals("Bank Transfer Descriptions", ui.list(BankTransfer).title)
        assertEquals(7, ui.list(BankTransfer).lines.size)
        assertTrue(ui.list(BankTransfer).lines.none { it.addedByYou })
        assertEquals("Matches anywhere in payee or memo · * = any characters", ui.note)
        assertNull(card.answer)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1 (settings.html#list): Settings shows both lists under Transfers after Transfer detection, each with its number of entries, opening the Transfer Descriptions")
    fun settingsShowsTheTwoLists() {
        runBlocking { slice.descriptionLists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT")) }

        val settings = SettingsViewModel(fixture.services).also { it.load() }

        val transfers = settings.state.value!!.sections.single { it.title == "Transfers" }.items
        assertEquals(
            listOf(
                SettingItemUi.Lists(ListsLineUi("Card Payment Descriptions", "Entries: 10 · defaults ship with the build", "lists-CardPayment")),
                SettingItemUi.Lists(ListsLineUi("Bank Transfer Descriptions", "Entries: 7 · defaults ship with the build", "lists-BankTransfer")),
            ),
            transfers.drop(1).take(2),
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008", "BR-009"], requirements = ["NFR-023"])
    @DisplayName("A7 (#edit): Save shows Saving… and the dialog cannot be left before anything is stored; saved, the Card Payment list shows the entry, says the Transfers were detected again and that it is recorded; the other list shows no answer")
    fun anEntryIsAddedWithSavingThenSaved() {
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val viewModel = screen()
        viewModel.startAdding(CardPayment)
        assertEquals("Add a Card Payment Description", viewModel.ui().editor!!.title)
        viewModel.edit("  PINECREST PMT ")

        viewModel.save()
        assertTrue(viewModel.ui().editor!!.saving)
        viewModel.cancelEditing()
        viewModel.startAdding(BankTransfer)
        assertEquals(CardPayment, viewModel.ui().editor!!.kind)
        assertEquals(defaultCardPayment, stored(CardPayment))
        hold.complete(Unit)

        assertNull(viewModel.ui().editor)
        assertEquals(defaultCardPayment + "PINECREST PMT", viewModel.ui().list(CardPayment).lines.map { it.text })
        assertTrue(viewModel.ui().list(CardPayment).lines.last().addedByYou)
        assertEquals(
            AnswerUi("Saved: PINECREST PMT added. $statusesGiven Recorded in the Decision Journal.", AnswerKind.Done),
            viewModel.ui().list(CardPayment).answer,
        )
        assertNull(viewModel.ui().list(BankTransfer).answer)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("A7 step 1, BR-008 (#edit Not saved): autopay is refused at the field as already in the list as AUTOPAY, an entry of spaces only as empty, and one of 101 characters as too long; the dialog stays open and nothing is stored")
    fun aRefusedEntryIsAnsweredAtTheField() {
        val viewModel = screen()
        viewModel.startAdding(CardPayment)

        viewModel.edit("autopay")
        viewModel.save()
        assertEquals("Not saved: autopay is already in the list as AUTOPAY. Enter another text.", viewModel.ui().editor!!.error)
        assertFalse(viewModel.ui().editor!!.saving)
        viewModel.edit("   ")
        assertNull(viewModel.ui().editor!!.error)
        viewModel.save()
        assertEquals("Not saved: an entry cannot be empty. Enter the text a payee or memo holds.", viewModel.ui().editor!!.error)
        viewModel.edit("Q".repeat(101))
        viewModel.save()
        assertEquals("Not saved: an entry is at most 100 characters, and this one has 101. Enter a shorter text.", viewModel.ui().editor!!.error)

        assertEquals(defaultCardPayment, stored(CardPayment))
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", requirements = ["NFR-023"])
    @DisplayName("Failure postconditions: an entry that cannot be stored closes the dialog; the list stays as it was and says so with how to try again, and Add entry offers the text entered again; never a crash")
    fun anEntryThatCannotBeStoredIsAnsweredAtTheList() {
        slice.settings.failsToStore = true
        val viewModel = screen()
        viewModel.startAdding(CardPayment)
        viewModel.edit("PINECREST PMT")

        viewModel.save()

        assertNull(viewModel.ui().editor)
        assertEquals(defaultCardPayment, viewModel.ui().list(CardPayment).lines.map { it.text })
        assertEquals(
            AnswerUi("Not saved: Slussen could not store PINECREST PMT. The list stays as it was.", AnswerKind.Failed, "Tap Add entry to try again."),
            viewModel.ui().list(CardPayment).answer,
        )
        viewModel.startAdding(CardPayment)
        assertEquals("PINECREST PMT", viewModel.ui().editor!!.value)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", requirements = ["NFR-023"])
    @DisplayName("NFR-023: a change whose lists cannot be read is answered Not saved at its list, and the screen says the lists could not be read again; never a crash")
    fun aChangeThatCannotReadTheListsIsNotSaved() {
        val viewModel = screen()
        slice.settings.failsToRead = true
        viewModel.startAdding(BankTransfer)
        viewModel.edit("SWEEP")

        viewModel.save()

        assertEquals(AnswerKind.Failed, viewModel.ui().list(BankTransfer).answer?.kind)
        assertEquals(7, viewModel.ui().list(BankTransfer).lines.size)
        assertEquals(
            "The description lists could not be read again from this device; the lists shown are the last ones read. Open them again to see them.",
            viewModel.ui().notReadAgain,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", requirements = ["NFR-023"])
    @DisplayName("NFR-023: lists that cannot be read when the screen opens say so with Try again, and show nothing as stored")
    fun listsNotOpened() {
        slice.settings.failsToRead = true

        val viewModel = screen()

        assertNull(viewModel.state.value)
        assertEquals("The description lists could not be read from this device; nothing was changed. Try again.", viewModel.notOpened.value)
        slice.settings.failsToRead = false
        viewModel.load()
        assertNull(viewModel.notOpened.value)
        assertEquals(9, viewModel.ui().list(CardPayment).lines.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"], requirements = ["NFR-023"])
    @DisplayName("A7: tapping an entry offers Change and Remove; Change opens the dialog with the entry, and saved, the entry is changed in its place and the answer names both")
    fun anEntryIsChangedFromItsChoice() {
        val viewModel = screen()

        viewModel.openEntry(CardPayment, "ACH PMT")
        assertEquals(EntryChoiceUi(CardPayment, "ACH PMT", removing = false), viewModel.ui().choice)
        viewModel.startChanging()
        assertNull(viewModel.ui().choice)
        assertEquals("Change a Card Payment Description", viewModel.ui().editor!!.title)
        assertEquals("ACH PMT", viewModel.ui().editor!!.value)
        viewModel.edit("ACH * PMT")
        viewModel.save()

        assertEquals(defaultCardPayment.map { if (it == "ACH PMT") "ACH * PMT" else it }, viewModel.ui().list(CardPayment).lines.map { it.text })
        assertEquals("Saved: ACH PMT changed to ACH * PMT. $statusesGiven Recorded in the Decision Journal.", viewModel.ui().list(CardPayment).answer?.text)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"], requirements = ["NFR-023"])
    @DisplayName("A7, BR-008: Remove shows Removing… in the choice, which cannot be left, before anything is stored; removed, the answer is at the list; the last entry removed leaves a list that says it holds none")
    fun anEntryIsRemovedFromItsChoice() {
        slice.copyStore.transferDescriptions.removeAll { it.kind == BankTransfer && it.text != "XFER" }
        val hold = CompletableDeferred<Unit>()
        slice.settings.beforeStore = { hold.await() }
        val viewModel = screen()
        viewModel.openEntry(BankTransfer, "XFER")

        viewModel.remove()
        assertEquals(EntryChoiceUi(BankTransfer, "XFER", removing = true), viewModel.ui().choice)
        viewModel.cancelChoice()
        assertTrue(viewModel.ui().choice!!.removing)
        hold.complete(Unit)

        assertNull(viewModel.ui().choice)
        assertEquals(emptyList<String>(), viewModel.ui().list(BankTransfer).lines.map { it.text })
        assertEquals("No entry: no payee or memo marks a Bank Transfer, so Slussen proposes none.", viewModel.ui().list(BankTransfer).emptyNote)
        assertEquals("Saved: XFER removed. The list holds no entry now. $statusesGiven Recorded in the Decision Journal.", viewModel.ui().list(BankTransfer).answer?.text)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-006", "BR-007"], requirements = ["NFR-023"])
    @DisplayName("A8 (#restore): Restore first asks, naming what the current list has beyond the default and showing the default list; Restore shows Restoring… and cannot be left; restored, the list is the default and the answer says so")
    fun aListIsRestoredAfterTheConfirmation() {
        runBlocking {
            slice.descriptionLists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))
            slice.descriptionLists.edit(CardPayment, DescriptionEdit.Remove("ACH PMT"))
        }
        val hold = CompletableDeferred<Unit>()
        val viewModel = screen()

        viewModel.askRestore(CardPayment)

        val restore = viewModel.ui().restore!!
        assertEquals("Restore the default Card Payment Descriptions?", restore.title)
        assertEquals(
            "Your current list, with PINECREST PMT and without ACH PMT, is recorded in the Decision Journal first. Then the list becomes:",
            restore.text,
        )
        assertEquals(defaultCardPayment.joinToString(" · "), restore.defaults)
        slice.settings.beforeStore = { hold.await() }
        viewModel.confirmRestore()
        assertTrue(viewModel.ui().restore!!.saving)
        viewModel.cancelRestore()
        assertTrue(viewModel.ui().restore!!.saving)
        hold.complete(Unit)

        assertNull(viewModel.ui().restore)
        assertEquals(defaultCardPayment, viewModel.ui().list(CardPayment).lines.map { it.text })
        assertEquals(
            "Saved: the default list is restored, 9 entries. $statusesGiven Recorded in the Decision Journal, with the list before it.",
            viewModel.ui().list(CardPayment).answer?.text,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-006"])
    @DisplayName("A8 step 2: Cancel restores nothing and records nothing, and the list says it is unchanged; a list that is the default already is answered so without asking")
    fun aRestoreIsCancelledOrNotNeeded() {
        runBlocking { slice.descriptionLists.edit(BankTransfer, DescriptionEdit.Add("SWEEP")) }
        val journaled = slice.journalRecorder.entries.size
        val viewModel = screen()

        viewModel.askRestore(BankTransfer)
        viewModel.cancelRestore()
        viewModel.askRestore(CardPayment)

        assertNull(viewModel.ui().restore)
        assertEquals(AnswerUi("Not changed: the list stays as it is.", AnswerKind.Unchanged), viewModel.ui().list(BankTransfer).answer)
        assertEquals(AnswerUi("Not changed: this is the default list already.", AnswerKind.Unchanged), viewModel.ui().list(CardPayment).answer)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertTrue("SWEEP" in stored(BankTransfer))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-006"], requirements = ["NFR-023"])
    @DisplayName("A8, failure postconditions: a restore that cannot be stored closes the question; the list stays and says so with how to try again")
    fun aRestoreThatCannotBeStoredIsAnsweredAtTheList() {
        runBlocking { slice.descriptionLists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT")) }
        slice.settings.failsToStore = true
        val viewModel = screen()
        viewModel.askRestore(CardPayment)

        viewModel.confirmRestore()

        assertNull(viewModel.ui().restore)
        assertEquals(
            AnswerUi(
                "Not restored: Slussen could not store the default list. The list stays as it was.",
                AnswerKind.Failed,
                "Tap Restore the default list to try again.",
            ),
            viewModel.ui().list(CardPayment).answer,
        )
        assertEquals(defaultCardPayment + "PINECREST PMT", stored(CardPayment))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, Save says why in the dialog, which stays open; nothing is stored")
    fun unavailableWhileAPlanAwaitsConfirmation() {
        val viewModel = screen()
        viewModel.startAdding(CardPayment)
        viewModel.edit("PINECREST PMT")
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        viewModel.save()

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", viewModel.ui().editor!!.error)
        assertFalse(viewModel.ui().editor!!.saving)
        assertEquals(defaultCardPayment, stored(CardPayment))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("Step 5, UC-003 BR-016: when Household owes a read of its read period, the answer names it as waiting for that read and never says every Transfer was detected again")
    fun theAnswerNamesTheBudgetThatWaits() {
        runBlocking {
            val listed = ExampleData.budgetsRead().map { budget ->
                BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
            }
            slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), slice.clock.instant())
            val household = slice.connectionStore.budgets().single { it.name == "Household" }.id
            slice.copyStore.cursors[household] = ChangeCursor("c-household")
            slice.copyStore.periodOwed += household
        }
        val viewModel = screen()
        viewModel.startAdding(BankTransfer)
        viewModel.edit("SWEEP")

        viewModel.save()

        assertEquals(
            "Saved: SWEEP added. The Transfers among the Bank Transactions not yet uploaded were detected again and their Transaction " +
                "Statuses given again from stored data, without contacting $app or any Source, except in Household, which waits for " +
                "the read of $app it owes. Recorded in the Decision Journal.",
            viewModel.ui().list(BankTransfer).answer?.text,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("Step 5, ADR-007 §3a: when the Transfers cannot be detected again now, the answer says the list is saved and that no Upload Plan is prepared until they are")
    fun transfersPendingAreSaid() {
        slice.copyStore.failsToConclude = true
        slice.copyStore.pending = false
        val viewModel = screen()
        viewModel.startAdding(BankTransfer)
        viewModel.edit("SWEEP")

        viewModel.save()

        assertEquals(
            "Saved: SWEEP added. The Transfers could not be detected again yet; no Upload Plan is prepared until they are, at the next " +
                "start or the next read of $app. Recorded in the Decision Journal.",
            viewModel.ui().list(BankTransfer).answer?.text,
        )
        assertTrue(slice.journalRecorder.entries.any { it.eventKind == JournalEventKind.ConfigurationChange })
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", requirements = ["NFR-023"])
    @DisplayName("NFR-023: when an operation ends elsewhere, the lists are read again and the dialog keeps what the Operator is typing")
    fun theListsAreReadAgainWhenAnOperationEnds() {
        val viewModel = screen()
        viewModel.startAdding(CardPayment)
        viewModel.edit("PINE")

        runBlocking { slice.descriptionLists.edit(BankTransfer, DescriptionEdit.Add("SWEEP")) }

        assertEquals("SWEEP", viewModel.ui().list(BankTransfer).lines.last().text)
        assertEquals(CardPayment, viewModel.ui().editor!!.kind)
        assertEquals("PINE", viewModel.ui().editor!!.value)
    }
}
