# Plan: Bank Transactions and the Comparison

**Initiative (AGENTS.md §6):** Fetch the Operator's booked Bank Transactions from SimpleFIN, store them, read YNAB, and give every Bank Transaction a Transaction Status, so that each Budget shows which transactions are new, already in YNAB, or need a decision. Nothing is uploaded yet: Uploads (UC-006) are the next initiative.
**Target repo / branch:** `flylasse/slussen`, branch `transactions-plan` (from `main` at `b5ace27`, VERSION 0.1.4). One PR at the end, or one per gate if the operator prefers (O-T-08).
**Specification (input):** `docs/vision.md`, `docs/requirements.md`, `docs/glossary.md`, `docs/entity_model.md`, `docs/use_cases/UC-001..UC-014`, ADR-001..ADR-008, `docs/mockups/`. All use cases are Approved; UC-014 is Implemented.
**Method:** as in the closed construction plan (`docs/archive/construction_kickoff_plan.md` §1): AIUP Marketplace, with `aiup-core` 3.0.0's `use-case-test`, `use-case-implement` and `uc-coverage-layered` read from the branch `feat/layer-plugins` of Marketplace PR #38 while it is open (issue #18). Every deviation goes into §5.
**Status:** Draft, for the operator's review. No phase starts before the operator approves this plan and answers §6's open questions.

## 0. Resume here (state as of 2026-10-28)

- The plan is drafted. Next: the operator reviews it and answers O-T-01..O-T-08 (§6).
- After that, Phase 1 (specification changes) starts.

## 1. Scope

### Use cases and requirements this initiative builds

| Use case | Part built here | Part left for later |
|---|---|---|
| UC-001 Fetch Transactions | Steps 7-10 (keep booked, non-zero transactions; store them; record them in the Decision Journal; compare), A8-A10 as far as SimpleFIN reports them, A17 (Unreadable Transactions), BR-002, BR-003, BR-009 (fetch period), BR-016 | A4, A9, A13 (Bank Consents, with Enable Banking); A12, A14, BR-013, BR-014 (Scheduled Fetch and New Transaction Notification) unless O-T-04 says otherwise |
| UC-003 Compare With the Budget App | Steps 1-3 and 5-7: read YNAB's transactions and changes, the order of BR-001, Eligible Transactions, the Decision Journal, A1-A3, A7, BR-001..BR-007, BR-011..BR-016, BR-018, BR-020 | Step 4 and the Transfer statuses (UC-005) unless O-T-01 says otherwise; A4-A6, BR-008..BR-010, BR-017, BR-019, BR-021, which need Review Decisions (UC-004), Sent Records (UC-006) or First Upload Matching |
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

- After a Fetch of SimpleFIN, each Budget tab lists its Bank Transactions with a Transaction Status: New, Already In Budget App, Match For Typed Entry, Needs Review (for example an Unreadable Transaction), Before Start Date, Too Old, and so on.
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
| 2 | UX review (report only) of the screens this initiative changes: Overview, Budget tab, Bank Account, Fetch states, the YNAB read, the Settings entries the Fetch result points to | §6 UX-nn | a different model from the authors, or an external CLI run by the operator (O-T-05) | every finding has an ID, a screen and state, what the Operator would not understand or could not do, and a proposal; only this plan changed | `docs: phase 2 - UX review findings` |
| 2b | Mockups revised from the UX gate's decisions | `docs/mockups/*.html` | `inherit` | `check_mockup_links.py` and `check_project_rules.py` exit 0; every accepted UX-nn is resolved in a named state | `docs: phase 2b - mockups revised after the UX gate` |
| — | **UX gate: the orchestrator stops** | operator walks the mockups | operator | operator says "approved" | — |
| 3 | Device baseline (#5): `gradlew connectedDebugAndroidTest` on an emulator (API 26 and current) or the phone, then E-13 with `adb shell bmgr` | results into §6 and issue #5 | operator | every instrumented test passes, or each failure becomes an issue | — (results recorded by the orchestrator in Phase 4's commit) |
| 4 | Data Provider contract and SimpleFIN: `Read` carries each transaction and the balance time (#16.1); SimpleFIN maps `transactions`, `pending`, `balance-date` and `errlist` (#11) | `:plugin-api`, `provider-simplefin`, contract tests | `inherit` | `gradlew check` passes; the contract tests cover a pending item, a zero amount, an item without a date, `con.auth` and `act.failed` | `feat: phase 4 - transactions in the Data Provider contract and SimpleFIN` |
| 5 | Storage: `slussen.db` version 4 with `BANK_TRANSACTION`, `UNREADABLE_TRANSACTION` and `BUDGET_APP_TRANSACTION` (entity model), and a hand-written Migration from version 3 (S-39) | `:persistence`, schema JSON, `SlussenDatabaseMigrationIT` | `inherit` | `gradlew check` passes; the S-39 check accepts version 4; the Migration IT covers versions 1, 2 and 3 to 4 (run on a device in Phase 8 at the latest) | `feat: phase 5 - schema version 4 for Bank Transactions` |
| 6 | UC-001 steps 7-10 in the core, A8, A10, A17, #8, #9, #16.3b; then the screens | `:core` `fetch`, `:app` | `inherit` | `gradlew check` and `:app:assembleDebug` pass; every in-scope BR and flow of §1 has a test that names it; `uc-coverage-layered` matrix in the phase report | `feat: phase 6 - Bank Transactions from a Fetch` |
| 7 | UC-003: YNAB `readChanges`, the stored copy, the Comparison in the order of BR-001, Eligible Transactions, the Decision Journal, the "⟳" read; the Budget tab and Bank Account screen as the UX gate approved them | `budgetapp-ynab`, `:core` (a comparison package, ADR-001 §7), `:app` | `inherit` | as Phase 6, and no Comparison writes to YNAB (BR-015, a contract-test property) | `feat: phase 7 - the Comparison` |
| 8 | **Acceptance: operator, on the phone** | release build 0.1.x → this build over it; a Fetch of SimpleFIN; YNAB read | operator | every Bank Account shows its Bank Transactions with plausible Transaction Statuses; nothing reached YNAB; no stored item lost in the upgrade; the instrumented Migration test passes on the phone | operator's verdict into §6 |
| 9 | Close: §7, requirement statuses, open work turned into issues, plan to `docs/archive/` | this plan, `docs/requirements.md`, issues | `inherit` | `lint_specs.py --strict` and `check_project_rules.py` exit 0; §7 lists issue numbers, not open work | `docs: phase 9 - close the transactions plan` |

## 4. Phase prompts

Written for each phase when the phase before it is accepted, so that each prompt carries what the gates decided. Every prompt starts with: read `AGENTS.md`, this plan's §1, §2 and §6, and the use cases and ADRs the phase names; follow `use-case-test` and then `use-case-implement` for code phases; return Clarified / Assumed / Open.

## 5. Methodology evaluation log

| # | Phase | AIUP step | What happened | Deviation? |
|---|---|---|---|---|
| 1 | Draft | Initiative intake (AGENTS.md §6) | The plan names the use cases, requirements and issues it takes on, and its first phase writes the specification changes. | No |

## 6. Findings, specification changes and open questions

### Open questions for the operator, before Phase 1

| ID | Question | Recommendation |
|---|---|---|
| O-T-01 | **Transfers.** Without UC-005, a payment from your checking account to your credit card shows as two New Bank Transactions, one on each Budget Account. Build the detection of Transfers (UC-005 BR-001, BR-002, BR-010) in this initiative, or leave it for the next one? | Leave it for the Upload initiative, which needs it before anything is sent, and add a build-state line: "Transfers are not detected in this build yet; both sides show as New". This initiative is already large. |
| O-T-02 | **Review Decisions.** Needs Review and Unreadable Transactions will appear but cannot be decided on (UC-004). Acceptable for this initiative? | Yes, with a build-state line. Nothing is uploaded, so nothing is lost by waiting. |
| O-T-03 | **Device evidence early.** Run the instrumented tests (#5) as Phase 3, before schema version 4, rather than at acceptance? | Yes. The Migration from 3 to 4 is the riskiest change here, and the tests of versions 1 and 2 have never run on a device. |
| O-T-04 | **Scheduled Fetch** (UC-001 A12, the New Transaction Notification). In or out? | Out. Starting a Fetch yourself is enough until Uploads exist. |
| O-T-05 | **Who does the UX review?** | A model from another vendor through its CLI, run by you, as for the architecture review: a reviewer trained differently has different blind spots. A fresh sub-agent on a different tier is the fallback. |
| O-T-06 | **Usability in the specification.** AIUP's requirement types have no natural place for usability (your verdict). Add usability requirements, for example an NFR "every action shows its state within 1 second and its result in place" (P9-16)? | Yes: one or two measurable NFRs in Phase 1, which the UX review then checks against. The rest stays in the mockups. |
| O-T-07 | **Fetch period on the first Fetch.** UC-001 BR-009 asks for the last 60 days. Your Start Dates exclude older transactions anyway (UC-003 BR-018, A7), but every older one is still stored. | Keep 60 days; transactions before the Start Date are stored and shown as Before Start Date, so you can check the cut-off. |
| O-T-08 | **PR shape.** One PR at the end (as construction), or one per gate (specification, mockups, code)? | One per gate: smaller reviews, and `main` keeps the approved specification even if the code takes longer. |

### Specification changes (S-T-nn)

None yet: Phase 1 writes them.

### UX review findings (UX-nn)

None yet: Phase 2 writes them.

## 7. Plan closed

Filled in by Phase 9: date, what shipped, specification changes, the issue numbers of the work left open, and the operator's verdict.
