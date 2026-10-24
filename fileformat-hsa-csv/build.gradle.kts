// :fileformat-hsa-csv (ADR-001 §1): the one File Pre-processor of this build (C-010), named after the export format it reads (O-P1-01). It depends on the plug-in contracts only (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    implementation(project(":plugin-api"))

    testImplementation(testFixtures(project(":plugin-api")))
}
