# Use Case: Compare With the Budget App

## Overview

**Use Case ID:** UC-003  
**Use Case Name:** Compare With the Budget App  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App  
**Goal:** Give every stored Bank Transaction a current Transaction Status from what the Budget App holds, so that the Operator sees per transaction whether it is new, already there or needs a decision, without spending any bank access  
**Trigger:** Operator asks Slussen to read the Budget App again; Fetch Transactions, Import a Transaction File and Upload to the Budget App also run this Comparison  
**Status:** Draft  

**Requirements:** [FR-009, FR-019, FR-020, FR-021, FR-023, FR-024, FR-025, FR-026, FR-027, FR-030, FR-031, FR-032, FR-040, FR-043, NFR-002, NFR-004, NFR-015, NFR-016, C-009, C-011, C-012](../requirements.md)

## Preconditions

- Every Budget of the Build Configuration exists in the Budget App.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Operator asks Slussen to read the Budget App again.
2. Slussen reads from the Budget App the transactions of every Budget that a Bank Account is mapped to, including the transactions deleted or merged there since the last read.
3. Slussen compares each stored Bank Transaction of a Bank Account that is not a Retired Account with the transactions of its Budget Account.
4. Slussen gives each Bank Transaction a Transaction Status in the order of BR-001.
5. Slussen detects Transfers among the Bank Transactions when transfer detection is switched on (UC-005 BR-002).
6. Slussen determines the Eligible Transactions (BR-014).
7. Slussen shows for each Budget the Transaction Status of every Bank Transaction and the number of Eligible Transactions.

## Alternative Flows

### A1: Budget App Cannot Be Read

**Trigger:** The Budget App does not answer within the time limit, is unavailable or refuses the read for a Budget (step 2)  
**Flow:**

1. Slussen keeps the previous Transaction Statuses of that Budget's Bank Transactions.
2. Slussen shows that these statuses come from the last successful read, with its time, and that the read failed.
3. Use case continues at step 3 for the other Budgets, or ends when no Budget could be read.

### A2: Currency Mismatch

**Trigger:** The currency of a Bank Account differs from the currency of the Budget it is mapped to (step 3)  
**Flow:**

1. Slussen blocks the Bank Account from upload and makes none of its Bank Transactions eligible.
2. Slussen shows the Blocked Reason "currency mismatch" for the Bank Account.
3. Use case continues at step 3 for the other Bank Accounts.

### A3: Already-Present Decision No Longer Holds

**Trigger:** The Budget App transaction that the Operator chose as already present was deleted, is now in another Budget Account, or has another amount (step 4)  
**Flow:**

1. Slussen gives the Bank Transaction the status Decision No Longer Holds and returns it to the Operator for review (UC-004).
2. Use case continues at step 4 for the next Bank Transaction.

### A4: Transaction Leaves the Upload Window While Waiting

**Trigger:** A Bank Transaction that waits for a Review Decision or a transfer decision is now dated before the Upload Window (step 4)  
**Flow:**

1. Slussen gives the Bank Transaction the status Too Old and keeps it visible with the reason that it can no longer be uploaded.
2. Use case continues at step 4 for the next Bank Transaction.

## Postconditions

### Success Postconditions

- Every stored Bank Transaction of a Bank Account that is not a Retired Account has exactly one Transaction Status from this Comparison.
- Slussen's stored copy of the Budget App's transactions matches what the Budget App reported.
- The number of Eligible Transactions per Budget is up to date.

### Failure Postconditions

- Nothing is written to the Budget App and no Bank is contacted.
- A Transaction Status that was not confirmed by this read is never shown as current.

## Business Rules

### BR-001: Order of the Comparison

The first rule that applies gives the Transaction Status: a Bank Account with a currency mismatch is blocked (BR-013); a transaction outside the Upload Window is Too Old or In The Future (BR-011); one whose import identifier is in the Budget App is Already In Budget App (BR-003); one with a Review Decision follows that decision (BR-009, BR-010, UC-004 BR-001); one Slussen sent that was deleted or merged in the Budget App is Deleted In Budget App or Merged In Budget App (BR-004, BR-005); one without a Stable Reference, or one its Source could not interpret, is Needs Review (BR-012); one that is a Transfer Leg follows the Transfer (UC-005); one that resembles an Import by an Other Import Path is Possible Import By Other Path (BR-007); one that matches a Typed Entry is Match For Typed Entry (BR-006); every other transaction is New.

### BR-002: Import Identifier

Slussen derives the import identifier of each Bank Transaction from its Source, the identity of its Bank Account and its Stable Reference, in the same way the Predecessor App derived it. A transaction the Predecessor App already uploaded therefore has the same import identifier and is recognised as Already In Budget App; replacing the Predecessor App needs no migration step.

### BR-003: Already in the Budget App

A Bank Transaction whose import identifier is held by a transaction of its Budget Account is Already In Budget App and is never sent again.

### BR-004: Deleted in the Budget App

A Bank Transaction that Slussen sent and that was later deleted in the Budget App is Deleted In Budget App. It is not sent again unless the Operator chooses Re-upload (UC-004 BR-003).

### BR-005: Merged in the Budget App

A Bank Transaction that the Budget App merged into a Typed Entry after Slussen sent it is Merged In Budget App and is not sent again.

### BR-006: Match for a Typed Entry

A Bank Transaction for which the Budget Account holds a Typed Entry with the same amount dated at most 10 days before or after it is Match For Typed Entry. It is shown with the number of such Typed Entries and is sent on upload, so that the Budget App pairs the two (C-009).

### BR-007: Possible Import by Another Path

A Bank Transaction for which the Budget Account holds an Import created by an Other Import Path, with the same amount and dated at most 10 days before or after it, is Possible Import By Other Path. It is held back from upload until the Operator decides (UC-004).

### BR-008: Own Uploads Recognised

An Import that carries an import identifier derived by Slussen or by the Predecessor App is never treated as an Import by an Other Import Path.

### BR-009: Already-Present Decision Rechecked

A Review Decision Already Present holds only while the chosen Budget App transaction exists, is in the Budget Account recorded with the decision and has the amount recorded with it. Otherwise the Bank Transaction becomes Decision No Longer Holds.

### BR-010: Deferred Stays Held

A Bank Transaction with the Review Decision Defer stays Deferred across every later Fetch and Comparison, also when the resembling Import disappears, until the Operator changes the decision.

### BR-011: Upload Window

Only a Bank Transaction dated within the Upload Window (the last 7 days by default, a number the Operator can change, UC-010) and not after today is uploaded. A transaction outside the window is shown as Too Old or In The Future and never disappears, also when it leaves the window while waiting for a decision. A Bank Transaction from a Transaction File uses the Upload Window its File Format declares.

### BR-012: No Stable Reference

A Bank Transaction without a Stable Reference, or one its Source could not interpret, is Needs Review. It is never uploaded, merged or dropped without the Operator's decision (UC-004).

### BR-013: Currency Mismatch

A Bank Account whose currency differs from its Budget's currency is blocked from upload with the reason shown, and its balance difference is hidden (UC-008). No amount is ever converted.

### BR-014: Eligible Transactions

A Bank Transaction is an Eligible Transaction when its status is New, Match For Typed Entry or Transfer Leg after the Operator's decisions, it lies in the Upload Window, and its Bank Account is neither blocked nor a Retired Account.

### BR-015: Comparing Never Writes

A Comparison reads the Budget App only. It never writes to the Budget App and never contacts a Bank or Data Provider. Every request to the Budget App gives up after at most 30 seconds.
