package slussen.fileformat.hsacsv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.testfixtures.FilePreprocessorContractTest
import slussen.pluginapi.testfixtures.UseCase

/** The HSA File Pre-processor against the contract tests of ADR-001 §4.4; its conversion comes with UC-002. */
class HsaCsvFilePreprocessorTest : FilePreprocessorContractTest() {
    override fun filePreprocessor(log: SlussenLog): FilePreprocessor = HsaCsvFilePreprocessor(log)

    @Test
    @UseCase(id = "UC-012", scenario = "A3: Bank Export Format Missing", businessRules = ["BR-005"])
    @DisplayName("A3: the File Pre-processor of this build declares the name HSA CSV export and the source key hsa-csv (ADR-001 §4.3, O-P2-42)")
    fun declaresItsProfile() {
        val profile = HsaCsvFilePreprocessor(SilentLog).profile
        assertEquals("HSA CSV export", profile.name)
        assertEquals("hsa-csv", profile.sourceKey)
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }
}
