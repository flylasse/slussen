package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.FileFormatRegistration
import slussen.core.port.FileTransaction
import slussen.core.port.NewBankAccount
import slussen.core.port.RejectedTransactionFile
import slussen.core.port.StableReferenceKind
import slussen.core.port.StoredTransactionFile
import slussen.core.port.TransactionFileOutcome
import slussen.core.port.TransactionFileToImport
import slussen.core.port.WholeImport
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * O-3 `FileImportStore.importWhole` and the Transaction Files of Import a Transaction File (UC-002) in `slussen.db` on a
 * device or emulator: the file counted against the stored Bank Transactions inside one transaction (BR-003, the cases of
 * survey D risk R1), all or nothing with its `transaction_file` record and `state_version` (BR-001), a planted collision
 * that writes nothing (A6, ADR-008 §5), a rejected file that stores only its record, and what the import status reads
 * back after a restart (BR-006, ADR-002 §1, §3, §8).
 */
@DisplayName("UC-002: Import a Transaction File, on the device")
class UC002ImportATransactionFilePersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val usd = CurrencyCode("USD")
    private val sourceKey = "example-format"
    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    /** The fake File Format registered, the Budget Care Fund served and two Bank Accounts of the File Format added. */
    private class Prepared(val fileFormatId: Long, val harbor: Long, val reserve: Long)

    private suspend fun prepared(storage: SlussenStorage): Prepared {
        val fileFormatId = storage.registry.reconcile(emptyList(), listOf(FileFormatRegistration("Harbor Health statement", sourceKey))).fileFormats.single().id
        storage.connections.serveBudgets(
            listOf(
                BudgetToServe(
                    "budget-care", "Care Fund", usd,
                    listOf(BudgetAccountToStore("ba-health", "Health Savings", Amount(6_324_200)), BudgetAccountToStore("ba-reserve", "Reserve Savings", Amount(12_000_000))),
                ),
            ),
            setOf("budget-care"),
            now,
        )
        val budgetAccounts = storage.connections.budgets().single().budgetAccounts
        suspend fun add(name: String, budgetAccount: String, suffix: String): Long = (
            storage.connections.addBankAccount(
                NewBankAccount(
                    generatedIdentifier = "00000000-0000-4000-8000-00000000$suffix",
                    bank = BankChoice.Named("Harbor Health Trust"),
                    providerBankId = null,
                    dataProviderId = null,
                    fileFormatId = fileFormatId,
                    balanceDataProviderId = null,
                    balanceProviderReference = null,
                    name = name,
                    currency = usd,
                    type = BankAccountType.Savings,
                    providerReference = null,
                    bankAccountFingerprint = null,
                    identifiedAt = now,
                    startDate = null,
                    budgetAccountId = budgetAccounts.single { it.name == budgetAccount }.id,
                ),
            ) as AddOutcome.Added
            ).bankAccountId
        return Prepared(fileFormatId, add("Harbor Health Savings", "Health Savings", "f11e"), add("Harbor Reserve", "Reserve Savings", "f12e"))
    }

    private fun tx(date: LocalDate, amount: Long, payee: String, reference: String? = null, kind: StableReferenceKind = StableReferenceKind.Derived) =
        FileTransaction(reference ?: "ref:$date:$amount:$payee", kind, date, Amount(amount), usd, payee, null)

    private fun file(prepared: Prepared, bankAccountId: Long, minute: Long, vararg transactions: FileTransaction, fileFormatId: Long = prepared.fileFormatId) =
        TransactionFileToImport(bankAccountId, fileFormatId, sourceKey, now.plusSeconds(minute * 60), transactions.toList())

    private fun stateVersion(storage: SlussenStorage): Long =
        storage.main.openHelper.readableDatabase.query("SELECT state_version FROM coordinator_state WHERE id = 1").use { it.moveToFirst(); it.getLong(0) }

    private fun count(storage: SlussenStorage, table: String): Int =
        storage.main.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private data class Identity(val bankAccountId: Long, val importIdentifier: String, val stableReference: String?, val referenceKind: StableReferenceKind, val occurrenceNumber: Int)

    private suspend fun identities(storage: SlussenStorage, vararg bankAccounts: Long): List<Identity> = bankAccounts.flatMap { id ->
        storage.fetches.bankTransactionsOf(id).map { Identity(it.bankAccountId, it.importIdentifier, it.stableReference, it.referenceKind, it.occurrenceNumber) }
    }

    private fun r(reference: String) = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference(sourceKey, reference))

    private fun f(t: FileTransaction, occurrence: Int) = ImportIdentifierProtocol.importIdentifier(
        ImportIdentifierInput.WithoutStableReference(sourceKey, t.bookedDate, t.amount, t.payee, t.memo, occurrence),
    )

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003", "BR-006"], requirements = ["NFR-020"])
    @DisplayName("Step 7, BR-003 (O-3): a file's new Bank Transactions are stored with their Import Identifier, Stable Reference and occurrence_number, and the file Imported with the number it delivered; state_version rises; the same file again stores only its Transaction File; all of it is read back after a restart and passes the start check")
    fun anImportedFileIsStoredAndTheSameFileAgainChangesNothing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val prepared = prepared(storage)
        val pharmacy = tx(oct14, -12_340, "DEBIT CARD PURCHASE HARBOR PHARMACY")
        val interest = tx(oct10, 1_200, "INTEREST EARNED")
        val versionBefore = stateVersion(storage)

        val first = storage.fileImports.importWhole(file(prepared, prepared.harbor, 1, pharmacy, interest, interest))

        assertEquals(3, (first as WholeImport.Imported).takenIn.size)
        assertTrue(stateVersion(storage) > versionBefore)
        val stored = identities(storage, prepared.harbor)
        val versionAfterFirst = stateVersion(storage)
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)
        assertTrue(restarted.startState.unreadableParts.isEmpty())
        assertEquals(
            listOf(
                Identity(prepared.harbor, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
                Identity(prepared.harbor, f(interest, 1), null, StableReferenceKind.None, 1),
                Identity(prepared.harbor, f(interest, 2), null, StableReferenceKind.None, 2),
            ),
            stored,
        )
        assertEquals(stored, identities(restarted, prepared.harbor))
        assertTrue(restarted.fetches.bankTransactionsOf(prepared.harbor).all { it.status == null && it.transferId == null && it.firstSeenAt == now.plusSeconds(60) })

        val again = restarted.fileImports.importWhole(file(prepared, prepared.harbor, 2, pharmacy, interest, interest))

        assertEquals(WholeImport.Imported(emptyList()), again)
        assertEquals(stored, identities(restarted, prepared.harbor))
        assertEquals(versionAfterFirst, stateVersion(restarted))
        assertEquals(2, count(restarted, "transaction_file"))
        assertEquals(listOf(StoredTransactionFile(prepared.harbor, now.plusSeconds(120), TransactionFileOutcome.Imported, 3)), restarted.importStatus.latestTransactionFiles())
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002", "BR-003"], requirements = ["NFR-020"])
    @DisplayName("BR-003, A4 (O-3): the cases of risk R1 in turn (an overlapping later file, two identical lines with one stored with a Stable Reference, the reverse, three identical lines over two files, a Supplied reference beside identical ones, a reference another Bank Account holds) give no transaction a second Import Identifier, and no stored one changes")
    fun theCasesOfRiskR1() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val prepared = prepared(storage)
        val harbor = prepared.harbor
        val reserve = prepared.reserve
        val pharmacy = tx(oct14, -12_340, "DEBIT CARD PURCHASE HARBOR PHARMACY")
        val interest = tx(oct10, 1_200, "INTEREST EARNED")
        val fee = tx(oct10, -500, "MONTHLY FEE")
        val supplied = tx(oct10, -500, "MONTHLY FEE", reference = "BANK-9", kind = StableReferenceKind.Supplied)
        val files = listOf(
            file(prepared, harbor, 1, interest),
            file(prepared, harbor, 2, pharmacy, interest, interest),
            file(prepared, harbor, 3, pharmacy, interest, interest),
            file(prepared, harbor, 4, interest, interest, interest),
            file(prepared, harbor, 5, interest),
            file(prepared, reserve, 6, fee, fee),
            file(prepared, reserve, 7, fee),
            file(prepared, reserve, 8, fee, fee, fee, supplied),
            file(prepared, reserve, 9, pharmacy),
        )

        var before = emptyList<Identity>()
        files.forEach { next ->
            storage.fileImports.importWhole(next)
            val after = identities(storage, harbor, reserve)
            assertEquals(before, after.filter { it in before }, "a stored Bank Transaction never changes")
            assertEquals(before.size, after.indexOfFirst { it !in before }.takeIf { it >= 0 } ?: after.size, "new ones come after the stored ones")
            before = after
        }

        assertEquals(
            listOf(
                Identity(harbor, r(interest.reference), interest.reference, StableReferenceKind.Derived, 1),
                Identity(harbor, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
                Identity(harbor, f(interest, 1), null, StableReferenceKind.None, 1),
                Identity(harbor, f(interest, 2), null, StableReferenceKind.None, 2),
                Identity(reserve, f(fee, 1), null, StableReferenceKind.None, 1),
                Identity(reserve, f(fee, 2), null, StableReferenceKind.None, 2),
                Identity(reserve, f(fee, 3), null, StableReferenceKind.None, 3),
                Identity(reserve, r("BANK-9"), "BANK-9", StableReferenceKind.Supplied, 1),
                Identity(reserve, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
            ),
            before,
        )
        assertEquals(files.size, count(storage, "transaction_file"))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-001"])
    @DisplayName("BR-001 (O-3): an import that fails part-way, here at its Transaction File after its Bank Transactions were written (a File Format that is not stored, refused by its foreign key), stores none of it: no Bank Transaction, no Transaction File, no raised state_version")
    fun aFailurePartWayStoresNothing() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val prepared = prepared(storage)
        storage.fileImports.importWhole(file(prepared, prepared.harbor, 1, tx(oct10, 1_200, "INTEREST EARNED")))
        val before = identities(storage, prepared.harbor)
        val versionBefore = stateVersion(storage)

        val failed = runCatching {
            storage.fileImports.importWhole(
                file(prepared, prepared.harbor, 2, tx(oct10, 1_200, "INTEREST EARNED"), tx(oct14, -12_340, "DEBIT CARD PURCHASE HARBOR PHARMACY"), fileFormatId = 999),
            )
        }

        assertTrue(failed.isFailure)
        assertEquals(before, identities(storage, prepared.harbor))
        assertEquals(versionBefore, stateVersion(storage))
        assertEquals(1, count(storage, "transaction_file"))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A6: Import Identifier Collision", businessRules = ["BR-001"], requirements = ["NFR-020"])
    @DisplayName("A6 (O-3, ADR-008 §5): a planted Import Identifier collision writes nothing of the file; the file is then recorded as rejected with its reason and 0 transactions, and the import status reads it as the latest with the last imported one after it")
    fun aCollisionWritesNothingAndARejectedFileStoresOnlyItsRecord() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val prepared = prepared(storage)
        val planted = RoomFileImportStore(storage.main.transactionFiles()) { ImportIdentifierProtocol.importIdentifier(it) { ByteArray(32) } }
        planted.importWhole(file(prepared, prepared.harbor, 1, tx(oct10, 1_200, "INTEREST EARNED")))
        val before = identities(storage, prepared.harbor)
        val versionBefore = stateVersion(storage)

        val outcome = planted.importWhole(file(prepared, prepared.harbor, 2, tx(oct10, 1_200, "INTEREST EARNED"), tx(oct14, -12_340, "DEBIT CARD PURCHASE HARBOR PHARMACY")))
        assertInstanceOf(WholeImport.Collision::class.java, outcome)
        storage.fileImports.recordRejected(RejectedTransactionFile(prepared.harbor, prepared.fileFormatId, now.plusSeconds(120), "Import Identifier collision"))

        assertEquals(before, identities(storage, prepared.harbor))
        assertEquals(versionBefore, stateVersion(storage))
        assertTrue(runCatching { storage.fileImports.recordRejected(RejectedTransactionFile(prepared.harbor, prepared.fileFormatId, now, "x".repeat(201))) }.isFailure)
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)
        assertTrue(restarted.startState.unreadableParts.isEmpty())
        assertEquals(
            listOf(
                StoredTransactionFile(prepared.harbor, now.plusSeconds(120), TransactionFileOutcome.Rejected, 0, "Import Identifier collision"),
                StoredTransactionFile(prepared.harbor, now.plusSeconds(60), TransactionFileOutcome.Imported, 1),
            ),
            restarted.importStatus.latestTransactionFiles(),
        )
        restarted.close()
    }
}
