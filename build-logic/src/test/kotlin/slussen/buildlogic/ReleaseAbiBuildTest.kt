package slussen.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * ADR-005 §5: a release package holds native code for arm64-v8a only, a debug package for every ABI a library brings
 * (the x86_64 emulator of ADR-005 §7). The fixture gets invented native libraries for four ABIs and a keystore made
 * for this test only, outside the fixture project, with invented values.
 */
class ReleaseAbiBuildTest {
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    @Test
    @DisplayName("ADR-005 §5: the release package holds arm64-v8a native code only, the debug package every ABI")
    fun releaseHoldsArm64OnlyAndDebugEveryAbi() {
        val fixture = GradleFixture("release-abi")
        abis.forEach { abi ->
            File(fixture.directory, "app/src/main/jniLibs/$abi/libfixture.so")
                .apply { parentFile.mkdirs() }
                .writeBytes("invented native library for $abi".toByteArray())
        }
        val keystore = inventedKeystore()
        val environment = mapOf(
            ReleaseSigning.STORE_FILE to keystore.absolutePath,
            ReleaseSigning.STORE_PASSWORD to INVENTED_PASSWORD,
            ReleaseSigning.KEY_ALIAS to INVENTED_ALIAS,
            ReleaseSigning.KEY_PASSWORD to INVENTED_PASSWORD,
        ).mapKeys { "ORG_GRADLE_PROJECT_${it.key}" }

        val result = fixture.runner(":app:assembleDebug", ":app:assembleRelease", environment = environment).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:assembleRelease")?.outcome)

        assertEquals(abis.toSet(), nativeAbis(fixture.debugApk()), "the debug package keeps every ABI")
        assertEquals(
            AndroidApplicationConventionPlugin.RELEASE_ABIS,
            nativeAbis(fixture.releaseApk()),
            "the release package holds arm64-v8a only",
        )
    }

    private fun nativeAbis(apk: File): Set<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split('/')[1] }
            .toSet()
    }

    /** A throwaway PKCS12 keystore with invented values, outside the fixture project (ADR-003 §2). */
    private fun inventedKeystore(): File {
        val directory = File(System.getProperty("slussen.repositoryRoot"), "build-logic/build/fixture-keystores")
        directory.deleteRecursively()
        directory.mkdirs()
        val keystore = File(directory, "invented-release.p12")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (windows) ".exe" else "")
        val process = ProcessBuilder(
            keytool.absolutePath, "-genkeypair", "-noprompt",
            "-storetype", "PKCS12", "-keystore", keystore.absolutePath,
            "-storepass", INVENTED_PASSWORD, "-keypass", INVENTED_PASSWORD,
            "-alias", INVENTED_ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=Invented Fixture",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        return keystore
    }

    private companion object {
        const val INVENTED_PASSWORD = "inventedFixturePassword"
        const val INVENTED_ALIAS = "inventedFixtureAlias"
    }
}
