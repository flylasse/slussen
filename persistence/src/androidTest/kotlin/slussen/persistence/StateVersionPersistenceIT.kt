package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.ReviewChoice
import slussen.core.port.SentRecordOutcome
import slussen.core.port.TransferCounterpartState
import slussen.core.port.UploadKind
import slussen.core.port.UploadPlanState
import slussen.persistence.main.EligibleTransactionEntity
import slussen.persistence.main.ReviewDecisionEntity
import slussen.persistence.main.SentRecordEntity
import slussen.persistence.main.TransactionFileEntity
import slussen.persistence.main.TransferCounterpartEntity
import slussen.persistence.main.UploadPlanEntity
import slussen.persistence.main.UploadPlanItemEntity
import slussen.persistence.main.UploadPlanItemKind
import slussen.testing.StateVersionScenario
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `coordinator_state.state_version` in `slussen.db` on a device or emulator (ADR-002 §3; ADR-007 §6 step 2): every storage
 * operation that changes what an Upload Plan was prepared from raises it in its own transaction, and a refused one leaves
 * it. The steps are those of [StateVersionScenario], which the in-memory fakes run on the JVM in
 * `StateVersionInMemoryTest`; the writes of the tables schema version 6 adds have their own tests here, on rows planted
 * with SQL, because no storage port writes them yet.
 */
@DisplayName("state_version (ADR-007 §6 step 2), on the device")
class StateVersionPersistenceIT {
    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var storage: SlussenStorage

    @BeforeEach
    fun open() = runBlocking {
        DeviceFiles.wipe()
        storage = SlussenStorage.open(DeviceFiles.context, clock)
    }

    @AfterEach
    fun close() {
        storage.main.close()
        storage.device.close()
        storage.journal.close()
    }

    private suspend fun stateVersion(): Long = checkNotNull(storage.main.coordinator().stateVersion()) { "coordinator_state has its one record after a start" }

    /** A Budget with two Budget Accounts, a Bank, a Bank Account and two Bank Transactions (invented), for the references of the new tables. */
    private fun plantReferencedRecords() {
        val db = storage.main.openHelper.writableDatabase
        listOf(
            "INSERT INTO bank VALUES (1, 'Kestrel Bank', NULL)",
            "INSERT INTO budget VALUES (1, 'budget-household', 'Household', 'USD', NULL, 1)",
            "INSERT INTO budget_account VALUES (1, 1, 'ba-checking', 'Checking', 0, NULL, NULL)",
            "INSERT INTO budget_account VALUES (2, 1, 'ba-reserve', 'Reserve Savings', 0, NULL, NULL)",
            "INSERT INTO file_format VALUES (1, 'Harbor Health Trust statement', 'example-hsa')",
            "INSERT INTO bank_account (id, generated_identifier, bank_id, file_format_id, name, currency, bank_account_type, identified_at, retired) " +
                "VALUES (1, '6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a', 1, 1, 'Kestrel Checking', 'USD', 'Checking', 1760529600000, 0)",
            "INSERT INTO bank_transaction (id, bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                "amount, currency, first_seen_at) VALUES (1, 1, 'TX-0001', 'Supplied', 1, 'SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa', 20375, -42500, 'USD', 1760533200000)",
            "INSERT INTO bank_transaction (id, bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                "amount, currency, first_seen_at) VALUES (2, 1, 'TX-0002', 'Supplied', 1, 'SLS1-bbbbbbbbbbbbbbbbbbbbbbbbbb', 20376, 150000, 'USD', 1760533200000)",
        ).forEach(db::execSQL)
    }

    private fun plan(state: UploadPlanState = UploadPlanState.AwaitingConfirmation) = UploadPlanEntity(
        budgetId = 1, bankAccountId = null, preparedAt = now, expiresAt = now.plusSeconds(600), newCount = 1, matchCount = 0, counterpartCount = 0,
        totalInflow = 0, totalOutflow = 42_500, state = state.text,
    )

    private fun sentRecord(plan: Long, budgetAccount: Long = 1, bankTransaction: Long = 1) = SentRecordEntity(
        bankTransaction = bankTransaction, uploadPlanId = plan, budgetAccountId = budgetAccount, importIdentifier = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa",
        occurrence = 0, bookedDate = LocalDate.ofEpochDay(20375), amount = -42_500, sentAsTransfer = true, sentAt = now,
        outcome = SentRecordOutcome.NotYetKnown.text, budgetAppReference = null,
    )

    @Test
    @DisplayName("ADR-007 §6 step 2: every storage operation that changes what an Upload Plan was prepared from (served Budgets, Bank Accounts, Account Mappings, Left-Out Bank Accounts, Manually Kept Accounts, Operator Settings, the stored copy of the Budget App, the conclusions of a Comparison) raises state_version in slussen.db, and a refused one leaves it")
    fun everyStoredChangeRaisesTheStateVersionAndARefusalLeavesIt() = runBlocking {
        val steps = StateVersionScenario(
            storage.registry, storage.registry, storage.settings, storage.connections, storage.mappings, storage.comparisons, storage.comparisons,
        ) { stateVersion() }.run()

        assertEquals(StateVersionScenario.OPERATIONS, steps.map { it.operation })
        assertEquals(emptyList<String>(), steps.filter { it.mustRaise != it.rose }.map { "${it.operation}: state_version ${if (it.rose) "rose" else "did not rise"}" })
    }

    @Test
    @DisplayName("ADR-007 §6 step 2: a Review Decision that is stored, changed or cleared raises state_version; a Bank Transaction keeps at most one, whose identifier a change keeps; clearing none changes nothing (entity model REVIEW_DECISION)")
    fun aReviewDecisionRaisesTheStateVersion() = runBlocking {
        plantReferencedRecords()
        val dao = storage.main.reviewDecisions()
        val defer = ReviewDecisionEntity(bankTransaction = 1, choice = ReviewChoice.Defer.text, chosenBudgetAppReference = null, chosenBudgetAccountId = null, chosenAmount = null, decidedAt = now)
        val start = stateVersion()

        val id = dao.store(defer)
        val afterStore = stateVersion()
        val changed = defer.copy(choice = ReviewChoice.AlreadyPresent.text, chosenBudgetAppReference = "ynab-tx-0001", chosenBudgetAccountId = 1, chosenAmount = -42_500, decidedAt = now.plusSeconds(60))
        val idAfterChange = dao.store(changed)
        val afterChange = stateVersion()
        val read = dao.of(1)
        val cleared = dao.clear(1)
        val afterClear = stateVersion()
        val clearedAgain = dao.clear(1)

        assertTrue(afterStore > start && afterChange > afterStore && afterClear > afterChange)
        assertEquals(id, idAfterChange)
        assertEquals(changed.copy(id = id), read)
        assertTrue(cleared)
        assertFalse(clearedAgain)
        assertEquals(afterClear, stateVersion())
        assertTrue(dao.all().isEmpty())
    }

    @Test
    @DisplayName("ADR-007 §6 step 2: a Sent Record and a Transfer Counterpart that are stored or changed raise state_version and are read back; a second Sent Record with the same Import Identifier in one Budget Account is refused with nothing stored, and the same one in another Budget Account is stored (entity model SENT_RECORD; ADR-002 §4)")
    fun theSentRecordAndATransferCounterpartRaiseTheStateVersion() = runBlocking {
        plantReferencedRecords()
        val dao = storage.main.uploads()
        val planId = dao.insertPlan(plan(UploadPlanState.Confirmed))
        val start = stateVersion()

        val sent = dao.insertSentRecord(sentRecord(planId))
        val afterInsert = stateVersion()
        val second = runCatching { dao.insertSentRecord(sentRecord(planId, bankTransaction = 2)) }
        val afterRefusal = stateVersion()
        val elsewhere = dao.insertSentRecord(sentRecord(planId, budgetAccount = 2))
        val written = sentRecord(planId).copy(id = sent, outcome = SentRecordOutcome.Written.text, budgetAppReference = "ynab-tx-0101")
        val updated = dao.updateSentRecord(written)
        val afterUpdate = stateVersion()
        val unknown = dao.updateSentRecord(written.copy(id = 99))
        val counterpart = TransferCounterpartEntity(
            sentRecordId = sent, budgetAccountId = 2, budgetAppReference = "ynab-tx-0201", state = TransferCounterpartState.Uncleared.text, clearedAt = null,
            settledByBankTransaction = null,
        )
        val counterpartId = dao.insertTransferCounterpart(counterpart)
        val afterCounterpart = stateVersion()
        val settled = counterpart.copy(id = counterpartId, state = TransferCounterpartState.Cleared.text, clearedAt = now, settledByBankTransaction = 2)
        val counterpartUpdated = dao.updateTransferCounterpart(settled)

        assertTrue(afterInsert > start)
        assertTrue(second.isFailure)
        assertEquals(afterInsert, afterRefusal)
        assertTrue(updated && !unknown && counterpartUpdated)
        assertTrue(afterUpdate > afterRefusal + 1 && afterCounterpart > afterUpdate && stateVersion() > afterCounterpart)
        assertEquals(listOf(written, sentRecord(planId, budgetAccount = 2).copy(id = elsewhere)), dao.sentRecords())
        assertEquals(written, dao.sentRecordIn(1, "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals(listOf(written), dao.sentRecordsOfPlan(planId).filter { it.budgetAccountId == 1L })
        assertEquals(listOf(written, sentRecord(planId, budgetAccount = 2).copy(id = elsewhere)), dao.sentRecordsOf(1))
        assertEquals(settled, dao.transferCounterpartOf(sent))
        assertEquals(listOf(settled), dao.transferCounterparts())
    }

    @Test
    @DisplayName("ADR-007 §4: an Upload Plan, its Eligible Transactions and its write set with the header line are stored and read back in their order and leave state_version as it is, since they are not what a plan is prepared from; a plan changes its state only from the state named; a Transaction File is stored and read back, the latest first (entity model UPLOAD_PLAN, ELIGIBLE_TRANSACTION, TRANSACTION_FILE; ADR-002 §2)")
    fun anUploadPlanWithItsWriteSetIsReadBackAndLeavesTheStateVersion() = runBlocking {
        plantReferencedRecords()
        val dao = storage.main.uploads()
        val start = stateVersion()

        val planId = dao.insertPlan(plan())
        val eligible = EligibleTransactionEntity(uploadPlanId = planId, bankTransaction = 1, uploadKind = UploadKind.LinkedTransfer.text, clearCounterpart = true)
        dao.insertEligible(listOf(eligible))
        val header = UploadPlanItemEntity(
            uploadPlanId = planId, position = UploadPlanItemEntity.HEADER_POSITION, writeKind = UploadPlanItemKind.Header.text, stateVersion = start, changeCursor = "4182",
        )
        val write = UploadPlanItemEntity(
            uploadPlanId = planId, position = 1, writeKind = UploadPlanItemKind.LinkedTransfer.text, bankTransaction = 1, budgetAccountId = 1,
            importIdentifier = "SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa", occurrence = 0, bookedDate = LocalDate.ofEpochDay(20375), amount = -42_500,
            payeeSent = "Corner Grocer", memoSent = null, cleared = true, transferBudgetAccountId = 2, clearCounterpart = true,
        )
        dao.insertItems(listOf(write, header))
        val expiredFromConfirmed = dao.moveState(planId, UploadPlanState.Confirmed.text, UploadPlanState.Expired.text)
        val confirmed = dao.moveState(planId, UploadPlanState.AwaitingConfirmation.text, UploadPlanState.Confirmed.text)
        val files = storage.main.transactionFiles()
        val first = TransactionFileEntity(bankAccountId = 1, fileFormatId = 1, importedAt = now, outcome = "Imported", transactionCount = 3, rejectionReason = null)
        val rejected = first.copy(importedAt = now.plusSeconds(60), outcome = "Rejected", transactionCount = 0, rejectionReason = "Line 4 has no amount.")
        val firstId = files.insert(first)
        val rejectedId = files.insert(rejected)

        assertEquals(start, stateVersion())
        assertEquals(0, expiredFromConfirmed)
        assertEquals(1, confirmed)
        assertEquals(plan(UploadPlanState.Confirmed).copy(id = planId), dao.plan(planId))
        assertEquals(listOf(planId), dao.plansIn(UploadPlanState.Confirmed.text).map { it.id })
        assertTrue(dao.plansIn(UploadPlanState.AwaitingConfirmation.text).isEmpty())
        assertEquals(listOf(eligible), dao.eligibleOf(planId))
        assertEquals(listOf(header, write), dao.itemsOf(planId))
        assertTrue(runCatching { dao.insertItems(listOf(header)) }.isFailure, "a second line at one position of a plan is refused")
        assertEquals(listOf(rejected.copy(id = rejectedId), first.copy(id = firstId)), files.of(1))
        assertEquals(2, files.all().size)
    }
}
