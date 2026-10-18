// :provider-simplefin (ADR-001 §1): the SimpleFIN Data Provider. It depends on the plug-in contracts only (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    implementation(project(":plugin-api"))

    testImplementation(testFixtures(project(":plugin-api")))
}
