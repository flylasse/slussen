# Use Case: Set Up an Own Build

## Overview

**Use Case ID:** UC-012  
**Use Case Name:** Set Up an Own Build  
**Primary Actor:** Adopting Developer  
**Goal:** Obtain a working Slussen for the Adopting Developer's own household, built from the public repository or installed from an app someone else built, with no credential in the repository or the built app, and set up in the app, without changing how Slussen compares, reviews and decides  
**Trigger:** Adopting Developer decides to use Slussen for their own household  
**Status:** Approved  

**Requirements:** [FR-070, FR-071, FR-072, FR-073, FR-083, FR-084, FR-085, FR-093, NFR-014, NFR-015, NFR-021, C-001, C-002, C-003, C-004, C-005, C-008, C-010, C-013](../requirements.md)

## Preconditions

- The Adopting Developer has access to the Data Providers and the Budget App their household uses.

## Main Success Scenario

1. Adopting Developer gets their own copy of the public repository by cloning or forking it (BR-008) and opens the Configuration Template.
2. Slussen's repository shows the Configuration Template for the signing setup of an own release build, stating what to fill in and where to get it.
3. Adopting Developer fills in a Build Configuration with the signing setup in the location the repository ignores, next to the Configuration Template (BR-002, BR-011); the default Card Payment and Bank Transfer Descriptions ship with the repository and the Operator edits them in the app (UC-010 BR-007).
4. Adopting Developer builds Slussen.
5. Slussen's build reads the Build Configuration from that location only (BR-009), checks that it is complete, signs the app with it, and includes no service credential (BR-012).
6. Adopting Developer installs the built app on their own phone.
7. Slussen starts and lets the Operator enter the service credentials and connect Budgets and Bank Accounts in the app (UC-013), and the Adopting Developer is from now on the Operator of this installation.

## Alternative Flows

### A1: Build Configuration Incomplete

**Trigger:** The Build Configuration lacks a required entry or holds an entry its Configuration Template does not define (step 5)  
**Flow:**

1. Slussen's build names each missing or unknown entry and produces no app.
2. Use case continues at step 3.

### A2: Data Provider Missing

**Trigger:** A Bank of the household is reached by no Data Provider of this build and offers no Transaction File (step 3)  
**Flow:**

1. Adopting Developer adds a Data Provider as its own part, declaring whether it gives every transaction a Stable Reference, the credentials the Operator enters for it, its Request Limit and its default Fetch Cooldown.
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

### A5: No Own Release Build

**Trigger:** Adopting Developer builds a debug build instead of an own release build (step 3)  
**Flow:**

1. Adopting Developer builds without a Build Configuration, which a debug build does not need (BR-011).
2. Use case continues at step 6.

### A6: Build Configuration Found Elsewhere

**Trigger:** Slussen's build finds a Build Configuration, or part of one recognised by its keys or by a value marked secret (BR-009), outside the location the repository ignores, for example in a tracked file (step 5)  
**Flow:**

1. Slussen's build does not use it, produces no app and states that the Build Configuration belongs in the location the repository ignores, next to the Configuration Template.
2. Use case continues at step 3.

### A7: Install an App Someone Else Built

**Trigger:** Adopting Developer installs an app that someone else built from the public repository instead of building one (step 1)  
**Flow:**

1. Adopting Developer installs that app on their own phone; it holds no credential of the one who built it (BR-003).
2. Use case continues at step 7.

## Postconditions

### Success Postconditions

- Slussen runs on the Adopting Developer's phone, set up with their own credentials in the app.
- The repository is unchanged by the build and holds none of the Adopting Developer's data.

### Failure Postconditions

- The repository holds no real Bank Account names or numbers, Budget identifiers, transactions or credentials.
- No built app holds a service credential.
- No Build Configuration found outside the location the repository ignores is used in a built app.

## Business Rules

### BR-001: Template Without Real Values

The repository provides a Configuration Template for the signing setup of an own release build. It shows what is needed and where to get it and never holds a working configuration. What each Data Provider and the Budget App need is stated in the app where the Operator enters their credentials (UC-014 BR-002).

### BR-002: Build Configuration in an Ignored Location

The Build Configuration is optional and holds only the signing setup of an own release build. It is kept in a location the repository ignores, next to the Configuration Template, is never committed and is used only when the app is built.

### BR-003: A Built App Holds No Credential

A built app holds no service credential, so it can be installed by an Adopting Developer who did not build it; each Operator enters their own credentials in the app (UC-014). A built app is not distributed through an app store.

### BR-004: Rotate Exposed Credentials

When a phone with Slussen leaves the Operator's control, every service credential entered in it is revoked and replaced before Slussen is used again (C-004, UC-014 BR-006).

### BR-005: Every Source Declares Its References

Every Data Provider states whether it gives each transaction a Stable Reference. Every File Pre-processor gives each transaction a reference and declares whether the Bank supplied it or the pre-processor derived it (UC-002 BR-002). Transactions without a Stable Reference are reviewed, never guessed (UC-003 BR-012). Nothing outside a Data Provider or a File Pre-processor depends on a particular Bank or export format.

### BR-006: Core Rules Stay Untouched

Adding a Data Provider or a File Pre-processor, or replacing the Budget App, changes none of the rules that fetch, compare, review and decide; everything that depends on one Budget App's behaviour lives in that Budget App's own part.

### BR-007: Start Date When Taking Over From Another Import Path

When another import path, such as the Predecessor App, already fed a Budget Account, the Operator sets the Start Date of the Bank Account in the app when adding it (UC-013 BR-005), so that Slussen offers nothing from before it (UC-003 BR-018); a transaction after it that looks like one the Other Import Path already sent is held for the Operator's decision (UC-003 BR-007). Slussen continues no history, identity or Import Identifier of another import path.

### BR-008: Own Copy by Clone or Fork

The Adopting Developer gets their own copy of the repository by cloning or forking it. Either is safe, because the repository holds only the Configuration Template and no Build Configuration or credential. A fork of the public repository is itself public, so nothing personal is ever committed to it (BR-002).

### BR-009: Build Configuration Read Only From Its Location

Slussen's build reads the Build Configuration only from the location the repository ignores. A Build Configuration, or part of one, found anywhere else, such as in a tracked file, is never used: the build fails and states where the Build Configuration belongs. Slussen recognises a part of a Build Configuration by its recognisable keys or by a value marked secret; a plain value pasted somewhere without its key cannot be recognised as configuration and stays protected only by the rule that the repository holds no personal data (C-005) and by review.

### BR-010: The First Operator Builds the Same Way

The first Operator of Slussen is an Adopting Developer too: their own build follows this use case, with their Build Configuration in the same ignored location, and has no other path.

### BR-011: Only the Signing Setup in the Build Configuration

The Build Configuration holds only the signing setup of an own release build; a debug build, or an app someone else built, needs none. The Request Limit and the default Fetch Cooldown of each Data Provider are declared by its part. The service credentials are entered in the app (UC-014), and the Budgets, Bank Accounts, Account Mappings and Start Dates are set in the app (UC-013, UC-009), so that none of them needs a rebuild.

### BR-012: No Credential in the Repository or the Built App

No service credential is ever in the repository, the Build Configuration or a built app: the build has no input for one and includes none. Credentials exist only on the Operator's device, where the Operator entered them (UC-014 BR-003).
