package slussen.core.mapping

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonReport
import slussen.core.comparison.ComparisonStart
import slussen.core.connection.OtherSideShown
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationRefusal
import slussen.core.identity.BankAccountFingerprint
import slussen.core.port.BankAccountType
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.value.BankAccountNumber
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.IdentificationStored
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.MappingStored
import slussen.core.port.NewIdentification
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.OtherSideKind
import slussen.core.port.SentRecordOutcome
import slussen.core.port.SentThrough
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredSentRecord
import slussen.core.port.StoredTransfer
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDropReason
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
 * Manage Account Mappings (UC-009), what phase B5 of the build-out builds: confirming and identifying a Bank Account
 * (steps 2-7, A1-A3, A6, A7, A10, BR-004, BR-007), moving, storing and removing an Account Mapping (A8, A11, BR-003,
 * S-B-10, S-B-11) and the statuses given again after them (BR-015), with what a move or a removal does to a Transfer the
 * Operator decided on (UC-005 BR-015, S-B-17), through the core's entry functions on the fakes of `:testing`. No Upload
 * writes a Sent Record before Wave 4: the locks are tested with Sent Records planted in the stored data. Every name,
 * amount, payee and identifier is invented (C-005).
 */
@DisplayName("UC-009: Manage Account Mappings, identification and moves")
class UC009IdentificationAndMovesTest {
    private val slice = TestSlice()
    private val now: Instant = slice.clock.instant()
    private var nextId = 7000L

    private val oct12: LocalDate = LocalDate.of(2026, 10, 12)
    private val oct13: LocalDate = LocalDate.of(2026, 10, 13)

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** The credentials are accepted and Household (USD), Summer House (EUR) and Care Fund (USD) are served; Household has Travel Card, Joint Checking and Old Savings too. */
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

    private suspend fun kestrel(): Long = slice.connectionStore.bankNamed("Kestrel Bank").id

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
            bankId = kestrel(),
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

    private suspend fun joint() = bankAccount("Kestrel Joint", "Joint Checking")

    private fun bankAccountOf(id: Long): StoredBankAccount = slice.connectionStore.bankAccountList.single { it.id == id }

    /** A Bank Transaction a Fetch stored (UC-001 step 8), not compared yet. */
    private fun stored(bankAccountId: Long, reference: String, date: LocalDate, amount: Amount, payee: String? = "PLANTED-PAYEE"): Long {
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
            currency = USD,
            payee = payee,
            memo = null,
            firstSeenAt = now.minus(Duration.ofHours(1)),
            status = null,
            transferId = null,
        )
        return id
    }

    /** A Sent Record of [transaction] to [budgetAccountId], planted as the Uploads of Wave 4 will write one (UC-006 BR-006). */
    private fun sent(transaction: Long, budgetAccountId: Long, outcome: SentRecordOutcome = SentRecordOutcome.Written) {
        slice.copyStore.sentRecords += StoredSentRecord(transaction, budgetAccountId, sentAsTransfer = false, outcome = outcome)
    }

    private fun transaction(id: Long): StoredBankTransaction = slice.importStatusStore.bankTransactions.single { it.id == id }

    private fun status(id: Long): TransactionStatus? = transaction(id).status

    private fun transferOf(id: Long): StoredTransfer? = transaction(id).transferId?.let { transferId -> slice.copyStore.transfers.single { it.id == transferId } }

    private fun legsOf(transfer: StoredTransfer?): Set<Long> = slice.importStatusStore.bankTransactions.filter { it.transferId == transfer?.id }.map { it.id }.toSet()

    private suspend fun compare(): Int = ((slice.comparison.readAgain() as ComparisonStart.Ran).report as ComparisonReport.Compared).statusesChanged

    private suspend fun counts(bankAccountId: Long) = slice.importStatusStore.transactionCounts().single { it.bankAccountId == bankAccountId }

    private suspend fun shown(bankAccountId: Long) = slice.connect.addedBankAccounts().single { it.bankAccountId == bankAccountId }

    private fun entries(since: Int, kind: JournalEventKind): List<JournalEntry> = slice.journalRecorder.entries.drop(since).filter { it.eventKind == kind }

    private fun readsFromOutside(): List<Int> = listOf(slice.budgetApp.reader.budgetReads, slice.budgetApp.reader.changeReads.size, slice.feeds.fetches)

    private val storedNow = MoveOutcome.Stored(statusesGiven = true)

    private fun one(sent: Int = 0, rejected: Int = 0) = SentThrough(sent, rejected)

    /** What a refused or unconfirmed change must leave as it was: the Bank Accounts, the statuses, state_version, the Decision Journal and the marker. */
    private inner class Unchanged {
        private val bankAccounts = slice.connectionStore.bankAccountList.toList()
        private val transactions = slice.importStatusStore.bankTransactions.toList()
        private val version = slice.stateVersion.value
        private val journaled = slice.journalRecorder.entries.size
        private val pending = slice.copyStore.pending

        fun check() {
            assertEquals(bankAccounts, slice.connectionStore.bankAccountList.toList())
            assertEquals(transactions, slice.importStatusStore.bankTransactions.toList())
            assertEquals(version, slice.stateVersion.value)
            assertEquals(journaled, slice.journalRecorder.entries.size)
            assertEquals(pending, slice.copyStore.pending)
        }
    }

    /** Kestrel Checking with one Bank Transaction compared as New in Checking. Returns (Bank Account, Bank Transaction). */
    private suspend fun checkingWithOne(): Pair<Long, Long> {
        ready()
        val checking = checking()
        val bought = stored(checking, "TX-1", oct12, amount(-18))
        compare()
        assertEquals(TransactionStatus.New, status(bought))
        return checking to bought
    }

    /** Kestrel Checking paid 250.00 to Kestrel Rewards, whose Funded By is Checking: a Transfer Slussen proposes, stored by a Comparison. */
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

    private suspend fun decide(leg: Long, choice: TransferChoice, decision: TransferDecision) {
        val outcome = slice.transferDecisions.decide(leg, choice)
        assertEquals(decision, (outcome as TransferOutcome.Stored).decision, "$outcome")
    }

    /** The Offered Bank Accounts Kestrel Feeds last delivered: a new one proposed as a match of [proposedFor], another one, and Kids Saver, which is left out. */
    private suspend fun offered(proposedFor: Long?, vararg more: Pair<String, String>): Map<String, Long> {
        val kestrel = kestrel()
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(
                ExampleData.offered("acct-new-4410", "Kestrel Everyday", "checking", USD, "4410", amount(100), kestrel, now, proposedBankAccountId = proposedFor),
                ExampleData.offered("acct-other-7781", "Kestrel Household", "checking", USD, "7781", null, kestrel, now),
                ExampleData.offered("acct-kids-2290", "Kids Saver", "savings", USD, "2290", null, kestrel, now),
            ) + more.map { (reference, name) -> ExampleData.offered(reference, name, "checking", USD, reference.takeLast(4), null, kestrel, now) },
        )
        slice.connectionStore.leaveOut(listOf(NewLeftOutBankAccount(slice.feedsId, "acct-kids-2290", "Kids Saver", now)))
        return slice.offeredStore.offered().associate { it.providerReference to it.id }
    }

    // ---- Steps 2-7, A1-A3, A10: confirming a proposed match ---------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("Steps 1-3, BR-004, BR-009: a Bank Account with a proposed match waits for confirmation, and Slussen shows, from the stored Offered Bank Accounts and without contacting the Source, the proposed one with its Bank, name, kind, currency and last four digits and the other Offered Bank Accounts of the Source, never a Left-Out Bank Account")
    fun stepsOneToThreeShowTheProposedMatchFromStoredData() = runTest {
        ready()
        val checking = checking()
        val offered = offered(proposedFor = checking)
        val reads = readsFromOutside()

        assertTrue(slice.mappings.list().byBudget.single { it.name == "Household" }.bankAccounts.single().waitsForConfirmation)
        val sheet = slice.mappings.identification(checking)!!

        assertEquals(OfferedToIdentify(offered.getValue("acct-new-4410"), "Kestrel Bank", "Kestrel Everyday", "checking", USD, "4410", current = false), sheet.proposed)
        assertEquals(listOf(offered.getValue("acct-other-7781")), sheet.others.map { it.offeredBankAccountId })
        assertEquals("Kestrel Feeds", sheet.sourceName)
        assertEquals(SentThrough.NONE, sheet.sentThrough)
        assertFalse(sheet.noneStored)
        assertEquals(reads, readsFromOutside())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario")
    @DisplayName("Steps 4-7: confirming the proposed match records how the Source identifies the Bank Account, with its fingerprint, the Bank its Data Provider names and the time of the confirmation, keeps its identifier and Account Mapping, records the confirmation in the Decision Journal without the Data Provider's reference, and gives no statuses again")
    fun confirmingTheProposedMatchRecordsTheIdentification() = runTest {
        ready()
        val checking = checking()
        val offered = offered(proposedFor = checking)
        val before = bankAccountOf(checking)
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-new-4410")))

        val after = bankAccountOf(checking)
        assertEquals(
            before.copy(providerReference = "acct-new-4410", bankAccountFingerprint = "fp-acct-new-4410", providerBankId = kestrel(), identifiedAt = slice.clock.instant()),
            after,
        )
        assertTrue(slice.stateVersion.value > version)
        assertFalse(slice.copyStore.pending)
        val recorded = entries(journaled, JournalEventKind.AccountMappingChange).single()
        assertTrue(recorded.details.contains("Kestrel Checking") && recorded.details.contains("Kestrel Everyday"), recorded.details)
        assertFalse(recorded.details.contains("acct-new-4410"), recorded.details)
        assertFalse(slice.mappings.list().byBudget.single().bankAccounts.single().waitsForConfirmation)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A1: Operator Chooses Another Offered Bank Account")
    @DisplayName("A1: the Operator chooses another Offered Bank Account than the proposed one, and Slussen continues with it at step 5: that one is recorded")
    fun anotherOfferedBankAccountCanBeChosen() = runTest {
        ready()
        val checking = checking()
        val offered = offered(proposedFor = checking)

        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-other-7781")))

        assertEquals("acct-other-7781", bankAccountOf(checking).providerReference)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A2: Bank Account Already Used", businessRules = ["BR-004"])
    @DisplayName("A2, BR-004: an Offered Bank Account that another Account Mapping already uses is refused with that Bank Account's name, by its reference or by its fingerprint; nothing is stored or journaled, and the Offered Bank Accounts can be shown again (step 3)")
    fun anOfferedBankAccountAnotherAccountMappingUsesIsRefused() = runTest {
        ready()
        val checking = checking()
        val joint = joint()
        val offered = offered(proposedFor = checking, "acct-fp-5512" to "Kestrel Spare")
        slice.connectionStore.bankAccountList.replaceAll {
            if (it.id == joint) it.copy(providerReference = "acct-other-7781") else it
        }
        val unchanged = Unchanged()

        assertEquals(IdentifyOutcome.AlreadyUsed("Kestrel Joint"), slice.mappings.identify(checking, offered.getValue("acct-other-7781")))

        unchanged.check()
        // The fingerprint identifies a Bank Account too: Kestrel Joint has the one of Kestrel Spare.
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == joint) it.copy(bankAccountFingerprint = "fp-acct-fp-5512") else it }
        val again = Unchanged()
        assertEquals(IdentifyOutcome.AlreadyUsed("Kestrel Joint"), slice.mappings.identify(checking, offered.getValue("acct-fp-5512")))
        again.check()
        assertTrue(slice.mappings.identification(checking)!!.proposed != null)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004: a Left-Out Bank Account is never offered to confirm or identify a Bank Account and is never identified as one, and an Offered Bank Account no longer stored or of another Source is refused; nothing is stored")
    fun aLeftOutBankAccountIsNeverIdentified() = runTest {
        ready()
        val checking = checking()
        val offered = offered(proposedFor = checking)
        val unchanged = Unchanged()

        assertEquals(IdentifyOutcome.NotOffered, slice.mappings.identify(checking, offered.getValue("acct-kids-2290")))
        assertEquals(IdentifyOutcome.NotOffered, slice.mappings.identify(checking, -1))
        slice.offeredStore.replaceOffered(
            slice.openBankingId, kestrel(), listOf(ExampleData.offered("acct-aurora-3301", "Aurora Current", "checking", USD, "3301", null, kestrel(), now)),
        )
        val ofAnotherSource = slice.offeredStore.offered().single { it.providerReference == "acct-aurora-3301" }.id
        assertEquals(IdentifyOutcome.NotOffered, slice.mappings.identify(checking, ofAnotherSource))

        unchanged.check()
        assertTrue(slice.mappings.identification(checking)!!.others.none { it.name == "Kids Saver" || it.name == "Aurora Current" })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A3: Operator Rejects the Proposal")
    @DisplayName("A3: rejecting the proposed Bank Account records no identification, so the Bank Account's transactions stay out of use, and records the rejection in the Decision Journal; an Offered Bank Account that is not the proposed match cannot be rejected")
    fun rejectingTheProposalRecordsNoIdentification() = runTest {
        ready()
        val checking = checking()
        val offered = offered(proposedFor = checking)
        val before = bankAccountOf(checking)
        val version = slice.stateVersion.value
        val journaled = slice.journalRecorder.entries.size

        assertEquals(IdentifyOutcome.Rejected, slice.mappings.reject(checking, offered.getValue("acct-new-4410")))

        assertEquals(before, bankAccountOf(checking))
        assertEquals(version, slice.stateVersion.value)
        assertTrue(entries(journaled, JournalEventKind.AccountMappingChange).single().details.contains("rejected"))
        val unchanged = Unchanged()
        assertEquals(IdentifyOutcome.NotOffered, slice.mappings.reject(checking, offered.getValue("acct-other-7781")))
        unchanged.check()
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A10: No Offered Bank Accounts Stored", businessRules = ["BR-009"])
    @DisplayName("A10, BR-009: with no Offered Bank Account of the Source stored, as after a reinstall, Slussen shows that the Bank Account can be identified after the next Fetch or Bank Consent and keeps its current identification in use; nothing can be confirmed and nothing is fetched")
    fun withNoOfferedBankAccountStoredTheIdentificationStays() = runTest {
        ready()
        val checking = checking()
        val reads = readsFromOutside()
        val unchanged = Unchanged()

        val sheet = slice.mappings.identification(checking)!!

        assertTrue(sheet.noneStored)
        assertEquals(IdentifyOutcome.NotOffered, slice.mappings.identify(checking, 1))
        unchanged.check()
        assertEquals(reads, readsFromOutside())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A6: Identify a Bank Account Again")
    @DisplayName("A6: a Bank Account of a File Format is not identified by a Data Provider: no Offered Bank Account is shown for it and none is recorded for it")
    fun aBankAccountOfAFileFormatIsNotIdentified() = runTest {
        ready()
        val offered = offered(proposedFor = null)
        val file = bankAccount("Harbor Health", "Joint Checking")
        slice.connectionStore.bankAccountList.replaceAll {
            if (it.id == file) it.copy(dataProviderId = null, fileFormatId = slice.plugins.fileFormats.single().id, providerReference = null) else it
        }
        val unchanged = Unchanged()

        assertNull(slice.mappings.identification(file))
        assertEquals(IdentifyOutcome.NotIdentifiable, slice.mappings.identify(file, offered.getValue("acct-other-7781")))
        unchanged.check()
    }

    // ---- A6, A7, BR-007: identifying again -------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A6: Identify a Bank Account Again", businessRules = ["BR-007", "BR-009"])
    @DisplayName("A6, BR-007: a Bank Account through whose Account Mapping nothing was sent is identified again without a question: the current identification is shown as such and stays until the Operator confirms another, which is then recorded")
    fun identifyingAgainWithoutASendAsksNothing() = runTest {
        ready()
        val checking = checking()
        val offered = offered(proposedFor = null, "acct-${checking}" to "Kestrel Checking as delivered")
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(bankAccountFingerprint = "fp-acct-$checking") else it }

        val sheet = slice.mappings.identification(checking)!!

        assertNull(sheet.proposed)
        assertEquals(listOf(offered.getValue("acct-$checking")), sheet.others.filter { it.current }.map { it.offeredBankAccountId })
        assertEquals("acct-$checking", bankAccountOf(checking).providerReference)
        assertEquals(IdentifyOutcome.Unchanged, slice.mappings.identify(checking, offered.getValue("acct-$checking")))
        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-new-4410")))
        assertEquals("acct-new-4410", bankAccountOf(checking).providerReference)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A6: Identify a Bank Account Again", businessRules = ["BR-007"])
    @DisplayName("A6, BR-007: while the Budget App rejected every transaction sent through the Account Mapping, nothing was written and the Bank Account is identified again without a question; the rejected ones are counted apart and named in the Decision Journal as sent again at their next Upload")
    fun identifyingAgainAfterOnlyRejectedSendsAsksNothing() = runTest {
        val (checking, bought) = checkingWithOne()
        val offered = offered(proposedFor = checking)
        sent(bought, budgetAccount("Checking"), SentRecordOutcome.Rejected)
        val journaled = slice.journalRecorder.entries.size

        assertEquals(one(rejected = 1), slice.mappings.identification(checking)!!.sentThrough)
        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-new-4410")))

        assertEquals("acct-new-4410", bankAccountOf(checking).providerReference)
        assertTrue(entries(journaled, JournalEventKind.AccountMappingChange).single().details.contains("1 the Budget App rejected"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A7: Identify Again After Transactions Were Sent", businessRules = ["BR-007"])
    @DisplayName("A7, BR-007: after a transaction sent through the Account Mapping was written, identifying again first asks the Operator to confirm with their number: unconfirmed, nothing is stored or journaled and the current identification stays in use; confirmed with that number, the new one is recorded and the number journaled")
    fun identifyingAgainAfterAWrittenSendAsksFirst() = runTest {
        val (checking, bought) = checkingWithOne()
        val offered = offered(proposedFor = checking)
        sent(bought, budgetAccount("Checking"))
        val unchanged = Unchanged()

        assertEquals(one(sent = 1), slice.mappings.identification(checking)!!.sentThrough)
        assertEquals(IdentifyOutcome.ToConfirm(one(sent = 1)), slice.mappings.identify(checking, offered.getValue("acct-new-4410")))

        unchanged.check()
        val journaled = slice.journalRecorder.entries.size
        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-new-4410"), confirmed = one(sent = 1)))
        assertEquals("acct-new-4410", bankAccountOf(checking).providerReference)
        assertTrue(entries(journaled, JournalEventKind.AccountMappingChange).single().details.contains("1 transaction sent through its Account Mapping"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A7: Identify Again After Transactions Were Sent", businessRules = ["BR-007"])
    @DisplayName("BR-007, entity model ACCOUNT_MAPPING: each outcome other than Rejected locks the identification (Written, Already Present, Not Yet Known, Unknown), and a confirmation of numbers that are no longer those stored is refused with the numbers as they are now")
    fun everyOutcomeButRejectedLocksTheIdentification() = runTest {
        val (checking, bought) = checkingWithOne()
        val offered = offered(proposedFor = checking)
        val proposed = offered.getValue("acct-new-4410")
        val checkingAccount = budgetAccount("Checking")
        listOf(SentRecordOutcome.Written, SentRecordOutcome.AlreadyPresent, SentRecordOutcome.NotYetKnown, SentRecordOutcome.Unknown).forEach { outcome ->
            slice.copyStore.sentRecords.clear()
            sent(bought, checkingAccount, outcome)
            val unchanged = Unchanged()

            assertEquals(IdentifyOutcome.ToConfirm(one(sent = 1)), slice.mappings.identify(checking, proposed), outcome.text)

            unchanged.check()
        }
        val other = stored(checking, "TX-2", oct13, amount(-40))
        sent(other, checkingAccount, SentRecordOutcome.Rejected)
        val unchanged = Unchanged()

        assertEquals(IdentifyOutcome.ToConfirm(one(sent = 1, rejected = 1)), slice.mappings.identify(checking, proposed, confirmed = one(sent = 1)))

        unchanged.check()
    }

    // ---- A8: moving or mapping again, S-B-10, S-B-11 ---------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-001"])
    @DisplayName("A8 step 1 (S-B-10), BR-001: Slussen offers the Budget Accounts of the served Budgets that receive through no Account Mapping, are no Manually Kept Account and are in the Bank Account's currency; any other is refused with nothing stored, and the Budget Account it already has changes nothing")
    fun aMoveOffersOnlyFreeBudgetAccountsOfTheCurrency() = runTest {
        ready()
        val checking = checking()
        rewardsCard()
        bankAccount("Aurora Current", "Current", currency = EUR)
        slice.connect.setReceivesFromSlussen(budgetAccount("Old Savings"), receives = false)

        val sheet = slice.mappings.moveSheet(checking)!!

        assertEquals(setOf("Travel Card", "Joint Checking", "Reserve Savings", "Health Savings"), sheet.choices.map { it.budgetAccountName }.toSet())
        assertEquals("Checking", sheet.currentBudgetAccountName)
        assertEquals(SentThrough.NONE, sheet.sentThrough)
        val unchanged = Unchanged()
        listOf("Old Savings", "Rewards Card", "Savings", "Current").forEach { name ->
            assertEquals(MoveOutcome.NotOffered, slice.mappings.move(checking, budgetAccount(name)), name)
        }
        assertEquals(MoveOutcome.NotOffered, slice.mappings.move(checking, -1))
        assertEquals(MoveOutcome.Unchanged, slice.mappings.move(checking, budgetAccount("Checking")))
        assertEquals(MoveOutcome.NotFound, slice.mappings.move(-1, budgetAccount("Travel Card")))
        unchanged.check()
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003", "BR-015"])
    @DisplayName("A8, BR-003, BR-015: with nothing sent through the Account Mapping the move asks nothing, also while the time of a first send is recorded; it is stored with that time emptied, journaled with the number, and the statuses and Transfers are given again from stored data without a read of the Budget App")
    fun aMoveWithoutASendAsksNothingAndGivesTheStatusesAgain() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(mapping = it.mapping!!.copy(firstSentAt = now.minus(Duration.ofDays(2)))) else it }
        val reads = readsFromOutside()
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Joint Checking")))

        assertEquals(StoredAccountMapping(bankAccountOf(checking).mapping!!.id, checking, budgetAccount("Joint Checking"), firstSentAt = null), bankAccountOf(checking).mapping)
        val recorded = entries(journaled, JournalEventKind.AccountMappingChange).single().details
        assertTrue(recorded.contains("from Budget Account Checking to Budget Account Joint Checking") && recorded.contains("no transaction written"), recorded)
        // The card's Funded By, Checking, is fetched for no Bank Account now: the Card Payment is a Guessed Transfer (UC-005 BR-014).
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        assertTrue(entries(journaled, JournalEventKind.StatusChanged).isNotEmpty())
        assertFalse(slice.copyStore.pending)
        assertEquals(reads, readsFromOutside())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-008"])
    @DisplayName("A8 (S-B-11), UC-005 BR-014: when a Bank Account's own Account Mapping is moved, its Funded By is kept; it does not apply while it is the Bank Account's own Budget Account or no Bank Account that Slussen fetches is mapped to it, and applies again without any change when it is a Budget Account Slussen fetches in its Budget")
    fun aMoveKeepsTheFundedByAndItAppliesAgainWithoutAChange() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val checkingAccount = budgetAccount("Checking")
        slice.connectionStore.link(card, fundedBy = checkingAccount)

        assertEquals(storedNow, slice.mappings.remove(checking))
        assertEquals(storedNow, slice.mappings.move(card, checkingAccount))

        assertEquals(OtherSideShown(OtherSideKind.FundedBy, checkingAccount, "Checking", applies = false), shown(card).otherSide)
        assertEquals(storedNow, slice.mappings.move(card, budgetAccount("Travel Card")))
        assertEquals(OtherSideShown(OtherSideKind.FundedBy, checkingAccount, "Checking", applies = false), shown(card).otherSide)

        assertEquals(storedNow, slice.mappings.move(checking, checkingAccount))

        assertEquals(OtherSideShown(OtherSideKind.FundedBy, checkingAccount, "Checking", applies = true), shown(card).otherSide)
        assertEquals(checkingAccount, bankAccountOf(card).fundedByBudgetAccountId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("A8, BR-015, UC-003 A1: a Bank Account moved into a Budget Slussen has not read yet is not compared yet: none of its Bank Transactions keeps a status the old Budget gave it or is eligible, nothing is read, and the Budget's time of the read stays empty")
    fun aMoveIntoABudgetNotReadYetLeavesTheBankTransactionsNotComparedYet() = runTest {
        val (checking, bought) = checkingWithOne()
        assertEquals(1, counts(checking).eligible)
        val reads = readsFromOutside()

        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Health Savings")))

        assertNull(status(bought))
        assertEquals(0, counts(checking).eligible)
        assertEquals(1, counts(checking).notComparedYet)
        assertNull(slice.connectionStore.budgets().single { it.name == "Care Fund" }.lastReadAt)
        assertEquals(reads, readsFromOutside())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-003", "BR-015"])
    @DisplayName("A11, A8: with nothing sent through it the Account Mapping is removed without a question; the Bank Account keeps its identification and history, is listed as not mapped, is neither compared nor offered for Upload, and is mapped again without a question, with no time of a first send")
    fun removingAndMappingAgainWithoutASendAskNothing() = runTest {
        val (checking, bought) = checkingWithOne()
        val before = bankAccountOf(checking)
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.remove(checking))

        assertEquals(before.copy(mapping = null), bankAccountOf(checking))
        assertEquals(listOf(checking), slice.mappings.list().notMapped.map { it.bankAccountId })
        assertEquals(TransactionStatus.New, status(bought))
        assertEquals(0, counts(checking).eligible)
        assertTrue(entries(journaled, JournalEventKind.AccountMappingChange).single().details.contains("removed"))
        assertEquals(MoveOutcome.NotMapped, slice.mappings.remove(checking))

        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Joint Checking")))

        assertEquals(budgetAccount("Joint Checking"), bankAccountOf(checking).mapping!!.budgetAccountId)
        assertNull(bankAccountOf(checking).mapping!!.firstSentAt)
        assertEquals(TransactionStatus.New, status(bought))
        assertEquals(1, counts(checking).eligible)
    }

    // ---- BR-003: the lock of a move and a removal ------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("A8 steps 3-5, BR-003: after a transaction sent through the Account Mapping was written, the move first asks the Operator to confirm with their number: unconfirmed, nothing is stored, journaled or given again; confirmed with that number, it is moved, the Sent Record keeps its Budget Account and the number is journaled with where the transactions stay")
    fun aMoveAfterAWrittenSendAsksFirst() = runTest {
        val (checking, bought) = checkingWithOne()
        val checkingAccount = budgetAccount("Checking")
        sent(bought, checkingAccount)
        val unchanged = Unchanged()

        assertEquals(one(sent = 1), slice.mappings.moveSheet(checking)!!.sentThrough)
        assertEquals(MoveOutcome.ToConfirm(one(sent = 1)), slice.mappings.move(checking, budgetAccount("Joint Checking")))

        unchanged.check()
        val journaled = slice.journalRecorder.entries.size
        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Joint Checking"), confirmed = one(sent = 1)))
        assertEquals(budgetAccount("Joint Checking"), bankAccountOf(checking).mapping!!.budgetAccountId)
        assertEquals(listOf(checkingAccount), slice.copyStore.sentRecords.map { it.budgetAccountId })
        val recorded = entries(journaled, JournalEventKind.AccountMappingChange).single().details
        assertTrue(recorded.contains("1 transaction written through it stays in Budget Account Checking"), recorded)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("BR-003, entity model ACCOUNT_MAPPING: each outcome other than Rejected locks a move (Written, Already Present, Not Yet Known, Unknown)")
    fun everyOutcomeButRejectedLocksAMove() = runTest {
        val (checking, bought) = checkingWithOne()
        listOf(SentRecordOutcome.Written, SentRecordOutcome.AlreadyPresent, SentRecordOutcome.NotYetKnown, SentRecordOutcome.Unknown).forEach { outcome ->
            slice.copyStore.sentRecords.clear()
            sent(bought, budgetAccount("Checking"), outcome)
            val unchanged = Unchanged()

            assertEquals(MoveOutcome.ToConfirm(one(sent = 1)), slice.mappings.move(checking, budgetAccount("Joint Checking")), outcome.text)

            unchanged.check()
        }
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("A8 step 3, BR-003: while the Budget App rejected every transaction sent through the Account Mapping, the move asks nothing; it is journaled with the number of the rejected ones and that they go to the new Budget Account at their next Upload")
    fun aMoveAfterOnlyRejectedSendsAsksNothing() = runTest {
        val (checking, bought) = checkingWithOne()
        sent(bought, budgetAccount("Checking"), SentRecordOutcome.Rejected)
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Joint Checking")))

        val recorded = entries(journaled, JournalEventKind.AccountMappingChange).single().details
        assertTrue(recorded.contains("1 the Budget App rejected go to Budget Account Joint Checking at their next Upload"), recorded)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("BR-003: a send through an earlier Account Mapping of the Bank Account to the same Budget Account counts: removed after a written send and mapped again to Checking, the Bank Account is shown as sent through and its next move asks first, although the time of the first send is empty")
    fun aSendThroughAnEarlierAccountMappingToTheSameBudgetAccountCounts() = runTest {
        val (checking, bought) = checkingWithOne()
        val checkingAccount = budgetAccount("Checking")
        sent(bought, checkingAccount)

        assertEquals(MoveOutcome.ToConfirm(one(sent = 1)), slice.mappings.remove(checking))
        assertEquals(storedNow, slice.mappings.remove(checking, confirmed = one(sent = 1)))
        assertEquals(storedNow, slice.mappings.move(checking, checkingAccount))

        assertNull(bankAccountOf(checking).mapping!!.firstSentAt)
        assertTrue(shown(checking).sentThrough)
        assertEquals(MoveOutcome.ToConfirm(one(sent = 1)), slice.mappings.move(checking, budgetAccount("Joint Checking")))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("BR-003: a transaction sent to another Budget Account, through an earlier Account Mapping elsewhere, and a transaction of another Bank Account sent to the same Budget Account do not count: the move asks nothing")
    fun aSendToAnotherBudgetAccountOrOfAnotherBankAccountDoesNotCount() = runTest {
        val (checking, bought) = checkingWithOne()
        val joint = bankAccount("Kestrel Joint", null)
        val ofJoint = stored(joint, "TX-9", oct12, amount(-60))
        sent(bought, budgetAccount("Joint Checking"))
        sent(ofJoint, budgetAccount("Checking"))

        assertEquals(SentThrough.NONE, slice.mappings.moveSheet(checking)!!.sentThrough)
        assertFalse(shown(checking).sentThrough)
        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Travel Card")))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-003"])
    @DisplayName("BR-003: a confirmation of numbers that are no longer those stored is refused with the numbers as they are now, a written send and a rejected one each counted, and nothing is stored")
    fun aConfirmationOfOtherNumbersIsRefused() = runTest {
        val (checking, bought) = checkingWithOne()
        sent(bought, budgetAccount("Checking"))
        val later = stored(checking, "TX-2", oct13, amount(-40))
        sent(later, budgetAccount("Checking"))
        val unchanged = Unchanged()

        assertEquals(MoveOutcome.ToConfirm(one(sent = 2)), slice.mappings.move(checking, budgetAccount("Joint Checking"), confirmed = one(sent = 1)))
        slice.copyStore.sentRecords.removeAll { it.storedBankTransaction == later }
        sent(later, budgetAccount("Checking"), SentRecordOutcome.Rejected)
        assertEquals(MoveOutcome.ToConfirm(one(sent = 1, rejected = 1)), slice.mappings.remove(checking, confirmed = one(sent = 1)))

        unchanged.check()
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-003"])
    @DisplayName("A11, BR-003: after a written send the removal first asks the Operator to confirm with their number; confirmed, the Account Mapping is removed, the Bank Account keeps its identification, the Sent Record keeps its Budget Account and the number is journaled")
    fun aRemovalAfterAWrittenSendAsksFirst() = runTest {
        val (checking, bought) = checkingWithOne()
        sent(bought, budgetAccount("Checking"))
        val unchanged = Unchanged()

        assertEquals(MoveOutcome.ToConfirm(one(sent = 1)), slice.mappings.remove(checking))

        unchanged.check()
        val journaled = slice.journalRecorder.entries.size
        assertEquals(storedNow, slice.mappings.remove(checking, confirmed = one(sent = 1)))
        assertNull(bankAccountOf(checking).mapping)
        assertEquals("acct-$checking", bankAccountOf(checking).providerReference)
        assertEquals(listOf(budgetAccount("Checking")), slice.copyStore.sentRecords.map { it.budgetAccountId })
        assertTrue(entries(journaled, JournalEventKind.AccountMappingChange).single().details.contains("1 transaction written through it stays in Budget Account Checking"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-003"])
    @DisplayName("A11 step 1, BR-003: while the Budget App rejected every transaction sent through it, the removal asks nothing and journals that the rejected ones wait until the Bank Account is mapped again")
    fun aRemovalAfterOnlyRejectedSendsAsksNothing() = runTest {
        val (checking, bought) = checkingWithOne()
        sent(bought, budgetAccount("Checking"), SentRecordOutcome.Rejected)
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.remove(checking))

        assertTrue(entries(journaled, JournalEventKind.AccountMappingChange).single().details.contains("1 the Budget App rejected wait until the Bank Account is mapped again"))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Step 1, BR-003: whether transactions were sent through the Account Mapping is read from the Sent Records of the Bank Account's own Bank Transactions to its current Budget Account, a rejected one included, also while the time of the first send is empty; a Bank Account whose only Sent Record went to another Budget Account is not shown as sent through, and one with the time of a first send recorded is (that time is set at the first send, whatever its outcome)")
    fun stepOneShowsSentThroughFromTheSentRecords() = runTest {
        val (checking, bought) = checkingWithOne()
        val card = rewardsCard()
        val joint = joint()
        val reserve = bankAccount("Reserve Saver", "Reserve Savings", BankAccountType.Savings)
        val ofCard = stored(card, "TX-3", oct13, amount(40))
        val ofJoint = stored(joint, "TX-4", oct13, amount(-12))
        sent(bought, budgetAccount("Checking"))
        sent(ofCard, budgetAccount("Rewards Card"), SentRecordOutcome.Rejected)
        sent(ofJoint, budgetAccount("Travel Card"))
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == reserve) it.copy(mapping = it.mapping!!.copy(firstSentAt = now.minus(Duration.ofDays(1)))) else it }

        assertTrue(listOf(checking, card, joint).all { bankAccountOf(it).mapping!!.firstSentAt == null })
        assertEquals(listOf(true, true, false, true), listOf(checking, card, joint, reserve).map { shown(it).sentThrough })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A7: Identify Again After Transactions Were Sent", businessRules = ["BR-007"])
    @DisplayName("A7, BR-007 (the conservative reading): an identification counts every written send of the Bank Account's own Bank Transactions, to any Budget Account: after a confirmed move from Checking to Travel Card, identifying again still asks first with the send that stays in Checking")
    fun anIdentificationOfAMappedBankAccountCountsWrittenSendsToAnyBudgetAccount() = runTest {
        val (checking, bought) = checkingWithOne()
        val offered = offered(proposedFor = checking)
        sent(bought, budgetAccount("Checking"))
        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Travel Card"), confirmed = one(sent = 1)))
        val unchanged = Unchanged()

        assertEquals(SentThrough.NONE, slice.mappings.moveSheet(checking)!!.sentThrough)
        assertEquals(one(sent = 1), slice.mappings.identification(checking)!!.sentThrough)
        assertEquals(IdentifyOutcome.ToConfirm(one(sent = 1)), slice.mappings.identify(checking, offered.getValue("acct-new-4410")))

        unchanged.check()
        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-new-4410"), confirmed = one(sent = 1)))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A7: Identify Again After Transactions Were Sent", businessRules = ["BR-007"])
    @DisplayName("A7, BR-007 (the conservative reading): a Bank Account without an Account Mapping counts its written sends to any Budget Account before a new identification: removed after a written send to Checking, identifying it again asks first")
    fun anIdentificationOfABankAccountWithoutAnAccountMappingCountsItsWrittenSends() = runTest {
        val (checking, bought) = checkingWithOne()
        val offered = offered(proposedFor = checking)
        sent(bought, budgetAccount("Checking"))
        assertEquals(storedNow, slice.mappings.remove(checking, confirmed = one(sent = 1)))
        val unchanged = Unchanged()

        assertEquals(one(sent = 1), slice.connectionStore.sentThrough(checking))
        assertEquals(IdentifyOutcome.ToConfirm(one(sent = 1)), slice.mappings.identify(checking, offered.getValue("acct-new-4410")))

        unchanged.check()
    }

    // ---- UC-013 A6, UC-009 steps 2-4: the proposal a Fetch stores ------------------------------------------------------------

    /** Kestrel Feeds lists [delivered] in a Fetch; none of them is read (UC-001 step 6). */
    private fun delivers(vararg delivered: Triple<String, String, String?>) {
        slice.feeds.fetchAnswer = {
            FetchResponse(
                SourceOutcome.Completed,
                emptyList(),
                delivered.map { (reference, name, number) ->
                    DeliveredBankAccount(reference, "Kestrel Bank", name, "checking", USD, number?.let(::BankAccountNumber), BankAccountResult.NotRead(NotReadCause.NotSelected, null))
                },
            )
        }
    }

    private fun fingerprintOf(number: String) = BankAccountFingerprint.of("example-feeds", "Kestrel Bank", BankAccountNumber(number))

    private suspend fun proposals(): Map<String, Long?> = slice.offeredStore.offered().associate { it.providerReference to it.proposedBankAccountId }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("UC-013 A6, steps 2-7: a Fetch that delivers, under a new reference, the Bank Account Fingerprint of an added Bank Account stores that Offered Bank Account as its proposed match; the Bank Account waits for confirmation, the Operator confirms the proposal shown, and its new identification is recorded")
    fun aFetchStoresTheProposedMatchAndTheOperatorConfirmsIt() = runTest {
        ready()
        val checking = checking()
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(bankAccountFingerprint = fingerprintOf("4410-2290-0001")) else it }
        delivers(Triple("acct-new-4410", "Kestrel Everyday", "4410 2290 0001"), Triple("acct-other-7781", "Kestrel Household", "7781-0000-0002"))

        slice.fetch.fetch(slice.feedsKey)

        assertEquals(mapOf("acct-new-4410" to checking, "acct-other-7781" to null), proposals())
        assertTrue(shown(checking).waitsForConfirmation)
        val proposed = slice.mappings.identification(checking)!!.proposed!!
        assertEquals("Kestrel Everyday", proposed.name)
        assertEquals("0001", proposed.lastFourDigits)

        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, proposed.offeredBankAccountId))

        assertEquals("acct-new-4410", bankAccountOf(checking).providerReference)
        assertFalse(shown(checking).waitsForConfirmation)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004, UC-013 A6: a Fetch proposes no match when two delivered Bank Accounts have the fingerprint, while the Bank Account's own reference is still delivered, for a Left-Out Bank Account, and for a fingerprint no added Bank Account has")
    fun aFetchProposesNoMatchWhereItWouldGuess() = runTest {
        ready()
        val checking = checking()
        val fingerprint = fingerprintOf("4410-2290-0001")
        slice.connectionStore.bankAccountList.replaceAll { if (it.id == checking) it.copy(bankAccountFingerprint = fingerprint) else it }

        delivers(Triple("acct-new-4410", "Kestrel Everyday", "4410 2290 0001"), Triple("acct-twin-4411", "Kestrel Twin", "4410229 00001"))
        slice.fetch.fetch(slice.feedsKey)
        assertEquals(mapOf("acct-new-4410" to null, "acct-twin-4411" to null), proposals())

        delivers(Triple("acct-$checking", "Kestrel Checking", "4410 2290 0001"), Triple("acct-new-4410", "Kestrel Everyday", "4410 2290 0001"))
        slice.fetch.fetch(slice.feedsKey)
        assertTrue(proposals().values.all { it == null })

        slice.connectionStore.leaveOut(listOf(NewLeftOutBankAccount(slice.feedsId, "acct-new-4410", "Kestrel Everyday", now)))
        delivers(Triple("acct-new-4410", "Kestrel Everyday", "4410 2290 0001"), Triple("acct-other-7781", "Kestrel Household", "7781-0000-0002"))
        slice.fetch.fetch(slice.feedsKey)
        assertTrue(proposals().values.all { it == null })
        assertFalse(shown(checking).waitsForConfirmation)
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-003"])
    @DisplayName("C-005, NFR-012: no Decision Journal entry of an identification, a move, a removal or the statuses given again after them names the payee of a Bank Transaction")
    fun noJournalEntryOfTheseChangesNamesAPayee() = runTest {
        val (paid, received) = plainPair()
        val checking = transaction(paid).bankAccountId
        val offered = offered(proposedFor = checking)
        val journaled = slice.journalRecorder.entries.size

        assertEquals(IdentifyOutcome.Identified, slice.mappings.identify(checking, offered.getValue("acct-new-4410")))
        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Joint Checking")))
        assertEquals(storedNow, slice.mappings.remove(transaction(received).bankAccountId))

        val recorded = slice.journalRecorder.entries.drop(journaled)
        assertTrue(recorded.any { it.eventKind == JournalEventKind.StatusChanged })
        assertTrue(recorded.none { it.details.contains("PLANTED-PAYEE") }, recorded.joinToString { it.details })
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-015"])
    @DisplayName("A11, BR-015: a Transfer without a Transfer Decision whose only Transfer Leg lies in the Bank Account whose Account Mapping is removed is released: the Transfer is no longer stored and its Bank Transaction is in no Transfer")
    fun aRemovalReleasesALonelyTransferWithoutADecision() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val received = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        compare()
        val lonely = transferOf(received)!!
        assertEquals(TransferDecision.Proposed, lonely.decision)

        assertEquals(storedNow, slice.mappings.remove(card))

        assertTrue(slice.copyStore.transfers.none { it.id == lonely.id })
        assertNull(transaction(received).transferId)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("A8, BR-015: a Transfer without a Transfer Decision whose only Transfer Leg lies in a Bank Account moved into a Budget not read yet is released, while a Lonely Transfer the Operator confirmed is judged as UC-005 BR-015 says and never released this way")
    fun aMoveIntoABudgetNotReadYetReleasesALonelyTransferWithoutADecision() = runTest {
        ready()
        checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val received = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        compare()
        val lonely = transferOf(received)!!

        assertEquals(storedNow, slice.mappings.move(card, budgetAccount("Health Savings")))

        assertTrue(slice.copyStore.transfers.none { it.id == lonely.id })
        assertNull(transaction(received).transferId)
        assertNull(status(received))
    }

    // ---- UC-005 BR-015, S-B-17: what a move or a removal does to a Transfer the Operator decided on ------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("A8, UC-005 BR-015: moving the Account Mapping of a Transfer Leg's Bank Account to another Budget Account dissolves a confirmed Transfer: the dropped decision is journaled with that reason for each Transfer Leg, and its Bank Transactions are detected again, here as a Transfer Slussen proposes towards the new Budget Account")
    fun aMoveDissolvesAConfirmedTransfer() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Confirm, TransferDecision.Confirmed)
        val confirmed = transferOf(paid)!!
        val card = transaction(received).bankAccountId
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.move(card, budgetAccount("Travel Card")))

        assertTrue(slice.copyStore.transfers.none { it.id == confirmed.id })
        val dropped = entries(journaled, JournalEventKind.OperatorDecision)
        assertEquals(setOf(transaction(paid).importIdentifier, transaction(received).importIdentifier), dropped.map { it.importIdentifier }.toSet())
        dropped.forEach { assertTrue(it.details.contains(TransferDropReason.TransferLegMappedElsewhere.text), it.details) }
        val again = transferOf(paid)!!
        assertEquals(TransferDecision.Proposed, again.decision)
        assertEquals(budgetAccount("Travel Card"), again.inflowBudgetAccountId)
        assertEquals(setOf(paid, received), legsOf(again))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("A8, UC-005 BR-015: moving the Account Mapping of a Transfer Leg's Bank Account dissolves a Transfer whose other side the Operator chose (Budget Account Chosen), journals the dropped decision and detects its Bank Transactions again")
    fun aMoveDissolvesABudgetAccountChosenTransfer() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        val paid = stored(checking, "TX-1", oct12, amount(-250))
        val received = stored(card, "TX-2", oct13, amount(250))
        compare()
        assertEquals(TransactionStatus.GuessedTransfer, status(received))
        decide(received, TransferChoice.ChooseBudgetAccount(budgetAccount("Checking"), keep = false), TransferDecision.BudgetAccountChosen)
        val chosen = transferOf(received)!!
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.move(checking, budgetAccount("Joint Checking")))

        assertTrue(slice.copyStore.transfers.none { it.id == chosen.id })
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).all { it.details.contains(TransferDropReason.TransferLegMappedElsewhere.text) })
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isNotEmpty())
        assertNotEquals(TransferDecision.BudgetAccountChosen, transferOf(paid)?.decision)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("A8, UC-005 BR-007, BR-015 (S-B-17): a Transfer the Operator holds back is never dissolved by a move of a Transfer Leg's Account Mapping: it stays held with both Transfer Legs, nothing is journaled as dropped and nothing of it is eligible")
    fun aMoveNeverDissolvesAHeldTransfer() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Hold, TransferDecision.Held)
        val held = transferOf(paid)!!
        val card = transaction(received).bankAccountId
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.move(card, budgetAccount("Travel Card")))

        assertEquals(listOf(held), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(held))
        assertEquals(TransactionStatus.HeldTransfer, status(paid))
        assertEquals(TransactionStatus.HeldTransfer, status(received))
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isEmpty())
        assertEquals(0, counts(card).eligible)
        assertEquals(0, counts(transaction(paid).bankAccountId).eligible)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("A8, UC-005 BR-015 (S-B-17): a Transfer the Operator split is never dissolved by a move of a Transfer Leg's Account Mapping: it stays split with both Transfer Legs, each an ordinary Bank Transaction, and nothing is journaled as dropped")
    fun aMoveNeverDissolvesASplitTransfer() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Split, TransferDecision.Split)
        val split = transferOf(paid)!!
        val card = transaction(received).bankAccountId
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.move(card, budgetAccount("Travel Card")))

        assertEquals(listOf(split), slice.copyStore.transfers.toList())
        assertEquals(setOf(paid, received), legsOf(split))
        assertEquals(TransactionStatus.New, status(paid))
        assertEquals(TransactionStatus.New, status(received))
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isEmpty())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-015"])
    @DisplayName("A11, UC-005 BR-015: removing the Account Mapping of a Transfer Leg's Bank Account dissolves a confirmed Transfer and journals the dropped decision with that reason")
    fun aRemovalDissolvesAConfirmedTransfer() = runTest {
        val (paid, received) = plainPair()
        decide(paid, TransferChoice.Confirm, TransferDecision.Confirmed)
        val confirmed = transferOf(paid)!!
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.remove(transaction(received).bankAccountId))

        assertTrue(slice.copyStore.transfers.none { it.id == confirmed.id })
        val dropped = entries(journaled, JournalEventKind.OperatorDecision)
        assertTrue(dropped.isNotEmpty())
        dropped.forEach { assertTrue(it.details.contains(TransferDropReason.TransferLegNotMapped.text), it.details) }
        assertEquals(TransactionStatus.New, status(paid))
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-015"])
    @DisplayName("A11, UC-005 BR-007, BR-015 (S-B-17): removing the Account Mapping of a Transfer Leg's Bank Account never dissolves a held Transfer: it keeps its decision and both Transfer Legs, stays held, and nothing is journaled as dropped")
    fun aRemovalNeverDissolvesAHeldTransfer() = runTest {
        removalKeeps(TransferChoice.Hold, TransferDecision.Held)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-015"])
    @DisplayName("A11, UC-005 BR-015 (S-B-17): removing the Account Mapping of a Transfer Leg's Bank Account never dissolves a split Transfer: it keeps its decision and both Transfer Legs, the compared one is an ordinary Bank Transaction, and nothing is journaled as dropped")
    fun aRemovalNeverDissolvesASplitTransfer() = runTest {
        removalKeeps(TransferChoice.Split, TransferDecision.Split)
    }

    private suspend fun removalKeeps(choice: TransferChoice, decision: TransferDecision) {
        val (paid, received) = plainPair()
        decide(paid, choice, decision)
        val kept = transferOf(paid)!!
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.remove(transaction(received).bankAccountId))

        assertEquals(listOf(kept), slice.copyStore.transfers.toList(), decision.text)
        assertEquals(setOf(paid, received), legsOf(kept), decision.text)
        assertTrue(entries(journaled, JournalEventKind.OperatorDecision).isEmpty(), decision.text)
        val expected = if (decision == TransferDecision.Held) TransactionStatus.HeldTransfer else TransactionStatus.New
        assertEquals(expected, status(paid), decision.text)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-015"])
    @DisplayName("A11, UC-005 BR-015: a confirmed Lonely Transfer whose missing side's Budget Account loses its last Account Mapping is dissolved, and the dropped decision is journaled with that reason")
    fun aRemovalDissolvesAConfirmedLonelyTransferWhoseOtherSideIsNoLongerFetched() = runTest {
        ready()
        val checking = checking()
        val card = rewardsCard()
        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val received = stored(card, "TX-1", oct13, amount(120), payee = "AUTOPAY")
        compare()
        assertEquals(TransactionStatus.TransferLeg, status(received))
        val outcome = slice.transferDecisions.decide(received, TransferChoice.Confirm) as TransferOutcome.Stored
        assertEquals(TransferDecision.Confirmed to true, outcome.decision to outcome.lonely)
        val lonely = transferOf(received)!!
        val journaled = slice.journalRecorder.entries.size

        assertEquals(storedNow, slice.mappings.remove(checking))

        assertTrue(slice.copyStore.transfers.none { it.id == lonely.id })
        val dropped = entries(journaled, JournalEventKind.OperatorDecision).single()
        assertTrue(dropped.details.contains(TransferDropReason.OtherSideNotFetched.text), dropped.details)
    }

    // ---- BR-015: a stop between the change and the statuses ---------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009", scenario = "A8: Move an Account Mapping or Map a Bank Account Again", businessRules = ["BR-015"])
    @DisplayName("BR-015: a move is stored together with the Comparison marker: when Slussen stops before the statuses are given again, the move is said to be stored with the statuses still to give, the marker stays set, and the next start gives them from stored data without a read")
    fun aStopAfterAMoveIsConcludedAtTheNextStart() = runTest {
        val (paid, _) = plainPair()
        val checking = transaction(paid).bankAccountId
        val reads = readsFromOutside()
        slice.copyStore.failsToConclude = true

        assertEquals(MoveOutcome.Stored(statusesGiven = false), slice.mappings.move(checking, budgetAccount("Joint Checking")))

        assertEquals(TransactionStatus.TransferLeg, status(paid))
        assertTrue(slice.copyStore.pending)
        slice.copyStore.failsToConclude = false
        assertTrue(slice.comparison.recomputePending())
        assertFalse(slice.copyStore.pending)
        assertEquals(TransactionStatus.GuessedTransfer, status(paid))
        assertEquals(reads, readsFromOutside())
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A11: Remove an Account Mapping", businessRules = ["BR-015"])
    @DisplayName("BR-015: a removal is stored together with the Comparison marker: a stop before the statuses are given again leaves it set until the next start gives them from stored data")
    fun aStopAfterARemovalIsConcludedAtTheNextStart() = runTest {
        val (paid, received) = plainPair()
        slice.copyStore.failsToConclude = true

        assertEquals(MoveOutcome.Stored(statusesGiven = false), slice.mappings.remove(transaction(received).bankAccountId))

        assertTrue(slice.copyStore.pending)
        slice.copyStore.failsToConclude = false
        assertTrue(slice.comparison.recomputePending())
        assertEquals(TransactionStatus.New, status(paid))
    }

    // ---- Admission, storage and failure postconditions -----------------------------------------------------------------

    @Test
    @UseCase(id = "UC-009")
    @DisplayName("Precondition (UC-006 BR-005): while an Upload Plan awaits confirmation or an Upload is written, confirming, rejecting, moving, mapping again and removing are refused with the reason and nothing is stored or journaled")
    fun everyIdentificationAndMoveWaitsForTheUpload() = runTest {
        ready()
        val checking = checking()
        val loose = bankAccount("Kestrel Spare", null)
        val offered = offered(proposedFor = checking)
        val travel = budgetAccount("Travel Card")
        val unchanged = Unchanged()
        val changes: List<suspend () -> Any> = listOf(
            { slice.mappings.identify(checking, offered.getValue("acct-new-4410")) },
            { slice.mappings.reject(checking, offered.getValue("acct-new-4410")) },
            { slice.mappings.move(checking, travel) },
            { slice.mappings.move(loose, travel) },
            { slice.mappings.remove(checking) },
        )

        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        changes.forEach { assertTrue(it().toString().contains(OperationRefusal.UploadPlanAwaitingConfirmation.name), it().toString()) }
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        changes.forEach { assertTrue(it().toString().contains(OperationRefusal.UploadBeingWritten.name), it().toString()) }

        unchanged.check()
    }

    @Test
    @UseCase(id = "UC-009")
    @DisplayName("Failure postconditions (NFR-023): when the stored data cannot be written, confirming, moving, mapping again and removing answer that nothing was stored instead of failing, and nothing is journaled")
    fun aStorageFailureStoresNothing() = runTest {
        val (checking, _) = checkingWithOne()
        val loose = bankAccount("Kestrel Spare", null)
        val offered = offered(proposedFor = checking)
        val unchanged = Unchanged()
        slice.connectionStore.failsToStore = true

        assertEquals(IdentifyOutcome.NotStored, slice.mappings.identify(checking, offered.getValue("acct-new-4410")))
        assertEquals(MoveOutcome.NotStored, slice.mappings.move(checking, budgetAccount("Travel Card")))
        assertEquals(MoveOutcome.NotStored, slice.mappings.move(loose, budgetAccount("Travel Card")))
        assertEquals(MoveOutcome.NotStored, slice.mappings.remove(checking))

        unchanged.check()
    }

    @Test
    @UseCase(id = "UC-009", businessRules = ["BR-003", "BR-007"])
    @DisplayName("BR-003, BR-007 (O-13, the in-memory fake of :testing; the Room store's own refusals are tested on a device in UC009ManageAccountMappingsPersistenceIT): the fake storage refuses, storing nothing, a move, a removal or a new identification while a written send is not confirmed with its numbers, a Budget Account that is not free, an Account Mapping for a Bank Account that has one, a move or removal of none, and a reference another Bank Account or a Left-Out Bank Account has")
    fun theFakeStorageRefusesWhatTheEntityModelForbids() = runTest {
        val (checking, bought) = checkingWithOne()
        val joint = joint()
        val loose = bankAccount("Kestrel Spare", null)
        sent(bought, budgetAccount("Checking"))
        slice.connectionStore.leaveOut(listOf(NewLeftOutBankAccount(slice.feedsId, "acct-kids-2290", "Kids Saver", now)))
        val store = slice.connectionStore
        val identification = NewIdentification("acct-new-4410", "fp-acct-new-4410", null, now)
        val unchanged = Unchanged()

        assertEquals(one(sent = 1), store.sentThrough(checking))
        assertEquals(MappingStored.ToConfirm(one(sent = 1)), store.move(checking, budgetAccount("Travel Card"), confirmed = null))
        assertEquals(MappingStored.ToConfirm(one(sent = 1)), store.remove(checking, confirmed = one(sent = 2)))
        assertEquals(IdentificationStored.ToConfirm(one(sent = 1)), store.identify(checking, identification, confirmed = null))
        assertEquals(MappingStored.NotAllowed, store.move(checking, budgetAccount("Joint Checking"), confirmed = one(sent = 1)))
        assertEquals(MappingStored.NotAllowed, store.mapAgain(checking, budgetAccount("Travel Card")))
        assertEquals(MappingStored.NotAllowed, store.mapAgain(loose, budgetAccount("Current")))
        assertEquals(MappingStored.NotAllowed, store.move(loose, budgetAccount("Travel Card"), confirmed = null))
        assertEquals(MappingStored.NotAllowed, store.remove(loose, confirmed = null))
        assertEquals(IdentificationStored.AlreadyUsed("Kestrel Joint"), store.identify(checking, identification.copy(providerReference = "acct-$joint"), confirmed = one(sent = 1)))
        assertEquals(IdentificationStored.LeftOut, store.identify(loose, identification.copy(providerReference = "acct-kids-2290", bankAccountFingerprint = null), confirmed = null))
        assertEquals(MappingStored.NotFound, store.remove(-1, confirmed = null))
        unchanged.check()

        assertEquals(MappingStored.Stored, store.move(checking, budgetAccount("Travel Card"), confirmed = one(sent = 1)))
        assertTrue(slice.copyStore.pending)
    }
}
