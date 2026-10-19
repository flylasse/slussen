# Use Case: Set Up an Own Build

## Overview

**Use Case ID:** UC-012  
**Use Case Name:** Set Up an Own Build  
**Primary Actor:** Adopting Developer  
**Goal:** Obtain a working Slussen for the Adopting Developer's own household, built from the public repository or installed from an app someone else built, with no credential in the repository or the built app, and set up in the app, without changing how Slussen compares, reviews and decides  
**Trigger:** Adopting Developer decides to use Slussen for their own household  
**Status:** Approved  

**Requirements:** [FR-070, FR-071, FR-072, FR-073, FR-083, FR-084, FR-085, FR-103, NFR-014, NFR-015, NFR-021, C-001, C-002, C-003, C-004, C-005, C-008, C-010, C-013](../requirements.md)

## Preconditions

- The Adopting Developer has access to the Data Providers and the Budget App their household uses.

## Main Success Scenario

1. Adopting Developer gets their own copy of the public repository by cloning or forking it (BR-008).
2. Adopting Developer keeps the signing values of an own release build outside the repository, where the repository's how-to says (BR-001).
3. Adopting Developer builds an own release build of Slussen.
4. Slussen's build takes the signing values from outside the repository, signs the app with the Adopting Developer's own key, and includes no service credential (BR-001, BR-012).
5. Adopting Developer installs the built app on their own phone (BR-009).
6. Slussen starts and lets the Operator enter the service credentials (UC-014) and connect Budgets and Bank Accounts in the app (UC-013), and the Adopting Developer is from now on the Operator of this installation.

## Alternative Flows

### A1: Signing Values Missing

**Trigger:** An own release build lacks one or more of its signing values (step 4)  
**Flow:**

1. Slussen's build names each missing signing value and where it goes, and produces no app (BR-002).
2. Use case continues at step 2.

### A2: Data Provider Missing

**Trigger:** A Bank of the household is reached by no Data Provider of this build and offers no Transaction File (step 3)  
**Flow:**

1. Adopting Developer adds a Data Provider as its own part, declaring its name, whether it gives every transaction a Stable Reference, the credentials the Operator enters for it, its Request Limit (the limit the Data Provider publishes, with its source, or, when it publishes none, a cautious default with the range within which the Operator can change it), its default Fetch Cooldown and whether it needs a Bank Consent.
2. Slussen applies its existing rules for comparing, reviewing and deciding to the new part's Bank Transactions, and gives the new Data Provider its own entry in Settings, its own page and its settings, generated from what its part declares, with no screen written for it (UC-010 BR-010).
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

1. Adopting Developer builds without signing values, which a debug build does not need (BR-001, BR-009).
2. Use case continues at step 5.

### A6: Install an App Someone Else Built

**Trigger:** Adopting Developer installs an app that someone else built from the public repository instead of building one (step 1)  
**Flow:**

1. Adopting Developer installs that app on their own phone; it holds no credential of the one who built it (BR-003).
2. Use case continues at step 6.

## Postconditions

### Success Postconditions

- Slussen runs on the Adopting Developer's phone, set up with their own credentials in the app.
- The repository is unchanged by the build and holds none of the Adopting Developer's data.

### Failure Postconditions

- The repository holds no real Bank Account names or numbers, Budget identifiers, transactions, credentials or signing values.
- No built app holds a service credential.

## Business Rules

### BR-001: Signing Values Outside the Repository

Only an own release build needs signing values: where the Adopting Developer's own signing key is kept, its alias and its passwords. The Adopting Developer keeps them outside the repository, in their own build settings or environment, never in the repository or in a folder of it; the repository's how-to names each signing value and where it goes. A debug build, or an app someone else built, needs none.

### BR-002: A Release Build Without Its Signing Values Fails Clearly

An own release build that lacks a signing value produces no app and names each missing signing value and where it goes, never its content. It never signs with another key instead.

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

The Adopting Developer gets their own copy of the repository by cloning or forking it. Either is safe, because the repository holds no signing value or credential. A fork of the public repository is itself public, so nothing personal is ever committed to it (BR-001).

### BR-009: The Operator's Data Lives in an Own Release Build

The Operator keeps their data in an own release build, not in a debug build. An update installs over an installation only when it is signed with the same key; a debug key belongs to one machine, and losing the key forces an uninstall, which deletes the app's local data, including its history, Sent Records and credentials. A debug build can also be debugged, so its private data can be read through a cable connection. A debug build is for trying Slussen out.

### BR-010: The First Operator Builds the Same Way

The first Operator of Slussen is an Adopting Developer too: their own build follows this use case, with their signing values outside the repository like every Adopting Developer's, and has no other path.

### BR-011: Nothing but the Signing Values Comes From Outside the App

Apart from the signing values of an own release build, nothing about an installation is given to the build. The Request Limit of each Data Provider, published or a default the Operator can change (UC-001 BR-007), and its default Fetch Cooldown are declared by its part. The service credentials are entered in the app (UC-014), and the Budgets, Bank Accounts, Account Mappings and Start Dates are set in the app (UC-013, UC-009), so that none of them needs a rebuild. The default Card Payment and Bank Transfer Descriptions ship with the repository and the Operator edits them in the app (UC-010 BR-007).

### BR-012: No Credential in the Repository or the Built App

No service credential is ever in the repository, among the signing values or in a built app: the build has no input for one and includes none. Credentials exist only on the Operator's device, where the Operator entered them (UC-014 BR-003).
