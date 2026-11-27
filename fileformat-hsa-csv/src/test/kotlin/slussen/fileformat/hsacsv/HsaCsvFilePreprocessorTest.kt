package slussen.fileformat.hsacsv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.fileformat.ReferenceKind
import slussen.pluginapi.fileformat.StandardImportTransaction
import slussen.pluginapi.testfixtures.FilePreprocessorContractTest
import slussen.pluginapi.testfixtures.RejectedFileScenario
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The HSA CSV export's File Pre-processor (ADR-001 §4.3, §4.3.1) against the contract tests of ADR-001 §4.4 and every
 * rule of the format, with invented files in the exact layout §4.3.1 states ([hsaFile]). The reference recipe is checked
 * against its committed test vectors (`hsa-csv-reference-v1.vectors`), which are never changed; the other expected
 * references are SHA-256 digests of canonical inputs written out by hand, never taken from the code.
 */
class HsaCsvFilePreprocessorTest : FilePreprocessorContractTest() {
    override fun filePreprocessor(log: SlussenLog): FilePreprocessor = HsaCsvFilePreprocessor(log)

    override fun convertedFile(): ByteArray = hsaFile(VECTOR_1, VECTOR_2, REINVESTMENT, VECTOR_3)

    override fun fileWithOneUnreadableLine(): RejectedFileScenario = RejectedFileScenario(
        hsaFile(VECTOR_1, VECTOR_2.replace(",150,", ",\"1,50\",")),
        listOf("11/23/2026", "11/25/2026", "PARTIC CONTR", "PARTICIPANT", "1,50", "2000", "No Description", "HARBOR PHARMACY", "-12.34", "1987.66"),
    )

    private val usd = CurrencyCode("USD")

    private fun convert(file: ByteArray): ConversionResult = HsaCsvFilePreprocessor(SilentLog).convert(file)

    private fun converted(file: ByteArray): List<StandardImportTransaction> {
        val result = convert(file)
        assertInstanceOf(ConversionResult.Converted::class.java, result, (result as? ConversionResult.Rejected)?.reason)
        return (result as ConversionResult.Converted).file.transactions
    }

    private fun rejected(file: ByteArray): String = assertInstanceOf(ConversionResult.Rejected::class.java, convert(file)).reason

    /** The file is rejected with a reason that names [line] by its number first and holds none of [values] (§4.3, NFR-007). */
    private fun assertRejectedAt(line: Int, file: ByteArray, vararg values: String) {
        val reason = rejected(file)
        assertTrue(reason.startsWith("Line $line:") || reason.startsWith("Line $line,"), "reason: $reason")
        values.forEach { assertFalse(reason.contains(it), "the reason holds a value of the file: $reason") }
        assertTrue(reason.length <= 200, "the reason is longer than 200 characters: $reason")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    @UseCase(id = "UC-012", scenario = "A3: Bank Export Format Missing", businessRules = ["BR-005"])
    @DisplayName("A3: the File Pre-processor of this build declares the name HSA CSV export and the source key hsa-csv (ADR-001 §4.3, O-P2-42)")
    fun declaresItsProfile() {
        val profile = HsaCsvFilePreprocessor(SilentLog).profile
        assertEquals("HSA CSV export", profile.name)
        assertEquals("hsa-csv", profile.sourceKey)
    }

    /** One vector of `hsa-csv-reference-v1.vectors`: a transaction line, its canonical input and its reference. */
    private data class Vector(val line: String, val input: String, val reference: String)

    private val vectors: List<Vector> by lazy {
        val text = checkNotNull(javaClass.getResource("/hsa-csv-reference-v1.vectors")) { "hsa-csv-reference-v1.vectors is missing" }
            .readBytes().toString(Charsets.UTF_8)
        val parts = text.split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t', limit = 2) }
        parts.chunked(3).map { (line, input, reference) ->
            check(line[0] == "line" && input[0] == "input" && reference[0] == "reference") { "malformed vectors file" }
            Vector(line[1], input[1], reference[1])
        }
    }

    // ---- The reference recipe, version 1 --------------------------------------------------------------------------

    @Nested
    @DisplayName("The reference recipe, version 1 (final)")
    inner class Recipe {
        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-020"])
        @DisplayName("BR-002: the five committed test vectors of ADR-001 §4.3.1 come out byte for byte: each line alone in a file gives the vector's canonical input, whose SHA-256 digest is the vector's reference, Derived")
        fun theTestVectorsComeOut() {
            assertEquals(listOf(VECTOR_1, VECTOR_2, VECTOR_3, VECTOR_4, VECTOR_5), vectors.map { it.line })
            assertEquals(
                listOf(
                    "3a3874d3bb5b7a579326adf332596a3bc66bb62230da6e56aefe73dd8fa043d8",
                    "7d31efe6e3271c3897da5bafc623363089b84ebeea68f23190987a30e502916c",
                    "e42b53158002d2fab3cfa8d965b197087ac51587bf48f6c4920bf06f492b3e2d",
                    "1cb30abe4c821e4e021a4690d89335cf4c781d2e6946fa8b1437d92c908cf507",
                    "5545f3fc35921c80ae682cfcab51ef97d72a7149fad2812d0f1436a94e9cec67",
                ),
                vectors.map { it.reference },
            )
            vectors.forEach { vector ->
                assertEquals(vector.reference, sha256(vector.input), "the committed input does not give the committed digest")
                val transaction = converted(hsaFile(vector.line)).single()
                val canonical = HsaCsvReference.canonicalInput(transaction.bookedDate, transaction.amount, transaction.payee.orEmpty(), transaction.memo)
                assertEquals(vector.input.toByteArray(Charsets.UTF_8).toList(), canonical.toList())
                assertEquals(vector.reference, transaction.reference)
                assertEquals(ReferenceKind.Derived, transaction.referenceKind)
            }
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-007"], requirements = ["C-010"])
        @DisplayName("BR-002, BR-007: the file of the first three vectors with the reinvestment between the second and the third gives the three transactions with the vectors' references; the reinvestment is left out")
        fun theFileOfTheVectors() {
            assertEquals(
                listOf(
                    StandardImportTransaction(vectors[0].reference, ReferenceKind.Derived, LocalDate.of(2026, 11, 25), Amount(-12_340), usd, "DEBIT CARD PURCHASE HARBOR PHARMACY", null, booked = true),
                    StandardImportTransaction(vectors[1].reference, ReferenceKind.Derived, LocalDate.of(2026, 11, 23), Amount(150_000), usd, "PARTIC CONTR CURRENT PARTICIPANT CUR YR", null, booked = true),
                    StandardImportTransaction(
                        vectors[2].reference, ReferenceKind.Derived, LocalDate.of(2026, 10, 30), Amount(4_500), usd,
                        "DIVIDEND RECEIVED EXAMPLE GOVERNMENT CASH RESERVES (EXMPX)", "EXAMPLE GOVERNMENT CASH RESERVES (EXMPX)", booked = true,
                    ),
                ),
                converted(hsaFile(VECTOR_1, VECTOR_2, REINVESTMENT, VECTOR_3)),
            )
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-020"])
        @DisplayName("BR-002: payee and memo are put into Form C for the reference only: a combining accent and the precomposed letter give one reference, each line keeps its text as the file has it, and nothing else is changed (case and spaces count)")
        fun formCAndNothingElse() {
            val combining = converted(hsaFile(VECTOR_1.replace("HARBOR PHARMACY", "CAFÉ EXEMPEL").replace("No Description", "Café"))).single()
            val precomposed = converted(hsaFile(VECTOR_1.replace("HARBOR PHARMACY", "CAFÉ EXEMPEL").replace("No Description", "Café"))).single()
            assertEquals("DEBIT CARD PURCHASE CAFÉ EXEMPEL", combining.payee)
            assertEquals("Café", combining.memo)
            // SHA-256 of 17:hsa-csv-reference1:110:2026-11-256:-1234033:DEBIT CARD PURCHASE CAFÉ EXEMPEL5:Café (Form C, byte lengths).
            assertEquals("ce59c92cfcd6e6ecbe05b10cfb045c4cc173801b784ec121d77c783ce8b1f507", precomposed.reference)
            assertEquals(precomposed.reference, combining.reference)
            val lowerCase = converted(hsaFile(VECTOR_1.replace("HARBOR PHARMACY", "Harbor Pharmacy"))).single()
            val spaced = converted(hsaFile(VECTOR_1.replace("HARBOR PHARMACY", "HARBOR  PHARMACY"))).single()
            val trailingSpace = converted(hsaFile(VECTOR_1.replace("No Description", "No Description "))).single()
            assertEquals(4, setOf(vectors[0].reference, lowerCase.reference, spaced.reference, trailingSpace.reference).size)
            assertEquals("Harbor Pharmacy", lowerCase.payee?.removePrefix("DEBIT CARD PURCHASE "))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["NFR-020"])
        @DisplayName("BR-002: the cash balance, the line's position, the columns not read and the time of the download are no part of the reference; the run date, action, symbol, description and amount each are")
        fun whatMakesTheReference() {
            val reference = vectors[0].reference
            val otherBalanceAndUnreadColumns = "11/25/2026,DEBIT CARD PURCHASE HARBOR PHARMACY (Cash),\"\",No Description,Cash,9.99,3,1,2,4,\"-12.34\",77.7,11/27/2026"
            assertEquals(reference, converted(hsaFile(otherBalanceAndUnreadColumns)).single().reference)
            assertEquals(reference, converted(hsaFile(VECTOR_1, trailer = listOf("Other legal text.", "Date downloaded 12/24/2026 11:59 pm"))).single().reference)
            assertEquals(reference, converted(hsaFile(line("12/01/2026", "0.8", "1988.46"), line("11/30/2026", "0", "1987.66"), VECTOR_1))[2].reference)
            listOf(
                VECTOR_1.replace("11/25/2026", "11/24/2026"),
                VECTOR_1.replace("HARBOR PHARMACY", "HARBOR PHARMACIES"),
                VECTOR_1.replace(",\"\",No Description,", ",EXMPX,No Description,"),
                VECTOR_1.replace(",\"\",No Description,", ",\"\",Prescription,"),
                VECTOR_1.replace("\"-12.34\"", "\"-12.35\""),
            ).forEach { assertNotEquals(reference, converted(hsaFile(it)).single().reference, it) }
        }
    }

    // ---- The Standard Import Format of a line ---------------------------------------------------------------------

    @Nested
    @DisplayName("The Standard Import Format of a line")
    inner class LineFields {
        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["C-010"])
        @DisplayName("BR-007: the memo in its four forms: description and symbol, the description alone, the symbol alone in parentheses, neither (absent); exactly No Description counts as empty")
        fun theMemo() {
            fun memoOf(symbol: String, description: String) =
                converted(hsaFile("10/02/2026,DISTRIBUTION EXAMPLE CLINIC (Cash),$symbol,$description,Cash,\"\",0,\"\",\"\",\"\",\"-25\",1825.5,\"\"")).single().memo
            assertEquals("Office visit (EXMPX)", memoOf("EXMPX", "Office visit"))
            assertEquals("Office visit", memoOf("\"\"", "Office visit"))
            assertEquals("(EXMPX)", memoOf("EXMPX", "No Description"))
            assertEquals("(EXMPX)", memoOf("EXMPX", "\"\""))
            assertNull(memoOf("\"\"", "No Description"))
            assertNull(memoOf("", ""))
            assertEquals("no description", memoOf("", "no description"))
            assertEquals("No Description ", memoOf("", "No Description "))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["C-010"])
        @DisplayName("BR-007: the payee is the Action without its ending (Cash); the amount is negative for an outflow, a quoted one too; every line is booked, in USD")
        fun payeeAmountCurrencyBooked() {
            val transactions = converted(hsaFile(VECTOR_1, VECTOR_2))
            assertEquals(listOf("DEBIT CARD PURCHASE HARBOR PHARMACY", "PARTIC CONTR CURRENT PARTICIPANT CUR YR"), transactions.map { it.payee })
            assertEquals(listOf(Amount(-12_340), Amount(150_000)), transactions.map { it.amount })
            assertTrue(transactions.all { it.booked && it.currency == usd && it.referenceKind == ReferenceKind.Derived })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["C-010"])
        @DisplayName("BR-007: an amount and a cash balance may have no point, one or two decimals, leading zeros and a minus sign; -0 is an amount of 0")
        fun numberForms() {
            val transactions = converted(
                hsaFile(
                    line("11/25/2026", "\"-0\"", "100"),
                    line("11/24/2026", "-0.05", "100"),
                    line("11/23/2026", "007.5", "100.05"),
                    line("11/22/2026", "\"92.55\"", "092.55"),
                ),
            )
            assertEquals(listOf(Amount(0), Amount(-50), Amount(7_500), Amount(92_550)), transactions.map { it.amount })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["C-010"])
        @DisplayName("BR-007: a field in double quotes may hold commas and a doubled double quote, the enclosing quotes are not part of the value, and \"\" is an empty value")
        fun quotedFields() {
            val transaction = converted(
                hsaFile("\"10/02/2026\",\"DISTRIBUTION \"\"EXAMPLE\"\" CLINIC, NORTH (Cash)\",\"\",\"Office visit, follow-up\",\"Cash\",\"\",0,\"\",\"\",\"\",\"-25\",\"1825.5\",\"\""),
            ).single()
            assertEquals(LocalDate.of(2026, 10, 2), transaction.bookedDate)
            assertEquals("DISTRIBUTION \"EXAMPLE\" CLINIC, NORTH", transaction.payee)
            assertEquals("Office visit, follow-up", transaction.memo)
            // SHA-256 of 17:hsa-csv-reference1:110:2026-10-026:-2500036:DISTRIBUTION "EXAMPLE" CLINIC, NORTH23:Office visit, follow-up
            assertEquals("4d06a394c263badb02007103642a941fbf94af981d20c853662b1e056e318fae", transaction.reference)
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A4: Transactions That Cannot Be Told Apart", businessRules = ["BR-002"])
        @DisplayName("A4: two identical lines of a file get the same reference (their cash balances differ, but are no part of it); both are given to the core, nothing is merged")
        fun identicalLinesShareAReference() {
            val transactions = converted(hsaFile(VECTOR_1, VECTOR_1.replace("1987.66", "2000"), line("11/24/2026", "12.34", "2012.34")))
            assertEquals(3, transactions.size)
            assertEquals(listOf(vectors[0].reference, vectors[0].reference), transactions.take(2).map { it.reference })
        }
    }

    // ---- The layout -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Layout")
    inner class Layout {
        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["C-010"])
        @DisplayName("BR-007: the file with and without the byte order mark, and with LF or CRLF line ends, gives the same transactions")
        fun byteOrderMarkAndLineEnds() {
            val lines = arrayOf(VECTOR_1, VECTOR_2, REINVESTMENT, VECTOR_3)
            val expected = converted(hsaFile(*lines))
            assertEquals(vectors.take(3).map { it.reference }, expected.map { it.reference })
            assertEquals(expected, converted(hsaFile(*lines, bom = false)))
            assertEquals(expected, converted(hsaFile(*lines, eol = "\r\n")))
            assertEquals(expected, converted(hsaFile(*lines, eol = "\r\n", bom = false)))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"], requirements = ["C-010"])
        @DisplayName("A2, BR-001: a CR not followed by LF, and LF and CRLF in one file, reject the file at the line where it happens")
        fun loneCarriageReturnOrMixedLineEnds() {
            assertRejectedAt(4, bytesOf(hsaText(VECTOR_1, VECTOR_2).replaceFirst("$VECTOR_1\n", "$VECTOR_1\r")), "HARBOR", "PARTIC")
            assertRejectedAt(4, bytesOf(hsaText(VECTOR_1, VECTOR_2).replaceFirst("$VECTOR_1\n", "$VECTOR_1\r\n")), "HARBOR")
            assertRejectedAt(5, bytesOf(hsaText(VECTOR_1, VECTOR_2, eol = "\r\n").replaceFirst("$VECTOR_2\r\n", "$VECTOR_2\n")), "PARTIC")
            assertRejectedAt(6, hsaFile(VECTOR_1, trailer = listOf("Legal\rtext.", "Date downloaded 11/26/2026 9:15 am")), "Legal")
            assertRejectedAt(2, bytesOf("\r\n\n$HEADER\n$VECTOR_1\n\nDate downloaded 11/26/2026 9:15 am\n"))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: a file that is not UTF-8 is rejected at the line that holds the bytes, also one with the byte order mark of another encoding")
        fun notUtf8() {
            val text = hsaText(VECTOR_1, VECTOR_2.replace("CUR YR", "CUR~YR"))
            val latin1 = bytesOf(text).let { bytes -> bytes.copyOf().also { it[bytes.indexOf('~'.code.toByte())] = 0xC9.toByte() } }
            assertRejectedAt(5, latin1, "PARTIC")
            assertRejectedAt(1, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + hsaText(VECTOR_1).toByteArray(Charsets.UTF_16LE))
            assertRejectedAt(1, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytesOf(hsaText(VECTOR_1)))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: lines 1 and 2 must be empty and line 3 the header exactly; a file that ends sooner is rejected at the first line missing")
        fun headerAndEmptyLines() {
            assertRejectedAt(1, bytesOf(" " + hsaText(VECTOR_1)))
            assertRejectedAt(2, bytesOf(hsaText(VECTOR_1).replaceFirst("\n\n", "\nx\n")))
            assertRejectedAt(2, bytesOf(hsaText(VECTOR_1).removePrefix("\n")))
            assertRejectedAt(3, bytesOf(hsaText(VECTOR_1).replace("Settlement Date", "Settlement date")))
            assertRejectedAt(3, bytesOf(hsaText(VECTOR_1).replace(",Settlement Date", "")))
            assertRejectedAt(3, bytesOf(hsaText(VECTOR_1).replace("Run Date,", "\"Run Date\",")))
            assertRejectedAt(3, bytesOf(hsaText(VECTOR_1).replace("Settlement Date", "Settlement Date ")))
            assertRejectedAt(1, bytesOf(""))
            assertRejectedAt(1, bytesOf("", bom = false))
            assertRejectedAt(3, bytesOf("\n\n"))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2, BR-001: a file cut short is rejected: no empty line after the transaction lines, no text after it, or a last line that is not empty and does not begin with Date downloaded")
        fun cutShort() {
            assertRejectedAt(5, bytesOf("\n\n$HEADER\n$VECTOR_1\n$VECTOR_2\n"), "PARTIC")
            assertRejectedAt(6, bytesOf("\n\n$HEADER\n$VECTOR_1\n$VECTOR_2\n\n"))
            assertRejectedAt(5, bytesOf("\n\n$HEADER\n$VECTOR_1\n\n\n\n"))
            assertRejectedAt(7, bytesOf("\n\n$HEADER\n$VECTOR_1\n\nLegal text.\nMore legal text.\n"), "Legal")
            assertRejectedAt(7, bytesOf("\n\n$HEADER\n$VECTOR_1\n\nDate downloaded 11/26/2026 9:15 am\nLegal text.\n\n\n"), "Legal")
            assertRejectedAt(6, bytesOf("\n\n$HEADER\n$VECTOR_1\n\ndate downloaded 11/26/2026 9:15 am\n"), "11/26")
            assertRejectedAt(6, bytesOf("\n\n$HEADER\n$VECTOR_1\n\n Date downloaded 11/26/2026 9:15 am\n"), "11/26")
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"])
        @DisplayName("BR-007: the text after the empty line is not read; only its presence is required, and empty lines may follow its last line")
        fun theTextAfterIsNotRead() {
            assertEquals(1, converted(hsaFile(VECTOR_1, trailer = listOf("\"An unclosed quote, and anything,,,", "", "Date downloaded"))).size)
            assertEquals(1, converted(bytesOf("\n\n$HEADER\n$VECTOR_1\n\nDate downloaded 11/26/2026 9:15 am\n\n\n")).size)
            assertEquals(1, converted(bytesOf("\n\n$HEADER\n$VECTOR_1\n\nDate downloaded 11/26/2026 9:15 am\n")).size)
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: a last line without a line end is rejected: §4.3.1 says the lines end with LF or CRLF and leaves a file that stops without one open")
        fun theLastLineEnds() {
            assertRejectedAt(6, bytesOf("\n\n$HEADER\n$VECTOR_1\n\nDate downloaded 11/26/2026 9:15 am"), "11/26")
            assertRejectedAt(6, bytesOf("\r\n\r\n$HEADER\r\n$VECTOR_1\r\n\r\nDate downloaded 11/26/2026 9:15 am"), "11/26")
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A3: File Holds No Importable Transaction", businessRules = ["BR-001"])
        @DisplayName("A3: an empty line straight after the header is a file with no transaction line: converted, with none (the core rejects it as A3)")
        fun noTransactionLine() {
            assertEquals(emptyList<StandardImportTransaction>(), converted(hsaFile()))
            assertEquals(emptyList<StandardImportTransaction>(), converted(hsaFile(eol = "\r\n", bom = false)))
        }
    }

    // ---- A transaction line ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("A transaction line")
    inner class TransactionLine {
        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: a quote that is not closed, a quote inside a field without enclosing quotes, text after a closing quote, and a line that does not give exactly the header's 13 fields reject the file")
        fun csvRules() {
            assertRejectedAt(5, hsaFile(VECTOR_1, VECTOR_2.replace(",\"\",No Description,", ",\"EXMPX,No Description,")), "PARTIC", "EXMPX")
            assertRejectedAt(5, hsaFile(VECTOR_1, VECTOR_2.replace(",No Description,", ",No \"Description\",")), "PARTIC", "No ")
            assertRejectedAt(5, hsaFile(VECTOR_1, VECTOR_2.replace(",\"\",No Description,", ",\"\"x,No Description,")), "PARTIC")
            assertRejectedAt(5, hsaFile(VECTOR_1, VECTOR_2.replace(",\"\",No Description,", ", \"\",No Description,")), "PARTIC")
            assertRejectedAt(5, hsaFile(VECTOR_1, VECTOR_2.removeSuffix(",\"\"")), "PARTIC")
            assertRejectedAt(5, hsaFile(VECTOR_1, "$VECTOR_2,"), "PARTIC")
            assertRejectedAt(4, hsaFile("$VECTOR_1,\"\""), "HARBOR")
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: a Run Date not of the form MM/DD/YYYY, not a real calendar date, or later than the line above it rejects the file; the same date as the line above is accepted")
        fun runDate() {
            listOf("1/25/2026", "11/25/26", "2026-11-25", "11/25/2026 ", "\"\"", "11.25.2026", "02/30/2026", "13/01/2026", "00/10/2026", "11/00/2026", "11/25/0000", "１1/25/2026")
                .forEach { date ->
                    val reason = rejected(hsaFile(VECTOR_1.replaceFirst("11/25/2026", date)))
                    assertTrue(reason.startsWith("Line 4, column 1 "), reason)
                    assertFalse(reason.contains("HARBOR") || reason.contains("/2026") || reason.contains("/26"), reason)
                }
            assertRejectedAt(5, hsaFile(VECTOR_2, VECTOR_1.replace("1987.66", "1850")), "HARBOR", "11/2")
            assertEquals(2, converted(hsaFile(line("11/25/2026", "12.34", "2000"), VECTOR_1)).size)
            assertEquals(LocalDate.of(2028, 2, 29), converted(hsaFile(VECTOR_1.replace("11/25/2026", "02/29/2028"))).single().bookedDate)
            assertRejectedAt(4, hsaFile(VECTOR_1.replace("11/25/2026", "02/29/2027")))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: an Action that does not end with (Cash), or has nothing before it, and a Type other than Cash (buying another fund) reject the file")
        fun actionAndType() {
            assertRejectedAt(4, hsaFile(VECTOR_1.replace(" (Cash),", ",")), "HARBOR")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace(" (Cash),", " (cash),")), "HARBOR")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace(" (Cash),", " (Cash) ,")), "HARBOR")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace("DEBIT CARD PURCHASE HARBOR PHARMACY (Cash)", "(Cash)")))
            assertRejectedAt(4, hsaFile(VECTOR_1.replace("DEBIT CARD PURCHASE HARBOR PHARMACY (Cash)", " (Cash)")))
            assertEquals(" ", converted(hsaFile(VECTOR_1.replace("DEBIT CARD PURCHASE HARBOR PHARMACY (Cash)", "  (Cash)"))).single().payee)
            assertRejectedAt(4, hsaFile("11/25/2026,YOU BOUGHT EXAMPLE INDEX FUND (EXIDX) (Cash),EXIDX,EXAMPLE INDEX FUND,Shares,10,1.5,\"\",\"\",\"\",\"-15\",1985,\"\""), "EXIDX", "INDEX", "Shares")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace(",Cash,", ",cash,")), "HARBOR")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace(",Cash,", ",\"\",")), "HARBOR")
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: an Amount or a Cash Balance that is not an optional minus, digits and at most two decimals rejects the file, naming the column and not the value")
        fun numbers() {
            listOf("+12.34", "\"-1,234\"", "12 34", "\"- 12.34\"", "12.", ".34", "12.345", "\"\"", "", "12e3", "\"(12.34)\"", "\"\$12.34\"", "１2", "99999999999999999999", "-9223372036854775.81")
                .forEach { amount ->
                    val reason = rejected(hsaFile(VECTOR_1.replace("\"-12.34\"", amount)))
                    assertTrue(reason.startsWith("Line 4, column 11 "), reason)
                    assertFalse(reason.contains("HARBOR") || reason.contains("12") || reason.contains("1987"), reason)
                }
            assertTrue(rejected(hsaFile(VECTOR_1.replace("1987.66", "\"1,987.66\""))).startsWith("Line 4, column 12 "))
            assertTrue(rejected(hsaFile(VECTOR_1.replace("1987.66", "\"\""))).startsWith("Line 4, column 12 "))
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: every reason names the line by its number and holds no value of the file: no amount, date, payee, memo or text of a line")
        fun reasonsHoldNoValue() {
            val secret = "11/26/2026,SECRETPAYEE (Cash),SECRETSYMBOL,SECRETMEMO,Cash,\"\",0,\"\",\"\",\"\",\"-777.77\",1209.89,\"\""
            assertEquals(2, converted(hsaFile(secret, VECTOR_1)).size)
            listOf(
                4 to secret.replace(",Cash,", ",Shares,"),
                4 to secret.replace("-777.77", "-777.7a"),
                4 to secret.replace("SECRETMEMO", "SECRETMEMO\""),
                4 to secret.replace("1209.89", "1210.23"),
                5 to secret.replace("11/26/2026", "11/24/2026"),
                4 to secret.replace("(Cash)", "(Shares)"),
            ).forEach { (line, changed) -> assertRejectedAt(line, hsaFile(changed, VECTOR_1), "SECRET", "777", "1209", "1210", "11/2", "HARBOR", "1987", "Shares") }
        }
    }

    // ---- The cash balance and the reinvestment --------------------------------------------------------------------

    @Nested
    @DisplayName("The cash balance and the reinvestment")
    inner class CashBalance {
        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"], requirements = ["C-010"])
        @DisplayName("BR-007: a line whose cash balance equals the line's below it, with an amount other than 0 and an Action that begins with REINVESTMENT, is left out; the dividend below it is kept")
        fun theReinvestmentIsLeftOut() {
            assertEquals(listOf(vectors[1].reference, vectors[2].reference), converted(hsaFile(VECTOR_2, REINVESTMENT, VECTOR_3)).map { it.reference })
        }

        @Test
        @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-007"])
        @DisplayName("BR-007: a difference of 0 with an amount of 0 is no reinvestment, also with REINVESTMENT: the line is a transaction of 0, given to the core, which leaves it out; a REINVESTMENT line that moved the cash balance is a transaction")
        fun aDifferenceOfZeroThatIsNoReinvestment() {
            val transactions = converted(
                hsaFile(
                    line("12/01/2026", "0", "1850", action = "REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX)"),
                    line("11/30/2026", "\"-0.00\"", "1850", action = "FEE CHARGED"),
                    line("11/29/2026", "\"-4.5\"", "1850", action = "REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX)"),
                    VECTOR_3.replace("10/30/2026", "11/28/2026").replace("1850", "1854.5"),
                    line("11/27/2026", "0", "1850", action = "FEE CHARGED"),
                ),
            )
            assertEquals(listOf(Amount(0), Amount(0), Amount(-4_500), Amount(4_500), Amount(0)), transactions.map { it.amount })
            assertEquals("REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX)", transactions[2].payee)
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2, BR-001: a difference of 0 with an amount other than 0 whose Action does not begin with REINVESTMENT, and any other break of the balance chain, reject the file at that line")
        fun anyOtherBreakRejects() {
            assertRejectedAt(5, hsaFile(VECTOR_2, REINVESTMENT.replace("REINVESTMENT ", "REINVESTMENT-"), VECTOR_3), "EXMPX", "4.5", "1850")
            assertRejectedAt(5, hsaFile(VECTOR_2, REINVESTMENT.replace("REINVESTMENT ", "reinvestment "), VECTOR_3), "EXMPX")
            assertRejectedAt(5, hsaFile(VECTOR_2, REINVESTMENT.replace("REINVESTMENT ", " REINVESTMENT "), VECTOR_3), "EXMPX")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace("1987.66", "1987.67"), VECTOR_2), "1987", "12.34")
            assertRejectedAt(4, hsaFile(VECTOR_1.replace("\"-12.34\"", "12.34"), VECTOR_2), "1987", "12.34")
            assertRejectedAt(4, hsaFile(REINVESTMENT.replace("1850", "1849"), VECTOR_3), "1849", "4.5")
            assertRejectedAt(4, hsaFile(VECTOR_1, VECTOR_3), "1987", "1850")
        }

        @Test
        @UseCase(id = "UC-002", scenario = "A2: File Cannot Be Converted Completely", businessRules = ["BR-001"])
        @DisplayName("A2: a REINVESTMENT line as the oldest line rejects the file, since whether it moved the cash balance cannot be told")
        fun aReinvestmentAsTheOldestLine() {
            assertRejectedAt(6, hsaFile(VECTOR_1, VECTOR_2, REINVESTMENT), "EXMPX")
            assertRejectedAt(4, hsaFile(REINVESTMENT), "EXMPX")
            assertRejectedAt(4, hsaFile(line("10/30/2026", "0", "1850", action = "REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX)")), "EXMPX")
        }
    }

    // ---- Two exports of one history -------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-002", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-003"], requirements = ["NFR-020"])
    @DisplayName("BR-002, BR-003: an overlapping later export of the same invented history (it lacks the oldest lines and adds newer ones, its balances continuing) gives the same references to the same transactions")
    fun overlappingExportsGiveTheSameReferences() {
        val first = converted(Histories.firstExport())
        val later = converted(Histories.laterExport())
        val (g, f, d) = vectors.take(3).map { it.reference }
        val (b, a) = vectors.drop(3).map { it.reference }
        assertEquals(listOf(g, f, d, Histories.REFERENCE_C, b, a), first.map { it.reference })
        assertEquals(listOf(Histories.REFERENCE_J, Histories.REFERENCE_I, g, f, d, Histories.REFERENCE_C), later.map { it.reference })
        assertEquals(first.take(4), later.drop(2))
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }
}
