package slussen.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

private fun Project.configureAndroidCommon(android: CommonExtension<*, *, *, *, *, *>) {
    android.apply {
        compileSdk = libs.version("compile-sdk").toInt()
        defaultConfig {
            minSdk = libs.version("min-sdk").toInt()
            // JUnit 5 on the device through the android-junit5 plugin (ADR-005 §3).
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            testInstrumentationRunnerArguments["runnerBuilder"] = "de.mannodermaus.junit5.AndroidJUnit5Builder"
        }
        compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }
    configureKotlinJvmVersion()
    configureJvmTests()
}

/** `slussen.android-library` (ADR-005 §1). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("de.mannodermaus.android-junit5")
        extensions.configure<LibraryExtension> { configureAndroidCommon(this) }
    }
}

/**
 * `slussen.android-application` (ADR-005 §1): the version name and code from `VERSION` (§4), the `.debug` suffix and
 * the release ABI filter (§5), and the release signing of ADR-003 §1-§2 (§6). A `debug` package is signed with the
 * machine's debug key and needs no signing value.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("de.mannodermaus.android-junit5")

        val version = AppVersion.read(rootProject.file("VERSION"))
        val signing = ReleaseSigning.propertyNames.associateWith { providers.gradleProperty(it) }

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig {
                targetSdk = libs.version("target-sdk").toInt()
                versionName = version.versionName
                versionCode = version.versionCode
            }
            // The values reach only the signing configuration of the release variant, never a packaged file
            // (ADR-003 §2, §3). Without all four, the release variant has no signing configuration and
            // verifyReleaseSigning stops its packaging; there is no fallback to another key.
            val values = signing.mapValues { it.value.orNull }
            if (values.values.none { it.isNullOrBlank() }) {
                val release = signingConfigs.create("release") {
                    storeFile = file(values.getValue(ReleaseSigning.STORE_FILE)!!)
                    storePassword = values.getValue(ReleaseSigning.STORE_PASSWORD)
                    keyAlias = values.getValue(ReleaseSigning.KEY_ALIAS)
                    keyPassword = values.getValue(ReleaseSigning.KEY_PASSWORD)
                }
                buildTypes.getByName("release").signingConfig = release
            }
            buildTypes.getByName("debug").applicationIdSuffix = ".debug"
            // ADR-005 §5: a release package holds native code for arm64-v8a only; a debug package keeps every ABI a
            // library brings, so the x86_64 emulator of ADR-005 §7 can run it.
            buildTypes.getByName("release").ndk.abiFilters += RELEASE_ABIS
        }

        val verify = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
            group = "verification"
            description = "Checks the four release signing values before a release package is built (ADR-003 section 2)."
            storeFile.set(signing.getValue(ReleaseSigning.STORE_FILE))
            storePassword.set(signing.getValue(ReleaseSigning.STORE_PASSWORD))
            keyAlias.set(signing.getValue(ReleaseSigning.KEY_ALIAS))
            keyPassword.set(signing.getValue(ReleaseSigning.KEY_PASSWORD))
            repositoryRoot.set(rootProject.layout.projectDirectory)
        }
        // Every packaging task of the release variant, and nothing else (ADR-003 §2, ADR-001 §6.4).
        tasks.configureEach {
            if (isReleasePackagingTask(name)) dependsOn(verify)
        }
    }

    companion object {
        /** The only ABI whose native code a release package holds (ADR-005 §5). */
        val RELEASE_ABIS: Set<String> = setOf("arm64-v8a")

        /**
         * The tasks of the Android Gradle Plugin that package or sign the release variant: the APK, the bundle, the
         * universal APK, and AGP's own signing check (which would otherwise print the keystore path it was given).
         * `assembleRelease`, `bundleRelease` and `installRelease` reach them.
         */
        private val releasePackaging = setOf(
            "packageRelease",
            "bundleRelease",
            "packageReleaseBundle",
            "signReleaseBundle",
            "packageReleaseUniversalApk",
            "validateSigningRelease",
        )

        fun isReleasePackagingTask(name: String): Boolean = name in releasePackaging
    }
}
