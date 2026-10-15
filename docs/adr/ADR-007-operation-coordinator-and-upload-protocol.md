# ADR-007: The Operation Coordinator and the Upload Protocol

## Status

Proposed (2026-10-14, architecture gate, revision after the Phase 2 review; revised 2026-10-15 after the Phase 2b
re-review and S-12). The Operator sets it to Accepted at the architecture gate.

This ADR is new. The Phase 2 review showed that the Upload pause, the check before writing, retries of unknown
outcomes and the Recovery Check all need one shared protocol that decides which operation may run when, what an Upload
Plan captures and what happens after the process dies (A-01, A-08). Spread over ADR-001 (modules) and ADR-002
(storage), it was not decided anywhere; it gets its own record.

## Context

- UC-006 BR-005, FR-036: at most one Upload Plan awaits confirmation; from its preparation until it is written,
  cancelled, discarded or expires, fetching (a Scheduled Fetch that falls due starts when the pause ends), file
  imports, reading the Budget App, granting, renewing or revoking a Bank Consent, Account Mapping changes and changes
  to Operator Settings are unavailable; only confirming, cancelling and changing Review Decisions or Transfer
  Decisions are allowed; looking at the import status stays available.
- UC-006 BR-003, BR-004, FR-035, NFR-003, step 5, A4, A5: an expired plan writes nothing; immediately before writing,
  Slussen checks the Budget App, the Bank Transactions and the configuration again and writes nothing when any of them
  changed. UC-006 A3: a changed decision replaces the plan with a new one with a new expiry.
- UC-006 BR-006, BR-007, A8, A9, FR-038, FR-041, NFR-001 (changed by S-05): Slussen records each transaction in the
  Sent Record before sending it and never sends again one the Budget App confirmed. A send with an unknown outcome is
  sent again automatically, under the same Import Identifier and to the same Budget Account, only when the Budget App
  plug-in declares that the Budget App refuses a reused Import Identifier; otherwise it waits for the Operator's Review
  Decision (UC-004 BR-008). A rejected send stays available for Upload (A9).
- UC-006 BR-017, A13, FR-095, `RECOVERY_CHECK` (S-06): while a Recovery Check is open, no Upload Plan is prepared.
- UC-006 BR-001, UC-001 BR-013: nothing is uploaded automatically or by a Scheduled Fetch.
- UC-006 A10, BR-008, BR-009, BR-016: how a Transfer, the Cleared marker and approval are written; the plan states
  whether its transactions arrive Cleared and which Transfer Counterparts it marks Cleared.
- UC-007 BR-002, A2: the answer to a Consent Request is accepted only within its lifetime and once.
- Phase 2 review A-01, A-08, A-10 (fallbacks), A-19 (runtime write admission), A-02 and A-03 (gating).
- Phase 2b re-review: B-01 (a new Transfer Counterpart could not be cleared in the same Upload), B-02 (clearing items
  went through the Sent Record protocol), B-04 (an interrupted Comparison left old statuses usable), B-07 (expiry and
  date-dependent eligibility were not rechecked at the transition to writing; the date part was later reversed by
  S-18).
- UC-006 BR-018 (S-18, O-P2-16): which transactions a plan holds is decided once, when it is prepared, from the date at
  that moment; a change of date while the plan awaits confirmation changes nothing, and confirming writes exactly what
  the plan showed. Before writing, only the visible expiry (BR-003) and the unchanged Budget App, Bank Transactions and
  configuration (BR-004) are checked.
- UC-003 BR-020 (S-12): the stored copy keeps each transaction's Transfer link, merge target, Cleared and approved
  together with the read point.

## Decision

### 1. One coordinator

`slussen.core.operation.OperationCoordinator` is the only way to start an operation that reads a Source or the Budget
App, writes to storage, or writes to the Budget App. One instance exists per app process, created by
`CompositionRoot` (ADR-001 §5). Every caller (a ViewModel, the Scheduled Fetch worker, the return from Strong Customer
Authentication) asks it for admission with the operation's kind and origin (`Operator` or `Schedule`). Looking at the
import status (UC-008 main flow) and the other read-only screens are not operations; they read stored data at any
time.

Operations run on one **exclusive lane**: at most one runs at a time. An operation the Operator starts while another
runs waits for the lane and the screen shows what is running; the Operator can withdraw the waiting request. A
Scheduled Fetch that finds the lane busy waits too.

### 2. Modes and admission

The coordinator has three modes, kept in the technical table `coordinator_state` of `slussen.db` (ADR-002 §2) so that
a restart sees them: **Normal**, **Plan Open** (an Upload Plan awaits confirmation) and **Uploading** (a confirmed
plan is being written).

| Operation (use case) | Normal | Plan Open | Uploading |
|---|---|---|---|
| Fetch the Operator starts (UC-001) | admitted | refused, with the reason | refused |
| Scheduled Fetch (UC-001 A12) | admitted | deferred until the mode is Normal again | deferred |
| Import a Transaction File (UC-002) | admitted | refused | refused |
| Read the Budget App and compare (UC-003), Journal Export (UC-011) | admitted | refused | only as the reads of §6 steps 4, 8 and 9 of the running Upload |
| Start, answer or revoke a Consent Request or Bank Consent (UC-007) | admitted | refused | refused |
| Account Mapping change (UC-009), Operator Setting change (UC-010) | admitted | refused | refused |
| Review Decision, Transfer Decision (UC-004, UC-005) | admitted | admitted; the plan is replaced (§5) | refused until the Upload ends |
| Prepare an Upload Plan (UC-006) | admitted only when §3 allows it | refused (one plan at a time) | refused |
| Confirm the plan, cancel the plan (UC-006 steps 4-5, A2) | not applicable | admitted for that plan only | refused (no second confirmation) |
| Confirm a Recovery Check (UC-008 A9) | admitted | refused | refused |

"Refused" returns a typed reason that the screen shows; nothing is half started. A deferred Scheduled Fetch is
remembered in `coordinator_state` and started when the mode returns to Normal (UC-006 BR-005), also after a restart.

### 3. When an Upload Plan may be prepared

Preparation is admitted only when all of these hold; otherwise the reason is shown and no plan exists:

- the mode is Normal and the request comes from the Operator (origin `Schedule` is always refused: UC-006 BR-001,
  UC-001 BR-013);
- no Recovery Check is open (`RECOVERY_CHECK.confirmed_at` empty for none; UC-006 BR-017, A13);
- no Consent Request is unanswered and within its lifetime, so that the return from Strong Customer Authentication
  can never arrive while a plan is open (the Operator can abandon the Consent Request to proceed);
- no Comparison is pending (§3a);
- the Budget App plug-in passed the composition checks of ADR-001 §4.2 and ADR-008 §6.

### 3a. A Comparison is never left half done (B-04)

A Comparison stores its read (O-4: the Budget App copy, with its links, merge targets, Cleared and approved, and the
next cursor, UC-003 BR-020) and then its conclusions (O-5: Transaction Statuses, settled Sent Records, Transfer
Counterparts). O-4 sets `coordinator_state.comparison_pending` in the same transaction; O-5 clears it in its own. While
it is set, no Upload Plan is prepared. The coordinator recomputes the Comparison from the stored data alone (no network
request) and commits it with O-5 at the next start and whenever it finds the marker set. A plan is therefore always
prepared from statuses that belong to the stored copy and cursor it captures.

### 4. Preparation: the immutable write set and retries

Preparation is one storage operation (ADR-002 §3, O-6). It computes everything the Upload will send and stores it, so
that nothing is decided while writing:

- one `upload_plan` record (entity model) with `state` Awaiting Confirmation, `expires_at` = `prepared_at` + the
  Upload Plan lifetime (10 minutes by default, an Operator Setting, NFR-003), and the counts and totals;
- one `eligible_transaction` record per Eligible Transaction (entity model);
- one `upload_plan_item` record per write (technical, ADR-002 §2), of one of two families with separate rules:
  - **transaction-send items**, write kind `Create` or `LinkedTransfer`, with the exact payload: Budget Account,
    Import Identifier, `occurrence`, date, amount, payee and memo already shortened (UC-006 BR-015), Cleared, for
    `LinkedTransfer` the other Budget Account and `clear_counterpart` (the clearing intent, true only when both Transfer
    Legs are present, Upload as Cleared is on and the Budget App supports the Cleared marker,
    `ELIGIBLE_TRANSACTION.clear_counterpart`), and, for a retry, the `sent_record_id` it reuses with the outcome that
    record had. Only these get a Sent Record (§6 step 6);
  - **clearing items**, write kind `ClearCounterpart`, naming an existing Transfer Counterpart (its `id` and Budget
    App reference) that a Comparison reconciled from the stored links and that is still uncleared: the counterpart of
    an earlier Lonely Transfer whose other side has arrived (UC-006 BR-010), or one whose clearing failed before
    (A11). They carry no Import Identifier and never touch a Sent Record (B-02);
- the inputs the check before writing compares (§6): the `state_version` of `coordinator_state`, the
  `ChangeCursor` of each Budget concerned, and the configuration fingerprint (ADR-003 §6).

The counts, totals and the list shown to the Operator (UC-006 step 3) are computed from these items, so the plan shown
is the plan written; `counterpart_count` counts the clearing items plus the `LinkedTransfer` items whose
`clear_counterpart` is true. `upload_plan_item` records are only ever inserted by O-6; no storage operation updates them.

Which send each Eligible Transaction becomes (this is what makes a retry possible, A-01):

| The Bank Transaction's Sent Record for its current occurrence | Item |
|---|---|
| none | a new send: `occurrence` 0, the Bank Transaction's own Import Identifier, the Budget Account of its Account Mapping |
| Rejected (UC-006 A9) | a retry of that Sent Record: same Import Identifier, same Budget Account |
| Not Yet Known or Unknown, and the Budget App refuses a reused Import Identifier | a retry of that Sent Record (UC-006 BR-007) |
| Not Yet Known or Unknown, the Budget App does not refuse a reused Import Identifier, and the Review Decision is Import Anyway | a retry of that Sent Record (UC-004 BR-008) |
| Not Yet Known or Unknown otherwise | not eligible; the Bank Transaction waits for the Operator's Review Decision |
| Written or Already Present, with a Re-upload decision | a new send with the next free occurrence and its Import Identifier (ADR-008 §5) |
| Written or Already Present otherwise | not eligible (UC-006 BR-006) |

A retry uses the Budget Account recorded in its Sent Record. If the Bank Account's Account Mapping no longer points
there (impossible while the lock of UC-009 BR-003 holds), the item is left out and the Bank Transaction shows the
Blocked Reason "configuration error".

Budget App capabilities are resolved here, never while writing (A-10):

- When the Budget App plug-in declares no linked Transfers, each Transfer Leg that would be written as a linked
  transfer is planned as a `Create` item instead, the other Transfer Leg stays an ordinary Bank Transaction that is
  planned too when it is eligible (UC-006 A10), and the plan's counts say so.
- When it declares no Cleared marker, every item is uncleared, the plan states that its transactions arrive
  uncleared, and it has no `ClearCounterpart` item (UC-006 BR-008, BR-016).
- A `ClearCounterpart` item names only a Transfer Counterpart that the Comparison reconciled from the stored links
  (ADR-001 §4.2.3, ADR-002 §3 O-5), never one taken from the answer to a write. A counterpart that a `LinkedTransfer`
  item of this plan will create has no reference yet; its clearing is the item's `clear_counterpart` intent, resolved
  by the defined read of §6 step 8 (B-01).

A plan is prepared from the stored statuses of the latest completed Comparison (§3a); the read and the checks at
confirmation (§6) are what make sure they are still current. **Date-dependent eligibility is decided here, once
(UC-006 BR-018):** the coordinator takes the date from `Clock` at preparation (the date of `prepared_at`) and applies
the Upload Window, the file upload window, the Start Date of each Bank Account and the Lonely Transfer grace period
with that date. The items it stores are the plan; a change of date while the plan awaits confirmation changes none of
them, and nothing rechecks them before writing. Eligibility is UC-003 BR-014 as those statuses give it: in
particular, a Transfer one of whose Transfer Legs is Needs Review (for any reason) or Possible Import By Other Path and
has no Review Decision yields no item at all, neither through that Transfer Leg nor through another, until each such
Transfer Leg has a Review Decision (UC-005 BR-013, UC-004 BR-009). When one is Already Present, the whole Transfer
counts as already in the Budget App and yields no item; otherwise, when one is Defer, the whole Transfer stays out;
when every one is Import Anyway, the Transfer is planned by the rules above (S-17).

### 5. While the plan is open

- A Review Decision or Transfer Decision is stored and the plan is replaced by a newly prepared one, with a new expiry,
  in one exclusive step (UC-006 A3, UC-004 A8). The old plan becomes Cancelled.
- Cancelling sets the plan to Cancelled and the mode to Normal (UC-006 A2).
- When `expires_at` passes, the coordinator sets the plan to Expired and the mode to Normal (UC-006 A4). Confirmation
  checks the expiry again, so a timer that did not fire changes nothing.

### 6. Confirmation: the check before writing, the Sent Record, writing and clearing

Confirmation runs as one operation in this order; any failure before step 6 writes nothing to the Budget App:

1. **Admission.** The mode is Plan Open for exactly this plan, the plan's `state` is Awaiting Confirmation, and the
   current time is before `expires_at`. Otherwise: Expired (A4/A5) or refused as a second confirmation.
2. **Local state unchanged.** `coordinator_state.state_version` equals the plan's captured value. Every storage
   operation that changes something a plan depends on (Bank Transactions and their statuses, Review Decisions and
   Transfer Decisions, Account Mappings, Operator Settings, Transfer Descriptions, the stored Budget App copy, Sent
   Records, Transfer Counterparts) raises `state_version` in its own transaction (ADR-002 §3). Different: the plan
   becomes Changed Before Writing (A5).
3. **Configuration unchanged.** The configuration fingerprint equals the captured one. Different: Changed Before
   Writing.
4. **Budget App unchanged.** For each Budget of the plan, the reader is asked for the changes since the plan's
   captured `ChangeCursor`. Any change in that Budget makes the plan Changed Before Writing; the change set is then
   stored (O-4, which marks the Comparison pending) and the Comparison is recomputed and committed (O-5, §3a) before
   the Operator returns to review, so the statuses shown are current (A5). A failed read is A6.
5. **Expiry at the transition to writing (UC-006 step 5, BR-003, A5; NFR-003; B-07).** After the read of step 4, the
   coordinator takes the current time once from `Clock` as `now` and checks that it is before `expires_at`; otherwise
   the plan becomes Expired and nothing is written. The same `now` is passed to O-7, which repeats the expiry check
   inside its transaction; the plan is therefore confirmed only at a moment before its expiry, and writing starts
   right after that commit. No date-dependent eligibility is rechecked: it was decided at preparation (§4, UC-006
   BR-018), and steps 2 to 4 are the only other checks before writing (BR-004).
6. **Sent Record before send.** One storage operation (O-7, ADR-002 §4), for the transaction-send items only: inserts
   a Sent Record with outcome Not Yet Known for every new send; for every retry, updates the reused Sent Record
   (outcome Not Yet Known, `upload_plan_id` of this plan, `sent_at`, date and amount sent) only if its outcome is
   still the one captured at preparation; locks the Account Mappings concerned; sets the plan to Confirmed; and sets
   the mode to Uploading. Clearing items get no Sent Record and change none (B-02). All or nothing. A failure is
   UC-006 A7 (nothing sent, plan discarded, mode Normal).
7. **Write.** `slussen.core.upload.UploadExecutor` sends the transaction-send items of the plan, and only those, per
   Budget Account (UC-006 BR-012), through the write permit of §7. Each reported result is stored per Budget Account
   (O-8): Created and Already Present become Written and Already Present with the Budget App reference; Rejected keeps
   the item available for a retry (A9); a timeout, a lost connection or a cancelled request becomes Unknown (A8,
   NFR-016).
8. **Clear (UC-006 step 10, BR-009, BR-010, A11; B-01, B-02).** Only what the confirmed plan states is cleared:
   - For each `LinkedTransfer` item whose `clear_counterpart` is true and whose result in step 7 was Created, the
     counterpart is resolved by one defined read: `BudgetAppReader.readTransaction(budgetReference, legReference)`
     (ADR-001 §4.2.1 F-3) returns the created leg with its `transferLink`. The counterpart is cleared only when that
     link names a transaction in the item's other Budget Account whose amount is the opposite of the leg's; then
     `markCleared` is sent through the write permit.
   - For each `ClearCounterpart` item, `markCleared` is sent for the Transfer Counterpart it names.
   - Each clearing result is stored by O-14 (ADR-002 §3): a new Transfer Counterpart is recorded with the leg's Sent
     Record, its Budget App reference and `state` Cleared or Uncleared; an existing one gets `state` Cleared and
     `cleared_at`, or stays Uncleared. No clearing ever inserts, resets or changes a Sent Record.
   - When the leg's result was Already Present, Rejected or Unknown, or the read finds no matching link, or
     `markCleared` fails or its answer is lost, nothing is cleared now. The next Comparison reconciles the Transfer
     Counterpart from the stored links and its stored `cleared` value (ADR-002 §3 O-5, S-12), and the next Upload Plan
     of the Budget includes a `ClearCounterpart` item for it while it is uncleared and Upload as Cleared is on (A11).
9. **Compare.** The Comparison of the Budget follows (step 11): O-4 stores the read with its cursor, O-5 commits the
   statuses (A12 keeps the outcomes when the read fails).
10. **Finish.** O-9 records the end and sets the mode to Normal (step 12).

An Unknown outcome is never written as success. Whether it is sent again is decided only at the next preparation, by
the table of §4.

### 7. The write permit

The plug-in's `BudgetAppWriter` (ADR-001 §4.2) is handed by `CompositionRoot` to `slussen.core.upload` only, where it
is wrapped at once in `AdmittedBudgetAppWriter`. Every call of the wrapper takes a `WritePermit` that the coordinator
issues only in mode Uploading, for the plan being written, to `UploadExecutor`; it is valid from the commit of O-7
until O-9, for steps 7 and 8 of §6. Before calling the plug-in, the wrapper checks the permit and that the call
belongs to the plan: a `create` for a transaction-send item of the plan, a `markCleared` for a `ClearCounterpart`
item of the plan, or a `markCleared` for the counterpart resolved in step 8 for a `LinkedTransfer` item of the plan
whose `clear_counterpart` is true. Otherwise it sends nothing and fails the call. A Scheduled Fetch, a facade or any
other path that reaches upload code therefore cannot write: no permit exists outside steps 7 and 8 of a plan the
Operator confirmed. The static rules K-05 and B-04 (ADR-001 §6) keep the raw writer out of other code; this permit is
the runtime guarantee behind them (A-19).

### 8. Process death and restart

At every start, after the start sequence of ADR-002 §6 has finished and before any operation is admitted, the
coordinator reads `coordinator_state`:

- **Plan Open:** the plan becomes Expired (its confirmation screen no longer exists) and the mode Normal. The Operator
  prepares again.
- **Uploading:** nothing is sent again on its own. Sent Records of that plan that are still Not Yet Known become
  Unknown; the next Comparison settles every one whose Import Identifier its Budget Account holds (UC-003 BR-017), and
  the others follow §4 at the next preparation. Clearings not stored as Cleared stay uncleared; the next Comparison
  reconciles their Transfer Counterparts and the next plan clears them (UC-006 A11). The mode becomes Normal.
- **A start that opens a Recovery Check** (ADR-002 §6) resets the mode to Normal in the same way, because
  `coordinator_state` came from older data.
- **Comparison pending** (§3a): the Comparison is recomputed locally and committed (O-5) before preparation is
  admitted.
- A deferred Scheduled Fetch is started once the mode is Normal.

### 9. Tests that prove it

In `:core`, against fakes: every row of the admission table; a Fetch running when preparation is asked; a decision
during Plan Open replacing the plan; a second confirmation refused; expiry at confirmation without the timer; expiry
reached during the read of step 4 (B-07); a plan prepared before a change of date and confirmed after it, holding a
transaction that would be outside the Upload Window and a Lonely Transfer whose grace period would have ended on the
new date, writes exactly its items, while its expiry still applies (UC-006 BR-018); each check of §6 failing; the retry
table of §4 for both values of the reuse-refusal capability; a plan with a retry and a new send together; a plan whose
Transfer has both Transfer Legs present and is cleared in the same Upload, and the same with the leg's answer lost
(B-01); a Transfer with a Transfer Leg that is Needs Review or Possible Import By Other Path, yielding no item from
either side until every such Transfer Leg is decided, and each outcome with the precedence Already Present, Defer,
Import Anyway (S-17); a plan with only `ClearCounterpart` items, which inserts and resets no Sent Record (B-02); a Comparison
interrupted between O-4 and O-5, after which preparation is refused until O-5 is committed (B-04); a Scheduled Fetch
calling upload code and the wrapper refusing it; a restart in each mode. In `:persistence`, the instrumented crash
tests of O-4 to O-9 and O-14 (ADR-002 §3). Device and live evidence that must pass before the first slice that uploads
is listed in ADR-005 §7.

## Consequences

- One operation at a time is simpler to prove than fine-grained locks. The cost: a decision made while a Fetch runs
  waits for it, at most for the Fetch's requests, each bounded by the request timeout (30 seconds by default, an
  Operator Setting, NFR-016).
- Exactly once now has a precise scope (S-05): Slussen never sends again what the Budget App confirmed, and resends an
  unknown outcome on its own only to a Budget App that refuses a reused Import Identifier. With any other Budget App,
  the Operator decides.
- The check before writing is strict: any change in a Budget since preparation, also in a Budget Account the plan
  does not touch, makes the plan Changed Before Writing. That can ask the Operator to prepare again more often than
  strictly needed; it never writes against a changed Budget.
- An Upload Plan never survives a restart. A crash while writing leaves Unknown outcomes that the Comparison settles.
- The Sent Record's `upload_plan_id` names the latest plan that sent it; earlier attempts are in the Decision Journal.
- A plan confirmed a moment before its expiry can still be refused at the last step when its expiry passes during the
  read; the Operator then prepares again. A change of date never refuses it (UC-006 BR-018): a transaction that left
  its window after preparation is written as the plan showed, and the next Comparison gives statuses from its own date.
- Clearing a new Transfer Counterpart in the same Upload costs one extra read of the created leg per such Transfer.
  When that cannot be done safely, the clearing waits for the next plan instead of guessing (UC-006 A11).

## Alternatives

- **Disabling buttons while a plan is open.** Rejected: it does not stop a Fetch already running, a worker, or a
  callback (A-08).
- **Fine-grained locks per Bank Account or per Budget.** Rejected: more parallelism than a household needs, and much
  harder to prove.
- **Inserting a new Sent Record for each attempt.** Rejected: it breaks the entity model's unique Import Identifier per
  Budget Account, which is what makes a retry recognisable (A-01).
- **Keeping an open plan across a restart.** Rejected: the confirmation screen is gone and the plan's inputs may be
  stale; preparing again is cheap.
- **Resuming an interrupted Upload automatically at start.** Rejected: it would write without the Operator present, and
  the outcome of the interrupted sends is unknown.
- **Deciding the Transfer fallback while writing.** Rejected: it would write something other than the confirmed plan
  (A-10).
- **Taking the counterpart reference from the answer to the write.** Rejected (B-01): the answer can be lost, and the
  contract does not promise it; the defined read of the created leg's link is the same source the Comparison uses.
- **Giving clearings a Sent Record.** Rejected (B-02): a clearing sends no Bank Transaction; a Sent Record for it would
  misstate what was sent and could interfere with the protection against duplicates.
- **Committing the Comparison's read and its conclusions in one transaction.** Not chosen: the marker of §3a gives the
  same guarantee while keeping the network read outside any transaction; the conclusions are recomputed from stored
  data alone.

## AIUP guidance

- No AIUP skill covers concurrency, operation admission or crash recovery on a device; the stack plugins assume a
  server with database transactions per request. This ADR is project-made. The use cases name the rules (UC-006
  BR-004, BR-005, BR-007, BR-017); the protocol that enforces them is construction's.
- The technical tables `coordinator_state` and `upload_plan_item` are outside the entity model, like the Budget App
  read cursor, which the Operator's answer to O-P1-03 keeps technical; ADR-002 §2 lists them.

## Review findings addressed

- A-01: §4 (retry reuses the Sent Record and its Import Identifier), §6 step 6 (insert only new sends, update reused
  ones with a compare of the captured outcome, association with the new plan).
- A-08: §1-§3 (one coordinator, admission per mode, deferred Scheduled Fetch, outstanding Consent Request), §4 (the
  immutable write set and captured inputs), §5-§6 (decisions, expiry, duplicate confirmation, the checks before
  writing), §8 (process death).
- A-02 and A-03 (with S-05, S-06): §3 (no plan while a Recovery Check is open), §4 and §6 (automatic resend only
  with the reuse-refusal capability, otherwise the Operator's decision).
- A-10: §4 (fallbacks resolved into the visible plan before confirmation).
- A-19: §7 (runtime write permit behind the static rules).
- B-01: §4 (`clear_counterpart` intent on the `LinkedTransfer` item), §6 step 8 (the counterpart resolved by the defined
  read of the created leg, then cleared within the same Upload; otherwise left for the next plan), §7.
- B-02: §4 (two item families), §6 steps 6 and 8 (clearing items get no Sent Record; results stored by O-14), §7 (the
  permit covers writing and clearing, steps 7-8), §8.
- B-04: §3a (Comparison-pending marker set by O-4, cleared by O-5, blocking preparation; local recompute), §3, §6 step 4,
  §8.
- B-07: §6 step 5 (expiry rechecked with one `now` after the network read and repeated inside O-7), §9. Its
  date-dependent part is reversed by S-18 (see below).
- S-12: §3a and §6 step 8 (reconciliation and clearing use the stored links and Cleared values).
- C-01 (final check, with S-16): §4 (a Transfer with a Transfer Leg under review for a Slussen Unsupported Format
  resemblance yields no item until the Review Decision on that Transfer Leg).
- S-17: §4 (any doubtful Transfer Leg holds its whole Transfer; outcomes and precedence of UC-005 BR-013), §9.
- S-18 (O-P2-16, UC-006 BR-018): §4 (date-dependent eligibility decided once at preparation from the coordinator's
  date), §6 step 5 (only the expiry is checked there; the date recheck is removed), §9 (a plan prepared before a change
  of date and confirmed after it writes exactly its items), Consequences.
