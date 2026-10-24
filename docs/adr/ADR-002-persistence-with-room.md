# ADR-002: Persistence With Room, Atomic Storage Operations, the Sent Record, Backups and First Upload Matching

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); revised for S-23/S-24 and re-checked externally (2026-10-18, `docs/reviews/mockup_gate_recheck.md`, no High finding).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the specification changes S-01 to S-10; revised 2026-10-15 after the Phase 2b re-review and S-12 to
S-14; revised for S-23/S-24 on 2026-10-16, pending an external check; revised 2026-10-16 for O-P2-34 (1); later
revisions at the gate are listed under "Review findings addressed".

## Context

What the specification requires of storage:

- **Exactly once, as S-05 states it.** FR-041, NFR-001, UC-006 BR-006: Slussen records each transaction in the Sent
  Record before it is sent and never sends again a Bank Transaction whose Sent Record shows that the Budget App created
  it or already held it. UC-006 BR-007, FR-038: a send with an unknown outcome is sent again automatically, under the
  same Import Identifier and to the same Budget Account, only when the Budget App refuses a reused Import Identifier;
  otherwise it waits for the Operator's Review Decision. UC-006 step 6 records every Eligible Transaction of the plan
  and records the first send through each Account Mapping (S-23); A7: when the Sent Record "cannot be stored completely", Slussen sends nothing.
  `SENT_RECORD` constraints: unique `import_identifier` per `budget_account_id`; Not Yet Known and Unknown outcomes are
  resolved by a later read (UC-003 BR-017).
- **What survives a reinstall with restore, and an upgrade.** FR-042 (changed), NFR-010 (changed), NFR-017,
  UC-006 BR-014, UC-009 BR-006, BR-008, UC-010 BR-006: the stored Bank Transactions, the Sent Record, how Slussen
  recognises each Bank Account with its generated identifier, name and Start Date, the chosen Budgets, the Account
  Mappings with the time of their first send, Funded By and Transfer Partner, the Review Decisions and Transfer Decisions, the Operator Settings, **as of the time the device
  backup was made**, and in full on an upgrade; since S-38 also the Left-Out Bank Accounts and the Manually Kept
  Accounts (FR-105, FR-106, UC-013 BR-011, BR-014). Since S-39, NFR-017 covers every newer build installed over the
  installed one, a release or a debug build signed with the same key, and every database: an upgrade loses 0 stored
  data, the Decision Journal and what `device.db` holds included.
- **What never leaves the device in a backup or device-to-device transfer.** NFR-009 (changed), UC-007 BR-005,
  `BANK_SESSION`, `OFFERED_BANK_ACCOUNT`, `SERVICE_CREDENTIAL`: no log, no service credential, no Bank Consent data,
  no Bank Session, no Consent Request in progress, no Offered Bank Account (NFR-021, UC-014 BR-003). NFR-011, UC-011 BR-004: no Decision Journal.
- **First Upload Matching (S-06, named by S-41).** FR-095, NFR-005 (changed), UC-006 BR-017, A13, UC-008 A1, A8, A9, BR-009,
  `FIRST_UPLOAD_MATCHING`: after Slussen starts from its last good copy, from data restored from a device backup or from the
  empty data of a new installation, no Upload Plan is prepared until a Comparison has run and the Operator has
  confirmed.
- **The backup size warning.** NFR-019, C-015, UC-008 A7, BR-010: Android backs up at most 25 MB of an app's data and
  makes no backup at all above it (a limit Android sets); Slussen warns at the backup warning threshold (80% by
  default, an Operator Setting).
- **Unreadable stored data.** NFR-005, NFR-006, UC-008 A1, UC-010 BR-004 (changed by S-13): keep the unreadable
  original unchanged, start from the last good or default content, show a notice naming the part, do not crash; open a
  First Upload Matching only when the part holds Decision-Critical Data (glossary); damage to the Decision Journal or other
  data no decision depends on opens none and changes no Upload Plan or Upload.
- **The stored Budget App copy (S-12).** UC-003 BR-020, BR-017, `BUDGET_APP_TRANSACTION` (`transfer_link_reference`,
  `merged_into_reference`, `cleared`, `approved`): each transaction is kept with the other side of its linked Transfer,
  the transaction it was merged into, Cleared and approved, stored together with the read point, so that a linked
  Upload whose answer was lost is settled from stored data after a restart.
- **Marker ownership (S-14).** UC-003 BR-001, BR-008, `BUDGET_APP_TRANSACTION.entered_by` (with the value Slussen
  Unsupported Format).
- **All-or-nothing steps.** UC-002 BR-001 (a Transaction File is taken in as a whole or not at all); UC-003 BR-016
  (every later read takes all changes since the previous read); UC-007 BR-002 (a Consent Request is answered once).
- **Set in the app, credentials on the device (S-23, S-24).** UC-013, UC-009, UC-014, FR-097 to FR-102, NFR-021:
  `BUDGET.served`, `BANK_ACCOUNT.generated_identifier` (replacing the Configuration Key and `configured` of S-03 and
  S-10), `ACCOUNT_MAPPING.first_sent_at` (replacing `locked_at`), `OFFERED_BANK_ACCOUNT.balance`, `SERVICE_CREDENTIAL`,
  `DATA_PROVIDER.request_limit` declared by the part, `FETCH.outcome` Credentials Refused.
- **New entities of the architecture gate:** `BANK_SESSION` (S-02), `FIRST_UPLOAD_MATCHING` (S-06), `UNREADABLE_TRANSACTION` (S-07),
  `OFFERED_BANK_ACCOUNT` (S-08), `BUDGET_APP_TRANSACTION.memo` (S-09), `OPERATOR_SETTINGS.file_upload_window_days`
  (S-01) and `backup_warning_percent` (NFR-019); `DATA_PROVIDER.fetch_cooldown_minutes` (S-22), the Fetch Cooldown of
  each Data Provider, an Operator Setting kept per Data Provider instead of in `OPERATOR_SETTINGS`.
- **The Decision Journal never affects decisions.** NFR-006, UC-010 BR-004, UC-011 BR-002; size limit by dropping
  the oldest entries (`DECISION_JOURNAL_ENTRY` constraints, UC-010 BR-003).
- **Counters that survive a restart.** `PROVIDER_REQUEST` is kept for at least 24 hours, the rolling window over which
  the Request Limit is counted, the window of a Data Provider's own limit and of PSD2 (C-006) (glossary: Request Limit), and survives a restart of the app
  (UC-001 BR-007, BR-012).
- The Operator decided (bootstrap plan O-09) that storage is Room, and (O-P1-03) that the Budget App read cursor stays
  technical, outside the entity model.

Evidence from the Predecessor App (`scratch/construction_inventory.md` §4), used as evidence and not as a template:
nine JSON files in `filesDir` written through `<name>.tmp` and rename, the journal in `noBackupFilesDir`, and two
backup rule files that disagreed: one copy excluded the files holding Review Decisions and Account Mapping locks
(which NFR-010 requires in the backup), the other excluded the consent files instead, and a third list repeated the
first.

The Phase 2b re-review found that the read metadata had no durable storage (B-03), that an interrupted Comparison
left old statuses usable (B-04), that process death during the start sequence could lose the First Upload Matching (B-05),
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
| Main | `slussen.db` in the app's database directory (`Context.getDatabasePath`) | **Yes**, and nothing else is | every entity of `docs/entity_model.md` except the six below, plus the technical tables of §2 marked `slussen.db` |
| Device | `device.db` in `Context.noBackupFilesDir` | No | `service_credential` (§2a), `bank_consent`, `bank_session`, `consent_request`, `offered_bank_account`, and the technical table `consent_request_correlation` |
| Journal | `journal.db` in `Context.noBackupFilesDir` | No | `decision_journal_entry` |

`slussen.db` therefore holds: `budget`, `budget_account`, `budget_app_transaction`, `data_provider`, `file_format`,
`bank`, `bank_account`, `account_mapping`, `left_out_bank_account`, `fetch`, `fetch_result`, `back_off`, `provider_request`,
`account_balance`, `transaction_file`, `bank_transaction`, `unreadable_transaction`, `review_decision`, `transfer`,
`upload_plan`, `eligible_transaction`, `sent_record`, `transfer_counterpart`, `recovery_check`, `operator_settings`,
`transfer_description`, and the technical tables `budget_read_state`, `coordinator_state` and `upload_plan_item`.

`device.db` holds what stays on this installation: the encrypted service credentials (UC-014 BR-003), Bank Consents
with their Bank Sessions and Consent Requests (UC-007 BR-005) and the Offered Bank Accounts (UC-009 BR-009, O-P2-07).
The key that encrypts the service credentials lives in the Android Keystore, not in a file (§2a). The Phase 1 name `consent.db` is replaced
because the file now holds more than consent data.

Besides the three databases, Slussen keeps exactly these files, all in `noBackupFilesDir`: `installation.id` (§6),
`slussen.lastgood.db` and its temporary file (§8), the unreadable copies of §8, and the start intent
`slussen.start-intent` with its temporary file (§6). No SharedPreferences, no DataStore, no other file. Logs are not written to files
(ADR-006). Data that the WorkManager library keeps for the Scheduled Fetch carries no Slussen data beyond the worker's
name.

### 2. Mapping the entity model to tables

- One table per entity; table and column names are the entity and attribute names in lower case
  (`BANK_TRANSACTION.import_identifier` becomes `bank_transaction.import_identifier`).
- One exception (S-41): `FIRST_UPLOAD_MATCHING` is stored in the table `recovery_check`, the name of committed schema
  version 1; renaming the table would need a schema version for no gain (§7). Its columns keep the attribute names
  (`cause`, `opened_at`, `compared_at`, `confirmed_at`), and no new schema version is added for the rename.
- `Long` with `Primary Key, Sequence` becomes `@PrimaryKey(autoGenerate = true)`. Composite keys
  (`fetch_result`, `eligible_transaction`) become Room `primaryKeys`.
- `Decimal 12,3` is stored as `INTEGER` thousandths of the currency unit (the `Amount` type of ADR-001 §4).
- `Date` is stored as `INTEGER` days since 1970-01-01; `DateTime` as `INTEGER` milliseconds since 1970-01-01 UTC.
  Conversion to the device's local time happens only for display (NFR-018).
- A `String` with `Values:` is stored as `TEXT` holding exactly the value of the entity model (for example
  `Match For Typed Entry`), through one Room `TypeConverter` per value set. An unknown text read back is unreadable
  data (§8).
- `Unique` and `Foreign Key` become Room indices and foreign keys when both tables are in the same database
  (`bank_session.bank_consent_id` is a real foreign key in `device.db`; `bank_account.generated_identifier` a unique
  index in `slussen.db`). A reference across databases (`service_credential.data_provider_id`,
  `bank_consent.bank_id`, `bank_consent.data_provider_id`,
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
| `upload_plan_item` | `slussen.db` | `upload_plan_id`, `position`, `write_kind` (`Create`, `LinkedTransfer`, `ClearCounterpart`), `bank_transaction_id`, `budget_account_id`, `import_identifier`, `occurrence`, `booked_date`, `amount`, `payee_sent`, `memo_sent`, `cleared`, `transfer_budget_account_id`, `clear_counterpart`, `transfer_counterpart_id` and `counterpart_reference` (clearing items only), `reused_sent_record_id`, `reused_outcome`, plus the plan's captured `state_version` and `change_cursor` per Budget in a header row | The immutable write set of an Upload Plan (ADR-007 §4). Inserted only by O-6; never updated. |
| `consent_request_correlation` | `device.db` | `consent_request_id` (key, foreign key), `state_digest` (TEXT, SHA-256 of the `state`, unique), `provider_handle` (TEXT, the plug-in's opaque handle) | Matches the answer from Strong Customer Authentication to its Consent Request (ADR-001 §4.1). The `state` itself is never stored. |

**Budgets, Bank Accounts and Account Mappings are set in the app (S-23; UC-013, UC-009).** There is no Build
Configuration of them and no reconciliation by key:

- `budget.served` is true for each Budget the Operator chose (UC-013 BR-001); only served Budgets are read, compared and
  shown. A Budget is stored when the Budget App lists it and the Operator chooses it; it stops being served only when
  no Bank Account is mapped into it (UC-013 A2).
- `bank_account.generated_identifier` is generated by the core when the Bank Account is added (UC-013 BR-003): a random
  version 4 UUID as RFC 9562 defines it, from `java.security.SecureRandom`, 122 random bits (ADR-008 §7, NFR-020), in
  its 36-character text form; unique index; never changed or migrated. History, Account Mapping, Sent Records and
  decisions refer to the Bank Account's row, which this identifier names for good; `name` is editable and never
  identifies (UC-009 BR-010, BR-011).
- `bank_account.identified_at` is set by O-15 when the Bank Account is added and by O-13 when the Operator confirms or
  changes how its Source identifies it (UC-009 steps 4-6, A1, A6, A7). A Fetch within the Fetch Cooldown still reads
  each selected Bank Account whose `identified_at` is later than the start of the Data Provider's last `fetch` with
  `outcome` Fetched, and is recorded as Served From Fetch Cooldown, so it does not restart it (UC-001 A2, BR-006).
- `account_mapping.first_sent_at` (the Phase 1-2 `locked_at`) is set at the first send through the mapping (§4),
  whatever the outcome; it records when sending began and decides no confirmation. A move or removal of a mapping, and
  identifying its Bank Account again, needs the Operator's confirmation in O-13 only while a `sent_record` of the Bank
  Account's transactions with the mapping's `budget_account_id` has an `outcome` other than Rejected, also one sent
  through an earlier mapping to that Budget Account, whatever `first_sent_at` holds; when every such record is
  Rejected, nothing was written and O-13 acts without one (UC-009 A7, A8, A11, BR-003, BR-007); after a move, or on a
  new mapping, `first_sent_at` is empty until the first send through it. The
  Sent Records keep their `budget_account_id` (UC-003 BR-021), except that a later retry of a Rejected send moves its
  record to the Budget Account of the current Account Mapping (§4 step 2; UC-006 BR-020, O-P2-34 (1)).
- `offered_bank_account.balance` holds the balance the Data Provider reported with the delivery (UC-013 step 5).
- `left_out_bank_account` (S-38) is a table of `slussen.db`, so it is backed up, restored and kept on an upgrade with
  the Bank Accounts: it is the Operator's decision, while the Offered Bank Accounts it filters live in `device.db`,
  are replaced at every delivery and are not restored (UC-013 BR-011). `data_provider_id` is a real foreign key to
  `data_provider`; a unique index on (`data_provider_id`, `provider_reference`). O-15 inserts one row when the Operator
  switches an Offered Bank Account's import off (UC-013 steps 6-7, BR-012; S-40) and deletes it when the import is
  switched on again (UC-013 A10, UC-009 A15 leads there) or the Bank Account is chosen for a File Format's balance
  (BR-006). O-2 and O-11 never touch it. The core matches the Offered Bank Accounts of `device.db` against it by
  (`data_provider_id`, `provider_reference`), across the two databases: a match is not offered for pairing, gets no
  `proposed_bank_account_id`, and is never selected for a Fetch (ADR-001 §4.1 `FetchRequest`). O-15 never adds a
  Bank Account whose reference is left out; choosing a Left-Out one for a File Format's balance deletes its row in the
  same transaction (UC-013 BR-006).
- `budget_account.manually_kept` (S-38) is set and cleared by O-15 (UC-013 A9; UC-009 A16 leads there), refused while
  the Budget Account receives through an Account Mapping; a read of the Budget Accounts from the Budget App (UC-013
  step 4, O-4) keeps it unchanged. A Manually Kept Account is never offered for pairing (UC-013 BR-014).
- `bank_account.provider_bank_id` (S-33) is set by O-15 when the Bank Account is added and by O-13 when it is
  identified, from the `offered_bank_account.bank_id` it is identified with, and is never changed by a change of
  `bank_account.bank_id` (UC-009 A14). It is kept in `slussen.db`, a real foreign key to `bank`, because the Offered
  Bank Accounts in `device.db` are replaced at every delivery and not restored after a reinstall, while the Bank
  Accounts a Bank Consent will cover must still be known when the Operator grants it again (UC-007 BR-005, BR-009).
- `data_provider.request_limit` with `request_limit_published` (S-31): when the Data Provider's part declares a
  published limit (`RequestLimitDeclaration.Published`, ADR-001 §4.1; FR-017), it is not an Operator Setting and O-11
  sets it at every start from the part; when the part declares none published (`NotPublished`), it is an Operator
  Setting kept per Data Provider beside `fetch_cooldown_minutes`, changed through O-13 within the range the part
  declares (UC-010 BR-002, A11).
- `fetch.outcome` has the value Credentials Refused (UC-001 A7); the Phase 1-2 value Configuration Error is gone.
- A `funded_by_budget_account_id` or `transfer_partner_budget_account_id` whose Budget Account no longer receives
  through any Account Mapping of a Bank Account that is not a Retired Account is kept unchanged; the core treats it as
  no longer applying, uses it for no Transfer and shows it as such, until the Operator changes or clears it or such a
  Bank Account is mapped to it again (UC-005 BR-014). It applies again without any write when that happens.

**O-11 registers the plug-ins.** At every start, before any operation is admitted, O-11 stores one `data_provider` row
per Data Provider of the build and one `file_format` row per File Pre-processor, matched by `source_key`, in one
transaction: a new `data_provider` row gets `fetch_cooldown_minutes` from the default its plug-in declares
(`DataProviderProfile.defaultFetchCooldownMinutes`), an existing row keeps the value the Operator set, which is backed
up and restored with the other Operator Settings (S-22, UC-001 BR-006). For the Request Limit, a `Published`
declaration always sets `request_limit` from the part with `request_limit_published` true; a `NotPublished` one gives
a new row, or a row whose limit was published until now, the part's default with `request_limit_published` false, and
an existing row keeps the Operator's value, backed up and restored like the Fetch Cooldown, while it lies in the
declared range, and otherwise takes the default (S-31, UC-001 BR-007).
Nothing is ever deleted by it.

### 2a. Service credentials, encrypted on the device (S-24)

NFR-021, UC-014 BR-003, BR-004, `SERVICE_CREDENTIAL`:

- `service_credential` is a table of `device.db`, which is never backed up or transferred (§1, §5). At most one row for
  the Budget App and one per Data Provider; `data_provider_id` is a plain column across databases.
- `secret` holds the credential the plug-in returned from its check (ADR-001 §4.5) as one ciphertext:
  AES-256 in GCM mode with a fresh 96-bit IV per encryption, stored as Base64 of IV, ciphertext and tag. The key is an
  Android Keystore key (`AndroidKeyStore`, alias `slussen.service-credential`), generated on the device with
  `KeyGenParameterSpec` for encryption and decryption only, GCM without padding, randomized encryption required. A
  Keystore key cannot be exported and is not part of any backup, so the ciphertext is useless off the device. No library
  is added (the platform's `javax.crypto` and the Keystore provider; ADR-001 §2).
- Only `:persistence` encrypts and decrypts, behind the port `CredentialVault` (ADR-001 §3); the plaintext exists only
  as a `Secret` in memory and is handed to the plug-in in its `RequestContext` (ADR-001 §4.0).
- O-16 replaces the row only after the service accepted the new credential (UC-014 step 4, A1) and deletes it on
  removal (A3).
- A ciphertext that cannot be decrypted (for example the Keystore key is gone) is treated as missing credentials: the
  row is deleted and the Blocked Reason "credentials missing" is shown (UC-014 BR-005). It is not Decision-Critical
  Data and opens no First Upload Matching (§6).
- After a reinstall or on a new device, `device.db` and the key are not restored; the Operator enters the credentials
  again (UC-014 BR-004).

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
| O-5 | `ComparisonStore.applyComparison` | Conclusions computed from stored data only (the stored copy of O-4, never a transient read): Transaction Statuses, Sent Records settled as Written when their Import Identifier was found, Transfer Counterparts reconciled from the stored `transfer_link_reference` with their stored `cleared` value (ADR-001 §4.2.3), `recovery_check.compared_at` when every Budget with a mapped Bank Account was read in the Comparison, and `comparison_pending` cleared; a Bank Transaction with a Sent Record is compared with the Budget Account recorded in its Sent Record as well as with its current one (UC-003 BR-021) | UC-003 steps 3-7, BR-017, BR-019, BR-020, BR-021; UC-005 BR-009 |
| O-6 | `UploadStore.preparePlan` | The `upload_plan`, its `eligible_transaction` and `upload_plan_item` records, and the mode Plan Open | ADR-007 §4 |
| O-7 | `UploadStore.confirmPlan` | §4 below: the Sent Record before every transaction send, with the expiry checked inside the transaction | UC-006 step 6, A5, A7, BR-006 |
| O-8 | `UploadStore.recordOutcomes` | The reported outcomes of one Budget Account's transaction sends | UC-006 steps 8-9, A8, A9, BR-012 |
| O-9 | `UploadStore.finishUpload` | The end of the Upload and the mode Normal | UC-006 step 12 |
| O-10 | `ConsentStore.startRequest`, `consumeAnswer`, `replaceConsent`, `revoke` (`device.db`) | Start: a new Consent Request with its correlation, making earlier ones of the Bank unanswerable. Consume: the most-recent, lifetime and not-answered checks and `answered` set, together. Replace: the Bank Consent and its Bank Session in place of the earlier ones, refused when a newer Consent Request exists. Revoke: delete the Bank Consent, its Bank Session and every Consent Request of the Bank | UC-007 BR-002, BR-003, BR-004, BR-007; NFR-013 |
| O-11 | `PluginRegistryStore.reconcile` | §2, the Data Providers and File Formats of the build, with the Request Limit (published, or a default and range) and the default Fetch Cooldown their parts declare | FR-017, UC-001 BR-006, BR-007 |
| O-12 | `FirstUploadMatchingStore.openCheck`, `confirmChecks` | Open a First Upload Matching with its cause; confirm every open one whose `compared_at` is filled, together with its Decision Journal record request | UC-006 BR-017, UC-008 A9, BR-009 |
| O-13 | `ReviewStore.decide`, `TransferStore.decide`, `MappingStore.*`, `SettingsStore.*` | Each decision or change with its effect on statuses; setting a Funded By or Transfer Partner (also from a Transfer Decision) is refused unless the Budget Account is in the same Budget, differs from the Bank Account's own, and receives through an Account Mapping of a Bank Account that is not a Retired Account, never a Manually Kept Account (UC-005 BR-014, `BANK_ACCOUNT` constraints); `MappingStore.move`, `MappingStore.remove` and `MappingStore.identifyAgain` change or delete an Account Mapping or record a new identification of its Bank Account (with `identified_at`), refused while a `sent_record` of the Bank Account's transactions with the mapping's `budget_account_id` (also one sent through an earlier mapping to that Budget Account; `first_sent_at` does not decide) has an `outcome` other than Rejected, unless the call carries the Operator's confirmation of the stated number of those sent transactions and, separately, of the Rejected ones; each records the change, and a move resets `first_sent_at` (UC-009 A6-A8, A11, BR-003, BR-007); `MappingStore.mapAgain` stores, without confirmation, a new Account Mapping with `first_sent_at` empty for a Bank Account that has none, refused unless the Budget Account belongs to a served Budget in the Bank Account's currency and receives through no Account Mapping (UC-009 A8); confirming a proposed match records the identification with `identified_at` (UC-009 steps 4-6, A1); renaming and Start Date are single updates (UC-009 A12, A13); a Review Decision on a Transfer Leg also sets the statuses of the other Transfer Legs of its Transfer (Deferred, or Already In Budget App while an Already Present decision holds) in the same transaction | UC-004 BR-006, BR-009; UC-005 BR-013, BR-014; UC-009 BR-003, BR-007, BR-008, A6-A14, UC-010 |
| O-14 | `UploadStore.recordClearing` | The result of one clearing (ADR-007 §6 step 8): a new `transfer_counterpart` with the leg's `sent_record_id`, its `budget_app_reference` and `state` Cleared (with `cleared_at`) or Uncleared; or an existing one set to Cleared with `cleared_at`. It never inserts, resets or changes a `sent_record` | UC-006 steps 10, BR-009, BR-010, BR-011, A11 |
| O-15 | `ConnectionStore.serveBudgets`, `ConnectionStore.leaveOut`, `ConnectionStore.addBankAccount`, `ConnectionStore.bringBack`, `ConnectionStore.setManuallyKept` | Serve: set `budget.served` for the chosen Budgets, refused for a Budget into which a Bank Account is mapped (UC-013 A2). Leave out: one `left_out_bank_account` for the Offered Bank Account whose import the Operator switched off, at once and without a confirmation, or one for each of them when the Operator switches off every one (UC-013 steps 6-7, A12, BR-011, BR-012). Add: a new `bank_account` with its generated identifier, proposed name, optional Start Date, how the Source identifies it, `identified_at`, for a File Format the optional `balance_data_provider_id` with the `balance_provider_reference` of the Offered Bank Account chosen for its balance, deleting its `left_out_bank_account` when it was left out (UC-013 A4, BR-006), and its `account_mapping`, refused when the Budget Account already receives through an Account Mapping, its Budget has another currency (UC-013 A5) or it is a Manually Kept Account (BR-014). Bring back: delete one `left_out_bank_account` when its import is switched on again (UC-013 A10). Mark and unmark: set or clear `budget_account.manually_kept` when the Operator switches "Receives from Slussen" off or on, setting refused while it receives through an Account Mapping (UC-013 A9, BR-014) | UC-013 steps 3-13, BR-001, BR-003, BR-011, BR-012, BR-014, A2, A4, A5, A9, A10, A12; FR-097, FR-098, FR-105, FR-106 |
| O-16 | `CredentialStore.replace`, `CredentialStore.remove` (`device.db`) | Replace: the encrypted `service_credential` of one service, only after the service accepted it, in place of the earlier one. Remove: delete it (§2a) | UC-014 steps 4, A1, A3; NFR-021 |

A crash at any point leaves each operation either complete or absent. Each has an instrumented test that injects a
failure before the commit and checks that nothing of it is visible (ADR-005 §7, E-05). Because O-4 stores the cursor
with its changes and the Budget App plug-in returns at least the same changes when read again from the same cursor
(ADR-001 §4.2.1 F-3), a crash between a read and its storage loses nothing (A-07). A crash between O-4 and O-5 leaves
`comparison_pending` set, which blocks preparation until the Comparison is recomputed from the stored data and O-5 is
committed (ADR-007 §3a, B-04).

### 4. The Sent Record is committed before anything is sent

O-7, `slussen.core.port.UploadStore.confirmPlan`:

```kotlin
/** Commits the Sent Record of every transaction-send item of the plan, records first sends, confirms the plan
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
   equals `reused_outcome`**; its `import_identifier` is never changed, and its `budget_account_id` is set to the
   item's: for a retry of a Not Yet Known or Unknown send that is always the stored one (UC-009 BR-003), and for a
   retry of a Rejected send it is the Budget Account of the current Account Mapping, which differs from the stored
   one after a move (ADR-007 §4; UC-006 BR-020, O-P2-34 (1));
3. sets `account_mapping.first_sent_at` to the same time for every Account Mapping that a new send or a retry of a
   Rejected send of the plan goes through, to its current Budget Account, whose `first_sent_at` is empty (UC-006
   step 6, UC-009 BR-003);
4. sets the `upload_plan.state` to Confirmed, only where it is still Awaiting Confirmation, and the coordinator mode to
   Uploading.

`ClearCounterpart` items get no Sent Record and change none; their results are stored by O-14 (B-02).

The unique index on (`budget_account_id`, `import_identifier`) of `sent_record` stays. A retry never inserts, so it
never meets the index (A-01); a retry of a Rejected send that changes `budget_account_id` keeps an Import Identifier
that only its own record carries. A violation can only come from a send whose Import Identifier another record
already carries, which ADR-008 §5 prevents, and is then UC-006 A7 (nothing sent, plan discarded, mode Normal).

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
Review Decision. Exactly once is not claimed for a Budget App that does neither. A Rejected send was refused, so no
Budget Account holds it (ADR-001 §4.2.1 F-2); sending it again to the current Account Mapping after a move cannot
create a second copy (UC-006 BR-020). And it covers only what Slussen sent
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
covered by First Upload Matching (§6), not by the backup.

Tests that prove it: a JVM test in `:persistence` parses both XML files and checks that the include list is exactly
`slussen.db` in every section and that no rule names `device.db` or `journal.db`; a test checks that the `device.db`
and `journal.db` paths are under `noBackupFilesDir`; the restore evidence of ADR-005 §7 (E-07) checks it on a device.

### 6. The start sequence and First Upload Matching (S-06; A-03, A-04)

At every start, before Room opens `slussen.db` and before any operation is admitted, `:persistence` runs the start
sequence and reports its result through `StartState` (ADR-001 §3):

| `installation.id` in `noBackupFilesDir` | `slussen.db` | Start | First Upload Matching opened with cause |
|---|---|---|---|
| present | present and readable | normal | none |
| absent | absent or empty | new installation | New Installation |
| absent | present | data restored from a device backup or transferred from another device (Android restores `slussen.db` but never `noBackupFilesDir`) | Restored Backup |
| present | absent | data cleared while the marker survived | New Installation |
| any | unreadable, started from the last good copy or empty (§8) | last good copy | Last Good Copy |

**Only Decision-Critical Data opens a First Upload Matching (S-13, B-06).** `slussen.db` holds all Decision-Critical Data
(the Bank Transactions with their statuses, the Sent Record, the chosen Budgets, the Bank Accounts with their
identifications, names and Start Dates, the Account Mappings with the time of their first send, the Review and Transfer Decisions, the Operator Settings), so damage to it, wherever in the file,
opens one. Damage to `device.db` (service credentials, Bank Consents, Bank Sessions, Consent Requests, Offered Bank
Accounts) or to
`journal.db` (the Decision Journal) shows the notice of UC-008 A1 only, opens no First Upload Matching and changes no Upload
Plan or Upload (NFR-005, NFR-006, UC-010 BR-004).

**The start intent survives process death (B-05).** A database transaction cannot cover file renames and a file
outside the database, so the start sequence makes the obligation to open a First Upload Matching durable before it changes
anything:

1. **Resume.** When `slussen.start-intent` exists, its cause is used and steps 2 and 3 are skipped; the sequence
   continues at step 4.
2. **Classify** by the table above.
3. **Record the intent.** For every start that opens a First Upload Matching, the sequence writes its cause to
   `slussen.start-intent` (written to a temporary file, flushed, renamed, and the directory flushed) **before** it
   writes `installation.id`, before it renames an unreadable `slussen.db` or copies the last good copy (§8), and before
   Room opens the database. A normal start writes no intent.
4. **Do the work of the cause.** For Last Good Copy: §8 step 3, which is safe to repeat (a `slussen.db` that still
   fails the checks of §8 step 1 is the unreadable original and is set aside again; one that passes is the
   replacement). Then `installation.id` is written when it is absent.
5. **Open First Upload Matching.** One transaction of O-12 opens the First Upload Matching with the cause and resets `coordinator_state`
   as ADR-007 §8 says.
6. **Clear the intent.** `slussen.start-intent` is deleted and the directory flushed, only after step 5 committed.

A process that dies between any two steps restarts at step 1 with the intent present and finishes the same work; it
can never look like a normal start. Dying between steps 5 and 6 opens a second First Upload Matching at the next start, which
the entity model allows ("also when an earlier one is still open") and which the same Comparison and confirmation close.
Each boundary is tested by an interruption (ADR-005 §7, E-12).

While any First Upload Matching is open, the coordinator prepares no Upload Plan (ADR-007 §3); UC-008 A8 shows why. A
Comparison that read every Budget with a mapped Bank Account fills `compared_at` (O-5, UC-003 BR-019), and only then
can the Operator confirm (O-12, UC-008 A9). This is how the data a backup or a last good copy lacks (Sent Records,
first sends, decisions written after it was made) is dealt with: the Comparison finds every transaction Slussen sent by its
Import Identifier (ADR-008), which no build can change, and the Operator checks the Budget App before any Upload.
Uploads never go ahead on a Sent Record that may be incomplete; a warning alone is not used.

**What UC-008 BR-009 shows comes from `cause` alone (S-41).** The reason line Slussen shows for each open First Upload
Matching is chosen by its `cause`: New Installation, Restored Backup or Last Good Copy. Nothing stored says when the
data Slussen started from was saved: Slussen cannot see when Android made the backup it restored (§5), and the date of
the last good copy (§8) is not recorded; `opened_at` is the time of the start, not of that data. So no reason line
names a date. A start from the last good copy that found none and started from the default content (§8 step 3) has
the same cause and shows the same line.

### 7. Upgrades keep the data (S-39)

NFR-017: installing a newer build over the installed one, a release or a debug build signed with the same key
(ADR-005 §6), loses 0 stored data, in all three databases; every change to how Slussen stores data upgrades the
stored data, and none deletes it.

- `exportSchema = true`. Room writes the schema it generates for each database into the build directory
  (`room.schemaLocation` under `persistence/build/`), never over the committed files; the committed files under
  `persistence/schemas/` are the reference.
- **A committed schema version is final.** Every schema version of each database committed under
  `persistence/schemas/` is final from its commit on, because a build that holds it may be installed: the Operator's
  phone holds one from Phase 9 on. The baseline is version 1 of `slussen.db`, `device.db` and `journal.db` as
  committed at `96712aa`. A committed version is never changed in place. This replaces the earlier rule that only
  a version "released" in a merged `VERSION` bump needed a Migration; construction changed version 1 of `slussen.db`
  in place under that rule, which ends with the baseline.
- **A schema change adds the next version.** Every change to a table, column, index or foreign key of a database,
  also a table that a later slice adds for an entity it builds, raises that database's version by one, commits the
  new version's schema file in the same commit, and adds a hand-written Room `Migration` from the previous version.
  The first after the baseline is `slussen.db` version 2 with `left_out_bank_account` (S-38).
- **Migration tests from every committed version.** For each database, a `MigrationTestHelper` test creates the
  database at every committed version with invented rows in every table, migrates it to the current version, and
  checks that every row and value is kept. It runs instrumented (§11; ADR-005 §7 E-06) and, where Room's JVM support
  allows it for these databases, also as a JVM test in `check`.
- **The schema check in the gate.** A JVM test in `:persistence`, run by `gradlew check`, compares for each database
  the schema the build generates for its current version with the committed file of that version by Room's identity
  hash. It fails, naming the database and the version, when they differ (an entity changed without a new version: an
  in-place change) and when the current version has no committed file (a new version not committed). A planted
  violation proves it (ADR-001 §6.6).
- A migration never rewrites `import_identifier`, `configuration_key` or `occurrence` values (ADR-008 §4), and never
  deletes a row or a value of stored data; a column that is no longer needed is kept, or its values are moved, in the
  same Migration.
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
   every default, the default Transfer Descriptions of UC-010 BR-007, and, through O-11 at the same start, one
   `data_provider` row per Data Provider with its declared default Fetch Cooldown and Request Limit) is created; the part is reported to the core,
   which shows the notice of UC-008 A1; a First Upload Matching is opened (§6).
4. **Unreadable while running.** A `TypeConverter` that meets an unknown text, or a failed foreign key, throws
   `UnreadableStoredData`. The app does not continue on that data: for `slussen.db` it writes the start intent with
   cause Last Good Copy (§6, step 3 of the start sequence) and restarts its process; the next start sequence resumes
   from that intent and treats `slussen.db` as unreadable (step 3 here). For `device.db` or `journal.db` it marks that
   file unreadable in the same way, without a start intent, and step 5 applies.
   Room's `onCorruption` callback does the same instead of SQLite's default, which deletes the file.
5. `device.db` and `journal.db` get no last good copy: starting with no Bank Consent is safe (the Operator grants
   again), and losing the journal changes nothing (NFR-006). Their unreadable originals are kept the same way, they
   start empty, the notice of UC-008 A1 names the part, and no First Upload Matching is opened (S-13).

The parts are named for the Operator: `slussen.db` "stored transactions, decisions and settings", `device.db` "service
credentials, Bank Consents and Offered Bank Accounts", `journal.db` "Decision Journal". The copy, the replacement, the logical check,
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
- A restore after reinstall brings back exactly `slussen.db` as of Android's last backup. Service credentials,
  Bank Consents, Bank Sessions, Offered Bank Accounts and the Decision Journal start empty: the Operator enters the
  credentials again (UC-014 BR-004), the Bank Accounts of Banks that need a Bank Consent show "missing consent" until
  the Operator grants again (FR-042), and UC-009 A10 applies until the next Fetch.
- After a restore, a new installation or a last good copy, Uploads wait for First Upload Matching. The Operator can see
  the Transaction Statuses at once; only Uploads wait.
- Cross-database references are checked by the core, not by SQLite.
- `TRUNCATE` journal mode writes more slowly than WAL, and the last good copy costs one file copy per start. The data
  is a household's transactions, so neither is noticeable; the backup size warning watches the file's growth.
- An Offered Bank Account cannot be confirmed after a reinstall until the next Fetch or grant (UC-009 A10).
- Damage to the Decision Journal or to `device.db` never blocks an Upload; damage to `slussen.db` always does until
  First Upload Matching is confirmed (S-13).
- From the baseline on (S-39), every schema change costs a new version, a Migration and its tests, also during
  construction; in exchange the Operator's phone keeps its data from Phase 9 on. The schema check sees only the
  current version of each database; an edit of an older committed file is caught by the migration tests from that
  version and by review, not by the check.
- The stored copy of the Budget App grows by four attributes per transaction (S-12); in exchange, a linked Upload
  whose answer was lost is settled from stored data alone after any restart.
- A crash during the start sequence or between the two halves of a Comparison costs at most a repeated step or a
  second open First Upload Matching, never a skipped one.

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
- **Opening a First Upload Matching for damage to any database.** Rejected by S-13 (B-06): it let Decision Journal damage
  block Uploads, against NFR-006.
- **Making the Comparison's read and conclusions one transaction.** Not chosen (B-04): the `comparison_pending` marker
  gives the same guarantee and keeps the network read outside a transaction (ADR-007 §3a).
- **Writing `installation.id` first and opening First Upload Matching after.** Rejected (B-05): a crash between the two
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
- A-05 (with S-03, S-10): superseded by S-23; §2 (the generated identifier, set when the Bank Account is added).
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
- S-20 (UC-005 BR-014): §2 (a Funded By or Transfer Partner whose Budget Account loses its last mapping of a Bank
  Account in use is kept but no longer applies), §3 O-13 (setting one is validated against Budget Accounts that
  Slussen fetches, never a Manually Kept Account).
- S-22 (FR-016, UC-001 BR-006, UC-010 BR-001, BR-002): Context (`DATA_PROVIDER.fetch_cooldown_minutes` instead of an
  `OPERATOR_SETTINGS` attribute), §2 (reconciliation sets a new Data Provider's Fetch Cooldown from its plug-in's
  default and keeps the Operator's value otherwise), §8 step 3 (default content).
- S-23/S-24 (2026-10-16, re-checked externally 2026-10-18): Context; §1 (`service_credential` in `device.db`, the Keystore key); §2
  (no Build Configuration of Budgets, Bank Accounts or Account Mappings; `budget.served`,
  `bank_account.generated_identifier`, `account_mapping.first_sent_at`, `offered_bank_account.balance`,
  `data_provider.request_limit` from the part, `fetch.outcome` Credentials Refused; O-11 registers the plug-ins); new
  §2a (service credentials encrypted with a non-exportable Android Keystore key, AES-256-GCM, never backed up); §3 O-5
  (UC-003 BR-021), O-13 (deliberate move and removal of an Account Mapping), new O-15 (UC-013) and O-16 (UC-014); §4
  step 3 (`first_sent_at`); the configuration fingerprint is no longer captured.
  - O-P2-34 (1) (2026-10-16, the Operator): §2 and §4 steps 2 and 3 (a retry of a Rejected send updates its Sent
    Record's `budget_account_id` to the current Account Mapping and records `first_sent_at` there; unknown outcomes
    keep theirs), §4 "What this guarantees"; ADR-007 §4, UC-006 BR-020.
- Mockup gate follow-up (2026-10-18): §2 and §3 O-13 (no confirmation to move or remove an Account Mapping whose Sent Records are all Rejected; `first_sent_at` keeps its meaning; UC-009 A8, A11, BR-003).
- Pre-Phase-6 review follow-up (2026-10-18): §2 and §3 O-13, O-15 (`bank_account.identified_at`, so the Fetch Cooldown never serves a Bank Account added or identified again, UC-001 BR-006; the confirmation to move, remove or identify again rests on the non-Rejected `sent_record`s alone, not on `first_sent_at`, and states the Rejected ones separately; mapping a Bank Account again; the balance Data Provider's reference stored on adding; UC-009 A6-A8, A11, BR-003, BR-007; UC-013 A4).
- Phase 7 (2026-10-18): S-33 (O-P2-48; UC-007 BR-009): §2 (`bank_account.provider_bank_id`, the Bank the Data Provider names, which decides the Bank Consent that covers the Bank Account; `bank_account.bank_id` names and groups only).
- Phase 7 (2026-10-18): S-31 (O-P2-42; FR-017, UC-001 BR-007, UC-010 A11, BR-002): Context and §2 (`data_provider.request_limit_published`; a Request Limit the Data Provider does not publish is an Operator Setting kept per Data Provider like the Fetch Cooldown; O-11 sets a published one from the part and keeps the Operator's value otherwise), §3 O-11, §8 step 3 (default content).
- Phase 9, second attempt (2026-10-19): S-38 (FR-105, FR-106, UC-013 BR-011..BR-014, A8-A13, UC-009 A15, A16, BR-013): Context; §1 (`left_out_bank_account` in `slussen.db`); §2 (`left_out_bank_account`, backed up with the Bank Accounts, matched against the Offered Bank Accounts of `device.db`; `budget_account.manually_kept` set by the Operator only); §3 O-13 and O-15 (leave out, bring back, mark and unmark). S-39 (NFR-017 widened): Context; §7 rewritten (every committed schema version is final from the baseline `96712aa`; a schema change adds the next version with a Migration and tests from every committed version; the schema check by identity hash in `check`; the "released" rule replaced); Consequences.
- Phase 9 acceptance (2026-10-19): S-40 (UC-013 steps 6-7, A9, A10, A12, BR-012, BR-014; A13 retired): §3 O-15 (a Left-Out Bank Account is recorded one at a time when its import is switched off, without the statement of numbers; marking and unmarking from the Budget App's page). No schema change.
- Phase 8f (2026-10-20): O-P2-72: §2 and §3 O-13 no longer name `MappingStore.bringBack` and `setManuallyKept`; since
  S-40, UC-009 A15 and A16 lead to the service pages and run through O-15; §2 `left_out_bank_account` updated for
  S-40 (one row per switch, no statement).
- Phase 9 acceptance (2026-10-24): S-41 (P9-17, O-P2-75; the glossary term Recovery Check renamed First Upload
  Matching, entity `RECOVERY_CHECK` renamed `FIRST_UPLOAD_MATCHING`): title, Context, §2 (the entity stays in the
  table `recovery_check` of committed schema version 1, an explicit exception to the naming rule; no new schema
  version), §2a, §3 O-12, §5, §6 (renamed; the reason lines of UC-008 BR-009 come from `cause`, and no date of a backup
  or of a last good copy is stored), §8, Consequences, Alternatives. The entries above keep the name of their time.
- S-41 follow-up (2026-10-24): §3 O-12 `RecoveryStore` renamed `FirstUploadMatchingStore`; the table keeps its name
  `recovery_check` (§2).
