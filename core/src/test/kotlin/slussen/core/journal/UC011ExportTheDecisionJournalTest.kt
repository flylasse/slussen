package slussen.core.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.comparison.ComparisonStart
import slussen.core.credential.CredentialStatus
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.CoordinatorMode
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.NumberSetting
import slussen.core.port.ServiceKey
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankTransaction
import slussen.core.settings.SettingOutcome
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.testing.ExampleData
import slussen.testing.ExampleData.USD
import slussen.testing.ExampleData.amount
import slussen.testing.InMemoryJournalExportDocument
import slussen.testing.JsonText
import slussen.testing.TestSlice
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Export the Decision Journal (UC-011): the entries of the Decision Journal up to the newest one noted at the start,
 * streamed page by page, with the Budget App's present transactions read fresh and not stored, or the stored copy where
 * the Budget App cannot be read (A2), in one JSON file (BR-007) that holds nothing that must not leave the device in
 * clear (BR-003, NFR-012), with codes for payee and memo text when the Operator chose them (BR-005), removed again when
 * it cannot be written completely (A3). Every name, amount, identifier and credential is invented (C-005).
 */
@DisplayName("UC-011: Export the Decision Journal")
class UC011ExportTheDecisionJournalTest {
    private val slice = TestSlice()
    private val journal = slice.journalRecorder
    private val now: Instant = slice.clock.instant()
    private val today: LocalDate = LocalDate.of(2026, 10, 15)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)
    private var nextId = 5000L

    private companion object {
        const val PLANTED_ACCOUNT_NUMBER = "PLANTED-ACCOUNT-NUMBER-440211"
        const val PLANTED_FINGERPRINT = "PLANTED-FINGERPRINT-77c1"
        const val PLANTED_BANK_PAYEE = "PLANTED-BANK-PAYEE-Harbor"
        const val PLANTED_BANK_MEMO = "PLANTED-BANK-MEMO-rent"
        const val PLANTED_PAYEE = "PLANTED-PAYEE-Corner-Grocer"
        const val PLANTED_MEMO = "PLANTED-MEMO-weekly"
        const val OTHER_PATH_IDENTIFIER = "YNAB:-42500:2026-10-14:1"
    }

    // ---- Set-up -----------------------------------------------------------------------------------------------------

    /** The Budget App's credentials accepted; Household (USD), Summer House (EUR) and Care Fund (USD) are served. */
    private suspend fun ready() {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            BudgetToServe(budget.reference, budget.name, budget.currency, budget.budgetAccounts.map { BudgetAccountToStore(it.reference, it.name, it.balance) })
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), now.minus(Duration.ofDays(3)))
    }

    /** [ready], with the entries it recorded cleared, so that a test counts only its own. */
    private suspend fun readyAndEmpty() {
        ready()
        journal.clear(firstEntry = null).await()
    }

    private suspend fun budget(name: String) = slice.connectionStore.budgets().single { it.name == name }

    private suspend fun budgetAccount(name: String) = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }

    /** Kestrel Checking, mapped to Checking, with a planted Bank Account number and fingerprint. */
    private suspend fun bankAccount(): Long {
        val id = nextId++
        slice.connectionStore.bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-%012d".format(id),
            bankId = slice.connectionStore.bankNamed("Kestrel Bank").id,
            providerBankId = null,
            dataProviderId = slice.feedsId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = "Kestrel Checking",
            currency = USD,
            type = BankAccountType.Checking,
            startDate = null,
            providerReference = PLANTED_ACCOUNT_NUMBER,
            bankAccountFingerprint = PLANTED_FINGERPRINT,
            balanceProviderReference = null,
            identifiedAt = now.minus(Duration.ofDays(30)),
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, budgetAccount("Checking").id, null),
        )
        return id
    }

    private fun identifier(reference: String) =
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-feeds", reference))

    /** A Bank Transaction a Fetch stored, with a planted payee and memo. */
    private fun bankTransaction(bankAccountId: Long, reference: String, amount: Amount): String {
        val identifier = identifier(reference)
        slice.importStatusStore.bankTransactions += StoredBankTransaction(
            id = nextId++,
            bankAccountId = bankAccountId,
            stableReference = reference,
            referenceKind = StableReferenceKind.Supplied,
            occurrenceNumber = 1,
            importIdentifier = identifier,
            bookedDate = oct14,
            amount = amount,
            currency = USD,
            payee = PLANTED_BANK_PAYEE,
            memo = PLANTED_BANK_MEMO,
            firstSeenAt = now.minus(Duration.ofHours(1)),
            status = null,
            transferId = null,
        )
        return identifier
    }

    private fun read(
        reference: String,
        date: LocalDate,
        amount: Amount,
        account: String = "ba-checking",
        importIdentifier: String? = null,
        entry: EntryKind = if (importIdentifier == null) EntryKind.Typed else EntryKind.Imported,
        state: ReadState = ReadState.Present,
        payee: String? = PLANTED_PAYEE,
        memo: String? = PLANTED_MEMO,
    ) = BudgetAppTransactionRead(reference, account, date, amount, payee, memo, importIdentifier, entry, null, state, true, true)

    /** Every read of changes of Household answers [transactions]; the other Budgets answer none. */
    private fun answerHousehold(vararg transactions: BudgetAppTransactionRead) {
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") {
                ReadResult.Answered(ChangeSet(transactions.toList(), ChangeCursor("k-${slice.budgetApp.reader.changeReads.size}")))
            } else {
                ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("other-${asked.budgetReference}")))
            }
        }
    }

    /**
     * A Household whose Bank Transaction is Slussen's own in the Budget App: one Comparison read it, stored its copy and
     * cursor and gave its status, which the Decision Journal recorded.
     */
    private suspend fun compared(): String {
        ready()
        val bankAccount = bankAccount()
        val sent = bankTransaction(bankAccount, "ref-1", amount(-12, 340))
        answerHousehold(
            read("ynab-1", oct14, amount(-12, 340), importIdentifier = sent),
            read("ynab-2", oct14, amount(-42, 500), importIdentifier = OTHER_PATH_IDENTIFIER),
            read("ynab-3", oct14, amount(-5), payee = PLANTED_PAYEE, memo = null),
        )
        assertInstanceOf(ComparisonStart.Ran::class.java, slice.comparison.readAgain())
        return sent
    }

    private val document = InMemoryJournalExportDocument()

    private suspend fun export(codes: Boolean = false): JournalExportOutcome = slice.journalExport.export(codes, document)

    @Suppress("UNCHECKED_CAST")
    private fun file(): Map<String, Any?> = JsonText.parse(document.text) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.part(name: String): Map<String, Any?> = getValue(name) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(name: String): List<Map<String, Any?>> = getValue(name) as List<Map<String, Any?>>

    private fun complete(outcome: JournalExportOutcome): JournalExportOutcome.Complete =
        assertInstanceOf(JournalExportOutcome.Complete::class.java, outcome)

    private fun failed(outcome: JournalExportOutcome): JournalExportOutcome.Failed =
        assertInstanceOf(JournalExportOutcome.Failed::class.java, outcome)

    private suspend fun records(count: Int) = repeat(count) { slice.journal.record(JournalEventKind.SessionStart, "Session $it started") }

    // ---- Main Success Scenario --------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007", "BR-008"])
    @DisplayName("Steps 3-5, BR-007: one JSON file with the header, the entries oldest first, the Budget App's present transactions read now and a closing part with the counts; the outcome counts the entries")
    fun theJournalExportHoldsItsPartsInOrder() = runTest {
        val sent = compared()
        answerHousehold(
            read("ynab-1", oct14, amount(-12, 340), importIdentifier = sent),
            read("ynab-4", oct14, amount(150), payee = "Payroll", memo = "October"),
        )
        val entries = journal.entries

        val outcome = complete(export())

        val file = file()
        assertEquals(listOf("header", "entries", "budgetAppTransactions", "closing"), file.keys.toList())
        val header = file.part("header")
        assertEquals("Slussen Journal Export: the Decision Journal with the Budget App's transactions of the served Budgets", header["file"])
        assertEquals("9.8.7", header["versionName"])
        assertEquals(now.toString(), header["exportedAt"])
        assertEquals(false, header["codes"])
        assertEquals(
            listOf("Household", "Summer House", "Care Fund"),
            header.list("budgets").map { it["name"] },
        )
        assertEquals(
            mapOf("name" to "Household", "currency" to "USD", "source" to "Read at the export", "readAt" to now.toString()),
            header.list("budgets").first(),
        )
        assertEquals(entries.map { it.details }, file.list("entries").map { it["details"] })
        assertEquals(entries.map { it.eventKind.text }, file.list("entries").map { it["kind"] })
        assertEquals(
            listOf(
                mapOf(
                    "budget" to "Household", "budgetAccount" to "Checking", "date" to "2026-10-14", "payee" to "Payroll",
                    "memo" to "October", "amount" to BigDecimal("150"), "importIdentifier" to null, "importedByOtherPath" to false,
                ),
                mapOf(
                    "budget" to "Household", "budgetAccount" to "Checking", "date" to "2026-10-14", "payee" to PLANTED_PAYEE,
                    "memo" to PLANTED_MEMO, "amount" to BigDecimal("-12.34"), "importIdentifier" to sent, "importedByOtherPath" to false,
                ),
            ).sortedBy { it["amount"] as BigDecimal },
            file.list("budgetAppTransactions").sortedBy { it["amount"] as BigDecimal },
        )
        assertEquals(mapOf("entries" to BigDecimal(entries.size), "budgetAppTransactions" to BigDecimal(2)), file.part("closing"))
        assertEquals(entries.size, outcome.entries)
        assertEquals(2, outcome.transactions)
        assertEquals(entries.takeLast(3).reversed(), outcome.latest)
        assertTrue(document.closed)
        assertFalse(document.removed)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-007"])
    @DisplayName("Step 4, BR-001, BR-007: each entry holds its time, its kind, its Bank Account and Import Identifier when it concerns a Bank Transaction, and its details as recorded")
    fun eachEntryNamesItsBankAccountAndImportIdentifier() = runTest {
        val sent = compared()

        complete(export())

        val statusEntry = file().list("entries").single { it["kind"] == "Status Changed" }
        assertEquals(
            mapOf(
                "recordedAt" to now.toString(),
                "kind" to "Status Changed",
                "bankAccount" to "Kestrel Checking",
                "importIdentifier" to sent,
                "details" to journal.entries.single { it.eventKind == JournalEventKind.StatusChanged }.details,
            ),
            statusEntry,
        )
        val sessionLike = file().list("entries").first { it["kind"] != "Status Changed" }
        assertNull(sessionLike["bankAccount"])
        assertNull(sessionLike["importIdentifier"])
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Step 3, BR-008: the Budget App is read fresh for every served Budget, from the day of the oldest entry back by the read period of a first read (70 days), not from the stored cursor; only present transactions dated up to today in the Budget Accounts Slussen knows are exported")
    fun theBudgetAppIsReadOverTheDatesOfBr008() = runTest {
        compared()
        slice.budgetApp.reader.changeReads.clear()
        // The oldest entry was recorded today, 2026-10-15: 60 days of the file upload window and 10 of the Matching Date Window.
        val from = LocalDate.of(2026, 8, 6)
        answerHousehold(
            read("ynab-old", from.minusDays(1), amount(-1)),
            read("ynab-first", from, amount(-2)),
            read("ynab-today", today, amount(-3)),
            read("ynab-tomorrow", today.plusDays(1), amount(-4)),
            read("ynab-deleted", oct14, amount(-5), state = ReadState.Deleted),
            read("ynab-merged", oct14, amount(-6), state = ReadState.Merged("ynab-today")),
            read("ynab-unknown-account", oct14, amount(-7), account = "ba-not-known"),
        )

        complete(export())

        assertEquals(
            listOf("budget-household", "budget-summer", "budget-care"),
            slice.budgetApp.reader.changeReads.map { it.budgetReference },
        )
        assertEquals(setOf(null), slice.budgetApp.reader.changeReads.map { it.since }.toSet())
        assertEquals(setOf(from), slice.budgetApp.reader.changeReads.map { it.from }.toSet())
        assertEquals(from.toString(), file().part("header")["firstDate"])
        assertEquals(
            listOf(BigDecimal("-2"), BigDecimal("-3")),
            file().list("budgetAppTransactions").map { it["amount"] },
        )
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("BR-008: the first date is the oldest entry's day back by the read period; a transaction dated before it is left out, one on it is exported")
    fun theFirstDateFollowsTheOldestEntry() = runTest {
        slice.clock.set(Instant.parse("2026-09-01T08:00:00Z"))
        ready()
        bankAccount()
        slice.journal.record(JournalEventKind.SessionStart, "Slussen opened")
        slice.clock.set(now)
        answerHousehold(
            read("ynab-old", LocalDate.of(2026, 6, 22), amount(-1)),
            read("ynab-first", LocalDate.of(2026, 6, 23), amount(-2)),
        )

        complete(export())

        assertEquals("2026-06-23", file().part("header")["firstDate"])
        assertEquals(listOf(LocalDate.of(2026, 6, 23)), slice.budgetApp.reader.changeReads.map { it.from }.distinct())
        assertEquals(listOf(BigDecimal("-2")), file().list("budgetAppTransactions").map { it["amount"] })
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007", "BR-008"])
    @DisplayName("BR-007, BR-008: Slussen's Import Identifier is exported; one of an Other Import Path is not, and its transaction is marked as such an import; a Typed Entry has none")
    fun onlySlussensImportIdentifiersAreExported() = runTest {
        val sent = compared()
        answerHousehold(
            read("ynab-1", oct14, amount(-12, 340), importIdentifier = sent),
            read("ynab-2", oct14, amount(-42, 500), importIdentifier = OTHER_PATH_IDENTIFIER),
            read("ynab-3", oct14, amount(-5)),
        )

        complete(export())

        val byAmount = file().list("budgetAppTransactions").associateBy { it["amount"] }
        assertEquals(sent, byAmount.getValue(BigDecimal("-12.34"))["importIdentifier"])
        assertEquals(false, byAmount.getValue(BigDecimal("-12.34"))["importedByOtherPath"])
        assertNull(byAmount.getValue(BigDecimal("-42.5"))["importIdentifier"])
        assertEquals(true, byAmount.getValue(BigDecimal("-42.5"))["importedByOtherPath"])
        assertNull(byAmount.getValue(BigDecimal("-5"))["importIdentifier"])
        assertEquals(false, byAmount.getValue(BigDecimal("-5"))["importedByOtherPath"])
        assertFalse(document.text.contains(OTHER_PATH_IDENTIFIER))
    }

    // ---- BR-002: the export changes nothing stored ----------------------------------------------------------------------

    /** Everything stored that an export must leave as it was (BR-002): the copy, cursors, read times, balances, statuses, the marker, the version. */
    private suspend fun storedState(): List<Any?> = listOf(
        slice.copyStore.copy.toList(),
        slice.copyStore.cursors.toMap(),
        slice.copyStore.periodOwed.toSet(),
        slice.copyStore.pending,
        slice.connectionStore.budgets(),
        slice.connectionStore.bankAccounts(),
        slice.importStatusStore.bankTransactions.toList(),
        slice.importStatusStore.transfers.toList(),
        slice.copyStore.reviewDecisions.toList(),
        slice.stateVersion.value,
        slice.coordinatorStore.mode,
        slice.settings.settings,
        journal.storedEntries,
        slice.credentials.status(ServiceKey.budgetApp),
        slice.connectionStore.budgets().map { slice.comparisonNotices.failedRead(it.id) },
    )

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-006"])
    @DisplayName("BR-002: an export stores nothing of its read: the copy, each Budget's last read time and cursor, the balances, every Transaction Status, the Comparison marker, state_version and the Decision Journal itself are exactly as before")
    fun anExportChangesNothingStored() = runTest {
        val sent = compared()
        // The Budget App changed since the Comparison: a fresh read sees other transactions and other balances.
        answerHousehold(
            read("ynab-1", oct14, amount(-12, 340), importIdentifier = sent, state = ReadState.Deleted),
            read("ynab-9", oct14, amount(-99)),
        )
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.Answered(emptyList())
        slice.copyStore.owePeriodRead(budget("Household").id)
        val before = storedState()
        val dropped = journal.dropped

        complete(export())

        assertEquals(before, storedState())
        assertEquals(dropped, journal.dropped)
        assertTrue(file().list("budgetAppTransactions").any { it["amount"] == BigDecimal("-99") })
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read", businessRules = ["BR-002"])
    @DisplayName("BR-002: a Budget that cannot be read changes nothing stored either, and its failure is not noted for the screens as a Comparison's would be")
    fun aFailedReadChangesNothingStored() = runTest {
        compared()
        slice.budgetApp.reader.changesAnswer = { ReadResult.TransientFailure(FailureKind.Timeout) }
        val before = storedState()

        complete(export())

        assertEquals(before, storedState())
    }

    // ---- BR-003, BR-005, NFR-012: nothing that must not leave in clear --------------------------------------------------

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-005", "BR-007"], requirements = ["NFR-012"])
    @DisplayName("BR-003, BR-005, NFR-012: with codes, no planted payee, memo, Bank Account number, fingerprint, credential or identifier the Budget App gives is in the written bytes")
    fun withCodesNothingPlantedIsInTheFile() = runTest {
        compared()

        complete(export(codes = true))

        val text = String(document.bytes, Charsets.UTF_8)
        listOf(
            PLANTED_PAYEE, PLANTED_MEMO, PLANTED_BANK_PAYEE, PLANTED_BANK_MEMO, PLANTED_ACCOUNT_NUMBER, PLANTED_FINGERPRINT,
            ExampleData.BUDGET_APP_TOKEN, ExampleData.SETUP_KEY, ExampleData.EXCHANGED_ACCESS_CREDENTIAL, OTHER_PATH_IDENTIFIER,
            "ynab-1", "ynab-2", "ynab-3", "budget-household", "ba-checking", "PLANTED",
        ).forEach { planted -> assertFalse(text.contains(planted), "\"$planted\" is in the Journal Export") }
        assertEquals(true, file().part("header")["codes"])
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-007"], requirements = ["NFR-012"])
    @DisplayName("BR-003, NFR-012: without codes the Budget App's payee and memo are exported as the Operator chose, and still no Bank Transaction's payee or memo, Bank Account number, fingerprint, credential or identifier the Budget App gives is in the written bytes")
    fun withoutCodesNoSecretIsInTheFile() = runTest {
        compared()

        complete(export(codes = false))

        val text = String(document.bytes, Charsets.UTF_8)
        listOf(
            PLANTED_BANK_PAYEE, PLANTED_BANK_MEMO, PLANTED_ACCOUNT_NUMBER, PLANTED_FINGERPRINT,
            ExampleData.BUDGET_APP_TOKEN, ExampleData.SETUP_KEY, ExampleData.EXCHANGED_ACCESS_CREDENTIAL, OTHER_PATH_IDENTIFIER,
            "ynab-1", "ynab-2", "ynab-3", "budget-household", "ba-checking",
        ).forEach { planted -> assertFalse(text.contains(planted), "\"$planted\" is in the Journal Export") }
        assertTrue(text.contains(PLANTED_PAYEE))
        assertTrue(text.contains(PLANTED_MEMO))
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("BR-005: the same payee always gets the same code and the same memo the same code within one export, numbered P1, P2 and M1, M2 in order of first appearance; names and Import Identifiers are not coded")
    fun theSameTextGetsTheSameCode() = runTest {
        val sent = compared()
        answerHousehold(
            read("ynab-1", oct14, amount(-1), payee = "Corner Grocer", memo = "weekly", importIdentifier = sent),
            read("ynab-2", oct14, amount(-2), payee = "Harbor Pharmacy", memo = null),
            read("ynab-3", oct14, amount(-3), payee = "Corner Grocer", memo = "weekly"),
            read("ynab-4", oct14, amount(-4), payee = null, memo = "Corner Grocer"),
        )

        complete(export(codes = true))

        val byAmount = file().list("budgetAppTransactions").associateBy { (it["amount"] as BigDecimal).toInt() }
        val codes = (1..4).map { byAmount.getValue(-it)["payee"] to byAmount.getValue(-it)["memo"] }
        assertEquals(listOf("P1" to "M1", "P2" to null, "P1" to "M1", null to "M2"), codes)
        assertEquals("Household", byAmount.getValue(-1)["budget"])
        assertEquals("Checking", byAmount.getValue(-1)["budgetAccount"])
        assertEquals(sent, byAmount.getValue(-1)["importIdentifier"])
        assertTrue(file().list("entries").any { it["bankAccount"] == "Kestrel Checking" })
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("BR-005: codes are numbered within each export, so two exports give the same text the code of its own order")
    fun codesAreNumberedPerExport() = runTest {
        compared()
        answerHousehold(read("ynab-a", oct14, amount(-1), payee = "Alpha"))
        complete(export(codes = true))
        assertEquals("P1", file().list("budgetAppTransactions").single()["payee"])

        answerHousehold(read("ynab-b", oct14, amount(-2), payee = "Beta"), read("ynab-a", oct14, amount(-1), payee = "Alpha"))
        complete(export(codes = true))

        val payees = file().list("budgetAppTransactions").associate { (it["amount"] as BigDecimal).toInt() to it["payee"] }
        assertEquals(setOf("P1", "P2"), payees.values.toSet())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("BR-007: text with quotes, backslashes, line breaks and control characters is written as valid JSON and read back unchanged")
    fun textIsEscaped() = runTest {
        compared()
        val awkward = "Café \"Ö\" \\ line\nbreak\ttab \u0001 end"
        answerHousehold(read("ynab-x", oct14, amount(-1), payee = awkward, memo = " "))
        slice.journal.record(JournalEventKind.ConfigurationChange, "Details with \"quotes\" and \\")

        complete(export())

        assertEquals(awkward, file().list("budgetAppTransactions").single()["payee"])
        assertEquals(" ", file().list("budgetAppTransactions").single()["memo"])
        assertEquals("Details with \"quotes\" and \\", file().list("entries").last()["details"])
    }

    // ---- BR-007, ADR-002 §9: the bound noted at the start, streamed ------------------------------------------------------

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 3, BR-007: the newest entry is noted on the journal's own thread, so entries handed over and not written yet are inside the bound; an entry recorded while the Budget App is read does not enter the file")
    fun theNewestEntryIsNotedOnTheJournalsThread() = runTest {
        readyAndEmpty()
        bankAccount()
        journal.hold()
        slice.journal.record(JournalEventKind.SessionStart, "Queued before the export")
        var recordedDuringRead = false
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (!recordedDuringRead) {
                recordedDuringRead = true
                journal.record(JournalEntry(now, JournalEventKind.SessionStart, "Recorded while the Budget App is read"), keepWithinBytes = null)
            }
            ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("c-${asked.budgetReference}")))
        }

        val running = async { export() }
        runCurrent()
        // The entry handed over, then the noting of the bounds behind it; nothing of the Budget App is read yet.
        assertEquals(2, journal.waiting)
        assertTrue(slice.budgetApp.reader.changeReads.isEmpty())
        journal.release()
        val outcome = complete(running.await())

        assertEquals(listOf("Queued before the export"), file().list("entries").map { it["details"] })
        assertEquals(1, outcome.entries)
        assertEquals(listOf("Queued before the export", "Recorded while the Budget App is read"), journal.entries.map { it.details })
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-006"])
    @DisplayName("BR-007 (ADR-002 §9): the entries are read in pages of a bound size, never all at once, and written as they are read")
    fun theEntriesAreStreamedInPages() = runTest {
        readyAndEmpty()
        records(1_234)
        val writtenBeforePage = mutableListOf<Int>()
        journal.beforePage = { writtenBeforePage += document.text.length }

        val outcome = complete(export())

        assertEquals(1_234, outcome.entries)
        assertEquals(1_234, file().list("entries").size)
        assertTrue(journal.pagesAsked.size >= 3, "the entries are read in several pages")
        assertTrue(journal.pagesAsked.all { it <= JournalExport.PAGE_SIZE + 1 }, "no page is larger than a page")
        assertTrue(writtenBeforePage.zipWithNext().all { (a, b) -> b > a }, "each page is written before the next is read")
        assertEquals((0 until 1_234).map { "Session $it started" }, file().list("entries").map { it["details"] })
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["NFR-006"])
    @DisplayName("BR-007, UC-010 BR-003: a trimming of entries already written, between two pages, neither blocks nor changes the file; an entry recorded meanwhile does not enter it")
    fun aTrimmingOfWrittenEntriesDoesNotChangeTheFile() = runTest {
        readyAndEmpty()
        records(1_200)
        journal.beforePage = { page ->
            if (page == 1) {
                journal.removeOldest(100)
                slice.journal.record(JournalEventKind.SessionStart, "Recorded while the entries are written")
            }
        }

        val outcome = complete(export())

        assertEquals(1_200, outcome.entries)
        assertEquals((0 until 1_200).map { "Session $it started" }, file().list("entries").map { it["details"] })
        assertEquals(1_101, journal.entries.size)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written", businessRules = ["BR-007"], requirements = ["NFR-006"])
    @DisplayName("BR-007: a clearing between two pages, which removes entries not written yet, is not blocked; the export does not present a file with a gap: it removes the file and says why")
    fun aClearingWhileTheEntriesAreWrittenRemovesTheFile() = runTest {
        readyAndEmpty()
        records(1_200)
        journal.beforePage = { page ->
            if (page == 1) assertEquals(Unit, journal.clear(firstEntry = null).await())
        }

        val outcome = failed(export())

        assertEquals(ExportFailure.JournalChangedWhileWritten, outcome.why)
        assertTrue(outcome.removed)
        assertTrue(document.removed)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-007"], requirements = ["NFR-006"])
    @DisplayName("ADR-007 §2, UC-010 BR-004: while the entries are written the operation lane is free, so an Operator Setting changed meanwhile is stored at once; only the read of the Budget App runs on the lane")
    fun theExportHoldsTheLaneOnlyForTheBudgetAppRead() = runTest {
        readyAndEmpty()
        bankAccount()
        records(600)
        val duringRead = mutableListOf<OperationKind?>()
        slice.budgetApp.reader.changesAnswer = { asked ->
            duringRead += slice.coordinator.running.value?.kind
            ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("c-${asked.budgetReference}")))
        }
        val changed = CompletableDeferred<SettingOutcome>()
        journal.beforePage = { page ->
            if (page == 1) {
                assertNull(slice.coordinator.running.value)
                changed.complete(withTimeout(5_000) { slice.generalSettings.change(NumberSetting.DefaultWait, 9) })
            }
        }

        complete(export())

        assertEquals(setOf(OperationKind.JournalExport), duringRead.toSet())
        assertEquals(SettingOutcome.Stored(9), changed.await())
        assertEquals(600, file().list("entries").size)
    }

    // ---- A2: Budget App Cannot Be Read -----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read", businessRules = ["BR-007"])
    @DisplayName("A2: a Budget the Budget App does not answer for comes from the stored copy, with its memo and the time of its last successful read; the Budgets read come from the read; nothing of the failed read is used")
    fun aBudgetThatCannotBeReadComesFromTheStoredCopy() = runTest {
        val sent = compared()
        val household = budget("Household")
        slice.budgetApp.reader.changesAnswer = { asked ->
            if (asked.budgetReference == "budget-household") {
                ReadResult.TransientFailure(FailureKind.ConnectionLost)
            } else {
                ReadResult.Answered(ChangeSet(emptyList(), ChangeCursor("c")))
            }
        }

        val outcome = complete(export())

        val budgets = file().part("header").list("budgets")
        assertEquals(
            mapOf(
                "name" to "Household", "currency" to "USD", "source" to "Stored copy", "lastReadAt" to household.lastReadAt.toString(),
                "reason" to "The Budget App could not be reached: the connection was lost.",
            ),
            budgets.first(),
        )
        assertEquals("Read at the export", budgets[1]["source"])
        val amounts = file().list("budgetAppTransactions").map { it["amount"] }.toSet()
        assertEquals(setOf(BigDecimal("-12.34"), BigDecimal("-42.5"), BigDecimal("-5")), amounts)
        assertEquals(PLANTED_MEMO, file().list("budgetAppTransactions").single { it["importIdentifier"] == sent }["memo"])
        assertEquals(
            ExportedBudget.FromStoredCopy("Household", USD, household.lastReadAt!!, BudgetNotRead.NoAnswer(FailureKind.ConnectionLost)),
            outcome.budgets.first(),
        )
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read", businessRules = ["BR-007"])
    @DisplayName("A2, UC-014 BR-005: without credentials for the Budget App it is not contacted; a Budget read before comes from the stored copy and one never read is named with none of its transactions")
    fun withoutCredentialsTheBudgetAppIsNotContacted() = runTest {
        compared()
        slice.credentials.remove(ServiceKey.budgetApp)
        slice.budgetApp.reader.changeReads.clear()
        val contextsBefore = slice.budgetApp.reader.contexts.size

        val outcome = complete(export())

        assertTrue(slice.budgetApp.reader.changeReads.isEmpty())
        assertEquals(contextsBefore, slice.budgetApp.reader.contexts.size)
        val budgets = file().part("header").list("budgets")
        assertEquals("Stored copy", budgets[0]["source"])
        assertEquals("No credentials for the Budget App are entered, so it was not contacted.", budgets[0]["reason"])
        assertEquals("None", budgets[1]["source"])
        assertEquals(3, file().list("budgetAppTransactions").size)
        assertEquals(BudgetNotRead.CredentialsMissing, (outcome.budgets[0] as ExportedBudget.FromStoredCopy).why)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read", businessRules = ["BR-007"])
    @DisplayName("A2: a Budget that was never read is named in the header, with why, and none of its transactions are in the export")
    fun aBudgetNeverReadHasNoTransactions() = runTest {
        readyAndEmpty()
        slice.credentials.remove(ServiceKey.budgetApp)
        records(1)

        val outcome = complete(export())

        assertEquals(
            mapOf(
                "name" to "Household", "currency" to "USD", "source" to "None",
                "reason" to "No credentials for the Budget App are entered, so it was not contacted. It was never read, so none of its transactions are in this export.",
            ),
            file().part("header").list("budgets").first(),
        )
        assertTrue(file().list("budgetAppTransactions").isEmpty())
        assertEquals(ExportedBudget.NeverRead("Household", USD, BudgetNotRead.CredentialsMissing), outcome.budgets.first())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read", businessRules = ["BR-002"])
    @DisplayName("A2, BR-002, UC-014 BR-005: credentials the Budget App refuses during the read are recorded as refused, the one thing an export stores; the Budgets after it are not contacted and come from the stored copy")
    fun refusedCredentialsAreRecordedAndTheRestIsNotContacted() = runTest {
        compared()
        slice.budgetApp.reader.changeReads.clear()
        slice.budgetApp.reader.changesAnswer = { ReadResult.CredentialsRefused }

        val outcome = complete(export())

        assertEquals(1, slice.budgetApp.reader.changeReads.size)
        assertEquals(CredentialStatus.Refused, slice.credentials.status(ServiceKey.budgetApp))
        assertEquals(List(3) { BudgetNotRead.CredentialsRefused }, outcome.budgets.map { (it as? ExportedBudget.FromStoredCopy)?.why ?: (it as ExportedBudget.NeverRead).why })
        assertEquals(
            "The Budget App refused Slussen's credentials, so it was not read; enter new ones on its page in Settings.",
            file().part("header").list("budgets").first()["reason"],
        )
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A2: Budget App Cannot Be Read", businessRules = ["BR-007"])
    @DisplayName("A2, UC-014 BR-005: credentials refused before the export are not used: the Budget App is not contacted")
    fun credentialsAlreadyRefusedAreNotUsed() = runTest {
        compared()
        slice.credentials.markRefused(ServiceKey.budgetApp)
        slice.budgetApp.reader.changeReads.clear()

        val outcome = complete(export())

        assertTrue(slice.budgetApp.reader.changeReads.isEmpty())
        assertEquals(BudgetNotRead.CredentialsRefused, (outcome.budgets.first() as ExportedBudget.FromStoredCopy).why)
    }

    // ---- A3: File Cannot Be Written ----------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written", businessRules = ["BR-007"])
    @DisplayName("A3: a write that fails part-way stops, removes the file Slussen began and says that the file could not be written")
    fun aWriteThatFailsPartWayRemovesTheFile() = runTest {
        compared()
        records(800)
        document.failsAfterCharacters = 5_000

        val outcome = failed(export())

        assertEquals(JournalExportOutcome.Failed(ExportFailure.NotWritten, removed = true), outcome)
        assertTrue(document.removed)
        assertEquals("", document.text)
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written", businessRules = ["BR-007"])
    @DisplayName("A3 step 1: when the file Slussen began cannot be removed, the outcome says so, so that the screen names it as incomplete and no Journal Export; it has no closing part")
    fun aFileThatCannotBeRemovedIsNamedIncomplete() = runTest {
        compared()
        document.failsAfterCharacters = 300
        document.failsToRemove = true

        val outcome = failed(export())

        assertEquals(JournalExportOutcome.Failed(ExportFailure.NotWritten, removed = false), outcome)
        assertFalse(document.text.contains("\"closing\""))
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written", businessRules = ["BR-007"])
    @DisplayName("A3: a place that cannot be opened is answered as not written, and the document is removed")
    fun aPlaceThatCannotBeOpened() = runTest {
        compared()
        document.failsToOpen = true

        assertEquals(JournalExportOutcome.Failed(ExportFailure.NotWritten, removed = true), export())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written", businessRules = ["BR-002"], requirements = ["NFR-006"])
    @DisplayName("Failure postconditions: a Decision Journal that cannot be read writes no Journal Export, removes the document and changes nothing")
    fun aJournalThatCannotBeReadWritesNothing() = runTest {
        compared()
        journal.failsToReadEntries = true
        val before = storedState()

        val outcome = failed(export())

        assertEquals(JournalExportOutcome.Failed(ExportFailure.JournalNotRead, removed = true), outcome)
        assertEquals(before, storedState())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "A3: File Cannot Be Written", businessRules = ["BR-002"])
    @DisplayName("Failure postconditions: stored data that cannot be read (here the coordinator's mode) writes no Journal Export, throws nothing and removes the document")
    fun storedDataThatCannotBeReadWritesNothing() = runTest {
        compared()
        slice.coordinatorStore.failsToRead = true

        assertEquals(JournalExportOutcome.Failed(ExportFailure.StoredDataNotRead, removed = true), export())
        assertTrue(document.removed)
    }

    // ---- Preconditions -------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Precondition: an empty Decision Journal is nothing to export: no Budget App is contacted, the document is removed")
    fun anEmptyJournalIsNothingToExport() = runTest {
        readyAndEmpty()
        bankAccount()

        assertEquals(JournalExportOutcome.Failed(ExportFailure.NothingToExport, removed = true), export())
        assertTrue(slice.budgetApp.reader.changeReads.isEmpty())
        assertEquals(false, slice.journalExport.hasEntries())
        records(1)
        assertEquals(true, slice.journalExport.hasEntries())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario")
    @DisplayName("Precondition, UC-006 BR-005: while an Upload Plan awaits confirmation or an Upload is written the export is unavailable, nothing is read and the document is removed")
    fun unavailableWhileAPlanIsOpenOrAnUploadIsWritten() = runTest {
        compared()
        slice.budgetApp.reader.changeReads.clear()
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        assertEquals(JournalExportOutcome.Failed(ExportFailure.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), removed = true), export())
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        assertEquals(JournalExportOutcome.Failed(ExportFailure.Unavailable(OperationRefusal.UploadBeingWritten), removed = true), export())
        assertTrue(slice.budgetApp.reader.changeReads.isEmpty())
    }

    @Test
    @UseCase(id = "UC-011", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("BR-007: the proposed name is slussen-journal-<date of the export>.json")
    fun theProposedName() {
        assertEquals("slussen-journal-2026-10-15.json", slice.journalExport.proposedName())
    }
}
