# ADR-005: Toolchain, Android SDK, Test Runner and Version Name

## Status

Proposed (2026-10-14, construction kickoff Phase 1). The Operator sets it to Accepted at the architecture gate.

## Context

- C-001: Slussen runs natively on the Operator's Android phone. C-003: it is installed directly on the phone.
- NFR-017, FR-042: upgrading to a newer version of the same build keeps every stored item. Android installs an
  upgrade over an installed app only when the new `versionCode` is higher and the signing key is the same.
- `AGENTS.md` §5: `VERSION` (repository root, plain `major.minor.revision`) is the single source of the app's version
  name; the build reads it directly; every PR bumps it.
- ADR-001 to ADR-004 need a build in which every module, including the Android ones, is always compiled and checked,
  and tests run on JUnit 5.
- Evidence from the Predecessor App (`scratch/construction_inventory.md` §1-2), used as evidence and not as a
  template: Gradle 8.14.1, Kotlin 2.3.21, Android Gradle Plugin 8.12.0, Compose BOM 2025.06.00, JUnit Jupiter 5.11.4,
  JVM 17, `compileSdk` and `targetSdk` 35, `minSdk` 26, an `arm64-v8a` split; `:platform` and `:app` were left out of
  the build, through no-op plugin stubs, when no Android SDK was installed.
- Construction kickoff plan §2: JDK 17 and an Android SDK for `targetSdk` 35 are prerequisites on the Operator's
  machine.

## Decision

### 1. Toolchain

- One version catalog, `gradle/libs.versions.toml`, holds every plugin and library version. No version appears in a
  `build.gradle.kts`.
- Phase 6 starts from the Predecessor App's versions above (JDK 17, Kotlin 2.3.21, AGP 8.12.0, Gradle 8.14.1, Compose
  BOM 2025.06.00, JUnit 5.11.4, `compileSdk` and `targetSdk` 35, `minSdk` 26) and adds, each pinned to one exact
  version compatible with them: Room and KSP (ADR-002), Konsist (ADR-001), WorkManager, `androidx.browser`, OkHttp and
  `kotlinx-serialization-json` (plug-ins), and the `de.mannodermaus.android-junit5` Gradle plugin (§3). A newer
  version is taken only where the pinned Kotlin or AGP requires it; Phase 6 records each such choice.
- All modules compile for JVM 17. Kotlin's `explicitApi()` is on for `:plugin-api` so the contract surface is
  deliberate.
- Gradle convention plugins in `build-logic/` (ADR-001) set these once: `slussen.kotlin-library`,
  `slussen.android-library`, `slussen.android-application`.

### 2. The Android SDK is required; nothing is left out of the build

Every module of ADR-001 is in `settings.gradle.kts` unconditionally. There are no plugin stubs. A machine without an
Android SDK fails at configuration with the message that `ANDROID_HOME` or `sdk.dir` in `local.properties` must point
to an Android SDK. Reason: the module-graph check, the Konsist rules and the compile of `:persistence` and `:app` must
never pass by not running.

### 3. Test runner

- JUnit 5 (Jupiter) everywhere, through `useJUnitPlatform()` for JVM modules and the `de.mannodermaus.android-junit5`
  plugin for the local and instrumented tests of `:persistence` and `:app`, including Compose UI tests through that
  plugin's Compose extension.
- `gradlew check` runs: the compile of every module, every JVM test (local unit tests of the Android modules
  included), the Konsist and traceability checks of `:architecture-tests`, `verifyModuleGraph` (ADR-001 §6.1) and
  `verifyBuildConfigurationPlacement` (ADR-003 §5). Tests tagged `live` are excluded unless `-PincludeLive`
  (ADR-004).
- Instrumented tests (`src/androidTest`) run with `gradlew connectedCheck` on a connected device or emulator; they
  are not part of `check`, because no device is guaranteed where `check` runs.
- If Phase 6 finds that JUnit 5 cannot run the instrumented or Compose tests with the pinned AGP, it stops and
  reports, instead of switching those tests to JUnit 4 (the markers of ADR-004 assume JUnit 5).

### 4. Version name and version code

`build-logic/` reads `VERSION` at the repository root:

- It must be exactly one line `major.minor.revision`, each part a whole number from 0 to 999; otherwise the build
  fails naming the file.
- `versionName` = the content of `VERSION`.
- `versionCode` = major × 1,000,000 + minor × 1,000 + revision. So `0.1.0` is `1000`, and every bump of
  `VERSION` gives a higher `versionCode`, which Android needs to install an upgrade in place (NFR-017).
- `VERSION` is read nowhere else. The app shows `versionName` from Android's package information.

### 5. Application identity

- `applicationId` and the namespace of `:app` are `slussen.app`; each other Android module's namespace is its
  package root (ADR-001 §1).
- The app ships for `arm64-v8a` only when it has native code; Slussen's own code has none, so no ABI split is
  configured unless a library adds native code.

### 6. Variant and signing (open)

Which variant the Operator installs, and with which signing key, decides whether a build from another machine can
upgrade the installed app in place (NFR-017). This is reported as an open question (O-P1-08), with the recommended
answer: the `release` variant, signed with the Operator's own key, whose keystore path and passwords are kept in
`build-config/local/signing.properties` under the same rules as the Build Configuration (ADR-003). Until the Operator
answers, Phase 6 configures only the `debug` variant, signed with the machine's default debug key.

## Consequences

- Every machine and every agent that builds Slussen needs JDK 17 and an Android SDK (plan §2 prerequisite).
- A green `gradlew check` means every module compiled and every architecture rule ran; nothing is skipped silently.
- Instrumented tests need a device. Phases that require them (for example the persistence test of ADR-002 §3) state
  it in their gate.
- `VERSION` parts above 999 fail the build; at the pace of `AGENTS.md` §5 that limit is far away, and raising it is an
  amendment of this ADR.
- Until O-P1-08 is answered, installing a build from a different machine requires uninstalling first, which removes
  the stored data unless a device backup is restored.

## Alternatives

- **The Predecessor App's SDK-conditional modules with plugin stubs.** Rejected: a build without an SDK passed while
  the Android modules were never compiled or checked.
- **`versionCode` from the Git commit count or a timestamp.** Rejected: `AGENTS.md` §5 makes `VERSION` the single
  source, and a commit count differs between a clone and a fork.
- **JUnit 4 for Android tests (the Android default).** Rejected: two test frameworks would need two marker forms;
  ADR-004 has one.
- **Robolectric for persistence and UI tests on the JVM.** Not chosen: it runs on JUnit 4 only. Revisit together
  with the Room Kotlin Multiplatform alternative of ADR-002.

## AIUP guidance

- AIUP prescribes no toolchain; its stack plugins each assume their own (Maven or Gradle with Spring Boot, .NET,
  Node). Nothing in AIUP covers an Android SDK, device-run tests, signing, or how a version name reaches an app.
- `AGENTS.md` §5 (project rule, not AIUP) is the source of the `VERSION` decision.
