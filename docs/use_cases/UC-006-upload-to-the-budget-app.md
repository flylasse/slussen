# Use Case: Upload to the Budget App

## Overview

**Use Case ID:** UC-006  
**Use Case Name:** Upload to the Budget App  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App  
**Goal:** Write exactly the confirmed Eligible Transactions of one Budget, or of one Bank Account, to the Budget App, each exactly once, and know truthfully what was written  
**Trigger:** Operator asks to upload one Budget or one Bank Account  
**Status:** Approved  

**Requirements:** [FR-030, FR-031, FR-033, FR-034, FR-035, FR-036, FR-037, FR-038, FR-039, FR-041, FR-042, FR-044, FR-047, FR-051, FR-052, FR-053, FR-055, FR-064, FR-066, FR-074, FR-075, FR-077, FR-090, FR-094, FR-095, FR-100, NFR-001, NFR-002, NFR-003, NFR-004, NFR-010, NFR-015, NFR-016, NFR-017, C-009, C-011, C-012](../requirements.md)

## Preconditions

- The Bank Transactions have Transaction Statuses from a Comparison (UC-003).
- Every Bank Account in the chosen Budget has an Account Mapping.

## Main Success Scenario

1. Operator asks to upload one Budget, or one Bank Account of a Budget.
2. Slussen prepares an Upload Plan from the Eligible Transactions in that scope on the current date (BR-018) and, while Upload as Cleared is on, from the Transfer Counterparts to be marked Cleared (BR-016), records it in the Decision Journal (UC-011 BR-001), and pauses other work while the plan awaits confirmation (BR-005).
3. Slussen shows the Upload Plan: the Budget, the Budget Accounts, the number of transactions to create and to send as a match for a Typed Entry, the total inflow, the total outflow, whether the transactions arrive Cleared, every Transfer Counterpart that will be marked Cleared, and when the plan expires.
4. Operator confirms the Upload Plan.
5. Slussen checks that the Upload Plan has not expired and reads the Budget App again to check that the Budget App, the Bank Transactions and the configuration are unchanged since the plan was prepared.
6. Slussen records each Eligible Transaction of the plan in the Sent Record with its Import Identifier, its Budget Account and the outcome not yet known, and records the first send through the Account Mapping of every Bank Account concerned where none is recorded yet (UC-009 BR-003).
7. Slussen sends the Eligible Transactions to the Budget App, each with its Import Identifier, marked Cleared while Upload as Cleared is on and always waiting for the Operator's approval (BR-008), with payee and memo shortened where the Budget App requires it (BR-015), and each Transfer as one linked transfer through one Transfer Leg.
8. Budget App reports for each transaction that it was created or that it already held it.
9. Slussen records the reported outcome of each transaction in the Sent Record.
10. Slussen marks Cleared every Transfer Counterpart that the Upload Plan states, which is none while Upload as Cleared is off (BR-016).
11. Slussen compares the Bank Transactions of the uploaded Budget with the Budget App again (UC-003).
12. Slussen records the Upload with the outcome of each transaction in the Decision Journal and ends the pause.
13. Slussen shows per Budget Account which transactions were written, which were not and which have an unknown outcome.

## Alternative Flows

### A1: Nothing to Upload

**Trigger:** The scope holds no Eligible Transaction and no Transfer Counterpart to be marked Cleared (step 2)  
**Flow:**

1. Slussen shows that there is nothing to upload and prepares no Upload Plan.
2. Use case ends.

### A2: Operator Cancels or Leaves the Upload Plan

**Trigger:** Operator cancels instead of confirming, or leaves the Upload Plan's screen before confirming (step 4)  
**Flow:**

1. Slussen writes nothing, discards the Upload Plan and ends the pause (BR-005, BR-019).
2. Use case ends.

### A3: Decision Changed While the Plan Awaits Confirmation

**Trigger:** Operator changes a Review Decision or a Transfer Decision while the Upload Plan awaits confirmation (step 4)  
**Flow:**

1. Slussen replaces the Upload Plan with a new one prepared from the changed decisions, with a new expiry.
2. Use case continues at step 3.

### A4: Plan Expires Before Confirmation

**Trigger:** The Upload Plan reaches its expiry while it awaits confirmation (step 4)  
**Flow:**

1. Slussen discards the Upload Plan, ends the pause and shows that the plan expired.
2. Use case ends.

### A5: Plan Expired or Something Changed

**Trigger:** The Upload Plan has expired, or the Budget App, the Bank Transactions or the configuration changed since it was prepared (step 5)  
**Flow:**

1. Slussen writes nothing, discards the Upload Plan and ends the pause.
2. Slussen shows why the plan no longer holds and returns the Operator to the review of the Budget.
3. Use case ends.

### A6: Budget App Cannot Be Read or Refuses Slussen's Credentials

**Trigger:** The Budget App does not answer, is unavailable, or answers that the service credentials the Operator entered are not valid or not allowed (step 5)  
**Flow:**

1. Slussen writes nothing, discards the Upload Plan and ends the pause.
2. Slussen shows that the Budget App could not be read, or the Blocked Reason "credentials refused" with the action that removes it: enter the credentials again (UC-014).
3. Use case ends.

### A7: Sent Record Cannot Be Stored

**Trigger:** Slussen cannot store the Sent Record of the plan completely (step 6)  
**Flow:**

1. Slussen sends nothing, discards the Upload Plan and ends the pause.
2. Slussen shows that the Upload did not start and why.
3. Use case ends.

### A8: Outcome Unknown

**Trigger:** The Budget App does not answer within the time limit of NFR-016 or the connection is lost before it reports the outcome (step 8)  
**Flow:**

1. Slussen records the outcome Unknown in the Sent Record for the transactions concerned and shows them as not confirmed, never as written.
2. Slussen keeps these transactions available for Upload under the same Import Identifiers and for the same Budget Accounts when the Budget App refuses a reused Import Identifier, and otherwise holds them for the Operator's Review Decision (BR-007).
3. Use case continues at step 11.

### A9: Budget App Rejects Transactions

**Trigger:** The Budget App rejects the transactions of a Budget Account (step 8)  
**Flow:**

1. Slussen records the outcome Rejected in the Sent Record for these transactions and keeps them available for Upload, under the same Import Identifiers and for the Budget Account of the current Account Mapping, also when the Operator moves the Account Mapping before the next Upload (BR-020).
2. Slussen shows the rejection with the Budget App's reason.
3. Use case continues at step 9 for the transactions of the other Budget Accounts.

### A10: Budget App Cannot Link a Transfer

**Trigger:** The Budget App offers no way to link a Transfer to the other Budget Account (step 7)  
**Flow:**

1. Slussen sends the Transfer Leg as an ordinary transaction.
2. Slussen keeps the other Transfer Leg as an ordinary Bank Transaction, so that it is uploaded too and not suppressed.
3. Use case continues at step 8.

### A11: Marking a Transfer Counterpart Cleared Fails

**Trigger:** While Upload as Cleared is on, the Budget App does not mark a Transfer Counterpart Cleared (step 10)  
**Flow:**

1. Slussen leaves the Transfer Counterpart uncleared, shows that, and includes it again in the next Upload Plan of the Budget.
2. Use case continues at step 11.

### A12: Comparison After the Upload Fails

**Trigger:** The Budget App cannot be read after the Upload (step 11)  
**Flow:**

1. Slussen keeps the reported outcomes of the Upload and shows that the Transaction Statuses will be updated at the next Comparison.
2. Use case continues at step 12.

### A13: Recovery Check Open

**Trigger:** A Recovery Check is open, because Slussen started from its last good copy of its Decision-Critical Data, from data restored from a device backup or from the empty data of a new installation, and the Operator has not confirmed it yet (step 2)  
**Flow:**

1. Slussen prepares no Upload Plan.
2. Slussen shows why: transactions sent after that data was saved are protected against a duplicate only by the Budget App's refusal of a reused Import Identifier and by the Comparison; and it offers the Recovery Check (UC-008).
3. Use case ends.

## Postconditions

### Success Postconditions

- Every confirmed Eligible Transaction exists exactly once in its Budget Account in the Budget App, marked Cleared exactly when Upload as Cleared was on, and waiting for the Operator's approval.
- The Sent Record holds every transaction sent, with its Import Identifier, its Budget Account and its confirmed outcome.
- For every Account Mapping through which a transaction was sent, the time of its first send is stored, so that it is moved or removed only with the Operator's confirmation (UC-009 BR-003).
- Every Transfer Counterpart the plan stated is marked Cleared.
- The Upload Plan and the Upload are recorded in the Decision Journal, unless the Decision Journal is switched off.
- Other work is available again.

### Failure Postconditions

- Nothing is written without a confirmed Upload Plan that has not expired and still matches the Budget App, the Bank Transactions and the configuration.
- Nothing is sent that is not first recorded in the Sent Record.
- No transaction is shown as written unless the Budget App confirmed it.
- No Bank Transaction whose Sent Record shows it written or already present is sent again, except by a Re-upload decision.
- No existing Budget App Transaction is changed, except that a Transfer Counterpart created by Slussen's own Upload is marked Cleared while Upload as Cleared is on.
- No Upload Plan stays open after the use case ends, so other work is available again.

## Business Rules

### BR-001: Confirmation Before Every Write

Slussen writes to the Budget App only after the Operator confirms an Upload Plan. Nothing is uploaded automatically, in the background or on a schedule; a Scheduled Fetch never prepares an Upload Plan (UC-001 BR-013).

### BR-002: Scope of an Upload Plan

An Upload Plan covers one Budget or one Bank Account of a Budget and states the Budget, the Budget Accounts, the number of transactions to create and to send as a match, the total inflow and total outflow, every Transfer Counterpart it marks Cleared, and its expiry.

### BR-003: Plan Lifetime

An Upload Plan expires when the Upload Plan lifetime has passed after it was prepared (10 minutes by default, a number the Operator can change, UC-010). Confirming an expired plan writes nothing.

### BR-004: Write Exactly What Was Confirmed

Immediately before writing, Slussen checks the Budget App, the Bank Transactions and the configuration again. When any of them changed since the plan was prepared, nothing is written, and Slussen shows why (A5). A change of date is not such a change (BR-018).

### BR-005: Other Work Pauses While a Plan Awaits Confirmation or Is Being Written

At most one Upload Plan awaits confirmation at a time. From the moment it is prepared until it is cancelled, discarded or expires, or, once confirmed, until its Upload is written, also after the Operator has left its screen (BR-019), fetching (a Scheduled Fetch that falls due starts when the pause ends), file imports, reading the Budget App and all setup are unavailable: entering, replacing or removing service credentials (UC-014), choosing Budgets and adding Bank Accounts (UC-013), Account Mapping changes (UC-009), granting, renewing or revoking a Bank Consent (UC-007) and changes to Operator Settings (UC-010). While the plan awaits confirmation, only confirming, cancelling and changing Review Decisions or Transfer Decisions are allowed; while its Upload is being written, Slussen shows that setup is paused until the Upload is written. Looking at the import status stays available.

### BR-006: Exactly Once Through the Sent Record

Slussen records each transaction in the Sent Record before it is sent, and never sends again a Bank Transaction whose Sent Record shows that the Budget App created it or already held it, even after a retry, timeout, crash, reinstall or re-mapping of a Bank Account. This is the guarantee Slussen gives on its own. The one case it cannot settle alone is a send whose outcome is unknown, because a request that never arrived and one whose answer was lost leave the same Sent Record; such a send follows BR-007, and sending it again is safe only because the Budget App refuses a reused Import Identifier.

### BR-007: Unknown Outcomes Are Resent Only to a Budget App That Refuses Duplicates

A transaction with an unknown outcome is sent again automatically, as an Eligible Transaction of a later Upload Plan, only when the part of Slussen for the Budget App declares that the Budget App refuses a transaction whose Import Identifier it already holds in the Budget Account, as YNAB does (C-009, NFR-015). It is then sent under the same Import Identifier and to the same Budget Account recorded in its Sent Record, so that the Budget App refuses it if it already arrived. With a Budget App that does not refuse a reused Import Identifier, Slussen never sends it again on its own; it waits for the Operator's Review Decision (UC-004 BR-008). In both cases a later Comparison settles the outcome when the Budget App holds the transaction (UC-003 BR-017).

### BR-008: Created Waiting for Approval, Cleared by Setting

Every transaction Slussen creates in the Budget App arrives waiting for the Operator's approval, and marked Cleared while Upload as Cleared is on (on by default, UC-010), using the Budget App's own markers (C-009). While the setting is off, or when the Budget App does not support the Cleared marker, it arrives uncleared.

### BR-009: A Transfer Is Written Through One Transfer Leg

A Transfer is written as one linked transfer through exactly one Transfer Leg, the outflow side when both Transfer Legs are present, never as two separate transactions. When both Transfer Legs are present and Upload as Cleared is on, the Transfer Counterpart is marked Cleared in the same Upload; for a Lonely Transfer, or while the setting is off, it stays uncleared.

### BR-010: Settling a Transfer Counterpart

When the Bank Transaction of the other side of an uploaded Lonely Transfer arrives, Slussen does not upload it (status Arrived As Transfer Counterpart), and, while Upload as Cleared is on, the next Upload Plan marks the Transfer Counterpart Cleared. While the setting is off, the counterpart stays uncleared and clearing is left to the Operator in the Budget App.

### BR-011: Only Own Transfer Counterparts Are Changed

Marking a Transfer Counterpart Cleared is the only change Slussen makes to an existing Budget App Transaction, only while Upload as Cleared is on, and only for a Transfer Counterpart created by a Transfer Slussen uploaded. While the setting is off, Slussen changes no existing Budget App Transaction.

### BR-012: Failures Stay Within One Budget Account

A rejection or an unknown outcome for one Budget Account leaves the transactions written to the other Budget Accounts of the same Upload unaffected.

### BR-013: Updated Statuses After Upload

Right after an Upload, Slussen compares the uploaded Budget with the Budget App again, so that uploaded transactions are not offered for Upload again.

### BR-014: Sent Record Kept Across a Reinstall, Restore or Upgrade

The Sent Record is included in device backups, restored after a reinstall and kept on an upgrade of the same build, together with the stored Bank Transactions, the Bank Account identifications and Account Mappings (UC-009 BR-006) and the Review Decisions and Transfer Decisions. Bank Consents with their Bank Sessions and the service credentials are not restored (UC-007 BR-005, UC-014 BR-004). After a restore, Uploads wait for the Recovery Check (BR-017).

### BR-015: Long Payee and Memo

When a payee or memo is longer than the Budget App accepts (C-009), Slussen sends it shortened to the accepted length. The stored Bank Transaction keeps the full text.

### BR-016: Upload as Cleared Applies at Preparation

An Upload Plan follows Upload as Cleared as it stands when the plan is prepared, and states whether its transactions arrive Cleared. While the setting is off, the plan lists no Transfer Counterpart to mark Cleared. Switching the setting changes only what later Upload Plans write and nothing already in the Budget App.

### BR-017: Uploads Wait for the Recovery Check

While a Recovery Check is open, Slussen prepares no Upload Plan. A Recovery Check opens whenever Slussen starts from data that may lack what it last recorded: its last good copy after its Decision-Critical Data could not be read, data restored from a device backup, or the empty data of a new installation. It closes only when a Comparison has run after it opened (UC-003 BR-019) and the Operator has confirmed it (UC-008 BR-009). A warning alone is not enough: Sent Records written after that data was saved are missing, and the transactions they covered are then protected against a duplicate only by the Budget App's refusal of a reused Import Identifier and by the Comparison. Damage to data that is not Decision-Critical Data, such as the Decision Journal, opens no Recovery Check (UC-010 BR-004).

### BR-018: Eligibility Is Fixed When the Plan Is Prepared

Which transactions an Upload Plan holds is decided once, when the plan is prepared, from the current date at that moment: the Upload Window and the file upload window (UC-003 BR-011, UC-002 BR-004), the Start Date of each Bank Account (UC-003 BR-018) and the Lonely Transfer grace period (UC-005 BR-005). While the plan awaits confirmation, a change of date changes nothing in it, and confirming writes exactly the transactions the plan showed. The plan is protected by its visible expiry (BR-003) and by the check that the Budget App, the Bank Transactions and the configuration are unchanged (BR-004). The next Comparison after the plan is written, cancelled, discarded or expired gives the Transaction Statuses from the date of that Comparison (UC-003).

### BR-019: An Upload Plan Exists Only While It Is Shown

An Upload Plan exists only while the Operator is shown it. When the Operator leaves it without confirming, Slussen discards it as on cancelling (A2): nothing is written and the pause ends. A discarded, cancelled or expired Upload Plan is never shown or confirmed again. Every request for an Upload Plan prepares a new one from the current data. Once the Operator has confirmed the Upload Plan, leaving its screen is not cancelling and changes nothing: Slussen finishes the Upload (steps 5 to 12, including the alternative flows that write nothing) and shows its outcome (step 13) when the Operator next opens the Budget or the outcome.

### BR-020: A Rejected Transaction Follows the Current Account Mapping

A transaction the Budget App rejected was not written, so the Budget App holds it in no Budget Account. It stays available for Upload and is sent again, under the same Import Identifier, to the Budget Account of its Bank Account's current Account Mapping, also when the Operator has moved the Account Mapping since the rejection; moving the mapping is often what fixes the rejection. Its Sent Record then names the Budget Account it was sent to. While the Bank Account has no Account Mapping, it is not offered for Upload (UC-009 A11). This differs from a transaction whose outcome is unknown, which keeps the Budget Account recorded in its Sent Record (BR-007, UC-009 BR-003), and from one the Budget App created or already held, which is never sent again because of a move (BR-006, UC-003 BR-021).
