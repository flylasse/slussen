# ADR-003: Build Configuration Location, Format, Checks and Secrecy

## Status

Proposed (2026-10-14, construction kickoff Phase 1). The Operator sets it to Accepted at the architecture gate.

## Context

- Glossary, Build Configuration: "The Operator's own Bank Accounts with their Start Dates, Budgets, Account
  Mappings, Request Limits and service credentials, kept in a location the repository ignores, next to the
  Configuration Templates, never committed, and included in the app when it is built."
- FR-070, UC-012 BR-001: one Configuration Template per Data Provider and per Budget App of this build, showing what
  to fill in and where to get it, never a working configuration.
- FR-071, UC-012 step 5, A1: the build reads the Build Configuration, checks that it is complete and consistent, and
  produces no app when it is not, naming each missing or inconsistent entry.
- FR-093, UC-012 BR-009, A6: the build reads the Build Configuration only from the ignored location; a Build
  Configuration, or part of one, found anywhere else, "such as in a tracked file", is never used, and the build
  fails, stating where the Build Configuration belongs.
- C-003, C-004, C-005, UC-012 BR-002, BR-003, BR-008, BR-010; NFR-008 and vision *Sensitive data*: credentials are
  never logged.
- FR-017 and `DATA_PROVIDER.request_limit`: the Request Limit is set per Data Provider in the Build Configuration,
  24 by default, between 1 and 1000. FR-075: the Start Date per Bank Account is optional.
- UC-010 BR-001 and the `OPERATOR_SETTINGS` constraints: "the Upload Window of each File Format ... set in the
  Build Configuration"; FR-084, FR-086, the glossary (File Format, File Pre-processor) and
  `FILE_FORMAT.upload_window_days`: the File Pre-processor declares the Upload Window of its File Format.
- Construction kickoff plan §1.2: no agent reads, writes or prints the Operator's Build Configuration.

Evidence from the Predecessor App, used as evidence and not as a template: credentials lived in an ignored
`secrets/` directory and in `local.properties` (with a tracked `local.properties.template`), and reached the app as
assets read by `BuildConfigSecrets`. Nothing refused a configuration found elsewhere. The current Slussen
`.gitignore` still carries the Predecessor App's `/secrets/` and `/local.properties` lines.

## Decision

### 1. Location

| Path | Tracked | Holds |
|---|---|---|
| `build-config/` | yes | the Configuration Templates, `<name>.template.properties` |
| `build-config/local/` | **no**, ignored by the line `/build-config/local/` in `.gitignore` | the Build Configuration, `<name>.properties`, one file per template that the build uses |

This is the "location the repository ignores, next to the Configuration Templates". Phase 6 adds the ignore line and
removes the inherited `/secrets/` line, so that exactly one ignored location exists. `local.properties` stays
ignored, but only for `sdk.dir`; it is never read for configuration.

### 2. Files and format

Java properties files in UTF-8 (`java.util.Properties.load(Reader)`), because they allow comments, need no library
in the build, and are what an Android developer already knows from `local.properties`.

| Template | Build Configuration file | Owner | Holds |
|---|---|---|---|
| `slussen.template.properties` | `slussen.properties` | `:core` | Budgets, Banks, Bank Accounts with kind, currency, Source, Start Date and Account Mapping; File Format Upload Windows (§4) |
| `simplefin.template.properties` | `simplefin.properties` | `:provider-simplefin` | Request Limit, service credentials |
| `enablebanking.template.properties` | `enablebanking.properties` | `:provider-enablebanking` | Request Limit, service credentials, the return address of Strong Customer Authentication |
| `ynab.template.properties` | `ynab.properties` | `:budgetapp-ynab` | service credentials |

The base name of a plug-in's file is its plug-in id, the suffix of its module name (`:provider-simplefin` →
`simplefin`). A File Pre-processor has a template only if it needs a value. A new plug-in adds its template; no
other file of this table changes.

**The template is the schema.** Every non-comment line of a template is `key=<placeholder>`, where the placeholder
is text between `<` and `>` that says what goes there. The comments above it say where to get the value. A comment
line `# optional` directly above a key makes that key optional; every other key is required. A key segment written
`<key>` (for example `bankAccount.<key>.name`) defines a repeated block: the Build Configuration may hold the block
any number of times, each with its own key segment of lowercase letters, digits and `-`, and each block must have
the block's required keys.

The keys of `slussen.template.properties` (Phase 8 writes the template with its explanations; the key names are
decided here):

```properties
budget.<key>.reference=<the Budget App's identifier of the Budget>
bank.<key>.name=<the Bank's name as Slussen shows it>
# optional
bank.<key>.country=<ISO 3166 two-letter code>
bankAccount.<key>.name=<the Bank Account's name as Slussen shows it>
bankAccount.<key>.bank=<the key of a bank.<key> block>
bankAccount.<key>.currency=<ISO 4217 code>
bankAccount.<key>.kind=<Checking, Savings, Credit Card or Other>
# optional
bankAccount.<key>.dataProvider=<plug-in id of the Data Provider that delivers its transactions>
# optional
bankAccount.<key>.fileFormat=<Source Key of the File Format of its Transaction Files>
# optional
bankAccount.<key>.balanceDataProvider=<plug-in id of the Data Provider that reports its balance>
bankAccount.<key>.budget=<the key of a budget.<key> block>
bankAccount.<key>.budgetAccount=<the Budget App's identifier of the Budget Account it is mapped to>
# optional
bankAccount.<key>.startDate=<YYYY-MM-DD; nothing before it is offered for Upload>
# optional
fileFormat.<key>.uploadWindowDays=<1 to 366; the File Pre-processor's declared value when left out>
```

Each Data Provider template has `# optional` `requestLimit=<1 to 1000, 24 when left out>` (FR-017) and its own
credential keys.

### 3. Checks that make a Build Configuration complete and consistent

Task `verifyBuildConfiguration` in `build-logic/` (UC-012 step 5, A1). It fails, naming the file and key of each
problem and never its value, when:

- a required key is missing, a key is not in the template (catches misspellings), or a value is still a
  placeholder;
- `slussen.properties` is missing, or the Budget App's file is missing, or a plug-in named by a Bank Account has no
  file;
- a Bank Account names neither or both of `dataProvider` and `fileFormat` (`BANK_ACCOUNT` constraint);
  `balanceDataProvider` is set without `fileFormat`; a named plug-in id or Source Key is not part of this build;
  `bank` or `budget` names a block that does not exist;
- two Bank Accounts have the same `budget` and `budgetAccount` (FR-056, UC-009 BR-001, UC-012 A1);
- `currency` is not three capital letters; `kind` is not one of the four values; `startDate` is not a valid date;
  `requestLimit` is not a whole number from 1 to 1000; `uploadWindowDays` is not a whole number from 1 to 366.

The build does not contact the Budget App or any Data Provider. A currency that differs from its Budget's is found
at run time (UC-003 A3).

### 4. The Upload Window of a File Format

The two statements of the specification are combined as follows: the File Pre-processor declares a value
(`FileFormatProfile.defaultUploadWindowDays`, ADR-001 §4.3), and `fileFormat.<key>.uploadWindowDays` in the
Build Configuration replaces it when set. The resulting value is stored as `FILE_FORMAT.upload_window_days`. It is
not an Operator Setting. This reading is an open question for the Operator (O-P1-02) until confirmed.

### 5. Refusing a Build Configuration found elsewhere

Task `verifyBuildConfigurationPlacement` in `build-logic/` runs in every build: `check` and the `preBuild` of `:app`
depend on it. It fails, uses nothing it found, and states "The Build Configuration belongs in
`build-config/local/`, next to the Configuration Templates in `build-config/`" together with the path and the rule
that matched, when:

| Rule | Finds |
|---|---|
| P-1 | A file whose name is the name of a Build Configuration file (`<name>.properties` for any `<name>.template.properties`) anywhere in the working tree outside `build-config/local/`; `.git/`, `.gradle/` and every `build/` directory are skipped. |
| P-2 | When the working tree is a Git repository and `git` is available: any file under `build-config/local/` that Git tracks (`git ls-files`), or `build-config/local/` not ignored (`git check-ignore`). Without Git (for example a downloaded archive) P-2 is skipped and the build says so in one line. |
| P-3 | A line of a template whose value is not a placeholder: a real value pasted into a tracked file. |
| P-4 | A Gradle property, an `ORG_GRADLE_PROJECT_` environment variable, or a key in `gradle.properties` or `local.properties`, whose name starts with `slussen.`. |

The build itself reads the Build Configuration only from `build-config/local/`; it reads no Gradle property,
environment variable or system property for configuration. P-4 exists so that an attempt to pass configuration that
way fails loudly instead of being silently ignored.

### 6. Inclusion in the app

- Task `generateBuildConfiguration` of `:app` reads `build-config/local/` and writes one Kotlin file,
  `GeneratedBuildConfiguration.kt`, to `app/build/generated/slussen/buildConfiguration/kotlin/`, which `:app`
  registers as a generated source directory. The file builds a typed `slussen.core.config.BuildConfiguration`
  (Budgets, Banks, Bank Accounts, Account Mappings, Start Dates, Request Limits, File Format Upload Windows) and one
  `PluginSettings` per plug-in file whose values are `Secret` (ADR-001 §4). `CompositionRoot` hands each plug-in
  its own `PluginSettings` and nothing else.
- When `build-config/local/` holds no file, the task writes a stub whose configuration is `BuildConfiguration.ABSENT`,
  so that every module compiles and every test runs without a Build Configuration. Every packaging task of `:app`
  (`assemble*`, `bundle*`, `package*`, `install*`) depends on `verifyBuildConfiguration`, which fails when the
  Build Configuration is absent, incomplete or inconsistent. So `gradlew check` passes without one, and no app is
  produced without one (UC-012 A1).
- The task reads the files during execution, not during Gradle's configuration phase, and declares them as file
  inputs, so their values are kept out of Gradle's configuration cache.
- At run time the core takes the configured Budgets, Banks, Bank Accounts and Account Mappings from the generated
  configuration and keeps what the app itself recorded (identification, locks, retirement, Funded
  By, Transfer Partner). A locked Account Mapping that the Build Configuration now maps elsewhere follows UC-009 A8.

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
`:testing`. The Configuration Templates hold placeholders only (P-3 enforces it).

## Consequences

- An Adopting Developer copies each template they need from `build-config/` to `build-config/local/`, drops
  `.template` from the name and replaces the placeholders. The how-to (Phase 8) says exactly that.
- `gradlew build` includes `assemble`, so **without a Build Configuration `gradlew build` fails by design**. Agents
  and continuous integration run `gradlew check` (every compile, unit test, Konsist rule and placement check) plus
  a test that `assemble` fails without a Build Configuration. Only the Operator runs `gradlew build` or
  an install task with a real Build Configuration (plan Phase 9). The plan's Phase 6 and Phase 7 gates say
  `gradlew build passes`; this is reported to the orchestrator as open (O-P1-06).
- A credential pasted into a source file under another key name is not found by P-1 to P-4. C-005 and the review of
  each PR remain the safeguard for that.
- A credential included in the app can be extracted from it (C-003); the masking above protects logs, not the app
  file.
- Renaming a template's key is a change for every Adopting Developer; keys are therefore decided here and changed only
  by amending this ADR.

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

## AIUP guidance

- No AIUP skill covers build-time configuration, secrets, or checks run by a build tool. UC-012 is the only
  specification of it; everything here beyond UC-012's rules is project-made.
- AIUP's stack plugins assume a server application configured at deploy time (for example Spring profiles); none
  covers a private build whose credentials are compiled into an app installed on one device.
