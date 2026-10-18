// The included build of ADR-001 §1: convention plugins, the module-graph check (§6.1), the task graph (§6.4), the
// release signing and no-credential checks (ADR-003) and the version name (ADR-005 §4). It is not an app module.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
