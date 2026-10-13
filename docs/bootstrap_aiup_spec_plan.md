# Plan: Bootstrap AIUP Living Specification Core for Slussen

**Initiative:** Slussen AIUP Specification Bootstrapping  
**Source Baseline:** `C:\work\ynab-import-android` (code, tests, `docs/archive/`, PR history)  
**Target Repository:** `C:\work\slussen`  
**Branch:** `initial-aiup-scaffolding`  
**Orchestration Stance:** Strong reasoning tier across all phases; one commit per phase; gate check before proceeding to next phase.

---

## 0. Executive Summary & Purpose

The purpose of this plan is to establish the permanent, living **Specification Core** for Slussen before any new application code or scaffolding is written. 

Following the **Three-Source Baseline Pass** defined in Centaur and AIUP:
1. **The Code & Tests** in `ynab-import-android` provide the ground truth for current runtime behavior.
2. **The Archived Spec/Plan Pairs** (`docs/archive/android_app_spec-v1.4.md`, `transfer_matching_spec-v0.11.md`, etc.) provide design rationale, intentional constraints, and the basis for the Requirements Catalog (`FR-`, `NFR-`, `C-`).
3. **The Git PR & Merge History** (80 merge PRs) captures field fixes, maintenance decisions, and behavior introduced without formal spec updates.

When complete, `slussen/docs/` will hold a complete, lintable, and operator-approved specification that is self-sufficient for greenfield code generation.

---

## 1. Target Deliverables in `slussen/docs/`

| Artifact | File Path | AIUP Standard / Description |
|---|---|---|
| **Vision** | `docs/vision.md` | Core problem, primary user, key constraints, and architectural invariants. |
| **Glossary** | `docs/glossary.md` | Domain taxonomy (Slussen, Leg, Delta Sync, Consent, Split, Pairing, etc.) + forbidden synonyms. |
| **Requirements** | `docs/requirements.md` | Functional (`FR-xxx`), Non-Functional (`NFR-xxx`), and Constraint (`C-xxx`) catalog. |
| **Entity Model** | `docs/entity_model.md` | Mermaid ER diagram (relationships only) + strict 5-column attribute tables using closed AIUP types. |
| **Use Case Diagram** | `docs/use_cases.puml` | PlantUML diagram showing actors and 4–8 aggregated use cases. |
| **Use Case Specs** | `docs/use_cases/UC-XXX-*.md` | 4–8 normative use case specifications with scoped business rules (`BR-xxx`). |
| **Spec Linter** | `tools/spec_lint.py` | Local deterministic linter copied from `marketplace/aiup-core`. |
| **Drift Report** | `scratch/drift_report.md` | Non-committed audit log tracking divergences between archived specs, PRs, and code. |

---

## 2. Phase Execution Table

| Phase | Description | Deliverable Artifacts | Model | Blocked By | Gate / Acceptance Criteria |
|:---|:---|:---|:---:|:---:|:---|
| **Phase 0** | Archaeological Extraction & Tooling Setup | `scratch/pr_history.md`, `tools/spec_lint.py` | Top Tier | — | Git PR history extracted; `tools/spec_lint.py` operational. |
| **Phase 1** | Vision, Glossary & Requirements Catalog | `docs/vision.md`, `docs/glossary.md`, `docs/requirements.md` | Top Tier | Phase 0 | Requirements catalog covers all core capabilities (`FR-`, `NFR-`, `C-`); glossary lists forbidden synonyms. |
| **Phase 2** | Entity Model & Data Dictionary | `docs/entity_model.md` | Top Tier | Phase 1 | Mermaid diagram valid; all attribute tables use exactly 5 columns and closed AIUP data types. |
| **Phase 3** | Use Case Diagram & Actor Synthesis | `docs/use_cases.puml` | Top Tier | Phase 2 | Aggregated into 4–8 use cases; primary actor ("Operator") and external APIs modeled; PlantUML syntax valid. |
| **Phase 4** | Detailed Use Case Specifications | `docs/use_cases/UC-XXX-*.md` | Top Tier | Phase 3 | All 4–8 specs written with preconditions, flows, postconditions, and scoped `BR-xxx` rules; drift cited. |
| **Phase 5** | Spec Lint Verification & Operator Review | Updated specs (`**Status:** Approved`) | Top Tier | Phase 4 | `python tools/spec_lint.py docs/` passes with 0 errors; operator approves use cases. |
| **Phase 6** | Plan Close & Archival | `docs/archive/bootstrap_aiup_spec_plan.md` | Top Tier | Phase 5 | Plan closed section completed; plan moved to archive; `docs/` clean and ready for Construction. |

---

## 3. Phase Details & Prompts

### Phase 0: Archaeological Extraction & Tooling Setup
- **Objective:** Extract the raw historical evidence from `C:\work\ynab-import-android` into `scratch/` and copy `spec_lint.py` into `tools/`.
- **Actions:**
  1. Copy `C:\work\marketplace\aiup-core\skills\spec-review\scripts\spec_lint.py` to `tools/spec_lint.py`.
  2. Extract git merge commit log and PR summaries from `ynab-import-android` (80 merge PRs) into `scratch/pr_history.md`.
  3. Inventory domain classes in `ynab-import-android/domain/src/main/` and archived specs in `ynab-import-android/docs/archive/`.
- **Gate:** `python tools/spec_lint.py --help` succeeds; `scratch/pr_history.md` populated.
- **Commit:** `docs: bootstrap tooling and extract predecessor git history`

---

### Phase 1: Vision, Glossary & Requirements Catalog
- **Objective:** Establish the high-level intent, domain terminology, and requirements catalog.
- **Actions:**
  1. Create `docs/vision.md` detailing the single-operator bank-to-YNAB import workflow, European SCA constraints, on-device execution, and system invariants.
  2. Create `docs/glossary.md` defining terms (Connection, Consent, Account Mapping, Raw Transaction, Normalized Transaction, Leg, Transfer Match, Delta Sync, Guess Flag, Decision Journal) and synonyms to avoid.
  3. Create `docs/requirements.md` synthesizing:
     - `FR-001` onwards: Bank integration, transaction parsing, transfer matching, YNAB upload, account management, decision logging.
     - `NFR-001` onwards: Security (tokens in EncryptedSharedPreferences/KeyStore), on-device processing, performance, offline capability.
     - `C-001` onwards: Android SDK constraints, bank rate limits, YNAB API constraints.
- **Gate:** Requirements format conforms to AIUP standard; every requirement has a unique, stable ID.
- **Commit:** `docs(spec): establish vision, glossary, and requirements catalog`

---

### Phase 2: Entity Model & Data Dictionary
- **Objective:** Model the domain entities and relationships based on `ynab-import-android` domain classes and storage schemas.
- **Actions:**
  1. Mine domain models: `Account`, `BankConnection`, `RawTransaction`, `NormalizedTransaction`, `TransferLeg`, `TransferPairing`, `Budget`, `AccountMapping`, `DecisionJournalEntry`.
  2. Generate `docs/entity_model.md`:
     - Mermaid ER diagram (relationships only, no attributes inside entity blocks).
     - Strict 5-column Markdown tables: `Attribute | Description | Data Type | Length/Precision | Validation Rules`.
     - Data types restricted strictly to closed AIUP list: `Long`, `String`, `Integer`, `Decimal`, `Boolean`, `Date`, `DateTime`, `BLOB`.
- **Gate:** Verified 5-column structure and closed data types. No SQL types or custom business types.
- **Commit:** `docs(spec): generate entity model and data dictionary`

---

### Phase 3: Use Case Diagram & Actor Partitioning
- **Objective:** Partition system behavior into a high-level PlantUML diagram with 4–8 aggregated use cases.
- **Actions:**
  1. Identify Actors:
     - Primary: `Operator` (the human user running the app).
     - Secondary/System: `Banking Provider` (Enable Banking, SimpleFIN, Fidelity), `YNAB API`.
  2. Aggregate behaviors into cohesive goal-oriented use cases:
     - `UC-001`: Manage Bank Connections & Consents
     - `UC-002`: Fetch & Ingest Bank Transactions
     - `UC-003`: Reconcile Transactions & Match Transfers
     - `UC-004`: Review & Upload to YNAB
     - `UC-005`: Manage Budgets & Account Mappings
     - `UC-006`: Audit Decision Journal
  3. Generate `docs/use_cases.puml`.
- **Gate:** Use case count is between 4 and 8; PlantUML syntax is valid; all use cases map to defined requirements.
- **Commit:** `docs(spec): generate use case diagram`

---

### Phase 4: Detailed Use Case Specifications
- **Objective:** Author comprehensive, normative use-case specifications for each identified use case.
- **Actions:**
  1. Write `docs/use_cases/UC-001-manage-bank-connections.md` through `UC-006-audit-decision-journal.md`.
  2. Each file must adhere to normative AIUP structure:
     - Title & Header (`**ID:** UC-XXX`, `**Primary Actor:**`, `**Status:** Draft`, `**Requirements:** FR-...`)
     - Description & Preconditions
     - Main Success Scenario (numbered steps, actor & system actions, business-level language)
     - Alternative Flows (`3a.`, `4a.`, etc.)
     - Postconditions (Success and Minimal Guarantee)
     - Business Rules (`BR-001`, `BR-002`, ... scoped per use case)
  3. Include drift notes where code in `ynab-import-android` diverged from archived specs, citing the relevant PR.
- **Gate:** All use cases present; each step is business-level; all business rules have scoped `BR-xxx` IDs.
- **Commit:** `docs(spec): author normative use case specifications UC-001 through UC-006`

---

### Phase 5: Spec Lint Verification & Operator Review
- **Objective:** Deterministically verify specification integrity using `spec_lint.py` and obtain operator approval.
- **Actions:**
  1. Run `python tools/spec_lint.py docs/`.
  2. Fix any lint errors: dangling requirement IDs, invalid data types, non-standard headings, or weak words.
  3. Present the specification corpus to the operator for review.
  4. Upon operator review, advance status in all use cases from `Draft` to `Approved`.
- **Gate:** `tools/spec_lint.py` exits with code 0 (zero errors); use case statuses are `Approved`.
- **Commit:** `docs(spec): pass spec_lint and approve specification core`

---

### Phase 6: Plan Close & Archival
- **Objective:** Formalize initiative completion and retire the plan to `docs/archive/`.
- **Actions:**
  1. Complete Section 4 ("Plan Closed") below: record what was generated, what drift was uncovered, and open items.
  2. Move `docs/bootstrap_aiup_spec_plan.md` to `docs/archive/bootstrap_aiup_spec_plan.md`.
  3. Verify `docs/` is clean and contains only the living Specification Core.
- **Gate:** Plan archived; working tree clean; repo ready for Construction Phase 1.
- **Commit:** `docs: close and archive bootstrap AIUP specification plan`

---

## 4. Plan Closed (To be completed upon Phase 6)

*This section will be filled out when the initiative concludes.*

- **Date Closed:** 
- **What Shipped:** 
- **Drift Uncovered Between Predecessor Specs and Code:** 
- **Owed Items / Open Questions for Construction:** 
