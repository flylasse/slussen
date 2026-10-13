# Use Case: Import a Transaction File

## Overview

**Use Case ID:** UC-002  
**Use Case Name:** Import a Transaction File  
**Primary Actor:** Operator  
**Goal:** Bring the Bank Transactions of a Bank Account that has no usable transaction feed into Slussen from a Transaction File, compared with the Budget App, without typing them  
**Trigger:** Operator asks to import a Transaction File for a Bank Account  
**Status:** Draft  

**Requirements:** [FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, NFR-002, NFR-005, C-010, C-012](../requirements.md)

## Preconditions

- The Bank Account is part of the Build Configuration with a File Format and an Account Mapping.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Operator asks to import a Transaction File for a Bank Account whose transactions come from Transaction Files.
2. Slussen asks the Operator to choose the file.
3. Operator chooses a Transaction File downloaded from the Bank.
4. Slussen reads the whole file with the File Format of the Bank Account.
5. Slussen keeps the booked transactions of the file with an amount other than zero (UC-001 BR-002, UC-001 BR-003).
6. Slussen gives each Bank Transaction its Stable Reference according to the File Format.
7. Slussen stores the Bank Transactions for the Bank Account and records the Transaction File as imported with the number of Bank Transactions it delivered.
8. Slussen compares the stored Bank Transactions with the Budget App (UC-003).
9. Slussen shows the number of Bank Transactions taken in and the Transaction Status of each.

## Alternative Flows

### A1: Operator Cancels the Choice

**Trigger:** Operator does not choose a file (step 3)  
**Flow:**

1. Slussen leaves the stored Bank Transactions of the Bank Account unchanged.
2. Use case ends.

### A2: File Cannot Be Read Completely

**Trigger:** The file does not follow the File Format, is damaged, or contains a line Slussen cannot read (step 4)  
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

**Trigger:** The File Format gives no Stable Reference and two or more transactions of the file are identical in every field Slussen reads (step 6)  
**Flow:**

1. Slussen keeps each of these transactions as a separate Bank Transaction.
2. Slussen gives each of them the status Needs Review, so that the Operator decides on each (UC-004).
3. Use case continues at step 7.

### A5: Retired Bank Account

**Trigger:** The chosen Bank Account is a Retired Account (step 1)  
**Flow:**

1. Slussen refuses the import and shows that the Bank Account must be restored first (UC-009).
2. Use case ends.

## Postconditions

### Success Postconditions

- Every Bank Transaction of the file is stored for the Bank Account and has a Transaction Status from the Comparison.
- The Transaction File is recorded as imported with the number of Bank Transactions it delivered.

### Failure Postconditions

- Nothing is written to the Budget App.
- No transaction of a rejected file is taken in.
- The Bank Transactions of the previous import and their Transaction Statuses are unchanged.

## Business Rules

### BR-001: All or Nothing

A Transaction File is taken in as a whole or rejected as a whole. A file that cannot be read completely never brings in part of its transactions.

### BR-002: Stable Reference Declared by the File Format

Each File Format declares whether its transactions carry a Stable Reference. When it carries none, Slussen derives one from the fields of the transaction. Only transactions that cannot be told apart by any field go to review; they are never merged into one Bank Transaction.

### BR-003: Earlier Transactions Are Kept

A Bank Transaction stored from an earlier Transaction File that a later file no longer contains stays stored with its Transaction Status. A Bank Transaction that appears in both files is stored once.

### BR-004: Upload Window of a File Format

A Bank Transaction from a Transaction File is uploaded only inside the Upload Window its File Format declares, under the never-silent rule of UC-003 BR-011.

### BR-005: Balance From a Data Provider

A Transaction File brings in no Account Balance. The Account Balance of the Bank Account comes from the Data Provider named for it in the Build Configuration (UC-001 BR-011).
