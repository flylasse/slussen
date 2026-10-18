package slussen.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-003 §3 and §5, ADR-006 §6: builds the `debug` package of a fixture project that uses the same convention plugins,
 * with the four release signing properties and the live-test properties set to invented values that are easy to
 * search for, and asserts that no such value, and no name of those properties, appears in any entry of the package,
 * compared as bytes in UTF-8 and UTF-16, and that no value appears in the build's output at any log level.
 */
class NoCredentialInAppTest {
    private val fixture = GradleFixture("no-credential-in-app")

    /** Invented values only; the store file is an invented absolute path outside the repository. */
    private val planted: Map<String, String> = mapOf(
        ReleaseSigning.STORE_FILE to File(System.getProperty("java.io.tmpdir"), "plantedStoreFileQz81vXk.jks").absolutePath,
        ReleaseSigning.STORE_PASSWORD to "plantedStorePasswordK7m2Qw",
        ReleaseSigning.KEY_ALIAS to "plantedKeyAliasR4t9Lp",
        ReleaseSigning.KEY_PASSWORD to "plantedKeyPasswordB6n1Xc",
        "slussenLiveTestYnabAccessToken" to "plantedLiveTokenH2s8Vd",
        "slussenLiveTestYnabBudgetId" to "plantedBudgetIdM5w3Jf",
    )

    /** What is searched for: each value (the store file also by its file name) and each property name. */
    private val searched: List<String> =
        planted.values.toList() + File(planted.getValue(ReleaseSigning.STORE_FILE)).name + planted.keys

    @Test
    @DisplayName("UC-012 BR-012, NFR-021: no planted credential or signing value enters a built app or the build output")
    fun noPlantedValueInTheDebugPackageOrTheOutput() {
        val environment = planted.mapKeys { "ORG_GRADLE_PROJECT_${it.key}" }
        val result = fixture.runner(":app:assembleDebug", "--debug", environment = environment).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:assembleDebug")?.outcome)

        val apk = fixture.debugApk()
        val inPackage = PackageScanner.findings(apk, searched)
        assertTrue(inPackage.isEmpty(), "planted values or property names in ${apk.name}:\n" + inPackage.joinToString("\n"))

        val inOutput = planted.values.filter { it in result.output }
        assertTrue(inOutput.isEmpty(), "${inOutput.size} planted values appear in the build output")

        // The planted values did reach the build: the release check (run alone, nothing is packaged) finds all four
        // signing values set and only the invented keystore missing, and names no value.
        val check = fixture.runner(":app:verifyReleaseSigning", "--debug", environment = environment).buildAndFail()
        assertTrue(check.output.contains("`${ReleaseSigning.STORE_FILE}` names no readable file"))
        assertTrue(!check.output.contains("Missing `"), "every planted signing value reached the build")
        val inCheckOutput = planted.values.filter { it in check.output }
        assertTrue(inCheckOutput.isEmpty(), "${inCheckOutput.size} planted values appear in the release check's output")
    }
}
