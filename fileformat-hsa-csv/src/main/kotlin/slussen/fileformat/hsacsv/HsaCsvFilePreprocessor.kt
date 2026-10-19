package slussen.fileformat.hsacsv

import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.ConversionResult
import slussen.pluginapi.fileformat.FileFormatProfile
import slussen.pluginapi.fileformat.FilePreprocessor

/**
 * The one File Pre-processor of this build (ADR-001 §1, §4.3; C-010), named after the export format it reads
 * (O-P1-01), with the name and source key ADR-001 §4.3 states (O-P2-42, S-31). The conversion into the Standard Import
 * Format comes with the slice that builds UC-002.
 */
class HsaCsvFilePreprocessor(private val log: SlussenLog) : FilePreprocessor {
    override val profile: FileFormatProfile = FileFormatProfile(name = "HSA CSV export", sourceKey = "hsa-csv")

    override fun convert(file: ByteArray): ConversionResult =
        TODO("Built by the slice that builds UC-002 (ADR-001 §4.3).")
}
