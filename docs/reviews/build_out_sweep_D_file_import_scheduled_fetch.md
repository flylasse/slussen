# Area D: Transaction File import, Scheduled Fetch, leftovers of fetching

Read-only survey of /home/user/slussen, branch build-out (HEAD 24235f0). Nothing in the repository was changed; Gradle
was not run; GitHub was not reachable.

Sources read: AGENTS.md; UC-001, UC-002, UC-012, UC-013 in full; mockups import-file.html (all states),
budget.html#care-fund, #care-fund-this-build, #fetch-failed-scheduled, overview.html#notification,
settings.html#fetch-schedule, connections.html#hsa-csv; ADR-001 §4.3, §5, §7; ADR-002 §2, §3 (O-1, O-3, O-11), ADR-007
§1-§3; ADR-008 §2, §4, §5; ADR-005 (SDK levels, WorkManager pin); entity model (FILE_FORMAT, BANK_ACCOUNT,
TRANSACTION_FILE, BANK_TRANSACTION, UNREADABLE_TRANSACTION, FETCH, FETCH_RESULT, PROVIDER_REQUEST, OPERATOR_SETTINGS);
glossary; requirements FR-004..009, FR-076..080, FR-083..086, FR-094, NFR-022; transactions_plan.md §0, §1, §6;
archive/construction_kickoff_plan.md (O-P2-42, O-P2-53..74, §7). Code: fileformat-hsa-csv, plugin-api fileformat and
testFixtures, core fetch/operation/request/status/comparison/settings/port, persistence schema 5.json and RoomStores,
testing fakes, app manifest, CompositionRoot, SlussenApplication, schedule package, SettingsViewModel, build-logic
DevicePermissions. Markers and @UseCase counted with grep over all modules.

## 1. What is unbuilt

### UC-002 Import a Transaction File: nothing of it is built

Checked: no `// UC-002` marker and no `@UseCase(id = "UC-002")` anywhere; `HsaCsvFilePreprocessor.convert` is
`TODO(...)` (would throw `NotImplementedError` if called); `FakeFilePreprocessor` in `:testing` errors on convert; no
`:core` `fileimport` package; no `FileImportStore` port (O-3); no `transaction_file` table in `slussen.db` schema 5;
`RoomImportStatusStore.latestTransactionFiles()` returns `emptyList()` (RoomStores.kt:544). What exists already:
`StoredTransactionFile`/`TransactionFileOutcome` in `core/port/ImportStatusStore.kt`, and `ImportStatus` already
derives Source Health and Freshness of a file-imported Bank Account from them (UC-002 BR-006 read side is ready);
`ComparisonRules` already uses `file_upload_window_days` for file-imported Bank Accounts (BR-004 read side);
`operator_settings.file_upload_window_days` exists (default 60); UC-013 A4 (adding a Bank Account of a File Format,
with its balance Data Provider) is built (`BudgetsAndBankAccounts.addFromTransactionFiles`, `ui/fileformat`).

Unbuilt, all of it:
- Main steps 1-10: entry "Import Transaction File" (Budget tab Source block per mockup), file choice, conversion,
  booked/non-zero filter, references, all-or-nothing store, `transaction_file` record, Decision Journal, Comparison,
  result screen.
- A1 cancel, A2 cannot convert (reject, reason, record Rejected), A3 no importable transaction, A4 identical references
  (no Stable Reference, occurrence numbers, Needs Review), A5 Retired Account refused, A6 Import Identifier collision
  rejects the file, A7 Bank Account not added yet (UC-013 A4 then continue).
- BR-001..BR-007 (BR-004 and BR-006 have their read side in Comparison/ImportStatus but nothing writes files);
  BR-005 (no balance from a file) is satisfied trivially.
- Coordinator: `OperationKind` has no file import kind (ADR-007 §2 row "Import a Transaction File").
- Storage: O-3 `FileImportStore.importWhole`, the `transaction_file` table, a new schema version 6 with a hand-written
  `MIGRATION_5_6`, its schema JSON, JVM migration tests and `SlussenDatabaseMigrationIT`; `SlussenStorage.MAIN_VALUES`
  (ADR-002 §8) for the new columns.
- `fileformat-hsa-csv`: the conversion; `FilePreprocessorContractTest` only checks the profile (contract tests for a
  rejection, non-empty references, purity of `convert` are missing).
- Screens: import-file.html all 8 states; remove the build-state lines (budget.html#care-fund-this-build,
  overview "Account Balance only: Transaction Files come in a later version", settings this-build line, File Format
  page text "Its Transaction Files are imported from the Bank Account").
- Permissions: the Android Storage Access Framework picker (ACTION_OPEN_DOCUMENT) needs no device permission, so UC-002
  needs no NFR-022 change.

### HSA CSV format: there is no description and no sample anywhere

Searched every .md, .html, .kt, .kts, .csv and .txt outside build/ and vendor/ for "hsa" and for .csv files. The
specification says only: File Format name "HSA CSV export", Source Key `hsa-csv` (ADR-001 §4.3, O-P2-42, S-31); C-010
one export format; the Standard Import Format type (`StandardImportTransaction(reference, referenceKind, bookedDate,
amount, currency?, payee?, memo?, booked)`). The mockups name an invented "Harbor Health Trust statement" and files
"harbor-statement-2026-10-15"; M-17 (archive) says the invented household mirrors the Operator's real structure (a
Budget fed by a Transaction File, its balance from SimpleFIN). Migration tests use an invented `example-hsa` row. So
column layout, date format, sign convention, decimal separator, encoding, header/footer lines, pending/status column,
transaction id column, currency column: all unknown. Only the Operator can supply this.

### UC-001 Fetch Transactions: what is left in area D (A4, A9, A13 excluded)

Checked by grep of markers and @UseCase scenarios: A1-A3, A5-A8, A10, A11 (all), A15, A16, A17, A19, A20, A21 and
BR-001..BR-012, BR-015, BR-016 have markers/tests (A12 and BR-014 appear only in a placeholder KDoc and a comment).
Unbuilt:
- **A12 Scheduled Fetch, BR-013 Fetch Schedule**: `app/.../schedule/SchedulePackage.kt` is a placeholder object;
  `OperationCoordinator.run` does `check(startedBy == StartedBy.Operator)`, i.e. a Schedule origin throws;
  no deferral of a Scheduled Fetch while Plan Open/Uploading although `coordinator_state.scheduled_fetch_deferred`
  exists in schema 5; `FetchTransactions` hard-codes `OperatorPresence.OperatorPresent` and `DataProviderGate` writes
  `unattended = false` and `bank_account_id = null` on every Provider Request; no WorkManager dependency in
  `app/build.gradle.kts` (the catalog has `androidx-work-runtime` 2.10.2, unused).
- **A14 Transient failure in a Scheduled Fetch**, BR-005's scheduled half (no retry until the next schedule).
- **A1's scheduled half**: a Back-off with cause Request Limit Warning must stop a Scheduled Fetch; today only the
  Operator path exists (A16 confirm).
- **BR-012 unattended half** (record requests as unattended; counting is A13, other analyst, but the Scheduled Fetch
  must write `provider_request.unattended = true` and per-Bank-Account rows for it to be countable).
- **A12 step 3 / BR-014 New Transaction Notification**: nothing; `EligibleTransactions.WAITING` in ComparisonRules is
  the nearest count ("as far as this build gives them"). Needs a count function over all non-Retired Bank Accounts
  (Eligible + Possible Import By Other Path, Needs Review, Decision No Longer Holds, Guessed Transfer, Transfer
  Counterpart Changed, Outcome Unknown waiting, Unreadable Needs Review), an Android notification channel
  (silent, low importance, badge via `setNumber`), update after every operation that changes the number, removal at 0.
- **UC-010 A6 Change the Fetch Schedule** (not my use case, but A12 needs it): Settings shows "Scheduled Fetches come in
  a later version" (SettingsViewModel.kt:171); the worker must be rescheduled when the value changes.
- **A11 "the Data Providers that have a Bank Account in one Budget"**: `FetchTransactions` has `fetch(service)` and
  `fetchAll()` only; no per-Budget fetch, and no mockup draws its entry (budget.html has per-Source Fetch only).
- Postcondition "the Fetch is recorded with who started it": `FETCH.started_by` Schedule exists in the port enum; never
  written.

### Device permissions and components for A12/BR-014 (ADR-001 §5, NFR-022, ADR-005)

Today: manifest declares only `INTERNET`; `build-logic/DevicePermissions.allowed` = INTERNET +
`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; `verify<Variant>Permissions` runs on the merged manifest
before packaging and in `check`. ADR-001 §5 already names WorkManager for the Scheduled Fetch (`slussen.app.schedule`,
origin `Schedule`); ADR-005 §1 pins WorkManager. targetSdk 35, minSdk 26.
Needed (from my knowledge of WorkManager 2.10's manifest; I could not inspect the AAR, it is not in the local cache):
- WorkManager's library manifest brings `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`,
  `FOREGROUND_SERVICE`. The permission check will fail the build as soon as the dependency is added.
  `FOREGROUND_SERVICE` can be removed with `tools:node="remove"` (no foreground work is needed); the other three are
  needed (network constraint, wake lock while running, rescheduling after reboot).
- `POST_NOTIFICATIONS` (runtime permission on Android 13+, targetSdk 35) for the New Transaction Notification; the
  Operator must grant it once; BR-014 says nothing changes when the device does not allow notifications.
- Components: a `CoroutineWorker` in `slussen.app.schedule` that awaits `SlussenApplication.services` (the start
  sequence of ADR-002 §6 runs in `Application.onCreate`, so it also runs for a worker-started process), a
  `PeriodicWorkRequest` (unique name, KEEP/UPDATE), a notification channel, a launcher badge through the notification.
- Spec changes this forces: NFR-022 text ("this build needs network access only") and ADR-001 §5's list, plus
  `DevicePermissions` and its unit tests; possibly ADR-005 §7 evidence items (worker runs under Doze and Samsung
  battery management; notification shown/removed) as device evidence.

### Issue #16 (cannot reach GitHub; reconstructed)

The plan takes "#16 items 1, 3": item 1 balance time (S-T-04, built: `Read.balanceReportedAt`, `reported_at`), item 3
balance in another currency (S-T-05 = UC-001 A21, built). The issue was evidently opened from archive O-P2-74, which
lists four things: (a) the balance time in `Read` (= item 1, done); (b) the Settings entry and first-run card naming
"grant a Bank Consent" as a next step although this build cannot (UC-013 BR-008, UC-008 BR-011): today handled by the
P9-13 texts (`Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD`) and resolved for good only by Enable Banking (other analyst);
(c) a reported currency that differs from the Bank Account's (= item 3, done); (d) the Budget tab did not list the
balance Data Provider of a File Format Bank Account as a Source: now built (`ImportStatus.sourcesOf` with
`balanceOnly`, marker UC-001 BR-011, asserted in `UC008CheckImportStatusTest` line 287). So what is left of #16 is
probably only (b), owned by the Enable Banking work, plus closing the issue. Item numbering 2 and 4 is my inference;
verify on GitHub before closing.

### UC-012 Set Up an Own Build

Markers/tests exist for main scenario, A1, A2, A3 (profile only), A4, A5, A6, BR-001, BR-002, BR-003, BR-005, BR-006,
BR-008, BR-009, BR-011, BR-012. Not built: A3 in substance (a working File Pre-processor applied by the import rules)
arrives with UC-002. BR-004 (rotate exposed credentials), BR-007 (Start Date, through UC-013), BR-010 (first Operator
builds the same way) are procedural/README rules; nothing to build. Nothing else.

### UC-013 Connect Budgets and Bank Accounts

Every step, flow (A1-A12) and BR-001..BR-015 has markers or tests. Not built: nothing in UC-013 itself. UC-002 A7
("adds the Bank Account as UC-013 A4 describes, then continues at step 2") is the only link left, and belongs to UC-002.

## 2. Proposed phases

| # | Name | Builds | Writes | Depends on | Size | Schema |
|---|---|---|---|---|---|---|
| D0 | Spec changes for area D | NFR-022 + ADR-001 §5 permission list; UC-002 clarifications (cross-file references, `transaction_count` meaning, A7 entry, currency of a line, rejection reason length/privacy); HSA CSV format description (ADR-001 §4.3 or a module KDoc); Fetch Schedule timing rule; when the notification permission is asked; mockup states for A7 and the permission ask | docs/ | operator answers (Q1-Q6) | S | no |
| D1 | Storage for Transaction Files | `transaction_file` table (FKs to `bank_account`, `file_format`, index on `bank_account_id`), schema 6, `MIGRATION_5_6`, schema JSON, S-39 check, JVM + instrumented migration tests (1..5 -> 6), O-3 `FileImportStore.importWhole` (occurrence allocation and collision check as O-1), `latestTransactionFiles`, MAIN_VALUES, in-memory fake in `:testing` | `:persistence`, `:core` port, `:testing` | D0 (if a column is added for no-stable-reference file rows, see S2) | M | yes (v6; coordinate the version number with every other analyst's schema change in the run) |
| D2 | UC-002 core | `slussen.core.fileimport`: steps 4-10, A1-A6, BR-001..BR-007; `OperationKind.FileImport` admitted only in Normal; Decision Journal; Comparison after import in the same operation; fake File Pre-processor in tests | `:core`, `:testing` | D1, UC-003 (built) | M | no |
| D3 | HSA CSV File Pre-processor | `HsaCsvFilePreprocessor.convert` per the Operator's format, derived reference recipe fixed forever, `Rejected` with line number; extend `FilePreprocessorContractTest` (rejection, non-empty references, determinism) | `:fileformat-hsa-csv`, `:plugin-api` testFixtures | Q1 answer | S-M | no |
| D4 | UC-002 screens | "Import Transaction File" on the Budget tab's file Source (and per Bank Account when several share the File Format), SAF picker, the 8 import-file.html states, A7 from the File Format page, build-state lines removed | `:app` | D2 (D3 for real files) | M | no |
| D5 | Scheduled Fetch in the core | coordinator origin `Schedule` (admitted in Normal, deferred via `scheduled_fetch_deferred` in Plan Open/Uploading and started when Normal returns); `FetchTransactions.scheduledFetch()` = A11 unattended; Provider Requests `unattended = true` with `bank_account_id`; A1 with Request Limit Warning; A14 no retry; `FETCH.started_by` Schedule; never prepares a plan | `:core`, `:persistence` (write path only), `:testing` | nothing new; hand A13's counting to the Enable Banking analyst | M | no (column exists) |
| D6 | Worker, Fetch Schedule setting, New Transaction Notification | WorkManager dependency, `slussen.app.schedule` worker, permission check list + manifest `tools:node="remove"` for FOREGROUND_SERVICE, UC-010 A6 edit and reschedule, the count function in `:core` `status`, notification channel and badge, POST_NOTIFICATIONS ask, update after every operation | `:app`, `:core` status, `build-logic` | D0, D5 | M-L | no |
| D7 | Fetch leftovers | A11 per-Budget Fetch (core function; UI only if D0 adds a mockup entry); close #16 | `:core` (`:app`) | — | S | no |

Order: D0 -> D1 -> D2 -> D3 -> D4 and, independently, D5 -> D6; D7 any time.

## 3. Operator questions

1. **What does your HSA's CSV export look like?** Need an anonymised sample (header + 3-5 lines, amounts and names
   invented but layout exact) or a description: columns, date format, amount sign or debit/credit columns, decimal
   separator, encoding, header/footer/summary lines, a status/pending column, a transaction id column, a running
   balance column, the currency. Options: (a) give a sample now; (b) build UC-002 against a fake pre-processor and
   leave the HSA conversion rejecting every file "not built yet"; (c) let the agent guess a common layout.
   Recommend (a), else (b). Redo if wrong: all of D3, and if a real file was already imported with a wrong derived
   reference recipe, those Import Identifiers are permanent (ADR-008 §4): a recipe change later duplicates every
   transaction of earlier files.
2. **Which fields make the derived reference** when the file has no transaction id? Options: date+amount+description
   (+memo); plus running balance if the file has one (makes identical same-day lines distinguishable); plus an
   in-file line number (breaks re-import of overlapping files). Recommend date, amount, description, memo, and the
   running balance only if present and stable across exports. Redo: same as Q1, permanent identifiers.
3. **May Slussen ask the device for the permissions the Scheduled Fetch and the notification need?** NFR-022 says
   network only. List: ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED (install-time, no prompt, brought by
   WorkManager) and POST_NOTIFICATIONS (runtime prompt). Options: (a) all four; (b) without RECEIVE_BOOT_COMPLETED
   (no Scheduled Fetch after a reboot until the app is opened); (c) no notification (drop POST_NOTIFICATIONS, keep
   the badge-less schedule). Recommend (a). Redo: manifest, permission check list, NFR-022/ADR-001 §5 text; small.
4. **When should Slussen ask for the notification permission?** Spec silent. Options: (a) once, when the Operator
   first opens Overview with the Fetch Schedule above 0; (b) once, when the Operator saves a Fetch Schedule above 0;
   (c) never ask, a Settings row links to Android's notification settings. Recommend (a) plus a passive line on the
   Fetch Schedule row when notifications are off. Redo: one screen flow and a mockup state.
5. **When during the day do Scheduled Fetches run?** Spec: "2 in any 24 hours, evenly spaced"; the mockup shows
   18:00/06:00. Options: (a) every 24/n hours from when the schedule was set, Android may shift it (inexact); (b) at
   fixed clock times, which the project rule makes an Operator Setting (new spec, new column, schema change).
   Recommend (a), showing the next approximate time. Redo for (b): a new Operator Setting, schema change, UC-010 row.
6. **Should Scheduled Fetches start by themselves right after this update?** `operator_settings.scheduled_fetches_per_day`
   is already 2 on your phone (default since schema 1). Options: (a) yes, as specified; (b) keep the stored value but
   start the schedule only after you open Settings and confirm once. Recommend (a) (Scheduled Fetch never uploads).
   Redo: one start-up rule; small.

## 4. Safe guesses

S1. Rejection reason (`ConversionResult.Rejected.reason`) is shown, cut to 200 characters (`rejection_reason` length),
    never logged with Detailed Logging off (it can hold file content; NFR-007), and is a pre-processor text in
    English like the rest of the app.
S2. Cross-file references (spec silent; BR-002 says "of the same Bank Account", A4 says "of the file"): a file line
    whose reference equals a stored Bank Transaction's Stable Reference is that transaction; more lines with that
    reference than stored ones are additional, kept without Stable Reference, Needs Review (BR-003); a file line that
    is unique in its file but identical (date, amount, payee, memo) to stored transactions without Stable Reference is
    counted against that group, never given a new Stable Reference. Stored Import Identifiers never change.
S3. `transaction_file.transaction_count` = booked, non-zero transactions the file delivered (entity model wording);
    the result screen also shows "new: n" from that import (mockup), computed at import time and not stored.
S4. A line whose currency is given and differs from the Bank Account's, or a missing currency: missing means the Bank
    Account's currency; a different one rejects the whole file (A2, BR-001), since a file has no Unreadable path.
S5. A later file that holds a known Supplied-reference transaction with changed payee/memo leaves the stored one
    unchanged and journals nothing (UC-002 step 8 journals first-time only).
S6. Entry point: "Import Transaction File" on the Budget tab's Transaction File Source; with two or more Bank Accounts
    of the File Format in one Budget, one action per Bank Account. A7 starts from the File Format page in Settings:
    after the UC-013 A4 form, the picker opens. No file size limit (a limit would be a Slussen-chosen number).
S7. A Comparison failure after a successful import (YNAB unreachable) keeps the import; the statuses stay "not
    compared yet" (UC-003 A1, S-T-08).
S8. Scheduled Fetch: WorkManager periodic work with a network-connected constraint only, unique work name, always
    `Result.success()` (A14, BR-005: never retried); it fetches only Data Providers in use (as `fetchAll`), skips one
    without credentials silently, and compares as A11.
S9. Notification tap opens the Overview; channel named "New transactions", importance low, no sound/vibration,
    `setNumber` for the badge; recomputed after every coordinator operation ends and at app start.
S10. A11 per-Budget Fetch has no mockup entry: build the core function, no UI, and record a spec-gap issue.

## 5. Risks

R1. Duplicates through derived references (S2): if the core treats references per file only, a transaction imported
    twice in identical-pair/single variations gets two Import Identifiers and goes to YNAB twice, silently as New.
    Needs tests with overlapping files before any upload phase.
R2. Permanent identifiers from a guessed HSA layout or recipe: once a real file is imported and uploaded, changing the
    recipe or the Source Key `hsa-csv` duplicates history. Do not let a guessed pre-processor near the Operator's data.
R3. Adding WorkManager fails `verifyReleasePermissions` (and `check`) at once; a sub-agent might "fix" it by widening
    `DevicePermissions.allowed` without the NFR-022/ADR-001 §5 change, which T-checks will not catch.
R4. Background reliability: Doze, app standby buckets and Samsung battery optimisation can delay or skip work for
    days; nothing in the JVM gate proves the worker runs. Also A21's `FetchNotices` is in memory only, so a notice from
    a Scheduled Fetch in a background process is lost when the process dies.
R5. Coordinator: `check(startedBy == Operator)` throws for a Schedule origin; a worker that calls the existing
    `fetchAll()` instead of a new scheduled path would count requests as attended (BR-012) and, once Enable Banking
    exists, break the PSD2 limit (C-006). Also all schema changes of the long run must share one version sequence:
    two phases each writing "version 6" will collide.

## Could not check

GitHub issue #16 text (reconstructed from O-P2-74); WorkManager 2.10's exact merged permissions (not in the local
Gradle cache); anything that needs a device (worker behaviour, notification, badge on the Operator's launcher).
