# Use Case: Adjust Settings

## Overview

**Use Case ID:** UC-010  
**Use Case Name:** Adjust Settings  
**Primary Actor:** Operator  
**Goal:** Set Slussen's Operator Settings to how the Operator wants to work, including the Fetch Schedule, the size and contents of the Decision Journal and Detailed Logging  
**Trigger:** Operator asks to change an Operator Setting  
**Status:** Approved  

**Requirements:** [FR-001, FR-012, FR-014, FR-016, FR-017, FR-030, FR-046, FR-051, FR-062, FR-065, FR-066, FR-067, FR-069, FR-076, FR-081, FR-087, FR-088, FR-089, FR-090, FR-094, FR-103, FR-104, NFR-003, NFR-006, NFR-007, NFR-008, NFR-010, NFR-013, NFR-016, NFR-017, NFR-019, NFR-023, FR-107](../requirements.md)

## Preconditions

- No Upload Plan is awaiting confirmation and no Upload is being written (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows the current value of each Operator Setting that applies to everything: Upload Window, file upload window, fetch period, Fetch Schedule, default wait, Request Limit warning pause, request timeout, Upload Plan lifetime, Consent Request lifetime, number of recent Fetches kept, Card Payment Descriptions, Bank Transfer Descriptions, transfer pairing days, Lonely Transfer grace period, outdated-balance limit, consent warning lead time, Decision Journal on or off with its size limit, warning threshold and current size, backup warning threshold, and Detailed Logging. The Operator Settings that belong to one service are shown with that service, which Settings lists directly (BR-010): the Fetch Cooldown of each Data Provider, with the default its part declares, and the Request Limit of a Data Provider that publishes none, with the default and the range its part declares, with the Data Provider, and Upload as Cleared, only when the Budget App supports the Cleared marker, with the Budget App. Slussen also shows the version name of the app and, for a debug build, that it is one (BR-011).
2. Operator changes the value of an Operator Setting.
3. Slussen checks that the value lies within the allowed range of the Operator Setting (BR-002).
4. Slussen stores the value and applies it from the next Fetch, Scheduled Fetch, Comparison, Upload or opening of the app on.
5. When the Operator Setting is one that a Transaction Status depends on (the Upload Window, the file upload window, the Card Payment Descriptions, the Bank Transfer Descriptions, the transfer pairing days or the Lonely Transfer grace period), Slussen gives every stored Bank Transaction its Transaction Status again under the new value, without contacting the Budget App or any Source; any other Operator Setting changes no Transaction Status.
6. Slussen records the change in the Decision Journal.

## Alternative Flows

### A1: Value Outside the Allowed Range

**Trigger:** The value lies outside the allowed range of the Operator Setting (step 3)  
**Flow:**

1. Slussen refuses the value, keeps the previous one and shows the allowed range.
2. Use case continues at step 2.

### A2: Lower the Decision Journal Size Limit

**Trigger:** Operator sets the Decision Journal size limit below its current size (step 2)  
**Flow:**

1. Slussen asks the Operator to confirm, stating that the oldest entries will be removed.
2. Operator confirms, or cancels and the use case ends.
3. Use case continues at step 3.

### A3: Clear the Decision Journal

**Trigger:** Operator chooses to clear the Decision Journal (step 2)  
**Flow:**

1. Slussen asks the Operator to confirm.
2. Operator confirms.
3. Slussen removes every entry of the Decision Journal, records that the Decision Journal was cleared as its first new entry, and changes nothing else.
4. Use case ends.

### A4: Decision Journal Near Its Limit

**Trigger:** The Decision Journal has reached the journal warning threshold of its size limit (step 1)  
**Flow:**

1. Slussen shows a warning with the current size and the limit.
2. Use case continues at step 2.

### A5: Switch On Detailed Logging

**Trigger:** Operator switches Detailed Logging on (step 2)  
**Flow:**

1. Slussen states that the device logs will then contain transaction data and Bank Account numbers, stay on the device, and never contain credentials or Bank Consent data.
2. Use case continues at step 4.

### A6: Change the Fetch Schedule

**Trigger:** Operator changes the Fetch Schedule (step 2)  
**Flow:**

1. Slussen states how many Scheduled Fetches it will make in any 24 hours under the new Fetch Schedule (2 by default), or that it will make none when the value is 0, and that a Scheduled Fetch never uploads.
2. Use case continues at step 3.

### A7: Edit a Description List

**Trigger:** Operator chooses to add, change or remove an entry of the Card Payment Descriptions or the Bank Transfer Descriptions (step 2)
**Flow:**

1. Slussen checks that the entry is not empty and that the same list holds no equal entry (BR-008).
2. Slussen stores the changed list and detects the Transfers among the Bank Transactions not yet uploaded again (BR-009).
3. Slussen records the list before and after the change in the Decision Journal as a configuration change.
4. Use case continues at step 5.

### A8: Restore a Default Description List

**Trigger:** Operator chooses to restore the default list of Card Payment Descriptions or of Bank Transfer Descriptions (step 2)
**Flow:**

1. Slussen asks the Operator to confirm, showing the default list.
2. Operator confirms, or cancels and the use case ends.
3. Slussen records the current list in the Decision Journal as a configuration change, then replaces it with the default list (BR-007).
4. Slussen detects the Transfers among the Bank Transactions not yet uploaded again (BR-009).
5. Use case continues at step 5.

### A9: Switch Upload as Cleared

**Trigger:** Operator switches Upload as Cleared on or off on the Budget App's page (step 2)
**Flow:**

1. Slussen states that the change applies only to Upload Plans prepared from now on, changes nothing already in the Budget App, and that while the setting is off Slussen marks nothing Cleared, including Transfer Counterparts, so that clearing is left to the Operator in the Budget App.
2. Use case continues at step 4.

### A10: Reset a Fetch Cooldown to Its Default

**Trigger:** Operator chooses, on a Data Provider's page, to reset its Fetch Cooldown to the default its part declares (step 2)  
**Flow:**

1. Slussen takes the default the Data Provider's part declares as the new value.
2. Use case continues at step 4.

### A11: Reset a Request Limit to Its Default

**Trigger:** Operator chooses, on the page of a Data Provider that publishes no limit of its own, to reset its Request Limit to the default its part declares (step 2)  
**Flow:**

1. Slussen takes the default the Data Provider's part declares as the new value.
2. Use case continues at step 4.

### A12: Start Over

**Trigger:** Operator chooses, on Settings, to start Slussen over (step 2)  
**Flow:**

1. Slussen states everything it will delete: the chosen Budgets and their Budget Accounts, the Bank Accounts with their Account Mappings and Start Dates, the Left-Out Bank Accounts, the Bank Transactions with their Review Decisions and Transfer Decisions, the Sent Record, the service credentials, the Bank Consents, the Operator Settings and the Decision Journal, with the number of Bank Accounts and Bank Transactions; and that, as after a new installation, nothing is uploaded until First Upload Matching is confirmed (UC-006 BR-017).
2. Operator confirms, or cancels and the use case ends with nothing deleted.
3. Slussen deletes everything it stores and starts as a new installation, opening a First Upload Matching (UC-006 BR-017).
4. Use case ends.

## Postconditions

### Success Postconditions

- The Operator Setting has the new value, and the Transaction Statuses reflect it when they depend on it (step 5).
- The change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- Every Operator Setting keeps its previous value.
- No Transaction Status, Upload Plan or Upload is affected by the Decision Journal.

## Business Rules

### BR-001: Operator Settings and Defaults

By default the Upload Window is 7 days, the file upload window 60 days, the fetch period 30 days, the Fetch Cooldown of each Data Provider the default its Data Provider's part declares, the Request Limit of a Data Provider that publishes none the default its part declares, the Fetch Schedule 2 Scheduled Fetches in any 24 hours, the default wait 8 hours, the Request Limit warning pause 24 hours, the request timeout 30 seconds, the Upload Plan lifetime 10 minutes, the Consent Request lifetime 15 minutes, the number of recent Fetches kept 60, Upload as Cleared on, the transfer pairing days 5, the Lonely Transfer grace period 6 days, the outdated-balance limit 36 hours, the consent warning lead time 7 days, the Decision Journal on with a size limit of 50 MB and a warning threshold of 80%, the backup warning threshold 80% of the backup size limit Android sets (C-015), and Detailed Logging off. Transfer detection is always on and is not an Operator Setting (UC-005 BR-001). The default Card Payment and Bank Transfer Descriptions are listed in BR-007. A Request Limit that the Data Provider publishes is declared by its part and is not an Operator Setting, because it protects the Data Provider's own limit (UC-001 BR-007); the chosen Budgets, the Bank Accounts with their names and Start Dates and the Account Mappings are set in the app (UC-013, UC-009), not here. Numbers fixed by an outside party are not Operator Settings either: the PSD2 access limit (C-006), the consent length the Bank or Data Provider reports (C-007), the Budget App's own rules (C-009) and the backup size limit Android sets (C-015).

### BR-002: Allowed Ranges

The Upload Window lies between 1 and 60 days, the file upload window between 7 and 365 days, the fetch period between 7 and 180 days, the Fetch Cooldown of each Data Provider between 0 and 1440 minutes (0 switches it off), the Request Limit of a Data Provider that publishes none within the range its part declares, in requests in any 24 hours, the default wait between 1 and 48 hours, the Request Limit warning pause between 1 and 72 hours, the request timeout between 10 and 120 seconds, the Upload Plan lifetime between 1 and 60 minutes, the Consent Request lifetime between 5 and 30 minutes, the number of recent Fetches kept between 10 and 500, the Fetch Schedule between 0 and 2 Scheduled Fetches in any 24 hours (0 switches them off), the transfer pairing days and the Lonely Transfer grace period between 0 and 30 days, the outdated-balance limit between 1 and 720 hours, the consent warning lead time between 1 and 60 days, the Decision Journal size limit between 10 and 500 MB, its warning threshold between 50% and 95% of the size limit, and the backup warning threshold between 50% and 95% of the backup size limit. Every value is a whole number.

### BR-003: Journal Size Limit

The Decision Journal stays within its size limit by removing its oldest entries, and Slussen warns when it reaches the warning threshold (80% of the limit by default).

### BR-004: Journal Never Affects Decisions

Switching off, clearing, filling, losing or damaging the Decision Journal changes no Transaction Status, Upload Plan or Upload and opens no First Upload Matching, because the Decision Journal is not Decision-Critical Data; and a failure to record an entry never fails or delays an Operator action.

### BR-005: Detailed Logging Boundaries

With Detailed Logging off, device logs contain no transaction amounts, payees, memos or Bank Account numbers. In every mode, device logs contain no credentials, Bank Consent data, Bank Sessions, authentication codes or one-time values.

### BR-006: Settings Are Kept

The Operator Settings are included in device backups, restored after a reinstall and kept on an upgrade to a newer build signed with the same key (NFR-017). There is no action that resets them to their defaults, with these exceptions: the Card Payment Descriptions and the Bank Transfer Descriptions can each be restored to their default list (A8), and the list they replace is recorded in the Decision Journal first; and the Fetch Cooldown of a Data Provider, and the Request Limit of a Data Provider that publishes none, can each be reset to the default its part declares (A10, A11), recorded like any other change.

### BR-007: Default Description Lists

The default lists ship with the build from the repository.

| Card Payment Descriptions | Bank Transfer Descriptions |
|---|---|
| AUTOPAY | ÖVERFÖRING |
| AUTOMATIC PAYMENT | INTERNET TRANSFER |
| PAYMENT - THANK | EXPRESS INTERNET TRANSFER |
| AUTO-PMT | AMERICAN EXPRESS TRANSFER |
| ACH PMT | ONLINE TRANSFER |
| AMERICAN EXPRESS ACH PMT | TRANSFER |
| CITI AUTOPAY | XFER |
| CRD AUTOPAY |  |
| CITICARDAP |  |

ÖVERFÖRING also matches OVERFORING, since entries are compared without regard to accents (BR-008, UC-005 BR-012).

### BR-008: Valid Description Entries

An entry is a non-empty text. Within one list, no two entries are equal when compared without regard to case and accents. A `*` stands for any characters (UC-005 BR-012); no other character has a special meaning.

### BR-009: Description Changes Apply Only Before Upload

A change to either list, including a restore, applies only to Transfers none of whose Transfer Legs has been uploaded. An uploaded Transfer keeps its outcome. A Transfer that has a Transfer Decision of the Operator keeps that decision; the changed lists only decide which Transfers Slussen proposes among the other Bank Transactions not yet uploaded.

### BR-010: Each Service's Settings Come From Its Part

Settings keeps the Operator Settings that apply to everything. Above them, at its top and with no level between, it lists the services directly, in the order of setting up: the Budget App, each Data Provider and each File Format of the build, set up or not, each with its status, and then the Account Mappings (UC-014 BR-007, UC-013 BR-008, UC-013 BR-015); there is no separate setup screen and no other entry for Bank Accounts. Each service has one page that holds its status, its credentials (UC-014) and everything else that belongs to it: for a Data Provider its Fetch (UC-001), its Fetch Cooldown with the default its part declares, its Request Limit (the limit the Data Provider publishes, shown with its source and not changeable, or, when it publishes none, a value the Operator can change within the range its part declares, shown with that default as not published by the Data Provider, UC-001 BR-007), its Offered Bank Accounts with the import switch of each (UC-013 BR-012) and, when it needs a Bank Consent, its Bank Consents (UC-007); for the Budget App the choice of the Budgets Slussen serves, the Budget Accounts of each with whether each receives from Slussen (UC-013 BR-014) and, when it supports the Cleared marker, Upload as Cleared; for a File Format its Bank Accounts and the action to add one (UC-013 A4). A service's entry, page and settings are generated from what its part declares: its name, the credentials it asks for, its default Fetch Cooldown, its Request Limit, published or a default with its range, whether it needs a Bank Consent and whether it supports the Cleared marker; a File Format's entry and page need only its name. Adding a Data Provider's part to a build brings its own entry, page and settings; nothing per service is written into the screens, and the Adopting Developer configures nothing (UC-012 A2).

### BR-011: Settings Shows the App's Version

Settings shows the version name of the build the Operator runs, the one the build takes from `VERSION` (ADR-005), and says when it is a debug build, which can be installed beside a release build. It is shown, not an Operator Setting: nothing changes it in the app.
