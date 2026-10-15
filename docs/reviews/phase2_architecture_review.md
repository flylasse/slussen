# Phase 2 — External Architecture Review

**Reviewer:** OpenAI Codex, GPT-6. The Phase 2 configuration requested in the construction plan is GPT-6 Astra with Max reasoning; this session does not expose an independently verifiable serving variant or snapshot identifier.
**Reviewed commit:** `d592aa2ada4a44142ef13b244ea54d4973d1d083`
**Branch:** `construction-kickoff`
**Date:** 2026-10-14
**Disposition:** Not ready to be Accepted.

This is a review of the Proposed ADRs against the Approved specification, not an implementation review. I read the requested inputs in order, including every use case and all six ADRs, then the in-repository construction plan. I did not access another repository, external documentation, the Operator's Build Configuration, or `scratch/`. The predecessor module list in the supplied prompt was an unfilled placeholder; predecessor claims in the ADRs have not been independently verified. External Marketplace instructions were not consulted because the review's explicit repository boundary takes precedence. The orchestrator can record that scope restriction in its methodology log.

The counterexamples below are deductions from the written contracts and decisions, not claims that executable tests were run. **High** means a stated safety, privacy, durability or essential workflow guarantee is contradicted or lacks a necessary mechanism. **Medium** means a contract, implementation or validation decision needs correction or clarification. Findings explicitly marked **uncertain** require verification within the allowed implementation environment.

## Findings

### A-01 — The Sent Record transaction rejects the retries it must permit

**Severity:** High. **Location:** `docs/adr/ADR-002-persistence-with-room.md`, Decision §3.

**Evidence:** The transaction “inserts one `sent_record` per Eligible Transaction”; a duplicate (`budget_account_id`, `import_identifier`) must “fail the whole transaction.” The executor sends “only for the sends it committed.” UC-006 A8/BR-007 requires retrying an unknown outcome with the same Import Identifier and Budget Account; A9 keeps rejected transactions available for Upload.

After the first attempt leaves a Not Yet Known, Unknown or Rejected Sent Record, another Upload Plan containing that Bank Transaction necessarily hits the unique index. The prescribed A7 handling then sends nothing, including any new transactions in that plan. The sentence saying that a resend uses the recorded identifiers does not supply a path around the mandatory insert.

**Fix:** Make preparation idempotently reuse and validate existing retryable Sent Records, atomically insert only genuinely new sends, and define their association with the newly confirmed Upload Plan.

### A-02 — A local Sent Record cannot provide the promised destination-independent exactly-once guarantee

**Severity:** High. **Location:** ADR-002, Decision §3 and Consequences; `docs/adr/ADR-001-module-boundaries.md`, Decision §4.2.

**Evidence:** UC-006 BR-006 says Slussen may use the Budget App's duplicate protection but “does not depend on it”; BR-007 requires resending unknown outcomes. ADR-002 says exactly once rests on the local transaction “plus the Import Identifier the Budget App can refuse.” `BudgetAppWriter.create` has no required idempotency or conditional-write guarantee, and `readChanges` has no definitive-outcome or read-consistency guarantee.

A request that never arrived and a request that committed remotely but lost its response leave the same local state. Retrying can duplicate the latter; refusing to retry can lose the former. A read that does not yet show the transaction does not resolve an in-flight or insufficiently visible write. This problem exists even without corruption or reinstall. The Approved requirements themselves need reconciliation if replacement Budget Apps may lack the necessary remote guarantees.

**Fix:** Specify and test the minimum destination protocol needed for safe retries, and obtain an explicit specification decision for destinations that cannot provide it before claiming destination-independent exactly once.

### A-03 — Corruption recovery deliberately discards safety history and continues

**Severity:** High. **Location:** ADR-002, Decision §6 and Consequences.

**Evidence:** Recovery uses the last good copy or default content. The ADR explicitly acknowledges that this “can lose Sent Records” and that exactly once then “leans on the Budget App's duplicate protection.” Vision, Exactly once, and UC-006 BR-006 forbid that dependency; FR-042 also requires keeping decisions and Account Mapping locks.

A snapshot taken at app start lacks subsequent Sent Records, locks, deliberate Re-upload occurrences and decisions. Restoring it can resurrect a Bank Transaction or an unlocked Account Mapping. UC-003's bounded initial read is not a reconstruction protocol for all lost history, and finding an original Import Identifier does not reconstruct later Re-upload decisions. Merely informing the Operator in an ADR does not satisfy the guarantee.

**Fix:** Define a recovery state that prevents affected Uploads until the required history is demonstrably restored or reconciled, and resolve any remaining impossibility through an approved specification change.

### A-04 — Backup inclusion is presented as a zero-loss restore guarantee

**Severity:** High. **Location:** ADR-002, Decision §4 and Consequences, including O-P1-05.

**Evidence:** NFR-010 requires a restore to lose zero of the listed records. The decision specifies an include list and a manual reinstall check, but no backup freshness or completion condition. Its own Consequences state that exceeding Android's 25 MB limit skips backup, then claim “No requirement covers this.” NFR-010 and FR-042 do cover the resulting loss.

A backup made before a confirmed Upload restores an older Sent Record and older Account Mapping locks. A missing, failed or oversized backup can restore nothing. Correctly excluding the Decision Journal and Bank Consents does not establish that the main database was backed up after its latest safety-critical change. A size warning alone would still not meet zero loss.

**Fix:** Decide a verifiable recovery/backup protocol and its supported reinstall conditions, including stale, absent and oversized backups, and gate affected Uploads when restored history cannot establish the guarantee.

### A-05 — Stable identity across Build Configuration changes is unresolved

**Severity:** High. **Location:** ADR-002, Decision §2, O-P1-04; `docs/adr/ADR-003-build-configuration.md`, Decision §6.

**Evidence:** ADR-002 leaves matching a stored `bank_account` to its Build Configuration entry open because the entity model has no configuration key. ADR-003 nevertheless says startup takes the configured records and “keeps what the app itself recorded.” UC-009 A8/BR-003/BR-006 requires keeping a locked Account Mapping even when the Build Configuration points elsewhere.

Names, insertion order, generated numeric keys, a changed provider reference and the configured Budget Account are not an agreed immutable identity. Treating an existing Bank Account as a new one after a rename, reorder or rebuild can bypass its lock and separate it from its Sent Records. The behavior for removing and later restoring a configured entry is also unspecified.

**Fix:** Approve an immutable configuration identity and define reconciliation, duplicate-key, rename, removal and restore behavior before implementing startup population or migrations.

### A-06 — The Import Identifier compatibility protocol has not been decided

**Severity:** High. **Location:** ADR-001, Decision §4/§4.2 and §7 UC-003; ADR-002, Decision §3.

**Evidence:** UC-003 BR-002 and FR-074 require the same inputs to produce the same Import Identifier across reinstall, including reference-less occurrences and deliberate Re-uploads. ADR-001 assigns generation to the core and declares `importIdentifierMaxLength`, but specifies no marker, canonical input encoding, algorithm/version, collision handling or persisted Re-upload allocation protocol.

Different implementations can encode amounts, absent text, Unicode, dates and occurrence numbers differently while appearing to satisfy this ADR. A later correction then changes identity. The Approved statement that different inputs always give different bounded identifiers also needs a collision policy rather than an unstated assumption about hashing. Changing a Budget App's identifier limit cannot silently change the identifiers of existing Sent Records.

**Fix:** Freeze a versioned deterministic identifier protocol with cross-version test vectors, collision detection and durable occurrence allocation, and explicitly handle the specification's bounded-identifier constraint.

### A-07 — Required atomic operations extend beyond the one storage transaction that is defined

**Severity:** High. **Location:** ADR-001, Decision §3/§7 UC-002 and UC-003; ADR-002, Decision §2/§3.

**Evidence:** Storage ports are “one per aggregate”; the only cross-aggregate transactional operation specified is `recordBeforeSend`. UC-002 BR-001 requires all-or-nothing file import. UC-003 BR-016 requires every change since the previous Budget App read; ADR-002 stores its cursor separately in `budget_read_state`.

If a cursor advances before its transaction changes are committed, a crash can skip those changes permanently. If Bank Transactions are committed incrementally before a file's final validation or write failure, the core cannot undo a partial import merely by owning the all-or-nothing rule. The core cannot use Room directly, and separate aggregate ports provide no stated transaction boundary for either workflow.

**Fix:** Define atomic storage operations for complete file ingestion and for applying a Budget App change set together with its cursor and dependent state, with rollback/crash tests at those boundaries.

### A-08 — The Upload pause and confirmed state have no concurrency or recovery protocol

**Severity:** High. **Location:** ADR-001, Decision §3/§5/§7 UC-006; ADR-002, Decision §2/§3.

**Evidence:** UC-006 BR-004/BR-005 and FR-035/FR-036 require unchanged confirmed content and a pause from preparation until completion, cancellation, discard or expiry. ADR-001 allocates “the pause” to `upload`; ADR-002 persists the entity model's plan fields and changes the plan to Confirmed before sending, without specifying a shared operation coordinator or the state compared at confirmation.

Disabling UI actions or checking Awaiting Confirmation is insufficient: an already-running Fetch or consent return can finish after plan preparation; a worker can pass a check before the pause starts; changing a Review Decision can race confirmation. Confirmed is also no longer Awaiting Confirmation although the Upload is still running. No decision establishes how these operations serialize, what captured state is validated, how a duplicate confirmation is refused, or what happens to an open plan after process death.

**Fix:** Define a shared admission/state-transition protocol, an immutable prepared write set with versioned validation inputs, and restart/expiry behavior covering workers, callbacks, decisions and confirmation.

### A-09 — A lost Upload response makes Transfer Counterpart recovery under-specified by the contract

**Severity:** High. **Location:** ADR-001, Decision §4.2; ADR-002, Decision §2/§3.

**Evidence:** `create` may return `Created(reference, counterpartReference?)` or Unknown. `readChanges` returns a transaction reference and the other Budget Account of a linked Transfer, but no linked counterpart transaction reference, explicit counterpart provenance or Cleared state. UC-005 BR-009/BR-010 and UC-006 BR-010/BR-011 require identifying an unchanged counterpart of Slussen's own Transfer before suppressing an arriving Bank Transaction or marking it Cleared.

After a response is lost, a later read can find Slussen's uploaded leg by its Import Identifier but cannot recover an unambiguous counterpart through the declared fields. Two Transfers with the same amount, dates and Budget Accounts cannot safely be distinguished by matching those fields. The sentence assigning counterpart recognition to the plug-in does not define how that result reaches the core. Even an acknowledged create may omit `counterpartReference`.

**Fix:** Return normalized, stable links and provenance for both sides plus the state needed for safe settlement, including reconstruction after Unknown or Already Present results, and test ambiguous-looking Transfers.

### A-10 — Budget App capability flags leave behavior that the core must guess

**Severity:** Medium. **Location:** ADR-001, Decision §4.2/§5 and Consequences.

**Evidence:** NFR-015 and UC-012 BR-006 isolate all Budget App-dependent behavior. The profile includes `supportsApprovalMarker`, but the writer unconditionally receives `awaitingApproval`; UC-006 BR-008 requires created transactions to await approval. `supportsLinkedTransfers` also changes whether one or two ordinary transactions must be written (UC-006 A10). The ADR says a replacement “fills the same profile” but supplies no compatibility rules for these cases.

The core is also assigned classification as Typed Entry, Slussen or Other Import Path from a read model containing only an optional Import Identifier, although counterpart recognition belongs to the plug-in. Numeric limits and switches do not by themselves specify how different origin, matching, merge and approval behavior is normalized. An implementer must either invent core rules for missing capabilities or leak a particular Budget App's interpretation into them. A fallback discovered only during writing can also change the counts and content of the confirmed Upload Plan.

**Fix:** Define the required capability floor and adapter-owned semantic results, and resolve supported fallbacks into the visible Upload Plan before confirmation.

### A-11 — An uninterpretable transaction cannot be retained for the required review

**Severity:** High. **Location:** ADR-001, Decision §4.1, `FetchResponse`.

**Evidence:** The response permits “`uninterpretable` with no content.” FR-009, NFR-002 and UC-003 BR-012 require such a Bank Transaction to remain visible as Needs Review. `BANK_TRANSACTION` requires date, amount, currency and Import Identifier; UC-004 step 2 shows date, payee, amount and Bank Account.

A content-free outcome cannot be turned into that stored entity, assigned a reproducible identity, shown meaningfully, or later imported by an Operator decision. Dropping it violates the requirement; inventing its mandatory fields would be guessing. No separate retained record for an unreadable item exists in ADR-002's complete storage list.

**Fix:** Define a lossless, safe representation and persistence/review path for uninterpretable items, obtaining an entity/specification clarification where the existing mandatory fields cannot be supplied.

### A-12 — Request limits and timeouts do not cover all network operations

**Severity:** High. **Location:** ADR-001, Decision §4.1/§4.2/§5.

**Evidence:** `FetchRequest` carries `ProviderRequestGate` and `RequestTimeout`. `startConsentRequest`, `completeConsent` and `revoke` carry neither; neither do the Budget App methods. Constructors in the composition example receive only `PluginSettings`. UC-001 BR-007 counts “every request” to a Data Provider; NFR-016 bounds every Data Provider and Budget App request by the current Operator Setting.

Consent start/completion/revocation can issue requests outside the only described counting/admission path. Budget App operations and consent operations have no stated route for the changeable timeout or cancellation of underlying I/O. Wrapping a suspend call in a timeout would require an explicit cancellation contract; it does not by itself establish that a library's request has stopped. These gaps affect both the Request Limit and truthful Unknown outcomes.

**Fix:** Specify request admission, durable counting and timeout/cancellation context for every network operation, with defined exceptions if consent actions need different Back-off treatment.

### A-13 — A provider-wide outcome and stop rule may defeat per-Bank isolation

**Severity:** Medium. **Status:** uncertain. **Location:** ADR-001, Decision §4.1.

**Evidence:** `FetchResponse` has “one outcome for the Data Provider”; the sum includes a single `AskedToWait`, `RequestLimitWarning` or `ConsentRefused(bankReference)`. On any gate Refused the plug-in “sends nothing more.” UC-001 A5/A8/A9/BR-010 requires unaffected Banks and Bank Accounts to continue, including when one Bank requests a wait or loses consent.

The text does not show how one Fetch reports several different Bank-scoped control outcomes while retaining successes, or how a bank-specific access refusal encountered during pagination lets another Bank Account continue. This might be implementable through per-Bank subcalls and richer per-Bank failures, but neither that continuation protocol nor those result types is specified. A flat interpretation of the contract stops too much work or loses control information.

**Fix:** Define structured per-scope control outcomes and distinguish stopping a Bank Account, a Bank or the entire Data Provider, with mixed-result and pagination tests.

### A-14 — The Consent Request state needed for callback validation has no defined storage or consumption operation

**Severity:** Medium. **Location:** ADR-001, Decision §4.1; ADR-002, Decision §1/§2.

**Evidence:** `startConsentRequest` returns an opaque `state`, while the core must enforce most-recent, lifetime and accepted-once checks. NFR-013 and UC-007 BR-002 require those checks. The persisted `consent_request` has only its local id, Bank, Data Provider, timestamps and `answered`; the complete additional-table list contains no request correlation state.

The ADRs do not connect the returned state to a stored Consent Request or define atomic consumption and replacement. In particular, they do not decide what survives while the Bank's authentication UI runs, how process recreation is handled, or how an older completion is prevented from replacing a newer Bank Consent after a race. Rejecting an unrecognized return can be safe, but the supported lifecycle and recovery behavior need to be explicit.

**Fix:** Define masked callback correlation storage in the excluded consent database and atomic validation/consumption/replacement semantics, including process recreation, replay and renewal races.

### A-15 — Later Bank Account identification has no retained proposal data

**Severity:** Medium. **Location:** ADR-001, Decision §4/§4.1/§7 UC-009; ADR-002, Decision §1/§2.

**Evidence:** `BankAccountNumber` is never stored, UC-009 receives no plug-in and its proposals must come from what the Source last delivered. UC-009 step 3 needs the proposed and alternative Bank Accounts, their descriptions and the last four digits; A6 must not contact the Source. ADR-002's complete state list contains configured/identified Bank Accounts, but no unconfirmed offered Bank Accounts or masked proposal details.

An unmatched delivery cannot be placed into the confirmed identification fields without violating their meaning. Once transient Fetch data is gone, the prescribed stored-data-only identification flow has no specified source for its alternatives or last four digits. This also affects the remedy shown on opening Slussen after a failed identification.

**Fix:** Decide where safe unconfirmed identification proposals and masked display details are retained, how they expire or are replaced, and how UC-009 works when they are unavailable.

### A-16 — The placement rules miss recognizable misplaced Build Configuration

**Severity:** High. **Location:** ADR-003, Decision §5/§6 and Consequences.

**Evidence:** FR-093 and UC-012 A6/BR-009 cover a Build Configuration “or part of one” outside its ignored location. P-1 recognizes filenames, P-3 examines templates, and P-4 recognizes only keys starting `slussen.`. The actual declared keys include `bankAccount.<key>.budgetAccount`, not `slussen.*`. The ADR explicitly admits source-file credentials can escape P-1 through P-4.

A tracked `copied.properties` containing unchanged `bankAccount.demo.budgetAccount` and other declared keys is recognizable configuration but matches none of those rules. The prescribed generator also has no explicit dependency on successful placement validation before it reads its input, and no canonical-path rule addresses a local path that resolves elsewhere. A PR review is a different safeguard from UC-012's required build refusal.

**Fix:** Define an enforceable configuration grammar/detection scope, reject recognizable fragments under renamed paths, validate canonical ignored/untracked inputs before generation, and explicitly resolve any remaining literal “anywhere” requirement the build cannot enforce.

### A-17 — Arbitrary event names bypass the supposedly typed logging boundary

**Severity:** High. **Location:** `docs/adr/ADR-006-logging-and-sensitive-data.md`, Decision §1/§2/§5/§6.

**Evidence:** `SlussenLog.event(name: String, vararg facts: LogFact)` is always written. The ADR claims NFR-007 is “a property of the type” because `LogFact` has no free-text constructor. NFR-007/NFR-008 prohibit personal transaction fields in default logs and secrets in every mode.

`log.event(bankTransaction.payee)` supplies no `LogFact` and violates no listed logging rule. A revealed credential returned as a plain string from another function could enter through the same parameter. `detail` has the same unbounded name channel; restricting direct `Throwable.message` calls does not restrict those strings. The proposed core tests with fakes do not establish the behavior of the real Android sink or all plug-in paths.

**Fix:** Use a closed event identifier type and constrained payloads, and test the actual logging adapters and secret-handling paths with planted values in both names and payloads.

### A-18 — Plug-ins cannot use the logging port the logging ADR tells them to use

**Severity:** Medium. **Location:** ADR-006, Decision §1/§4; ADR-001, Decision §2/§3.

**Evidence:** `SlussenLog` belongs to `slussen.core.port`. Plug-ins may depend only on `:plugin-api`, never `:core`. Nevertheless ADR-006 §4 allows a plug-in to send sanitized error text to `SlussenLog.detail`, and its Consequences require Detailed Logging for Data Provider troubleshooting. ADR-001 does not provide a plug-in logging contract.

Implementing the described diagnostic path requires a forbidden dependency or a new interface that the ADRs do not define. Returning the raw provider error to the core is also explicitly forbidden. This is a compile-time incompatibility between two decisions, not merely a missing test.

**Fix:** Put a suitably restricted diagnostic contract at an allowed plug-in boundary, or define a different permitted transport for sanitized diagnostics and its enforcement.

### A-19 — Import and package checks do not enforce the claimed behavioral boundaries

**Severity:** High. **Location:** ADR-001, Decision §6.1/§6.2 and Consequences; ADR-006, Decision §5.

**Evidence:** Several rules inspect imports; §6.1 checks only first-level dependencies and says a forbidden fully qualified reference cannot compile without its module. K-11 claims pure File Pre-processing, K-05/K-06 restrict writes and Scheduled Fetches, and L-01 restricts Android logging. The ADR also concludes that lacking a core dependency means a Data Provider “cannot filter, deduplicate or decide.”

Concrete gaps in those claims:

- JDK I/O is already on a JVM module's classpath: a fully qualified `java.net.URL` call or `java.nio.file.Files` call needs no new dependency and bypasses the listed K-11 imports. No rule forbids a File Pre-processor's clock or mutable state either.
- An Android UI file can name a plug-in using its fully qualified name because the app already depends on it; K-12 checks imports. A fully qualified Android log call likewise bypasses L-01.
- A Scheduled Fetch can call a facade outside `slussen.core.upload` which delegates to the Upload executor; K-06's direct-reference check does not establish that it cannot cause a write. K-05 does not prove that an executor call has a still-valid Operator confirmation.
- A plug-in can filter its own result list without referencing the core at all. A module graph cannot enforce result completeness.

The two proposed planted violations cover only a forbidden dependency and an import, not these paths.

**Fix:** Narrow the claimed static guarantees, check resolved references/classpaths where applicable, enforce write admission at runtime, and add negative tests for fully qualified calls, indirection and contract-level data loss.

### A-20 — Building a subset can skip the architecture gates

**Severity:** High. **Location:** ADR-001, Decision §6; ADR-003, Decision §5; `docs/adr/ADR-005-toolchain-and-version.md`, Decision §3.

**Evidence:** ADR-001 says both architecture layers are wired into `gradlew check`, “so neither can be skipped by building a subset.” ADR-003 separately attaches only placement validation to the app's `preBuild`. No decision attaches `verifyModuleGraph` and the Konsist rules to artifact-producing tasks or each module's targeted build/test entry points.

Wiring a task into `check` does not make `:app:assembleDebug` or a targeted module task depend on it. A developer can add a forbidden dependency, thereby put the forbidden code on the classpath, and compile/package without the check that rejects the dependency. Merely including every module in settings does not schedule every verification task.

**Fix:** Specify the task dependency graph for required validation on supported targeted builds and packaging, and prove it using those entry points rather than only root `check`.

### A-21 — The enforcement tables reject parts of the prescribed implementation

**Severity:** Medium. **Location:** ADR-001, Decision §1/§2/§6.2 K-07.

**Evidence:** `:testing` must implement in-memory fakes of the core's ports, but K-07 permits references to `DecisionJournalReader` only in `JournalExport` and `:persistence`, while scanning production and tests. The external-library allowlist omits `:testing` and a normal Kotlin library baseline for `:architecture-tests`; the `:app` row allows AndroidX/Compose libraries but not the Kotlin standard library. §6.1 rejects every unlisted direct library.

The promised fake reader would violate K-07. Ordinary Kotlin dependency declarations/default additions also need a disposition under the supposedly exhaustive allowlist; the test-only JUnit/Konsist exceptions do not supply a Kotlin standard-library rule. Implementers must invent exceptions or change the ADR before a conforming skeleton can pass.

**Fix:** Make production/test rule scopes and the shared Kotlin dependency baseline explicit, including every named module and the required reader fakes/composition wiring.

### A-22 — Build Configuration validation both rejects a permitted plug-in and admits an unusable build

**Severity:** Medium. **Location:** ADR-003, Decision §2/§3/§6.

**Evidence:** §2 says a File Pre-processor has a template “only if it needs a value”; §3 fails when “a plug-in named by a Bank Account has no file.” Repeated blocks may occur “any number of times,” with required keys checked within each block. UC-008's precondition requires at least one Budget with a mapped Bank Account; UC-012 requires a complete, consistent working build.

A File Pre-processor needing no private settings is allowed by §2 yet its configured Bank Account triggers §3's missing-file failure. Conversely, no rule requires any Budget or Bank Account block to exist: an existing but empty `slussen.properties`, accompanied by valid required service files, can satisfy the enumerated checks without meeting the startup precondition.

**Fix:** Require plug-in files according to their actual schema requirements and validate the minimum configured Budget/Bank Account population needed by the startup use case.

### A-23 — Journal Export cannot obtain the Budget App memo text

**Severity:** Medium. **Location:** ADR-001, Decision §4.2/§7 UC-011; ADR-002, Decision §1/§2.

**Evidence:** UC-011 steps 3–4/A2 and FR-068 require exporting the Budget App's current transactions, falling back to the stored copy, optionally coding payee and memo text. `readChanges` returns payee but no memo; the Approved `BUDGET_APP_TRANSACTION` entity also has no memo, and ADR-002 maps that entity directly.

The exporter cannot include or code a memo of an external Budget App transaction that the reader never supplies. In particular, memos on Typed Entries and Other Import Path transactions cannot be recovered from Slussen's own Bank Transactions. This is an inherited mismatch between the use case and entity model that the proposed contract has left unresolved.

**Fix:** Obtain a specification decision on the required export fields and extend the normalized read/storage/export contract accordingly, including the offline path.

### A-24 — The last-good-copy and corruption mechanism needs a platform proof

**Severity:** Medium. **Status:** uncertain. **Location:** ADR-002, Decision §6; ADR-005, Decision §1.

**Evidence:** The app supports `minSdk` 26, uses Room with no specified bundled SQLite driver, and runs `VACUUM INTO slussen.lastgood.db` on every app start. The recovery text relies on `SupportSQLiteOpenHelper.Callback.onCorruption`, while also treating unknown enum text and foreign-key failures as unreadable databases.

The ADR supplies no evidence that `VACUUM INTO` exists on the minimum platform's SQLite, or a protocol for replacing an already existing last-good destination on a subsequent start. It also does not explain how Room/conversion exceptions that are not native corruption notifications reach this recovery path, or how the original database and any required rollback sidecar are preserved together. These are platform/API questions not verified externally under this review's scope; they must not be treated as established guarantees.

**Fix:** Prove the recovery protocol on the minimum supported Android version, including repeated starts, failed snapshot replacement, logical decoding failures and preservation of unreadable originals, and amend the selected mechanism if needed.

### A-25 — The install/signing decision leaves the upgrade guarantee conditional

**Severity:** Medium. **Location:** ADR-005, Decision §6 and Consequences, O-P1-08.

**Evidence:** FR-042/NFR-017 require same-build upgrades to keep the stored history. ADR-005 leaves the installed variant and signing key open and defaults to a machine's debug key; its Consequences admit that another machine then requires uninstalling, losing data unless restored.

An increasing `versionCode` alone does not establish an upgrade path. The ADR knowingly permits the first installation to use an identity that may not be reproducible for later builds. Moving to a later release key can cause the same discontinuity. This interacts directly with the incomplete reinstall guarantees in A-04.

**Fix:** Decide the persistent application/signing identity, key custody and supported build/install path before the first Operator installation, and test an in-place upgrade with retained safety state.

### A-26 — File Format Upload Window precedence is still an unapproved specification interpretation

**Severity:** Medium. **Location:** ADR-003, Decision §4; ADR-001, Decision §4.3; `docs/construction_kickoff_plan.md`, O-P1-02.

**Evidence:** ADR-003 makes the File Pre-processor's declared Upload Window a default overridden by Build Configuration and labels this reading open. UC-010 BR-001/`OPERATOR_SETTINGS` assigns the value to Build Configuration, while FR-084/FR-086 and UC-002 BR-004 speak of the File Format's declared window. The plan records that alignment needs a specification change; `AGENTS.md` §1 requires specification changes first.

The interpretation determines which transactions can be offered for Upload. Recording the ambiguity does not authorize an implementer to choose precedence or a default on the Operator's behalf. Accepting the contract without resolving O-P1-02 would turn an open behavioral decision into code.

**Fix:** Obtain and record the Operator's specification decision on declaration, default and override precedence before accepting the corresponding contract and configuration rules.

### A-27 — The proposed gates do not demonstrate the persistence and Budget App guarantees they claim

**Severity:** Medium. **Location:** ADR-002, Decision §3/§4/§5/§9; `docs/adr/ADR-004-traceability-markers-kotlin.md`, Decision §2; ADR-005, Decision §3; construction plan, Phase 6/7/9 gates.

**Evidence:** The Sent Record, migration and persistence proofs are instrumented tests. ADR-005 explicitly excludes them from `check`; the construction plan's gates do not require `connectedCheck`. Live Budget App tests are opt-in and their credential route is undecided. C-011 requires real throwaway-Budget proof before relying on matching, deletion, merging and Transfer behavior.

A green default gate can therefore leave the central transaction and upgrade mechanisms unexecuted. XML include-list checks do not prove a coherent restore. A `live` tag describes selection, not the acceptance condition that prevents an unproven Budget App behavior from being trusted for Upload. The current first slice need not upload, but the ADRs must make the prerequisite explicit for the slice that first does.

**Fix:** Define required device, crash/restore/migration and live Budget App acceptance evidence, with a safe credential route and an explicit gate before enabling Uploads that depend on those results.

### A-28 — The construction gate and Build Configuration policy are mutually incompatible

**Severity:** Medium. **Location:** ADR-003, Decision §6/§8 and Consequences, O-P1-06; construction plan, Phase 6 and Phase 7 gates.

**Evidence:** The plan requires `gradlew build` to pass. ADR-003 intentionally makes that command fail without private Build Configuration and forbids agents from accessing it. Its Consequences propose a different gate but leave O-P1-06 open. `AGENTS.md` §3 says the phase is incomplete until its stated gate exits zero.

An executor following both instructions cannot complete either phase. It must not silently use private configuration, substitute a different gate, or claim success from an expected failing build. This is a concrete unresolved workflow decision, not an implementation choice.

**Fix:** Have the orchestrator and Operator resolve the documented gate to configuration-free verification plus explicit packaging-refusal tests, with private packaging reserved for the Operator.

## Coverage of the requested review

| Use case | Findings affecting its implementation or guarantees |
|---|---|
| UC-001 Fetch Transactions | A-07, A-08, A-11, A-12, A-13, A-15, A-19 |
| UC-002 Import a Transaction File | A-06, A-07, A-08, A-19, A-22, A-26 |
| UC-003 Compare With the Budget App | A-02, A-03, A-06, A-07, A-09, A-10, A-11, A-12 |
| UC-004 Review Doubtful Transactions | A-01, A-03, A-04, A-06, A-08, A-11 |
| UC-005 Review Transfers | A-03, A-04, A-08, A-09, A-10 |
| UC-006 Upload to the Budget App | A-01 through A-10, A-12, A-19, A-20, A-27 |
| UC-007 Grant Bank Consent | A-08, A-12, A-13, A-14, A-15, A-17 |
| UC-008 Check Import Status | A-03, A-04, A-05, A-08, A-15, A-19, A-22, A-24 |
| UC-009 Manage Account Mappings | A-03, A-04, A-05, A-08, A-15 |
| UC-010 Adjust Settings | A-04, A-07, A-08, A-12, A-17, A-18, A-21, A-26 |
| UC-011 Export the Decision Journal | A-08, A-12, A-18, A-21, A-23 |
| UC-012 Set Up an Own Build | A-02, A-05, A-06, A-10, A-16, A-18 through A-22, A-25 through A-28 |

The review also checked the three `AGENTS.md` project rules manually: qualified Bank Account/Budget Account terminology, the provenance/configurability of behavioral numbers, and Title Case/code notation for defined terms and identifiers. No additional, independently evidenced violation of those three rules is asserted. Technical SDK/version values, storage representation and illustrative counts were not treated as Operator-selected behavioral limits. The specification-first and deterministic-gate conflicts are recorded in A-26 and A-28.

## Verdict

**The ADRs are not ready to be Accepted.** Confirmed High findings **A-01–A-09, A-11–A-12, A-16–A-17 and A-19–A-20** block acceptance. The incompatible contracts/checks and unresolved specification/gate decisions in **A-18, A-21–A-23, A-26 and A-28** also need disposition before acceptance. **A-13 and A-24 are uncertain**, and require verification rather than being treated as established implementation failures. The remaining Medium findings require explicit decisions or acceptance prerequisites; none should be silently delegated to an implementer's guess.
