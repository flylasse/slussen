package slussen.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Gradle TestKit tests of ADR-003 §6 on the release signing check, each on a fixture project that uses the same
 * convention plugins and holds only invented values. No signing property is set in these builds.
 */
class ReleaseSigningBuildTest {
    @Test
    @DisplayName("UC-012 A5: without any signing property, check and :app:assembleDebug pass")
    fun debugAndCheckNeedNoSigningValues() {
        val fixture = GradleFixture("debug-without-signing")
        val result = fixture.runner("check", ":app:assembleDebug").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:assembleDebug")?.outcome)
        assertFalse(result.output.contains("verifyReleaseSigning"), "check and debug never run the release check")
        assertTrue(fixture.debugApk().isFile)
    }

    @Test
    @DisplayName("UC-012 A1, BR-002: without any signing property, :app:assembleRelease fails naming each and where it goes")
    fun releaseFailsNamingEachMissingValue() {
        val fixture = GradleFixture("release-without-signing")
        val result = fixture.runner(":app:assembleRelease").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyReleaseSigning")?.outcome)
        ReleaseSigning.propertyNames.forEach { property ->
            val expected = "Missing `$property`: set it in `~/.gradle/gradle.properties` or as the environment " +
                "variable `ORG_GRADLE_PROJECT_$property`; see the README."
            assertTrue(result.output.contains(expected), "the failure names $property and where it goes")
        }
        assertTrue(
            result.tasks.none { it.path == ":app:packageRelease" && it.outcome == TaskOutcome.SUCCESS },
            "no release package is built",
        )
    }
}
