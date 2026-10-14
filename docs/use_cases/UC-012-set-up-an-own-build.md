# Use Case: Set Up an Own Build

## Overview

**Use Case ID:** UC-012  
**Use Case Name:** Set Up an Own Build  
**Primary Actor:** Adopting Developer  
**Goal:** Obtain a working Slussen for the Adopting Developer's own household, built from the public repository with their own Bank Accounts, Budgets, Account Mappings and credentials, without changing how Slussen compares, reviews and decides  
**Trigger:** Adopting Developer decides to build Slussen from the public repository for their own household  
**Status:** Draft  

**Requirements:** [FR-056, FR-070, FR-071, FR-072, FR-073, FR-075, FR-083, FR-084, FR-085, NFR-014, NFR-015, C-001, C-002, C-003, C-004, C-005, C-008, C-010, C-013](../requirements.md)

## Preconditions

- The Adopting Developer has a copy of the public repository.
- The Adopting Developer has access to the Data Providers and the Budget App their household uses.

## Main Success Scenario

1. Adopting Developer opens the Configuration Templates.
2. Slussen's repository shows for each Data Provider and for the Budget App of this build a Configuration Template that states what to fill in and where to get it.
3. Adopting Developer fills in a Build Configuration, kept outside the repository, with their Budgets, Bank Accounts with their Start Dates, Account Mappings, Transfer Descriptions, Request Limits and service credentials (BR-007).
4. Adopting Developer builds Slussen.
5. Slussen's build checks that the Build Configuration is complete and consistent and includes it in the built app.
6. Adopting Developer installs the built app directly on their own phone.
7. Slussen starts with the configured Budgets, Bank Accounts and Account Mappings (UC-008), and the Adopting Developer is from now on the Operator of this build.

## Alternative Flows

### A1: Build Configuration Incomplete or Inconsistent

**Trigger:** The Build Configuration lacks a required entry or maps two Bank Accounts to one Budget Account (step 5)  
**Flow:**

1. Slussen's build names each missing or inconsistent entry and produces no app.
2. Use case continues at step 3.

### A2: Data Provider Missing

**Trigger:** A Bank of the household is reached by no Data Provider of this build and offers no Transaction File (step 3)  
**Flow:**

1. Adopting Developer adds a Data Provider as its own part, stating whether it gives every transaction a Stable Reference.
2. Slussen applies its existing rules for comparing, reviewing and deciding to the new part's Bank Transactions.
3. Use case continues at step 3.

### A3: Bank Export Format Missing

**Trigger:** A Bank of the household offers a Transaction File in an export format that no File Pre-processor of this build converts (step 3)  
**Flow:**

1. Adopting Developer adds one File Pre-processor that converts the Bank's export format into the Standard Import Format, gives every transaction a reference and declares whether the Bank supplied it or the pre-processor derived it, and declares the Upload Window of the File Format.
2. Slussen applies its existing rules for importing, comparing, reviewing and deciding to the converted transactions (UC-002 BR-007).
3. Use case continues at step 3.

### A4: Another Budget App

**Trigger:** The household uses a Budget App other than the one of this build (step 3)  
**Flow:**

1. Adopting Developer replaces only the part of Slussen that talks to the Budget App.
2. Use case continues at step 3.

### A5: Built App Leaves the Adopting Developer's Control

**Trigger:** The built app is copied, shared or lost (step 6)  
**Flow:**

1. Adopting Developer revokes and replaces every service credential included in it.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- A built app with the Adopting Developer's Build Configuration runs on their phone.
- The repository is unchanged by the build and holds none of the Adopting Developer's data.

### Failure Postconditions

- The repository holds no real Bank Account names or numbers, Budget identifiers, transactions or credentials.
- No built app is published.

## Business Rules

### BR-001: Templates Without Real Values

The repository provides a Configuration Template for each Data Provider and for the Budget App of this build. A template shows what is needed and where to get it and never holds a working configuration.

### BR-002: Build Configuration Outside the Repository

The Build Configuration is kept outside the repository and is included in the app only when it is built. Each installation is built for one Operator.

### BR-003: A Built App Is Never Published

A built app is installed directly on the Operator's phone and is never published, because the credentials can be extracted from it.

### BR-004: Rotate Exposed Credentials

When a built app leaves the Operator's control, every service credential included in it is revoked and replaced before Slussen is used again.

### BR-005: Every Source Declares Its References

Every Data Provider states whether it gives each transaction a Stable Reference. Every File Pre-processor gives each transaction a reference and declares whether the Bank supplied it or the pre-processor derived it (UC-002 BR-002). Transactions without a Stable Reference are reviewed, never guessed (UC-003 BR-012). Nothing outside a Data Provider or a File Pre-processor depends on a particular Bank or export format.

### BR-006: Core Rules Stay Untouched

Adding a Data Provider or a File Pre-processor, or replacing the Budget App, changes none of the rules that fetch, compare, review and decide; everything that depends on one Budget App's behaviour lives in that Budget App's own part.

### BR-007: Start Date When Taking Over From Another Import Path

The Start Date of each Bank Account is optional. When another import path, such as the Predecessor App, already fed a Bank Account's Budget Account, the Adopting Developer sets the Start Date so that Slussen offers nothing from before it (UC-003 BR-018); a transaction after it that looks like one the other import path already sent is held for the Operator's decision (UC-003 BR-007). Slussen continues no history, identity or Import Identifier of another import path.
