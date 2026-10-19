// :persistence (ADR-001 §1): the Room databases of ADR-002, their DAOs, the implementations of the core's storage
// ports, the start sequence (ADR-002 §6, §8), the credential vault (ADR-002 §2a) and the backup rule files (ADR-002 §5).
plugins {
    id("slussen.android-library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "slussen.persistence"
}

ksp {
    // Every schema version of each database is committed under persistence/schemas/ (ADR-002 §7).
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
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

    // Instrumented tests on JUnit 5 through the android-junit5 plugin (ADR-005 §3): the PersistenceIT classes and the
    // device evidence E-13 (ADR-005 §7).
    androidTestImplementation(testFixtures(project(":plugin-api")))
    androidTestImplementation(project(":testing"))
    androidTestImplementation(platform(libs.junit.bom))
    androidTestImplementation(libs.junit.jupiter.api)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit5.android.test.core)
    androidTestRuntimeOnly(libs.junit5.android.test.runner)
}
