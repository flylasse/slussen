# ADR-003: Build Configuration Location, Format, Checks and Secrecy

## Status

Proposed (2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the Phase 2
review and the specification changes S-01, S-03 and S-10; revised 2026-10-15 after the Phase 2b re-review). The
Operator sets it to Accepted at the architecture gate.

## Context

- Glossary, Build Configuration (changed by S-03): "The Operator's own Bank Accounts with their Configuration Keys and
  Start Dates, Budgets, Account Mappings, Request Limits and service credentials, kept in a location the repository
  ignores, next to the Configuration Templates, never committed, and included in the app when it is built."
- Glossary, Configuration Key; FR-071, FR-096, UC-012 BR-011, UC-009 BR-010, BR-011, A11, A12, `BANK_ACCOUNT`
  (`configuration_key`, `configured`): each Bank Account of the Build Configuration has a Configuration Key, set once
  and never changed; a stored Bank Account is matched to its entry by that key alone; a removed entry or a new key
  keeps the stored Bank Account out of use with its history and lock; a new key is a new Bank Account whose Account
  Mapping is refused when it points at a locked Budget Account.
- FR-070, UC-012 BR-001: one Configuration Template per Data Provider and per Budget App of this build, showing what
  to fill in and where to get it, never a working configuration.
- FR-071, UC-012 step 5, A1 (changed): the build reads the Build Configuration, checks that it is complete and
  consistent, and produces no app when it is not, naming each missing or inconsistent entry, also when two Bank
  Accounts have the same Configuration Key.
- FR-093, UC-012 BR-009, A6: the build reads the Build Configuration only from the ignored location; a Build
  Configuration, or part of one, found anywhere else, "such as in a tracked file", is never used, and the build
  fails, stating where the Build Configuration belongs.
- C-003, C-004, C-005, UC-012 BR-002, BR-003, BR-008, BR-010; NFR-008 and vision *Sensitive data*: credentials are
  never logged.
- FR-017 and `DATA_PROVIDER.request_limit`: the Request Limit is set per Data Provider in the Build Configuration,
  24 by default, between 1 and 1000. FR-075: the Start Date per Bank Account is optional.
- S-01, FR-094, UC-002 BR-004, UC-010 BR-001, `OPERATOR_SETTINGS.file_upload_window_days`: the file upload window is
  one Operator Setting for every File Format; nothing about it is in the Build Configuration. FR-086 is Rejected.
- UC-008 precondition: at least one Budget with a mapped Bank Account.
- Construction kickoff plan §1.2: no agent reads, writes or prints the Operator's Build Configuration. The Operator's
  answers: O-P1-06 (agents gate on `gradlew check` plus a test that packaging fails without a Build Configuration;
  only the Operator runs `gradlew build`), O-P1-07 (live-test credentials in the ignored location, ADR-004),
  O-P1-08 and O-P2-05 (release signing, ADR-005).

Evidence from the Predecessor App, used as evidence and not as a template: credentials lived in an ignored
`secrets/` directory and in `local.properties` (with a tracked `local.properties.template`), and reached the app as
assets read at run time. Nothing refused a configuration found elsewhere. The current Slussen `.gitignore` still
carries the Predecessor App's `/secrets/` and `/local.properties` lines, and has `build-config/local/` since the
Operator's commit between Phase 1 and Phase 2.

The Phase 2 review found that the placement rules missed recognisable configuration under a renamed file, that the
generator was not ordered after validation (A-16), that validation rejected a File Pre-processor without a template and
accepted a configuration with no Budget or Bank Account (A-22), that the Upload Window precedence was an unapproved
reading (A-26), that no immutable identity tied a stored Bank Account to its entry (A-05), and that the plan's
`gradlew build` gate could not pass without private data (A-28).

## Decision

### 1. Location

| Path | Tracked | Holds |
|---|---|---|
| `build-config/` | yes | the Configuration Templates, `<name>.template.properties` |
| `build-config/local/` | **no**, ignored by the line `build-config/local/` in `.gitignore` | the Build Configuration, `<name>.properties`, one file per template that the build uses |

This is the "location the repository ignores, next to the Configuration Templates". Phase 6 removes the inherited
`/secrets/` line, so that exactly one ignored location for configuration exists. `local.properties` stays ignored,
but only for `sdk.dir`; it is never read for configuration.

The path is canonical: the build resolves `build-config/local/` with symbolic links and junctions followed and
requires the result to be exactly `<real path of the repository root>/build-config/local`, and requires that neither
the directory nor any file in it is a link (P-2).

**Other files in `build-config/local/`, and an optional private Git repository** (the Operator's decision of
2026-10-15):

- Files other than the Build Configuration files may sit in `build-config/local/`. This includes a `.git` folder and
  a `.gitignore` of the Operator's own private Git repository, which the Operator may use, optionally, to version and
  back up their Build Configuration. The public repository ignores the whole folder (the `.gitignore` line above), so
  nothing of the private repository can be committed to it.
- The build reads there only the `<name>.properties` files of §2 that lie directly in `build-config/local/`; every
  other file and every subfolder is ignored by §3 and §6. A folder that holds only such other files (for example only
  a private repository) is an absent Build Configuration (§3). The build refuses only what §5 names.
- P-2 does **not descend** into `build-config/local/.git/`: its link check and its Git checks apply to
  `build-config/local/` itself and to every file in it outside `.git/`. (A `.git` that is a file, as in a Git
  worktree, is an ordinary file there and is checked like one.) A nested repository therefore never fails P-1 to P-6
  or packaging: P-1, P-5 and P-6 look only outside `build-config/local/`, and P-3 and P-4 never look inside it.
- This ADR does not prescribe how the Operator backs up the folder. No agent reads, writes or lists it, as before
  (§8).

### 2. Files and format

Java properties files in UTF-8, because they allow comments, need no library in the build, and are what an Android
developer already knows from `local.properties`. The build reads them with its own line parser that follows the
properties syntax and, unlike `java.util.Properties`, fails on a key that appears twice.

| Template | Build Configuration file | Owner | Holds |
|---|---|---|---|
| `slussen.template.properties` | `slussen.properties` | `:core` | Budgets, Banks, Bank Accounts with their Configuration Keys, kind, currency, Source, Start Date and Account Mapping |
| `simplefin.template.properties` | `simplefin.properties` | `:provider-simplefin` | Request Limit, service credentials |
| `enablebanking.template.properties` | `enablebanking.properties` | `:provider-enablebanking` | Request Limit, service credentials, the return address of Strong Customer Authentication |
| `ynab.template.properties` | `ynab.properties` | `:budgetapp-ynab` | service credentials |
| `signing.template.properties` | `signing.properties` | `build-logic/` | the path of the release keystore outside the repository and its passwords (ADR-005 §6) |
| `live-test.template.properties` | `live-test.properties` | the live tests of `:budgetapp-ynab` | the credentials and Budget reference of a throwaway YNAB Budget (ADR-004 §2) |

The base name of a plug-in's file is its plug-in id, the suffix of its module name (`:provider-simplefin` →
`simplefin`). A plug-in has a template only if it needs a value. A new plug-in adds its template; no other file of
this table changes.

**Every key carries its file's base name as its first segment** (`slussen.`, `simplefin.`, `ynab.`, ...). That makes
every key of every Build Configuration recognisable wherever it appears (P-4, P-5).

**The template is the schema.** Every non-comment line of a template is `key=<placeholder>`, where the placeholder
is text between `<` and `>` that says what goes there. The comments above it say where to get the value. Two marker
comments apply to the key directly below them: `# optional` makes it optional (every other key is required) and
`# secret` marks a credential, password or session value (P-6, §7). A key segment written `<key>` (for example
`slussen.bankAccount.<key>.name`) defines a repeated block: the Build Configuration may hold the block any number of
times, each with its own key segment, and each block must have the block's required keys.

The keys of `slussen.template.properties` (Phase 8 writes the template with its explanations; the key names are
decided here):

```properties
slussen.budget.<key>.reference=<the Budget App's identifier of the Budget>
slussen.bank.<key>.name=<the Bank's name as Slussen shows it>
# optional
slussen.bank.<key>.country=<ISO 3166 two-letter code>
slussen.bankAccount.<key>.name=<the Bank Account's name as Slussen shows it>
slussen.bankAccount.<key>.bank=<the key of a slussen.bank.<key> block>
slussen.bankAccount.<key>.currency=<ISO 4217 code>
slussen.bankAccount.<key>.kind=<Checking, Savings, Credit Card or Other>
# optional
slussen.bankAccount.<key>.dataProvider=<plug-in id of the Data Provider that delivers its transactions>
# optional
slussen.bankAccount.<key>.fileFormat=<Source Key of the File Format of its Transaction Files>
# optional
slussen.bankAccount.<key>.balanceDataProvider=<plug-in id of the Data Provider that reports its balance>
slussen.bankAccount.<key>.budget=<the key of a slussen.budget.<key> block>
slussen.bankAccount.<key>.budgetAccount=<the Budget App's identifier of the Budget Account it is mapped to>
# optional
slussen.bankAccount.<key>.startDate=<YYYY-MM-DD; nothing before it is offered for Upload>
```

**The `<key>` of a `slussen.bankAccount.<key>` block is the Bank Account's Configuration Key** (S-03): lowercase
letters, digits and single hyphens, starting with a letter, at most 50 characters (`BANK_ACCOUNT.configuration_key`
length). The template's comment says that it is set when the Bank Account is first added and never changed
afterwards, and that renaming or reordering entries is safe (UC-012 BR-011). The `<key>` of a Budget or Bank block
follows the same grammar and only links blocks within the file.

Each Data Provider template has `# optional` `<id>.requestLimit=<1 to 1000, 24 when left out>` (FR-017) and its own
credential keys, each marked `# secret`. The file upload window and every other Operator Setting are not in any
template (S-01).

### 3. Checks that make a Build Configuration complete and consistent

Task `verifyBuildConfiguration` in `build-logic/` (UC-012 step 5, A1). It reads only files that passed
`verifyBuildConfigurationPlacement` (§5). Its result is one of three (B-12):

- **Absent**: `build-config/local/` is missing or holds none of the Build Configuration files of §2 (`signing.properties`
  and `live-test.properties` do not count). This is not a failure: compiling and `gradlew check` go on with the
  `ABSENT` stub (§6), and only packaging refuses it (`requireBuildConfiguration`, §6).
- **Valid**: at least one Build Configuration file is present and none of the rules below fails.
- **Failed**: at least one Build Configuration file is present and a rule below fails. The task then fails, naming the
  file and key of each problem and never its value.

The rules, applied only when at least one Build Configuration file is present:

- a key appears twice in one file, a required key is missing, a key is not in its file's template (catches
  misspellings), or a value is still a placeholder;
- a `<name>.properties` file directly in `build-config/local/` has no template (other files are ignored, §1), `slussen.properties` is missing while another Build Configuration file is present, or a
  plug-in that a Bank Account names, or the Budget App,
  has a template with at least one required key and no file; a plug-in without a template, or whose template has only
  optional keys, needs no file (A-22);
- there is no `slussen.budget` block, no `slussen.bankAccount` block, or a Budget block that no Bank Account names
  (UC-008 precondition; A-22);
- a Configuration Key does not follow the grammar of §2; the grammar of properties keys makes two Bank Accounts with
  the same Configuration Key appear as a repeated key, which fails the first rule (UC-012 A1);
- a Bank Account names neither or both of `dataProvider` and `fileFormat` (`BANK_ACCOUNT` constraint);
  `balanceDataProvider` is set without `fileFormat`; a named plug-in id or Source Key is not part of this build;
  `bank` or `budget` names a block that does not exist;
- two Bank Accounts have the same `budget` and `budgetAccount` (FR-056, UC-009 BR-001, UC-012 A1);
- `currency` is not three capital letters; `kind` is not one of the four values; `startDate` is not a valid date;
  `requestLimit` is not a whole number from 1 to 1000 (`DATA_PROVIDER.request_limit`).

The build does not contact the Budget App or any Data Provider. It cannot know which Configuration Keys an installed
app has stored; a renamed key is therefore not a build error but a new Bank Account at run time, handled by FR-096
(ADR-002 §2). A currency that differs from its Budget's is found at run time (UC-003 A3).

### 4. The file upload window

There is no Upload Window per File Format (S-01, A-26). The file upload window is the Operator Setting
`OPERATOR_SETTINGS.file_upload_window_days` (60 days by default, between 7 and 365, a number the Operator can change,
FR-094, UC-010 BR-001), set in the app, never in the Build Configuration or a File Pre-processor. The Phase 1 key
`fileFormat.<key>.uploadWindowDays` and the declared default of ADR-001 §4.3 are removed.

### 5. Refusing a Build Configuration found elsewhere

Task `verifyBuildConfigurationPlacement` in `build-logic/` runs before anything reads configuration: every compile
task of `:app`, `verifyBuildConfiguration`, `generateBuildConfiguration` and `check` depend on it. It fails, uses
nothing it found, and states "The Build Configuration belongs in `build-config/local/`, next to the Configuration
Templates in `build-config/`" together with the path and the rule that matched (never a value), when:

| Rule | Finds |
|---|---|
| P-1 | A file whose name is the name of a Build Configuration file (`<name>.properties` for any `<name>.template.properties`) anywhere in the working tree outside `build-config/local/`. |
| P-2 | `build-config/local/` that is not the canonical path of §1, or that is or contains a link; and, when the working tree is a Git repository and `git` is available, any file under `build-config/local/` that Git tracks (`git ls-files`) or that is not ignored (`git check-ignore`). Without Git (for example a downloaded archive) the Git part is skipped and the build says so in one line. |
| P-3 | A line of a template whose value is not a placeholder: a real value pasted into a tracked file. |
| P-4 | A Gradle property, an `ORG_GRADLE_PROJECT_` environment variable, a system property, or a key in `gradle.properties` or `local.properties`, whose name starts with the base name of any template followed by a dot (`slussen.`, `simplefin.`, `ynab.`, ...). |
| P-5 | **Any text file** of the working tree outside `build-config/local/` and outside the templates, under any name, read line by line with **the same properties parser** that reads `build-config/local/` (B-13): logical lines joined across a trailing backslash, comment lines starting with `#` or `!` skipped, the key ending at the first unescaped `=`, `:` or white space, and escapes (`\uXXXX`, `\ `, `\=`, `\:` and the others of the properties format) decoded in key and value. A logical line whose decoded key is a key of the grammar (every template key with each `<key>` segment read as the Configuration Key grammar), with any value that is not a placeholder, the empty value included, is refused. This finds a renamed or partial copy whatever separator or escapes it uses, for example a tracked `copied.properties` with `slussen.bankAccount.example.budgetAccount example-reference` (white space as separator) or with the key written with an escape such as `slussen.bankAccount.example.budget\u0041ccount=…`. Documentation that shows keys uses placeholders and passes. |
| P-6 | **The value of any `# secret` key** of the Build Configuration found as text in any file of the working tree outside `build-config/local/` that Git tracks or that is not ignored (every file, without Git), whatever surrounds it: a credential pasted into a source file under another name. |

P-1, P-5 and P-6 skip `.git/`, `.gradle/`, every `build/` directory and binary files (a file with a NUL byte in its
first block). P-6 compares bytes and never prints, logs or caches a value.

The build itself reads the Build Configuration only from `build-config/local/`; it reads no Gradle property,
environment variable or system property for configuration. P-4 exists so that an attempt to pass configuration that
way fails loudly instead of being silently ignored.

**What "part of one" means here.** The build recognises a part of a Build Configuration by its key (P-1, P-4, P-5) or
by a secret value (P-6). A non-secret value written without its key (for example a Budget's reference on its own) is
not recognisable as configuration and is not refused; C-005 and the review of each pull request remain the safeguard
for it. This is the reading FR-093 states since S-11.

### 6. Inclusion in the app

- Task ordering: `verifyBuildConfigurationPlacement` → `verifyBuildConfiguration` → `generateBuildConfiguration` →
  compile. `verifyBuildConfiguration` writes its normalised, checked result to
  `app/build/slussen/buildConfiguration/validated.properties`, and `generateBuildConfiguration` reads only that file,
  so the generator never reads anything the checks did not pass (A-16).
- `generateBuildConfiguration` writes one Kotlin file, `GeneratedBuildConfiguration.kt`, to
  `app/build/generated/slussen/buildConfiguration/kotlin/`, which `:app` registers as a generated source directory.
  The file builds a typed `slussen.core.config.BuildConfiguration` (Budgets, Banks, Bank Accounts with their
  Configuration Keys, Account Mappings, Start Dates, Request Limits), the configuration fingerprint (a SHA-256 of the
  normalised non-secret content, used by the check before writing, ADR-007 §6), and one `PluginSettings` per plug-in
  file whose `# secret` values are `Secret` (ADR-001 §4). `CompositionRoot` hands each plug-in its own
  `PluginSettings` and nothing else. `signing.properties` and `live-test.properties` never reach the generated file.
- When `verifyBuildConfiguration` reports Absent (§3), `generateBuildConfiguration` writes a stub whose configuration
  is `BuildConfiguration.ABSENT`, so that every module compiles and every test runs without a Build Configuration; the
  order of the tasks above is unchanged, and Absent passes through it. Every packaging task of `:app` (`package*`,
  `bundle*`, and therefore `assemble*` and `install*`) depends on `requireBuildConfiguration`, which fails when the
  result is Absent, and on `verifyBuildConfiguration`, which fails when the result is Failed (incomplete or
  inconsistent). So `gradlew check` passes without a Build Configuration, a partial or inconsistent one fails every
  build, and no app is produced without a valid one (UC-012 A1; B-12).
- **The packaging-refusal test** (O-P1-06, A-28): a Gradle TestKit test in `build-logic/` runs `:app:assembleDebug` on
  a small fixture project that uses the same convention plugins and has no `build-config/local/`, and asserts that it
  fails with the message of UC-012 A1, and that `check` on the same fixture passes. A second fixture with a misplaced
  file asserts the message of A6; a third holds renamed fragments with a white-space separator and with an escaped key
  and asserts that P-5 refuses each (B-13). A fourth holds a nested private Git repository inside
  `build-config/local/` (an initialised `.git` with one commit of invented content, and its own `.gitignore`) beside a
  valid invented Build Configuration, with the outer `.gitignore` line: it asserts that every check of §5 and the
  generator pass, and that `git ls-files` and `git check-ignore` run from the outer repository report nothing under
  `build-config/local/` as tracked and every file there as ignored (§1). All run in `check` (ADR-001 §6.4).
- The tasks read the files during execution, not during Gradle's configuration phase, and declare them as file
  inputs, so their values are kept out of Gradle's configuration cache.
- At run time the core takes the configured Budgets, Banks, Bank Accounts and Account Mappings from the generated
  configuration and reconciles them with what the app recorded (identification, locks, retirement, Funded By,
  Transfer Partner) by Configuration Key, as ADR-002 §2 says (O-11; FR-096, UC-009 A8, A11, A12).

### 7. Never logged, never printed

- No message of `build-logic/` (failure, warning or Gradle log at any level) contains a value of the Build
  Configuration; messages name the file and key.
- `Secret.toString()` returns a fixed mask. `BuildConfiguration.toString()` returns counts only (for example
  "3 Budgets, 9 Bank Accounts").
- Credentials are not put into `BuildConfig` fields, resources or the manifest; only the generated Kotlin file
  carries them.
- Logging rules for run time are in ADR-006.

### 8. Who touches what

The Operator and every Adopting Developer write `build-config/local/` by hand (UC-012 BR-010). No agent reads,
writes, lists the contents of, or prints it; tests use `BuildConfiguration.ABSENT` and invented data from
`:testing`. The Configuration Templates hold placeholders only (P-3 enforces it). Agents verify with
`gradlew check`, which includes the packaging-refusal test of §6; only the Operator runs `gradlew build`, a packaging
or install task, or the live tests, with the real Build Configuration (O-P1-06, O-P1-07).

## Consequences

- The Operator may keep `build-config/local/` as a private Git repository to version and back up the Build
  Configuration (the Operator's decision of 2026-10-15). The build ignores it, P-2 does not descend into its `.git/`,
  and a fixture in the TestKit tests proves that it fails no check (§1, §6).

- An Adopting Developer copies each template they need from `build-config/` to `build-config/local/`, drops
  `.template` from the name and replaces the placeholders, choosing a Configuration Key for each Bank Account that
  they never change. The how-to (Phase 8) says exactly that.
- `gradlew build` includes `assemble`, so **without a Build Configuration `gradlew build` fails by design**. The agent
  gate of every construction phase is `gradlew check`, which includes the test that packaging fails without a Build
  Configuration; the plan's Phase 6 and 7 gates say so (A-28, O-P1-06).
- P-5 and P-6 read every text file of the working tree on each build; on a repository of this size that is a short
  scan, and it is skipped by nothing.
- A non-secret value pasted without its key is not found (§5); the review of each PR remains the safeguard for it.
- A credential included in the app can be extracted from it (C-003); the masking above protects logs, not the app
  file.
- Renaming a template's key is a change for every Adopting Developer; keys are therefore decided here and changed only
  by amending this ADR. The namespaced keys of §2 replace the Phase 1 keys before any Build Configuration exists.
- Changing a Bank Account's Configuration Key is not refused by the build; the app keeps the old Bank Account out of
  use with its history and lock and treats the new one as new (FR-096).

## Alternatives

- **The Predecessor App's `secrets/` directory and `local.properties`.** Rejected: not next to the Configuration
  Templates, mixes Android SDK settings with credentials, and nothing refused a configuration found elsewhere.
- **One file for everything.** Rejected: FR-070 asks for a template per Data Provider and per Budget App, and a
  plug-in should own its keys, so that adding one touches no other template.
- **JSON, YAML or TOML.** Rejected: JSON has no comments for "where to get it"; YAML and TOML need an extra library to read them
  in the build.
- **Environment variables or Gradle properties.** Rejected: they are not "next to the Configuration Templates" and
  leak into shell history and Gradle logs; P-4 refuses them.
- **Assets read at run time (the Predecessor App).** Rejected in favour of generated Kotlin: the build checks types
  and completeness once, and the app does not parse configuration at start.
- **Including the Build Configuration only when present, without failing (`ABSENT` app).** Rejected: UC-012 A1
  requires no app.
- **Matching a stored Bank Account by name or position.** Rejected by S-03: a rename or reorder would bypass a lock.
- **Un-namespaced keys with detection by file name only.** Rejected (A-16): a renamed copy would pass.
- **Scanning every file for every configuration value.** Rejected: non-secret values (names, currencies, kinds) are
  common words and would fail ordinary files; only `# secret` values are searched.

## AIUP guidance

- No AIUP skill covers build-time configuration, secrets, or checks run by a build tool. UC-012 is the only
  specification of it; everything here beyond UC-012's rules is project-made.
- AIUP's stack plugins assume a server application configured at deploy time (for example Spring profiles); none
  covers a private build whose credentials are compiled into an app installed on one device.

## Review findings addressed

- A-05 (with S-03, S-10): §2 (the block key is the Configuration Key, its grammar), §3 (repeated key), §6 and
  ADR-002 §2 (reconciliation by key at run time).
- A-16: §1 and §5 P-2 (canonical path, no links), §2 (namespaced keys, `# secret`), §5 P-4 to P-6 (recognisable keys
  under any file name, secret values anywhere), §5 "What part of one means here", §6 (generator reads only the
  validated output, after placement).
- A-22: §3 (a plug-in file required only by a template with required keys; at least one Budget and Bank Account,
  every Budget used).
- A-26 (with S-01): §4.
- A-28 (with O-P1-06): §6 packaging-refusal test, §8, Consequences.
- A-25 and A-27 in part: §2 `signing.properties` (O-P1-08, O-P2-05) and `live-test.properties` (O-P1-07).
- B-12: §3 (three results, Absent valid for compile and `check`), §6 (stub for Absent; packaging refuses Absent and
  Failed; the packaging-refusal test also checks that `check` passes).
- B-13: §5 P-5 (the reader's properties parser: white-space separators, continuations, comments and escapes; empty
  value refused), §6 (renamed-fragment negative tests).
- The Operator's decision of 2026-10-15 (an optional nested private Git repository in `build-config/local/`): §1 (other
  files allowed, only the §2 files read, P-2 does not descend into `.git/`, no backup method prescribed), §6 (nested
  repository fixture), Consequences.
