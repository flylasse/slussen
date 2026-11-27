package slussen.fileformat.hsacsv

/**
 * Invented HSA CSV export files in the exact layout of ADR-001 §4.3.1: two empty lines, the header, the transaction
 * lines newest first, an empty line, and text whose last line begins with `Date downloaded`. No value is real.
 */
internal const val HEADER =
    "Run Date,Action,Symbol,Description,Type,Price (\$),Quantity,Commission (\$),Fees (\$),Accrued Interest (\$),Amount (\$),Cash Balance (\$),Settlement Date"

/** The lines of the five test vectors of ADR-001 §4.3.1, and the reinvestment it places between the second and third. */
internal const val VECTOR_1 = "11/25/2026,DEBIT CARD PURCHASE HARBOR PHARMACY (Cash),\"\",No Description,Cash,\"\",0,\"\",\"\",\"\",\"-12.34\",1987.66,\"\""
internal const val VECTOR_2 = "11/23/2026,PARTIC CONTR CURRENT PARTICIPANT CUR YR (Cash),\"\",No Description,Cash,\"\",0,\"\",\"\",\"\",150,2000,\"\""
internal const val VECTOR_3 =
    "10/30/2026,DIVIDEND RECEIVED EXAMPLE GOVERNMENT CASH RESERVES (EXMPX) (Cash),EXMPX,EXAMPLE GOVERNMENT CASH RESERVES,Cash,\"\",0,\"\",\"\",\"\",4.5,1850,\"\""
internal const val VECTOR_4 = "10/02/2026,DISTRIBUTION EXAMPLE CLINIC (Cash),\"\",Office visit,Cash,\"\",0,\"\",\"\",\"\",\"-25\",1825.5,\"\""
internal const val VECTOR_5 = "09/30/2026,INTEREST EARNED (Cash),EXMPX,No Description,Cash,\"\",0,\"\",\"\",\"\",1.2,1850.5,\"\""
internal const val REINVESTMENT =
    "10/30/2026,REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX) (Cash),EXMPX,EXAMPLE GOVERNMENT CASH RESERVES,Cash,1,4.5,\"\",\"\",\"\",\"-4.5\",1850,\"\""

internal val TRAILER = listOf(
    "The data and information in this invented export are provided for illustration only.",
    "Brokerage services are invented as well.",
    "Date downloaded 11/26/2026 9:15 am",
)

/** A transaction line of the layout with the given date, amount and cash balance (as written in the file). */
internal fun line(date: String, amount: String, balance: String, action: String = "INTEREST EARNED", symbol: String = "EXMPX", description: String = "No Description") =
    "$date,$action (Cash),$symbol,$description,Cash,\"\",0,\"\",\"\",\"\",$amount,$balance,\"\""

/** The text of a file: two empty lines, the header, [lines], an empty line and [trailer], each line ended by [eol]. */
internal fun hsaText(vararg lines: String, eol: String = "\n", trailer: List<String> = TRAILER): String =
    (listOf("", "", HEADER) + lines + "" + trailer).joinToString("") { it + eol }

/** [text] as UTF-8, with the byte order mark at its start when [bom]. */
internal fun bytesOf(text: String, bom: Boolean = true): ByteArray =
    (if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else byteArrayOf()) + text.toByteArray(Charsets.UTF_8)

internal fun hsaFile(vararg lines: String, eol: String = "\n", bom: Boolean = true, trailer: List<String> = TRAILER): ByteArray =
    bytesOf(hsaText(*lines, eol = eol, trailer = trailer), bom)

/**
 * One invented history of a Bank Account, oldest first: A (vector 5), B (vector 4), C, D (vector 3), E (the
 * reinvestment, left out), F (vector 2), G (vector 1), then I and J. The first export holds A to G; the later one lacks
 * A and B and adds I and J, its cash balances continuing.
 */
internal object Histories {
    const val LINE_C = "10/15/2026,PARTIC CONTR CURRENT PARTICIPANT CUR YR (Cash),\"\",No Description,Cash,\"\",0,\"\",\"\",\"\",20,1845.5,\"\""
    const val LINE_I = "12/01/2026,INTEREST EARNED (Cash),EXMPX,No Description,Cash,\"\",0,\"\",\"\",\"\",0.8,1988.46,\"\""
    const val LINE_J = "12/03/2026,DISTRIBUTION EXAMPLE CLINIC (Cash),\"\",Office visit,Cash,\"\",0,\"\",\"\",\"\",\"-40\",1948.46,\"\""

    /** SHA-256 of `17:hsa-csv-reference1:110:2026-10-155:2000039:PARTIC CONTR CURRENT PARTICIPANT CUR YR0:`, computed apart from the code. */
    const val REFERENCE_C = "c08f364598ab7b2fbe09bbe179d7ca50abe5628dbf82741d15531e9f44cfb7cd"

    /** SHA-256 of `17:hsa-csv-reference1:110:2026-12-013:80015:INTEREST EARNED7:(EXMPX)`. */
    const val REFERENCE_I = "4d3ed70b49cf3bf5246945394830798c126b3a790a903a5ff6e9e911a46f8bce"

    /** SHA-256 of `17:hsa-csv-reference1:110:2026-12-036:-4000027:DISTRIBUTION EXAMPLE CLINIC12:Office visit`. */
    const val REFERENCE_J = "cb1471adca6e40dfe0619109267d0ff1199d55701f5d83fb690d84a64264d413"

    fun firstExport(): ByteArray = hsaFile(VECTOR_1, VECTOR_2, REINVESTMENT, VECTOR_3, LINE_C, VECTOR_4, VECTOR_5)

    fun laterExport(): ByteArray = hsaFile(
        LINE_J, LINE_I, VECTOR_1, VECTOR_2, REINVESTMENT, VECTOR_3, LINE_C,
        trailer = TRAILER.dropLast(1) + "Date downloaded 12/04/2026 7:02 pm",
    )
}
