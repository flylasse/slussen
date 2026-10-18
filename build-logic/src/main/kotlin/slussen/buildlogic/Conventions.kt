package slussen.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

/** The live-test properties of ADR-003 §4; read only by a test task run with `-PincludeLive`. */
internal object LiveTest {
    const val INCLUDE = "includeLive"
    val propertyNames = listOf("slussenLiveTestYnabAccessToken", "slussenLiveTestYnabBudgetId")
}

/** JVM 17 for every module (ADR-005 §1). */
internal fun Project.configureKotlinJvmVersion() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(libs.version("jvm-target")))
    }
}

internal val javaVersion: JavaVersion = JavaVersion.VERSION_17

/**
 * JUnit 5 for every JVM test (ADR-005 §3). Tests tagged `live` run only with `-PincludeLive`; only then are the
 * live-test properties read, at execution of the test task, and handed to the tests as environment variables
 * (ADR-003 §4, ADR-004 §2). Agents never pass `-PincludeLive`.
 */
internal fun Project.configureJvmTests() {
    dependencies {
        add("testImplementation", platform(libs.library("junit-bom")))
        add("testImplementation", libs.library("junit-jupiter"))
        add("testRuntimeOnly", libs.library("junit-platform-launcher"))
    }
    val includeLive = providers.gradleProperty(LiveTest.INCLUDE).isPresent
    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            if (!includeLive) excludeTags("live")
        }
        if (includeLive) {
            val liveValues = LiveTest.propertyNames.associateWith { providers.gradleProperty(it) }
            doFirst {
                val test = this as Test
                liveValues.forEach { (name, value) -> value.orNull?.let { v -> test.environment(name, v) } }
            }
        }
    }
}
