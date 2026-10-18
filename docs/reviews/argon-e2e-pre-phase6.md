# End-to-End Pre-Phase-6 Peer Review — Specification, Architecture, Entity Model, and Mockups

- **Reviewer:** Gemini 4 Argon (High) (`argon-e2e-pre-phase6`)
- **Commit reviewed:** `a819919cee37a6e9bc27de219228745e367d1830`
- **Scope:** End-to-end sanity review of the complete specification (`docs/vision.md`, `docs/requirements.md`, `docs/glossary.md`, `docs/entity_model.md`, `docs/use_cases.puml`, `docs/use_cases/UC-001..UC-014.md`), architecture (`docs/adr/ADR-001..ADR-008.md`), screen mockups (`docs/mockups/*.html`), and construction plan (`docs/construction_kickoff_plan.md`) before Phase 6 (`build: phase 6 - module skeleton`) and Phase 7 (`feat: phase 7 - set up an own build and check import status`).

---

## 1. Review Method

Rather than checking individual files in isolation, this review tested the repository as a **closed system** along six orthogonal axes:

1. **Axis 1 — Deterministic Gate & Structural Traceability Audit:**
   Executed `python tools/lint_specs.py --strict` and `python tools/check_project_rules.py`, then verified the bidirectional chain `vision.md` → `requirements.md` (`FR-001..FR-103`, `NFR-001..NFR-021`, `C-001..C-015`) → `use_cases.puml` & `UC-001..UC-014` → `entity_model.md` (21 entities) → `ADR-001..ADR-008` → `docs/mockups/index.html` and all 19 HTML mockup files.
2. **Axis 2 — End-to-End Lifecycle State-Machine Simulation:**
   Simulated the state of the SQLite database (`ADR-002`) and `OperationCoordinator` (`ADR-007`) step by step through the full lifecycle of a real Operator:
   - *Stage A:* Fresh install (`UC-012`) → Enter YNAB & SimpleFIN credentials (`UC-014`) → Choose Budgets (`UC-013`) → First-run Fetch of Offered Bank Accounts (`UC-013 A3` → `UC-001`) → Pair Bank Accounts & set Start Dates (`UC-013`) → First post-pairing Fetch (`UC-001`).
   - *Stage B:* Startup Recovery Check (`UC-008`) → Enable Banking consent grant/renewal (`UC-007`) → File import + balance-only Data Provider fetch (`UC-002`, `UC-013 A4`, `UC-001 BR-011`).
   - *Stage C:* Compare with Budget App (`UC-003`) → Review Candidates, Credit Card Funding, and Reconciled Differences (`UC-004`, `UC-005`) → Assemble, confirm, and execute Upload Plan (`UC-006`) including interrupted-send recovery (`UC-008`).
   - *Stage D:* Post-send Account Mapping mutations (`UC-009`: rename, re-identify, move, remove, map again) → Operator Settings (`UC-010`) → Decision Journal export (`UC-011`).
3. **Axis 3 — Cross-Layer Invariant & Concurrency Audit:**
   Compared the state-exclusion matrix in `ADR-007` §2 and the 15 atomic Room write transactions (`O-01..O-15`) in `ADR-002` §3 against every Use Case Precondition, Postcondition, Business Rule, and `entity_model.md` constraint—specifically checking whether recent spec refinements (`S-21..S-27`, `O-P2-20..O-P2-36`) propagated into the ADRs and older use cases.
4. **Axis 4 — Domain Contract & SPI Completeness:**
   Checked that the pure Kotlin interfaces in `core-model` (`DataProvider`, `FileFormat`, `BudgetApp` in `ADR-001` §4) and the Room schema (`ADR-002`) carry every input and output needed by `UC-001..UC-014`.
5. **Axis 5 — UI / Mockup Walk-Through & Copy Verification:**
   Followed links across all 19 mockup files and cross-checked displayed counts, defaults, and actions against the governing Use Case steps and Business Rules.
6. **Axis 6 — Phase 6 & Phase 7 Construction Readiness:**
   Assessed whether any finding blocks Phase 6 (Gradle multi-module skeleton) or Phase 7 (`UC-012 + UC-014 + UC-013 + UC-008`).

---

## 2. Executive Assessment

### What is in strong shape
- **Deterministic gates are clean:** `python tools/lint_specs.py --strict` passes with **0 errors, 0 warnings, 0 infos**, and `python tools/check_project_rules.py` passes across all **47 checked files with 0 findings**.
- **Requirements coverage is complete:** Every requirement (`FR-001..FR-103`, `NFR-001..NFR-021`, `C-001..C-015`) traces to an approved Use Case (`UC-001..UC-014`), an entity/attribute in `docs/entity_model.md`, an architectural component in `ADR-001..ADR-008`, and (for Operator-facing behavior) a frame in `docs/mockups/*.html`.
- **Core safety invariants are sound:** The architectural guarantees that matter most to the Vision—*no silent drops, no silent writes to YNAB, deterministic `import_id` protocol (`ADR-008`), two-step `Sending` → `Written`/`Ambiguous` recovery journal (`ADR-002`, `ADR-007`), SQLCipher + Keystore encryption (`ADR-005`), and strict module isolation (`ADR-001`)*—are coherently designed and mutually reinforcing.

### Where seams remain (6 findings: 1 High, 4 Medium, 1 Low)
The end-to-end simulation exposed **one High-severity lifecycle trap** in the first-run / pairing flow (`E2E-01`) and **four Medium-severity cross-artifact inconsistencies** (`E2E-02`..`E2E-05`), three of which were introduced when recent spec changes (`S-27` / `O-P2-35` / `O-P2-36` and `UC-009 A8/A11`) updated the Use Cases in scope without propagating the matching changes into `ADR-002`, `ADR-007`, or the Preconditions of `UC-001..UC-003, UC-011`.

---

## 3. End-to-End Architecture & Specification Matrix

| Layer / Artifact | Status | Summary of Alignment |
|---|---|---|
| `docs/vision.md` & `docs/requirements.md` | **Pass (1 minor drift)** | All `FR-001..FR-103`, `NFR-001..NFR-021`, `C-001..C-015` are specified. `FR-036` slightly trails `UC-006 BR-005` on the `Uploading` pause (`E2E-04`). |
| `docs/use_cases/UC-001..UC-014.md` | **1 High, 2 Medium** | All 14 UCs are `Approved`. First-run Fetch vs Fetch Cooldown has a lifecycle trap (`E2E-01`); `UC-001/002/003/011` Preconditions trail `UC-006 BR-005` (`E2E-04`); `UC-001` step 3 omits balance-only Data Provider accounts (`E2E-05`). |
| `docs/entity_model.md` & `docs/glossary.md` | **1 Medium** | All 21 entities and relationships match `ADR-002`. `ACCOUNT_MAPPING.first_sent_at` constraint has a bypass after `Remove` + `Map again` (`E2E-03`), and `BANK_ACCOUNT.provider_account_id` / `identification_status` need a note for balance-only Data Providers (`E2E-05`). |
| `docs/adr/ADR-001..ADR-008.md` | **2 Medium** | `ADR-001, 003, 004, 005, 006, 008` are fully aligned. `ADR-002 O-13/O-15` and `ADR-007 §9` were not updated after commit `a819919` (`S-27`) (`E2E-02`). |
| `docs/mockups/*.html` (19 files) | **Pass (1 Low)** | Every Operator-facing step and alternative flow is reachable and faithful to the UCs. Two dialog buttons in `connections.html` and one count label in `settings.html` have minor polish issues (`E2E-06`). |

---

## 4. Detailed Findings

### E2E-01 — High: First-run Fetch of a Data Provider starts its Fetch Cooldown while storing zero transactions, blocking the post-pairing Fetch

- **Severity:** High (a first-run or add-account flow cannot fetch transactions after pairing until the Data Provider's Fetch Cooldown expires)
- **Locations:**
  - `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` step 11 (line 31), `A3` (lines 51–57), `Success Postconditions` (line 97)
  - `docs/use_cases/UC-001-fetch-transactions.md` steps 2–8 (lines 23–28), `A2` (lines 48–56), `A18` (lines 195–201), `Success Postconditions` (line 214), `BR-006` (lines 249–251)
  - `docs/adr/ADR-001-module-boundaries.md` §4.1 `FetchRequest` (lines 269–272)
  - `docs/mockups/setup.html#first-fetch` (lines 383–389) and `setup.html#done` (lines 438–444)
- **Evidence:**
  1. During first-run setup (`UC-013 A3`: *"A Data Provider has not been fetched yet when the Operator wants to add a Bank Account"*), the Operator runs a Fetch (`UC-001`) so SimpleFIN delivers its `OFFERED_BANK_ACCOUNT`s.
  2. At that moment, **zero** Bank Accounts have been added by the Operator yet. Under `UC-001 A18` (*"A delivered Bank Account matches none of the Bank Accounts the Operator added"*, lines 195–201):
     > *"1. Slussen keeps it only as an Offered Bank Account (UC-009 BR-009) and stores none of its transactions."*
     `setup.html#first-fetch` (line 388) reflects this: *"Bank Accounts offered: 3 · Transactions come from the next Fetch after they are added"*.
  3. However, because that Fetch delivered 3 Offered Bank Accounts from SimpleFIN, it is a successful Fetch (`FETCH.outcome = Fetched`) and does **not** qualify for the exception in `UC-001 BR-006` (line 251):
     > *"A Fetch that delivered no Bank Account does not start a Fetch Cooldown."*
     Therefore, `UC-001` Success Postcondition (line 214) starts SimpleFIN's **Fetch Cooldown** (`60 minutes by default` for SimpleFIN; `360 minutes by default` for Enable Banking).
  4. Immediately after pairing those 3 Bank Accounts (`setup.html#pair` → `#start-date` → `#done`), the Operator taps `Fetch all Data Providers` on `setup.html#done` (line 443, corresponding to `UC-013` Success Postcondition line 97: *"Each added Bank Account ... is read from the next Fetch"*).
  5. When `UC-001` runs step 2, SimpleFIN is inside its 60-minute Fetch Cooldown. `UC-001 A2` (*"Within the Fetch Cooldown"*, lines 52–55) triggers:
     > *"1. Slussen does not contact the Data Provider and uses the stored Bank Transactions and Account Balances from the last Fetch instead."*
     Because **no** transactions and **no** balances were stored for those 3 Bank Accounts during `#first-fetch` (`UC-001 A18`), the post-pairing Fetch returns **nothing** for the newly added Bank Accounts for the next 60 minutes (or 6 hours for Enable Banking).
  6. Relatedly, `UC-001` step 3 (line 24) selects *"every Bank Account the Operator added for the Data Provider (UC-013) that has an Account Mapping..."* and `UC-001 A4` step 3 (line 73) says *"or ends when no Bank Account is left to read"*. When 0 Bank Accounts have been added yet (`UC-013 A3`), step 3 selects an empty set—so `UC-001` step 3/4 should explicitly state that when a Data Provider is fetched to discover its Offered Bank Accounts (or when no added Bank Account is selected), Slussen still asks the Data Provider for its Offered Bank Accounts.
- **Fix:** Specify in `UC-001` (`step 3`, `A2`, `BR-006`) and `ADR-001` §4.1 that (a) a Fetch of a Data Provider with no selected Bank Accounts still requests its Offered Bank Accounts, and (b) a Fetch that read no added Bank Account's transactions—or a Bank Account added or re-identified since the Data Provider's last Fetch—is not served from the Fetch Cooldown (`BR-006`).

---

### E2E-02 — Medium: `ADR-002` (`O-13`, `O-15`) and `ADR-007` (§9) were not updated after `S-27` (`UC-009 A7, A8, A11, BR-003, BR-007`)

- **Severity:** Medium (architectural contract in `ADR-002` and `ADR-007` contradicts `UC-009` and `entity_model.md` on what `MappingStore` and `OperationCoordinator` must guard and expose)
- **Locations:**
  - `docs/use_cases/UC-009-manage-account-mappings.md` `A7` (lines 84–91), `A8` (lines 93–103), `A11` (lines 124–133), `BR-003` (lines 175–177), `BR-007` (lines 191–193)
  - `docs/entity_model.md` `ACCOUNT_MAPPING` Constraints (lines 219–220)
  - `docs/adr/ADR-002-persistence-with-room.md` §3 `O-13` (line 226) and `O-15` (line 228)
  - `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §9 (lines 345–353)
- **Evidence:**
  In commit `a819919` (`S-27`, `O-P2-35`, `N-07`, `N-08`), `UC-009` and `entity_model.md` were updated in three ways that did not propagate to `ADR-002` and `ADR-007`:
  1. **Confirmation guard on `identifyAgain` (`UC-009 A7, BR-007`, `entity_model.md` line 220):** Identifying a Bank Account again as a different Offered Bank Account now requires the Operator's confirmation when a non-`Rejected` `SENT_RECORD` exists for the mapping. However, `ADR-002` §3 `O-13` (line 226) still says:
     > *"renaming, Start Date and identifying again are single updates (UC-009 A6, A7, A12, A13)"*
     And `ADR-007` §9 (*"Moving or removing an Account Mapping after a send"*, lines 345–353) describes the confirmation guard only for `MappingStore.move` and `MappingStore.remove`, omitting `identifyAgain`.
  2. **Separate count of `Rejected` transactions (`UC-009 A7, A8, A11, BR-003, BR-007`):** The confirmation contract now requires stating both the count of non-`Rejected` sent transactions and, separately, the count of `Rejected` ones. `ADR-002 O-13` (line 226) and `ADR-007` §9 (line 348) mention only *"the count of sent transactions"*.
  3. **Mapping an existing unmapped Bank Account (`UC-009 A8`):** `UC-009 A8` now covers both moving an existing `ACCOUNT_MAPPING` and creating a new `ACCOUNT_MAPPING` for a `BANK_ACCOUNT` whose mapping was removed in `A11` (*"or stores the new Account Mapping of a Bank Account that had none"*, line 102). `ADR-002 O-13` covers only changing or deleting an existing `account_mapping` (`MappingStore.move` / `MappingStore.remove`), while `O-15` (`ConnectionStore.addBankAccount`) creates both a `bank_account` and an `account_mapping`.
- **Fix:** Update `ADR-002` §3 `O-13` and `ADR-007` §9 so `MappingStore.identifyAgain` enforces the non-`Rejected` `sent_record` confirmation guard (`UC-009 A7, BR-007`), `MappingStore` includes mapping an existing unmapped `bank_account` (`UC-009 A8`), and the confirmation payload carries both the non-`Rejected` count and the `Rejected` count.

---

### E2E-03 — Medium: `first_sent_at` confirmation guard in `entity_model.md`, `ADR-002`, and `ADR-007` is bypassed after `Remove` (`UC-009 A11`) followed by `Map again` (`UC-009 A8`) to the same Budget Account

- **Severity:** Medium (state-transition inconsistency between `ACCOUNT_MAPPING.first_sent_at` and historical `SENT_RECORD` rows)
- **Locations:**
  - `docs/entity_model.md` `ACCOUNT_MAPPING` (`first_sent_at` line 213; Constraints lines 219–220)
  - `docs/use_cases/UC-009-manage-account-mappings.md` `A8` (lines 93–103), `A11` (lines 124–133), `BR-001` (line 169), `BR-003` (lines 175–177)
  - `docs/adr/ADR-002-persistence-with-room.md` §3 `O-13` (line 226)
  - `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §9 (lines 347–351)
- **Evidence:**
  1. `UC-009 BR-001` (line 169) and `docs/mockups/account-mappings.html#remove` (line 43) instruct the Operator to remove an `ACCOUNT_MAPPING` (`A11`) to free a Budget Account during a rearrangement, and then map the Bank Account again (`A8`).
  2. Removing the mapping in `A11` deletes the `ACCOUNT_MAPPING` row (`entity_model.md` line 55: `has zero or one ACCOUNT_MAPPING`; `ADR-002 O-13`: `MappingStore.remove ... delete an Account Mapping`), while all historical `SENT_RECORD` rows for `(bank_account_id, budget_account_id)` remain in `SENT_RECORD`.
  3. If the Operator subsequently maps that Bank Account back to the **same** `budget_account_id` via `A8` (for instance, after deciding not to swap, or after moving it back), `A8` step 5 creates a new `ACCOUNT_MAPPING` row whose `first_sent_at` is **empty** (`entity_model.md` line 213: *"Time of the first send through this mapping; empty until the first send"*).
  4. However, `entity_model.md` lines 219–220, `ADR-002 O-13` (line 226), and `ADR-007` §9 (line 347) all require **`first_sent_at` to be filled AND a non-`Rejected` `SENT_RECORD` to exist** before asking for confirmation on move, remove, or re-identify:
     > *"Once `first_sent_at` is filled and a `SENT_RECORD` of the Bank Account's transactions with this `budget_account_id` has an `outcome` other than Rejected..."* (`entity_model.md` line 219)
     Because `first_sent_at` is empty on the recreated row (and is also reset to empty by `MappingStore.move` in `ADR-002 O-13`), moving, removing, or re-identifying that mapping before the next send would see `first_sent_at == null` and skip the confirmation guard—even though non-`Rejected` `SENT_RECORD`s already exist for `(bank_account_id, budget_account_id)`.
- **Fix:** Either initialize `first_sent_at` from the earliest `SENT_RECORD` for `(bank_account_id, budget_account_id)` whenever an `ACCOUNT_MAPPING` is created or moved (`UC-009 A8`), or drop the redundant `first_sent_at is filled` conjunct from the guard condition in `entity_model.md`, `ADR-002 O-13`, and `ADR-007 §9` and check directly whether a non-`Rejected` `SENT_RECORD` exists for `(bank_account_id, budget_account_id)`.

---

### E2E-04 — Medium: Incomplete propagation of the `Uploading` (`Upload is being written`) pause to `UC-001`, `UC-002`, `UC-003`, `UC-011`, and `FR-036`

- **Severity:** Medium (cross-specification inconsistency between `UC-006 BR-005` / `ADR-007 §2` and the Preconditions of four Use Cases plus `FR-036`)
- **Locations:**
  - `docs/use_cases/UC-006-upload-to-the-budget-app.md` `BR-005` (lines 187–189)
  - `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §2 State table (lines 89–101)
  - `docs/use_cases/UC-001-fetch-transactions.md` Preconditions (line 18) and `BR-013` (lines 277–279)
  - `docs/use_cases/UC-002-import-a-transaction-file.md` Preconditions (line 17)
  - `docs/use_cases/UC-003-compare-with-the-budget-app.md` Preconditions (line 18)
  - `docs/use_cases/UC-011-export-the-decision-journal.md` Preconditions (lines 15–18) and step 3 (line 23)
  - `docs/requirements.md` `FR-036` (line 45)
- **Evidence:**
  In commit `a819919` (`S-27`, `O-P2-36`), `UC-006 BR-005` (line 189) was updated to pause operations not only while an Upload Plan awaits confirmation, but also while a confirmed Upload is being written:
  > *"While an Upload Plan exists, until the Operator dismisses it or, once confirmed, until its Upload is written, also after the Operator has left its screen (BR-019), Slussen pauses fetching, file imports, reading the Budget App and all setup (Account Mappings, Bank Consents, Operator Settings, service credentials and connecting Budgets and Bank Accounts)."*
  The Preconditions of `UC-007`, `UC-009`, `UC-010`, `UC-013`, and `UC-014` were updated accordingly (*"No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005)."*), as was `ADR-007` §2 (lines 89–101). However, five places were left with the older wording or no Precondition:
  1. `UC-001` Precondition (line 18): `- No Upload Plan is awaiting confirmation (UC-006 BR-005).` And `UC-001 BR-013` (line 279): `A Scheduled Fetch that falls due while an Upload Plan awaits confirmation starts when that pause ends (UC-006 BR-005).`
  2. `UC-002` Precondition (line 17): `- No Upload Plan is awaiting confirmation (UC-006 BR-005).`
  3. `UC-003` Precondition (line 18): `- No Upload Plan is awaiting confirmation (UC-006 BR-005).`
  4. `UC-011` (`Export the Decision Journal`, lines 15–18) has **no** Precondition referencing `UC-006 BR-005`, even though `UC-011` step 3 reads the Budget App and `ADR-007` §2 (line 95) explicitly specifies that `Journal Export (UC-011)` is **refused** in both `Plan Open` and `Uploading`.
  5. `FR-036` (`docs/requirements.md` line 45) still reads: *"The system shall pause fetching, file imports, reading the Budget App, and changing Account Mappings, Bank Consents or Operator Settings while an Upload Plan waits for my confirmation."*
- **Fix:** Update the Preconditions of `UC-001`, `UC-002`, `UC-003`, and `UC-011`, `UC-001 BR-013`, `UC-006 BR-005` (to mention `UC-011`), and `FR-036` so all of them state *"while an Upload Plan is awaiting confirmation or an Upload is being written"*.

---

### E2E-05 — Medium: Balance-only Data Provider linkage on a File-Format `BANK_ACCOUNT` is underspecified across `UC-001` step 3, `entity_model.md`, and `ADR-002 O-15`

- **Severity:** Medium (gap in how a file-imported Bank Account identifies which Offered Bank Account of `balance_data_provider_id` supplies its balance and how `UC-001` step 3 selects it)
- **Locations:**
  - `docs/use_cases/UC-001-fetch-transactions.md` step 3 (line 24), step 4 (line 25), `BR-011` (lines 269–271)
  - `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` `A4` (lines 59–65), `BR-006` (lines 128–130)
  - `docs/entity_model.md` `BANK_ACCOUNT` (lines 173–191)
  - `docs/adr/ADR-001-module-boundaries.md` §4.1 `FetchRequest` (lines 269–272)
  - `docs/adr/ADR-002-persistence-with-room.md` §3 `O-15` (line 228)
- **Evidence:**
  1. Under `UC-013 A4` (lines 62–64) and `setup.html#file-account` (line 100: `Balance from: SimpleFIN · HSA ••••0907`), a file-imported Bank Account has `file_format_id` filled, `data_provider_id` empty, and `balance_data_provider_id` optionally pointing to a `DATA_PROVIDER` along with one of that Data Provider's `OFFERED_BANK_ACCOUNT`s.
  2. In `docs/entity_model.md` (`BANK_ACCOUNT`, lines 178–190):
     - `provider_account_id` is defined as *"How the Source identifies the Bank Account; empty until identified"* (line 178).
     - `identification_status` is defined as *"Identified, Probable Match, or Unidentified; always Identified for a File Format"* (line 180).
     Because a file-imported Bank Account's Source is its `FILE_FORMAT` (which needs no `provider_account_id`), `entity_model.md` does not state that `provider_account_id` stores the `balance_data_provider_id`'s account identifier when `balance_data_provider_id` is set—nor what happens if that Offered Bank Account later becomes a `Probable Match` or `Unidentified` on a Fetch when `identification_status` is constrained to be *"always Identified for a File Format"*.
  3. In `UC-001` step 3 (line 24), Slussen selects:
     > *"every Bank Account the Operator added for the Data Provider (UC-013) that has an Account Mapping, is not a Retired Account and whose Bank has a valid Bank Consent when the Data Provider needs one."*
     And step 4 (line 25) asks *"for the transactions and the balance of each selected Bank Account"*. While `UC-001 BR-011` (line 271) and `ADR-001` §4.1 (`what to read: Both or Balance`, line 271) mention balance-only reading, `UC-001` steps 3–4 do not include file-imported Bank Accounts whose `balance_data_provider_id` is that Data Provider (for balance-only reading).
  4. In `ADR-002` §3 `O-15` (line 228), `ConnectionStore.addBankAccount` describes adding a Bank Account from *"an Offered Bank Account ... (or a File Format and Bank, UC-013 A4, UC-002 A7)"*, omitting the optional `balance_data_provider_id` and its chosen `OFFERED_BANK_ACCOUNT`.
- **Fix:** Clarify in `UC-001` steps 3–4 that a Fetch also selects non-retired mapped Bank Accounts whose `balance_data_provider_id` is that Data Provider (asking only for their balance, `BR-011`), and state in `entity_model.md` (`BANK_ACCOUNT`) and `ADR-002 O-15` how the chosen Offered Bank Account of `balance_data_provider_id` is stored on `BANK_ACCOUNT`.

---

### E2E-06 — Low: Minor mockup link targets in `connections.html` and default count label in `settings.html`

- **Severity:** Low (mockup polish only; does not affect architecture or specification logic)
- **Locations:**
  - `docs/mockups/connections.html#enable-banking-remove` (line 420) and `docs/mockups/connections.html#ynab-remove` (line 431)
  - `docs/mockups/settings.html#list-2` (line 311) and `docs/mockups/settings.html#journal-near` (line 371) vs `docs/use_cases/UC-010-adjust-settings.md` `BR-007` (lines 161–169)
- **Evidence:**
  1. In `connections.html#enable-banking-remove` (line 420) and `connections.html#ynab-remove` (line 431), the `Remove` button inside the confirmation dialog links to `href="setup.html#removed"`, which is **SimpleFIN's** service page (`‹ SimpleFIN`, `Data Provider`). Although the step caption above each frame notes `"as drawn for SimpleFIN in removed"`, clicking `Remove` inside the Enable Banking or YNAB dialog jumps to SimpleFIN's screen.
  2. In `settings.html#list-2` (line 311) and `settings.html#journal-near` (line 371), the row for `Card Payment Descriptions` says `"Entries: 10 · defaults ship with the build"`, whereas `UC-010 BR-007` (lines 161–169) and `transfer-descriptions.html#list` define **9** default Card Payment Descriptions (10 only after the Operator adds a custom entry in `transfer-descriptions.html#edit`).
- **Fix:** Point `connections.html#enable-banking-remove` and `#ynab-remove` to their respective service frames (or add a note on the button), and change `Entries: 10 · defaults ship with the build` to `Entries: 9 · defaults ship with the build` in `settings.html#list-2` and `#journal-near`.

---

## 5. Summary Count & Verdict for Phase 6 / Phase 7

| Severity | Count | Findings |
|---|---:|---|
| **High** | 1 | `E2E-01` (First-run Fetch starts Fetch Cooldown with 0 stored transactions, blocking post-pairing Fetch) |
| **Medium** | 4 | `E2E-02` (`ADR-002 O-13/O-15` & `ADR-007 §9` trail `S-27`), `E2E-03` (`first_sent_at` guard bypass after Remove + Map again), `E2E-04` (`Uploading` pause missing in `UC-001/002/003/011` Preconditions & `FR-036`), `E2E-05` (balance-only Data Provider linkage on File-Format `BANK_ACCOUNT`) |
| **Low** | 1 | `E2E-06` (`connections.html` remove links & `settings.html` default count label) |
| **Total** | **6** | |

### Verdict
- **Phase 6 (`build: phase 6 - module skeleton`):** **Ready to start immediately.** None of `E2E-01..E2E-06` affect the Gradle multi-module layout, dependency graph, build logic, or Detekt ` ForbiddenImport` rules in `ADR-001`, `ADR-004`, and `ADR-005`.
- **Phase 7 (`feat: phase 7 - set up an own build and check import status`, implementing `UC-012 + UC-014 + UC-013 + UC-008` and the Room schema in `ADR-002`):** **Resolve `E2E-01`..`E2E-05` before writing Phase 7 domain/persistence code.** In particular, `E2E-01` (first-run `UC-013` → `UC-001` cooldown interaction) and `E2E-05` (`BANK_ACCOUNT` balance-provider identifier in `UC-013 A4` / `ADR-002 O-15`) directly touch the Phase 7 vertical slice (`UC-013` and the Room schema), and `E2E-02`..`E2E-04` are small, surgical specification/ADR alignments that keep `docs/` and `docs/adr/` 100% synchronized before code generation begins.
