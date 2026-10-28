# Use Case: Check Import Status

## Overview

**Use Case ID:** UC-008  
**Use Case Name:** Check Import Status  
**Primary Actor:** Operator  
**Goal:** See at a glance, for every Budget, whether the Budget App is up to date with each Source and Bank Account, how each Account Balance compares with its Budget Account, and what to do wherever something is blocked  
**Trigger:** Operator opens Slussen  
**Status:** Approved  

**Requirements:** [FR-003, FR-011, FR-012, FR-032, FR-060, FR-061, FR-062, FR-063, FR-064, FR-065, FR-066, FR-095, FR-103, NFR-005, NFR-018, NFR-019, NFR-023, C-015](../requirements.md)

## Preconditions

_None — opening Slussen before any Bank Account is added is handled by A10._

## Main Success Scenario

1. Slussen shows, from what it last stored and without contacting any Source or the Budget App, an overview of every Budget with its number of Eligible Transactions, its number of Bank Transactions waiting for a decision, and whether any of its Sources or Bank Accounts is blocked.
2. Slussen shows every Bank Consent that expires within the consent warning lead time, with its expiry date and the action that renews it (UC-007).
3. Slussen records the start of the session in the Decision Journal (UC-011 BR-001).
4. Operator selects a Budget.
5. Slussen shows only that Budget's Sources, Bank Accounts and Bank Transactions with their Transaction Statuses.
6. Slussen shows for each Source and Bank Account its Source Health with its time, separately its Freshness, and the number of its Bank Transactions not yet uploaded (BR-003).
7. Slussen shows each Bank Account's Account Balance next to the balance of its Budget Account in the Budget App, with the difference and the time each balance was read.
8. Slussen shows, for every Bank Account or Source that cannot be fetched or uploaded, its Blocked Reason together with the action that removes it or the time it ends.

## Alternative Flows

### A1: Stored Data Cannot Be Read

**Trigger:** A part of the stored data cannot be read (step 1)  
**Flow:**

1. Slussen keeps the unreadable original unchanged and starts that part from its last good content, or from its default content when there is none.
2. Slussen shows a notice naming the part that could not be read.
3. When the part holds Decision-Critical Data, Slussen opens a First Upload Matching, so that no Upload Plan is prepared until the Operator has confirmed it (BR-009); damage to any other part, such as the Decision Journal, opens none and changes no Upload Plan or Upload.
4. Use case continues at step 2.

### A2: Account Balance Outdated

**Trigger:** The Account Balance of a Bank Account is older than the outdated-balance limit (step 7)  
**Flow:**

1. Slussen hides the difference and marks the Account Balance as outdated.
2. Use case continues at step 7 for the next Bank Account.

### A3: Currency Mismatch

**Trigger:** The Bank Account's currency differs from its Budget's currency (step 7)  
**Flow:**

1. Slussen hides the difference and shows the Blocked Reason "currency mismatch".
2. Use case continues at step 7 for the next Bank Account.

### A4: Balance Not Known

**Trigger:** No Account Balance is stored for a Bank Account, or the Budget App has not yet reported the balance of its Budget Account (step 7)  
**Flow:**

1. Slussen shows which balance is not known and shows no difference.
2. Use case continues at step 7 for the next Bank Account.

### A5: Recent Fetch Outcomes

**Trigger:** Operator asks for the recent Fetch outcomes (step 8)  
**Flow:**

1. Slussen shows the most recent Fetches (BR-008) with Source, time, whether the Operator or the Fetch Schedule started them, and outcome.
2. Use case ends.

### A6: Operator Takes the Remedy

**Trigger:** Operator chooses the action of a Blocked Reason (step 8)  
**Flow:**

1. Slussen starts the use case that removes the reason: Grant Bank Consent (UC-007) for a missing or expiring Bank Consent, Manage Account Mappings (UC-009) for an unrecognised Bank Account, Fetch Transactions (UC-001) for a failed Fetch.
2. Use case ends.

### A7: Backed-Up Data Nears the Backup Limit

**Trigger:** The data Slussen includes in device backups has reached the backup warning threshold of the backup size limit Android sets (step 1)  
**Flow:**

1. Slussen shows a warning with the current size of the backed-up data and Android's limit, and that Android makes no backup at all once the limit is passed (BR-010).
2. Use case continues at step 2.

### A8: First Upload Matching Open

**Trigger:** A First Upload Matching is open (step 1)  
**Flow:**

1. Slussen shows that Uploads wait for First Upload Matching, with its explanation, the reason line of the cause of each open one and the action "Start First Upload Matching", as information and not as an error (BR-009).
2. Use case continues at step 2.

### A9: Operator Confirms First Upload Matching

**Trigger:** Operator chooses the action "Start First Upload Matching" of the Blocked Reason "Uploads wait for First Upload Matching" (step 8)  
**Flow:**

1. Slussen starts Compare With the Budget App (UC-003) when no Comparison that read every Budget has run since First Upload Matching opened; when the Budget App cannot be read, First Upload Matching stays open and the use case ends.
2. Slussen shows the result, the Transaction Statuses of every Budget with every Bank Transaction the Budget App already holds marked, and asks the Operator to confirm that they have looked at it and checked the Budget App.
3. Operator confirms, or cancels and the use case ends.
4. Slussen closes First Upload Matching, so that Uploads are available again, and records the confirmation in the Decision Journal.
5. Use case ends.

### A10: No Bank Account Added Yet

**Trigger:** No Bank Account is added yet, for example on the first opening after a new installation (step 1)  
**Flow:**

1. Slussen shows, instead of the overview, only one card that leads to Settings and names what comes next in setting up Slussen, for example entering the Budget App's credentials (BR-011, UC-013 BR-008).
2. Slussen records the start of the session in the Decision Journal (UC-011 BR-001).
3. Use case ends.

## Postconditions

### Success Postconditions

- The Operator has seen, for every Budget, its number of Eligible Transactions and whether anything is blocked, and for the selected Budget the Source Health, Freshness, transactions not yet uploaded, Account Balances, differences and Blocked Reasons of every Source and Bank Account.

### Failure Postconditions

- No Source and no Bank is contacted, and nothing is written to the Budget App.
- Unreadable stored data is kept unchanged and is never shown as valid.
- A First Upload Matching is never closed without a Comparison that read every Budget and the Operator's confirmation.

## Business Rules

### BR-001: Opening Costs No Bank Access

Opening Slussen shows only stored data. It never contacts a Source, a Bank or the Budget App.

### BR-002: Expiry Warning

Every opening of Slussen shows each Bank Consent whose reported expiry date lies within the consent warning lead time (7 days by default, a number the Operator can change, UC-010), with its expiry date and the renew action. The warning never blocks a Fetch. It appears only when Slussen is opened and is never a notification.

### BR-003: Health and Freshness Are Separate

Source Health shows only the outcome of the last Fetch, or of the last Transaction File import for a file-imported Bank Account (UC-002 BR-006). Freshness shows only how old the data is. Old data never changes the Source Health. Next to both, Slussen shows how many Bank Transactions of the Source or Bank Account are not yet uploaded: Eligible Transactions and those waiting for a decision.

### BR-004: Outdated Balances

When an Account Balance is older than 36 hours (36 by default, a number the Operator can change, UC-010), its difference is hidden and the Account Balance is marked as outdated.

### BR-005: Balance Difference

The difference is the Account Balance minus the balance of the Budget Account as the Budget App last reported it (UC-003), both in the Budget's currency, shown with the time each balance was read: for the Account Balance, the time the Data Provider says it was reported ("balance as of …").

### BR-006: Blocked Reasons and Their Remedies

Each Blocked Reason is shown with the action that removes it: missing or expired Bank Consent, grant it (UC-007); unrecognised Bank Account, confirm or identify it (UC-009); waiting for the provider, the time the wait ends; a failed Fetch, the time of the failure and the action that fetches again (UC-001); Request Limit reached or access limit reached, the time the limit frees up; credentials refused or missing, enter them (UC-014); not delivered by the Data Provider, the time of the Fetch, the action that fetches again and the advice to check the Bank Account at the Data Provider (UC-001 A19); the Bank's sign-in at the Data Provider needs you, sign in to the Bank again at the Data Provider and fetch again (UC-001 A20); currency mismatch, move the Account Mapping (UC-009 A8); Uploads wait for First Upload Matching, start First Upload Matching: compare with the Budget App and confirm (BR-009).

### BR-007: One Budget at a Time

Each Budget, with its own currency, is shown as its own workspace with only its own Sources, Bank Accounts, Bank Transactions and number of Eligible Transactions. The overview of every Budget shows counts and blocked states only and never mixes the transactions of two Budgets.

### BR-008: Recent Fetches

Slussen keeps the most recent Fetches with Source, time, who started them and outcome: 60 by default, a number the Operator can change (UC-010). Every time Slussen shows is in the device's local time.

### BR-009: First Upload Matching

After Slussen starts from Decision-Critical Data that may lack what it last recorded (UC-006 BR-017), every opening shows that Uploads wait for First Upload Matching, until the Operator confirms it. Its short name is First Upload Matching, its Blocked Reason reads "Uploads wait for First Upload Matching", and its action reads "Start First Upload Matching". It holds Uploads back and is not a failure, so it is always shown as information and never as an error: as plain information after a new installation, and as a caution after a restore from a device backup or a start from the last good copy, also while one of those is open beside one of a new installation.

The explanation is the same in every case, with the Budget App's name where it says the Budget App: "Before Slussen uploads anything, it compares the transactions it fetched with what is already in the Budget App and marks every match, so that no transaction ends up in the Budget App twice. You look at the result and confirm; until then nothing is uploaded. Fetching works as usual."

Below it, Slussen shows one reason line for what it started from. After a new installation: "This is a new installation. Your earlier importer, the Budget App's own bank import or you by hand may already have added some of these transactions to the Budget App." After a restore from a device backup: "Slussen was restored from a backup. It may have uploaded transactions after the backup was made that its records no longer show." After a start from the last good copy: "Slussen's records could not be read, so it started from its last good copy, or from empty records when it had none. Anything uploaded after that copy was made is not in its records." Slussen knows neither the date of the backup nor the date of the last good copy, so no line names a date. While more than one First Upload Matching is open, the line of each cause is shown once.

The Operator can confirm First Upload Matching only after a Comparison that read every Budget has run since it opened (UC-003 BR-019); one confirmation closes every open one. The confirmation is recorded in the Decision Journal.

### BR-010: Backup Size Warning

Android backs up at most 25 MB of an app's data and makes no backup at all when the data is larger, a limit Android sets (C-015). When the data Slussen includes in device backups reaches the backup warning threshold of that limit (80% by default, a number the Operator can change, UC-010), every opening of Slussen shows a warning with the current size and the limit. The Decision Journal, logs, Bank Consents, Bank Sessions and other data that are never backed up do not count.

### BR-011: First Opening Leads to Settings

While no Bank Account is added, opening Slussen shows nothing but one card that leads to Settings, where setting up Slussen starts (UC-013 BR-008), and names the next thing to do there, the first one UC-013 BR-008 recommends that is not yet done: for example entering the Budget App's credentials, choosing the Budgets, fetching a Data Provider or granting a Bank Consent, or mapping the imported Bank Accounts. Nothing else is shown, not even the First Upload Matching that a new installation opens (A8, UC-006 BR-017), since nothing can be prepared for Upload before a Bank Account is added. From the first added Bank Account on, opening Slussen shows the overview of the main success scenario.
