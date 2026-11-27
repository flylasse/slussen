package slussen.core.journal

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.NumberSetting
import slussen.core.port.ReviewChoice
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.review.ReviewOutcome
import slussen.core.settings.SettingOutcome
import slussen.core.settings.StoredStatusesFixture
import slussen.core.settings.SwitchOutcome
import slussen.core.transfer.TransferChoice
import slussen.core.transfer.TransferOutcome
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * The Decision Journal's own control (UC-010 A2, A3, A4, BR-003, BR-004, the success postconditions; ADR-002 §9) on the
 * fake journal database of `:testing`: its size in MB of 1,000,000 bytes, its switch, its size limit and warning
 * threshold, clearing, the trimming after each write; and that no decision waits for it or fails with it. The fake's size
 * is chosen per test ([slussen.testing.InMemoryDecisionJournal.bytesPerEntry]); every name, amount and identifier is
 * invented (C-005).
 */
@DisplayName("UC-010: Adjust Settings, the Decision Journal's own control")
class UC010DecisionJournalControlTest {
    private val fixture = StoredStatusesFixture()
    private val slice = fixture.slice
    private val journal = slice.journalRecorder
    private val control = slice.journalControl

    /** [count] entries of the Session Start kind recorded through the core, as earlier sessions would have. */
    private suspend fun earlierEntries(count: Int) {
        repeat(count) { slice.journal.record(JournalEventKind.SessionStart, "Session $it started") }
    }

    private fun details(): List<String> = journal.entries.map { it.details }

    // ---- Step 1, A4 ---------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-003"])
    @DisplayName("Step 1, BR-001: a new installation shows the Decision Journal on, a size limit of 50 MB, a warning threshold of 80% and its size as journal.db takes it")
    fun theDecisionJournalIsShownWithItsDefaults() = runTest {
        journal.baseBytes = 12_400_000

        assertEquals(JournalShown(on = true, sizeLimitMb = 50, warningPercent = 80, sizeBytes = 12_400_000), control.shown())
        assertFalse(control.shown().nearLimit)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Step 1, ADR-002 §9: the size is in MB of 1,000,000 bytes with one decimal, rounded to the nearest tenth")
    fun theSizeIsInMegabytesOfAMillionBytes() {
        assertEquals(124, JournalRules.tenthsOfMegabyte(12_449_999))
        assertEquals(125, JournalRules.tenthsOfMegabyte(12_450_000))
        assertEquals(0, JournalRules.tenthsOfMegabyte(0))
        assertEquals(5_000, JournalRules.tenthsOfMegabyte(500_000_000))
        assertEquals(50_000_000, JournalRules.limitBytes(50))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-003"])
    @DisplayName("A4: the Decision Journal is near its limit once its size reaches the warning threshold of its size limit, 40 MB of 50 MB at 80%, and not 1 byte before")
    fun nearItsLimitAtTheWarningThreshold() = runTest {
        journal.baseBytes = 40_000_000
        assertTrue(control.shown().nearLimit)

        journal.baseBytes = 39_999_999
        assertFalse(control.shown().nearLimit)

        assertEquals(SettingOutcome.Stored(70), control.changeWarningThreshold(70))
        journal.baseBytes = 35_000_000
        assertTrue(control.shown().nearLimit)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("A4, BR-004: a size that cannot be read is shown as not known and never as near the limit; the other values are shown")
    fun aSizeThatCannotBeReadIsNotKnown() = runTest {
        journal.failsToReadSize = true

        assertEquals(JournalShown(on = true, sizeLimitMb = 50, warningPercent = 80, sizeBytes = null), control.shown())
        assertFalse(control.shown().nearLimit)
    }

    // ---- BR-003: trimming after each write ------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: every entry is handed over with the size limit in bytes of 1,000,000 per MB, so that after it is written the oldest entries are removed until the Decision Journal is within its limit")
    fun afterAWriteTheOldestEntriesAreRemovedUntilWithinTheLimit() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(50)
        assertEquals(50, journal.entries.size)

        slice.journal.record(JournalEventKind.SessionStart, "Session 50 started")

        assertEquals(listOf<Long?>(50_000_000), journal.limitsHandedOver.distinct())
        assertEquals((1..50).map { "Session $it started" }, details())
    }

    // ---- The switch: postconditions --------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Postconditions: switching the Decision Journal off is its last entry, a Configuration Change handed to the recorder before journal_on is stored; nothing is recorded after it")
    fun switchingOffIsTheLastEntryHandedOverBeforeItIsStored() = runTest {
        var handedOverBeforeTheStore: List<String>? = null
        slice.settings.beforeStore = { handedOverBeforeTheStore = details() }

        assertEquals(SwitchOutcome.Stored(false), control.switch(false))

        assertEquals(listOf("Decision Journal switched off"), handedOverBeforeTheStore)
        assertFalse(slice.settings.settings.journalOn)
        assertEquals(JournalEventKind.ConfigurationChange, journal.entries.single().eventKind)
        slice.generalSettings.change(NumberSetting.DefaultWait, 9)
        assertEquals(listOf("Decision Journal switched off"), details())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Postconditions: switching the Decision Journal on is stored first and recorded as its first entry after; nothing of the time it was off is in it")
    fun switchingOnIsTheFirstEntryAfter() = runTest {
        control.switch(false)
        slice.generalSettings.change(NumberSetting.DefaultWait, 9)

        assertEquals(SwitchOutcome.Stored(true), control.switch(true))
        slice.generalSettings.change(NumberSetting.DefaultWait, 10)

        assertTrue(slice.settings.settings.journalOn)
        assertEquals(
            listOf("Decision Journal switched off", "Decision Journal switched on", "Default wait changed from 9 to 10 hours"),
            details(),
        )
        assertEquals(JournalEventKind.ConfigurationChange, journal.entries[1].eventKind)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Failure postconditions: when switching off cannot be stored, the Decision Journal stays on and says after its off entry that it was not switched off")
    fun switchingOffThatCannotBeStoredKeepsItOnAndSaysSo() = runTest {
        slice.settings.failsToStore = true

        assertEquals(SwitchOutcome.NotStored, control.switch(false))

        assertTrue(slice.settings.settings.journalOn)
        assertEquals(
            listOf("Decision Journal switched off", "Decision Journal not switched off: the change could not be stored, so it stays on"),
            details(),
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: switching the Decision Journal off or on removes no entry, also when its entry takes it above its size limit")
    fun switchingOffOrOnRemovesNoEntry() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(50)

        control.switch(false)
        control.switch(true)

        assertEquals(52, journal.entries.size)
        assertEquals("Session 0 started", journal.entries.first().details)
        assertEquals(0, journal.removed)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Steps 2-4: switching to the state it has stores, raises and records nothing")
    fun switchingToTheSameStateIsUnchanged() = runTest {
        val version = slice.stateVersion.value

        assertEquals(SwitchOutcome.Unchanged(true), control.switch(true))

        assertEquals(version, slice.stateVersion.value)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Precondition: while an Upload Plan awaits confirmation the switch is unavailable; nothing is stored or recorded")
    fun theSwitchIsUnavailableWhileAPlanIsOpen() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertEquals(SwitchOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), control.switch(false))

        assertTrue(slice.settings.settings.journalOn)
        assertTrue(journal.entries.isEmpty())
    }

    // ---- A2: the size limit ------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("A2 step 1: a size limit below the current size is not stored before the Operator confirms; the answer names the current size, and nothing is removed or recorded")
    fun aLimitBelowTheSizeAsksFirst() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(12)
        val version = slice.stateVersion.value

        assertEquals(SizeLimitOutcome.ConfirmFirst(10, sizeBytes = 12_000_000), control.changeSizeLimit(10))

        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
        assertEquals(version, slice.stateVersion.value)
        assertEquals(12, journal.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("A2 step 1: when the size cannot be read, a lower size limit asks first too, saying that the size is not known")
    fun aLowerLimitAsksFirstWhenTheSizeIsNotKnown() = runTest {
        journal.failsToReadSize = true

        assertEquals(SizeLimitOutcome.ConfirmFirst(40, sizeBytes = null), control.changeSizeLimit(40))
        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("A2 steps 2-3, BR-003: confirmed, the lower size limit is stored and recorded, and right after it the oldest entries are removed until the Decision Journal is within 10 MB of 1,000,000 bytes")
    fun aConfirmedLowerLimitRemovesTheOldestEntriesUntilWithin() = runTest {
        journal.bytesPerEntry = 100_000
        earlierEntries(150)

        val outcome = control.changeSizeLimit(10, confirmed = true)

        assertEquals(SizeLimitOutcome.Stored(10, Trimming.Done(removed = 51, sizeBytes = 10_000_000)), outcome)
        assertEquals(10, slice.settings.settings.journalSizeLimitMb)
        assertEquals(100, journal.entries.size)
        assertEquals((51..149).map { "Session $it started" } + "Decision Journal size limit changed from 50 to 10 MB", details())
        assertEquals(JournalEventKind.ConfigurationChange, journal.entries.last().eventKind)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("BR-003: a lower size limit stored while the Decision Journal is off removes the oldest entries too, and records nothing")
    fun aLowerLimitWhileOffTrimsAndRecordsNothing() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(20)
        control.switch(false)

        assertEquals(SizeLimitOutcome.Stored(15, Trimming.Done(removed = 6, sizeBytes = 15_000_000)), control.changeSizeLimit(15, confirmed = true))

        assertEquals((6..19).map { "Session $it started" } + "Decision Journal switched off", details())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("BR-003: a lower size limit the Decision Journal is within needs no confirmation and removes no entry")
    fun aLowerLimitWithinTheSizeRemovesNothing() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(12)

        assertEquals(SizeLimitOutcome.Stored(20, Trimming.Done(removed = 0, sizeBytes = 13_000_000)), control.changeSizeLimit(20))

        assertEquals(13, journal.entries.size)
        assertEquals(0, journal.removed)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003: raising the size limit removes no entry, even while the Decision Journal is above the higher limit; the entry of the change is handed over to remove nothing")
    fun raisingTheLimitRemovesNothing() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(5)
        journal.baseBytes = 200_000_000

        assertEquals(SizeLimitOutcome.Stored(100, Trimming.NoneNeeded), control.changeSizeLimit(100))

        assertEquals(6, journal.entries.size)
        assertEquals(0, journal.removed)
        assertEquals(null, journal.limitsHandedOver.last())
        assertEquals("Decision Journal size limit changed from 50 to 100 MB", details().last())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-002", "BR-003"])
    @DisplayName("A1, BR-002: a size limit outside 10 to 500 MB is refused with the range; nothing is stored, removed or recorded")
    fun aLimitOutsideTheRangeIsRefused() = runTest {
        assertEquals(SizeLimitOutcome.OutsideRange(10..500), control.changeSizeLimit(9, confirmed = true))
        assertEquals(SizeLimitOutcome.OutsideRange(10..500), control.changeSizeLimit(501, confirmed = true))

        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("Steps 2-4: the size limit it has already is unchanged: nothing is stored, removed or recorded")
    fun theSameLimitIsUnchanged() = runTest {
        assertEquals(SizeLimitOutcome.Unchanged(50), control.changeSizeLimit(50, confirmed = true))
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("Failure postconditions: a lower size limit that cannot be stored keeps the previous one and removes and records nothing")
    fun aLimitThatCannotBeStoredRemovesNothing() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(12)
        slice.settings.failsToStore = true

        assertEquals(SizeLimitOutcome.NotStored, control.changeSizeLimit(10, confirmed = true))

        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
        assertEquals(12, journal.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-004: a lower size limit is stored also when the journal's database cannot trim; the answer says that the oldest entries were not removed")
    fun aLowerLimitIsStoredWhenTrimmingFails() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(12)
        journal.failsToTrim = true

        assertEquals(SizeLimitOutcome.Stored(10, Trimming.Failed), control.changeSizeLimit(10, confirmed = true))
        assertEquals(10, slice.settings.settings.journalSizeLimitMb)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A2: Lower the Decision Journal Size Limit", businessRules = ["BR-003"])
    @DisplayName("Precondition: while an Upload Plan awaits confirmation a lower size limit is unavailable; nothing is stored or removed")
    fun aLowerLimitIsUnavailableWhileAPlanIsOpen() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(12)
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertEquals(SizeLimitOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), control.changeSizeLimit(10, confirmed = true))

        assertEquals(50, slice.settings.settings.journalSizeLimitMb)
        assertEquals(12, journal.entries.size)
    }

    // ---- The warning threshold ----------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-002", "BR-003"])
    @DisplayName("A4, BR-002, BR-003: the warning threshold lies between 50% and 95%; a change is stored and recorded, and removes no entry even while the Decision Journal is above its limit")
    fun theWarningThresholdIsStoredAndRemovesNothing() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(3)
        journal.baseBytes = 60_000_000

        assertEquals(SettingOutcome.OutsideRange(50..95), control.changeWarningThreshold(49))
        assertEquals(SettingOutcome.OutsideRange(50..95), control.changeWarningThreshold(96))
        assertEquals(SettingOutcome.Unchanged(80), control.changeWarningThreshold(80))
        assertEquals(SettingOutcome.Stored(95), control.changeWarningThreshold(95))

        assertEquals(95, slice.settings.settings.journalWarningPercent)
        assertEquals(0, journal.removed)
        assertEquals("Decision Journal warning threshold changed from 80% to 95%", details().last())
        assertEquals(null, journal.limitsHandedOver.last())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A4: Decision Journal Near Its Limit", businessRules = ["BR-003"])
    @DisplayName("Failure postconditions: a warning threshold that cannot be stored keeps the previous one and records nothing")
    fun aWarningThresholdThatCannotBeStored() = runTest {
        slice.settings.failsToStore = true

        assertEquals(SettingOutcome.NotStored, control.changeWarningThreshold(60))
        assertEquals(80, slice.settings.settings.journalWarningPercent)
        assertTrue(journal.entries.isEmpty())
    }

    // ---- A3: clearing -------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("A3 step 3: clearing removes every entry and frees their space, records the clearing as its first new entry, a Journal Control, and changes nothing else")
    fun clearingRemovesEveryEntryAndRecordsItFirst() = runTest {
        journal.bytesPerEntry = 1_000_000
        earlierEntries(30)
        val settingsBefore = slice.settings.settings
        val version = slice.stateVersion.value

        assertEquals(ClearOutcome.Cleared(recorded = true, sizeBytes = 1_000_000), control.clear())

        assertEquals(listOf("Decision Journal cleared"), details())
        assertEquals(JournalEventKind.JournalControl, journal.entries.single().eventKind)
        assertEquals(settingsBefore, slice.settings.settings)
        assertEquals(version, slice.stateVersion.value)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("A3 step 3: clearing while the Decision Journal is off removes every entry and records nothing")
    fun clearingWhileOffRecordsNothing() = runTest {
        earlierEntries(4)
        control.switch(false)

        assertEquals(ClearOutcome.Cleared(recorded = false, sizeBytes = 0), control.clear())

        assertTrue(journal.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("A3: a clearing the journal's database cannot do is answered not cleared; every entry is kept and nothing is recorded")
    fun aClearingThatFailsKeepsEveryEntry() = runTest {
        earlierEntries(4)
        journal.failsToClear = true

        assertEquals(ClearOutcome.NotCleared, control.clear())

        assertEquals((0..3).map { "Session $it started" }, details())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("Precondition: while an Upload is written clearing is unavailable; nothing is removed")
    fun clearingIsUnavailableWhileAnUploadIsWritten() = runTest {
        earlierEntries(4)
        slice.coordinatorStore.mode = CoordinatorMode.Uploading

        assertEquals(ClearOutcome.Unavailable(OperationRefusal.UploadBeingWritten), control.clear())

        assertEquals(4, journal.entries.size)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A3: Clear the Decision Journal", businessRules = ["BR-004"])
    @DisplayName("A3: clearing does not hold the operation lane while the journal's database works: a decision can run meanwhile")
    fun clearingDoesNotHoldTheLane() = runTest {
        earlierEntries(4)
        var laneFree = false
        journal.beforeClear = { laneFree = slice.coordinator.running.value == null }

        control.clear()

        assertTrue(laneFree)
    }

    // ---- BR-004: no decision waits for the Decision Journal or fails with it ----------------------------------------

    /** Household's Kestrel Checking holds a Bank Transaction without a Stable Reference, compared: Needs Review. */
    private suspend fun doubtful(): Long {
        fixture.readOnce()
        val checking = slice.connectionStore.bankAccountList.single { it.name == "Kestrel Checking" }.id
        val doubtful = fixture.stored(checking, "TX-DOUBT", fixture.oct13, amount(-7))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == doubtful) it.copy(stableReference = null) else it }
        fixture.compare()
        assertEquals(TransactionStatus.NeedsReview, fixture.status(doubtful))
        return doubtful
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-004: a Review Decision taken while the journal's database fails, to write, to trim and to read its size, is stored with its status")
    fun aReviewDecisionIsStoredWhileTheJournalDatabaseFails() = runTest {
        val doubtful = doubtful()
        journal.failsToRecord = true
        journal.failsToTrim = true
        journal.failsToReadSize = true

        val outcome = slice.review.decide(doubtful, ReviewChoice.ImportAnyway)

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.New), outcome)
        assertEquals(ReviewChoice.ImportAnyway, slice.copyStore.reviewDecisions.single().choice)
        assertEquals(TransactionStatus.New, fixture.status(doubtful))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-003, BR-004: a Transfer Decision taken while the Decision Journal is above its limit and cannot trim is stored; its entries are written and the oldest stay")
    fun aTransferDecisionIsStoredWhileTheJournalCannotTrim() = runTest {
        fixture.ready()
        val checking = fixture.bankAccount("Kestrel Checking", "Checking")
        val card = fixture.bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard)
        slice.connectionStore.link(card, fundedBy = fixture.budgetAccountIdOf("Checking"))
        val paid = fixture.stored(checking, "TX-PAID", LocalDate.of(2026, 10, 12), amount(-250))
        fixture.stored(card, "TX-RECEIVED", fixture.oct13, amount(250))
        fixture.compare()
        journal.baseBytes = 60_000_000
        journal.failsToTrim = true

        val outcome = slice.transferDecisions.decide(paid, TransferChoice.Hold)

        assertTrue(outcome is TransferOutcome.Stored)
        assertEquals(TransferDecision.Held, slice.copyStore.transfers.single().decision)
        assertEquals(TransactionStatus.HeldTransfer, fixture.status(paid))
        assertTrue(journal.entries.any { it.eventKind == JournalEventKind.OperatorDecision })
        assertEquals(0, journal.removed)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-006"])
    @DisplayName("BR-003, BR-004: a decision does not wait for the trimming after its entries are written; it is stored while the trimming still waits, and the trimming runs after")
    fun aDecisionDoesNotWaitForTheJournalsTrimming() = runTest {
        val doubtful = doubtful()
        journal.bytesPerEntry = 1_000_000
        journal.baseBytes = 49_500_000
        journal.holdsTrimming = true
        val before = journal.entries.size

        assertEquals(ReviewOutcome.Stored(ReviewChoice.ImportAnyway, TransactionStatus.New), slice.review.decide(doubtful, ReviewChoice.ImportAnyway))
        assertEquals(0, journal.removed)
        assertTrue(journal.entries.size > before)

        journal.runHeldTrimming()
        assertTrue(journal.removed > 0)
        assertTrue(control.shown().sizeBytes!! <= 50_000_000)
    }
}
