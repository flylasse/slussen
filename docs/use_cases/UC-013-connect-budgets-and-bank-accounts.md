# Use Case: Connect Budgets and Bank Accounts

## Overview

**Use Case ID:** UC-013  
**Use Case Name:** Connect Budgets and Bank Accounts  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App, Data Provider  
**Goal:** Enter the service credentials, choose the Budgets Slussen serves and add Bank Accounts by pairing each with a Budget Account, so that their transactions are fetched, compared and proposed for Upload without rebuilding the app  
**Trigger:** Operator asks to connect Budgets and Bank Accounts, chooses the Budgets in use on the Budget App's page in Connections, or opens Slussen while no Bank Account is added yet  
**Status:** Approved  

**Requirements:** [FR-056, FR-058, FR-066, FR-075, FR-097, FR-098, FR-099, FR-103, NFR-010, NFR-017, NFR-020, NFR-021, C-012](../requirements.md)

## Preconditions

- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows whether the service credentials of the Budget App and of each Data Provider are entered and accepted, and the Operator enters those that are missing (BR-008, UC-014).
2. Slussen reads the list of Budgets from the Budget App and shows it, marking the Budgets it already serves.
3. Operator chooses the Budgets Slussen serves.
4. Slussen stores the choice and reads the Budget Accounts of each chosen Budget with their balances.
5. Slussen shows, for each Source, the Offered Bank Accounts that are not yet added, each with its name or product, kind, currency, last four digits and balance, next to the Budget Accounts of the chosen Budgets that receive through no Account Mapping, each with its name and balance (BR-002).
6. Operator pairs an Offered Bank Account with a Budget Account.
7. Slussen checks that the Budget Account receives through no other Account Mapping and that its Budget has the Bank Account's currency.
8. Slussen adds the Bank Account with an identifier it generates (BR-003), records how the Source identifies it, proposes its name (BR-004) and stores its Account Mapping.
9. Operator accepts or edits the name and optionally sets a Start Date (BR-005).
10. Slussen stores them and records the addition in the Decision Journal.
11. Slussen shows the Bank Account as added; its transactions are fetched and compared from the next Fetch on (UC-001).

## Alternative Flows

### A1: Budget App Cannot Be Read or Refuses Slussen's Credentials

**Trigger:** The Budget App does not answer, is unavailable, or answers that the service credentials the Operator entered are not valid or not allowed (step 2)  
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

1. Operator chooses its File Format and Bank, optionally the Offered Bank Account of a Data Provider that reports its balance (BR-006), and the Budget Account.
2. Use case continues at step 7.

### A5: Budget Account Already Mapped or in Another Currency

**Trigger:** The chosen Budget Account receives through another Account Mapping, or its Budget has another currency than the Bank Account (step 7)  
**Flow:**

1. Slussen refuses the pairing and shows why; no amount is ever converted (UC-003 BR-013).
2. Use case continues at step 6.

### A6: Offered Bank Account Probably Already Added

**Trigger:** An Offered Bank Account is probably an already added Bank Account whose identification changed (step 5)  
**Flow:**

1. Slussen shows it as a proposed match of that Bank Account, to be confirmed in Manage Account Mappings (UC-009), and not as a Bank Account to add (UC-009 BR-004).
2. Use case continues at step 5.

### A7: Budget App Credentials Not Entered

**Trigger:** The Operator does not enter accepted credentials for the Budget App (step 1)  
**Flow:**

1. Slussen shows that Budgets can be chosen once the Budget App's credentials are accepted.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Budget App and the Data Providers the Operator uses have accepted credentials, stored only on the device.
- Slussen serves the Budgets the Operator chose.
- Each paired Bank Account is added with its generated identifier, its name, its optional Start Date and its Account Mapping, and its transactions are fetched and compared from the next Fetch on.
- Every change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- No Bank Account is added without the Operator's pairing.
- No Budget Account receives through two Account Mappings, and no Bank Account is mapped into a Budget of another currency.
- Nothing is written to the Budget App.

## Business Rules

### BR-001: Budgets Are Chosen in the App

Slussen serves only the Budgets the Operator chose from those the Budget App lists; only these are read, compared and shown. A Budget stops being served only when no Bank Account is mapped into it.

### BR-002: Pairing Shows Both Sides

For each Offered Bank Account Slussen shows its name or product, kind, currency, the last four digits of its number (UC-009 BR-009) and its balance; for each Budget Account its name and balance. Only Budget Accounts of served Budgets that receive through no Account Mapping are offered for pairing.

### BR-003: Slussen Generates the Identifier

When a Bank Account is added, Slussen generates its identifier, as unlikely to collide as a random UUID (NFR-020), and records how the Source identifies the Bank Account (UC-009 BR-004, UC-009 BR-010).

### BR-004: Proposed Name

Slussen proposes the name of a Bank Account from what its Source delivers, its name or product with the last four digits of its number. The Operator can edit it now or at any time later (UC-009 A12); the name never identifies the Bank Account (UC-009 BR-011).

### BR-005: Start Date in the App

The Start Date of a Bank Account is optional and is set or changed in the app (UC-009 A13). When another import path already fed the Budget Account, the Operator sets it so that Slussen offers nothing from before it (UC-003 BR-018, UC-012 BR-007).

### BR-006: Bank Accounts of a File Format

A Bank Account whose transactions come from Transaction Files is added by choosing its File Format, its Bank and, when a Data Provider reports its balance, that Data Provider's Offered Bank Account (UC-001 BR-011), or when its first Transaction File is imported (UC-002 A7). Slussen proposes no name from a file; the Operator enters it.

### BR-007: Kept Across a Reinstall, Restore or Upgrade

The chosen Budgets and each added Bank Account with its identifier, name, Start Date and Account Mapping are Decision-Critical Data: they are included in device backups, restored after a reinstall and kept on an upgrade of the same build (UC-009 BR-006).

### BR-008: First-Run Order

Setting up Slussen goes in this order: enter the service credentials of the Budget App and of the Data Providers (UC-014); choose the Budgets; then add Bank Accounts by pairing them, granting a Bank Consent first where a Data Provider needs one to offer its Bank Accounts (UC-007, A3). Later, each of these can be done again on its own: the credentials on each service's page in Connections (UC-014), the Budgets from the Budget App's page, which shows the Budgets in use and leads to their choice (step 2), and the Bank Accounts from Settings.
