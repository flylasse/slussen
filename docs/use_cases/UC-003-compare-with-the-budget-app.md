# Use Case: Compare With the Budget App

## Overview

**Use Case ID:** UC-003  
**Use Case Name:** Compare With the Budget App  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App  
**Goal:** Give every stored Bank Transaction a current Transaction Status from what the Budget App holds, so that the Operator sees per transaction whether it is new, already there or needs a decision, without spending any Bank access  
**Trigger:** Operator asks Slussen to read the Budget App again, or Fetch Transactions, Import a Transaction File or Upload to the Budget App reaches its Comparison step  
**Status:** Approved  

**Requirements:** [FR-009, FR-019, FR-020, FR-021, FR-023, FR-024, FR-025, FR-026, FR-027, FR-030, FR-031, FR-032, FR-038, FR-040, FR-046, FR-061, FR-066, FR-074, FR-075, FR-094, FR-095, NFR-002, NFR-004, NFR-015, NFR-016, NFR-020, C-009, C-011, C-012](../requirements.md)

## Preconditions

- Every Budget of the Build Configuration exists in the Budget App.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Slussen reads from the Budget App, for every Budget that a Bank Account is mapped to, the transactions of the Budget App Read Period (BR-016), the transactions deleted or merged there since the last read, and the balance of each Budget Account, and stores them as its copy of the Budget App Transactions.
2. Slussen compares each stored Bank Transaction of a Bank Account that is not a Retired Account with the Budget App Transactions of its Budget Account and with the Sent Record.
3. Slussen gives each Bank Transaction a Transaction Status in the order of BR-001.
4. Slussen detects Transfers among the Bank Transactions (UC-005 BR-001, UC-005 BR-002) and gives their Transfer Legs their status (UC-005 BR-010).
5. Slussen determines the Eligible Transactions (BR-014).
6. Slussen records in the Decision Journal every Transaction Status that changed and what the Budget App reported for each transaction Slussen sent (UC-011 BR-001).
7. Slussen shows for each Budget the Transaction Status of every Bank Transaction, the number of Eligible Transactions and the time of the read, and, while a Recovery Check is open and every Budget was read, records that this Comparison ran (BR-019).

## Alternative Flows

### A1: Budget App Cannot Be Read

**Trigger:** The Budget App does not answer within the time limit of NFR-016, is unavailable or refuses the read for a Budget (step 1)  
**Flow:**

1. Slussen keeps the previous Transaction Statuses and the stored Budget App Transactions of that Budget.
2. Slussen shows that these statuses come from the last successful read, with its time, and that the read failed.
3. Use case continues at step 2 for the other Budgets, or ends when no Budget could be read.

### A2: Budget App Refuses Slussen's Credentials

**Trigger:** The Budget App answers that the service credentials of the Build Configuration are not valid or not allowed (step 1)  
**Flow:**

1. Slussen keeps the previous Transaction Statuses and the stored Budget App Transactions of every Budget.
2. Slussen shows the Blocked Reason "configuration error" with the action that removes it: correct the Build Configuration (UC-012).
3. Use case ends.

### A3: Currency Mismatch

**Trigger:** The currency of a Bank Account differs from the currency of the Budget it is mapped to (step 2)  
**Flow:**

1. Slussen blocks the Bank Account from Upload and makes none of its Bank Transactions eligible.
2. Slussen shows the Blocked Reason "currency mismatch" for the Bank Account.
3. Use case continues at step 2 for the other Bank Accounts.

### A4: Already-Present Decision No Longer Holds

**Trigger:** The Budget App Transaction that the Operator chose as already present was deleted, is now in another Budget Account, or has another amount (step 3)  
**Flow:**

1. Slussen gives the Bank Transaction the status Decision No Longer Holds and returns it to the Operator for review (UC-004).
2. Use case continues at step 3 for the next Bank Transaction.

### A5: Transaction Leaves the Upload Window While Waiting

**Trigger:** A Bank Transaction that waits for a Review Decision or a Transfer Decision is now dated before the Upload Window, or for a transaction from a Transaction File before the file upload window (step 3)  
**Flow:**

1. Slussen gives the Bank Transaction the status Too Old and keeps it visible with the reason that it can no longer be uploaded.
2. Use case continues at step 3 for the next Bank Transaction.

### A6: Outcome of an Earlier Send Found

**Trigger:** A Bank Transaction has a Sent Record whose outcome is not yet known or unknown (step 2)  
**Flow:**

1. Slussen applies BR-017: when its Budget Account holds the Import Identifier, the Sent Record becomes Written and the Bank Transaction Already In Budget App; otherwise it stays Outcome Unknown, eligible or waiting for the Operator's Review Decision as BR-017 says.
2. Use case continues at step 3 for the next Bank Transaction.

### A7: Transaction Before the Start Date

**Trigger:** A Bank Transaction that has not been sent is dated before the Start Date of its Bank Account (step 3)  
**Flow:**

1. Slussen gives the Bank Transaction the status Before Start Date and keeps it visible with the reason that it lies before the Start Date (BR-018).
2. Use case continues at step 3 for the next Bank Transaction.

## Postconditions

### Success Postconditions

- Every stored Bank Transaction of a Bank Account that is not a Retired Account has exactly one Transaction Status from this Comparison.
- Slussen's stored copy of the Budget App Transactions and Budget Account balances matches what the Budget App reported, with the time of the read.
- The number of Eligible Transactions per Budget is up to date.

### Failure Postconditions

- Nothing is written to the Budget App and no Bank is contacted.
- A Transaction Status that was not confirmed by this read is never shown as current.

## Business Rules

### BR-001: Order of the Comparison

The first rule that applies gives the Transaction Status: a Bank Account with a currency mismatch is blocked (BR-013); a transaction whose Import Identifier, or the Import Identifier of a Re-upload of it, is held in its Budget Account is Already In Budget App (BR-003); one Slussen sent that was deleted or merged in the Budget App is Deleted In Budget App or Merged In Budget App (BR-004, BR-005), unless the Operator chose Re-upload; one whose earlier send has an unknown outcome is Outcome Unknown (BR-017); one dated before the Start Date of its Bank Account is Before Start Date (BR-018); one dated outside the Upload Window is Too Old or In The Future (BR-011); one with a Review Decision follows that decision (BR-009, BR-010, UC-004 BR-006); one without a Stable Reference is Needs Review (BR-012); one that is a Transfer Leg follows the Transfer (UC-005 BR-010); one that resembles an Import by an Other Import Path is Possible Import By Other Path (BR-007); one that matches a Typed Entry is Match For Typed Entry (BR-006); every other transaction is New.

### BR-002: Import Identifier

Slussen derives the Import Identifier of each Bank Transaction itself, the same way every time, from the Source Key of its Data Provider or File Format and its Stable Reference. A Bank Transaction without a Stable Reference takes, in place of the Stable Reference, its date, amount, payee and memo together with its position among the identical transactions of its Bank Account, so that each such transaction keeps its own Import Identifier when the Operator decides to import it. A Re-upload adds the number of the Re-upload (UC-004 BR-003). The same inputs always give the same Import Identifier. Different inputs give the same one at most as rarely as two random UUIDs do (NFR-020), and such a collision is still detected when a new Bank Transaction is stored: from a Data Provider, the new transaction is kept as an Unreadable Transaction with the reason "Import Identifier collision" (UC-001 BR-016); from a Transaction File, the whole file is rejected with that reason (UC-002 A6). Every Import Identifier begins with a fixed marker that identifies Slussen and fits the length the Budget App accepts (C-009). Slussen does not take over the Import Identifiers of the Predecessor App or of any Other Import Path.

### BR-003: Already in the Budget App

A Bank Transaction whose Import Identifier is held by a transaction of its Budget Account is Already In Budget App and is never sent again.

### BR-004: Deleted in the Budget App

A Bank Transaction that Slussen sent and that was later deleted in the Budget App is Deleted In Budget App. It is not sent again unless the Operator chooses Re-upload (UC-004 BR-003).

### BR-005: Merged in the Budget App

A Bank Transaction that the Budget App merged into a Typed Entry after Slussen sent it is Merged In Budget App and is not sent again.

### BR-006: Match for a Typed Entry

A Bank Transaction for which the Budget Account holds a Typed Entry with the same amount, dated within the Budget App's Matching Date Window before or after it, is Match For Typed Entry. It is shown with the number of such Typed Entries and is sent on Upload, so that the Budget App pairs the two (C-009).

### BR-007: Possible Import by Another Path

A Bank Transaction for which the Budget Account holds an Import created by an Other Import Path, with the same amount and dated within the Matching Date Window before or after it, is Possible Import By Other Path. Such Imports are the ones that resemble it. It is held back from Upload until the Operator decides (UC-004). This is what protects a Bank Account that another import path, such as the Predecessor App, fed before Slussen took it over: a transaction that looks like one that path already sent is never written without the Operator's decision.

### BR-008: Own Uploads Recognised

An Import whose Import Identifier begins with Slussen's marker (BR-002) is never treated as an Import by an Other Import Path. An Import of the Predecessor App or of any other importer is an Import by an Other Import Path.

### BR-009: Already-Present Decision Rechecked

A Review Decision Already Present holds only while the chosen Budget App Transaction exists, is in the Budget Account recorded with the decision and has the amount recorded with it. Otherwise the Bank Transaction becomes Decision No Longer Holds.

### BR-010: Deferred Stays Held

A Bank Transaction with the Review Decision Defer stays Deferred across every later Fetch and Comparison, also when the resembling Import disappears, until the Operator changes the decision.

### BR-011: Upload Window

Only a Bank Transaction dated within the Upload Window (the last 7 days by default, a number the Operator can change, UC-010) and not after today is uploaded. A transaction outside the window is shown as Too Old or In The Future and never disappears, also when it leaves the window while waiting for a decision. A Bank Transaction from a Transaction File uses the file upload window instead (the last 60 days by default, a number the Operator can change, UC-002 BR-004). A Re-upload is possible only inside the window. The window never hides what is already known about a sent transaction: Already In Budget App, Deleted In Budget App, Merged In Budget App and Outcome Unknown are decided before it.

### BR-012: No Stable Reference or Unreadable

A Bank Transaction without a Stable Reference is Needs Review. It is never uploaded, merged or dropped without the Operator's decision (UC-004). An Unreadable Transaction (UC-001 BR-016) is Needs Review too, but is never uploaded at all; the Operator can only defer it (UC-004 BR-007).

### BR-013: Currency Mismatch

A Bank Account whose currency differs from its Budget's currency is blocked from Upload with the reason shown, and its balance difference is hidden (UC-008). No amount is ever converted.

### BR-014: Eligible Transactions

A Bank Transaction is an Eligible Transaction when its status is New, Match For Typed Entry, Outcome Unknown under the conditions of BR-017, or Transfer Leg for the Transfer Leg that writes its Transfer (UC-006 BR-009), it lies in the Upload Window that applies to it (BR-011) and not before the Start Date of its Bank Account, and its Bank Account is neither blocked nor a Retired Account.

### BR-015: Comparing Never Writes

A Comparison reads the Budget App only. It never writes to the Budget App and never contacts a Bank or Data Provider.

### BR-016: Budget App Read Period

The first read of a Budget covers the transactions dated from today back by the longest of the Fetch period (UC-001 BR-009), the Upload Window and the file upload window, plus the Matching Date Window. Every later read takes all changes since the previous read, including deletions and merges, so that a transaction Slussen sent is always found again.

### BR-017: Unknown Outcome Resolved

At every Comparison, a Sent Record whose outcome is not yet known or unknown is checked against its Budget Account: when the Budget Account holds its Import Identifier, the outcome becomes Written with the Budget App's identifier of the transaction, and the Bank Transaction is Already In Budget App. Otherwise the Bank Transaction is Outcome Unknown. It stays eligible under the same Import Identifier and for the same Budget Account only when the Budget App refuses a reused Import Identifier (UC-006 BR-007). Otherwise it waits for the Operator's Review Decision (UC-004 BR-008): Import Anyway makes it eligible under the same Import Identifier and for the same Budget Account, and Defer holds it back.

### BR-018: Start Date

A Bank Transaction that has not been sent and is dated before the Start Date that the Build Configuration sets for its Bank Account is Before Start Date. It stays visible with that reason and is never offered for Upload, also not through a Review Decision. A Bank Account without a Start Date has no such limit; its transactions are bounded by the Upload Window only.

### BR-019: Comparison for a Recovery Check

While a Recovery Check is open (UC-006 BR-017), a Comparison that read every Budget with a mapped Bank Account records that it ran, so that the Operator can then confirm the Recovery Check (UC-008 BR-009). A Comparison in which a Budget could not be read records nothing for the Recovery Check.
