package slussen.core.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import java.time.LocalDate

/**
 * The Import Identifier protocol of ADR-008, version 1, against its committed test vectors
 * (`import-identifier-v1.vectors`, §3): every vector is recomputed, so a change of the protocol fails the build (§6).
 */
@DisplayName("ADR-008: the Import Identifier protocol, version 1")
class ImportIdentifierProtocolTest {
    /** One line of the vectors file: the input and the Import Identifier it gives. */
    private data class Vector(val line: Int, val input: ImportIdentifierInput, val expected: String)

    private val vectors: List<Vector> by lazy {
        val text = checkNotNull(javaClass.getResource("/import-identifier-v1.vectors")) { "import-identifier-v1.vectors is missing" }
            .readText(Charsets.UTF_8)
        text.lines().withIndex()
            .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
            .map { (index, line) -> vectorOf(index + 1, line) }
    }

    private fun vectorOf(line: Int, text: String): Vector {
        val fields = text.split(" | ").map(::decoded)
        val input = when (fields.first()) {
            "R" -> ImportIdentifierInput.WithStableReference(fields[1]!!, fields[2]!!)
            "F" -> ImportIdentifierInput.WithoutStableReference(
                sourceKey = fields[1]!!,
                bookedDate = LocalDate.parse(fields[2]),
                amount = Amount(fields[3]!!.toLong()),
                payee = fields[4],
                memo = fields[5],
                occurrenceNumber = fields[6]!!.toInt(),
            )
            "U" -> ImportIdentifierInput.ReUpload(fields[1]!!, fields[2]!!.toInt())
            else -> error("line $line: unknown kind ${fields.first()}")
        }
        return Vector(line, input, fields.last()!!)
    }

    /** `<absent>` is null, `<empty>` the empty text, and `\u{XXXX}` one code point. */
    private fun decoded(field: String): String? = when (field) {
        "<absent>" -> null
        "<empty>" -> ""
        else -> Regex("""\\u\{([0-9A-Fa-f]{1,6})}""").replace(field) { String(Character.toChars(it.groupValues[1].toInt(16))) }
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"], requirements = ["NFR-020"])
    @DisplayName("ADR-008 §3: every committed vector gives its Import Identifier, the vectors of §3 and those the first slice adds included")
    fun everyVectorGivesItsImportIdentifier() {
        assertEquals(13, vectors.size, "the 9 vectors of ADR-008 §3 and the 4 the first slice adds")
        vectors.forEach { vector ->
            assertEquals(vector.expected, ImportIdentifierProtocol.importIdentifier(vector.input), "vector of line ${vector.line}")
        }
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"])
    @DisplayName("ADR-008 §3: the canonical input of the first vector, for an implementer to check the encoding")
    fun canonicalInputOfTheFirstVector() {
        val input = ImportIdentifierInput.WithStableReference("example-provider", "TX-0001")

        assertEquals(
            "25:slussen-import-identifier1:11:R16:example-provider7:TX-0001",
            String(ImportIdentifierProtocol.canonicalInput(input), Charsets.UTF_8),
        )
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"], requirements = ["C-009"])
    @DisplayName("ADR-008 §1: an Import Identifier is SLS1- and 26 characters of lower-case base32, 31 characters in all, within the 36 YNAB accepts (C-009)")
    fun formatOfVersion1() {
        vectors.forEach { vector ->
            val identifier = ImportIdentifierProtocol.importIdentifier(vector.input)
            assertTrue(identifier.matches(Regex("^SLS1-[a-z2-7]{26}$")), identifier)
            assertEquals(31, identifier.length)
        }
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"])
    @DisplayName("ADR-008 §2: the amount is the signed whole number of thousandths, and the field length counts UTF-8 bytes")
    fun amountAndFieldLengths() {
        val input = ImportIdentifierInput.WithoutStableReference(
            "example-format", LocalDate.of(2026, 3, 7), Amount(-9990), "Example \uD83E\uDD6F Bagels", null, 1,
        )

        assertEquals(
            "25:slussen-import-identifier1:11:F14:example-format10:2026-03-075:-999019:Example \uD83E\uDD6F Bagels0:1:1",
            String(ImportIdentifierProtocol.canonicalInput(input), Charsets.UTF_8),
        )
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"])
    @DisplayName("ADR-008 §2: payee and memo are compared in Normalization Form C and an absent one equals an empty one; nothing else is changed, not case, not spaces")
    fun normalisedTextOfPayeeAndMemo() {
        assertEquals(ImportIdentifierProtocol.normalisedText("Caf\u00e9"), ImportIdentifierProtocol.normalisedText("Cafe\u0301"))
        assertEquals("", ImportIdentifierProtocol.normalisedText(null))
        assertEquals(" Corner  grocer ", ImportIdentifierProtocol.normalisedText(" Corner  grocer "))
        assertNotEquals(ImportIdentifierProtocol.normalisedText("Corner Grocer"), ImportIdentifierProtocol.normalisedText("corner grocer"))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"])
    @DisplayName("ADR-008 §2 (B-08): a Stable Reference that is not well-formed Unicode cannot be encoded losslessly; every well-formed one can")
    fun stableReferenceMustBeWellFormedUnicode() {
        assertFalse(ImportIdentifierProtocol.isEncodable("TX-\uD83E"))
        assertFalse(ImportIdentifierProtocol.isEncodable("\uDD6F-TX"))
        assertTrue(ImportIdentifierProtocol.isEncodable("TX-\uD83E\uDD6F"))
        assertTrue(ImportIdentifierProtocol.isEncodable("  TX-0002 "))
    }

    @Test
    @UseCase(id = "UC-003", businessRules = ["BR-002"], requirements = ["NFR-020"])
    @DisplayName("ADR-008 §5: a test-only digest forces two different inputs onto one Import Identifier, so the collision handling can be tested")
    fun testOnlyDigestForcesACollision() {
        val zeros: (ByteArray) -> ByteArray = { ByteArray(32) }

        val first = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-provider", "TX-1"), zeros)
        val second = ImportIdentifierProtocol.importIdentifier(ImportIdentifierInput.WithStableReference("example-provider", "TX-2"), zeros)

        assertEquals("SLS1-aaaaaaaaaaaaaaaaaaaaaaaaaa", first)
        assertEquals(first, second)
    }
}
