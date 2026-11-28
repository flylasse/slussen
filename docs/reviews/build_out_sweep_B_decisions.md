# Area B: the Operator's decisions and the mappings they depend on

Survey of UC-004 (all), UC-005 (Transfer Decisions; detection is built), UC-003 (BR-008..BR-010, A4..A6 as far as
decisions go), UC-009 (what is not built). Read-only analysis of branch `build-out`, 2026-11-26.

## What I read

- AGENTS.md; docs/use_cases UC-003, UC-004, UC-005, UC-009 in full; entity_model.md (BANK_ACCOUNT, ACCOUNT_MAPPING,
  BANK_TRANSACTION, UNREADABLE_TRANSACTION, REVIEW_DECISION, TRANSFER, SENT_RECORD, TRANSFER_COUNTERPART,
  DECISION_JOURNAL_ENTRY, OPERATOR_SETTINGS, TRANSFER_DESCRIPTION, ERD); glossary entries and FR-022..FR-029,
  FR-048..FR-052, FR-089, FR-092; ADR-001 §7 rows UC-003/004/005/009; ADR-002 §2 (technical tables) and §3 (O-1..O-16);
  ADR-007 §1-§3a; transactions_plan.md §0, §1, §6 (O-T-01..O-T-12, S-T-18, S-T-20, S-T-21, Phase 7b/2b notes).
- Mockups (text extracted, all states): review-decision.html, transfers.html, transfer-budget-account.html,
  account-mappings.html.
- Code: core/comparison/{Comparison,ComparisonRules,BudgetAppCopy}.kt, core/transfer/TransferDetection.kt,
  core/mapping/AccountMappings.kt, core/port/{StoredTransfers,ConnectionStore,BudgetAppCopyStore}.kt,
  core/operation/OperationCoordinator.kt, core/connection/ConnectionModel.kt (AddedBankAccount),
  persistence/RoomComparisonStores.kt (grep), persistence schema 5.json (all tables), SlussenMigrations.kt (list),
  app/ui/** file list and mappings UI (grep).
- Markers: grep of `// UC-00[3459] BR-...` in production code and `@UseCase(id = "UC-00[3459]"...)` in tests.

Not checked: I did not run Gradle or any test; did not read UC-006, UC-008, UC-010, UC-011 in full (only where my
area points at them); did not verify YNAB behaviour (for example whether a transaction moved to another account
keeps its id); did not read every app screen.

## 1. Unbuilt

### Evidence of what exists

- No `@UseCase(id = "UC-004" ...)` test anywhere; the only UC-004 mention in production code is the Re-upload kind
  `U` in `core/identity/ImportIdentifierProtocol.kt` (Import Identifier protocol, no decision logic).
- Schema 5 has no `review_decision` table (tables: operator_settings, data_provider, file_format, bank, budget,
  budget_account, bank_account, account_mapping, left_out_bank_account, provider_request, fetch, fetch_result,
  back_off, account_balance, budget_app_transaction, transfer, bank_transaction, unreadable_transaction,
  transfer_description, recovery_check, coordinator_state, budget_read_state). No sent_record, upload_plan,
  eligible_transaction, upload_plan_item or transfer_counterpart table either (another analyst's area, but it decides
  how schema versions are cut).
- `transfer` exists with `decision` (Proposed only ever written) and `decided_at`; `outflow_budget_account_id` and
  `inflow_budget_account_id` Not Null (O-T-10). `bank_account.funded_by_budget_account_id` and
  `transfer_partner_budget_account_id` exist and are read by detection, never written except cleared by A14.
- `OperationKind` has no ReviewDecision or TransferDecision kind; ADR-007 §2 gives them their own admission row
  (admitted in Plan Open with the plan replaced; refused while Uploading).
- `ComparisonRules.conclude` skips every Bank Transaction whose Transfer has a decision other than Proposed
  (`filterNot { it.transferId in decided }`) and never produces Deferred, Decision No Longer Holds or Held Transfer.
  `ComparisonData` carries no Review Decisions.
- `EligibleTransactions.WAITING` lacks nothing for decisions but Deferred correctly is not in it.
- `AccountMappings` builds UC-009 step 1 (list in BR-014 order, without the Funded By/Transfer Partner, "no longer
  applies", "sent through", "identified" fields: `AddedBankAccount` has none of them), A14 and the read-only lists of
  A15/A16 (BR-013). A14 step 4's "detects the Transfers ... again" is not done (no Comparison is re-run after the type
  change): a defect against the Approved spec.
- `MappingStore` has only `changeTypeAndBank`.
- app: no Review Decision sheet, no Transfers screen, no Bank Account screen (plan §0: Phase 7c, the read-only screens,
  is still to come). Account Mappings screen: list, add/pair flow of UC-013, A14.

### UC-004 Review Doubtful Transactions: unbuilt in full

Main flow 1-8 (Already Present with the resembling Imports of UC-003 BR-007, storing the chosen Budget App
Transaction with Budget Account and amount, status, counts, Decision Journal), A1 Import Anyway, A2 Defer, A3 one
resembling transaction, A4 Needs Review / Transfer Counterpart Changed, A5 Re-upload (needs Sent Records to have an
effect), A6 change/clear, A7 locked (needs Sent Records), A8 replace a waiting Upload Plan (needs UC-006), A9
Unreadable Transaction Defer (`unreadable_transaction.status` Deferred), A10 Outcome Unknown decision (needs Sent
Records and a Budget App that does not refuse reused Import Identifiers; YNAB refuses, so only a fake can reach it),
A11 Transfer Leg. BR-001..BR-009 all.

### UC-005 Review Transfers: unbuilt

Steps 2-6 (select, show proposal and the other fetched Budget Accounts of the same Budget and currency, Confirm,
remove the Guessed flag, lower the count, journal), A1 Split, A2 Held (status Held Transfer for both), A3 choose
another Budget Account (ask Funded By / Transfer Partner, re-pair or Lonely Transfer, Budget Account Chosen, set the
Funded By/Transfer Partner, journal), A4 step 2-3 Confirm of a Lonely Transfer, A6 Transfer Counterpart Changed
(needs Transfer Counterparts, UC-006), A7 locked (needs Sent Records), A8 step 2 (the decision that completes a first
Card Payment, incl. the no-proposal case of O-T-10), A9 (decide the doubtful leg or Split), BR-007, BR-008, BR-009
(needs Uploads), BR-011, BR-013's Review Decision outcomes (rejoin, Already Present for the whole Transfer, Deferred
for the whole Transfer). Step 1's list exists in no screen yet (Phase 7c builds the read-only one).

### UC-003 parts that wait for decisions

- BR-001: the place of "one with a Review Decision follows that decision" in the order (after In The Future/Too Old,
  before the doubt rules) is not in `statusOf`.
- BR-009 / A4: recheck of Already Present against the stored copy (exists, same Budget Account, same amount) giving
  Decision No Longer Holds.
- BR-010: Deferred stays Deferred whatever happens to the resembling Import.
- BR-008: the status part is built; deciding on it is UC-004 A4.
- A5: status part effectively built (Too Old comes before the doubt rules and is kept by a Transfer Leg); the UI reason
  "can no longer be uploaded" belongs to the screens.
- A6 / BR-017: needs Sent Records (other analyst); the decision half is UC-004 A10.
- The decided-Transfer skip in `conclude` must be replaced by real handling of Confirmed, Split, Held and Budget
  Account Chosen Transfers (their legs still need the earlier rules: Already In Budget App, Too Old, doubt).

### UC-009 Manage Account Mappings: unbuilt

Step 1's missing fields (Funded By or Transfer Partner and "no longer applies", Start Date shown, sent-through,
identified); steps 2-7 (confirm a proposed match from stored Offered Bank Accounts, check not already used, record
identification with `identified_at`, journal); A1, A2, A3 (reject: Blocked Reason "unrecognised Bank Account"); A4
retire, A5 restore; A6 identify again, A7 identify again after sends (counts need Sent Records); A8 move / map again
(confirmation counts need Sent Records; `first_sent_at` reset); A9 Funded By / Transfer Partner (offered only from
fetched Budget Accounts, re-detect Transfers); A10 no Offered Bank Accounts; A11 remove; A12 rename; A13 Start Date
(re-status); A14 step 4 re-detection (defect); BR-003, BR-005, BR-007, BR-008, BR-010 (generated identifier exists).

## 2. Phases (proposal)

| # | Name | Builds | Modules | Depends on | Size | Schema |
|---|---|---|---|---|---|---|
| B0 | Spec settle | S-nn for the answers and safe guesses below: REVIEW_DECISION lock wording; TRANSFER side optional (O-T-10); A3 pairing rule; Manually Kept Accounts excluded from UC-009 A8; Split/decided Transfers and re-detection; UC-009 A14 mockup note | docs only | operator answers | S | no (designs v6) |
| B1 | Storage for decisions | `review_decision` table; `transfer` outflow/inflow side made Optional (table rebuild, O-T-10); Migration 5→6 hand-written; ports `ReviewStore`, `TransferStore`, more `MappingStore` ops (O-13); `ComparisonData` gets Review Decisions; `OperationKind.ReviewDecision`, `TransferDecision` with ADR-007 §2 admission | persistence, core/port, core/operation, testing (fakes) | B0; coordinate with the Upload analyst so that ONE version 6 adds every remaining table (sent_record, upload_plan, eligible_transaction, upload_plan_item, transfer_counterpart) or the versions are strictly sequenced | M | YES (v6) |
| B2 | Review Decisions in the core | UC-004 main, A1-A4, A6, A9, A11, BR-001, BR-002, BR-004..BR-007, BR-009; UC-003 BR-001 (decision step), BR-009/A4, BR-010; UC-005 BR-013 outcomes; O-13 `ReviewStore.decide` setting the other legs' statuses in the same transaction; journal "Operator Decision" with the proposed status; "status without a decision" recomputed for A6 choices and clearing | core (review, comparison), persistence, testing | B1 | L | no |
| B3 | Transfer Decisions in the core | UC-005 steps 2-6, A1, A2, A3 (with Funded By/Transfer Partner), A4 confirm, A8 completion (incl. no-proposal Guessed Transfer), A9, BR-007, BR-008 (without upload lock), BR-011; Comparison handling of decided Transfers instead of skipping them | core (transfer, comparison), persistence, testing | B1, B2 (BR-013 shared) | L | no |
| B4 | Mappings, simple changes | UC-009 step 1 fields, A4, A5, A9, A12, A13, A14 step 4 fix; re-status/re-detection from stored data after every change (no Budget App read) | core (mapping), persistence | B3 (re-detection must respect decisions) | M | no |
| B5 | Mappings, identification and moves | UC-009 steps 2-7, A1-A3, A6, A10, A8 map again / move, A11 remove, BR-003, BR-007 with counts that read 0 until Sent Records exist, BR-010 | core (mapping), persistence | B4; Sent Records for real counts | M-L | no (if sent_record comes with v6) |
| B6 | Screens | review-decision.html (#possible-import .. #locked), transfers.html (#list, #selected, #confirmed, #lonely, #split, #held, #first-card-payment, #doubtful-leg, #locked), transfer-budget-account.html (#choose, #lonely-result, #choose-pair, #paired, #partner), account-mappings.html (#sheet, #confirm, #confirmed, #already-used, #rejected, #retire, #restore, #move, #map-again, #move-confirm, #remove, #identify-again, #identify-after-sends, #identify-again-sent, #rename, #start-date, #funded-by, #no-offered); remove the `-this-build` states (plan §6, Phase 2b note) | app | B2-B5; Phase 7c (Budget tab, Bank Account screen, read-only Transfers) | L | no |
| B7 | Upload-dependent tails | UC-004 A5 effect (occurrence, U identifier), A7, A8, A10/BR-008; UC-005 A6, A7, BR-009; UC-009 A7/A8/A11 real counts; locks (UC-004 BR-004, UC-005 BR-008) | core, app | UC-006 phases | M | no |

B2 and the Upload analyst's Sent Record work both edit `ComparisonRules.statusOf/conclude` (the order of UC-003
BR-001): run them in sequence, not in parallel.

## 3. Operator questions

### Q1. Specification changes during the run
AGENTS.md §6 says a spec change (Change path) is approved by the operator before the code. The run has no stops.
- (a) Blanket approval: the run writes each S-nn for the safe guesses and questions answered here, applies it, and the
  operator reviews them all at the end (rejections become `spec-gap` issues);
- (b) the run stops at each spec change;
- (c) the run builds only what needs no spec change and leaves the rest as issues.
Recommend (a), limited to the readings listed in this questionnaire. Redo if different: every phase that rests on a
rejected S-nn (B1's schema shape at worst, which then needs another schema version).

### Q2. What happens to an existing Transfer Decision when its basis changes
Changing a Funded By / Transfer Partner (UC-009 A9, UC-005 A3), the description lists (UC-010), the Bank Account type
(A14), a Start Date, moving/removing an Account Mapping or retiring a Bank Account all say "detect the Transfers ...
again" for not-uploaded transactions. The spec does not say what happens to Confirmed, Split, Held or Budget Account
Chosen Transfers then; UC-005's postcondition says decisions are kept across later Comparisons.
- (a) Keep every decision; re-detect only Proposed Transfers;
- (b) keep decisions, except that a decided Transfer whose own sides are no longer valid (a leg's Bank Account moved to
  another Budget Account, unmapped or retired; the Transfer no longer a Card Payment after a type change) is dissolved,
  journaled, and its legs re-detected;
- (c) reset every not-uploaded decision touched by the change.
Recommend (b). Redo if different: B3/B4 rules and tests in `comparison`/`transfer` (M).

### Q3. A Lonely Transfer the Operator confirmed
After Confirm (A4) or Budget Account Chosen towards a Lonely side (A3 step 3), before upload:
(i) its grace period passes (BR-005 says it becomes an ordinary Bank Transaction);
(ii) its other side arrives.
- (a) The decision holds: BR-005 applies only to Proposed Transfers; an arriving other side joins the decided
  Transfer, which keeps its decision;
- (b) BR-005 applies to decided ones too (the decision is dropped, the transaction is uploaded as ordinary); an
  arriving other side resets the Transfer to Proposed.
Recommend (a): nothing the Operator decided changes without them. Redo: small (rule + tests in B3).

### Q4. A3 when the chosen Budget Account's matching Bank Transaction is already in another Transfer
A3 step 3 pairs only with a Bank Transaction that "belongs to no other Transfer", otherwise makes a Lonely Transfer
towards the chosen Budget Account. If the real other side sits in another (wrongly guessed) Transfer, the Lonely
Transfer writes a linked transfer into that Budget Account AND the other Transfer writes the same Bank Transaction
again: a duplicate in YNAB.
- (a) Literal spec (Lonely Transfer);
- (b) take that Bank Transaction when its Transfer is only Proposed (that Transfer is dissolved and its other leg
  re-detected); when its Transfer is decided, refuse that choice and say which Transfer holds it;
- (c) always refuse and say why.
Recommend (b). Redo: A3 rule in B3 (S-M), one mockup state.

## 4. Safe guesses

1. Lock (UC-004 BR-004, A7): REVIEW_DECISION says "locked once a Sent Record is Written", which would also lock the
   Re-upload of a Deleted In Budget App / Merged In Budget App transaction (it has a Written Sent Record). Read: locked
   while the transaction is Already In Budget App through its latest send (Written or Already Present); Outcome
   Unknown (A10) and Deleted/Merged (A5) stay decidable. Entity-model wording fixed in B0.
2. O-T-10: make one TRANSFER side Optional (filled except for a Guessed Transfer that proposes no Budget Account), so
   Split and Held can be stored for it (transfers.html#first-card-payment offers both); B1's v6 migration rebuilds the
   table.
3. Split is permanent until changed: the legs keep `transfer_id` to the Split record, are compared as ordinary and are
   never re-detected into another Transfer; the Split Transfer stays listed so the decision can be changed back
   (BR-008).
4. Decision No Longer Holds on a Transfer Leg holds its whole Transfer like Needs Review does (BR-013 lists only Needs
   Review and Possible Import By Other Path; `writesItsTransfer` already blocks it).
5. A Review Decision follows UC-003 BR-001 literally: a decided transaction that is Before Start Date, Too Old or In
   The Future shows that status; the stored decision is kept and shown on the sheet.
6. A6 choices and "clearing gives the status the Comparison gives without a decision" are computed by running the
   rules without the decision on stored data; no extra column.
7. UC-009 A8 targets exclude Manually Kept Accounts (UC-013 BR-014 "never paired"; account-mappings.html#move wrongly
   lists Old Savings, which #map shows as kept by hand). Fix the mockup in B0.
8. Moving a mapping into another Budget keeps the Bank Account's own Funded By/Transfer Partner, shown as no longer
   applying (as UC-005 BR-014 does for a lost mapping); detection ignores it already (`applies`).
9. "Detect again / status again" after UC-009 A8, A9, A13, A14 recomputes from the stored copy only (O-5, no Budget
   App read, as ADR-007 §3a); A3 setting a Funded By in Plan Open is allowed as part of the Transfer Decision, the
   plan being replaced (ADR-007 §2), although UC-009 changes are refused in Plan Open.
10. A3/A8 "Keep this answer?" preselection (the mockups disagree): preselect "Keep" when the Operator chooses
    Slussen's proposal or answers a first Card Payment, "Just this Transfer" when they override a proposal (fits
    #choose, #partner and #choose-pair). A Re-upload of a transaction never sent (Decision No Longer Holds, FR-028)
    uses occurrence 1 like any first Re-upload. Already Present is hidden when nothing resembles any more.

## 5. Risks

1. `ComparisonRules.conclude` skips the legs of every decided Transfer. If B3 forgets to replace this, a Confirmed or
   Held Transfer's legs never become Too Old, Already In Budget App or doubtful again; wrong statuses silently persist
   (and an uploaded leg might still look eligible).
2. Re-detection after Funded By / description / mapping changes interacts with O-5's "release every Proposed Transfer
   no detection continues" and with A3's pairing: without Q2/Q4 settled, one Bank Transaction can end up written
   twice (two Transfers, or a Lonely Transfer plus its real leg elsewhere).
3. Schema v6: making TRANSFER sides nullable needs a table rebuild while `bank_transaction.transfer_id` references it;
   foreign keys during a Room migration (which runs inside a transaction) are easy to get wrong, and the instrumented
   migration test runs only on a device (`device-evidence`), not in the autonomous run. Two analysts' tables in one
   version, or strictly sequenced versions, must be agreed before B1.
4. UC-003 BR-009 depends on the stored copy: `BudgetAppCopy.toStore` drops a read transaction whose Budget Account
   Slussen does not hold, so a chosen transaction moved in YNAB to such a Budget Account stays in the old one in the
   copy and the Already Present decision keeps holding (should be Decision No Longer Holds). Not verified whether YNAB
   keeps a transaction's id when it is moved between accounts.
5. B2 (decisions) and the Upload phases both edit the order of UC-003 BR-001 in `statusOf` and the Eligible rules;
   run in parallel they will conflict or drop a rule. Also the existing A14 does not re-detect Transfers (defect) and
   UC-004 A10 is unreachable with YNAB, so its tests need a fake Budget App that accepts reused Import Identifiers.
