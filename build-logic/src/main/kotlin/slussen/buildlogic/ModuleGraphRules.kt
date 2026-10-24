package slussen.buildlogic

/**
 * The tables of ADR-001 §2 (allowed project dependencies and allowed external libraries per module group) and the
 * naming rule of ADR-001 §1, kept in one file as ADR-001 §6.1 requires. A change here is an amendment of ADR-001.
 */
enum class ModuleGroup {
    PluginApi,
    Core,
    DataProvider,
    BudgetApp,
    FileFormat,
    Persistence,
    App,
    Testing,
    ArchitectureTests,
    ;

    val isPlugin: Boolean get() = this == DataProvider || this == BudgetApp || this == FileFormat
}

/** One declared dependency, as the module graph check sees it. */
sealed interface DeclaredDependency {
    /** A project dependency; [testFixtures] is true for `testFixtures(project(...))`. */
    data class OnProject(val path: String, val testFixtures: Boolean) : DeclaredDependency

    /** An external library, identified by group and name; the version is not part of the rule. */
    data class OnLibrary(val group: String, val name: String) : DeclaredDependency

    /** Anything else (a file collection, a Gradle API dependency): never allowed. */
    data class Other(val description: String) : DeclaredDependency
}

object ModuleGraphRules {
    /** ADR-001 §1: `<name>` is words of lowercase letters and digits joined by single hyphens. */
    private val pluginName = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /** The module group of a project path, or null when the path follows no naming rule of ADR-001 §1. */
    fun groupOf(path: String): ModuleGroup? = when {
        path == ":plugin-api" -> ModuleGroup.PluginApi
        path == ":core" -> ModuleGroup.Core
        path == ":persistence" -> ModuleGroup.Persistence
        path == ":app" -> ModuleGroup.App
        path == ":testing" -> ModuleGroup.Testing
        path == ":architecture-tests" -> ModuleGroup.ArchitectureTests
        path.startsWith(":provider-") && pluginName.matches(path.removePrefix(":provider-")) -> ModuleGroup.DataProvider
        path.startsWith(":budgetapp-") && pluginName.matches(path.removePrefix(":budgetapp-")) -> ModuleGroup.BudgetApp
        path.startsWith(":fileformat-") && pluginName.matches(path.removePrefix(":fileformat-")) -> ModuleGroup.FileFormat
        else -> null
    }

    /**
     * ADR-001 §2, first table, production configurations: the project dependencies a module may declare. `:core`
     * declares `:plugin-api` as `api` only.
     */
    fun productionProjectDependencyAllowed(from: ModuleGroup, configuration: String, to: ModuleGroup): Boolean =
        when (from) {
            ModuleGroup.PluginApi -> false
            ModuleGroup.Core -> to == ModuleGroup.PluginApi && configuration == "api"
            ModuleGroup.DataProvider, ModuleGroup.BudgetApp, ModuleGroup.FileFormat -> to == ModuleGroup.PluginApi
            ModuleGroup.Persistence -> to == ModuleGroup.Core
            ModuleGroup.App -> to == ModuleGroup.Core || to == ModuleGroup.Persistence || to.isPlugin
            ModuleGroup.Testing -> to == ModuleGroup.Core
            ModuleGroup.ArchitectureTests -> false
        }

    /** ADR-001 §2, first table, last column: the project dependencies a module may declare in tests only. */
    fun testProjectDependencyAllowed(from: ModuleGroup, to: ModuleGroup, testFixtures: Boolean): Boolean {
        val pluginApiFixtures = to == ModuleGroup.PluginApi && testFixtures
        return when (from) {
            ModuleGroup.PluginApi -> false
            ModuleGroup.Core, ModuleGroup.Persistence, ModuleGroup.App -> pluginApiFixtures || to == ModuleGroup.Testing
            ModuleGroup.DataProvider, ModuleGroup.BudgetApp, ModuleGroup.FileFormat -> pluginApiFixtures
            ModuleGroup.Testing -> false
            ModuleGroup.ArchitectureTests -> pluginApiFixtures
        }
    }

    /** ADR-001 §2: the baseline of every module and configuration, the Kotlin standard library the plugin adds. */
    private val baseline = setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
    )

    private const val COROUTINES_CORE = "org.jetbrains.kotlinx:kotlinx-coroutines-core"
    private const val COROUTINES_ANDROID = "org.jetbrains.kotlinx:kotlinx-coroutines-android"
    private const val COROUTINES_TEST = "org.jetbrains.kotlinx:kotlinx-coroutines-test"

    /** ADR-001 §2, second table: external libraries allowed in production configurations, besides the baseline. */
    fun productionLibraryAllowed(group: ModuleGroup, configuration: String, library: DeclaredDependency.OnLibrary): Boolean {
        val id = "${library.group}:${library.name}"
        if (id in baseline) return true
        return when (group) {
            ModuleGroup.PluginApi, ModuleGroup.Core -> id == COROUTINES_CORE
            ModuleGroup.DataProvider, ModuleGroup.BudgetApp ->
                id == COROUTINES_CORE || id == "com.squareup.okhttp3:okhttp" ||
                    id == "org.jetbrains.kotlinx:kotlinx-serialization-json"
            ModuleGroup.FileFormat -> false
            ModuleGroup.Persistence ->
                id == COROUTINES_ANDROID ||
                    id == "androidx.room:room-runtime" || id == "androidx.room:room-ktx" ||
                    (id == "androidx.room:room-compiler" && configuration.startsWith("ksp")) ||
                    library.group == "androidx.sqlite"
            ModuleGroup.App ->
                id == COROUTINES_ANDROID || library.group.startsWith("androidx.")
            ModuleGroup.Testing -> id == COROUTINES_CORE || id == COROUTINES_TEST
            ModuleGroup.ArchitectureTests -> false
        }
    }

    /**
     * ADR-001 §2, after the second table: what test configurations of every module may declare, besides what the module
     * may declare in production: JUnit 5, `kotlinx-coroutines-test`, the AndroidX test libraries (Android modules only),
     * OkHttp's `mockwebserver` (plug-in modules only) and the libraries of the `android-junit5` plugin; for
     * `:architecture-tests` also Konsist and ASM (second table, last row).
     */
    fun testLibraryAllowed(group: ModuleGroup, configuration: String, library: DeclaredDependency.OnLibrary): Boolean {
        if (productionLibraryAllowed(group, configuration, library)) return true
        val id = "${library.group}:${library.name}"
        val junit5 = library.group == "org.junit" || library.group == "org.junit.jupiter" ||
            library.group == "org.junit.platform"
        val androidJunit5 = library.group == "de.mannodermaus.junit5"
        val androidxTest = library.group == "androidx.test" || library.group.startsWith("androidx.test.") ||
            id == "androidx.room:room-testing" || library.name.startsWith("ui-test") && library.group == "androidx.compose.ui"
        val android = group == ModuleGroup.Persistence || group == ModuleGroup.App
        return junit5 || id == COROUTINES_TEST ||
            (android && (androidxTest || androidJunit5)) ||
            (group.isPlugin && id == "com.squareup.okhttp3:mockwebserver") ||
            (group == ModuleGroup.ArchitectureTests && (id == "com.lemonappdev:konsist" || library.group == "org.ow2.asm"))
    }

    /** Which configurations are checked: every bucket a module declares dependencies in. */
    fun isChecked(configuration: String): Boolean {
        val name = configuration.lowercase()
        // Tool classpaths that plugins resolve for themselves (for example kspPluginClasspath) are not modules' code.
        if (name.contains("classpath")) return false
        return name.endsWith("implementation") || name.endsWith("api") || name.endsWith("compileonly") ||
            name.endsWith("runtimeonly") || name.endsWith("compileonlyapi") || name.startsWith("ksp") ||
            name.endsWith("annotationprocessor") || name.startsWith("kapt")
    }

    /** Test configurations (test, androidTest, testFixtures and their variants); every other checked one is production. */
    fun isTest(configuration: String): Boolean = configuration.lowercase().contains("test")

    /**
     * Checks every declared dependency of every module against the tables above. Returns one line per violation,
     * empty when the graph is allowed.
     */
    fun check(modules: Map<String, List<Pair<String, DeclaredDependency>>>): List<String> {
        val problems = mutableListOf<String>()
        for ((path, dependencies) in modules.toSortedMap()) {
            val group = groupOf(path)
            if (group == null) {
                problems += "Module $path follows no naming rule of ADR-001 section 1 (:provider-<name>, :budgetapp-<name>, " +
                    ":fileformat-<name>, <name> of lowercase letters and digits joined by single hyphens)."
                continue
            }
            for ((configuration, dependency) in dependencies) {
                if (!isChecked(configuration)) continue
                val test = isTest(configuration)
                when (dependency) {
                    is DeclaredDependency.OnProject -> {
                        if (dependency.path == path) continue
                        val dependencyGroup = groupOf(dependency.path)
                        val allowed = dependencyGroup != null && if (test) {
                            testProjectDependencyAllowed(group, dependencyGroup, dependency.testFixtures) ||
                                (!dependency.testFixtures &&
                                    productionProjectDependencyAllowed(group, "api", dependencyGroup))
                        } else {
                            !dependency.testFixtures && productionProjectDependencyAllowed(group, configuration, dependencyGroup)
                        }
                        if (!allowed) {
                            val shown = if (dependency.testFixtures) "testFixtures(${dependency.path})" else dependency.path
                            problems += "Forbidden project dependency $path -> $shown in configuration " +
                                "'$configuration' (ADR-001 section 2)."
                        }
                    }
                    is DeclaredDependency.OnLibrary -> {
                        val allowed = if (test) {
                            testLibraryAllowed(group, configuration, dependency)
                        } else {
                            productionLibraryAllowed(group, configuration, dependency)
                        }
                        if (!allowed) {
                            problems += "Forbidden external library ${dependency.group}:${dependency.name} in $path " +
                                "configuration '$configuration' (ADR-001 section 2, module group $group)."
                        }
                    }
                    is DeclaredDependency.Other ->
                        problems += "Forbidden dependency ${dependency.description} in $path configuration " +
                            "'$configuration' (ADR-001 section 2 allows project dependencies and listed libraries only)."
                }
            }
        }
        return problems
    }
}
