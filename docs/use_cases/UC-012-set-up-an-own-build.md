# Use Case: Set Up an Own Build

## Overview

**Use Case ID:** UC-012  
**Use Case Name:** Set Up an Own Build  
**Primary Actor:** Adopting Developer  
**Goal:** Obtain a working Slussen for the Adopting Developer's own household, built from the public repository with their own Bank Accounts, Budgets, Account Mappings and credentials, without changing how Slussen compares, reviews and decides  
**Trigger:** Adopting Developer decides to build Slussen from the public repository for their own household  
**Status:** Approved  

**Requirements:** [FR-056, FR-070, FR-071, FR-072, FR-073, FR-075, FR-083, FR-084, FR-085, FR-093, FR-096, NFR-014, NFR-015, C-001, C-002, C-003, C-004, C-005, C-008, C-010, C-013](../requirements.md)

## Preconditions

- The Adopting Developer has their own copy of the public repository, made by cloning or forking it (BR-008).
- The Adopting Developer has access to the Data Providers and the Budget App their household uses.

## Main Success Scenario

1. Adopting Developer opens the Configuration Templates.
2. Slussen's repository shows for each Data Provider and for the Budget App of this build a Configuration Template that states what to fill in and where to get it.
3. Adopting Developer fills in a Build Configuration in the location the repository ignores, next to the Configuration Templates (BR-002), with their Budgets, Bank Accounts with their Configuration Keys (BR-011) and Start Dates, Account Mappings, Request Limits and service credentials; the default Card Payment and Bank Transfer Descriptions ship with the repository and the Operator edits them in the app (BR-007, UC-010 BR-007).
4. Adopting Developer builds Slussen.
5. Slussen's build reads the Build Configuration from that location only (BR-009), checks that it is complete and consistent, and includes it in the built app.
6. Adopting Developer installs the built app directly on their own phone.
7. Slussen starts with the configured Budgets, Bank Accounts and Account Mappings (UC-008), and the Adopting Developer is from now on the Operator of this build.

## Alternative Flows

### A1: Build Configuration Incomplete or Inconsistent

**Trigger:** The Build Configuration lacks a required entry, maps two Bank Accounts to one Budget Account or gives two Bank Accounts the same Configuration Key (step 5)  
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

1. Adopting Developer adds one File Pre-processor that converts the Bank's export format into the Standard Import Format, gives every transaction a reference and declares whether the Bank supplied it or the pre-processor derived it.
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

### A6: Build Configuration Found Elsewhere

**Trigger:** Slussen's build finds a Build Configuration, or part of one recognised by its keys or by a value marked secret (BR-009), outside the location the repository ignores, for example in a tracked file (step 5)  
**Flow:**

1. Slussen's build does not use it, produces no app and states that the Build Configuration belongs in the location the repository ignores, next to the Configuration Templates.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- A built app with the Adopting Developer's Build Configuration runs on their phone.
- The repository is unchanged by the build and holds none of the Adopting Developer's data.

### Failure Postconditions

- The repository holds no real Bank Account names or numbers, Budget identifiers, transactions or credentials.
- No built app is published.
- No Build Configuration found outside the location the repository ignores is used in a built app.

## Business Rules

### BR-001: Templates Without Real Values

The repository provides a Configuration Template for each Data Provider and for the Budget App of this build. A template shows what is needed and where to get it and never holds a working configuration.

### BR-002: Build Configuration in an Ignored Location

The Build Configuration is kept in a location the repository ignores, next to the Configuration Templates. It is never committed and is included in the app only when it is built. Each installation is built for one Operator.

### BR-003: A Built App Is Never Published

A built app is installed directly on the Operator's phone and is never published, because the credentials can be extracted from it.

### BR-004: Rotate Exposed Credentials

When a built app leaves the Operator's control, every service credential included in it is revoked and replaced before Slussen is used again.

### BR-005: Every Source Declares Its References

Every Data Provider states whether it gives each transaction a Stable Reference. Every File Pre-processor gives each transaction a reference and declares whether the Bank supplied it or the pre-processor derived it (UC-002 BR-002). Transactions without a Stable Reference are reviewed, never guessed (UC-003 BR-012). Nothing outside a Data Provider or a File Pre-processor depends on a particular Bank or export format.

### BR-006: Core Rules Stay Untouched

Adding a Data Provider or a File Pre-processor, or replacing the Budget App, changes none of the rules that fetch, compare, review and decide; everything that depends on one Budget App's behaviour lives in that Budget App's own part.

### BR-007: Start Date When Taking Over From Another Import Path

The Start Date of each Bank Account is optional. When another import path, such as the Predecessor App, already fed a Bank Account's Budget Account, the Adopting Developer sets the Start Date so that Slussen offers nothing from before it (UC-003 BR-018); a transaction after it that looks like one the Other Import Path already sent is held for the Operator's decision (UC-003 BR-007). Slussen continues no history, identity or Import Identifier of another import path.

### BR-008: Own Copy by Clone or Fork

The Adopting Developer gets their own copy of the repository by cloning or forking it. Either is safe, because the repository holds only Configuration Templates and no Build Configuration. A fork of the public repository is itself public, so nothing personal is ever committed to it (BR-002).

### BR-009: Build Configuration Read Only From Its Location

Slussen's build reads the Build Configuration only from the location the repository ignores. A Build Configuration, or part of one, found anywhere else, such as in a tracked file, is never used: the build fails and states where the Build Configuration belongs. Slussen recognises a part of a Build Configuration by its recognisable keys or by a value marked secret; a plain value pasted somewhere without its key cannot be recognised as configuration and stays protected only by the rule that the repository holds no personal data (C-005) and by review.

### BR-010: The First Operator Builds the Same Way

The first Operator of Slussen is an Adopting Developer too: their own build follows this use case, with their Build Configuration in the same ignored location, and has no other path.

### BR-011: Configuration Keys Never Change

Each Bank Account of the Build Configuration has a Configuration Key that the Adopting Developer sets when first adding the Bank Account and never changes afterwards; its name, its position and its other entries can change freely. The key is how Slussen finds the Bank Account's stored identification, Account Mapping and history again after a rebuild (UC-009 BR-010). Removing an entry or giving it a new Configuration Key does not delete the stored Bank Account: it is kept, out of use, with its history and lock, and the new key is a new Bank Account (UC-009 BR-011).
