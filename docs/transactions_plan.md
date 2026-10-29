# Plan: Bank Transactions and the Comparison

**Initiative (AGENTS.md §6):** Fetch the Operator's booked Bank Transactions from SimpleFIN, store them, read YNAB, and give every Bank Transaction a Transaction Status, so that each Budget shows which transactions are new, already in YNAB, or need a decision. Nothing is uploaded yet: Uploads (UC-006) are the next initiative.
**Target repo / branch:** `flylasse/slussen`, branch `transactions-plan` (from `main` at `b5ace27`, VERSION 0.1.4). One PR per gate (O-T-08): this PR (#24) carries the plan and Phase 1's specification, the next the revised mockups (Phases 2-2b), then one per code phase or pair of phases.
**Specification (input):** `docs/vision.md`, `docs/requirements.md`, `docs/glossary.md`, `docs/entity_model.md`, `docs/use_cases/UC-001..UC-014`, ADR-001..ADR-008, `docs/mockups/`. All use cases are Approved; UC-014 is Implemented.
**Method:** as in the closed construction plan (`docs/archive/construction_kickoff_plan.md` §1): AIUP Marketplace, with `aiup-core` 3.0.0's `use-case-test`, `use-case-implement` and `uc-coverage-layered` read from the branch `feat/layer-plugins` of Marketplace PR #38 while it is open (issue #18). Every deviation goes into §5.
**Status:** Draft, for the operator's review. No phase starts before the operator approves this plan and answers §6's open questions.

## 0. Resume here (state as of 2026-10-28)

- The plan is drafted and the operator answered O-T-01..O-T-08 on 2026-10-28 (§6): Transfers are in scope, and the fetch period becomes a setting in the app with a default of 30 days.
- The operator approved the plan on 2026-10-28. Phase 1 wrote S-T-01..S-T-07 (§6).
- The specification gate passed: the operator merged PR #24 on 2026-10-28, approving S-T-01..S-T-07.
- Phase 2 done: the operator pushed the external UX review (1982708); its 27 findings are in §6 with recommendations.
- The operator answered UX-D1..UX-D4 on 2026-10-28 (§6); issue #26 holds UX-24's notices outside this initiative.
- Phase 2b done: the mockups are revised (a sub-agent; 8 files), with this version's read-only states beside the full-product ones. It found four specification gaps, written as **S-T-08..S-T-11** for approval (§6).
- The operator approved S-T-08..S-T-11 on 2026-10-28.
- The UX gate passed: the operator merged PR #25 on 2026-10-28. Issue #27 plans the removal of the `-this-build` states.
- Phase 3 (device baseline), on the operator's phone (Galaxy, Android 17), 2026-10-28:
  - first run: `:app` 17 of 18 failed, all from Espresso 3.6.1 calling the removed `InputManager.getInstance`; fixed by pinning Espresso 3.7.0 (PR #28);
  - second run: `:persistence` **29 of 29 passed**, among them `SlussenDatabaseMigrationIT` 4 of 4 (E-06: versions 1 and 2 upgraded on the phone); `:app` 15 of 18 passed; the 3 failures were stale tests (a text the Budget card now repeats, a section title drawn in capitals, a tap below the screen edge), fixed in PR #28;
  - **E-13 passed**: after a backup with `bmgr`, an uninstall, a reinstall and a restore, YNAB showed credentials missing while the chosen Budgets and their Budget Accounts were kept.
  - third run on PR #28 at 3b635e1: `:app` **18 of 18 passed**. Phase 3 done: PR #28 merged on 2026-10-28, issue #5 closed.
- Phase 4 built (a sub-agent; the orchestrator re-ran the full gate with `--rerun-tasks`: green). It found two specification conflicts, proposed as **S-T-12** and **S-T-13** (§6), to be approved before its PR merges.
- Phase 4 done: the operator approved S-T-12, S-T-13 and the six `errlist` assumptions and merged PR #30 (2026-10-29). The SimpleFIN protocol (`www.simplefin.org`, allowed by the operator on 2026-10-29) confirms the codes, the shape of `errlist` and `balance-date`.
- Phase 5 built (a sub-agent; the orchestrator re-ran the gate with `--rerun-tasks`: green). `slussen.db` version 4 with five tables and `MIGRATION_3_4`; the instrumented migration test runs on the phone in Phase 8 at the latest. It found two specification problems, proposed as **S-T-14** and **S-T-15** (§6).
- Next: the operator approves S-T-14 and S-T-15 and merges the Phase 5 PR; then Phase 6.

## 1. Scope

### Use cases and requirements this initiative builds

| Use case | Part built here | Part left for later |
|---|---|---|
| UC-001 Fetch Transactions | Steps 7-10 (keep booked, non-zero transactions; store them; record them in the Decision Journal; compare), A8-A10 as far as SimpleFIN reports them, A17 (Unreadable Transactions), BR-002, BR-003, BR-009 (fetch period), BR-016 | A4, A9, A13 (Bank Consents, with Enable Banking); A12, A14, BR-013, BR-014 (Scheduled Fetch and New Transaction Notification) unless O-T-04 says otherwise |
| UC-003 Compare With the Budget App | Steps 1-7: read YNAB's transactions and changes, the order of BR-001, the Transfers (step 4, with UC-005 below), Eligible Transactions, the Decision Journal, A1-A3, A7, BR-001..BR-007, BR-011..BR-016, BR-018, BR-020 | A4-A6, BR-008..BR-010, BR-017, BR-019, BR-021, which need Review Decisions (UC-004), Sent Records (UC-006) or First Upload Matching |
| UC-005 Review Transfers | Detecting Transfers and giving their Transfer Legs their status (O-T-01): BR-001..BR-006, BR-010, BR-012, BR-014, A4, A5, A8 as far as they give a status; the Card Payment Descriptions and Bank Transfer Descriptions as their default lists (UC-010 BR-007) | The Transfer Decisions (steps 2-6, A1-A3, A9, BR-007, BR-008, BR-011, BR-013), as for Review Decisions (O-T-02); A6, A7, BR-009, which need Uploads; editing the description lists and Funded By |
| UC-010 Adjust Settings | The fetch period on Settings: shown, changed within 7-180 days, journaled (steps 1-4, 6, A1) (O-T-07) | Every other Operator Setting that is not already on a service page |
| UC-008 Check Import Status | The Bank Transactions with their Transaction Statuses on the Budget tab and the Bank Account screen (`budget.html`, `bank-account.html`); the counts that now stop being 0 | Upload Plan entry points |
| UC-011 Export the Decision Journal | BR-001 recording for the events above (the journal exists since construction) | The export itself |

The requirements are those these use cases list; their statuses are derived again at close (`docs/architecture/development.md`, REFERENCE.md rule). Phase 1 adds or changes requirements only where §6's issues need it.

### Issues this initiative takes on

| Issue | What | Where |
|---|---|---|
| #8 | A selected Bank Account the Data Provider does not deliver: a UC-001 flow and its Fetch Result | Phase 1 (spec), Phase 6 (code) |
| #9 | The Fetch Cooldown re-reads a newly added Bank Account on every Fetch: "not read since it was added" becomes a stored fact | Phase 1, Phase 6 |
| #11 | SimpleFIN's `errlist`: `con.auth` becomes Slussen's own outcome "the Bank's sign-in at the Data Provider needs you"; `act.failed`, `act.missingdata` become `Failed(Incomplete)`; no SimpleFIN text passed on | Phase 1, Phase 4 |
| #16 items 1, 3 | The balance time in `Read` and "balance as of …"; a balance in another currency is not stored and is shown (3b) | Phase 1, Phase 4, Phase 6 |
| #5 | Device evidence: the instrumented tests and E-13, before the schema changes (O-T-03) | Phase 3 |

Not taken on: #6 (gates Uploads), #7 and #15 (Enable Banking), #18 (waits on the Marketplace).

### What the Operator will see at the end

- After a Fetch of SimpleFIN, each Budget tab lists its Bank Transactions with a Transaction Status: New, Match For Typed Entry, Transfer Leg, Guessed Transfer, Needs Review (for example an Unreadable Transaction), Before Start Date, Too Old, and so on. Already In Budget App cannot occur yet: it needs an Import Identifier Slussen sent (UC-003 BR-003), and this version sends nothing.
- A payment from one of your Bank Accounts to another in the same Budget is one Transfer with two Transfer Legs, not two New Bank Transactions. One that Slussen cannot pair for certain is a Guessed Transfer, shown but not yet confirmable (O-T-02).
- Settings shows the fetch period, 30 days by default, and you can change it, for example to 14 days.
- The counts on the Overview and the Budget tabs are real: Eligible, and waiting for a decision.
- The "⟳" on the YNAB line reads YNAB again and updates the statuses without contacting a Bank.
- The build-state lines "Bank Transactions are not fetched in this build yet" (P9-14, P9-15) disappear.
- Upgrading from 0.1.x keeps every stored item (NFR-017): `slussen.db` moves to schema version 4 by a Migration.

## 2. How this plan is executed

The rules of the closed construction plan's §1 apply:
- one sub-agent per phase, with a clean context and the §4 prompt;
- the orchestrator runs every gate itself;
- one commit per phase, pushed before the next phase starts;
- §5 and §6 are updated in the phase's commit;
- no private data reaches an agent (signing values, credentials, anything entered in the app);
- tests use invented data and local test servers only, and never `-PincludeLive`.

In addition:

1. **Four operator stops.** The orchestrator never moves past a stop on its own.
   - **Specification gate**, after Phase 1: the operator approves every specification change (S-T-nn) before any code.
   - **UX gate**, after Phase 2: the operator walks through the screens of this initiative with the UX review's findings, and the mockups are revised before any code (the operator's verdict on the last plan).
   - **Device baseline**, Phase 3: the operator runs the instrumented tests on an emulator or the phone (O-T-03).
   - **Acceptance**, Phase 8: on the phone, with the operator's own release build, real SimpleFIN and YNAB data, upgraded from 0.1.4.
2. **Specification first.** If construction shows that a use case, rule or entity is wrong or missing, the phase stops and the finding goes into §6. The specification is changed, with the operator's approval, before the code.
3. **Issues.** Every finding that this plan does not settle becomes a GitHub issue with one of the five labels (AGENTS.md §6). The orchestrator comments on an issue whenever its status changes, not only when it closes.
4. **Build-state lines.** A screen that shows a feature not yet built says so in one plain line, as P9-13..P9-15 did, and the mockup carries the same line as a build-state note.

## 3. Phase table

| Phase | What | Writes | Model | Gate (orchestrator runs it) | Commit message |
|---|---|---|---|---|---|
| 1 | Specification changes for #8, #9, #11, #16 and the open questions' answers | `docs/` (use cases, entity model, ADR-001 §4.1, glossary, requirements as needed); §6 S-T-nn | `inherit` | `lint_specs.py --strict` and `check_project_rules.py` exit 0; every S-T-nn names its issue, the files it changes and the reason | `docs: phase 1 - specification for Bank Transactions and the Comparison` |
| — | **Specification gate: the orchestrator stops** | operator reviews the S-T-nn | operator | operator says "approved" | — |
| 2 | UX review (report only), **external reviewer**, of the screens this initiative changes: Overview, Budget tab, Bank Account, Transfers (status only), Fetch states and Recent Fetches, the YNAB read, Settings with the fetch period, the SimpleFIN page | `docs/reviews/transactions_phase2_ux_review.md` (reviewer's commit); findings copied into §6 UX-nn (orchestrator's commit) | external CLI of another vendor, run by the operator with the prompt in §4 (O-T-05) | the reviewer's commit changes only its review file; every finding has an ID, a screen and state, what the Operator would not understand or could not do, a severity and a proposal | reviewer: `docs: phase 2 - UX review (external)`; orchestrator: `docs: phase 2 - record the UX review in the plan` |
| 2b | Mockups revised from the UX gate's decisions | `docs/mockups/*.html` | `inherit` | `check_mockup_links.py` and `check_project_rules.py` exit 0; every accepted UX-nn is resolved in a named state | `docs: phase 2b - mockups revised after the UX gate` |
| — | **UX gate: the orchestrator stops** | operator walks the mockups | operator | operator says "approved" | — |
| 3 | Device baseline (#5): `gradlew connectedDebugAndroidTest` on an emulator (API 26 and current) or the phone, then E-13 with `adb shell bmgr` | results into §6 and issue #5 | operator | every instrumented test passes, or each failure becomes an issue | — (results recorded by the orchestrator in Phase 4's commit) |
| 4 | Data Provider contract and SimpleFIN: `Read` carries each transaction and the balance time (#16.1); SimpleFIN maps `transactions`, `pending`, `balance-date` and `errlist` (#11) | `:plugin-api`, `provider-simplefin`, contract tests | `inherit` | `gradlew check` passes; the contract tests cover a pending item, a zero amount, an item without a date, `con.auth` and `act.failed` | `feat: phase 4 - transactions in the Data Provider contract and SimpleFIN` |
| 5 | Storage: `slussen.db` version 4 with `BANK_TRANSACTION`, `UNREADABLE_TRANSACTION`, `BUDGET_APP_TRANSACTION`, `TRANSFER` and `TRANSFER_DESCRIPTION` (entity model), the default description lists, and a hand-written Migration from version 3 (S-39) | `:persistence`, schema JSON, `SlussenDatabaseMigrationIT` | `inherit` | `gradlew check` passes; the S-39 check accepts version 4; the Migration IT covers versions 1, 2 and 3 to 4 (run on a device in Phase 8 at the latest) | `feat: phase 5 - schema version 4 for Bank Transactions` |
| 6 | UC-001 steps 7-10 in the core, A8, A10, A17, #8, #9, #16.3b; the fetch period on Settings (UC-010, O-T-07); then the screens | `:core` `fetch`, `:core` `settings`, `:app` | `inherit` | `gradlew check` and `:app:assembleDebug` pass; every in-scope BR and flow of §1 has a test that names it; `uc-coverage-layered` matrix in the phase report | `feat: phase 6 - Bank Transactions from a Fetch` |
| 7 | UC-003: YNAB `readChanges`, the stored copy, the Comparison in the order of BR-001, Eligible Transactions, the Decision Journal, the "⟳" read; the Budget tab and Bank Account screen as the UX gate approved them | `budgetapp-ynab`, `:core` (a comparison package, ADR-001 §7), `:app` | `inherit` | as Phase 6, and no Comparison writes to YNAB (BR-015, a contract-test property) | `feat: phase 7 - the Comparison` |
| 7b | UC-005 detection inside the Comparison (UC-003 step 4): Transfers, Transfer Legs, Guessed Transfers, Lonely Transfers and the grace period, the description lists; the Transfers shown on the Budget tab without decisions | `:core` (a transfer package, ADR-001 §7), `:app` | `inherit` | as Phase 6; a test for each in-scope BR of UC-005, including a Transfer never across Budgets or currencies (BR-004) | `feat: phase 7b - Transfers detected` |
| 8 | **Acceptance: operator, on the phone** | release build 0.1.x → this build over it; a Fetch of SimpleFIN; YNAB read | operator | every Bank Account shows its Bank Transactions with plausible Transaction Statuses; nothing reached YNAB; no stored item lost in the upgrade; the instrumented Migration test passes on the phone | operator's verdict into §6 |
| 9 | Close: §7, requirement statuses, open work turned into issues, plan to `docs/archive/` | this plan, `docs/requirements.md`, issues | `inherit` | `lint_specs.py --strict` and `check_project_rules.py` exit 0; §7 lists issue numbers, not open work | `docs: phase 9 - close the transactions plan` |

## 4. Phase prompts

### Phase 2: UX review (external reviewer)

The operator runs it on the branch `transactions-ux` with an external CLI (O-T-05), and commits and pushes only the review file.

```text
You are an external UX reviewer of Slussen, an Android app for one person, the Operator, that fetches their
bank transactions from Data Providers (SimpleFIN in this build) and, in a later version, uploads them to their
budgeting app, YNAB, after they confirm an Upload Plan. It is an AIUP project: the specification in docs/ is
the source of truth. Read AGENTS.md first, then docs/glossary.md, docs/requirements.md (NFR-023 especially),
docs/use_cases/UC-001, UC-003, UC-005, UC-008 and UC-010, and docs/transactions_plan.md sections 1 and 6.

What changes for the Operator in the next version (transactions_plan.md section 1):
- A Fetch of SimpleFIN now brings in booked Bank Transactions, not only balances. Slussen reads YNAB and gives
  each Bank Transaction a Transaction Status (New, Already In Budget App, Match For Typed Entry, Needs Review,
  Before Start Date, Too Old, In The Future, Transfer Leg, Guessed Transfer, ...).
- Transfers between the Operator's own Bank Accounts in one Budget are detected, but cannot yet be confirmed,
  split or held (no Transfer Decisions). Needs Review transactions cannot yet be decided (no Review Decisions).
  Nothing is uploaded in this version.
- New situations: a selected Bank Account that SimpleFIN does not deliver (UC-001 A19), the Bank's sign-in at
  SimpleFIN needed (A20), a balance in another currency (A21), the time a balance was reported ("balance as
  of"), the fetch period on Settings (30 days by default, 7-180).

Scope: the mockups in docs/mockups/ that show these situations: overview.html, budget.html,
bank-account.html, transfers.html (statuses only), fetch-history.html, settings.html, connections.html (the
SimpleFIN page). Read the HTML source; each <div class="state" id="..."> is one screen state, and its
step-label names the use case step it shows. Do not review screens for Uploads, Review Decisions, Bank
Consents or Transaction Files except where this version's screens lead to them.

Walk these journeys as the Operator, a careful non-expert who knows their own banks and YNAB:
1. Upgrade from the previous version, open the app, fetch everything, find this week's transactions.
2. For each Transaction Status that can appear in this version: can the Operator tell what it means, whether
   anything is wrong, and what, if anything, to do now or later?
3. A payment from checking to the credit card: how does it look; is a Guessed Transfer that cannot yet be
   confirmed a dead end or clearly "later"?
4. Each Blocked Reason of A19, A20, A21 and the existing ones: is the next action clear and reachable?
5. Read YNAB again with the refresh on the YNAB line; tell apart "statuses as of" and "balance as of".
6. Change the fetch period to 14 days.

For every screen in scope, check NFR-023: every action shows its state on its own control before it sends
anything, its result appears in the same place, and every Blocked Reason, empty state or line about a part
this version does not have names the next action or says that none is needed. Also report a situation in
the specification that no mockup state shows (for example A19-A21 or the fetch period), and any term on a
screen that is not the glossary's.

Report only. Write docs/reviews/transactions_phase2_ux_review.md and change no other file. One finding per
row: ID (UX-01, UX-02, ...), screen and state id, journey, what the Operator would not understand or could not
do, severity (Blocker: the Operator cannot complete the journey or misreads money; Major: they get stuck or
need to guess; Minor: friction), and a proposal in one or two sentences. End with the three findings you
would fix first. Use invented names and amounts only. Do not read scratch/ or any other repository.
```

### Phase 4: Data Provider contract and SimpleFIN (sub-agent)

```text
Work in /home/user/slussen on the branch transactions-phase4 (checked out). Do not commit, push or switch
branches; leave the changes in the working tree for the orchestrator.

Read AGENTS.md, docs/transactions_plan.md §1, §2 and §6 (S-T-03, S-T-04), ADR-001 §4.1 (the Data Provider
contract, including the bullets "The time of a balance" and "SimpleFIN's error list"), UC-001 A8, A20, A21,
ADR-004 (traceability markers), ADR-006 (logging) and docs/architecture/development.md. Follow aiup-core's
use-case-test and then use-case-implement (read from /home/user/marketplace, branch feat/layer-plugins):
tests first.

Build:
1. :plugin-api: `BankAccountResult.Read` gains `balanceReportedAt: Instant?` (default null);
   `BankAccountResult.SignInNeeded` (an object, carries nothing), with KDoc citing UC-001 A20 and ADR-001 §4.1.
2. provider-simplefin: read each account's `balance-date` (a Unix time) into `balanceReportedAt`; read the
   answer's `errlist` (protocol version 2: objects with `code`, and `conn_id` or `account_id`) and map it:
   `con.auth` -> `SignInNeeded` for every account of that connection; `act.failed`, `act.missingdata` ->
   `Failed(FailureKind.Incomplete)` for that account; any other code -> `Failed(UnreadableAnswer)` for the
   accounts it names, ignored when it names none. Never pass any SimpleFIN text on (NFR-012). Remove the
   KDoc sentence that says errlist is not read.
3. The Data Provider contract tests (plugin-api testFixtures, DataProviderFetchContractTest) and the
   SimpleFIN tests cover: a pending item passed on with booked=false, a zero amount passed on, an item without
   a date as Uninterpretable(MissingDate), balance-date carried, con.auth -> SignInNeeded for the accounts of
   that connection only, act.failed -> Failed(Incomplete), and that no errlist text appears in any
   toString() of the response. Invented data and the local test server only; never -PincludeLive.
4. Enable Banking and every other user of these types compile unchanged in behaviour.
5. :core: the Account Balance stored in step 8 takes `reported_at` from `balanceReportedAt`, or the time the
   answer arrived when it is null (S-T-04), with a test naming UC-001 step 8. A `SignInNeeded` Bank Account
   keeps its stored data and gets no Fetch Result yet (UC-001 A20 is built in Phase 6): handle it in every
   `when`, and say so in a comment naming Phase 6.

Gate (run it; it must pass): ANDROID_HOME=/opt/android-sdk; sh ./gradlew --stop;
sh ./gradlew check :app:assembleDebug --rerun-tasks; python3 tools/lint_specs.py --strict;
python3 tools/check_project_rules.py.

Report (under 40 lines): files changed, tests added with their names, gate result, Clarified / Assumed /
Open, and any place the specification was unclear or wrong (do not change docs/; report it instead).
```

### Phase 5: schema version 4 (sub-agent)

```text
Work in /home/user/slussen on the branch transactions-phase5 (checked out). Do not commit, push or switch
branches; leave the changes in the working tree for the orchestrator. Never read, print or write signing values
or credentials; never run -PincludeLive; tests use invented data only.

Read AGENTS.md, docs/transactions_plan.md §1, §2, §3 (Phase 5 row) and §6, docs/entity_model.md (BANK_TRANSACTION,
UNREADABLE_TRANSACTION, BUDGET_APP_TRANSACTION, TRANSFER, TRANSFER_DESCRIPTION, and their constraints), ADR-002
(all of it; §2 mapping, §3 storage operations, §7 upgrades and S-39), ADR-005 §7 E-06, UC-010 BR-007 (the default
Card Payment Descriptions and Bank Transfer Descriptions), UC-003 A1 (S-T-08: BANK_TRANSACTION.status is Optional),
and the existing persistence code, schemas and migrations (MIGRATION_1_2, MIGRATION_2_3, SlussenDatabaseMigrationIT,
the JVM migration tests). Follow aiup-core's use-case-test, then use-case-implement (read from
/home/user/marketplace, branch feat/layer-plugins): tests first.

Build:
1. slussen.db schema version 4 with the five tables above, mapped as ADR-002 §2 says (names, types, indices,
   foreign keys, unique constraints), and its committed schema JSON. A column whose foreign key targets a table
   that does not exist in version 4 is a plain column; list each one in the report.
2. A hand-written MIGRATION_3_4 that creates the five tables and fills TRANSFER_DESCRIPTION with the default lists of
   UC-010 BR-007; every row of version 3 kept. Never fallbackToDestructiveMigration (K-09).
3. Tests: the JVM migration tests for 3 -> 4 and 1 -> 4 / 2 -> 4 through the chain, with the rows of every earlier
   table read back; SlussenDatabaseMigrationIT extended the same way (it runs on the phone in Phase 8 at the latest);
   the S-39 schema check accepts version 4 and still refuses a change to versions 1-3.
4. No DAO or store for these tables beyond what the migration tests need: the stores come with Phases 6 and 7.

Gate (run it; it must pass): ANDROID_HOME=/opt/android-sdk; sh ./gradlew --stop;
sh ./gradlew check :app:assembleDebug :persistence:assembleDebugAndroidTest --rerun-tasks;
python3 tools/lint_specs.py --strict; python3 tools/check_project_rules.py.

Report (under 40 lines): files changed, tests added with their names, the plain columns of step 1, gate result,
Clarified / Assumed / Open, and any place the specification was unclear or wrong (do not change docs/).
```

Written for each phase when the phase before it is accepted, so that each prompt carries what the gates decided. Every prompt starts with: read `AGENTS.md`, this plan's §1, §2 and §6, and the use cases and ADRs the phase names; follow `use-case-test` and then `use-case-implement` for code phases; return Clarified / Assumed / Open.

## 5. Methodology evaluation log

| # | Phase | AIUP step | What happened | Deviation? |
|---|---|---|---|---|
| 1 | Draft | Initiative intake (AGENTS.md §6) | The plan names the use cases, requirements and issues it takes on, and its first phase writes the specification changes. | No |
| 2 | 1 | Specification changes (`requirements`, `use-case-spec`, `entity-model`) | The orchestrator wrote Phase 1 itself instead of dispatching a sub-agent (§2): every change had been designed with the operator in the same session (issues #8, #9, #11, #16, O-T-06, O-T-07), and a sub-agent would have needed that whole conversation as its prompt. The semantic `/spec-review` was not run; the two linters were. | Yes |
| 3 | 1 | `requirements` (NFR) | A usability requirement (NFR-023, O-T-06) is written as an NFR with the category Usability. The project rule on numbers (AGENTS.md §2) refused "within 1 second", so the measure is "before the action sends any request or waits for anything". | No |
| 6 | 4 | `use-case-test`, then `use-case-implement` | Tests were written first, but they could not compile before the new types existed, so none was seen failing first: the recurring deviation of the construction plan (§7 there). | Yes |
| 7 | 5 | `use-case-test`, `use-case-implement` | The sub-agent read the two skills from `origin/feat/layer-plugins` with `git show`, since the Marketplace clone is on a detached HEAD without them. Six new JVM tests were seen failing before the production change. | Yes (how the skills were read) |
| 5 | 2b | Mockups (`use-case-spec` mockup step) | Phase 2b ran as a sub-agent with the accepted findings as its brief, as §2 says; it changed only `docs/mockups/` and reported nine specification observations instead of changing the specification. | No |
| 4 | 2 | Adversarial review (Centaur cross-model rule) | The external UX reviewer (Codex CLI) worked only from this repository, as its prompt said, so it did not read the Marketplace `spec-review` skill that AGENTS.md names. Its review is a source walkthrough of the HTML mockups, not of the running app. | Yes (recorded as the reviewer asked) |

## 6. Findings, specification changes and open questions

### Open questions, answered by the operator on 2026-10-28

| ID | Question | Recommendation | Operator's answer |
|---|---|---|---|
| O-T-01 | **Transfers.** Without UC-005, a payment from your checking account to your credit card shows as two New Bank Transactions, one on each Budget Account. Build the detection of Transfers (UC-005 BR-001, BR-002, BR-010) in this initiative, or leave it for the next one? | Leave it for the Upload initiative, which needs it before anything is sent, and add a build-state line: "Transfers are not detected in this build yet; both sides show as New". This initiative is already large. | **Transfers are in scope.** "If we only were downloading we could keep it out of scope, but we are classifying transactions as already in YNAB: either one or the other. If we start classifying, Transfers are included." UC-005 detection is added to §1 and Phase 7b; Transfer Decisions stay out, as Review Decisions do (O-T-02). |
| O-T-02 | **Review Decisions.** Needs Review and Unreadable Transactions will appear but cannot be decided on (UC-004). Acceptable for this initiative? | Yes, with a build-state line. Nothing is uploaded, so nothing is lost by waiting. | As recommended. |
| O-T-03 | **Device evidence early.** Run the instrumented tests (#5) as Phase 3, before schema version 4, rather than at acceptance? | Yes. The Migration from 3 to 4 is the riskiest change here, and the tests of versions 1 and 2 have never run on a device. | As recommended. |
| O-T-04 | **Scheduled Fetch** (UC-001 A12, the New Transaction Notification). In or out? | Out. Starting a Fetch yourself is enough until Uploads exist. | As recommended. |
| O-T-05 | **Who does the UX review?** | A model from another vendor through its CLI, run by you, as for the architecture review: a reviewer trained differently has different blind spots. A fresh sub-agent on a different tier is the fallback. | As recommended. |
| O-T-06 | **Usability in the specification.** AIUP's requirement types have no natural place for usability (your verdict). Add usability requirements, for example an NFR "every action shows its state within 1 second and its result in place" (P9-16)? | Yes: one or two measurable NFRs in Phase 1, which the UX review then checks against. The rest stays in the mockups. | As recommended. |
| O-T-07 | **Fetch period on the first Fetch.** UC-001 BR-009 asks for the last 60 days. Your Start Dates exclude older transactions anyway (UC-003 BR-018, A7), but every older one is still stored. | Keep 60 days; transactions before the Start Date are stored and shown as Before Start Date, so you can check the cut-off. | **Default 30 days, changeable in the app**; the operator sets 14 to start with. Phase 1 changes the default from 60 to 30 (FR-001, UC-001 BR-009, UC-010 BR-001, `OPERATOR_SETTINGS.fetch_period_days`), and Phase 6 builds the fetch period on Settings (UC-010). The range stays 7-180 days. |
| O-T-08 | **PR shape.** One PR at the end (as construction), or one per gate (specification, mockups, code)? | One per gate: smaller reviews, and `main` keeps the approved specification even if the code takes longer. | As recommended: one PR per gate. |

### Specification changes (S-T-nn)

Phase 1, 2026-10-28. Waiting for the operator's approval at the specification gate.

| ID | Issue or answer | Change | Files |
|---|---|---|---|
| S-T-01 | #8 | New flow UC-001 A19 "Selected Bank Account Not Delivered": stored data unchanged, the outcome Not Delivered, the Blocked Reason "not delivered by the Data Provider" with the time, the action that fetches again and the advice to check the Bank Account at the Data Provider. | UC-001, UC-008 BR-006, entity model `FETCH_RESULT.outcome` |
| S-T-02 | #9 | A Bank Account is read within the Fetch Cooldown only until it has been read once since it was added or its identification changed (a `FETCH_RESULT` with outcome Fetched since `identified_at`); after that the Fetch Cooldown serves it. | UC-001 A2, BR-006; entity model `BANK_ACCOUNT.identified_at` |
| S-T-03 | #11 | New flow UC-001 A20 "Bank's Sign-In at the Data Provider Needed" with the outcome Sign-In Needed and its Blocked Reason; the contract's `BankAccountResult.SignInNeeded`; SimpleFIN's `errlist` mapped (`con.auth`, `act.failed`, `act.missingdata`), a recorded deviation from SimpleFIN's checklist, since no Data Provider text is shown (NFR-012). | UC-001, UC-008 BR-006, ADR-001 §4.1, entity model `FETCH_RESULT.outcome` |
| S-T-04 | #16 item 1 | `Read.balanceReportedAt` (SimpleFIN `balance-date`); `ACCOUNT_BALANCE.reported_at` is the time the Data Provider says the balance was reported, or the time Slussen received it; the Budget tab shows "balance as of …". | ADR-001 §4.1, entity model, UC-001 postconditions, UC-008 BR-005 |
| S-T-05 | #16 item 3, decided 3b | New flow UC-001 A21 "Balance in Another Currency": the balance is not stored, the previous one stays, and the Bank Account shows both currencies; no amount is converted. | UC-001, entity model `ACCOUNT_BALANCE` |
| S-T-06 | O-T-07 | The fetch period defaults to 30 days instead of 60 (range 7-180 unchanged), changed on Settings. | FR-001, UC-001 BR-009, UC-010 BR-001, entity model `OPERATOR_SETTINGS.fetch_period_days` |
| S-T-07 | O-T-06 | New NFR-023 "Every Action Answers in Place" (Usability): every action shows its state on its own control before it sends anything, its result in the same place, and every Blocked Reason, empty state or build-state line names the next action; the UX review checks each changed screen against it. Referenced by UC-001, UC-003, UC-005, UC-008, UC-010. | requirements, those use cases |

Not changed: O-T-01 (Transfers) needs no specification change, since UC-005 is Approved as it stands.

Phase 2b, 2026-10-28: four specification gaps the mockup revision found. **Approved by the operator on 2026-10-28.**

| ID | Found by | Change | Files |
|---|---|---|---|
| S-T-08 | Phase 2b (UX-13) | A Bank Transaction stored before any successful Comparison of its Budget, or since the last one, has no Transaction Status yet: it is shown as not compared yet and counted neither as Eligible nor as waiting for a decision. `BANK_TRANSACTION.status` becomes Optional, empty until a Comparison has read its Budget. | UC-003 A1, entity model |
| S-T-09 | Phase 2b | The glossary's Fetch Result lists the two outcomes S-T-01 and S-T-03 added: not delivered, sign-in needed. | glossary |
| S-T-10 | Phase 2b (NFR-023) | UC-001 A21 names its next action: nothing is needed now; if it happens again, check the Bank Account's currency at the Data Provider. | UC-001 A21 |
| S-T-11 | Phase 2b (UX-19) | A changed fetch period applies from the next Fetch that contacts the Data Provider; a Fetch served from the Fetch Cooldown asks for nothing. | UC-001 BR-009 |

Phase 4, 2026-10-28: two specification conflicts the sub-agent found. **Approved by the operator on 2026-10-29.**

| ID | Found by | Change | Files |
|---|---|---|---|
| S-T-12 | Phase 4 | ADR-001 §4.1 said both that a listed Bank Account the request did not read is `NotRead(NotSelected, balance)` (S-35) and that SimpleFIN's `con.auth` makes every Bank Account of the connection `SignInNeeded` (S-T-03). The error wins: an error the Data Provider reports for such a Bank Account, or its connection, replaces `NotRead`. | ADR-001 §4.1 |
| S-T-13 | Phase 4 | UC-001 lists NFR-012 (no Data Provider text in the journal or on screen), which A20 already relies on, so its tests can name it. | UC-001 Requirements |

Phase 5, 2026-10-29: two specification problems the sub-agent found. **Proposed, waiting for the operator's approval.**

| ID | Found by | Change | Files |
|---|---|---|---|
| S-T-14 | Phase 5 | UC-010 BR-007's default Bank Transfer Descriptions held both ÖVERFÖRING and OVERFORING, which BR-008 forbids (no two entries equal without regard to case and accents). OVERFORING is dropped: ÖVERFÖRING already matches it, since matching ignores accents (UC-005 BR-012). | UC-010 BR-007, `transfer-descriptions.html`, the default list in `SlussenMigrations` |
| S-T-15 | Phase 5 | `BANK_TRANSACTION.transfer_id` is a foreign key to `TRANSFER`, as the diagram already shows. | entity model |

Phase 5's notes: a new installation also starts with the default description lists (ADR-002 §8 step 3); the rule that no two entries of a list are equal without regard to case and accents is the core's, since a unique index cannot compare that way; the new value columns join the startup check of stored values (`SlussenStorage.MAIN_VALUES`, ADR-002 §8 step 1) when Phases 6 and 7 add their core types; version 4's digest joins the pinned final versions when version 5 is added.

Phase 4's assumptions about SimpleFIN's `errlist`, built and tested; **confirmed by the operator on 2026-10-29**:
1. An error that names a connection applies to every account of that connection, whatever its code.
2. `con.auth` that names only an account applies to that account alone.
3. Several errors for one account: the sign-in wins, then `Failed(Incomplete)`, then `Failed(UnreadableAnswer)`.
4. A malformed entry, a protocol-1 list of strings, and a code that names no account or connection are ignored.
5. A `balance-date` that is missing, 0 or unreadable is null, so the core takes the time the answer arrived.
6. No balance time is carried when no balance is.

Phase 4's other notes:
- The codes `con.auth`, `act.failed`, `act.missingdata` and the shape of `errlist` come from issue #11 and the construction plan (O-P2-61); checked against the SimpleFIN protocol on 2026-10-29: they match, and the protocol's rule that an unknown subcode is treated like its prefix (`con.`, `act.`) agrees with mapping any other code to `Failed(UnreadableAnswer)` for the accounts it names.
- The core test `fetchWithNoBankAccountAddedAsksForTheOfferedBankAccounts` still expects 60 days: the code's default fetch period moves to 30 days (S-T-06) in Phase 6.

Phase 2b's other observations, no specification change:
- Plan §1 listed Already In Budget App among this version's statuses; corrected, since it needs a sent Import Identifier.
- Funded By and Transfer Partner start empty and cannot be set in this version, so card-side Lonely Transfers cannot occur; UC-005 does not say how the card of a funding-side Lonely Transfer found by a Card Payment Description is known. Phase 7b checks this against UC-005 BR-002 and BR-012 and stops for a specification change if needed.
- UC-010 has no flow for a setting that cannot be stored; the mockups rest that on NFR-023 and the failure postconditions. No change proposed.
- The build-state notes "while the Comparison (UC-003) is not built" in `overview.html#first-added`, `#recovery` and `first-upload-matching.html` go out of date with this version; Phase 7 updates them with the code.
- **The 18 `-this-build` states are transitional** (operator's question, 2026-10-28). They show what this version shows where the full product would offer a decision or an Upload; the other new states (A19-A21, the fetch period, the times, the empty and failed results) are permanent. Their removal is planned: at this plan's close (Phase 9) they become one `debt` issue, "Remove the `-this-build` mockup states and the app's matching build-state lines", and the initiative that builds Review Decisions, Transfer Decisions and Uploads (UC-004, UC-005, UC-006) names that issue in its scope and deletes them with the code that replaces them.
- `index.html` has no traceability row for UC-001 A18 (missing since construction); `transfers.html#doubtful-leg` has no this-version state. Both are small and are fixed in Phase 7's mockup touch-ups.

### UX review findings (UX-nn)

Phase 2, 2026-10-28: an external review (Codex CLI, run by the operator) of the mockups against the approved specification, in `docs/reviews/transactions_phase2_ux_review.md`, 27 findings: 4 Blocker, 21 Major, 2 Minor. The reviewer's own limitation, carried forward as it asked: it did not read the Marketplace review skill, which lives in another repository (§5 row 4).

Most findings fall into five groups. Each group is followed by the orchestrator's recommendation; "Accept" means Phase 2b changes the mockups accordingly.

| Group | Findings | What the review found | Recommendation |
|---|---|---|---|
| **A. The version boundary** | UX-01, UX-02, UX-03, UX-04 (Blocker), UX-23 | The mockups draw the whole product. In this version, Upload, Review Decision, Transfer Decision, Scheduled Fetch and Bank Consent entry points look usable but are not, and "Written via Kestrel Checking" (UX-04) suggests money already in YNAB. | Accept, by **adding** this version's states rather than removing the full-product ones, which stay the target of later initiatives (decision UX-D1). Each added state is read-only: it shows the reason, says the action comes in a later version, that nothing is uploaded and that no action is needed now. UX-04's wording is fixed in both. |
| **B. Situations no state shows yet** | UX-05 (A19), UX-06 (A20), UX-07 (A21, Blocker), UX-09 (fetch period, Blocker), UX-13 (first YNAB read failed, one Budget failed), UX-18 (successful but empty), UX-19 (Fetch Cooldown with one Bank Account read) | Each is specified but not drawn. UX-09 also found the fetch period still drawn at 60 days, with no editor. | Accept all. |
| **C. Times and balances** | UX-08 (Blocker), UX-25 | "Read 13:20" does not say whose time it is; the Account Balance's reported time, the Fetch time and the YNAB read time are not told apart; some Bank Accounts lack their own Source Health and Freshness. | Accept: "Bank balance as of …" (reported time, S-T-04), "Budget Account balance read …", and per Bank Account health. |
| **D. Answer in place (NFR-023)** | UX-10, UX-11, UX-12, UX-20, UX-21, UX-22, UX-24 | The Overview's Fetch all, the SimpleFIN page, the YNAB read and Recent Fetches lack running, queued, result or failure states at their own control; some saved values disagree with their row (UX-22); a credential refusal leads to a page that says Accepted (UX-21); some notices name no next action (UX-24). | Accept UX-10, -11, -12, -20, -21, -22. UX-24: accept for the outdated Account Balance (in scope); the damaged-journal and backup notices are outside this initiative and become an issue (decision UX-D3). |
| **E. Understanding statuses and Transfers** | UX-14, UX-15, UX-16, UX-17 (Minor), UX-26 (Minor), UX-27 | New, Match For Typed Entry and Already In Budget App lack a reachable explanation; the date statuses do not name their boundary or say why the fetch period does not change eligibility; a Transfer's two sides are not shown; finding this week's Bank Transactions means scanning groups; a few words differ from the glossary ("refreshing", "imported", "waiting"); the counts on the Transfers entry cannot be reconciled. | Accept UX-14, -15, -16, -26, -27. UX-17: a chronological list per Bank Account; no date filter (decision UX-D2). |

**Decisions for the operator at the UX gate:**

| ID | Question | Recommendation | Operator's answer |
|---|---|---|---|
| UX-D1 | Version boundary (group A): add read-only states for this version next to the full-product states, or redraw the full-product states? | Add. The full-product states are the approved target of the Upload and Review initiatives; redrawing them would lose that specification. | Agreed (2026-10-28). |
| UX-D2 | UX-17: a date filter on Bank Transactions now? | No: a chronological list now, the filter as a `spec-gap` issue for UC-008. | No filter and **no issue**: it is not a requirement; the operator raises it as one if they want it (2026-10-28). The chronological list stays, as a drawing choice of UC-008 step 5. |
| UX-D3 | UX-24's damaged-journal and backup notices: now or later? | Later, as a `defect` issue: they are existing screens outside this initiative's journeys. | Agreed (2026-10-28): issue #26. |
| UX-D4 | UX-21 and UX-22 concern screens already built in construction (credentials, Fetch Cooldown); the app may share the mockups' gaps. Check and fix the app in this initiative? | Yes: Phase 6 checks those screens against NFR-023 and fixes what the app shares; the mockups are fixed in Phase 2b. | Agreed (2026-10-28). |

## 7. Plan closed

Filled in by Phase 9: date, what shipped, specification changes, the issue numbers of the work left open, and the operator's verdict.
