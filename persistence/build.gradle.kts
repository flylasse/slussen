// :persistence (ADR-001 §1): the Room databases of ADR-002, their DAOs, the implementations of the core's storage
// ports, the credential vault (ADR-002 §2a) and the backup rule files (ADR-002 §5).
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
    ksp(libs.room.compiler)
}
