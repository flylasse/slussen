package slussen.core.identity

import slussen.pluginapi.value.BankAccountNumber
import java.security.MessageDigest

/**
 * The one-way `bank_account_fingerprint` of a delivered Bank Account number and its `last_four_digits` (ADR-008 §8,
 * UC-009 BR-009). The number itself is never stored.
 */
object BankAccountFingerprint {
    private const val HEX = "0123456789abcdef"

    /** Spaces, hyphens, dots and slashes removed, the ASCII letters a to z written as capitals; nothing else changed. */
    private fun canonical(number: BankAccountNumber): String = buildString {
        number.delivered.forEach { c ->
            when (c) {
                ' ', '-', '.', '/' -> Unit
                in 'a'..'z' -> append(c.uppercaseChar())
                else -> append(c)
            }
        }
    }

    private fun field(text: String): String = "${text.toByteArray(Charsets.UTF_8).size}:$text"

    /**
     * SHA-256, in hex, of the scoped canonical input of ADR-008 §8: the Data Provider's Source Key and the Bank's
     * reference as the Data Provider delivers it (empty when it names none), so the same number at two Banks or
     * through two Data Providers gives two fingerprints.
     */
    fun of(sourceKey: String, bankReference: String?, number: BankAccountNumber): String {
        val input = field("slussen-fingerprint") + field("1") + field(sourceKey) + field(bankReference.orEmpty()) +
            field(canonical(number))
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append(HEX[(byte.toInt() shr 4) and 0x0f]).append(HEX[byte.toInt() and 0x0f]) }
        }
    }

    // UC-009 BR-009: Of a Bank Account number only the last four digits are kept, letters skipped.
    /** The last four digits of the canonical number, or null when it has fewer than four. */
    fun lastFourDigits(number: BankAccountNumber): String? =
        canonical(number).filter { it in '0'..'9' }.takeIf { it.length >= LAST_DIGITS }?.takeLast(LAST_DIGITS)

    /** The part of a Bank Account or card number that banks and card issuers conventionally show (UC-009 BR-009). */
    private const val LAST_DIGITS = 4
}
