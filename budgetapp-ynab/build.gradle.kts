// :budgetapp-ynab (ADR-001 §1): the YNAB Budget App: everything that depends on YNAB's behaviour (C-009). It depends on the plug-in contracts only (ADR-001 §2).
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
