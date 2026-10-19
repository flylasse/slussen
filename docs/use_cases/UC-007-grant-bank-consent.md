# Use Case: Grant Bank Consent

## Overview

**Use Case ID:** UC-007  
**Use Case Name:** Grant Bank Consent  
**Primary Actor:** Operator  
**Secondary Actors:** Bank, Data Provider  
**Goal:** Decide whether Slussen may read the Operator's Bank Accounts at one Bank through a Data Provider, by granting or renewing the Bank Consent once for that Bank until it expires, or by revoking it  
**Trigger:** Operator asks to grant, renew or revoke the Bank Consent of a Bank, on the Data Provider's page, opened from its entry in Settings, or wherever Slussen shows the Bank Consent as missing, expiring or expired  
**Status:** Approved  

**Requirements:** [FR-010, FR-011, FR-042, FR-058, FR-082, FR-098, FR-103, NFR-004, NFR-008, NFR-009, NFR-013, NFR-022, C-006, C-007](../requirements.md)

## Preconditions

- The Operator has entered service credentials, accepted by it, for a Data Provider that needs a Bank Consent (UC-014).
- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Operator asks to grant the Bank Consent of a Bank that the Data Provider reaches, also before any of its Bank Accounts is added (UC-013), or to renew it, where Slussen shows the Data Provider's Bank Consents, each with its status and expiry date (BR-008), or from any place where Slussen shows that the Bank Consent is missing, expiring or expired.
2. Slussen starts a Consent Request for the Bank with the Data Provider, which makes every earlier Consent Request of this Bank unanswerable.
3. Data Provider hands Slussen the way to the Bank's Strong Customer Authentication.
4. Slussen sends the Operator to the Bank's Strong Customer Authentication.
5. Operator completes the Strong Customer Authentication with the Bank.
6. Bank confirms the consent to the Data Provider, and the Operator returns to Slussen with the result.
7. Slussen checks that the result answers the most recent Consent Request for this Bank, arrives within the Consent Request lifetime and has not been used before (BR-002).
8. Slussen stores the Bank Consent with the expiry date the Bank or Data Provider reports and the Bank Session it opens, replacing any earlier Bank Consent and Bank Session of this Bank (BR-004).
9. Slussen keeps the Bank Accounts the Bank Consent covers as Offered Bank Accounts (UC-009 BR-009) and identifies each that the Operator already added (UC-009 BR-004); the others are imported and are Bank Accounts to map (UC-013 BR-012), except the Left-Out Bank Accounts, which stay not imported until the Operator imports them again (UC-013 BR-011).
10. Slussen shows the Bank Consent as valid, with its expiry date and the Bank Accounts it covers (BR-009).

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

**Trigger:** Two of the Bank Accounts the Operator added are possible for one covered Bank Account, or two Account Mappings would point to the same one (step 9)  
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

Renewing a Bank Consent follows the same steps as granting it and replaces the earlier Bank Consent of the Bank. The action is offered on the Data Provider's page and wherever Slussen shows a Bank Consent as missing, expiring or expired.

### BR-004: Expiry Comes From the Bank or Data Provider

The expiry date is the one the Bank or Data Provider reports when the Bank Consent is granted or renewed; Slussen assumes no fixed consent length (C-007). The warning before expiry uses this date and the consent warning lead time (UC-008 BR-002). Only the holder of the Bank Accounts can renew a Bank Consent.

### BR-005: Bank Consents Stay on This Installation

Bank Consents, their Bank Sessions and Consent Requests in progress are never included in device backups or transfers and are not restored after a reinstall; the Operator grants each Bank Consent again.

### BR-006: Consent Data Is Never Logged

Bank Consent data, Bank Sessions, authentication codes and one-time values never appear in device logs, in any logging mode.

### BR-007: Revocation Removes Everything Stored

Revoking a Bank Consent deletes the stored Bank Consent, its Bank Session and every Consent Request of the Bank, whether or not the Data Provider confirms that it ended the consent. Slussen then contacts neither the Bank nor the Data Provider for that Bank until a new Bank Consent is granted. The Operator's Bank Accounts, Account Mappings, Bank Transactions and Sent Record are kept.

### BR-008: Bank Consents Live on the Data Provider's Page

The Bank Consents of a Data Provider that needs one are shown on that Data Provider's page, opened from its entry in Settings (UC-010 BR-010), each with its Bank, status, expiry date and the Bank Accounts it covers, and with the actions to grant, renew and revoke. Whether a Data Provider needs a Bank Consent is declared by its part; a Data Provider that needs none has no Bank Consents on its page.

### BR-009: The Bank Consent Follows the Data Provider's Bank

A Bank Consent covers every Bank Account that its Data Provider reads, for its transactions or for its balance, and names this Bank for: the Bank where the Bank Consent is granted. Slussen records that Bank for the Bank Account when the Bank Account is added (UC-013) or identified (UC-009, step 9). The Bank the Operator confirms or changes for a Bank Account (UC-013 BR-009, UC-009 A14, BR-012) is only how the Bank Account is named and grouped in Slussen; it never decides which Bank Consent covers the Bank Account or at which Bank a Bank Consent is granted. Wherever this use case and UC-001 speak of the Bank Accounts of a Bank, or of the Bank of a Bank Account, in connection with a Bank Consent, the Bank the Data Provider names is meant.
