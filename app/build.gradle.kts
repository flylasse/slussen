// :app (ADR-001 §1): Compose UI and ViewModels, the composition root, and the platform adapters. No configuration
// is generated into it (ADR-003 §3).
plugins {
    id("slussen.android-application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "slussen.app"
    defaultConfig {
        applicationId = "slussen.app"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":persistence"))
    implementation(project(":provider-simplefin"))
    implementation(project(":provider-enablebanking"))
    implementation(project(":budgetapp-ynab"))
    implementation(project(":fileformat-hsa-csv"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The screen tests need the test activity in the debug manifest (ADR-005 §3).
    debugImplementation(libs.compose.ui.test.manifest)

    // ViewModel tests on the JVM against the fakes of :testing (docs/architecture/development.md, Integration layer).
    testImplementation(project(":testing"))
    testImplementation(testFixtures(project(":plugin-api")))
    testImplementation(libs.kotlinx.coroutines.test)

    // Screen tests on a device or emulator, JUnit 5 with the Compose extension of android-junit5 (ADR-005 §3).
    androidTestImplementation(project(":testing"))
    androidTestImplementation(testFixtures(project(":plugin-api")))
    androidTestImplementation(platform(libs.junit.bom))
    androidTestImplementation(libs.junit.jupiter.api)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit5.android.test.core)
    androidTestImplementation(libs.junit5.android.test.compose)
    androidTestRuntimeOnly(libs.junit5.android.test.runner)
}
