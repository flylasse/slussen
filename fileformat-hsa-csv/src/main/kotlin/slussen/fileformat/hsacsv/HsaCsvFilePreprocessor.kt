package slussen.fileformat.hsacsv

import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.FileFormatProfile
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.fileformat.ReferenceKind
import slussen.pluginapi.fileformat.StandardImportFile
import slussen.pluginapi.fileformat.StandardImportTransaction
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.DateTimeException
import java.time.LocalDate

/**
 * The one File Pre-processor of this build (ADR-001 §1, §4.3; C-010), named after the export format it reads
 * (O-P1-01), with the name and source key ADR-001 §4.3 states (O-P2-42, S-31). It converts the history export of an HSA
 * exactly as ADR-001 §4.3.1 describes it into the Standard Import Format, or rejects the whole file. Where the
 * description leaves a point open, the file is rejected, never guessed. Pure: no I/O, no clock, no locale, no default
 * charset and no state (B-01); it logs nothing, so no value of a file reaches a log (NFR-007).
 */
class HsaCsvFilePreprocessor(private val log: SlussenLog) : FilePreprocessor {
    override val profile: FileFormatProfile = FileFormatProfile(name = "HSA CSV export", sourceKey = "hsa-csv")

    override fun convert(file: ByteArray): ConversionResult =
        try {
            ConversionResult.Converted(StandardImportFile(transactionsOf(file)))
        } catch (rejection: UnreadableFile) {
            // UC-002 BR-001: A file that cannot be converted completely never brings in part of its transactions.
            ConversionResult.Rejected(rejection.reason)
        }

    /** One transaction line as read (ADR-001 §4.3.1, "A transaction line"), [number] its line of the file. */
    private class Line(
        val number: Int,
        val runDate: LocalDate,
        val action: String,
        val symbol: String,
        val description: String,
        val amount: Amount,
        val cashBalance: Amount,
    )

    private fun transactionsOf(file: ByteArray): List<StandardImportTransaction> {
        val lines = HsaCsvText.linesOf(file)
        // Layout rule 2: lines 1 and 2 are empty; line 3 is the header, exactly.
        if (lines.isEmpty()) unreadable(1, "missing; the file ends before the header of the export.")
        if (lines[0].isNotEmpty()) unreadable(1, "not empty; the export begins with two empty lines.")
        if (lines.size < 2) unreadable(2, "missing; the file ends before the header of the export.")
        if (lines[1].isNotEmpty()) unreadable(2, "not empty; the export begins with two empty lines.")
        if (lines.size < 3) unreadable(3, "missing; the file ends before the header of the export.")
        if (lines[2] != HsaCsvColumns.header) unreadable(3, "not the header of the HSA CSV export.")
        // Layout rule 3: every line from line 4 up to the first empty line is a transaction line.
        val emptyLine = (3 until lines.size).firstOrNull { lines[it].isEmpty() }
            ?: unreadable(lines.size, "the file ends after this line without the empty line that follows the transaction lines; it may be cut short.")
        // Layout rule 4: text follows the empty line, and its last line that is not empty begins with Date downloaded.
        val lastText = (emptyLine + 1 until lines.size).lastOrNull { lines[it].isNotEmpty() }
            ?: unreadable(emptyLine + 1, "no text follows this empty line; the file may be cut short.")
        if (!lines[lastText].startsWith(DATE_DOWNLOADED)) {
            unreadable(lastText + 1, "the last line of the file does not begin with Date downloaded; the file may be cut short.")
        }
        // Layout rule 4: the text holds no transaction line that would not be read (two exports in one file, or an empty line among the transaction lines).
        (emptyLine + 1 until lines.size).forEach { index -> requireNoTransactionLine(index + 1, lines[index]) }

        val read = ArrayList<Line>()
        (3 until emptyLine).forEach { index ->
            val line = lineOf(index + 1, lines[index])
            val above = read.lastOrNull()
            if (above != null && line.runDate.isAfter(above.runDate)) {
                unreadable(line.number, HsaCsvColumns.RUN_DATE, "later than the Run Date of the line above; the lines are newest first.")
            }
            read += line
        }
        return read.filterIndexed { index, line -> isTransaction(line, read.getOrNull(index + 1)) }.map(::standardOf)
    }

    /**
     * Layout rule 4: a line of the text after the first empty line that is the header again, or gives exactly the
     * header's fields (split as a transaction line is) with a first field of the form `MM/DD/YYYY`, rejects the file.
     * A line that cannot be split so (an unclosed quote, for example) is text.
     */
    private fun requireNoTransactionLine(number: Int, text: String) {
        if (text == HsaCsvColumns.header) unreadable(number, "the header again after the transaction lines; " + NOT_READ)
        val fields = try {
            HsaCsvText.fieldsOf(number, text)
        } catch (notFields: UnreadableFile) {
            return
        }
        if (fields.size == HsaCsvColumns.names.size && DATE.matches(fields[HsaCsvColumns.RUN_DATE - 1])) {
            unreadable(number, "a transaction line after the empty line that ends the transaction lines; " + NOT_READ)
        }
    }

    /** One transaction line: exactly the header's fields, and the columns §4.3.1 reads in their forms. */
    private fun lineOf(number: Int, text: String): Line {
        val fields = HsaCsvText.fieldsOf(number, text)
        if (fields.size != HsaCsvColumns.names.size) unreadable(number, "${fields.size} fields where the header has ${HsaCsvColumns.names.size}.")
        val action = fields[HsaCsvColumns.ACTION - 1]
        val runDate = runDateOf(number, fields[HsaCsvColumns.RUN_DATE - 1])
        if (action.length <= CASH_ENDING.length || !action.endsWith(CASH_ENDING)) {
            unreadable(number, HsaCsvColumns.ACTION, "does not end with (Cash) after at least one character.")
        }
        // A line of another Type (buying or selling another fund) is not described: it rejects the file.
        if (fields[HsaCsvColumns.TYPE - 1] != CASH) unreadable(number, HsaCsvColumns.TYPE, "not Cash; only lines of the cash position can be read.")
        return Line(
            number = number,
            runDate = runDate,
            action = action,
            symbol = fields[HsaCsvColumns.SYMBOL - 1],
            description = fields[HsaCsvColumns.DESCRIPTION - 1],
            amount = numberOf(number, HsaCsvColumns.AMOUNT, fields[HsaCsvColumns.AMOUNT - 1]),
            cashBalance = numberOf(number, HsaCsvColumns.CASH_BALANCE, fields[HsaCsvColumns.CASH_BALANCE - 1]),
        )
    }

    /** `MM/DD/YYYY` with exactly those digits and a real calendar date (year 0000 is none: the calendar has no year 0). */
    private fun runDateOf(number: Int, value: String): LocalDate {
        val match = DATE.matchEntire(value) ?: unreadable(number, HsaCsvColumns.RUN_DATE, NOT_A_DATE)
        val (month, day, year) = match.destructured
        if (year.toInt() == 0) unreadable(number, HsaCsvColumns.RUN_DATE, NOT_A_DATE)
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (notADate: DateTimeException) {
            unreadable(number, HsaCsvColumns.RUN_DATE, NOT_A_DATE)
        }
    }

    /** An optional `-`, one or more digits, and optionally a point with one or two digits; nothing else. */
    private fun numberOf(number: Int, column: Int, value: String): Amount {
        val match = NUMBER.matchEntire(value) ?: unreadable(number, column, "not a number of the export's form: an optional minus, digits and at most two decimals.")
        val (sign, whole, decimals) = match.destructured
        val magnitude = whole.toLongOrNull()?.let { units ->
            try {
                Math.addExact(Math.multiplyExact(units, THOUSANDTHS), decimals.padEnd(3, '0').toLong())
            } catch (tooLarge: ArithmeticException) {
                null
            }
        } ?: unreadable(number, column, "a number too large to convert.")
        return Amount(if (sign.isEmpty()) magnitude else -magnitude)
    }

    /**
     * The cash balance and the reinvestment (ADR-001 §4.3.1): [line]'s cash balance minus that of the line [below] is its
     * amount (a transaction) or 0. A difference of 0 with an amount other than 0 is a reinvestment into the cash
     * position, left out, only when the Action begins with `REINVESTMENT `; any other line with a difference of 0 and
     * an amount other than 0, and any other difference, rejects the file. The oldest line is a transaction unless it is
     * a reinvestment, which rejects the file, since whether it moved the cash balance cannot be told.
     */
    private fun isTransaction(line: Line, below: Line?): Boolean {
        val reinvestment = line.action.startsWith(REINVESTMENT)
        if (below == null) {
            if (reinvestment) unreadable(line.number, HsaCsvColumns.ACTION, "a reinvestment as the oldest line: whether it moved the cash balance cannot be told.")
            return true
        }
        val difference = try {
            Math.subtractExact(line.cashBalance.thousandths, below.cashBalance.thousandths)
        } catch (tooLarge: ArithmeticException) {
            unreadable(line.number, HsaCsvColumns.CASH_BALANCE, BALANCE_BREAK)
        }
        if (difference == line.amount.thousandths) return true
        if (difference != 0L) unreadable(line.number, HsaCsvColumns.CASH_BALANCE, BALANCE_BREAK)
        if (!reinvestment) unreadable(line.number, HsaCsvColumns.CASH_BALANCE, "the same as on the line below while the amount is not 0, and the Action does not begin with REINVESTMENT.")
        return false
    }

    // UC-002 BR-007: Each File Format has one File Pre-processor that converts the Bank's file into the Standard Import Format.
    /** The Standard Import Format of a line (ADR-001 §4.3.1): booked, in USD, its reference Derived by the recipe. */
    private fun standardOf(line: Line): StandardImportTransaction {
        val payee = line.action.removeSuffix(CASH_ENDING)
        val memo = memoOf(line.symbol, line.description)
        return StandardImportTransaction(
            // UC-002 BR-002: The File Pre-processor gives every transaction of the file a reference derived from its own fields.
            reference = HsaCsvReference.of(line.runDate, line.amount, payee, memo),
            referenceKind = ReferenceKind.Derived,
            bookedDate = line.runDate,
            amount = line.amount,
            currency = USD,
            payee = payee,
            memo = memo,
            booked = true,
        )
    }

    /**
     * From `Description` (empty when it is exactly `No Description`) and `Symbol`: with both, the description, a space
     * and the symbol in parentheses; with the description alone, the description; with the symbol alone, the symbol in
     * parentheses; with neither, absent.
     */
    private fun memoOf(symbol: String, description: String): String? {
        val text = if (description == NO_DESCRIPTION) "" else description
        return when {
            text.isNotEmpty() && symbol.isNotEmpty() -> "$text ($symbol)"
            text.isNotEmpty() -> text
            symbol.isNotEmpty() -> "($symbol)"
            else -> null
        }
    }

    private companion object {
        const val CASH = "Cash"
        const val CASH_ENDING = " (Cash)"
        const val REINVESTMENT = "REINVESTMENT "
        const val NO_DESCRIPTION = "No Description"
        const val DATE_DOWNLOADED = "Date downloaded"
        const val THOUSANDTHS = 1000L
        const val NOT_A_DATE = "not a real date of the form MM/DD/YYYY."
        const val NOT_READ = "it would not be read (two exports in one file, or an empty line among the transaction lines)."
        const val BALANCE_BREAK = "differs from the line below by neither the line's amount nor 0."

        /** The header's `($)`: every line is in US dollars (ADR-001 §4.3.1). */
        val USD = CurrencyCode("USD")
        val DATE = Regex("([0-9]{2})/([0-9]{2})/([0-9]{4})")
        val NUMBER = Regex("(-?)([0-9]+)(?:\\.([0-9]{1,2}))?")
    }
}
