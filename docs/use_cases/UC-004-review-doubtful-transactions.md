# Use Case: Review Doubtful Transactions

## Overview

**Use Case ID:** UC-004  
**Use Case Name:** Review Doubtful Transactions  
**Primary Actor:** Operator  
**Goal:** Give every doubtful Bank Transaction the Operator's Review Decision, so that it is uploaded, held back or recognised as already present exactly as the Operator intends and never silently  
**Trigger:** Operator selects a doubtful Bank Transaction to decide on it  
**Status:** Draft  

**Requirements:** [FR-009, FR-022, FR-023, FR-024, FR-028, FR-029, FR-054, FR-066, NFR-002, NFR-010](../requirements.md)

## Preconditions

- The Bank Transactions have Transaction Statuses from a Comparison (UC-003).

## Main Success Scenario

1. Operator selects a Bank Transaction with the status Possible Import By Other Path.
2. Slussen shows the Bank Transaction with its date, payee, amount, Bank Account and the reason it is doubtful, together with the choices Already Present, Import Anyway and Defer.
3. Operator chooses Already Present.
4. Slussen shows the Imports by an Other Import Path that resemble it (UC-003 BR-007), each with its date, payee, amount and Budget Account.
5. Operator chooses the Budget App Transaction that is the same as the Bank Transaction.
6. Slussen stores the Review Decision together with the chosen Budget App Transaction, its Budget Account and its amount.
7. Slussen gives the Bank Transaction the Transaction Status that follows from the decision (BR-006) and updates the number of Eligible Transactions of the Budget.
8. Slussen records in the Decision Journal the Operator's decision together with the status Slussen had proposed.

## Alternative Flows

### A1: Import Anyway

**Trigger:** Operator chooses Import Anyway (step 3)  
**Flow:**

1. Slussen stores the Review Decision Import Anyway.
2. Use case continues at step 7.

### A2: Defer

**Trigger:** Operator chooses Defer (step 3)  
**Flow:**

1. Slussen stores the Review Decision Defer, so that the Bank Transaction stays held back (UC-003 BR-010).
2. Use case continues at step 7.

### A3: Exactly One Resembling Transaction

**Trigger:** Only one Import by an Other Import Path resembles the Bank Transaction (step 4)  
**Flow:**

1. Slussen takes that Budget App Transaction as the chosen one.
2. Use case continues at step 6.

### A4: Transaction Needs Review or Its Transfer Counterpart Changed

**Trigger:** Operator selects a Bank Transaction with the status Needs Review or Transfer Counterpart Changed (step 1)  
**Flow:**

1. Slussen shows the Bank Transaction with the reason it Needs Review and the choices Import Anyway and Defer.
2. Use case continues at step 3, where the Operator chooses one of these two.

### A5: Re-upload a Deleted Transaction

**Trigger:** Operator selects a Bank Transaction with the status Deleted In Budget App, Merged In Budget App or Decision No Longer Holds (step 1)  
**Flow:**

1. Slussen shows the Bank Transaction with its status and the choice Re-upload, which is off.
2. Operator switches Re-upload on.
3. Slussen stores the Review Decision Re-upload.
4. Use case continues at step 7.

### A6: Change or Clear an Earlier Decision

**Trigger:** Operator selects a Bank Transaction that already has a Review Decision (step 1)  
**Flow:**

1. Slussen shows the current Review Decision and the choices that apply to the Bank Transaction, including clearing the decision.
2. Operator chooses another decision or clears it.
3. Slussen replaces or removes the Review Decision.
4. Use case continues at step 7.

### A7: Transaction Already Uploaded

**Trigger:** The selected Bank Transaction has been uploaded (step 1)  
**Flow:**

1. Slussen shows the Review Decision as locked and offers no choice.
2. Use case ends.

### A8: Upload Plan Awaiting Confirmation

**Trigger:** An Upload Plan awaits confirmation when the decision is stored (step 7)  
**Flow:**

1. Slussen replaces the Upload Plan as UC-006 A3 describes.
2. Use case continues at step 8.

## Postconditions

### Success Postconditions

- The Bank Transaction has the Operator's Review Decision, and its Transaction Status follows it.
- The Review Decision is kept across later Fetches and Comparisons and across a reinstall of the app.
- The decision is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- Nothing is written to the Budget App.
- An earlier Review Decision stays unchanged unless the Operator replaced it.
- No doubtful Bank Transaction becomes eligible without the Operator's decision.

## Business Rules

### BR-001: Choices per Transaction Status

Possible Import By Other Path offers Already Present, Import Anyway and Defer. Decision No Longer Holds offers Already Present, Import Anyway, Defer and Re-upload. Needs Review and Transfer Counterpart Changed offer Import Anyway and Defer. Deleted In Budget App and Merged In Budget App offer Re-upload. Deferred offers changing or clearing the decision.

### BR-002: Already Present Names the Transaction

An Already Present decision always names one specific Budget App Transaction. When several resemble the Bank Transaction, the Operator chooses it from a list that shows date, payee, amount and Budget Account; its Budget Account and amount are recorded so that UC-003 BR-009 can recheck the decision.

### BR-003: Re-upload Is Deliberate

Re-upload is chosen per Bank Transaction and is off by default. A re-uploaded transaction is sent as a new transaction under a new Import Identifier derived from its own one and the number of earlier re-uploads (UC-003 BR-002), and only while it lies inside the Upload Window (UC-003 BR-011); once uploaded, it counts as Already In Budget App.

### BR-004: Decisions Change Until Upload

A Review Decision can be changed or cleared any number of times until its Bank Transaction is uploaded. From then on it is locked.

### BR-005: Every Decision Is Journaled

Every Review Decision, change and clearing is recorded in the Decision Journal together with the Transaction Status Slussen had given. Recording never delays or blocks the decision (UC-011 BR-002).

### BR-006: Status After a Decision

Already Present gives the status Already In Budget App while the decision holds (UC-003 BR-009). Import Anyway and Re-upload give the status New, so that the Bank Transaction is eligible as a new transaction; for a transaction without a Stable Reference, its Import Identifier comes from UC-003 BR-002. Defer gives the status Deferred. Clearing a decision gives the status the Comparison gives without a decision (UC-003 BR-001).
