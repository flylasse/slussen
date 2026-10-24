package slussen.architecture

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.architecture.Sources.failOn
import slussen.architecture.Sources.requireScope

/**
 * The Konsist rules K-01 to K-16 of ADR-001 §6.2, one test per rule. Konsist reads source files: imports, declarations
 * and text, not resolved references (the bytecode rules of [BytecodeRulesTest] see those). Every rule first asserts
 * that its scope holds at least one file.
 */
class KonsistRulesTest {
    private val androidName = Regex("""(?<![\w.])(android|androidx|com\.google\.android)\.[A-Za-z_]""")

    private fun lineOf(file: SourceFile, index: Int): Int = file.text.substring(0, index).count { it == '\n' } + 1

    private fun matches(file: SourceFile, regex: Regex, text: String = file.code): List<String> =
        regex.findAll(text).map { "$file:${lineOf(file, it.range.first)}: ${it.value.trim()}" }.toList()

    @Test
    @DisplayName("K-01 (All): :plugin-api, :core, the plug-ins and :testing name nothing of Android")
    fun k01() {
        val scope = requireScope("K-01", Sources.allOf(listOf("plugin-api", "core", "testing") + Sources.pluginModules))
        failOn("K-01", scope.flatMap { matches(it, androidName) })
    }

    @Test
    @DisplayName("K-02 (Prod): :core names no plug-in, persistence or app package")
    fun k02() {
        val scope = requireScope("K-02", Sources.prodOf("core"))
        val forbidden = Regex("""\bslussen\.(provider|budgetapp|fileformat|persistence|app)\b""")
        failOn("K-02", scope.flatMap { matches(it, forbidden) })
    }

    @Test
    @DisplayName("K-03 (All): a plug-in names from slussen only the contracts and its own package root")
    fun k03() {
        val scope = requireScope("K-03", Sources.allOf(Sources.pluginModules))
        val reference = Regex("""\bslussen(\.[a-z][\w]*)+""")
        failOn(
            "K-03",
            scope.flatMap { file ->
                val ownRoot = "slussen." + file.module.substringBefore('-') + "." +
                    file.module.substringAfter('-').replace("-", "")
                reference.findAll(file.code)
                    .filter { m ->
                        val name = m.value
                        !(name == "slussen.pluginapi" || name.startsWith("slussen.pluginapi.") ||
                            name == ownRoot || name.startsWith("$ownRoot."))
                    }
                    .map { "$file:${lineOf(file, it.range.first)}: ${it.value}" }
                    .toList()
            },
        )
    }

    /** The names of this build's plug-ins: every `<name>` of ADR-001 §1, with or without hyphens, and "enable banking". */
    private val pluginNames: List<String> = buildSet {
        Sources.pluginModules.forEach { module ->
            val name = module.substringAfter('-')
            add(name)
            add(name.replace("-", ""))
            add(name.replace("-", " "))
        }
        add("enable banking")
    }.toList()

    @Test
    @DisplayName("K-04 (Prod): :plugin-api, :core, :persistence and slussen.app.ui name no plug-in")
    fun k04() {
        val scope = requireScope(
            "K-04",
            Sources.prodOf("plugin-api", "core", "persistence") +
                Sources.prodOf("app").filter { it.inPackage("slussen.app.ui") },
        )
        requireScope("K-04 (slussen.app.ui)", Sources.prodOf("app").filter { it.inPackage("slussen.app.ui") })
        failOn(
            "K-04",
            scope.flatMap { file ->
                val lower = file.text.lowercase()
                pluginNames.filter { it in lower }.map { "$file: contains '$it'" }
            },
        )
    }

    @Test
    @DisplayName("K-05 (Prod): only slussen.core.upload and slussen.app.di reach the Budget App writer")
    fun k05() {
        val core = requireScope("K-05 (:core)", Sources.prodOf("core"))
        val app = requireScope("K-05 (:app)", Sources.prodOf("app"))
        val writerType = Regex("""\bBudgetAppWriter\b""")
        val writerProperty = Regex("""\.writer\b(?!\s*\()""")
        val writeCall = Regex("""\.(create|markCleared)\s*\(""")
        val violations = mutableListOf<String>()
        core.forEach { file ->
            if (!file.inPackage("slussen.core.upload")) {
                violations += matches(file, writerType) + matches(file, writerProperty)
            }
            val isAdmittedWriter = file.inPackage("slussen.core.upload") && file.fileName == "AdmittedBudgetAppWriter.kt"
            if (!isAdmittedWriter) {
                violations += matches(file, Regex("""\.markCleared\s*\("""))
                if (writerType.containsMatchIn(file.code)) violations += matches(file, writeCall)
            }
        }
        app.filterNot { it.inPackage("slussen.app.di") }.forEach { file ->
            violations += matches(file, writerType) + matches(file, writerProperty)
        }
        failOn("K-05", violations)
    }

    @Test
    @DisplayName("K-06 (Prod): slussen.app.schedule names nothing of slussen.core.upload")
    fun k06() {
        val scope = requireScope("K-06", Sources.prodOf("app").filter { it.inPackage("slussen.app.schedule") })
        failOn("K-06", scope.flatMap { matches(it, Regex("""\bslussen\.core\.upload\b""")) })
    }

    @Test
    @DisplayName("K-07 (Prod): only JournalExport, slussen.app.di and :persistence reference DecisionJournalReader")
    fun k07() {
        val scope = requireScope("K-07", Sources.prod)
        val reader = Regex("""\bDecisionJournalReader\b""")
        failOn(
            "K-07",
            scope
                .filterNot { it.module == "persistence" || it.inPackage("slussen.app.di") }
                .filterNot { it.inPackage("slussen.core.journal") && it.fileName == "JournalExport.kt" }
                .filterNot { Regex("""\binterface\s+DecisionJournalReader\b""").containsMatchIn(it.code) }
                .flatMap { matches(it, reader) },
        )
    }

    @Test
    @DisplayName("K-08 (Prod): slussen.app.ui and slussen.core.status reference no Data Provider, reader or writer")
    fun k08() {
        val scope = requireScope(
            "K-08",
            Sources.prod.filter { it.inPackage("slussen.app.ui") || it.inPackage("slussen.core.status") },
        )
        failOn("K-08", scope.flatMap { matches(it, Regex("""\b(DataProvider|BudgetAppReader|BudgetAppWriter)\b""")) })
    }

    @Test
    @DisplayName("K-09 (All): Room annotations only in :persistence; no destructive fallback anywhere")
    fun k09() {
        val scope = requireScope("K-09", Sources.all.filter { it.module != "architecture-tests" && it.module != "build-logic" })
        val room = Regex("""\bandroidx\.room\b""")
        val destructive = Regex("""fallbackToDestructiveMigration""")
        failOn(
            "K-09",
            scope.filter { it.module != "persistence" }.flatMap { matches(it, room) } +
                scope.flatMap { matches(it, destructive) },
        )
    }

    @Test
    @DisplayName("K-10 (Prod): each plug-in module has exactly one implementation of its contract, named after it")
    fun k10() {
        requireScope("K-10", Sources.pluginModules)
        val contracts = Sources.providerModules.associateWith { "DataProvider" } +
            Sources.budgetAppModules.associateWith { "BudgetApp" } +
            Sources.fileFormatModules.associateWith { "FilePreprocessor" }
        val violations = mutableListOf<String>()
        contracts.forEach { (module, contract) ->
            requireScope("K-10 ($module)", Sources.prodOf(module))
            val implementations = Sources.scope.classes(true, true)
                .filter { SourceFile(it.path, "").let { f -> f.module == module && f.isProduction } }
                .filter { it.parents(false).any { parent -> parent.name == contract } }
            if (implementations.size != 1) {
                violations += ":$module has ${implementations.size} classes implementing $contract: " +
                    implementations.map { it.name }
            }
            implementations.filterNot { Regex("[A-Z][A-Za-z0-9]*$contract").matches(it.name) }.forEach {
                violations += ":$module: ${it.name} is not named <Name>$contract"
            }
        }
        failOn("K-10", violations)
    }

    @Test
    @DisplayName("K-11 (Prod): a File Pre-processor opens nothing, reads no clock and has no var property")
    fun k11() {
        val scope = requireScope("K-11", Sources.prod.filter { it.module in Sources.fileFormatModules })
        val forbidden = Regex(
            """(?<![\w.])(okhttp3\.|java\.net\.|java\.nio\.file\.|java\.io\.File\b|kotlinx\.coroutines\.|java\.time\.Clock\b)""",
        )
        val varProperties = Sources.scope.properties(true)
            .filter { SourceFile(it.path, "").let { f -> f.module in Sources.fileFormatModules && f.isProduction } }
            .filter { it.isVar }
            .map { "${it.path}: var ${it.name}" }
        failOn("K-11", scope.flatMap { matches(it, forbidden) } + varProperties)
    }

    @Test
    @DisplayName("K-12 (Prod): slussen.app.ui imports nothing from :persistence or a plug-in")
    fun k12() {
        val scope = requireScope("K-12", Sources.prodOf("app").filter { it.inPackage("slussen.app.ui") })
        val forbidden = Regex("""^slussen\.(persistence|provider|budgetapp|fileformat)\b""")
        failOn("K-12", scope.flatMap { file -> file.imports.filter { forbidden.containsMatchIn(it) }.map { "$file: import $it" } })
    }

    @Test
    @DisplayName("K-13 (Prod): the logging rules L-01 to L-06 of ADR-006")
    fun k13() {
        val scope = requireScope("K-13", Sources.prod)
        failOn("K-13", LoggingRules.violations(scope))
    }

    @Test
    @DisplayName("K-14 (Test): the naming and marker rules T-01 to T-05 of ADR-004")
    fun k14() {
        val tests = requireScope("K-14", Sources.test)
        val production = requireScope("K-14 (production code for T-03, T-04)", Sources.all.filter { it.isProduction })
        failOn("K-14", Traceability.violations(tests, production))
    }

    @Test
    @DisplayName("K-15 (Prod): within :core, MessageDigest and the Import Identifier marker only in slussen.core.identity")
    fun k15() {
        val scope = requireScope("K-15", Sources.prodOf("core"))
        val digest = Regex("""\bMessageDigest\b""")
        val marker = Regex(""""[^"\n]*SLS[^"\n]*"""")
        failOn(
            "K-15",
            scope.filterNot { it.inPackage("slussen.core.identity") }.flatMap { matches(it, digest) + matches(it, marker) },
        )
    }

    @Test
    @DisplayName("K-16 (Prod): OperationCoordinator is constructed only in slussen.app.di")
    fun k16() {
        val scope = requireScope("K-16", Sources.prod)
        val construction = Regex("""(?<!class\s)\bOperationCoordinator\s*\(""")
        failOn("K-16", scope.filterNot { it.inPackage("slussen.app.di") }.flatMap { matches(it, construction) })
    }
}
