# ADR-002: Persistence With Room, the Sent Record Before Every Upload, and Backups

## Status

Proposed (2026-10-14, construction kickoff Phase 1). The Operator sets it to Accepted at the architecture gate.

## Context

What the specification requires of storage:

- **Exactly once.** FR-041, NFR-001, UC-006 BR-006: "A transaction is recorded in the Sent Record before it is
  sent." UC-006 step 6 records every Eligible Transaction of the plan and locks the Account Mappings; A7: when the
  Sent Record "cannot be stored completely", Slussen sends nothing. UC-009 BR-003: the lock happens at the first
  send, whatever the Budget App answers. `SENT_RECORD` constraints: unique `import_identifier` per
  `budget_account_id`; Not Yet Known and Unknown outcomes are resolved by a later read (UC-003 BR-017).
- **What survives a reinstall with restore, and an upgrade.** FR-042, NFR-010, NFR-017, UC-006 BR-014,
  UC-009 BR-006, BR-008, UC-010 BR-006: the stored Bank Transactions, the Sent Record, how Slussen recognises each
  Bank Account, the Account Mappings with their locks, Funded By and Transfer Partner, the Review Decisions and
  Transfer Decisions, the Operator Settings.
- **What never leaves the device in a backup or device-to-device transfer.** NFR-009, UC-007 BR-005: no log, no
  Bank Consent data, no Consent Request in progress. NFR-011, UC-011 BR-004: no Decision Journal. Vision,
  *Sensitive data*: "Neither logs nor bank sessions are ever included in device backups."
- **Unreadable stored data.** NFR-005, UC-008 A1: keep the unreadable original unchanged, start from the last good
  or default content, show a notice naming the part, do not crash.
- **The Decision Journal never affects decisions.** NFR-006, UC-010 BR-004, UC-011 BR-002; size limit by dropping
  the oldest entries (`DECISION_JOURNAL_ENTRY` constraints, UC-010 BR-003).
- **Counters that survive a restart.** `PROVIDER_REQUEST` is kept for at least 24 hours and survives a restart of
  the app (UC-001 BR-007, BR-012).
- The Operator decided (bootstrap plan O-09) that storage is Room.

Evidence from the Predecessor App (`scratch/construction_inventory.md` §4), used as evidence and not as a template:
nine JSON files in `filesDir` written through `<name>.tmp` and rename, the journal in `noBackupFilesDir`, and two
backup rule files that disagreed: the `:platform` copy excluded `resolutions.json` and `binding_registry.json`
(Review Decisions and Account Mapping locks, which NFR-010 requires in the backup), the `:app` copy excluded the
consent files instead, and a third list (`PlatformSecurePolicy.BACKUP_EXCLUDED_FILES`) repeated the first.

## Decision

### 1. Three Room databases, split by backup policy

Android Auto Backup and device-to-device transfer include or exclude whole files, so data with different backup
rules lives in different database files.

| Database | File | Backed up and transferred | Tables |
|---|---|---|---|
| Main | `slussen.db` in the app's database directory (`Context.getDatabasePath`) | **Yes**, and nothing else is | every entity of `docs/entity_model.md` except the four below, plus `budget_read_state` (§2) |
| Consent | `consent.db` in `Context.noBackupFilesDir` | No | `bank_consent`, `consent_request`, `bank_session` (§2) |
| Journal | `journal.db` in `Context.noBackupFilesDir` | No | `decision_journal_entry` |

`slussen.db` therefore holds: `budget`, `budget_account`, `budget_app_transaction`, `data_provider`, `file_format`,
`bank`, `bank_account`, `account_mapping`, `fetch`, `fetch_result`, `back_off`, `provider_request`,
`account_balance`, `transaction_file`, `bank_transaction`, `review_decision`, `transfer`, `upload_plan`,
`eligible_transaction`, `sent_record`, `transfer_counterpart`, `operator_settings`, `transfer_description`,
`budget_read_state`.

Slussen keeps no other persistent state: no SharedPreferences, no DataStore, no file of its own outside these three
databases, the last good copy and the unreadable copies of §6. Logs are not written to files (ADR-006). Data that
the WorkManager library keeps for the Scheduled Fetch carries no Slussen data beyond the worker's name.

### 2. Mapping the entity model to tables

- One table per entity; table and column names are the entity and attribute names in lower case
  (`BANK_TRANSACTION.import_identifier` becomes `bank_transaction.import_identifier`).
- `Long` with `Primary Key, Sequence` becomes `@PrimaryKey(autoGenerate = true)`. Composite keys
  (`fetch_result`, `eligible_transaction`) become Room `primaryKeys`.
- `Decimal 12,3` is stored as `INTEGER` thousandths of the currency unit (the `Amount` type of ADR-001 §4).
- `Date` is stored as `INTEGER` days since 1970-01-01; `DateTime` as `INTEGER` milliseconds since 1970-01-01 UTC.
  Conversion to the device's local time happens only for display (NFR-018).
- A `String` with `Values:` is stored as `TEXT` holding exactly the value of the entity model (for example
  `Match For Typed Entry`), through one Room `TypeConverter` per value set. An unknown text read back is unreadable
  data (§6).
- `Unique` and `Foreign Key` become Room indices and foreign keys when both tables are in the same database. A
  reference across databases (`bank_consent.bank_id`, `bank_consent.data_provider_id`, `consent_request.bank_id`,
  `consent_request.data_provider_id`) is a plain column; the core checks it.
- Every other constraint of the entity model is a rule of the core (ADR-001), marked there per ADR-004, not a
  database trigger.

Technical storage that is not in the entity model, complete list:

| Table | Database | Columns | Why |
|---|---|---|---|
| `budget_read_state` | `slussen.db` | `budget_id` (key), `change_cursor` (TEXT) | The opaque `ChangeCursor` of ADR-001 §4.2 that lets every later read take "all changes since the previous read" (UC-003 BR-016). |
| `bank_session` | `consent.db` | `bank_id`, `data_provider_id` (key together), `session` (BLOB) | The Data Provider's session for a Bank Consent ("the stored consent and its session", UC-007 BR-007, FR-082). Opaque to the core. |

Whether these two belong in the entity model, and how a stored `bank_account` record is matched to its entry in the
Build Configuration across rebuilds (the entity model has no configuration key for a Bank Account), are open
questions for the Operator (O-P1-03, O-P1-04 in the report to the orchestrator). Until they are answered, Phase 6
creates no table for them beyond this list.

### 3. The Sent Record is committed before anything is sent

The core's port `slussen.core.port.SentRecordStore` has this method for UC-006 step 6:

```kotlin
/** Commits every Sent Record of the plan, locks the Account Mappings and confirms the plan, all or nothing.
 *  Returns only after the commit is durable. Throws SentRecordNotStored when anything fails. */
suspend fun recordBeforeSend(plan: UploadPlanId, sends: List<PlannedSend>)
```

`:persistence` implements it as one `RoomDatabase.withTransaction` block on `slussen.db` that:

1. inserts one `sent_record` per Eligible Transaction with `outcome` Not Yet Known, its `import_identifier`,
   `occurrence`, `budget_account_id`, `booked_date`, `amount`, `sent_as_transfer` and `sent_at`;
2. sets `account_mapping.locked_at` to the same time for every Account Mapping of those transactions whose
   `locked_at` is empty (UC-009 BR-003);
3. sets the `upload_plan.state` to Confirmed.

A unique index on (`budget_account_id`, `import_identifier`) of `sent_record` makes a second record of the same send
fail the whole transaction. When the transaction fails, nothing of it is stored and the core follows UC-006 A7
(sends nothing, discards the plan, ends the pause).

`slussen.core.upload.UploadExecutor` calls `BudgetAppWriter.create` only after `recordBeforeSend` has returned, and
only for the sends it committed. It records each reported outcome in a second transaction per Budget Account
(UC-006 step 9, BR-012). After a crash between the two, the records stay Not Yet Known and the next Comparison
resolves them (UC-003 BR-017); a resend uses the recorded `import_identifier` and `budget_account_id`
(UC-006 BR-007).

Durability settings for `slussen.db` and `consent.db`: `JournalMode.TRUNCATE` (not WAL) and
`PRAGMA synchronous = FULL`, so that a committed transaction is complete in the one database file that a backup
copies, and `recordBeforeSend` returning means the records survive a power loss.

Tests that prove it: in `:core`, a fake `BudgetAppWriter` that fails the test when it is called before the fake
store committed; in `:persistence`, an instrumented test that plants a duplicate `import_identifier` and checks that
no `sent_record`, no `locked_at` and no plan state change remain.

### 4. Backup and device-to-device transfer rules

One copy of the rules, in `:persistence` (`src/main/res/xml/`), referenced from the `:app` manifest with
`android:allowBackup="true"`:

- `data_extraction_rules.xml` (`android:dataExtractionRules`, Android 12 and later): both `<cloud-backup>` and
  `<device-transfer>` hold exactly `<include domain="database" path="slussen.db" />`.
- `backup_rules.xml` (`android:fullBackupContent`, Android 8 to 11): exactly
  `<include domain="database" path="slussen.db" />`.

The rules **include** what must survive instead of excluding what must not, so anything added later is left out
until this ADR says otherwise. Left out by this: `consent.db` and `journal.db` (also in `noBackupFilesDir`, which
Android never backs up), the rollback journal `slussen.db-journal`, the last good copy and unreadable copies of §6,
WorkManager's database, and any cache.

Tests that prove it: a JVM test in `:persistence` parses both XML files and checks that the include list is exactly
`slussen.db` in every section and that no rule names `consent.db` or `journal.db`; a test checks that the
`consent.db` and `journal.db` paths are under `noBackupFilesDir`. A reinstall-and-restore check on a real device
(`adb shell bmgr`) is a manual step of an acceptance phase, not of `gradlew check`.

### 5. Upgrades keep the data

- `exportSchema = true`; every schema version of each database is committed under `persistence/schemas/`.
- Every schema change has a hand-written Room `Migration` and a `MigrationTestHelper` test from every schema version
  that was released (released = present in a merged `VERSION` bump, ADR-005).
- Destructive fallback is never used (Konsist K-09 in ADR-001).
- Installing an older build over a newer one is refused by Android (lower `versionCode`, ADR-005), so no downgrade
  migration exists.

### 6. Unreadable stored data

- Each database is opened through a `SupportSQLiteOpenHelper.Callback` whose `onCorruption` does **not** delete the
  file (SQLite's default does). It renames the file unchanged to `<name>.unreadable-<epochMillis>` in the same
  directory, then starts the database from its last good copy when there is one, otherwise empty with the default
  content (one `operator_settings` record with every default, the default Transfer Descriptions of UC-010 BR-007), and
  reports the part to the core, which shows the notice of UC-008 A1. The parts are named for the Operator:
  `slussen.db` "stored transactions, decisions and settings", `consent.db` "Bank Consents", `journal.db` "Decision
  Journal".
- Last good copy: once per app start, after `PRAGMA quick_check` on `slussen.db` returns `ok`, Slussen writes
  `VACUUM INTO` a file `slussen.lastgood.db` in `noBackupFilesDir`. `consent.db` has no copy (starting with no Bank
  Consent is safe: the Operator grants again) and `journal.db` has none (NFR-006: losing it changes nothing).
- A value that cannot be read back (an unknown `Values:` text, a failed foreign key) is treated as an unreadable
  database, not skipped record by record.

### 7. The Decision Journal

- Written only through `DecisionJournalRecorder` (ADR-001 §3), on its own single-threaded coroutine dispatcher,
  never inside a transaction of `slussen.db`. Any failure is caught, counted and dropped; it never reaches the
  caller (NFR-006, UC-004 BR-005).
- `journal.db` is created with `auto_vacuum = INCREMENTAL`. After each write, when `page_count × page_size` exceeds
  the size limit (`OPERATOR_SETTINGS.journal_size_limit_mb`, 50 MB by default, an Operator Setting), the oldest
  entries are deleted and `PRAGMA incremental_vacuum` returns the space (UC-010 BR-003). The warning uses
  `journal_warning_percent` (80% by default, an Operator Setting).
- Nothing in the core reads entries except the export (Konsist K-07 in ADR-001).

### 8. Housekeeping

- At the start of each Fetch, `provider_request` records whose `sent_at` is more than 24 hours old are deleted: the
  window of the Data Providers' own limits and of C-006, not a Slussen choice.
- `fetch` records beyond `OPERATOR_SETTINGS.fetch_history_size` (60 by default, an Operator Setting) are deleted,
  oldest first, with their `fetch_result` records.

### 9. Access

DAOs, Room entities and `TypeConverter` classes live only in `:persistence` (Konsist K-09). The core sees its ports and
domain types only (ADR-001 §3). Persistence tests (DAO, transaction, migration) are instrumented tests run with
JUnit 5 (ADR-004, ADR-005).

## Consequences

- Exactly once rests on a single SQLite transaction committed before any write to the Budget App, plus the Import
  Identifier the Budget App can refuse. A crash at any point leaves either no Sent Record and nothing sent, or a
  Not Yet Known record that the next Comparison resolves.
- A restore after reinstall brings back exactly `slussen.db`. Bank Consents and the Decision Journal start empty;
  the Bank Accounts of Banks that need a Bank Consent show "missing consent" until the Operator grants again
  (FR-042).
- Starting from the last good copy after corruption can lose Sent Records written after that copy. The next
  Comparison still finds every transaction the Budget App created by its Import Identifier (UC-003 BR-003,
  BR-017), and a resend uses the same Import Identifier, which YNAB refuses as a second copy (C-009). In that one
  case exactly once leans on the Budget App's duplicate protection; the vision allows using it but not depending
  on it, so the Operator should know this case exists.
- Android limits an app's Auto Backup to 25 MB (a limit set by Android). If `slussen.db` ever grows past it, Android
  skips the backup. No requirement covers this; it is reported as an open question (O-P1-05).
- Cross-database references are checked by the core, not by SQLite.
- `TRUNCATE` journal mode writes more slowly than WAL. The data is a household's transactions, so the cost is not
  noticeable.
- Two technical tables exist outside the entity model until the Operator decides (§2).

## Alternatives

- **JSON files with write-to-temporary-and-rename (the Predecessor App).** Rejected: the Operator chose Room
  (O-09); several files cannot be changed in one atomic step, which UC-006 step 6 needs (Sent Records, locks and
  plan state together).
- **One database with backup exclusions.** Not possible: backup rules work on whole files.
- **WAL journal mode.** Rejected for `slussen.db`: a backup that copies the main file without the `-wal` file can
  miss committed transactions.
- **Excluding files instead of including `slussen.db`.** Rejected: the Predecessor App's two exclusion lists drifted
  apart and excluded data NFR-010 requires; an include list fails safe.
- **Encrypting the databases (SQLCipher).** Not chosen: no requirement asks for it, and Android's file-based
  encryption already protects the app's files at rest. Revisit if a requirement is added.
- **Room's Kotlin Multiplatform setup in a JVM module, so that persistence tests run without a device.** Deferred:
  attractive for fast tests, but not needed for the kickoff slice; Phase 6 may propose it as an amendment.
- **Default SQLite corruption handling.** Rejected: it deletes the file, which breaks NFR-005.

## AIUP guidance

- AIUP's stack plugins turn the entity model into Flyway, EF Core or Drizzle migrations for a server database. There
  is no skill for an on-device database, for Room, for mapping the entity model's data types to SQLite, or for
  backup and restore. The mapping rules of §2 and the migration rules of §5 are project-made.
- The entity model skill allows only `Long, String, Integer, Decimal, Boolean, Date, DateTime`; the storage form of
  each in SQLite is decided here, not by AIUP.
- AIUP has no place for technical storage outside the entity model (§2); this ADR lists it and hands the question to
  the Operator rather than adding attributes to `docs/entity_model.md`.
