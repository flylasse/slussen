// :provider-simplefin (ADR-001 §1): the SimpleFIN Data Provider. It depends on the plug-in contracts only (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.okhttp.mockwebserver)
}
