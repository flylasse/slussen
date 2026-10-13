# Plan: Bootstrap the AIUP Specification Core for Slussen

**Initiative:** Mine `ynab-import-android` into a living AIUP specification for Slussen, before any Slussen code exists.
**Target repo / branch:** `C:\work\slussen`, branch `initial-aiup-scaffolding`, one PR at the end.
**Evidence (read-only):** `C:\work\ynab-import-android` — code and tests, `docs/archive/` spec/plan pairs, every merged PR description.
**Method:** AIUP Marketplace `aiup-core` at commit `05af8163838df704620ebe1121cd6019dbce276c` (2026-10-13), local clone `C:\work\marketplace`; book corpus `C:\work\book-context-martinelli-spec-driven-dev\corpus`. If `git -C C:\work\marketplace rev-parse HEAD` differs, stop and ask.
**Gate tool:** `python tools/lint_specs.py` (wraps the Marketplace `spec_lint.py`).

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

**At the vision gate (operator).** Read `docs/vision.md`, correct it by hand, commit (`docs: vision - operator corrections`) or accept as drafted, and say "resume". The operator's commit is the authoritative vision; Phase 2 reads it, not the draft. **For the evaluation:** in the book the vision is written by hand. Here it is drafted from evidence and corrected by hand before anything is derived from it. On resume, the orchestrator records in §5 how much the operator changed (`git diff --stat` between the Phase 1 commit and the operator's commit, plus a one-line summary of the kind of change). That shows how good an evidence-drafted vision is.

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
| 0 | | | | | | | | |
| 1 | | | | | | | | |
| Vision gate | — | — | — | — | — | — | — | operator diff: |
| 2 | | | | | | | | |
| 3 | | | | | | | | |
| 4 | | | | | | | | |
| 5 | | | | | | | | |
| 6 | | | | | | | | |

## 6. Drift, review findings and open questions

**Vision gate** (choices the draft made that the operator may want to change):

| ID | Choice in the draft | Alternative | Operator's decision |
|---|---|---|---|

**Drift** (predecessor spec vs PR vs code; never resolved silently):

| ID | Artifact | Spec says | Code does | Since | Proposed resolution |
|---|---|---|---|---|---|

**Review findings (Phase 5) and dispositions (Phase 6):**

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|

**Open questions for the operator (Phase 7):**

| ID | From phase | Question | Recommended answer | Operator's answer |
|---|---|---|---|---|

## 7. Plan closed

- **Date closed:**
- **What shipped:**
- **Drift resolved / carried:**
- **Owed to construction:**
- **Verdict on the method (operator):**
