// Every module of ADR-001 §1 is part of the build, always (ADR-005 §2): there is no module that is left out when an
// Android SDK is missing, and no plugin stub.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "slussen"

include(":plugin-api")
include(":core")
include(":provider-simplefin")
include(":provider-enablebanking")
include(":budgetapp-ynab")
include(":fileformat-hsa-csv")
include(":persistence")
include(":app")
include(":testing")
include(":architecture-tests")
