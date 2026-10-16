# Use Case: Manage Account Mappings

## Overview

**Use Case ID:** UC-009  
**Use Case Name:** Manage Account Mappings  
**Primary Actor:** Operator  
**Goal:** Have every Bank Account identified with certainty and sending its transactions to exactly one Budget Account, and pause or resume a Bank Account without losing anything  
**Trigger:** Operator asks to confirm, identify again, retire or restore a Bank Account, to set its Funded By or Transfer Partner, or to check how the Bank Accounts are mapped  
**Status:** Approved  

**Requirements:** [FR-042, FR-056, FR-057, FR-058, FR-059, FR-066, FR-075, FR-091, FR-096, NFR-009, NFR-010, NFR-017, C-012](../requirements.md)

## Preconditions

- The Build Configuration holds the Bank Accounts and their Account Mappings.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows each Bank Account with its Bank, its Source, the Budget and Budget Account it is mapped to, its Funded By when it is a credit card or its Transfer Partner otherwise, and whether that value no longer applies (UC-005 BR-014), its Start Date, whether its Account Mapping is locked and why, whether it is identified, and whether it is a Retired Account.
2. Operator selects a Bank Account that waits for confirmation of a proposed match.
3. Slussen shows, from the Offered Bank Accounts the Source last delivered and without contacting it (BR-009), the proposed Bank Account with its Bank, name, kind, currency and the last four digits of its number, and the other Offered Bank Accounts of the Source.
4. Operator confirms the proposed Bank Account.
5. Slussen checks that no other Account Mapping already uses the confirmed Bank Account.
6. Slussen records how the Source identifies the Bank Account, so that its transactions are used from the next Fetch on.
7. Slussen records the confirmation in the Decision Journal.

## Alternative Flows

### A1: Operator Chooses Another Offered Bank Account

**Trigger:** Operator chooses another of the Offered Bank Accounts instead of the proposed one (step 4)  
**Flow:**

1. Use case continues at step 5 with the chosen Bank Account.

### A2: Bank Account Already Used

**Trigger:** Another Account Mapping already uses the confirmed Bank Account (step 5)  
**Flow:**

1. Slussen refuses the confirmation and shows which Account Mapping already uses it.
2. Use case continues at step 3.

### A3: Operator Rejects the Proposal

**Trigger:** Operator rejects the proposed Bank Account (step 4)  
**Flow:**

1. Slussen records no identification and keeps the Bank Account's transactions out of use.
2. Slussen shows the Blocked Reason "unrecognised Bank Account".
3. Slussen records the rejection in the Decision Journal.
4. Use case ends.

### A4: Retire a Bank Account

**Trigger:** Operator chooses to retire a Bank Account (step 2)  
**Flow:**

1. Slussen marks the Bank Account as a Retired Account, so that it is no longer fetched, compared, matched or uploaded.
2. Slussen keeps its identification, its Account Mapping, its Start Date and its history.
3. Slussen records the change in the Decision Journal.
4. Use case ends.

### A5: Restore a Retired Account

**Trigger:** Operator chooses to restore a Retired Account (step 2)  
**Flow:**

1. Slussen restores the Bank Account with its identification, its Account Mapping, its Start Date and its history, so that it is fetched, compared and uploaded again.
2. Slussen records the change in the Decision Journal.
3. Use case ends.

### A6: Identify a Bank Account Again

**Trigger:** Operator asks to identify again a Bank Account whose Account Mapping is not locked (step 2)  
**Flow:**

1. Slussen keeps the current identification in use until the Operator confirms another one.
2. Slussen proposes a match from the Offered Bank Accounts the Source last delivered, without contacting the Source (BR-009).
3. Use case continues at step 3.

### A7: Account Mapping Locked

**Trigger:** Operator asks to identify again a Bank Account whose Account Mapping is locked (step 2)  
**Flow:**

1. Slussen refuses and shows that transactions were already sent through this Account Mapping.
2. Use case ends.

### A8: Build Configuration Changes a Locked Account Mapping

**Trigger:** The Build Configuration entry with the Configuration Key of a Bank Account whose Account Mapping is locked maps it to another Budget Account (step 1)  
**Flow:**

1. Slussen keeps the locked Account Mapping, uploads nothing to the other Budget Account, and shows the reason that transactions were already sent through it.
2. Use case continues at step 2.

### A9: Set, Change or Clear Funded By or Transfer Partner

**Trigger:** Operator chooses to set, change or clear the Funded By of a credit card Bank Account or the Transfer Partner of another Bank Account (step 2)
**Flow:**

1. Slussen shows the Budget Accounts of the same Budget, other than the Bank Account's own, that Slussen fetches (UC-005 BR-014).
2. Operator chooses one, or clears the value.
3. Slussen stores the Funded By or the Transfer Partner and detects the Transfers among the Bank Transactions not yet uploaded again (UC-005 BR-006).
4. Slussen records the old and the new value in the Decision Journal.
5. Use case ends.

### A10: No Offered Bank Accounts Stored

**Trigger:** No Offered Bank Account of the Source is stored, for example after a reinstall (step 3)  
**Flow:**

1. Slussen shows that the Bank Account can be identified after the next Fetch of its Data Provider or the next grant of its Bank Consent, and keeps the current identification in use.
2. Use case ends.

### A11: Bank Account No Longer in the Build Configuration

**Trigger:** The Build Configuration has no entry with the Configuration Key of a stored Bank Account, because the entry was removed or given a new Configuration Key (step 1)  
**Flow:**

1. Slussen keeps the Bank Account with its identification, history, Account Mapping and lock, neither fetches it nor offers its Bank Transactions for Upload, and shows it with the Blocked Reason "removed from the Build Configuration" (BR-011).
2. Use case continues at step 2.

### A12: New Configuration Key Points at a Locked Budget Account

**Trigger:** A Build Configuration entry with a Configuration Key that no stored Bank Account has maps its Bank Account to a Budget Account that already receives through a locked Account Mapping (step 1)  
**Flow:**

1. Slussen treats the entry as a new Bank Account, refuses its Account Mapping and keeps its transactions out of use.
2. Slussen shows the Blocked Reason "configuration error" with the locked Account Mapping that holds the Budget Account and the action that removes it: correct the Build Configuration (UC-012).
3. Use case continues at step 2.

## Postconditions

### Success Postconditions

- Every confirmed Bank Account is identified with certainty and sends its transactions to exactly one Budget Account.
- A Retired Account keeps its identification, Account Mapping, Start Date and history.
- Every change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- No Bank Account's transactions are used under an identification the Operator did not confirm or Slussen did not establish with certainty.
- No locked Account Mapping changes its Budget Account.

## Business Rules

### BR-001: One to One

A Bank Account has at most one Account Mapping, and a Budget Account receives the transactions of at most one Bank Account. A Manually Kept Account has no Account Mapping.

### BR-002: Mappings Come From the Build Configuration

Bank Accounts with their Start Dates, Budgets and Account Mappings are set in the Build Configuration. Slussen offers no way to add a Bank Account, to choose the Budget Account of an Account Mapping or to move a locked Account Mapping to another Budget Account inside the app. The Funded By and the Transfer Partner are not part of the Build Configuration; they are set in the app (BR-008).

### BR-003: Lock at the First Send

As soon as a transaction is recorded as sent through an Account Mapping (UC-006 BR-006), whatever the Budget App later answers, the Account Mapping is locked: its Budget Account never changes, and the reason is shown wherever a change is refused.

### BR-004: Never Guess a Bank Account

Slussen uses a Bank Account's transactions only when it identifies the Bank Account with certainty. When it can only propose a likely Bank Account, it asks the Operator to confirm. When two Bank Accounts are possible, or two Account Mappings would point to the same Bank Account, it stops for that Bank Account and shows why.

### BR-005: Retire and Restore

A Retired Account is neither fetched, compared, matched nor uploaded. It keeps its identification, Account Mapping, Start Date and history and can be restored at any time.

### BR-006: Identifications Survive a Reinstall, Restore or Upgrade

How the Source identifies each Bank Account, its Configuration Key, and the Account Mappings with their locks, are included in device backups, restored after a reinstall and kept on an upgrade of the same build.

### BR-007: Identify Again Only Before the Lock

A Bank Account can be identified again only while its Account Mapping is not locked. Until the Operator confirms a new identification, the current one stays in use.

### BR-008: Funded By and Transfer Partner Are Set in the App

A credit card Bank Account can have a Funded By and every other Bank Account a Transfer Partner (UC-005 BR-006). Both are always a Budget Account that Slussen fetches (UC-005 BR-014). Both start empty, have no default in the build or the Build Configuration, and are set, changed or cleared here or kept from the Operator's answer when reviewing Transfers (UC-005). They are included in device backups and kept across a reinstall, a restore and an upgrade with the Account Mappings (BR-006).

### BR-009: Offered Bank Accounts Are Kept Until the Next Delivery

The Bank Accounts a Data Provider offered in a Fetch (UC-001) or with a Bank Consent (UC-007) are kept as Offered Bank Accounts until the Data Provider next delivers its Bank Accounts, so that a Bank Account can be confirmed or identified again from stored data without contacting the Data Provider. Of each Bank Account number, only the last four digits are kept for display, the part of a Bank Account or card number that banks and card issuers conventionally show; the full number is never stored. Offered Bank Accounts are never included in device backups or transfers and are not restored after a reinstall.

### BR-010: Configuration Key Ties a Bank Account to Its Entry

Every Bank Account of the Build Configuration has a Configuration Key, set once when the Bank Account is first configured and never changed (UC-012 BR-011). Slussen matches each stored Bank Account to its Build Configuration entry by this key alone, never by name or position, so that renaming or reordering entries keeps its identification, Account Mapping, lock and history. A locked Account Mapping stays as it is even when the entry with its Configuration Key maps the Bank Account to another Budget Account (A8).

### BR-011: Removed Entries and New Keys

A stored Bank Account whose Configuration Key no Build Configuration entry has, because the entry was removed or given a new Configuration Key, is kept with its identification, history, Account Mapping and lock. It is neither fetched nor offered for Upload and stays visible with the reason; it is in use again when an entry with its Configuration Key returns. An entry with a new Configuration Key is a new Bank Account. Its Account Mapping is refused when it points at a Budget Account that already receives through a locked Account Mapping, also one of a Bank Account that is out of use this way, so that editing the Build Configuration never bypasses a lock (BR-003).
