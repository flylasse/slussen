# Use Case: Review Transfers

## Overview

**Use Case ID:** UC-005  
**Use Case Name:** Review Transfers  
**Primary Actor:** Operator  
**Goal:** Confirm or correct each Transfer between the Operator's own Bank Accounts in one Budget, so that every Transfer arrives in the Budget App as one linked transfer between the intended Budget Accounts  
**Trigger:** Operator asks to review the Transfers of a Budget  
**Status:** Draft  

**Requirements:** [FR-029, FR-046, FR-048, FR-049, FR-050, FR-051, FR-054, FR-066, NFR-002, NFR-010](../requirements.md)

## Preconditions

- The Bank Transactions have Transaction Statuses from a Comparison (UC-003).

## Main Success Scenario

1. Slussen shows each Transfer of the Budget with its two Budget Accounts, amount, dates and whether both Transfer Legs have arrived, flags every Guessed Transfer and shows the number of Guessed Transfers of the Budget.
2. Operator selects a Transfer.
3. Slussen shows the proposed other Budget Account and the other Budget Accounts of the same Budget and currency that the Transfer could belong to.
4. Operator confirms the Transfer.
5. Slussen stores the Transfer Decision Confirmed and, for a Guessed Transfer, removes the flag and lowers the number of Guessed Transfers of the Budget.
6. Slussen records in the Decision Journal the Operator's decision together with the Transfer Slussen had proposed.

## Alternative Flows

### A1: Split Into Ordinary Transactions

**Trigger:** Operator splits the Transfer (step 4)  
**Flow:**

1. Slussen stores the Transfer Decision Split, so that each Transfer Leg is compared as an ordinary Bank Transaction.
2. Use case continues at step 6.

### A2: Hold the Transfer Back

**Trigger:** Operator holds the Transfer back (step 4)  
**Flow:**

1. Slussen stores the Transfer Decision Held and gives both Transfer Legs the status Held Transfer, so that neither is uploaded, as a Transfer or as an ordinary transaction (BR-007).
2. Use case continues at step 6.

### A3: Choose Another Budget Account

**Trigger:** Operator chooses another Budget Account as the other side of the Transfer (step 4)  
**Flow:**

1. Slussen asks whether to remember this Budget Account as the other side of future Transfers of the Bank Account.
2. Operator answers.
3. Slussen pairs the Transfer Leg with a Bank Transaction in the Bank Account mapped to the chosen Budget Account that has the opposite amount, lies within the transfer pairing days and belongs to no other Transfer; when there is none, the Transfer becomes a Lonely Transfer towards the chosen Budget Account.
4. Slussen stores the Transfer Decision Budget Account Chosen, removes the Guessed Transfer flag and, when the Operator agreed, remembers the Budget Account for the Bank Account.
5. Use case continues at step 6.

### A4: Lonely Transfer

**Trigger:** Operator selects a Lonely Transfer (step 2)  
**Flow:**

1. Slussen shows the arrived Transfer Leg with the expected other side, its Budget Account and the date by which it must arrive.
2. Operator confirms the Transfer.
3. Slussen stores the Transfer Decision Confirmed, so that the Transfer Leg is uploaded as a linked transfer whose Transfer Counterpart stays uncleared until the other side arrives.
4. Use case continues at step 6.

### A5: Other Side Not Arrived Within the Grace Period

**Trigger:** The other Transfer Leg of a Lonely Transfer has not arrived within the grace period (step 1)  
**Flow:**

1. Slussen shows the Transfer Leg as an ordinary Bank Transaction with the reason that its other side did not arrive in time.
2. Use case continues at step 2.

### A6: Transfer Counterpart Changed in the Budget App

**Trigger:** A Transfer Counterpart that Slussen's upload created was changed or unlinked in the Budget App, and the Bank Transaction of that side has arrived (step 1)  
**Flow:**

1. Slussen shows that Bank Transaction with the status Transfer Counterpart Changed instead of holding it back.
2. Operator decides on it with Import Anyway or Defer (UC-004 BR-001).
3. Use case continues at step 2.

### A7: Transfer Already Uploaded

**Trigger:** A Transfer Leg of the selected Transfer has been uploaded (step 2)  
**Flow:**

1. Slussen shows the Transfer Decision as locked and offers no choice.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Transfer has the Operator's Transfer Decision, and the Bank Transactions of its Transfer Legs are eligible or held back accordingly.
- The Transfer Decision is kept across later Fetches and Comparisons and across a reinstall of the app.
- The decision is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- Nothing is written to the Budget App.
- An earlier Transfer Decision stays unchanged unless the Operator replaced it.
- No Transfer Leg is lost: each is uploaded as part of a Transfer, uploaded as an ordinary transaction, or shown to the Operator with its status.

## Business Rules

### BR-001: Transfer Detection Is Always On

Slussen always detects Transfers; there is no setting that switches detection off. The Operator can still split any Transfer that has not been uploaded into ordinary transactions (A1).

### BR-002: Detecting a Transfer

Two Bank Transactions form a Transfer when they have equal and opposite amounts, belong to two different Bank Accounts mapped into the same Budget and currency, are dated at most 5 days apart (a number the Operator can change), and are recognised as a card payment or as a bank transfer. A pair in which one Bank Account is a credit card is a card payment. A pair of two other Bank Accounts is a bank transfer only when the payee or memo of at least one of the two contains a Transfer Description of the Build Configuration, compared without regard to case. A single Bank Transaction whose payee or memo contains a Transfer Description and whose other side has not arrived is a Lonely Transfer when the Budget Account of the other side is known: the one the Operator chose to remember for the Bank Account (BR-006), or, for a card payment, the only credit card Bank Account mapped into the same Budget.

### BR-003: One Transfer per Transaction

A Bank Transaction belongs to at most one Transfer. When a Transfer Leg could pair with more than one Bank Transaction, Slussen proposes one pairing and flags it as a Guessed Transfer for the Operator to confirm or correct. It proposes the Bank Transaction in the Budget Account the Operator chose to remember for the Bank Account (BR-006) first, then the one with the nearest booked date, then the one Slussen received first.

### BR-004: Never Across Budgets or Currencies

Movements between Budgets or between currencies are never treated as Transfers. Each side stays an ordinary Bank Transaction, and no currency is converted.

### BR-005: Grace Period for a Lonely Transfer

A Lonely Transfer whose other Transfer Leg has not arrived within 6 days (a number the Operator can change) becomes an ordinary Bank Transaction.

### BR-006: Remembered Other Side

A Budget Account the Operator chose to remember is proposed as the other side of every later Transfer of that Bank Account. It must belong to the same Budget as the Bank Account's own Account Mapping.

### BR-007: Held Means Both Sides Held

A Transfer the Operator holds back keeps both Transfer Legs out of every Upload Plan, with the status Held Transfer, until the Operator changes the decision. Holding never releases one Transfer Leg as an ordinary transaction.

### BR-008: Transfer Decisions Change Until Upload

A Transfer Decision can be changed any number of times until a Transfer Leg of the Transfer is uploaded. From then on it is locked.

### BR-009: Changed Counterparts Are Reviewed

A Bank Transaction whose Transfer Counterpart was changed or unlinked in the Budget App is shown for review and is never suppressed.

### BR-010: Status and Eligibility of Transfer Legs

A Transfer that is not guessed is uploaded as proposed without a decision: a pair as one linked transfer, a Lonely Transfer as a linked transfer whose Transfer Counterpart stays uncleared; its Transfer Legs have the status Transfer Leg. The Transfer Legs of a Guessed Transfer have the status Guessed Transfer and are not eligible until the Operator confirms the Transfer or chooses another Budget Account. Confirmed and Budget Account Chosen Transfers are uploaded as linked transfers; Held Transfers are not uploaded (BR-007); after Split, each Transfer Leg gets its status as an ordinary Bank Transaction. A Bank Transaction that arrives for the side of an uploaded Transfer whose Transfer Counterpart is unchanged has the status Arrived As Transfer Counterpart and is not uploaded (UC-006 BR-010).
