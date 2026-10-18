# Architecture Re-Check — Mockup Gate (S-23, S-24, S-25, S-26)

- **Reviewer:** Google Gemini 4 (High)
- **Reviewed commit (`HEAD`):** `8906dadf99134be7b0dd3bb4bfb601e7ecfa07bb`
- **Diff base:** `ff455b3` (`git diff ff455b3..HEAD -- docs/ AGENTS.md`)
- **Prior reviews checked:** `docs/reviews/phase2_architecture_review.md`, `docs/reviews/phase2b_architecture_rereview.md`, `docs/reviews/phase2c_architecture_final_check.md`

---

## 1. Exactly Once and Nothing Without Confirmation

For each path below, the three failure modes are evaluated:
- **(a) Send twice:** Can Slussen send the same transaction to the Budget App twice without an explicit `Re-upload` decision?
- **(b) Send to an unconfirmed Budget Account:** Can Slussen send a transaction to a Budget Account that the Operator did not confirm?
- **(c) Make a sent transaction eligible again:** Can the path return a previously sent transaction to an upload-eligible state without an explicit Operator decision?

### Summary Table

| Path | Send twice? | Unconfirmed Budget Account? | Sent transaction eligible again? | Gap? |
|---|---|---|---|---|
| **1.1 Adding and pairing a Bank Account** | No | No | No | None |
| **1.2 Renaming a Bank Account** | No | No | No | None |
| **1.3 Identifying a Bank Account again** | No | No | No | None |
| **1.4 Moving an Account Mapping** | No | No | No | None |
| **1.5 Removing an Account Mapping** | No | No | No | None |
| **1.6 A retry after a move** | No | No | No | None |
| **1.7 Credentials refused, removed, or lost on reinstall** | No | No | No | None |
| **1.8 Reinstall with restoring a backup** | No | No | No | None |
| **1.9 Reinstall without restoring a backup (new `generated_identifier`s)** | No | No | No | None |
| **1.10 Setup or mapping change while an Upload Plan is open** | No | No | No | None |

---

### 1.1 Adding and Pairing a Bank Account

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Operator confirmation of pairing and zero writes during setup:** In `UC-013` steps 7–9 (`FR-097`, `FR-098`) and `ADR-002 §3 O-15` (`ConnectionStore.addBankAccount`), a `BANK_ACCOUNT` and its `ACCOUNT_MAPPING` are stored only when the Operator chooses the Offered Bank Account and its target `BUDGET_ACCOUNT` and confirms; `UC-013` Postconditions, `UC-006 BR-001` (`C-003`), and `ADR-007 §7` (`WritePermit`) prevent any write to the Budget App outside a confirmed Upload Plan.
  - **One-to-one mapping and currency guard:** `UC-013 BR-002` / `A5`, `UC-009 BR-001`, `entity_model.md` (`ACCOUNT_MAPPING`), and `ADR-002 §2` (`index_account_mapping_budget_account_id` unique) forbid mapping two Bank Accounts to the same `BUDGET_ACCOUNT`; `UC-013 A5` and `UC-003 BR-013` forbid pairing across different currencies.
  - **Changed external reference intercepted before duplicate creation:** If a Data Provider presents an already-added Bank Account under a new external reference, `UC-013 A6` and `UC-009 BR-004` (`FR-066`) match it by `BANK_ACCOUNT.bank_account_fingerprint` (`ADR-008 §8`) and route the Operator to `UC-009` confirmation instead of creating a second `BANK_ACCOUNT` row (`O-15` precondition).
  - **Generated identifier excluded from `Import Identifier`:** `UC-013 BR-003` (`FR-098`) and `ADR-008 §7` assign a v4 UUID to `BANK_ACCOUNT.generated_identifier`, while `FR-074`, `UC-003 BR-002`, and `ADR-008 §2` derive `Import Identifier` (`SLS1-...`) solely from `source_key` and `stable_reference` (or `booking_date`, `amount`, `payee`, `memo`, and `occurrence_number`), never from `generated_identifier` or `name`. Any transaction previously sent to that Budget Account is therefore matched by `Import Identifier` as `Already In Budget App` during Comparison (`UC-003 BR-003`).

### 1.2 Renaming a Bank Account

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Display-only attribute:** `FR-099`, `UC-009 A12`, `UC-009 BR-010`, `UC-009 BR-011`, `UC-013 BR-004`, and `entity_model.md` (`BANK_ACCOUNT.name`) specify that `name` is for display only and never identifies a Bank Account.
  - **No effect on identity, mapping, or history:** `ADR-002 §3 O-13` (`MappingStore.rename`) updates only `BANK_ACCOUNT.name` (and increments `coordinator_state.state_version`). It does not alter `BANK_ACCOUNT.id`, `generated_identifier`, `external_reference`, `bank_account_fingerprint`, `ACCOUNT_MAPPING`, `BANK_TRANSACTION`, `SENT_RECORD`, or `Import Identifier` (`ADR-008 §2`, `§8`).

### 1.3 Identifying a Bank Account Again

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **No silent matching by name or position:** When a Bank Account's `external_reference` is missing or changed at the Source, `UC-001 A7`, `UC-009` steps 1–6, `UC-009 BR-001`, and `UC-009 BR-004` (`FR-063`, `FR-066`) block Fetch and Upload for that Bank Account until the Operator explicitly confirms the Offered Bank Account in `UC-009`.
  - **Confirmed act with sent count after a send:** If any transaction has been sent for that Bank Account (`ACCOUNT_MAPPING.first_sent_at != null`), `UC-009 A7` and `UC-009 BR-007` require Operator confirmation that states the count of previously sent transactions before updating the reference.
  - **Preservation of `BANK_ACCOUNT` identity and `SENT_RECORD`s:** `UC-009 BR-010` and `ADR-002 §3 O-13` (`MappingStore.confirmMapping`) update `external_reference` and `bank_account_fingerprint` on the same `BANK_ACCOUNT` row, preserving `id`, `generated_identifier`, `ACCOUNT_MAPPING`, `BANK_TRANSACTION`s, and `SENT_RECORD`s. Subsequent Comparisons match existing transactions by `SENT_RECORD` and `Import Identifier` (`UC-003 BR-001`, `BR-002`, `BR-003`).

### 1.4 Moving an Account Mapping

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Confirmed act stating sent and rejected counts:** Once `ACCOUNT_MAPPING.first_sent_at` is set (`ADR-002 §4` step 3), `UC-009 A8`, `UC-009 BR-003`, and `ADR-002 §3 O-13` (`MappingStore.move`) refuse to move the mapping to another `BUDGET_ACCOUNT` unless the Operator confirms a prompt stating both the count of sent transactions (`Written`, `Already Present`, `Not Yet Known`, `Unknown`) and the count of `Rejected` transactions.
  - **Per-send `budget_account_id` preserved on `SENT_RECORD`:** `entity_model.md` (`SENT_RECORD.budget_account_id`), `UC-009 BR-003`, and `ADR-002 §2` keep the `budget_account_id` to which each transaction was sent on its `SENT_RECORD` when `ACCOUNT_MAPPING.budget_account_id` changes.
  - **Two-account Comparison check prevents re-eligibility:** `UC-003 BR-001`, `UC-003 BR-021` (`FR-027`, `FR-065`), and `ADR-002 §3 O-5` compare every `BANK_TRANSACTION` that has a `SENT_RECORD` (`Written`, `Already Present`, `Not Yet Known`, or `Unknown`) against **both** the `BUDGET_ACCOUNT` recorded in its `SENT_RECORD` and its current `BUDGET_ACCOUNT`. If found in either, its status is `Already In Budget App`; if absent from both, it is `Deleted In Budget App` (or `Merged In Budget App`), never `New`.
  - **Upload eligibility gate:** `UC-006 BR-006` (`FR-047`) and `ADR-007 §4` exclude any transaction whose `SENT_RECORD` is `Written` or `Already Present` unless the Operator makes an explicit `Re-upload` Review Decision (`UC-004 A5`, `BR-003`), which increments `occurrence` and generates a new `Import Identifier` (`ADR-008 §5`) only for a future Upload Plan that the Operator must still confirm (`UC-006 BR-001`).

### 1.5 Removing an Account Mapping

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Confirmed act stating sent and rejected counts:** When `ACCOUNT_MAPPING.first_sent_at` is set, `UC-009 A11`, `UC-009 BR-003`, and `ADR-002 §3 O-13` (`MappingStore.remove`) require Operator confirmation stating the counts of sent and `Rejected` transactions before deleting the `ACCOUNT_MAPPING` row.
  - **No Fetch, Comparison, or Upload without an `ACCOUNT_MAPPING`:** While a `BANK_ACCOUNT` has no `ACCOUNT_MAPPING`, `UC-001` step 3 skips it during Fetch, `UC-009 A11` skips it during Comparison, and `entity_model.md` (`BANK_ACCOUNT`), `UC-006` Preconditions, `UC-006 BR-020`, and `ADR-007 §4` exclude all of its transactions (including `Rejected` ones) from Upload Plans and Uploads.
  - **History retained if mapped again later:** `UC-009 A11` and `UC-009 BR-010` retain the `BANK_ACCOUNT`, its `BANK_TRANSACTION`s, and their `SENT_RECORD`s (each with its recorded `budget_account_id`). If the Operator later creates a new `ACCOUNT_MAPPING` for that `BANK_ACCOUNT`, `UC-003 BR-021` and `UC-006 BR-006` still classify previously sent transactions against their recorded `SENT_RECORD.budget_account_id` and never treat them as `New`.

### 1.6 A Retry After a Move

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Case A — `SENT_RECORD.outcome` is `Not Yet Known` or `Unknown`:**
    - *Target Budget Account:* Because the Budget App may already have written the earlier attempt to the original `BUDGET_ACCOUNT`, `UC-009 BR-003`, `UC-006 BR-007`, `ADR-007 §4`, and `ADR-002 §4` step 2 keep `SENT_RECORD.budget_account_id` and `SENT_RECORD.import_identifier` unchanged; any retry sends to the **recorded** `SENT_RECORD.budget_account_id` (which the Operator confirmed in the original Upload Plan and confirms again in the retry's Upload Plan, `UC-006` steps 3–4).
    - *Duplicate prevention:* Under `UC-006 A8` / `BR-007` and `ADR-007 §4`, automatic retry is permitted only when the Budget App plug-in declares `refusesReusedImportIdentifier = true` (`C-009`), in which case the Budget App deduplicates by `Import Identifier` and returns `AlreadyPresent` (`ADR-001 §4.2.1 F-2`). If `refusesReusedImportIdentifier = false`, `UC-006 A9` / `BR-007` and `UC-004 BR-008` block retry unless the Operator makes an explicit `Import Anyway` decision after checking the Budget App.
  - **Case B — `SENT_RECORD.outcome` is `Rejected`:**
    - *Target Budget Account and single write:* A `Rejected` outcome means the Budget App refused the transaction and holds it in neither Budget Account (duplicate `Import Identifier` refusal is mapped to `AlreadyPresent`, never `Rejected`, per `ADR-001 §4.2.1 F-2`). In `UC-009 A8`, the Operator confirmed moving the mapping with a prompt stating the count of `Rejected` transactions that will follow the new mapping. In `UC-006 BR-020`, `ADR-007 §4`, and `ADR-002 §4` steps 2–3, the retry is planned for the **current** `ACCOUNT_MAPPING.budget_account_id` under the same `import_identifier`, reuses and updates the single `SENT_RECORD` row in `O-7` (updating `budget_account_id` and setting `first_sent_at` on the new `ACCOUNT_MAPPING`), and is sent only after the Operator confirms the new Upload Plan (`UC-006` steps 3–4, `BR-001`).

### 1.7 Credentials Refused, Removed, or Lost on Reinstall

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Refused replacement credentials never overwrite stored state:** In `UC-014 A1` / `A2`, `UC-014 BR-006`, `ADR-001 §4.0`, and `ADR-002 §3 O-16`, credentials are tested via `checkCredentials` before `O-16` writes to `device.db`; a refused or unreachable check stores nothing and leaves existing credentials and domain state untouched.
  - **Removed, refused, or lost credentials block network operations without touching history:** In `UC-014 A3`, `UC-014 BR-004`, `UC-014 BR-005` (`FR-102`), `UC-008 BR-006`, and `ADR-002 §2a` / `§3 O-16`, removing credentials or losing `device.db` / the Android Keystore key (`slussen.credentials.v1`) sets the service to `"credentials missing"`, and a credential refusal during Fetch or Comparison (`UC-001 A7`, `UC-003 A2`, `ADR-001 §4.5`) sets it to `"credentials refused"`. Neither event deletes or modifies `BUDGET`, `BANK_ACCOUNT`, `ACCOUNT_MAPPING`, `BANK_TRANSACTION`, `SENT_RECORD`, `REVIEW_DECISION`, or `TRANSFER_DECISION` (`UC-014 BR-005`).
  - **No Upload without a fresh Budget App read and valid credentials:** `ADR-007 §6` step 4 (`UC-006 A6`, `BR-004`) re-reads every affected Budget from the Budget App before `O-7` writes any `SENT_RECORD` or calls `sendTransactions`. If Budget App credentials are missing or refused, confirmation aborts before `O-7` and writes nothing. Furthermore, damage to or loss of `device.db` never opens a Recovery Check or alters any Upload Plan (`NFR-005`, `ADR-002 §2a`, `§6`).

### 1.8 A Reinstall With Restoring a Backup

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Domain history restored intact:** `slussen.db` is included in Android backup (`NFR-010`, `UC-009 BR-006`, `UC-013 BR-007`, `ADR-002 §5`), restoring all `BUDGET`s, `BANK_ACCOUNT`s (with their original `generated_identifier`s), `ACCOUNT_MAPPING`s (`first_sent_at`), `BANK_TRANSACTION`s, `SENT_RECORD`s, `REVIEW_DECISION`s, and `TRANSFER_DECISION`s, while `device.db` and `noBackupFilesDir/installation.id` are excluded (`UC-014 BR-004`, `ADR-002 §2a`, `§5`).
  - **Durable `Restored Backup` Recovery Check blocks Upload Plans:** On first start after restore (`slussen.db` present, `installation.id` absent), `ADR-002 §6` writes `noBackupFilesDir/slussen.start-intent` before creating `slussen.db` or `installation.id` and records an open `RECOVERY_CHECK` (`cause = Restored Backup`, `UC-008 A8`, `BR-009`). While that `RECOVERY_CHECK` is open, `UC-006` Preconditions, `UC-006 A13`, `UC-006 BR-017` (`FR-085`), and `ADR-007 §2` / `§3` (`RecoveryCheckGate`) refuse `Prepare an Upload Plan` and `Confirm Upload Plan`.
  - **Mandatory Comparison and `Import Identifier` reconciliation before clearing:** Under `UC-008 A9`, `UC-008 BR-009`, `UC-003 BR-019`, and `ADR-002 §3 O-5` / `O-12`, the Operator cannot confirm and clear the Recovery Check until credentials are re-entered (`UC-014 BR-004`) and a Comparison has read every `BUDGET` with a mapped `BANK_ACCOUNT` (`RecoveryCheckBudget.compared = 1`). Any transaction sent after the backup snapshot was taken is matched in the Budget App by its deterministic `Import Identifier` (`UC-003 BR-002`, `BR-003`, `ADR-008 §2`) as `Already In Budget App`, and any in-flight `SENT_RECORD` (`Not Yet Known`) is reconciled by `UC-003 BR-017` (`FR-076`) or protected by the Budget App's refusal of a reused `Import Identifier` (`UC-006 BR-007`, `C-009`).

### 1.9 A Reinstall Without Restoring a Backup (New `generated_identifier`s)

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Durable `New Installation` Recovery Check blocks Upload Plans:** When both `slussen.db` and `noBackupFilesDir/installation.id` are absent on start, `ADR-002 §6` durably records `slussen.start-intent` (`NEW_INSTALLATION`) and opens a `RECOVERY_CHECK` (`cause = New Installation`, `UC-008 A9`, `BR-009`, `FR-084`), which blocks `Prepare an Upload Plan` (`UC-006 BR-017`, `A13`, `ADR-007 §3`) until all mapped Budgets have been compared (`UC-003 BR-019`) and the Operator confirms the Recovery Check (`ADR-002 §3 O-12`).
  - **Explicit pairing confirmation and `Start Date` on new setup:** The Operator must enter credentials (`UC-014`) and explicitly choose Budgets and pair each Bank Account with a Budget Account (`UC-013` steps 7–9, `O-15`), optionally setting a `Start Date` (`UC-013 BR-005`, `UC-003 BR-018`) before which transactions are `Before Start Date` and excluded from Upload.
  - **`Import Identifier` independence from `generated_identifier`:** Although `O-15` assigns a brand-new v4 UUID to `BANK_ACCOUNT.generated_identifier` for each newly added Bank Account (`UC-013 BR-003`, `ADR-008 §7`), `FR-074`, `UC-003 BR-002`, `ADR-008 §2`, and `ADR-008 §7` exclude `generated_identifier` (and `name`) from `Import Identifier` derivation (`SLS1:<source_key>:R:<stable_reference>` or `SLS1:<source_key>:F:<date>:<amount>:<payee>:<memo>:<occurrence_number>`). Consequently, every fetched transaction produces the exact same `Import Identifier` it had before the reinstall.
  - **Mandatory Comparison matches prior sends in the Budget App:** During the mandatory Comparison required to clear the `New Installation` Recovery Check (`UC-003 BR-019`, `UC-008 A9`), `UC-003 BR-003` matches every transaction already present in the Budget App under that `Import Identifier` and marks it `Already In Budget App` (ineligible for Upload under `UC-006 BR-006`), while `C-009` (`refusesReusedImportIdentifier`) provides a second barrier at the Budget App boundary (`UC-006 BR-014`).

### 1.10 A Setup or Mapping Change While an Upload Plan Is Open

- **No send twice / no unconfirmed Budget Account / no sent transaction made eligible again.**
- **Rules that prevent it:**
  - **Screen-bound Upload Plan lifetime:** `UC-006 BR-019` (`FR-042`), `UC-006 A2`, and `ADR-007 §5` specify that an Upload Plan exists only in memory while its screen is open; navigating away before confirming cancels the plan (`state = Cancelled`) and returns `OperationCoordinator` to `Normal` without writing anything.
  - **Coordinator admission refusal while `Plan Open` or `Uploading`:** While an Upload Plan is open (`mode = Plan Open`) or being sent (`mode = Uploading`), `UC-006 BR-005`, the Preconditions of `UC-009`, `UC-010`, `UC-013`, and `UC-014`, and `ADR-007 §2` (`OperationCoordinator` admission table) refuse every setup, credential, setting, or mapping operation: `UC-009` (confirm, move, remove, rename, or change `Start Date` on an Account Mapping), `UC-010` (change Operator Settings), `UC-013` (choose Budgets or add Bank Accounts), and `UC-014` (enter, replace, or remove service credentials).
  - **Monotonic `state_version` guard at confirmation:** Even if a state-changing transaction (`O-6`, `O-9`, `O-11`, `O-13`, `O-14`, `O-15`) were to commit around a plan boundary, every such operation increments `coordinator_state.state_version` in the same SQLite transaction (`ADR-002 §2`, `§3`), and `ADR-007 §6` step 2 (`UC-006 BR-004`, `A5`) verifies that `coordinator_state.state_version` still equals `plan.stateVersionAtCreation` (and re-reads the Budget App in step 4) before `O-7`, transitioning the plan to `Changed Before Writing` and sending nothing if anything changed.

---

## 2. New Contradictions of Severity High

None. No new contradiction of severity High was found between ADRs, between an ADR and the specification, or inside the changed specification.

---

**Verdict: Yes — the Operator can remove "re-check pending" from the ADRs (no D-nn findings block it).**
