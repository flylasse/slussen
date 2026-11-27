# Use Case: Export the Decision Journal

## Overview

**Use Case ID:** UC-011  
**Use Case Name:** Export the Decision Journal  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App  
**Goal:** Obtain a Journal Export that holds the Decision Journal together with the Budget App's current transactions, so that the Operator can measure outside the app how often Slussen was right  
**Trigger:** Operator asks to export the Decision Journal  
**Status:** Approved  

**Requirements:** [FR-066, FR-068, NFR-006, NFR-011, NFR-012](../requirements.md)

## Preconditions

- The Decision Journal has recorded at least one entry.
- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Slussen asks whether payee and memo text are to be replaced by codes, and where to save the file.
2. Operator chooses whether to replace payee and memo text and chooses the place for the file.
3. Slussen reads the current transactions of the Operator's Budgets from the Budget App, over the dates of BR-008, without storing anything of that read (BR-002).
4. Slussen writes the Journal Export (BR-007) with the entries of the Decision Journal, each transaction named by its Bank Account and its Import Identifier, and the Budget App's current transactions with their Budget, Budget Account, date, payee, memo, amount and the Import Identifier they carry, replacing payee and memo text by codes when the Operator chose that.
5. Slussen shows that the export is complete, with the number of entries it holds.

## Alternative Flows

### A1: Operator Cancels

**Trigger:** Operator does not choose a place for the file (step 2)  
**Flow:**

1. Slussen writes no file.
2. Use case ends.

### A2: Budget App Cannot Be Read

**Trigger:** A Budget cannot be read completely: the Budget App does not answer, cannot be reached or refuses Slussen's credentials (step 3)  
**Flow:**

1. Slussen uses, for that Budget, its stored copy of the Budget App Transactions instead, which holds their memo too (UC-003): those present at its last successful read, over the dates of BR-008 as far back as the copy reaches; nothing of the failed read is used.
2. Slussen states in the Journal Export that this Budget's transactions come from the stored copy, with the time of the last successful read of the Budget, or that it was never read and none of its transactions are in the export, and why, in Slussen's own words and never in the Budget App's.
3. Use case continues at step 4.

### A3: File Cannot Be Written

**Trigger:** The file cannot be written completely at the chosen place (step 4)  
**Flow:**

1. Slussen stops writing and removes the file it began at the chosen place; when it cannot remove it, it names the file and states that it is incomplete and is no Journal Export.
2. Slussen shows that the export failed and why.
3. Use case ends.

## Postconditions

### Success Postconditions

- A Journal Export exists at the place the Operator chose, holding the Decision Journal and the Budget App's transactions together with where they come from.

### Failure Postconditions

- No incomplete file is presented as a complete Journal Export.
- The Decision Journal itself is unchanged.
- Nothing is written to the Budget App.

## Business Rules

### BR-001: What the Decision Journal Records

The Decision Journal records every Bank Transaction that Slussen received from a Fetch or a Transaction File for the first time or with changed content, every change of the Transaction Status it gave a Bank Transaction, every Review Decision and Transfer Decision together with what Slussen had proposed, every Upload Plan and Upload, every Account Mapping change, every change to an Operator Setting and every clearing of the Decision Journal, the start of every session, and what later happened in the Budget App to each transaction Slussen sent. Every entry about a Bank Transaction names its Bank Account and its Import Identifier, because an Import Identifier is unique only within one Bank Account.

### BR-002: The Journal Only Observes

The Decision Journal is a record only. Recording, exporting or failing to record changes no Transaction Status, Upload Plan or Upload. The read of step 3 is not stored: Slussen's copy of the Budget App Transactions, the time of the last successful read of each Budget, the point from which its next read continues, the Budget Account balances and every Transaction Status stay as they were, and an export removes no entry of the Decision Journal.

### BR-003: No Secrets in the Journal

The Decision Journal and every Journal Export contain no credentials, Bank Consent data, Bank Account numbers, identifiers that Data Providers give Bank Accounts, or Data Provider error texts.

### BR-004: The Journal Leaves the Device Only by Export

The Decision Journal is never included in device backups. It leaves the device only through a Journal Export the Operator starts.

### BR-005: Consistent Codes

When payee and memo text are replaced by codes, the same text always gets the same code within one Journal Export, so that the export can still be analysed. The codes are numbered within each export (ADR-008), so a code has a meaning only within its own export. The names of Budgets, Budget Accounts and Bank Accounts, the Import Identifiers and the entries of the Decision Journal, which hold no payee or memo (`DECISION_JOURNAL_ENTRY`), are not replaced.

### BR-006: Facts, Not Conclusions

A Journal Export holds the recorded facts and the Budget App's transactions only. It draws no conclusions and computes no measures, such as how often Slussen was right; the analysis happens outside the app.

### BR-007: The Journal Export File

A Journal Export is one JSON file, for which Slussen proposes the name slussen-journal-<date of the export>.json. It holds, in this order: a header that says what the file is, the version name of the build that wrote it (UC-010 BR-011), the time of the export, whether payee and memo text are replaced by codes, the first date of the Budget App's transactions it holds (BR-008), and for each Budget Slussen serves its name, its currency and where its transactions come from (read at the time of the export, or the stored copy with the time of its last successful read, or none because it was never read, A2); then every entry the Decision Journal held when the export began, before the read of step 3, oldest first, each with its time, its kind, the name of its Bank Account and its Import Identifier when it concerns a Bank Transaction, and its details as recorded; then the Budget App's transactions; and last a closing part with the number of entries and the number of the Budget App's transactions it holds. A file without its closing part is incomplete. The file holds nothing else: nothing BR-003 excludes, and no identifier that the Budget App or a Data Provider gives a Budget, a Budget Account, a Bank Account or a transaction.

### BR-008: The Budget App's Transactions in the Export

A Journal Export holds, for each Budget Slussen serves, the transactions present in the Budget App in the Budget Accounts Slussen knows of that Budget (UC-013), dated from the day of the oldest entry the export holds (BR-007), back by the read period of a Budget's first read: the longest of the fetch period, the Upload Window and the file upload window, each the Operator Setting as it is at the export, plus the Budget App's Matching Date Window (UC-003 BR-016), so that the transactions the oldest entries name are included. Each holds its Budget, its Budget Account, its date, payee, memo, its amount in the Budget's currency, negative for an outflow, and the Import Identifier it carries, empty for a Typed Entry.
