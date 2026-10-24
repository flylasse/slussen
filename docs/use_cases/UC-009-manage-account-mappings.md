# Use Case: Manage Account Mappings

## Overview

**Use Case ID:** UC-009  
**Use Case Name:** Manage Account Mappings  
**Primary Actor:** Operator  
**Goal:** Have every added Bank Account identified with certainty and sending its transactions to exactly one Budget Account, change a mapping only deliberately, and pause or resume a Bank Account without losing anything  
**Trigger:** Operator asks to confirm, identify again, rename, retire or restore a Bank Account, to change its Bank Account type or its Bank, to move or remove its Account Mapping or map it again, to set its Start Date, Funded By or Transfer Partner, to bring back a Left-Out Bank Account, to mark or unmark a Manually Kept Account, or to check how the Bank Accounts are mapped  
**Status:** Approved  

**Requirements:** [FR-042, FR-056, FR-058, FR-059, FR-066, FR-075, FR-091, FR-099, FR-100, FR-103, FR-105, FR-106, NFR-009, NFR-010, NFR-017, NFR-020, C-009, C-012](../requirements.md)

## Preconditions

- The Operator has added at least one Bank Account (UC-013).
- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows, after the imported Bank Accounts still to map, each on one line and each mapped as Connect Budgets and Bank Accounts describes (UC-013 steps 8-15, BR-014), each Bank Account grouped by the Budget it is mapped into, with its name, its Bank, its Bank Account type, its Source, the Budget and Budget Account it is mapped to, its Funded By when it is a credit card or its Transfer Partner otherwise, and whether that value no longer applies (UC-005 BR-014), its Start Date, whether transactions were sent through its Account Mapping, whether it is identified, and whether it is a Retired Account; apart from the Bank Accounts, it shows the Left-Out Bank Accounts of each Data Provider as not imported, when there is at least one, and the Manually Kept Accounts of each served Budget as kept by hand (BR-013).
2. Operator selects a Bank Account that waits for confirmation of a proposed match.
3. Slussen shows, from the Offered Bank Accounts the Source last delivered and without contacting it (BR-009), the proposed Bank Account with its Bank, name, kind, currency and the last four digits of its number, and the other Offered Bank Accounts of the Source that are not Left-Out Bank Accounts (BR-004).
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

**Trigger:** Operator asks to identify again a Bank Account through whose Account Mapping no transaction was sent, or every transaction sent was rejected by the Budget App (step 2)  
**Flow:**

1. Slussen keeps the current identification in use until the Operator confirms another one.
2. Slussen proposes a match from the Offered Bank Accounts the Source last delivered, without contacting the Source (BR-009).
3. Use case continues at step 3.

### A7: Identify Again After Transactions Were Sent

**Trigger:** Operator asks to identify again a Bank Account through whose Account Mapping a transaction was sent that was written, already present or has an unknown outcome (step 2)  
**Flow:**

1. Slussen asks the Operator to confirm, stating how many of these transactions were sent through the Account Mapping, that they stay as they are and that Slussen never sends them again, and, when the Budget App rejected some, their number and that they are sent again at their next Upload (BR-007, UC-006 BR-020).
2. Operator confirms, or cancels and the use case ends.
3. Use case continues at step 1 of A6.

### A8: Move an Account Mapping or Map a Bank Account Again

**Trigger:** Operator chooses to map a Bank Account to another Budget Account, or to map a Bank Account that has no Account Mapping (A11) (step 2)  
**Flow:**

1. Slussen shows the Budget Accounts of the Budgets it serves that receive through no Account Mapping and are in the Bank Account's currency.
2. Operator chooses one.
3. When the Bank Account has an Account Mapping and a transaction sent through it was written, already present or has an unknown outcome, Slussen asks the Operator to confirm, stating their number, that they stay in the current Budget Account, that Slussen never sends them again, and that the Operator moves or deletes them in the Budget App, and, when the Budget App rejected some of them, their number and that they go to the new Budget Account at their next Upload (BR-003). When the Bank Account has no Account Mapping, no transaction was sent through it, or the Budget App rejected every one, Slussen asks nothing and continues at step 5.
4. Operator confirms, or cancels and the use case ends.
5. Slussen moves the Account Mapping, or stores the new Account Mapping of a Bank Account that had none, records the change with that number in the Decision Journal, and gives the Bank Transactions their Transaction Status again (UC-003).
6. Use case ends.

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

### A11: Remove an Account Mapping

**Trigger:** Operator chooses to remove the Account Mapping of a Bank Account (step 2)  
**Flow:**

1. When a transaction sent through it was written, already present or has an unknown outcome, Slussen asks the Operator to confirm as in step 3 of A8, stating for the rejected ones that they wait until the Bank Account is mapped again. When no transaction was sent through it, or the Budget App rejected every one, Slussen asks nothing and continues at step 3 (BR-003).
2. Operator confirms, or cancels and the use case ends.
3. Slussen removes the Account Mapping and keeps the Bank Account with its identification and history, neither compared nor offered for Upload until the Operator maps it again (A8).
4. Slussen records the change in the Decision Journal.
5. Use case ends.

### A12: Rename a Bank Account

**Trigger:** Operator edits the name of a Bank Account (step 2)  
**Flow:**

1. Slussen stores the new name, changes nothing else, and records the change in the Decision Journal (BR-011).
2. Use case ends.

### A13: Set, Change or Clear the Start Date

**Trigger:** Operator sets, changes or clears the Start Date of a Bank Account (step 2)  
**Flow:**

1. Slussen stores the Start Date, records the change in the Decision Journal, and gives the Bank Transactions of the Bank Account their Transaction Status again (UC-003 BR-018).
2. Use case ends.

### A14: Change the Bank Account Type or the Bank

**Trigger:** Operator chooses to change the Bank Account type or the Bank of a Bank Account (step 2)  
**Flow:**

1. Slussen shows the current Bank Account type and Bank, the four Bank Account types and the Banks it already knows (BR-012).
2. Operator chooses another Bank Account type, chooses another known Bank or names a new one, or cancels and the use case ends.
3. When the new Bank Account type turns a credit card into another Bank Account, or another Bank Account into a credit card, and the Bank Account has a Funded By or a Transfer Partner, Slussen states that this value will be cleared, because a Funded By belongs only to a credit card and a Transfer Partner only to another Bank Account (UC-005 BR-006); the Operator saves, or cancels and the use case ends.
4. Slussen stores the Bank Account type and the Bank, clears a Funded By or Transfer Partner that the new Bank Account type does not allow, records the old and the new values in the Decision Journal, and detects the Transfers among the Bank Transactions not yet uploaded again (UC-005 BR-006).
5. Use case ends.

### A15: Bring Back a Left-Out Bank Account

**Trigger:** Operator chooses one of the Left-Out Bank Accounts shown as not imported and imports it again with its Data Provider's Offered Bank Accounts (step 2)  
**Flow:**

1. Slussen no longer leaves it out and records the change in the Decision Journal (UC-013 BR-011).
2. Slussen shows that it is imported again and is a Bank Account to map (UC-013 BR-012), from the Data Provider's latest delivery when it holds the Bank Account and otherwise from the next delivery that does.
3. Use case ends.

### A16: Mark or Unmark a Manually Kept Account

**Trigger:** Operator chooses the Manually Kept Accounts shown as kept by hand and, with the Budget Accounts of the served Budgets, chooses that a Budget Account that receives through no Account Mapping no longer receives from Slussen, or that a Manually Kept Account receives from Slussen again (step 2)  
**Flow:**

1. Slussen marks the Budget Account as a Manually Kept Account or unmarks it; a marked one is no longer offered for pairing, an unmarked one is offered again (UC-013 BR-014, UC-013 A9).
2. Slussen records the change in the Decision Journal.
3. Use case ends.

## Postconditions

### Success Postconditions

- Every confirmed Bank Account is identified with certainty and sends its transactions to exactly one Budget Account.
- A Retired Account keeps its identification, Account Mapping, Start Date and history.
- A Left-Out Bank Account the Operator brought back, and a Budget Account the Operator unmarked, are offered for pairing again; a marked Manually Kept Account is not.
- Every change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- No Bank Account's transactions are used under an identification the Operator did not confirm or Slussen did not establish with certainty.
- No Account Mapping through which a transaction was written, found already present or sent with an unknown outcome is moved or removed, and its Bank Account is not identified again, without the Operator's confirmation.
- No transaction Slussen sent becomes eligible again because of a change made here.

## Business Rules

### BR-001: One to One

A Bank Account has at most one Account Mapping, and a Budget Account receives the transactions of at most one Bank Account. A Manually Kept Account has no Account Mapping. To swap the Budget Accounts of two Bank Accounts, the Operator first removes one Account Mapping (A11).

### BR-002: Mappings Are Set in the App

Bank Accounts are added and mapped in the app (UC-013), those of a Source here among the Bank Accounts to map and those of a File Format on the File Format's page (UC-013 BR-006), and changed here. No build is given any of them (UC-012 BR-011).

### BR-003: Moving a Mapping After a Send Is Deliberate

Slussen records the time of the first send through an Account Mapping (UC-006 BR-006), whatever the Budget App answers. Once a transaction sent through it has the outcome Written, Already Present, Not Yet Known or Unknown, the Account Mapping is moved or removed only by an action the Operator confirms, after Slussen has stated how many transactions were sent through it; Slussen never does it on its own. While the Budget App has rejected every transaction sent through it, nothing was written, and the Account Mapping is moved or removed without that confirmation, as before any send; the rejected transactions follow the current Account Mapping (UC-006 BR-020) and, after a removal, wait until the Bank Account is mapped again. The transactions already sent stay where they are in the Budget App, keep their Sent Records and are never sent again; the Operator moves or deletes them in the Budget App. Later Comparisons treat them by the existing rules (UC-003 BR-021): found by their Import Identifier, they are Already In Budget App; deleted there, they go to the Re-upload decision (UC-004 BR-003), which sends them under a new Import Identifier, so that the Budget App's refusal of a reused Import Identifier (C-009) does not stop them; a resemblance goes to review. A Sent Record whose outcome is unknown at the move keeps its Budget Account, and any later send of it goes there (UC-006 BR-007). A transaction the Budget App rejected was not written: it is not counted among the transactions that stay, Slussen states their number separately, and its next send goes to the Budget Account of the current Account Mapping (UC-006 BR-020). A transaction counts as sent through the Account Mapping when it was sent from the Bank Account to the Budget Account the Account Mapping now names, also through an earlier Account Mapping of the Bank Account to that Budget Account, for example one removed (A11) and mapped again (A8); the time of the first send records when sending began and does not decide whether Slussen asks.

### BR-004: Never Guess a Bank Account

Slussen uses a Bank Account's transactions only when it identifies the Bank Account with certainty. When it can only propose a likely Bank Account, it asks the Operator to confirm. When two Bank Accounts are possible, or two Account Mappings would point to the same Bank Account, it stops for that Bank Account and shows why. This holds also when a Source later delivers a changed list of Bank Accounts. A Left-Out Bank Account (UC-013 BR-011) is never proposed as a likely Bank Account, never offered to confirm or identify a Bank Account, and never identified as one, until the Operator brings it back (A15, UC-013 A10).

### BR-005: Retire and Restore

A Retired Account is neither fetched, compared, matched nor uploaded. It keeps its identification, Account Mapping, Start Date and history and can be restored at any time.

### BR-006: Identifications Survive a Reinstall, Restore or Upgrade

The chosen Budgets, each Bank Account with its generated identifier, name and Start Date, how the Source identifies it, and the Account Mappings with the time of their first send are included in device backups, restored after a reinstall and kept on an upgrade of the same build.

### BR-007: Identify Again Deliberately

A Bank Account can be identified again at any time. Once a transaction sent through its Account Mapping has the outcome Written, Already Present, Not Yet Known or Unknown, Slussen first asks the Operator to confirm, stating their number (A7), counted as BR-003 describes. While the Budget App has rejected every transaction sent through it, nothing was written and Slussen asks nothing, as before any send (A6); the rejected transactions are sent again at their next Upload (UC-006 BR-020). Until the Operator confirms a new identification, the current one stays in use.

### BR-008: Funded By and Transfer Partner Are Set in the App

A credit card Bank Account can have a Funded By and every other Bank Account a Transfer Partner (UC-005 BR-006). Both are always a Budget Account that Slussen fetches (UC-005 BR-014). Both start empty, have no default, and are set, changed or cleared here or kept from the Operator's answer when reviewing Transfers (UC-005). They are included in device backups and kept across a reinstall, a restore and an upgrade with the Account Mappings (BR-006).

### BR-009: Offered Bank Accounts Are Kept Until the Next Delivery

The Bank Accounts a Data Provider offered in a Fetch (UC-001) or with a Bank Consent (UC-007) are kept as Offered Bank Accounts until the Data Provider next delivers its Bank Accounts, so that a Bank Account can be added (UC-013), confirmed or identified again from stored data without contacting the Data Provider. Of each Bank Account number, only the last four digits are kept for display, the part of a Bank Account or card number that banks and card issuers conventionally show; the full number is never stored. Offered Bank Accounts are never included in device backups or transfers and are not restored after a reinstall.

### BR-010: A Generated Identifier Ties Everything to a Bank Account

Every Bank Account has an identifier that Slussen generates when the Bank Account is added (UC-013 BR-003), as unlikely to collide as a random UUID (NFR-020), and that never changes. Its history, Account Mapping, Sent Records and decisions are tied to this identifier, so that renaming the Bank Account or moving its Account Mapping keeps them.

### BR-011: A Name Never Identifies a Bank Account

The name of a Bank Account is proposed from what its Source delivers (UC-013 BR-004), and the Operator can edit it at any time. Slussen never uses the name to identify a Bank Account or to match its transactions.

### BR-012: Bank Account Type and Bank Can Be Changed

The Operator can change the Bank Account type of a Bank Account, one of Checking, Savings, Credit Card or Other, and its Bank, by choosing a Bank Slussen already knows or naming a new one, with the same values and choice as when adding it (UC-013 BR-009). The change touches no Account Mapping, no identification, no Import Identifier and no Sent Record, so Slussen asks for none of the confirmations of BR-003 and BR-007, whatever was sent through the Account Mapping. A change of the Bank Account type applies only to Transfers none of whose Transfer Legs has been uploaded (UC-005 BR-006); an uploaded transaction keeps its outcome. Like every change here, it is not possible while an Upload Plan is awaiting confirmation or an Upload is being written (UC-006 BR-005). For a Data Provider that needs a Bank Consent, a change of the Bank changes neither the Bank Consent that covers the Bank Account nor the Bank where it is granted, which stay those of the Bank the Data Provider names for it (UC-007 BR-009).

### BR-013: Left-Out Bank Accounts and Manually Kept Accounts

The Left-Out Bank Accounts (UC-013 BR-011) are listed for each Data Provider, by the name they had when they were left out, and the Manually Kept Accounts (UC-013 BR-014) for each served Budget, apart from the Bank Accounts, both read-only. A Left-Out Bank Account is brought back where its Data Provider's Offered Bank Accounts are shown, by importing it again (A15, UC-013 A10), and a Manually Kept Account is marked and unmarked where the Budget App's Budget Accounts are shown (A16, UC-013 A9); the lists here lead there. Bringing a Left-Out Bank Account back, and marking or unmarking a Manually Kept Account, change no Bank Account, Account Mapping, identification, Import Identifier or Sent Record, so Slussen asks for none of the confirmations of BR-003 and BR-007. Only a Budget Account that receives through no Account Mapping can be marked (BR-001). Both are included in device backups, restored after a reinstall and kept on an upgrade (NFR-017). Like every change here, neither is possible while an Upload Plan is awaiting confirmation or an Upload is being written (UC-006 BR-005).

### BR-014: What the Account Mappings List

The Account Mappings are opened from their entry in Settings once UC-013 BR-015 holds; there is no other entry for Bank Accounts. They list, in this order: the imported Bank Accounts to map (UC-013 BR-012), each on one line with its name, the last four digits of its number, its Source, its currency and its balance, each opening the pairing of one Bank Account at a time (UC-013 steps 9-15) with the free Budget Accounts of its currency and every other free Budget Account one step away (UC-013 BR-013); then the Bank Accounts by the Budget they are mapped into, those not mapped and the Retired Accounts; then, only when there is at least one, two short read-only lists: the Left-Out Bank Accounts as not imported, leading to their Data Provider's page (A15), and the Manually Kept Accounts as kept by hand, leading to the Budget App's page (A16). No Budget Account is marked or unmarked from the Account Mappings or from the pairing of one Bank Account (UC-013 BR-014). A Bank Account of a File Format is never among the Bank Accounts to map: it is added already mapped on its File Format's page (UC-013 A4, BR-006). Nothing is fetched or uploaded for a Bank Account to map until it is added (UC-013 BR-012).
