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

Reviewer: OpenAI Codex CLI, GPT-6 (the reviewer could not confirm the Astra Max variant or a snapshot identifier), commit `1646ecd`, reviewing `d592aa2`. Verdict: not ready to be Accepted. Blocking High: A-01..A-09, A-11, A-12, A-16, A-17, A-19, A-20; also needing disposition: A-18, A-21..A-23, A-26, A-28; uncertain: A-13, A-24. Full text: `docs/reviews/phase2_architecture_review.md`.

| ID | Location | Finding | Evidence | Disposition |
|---|---|---|---|---|
| A-01 | ADR-002 Decision §3 | High: the unique (`budget_account_id`, `import_identifier`) insert rejects the retry UC-006 A8/BR-007 requires; a plan holding a retryable Sent Record sends nothing | ADR-002 §3; UC-006 A8, A9, BR-007; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-02 | ADR-002 §3, Consequences; ADR-001 §4.2 | High: exactly once cannot be destination-independent: a lost request and a lost response leave the same local state; `BudgetAppWriter.create` has no idempotency guarantee | UC-006 BR-006, BR-007; ADR-002 Consequences; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-05 (Budget App minimum: refuses a reused Import Identifier) and ADR revision. |
| A-03 | ADR-002 Decision §6, Consequences | High: restoring the last good copy can lose Sent Records, Account Mapping locks and decisions and continue; informing the Operator does not meet the guarantee | vision Exactly once; UC-006 BR-006; FR-042; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-06 (Uploads blocked after a restore until a Comparison and the Operator's confirmation) and ADR revision. |
| A-04 | ADR-002 Decision §4, Consequences; O-P1-05 | High: backup inclusion is not a zero-loss restore; stale, absent or oversized (25 MB) backups restore older or no history | NFR-010; FR-042; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-04 and S-06, and ADR revision. |
| A-05 | ADR-002 §2, ADR-003 §6; O-P1-04 | High: no immutable identity matches a stored Bank Account to its Build Configuration entry; rename, reorder or removal could bypass a locked Account Mapping | UC-009 A8, BR-003, BR-006; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-03 (O-P1-04) and ADR revision. |
| A-06 | ADR-001 §4, §4.2, §7 UC-003; ADR-002 §3 | High: the Import Identifier protocol (marker, canonical encoding, algorithm version, collisions, Re-upload occurrence allocation) is not frozen | UC-003 BR-002; FR-074; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-07 | ADR-001 §3, §7 UC-002/UC-003; ADR-002 §2, §3 | High: no atomic operation for a whole file import or for a Budget App change set together with its read cursor | UC-002 BR-001; UC-003 BR-016; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-08 | ADR-001 §3, §5, §7 UC-006; ADR-002 §2, §3 | High: the Upload pause and Confirmed state have no concurrency, admission or process-death protocol | UC-006 BR-004, BR-005; FR-035, FR-036; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-09 | ADR-001 §4.2; ADR-002 §2, §3 | High: after a lost Upload response the read model cannot recover the Transfer Counterpart unambiguously | UC-005 BR-009, BR-010; UC-006 BR-010, BR-011; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-10 | ADR-001 §4.2, §5, Consequences | Medium: Budget App capability flags (approval marker, linked Transfers) leave behaviour the core must guess; no capability floor; fallbacks can change a confirmed Upload Plan | NFR-015; UC-012 BR-006; UC-006 BR-008, A10; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-11 | ADR-001 §4.1 `FetchResponse` | High: an uninterpretable item with no content cannot become a Bank Transaction kept as Needs Review | FR-009; NFR-002; UC-003 BR-012; `BANK_TRANSACTION`; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-07 and ADR revision. |
| A-12 | ADR-001 §4.1, §4.2, §5 | High: consent operations and all Budget App calls carry no request gate or timeout | UC-001 BR-007; NFR-016; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-13 | ADR-001 §4.1 (uncertain) | Medium: one Data Provider-wide outcome and stop rule may stop unaffected Banks and Bank Accounts | UC-001 A5, A8, A9, BR-010; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-14 | ADR-001 §4.1; ADR-002 §1, §2 | Medium: Consent Request callback state has no storage or atomic consume/replace operation | NFR-013; UC-007 BR-002; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-15 | ADR-001 §4, §4.1, §7 UC-009; ADR-002 §1, §2 | Medium: no retained proposal data (offered Bank Accounts, last four digits) for later identification from stored data | UC-009 step 3, A6; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-08 and ADR revision. |
| A-16 | ADR-003 Decision §5, §6, Consequences | High: placement rules P-1..P-4 miss recognizable configuration under a renamed file (keys not prefixed `slussen.`); generator not ordered after validation | FR-093; UC-012 A6, BR-009; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-17 | ADR-006 Decision §1, §2, §5, §6 | High: the free-text `name` of `SlussenLog.event`/`detail` bypasses the typed logging boundary | NFR-007; NFR-008; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-18 | ADR-006 §1, §4; ADR-001 §2, §3 | Medium: plug-ins cannot reach `SlussenLog` (in `:core`) yet ADR-006 tells them to use it | ADR-001 dependency rules; ADR-006 §4; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-19 | ADR-001 §6.1, §6.2, Consequences; ADR-006 §5 | High: import and package checks miss fully qualified calls, JDK I/O, indirection and result filtering; claimed guarantees are too strong | ADR-001 K-05, K-06, K-11, K-12; L-01; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-20 | ADR-001 §6; ADR-003 §5; ADR-005 §3 | High: checks wired into `check` do not run on `:app:assembleDebug` or targeted module builds | ADR-001 §6 'cannot be skipped by building a subset'; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-21 | ADR-001 §1, §2, §6.2 K-07 | Medium: K-07 and the library allowlist reject the prescribed `:testing` fakes and the Kotlin standard library | ADR-001 §6.2; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-22 | ADR-003 Decision §2, §3, §6 | Medium: validation fails a File Pre-processor without a template file, and accepts a Build Configuration with no Budget or Bank Account | UC-008 precondition; UC-012; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision. |
| A-23 | ADR-001 §4.2, §7 UC-011; ADR-002 §1, §2 | Medium: Journal Export cannot get the Budget App memo; `BUDGET_APP_TRANSACTION` has no memo (inherited specification mismatch) | UC-011 steps 3-4, A2; FR-068; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-09 and ADR revision. |
| A-24 | ADR-002 §6; ADR-005 §1 (uncertain) | Medium: `VACUUM INTO` on `minSdk` 26, snapshot replacement and non-native corruption paths are unproven | ADR-002 §6; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision; the proof is a prerequisite for the first slice that uploads, not for UC-012 + UC-008. |
| A-25 | ADR-005 Decision §6, Consequences; O-P1-08 | Medium: debug-key default leaves the upgrade guarantee dependent on one machine | FR-042; NFR-017; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): O-P1-08 and O-P2-05; ADR revision. |
| A-26 | ADR-003 §4; ADR-001 §4.3; O-P1-02 | Medium: File Format Upload Window precedence is an unapproved specification reading | FR-084, FR-086; UC-002 BR-004; UC-010 BR-001; `AGENTS.md` §1; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): S-01 (O-P1-02) and ADR revision. |
| A-27 | ADR-002 §3, §4, §5, §9; ADR-004 §2; ADR-005 §3; plan Phases 6, 7, 9 | Medium: gates exclude instrumented and live tests, so the Sent Record, migration and Budget App guarantees are never proven before Uploads are enabled | C-011; ADR-005 §3; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): ADR revision; device, restore, migration and live Budget App evidence gate the first slice that uploads. |
| A-28 | ADR-003 §6, §8, Consequences; O-P1-06; plan Phases 6, 7 | Medium: the plan's `gradlew build` gate cannot pass without private Build Configuration | `AGENTS.md` §3; ADR-003; `docs/reviews/phase2_architecture_review.md` | Accepted (2026-10-14): O-P1-06 and ADR revision; plan gates for Phases 6 and 7 changed to `gradlew check` plus the packaging-refusal test. |

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
| O-P2-12 | gate | ADR revision O-4: Phase 9 variant; the gate of the first slice that uploads. | Phase 9 installs `release`; the first Upload slice's plan lists evidence E-01..E-11 (ADR-005 §7) as its gate. | Agreed (2026-10-14). Phase 9 row changed; carried to §7 'Owed to the next plan'. |
| O-P2-13 | gate | ADR revision Assumed 1–10 (coordinator: one operation at a time, decisions wait during a Fetch, open plan expires at restart, Not Yet Known becomes Unknown at restart, any Budget change re-asks confirmation; consent refused during Back-off or at a Request Limit; renamed Bank needs a new Bank Consent; debug installs beside release; and the rest as reported). | Accept. | Agreed (2026-10-14). |

## 7. Plan closed

- **Date closed:**
- **What shipped:**
- **Specification changes:**
- **Owed to the next plan:** the first slice that uploads is gated on evidence E-01..E-11 (ADR-005 §7, O-P2-12).
- **Verdict on AIUP construction for an Android stack (operator):**
