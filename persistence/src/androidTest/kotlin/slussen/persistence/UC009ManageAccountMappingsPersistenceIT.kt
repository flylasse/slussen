package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonRules
import slussen.core.comparison.ComparisonWindows
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.ComparisonConclusions
import slussen.core.port.CoordinatorMode
import slussen.core.port.NewBankAccount
import slussen.core.port.OtherSideKind
import slussen.core.port.OtherSideStored
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferType
import slussen.persistence.main.CoordinatorStateEntity
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.TransferEntity
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/**
 * The storage operations of UC-009 (O-13 `MappingStore`) on the platform's SQLite (ADR-002 §3): A14, and the changes of
 * one Bank Account of build-out phase B4 (A4, A5, A9, A12, A13), each one transaction that raises `state_version` and,
 * where UC-009 BR-015 follows it with statuses given again, sets `comparison_pending`; and what O-5 concludes on the
 * stored data after a Bank Account is retired and restored (UC-005 BR-015). Invented data only (C-005).
 */
@DisplayName("UC-009: Manage Account Mappings, on the device")
class UC009ManageAccountMappingsPersistenceIT {
    private val usd = CurrencyCode("USD")
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private lateinit var main: SlussenDatabase
    private lateinit var connections: RoomConnectionStore

    @BeforeEach
    fun open() {
        DeviceFiles.wipe()
        main = SlussenDatabases.openMain(DeviceFiles.context)
        connections = RoomConnectionStore(main.connections())
    }

    @AfterEach
    fun close() = main.close()

    private val listed = listOf(
        BudgetToServe("budget-household", "Household", usd, listOf(BudgetAccountToStore("ba-checking", "Checking", Amount(4_140_050)))),
    )

    private fun newBankAccount(budgetAccountId: Long) = NewBankAccount(
        generatedIdentifier = "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a",
        bank = BankChoice.Named("Kestrel Bank"),
        providerBankId = null,
        dataProviderId = null,
        fileFormatId = null,
        balanceDataProviderId = null,
        balanceProviderReference = null,
        name = "Kestrel Checking",
        currency = usd,
        type = BankAccountType.Checking,
        providerReference = null,
        bankAccountFingerprint = null,
        identifiedAt = now,
        startDate = null,
        budgetAccountId = budgetAccountId,
    )

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14: O-13 stores the type and a newly named Bank, clears a Transfer Partner as asked, and leaves the Account Mapping and identification as they are")
    fun typeAndBankAreChanged() = runBlocking {
        connections.serveBudgets(listed, setOf("budget-household"), now)
        val checking = connections.budgets().single().budgetAccounts.single().id
        val added = connections.addBankAccount(newBankAccount(checking)) as AddOutcome.Added
        main.openHelper.writableDatabase.execSQL(
            "UPDATE bank_account SET transfer_partner_budget_account_id = $checking WHERE id = ${added.bankAccountId}",
        )
        val before = connections.bankAccounts().single()

        RoomMappingStore(main.connections()).changeTypeAndBank(
            added.bankAccountId, BankAccountType.CreditCard, BankChoice.Named("Pinecrest Card Co."),
            clearFundedBy = false, clearTransferPartner = true,
        )

        val after = connections.bankAccounts().single()
        assertEquals(BankAccountType.CreditCard, after.type)
        assertEquals("Pinecrest Card Co.", connections.banks().single { it.id == after.bankId }.name)
        assertEquals(null, after.transferPartnerBudgetAccountId)
        assertEquals(before.copy(type = after.type, bankId = after.bankId, transferPartnerBudgetAccountId = null), after)
    }

    // ---- Build-out phase B4: the changes of one Bank Account ------------------------------------------------------------

    private val eur = CurrencyCode("EUR")
    private val oct12 = LocalDate.of(2026, 10, 12)
    private val oct13 = LocalDate.of(2026, 10, 13)

    private val household = listOf(
        BudgetToServe(
            "budget-household", "Household", usd,
            listOf(
                BudgetAccountToStore("ba-checking", "Checking", Amount(4_140_050)),
                BudgetAccountToStore("ba-rewards", "Rewards Card", Amount(-1_268_400)),
                BudgetAccountToStore("ba-reserve", "Reserve Savings", Amount(12_000_000)),
                BudgetAccountToStore("ba-travel", "Travel Card", Amount(-90_000)),
                BudgetAccountToStore("ba-old", "Old Savings", Amount(15_000)),
            ),
        ),
        BudgetToServe("budget-summer", "Summer House", eur, listOf(BudgetAccountToStore("ba-current", "Current", Amount(3_402_300)))),
    )

    /** What a test of phase B4 needs of the stored data: the Budget Accounts by name and the Bank Accounts mapped to four of them. */
    private class Mapped(val budgetAccounts: Map<String, Long>, val householdId: Long, val checking: Long, val card: Long, val saver: Long, val current: Long)

    /**
     * `coordinator_state` with its one record, as a start leaves it; Household (USD) and Summer House (EUR) served; Kestrel
     * Checking, the credit card Kestrel Rewards and Reserve Saver mapped into Household, Aurora Current into Summer House.
     * No Bank Account is mapped to Travel Card or Old Savings.
     */
    private suspend fun mapped(): Mapped {
        if (main.coordinator().state() == null) {
            main.coordinator().insert(
                CoordinatorStateEntity(
                    mode = RoomCoordinatorStore.textOf(CoordinatorMode.Normal), uploadPlanId = null, stateVersion = 0, scheduledFetchDeferred = false,
                    comparisonPending = false,
                ),
            )
        }
        connections.serveBudgets(household, household.map { it.budgetAppReference }.toSet(), now)
        val budgets = connections.budgets()
        val budgetAccounts = budgets.flatMap { it.budgetAccounts }.associate { it.name to it.id }

        suspend fun add(identifier: String, name: String, type: BankAccountType, budgetAccount: String, currency: CurrencyCode = usd): Long =
            (
                connections.addBankAccount(
                    newBankAccount(budgetAccounts.getValue(budgetAccount)).copy(generatedIdentifier = identifier, name = name, type = type, currency = currency),
                ) as AddOutcome.Added
                ).bankAccountId

        return Mapped(
            budgetAccounts = budgetAccounts,
            householdId = budgets.single { it.name == "Household" }.id,
            checking = add("6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a", "Kestrel Checking", BankAccountType.Checking, "Checking"),
            card = add("0d9b7c1a-52e4-4f0b-8c3d-7a1e5f2b9c60", "Kestrel Rewards", BankAccountType.CreditCard, "Rewards Card"),
            saver = add("b2a4e6c8-1d3f-4a5b-9c7e-0f1a2b3c4d5e", "Reserve Saver", BankAccountType.Savings, "Reserve Savings"),
            current = add("c3b5f7d9-2e4a-4b6c-8d0f-1a2b3c4d5e6f", "Aurora Current", BankAccountType.Checking, "Current", eur),
        )
    }

    private suspend fun stateVersion(): Long = checkNotNull(main.coordinator().stateVersion())

    private suspend fun pending(): Boolean = checkNotNull(main.budgetAppCopy().comparisonPending())

    /** Clears the Comparison marker as O-5 does, so that the next change shows whether it sets it. */
    private fun concluded() = main.openHelper.writableDatabase.execSQL("UPDATE coordinator_state SET comparison_pending = 0 WHERE id = 1")

    private suspend fun stored(bankAccountId: Long) = connections.bankAccounts().single { it.id == bankAccountId }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005", "BR-015"])
    @DisplayName("A4, A5 (O-13): retiring and restoring write only the mark: the Account Mapping, the identification and the Start Date stay; each raises state_version and sets comparison_pending in its transaction; retiring a Retired Account, restoring one that is not retired and an unknown Bank Account store nothing")
    fun retiringAndRestoringKeepTheAccountMapping() = runBlocking {
        val mapped = mapped()
        val mappings = RoomMappingStore(main.connections())
        val before = stored(mapped.card)
        val start = stateVersion()

        val retired = mappings.setRetired(mapped.card, true)

        assertTrue(retired)
        assertEquals(before.copy(retired = true), stored(mapped.card))
        assertEquals(mapped.budgetAccounts.getValue("Rewards Card"), stored(mapped.card).mapping?.budgetAccountId)
        val afterRetiring = stateVersion()
        assertTrue(afterRetiring > start)
        assertTrue(pending())
        concluded()

        assertFalse(mappings.setRetired(mapped.card, true))
        assertFalse(mappings.setRetired(mapped.checking, false))
        assertFalse(mappings.setRetired(mapped.card + 1000, true))
        assertEquals(afterRetiring, stateVersion())
        assertFalse(pending())

        assertTrue(mappings.setRetired(mapped.card, false))
        assertEquals(before, stored(mapped.card))
        assertTrue(stateVersion() > afterRetiring)
        assertTrue(pending())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A12: Rename a Bank Account", businessRules = ["BR-011", "BR-015"])
    @DisplayName("A12, A13 (O-13): a new name is a single update that raises state_version and leaves comparison_pending as it is; a Start Date, set and cleared, raises state_version and sets comparison_pending; nothing else of the Bank Account changes, and an unknown Bank Account stores nothing")
    fun theNameAndTheStartDateAreSingleUpdates() = runBlocking {
        val mapped = mapped()
        val mappings = RoomMappingStore(main.connections())
        val before = stored(mapped.checking)
        val start = stateVersion()

        assertTrue(mappings.rename(mapped.checking, "Everyday Checking"))

        assertEquals(before.copy(name = "Everyday Checking"), stored(mapped.checking))
        val afterName = stateVersion()
        assertTrue(afterName > start)
        assertFalse(pending())

        assertTrue(mappings.setStartDate(mapped.checking, LocalDate.of(2026, 9, 1)))

        assertEquals(before.copy(name = "Everyday Checking", startDate = LocalDate.of(2026, 9, 1)), stored(mapped.checking))
        val afterStartDate = stateVersion()
        assertTrue(afterStartDate > afterName)
        assertTrue(pending())
        concluded()

        assertTrue(mappings.setStartDate(mapped.checking, null))
        assertNull(stored(mapped.checking).startDate)
        assertTrue(pending())
        concluded()
        val afterClearing = stateVersion()

        assertFalse(mappings.rename(mapped.checking + 1000, "Everyday Checking"))
        assertFalse(mappings.setStartDate(mapped.checking + 1000, oct12))
        assertEquals(afterClearing, stateVersion())
        assertFalse(pending())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008", "BR-015"])
    @DisplayName("A9, BR-008 (O-13): a Funded By is stored for a credit card and a Transfer Partner for another Bank Account, each raising state_version and setting comparison_pending, and cleared again; one that does not fit the type, the Bank Account's own Budget Account, one of another Budget, a Manually Kept Account, one no Bank Account is mapped to, one only a Retired Account is mapped to and an unknown one are refused with nothing stored")
    fun aFundedByIsStoredOnlyForABudgetAccountSlussenFetches() = runBlocking {
        val mapped = mapped()
        val mappings = RoomMappingStore(main.connections())
        val accounts = mapped.budgetAccounts
        connections.setManuallyKept(accounts.getValue("Travel Card"), true)
        val start = stateVersion()

        assertEquals(OtherSideStored.NotAllowed, mappings.setOtherSide(mapped.checking, OtherSideKind.FundedBy, accounts.getValue("Reserve Savings")))
        assertEquals(OtherSideStored.NotAllowed, mappings.setOtherSide(mapped.card, OtherSideKind.TransferPartner, accounts.getValue("Checking")))
        listOf("Rewards Card", "Current", "Travel Card", "Old Savings").forEach { name ->
            assertEquals(OtherSideStored.NotAllowed, mappings.setOtherSide(mapped.card, OtherSideKind.FundedBy, accounts.getValue(name)), name)
        }
        assertEquals(OtherSideStored.NotAllowed, mappings.setOtherSide(mapped.card, OtherSideKind.FundedBy, 999_999))
        assertEquals(OtherSideStored.NotFound, mappings.setOtherSide(mapped.card + 1000, OtherSideKind.FundedBy, accounts.getValue("Checking")))
        assertNull(stored(mapped.card).fundedByBudgetAccountId)
        assertEquals(start, stateVersion())
        assertFalse(pending())

        assertEquals(OtherSideStored.Stored, mappings.setOtherSide(mapped.card, OtherSideKind.FundedBy, accounts.getValue("Checking")))

        assertEquals(accounts.getValue("Checking"), stored(mapped.card).fundedByBudgetAccountId)
        assertNull(stored(mapped.card).transferPartnerBudgetAccountId)
        val afterFundedBy = stateVersion()
        assertTrue(afterFundedBy > start)
        assertTrue(pending())
        concluded()

        assertEquals(OtherSideStored.Stored, mappings.setOtherSide(mapped.checking, OtherSideKind.TransferPartner, accounts.getValue("Reserve Savings")))
        assertEquals(accounts.getValue("Reserve Savings"), stored(mapped.checking).transferPartnerBudgetAccountId)
        assertNull(stored(mapped.checking).fundedByBudgetAccountId)
        assertTrue(pending())
        concluded()

        // UC-005 BR-014: once its Bank Account is a Retired Account, Reserve Savings is not a Budget Account that Slussen fetches.
        assertTrue(mappings.setRetired(mapped.saver, true))
        concluded()
        val afterRetiring = stateVersion()
        assertEquals(OtherSideStored.NotAllowed, mappings.setOtherSide(mapped.card, OtherSideKind.FundedBy, accounts.getValue("Reserve Savings")))
        assertEquals(afterRetiring, stateVersion())
        assertFalse(pending())
        // The value stored before the retiring is kept: it no longer applies, and nothing clears it but the Operator.
        assertEquals(accounts.getValue("Reserve Savings"), stored(mapped.checking).transferPartnerBudgetAccountId)

        assertEquals(OtherSideStored.Stored, mappings.setOtherSide(mapped.card, OtherSideKind.FundedBy, null))
        assertNull(stored(mapped.card).fundedByBudgetAccountId)
        assertTrue(stateVersion() > afterRetiring)
        assertTrue(pending())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012", "BR-015"])
    @DisplayName("A14 step 4, BR-015 (O-13): the Bank Account type is stored together with comparison_pending and a raised state_version, so that no Upload Plan is prepared before the Transfers are detected again")
    fun aTypeChangeLeavesTheComparisonPending() = runBlocking {
        val mapped = mapped()
        val start = stateVersion()

        RoomMappingStore(main.connections()).changeTypeAndBank(
            mapped.card, BankAccountType.Savings, BankChoice.Named("Kestrel Bank"), clearFundedBy = true, clearTransferPartner = false,
        )

        assertEquals(BankAccountType.Savings, stored(mapped.card).type)
        assertTrue(stateVersion() > start)
        assertTrue(pending())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005", "BR-015"])
    @DisplayName("A4, A5, UC-005 BR-015 (O-13, O-5): a Bank Account retired in slussen.db keeps its Account Mapping, so the conclusions from the stored data keep a Transfer the Operator confirmed, with its decision and both Transfer Legs, drop nothing and leave it in no Upload Plan; after restoring they make it eligible again through its outflow side")
    fun aRetiredAccountKeepsADecidedTransferInTheStoredData() = runBlocking {
        val mapped = mapped()
        val mappings = RoomMappingStore(main.connections())
        val comparisons = RoomComparisonStore(main, connections)
        val counts = RoomImportStatusStore(main.fetches(), connections)
        val checkingAccount = mapped.budgetAccounts.getValue("Checking")
        val cardAccount = mapped.budgetAccounts.getValue("Rewards Card")
        comparisons.applyChangeSet(BudgetReadToStore(mapped.householdId, null, emptyList(), ChangeCursor("1"), emptyMap(), now))
        val db = main.openHelper.writableDatabase
        val seen = now.minusSeconds(3600).toEpochMilli()
        // Kestrel Checking paid 250.40 to Kestrel Rewards: the two Transfer Legs of a Card Payment, received before the read.
        db.execSQL(
            "INSERT INTO bank_transaction (id, bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                "amount, currency, first_seen_at, status) VALUES (1, ${mapped.checking}, 'TX-0001', 'Supplied', 1, 'SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa', " +
                "${oct12.toEpochDay()}, -250400, 'USD', $seen, 'Transfer Leg')",
        )
        db.execSQL(
            "INSERT INTO bank_transaction (id, bank_account_id, stable_reference, reference_kind, occurrence_number, import_identifier, booked_date, " +
                "amount, currency, first_seen_at, status) VALUES (2, ${mapped.card}, 'TX-0002', 'Supplied', 1, 'SLS1-bbbbbbbbbbbbbbbbbbbbbbbbbb', " +
                "${oct13.toEpochDay()}, 250400, 'USD', $seen, 'Transfer Leg')",
        )
        val transferId = main.budgetAppCopy().insertTransfer(
            TransferEntity(
                outflowBudgetAccountId = checkingAccount, inflowBudgetAccountId = cardAccount, amount = 250_400, transferType = TransferType.CardPayment.text,
                guessed = false, decision = TransferDecision.Confirmed.text, decidedAt = now,
            ),
        )
        main.budgetAppCopy().storeTransferOf(listOf(1, 2), transferId)
        val windows = ComparisonWindows(
            uploadWindowDays = 7, fileUploadWindowDays = 60, matchingDateWindowDays = 10, transferPairingDays = 5, lonelyTransferGraceDays = 6,
            today = LocalDate.of(2026, 10, 15),
        )
        var conclusions: ComparisonConclusions? = null
        suspend fun conclude() = comparisons.applyComparison(setOf(mapped.householdId)) { data -> ComparisonRules.conclude(data, windows).also { conclusions = it } }
        suspend fun eligible() = counts.transactionCounts().associate { it.bankAccountId to it.eligible }
        conclude()
        assertEquals(1, eligible().getValue(mapped.checking))

        assertTrue(mappings.setRetired(mapped.card, true))
        assertTrue(pending())
        conclude()

        assertFalse(pending())
        assertTrue(checkNotNull(conclusions).dropped.isEmpty())
        val kept = main.budgetAppCopy().transfers().single()
        assertEquals(transferId, kept.id)
        assertEquals(TransferDecision.Confirmed.text, kept.decision)
        assertEquals(listOf(1L, 2L), main.budgetAppCopy().legsOf(transferId).map { it.id })
        assertEquals(TransactionStatus.TransferLeg.text, main.budgetAppCopy().legsOf(transferId).first().status)
        assertEquals(0, eligible().getValue(mapped.checking))
        assertEquals(0, eligible().getValue(mapped.card))

        assertTrue(mappings.setRetired(mapped.card, false))
        conclude()

        assertTrue(checkNotNull(conclusions).dropped.isEmpty())
        assertEquals(TransferDecision.Confirmed.text, main.budgetAppCopy().transfers().single().decision)
        assertEquals(listOf(1L, 2L), main.budgetAppCopy().legsOf(transferId).map { it.id })
        assertEquals(1, eligible().getValue(mapped.checking))
        assertEquals(0, eligible().getValue(mapped.card))
    }
}
