package slussen.core.settings

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StoredSentRecord
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferDescriptionKind.BankTransfer
import slussen.core.port.TransferDescriptionKind.CardPayment
import slussen.core.transfer.TransferChoice
import slussen.core.transfer.TransferOutcome
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * Adjust Settings (UC-010) for the two lists, the Card Payment Descriptions and the Bank Transfer Descriptions: shown with
 * the entries the Operator added, worked out each time (step 1, BR-007, S-B-20); an entry added, changed or removed (A7)
 * and a list restored to its default (A8), checked as BR-008 says, without regard to case and accents; each stored in one
 * storage operation (O-13) with the Comparison marker, recorded with the list before and after, and followed by the
 * Transfers detected again from stored data (step 5, BR-009, UC-005 BR-015, S-B-17). Every name, amount and identifier
 * is invented (C-005).
 */
@DisplayName("UC-010: Adjust Settings, the Card Payment Descriptions and the Bank Transfer Descriptions")
class UC010DescriptionListsTest {
    private val fixture = StoredStatusesFixture()
    private val slice = fixture.slice
    private val lists = slice.descriptionLists

    /** BR-007 as UC-010 states it, written here from the rule. */
    private val defaultCardPayment = listOf(
        "AUTOPAY", "AUTOMATIC PAYMENT", "PAYMENT - THANK", "AUTO-PMT", "ACH PMT", "AMERICAN EXPRESS ACH PMT", "CITI AUTOPAY", "CRD AUTOPAY", "CITICARDAP",
    )
    private val defaultBankTransfer = listOf("ÖVERFÖRING", "INTERNET TRANSFER", "EXPRESS INTERNET TRANSFER", "AMERICAN EXPRESS TRANSFER", "ONLINE TRANSFER", "TRANSFER", "XFER")

    private fun stored(kind: TransferDescriptionKind): List<String> = slice.copyStore.transferDescriptions.filter { it.kind == kind }.map { it.text }

    private fun configurationEntries() = slice.journalRecorder.entries.filter { it.eventKind == JournalEventKind.ConfigurationChange }

    /** A Bank Transaction a Fetch stored, with [payee] as its payee. */
    private fun withPayee(bankAccountId: Long, reference: String, date: LocalDate, amount: Amount, payee: String): Long {
        val id = fixture.stored(bankAccountId, reference, date, amount)
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == id) it.copy(payee = payee) else it }
        return id
    }

    /**
     * Kestrel Checking paid 100.00 on 2026-10-12 to Kestrel Reserve, received on 2026-10-13, both with the payee SWEEP …,
     * which no default Bank Transfer Description matches; Household read once. Returns (paid, received).
     */
    private suspend fun sweep(): Pair<Long, Long> {
        fixture.ready()
        val checking = fixture.bankAccount("Kestrel Checking", "Checking")
        val reserve = fixture.bankAccount("Kestrel Reserve", "Reserve Savings")
        val paid = withPayee(checking, "TX-SWEEP-OUT", LocalDate.of(2026, 10, 12), amount(-100), "SWEEP TO RESERVE 4410")
        val received = withPayee(reserve, "TX-SWEEP-IN", fixture.oct13, amount(100), "SWEEP FROM CHECKING")
        fixture.compare()
        check(fixture.status(paid) == TransactionStatus.New && fixture.status(received) == TransactionStatus.New)
        check(slice.copyStore.transfers.isEmpty())
        return paid to received
    }

    /** [sweep], with the Bank Transfer Description SWEEP added, so that the two are a Transfer Slussen proposes. */
    private suspend fun sweepProposed(): Pair<Long, Long> {
        val legs = sweep()
        assertTrue(lists.edit(BankTransfer, DescriptionEdit.Add("SWEEP")) is DescriptionOutcome.Stored)
        check(slice.copyStore.transfers.single().decision == TransferDecision.Proposed)
        return legs
    }

    // ---- Step 1, BR-007: the lists and the entries the Operator added --------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1, BR-007: a new installation shows the default lists in the order of BR-007, 9 Card Payment and 7 Bank Transfer Descriptions, none added by the Operator, each with its default list")
    fun aNewInstallationShowsTheDefaultLists() = runTest {
        val shown = lists.shown()

        assertEquals(defaultCardPayment, shown.cardPayment.texts)
        assertEquals(defaultBankTransfer, shown.bankTransfer.texts)
        assertTrue((shown.cardPayment.entries + shown.bankTransfer.entries).none { it.addedByOperator })
        assertEquals(defaultCardPayment, shown.cardPayment.defaults)
        assertEquals(defaultBankTransfer, shown.bankTransfer.defaults)
        assertEquals(defaultCardPayment, DescriptionRules.defaults(CardPayment))
        assertEquals(defaultBankTransfer, DescriptionRules.defaults(BankTransfer))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-007", "BR-008"])
    @DisplayName("BR-007, S-B-20: added by the Operator is worked out each time from the default list, compared as BR-008 compares: PINECREST PMT is added, OVERFORING entered after ÖVERFÖRING was removed is not, and nothing of it is stored")
    fun addedByTheOperatorIsWorkedOutEachTime() = runTest {
        lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))
        lists.edit(BankTransfer, DescriptionEdit.Remove("ÖVERFÖRING"))
        lists.edit(BankTransfer, DescriptionEdit.Add("overforing"))

        val shown = lists.shown()

        assertEquals(DescriptionEntryShown("PINECREST PMT", addedByOperator = true), shown.cardPayment.entries.last())
        assertEquals(DescriptionEntryShown("overforing", addedByOperator = false), shown.bankTransfer.entries.last())
        // The stored entry is a kind and a text only (`TRANSFER_DESCRIPTION`): nothing else could hold it.
        assertEquals(StoredTransferDescription(CardPayment, "PINECREST PMT"), slice.copyStore.transferDescriptions.single { it.text == "PINECREST PMT" })
        assertTrue(DescriptionRules.addedByOperator(CardPayment, "Pinecrest Pmt"))
        assertFalse(DescriptionRules.addedByOperator(CardPayment, "citi autopay"))
        // An entry of the other list's defaults is added by the Operator in this one.
        assertTrue(DescriptionRules.addedByOperator(CardPayment, "XFER"))
    }

    // ---- A7, BR-008: adding an entry -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"], requirements = ["NFR-006"])
    @DisplayName("A7 steps 1-3, BR-008: an entry with spaces around it is stored without them at the end of its list, in one storage operation that raises state_version and leaves the other list as it was, and is recorded once with the list before and after")
    fun anEntryIsAddedTrimmedAndRecordedWithTheListBeforeAndAfter() = runTest {
        val version = slice.stateVersion.value

        val outcome = lists.edit(CardPayment, DescriptionEdit.Add("  PINECREST PMT  "))

        assertEquals(DescriptionOutcome.Stored(defaultCardPayment + "PINECREST PMT", SettingEffect.StatusesGiven()), outcome)
        assertEquals(defaultCardPayment + "PINECREST PMT", stored(CardPayment))
        assertEquals(defaultBankTransfer, stored(BankTransfer))
        assertTrue(slice.stateVersion.value > version)
        val entry = configurationEntries().single()
        assertEquals(
            "Card Payment Descriptions: PINECREST PMT added; before: ${defaultCardPayment.joinToString(" · ")}; " +
                "after: ${(defaultCardPayment + "PINECREST PMT").joinToString(" · ")}",
            entry.details,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("A7 step 1, BR-008: an entry of spaces only is empty and refused; nothing is stored, raised or recorded")
    fun anEntryOfSpacesOnlyIsRefused() = runTest {
        val version = slice.stateVersion.value

        assertEquals(DescriptionOutcome.Empty, lists.edit(CardPayment, DescriptionEdit.Add("   ")))
        assertEquals(DescriptionOutcome.Empty, lists.edit(CardPayment, DescriptionEdit.Add("")))
        assertEquals(DescriptionOutcome.Empty, lists.edit(CardPayment, DescriptionEdit.Change("AUTOPAY", " ")))

        assertEquals(defaultCardPayment, stored(CardPayment))
        assertEquals(version, slice.stateVersion.value)
        assertTrue(slice.journalRecorder.entries.isEmpty())
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("BR-008: an entry is no longer than its stored length of 100 characters, counted without the spaces around it: 100 is stored, 101 refused with its length")
    fun anEntryIsNoLongerThanItsStoredLength() = runTest {
        val hundred = "P".repeat(100)

        assertEquals(DescriptionOutcome.TooLong(101, 100), lists.edit(CardPayment, DescriptionEdit.Add("Q".repeat(101))))
        assertTrue(lists.edit(CardPayment, DescriptionEdit.Add(" $hundred ")) is DescriptionOutcome.Stored)

        assertEquals(hundred, stored(CardPayment).last())
        assertEquals(DescriptionRules.MAX_LENGTH, 100)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("A7 step 1, BR-008: within one list no two entries are equal without regard to case and accents: autopay and overforing are refused, naming the entry that holds them; the same text in the other list is allowed")
    fun anEqualEntryInTheSameListIsRefused() = runTest {
        assertEquals(DescriptionOutcome.AlreadyInList("autopay", "AUTOPAY"), lists.edit(CardPayment, DescriptionEdit.Add("autopay")))
        assertEquals(DescriptionOutcome.AlreadyInList("Overforing", "ÖVERFÖRING"), lists.edit(BankTransfer, DescriptionEdit.Add(" Overforing ")))
        assertEquals(defaultCardPayment, stored(CardPayment))
        assertTrue(slice.journalRecorder.entries.isEmpty())

        assertTrue(lists.edit(BankTransfer, DescriptionEdit.Add("AUTOPAY")) is DescriptionOutcome.Stored)
        assertEquals(defaultBankTransfer + "AUTOPAY", stored(BankTransfer))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("BR-008: a * stands for any characters and is stored as typed; no other character is special, so a dot or a bracket is stored as it is")
    fun aStarAndOtherCharactersAreStoredAsTyped() = runTest {
        lists.edit(CardPayment, DescriptionEdit.Add("VESTA * BETALNING"))
        lists.edit(CardPayment, DescriptionEdit.Add("A.B [C]"))

        assertEquals(listOf("VESTA * BETALNING", "A.B [C]"), stored(CardPayment).takeLast(2))
        // The star makes VESTA ** BETALNING equal to no entry: only case and accents are disregarded.
        assertTrue(lists.edit(CardPayment, DescriptionEdit.Add("VESTA ** BETALNING")) is DescriptionOutcome.Stored)
    }

    // ---- A7: changing and removing an entry ------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("A7: a changed entry keeps its place and is recorded with the list before and after; changing it into another entry of the list is refused; its own spelling in another case is a change")
    fun anEntryIsChangedInItsPlace() = runTest {
        assertEquals(DescriptionOutcome.AlreadyInList("citi autopay", "CITI AUTOPAY"), lists.edit(CardPayment, DescriptionEdit.Change("AUTOPAY", "citi autopay")))

        val outcome = lists.edit(CardPayment, DescriptionEdit.Change("ACH PMT", "ACH * PMT"))

        val after = defaultCardPayment.map { if (it == "ACH PMT") "ACH * PMT" else it }
        assertEquals(DescriptionOutcome.Stored(after, SettingEffect.StatusesGiven()), outcome)
        assertEquals(after, stored(CardPayment))
        assertEquals(
            "Card Payment Descriptions: ACH PMT changed to ACH * PMT; before: ${defaultCardPayment.joinToString(" · ")}; after: ${after.joinToString(" · ")}",
            configurationEntries().single().details,
        )
        assertTrue(lists.edit(CardPayment, DescriptionEdit.Change("AUTOPAY", "Autopay")) is DescriptionOutcome.Stored)
        assertEquals("Autopay", stored(CardPayment).first())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("A7: an entry changed to its own text, or a list that would stay the same, stores nothing, raises nothing and records nothing")
    fun anEntryChangedToItsOwnTextIsUnchanged() = runTest {
        val version = slice.stateVersion.value

        assertEquals(DescriptionOutcome.Unchanged, lists.edit(CardPayment, DescriptionEdit.Change("AUTOPAY", " AUTOPAY ")))
        assertEquals(DescriptionOutcome.Unchanged, lists.edit(CardPayment, DescriptionEdit.Restore))

        assertEquals(version, slice.stateVersion.value)
        assertTrue(slice.journalRecorder.entries.isEmpty())
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("A7, BR-008: an entry is removed and recorded; a list may hold no entry, recorded as none; an entry that is no longer in the list is answered as gone and nothing is stored")
    fun anEntryIsRemovedAndAListMayBeEmpty() = runTest {
        slice.copyStore.transferDescriptions.removeAll { it.kind == BankTransfer && it.text != "XFER" }

        assertEquals(DescriptionOutcome.Stored(emptyList(), SettingEffect.StatusesGiven()), lists.edit(BankTransfer, DescriptionEdit.Remove("XFER")))

        assertEquals(emptyList<String>(), stored(BankTransfer))
        assertEquals(defaultCardPayment, stored(CardPayment))
        assertEquals("Bank Transfer Descriptions: XFER removed; before: XFER; after: no entry", configurationEntries().single().details)
        assertEquals(emptyList<DescriptionEntryShown>(), lists.shown().bankTransfer.entries)
        assertEquals(DescriptionOutcome.EntryGone, lists.edit(BankTransfer, DescriptionEdit.Remove("XFER")))
        assertEquals(DescriptionOutcome.EntryGone, lists.edit(BankTransfer, DescriptionEdit.Change("XFER", "X-FER")))
        assertEquals(1, configurationEntries().size)
    }

    // ---- A8: restoring a default list ------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-006", "BR-007"])
    @DisplayName("A8 step 3, BR-006: the current list is recorded first, before the default list is stored, and the default list after it; the other list stays")
    fun aRestoredListIsRecordedFirstThenReplaced() = runTest {
        lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))
        lists.edit(CardPayment, DescriptionEdit.Remove("ACH PMT"))
        lists.edit(BankTransfer, DescriptionEdit.Add("SWEEP"))
        val before = stored(CardPayment)
        val journaled = slice.journalRecorder.entries.size
        var recordedWhenStored: List<String> = emptyList()
        slice.settings.beforeStore = { recordedWhenStored = slice.journalRecorder.entries.drop(journaled).map { it.details } }

        val outcome = lists.edit(CardPayment, DescriptionEdit.Restore)

        assertEquals(DescriptionOutcome.Stored(defaultCardPayment, SettingEffect.StatusesGiven()), outcome)
        assertEquals(defaultCardPayment, stored(CardPayment))
        assertEquals(defaultBankTransfer + "SWEEP", stored(BankTransfer))
        val current = "Card Payment Descriptions before the default list is restored: ${before.joinToString(" · ")}"
        assertEquals(listOf(current), recordedWhenStored)
        assertEquals(
            listOf(current, "Card Payment Descriptions restored to the default list: ${defaultCardPayment.joinToString(" · ")}"),
            configurationEntries().drop(3).map { it.details },
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-006"])
    @DisplayName("A8, failure postconditions: a restore that cannot be stored keeps the list; the current list recorded first is followed by the entry that it was not restored")
    fun aRestoreThatCannotBeStoredKeepsTheList() = runTest {
        lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))
        slice.settings.failsToStore = true

        assertEquals(DescriptionOutcome.NotStored, lists.edit(CardPayment, DescriptionEdit.Restore))

        assertEquals(defaultCardPayment + "PINECREST PMT", stored(CardPayment))
        assertEquals(
            "Card Payment Descriptions not restored: the default list could not be stored, and the list stays as recorded before",
            configurationEntries().last().details,
        )
    }

    // ---- Failure postconditions and the precondition ----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", requirements = ["NFR-006"])
    @DisplayName("Failure postconditions: an entry that cannot be stored is answered not stored; the list stays, nothing is recorded, no marker is left and no Transfer is detected again")
    fun anEntryThatCannotBeStoredLeavesEverything() = runTest {
        val (paid, _) = sweep()
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size
        slice.settings.failsToStore = true

        assertEquals(DescriptionOutcome.NotStored, lists.edit(BankTransfer, DescriptionEdit.Add("SWEEP")))

        assertEquals(defaultBankTransfer, stored(BankTransfer))
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertFalse(slice.copyStore.pending)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, fixture.status(paid))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List")
    @DisplayName("Failure postconditions: when the lists cannot be read before the change, nothing is stored and the change is answered not stored")
    fun aChangeWhoseListsCannotBeReadIsNotStored() = runTest {
        slice.settings.failsToRead = true

        assertEquals(DescriptionOutcome.NotStored, lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT")))

        slice.settings.failsToRead = false
        assertEquals(defaultCardPayment, stored(CardPayment))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List")
    @DisplayName("Precondition (UC-006 BR-005): while an Upload Plan awaits confirmation or an Upload is written, no list can be changed or restored")
    fun unavailableWhileAPlanAwaitsConfirmation() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(DescriptionOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT")))
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(DescriptionOutcome.Unavailable(OperationRefusal.UploadBeingWritten), lists.edit(CardPayment, DescriptionEdit.Restore))

        assertEquals(defaultCardPayment, stored(CardPayment))
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    // ---- Step 5, BR-009: the Transfers detected again ---------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"], requirements = ["NFR-006"])
    @DisplayName("A7 step 2, BR-009: a Bank Transfer Description SWEEP pairs the two Bank Transactions into a Transfer Slussen proposes, from stored data: nothing is read, the time of the read stays, the marker is cleared and the changed statuses are recorded; removed again, the Transfer is dissolved")
    fun aChangedBankTransferListDetectsTheTransfersAgain() = runTest {
        val (paid, received) = sweep()
        val household = slice.connectionStore.budgets().single { it.name == "Household" }
        val reads = fixture.readsFromOutside()
        val journaled = slice.journalRecorder.entries.size

        lists.edit(BankTransfer, DescriptionEdit.Add("SWEEP"))

        assertEquals(TransferDecision.Proposed, slice.copyStore.transfers.single().decision)
        assertEquals(TransactionStatus.TransferLeg, fixture.status(paid))
        assertEquals(TransactionStatus.TransferLeg, fixture.status(received))
        assertEquals(reads, fixture.readsFromOutside())
        assertEquals(household.lastReadAt, slice.connectionStore.budgets().single { it.name == "Household" }.lastReadAt)
        assertFalse(slice.copyStore.pending)
        assertEquals(2, slice.journalRecorder.entries.drop(journaled).count { it.eventKind == JournalEventKind.StatusChanged })

        lists.edit(BankTransfer, DescriptionEdit.Remove("SWEEP"))

        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, fixture.status(paid))
        assertEquals(TransactionStatus.New, fixture.status(received))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("A7 step 2, BR-009, UC-005 BR-002: a Card Payment Description PINECREST PMT makes a card's inflow whose other side has not arrived a Lonely Transfer from its Funded By")
    fun aChangedCardPaymentListDetectsALonelyTransfer() = runTest {
        fixture.ready()
        fixture.bankAccount("Kestrel Checking", "Checking")
        val card = fixture.bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard)
        slice.connectionStore.link(card, fundedBy = fixture.budgetAccountIdOf("Checking"))
        val received = withPayee(card, "TX-CARD-IN", fixture.oct13, amount(250), "PINECREST PMT THANK YOU")
        fixture.compare()
        assertEquals(TransactionStatus.New, fixture.status(received))

        lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))

        val lonely = slice.copyStore.transfers.single()
        assertEquals(fixture.budgetAccountIdOf("Checking"), lonely.outflowBudgetAccountId)
        assertEquals(listOf(received), slice.importStatusStore.bankTransactions.filter { it.transferId == lonely.id }.map { it.id })
        assertEquals(TransactionStatus.TransferLeg, fixture.status(received))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("BR-009, UC-005 BR-015, S-B-17: a Transfer the Operator holds back stays held with both Transfer Legs when the description that made it is removed")
    fun aHeldTransferStaysHeldWhenItsDescriptionIsRemoved() = runTest {
        val (paid, received) = sweepProposed()
        assertTrue(slice.transferDecisions.decide(paid, TransferChoice.Hold) is TransferOutcome.Stored)
        val held = slice.copyStore.transfers.single()

        assertTrue(lists.edit(BankTransfer, DescriptionEdit.Remove("SWEEP")) is DescriptionOutcome.Stored)

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.HeldTransfer, fixture.status(paid))
        assertEquals(TransactionStatus.HeldTransfer, fixture.status(received))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-009"])
    @DisplayName("BR-009, UC-005 BR-015, S-B-17: a Transfer the Operator split stays split, its Transfer Legs ordinary, when the default list is restored without its description")
    fun aSplitTransferStaysSplitWhenTheDefaultListIsRestored() = runTest {
        val (paid, received) = sweepProposed()
        slice.transferDecisions.decide(paid, TransferChoice.Split)
        val split = slice.copyStore.transfers.single()

        assertTrue(lists.edit(BankTransfer, DescriptionEdit.Restore) is DescriptionOutcome.Stored)

        assertEquals(listOf(split), slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.New, fixture.status(paid))
        assertEquals(TransactionStatus.New, fixture.status(received))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("BR-009, UC-005 BR-015: a Confirmed Transfer whose own sides are still valid keeps its decision when its description is removed; no decision is dropped")
    fun aConfirmedTransferKeepsItsDecisionWhenItsDescriptionIsRemoved() = runTest {
        val (paid, received) = sweepProposed()
        slice.transferDecisions.decide(paid, TransferChoice.Confirm)
        val confirmed = slice.copyStore.transfers.single()
        val journaled = slice.journalRecorder.entries.size

        lists.edit(BankTransfer, DescriptionEdit.Remove("SWEEP"))

        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.TransferLeg, fixture.status(paid))
        assertEquals(TransactionStatus.TransferLeg, fixture.status(received))
        assertTrue(slice.journalRecorder.entries.drop(journaled).none { it.details.startsWith("Slussen dropped the Transfer Decision") })
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("UC-005 BR-015: a Confirmed Transfer whose own sides are no longer valid, by a change stored before whose statuses an earlier stop left pending, is dissolved when a list changes, and the dropped decision is recorded with its reason for each Transfer Leg")
    fun aConfirmedTransferWhoseSidesAreNoLongerValidIsDissolved() = runTest {
        val (paid, received) = sweepProposed()
        slice.transferDecisions.decide(paid, TransferChoice.Confirm)
        val reserve = slice.connectionStore.bankAccountList.single { it.name == "Kestrel Reserve" }.id
        // UC-009 A11 stored the removal of Kestrel Reserve's Account Mapping with the marker (O-13), and Slussen stopped before O-5.
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == reserve) it.copy(mapping = null) else it }
        slice.copyStore.pending = true
        val journaled = slice.journalRecorder.entries.size

        lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))

        assertTrue(slice.copyStore.transfers.isEmpty())
        assertNull(slice.importStatusStore.bankTransactions.single { it.id == received }.transferId)
        assertEquals(TransactionStatus.New, fixture.status(paid))
        val dropped = slice.journalRecorder.entries.drop(journaled).filter { it.details.startsWith("Slussen dropped the Transfer Decision Confirmed") }
        assertEquals(2, dropped.size)
        assertTrue(dropped.all { "has no Account Mapping any more" in it.details })
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("BR-009: a Transfer one of whose Transfer Legs has been uploaded keeps its outcome when its description is removed")
    fun anUploadedTransferKeepsItsOutcome() = runTest {
        val (paid, received) = sweepProposed()
        val transfer = slice.copyStore.transfers.single()
        val checking = fixture.budgetAccountIdOf("Checking")
        // A Sent Record as an Upload will store it (UC-006), planted: no Upload exists yet.
        slice.copyStore.sentRecords += StoredSentRecord(paid, checking, sentAsTransfer = true, outcome = SentRecordOutcome.Written)

        lists.edit(BankTransfer, DescriptionEdit.Remove("SWEEP"))

        assertEquals(listOf(transfer), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), slice.importStatusStore.bankTransactions.filter { it.transferId == transfer.id }.map { it.id }.toSet())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("Step 5, UC-003 BR-016: while Household owes a read of its read period, a changed list detects no Transfer there from stored data, and the answer names Household as waiting for its read")
    fun aBudgetThatOwesAReadWaitsForIt() = runTest {
        val (paid, _) = sweep()
        fixture.householdGainsABudgetAccount()

        val outcome = lists.edit(BankTransfer, DescriptionEdit.Add("SWEEP"))

        assertEquals(DescriptionOutcome.Stored(defaultBankTransfer + "SWEEP", SettingEffect.StatusesGiven(listOf("Household"))), outcome)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, fixture.status(paid))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-009"])
    @DisplayName("Step 5, ADR-007 §3a: when the Transfers cannot be detected again now, the list stays stored, the answer says so, and the marker stays until the next start detects them")
    fun transfersThatCannotBeDetectedNowStayPending() = runTest {
        val (paid, _) = sweep()
        slice.copyStore.failsToConclude = true

        assertEquals(DescriptionOutcome.Stored(defaultBankTransfer + "SWEEP", SettingEffect.StatusesPending), lists.edit(BankTransfer, DescriptionEdit.Add("SWEEP")))

        assertEquals(defaultBankTransfer + "SWEEP", stored(BankTransfer))
        assertTrue(slice.copyStore.pending)
        assertEquals(TransactionStatus.New, fixture.status(paid))
        slice.copyStore.failsToConclude = false
        assertTrue(slice.comparison.recomputePending())
        assertEquals(TransactionStatus.TransferLeg, fixture.status(paid))
    }

    // ---- UC-011 BR-001: a long list in the Decision Journal ----------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List")
    @DisplayName("A7 step 3: a change whose lists before and after are longer than an entry's details (4000 characters) is recorded in parts, each within that length, that together hold every entry before and after")
    fun aLongListIsRecordedInParts() = runTest {
        val long = (1..45).map { "ENTRY $it " + "X".repeat(85) }
        slice.copyStore.transferDescriptions.removeAll { it.kind == CardPayment }
        slice.copyStore.transferDescriptions += long.map { StoredTransferDescription(CardPayment, it) }

        lists.edit(CardPayment, DescriptionEdit.Add("PINECREST PMT"))

        val parts = configurationEntries()
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.details.length <= 4000 })
        assertTrue(parts.first().details.startsWith("Card Payment Descriptions: PINECREST PMT added (part 1 of ${parts.size}); "))
        val all = parts.joinToString(" ") { it.details }
        // Each entry once in the list before and once in the list after; the new one only after.
        long.forEach { entry -> assertEquals(2, Regex(Regex.escape(entry)).findAll(all).count(), entry) }
        assertTrue(all.substringAfter("after: ").contains("PINECREST PMT"))
    }
}
