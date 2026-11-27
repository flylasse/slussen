package slussen.app.di

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.connection.BudgetsRead
import slussen.core.fileimport.FileImportOutcome
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.NewBankAccount
import slussen.core.port.StableReferenceKind
import slussen.fileformat.hsacsv.HsaCsvFilePreprocessor
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData.USD
import slussen.testing.TestSlice
import java.time.Duration

/**
 * Import a Transaction File (UC-002) through the core of phase D2 ([slussen.core.fileimport.ImportTransactionFile]) on
 * the fakes of `:testing`, with the real File Pre-processor of this build (ADR-001 §4.3.1, Source Key `hsa-csv`) in place
 * of the fake, as the composition root wires it: two invented, overlapping exports of one invented history (survey D
 * risk R1). The expected references are the committed test vectors of ADR-001 §4.3.1 and SHA-256 digests of canonical
 * inputs written out by hand; none is taken from the code.
 */
@DisplayName("UC-002: Import a Transaction File, with the File Format of this build")
class UC002ImportTransactionFileWithTheRealFileFormatTest {
    private val slice = TestSlice(filePreprocessor = HsaCsvFilePreprocessor(SilentLog))

    /** The Stable Reference and Import Identifier of one stored Bank Transaction (ADR-008 §4: never changed once stored). */
    private data class Identity(val stableReference: String?, val referenceKind: StableReferenceKind, val occurrenceNumber: Int, val importIdentifier: String)

    private fun identities(): List<Identity> =
        slice.importStatusStore.bankTransactions.map { Identity(it.stableReference, it.referenceKind, it.occurrenceNumber, it.importIdentifier) }

    /** A Bank Transaction stored with [reference] as its Derived Stable Reference: Import Identifier of kind R with `hsa-csv`. */
    private fun named(reference: String) = Identity(
        reference, StableReferenceKind.Derived, 1,
        ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("hsa-csv", reference)),
    )

    private suspend fun harborAccount(): Long {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer", "budget-care"))
        val budgetAccountId = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Health Savings" }.id
        val added = slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-0000000d3f11",
                bank = BankChoice.Named("Harbor Health Trust"),
                providerBankId = null,
                dataProviderId = null,
                fileFormatId = slice.plugins.fileFormats.single().id,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = "Harbor Health Savings",
                currency = USD,
                type = BankAccountType.Savings,
                providerReference = null,
                bankAccountFingerprint = null,
                identifiedAt = slice.clock.instant(),
                startDate = null,
                budgetAccountId = budgetAccountId,
            ),
        )
        return (added as AddOutcome.Added).bankAccountId
    }

    private suspend fun import(bankAccountId: Long, file: ByteArray): FileImportOutcome.Imported {
        slice.clock.advance(Duration.ofMinutes(5))
        return assertInstanceOf(FileImportOutcome.Imported::class.java, slice.fileImport.import(bankAccountId, file))
    }

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"], requirements = ["NFR-020", "C-010"])
    @DisplayName("BR-002, BR-003: the first export, then an overlapping later one, then the first again: each transaction is stored once with its derived reference as its Stable Reference, and no transaction gets a second Import Identifier")
    fun overlappingExportsThroughTheCore() = runTest {
        val harbor = harborAccount()
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.TransientFailure(FailureKind.Timeout)
        val g = named(VECTOR_1_REFERENCE)
        val f = named(VECTOR_2_REFERENCE)
        val d = named(VECTOR_3_REFERENCE)
        val c = named(REFERENCE_C)
        val b = named(VECTOR_4_REFERENCE)
        val a = named(VECTOR_5_REFERENCE)
        val i = named(REFERENCE_I)
        val j = named(REFERENCE_J)

        val first = import(harbor, firstExport())
        assertEquals(6, first.delivered)
        assertEquals(listOf(g, f, d, c, b, a), identities())
        assertEquals(identities().map { it.importIdentifier }, first.takenIn.map { it.importIdentifier })

        val later = import(harbor, laterExport())
        assertEquals(6, later.delivered)
        assertEquals(listOf(j, i).map { it.importIdentifier }, later.takenIn.map { it.importIdentifier })
        assertEquals(listOf(g, f, d, c, b, a, j, i), identities())

        val again = import(harbor, firstExport())
        assertEquals(6, again.delivered)
        assertEquals(emptyList<String>(), again.takenIn.map { it.importIdentifier })
        assertEquals(listOf(g, f, d, c, b, a, j, i), identities())
        assertEquals(8, identities().map { it.importIdentifier }.toSet().size)
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }

    private companion object {
        /** The references of the five test vectors of ADR-001 §4.3.1. */
        const val VECTOR_1_REFERENCE = "3a3874d3bb5b7a579326adf332596a3bc66bb62230da6e56aefe73dd8fa043d8"
        const val VECTOR_2_REFERENCE = "7d31efe6e3271c3897da5bafc623363089b84ebeea68f23190987a30e502916c"
        const val VECTOR_3_REFERENCE = "e42b53158002d2fab3cfa8d965b197087ac51587bf48f6c4920bf06f492b3e2d"
        const val VECTOR_4_REFERENCE = "1cb30abe4c821e4e021a4690d89335cf4c781d2e6946fa8b1437d92c908cf507"
        const val VECTOR_5_REFERENCE = "5545f3fc35921c80ae682cfcab51ef97d72a7149fad2812d0f1436a94e9cec67"

        /** SHA-256 of `17:hsa-csv-reference1:110:2026-10-155:2000039:PARTIC CONTR CURRENT PARTICIPANT CUR YR0:`. */
        const val REFERENCE_C = "c08f364598ab7b2fbe09bbe179d7ca50abe5628dbf82741d15531e9f44cfb7cd"

        /** SHA-256 of `17:hsa-csv-reference1:110:2026-12-013:80015:INTEREST EARNED7:(EXMPX)`. */
        const val REFERENCE_I = "4d3ed70b49cf3bf5246945394830798c126b3a790a903a5ff6e9e911a46f8bce"

        /** SHA-256 of `17:hsa-csv-reference1:110:2026-12-036:-4000027:DISTRIBUTION EXAMPLE CLINIC12:Office visit`. */
        const val REFERENCE_J = "cb1471adca6e40dfe0619109267d0ff1199d55701f5d83fb690d84a64264d413"

        const val HEADER =
            "Run Date,Action,Symbol,Description,Type,Price (\$),Quantity,Commission (\$),Fees (\$),Accrued Interest (\$),Amount (\$),Cash Balance (\$),Settlement Date"

        // One invented history, oldest first: A, B, C, D, E (the reinvestment, left out), F, G, then I and J.
        const val A = "09/30/2026,INTEREST EARNED (Cash),EXMPX,No Description,Cash,\"\",0,\"\",\"\",\"\",1.2,1850.5,\"\""
        const val B = "10/02/2026,DISTRIBUTION EXAMPLE CLINIC (Cash),\"\",Office visit,Cash,\"\",0,\"\",\"\",\"\",\"-25\",1825.5,\"\""
        const val C = "10/15/2026,PARTIC CONTR CURRENT PARTICIPANT CUR YR (Cash),\"\",No Description,Cash,\"\",0,\"\",\"\",\"\",20,1845.5,\"\""
        const val D = "10/30/2026,DIVIDEND RECEIVED EXAMPLE GOVERNMENT CASH RESERVES (EXMPX) (Cash),EXMPX,EXAMPLE GOVERNMENT CASH RESERVES,Cash,\"\",0,\"\",\"\",\"\",4.5,1850,\"\""
        const val E = "10/30/2026,REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX) (Cash),EXMPX,EXAMPLE GOVERNMENT CASH RESERVES,Cash,1,4.5,\"\",\"\",\"\",\"-4.5\",1850,\"\""
        const val F = "11/23/2026,PARTIC CONTR CURRENT PARTICIPANT CUR YR (Cash),\"\",No Description,Cash,\"\",0,\"\",\"\",\"\",150,2000,\"\""
        const val G = "11/25/2026,DEBIT CARD PURCHASE HARBOR PHARMACY (Cash),\"\",No Description,Cash,\"\",0,\"\",\"\",\"\",\"-12.34\",1987.66,\"\""
        const val I = "12/01/2026,INTEREST EARNED (Cash),EXMPX,No Description,Cash,\"\",0,\"\",\"\",\"\",0.8,1988.46,\"\""
        const val J = "12/03/2026,DISTRIBUTION EXAMPLE CLINIC (Cash),\"\",Office visit,Cash,\"\",0,\"\",\"\",\"\",\"-40\",1948.46,\"\""

        /** A file in the layout of ADR-001 §4.3.1, with the byte order mark and LF line ends, newest line first. */
        fun export(lines: List<String>, downloaded: String): ByteArray =
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                (listOf("", "", HEADER) + lines + listOf("", "Invented legal text.", "Date downloaded $downloaded"))
                    .joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)

        fun firstExport(): ByteArray = export(listOf(G, F, E, D, C, B, A), "11/26/2026 9:15 am")

        fun laterExport(): ByteArray = export(listOf(J, I, G, F, E, D, C), "12/04/2026 7:02 pm")
    }
}
