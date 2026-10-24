// :core (ADR-001 §1): every rule that fetches, imports, compares, reviews, decides, plans, uploads and reports, and
// the ports that :persistence and :app implement. It depends on the plug-in contracts only (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    api(project(":plugin-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testing"))
    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.kotlinx.coroutines.test)
}
