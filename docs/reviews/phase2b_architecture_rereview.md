# Phase 2b — External Architecture Re-review

**Reviewer:** OpenAI Codex, GPT-6. The model version identified to this session is GPT-6; an independently verifiable serving variant or snapshot identifier is not exposed. The plan requests GPT-6 Astra with Max reasoning; I cannot independently attest to that configuration.
**Reviewed commit:** `e0d35c31ba6c098512add3ffd2ed6f518bb8e2b7`
**Diff base:** `00720b1`
**Branch:** `construction-kickoff`
**Date:** 2026-10-15
**Disposition:** Not ready to be Accepted.

## Scope and method

I read `AGENTS.md` first, reviewed `git diff 00720b1..HEAD -- docs/`, read ADR-007 and ADR-008 in full, and checked the revisions against the earlier review, the dispositions in `docs/construction_kickoff_plan.md` §6, and the relevant Approved requirements, entity constraints and use cases. The actual revision includes **S-11**, as well as S-01 through S-10; this review includes its configuration-recognition and identifier-uniqueness decisions.

The supplied predecessor module list is comparison evidence only. Slussen deliberately separates the contracts from the core, keeps Android modules in the build, and adds enforcement beyond the predecessor's task wired into every subproject's `check`. Those choices are not findings merely because they differ from the predecessor.

I accessed no evidence outside this repository, no `scratch/`, and no private Build Configuration. Consequently I did not consult the external Marketplace skills or book, verify external service/platform claims, or run `tools/lint_specs.py`: that wrapper executes the linter outside the repository. This is the repository-boundary methodology limitation to record in the active plan's §5; the instruction to change only this review prevents me from recording it there myself. This report is not an orchestrator gate result.

I independently recomputed the published ADR-008 §3 digest vectors using an in-memory calculation; their expected identifiers match, including the empty/absent memo and Unicode payee equivalences. I also reproduced the distinct-Stable-Reference normalization counterexample in B-08. No application, device or live-service tests were run. In particular, ADR-005 §7 evidence remains future acceptance work, as the Operator explicitly decided.

**Resolved** means the previous finding is addressed at the architecture-decision level under its recorded disposition, not that the future implementation has passed its tests. **Partly** identifies a remaining gap. High findings contradict or leave a necessary mechanism missing for a safety, durability or essential workflow guarantee; Medium findings require a contract or gate correction; Low findings concern document conformance. An uncertain finding is explicitly marked. B-nn below are review findings, distinct from the identically numbered bytecode rules in ADR-001.

## Disposition of A-01 through A-28

| Finding | Verdict | Evidence and remaining gap |
|---|---|---|
| A-01 | Resolved | `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §4 retry table and §6 step 5; `docs/adr/ADR-002-persistence-with-room.md` §4. Retries update the existing Sent Record under an outcome comparison, retain its Import Identifier and Budget Account, and associate it with the new plan; only new sends insert. B-02 concerns the newly introduced clearing items, separately. |
| A-02 | Resolved | S-05; `docs/requirements.md` FR-038, FR-041, NFR-001, NFR-015; UC-006 BR-006/BR-007 and UC-004 BR-008. ADR-001 §4.2.2 and ADR-007 §4 implement the approved conditional retry guarantee. The former destination-independent promise has been explicitly changed. |
| A-03 | Partly | S-06; FR-095 and UC-006 BR-017; ADR-002 §6 and ADR-007 §3 now block preparation until Comparison and confirmation. However, the start sequence can lose the obligation to open the check across a crash (B-05). |
| A-04 | Partly | S-04/S-06; FR-042 and NFR-010 now promise the data as of the backup, while NFR-019/C-015 and ADR-002 §5 define the size warning and acknowledge absent/stale backups. Restore/new-installation gating is specified in §6 but has the B-05 interruption gap. |
| A-05 | Resolved | S-03/S-10; `docs/entity_model.md` `BANK_ACCOUNT` constraints; UC-009 A8/A11/A12 and BR-010/BR-011; ADR-002 §2/O-11; ADR-003 §2/§3/§6. Immutable Configuration Keys, duplicate-key rejection, removal/return, new keys and preservation of locked Account Mappings are decided. |
| A-06 | Partly | ADR-008 §1–§7 now decides the format, digest, encoding, vectors, occurrence allocation, collision handling and length/version policy; S-11 resolves the impossible absolute uniqueness claim. The published vectors pass. Stable Reference normalization and marker recognition still conflict with the contracts (B-08, B-10); the additional fingerprint protocol needs clarification (B-09, uncertain). |
| A-07 | Partly | ADR-002 §3 O-3 makes file ingestion atomic, and O-4 commits the Budget App changes with their cursor. However, O-5 is separate and there is no durable indication that Comparison must finish before preparation can use its statuses (B-04). |
| A-08 | Partly | ADR-007 §1–§8 supplies the exclusive lane, admission modes, immutable items, captured inputs, replacement, duplicate-confirmation refusal and restart handling. Time-dependent validity still escapes those checks (B-07), and interrupted Comparison can leave a supposedly current preparation basis (B-04). |
| A-09 | Partly | ADR-001 §4.2.3 supplies both sides' links, entry kind, Cleared state and reconciliation after Unknown/Already Present. ADR-002 O-5 consumes them, but its complete storage model does not retain the necessary read metadata (B-03). B-01 also prevents clearing a newly created counterpart in the same Upload. |
| A-10 | Resolved | ADR-001 §4.2.1 defines a capability floor, §4.2.2 fixes each optional fallback, and §4.2.3 assigns adapter-owned semantic results. ADR-007 §4 resolves fallbacks into the visible plan. Approval is mandatory, rather than an unexplained switch. |
| A-11 | Resolved | S-07; `docs/entity_model.md` `UNREADABLE_TRANSACTION`; UC-001 A17/BR-016 and UC-004 A9/BR-007; ADR-001 §4.1 and ADR-002 O-1. Partially readable fields and a reason are retained; the Operator can defer, and the record cannot be uploaded. |
| A-12 | Resolved | ADR-001 §4.0 requires a request context for every network operation, durable Data Provider admission/counting, timeout and underlying-call cancellation; §4.1 and §4.2 apply it to consent and Budget App calls. §4.4 and ADR-005 E-11 specify the verification. O-P2-13 approves the consent admission policy. |
| A-13 | Resolved | ADR-001 §4.1 now has Source, Bank and Bank Account outcomes and an explicit continuation protocol. Incomplete paged deliveries do not replace stored transactions; §4.4 requires mixed-scope and pagination tests. The original uncertainty about provider-wide stopping is addressed. |
| A-14 | Resolved | ADR-001 §4.1, ADR-002 §2 `consent_request_correlation` and §3 O-10 decide durable digest/handle storage, most-recent/lifetime checks, atomic consumption and replacement guarded against newer requests. Process recreation and replay are explicitly covered. |
| A-15 | Resolved | S-08/S-10; `OFFERED_BANK_ACCOUNT`; UC-009 BR-009/A10; ADR-001 §4.1 and ADR-002 §1/O-2. Proposals and masked display details survive ordinary process restarts in `device.db`, are replaced on delivery and are excluded from backups. |
| A-16 | Partly | S-11 narrows FR-093 explicitly; ADR-003 §1/§5 P-2 add canonical-path checks, §2 namespaced keys, §5 P-5/P-6 renamed-fragment and secret-value checks, and §6 validation before generation. P-5 still misses a recognizable configuration fragment in the accepted properties syntax (B-13). |
| A-17 | Resolved | ADR-006 §1 replaces arbitrary event names with enum constants and sealed facts; the sink uses enum names rather than `toString()`. §6 tests the real adapter through a recording sink. The original free-text event-name bypass is closed. |
| A-18 | Resolved | ADR-001 §1/§3/§5 and ADR-006 §1 place `SlussenLog` in `:plugin-api` and inject it into plug-ins, making the diagnostic path compatible with their dependencies. |
| A-19 | Resolved | ADR-001 §6.3 adds bytecode checks, §6.5 narrows static claims, and §6.6 names planted fully qualified calls, indirection and result filtering. ADR-007 §7 adds runtime write admission; ADR-006 §5/§6 covers logging indirection with real-adapter tests. The new rule-scope incompatibility is tracked under A-21/B-11. |
| A-20 | Resolved | ADR-001 §6.4 puts graph verification before compilation and architecture tests before module `check` and app packaging/install tasks. It explicitly distinguishes a standalone `test` shortcut from a supported gate; §6.6 names targeted negative tests. |
| A-21 | Partly | ADR-001 §2 now supplies the Kotlin baseline and the previously omitted module allowlists; §6.2 K-07 excludes test fakes and allows wiring. However, the new bytecode rule B-04 rejects the shared writer contract and its required fakes (review finding B-11). |
| A-22 | Resolved | ADR-003 §3 requires files according to required template keys and requires Budget/Bank Account blocks, with every configured Budget used. These address both original validation errors. The absent-configuration gate regression is B-12/A-28. |
| A-23 | Resolved | S-09; `docs/entity_model.md` `BUDGET_APP_TRANSACTION.memo`; UC-011 step 4/A2; ADR-001 §4.2.3 and §7 UC-011; ADR-002 O-4. Both current reads and the offline copy now supply the memo. |
| A-24 | Resolved | Under the explicitly deferred-proof disposition: ADR-002 §8 replaces `VACUUM INTO` with a closed-file copy and specifies replacement, logical checks, running-app recovery and preservation of originals. ADR-005 §7 E-01–E-04 requires device proof before Uploads. This is acceptance of a specified prerequisite, not a claim that platform proof already exists; B-05 needs an additional interruption case. |
| A-25 | Resolved | O-P1-08/O-P2-05; ADR-005 §5/§6 chooses release signing with an externally kept, backed-up keystore and gives debug a separate application identity. E-09 requires an upgrade from another checkout with the same key. |
| A-26 | Resolved | S-01; FR-094, Rejected FR-086, UC-002 BR-004, UC-010 BR-001/BR-002 and `OPERATOR_SETTINGS.file_upload_window_days` agree on one configurable file upload window. ADR-003 §4 and ADR-001 §4.3 remove per-format configuration. |
| A-27 | Resolved | ADR-004 §2 defines the live-test credential route and Operator execution; ADR-005 §7 makes E-01–E-11 mandatory before the first slice that uploads is complete or installed for real use. The plan §6 O-P2-12 and §7 carry that obligation forward. |
| A-28 | Partly | O-P1-06 and the plan header/Phases 6–7 now choose `gradlew check` plus a packaging-refusal test, with private packaging reserved for the Operator. ADR-003 §6 nevertheless orders an absence-rejecting validator before stub generation, so the promised configuration-free check is still contradictory (B-12). |

**Totals:** 19 Resolved, 9 Partly, 0 Not resolved. These judgments use the Operator's changed guarantee; they do not reimpose the superseded destination-independent or latest-moment backup promises.

## New findings

### B-01 — A newly created Transfer Counterpart cannot be cleared in the same Upload

**Severity:** High. **Location:** `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §4, §6 steps 6–7; `docs/adr/ADR-001-module-boundaries.md` §4.2.3; `docs/use_cases/UC-006-upload-to-the-budget-app.md` BR-009; `docs/entity_model.md` `ELIGIBLE_TRANSACTION.clear_counterpart`.

**Evidence:** A `ClearCounterpart` item must contain the counterpart's Budget App reference at preparation, and may name only a counterpart already reconciled from a Comparison. Items are immutable. A new linked Transfer has no counterpart reference yet, and `Created` deliberately returns none. The protocol clears before its post-Upload Comparison. Thus, when both Transfer Legs first arrive together, it cannot prepare or execute the clearing that UC-006 BR-009 requires in that same Upload. Setting `cleared` on the uploaded leg is not a defined instruction to clear the other side, and must also distinguish a Lonely Transfer.

**Fix:** Represent the confirmed clearing intent for a newly created linked Transfer, then resolve its counterpart through a defined read/link operation before clearing within that Upload, preserving exactly the confirmed intent.

### B-02 — Clearing items are incorrectly included in the Sent Record insertion protocol

**Severity:** High. **Location:** `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §4, §6 steps 5–7 and §7; `docs/adr/ADR-002-persistence-with-room.md` §3 O-7/O-8 and §4.

**Evidence:** The new write set contains `Create`, `LinkedTransfer` and `ClearCounterpart`. O-7 nevertheless inserts a Sent Record for each item without `reused_sent_record_id`, or resets the reused Sent Record to Not Yet Known. It has no branch excluding `ClearCounterpart`. Clearing an existing counterpart sends no Eligible Transaction: the arriving leg is expressly suppressed by UC-006 BR-010. Inventing its Sent Record, or resetting the original linked send's Written record, misrepresents what was sent and can interfere with duplicate protection. No clearing-result persistence operation is specified, either. The permit text additionally limits permits to §6 step 6, although clearing is step 7 and also needs the wrapper.

**Fix:** Define separate preparation, confirmation, permit and outcome rules for clearing items; they must update counterpart state without inserting or resetting a transaction-send Sent Record.

### B-03 — The normalized read metadata has no durable representation

**Severity:** High. **Location:** `docs/adr/ADR-001-module-boundaries.md` §4.2.3; `docs/adr/ADR-002-persistence-with-room.md` §2 and §3 O-4/O-5; `docs/entity_model.md` `BUDGET_APP_TRANSACTION` and `TRANSFER_COUNTERPART`.

**Evidence:** The new reader returns both sides' `transferLink`, `Merged(intoReference)`, `cleared` and `approved`. ADR-002 maps the entity attributes directly and declares its additional technical-table list complete. Neither list stores those transaction links or merge targets; the stored transaction also lacks the Cleared/approval fields. O-4 can therefore advance the cursor while discarding information O-5 needs to reconstruct an unknown linked send. After process death, or when only one side appears in a later delta, the promised reconciliation from stored links is unavailable. `TRANSFER_COUNTERPART` does not repair this: it is the result O-5 has yet to derive.

**Fix:** Define the durable normalized read state and its atomic persistence, obtaining the necessary specification/entity change before deriving storage from it.

### B-04 — An interrupted Comparison leaves old eligibility usable with a new cursor

**Severity:** High. **Location:** `docs/adr/ADR-002-persistence-with-room.md` §3 O-4/O-5; `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §4 final paragraph, §6 steps 2–4 and §8.

**Evidence:** O-4 commits the Budget App copy and cursor; O-5 separately commits Transaction Statuses and settled Sent Records. Consider O-4 recording a newly found Other Import Path transaction, followed by process death before O-5 changes an earlier New status to Possible Import By Other Path. Restart in Normal has no incomplete-Comparison recovery rule. Preparation uses stored statuses, captures the already advanced cursor and current version, and the confirmation read can return no further changes. All prescribed checks then pass while the required review is bypassed. Raising `state_version` at O-4 does not help a plan prepared after that increment. The same issue occurs in the confirmation-change branch, which names O-4 but no O-5 despite promising current statuses.

**Fix:** Commit the dependent Comparison state atomically with the read, or persist an incomplete-Comparison marker and block preparation until Comparison has been recomputed and committed.

### B-05 — Process death during startup can bypass the Recovery Check

**Severity:** High. **Location:** `docs/adr/ADR-002-persistence-with-room.md` §6 start table and following paragraph, §8 step 3; `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §3/§8; FR-095 and UC-006 BR-017.

**Evidence:** On a restore, `installation.id` is absent and the restored database is present. The specified sequence writes `installation.id` and then opens the Recovery Check through O-12. If the process dies between those writes, the next start sees a marker and a readable database and classifies the start as normal. A backup with no open check then permits preparation without the required Comparison and confirmation. Similarly, replacing an unreadable main database with a readable last good copy can erase the detection condition before O-12 is durable. A database transaction alone cannot make these file/database transitions atomic.

**Fix:** Persist recoverable startup intent before publishing the restored/replacement state, and clear it only after the Recovery Check and coordinator reset are durable; test interruption at every boundary.

### B-06 — Journal corruption now blocks Uploads despite the journal-independence requirement

**Severity:** High. **Location:** `docs/adr/ADR-002-persistence-with-room.md` §6 and §8 step 5; `docs/requirements.md` NFR-005/NFR-006; `docs/use_cases/UC-008-check-import-status.md` A1; `docs/use_cases/UC-010-adjust-settings.md` BR-004.

**Evidence:** ADR-002 explicitly says an unreadable `journal.db` opens a Recovery Check. That check blocks Upload Plan preparation. NFR-006 still requires corrupting the Decision Journal to change zero Upload Plans or Uploads, and UC-010 BR-004 says losing it changes no such decisions. The changed NFR-005/UC-008 A1 broadly require a Recovery Check for unreadable stored data, so this is a new contradiction within the Approved specification as well as between the ADR's behavior and the journal guarantee. The sentence in §8 that losing the journal changes nothing is no longer true of its own protocol.

**Fix:** Obtain a specification decision distinguishing loss of decision-critical state from journal-only corruption, then align the recovery rules and tests with that decision.

### B-07 — The confirmation checks do not cover expiry during the read or a changing date window

**Severity:** High. **Location:** `docs/adr/ADR-007-operation-coordinator-and-upload-protocol.md` §5 and §6 steps 1–5; `docs/adr/ADR-002-persistence-with-room.md` §4; UC-006 A5/failure postconditions and UC-003 BR-011/BR-014.

**Evidence:** Expiry is checked on admission, before the network read. O-7 checks Awaiting Confirmation and reused outcomes but does not require the current time still to precede `expires_at`. A confirmation just before expiry can finish its read after expiry and proceed to send; §5 expressly says safety must not depend on the expiry timer firing. Separately, a plan spanning local midnight can contain a Bank Transaction that has just left its Upload Window. The row version, configuration fingerprint and Budget App cursor can all remain unchanged, although the transaction is no longer eligible. The exclusive lane does not stop time passing.

**Fix:** Define the final deadline and date-dependent eligibility checks at the transition to writing, after network validation, and discard/reprepare whenever the confirmed set is no longer valid.

### B-08 — Unicode normalization can merge distinct opaque Stable References before collision detection

**Severity:** Medium. **Location:** `docs/adr/ADR-008-import-identifier-protocol.md` §2 and §5; `docs/adr/ADR-001-module-boundaries.md` §4.1; `docs/glossary.md` Stable Reference; UC-003 BR-002 and NFR-020.

**Evidence:** The contract permits a Source-supplied Stable Reference as a string and does not promise equivalence under Unicode normalization. ADR-008 normalizes that identifier to Form C anyway. Two distinct references, `TX-\u00e9` and `TX-e\u0301` (escape notation for their code points), therefore have identical canonical inputs. With Source Key `example-provider`, both compute `SLS1-2obifbyikdagkslwmfqes72y7x`; I reproduced this. The §5 collision detector requires *different canonical inputs*, so it cannot detect this conflation. This is unrelated to the probability of a SHA-256 collision. Normalizing human-readable payee/memo text does not establish that opaque external identifiers may be normalized too.

**Fix:** Preserve Stable References as opaque identifiers, or explicitly constrain and validate the plug-in contract so that its identity equivalence is proven before normalization.

### B-09 — The Bank Account fingerprint discards potentially identifying characters

**Severity:** Medium. **Status:** uncertain. **Location:** `docs/adr/ADR-008-import-identifier-protocol.md` §7, `bank_account_fingerprint` row; `docs/adr/ADR-001-module-boundaries.md` §4; UC-009 BR-004.

**Evidence:** The new fingerprint is SHA-256 of the delivered Bank Account number, "digits only." The shared `BankAccountNumber` contract states no digits-only repertoire or namespace guarantee. Distinct alphanumeric number representations with the same digits therefore become the same input before hashing; increasing the digest size does not restore the lost identity. Such a fingerprint is used for certainty of identification. I cannot establish within the permitted evidence whether the current providers always return an adequate digits-only, properly scoped identifier, so this is an unresolved contract assumption, not a claim of an observed wrong identification.

**Fix:** Define and test a canonical Bank Account identity that retains meaningful letters and the necessary scope, or document and enforce the narrower input guarantee on which digits-only hashing relies.

### B-10 — Full-format recognition is narrower than the Approved marker rule

**Severity:** Medium. **Location:** `docs/adr/ADR-008-import-identifier-protocol.md` §1/§6 and Consequences; `docs/adr/ADR-001-module-boundaries.md` §4.2.3; UC-003 BR-008; `docs/entity_model.md` `BUDGET_APP_TRANSACTION` constraints.

**Evidence:** ADR-008 declares `SLS` to be the fixed marker, but recognizes ownership only with `^SLS[1-9]-[a-z2-7]{26}$`. An imported identifier starting with `SLS` but failing that full-format expression is consequently classified as Other Import Path by ADR-001. UC-003 BR-008 expressly says an Import whose Import Identifier *begins with* Slussen's marker is never treated as Other Import Path. The entity constraint likewise makes marker ownership decisive. Format validation and ownership are different tests; the ADR currently substitutes the former for the latter without a specification change.

**Fix:** Separate marker ownership from supported-format validation, keeping unsupported or malformed marked identifiers out of Other Import Path, or obtain an explicit specification amendment.

### B-11 — The new writer bytecode rule rejects the contracts and test fakes it must allow

**Severity:** Medium. **Location:** `docs/adr/ADR-001-module-boundaries.md` §1/§2, §4.2 and §6.3 bytecode rule B-04.

**Evidence:** The bytecode rules scan every module's main source set. B-04 allows references to `BudgetAppWriter` only in `slussen.core.upload`, `slussen.app.di` and plug-in modules. `:plugin-api` necessarily references the type in the shared `BudgetApp.writer` contract; it is the contract module, not a provider/Budget App implementation module. `:testing` must also implement the writer fake promised by §1, in that module's main source set. Neither is exempted. K-07's new test-fake exception does not apply to this bytecode rule. A conforming skeleton can therefore fail its own enforcement.

**Fix:** Specify bytecode scopes and exceptions for shared declarations and test-support implementations, while keeping actual production writer invocations confined to the admitted wrapper.

### B-12 — The absent-configuration stub is ordered after a validator that rejects absence

**Severity:** Medium. **Location:** `docs/adr/ADR-003-build-configuration.md` §3 and §6; `docs/adr/ADR-005-toolchain-and-version.md` §3; `docs/construction_kickoff_plan.md` Phases 6–7.

**Evidence:** §3 says `verifyBuildConfiguration` fails when `slussen.properties` is missing. §6 requires placement validation, then `verifyBuildConfiguration`, then generation, then compilation. It also promises that no Build Configuration produces an `ABSENT` stub and lets `gradlew check` pass. Under the specified task order the validator fails before that stub can be generated. The new packaging-only `requireBuildConfiguration` does not undo the earlier failure. The plan's corrected gate therefore remains impossible to implement literally.

**Fix:** Make wholly absent configuration an explicit valid validation result for compilation/check, and let `requireBuildConfiguration` reject it for packaging while retaining validation failures for partial or inconsistent configuration.

### B-13 — The placement matcher does not recognize all syntax the configuration reader accepts

**Severity:** Medium. **Location:** `docs/adr/ADR-003-build-configuration.md` §2 and §5 P-1/P-4/P-5/P-6; FR-093 and UC-012 BR-009, as changed by S-11.

**Evidence:** The reader follows Java properties syntax, but P-5 recognizes a key only when followed by `=` or `:`. A renamed `copied.properties` containing the recognizable key/value line `slussen.bankAccount.example.budgetAccount example-budget-account` uses the properties whitespace separator and falls outside P-5. P-1 does not recognize its filename; P-4 does not cover that arbitrary file; and this non-secret value is outside P-6. This is configuration with its key present, not the explicitly accepted limitation for a non-secret value pasted without its key.

**Fix:** Derive placement recognition from the same key and properties grammar as input validation, including whitespace separators and escapes, and add a renamed-fragment negative test.

### B-14 — Revised specification prose still violates defined-term capitalization

**Severity:** Low. **Location:** `docs/vision.md` Goals, Exactly once; `AGENTS.md` §2, third project rule; `docs/construction_kickoff_plan.md` §1.4.

**Evidence:** The revised goal text uses "the operator decides" and "the operator has confirmed" for the defined actor Operator, and "the answer to an upload" for the defined Upload. These are new/replaced lines in the reviewed diff. The project rule requires defined terms in Title Case wherever used in their defined sense; the plan extends the rule to every document a phase writes.

**Fix:** Apply the glossary's capitalization to the revised prose and include those cases in the planned project-rule check.

## AGENTS.md and methodological conformance

B-06 and B-10 need an explicit resolution at specification level; an implementer must not silently select which Approved rule to follow. B-03 likewise requires resolving the entity/storage contract before construction. These are applications of the specification-first doctrine, not authorization for this reviewer to change those documents.

For the three project rules: I found no new bare Bank Account/Budget Account terminology violation in the additions. The changed file upload window and backup warning threshold have Operator Setting defaults and ranges; the backup size and displayed final digits name their outside sources in the specification. Protocol bit widths, identifier lengths, platform versions and illustrative counts are not Operator-facing time/window limits. B-14 records the concrete capitalization violation; no additional numeric-rule violation is asserted.

The repository boundary and single-file output are explicit exceptions for this review and are recorded above for the orchestrator's methodology log. I have not changed `VERSION`, the plan, the ADRs or the Approved specification. This is a review commit on the existing branch, not a new PR or a phase advancing construction past the architecture gate.

## Verdict

**The Operator should not set the ADRs to Accepted yet.** B-01–B-07 are High blockers. Resolve the contract and gate contradictions B-08 and B-10–B-13 before acceptance; B-09 is uncertain and needs either evidence for its input assumption or a contract correction. B-14 is a nonblocking editorial correction. The nine Partly dispositions above remain open through those findings. ADR-005 E-01–E-11 may remain explicitly deferred until the first slice that uploads, as already approved; their future execution does not substitute for correcting the contradictions in the Proposed decisions now.
