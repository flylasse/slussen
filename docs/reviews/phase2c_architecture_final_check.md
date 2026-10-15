# Phase 2c — External Architecture Final Check

**Reviewer:** OpenAI Codex, GPT-6. GPT-6 is the model version exposed to this session; a serving variant or snapshot identifier is not exposed.
**Reviewed commit:** `70f2b61ec716cb9afebb1e49ef7e49d5795f625e`
**Diff base:** `8c18a54`
**Branch:** `construction-kickoff`
**Date:** 2026-10-15

I read `AGENTS.md` first and reviewed `git diff 8c18a54..HEAD -- docs/`, using surrounding sections, the Phase 2b report and the dispositions in the construction kickoff plan §6 to assess closure. Evidence was confined to this repository, excluding `scratch/`. **Resolved** means resolved at the architecture-decision level under the recorded disposition; implementation and device evidence remain due in the first slice that uploads. I independently recomputed the two added Stable Reference vectors in memory; both match. This is not an implementation test or an orchestrator gate run. The repository boundary prevents consulting the external Marketplace material or running its linter; this review records that methodology limitation because only this file may be changed.

## 1. Are the previous findings resolved?

| Finding | Verdict | Evidence |
|---|---|---|
| B-01 | Resolved | [ADR-007](../adr/ADR-007-operation-coordinator-and-upload-protocol.md) §4, §6 step 8 and §7 now put `clear_counterpart` intent on the confirmed `LinkedTransfer` item, resolve its counterpart after creation and permit clearing within the same Upload. [ADR-001](../adr/ADR-001-module-boundaries.md) §4.2.1 F-3 and §4.2.3 define `readTransaction`; [ADR-005](../adr/ADR-005-toolchain-and-version.md) §7 E-10 requires evidence of same-Upload clearing. |
| B-02 | Resolved | ADR-007 §4 separates transaction-send and clearing items; §6 steps 6–8 and §7 exclude clearing from Sent Record changes and authorize its own writes. [ADR-002](../adr/ADR-002-persistence-with-room.md) §3 O-7/O-8/O-14 and §4 distinguish send outcomes from counterpart state, including a plan containing only clearing items. |
| B-03 | Resolved | S-12 adds `transfer_link_reference`, `merged_into_reference`, `cleared` and `approved` to [the entity model](../entity_model.md), `BUDGET_APP_TRANSACTION`; [UC-003](../use_cases/UC-003-compare-with-the-budget-app.md) BR-020 requires their retention with the read point. ADR-001 §4.2.3 and ADR-002 §2/§3 O-4/O-5 persist them with the cursor and reconcile from the stored copy. |
| B-04 | Resolved | ADR-002 §3 O-4 sets `comparison_pending` atomically with the copy and cursor; O-5 commits conclusions and clears it. ADR-007 §3/§3a blocks preparation while it is set, §6 step 4 completes Comparison after a changed read, and §8 requires local recomputation after restart. ADR-005 §7 E-05 includes the interrupted-Comparison case. |
| B-05 | Resolved | ADR-002 §6 steps 1–6 persist `slussen.start-intent` before publishing the installation marker or replacement database, resume it after interruption, and delete it only after the Recovery Check and coordinator reset commit. §8 uses that sequence for main-database recovery; ADR-005 §7 E-12 requires interruption evidence at every boundary. |
| B-06 | Resolved | S-13 aligns [requirements](../requirements.md) FR-095/NFR-005/NFR-006, [UC-008](../use_cases/UC-008-check-import-status.md) A1/BR-009 and [UC-010](../use_cases/UC-010-adjust-settings.md) BR-004: damage to data outside Decision-Critical Data opens no Recovery Check. ADR-002 §6/§8 explicitly makes journal and `device.db` damage notice-only, with no effect on Upload Plans or Uploads; ADR-005 §7 E-03 requires that evidence. |
| B-07 | Resolved | ADR-007 §6 step 5 now checks expiry and date-dependent eligibility after the network read, immediately before confirmation, and rejects an expired or newly ineligible plan. ADR-002 §4 checks the supplied `now` against expiry inside O-7. ADR-007 §9 covers expiry during the read, crossing midnight and expiration of the Lonely Transfer grace period. |
| B-08 | Resolved | [ADR-008](../adr/ADR-008-import-identifier-protocol.md) §2 and ADR-001 §4.1 preserve Stable References exactly, without normalization, trimming or case changes; malformed Unicode is rejected as unreadable. ADR-008 §3 adds distinct identifiers for the precomposed and combining-accent references. Both added vectors independently reproduce. |
| B-09 | Resolved | ADR-008 §7/§8 replaces the digits-only fingerprint input with a canonical number retaining letters, scoped by Data Provider and the Bank's reference. ADR-001 §4 preserves the delivered number in `BankAccountNumber`. This addresses the previously uncertain conflation and scope finding under its accepted disposition. |
| B-10 | Partly | ADR-008 §1 now separates exact `SLS` prefix ownership from supported format, and ADR-001 §4.2.3 plus the entity model's `BUDGET_APP_TRANSACTION` constraints prevent classification as Other Import Path. However, S-14's required review is bypassed for automatically recognized Transfers by UC-003 BR-001's ordering; see C-01. |
| B-11 | Resolved | ADR-001 §6.3 excludes `:testing` and `:architecture-tests` from the production bytecode scope and expressly allows `BudgetAppWriter` references in `:plugin-api` and the Budget App implementations. Its bytecode rule B-04 therefore permits both the contract declaration and test fakes identified by this review finding. |
| B-12 | Resolved | [ADR-003](../adr/ADR-003-build-configuration.md) §3 distinguishes Absent, Valid and Failed; Absent permits compilation and `check`. §6 generates the `ABSENT` stub, rejects Absent at packaging, and requires a fixture proving both successful `check` and packaging refusal. This agrees with ADR-005 §3. |
| B-13 | Resolved | ADR-003 §5 P-5 now uses the same properties grammar as the reader, including whitespace separators, continuation lines and decoded escapes; non-placeholder empty values are also refused. §6 adds renamed-fragment fixtures for whitespace and escaped keys. |
| B-14 | Not resolved | At the reviewed HEAD, [vision](../vision.md) Goals, Exactly once still says “the operator for review”, “the operator for a decision” and “asks the operator”. These refer to the defined actor Operator and violate [AGENTS.md](../../AGENTS.md) §2's Title Case rule. The noun “upload” example is gone, but S-15's Operator capitalization correction was not retained in the final rewrite. This remains the existing nonblocking editorial finding. |

The nine A findings previously graded Partly depend on the following closures:

| Finding | Verdict | Evidence |
|---|---|---|
| A-03 | Resolved | B-05 closes the remaining interruption gap: ADR-002 §6 persists the recovery obligation before file changes; ADR-007 §3/§8 prevents preparation before the Recovery Check is completed. |
| A-04 | Resolved | B-05 closes the restore-start gap in ADR-002 §6. The existing backup scope and warning in §5 remain, and [requirements](../requirements.md) NFR-010 still limits restored data to what existed when the backup was made. |
| A-06 | Partly | B-08/B-09 are resolved by ADR-008 §2/§3/§8, and §1 corrects marker ownership. The remaining S-14 review-routing contradiction under B-10/C-01 prevents full closure. |
| A-07 | Resolved | ADR-002 §3 O-4/O-5 and ADR-007 §3a/§8 now make an interrupted Comparison detectable and block preparation until its conclusions are committed (B-04). |
| A-08 | Resolved | ADR-007 §3a/§8 closes interrupted Comparison (B-04); §6 step 5 and ADR-002 §4 supply the missing final time and eligibility checks (B-07). |
| A-09 | Resolved | S-12, the entity model's `BUDGET_APP_TRANSACTION` and ADR-002 §3 O-4/O-5 provide durable counterpart metadata (B-03); ADR-007 §4/§6 step 8 and ADR-001 §4.2.3 provide the confirmed intent and read for same-Upload clearing (B-01). |
| A-16 | Resolved | ADR-003 §5 P-5/§6 now recognize the same properties syntax the reader accepts and require the missing negative fixtures (B-13). The narrowed recognition scope remains explicit in requirements FR-093. |
| A-21 | Resolved | ADR-001 §6.3 corrects the production bytecode scopes and contract-reference allowlist, closing the remaining B-11 incompatibility. |
| A-28 | Resolved | ADR-003 §3/§6 makes the configuration-free `check` path consistent and preserves packaging refusal; ADR-005 §3 retains that agent gate (B-12). |

**Totals:** B findings: 12 Resolved, 1 Partly, 1 Not resolved. Dependent A findings: 8 Resolved, 1 Partly, 0 Not resolved.

## 2. Did the fixes introduce any new High contradiction?

### C-01 — Transfer precedence bypasses S-14's required review

**Severity:** High.

**Location:** [UC-003](../use_cases/UC-003-compare-with-the-budget-app.md) BR-001 versus BR-008, with Main Success Scenario steps 3–5 and BR-014; [entity model](../entity_model.md), `BUDGET_APP_TRANSACTION` constraints; [ADR-001](../adr/ADR-001-module-boundaries.md) §4.2.3; [ADR-008](../adr/ADR-008-import-identifier-protocol.md) §1 and Consequences. Relevant unchanged rule: [UC-005](../use_cases/UC-005-review-transfers.md) BR-010.

**Evidence:** S-14 newly requires a Bank Transaction resembling a Slussen Unsupported Format Import to be Needs Review. The entity constraint and both ADRs repeat that rule. However, the changed UC-003 BR-001 says the first applicable rule wins and puts “one that is a Transfer Leg follows the Transfer” before the new unsupported-format rule. UC-005 BR-010 makes a Transfer that is not guessed eligible without a Transfer Decision.

Consider an unsent, otherwise eligible Transfer Leg with a Stable Reference and no Review Decision, in a recognized Transfer that is not guessed. Its Budget Account already holds a same-amount Import within the Matching Date Window whose identifier begins with `SLS` but has an unsupported format. There is no exact Import Identifier match. BR-001 selects Transfer Leg before reaching the required Needs Review branch; BR-014 then admits the writing leg to an Upload Plan. Ordinary plan confirmation can consequently create a duplicate under the new supported Import Identifier without the specific review S-14 requires. Refusal of a reused Import Identifier cannot protect these different identifiers. This is a contradiction in the changed specification and with the revised ADRs, rather than an additional Upload protocol refinement.

**Fix:** Give the unsupported-format review requirement precedence over automatic Transfer eligibility and preserve it through Transfer detection until an applicable explicit Review Decision resolves it.

No other new High contradiction was found in the reviewed change. Further Upload protocol refinements remain with the first slice that uploads, as directed.

**Verdict: No — C-01 blocks setting the ADRs to Accepted.**
