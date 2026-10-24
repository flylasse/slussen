// :architecture-tests (ADR-001 §1): test-only. The Konsist rules (ADR-001 §6.2), the bytecode rules (§6.3) and the
// traceability checks of ADR-004. Its test task reads the compiled classes of every production module and is wired
// into check and packaging by build-logic (ADR-001 §6.4).
plugins {
    id("slussen.kotlin-library")
}

dependencies {
    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.konsist)
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}
