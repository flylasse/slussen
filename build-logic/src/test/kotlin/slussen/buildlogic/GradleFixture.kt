package slussen.buildlogic

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.Properties

/**
 * A copy of the fixture project of ADR-003 §6, built with Gradle TestKit in its own Gradle user home: the user's
 * `~/.gradle/gradle.properties` is never read, and no `ORG_GRADLE_PROJECT_` variable of the calling environment reaches
 * the fixture build. Only the invented values a test passes do.
 */
class GradleFixture(name: String) {
    private val repositoryRoot = File(System.getProperty("slussen.repositoryRoot")).canonicalFile
    private val testKitDir = File(System.getProperty("slussen.testKitDir"))
    private val source = File(repositoryRoot, "build-logic/src/test/fixtures/android-app")

    val directory: File = File(repositoryRoot, "build-logic/build/fixtures/$name").also { copy ->
        copy.deleteRecursively()
        source.copyRecursively(copy, overwrite = true)
        val template = File(copy, "settings.gradle.kts.template")
        val catalog = File(repositoryRoot, "gradle/libs.versions.toml").invariantSeparatorsPath
        File(copy, "settings.gradle.kts").writeText(template.readText().replace("@CATALOG@", catalog))
        template.delete()
        File(copy, "local.properties").writeText("sdk.dir=" + androidSdk().replace("\\", "/").replace(":", "\\:") + "\n")
    }

    private fun androidSdk(): String {
        val local = File(repositoryRoot, "local.properties")
        val fromFile = if (local.isFile) {
            Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
        } else {
            null
        }
        return fromFile ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("The TestKit tests need an Android SDK (ADR-005 section 2).")
    }

    /** The calling environment without any Gradle project property and without another Gradle user home. */
    private fun cleanEnvironment(): Map<String, String> =
        System.getenv().filterKeys { !it.startsWith("ORG_GRADLE_PROJECT_") && it != "GRADLE_USER_HOME" }

    fun runner(vararg arguments: String, environment: Map<String, String> = emptyMap()): GradleRunner =
        GradleRunner.create()
            .withProjectDir(directory)
            .withPluginClasspath()
            .withTestKitDir(testKitDir)
            .withEnvironment(cleanEnvironment() + environment)
            .withArguments(arguments.toList())

    fun debugApk(): File = apk("debug")

    fun releaseApk(): File = apk("release")

    private fun apk(variant: String): File =
        File(directory, "app/build/outputs/apk/$variant").listFiles { f -> f.name.endsWith(".apk") }
            ?.singleOrNull() ?: error("No $variant APK was built in $directory.")
}
