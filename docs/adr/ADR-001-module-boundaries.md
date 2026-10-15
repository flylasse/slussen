# ADR-001: Module Boundaries, Plug-in Contracts and Their Enforcement

## Status

Proposed (2026-10-14, construction kickoff Phase 1). The Operator sets it to Accepted at the architecture gate.

## Context

The Approved specification asks for a structure in which the rules that fetch, compare, review and decide never
depend on one Data Provider, one File Format or one Budget App:

- vision, Constraints: *Extensible Data Providers and File Formats*, *Replaceable Budget App*, *Private build for
  one operator* ("the design keeps its concerns separated");
- NFR-014: adding a Data Provider or a File Pre-processor changes 0 files of the rules that compare, review and
  decide; NFR-015: every rule that depends on one Budget App's behaviour lives in that Budget App's own part, which
  also declares whether the Budget App supports the Cleared marker;
- FR-072, FR-073, FR-083, FR-084, FR-085; C-008, C-009, C-010; UC-012 BR-005, BR-006, A2, A3, A4;
- UC-002 BR-007: each File Format has one File Pre-processor that converts the Bank's file into the Standard Import
  Format.

The Operator decided (bootstrap plan O-03): Gradle modules for the core, each Budget App, each Data Provider, each
File Pre-processor and the app, enforced by Konsist architecture tests, recorded in an ADR before construction.

Evidence from the Predecessor App (`ynab-import-android` at `46efbf0`, read through
`scratch/construction_inventory.md`), used as evidence and not as a template:

- Eight modules: `:domain`, `:source-simplefin`, `:source-enablebanking`, `:source-fidelity-hsa`, `:budget-ynab`,
  `:test-support`, and, only when an Android SDK is installed, `:platform` and `:app`.
- The plug-in modules depended on the whole `:domain`, so a Data Provider could call any rule of the core.
- Boundaries were enforced by a custom `verifyArchitecture` task that scanned `import` lines of a hard-coded module
  list. It could not see a fully qualified reference, did not check the Gradle dependency graph, and its UI rule
  "passes vacuously when app is empty".
- `:platform` and `:app` were dropped silently when no Android SDK was present, so a build could pass without
  compiling or checking the Android code.

## Decision

### 1. Modules

Package root is `slussen`. Every module owns exactly one package root and nothing else.

| Module | Gradle plugin | Package root | Responsibility |
|---|---|---|---|
| `:plugin-api` | `kotlin("jvm")` | `slussen.pluginapi` | The three plug-in contracts (§4), their value types and the Standard Import Format. Nothing else. Ships, as `testFixtures`, the contract tests (§4.4) and the `@UseCase` test annotation (ADR-004). |
| `:core` | `kotlin("jvm")` | `slussen.core` | Every rule that fetches, imports, compares, reviews, decides, plans, uploads and reports, in Slussen's own terms (glossary). The domain model of `docs/entity_model.md` as Kotlin types. The ports (§3) that persistence and the app implement. |
| `:provider-simplefin` | `kotlin("jvm")` | `slussen.provider.simplefin` | The SimpleFIN Data Provider. |
| `:provider-enablebanking` | `kotlin("jvm")` | `slussen.provider.enablebanking` | The Enable Banking Data Provider, including its Bank Consent capability. |
| `:budgetapp-ynab` | `kotlin("jvm")` | `slussen.budgetapp.ynab` | The YNAB Budget App: everything that depends on YNAB's behaviour (C-009). |
| `:fileformat-<format>` | `kotlin("jvm")` | `slussen.fileformat.<format>` | The one File Pre-processor of this build (C-010). Its `<format>` name is open (O-P1-01 in the report to the orchestrator). |
| `:persistence` | `com.android.library` | `slussen.persistence` | Room databases, DAOs and the implementations of the core's storage ports; the backup rule files (ADR-002). |
| `:app` | `com.android.application` | `slussen.app` | Compose UI and ViewModels, the composition root, the Scheduled Fetch worker, the New Transaction Notification, the return from Strong Customer Authentication, file choosing and saving, the log adapter (ADR-006), and the generated Build Configuration (ADR-003). |
| `:testing` | `kotlin("jvm")` | `slussen.testing` | Test-only: in-memory fakes of the core's ports and of the three plug-in contracts, invented example data. |
| `:architecture-tests` | `kotlin("jvm")` | `slussen.architecture` | Test-only: the Konsist rules (§6.2) and the traceability checks of ADR-004. |

Naming rule for later modules: a Data Provider is `:provider-<name>`, a Budget App is `:budgetapp-<name>`, a File
Pre-processor is `:fileformat-<name>`; `<name>` is lowercase letters and digits only.

Build logic lives in an included build `build-logic/` (convention plugins, the module-graph check of §6.1, the
Build Configuration loading of ADR-003, the version name of ADR-005). It is not an app module.

Every module is always part of the build. There is no module that is included only when an Android SDK is present
(ADR-005).

### 2. Allowed dependencies

Only the project dependencies in this table are allowed. Every other project dependency fails the build.

| Module | May depend on (`implementation` / `api`) | May depend on in tests only |
|---|---|---|
| `:plugin-api` | none | none |
| `:core` | `:plugin-api` (as `api`) | `:testing`, `testFixtures(:plugin-api)` |
| `:provider-*`, `:budgetapp-*`, `:fileformat-*` | `:plugin-api` | `testFixtures(:plugin-api)` |
| `:persistence` | `:core` | `:testing`, `testFixtures(:plugin-api)` |
| `:app` | `:core`, `:persistence`, every `:provider-*`, `:budgetapp-*` and `:fileformat-*` | `:testing`, `testFixtures(:plugin-api)` |
| `:testing` | `:core` | none |
| `:architecture-tests` | none (Konsist reads the source files) | `testFixtures(:plugin-api)` (to read the `@UseCase` annotation type) |

The directions in words: the plug-in contracts depend on nothing; the core depends only on the contracts; each
plug-in depends only on the contracts, never on the core and never on another plug-in; persistence depends on the
core; the app depends on everything and nothing depends on the app.

Allowed external libraries per module group (checked by §6.1 like project dependencies):

| Module group | External libraries allowed |
|---|---|
| `:plugin-api`, `:core` | Kotlin standard library, `kotlinx-coroutines-core`. No `android.*`, no `androidx.*`, no HTTP or JSON library. |
| `:provider-*`, `:budgetapp-*` | Kotlin standard library, `kotlinx-coroutines-core`, one HTTP library (`okhttp3`), `kotlinx-serialization-json` |
| `:fileformat-*` | the Kotlin standard library only: a File Pre-processor converts bytes it is given and never opens a connection (K-11) |
| `:persistence` | Kotlin standard library, `kotlinx-coroutines-core`, `androidx.room`, `androidx.sqlite` |
| `:app` | any AndroidX and Compose library, `androidx.work`, `androidx.browser` |

Test configurations of every module may also declare JUnit 5, `kotlinx-coroutines-test`, the AndroidX test
libraries (Android modules only) and the libraries of the `android-junit5` plugin (ADR-005); Konsist is declared only
by `:architecture-tests`. A new external library for a module group is an amendment of this ADR.

### 3. Ports of the core

The core reaches everything outside it through interfaces it owns in `slussen.core.port`:

- storage ports, one per aggregate of the entity model (for example `SentRecordStore`, `BankTransactionStore`,
  `OperatorSettingsStore`), implemented by `:persistence` (ADR-002);
- `DecisionJournalRecorder` (write-only, returns `Unit`, never throws to the caller) and `DecisionJournalReader`
  (read for export only), implemented by `:persistence`; `DecisionJournalControl` (current size, size limit,
  clearing; UC-010 A2-A4), which reads no entry;
- `Clock` (`java.time.Clock`, injected; the core never calls `Instant.now()` or `System.currentTimeMillis()`);
- `SlussenLog` (ADR-006);
- `NewTransactionNotifier` and `ScheduledFetchScheduler`, implemented by `:app`.

### 4. Plug-in contracts

The contracts are interfaces in `:plugin-api`. They use Slussen's vocabulary only (glossary terms, written as
code identifiers). They carry no type, name or constant of a particular Data Provider, File Format or Budget App.
Every number that comes from an outside party is a declared property of the plug-in whose KDoc names where it
comes from, never a constant of the core.

Shared value types in `slussen.pluginapi.value`: `Amount` (signed thousandths of the currency unit as `Long`,
entity model precision `12,3`), `CurrencyCode` (ISO 4217), `Secret` (a credential or session value whose
`toString()` returns a fixed mask; ADR-006), `BankAccountNumber` (masked like `Secret`; the core turns it into the
one-way `bank_account_fingerprint` and never stores it), `PluginSettings` (the plug-in's own Build Configuration
values, ADR-003), `RequestTimeout` (the request timeout Operator Setting, NFR-016).

#### 4.1 Data Provider (`slussen.pluginapi.provider`)

```kotlin
interface DataProvider {
    val profile: DataProviderProfile
    val bankConsent: BankConsentCapability?            // non-null exactly when profile.needsBankConsent
    suspend fun fetch(request: FetchRequest): FetchResponse
}

data class DataProviderProfile(
    val name: String,                  // DATA_PROVIDER.name
    val sourceKey: String,             // DATA_PROVIDER.source_key; fixed forever once released
    val givesStableReference: Boolean, // DATA_PROVIDER.stable_reference (FR-072, UC-012 BR-005)
    val needsBankConsent: Boolean,     // DATA_PROVIDER.bank_consent_required
)
```

- `FetchRequest` holds: the Bank Accounts to read (by `provider_reference`, or "all the Data Provider exposes" for
  identification), the fetch period as two dates, whether the Operator is present (`OperatorPresent` or
  `Unattended`, UC-001 BR-012), what to read (transactions, balance, or both), the stored session of each Bank for a
  Data Provider that needs a Bank Consent, the `RequestTimeout`, and a `ProviderRequestGate`.
- The plug-in calls `ProviderRequestGate.beforeRequest(kind, bankAccountReference)` before every request it sends.
  The gate is the core's: it records the Provider Request and answers `Allowed` or `Refused` (Request Limit,
  Unattended Access limit, Back-off; UC-001 BR-004, BR-007, BR-012). On `Refused` the plug-in sends nothing more and
  returns what it has. The plug-in never counts, waits or retries on its own.
- `FetchResponse` holds one outcome for the Data Provider and, per delivered Bank Account: its
  `provider_reference`, name, currency, kind, `BankAccountNumber` when the Data Provider gives one, the
  transactions (`stableReference` or null, booked date, amount, currency, payee, memo, `booked` flag, or
  `uninterpretable` with no content) and the Account Balance with the time the Data Provider reported it, or a
  failure for that Bank Account (UC-001 A8).
- The Data Provider outcome is one of: `Delivered`, `AskedToWait(scope = Provider | Bank(bankReference),
  until = Instant?)` (UC-001 A5), `RequestLimitWarning` (A15), `TransientFailure(kind = Timeout | Unavailable |
  Unreadable)` (A6, A14), `CredentialsRefused` (A7), `ConsentRefused(bankReference)` (A9). A Data Provider error
  text never leaves the plug-in (NFR-012): the outcome carries the kind only.
- The plug-in does not filter, deduplicate or classify. Leaving out pending and zero-amount transactions
  (UC-001 BR-002, BR-003), identifying Bank Accounts (UC-009 BR-004), every Back-off and every Transaction Status
  are core rules.

`BankConsentCapability`: `startConsentRequest(bank, returnAddress)` returns the address of the Bank's Strong
Customer Authentication and an opaque `state`; `completeConsent(answer)` returns the expiry the Bank or Data
Provider reports (C-007), the covered Bank Accounts and an opaque `ConsentSession` (masked like `Secret`, ADR-006
§2); `revoke(session)` returns `Ended` or `NotConfirmed`. Checking that an answer belongs to the most recent Consent Request, arrives
within the Consent Request lifetime and is used once (UC-007 BR-002), storing the Bank Consent and deleting it on
revocation (UC-007 BR-007) are core rules.

#### 4.2 Budget App (`slussen.pluginapi.budgetapp`)

The contract is split so that the right to write can be confined (§6.2, K-05):

```kotlin
interface BudgetApp {
    val profile: BudgetAppProfile
    val reader: BudgetAppReader
    val writer: BudgetAppWriter
}

data class BudgetAppProfile(
    val name: String,
    val supportsClearedMarker: Boolean,            // NFR-015, FR-090; YNAB: true (C-009)
    val supportsApprovalMarker: Boolean,           // UC-006 BR-008; YNAB: true (C-009)
    val supportsLinkedTransfers: Boolean,          // UC-006 A10
    val matchingDateWindowDays: Int,               // glossary Matching Date Window; YNAB: 10 (C-009)
    val importIdentifierMaxLength: Int,            // YNAB: 36 (C-009)
    val payeeMaxLength: Int,                       // YNAB: 200 (C-009)
    val memoMaxLength: Int,                        // YNAB: 500 (C-009)
)
```

These declared properties are the Budget App's markers and rules as numbers and switches. The core reads them and
never holds a YNAB number. Each implementation's KDoc names where each value comes from.

- `BudgetAppReader.readBudgets()` returns each Budget and its Budget Accounts with name, currency and balance.
- `BudgetAppReader.readChanges(budgetReference, since: ChangeCursor?, from: LocalDate)` returns the Budget App
  Transactions (reference, Budget Account, date, amount, payee, Import Identifier or null, `state` Present, Deleted
  or Merged, and the reference of the Budget Account a linked transfer points to) plus the next `ChangeCursor`.
  `ChangeCursor` is an opaque string the core stores and hands back; only the plug-in knows what it means
  (UC-003 BR-016). Recognising a merge, a deletion or a Transfer Counterpart is the plug-in's job, because it
  depends on the Budget App's behaviour; classifying a transaction as Typed Entry, Slussen or Other Import Path is
  the core's (UC-003 BR-008).
- `BudgetAppWriter.create(budgetReference, items)` takes, per item, the Budget Account, Import Identifier, date,
  amount, payee and memo already shortened by the core (UC-006 BR-015), `cleared`, `awaitingApproval`, and the
  other Budget Account for a linked transfer. It returns one result per item: `Created(reference,
  counterpartReference?)`, `AlreadyPresent(reference?)`, `Rejected(reason)` or `Unknown` (UC-006 steps 8-9, A8,
  A9). The `reason` of a rejection is the Budget App's text, shown to the Operator (UC-006 A9) and never written to
  the Decision Journal or a log in the default mode.
- `BudgetAppWriter.markCleared(budgetReference, counterpartReference)` is the only change to an existing Budget
  App Transaction the contract offers (FR-055, UC-006 BR-011).
- Credentials refused is a typed result of every call, never an exception with text (UC-003 A2, UC-006 A6).

#### 4.3 File Pre-processor (`slussen.pluginapi.fileformat`)

```kotlin
interface FilePreprocessor {
    val profile: FileFormatProfile
    fun convert(file: ByteArray): ConversionResult        // pure: no I/O, no clock, no state
}

data class FileFormatProfile(
    val name: String,                 // FILE_FORMAT.name
    val sourceKey: String,            // FILE_FORMAT.source_key; fixed forever once released
    val defaultUploadWindowDays: Int, // FILE_FORMAT.upload_window_days, 1..366 (FR-084, FR-086); see ADR-003 §4
)

sealed interface ConversionResult {
    data class Converted(val file: StandardImportFile) : ConversionResult
    data class Rejected(val reason: String) : ConversionResult   // shown to the Operator, UC-002 A2
}
```

The **Standard Import Format** (FR-083, UC-002 BR-007) is the Kotlin type `StandardImportFile`: a list of
`StandardImportTransaction(reference: String, referenceOrigin: Supplied | Derived, bookedDate: LocalDate,
amount: Amount, currency: CurrencyCode?, payee: String?, memo: String?, booked: Boolean)`. It is a type, not a
file layout: nothing is written to disk in it. Every transaction has a non-empty `reference` (UC-002 BR-002,
FR-085). `convert` returns `Rejected` when any part of the file cannot be converted (UC-002 BR-001). Deciding that
transactions sharing a reference have no Stable Reference (UC-002 A4), leaving out pending and zero amounts, and
the all-or-nothing store are core rules.

#### 4.4 Contract tests

`:plugin-api` publishes abstract JUnit 5 contract tests in its `testFixtures`: `DataProviderContractTest`,
`BudgetAppContractTest`, `FilePreprocessorContractTest`. Each plug-in module's tests extend the matching one with
invented recorded answers. They check, for example, that `beforeRequest` is called before every request, that a
`Refused` stops the plug-in, that no `toString()` of a result reveals a `Secret`, that `convert` rejects a file with
one unreadable line, and that every declared number lies in its range.

### 5. Composition

`:app` holds the only composition root, `slussen.app.di.CompositionRoot`, written by hand (no dependency injection
framework). It lists the plug-ins of the build explicitly:

```kotlin
val plugins = Plugins(
    dataProviders = listOf(SimpleFinDataProvider(settings("simplefin")), EnableBankingDataProvider(settings("enablebanking"))),
    budgetApp = YnabBudgetApp(settings("ynab")),
    filePreprocessors = listOf(/* the one File Pre-processor of this build */),
)
```

Adding a Data Provider or File Pre-processor, or replacing the Budget App (UC-012 A2-A4), touches only: the new
module, one line in `settings.gradle.kts`, one dependency line in `app/build.gradle.kts`, one entry in
`CompositionRoot`, its Configuration Template (ADR-003), and the allowed-dependency table of §2 when it is a new
module group. It touches no file in `:core` or `:plugin-api` (NFR-014, NFR-015).

`CompositionRoot` hands the core the `BudgetAppReader` and the `BudgetAppWriter` separately: the Comparison and the
export receive only the reader, the upload only the writer and the reader.

UI: one ViewModel per screen in `slussen.app.ui.<screen>`; composables receive state and callbacks only. Background
work: the Scheduled Fetch runs as a WorkManager periodic worker in `slussen.app.schedule`.

### 6. Enforcement

Two independent layers, both wired into `gradlew check`, so neither can be skipped by building a subset.

#### 6.1 Module graph check (Gradle)

A task `verifyModuleGraph` in `build-logic/` reads the declared (first-level) project and external dependencies of
every module and configuration and compares them with the tables of §2; libraries those bring in transitively are
not checked. It fails on any project dependency not in the table, on any external library outside the module
group's list, and on any module whose name does not follow the naming rule of §1. The tables are kept in one Kotlin file in `build-logic/` that cites this ADR. Because a class of a
module that is not on the classpath cannot compile, this layer is what makes a forbidden dependency impossible,
including through a fully qualified name.

#### 6.2 Konsist rules (`:architecture-tests`)

Konsist scans the source files of every module (`Konsist.scopeFromProject()`), production and test, so it does not
depend on the Gradle graph. Each rule is one JUnit 5 test named after its ID.

| ID | Rule | Requirement it protects |
|---|---|---|
| K-01 | Files in `:plugin-api`, `:core`, every plug-in module and `:testing` import nothing from `android.`, `androidx.` or `com.google.android.` | C-001 with portability of the core; vision *Replaceable Budget App* |
| K-02 | Files in `:core` import nothing from `slussen.provider..`, `slussen.budgetapp..`, `slussen.fileformat..`, `slussen.persistence..` or `slussen.app..` | NFR-014, NFR-015 |
| K-03 | Files in a plug-in module import from `slussen..` only `slussen.pluginapi..` and their own package root | NFR-014, NFR-015, UC-012 BR-005 |
| K-04 | No declaration name, string literal or comment in `:plugin-api`, `:core` or `:persistence` contains `ynab`, `simplefin`, `enablebanking`, `enable banking` or the root of the File Pre-processor module (case-insensitive); the list grows with every plug-in module | NFR-015, C-009 kept in its own part |
| K-05 | Within `:core`, only `slussen.core.upload` references `BudgetAppWriter` or the `writer` property of `BudgetApp`, and only `slussen.core.upload.UploadExecutor` calls `create` or `markCleared`; within `:app`, only `slussen.app.di` references them (to hand the writer to the core) | FR-034, FR-055, UC-006 BR-001, BR-011 |
| K-06 | Nothing in `slussen.app.schedule` references `slussen.core.upload..` | FR-077, UC-001 BR-013 |
| K-07 | Only `slussen.core.journal.JournalExport` and `:persistence` reference `DecisionJournalReader` | NFR-006, UC-011 BR-002 |
| K-08 | Classes in `slussen.app.ui..` and `slussen.core.status..` reference no `DataProvider`, `BudgetAppReader` or `BudgetAppWriter` | UC-008 BR-001, FR-003 |
| K-09 | Room annotations (`androidx.room.*`) appear only in `:persistence`; no file references `fallbackToDestructiveMigration` | ADR-002, NFR-017 |
| K-10 | Each `:provider-*` module has exactly one class implementing `DataProvider`, named `<Name>DataProvider`; each `:budgetapp-*` exactly one implementing `BudgetApp`, named `<Name>BudgetApp`; each `:fileformat-*` exactly one implementing `FilePreprocessor`, named `<Name>FilePreprocessor` | glossary File Pre-processor ("one per File Format"); UC-002 BR-007 |
| K-11 | Files in `:fileformat-*` import nothing from `okhttp3.`, `java.net.`, `java.io.File` or `kotlinx.coroutines.` | §4.3 (pure conversion) |
| K-12 | Classes in `slussen.app.ui..` import nothing from `slussen.persistence..` or a plug-in package | §5 |
| K-13 | Logging rules of ADR-006 (L-01 to L-05) | NFR-007, NFR-008 |
| K-14 | Naming and marker rules of ADR-004 (T-01 to T-05) | `workflow.md` Traceability |

A rule that passes because the scope it inspects is empty fails instead: every Konsist test first asserts that its
scope holds at least one file (lesson from the Predecessor App's vacuous UI rule).

Phase 6 proves both layers by planting a forbidden dependency and a forbidden import, seeing `gradlew check` fail,
and removing them.

### 7. Use cases and the modules they touch

All use cases also touch `:testing` and `:architecture-tests` through their tests; the table leaves those out.
"Data Provider modules" means `:provider-simplefin` and `:provider-enablebanking`.

| Use case | Modules | What each part does |
|---|---|---|
| UC-001 Fetch Transactions | `:app`, `:core`, `:plugin-api`, Data Provider modules, `:budgetapp-ynab`, `:persistence` | `:app`: the Fetch action, the Scheduled Fetch worker (A12), the New Transaction Notification (BR-014). `:core` `fetch`: Back-off, Fetch Cooldown, Request Limit, Unattended Access, identification, booked and non-zero filter, Fetch Results. Data Provider modules: the requests. `:budgetapp-ynab`: through the included UC-003. `:persistence`: Bank Transactions, Account Balances, Fetches, Provider Requests, Back-offs. |
| UC-002 Import a Transaction File | `:app`, `:core`, `:plugin-api`, `:fileformat-<format>`, `:budgetapp-ynab`, `:persistence` | `:app`: choosing the file. `:core` `fileimport`: all-or-nothing, references, occurrence numbers. `:fileformat-<format>`: conversion into the Standard Import Format. `:budgetapp-ynab`: through UC-003. |
| UC-003 Compare With the Budget App | `:app`, `:core`, `:plugin-api`, `:budgetapp-ynab`, `:persistence` | `:app`: the re-read action and the statuses. `:core` `comparison` and `transfer`: the order of BR-001, Import Identifier, Transfer detection, Eligible Transactions. `:budgetapp-ynab`: reading changes since the last `ChangeCursor`. |
| UC-004 Review Doubtful Transactions | `:app`, `:core`, `:persistence` | `:core` `review`: choices per Transaction Status, locking after Upload. No plug-in. |
| UC-005 Review Transfers | `:app`, `:core`, `:persistence` | `:core` `transfer`: Transfer Decisions, Funded By and Transfer Partner. No plug-in. |
| UC-006 Upload to the Budget App | `:app`, `:core`, `:plugin-api`, `:budgetapp-ynab`, `:persistence` | `:core` `upload`: Upload Plan, the pause, the check before writing, Sent Record before sending (ADR-002 §3), outcomes. `:budgetapp-ynab`: the write and marking Cleared. |
| UC-007 Grant Bank Consent | `:app`, `:core`, `:plugin-api`, `:provider-enablebanking`, `:persistence` | `:app`: opening the Bank's Strong Customer Authentication and receiving the return. `:core` `consent`: one-time, most recent, lifetime, revocation. `:provider-enablebanking`: the only Data Provider of this build that needs a Bank Consent. `:persistence`: the consent database, excluded from backups. |
| UC-008 Check Import Status | `:app`, `:core`, `:persistence` | `:core` `status`: counts, Source Health, Freshness, Blocked Reasons, outdated balances, the expiry warning. No plug-in and no network (K-08). |
| UC-009 Manage Account Mappings | `:app`, `:core`, `:persistence` | `:core` `mapping`: identification, locks, retire and restore, Funded By and Transfer Partner. No plug-in: proposals come from what the Source last delivered (A6). |
| UC-010 Adjust Settings | `:app`, `:core`, `:persistence` | `:core` `settings`: ranges, defaults, Transfer Descriptions, re-status; `:core` `journal`: size limit and clearing. `:app`: Detailed Logging read by the log adapter (ADR-006), and the Scheduled Fetch worker rescheduled when the Fetch Schedule changes (A6). |
| UC-011 Export the Decision Journal | `:app`, `:core`, `:plugin-api`, `:budgetapp-ynab`, `:persistence` | `:app`: choosing where to save. `:core` `journal`: the export, codes for payee and memo. `:budgetapp-ynab`: reading current transactions (A2 falls back to the stored copy). `:persistence`: the journal database. |
| UC-012 Set Up an Own Build | `build-logic/`, `build-config/`, `:app`, `:plugin-api`, every plug-in module, `.gitignore`, `README.md` | `build-logic/`: reading, checking and refusing the Build Configuration (ADR-003), the version name (ADR-005). `build-config/`: the Configuration Templates. `:app`: the generated Build Configuration and the composition root. `:plugin-api` and plug-in modules: A2-A4. `README.md`: the how-to (Phase 8). |

## Consequences

- A plug-in cannot call a core rule, because the core is not on its classpath; it can only answer through the
  contract. A Data Provider therefore cannot filter, deduplicate or decide, which keeps UC-001 BR-002/BR-003,
  UC-003 and UC-009 BR-004 in one place.
- The core cannot name YNAB, so every YNAB number reaches it as a declared property (C-009 values in
  `BudgetAppProfile`). A second Budget App fills the same profile.
- `ProviderRequestGate` makes the plug-in responsible for calling the gate before every request. Forgetting it is
  caught by `DataProviderContractTest`, not by the compiler.
- `:plugin-api` changes are rare and deliberate: a change there touches every plug-in. A change to a contract is an
  amendment of this ADR.
- Adding a plug-in edits `CompositionRoot` and `settings.gradle.kts`. Neither holds a rule that compares, reviews or
  decides, so NFR-014 and NFR-015 hold; the reviewer checks that the diff of such a change has no file under
  `core/` or `plugin-api/`.
- The build needs an Android SDK everywhere it runs (ADR-005).
- Ten modules plus `build-logic/` is more structure than the Predecessor App had; each module boundary is one the
  specification names.

## Alternatives

- **Predecessor layout (`:domain` + `:source-*` + `:budget-ynab` + `:platform` + `:app`).** Rejected: plug-ins
  depended on the whole domain, so nothing stopped a Data Provider from using a rule, and the contracts were
  mixed with the rules.
- **Contracts inside `:core`.** Rejected for the same reason: a plug-in that depends on `:core` sees every rule.
- **Discovery of plug-ins by `ServiceLoader` or reflection.** Rejected: invisible wiring, R8 keep rules, and a
  missing plug-in would surface at run time instead of at compile time.
- **A dependency injection framework (Hilt, Koin).** Rejected: one composition root of a few dozen lines is
  readable and needs no annotation processing; revisit if the root grows past one screen of code.
- **Konsist alone, without the Gradle graph check.** Rejected: Konsist reads imports and text, so a fully qualified
  name or a new Gradle dependency would pass it.
- **The Predecessor App's text-scanning `verifyArchitecture` task.** Rejected: it duplicated what Konsist does,
  scanned a hard-coded module list and could pass on an empty scope.
- **One module per use case (vertical slices as modules).** Rejected: the specification's boundaries are between
  rules and external parts, not between use cases; several use cases share the Comparison (UC-003 is included by
  UC-001, UC-002, UC-006).

## AIUP guidance

- AIUP has no stack plugin for Android or Kotlin. Module structure, plug-in contracts and architecture tests are
  outside every Marketplace skill; `docs/workflow.md` says only that ADRs under `docs/**/adr/` are "followed like
  existing conventions". This ADR is therefore project-made, and its use case map is not a format any skill
  defines.
- No Marketplace skill defines an ADR format. The sections (Status, Context, Decision, Consequences, Alternatives)
  come from the construction kickoff plan.
- The stack plugins name services and views "after the use case's domain"; this ADR applies that to Kotlin
  packages (`slussen.core.fetch`, `slussen.core.upload`, ...) rather than to use case folders, because several use
  cases share one domain.
