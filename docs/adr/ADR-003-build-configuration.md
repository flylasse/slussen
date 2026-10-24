# ADR-003: Release Signing From Standard Gradle Properties, and No Credential in the Repository or a Built App

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); revised for S-23/S-24/S-25 and re-checked externally (2026-10-18, `docs/reviews/mockup_gate_recheck.md`, no High finding).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the specification changes S-01, S-03 and S-10; revised 2026-10-15 after the Phase 2b re-review;
revised for S-23/S-24 on 2026-10-16; rewritten for S-25 on 2026-10-18 (the Build Configuration, its templates and its
placement checks are dropped), pending an external check. The file name stays `ADR-003-build-configuration.md` so that
links from the reviews and the plan keep working; the title changed with S-23/S-24 (it was "Build Configuration
Location, Format, Checks and Secrecy") and again with S-25 (it was "The Optional Build Configuration for Signing, and No
Credential in the Repository or a Built App").

## Context

- FR-071, UC-012 steps 2-4, A1, A5, BR-001, BR-002, BR-009: only an own release build needs signing values (where the
  signing key is kept, its alias and its passwords); they are kept outside the repository; an own release build
  without them fails, naming each missing value and where it goes; a debug build, or an app someone else built, needs
  none. The Operator keeps their data in an own release build.
- FR-070: the repository's how-to names each signing value and where it goes; the app states what each Data Provider
  and the Budget App need (UC-014 BR-002).
- UC-012 BR-003, BR-012, C-003, NFR-021, UC-014 BR-001, BR-003: no service credential is ever in the repository, among
  the signing values or in a built app; the build has no input for one. Credentials are entered by the Operator in the
  app (UC-014) and stored only on the device, encrypted (ADR-002 §2a). A built app may therefore be installed by
  someone who did not build it.
- UC-012 BR-011, UC-013, UC-009: nothing but the signing values comes from outside the app; Budgets, Bank Accounts,
  Account Mappings and Start Dates are set in the app, and the Request Limit and the default Fetch Cooldown are
  declared by each Data Provider's part (ADR-001 §4.1).
- C-004, C-005; NFR-008 and vision *Sensitive data*: credentials are never logged.
- S-25 (the Operator, 2026-10-18): after S-23/S-24 the Build Configuration held only the release signing setup, so it
  is dropped as a concept, together with its Configuration Template, its ignored folder and its placement checks, in
  favour of Android's standard signing setup from Gradle properties. An own release build is still recommended,
  because updates must be signed with the same key (a debug key belongs to one machine, and losing the key forces an
  uninstall, which deletes the app's local data: history, Sent Records, credentials), and because a debug build is
  debuggable, so its private data can be read over USB. A misplaced signing password has low impact, because the
  keystore file is needed as well, so no detection of misplaced signing values is needed.
- The Operator's decisions O-P1-07 (live-test credentials, never run by agents, ADR-004; their place moved by S-25,
  §4) and O-P1-08 and O-P2-05 (release signing with a keystore outside the repository, ADR-005 §6).

Evidence from the Predecessor App, used as evidence and not as a template: credentials lived in an ignored `secrets/`
directory and in `local.properties`, and reached the app as assets, so every built app carried its builder's
credentials.

## Decision

### 1. Where the signing values come from

The release signing values are four Gradle properties, read by `build-logic/` only through Gradle's own property
lookup (`providers.gradleProperty`):

| Gradle property | Holds |
|---|---|
| `slussenReleaseStoreFile` | the absolute path of the release keystore, outside the repository |
| `slussenReleaseStorePassword` | the password of the keystore |
| `slussenReleaseKeyAlias` | the alias of the signing key in the keystore |
| `slussenReleaseKeyPassword` | the password of the signing key |

They are set in one of Gradle's standard places outside the repository:

- the user's `~/.gradle/gradle.properties` (or `gradle.properties` in `GRADLE_USER_HOME`), the recommended place; or
- environment variables named `ORG_GRADLE_PROJECT_<property>`, for example `ORG_GRADLE_PROJECT_slussenReleaseKeyAlias`,
  for a build machine that sets them per run.

Nothing about signing lives in the repository or in a folder of it: there is no template, no ignored configuration
folder and no signing file in the working tree. The repository's own `gradle.properties` never holds a signing value;
the README how-to names the four properties and where they go (FR-070, plan Phase 8). The build does not search for
signing values placed elsewhere (S-25). `local.properties` stays ignored, but only for `sdk.dir`.

### 2. The release build checks its signing values

Task `verifyReleaseSigning` in `build-logic/` is a dependency of every packaging task of the `release` variant of
`:app`, and of nothing else (UC-012 step 4, A1, BR-002):

- **Debug and `check` need nothing.** Compiling, `gradlew check`, and packaging or installing the `debug` variant never
  require the four properties, never fail for their absence (UC-012 A5), and never print, package or record their
  values. A `debug` package is signed with the machine's default debug key (ADR-005 §6). Only the signing
  configuration of the `release` variant and `verifyReleaseSigning` use the values. The Android Gradle Plugin takes its
  signing configuration when the build is configured, so the build looks the properties up then, in every build;
  looking them up uses no value outside the release signing.
- **A release package needs all four.** When one or more of them is missing or empty, the task fails and names each
  missing property and where it goes, for example "Missing `slussenReleaseKeyAlias`: set it in
  `~/.gradle/gradle.properties` or as the environment variable `ORG_GRADLE_PROJECT_slussenReleaseKeyAlias`; see the
  README". It never falls back to another key.
- It also fails when `slussenReleaseStoreFile` is not an absolute path, names no readable file, or resolves inside the
  repository working tree, naming the property and never its value: the keystore, the one thing without which a
  misplaced password is harmless, stays outside the repository (ADR-005 §6).

The values reach only the Android signing configuration of the `release` variant; they are never packaged.

### 3. No credential in the repository or a built app

UC-012 BR-012, BR-003, NFR-021, C-003:

- **The build has no input for a credential.** No task of `build-logic/` reads a service credential, and none
  generates Kotlin, `BuildConfig` fields, resources, assets or manifest entries from a Gradle property or an
  environment variable. There is no `PluginSettings` and no generated configuration in any module (ADR-001 §4).
- **Credentials exist only at run time,** entered by the Operator in the app (UC-014), checked with the service, and
  stored on the device encrypted with a key that never leaves it (ADR-002 §2a). A built app therefore holds no
  credential, and an app someone else built can be installed (UC-012 A6).
- **Proof, a test in `check`:** the Gradle TestKit test `NoCredentialInAppTest` of `build-logic/` builds the `debug`
  package of a fixture project that uses the same convention plugins, with the four signing properties and the
  live-test properties of §4 set to invented values that are easy to search for, and asserts that no such value, and
  no name of those properties, appears in any entry of the package (dex files, resources, assets, manifest), compared
  as bytes in UTF-8 and UTF-16, and that none of the values appears in the build's output at any log level (§5).

### 4. Live-test credentials (moved by S-25)

The tests against a real throwaway YNAB Budget (C-011, ADR-004 §2) take their credentials from Gradle properties in
the same standard places as §1 (`~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_<property>`):
`slussenLiveTestYnabAccessToken` (a personal access token of the throwaway Budget's YNAB user) and
`slussenLiveTestYnabBudgetId` (the throwaway Budget). They are test-only, never service credentials of an
installation:

- Only the test task run with `-PincludeLive` reads them and hands them to the tests tagged `live`; no compile,
  packaging or install task reads them, so they never reach a built app (§3).
- A live test that finds a property missing fails with a message naming it and where it goes, instead of passing
  silently (ADR-004 §2).
- The earlier test-only file `build-config/local/live-test.properties` and its template are retired.

### 5. Never logged, never printed

- No message of `build-logic/` (failure, warning or Gradle log at any level) contains a signing value or a live-test
  credential; messages name the property.
- Signing passwords are never put into `BuildConfig` fields, resources or the manifest.
- Run-time logging rules for service credentials are in ADR-006.

### 6. Tests

Gradle TestKit tests in `build-logic/`, all run in `check` (ADR-001 §6.4), each on a small fixture project that uses
the same convention plugins and holds only invented values:

- without any signing property: `check` and `:app:assembleDebug` pass (UC-012 A5);
- without any signing property: `:app:assembleRelease` fails, naming each of the four properties and where it goes
  (UC-012 A1, BR-002);
- `NoCredentialInAppTest` (§3).

### 7. Who touches what

The Operator and every Adopting Developer set their own signing values, and the Operator the live-test credentials,
by hand outside the repository (UC-012 BR-010). No agent reads, writes, lists or prints the user's
`~/.gradle/gradle.properties`, the signing values or the live-test credentials. Agents verify with `gradlew check`,
which includes the tests of §6, and may build a `debug` package, which needs no private data; only the Operator builds
a `release` package with the real signing values or runs the live tests (O-P1-06, O-P1-07; ADR-005 §3).

## Consequences

- An app holds no credential, so one build can serve several households: each Operator enters their own credentials
  in the app (UC-014), and an Adopting Developer may install an app someone else built (UC-012 A6).
- Agents and continuous integration can build and install a `debug` package without any private data.
- Signing follows the setup Android developers already know; there is no Slussen-specific file, template, folder or
  placement check to learn or maintain.
- A signing password pasted into a tracked file is not detected by the build; C-005 and the review of each pull
  request remain the safeguard. The impact is low: the keystore, which the build keeps outside the repository (§2), is
  needed as well (S-25).
- Values in `~/.gradle/gradle.properties` apply to every Gradle build of that user; the `slussen` prefix keeps them
  apart from other projects' properties.
- Adding a Data Provider or replacing the Budget App adds no build input; its credential form lives in its part
  (ADR-001 §4.5).
- Renaming one of the properties is a change for every Adopting Developer; the names are decided here and changed
  only by amending this ADR.

## Alternatives

- **The Predecessor App's `secrets/` directory and `local.properties`.** Rejected: mixes Android SDK settings with
  credentials, and every built app carried its builder's credentials.
- **Credentials and Bank Accounts in a Build Configuration, compiled into the app (the Phase 1-2 decision).**
  Rejected by S-23 and S-24: every change needed a rebuild, and a built app carried its builder's credentials.
- **A signing-only Build Configuration in the ignored folder `build-config/local/`, with a Configuration Template and
  placement checks P-1 to P-6 (the S-23/S-24 decision).** Rejected by S-25: a Slussen-specific concept, folder and set
  of checks for what Android's standard signing setup already does, guarding against a misplaced signing password whose
  impact is low.
- **A `key.properties` file in the repository working tree, ignored by Git (the pattern of some Android guides).**
  Rejected: one wrong ignore line publishes it; outside the repository it cannot be committed at all.
- **Signing values on the command line (`-P...`).** Not recommended: passwords end up in shell history. Gradle accepts
  them, so the build does not refuse them.

## AIUP guidance

- No AIUP skill covers build-time signing or checks run by a build tool. UC-012 is the only specification of it;
  everything here beyond UC-012's rules is project-made.
- AIUP's stack plugins assume a server application configured at deploy time; none covers an app whose credentials are
  entered on the device.

## Review findings addressed

Entries before S-25 refer to sections of earlier versions of this ADR; where they concern the Build Configuration, its
templates or its placement checks, S-23/S-24 or S-25 supersede them.

- A-05 (with S-03, S-10): superseded by S-23 (the generated identifier of ADR-002 §2 and ADR-008 §7 replaces the
  Configuration Key).
- A-16: superseded by S-25 (the canonical path, namespaced keys, `# secret` and the checks P-2 to P-6 are dropped
  with the Build Configuration).
- A-22: superseded by S-23 (no Budget or Bank Account in the Build Configuration).
- A-26 (with S-01): the file upload window is an Operator Setting; nothing about it is a build input.
- A-28 (with O-P1-06): §2 (`check` and `debug` need no signing values), §6, §7.
- A-25 and A-27 in part: §1 (signing values, O-P1-08, O-P2-05) and §4 (live-test credentials, O-P1-07).
- B-12: §2 (absent signing values are valid for compiling, `check` and `debug`).
- B-13: superseded by S-25 (no properties parser, no renamed-fragment checks).
- The Operator's decision of 2026-10-15 (an optional nested private Git repository in `build-config/local/`):
  superseded by S-25; the folder is no longer used by the build.
- S-23/S-24 (2026-10-16): the Build Configuration was reduced to the signing setup; §3 adds the rule and the test that
  no credential can enter the repository or a built app.
- S-25 (2026-10-18, re-checked externally 2026-10-18): title, Status, Context and Decision rewritten. The Build Configuration, the
  Configuration Template, `build-config/` and `build-config/local/`, the tasks `verifyBuildConfiguration` and
  `verifyBuildConfigurationPlacement`, the rules P-1 to P-6 and the tests of a misplaced, partial or nested
  configuration are dropped. §1: the four signing values are Gradle properties from `~/.gradle/gradle.properties` or
  environment variables; §2: only a release package needs them and fails clearly without them; §3: unchanged rule,
  `NoCredentialInAppTest` with properties instead of files; §4: the live-test credentials moved to Gradle properties,
  read only by the live-test task.
- Phase 6 (2026-10-18): O-P2-41: §2 "never read" replaced by what the Android Gradle Plugin allows: debug and `check`
  builds never require, fail on, print, package or record the signing values; only the release signing and
  `verifyReleaseSigning` use them; the properties are looked up when the build is configured.
