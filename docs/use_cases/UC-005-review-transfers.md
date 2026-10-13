# Use Case: Review Transfers

## Overview

**Use Case ID:** UC-005  
**Use Case Name:** Review Transfers  
**Primary Actor:** Operator  
**Goal:** Confirm or correct each Transfer between the Operator's own Bank Accounts in one Budget, so that every Transfer arrives in the Budget App as one linked transfer between the intended Budget Accounts  
**Trigger:** Operator opens the Transfers of a Budget to review them  
**Status:** Draft  

**Requirements:** [FR-029, FR-045, FR-046, FR-048, FR-049, FR-050, FR-051, FR-054, FR-066, NFR-002, NFR-010](../requirements.md)

## Preconditions

- Transfer detection is switched on (UC-010).
- The Bank Transactions have Transaction Statuses from a Comparison (UC-003).

## Main Success Scenario

1. Operator opens the Transfers of a Budget.
2. Slussen shows each Transfer with its two Budget Accounts, amount, dates and whether both Transfer Legs have arrived, flags every Guessed Transfer and shows the number of Guessed Transfers of the Budget.
3. Operator selects a Guessed Transfer.
4. Slussen shows the proposed other Budget Account and the other Budget Accounts of the same Budget and currency that the Transfer could belong to.
5. Operator confirms the Transfer.
6. Slussen stores the transfer decision Confirmed, removes the Guessed Transfer flag and lowers the number of Guessed Transfers of the Budget.
7. Slussen records in the Decision Journal the Operator's decision together with the Transfer Slussen had proposed.

## Alternative Flows

### A1: Split Into Ordinary Transactions

**Trigger:** Operator splits the Transfer (step 5)  
**Flow:**

1. Slussen stores the transfer decision Split, so that each Transfer Leg is treated as an ordinary Bank Transaction.
2. Use case continues at step 7.

### A2: Hold the Transfer Back

**Trigger:** Operator holds the Transfer back (step 5)  
**Flow:**

1. Slussen stores the transfer decision Held, so that no Transfer Leg of it is uploaded, neither as a Transfer nor as an ordinary transaction.
2. Use case continues at step 7.

### A3: Choose Another Budget Account

**Trigger:** Operator chooses another Budget Account as the other side of the Transfer (step 5)  
**Flow:**

1. Slussen asks whether to remember this Budget Account as the other side of future Transfers of the Bank Account.
2. Operator answers.
3. Slussen pairs the Transfer Leg with a Bank Transaction in the chosen Budget Account that has the opposite amount, lies within the transfer pairing days and belongs to no other Transfer; when there is none, the Transfer becomes a Lonely Transfer towards the chosen Budget Account.
4. Slussen stores the transfer decision with the chosen Budget Account, removes the Guessed Transfer flag and, when the Operator agreed, remembers the Budget Account for the Bank Account.
5. Use case continues at step 7.

### A4: Lonely Transfer

**Trigger:** Operator selects a Lonely Transfer (step 3)  
**Flow:**

1. Slussen shows the arrived Transfer Leg with the expected other side, its Budget Account and the date by which it must arrive.
2. Operator confirms the Transfer.
3. Slussen stores the transfer decision Confirmed, so that the Transfer Leg is uploaded as a linked transfer whose Transfer Counterpart stays uncleared until the other side arrives.
4. Use case continues at step 7.

### A5: Other Side Not Arrived Within the Grace Period

**Trigger:** The other Transfer Leg of a Lonely Transfer has not arrived within the grace period (step 2)  
**Flow:**

1. Slussen shows the Transfer Leg as an ordinary Bank Transaction with the reason that its other side did not arrive in time.
2. Use case continues at step 3.

### A6: Transfer Counterpart Changed in the Budget App

**Trigger:** A Transfer Counterpart that Slussen's upload created was changed or unlinked in the Budget App, and the Bank Transaction of that side has arrived (step 2)  
**Flow:**

1. Slussen shows that Bank Transaction with the status Transfer Counterpart Changed instead of holding it back.
2. Operator decides on it with Import Anyway or Defer (UC-004).
3. Use case continues at step 3.

### A7: Transfer Already Uploaded

**Trigger:** A Transfer Leg of the selected Transfer has been uploaded (step 3)  
**Flow:**

1. Slussen shows the transfer decision as locked and offers no choice.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Transfer has the Operator's transfer decision, and the Bank Transactions of its Transfer Legs are eligible or held back accordingly.
- The transfer decision is kept across later Fetches and Comparisons and across a reinstall of the app.
- The decision is recorded in the Decision Journal.

### Failure Postconditions

- Nothing is written to the Budget App.
- An earlier transfer decision stays unchanged unless the Operator replaced it.
- No Transfer Leg is lost: each is uploaded as part of a Transfer, uploaded as an ordinary transaction, or shown to the Operator with its status.

## Business Rules

### BR-001: Transfer Detection Is Opt-in

Transfer detection is off by default and switched on or off by the Operator (UC-010). While it is off, every Bank Transaction is treated as an ordinary transaction.

### BR-002: Detecting a Transfer

Two Bank Transactions form a Transfer when they have equal and opposite amounts, belong to two different Bank Accounts mapped into the same Budget and currency, are dated at most 5 days apart (a number the Operator can change), and are recognised as a card payment or as a bank transfer. A pair in which one Bank Account is a credit card is a card payment; a pair of two other Bank Accounts counts only when the description of the transactions identifies a bank transfer.

### BR-003: One Transfer per Transaction

A Bank Transaction belongs to at most one Transfer. When a Transfer Leg could pair with more than one Bank Transaction, Slussen proposes the best pairing and flags it as a Guessed Transfer for the Operator to confirm or correct.

### BR-004: Never Across Budgets or Currencies

Movements between Budgets or between currencies are never treated as Transfers. Each side stays an ordinary Bank Transaction, and no currency is converted.

### BR-005: Grace Period for a Lonely Transfer

A Lonely Transfer whose other Transfer Leg has not arrived within 6 days (a number the Operator can change) becomes an ordinary Bank Transaction.

### BR-006: Remembered Other Side

A Budget Account the Operator chose to remember is proposed as the other side of every later Transfer of that Bank Account. It must belong to the same Budget as the Bank Account's own Account Mapping.

### BR-007: Held Means Both Sides Held

A Transfer the Operator holds back keeps both Transfer Legs out of every Upload Plan until the Operator changes the decision. Holding never releases one Transfer Leg as an ordinary transaction.

### BR-008: Transfer Decisions Change Until Upload

A transfer decision can be changed any number of times until a Transfer Leg of the Transfer is uploaded. From then on it is locked.

### BR-009: Changed Counterparts Are Reviewed

A Bank Transaction whose Transfer Counterpart was changed or unlinked in the Budget App is shown for review and is never suppressed.
