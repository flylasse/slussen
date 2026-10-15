# Use Case: Review Transfers

## Overview

**Use Case ID:** UC-005  
**Use Case Name:** Review Transfers  
**Primary Actor:** Operator  
**Goal:** Confirm or correct each Transfer between the Operator's own Bank Accounts in one Budget, so that every Transfer arrives in the Budget App as one linked transfer between the intended Budget Accounts  
**Trigger:** Operator asks to review the Transfers of a Budget  
**Status:** Approved  

**Requirements:** [FR-029, FR-046, FR-092, FR-052, FR-089, FR-048, FR-049, FR-050, FR-051, FR-021, FR-054, FR-066, NFR-002, NFR-010](../requirements.md)

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

1. Slussen asks whether to keep this Budget Account as the Funded By of the Bank Account when it is a credit card, or as its Transfer Partner otherwise (BR-006).
2. Operator answers.
3. Slussen pairs the Transfer Leg with a Bank Transaction in the Bank Account mapped to the chosen Budget Account that has the opposite amount, lies within the transfer pairing days and belongs to no other Transfer; when there is none, the Transfer becomes a Lonely Transfer towards the chosen Budget Account.
4. Slussen stores the Transfer Decision Budget Account Chosen, removes the Guessed Transfer flag and, when the Operator agreed, sets the Funded By or the Transfer Partner of the Bank Account and records the change in the Decision Journal.
5. Use case continues at step 6.

### A4: Lonely Transfer

**Trigger:** Operator selects a Lonely Transfer (step 2)  
**Flow:**

1. Slussen shows the arrived Transfer Leg with the expected other side, its Budget Account and the date by which it must arrive.
2. Operator confirms the Transfer.
3. Slussen stores the Transfer Decision Confirmed, so that the Transfer Leg is proposed for the next Upload Plan as a linked transfer whose Transfer Counterpart stays uncleared until the other side arrives, and is then marked Cleared only while Upload as Cleared is on (UC-006 BR-010).
4. Use case continues at step 6.

### A5: Other Side Not Arrived Within the Grace Period

**Trigger:** The other Transfer Leg of a Lonely Transfer has not arrived within the grace period (step 1)  
**Flow:**

1. Slussen shows the Transfer Leg as an ordinary Bank Transaction with the reason that its other side did not arrive in time.
2. Use case continues at step 2.

### A6: Transfer Counterpart Changed in the Budget App

**Trigger:** A Transfer Counterpart that Slussen's Upload created was changed or unlinked in the Budget App, and the Bank Transaction of that side has arrived (step 1)  
**Flow:**

1. Slussen shows that Bank Transaction with the status Transfer Counterpart Changed instead of holding it back.
2. Operator decides on it with Import Anyway or Defer (UC-004 BR-001).
3. Use case continues at step 2.

### A7: Transfer Already Uploaded

**Trigger:** A Transfer Leg of the selected Transfer has been uploaded (step 2)  
**Flow:**

1. Slussen shows the Transfer Decision as locked and offers no choice.
2. Use case ends.

### A8: First Card Payment Without Funded By

**Trigger:** An inflow to a credit card Bank Account that has no Funded By matches a Card Payment Description (step 1)
**Flow:**

1. Slussen shows the Bank Transaction as a Guessed Transfer, proposing as its other side the Budget Account of the only Bank Account that is not a credit card and is mapped into the same Budget when there is exactly one, and otherwise no Budget Account. A8 starts only from the card's own Bank Transaction, never from an outflow of the paying side.
2. Use case continues at step 2, where the Operator confirms the proposal or chooses the paying Budget Account as A3 describes, which asks whether to keep it as the card's Funded By.

### A9: Transfer With a Doubtful Transfer Leg

**Trigger:** A Transfer Leg of the selected Transfer is Needs Review or Possible Import By Other Path and has no Review Decision (step 2)  
**Flow:**

1. Slussen shows the Transfer as waiting for the Operator's Review Decision on that Transfer Leg, with the reason it is doubtful (BR-013).
2. Operator decides on that Transfer Leg (UC-004), or splits the Transfer as A1 describes.
3. Use case continues at step 2.

## Postconditions

### Success Postconditions

- The Transfer has the Operator's Transfer Decision, and the Bank Transactions of its Transfer Legs are eligible or held back accordingly.
- The Transfer Decision is kept across later Fetches and Comparisons and across a reinstall of the app.
- The decision is recorded in the Decision Journal, unless the Decision Journal is switched off.

### Failure Postconditions

- Nothing is written to the Budget App.
- An earlier Transfer Decision stays unchanged unless the Operator replaced it.
- No Transfer Leg is lost: each is already uploaded, eligible for an Upload Plan as part of a Transfer or as an ordinary transaction, or shown to the Operator with its status.
- No Transfer with a Transfer Leg that waits for a Review Decision is in an Upload Plan.

## Business Rules

### BR-001: Transfer Detection Is Always On

Slussen always detects Transfers; there is no setting that switches detection off. The Operator can still split any Transfer that has not been uploaded into ordinary transactions (A1).

### BR-002: Detecting a Transfer

Two Bank Transactions form a Transfer when they have equal and opposite amounts, belong to two different Bank Accounts mapped into the same Budget and currency, are dated at most 5 days apart (5 by default, a number the Operator can change, UC-010), and are recognised as a Card Payment or as a Bank Transfer. A pair in which one Bank Account is a credit card is a Card Payment. A pair of two other Bank Accounts is a Bank Transfer only when the payee or memo of at least one of the two matches a Bank Transfer Description (BR-012). A single Bank Transaction whose other side has not arrived is a Lonely Transfer when the Budget Account of the other side is known and its payee or memo matches a description of the right list: a Card Payment Description for an inflow to a credit card Bank Account, whose other side is the card's Funded By, or for an outflow from another Bank Account, whose other side is the credit card Bank Account whose Funded By is that Bank Account's Budget Account; when more than one credit card Bank Account has that Funded By, the outflow is a Guessed Transfer (BR-003). Slussen never infers a credit card from the paying Bank Account: an outflow whose Budget Account is the Funded By of no credit card Bank Account follows the rules for a Bank Transfer Description, or stays an ordinary Bank Transaction; a Bank Transfer Description for a Bank Account that is not a credit card, whose other side is its Transfer Partner (BR-006). A Card Payment to a credit card Bank Account without a Funded By is shown as a Guessed Transfer (A8).

### BR-003: One Transfer per Transaction

A Bank Transaction belongs to at most one Transfer. When a Transfer Leg could pair with more than one Bank Transaction, Slussen proposes one pairing and flags it as a Guessed Transfer for the Operator to confirm or correct. It proposes the Bank Transaction in the Funded By of a credit card Bank Account for a Card Payment, or in the Transfer Partner of the Bank Account for a Bank Transfer (BR-006), first, then the one with the nearest booked date, then the one Slussen received first.

### BR-004: Never Across Budgets or Currencies

Movements between Budgets or between currencies are never treated as Transfers. Each side stays an ordinary Bank Transaction, and no currency is converted.

### BR-005: Grace Period for a Lonely Transfer

A Lonely Transfer whose other Transfer Leg has not arrived within 6 days (6 by default, a number the Operator can change, UC-010) becomes an ordinary Bank Transaction.

### BR-006: Funded By and Transfer Partner

A credit card Bank Account can have a Funded By, and every other Bank Account a Transfer Partner: one Budget Account each, in the same Budget as the Bank Account's own Account Mapping, which may be a Budget Account Slussen does not fetch. The Funded By is used on the Card Payment path and the Transfer Partner on the Bank Transfer path (BR-002, BR-003). Neither has a default: both start empty and are set in Manage Account Mappings (UC-009) or kept from the Operator's answer in A3 or A8. Every change is recorded in the Decision Journal and applies only to Transfers none of whose Transfer Legs has been uploaded.

### BR-007: Held Means Both Sides Held

A Transfer the Operator holds back keeps both Transfer Legs out of every Upload Plan, with the status Held Transfer, until the Operator changes the decision. Holding never releases one Transfer Leg as an ordinary transaction.

### BR-008: Transfer Decisions Change Until Upload

A Transfer Decision can be changed any number of times until a Transfer Leg of the Transfer is uploaded. From then on it is locked.

### BR-009: Changed Counterparts Are Reviewed

A Bank Transaction whose Transfer Counterpart was changed or unlinked in the Budget App is shown for review and is never suppressed.

### BR-010: Status and Eligibility of Transfer Legs

A Transfer with a Transfer Leg that waits for a Review Decision follows BR-013 first. Any other Transfer that is not guessed is eligible for the next Upload Plan as Slussen proposes it, without a Transfer Decision: a pair as one linked transfer, a Lonely Transfer as a linked transfer whose Transfer Counterpart stays uncleared until its other side arrives (UC-006 BR-010); its Transfer Legs have the status Transfer Leg. Nothing is written until the Operator confirms the Upload Plan (UC-006). The Transfer Legs of a Guessed Transfer have the status Guessed Transfer and are not eligible until the Operator confirms the Transfer or chooses another Budget Account. Confirmed and Budget Account Chosen Transfers are eligible as linked transfers; Held Transfers are in no Upload Plan (BR-007); after Split, each Transfer Leg gets its status as an ordinary Bank Transaction. A Bank Transaction that arrives for the side of an uploaded Transfer whose Transfer Counterpart is unchanged has the status Arrived As Transfer Counterpart and is not uploaded (UC-006 BR-010).

### BR-011: Every Transfer Decision Is Journaled

Every Transfer Decision and every change to it is recorded in the Decision Journal together with the Transfer Slussen had proposed. Recording never delays or blocks the decision (UC-011 BR-002).

### BR-012: Matching a Transfer Description

The Card Payment Descriptions and Bank Transfer Descriptions are Operator Settings (UC-010 BR-007). An entry matches when its text occurs anywhere in the payee or memo of the Bank Transaction, compared without regard to case and accents, so that ÖVERFÖRING and OVERFORING match either spelling. A `*` in an entry stands for any characters, as in AMERICAN EXPRESS * PMT; no other character has a special meaning.

### BR-013: Doubt About a Transfer Leg Is Decided First

The doubt about a Bank Transaction itself is decided before its Transfer is eligible. When a Transfer Leg of a pair or of a Lonely Transfer is Needs Review, for any reason (UC-003 BR-008, UC-003 BR-012), or Possible Import By Other Path (UC-003 BR-007), the whole Transfer is in no Upload Plan, from either side, until each such Transfer Leg has a Review Decision (UC-004 BR-009). When every such Transfer Leg is decided Import Anyway, it rejoins the Transfer, which then follows BR-010 and goes into the next Upload Plan as one linked transfer. When one is decided Already Present, the whole Transfer counts as already in the Budget App: neither Transfer Leg is proposed for an Upload Plan, and every other Transfer Leg of it has the status Already In Budget App while that decision holds (UC-003 BR-009). Otherwise, when one is decided Defer, the whole Transfer is held back and every other Transfer Leg of it has the status Deferred. Split (A1) is the only way to separate the two Transfer Legs. A Guessed Transfer also needs its Transfer Decision (BR-010).
