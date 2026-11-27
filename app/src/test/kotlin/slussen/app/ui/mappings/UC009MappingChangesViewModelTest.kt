package slussen.app.ui.mappings

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
import slussen.core.port.CoordinatorMode
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredSentRecord
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import java.time.LocalDate

/**
 * The changes of Manage Account Mappings on the screen (UC-009 step 1, steps 2-7, A1-A13; `docs/mockups/account-mappings.html`
 * `#map`, `#sheet`, `#confirm`, `#confirmed`, `#already-used`, `#rejected`, `#retire`, `#restore`, `#move`, `#map-again`,
 * `#move-confirm`, `#remove`, `#identify-again`, `#identify-after-sends`, `#rename`, `#start-date`, `#funded-by`,
 * `#no-offered`): each Bank Account's line with its Funded By or Transfer Partner, and the sheet of the Bank Account with every action,
 * each with its state at the control before anything is stored and its answer there. The rules themselves are tested in
 * `UC009MappingChangesTest` and `UC009IdentificationAndMovesTest`. Every name and amount is invented (C-005).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-009: Manage Account Mappings, the changes on the screen")
class UC009MappingChangesViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)
    private val screen = AccountMappingsViewModel(fixture.services)

    private fun ui() = screen.state.value!!

    private fun ready() {
        runBlocking { scenario.compareEveryStatus() }
        screen.load()
    }

    private fun id(name: String) = scenario.bankAccount(name)

    private fun line(name: String): MappingLine = ui().groups.flatMap { it.lines }.single { it.bankAccount == name }

    private fun group(name: String): String = ui().groups.single { group -> group.lines.any { it.bankAccount == name } }.title

    private fun stored(name: String): StoredBankAccount = slice.connectionStore.bankAccountList.single { it.id == id(name) }

    private fun budgetAccount(name: String): Long = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    private fun sentThroughChecking(vararg keys: String, outcome: SentRecordOutcome = SentRecordOutcome.Written) {
        keys.forEach { slice.copyStore.sentRecords += StoredSentRecord(scenario.transaction(it), budgetAccount("Checking"), sentAsTransfer = false, outcome) }
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-014"])
    @DisplayName("Step 1 (#map): each Bank Account shows its Funded By when it is a credit card or its Transfer Partner otherwise, whether it no longer applies, whether anything was sent through its Account Mapping and whether it is identified; nothing says it is set in a later version")
    fun eachLineShowsItsOtherSideAndState() {
        ready()

        assertEquals("Transfer Partner: not set", line("Kestrel Checking").otherSide)
        assertEquals("Transfer Partner: Checking", line("Kestrel Reserve").otherSide)
        assertEquals("Funded By: not set", line("Pinecrest Rewards Card").otherSide)
        assertEquals("Nothing sent yet · identified", line("Kestrel Checking").state)
        assertEquals("Start Date 2026-10-01", line("Kestrel Checking").note)

        runBlocking { slice.connectionStore.link(id("Kestrel Reserve"), transferPartner = budgetAccount("Current")) }
        screen.load()
        assertEquals("Transfer Partner: Current · no longer applies", line("Kestrel Reserve").otherSide)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario")
    @DisplayName("Step 2 (#sheet): a Bank Account opens its sheet with every action for it; a Retired Account offers Restore instead of Retire")
    fun theSheetHasEveryAction() {
        ready()

        screen.openSheet(id("Kestrel Checking"))

        val sheet = ui().bankAccountSheet!!
        assertEquals("Kestrel Checking → Checking", sheet.title)
        assertEquals(
            listOf(
                "Rename", "Start Date", "Bank Account type and Bank", "Move Account Mapping…", "Remove Account Mapping…",
                "Identify again…", "Transfer Partner…", "Retire Bank Account…",
            ),
            sheet.actions.map { it.title },
        )
        assertEquals("2026-10-01", sheet.actions.single { it.action == MappingAction.StartDate }.detail)
        assertEquals("Not set", sheet.actions.single { it.action == MappingAction.OtherSide }.detail)
        screen.closeSheet()
        assertNull(ui().bankAccountSheet)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A12: Rename a Bank Account", businessRules = ["BR-011"])
    @DisplayName("A12 (#rename): a new name is stored and shown; an empty name is refused at the editor, which stays open")
    fun renaming() {
        ready()
        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.Rename)
        assertEquals("Kestrel Checking", (ui().editor as MappingEditorUi.Rename).name)

        screen.saveName("  ")
        assertEquals("Not stored: a name is 1 to 100 characters", ui().message)
        assertTrue(ui().editor is MappingEditorUi.Rename)

        screen.saveName("Kestrel Everyday")
        assertNull(ui().editor)
        assertEquals("Kestrel Everyday: renamed · stored", ui().message)
        assertEquals("Kestrel Everyday", stored("Kestrel Checking").name)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A13: Set, Change or Clear the Start Date", businessRules = ["BR-015"])
    @DisplayName("A13 (#start-date): a Start Date is stored and the statuses given again; one that is not a date is answered at the editor; Clear removes it")
    fun startDate() {
        ready()
        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.StartDate)

        screen.saveStartDate("10/05")
        assertEquals("The Start Date is a date like 2026-09-01, or empty.", ui().message)

        screen.saveStartDate("2026-10-05")
        assertEquals("Kestrel Checking: Start Date 2026-10-05 · stored · the Transaction Statuses are given again", ui().message)
        assertEquals(LocalDate.of(2026, 10, 5), stored("Kestrel Checking").startDate)

        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.StartDate)
        screen.clearStartDate()
        assertEquals("Kestrel Checking: Start Date cleared · stored · the Transaction Statuses are given again", ui().message)
        assertNull(stored("Kestrel Checking").startDate)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008"])
    @DisplayName("A9 (#funded-by): the Funded By of a credit card is chosen from the Budget Accounts of its Budget that Slussen fetches, stored and shown in the list; Clear removes it")
    fun fundedBy() {
        ready()
        screen.openSheet(id("Pinecrest Rewards Card"))
        screen.startAction(MappingAction.OtherSide)

        val editor = ui().editor as MappingEditorUi.OtherSide
        assertEquals("Funded By of Pinecrest Rewards Card", editor.title)
        assertEquals(listOf("Checking", "Reserve Savings"), editor.choices.map { it.title })
        assertEquals(listOf("Kestrel Checking", "Kestrel Reserve"), editor.choices.map { it.detail })

        screen.saveOtherSide(budgetAccount("Checking"))
        assertEquals("Pinecrest Rewards Card: Funded By Checking · stored · the Transfers are detected again", ui().message)
        assertEquals("Funded By: Checking", line("Pinecrest Rewards Card").otherSide)

        screen.openSheet(id("Pinecrest Rewards Card"))
        screen.startAction(MappingAction.OtherSide)
        screen.saveOtherSide(null)
        assertEquals("Funded By: not set", line("Pinecrest Rewards Card").otherSide)
        assertNull(stored("Pinecrest Rewards Card").fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005"])
    @DisplayName("A4, A5 (#retire, #restore): Retire asks first and says what it means; confirmed, the Bank Account is listed among the Retired Accounts, and Restore brings it back")
    fun retireAndRestore() {
        ready()
        screen.openSheet(id("Pinecrest Rewards Card"))
        screen.startAction(MappingAction.Retire)

        val question = ui().question!!
        assertEquals("Retire Pinecrest Rewards Card?", question.title)
        assertEquals(listOf("Not fetched, compared or uploaded until you restore it. History kept."), question.lines)
        assertFalse(stored("Pinecrest Rewards Card").retired)
        screen.confirmQuestion()

        assertTrue(stored("Pinecrest Rewards Card").retired)
        assertEquals("Retired Accounts", group("Pinecrest Rewards Card"))
        assertEquals("Pinecrest Rewards Card: retired · stored · the Transaction Statuses are given again", ui().message)

        screen.openSheet(id("Pinecrest Rewards Card"))
        assertTrue(ui().bankAccountSheet!!.actions.any { it.action == MappingAction.Restore })
        screen.startAction(MappingAction.Restore)
        assertFalse(stored("Pinecrest Rewards Card").retired)
        assertEquals("Household · USD", group("Pinecrest Rewards Card"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("A8 (#move, #move-confirm): Move offers the free Budget Accounts of the Bank Account's currency; after a written send it asks first with the number sent and the number rejected, and stores only on Confirm")
    fun movingAfterSendsAsksFirst() {
        ready()
        sentThroughChecking("new", "typed")
        sentThroughChecking("deleted", outcome = SentRecordOutcome.Rejected)
        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.Move)

        val editor = ui().editor as MappingEditorUi.Move
        assertEquals("Move Kestrel Checking to…", editor.title)
        assertEquals(listOf("Health Savings"), editor.choices.map { it.title })
        assertEquals("Care Fund · 6,324.20", editor.choices.single().detail)

        screen.moveTo(budgetAccount("Health Savings"))
        val question = ui().question!!
        assertEquals("Move Kestrel Checking to Health Savings?", question.title)
        assertEquals(
            listOf(
                "2 transactions were sent to Checking in Example Budget App. They stay there; Slussen won't send them again. Move or delete them in Example Budget App.",
                "1 more was rejected by Example Budget App and is not in Checking. It goes to Health Savings at its next Upload.",
            ),
            question.lines,
        )
        assertEquals(budgetAccount("Checking"), stored("Kestrel Checking").mapping!!.budgetAccountId)
        screen.cancelQuestion()
        assertNull(ui().question)
        assertEquals(budgetAccount("Checking"), stored("Kestrel Checking").mapping!!.budgetAccountId)

        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.Move)
        screen.moveTo(budgetAccount("Health Savings"))
        screen.confirmQuestion()
        assertEquals(budgetAccount("Health Savings"), stored("Kestrel Checking").mapping!!.budgetAccountId)
        assertEquals("Care Fund · USD", group("Kestrel Checking"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-001", "BR-003"])
    @DisplayName("A11, A8 (#remove, #map-again): without a send the Account Mapping is removed with no question; the Bank Account is listed as not mapped and is mapped again with no question")
    fun removeAndMapAgain() {
        ready()
        screen.openSheet(id("Kestrel Reserve"))
        screen.startAction(MappingAction.Remove)

        assertNull(ui().question)
        assertNull(stored("Kestrel Reserve").mapping)
        assertEquals("Not mapped", group("Kestrel Reserve"))
        assertEquals("Kestrel Reserve: Account Mapping removed · stored · the Transaction Statuses are given again", ui().message)

        screen.openSheet(id("Kestrel Reserve"))
        val titles = ui().bankAccountSheet!!.actions.map { it.title }
        assertTrue("Map it to a Budget Account…" in titles)
        assertFalse("Remove Account Mapping…" in titles)
        screen.startAction(MappingAction.Move)
        assertEquals("Map Kestrel Reserve to…", (ui().editor as MappingEditorUi.Move).title)
        screen.moveTo(budgetAccount("Reserve Savings"))
        assertEquals(budgetAccount("Reserve Savings"), stored("Kestrel Reserve").mapping!!.budgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A10: No Offered Bank Accounts Stored", businessRules = ["BR-009"])
    @DisplayName("A10 (#no-offered): with no Offered Bank Account stored, Identify again says when it can be done and that the current identification stays in use")
    fun noOfferedBankAccounts() {
        ready()
        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.Identify)

        val editor = ui().editor as MappingEditorUi.Identify
        assertEquals("Identify Kestrel Checking again", editor.title)
        assertEquals(
            "Nothing offered yet · it can be identified after the next Fetch of Kestrel Feeds; the current identification stays in use.",
            editor.none,
        )
        assertTrue(editor.options.isEmpty())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A6: Identify a Bank Account Again", businessRules = ["BR-007", "BR-009"])
    @DisplayName("A6, A1, steps 4-7 (#identify-again, #confirmed): the Offered Bank Accounts of the Source are shown with the current one marked; another one is confirmed and the answer says it is used from the next Fetch")
    fun identifyingAgain() {
        ready()
        val bank = runBlocking { slice.connectionStore.bankNamed("Kestrel Bank").id }
        runBlocking {
            slice.offeredStore.replaceOffered(
                slice.feedsId, null,
                listOf(
                    ExampleData.offered("acct-${id("Kestrel Checking")}", "Checking", "checking", USD, "4410", null, bank, slice.clock.instant()),
                    ExampleData.offered("acct-9911", "Everyday Checking", "checking", USD, "9911", null, bank, slice.clock.instant()),
                ),
            )
        }
        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.Identify)

        val editor = ui().editor as MappingEditorUi.Identify
        assertEquals(listOf("Checking · Kestrel Bank", "Everyday Checking · Kestrel Bank"), editor.options.map { it.title })
        assertEquals("Current", editor.options.first().tag)

        screen.identifyWith(editor.options.last().id)

        assertNull(ui().editor)
        assertEquals("Kestrel Checking identified · used from the next Fetch", ui().message)
        assertEquals("acct-9911", stored("Kestrel Checking").providerReference)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A7: Identify Again After Transactions Were Sent", businessRules = ["BR-007"])
    @DisplayName("A7 (#identify-after-sends): after a written send, identifying again asks first with the numbers; nothing is stored until Continue")
    fun identifyingAgainAfterSendsAsksFirst() {
        ready()
        val bank = runBlocking { slice.connectionStore.bankNamed("Kestrel Bank").id }
        runBlocking {
            slice.offeredStore.replaceOffered(
                slice.feedsId, null,
                listOf(ExampleData.offered("acct-9911", "Everyday Checking", "checking", USD, "9911", null, bank, slice.clock.instant())),
            )
        }
        sentThroughChecking("new")
        screen.openSheet(id("Kestrel Checking"))
        screen.startAction(MappingAction.Identify)
        screen.identifyWith((ui().editor as MappingEditorUi.Identify).options.single().id)

        val question = ui().question!!
        assertEquals("Identify Kestrel Checking again?", question.title)
        assertEquals(listOf("1 transaction was sent from Kestrel Checking to Example Budget App. It stays there; Slussen won't send it again."), question.lines)
        assertEquals("Continue", question.confirm)
        assertEquals("acct-${id("Kestrel Checking")}", stored("Kestrel Checking").providerReference)

        screen.confirmQuestion()
        assertEquals("acct-9911", stored("Kestrel Checking").providerReference)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A3: Operator Rejects the Proposal", businessRules = ["BR-004"])
    @DisplayName("Steps 2-3, A3 (#confirm, #rejected): a Bank Account waiting for confirmation says so in the list; its proposal is shown marked, and Not this one records no identification and says that its transactions stay out of use")
    fun aProposalIsRejected() {
        ready()
        val bank = runBlocking { slice.connectionStore.bankNamed("Kestrel Bank").id }
        runBlocking {
            slice.connectionStore.bankAccountList.replaceAll { if (it.id == id("Kestrel Checking")) it.copy(providerReference = null) else it }
            slice.offeredStore.replaceOffered(
                slice.feedsId, null,
                listOf(ExampleData.offered("acct-9911", "Everyday Checking", "checking", USD, "9911", null, bank, slice.clock.instant(), proposedBankAccountId = id("Kestrel Checking"))),
            )
        }
        screen.load()
        assertEquals("Nothing sent yet · Waiting for your confirmation", line("Kestrel Checking").state)
        screen.openSheet(id("Kestrel Checking"))
        assertTrue(ui().bankAccountSheet!!.actions.any { it.title == "Confirm the Bank Account…" })
        screen.startAction(MappingAction.Identify)

        val editor = ui().editor as MappingEditorUi.Identify
        assertEquals("Confirm the Bank Account for Kestrel Checking", editor.title)
        assertEquals("Proposed", editor.options.single().tag)
        assertTrue(editor.canReject)

        screen.rejectProposal()

        assertEquals("Kestrel Checking: proposal rejected · not used until identified (Blocked Reason: unrecognised Bank Account)", ui().message)
        assertNull(stored("Kestrel Checking").providerReference)
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-012"])
    @DisplayName("NFR-023, failure postconditions: a change that cannot be stored is answered at its control and changes nothing; from the tap on the control shows that it runs and takes no second tap; while an Upload Plan awaits confirmation it says why it cannot run")
    fun aChangeThatCannotBeStoredChangesNothing() {
        ready()
        screen.openSheet(id("Pinecrest Rewards Card"))
        screen.startAction(MappingAction.Retire)
        slice.connectionStore.failsToStore = true

        screen.confirmQuestion()

        assertEquals("Not stored: Slussen could not use the data stored on this device · try again", ui().message)
        assertFalse(stored("Pinecrest Rewards Card").retired)
        assertTrue(ui().question != null)

        slice.connectionStore.failsToStore = false
        val hold = CompletableDeferred<Unit>()
        slice.connectionStore.beforeStore = { hold.await() }
        val ended = slice.coordinator.ended.value
        screen.confirmQuestion()
        assertEquals("Retiring…", ui().pending)
        screen.confirmQuestion()
        hold.complete(Unit)
        assertNull(ui().pending)
        assertTrue(stored("Pinecrest Rewards Card").retired)
        assertEquals(ended + 1, slice.coordinator.ended.value)

        slice.connectionStore.beforeStore = {}
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        screen.openSheet(id("Pinecrest Rewards Card"))
        screen.startAction(MappingAction.Restore)
        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", ui().message)
        assertTrue(stored("Pinecrest Rewards Card").retired)
    }
}
