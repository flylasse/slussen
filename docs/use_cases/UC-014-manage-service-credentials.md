# Use Case: Manage Service Credentials

## Overview

**Use Case ID:** UC-014  
**Use Case Name:** Manage Service Credentials  
**Primary Actor:** Operator  
**Secondary Actors:** Budget App, Data Provider  
**Goal:** Give Slussen valid service credentials for the Budget App and for each Data Provider the household uses, kept only on the phone, and change or remove them when needed  
**Trigger:** Operator asks in Connections to set up a Data Provider of this build, or on a service's page to enter, replace or remove its service credentials, or Connect Budgets and Bank Accounts reaches its credentials step  
**Status:** Approved  

**Requirements:** [FR-064, FR-066, FR-101, FR-102, FR-103, NFR-008, NFR-009, NFR-012, NFR-021, C-004, C-008](../requirements.md)

## Preconditions

- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows Connections: the Budget App and each Data Provider whose service credentials are entered, each with its status, and the Data Providers of this build not yet set up (BR-007).
2. Operator chooses to set up a Data Provider, or opens the page of a service and chooses its credentials, and enters the credentials its part asks for, as Slussen describes them with where to get them (BR-002).
3. Slussen checks the credentials with the service (BR-002).
4. Slussen stores the accepted credentials on the device, encrypted with a key that never leaves the device, replacing earlier ones (BR-003), and keeps nothing it no longer needs.
5. Slussen records in the Decision Journal that the credentials of that service were changed, without any credential (UC-011 BR-003).
6. Slussen shows the credentials as accepted on the service's page, or returns to Connect Budgets and Bank Accounts when that started this use case.

## Alternative Flows

### A1: Credentials Refused

**Trigger:** The service answers that the credentials are not valid or not allowed (step 3)  
**Flow:**

1. Slussen stores nothing, keeps any earlier credentials unchanged and shows the refusal.
2. Use case continues at step 2.

### A2: Service Cannot Be Reached

**Trigger:** The service does not answer within the time limit of NFR-016 or is unavailable (step 3)  
**Flow:**

1. Slussen stores nothing, keeps any earlier credentials unchanged and shows that the service could not be reached.
2. Use case ends.

### A3: Remove Credentials

**Trigger:** Operator chooses, on the page of the Budget App or of a Data Provider, to remove its credentials (step 2)  
**Flow:**

1. Slussen asks the Operator to confirm, stating that the service will not be contacted until new credentials are entered.
2. Operator confirms, or cancels and the use case ends.
3. Slussen deletes the credentials and shows the service's page with the Blocked Reason "credentials missing" for the Sources or Budgets concerned (BR-005).
4. Slussen records the removal in the Decision Journal.
5. Use case ends.

### A4: Operator Cancels

**Trigger:** Operator does not enter the credentials (step 2)  
**Flow:**

1. Slussen keeps any earlier credentials unchanged.
2. Use case ends.

## Postconditions

### Success Postconditions

- The chosen service has accepted credentials, stored only on the device and encrypted.
- The change is recorded in the Decision Journal without any credential, unless the Decision Journal is switched off.

### Failure Postconditions

- No credential that the service refused replaces accepted ones.
- No credential appears in a log, the Decision Journal, a Journal Export, a device backup or a built app.

## Business Rules

### BR-001: Credentials Are Entered in the App

The service credentials of the Budget App and of each Data Provider are entered by the Operator in the app, never in the repository, among the signing values of a build or in a built app (UC-012 BR-012).

### BR-002: What Each Part Asks For

Each part declares the credentials it needs and how Slussen checks them. In this build: for YNAB, a personal access token, checked by listing the Budgets; for SimpleFIN, a setup token, which Slussen exchanges once for its access credential and then discards; for Enable Banking, the application id and the private key file of the household's own registered Enable Banking application, the key file chosen with the device's file picker. Each household registers its own Enable Banking application, as Enable Banking requires (C-008).

### BR-003: Stored Only on the Device, Encrypted

Credentials are stored only on the device, encrypted with a key that never leaves the device. They are never in a built app, a device backup or device-to-device transfer, a Journal Export, the Decision Journal or a log, in any logging mode (NFR-008, NFR-021).

### BR-004: Entered Again After a Reinstall

Credentials are not restored after a reinstall or on a new device: the Operator enters them again, as each Bank Consent is granted again (UC-007 BR-005). Uploads after a restore still wait for the Recovery Check (UC-006 BR-017).

### BR-005: Refused or Missing Credentials

Whenever the Budget App or a Data Provider refuses Slussen's credentials, or none are entered, Slussen does not contact it and shows the Blocked Reason "credentials refused" or "credentials missing" with the action that removes it: enter the credentials here.

### BR-006: Rotate Exposed Credentials

When a phone with Slussen leaves the Operator's control, the Operator revokes every credential entered in it at its service and replaces it before Slussen is used again (C-004).

### BR-007: Connections Lists the Services

Connections lists the Budget App and each Data Provider whose credentials are entered, each with one status line, such as connected with the time of its last Fetch, "credentials refused", or the nearest expiry of a Bank Consent within the consent warning lead time (UC-008 BR-002), and below them every Data Provider of this build whose credentials are not entered, also after they were removed, each with the action to set it up. Each listed service opens its own page (UC-010 BR-010). The Budget App is always listed, with "credentials missing" until its credentials are accepted.
