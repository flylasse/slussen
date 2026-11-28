package slussen.core.comparison

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.mapping.MappingChangeOutcome
import slussen.core.operation.OperationRefusal
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.CoordinatorMode
import slussen.core.port.EnteredBy
import slussen.core.port.NumberSetting
import slussen.core.port.StoredBudgetAppTransaction
import slussen.core.port.TransactionStatus
import slussen.core.settings.SettingEffect
import slussen.core.settings.SettingOutcome
import slussen.core.settings.StoredStatusesFixture
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData.amount
import java.time.LocalDate

/**
 * UC-003 BR-016 as the review of Wave 3's specification corrected it (S-B-21; ADR-007 §3a; ADR-002 §2, O-13): a change
 * that makes the read period longer leaves days that Slussen's copy of the Budget App never covered, where a Bank
 * Transaction would look New beside its own copy in the Budget App. Such a change owes each served Budget read before a
 * read of the period, and while a Budget owes it, no conclusion from stored data changes its Transaction Statuses: not
 * UC-010 step 5, not UC-009's statuses again, not a marker left for another reason, also in the UC-013 case of a Budget
 * that gains a Budget Account. Only a Comparison that reads the Budget with its period gives them again. Every name,
 * amount and identifier is invented (C-005).
 */
@DisplayName("UC-003: Compare With the Budget App, a read of the read period owed")
class UC003PeriodReadOwedTest {
    private val fixture = StoredStatusesFixture()
    private val slice = fixture.slice
    private val today: LocalDate = LocalDate.of(2026, 10, 15)
    private val matchingDays: Long = slice.plugins.budgetApp.profile.matchingDateWindowDays.toLong()

    private suspend fun owed(name: String): Boolean = slice.copyStore.periodReadOwed(fixture.budgetId(name))

    /**
     * The read period is the Upload Window alone (fetch period and file upload window lowered to 7 days), and the Upload
     * Window is raised from 7 to 10 days: the read period becomes longer, so Household and Care Fund owe a read.
     */
    private suspend fun uploadWindowRaisedBeyondTheReadPeriod(): Triple<Long, Long, Long> {
        val read = fixture.readOnce()
        assertTrue(slice.generalSettings.change(NumberSetting.FetchPeriod, 7) is SettingOutcome.Stored)
        assertTrue(slice.generalSettings.change(NumberSetting.FileUploadWindow, 7) is SettingOutcome.Stored)
        assertFalse(owed("Household"))
        val outcome = slice.generalSettings.change(NumberSetting.UploadWindow, 10)
        assertEquals(SettingOutcome.Stored(10, SettingEffect.PeriodReadOwed(listOf("Household", "Care Fund"))), outcome)
        return read
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: a longer file upload window owes a read of the read period for every served Budget that has a cursor, and none for a Budget not read yet, which gets no row with a made-up cursor")
    fun raisingTheFileUploadWindowOwesAPeriodReadForEveryServedBudgetWithACursor() = runTest {
        fixture.readOnce()
        val before = fixture.statuses()
        val summerHouse = fixture.budgetId("Summer House")
        assertNull(slice.copyStore.changeCursorOf(summerHouse))

        val outcome = slice.generalSettings.change(NumberSetting.FileUploadWindow, 90)

        assertEquals(SettingOutcome.Stored(90, SettingEffect.PeriodReadOwed(listOf("Household", "Care Fund"))), outcome)
        assertTrue(owed("Household"))
        assertTrue(owed("Care Fund"))
        assertFalse(owed("Summer House"))
        assertNull(slice.copyStore.changeCursorOf(summerHouse))
        assertEquals(before, fixture.statuses())
        // ADR-007 §3a: the change that owes the read sets no Comparison marker of its own.
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: a longer fetch period, on which no Transaction Status depends, owes the read of the read period too")
    fun raisingTheFetchPeriodBeyondTheReadPeriodOwesAPeriodRead() = runTest {
        fixture.readOnce()

        val outcome = slice.generalSettings.change(NumberSetting.FetchPeriod, 90)

        assertEquals(SettingOutcome.Stored(90, SettingEffect.PeriodReadOwed(listOf("Household", "Care Fund"))), outcome)
        assertTrue(owed("Household"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: a longer Upload Window that makes the read period longer gives no status again from stored data: the Bank Transaction of 9 days ago stays Too Old, though 10 days would make it New, and no marker is set")
    fun aChangeThatOwesAPeriodReadGivesNoStatusesFromStoredData() = runTest {
        val (recent, old, careOld) = uploadWindowRaisedBeyondTheReadPeriod()

        assertEquals(TransactionStatus.New, fixture.status(recent))
        assertEquals(TransactionStatus.TooOld, fixture.status(old))
        assertEquals(TransactionStatus.TooOld, fixture.status(careOld))
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: lowering the setting again does not clear the read owed, and the statuses it would give again from stored data are not given to a Budget that owes the read")
    fun aLoweredSettingDoesNotClearTheFlag() = runTest {
        val (_, old, _) = uploadWindowRaisedBeyondTheReadPeriod()

        val outcome = slice.generalSettings.change(NumberSetting.UploadWindow, 9)

        assertTrue(outcome is SettingOutcome.Stored)
        assertTrue(owed("Household"))
        assertTrue(owed("Care Fund"))
        assertEquals(TransactionStatus.TooOld, fixture.status(old))
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "A1: Budget App Cannot Be Read", businessRules = ["BR-016"])
    @DisplayName("BR-016, A1: a read that fails leaves the read owed and the Budget's statuses as they were, while the Budget read in the same Comparison gets its statuses under the new value")
    fun aReadThatFailsLeavesTheFlag() = runTest {
        val (_, old, careOld) = uploadWindowRaisedBeyondTheReadPeriod()
        fixture.householdAnswer = { ReadResult.TransientFailure(FailureKind.ConnectionLost) }

        fixture.compare()

        assertTrue(owed("Household"))
        assertEquals(TransactionStatus.TooOld, fixture.status(old))
        assertFalse(owed("Care Fund"))
        assertEquals(TransactionStatus.New, fixture.status(careOld))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: the read of the period, from today back by the new read period, with the changes since the cursor, gives the statuses under the new value and ends the read owed")
    fun theReadOfThePeriodGivesTheStatuses() = runTest {
        val (_, old, _) = uploadWindowRaisedBeyondTheReadPeriod()
        val household = fixture.budgetId("Household")
        val cursor = slice.copyStore.changeCursorOf(household)
        val readsBefore = slice.budgetApp.reader.changeReads.size

        fixture.compare()

        val reads = slice.budgetApp.reader.changeReads.drop(readsBefore).filter { it.budgetReference == "budget-household" }
        assertEquals(listOf(null, cursor), reads.map { it.since })
        assertEquals(today.minusDays(10 + matchingDays), reads.first().from)
        assertFalse(owed("Household"))
        assertEquals(TransactionStatus.New, fixture.status(old))
        assertFalse(slice.status.workspace(household).summary.periodReadOwed)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: a Budget not read yet owes nothing and gets the longer period at its first read")
    fun aBudgetNotReadYetGetsTheLongerPeriodAtItsFirstRead() = runTest {
        fixture.readOnce()
        slice.generalSettings.change(NumberSetting.FileUploadWindow, 90)
        fixture.bankAccount("Summer Current", "Current")
        val readsBefore = slice.budgetApp.reader.changeReads.size

        fixture.compare()

        val summer = slice.budgetApp.reader.changeReads.drop(readsBefore).filter { it.budgetReference == "budget-summer" }
        assertEquals(listOf<ChangeCursor?>(null), summer.map { it.since })
        assertEquals(today.minusDays(90 + matchingDays), summer.single().from)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016, ADR-007 §3a: a Comparison marker left for another reason is concluded at the next start without changing the statuses of a Budget that owes the read; the other Budget is concluded")
    fun aMarkerLeftForAnotherReasonLeavesAnOwedBudgetsStatuses() = runTest {
        val (recent, _, careOld) = uploadWindowRaisedBeyondTheReadPeriod()
        fixture.householdAnswer = { ReadResult.TransientFailure(FailureKind.ConnectionLost) }
        fixture.compare()
        assertTrue(owed("Household"))
        // What a stopped process left: the copy holds Household's Bank Transaction, and the conclusions are pending.
        val checking = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        slice.copyStore.copy += StoredBudgetAppTransaction(
            id = 99, budgetAccountId = checking, budgetAppReference = "ynab-recent", transactionDate = fixture.oct13, amount = amount(-12),
            payee = "PLANTED-PAYEE", memo = null, importIdentifier = fixture.importIdentifierOf("TX-RECENT"), enteredBy = EnteredBy.Slussen,
            transferLinkReference = null, mergedIntoReference = null, cleared = true, approved = true, state = BudgetAppTransactionState.Present,
        )
        // ... and Care Fund's copy, read before, holds Care Saver's Bank Transaction: concluded, it is Already In Budget App.
        val health = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id
        slice.copyStore.copy += StoredBudgetAppTransaction(
            id = 100, budgetAccountId = health, budgetAppReference = "ynab-care-old", transactionDate = fixture.oct06, amount = amount(-40),
            payee = "PLANTED-PAYEE", memo = null, importIdentifier = fixture.importIdentifierOf("TX-CARE-OLD"), enteredBy = EnteredBy.Slussen,
            transferLinkReference = null, mergedIntoReference = null, cleared = true, approved = true, state = BudgetAppTransactionState.Present,
        )
        assertEquals(TransactionStatus.New, fixture.status(careOld))
        slice.copyStore.pending = true

        assertTrue(slice.comparison.recomputePending())

        assertEquals(TransactionStatus.New, fixture.status(recent))
        assertEquals(TransactionStatus.AlreadyInBudgetApp, fixture.status(careOld))
        assertFalse(slice.copyStore.pending)
        assertTrue(owed("Household"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016, UC-009 BR-015: a change of a Bank Account followed by its statuses again from stored data leaves the statuses of a Budget that owes the read, and gives them to a Budget that does not")
    fun aMappingChangeGivesNoStatusesAgainToABudgetThatOwesARead() = runTest {
        val (recent, _, careOld) = uploadWindowRaisedBeyondTheReadPeriod()
        fixture.householdAnswer = { ReadResult.TransientFailure(FailureKind.ConnectionLost) }
        fixture.compare()
        val checking = slice.connectionStore.bankAccountList.single { it.name == "Kestrel Checking" }.id
        val care = slice.connectionStore.bankAccountList.single { it.name == "Care Saver" }.id

        assertEquals(MappingChangeOutcome.Stored(statusesGiven = true), slice.mappings.setStartDate(checking, LocalDate.of(2026, 10, 14)))
        assertEquals(MappingChangeOutcome.Stored(statusesGiven = true), slice.mappings.setStartDate(care, LocalDate.of(2026, 10, 7)))

        assertEquals(TransactionStatus.New, fixture.status(recent))
        assertEquals(TransactionStatus.BeforeStartDate, fixture.status(careOld))
        assertTrue(owed("Household"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016, UC-013: a served Budget that gains a Budget Account owes the read; a setting a status depends on then gives the other Budget its statuses again and leaves that Budget's as they are")
    fun aBudgetThatGainsABudgetAccountKeepsItsStatusesUntilItsRead() = runTest {
        val (_, old, careOld) = fixture.readOnce()
        fixture.householdGainsABudgetAccount()
        assertTrue(owed("Household"))
        assertFalse(owed("Care Fund"))

        val outcome = slice.generalSettings.change(NumberSetting.UploadWindow, 10)

        assertEquals(SettingOutcome.Stored(10, SettingEffect.StatusesGiven(listOf("Household"))), outcome)
        assertEquals(TransactionStatus.TooOld, fixture.status(old))
        assertEquals(TransactionStatus.New, fixture.status(careOld))
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"], requirements = ["NFR-023"])
    @DisplayName("BR-016: the Budget shows that a read is owed (the read model of the Budget tab and the Overview), and no longer once it is read")
    fun theBudgetShowsThatAReadIsOwed() = runTest {
        fixture.readOnce()
        val household = fixture.budgetId("Household")
        assertFalse(slice.status.workspace(household).summary.periodReadOwed)

        slice.generalSettings.change(NumberSetting.FileUploadWindow, 90)

        assertTrue(slice.status.workspace(household).summary.periodReadOwed)
        assertTrue(slice.status.overview().budgets.single { it.budgetId == household }.periodReadOwed)
        fixture.compare()
        assertFalse(slice.status.workspace(household).summary.periodReadOwed)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016, UC-010 failure postconditions: a longer read period that is refused while an Upload Plan awaits confirmation, or that cannot be stored, owes no read and keeps the value")
    fun aRefusedOrFailedChangeOwesNoRead() = runTest {
        fixture.readOnce()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(
            SettingOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation),
            slice.generalSettings.change(NumberSetting.FileUploadWindow, 90),
        )
        slice.coordinatorStore.mode = CoordinatorMode.Normal
        slice.settings.failsToStore = true

        assertEquals(SettingOutcome.NotStored, slice.generalSettings.change(NumberSetting.FileUploadWindow, 90))

        assertEquals(60, slice.settings.settings.fileUploadWindowDays)
        assertFalse(owed("Household"))
        assertFalse(owed("Care Fund"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: a read of the period that Slussen only began, before the changes since the cursor answered, stores nothing and leaves the read owed; a read later that answers both ends it")
    fun aReadOfThePeriodIsStoredOnlyWithTheChangesSinceTheCursor() = runTest {
        uploadWindowRaisedBeyondTheReadPeriod()
        fixture.householdAnswer = { asked ->
            if (asked.since == null) ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("h-period"))) else ReadResult.TransientFailure(FailureKind.ConnectionLost)
        }
        fixture.compare()
        assertTrue(owed("Household"))

        fixture.householdAnswer = { ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("h-${it.since?.value}"))) }
        fixture.compare()

        assertFalse(owed("Household"))
    }

    /** Household holds a Bank Transaction without a Stable Reference (Needs Review), compared; then Household owes the read. */
    private suspend fun doubtfulInAnOwedBudget(): Pair<Long, Long> {
        val (_, old, _) = fixture.readOnce()
        val checking = slice.connectionStore.bankAccountList.single { it.name == "Kestrel Checking" }.id
        val doubtful = fixture.stored(checking, "TX-DOUBT", fixture.oct13, amount(-7))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == doubtful) it.copy(stableReference = null) else it }
        fixture.compare()
        assertEquals(TransactionStatus.NeedsReview, fixture.status(doubtful))
        assertTrue(slice.generalSettings.change(NumberSetting.FetchPeriod, 7) is SettingOutcome.Stored)
        assertTrue(slice.generalSettings.change(NumberSetting.FileUploadWindow, 7) is SettingOutcome.Stored)
        assertTrue(slice.generalSettings.change(NumberSetting.UploadWindow, 10) is SettingOutcome.Stored)
        assertTrue(owed("Household"))
        return doubtful to old
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016, UC-004: a Review Decision on a Bank Transaction of a Budget that owes the read is refused with its reason; nothing is stored: no decision, no status, no state_version, no marker")
    fun aReviewDecisionIsRefusedWhileTheBudgetOwesARead() = runTest {
        val (doubtful, old) = doubtfulInAnOwedBudget()
        val version = slice.stateVersion.value
        val statuses = fixture.statuses()

        val outcome = slice.review.decide(doubtful, slussen.core.port.ReviewChoice.ImportAnyway)

        assertEquals(slussen.core.review.ReviewOutcome.NotDecidable(slussen.core.review.Undecidable.PeriodReadOwed), outcome)
        assertTrue(slice.copyStore.reviewDecisions.isEmpty())
        assertEquals(statuses, fixture.statuses())
        assertEquals(TransactionStatus.TooOld, fixture.status(old))
        assertEquals(version, slice.stateVersion.value)
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016, UC-005: a Transfer Decision on a Transfer of a Budget that owes the read is refused with its reason; nothing is stored: the Transfer, its statuses and state_version stay")
    fun aTransferDecisionIsRefusedWhileTheBudgetOwesARead() = runTest {
        fixture.ready()
        val checking = fixture.bankAccount("Kestrel Checking", "Checking")
        val card = fixture.bankAccount("Kestrel Rewards", "Rewards Card", slussen.core.port.BankAccountType.CreditCard)
        slice.connectionStore.link(card, fundedBy = fixture.budgetAccountIdOf("Checking"))
        val paid = fixture.stored(checking, "TX-PAID", LocalDate.of(2026, 10, 12), amount(-250))
        fixture.stored(card, "TX-RECEIVED", fixture.oct13, amount(250))
        fixture.compare()
        assertEquals(TransactionStatus.TransferLeg, fixture.status(paid))
        slice.generalSettings.change(NumberSetting.FileUploadWindow, 90)
        assertTrue(owed("Household"))
        val transfers = slice.copyStore.transfers.toList()
        val statuses = fixture.statuses()
        val version = slice.stateVersion.value

        val outcome = slice.transferDecisions.decide(paid, slussen.core.transfer.TransferChoice.Hold)

        assertEquals(slussen.core.transfer.TransferOutcome.NotDecidable(slussen.core.transfer.TransferUndecidable.PeriodReadOwed), outcome)
        assertEquals(transfers, slice.copyStore.transfers.toList())
        assertEquals(statuses, fixture.statuses())
        assertEquals(version, slice.stateVersion.value)
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("BR-016: a Budget read before and no longer served owes the read too, so that when it is served again its next read covers the longer period")
    fun aBudgetNoLongerServedOwesTheReadToo() = runTest {
        fixture.readOnce()
        val summer = fixture.budgetId("Summer House")
        val current = fixture.bankAccount("Summer Current", "Current")
        fixture.compare()
        assertTrue(slice.copyStore.changeCursorOf(summer) != null)
        slice.connectionStore.bankAccountList.removeAll { it.id == current }
        val listed = slussen.testing.ExampleData.budgetsRead().map { budget ->
            slussen.core.port.BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { slussen.core.port.BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, setOf("budget-household", "budget-care"), fixture.now)
        assertFalse(slice.connectionStore.budgets().single { it.id == summer }.served)

        slice.generalSettings.change(NumberSetting.FileUploadWindow, 90)

        assertTrue(slice.copyStore.periodReadOwed(summer))
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), fixture.now)
        fixture.bankAccount("Summer Current again", "Current")
        val readsBefore = slice.budgetApp.reader.changeReads.size
        fixture.compare()
        val reads = slice.budgetApp.reader.changeReads.drop(readsBefore).filter { it.budgetReference == "budget-summer" }
        assertEquals(null, reads.first().since)
        assertEquals(today.minusDays(90 + matchingDays), reads.first().from)
        assertFalse(slice.copyStore.periodReadOwed(summer))
    }
}
