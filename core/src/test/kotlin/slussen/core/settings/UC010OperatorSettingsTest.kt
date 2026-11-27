package slussen.core.settings

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEventKind
import slussen.core.port.NumberSetting
import slussen.core.port.SwitchSetting
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.transfer.TransferChoice
import slussen.core.transfer.TransferOutcome
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * Adjust Settings (UC-010) for every Operator Setting that applies to everything and has no editor of its own elsewhere:
 * each whole number with its default (BR-001) and range (BR-002, A1), stored through O-13 with `state_version` raised
 * and recorded with the value before and after (steps 4, 6, UC-011 BR-001), a value that cannot be stored answered as
 * such (failure postconditions); the statuses given again from stored data where a Transaction Status depends on the
 * setting and not where none does (step 5); Detailed Logging (A5) and Upload as Cleared (A9) as switches. The case of a
 * longer read period is in `UC003PeriodReadOwedTest`. Every name, amount and identifier is invented (C-005).
 */
@DisplayName("UC-010: Adjust Settings, every Operator Setting that applies to everything")
class UC010OperatorSettingsTest {
    private val fixture = StoredStatusesFixture()
    private val slice = fixture.slice
    private val settings = slice.generalSettings

    /** BR-001 and BR-002 as UC-010 states them: the default and the allowed range of each, written here from the rule. */
    private val ruled: Map<NumberSetting, Pair<Int, IntRange>> = mapOf(
        NumberSetting.UploadWindow to (7 to 1..60),
        NumberSetting.FileUploadWindow to (60 to 7..365),
        NumberSetting.FetchPeriod to (30 to 7..180),
        NumberSetting.FetchSchedule to (2 to 0..2),
        NumberSetting.DefaultWait to (8 to 1..48),
        NumberSetting.RequestLimitWarningPause to (24 to 1..72),
        NumberSetting.RequestTimeout to (30 to 10..120),
        NumberSetting.UploadPlanLifetime to (10 to 1..60),
        NumberSetting.ConsentRequestLifetime to (15 to 5..30),
        NumberSetting.RecentFetchesKept to (60 to 10..500),
        NumberSetting.TransferPairingDays to (5 to 0..30),
        NumberSetting.LonelyTransferGracePeriod to (6 to 0..30),
        NumberSetting.OutdatedBalanceLimit to (36 to 1..720),
        NumberSetting.ConsentWarningLeadTime to (7 to 1..60),
        NumberSetting.BackupWarningThreshold to (80 to 50..95),
    )

    /** The journal's words for the change of each from its default to [value], as UC-011 BR-001 asks: before and after. */
    private fun journaled(setting: NumberSetting, value: Int): String {
        val default = ruled.getValue(setting).first
        return when (setting) {
            NumberSetting.UploadWindow -> "Upload Window changed from $default to $value days"
            NumberSetting.FileUploadWindow -> "File upload window changed from $default to $value days"
            NumberSetting.FetchPeriod -> "Fetch period changed from $default to $value days"
            NumberSetting.FetchSchedule -> "Fetch Schedule changed from $default to $value Scheduled Fetches in any 24 hours"
            NumberSetting.DefaultWait -> "Default wait changed from $default to $value hours"
            NumberSetting.RequestLimitWarningPause -> "Request Limit warning pause changed from $default to $value hours"
            NumberSetting.RequestTimeout -> "Request timeout changed from $default to $value seconds"
            NumberSetting.UploadPlanLifetime -> "Upload Plan lifetime changed from $default to $value minutes"
            NumberSetting.ConsentRequestLifetime -> "Consent Request lifetime changed from $default to $value minutes"
            NumberSetting.RecentFetchesKept -> "Number of recent Fetches kept changed from $default to $value"
            NumberSetting.TransferPairingDays -> "Transfer pairing days changed from $default to $value days"
            NumberSetting.LonelyTransferGracePeriod -> "Lonely Transfer grace period changed from $default to $value days"
            NumberSetting.OutdatedBalanceLimit -> "Outdated-balance limit changed from $default to $value hours"
            NumberSetting.ConsentWarningLeadTime -> "Consent warning lead time changed from $default to $value days"
            NumberSetting.BackupWarningThreshold -> "Backup warning threshold changed from $default% to $value%"
        }
    }

    @ParameterizedTest
    @EnumSource(NumberSetting::class)
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-002"])
    @DisplayName("Step 1, BR-001, BR-002: a new installation shows each Operator Setting with the default and the allowed range UC-010 states")
    fun eachSettingIsShownWithItsDefaultAndRange(setting: NumberSetting) = runTest {
        val (default, range) = ruled.getValue(setting)

        assertEquals(NumberSettingShown(setting, default, default, range), settings.shown().number(setting))
    }

    @ParameterizedTest
    @EnumSource(NumberSetting::class)
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-006"])
    @DisplayName("Steps 2-4, 6, BR-002: each bound of the range is stored in one storage operation that raises state_version, shown, and recorded with the value before and after; a setting a status depends on is followed by the conclusions")
    fun eachBoundIsStoredAndRecorded(setting: NumberSetting) = runTest {
        val range = ruled.getValue(setting).second
        val bound = if (range.last == ruled.getValue(setting).first) range.first else range.last
        val version = slice.stateVersion.value

        val outcome = settings.change(setting, bound)

        assertEquals(bound, (outcome as SettingOutcome.Stored).value)
        assertEquals(bound, setting.valueIn(slice.settings.settings))
        assertEquals(bound, settings.shown().number(setting).value)
        // ADR-007 §6 step 2: the storage operation raises state_version once, and the conclusions that follow it (O-5) once more.
        val followedByConclusions = setting in setOf(NumberSetting.UploadWindow, NumberSetting.TransferPairingDays, NumberSetting.LonelyTransferGracePeriod)
        assertEquals(version + if (followedByConclusions) 2 else 1, slice.stateVersion.value)
        val entry = slice.journalRecorder.entries.single()
        assertEquals(JournalEventKind.ConfigurationChange, entry.eventKind)
        assertEquals(journaled(setting, bound), entry.details)
    }

    @ParameterizedTest
    @EnumSource(NumberSetting::class)
    @UseCase(id = "UC-010", scenario = "A1: Value Outside the Allowed Range", businessRules = ["BR-002"])
    @DisplayName("A1: one below and one above the range are refused with the range; the previous value is kept and nothing is stored or recorded")
    fun aValueOutsideTheRangeIsRefused(setting: NumberSetting) = runTest {
        val (default, range) = ruled.getValue(setting)
        val version = slice.stateVersion.value

        assertEquals(SettingOutcome.OutsideRange(range), settings.change(setting, range.first - 1))
        assertEquals(SettingOutcome.OutsideRange(range), settings.change(setting, range.last + 1))

        assertEquals(default, setting.valueIn(slice.settings.settings))
        assertEquals(version, slice.stateVersion.value)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(NumberSetting::class)
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Failure postconditions: a value that cannot be stored is answered not stored; the previous value is kept and nothing is recorded")
    fun aValueThatCannotBeStoredIsAnsweredNotStored(setting: NumberSetting) = runTest {
        val (default, range) = ruled.getValue(setting)
        slice.settings.failsToStore = true

        assertEquals(SettingOutcome.NotStored, settings.change(setting, if (default == range.last) range.first else range.last))

        assertEquals(default, setting.valueIn(slice.settings.settings))
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Failure postconditions: when the Operator Settings cannot be read before the change, nothing is stored and the change is answered not stored")
    fun aChangeWhoseSettingsCannotBeReadIsNotStored() = runTest {
        slice.settings.failsToRead = true

        assertEquals(SettingOutcome.NotStored, settings.change(NumberSetting.DefaultWait, 12))

        slice.settings.failsToRead = false
        assertEquals(8, slice.settings.settings.defaultWaitHours)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation or an Upload is written, no Operator Setting and no switch can be changed")
    fun unavailableWhileAPlanAwaitsConfirmation() = runTest {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        assertEquals(SettingOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), settings.change(NumberSetting.UploadWindow, 10))
        assertEquals(SwitchOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), settings.switch(SwitchSetting.DetailedLogging, true))
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(SettingOutcome.Unavailable(OperationRefusal.UploadBeingWritten), settings.change(NumberSetting.UploadWindow, 10))

        assertEquals(7, slice.settings.settings.uploadWindowDays)
        assertFalse(slice.settings.settings.detailedLogging)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6, BR-002: the Fetch Schedule of 0 switches the Scheduled Fetches off and is stored; 3 is refused")
    fun fetchScheduleOfZeroIsOff() = runTest {
        assertEquals(SettingOutcome.Stored(0), settings.change(NumberSetting.FetchSchedule, 0))
        assertEquals(0, slice.settings.settings.scheduledFetchesPerDay)
        assertEquals(SettingOutcome.OutsideRange(0..2), settings.change(NumberSetting.FetchSchedule, 3))
    }

    // ---- Step 5: the statuses given again where a Transaction Status depends on the setting ------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", requirements = ["NFR-006"])
    @DisplayName("Step 5: a longer Upload Window within the read period gives every stored Bank Transaction its status again from stored data: the one of 9 days ago is New under 10 days; nothing is read, the time of the read stays, and the changed status is recorded")
    fun aLongerUploadWindowGivesTheStatusesAgain() = runTest {
        val (recent, old, careOld) = fixture.readOnce()
        val household = slice.connectionStore.budgets().single { it.name == "Household" }
        val reads = fixture.readsFromOutside()
        val journaled = slice.journalRecorder.entries.size

        val outcome = settings.change(NumberSetting.UploadWindow, 10)

        assertEquals(SettingOutcome.Stored(10, SettingEffect.StatusesGiven), outcome)
        assertEquals(TransactionStatus.New, fixture.status(recent))
        assertEquals(TransactionStatus.New, fixture.status(old))
        assertEquals(TransactionStatus.New, fixture.status(careOld))
        assertEquals(reads, fixture.readsFromOutside())
        assertEquals(household.lastReadAt, slice.connectionStore.budgets().single { it.name == "Household" }.lastReadAt)
        assertFalse(slice.copyStore.pending)
        val entries = slice.journalRecorder.entries.drop(journaled)
        assertEquals(JournalEventKind.ConfigurationChange, entries.first().eventKind)
        assertEquals(2, entries.count { it.eventKind == JournalEventKind.StatusChanged })
        assertTrue(entries.filter { it.eventKind == JournalEventKind.StatusChanged }.any { it.importIdentifier == fixture.importIdentifierOf("TX-OLD") })
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5: a shorter Upload Window gives the statuses again: the Bank Transaction of 2 days ago is Too Old under 1 day")
    fun aShorterUploadWindowGivesTheStatusesAgain() = runTest {
        val (recent, _, _) = fixture.readOnce()

        assertEquals(SettingOutcome.Stored(1, SettingEffect.StatusesGiven), settings.change(NumberSetting.UploadWindow, 1))

        assertEquals(TransactionStatus.TooOld, fixture.status(recent))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Step 5: a Bank Transaction stored after the Budget's last read stays not compared yet when the statuses are given again")
    fun notComparedYetStaysNotComparedYet() = runTest {
        fixture.readOnce()
        val checking = slice.connectionStore.bankAccountList.single { it.name == "Kestrel Checking" }.id
        val later = fixture.stored(checking, "TX-LATER", fixture.oct13, amount(-5))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == later) it.copy(firstSeenAt = fixture.now.plusSeconds(60)) else it }

        settings.change(NumberSetting.UploadWindow, 10)

        assertNull(fixture.status(later))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5: a setting no Transaction Status depends on gives no status again: the default wait is stored, and a stored copy that would now make the Bank Transaction Already In Budget App leaves it New")
    fun aSettingNoStatusDependsOnGivesNoStatusAgain() = runTest {
        val (recent, _, _) = fixture.readOnce()
        val checking = fixture.budgetAccountIdOf("Checking")
        slice.copyStore.copy += slussen.core.port.StoredBudgetAppTransaction(
            id = 98, budgetAccountId = checking, budgetAppReference = "ynab-recent", transactionDate = fixture.oct13, amount = amount(-12),
            payee = "PLANTED-PAYEE", memo = null, importIdentifier = fixture.importIdentifierOf("TX-RECENT"),
            enteredBy = slussen.core.port.EnteredBy.Slussen, transferLinkReference = null, mergedIntoReference = null, cleared = true,
            approved = true, state = slussen.core.port.BudgetAppTransactionState.Present,
        )

        for (setting in listOf(NumberSetting.DefaultWait, NumberSetting.RequestTimeout, NumberSetting.OutdatedBalanceLimit, NumberSetting.FetchSchedule)) {
            assertEquals(SettingEffect.None, (settings.change(setting, ruled.getValue(setting).second.first) as SettingOutcome.Stored).effect)
        }

        assertEquals(TransactionStatus.New, fixture.status(recent))
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5, ADR-007 §3a: when the statuses cannot be given again now, the value stays stored, the change says so, and the Comparison marker stays set so that no Upload Plan is prepared until they are")
    fun statusesThatCannotBeGivenNowStayPending() = runTest {
        val (_, old, _) = fixture.readOnce()
        slice.copyStore.failsToConclude = true

        val outcome = settings.change(NumberSetting.UploadWindow, 10)

        assertEquals(SettingOutcome.Stored(10, SettingEffect.StatusesPending), outcome)
        assertEquals(10, slice.settings.settings.uploadWindowDays)
        assertTrue(slice.copyStore.pending)
        assertEquals(TransactionStatus.TooOld, fixture.status(old))
        slice.copyStore.failsToConclude = false
        assertTrue(slice.comparison.recomputePending())
        assertEquals(TransactionStatus.New, fixture.status(old))
    }

    /**
     * Kestrel Checking paid 250.00 on 2026-10-12 to Kestrel Rewards, whose Funded By is Checking, received on 2026-10-13: a
     * Transfer Slussen proposes with the transfer pairing days of 5. Returns (paid, received).
     */
    private suspend fun cardPayment(): Pair<Long, Long> {
        fixture.ready()
        val checking = fixture.bankAccount("Kestrel Checking", "Checking")
        val card = fixture.bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard)
        slice.connectionStore.link(card, fundedBy = fixture.budgetAccountIdOf("Checking"))
        val paid = fixture.stored(checking, "TX-PAID", LocalDate.of(2026, 10, 12), amount(-250))
        val received = fixture.stored(card, "TX-RECEIVED", fixture.oct13, amount(250))
        fixture.compare()
        assertEquals(TransactionStatus.TransferLeg, fixture.status(paid))
        return paid to received
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5, UC-005 BR-015: transfer pairing days of 0 no longer pair Transfer Legs a day apart: a Transfer Slussen proposed is detected again and dissolved")
    fun fewerPairingDaysDissolveAProposedTransfer() = runTest {
        val (paid, received) = cardPayment()

        assertEquals(SettingOutcome.Stored(0, SettingEffect.StatusesGiven), settings.change(NumberSetting.TransferPairingDays, 0))

        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, fixture.status(paid))
        assertEquals(TransactionStatus.New, fixture.status(received))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5, UC-005 BR-015, S-B-17: a Transfer the Operator holds back is never dissolved by a changed setting: with transfer pairing days of 0 it stays held with both Transfer Legs")
    fun aHeldTransferSurvivesAChangedSetting() = runTest {
        val (paid, received) = cardPayment()
        assertEquals(TransferOutcome.Stored(TransferDecision.Held, lonely = false, kept = null, detectedAgain = true), slice.transferDecisions.decide(paid, TransferChoice.Hold))
        val held = slice.copyStore.transfers.single()

        settings.change(NumberSetting.TransferPairingDays, 0)
        settings.change(NumberSetting.LonelyTransferGracePeriod, 0)

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.HeldTransfer, fixture.status(paid))
        assertEquals(TransactionStatus.HeldTransfer, fixture.status(received))
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario")
    @DisplayName("Step 5, UC-005 BR-015, S-B-17: a Transfer the Operator split is never dissolved by a changed setting")
    fun aSplitTransferSurvivesAChangedSetting() = runTest {
        val (paid, _) = cardPayment()
        slice.transferDecisions.decide(paid, TransferChoice.Split)
        val split = slice.copyStore.transfers.single()

        settings.change(NumberSetting.TransferPairingDays, 0)

        assertEquals(listOf(split), slice.copyStore.transfers.toList())
        assertEquals(TransferDecision.Split, slice.copyStore.transfers.single().decision)
    }

    // ---- A5, A9: the switches ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-001", "BR-005"])
    @DisplayName("A5, BR-001: Detailed Logging is off by default; switched on it is stored, applied to what reads it outside the core, and recorded; switched off again likewise; no Transaction Status is given again")
    fun detailedLoggingIsSwitchedOnAndOff() = runTest {
        assertFalse(settings.shown().values.detailedLogging)

        assertEquals(SwitchOutcome.Stored(true), settings.switch(SwitchSetting.DetailedLogging, true))
        assertTrue(slice.settings.settings.detailedLogging)
        assertTrue(slice.detailedLoggingApplied)
        assertEquals(SwitchOutcome.Stored(false), settings.switch(SwitchSetting.DetailedLogging, false))
        assertFalse(slice.detailedLoggingApplied)

        assertEquals(listOf("Detailed Logging switched on", "Detailed Logging switched off"), slice.journalRecorder.entries.map { it.details })
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-005"])
    @DisplayName("A5, failure postconditions: Detailed Logging that cannot be stored is answered not stored; it stays off, also for the log, and nothing is recorded")
    fun detailedLoggingNotStoredStaysOff() = runTest {
        slice.settings.failsToStore = true

        assertEquals(SwitchOutcome.NotStored, settings.switch(SwitchSetting.DetailedLogging, true))

        assertFalse(slice.settings.settings.detailedLogging)
        assertFalse(slice.detailedLoggingApplied)
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A9: Switch Upload as Cleared", businessRules = ["BR-001"])
    @DisplayName("A9, BR-001: Upload as Cleared is on by default; switched off it is stored and recorded, and changes no Transaction Status")
    fun uploadAsClearedIsSwitchedOff() = runTest {
        val (recent, _, _) = fixture.readOnce()
        assertTrue(settings.shown().values.uploadAsCleared)
        val version = slice.stateVersion.value

        assertEquals(SwitchOutcome.Stored(false), settings.switch(SwitchSetting.UploadAsCleared, false))

        assertFalse(slice.settings.settings.uploadAsCleared)
        assertEquals(version + 1, slice.stateVersion.value)
        assertEquals("Upload as Cleared switched off", slice.journalRecorder.entries.last().details)
        assertEquals(TransactionStatus.New, fixture.status(recent))
        assertFalse(slice.copyStore.pending)
    }
}
