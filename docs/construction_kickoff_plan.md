# Plan: Construction Kickoff for Slussen

**Initiative:** Turn the Approved specification into the first installable Slussen: decide the architecture, design every screen as an HTML mockup, build the module skeleton, and implement one vertical slice (UC-012 Set Up an Own Build + UC-008 Check Import Status) end to end, ending with the operator's own build on the operator's phone.
**Target repo / branch:** `C:\work\slussen`, branch `construction-kickoff` (from `main` at `852c4d2`), one PR at the end.
**Specification (input, Approved):** `docs/vision.md`, `docs/requirements.md`, `docs/glossary.md`, `docs/entity_model.md`, `docs/use_cases.puml`, `docs/use_cases/UC-001..UC-012`. What the bootstrap left owed to construction is §7 of `docs/archive/bootstrap_aiup_spec_plan.md`.
**Evidence (read-only):** `C:\work\ynab-import-android`: its Gradle module layout and its six HTML mockups in `docs/archive/*mockup*.html`.
**Phase 0 evidence (2026-10-14):** predecessor commit read = 46efbf004a98d802a30aba7dbd7601624bd1f19f (origin/main after fetch); modules = 8; mockups = 6.
**Method:** AIUP Marketplace at commit `05af8163838df704620ebe1121cd6019dbce276c`, local clone `C:\work\marketplace`. There is **no Android/Kotlin stack plugin** (bootstrap O-03), so construction follows `docs/workflow.md` (construction inputs, traceability markers, requirement status) and uses `aiup-vaadin-jooq/skills/implement` as the pattern for the implementation phase, with every deviation recorded in §5. If `git -C C:\work\marketplace rev-parse HEAD` differs, stop and ask.
**Version:** this PR sets `VERSION` to `0.1.0` (operator decision: first version of Slussen; the merged bootstrap PR counts as 0.0.0). Later PRs follow `AGENTS.md` §5.
**Gate tools:** `python tools/lint_specs.py --strict`; from Phase 3 on, `tools/check_project_rules.py`; from Phase 6 on, `gradlew check` (compile, unit tests, Konsist architecture tests) plus a test that packaging fails without a Build Configuration; only the Operator runs `gradlew build` with a real Build Configuration (O-P1-06, A-28).

---

## 0. Resume here (state as of 2026-10-15)

This section lets a fresh orchestrator session (local or cloud) pick up the plan on branch `construction-kickoff`. Read `AGENTS.md`, then this section, then §1, §3 and §6. Update this section in every commit that moves the plan forward.

**Where the plan stands:** the architecture gate is passed (ADRs Accepted 2026-10-15) and **Phase 3** is complete (`tools/check_project_rules.py`, S-19). **Phase 4** is complete (`docs/mockups/`). Next: **Phase 5** (adversarial mockup review), then the mockup gate.

**Done so far:**
- Phases 0, 1 and 2 are complete; A-01..A-28, B-01..B-14 and C-01 are dispositioned in §6.
- Specification changes S-01..S-18 are committed; the ADRs are aligned with them.
- The ADRs are Accepted (2026-10-15).

**Next steps, in order:**
1. **Phase 5:** adversarial mockup review; then the orchestrator stops at the mockup gate for the operator's review.

**Environment for a session that is not on the operator's Windows machine:**
- `scratch/` is git-ignored and does not exist in a fresh clone. Everything a later phase needs from it is already in this plan:
  - the Phase 0 inventory summary is in §5 row 0;
  - the operator's answers are in §6;
  - the re-review prompt is in §4.
- The gate tool `tools/lint_specs.py` needs the AIUP Marketplace clone at the pinned commit in the header. Clone `https://github.com/AI-Unified-Process/marketplace` and check out that commit. Then set `SLUSSEN_MARKETPLACE` to the clone's root. Without it, the tool looks in `C:\work\marketplace` and in a `marketplace` folder next to the repository.
- Evidence repositories, **read-only**. Never commit to them or push to them.
  - The predecessor `https://github.com/flylasse/ynab-import-android`, commit `46efbf0`. Phase 4 needs its six `docs/archive/*mockup*.html` files.
  - The book `https://github.com/flylasse/book-context-martinelli-spec-driven-dev`.
- The Android SDK, JDK 17 and `adb` (§2) are needed from Phase 6 on. Phase 9 needs the operator's machine and phone.
- **Git:**
  - Commits use the operator's configured identity, which is not the real email address.
  - End commit messages with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - End the PR body with "🤖 Generated with [Claude Code](https://claude.com/claude-code)".
  - `main` is protected by the "Force PR" ruleset.
  - Files use CRLF; keep it when editing.
- **Private data:** no agent reads, writes or prints the operator's Build Configuration (`build-config/local/`), §1.2.

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
| 2b | External re-review (report only), inside the architecture gate, after the specification changes and the ADR revision | `docs/reviews/phase2b_architecture_rereview.md` (reviewer's commit); result copied into §6 (orchestrator's commit) | external: Codex CLI (OpenAI), run by the operator (O-P2-04) | the reviewer's commit changes only its review file; every A-nn has a verdict; every B-nn has an ID, a location, evidence and a severity; the orchestrator's follow-up commit changes only this plan | reviewer: `docs: architecture gate - external re-review`; orchestrator: `docs: architecture gate - record re-review in plan` |
| 2c | External final check (report only): B-01..B-14 resolved? any new High contradiction? | `docs/reviews/phase2c_architecture_final_check.md` (reviewer's commit); result into §6 (orchestrator's commit) | external: Codex CLI, run by the operator | the reviewer's commit changes only its review file; every B-nn has a verdict; every C-nn has a location and evidence | reviewer: `docs: architecture gate - external final check`; orchestrator: `docs: architecture gate - record final check in plan` |
| — | **Architecture gate — orchestrator stops here** | operator discusses the ADRs and the findings, the ADRs are revised, each ADR is set to Accepted | operator | operator says "resume" | operator's own commit |
| 3 | Project-rule check | `tools/check_project_rules.py` (+ self-test) | `inherit` | `--self-test` passes; the check runs clean on current `docs/`; it fails on planted bare "account", on an unmarked Slussen number and on a lowercase glossary term | `tools: phase 3 - project rule check` |
| 4 | HTML mockups and icon proposals | `docs/mockups/*.html`, `docs/mockups/index.html`, `docs/mockups/icon-*.svg` | `inherit` | every operator-facing step of UC-001..UC-011 is reachable on a mockup screen (traceability table in `index.html`); only invented names and amounts; `check_project_rules.py` exits 0 | `docs: phase 4 - screen mockups and icon proposals` |
| 5 | Adversarial mockup review (report only) | findings into §6 | `pro` | every finding has an ID and a location; no file other than this plan changed | `docs: phase 5 - mockup review findings` |
| — | **Mockup gate — orchestrator stops here** | operator reviews the mockups and the findings, the mockups are revised, an icon is chosen | operator | operator says "resume" | operator's own commit |
| 6 | Module skeleton | Gradle modules per ADR-001, Konsist tests, Room setup, Build Configuration loading per ADR; the app's version name read from `VERSION` (`AGENTS.md` §5) | `inherit` | `gradlew check` passes, including the Konsist tests and the test that packaging fails without a Build Configuration; a planted forbidden dependency makes them fail | `build: phase 6 - module skeleton` |
| 7 | Vertical slice UC-012 + UC-008 | use case tests first, then the implementation, with the `workflow.md` markers adapted per ADR | `inherit` | `gradlew check` passes; every BR of UC-012 and UC-008 has a test that names it; every BR the code enforces has its implementation marker; UC-008 screen matches the accepted mockup; UC-008 A8 (Recovery Check open) is shown, its confirmation (A9) unavailable until a slice builds UC-003 (O-P2-09) | `feat: phase 7 - set up an own build and check import status` |
| 8 | README how-to | `README.md` adopting-developer section; the Configuration Templates for SimpleFIN, Enable Banking and YNAB | `inherit` | the how-to covers clone or fork, where the Build Configuration goes (UC-012 BR-002), build and install; the templates hold no real values; `check_project_rules.py` exits 0 | `docs: phase 8 - adopting developer how-to and templates` |
| 9 | **Acceptance — operator** | operator's own Build Configuration (never committed, never read by an agent), each Start Date = the day after the predecessor's last upload | operator | the `release` variant (signed per ADR-005, O-P2-05) builds, installs and shows the operator's Bank Accounts per UC-008; misplacing the configuration fails the build (UC-012 A6) | none |
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

### Architecture gate — external re-review (Codex CLI)

The operator starts Codex CLI with GPT-6 Astra at Max reasoning (as in Phase 2), in the repository on branch `construction-kickoff`, at or after commit `9b4ae1f`, and gives it this prompt verbatim. The module list is already filled in, with a placeholder for the bank-specific module.

```text
You are an external reviewer of architecture decisions for Slussen, an Android app that fetches bank
transactions and uploads them to a budgeting app. The project uses Martinelli's AI Unified Process (AIUP):
the specification in docs/ is Approved and is the source of truth; the architecture decisions in docs/adr/
are Proposed. You reviewed an earlier version (docs/reviews/phase2_architecture_review.md, findings
A-01..A-28, reviewed commit d592aa2). Since then the Operator decided dispositions (docs/construction_kickoff_plan.md
§6), the specification was changed (S-01..S-10 in that table) and the ADRs were revised; ADR-007 and ADR-008 are new.

Review the change since commit 00720b1 (git diff 00720b1..HEAD -- docs/), and read ADR-007 and ADR-008 in full.
Read AGENTS.md first.

The predecessor app's Gradle modules, for comparison only (not a template):
- :domain (kotlin.jvm; no project dependencies)
- :source-simplefin, :source-enablebanking, :source-<bank-file> (kotlin.jvm; depend on :domain)
- :budget-ynab (kotlin.jvm, serialization; depends on :domain)
- :platform (android.library; depends on :domain; included only with an Android SDK)
- :app (android.application, compose; depends on every module; included only with an Android SDK)
- :test-support (api :domain; implementation on every source/budget module)
- a root verifyArchitecture task wired into every subproject's check.

Your job:
1. For each A-01..A-28: is it resolved by the specification change and/or the ADR revision as its disposition
   says? Answer Resolved / Partly / Not resolved, with evidence (file and section).
2. New findings introduced by the revision (B-01, B-02, ...), in particular in ADR-007 (coordinator and Upload
   protocol) and ADR-008 (Import Identifier protocol), and any contradiction between the revised ADRs and the
   changed specification.
3. Anything that contradicts AGENTS.md, its three project rules included.

Rules:
- Do not modify any file except the review file below. Do not edit the ADRs or the specification.
- Do not access anything outside this repository, and do not read scratch/.
- Every new finding gets an ID B-nn, a location (file and section), the evidence, a severity (High, Medium, Low)
  and, in one line, what would fix it. Mark a finding "uncertain" when you are not sure.
- End with a short verdict: can the Operator set the ADRs to Accepted, and which findings block that.

Write the review to docs/reviews/phase2b_architecture_rereview.md with a header naming yourself (model and
version) and the commit you reviewed (git rev-parse HEAD). Then commit only that file with the message
"docs: architecture gate - external re-review" and push the branch. Do nothing else.
```

The orchestrator then runs the gate in §0 step 2 and records the result as in §0 step 3.

### Architecture gate — external final check (Codex CLI)

Decided by the operator on 2026-10-15 after the 2b re-review. The operator starts Codex CLI as in Phase 2, on `construction-kickoff` after the commit that fixes B-01..B-14, and gives it this prompt verbatim:

```text
You are an external reviewer of architecture decisions for Slussen (AIUP project; the specification in docs/
is Approved and is the source of truth; the ADRs in docs/adr/ are Proposed). You re-reviewed them in
docs/reviews/phase2b_architecture_rereview.md (findings B-01..B-14, reviewed commit e0d35c3). The Operator
dispositioned every finding (docs/construction_kickoff_plan.md §6, "Architecture re-review findings (2b)"),
the specification was changed (S-12..S-15 in that plan's specification-change table) and the ADRs were fixed.

This is a final, deliberately narrow check. Read AGENTS.md, then review only the change since commit 8c18a54
(git diff 8c18a54..HEAD -- docs/), reading surrounding sections as needed.

Answer exactly two questions:
1. For each B-01..B-14 (and the nine A-nn you graded Partly, which depend on them): Resolved / Partly /
   Not resolved, with evidence (file and section).
2. Did the fixes introduce any new contradiction of severity High: between ADRs, between an ADR and the
   specification, or inside the changed specification? Report only High findings, as C-01, C-02, ...,
   each with a location, the evidence and, in one line, what would fix it. Mark one "uncertain" if unsure.
   Do not report Medium or Low findings; the Operator has decided that further upload-protocol refinements
   go to the review of the first slice that uploads.

Rules:
- Do not modify any file except the review file below. Do not access anything outside this repository, and
  do not read scratch/.
- End with a one-line verdict: can the Operator set the ADRs to Accepted, yes or no, and which C-nn block it.

Write the review to docs/reviews/phase2c_architecture_final_check.md with a header naming yourself (model and
version) and the commit you reviewed (git rev-parse HEAD). Then commit only that file with the message
"docs: architecture gate - external final check" and push the branch. Do nothing else.
```

Gate (orchestrator): the reviewer's commit changes only its review file; every B-nn has a verdict; every C-nn has a location and evidence. Record the result in §6 with the commit `docs: architecture gate - record final check in plan`.

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
| 0 | none (mechanical extraction; no AIUP skill covers construction evidence) | — | Model tier `flash` has no Claude equivalent; ran on `haiku`, as in the bootstrap. The executor replaced operator bank and account names in mockup headings with `<bank>`/`<account>` placeholders in the inventory (not asked, harmless). | Construction evidence for a brownfield successor: AIUP's construction skills assume an empty or same-stack codebase. | `lint_specs.py --strict` 0/0/0 | Assumed: 'modules' = the 8 in `settings.gradle.kts`, 2 of them SDK-conditional; mockup screens and states read from headings and labels only. Open: none. | 0 | Gate green on first dispatch: inventory lists 8 modules with plugins and project dependencies, the toolchain (Gradle 8.14.1, Kotlin 2.3.21, AGP 8.12.0, Compose BOM 2025.06.00, JUnit 5.11.4, JVM 17, compileSdk/targetSdk 35, minSdk 26), the 6 mockups with screens and states, and 9 JSON stores plus the journal in `noBackupFilesDir`. Predecessor read at `46efbf0`, unchanged since the bootstrap. Notable evidence for Phase 1: the predecessor already enforced boundaries with a custom `verifyArchitecture` task wired into `check`, and made `:platform`/`:app` conditional on an installed Android SDK. |
| 1 | none for ADRs; `docs/workflow.md` (construction inputs, markers) and `aiup-vaadin-jooq/skills/implement` as patterns | Partly: no skill defines ADRs, Android modules, Room or build-time configuration, so everything is project-made | (1) Six ADRs, not four: ADR-005 toolchain and version, ADR-006 logging and sensitive data, added because ADR-002 and ADR-003 depend on them. (2) Each ADR has an extra 'AIUP guidance' section listing what AIUP does not cover. (3) Names changed to avoid glossary Avoid words (`*Descriptor` → `*Profile`, `UploadLedger` → `SentRecordStore`). (4) Kotlin marker entry added in the form of the JVM stacks, plus conventions AIUP lacks: `IT` suffix for instrumented tests, `@DisplayName("NFR-xxx: …")` for NFR/C in tests. | ADR format; Android stack; on-device storage and SQLite type mapping; backup and restore; migrations outside Flyway/EF/Drizzle; build-time configuration and secrets; logging; toolchain, SDK, signing, device-run tests; where `@UseCase` is defined; markers for NFRs, constraints and entity-model constraints; no `uc-coverage` agent for this stack. | `lint_specs.py --strict` 0/0/0 (the linter does not scan `docs/adr/`; the executor checked the `AGENTS.md` §2 rules by script) | Assumed: package root `slussen`; Build Configuration as Java properties, templates in `build-config/*.template.properties`, real files in ignored `build-config/local/`; generated Kotlin from the configuration, a stub when none exists; Standard Import Format is a Kotlin type; C-009 numbers declared by the Budget App plug-in; logcat only; hand-written composition root, no DI framework; instrumented tests outside `check`; a last good copy of the database per app start. Open: O-P1-01..O-P1-08. | 0 | Gate green on first dispatch: 6 ADRs (1,212 lines), each with Status Proposed, Context, Decision, Consequences, Alternatives. ADR-001 names every module with its allowed dependencies (`:plugin-api` ← `:core` and every plug-in; plug-ins never depend on `:core` or each other; `:persistence` → `:core`; `:app` composes all) and maps UC-001..UC-012 to modules. Enforcement in two layers: a Gradle `verifyModuleGraph` task and Konsist rules K-01..K-14, each failing on an empty scope (the predecessor's `verifyArchitecture` passed on an empty scope). Residual risk recorded in ADR-002: after restoring the last good database copy, exactly once for Sent Records written after that copy relies on YNAB refusing a duplicate Import Identifier. |
| 2 | none (no AIUP skill covers architecture review; the bootstrap's adversarial review pattern) | Partly | (1) Operator-dispatched external reviewer from another vendor (Codex CLI, OpenAI) instead of an orchestrator sub-agent, and two commits for the phase (reviewer's `1646ecd`, orchestrator's), as planned in §1. (2) The reviewer reports 'GPT-6' and could not confirm the Astra Max variant. (3) The reviewer received the §4 prompt with its placeholder unfilled, not the filled copy prepared by the orchestrator, so it saw no predecessor module list and did not verify the ADRs' predecessor claims. (4) The reviewer also skipped the Marketplace skills (outside the repository boundary). (5) Between the phases the operator made an extra commit, `50f6deb` (`build-config/local/` added to `.gitignore`), and rewrote the branch history to replace the commit email; the Phase 0 and 1 commits are now `9180c8b` and `4a0c108`. | How to review architecture against a specification; ADR acceptance criteria. | Gate run by the orchestrator: the reviewer's commit changes only `docs/reviews/phase2_architecture_review.md`; all 28 findings have an ID, a location, evidence and a severity. This commit changes only this plan. | Open: dispositions of A-01..A-28, decided at the architecture gate. | 0 | 28 findings: 15 High, 13 Medium (2 uncertain); verdict 'not ready to be Accepted'. Most High findings concern exactly once and durability (A-01..A-09), which reach past the first slice (UC-012 + UC-008), and the strength of the claimed static enforcement (A-19, A-20). The residual risk in ADR-002 is now O-P2-01. |
| Architecture gate | none (no AIUP skill covers ADR acceptance) | Partly | (1) Specification changed during construction (S-01..S-18) before the ADRs, per §1.3, through a sub-agent with the operator's approval; S-18 reversed S-17 item 1 after the operator found it introduced invisible behaviour. (2) Two new ADRs (ADR-007, ADR-008). (3) Two further external reviews by the operator's Codex CLI (2b re-review, 2c final check), which again skipped the Marketplace skills and did not run `tools/lint_specs.py` (both outside the repository boundary); C-01 was fixed and checked by the orchestrator without a further external round. (4) A resume section §0 added so another session could continue. (5) An ADR-001 module map added at the operator's request (O-P2-17); AIUP does not require one. (6) At the operator's explicit request the orchestrator cloned the operator's new, empty private configuration repository into `build-config/local/` and wrote its README there; no configuration file was read, and nothing there was committed by the orchestrator. | How to iterate an architecture review to acceptance; how an Operator versions a Build Configuration (O-P2-18). | Every specification change and ADR revision gated by the orchestrator: `tools/lint_specs.py --strict` 0/0/0, scope limited to the intended files, ADRs Proposed until acceptance. 2b and 2c reviewer commits change only their review files. | O-P2-01..O-P2-18 answered; no open item. Accepted with the ADRs: the sub-agents' Assumed items recorded in §6 (O-P2-13, O-P2-14) and in the S-17 report (statuses of the other Transfer Leg; precedence Already Present over Defer over Import Anyway). | 0 | All eight ADRs Accepted by the operator on 2026-10-15. |
| 3 | none (no AIUP skill covers project-specific rules; style of `tools/lint_specs.py`) | Yes | Scope chosen by the sub-agent: specification and ADRs (25 files, HTML included for later mockups); plans, reviews and archive excluded because they quote violations on purpose. The first run was not clean (14 real findings); routed to S-19 instead of exceptions, plus the operator's HSA ruling (O-P2-19). | What counts as a duration or count, and as a source: defined by markers and heuristics listed in the script. Known limits: single-word terms other than Operator are not case-checked; single-word attribute names in prose and spelled-out counts are not detected. | Green: `--self-test` 23 planted cases and a clean text pass; it fails on a planted bare "account", an unmarked Slussen number and a lowercase glossary term; 0 findings on `docs/` after S-19 (`39ac9bc`). | Assumed: ADRs in scope; only Operator among single-word terms; 0 and 100% exempt; a filled Source cell sources its row. Open: `spec_lint.py` (Marketplace) misses the plural "entries" of an Avoid word; not changed (outside the repository). | 1 (exceptions for HSA, the Data Provider's own window and `*_bank_transaction_id` foreign keys) | Seven exceptions, each with its reason in the script. |
| 4 | none (no AIUP skill covers mockups; the predecessor's six mockups as the look to adopt) | Yes | 17 screens plus `index.html` and three icon SVGs; each screen shows its states as separate phone frames with anchors, no JavaScript. Files generated by a sub-agent script outside the repository; the HTML is the source of truth. | How to show the Bank Consent result returning from Strong Customer Authentication (UC-007 step 6); a Lonely Transfer towards a Budget Account Slussen does not fetch (UC-005 A3, BR-005); flows that cannot occur with YNAB (UC-004 A10, UC-006 A10), drawn for a replaceable Budget App. | Green: `check_project_rules.py` 0 findings on 42 files; `lint_specs.py --strict` 0/0/0; traceability table of 148 rows covers every Operator-facing step and alternative flow of UC-001..UC-011, no broken link or anchor (sub-agent's check); orchestrator's scan against the predecessor's mockups found no copied Bank Account name, payee or amount (only generic words, font names, the Data Provider name and round amounts). | Open for the mockup gate: the three items above, interactive state toggles, and the choice of icon. | 0 | |
| 5 | | | | | | | | |
| Mockup gate | | | | | | | | |
| 6 | | | | | | | | |
| 7 | | | | | | | | |
| 8 | | | | | | | | |
| 9 | | | | | | | | |
| 10 | | | | | | | | |

## 6. Findings, specification changes and open questions

**Architecture review findings (Phase 2) and dispositions:**

Reviewer: OpenAI Codex CLI, GPT-6 (the reviewer could not confirm the Astra Max variant or a snapshot identifier), commit `1646ecd`, reviewing `d592aa2`. Verdict: not ready to be Accepted. Blocking High: A-01..A-09, A-11, A-12, A-16, A-17, A-19, A-20; also needing disposition: A-18, A-21..A-23, A-26, A-28; uncertain: A-13, A-24. Full text: `docs/reviews/phase2_architecture_review.md`.

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|
| A-01 | ADR-002 Decision §3 | High: the unique (`budget_account_id`, `import_identifier`) insert rejects the retry UC-006 A8/BR-007 requires; a plan holding a retryable Sent Record sends nothing | ADR-002 §3; UC-006 A8, A9, BR-007; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-02 | ADR-002 §3, Consequences; ADR-001 §4.2 | High: exactly once cannot be destination-independent: a lost request and a lost response leave the same local state; `BudgetAppWriter.create` has no idempotency guarantee | UC-006 BR-006, BR-007; ADR-002 Consequences; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-05 (Budget App minimum: refuses a reused Import Identifier) and ADR revision. Re-review: Resolved. |
| A-03 | ADR-002 Decision §6, Consequences | High: restoring the last good copy can lose Sent Records, Account Mapping locks and decisions and continue; informing the Operator does not meet the guarantee | vision Exactly once; UC-006 BR-006; FR-042; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-06 (Uploads blocked after a restore until a Comparison and the Operator's confirmation) and ADR revision. Re-review: Partly, see B-05. |
| A-04 | ADR-002 Decision §4, Consequences; O-P1-05 | High: backup inclusion is not a zero-loss restore; stale, absent or oversized (25 MB) backups restore older or no history | NFR-010; FR-042; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-04 and S-06, and ADR revision. Re-review: Partly, see B-05. |
| A-05 | ADR-002 §2, ADR-003 §6; O-P1-04 | High: no immutable identity matches a stored Bank Account to its Build Configuration entry; rename, reorder or removal could bypass a locked Account Mapping | UC-009 A8, BR-003, BR-006; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-03 (O-P1-04) and ADR revision. Re-review: Resolved. |
| A-06 | ADR-001 §4, §4.2, §7 UC-003; ADR-002 §3 | High: the Import Identifier protocol (marker, canonical encoding, algorithm version, collisions, Re-upload occurrence allocation) is not frozen | UC-003 BR-002; FR-074; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Partly, see B-08, B-09, B-10. |
| A-07 | ADR-001 §3, §7 UC-002/UC-003; ADR-002 §2, §3 | High: no atomic operation for a whole file import or for a Budget App change set together with its read cursor | UC-002 BR-001; UC-003 BR-016; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Partly, see B-04. |
| A-08 | ADR-001 §3, §5, §7 UC-006; ADR-002 §2, §3 | High: the Upload pause and Confirmed state have no concurrency, admission or process-death protocol | UC-006 BR-004, BR-005; FR-035, FR-036; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Partly, see B-04, B-07. |
| A-09 | ADR-001 §4.2; ADR-002 §2, §3 | High: after a lost Upload response the read model cannot recover the Transfer Counterpart unambiguously | UC-005 BR-009, BR-010; UC-006 BR-010, BR-011; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Partly, see B-01, B-03. |
| A-10 | ADR-001 §4.2, §5, Consequences | Medium: Budget App capability flags (approval marker, linked Transfers) leave behaviour the core must guess; no capability floor; fallbacks can change a confirmed Upload Plan | NFR-015; UC-012 BR-006; UC-006 BR-008, A10; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-11 | ADR-001 §4.1 `FetchResponse` | High: an uninterpretable item with no content cannot become a Bank Transaction kept as Needs Review | FR-009; NFR-002; UC-003 BR-012; `BANK_TRANSACTION`; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-07 and ADR revision. Re-review: Resolved. |
| A-12 | ADR-001 §4.1, §4.2, §5 | High: consent operations and all Budget App calls carry no request gate or timeout | UC-001 BR-007; NFR-016; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-13 | ADR-001 §4.1 (uncertain) | Medium: one Data Provider-wide outcome and stop rule may stop unaffected Banks and Bank Accounts | UC-001 A5, A8, A9, BR-010; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-14 | ADR-001 §4.1; ADR-002 §1, §2 | Medium: Consent Request callback state has no storage or atomic consume/replace operation | NFR-013; UC-007 BR-002; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-15 | ADR-001 §4, §4.1, §7 UC-009; ADR-002 §1, §2 | Medium: no retained proposal data (offered Bank Accounts, last four digits) for later identification from stored data | UC-009 step 3, A6; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-08 and ADR revision. Re-review: Resolved. |
| A-16 | ADR-003 Decision §5, §6, Consequences | High: placement rules P-1..P-4 miss recognizable configuration under a renamed file (keys not prefixed `slussen.`); generator not ordered after validation | FR-093; UC-012 A6, BR-009; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Partly, see B-13. |
| A-17 | ADR-006 Decision §1, §2, §5, §6 | High: the free-text `name` of `SlussenLog.event`/`detail` bypasses the typed logging boundary | NFR-007; NFR-008; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-18 | ADR-006 §1, §4; ADR-001 §2, §3 | Medium: plug-ins cannot reach `SlussenLog` (in `:core`) yet ADR-006 tells them to use it | ADR-001 dependency rules; ADR-006 §4; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-19 | ADR-001 §6.1, §6.2, Consequences; ADR-006 §5 | High: import and package checks miss fully qualified calls, JDK I/O, indirection and result filtering; claimed guarantees are too strong | ADR-001 K-05, K-06, K-11, K-12; L-01; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-20 | ADR-001 §6; ADR-003 §5; ADR-005 §3 | High: checks wired into `check` do not run on `:app:assembleDebug` or targeted module builds | ADR-001 §6 'cannot be skipped by building a subset'; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-21 | ADR-001 §1, §2, §6.2 K-07 | Medium: K-07 and the library allowlist reject the prescribed `:testing` fakes and the Kotlin standard library | ADR-001 §6.2; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Partly, see B-11. |
| A-22 | ADR-003 Decision §2, §3, §6 | Medium: validation fails a File Pre-processor without a template file, and accepts a Build Configuration with no Budget or Bank Account | UC-008 precondition; UC-012; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. Re-review: Resolved. |
| A-23 | ADR-001 §4.2, §7 UC-011; ADR-002 §1, §2 | Medium: Journal Export cannot get the Budget App memo; `BUDGET_APP_TRANSACTION` has no memo (inherited specification mismatch) | UC-011 steps 3-4, A2; FR-068; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-09 and ADR revision. Re-review: Resolved. |
| A-24 | ADR-002 §6; ADR-005 §1 (uncertain) | Medium: `VACUUM INTO` on `minSdk` 26, snapshot replacement and non-native corruption paths are unproven | ADR-002 §6; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision; the proof is a prerequisite for the first slice that uploads, not for UC-012 + UC-008. Re-review: Resolved. |
| A-25 | ADR-005 Decision §6, Consequences; O-P1-08 | Medium: debug-key default leaves the upgrade guarantee dependent on one machine | FR-042; NFR-017; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): O-P1-08 and O-P2-05; ADR revision. Re-review: Resolved. |
| A-26 | ADR-003 §4; ADR-001 §4.3; O-P1-02 | Medium: File Format Upload Window precedence is an unapproved specification reading | FR-084, FR-086; UC-002 BR-004; UC-010 BR-001; `AGENTS.md` §1; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-01 (O-P1-02) and ADR revision. Re-review: Resolved. |
| A-27 | ADR-002 §3, §4, §5, §9; ADR-004 §2; ADR-005 §3; plan Phases 6, 7, 9 | Medium: gates exclude instrumented and live tests, so the Sent Record, migration and Budget App guarantees are never proven before Uploads are enabled | C-011; ADR-005 §3; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision; device, restore, migration and live Budget App evidence gate the first slice that uploads. Re-review: Resolved. |
| A-28 | ADR-003 §6, §8, Consequences; O-P1-06; plan Phases 6, 7 | Medium: the plan's `gradlew build` gate cannot pass without private Build Configuration | `AGENTS.md` §3; ADR-003; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): O-P1-06 and ADR revision; plan gates for Phases 6 and 7 changed to `gradlew check` plus the packaging-refusal test. Re-review: Partly, see B-12. |

**Architecture re-review findings (2b) and dispositions:**

Reviewer: OpenAI Codex CLI, GPT-6 (Astra Max variant not attestable by the reviewer), commit `2cc8bff`, reviewing `e0d35c3` against base `00720b1`. A-01..A-28: 19 Resolved, 9 Partly, 0 Not resolved. 14 new findings: 7 High (B-01..B-07), 6 Medium (B-09 uncertain), 1 Low. Verdict: not ready to be Accepted; B-01..B-07 block, B-08 and B-10..B-13 to be resolved before acceptance. The reviewer recomputed the ADR-008 test vectors and they match. Full text: `docs/reviews/phase2b_architecture_rereview.md`.

| ID | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| B-01 | High | ADR-007 §4, §6; ADR-001 §4.2.3; UC-006 BR-009 | A newly created Transfer Counterpart cannot be cleared in the same Upload: a `ClearCounterpart` item needs a reference that a new linked Transfer does not have yet; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Clearing intent for a new linked Transfer is part of the confirmed plan; its counterpart is resolved by a defined read before clearing in the same Upload. Final check: Resolved. |
| B-02 | High | ADR-007 §4, §6, §7; ADR-002 §3 O-7/O-8, §4 | Clearing items go through the Sent Record insertion protocol; they need their own preparation, permit and outcome rules; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Clearing items get their own preparation, permit and outcome rules; they never insert or reset a Sent Record. Final check: Resolved. |
| B-03 | High | ADR-001 §4.2.3; ADR-002 §2, §3 O-4/O-5; `BUDGET_APP_TRANSACTION` | The normalized read metadata (Transfer links, merge target, Cleared, approved) has no durable storage, so O-4 can advance the cursor and lose what O-5 needs; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): S-12, then ADR fix (durable storage, written atomically with the cursor). Final check: Resolved. |
| B-04 | High | ADR-002 §3 O-4/O-5; ADR-007 §4, §6, §8 | An interrupted Comparison (O-4 committed, O-5 not) leaves old statuses usable with a new cursor; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. An incomplete-Comparison marker blocks preparation until the Comparison is recomputed and committed. Final check: Resolved. |
| B-05 | High | ADR-002 §6, §8; ADR-007 §3, §8; FR-095 | Process death between writing `installation.id` and opening the Recovery Check makes the next start look normal; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Startup intent persisted before the restored or replacement data is used; cleared only once the Recovery Check is durable; interruption tested at every boundary. Final check: Resolved. |
| B-06 | High | ADR-002 §6, §8; NFR-005, NFR-006; UC-008 A1; UC-010 BR-004 | Journal corruption opens a Recovery Check and blocks Uploads, contradicting the journal-independence requirement (also a contradiction inside the specification); `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): S-13, then ADR fix. Only decision-critical data opens a Recovery Check; journal-only damage shows a notice. Final check: Resolved. |
| B-07 | High | ADR-007 §5, §6; ADR-002 §4; UC-006 A5; UC-003 BR-011/BR-014 | Confirmation does not recheck expiry after the network read, nor the Upload Window when the date changes; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Expiry and date-dependent eligibility rechecked at the transition to writing, after the network read. Final check: Resolved. Date-dependent part reversed by the operator (2026-10-15, O-P2-16, S-18): eligibility is fixed when the plan is prepared; expiry is still checked. |
| B-08 | Medium | ADR-008 §2, §5; ADR-001 §4.1 | Unicode normalization merges distinct opaque Stable References before collision detection (reproduced); `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Stable References are opaque, never normalized. Final check: Resolved. |
| B-09 | Medium (uncertain) | ADR-008 §7; ADR-001 §4; UC-009 BR-004 | The Bank Account fingerprint keeps digits only and may merge distinct alphanumeric numbers; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. The fingerprint keeps letters and is scoped by Data Provider and Bank. Final check: Resolved. |
| B-10 | Medium | ADR-008 §1, §6; ADR-001 §4.2.3; UC-003 BR-008 | Ownership is recognised by the full format, while the specification says any Import Identifier beginning with the marker is Slussen's; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): S-14 (clarifies the existing rule), then ADR fix. Ownership = begins with the marker; a malformed marked identifier goes to review, never to Other Import Path. Final check: Partly, see C-01. |
| B-11 | Medium | ADR-001 §1, §2, §4.2, §6.3 B-04 | Bytecode rule B-04 rejects `:plugin-api`'s writer contract and the `:testing` fake; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Bytecode scopes allow the contract declaration and the `:testing` fake. Final check: Resolved. |
| B-12 | Medium | ADR-003 §3, §6; ADR-005 §3; plan Phases 6-7 | The validator rejects an absent Build Configuration before the `ABSENT` stub can be generated, so `gradlew check` cannot pass without one; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. An absent Build Configuration is valid for `check`; packaging refuses it. Final check: Resolved. |
| B-13 | Medium | ADR-003 §2, §5 P-5 | Placement matching misses the properties whitespace separator and escapes; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): ADR fix. Placement recognition uses the reader's properties grammar, including whitespace separators and escapes. Final check: Resolved. |
| B-14 | Low | vision Goals, Exactly once; `AGENTS.md` §2 | Revised prose writes 'the operator' and 'upload' in lowercase where the defined terms are meant; `docs/reviews/phase2b_architecture_rereview.md` | Accepted (2026-10-15): S-15 (editorial). Final check: Not resolved (the vision rewrite of `70f2b61` returned to lowercase "operator"); S-16. |

**Architecture final check (2c):**

Reviewer: OpenAI Codex CLI, GPT-6, commit `8c7092f`, reviewing `70f2b61` against base `8c18a54`. B-01..B-14: 12 Resolved, 1 Partly (B-10), 1 Not resolved (B-14). The nine Partly A-nn: 8 Resolved, A-06 Partly (via C-01). One new High: C-01. Verdict: not yet; C-01 blocks. Full text: `docs/reviews/phase2c_architecture_final_check.md`.

| ID | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| C-01 | High | UC-003 BR-001 vs BR-008, steps 3–5, BR-014; `BUDGET_APP_TRANSACTION` constraints; ADR-001 §4.2.3; ADR-008 §1; UC-005 BR-010 | UC-003 BR-001's order lets a Transfer Leg follow its Transfer before the S-14 rule sends a Bank Transaction resembling a Slussen Unsupported Format Import to Needs Review, so a non-guessed Transfer can be uploaded without that review; `docs/reviews/phase2c_architecture_final_check.md` | S-16, then a check by the orchestrator: the unsupported-format review takes precedence over Transfer eligibility and holds until an explicit Review Decision. Orchestrator check (2026-10-15): resolved in UC-003 BR-001/BR-008/BR-014, UC-004 BR-006, UC-005 BR-010, `BUDGET_APP_TRANSACTION`, ADR-001 §4.2.3/§7, ADR-007 §4, ADR-008 §1; lint 0/0. |

**Mockup review findings (Phase 5) and dispositions:**

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|

**Specification changes made during construction (§1.3):**

| ID | Found in phase | Specification element | Change | Operator approval |
|---|---|---|---|---|
| S-01 | Architecture gate (O-P1-02, A-26) | FR-084, FR-086, glossary, `FILE_FORMAT.upload_window_days`, `OPERATOR_SETTINGS`, UC-002, UC-003 BR-011, UC-010 BR-001 | One Operator Setting, the file upload window, for all Transaction Files: 60 days by default, range 7–365. Replaces the per-File-Format window. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-02 | Architecture gate (O-P1-03) | entity model, glossary | Add `BANK_SESSION` (revoked per FR-082 and UC-007 BR-007, never backed up). The Budget App read cursor stays technical. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-03 | Architecture gate (O-P1-04, A-05) | `BANK_ACCOUNT`, UC-009, UC-012 | Add a configuration key, set once in the Build Configuration and never changed; it ties a stored Bank Account to its Build Configuration entry. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-04 | Architecture gate (O-P1-05, A-04) | new NFR | Slussen warns the Operator when the backed-up data nears Android's Auto Backup limit (25 MB, set by Android). | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-05 | Architecture gate (A-02) | vision Exactly once, UC-006 BR-006/BR-007, C-009, NFR-015 | A Budget App plug-in must refuse a reused Import Identifier to qualify for automatic retry. Without it, Slussen never retries an Unknown outcome automatically. Exactly once is stated as this guarantee, not as one that holds whatever the Budget App does. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-06 | Architecture gate (A-03, A-04, O-P2-01) | new FR, UC-006, UC-008 | After Slussen starts from the last good copy or from a restored backup, Uploads stay blocked until a Comparison has run and the Operator has confirmed. Replaces the warning-only answer to O-P2-01. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-07 | Architecture gate (A-11) | FR-009, NFR-002, UC-003 BR-012, UC-004, entity model | A Bank Transaction Slussen cannot interpret is kept as a raw record the Operator can review, holding whatever could be read and the reason. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-08 | Architecture gate (A-15) | UC-009, entity model | The Bank Accounts a Data Provider offered are kept with their last four digits only, until the next Fetch, so identification works from stored data. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-09 | Architecture gate (A-23) | `BUDGET_APP_TRANSACTION`, UC-011 | Add a memo to the stored Budget App transaction so the Journal Export can include it. | Approved by the Operator (2026-10-14), including the executor's assumptions (Recovery Check also after a new installation; backup warning threshold 80% by default, range 50–95%; an Unreadable Transaction can only be deferred). |
| S-10 | Architecture gate (O-P2-06, O-P2-07, O-P2-08) | new FR-096, NFR-009, `BANK_ACCOUNT.configured`, `OFFERED_BANK_ACCOUNT`, UC-008, UC-009, UC-012 | A Bank Account removed from the Build Configuration, or given a new Configuration Key, is kept out of use with its history and lock; a new key is a new Bank Account, refused if it points at a locked Budget Account. Offered Bank Accounts are never backed up. The last four digits name their outside source. | Approved by the Operator (2026-10-14). |
| S-11 | Architecture gate (O-P2-10, O-P2-11) | FR-093, UC-012 A6/BR-009; new NFR-020 Identifier Uniqueness; FR-074, UC-001 A17/BR-016, UC-002 A6, UC-003 BR-002, glossary | FR-093 names what can be recognised (recognisable keys, values marked secret). Every identifier Slussen generates has a collision made extraordinarily unlikely, at least as unlikely as a random UUID (Operator: "like uuid or similar uniqueness"); an Import Identifier collision is still detected (Unreadable Transaction, or the whole file rejected). | Approved by the Operator (2026-10-14). |
| S-12 | Architecture gate (B-03) | `BUDGET_APP_TRANSACTION`, UC-003 | Store the Transfer link (the other side's reference), the merge target, Cleared and approved for each Budget App transaction read, so a lost linked Upload can be reconciled after a restart. | Approved by the Operator (2026-10-15). |
| S-13 | Architecture gate (B-06) | NFR-005, NFR-006, FR-095, UC-008 A1, UC-010 BR-004 | Only damage to decision-critical stored data opens a Recovery Check; damage to the Decision Journal or other data excluded from decisions shows a notice and changes no Upload. | Approved by the Operator (2026-10-15). |
| S-14 | Architecture gate (B-10) | UC-003 BR-008, `BUDGET_APP_TRANSACTION` constraints | An Import Identifier that begins with Slussen's marker is Slussen's even when its format is not one Slussen supports; such a transaction goes to review and is never Other Import Path. | Approved by the Operator (2026-10-15). |
| S-15 | Architecture gate (B-14) | vision Goals, Exactly once | Defined terms in Title Case in the revised prose (Operator, Upload). | Approved by the Operator (2026-10-15). |
| S-16 | Architecture gate (C-01, B-14) | UC-003 BR-001 order, UC-005 where it states Transfer eligibility, vision | The rule for a Bank Transaction resembling a Slussen Unsupported Format Import comes before the Transfer Leg rule, and a Transfer containing such a leg is not eligible until an explicit Review Decision; defined terms in Title Case across the whole vision (Operator, Upload, Budget, Fetch where meant). | Orchestrator, to fix C-01 and B-14 within dispositions already approved (S-14, S-15); operator to confirm at acceptance. |
| S-17 | Architecture gate (O-P2-14, O-P2-15) | UC-006 step 5, BR-004, A5; UC-003 BR-001, BR-007, BR-008, BR-012, BR-014; UC-004 BR-001, BR-006; UC-005 BR-010; entity model; glossary; wording across the specification | (1) Immediately before writing, every transaction of the confirmed Upload Plan is checked to be still eligible on the current date. (2) Slussen guarantees no duplicate only of what it sent itself; resemblance to another path's Import or a Typed Entry leads to review, not a guarantee. (3) A doubtful Transfer Leg (Needs Review for any reason, or Possible Import By Other Path) holds its whole Transfer until a Review Decision; Import Anyway rejoins the Transfer, Defer holds it back, Already Present counts it as already in the Budget App. (4) 'Upload' means only the write of a confirmed Upload Plan; before that, Slussen proposes. | Approved by the Operator (2026-10-15). |
| S-18 | Architecture gate (O-P2-16) | UC-006 step 5, BR-003/BR-004, A5; FR-035; `UPLOAD_PLAN` | Reverses S-17 item 1: date-dependent eligibility (Upload Window, file upload window, Start Date, Lonely Transfer grace period) is fixed when the Upload Plan is prepared; a change of date while it awaits confirmation changes nothing; only the visible expiry and the unchanged Budget App, Bank Transactions and configuration are checked before writing. | Approved by the Operator (2026-10-15). |
| S-19 | Phase 3 (O-P2-19) | entity model (`reference_kind`, `settled_by_bank_transaction_id`, two "24 hours" sources), glossary, requirements (Adopting Developer, Other Import Paths, Consent Request), UC-003 BR-007, UC-009 A1; editorial corrections in the Accepted ADR-001, ADR-002 and ADR-004 | Project-rule findings of Phase 3: renames away from Avoid words, sources named for the 24-hour window, defined terms in Title Case, "manual entries" becomes Typed Entries. No decision changed. | Approved by the Operator (2026-10-15). |

**Open questions for the operator:**

| ID | From phase | Question | Recommended answer | Operator's answer |
|---|---|---|---|---|
| O-P1-01 | 1 | What is the one File Pre-processor module called (`:fileformat-<format>`)? The specification deliberately names no bank. | Name it after the export format, not the bank (e.g. `:fileformat-hsa-csv`); the pre-processor class inside may name the bank. | Accepted as recommended (2026-10-14). |
| O-P1-02 | 1 | The specification conflicts on who sets a File Format's Upload Window: UC-010 BR-001 and the `OPERATOR_SETTINGS` constraint say the Build Configuration; FR-084, the glossary and `FILE_FORMAT.upload_window_days` say the File Pre-processor declares it. | ADR-003 §4: the pre-processor declares a default and the Build Configuration may replace it; align the specification (a §1.3 change). | One Operator Setting, the file upload window, for all Transaction Files, not one per File Format: Data Provider Bank Accounts are fetched often, manual files come more slowly. 60 days by default, range 7–365. Replaces the per-File-Format window (FR-086, `FILE_FORMAT.upload_window_days`, glossary, UC-002, UC-003 BR-011, UC-010 BR-001, `OPERATOR_SETTINGS`): a §1.3 change (2026-10-14). |
| O-P1-03 | 1 | Two technical tables are not in the entity model: `budget_read_state` (the change cursor for UC-003 BR-016) and `bank_session` (the session FR-082 and UC-007 BR-007 call 'its session'). Add them? | Add BANK_SESSION (it carries business rules: revoke, never backed up); keep the read cursor technical, outside the entity model. | Accepted as recommended (2026-10-14). |
| O-P1-04 | 1 | `BANK_ACCOUNT` has no stable key that matches a stored record to its Build Configuration entry across rebuilds. | Add a configuration key attribute to `BANK_ACCOUNT` (a §1.3 change), set once in the Build Configuration and never changed. | Accepted as recommended (2026-10-14). |
| O-P1-05 | 1 | If `slussen.db` grows past Android's 25 MB Auto Backup limit, Android skips the backup silently. No requirement covers this. | Add an NFR: Slussen warns the Operator when the backed-up data nears the limit, and the journal (already excluded) never counts toward it. | Accepted as recommended (2026-10-14). |
| O-P1-06 | 1 | The Phase 6 and 7 gates say '`gradlew build` passes', but without a Build Configuration `build` fails by design (UC-012 A1). | Agents gate on `gradlew check` plus a test that `assemble` fails without a Build Configuration; only the Operator runs `build` (Phase 9). | Accepted as recommended (2026-10-14). |
| O-P1-07 | 1 | Where do the credentials for the live tests against a throwaway YNAB budget (C-011) come from? | From the Operator's ignored Build Configuration location, in a separate test file; live tests are tagged and never run by agents. | Accepted as recommended (2026-10-14). |
| O-P1-08 | 1 | Which variant does the Operator install, and with which signing key? It decides whether a build from another machine can upgrade in place (NFR-017). | `release`, signed with a key named in `build-config/local/signing.properties`; until answered, Phase 6 builds `debug` only. | Accepted as recommended (2026-10-14). |
| O-P2-01 | 2 | ADR-002 residual risk: after the last good copy is restored, Sent Records written after that copy are protected only by the Budget App's refusal of a reused Import Identifier (C-009) and the next Comparison. | Keep the ADR text; add a requirement (a §1.3 change). | Accepted (2026-10-14): keep the ADR text and add a requirement. When Slussen starts from the last good copy after corruption, it tells the Operator that transactions sent after that copy are protected only by the Budget App's duplicate refusal and the next Comparison, and asks the Operator to check the Budget App before the next Upload. A Budget App plug-in declares whether it refuses a reused Import Identifier. To be revisited with A-02..A-04. Superseded by O-P2-02 (2026-10-14). |
| O-P2-02 | 2 | How far does exactly once go (A-02, A-03, A-04)? | Budget App minimum (refuses a reused Import Identifier); Uploads blocked after a restore until a Comparison and the Operator's confirmation; the exactly-once requirement reworded to that guarantee. | Agreed (2026-10-14). Becomes S-05 and S-06; supersedes the warning-only answer to O-P2-01. |
| O-P2-03 | 2 | A-11, A-15, A-23: uninterpretable transactions, offered Bank Accounts, the memo. | Raw reviewable record; offered Bank Accounts kept with the last four digits until the next Fetch; memo on the stored Budget App transaction. | Agreed (2026-10-14). Become S-07, S-08 and S-09. |
| O-P2-04 | 2 | A second external review of the revised ADRs? | Yes, Codex on the changes only, with the module list filled in. | Yes (2026-10-14). |
| O-P2-05 | 2 | Where does the release signing key live (A-25)? | A keystore outside the repository, its path in `build-config/local/signing.properties`, backed up by the Operator. | Agreed (2026-10-14). |
| O-P2-06 | gate | S-03: what happens when a Build Configuration entry is removed, or a Bank Account gets a new Configuration Key? | Keep the stored Bank Account with its history and lock, out of use; a new key is a new Bank Account, and its Account Mapping is refused if it points at a locked Budget Account. | Agreed (2026-10-14). |
| O-P2-07 | gate | S-08: are Offered Bank Accounts included in device backups? | No: they are replaced at the next delivery anyway, and UC-009 A10 covers none being stored. | Agreed (2026-10-14). |
| O-P2-08 | gate | UC-009 shows the 'last four digits' of a Bank Account: a fixed number. Setting or outside convention? | An outside convention (banks and card issuers show the last four digits); name that source where it is used, as the project rule requires. | Agreed (2026-10-14). |
| O-P2-09 | gate | ADR revision O-1: a new installation opens a Recovery Check, whose confirmation needs a Comparison (UC-003), which the first slice does not build. | The first slice shows UC-008 A8 with the A9 confirmation unavailable; harmless, since it cannot upload. | Agreed (2026-10-14). |
| O-P2-10 | gate | ADR revision O-2: FR-093 says 'anywhere'; a plain value pasted without its key cannot be recognised. | Accept ADR-003 §5's reading and say so in FR-093. | Agreed (2026-10-14). S-11. |
| O-P2-11 | gate | ADR revision O-3: a digest makes Import Identifier collisions unlikely, not impossible. | Say so in UC-003 BR-002, with detection and routing. | Agreed (2026-10-14), plus: every identifier Slussen generates must make a collision extraordinarily unlikely, like a UUID. S-11. |
| O-P2-12 | gate | ADR revision O-4: Phase 9 variant; the gate of the first slice that uploads. | Phase 9 installs `release`; the first Upload slice's plan lists evidence E-01..E-12 (ADR-005 §7) as its gate. | Agreed (2026-10-14). Phase 9 row changed; carried to §7 'Owed to the next plan'. |
| O-P2-13 | gate | ADR revision Assumed 1–10 (coordinator: one operation at a time, decisions wait during a Fetch, open plan expires at restart, Not Yet Known becomes Unknown at restart, any Budget change re-asks confirmation; consent refused during Back-off or at a Request Limit; renamed Bank needs a new Bank Consent; debug installs beside release; and the rest as reported). | Accept. | Agreed (2026-10-14). |
| O-P2-14 | gate | ADR fix assumptions for B-01..B-14: a crash between opening a Recovery Check and clearing the start intent can open a second one (one Comparison and confirmation closes all); a plan failing the final date check becomes Changed Before Writing; counterpart read `readTransaction` joins the Budget App floor; `markCleared` may return Unknown (counterpart stays uncleared until a Comparison); the fingerprint keeps letters and strips only spaces, hyphens, dots and slashes; the ownership marker is exactly `SLS` (another importer's `SLS…` identifiers go to review); P-5 refuses an empty value. New evidence item E-12 (startup interruption). | Accept. |Agreed (2026-10-15), with two clarifications: the final date check is a specification rule, not only an ADR one (S-17 item 1); Slussen guarantees no duplicate only of what it sent itself, and a resemblance to another path's Import or a Typed Entry is a reason for review, not a guarantee (S-17 item 2). E-12 is an instrumented test written and run in the first slice that uploads, not a check during normal use. |
| O-P2-15 | gate | Found while fixing C-01: a Transfer Leg that is Needs Review because it has no Stable Reference becomes New after Import Anyway (UC-004 BR-006) and is uploaded as an ordinary transaction, not as part of its Transfer. Should every Needs Review Transfer Leg follow its Transfer after Import Anyway? | Yes: Import Anyway on any Needs Review Transfer Leg lets it follow its Transfer, so a Transfer is never split by a review decision; Split stays the explicit way to separate it (UC-005). |Agreed (2026-10-15), widened: every doubtful Transfer Leg (Needs Review for any reason, or Possible Import By Other Path) makes its whole Transfer not eligible until a Review Decision; Import Anyway rejoins the Transfer, Defer holds it back, Already Present counts the Transfer as already in the Budget App. Also: 'upload' only for the write of a confirmed Upload Plan, the central pre-upload concept. S-17. |
| O-P2-16 | gate | S-17 item 1 rechecked date-dependent eligibility at confirmation, so a plan reviewed at 23:58 and confirmed at 00:01 could be refused. The operator: the plan must stay exactly as reviewed; a hidden recheck is invisible behaviour. | Fix eligibility when the plan is prepared; keep only the visible expiry and the unchanged-Budget-App/Bank-Transactions/configuration checks. | Agreed (2026-10-15). S-18, reverses S-17 item 1 and the date part of B-07. |
| O-P2-17 | gate | The Operator asked for a module diagram in ADR-001; AIUP does not require one. | Add a Mermaid module map of the production dependencies of §2; the text stays authoritative. | Agreed (2026-10-15). ADR-001 "Module map". |
| O-P2-18 | gate | How does the Operator, or an Adopting Developer, version and back up their Build Configuration? A fork of a public repository is always public, and the build refuses tracked configuration. | No specification change. ADR-003 explicitly allows an optional private Git repository nested in the ignored `build-config/local/`, with a build test; a plain copy remains equally valid. | Agreed (2026-10-15). ADR-003 §1 and its test. |
| O-P2-19 | Phase 3 | The first run of `tools/check_project_rules.py` found 14 violations in the specification and the ADRs (bare "account", unsourced "24 hours", lowercase terms, Avoid words in two attribute names). | Fix them as S-19; ADR wording corrected after acceptance as editorial. | Agreed (2026-10-15), except: "health savings account" (HSA) is a financial and legal term and stays as written in the vision; the check lists it as an exception. |

## 7. Plan closed

- **Date closed:**
- **What shipped:**
- **Specification changes:**
- **Owed to the next plan:** the first slice that uploads is gated on evidence E-01..E-12 (ADR-005 §7, O-P2-12).
- **Verdict on AIUP construction for an Android stack (operator):**
