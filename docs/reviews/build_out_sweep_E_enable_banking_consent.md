# Sweep E: Enable Banking and Bank Consent (UC-007, UC-001 EB parts, UC-013/UC-009 Bank choice)

Read-only survey of `/home/user/slussen`, branch `build-out`, 2026-11-26. No network, no GitHub, no Gradle.

## What I read

- AGENTS.md; UC-007 (all); UC-001 (all); the consent-related parts of UC-003 (BR-001, BR-012), UC-006 (BR-005), UC-008
  (BR-002, BR-006), UC-009 (BR-004, BR-009, BR-012, A6/A7), UC-010 (defaults, reset), UC-012, UC-013 (A3, BR-009, BR-011, BR-012), UC-014.
- requirements.md (FR-010..FR-014, FR-036, FR-042, FR-079, FR-081, FR-082, NFR-004, NFR-008, NFR-009, NFR-012, NFR-013,
  NFR-017, NFR-018, NFR-022, C-006, C-007, C-008); glossary (Bank Consent, Bank Session, Consent Request, SCA, Unattended Access).
- entity_model.md: DATA_PROVIDER, BANK, BANK_ACCOUNT, OFFERED_BANK_ACCOUNT, LEFT_OUT_BANK_ACCOUNT, BANK_CONSENT,
  BANK_SESSION, CONSENT_REQUEST, FETCH, FETCH_RESULT, BACK_OFF, PROVIDER_REQUEST, OPERATOR_SETTINGS.
- ADR-001 §4.0, §4.1 (incl. `BankConsentCapability`), §4.4, §4.5, §4.6, §5 (composition, device permissions), §6.2 (Konsist K-01..K-16), §7 rows UC-001/UC-007/UC-013.
- ADR-002 §1, §2 (cross-database columns, `consent_request_correlation`), §3 (O-2, O-10), §7 (upgrades, S-39), §10.
- ADR-005 §1, §5; ADR-007 §2, §3; ADR-008 §1, §2 (kinds R/F/U); docs/mockups/consent.html (all states).
- transactions_plan.md §0, §1; archive/construction_kickoff_plan.md O-P2-21, O-P2-73.
- Code: all of `provider-enablebanking/`; `plugin-api/.../provider/*.kt`, `request/Request.kt`; `core/.../fetch/FetchTransactions.kt`
  (fetchOne, request, keep, offeredOf), `core/.../request/Gates.kt`, `core/.../status/ImportStatus.kt` (bankAccountBlocked),
  `core/.../operation/OperationCoordinator.kt`, `core/.../port/ImportStatusStore.kt`; `persistence/.../device/DeviceDatabase.kt`,
  `RoomStores.kt` (bankConsents), schema JSON (device.db v1, slussen.db v5); `app/src/main/AndroidManifest.xml`, `Texts.kt`,
  `OverviewViewModel.kt`, `BudgetViewModel.kt`; `build-logic/.../DevicePermissions.kt`; `gradle/libs.versions.toml`;
  `testing/FakePlugins.kt`; contract test class list in `plugin-api/src/testFixtures/.../ContractTests.kt`.
- Greps for `UC-007`, `consent`, `bankConsent`, `providerBankId`, `bankReference` across all modules.

Not read in full: UC-013 and UC-009 main scenarios, ADR-004, ADR-006, stack.md, the vendor AIUP skills. Not checked:
anything on the network (Enable Banking documentation, GitHub issues #7/#15 beyond the text given).

## 1. What is built and what is not

### Built (consent-related)

- `:plugin-api`: `BankConsentCapability` (`startConsentRequest(bank, returnAddress, state, context)`, `completeConsent`,
  `revoke`), `ConsentStart`, `ConsentCompletion`, `ConsentAnswer` (masked), `ConsentSession` (masked),
  `ConsentFailureKind`, `RevokeResult`; `FetchRequest.sessions`, `BankOutcome.ConsentRefused/AskedToWait/StoppedByGate`,
  `RefusalScope.Bank/BankAccount`, `RefusalReason.UnattendedAccessLimitReached`, `OperatorPresence`.
- `:provider-enablebanking`: profile (needsBankConsent=true, givesStableReference=false, cooldown 360, NotPublished(50, 10..500));
  credentials (`EnableBankingCredentials`: app id + PEM key, `GET /application` with an RS256 JWT, `SignedRequestToken`,
  PKCS#1/#8 reading); `Http.kt` (`send` with call timeout and cancellation, `ExceptionsAsIoFailure`).
  Tests: `EnableBankingDataProviderTest` extends `DataProviderContractTest` (credential contract only; UC-014 BR-002, A1; UC-001 BR-007; UC-012 BR-005/BR-011).
- `:core`: `OperatorSettings.consentRequestMinutes` (15) and `consentWarningDays` (7); `ImportStatus` derives
  MissingBankConsent/ExpiredBankConsent Blocked Reasons and the expiring-consent list (UC-008 BR-002, BR-006) from
  `ImportStatusStore.bankConsents()`; `SettingsOverview` `BankConsentExpires`, `NothingOffered(needsBankConsent)`,
  `NextThing.FetchOrGrantBankConsent`; `OfferRemedy.GrantBankConsent` (UC-013 A3); `BANK_ACCOUNT.provider_bank_id`
  recorded at add/identify (S-33, UC-007 BR-009 groundwork); `BankAccountFingerprint` scoped by bank reference.
- `:persistence`: `RoomImportStatusStore.bankConsents()` returns `emptyList()` (RoomStores.kt:540); device.db v1 has only
  `service_credential` and `offered_bank_account`; `OfferedBankAccountDao.replace(dataProviderId, bankId, …)` already supports per-Bank replacement.
- `:app`: build-state texts P9-13 (`Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD`), the Renew banner with "cannot be renewed here".
- Catalog: `androidx-browser = 1.8.0` in `gradle/libs.versions.toml`, but **not** a dependency of `:app`.

### Not built

UC-007 Grant Bank Consent: **nothing of the flow**. No `@UseCase(id = "UC-007")` test anywhere; no `// UC-007` marker in
production code. Specifically:
- Steps 1-10, A1-A7, BR-001..BR-009 all unbuilt.
- `EnableBankingDataProvider.bankConsent` getter is `TODO(...)`: calling it throws `NotImplementedError`. Nothing calls it today
  (only SimpleFIN's test reads `.bankConsent` on SimpleFIN). Any generic code that touches `plugin.bankConsent` for every
  provider would crash the app until UC-007 is built.
- No `:core` `consent` package (ADR-001 §7 names it); no `ConsentStore` port (O-10 `startRequest`, `consumeAnswer`,
  `replaceConsent`, `revoke`); no `OperationKind` for consent (ADR-007 §2 row "Start, answer or revoke ..."); no state value
  generation/digest; no Offered Bank Accounts from a consent; no identification of covered Bank Accounts (step 9, A5, A6).
- device.db: no `bank_consent`, `bank_session`, `consent_request`, `consent_request_correlation` (ADR-002 §1, §2).
  The KDoc of `DeviceDatabase` says these come "within schema version 1 until the first release": this contradicts ADR-002 §7
  (S-39: every committed version is final; the phone holds v1). They need **device.db version 2 with a hand-written Migration 1→2**.
- No `BankConsentContractTest` in `plugin-api` testFixtures (ADR-001 §4.4 requires gate admission for every Bank Consent request
  and masked `toString()`); no fake `BankConsentCapability` in `:testing` (`FakePlugins` has `bankConsent = null`).
- `:app`: no Bank Consents section on the Data Provider page (ServicePageScreen KDoc: "come with UC-007"), no Bank chooser,
  no Custom Tab, no intent-filter for a return address, no `onNewIntent` handling; Activity has default launchMode.

UC-001 Fetch Transactions, Enable Banking parts:
- `EnableBankingDataProvider.fetch` is `TODO(...)`.
- `FetchTransactions.fetchOne` returns `FetchReport.NeedsBankConsent` for every Data Provider that needs a consent
  (FetchTransactions.kt:138-139), so A4, A9, A5-for-one-Bank, A13, BR-008 (per Bank), BR-012 (Unattended count) are unbuilt.
- `request()` always sends `sessions = emptyMap()` and `OperatorPresent`.
- `keep()` replaces Offered Bank Accounts for the whole Data Provider (`replaceOffered(stored.id, null, …)`): for Enable Banking it
  must replace per Bank, or a Fetch that skipped one Bank (no consent, Back-off) wipes that Bank's Offered Bank Accounts.
- `answered()` ignores `response.bankOutcomes` ("concern only Data Providers that need a Bank Consent, which come with UC-007").
- `DataProviderGate` (Gates.kt): refuses only Source scope (Back-off of the whole DP, Request Limit); no Bank-scope refusal for a
  Back-off of one Bank; no BankAccount-scope Unattended Access check (C-006); stores every Provider Request with
  `bankAccountId = null` and `unattended = false`, so C-006 cannot be counted per Bank Account (the column
  `provider_request.bank_account_id` exists in slussen.db v5, nullable, indexed: no schema change needed).
- `requestToWaitUntil` ignores Bank-scope Back-offs; no code starts a Back-off with a `bank_id`.
- FETCH.outcome Not Consented is never recorded.

UC-013 / UC-009 (as far as Bank Consent concerns them):
- UC-013 A3 for a Data Provider that needs consent: the core has the remedy (`OfferRemedy.GrantBankConsent`), the app shows it
  as not in this build. No way to choose a Bank from the Data Provider's list: the plug-in contract has **no operation that
  lists Banks** (ADR-001 §4.1 has none), and no mockup state shows a Bank chooser (consent.html has a button "Grant Bank
  Consent for another Bank" and nothing behind it).
- UC-009 A6/A7 ("identified after the next grant of its Bank Consent") are reachable only once UC-007 delivers Offered Bank Accounts.

UC-008 (existing code, a defect found, not mine to fix but blocks correct EB behaviour):
- `ImportStatus.bankAccountBlocked` (ImportStatus.kt:333-345) looks up the Bank Consent and the Bank Back-off by
  `bankAccount.bankId` (the Operator's chosen Bank) instead of `bankAccount.providerBankId`, and only via `dataProviderId`
  (not `balanceDataProviderId`). UC-007 BR-009, UC-001 BR-004, entity model BANK_ACCOUNT/BANK_CONSENT/BACK_OFF constraints
  say the Bank the Data Provider names decides. Silent today (no consents exist); wrong as soon as the Operator renames or
  re-chooses a Bank (UC-009 A14) or a file-imported Bank Account takes its balance from Enable Banking (UC-001 BR-011).

## 2. Specification gaps, contradictions, silences (my area)

G1. **No Bank list in the contract.** UC-007 step 1 says "a Bank that the Data Provider reaches"; ADR-001 §4.1 gives
`startConsentRequest(bank: String, …)` but no way to learn the Banks. Needs an ADR-001 §4.1 addition (e.g.
`BankConsentCapability.banks(country, context): BankList`, counted as request kind Other, attended) and a UC-007 step or
flow for choosing the Bank (by country, with search; issue #15 item 2), plus a mockup state. The format of the `bank`
reference is unspecified; Enable Banking identifies an ASPSP by name **and** country.

G2. **Bank identity across countries.** The core turns `DeliveredBankAccount.bankReference` into `BANK.name`
(`connections.bankNamed`, FetchTransactions.kt:568), and `BANK.name` is Unique while `BANK.country` is optional. Two ASPSPs with
the same name in two countries would share one `BANK` row and therefore one Bank Consent. Either the bank reference
includes the country in a display-safe way, or BANK becomes unique by (name, country) (slussen.db v6). Matters only if the
Operator uses same-named Banks in two countries.

G3. **Return address.** Spec never states its form or who registers it. O-P2-21 (archive) accepted a paste-back field as a
fallback; the mockup says "no paste field". Silent on: debug vs release (applicationId `slussen.app` vs `slussen.app.debug`, ADR-005 §5)
both installed; how the Operator learns the exact address to register in his Enable Banking application; Adopting
Developers with their own applicationId (UC-012). NFR-022/ADR-001 §5: an intent-filter (custom scheme or https App Link)
needs **no permission**; a `<queries>` element for `android.support.customtabs.action.CustomTabsService` is not a permission
either. The existing `verifyPermissions` check will flag any permission `androidx.browser` might add (I believe it adds none; not verified).

G4. **Who reads `state` from the answer.** ADR-001 §4.1: the answer is "matched by the digest of the state it carries", but
`BankConsentCapability` has no method to extract it from a `ConsentAnswer`. Either the core parses the `state` query parameter of
Slussen's own return address, or the contract gains `stateOf(answer)`.

G5. **Consent length requested.** C-007/BR-004: "Slussen assumes no fixed consent length". Enable Banking (to verify) needs the client to
send `access.valid_until` when starting authorization; the ASPSP list declares a maximum validity. The spec does not say what to ask
for. Reading that keeps the rule: ask for the maximum the Data Provider declares for that Bank and store the expiry it reports.

G6. **Flows missing in UC-007:** (a) transient failure / network loss at start, completion or revocation (NFR-004 says "treated as
failed"; contract has `TransientFailure`); (b) gate refusal (Back-off, Request Limit) at start or completion (ADR-001 §4.1 says the
reason is shown; no flow); (c) the Operator closes the browser without a result (no answer ever comes; ADR-007 §3 says Upload
preparation is refused while a Consent Request is unanswered within its lifetime and "the Operator can abandon the Consent Request",
but no use case has an abandon step); (d) no browser on the device; (e) completion refused after `consumeAnswer` marked the request
answered (cannot retry: start again).

G7. **A3/A4 "shows the Data Provider's refusal as the Blocked Reason of the Bank's Bank Accounts"**: Blocked Reasons are derived
from stored state (UC-008 BR-001), but no entity stores a consent refusal; and before first grant the Bank has no Bank Accounts.
The mockup state `provider-refused` shows it in the sheet only.

G8. **A9 "treats the Bank Consent as expired from now on"**: BANK_CONSENT has only `expires_at` (the date the Bank reported) and no
status; O-10 has no operation for this. Reading: set `expires_at` to the refusal time (new O-10 operation), or add a column
(device.db v2 anyway, so free if decided before E1).

G9. **Account references across sessions.** UC-013 BR-011 / LEFT_OUT_BANK_ACCOUNT recognise by `provider_reference` only, and
UC-009 BR-004 identifies with certainty only by reference. If Enable Banking's account `uid` is per session (to verify; I recall a
separate cross-session `identification_hash`), every renewal (≤180 days) and every reinstall gives new references: all added Bank
Accounts fall back to "probably recognised" (fingerprint) and need confirmation (A5), Left-Out ones reappear. ADR-001 should say the
plug-in uses a cross-session identifier as `providerReference` and resolves the session-scoped one itself (e.g. one `GET /sessions/{id}`
per Bank per Fetch). `BANK_SESSION.session_reference` is String(200): too short to carry a reference map inside it.

G10. **Attended vs unattended at the Bank.** UC-001 BR-012 counts Unattended Access in Slussen. Whether the Bank sees a request as
attended depends (to verify) on PSU headers (`Psu-Ip-Address`, `Psu-User-Agent`, possibly per-ASPSP required headers). The phone does
not know its public IP without asking a third party. Spec silent; ADR-001 §4.1 `OperatorPresence` exists but nothing says how it is expressed.

G11. **Balance type and sign.** Spec silent which of a Bank's several balances (booked, expected, available, with credit) is the
Account Balance, and on credit-card sign conventions. ADR-001 §4.1 only says balance + reported time.

G12. **Transaction field mapping** for Enable Banking (date: booking/value/transaction date; sign from credit/debit indicator; payee from
creditor or debtor; memo from remittance information) is unspecified. BR-016 forbids completing by guessing, so a missing booking date
cannot silently become the value date without a rule.

G13. **UC-010 reset (all data)** deletes Bank Consents; silent whether Slussen first asks the Data Provider to end them (A7 semantics).
**UC-014 credential removal**: silent whether consents survive removal of Enable Banking credentials (they cannot be used without them).

G14. **K-04**: no "enablebanking" text in `:core`, `:persistence`, `slussen.app.ui`; the return address and the scheme must be generic
(derived from applicationId in `slussen.app.di` or the manifest), and any "register this address" hint must be generic.
**K-15**: SHA-256 (`MessageDigest`) only in `slussen.core.identity`, so the state digest lives there.

G15. Mockup consent.html lacks states for: Bank chooser (country, search), waiting with Cancel/abandon, transient failure, gate
refusal, no browser, and the Bank Consents section for a Data Provider with none yet (only first-run sheet).

## 3. Issue #7 (no Stable Reference) — options and what each means for the build

Current spec: ADR-001 §4.1 row and UC-003 BR-012: every Enable Banking Bank Transaction has Import Identifier kind F and is Needs Review.

1. **Keep it.** No spec or code change beyond building the plug-in with `stableReference = null`. Consequence: Enable Banking is useful
   for Uploads only once UC-004 Review Decisions exist (another area); every transaction needs a manual decision; every Transfer
   with an Enable Banking leg is held (UC-005 BR-013). Safe: nothing is uploaded or merged on a fingerprint alone.
2. **Declare the Stable Reference per Bank.** Spec: `DataProviderProfile.givesStableReference` stays false; ADR-001 §4.1 gains
   per-Bank declaration (the part declares a list of ASPSPs whose `entry_reference` is unique, or an Operator choice per Bank);
   entity model: `DATA_PROVIDER.stable_reference` semantics or a new per-Bank attribute (slussen.db v6 if stored); ADR-008 kind R
   for those Banks. Plug-in passes `entry_reference` only for declared Banks. Risk: a Bank that reuses references → R-kind
   collisions → A17 Unreadable or, worse, Budget App dedupe treating a different transaction as Already In Budget App (silent miss).
   **Switching a Bank from F to R after its transactions were uploaded changes their Import Identifiers → duplicates in YNAB**,
   so this must be decided before the first Enable Banking Upload. Evidence per Bank is needed that the Operator cannot have yet.
3. **Change UC-003 BR-012** so a kind-F Import Identifier alone is not Needs Review. Spec change to UC-003 (and FR on Stable Reference,
   UC-012 BR-005 text); core: one rule in `ComparisonRules`. Affects every source without Stable Reference (all of Enable Banking).
   Risk: a Bank that edits payee/memo after booking produces a new F identifier → a second New transaction → duplicate in YNAB;
   identical same-day transactions rely on occurrence numbers across shifting fetch windows.

Recommendation: **option 1 for this run** (no spec change, no silent-risk path; the plug-in still parses `entry_reference` but does
not pass it), and revisit option 2 with real evidence from the Operator's Banks before the first Enable Banking Upload; avoid
option 3 (it removes the only safety net for fingerprint identities). Option 2 later is cheap only if no Enable Banking
transaction has been uploaded yet.

## 4. Issue #15 — what I would answer

1. Return address: prefer a custom scheme derived from applicationId (`slussen.app://consent` and `slussen.app.debug://consent`,
   both registered in the Operator's Enable Banking application) if Enable Banking accepts it; an intercepted code is useless
   without the application's private key (the code exchange is signed), and the `state` digest, one-time use and lifetime hold.
   Fallback 1: verified https App Link (needs a domain the Operator controls with `assetlinks.json` per applicationId and signing
   certificate; burden for Adopting Developers). Fallback 2: O-P2-21's paste-back field. **Must be verified** against Enable
   Banking's documentation and the Operator's Control Panel.
2. Bank choice by country with search: yes; default country from device locale; list fetched from the Data Provider when the chooser
   opens (one request kind Other).
3. Custom Tab (`androidx.browser` 1.8.0, already in the catalog, ADR-005 §1, allowed for `:app` in ADR-001 §2): yes, with
   fallback to any browser via ACTION_VIEW and a clear message when none exists.

## 5. Proposed build phases

| Id | Name | Builds | Modules | Depends on | Size | Schema |
|---|---|---|---|---|---|---|
| E0 | Spec for consent and EB | ADR-001 §4.1: bank list operation, bank reference (name+country), cross-session account reference, how presence is sent, requested validity, who reads `state`; UC-007: Bank choice step, flows for transient failure, gate refusal, abandon/no result, no browser; A9 storage; entity model if G2/G8 need it; UC-003 BR-012 only if #7 says 3; mockup states (G15); NFR-022 note that the return address needs no permission | `docs/` | operator answers; spec approval | M | none (docs) |
| E1 | Consent storage | device.db **v2**: `bank_consent`, `bank_session` (real FK), `consent_request`, `consent_request_correlation`; hand-written `Migration 1→2`; `schemas/...DeviceDatabase/2.json`; migration test from v1; `ConsentStore` port (O-10 + abandon + mark refused); `RoomImportStatusStore.bankConsents()` real; in-memory store in `:testing`; backup-rule test still excludes device.db | `:core` port, `:persistence`, `:testing` | E0 | M | device.db 1→2 (slussen.db stays 5) |
| E2 | Consent in the core | `:core` `consent`: state (RandomSource, 256 bits) and SHA-256 digest in `core.identity` (K-15); grant/renew (steps 1-10, BR-001..BR-004, BR-009), A1-A7, revoke (BR-007), abandon; `OperationKind.BankConsent` in the coordinator (UC-006 BR-005); Offered Bank Accounts per Bank (O-2 with bankId), identification (step 9, A5, A6, UC-013 BR-011/BR-012); Decision Journal entries if any are specified; fix `ImportStatus` to `providerBankId`/`balanceDataProviderId`; fake `BankConsentCapability`; `BankConsentContractTest` in plugin-api testFixtures | `:core`, `:plugin-api` testFixtures (+ contract addition from E0), `:testing` | E1 | L | none |
| E3 | EB consent plug-in | `banks(country)`, `startConsentRequest` (POST auth), `completeConsent` (POST sessions; accounts → `DeliveredBankAccount`, expiry), `revoke` (DELETE session); error mapping to `ConsentFailureKind`; MockWebServer tests extending the consent contract test; remove `TODO` getter | `:provider-enablebanking` | E0, E2 (contract test) | M | none |
| E4 | Consent screens and return | `androidx.browser` dependency in `:app`; Custom Tab launch with fallback; intent-filter per applicationId (manifest placeholder), `singleTop` + `onNewIntent` + cold start; Bank chooser; Bank Consents section on the DP page (all consent.html states); expiry banner Renew action live; remove P9-13 build-state lines; `verifyPermissions` stays green; screen tests | `:app` | E2, E3 | L | none |
| E5 | EB Fetch | plug-in `fetch`: per Bank session, account list/resolution, balances, transactions with paging (each page through the gate; incomplete → `Failed(Incomplete)`), mapping (G11, G12), outcomes (ConsentRefused, AskedToWait per Bank, rate limit, credentials refused); core: sessions in `FetchRequest`, A4 (skip Banks without valid consent by `provider_bank_id`, no contact), A9, A5 per Bank (Back-off with `bank_id`, Bank-scope gate refusal), per-Bank Offered replacement, FETCH outcome Not Consented, Provider Requests with `bank_account_id` | `:provider-enablebanking`, `:core` `fetch`/`request`, `:persistence` (store `bank_account_id`), `:app` texts | E2, E3 | L | none (columns exist in v5) |
| E6 | Unattended Access limit | UC-001 A13, BR-012, FR-079: BankAccount-scope refusal in the gate, counting unattended Transactions and Balance requests per Bank Account over 24 h; Fetch Result Access Limit Reached; Blocked Reason with free-up time | `:core` `request`/`fetch` | E5 and the Scheduled Fetch (UC-001 A12, another area) | S | none |
| E7 | #7 follow-up (only if option 2 or 3) | option 2: per-Bank Stable Reference declaration, plug-in passes `entry_reference` for those; option 3: one Comparison rule | `:plugin-api`, `:provider-enablebanking`, `:core` | E5 | S-M | option 2 maybe slussen.db 5→6 |
| E8 | Device evidence and acceptance | real grant at a real Bank, return to app (also after process death), Fetch, revoke, renewal, migration test of device.db on the phone | operator | all | — | — |

## 6. Enable Banking API facts the design assumes without proof (to check against its documentation)

1. Redirect URL rules: custom scheme allowed? https only? exact match with pre-registered URLs in the application? query parameters kept?
2. Start authorization (`POST /auth`): body fields (`aspsp.name`, `aspsp.country`, `access.valid_until` required?, `psu_type`,
   `redirect_url`, `state` echoed back unchanged and its maximum length), response (`url`, `authorization_id`), lifetime of the auth URL.
3. Return: parameters on the redirect (`code`, `state`, `error`, `error_description`); values for cancel vs refusal (A1 vs A4).
4. `POST /sessions` with the code: code lifetime, single use, response fields (`session_id`, `accounts`, `access.valid_until`, `aspsp`),
   whether the expiry there is the Bank's (BR-004).
5. Account identifiers: is `uid` per session? Is there a cross-session identifier (`identification_hash`)? What `GET /sessions/{id}`
   returns and whether it contacts the Bank (counts against PSD2 access)?
6. `DELETE /sessions/{id}` semantics and answer when already expired (A7 "not confirmed").
7. `GET /aspsps`: `country` filter, fields (`maximum_consent_validity`, `psu_types`, `auth_methods`, required PSU headers, `beta`),
   whether one call is enough or paging exists; how many Banks per country.
8. Transactions: `date_from`/`date_to`, `continuation_key` paging, `transaction_status` filter; fields `entry_reference`,
   `transaction_amount` (unsigned?), `credit_debit_indicator`, `status` (BOOK/PDNG), `booking_date`/`value_date`, `creditor`/`debtor`,
   `remittance_information`.
9. Balances: the list of `balance_type` values and which one matches booked transactions; `reference_date`/`last_change_date_time`.
10. Errors: format, codes such as `ASPSP_RATE_LIMIT_EXCEEDED` (cited in ADR-001 from the FAQ), expired/revoked session, consent
    refused by Bank, credentials refused (401/403), a "retry after" value; which map to `BankOutcome.ConsentRefused`,
    `AskedToWait`, `SourceOutcome.CredentialsRefused`.
11. Attended access: which PSU headers make a request "PSU present" for the Bank; whether an IP address is mandatory.
12. Application modes: sandbox vs production; "restricted" production mode limited to accounts linked in the Control Panel; whether
    the Operator's application can reach his Banks at all.
13. Enable Banking's own rate limits on its API (the FAQ says quotas by contract; the default 50/24 h is a guess).
14. JWT: `iss`/`aud` values and 24 h maximum (built and accepted on the phone in Phase 9 per the archive: "every credential accepted").

## 7. Safe guesses (conservative readings)

S1. Bank choice: country list, then the Data Provider's Banks of that country with a text search; default country from the device
    locale; `psu_type` personal; Bank reference = name + country, shown as the name.
S2. Requested validity = the maximum the Data Provider declares for the Bank; `expires_at` = the expiry the completion reports
    (C-007, BR-004 intact; no Slussen number).
S3. Return: the whole return URI is a `Secret` inside `ConsentAnswer`; the core (or app) reads `state` from Slussen's own return
    address; `SlussenActivity` `singleTop`, handles the intent in `onCreate` and `onNewIntent`; correlation found by digest only;
    digest computed in `core.identity` (K-15); scheme derived from applicationId so debug and release do not collide.
S4. No result (tab closed): the sheet shows "until HH:MM" with Cancel, which abandons the Consent Request (makes it unanswerable,
    new O-10 operation); after the lifetime it is simply unanswerable.
S5. Transient failure or gate refusal at start: nothing stored, the sheet shows the failure or the Blocked Reason with its time
    (as UC-001 A1/A3/A6); at completion after `consumeAnswer`: treat as A2 ("start again"); at revocation: A7 path "not confirmed",
    delete regardless (BR-007). Consent requests count as kind Other, attended (ADR-001 §4.0).
S6. A3/A4 refusal shown in the sheet only (mockup `provider-refused`), not persisted as a Blocked Reason.
S7. A9: set `expires_at` to the refusal time via an O-10 operation; the Bank's Bank Accounts get Fetch Result Not Consented; FETCH
    outcome Not Consented only when no Bank Account of the Data Provider was read.
S8. `providerReference` = a cross-session account identifier if Enable Banking gives one; the plug-in resolves the session-scoped id itself.
S9. Transactions: booked = status BOOK (pending passed with booked=false, no filtering); date = booking date, otherwise
    `Uninterpretable(MissingDate)` (BR-016, no guessing); sign from the credit/debit indicator; payee = counterparty name;
    memo = remittance lines joined; `stableReference = null` (until #7 changes).
S10. Balance: a booked balance type first (closing/interim booked), then expected; never one that includes a credit limit;
     `balanceReportedAt` from the balance's own time, else null. Credit card sign checked against Budget App convention in tests.

Also conservative: UC-010 reset asks the Data Provider to end each Bank Consent and deletes regardless (A7 semantics); removing
Enable Banking credentials keeps the stored consents (they are unusable until credentials return; UC-014 says nothing about them).

## 8. Risks

R1. Session-scoped account ids (G9): renewal every ≤180 days and every reinstall silently turns every Enable Banking Bank Account
    into "needs confirmation" and brings Left-Out ones back.
R2. Attended/unattended mismatch (G10): without the right PSU headers Banks may count Operator-started Fetches against the 4 per
    24 h PSD2 limit; Slussen's own count (per Bank Account, separately for transactions and balance) may differ from a Bank's
    (some count per consent or count account-detail calls). Shows as rate-limit refusals, not as wrong data.
R3. Existing `ImportStatus` defect (bankId vs providerBankId, dataProviderId vs balanceDataProviderId): wrong "missing consent" /
    wrong Back-off display after the Operator changes a Bank (UC-009 A14), silently.
R4. Return address collisions or mismatch: debug and release with the same scheme → chooser, answer lands in the wrong install → A2;
    a return address that differs by one character from the registered one → Enable Banking error page, no return at all.
R5. Balance type and sign (G11): a wrong choice shows a plausible but wrong balance difference against YNAB, silently.
R6. Not testable without a real Bank Consent: the real SCA round trip (BankID app-to-app, Custom Tab return, process death on
    return), the real expiry reported, real error codes and their mapping to A1/A4/A9, revocation confirmation, real
    `entry_reference` quality (#7), real rate-limit behaviour, whether the Operator's application mode reaches his Banks.
    The device.db v1→v2 migration on the phone also needs a device run (instrumented test).

Also: `bankConsent` getter `TODO()` crashes if any generic code reads it before E3; `ConsentStart.Started.consentAddress` is a plain
String and may carry one-time values in its URL (NFR-008): treat as sensitive, never log.

## 9. Operator questions (candidates)

Q1. Return address: what does your Enable Banking application's redirect URL setting accept (custom scheme like `slussen.app://consent`,
    https only)? Options: custom scheme (recommended, per build variant, both registered); https App Link (needs your own domain);
    paste-back field (O-P2-21). Redo if changed later: manifest intent-filter, the address passed to `startConsentRequest`, the
    registration; core and storage unaffected.
Q2. Which country (or countries) and which Banks will you connect through Enable Banking, and through which of them do you hold
    Bank Accounts that SimpleFIN also reads? Any same-named Bank in two countries? Recommended: one country, chooser defaulting to it.
    Redo: G2 → slussen.db v6 for BANK uniqueness by (name, country), and Bank rows re-keyed.
Q3. Is your Enable Banking application in production (restricted to accounts you linked in its Control Panel) or sandbox, and are your
    Bank Accounts linked? Determines whether acceptance (E8) can happen at all. Recommended: production, restricted, accounts linked.
Q4. Issue #7: keep Needs Review (1), per-Bank Stable Reference (2) or change UC-003 BR-012 (3)? Recommended: 1 now, revisit 2 with
    evidence before the first Enable Banking Upload. Redo: switching after uploads creates duplicates in YNAB (identifiers change).
Q5. May the run change the specification itself (ADR-001 §4.1 bank list and account reference, UC-007 new flows and Bank choice step,
    mockup states, A9 storage) with your review afterwards, given AGENTS.md requires approval before code? Recommended: yes, listed as
    S-nn in the plan for review at the end. Redo: code of E1-E5 if a change is rejected.
Q6. Attended access: may Slussen learn the phone's public IP address from a third-party service to send it to Enable Banking, or send
    only the user agent and accept that some Banks may count your own Fetches as unattended? Recommended: no third-party lookup; send
    what Enable Banking requires without it, verify. Redo: plug-in headers only, plus a network host.
Q7. Build Enable Banking fully in this run even though it cannot be accepted without a real Bank Consent, keeping a build-state line
    until your acceptance? Recommended: yes, acceptance as the first stop after the run. Redo: none, only the line.
