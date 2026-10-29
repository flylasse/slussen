# Use Case: Fetch Transactions

## Overview

**Use Case ID:** UC-001  
**Use Case Name:** Fetch Transactions  
**Primary Actor:** Operator  
**Secondary Actors:** Data Provider  
**Goal:** Bring the latest booked Bank Transactions and Account Balances of the Bank Accounts that one Data Provider reads into Slussen, compared with the Budget App, while spending no more Bank access than needed  
**Trigger:** Operator starts a Fetch of one Data Provider, of the Data Providers that have a Bank Account in one Budget, or of all Data Providers, or the time of a Scheduled Fetch arrives  
**Status:** Approved  

**Requirements:** [FR-001, FR-004, FR-005, FR-008, FR-009, FR-013, FR-014, FR-015, FR-016, FR-017, FR-018, FR-058, FR-064, FR-066, FR-076, FR-077, FR-078, FR-079, FR-080, FR-098, FR-102, FR-105, NFR-002, NFR-004, NFR-007, NFR-008, NFR-012, NFR-016, NFR-018, NFR-020, NFR-022, NFR-023, C-006, C-007, C-012](../requirements.md)

## Preconditions

- The Operator has entered service credentials for the Data Provider that it accepted (UC-014).
- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Operator starts a Fetch of a Data Provider.
2. Slussen checks that the Data Provider is in no Back-off, is not within its Fetch Cooldown and has not reached its Request Limit.
3. Slussen selects the Bank Accounts to read: every Bank Account the Operator added for the Data Provider (UC-013), and, for its balance only, every Bank Account whose transactions come from Transaction Files and whose balance the Operator chose to take from the Data Provider (BR-011), that has an Account Mapping, is not a Retired Account and is covered by a valid Bank Consent when the Data Provider needs one (UC-007 BR-009).
4. Slussen asks the Data Provider, with the Operator present, for the Bank Accounts it offers, also when no Bank Account is selected, and for the transactions and the balance of each selected Bank Account for the fetch period, only the balance for one whose transactions come from Transaction Files, and keeps each request as a Provider Request (BR-007).
5. Data Provider delivers the transactions and the Account Balance of each selected Bank Account.
6. Slussen keeps the delivered Bank Accounts as Offered Bank Accounts (UC-009 BR-009) and identifies each selected one with certainty as one of the Bank Accounts the Operator added.
7. Slussen keeps the booked Bank Transactions with an amount other than zero and leaves out the others.
8. Slussen stores the Bank Transactions and Account Balances of each identified Bank Account, including the balance the Data Provider reports for a Bank Account whose transactions come from Transaction Files.
9. Slussen records in the Decision Journal each Bank Transaction it received for the first time or with changed content (UC-011 BR-001).
10. Slussen compares the stored Bank Transactions with the Budget App (UC-003).
11. Slussen records the Fetch with its outcome for each Bank Account and shows the Source Health, the Freshness and the Transaction Statuses of each Bank Account.

## Alternative Flows

### A1: Data Provider Asked Slussen to Wait

**Trigger:** A Back-off with cause Request To Wait that the Data Provider or a Bank asked for has not ended yet, or, in a Scheduled Fetch, a Back-off with cause Request Limit Warning has not ended yet (step 2)  
**Flow:**

1. Slussen does not contact the Data Provider, or the Bank that asked to wait.
2. Slussen shows the Blocked Reason "waiting for the provider" with the time the Back-off ends.
3. Slussen records the Fetch with outcome Waiting For Back-off.
4. Use case ends.

### A2: Within the Fetch Cooldown

**Trigger:** The last successful Fetch of the Data Provider lies within the Fetch Cooldown (step 2)  
**Flow:**

1. Slussen does not contact the Data Provider, except for a Bank Account selected as step 3 describes that has not been read since the Operator added it or confirmed or changed its identification (UC-009) (BR-006): Slussen reads only those Bank Accounts from the Data Provider as steps 4 to 9 describe, or, when the Request Limit is reached, reads none of them and shows the Blocked Reason "Request Limit reached" as A3 describes.
2. Slussen shows the Bank Transactions and Account Balances already stored of the other Bank Accounts, states that they come from the last Fetch and when a new Fetch will contact the Data Provider again.
3. Slussen records the Fetch with outcome Served From Fetch Cooldown, with the outcome for each Bank Account it read.
4. Use case continues at step 10, so that the Budget App is still read (BR-006).

### A3: Request Limit Reached

**Trigger:** The Data Provider's Request Limit (BR-007) is reached (step 2)  
**Flow:**

1. Slussen does not contact the Data Provider.
2. Slussen shows the Blocked Reason "Request Limit reached" with the time the oldest counted Provider Request leaves the 24-hour window.
3. Slussen records the Fetch with outcome Request Limit Reached.
4. Use case ends.

### A4: Bank Without Valid Bank Consent

**Trigger:** The Bank the Data Provider names for one or more Bank Accounts has no Bank Consent, or its Bank Consent has expired (UC-007 BR-009) (step 3)  
**Flow:**

1. Slussen leaves those Bank Accounts out without contacting their Bank.
2. Slussen shows for each of them the Blocked Reason "missing consent" with the action that grants it (UC-007).
3. Use case continues at step 4 for the Bank Accounts of the other Banks, or ends when no Bank Account is left to read.

### A5: Data Provider Asks Slussen to Wait

**Trigger:** The Data Provider answers that Slussen must wait before asking again, for the whole Data Provider or for one Bank (step 5)  
**Flow:**

1. Slussen starts a Back-off with cause Request To Wait, for the whole Data Provider or only for that Bank, that lasts the period the Data Provider names, or the default wait when it names none (BR-004).
2. Slussen keeps the previously stored Bank Transactions and Account Balances of the affected Bank Accounts unchanged.
3. Slussen shows the Blocked Reason "waiting for the provider" with the time the Back-off ends.
4. Use case continues at step 6 for the Bank Accounts that were delivered, or ends when none was delivered.

### A6: Data Provider Does Not Answer Usably

**Trigger:** In a Fetch the Operator started, the Data Provider does not answer within the time limit of NFR-016, is unavailable, or gives an answer Slussen cannot read (step 5)  
**Flow:**

1. Slussen keeps the previously stored Bank Transactions and Account Balances unchanged.
2. Slussen records the Fetch with outcome Failed and shows the failure with its time as the Source Health of the Data Provider.
3. Slussen starts no Back-off: the Operator can start a Fetch again at any time (BR-005).
4. Use case ends.

### A7: Data Provider Refuses Slussen's Credentials

**Trigger:** The Data Provider answers that the service credentials the Operator entered are not valid or not allowed (step 5)  
**Flow:**

1. Slussen starts no Back-off and keeps the previously stored Bank Transactions and Account Balances unchanged.
2. Slussen records the Fetch with outcome Credentials Refused.
3. Slussen shows the Blocked Reason "credentials refused" with the action that removes it: enter the credentials again (UC-014).
4. Use case ends.

### A8: Failure for One Bank Account

**Trigger:** The Data Provider reports a failure for one Bank Account while delivering the others (step 5)  
**Flow:**

1. Slussen keeps the previously stored Bank Transactions and Account Balance of that Bank Account unchanged.
2. Slussen records the outcome Failed for that Bank Account and shows the reason as its Blocked Reason.
3. Use case continues at step 6 for the other Bank Accounts.

### A9: Bank Consent Refused Before Its Expiry

**Trigger:** The Data Provider reports that a Bank no longer accepts its Bank Consent (step 5)  
**Flow:**

1. Slussen treats the Bank Consent as expired from now on and starts no Back-off for that Bank.
2. Slussen keeps the previously stored data of that Bank's Bank Accounts unchanged and records the outcome Not Consented for them.
3. Slussen shows the Blocked Reason "missing consent" with the action that grants it again (UC-007).
4. Use case continues at step 6 for the other Bank Accounts.

### A10: Bank Account Not Identified With Certainty

**Trigger:** A delivered Bank Account can only probably be identified as one of the Bank Accounts the Operator added, or two of them are possible, or the Data Provider delivered two Bank Accounts with the same reference (UC-009 BR-004) (step 6)  
**Flow:**

1. Slussen does not use the transactions of that Bank Account.
2. Slussen applies UC-009 BR-004: it either asks the Operator to confirm a proposed Bank Account (UC-009), or stops for that Bank Account and shows the Blocked Reason "unrecognised Bank Account".
3. Slussen records the outcome Not Recognised or Ambiguous Bank Account for that Bank Account.
4. Use case continues at step 7 for the other Bank Accounts.

### A11: Fetch of Several Data Providers

**Trigger:** Operator starts a Fetch of all Data Providers, or of the Data Providers that have a Bank Account in one Budget (step 1)  
**Flow:**

1. Slussen runs steps 2 to 9 for each Data Provider in turn, each under its own Back-off, Fetch Cooldown and Request Limit.
2. Slussen handles a Data Provider that waits, is within its Fetch Cooldown, has reached its Request Limit, fails or refuses the credentials as A1, A2, A3, A6 or A7 describe, for that Data Provider only, and goes on with the next Data Provider (BR-010).
3. Use case continues at step 10.

### A12: Scheduled Fetch

**Trigger:** The time of a Scheduled Fetch arrives under the Fetch Schedule (step 1)  
**Flow:**

1. Slussen runs steps 2 to 9 for each Data Provider in turn as A11 describes, without the Operator present, so that every request to a Data Provider that needs a Bank Consent is an Unattended Access (BR-012).
2. Slussen compares the stored Bank Transactions with the Budget App (UC-003) and records each Fetch as started by the Fetch Schedule, with its outcome for each Bank Account.
3. Slussen updates the New Transaction Notification with the number of unprocessed Bank Transactions, or removes it when that number is 0 (BR-014).
4. Slussen prepares no Upload Plan and writes nothing to the Budget App (BR-013).
5. Use case ends.

### A13: Unattended Access Limit Reached

**Trigger:** In a Scheduled Fetch, a Bank Account read through a Data Provider that needs a Bank Consent has had 4 Unattended Accesses of its transactions or of its balance in the preceding 24 hours, the limit PSD2 sets (C-006) (step 3)  
**Flow:**

1. Slussen leaves that Bank Account out without contacting its Bank.
2. Slussen shows the Blocked Reason "access limit reached" for the Bank Account with the time the limit frees up.
3. Slussen records the outcome Access Limit Reached for that Bank Account.
4. Use case continues at step 4 for the other Bank Accounts, or ends when no Bank Account is left to read.

### A14: Transient Failure in a Scheduled Fetch

**Trigger:** In a Scheduled Fetch, the Data Provider does not answer within the time limit of NFR-016, is unavailable, or gives an answer Slussen cannot read (step 5)  
**Flow:**

1. Slussen keeps the previously stored data unchanged, records the Fetch with outcome Failed and shows the failure with its time as the Source Health of the Data Provider.
2. Slussen does not contact that Data Provider again before the next Scheduled Fetch, unless the Operator starts a Fetch (BR-005).
3. Use case continues at step 2 for the next Data Provider, or ends when none is left.

### A15: Data Provider Warns About Its Request Limit

**Trigger:** The Data Provider warns that Slussen is near or over its Request Limit (step 5)
**Flow:**

1. Slussen starts a Back-off with cause Request Limit Warning for the Data Provider that lasts the Request Limit warning pause from the first warning, so that no Scheduled Fetch contacts it until then (BR-015).
2. Slussen shows the warning with the time it ends and the risk that further requests can get Slussen's access to the Data Provider disabled.
3. Use case continues at step 6 for the Bank Accounts that were delivered.

### A16: Operator Fetches Despite a Request Limit Warning

**Trigger:** The Operator starts a Fetch of a Data Provider whose Back-off with cause Request Limit Warning has not ended yet (step 2)
**Flow:**

1. Slussen shows the warning, the time it ends and the risk that further requests can get Slussen's access to the Data Provider disabled, and asks the Operator to confirm the Fetch.
2. Operator confirms, or cancels and the use case ends.
3. Use case continues at step 3.

### A17: Transaction Cannot Be Interpreted

**Trigger:** The Data Provider delivers a transaction for an identified Bank Account that Slussen cannot interpret, for example because its date or amount is missing or unreadable, or whose Import Identifier collides with the Import Identifier of a different stored Bank Transaction of the same Bank Account (UC-003 BR-002) (step 7)  
**Flow:**

1. Slussen keeps it as an Unreadable Transaction of that Bank Account, with whatever fields could be read, the reason and the status Needs Review (BR-016).
2. Use case continues at step 7 for the other transactions.

### A18: Bank Account Not Added

**Trigger:** A delivered Bank Account matches none of the Bank Accounts the Operator added (step 6)  
**Flow:**

1. Slussen keeps it only as an Offered Bank Account, imported and to map until the Operator adds it (UC-013 BR-012), or, when it is a Left-Out Bank Account, not imported until the Operator imports it again (UC-013 BR-011), and stores or uses none of its transactions or its balance. A Left-Out Bank Account is not counted among the Bank Accounts the Fetch shows as not added.
2. Use case continues at step 7 for the other Bank Accounts.

### A19: Selected Bank Account Not Delivered

**Trigger:** The Data Provider answers without one of the Bank Accounts selected in step 3, neither delivered nor reported as failed or not read (step 5)  
**Flow:**

1. Slussen keeps the previously stored Bank Transactions and Account Balance of that Bank Account unchanged.
2. Slussen records the outcome Not Delivered for that Bank Account.
3. Slussen shows the Blocked Reason "not delivered by the Data Provider" with the time of the Fetch, the action that fetches again, and the advice to check at the Data Provider that the Bank Account is still connected there.
4. Use case continues at step 6 for the other Bank Accounts.

### A20: Bank's Sign-In at the Data Provider Needed

**Trigger:** The Data Provider reports that the Operator must sign in to a Bank again at the Data Provider before it can read that Bank's Bank Accounts, for example SimpleFIN's `con.auth` (step 5)  
**Flow:**

1. Slussen keeps the previously stored Bank Transactions and Account Balances of those Bank Accounts unchanged and starts no Back-off.
2. Slussen records the outcome Sign-In Needed for each of them.
3. Slussen shows the Blocked Reason "the Bank's sign-in at the Data Provider needs you", naming the Data Provider, with the action: sign in to the Bank again at the Data Provider, then fetch again. Slussen never shows the Data Provider's own text (NFR-012).
4. Use case continues at step 6 for the other Bank Accounts.

### A21: Balance in Another Currency

**Trigger:** The Data Provider reports the balance of an identified Bank Account in a currency other than the Bank Account's own (step 5)  
**Flow:**

1. Slussen does not store that balance and keeps the previous Account Balance of the Bank Account unchanged.
2. Slussen shows on the Bank Account that its balance was reported in the other currency, naming both currencies, for example "balance reported in EUR, expected USD", and that nothing is needed now; if it happens again, the Operator checks the Bank Account's currency at the Data Provider. No amount is converted.
3. Use case continues at step 7 for the transactions of that Bank Account and for the other Bank Accounts.

## Postconditions

### Success Postconditions

- Every Bank Transaction delivered for an identified Bank Account is stored and has a Transaction Status from the Comparison.
- Every delivered transaction that Slussen could not interpret is kept as an Unreadable Transaction with its reason.
- The Account Balance of every identified Bank Account is stored with the time the Data Provider says it was reported, or the time Slussen received it when the Data Provider gives none, and only in the Bank Account's own currency (A21).
- The Fetch is recorded with who started it and its outcome for each Bank Account, and a Fetch Cooldown starts for the Data Provider.
- Every request sent is kept as a Provider Request with its time.
- The Bank Transactions received are recorded in the Decision Journal, unless the Decision Journal is switched off.
- After a Scheduled Fetch, the New Transaction Notification shows the number of unprocessed Bank Transactions, or is absent when that number is 0.

### Failure Postconditions

- Nothing is written to the Budget App, and no Upload Plan is prepared.
- The stored Bank Transactions and Account Balances of every Bank Account that was not read successfully are unchanged.
- No Bank is contacted without a valid Bank Consent, and no Data Provider is contacted during a Back-off it asked for.
- No Data Provider receives more requests than its Request Limit, and no Bank Account more Unattended Accesses than the PSD2 limit (C-006), in any 24 hours.
- No transaction of one Bank Account is stored as a transaction of another Bank Account.

## Business Rules

### BR-001: Fetch Only When the Operator or the Fetch Schedule Starts It

Slussen contacts a Data Provider only in a Fetch the Operator starts or in a Scheduled Fetch. It never fetches when the app is opened.

### BR-002: Booked Transactions Only

Only booked transactions are taken in. A pending transaction is left out until a later Fetch delivers it as booked.

### BR-003: No Zero Amounts

A transaction with an amount of zero is left out.

### BR-004: Requests to Wait Are Honoured

When a Data Provider or a Bank asks Slussen to wait, Slussen does not contact it until the period it named has ended, or for the default wait when it named no period (8 hours by default, a number the Operator can change, UC-010). Such a Back-off holds for a Fetch the Operator starts and for a Scheduled Fetch alike; no action of the Operator ends it. It applies to the whole Data Provider or to the one Bank that asked. A Back-off of one Bank holds for the Bank Accounts the Data Provider names that Bank for, whatever Bank the Operator confirmed or chose for them, as a Bank Consent does (UC-007 BR-009): the Bank the Operator chose only names and groups a Bank Account in Slussen. A warning about the Request Limit is not a request to wait and follows BR-015.

### BR-005: No Wait After a Transient Failure

A timeout, an outage or an unreadable answer starts no Back-off. Slussen shows the failure with its time, and the Operator can start a Fetch again at any time. A Scheduled Fetch that meets such a failure is not repeated; the Data Provider is next contacted by the next Scheduled Fetch or by a Fetch the Operator starts. A refusal of credentials or of a Bank Consent is not a transient failure.

### BR-006: Fetch Cooldown

Each Data Provider has its own Fetch Cooldown, whose default comes with the Data Provider's part and which the Operator can change, reset to that default or set to 0 to switch off on the Data Provider's page in Settings (UC-010 A10, BR-010). During it, a new Fetch of that Data Provider, started by the Operator or as a Scheduled Fetch, is answered from the stored data without contacting it, and the Comparison still reads the Budget App (UC-003). A Fetch that delivered no Bank Account does not start a Fetch Cooldown. Whether it delivered one is not stored with the Fetch: Slussen takes it from the Offered Bank Accounts the Fetch kept (UC-009 BR-009), which are not restored after a reinstall, so the first Fetch after a restore starts no Fetch Cooldown and can cost one request more than needed. A Bank Account that has not been read since the Operator added it or confirmed or changed its identification (UC-009), that is, that has no Fetch Result with the outcome Fetched since then, is not served from the Fetch Cooldown, because nothing of it under that identification is stored yet: a Fetch within the Fetch Cooldown reads it from the Data Provider (A2). Once a Fetch has read it, the Fetch Cooldown serves it like every other Bank Account. That read is kept as a Provider Request and counts against the Request Limit (BR-007), and in a Scheduled Fetch against the Unattended Access limit (BR-012), like any other request; it neither starts nor extends the Fetch Cooldown.

### BR-007: Request Limit

Slussen keeps every request it sends to a Data Provider as a Provider Request with its time, and sends a Data Provider at most its Request Limit of requests in the preceding 24 hours, the window of a Data Provider's own limit and of PSD2 (C-006). Each Data Provider's part declares its Request Limit (UC-012 BR-011): the limit the Data Provider publishes, fixed and named with its source, which the Operator cannot change because it protects the Data Provider's own limit; or, when the Data Provider publishes none, a cautious default that is an Operator Setting the Operator can change on the Data Provider's page in Settings within the range the part declares (UC-010 BR-002), shown as not published by the Data Provider. The count survives a restart of the app, does not depend on calendar day or time zone, and is not lifted by a Fetch the Operator starts. Times are shown in the device's local time.

### BR-008: No Contact Without Bank Consent

Slussen never contacts a Bank whose Bank Consent is missing or expired. Other Banks of the same Data Provider are still read. A Fetch never starts a Bank Consent grant; the Operator starts it from the Blocked Reason (UC-007).

### BR-009: Fetch Period

A Fetch asks for the transactions of the fetch period (the last 30 days by default, a number the Operator can change on Settings, UC-010). A changed fetch period applies from the next Fetch that contacts the Data Provider; a Fetch served from the Fetch Cooldown asks for nothing.

### BR-010: Failures Stay Isolated

A failure, a Back-off, a Fetch Cooldown, a reached Request Limit or a reached Unattended Access limit of one Data Provider or one Bank Account leaves the Fetch, the Comparison and the Upload of every other Data Provider and Bank Account unaffected.

### BR-011: Balance of a File-Imported Bank Account

For a Bank Account whose transactions come from Transaction Files, the Account Balance is taken from the Data Provider the Operator chose for its balance (UC-013 BR-006), during a Fetch of that Data Provider.

### BR-012: Operator Present or Unattended

A Fetch the Operator starts is made with the Operator present and is never counted against the Unattended Access limit. A Scheduled Fetch is unattended: through a Data Provider that needs a Bank Consent, it reads the transactions of a Bank Account at most 4 times and its balance at most 4 times in any 24 hours (C-006), counted from the Provider Requests of the preceding 24 hours.

### BR-013: Fetch Schedule

Scheduled Fetches follow the Fetch Schedule (UC-010): 2 in any 24 hours by default, evenly spaced, a number the Operator can lower to 1 or to 0 to switch them off. A Scheduled Fetch fetches all Data Providers and compares, and never prepares an Upload Plan, writes to the Budget App or waits for an answer from the Operator. A Scheduled Fetch that falls due while an Upload Plan awaits confirmation or its Upload is being written starts when that pause ends (UC-006 BR-005).

### BR-014: New Transaction Notification

Slussen shows one silent, low-priority New Transaction Notification, also shown as a badge on the app icon, with the number of unprocessed Bank Transactions: the Eligible Transactions plus the Bank Transactions with the status Possible Import By Other Path, Needs Review, Decision No Longer Holds, Guessed Transfer or Transfer Counterpart Changed, the Bank Transactions with the status Outcome Unknown that wait for the Operator's Review Decision (UC-004 BR-008), and the Unreadable Transactions with the status Needs Review, of every Bank Account that is not a Retired Account. Whether the Operator has looked at them does not matter. The notification is updated after each Scheduled Fetch and whenever the number changes, for example after a Review Decision or an Upload, and disappears when the number is 0. A later update replaces it instead of adding another one. Slussen shows no other notification. When the device does not allow notifications, the number is not shown and nothing else changes.

### BR-015: Request Limit Warning

When a Data Provider warns that Slussen is near or over its Request Limit, Slussen starts a Back-off with cause Request Limit Warning that lasts the Request Limit warning pause from the first warning (24 hours by default, a number the Operator can change, UC-010). During it, no Scheduled Fetch contacts that Data Provider. A Fetch the Operator starts is still allowed: Slussen first shows the warning, the time it ends and the risk that further requests can get Slussen's access to the Data Provider disabled, and goes ahead when the Operator confirms. A Data Provider's request to wait (BR-004) is never overridden this way.

### BR-016: Unreadable Transactions Are Kept

A delivered transaction that Slussen cannot interpret, or whose Import Identifier collides with the Import Identifier of a different stored Bank Transaction of the same Bank Account (UC-003 BR-002), is never dropped and never completed by guessing. Slussen keeps it as an Unreadable Transaction with whatever fields could be read and the reason, shows it as Needs Review for the Operator's review (UC-004) and never uploads it (UC-003 BR-012). A later Fetch that delivers as many Unreadable Transactions with the same readable fields and reason as are stored adds none; when it delivers more, only the additional ones are added.
