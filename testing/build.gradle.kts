// :testing (ADR-001 §1): test-only in-memory fakes of the core's ports and of the plug-in contracts, and invented
// example data. It is never on a production classpath (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    implementation(project(":core"))
}
