# Use Case: Import a Transaction File

## Overview

**Use Case ID:** UC-002  
**Use Case Name:** Import a Transaction File  
**Primary Actor:** Operator  
**Goal:** Bring the Bank Transactions of a Bank Account that has no usable transaction feed into Slussen from a Transaction File, compared with the Budget App, without typing them  
**Trigger:** Operator asks to import a Transaction File for a Bank Account  
**Status:** Approved  

**Requirements:** [FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, FR-063, FR-066, FR-083, FR-085, FR-094, FR-098, NFR-002, NFR-005, NFR-014, NFR-020, C-010, C-012](../requirements.md)

## Preconditions

- Slussen serves at least one Budget (UC-013).
- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Operator asks to import a Transaction File for a Bank Account whose transactions come from Transaction Files.
2. Slussen asks the Operator to choose the file.
3. Operator chooses a Transaction File downloaded from the Bank.
4. Slussen has the File Pre-processor of the Bank Account's File Format convert the whole file into the Standard Import Format (BR-007).
5. Slussen keeps the booked transactions of the file with an amount other than zero (UC-001 BR-002, UC-001 BR-003).
6. Slussen takes each transaction's reference as the File Pre-processor gives it, supplied by the Bank or derived by the pre-processor, as its Stable Reference (BR-002).
7. Slussen stores the Bank Transactions that are new for the Bank Account (BR-003) and records the Transaction File as imported with the number of Bank Transactions it delivered.
8. Slussen records in the Decision Journal each Bank Transaction it took in for the first time (UC-011 BR-001).
9. Slussen compares the stored Bank Transactions with the Budget App (UC-003).
10. Slussen shows the number of Bank Transactions taken in, the Transaction Status of each, and the import as the Source Health and Freshness of the Bank Account (BR-006).

## Alternative Flows

### A1: Operator Cancels the Choice

**Trigger:** Operator does not choose a file (step 3)  
**Flow:**

1. Slussen leaves the stored Bank Transactions of the Bank Account unchanged.
2. Use case ends.

### A2: File Cannot Be Converted Completely

**Trigger:** The file does not follow the File Format, is damaged, or contains a line the File Pre-processor cannot convert (step 4)  
**Flow:**

1. Slussen rejects the whole file and takes in none of its transactions.
2. Slussen shows the reason for the rejection.
3. Slussen records the Transaction File as rejected with its reason and keeps the Bank Transactions of the previous import unchanged.
4. Use case ends.

### A3: File Holds No Importable Transaction

**Trigger:** No booked transaction with an amount other than zero is left (step 5)  
**Flow:**

1. Slussen rejects the file with the reason that it holds no importable transaction.
2. Slussen records the Transaction File as rejected and keeps the Bank Transactions of the previous import unchanged.
3. Use case ends.

### A4: Transactions That Cannot Be Told Apart

**Trigger:** Two or more transactions of the file have the same reference (step 6)  
**Flow:**

1. Slussen keeps each of these transactions as a separate Bank Transaction without a Stable Reference, numbered by its position among the identical transactions (UC-003 BR-002).
2. Slussen gives each of them the status Needs Review, so that the Operator decides on each (UC-004).
3. Use case continues at step 7.

### A5: Retired Bank Account

**Trigger:** The chosen Bank Account is a Retired Account (step 1)  
**Flow:**

1. Slussen refuses the import and shows that the Bank Account must be restored first (UC-009).
2. Use case ends.

### A6: Import Identifier Collision

**Trigger:** The Import Identifier of a transaction of the file collides with the Import Identifier of a different stored Bank Transaction of the same Bank Account (UC-003 BR-002) (step 7)  
**Flow:**

1. Slussen rejects the whole file with the reason "Import Identifier collision" and takes in none of its transactions (BR-001).
2. Slussen records the Transaction File as rejected with its reason and keeps the Bank Transactions of the previous import unchanged.
3. Use case ends.

### A7: Bank Account Not Added Yet

**Trigger:** Operator chooses a File Format for which no Bank Account is added yet (step 1)  
**Flow:**

1. Slussen adds the Bank Account with its Account Mapping as UC-013 A4 describes.
2. Use case continues at step 2.

## Postconditions

### Success Postconditions

- Every Bank Transaction of the file is stored once for the Bank Account and has a Transaction Status from the Comparison.
- Every stored Bank Transaction of the file records whether its reference was supplied by the Bank or derived by the File Pre-processor, or that it has no Stable Reference.
- The Transaction File is recorded as imported with the number of Bank Transactions it delivered.
- The Bank Transactions taken in for the first time are recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- Nothing is written to the Budget App.
- No transaction of a rejected file is taken in.
- The Bank Transactions of the previous import and their Transaction Statuses are unchanged.

## Business Rules

### BR-001: All or Nothing

A Transaction File is taken in as a whole or rejected as a whole. A file that cannot be converted completely never brings in part of its transactions.

### BR-002: Every Transaction Gets a Reference

The File Pre-processor gives every transaction of the file a reference and declares for each whether the Bank supplied it or the pre-processor derived it from the transaction's own fields (for example its date, amount and description). A reference is a Stable Reference only while no other transaction of the same Bank Account has the same one. Transactions that share a reference cannot be told apart: they have no Stable Reference, go to review (UC-003 BR-012), and are never merged into one Bank Transaction.

### BR-003: Earlier Transactions Are Kept

A Bank Transaction stored from an earlier Transaction File that a later file no longer contains stays stored with its Transaction Status. A Bank Transaction that appears in both files is stored once. Transactions that cannot be told apart are recognised in a later file by their date, amount, payee, memo and number: when the later file holds as many identical transactions as are stored, it brings none of them in again; when it holds more, only the additional ones are new, each with the status Needs Review.

### BR-004: File Upload Window

A Bank Transaction from a Transaction File can be in an Upload Plan only inside the file upload window (the last 60 days by default, a number the Operator can change, UC-010). The window is the same for every File Format and is usually wider than the Upload Window, because Transaction Files are imported less often than Data Providers are fetched. A transaction outside it is shown as Too Old or In The Future and never disappears (UC-003 BR-011).

### BR-005: Balance From a Data Provider

A Transaction File brings in no Account Balance. The Account Balance of the Bank Account comes from the Data Provider the Operator chose for it in the app (UC-013 BR-006, UC-001 BR-011).

### BR-006: Health and Freshness of a File-Imported Bank Account

For a Bank Account whose transactions come from Transaction Files, the Source Health is the outcome of the last Transaction File import (imported or rejected with its reason) with its time, and the Freshness is measured from the time of the last imported Transaction File. The Account Balance keeps its own time from its Data Provider.

### BR-007: One Standard Import Format

Slussen takes in the transactions of a Transaction File only in its Standard Import Format. Each File Format has one File Pre-processor that converts the Bank's file into it, so that nothing else in Slussen depends on a particular Bank or export format (UC-012 BR-005).
