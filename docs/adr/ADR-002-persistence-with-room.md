# ADR-002: Persistence With Room, Atomic Storage Operations, the Sent Record, Backups and the Recovery Check

## Status

Accepted (2026-10-15, by the Operator at the architecture gate).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the specification changes S-01 to S-10; revised 2026-10-15 after the Phase 2b re-review and S-12 to
S-14; later revisions at the gate are listed under "Review findings addressed".

## Context

What the specification requires of storage:

- **Exactly once, as S-05 states it.** FR-041, NFR-001, UC-006 BR-006: Slussen records each transaction in the Sent
  Record before it is sent and never sends again a Bank Transaction whose Sent Record shows that the Budget App created
  it or already held it. UC-006 BR-007, FR-038: a send with an unknown outcome is sent again automatically, under the
  same Import Identifier and to the same Budget Account, only when the Budget App refuses a reused Import Identifier;
  otherwise it waits for the Operator's Review Decision. UC-006 step 6 records every Eligible Transaction of the plan
  and locks the Account Mappings; A7: when the Sent Record "cannot be stored completely", Slussen sends nothing.
  `SENT_RECORD` constraints: unique `import_identifier` per `budget_account_id`; Not Yet Known and Unknown outcomes are
  resolved by a later read (UC-003 BR-017).
- **What survives a reinstall with restore, and an upgrade.** FR-042 (changed), NFR-010 (changed), NFR-017,
  UC-006 BR-014, UC-009 BR-006, BR-008, UC-010 BR-006: the stored Bank Transactions, the Sent Record, how Slussen
  recognises each Bank Account with its Configuration Key, the Account Mappings with their locks, Funded By and
  Transfer Partner, the Review Decisions and Transfer Decisions, the Operator Settings, **as of the time the device
  backup was made**, and in full on an upgrade.
- **What never leaves the device in a backup or device-to-device transfer.** NFR-009 (changed), UC-007 BR-005,
  `BANK_SESSION`, `OFFERED_BANK_ACCOUNT`: no log, no Bank Consent data, no Bank Session, no Consent Request in
  progress, no Offered Bank Account. NFR-011, UC-011 BR-004: no Decision Journal.
- **The Recovery Check (S-06).** FR-095, NFR-005 (changed), UC-006 BR-017, A13, UC-008 A1, A8, A9, BR-009,
  `RECOVERY_CHECK`: after Slussen starts from its last good copy, from data restored from a device backup or from the
  empty data of a new installation, no Upload Plan is prepared until a Comparison has run and the Operator has
  confirmed.
- **The backup size warning.** NFR-019, C-015, UC-008 A7, BR-010: Android backs up at most 25 MB of an app's data and
  makes no backup at all above it (a limit Android sets); Slussen warns at the backup warning threshold (80% by
  default, an Operator Setting).
- **Unreadable stored data.** NFR-005, NFR-006, UC-008 A1, UC-010 BR-004 (changed by S-13): keep the unreadable
  original unchanged, start from the last good or default content, show a notice naming the part, do not crash; open a
  Recovery Check only when the part holds Decision-Critical Data (glossary); damage to the Decision Journal or other
  data no decision depends on opens none and changes no Upload Plan or Upload.
- **The stored Budget App copy (S-12).** UC-003 BR-020, BR-017, `BUDGET_APP_TRANSACTION` (`transfer_link_reference`,
  `merged_into_reference`, `cleared`, `approved`): each transaction is kept with the other side of its linked Transfer,
  the transaction it was merged into, Cleared and approved, stored together with the read point, so that a linked
  Upload whose answer was lost is settled from stored data after a restart.
- **Marker ownership (S-14).** UC-003 BR-001, BR-008, `BUDGET_APP_TRANSACTION.entered_by` (with the value Slussen
  Unsupported Format).
- **All-or-nothing steps.** UC-002 BR-001 (a Transaction File is taken in as a whole or not at all); UC-003 BR-016
  (every later read takes all changes since the previous read); UC-007 BR-002 (a Consent Request is answered once).
- **New entities of the architecture gate:** `BANK_SESSION` (S-02), `BANK_ACCOUNT.configuration_key` and
  `configured` (S-03, S-10, FR-096), `RECOVERY_CHECK` (S-06), `UNREADABLE_TRANSACTION` (S-07),
  `OFFERED_BANK_ACCOUNT` (S-08), `BUDGET_APP_TRANSACTION.memo` (S-09), `OPERATOR_SETTINGS.file_upload_window_days`
  (S-01) and `backup_warning_percent` (NFR-019).
- **The Decision Journal never affects decisions.** NFR-006, UC-010 BR-004, UC-011 BR-002; size limit by dropping
  the oldest entries (`DECISION_JOURNAL_ENTRY` constraints, UC-010 BR-003).
- **Counters that survive a restart.** `PROVIDER_REQUEST` is kept for at least 24 hours, the window set by the Data
  Provider's own limit, over which the Request Limit is counted (glossary: Request Limit), and survives a restart of the app
  (UC-001 BR-007, BR-012).
- The Operator decided (bootstrap plan O-09) that storage is Room, and (O-P1-03) that the Budget App read cursor stays
  technical, outside the entity model.

Evidence from the Predecessor App (`scratch/construction_inventory.md` §4), used as evidence and not as a template:
nine JSON files in `filesDir` written through `<name>.tmp` and rename, the journal in `noBackupFilesDir`, and two
backup rule files that disagreed: one copy excluded the files holding Review Decisions and Account Mapping locks
(which NFR-010 requires in the backup), the other excluded the consent files instead, and a third list repeated the
first.

The Phase 2b re-review found that the read metadata had no durable storage (B-03), that an interrupted Comparison
left old statuses usable (B-04), that process death during the start sequence could lose the Recovery Check (B-05),
that journal damage blocked Uploads (B-06), and that clearing items went through the Sent Record protocol and
confirmation did not recheck expiry at the commit (B-02, B-07).

The Phase 2 review found that the Sent Record insert rejected the retries it must permit (A-01), that the guarantee
was claimed to hold whatever the Budget App does (A-02), that recovery from the last good copy or a backup continued
uploading on possibly incomplete history (A-03, A-04), that only one atomic storage operation was defined (A-07), and
that the last good copy rested on unproven platform features (A-24).

## Decision

### 1. Three Room databases, split by backup policy

Android Auto Backup and device-to-device transfer include or exclude whole files, so data with different backup
rules lives in different database files.

| Database | File | Backed up and transferred | Tables |
|---|---|---|---|
| Main | `slussen.db` in the app's database directory (`Context.getDatabasePath`) | **Yes**, and nothing else is | every entity of `docs/entity_model.md` except the five below, plus the technical tables of §2 marked `slussen.db` |
| Device | `device.db` in `Context.noBackupFilesDir` | No | `bank_consent`, `bank_session`, `consent_request`, `offered_bank_account`, and the technical table `consent_request_correlation` |
| Journal | `journal.db` in `Context.noBackupFilesDir` | No | `decision_journal_entry` |

`slussen.db` therefore holds: `budget`, `budget_account`, `budget_app_transaction`, `data_provider`, `file_format`,
`bank`, `bank_account`, `account_mapping`, `fetch`, `fetch_result`, `back_off`, `provider_request`,
`account_balance`, `transaction_file`, `bank_transaction`, `unreadable_transaction`, `review_decision`, `transfer`,
`upload_plan`, `eligible_transaction`, `sent_record`, `transfer_counterpart`, `recovery_check`, `operator_settings`,
`transfer_description`, and the technical tables `budget_read_state`, `coordinator_state` and `upload_plan_item`.

`device.db` holds what stays on this installation: Bank Consents with their Bank Sessions and Consent Requests
(UC-007 BR-005) and the Offered Bank Accounts (UC-009 BR-009, O-P2-07). The Phase 1 name `consent.db` is replaced
because the file now holds more than consent data.

Besides the three databases, Slussen keeps exactly these files, all in `noBackupFilesDir`: `installation.id` (§6),
`slussen.lastgood.db` and its temporary file (§8), the unreadable copies of §8, and the start intent
`slussen.start-intent` with its temporary file (§6). No SharedPreferences, no DataStore, no other file. Logs are not written to files
(ADR-006). Data that the WorkManager library keeps for the Scheduled Fetch carries no Slussen data beyond the worker's
name.

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
  data (§8).
- `Unique` and `Foreign Key` become Room indices and foreign keys when both tables are in the same database
  (`bank_session.bank_consent_id` is a real foreign key in `device.db`; `bank_account.configuration_key` a unique
  index in `slussen.db`). A reference across databases (`bank_consent.bank_id`, `bank_consent.data_provider_id`,
  `consent_request.bank_id`, `consent_request.data_provider_id`, `offered_bank_account.data_provider_id`, `bank_id`
  and `proposed_bank_account_id`) is a plain column; the core checks it.
- `BUDGET_APP_TRANSACTION` is stored with every attribute of the entity model, `transfer_link_reference`,
  `merged_into_reference`, `cleared` and `approved` included (S-12), as the read model of ADR-001 §4.2.3 delivers them.
  `entered_by` is decided by the core before O-4 stores the record: from the plug-in's entry kind and the marker
  rules of ADR-008 §1 (Slussen, Slussen Unsupported Format, Other Import Path, Transfer Counterpart, Typed Entry).
- `BANK_SESSION.session_reference` is stored as the revealed text of the `ConsentSession` (ADR-006 §2), written and
  read only inside `:persistence`.
- Every other constraint of the entity model is a rule of the core (ADR-001), marked there per ADR-004, not a
  database trigger.

Technical storage that is not in the entity model, complete list:

| Table | Database | Columns | Why |
|---|---|---|---|
| `budget_read_state` | `slussen.db` | `budget_id` (key), `change_cursor` (TEXT) | The opaque `ChangeCursor` of ADR-001 §4.2.3 that lets every later read take "all changes since the previous read" (UC-003 BR-016); technical by the Operator's answer to O-P1-03. Written only by O-4. |
| `coordinator_state` | `slussen.db` | one row: `mode` (Normal, Plan Open, Uploading), `upload_plan_id`, `state_version` (INTEGER), `scheduled_fetch_deferred` (BOOLEAN), `comparison_pending` (BOOLEAN) | The operation coordinator's mode, the version of everything an Upload Plan depends on, and the marker that a Comparison's read is stored but its conclusions are not (ADR-007 §2, §3a, §6). |
| `upload_plan_item` | `slussen.db` | `upload_plan_id`, `position`, `write_kind` (`Create`, `LinkedTransfer`, `ClearCounterpart`), `bank_transaction_id`, `budget_account_id`, `import_identifier`, `occurrence`, `booked_date`, `amount`, `payee_sent`, `memo_sent`, `cleared`, `transfer_budget_account_id`, `clear_counterpart`, `transfer_counterpart_id` and `counterpart_reference` (clearing items only), `reused_sent_record_id`, `reused_outcome`, plus the plan's captured `state_version`, `change_cursor` per Budget and configuration fingerprint in a header row | The immutable write set of an Upload Plan (ADR-007 §4). Inserted only by O-6; never updated. |
| `consent_request_correlation` | `device.db` | `consent_request_id` (key, foreign key), `state_digest` (TEXT, SHA-256 of the `state`, unique), `provider_handle` (TEXT, the plug-in's opaque handle) | Matches the answer from Strong Customer Authentication to its Consent Request (ADR-001 §4.1). The `state` itself is never stored. |

**Bank Accounts and the Build Configuration (S-03, S-10, FR-096; A-05).** Each Bank Account entry of the Build
Configuration carries its Configuration Key (ADR-003 §2). At every start, before any operation is admitted, O-11
reconciles the stored configuration with the generated one in one transaction:

- a stored `bank_account` is matched by `configuration_key` only, never by name or position; its `name`, `currency`,
  kind, Source, Start Date and Bank follow the entry;
- a stored Bank Account whose key no entry has gets `configured` false and keeps its identification, history,
  Account Mapping and lock (UC-009 A11); one whose key returns gets `configured` true;
- an entry with a key no stored Bank Account has becomes a new `bank_account`; its Account Mapping is not stored when
  its Budget Account already receives through a locked Account Mapping, also one of a Bank Account whose `configured`
  is false (UC-009 A12), and the Bank Account shows the Blocked Reason "configuration error";
- an entry that maps a Bank Account with a locked Account Mapping to another Budget Account changes nothing in
  `account_mapping` (UC-009 A8);
- Budgets are matched by `budget_app_reference`, Data Providers and File Formats by `source_key`, Banks by `name`;
- nothing is ever deleted by reconciliation.

### 3. Atomic storage operations

Every step the specification wants all or nothing is one named method of a core storage port (ADR-001 §3),
implemented by `:persistence` as one `RoomDatabase.withTransaction` block on one database. The core never builds such
a step from two calls. Each operation that changes something an Upload Plan depends on raises
`coordinator_state.state_version` in the same transaction (ADR-007 §6).

| ID | Port method | What it does, all or nothing | Specification |
|---|---|---|---|
| O-1 | `FetchStore.storeBankAccountDelivery` | For one identified Bank Account: new Bank Transactions (with `occurrence_number` allocated and Import Identifiers computed and checked for collision, ADR-008 §4, §5), changed content of known ones, new Unreadable Transactions (counted against stored ones with the same readable fields and reason), the Account Balance, the `fetch_result` | UC-001 steps 7-8, A8, A17, BR-010, BR-016, NFR-020 |
| O-2 | `FetchStore.replaceOfferedBankAccounts` (`device.db`) | Replaces the Offered Bank Accounts of a Data Provider, or of one Bank through it, with the latest delivery | UC-009 BR-009 |
| O-3 | `FileImportStore.importWhole` | The `transaction_file` record Imported and every new Bank Transaction of the file with its occurrence number and Import Identifier; a collision or any failure stores none of them and the core records the file as Rejected in a separate step | UC-002 BR-001, BR-003, A6, NFR-020, ADR-008 §5 |
| O-4 | `BudgetAppCopyStore.applyChangeSet` | The changed `budget_app_transaction` records (upserted by `budget_app_reference`) with every attribute, Transfer link, merge target, Cleared and approved included, the Budget Account balances, `budget.last_read_at` and the next `change_cursor`, only if the stored cursor still equals the one the read started from; and `coordinator_state.comparison_pending` set (ADR-007 §3a) | UC-003 step 1, BR-016, BR-020 |
| O-5 | `ComparisonStore.applyComparison` | Conclusions computed from stored data only (the stored copy of O-4, never a transient read): Transaction Statuses, Sent Records settled as Written when their Import Identifier was found, Transfer Counterparts reconciled from the stored `transfer_link_reference` with their stored `cleared` value (ADR-001 §4.2.3), `recovery_check.compared_at` when every Budget with a mapped Bank Account was read in the Comparison, and `comparison_pending` cleared | UC-003 steps 3-7, BR-017, BR-019, BR-020; UC-005 BR-009 |
| O-6 | `UploadStore.preparePlan` | The `upload_plan`, its `eligible_transaction` and `upload_plan_item` records, and the mode Plan Open | ADR-007 §4 |
| O-7 | `UploadStore.confirmPlan` | §4 below: the Sent Record before every transaction send, with the expiry checked inside the transaction | UC-006 step 6, A5, A7, BR-006 |
| O-8 | `UploadStore.recordOutcomes` | The reported outcomes of one Budget Account's transaction sends | UC-006 steps 8-9, A8, A9, BR-012 |
| O-9 | `UploadStore.finishUpload` | The end of the Upload and the mode Normal | UC-006 step 12 |
| O-10 | `ConsentStore.startRequest`, `consumeAnswer`, `replaceConsent`, `revoke` (`device.db`) | Start: a new Consent Request with its correlation, making earlier ones of the Bank unanswerable. Consume: the most-recent, lifetime and not-answered checks and `answered` set, together. Replace: the Bank Consent and its Bank Session in place of the earlier ones, refused when a newer Consent Request exists. Revoke: delete the Bank Consent, its Bank Session and every Consent Request of the Bank | UC-007 BR-002, BR-003, BR-004, BR-007; NFR-013 |
| O-11 | `ConfigurationStore.reconcile` | §2, Bank Accounts and the Build Configuration | UC-009 A8, A11, A12, BR-010, BR-011; FR-096 |
| O-12 | `RecoveryStore.openCheck`, `confirmChecks` | Open a Recovery Check with its cause; confirm every open check whose `compared_at` is filled, together with its Decision Journal record request | UC-006 BR-017, UC-008 A9, BR-009 |
| O-13 | `ReviewStore.decide`, `TransferStore.decide`, `MappingStore.*`, `SettingsStore.*` | Each decision or change with its effect on statuses; a Review Decision on a Transfer Leg also sets the statuses of the other Transfer Legs of its Transfer (Deferred, or Already In Budget App while an Already Present decision holds) in the same transaction | UC-004 BR-006, BR-009; UC-005 BR-013; UC-009, UC-010 |
| O-14 | `UploadStore.recordClearing` | The result of one clearing (ADR-007 §6 step 8): a new `transfer_counterpart` with the leg's `sent_record_id`, its `budget_app_reference` and `state` Cleared (with `cleared_at`) or Uncleared; or an existing one set to Cleared with `cleared_at`. It never inserts, resets or changes a `sent_record` | UC-006 steps 10, BR-009, BR-010, BR-011, A11 |

A crash at any point leaves each operation either complete or absent. Each has an instrumented test that injects a
failure before the commit and checks that nothing of it is visible (ADR-005 §7, E-05). Because O-4 stores the cursor
with its changes and the Budget App plug-in returns at least the same changes when read again from the same cursor
(ADR-001 §4.2.1 F-3), a crash between a read and its storage loses nothing (A-07). A crash between O-4 and O-5 leaves
`comparison_pending` set, which blocks preparation until the Comparison is recomputed from the stored data and O-5 is
committed (ADR-007 §3a, B-04).

### 4. The Sent Record is committed before anything is sent

O-7, `slussen.core.port.UploadStore.confirmPlan`:

```kotlin
/** Commits the Sent Record of every transaction-send item of the plan, locks the Account Mappings, confirms the plan
 *  and sets the mode Uploading, all or nothing, only while now is before the plan's expires_at. Returns only after
 *  the commit is durable. Throws PlanExpired, or SentRecordNotStored when anything fails or a reused Sent Record no
 *  longer has the outcome captured at preparation. Clearing items are not touched. */
suspend fun confirmPlan(plan: UploadPlanId, now: Instant)
```

`:persistence` implements it as one transaction on `slussen.db` that:

0. checks that `now`, the one value the coordinator took after its network read (ADR-007 §6 step 5), is before
   `upload_plan.expires_at`, and otherwise changes nothing and throws `PlanExpired` (B-07);
1. for each transaction-send item (`write_kind` `Create` or `LinkedTransfer`) that is a new send (no
   `reused_sent_record_id`), inserts one `sent_record` with
   `outcome` Not Yet Known, its `import_identifier`, `occurrence`, `budget_account_id`, `booked_date`, `amount`,
   `sent_as_transfer` and `sent_at`;
2. for each transaction-send item that reuses a Sent Record (a retry, ADR-007 §4), updates that `sent_record`: `outcome` Not Yet Known,
   `upload_plan_id` of this plan, `sent_at`, and the `booked_date` and `amount` sent, **only where its `outcome` still
   equals `reused_outcome`**; its `import_identifier` and `budget_account_id` are never changed;
3. sets `account_mapping.locked_at` to the same time for every Account Mapping of those transactions whose
   `locked_at` is empty (UC-009 BR-003);
4. sets the `upload_plan.state` to Confirmed, only where it is still Awaiting Confirmation, and the coordinator mode to
   Uploading.

`ClearCounterpart` items get no Sent Record and change none; their results are stored by O-14 (B-02).

The unique index on (`budget_account_id`, `import_identifier`) of `sent_record` stays. A retry never inserts, so it
never meets the index (A-01); a violation can only come from a new send whose Import Identifier is already held, which
ADR-008 §5 prevents, and is then UC-006 A7 (nothing sent, plan discarded, mode Normal).

`UploadExecutor` sends only after `confirmPlan` has returned, and only the items of the plan, through the write
permit (ADR-007 §6, §7). After a crash before the outcomes are stored, the records stay Not Yet Known, become Unknown
at the next start (ADR-007 §8), and the next Comparison settles every one the Budget App holds (UC-003 BR-017).

Durability settings for `slussen.db` and `device.db`: `JournalMode.TRUNCATE` (not WAL) and
`PRAGMA synchronous = FULL`, so that a committed transaction is complete in the one database file that a backup
copies, and `confirmPlan` returning means the records survive a power loss.

**What this guarantees, and what it does not (S-05, A-02).** A transaction whose Sent Record is Written or Already
Present is never sent again, whatever happens later on this installation. A send whose answer was lost leaves the
same Sent Record as a request that never arrived; Slussen cannot tell them apart and does not claim to. Such a send is
resent automatically only to a Budget App that declares it refuses a reused Import Identifier, which the live tests
prove for YNAB before Uploads rely on it (ADR-005 §7, E-10); with any other Budget App it waits for the Operator's
Review Decision. Exactly once is not claimed for a Budget App that does neither. And it covers only what Slussen sent
itself: a resemblance to an Import by an Other Import Path or to a Typed Entry is a reason for review, not a
guarantee (UC-003 BR-007, FR-021).

### 5. Backup and device-to-device transfer rules

One copy of the rules, in `:persistence` (`src/main/res/xml/`), referenced from the `:app` manifest with
`android:allowBackup="true"`:

- `data_extraction_rules.xml` (`android:dataExtractionRules`, Android 12 and later): both `<cloud-backup>` and
  `<device-transfer>` hold exactly `<include domain="database" path="slussen.db" />`.
- `backup_rules.xml` (`android:fullBackupContent`, Android 8 to 11): exactly
  `<include domain="database" path="slussen.db" />`.

The rules **include** what must survive instead of excluding what must not, so anything added later is left out
until this ADR says otherwise. Left out by this: `device.db` (Bank Consents, Bank Sessions, Consent Requests,
Offered Bank Accounts) and `journal.db` (also in `noBackupFilesDir`, which Android never backs up), the rollback
journal `slussen.db-journal`, every file of §1 in `noBackupFilesDir`, WorkManager's database, and any cache.

**Backup size warning (NFR-019, C-015).** `BackupSizeMonitor` reports the size of `slussen.db`, the only file that is
backed up, and the limit Android sets for Auto Backup (25 MB, C-015; a constant in `:persistence` whose KDoc names the
Android documentation as its source). At every opening, when the size reaches `backup_warning_percent` of that limit
(80% by default, an Operator Setting), the core shows UC-008 A7. Data that is never backed up does not count.

**What a backup can and cannot give (A-04).** Android decides when it makes a backup; Slussen cannot force a fresh
one, cannot see whether the last one succeeded, and gets none at all above the limit. NFR-010 (changed) promises the
data "that existed when Android made the backup", not the data of the last moment. Everything after that point is
covered by the Recovery Check (§6), not by the backup.

Tests that prove it: a JVM test in `:persistence` parses both XML files and checks that the include list is exactly
`slussen.db` in every section and that no rule names `device.db` or `journal.db`; a test checks that the `device.db`
and `journal.db` paths are under `noBackupFilesDir`; the restore evidence of ADR-005 §7 (E-07) checks it on a device.

### 6. The start sequence and the Recovery Check (S-06; A-03, A-04)

At every start, before Room opens `slussen.db` and before any operation is admitted, `:persistence` runs the start
sequence and reports its result through `StartState` (ADR-001 §3):

| `installation.id` in `noBackupFilesDir` | `slussen.db` | Start | Recovery Check opened with cause |
|---|---|---|---|
| present | present and readable | normal | none |
| absent | absent or empty | new installation | New Installation |
| absent | present | data restored from a device backup or transferred from another device (Android restores `slussen.db` but never `noBackupFilesDir`) | Restored Backup |
| present | absent | data cleared while the marker survived | New Installation |
| any | unreadable, started from the last good copy or empty (§8) | last good copy | Last Good Copy |

**Only Decision-Critical Data opens a Recovery Check (S-13, B-06).** `slussen.db` holds all Decision-Critical Data
(the Bank Transactions with their statuses, the Sent Record, the Bank Account identifications, the Account Mappings
with their locks, the Review and Transfer Decisions, the Operator Settings), so damage to it, wherever in the file,
opens one. Damage to `device.db` (Bank Consents, Bank Sessions, Consent Requests, Offered Bank Accounts) or to
`journal.db` (the Decision Journal) shows the notice of UC-008 A1 only, opens no Recovery Check and changes no Upload
Plan or Upload (NFR-005, NFR-006, UC-010 BR-004).

**The start intent survives process death (B-05).** A database transaction cannot cover file renames and a file
outside the database, so the start sequence makes the obligation to open a Recovery Check durable before it changes
anything:

1. **Resume.** When `slussen.start-intent` exists, its cause is used and steps 2 and 3 are skipped; the sequence
   continues at step 4.
2. **Classify** by the table above.
3. **Record the intent.** For every start that opens a Recovery Check, the sequence writes its cause to
   `slussen.start-intent` (written to a temporary file, flushed, renamed, and the directory flushed) **before** it
   writes `installation.id`, before it renames an unreadable `slussen.db` or copies the last good copy (§8), and before
   Room opens the database. A normal start writes no intent.
4. **Do the work of the cause.** For Last Good Copy: §8 step 3, which is safe to repeat (a `slussen.db` that still
   fails the checks of §8 step 1 is the unreadable original and is set aside again; one that passes is the
   replacement). Then `installation.id` is written when it is absent.
5. **Open the check.** One transaction of O-12 opens the Recovery Check with the cause and resets `coordinator_state`
   as ADR-007 §8 says.
6. **Clear the intent.** `slussen.start-intent` is deleted and the directory flushed, only after step 5 committed.

A process that dies between any two steps restarts at step 1 with the intent present and finishes the same work; it
can never look like a normal start. Dying between steps 5 and 6 opens a second Recovery Check at the next start, which
the entity model allows ("also when an earlier one is still open") and which the same Comparison and confirmation close.
Each boundary is tested by an interruption (ADR-005 §7, E-12).

While any Recovery Check is open, the coordinator prepares no Upload Plan (ADR-007 §3); UC-008 A8 shows why. A
Comparison that read every Budget with a mapped Bank Account fills `compared_at` (O-5, UC-003 BR-019), and only then
can the Operator confirm (O-12, UC-008 A9). This is how the data a backup or a last good copy lacks (Sent Records,
locks, decisions written after it was made) is dealt with: the Comparison finds every transaction Slussen sent by its
Import Identifier (ADR-008), which no build can change, and the Operator checks the Budget App before any Upload.
Uploads never go ahead on a Sent Record that may be incomplete; a warning alone is not used.

### 7. Upgrades keep the data

- `exportSchema = true`; every schema version of each database is committed under `persistence/schemas/`.
- Every schema change has a hand-written Room `Migration` and a `MigrationTestHelper` test from every schema version
  that was released (released = present in a merged `VERSION` bump, ADR-005).
- A migration never rewrites `import_identifier`, `configuration_key` or `occurrence` values (ADR-008 §4).
- Destructive fallback is never used (Konsist K-09 in ADR-001).
- Installing an older build over a newer one is refused by Android (lower `versionCode`, ADR-005), so no downgrade
  migration exists. An in-place upgrade needs the same signing key (ADR-005 §6).

### 8. Unreadable stored data and the last good copy (A-24)

Room on the platform's SQLite is kept; no bundled SQLite driver is added. Android 8.0 (API 26, the `minSdk`) ships an
SQLite older than 3.27, which has no `VACUUM INTO`, so the last good copy is a plain file copy made while the database
is closed:

1. **Check.** Before Room opens it, the start sequence opens `slussen.db` with the platform's `SQLiteDatabase` (which
   rolls back a leftover `slussen.db-journal`), runs `PRAGMA quick_check` and `PRAGMA foreign_key_check`, and a
   logical check: every `Values:` column holds only its allowed texts (queries generated from the entity model's value
   sets). It then closes the database.
2. **Copy.** When all three pass, it copies `slussen.db` to `slussen.lastgood.db.tmp` in `noBackupFilesDir`, flushes
   it to the device, and renames it over `slussen.lastgood.db` (atomic within one file system). A failed copy leaves the
   previous last good copy in place; a leftover temporary file is deleted at the next start.
3. **Unreadable.** When a check fails, or SQLite reports corruption, the original `slussen.db` and its
   `slussen.db-journal`, if present, are renamed together, unchanged, to `slussen.db.unreadable-<epochMillis>-<random>`
   and `slussen.db-journal.unreadable-<epochMillis>-<random>` in the database directory, with 128 random bits as
   ADR-008 §7 says, and the rename never overwrites an existing file; `slussen.lastgood.db` is copied to
   `slussen.db`, or, without one, an empty database with the default content (one `operator_settings` record with
   every default, the default Transfer Descriptions of UC-010 BR-007) is created; the part is reported to the core,
   which shows the notice of UC-008 A1; a Recovery Check is opened (§6).
4. **Unreadable while running.** A `TypeConverter` that meets an unknown text, or a failed foreign key, throws
   `UnreadableStoredData`. The app does not continue on that data: for `slussen.db` it writes the start intent with
   cause Last Good Copy (§6, step 3 of the start sequence) and restarts its process; the next start sequence resumes
   from that intent and treats `slussen.db` as unreadable (step 3 here). For `device.db` or `journal.db` it marks that
   file unreadable in the same way, without a start intent, and step 5 applies.
   Room's `onCorruption` callback does the same instead of SQLite's default, which deletes the file.
5. `device.db` and `journal.db` get no last good copy: starting with no Bank Consent is safe (the Operator grants
   again), and losing the journal changes nothing (NFR-006). Their unreadable originals are kept the same way, they
   start empty, the notice of UC-008 A1 names the part, and no Recovery Check is opened (S-13).

The parts are named for the Operator: `slussen.db` "stored transactions, decisions and settings", `device.db` "Bank
Consents and Offered Bank Accounts", `journal.db` "Decision Journal". The copy, the replacement, the logical check,
the restart path and the preservation of originals are proven on a device with `minSdk` (ADR-005 §7, E-01 to E-04)
before the first slice that uploads.

### 9. The Decision Journal

- Written only through `DecisionJournalRecorder` (ADR-001 §3), on its own single-threaded coroutine dispatcher,
  never inside a transaction of `slussen.db`. Any failure is caught, counted and dropped; it never reaches the
  caller (NFR-006, UC-004 BR-005).
- `journal.db` is created with `auto_vacuum = INCREMENTAL`. After each write, when `page_count × page_size` exceeds
  the size limit (`OPERATOR_SETTINGS.journal_size_limit_mb`, 50 MB by default, an Operator Setting), the oldest
  entries are deleted and `PRAGMA incremental_vacuum` returns the space (UC-010 BR-003). The warning uses
  `journal_warning_percent` (80% by default, an Operator Setting).
- Nothing in the core reads entries except the export (Konsist K-07 in ADR-001).
- Offered Bank Accounts, Bank Sessions and `state` values never reach it (`OFFERED_BANK_ACCOUNT`, `BANK_SESSION`
  constraints; NFR-012).

### 10. Housekeeping

- At the start of each Fetch, `provider_request` records whose `sent_at` is more than 24 hours old are deleted: the
  window of the Data Providers' own limits and of C-006, not a Slussen choice.
- `fetch` records beyond `OPERATOR_SETTINGS.fetch_history_size` (60 by default, an Operator Setting) are deleted,
  oldest first, with their `fetch_result` records.
- `consent_request` records of a Bank are deleted with its Bank Consent on revocation (O-10); an expired, unanswered
  Consent Request is kept until the next one of the Bank replaces it.
- Unreadable Transactions are never deleted by housekeeping (`UNREADABLE_TRANSACTION` constraints).

### 11. Access

DAOs, Room entities and `TypeConverter` classes live only in `:persistence` (Konsist K-09). The core sees its ports and
domain types only (ADR-001 §3). Persistence tests (DAO, transaction, migration, start sequence) are instrumented tests
run with JUnit 5 (ADR-004, ADR-005).

## Consequences

- Exactly once rests on one SQLite transaction committed before any write to the Budget App, on retries that reuse
  the Sent Record and its Import Identifier, and, for a lost answer, on the Budget App's declared refusal of a reused
  Import Identifier or the Operator's decision (S-05). A crash at any point leaves either no Sent Record and nothing
  sent, or a Not Yet Known or Unknown record that the next Comparison settles when the Budget App holds it.
- A restore after reinstall brings back exactly `slussen.db` as of Android's last backup. Bank Consents, Bank
  Sessions, Offered Bank Accounts and the Decision Journal start empty; the Bank Accounts of Banks that need a Bank
  Consent show "missing consent" until the Operator grants again (FR-042), and UC-009 A10 applies until the next Fetch.
- After a restore, a new installation or a last good copy, Uploads wait for the Recovery Check. The Operator can see
  the Transaction Statuses at once; only Uploads wait.
- Cross-database references are checked by the core, not by SQLite.
- `TRUNCATE` journal mode writes more slowly than WAL, and the last good copy costs one file copy per start. The data
  is a household's transactions, so neither is noticeable; the backup size warning watches the file's growth.
- An Offered Bank Account cannot be confirmed after a reinstall until the next Fetch or grant (UC-009 A10).
- Damage to the Decision Journal or to `device.db` never blocks an Upload; damage to `slussen.db` always does until
  the Recovery Check is confirmed (S-13).
- The stored copy of the Budget App grows by four attributes per transaction (S-12); in exchange, a linked Upload
  whose answer was lost is settled from stored data alone after any restart.
- A crash during the start sequence or between the two halves of a Comparison costs at most a repeated step or a
  second open Recovery Check, never a skipped one.

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
  attractive for fast tests, but not needed for the kickoff slice; a later phase may propose it as an amendment.
- **A bundled SQLite driver (`androidx.sqlite:sqlite-bundled`) for `VACUUM INTO` on every API level.** Not chosen: it
  adds native code to the app (ADR-005 §5) and replaces the open-helper callbacks this ADR relies on; the closed-file
  copy of §8 needs no newer SQLite. Revisit if the device evidence (ADR-005 §7) shows the copy unreliable.
- **Default SQLite corruption handling.** Rejected: it deletes the file, which breaks NFR-005.
- **Inserting a new Sent Record for each retry.** Rejected (A-01): it breaks the unique Import Identifier per Budget
  Account.
- **Warning the Operator after a restore and continuing to upload.** Rejected by S-06: a warning alone does not
  protect a Sent Record that may be incomplete.
- **Opening a Recovery Check for damage to any database.** Rejected by S-13 (B-06): it let Decision Journal damage
  block Uploads, against NFR-006.
- **Making the Comparison's read and conclusions one transaction.** Not chosen (B-04): the `comparison_pending` marker
  gives the same guarantee and keeps the network read outside a transaction (ADR-007 §3a).
- **Writing `installation.id` first and opening the Recovery Check after.** Rejected (B-05): a crash between the two
  would make the next start look normal; the start intent is written first instead.
- **Detecting a restore from Android's backup agent callbacks.** Not chosen: Auto Backup restores files without a
  callback the app can rely on; the missing `installation.id` is a fact the start sequence can read.

## AIUP guidance

- AIUP's stack plugins turn the entity model into Flyway, EF Core or Drizzle migrations for a server database. There
  is no skill for an on-device database, for Room, for mapping the entity model's data types to SQLite, or for
  backup and restore. The mapping rules of §2, the operations of §3 and the migration rules of §7 are project-made.
- The entity model skill allows only `Long, String, Integer, Decimal, Boolean, Date, DateTime`; the storage form of
  each in SQLite is decided here, not by AIUP.
- AIUP has no place for technical storage outside the entity model (§2); this ADR lists it, as the Operator decided
  for the read cursor (O-P1-03).

## Review findings addressed

- A-01: §4 (retries update the reused Sent Record under a compare of the captured outcome; only new sends insert);
  ADR-007 §4.
- A-02 (with S-05): §4 "What this guarantees", Consequences.
- A-03 (with S-06): §6 (the Recovery Check after a last good copy blocks Uploads), §8.
- A-04 (with S-04, S-06, NFR-019): §5 (what a backup can give, the size warning), §6 (restore and new installation
  detected and gated).
- A-05 (with S-03, S-10): §2 (reconciliation by Configuration Key, removal, return, new keys, locks).
- A-07: §3 (named atomic operations O-1 to O-14; the cursor stored with its change set).
- A-09: §3 O-5 (Transfer Counterparts reconciled from links).
- A-11 (with S-07): §1 and §2 (`unreadable_transaction`), §3 O-1.
- A-14: §1 and §2 (`consent_request_correlation` in `device.db`), §3 O-10.
- A-15 (with S-08, O-P2-07): §1 (`offered_bank_account` in `device.db`, never backed up), §3 O-2.
- A-23 (with S-09): §2 (`budget_app_transaction.memo` mapped like every attribute), §3 O-4.
- A-24: §8 (no `VACUUM INTO`; closed-file copy, atomic replacement, logical check, restart path, originals with their
  journal); the device proof is ADR-005 §7.
- A-27 in part: §3 and §8 name the evidence; ADR-005 §7 makes it a gate.
- B-02: §3 O-7, O-8 (transaction sends only) and O-14 (clearing results, never a Sent Record), §4.
- B-03 (with S-12): §2 (`budget_app_transaction` stored with Transfer link, merge target, Cleared and approved), §3 O-4
  (stored atomically with the cursor) and O-5 (conclusions from stored data only).
- B-04: §2 (`coordinator_state.comparison_pending`), §3 O-4 sets it and O-5 clears it; ADR-007 §3a.
- B-05: §1 (`slussen.start-intent`), §6 (the start intent written before any change, cleared after the Recovery Check
  and coordinator reset are committed; resume rule), §8 step 4; ADR-005 §7 E-12.
- B-06 (with S-13): §6 (only Decision-Critical Data, held by `slussen.db`, opens a Recovery Check), §8 steps 4-5.
- B-07: §3 O-7 and §4 step 0 (expiry checked inside the confirming transaction with the coordinator's one `now`).
- S-14: §2 (`entered_by` decided by the core with the marker rules, Slussen Unsupported Format included).
- S-17: §3 O-13 (a decision on a Transfer Leg sets the other Transfer Legs' statuses atomically), §4 (the guarantee
  covers only what Slussen sent itself).
