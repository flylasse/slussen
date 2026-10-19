package slussen.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Unit tests of the module graph tables (ADR-001 §2), the version name (ADR-005 §4) and the release signing rules. */
class BuildLogicUnitTest {
    private fun library(group: String, name: String) = DeclaredDependency.OnLibrary(group, name)

    private fun project(path: String, testFixtures: Boolean = false) = DeclaredDependency.OnProject(path, testFixtures)

    private val allowedGraph: Map<String, List<Pair<String, DeclaredDependency>>> = mapOf(
        ":plugin-api" to listOf("testFixturesApi" to library("org.junit.jupiter", "junit-jupiter-api")),
        ":core" to listOf(
            "api" to project(":plugin-api"),
            "testImplementation" to project(":testing"),
            "testImplementation" to project(":plugin-api", testFixtures = true),
        ),
        ":provider-simplefin" to listOf(
            "implementation" to project(":plugin-api"),
            "implementation" to library("com.squareup.okhttp3", "okhttp"),
            "testImplementation" to library("com.squareup.okhttp3", "mockwebserver"),
        ),
        ":fileformat-hsa-csv" to listOf("implementation" to project(":plugin-api")),
        ":persistence" to listOf(
            "implementation" to project(":core"),
            "ksp" to library("androidx.room", "room-compiler"),
        ),
        ":app" to listOf(
            "implementation" to project(":core"),
            "implementation" to project(":provider-simplefin"),
            "implementation" to library("androidx.compose", "compose-bom"),
        ),
        ":testing" to listOf("implementation" to project(":core")),
        ":architecture-tests" to listOf(
            "testImplementation" to project(":plugin-api", testFixtures = true),
            "testImplementation" to library("com.lemonappdev", "konsist"),
        ),
    )

    @Test
    @DisplayName("ADR-001 section 2: the allowed graph passes")
    fun allowedGraphPasses() {
        assertEquals(emptyList<String>(), ModuleGraphRules.check(allowedGraph))
    }

    @Test
    @DisplayName("ADR-001 section 6.6: a plug-in that depends on :core fails")
    fun pluginOnCoreFails() {
        val planted = allowedGraph + (":provider-simplefin" to listOf("implementation" to project(":core")))
        val problems = ModuleGraphRules.check(planted)
        assertEquals(1, problems.size, problems.joinToString())
        assertTrue(problems.single().contains(":provider-simplefin -> :core"))
    }

    @Test
    @DisplayName("ADR-001 section 2: forbidden directions and libraries fail")
    fun forbiddenEdgesFail() {
        val planted = mapOf(
            ":core" to listOf("implementation" to project(":plugin-api"), "implementation" to project(":persistence")),
            ":provider-simplefin" to listOf("implementation" to project(":provider-enablebanking")),
            ":fileformat-hsa-csv" to listOf("implementation" to library("com.squareup.okhttp3", "okhttp")),
            ":plugin-api" to listOf("implementation" to library("androidx.core", "core-ktx")),
            ":persistence" to listOf("implementation" to project(":app")),
            ":testing" to listOf("testImplementation" to project(":persistence")),
        )
        // :core -> :plugin-api must be api; :core -> :persistence; plug-in -> plug-in; a library in a File
        // Pre-processor; AndroidX in :plugin-api; :persistence -> :app; :testing -> :persistence in tests.
        assertEquals(7, ModuleGraphRules.check(planted).size, ModuleGraphRules.check(planted).joinToString("\n"))
    }

    @Test
    @DisplayName("ADR-001 section 1: a module that follows no naming rule fails")
    fun namingRule() {
        assertEquals(1, ModuleGraphRules.check(mapOf(":provider-Simple_Fin" to emptyList())).size)
        assertEquals(1, ModuleGraphRules.check(mapOf(":source-simplefin" to emptyList())).size)
        assertEquals(ModuleGroup.FileFormat, ModuleGraphRules.groupOf(":fileformat-hsa-csv"))
    }

    @Test
    @DisplayName("ADR-005 section 4: the version name and code come from VERSION")
    fun versionFile() {
        val version = AppVersion.parse("0.1.0\r\n", "VERSION")
        assertEquals("0.1.0", version.versionName)
        assertEquals(1000, version.versionCode)
        assertEquals(999_999_999, AppVersion.parse("999.999.999", "VERSION").versionCode)
        listOf("0.1", "0.1.0.1", "1000.0.0", "01.2.3", "0.1.0\n\n", "v0.1.0", "").forEach { text ->
            val failure = assertThrows<IllegalStateException> { AppVersion.parse(text, "VERSION") }
            assertTrue(failure.message!!.contains("VERSION"), "the failure names the file")
        }
    }

    @Test
    @DisplayName("UC-012 BR-001, BR-002, ADR-003 section 2: each missing signing value is named with where it goes, never its value")
    fun missingSigningValues(@TempDir outside: File) {
        val repository = File(outside, "repository").apply { mkdirs() }
        val problems = ReleaseSigning.problems(emptyMap(), repository)
        assertEquals(4, problems.size)
        ReleaseSigning.propertyNames.forEach { property ->
            assertTrue(problems.any { it.startsWith("Missing `$property`") && "ORG_GRADLE_PROJECT_$property" in it })
        }
    }

    @Test
    @DisplayName("UC-012 BR-001, BR-010, ADR-003 section 2: the keystore must be an absolute, readable file outside the repository")
    fun keystoreRules(@TempDir outside: File) {
        val repository = File(outside, "repository").apply { mkdirs() }
        val inside = File(repository, "release.jks").apply { writeText("invented") }
        val outsideFile = File(outside, "release.jks").apply { writeText("invented") }
        fun values(storeFile: String) = mapOf(
            ReleaseSigning.STORE_FILE to storeFile,
            ReleaseSigning.STORE_PASSWORD to "invented-store-password",
            ReleaseSigning.KEY_ALIAS to "invented-alias",
            ReleaseSigning.KEY_PASSWORD to "invented-key-password",
        )
        assertEquals(emptyList<String>(), ReleaseSigning.problems(values(outsideFile.absolutePath), repository))
        listOf("release.jks", File(outside, "absent.jks").absolutePath, inside.absolutePath).forEach { storeFile ->
            val problems = ReleaseSigning.problems(values(storeFile), repository)
            assertEquals(1, problems.size, storeFile)
            assertTrue(problems.none { storeFile in it || "invented" in it }, "no value is named")
        }
    }

    @Test
    @DisplayName("UC-012 A5, BR-001, ADR-001 section 6.4: only the packaging and signing tasks of the release variant need the signing values")
    fun releasePackagingTasks() {
        listOf("packageRelease", "bundleRelease", "validateSigningRelease").forEach {
            assertTrue(AndroidApplicationConventionPlugin.isReleasePackagingTask(it), it)
        }
        listOf("packageDebug", "compileReleaseKotlin", "testReleaseUnitTest", "lintVitalRelease", "check").forEach {
            assertTrue(!AndroidApplicationConventionPlugin.isReleasePackagingTask(it), it)
        }
    }

    @Test
    @DisplayName("ADR-003 section 3: the package scanner finds a value planted in UTF-8 or UTF-16")
    fun packageScannerFindsPlantedValues(@TempDir directory: File) {
        val apk = File(directory, "planted.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0, 1) + "xxplantedUtf8Valuexx".toByteArray(Charsets.UTF_8))
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write("plantedUtf16Value".toByteArray(Charsets.UTF_16LE))
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("nothing here".toByteArray(Charsets.UTF_16BE))
        }
        val findings = PackageScanner.findings(apk, listOf("plantedUtf8Value", "plantedUtf16Value", "absentValue"))
        assertEquals(listOf("classes.dex: plantedUtf8Value", "resources.arsc: plantedUtf16Value"), findings)
    }
}
