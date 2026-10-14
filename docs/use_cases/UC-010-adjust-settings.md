# Use Case: Adjust Settings

## Overview

**Use Case ID:** UC-010  
**Use Case Name:** Adjust Settings  
**Primary Actor:** Operator  
**Goal:** Set Slussen's Operator Settings to how the Operator wants to work, including the Fetch Schedule, the size and contents of the Decision Journal and Detailed Logging  
**Trigger:** Operator asks to change an Operator Setting  
**Status:** Draft  

**Requirements:** [FR-012, FR-016, FR-030, FR-046, FR-051, FR-062, FR-066, FR-067, FR-069, FR-076, FR-081, NFR-006, NFR-007, NFR-008, NFR-010, NFR-017](../requirements.md)

## Preconditions

- No Upload Plan is awaiting confirmation (UC-006 BR-005).

## Main Success Scenario

1. Slussen shows the current value of each Operator Setting: Upload Window, Fetch Cooldown, Fetch Schedule, transfer pairing days, Lonely Transfer grace period, outdated-balance limit, consent warning lead time, Decision Journal on or off with its size limit and current size, and Detailed Logging.
2. Operator changes the value of an Operator Setting.
3. Slussen checks that the value lies within the allowed range of the Operator Setting (BR-002).
4. Slussen stores the value and applies it from the next Fetch, Scheduled Fetch, Comparison, upload or opening of the app on.
5. Slussen gives every stored Bank Transaction its Transaction Status again under the new value, without contacting the Budget App or any Source.
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

**Trigger:** The Decision Journal has reached 80% of its size limit (step 1)  
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

1. Slussen states how many Scheduled Fetches it will make in any 24 hours, or that it will make none when the value is 0, and that a Scheduled Fetch never uploads.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- The Operator Setting has the new value, and the Transaction Statuses reflect it.
- The change is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- Every Operator Setting keeps its previous value.
- No Transaction Status, Upload Plan or upload is affected by the Decision Journal.

## Business Rules

### BR-001: Operator Settings and Defaults

By default the Upload Window is 7 days, the Fetch Cooldown 60 minutes, the Fetch Schedule 2 Scheduled Fetches in any 24 hours, the transfer pairing days 5, the Lonely Transfer grace period 6 days, the outdated-balance limit 36 hours, the consent warning lead time 7 days, the Decision Journal on with a size limit of 50 MB, and Detailed Logging off. Transfer detection is always on and is not an Operator Setting (UC-005 BR-001). The Request Limit of each Data Provider, the Upload Window of each File Format, the Start Dates and the Transfer Descriptions are set in the Build Configuration and are not Operator Settings.

### BR-002: Allowed Ranges

The Upload Window lies between 1 and 60 days, the Fetch Cooldown between 0 and 1440 minutes (0 switches it off), the Fetch Schedule between 0 and 2 Scheduled Fetches in any 24 hours (0 switches them off), the transfer pairing days and the Lonely Transfer grace period between 0 and 30 days, the outdated-balance limit between 1 and 720 hours, the consent warning lead time between 1 and 60 days, and the Decision Journal size limit between 10 and 500 MB. Every value is a whole number.

### BR-003: Journal Size Limit

The Decision Journal stays within its size limit by removing its oldest entries, and Slussen warns when it reaches 80% of the limit.

### BR-004: Journal Never Affects Decisions

Switching off, clearing, filling or losing the Decision Journal changes no Transaction Status, Upload Plan or upload, and a failure to record an entry never fails or delays an Operator action.

### BR-005: Detailed Logging Boundaries

With Detailed Logging off, device logs contain no transaction amounts, payees, memos or Bank Account numbers. In every mode, device logs contain no credentials, Bank Consent data, authentication codes or one-time values.

### BR-006: Settings Are Kept

The Operator Settings are included in device backups, restored after a reinstall and kept on an upgrade of the same build. There is no action that resets them to their defaults.
