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

1. Operator asks to grant the Bank Consent of a Bank that the Data Provider reaches, chosen from the Banks the Data Provider lists for one country (BR-010), also before any of its Bank Accounts is added (UC-013), or to renew it, where Slussen shows the Data Provider's Bank Consents, each with its status and expiry date (BR-008), or from any place where Slussen shows that the Bank Consent is missing, expiring or expired.
2. Slussen starts a Consent Request for the Bank with the Data Provider, which makes every earlier Consent Request of this Bank unanswerable.
3. Data Provider hands Slussen the way to the Bank's Strong Customer Authentication.
4. Slussen sends the Operator to the Bank's Strong Customer Authentication in the device's browser and shows, until the Consent Request lifetime ends, that it waits for the result, with the way to paste it and to cancel (BR-011).
5. Operator completes the Strong Customer Authentication with the Bank.
6. Bank confirms the consent to the Data Provider, and the Operator returns to Slussen with the result, at Slussen's return address or by pasting the address the browser shows (BR-011).
7. Slussen checks that the result answers the most recent Consent Request for this Bank, arrives within the Consent Request lifetime and has not been used before (BR-002).
8. Slussen stores the Bank Consent with the expiry date the Bank or Data Provider reports and the Bank Session it opens, replacing any earlier Bank Consent and Bank Session of this Bank (BR-004).
9. Slussen keeps the Bank Accounts the Bank Consent covers as Offered Bank Accounts of that Bank, in place of its earlier ones (UC-009 BR-009), and identifies each that the Operator already added (UC-009 BR-004); the others are imported and are Bank Accounts to map (UC-013 BR-012), except the Left-Out Bank Accounts, which stay not imported until the Operator imports them again (UC-013 BR-011).
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

**Trigger:** The Data Provider refuses to start the Consent Request, also because it does not accept Slussen's return address (step 3)  
**Flow:**

1. Slussen stores no new Bank Consent and keeps any earlier Bank Consent of the Bank unchanged.
2. Slussen shows the Data Provider's refusal at the action that started the request, also for a Bank none of whose Bank Accounts is added yet; for a refused return address it names the address to register (BR-011). Nothing of the refusal is stored, so the Bank's Bank Accounts keep the Blocked Reason their stored Bank Consent gives (UC-008 BR-001).
3. Use case ends.

### A4: Data Provider Does Not Confirm the Consent

**Trigger:** The Data Provider refuses to confirm the Bank Consent after the Strong Customer Authentication (step 6)  
**Flow:**

1. Slussen stores no new Bank Consent and keeps any earlier Bank Consent of the Bank unchanged.
2. Slussen shows the Data Provider's refusal where it showed that it waits for the result, as A3 step 2 does; the Consent Request was answered and cannot be answered again, so Slussen asks the Operator to start again.
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
5. Slussen shows the Bank Consent as revoked, the Blocked Reason "missing consent" for the Bank's Bank Accounts, and, unless the Data Provider confirmed that it ended the consent at the Bank, that the Operator can also end the consent at the Bank (ADR-001 §4.1).
6. Use case ends.

### A8: Data Provider Cannot Be Reached

**Trigger:** A request to list the Banks, to start the Consent Request, to complete the Bank Consent or to end it does not answer within the time limit of NFR-016, cannot be sent from the device, or gets an answer Slussen cannot read (step 1, step 3, step 7, A7 step 3)  
**Flow:**

1. When listing the Banks or starting, Slussen stores nothing, keeps any earlier Bank Consent and Consent Request of the Bank unchanged, and shows the failure with its time at the action; the Operator can try again.
2. When completing, the Consent Request was answered and cannot be answered again (BR-002): Slussen stores no Bank Consent, keeps any earlier one unchanged, says that the Bank may have granted a consent that did not reach Slussen, which is never used and ends at its expiry or at the Bank, and asks the Operator to start again.
3. When ending, Slussen continues at A7 step 4 as a revocation the Data Provider did not confirm.
4. Use case ends.

### A9: Held Back by the Request Gate

**Trigger:** The Data Provider's Request Limit is reached, or a Back-off holds for the Data Provider or for the Bank (UC-001 BR-004, BR-007), when Slussen would list the Banks, start, complete or end (step 1, step 3, step 7, A7 step 3)  
**Flow:**

1. Slussen sends no request in that scope and stores nothing new.
2. Slussen shows at the action the Blocked Reason "Request Limit reached" or "waiting for the provider", with the time it ends (UC-001 A3, A5).
3. When completing, Slussen continues as A8 step 2, and the Operator starts again after that time; when ending, at A7 step 4 with the statement that the Data Provider was not contacted (BR-007).
4. Use case ends.

### A10: Consent Request Abandoned

**Trigger:** The Operator cancels while Slussen waits for the result, or no result arrives within the Consent Request lifetime (step 6)  
**Flow:**

1. On Cancel, Slussen deletes the Consent Request, so that no answer to it can be accepted (BR-002) and an Upload Plan can be prepared again (ADR-007 §3); without Cancel, the Consent Request becomes unanswerable when its lifetime ends.
2. Slussen keeps any earlier Bank Consent of the Bank unchanged and shows that the consent was not granted, with the action that starts again.
3. Use case ends.

### A11: No Browser on the Device

**Trigger:** The device has no app that can open the way to the Bank's Strong Customer Authentication (step 2)  
**Flow:**

1. Slussen starts no Consent Request and sends no request.
2. Slussen shows at the action that a browser is needed on the device.
3. Use case ends.

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

Renewing a Bank Consent follows the same steps as granting it and replaces the earlier Bank Consent of the Bank. Once the new Bank Consent is stored, Slussen asks the Data Provider to end the earlier Bank Session; when that request fails or is not confirmed, the renewal stands and Slussen says that the earlier consent may stay at the Bank until it expires. The action is offered on the Data Provider's page and wherever Slussen shows a Bank Consent as missing, expiring or expired.

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

### BR-010: Choosing the Bank

To grant a Bank Consent for a Bank that has none, the Operator chooses the Bank from the Banks the Data Provider lists for one country, starting at the device's country, which the Operator can change; a search by name narrows the list on the device. The list is asked of the Data Provider each time the choice opens or the country changes, as one request through the request gate, and is not stored. The Data Provider names each Bank so that two Banks of one name in two countries are two Banks, and Slussen shows that name (ADR-001 §4.1). Renewing needs no choice: the Bank is the one of the Bank Consent.

### BR-011: The Return Address

A Consent Request is sent with the return address registered with the Data Provider's credentials (UC-014 BR-002), or, when none is registered there, with Slussen's own return address, `<application id>://consent`, formed from the application id of the build (ADR-005 §5): `slussen.app://consent` for a release build and `slussen.app.debug://consent` for a debug build, so that the answer never reaches the other installation, and an Adopting Developer's own application id for an own build (UC-012). The Data Provider's page shows the exact address Slussen sends, with the action that copies it, as the address to register with the Data Provider, also before any Bank Consent is granted, and a refusal of the return address names it (A3); no text or address names the Data Provider except by the name its part declares. While Slussen waits for the result, it offers a field into which the Operator pastes the address the browser shows after the Strong Customer Authentication. With Slussen's own address, the browser normally hands the answer back to Slussen and the field is for when it does not; with another address, the browser ends on that page with the answer in its address, and pasting that address is the way back. A pasted address is checked exactly as a returned one (BR-002). Slussen never reads the clipboard itself. The return address and the field need no device permission (NFR-022, ADR-001 §5). The address, a pasted one and the way to the Strong Customer Authentication carry one-time values and are never logged (BR-006).
