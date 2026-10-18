// :plugin-api (ADR-001 §1): the three plug-in contracts, their value types, the request context, the diagnostic
// logging contract and the Standard Import Format. Its testFixtures hold the contract tests and @UseCase (ADR-004).
plugins {
    id("slussen.kotlin-library")
    `java-test-fixtures`
}

kotlin {
    // The contract surface is deliberate (ADR-005 §1).
    explicitApi()
}

dependencies {
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)
}
