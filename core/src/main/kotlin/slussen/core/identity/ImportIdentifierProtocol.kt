package slussen.core.identity

import slussen.pluginapi.value.Amount
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate

/** What an Import Identifier of protocol version 1 is derived from: one of the three kinds of ADR-008 §2. */
sealed interface ImportIdentifierInput {
    /** Kind `R`: a Bank Transaction with a Stable Reference, which is opaque (B-08). */
    data class WithStableReference(val sourceKey: String, val stableReference: String) : ImportIdentifierInput

    /** Kind `F`: a Bank Transaction without a Stable Reference, with its position among the identical ones. */
    data class WithoutStableReference(
        val sourceKey: String,
        val bookedDate: LocalDate,
        val amount: Amount,
        val payee: String?,
        val memo: String?,
        val occurrenceNumber: Int,
    ) : ImportIdentifierInput

    /** Kind `U`: a Re-upload, from the Bank Transaction's own Import Identifier and the occurrence (UC-004 BR-003). */
    data class ReUpload(val importIdentifier: String, val occurrence: Int) : ImportIdentifierInput
}

/**
 * The Import Identifier protocol of ADR-008: the one place that computes an Import Identifier (§4, Konsist K-15), its
 * canonical input (§2), and the equality that decides which transactions without a Stable Reference are identical
 * (§2, §5). Version 1 is frozen (§6): the committed test vectors (`import-identifier-v1.vectors`, §3) prove it.
 */
object ImportIdentifierProtocol {
    /** Slussen's marker, fixed for ever (ADR-008 §1). */
    private const val MARKER = "SLS"

    /** The protocol version this build produces (ADR-008 §1, §6). */
    private const val VERSION = "1"

    /** The domain of the canonical input (ADR-008 §2). */
    private const val DOMAIN = "slussen-import-identifier"

    /** RFC 4648 base32 in lower case (ADR-008 §1). */
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    /** The 26 characters of 5 bits each, the first 130 bits of the digest (ADR-008 §1, §7). */
    private const val CHARACTERS = 26

    /**
     * The canonical input of ADR-008 §2: `field("slussen-import-identifier") field("1") field(kind)` and the fields of
     * the kind, each field the decimal number of bytes of its UTF-8 form, a colon and those bytes.
     */
    fun canonicalInput(input: ImportIdentifierInput): ByteArray {
        val fields = when (input) {
            is ImportIdentifierInput.WithStableReference -> {
                require(isEncodable(input.stableReference)) { "A Stable Reference that is not well-formed Unicode has no Import Identifier (ADR-008 §2)." }
                listOf("R", input.sourceKey, input.stableReference)
            }
            is ImportIdentifierInput.WithoutStableReference -> {
                require(input.occurrenceNumber >= 1) { "occurrence_number is 1 for the first (ADR-008 §5)." }
                listOf(
                    "F",
                    input.sourceKey,
                    input.bookedDate.toString(),
                    input.amount.thousandths.toString(),
                    normalisedText(input.payee),
                    normalisedText(input.memo),
                    input.occurrenceNumber.toString(),
                )
            }
            is ImportIdentifierInput.ReUpload -> {
                require(input.occurrence >= 1) { "A Re-upload has the occurrence 1 or more (ADR-008 §2)." }
                listOf("U", input.importIdentifier, input.occurrence.toString())
            }
        }
        val out = ByteArrayOutputStream()
        (listOf(DOMAIN, VERSION) + fields).forEach { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // UC-003 BR-002: Slussen derives the Import Identifier the same way every time, beginning with its marker.
    /** The Import Identifier of [input]: `SLS1-` and the first 130 bits of the SHA-256 digest of its canonical input in base32. */
    fun importIdentifier(input: ImportIdentifierInput): String =
        importIdentifier(input) { MessageDigest.getInstance("SHA-256").digest(it) }

    /**
     * The Import Identifier of [input] with [digest] in place of SHA-256. Production code uses the function above; a
     * test passes a test-only digest to force a collision (ADR-008 §5, §7).
     */
    fun importIdentifier(input: ImportIdentifierInput, digest: (ByteArray) -> ByteArray): String {
        val bytes = digest(canonicalInput(input))
        require(bytes.size * Byte.SIZE_BITS >= CHARACTERS * BITS_PER_CHARACTER) { "The digest is too short for 130 bits." }
        val text = StringBuilder("$MARKER$VERSION-")
        repeat(CHARACTERS) { index ->
            var value = 0
            repeat(BITS_PER_CHARACTER) { offset ->
                val bit = index * BITS_PER_CHARACTER + offset
                val set = (bytes[bit / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)) and 1
                value = (value shl 1) or set
            }
            text.append(BASE32[value])
        }
        return text.toString()
    }

    /**
     * A payee or memo as identity compares it (ADR-008 §2): in Unicode Normalization Form C, an absent one as empty;
     * nothing else changed, not case, not spaces.
     */
    fun normalisedText(text: String?): String = Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFC)

    /**
     * Whether [stableReference] is well-formed Unicode, so that its UTF-8 bytes encode it losslessly (ADR-008 §2, B-08):
     * no unpaired surrogate.
     */
    fun isEncodable(stableReference: String): Boolean {
        var index = 0
        while (index < stableReference.length) {
            val char = stableReference[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= stableReference.length || !Character.isLowSurrogate(stableReference[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(char) -> return false
                else -> index++
            }
        }
        return true
    }

    private const val BITS_PER_CHARACTER = 5
}
