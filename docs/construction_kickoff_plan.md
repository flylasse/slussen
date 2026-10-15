# Plan: Construction Kickoff for Slussen

**Initiative:** Turn the Approved specification into the first installable Slussen: decide the architecture, design every screen as an HTML mockup, build the module skeleton, and implement one vertical slice (UC-012 Set Up an Own Build + UC-008 Check Import Status) end to end, ending with the operator's own build on the operator's phone.
**Target repo / branch:** `C:\work\slussen`, branch `construction-kickoff` (from `main` at `852c4d2`), one PR at the end.
**Specification (input, Approved):** `docs/vision.md`, `docs/requirements.md`, `docs/glossary.md`, `docs/entity_model.md`, `docs/use_cases.puml`, `docs/use_cases/UC-001..UC-012`. What the bootstrap left owed to construction is §7 of `docs/archive/bootstrap_aiup_spec_plan.md`.
**Evidence (read-only):** `C:\work\ynab-import-android`: its Gradle module layout and its six HTML mockups in `docs/archive/*mockup*.html`.
**Method:** AIUP Marketplace at commit `05af8163838df704620ebe1121cd6019dbce276c`, local clone `C:\work\marketplace`. There is **no Android/Kotlin stack plugin** (bootstrap O-03), so construction follows `docs/workflow.md` (construction inputs, traceability markers, requirement status) and uses `aiup-vaadin-jooq/skills/implement` as the pattern for the implementation phase, with every deviation recorded in §5. If `git -C C:\work\marketplace rev-parse HEAD` differs, stop and ask.
**Version:** this PR sets `VERSION` to `0.1.0` (operator decision: first version of Slussen; the merged bootstrap PR counts as 0.0.0). Later PRs follow `AGENTS.md` §5.
**Gate tools:** `python tools/lint_specs.py --strict`; from Phase 3 on, `tools/check_project_rules.py`; from Phase 6 on, `gradlew build` (compile, unit tests, Konsist architecture tests).

---

## 1. How this plan is executed (global prompt for the orchestrator)

The rules of the bootstrap plan's §1 apply unchanged: one sub-agent per phase with a clean context and the verbatim §4 prompt; the orchestrator runs every gate itself; one commit per phase, pushed before the next phase starts; one re-dispatch and at most one fix commit on a red gate; sub-agents return Clarified / Assumed / Open; §5 and §6 are updated in the phase's commit; `ynab-import-android`, `marketplace` and the book repo are never touched, and `scratch/` is never committed. In addition:

1. **Three operator stops.**
   - **Architecture gate** after Phase 2. The orchestrator and the operator discuss the module boundaries before anything is built; the operator sets each ADR to Accepted.
   - **Mockup gate** after Phase 5. The operator reviews every screen before a single line of application code exists.
   - **Acceptance** in Phase 9. The operator builds with their own Build Configuration and installs the app on their phone.

   The orchestrator never moves past a stop on its own.
2. **No private data reaches an agent.** The operator's Build Configuration holds credentials and real Bank Account names. No agent reads, writes or prints it. Mockups and tests use invented names and amounts only.
3. **Specification first.** If construction shows that a use case, rule or entity is wrong or missing, the phase stops. The orchestrator records the finding in §6, and the specification is changed (operator-approved) before the code. Code never silently departs from an Approved use case.
4. **The three `AGENTS.md` §2 project rules** apply to every document a phase writes. From Phase 3 on, they are checked by tool.

**Why these model choices.** Phase 0 is mechanical, so it runs on the fast tier. ADRs, mockups and code run on the strongest tier. The two reviews run on a **different model** from the authoring phases (Centaur's cross-model rule) and only report. Phase 2 goes further: it runs on a model from **another vendor** (OpenAI, through Codex CLI), which the operator starts with the prompt in §4. A reviewer trained differently has different blind spots. This deviates from §1's dispatch rule in two ways: the operator, not the orchestrator, dispatches the reviewer; and the phase has two commits, the reviewer's and the orchestrator's. Both are recorded in §5.

**External reviewer focus.** The external reviewer reviews this repository only, plus the Phase 0 module list pasted into its prompt. It does not read `C:\work\ynab-import-android` or `scratch/`: the review judges the ADRs against the Approved specification, and the predecessor is evidence for the authors, not a standard for the reviewer. (Privacy is not the reason; the operator already uses these CLIs on all repositories.)

## 2. Prerequisites (operator, before kickoff)

- [ ] JDK 17 (found: 17.0.12) and an Android SDK with build tools for `targetSdk` 35, with `ANDROID_HOME` set (not set on 2026-10-14).
- [ ] `adb` on PATH or a known path (found: `C:\work\scrcpy-win64-v4.0\adb`), and the phone set up for USB or wireless debugging.
- [ ] `git -C C:\work\slussen status` clean on `construction-kickoff`.

## 3. Phase table

| Phase | What | Writes | Model | Gate (orchestrator runs it) | Commit message |
|---|---|---|---|---|---|
| 0 | Extract construction evidence | `scratch/construction_inventory.md`; plan header | `flash` | inventory lists the predecessor's modules with their dependencies, the six mockups with the screens they show, and the toolchain versions; `lint_specs.py --strict` exits 0 | `docs: phase 0 - extract construction evidence` |
| 1 | Architecture decisions (proposed) | `docs/adr/ADR-001..` | `inherit` | every ADR has Context, Decision, Consequences and Status Proposed; ADR-001 names every module and every allowed dependency; every use case maps to the modules it touches; `lint_specs.py --strict` exits 0 | `docs: phase 1 - architecture decisions proposed` |
| 2 | Adversarial architecture review (report only), **external reviewer** | `docs/reviews/phase2_architecture_review.md` (reviewer's commit); findings copied into §6 (orchestrator's commit) | external: Codex CLI (OpenAI), run by the operator | the reviewer's commit changes only its review file; every finding has an ID, a location and evidence; the orchestrator's follow-up commit changes only this plan | reviewer: `docs: phase 2 - architecture review findings (external)`; orchestrator: `docs: phase 2 - record external review in plan` |
| — | **Architecture gate — orchestrator stops here** | operator discusses the ADRs and the findings, the ADRs are revised, each ADR is set to Accepted | operator | operator says "resume" | operator's own commit |
| 3 | Project-rule check | `tools/check_project_rules.py` (+ self-test) | `inherit` | `--self-test` passes; the check runs clean on current `docs/`; it fails on planted bare "account", on an unmarked Slussen number and on a lowercase glossary term | `tools: phase 3 - project rule check` |
| 4 | HTML mockups and icon proposals | `docs/mockups/*.html`, `docs/mockups/index.html`, `docs/mockups/icon-*.svg` | `inherit` | every operator-facing step of UC-001..UC-011 is reachable on a mockup screen (traceability table in `index.html`); only invented names and amounts; `check_project_rules.py` exits 0 | `docs: phase 4 - screen mockups and icon proposals` |
| 5 | Adversarial mockup review (report only) | findings into §6 | `pro` | every finding has an ID and a location; no file other than this plan changed | `docs: phase 5 - mockup review findings` |
| — | **Mockup gate — orchestrator stops here** | operator reviews the mockups and the findings, the mockups are revised, an icon is chosen | operator | operator says "resume" | operator's own commit |
| 6 | Module skeleton | Gradle modules per ADR-001, Konsist tests, Room setup, Build Configuration loading per ADR; the app's version name read from `VERSION` (`AGENTS.md` §5) | `inherit` | `gradlew build` passes, including the Konsist tests; a planted forbidden dependency makes them fail | `build: phase 6 - module skeleton` |
| 7 | Vertical slice UC-012 + UC-008 | use case tests first, then the implementation, with the `workflow.md` markers adapted per ADR | `inherit` | `gradlew build` passes; every BR of UC-012 and UC-008 has a test that names it; every BR the code enforces has its implementation marker; UC-008 screen matches the accepted mockup | `feat: phase 7 - set up an own build and check import status` |
| 8 | README how-to | `README.md` adopting-developer section; the Configuration Templates for SimpleFIN, Enable Banking and YNAB | `inherit` | the how-to covers clone or fork, where the Build Configuration goes (UC-012 BR-002), build and install; the templates hold no real values; `check_project_rules.py` exits 0 | `docs: phase 8 - adopting developer how-to and templates` |
| 9 | **Acceptance — operator** | operator's own Build Configuration (never committed, never read by an agent), each Start Date = the day after the predecessor's last upload | operator | the app builds, installs and shows the operator's Bank Accounts per UC-008; misplacing the configuration fails the build (UC-012 A6) | none |
| 10 | Close and archive | §7; requirement statuses per `workflow.md`; plan → `docs/archive/` | `inherit` | `lint_specs.py --strict` and `check_project_rules.py` exit 0; §7 filled | `docs: phase 10 - close construction kickoff plan` |

## 4. Phase prompts

Every phase prompt starts with: *"Read `C:\work\slussen\AGENTS.md` and this plan's §1. You are the executor for Phase N only. Write only the files listed for your phase. Return your Clarified / Assumed / Open items, and any place where you could not follow AIUP exactly and why."*

### Phase 0 — Extract construction evidence (`flash`)

Read the predecessor at its current `origin/main` (record the commit in the plan header). In `scratch/construction_inventory.md`, lists and paths only, no interpretation:
- every Gradle module, its plugins and its project dependencies;
- the toolchain: Kotlin, AGP, Compose BOM, JUnit, `minSdk`/`targetSdk`;
- each file in `docs/archive/*mockup*.html`, with the screens and states it shows;
- the persistence classes and their storage.

### Phase 1 — Architecture decisions (`inherit`)

Write ADRs in `docs/adr/` (`ADR-NNN-<kebab-name>.md`: Status, Context, Decision, Consequences, Alternatives), all `Status: Proposed`. At least:
- **ADR-001, module boundaries:** a core with no Android or provider dependency; one module per Data Provider, per Budget App and per File Pre-processor; Android persistence; the app/UI. State the allowed dependency directions, the plug-in contracts (Data Provider, Budget App with its declared markers, File Pre-processor with the Standard Import Format) and the Konsist rules that enforce them. Map every use case to the modules it touches. Use the predecessor's layout as evidence, not as a template.
- **ADR-002, persistence with Room:** atomic write of the Sent Record before an upload; what is backed up and what is excluded (journal, logs, bank sessions; NFR-010/011, vision Sensitive data).
- **ADR-003, Build Configuration:** read only from the ignored location; refuse a misplaced one (FR-093); never logged.
- **ADR-004, traceability markers for Kotlin:** the `workflow.md` markers adapted to JUnit 5, as the stack plugins do for their frameworks.

Record every place AIUP gives no guidance for an Android stack.

### Phase 2 — Adversarial architecture review (external: Codex CLI)

The operator starts Codex CLI with GPT-6 Astra at Max reasoning (`/model` → GPT-6 Astra → More reasoning… → Max; OpenAI's model guidance names Astra its most capable model), in `C:\work\slussen` on branch `construction-kickoff`, after the orchestrator has pushed Phase 1, and gives it the prompt below. The orchestrator first pastes the module list from `scratch/construction_inventory.md` into the marked place, after checking it holds no real names.

```text
You are an external reviewer of architecture decisions for Slussen, an Android app that fetches bank
transactions and uploads them to a budgeting app. The project uses Martinelli's AI Unified Process (AIUP):
the specification in docs/ is Approved and is the source of truth; the architecture decisions in docs/adr/
are Proposed and are what you review. Another AI model wrote them. Your job is to find what is wrong,
missing or unenforceable, not to praise or rewrite.

Read first, in this order: AGENTS.md; docs/vision.md; docs/requirements.md; docs/glossary.md;
docs/entity_model.md; docs/use_cases.puml and docs/use_cases/UC-*.md; then every file in docs/adr/.

The predecessor app's Gradle modules and their dependencies, for comparison only (not a template):
<<< orchestrator pastes the module list from the Phase 0 inventory here >>>

Review the ADRs against the specification. Look in particular for:
1. Boundaries that cannot be enforced, or Konsist rules that would not catch a violation.
2. Plug-in contracts (Data Provider, Budget App, File Pre-processor) that leak one provider's or YNAB's
   vocabulary or behaviour into the core (vision: Replaceable Budget App; NFR-014, NFR-015; C-009).
3. Use cases or business rules that cannot be built within the stated boundaries, or that need a module
   dependency the ADRs forbid. Check every UC-001..UC-012.
4. Guarantees the persistence decision does not secure: exactly once (Sent Record written before upload),
   durability across reinstall and backup restore, what must stay out of backups (NFR-010, NFR-011).
5. The Build Configuration: read only from the ignored location, misplaced configuration refused (FR-093,
   UC-012), never logged.
6. Missing decisions an implementer would have to guess.
7. Anything that contradicts AGENTS.md (its three project rules included).

Rules:
- Do not modify any file except the one review file below. Do not edit the ADRs or the specification.
- Do not access anything outside this repository, and do not read scratch/.
- Every finding gets an ID A-01, A-02, ..., a location (file and section or line), the evidence (quote or
  cite the requirement, rule or ADR text), a severity (High, Medium, Low) and, in one line, what would fix it.
- Mark a finding "uncertain" when you are not sure, rather than leaving it out.
- End with a short verdict: are the ADRs ready to be Accepted, and which findings block that.

Write the review to docs/reviews/phase2_architecture_review.md with a header naming yourself (model and
version) and the commit you reviewed (git rev-parse HEAD). Then commit only that file with the message
"docs: phase 2 - architecture review findings (external)" and push the branch. Do nothing else.
```

The orchestrator then runs the gate:
- The reviewer's commit changes only the review file.
- Every finding has an ID, a location and evidence.

It then copies the findings into §6, with the reviewer's model and version, and fills §5 in its own commit.

### Phase 3 — Project-rule check (`inherit`)

Write `tools/check_project_rules.py`, with `--self-test`, which checks `docs/` for the three `AGENTS.md` §2 rules:
- bare "account";
- a duration or count with no setting, default or source in its sentence;
- a glossary term in lowercase, and an entity or attribute name in prose without code notation.

Also check `entity_model.md` names against the glossary Avoid words (bootstrap Phase 3 tooling gap). Every exception the check allows must be listed in the script.

### Phase 4 — HTML mockups and icon proposals (`inherit`)

Read the predecessor's six mockups first. The operator is about 95% happy with that UI, so adopt their look, layout and navigation, and change only what the Approved use cases require. Do not copy their content.
- One self-contained HTML file per screen, plus `index.html`, which links every screen and has a table mapping each operator-facing use case step to its screen.
- Use invented Bank Account names, Budget names, payees and amounts only; never the predecessor's real ones.
- Show the states the use cases name: empty, blocked, outdated, Guessed Transfer, Outcome Unknown, consent expiring, etc.
- Propose 2–3 app icons as SVG.

### Phase 5 — Adversarial mockup review (`pro`)

Review the mockups against the use cases: missing states or alternative flows, screens that mirror the predecessor where the specification differs (§6 drift D-01..D-37 of the bootstrap), glossary terms not used, real data. Report findings `M-nn` into §6.

### Phase 6 — Module skeleton (`inherit`)

Build the Gradle modules, dependencies, Konsist tests, Room setup and Build Configuration loading exactly as the Accepted ADRs say, with no use case behaviour yet. Prove the Konsist rules by a forbidden dependency that fails them (planted, then removed).

### Phase 7 — Vertical slice UC-012 + UC-008 (`inherit`)

Follow `aiup-vaadin-jooq/skills/implement` as the pattern and record every adaptation:
- **Inputs:** the inputs from `workflow.md`'s *Construction inputs*: only the requirements on each use case's Requirements line, the entity model, the glossary and the ADRs.
- **Tests first,** carrying the use case and BR ids per ADR-004.
- **Then the implementation,** with a marker per enforced BR.
- **The UC-008 screen** follows the accepted mockup.

Stop and report if the specification is wrong or incomplete (§1.3).

### Phase 8 — README how-to and templates (`inherit`)

Write the adopting-developer how-to in `README.md`: clone or fork, where the Build Configuration goes, build, install. Write one Configuration Template per Data Provider and Budget App of this build, saying what to fill in and where to get it, with no real values.

### Phase 10 — Close (`inherit`)

- Fill §7.
- Set requirement statuses from the use case statuses as `workflow.md` defines them.
- Move this file to `docs/archive/construction_kickoff_plan.md`.
- Open the PR.

## 5. Methodology evaluation log

Same columns as the bootstrap plan. One row per phase, written by the orchestrator in the phase's commit.

| Phase | Skill(s) followed | Followed as written? | Deviations and why | Skill silent on | Gate result | Assumptions / Open | Re-dispatches | Notes |
|---|---|---|---|---|---|---|---|---|
| 0 | | | | | | | | |
| 1 | | | | | | | | |
| 2 | | | | | | | | |
| Architecture gate | | | | | | | | |
| 3 | | | | | | | | |
| 4 | | | | | | | | |
| 5 | | | | | | | | |
| Mockup gate | | | | | | | | |
| 6 | | | | | | | | |
| 7 | | | | | | | | |
| 8 | | | | | | | | |
| 9 | | | | | | | | |
| 10 | | | | | | | | |

## 6. Findings, specification changes and open questions

**Architecture review findings (Phase 2) and dispositions:**

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|

**Mockup review findings (Phase 5) and dispositions:**

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|

**Specification changes made during construction (§1.3):**

| ID | Found in phase | Specification element | Change | Operator approval |
|---|---|---|---|---|

**Open questions for the operator:**

| ID | From phase | Question | Recommended answer | Operator's answer |
|---|---|---|---|---|

## 7. Plan closed

- **Date closed:**
- **What shipped:**
- **Specification changes:**
- **Owed to the next plan:**
- **Verdict on AIUP construction for an Android stack (operator):**
