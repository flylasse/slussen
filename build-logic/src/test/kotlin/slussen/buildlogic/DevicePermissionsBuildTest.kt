package slussen.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Gradle TestKit tests of the permission check of ADR-001 §5 and §6.4 (S-36, NFR-022), each on a fixture project
 * that uses the same convention plugins. The fixture's manifest is replaced by one that asks for the given permissions.
 */
class DevicePermissionsBuildTest {
    private fun GradleFixture.manifestAsking(vararg permissions: String) {
        val uses = permissions.joinToString("\n") { "    <uses-permission android:name=\"$it\" />" }
        File(directory, "app/src/main/AndroidManifest.xml").writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |$uses
            |    <application android:label="@string/fixture_name" />
            |</manifest>
            """.trimMargin(),
        )
    }

    @Test
    @DisplayName("NFR-022, ADR-001 section 5, 6.4: network access and the app's own receiver permission pass; check runs the permission check of every variant and :app:assembleDebug runs it before packaging")
    fun allowedPermissionsPass() {
        val fixture = GradleFixture("permissions-allowed")
        fixture.manifestAsking("android.permission.INTERNET", "\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

        val result = fixture.runner(":app:check", ":app:assembleDebug").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:verifyDebugPermissions")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:verifyReleasePermissions")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:assembleDebug")?.outcome)
        val order = result.tasks.map { it.path }
        assertTrue(
            order.indexOf(":app:verifyDebugPermissions") < order.indexOf(":app:packageDebug"),
            "the permission check runs before the debug package is built: $order",
        )
        assertTrue(fixture.debugApk().isFile)
    }

    @Test
    @DisplayName("NFR-022, ADR-001 section 6.4, 6.6: a permission section 5 does not allow fails :app:assembleDebug naming each one, and no package is built")
    fun disallowedPermissionFailsPackagingNamingEach() {
        val fixture = GradleFixture("permissions-disallowed-assemble")
        fixture.manifestAsking("android.permission.INTERNET", "android.permission.READ_CONTACTS", "android.permission.CAMERA")

        val result = fixture.runner(":app:assembleDebug").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyDebugPermissions")?.outcome)
        listOf("android.permission.READ_CONTACTS", "android.permission.CAMERA").forEach { permission ->
            assertTrue(result.output.contains("  - `$permission`"), "the failure names $permission")
        }
        assertTrue(!result.output.contains("  - `android.permission.INTERNET`"), "network access is allowed")
        assertTrue(
            result.tasks.none { it.path == ":app:packageDebug" && it.outcome == TaskOutcome.SUCCESS },
            "no debug package is built",
        )
    }

    @Test
    @DisplayName("NFR-022, ADR-001 section 6.4: a permission section 5 does not allow fails :app:check")
    fun disallowedPermissionFailsCheck() {
        val fixture = GradleFixture("permissions-disallowed-check")
        fixture.manifestAsking("android.permission.READ_CONTACTS")

        val result = fixture.runner(":app:check", "--continue").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyDebugPermissions")?.outcome)
        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyReleasePermissions")?.outcome)
        assertTrue(result.output.contains("  - `android.permission.READ_CONTACTS`"), "the failure names the permission")
    }
}
