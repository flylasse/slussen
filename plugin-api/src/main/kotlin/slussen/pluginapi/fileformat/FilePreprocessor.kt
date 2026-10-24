package slussen.pluginapi.fileformat

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.LocalDate

/**
 * The File Pre-processor contract (ADR-001 §4.3): converts a Bank's Transaction File into the Standard Import Format.
 * A File Pre-processor declares no Upload Window (S-01, FR-094).
 */
public interface FilePreprocessor {
    public val profile: FileFormatProfile

    /** Pure: no I/O, no clock, no state (Konsist K-11, bytecode rule B-01). */
    public fun convert(file: ByteArray): ConversionResult
}

public data class FileFormatProfile(
    /** `FILE_FORMAT.name`. */
    val name: String,
    /** `FILE_FORMAT.source_key`; fixed forever once released (ADR-008 §2). */
    val sourceKey: String,
)

public sealed interface ConversionResult {
    public data class Converted(val file: StandardImportFile) : ConversionResult

    /** Shown to the Operator (UC-002 A2). */
    public data class Rejected(val reason: String) : ConversionResult
}

/** The Standard Import Format (FR-083, UC-002 BR-007): a type, not a file layout. */
public data class StandardImportFile(val transactions: List<StandardImportTransaction>)

public data class StandardImportTransaction(
    /** Never empty (UC-002 BR-002, FR-085); opaque like a Stable Reference (ADR-008 §2). */
    val reference: String,
    val referenceKind: ReferenceKind,
    val bookedDate: LocalDate,
    val amount: Amount,
    val currency: CurrencyCode?,
    val payee: String?,
    val memo: String?,
    val booked: Boolean,
)

/** Whether the Bank supplied the reference or the File Pre-processor derived it. */
public enum class ReferenceKind { Supplied, Derived }
