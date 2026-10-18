package slussen.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool
import java.io.File
import java.util.Properties

/**
 * `slussen.root`, applied to the root project only: the Android SDK requirement (ADR-005 §2), the module graph check
 * `verifyModuleGraph` (ADR-001 §6.1) and the task graph of ADR-001 §6.4.
 */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        check(this == rootProject) { "slussen.root is applied to the root project only." }
        pluginManager.apply("base")
        requireAndroidSdk()

        val verifyModuleGraph = tasks.register<VerifyModuleGraphTask>("verifyModuleGraph") {
            group = "verification"
            description = "Checks every declared dependency against the tables of ADR-001 section 2."
        }

        gradle.projectsEvaluated {
            verifyModuleGraph.configure { declared.set(collectDeclaredDependencies()) }
            wireTaskGraph()
        }
    }

    /** ADR-005 §2: a machine without an Android SDK fails at configuration, saying where the SDK must be named. */
    private fun Project.requireAndroidSdk() {
        val localProperties = rootProject.file("local.properties")
        val fromFile = if (localProperties.isFile) {
            Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
        } else {
            null
        }
        val sdk = fromFile ?: providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        if (sdk == null || !File(sdk).isDirectory) {
            throw GradleException(
                "Slussen needs an Android SDK to build every module (ADR-005 section 2): set ANDROID_HOME, or sdk.dir in " +
                    "local.properties, to the directory of an Android SDK.",
            )
        }
    }

    private fun Project.collectDeclaredDependencies(): List<String> =
        subprojects.flatMap { module ->
            module.configurations.flatMap { configuration ->
                configuration.dependencies.map { dependency ->
                    val declared = when (dependency) {
                        is ProjectDependency -> DeclaredDependency.OnProject(
                            dependency.path,
                            dependency.requestedCapabilities.any { it.name.endsWith("-test-fixtures") },
                        )
                        is ExternalModuleDependency -> DeclaredDependency.OnLibrary(dependency.group, dependency.name)
                        else -> DeclaredDependency.Other("${dependency.group}:${dependency.name}")
                    }
                    VerifyModuleGraphTask.encode(module.path, configuration.name, declared)
                }
            }
        } + subprojects.map { VerifyModuleGraphTask.encodeModule(it.path) }

    /** ADR-001 §6.4. */
    private fun Project.wireTaskGraph() {
        val architectureTests = project(":architecture-tests")
        val architectureTest = architectureTests.tasks.named("test")

        // Every Kotlin and Java compile task of every module runs after the module graph check.
        subprojects.forEach { module ->
            module.tasks.configureEach {
                if (this is KotlinCompileTool || this is JavaCompile || name.startsWith("ksp")) {
                    dependsOn(":verifyModuleGraph")
                }
            }
        }

        // The rules of :architecture-tests take the compiled classes of every production module as inputs.
        val production = subprojects.filter {
            val group = ModuleGraphRules.groupOf(it.path)
            group != null && group != ModuleGroup.Testing && group != ModuleGroup.ArchitectureTests
        }
        architectureTest.configure {
            val test = this as Test
            production.forEach { module ->
                val compileTasks = module.tasks.matching {
                    it.name == "compileKotlin" || it.name == "compileDebugKotlin" ||
                        it.name == "compileDebugJavaWithJavac" || it.name == "compileJava"
                }
                test.dependsOn(compileTasks)
                val directories = module.provider {
                    compileTasks.mapNotNull { task ->
                        when (task) {
                            is KotlinCompile -> task.destinationDirectory.get().asFile
                            is JavaCompile -> task.destinationDirectory.get().asFile
                            else -> null
                        }
                    }
                }
                test.inputs.files(directories).withPropertyName("classes${module.path.replace(':', '_')}")
                test.jvmArgumentProviders.add {
                    listOf("-Dslussen.classes.${module.name}=" + directories.get().joinToString(File.pathSeparator))
                }
            }
            test.systemProperty("slussen.repositoryRoot", rootDir.absolutePath)
            test.systemProperty("slussen.productionModules", production.joinToString(",") { it.name })
            // Konsist reads the source files, which are not declared as inputs, so the rules always run.
            test.outputs.upToDateWhen { false }
        }

        // The root check, every module's check and every packaging task of :app run the architecture rules.
        tasks.named("check") {
            dependsOn(architectureTest)
            dependsOn(gradle.includedBuild("build-logic").task(":check"))
        }
        subprojects.forEach { module ->
            if (module != architectureTests) {
                module.tasks.matching { it.name == "check" }.configureEach { dependsOn(architectureTest) }
            }
        }
        // package*, bundle* of each variant (and therefore assemble* and install*); not the resource or asset steps
        // that the compile tasks themselves need.
        val appPackaging = Regex("(package|bundle)(Debug|Release)(Bundle|UniversalApk)?")
        project(":app").tasks.configureEach {
            if (appPackaging.matches(name)) dependsOn(architectureTest)
        }
    }
}

/** `verifyModuleGraph` of ADR-001 §6.1. */
abstract class VerifyModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val declared: ListProperty<String>

    @TaskAction
    fun verify() {
        val modules = linkedMapOf<String, MutableList<Pair<String, DeclaredDependency>>>()
        declared.get().forEach { line ->
            val parts = line.split('|')
            val list = modules.getOrPut(parts[0]) { mutableListOf() }
            when (parts[1]) {
                "module" -> Unit
                else -> list += parts[2] to decode(parts.drop(1))
            }
        }
        val problems = ModuleGraphRules.check(modules)
        if (problems.isNotEmpty()) {
            throw GradleException("The module graph breaks ADR-001 section 2:\n" + problems.joinToString("\n") { "  - $it" })
        }
    }

    companion object {
        fun encodeModule(path: String) = "$path|module"

        fun encode(path: String, configuration: String, dependency: DeclaredDependency): String = when (dependency) {
            is DeclaredDependency.OnProject -> "$path|project|$configuration|${dependency.path}|${dependency.testFixtures}"
            is DeclaredDependency.OnLibrary -> "$path|library|$configuration|${dependency.group}|${dependency.name}"
            is DeclaredDependency.Other -> "$path|other|$configuration|${dependency.description}"
        }

        private fun decode(parts: List<String>): DeclaredDependency = when (parts[0]) {
            "project" -> DeclaredDependency.OnProject(parts[2], parts[3].toBoolean())
            "library" -> DeclaredDependency.OnLibrary(parts[2], parts[3])
            else -> DeclaredDependency.Other(parts[2])
        }
    }
}
