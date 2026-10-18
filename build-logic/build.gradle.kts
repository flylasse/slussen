plugins {
    `kotlin-dsl`
}

group = "slussen.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.kotlin)
    // Applied by id only, never compiled against: on the runtime classpath of the convention plugins.
    runtimeOnly(libs.gradle.plugin.compose)
    runtimeOnly(libs.gradle.plugin.serialization)
    runtimeOnly(libs.gradle.plugin.ksp)
    runtimeOnly(libs.gradle.plugin.android.junit5)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        register("root") {
            id = "slussen.root"
            implementationClass = "slussen.buildlogic.RootConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "slussen.kotlin-library"
            implementationClass = "slussen.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "slussen.android-library"
            implementationClass = "slussen.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "slussen.android-application"
            implementationClass = "slussen.buildlogic.AndroidApplicationConventionPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The TestKit tests of ADR-003 §6 build fixture Android projects; they need the Android SDK of this machine and
    // the version catalog of the repository. They run in their own Gradle user home (see GradleFixture).
    systemProperty("slussen.repositoryRoot", rootDir.parentFile.absolutePath)
    systemProperty("slussen.testKitDir", layout.buildDirectory.dir("testkit-gradle-home").get().asFile.absolutePath)
    inputs.dir("src/test/fixtures").withPropertyName("fixtures")
    inputs.file("../gradle/libs.versions.toml").withPropertyName("versionCatalog")
    maxParallelForks = 1
    // The --debug output of a fixture build is kept in memory by TestKit.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
