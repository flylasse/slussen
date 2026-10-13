# Use Case: Manage Account Mappings

## Overview

**Use Case ID:** UC-009  
**Use Case Name:** Manage Account Mappings  
**Primary Actor:** Operator  
**Goal:** Have every Bank Account identified with certainty and sending its transactions to exactly one Budget Account, and pause or resume a Bank Account without losing anything  
**Trigger:** Operator opens the Account Mappings to check or change them  
**Status:** Draft  

**Requirements:** [FR-042, FR-056, FR-057, FR-058, FR-059, FR-066, NFR-010, C-012](../requirements.md)

## Preconditions

- The Build Configuration holds the Bank Accounts and their Account Mappings.

## Main Success Scenario

1. Operator opens the Account Mappings.
2. Slussen shows each Bank Account with its Bank, its Source, the Budget and Budget Account it is mapped to, whether its Account Mapping is locked and why, whether it is identified, and whether it is a Retired Account.
3. Operator selects a Bank Account that waits for confirmation of a proposed match.
4. Slussen shows the proposed Bank Account as the Source describes it, with its name, kind, currency and the last four digits of its number, and the other Bank Accounts the Source offers.
5. Operator confirms the proposed Bank Account.
6. Slussen checks that no other Account Mapping already uses the confirmed Bank Account.
7. Slussen records the identity of the Bank Account, so that its transactions are used from the next Fetch on.
8. Slussen records the confirmation in the Decision Journal.

## Alternative Flows

### A1: Operator Chooses Another Offered Bank Account

**Trigger:** Operator chooses another of the offered Bank Accounts instead of the proposed one (step 5)  
**Flow:**

1. Use case continues at step 6 with the chosen Bank Account.

### A2: Bank Account Already Used

**Trigger:** Another Account Mapping already uses the confirmed Bank Account (step 6)  
**Flow:**

1. Slussen refuses the confirmation and shows which Account Mapping already uses it.
2. Use case continues at step 4.

### A3: Operator Rejects the Proposal

**Trigger:** Operator rejects the proposed Bank Account (step 5)  
**Flow:**

1. Slussen records no identity and keeps the Bank Account's transactions out of use.
2. Slussen shows the Blocked Reason "unrecognised Bank Account".
3. Slussen records the rejection in the Decision Journal.
4. Use case ends.

### A4: Retire a Bank Account

**Trigger:** Operator chooses to retire a Bank Account (step 3)  
**Flow:**

1. Slussen marks the Bank Account as a Retired Account, so that it is no longer fetched, compared, matched or uploaded.
2. Slussen keeps its identity, its Account Mapping and its history.
3. Slussen records the change in the Decision Journal.
4. Use case ends.

### A5: Restore a Retired Account

**Trigger:** Operator chooses to restore a Retired Account (step 3)  
**Flow:**

1. Slussen restores the Bank Account with its identity, its Account Mapping and its history, so that it is fetched, compared and uploaded again.
2. Slussen records the change in the Decision Journal.
3. Use case ends.

### A6: Identify a Bank Account Again

**Trigger:** Operator asks to identify again a Bank Account whose Account Mapping is not locked (step 3)  
**Flow:**

1. Slussen keeps the current identity in use until the Operator confirms another one.
2. Slussen proposes a match from what the Source last delivered, without contacting the Source.
3. Use case continues at step 4.

### A7: Account Mapping Locked

**Trigger:** Operator asks to identify again a Bank Account whose Account Mapping is locked (step 3)  
**Flow:**

1. Slussen refuses and shows that transactions were already uploaded through this Account Mapping.
2. Use case ends.

### A8: Build Configuration Changes a Locked Account Mapping

**Trigger:** The Build Configuration maps a Bank Account with a locked Account Mapping to another Budget Account (step 2)  
**Flow:**

1. Slussen keeps the locked Account Mapping, uploads nothing to the other Budget Account, and shows the reason that transactions were already uploaded through it.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- Every confirmed Bank Account is identified with certainty and sends its transactions to exactly one Budget Account.
- A Retired Account keeps its identity, Account Mapping and history.
- Every change is recorded in the Decision Journal.

### Failure Postconditions

- No Bank Account's transactions are used under an identity the Operator did not confirm or Slussen did not establish with certainty.
- No locked Account Mapping changes its Budget Account.

## Business Rules

### BR-001: One to One

A Bank Account has at most one Account Mapping, and a Budget Account receives the transactions of at most one Bank Account. A Manually Kept Account has no Account Mapping.

### BR-002: Mappings Come From the Build Configuration

Bank Accounts, Budgets and Account Mappings are set in the Build Configuration. Slussen offers no way to add a Bank Account or to choose a Budget Account inside the app.

### BR-003: Lock After the First Upload

Once a transaction has been uploaded through an Account Mapping, the Account Mapping is locked: its Budget Account never changes, and the reason is shown wherever a change is refused.

### BR-004: Never Guess a Bank Account

Slussen uses a Bank Account's transactions only when it identifies the Bank Account with certainty. When it can only propose a likely Bank Account, it asks the Operator to confirm. When two Bank Accounts are possible, or two Account Mappings would point to the same Bank Account, it stops for that Bank Account and shows why.

### BR-005: Retire and Restore

A Retired Account is neither fetched, compared, matched nor uploaded. It keeps its identity, Account Mapping and history and can be restored at any time.

### BR-006: Identities Survive a Reinstall

The identities and Account Mappings of the Bank Accounts are included in device backups and restored after a reinstall.

### BR-007: Identify Again Only Before the Lock

A Bank Account can be identified again only while its Account Mapping is not locked. Until the Operator confirms a new identity, the current one stays in use.
