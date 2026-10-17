# ADR-003: The Optional Build Configuration for Signing, and No Credential in the Repository or a Built App

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); re-check pending (S-23/S-24).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the specification changes S-01, S-03 and S-10; revised 2026-10-15 after the Phase 2b re-review;
revised for S-23/S-24 on 2026-10-16, pending an external check. Earlier revisions at the gate are listed under "Review
findings addressed". The file name stays `ADR-003-build-configuration.md`; the title changed with S-23/S-24 (it was
"Build Configuration Location, Format, Checks and Secrecy").

## Context

- Glossary, Build Configuration (changed by S-24): "The optional signing setup of an Adopting Developer's own release
  build; a debug build, or an app someone else built, needs none. It holds no service credentials, Budgets, Bank
  Accounts, Account Mappings or Start Dates; those are entered in the app. It is kept in a location the repository
  ignores, next to the Configuration Templates, never committed, and included in the app when it is built."
- FR-070, FR-071, UC-012 steps 1-5, A1, A5, BR-001, BR-002, BR-011: one Configuration Template, for the signing setup
  of an own release build; the Build Configuration holds only that setup; a debug build or an app someone else built
  needs none; an incomplete Build Configuration, or one with an entry its template does not define, produces no app.
- FR-093, UC-012 A6, BR-009: the build reads the Build Configuration only from the ignored location; a Build
  Configuration, or part of one recognised by its keys or by a value marked secret, found anywhere else is never used,
  and the build fails, stating where the Build Configuration belongs.
- UC-012 BR-003, BR-012, C-003, NFR-021, UC-014 BR-001, BR-003 (S-24): no service credential is ever in the
  repository, the Build Configuration or a built app; the build has no input for one. Credentials are entered by the
  Operator in the app (UC-014) and stored only on the device, encrypted (ADR-002 §2a). A built app may therefore be
  installed by someone who did not build it.
- UC-012 BR-011, UC-013, UC-009 (S-23): Budgets, Bank Accounts, Account Mappings and Start Dates are set in the app; the
  Request Limit and the default Fetch Cooldown are declared by each Data Provider's part (ADR-001 §4.1). The Phase 1-2
  Build Configuration of Budgets, Banks, Bank Accounts, Configuration Keys and credentials no longer exists.
- C-004, C-005; NFR-008 and vision *Sensitive data*: credentials are never logged.
- Construction kickoff plan §1.2: no agent reads, writes or prints the Operator's Build Configuration.
- The Operator's decisions: O-P1-07 (live-test credentials in the Operator's ignored location, never run by agents,
  ADR-004), O-P1-08 and O-P2-05 (release signing, ADR-005), and 2026-10-15 (an optional nested private Git repository
  in `build-config/local/`).

Evidence from the Predecessor App, used as evidence and not as a template: credentials lived in an ignored `secrets/`
directory and in `local.properties`, and reached the app as assets. Nothing refused a configuration found elsewhere,
and every built app carried its builder's credentials.

## Decision

### 1. Location

| Path | Tracked | Holds |
|---|---|---|
| `build-config/` | yes | the Configuration Template `signing.template.properties`, and the test-only template `live-test.template.properties` (ADR-004 §2) |
| `build-config/local/` | **no**, ignored by the line `build-config/local/` in `.gitignore` | the optional Build Configuration `signing.properties`; the Operator's test-only `live-test.properties` |

This is the "location the repository ignores, next to the Configuration Template". Phase 6 removes the inherited
`/secrets/` line, so that exactly one ignored location for configuration exists. `local.properties` stays ignored, but
only for `sdk.dir`; it is never read for configuration.

The path is canonical: the build resolves `build-config/local/` with symbolic links and junctions followed and
requires the result to be exactly `<real path of the repository root>/build-config/local`, and requires that neither
the directory nor any file in it is a link (P-2).

`live-test.properties` is not part of the Build Configuration: it is read only by the live tests of ADR-004 §2, never
by a task that builds an app (§4), and is protected by every rule of §5 like the Build Configuration.

**Other files in `build-config/local/`, and an optional private Git repository** (the Operator's decision of
2026-10-15):

- Files other than the files of §2 may sit in `build-config/local/`. This includes a `.git` folder and a `.gitignore`
  of the Operator's own private Git repository, which the Operator may use, optionally, to version and back up the
  folder. The public repository ignores the whole folder, so nothing of the private repository can be committed to it.
- The build reads there only the files of §2 that lie directly in `build-config/local/`; every other file and every
  subfolder is ignored. A folder that holds only such other files is an absent Build Configuration (§3). The build
  refuses only what §5 names.
- P-2 does **not descend** into `build-config/local/.git/`: its link check and its Git checks apply to
  `build-config/local/` itself and to every file in it outside `.git/`. (A `.git` that is a file, as in a Git worktree,
  is an ordinary file there and is checked like one.) A nested repository therefore never fails P-1 to P-6 or
  packaging: P-1, P-5 and P-6 look only outside `build-config/local/`, and P-3 and P-4 never look inside it.
- This ADR does not prescribe how the Operator backs up the folder. No agent reads, writes or lists it (§8).

### 2. Files and format

Java properties files in UTF-8, read by the build's own line parser, which follows the properties syntax and, unlike
`java.util.Properties`, fails on a key that appears twice.

| Template | File in `build-config/local/` | Read by | Holds |
|---|---|---|---|
| `signing.template.properties` | `signing.properties` (the Build Configuration) | `build-logic/`, only to sign a `release` package (ADR-005 §6) | the path of the release keystore outside the repository, the key alias and the passwords |
| `live-test.template.properties` | `live-test.properties` | the live tests only (ADR-004 §2) | the credentials and Budget reference of a throwaway YNAB Budget |

**Every key carries its file's base name as its first segment** (`signing.`, `live-test.`), so that it is recognisable
wherever it appears (P-4, P-5).

**The template is the schema.** Every non-comment line of a template is `key=<placeholder>`, where the placeholder
is text between `<` and `>` that says what goes there. The comments above it say where to get the value. Two marker
comments apply to the key directly below them: `# optional` makes it optional (every other key is required) and
`# secret` marks a password or credential (P-6, §7).

The keys of `signing.template.properties` (Phase 8 writes the template with its explanations):

```properties
signing.keystorePath=<absolute path of the release keystore, outside the repository>
signing.keyAlias=<alias of the signing key in the keystore>
# secret
signing.keystorePassword=<password of the keystore>
# secret
signing.keyPassword=<password of the signing key>
```

There is no key for a Budget, Bank, Bank Account, Account Mapping, Start Date, Request Limit, Fetch Cooldown or service
credential, and no template per Data Provider or Budget App (S-23, S-24). What each Data Provider and the Budget App
need is stated in the app where the Operator enters it (UC-014 BR-002, ADR-001 §4.5).

### 3. Checks that make a Build Configuration complete

Task `verifyBuildConfiguration` in `build-logic/` (UC-012 step 5, A1). It reads only files that passed
`verifyBuildConfigurationPlacement` (§5). Its result is one of three:

- **Absent**: `build-config/local/` is missing or holds no `signing.properties`. This is not a failure: compiling,
  `gradlew check` and a `debug` package go on without it (UC-012 A5, BR-011); only a `release` package refuses it
  (ADR-005 §6).
- **Valid**: `signing.properties` is present and none of the rules below fails.
- **Failed**: `signing.properties` is present and a rule below fails. The task then fails every build, naming the file
  and key of each problem and never its value (UC-012 A1).

The rules: a key appears twice; a required key is missing; a key is not in the template (an entry the template does
not define, UC-012 A1); a value is still a placeholder; `signing.keystorePath` is relative, does not name a readable
file, or resolves inside the repository working tree (ADR-005 §6). `live-test.properties` is checked the same way, but
only by the live-test task.

### 4. No credential in the repository or a built app (S-24)

UC-012 BR-012, BR-003, NFR-021, C-003:

- **The build has no input for a credential.** No task of `build-logic/` reads a service credential, and none
  generates Kotlin, `BuildConfig` fields, resources, assets or manifest entries from any file of `build-config/local/`.
  The Phase 1-2 `generateBuildConfiguration` task, its generated `BuildConfiguration` and the `PluginSettings` of the
  plug-ins are removed. `signing.properties` reaches only the Android signing configuration of the `release` variant;
  its values are never packaged.
- **Credentials exist only at run time,** entered by the Operator in the app (UC-014), checked with the service, and
  stored on the device encrypted with a key that never leaves it (ADR-002 §2a). A built app therefore holds no
  credential, and an app someone else built can be installed (UC-012 A7).
- **Proof, a test in `check`:** the Gradle TestKit test `NoCredentialInAppTest` of `build-logic/` builds the `debug`
  package of a fixture project that uses the same convention plugins, with a `build-config/local/` holding invented
  `signing.properties` and `live-test.properties` whose `# secret` values are easy to search for, and asserts that no
  such value, and no key of either template, appears in any entry of the package (dex files, resources, assets,
  manifest), compared as bytes in UTF-8 and UTF-16. It also asserts that the debug package builds without any Build
  Configuration (UC-012 A5).

### 5. Refusing a Build Configuration found elsewhere

Task `verifyBuildConfigurationPlacement` in `build-logic/` runs before anything reads configuration: every compile
task of `:app`, `verifyBuildConfiguration`, every packaging task and `check` depend on it. It fails, uses nothing it
found, and states "The Build Configuration belongs in `build-config/local/`, next to the Configuration Template in
`build-config/`" together with the path and the rule that matched (never a value), when (UC-012 A6, BR-009):

| Rule | Finds |
|---|---|
| P-1 | A file whose name is the name of a file of §2 (`<name>.properties` for any `<name>.template.properties`) anywhere in the working tree outside `build-config/local/`. |
| P-2 | `build-config/local/` that is not the canonical path of §1, or that is or contains a link (not descending into `.git/`, §1); and, when the working tree is a Git repository and `git` is available, any file under `build-config/local/` that Git tracks (`git ls-files`) or that is not ignored (`git check-ignore`). Without Git (for example a downloaded archive) the Git part is skipped and the build says so in one line. |
| P-3 | A line of a template whose value is not a placeholder: a real value pasted into a tracked file. |
| P-4 | A Gradle property, an `ORG_GRADLE_PROJECT_` environment variable, a system property, or a key in `gradle.properties` or `local.properties`, whose name starts with the base name of a template followed by a dot (`signing.`, `live-test.`). |
| P-5 | **Any text file** of the working tree outside `build-config/local/` and outside the templates, under any name, read line by line with **the same properties parser** that reads `build-config/local/`: logical lines joined across a trailing backslash, comment lines starting with `#` or `!` skipped, the key ending at the first unescaped `=`, `:` or white space, and escapes (`\uXXXX`, `\ `, `\=`, `\:` and the others of the properties format) decoded in key and value. A logical line whose decoded key is a key of a template, with any value that is not a placeholder, the empty value included, is refused, whatever separator or escapes it uses (for example `signing.keyAlias example-alias` with white space as separator). Documentation that shows keys uses placeholders and passes. |
| P-6 | **The value of any `# secret` key** of `build-config/local/` found as text in any file of the working tree outside `build-config/local/` that Git tracks or that is not ignored (every file, without Git), whatever surrounds it: a password or credential pasted into a source file under another name. |

P-1, P-5 and P-6 skip `.git/`, `.gradle/`, every `build/` directory and binary files (a file with a NUL byte in its
first block). P-6 compares bytes and never prints, logs or caches a value.

The build reads the Build Configuration only from `build-config/local/`; it reads no Gradle property, environment
variable or system property for configuration. P-4 exists so that an attempt to pass configuration that way fails
loudly instead of being silently ignored.

**What "part of one" means here.** The build recognises a part of a Build Configuration by its key (P-1, P-4, P-5) or
by a secret value (P-6). A non-secret value written without its key is not recognisable as configuration and is not
refused; C-005 and the review of each pull request remain the safeguard for it (FR-093, UC-012 BR-009). Service
credentials are not in the Build Configuration at all (§4).

### 6. Tests of the checks

Gradle TestKit tests in `build-logic/`, all run in `check` (ADR-001 §6.4), each on a small fixture project that uses
the same convention plugins and holds only invented values:

- no `build-config/local/`: `check` and `:app:assembleDebug` pass, `:app:assembleRelease` fails with the message of
  ADR-005 §6 (UC-012 A5);
- an incomplete `signing.properties`, and one with a key its template does not define: every build fails with the
  message of UC-012 A1;
- a misplaced `signing.properties`, and renamed fragments with a white-space separator and with an escaped key: every
  build fails with the message of UC-012 A6, P-1 and P-5 respectively;
- a nested private Git repository in `build-config/local/` (an initialised `.git` with one commit of invented content
  and its own `.gitignore`, beside a valid invented `signing.properties`, with the outer `.gitignore` line): every check
  of §5 passes, and `git ls-files` and `git check-ignore` run from the outer repository report nothing under
  `build-config/local/` as tracked and every file there as ignored (§1);
- `NoCredentialInAppTest` (§4).

### 7. Never logged, never printed

- No message of `build-logic/` (failure, warning or Gradle log at any level) contains a value of `build-config/local/`;
  messages name the file and key.
- Signing passwords are passed to the Android signing configuration during execution, never during Gradle's
  configuration phase, and never put into `BuildConfig` fields, resources or the manifest.
- Run-time logging rules for service credentials are in ADR-006.

### 8. Who touches what

The Operator and every Adopting Developer write `build-config/local/` by hand (UC-012 BR-010). No agent reads, writes,
lists the contents of, or prints it. The templates hold placeholders only (P-3). Agents verify with `gradlew check`,
which includes the tests of §6, and may build a `debug` package, which needs no private data; only the Operator
builds a `release` package with the real `signing.properties` or runs the live tests (O-P1-06, O-P1-07; ADR-005 §3).

## Consequences

- An app holds no credential, so one build can serve several households: each Operator enters their own credentials
  in the app (UC-014), and an Adopting Developer may install an app someone else built (UC-012 A7).
- Agents and continuous integration can build and install a `debug` package without any private data; the gate no
  longer needs a test that packaging fails without a Build Configuration (§6).
- Adding a Data Provider or replacing the Budget App adds no template; its credential form lives in its part
  (ADR-001 §4.5).
- A non-secret value pasted without its key is not found (§5); the review of each PR remains the safeguard for it.
- The Operator may keep `build-config/local/` as a private Git repository (the Operator's decision of 2026-10-15). The
  build ignores it, P-2 does not descend into its `.git/`, and a fixture proves that it fails no check (§1, §6).
- Renaming a template's key is a change for every Adopting Developer; keys are decided here and changed only by
  amending this ADR.

## Alternatives

- **The Predecessor App's `secrets/` directory and `local.properties`.** Rejected: not next to the Configuration
  Template, mixes Android SDK settings with credentials, and nothing refused a configuration found elsewhere.
- **Credentials and Bank Accounts in the Build Configuration, compiled into the app (the Phase 1-2 decision).**
  Rejected by S-23 and S-24: every change needed a rebuild, a built app carried its builder's credentials, and the
  Configuration Key had to be kept stable by hand.
- **JSON, YAML or TOML.** Rejected: JSON has no comments for "where to get it"; YAML and TOML need an extra library to
  read them in the build.
- **Environment variables or Gradle properties.** Rejected: they are not "next to the Configuration Template" and leak
  into shell history and Gradle logs; P-4 refuses them.

## AIUP guidance

- No AIUP skill covers build-time configuration, signing, or checks run by a build tool. UC-012 is the only
  specification of it; everything here beyond UC-012's rules is project-made.
- AIUP's stack plugins assume a server application configured at deploy time; none covers an app whose credentials are
  entered on the device.

## Review findings addressed

Entries before S-23/S-24 refer to the earlier Build Configuration of Budgets, Bank Accounts, Configuration Keys and
credentials; where they did, S-23/S-24 supersede them.

- A-05 (with S-03, S-10): superseded by S-23 (the generated identifier of ADR-002 §2 and ADR-008 §7 replaces the
  Configuration Key).
- A-16: §1 and §5 P-2 (canonical path, no links), §2 (namespaced keys, `# secret`), §5 P-4 to P-6 (recognisable keys
  under any file name, secret values anywhere), §5 "What part of one means here".
- A-22: superseded by S-23 (no Budget or Bank Account in the Build Configuration).
- A-26 (with S-01): the file upload window is an Operator Setting; nothing about it is in the Build Configuration.
- A-28 (with O-P1-06): §3 (Absent valid for compiling, `check` and `debug`), §6, §8.
- A-25 and A-27 in part: §2 `signing.properties` (O-P1-08, O-P2-05) and `live-test.properties` (O-P1-07).
- B-12: §3 (three results, Absent valid).
- B-13: §5 P-5 (the reader's properties parser), §6 (renamed-fragment tests).
- The Operator's decision of 2026-10-15 (an optional nested private Git repository in `build-config/local/`): §1, §6,
  Consequences.
- S-23/S-24 (2026-10-16, re-check pending): title, Context, §1-§8 rewritten. The Build Configuration holds only the
  signing setup; the `slussen.*`, `simplefin.*`, `enablebanking.*` and `ynab.*` templates, the Configuration Key
  grammar, the reconciliation by key and the generated Build Configuration are removed; §4 adds the rule and the test
  that no credential can enter the repository or a built app; the packaging-refusal test is replaced by the tests of
  §6 (a `debug` package needs no Build Configuration, a `release` package needs a valid one).
