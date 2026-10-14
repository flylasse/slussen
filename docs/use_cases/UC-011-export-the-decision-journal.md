# Use Case: Export the Decision Journal

## Overview

**Use Case ID:** UC-011  
**Use Case Name:** Export the Decision Journal  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App  
**Goal:** Obtain a Journal Export that holds the Decision Journal together with the Budget App's current transactions, so that the Operator can measure outside the app how often Slussen was right  
**Trigger:** Operator asks to export the Decision Journal  
**Status:** Draft  

**Requirements:** [FR-066, FR-068, NFR-006, NFR-011, NFR-012](../requirements.md)

## Preconditions

- The Decision Journal has recorded at least one entry.

## Main Success Scenario

1. Slussen asks whether payee and memo text are to be replaced by codes, and where to save the file.
2. Operator chooses whether to replace payee and memo text and chooses the place for the file.
3. Slussen reads the current transactions of the Operator's Budgets from the Budget App.
4. Slussen writes the Journal Export with the entries of the Decision Journal, each transaction named by its Bank Account and its Import Identifier, and the Budget App's current transactions, replacing payee and memo text by codes when the Operator chose that.
5. Slussen shows that the export is complete, with the number of entries it holds.

## Alternative Flows

### A1: Operator Cancels

**Trigger:** Operator does not choose a place for the file (step 2)  
**Flow:**

1. Slussen writes no file.
2. Use case ends.

### A2: Budget App Cannot Be Read

**Trigger:** The Budget App does not answer or cannot be reached (step 3)  
**Flow:**

1. Slussen uses its stored copy of the Budget App Transactions instead (UC-003).
2. Slussen states in the Journal Export that the Budget App's transactions come from the stored copy, with the time of the last successful read of each Budget.
3. Use case continues at step 4.

### A3: File Cannot Be Written

**Trigger:** The file cannot be written completely at the chosen place (step 4)  
**Flow:**

1. Slussen shows that the export failed and why.
2. Use case ends.

## Postconditions

### Success Postconditions

- A Journal Export exists at the place the Operator chose, holding the Decision Journal and the Budget App's transactions together with their source.

### Failure Postconditions

- No incomplete file is presented as a complete Journal Export.
- The Decision Journal itself is unchanged.
- Nothing is written to the Budget App.

## Business Rules

### BR-001: What the Decision Journal Records

The Decision Journal records every Bank Transaction that Slussen received from a Fetch or a Transaction File for the first time or with changed content, every change of the Transaction Status it gave a Bank Transaction, every Review Decision and Transfer Decision together with what Slussen had proposed, every Upload Plan and upload, every Account Mapping change, every change to an Operator Setting and every clearing of the Decision Journal, the start of every session, and what later happened in the Budget App to each transaction Slussen sent. Every entry about a Bank Transaction names its Bank Account and its Import Identifier, because an Import Identifier is unique only within one Bank Account.

### BR-002: The Journal Only Observes

The Decision Journal is a record only. Recording, exporting or failing to record changes no Transaction Status, Upload Plan or upload.

### BR-003: No Secrets in the Journal

The Decision Journal and every Journal Export contain no credentials, Bank Consent data, Bank Account numbers, identifiers that Data Providers give Bank Accounts, or Data Provider error texts.

### BR-004: The Journal Leaves the Device Only by Export

The Decision Journal is never included in device backups. It leaves the device only through a Journal Export the Operator starts.

### BR-005: Consistent Codes

When payee and memo text are replaced by codes, the same text always gets the same code within one Journal Export, so that the export can still be analysed.

### BR-006: Facts, Not Conclusions

A Journal Export holds the recorded facts and the Budget App's transactions only. It draws no conclusions and computes no measures, such as how often Slussen was right; the analysis happens outside the app.
