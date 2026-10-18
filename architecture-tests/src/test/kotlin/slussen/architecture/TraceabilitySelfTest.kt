package slussen.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The traceability checks of ADR-004 §3 against planted sources, so that a check that finds nothing in the module
 * skeleton is known to find what it looks for. The planted texts live only in this test.
 */
class TraceabilitySelfTest {
    private val useCases = Traceability.useCaseFiles().mapValues { Traceability.headings(it.value) }

    private fun source(path: String, text: String) = SourceFile(File(Sources.repositoryRoot, path).path, text)

    @Test
    @DisplayName("The specification is read: every use case file has business rules and flows")
    fun theSpecificationIsRead() {
        assertTrue(useCases.size >= 14, "docs/use_cases holds ${useCases.size} use case files")
        assertTrue(useCases.values.all { it.businessRules.isNotEmpty() })
        assertTrue("A1: Signing Values Missing" in useCases.getValue("UC-012").flows)
        assertTrue("BR-002" in useCases.getValue("UC-012").businessRules)
    }

    @Test
    @DisplayName("T-01 finds a test without @UseCase and an id that is not the class's")
    fun t01FindsPlantedViolations() {
        val planted = source(
            "core/src/test/kotlin/slussen/core/UC012PlantedTest.kt",
            """
            package slussen.core
            @DisplayName("UC-012: Set Up an Own Build (with a parenthesis)")
            class UC012PlantedTest {
                @Test
                @UseCase(id = "UC-012", scenario = "A1: Signing Values Missing", businessRules = ["BR-002"])
                @DisplayName("A1: fails (clearly)")
                fun marked() {}

                @Test
                fun unmarked() {}

                @Test
                @UseCase(id = "UC-013")
                fun wrongId() {}
            }
            """.trimIndent(),
        )
        val found = Traceability.t01(listOf(planted))
        assertEquals(2, found.size, found.joinToString("\n"))
        assertTrue(found.any { "unmarked" in it })
        assertTrue(found.any { "UC-013" in it })
    }

    @Test
    @DisplayName("T-02 finds an unknown use case, business rule and flow")
    fun t02FindsPlantedViolations() {
        val planted = source(
            "core/src/test/kotlin/slussen/core/UC012PlantedTest.kt",
            """
            @UseCase(id = "UC-012", scenario = "A1: Signing Values Missing", businessRules = ["BR-002", "BR-099"])
            @UseCase(id = "UC-012", scenario = "A9: No Such Flow")
            @UseCase(id = "UC-999")
            """.trimIndent(),
        )
        val found = Traceability.t02(listOf(planted), useCases)
        assertEquals(3, found.size, found.joinToString("\n"))
    }

    @Test
    @DisplayName("T-03 and T-04 find malformed, unknown, bare and misplaced markers")
    fun t03AndT04FindPlantedViolations() {
        val planted = source(
            "core/src/main/kotlin/slussen/core/Planted.kt",
            """
            // UC-012 BR-002: A release build without its signing values fails clearly.
            fun good() {}
            // UC-012 BR-099: No such rule.
            fun unknownRule() {}
            // UC-012: no rule
            fun malformed() {}
            // BR-002: without its use case
            fun bare() {}
            // UC-012 BR-002: followed by a comment.
            // another comment
            fun misplaced() {}
            """.trimIndent(),
        )
        val t03 = Traceability.t03(listOf(planted), useCases)
        assertEquals(3, t03.size, t03.joinToString("\n"))
        val t04 = Traceability.t04(listOf(planted))
        assertEquals(1, t04.size, t04.joinToString("\n"))
    }

    @Test
    @DisplayName("T-05 finds a name with a glossary Avoid term and allows the export format's name in its module")
    fun t05FindsPlantedViolations() {
        val avoid = Traceability.avoidTerms()
        assertTrue(listOf("sync") in avoid && listOf("csv") in avoid, "the Avoid column is read")
        val planted = source(
            "core/src/main/kotlin/slussen/core/Planted.kt",
            "package slussen.core\nclass BankRowMapper(val syncTarget: String)\nfun pullTransactions(origin: String) {}\n",
        )
        val found = Traceability.t05(listOf(planted), avoid)
        assertTrue(found.any { "BankRowMapper" in it }, found.joinToString("\n"))
        assertTrue(found.any { "syncTarget" in it }, found.joinToString("\n"))
        assertTrue(found.any { "pullTransactions" in it }, found.joinToString("\n"))
        assertTrue(found.any { "origin" in it }, found.joinToString("\n"))
        val ownFormat = source(
            "fileformat-hsa-csv/src/main/kotlin/slussen/fileformat/hsacsv/Planted.kt",
            "package slussen.fileformat.hsacsv\nclass HsaCsvThing\n",
        )
        assertEquals(emptyList<String>(), Traceability.t05(listOf(ownFormat), avoid))
    }
}
