# Use Case: Connect Budgets and Bank Accounts

## Overview

**Use Case ID:** UC-013  
**Use Case Name:** Connect Budgets and Bank Accounts  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App, Data Provider  
**Goal:** Enter the service credentials, choose the Budgets Slussen serves and add Bank Accounts by pairing each with a Budget Account, so that their transactions are fetched, compared and proposed for Upload without rebuilding the app  
**Trigger:** Operator asks to connect Budgets and Bank Accounts, chooses the Budgets in use on the Budget App's page in Connections, or opens Slussen while no Bank Account is added yet  
**Status:** Approved  

**Requirements:** [FR-056, FR-058, FR-066, FR-075, FR-097, FR-098, FR-099, FR-103, FR-105, FR-106, NFR-004, NFR-010, NFR-017, NFR-020, NFR-021, NFR-022, C-012](../requirements.md)

## Preconditions

- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows whether the service credentials of the Budget App and of each Data Provider are entered and accepted, and the Operator enters those that are missing (BR-008, UC-014).
2. Slussen reads the list of Budgets from the Budget App and shows it, marking the Budgets it already serves.
3. Operator chooses the Budgets Slussen serves.
4. Slussen stores the choice and reads the Budget Accounts of each chosen Budget with their balances.
5. Slussen shows, grouped by Source and by Bank, the Offered Bank Accounts that are neither added nor left out, each with its name or product, kind, currency, last four digits and balance and a choice to bring it in, none of them chosen (BR-002, BR-012), and below them, collapsed, the Left-Out Bank Accounts (BR-011).
6. Operator chooses the Offered Bank Accounts to bring in and continues.
7. Slussen states how many of the shown Offered Bank Accounts are brought in and how many are left out (BR-012).
8. Operator confirms.
9. Slussen records each shown Offered Bank Account that was not chosen as a Left-Out Bank Account, records them in the Decision Journal (BR-011), and takes the chosen ones one at a time, showing which one of how many is current.
10. Slussen proposes, for the current Offered Bank Account, the Budget Account that fits it best (BR-013), lists the other free Budget Accounts of its currency and, on request, every free Budget Account of each served Budget, each with its name and balance, and proposes the name of the Bank Account (BR-004), its Bank Account type and its Bank (BR-009).
11. Operator accepts the proposed Budget Account or chooses another, accepts or edits the name, confirms or changes the Bank Account type and the Bank, states the currency when the Offered Bank Account states none (BR-010), optionally sets a Start Date (BR-005), and asks Slussen to add the Bank Account and continue.
12. Slussen checks that the Budget Account receives through no other Account Mapping and that its Budget has the currency of the Bank Account, stated by the Data Provider or by the Operator (BR-010).
13. Slussen adds the Bank Account with an identifier it generates (BR-003), records how the Source identifies it, stores its name, currency, Bank Account type, Bank, Start Date and Account Mapping, and records the addition in the Decision Journal.
14. Slussen repeats steps 10 to 13 for each further chosen Offered Bank Account.
15. Slussen shows which Bank Accounts were added, which were skipped for now and how many were left out; the transactions of the added Bank Accounts are fetched and compared from the next Fetch on (UC-001).

## Alternative Flows

### A1: Budget App Cannot Be Read or Refuses Slussen's Credentials

**Trigger:** The Budget App does not answer, is unavailable or cannot be reached from the device (NFR-004), or answers that the service credentials the Operator entered are not valid or not allowed (step 2)  
**Flow:**

1. Slussen keeps the Budgets it serves unchanged and shows that the Budget App could not be read, or the Blocked Reason "credentials refused" with the action that removes it (UC-014).
2. Use case ends.

### A2: Stop Serving a Budget That Has Mapped Bank Accounts

**Trigger:** Operator removes from the chosen Budgets a Budget into which a Bank Account is mapped (step 3)  
**Flow:**

1. Slussen refuses and names the Bank Accounts mapped into that Budget, whose Account Mappings the Operator removes first (UC-009 A11).
2. Use case continues at step 3.

### A3: No Offered Bank Accounts for a Source

**Trigger:** No Offered Bank Account of a Source is stored, for example before its first Fetch or Bank Consent (step 5)  
**Flow:**

1. Slussen offers to start a Fetch of that Data Provider (UC-001) or, for a Data Provider that needs a Bank Consent, to grant the Bank Consent of a Bank (UC-007).
2. Use case continues at step 5.

### A4: Bank Account of a File Format

**Trigger:** Operator adds a Bank Account whose transactions come from Transaction Files (step 6)  
**Flow:**

1. Operator chooses its File Format, its Bank Account type and its Bank, optionally the Offered Bank Account of a Data Provider that reports its balance (BR-006, BR-009), the Budget Account, its name and optionally a Start Date (BR-005).
2. Slussen checks the Budget Account as at step 12 (A5) and adds the Bank Account as at step 13.
3. Use case continues at step 5.

### A5: Budget Account Already Mapped or in Another Currency

**Trigger:** The chosen Budget Account receives through another Account Mapping, or its Budget has another currency than the Bank Account, stated by the Data Provider or by the Operator (step 12, BR-010)  
**Flow:**

1. Slussen refuses the pairing and shows why; no amount is ever converted (UC-003 BR-013).
2. Use case continues at step 11.

### A6: Offered Bank Account Probably Already Added

**Trigger:** An Offered Bank Account is probably an already added Bank Account whose identification changed (step 5)  
**Flow:**

1. Slussen shows it as a proposed match of that Bank Account, to be confirmed in Manage Account Mappings (UC-009), and not among the Offered Bank Accounts to bring in, so that it is neither brought in nor left out (UC-009 BR-004).
2. Use case continues at step 5.

### A7: Budget App Credentials Not Entered

**Trigger:** The Operator does not enter accepted credentials for the Budget App (step 1)  
**Flow:**

1. Slussen shows that Budgets can be chosen once the Budget App's credentials are accepted.
2. Use case ends.

### A8: Skip for Now

**Trigger:** Operator skips the current Offered Bank Account for now (step 11)  
**Flow:**

1. Slussen adds nothing for it and does not leave it out, so that it is offered again at step 5 the next time (BR-012).
2. Use case continues at step 14.

### A9: Mark a Budget Account Manually Kept

**Trigger:** Operator marks one of the listed Budget Accounts as kept by hand (step 11)  
**Flow:**

1. Slussen marks it as a Manually Kept Account, no longer offers it for pairing, also in the proposals that follow (BR-014), and records the change in the Decision Journal.
2. Use case continues at step 10.

### A10: Bring Back a Left-Out Bank Account

**Trigger:** Operator opens the Left-Out Bank Accounts and brings one back (step 5)  
**Flow:**

1. Slussen no longer leaves it out and records the change in the Decision Journal (BR-011).
2. Slussen shows it among the Offered Bank Accounts to choose from when the Data Provider's latest delivery holds it, and otherwise from the next delivery that holds it.
3. Use case continues at step 5.

### A11: No Offered Bank Account Left to Decide

**Trigger:** Every stored Offered Bank Account is added, left out or a proposed match of an added Bank Account (step 5)  
**Flow:**

1. Slussen shows that there is nothing new to bring in, with the Left-Out Bank Accounts collapsed below, the actions that fetch a Data Provider again (UC-001) or grant a Bank Consent (UC-007), and the action that adds a Bank Account of a File Format (A4).
2. Use case ends.

### A12: Nothing Brought In

**Trigger:** Operator continues without choosing any Offered Bank Account (step 6)  
**Flow:**

1. Slussen states that none is brought in and how many are left out (BR-012).
2. Operator confirms, or goes back and the use case continues at step 6.
3. Slussen records each shown Offered Bank Account as a Left-Out Bank Account and records them in the Decision Journal (BR-011).
4. Use case continues at step 15.

### A13: Go Back Before Confirming

**Trigger:** Operator does not confirm the stated numbers and goes back (step 8)  
**Flow:**

1. Slussen leaves nothing out and keeps the choice as it was.
2. Use case continues at step 6.

## Postconditions

### Success Postconditions

- The Budget App and the Data Providers the Operator uses have accepted credentials, stored only on the device.
- Slussen serves the Budgets the Operator chose.
- Each paired Bank Account is added with its generated identifier, its name, its Bank Account type, its Bank, its optional Start Date and its Account Mapping, and its transactions are fetched and compared from the next Fetch on.
- Each shown Offered Bank Account the Operator did not choose is a Left-Out Bank Account, no longer offered for pairing and never read, until the Operator brings it back; each one chosen but skipped is offered again.
- Each Budget Account the Operator marked as kept by hand is a Manually Kept Account and is no longer offered for pairing.
- Every change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- No Bank Account is added without the Operator's pairing, or without a Bank Account type and a Bank the Operator confirmed or chose.
- No Offered Bank Account is left out without the Operator's confirmation of the stated numbers, and no proposed Budget Account is used without the Operator.
- No Budget Account receives through two Account Mappings, and no Bank Account is mapped into a Budget of another currency.
- Nothing is written to the Budget App.

## Business Rules

### BR-001: Budgets Are Chosen in the App

Slussen serves only the Budgets the Operator chose from those the Budget App lists; only these are read, compared and shown. A Budget stops being served only when no Bank Account is mapped into it.

### BR-002: Pairing Shows Both Sides

For each Offered Bank Account Slussen shows its name or product, kind, currency, the last four digits of its number (UC-009 BR-009) and its balance, grouped by Source and by Bank; for each Budget Account its name and balance. Only Budget Accounts of served Budgets that receive through no Account Mapping and are not Manually Kept Accounts are offered for pairing (BR-014).

### BR-003: Slussen Generates the Identifier

When a Bank Account is added, Slussen generates its identifier, as unlikely to collide as a random UUID (NFR-020), and records how the Source identifies the Bank Account (UC-009 BR-004, UC-009 BR-010).

### BR-004: Proposed Name

Slussen proposes the name of a Bank Account from what its Source delivers, its name or product with the last four digits of its number. The Operator can edit it now or at any time later (UC-009 A12); the name never identifies the Bank Account (UC-009 BR-011).

### BR-005: Start Date in the App

The Start Date of a Bank Account is optional and is set or changed in the app (UC-009 A13). When another import path already fed the Budget Account, the Operator sets it so that Slussen offers nothing from before it (UC-003 BR-018, UC-012 BR-007).

### BR-006: Bank Accounts of a File Format

A Bank Account whose transactions come from Transaction Files is added by choosing its File Format, its Bank Account type, its Bank and, when a Data Provider reports its balance, that Data Provider's Offered Bank Account (UC-001 BR-011), or when its first Transaction File is imported (UC-002 A7). Slussen proposes no name, Bank Account type or Bank from a file; the Operator enters the name and chooses the others (BR-009). The Offered Bank Accounts offered for the balance include the Left-Out ones; choosing one brings it back (BR-011).

### BR-007: Kept Across a Reinstall, Restore or Upgrade

The chosen Budgets and each added Bank Account with its identifier, name, Start Date and Account Mapping are Decision-Critical Data: they are included in device backups, restored after a reinstall and kept on an upgrade of the same build (UC-009 BR-006). The Left-Out Bank Accounts and the Manually Kept Accounts are kept the same way (NFR-017), though they are not Decision-Critical Data.

### BR-008: First-Run Order

Setting up Slussen goes in this order: enter the service credentials of the Budget App and of the Data Providers (UC-014); choose the Budgets; then add Bank Accounts by pairing them, granting a Bank Consent first where a Data Provider needs one to offer its Bank Accounts (UC-007, A3). Later, each of these can be done again on its own: the credentials on each service's page in Connections (UC-014), the Budgets from the Budget App's page, which shows the Budgets in use and leads to their choice (step 2), and the Bank Accounts from Settings.

### BR-009: Proposed Bank Account Type and Bank

Before a Bank Account is added, Slussen proposes its Bank Account type, one of Checking, Savings, Credit Card or Other, from the kind the Data Provider gives the Offered Bank Account, Other when that kind matches none of the first three, and its Bank, the Bank the Data Provider names for it. The Operator confirms or changes both: the Bank Account type from these four values, the Bank by choosing one Slussen already knows or naming a new one. When the Data Provider names no Bank, Slussen proposes none and the Operator chooses or names it. For a Bank Account of a File Format, Slussen proposes neither, and the Operator chooses the Bank Account type and chooses or names the Bank (A4, BR-006). No Bank Account is added without a Bank Account type and a Bank. The Bank Account type decides whether the Bank Account is a credit card, with a Funded By, or has a Transfer Partner (UC-005 BR-006). For a Data Provider that needs a Bank Consent, the Bank Account is always covered by the Bank Consent of the Bank the Data Provider names for it, whatever Bank the Operator confirms or chooses here, which is only how the Bank Account is named and grouped in Slussen (UC-007 BR-009).

### BR-010: Currency Stated by the Operator

When an Offered Bank Account states no currency, the Operator states the currency of the Bank Account at step 11; Slussen proposes the currency of the chosen Budget Account, which is that of its Budget. The pairing is then checked as for a currency the Data Provider states (step 12, A5): a Budget of another currency is refused, and no amount is ever converted (UC-003 BR-013). No Bank Account is added without a currency.

### BR-011: Left-Out Bank Accounts Are Remembered and Never Read

A shown Offered Bank Account that the Operator does not choose at step 6, once the Operator confirms the stated numbers (step 8, A12), becomes a Left-Out Bank Account. Slussen remembers it by its Data Provider and the Data Provider's reference, across later Fetches, Bank Consents, a reinstall with restore from a device backup and upgrades (NFR-017). It is no longer offered for pairing, never proposed as a match of an added Bank Account (UC-009 BR-004), and never read: a Fetch never asks for its transactions or its balance (UC-001 A18), though it may still appear in the list of Bank Accounts the Data Provider delivers. It is not a Bank Account: it has no Account Mapping and no identifier, and unlike a Retired Account it was never added. Slussen offers it again only when the Operator brings it back, here (A10), when choosing it for the balance of a Bank Account of a File Format (BR-006), or in Manage Account Mappings (UC-009 A15). Leaving it out and bringing it back are recorded in the Decision Journal with its name, never with the Data Provider's reference (NFR-012).

### BR-012: Nothing Chosen by Default, the Numbers Stated Before Leaving Out

At step 5 no Offered Bank Account is chosen to be brought in; the Operator chooses each one. Before anything is left out, Slussen states how many of the shown Offered Bank Accounts are brought in and how many are left out, and leaves them out only when the Operator confirms (steps 7-8, A12, A13). An Offered Bank Account that a later Fetch or Bank Consent delivers, and one the Operator chose but did not add, because it was skipped for now (A8) or the Operator stopped before reaching it, is neither added nor left out and is offered again at step 5. A proposed match of an added Bank Account (A6) is not shown to be chosen and is never left out.

### BR-013: The Proposed Budget Account

A Budget Account is free when it belongs to a served Budget, receives through no Account Mapping and is not a Manually Kept Account (BR-002). For the current Offered Bank Account, Slussen considers the free Budget Accounts of its currency, or every free Budget Account when it states no currency, and proposes the one whose balance equals the Offered Bank Account's balance, or, when no balance or more than one is equal, the only free Budget Account it considers. When neither gives exactly one, Slussen proposes none. It shows why it proposes one (equal balance, or the only free Budget Account of that currency). The other free Budget Accounts of that currency are listed below the proposal, and every free Budget Account of each served Budget can be reached from there. The proposal is never applied without the Operator, who accepts it or chooses another at step 11; it identifies nothing (UC-009 BR-004) and is checked like any other choice (step 12, A5).

### BR-014: A Manually Kept Account Is Not Offered for Pairing

The Operator can mark a free Budget Account as a Manually Kept Account at step 11 (A9); it then leaves the Budget Accounts offered for pairing and the proposals of BR-013. Only a Budget Account that receives through no Account Mapping can be marked (UC-009 BR-001). The Operator can unmark it in Manage Account Mappings (UC-009 A16), after which it is offered for pairing again. Slussen never writes to a Manually Kept Account and never fetches it (UC-005 BR-014).
