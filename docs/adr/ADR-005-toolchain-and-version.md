# ADR-005: Toolchain, Android SDK, Test Runner, Version Name, Signing and the Evidence Before Uploads

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); revised for S-23/S-24/S-25 and re-checked externally (2026-10-18, `docs/reviews/mockup_gate_recheck.md`, no High finding).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the Operator's answers O-P1-06, O-P1-07, O-P1-08 and O-P2-05; revised 2026-10-15 after the Phase 2b
re-review; revised for S-23/S-24 on 2026-10-16 and for S-25 on 2026-10-18, pending an external check; later revisions
at the gate are listed under "Review findings addressed".

## Context

- C-001: Slussen runs natively on the Operator's Android phone. C-003: it is installed directly on the phone.
- NFR-017, FR-042: upgrading to a newer version of the same build keeps every stored item. Android installs an
  upgrade over an installed app only when the new `versionCode` is higher and the signing key is the same.
- C-011: every guarantee that depends on YNAB's behaviour is shown by a test against a real, throwaway YNAB Budget
  before Uploads that rely on it are trusted. S-05: automatic resending of an unknown outcome depends on the Budget
  App refusing a reused Import Identifier.
- `AGENTS.md` §5: `VERSION` (repository root, plain `major.minor.revision`) is the single source of the app's version
  name; the build reads it directly; every PR bumps it. `AGENTS.md` §3: a phase is complete only when its gate exits 0.
- ADR-001 to ADR-004, ADR-007 and ADR-008 need a build in which every module, including the Android ones, is always
  compiled and checked, and tests run on JUnit 5.
- S-25, UC-012 A1, A5, BR-001, BR-002, BR-009, BR-011, BR-012: only an own release build needs signing values, kept
  outside the repository; it fails clearly without them; a debug build needs none; the Operator keeps their data in an
  own release build; no credential is ever in a built app.
- The Operator's answers: O-P1-06 (agents gate on `gradlew check`; only the Operator runs `gradlew build`; the test
  that packaging fails without a Build Configuration is replaced since S-24, §3), O-P1-07 (live-test credentials in the Operator's ignored
  location, since S-25 Gradle properties outside the repository, ADR-003 §4; tagged, never run by agents), O-P1-08 and O-P2-05 (the `release` variant, signed with a keystore outside
  the repository whose path was in `build-config/local/signing.properties` and since S-25 is a Gradle property outside
  the repository, ADR-003 §1; backed up by the Operator; `debug` only until then).
- Evidence from the Predecessor App (`scratch/construction_inventory.md` §1-2), used as evidence and not as a
  template: Gradle 8.14.1, Kotlin 2.3.21, Android Gradle Plugin 8.12.0, Compose BOM 2025.06.00, JUnit Jupiter 5.11.4,
  JVM 17, `compileSdk` and `targetSdk` 35, `minSdk` 26, an `arm64-v8a` split; the Android modules were left out of
  the build, through no-op plugin stubs, when no Android SDK was installed.
- Construction kickoff plan §2: JDK 17 and an Android SDK for `targetSdk` 35 are prerequisites on the Operator's
  machine.
- The Phase 2 review found that the gates did not run the task graph for targeted builds (A-20), left the upgrade
  guarantee depending on one machine's debug key (A-25), never proved the persistence, restore, migration and Budget
  App guarantees before Uploads (A-27), and could not pass without private data (A-28); and that the last good copy
  relied on an SQLite feature `minSdk` may lack (A-24).

## Decision

### 1. Toolchain

- One version catalog, `gradle/libs.versions.toml`, holds every plugin and library version. No version appears in a
  `build.gradle.kts`.
- Phase 6 starts from the Predecessor App's versions above (JDK 17, Kotlin 2.3.21, AGP 8.12.0, Gradle 8.14.1, Compose
  BOM 2025.06.00, JUnit 5.11.4, `compileSdk` and `targetSdk` 35, `minSdk` 26) and adds, each pinned to one exact
  version compatible with them: Room and KSP (ADR-002), Konsist and ASM (ADR-001), WorkManager, `androidx.browser`,
  OkHttp with `mockwebserver` for tests, and `kotlinx-serialization-json` (plug-ins), and the
  `de.mannodermaus.android-junit5` Gradle plugin (§3). A newer version is taken only where the pinned Kotlin or AGP
  requires it; Phase 6 records each such choice.
- All modules compile for JVM 17. Kotlin's `explicitApi()` is on for `:plugin-api` so the contract surface is
  deliberate.
- Gradle convention plugins in `build-logic/` (ADR-001) set these once: `slussen.kotlin-library`,
  `slussen.android-library`, `slussen.android-application`.
- SQLite is the platform's, through Room's framework open helper; no bundled SQLite driver. Android 8.0 (API 26)
  ships an SQLite without `VACUUM INTO`, so ADR-002 §8 uses no SQLite feature newer than the `minSdk` platform's;
  evidence E-01 to E-04 (§7) proves the mechanism on API 26.

### 2. The Android SDK is required; nothing is left out of the build

Every module of ADR-001 is in `settings.gradle.kts` unconditionally. There are no plugin stubs. A machine without an
Android SDK fails at configuration with the message that `ANDROID_HOME` or `sdk.dir` in `local.properties` must point
to an Android SDK. Reason: the module-graph check, the Konsist and bytecode rules and the compile of `:persistence`
and `:app` must never pass by not running.

### 3. Test runner and gates

- JUnit 5 (Jupiter) everywhere, through `useJUnitPlatform()` for JVM modules and the `de.mannodermaus.android-junit5`
  plugin for the local and instrumented tests of `:persistence` and `:app`, including Compose UI tests through that
  plugin's Compose extension.
- `gradlew check` runs: the compile of every module (each after `verifyModuleGraph`), every JVM test (local unit tests
  of the Android modules included), the Konsist, bytecode and traceability rules of `:architecture-tests`,
  and `check` of `build-logic/` with the TestKit tests of ADR-003 §6, among them `NoCredentialInAppTest` (no
  credential can enter a built app) and the tests that a `debug` package builds without signing values while a
  `release` package fails, naming each missing one. The task graph that also runs these checks for targeted builds and packaging is ADR-001 §6.4. Tests
  tagged `live` are excluded unless `-PincludeLive` (ADR-004).
- **The agent gate** of every construction phase that builds code is `gradlew check`, run without signing values,
  which includes the tests of ADR-003 §6. Agents may also build and install a `debug` package
  (`gradlew :app:assembleDebug`), which needs no private data. Only the Operator builds a `release` package with the
  real signing values, runs `connectedCheck` on the Operator's phone, and runs the live tests (O-P1-06, O-P1-07;
  S-24, S-25).
- Instrumented tests (`src/androidTest`) run with `gradlew connectedCheck` on a connected device or emulator; they
  are not part of `check`, because no device is guaranteed where `check` runs. They are part of the evidence of §7.
- If Phase 6 finds that JUnit 5 cannot run the instrumented or Compose tests with the pinned AGP, it stops and
  reports, instead of switching those tests to JUnit 4 (the markers of ADR-004 assume JUnit 5).

### 4. Version name and version code

`build-logic/` reads `VERSION` at the repository root:

- It must be exactly one line `major.minor.revision`, each part a whole number from 0 to 999; otherwise the build
  fails naming the file.
- `versionName` = the content of `VERSION`.
- `versionCode` = major × 1,000,000 + minor × 1,000 + revision. So `0.1.0` is `1000`, and every bump of
  `VERSION` gives a higher `versionCode`, which Android needs to install an upgrade in place (NFR-017).
- `VERSION` is read nowhere else. The app shows `versionName` from Android's package information, on Settings
  (S-37, UC-010 BR-011), and says "debug build" when its package is the `debug` variant (§5), told by the
  `applicationIdSuffix` `.debug` of its package name; nothing else is generated or written for it.

### 5. Application identity

- `applicationId` and the namespace of `:app` are `slussen.app`; each other Android module's namespace is its
  package root (ADR-001 §1).
- The `debug` variant has `applicationIdSuffix ".debug"`, so a debug build installs beside the release installation
  and can never replace it or its data.
- A `release` package holds native code for `arm64-v8a` only; a `debug` package keeps every ABI (O-P2-40).
  Slussen's own code has no native code, but Compose BOM 2025.06.00 adds `libandroidx.graphics.path.so` for four
  ABIs, and the device evidence of §7 runs a debug build on an `x86_64` emulator. The `slussen.android-application`
  convention sets `ndk.abiFilters` on the `release` build type only.

### 6. Variant and signing (O-P1-08, O-P2-05; A-25)

- The Operator installs and keeps data in the **`release`** variant only. It is signed with the Operator's own
  keystore, which lives **outside the repository**. The keystore path, the key alias and the passwords are the four
  Gradle properties of ADR-003 §1, set in the Operator's `~/.gradle/gradle.properties` or as environment variables,
  never in the repository, never printed and never read by an agent (S-25). They hold no service credential, Budget
  or Bank Account (ADR-003 §3).
- `build-logic/` refuses to package `release` when a signing property is missing, when the keystore path resolves
  inside the repository working tree, or when it names no readable keystore; the message names each property and
  where it goes, never a value (ADR-003 §2, UC-012 A1, BR-002).
- The Operator keeps a backup of the keystore and its passwords outside the device and outside the repository. A lost
  keystore means a new installation (and with it a Recovery Check, ADR-002 §6), never an in-place upgrade.
- A `debug` build needs no signing values (UC-012 A5, BR-001): it is signed with the machine's default debug key, is
  for trying out, and the Operator does not keep data in it (UC-012 BR-009): a debug key belongs to one machine, so
  losing it forces an uninstall, which deletes the app's local data, and a debug build is debuggable, so its private
  data can be read over USB. Until the Operator has created the keystore and set the signing values, Phase 6 and later
  phases build `debug` only.
- A built app holds no credential (ADR-003 §3), so a `release` package may be installed by another household, whose
  Operator enters their own credentials (UC-012 A6, C-003); it is not distributed through an app store.

### 7. Evidence that gates the first slice that uploads (A-24, A-25, A-27)

The first slice that implements UC-006 (Upload to the Budget App) is not complete, and no build that can upload is
installed for real use, until every item below has passed and its result is recorded in that slice's plan. The
UC-012 + UC-008 slice of the construction kickoff plan uploads nothing and does not need them. Device items run with
`gradlew connectedCheck` on an emulator with API 26 (the `minSdk`) and on the Operator's phone; the Operator runs the
phone and live items.

| ID | Evidence | Where | Proves |
|---|---|---|---|
| E-01 | The last good copy is made at start, replaced atomically on each later start, and survives repeated starts | instrumented, API 26 and phone | ADR-002 §8 steps 1-2 |
| E-02 | A failed copy (planted write failure) leaves the previous last good copy in place, and a leftover temporary file is removed | instrumented, API 26 | ADR-002 §8 step 2 |
| E-03 | A `slussen.db` with planted garbage bytes, and one with a planted unknown `Values:` text, are kept unchanged with their journal, the last good copy is used, the notice is shown and a Recovery Check opens; the running-app path restarts and recovers. A damaged `journal.db` and a damaged `device.db` are kept unchanged and show the notice, and open **no** Recovery Check and block no Upload (S-13) | instrumented, API 26 and phone | ADR-002 §6, §8 steps 3-5; NFR-005, NFR-006 |
| E-04 | With no last good copy, an unreadable database starts empty with the default content | instrumented, API 26 | ADR-002 §8 step 3 |
| E-05 | Each atomic operation O-1 to O-14 leaves nothing visible after a failure injected before its commit; a process killed between O-4 and O-5 leaves the Comparison pending and preparation refused until O-5 is committed (ADR-007 §3a); `confirmPlan` survives a killed process right after it returns | instrumented, API 26 | ADR-002 §3, §4 |
| E-06 | Migration from every committed schema version keeps every row (S-39) | instrumented (`MigrationTestHelper`), API 26 | ADR-002 §7, NFR-017 |
| E-07 | Backup and restore with `adb shell bmgr` (backup now, uninstall, reinstall, restore): `slussen.db` comes back, `device.db` and `journal.db` do not, the start is detected as Restored Backup, a Recovery Check blocks Uploads until a Comparison and confirmation | phone | ADR-002 §5, §6; NFR-009, NFR-010, FR-095 |
| E-08 | A new installation opens a Recovery Check with cause New Installation | instrumented, API 26 | ADR-002 §6 |
| E-09 | An in-place upgrade of the `release` variant built on a second checkout with the same keystore keeps every Sent Record, Account Mapping lock and decision | phone | §6, NFR-017 |
| E-10 | The live tests against a throwaway YNAB Budget (C-011): a reused Import Identifier is refused and answered `AlreadyPresent`; matching to a Typed Entry within the Matching Date Window; a deletion and a merge are reported; a linked transfer carries both sides' links, `readTransaction` returns a just-created leg with its link, and the counterpart can be marked Cleared in the same Upload; created transactions await approval; YNAB's own Bank import writes Import Identifiers that do not match Slussen's marker | Operator's machine, `-PincludeLive` | ADR-001 §4.2.1, §4.2.2; ADR-008 §1; S-05 |
| E-11 | The contract tests' timeout and cancellation cases pass against each plug-in (part of `check`) | JVM | ADR-001 §4.0 |
| E-13 | Service credentials entered in the app are stored only as ciphertext in `device.db` under a non-exportable Android Keystore key; after a backup and restore with `adb shell bmgr` they are absent and "credentials missing" is shown; a planted ciphertext that cannot be decrypted is treated as missing credentials | instrumented, API 26 and phone | ADR-002 §2a, §5; NFR-021, UC-014 BR-003, BR-004 |
| E-12 | The start sequence is interrupted (process killed) at each boundary of ADR-002 §6, for a restored backup, a new installation and a last good copy: every next start finds the start intent, finishes the work and opens a Recovery Check; none looks like a normal start | instrumented, API 26 | ADR-002 §6, FR-095 |

Each item's test or procedure is written in the slice that needs it, carries the markers of ADR-004, and its result
(pass, device, date, commit) is recorded in that slice's plan. A failing item stops the slice; a mechanism that
fails on the device is changed by amending ADR-002, not worked around.

## Consequences

- Every machine and every agent that builds Slussen needs JDK 17 and an Android SDK (plan §2 prerequisite).
- A green `gradlew check` means every module compiled, every architecture rule ran, a `release` package without
  signing values is refused, and no credential can enter a built app; nothing is skipped silently. It does not mean the device or live evidence passed; that is
  §7, and it gates Uploads, not the first slice.
- Instrumented tests need a device; phone and live items need the Operator.
- `VERSION` parts above 999 fail the build; at the pace of `AGENTS.md` §5 that limit is far away, and raising it is an
  amendment of this ADR.
- The release keystore is the identity of the Operator's installation: its backup is the Operator's job, and losing it
  costs a reinstall with restore (and a Recovery Check), never silent data loss.
- Debug and release builds can be installed side by side, so trying a debug build never touches the Operator's data.

## Alternatives

- **The Predecessor App's SDK-conditional modules with plugin stubs.** Rejected: a build without an SDK passed while
  the Android modules were never compiled or checked.
- **`versionCode` from the Git commit count or a timestamp.** Rejected: `AGENTS.md` §5 makes `VERSION` the single
  source, and a commit count differs between a clone and a fork.
- **JUnit 4 for Android tests (the Android default).** Rejected: two test frameworks would need two marker forms;
  ADR-004 has one.
- **Robolectric for persistence and UI tests on the JVM.** Not chosen: it runs on JUnit 4 only, and it cannot prove
  the platform SQLite of `minSdk`. Revisit together with the Room Kotlin Multiplatform alternative of ADR-002.
- **The debug key for the Operator's installation.** Rejected (A-25): it ties upgrades to one machine.
- **A keystore inside the repository, ignored by Git.** Rejected: one wrong ignore line publishes it; outside the
  repository it cannot be committed at all.
- **Making `connectedCheck` part of every agent gate.** Not chosen: no device is guaranteed where agents run, and the
  phone items need the Operator; the evidence is required where it matters, before Uploads.

## AIUP guidance

- AIUP prescribes no toolchain; its stack plugins each assume their own (Maven or Gradle with Spring Boot, .NET,
  Node). Nothing in AIUP covers an Android SDK, device-run tests, signing, or how a version name reaches an app.
- `AGENTS.md` §5 (project rule, not AIUP) is the source of the `VERSION` decision.
- AIUP has no gate for evidence that only a device or a real external service can give; §7 is project-made, following
  C-011.

## Review findings addressed

- A-20: §3 (what `check` runs; the task graph for targeted builds is ADR-001 §6.4).
- A-24: §1 (platform SQLite, no `VACUUM INTO`), §7 E-01 to E-04 on API 26.
- A-25 (with O-P1-08, O-P2-05): §5 (debug beside release), §6 (release keystore outside the repository, path in
  `signing.properties`, backed up by the Operator, debug only until then), §7 E-09.
- A-27: §7 (device, crash, restore, migration and live Budget App evidence as an explicit prerequisite of the first
  slice that uploads), with ADR-004 §2 for the live-test credentials.
- A-28 (with O-P1-06): §3 (the agent gate is `gradlew check` with the packaging-refusal test; only the Operator runs
  `build`); ADR-003 §3 and §6 make an absent Build Configuration valid for `check` (B-12).
- B-01, B-04: §7 E-05 and E-10 (the Comparison-pending marker; clearing a new counterpart in the same Upload).
- B-05: §7 E-12 (interruption at every boundary of the start sequence).
- B-06 (with S-13): §7 E-03 (journal and `device.db` damage opens no Recovery Check).
- S-23/S-24 (2026-10-16, re-checked externally 2026-10-18): Context; §3 (the agent gate is `gradlew check` with the tests of ADR-003 §6,
  among them `NoCredentialInAppTest`; agents may build a `debug` package; the packaging-refusal test is replaced); §6
  (`signing.properties` is the whole Build Configuration; a debug build needs none; a release package may be installed
  by another household); §7 E-13 (credentials encrypted on the device, not restored); Consequences.
- S-25 (2026-10-18, re-checked externally 2026-10-18): Context; §3 (`gradlew check` without signing values; no placement task); §6
  (the signing values are Gradle properties outside the repository, ADR-003 §1; a release package without them fails
  naming each; why a debug build does not hold the Operator's data); Consequences.
- Phase 6 (2026-10-18): O-P2-40: §5 (a `release` package holds `arm64-v8a` native code only, a `debug` package every
  ABI, for the `x86_64` emulator of §7).
- Phase 9, first attempt (2026-10-19): S-37 (FR-104, UC-010 BR-011): §4 the app shows `versionName` and, for the
  `debug` variant, that it is a debug build, on Settings.
- Phase 9, first attempt (2026-10-19): S-39 (NFR-017, ADR-002 §7): §7 E-06 migrates from every committed schema
  version, not only from released ones.
