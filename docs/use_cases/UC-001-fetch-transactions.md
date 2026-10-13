# Use Case: Fetch Transactions

## Overview

**Use Case ID:** UC-001  
**Use Case Name:** Fetch Transactions  
**Primary Actor:** Operator  
**Secondary Actors:** Data Provider  
**Goal:** Bring the latest booked Bank Transactions and Account Balances of the Bank Accounts that one Data Provider reads into Slussen, compared with the Budget App, while spending no more bank access than needed  
**Trigger:** Operator starts a Fetch of a Data Provider, or of all Data Providers at once  
**Status:** Draft  

**Requirements:** [FR-001, FR-002, FR-004, FR-005, FR-008, FR-013, FR-014, FR-015, FR-016, FR-017, FR-018, FR-058, NFR-002, NFR-004, NFR-007, NFR-008, NFR-016, C-006, C-007, C-012](../requirements.md)

## Preconditions

- The Data Provider and the Bank Accounts it reads are part of the Build Configuration, each Bank Account with an Account Mapping.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Operator starts a Fetch of a Data Provider.
2. Slussen checks that the Data Provider has not asked Slussen to wait, is not within its Fetch Cooldown and has not reached its daily request limit.
3. Slussen selects the Bank Accounts to read: every Bank Account of the Data Provider that is not a Retired Account and whose Bank has a valid Bank Consent when the Data Provider needs one.
4. Slussen asks the Data Provider for the transactions and the balance of each selected Bank Account for the fetch period.
5. Data Provider delivers the transactions and the Account Balance of each selected Bank Account.
6. Slussen identifies each delivered Bank Account with certainty as one Bank Account of the Build Configuration.
7. Slussen keeps the booked Bank Transactions with an amount other than zero and leaves out the others.
8. Slussen stores the Bank Transactions and Account Balances of each identified Bank Account, including the balance the Data Provider reports for a Bank Account whose transactions come from Transaction Files.
9. Slussen compares the stored Bank Transactions with the Budget App (UC-003).
10. Slussen records the Fetch with its outcome for each Bank Account and shows the Source Health, the Freshness and the Transaction Statuses of each Bank Account.

## Alternative Flows

### A1: Data Provider Asked Slussen to Wait

**Trigger:** A Back-off that the Data Provider or a Bank asked for has not ended yet (step 2)  
**Flow:**

1. Slussen does not contact the Data Provider, or the Bank that asked to wait.
2. Slussen shows the Blocked Reason "waiting for the provider" with the time the Back-off ends.
3. Slussen records the Fetch with outcome Waiting For Back-off.
4. Use case ends.

### A2: Within the Fetch Cooldown

**Trigger:** The last successful Fetch of the Data Provider lies within the Fetch Cooldown (step 2)  
**Flow:**

1. Slussen does not contact the Data Provider.
2. Slussen shows the Bank Transactions and Account Balances already stored, states that they come from the last Fetch and when a new Fetch will contact the Data Provider again.
3. Slussen records the Fetch with outcome Served From Fetch Cooldown.
4. Use case ends.

### A3: Daily Request Limit Reached

**Trigger:** The Data Provider's daily request limit is reached (step 2)  
**Flow:**

1. Slussen does not contact the Data Provider.
2. Slussen shows the Blocked Reason "daily limit reached" and that the limit resets at the start of the next day.
3. Slussen records the Fetch with outcome Daily Limit Reached.
4. Use case ends.

### A4: Bank Without Valid Bank Consent

**Trigger:** The Bank of one or more Bank Accounts has no Bank Consent, or its Bank Consent has expired (step 3)  
**Flow:**

1. Slussen leaves those Bank Accounts out without contacting their Bank.
2. Slussen shows for each of them the Blocked Reason "missing consent" with the action that grants it (UC-007).
3. Use case continues at step 4 for the Bank Accounts of the other Banks, or ends when no Bank Account is left to read.

### A5: Data Provider Asks Slussen to Wait

**Trigger:** The Data Provider answers that Slussen must wait before asking again, for the whole Data Provider or for one Bank (step 5)  
**Flow:**

1. Slussen starts a Back-off with cause Request To Wait for the period the Data Provider names, for the whole Data Provider or only for that Bank.
2. Slussen keeps the previously stored Bank Transactions and Account Balances of the affected Bank Accounts unchanged.
3. Slussen shows the Blocked Reason "waiting for the provider" with the time the Back-off ends.
4. Use case continues at step 6 for the Bank Accounts that were delivered, or ends when none was delivered.

### A6: Data Provider Does Not Answer Usably

**Trigger:** The Data Provider does not answer within the time limit, is unavailable, or gives an answer Slussen cannot read (step 5)  
**Flow:**

1. Slussen starts a Back-off with cause Transient Failure of 30 minutes for the Data Provider.
2. Slussen keeps the previously stored Bank Transactions and Account Balances unchanged.
3. Slussen records the Fetch with outcome Failed and shows the failure as the Source Health of the Data Provider.
4. Use case ends.

### A7: Failure for One Bank Account

**Trigger:** The Data Provider reports a failure for one Bank Account while delivering the others (step 5)  
**Flow:**

1. Slussen keeps the previously stored Bank Transactions and Account Balance of that Bank Account unchanged.
2. Slussen records the outcome Failed for that Bank Account and shows the reason as its Blocked Reason.
3. Use case continues at step 6 for the other Bank Accounts.

### A8: Bank Consent Refused Before Its Expiry

**Trigger:** The Data Provider reports that a Bank no longer accepts its Bank Consent (step 5)  
**Flow:**

1. Slussen treats the Bank Consent as expired from now on.
2. Slussen keeps the previously stored data of that Bank's Bank Accounts unchanged and records the outcome Not Consented for them.
3. Slussen shows the Blocked Reason "missing consent" with the action that grants it again (UC-007).
4. Use case continues at step 6 for the other Bank Accounts.

### A9: Bank Account Not Identified With Certainty

**Trigger:** A delivered Bank Account cannot be identified with certainty as a Bank Account of the Build Configuration (step 6)  
**Flow:**

1. Slussen does not use the transactions of that Bank Account.
2. Slussen applies UC-009 BR-004: it either asks the Operator to confirm a proposed Bank Account (UC-009), or stops for that Bank Account and shows the Blocked Reason "unrecognised Bank Account".
3. Slussen records the outcome Not Recognised or Ambiguous Account for that Bank Account.
4. Use case continues at step 7 for the other Bank Accounts.

### A10: Fetch of All Data Providers

**Trigger:** Operator starts a Fetch of all Data Providers at once (step 1)  
**Flow:**

1. Slussen runs steps 2 to 8 for each Data Provider in turn, each under its own Back-off, Fetch Cooldown and daily request limit.
2. Use case continues at step 9.

## Postconditions

### Success Postconditions

- Every Bank Transaction delivered for an identified Bank Account is stored and has a Transaction Status from the Comparison.
- The Account Balance of every identified Bank Account is stored with the time the Data Provider reported it.
- The Fetch is recorded with its outcome for each Bank Account, and a Fetch Cooldown starts for the Data Provider.

### Failure Postconditions

- Nothing is written to the Budget App.
- The stored Bank Transactions and Account Balances of every Bank Account that was not read successfully are unchanged.
- No Bank is contacted without a valid Bank Consent, and no Data Provider is contacted during a Back-off it asked for.
- No transaction of one Bank Account is stored as a transaction of another Bank Account.

## Business Rules

### BR-001: Fetch Only When the Operator Starts It

Slussen contacts a Data Provider only in a Fetch the Operator starts. It never fetches when the app is opened, in the background or on a schedule.

### BR-002: Booked Transactions Only

Only booked transactions are taken in. A pending transaction is left out until a later Fetch delivers it as booked.

### BR-003: No Zero Amounts

A transaction with an amount of zero is left out.

### BR-004: Requests to Wait Are Honoured

When a Data Provider or a Bank asks Slussen to wait, Slussen does not contact it until the period it named has ended, also when the Operator starts a Fetch. The Back-off applies to the whole Data Provider or to the one Bank that asked.

### BR-005: Back-off After a Transient Failure

After a timeout, an outage or an unreadable answer, Slussen records a Back-off with cause Transient Failure of 30 minutes and shows it with the Source Health. A Fetch the Operator starts is not held back by such a Back-off and ends it.

### BR-006: Fetch Cooldown

For 60 minutes after a successful Fetch of a Data Provider (a number the Operator can change or set to 0 to switch off, UC-010), a new Fetch of that Data Provider is answered from the stored data without contacting it. A Fetch that delivered no Bank Account does not start a Fetch Cooldown.

### BR-007: Daily Request Limit

Slussen sends at most the daily request limit of the Data Provider (24 by default) per calendar day. The count survives a restart of the app, and the limit is not lifted by a Fetch the Operator starts.

### BR-008: No Contact Without Bank Consent

Slussen never contacts a Bank whose Bank Consent is missing or expired. Other Banks of the same Data Provider are still read.

### BR-009: Fetch Period

A Fetch asks for the transactions of the last 60 days.

### BR-010: Failures Stay Isolated

A failure of one Data Provider or one Bank Account leaves the Fetch, the Comparison and the upload of every other Data Provider and Bank Account unaffected.

### BR-011: Balance of a File-Imported Bank Account

For a Bank Account whose transactions come from Transaction Files, the Account Balance is taken from the Data Provider that the Build Configuration names for its balance, during a Fetch of that Data Provider.

### BR-012: Request Time Limit

Every request to a Data Provider gives up after at most 30 seconds and is then treated as a failed request.
