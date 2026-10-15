# Use Case: Grant Bank Consent

## Overview

**Use Case ID:** UC-007  
**Use Case Name:** Grant Bank Consent  
**Primary Actor:** Operator  
**Secondary Actors:** Bank, Data Provider  
**Goal:** Decide whether Slussen may read the Operator's Bank Accounts at one Bank through a Data Provider, by granting or renewing the Bank Consent once for that Bank until it expires, or by revoking it  
**Trigger:** Operator asks to grant, renew or revoke the Bank Consent of a Bank  
**Status:** Approved  

**Requirements:** [FR-010, FR-011, FR-042, FR-058, FR-082, NFR-008, NFR-009, NFR-013, C-006, C-007](../requirements.md)

## Preconditions

- The Bank and its Bank Accounts are part of the Build Configuration with a Data Provider that needs a Bank Consent.
- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Operator asks to grant the Bank Consent of a Bank, or to renew it, from any place where Slussen shows that the Bank Consent is missing, expiring or expired.
2. Slussen starts a Consent Request for the Bank with the Data Provider, which makes every earlier Consent Request of this Bank unanswerable.
3. Data Provider hands Slussen the way to the Bank's Strong Customer Authentication.
4. Slussen sends the Operator to the Bank's Strong Customer Authentication.
5. Operator completes the Strong Customer Authentication with the Bank.
6. Bank confirms the consent to the Data Provider, and the Operator returns to Slussen with the result.
7. Slussen checks that the result answers the most recent Consent Request for this Bank, arrives within the Consent Request lifetime and has not been used before (BR-002).
8. Slussen stores the Bank Consent with the expiry date the Bank or Data Provider reports and the Bank Session it opens, replacing any earlier Bank Consent and Bank Session of this Bank (BR-004).
9. Slussen keeps the Bank Accounts the Bank Consent covers as Offered Bank Accounts (UC-009 BR-009) and identifies each as a Bank Account of the Build Configuration (UC-009 BR-004).
10. Slussen shows the Bank Consent as valid, with its expiry date and the Bank Accounts it covers.

## Alternative Flows

### A1: Authentication Not Completed

**Trigger:** Operator cancels the Strong Customer Authentication or the Bank refuses it (step 5)  
**Flow:**

1. Slussen stores no new Bank Consent and keeps any earlier Bank Consent of the Bank unchanged.
2. Slussen shows that the consent was not granted.
3. Use case ends.

### A2: Result Not Accepted

**Trigger:** The result does not answer the most recent Consent Request for this Bank, arrives after the Consent Request lifetime has passed, was used before, or cannot be read (step 7)  
**Flow:**

1. Slussen rejects the result and stores no Bank Consent.
2. Slussen asks the Operator to start the consent again.
3. Use case ends.

### A3: Data Provider Refuses

**Trigger:** The Data Provider refuses to start the Consent Request (step 3)  
**Flow:**

1. Slussen stores no new Bank Consent and keeps any earlier Bank Consent of the Bank unchanged.
2. Slussen shows the Data Provider's refusal as the Blocked Reason of the Bank's Bank Accounts.
3. Use case ends.

### A4: Data Provider Does Not Confirm the Consent

**Trigger:** The Data Provider refuses to confirm the Bank Consent after the Strong Customer Authentication (step 6)  
**Flow:**

1. Slussen stores no new Bank Consent and keeps any earlier Bank Consent of the Bank unchanged.
2. Slussen shows the Data Provider's refusal as the Blocked Reason of the Bank's Bank Accounts.
3. Use case ends.

### A5: Bank Account Only Probably Recognised

**Trigger:** A Bank Account the Bank Consent covers can only be proposed as a likely match (step 9)  
**Flow:**

1. Slussen does not use that Bank Account's transactions yet and asks the Operator to confirm the proposed Bank Account (UC-009).
2. Use case continues at step 10.

### A6: Bank Account Not Identified

**Trigger:** Two Bank Accounts of the Build Configuration are possible for one covered Bank Account, or two Account Mappings would point to the same one (step 9)  
**Flow:**

1. Slussen stops for that Bank Account and shows the Blocked Reason "unrecognised Bank Account".
2. Use case continues at step 10.

### A7: Revoke a Bank Consent

**Trigger:** Operator asks to revoke the Bank Consent of a Bank that has one (step 1)  
**Flow:**

1. Slussen asks the Operator to confirm, stating that the Bank Accounts of this Bank will not be fetched until a new Bank Consent is granted.
2. Operator confirms, or cancels and the use case ends.
3. Slussen asks the Data Provider to end the Bank Consent.
4. Slussen deletes the stored Bank Consent with its Bank Session and every Consent Request of this Bank, also when the Data Provider does not confirm the end (BR-007).
5. Slussen shows the Bank Consent as revoked, the Blocked Reason "missing consent" for the Bank's Bank Accounts, and, when the Data Provider did not confirm the end, that the Operator can also end the consent at the Bank.
6. Use case ends.

## Postconditions

### Success Postconditions

- The Bank has a valid Bank Consent for the Data Provider with its expiry date.
- Every Bank Account the Bank Consent covers is identified with certainty, waits for the Operator's confirmation, or shows why it is not identified.
- After a revocation, Slussen holds no Bank Consent, Bank Session or Consent Request of the Bank and reads none of its Bank Accounts until a new Bank Consent is granted.

### Failure Postconditions

- No Bank Consent is stored that the Operator did not grant through the Bank's Strong Customer Authentication.
- An earlier Bank Consent of the Bank stays as it was, unless the Operator confirmed its revocation.
- No Bank Account's transactions are used before the Bank Account is identified with certainty.

## Business Rules

### BR-001: One Authentication per Bank

One Strong Customer Authentication grants the Bank Consent for all of the Operator's Bank Accounts at that Bank that the Data Provider reads.

### BR-002: One-Time Consent Request

A Consent Request is valid for at most the Consent Request lifetime (15 minutes by default, a number the Operator can change, UC-010), is accepted at most once, and is rejected when it is not the most recent Consent Request for that Bank.

### BR-003: Renewal Is a New Grant

Renewing a Bank Consent follows the same steps as granting it and replaces the earlier Bank Consent of the Bank. The action is offered wherever Slussen shows a Bank Consent as missing, expiring or expired.

### BR-004: Expiry Comes From the Bank or Data Provider

The expiry date is the one the Bank or Data Provider reports when the Bank Consent is granted or renewed; Slussen assumes no fixed consent length (C-007). The warning before expiry uses this date and the consent warning lead time (UC-008 BR-002). Only the holder of the Bank Accounts can renew a Bank Consent.

### BR-005: Bank Consents Stay on This Installation

Bank Consents, their Bank Sessions and Consent Requests in progress are never included in device backups or transfers and are not restored after a reinstall; the Operator grants each Bank Consent again.

### BR-006: Consent Data Is Never Logged

Bank Consent data, Bank Sessions, authentication codes and one-time values never appear in device logs, in any logging mode.

### BR-007: Revocation Removes Everything Stored

Revoking a Bank Consent deletes the stored Bank Consent, its Bank Session and every Consent Request of the Bank, whether or not the Data Provider confirms that it ended the consent. Slussen then contacts neither the Bank nor the Data Provider for that Bank until a new Bank Consent is granted. The Operator's Bank Accounts, Account Mappings, Bank Transactions and Sent Record are kept.
