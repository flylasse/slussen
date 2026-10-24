package slussen.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.testfixtures.UseCase

/** A debug build installs beside the Operator's own release build and never replaces it or its data (ADR-005 §5). */
class DebugPackageIT {
    @Test
    @UseCase(id = "UC-012", scenario = "A5: No Own Release Build", businessRules = ["BR-009"])
    @DisplayName("BR-009: the debug build is its own package, slussen.app.debug, so the release build keeps the Operator's data")
    fun debugBuildIsItsOwnPackage() {
        assertEquals("slussen.app.debug", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }
}
