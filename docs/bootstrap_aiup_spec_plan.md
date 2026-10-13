# Plan: Bootstrap AIUP Living Specification Core for Slussen

**Initiative:** Slussen AIUP Specification Bootstrapping  
**Source Baseline:** `C:\work\ynab-import-android` (code, tests, `docs/archive/`, PR history)  
**Target Repository:** `C:\work\slussen`  
**Branch:** `initial-aiup-scaffolding`  
**Authoritative Methodology:** Simon Martinelli's *Spec-Driven Development: From Specs to Code with AI Agents* (Apress 2026) & AIUP Marketplace  
**Tooling Runner:** `python tools/lint_specs.py`  
**Orchestration Stance:** Top tier model across all phases; one commit per phase; mandatory green `spec_lint` gate before proceeding to the next phase.

---

## 0. Executive Summary & The Religious Rule

The purpose of this plan is to establish the permanent, living **Specification Core** for Slussen before any application code or scaffolding is written.

Martinelli's AIUP process is treated as **strict doctrine**:
1. **The specification in `docs/` is the single source of truth.**
2. Code and tests are downstream derived artifacts.
3. Every behavior must be covered by a use case; every rule must have a stable ID.
4. **Change the specification first, then synchronize the code.**
5. Specifications outlive plans: this plan will close and archive to `docs/archive/`, leaving `docs/` as the permanent living baseline.

---

## 1. Authoritative Reference Materials on Disk

Every executing agent MUST read the designated reference files before writing artifacts for a phase:

| Phase | Core Book Chapter (`C:\work\book-context-martinelli-spec-driven-dev\corpus\chapters\`) | AIUP Skill Contract (`C:\work\marketplace\aiup-core\skills\`) |
|---|---|---|
| **Phase 0** | `08-case-study-building-a-simple-task-manager.md` | `reverse-engineer/SKILL.md` |
| **Phase 1** | `03-from-requirements-to-specs.md` | `requirements/SKILL.md` (and glossary section) |
| **Phase 2** | `03-from-requirements-to-specs.md` § Entity Model | `entity-model/SKILL.md` |
| **Phase 3** | `04-writing-executable-specifications.md` § Use Case Diagrams | `use-case-diagram/SKILL.md` |
| **Phase 4** | `04-writing-executable-specifications.md` § Specifications | `use-case-spec/SKILL.md` + `use-case-spec/references/format-spec.md` |
| **Phase 5** | `10-best-practices-pitfalls-and-the-road-ahead.md` | `spec-review/SKILL.md` + `spec-review/references/review-checklist.md` |

---

## 2. Target Deliverables in `slussen/docs/`

| Artifact | File Path | AIUP Standard / Description |
|---|---|---|
| **Vision** | `docs/vision.md` | Core problem, primary user, key constraints, on-device SCA requirements, and invariants. |
| **Glossary** | `docs/glossary.md` | Domain taxonomy + mandatory **Synonyms to Avoid** table. |
| **Requirements** | `docs/requirements.md` | Catalog of `FR-xxx`, `NFR-xxx`, and `C-xxx` IDs. |
| **Entity Model** | `docs/entity_model.md` | Mermaid ER diagram (relationships only) + strict 5-column attribute tables using the 8 closed AIUP types. |
| **Use Case Diagram** | `docs/use_cases.puml` | PlantUML diagram showing actors and 4–8 aggregated use cases. |
| **Use Case Specs** | `docs/use_cases/UC-XXX-*.md` | 4–8 normative use case specifications with scoped `BR-xxx` rules and drift notes. |
| **Drift Report** | `scratch/drift_report.md` | Audit log tracking divergences between archived predecessor specs, PRs, and code. |

---

## 3. Phase Execution Table

| Phase | Description | Deliverables | Model | Blocked By | Gate / Acceptance Criteria |
|:---|:---|:---|:---:|:---:|:---|
| **Phase 0** | Archaeological Extraction & Tooling Setup | `scratch/pr_history.md`, `scratch/domain_inventory.md` | Top Tier | — | Predecessor PRs dumped; domain models inventoried; `python tools/lint_specs.py` verified. |
| **Phase 1** | Vision, Glossary & Requirements Catalog | `docs/vision.md`, `docs/glossary.md`, `docs/requirements.md` | Top Tier | Phase 0 | `requirements.md` has `FR-`, `NFR-`, `C-` IDs; glossary has synonyms table; `lint_specs` passes. |
| **Phase 2** | Entity Model & Data Dictionary | `docs/entity_model.md` | Top Tier | Phase 1 | `### UPPER_SNAKE_CASE` headings; 1-sentence descriptions; 5-column tables; closed types only; `lint_specs` passes. |
| **Phase 3** | Use Case Diagram & Actor Synthesis | `docs/use_cases.puml` | Top Tier | Phase 2 | 4–8 aggregated use cases; PlantUML valid; all use cases mapped to `FR-` IDs; `lint_specs` passes. |
| **Phase 4** | Detailed Use Case Specifications | `docs/use_cases/UC-XXX-*.md` | Top Tier | Phase 3 | All 4–8 use cases written to normative format; scoped `BR-xxx` rules; failure postconditions = minimal guarantees; `lint_specs` passes. |
| **Phase 5** | Spec Lint Verification & Operator Approval | `docs/use_cases/*.md` (`**Status:** Approved`) | Top Tier | Phase 4 | `python tools/lint_specs.py --strict` exits 0 with zero errors/warnings; operator sets status to `Approved`. |
| **Phase 6** | Plan Close & Archival | `docs/archive/bootstrap_aiup_spec_plan.md` | Top Tier | Phase 5 | "Plan Closed" section completed; plan moved to archive; `docs/` clean for Construction Phase 1. |

---

## 4. Phase Dispatch Instructions (Paste-Ready Prompts)

### Phase 0: Archaeological Extraction & Tooling Setup
* **Read First:** `C:\work\marketplace\aiup-core\skills\reverse-engineer\SKILL.md` (Workflow §1–§3).
* **Actions:**
  1. Extract merge commits and PR descriptions from `C:\work\ynab-import-android` into `scratch/pr_history.md`.
  2. Inventory domain entities, enums, and data classes from `ynab-import-android/domain/` and `app/` into `scratch/domain_inventory.md`.
  3. Verify `python tools/lint_specs.py` runs cleanly.
* **Gate:** `scratch/pr_history.md` contains the 80 PRs; `python tools/lint_specs.py` exits 0.
* **Commit:** `docs: phase 0 - extract predecessor git history and inventory domain models`

---

### Phase 1: Vision, Glossary & Requirements Catalog
* **Read First:**
  - `C:\work\book-context-martinelli-spec-driven-dev\corpus\chapters\03-from-requirements-to-specs.md`
  - `C:\work\marketplace\aiup-core\skills\requirements\SKILL.md`
* **Actions:**
  1. Author `docs/vision.md`: Define the core problem (secure European bank import onto phone with SCA), single-operator workflow (fetch $\to$ reconcile $\to$ upload), and non-negotiable invariants (YNAB is system of record; no auto-posting without operator review).
  2. Author `docs/glossary.md`: Define domain terms (Connection, Consent, Leg, Transfer Match, Delta Sync, One-sided Transfer, Decision Journal) and provide a strict **Synonyms to Avoid** table.
  3. Author `docs/requirements.md`: Synthesize `FR-001`..`FR-040` (functional capabilities), `NFR-001`..`NFR-010` (security, performance, on-device execution), and `C-001`..`C-010` (banking rate limits, Android KeyStore, YNAB delta sync API).
* **Gate:** Run `python tools/lint_specs.py`. Zero errors.
* **Commit:** `docs: phase 1 - establish vision, glossary, and requirements catalog`

---

### Phase 2: Entity Model & Data Dictionary
* **Read First:** `C:\work\marketplace\aiup-core\skills\entity-model\SKILL.md`.
* **Actions:**
  1. Mine domain models from `scratch/domain_inventory.md` and `ynab-import-android/domain/`.
  2. Author `docs/entity_model.md`:
     - Mermaid ER diagram with relationship lines only (NO attributes in diagram boxes).
     - Entities named with `### UPPER_SNAKE_CASE` headings (e.g. `### BANK_CONNECTION`, `### TRANSACTION_LEG`).
     - Exactly one sentence description under each entity heading.
     - Exactly 5 columns: `Attribute | Description | Data Type | Length/Precision | Validation Rules`.
     - Data types restricted strictly to the 8 closed AIUP types: `Long`, `String`, `Integer`, `Decimal`, `Boolean`, `Date`, `DateTime`, `BLOB`.
* **Gate:** Run `python tools/lint_specs.py`. Zero errors.
* **Commit:** `docs: phase 2 - generate entity model and data dictionary`

---

### Phase 3: Use Case Diagram & Actor Synthesis
* **Read First:** `C:\work\marketplace\aiup-core\skills\use-case-diagram\SKILL.md`.
* **Actions:**
  1. Model actors: `Operator` (primary), `Banking Provider` (secondary system), `YNAB API` (secondary system).
  2. Aggregate behaviors into 4–8 use cases:
     - `UC-001`: Manage Bank Connections & Consents
     - `UC-002`: Fetch & Ingest Bank Transactions
     - `UC-003`: Reconcile Transactions & Match Transfers
     - `UC-004`: Review & Upload to YNAB
     - `UC-005`: Manage Budgets & Account Mappings
     - `UC-006`: Audit Decision Journal
  3. Generate `docs/use_cases.puml`.
* **Gate:** Aggregated count between 4 and 8; PlantUML syntax valid; `python tools/lint_specs.py` passes.
* **Commit:** `docs: phase 3 - generate use case diagram`

---

### Phase 4: Detailed Use Case Specifications
* **Read First:**
  - `C:\work\book-context-martinelli-spec-driven-dev\corpus\chapters\04-writing-executable-specifications.md`
  - `C:\work\marketplace\aiup-core\skills\use-case-spec\SKILL.md`
  - `C:\work\marketplace\aiup-core\skills\use-case-spec\references\format-spec.md`
* **Actions:**
  1. Write `docs/use_cases/UC-001-manage-bank-connections.md` through `UC-006-audit-decision-journal.md`.
  2. Strictly adhere to the normative format grammar (Overview, Preconditions, Main Success Scenario, Alternative Flows, Postconditions, Business Rules).
  3. Every step in business-level prose (no SQL, HTTP methods, or library names).
  4. Failure postconditions state minimal guarantees, not error messages.
  5. Scoped business rules `### BR-001: <Name>` numbered from 1 per file.
  6. Document any drift from predecessor specs in the rule description, citing the PR number.
* **Gate:** All 6 use cases present; `python tools/lint_specs.py` passes.
* **Commit:** `docs: phase 4 - author normative use case specifications UC-001 through UC-006`

---

### Phase 5: Spec Lint Verification & Operator Review
* **Read First:** `C:\work\marketplace\aiup-core\skills\spec-review\SKILL.md`.
* **Actions:**
  1. Run `python tools/lint_specs.py --strict`.
  2. Fix any warnings or errors.
  3. Present the complete living specification corpus to the operator.
  4. Upon operator review and confirmation, update `**Status:** Approved` in each use case file.
* **Gate:** `python tools/lint_specs.py --strict` exits 0 with zero findings; operator approval confirmed.
* **Commit:** `docs: phase 5 - pass strict spec_lint and mark use cases approved`

---

### Phase 6: Plan Close & Archival
* **Actions:**
  1. Complete Section 5 ("Plan Closed") below: record what shipped, drift findings, and owed items.
  2. Move `docs/bootstrap_aiup_spec_plan.md` to `docs/archive/bootstrap_aiup_spec_plan.md`.
  3. Verify `docs/` is clean and contains only the living Specification Core.
* **Gate:** Plan moved to `docs/archive/`; working tree clean; repo ready for Construction Phase 1.
* **Commit:** `docs: phase 6 - close and archive bootstrap AIUP specification plan`

---

## 5. Plan Closed (To be completed upon Phase 6)

*This section will be filled out when the initiative concludes.*

- **Date Closed:** 
- **What Shipped:** 
- **Drift Uncovered Between Predecessor Specs and Code:** 
- **Owed Items / Open Questions for Construction:** 
