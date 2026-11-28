# ADR-005: Toolchain, Android SDK, Test Runner, Version Name, Signing and the Evidence Before Uploads

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); revised for S-23/S-24/S-25 and re-checked externally (2026-10-18, `docs/reviews/mockup_gate_recheck.md`, no High finding).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the Operator's answers O-P1-06, O-P1-07, O-P1-08 and O-P2-05; revised 2026-10-15 after the Phase 2b
re-review; revised for S-23/S-24 on 2026-10-16 and for S-25 on 2026-10-18, pending an external check; later revisions
at the gate are listed under "Review findings addressed".

## Context

- C-001: Slussen runs natively on the Operator's Android phone. C-003: it is installed directly on the phone.
- NFR-017, FR-042: upgrading to a newer build signed with the same key, release or debug, keeps every stored item. Android installs an
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
  keystore means a new installation (and with it First Upload Matching, ADR-002 §6), never an in-place upgrade.
- A `debug` build needs no signing values (UC-012 A5, BR-001): it is signed with the machine's default debug key, is
  for trying out, and the Operator does not keep data in it (UC-012 BR-009): a debug key belongs to one machine, so
  losing it forces an uninstall, which deletes the app's local data, and a debug build is debuggable, so its private
  data can be read over USB. Until the Operator has created the keystore and set the signing values, Phase 6 and later
  phases build `debug` only.
- A built app holds no credential (ADR-003 §3), so a `release` package may be installed by another household, whose
  Operator enters their own credentials (UC-012 A6, C-003); it is not distributed through an app store.

### 7. Evidence that gates the first slice that uploads (A-24, A-25, A-27)

No build that can upload is used for a real Upload until every item below has passed. The gate is the Operator's own:
the app does not check it, and Uploads are built fully enabled, with no switch that turns them off (the Operator's
decision for the build-out, `docs/build_out_plan.md` §6). The items are his checklist before the first real Upload, and
each result (pass, device, date, commit) is recorded in the end checklist of the plan that builds Uploads
(`docs/build_out_plan.md` §7). Who can produce an item: **gate**, a JVM test that `gradlew check` runs; **device**, an
instrumented test (`connectedDebugAndroidTest`) on an emulator with API 26 (the `minSdk`) and on the Operator's phone;
**phone**, a procedure the Operator carries out on his phone; **live**, a test tagged `live` that only the Operator
runs, on his machine with `-PincludeLive`, against a throwaway YNAB Budget he creates for it with invented
transactions (C-011): at least three Budget Accounts in one currency, one of them for a credit card. A live test
that needs a step in YNAB's own screens between two of its stages says what to do and waits. The state named is that
of the branch `build-out` at commit 8c913da; what the wave of Uploads builds is named with its phase.

| ID | Evidence | Who | State at 8c913da | Proves |
|---|---|---|---|---|
| E-01 | The last good copy is made at start, replaced atomically on each later start, and survives repeated starts | device | a JVM test with a fake database check only; the instrumented test comes with the evidence kit (C7) | ADR-002 §8 steps 1-2 |
| E-02 | A failed copy (planted write failure) leaves the previous last good copy in place, and a leftover temporary file is removed | device (API 26) | none; C7 adds the seam that fails the copy | ADR-002 §8 step 2 |
| E-03 | A `slussen.db` with planted garbage bytes, and one with a planted unknown `Values:` text, are kept unchanged with their journal, the last good copy is used, the notice is shown and a First Upload Matching opens; the running-app path (ADR-002 §8 step 4) restarts and recovers, also from a screen that answers storage failures. A damaged `journal.db` and a damaged `device.db` are kept unchanged and show the notice, and open **no** First Upload Matching and block no Upload (S-13) | device | JVM approximations; the running-app path is not built (C7) | ADR-002 §6, §8 steps 3-5; NFR-005, NFR-006 |
| E-04 | With no last good copy, an unreadable database starts empty with the default content | device (API 26) | a JVM test | ADR-002 §8 step 3 |
| E-05 | Each atomic operation O-1 to O-16 leaves nothing visible after a failure injected before its commit; a process killed between O-4 and O-5 leaves the Comparison pending and preparation refused until O-5 is committed (ADR-007 §3a); `confirmPlan` survives a killed process right after it returns, and the next start makes its Sent Records Unknown and sends nothing | device (API 26) | none; C7 adds the seams | ADR-002 §3, §4; ADR-007 §8 |
| E-06 | Migration from every committed schema version keeps every row (S-39): `slussen.db` 1 to 6, `device.db` 1 to 2 | device; a JVM variant in `check` | written for both databases; passed on the phone for `slussen.db` 1-2 on 2026-10-28, the rest not run | ADR-002 §7, NFR-017 |
| E-07 | Backup and restore with `adb shell bmgr` (backup now, uninstall, reinstall, restore): `slussen.db` comes back, `device.db` and `journal.db` do not, the start is detected as Restored Backup, the Bank Transactions and Sent Records are kept (seen as Already In Budget App), First Upload Matching holds Uploads back until a Comparison and confirmation | phone | procedure to be written (C7) | ADR-002 §5, §6; NFR-009, NFR-010, FR-095 |
| E-08 | A new installation opens a First Upload Matching with cause New Installation | device (API 26) | written (`UC008CheckImportStatusPersistenceIT`) | ADR-002 §6 |
| E-09 | An in-place upgrade of the `release` variant built on a second checkout with the same keystore keeps every Sent Record, the `first_sent_at` of every Account Mapping and every decision, compared before and after by the counts of the Budget tab and a Journal Export | phone | procedure to be written (C7) | §6, NFR-017 |
| E-10 | The live tests against the throwaway YNAB Budget, each a fact ADR-001 §4.2.5 marks as confirmed or not documented: (1) a second create with an Import Identifier the Budget Account holds is answered as a duplicate, alone (`409`) and in a list (`duplicate_import_ids`), and is `AlreadyPresent`; (2) after the transaction is deleted, a create with the same Import Identifier is still refused (if it is accepted, a new installation or Start Over can bring back once a transaction the Operator deleted, UC-006 BR-017, and the Operator decides, G-44); (3) the same Import Identifier in two Budget Accounts of one Budget is accepted in both; (4) a list holding one item YNAB refuses (a future date) is refused, the single sends that follow give `Rejected` for that item only, and nothing of the list was stored twice; (5) a linked transfer through the other Budget Account's transfer payee creates the other side, linked both ways, with no Import Identifier on it, and the test prints its `cleared` and `approved`; `readTransaction` right after the write returns the leg with its link, and the counterpart is marked Cleared in the same Upload; (6) the same with a hand-typed transfer of the same amount already in both Budget Accounts: whether YNAB matches it or a second pair appears (Q-04); (7) matching to a Typed Entry at 10 days before and after, and not at 11; (8) a match approved in YNAB's screens between two stages, then read by `last_knowledge_of_server`: which transaction remains, which is reported deleted, and that the reader gives Merged; (9) a deletion is reported by `last_knowledge_of_server` and not by `since_date`; (10) marking Cleared changes nothing else (memo, payee, category, flag, `approved`); (11) deleting one side of a linked transfer deletes the other, and both are reported; (12) created transactions await approval; (13) a create to a closed Budget Account is refused; (14) YNAB's own Bank import writes Import Identifiers that do not begin with Slussen's marker (this one needs a linked bank: a read-only live test against the Operator's real Budget that prints only the first four characters of each Import Identifier it finds); (15) a read from an old cursor, as after a restore: a first run reads the throwaway Budget's changes, saves that cursor in a file under the checkout's `build/` folder (no secret, never committed), creates two transactions and reads again; the Operator deletes one of them in YNAB's screens; a second run, the next day, reads from the saved cursor and must report the deletion and the other transaction, otherwise a restore can miss deletions and the rule of UC-003 BR-016 for a start that opens a First Upload Matching is all that protects; (16) the `/budgets/{budget_id}` paths the part uses and the documented `/plans/{plan_id}` paths answer the same transactions of the throwaway Budget (G-44); (17) a create to the throwaway Budget naming a Budget Account of another Budget of the same YNAB user (a second throwaway Budget, which this case needs) is refused by YNAB, and the test prints YNAB's status and code; the writer sends no such item and answers `Unknown` (S-B-79) | live | plumbing only (`-PincludeLive`, tag `live`); the tests come with the YNAB writer (C2) | ADR-001 §4.2.1, §4.2.2, §4.2.5; ADR-008 §1; S-05 |
| E-11 | The contract tests' timeout and cancellation cases pass against each plug-in, also for `create`, `markCleared` and `readTransaction` | gate | built for the credential checks and the reads; the writer's come with C2 | ADR-001 §4.0 |
| E-12 | The start sequence is interrupted (process killed) at each boundary of ADR-002 §6, for a restored backup, a new installation, a last good copy and Start Over: every next start finds the start intent, finishes the work and opens a First Upload Matching; none looks like a normal start | device (API 26) | one JVM test (resume from the intent) and `UC010StartOverPersistenceIT`; C7 adds a seam per boundary | ADR-002 §3 O-17, §6, FR-095 |
| E-13 | Service credentials entered in the app are stored only as ciphertext in `device.db` under a non-exportable Android Keystore key; after a backup and restore with `adb shell bmgr` they are absent and "credentials missing" is shown; a planted ciphertext that cannot be decrypted is treated as missing credentials | device and phone | passed on the phone on 2026-10-28 for `device.db` version 1; to run again on version 2 | ADR-002 §2a, §5; NFR-021, UC-014 BR-003, BR-004 |

Each item's test or procedure carries the markers of ADR-004. A failing item stops the first real Upload, not the
build; a mechanism that fails on the device is changed by amending ADR-002, a behaviour of YNAB that differs from
ADR-001 §4.2.5 by amending that section and the rules that rest on it, not worked around.

## Consequences

- Every machine and every agent that builds Slussen needs JDK 17 and an Android SDK (plan §2 prerequisite).
- A green `gradlew check` means every module compiled, every architecture rule ran, a `release` package without
  signing values is refused, and no credential can enter a built app; nothing is skipped silently. It does not mean the device or live evidence passed; that is
  §7, and it gates Uploads, not the first slice.
- Instrumented tests need a device; phone and live items need the Operator.
- `VERSION` parts above 999 fail the build; at the pace of `AGENTS.md` §5 that limit is far away, and raising it is an
  amendment of this ADR.
- The release keystore is the identity of the Operator's installation: its backup is the Operator's job, and losing it
  costs a reinstall with restore (and First Upload Matching), never silent data loss.
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
- Phase 9 acceptance (2026-10-24): S-41 (P9-17, O-P2-75; the glossary term Recovery Check renamed First Upload
  Matching): §6, §7 E-03, E-07, E-08, E-12 and Consequences renamed; no evidence changes.
- Backlog decisions (2026-10-27, issue #13): Context says "a newer build signed with the same key" instead of "the
  same build", as NFR-017 does; no decision changes.
- Build-out, phase W4-0 (2026-11-28): S-B-76: §7 (the gate is the Operator's own, with no switch in the app; who can
  produce each item and its state; E-03, E-05, E-06, E-07, E-09, E-11, E-12 brought up to date, E-09 names
  `first_sent_at` in place of the lock; E-10 lists one live test per fact of ADR-001 §4.2.5, the linked transfer onto a
  typed one of Q-04 among them; E-12 before E-13).
- Build-out, the review of phase W4-0 (2026-11-28): §7 E-10 (2) says what follows if it fails; (15) a read from an old
  cursor, in two runs; (16) the two forms of YNAB's paths.
