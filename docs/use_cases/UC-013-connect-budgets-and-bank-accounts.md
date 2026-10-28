# Use Case: Connect Budgets and Bank Accounts

## Overview

**Use Case ID:** UC-013  
**Use Case Name:** Connect Budgets and Bank Accounts  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App, Data Provider  
**Goal:** Enter the service credentials, choose the Budgets Slussen serves and add Bank Accounts by importing them from their Sources and pairing each with a Budget Account, so that their transactions are fetched, compared and proposed for Upload without rebuilding the app  
**Trigger:** Operator asks in Settings to set up the Budget App, a Data Provider or a File Format, or to map the imported Bank Accounts, or follows the card that leads to Settings while no Bank Account is added yet  
**Status:** Approved  

**Requirements:** [FR-056, FR-058, FR-066, FR-075, FR-097, FR-098, FR-099, FR-103, FR-105, FR-106, NFR-004, NFR-010, NFR-017, NFR-020, NFR-021, NFR-022, C-012](../requirements.md)

## Preconditions

- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows the Budget App, each Data Provider and each File Format of the build, each with its status, and the Account Mappings with the number of Bank Accounts to map, available or with the reason they are not yet, and names what comes next (BR-008, BR-015); the Operator enters the service credentials that are missing (UC-014).
2. Slussen reads the list of Budgets from the Budget App and shows it, marking the Budgets it already serves.
3. Operator chooses the Budgets Slussen serves.
4. Slussen stores the choice, reads the Budget Accounts of each chosen Budget with their balances and shows them under their Budget, each that receives through no Account Mapping as receiving from Slussen unless it is a Manually Kept Account, and each mapped one with its Account Mapping (BR-014).
5. Slussen shows, for each Data Provider and grouped by Bank, its Offered Bank Accounts, each with its name or product, kind, currency, last four digits and balance: each one neither added nor left out as imported (BR-002, BR-012), each Left-Out Bank Account as not imported (BR-011), and each one that is an added Bank Account as added.
6. Operator chooses not to import each Offered Bank Account that Slussen is to leave out, and keeps the others imported.
7. Slussen records each Offered Bank Account the Operator chose not to import as a Left-Out Bank Account at once, records it in the Decision Journal (BR-011), and keeps every imported one that is not yet added as a Bank Account to map (BR-012).
8. Operator asks to map the Bank Accounts to map, or one of them (BR-015).
9. Slussen takes the Bank Accounts to map one at a time, showing which one of how many is current.
10. Slussen lists, for the current Offered Bank Account, the free Budget Accounts of its currency, proposing one only when it is the only one (BR-013), and, on request, every free Budget Account of each served Budget, each with its name and balance, and proposes the name of the Bank Account (BR-004), its Bank Account type and its Bank (BR-009).
11. Operator accepts the proposed Budget Account or chooses another, accepts or edits the name, confirms or changes the Bank Account type and the Bank, states the currency when the Offered Bank Account states none (BR-010), optionally sets a Start Date (BR-005), and asks Slussen to add the Bank Account and continue.
12. Slussen checks that the Budget Account receives through no other Account Mapping and that its Budget has the currency of the Bank Account, stated by the Data Provider or by the Operator (BR-010).
13. Slussen adds the Bank Account with an identifier it generates (BR-003), records how the Source identifies it, stores its name, currency, Bank Account type, Bank, Start Date and Account Mapping, and records the addition in the Decision Journal.
14. Slussen repeats steps 10 to 13 for each further Bank Account to map that the Operator asked to map.
15. Slussen shows which Bank Accounts were added and which were skipped for now, and lists the added ones with their Account Mappings (UC-009); the transactions of the added Bank Accounts are fetched and compared from the next Fetch on (UC-001).

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

**Trigger:** No Offered Bank Account of a Data Provider is stored, for example before its first Fetch or Bank Consent (step 5)  
**Flow:**

1. Slussen shows that the Data Provider offers no Bank Account yet and offers to start a Fetch of it (UC-001) or, for a Data Provider that needs a Bank Consent, to grant the Bank Consent of a Bank (UC-007).
2. Use case continues at step 5.

### A4: Bank Account of a File Format

**Trigger:** Operator chooses a File Format of the build and asks to add a Bank Account whose transactions come from its Transaction Files (step 1)  
**Flow:**

1. Slussen shows the Bank Accounts already added for that File Format and, while it serves no Budget, that one can be added once a Budget is chosen (BR-006).
2. Operator chooses its Bank Account type and its Bank, optionally the Offered Bank Account of a Data Provider that reports its balance (BR-006, BR-009), the Budget Account from the free Budget Accounts listed as at step 10 (BR-013), its name, its currency (BR-010) and optionally a Start Date (BR-005).
3. Slussen checks the Budget Account as at step 12 (A5) and adds the Bank Account with its Account Mapping as at step 13, so that it is listed with the Bank Accounts of the File Format and with the Account Mappings, already mapped.
4. Use case ends.

### A5: Budget Account Already Mapped or in Another Currency

**Trigger:** The chosen Budget Account receives through another Account Mapping, or its Budget has another currency than the Bank Account, stated by the Data Provider or by the Operator (step 12, BR-010)  
**Flow:**

1. Slussen refuses the pairing and shows why; no amount is ever converted (UC-003 BR-013).
2. Use case continues at step 11.

### A6: Offered Bank Account Probably Already Added

**Trigger:** An Offered Bank Account is probably an already added Bank Account whose identification changed (step 5)  
**Flow:**

1. Slussen shows it as a proposed match of that Bank Account, to be confirmed in Manage Account Mappings (UC-009), with no choice to import it or not, so that it is neither a Bank Account to map nor left out (UC-009 BR-004).
2. Use case continues at step 5.

### A7: Budget App Credentials Not Entered

**Trigger:** The Operator does not enter accepted credentials for the Budget App (step 1)  
**Flow:**

1. Slussen shows that Budgets can be chosen once the Budget App's credentials are accepted, and that the Account Mappings wait for a chosen Budget (BR-015).
2. Use case ends.

### A8: Skip for Now

**Trigger:** Operator skips the current Offered Bank Account for now (step 11)  
**Flow:**

1. Slussen adds nothing for it and does not leave it out, so that it stays imported and is still a Bank Account to map (BR-012).
2. Use case continues at step 14.

### A9: Mark or Unmark a Budget Account Manually Kept

**Trigger:** Operator chooses that a shown Budget Account that receives through no Account Mapping no longer receives from Slussen, or that a Manually Kept Account receives from Slussen again (step 4)  
**Flow:**

1. Slussen marks it as a Manually Kept Account, so that it is no longer offered for pairing or proposed (BR-013, BR-014), or unmarks it, so that it is offered again, and records the change in the Decision Journal.
2. Use case continues at step 4.

### A10: Bring Back a Left-Out Bank Account

**Trigger:** Operator chooses to import a Left-Out Bank Account again (step 6)  
**Flow:**

1. Slussen no longer leaves it out and records the change in the Decision Journal (BR-011).
2. Slussen keeps it as imported and as a Bank Account to map when the Data Provider's latest delivery holds it, and otherwise from the next delivery that holds it (BR-012).
3. Use case continues at step 6.

### A11: No Offered Bank Account Left to Decide

**Trigger:** Every stored Offered Bank Account of a Data Provider is added, left out, a proposed match of an added Bank Account or the balance of a Bank Account of a File Format (step 5)  
**Flow:**

1. Slussen shows that the Data Provider offers nothing new to import, with its Left-Out Bank Accounts as not imported, and offers to fetch it again (UC-001) or to grant a Bank Consent (UC-007); none of its Offered Bank Accounts is a Bank Account to map.
2. Use case ends.

### A12: Nothing Brought In

**Trigger:** Operator chooses not to import any Offered Bank Account, and no Bank Account is added or to map (step 6)  
**Flow:**

1. Slussen records each of them as a Left-Out Bank Account at once and records them in the Decision Journal (BR-011), as at step 7.
2. Slussen shows that there is nothing to map, so that the Account Mappings wait until a Bank Account is imported or added (BR-015).
3. Use case ends.

## Postconditions

### Success Postconditions

- The Budget App and the Data Providers the Operator uses have accepted credentials, stored only on the device.
- Slussen serves the Budgets the Operator chose.
- Each paired Bank Account is added with its generated identifier, its name, its Bank Account type, its Bank, its optional Start Date and its Account Mapping, and its transactions are fetched and compared from the next Fetch on.
- Each Offered Bank Account the Operator chose not to import is a Left-Out Bank Account, no longer a Bank Account to map and never read, until the Operator imports it again; each imported one skipped for now or not yet reached is still a Bank Account to map, and nothing is fetched for it.
- Each Budget Account the Operator chose to no longer receive from Slussen is a Manually Kept Account and is no longer offered for pairing; each one unmarked is offered again.
- Every change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- No Bank Account is added without the Operator's pairing, or without a Bank Account type and a Bank the Operator confirmed or chose.
- No Offered Bank Account is left out unless the Operator chose not to import it, and no proposed Budget Account is used without the Operator.
- No Budget Account receives through two Account Mappings, and no Bank Account is mapped into a Budget of another currency.
- Nothing is written to the Budget App.

## Business Rules

### BR-001: Budgets Are Chosen in the App

Slussen serves only the Budgets the Operator chose from those the Budget App lists; only these are read, compared and shown. A Budget stops being served only when no Bank Account is mapped into it.

### BR-002: Pairing Shows Both Sides

For each Offered Bank Account Slussen shows its name or product, kind, currency, the last four digits of its number (UC-009 BR-009) and its balance, on its Data Provider's page grouped by Bank, and as one line with its Source among the Bank Accounts to map; for each Budget Account its name and balance. Only Budget Accounts of served Budgets that receive through no Account Mapping and are not Manually Kept Accounts are offered for pairing (BR-014).

### BR-003: Slussen Generates the Identifier

When a Bank Account is added, Slussen generates its identifier, as unlikely to collide as a random UUID (NFR-020), and records how the Source identifies the Bank Account (UC-009 BR-004, UC-009 BR-010).

### BR-004: Proposed Name

Slussen proposes the name of a Bank Account from what its Source delivers: its name or product, followed by the last four digits of its number (UC-009 BR-009) only when that name or product does not already end with them, so that the digits never appear twice in the proposed name. The Operator can edit it now or at any time later (UC-009 A12); the name never identifies the Bank Account (UC-009 BR-011).

### BR-005: Start Date in the App

The Start Date of a Bank Account is optional and is set or changed in the app (UC-009 A13). When another import path already fed the Budget Account, the Operator sets it so that Slussen offers nothing from before it (UC-003 BR-018, UC-012 BR-007).

### BR-006: Bank Accounts of a File Format

A Bank Account whose transactions come from Transaction Files is added and mapped in one go on its File Format's page in Settings (A4), never among the Bank Accounts to map, by choosing its Bank Account type, its Bank, its Budget Account and, when a Data Provider reports its balance, that Data Provider's Offered Bank Account (UC-001 BR-011), or when its first Transaction File is imported (UC-002 A7). It is then listed with the Account Mappings like any other added Bank Account, where its Account Mapping is changed (UC-009). Adding one needs a served Budget, since it is mapped as it is added; until a Budget is served, the File Format's page says so. Slussen proposes no name, Bank Account type or Bank from a file; the Operator enters the name and chooses the others (BR-009). The Offered Bank Accounts offered for the balance include the Left-Out ones; choosing one brings it back (BR-011), and an Offered Bank Account that reports the balance of a Bank Account of a File Format is never a Bank Account to map (BR-012).

### BR-007: Kept Across a Reinstall, Restore or Upgrade

The chosen Budgets and each added Bank Account with its identifier, name, Start Date and Account Mapping are Decision-Critical Data: they are included in device backups, restored after a reinstall and kept on an upgrade to a newer build signed with the same key (UC-009 BR-006, NFR-017). The Left-Out Bank Accounts and the Manually Kept Accounts are kept the same way (NFR-017), though they are not Decision-Critical Data.

### BR-008: First-Run Order

Setting up Slussen has no screen of its own: it starts at the top of Settings, which lists the Budget App, each Data Provider, each File Format and then the Account Mappings in the order of setting up (UC-010 BR-010, UC-014 BR-007), and names what comes next on the first of them that still needs it: enter the Budget App's credentials (UC-014), choose the Budgets, enter a Data Provider's credentials and fetch it or grant the Bank Consent of a Bank so that it offers its Bank Accounts (UC-001, UC-007, A3), then map the imported Bank Accounts. This order is a recommendation, not a lock: the page of the Budget App, of each Data Provider and of each File Format can be opened at any time, and only the Account Mappings wait for what they need (BR-015). While no Bank Account is added, opening Slussen shows only one card that leads to Settings and names what comes next (UC-008 BR-011); from the first added Bank Account on, opening Slussen shows the overview of UC-008. Each of these can be done again on its own at any time from the same entries: the credentials and the Budgets on the Budget App's page, the credentials and the Offered Bank Accounts on each Data Provider's page, the Bank Accounts of a File Format on its page, and the Bank Accounts to map in the Account Mappings.

### BR-009: Proposed Bank Account Type and Bank

Before a Bank Account is added, Slussen proposes its Bank Account type, one of Checking, Savings, Credit Card or Other, from the kind the Data Provider gives the Offered Bank Account, Other when that kind matches none of the first three, and its Bank, the Bank the Data Provider names for it. The Operator confirms or changes both: the Bank Account type from these four values, the Bank by choosing exactly one Bank Slussen already knows or naming a new one. When the Data Provider names no Bank, Slussen proposes none and the Operator chooses or names it. For a Bank Account of a File Format, Slussen proposes neither, and the Operator chooses the Bank Account type and chooses or names the Bank (A4, BR-006). No Bank Account is added without a Bank Account type and a Bank. The Bank Account type decides whether the Bank Account is a credit card, with a Funded By, or has a Transfer Partner (UC-005 BR-006). For a Data Provider that needs a Bank Consent, the Bank Account is always covered by the Bank Consent of the Bank the Data Provider names for it, whatever Bank the Operator confirms or chooses here, which is only how the Bank Account is named and grouped in Slussen (UC-007 BR-009).

### BR-010: Currency Stated by the Operator

When an Offered Bank Account states no currency, the Operator states the currency of the Bank Account at step 11; Slussen proposes the currency of the chosen Budget Account, which is that of its Budget. A Bank Account of a File Format has the currency the Operator states in the same way (A4), proposed from the chosen Budget Account, or the currency of the Offered Bank Account that reports its balance when that one states one. The pairing is then checked as for a currency the Data Provider states (step 12, A5): a Budget of another currency is refused, and no amount is ever converted (UC-003 BR-013). No Bank Account is added without a currency.

### BR-011: Left-Out Bank Accounts Are Remembered and Never Read

An Offered Bank Account that the Operator chooses not to import (step 6, A12) becomes a Left-Out Bank Account at once. Slussen remembers it by its Data Provider and the Data Provider's reference, across later Fetches, Bank Consents, a reinstall with restore from a device backup and upgrades (NFR-017). It is remembered by that reference only: when the Data Provider gives the same Bank Account a new reference, Slussen offers it again as a new Offered Bank Account, imported by default (BR-012), and the Operator leaves it out again. It is never a Bank Account to map, never proposed as a match of an added Bank Account (UC-009 BR-004), and never read: Slussen never asks for its transactions or its balance on their own and never stores or uses them (UC-001 A18). A Data Provider that answers with all its Bank Accounts in one list, each with its balance, as SimpleFIN does, still lists it; that answer is not a read of the Left-Out Bank Account. It is not a Bank Account: it has no Account Mapping and no identifier, and unlike a Retired Account it was never added. It is shown as not imported among its Data Provider's Offered Bank Accounts and with the Account Mappings (UC-009 BR-013). Slussen imports it again only when the Operator brings it back: by choosing to import it again (A10), by choosing it for the balance of a Bank Account of a File Format (BR-006), or in Manage Account Mappings (UC-009 A15). Leaving it out and bringing it back are recorded in the Decision Journal with its name, never with the Data Provider's reference (NFR-012).

### BR-012: Imported by Default, Left Out One at a Time

Every Offered Bank Account that is neither added nor left out is imported by default, also one a later Fetch or Bank Consent delivers: its import switch on its Data Provider's page in Settings is on, and it is a Bank Account to map, listed first in the Account Mappings (UC-009 BR-014). Switching the import of one off makes it a Left-Out Bank Account at once, with no confirmation and no statement of how many are imported and how many are left out, because switching it on again brings it back (BR-011, A10). An imported Offered Bank Account is not a Bank Account until it is paired and added (step 13): nothing is fetched, compared or uploaded for it before (UC-001 step 3). One skipped for now (A8), or not yet reached, stays imported and to map. A proposed match of an added Bank Account (A6) and an Offered Bank Account that reports the balance of a Bank Account of a File Format (BR-006) have no import switch, are never Bank Accounts to map and are never left out; an added Bank Account is shown as added, without a switch. The flow A13, Go Back Before Confirming, which served the statement of the numbers imported and left out, is retired, and its id is not used again.

### BR-013: The Proposed Budget Account

A Budget Account is free when it belongs to a served Budget, receives through no Account Mapping and is not a Manually Kept Account (BR-002). For the current Offered Bank Account, Slussen lists the free Budget Accounts of its currency, or every free Budget Account when it states no currency. When exactly one is listed, Slussen proposes it and says that it is the only free Budget Account of that currency; otherwise it proposes none, and the Operator chooses one from the list. Slussen compares no balance or name to propose one. Every free Budget Account of each served Budget can be reached from the list in one step. The proposal is never applied without the Operator, who accepts it or chooses another at step 11; it identifies nothing (UC-009 BR-004) and is checked like any other choice (step 12, A5).

### BR-014: A Manually Kept Account Is Not Offered for Pairing

The Budget App's page in Settings lists, under each chosen Budget, its Budget Accounts: each that receives through no Account Mapping with a switch "Receives from Slussen", on by default, and each that receives through an Account Mapping with that Account Mapping instead of a switch, since it cannot be marked (UC-009 BR-001). Switching it off marks the Budget Account as a Manually Kept Account (A9, UC-009 A16); it then leaves the Budget Accounts offered for pairing and the proposals of BR-013. Switching it on again unmarks it, after which it is offered for pairing again. The pairing of one Bank Account offers no marking, and the Account Mappings only list the Manually Kept Accounts and lead to the Budget App's page (UC-009 BR-014). Slussen never writes to a Manually Kept Account and never fetches it (UC-005 BR-014).

### BR-015: When the Account Mappings Are Available

The Account Mappings become available in Settings once Slussen serves at least one Budget, which needs credentials the Budget App accepted when the Budgets were chosen (step 3, A7), and holds at least one Bank Account to show there: an added Bank Account or an imported Bank Account to map. Until then their entry in Settings is shown as not yet available, with the first thing missing as the reason: the Budget App's credentials, a chosen Budget, or a Bank Account imported from a Data Provider (fetch it or grant a Bank Consent, A3) or added on a File Format's page (A4). Once available, their entry stays available when the Budget App's credentials are later refused or removed (UC-014 A3), so that the added Bank Accounts stay reachable. Nothing opens by itself; the Operator opens them from their entry. The page of the Budget App, of each Data Provider and of each File Format can be opened at any time; they wait for nothing.
