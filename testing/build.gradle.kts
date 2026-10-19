// :testing (ADR-001 §1): test-only in-memory fakes of the core's ports and of the plug-in contracts, invented example
// data, and the slice wired on them as the composition root wires it. It is never on a production classpath
// (ADR-001 §2).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
}
