# Plan: Bootstrap the AIUP Specification Core for Slussen

**Initiative:** Mine `ynab-import-android` into a living AIUP specification for Slussen, before any Slussen code exists.
**Target repo / branch:** `C:\work\slussen`, branch `initial-aiup-scaffolding`, one PR at the end.
**Evidence (read-only):** `C:\work\ynab-import-android` — code and tests, `docs/archive/` spec/plan pairs, every merged PR description.
**Method:** AIUP Marketplace `aiup-core` at commit `05af8163838df704620ebe1121cd6019dbce276c` (2026-10-13), local clone `C:\work\marketplace`; book corpus `C:\work\book-context-martinelli-spec-driven-dev\corpus`. If `git -C C:\work\marketplace rev-parse HEAD` differs, stop and ask.
**Gate tool:** `python tools/lint_specs.py` (wraps the Marketplace `spec_lint.py`).
**Phase 0 evidence (2026-10-13):** merged PRs = 81 (`scratch/pr_history.md`); archived specs = 11 (`docs/archive/*spec*.md`; 24 files in `docs/archive/` in total); `ynab-import-android` commit read = `46efbf004a98d802a30aba7dbd7601624bd1f19f` (`origin/main` after fetch, same as local HEAD, read from disk).

---

## 1. How this plan is executed (global prompt for the orchestrator)

The operator starts **one orchestrator session on the strongest model** and tells it to execute this plan. The orchestrator:

1. **Has two operator stops.** It runs Phases 0–1 and **stops at the vision gate** (Phase 1's end). After the operator has reviewed and corrected `docs/vision.md` and says to resume, it runs Phases 2–6 without the operator and **stops at Phase 7**, the approval gate. Phase 8 runs only after the operator has approved. The orchestrator never moves past a stop on its own.
2. **Dispatches each phase to its own sub-agent** with a clean context, the phase's prompt from §4 (verbatim, plus the paths it needs), and the model named in the phase table. `inherit` = the orchestrator's model. The orchestrator does not write the phase's artifacts itself.
3. **Runs the phase gate itself** after the sub-agent returns. It never accepts a sub-agent's report that the gate passed.
4. **Makes exactly one commit per phase** after the gate is green, with the message given in the phase table, and pushes it before starting the next phase. If the gate is red: one re-dispatch of the same phase with the gate output (on `inherit` if the phase ran on a lower tier, recorded as an upward re-dispatch), then one fix commit at most. Still red → stop and report. Never skip a phase, never pull work forward from a later phase.
5. **Sub-agents cannot ask the operator.** Every Marketplace skill tells the agent to ask clarifying questions; in this run each sub-agent takes the skill's recommended option, and returns its **Clarified / Assumed / Open** items (the skills' own reporting format). The orchestrator copies them into §6. Open items about the vision are answered at the vision gate; the rest at Phase 7.
6. **Updates §5 (evaluation log) and §6 (drift and open questions) in the same commit as the phase.** These are the main output of the pilot, not paperwork.
7. Does not touch `ynab-import-android`, `marketplace` or the book repo, and never commits anything under `scratch/`.

**Why these model choices.** Phase 0 is mechanical extraction with no interpretation, so it runs on a fast tier, with an automatic upward re-dispatch if its gate fails. Phases 1–4 and 6 are synthesis across three sources where correctness is the point, so they run on the strongest tier. Phase 5 is the adversarial review: it runs on a **different model** from the authoring phases (Centaur's cross-model review rule), and only reports, as `/spec-review` requires.

## 2. Prerequisites (operator, before kickoff)

- [x] GitHub CLI installed and authenticated with read access to `flylasse/ynab-import-android`. Verified 2026-10-13: gh 2.102.0, account `flylasse`, scope `repo`, 81 merged PRs visible. **gh is not on PATH in shells started before the install** — agents call it by full path: `& "C:\Program Files\GitHub CLI\gh.exe"`.
- [x] `python tools/lint_specs.py --self-test` prints `self-test passed` (verified 2026-10-13).
- [ ] `git -C C:\work\slussen status` clean on `initial-aiup-scaffolding`.

## 3. Phase table

| Phase | What | Writes | Model | Gate (orchestrator runs it) | Commit message |
|---|---|---|---|---|---|
| 0 | Extract evidence | `scratch/pr_history.md`, `scratch/source_inventory.md`; §5 and plan header counts | `flash` | PR count in dump = `gh` merged-PR count; inventory lists entry points, domain classes, persistence, tests, archived specs; `lint_specs.py` exits 0 | `docs: phase 0 - extract predecessor evidence` |
| 1 | Vision draft | `docs/vision.md` | `inherit` | follows the Marketplace template's sections; `lint_specs.py` exits 0 | `docs: phase 1 - vision draft for operator review` |
| — | **Vision gate — orchestrator stops here** | operator corrects `docs/vision.md` by hand | operator | operator says "resume" | operator's own commit (`docs: vision - operator corrections`), or none if accepted as drafted |
| 2 | Requirements and glossary | `docs/requirements.md`, `docs/glossary.md` | `inherit` | `lint_specs.py` exits 0 (`FR_UNCOVERED` warnings expected) | `docs: phase 2 - requirements catalog and glossary` |
| 3 | Entity model | `docs/entity_model.md` | `inherit` | `lint_specs.py` exits 0 | `docs: phase 3 - entity model` |
| 4 | Use case diagram and specifications | `docs/use_cases.puml`, `docs/use_cases/UC-*.md` | `inherit` | `lint_specs.py --strict` exits 0 | `docs: phase 4 - use case diagram and specifications` |
| 5 | Adversarial spec review (report only) | findings into §6 | `pro` | every finding has an ID and a location; no file under `docs/` other than this plan changed | `docs: phase 5 - adversarial spec review findings` |
| 6 | Dispositions | `docs/**` fixes; disposition per finding in §6 | `inherit` | `lint_specs.py --strict` exits 0; every Phase 5 finding has a disposition (fixed / rejected with reason / Open for operator) | `docs: phase 6 - review dispositions` |
| 7 | **Operator approval — orchestrator stops here** | operator answers §6, sets `**Status:** Approved` | operator | — | operator's own commit |
| 8 | Close and archive | plan → `docs/archive/` | `inherit` | `lint_specs.py --strict` exits 0; §7 filled | `docs: phase 8 - close bootstrap plan` |

Phase 4 combines the diagram and the specifications on purpose: `spec_lint.py` reports a use case in the diagram without a specification as an `ERROR` (`SPEC_MISSING`), so a diagram-only commit cannot pass a gate.

## 4. Phase prompts

Every phase prompt starts with: *"Read `C:\work\slussen\AGENTS.md` and this plan's §1. You are the executor for Phase N only. Write only the files listed for your phase. Return your Clarified / Assumed / Open items, and any place where you could not follow the skill exactly and why."*

### Phase 0 — Extract evidence (`flash`)

Read `C:\work\marketplace\aiup-core\skills\reverse-engineer\SKILL.md`, stage 1 (project discovery), for what to look for.

1. `& "C:\Program Files\GitHub CLI\gh.exe" pr list --repo flylasse/ynab-import-android --state merged --limit 500 --json number,title,mergedAt,body` → `scratch/pr_history.md`, one section per PR in number order: number, title, merge date, full body. No summarising. If `gh` fails or is unauthenticated, stop and report.
2. `scratch/source_inventory.md`: first run `git -C C:\work\ynab-import-android fetch origin`, then read the tree at `origin/main`. If `git -C C:\work\ynab-import-android diff --stat HEAD origin/main` is empty and `status` is clean (true on 2026-10-13: local `9068c74` has the same content as `origin/main` `46efbf0`), read the files from disk; otherwise use `git show origin/main:<path>` and never check out or modify the operator's local branch. List with paths: the Gradle modules; the user-facing entry points (screens, ViewModels, workers, receivers); domain classes and enums; persistence (Room entities, DataStore, files); the test directories with file counts; every file in `docs/` and `docs/archive/`. Lists and paths only, no interpretation.
3. In the plan header, record PR count, archived spec count, and the `ynab-import-android` commit read (`git -C C:\work\ynab-import-android rev-parse origin/main`). The evidence is `origin/main` after the fetch, not the current local branch. All later phases read the predecessor at that same commit.

### Phase 1 — Vision draft (`inherit`), then the vision gate

Read: book chapters `02` and `08` (the `init` step); the Marketplace template `C:\work\marketplace\docs\templates\vision.md`. Sources: `ynab-import-android/README.md`, `docs/archive/android_app_spec-v1.4.md` and the other archived specs (intent), `scratch/pr_history.md` (later decisions).

1. Write `docs/vision.md` to the template: what the system is and is not, who uses it, the scope boundary, what success looks like. Stay at the vision level — no requirements, no screens.
2. Return, besides the usual items, a short list of the **choices the draft made that the operator is most likely to want to change** (scope inclusions and exclusions especially). The orchestrator puts these in §6 under *Vision gate*.
3. **The orchestrator then stops** and tells the operator: the vision is committed, the questions are in §6, edit `docs/vision.md` directly and say "resume".

**At the vision gate (operator).** Read `docs/vision.md`, correct it by hand, commit (`docs: vision - operator corrections`) or accept as drafted, and say "resume". The operator's commit is the authoritative vision; Phase 2 reads it, not the draft. **For the evaluation:** in the book the vision is written by hand. Here it is drafted from evidence and corrected by hand before anything is derived from it. Operator's rationale (2026-10-13): the evidence is itself the operator's work — 80+ PRs and the archived specs were written or directed by the operator — so the draft is the operator's intent recovered, not an agent's invention. What the draft cannot know is what the operator wants to *change* in a rewrite; that is what the gate is for. On resume, the orchestrator records in §5 how much the operator changed (`git diff --stat` between the Phase 1 commit and the operator's commit, plus a one-line summary of the kind of change). That shows how good an evidence-drafted vision is.

### Phase 2 — Requirements and glossary (`inherit`)

Read: book chapter `03`; `C:\work\marketplace\aiup-core\skills\requirements\SKILL.md` and its references; `reverse-engineer/SKILL.md` stages 1–2. Sources: `docs/vision.md` (as corrected by the operator — the skill derives requirements from it), the archived specs (intent and rules as designed), `scratch/pr_history.md` (later decisions), the code where the specs are silent.

1. `docs/requirements.md` and `docs/glossary.md`, exactly in the `requirements` skill's format (FR as user stories, NFR measurable, constraints with Source where regulatory, Status column, glossary with synonyms to avoid). Requirement count follows from the evidence; there is no target number. Nothing outside the corrected vision's scope.
2. Where the archived specs, PRs and code disagree about a requirement, do not choose silently: write the requirement the code currently satisfies, and add a drift entry to the return (`FR-xxx: spec <file> §x says A; code does B; since PR #n`).

### Phase 3 — Entity model (`inherit`)

Read: `C:\work\marketplace\aiup-core\skills\entity-model\SKILL.md`, its `references/` including `clarify-checklist.md`; `reverse-engineer/SKILL.md` on entity sources. Sources: `docs/requirements.md`, `docs/glossary.md`, the domain and persistence classes listed in `scratch/source_inventory.md`.

Write `docs/entity_model.md` exactly to the skill. **Deviation to log:** the predecessor has no SQL schema or migrations, which `reverse-engineer` ranks as the authoritative entity source; entities come from Kotlin domain and persistence classes. Model the business entities, not Android storage mechanics; record what was left out and why.

### Phase 4 — Use case diagram and specifications (`inherit`)

Read: book chapter `04`; `reverse-engineer/SKILL.md` stages 2–7; `use-case-diagram/SKILL.md`; `use-case-spec/SKILL.md` and `references/format-spec.md`, `example.md`, `clarify-checklist.md`. Sources: the entry points in `scratch/source_inventory.md`, the tests (they state intended behaviour), the archived specs, the PR history, `docs/vision.md`, `docs/requirements.md`, `docs/entity_model.md`.

1. Find actors and aggregate entry points into use cases by actor goal, following `reverse-engineer` stage 3 including its self-check. A hypothesis from an earlier skim (Manage bank connections, Fetch transactions, Reconcile and match transfers, Review and upload to YNAB, Manage budgets and account mapping, Audit decision journal) is **only a hypothesis**; the phase derives its own set and records in the return where and why it differs. If it ends with more than 8 use cases, justify that in the return.
2. Write `docs/use_cases.puml` and one `docs/use_cases/UC-XXX-<kebab-name>.md` per use case, `**Status:** Draft`, every active FR linked from at least one use case.
3. Specifications describe the behaviour Slussen must have, not the history: no PR numbers or archive references inside the use cases. Drift goes in the return, as in Phase 2.
4. Run `python tools/lint_specs.py --strict` yourself before returning, and fix until it passes.

### Phase 5 — Adversarial spec review (`pro`, report only)

Read: `C:\work\marketplace\aiup-core\skills\spec-review\SKILL.md` and `references/review-checklist.md`; book chapter `10`. Review `docs/` against the skills and against the evidence. Prompt stance: *find what would make a developer build the wrong app from this spec — missing behaviour the predecessor has, rules stated but not enforced, invented behaviour with no evidence, steps that leak implementation, aggregation that mirrors screens, anything that contradicts `docs/vision.md`. No compliments. Report only; never edit `docs/`.* Each finding: ID `R-nn`, location, what is wrong, evidence path. The orchestrator writes them into §6.

### Phase 6 — Dispositions (`inherit`)

For every `R-nn`: fix it in `docs/`, or reject it with a reason, or mark it Open for the operator. Record the disposition in §6. Never edit `docs/vision.md` — a finding against the vision is Open for the operator. Gate: `--strict` passes.

### Phase 7 — Operator approval (operator)

The operator reads `docs/` and §6, answers the Open items (as edits to the specifications), and sets each use case that holds to `**Status:** Approved`. Construction does not start before this.

### Phase 8 — Close (`inherit`)

Fill §7, move this file to `docs/archive/bootstrap_aiup_spec_plan.md`, open the PR. `docs/` then holds only the specification.

## 5. Methodology evaluation log

One block per phase, written by the orchestrator in the phase's commit. This is the evidence the operator will use to judge AIUP.

| Phase | Skill(s) followed | Followed as written? | Deviations and why | Skill silent on | Lint result (E/W/I) | Assumptions / Open | Re-dispatches | Notes |
|---|---|---|---|---|---|---|---|---|
| 0 | `reverse-engineer` stage 1 (discovery) | Partly — stage 1 scoped to the plan's Phase 0 list | Did not search auth/role configuration (skill's richest actor source) because it is outside the Phase 0 list, so it is owed to Phase 4. Did not read `references/stack-signals.md`; stack read from Gradle files and the module layout. Model tier `flash` has no Claude equivalent, so the orchestrator ran it on `haiku` (the fast tier). | Android/Kotlin projects with no SQL/ORM: no Room, DataStore or DDL; persistence is JSON files behind a `FileStore` port. No receivers, services or workers; one launcher activity. | 0/0/0 | Assumed: "archived spec" = `docs/archive/*spec*.md` (11 of 24 archive files). Open: O-01. | 0 | Gate green on first dispatch: 81 PRs in dump = 81 from `gh`. Plan said local predecessor HEAD was `9068c74`. The operator fast-forwarded local `main` to `46efbf0` at 12:59 before the run (reflog), and content = `origin/main`. |
| 1 | Marketplace template `docs/templates/vision.md`; book ch. 02, ch. 08 step 1 (`init`) | Yes for structure: headings and order are exactly the template's | The book has the vision written by hand; here it is drafted from evidence (planned deviation, corrected at the vision gate). Template asks for a secondary user role; the draft states there is none rather than inventing one. Mission adds a short note on the predecessor and the rename. Goals with no evidence-backed number are stated as trends. | Vision length/detail (book example is ~10 lines; draft is 135); whether vendor names and regulatory numbers belong in a vision (placed under Constraints); brownfield successor concerns (continuity, migration, predecessor as intent); how to mark inferred vs evidenced content. | 0/0/0 (`spec_lint.py` does not check `vision.md`, so the lint part of this gate cannot fail; the real gate is the template-heading comparison, which the orchestrator ran) | Assumed A1–A7 (vendors only in Constraints; single role; zero targets for safety goals; clean takeover is an inference; journal in scope per O-01; README viewer omitted; reinstall durability in scope). Open: O-02, O-01 still pending. | 0 | Gate green on first dispatch. VG-01..VG-13 are in §6 for the operator. |
| Vision gate | — | — | — | — | — | — | — | operator diff: `git diff --stat 981e4cc 5f65515 -- docs/vision.md` = 1 file, +135/−79, over five operator commits (`1ec7e81`..`5f65515`). Kind of change: scope and principles, not structure. The operator rewrote the Mission problem statement, made Budget App and Data Providers replaceable or extensible, made the repository public with an adopting-developer user, relaxed logging to opt-in detail, corrected the consent rationale, and added seven safety principles the draft had missed. **Deviation from the plan:** the operator did not edit entirely by hand. On the operator's direction, the orchestrator drafted consistency fixes and wording into the file, and the operator reviewed and committed them. At the operator's request, the orchestrator also ran an extra report-only cross-model review of the vision against the predecessor (`sonnet`): 19 findings V-01..V-19, all verified by the orchestrator against code at `46efbf0`. 7 were applied (group A), 4 were decided by the operator (group B), and 8 are carried to Phase 2 (group C). **Evaluation signal:** the evidence-drafted vision kept its structure but missed seven safety rules that were in the evidence. The draft step alone would not have caught them; the extra adversarial pass did. |
| 2 | `requirements` (SKILL + references); `reverse-engineer` stages 1–2; book ch. 03 | Yes for format (FR user stories, measurable NFR, C with Source, Status, glossary with Avoid) | (1) No clarifying questions possible, so recommended options taken (planned). (2) The FR table has no Source column, so vision-only requirements are not marked in `docs/`; they are listed here: FR-009 (review part), 031, 041–043, 054, 069–073; NFR-002, 008, 010 (sent-record part), 014, 015; C-005, 010, 013. (3) **Book vs skill conflict:** ch. 03 says the entity model replaces the glossary; the skill requires `glossary.md`, and the skill was followed per AGENTS.md. (4) "YNAB" is not an avoided glossary term because the linter flags avoided words everywhere and C-008/009/011 must name YNAB; YNAB has its own glossary row instead. (5) No TodoWrite in this environment. (6) All Status = Open (no use cases yet). The executor used four nested read-only research sub-agents; not prohibited by §1, noted for cost. | Brownfield sources and where requirement-level drift goes; the vision-overrides-code case (this plan's 'follow the code' rule does not cover it); whether default numbers belong in FRs (included, for testability); glossary avoid-words that collide with preferred terms; no constraint category for external service rules (Technical used); whether a build-time role (adopting developer) may own FRs. | 0/0/0 (the plan expected `FR_UNCOVERED` warnings; none appeared because the linter checks coverage only once use cases exist) | 13 assumed defaults from predecessor code (7-day expiry warning, 60-min cooldown, 24/day request budget, 7-day upload window, 5-day pairing, 6-day lonely-leg grace, 36-h stale balance, 10-min plan expiry, etc.); NFR integrity rules filed under Availability (no Integrity category in the skill). Open: O-04..O-09. | 0 | Gate green on first dispatch. 73 FR / 16 NFR / 13 C; 46 glossary terms. Real account labels and IDs in the predecessor (spec Appendix A, README, PRs) were not copied; the orchestrator grep found none in `docs/`. 20 drift items (D-05..D-24). All carried V-findings dispositioned (the V-14 screenshot part and V-18 were not made requirements; now O-06/O-07). |
| 3 | `entity-model` (SKILL, references, `clarify-checklist.md`); `reverse-engineer` on entity sources | Yes for format (Mermaid without attributes, 5-column attribute tables, closed type set, one vocabulary row per Validation Rules cell). Orchestrator verified all 134 attributes use the 7 allowed types | (1) Planned: no SQL/DDL in the predecessor, so entities come from Kotlin domain classes and JSON file stores plus vision and requirements; three entities have no stored predecessor form (SENT_RECORD, TRANSFER as one record, ELIGIBLE_TRANSACTION). (2) Nullable FKs typed `Optional` (vocabulary has no optional-FK row). (3) DECISION_JOURNAL_ENTRY has no relationship on purpose (journal observes only, NFR-006). (4) Counts only `Not Null` (vocabulary forbids `Min:` without `Max:`). (5) Multi-column uniqueness and conditional rules as `#### Constraints` bullets. (6) No TodoWrite. (7) `reverse-engineer` lists BLOB, `entity-model` does not; entity-model followed. | A vision-required entity the code lacks; entities with no relationships; optional-FK typing; caches of an external system of record; settings as entities; per-install data that does not survive reinstall; 3-decimal currencies vs fixed `10,2`; new names with no glossary term. **Tooling gap:** the clarify checklist checks names against glossary Avoid words, but `spec_lint.py` does not lint `entity_model.md` for them (the executor checked by script). | 0/0/0 (`--strict` also 0/0/0) | 13 assumed (one current mapping, no history; TRANSFER entity with 1–2 legs; one Review Decision per transaction; SENT_RECORD new; transactions survive reinstall; only latest balance; account type enum incl. Other; Decimal 10,2; field lengths; daily limit per Data Provider; file import is not a FETCH; BACK_OFF persisted; import identifier always filled). Open: O-10..O-13; O-05 still open. | 0 | Gate green on first dispatch. 21 entities. Left out: storage mechanics, the Budget App cache (system of record elsewhere), provider-specific ids and hashes (one generic `account_fingerprint`), credentials, the one-time consent request (NFR-013), UI state. UNKNOWN_SOURCE_ROW and NO_STABLE_ID merged into Needs Review. No new drift; D-10, D-17 modelled per spec/vision, D-12, D-15 per code. |
| 4 | `reverse-engineer` stages 2–7; `use-case-diagram`; `use-case-spec` (+ `format-spec.md`, `example.md`, `clarify-checklist.md`); book ch. 04 | Yes for format: `validate_use_case.py --strict` passes on all 12; all Draft | (1) Content mined from vision + requirements + code together, not code alone; where the vision overrides the code, the use case states what Slussen must do. (2) UC-003 Compare is both an operator goal and an `<<include>>` target of UC-001/002/006; the skill describes includes only as subfunctions. (3) Some preconditions name a state another use case controls. (4) UC-012's system is the repository and build, not the running app. (5) No TodoWrite; no questions asked, so recommended defaults are logged as Assumed. (6) Mid-phase, the orchestrator relayed the operator's 'always qualify account' directive (O-14) to the executor. | A build-time actor (adopting developer); a use case that is both goal and include; settings scattered across many FRs (gathered in UC-010); where a vision-overrides-code rule is recorded; rule ownership when several use cases enforce one rule; `validate_use_case.py` does not check glossary Avoid words; neither tool can enforce qualified-noun rules (grep used). | 0/0/0 `--strict` | ~20 assumed (O-11 derive same import identifiers; O-04 per-format window without a number; O-05 only indistinguishable rows to review; file import keeps earlier transactions; O-09 per Data Provider, 24/day, persisted; empty fetch starts no cooldown; 60-day fetch period from code; Fetch all = one Fetch per Data Provider; Review Decision ahead of Needs Review; Hold keeps both legs; sent record written before sending; settings ranges own defaults). Open: O-15..O-19. | 0 | Gate green on first dispatch. 12 use cases (> 8, justified: ~32 operator intents on 7 screens plus a build-time actor; each UC has its own trigger and scenario). Differs from the hypothesis: review split from upload; Compare is a shared include; added Import a Transaction File, Check Import Status, Adjust Settings, Set Up an Own Build. All 73 FR linked. No roles or permissions exist in the predecessor (grep), so actors come from the vision. Bare 'account': the orchestrator grep found 1 (the outcome name 'Ambiguous Account', from the entity model) → Phase 6. |
| 5 | `spec-review` (SKILL + `review-checklist.md`); book ch. 10 | Partly: Part A (lint) as written; Part B semantic review done, but output in the plan's format | (1) Model tier `pro` has no Claude equivalent; ran on `sonnet` (a different model from the authoring phases' `opus`, which satisfies the cross-model rule). (2) Report format: the skill prescribes a table with severity `warning`/`info`, `File:Line`, Check column and a 'Ready for Approved' verdict; the plan prescribes `R-nn` + location + evidence. The plan's format was used, with High/Medium ≈ warning and Low ≈ info; the verdict was still given. (3) The reviewer read the predecessor as evidence, which the skill does not do (the plan requires it). (4) No `/…` fix commands offered (Phase 6 owns fixes). | How to review a spec against an external body of evidence (the skill reviews artifacts only against each other); severity beyond warning/info. Part B is non-deterministic: a re-run may rank or phrase differently. | 0/0/0 `--strict` | — | 0 | Gate green: 29 findings (5 High, 19 Medium, 5 Low), each with ID and location; `git status` clean after the reviewer (no `docs/` change). Verdict: **not Ready for Approved**. The orchestrator verified R-01 against code: `CanonicalTxn.kt` derives `YI1-` + base32(sha256(… bindingId …)), and the bindingIds are seeded in `DefaultConfig.kt`; nothing in Slussen's spec carries them over. **Evaluation signal:** lint was 0/0/0 strict at the same time as 5 High semantic findings, so the deterministic gate says almost nothing about whether the spec builds the right app; the adversarial pass does. |
| 6 | | | | | | | | |

## 6. Drift, review findings and open questions

**Vision gate** (choices the draft made that the operator may want to change):

| ID | Choice in the draft | Alternative | Operator's decision |
|---|---|---|---|
| VG-01 | Single operator, private build with credentials embedded at build time, sideloaded, no server | Multi-operator product: runtime credentials, server-side signing for Enable Banking, distribution || Changed: public repository with no personal data; private build per operator, with accounts and credentials from configuration outside the repository and configuration templates provided; adopting developer added as a second user. |
| VG-02 | Android-native on the phone (driven by SCA/BankID); no language or framework fixed | Fix Kotlin/Compose as a constraint, or open other platforms || Accepted (Android-native). Rationale corrected: the phone is chosen for on-the-go use, not because the ID app must be on the same device (see D-03). |
| VG-03 | YNAB is the only destination; pluggable destinations not promised | Keep "pluggable destination" as a goal or constraint || Changed: YNAB is the only Budget App in this build; the Budget App is replaceable by design; Slussen owns exactly-once itself (option a), using the Budget App's duplicate protection only where it exists. |
| VG-04 | File import in scope only for the one account with no feed (Fidelity HSA CSV), contradicting the predecessor's "not a file importer" | Drop file import, or generalise it to any bank export format || Changed: file import in scope; file formats are extensible; one format in this build. Each format declares whether rows have a stable reference. |
| VG-05 | Transfer detection in scope (opt-in, same budget and currency, includes lonely legs) | Make it core and always on, or defer it from the rebuild || Accepted as drafted. |
| VG-06 | Decision journal and self-audit export in scope (per O-01); analysis happens outside the app | Defer the journal, or bring analysis into the app || Accepted as drafted. |
| VG-07 | Accounts configured per build; in-app account mapping/onboarding out | In-app mapping and onboarding (predecessor README's stated future direction) || Accepted as drafted. |
| VG-08 | No background, scheduled or automatic fetch; no notifications | Opt-in scheduled fetch and an expiry notification (predecessor deferred items NAV D3, AUTH) || Accepted. Expiry warning only when the app is opened; notifications stay out (V-04). |
| VG-09 | Clean takeover from the predecessor with no duplicates is a goal (drafter's inference, not evidenced) | Fresh start with an operator-chosen cutover date, no identity continuity || Accepted as drafted. |
| VG-10 | Only YNAB mutation besides create is clearing a transfer counterpart Slussen itself created | Strictly create-only, or allow wider edits (category, payee) || Accepted as drafted. |
| VG-11 | Sources limited to the aggregator, Enable Banking and one file format; Plaid and others out | Leave room for a third online source || Changed: Data Providers are extensible by design; SimpleFIN and Enable Banking in this build. |
| VG-12 | Moving an account's history to another YNAB account is out | Bring Move back into scope (predecessor deferred O3) || Accepted as drafted. |
| VG-13 | Reinstall durability (export/import of identities and decisions) in scope | Leave it out of the vision as an implementation concern || Accepted, extended: the record of what was sent also survives a reinstall; bank authorizations do not (V-11). |

**Drift** (predecessor spec vs PR vs code; never resolved silently):

| ID | Artifact | Spec says | Code does | Since | Proposed resolution |
|---|---|---|---|---|---|
| D-01 | predecessor `README.md` | "Seven Gradle modules" | Eight modules, including `source-fidelity-hsa` | PR #46 | README is stale; Slussen specs follow the code. No vision impact. |
| D-02 | predecessor `README.md` | Fidelity HSA file import listed as "options being explored" | File source is built | PR #46 | Treated as in scope (VG-04). |
| D-03 | Phase 1 draft, from predecessor rationale | Bank consent needs the bank's identification app on the same device | Operator correction: Strong Customer Authentication (e.g. BankID) may run on another device; authentication happens once per bank per consent | — | Corrected in the vision; Phases 2–4 must not reintroduce the same-device rationale. |
| D-04 | predecessor `android_app_spec-v1.4.md` §13 | Imported transactions post `uncleared` | Code and README: post `cleared` (operator decision 2026-06, config schema v2) | README config table | Follow the code: cleared. To Phase 2 with V-08. |
| D-05 | FR-044 | v1.4 §12/§13: post uncleared | Posts cleared; no `approved` field, so imports land unapproved | config schema v2 (2026-06) | Follow code (= D-04). Done in FR-044. |
| D-06 | NFR-009/010 | v1.4 §19: consent sessions, resolutions and binding registry excluded from backup | Effective backup XML excludes only consent sessions and pending consent; registry and resolutions are backed up. `PlatformSecurePolicy` and platform `backup_rules.xml` still list the old exclusions but are shadowed | PR #81 (operator decision 2026-09-23) | Follow code; agrees with the vision. |
| D-07 | (none) | v1.4 §19, README: FLAG_SECURE on by default | Never set; removed in `4888de7` (operator accepts screenshot/recording exposure) | ~PR #26/#27 | Not a requirement; O-06. |
| D-08 | FR-030/031 | v1.4 §6.3: one age gate for all | File sources exempt (`exemptFromUploadAgeGate`); PR #77 left review rows ageing out silently | PR #46, #77 | FR-031 closes the gap per the vision; file-window question O-04. |
| D-09 | FR-009 | v1.4 §6.1: no stable ref = review-only | HSA file source synthesises ref from date/amount/action, declared stable; identical rows collapse | PR #46 | Vision overrides code (V-03). |
| D-10 | FR-054 | TM v0.11 §10 inv.1: arriving leg always suppressed | Same; leg lost if the transfer is un-transferred in YNAB | PR #68 (known gap) | Vision overrides code. |
| D-11 | FR-046 | TM v0.11 §5: descriptor required for every pair; per-leg tiebreak | Descriptor needed only bank-to-bank; one greedy 1:1 pass incl. settlements | PR #80 | Follow code. |
| D-12 | FR-047 | TM v0.11 §6: either leg may post | Always posts the outflow leg | PR #69 | Follow code. |
| D-13 | FR-051 | TM v0.11 §7/§11: grace only for unapproved or held lonely legs; lonely proposal waits for approval | Grace for any not-yet-uploaded lonely leg; lonely proposal eligible on detection | — | Follow code. |
| D-14 | FR-053 | TM v0.11 §9.1: counterpart cleared at create | Create, then best-effort setCleared (YNAB creates it uncleared, live test T-4) | PR #67 | Follow code. |
| D-15 | FR-050 | TM v0.11 §12.1: five decisions; HOLD only on lonely/ambiguous; re-pick among candidates | Adds DEFER (= HOLD); HOLD on a confident pair releases the partner as ordinary; PICK_COUNTERPART ignores window and claims | — | Follow code; Phase 4/5 to check PICK_COUNTERPART. |
| D-16 | FR-047 | TM silent on a missing transfer payee | Uploads the leg as ordinary but still suppresses the other leg, which is lost | — | Vision overrides code (no silent drops). |
| D-17 | FR-057 | Account-map spec v0.1: every mapping locks after first upload | Registry only for EB, file and retired bindings, so SimpleFIN mappings appear never to lock (code reading, untested); a corrupt registry is replaced with an empty one and locks are lost (fails open) | — | Requirement as spec; code gap noted for construction. |
| D-18 | FR-058 | v1.4 §6.4: ambiguous identity fails closed | Bind-time resolver fails closed; EB fetch path takes the first hash-overlapping account | PR #39 (partial) | Requirement fails closed per the vision (V-10). |
| D-19 | C-006/FR-017 | Vision: PSD2 4/account/24 h; bank-auth spec §5: budget resets 00:00 UTC | No per-account counter; 24/day per source, in memory, reset on restart; device-local date; persisted back-off config never read | PR #79 | Mechanism per operator decision V-06; O-09. |
| D-20 | FR-016/014 | Navigation spec §8: cooldown after a successful fetch | Cooldown after any acquire incl. zero accounts; auto-forced past cooldown/back-off on missing org key or empty data; back-off restore after restart disputed | PR #26, #78, #79 | Follow spec intent; verify in Phase 4. |
| D-21 | NFR-007 | v1.4 §19: account numbers never logged | EB source and controller log account uids, hash prefixes and raw exception messages at INFO | — | Requirement per the vision (detailed logging off by default); predecessor code violates it. |
| D-22 | FR-065 | README: fetch log keeps 10 | Keeps 60 | PR #78 | Follow code. |
| D-23 | FR-025 | v1.4 §6.3: any foreign `import_id` is a possible external match | Excludes this install's own imports | PR #77 | Follow code. |
| D-24 | FR-067 | DJ spec §7: small bounded queue | 16,384-event queue | PR #81 | No requirement impact. |
| D-25 | UC-003 BR-001 | v1.4 §6.3 classifier order | 'No stable reference' is checked before the operator's decision, so such rows can never be decided | — | Spec puts the Review Decision first. |
| D-26 | UC-002 BR-003 | — | File import replaces the Bank Account's stored transactions each time; not-yet-uploaded rows can vanish | PR #46 | Spec keeps earlier transactions (NFR-002, no silent drops). |
| D-27 | (no FR) | — | Predecessor has bank de-authorize, settings 'Reset to defaults', and `migrateDestination` (re-points a locked mapping, with a duplication warning) | — | Not specified; O-18. |
| D-28 | UC-001 BR-004 / FR-014 | FR-014: a provider's wait instruction holds even on retry | 'Force retry' (`force=true`) skips every back-off, including a provider's 429 | — | Spec allows no override. |
| D-29 | UC-005 A3, BR-007 | D-15 proposed 'follow code' | PICK_COUNTERPART ignores window and claims (one leg can be suppressed for two transfers); HOLD on a confident pair releases the partner as ordinary (duplicate risk) | — | Spec follows the vision instead of code: re-pair only with an unclaimed leg in window, else Lonely Transfer; Hold keeps both legs out. Supersedes D-15's proposal. |

**Vision review at the vision gate (extra, operator-requested, report-only, `sonnet`) and dispositions:**

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|
| V-01 | Mission | "Writes only the new" omits that typed-entry matches are sent so the Budget App can pair them | SPEC §6.3; `Classifier.kt` eligible = NEW, MATCH_CANDIDATE, TRANSFER_PAIRED | Fixed in the vision |
| V-02 | Scope | Upload date window missing; rows can age out silently (PR #77 gap) | SPEC §6.3, §14; PR #77 | Operator: bounded window, never silent. Fixed in the vision |
| V-03 | Goals | Two silent-drop paths: file rows without a stable reference collapse; the suppressed transfer leg is lost | `FidelityHsaTransactionSource.kt`; PR #46, #68 | Operator: fix in the rebuild (review instead of merging or losing). Fixed in the vision |
| V-04 | Success measures | "No consent lapses unwarned" is impossible with no background work | README; BA §12 | Operator: notifications out; warn on app open. Fixed in the vision |
| V-05 | Mission | "Always sure up to date" overclaims | README principle 6; SPEC §17 | Fixed in the vision (at-a-glance status) |
| V-06 | Goals | Per-account PSD2 limit not enforced by the predecessor; the mechanism differs | `SourceCoordinator.kt`; README | Operator: state the mechanism. Fixed in the vision |
| V-07 | Goals | "Written = confirmed" (plan expiry, re-read before write) missing | SPEC §8, §15; PR #21 | Fixed in the vision |
| V-08 | Scope | Imports land cleared and unapproved; lone-transfer mirror handling | README config; `YnabBudgetWriter.kt`; TM §9.1 | Carried to Phase 2 (requirement level); see D-04 |
| V-09 | Scope | Only booked transactions; zero-amount skipped; no stable reference means review only | SPEC §6.1, §13; sources | Carried to Phase 2 (stable reference partly in the vision via V-03) |
| V-10 | Goals | "Never silently redirect an account" missing | README incident notes; SPEC §6.4; PR #39 | Fixed in the vision (Never guess an account) |
| V-11 | Goals / Scope | Owning exactly-once needs a durable sent record across reinstall; bank authorizations lost on reinstall | SPEC §6.3, §6.6; PR #81 | Fixed in the vision |
| V-12 | Goals | Truthful unknown outcomes and failure isolation missing | PR #66, #38, #46; SPEC §7 | Fixed in the vision |
| V-13 | Out of scope | Currency mismatch blocks the account | SPEC §12; PR #4 | Carried to Phase 2 |
| V-14 | Constraints | Screenshot protection; one-time consent state; journal excluded from backup | SPEC §18, §19; DJ §8 | Carried to Phase 2 |
| V-15 | Scope | The journal only observes and never blocks an import | DJ §2, §7, §11 | Carried to Phase 2 |
| V-16 | Goals / Success measures | Exactly-once bounded to Slussen's own sends | README principle 2; TM §18 | Fixed in the vision |
| V-17 | Goals / Out of scope | Clearing a transfer counterpart must appear in the plan | TM §9.1; DJ §3 | Carried to Phase 2 |
| V-18 | Scope | In-app offline help (README viewer) | README; PR #48 | Carried to Phase 2 (may be out of scope) |
| V-19 | Constraints | Rotate credentials if a build is exposed | SPEC §19 | Carried to Phase 2 |

**Review findings (Phase 5) and dispositions (Phase 6):**

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|
| R-01 | FR-043; UC-003 BR-002; entity BANK_ACCOUNT, BANK_TRANSACTION.import_identifier | High: clean takeover relies on 'derived as the predecessor did', but the derivation is unspecified and BANK_ACCOUNT has no stable account identity, so a new install cannot reproduce the identifiers and would duplicate everything | README §1, §4; `CanonicalTxn.kt`; `DefaultConfig.kt` (seeded bindingIds); v1.4 l.500 | |
| R-02 | FR-009 vs UC-002 BR-002/step 6, UC-003 BR-012, entity FILE_FORMAT/BANK_TRANSACTION.stable_reference | High: FR-009 and the vision send every row without a Stable Reference to review; UC-002 derives one and reviews only indistinguishable rows; whether a derived reference counts as stable is unstated (O-05) | D-09; `FidelityHsaTransactionSource`; vision Constraints | |
| R-03 | entity_model.md vs UC-003, UC-011 A2, UC-001 BR-007, UC-007 BR-002, UC-010, UC-008 step 7 | High: use cases depend on data no entity holds: Budget App copy, last Budget App read time, Budget Account balance, pending consent request, per-provider request count, settings | v1.4 §9.3; README §7 | |
| R-04 | UC-008 step 7, BR-005; FR-061; UC-003 step 2 | High: the Budget Account balance is shown but no use case reads or refreshes it, so it has no stated age | v1.4 l.268, l.331 | |
| R-05 | UC-006 step 9; UC-009 BR-003, A8; entity ACCOUNT_MAPPING.locked_at; FR-041/057 | High: the mapping locks only after a confirmed write; after an Unknown outcome a re-map can resend the same identifier into another Budget Account (duplicate) | v1.4 §6.4a; README l.380 | |
| R-06 | UC-003 BR-001, BR-011; UC-006 BR-007 | Medium: the Upload Window check precedes Already In / Deleted / Merged, so after 7 days sent rows show Too Old, Unknown outcomes cannot resolve, and old deleted rows cannot be re-uploaded | README §2 (code does the same); vision 'never disappears silently' | |
| R-07 | UC-006 BR-005, BR-003, A4; FR-036; UPLOAD_PLAN.state; UC-007 precondition | Medium: plan expiry never lifts the pause; paused-action lists differ between FR-036, BR-005 and UC-007 | v1.4 l.341 | |
| R-08 | UC-001 A1/A2/A3/A6 vs A10, BR-010; FR-018 | Medium: in Fetch all, one provider's cooldown/back-off/failure ends the whole use case, contradicting failure isolation | — | |
| R-09 | UC-001 A5, BR-004; BACK_OFF.ends_at | Medium: no default wait when a provider refuses without naming a period | v1.4 l.288, l.371 (8 h) | |
| R-10 | FR-015; UC-001 A6, BR-005; BACK_OFF | Medium: O-15 propagated; the transient back-off never blocks anything | v1.4 l.371 | |
| R-11 | C-006; UC-001 BR-007 | Medium: no rule enforces 4 accesses per Bank Account per 24 h; six fetches can exceed it and the cooldown can be 0 | D-19, V-06; `SourceCoordinator.kt` | |
| R-12 | FR-066; UC-001, UC-003, UC-006; UC-011 BR-001; DECISION_JOURNAL_ENTRY.event_type | Medium: journal events (Session Start, Transaction Seen, Status Changed, Budget App Outcome…) have no producing step | `docs/decision_journal_spec.md` §3 | |
| R-13 | UC-008, UC-009, UC-010 (UC-005 partly) | Medium: screen-mirroring aggregation; triggers are navigation ('opens the settings') | v1.4 l.330; navigation spec | |
| R-14 | UC-005 A4, BR-003; UC-003 BR-001, BR-014; TRANSFER.decision; BANK_TRANSACTION.status | Medium: eligibility of confident and lonely Transfers without a decision unspecified; no status for legs of a Held Transfer; enum mismatch (O-17) | D-13 | |
| R-15 | UC-005 BR-002, BR-003; UC-004 step 4; FR-046 | Medium: 'identifies a bank transfer', 'best pairing', 'resemble' undefined; the predecessor uses tunable payee/memo pattern lists | TM §5, §8 | |
| R-16 | UC-003 BR-002/003/004/006/007; UC-006 BR-008; FR-044; C-009 | Medium: core rules hard-code YNAB behaviour (10-day window, cleared/unapproved, import-id semantics), against the vision's replaceable Budget App and NFR-015 | vision Constraints; NFR-015 | |
| R-17 | UC-003 step 2; UC-001 BR-009; FR-019 | Medium: no Budget App read lookback; if shorter than fetch period + match window, sent rows are re-offered (duplicates) | v1.4 l.276, l.332, l.460 | |
| R-18 | glossary Fetch/Source/Source Health/Freshness; UC-008 step 6; UC-002; entity FETCH | Medium: health and freshness undefined for file-imported Bank Accounts | — | |
| R-19 | UC-008 steps 4–8, BR-007; FR-060/061/063 | Medium: no all-Budgets at-a-glance view and no per-source pending-upload indicator, against the vision's mission | vision Mission | |
| R-20 | UC-002 A4, BR-003; FR-009 | Medium: identical indistinguishable rows: no rule recognises the same row in an overlapping later file | — | |
| R-21 | FR-064; UC-008 BR-006; UC-001 A6/A7; UC-006 A6 | Medium: no flow produces 'configuration error'; invalid credentials (401/403) treated as transient back-off or rejection | v1.4 l.371 | |
| R-22 | UC-006 step 6, 11, A5; SENT_RECORD.outcome | Medium: no flow for failing to store the Sent Record before sending; no rule resolves Unknown from the Comparison | v1.4 l.240 | |
| R-23 | UC-004 step 7, BR-001; UC-003 BR-003; UC-005 A6 | Medium: resulting status per decision unnamed; Transfer Counterpart Changed choices missing in UC-004; FR-054 not linked from UC-004 | — | |
| R-24 | requirements (31 rows), glossary (8 entries), entity model (descriptions, 4 attribute names, 2 values), UC-001 A9 | Medium: operator rule O-14: bare 'account' remains | O-14 | |
| R-25 | UC-003 BR-011, UC-002 BR-004, UC-010 BR-001; FILE_FORMAT; DATA_PROVIDER.daily_request_limit; FR-017 | Low: per-format Upload Window has no attribute or setting; 'by default' 24/day not adjustable in UC-010 | O-04 | |
| R-26 | UC-012 steps 3–6, A2–A3, BR-007; FR-072/073 | Low: steps are developer actions, not system interaction; extensibility only testable via NFR-014/015; BR-007 duplicates C-001/C-002 | — | |
| R-27 | UC-006 BR-014 / UC-009 BR-006 / NFR-010; UC-006 A3 / UC-004 A8; NFR-016 / UC-001 BR-012 / UC-003 BR-015; UC-006 BR-011 / FR-055 | Low: the same rule restated in several places | — | |
| R-28 | BANK_TRANSACTION.payee/memo; UC-006 step 7 | Low: no rule for over-long payee/memo (the predecessor truncates to 200/500) | v1.4 l.273 | |
| R-29 | UC-003, UC-008, UC-005, UC-011 step 1 | Low: step 1 repeats the trigger; UC-003 as an include has a trigger that does not apply | review checklist §8 | |

**Open questions for the operator (Phase 7):**

| ID | From phase | Question | Recommended answer | Operator's answer |
|---|---|---|---|---|
| O-01 | 0 | `docs/decision_journal_spec.md` and `docs/decision_journal_plan.md` live in predecessor `docs/`, not `docs/archive/`. Are they live intent or archived intent? | Treat the spec as intent on the same footing as the archived specs (it is the latest designed intent for the journal); treat the plan as history only. Phases 1–4 read it accordingly. | |
| O-02 | 1 | The vision's trend-based success measures (e.g. transfer re-link rate, missed transfers measured from the journal) have no numeric targets in the evidence. What targets? | Leave as trends for the bootstrap; set numbers once the journal yields a baseline. | |
| O-03 | vision gate | The Marketplace has stack plugins only for web stacks (Angular/JPA, Blazor, NestJS, Vaadin), none for Android or Kotlin. Module structure and separation of concerns (replaceable Budget App, extensible Data Providers) will rest on project ADRs (`docs/adr/`, per Marketplace `docs/workflow.md`) with no AIUP stack guidance. How should construction proceed? | Write ADRs for the module boundaries before the first use case is implemented; log the missing stack plugin as an AIUP gap. | |
| O-04 | 2 | The vision bounds every upload by a date window; the predecessor exempts file imports (PR #46). Should a file import use its own wider window or the common one? | Its own wider window, declared per file format, with the same never-silent rule. | |
| O-05 | 2 | For a format without stable row references, does 'reviewed, never guessed' mean every row needs review, or only rows that cannot be told apart? | Only indistinguishable rows (FR-009 as written). | |
| O-06 | 2 | Screenshot protection (FLAG_SECURE): the predecessor spec required it; the code removed it on purpose (D-07). Requirement or not? | Not a requirement (operator accepted the exposure); add one only if the public build should default it on. | |
| O-07 | 2 | In-app offline help (README viewer, PR #48) is not in the vision. Keep it out? | Keep out; repository docs serve the adopting developer. | |
| O-08 | 2 | **Vision-level:** the vision and C-007 say consent lasts about 90 days. Delegated Regulation (EU) 2022/2360 moved account-information renewal to 180 days (RTS Art. 10a). Update the vision? | Operator to verify against Enable Banking's actual consent length; if 180, edit the vision and C-007. | |
| O-09 | 2 | FR-017 request budget: per Data Provider, value 24/day, and should it survive a restart (the predecessor resets it in memory)? | Per Data Provider, 24/day default, persisted across restarts. | |
| O-10 | 3 | All Decimals are `10,2`, but the predecessor supports currencies with 3 decimal places. Keep 2 or widen? | Widen amounts to 3 decimals (`12,3`) so any ISO 4217 currency fits; Phase 6 can apply it if the review agrees. | |
| O-11 | 3 | Clean takeover (FR-043): pre-fill the Sent Record from what the predecessor uploaded, or rely only on deriving the same import identifiers? | Derive the same import identifiers (no migration step); Phase 4 to specify, Phase 5 to challenge. | |
| O-12 | 3 | The entity model introduces names missing from the glossary: Fetch Result, Decision Journal Entry, transfer decision, import identifier, and status values (Needs Review, Decision No Longer Holds, Arrived As Transfer Counterpart, Held Lonely Transfer, Transfer Counterpart Changed). Add them? | Yes; Phase 6 adds them to `glossary.md` (within its `docs/**` remit). | |
| O-13 | 3 | Operator settings (upload window, cooldown, transfer window and grace, stale-balance hours, journal on/off and size, detailed logging) have no entity or glossary term. Model them? | Add a glossary term 'Operator Setting'; keep them out of the entity model (configuration, not business data). | |
| O-14 | operator (during Phase 4) | Bare "account" is ambiguous. Must every use say Bank Account or Budget Account (or a glossary compound)? About 110 bare uses exist in vision, requirements, glossary and entity model. The linter cannot enforce this, because an Avoid entry for "account" would also flag the qualified terms. | Yes: the glossary states the rule in the Bank Account and Budget Account definitions; Phase 4 writes use cases with 0 bare uses (relayed to the executor mid-phase); Phase 5 checks it; Phase 6 fixes requirements, glossary and entity model. The vision is the operator's file, so it is fixed only on the operator's word. | **Decided by operator 2026-10-13:** always qualify. |
| O-15 | 4 | FR-015 says a transient back-off holds unless the operator starts the fetch, but every fetch is operator-started, so the 30-minute back-off never blocks anything. Reword FR-015? | Make the transient back-off hold for a repeated fetch of the same Data Provider within 30 minutes unless the operator explicitly overrides it; a provider's own wait instruction is never overridden (FR-014). | |
| O-16 | 4 | An 'Import Anyway' on a Needs Review transaction without a Stable Reference has no Stable Reference to derive an import identifier from. How is exactly-once kept? | Derive the identifier from the transaction's fields plus an occurrence counter recorded in the Sent Record; Phase 6 aligns the entity model and requirements. | |
| O-17 | 4 | Entity enum mismatch: TRANSFER decision values include Deferred and Other Account Chosen; the use cases use Held and a 'chosen Budget Account' decision (Defer folded into Hold). Align? | Yes, Phase 6 aligns the entity model with the use cases (also removes a bare 'account'). | |
| O-18 | 4 | Predecessor behaviour with no FR: de-authorize a bank; reset settings to defaults; migrate a locked mapping to another Budget Account. Specify any? | De-authorize: yes (new FR in Grant Bank Consent; revoking consent is a privacy basic). Reset to defaults: no. Migration: no (vision rules out moving history). | |
| O-19 | 4 | UC-001 BR-007 counts the request limit per calendar day. Device-local day or UTC (D-19)? | Device-local day (what the operator sees); the PSD2 limit itself is a rolling 24 h, which the cooldown keeps far below. | |

## 7. Plan closed

- **Date closed:**
- **What shipped:**
- **Drift resolved / carried:**
- **Owed to construction:**
- **Verdict on the method (operator):**
