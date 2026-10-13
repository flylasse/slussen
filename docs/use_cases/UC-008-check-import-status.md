# Use Case: Check Import Status

## Overview

**Use Case ID:** UC-008  
**Use Case Name:** Check Import Status  
**Primary Actor:** Operator  
**Goal:** See at a glance, per Budget, whether the Budget App is up to date with each Source and Bank Account, how each Account Balance compares with its Budget Account, and what to do wherever something is blocked  
**Trigger:** Operator opens Slussen  
**Status:** Draft  

**Requirements:** [FR-003, FR-011, FR-012, FR-032, FR-060, FR-061, FR-062, FR-063, FR-064, FR-065, NFR-005](../requirements.md)

## Preconditions

- The Build Configuration holds at least one Budget with a mapped Bank Account.

## Main Success Scenario

1. Operator opens Slussen.
2. Slussen shows the Bank Transactions, Transaction Statuses and Account Balances it last stored, without contacting any Source or the Budget App.
3. Slussen shows every Bank Consent that expires within 7 days, with its expiry date and the action that renews it (UC-007).
4. Operator selects a Budget.
5. Slussen shows only that Budget's Sources, Bank Accounts and Bank Transactions and its number of Eligible Transactions.
6. Slussen shows for each Source and Bank Account the Source Health of its last Fetch and, separately, its Freshness.
7. Slussen shows each Bank Account's Account Balance next to the balance of its Budget Account in the Budget App, with the difference.
8. Slussen shows, for every Bank Account or Source that cannot be fetched or uploaded, its Blocked Reason together with the action that removes it.

## Alternative Flows

### A1: Stored Data Cannot Be Read

**Trigger:** A part of the stored data cannot be read (step 2)  
**Flow:**

1. Slussen keeps the unreadable original unchanged and starts that part from its last good content, or from its default content when there is none.
2. Slussen shows a notice naming the part that could not be read.
3. Use case continues at step 3.

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

### A4: No Account Balance

**Trigger:** No Account Balance is stored for a Bank Account (step 7)  
**Flow:**

1. Slussen shows that no Account Balance is known and shows no difference.
2. Use case continues at step 7 for the next Bank Account.

### A5: Recent Fetch Outcomes

**Trigger:** Operator asks for the recent Fetch outcomes (step 8)  
**Flow:**

1. Slussen shows the 60 most recent Fetches with Source, time and outcome.
2. Use case ends.

### A6: Operator Takes the Remedy

**Trigger:** Operator chooses the action of a Blocked Reason (step 8)  
**Flow:**

1. Slussen starts the use case that removes the reason: Grant Bank Consent (UC-007) for a missing or expiring Bank Consent, Manage Account Mappings (UC-009) for an unrecognised Bank Account, Fetch Transactions (UC-001) for a failed Fetch.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Operator has seen, for the selected Budget, the Source Health, Freshness, Account Balances, differences and Blocked Reasons of every Source and Bank Account.

### Failure Postconditions

- No Source and no Bank is contacted, and nothing is written to the Budget App.
- Unreadable stored data is kept unchanged and is never shown as valid.

## Business Rules

### BR-001: Opening Costs No Bank Access

Opening Slussen shows only stored data. It never contacts a Source, a Bank or the Budget App.

### BR-002: Expiry Warning

Every opening of Slussen shows each Bank Consent that expires within 7 days, with its expiry date and the renew action. The warning never blocks a Fetch.

### BR-003: Health and Freshness Are Separate

Source Health shows only the outcome of the last Fetch. Freshness shows only how old the data is. Old data never changes the Source Health.

### BR-004: Outdated Balances

When an Account Balance is older than 36 hours (a number the Operator can change, UC-010), its difference is hidden and the Account Balance is marked as outdated.

### BR-005: Balance Difference

The difference is the Account Balance minus the balance of the Budget Account in the Budget App, both in the Budget's currency.

### BR-006: Blocked Reasons and Their Remedies

Each Blocked Reason is shown with the action that removes it: missing or expired Bank Consent, grant it (UC-007); unrecognised Bank Account, confirm or identify it (UC-009); waiting for the provider, the time the wait ends; daily limit reached, the start of the next day; configuration error or currency mismatch, correct the Build Configuration (UC-012).

### BR-007: One Budget at a Time

Each Budget, with its own currency, is shown as its own workspace with only its own Sources, Bank Accounts, Bank Transactions and number of Eligible Transactions.

### BR-008: Recent Fetches

Slussen keeps the 60 most recent Fetches with Source, time and outcome.
