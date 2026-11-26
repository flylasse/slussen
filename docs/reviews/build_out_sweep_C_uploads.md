# Area C: Uploads (UC-006, ADR-007, ADR-008, the Sent Record parts of UC-003/UC-005, the YNAB writer, evidence E-01..E-13)

Read-only survey of `/home/user/slussen`, branch `build-out` at `24235f0`. Nothing in the repository was changed.

## 0. What was read, and what was not

Read in full: `AGENTS.md`; UC-006; UC-003; UC-005 (flows and rules); UC-004 (flows and rules); ADR-007; ADR-008;
ADR-001 §3, §4.0, §4.2 (all), §4.4, §6.5, §7; ADR-002 §2, §2a, §3, §4, §5, §6, §7, §8, §9, §10, §11; ADR-005 §7;
ADR-003 §4; `docs/transactions_plan.md` §0, §1, §3, §5, §6; entity model `BANK_TRANSACTION` .. `FIRST_UPLOAD_MATCHING`,
`DECISION_JOURNAL_ENTRY`; the requirements UC-006 cites; the text of `upload-plan.html`, `upload-outcome.html`,
`first-upload-matching.html`; GitHub issue #6 (through the REST API).

Code read: `plugin-api/.../budgetapp/BudgetApp.kt`, `request/Request.kt`, the contract test fixtures (outline);
`budgetapp-ynab` (all four files); `core` `operation`, `comparison` (all), `transfer/TransferDetection.kt`,
`firstupload`, `identity/ImportIdentifierProtocol.kt` (head), `port/BudgetAppCopyStore.kt`, `StoredTransfers.kt`,
`StartState.kt`, `DecisionJournalRecorder.kt`; `persistence` `SlussenStorage.kt`, `RoomComparisonStores.kt`,
`start/StartSequence.kt`, the committed schema files (`slussen.db` 1..5, `device.db` 1, `journal.db` 1), grep of
`MainDaos.kt` and `RoomStores.kt`; `testing/FakePlugins.kt` (writer part); `app/di/CompositionRoot.kt`;
`architecture-tests` (grep for K-05, K-06, B-04, B-05, K-15, K-16); `build-logic` live-test convention.

Not checked:
- Any live behaviour of YNAB. Everything in §6 about YNAB's API is from memory of its public documentation and must be
  checked against the documentation or by a live test.
- Gradle, tests, an emulator or a phone were not run (the task forbids Gradle). "Exists" below means the source exists.
- UC-001, UC-002, UC-007, UC-009 (only the Sent Record passages), UC-010..UC-014, ADR-003, ADR-004, ADR-006,
  `docs/reviews/`, `docs/vision.md` were not read in full. The `:app` screens were only listed.
- GitHub issues other than #6.
- The sibling surveys in the same scratchpad folder were only skimmed (B-decisions: phases, questions, guesses).

## 1. What exists today (the starting point)

| Piece | State | Where |
|---|---|---|
| Budget App contract types for writing (`BudgetAppWriter.create`, `markCleared`, `CreateItem`, `CreateResult`, `ItemOutcome`, `ClearResult`), `readTransaction`, `TransactionLookup` | Declared, no implementation | `plugin-api/src/main/kotlin/slussen/pluginapi/budgetapp/BudgetApp.kt` |
| YNAB `writer` | `TODO(...)` getter | `budgetapp-ynab/.../YnabBudgetApp.kt:48` |
| YNAB `readTransaction` | `TODO(...)` | `budgetapp-ynab/.../YnabReader.kt:63` |
| YNAB `readBudgets`, `readChanges` | Built (Phase 7a) | `YnabReader.kt` |
| Contract tests for the Budget App | Reading only (F-3, F-4, F-8, length check). The fixture's own KDoc says F-1, F-2, F-5, F-7 "come with the slice that builds UC-006" | `plugin-api/src/testFixtures/.../ContractTests.kt:609-840` |
| Fake Budget App writer | `RecordingBudgetAppWriter` throws on every call; fake `readTransaction` throws | `testing/.../FakePlugins.kt` |
| `OperationCoordinator` | One lane; refuses everything in Plan Open and Uploading; no code ever sets those modes; no plan preparation, no origin `Schedule`, no deferred Scheduled Fetch, no waiting/withdraw, no restart handling beyond `Comparison.recomputePending()` | `core/.../operation/OperationCoordinator.kt` |
| `CoordinatorStore` | Only `mode()` | `core/.../port/StartState.kt` |
| `coordinator_state.state_version` | Raised only by O-1 (new or changed Bank Transactions) and by `storeComparisonPending` (O-4, O-5). Not raised by any mapping, settings, connection, description or decision operation | `persistence/.../main/MainDaos.kt:447, 555` |
| Import Identifier protocol, kinds R, F, U with vectors | Built (Phase 6a); kind U (Re-upload) is computed nowhere yet | `core/.../identity/ImportIdentifierProtocol.kt` |
| Comparison (O-4, O-5) | Built without Sent Records, Review Decisions, Transfer Counterparts, `compared_at` | `core/.../comparison/*`, `persistence/.../RoomComparisonStores.kt` |
| Start sequence, last good copy, start intent, First Upload Matching opened at start | Built, with JVM tests on a fake database check | `persistence/.../start/StartSequence.kt`, `StartSequenceTest.kt` |
| "Unreadable while running" (ADR-002 §8 step 4: write the start intent, restart the process; Room `onCorruption`) | Not built: `UnreadableStoredData` is thrown and nothing catches it | grep: no `onCorruption`, no restart |
| First Upload Matching: notice | Built. `start()` always answers `ComparisonNotAvailable`; `COMPARISON_BUILT = false`; no confirm | `core/.../firstupload/FirstUploadMatching.kt` |
| Architecture rules for the writer (K-05, K-06, B-04, B-05) | Exist and already name `slussen.core.upload.AdmittedBudgetAppWriter` | `architecture-tests/...` |
| Live-test plumbing (`-PincludeLive`, tag `live`, properties `slussenLiveTestYnabAccessToken`, `slussenLiveTestYnabBudgetId`) | Exists; no live test is written | `build-logic/.../Conventions.kt` |
| Operator Settings `upload_plan_minutes`, `upload_as_cleared` | Stored with defaults; the Upload as Cleared switch is drawn on the YNAB page | `OperatorSettingsStore.kt`, `ServicePageScreen.kt` |
| Decision Journal event kinds Upload Plan, Upload, Budget App Outcome | Declared, unused | `core/.../port/DecisionJournalRecorder.kt` |
| Tests marked `@UseCase(id = "UC-006")` or `"UC-004"` | None | grep over the repository |

Tables of `slussen.db` at schema version 5 (from `persistence/schemas/.../SlussenDatabase/5.json`):
`operator_settings`, `data_provider`, `file_format`, `bank`, `budget`, `budget_account`, `bank_account`,
`account_mapping` (with `first_sent_at`), `left_out_bank_account`, `provider_request`, `fetch`, `fetch_result`,
`back_off`, `account_balance`, `budget_app_transaction`, `transfer`, `bank_transaction`, `unreadable_transaction`,
`transfer_description`, `recovery_check`, `coordinator_state`, `budget_read_state`.

Missing from `slussen.db` against ADR-002 §2 and the entity model:
- Area C: `upload_plan`, `eligible_transaction`, `upload_plan_item` (technical), `sent_record`, `transfer_counterpart`.
- Other areas, needed by C: `review_decision` (Re-upload, Import Anyway on an unknown outcome, plan replaced on a
  decision). Also missing: `transaction_file`.
- `device.db` version 1 holds only `service_credential` and `offered_bank_account`; `bank_consent`, `bank_session`,
  `consent_request`, `consent_request_correlation` are missing (area E; ADR-007 §3 needs "no unanswered Consent
  Request" before a plan is prepared).

## 2. Unbuilt, in detail

### UC-006
Everything: steps 1-13; A1-A13; BR-001..BR-020. The only traces in production code:
- BR-005: existing operations ask the coordinator and are refused in Plan Open and Uploading (a state nothing can
  reach yet). Missing: setting the modes; Review and Transfer Decisions admitted in Plan Open and replacing the plan;
  confirm and cancel admitted "for that plan only"; a Scheduled Fetch deferred and started when the pause ends; the
  reads of the running Upload admitted in Uploading; "setup is paused until the Upload is written" on the screens.
- BR-009: the eligibility of the writing Transfer Leg (`EligibleTransactions.writesItsTransfer`).
- BR-017: the start sequence opens a First Upload Matching; the gate itself (A13) does not exist because no plan can
  be prepared.

### ADR-007
- §1 lane: waiting with "the Operator can withdraw the waiting request" is not built (the Mutex just queues); origin
  `Schedule` is rejected by a `check`, not by a typed refusal.
- §2 admission table: only "refuse everything" exists. §3 preparation conditions: none. §3a: the marker exists (O-4
  sets, O-5 clears, recompute at start); "no plan while pending" has nothing to block yet.
- §4 preparation and the retry table, capability fallbacks, date fixed at preparation, shortening: none.
- §5 replace, cancel, leave, expire: none. §6 steps 1-10: none. §7 `AdmittedBudgetAppWriter`, `WritePermit`,
  `UploadExecutor`: none (only the static rules that name them). §8 restart: Plan Open becomes Expired, Uploading
  turns Not Yet Known into Unknown, deferred Scheduled Fetch: none (the start only resets the mode when a First Upload
  Matching opens, `SlussenStorage.start`).
- §9 tests: none.

### ADR-008
- §1-§4 built and proven by vectors. Unbuilt: §5 Re-upload allocation (next occurrence, skipping an identifier already
  held by a Sent Record or by the stored copy, the used occurrence stored at confirmation); §6 the composition check of
  `importIdentifierMaxLength` with "every Upload refused and the import status shows why" at run time (the contract
  test `importIdentifierLengthCoversTheProtocol` exists).

### ADR-001 §4.2
- §4.2.1: F-1, F-2, F-5, F-7 have no contract test; F-3's `readTransaction` half is not built.
- §4.2.2: none of the three capability switches is read by the core for Uploads (`supportsClearedMarker` only decides
  whether the switch is drawn).
- §4.2.3: "Transfer Counterparts are reconciled from stored links" is not built (`entered_by` Transfer Counterpart is
  decided and stored by O-4; nothing uses it).
- §4.2.4: `create`, `markCleared`, the composition checks: none.

### ADR-002
- §3: O-6 `UploadStore.preparePlan`, O-7 `confirmPlan`, O-8 `recordOutcomes`, O-9 `finishUpload`, O-14
  `recordClearing`: none (no `UploadStore` port). O-12: `openCheck` is done inline by `SlussenStorage.start`;
  `confirmChecks` is missing. O-5 lacks: Sent Records settled, Transfer Counterparts reconciled, `compared_at`,
  the comparison against the Sent Record's Budget Account (BR-021). O-13 (decisions, mapping moves with the Sent Record
  guard) is area B.
- §3 rule "each operation that changes something an Upload Plan depends on raises `state_version`": only O-1, O-4, O-5
  do. Every existing mapping, connection, settings and description operation must be changed.
- §4: nothing. Durability settings (`TRUNCATE`, `synchronous = FULL`) are set (`SlussenDatabases.kt`).
- §6: built on files; steps "directory flushed" are not done (no directory sync in `StartSequence`); instrumented
  proof missing (E-12).
- §8: steps 1-3 and 5 built; step 4 (unreadable while running, `onCorruption`) not built.

### UC-003 parts in this area
- A6 and BR-017 (unknown outcome settled or kept; status Outcome Unknown).
- BR-019 (`recovery_check.compared_at` when every Budget with a mapped Bank Account was read).
- BR-021 (the Budget Account recorded in the Sent Record is compared too).
- BR-001, BR-003: "or the Import Identifier of a Re-upload of it"; BR-004, BR-005: "unless the Operator chose
  Re-upload"; BR-011: "a Re-upload is possible only inside the window".
- BR-014: "Outcome Unknown under the conditions of BR-017" as an Eligible Transaction.
- Step 6: "what the Budget App reported for each transaction Slussen sent" in the Decision Journal (event Budget App
  Outcome).
- BR-020's use: Transfer Counterparts from `transfer_link_reference` and `cleared`.

### UC-005 parts in this area
- A6, BR-009 (Transfer Counterpart Changed), A7 and BR-008's lock, the last sentence of BR-010 (Arrived As Transfer
  Counterpart), the settling of a counterpart (`settled_by_bank_transaction_id`).

### UC-008 parts tied to Uploads
- A9 steps 1-5 (start the Comparison, show the result, confirm, close, journal), the screens of
  `first-upload-matching.html` (#comparing, #confirm, #budget-app-unreadable, #closed); the Upload entry points
  ("Review Upload Plan · Eligible n"), `budget.html#nothing-to-upload`, `#recovery-blocked`.

### Mockups
- `upload-plan.html`: #awaiting, #counterparts, #bank-account-scope, #cleared-off, #decide-in-plan,
  #decide-transfer-in-plan, #rebuilt, #writing, #cancelled, #leave, #expired, #changed, #budget-app-unreadable,
  #sent-record-failed, #recovery. None built.
- `upload-outcome.html`: #written, #outcome-unknown, #rejected, #not-linked, #clear-failed, #compare-failed. None built.

## 3. Proposed phases (dependency order)

Schema numbering: `slussen.db` is at 5. Area B proposes version 6 for `review_decision` and an optional side of
`transfer`. Either one shared schema phase adds every remaining table in version 6 (preferred: one Migration, one
device run), or the Upload tables are version 7. Area C needs exactly one schema change.

| # | Name | Builds | Modules | Depends on | Size | Schema |
|---|---|---|---|---|---|---|
| C0 | Specification settle | The S-nn for the answers and safe guesses of §4 and §5: ADR-005 §7 (the gate wording), UC-003 BR-001 (where Arrived As Transfer Counterpart, Transfer Counterpart Changed and a late other side stand; a Sent Record that is Written while the copy lacks it), UC-005 (a Transfer is frozen once a Transfer Leg is sent; a Transfer Leg already written as ordinary), UC-004 (Re-upload once), ADR-001 §4.2.4 (mapping of YNAB's answers, the transfer payee, one-by-one after a refused batch), ADR-002 §2 (the header of `upload_plan_item`), UC-006 (read before preparing; background is not leaving), E-10's list | `docs/` | the operator's answers | S | none |
| C1 | Storage for Uploads | `upload_plan`, `eligible_transaction`, `upload_plan_item`, `sent_record` (unique index on `budget_account_id`, `import_identifier`), `transfer_counterpart`; hand-written Migration; schema file; JVM and instrumented migration tests; the new `Values:` columns in `SlussenStorage.MAIN_VALUES`; port types; in-memory fakes; `state_version` raised by every existing storage operation that changes what a plan depends on | `:persistence`, `:core` `port`, `:testing` | C0; agreed with B1 | M | version 6 shared, or 7 |
| C2 | YNAB writer and one-transaction read | `YnabWriter.create` (one request per Budget Account, transfer through the other account's transfer payee, never approved), `markCleared`, `readTransaction`; contract tests F-1, F-2, F-5, F-7, a duplicate answered `AlreadyPresent`, write timeout and cancellation (`Unknown` per item), a refusing device; a scriptable fake writer in `:testing`; the live tests of E-10, tagged `live`, written and never run by an agent | `:budgetapp-ynab`, `:plugin-api` test fixtures, `:testing` | C0 | M | none |
| C3 | Comparison with Sent Records | O-5 extended: BR-017 and A6, BR-021, the Re-upload identifiers in BR-001/BR-003..BR-005, Transfer Counterparts reconciled from stored links, statuses Outcome Unknown, Arrived As Transfer Counterpart, Transfer Counterpart Changed (UC-005 A6, BR-009), a Transfer frozen once sent, `compared_at` (BR-019), journal "Budget App Outcome"; persistence IT with planted Sent Records | `:core` `comparison`, `transfer`; `:persistence`; `:testing` | C1; after B2/B3 (both edit `ComparisonRules`), never in parallel with them | L | none |
| C4 | First Upload Matching completed | UC-008 A9 steps 1-5, O-12 `confirmChecks`, the gate value the coordinator reads; the screens of `first-upload-matching.html` | `:core` `firstupload`, `:persistence`, `:app` | C3 | S-M | none |
| C5a | Coordinator and preparation | Modes Plan Open and Uploading stored; the whole admission table of ADR-007 §2; §3 conditions; O-6 with the retry table, the capability fallbacks, eligibility fixed at the preparation date, payee and memo shortened, Re-upload occurrence allocation (ADR-008 §5); cancel, leave, expire, replace on a decision (§5); restart in Plan Open (§8); composition checks (ADR-008 §6); journal "Upload Plan" | `:core` `operation`, `upload`; `:persistence` (`UploadStore`); `:testing`; `:app` `di` | C1, C3, C4; B2/B3 for A3 | L | none |
| C5b | Confirmation, writing, clearing | ADR-007 §6 steps 1-10: the checks, O-7, `WritePermit`, `AdmittedBudgetAppWriter`, `UploadExecutor`, O-8, the clearing read and `markCleared`, O-14, the Comparison after, O-9; A5-A12; restart in Uploading; every test of ADR-007 §9 | same as C5a | C5a, C2 | L | none |
| C6 | Screens | `upload-plan.html` and `upload-outcome.html` (all states), the entry points on the Budget tab and the Bank Account screen, the pause texts, leaving as cancel, the outcome when the Operator comes back (BR-019); removal of the matching `-this-build` lines (issue #27) | `:app` | C5b; B6 for the decision sheets inside the plan | L | none |
| C7 | Evidence kit | Instrumented tests E-01..E-05 and E-12 (seams for a planted copy failure, a failure before each commit, a kill at each boundary), ADR-002 §8 step 4 (restart path, `onCorruption`), the migration IT for the new version (E-06), the phone procedures E-07 and E-09 and the live procedure E-10 written as one checklist with a result table, issue #6 updated | `:persistence` `androidTest`, `:app`, `docs/` | C5b | M | none |

C2 has no dependency on C1 and can run beside it. C7's tests can be compiled in the run
(`assembleDebugAndroidTest`) and cannot be executed in it.

## 4. Gaps, contradictions and decisions

### 4.1 Questions for the operator

Q1. **The evidence gate.** ADR-005 §7 says "no build that can upload is installed for real use until every item
below has passed". The instruction for this run is that Upload is not switched off and the operator runs the evidence
before his first real Upload. ADR-005 §7 has to say that.
- (a) The gate is the operator's own: the app shows nothing; a checklist in `docs/` and the plan's result table.
- (b) As (a), and the app shows one plain notice on the first Upload Plan until the operator marks the evidence done
  (a new Operator Setting, a schema column).
- Recommend (a). Redo if different: the ADR text, one notice, and for (b) a schema version.

Q2. **The throwaway YNAB Budget for E-10.** The live tests need a personal access token and a Budget that may be
filled with invented transactions, with at least three Budget Accounts in one currency (one of them a credit card).
Two items of E-10 need a hand in YNAB's own screens between two test stages (approving a match so that YNAB merges;
perhaps deleting one transaction), and one cannot be produced in a throwaway Budget at all: "YNAB's own Bank import
writes Import Identifiers that do not match Slussen's marker" needs a linked bank.
- (a) The operator creates the Budget and its accounts; the live test runs in stages and tells him what to do in YNAB
  between them; the last item is shown by a read-only live test against his real Budget that prints only the first
  characters of the Import Identifiers it finds.
- (b) As (a), but the last item is accepted from YNAB's documentation.
- (c) The live test creates the accounts itself through YNAB's interface (accounts created that way cannot be removed
  through it).
- Recommend (a). Redo if different: the live tests only.

Q3. **Read YNAB before the plan is prepared?** UC-006 step 2 prepares the plan from the statuses of the latest
Comparison, which can be hours or days old; ADR-007 §6 step 4 then finds at confirmation that YNAB changed (it almost
always has, when the operator uses YNAB) and writes nothing (A5). As written, most first confirmations would fail.
- (a) "Review Upload Plan" first compares the Budget (UC-003) and then prepares the plan; a failed read shows A1 and
  prepares nothing.
- (b) As written: prepare from stored statuses; the operator reads YNAB again himself when A5 says so.
- Recommend (a). Redo if different: the entry flow of C5a and one screen state (S).

Q4. **A Transfer Leg that was already uploaded as an ordinary transaction.** The first side of a payment between two
Bank Accounts often arrives alone and matches no description, so it is New and gets uploaded as an ordinary
transaction. When the other side arrives, detection pairs them (UC-005 BR-002 does not look at the status). BR-010
then keeps the whole Transfer out of every Upload Plan, and A7/BR-008 lock the Transfer Decision because a Transfer
Leg "has been uploaded": the second side can never be uploaded and no action is offered.
- (a) A Bank Transaction whose Sent Record shows it written as an ordinary transaction is no Transfer Leg; the other
  side is an ordinary Bank Transaction and is uploaded as one; the operator links the two in YNAB if he wants.
- (b) The Transfer is kept, not eligible, and Split stays available for it although a Transfer Leg was uploaded.
- (c) As written: it waits for ever.
- Recommend (a). Redo if different: one rule in the Comparison and its tests (S-M).

Q5. **A whole Budget Account refused because of one transaction.** YNAB answers a list of transactions as a whole;
one transaction it will not take (a date it calls future, a closed account) refuses them all (A9). A New transaction
offers no decision (UC-004 BR-001), so the operator cannot take it out, and that Budget Account's Uploads stay refused.
- (a) After a refused list, the YNAB part sends the same items one at a time in the same Upload, so that only the one
  YNAB refuses is Rejected (more requests; a duplicate is still refused by its Import Identifier).
- (b) As written: all are Rejected with YNAB's reason; the operator fixes the cause in YNAB or at the bank.
- (c) Defer becomes a choice for a Rejected transaction.
- Recommend (a). Redo if different: `YnabWriter` and its contract tests (S).

Q6. **Transfers typed or scheduled by hand in YNAB.** UC-003 BR-001 puts "Transfer Leg" before "Match For Typed
Entry": a Transfer whose side resembles a Typed Entry is still sent as a linked transfer. Whether YNAB then matches
the typed transfer or keeps both pairs is unproven. Does the operator type or schedule transfers (a recurring card
payment, a move to savings) in YNAB before the bank books them? Are any mapped Budget Accounts tracking or loan
accounts?
- (a) As written: send the linked transfer; E-10 gets a case for it.
- (b) Until E-10 shows that YNAB matches without a second pair, a Transfer of which either side resembles a Typed
  Entry (same amount, Matching Date Window) is held as Needs Review.
- Recommend (b) if he does type or schedule transfers, (a) otherwise. Redo if different: one rule in C3 (S).

Q7. **Re-upload: once, or standing?** A Review Decision Re-upload is stored for the Bank Transaction. After the
Re-upload is written and the operator deletes that transaction in YNAB again, UC-003 BR-001 ("Deleted In Budget App
... unless the Operator chose Re-upload") makes it New again, and the next Upload Plan would send occurrence 2
without a new decision. (Area B's survey covers the lock wording of UC-004 A7/BR-004.)
- (a) Once: the decision is used up by the send it caused; a later deletion shows Deleted In Budget App and Re-upload
  is switched on again by hand.
- (b) Standing: every later deletion is followed by a new send.
- Recommend (a). Redo if different: one rule in C3/C5a (S). Note for the two surveys: ADR-007 §4 and `SENT_RECORD`
  say the first send of a Bank Transaction is occurrence 0, also when a Re-upload decision caused it (area B's guess
  10 says occurrence 1); ADR-008 §5's skip rule then moves to occurrence 1 only when the stored copy already holds the
  identifier.

Q8. **The other side arrives late.** A Lonely Transfer is uploaded as a linked transfer, so YNAB holds the
counterpart at once. The arriving Bank Transaction of that side is recognised only by being paired (transfer pairing
days, 5 by default, on booked dates). Today a Proposed Lonely Transfer is also dissolved when its grace period ends
(`TransferDetection.detect`, `RoomComparisonStore.storeTransfers`). A side booked later than the pairing days is then
New and is uploaded beside the counterpart: a duplicate.
- (a) A Transfer with a sent Transfer Leg is never dissolved; a Bank Transaction in the counterpart's Budget Account
  with the counterpart's amount, outside the pairing days but within the Matching Date Window of an unsettled
  counterpart, is Needs Review (Import Anyway or Defer), never New.
- (b) Such a Bank Transaction is Arrived As Transfer Counterpart without asking (risk: a real second payment of the
  same amount is held back with no action).
- (c) As written: pairing days only.
- Recommend (a). Redo if different: the rule in C3 (S-M).

### 4.2 Gaps where a conservative reading is taken (no question)

1. **What YNAB's answers become** (ADR-001 §4.2.4 is silent beyond timeout and lost connection). A duplicate
   (`duplicate_import_ids`, or 409 for a single item) is `AlreadyPresent(null)`. 400 and 404 are `Rejected` with
   YNAB's text. 401 and 403 are `CredentialsRefused`. 429, every 5xx, an answer that cannot be read, and an item that
   is neither among the created ones nor among the duplicates are `Unknown`.
2. **`CredentialsRefused` or `StoppedByGate` while writing** (UC-006 A6 covers only step 5; the Sent Records are
   already Not Yet Known). Record Unknown for the items concerned, mark the credentials refused, go on to step 11.
   Unknown keeps the Budget Account and is resent only under the same Import Identifier.
3. **The transfer payee.** YNAB links a transfer through the other account's transfer payee, which the contract does
   not carry. The YNAB part reads the accounts of the Budget inside `create` (one request through the gate, kind
   `BudgetAppRead`); when that read fails, the linked items are Unknown and nothing is sent for them.
4. **A Sent Record that is Rejected although YNAB holds the transaction** (a refused list that was partly written).
   The plan builder asks the status and the Sent Record: a transaction whose Import Identifier the stored copy holds
   is Already In Budget App and yields no item whatever its Sent Record says; O-5 settles such a record as Written.
5. **A Sent Record that is Written or Already Present while the stored copy does not hold the identifier** (YNAB
   keeps a deleted transaction's identifier locked and reports no deletion in a first read; a transaction moved in
   YNAB to a Budget Account Slussen does not hold). Never New, never eligible: shown as Deleted In Budget App, which
   offers Re-upload and nothing else.
6. **A Transfer is frozen once a Transfer Leg is sent as a linked transfer** with an outcome other than Rejected: O-5
   never dissolves, re-pairs or expires it (the entity model says "Written" only; Not Yet Known, Unknown and Already
   Present are treated alike), and changes of Funded By, Transfer Partner, descriptions or pairing days do not touch
   it (UC-005 BR-006 says so for Funded By and Transfer Partner).
7. **The `upload_plan_item` "header row"** (ADR-002 §2 names it without a shape). One row at position 0 per plan with
   `write_kind` Header, holding `state_version` and the `change_cursor` of the plan's Budget; a plan has one Budget
   (`UPLOAD_PLAN.budget_id`), so "per Budget" is one value.
8. **"Any change in that Budget" at confirmation** (ADR-007 §6 step 4). A read that returns no changed transaction
   is "unchanged", also when YNAB's cursor moved for another reason; nothing is stored then. One changed transaction,
   in any Budget Account of the Budget, is "changed".
9. **Preparation uses the date of preparation** (UC-006 BR-018) although the stored statuses carry the date of the
   last Comparison. Preparation first concludes the Comparison again from stored data with today's date (O-5, no
   request), then builds the plan in O-6. With Q3 (a) this happens anyway.
10. **Leaving the screen** (BR-019). Back, a tab and any navigation inside Slussen cancel. Putting Slussen in the
    background or turning the screen off does not; the expiry still ends the plan, and a process that dies makes it
    Expired (ADR-007 §8).
11. **Shortening** (BR-015). Cut at a code point boundary so that the text has at most the declared number of UTF-16
    units (which is also at most that many code points); never in the middle of a surrogate pair. For a linked
    transfer YNAB takes the payee from the transfer; the memo is sent as it is.
12. **The rejection reason** is YNAB's text and is never stored (ADR-001 §4.2.4). It is kept in memory for the
    outcome screen; after a restart the outcome shows "rejected" without the reason. The same for "the Comparison
    after the Upload failed" (A12).
13. **The outcome shown later** (BR-019, step 13). The latest Confirmed plan of the Budget, read back from
    `upload_plan`, its items, their Sent Records and Transfer Counterparts; shown until the next plan of that Budget
    is prepared. `UPLOAD_PLAN.state` has no value for "written"; an Upload cut short shows its Unknown outcomes.
14. **A plan for one Bank Account** (BR-002). It holds the items written through that Bank Account's Bank
    Transactions (a linked transfer's other Budget Account is listed too) and the clearing items whose Transfer
    Counterpart was created by a send of that Bank Account or lies in its Budget Account.
15. **A plan with clearing items only** is a plan (A1 says so); it writes no Sent Record (ADR-007 §9 has the test).
16. **An Import Identifier of the Bank Transaction held in another Budget Account of a served Budget** (a restore
    from a backup made before a mapping was moved: the Sent Record and the move are both lost). Needs Review, not
    New: YNAB refuses a reused identifier per Budget Account only, so the refusal does not protect here.
17. **`AlreadyPresent(null)`**: the reference stays empty; the next Comparison fills `budget_app_reference` when it
    finds the identifier.
18. **Budget App requests are not counted** (ADR-001 §4.0: "the specification sets no limit"). YNAB does limit them
    (see §6). A refused request is Unknown for a write, Not Cleared for a clearing, A1 for a read; nothing more.

### 4.3 Contradictions and stale text found (for C0)

- ADR-005 §7 against this run's instruction (Q1). Its table lists E-13 between E-11 and E-12; issue #6 names
  "E-01..E-12". E-09 still says "Account Mapping lock" (now `first_sent_at`, S-23).
- UC-003 BR-001's order names neither Arrived As Transfer Counterpart, Transfer Counterpart Changed nor Held Transfer;
  the glossary and `BANK_TRANSACTION.status` list all three.
- UC-005 A7/BR-008 (locked once a Transfer Leg is uploaded) against BR-010 (a Transfer with a Transfer Leg that is
  Already In Budget App is in no Upload Plan): Q4.
- `TRANSFER`: "cannot be changed once a Transfer Leg has a Sent Record with outcome Written" says nothing for Not Yet
  Known, Unknown, Already Present (guess 6).
- `upload-plan.html#awaiting` counts a resend ("Payroll ... Resend, Outcome Unknown") among "8 New"; UC-006 step 3
  and `UPLOAD_PLAN.new_count` have no count for resends. Reading: a resend counts as a transaction to create.
- `first-upload-matching.html` and `FirstUploadMatching.kt` still carry the build-state "the Comparison is not built"
  (`COMPARISON_BUILT = false`), although Phase 7a built it.
- ADR-002 §2's list of technical tables is "complete"; a separate header table would amend it (guess 7 avoids that).
- ADR-007 §2: a Fetch the Operator starts "waits for the lane" and can be withdrawn; nothing of that is specified in
  a use case or drawn.

## 5. Evidence E-01..E-13 (ADR-005 §7, issue #6)

| ID | Who can produce it | State now | What the code must provide |
|---|---|---|---|
| E-01 | Device: API 26 emulator and the phone | JVM test with a fake database check only | Instrumented test of `StartSequence` with the real `SqliteDatabaseCheck`, repeated starts |
| E-02 | Device, API 26 | None | A seam to plant a failed copy (the copy step injectable) and the test |
| E-03 | Device, API 26 and the phone | JVM approximations | Instrumented tests with planted garbage bytes and a planted unknown `Values:` text for each database; **the running-app path of ADR-002 §8 step 4 is not built** (start intent written, process restarted, Room `onCorruption` replaced) |
| E-04 | Device, API 26 | JVM test | Instrumented test |
| E-05 | Device, API 26 | None (no failure injection in any IT) | A test seam that fails each of O-1..O-14 before its commit; a kill between O-4 and O-5; a kill right after `confirmPlan` returns (a second process or `am force-stop` from the test) |
| E-06 | Device (a JVM variant runs in `check`) | Exists for versions 1-5; passed on the phone for 1-2 on 2026-10-28 | The new version added to `SlussenDatabaseMigrationIT` and the JVM test |
| E-07 | The phone and the operator only (`adb shell bmgr`) | Procedure not written; E-13's run already showed the Budgets kept and the credentials gone | A written procedure; the app must show after the restore: cause Restored Backup, the kept Bank Transactions and Sent Records (visible as Already In Budget App counts), no Upload Plan until confirmation |
| E-08 | Device | Instrumented test exists (`UC008CheckImportStatusPersistenceIT`) | Nothing new |
| E-09 | The phone and the operator's keystore only | Procedure not written | A written procedure; something the operator can compare before and after the upgrade (the counts on the Budget tab, or a Journal Export) |
| E-10 | The operator's machine, live YNAB, `-PincludeLive` | Plumbing exists, no test | The live tests (C2), staged where YNAB's own screens are needed (Q2); see §6 for the cases to add |
| E-11 | JVM, part of `check` | Exists for credential checks and reads | The same cases for `create`, `markCleared`, `readTransaction` |
| E-12 | Device, API 26 | One JVM test (resume from the intent) | A seam to stop `StartSequence` at each boundary and an instrumented test per boundary and cause |
| E-13 | Device and the phone | Passed on the phone, 2026-10-28 | Nothing, unless `device.db` changes (area E) |

Only a device can give E-01..E-06, E-08, E-12, E-13; only the phone and the operator E-07 and E-09; only live YNAB
E-10. Only E-11 is proven inside the run.

## 6. YNAB behaviours the design assumes without proof

From memory of YNAB's public documentation; none could be checked here. "Live" marks what E-10 should cover.

1. A second transaction with an Import Identifier the Budget Account already holds is refused and reported: in
   `duplicate_import_ids` for a list, as 409 for a single one. C-009, BR-007. Live (already in E-10).
2. The identifier stays locked after the transaction is deleted (C-009). Re-upload's new identifier and guess 5 rest
   on it. Live: create, delete, create again.
3. The identifier is unique per Budget Account, not per Budget. BR-020 (a Rejected send follows a moved mapping) and
   guess 16 rest on it. Live: the same identifier in two accounts.
4. A refused list writes nothing. "Rejected means the Budget App holds it nowhere" (ADR-001 F-2, ADR-007 §4) rests
   on it. Live: a list with one item YNAB refuses.
5. A transaction whose payee is the other account's transfer payee creates the other side, linked both ways; the
   identifier sits only on the side that was sent; `readTransaction` right after the write shows the link (B-01).
   Live (in E-10); add: what `cleared` and `approved` the other side gets.
6. A linked transfer sent where a typed transfer, or a typed ordinary entry, of the same amount exists: whether YNAB
   matches, and whether a second counterpart appears (Q6). Live: not in E-10 today.
7. An Import is matched to a Typed Entry of the same Budget Account and amount within 10 days either way, and never
   to another Import (C-009). Live (in E-10); add: the two ends of the window.
8. How a match and a merge look when read: `matched_transaction_id`, which identifier survives the operator's
   approval, and whether the removed one is reported as deleted in the same answer as the survivor. `YnabReader`
   recognises a merge only then; otherwise it reads Deleted (safe, but the status is wrong). Live, staged (Q2).
9. A read by `last_knowledge_of_server` reports deletions; a read by `since_date` reports none (S-T-18 already rests
   on this); server knowledge does not expire. Live.
10. Changing only `cleared` on an existing transaction changes nothing else (memo, category, flag, `approved`), and a
    reconciled transaction is never touched (the reader reads it as Cleared, so no clearing item exists). Live: add
    the "nothing else changed" check.
11. Deleting one side of a transfer in YNAB deletes the other, and both are reported. UC-005 A6 rests on it. Live.
12. Lengths: Import Identifier 36, payee 200, memo 500 (C-009). The documentation has carried smaller numbers for
    payee and memo in the past. Check the current documentation; live: what YNAB does with a longer text.
13. Created with `approved` false and an Import Identifier, a transaction waits for approval in YNAB (F-5). Live (in
    E-10).
14. YNAB's own bank import and file import write identifiers of the form `YNAB:amount:date:occurrence`, never
    beginning with `SLS`. Not producible in a throwaway Budget (Q2).
15. YNAB limits requests per access token and hour (200 as remembered). ADR-001 §4.0 assumes no limit. One Upload
    costs, per Budget: one read of Budgets and one of changes before writing, one write per Budget Account, one read
    and one write per Transfer cleared in the same Upload, the transfer-payee read of guess 3, and the reads of the
    Comparison after. Documentation.
16. YNAB refuses a date it calls future and one older than five years; whose "today" it uses (the Budget's zone or
    UTC) is unknown. A transaction dated today on the phone just after midnight may be refused: A9, and Q5. Live or
    documentation.
17. Amounts are milliunits, the same precision as `Amount`. Documentation.
18. A write to a closed or deleted Budget Account is refused (A9's example). Live.
19. A payee rename rule may change the payee of an Import; nothing in Slussen compares payees. No action.

## 7. Risks (what could go wrong silently) and what catches them

1. **The late other side of an uploaded Lonely Transfer becomes New** (Q8): duplicate beside the counterpart.
   Catch: a core test with the other side booked after the pairing days and after the grace period; it must not be
   New and must be in no plan. Fails on today's code.
2. **A Transfer dissolved after one Transfer Leg was sent** (a setting, a description, a Funded By or a mapping
   change, the grace period): the other Transfer Leg becomes New. Catch: a test per cause that the Transfer and the
   status of the other Transfer Leg survive; persistence IT on `storeTransfers`.
3. **`state_version` not raised everywhere** (today only O-1, O-4, O-5): ADR-007 §6 step 2 would pass over a change
   made between preparation and confirmation by a path that slipped past the coordinator. Catch: one persistence test
   per storage operation asserting the version rose; the planted-violation test of ADR-007 §9 ("each check of §6
   failing").
4. **A refused list that YNAB partly wrote, or a mapping moved after a backup** (guesses 4 and 16): a retry of a
   Rejected send to another Budget Account, where YNAB's refusal does not reach. Catch: E-10 cases 3 and 4; a core
   test that an identifier held in any Budget Account of the Budget yields no item.
5. **A linked transfer onto a typed transfer** (Q6): a second pair in YNAB, with nothing in Slussen to notice.
   Catch: the E-10 case; until then Q6 (b).
6. **A merge read as a deletion, or not read at all** (assumption 8), and **a transaction that leaves the stored
   copy** (guess 5): a Bank Transaction shown Deleted In Budget App, or New, that YNAB still holds; a Re-upload would
   then duplicate it. Catch: E-10's staged merge case; a core test for every Sent Record outcome crossed with "copy
   holds it / holds it deleted / does not hold it".
7. **A crash between O-7 and O-8, or between the write and its answer**: covered by design (Unknown, same
   identifier). Catch: E-05's `confirmPlan` kill test and the ADR-007 §9 "answer lost" tests; they must exist before
   the first real Upload.
