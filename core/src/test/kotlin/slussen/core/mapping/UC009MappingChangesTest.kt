package slussen.core.mapping

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonReport
import slussen.core.comparison.ComparisonStart
import slussen.core.connection.OtherSideShown
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.OtherSideKind
import slussen.core.port.OtherSideStored
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDropReason
import slussen.core.status.TransferEligibility
import slussen.core.status.WhyGuessed
import slussen.core.transfer.TransferChoice
import slussen.core.transfer.TransferOutcome
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.ExampleData
import slussen.testing.ExampleData.EUR
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Manage Account Mappings (UC-009), the changes of one Bank Account that phase B4 of the build-out builds: what step 1
 * shows of a Funded By and a Transfer Partner, A4 Retire, A5 Restore, A9 Funded By and Transfer Partner, A12 Rename, A13
 * Start Date, step 4 of A14, and BR-015, the statuses given again from stored data after each of them, through the core's
 * entry functions on the fakes of `:testing`. The Transfers a change must keep or drop are those of UC-005 BR-015. Every
 * name, amount, payee and identifier is invented (C-005).
 */
@DisplayName("UC-009: Manage Account Mappings, the changes of one Bank Account and the statuses after them")
class UC009MappingChangesTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private var nextId = 7000L

    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct13: LocalDate = LocalDate.of(2026, 10, 13)

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** The credentials are accepted and Household (USD: Checking, Rewards Card, Reserve Savings, Travel Card, Joint Checking, Old Savings) is served with the other Budgets. */
    private suspend fun ready() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            val more = if (budget.name == "Household") {
                listOf(
                    BudgetAccountToStore("ba-travel", "Travel Card", amount(-90)),
                    BudgetAccountToStore("ba-joint", "Joint Checking", amount(700)),
                    BudgetAccountToStore("ba-old", "Old Savings", amount(15)),
                )
            } else {
                emptyList()
            }
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) } + more)
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    private suspend fun budgetAccount(name: String): Long = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id

    private suspend fun household(): Long = slice.connectionStore.budgets().single { it.name == "Household" }.id

    private suspend fun bankAccount(
        name: String,
        budgetAccountName: String?,
        type: BankAccountType = BankAccountType.Checking,
        currency: CurrencyCode = USD,
    ): Long {
        val id = nextId++
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = slice.feedsId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = name,
            currency = currency,
            type = type,
            startDate = null,
            providerReference = "acct-$id",
            bankAccountFingerprint = null,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = budgetAccountName?.let { StoredAccountMapping(nextId++, id, budgetAccount(it), null) },
        )
        return id
    }

    private suspend fun checking() = bankAccount("Kestrel Checking", "Checking")

    private suspend fun rewardsCard() = bankAccount("Kestrel Rewards", "Rewards Card", BankAccountType.CreditCard)

    private suspend fun reserve() = bankAccount("Reserve Saver", "Reserve Savings", BankAccountType.Savings)

    private suspend fun joint() = bankAccount("Kestrel Joint", "Joint Checking")

    private fun bankAccountOf(id: Long): StoredBankAccount = slice.connectionStore.bankAccountList.single { it.id == id }

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet. */
    private fun stored(bankAccountId: Long, reference: String, date: LocalDate, amount: Amount, payee: String? = "PLANTED-PAYEE", currency: CurrencyCode = USD): Long {
        val id = nextId++
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = id,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference)),
            bookedDate = date,
            amount = amount,
            currency = currency,
            payee = payee,
            memo = null,
            firstSeenAt = now.minus(Duration.ofHours(1)),
            status = null,
            transferId = null,
        )
        return id
    }

    private fun transaction(id: Long): StoredBankTransaction = slice.importStatusStore.bankTransactions.single { it.id == id }

    private fun status(id: Long): TransactionStatus? = transaction(id).status

    private fun transferOf(id: Long): StoredTransfer? = transaction(id).transferId?.let { transferId -> slice.copyStore.transfers.single { it.id == transferId } }

    private fun legsOf(transfer: StoredTransfer?): Set<Long> = slice.importStatusStore.bankTransactions.filter { it.transferId == transfer?.id }.map { it.id }.toSet()

    /** A Comparison that reads the Budget App; returns how many Transaction Statuses it changed. */
    private suspend fun compare(): Int = ((slice.comparison.readAgain() as ComparisonStart.Ran).report as ComparisonReport.Compared).statusesChanged

    private suspend fun eligibleOf(bankAccountId: Long): Int = slice.importStatusStore.transactionCounts().single { it.bankAccountId == bankAccountId }.eligible

    private suspend fun waitingOf(bankAccountId: Long): Int = slice.importStatusStore.transactionCounts().single { it.bankAccountId == bankAccountId }.waitingForDecision

    private suspend fun shown(bankAccountId: Long) = slice.connect.addedBankAccounts().single { it.bankAccountId == bankAccountId }

    private fun entries(since: Int, kind: JournalEventKind): List<JournalEntry> = slice.journalRecorder.entries.drop(since).filter { it.eventKind == kind }

    private val stored = MappingChangeOutcome.Stored(statusesGiven = true)

    /** What was read from outside Slussen so far: the reads of the Budget App and the Fetches of the Data Provider. */
    private fun readsFromOutside(): List<Int> = listOf(slice.budgetApp.reader.budgetReads, slice.budgetApp.reader.changeReads.size, slice.feeds.fetches)

    /**
     * Kestrel Checking paid 250.00 to Kestrel Rewards, whose Funded By is Checking: a Transfer Slussen proposes, not
     * guessed, stored by a Comparison. Returns (outflow, inflow).
     */
    private suspend fun plainPair(): Pair<Long, Long> {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        return paid to received
    }

    /** The same Card Payment to a card without a Funded By: a Guessed Transfer with both Transfer Legs. Returns (outflow, inflow). */
    private suspend fun guessedPair(): Pair<Long, Long> {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        return paid to received
    }

    private suspend fun decide(leg: Long, choice: TransferChoice, decision: TransferDecision) {
        assertEquals(TransferOutcome.Stored(decision, lonely = false, kept = null, detectedAgain = true), slice.transferDecisions.decide(leg, choice))
    }

    // ---- Step 1: what the Account Mappings show of each Bank Account ---------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Step 1, BR-008: each Bank Account is shown with its Funded By when it is a credit card and its Transfer Partner otherwise, by the name of the Budget Account or as not set, with its Start Date, whether transactions were sent through its Account Mapping and whether it is identified")
    fun stepOneShowsTheFundedByOrTheTransferPartnerOfEachBankAccount() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val saver = reserve()
        val sent = now.minus(Duration.ofDays(2))
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        slice.connectionStore.link(checking, transferPartner = budgetAccount("Reserve Savings"))
        slice.connectionStore.bankAccountList.replaceAll {
            when (it.id) {
                checking -> it.copy(startDate = LocalDate.of(2026, 9, 1), mapping = it.mapping!!.copy(firstSentAt = sent))
                saver -> it.copy(providerReference = null)
                else -> it
            }
        }

        val list = slice.mappings.list().byBudget.single { it.name == "Household" }.bankAccounts.associateBy { it.bankAccountId }

        assertEquals(OtherSideShown(OtherSideKind.FundedBy, budgetAccount("Checking"), "Checking", applies = true), list.getValue(card).otherSide)
        assertEquals(OtherSideShown(OtherSideKind.TransferPartner, budgetAccount("Reserve Savings"), "Reserve Savings", applies = true), list.getValue(checking).otherSide)
        // Not set is shown as such, never as a value that does not apply.
        assertEquals(OtherSideShown(OtherSideKind.TransferPartner, null, null, applies = false), list.getValue(saver).otherSide)
        assertEquals(LocalDate.of(2026, 9, 1), list.getValue(checking).startDate)
        assertNull(list.getValue(card).startDate)
        assertEquals(listOf(true, false, false), listOf(checking, card, saver).map { list.getValue(it).sentThrough })
        assertEquals(listOf(true, true, false), listOf(checking, card, saver).map { list.getValue(it).identified })
        assertTrue(listOf(checking, card, saver).none { list.getValue(it).retired })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Step 1, UC-005 BR-014: a Funded By or Transfer Partner is shown as no longer applying when no Bank Account that Slussen fetches is mapped to its Budget Account, when its Bank Account is retired, or when the Bank Account itself has no Account Mapping; it is kept, and applies again without a change when the Bank Account is restored")
    fun stepOneSaysWhenAFundedByNoLongerApplies() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val saver = reserve()
        val unmapped = bankAccount("Kestrel Old Savings", null, BankAccountType.Savings)
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        // No Bank Account is mapped to Old Savings, so Slussen does not fetch it.
        slice.connectionStore.link(saver, transferPartner = budgetAccount("Old Savings"))
        slice.connectionStore.link(unmapped, transferPartner = budgetAccount("Checking"))

        assertEquals(OtherSideShown(OtherSideKind.TransferPartner, budgetAccount("Old Savings"), "Old Savings", applies = false), shown(saver).otherSide)
        assertEquals(OtherSideShown(OtherSideKind.TransferPartner, budgetAccount("Checking"), "Checking", applies = false), shown(unmapped).otherSide)
        assertTrue(shown(card).otherSide!!.applies)

        assertEquals(stored, slice.mappings.retire(checking))

        assertEquals(OtherSideShown(OtherSideKind.FundedBy, budgetAccount("Checking"), "Checking", applies = false), shown(card).otherSide)
        assertEquals(budgetAccount("Checking"), bankAccountOf(card).fundedByBudgetAccountId)

        assertEquals(stored, slice.mappings.restore(checking))

        assertEquals(OtherSideShown(OtherSideKind.FundedBy, budgetAccount("Checking"), "Checking", applies = true), shown(card).otherSide)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("UC-005 A8 (S-B-15): a Guessed Transfer to a card whose Funded By is set but no longer applies names that Budget Account as not applying, and one to a card without a Funded By names none")
    fun aGuessedTransferNamesTheFundedByThatNoLongerApplies() = runTest {
        ready()
        val card = rewardsCard()
        joint()
        checking()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Old Savings"))
        stored(card, "TX-1", oct13, amount(95), payee = "AUTOPAY")
        compare()

        val notApplying = slice.budgetTransfers.of(household()).transfers.single().whyGuessed

        assertEquals(listOf(WhyGuessed.NoFundedBy("Kestrel Rewards", payingBankAccounts = 2, fundedByNotApplying = "Old Savings")), notApplying)

        assertEquals(stored, slice.mappings.setOtherSide(card, null))

        assertEquals(listOf(WhyGuessed.NoFundedBy("Kestrel Rewards", payingBankAccounts = 2)), slice.budgetTransfers.of(household()).transfers.single().whyGuessed)
    }

    // ---- A4, A5: retire and restore ------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005"])
    @DisplayName("A4, BR-005: retiring marks the Bank Account as a Retired Account and keeps its identification, its Account Mapping, its Start Date and its history; it is listed with the Retired Accounts, none of its Bank Transactions is eligible or waits for a decision, and the change is recorded in the Decision Journal")
    fun retiringKeepsTheIdentificationTheAccountMappingTheStartDateAndTheHistory() = runTest {
        ready()
        val checking = checking()
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(startDate = oct10) else it }
        val bought = stored(checking, "TX-1", oct12, amount(-18))
        compare()
        assertEquals(1, eligibleOf(checking))
        val before = bankAccountOf(checking)
        val journaled = slice.journalRecorder.entries.size
        val version = slice.stateVersion.value

        val outcome = slice.mappings.retire(checking)

        assertEquals(stored, outcome)
        assertEquals(before.copy(retired = true), bankAccountOf(checking))
        assertTrue(slice.stateVersion.value > version)
        assertEquals(listOf("Kestrel Checking"), slice.mappings.list().retired.map { it.name })
        assertTrue(slice.mappings.list().byBudget.none { budget -> budget.bankAccounts.any { it.bankAccountId == checking } })
        assertEquals(listOf(bought), slice.importStatusStore.bankTransactions.filter { it.bankAccountId == checking }.map { it.id })
        assertEquals(0, eligibleOf(checking))
        assertEquals(0, waitingOf(checking))
        val entry = entries(journaled, JournalEventKind.AccountMappingChange).single()
        assertTrue("Kestrel Checking" in entry.details && "retired" in entry.details, entry.details)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005", "BR-015"])
    @DisplayName("A4, BR-015: after retiring, the Transfers are detected again from stored data: a Transfer without a Transfer Decision that has a side in the Retired Account is released, its other Transfer Leg is an ordinary Bank Transaction with its own status, each changed status is journaled, and nothing is read from the Budget App or a Data Provider")
    fun retiringReleasesATransferWithoutADecision() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId
        val reads = readsFromOutside()
        val lastRead = slice.connectionStore.budgets().single { it.name == "Household" }.lastReadAt
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.retire(card))

        assertTrue(slice.copyStore.transfers.isEmpty())
        assertNull(transaction(paid).transferId)
        assertNull(transaction(received).transferId)
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(1, eligibleOf(transaction(paid).bankAccountId))
        assertEquals(0, eligibleOf(card))
        assertEquals(reads, readsFromOutside())
        assertEquals(lastRead, slice.connectionStore.budgets().single { it.name == "Household" }.lastReadAt)
        assertFalse(slice.copyStore.pending)
        // The change is recorded first, then the status it changed (UC-003 step 6).
        assertEquals(
            listOf(JournalEventKind.AccountMappingChange, JournalEventKind.StatusChanged),
            slice.journalRecorder.entries.drop(journaled).map { it.eventKind },
        )
        assertEquals(transaction(paid).importIdentifier, entries(journaled, JournalEventKind.StatusChanged).single().importIdentifier)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A5: Restore a Retired Account", businessRules = ["BR-005", "BR-015"])
    @DisplayName("A5, BR-015: restoring brings the Bank Account back with its identification, Account Mapping, Start Date and history; it is compared again from stored data, so that the Transfer the retiring released is detected again, without a read of the Budget App, and the change is recorded in the Decision Journal")
    fun restoringComparesTheBankAccountAgain() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId
        val before = bankAccountOf(card)
        assertEquals(stored, slice.mappings.retire(card))
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, status(paid))
        val reads = readsFromOutside()
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.restore(card))

        assertEquals(before, bankAccountOf(card))
        assertTrue(slice.mappings.list().retired.isEmpty())
        val transfer = transferOf(paid)!!
        assertEquals(TransferDecision.Proposed, transfer.decision)
        assertEquals(setOf(paid, received), legsOf(transfer))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertEquals(reads, readsFromOutside())
        val entry = entries(journaled, JournalEventKind.AccountMappingChange).single()
        assertTrue("Kestrel Rewards" in entry.details && "restored" in entry.details, entry.details)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005", "BR-015"])
    @DisplayName("A4, A5, UC-005 BR-015 (W2-0 item 9): retiring keeps the Account Mapping, so a Transfer the Operator confirmed is not dissolved: it keeps its decision, its Transfer Legs and its Budget Accounts and is in no Upload Plan, from either side, while a side is a Retired Account, nothing is journaled as dropped, and restoring brings it back confirmed and eligible")
    fun retiringKeepsAConfirmedTransferOutOfEveryUploadPlanAndRestoringBringsItBack() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Confirm, TransferDecision.Confirmed)
        val confirmed = transferOf(paid)!!
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        assertEquals(1, eligibleOf(checking))
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.retire(card))

        assertEquals(bankAccountOf(card).mapping!!.budgetAccountId, budgetAccount("Rewards Card"))
        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(confirmed))
        assertEquals(0, eligibleOf(checking))
        assertEquals(0, eligibleOf(card))
        assertEquals(TransferEligibility.RetiredSide, slice.budgetTransfers.of(household()).transfers.single().eligibility)
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isEmpty())

        assertEquals(stored, slice.mappings.restore(card))

        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(confirmed))
        assertEquals(1, eligibleOf(checking))
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isEmpty())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-015"])
    @DisplayName("A4, A5, UC-005 BR-007, BR-015: a Transfer the Operator holds back stays held while the Bank Account of a side is a Retired Account and after it is restored; it never comes back as a Transfer Slussen proposes and would upload")
    fun retiringAndRestoringKeepAHeldTransferHeld() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Hold, TransferDecision.Held)
        val held = transferOf(paid)!!
        val checking = transaction(paid).bankAccountId

        assertEquals(stored, slice.mappings.retire(checking))

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(0, eligibleOf(transaction(received).bankAccountId))

        assertEquals(stored, slice.mappings.restore(checking))

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(0, eligibleOf(checking))
        assertEquals(0, eligibleOf(transaction(received).bankAccountId))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-005"])
    @DisplayName("A4, A5: retiring a Retired Account, restoring a Bank Account that is not retired and a Bank Account that does not exist store nothing, journal nothing and leave state_version and the Comparison marker as they are")
    fun retiringARetiredAccountChangesNothing() = runTest {
        ready()
        val checking = checking()
        assertEquals(stored, slice.mappings.retire(checking))
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.retire(checking))
        assertEquals(MappingChangeOutcome.NotFound, slice.mappings.retire(-1))
        assertEquals(MappingChangeOutcome.NotFound, slice.mappings.restore(-1))
        assertTrue(bankAccountOf(checking).retired)
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)

        assertEquals(stored, slice.mappings.restore(checking))
        val restored = slice.stateVersion.value
        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.restore(checking))
        assertFalse(bankAccountOf(checking).retired)
        assertEquals(restored, slice.stateVersion.value)
        assertFalse(slice.copyStore.pending)
    }

    // ---- A9: Funded By and Transfer Partner ----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008"])
    @DisplayName("A9 step 1, UC-005 BR-014: Slussen shows the Budget Accounts of the same Budget that it fetches, other than the Bank Account's own: never a Manually Kept Account, one no Bank Account is mapped to, one only a Retired Account is mapped to or one of another Budget; for a credit card it is the Funded By, otherwise the Transfer Partner")
    fun onlyFetchedBudgetAccountsOfTheSameBudgetAreOffered() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        reserve()
        val retiredJoint = joint()
        bankAccount("Aurora Current", "Current", currency = EUR)
        slice.connect.setReceivesFromSlussen(budgetAccount("Travel Card"), receives = false)
        assertEquals(stored, slice.mappings.retire(retiredJoint))

        val ofCard = slice.mappings.otherSide(card)!!
        val ofChecking = slice.mappings.otherSide(checking)!!

        assertEquals(OtherSideKind.FundedBy, ofCard.kind)
        assertEquals("Kestrel Rewards", ofCard.name)
        assertEquals(listOf("Checking" to "Kestrel Checking", "Reserve Savings" to "Reserve Saver"), ofCard.choices.map { it.budgetAccountName to it.bankAccountName })
        assertEquals(OtherSideShown(OtherSideKind.FundedBy, null, null, applies = false), ofCard.current)
        assertEquals(OtherSideKind.TransferPartner, ofChecking.kind)
        assertEquals(listOf("Rewards Card", "Reserve Savings"), ofChecking.choices.map { it.budgetAccountName })
        assertNull(slice.mappings.otherSide(-1))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008", "BR-015"])
    @DisplayName("A9, BR-015: setting the Funded By of a credit card stores it and detects the Transfers again from stored data: the Guessed Transfer to the card is a Transfer that is not guessed and is eligible; clearing it makes it a Guessed Transfer again; each change records the old and the new value in the Decision Journal, and nothing is read from the Budget App")
    fun settingAndClearingAFundedByDetectsTheTransfersAgain() = runTest {
        val (paid, received) = guessedPair()
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        assertEquals(0, eligibleOf(checking))
        val reads = readsFromOutside()
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.setOtherSide(card, budgetAccount("Checking")))

        assertEquals(budgetAccount("Checking"), bankAccountOf(card).fundedByBudgetAccountId)
        assertNull(bankAccountOf(card).transferPartnerBudgetAccountId)
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertFalse(transferOf(paid)!!.guessed)
        assertEquals(1, eligibleOf(checking))
        val set = entries(journaled, JournalEventKind.AccountMappingChange).single()
        assertTrue("Kestrel Rewards" in set.details && "Funded By" in set.details && "none" in set.details && "Checking" in set.details, set.details)
        val afterSet = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.setOtherSide(card, null))

        assertNull(bankAccountOf(card).fundedByBudgetAccountId)
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        assertEquals(0, eligibleOf(checking))
        assertEquals(reads, readsFromOutside())
        val cleared = entries(afterSet, JournalEventKind.AccountMappingChange).single()
        assertTrue("Funded By" in cleared.details && "Checking" in cleared.details && "none" in cleared.details, cleared.details)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008", "BR-015"])
    @DisplayName("A9, BR-015: setting the Transfer Partner of a Bank Account that is not a credit card makes its Bank Transaction that matches a Bank Transfer Description a Lonely Transfer towards that Budget Account, and changing it to another Budget Account moves the Lonely Transfer there")
    fun settingATransferPartnerDetectsALonelyTransfer() = runTest {
        ready()
        val checking = checking()
        reserve()
        joint()
        val moved = stored(checking, "TX-1", oct13, amount(-400), payee = "ONLINE TRANSFER")
        compare()
        assertEquals(TransactionStatus.New, status(moved))

        assertEquals(stored, slice.mappings.setOtherSide(checking, budgetAccount("Reserve Savings")))

        assertEquals(budgetAccount("Reserve Savings"), bankAccountOf(checking).transferPartnerBudgetAccountId)
        assertNull(bankAccountOf(checking).fundedByBudgetAccountId)
        assertEquals(TransactionStatus.TransferLeg, status(moved))
        assertEquals(budgetAccount("Reserve Savings"), transferOf(moved)!!.inflowBudgetAccountId)

        assertEquals(stored, slice.mappings.setOtherSide(checking, budgetAccount("Joint Checking")))

        assertEquals(budgetAccount("Joint Checking"), transferOf(moved)!!.inflowBudgetAccountId)
        assertEquals(1, slice.copyStore.transfers.size)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-015"])
    @DisplayName("A9, UC-005 BR-015: a change of a Funded By never changes a Transfer the Operator decided on: a confirmed Transfer keeps its decision, its Transfer Legs and its two Budget Accounts when the card's Funded By is set to another Budget Account, and nothing is journaled as dropped")
    fun aChangeOfTheFundedByKeepsADecidedTransfer() = runTest {
        val (paid, received) = plainPair()
        reserve()
        decide(paid, TransferChoice.Confirm, TransferDecision.Confirmed)
        val confirmed = transferOf(paid)!!
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.setOtherSide(transaction(received).bankAccountId, budgetAccount("Reserve Savings")))

        assertEquals(listOf(confirmed), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(confirmed))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(1, eligibleOf(transaction(paid).bankAccountId))
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isEmpty())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008"])
    @DisplayName("A9, BR-008, UC-005 BR-014: a Budget Account that is the Bank Account's own, a Manually Kept Account, of another Budget, fed by no Bank Account or only by a Retired Account, or unknown, is refused: nothing is stored or journaled, state_version stays and no Comparison is pending; the value it already has, and clearing none, change nothing either")
    fun aBudgetAccountSlussenDoesNotFetchIsRefused() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val retiredJoint = joint()
        bankAccount("Aurora Current", "Current", currency = EUR)
        slice.connect.setReceivesFromSlussen(budgetAccount("Travel Card"), receives = false)
        assertEquals(stored, slice.mappings.retire(retiredJoint))
        assertEquals(stored, slice.mappings.setOtherSide(card, budgetAccount("Checking")))
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        listOf("Rewards Card", "Travel Card", "Current", "Old Savings", "Joint Checking").forEach { name ->
            assertEquals(MappingChangeOutcome.NotOffered, slice.mappings.setOtherSide(card, budgetAccount(name)), name)
        }
        assertEquals(MappingChangeOutcome.NotOffered, slice.mappings.setOtherSide(card, -1))
        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.setOtherSide(card, budgetAccount("Checking")))
        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.setOtherSide(checking, null))
        assertEquals(MappingChangeOutcome.NotFound, slice.mappings.setOtherSide(-1, budgetAccount("Checking")))

        assertEquals(budgetAccount("Checking"), bankAccountOf(card).fundedByBudgetAccountId)
        assertNull(bankAccountOf(checking).transferPartnerBudgetAccountId)
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-008"])
    @DisplayName("BR-008 (O-13): the storage itself refuses a Funded By for a Bank Account that is not a credit card, a Transfer Partner for a credit card, and a Budget Account that is its own, of another Budget, a Manually Kept Account or fed by no Bank Account that is not a Retired Account, storing nothing; it stores one that is allowed and clears one")
    fun theStorageRefusesAFundedByTheEntityModelForbids() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val retiredJoint = joint()
        bankAccount("Aurora Current", "Current", currency = EUR)
        slice.connect.setReceivesFromSlussen(budgetAccount("Travel Card"), receives = false)
        slice.connectionStore.setRetired(retiredJoint, true)
        slice.copyStore.pending = false
        val version = slice.stateVersion.value
        val store = slice.connectionStore

        assertEquals(OtherSideStored.NotAllowed, store.setOtherSide(checking, OtherSideKind.FundedBy, budgetAccount("Rewards Card")))
        assertEquals(OtherSideStored.NotAllowed, store.setOtherSide(card, OtherSideKind.TransferPartner, budgetAccount("Checking")))
        listOf("Rewards Card", "Travel Card", "Current", "Old Savings", "Joint Checking").forEach { name ->
            assertEquals(OtherSideStored.NotAllowed, store.setOtherSide(card, OtherSideKind.FundedBy, budgetAccount(name)), name)
        }
        assertEquals(OtherSideStored.NotAllowed, store.setOtherSide(card, OtherSideKind.FundedBy, -1))
        assertEquals(OtherSideStored.NotFound, store.setOtherSide(-1, OtherSideKind.FundedBy, budgetAccount("Checking")))
        assertNull(bankAccountOf(card).fundedByBudgetAccountId)
        assertEquals(version, slice.stateVersion.value)
        assertFalse(slice.copyStore.pending)

        assertEquals(OtherSideStored.Stored, store.setOtherSide(card, OtherSideKind.FundedBy, budgetAccount("Checking")))
        assertEquals(budgetAccount("Checking"), bankAccountOf(card).fundedByBudgetAccountId)
        assertTrue(slice.stateVersion.value > version)
        assertTrue(slice.copyStore.pending)
        assertEquals(OtherSideStored.Stored, store.setOtherSide(card, OtherSideKind.FundedBy, null))
        assertNull(bankAccountOf(card).fundedByBudgetAccountId)
    }

    // ---- A12: rename ---------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A12: Rename a Bank Account", businessRules = ["BR-010", "BR-011"])
    @DisplayName("A12, BR-011: renaming stores the new name and changes nothing else: the identifier, the identification, the Account Mapping, every Transaction Status and Transfer stay, no Comparison is pending, and the old and the new name are recorded in the Decision Journal")
    fun renamingStoresTheNameAndChangesNothingElse() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        val before = bankAccountOf(checking)
        val transactions = slice.importStatusStore.bankTransactions.toList()
        val transfers = slice.copyStore.transfers.toList()
        val journaled = slice.journalRecorder.entries.size
        val version = slice.stateVersion.value

        val outcome = slice.mappings.rename(checking, "  Everyday Checking ")

        assertEquals(stored, outcome)
        assertEquals(before.copy(name = "Everyday Checking"), bankAccountOf(checking))
        assertEquals(transactions, slice.importStatusStore.bankTransactions.toList())
        assertEquals(transfers, slice.copyStore.transfers.toList())
        assertEquals(TransactionStatus.TransferLeg, status(received))
        assertFalse(slice.copyStore.pending)
        assertTrue(slice.stateVersion.value > version)
        val entry = slice.journalRecorder.entries.drop(journaled).single()
        assertEquals(JournalEventKind.AccountMappingChange, entry.eventKind)
        assertTrue("Kestrel Checking" in entry.details && "Everyday Checking" in entry.details, entry.details)
        assertEquals("Everyday Checking", shown(checking).name)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A12: Rename a Bank Account", businessRules = ["BR-011"])
    @DisplayName("A12: an empty name, a name longer than the 100 characters a Bank Account's name holds, the name it already has and a Bank Account that does not exist store nothing and journal nothing")
    fun aNameThatIsNotValidIsNotStored() = runTest {
        ready()
        val checking = checking()
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        assertEquals(MappingChangeOutcome.NameNotValid, slice.mappings.rename(checking, "   "))
        assertEquals(MappingChangeOutcome.NameNotValid, slice.mappings.rename(checking, "K".repeat(101)))
        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.rename(checking, " Kestrel Checking "))
        assertEquals(MappingChangeOutcome.NotFound, slice.mappings.rename(-1, "Everyday Checking"))

        assertEquals("Kestrel Checking", bankAccountOf(checking).name)
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertEquals(stored, slice.mappings.rename(checking, "K".repeat(100)))
    }

    // ---- A13: Start Date -----------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A13: Set, Change or Clear the Start Date", businessRules = ["BR-015"])
    @DisplayName("A13, BR-015, UC-003 BR-018: setting the Start Date stores it, records the change and gives the Bank Transactions of the Bank Account their Transaction Status again from stored data: one booked before it is Before Start Date and no longer eligible, one booked on it stays New; changing and clearing it give the statuses again, and nothing is read from the Budget App, whose time of the read stays")
    fun theStartDateGivesTheStatusesAgain() = runTest {
        ready()
        val checking = checking()
        val early = stored(checking, "TX-1", oct10, amount(-18))
        val onTheDay = stored(checking, "TX-2", oct12, amount(-19))
        compare()
        assertEquals(2, eligibleOf(checking))
        val reads = readsFromOutside()
        val lastRead = slice.connectionStore.budgets().single { it.name == "Household" }.lastReadAt
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.setStartDate(checking, oct12))

        assertEquals(oct12, bankAccountOf(checking).startDate)
        assertEquals(TransactionStatus.BeforeStartDate, status(early))
        assertEquals(TransactionStatus.New, status(onTheDay))
        assertEquals(1, eligibleOf(checking))
        val set = slice.journalRecorder.entries.drop(journaled)
        assertEquals(listOf(JournalEventKind.AccountMappingChange, JournalEventKind.StatusChanged), set.map { it.eventKind })
        assertTrue("Kestrel Checking" in set.first().details && "Start Date" in set.first().details && "none" in set.first().details && "2026-10-12" in set.first().details, set.first().details)
        assertEquals(transaction(early).importIdentifier, set.last().importIdentifier)

        assertEquals(stored, slice.mappings.setStartDate(checking, oct13))
        assertEquals(TransactionStatus.BeforeStartDate, status(onTheDay))
        assertEquals(0, eligibleOf(checking))

        assertEquals(stored, slice.mappings.setStartDate(checking, null))
        assertNull(bankAccountOf(checking).startDate)
        assertEquals(TransactionStatus.New, status(early))
        assertEquals(TransactionStatus.New, status(onTheDay))
        assertEquals(2, eligibleOf(checking))
        assertEquals(reads, readsFromOutside())
        assertEquals(lastRead, slice.connectionStore.budgets().single { it.name == "Household" }.lastReadAt)
        assertTrue("2026-10-13" in slice.journalRecorder.entries.last { it.eventKind == JournalEventKind.AccountMappingChange }.details)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A13: Set, Change or Clear the Start Date", businessRules = ["BR-015"])
    @DisplayName("A13, BR-015, UC-005 BR-010: a Transfer Leg that the new Start Date makes Before Start Date stays a Transfer Leg of its Transfer, and the whole Transfer is in no Upload Plan, from either side, until the Start Date is cleared")
    fun aTransferLegBeforeTheStartDateKeepsItsTransferOutOfEveryUploadPlan() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        assertEquals(1, eligibleOf(checking))

        assertEquals(stored, slice.mappings.setStartDate(card, LocalDate.of(2026, 10, 14)))

        assertEquals(TransactionStatus.BeforeStartDate, status(received))
        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertEquals(setOf(paid, received), legsOf(transferOf(paid)))
        assertEquals(0, eligibleOf(checking))
        assertEquals(0, eligibleOf(card))

        assertEquals(stored, slice.mappings.setStartDate(card, null))
        assertEquals(1, eligibleOf(checking))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A13: Set, Change or Clear the Start Date")
    @DisplayName("A13: the Start Date a Bank Account already has, clearing none and a Bank Account that does not exist store nothing and journal nothing")
    fun theSameStartDateChangesNothing() = runTest {
        ready()
        val checking = checking()
        assertEquals(stored, slice.mappings.setStartDate(checking, oct12))
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.setStartDate(checking, oct12))
        assertEquals(MappingChangeOutcome.NotFound, slice.mappings.setStartDate(-1, oct12))
        assertEquals(stored, slice.mappings.setStartDate(checking, null))
        val cleared = slice.stateVersion.value
        assertEquals(MappingChangeOutcome.Unchanged, slice.mappings.setStartDate(checking, null))

        assertTrue(cleared > version)
        assertEquals(cleared, slice.stateVersion.value)
        assertEquals(journaled + 1, slice.journalRecorder.entries.size)
    }

    // ---- A14 step 4: the Transfers after a change of the Bank Account type ------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012", "BR-015"])
    @DisplayName("A14 step 4, BR-015: after a credit card becomes another Bank Account, the Transfers are detected again from stored data: the Card Payment Slussen proposed is no Transfer any more, both Bank Transactions are New and eligible, each changed status is journaled after the change, and nothing is read from the Budget App")
    fun aCardThatBecomesAnotherBankAccountNoLongerMakesACardPayment() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId
        val reads = readsFromOutside()
        val journaled = slice.journalRecorder.entries.size

        val outcome = slice.mappings.changeTypeAndBank(card, BankAccountType.Savings, BankChoice.Known(bankAccountOf(card).bankId), clearingConfirmed = true)

        assertEquals(TypeAndBankOutcome.Stored, outcome)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertNull(transaction(paid).transferId)
        assertNull(transaction(received).transferId)
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertEquals(1, eligibleOf(card))
        assertEquals(reads, readsFromOutside())
        assertFalse(slice.copyStore.pending)
        assertEquals(
            listOf(JournalEventKind.AccountMappingChange, JournalEventKind.StatusChanged, JournalEventKind.StatusChanged),
            slice.journalRecorder.entries.drop(journaled).map { it.eventKind },
        )
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012", "BR-015"])
    @DisplayName("A14 step 4, BR-015: after another Bank Account becomes a credit card, two Bank Transactions of equal and opposite amounts that were ordinary are detected as a Card Payment: a Guessed Transfer, since the card has no Funded By, which waits for the Operator and is not eligible")
    fun aBankAccountThatBecomesACardMakesACardPayment() = runTest {
        ready()
        val checking = checking()
        val other = joint()
        val paid = stored(checking, "TX-1", oct12, amount(-80))
        val received = stored(other, "TX-2", oct13, amount(80))
        compare()
        assertEquals(listOf(TransactionStatus.New, TransactionStatus.New), listOf(status(paid), status(received)))

        val outcome = slice.mappings.changeTypeAndBank(other, BankAccountType.CreditCard, BankChoice.Known(bankAccountOf(other).bankId))

        assertEquals(TypeAndBankOutcome.Stored, outcome)
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        assertEquals(setOf(paid, received), legsOf(transferOf(paid)))
        assertEquals(0, eligibleOf(checking))
        assertEquals(1, waitingOf(checking))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-015"])
    @DisplayName("A14 step 4, UC-005 BR-015: a confirmed Card Payment neither of whose sides is a credit card after the change loses its Transfer Decision: the Transfer is dissolved, the dropped decision is journaled with that reason for each Transfer Leg, and its Bank Transactions are detected again as any others")
    fun aTypeChangeDropsAConfirmedCardPayment() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Confirm, TransferDecision.Confirmed)
        val card = transaction(received).bankAccountId
        val journaled = slice.journalRecorder.entries.size

        val outcome = slice.mappings.changeTypeAndBank(card, BankAccountType.Other, BankChoice.Known(bankAccountOf(card).bankId), clearingConfirmed = true)

        assertEquals(TypeAndBankOutcome.Stored, outcome)
        assertTrue(slice.copyStore.transfers.isEmpty())
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        val dropped = entries(journaled, JournalEventKind.OperatorDecision)
        assertEquals(setOf(transaction(paid).importIdentifier, transaction(received).importIdentifier), dropped.map { it.importIdentifier }.toSet())
        dropped.forEach { assertTrue(it.details.startsWith("Slussen dropped the Transfer Decision Confirmed because ${TransferDropReason.TypeNoLongerFits.text}"), it.details) }
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-015"])
    @DisplayName("A14 step 4, UC-005 BR-007, BR-015 (S-B-17): a Card Payment the Operator holds back is never dissolved by the change of the type: it stays held with both Transfer Legs and nothing of it is eligible")
    fun aTypeChangeNeverDissolvesAHeldTransfer() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Hold, TransferDecision.Held)
        val held = transferOf(paid)!!
        val card = transaction(received).bankAccountId

        val outcome = slice.mappings.changeTypeAndBank(card, BankAccountType.Other, BankChoice.Known(bankAccountOf(card).bankId), clearingConfirmed = true)

        assertEquals(TypeAndBankOutcome.Stored, outcome)
        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertEquals(0, eligibleOf(card))
        assertEquals(0, eligibleOf(transaction(paid).bankAccountId))
    }

    // ---- BR-015: the statuses after a change -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-015"])
    @DisplayName("BR-015: the statuses given again after a change are those a Comparison gives from the same stored data: a Comparison that reads the Budget App right after, and finds nothing changed there, changes no Transaction Status and no Transfer")
    fun theStatusesAfterAChangeAreThoseAComparisonGives() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        reserve()
        stored(checking, "TX-3", oct10, amount(-18))
        stored(checking, "TX-4", oct13, amount(-400), payee = "ONLINE TRANSFER")
        compare()

        assertEquals(stored, slice.mappings.setStartDate(checking, oct12))
        assertEquals(stored, slice.mappings.setOtherSide(checking, budgetAccount("Reserve Savings")))
        assertEquals(TypeAndBankOutcome.Stored, slice.mappings.changeTypeAndBank(card, BankAccountType.Other, BankChoice.Known(bankAccountOf(card).bankId), clearingConfirmed = true))
        val statuses = slice.importStatusStore.bankTransactions.associate { it.id to (it.status to it.transferId) }
        val transfers = slice.copyStore.transfers.toList()
        assertEquals(
            setOf(TransactionStatus.BeforeStartDate, TransactionStatus.New, TransactionStatus.TransferLeg),
            slice.importStatusStore.bankTransactions.map { it.status }.toSet(),
        )

        assertEquals(0, compare())

        assertEquals(statuses, slice.importStatusStore.bankTransactions.associate { it.id to (it.status to it.transferId) })
        assertEquals(transfers, slice.copyStore.transfers.toList())
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-015"])
    @DisplayName("BR-015, UC-003 A1: the Bank Transactions of a Bank Account mapped into a Budget Slussen has not read yet are not compared yet after a change, and one stored since its Budget's last successful read gets no status either")
    fun aBudgetNotReadYetIsNotComparedAfterAChange() = runTest {
        ready()
        val checking = checking()
        val bought = stored(checking, "TX-1", oct12, amount(-18))
        compare()
        val current = bankAccount("Aurora Current", "Current", currency = EUR)
        val abroad = stored(current, "TX-2", oct12, amount(-30), currency = EUR)
        val since = stored(checking, "TX-3", oct13, amount(-21))
        slice.importStatusStore.bankTransactions.replaceAll { if (it.id == since) it.copy(firstSeenAt = now.plus(Duration.ofMinutes(5))) else it }

        assertEquals(stored, slice.mappings.setStartDate(current, oct10))
        assertEquals(stored, slice.mappings.setStartDate(checking, oct13))

        assertNull(status(abroad))
        assertNull(status(since))
        assertEquals(TransactionStatus.BeforeStartDate, status(bought))
        assertNull(slice.connectionStore.budgets().single { it.name == "Summer House" }.lastReadAt)
    }

    /**
     * BR-015: [change] stores its change and the Comparison marker in one step; while the conclusions cannot be stored, as
     * when Slussen stops between the two, [paid] keeps the status it had and the marker blocks every Upload Plan (ADR-007
     * §3); the next start concludes from stored data, without a read, and gives [after].
     */
    private suspend fun assertConcludedAtTheNextStart(paid: Long, after: TransactionStatus, notGivenYet: Any, change: suspend () -> Any) {
        val before = status(paid)
        val reads = readsFromOutside()
        slice.copyStore.failsToConclude = true

        val outcome = change()

        assertEquals(notGivenYet, outcome)
        assertEquals(before, status(paid))
        assertTrue(slice.copyStore.pending)

        // The next start (ADR-007 §8): the composition root asks this before any other operation.
        slice.copyStore.failsToConclude = false
        assertTrue(slice.comparison.recomputePending())

        assertFalse(slice.copyStore.pending)
        assertEquals(after, status(paid))
        assertEquals(reads, readsFromOutside())
    }

    private val storedNotGivenYet = MappingChangeOutcome.Stored(statusesGiven = false)

    private fun <T> valueOf(result: OperationResult<T>): T = (result as OperationResult.Ran<T>).value

    @Test
    @UseCase(id = "UC-009", scenario = "A4: Retire a Bank Account", businessRules = ["BR-015"])
    @DisplayName("BR-015: retiring is stored together with the Comparison marker: when Slussen stops before the statuses are given again, the change is said to be stored with the statuses still to give, no Upload Plan can be prepared from the old statuses, and the next start gives them from stored data")
    fun aStopAfterRetiringIsConcludedAtTheNextStart() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId

        assertConcludedAtTheNextStart(paid, TransactionStatus.New, storedNotGivenYet) { slice.mappings.retire(card) }

        assertTrue(bankAccountOf(card).retired)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A5: Restore a Retired Account", businessRules = ["BR-015"])
    @DisplayName("BR-015: restoring is stored together with the Comparison marker: a stop before the statuses are given again is concluded at the next start from stored data")
    fun aStopAfterRestoringIsConcludedAtTheNextStart() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId
        assertEquals(stored, slice.mappings.retire(card))
        assertEquals(TransactionStatus.New, status(paid))

        assertConcludedAtTheNextStart(paid, TransactionStatus.TransferLeg, storedNotGivenYet) { slice.mappings.restore(card) }

        assertFalse(bankAccountOf(card).retired)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A9: Set, Change or Clear Funded By or Transfer Partner", businessRules = ["BR-015"])
    @DisplayName("BR-015: a Funded By is stored together with the Comparison marker: a stop before the Transfers are detected again is concluded at the next start from stored data")
    fun aStopAfterSettingAFundedByIsConcludedAtTheNextStart() = runTest {
        val (paid, received) = guessedPair()
        val card = transaction(received).bankAccountId
        val checking = budgetAccount("Checking")

        assertConcludedAtTheNextStart(paid, TransactionStatus.TransferLeg, storedNotGivenYet) { slice.mappings.setOtherSide(card, checking) }

        assertEquals(checking, bankAccountOf(card).fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A13: Set, Change or Clear the Start Date", businessRules = ["BR-015"])
    @DisplayName("BR-015: a Start Date is stored together with the Comparison marker: a stop before the statuses are given again is concluded at the next start from stored data")
    fun aStopAfterSettingTheStartDateIsConcludedAtTheNextStart() = runTest {
        ready()
        val checking = checking()
        val early = stored(checking, "TX-1", oct10, amount(-18))
        compare()

        assertConcludedAtTheNextStart(early, TransactionStatus.BeforeStartDate, storedNotGivenYet) { slice.mappings.setStartDate(checking, oct12) }

        assertEquals(oct12, bankAccountOf(checking).startDate)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-015"])
    @DisplayName("BR-015: a Bank Account type is stored together with the Comparison marker: when Slussen stops before the Transfers are detected again, the change is said to be stored with the Transfers still to detect, and the next start detects them from stored data")
    fun aStopAfterChangingTheTypeIsConcludedAtTheNextStart() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId
        val bank = BankChoice.Known(bankAccountOf(card).bankId)

        assertConcludedAtTheNextStart(paid, TransactionStatus.New, TypeAndBankOutcome.StoredNotDetectedYet) {
            slice.mappings.changeTypeAndBank(card, BankAccountType.Savings, bank, clearingConfirmed = true)
        }

        assertEquals(BankAccountType.Savings, bankAccountOf(card).type)
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-015"])
    @DisplayName("BR-015 (ADR-007 §3a): the statuses are given again by one operation of the core that runs the change and the conclusions on the coordinator's lane: it concludes only when the change left the Comparison marker, says whether a Comparison is still pending after it, and is refused, without running the change, while an Upload is written")
    fun theOperationThatGivesTheStatusesAgainFollowsTheMarker() = runTest {
        val (paid, received) = plainPair()
        val card = transaction(received).bankAccountId
        val version = slice.stateVersion.value

        // A change that stores nothing leaves no marker: no conclusions are stored for it.
        val nothing = valueOf(slice.comparison.statusesAgainAfter(OperationKind.AccountMappingChange) { "nothing stored" })

        assertEquals("nothing stored", nothing.change)
        assertTrue(nothing.statusesGiven)
        assertEquals(version, slice.stateVersion.value)

        // A change whose storage operation leaves the marker is concluded within the same operation.
        val retired = valueOf(slice.comparison.statusesAgainAfter(OperationKind.AccountMappingChange) { slice.connectionStore.setRetired(card, true) })

        assertTrue(retired.change)
        assertTrue(retired.statusesGiven)
        assertFalse(slice.copyStore.pending)
        assertEquals(TransactionStatus.New, status(paid))

        var ran = false
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        val refused = slice.comparison.statusesAgainAfter(OperationKind.AccountMappingChange) { ran = true }

        assertEquals(OperationResult.Refused(OperationRefusal.UploadBeingWritten), refused)
        assertFalse(ran)
    }

    // ---- Admission and failure postconditions -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-012"])
    @DisplayName("Precondition (UC-006 BR-005): while an Upload Plan awaits confirmation or an Upload is written, retiring, restoring, renaming, the Start Date and the Funded By are refused with the reason and nothing is stored or journaled")
    fun everyChangeWaitsForTheUpload() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val before = slice.connectionStore.bankAccountList.toList()
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size
        val changes: List<suspend () -> MappingChangeOutcome> = listOf(
            { slice.mappings.retire(checking) },
            { slice.mappings.restore(checking) },
            { slice.mappings.rename(checking, "Everyday Checking") },
            { slice.mappings.setStartDate(checking, oct12) },
            { slice.mappings.setOtherSide(card, budgetAccount("Checking")) },
        )

        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        changes.forEach { assertEquals(MappingChangeOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), it()) }
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        changes.forEach { assertEquals(MappingChangeOutcome.Unavailable(OperationRefusal.UploadBeingWritten), it()) }

        assertEquals(before, slice.connectionStore.bankAccountList.toList())
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-009")
    @DisplayName("Failure postconditions: when the stored data cannot be written, every change answers that nothing was stored instead of failing: the Bank Account, every Transaction Status and Transfer and state_version are unchanged, nothing is journaled and no Comparison is pending")
    fun aStorageFailureStoresNothing() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        val reserve = budgetAccount("Reserve Savings")
        reserve()
        val bankAccounts = slice.connectionStore.bankAccountList.toList()
        val transactions = slice.importStatusStore.bankTransactions.toList()
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size
        val bank = BankChoice.Known(bankAccountOf(card).bankId)
        slice.connectionStore.failsToStore = true

        assertEquals(MappingChangeOutcome.NotStored, slice.mappings.retire(checking))
        assertEquals(MappingChangeOutcome.NotStored, slice.mappings.rename(checking, "Everyday Checking"))
        assertEquals(MappingChangeOutcome.NotStored, slice.mappings.setStartDate(checking, oct13))
        assertEquals(MappingChangeOutcome.NotStored, slice.mappings.setOtherSide(card, reserve))
        assertEquals(TypeAndBankOutcome.NotStored, slice.mappings.changeTypeAndBank(card, BankAccountType.Savings, bank, clearingConfirmed = true))

        assertEquals(bankAccounts, slice.connectionStore.bankAccountList.toList())
        assertEquals(transactions, slice.importStatusStore.bankTransactions.toList())
        assertEquals(version, slice.stateVersion.value)
        assertEquals(journaled, slice.journalRecorder.entries.size)
        assertFalse(slice.copyStore.pending)
    }

    @Test
    @UseCase(id = "UC-009")
    @DisplayName("Postconditions: with the Decision Journal switched off every change is still stored and the statuses are still given again, and nothing is recorded")
    fun withTheJournalOffTheChangesAreStillStored() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        val card = transaction(received).bankAccountId
        slice.settings.settings = slice.settings.settings.copy(journalOn = false)
        val journaled = slice.journalRecorder.entries.size

        assertEquals(stored, slice.mappings.rename(checking, "Everyday Checking"))
        assertEquals(stored, slice.mappings.setStartDate(checking, oct13))
        assertEquals(stored, slice.mappings.setOtherSide(card, null))
        assertEquals(stored, slice.mappings.retire(card))

        assertEquals("Everyday Checking", bankAccountOf(checking).name)
        assertEquals(TransactionStatus.BeforeStartDate, status(paid))
        assertNull(bankAccountOf(card).fundedByBudgetAccountId)
        assertTrue(bankAccountOf(card).retired)
        assertEquals(journaled, slice.journalRecorder.entries.size)
    }
}
