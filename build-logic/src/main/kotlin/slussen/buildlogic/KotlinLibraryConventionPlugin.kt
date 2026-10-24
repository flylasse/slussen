package slussen.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/** `slussen.kotlin-library` (ADR-005 §1): a plain Kotlin/JVM module compiled for JVM 17 with JUnit 5 tests. */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        configureKotlinJvmVersion()
        configureJvmTests()
    }
}
