# Mockup Gate — Adversarial Review of the New and Reworked Flows

- **Reviewer:** Gemini 4 Argon (High)
- **Reviewed commit:** `34180bfbf8a2eca40b746f4808b9d9c02556587b`
- **Scope:**
  - Use cases: `UC-013`, `UC-014` (new); `UC-009` (reworked: rename, move, remove, identify again, add); `UC-010` (general settings, `A10`, `BR-010`); `UC-007` (`BR-008`); `UC-012`.
  - Requirements and glossary: `FR-097..FR-103` and the glossary entries they use.
  - Mockups: `setup.html`, `connections.html`, `account-mappings.html`, `settings.html`, `consent.html`, the `index.html` traceability rows for the use cases above, and every link from other screens into these.

---

## Findings

### N-01 — Specification contradiction and first-run dead end between `UC-013 A3`, `UC-001` Preconditions/step 3, and `setup.html#budgets` / `#consent-first` / `#pair`

- **Severity:** High
- **Location:** `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` step 5 and `A3`; `docs/use_cases/UC-001-fetch-bank-transactions.md` Preconditions and step 3; `docs/mockups/setup.html#budgets` (line 340), `#pair` (line 351), `#consent-first` (line 402); `docs/mockups/consent.html#granted` (line 328).
- **Evidence:** On a fresh install (or when setting up a Data Provider that has no `UC-007` Bank Consent, such as SimpleFIN), `UC-014` step 3 (`checkCredentials`) stores no `OFFERED_BANK_ACCOUNT`s. `UC-013` step 5 lists Offered Bank Accounts stored from the Data Provider, and `UC-013 A3` (*"No Offered Bank Accounts Known Yet for the Data Provider"*) offers *"to start a Fetch (UC-001) or to grant a Bank Consent (UC-007)"* so Offered Bank Accounts are stored (`UC-001` step 4). While `UC-007 BR-008` explicitly permits granting a Bank Consent before any Bank Account is added, `UC-001` Preconditions still require *"At least one Bank Account has an Account Mapping to a Budget Account of a Budget"* and `UC-001` step 3 contacts only *"each Source in scope that has at least one mapped Bank Account"*, making a first-run Fetch of SimpleFIN impossible under `UC-001`. In the mockups, `setup.html#budgets` (`Continue`, line 340) bypasses `#consent-first` and links directly to `#pair` (which assumes 3 SimpleFIN Offered Bank Accounts are already stored), nothing in `setup.html` links to `#consent-first`, and on `#consent-first` (line 402) both `Fetch SimpleFIN` (`href="budget.html#fetch-states"`) and `Grant Bank Consent` (`href="consent.html#grant"` → `consent.html#granted` → `href="budget.html#summer-house"`) exit first-run setup without any path back to `setup.html#pair` (`UC-013 A3` steps 2–3, `UC-007` step 8).
- **Fix:** Allow `UC-001` Preconditions and step 3 to fetch a Data Provider with accepted credentials before any Bank Account is mapped (so `OFFERED_BANK_ACCOUNT`s are stored), route `setup.html#budgets` through `#consent-first` to `#pair`, and provide return links from `#consent-first` and `consent.html#granted` to `setup.html#pair`.

### N-02 — First-run walk-through dead end when skipping a Data Provider on `setup.html`

- **Severity:** High
- **Location:** `docs/mockups/setup.html#welcome` (line 299), `#ynab-token` (line 312), `#simplefin-token` (line 320), `#enable-banking` (line 329), `#cancel` (line 392), `#budgets` (line 340); `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` Preconditions and step 1.
- **Evidence:** `UC-013` Preconditions require only *"At least one Data Provider or File Format is available in this build"*, and `UC-013` step 1 states that the Operator may use only SimpleFIN, only Enable Banking, or only a Transaction File (`UC-013 A4`). In `setup.html`, however, the only link to `#budgets` (`② Budgets`) in the entire file is `Save` on `#enable-banking` (line 329). Saving `#ynab-token` forces `#simplefin-token`, saving `#simplefin-token` forces `#enable-banking`, and tapping `Cancel` on `#simplefin-token` or `#enable-banking` leads to `#cancel` (line 392), which shows `YNAB: Accepted` and `SimpleFIN`/`Enable Banking: Not entered` with no `Continue` / `Choose Budgets` button and non-clickable step chips (`② Budgets`), leaving the Operator unable to reach step 2 (`#budgets`).
- **Fix:** Add a `Continue to Budgets` button on `setup.html#welcome` and `setup.html#cancel` (enabled once `YNAB` is `Accepted`) and a `Skip` (or return-to-credentials-list) action on `#simplefin-token` and `#enable-banking`.

### N-03 — Broken credential replacement, setup cancellation, and credential removal flows across `connections.html` and `setup.html` (`UC-014` step 6 and `A3`)

- **Severity:** High
- **Location:** `docs/mockups/connections.html#simplefin` (line 311), `#enable-banking` (line 322), `#ynab` (line 333), `#set-up` (line 344); `docs/mockups/setup.html#remove-credentials` (line 439), `#removed` (line 447); `docs/use_cases/UC-014-manage-service-credentials.md` step 6 and `A3`.
- **Evidence:** Tapping `Replace` on `connections.html#simplefin`, `#enable-banking`, or `#ynab` links to `setup.html#simplefin-token`, `setup.html#enable-banking`, or `setup.html#ynab-token` — first-run wizard frames (`① Credentials ② Budgets ③ Bank Accounts`) whose `Save` buttons advance to `#enable-banking` or `#budgets` instead of returning to the service's page on `connections.html` as required by `UC-014` step 6 (*"Slussen shows the credentials as accepted on the service's page"*). Similarly, `Cancel` on `connections.html#set-up` (line 344) links to `setup.html#cancel` (the first-run wizard) instead of `connections.html#list`, and `Enter` on `setup.html#removed` (line 447) links to `setup.html#simplefin-token`. Furthermore, `Remove` on `connections.html#enable-banking` (line 322) and `connections.html#ynab` (line 333) is a dead `<span class="btn danger">Remove</span>` with no link and no `UC-014 A3` confirmation frame for Enable Banking or YNAB, and `Cancel` on `setup.html#remove-credentials` (line 439) is a dead `<span>` with no Back button.
- **Fix:** Provide post-setup credential entry/replacement frames (or return links) that return to the respective service page on `connections.html` (`Cancel` returning to `connections.html#list` or the service page), and wire `Remove` on `#enable-banking` and `#ynab` to `UC-014 A3` confirmation frames.

### N-04 — `UC-007 A5` (Revoke a Bank Consent) is unreachable from app navigation after `S-26` moved Bank Consents under `Connections -> Enable Banking`

- **Severity:** High
- **Location:** `docs/mockups/connections.html#enable-banking` (line 322); `docs/mockups/consent.html#list` (line 297), `#revoke` (line 395); `docs/use_cases/UC-007-grant-bank-consent.md` `A5`.
- **Evidence:** Per `S-26`, `settings.html#list` no longer has a `Bank Consents` row; instead, `Settings -> Connections -> Enable Banking` (`connections.html#enable-banking`) is the navigation path to Bank Consents. On `connections.html#enable-banking` (line 322), `Aurora Bank` has only `<a class="btn" href="consent.html#grant">Renew</a>` and `Vesta Sparbank` has only `<a class="btn primary" href="consent.html#grant">Renew Bank Consent</a>`, with no `Revoke` button and no link to `consent.html#list` or `consent.html#revoke`. Meanwhile, `consent.html#list` is an orphaned frame (not linked from any phone screen) whose Back button (`‹`, line 297) still points to `settings.html#list`.
- **Fix:** Add a `Revoke` action (linking to `consent.html#revoke`) on `connections.html#enable-banking`, and update `consent.html#list` / `#revoke` Back and Cancel links to `connections.html#enable-banking`.

### N-05 — Missing `UC-006` flow and `upload-plan.html` frame for `Confirm before Upload Plan = On` (`UC-010 BR-010` / `FR-096`), and contradictory hint text on `settings.html#list-2`

- **Severity:** High
- **Location:** `docs/use_cases/UC-010-adjust-settings.md` `BR-010`; `docs/requirements.md` `FR-096`; `docs/use_cases/UC-006-prepare-and-confirm-upload-plan.md` steps 3–7; `docs/mockups/settings.html#list-2` (line 311), `#general` (line 398); `docs/mockups/upload-plan.html`.
- **Evidence:** `FR-096` and `UC-010 BR-010` require that when `Confirm before Upload Plan` is `On`, *"Slussen asks for an additional confirmation before sending a confirmed Upload Plan"*. However, `UC-006` step 3 specifies *"a single screen with one confirmation action"* and steps 4–7 immediately write to the Budget App upon confirmation with no alternative flow or business rule for `Confirm before Upload Plan = On`, and `upload-plan.html` has no frame for that additional confirmation step. In addition, `settings.html#list-2` (line 311) describes the setting as `"Default: Off · extra step when preparing a plan"` (when preparing a plan rather than before sending a confirmed plan), contradicting `settings.html#general` (line 398), `UC-010 BR-010`, and `FR-096`.
- **Fix:** Add the alternative flow in `UC-006` and a confirmation dialog frame in `upload-plan.html` for when `Confirm before Upload Plan` is `On`, and change the hint on `settings.html#list-2` to `"Default: Off · extra confirmation before sending a confirmed Upload Plan"`.

### N-06 — Contradiction between `UC-012` (`setup.html#own-build`) and `UC-014` (`setup.html#enable-banking`, `connections.html#enable-banking`) on Enable Banking credentials

- **Severity:** Medium
- **Location:** `docs/use_cases/UC-012-set-up-an-own-build.md` step 3, `A1`, `A2`, `BR-001`; `docs/use_cases/UC-014-manage-service-credentials.md` steps 1–4, `BR-001`, `BR-007`; `docs/mockups/setup.html#enable-banking` (line 329), `#own-build` (line 462); `docs/mockups/connections.html#enable-banking` (line 322).
- **Evidence:** `UC-012` step 3 and `BR-001` require the Enable Banking application ID and signing key path in the local git-ignored build file and fail the build before producing an installable app if `ENABLE_BANKING_APP_ID` is missing or a placeholder (`setup.html#own-build`). Yet `UC-014` (`FR-102`), `setup.html#welcome` (`"Enable Banking: Not entered"`), `setup.html#enable-banking`, and `connections.html#enable-banking` treat the Enable Banking Application ID and Private key as runtime service credentials that the Operator enters, replaces, and removes inside the app (and may omit entirely if using only SimpleFIN). In addition, `setup.html#own-build` renders a compile-time build check failure banner inside a running Android `Settings · About this build` phone screen.
- **Fix:** Align `UC-012` and `setup.html#own-build` with `UC-014` so that Enable Banking credentials are runtime credentials in `UC-014` (or optional build-time defaults that do not fail the build when omitted), and separate the build-time error output from the in-app `About this build` screen.

### N-07 — `UC-009 A6`, `A7`, and `BR-007` (Identify a Bank Account Again) were not updated for all-`Rejected` sends

- **Severity:** Medium
- **Location:** `docs/use_cases/UC-009-manage-account-mappings.md` `A6`, `A7`, `BR-007` (compare `A8`, `A11`, `BR-003`, and `docs/entity_model.md` `ACCOUNT_MAPPING.first_sent_at`).
- **Evidence:** `UC-009 A8`, `A11`, `BR-003`, and `entity_model.md` (`ACCOUNT_MAPPING.first_sent_at`) require confirmation only when at least one transaction sent through the Account Mapping has an outcome other than `Rejected`. `UC-009 A6`, `A7`, and `BR-007` still branch on whether *"transactions were already sent through the Bank Account's Account Mapping"* without excluding `Rejected` outcomes, and `A7` step 1 states that those transactions *"stay as they are and will not be sent again"*, which is false when all sends were `Rejected` (`UC-006 BR-020`).
- **Fix:** Update `UC-009 A6`, `A7`, and `BR-007` so that `A6` applies when no transaction has been sent or all sent transactions were `Rejected`, and `A7` applies only when at least one sent transaction has an outcome other than `Rejected`.

### N-08 — Missing specification flow in `UC-009` for mapping an existing unmapped `BANK_ACCOUNT` again after its `ACCOUNT_MAPPING` was removed in `A11`

- **Severity:** Medium
- **Location:** `docs/use_cases/UC-009-manage-account-mappings.md` `A8`, `A11`; `docs/mockups/account-mappings.html#remove` (line 428), `#map` (line 320); `docs/mockups/bank-account.html#unmapped` (line 408).
- **Evidence:** `UC-009 A11` deletes the `ACCOUNT_MAPPING` while keeping the `BANK_ACCOUNT` (`Not mapped`). `account-mappings.html#remove` says *"Kestrel Checking is kept, not mapped. Map it again with Move"* and `bank-account.html#unmapped` links `Map to a Budget Account` to `account-mappings.html#move`, while `Kestrel Old Savings` on `account-mappings.html#map` has a non-clickable `⋯`. In the specification, however, `UC-009 A8` covers only moving an existing `ACCOUNT_MAPPING` (*"Trigger: Operator asks to move a Bank Account's Account Mapping to another Budget Account"*), and `UC-013 BR-002` forbids adding an already-added `BANK_ACCOUNT` again.
- **Fix:** Extend `UC-009 A8` (and cross-reference it in `A11`) to cover creating a new `ACCOUNT_MAPPING` for an existing unmapped `BANK_ACCOUNT` to an unmapped `BUDGET_ACCOUNT` of a served Budget in the same currency, and wire `⋯` on the unmapped row of `account-mappings.html#map`.

### N-09 — `UC-013 A6` and `UC-013 A7` have no traceability rows in `docs/mockups/index.html` and no mockup frames

- **Severity:** Medium
- **Location:** `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` `A6` and `A7`; `docs/mockups/index.html` lines 246–257; `docs/mockups/setup.html`; `docs/mockups/connections.html`; `docs/mockups/account-mappings.html`.
- **Evidence:** `UC-013` defines `A6` (*"Offered Bank Account Matches an Already-Added Bank Account"*, where Slussen shows that the Bank Account is already added and offers to confirm its Account Mapping in `UC-009` instead of adding a second Bank Account) and `A7` (*"Budget App Cannot Be Read"* at step 2 when loading Budgets and Budget Accounts). Neither `A6` nor `A7` appears in `docs/mockups/index.html` or on any frame in `setup.html`, `connections.html`, or `account-mappings.html` (`setup.html#unreachable` depicts `UC-014 A2` at credential check time, not `UC-013 A7` at step 2).
- **Fix:** Add mockup frames for `UC-013 A6` and `UC-013 A7` in `setup.html` / `connections.html` and add their traceability rows in `docs/mockups/index.html`.

### N-10 — False traceability links in `docs/mockups/index.html` and missing visual states for `UC-009 A1`, `UC-010 A4`, and `UC-010 A5`

- **Severity:** Medium
- **Location:** `docs/mockups/index.html` lines 206, 226, 227; `docs/mockups/account-mappings.html#confirm` (line 341); `docs/mockups/settings.html#edit` (line 326), `#list-2` (line 311).
- **Evidence:**
  1. `index.html` line 206 links `UC-009 A1` (*"lists each unpaired Bank Account ... without pre-selecting one"*) to `account-mappings.html#confirm`, which pre-selects `Joint Savings · Aurora Bank` (`class="pick sel"`) with badge `Proposed` (the Main Flow step 2 single-proposal state).
  2. `index.html` line 226 links `UC-010 A4 · BR-004` (*"Changing the Consent warning lead time re-evaluates stored Bank Consents"*) to `settings.html#edit`, which shows only `Scheduled Fetch interval` (`12 h → 6 h`) and nothing about `Consent warning lead time`.
  3. `index.html` line 227 links `UC-010 A5 · BR-005` (*"Changing the Upload Window, File upload window, Matching Date Window or Outdated Account Balance threshold re-evaluates stored data"*) to `settings.html#list-2`, which is only the static settings list with no edit or re-evaluation state.
- **Fix:** Add frames (or explicit state callouts) showing `UC-009 A1` (unpaired candidates with none pre-selected), `UC-010 A4`, and `UC-010 A5`, and point `index.html` lines 206, 226, and 227 to them.

### N-11 — `settings.html#plan-open` (`UC-010 A9`) depicts an unreachable background unconfirmed Upload Plan (contradicting `UC-006 BR-019` / `S-21`), while `UC-009`, `UC-013`, and `UC-014` lack an alternative flow for when an Upload is being sent

- **Severity:** Medium
- **Location:** `docs/mockups/settings.html#plan-open` (line 390); `docs/use_cases/UC-010-adjust-settings.md` `A9`; `docs/use_cases/UC-006-prepare-and-confirm-upload-plan.md` `BR-019`; `docs/use_cases/UC-009-manage-account-mappings.md` `BR-009`; `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` `BR-006`; `docs/use_cases/UC-014-manage-service-credentials.md` `BR-006`.
- **Evidence:** Under `UC-006 BR-019` and `S-21` (`upload-plan.html#leave`: *"Left by Back or a tab ... discarded like Cancel. No background plan, no banner elsewhere"*), leaving the Upload Plan screen immediately discards an unconfirmed Upload Plan, so the Operator can never navigate to `Settings` with an unconfirmed *"Upload Plan open for Household"* as shown on `settings.html#plan-open`. Conversely, under `S-21` (`upload-plan.html#writing`), the Operator *can* leave the Upload screen while a confirmed Upload is **being sent**, which is the state that actually blocks `UC-009`, `UC-010`, `UC-013`, and `UC-014` from `Settings`; yet `settings.html#plan-open` depicts an unconfirmed Upload Plan (`Open Household's Upload Plan`) instead of an Upload in progress, and `UC-009`, `UC-013`, and `UC-014` define no alternative flow (unlike `UC-010 A9`) when the Operator attempts an action while an Upload is being sent.
- **Fix:** Update `settings.html#plan-open` to show an in-progress Upload blocking changes, and add the corresponding alternative flow in `UC-009`, `UC-013`, and `UC-014` (or align `UC-010 A9` wording with `UC-006 BR-019`).

### N-12 — Overlapping group definition in `UC-014 BR-007` when a Data Provider with Bank Accounts has its credentials removed or lost

- **Severity:** Medium
- **Location:** `docs/use_cases/UC-014-manage-service-credentials.md` `BR-007` and `A3`; `docs/mockups/connections.html#list` (line 300); `docs/mockups/setup.html#removed` (line 447).
- **Evidence:** `UC-014 BR-007` states that `Connections` lists *"every service currently in use — the Budget App and every Data Provider that has accepted credentials or at least one Bank Account"* and below them *"every Data Provider of this build whose credentials are not entered, also after they were removed, each with the action to set it up"*. A Data Provider that has at least one `BANK_ACCOUNT` and whose credentials were removed (`UC-014 A3`, `setup.html#removed`) or lost on backup restore (`BR-004`) satisfies both conditions simultaneously.
- **Fix:** Amend `UC-014 BR-007` so that a Data Provider with at least one `BANK_ACCOUNT` and missing credentials appears only in the *"In use"* group (with status `"Credentials missing"`), and the *"Not set up"* group lists only Data Providers with neither accepted credentials nor any `BANK_ACCOUNT`.

### N-13 — First-run walk-through navigation and state inconsistencies in `overview.html#new-installation` and `setup.html#pair-name`, `#start-date`, `#done`

- **Severity:** Medium
- **Location:** `docs/mockups/overview.html#new-installation` (lines 370–375); `docs/mockups/setup.html#pair` (line 351), `#pair-name` (line 369), `#start-date` (line 378), `#done` (line 384); `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` steps 7–10.
- **Evidence:**
  1. `overview.html#new-installation` shows 3 Budget tabs (`Household`, `Summer House`, `Care Fund`) in the bottom navigation bar before any credentials or Budgets exist (unlike `setup.html#welcome..#budgets`), and its Recovery Check banner links to `recovery-check.html#open` (`Cause: Restored Backup` instead of `New Installation`).
  2. On `setup.html#pair-name` (line 369, renaming `CHK 4410`), `Save` jumps straight to `#start-date` instead of returning to `#pair` so the Operator can finish pairing and tap `Add paired`.
  3. On `setup.html#start-date` (line 378), although 2 Bank Accounts were paired in `#pair` (`Kestrel Checking` and `Pinecrest Rewards Card`), Slussen offers a Start Date only for `Kestrel Checking` before `Save`/`Skip` jumps to `#done` (whereas `UC-013` step 10 offers a Start Date *"For each added Bank Account"*).
  4. On `setup.html#done` (line 384), `Open Overview` links to `overview.html#open` (which shows 9 Eligible Transactions and no `Recovery Check open` banner, contradicting `UC-006 BR-017` / `UC-008 A8` for a new installation), and `Fetch all Data Providers` links to `budget.html#fetch-states`.
- **Fix:** Show only `Overview` and `Settings` in the bottom nav of `overview.html#new-installation`, link `setup.html#pair-name` (`Save`) back to `#pair`, step through Start Dates for all added Bank Accounts on `#start-date`, and link `#done` to an initial post-setup Overview state.

### N-14 — Cross-screen Bank Account count, name, and coverage contradictions across `connections.html`, `setup.html`, `account-mappings.html`, `settings.html`, and `consent.html`

- **Severity:** Medium
- **Location:** `docs/mockups/connections.html#list` (line 300), `#simplefin` (line 311), `#enable-banking` (line 322), `#ynab` (line 333), `#stop-serving` (line 370); `docs/mockups/setup.html#remove-credentials` (line 439), `#removed` (line 447); `docs/mockups/account-mappings.html#map` (lines 306–320), `#identify-after-sends` (line 439), `#identify-again` (line 450); `docs/mockups/settings.html#list` (line 300); `docs/mockups/consent.html#list` (line 300), `#grant` (line 319), `#granted` (line 328), `#revoke` (line 395).
- **Evidence:**
  1. `connections.html#simplefin` (line 311) and `setup.html#removed` (line 447) display SimpleFIN's Offered Bank Account names (`Pinecrest Rewards Visa`, `Reserve Saver`) instead of the Operator's Bank Account names in Slussen (`Pinecrest Rewards Card`, `Kestrel Reserve`), violating `UC-009 BR-011`.
  2. `connections.html#list` (`3 Bank Accounts`), `connections.html#simplefin` (`Bank Accounts · 3`), `setup.html#remove-credentials`, and `setup.html#removed` omit `Pinecrest Home Card` (the 4th mapped SimpleFIN Bank Account in `Household`, plus `Harbor Health Savings` in `Care Fund`), even though `connections.html#ynab` says `Household: USD · 4 Bank Accounts`.
  3. `account-mappings.html#map` and `budget.html#summer-house` show **5** mapped Bank Accounts in `Summer House` (4 at `Aurora Bank`: `Aurora Current`, `Aurora Savings`, `Aurora Joint`, `Aurora Dollar`; 1 at `Vesta Sparbank`: `Vesta Card`), yet `connections.html#enable-banking` (`3 Bank Accounts` for Aurora Bank), `connections.html#ynab` (`Summer House: EUR · 4 Bank Accounts`), `connections.html#stop-serving` (`Its 4 Bank Accounts`), and `consent.html#list`, `#grant`, `#granted`, `#revoke` all omit `Aurora Dollar`.
  4. `settings.html#list` (line 300) and `account-mappings.html#map` (line 306) say `3 Budgets · 9 mapped · 1 not mapped · 1 retired`, whereas the group headers on `account-mappings.html#map` sum to `4 + 5 + 1 = 10 mapped`.
  5. On `account-mappings.html#identify-after-sends` (line 439), tapping `Continue` to identify `Kestrel Checking` again opens `#identify-again` (line 450) for `Pinecrest Home Card`.
- **Fix:** Reconcile all Bank Account names and counts across `connections.html`, `setup.html`, `account-mappings.html`, `settings.html`, and `consent.html` with the 10 mapped Bank Accounts on `account-mappings.html#map`.

### N-15 — Project rule violations (`AGENTS.md` §2): bare "account"/"accounts" in `setup.html`, `consent.html`, `UC-009`, and `UC-013`

- **Severity:** Low
- **Location:** `docs/mockups/setup.html#consent-first` (line 402); `docs/mockups/consent.html#grant` (line 319), `#granted` (line 328); `docs/use_cases/UC-009-manage-account-mappings.md` `A1` step 1 (line 46); `docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md` step 5 (line 29).
- **Evidence:** `setup.html#consent-first` line 402 reads `"Grant a Bank Consent to see your Bank's accounts"` (bare `accounts`); `consent.html#grant` line 319 reads `"Accounts covered"` (bare `Accounts`); `consent.html#granted` line 328 reads `"Accounts unblocked"` (bare `Accounts`); and `UC-009 A1` step 1 and `UC-013` step 5 write `"account type"` instead of `"Bank Account type"`.
- **Fix:** Replace bare `"accounts"` / `"Accounts"` / `"account type"` with `"Bank Accounts"` (or `"Offered Bank Accounts"`) and `"Bank Account type"`.

### N-16 — Project rule violations (`AGENTS.md` §2 and `O-P2-25`): casing of `Credit Card`, glossary terms `Card Payment Description` / `Bank Transfer Description` in `UC-012`, Operator Setting names in `UC-010`, and all-caps `RENEW`

- **Severity:** Low
- **Location:** `docs/use_cases/UC-009-manage-account-mappings.md` lines 99, 101, 141; `docs/use_cases/UC-012-set-up-an-own-build.md` lines 6, 19, 27, 42; `docs/use_cases/UC-010-adjust-settings.md` lines 16, 51; `docs/mockups/setup.html#pair` (line 351); `docs/mockups/account-mappings.html#map` (line 309), `#identify-again` (line 450); `docs/mockups/budget.html#ptr-skipped` (line 420).
- **Evidence:** `UC-009` (`A9` steps 1–2, `BR-002`) writes `"credit card"` and `setup.html#pair` and `account-mappings.html#map` / `#identify-again` write `"Credit card"` instead of the Title Case attribute value `Credit Card` (`AGENTS.md` §2); `UC-012` (lines 6, 19, 27, 42) writes the glossary terms `Card Payment Description` and `Bank Transfer Description` in lowercase (`"card payment description"`, `"transfer description"`); `UC-010` step 1 (line 16) and `A5` step 1 (line 51) write setting names in lowercase (`consent warning lead time`, `default Back-off duration`, `outdated Account Balance threshold`, `file upload window`, `theme`) while `BR-001`, `A4`, `A5` Trigger, and `BR-010` capitalize them; and `budget.html#ptr-skipped` (line 420, linking to `consent.html#grant`) uses all-caps `RENEW` instead of sentence-case `Renew` (`O-P2-25`).
- **Fix:** Write `Credit Card`, `Card Payment Description`, and `Bank Transfer Description` in Title Case, align Operator Setting name casing in `UC-010`, and change `RENEW` to `Renew`.

### N-17 — Missing Back buttons (`‹`), non-clickable `Cancel` actions on sheets/dialogs, missing `Restore the default list…` row on `Bank Transfer Descriptions`, and minor copy defects

- **Severity:** Low
- **Location:** `docs/mockups/account-mappings.html` (all frames except `#map`); `docs/mockups/connections.html#set-up` (line 344), `#choose-budgets` (line 359), `#stop-serving` (line 370); `docs/mockups/consent.html` (all frames except `#list`); `docs/mockups/settings.html` (`#edit`, `#invalid`, `#cooldown-zero`, `#schedule-off`, `#cleared`, `#general`); `docs/mockups/transfer-descriptions.html#lists` (line 300), `#restore` (line 322).
- **Evidence:** Secondary frames in `account-mappings.html`, `consent.html`, `settings.html`, and `connections.html#choose-budgets` / `#set-up` omit the `<a class="back">‹</a>` link in `.screen-title`; dialogs (`#move-confirm`, `#remove`, `#identify-after-sends`, `#retire`, `#stop-serving`, `transfer-descriptions.html#restore`) render `Cancel` (and `Retire` / `Restore`) as non-clickable `<span>`s while sheets (`#confirm`, `#add-pair`, `#move`, `#identify-again`, `#rename`, `#start-date`, `#funded-by`, `#choose-budgets`, `#choose-bank`) omit `Cancel`; `transfer-descriptions.html#lists` (line 300) omits `Restore the default list…` under `Bank Transfer Descriptions` (`UC-010 A8`); `connections.html#stop-serving` (line 370) has typo `"andScheduled Fetches"`; and `consent.html#list` (line 295) still says `"Bank Consents in Settings"`.
- **Fix:** Add Back/Cancel links on secondary frames and dialogs, add `Restore the default list…` under `Bank Transfer Descriptions`, fix `"andScheduled"`, and update the `consent.html#list` step label.

---

## Summary

| Severity | Count | Findings |
|---|---:|---|
| **High** | 5 | `N-01`, `N-02`, `N-03`, `N-04`, `N-05` |
| **Medium** | 9 | `N-06`, `N-07`, `N-08`, `N-09`, `N-10`, `N-11`, `N-12`, `N-13`, `N-14` |
| **Low** | 3 | `N-15`, `N-16`, `N-17` |
| **Total** | **17** | |

**Verdict:** No — resolve the 5 High and 9 Medium findings in the specification and mockups before starting Phase 7, especially `N-01`..`N-03` and `N-06`..`N-09`, which directly affect `UC-012`, `UC-014`, and `UC-013`.
