# Use Case: Grant Bank Consent

## Overview

**Use Case ID:** UC-007  
**Use Case Name:** Grant Bank Consent  
**Primary Actor:** Operator  
**Secondary Actors:** Bank, Data Provider  
**Goal:** Let Slussen read the Operator's Bank Accounts at one Bank through a Data Provider until the Bank Consent expires, by granting or renewing the Bank Consent once for that Bank  
**Trigger:** Operator asks to grant or renew the Bank Consent of a Bank  
**Status:** Draft  

**Requirements:** [FR-010, FR-011, FR-042, FR-058, NFR-008, NFR-009, NFR-013, C-006, C-007](../requirements.md)

## Preconditions

- The Bank and its Bank Accounts are part of the Build Configuration with a Data Provider that needs a Bank Consent.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Operator asks to grant the Bank Consent of a Bank, or to renew it, from any place where Slussen shows that the Bank Consent is missing, expiring or expired.
2. Slussen starts a consent request for the Bank with the Data Provider.
3. Data Provider hands Slussen the way to the Bank's Strong Customer Authentication.
4. Slussen sends the Operator to the Bank's Strong Customer Authentication.
5. Operator completes the Strong Customer Authentication with the Bank.
6. Bank confirms the consent to the Data Provider, and the Operator returns to Slussen with the result.
7. Slussen checks that the result answers the most recent consent request for this Bank, arrives within 15 minutes of it and has not been used before.
8. Slussen stores the Bank Consent with its expiry date, replacing any earlier Bank Consent of this Bank.
9. Slussen identifies each Bank Account the Bank Consent covers as a Bank Account of the Build Configuration (UC-009 BR-004).
10. Slussen shows the Bank Consent as valid, with its expiry date and the Bank Accounts it covers.

## Alternative Flows

### A1: Authentication Not Completed

**Trigger:** Operator cancels the Strong Customer Authentication or the Bank refuses it (step 5)  
**Flow:**

1. Slussen stores no new Bank Consent and keeps any earlier Bank Consent of the Bank unchanged.
2. Slussen shows that the consent was not granted.
3. Use case ends.

### A2: Result Not Accepted

**Trigger:** The result does not answer the most recent consent request for this Bank, arrives later than 15 minutes after it, was used before, or cannot be read (step 7)  
**Flow:**

1. Slussen rejects the result and stores no Bank Consent.
2. Slussen asks the Operator to start the consent again.
3. Use case ends.

### A3: Data Provider Refuses

**Trigger:** The Data Provider refuses to start the consent request or to confirm the consent (step 3)  
**Flow:**

1. Slussen shows the Data Provider's refusal as the Blocked Reason of the Bank's Bank Accounts.
2. Use case ends.

### A4: Bank Account Only Probably Recognised

**Trigger:** A Bank Account the Bank Consent covers can only be proposed as a likely match (step 9)  
**Flow:**

1. Slussen does not use that Bank Account's transactions yet and asks the Operator to confirm the proposed Bank Account (UC-009).
2. Use case continues at step 10.

### A5: Bank Account Not Identified

**Trigger:** Two Bank Accounts of the Build Configuration are possible for one covered Bank Account, or two Account Mappings would point to the same one (step 9)  
**Flow:**

1. Slussen stops for that Bank Account and shows the Blocked Reason "unrecognised Bank Account".
2. Use case continues at step 10.

## Postconditions

### Success Postconditions

- The Bank has a valid Bank Consent for the Data Provider with its expiry date.
- Every Bank Account the Bank Consent covers is identified with certainty, waits for the Operator's confirmation, or shows why it is not identified.

### Failure Postconditions

- No Bank Consent is stored that the Operator did not grant through the Bank's Strong Customer Authentication.
- An earlier Bank Consent of the Bank stays as it was.
- No Bank Account's transactions are used before the Bank Account is identified with certainty.

## Business Rules

### BR-001: One Authentication per Bank

One Strong Customer Authentication grants the Bank Consent for all of the Operator's Bank Accounts at that Bank that the Data Provider reads.

### BR-002: One-Time Consent Request

A consent request is valid for at most 15 minutes, is accepted at most once, and is rejected when it is not the most recent request for that Bank.

### BR-003: Renewal Is a New Grant

Renewing a Bank Consent follows the same steps as granting it and replaces the earlier Bank Consent of the Bank. The action is offered wherever Slussen shows a Bank Consent as missing, expiring or expired.

### BR-004: Expiry Comes From the Bank

The expiry date is the one the Bank grants, about 90 days after the Strong Customer Authentication. Only the holder of the Bank Accounts can renew a Bank Consent.

### BR-005: Bank Consents Stay on This Installation

Bank Consents and consent requests in progress are never included in device backups or transfers and are not restored after a reinstall; the Operator grants each Bank Consent again.

### BR-006: Consent Data Is Never Logged

Bank Consent data, authentication codes and one-time values never appear in device logs, in any logging mode.
