# Plan: Build-out of the rest of Slussen

**Initiative (AGENTS.md §6):** build everything of the specification that is not built yet, in one autonomous run without operator stops: the screens for what exists, Review Decisions and Transfer Decisions, Uploads, Transaction File import, the Scheduled Fetch, Enable Banking with Bank Consent, the remaining settings and the Decision Journal export.
**Target repo / branch:** `flylasse/slussen`, branch `build-out` (from `main` at `b150a1b`, VERSION 0.1.15), one draft PR (#37) for the whole run.
**Specification (input):** `docs/` as on `main`; all use cases are Approved. The five read-only surveys of 2026-11-26 are in `docs/reviews/build_out_sweep_A..E_*.md`: what is unbuilt, the proposed phases, the questions and the safe guesses of each area.
**Method:** AIUP as in `docs/transactions_plan.md`, with the skills read from `vendor/aiup-marketplace/`, and with the deviations of §2, which the operator chose on 2026-11-26.
**Status:** In progress. The operator answered §6 on 2026-11-26 and started the run.

## 0. Resume here (state as of 2026-11-26)

- The transactions plan is built up to Phase 7b (PR #36 merged). Its Phase 7c (the screens) is Wave 1 here; its acceptance (Phase 8) and close (Phase 9) fold into this plan's end.
- Device run by the operator on `main` (2026-11-26, Galaxy, Android 17): `:persistence` 49 of 49; `:app` 22 of 22 after two stale tests were fixed (24235f0).
- Five surveys done (`docs/reviews/build_out_sweep_*.md`).
- The operator answered the questionnaire on 2026-11-26 (§6): the defaults, with a sample for Q-01 and "no" to Q-04. The run started.
- Run log (the last commit named in a row passed the orchestrator's gate):

| Phase | Commits | Gate | Review | Notes |
|---|---|---|---|---|
| A-1 | 695249e, 201b7a3 | green at 201b7a3 | `docs/reviews/build_out_reviews/A-1.md`: 10 findings fixed, 3 to §6 (G-01..G-03) | A regular expression in the migration test overflowed the stack at random in one gate run; fixed in 16bf178 |

- Next: phase A-2.

## 1. Scope

Everything the surveys list as unbuilt, in the waves of §3. Not in scope: a requirement the specification does not have (for example a retention setting for Bank Transactions), and anything only a device, a real Bank Consent or a live YNAB test can show; those are the end checklist of §7.

Live defects the surveys found, fixed in the wave named:
- The Overview and Budget tab still say that nothing is compared and the counts stay at 0, which is false since Phases 7a and 7b (Wave 1, first phase).
- `ImportStatus` looks up a Bank Consent and a Bank's Back-off by the wrong Bank of a Bank Account (Wave 6).
- UC-009 A14 step 4, "detect the Transfers again", is not done (Wave 2).
- Issue #33: a storage failure at the import switch or the credential removal crashes the app (Wave 1).
- `state_version` is raised by three storage operations only, so the check before an Upload writes could pass over a change (Wave 4, storage).

## 2. How this plan is executed

The operator's choices of 2026-11-26, each a deviation from AGENTS.md §6 or from the transactions plan's §2, recorded in §5:
1. **No operator stops after §6.** One questionnaire before the run; then the run goes to its end.
2. **One branch, one draft PR.** A builder's work is committed and pushed when it reports, so that nothing is lost with the container; the orchestrator's gate and the review follow, and what they change is a further commit of that phase. No PR and no approval per phase. §0 names the last commit the orchestrator's gate passed.
3. **Specification changes are proposed, not approved, before their code.** Each is written as an S-B-nn row in §6 and in the specification, in its own commit before the code that follows it. The operator approves or rejects them together at the end; a rejection means rework.
4. **Best guess.** Where the specification is silent or two parts disagree, the builder takes the most conservative reading: never a duplicate in the Budget App, never a write the Operator did not confirm, nothing lost. The guess goes into §6 with what was built and what another answer would cost.
5. **A phase that stays red is parked.** One fix round by the orchestrator; if its gate is still red, the phase's commits are reverted, recorded in §0, and the run goes on with the phases that do not depend on it.

Unchanged: one sub-agent per phase with a clean context and a written prompt; tests first; the orchestrator runs every gate itself (`gradlew check` with the three assemble tasks, `lint_specs.py --strict`, `check_project_rules.py`); no private data reaches an agent; tests use invented data and local test servers only, never `-PincludeLive`; no `release` build.

Roles and models (Centaur: the reviewer is never the builder's model):
- **Orchestrator:** this session. Writes prompts, runs gates, reads each diff, commits, keeps §0 and §6.
- **Builder, rules, storage and the Upload protocol:** the strongest model.
- **Builder, screens that follow a mockup:** a mid model.
- **Reviewer, after each phase:** read-only, another model than that phase's builder; its findings are fixed in the same phase or recorded in §6.
- **Coverage audit (`uc-coverage-layered`):** read-only, at the end of each wave.

Device and Windows checks do not stop the run. At the end of each wave §0 names the commit that is ready for `connectedDebugAndroidTest` and the Windows gate; the operator runs them when convenient, and the run goes on meanwhile.

## 3. Waves and phases

Schema: `slussen.db` gets one version 6 for every remaining table (`review_decision`, `upload_plan`, `eligible_transaction`, `upload_plan_item`, `sent_record`, `transfer_counterpart`, `transaction_file`, and an optional side of `transfer`), written once in Wave 2, so that the run adds one Migration, not four. `device.db` gets version 2 for Bank Consents in Wave 6. A later need is a further version.

| Wave | Phases (survey ids) | Use cases | Writes |
|---|---|---|---|
| 0 | The answers recorded (§6). Each later wave starts with its own specification commit, from its survey's safe guesses and the answers (the surveys' B0, C0, D0, E0) | all | `docs/` |
| 1 | Truthful build state and the YNAB line (A-1); Bank Account screen (A-2); Transfers, read-only (A-3); Recent Fetches detail (A-4); #33 and #26 (A-5) | UC-008, UC-003 A1 | `:core` status, `:app` |
| 2 | Storage version 6 (B1, C1, D1 together); Review Decisions (B2); Transfer Decisions (B3); mapping changes (B4, B5); the decision screens (B6) | UC-004, UC-005, UC-009, UC-003 BR-008..BR-010 | `:core`, `:persistence`, `:testing`, `:app` |
| 3 | All scalar settings and the re-status (A-6); description lists (A-7); Decision Journal control (A-8); the export (A-9) | UC-010, UC-011 | `:core`, `:persistence`, `:app` |
| 4 | YNAB writer and `readTransaction` (C2); the Comparison with Sent Records (C3); First Upload Matching (C4, A-10); coordinator and preparation (C5a); confirmation and writing (C5b); the Upload screens (C6); the evidence kit (C7); the tails that waited for Uploads (B7) | UC-006, UC-003, UC-005 A6, A7, UC-008 A9 | `:budgetapp-ynab`, `:core`, `:persistence`, `:app` |
| 5 | Transaction File import in the core (D2); the HSA conversion (D3); its screens (D4); Scheduled Fetch in the core (D5); worker, Fetch Schedule and notification (D6); leftovers (D7) | UC-002, UC-001 A12, A14, BR-013, BR-014 | `:core`, `:fileformat-hsa-csv`, `:app`, `build-logic` |
| 6 | Consent storage, `device.db` version 2 (E1); consent in the core (E2); the Enable Banking consent plug-in (E3); screens and the return (E4); the Enable Banking Fetch (E5); Unattended Access (E6) | UC-007, UC-001 A4, A9, A13 | `:core`, `:persistence`, `:provider-enablebanking`, `:app` |
| 7 | Start Over (A-11); the `-this-build` states removed (#27); requirement statuses derived; the question list and the end checklist | UC-010 A12, all | `docs/`, `:core`, `:persistence`, `:app` |

About 38 phases. The order inside a wave is the one written; a wave starts when the one before it is green or parked.

## 4. Phase prompts

Written by the orchestrator when a phase is dispatched, from the survey of its area and §6, and recorded here with the phase's commit.

## 5. Methodology evaluation log

| # | Phase | AIUP step | What happened | Deviation? |
|---|---|---|---|---|
| 1 | Draft | Initiative intake (AGENTS.md §6) | The operator chose an autonomous run: one questionnaire, no stops, one PR, specification changes proposed with their code and approved together at the end, best guesses recorded (§2). | Yes: AGENTS.md §1 ("change the specification first"), §6 (Initiative: approved before any code phase) |
| 2 | Draft | none (surveys) | Five read-only sub-agents surveyed the unbuilt specification against the code before any phase, to find the questions early. | No AIUP step covers it |

## 6. Questions, specification changes and guesses

### Questionnaire, 2026-11-26

Answered by the operator on 2026-11-26: "defaults, with these clarifications" (Q-01, Q-04, Q-07).

Facts only the operator has:

| ID | Question | Default | Answer |
|---|---|---|---|
| Q-01 | **HSA CSV.** No sample or description of the HSA's export exists in the repository. Import Identifiers are permanent, so a guessed layout would make earlier files come in again as duplicates. A sample (header and a few invented lines in the exact layout) or a description? | Without one: the import is built against an invented format, and the HSA conversion rejects every file as "not built yet". | A sample was given: the format below. |
| Q-02 | **Enable Banking return address.** Where the Bank's sign-in sends you back, registered in your Enable Banking application: a custom address (`slussen.app://consent`, and `slussen.app.debug://consent` for debug builds), an https link on a domain of yours, or pasting the address back by hand? | The custom address, with paste-back as the fallback. | Default. |
| Q-03 | **Country and Banks through Enable Banking.** Which country or countries? | One country, the chooser starting at the phone's. | Default. |
| Q-04 | **Transfers in YNAB before the Bank books them.** Do you type or schedule transfers in YNAB ahead of time, and is a tracking or loan account mapped? A linked transfer sent onto a hand-typed one could make a second pair. | Treated as yes: a Transfer that resembles a Typed Entry waits as Needs Review until a live test proves what YNAB does. | No: "I don't pre-type anything; Slussen will be the main source for YNAB." So a Transfer is sent as a linked transfer as the specification says, without waiting; a live test case for it joins the end checklist (§7). |

**Q-01, the format (from the operator's sample; the sample itself holds real account data and is neither in the repository nor given to any agent):** the history export of Fidelity for a health savings account, one file per Bank Account.
- UTF-8 with a byte order mark, LF line ends. Two empty lines, then the header line, then one line per transaction, newest first, then an empty line and a legal text of several lines that ends with `Date downloaded MM/DD/YYYY hh:mm am`.
- Header, 13 columns: `Run Date,Action,Symbol,Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date`.
- `Run Date` is `MM/DD/YYYY`. `Amount ($)` is in US dollars with a decimal point and at most the decimals it needs (`-393`, `-316.5`, `298.08`); a negative amount is quoted (`"-25.57"`), an outflow. An empty field is `""`. `Cash Balance ($)` is the balance after the line. There is no transaction identifier and no currency column.
- `Action` carries what happened and with whom, and ends with ` (Cash)`. Invented lines in the exact layout:

```text
11/25/2026,DEBIT CARD PURCHASE HARBOR PHARMACY (Cash),"",No Description,Cash,"",0,"","","","-12.34",1987.66,""
11/23/2026,PARTIC CONTR CURRENT PARTICIPANT CUR YR (Cash),"",No Description,Cash,"",0,"","","",150,2000,""
10/30/2026,REINVESTMENT EXAMPLE GOVERNMENT CASH RESERVES (EXMPX) (Cash),EXMPX,EXAMPLE GOVERNMENT CASH RESERVES,Cash,1,4.5,"","","","-4.5",1850,""
10/30/2026,DIVIDEND RECEIVED EXAMPLE GOVERNMENT CASH RESERVES (EXMPX) (Cash),EXMPX,EXAMPLE GOVERNMENT CASH RESERVES,Cash,"",0,"","","",4.5,1850,""
```

- The last two lines show the one trap: a dividend raises the cash balance, and its reinvestment into the cash fund on the same day does not lower it (the balance column is the same on both lines). Guess, for §6's end list: a line whose amount does not move the cash balance (a `REINVESTMENT` into the cash position) is not a transaction of the account and is left out; the dividend is kept. Buying or selling another fund was not in the sample.
- Guess, for the derived reference (the file has no identifier): run date, action, symbol, description and amount; identical lines are told apart by their count, never by the cash balance, which changes when an earlier line is added later.

Choices, each with a default:

| ID | Question | Default | Answer |
|---|---|---|---|
| Q-05 | **Enable Banking gives no Stable Reference (issue #7).** Every Enable Banking Bank Transaction is then Needs Review and needs your Review Decision, and every Transfer with such a Transfer Leg waits. Keep it, trust the reference of named Banks, or stop treating a missing reference as doubtful? | Keep it now; trust a Bank's references only after you have seen real ones. Changing it after Uploads changes Import Identifiers and risks duplicates. | Default. |
| Q-06 | **Read YNAB before an Upload Plan.** As written, the plan is prepared from the last read and the confirmation refuses when YNAB changed since, which is nearly always. Read and compare first when you open "Review Upload Plan"? | Yes. | Default. |
| Q-07 | **One side already uploaded as an ordinary transaction, then the pair is detected.** As written the other side is in no plan and the decision is locked, for ever. | A side already written as ordinary is no Transfer Leg; the other uploads as ordinary. | Default: "not a common case; his mess to clean up". |
| Q-08 | **One transaction makes YNAB refuse a whole Budget Account's list.** | The YNAB part resends them one by one in the same Upload, so only the refused one is Rejected. | Default. |
| Q-09 | **Re-upload: once or standing.** After a Re-upload is deleted again in YNAB, is it sent again without a new decision? | Once: a Re-upload is used up by its send. | Default. |
| Q-10 | **The other side of an uploaded Lonely Transfer arrives late**, after the transfer pairing days. As written it would be New, a duplicate beside the Transfer Counterpart. | Needs Review when it resembles a Transfer Counterpart that is not settled. | Default. |
| Q-11 | **A decided Transfer when its ground changes** (Funded By, Transfer Partner, a description, a Bank Account's type, an Account Mapping). | The decision is kept unless the Transfer's own sides are no longer valid; then it is dissolved, journaled and detected again. | Default. |
| Q-12 | **A confirmed Lonely Transfer, not uploaded yet,** when its grace period passes or its other side arrives. | The decision holds, and an arriving side joins it. | Default. |
| Q-13 | **Choosing another Budget Account (UC-005 A3) when the matching transaction already belongs to another Transfer.** As written it becomes a Lonely Transfer, which would write the same money twice. | It is taken from a Transfer that is only Proposed, and refused with the reason when that Transfer is decided. | Default. |
| Q-14 | **Scheduled Fetch and notification.** They need four permissions (network state, wake lock, start after boot, show notifications), so NFR-022 changes. Twice a day at roughly even spacing, starting as soon as the build is installed; the notification permission is asked once on the Overview. | All of it as described. | Default. |
| Q-15 | **Documentation hosts.** The builders know Enable Banking's and YNAB's API from memory only. Allow this environment to read `enablebanking.com` and `api.ynab.com` (their documentation; no agent holds a credential)? | Yes. | Default. |

Smaller choices taken without asking, to be confirmed at the end: the Decision Journal export is one JSON file covering the Budget App from the oldest journal entry on; the backup-size warning says that no action is possible (the specification has no retention rule); Start Over is built last, on the device only, and deletes everything; the screens are built in their permanent form with decision controls added in their wave; the evidence of issue #6 is the operator's own checklist (§7), with no switch in the app; attended access sends no public IP address looked up at a third party. The safe guesses of the surveys (about 48) are taken as written there.

### Guesses and open points for the end list (G-nn)

| ID | Phase | What was assumed or left | What another answer would cost |
|---|---|---|---|
| G-01 | A-1 | A failed read of a Budget is kept in memory only: after the app is killed the Overview no longer says that the Budget was not read; its "statuses as of" stays right. | A column in `slussen.db` (a schema version) and its write in O-4. |
| G-02 | A-1 | The result on the YNAB line and its remedy clear at the next tap, as the Fetch result line does; after new credentials are entered the refusal still shows until then. | A small change in two ViewModels. |
| G-03 | A-1 | An outdated balance of a file-imported Bank Account without a balance Data Provider would say "Fetch again for a newer one". Not reachable until Wave 5. | A text and a condition, with Wave 5. |
| G-04 | A-1 | The read shows "Reading YNAB · no Bank Fetch", not the mockups' "Budgets: n of m": the coordinator reports no progress within a read. | A progress report from the Comparison through the coordinator. |
| G-05 | A-1 | The remedy of an outdated balance is a line of text, not a button: the Budget tab has no Fetch control yet. | With the Budget tab's Fetch (UC-001's trigger per Budget, Wave 5). |
| G-06 | A-1 | Times show as HH:mm today, MM-dd HH:mm earlier in the year, the full date before that, as the mockups do. | Text only. |

### Specification changes (S-B-nn)

None yet.

## 7. End checklist (the operator)

Filled as the run goes: the Windows gate, `connectedDebugAndroidTest`, evidence E-01..E-12 (issue #6) with a throwaway YNAB Budget before the first real Upload, a real Bank Consent, acceptance on the phone.
