package slussen.fileformat.hsacsv

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * A file that does not follow the HSA CSV export (ADR-001 §4.3.1), with the reason shown to the Operator: the line by
 * its number counted from 1, the column where there is one, and what is wrong, never a value of the file (ADR-001 §4.3,
 * NFR-007).
 */
internal class UnreadableFile(val reason: String) : Exception(reason)

internal fun unreadable(line: Int, what: String): Nothing = throw UnreadableFile("Line $line: $what")

internal fun unreadable(line: Int, column: Int, what: String): Nothing =
    throw UnreadableFile("Line $line, column $column${HsaCsvColumns.nameOf(column)?.let { " ($it)" }.orEmpty()}: $what")

/** The columns of the header (ADR-001 §4.3.1, layout rule 2), counted from 1. */
internal object HsaCsvColumns {
    const val RUN_DATE = 1
    const val ACTION = 2
    const val SYMBOL = 3
    const val DESCRIPTION = 4
    const val TYPE = 5
    const val AMOUNT = 11
    const val CASH_BALANCE = 12

    val names: List<String> = listOf(
        "Run Date", "Action", "Symbol", "Description", "Type", "Price (\$)", "Quantity", "Commission (\$)", "Fees (\$)",
        "Accrued Interest (\$)", "Amount (\$)", "Cash Balance (\$)", "Settlement Date",
    )

    /** Line 3 of the file, exactly. */
    val header: String = names.joinToString(",")

    fun nameOf(column: Int): String? = names.getOrNull(column - 1)
}

/** The text of the file and its division into lines (ADR-001 §4.3.1, layout rule 1). */
internal object HsaCsvText {
    private const val BOM_1 = 0xEF.toByte()
    private const val BOM_2 = 0xBB.toByte()
    private const val BOM_3 = 0xBF.toByte()
    private const val LINE_FEED = 0x0A.toByte()

    /**
     * The lines of [file], each without its line end: UTF-8, with or without the byte order mark at its start, every
     * line ended by LF or by CRLF, one kind in the file; the last line may lack its line end. A CR not followed by LF
     * (also one at the very end), both kinds in one file, and a byte that is not UTF-8 reject the file. No default
     * charset is used.
     */
    fun linesOf(file: ByteArray): List<String> {
        val start = if (file.size >= 3 && file[0] == BOM_1 && file[1] == BOM_2 && file[2] == BOM_3) 3 else 0
        val text = decoded(file, start)
        val lines = ArrayList<String>()
        var lineStart = 0
        var crlf: Boolean? = null
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\r' || char == '\n') {
                val number = lines.size + 1
                val isCrlf = char == '\r'
                if (isCrlf && (index + 1 >= text.length || text[index + 1] != '\n')) {
                    unreadable(number, "holds a carriage return that is not followed by a line feed.")
                }
                if (crlf != null && crlf != isCrlf) unreadable(number, "ends with another kind of line end than the lines before it.")
                crlf = isCrlf
                lines += text.substring(lineStart, index)
                index += if (isCrlf) 2 else 1
                lineStart = index
            } else {
                index += 1
            }
        }
        // ADR-001 §4.3.1 rule 1: the last line of the file may lack its line end; whether the file was cut short there is for the layout's rules.
        if (lineStart < text.length) lines += text.substring(lineStart)
        return lines
    }

    /** [file] from [start] as UTF-8; the line of the first byte that is not UTF-8 rejects the file. */
    private fun decoded(file: ByteArray, start: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(file, start, file.size - start)
        val output = CharBuffer.allocate(file.size - start)
        val decodedAll = !decoder.decode(input, output, true).isError && !decoder.flush(output).isError
        if (!decodedAll) unreadable(lineOfByte(file, start, input.position()), "not UTF-8 text.")
        output.flip()
        return output.toString()
    }

    private fun lineOfByte(file: ByteArray, start: Int, position: Int): Int =
        1 + (start until position).count { file[it] == LINE_FEED }

    /**
     * The fields of the transaction line [text], line [number] of the file, split at commas as CSV does: a field in
     * double quotes may hold commas, two double quotes inside it stand for one, the enclosing quotes are not part of the
     * value, and `""` is an empty value. A quote that is not closed, or stands inside a field without enclosing quotes,
     * or is followed by anything but a comma or the line's end, rejects the file.
     */
    fun fieldsOf(number: Int, text: String): List<String> {
        val fields = ArrayList<String>()
        var index = 0
        while (true) {
            val column = fields.size + 1
            if (index < text.length && text[index] == '"') {
                val value = StringBuilder()
                var at = index + 1
                while (true) {
                    if (at >= text.length) unreadable(number, column, "a double quote is not closed.")
                    if (text[at] != '"') {
                        value.append(text[at])
                        at += 1
                    } else if (at + 1 < text.length && text[at + 1] == '"') {
                        value.append('"')
                        at += 2
                    } else {
                        at += 1
                        break
                    }
                }
                fields += value.toString()
                if (at == text.length) return fields
                if (text[at] != ',') unreadable(number, column, "text follows the closing double quote.")
                index = at + 1
            } else {
                val comma = text.indexOf(',', index).let { if (it < 0) text.length else it }
                val value = text.substring(index, comma)
                if (value.contains('"')) unreadable(number, column, "a double quote inside a field that does not begin with one.")
                fields += value
                if (comma == text.length) return fields
                index = comma + 1
            }
        }
    }
}
