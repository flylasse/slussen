package slussen.fileformat.hsacsv

import slussen.pluginapi.value.Amount
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate

/**
 * The reference recipe of the HSA CSV export, version 1 (final; ADR-001 §4.3.1): the lower-case hexadecimal SHA-256
 * digest of the UTF-8 bytes of `field("hsa-csv-reference") field("1") field(bookedDate) field(amount) field(payee)
 * field(memo)`, with ADR-008 §2's `field` encoding, date, amount and text rules. It is permanent once a file was imported
 * (ADR-008 §4): its committed test vectors (`hsa-csv-reference-v1.vectors`) are never changed. No other normalisation is
 * applied: no trimming, no change of case, no locale.
 */
internal object HsaCsvReference {
    private const val RECIPE = "hsa-csv-reference"
    private const val VERSION = "1"
    private const val HEX = "0123456789abcdef"

    /** The reference of a line: 64 lower-case hexadecimal characters. */
    fun of(bookedDate: LocalDate, amount: Amount, payee: String, memo: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalInput(bookedDate, amount, payee, memo))
        val hex = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            hex.append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return hex.toString()
    }

    /** The UTF-8 bytes the digest is taken of. An absent memo is encoded like an empty one (ADR-008 §2). */
    fun canonicalInput(bookedDate: LocalDate, amount: Amount, payee: String, memo: String?): ByteArray {
        val text = StringBuilder()
            .field(RECIPE)
            .field(VERSION)
            .field(isoDate(bookedDate))
            // ADR-008 §2: the signed whole number of thousandths in decimal, a leading - for an outflow, no leading zeros.
            .field(amount.thousandths.toString())
            .field(Normalizer.normalize(payee, Normalizer.Form.NFC))
            .field(Normalizer.normalize(memo.orEmpty(), Normalizer.Form.NFC))
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    /** ADR-008 §2: the number of bytes of the UTF-8 form in decimal, a colon, and those bytes. */
    private fun StringBuilder.field(value: String): StringBuilder =
        append(value.toByteArray(Charsets.UTF_8).size.toString()).append(':').append(value)

    /** ISO 8601 `YYYY-MM-DD`, written without a locale; the format only gives years 0001 to 9999. */
    private fun isoDate(date: LocalDate): String =
        date.year.toString().padStart(4, '0') + "-" + date.monthValue.toString().padStart(2, '0') + "-" + date.dayOfMonth.toString().padStart(2, '0')
}
