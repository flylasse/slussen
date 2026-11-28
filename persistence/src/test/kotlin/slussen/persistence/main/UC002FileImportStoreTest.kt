package slussen.persistence.main

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.FileTransaction
import slussen.core.port.StableReferenceKind
import slussen.core.port.TransactionFileOutcome
import slussen.core.port.TransactionFileToImport
import slussen.core.port.WholeImport
import slussen.persistence.RoomFileImportStore
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.testing.InMemoryImportStatusStore
import java.time.Instant
import java.time.LocalDate

/**
 * O-3 `FileImportStore.importWhole` of `slussen.db` on the JVM: the body of [TransactionFileDao.importWhole] run on a
 * list-backed subclass of the DAO (the SQLite transaction around it, and its rollback, are proven on a device in
 * `UC002ImportATransactionFilePersistenceIT`). The same sequence of files, the cases of survey D risk R1, goes through it
 * and through the fake of `:testing` that every core test uses, and both store the same Bank Transactions with the same
 * Import Identifiers, Stable References and occurrence numbers after every file: the fake cannot differ from the device,
 * since both apply the core's one counting rule to what they hold.
 */
@DisplayName("UC-002: Import a Transaction File, O-3 of slussen.db on the JVM")
class UC002FileImportStoreTest {
    private val usd = CurrencyCode("USD")
    private val sourceKey = "example-format"
    private val oct10: LocalDate = LocalDate.of(2026, 10, 10)
    private val oct14: LocalDate = LocalDate.of(2026, 10, 14)

    /** `transaction_file` and `bank_transaction` as lists, with `state_version` as a counter. */
    private class ListedDao : TransactionFileDao() {
        val files = mutableListOf<TransactionFileEntity>()
        val transactions = mutableListOf<BankTransactionEntity>()
        var stateVersion = 0L

        override suspend fun insert(file: TransactionFileEntity): Long {
            val id = files.size + 1L
            files += file.copy(id = id)
            return id
        }

        override suspend fun all(): List<TransactionFileEntity> = files.toList()

        override suspend fun of(bankAccountId: Long): List<TransactionFileEntity> = files.filter { it.bankAccountId == bankAccountId }.reversed()

        override suspend fun latest(): List<TransactionFileEntity> = error("Not read in this test.")

        override suspend fun bankTransactionsOf(bankAccountId: Long): List<BankTransactionEntity> = transactions.filter { it.bankAccountId == bankAccountId }

        override suspend fun insertBankTransactions(transactions: List<BankTransactionEntity>) {
            transactions.forEach { this.transactions += it.copy(id = this.transactions.size + 1L) }
        }

        override suspend fun raiseStateVersion() {
            stateVersion++
        }
    }

    private fun tx(date: LocalDate, amount: Long, payee: String, reference: String? = null, kind: StableReferenceKind = StableReferenceKind.Derived) =
        FileTransaction(reference ?: "ref:$date:$amount:$payee", kind, date, Amount(amount), usd, payee, null)

    private fun file(bankAccountId: Long, minute: Long, vararg transactions: FileTransaction) =
        TransactionFileToImport(bankAccountId, fileFormatId = 1, sourceKey = sourceKey, importedAt = Instant.parse("2026-10-15T12:00:00Z").plusSeconds(minute * 60), transactions = transactions.toList())

    /** What must be equal in `slussen.db` and the fake: every Bank Transaction's identity, in the order stored. */
    private data class Identity(val bankAccountId: Long, val importIdentifier: String, val stableReference: String?, val referenceKind: StableReferenceKind, val occurrenceNumber: Int)

    @Test
    @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-001", "BR-002", "BR-003"], requirements = ["NFR-020"])
    @DisplayName("BR-003 (O-3): the files of each case of risk R1, in turn, give in slussen.db exactly the Bank Transactions, Import Identifiers, Stable References and occurrence numbers the fake gives; a stored one never changes; each file is recorded Imported with the number it delivered, and state_version rises only when a Bank Transaction is new")
    fun theRoomStoreCountsAsTheFakeDoes() = runBlocking {
        val dao = ListedDao()
        val room = RoomFileImportStore(dao)
        val fake = InMemoryImportStatusStore()
        val pharmacy = tx(oct14, -12_340, "DEBIT CARD PURCHASE HARBOR PHARMACY")
        val interest = tx(oct10, 1_200, "INTEREST EARNED")
        val fee = tx(oct10, -500, "MONTHLY FEE")
        val supplied = tx(oct10, -500, "MONTHLY FEE", reference = "BANK-9", kind = StableReferenceKind.Supplied)
        val files = listOf(
            file(1, 1, interest),
            file(1, 2, pharmacy, interest, interest),
            file(1, 3, pharmacy, interest, interest),
            file(1, 4, interest, interest, interest),
            file(1, 5, interest),
            file(2, 6, fee, fee),
            file(2, 7, fee),
            file(2, 8, fee, fee, fee, supplied),
            file(2, 9, pharmacy),
        )

        var storedBefore = emptyList<Identity>()
        files.forEach { next ->
            val versionBefore = dao.stateVersion
            val inRoom = room.importWhole(next)
            val inFake = fake.importWhole(next)

            val roomIdentities = dao.transactions.map { Identity(it.bankAccountId, it.importIdentifier, it.stableReference, StableReferenceKind.entries.single { k -> k.text == it.referenceKind }, it.occurrenceNumber) }
            val fakeIdentities = fake.bankTransactions.map { Identity(it.bankAccountId, it.importIdentifier, it.stableReference, it.referenceKind, it.occurrenceNumber) }
            assertEquals(fakeIdentities, roomIdentities, "after the file of minute ${next.importedAt}")
            assertEquals(inFake, inRoom)
            assertEquals(storedBefore, roomIdentities.take(storedBefore.size), "a stored Bank Transaction never changes")
            val taken = (inRoom as WholeImport.Imported).takenIn.size
            assertEquals(if (taken > 0) versionBefore + 1 else versionBefore, dao.stateVersion)
            storedBefore = roomIdentities
        }

        assertEquals(
            listOf(
                Identity(1, r(interest.reference), interest.reference, StableReferenceKind.Derived, 1),
                Identity(1, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
                Identity(1, f(interest, 1), null, StableReferenceKind.None, 1),
                Identity(1, f(interest, 2), null, StableReferenceKind.None, 2),
                Identity(2, f(fee, 1), null, StableReferenceKind.None, 1),
                Identity(2, f(fee, 2), null, StableReferenceKind.None, 2),
                Identity(2, f(fee, 3), null, StableReferenceKind.None, 3),
                Identity(2, r("BANK-9"), "BANK-9", StableReferenceKind.Supplied, 1),
                Identity(2, r(pharmacy.reference), pharmacy.reference, StableReferenceKind.Derived, 1),
            ),
            storedBefore,
        )
        assertEquals(files.map { it.transactions.size }, dao.files.map { it.transactionCount })
        assertTrue(dao.files.all { it.outcome == TransactionFileOutcome.Imported.text && it.rejectionReason == null && it.fileFormatId == 1L })
        assertTrue(dao.transactions.all { it.status == null && it.transferId == null && it.currency == "USD" })
    }

    @Test
    @UseCase(id = "UC-002", scenario = "A6: Import Identifier Collision", businessRules = ["BR-001"], requirements = ["NFR-020"])
    @DisplayName("A6 (O-3): with a planted collision, the Room store writes nothing: no Bank Transaction, no Transaction File and no raised state_version")
    fun aCollisionWritesNothing() = runBlocking {
        val dao = ListedDao()
        val planted = RoomFileImportStore(dao) { ImportIdentifierProtocol.importIdentifier(it) { ByteArray(32) } }
        planted.importWhole(file(1, 1, tx(oct10, 1_200, "INTEREST EARNED")))
        val before = dao.transactions.toList()
        val filesBefore = dao.files.toList()
        val versionBefore = dao.stateVersion

        val outcome = planted.importWhole(file(1, 2, tx(oct10, 1_200, "INTEREST EARNED"), tx(oct14, -12_340, "DEBIT CARD PURCHASE HARBOR PHARMACY")))

        assertInstanceOf(WholeImport.Collision::class.java, outcome)
        assertEquals(before, dao.transactions)
        assertEquals(filesBefore, dao.files)
        assertEquals(versionBefore, dao.stateVersion)
    }

    private fun r(reference: String) = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference(sourceKey, reference))

    private fun f(t: FileTransaction, occurrence: Int) = ImportIdentifierProtocol.importIdentifier(
        ImportIdentifierInput.WithoutStableReference(sourceKey, t.bookedDate, t.amount, t.payee, t.memo, occurrence),
    )
}
