package slussen.core.identity

import slussen.core.port.RandomSource

/** The identifiers Slussen generates itself (NFR-020, ADR-008 §7). */
object GeneratedIdentifiers {
    private const val UUID_BYTES = 16
    private const val HEX = "0123456789abcdef"

    // UC-013 BR-003: Slussen generates the Bank Account's identifier, as unlikely to collide as a random UUID.
    /**
     * A new `BANK_ACCOUNT.generated_identifier`: a random version 4 UUID as RFC 9562 defines it, 122 random bits from
     * [random], in its 36-character text form (ADR-002 §2, ADR-008 §7).
     */
    fun bankAccountIdentifier(random: RandomSource): String {
        val bytes = ByteArray(UUID_BYTES)
        random.nextBytes(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // variant 10 of RFC 9562
        val text = StringBuilder(36)
        bytes.forEachIndexed { index, byte ->
            if (index == 4 || index == 6 || index == 8 || index == 10) text.append('-')
            text.append(HEX[(byte.toInt() shr 4) and 0x0f]).append(HEX[byte.toInt() and 0x0f])
        }
        return text.toString()
    }
}
