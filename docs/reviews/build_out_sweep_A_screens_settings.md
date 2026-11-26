# Area A: screens and settings for what exists; UC-008, UC-010, UC-011; issues #26, #33

Surveyed on branch `build-out` at 24235f0 (2026-11-26), read-only. Sources read: AGENTS.md; UC-008, UC-010, UC-011 in full;
entity model (BUDGET_APP_TRANSACTION, BANK_TRANSACTION, UNREADABLE_TRANSACTION, DECISION_JOURNAL_ENTRY, OPERATOR_SETTINGS,
TRANSFER_DESCRIPTION); ADR-001 §3 and §7 rows UC-008/010/011; ADR-002 §3 (O-1..O-16), §5, §8, §9, §10; plan §0, §1, §3,
§6 (S-T-nn, O-T-09..12, UX-nn, UX-D1..D4, Phase notes); the text of every state of settings.html, transfer-descriptions.html,
journal-export.html, and the `-this-build` and permanent new states of overview.html, budget.html, bank-account.html,
transfers.html, fetch-history.html (extracted from the HTML, so layout and colours were not seen). Code: all of
`app/src/main/.../ui`, `core/.../status`, `settings`, `journal`, `firstupload`, the ports, `persistence/.../journal`,
`SlussenStorage`, `SlussenDatabases`, and the `@UseCase` annotations of all tests.

Not checked: GitHub issues #26, #27, #33 (no access; read from the plan and the code only); the full-product
states of budget.html/bank-account.html/transfers.html in detail (only those this area needs); behaviour on a device;
whether the gate currently passes.

---

## 1. What exists (to know what is unbuilt)

| Thing | State in code |
|---|---|
| Overview (`OverviewViewModel/Screen`) | Steps 1-3, A1 notice, A7 warning, A8 banner, A10 first-run card, Fetch all with states. No "Read YNAB again", no "statuses as of", no not-compared-yet, no per-Budget read failure line. |
| Budget tab (`BudgetViewModel/Screen`) | Sources and Bank Account cards with health, freshness, counts, balance line "Read x / y" (the UX-08 wording the UX gate rejected), Blocked Reasons with remedy. No Bank Transaction list, no tap into a Bank Account, no Transfers entry, no YNAB line, no Fetch on the tab, no pull to refresh. |
| Bank Account screen (`bank-account.html`) | **Does not exist** (no screen, no route in `SlussenActivity`). |
| Transfers screen (`transfers.html`) | **Does not exist.** |
| Recent Fetches | One text line per Fetch (Source · time · started by · outcome). No per-Bank Account lines, no detail, no "still needed now". |
| Core read model | `ImportStatus` gives counts only. `FetchStore.bankTransactionsOf`, `unreadableTransactionsOf`, `BudgetAppCopyStore.budgetAppTransactionsOf` exist but no status read model of transactions, reasons or Transfers for display. `Comparison.readAgain()` exists and is in `UiServices`, **unused by any ViewModel**. |
| Settings (`SettingsViewModel/Screen`) | Service entries, Account Mappings entry, Recent Fetches, fetch period editor (full NFR-023 states), 7 other settings shown read-only, version. Missing rows: file upload window, Request Limit warning pause, Upload Plan lifetime, Consent Request lifetime, outdated-balance limit, consent warning lead time, backup warning threshold, Decision Journal (on/off, size limit with current size, warning threshold, Clear), Detailed Logging, the two description lists, Export the Decision Journal entry, Start Over. |
| `OperatorSettingsStore` | `current()` and `storeFetchPeriod()` only. All columns exist in `operator_settings` (no schema change needed for editors). |
| `AllowedRanges` | Fetch period and Fetch Cooldown only. |
| Service pages | Fetch Cooldown and Request Limit editors with reset (A10, A11) built. Upload as Cleared shown as static text "On by default · changing it comes with Adjust Settings" (not the stored value, no switch: A9 unbuilt). |
| Description lists | `TransferDescriptions.matches` (pure), DAO `transferDescriptions()` read-only; defaults inserted by migration. No add/change/remove/restore. |
| Decision Journal | `DecisionJournalRecorder` write-only; DAO has `insert` and `count` only; `journal.db` has `auto_vacuum = INCREMENTAL`, but **no trimming to the size limit after writes** (ADR-002 §9 not built), no `DecisionJournalControl`, no `DecisionJournalReader`, no `JournalExport`. K-07 Konsist rule exists and passes vacuously. |
| Detailed Logging | `SlussenApplication` wires `LogcatLog(detailedLogging = { false })` hard-coded. |
| First Upload Matching A9 | `FirstUploadMatching.COMPARISON_BUILT = false`; `start()` always returns `ComparisonNotAvailable`; `recovery_check.compared_at` is never written (UC-003 BR-019 not built); O-12 `confirmChecks` not built. |
| Start Over (UC-010 A12, FR-107) | Nothing: no code, **no mockup state anywhere**. |
| Tests | No test names UC-011. UC-010 tests cover fetch period, Fetch Cooldown, Request Limit, BR-006/010/011. UC-008 tests cover A1, A2-A4, A5, A6, A7, A8, A10, BR-001..BR-011 for counts and blocked states. |

### Stale build-state texts that are now false (live defect)
- `Texts.TRANSACTIONS_NOT_IN_THIS_BUILD` (Budget tab): "not compared with the Budget App in this build yet ... counts stay at 0". Since 7a/7b the Fetch runs the Comparison (`FetchTransactions` line 114) and counts are real.
- `Texts.FETCH_IN_THIS_BUILD` (Overview): "it does not compare them with the Budget App yet". False since 7a.
- `BudgetViewModel.action`: StartFirstUploadMatching note "Comparing with the Budget App is not in this build yet". False since 7a (only BR-019 and A9 are missing).
- `Texts.firstUploadMatching.buildNote`: "Matching with YNAB comes in a later version" – still true in effect, but the `overview.html#open-this-build` wording is "First Upload Matching comes with Uploads in a later version... no action is needed now".

---

## 2. UNBUILT per use case

### UC-008 Check Import Status
- Step 5 (only that Budget's Bank Transactions with their Transaction Statuses): the whole transaction view. Mockup states: `budget.html#household-this-build`, `#empty-this-build`, `#first-read-failed`; `bank-account.html#checking-this-build`, `#statuses-this-build`, `#tx-match-this-build`, `#tx-possible-import-this-build`, `#tx-unreadable-this-build`, `#home-card-this-build`, `#rewards-this-build`, `#reserve-this-build`, `#balance-other-currency`, `#fetched-empty`; `transfers.html#list-this-build`, `#selected-this-build`, `#lonely-this-build`, `#no-transfers` (and `#doubtful-leg`, which has no this-build state, plan §6 Phase 2b note).
- Step 7 wording (UX-08, UX-25): "Bank balance … as of <reported time>" vs "Budget Account balance … read <time>"; outdated line with its remedy ("Fetch again later … nothing else is needed", UX-24 accepted part).
- UC-003 A1 shown on UC-008 screens: "statuses as of <last successful read>", "read failed", not compared yet counts per Bank Account (S-T-08), `overview.html#read-one-failed`.
- FR-040 / UC-003 trigger: "⟳ Read YNAB again" on the YNAB line of the Overview and every Budget tab with running/result/failed at its line (NFR-023, UX-12). Core `Comparison.readAgain()` exists.
- O-T-11: A5's reason "its other side did not arrive in time" derived at display.
- A5 / BR-008: per-Bank Account outcome lines, `fetch-history.html#fetch-detail`, `#fetch-detail-cooldown`, `#empty` ("No Fetches yet" with the action), "still needed now / nothing needed now".
- A9 (with UC-003 BR-019, O-12): run Comparison when none since opening, show result, confirm, close every open one, journal it.
- A1 / A7 notices: next action named (#26, UX-24) – see §5 questions.
- Budget tab Fetch and pull to refresh (`budget.html#ptr-progress`, `#ptr-result`, `bank-account.html#checking-ptr-*`): the core has `fetch(service)` and `fetchAll()`, no "Data Providers with a Bank Account in one Budget" (UC-001 trigger). Cross-area with UC-001.
- `budget.html#many-tab`, `#many-switcher`, `#many-overview` (4+ Budgets): one tab per Budget today.

### UC-010 Adjust Settings
- Steps 1-4, 6 and A1 for every scalar Operator Setting other than fetch period / Fetch Cooldown / Request Limit (14 settings, ranges of BR-002). Generic editor with the fetch period's NFR-023 states.
- Step 5 (re-status without contacting anything) for Upload Window, file upload window, both description lists, transfer pairing days, Lonely Transfer grace period. Needs a public core operation "conclude every Budget from the stored copy" under the coordinator (today `Comparison.conclude` is private; `recomputePending` is the nearest).
- A2 (lower journal limit below current size, confirm), A3 (clear, recorded as first new entry), A4 (journal near limit warning), BR-003 (trim to limit: ADR-002 §9), BR-004.
- A5 (Detailed Logging statement; log adapter must read the stored value; BR-005).
- A6 (Fetch Schedule statement; worker reschedule belongs with Scheduled Fetch, ADR-001 §7 row UC-010).
- A7, A8, BR-007..BR-009 (description lists editor and restore, re-detection), `transfer-descriptions.html#lists`, `#edit`, `#restore`.
- A9 (Upload as Cleared switch on the Budget App page, shown only when `supportsClearedMarker`).
- A12 Start Over (FR-107): unbuilt and without a mockup.
- Precondition (no Upload Plan awaiting / no Upload being written): already via `OperationCoordinator`; `settings.html#upload-running` state for the Settings page.
- Settings structure of `#list` / `#list-2`: sections Fetching, Upload, Transfers, Status and warnings, Decision Journal and logs; "Export the Decision Journal" entry under "Fetches and the Decision Journal".

### UC-011 Export the Decision Journal
- Everything: steps 1-5, A1-A3, BR-002, BR-003, BR-005, BR-006; precondition (at least one entry, `journal-export.html#empty`). Ports `DecisionJournalReader` (core `JournalExport` only, K-07), YNAB read of current transactions, SAF save dialog in `:app`.
- BR-001 recording: built for what exists (sessions, settings, Account Mapping changes, statuses, transactions seen/changed). Not yet: decisions, Upload Plan, Upload, Budget App outcomes (other areas).

### Defects
- #33: `ServicePageViewModel.switchImport`, `setReceives` (`applyReceives`) and `confirmRemoval` show no pending state at their control, answer a refusal in the page-level `message` (not at the control), and have no failure answer: a storage exception escapes `viewModelScope.launch` and crashes the app. `RemovalOutcome` has no NotStored variant. Also covers the description-lists row of `settings.html#list-this-build` (plan §6 Phase 6b note).
- #26: `OverviewViewModel.show`: unreadable notice "Stored data could not be read: X · started from its last good copy / started empty" and the backup warning name no next action (NFR-023).

---

## 3. Proposed phases

| # | Name | Builds | Modules | Depends on | Size | Schema |
|---|---|---|---|---|---|---|
| A-1 | Truthful build state and the YNAB line | Remove the false texts above; "⟳ Read YNAB again" on Overview and Budget tabs with NFR-023 states; statuses-as-of, read-failed, not-compared-yet counts (UC-003 A1, S-T-08); balance wording "as of"/"read" (UX-08); outdated remedy line | `:core` status (add last-read and not-compared counts to the read model), `:app` | 7a, 7b | M | No |
| A-2 | Bank Account screen | Route from Budget tab; chronological list newest first with week headings, status + reason per row (incl. O-T-11 derived), read-only detail sheets (match, possible import, unreadable), "What the statuses mean", fetched-empty, A21 notice, Funded By / Transfer Partner "not set" lines | `:core` status (new read model `BankAccountTransactions`, reasons derived by calling `comparison`/`transfer` pure rules), `:app` | A-1 | L | No |
| A-3 | Transfers screen (read-only) | Transfers of a Budget with both legs, guessed reason and alternatives, lonely "expected by", no-transfers, entry counts that reconcile (UX-27) | `:core` transfer/status read model, `:app` | 7b, A-2 | M | No |
| A-4 | Recent Fetches detail | Per-Bank Account outcome lines, detail screen, "still needed now", empty state | `:core` status, `:app` | — | S | No |
| A-5 | #33 and #26 | Pending/failed at import switch, Receives switch, credential removal; failure caught; notices with next action (needs spec text first, Change path) | `:core` connection/credential (NotStored outcomes), `:app`, `docs/` for #26 | — | S | No |
| A-6 | General Operator Settings editors | Every scalar setting with range/default/editor (UC-010 1-4, 6, A1), Settings sections per `#list`/`#list-2`, A5 Detailed Logging (cached flag read by `LogcatLog`), A6 statement, A9 Upload as Cleared switch, step 5 re-status engine (public stored-copy conclusion through the coordinator, journaled) | `:core` settings, comparison, `:persistence` (`SettingsStore` update methods), `:app` | 7a, 7b | L | No |
| A-7 | Description lists | `transfer-descriptions.html` A7, A8, BR-007..BR-009; validation without case/accents; journal before/after; re-detection via A-6's engine | `:core` settings/transfer, `:persistence` (O-13 `SettingsStore.replaceDescriptions` in one transaction), `:app` | A-6 | M | No |
| A-8 | Decision Journal control | ADR-002 §9 trimming after writes, `DecisionJournalControl` (current size, limit, clear), A2, A3, A4, on/off | `:core` journal, `:persistence` journal, `:app` | A-6 | M | No (journal.db v1 suffices) |
| A-9 | Journal Export | UC-011 all; `DecisionJournalReader` (streamed), `JournalExport`, YNAB read without storing (or per Q3), A2 stored-copy fallback with each Budget's `last_read_at`, codes, SAF document, delete on failure | `:core` journal, `:persistence`, `:app`, `:budgetapp-ynab` only if a new read is needed (Q3) | A-8 | M | No |
| A-10 | First Upload Matching confirmation | UC-003 BR-019 (`compared_at` in O-5), O-12 `confirmChecks`, UC-008 A9 steps 1-5, `first-upload-matching.html` | `:core` firstupload/comparison, `:persistence`, `:app` | 7a; must precede the first Upload | M | No |
| A-11 | Start Over | UC-010 A12, FR-107; needs a mockup state and an ADR-002 operation (delete across slussen.db, device.db, journal.db, Keystore key, last good copy) first | `docs/`, `:core` settings, `:persistence`, `:app` | A-8; after Q5 | M | No (but new atomic op in ADR-002 §3) |

Order: A-1 first (screens currently tell the Operator false things). A-5 and A-4 are independent. A-6 before A-7. A-8 before A-9. A-10 belongs with or just before the Upload area.

---

## 4. Operator questions

1. **`-this-build` states (issue #27).** The same run will build Review Decisions, Transfer Decisions and Uploads. Build A-1..A-3 against the 18 `-this-build` states (read-only, "comes in a later version") and remove them later, or build the permanent states directly and let the decision/upload phases add their controls? Options: (a) build this-build first, remove per #27; (b) skip this-build, ship only after the decision/upload phases; (c) build permanent layout with read-only rows, no "later version" lines, decisions slot in. Recommend (c): no throwaway text, and no build of the run ships with false "later version" promises. Redo if wrong: if a release must ship between phases, the build-state lines (plan §2 rule 4) have to be added back (S per screen).
2. **Journal Export file format.** The spec names contents, not a format. Options: one JSON file (header with source and read times, `entries[]`, `budgetAppTransactions[]`); a ZIP of two CSV files plus a header file; one CSV with a record-type column. Recommend JSON (one file, nested times, lossless for `details`). Redo: only the writer and its tests (S).
3. **Which Budget App transactions go into the export, and does the read update Slussen's copy?** Options: (a) the Budget App Read Period only (UC-003 BR-016); (b) from the oldest journal entry's date (minus the Matching Date Window) to today, read fresh and not stored; (c) the whole history of each Budget. Recommend (b), not stored (so the export never changes statuses, BR-002). Redo: the read call and maybe a new `BudgetAppReader` method or `readChanges(since=null, from=…)` use (S-M).
4. **Backup size warning: what is the next action (#26), and are old Bank Transactions ever removed?** Nothing in the spec deletes Bank Transactions or Budget App copy records, so `slussen.db` only grows toward Android's 25 MB limit (C-015). Options: (a) warning says no action is possible, backups stop above the limit, First Upload Matching protects after a restore; (b) new requirement: an Operator Setting "keep Bank Transactions for N days" with housekeeping of uploaded/old ones; (c) advise Start Over. Recommend (a) now plus (b) as a new requirement for later. Redo if (b) now: new requirement, UC changes, `OPERATOR_SETTINGS` column, schema version 6 (M).
5. **Start Over (FR-107, Priority Low).** Build it in this run? And: should it contact anything (revoke Bank Consents at Enable Banking, O-10) or delete only on the device? Does it delete the last good copy and the kept unreadable originals? No mockup exists. Recommend: build it late; device-only, no network; delete last good copy and unreadable originals too ("everything it stores"); mockup state drawn first. Redo: add revoke calls (S) or keep originals (S).
6. **Damaged-data notices (#26): what to say for each part.** Proposed: Decision Journal – "started empty; the unreadable original is kept on this device; nothing else is affected; no action is needed"; credentials/Bank Consents part – "enter the credentials again and grant Bank Consents again" with the service pages as actions; slussen.db – First Upload Matching banner is the action. Recommend these. Note `overview.html#damaged` says the Decision Journal "Started from its last good copy", which ADR-002 §8.5 forbids (journal.db has no last good copy). Redo: text only (S).

---

## 5. Safe guesses

1. Where a mockup disagrees with a use case, the use case wins (every mockup's footer). So: Fetch Schedule range is 0-2 with default 2 (`settings.html#list` hint "1 or 0 (off)" and `#fetch-schedule` "2 · default 1" are wrong); Bank Transfer Descriptions has 7 default entries, not 8 (`#list-2` predates S-T-14).
2. Journal "current size" = `page_count × page_size` of `journal.db` (ADR-002 §9), shown in MB with one decimal.
3. "Added by you" in `transfer-descriptions.html#lists` is derived by comparing each entry with the shipped default list (no attribute in `TRANSFER_DESCRIPTION`).
4. Re-status (UC-010 step 5) concludes every Budget from the stored copy through the coordinator; a Bank Transaction not compared yet (S-T-08) stays not compared yet; a Transfer with a decision other than Proposed is untouched; status changes are journaled as a Comparison's are.
5. A lowered "number of recent Fetches kept" trims at the next Fetch (ADR-002 §10); the list shows at most N immediately.
6. The Bank Account list shows every stored Bank Transaction, newest first, and its header names the dates the list actually covers (not "the fetch period", which the stored list can exceed). Week headings follow the device locale's first day of week.
7. "Statuses as of" is the Budget's `last_read_at`; reasons, a Match For Typed Entry's candidate, a Guessed Transfer's alternatives and O-T-11 are derived at display time by the same pure rule functions the Comparison uses, never stored.
8. Journal Export codes: one map per export for payee and memo together, sequential codes (`T0001`…), never a hash (so two exports cannot be linked, BR-005). Bank Account and Budget Account names are not coded.
9. A failed write of any setting, switch or removal answers "not stored / not removed" at its control, as the fetch period does (Phase 6b note; UC-010 failure postconditions), and catches the exception instead of crashing.
10. Budget switcher (`budget.html#many-*`): switch to the three-item bar only when the Budget tabs no longer fit the navigation bar's width; "four or more" is not written as a Slussen-chosen fixed number (AGENTS.md project rule).

---

## 6. Risks

1. **False build-state lines are on screen now** (see §1). Any phase that does not start by removing them keeps telling the Operator that nothing is compared while statuses and counts are real.
2. **Display reasons can disagree with stored statuses.** A read model that re-implements "why" (O-T-11, guessed alternatives, matched Typed Entry, Too Old boundary) instead of calling the Comparison's own pure rules will drift silently. Tests must assert that the derived reason agrees with the stored status for every status.
3. **Re-status from Settings is a second entry into O-5.** If it bypasses the coordinator or the comparison-pending marker, or treats a never-read Budget as read, it can invent statuses or Eligible Transactions; once Uploads exist that is a duplicate risk.
4. **Decision Journal has no size limit today.** Turning on trimming later deletes entries in bulk; an export running while trimming can produce an inconsistent file; a 50-500 MB export must be streamed (memory). SAF documents cannot be renamed atomically, so A3 needs delete-on-failure to keep "no incomplete file presented as complete".
5. **Uncaught exceptions in ViewModel coroutines** (`switchImport`, `applyReceives`, `confirmRemoval`): the same pattern copied into 14 new editors would crash the app on a storage failure rather than answer at the control (NFR-023, #33).
