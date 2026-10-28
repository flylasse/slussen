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
- Next: Phase 2. The operator runs the external UX review with the prompt in §4 on the branch `transactions-ux`.

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

- After a Fetch of SimpleFIN, each Budget tab lists its Bank Transactions with a Transaction Status: New, Already In Budget App, Match For Typed Entry, Transfer Leg, Guessed Transfer, Needs Review (for example an Unreadable Transaction), Before Start Date, Too Old, and so on.
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

Written for each phase when the phase before it is accepted, so that each prompt carries what the gates decided. Every prompt starts with: read `AGENTS.md`, this plan's §1, §2 and §6, and the use cases and ADRs the phase names; follow `use-case-test` and then `use-case-implement` for code phases; return Clarified / Assumed / Open.

## 5. Methodology evaluation log

| # | Phase | AIUP step | What happened | Deviation? |
|---|---|---|---|---|
| 1 | Draft | Initiative intake (AGENTS.md §6) | The plan names the use cases, requirements and issues it takes on, and its first phase writes the specification changes. | No |
| 2 | 1 | Specification changes (`requirements`, `use-case-spec`, `entity-model`) | The orchestrator wrote Phase 1 itself instead of dispatching a sub-agent (§2): every change had been designed with the operator in the same session (issues #8, #9, #11, #16, O-T-06, O-T-07), and a sub-agent would have needed that whole conversation as its prompt. The semantic `/spec-review` was not run; the two linters were. | Yes |
| 3 | 1 | `requirements` (NFR) | A usability requirement (NFR-023, O-T-06) is written as an NFR with the category Usability. The project rule on numbers (AGENTS.md §2) refused "within 1 second", so the measure is "before the action sends any request or waits for anything". | No |

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

### UX review findings (UX-nn)

None yet: Phase 2 writes them.

## 7. Plan closed

Filled in by Phase 9: date, what shipped, specification changes, the issue numbers of the work left open, and the operator's verdict.
