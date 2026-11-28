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
7. Slussen stores the Bank Transactions that are new for the Bank Account (BR-003) and records the Transaction File as imported with the number of Bank Transactions it delivered: every transaction kept at step 5, those already stored included.
8. Slussen records in the Decision Journal each Bank Transaction it took in for the first time (UC-011 BR-001).
9. Slussen compares the stored Bank Transactions with the Budget App (UC-003). When the Budget App cannot be read, the import stays as stored and its new Bank Transactions are shown as not compared yet (UC-003 A1).
10. Slussen shows the number of Bank Transactions taken in for the first time (worked out at this import, not stored), the Transaction Status of each, and the import as the Source Health and Freshness of the Bank Account (BR-006).

## Alternative Flows

### A1: Operator Cancels the Choice

**Trigger:** Operator does not choose a file (step 3)  
**Flow:**

1. Slussen leaves the stored Bank Transactions of the Bank Account unchanged.
2. Use case ends.

### A2: File Cannot Be Converted Completely

**Trigger:** The file does not follow the File Format, is damaged, or contains a line the File Pre-processor cannot convert, or a transaction of the file is in another currency than the Bank Account's (a transaction without a currency is in the Bank Account's) (step 4)  
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

1. Slussen counts these transactions against the stored Bank Transactions as BR-003 says and keeps each one that is new as a separate Bank Transaction without a Stable Reference, numbered by its position among the identical transactions of the Bank Account (UC-003 BR-002).
2. Slussen gives each new one the status Needs Review, so that the Operator decides on each (UC-004).
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

**Trigger:** Operator asks to import a Transaction File on the page of a File Format for which no Bank Account is added yet (step 1)  
**Flow:**

1. Slussen adds the Bank Account with its Account Mapping as UC-013 A4 describes, or the Operator cancels and the use case ends with nothing added.
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

A Transaction File is taken in as a whole or rejected as a whole. A file that cannot be converted completely never brings in part of its transactions. Slussen sets no limit on the size of a file.

### BR-002: Every Transaction Gets a Reference

The File Pre-processor gives every transaction of the file a reference and declares for each whether the Bank supplied it or the pre-processor derived it from the transaction's own fields (for example its date, amount and description; ADR-001 §4.3.1 for the File Format of this build). A new Bank Transaction gets its reference as its Stable Reference only when no other transaction of its file has the same reference (BR-003); a stored Bank Transaction keeps its Stable Reference, `occurrence_number` and Import Identifier for ever, whatever a later file holds (ADR-008 §4). Transactions of a file that share a reference cannot be told apart: those that are new have no Stable Reference, go to review (UC-003 BR-012), and are never merged into one Bank Transaction.

### BR-003: Earlier Transactions Are Kept

A Bank Transaction stored from an earlier Transaction File that a later file no longer contains stays stored with its Transaction Status. A Bank Transaction that appears in both files is stored once. Transactions that cannot be told apart are recognised in a later file by their date, amount, payee, memo and number: when the later file holds as many identical transactions as are stored, it brings none of them in again; when it holds more, only the additional ones are new, each with the status Needs Review. Slussen counts the transactions of a file against the stored Bank Transactions of the Bank Account in three steps, all inside the storage operation that stores the file (ADR-002 §3 O-3), so that no transaction gets a second Import Identifier:

1. For each reference that a stored Bank Transaction holds as its Stable Reference, the first transaction of the file with that reference, in the order of the file, is that Bank Transaction.
2. Every other transaction of the file is counted, in the order of the file, against the stored Bank Transactions without a Stable Reference that are identical to it in date, amount, payee and memo (ADR-008 §2): as many identical transactions of the file as are stored are those stored ones, each stored one counted once. This holds for a transaction with a Derived reference also when no other transaction of the file has its reference, and for a transaction with a Supplied reference that another transaction of the file shares. Only a transaction with a Supplied reference that no other transaction of the file has is not counted here: its reference names it, so it is new.
3. Every transaction left is new. A new one whose reference no other transaction of the file has gets that reference as its Stable Reference; every other new one has no Stable Reference, takes the next `occurrence_number` after the stored identical ones (ADR-008 §5) and is Needs Review (A4).

So an overlapping later export brings only the transactions not stored yet; a later file that holds two identical transactions of which one was stored from an earlier file with a Stable Reference brings in the second one only, without a Stable Reference; and a later file that holds one transaction of two identical ones stored without a Stable Reference brings in none. A transaction of a later file that is counted as a stored one leaves that Bank Transaction as it is stored, also when its payee or memo differs (a Supplied reference), and is not recorded in the Decision Journal again (step 8).

### BR-004: File Upload Window

A Bank Transaction from a Transaction File can be in an Upload Plan only inside the file upload window (the last 60 days by default, a number the Operator can change, UC-010). The window is the same for every File Format and is usually wider than the Upload Window, because Transaction Files are imported less often than Data Providers are fetched. A transaction outside it is shown as Too Old or In The Future and never disappears (UC-003 BR-011).

### BR-005: Balance From a Data Provider

A Transaction File brings in no Account Balance. The Account Balance of the Bank Account comes from the Data Provider the Operator chose for it in the app (UC-013 BR-006, UC-001 BR-011).

### BR-006: Health and Freshness of a File-Imported Bank Account

For a Bank Account whose transactions come from Transaction Files, the Source Health is the outcome of the last Transaction File import (imported or rejected with its reason) with its time, and the Freshness is measured from the time of the last imported Transaction File. The Account Balance keeps its own time from its Data Provider.

### BR-007: One Standard Import Format

Slussen takes in the transactions of a Transaction File only in its Standard Import Format. Each File Format has one File Pre-processor that converts the Bank's file into it, so that nothing else in Slussen depends on a particular Bank or export format (UC-012 BR-005).
