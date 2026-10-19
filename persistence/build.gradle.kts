// :persistence (ADR-001 §1): the Room databases of ADR-002, their DAOs, the implementations of the core's storage
// ports, the start sequence (ADR-002 §6, §8), the credential vault (ADR-002 §2a) and the backup rule files (ADR-002 §5).
plugins {
    id("slussen.android-library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "slussen.persistence"
    // MigrationTestHelper reads the committed schema of every version from the test APK's assets (ADR-002 §7, E-06).
    sourceSets["androidTest"].assets.srcDir("schemas")
}

// Room writes the schema it generates for each database into the build directory, one directory per variant, never over
// the committed files under persistence/schemas/: those are final once committed and are the reference of the schema
// check (ADR-002 §7, S-39). Each KSP task declares its directory as an output, so two variants never share a file.
val generatedRoomSchemas = layout.buildDirectory.dir("generated/room-schemas")
val committedRoomSchemas = layout.projectDirectory.dir("schemas")

/** `room.schemaLocation` for one KSP task, with the directory as the task's output. */
class RoomSchemaLocation(@get:OutputDirectory val directory: Provider<Directory>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${directory.get().asFile.path}")
}

ksp {
    arg("room.generateKotlin", "true")
}

tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    val variant = name.removePrefix("ksp").removeSuffix("Kotlin").replaceFirstChar { it.lowercase() }
    commandLineArgumentProviders.add(RoomSchemaLocation(generatedRoomSchemas.map { it.dir(variant) }))
}

// The schema check of ADR-002 §7 (SchemaVersionCheckTest) and the JVM migration tests compare the schemas the variant
// under test generates with the committed ones.
tasks.withType<Test>().configureEach {
    val variant = name.removePrefix("test").removeSuffix("UnitTest").replaceFirstChar { it.lowercase() }
    inputs.dir(committedRoomSchemas).withPropertyName("committedRoomSchemas").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("slussen.schemas.generated", generatedRoomSchemas.get().dir(variant).asFile.path)
    systemProperty("slussen.schemas.committed", committedRoomSchemas.asFile.path)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.room.compiler)

    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The JVM migration tests run each Migration on SQLite itself (ADR-002 §7, S-39).
    testImplementation(libs.androidx.sqlite.bundled.jvm)

    // Instrumented tests on JUnit 5 through the android-junit5 plugin (ADR-005 §3): the PersistenceIT classes and the
    // device evidence E-13 (ADR-005 §7).
    androidTestImplementation(testFixtures(project(":plugin-api")))
    androidTestImplementation(project(":testing"))
    androidTestImplementation(platform(libs.junit.bom))
    androidTestImplementation(libs.junit.jupiter.api)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.junit5.android.test.core)
    androidTestRuntimeOnly(libs.junit5.android.test.runner)
}
