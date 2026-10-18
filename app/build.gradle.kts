// :app (ADR-001 §1): Compose UI and ViewModels, the composition root, and the platform adapters. No configuration
// is generated into it (ADR-003 §3). The module skeleton holds a placeholder launcher screen only.
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
