# Use Case: Check Import Status

## Overview

**Use Case ID:** UC-008  
**Use Case Name:** Check Import Status  
**Primary Actor:** Operator  
**Goal:** See at a glance, for every Budget, whether the Budget App is up to date with each Source and Bank Account, how each Account Balance compares with its Budget Account, and what to do wherever something is blocked  
**Trigger:** Operator opens Slussen  
**Status:** Draft  

**Requirements:** [FR-003, FR-011, FR-012, FR-032, FR-060, FR-061, FR-062, FR-063, FR-064, FR-065, FR-066, NFR-005, NFR-018](../requirements.md)

## Preconditions

- The Build Configuration holds at least one Budget with a mapped Bank Account.

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
3. Use case continues at step 2.

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

## Postconditions

### Success Postconditions

- The Operator has seen, for every Budget, its number of Eligible Transactions and whether anything is blocked, and for the selected Budget the Source Health, Freshness, transactions not yet uploaded, Account Balances, differences and Blocked Reasons of every Source and Bank Account.

### Failure Postconditions

- No Source and no Bank is contacted, and nothing is written to the Budget App.
- Unreadable stored data is kept unchanged and is never shown as valid.

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

The difference is the Account Balance minus the balance of the Budget Account as the Budget App last reported it (UC-003), both in the Budget's currency, shown with the time each balance was read.

### BR-006: Blocked Reasons and Their Remedies

Each Blocked Reason is shown with the action that removes it: missing or expired Bank Consent, grant it (UC-007); unrecognised Bank Account, confirm or identify it (UC-009); waiting for the provider, the time the wait ends; a failed Fetch, the time of the failure and the action that fetches again (UC-001); Request Limit reached or access limit reached, the time the limit frees up; configuration error or currency mismatch, correct the Build Configuration (UC-012).

### BR-007: One Budget at a Time

Each Budget, with its own currency, is shown as its own workspace with only its own Sources, Bank Accounts, Bank Transactions and number of Eligible Transactions. The overview of every Budget shows counts and blocked states only and never mixes the transactions of two Budgets.

### BR-008: Recent Fetches

Slussen keeps the most recent Fetches with Source, time, who started them and outcome: 60 by default, a number the Operator can change (UC-010). Every time Slussen shows is in the device's local time.
