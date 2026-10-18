// :provider-enablebanking (ADR-001 §1): the Enable Banking Data Provider, including its Bank Consent capability. It depends on the plug-in contracts only (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    implementation(project(":plugin-api"))

    testImplementation(testFixtures(project(":plugin-api")))
}
