# Use Case: Review Transfers

## Overview

**Use Case ID:** UC-005  
**Use Case Name:** Review Transfers  
**Primary Actor:** Operator  
**Goal:** Confirm or correct each Transfer between the Operator's own Bank Accounts in one Budget, so that every Transfer arrives in the Budget App as one linked transfer between the intended Budget Accounts  
**Trigger:** Operator asks to review the Transfers of a Budget  
**Status:** Approved  

**Requirements:** [FR-029, FR-046, FR-092, FR-052, FR-089, FR-048, FR-049, FR-050, FR-051, FR-021, FR-054, FR-066, NFR-002, NFR-010, NFR-023](../requirements.md)

## Preconditions

- The Bank Transactions have Transaction Statuses from a Comparison (UC-003).

## Main Success Scenario

1. Slussen shows each Transfer of the Budget with its two Budget Accounts, amount, dates and whether both Transfer Legs have arrived, flags every Guessed Transfer and shows the number of Guessed Transfers of the Budget.
2. Operator selects a Transfer.
3. Slussen shows the proposed other Budget Account and the other Budget Accounts of the same Budget and currency that Slussen fetches and that the Transfer could belong to (BR-014).
4. Operator confirms the Transfer.
5. Slussen stores the Transfer Decision Confirmed and, for a Guessed Transfer, removes the flag and lowers the number of Guessed Transfers of the Budget.
6. Slussen records in the Decision Journal the Operator's decision together with the Transfer Slussen had proposed.

## Alternative Flows

### A1: Split Into Ordinary Transactions

**Trigger:** Operator splits the Transfer (step 4)  
**Flow:**

1. Slussen stores the Transfer Decision Split, so that each Transfer Leg is compared as an ordinary Bank Transaction and is a Transfer Leg of no other Transfer while the decision stands (BR-010).
2. Use case continues at step 6.

### A2: Hold the Transfer Back

**Trigger:** Operator holds the Transfer back (step 4)  
**Flow:**

1. Slussen stores the Transfer Decision Held and gives both Transfer Legs the status Held Transfer, so that neither is uploaded, as a Transfer or as an ordinary transaction (BR-007).
2. Use case continues at step 6.

### A3: Choose Another Budget Account

**Trigger:** Operator chooses another Budget Account that Slussen fetches as the other side of the Transfer (step 4)  
**Flow:**

1. Slussen asks whether to keep this Budget Account as the Funded By of the Bank Account of the Transfer Leg that stays (BR-016) when it is a credit card, or as its Transfer Partner otherwise (BR-006), and proposes an answer that the Operator can change (BR-016).
2. Operator answers.
3. Slussen pairs the Transfer Leg that stays with a Bank Transaction in the Bank Account mapped to the chosen Budget Account that has the opposite amount and lies within the transfer pairing days: one that has not been uploaded and belongs to no other Transfer, or one that it takes from a Transfer without a Transfer Decision (BR-016); when that Bank Account holds no Bank Transaction with that amount within those days, the Transfer becomes a Lonely Transfer towards the chosen Budget Account.
4. Slussen stores the Transfer Decision Budget Account Chosen, removes the Guessed Transfer flag, detects Transfers again for a Bank Transaction that the choice released (BR-016) and, when the Operator agreed, sets the Funded By or the Transfer Partner of the Bank Account and records the change in the Decision Journal.
5. Use case continues at step 6.

### A4: Lonely Transfer

**Trigger:** Operator selects a Lonely Transfer (step 2)  
**Flow:**

1. Slussen shows the arrived Transfer Leg with the expected other side, its Budget Account and the date by which it must arrive.
2. Operator confirms the Transfer.
3. Slussen stores the Transfer Decision Confirmed, so that the Transfer Leg is proposed for the next Upload Plan as a linked transfer whose Transfer Counterpart stays uncleared until the other side arrives, and is then marked Cleared only while Upload as Cleared is on (UC-006 BR-010).
4. Use case continues at step 6.

### A5: Other Side Not Arrived Within the Grace Period

**Trigger:** The other Transfer Leg of a Lonely Transfer that has no Transfer Decision has not arrived within the grace period (BR-005) (step 1)  
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

**Trigger:** An inflow to a credit card Bank Account that has no Funded By, or one that no longer applies (BR-014), matches a Card Payment Description, or forms a Card Payment with an outflow that has arrived (BR-002) (step 1)
**Flow:**

1. Slussen shows the Bank Transaction as a Guessed Transfer. When the outflow has arrived, the Guessed Transfer has both Transfer Legs and proposes the Budget Account of the outflow as its other side. Otherwise it proposes the Budget Account of the only Bank Account that is not a credit card and is mapped into the same Budget when there is exactly one, and otherwise no Budget Account. A8 starts only from the card's own Bank Transaction, never from an outflow of the paying side. Slussen states why the paying side is not certain: that the card has no Funded By or, when one is set that no longer applies, that its Funded By, named by its Budget Account, no longer applies, with the action that changes or clears it (UC-009 A9).
2. Use case continues at step 2, where the Operator confirms the proposal or chooses the paying Budget Account as A3 describes, which asks whether to keep it as the card's Funded By. When Slussen proposes no Budget Account, the Transfer has no Budget Account on its paying side and there is nothing to confirm: the Operator chooses the paying Budget Account (A3), splits the Transfer (A1) or holds it back (A2).

### A9: Transfer With a Doubtful Transfer Leg

**Trigger:** A Transfer Leg of the selected Transfer is Needs Review or Possible Import By Other Path and has no Review Decision, or is Decision No Longer Holds (step 2)  
**Flow:**

1. Slussen shows the Transfer as waiting for the Operator's Review Decision on that Transfer Leg, with the reason it is doubtful (BR-013).
2. Operator decides on that Transfer Leg (UC-004), or splits the Transfer as A1 describes.
3. Use case continues at step 2.

### A10: Other Side Already Decided or Uploaded

**Trigger:** When Slussen pairs the Transfer Leg in A3, the Bank Account mapped to the chosen Budget Account holds at least one Bank Transaction that could be the other side, and each of them has been uploaded or is a Transfer Leg of another Transfer that has a Transfer Decision or one of whose Transfer Legs has been uploaded (step 4)  
**Flow:**

1. Slussen refuses the choice: it stores no Transfer Decision, sets no Funded By or Transfer Partner, and shows why: the Bank Transaction that would be the other side has already been uploaded, or belongs to another Transfer that the Operator decided on or that is already uploaded, which it names with its two Budget Accounts, amount, dates and Transfer Decision (BR-016).
2. Slussen shows what the Operator can do: change the Transfer Decision of that other Transfer while it is not locked (BR-008), choose another Budget Account, or split or hold back the selected Transfer.
3. Use case continues at step 3.

### A11: Budget Owes a Read of Its Read Period

**Trigger:** The Budget of the selected Transfer owes a read of its read period, because the read period became longer or the Budget gained a Budget Account (UC-003 BR-016) (step 4)  
**Flow:**

1. Slussen refuses the Transfer Decision and stores nothing: no Transfer Decision, no Transaction Status, no Funded By or Transfer Partner.
2. Slussen shows the reason and offers to read the Budget App again, which gives the Transfer Legs their Transaction Statuses (UC-003).
3. Use case ends.

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

Two Bank Transactions form a Transfer when they have equal and opposite amounts, belong to two different Bank Accounts mapped into the same Budget and currency, are dated at most 5 days apart (5 by default, a number the Operator can change, UC-010), and are recognised as a Card Payment or as a Bank Transfer. A pair in which one Bank Account is a credit card is a Card Payment. A pair of two other Bank Accounts is a Bank Transfer only when the payee or memo of at least one of the two matches a Bank Transfer Description (BR-012). A single Bank Transaction whose other side has not arrived is a Lonely Transfer when the Budget Account of the other side is known and is a Budget Account that Slussen fetches (BR-014) and its payee or memo matches a description of the right list: a Card Payment Description for an inflow to a credit card Bank Account, whose other side is the card's Funded By, or for an outflow from another Bank Account, whose other side is the credit card Bank Account whose Funded By is that Bank Account's Budget Account; when more than one credit card Bank Account has that Funded By, the outflow is a Guessed Transfer (BR-003). Slussen never infers a credit card from the paying Bank Account: an outflow whose Budget Account is the Funded By of no credit card Bank Account follows the rules for a Bank Transfer Description, or stays an ordinary Bank Transaction; a Bank Transfer Description for a Bank Account that is not a credit card, whose other side is its Transfer Partner (BR-006). A Card Payment to a credit card Bank Account without a Funded By, or with one that no longer applies (BR-014), is shown as a Guessed Transfer, whether its other side has arrived or not (A8). Whether two Bank Transactions form a Transfer does not depend on the Upload Window or on any other rule of the Comparison that gives a Bank Transaction its Transaction Status (UC-003 BR-001): a Bank Transaction that is Too Old, Before Start Date or Already In Budget App is still a Transfer Leg of its Transfer, so that a Transfer, once detected, stays one Transfer (BR-010). The one exception: a Bank Transaction that Slussen uploaded as an ordinary transaction while it was a Transfer Leg of no Transfer is never made a Transfer Leg later; when its other side arrives, that one is an ordinary Bank Transaction and is uploaded as one, and the Operator links the two in the Budget App if he wants (BR-005).

### BR-003: One Transfer per Transaction

A Bank Transaction belongs to at most one Transfer. When a Transfer Leg could pair with more than one Bank Transaction, Slussen proposes one pairing and flags it as a Guessed Transfer for the Operator to confirm or correct. It proposes the Bank Transaction in the Funded By of a credit card Bank Account for a Card Payment, or in the Transfer Partner of the Bank Account for a Bank Transfer (BR-006), first, then the one with the nearest booked date, then the one Slussen received first.

### BR-004: Never Across Budgets or Currencies

Movements between Budgets or between currencies are never treated as Transfers. Each side stays an ordinary Bank Transaction, and no currency is converted.

### BR-005: Grace Period for a Lonely Transfer

A Lonely Transfer whose other Transfer Leg has not arrived within 6 days (6 by default, a number the Operator can change, UC-010) becomes an ordinary Bank Transaction. The days are counted from the booked date of the Transfer Leg that has arrived, and the last of them is included. The grace period ends only a Lonely Transfer that has no Transfer Decision and whose Transfer Leg has not been uploaded (BR-008); one uploaded as a linked transfer stays a Lonely Transfer, since the Budget App already holds its Transfer Counterpart. A Lonely Transfer with the Transfer Decision Confirmed, Held or Budget Account Chosen keeps its decision and stays a Lonely Transfer after the grace period, until its other side arrives or the Operator changes the decision. When Slussen then finds a Bank Transaction in the Bank Account mapped to the Budget Account of the missing side that has the opposite amount, is dated within the transfer pairing days of the arrived Transfer Leg (BR-002), has not been uploaded and is a Transfer Leg of no Transfer that has a Transfer Decision or an uploaded Transfer Leg (BR-008), that Bank Transaction joins the Transfer as its second Transfer Leg before Slussen proposes any other Transfer for it, and the Transfer keeps its Transfer Decision; a Transfer without a Transfer Decision that it leaves is dissolved and its other Transfer Leg detected again (BR-002). Of several such Bank Transactions the one with the nearest booked date joins, then the one Slussen received first; when one Bank Transaction could join more than one such Lonely Transfer, it joins the one whose arrived Transfer Leg has the nearest booked date, then the one the Operator decided on first. After Split nothing joins: the Transfer Leg stays an ordinary Bank Transaction (BR-010). A Bank Transaction that was uploaded as an ordinary transaction is no Transfer Leg and never the other side of a Transfer. When no Bank Transaction can join and that Bank Account holds one with the opposite amount, dated within the transfer pairing days of the arrived Transfer Leg, that was uploaded as an ordinary transaction, the other side is written already: the ground of the Transfer is no longer valid (BR-015), Slussen dissolves it, and its Transfer Leg is an ordinary Bank Transaction and no Lonely Transfer towards that Budget Account, with or without a Transfer Decision, while that Bank Transaction could be its other side; only a Lonely Transfer that the Operator split or holds back is not dissolved and stays as decided (BR-015).

### BR-006: Funded By and Transfer Partner

A credit card Bank Account can have a Funded By, and every other Bank Account a Transfer Partner: one Budget Account each, in the same Budget as the Bank Account's own Account Mapping, which is always a Budget Account that Slussen fetches (BR-014). The Funded By is used on the Card Payment path and the Transfer Partner on the Bank Transfer path (BR-002, BR-003). Neither has a default: both start empty and are set in Manage Account Mappings (UC-009) or kept from the Operator's answer in A3 or A8. Every change is recorded in the Decision Journal and applies only to Transfers none of whose Transfer Legs has been uploaded. The answer the Operator gives in A3 or A8 belongs to the Transfer Decision: Slussen stores it with that decision, also while an Upload Plan awaits confirmation, which is then replaced (UC-006 A3), although no change in Manage Account Mappings is possible then (UC-009 BR-012).

### BR-007: Held Means Both Sides Held

A Transfer the Operator holds back keeps both Transfer Legs out of every Upload Plan, with the status Held Transfer, until the Operator changes the decision. Holding never releases one Transfer Leg as an ordinary transaction. A Transfer the Operator holds back is not dissolved when its ground changes (BR-015). While the Transfer is held back, a Transfer Leg of it offers no Re-upload, and a Re-upload decision it already has does not make it a New transaction: the Operator changes the Transfer Decision first (UC-004 BR-009).

### BR-008: Transfer Decisions Change Until Upload

A Transfer Decision can be changed any number of times until a Transfer Leg of the Transfer is uploaded. From then on it is locked. A Transfer Leg counts as uploaded once Slussen has sent it and the Budget App has not rejected it, that is, once its Sent Record has the outcome Written, Already Present, Not Yet Known or Unknown, whether it was sent as a linked transfer or, after Split, as an ordinary transaction. A Transfer Leg that the Budget App rejected was not written and locks nothing.

### BR-009: Changed Counterparts Are Reviewed

A Bank Transaction whose Transfer Counterpart was changed or unlinked in the Budget App is shown for review and is never suppressed.

### BR-010: Status and Eligibility of Transfer Legs

A Transfer with a Transfer Leg that waits for a Review Decision follows BR-013 first. A Transfer Leg to which an earlier rule of the Comparison gave its Transaction Status, such as Too Old or Before Start Date (UC-003 BR-001), keeps that status and stays a Transfer Leg of its Transfer; while a Transfer has such a Transfer Leg, the whole Transfer is in no Upload Plan, from either side, and its other Transfer Leg keeps the status Transfer Leg or Guessed Transfer. The Transfer becomes eligible again when no Transfer Leg has such a status any more, for example after the Operator widened the Upload Window. Any other Transfer that is not guessed is eligible for the next Upload Plan as Slussen proposes it, without a Transfer Decision: a pair as one linked transfer, a Lonely Transfer as a linked transfer whose Transfer Counterpart stays uncleared until its other side arrives (UC-006 BR-010); its Transfer Legs have the status Transfer Leg. Nothing is written until the Operator confirms the Upload Plan (UC-006). The Transfer Legs of a Guessed Transfer have the status Guessed Transfer and are not eligible until the Operator confirms the Transfer or chooses another Budget Account. Confirmed and Budget Account Chosen Transfers are eligible as linked transfers; Held Transfers are in no Upload Plan (BR-007); after Split, each Transfer Leg gets its status as an ordinary Bank Transaction. A Bank Transaction that arrives for the side of an uploaded Transfer whose Transfer Counterpart is unchanged has the status Arrived As Transfer Counterpart and is not uploaded (UC-006 BR-010); it then settles that Transfer Counterpart, as the other Transfer Leg of a pair settles the counterpart its upload created. One that arrives later than the transfer pairing days and so is no Transfer Leg of that Transfer, but lies in the Bank Account mapped to the Budget Account that a Transfer names for its other side, while a Transfer Leg of that Transfer was sent as a linked transfer and its Sent Record is not Rejected and no Bank Transaction has settled that side, with the amount of that side, is Needs Review and never New, whatever its date and whether or not Slussen has recorded the Transfer Counterpart (UC-003 BR-001): it may be that side, arrived late, and only the Operator can tell. When the Budget App deleted or merged a Transfer Leg that Slussen sent as a linked transfer, the Transfer stays as it is (BR-015); that Transfer Leg is compared as an ordinary Bank Transaction from then on (UC-004 BR-009), and the other Transfer Leg takes its status from the Transfer Counterpart that send created: Arrived As Transfer Counterpart while it is unchanged, Transfer Counterpart Changed otherwise (BR-009), never Transfer Leg. After Split the Transfer stays stored with its Transfer Legs and is shown as split, so that the Operator can change the decision again (BR-008); while the decision is Split, Slussen forms no other Transfer from its Transfer Legs. For a Transfer that has a Transfer Decision, the first of these that applies gives each Transfer Leg its status: after Split, its status as an ordinary Bank Transaction; a status an earlier rule of the Comparison gives it (UC-003 BR-001), with the effect on the whole Transfer described above; the status BR-013 gives it because of the Review Decision on another Transfer Leg; Held Transfer when the Transfer is held back; and otherwise Transfer Leg. A Guessed Transfer that the Operator splits or holds back keeps its flag, but the number of Guessed Transfers of a Budget counts only those that have no Transfer Decision.

### BR-011: Every Transfer Decision Is Journaled

Every Transfer Decision and every change to it is recorded in the Decision Journal together with the Transfer Slussen had proposed, and so is every Transfer Decision that Slussen drops with its reason (BR-015). Each is recorded once for each Transfer Leg of the Transfer, which the entry names by its Bank Account and its Import Identifier (UC-011 BR-001). Recording never delays or blocks the decision (UC-011 BR-002).

### BR-012: Matching a Transfer Description

The Card Payment Descriptions and Bank Transfer Descriptions are Operator Settings (UC-010 BR-007). An entry matches when its text occurs anywhere in the payee or memo of the Bank Transaction, compared without regard to case and accents, so that ÖVERFÖRING and OVERFORING match either spelling. A `*` in an entry stands for any characters, as in AMERICAN EXPRESS * PMT; no other character has a special meaning.

### BR-013: Doubt About a Transfer Leg Is Decided First

The doubt about a Bank Transaction itself is decided before its Transfer is eligible. When a Transfer Leg of a pair or of a Lonely Transfer is Needs Review, for any reason (UC-003 BR-008, UC-003 BR-012), or Possible Import By Other Path (UC-003 BR-007), the whole Transfer is in no Upload Plan, from either side, until each such Transfer Leg has a Review Decision (UC-004 BR-009). When every such Transfer Leg is decided Import Anyway, it rejoins the Transfer, which then follows BR-010 and goes into the next Upload Plan as one linked transfer. When one is decided Already Present, the whole Transfer counts as already in the Budget App: neither Transfer Leg is proposed for an Upload Plan, and every other Transfer Leg of it has the status Already In Budget App while that decision holds (UC-003 BR-009). Otherwise, when one is decided Defer, the whole Transfer is held back and every other Transfer Leg of it has the status Deferred. Split (A1) is the only way to separate the two Transfer Legs. A Guessed Transfer also needs its Transfer Decision (BR-010). A Transfer Leg whose Already Present decision does not hold any more is Decision No Longer Holds (UC-003 BR-009) and holds its whole Transfer in the same way until the Operator decides on it again: the other Transfer Legs then no longer have the status that decision gave them, and Re-upload lets the Transfer Leg rejoin its Transfer as Import Anyway does (UC-004 BR-009).

### BR-014: The Other Side Is a Budget Account Slussen Fetches

The other side of a Transfer is always a Budget Account that Slussen fetches, that is, one that a Bank Account the Operator added that is not a Retired Account is mapped to through an Account Mapping. A Budget Account that Slussen does not fetch, such as a Manually Kept Account, is never a Funded By, a Transfer Partner, the other side of a Lonely Transfer, the proposal of A8 or a choice in A3; a movement to or from it stays an ordinary Bank Transaction, as a movement across Budgets does (BR-004). A Funded By or Transfer Partner whose Budget Account loses its last such Account Mapping, because its Bank Account's Account Mapping was moved or removed or the Bank Account was retired, is kept but no longer applies: Slussen does not use it to detect Transfers and shows that it no longer applies (UC-009), until the Operator changes or clears it or such a Bank Account is mapped to that Budget Account again. The Funded By or Transfer Partner of a Bank Account whose own Account Mapping is moved or removed (UC-009 A8, A11) is kept too: it does not apply while it is not a Budget Account of the Budget the Bank Account is mapped into, or is the Bank Account's own Budget Account, and applies again without any change when the Bank Account is mapped so that it is.

### BR-015: A Transfer Decision When Its Ground Changes

A Transfer that has a Transfer Decision (Confirmed, Split, Held or Budget Account Chosen) and none of whose Transfer Legs has been uploaded keeps its decision, its Transfer Legs and its two Budget Accounts whenever Slussen detects Transfers again, at a Comparison or after a change (UC-009 BR-015, UC-010 BR-009). A change of a Funded By, a Transfer Partner, a Transfer Description, the transfer pairing days or the Lonely Transfer grace period changes only which Transfers Slussen proposes among the other Bank Transactions. Slussen drops a decision Confirmed or Budget Account Chosen, and only when the Transfer's own sides are no longer valid: the Bank Account of one of its Transfer Legs has no Account Mapping or is mapped to another Budget Account than the one the Transfer names for that side; the Budget Account of a side that has not arrived receives through no Account Mapping any more; after a change of a Bank Account type (UC-009 A14), the Transfer is a Card Payment neither of whose sides is a credit card Bank Account, or a Bank Transfer one of whose sides is; or the other side of a Lonely Transfer was already uploaded as an ordinary transaction (BR-005). A Transfer that the Operator split or holds back is never dissolved by such a change: it keeps its decision, its Transfer Legs and its Budget Accounts until the Operator changes the decision (BR-008); one that is held back stays in no Upload Plan, from either side (BR-007), and the Transfer Legs of a split Transfer stay ordinary Bank Transactions from which Slussen forms no other Transfer (BR-010). Retiring a Bank Account dissolves no Transfer that has a Transfer Decision: while the Bank Account of one of its sides is a Retired Account, the Transfer keeps its decision, its Transfer Legs and its Budget Accounts and is in no Upload Plan, from either side, and restoring the Bank Account (UC-009 A5) brings it back as the Operator decided it; a Transfer without a Transfer Decision none of whose Transfer Legs has been uploaded is released when a Bank Account of its sides is retired, as before (BR-014). Slussen then dissolves the Transfer, records the dropped Transfer Decision with that reason in the Decision Journal (BR-011), and detects Transfers among its Bank Transactions again as among any others (BR-002), so that each is a Transfer Leg of a Transfer Slussen proposes or an ordinary Bank Transaction; nothing of the dropped decision is carried over to a Transfer Slussen proposes then. A Transfer one of whose Transfer Legs has been uploaded is never dissolved or changed (BR-008).

### BR-016: Choosing Another Budget Account

When the Operator chooses another Budget Account (A3), one Transfer Leg of the Transfer stays and its other side is replaced: the one Transfer Leg of a Lonely Transfer; otherwise, for a Card Payment, the Transfer Leg in the credit card Bank Account, or the inflow when both sides are credit card Bank Accounts, and for a Bank Transfer the outflow. The question of A3 concerns the Bank Account of the Transfer Leg that stays. Slussen proposes keeping the answer when the Operator chooses the Budget Account Slussen proposed, or answers a first Card Payment for which Slussen proposed none (A8), and proposes not keeping it when the Operator chooses another Budget Account than the one Slussen proposed; only the Operator's answer sets a Funded By or a Transfer Partner. The Bank Transactions that could be the other side are those of the Bank Account mapped to the chosen Budget Account that have the opposite amount and are dated within the transfer pairing days of the Transfer Leg that stays (BR-002). Among them Slussen pairs first with the Transfer's own other Transfer Leg; then with one that has not been uploaded (BR-008) and belongs to no Transfer; then with one that is a Transfer Leg of a Transfer that has no Transfer Decision and none of whose Transfer Legs has been uploaded, which it takes from that Transfer; among several of the same kind it takes the one with the nearest booked date, then the one Slussen received first (BR-003). A Transfer that loses a Transfer Leg this way is dissolved, and Slussen detects Transfers again for its other Transfer Leg, and for a Transfer Leg the choice released from the selected Transfer, as for any other Bank Transaction (BR-002), from stored data and before any Upload Plan is prepared, also when Slussen stops in between (UC-009 BR-015). When there is at least one and Slussen can pair with none of them, because each has been uploaded or is a Transfer Leg of another Transfer that has a Transfer Decision or one of whose Transfer Legs has been uploaded, Slussen refuses the choice (A10): a Lonely Transfer towards the chosen Budget Account would write there what was already written or what that other Transfer writes. Only when there is none does the Transfer become a Lonely Transfer towards the chosen Budget Account.
