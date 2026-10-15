# ADR-008: The Import Identifier Protocol

## Status

Proposed (2026-10-14, architecture gate, revision after the Phase 2 review; revised 2026-10-15 after the Phase 2b
re-review and S-14). The Operator sets it to Accepted at the architecture gate.

This ADR is new. The Import Identifier is the one value that ties a Bank Transaction to what the Budget App holds
across a reinstall, a restore and every later version of Slussen; once a build has sent one, its derivation can never
change. Such a decision needs its own record, which ADR-001 (contracts) and ADR-002 (storage) only refer to.

## Context

- FR-074, UC-003 BR-002, glossary *Import Identifier*: Slussen derives the Import Identifier of each Bank Transaction
  itself, the same way every time, from the Source Key and the Stable Reference, or, without a Stable Reference, from
  the date, amount, payee and memo together with the transaction's position among the identical transactions of its
  Bank Account (`BANK_TRANSACTION.occurrence_number`). A Re-upload adds the number of the Re-upload (UC-004 BR-003;
  `SENT_RECORD.occurrence`). "The same inputs always give the same Import Identifier. Different inputs give the same
  one at most as rarely as two random UUIDs do (NFR-020), and such a collision is still detected when a new Bank
  Transaction is stored": from a Data Provider it is kept as an Unreadable Transaction with the reason "Import
  Identifier collision" (UC-001 A17, BR-016), from a Transaction File the whole file is rejected (UC-002 A6). Every
  Import Identifier begins with a fixed marker that identifies Slussen and fits the length the
  Budget App accepts (C-009: YNAB accepts at most 36 characters).
- UC-003 BR-008: an Import whose Import Identifier begins with Slussen's marker is never treated as an Import by an
  Other Import Path. `BUDGET_APP_TRANSACTION.entered_by` is Slussen exactly when `import_identifier` carries the marker.
- `BANK_TRANSACTION`: within one Bank Account, two Bank Transactions never have the same `import_identifier`.
  `SENT_RECORD`: within one `budget_account_id`, two Sent Records never have the same `import_identifier`; the first
  send (`occurrence` 0) uses the Bank Transaction's own Import Identifier, each Re-upload a new one derived from it and
  the occurrence.
- UC-002 BR-003: transactions of a Transaction File that cannot be told apart are recognised again by their date,
  amount, payee, memo and number.
- C-014 is Rejected and FR-043 is Rejected: Slussen does not use the Predecessor App's format.
- NFR-020, Identifier Uniqueness: every identifier Slussen generates itself collides at most as rarely as two random
  version 4 UUIDs do (RFC 9562, 122 random bits); a collision of Import Identifiers that still occurs is detected and
  handled as UC-003 BR-002 states, never merged or uploaded. It records the Operator's instruction at the architecture
  gate: "id
  collisions should be made extraordinarily unlikely where we control id generation, like uuid or similar
  uniqueness."
- Phase 2 review A-06: the marker, canonical encoding, algorithm and version, collision handling, Re-upload allocation
  and the length rule were not decided, so two implementations could both claim to follow ADR-001 and still produce
  different identifiers.
- UC-003 BR-001, BR-008 and the `BUDGET_APP_TRANSACTION` constraints (changed by S-14): every Import Identifier that
  begins with Slussen's marker is Slussen's; `entered_by` is Slussen when the rest has a form Slussen supports and
  Slussen Unsupported Format when it does not; such a transaction is never Other Import Path, and a Bank Transaction
  that resembles one with Slussen Unsupported Format is Needs Review, also when it is a Transfer Leg, and its whole
  Transfer is not eligible until the Operator's Review Decision on that Transfer Leg (S-16).
- Phase 2b re-review: B-08 (Form C normalisation merged distinct Stable References before collision detection),
  B-09 (the fingerprint kept digits only and had no scope), B-10 (ownership was recognised by the full format).

## Decision

### 1. Format of version 1

An Import Identifier of protocol version 1 is exactly 31 characters:

```text
SLS1-<26 characters from the alphabet a-z and 2-7>
```

- `SLS` (three capital letters, compared exactly) is Slussen's marker, `1` the protocol version, `-` a separator.
- The 26 characters are the first 130 bits of a SHA-256 digest of the canonical input (§2), taken from the most
  significant bit on, in 26 groups of 5 bits, each group written with the RFC 4648 base32 alphabet in lower case
  (`abcdefghijklmnopqrstuvwxyz234567`).
- **Ownership and format are two separate tests (S-14, B-10).** The core applies both to the Import Identifier a Budget
  App Transaction carries; the Budget App plug-in never decides either (ADR-001 §4.2.3).
  - *Ownership:* an Import Identifier that **begins with** `SLS` is Slussen's (UC-003 BR-008). Nothing else is
    checked for ownership.
  - *Supported format:* an owned Import Identifier matching `^SLS[1-9]-[a-z2-7]{26}$` for a version this build
    produces gets `entered_by` Slussen; any other owned one gets `entered_by` Slussen Unsupported Format. It is never
    Other Import Path, and a Bank Transaction that resembles it is Needs Review (UC-003 BR-001, BR-008); this check comes before Transfer detection: when that Bank Transaction is a Transfer Leg, its whole Transfer is not eligible, whichever Transfer Leg would write it, until the Operator's Review Decision on that Transfer Leg; Import Anyway lets the Transfer Leg follow its Transfer, Defer holds the whole Transfer back (UC-003 BR-001, BR-008, BR-014; UC-004 BR-006; UC-005 BR-010; S-16). Only an exact
    equality with a Sent Record's or Bank Transaction's Import Identifier ever settles a send (UC-003 BR-003, BR-017);
    an unsupported one settles nothing.

The length is a storage size of the protocol, not a behavioural number: `BANK_TRANSACTION.import_identifier` and
`SENT_RECORD.import_identifier` hold up to 36 characters.

### 2. Canonical input of version 1

Every field is encoded as the decimal number of bytes of its UTF-8 form, a colon, and those bytes
(`field("abc")` = `3:abc`). The canonical input is the concatenation:

```text
field("slussen-import-identifier") field("1") field(kind) <fields of the kind>
```

| Kind | Used for | Fields after the kind, in this order |
|---|---|---|
| `R` | a Bank Transaction with a Stable Reference | Source Key; Stable Reference, opaque (exactly as delivered) |
| `F` | a Bank Transaction without a Stable Reference | Source Key; booked date; amount; payee; memo; `occurrence_number` |
| `U` | a Re-upload (`SENT_RECORD.occurrence` 1 or more) | the Bank Transaction's own Import Identifier; the occurrence |

Normalisation, complete list:

- **A Stable Reference is opaque (B-08).** It is encoded as the UTF-8 bytes of exactly the string the Source
  delivered, with no normalisation of any kind: two references that differ in any code point are two references and
  give two Import Identifiers. A Stable Reference that is not well-formed Unicode (an unpaired surrogate) cannot be
  encoded losslessly; the core then keeps the delivered item as an Unreadable Transaction with the reason
  `UnreadableStructure` (ADR-001 §4.1), never with a replacement character. Two Bank Transactions of a Bank Account
  have the same Stable Reference only when the strings are equal code point for code point (`BANK_TRANSACTION`
  uniqueness).
- Payee and memo, which are human-readable text, are put into Unicode Normalization Form C. Nothing else is changed:
  no trimming, no change of case, no change of spaces.
- An absent payee or memo is encoded exactly like an empty one (`0:`), because a Source that delivers a missing text
  once as absent and once as empty must not change the transaction's identity.
- The payee and memo are the full texts as delivered (`BANK_TRANSACTION.payee`, `memo`), never the texts shortened for
  the Budget App (UC-006 BR-015).
- The booked date is ISO 8601 `YYYY-MM-DD`.
- The amount is the signed whole number of thousandths of the currency unit (the `Amount` of ADR-001 §4) in decimal,
  with a leading `-` for an outflow and no leading zeros (`-12.500` is `-12500`).
- Numbers (`occurrence_number`, occurrence) are decimal without leading zeros.
- The Source Key is the `source_key` of the Data Provider or File Format, which never changes once released
  (ADR-001 §4.1, §4.3).

The same normalised equality (Form C, absent equals empty) decides which transactions without a Stable Reference are
"identical in date, amount, payee and memo" for `occurrence_number` (UC-002 BR-003), so the identity and the
occurrence count can never disagree.

### 3. Test vectors

`:core` holds the test vectors as a committed resource, `core/src/test/resources/import-identifier-v1.vectors`, and a
test that recomputes each one. A vector is never changed once committed; a failing vector means the protocol changed,
which this ADR forbids (§6). The first vectors, with invented inputs:

| Kind | Inputs | Import Identifier |
|---|---|---|
| `R` | Source Key `example-provider`; Stable Reference `TX-0001` | `SLS1-rscv2q3lvs7nemqz2yqdgvjykw` |
| `F` | `example-format`; 2026-03-05; -12.500; payee `Example Bakery`; memo absent; occurrence 1 | `SLS1-tgwy3vevvqlnyt5mg7miyv2fnt` |
| `F` | the same with memo empty (absent equals empty) | `SLS1-tgwy3vevvqlnyt5mg7miyv2fnt` |
| `F` | the same with occurrence 2 | `SLS1-ejox3o4izdp3rgwdphzn2jhpfn` |
| `F` | `example-format`; 2026-03-05; -12.500; payee `Café Exempel` written with a combining accent; memo absent; occurrence 1 | `SLS1-gpnx76lek6eymiesbmaxgkjqtg` |
| `F` | the same payee written with the precomposed `é` (Form C makes them equal) | `SLS1-gpnx76lek6eymiesbmaxgkjqtg` |
| `U` | base `SLS1-rscv2q3lvs7nemqz2yqdgvjykw`; occurrence 1 | `SLS1-ckfrf47p3mlu3wjsyazsip6k3y` |
| `R` | `example-provider`; Stable Reference `TX-é` with the precomposed `é` (U+00E9) | `SLS1-2obifbyikdagkslwmfqes72y7x` |
| `R` | `example-provider`; Stable Reference `TX-é` written as `e` plus a combining accent (U+0065 U+0301): opaque, so a different identifier | `SLS1-gzjltiw5oyt4laoscprrmdjaft` |

The canonical input of the first vector, for an implementer to check the encoding:
`25:slussen-import-identifier1:11:R16:example-provider7:TX-0001`.

The first slice that computes Import Identifiers adds vectors for a negative and a positive amount, a payee with characters outside the Basic Multilingual
Plane, and a Stable Reference with spaces at its ends (kept as they are).

### 4. Where it is computed and stored

- One function in `:core`, `slussen.core.identity.ImportIdentifierProtocol`, computes every Import Identifier. No
  other code computes, shortens or parses one; Konsist rule K-15 (ADR-001 §6.2) keeps `MessageDigest` and the marker
  text inside `slussen.core.identity`.
- The identifier is computed once, when a Bank Transaction is first stored (ADR-002 §3, operations O-1 and O-3), and
  stored in `BANK_TRANSACTION.import_identifier`. A Re-upload identifier is computed when the Upload Plan that carries
  it is prepared (ADR-007 §4) and stored in `SENT_RECORD.import_identifier` when the plan is confirmed.
- A stored Import Identifier is never recomputed, rewritten or migrated (ADR-002 §7). A later change of the stored
  payee or memo of a transaction with a Stable Reference does not change its Import Identifier, because kind `R` does
  not use them.

### 5. Collisions and occurrence allocation

130 bits make two different inputs with the same Import Identifier extraordinarily unlikely (§7, NFR-020), and a
collision that still occurs is detected, as NFR-020 and UC-003 BR-002 require:

- **Bank Transactions.** Inside the storage operation that stores new Bank Transactions (ADR-002 §3, O-1 and O-3), a
  new transaction whose Import Identifier is already held by a stored Bank Transaction of the same Bank Account with a
  different canonical input (recomputed from the stored row) is a collision. From a Data Provider, the transaction is
  kept as an Unreadable Transaction with the reason "Import Identifier collision" (ADR-001 §4.1), so it is shown for
  review, never merged and never uploaded (UC-001 A17, BR-016, UC-004 BR-007). From a Transaction File, the whole file
  is rejected with that reason and nothing of it is stored (UC-002 A6, BR-001).
- **Re-uploads.** When preparing a Re-upload, the next occurrence is the highest `SENT_RECORD.occurrence` of the Bank
  Transaction plus one. If its Import Identifier is already held in the Budget Account, by a Sent Record or by a Budget
  App Transaction in Slussen's stored copy, that occurrence is skipped and the next one is tried. The occurrence that
  is used is stored with the Sent Record at confirmation and is never given to another send; skipped occurrences are
  never used later.
- **Occurrence numbers.** `occurrence_number` of a transaction without a Stable Reference is allocated inside the same
  storage operation that stores it: the number of stored identical transactions of the Bank Account plus one, in the
  order the Source delivered them (UC-002 BR-003). Once stored it never changes, also when an earlier identical
  transaction is later deferred or no longer delivered.
- A collision is recorded in the Decision Journal as a Status Changed event, and a planted-collision test forces one through a
  test-only digest.

### 6. The length the Budget App accepts, and later versions

- A Budget App plug-in declares `importIdentifierMaxLength` with its source (ADR-001 §4.2; YNAB: 36, C-009). The core
  checks at composition that it is at least the length of every protocol version this build produces (31 for
  version 1). When it is not, the app refuses every Upload with the Blocked Reason "configuration error", and the
  Budget App's contract test fails. An Import Identifier is never shortened to fit, because a shortened identifier is a
  different identifier.
- Version 1 is frozen. A new version is made only by amending this ADR, together with a specification change that
  says how Bank Transactions already sent under version 1 are recognised. A build always recognises every version it
  ever produced (§1), and never recomputes a stored identifier under a newer version.
- A Budget App that accepts fewer characters than version 1 produces therefore needs such an amendment before it can
  be used; replacing the Budget App (FR-073) does not change identifiers already in the Operator's Budgets.

### 7. The uniqueness bar, and every identifier Slussen generates

**The bar (NFR-020).** Every identifier Slussen generates has a collision probability at least as low as two random
version 4 UUIDs (RFC 9562), which carry 122 random bits: it carries at least 122 bits of cryptographic randomness (a random identifier) or of a
cryptographic digest (an identifier derived from its inputs), or it is unique by construction. This is NFR-020
applied to construction. Collision detection (§5) stays in place on top of
it where a collision could otherwise go unnoticed.

**The Import Identifier meets it.** Version 1 keeps 130 bits of SHA-256, 8 more than UUID version 4, in 31 characters,
within the 36 that YNAB accepts (C-009). By the birthday bound, the chance that any two of *n* different inputs share
an identifier is at most *n*² / 2^131. For an illustrative one million Bank Transactions in one Bank Account, far more
than a household has, that is about 4 × 10^-28; the same million random UUIDs version 4 would collide with a chance of
about 9 × 10^-26. The 5 characters left below YNAB's limit are not used, so a Budget App that accepts 31 characters can
still be used; a later version may use them only by amending this ADR (§6).

**Every identifier Slussen generates**, complete list. Values Slussen receives from outside (Stable References, the
Budget App's and Data Providers' references, `ChangeCursor`, the consent handle, Bank Session references) are not
generated by Slussen and are not listed.

| Identifier | Where | How it is generated | Bits | Meets the bar by |
|---|---|---|---|---|
| Import Identifier, first send and Re-upload | `BANK_TRANSACTION.import_identifier`, `SENT_RECORD.import_identifier` (ADR-008 §1-§2) | SHA-256 of the canonical input, first 130 bits, base32 | 130 (digest) | digest ≥ 122; plus collision detection (§5) |
| Consent Request `state` | sent to the Data Provider, never stored (ADR-001 §4.1) | 256 bits from `java.security.SecureRandom` (`RandomSource`), base64url without padding | 256 (random) | randomness ≥ 122; an unknown or reused `state` is rejected (UC-007 A2) |
| Consent Request correlation | `consent_request_correlation.state_digest` (ADR-002 §2) | SHA-256 of the `state`, hex | 256 (digest) | digest ≥ 122; unique index |
| `installation.id` | file in `noBackupFilesDir` (ADR-002 §6) | 256 bits from `SecureRandom`, hex | 256 (random) | randomness ≥ 122 |
| Unreadable copy suffix | `slussen.db.unreadable-<epochMillis>-<random>` and the same suffix for its journal (ADR-002 §8) | the time plus 128 bits from `SecureRandom`, hex | 128 (random) | randomness ≥ 122; the rename refuses to overwrite |
| `bank_account_fingerprint` | `BANK_ACCOUNT`, `OFFERED_BANK_ACCOUNT` | SHA-256 of the scoped canonical number of §8, hex (64 characters, the entity model's length) | 256 (digest) | digest ≥ 122, over an input that keeps letters and is scoped by Data Provider and Bank (§8) |
| Configuration fingerprint | generated Build Configuration (ADR-003 §6), captured by an Upload Plan (ADR-007 §6) | SHA-256 of the normalised non-secret content | 256 (digest) | digest ≥ 122 |
| Payee and memo codes of a Journal Export | the exported file only (UC-011, FR-068) | numbered in order of first appearance within one export (`P1`, `P2`, … for payees, `M1`, `M2`, … for memos), the same text always getting the same code within that file; the table from text to code is kept in memory only and discarded | not random | unique by construction within the file; reveals nothing about the text |
| Database row keys (`id` of every entity, `upload_plan.id`, `sent_record.id`, `consent_request.id`, `recovery_check.id`, `decision_journal_entry.id`, ...) and `upload_plan_item.position` | the three databases | SQLite autoincrement (`Primary Key, Sequence`) within one database | not random | unique by construction within their table; never sent anywhere or compared across installations |
| `coordinator_state.state_version` | `slussen.db` (ADR-007 §6) | a counter raised by one in each storage operation | not random | a version, not an identifier; compared only with a value from the same database |
| Write permit (ADR-007 §7) | memory of the running process only | an object issued for one plan; never serialised | not applicable | identity of an in-memory object; it cannot outlive the process |

A new kind of identifier is added to this table, meeting the bar, by amending this ADR. The only digest used in tests
with fewer bits is the test-only digest of the planted-collision test (§5), which never reaches production code
(K-15, B-07 in ADR-001).

### 8. The Bank Account fingerprint (B-09)

`bank_account_fingerprint` (UC-009 BR-004, `BANK_ACCOUNT`, `OFFERED_BANK_ACCOUNT`) is computed by
`slussen.core.identity` from the `BankAccountNumber` of a delivery (ADR-001 §4), with the field encoding of §2:

```text
field("slussen-fingerprint") field("1") field(Source Key of the Data Provider)
field(the Bank's reference as the Data Provider delivers it, empty when it names none) field(canonical number)
```

- The canonical number is the delivered number with spaces, hyphens, dots and slashes removed (formatting only) and
  the ASCII letters `a` to `z` written as capitals. Every other character, letters and digits included, is kept as it
  is; nothing is reduced to digits, so two numbers that differ in a letter give two fingerprints.
- The scope (Data Provider and the Bank as that Data Provider names it) makes the same number at two Banks or through
  two Data Providers two different fingerprints; the `BANK_ACCOUNT` constraint compares fingerprints within one Data
  Provider only.
- `last_four_digits` of an Offered Bank Account is taken separately: the last four digits of the canonical number
  (the outside convention of UC-009 BR-009), letters skipped.
- The contract tests of ADR-001 §4.4 cover a number with letters, one with formatting characters, and the same
  number under two Banks.

## Consequences

- Two independent implementations that follow §1 and §2 produce the same Import Identifier; the vectors of §3 prove it
  for this one.
- An Import Identifier depends on nothing a build can change: not the Budget App's length limit, not a setting, not
  the Build Configuration. A new installation that recomputes the Import Identifiers of the same Bank Transactions
  finds Slussen's earlier Uploads in the Budget App (UC-003 BR-003), which the Recovery Check relies on (ADR-002 §6).
- Treating an absent and an empty payee or memo alike means two transactions that differ only in that respect are
  identical for identity and occurrence; they are told apart by `occurrence_number`, as UC-002 BR-003 intends.
- A collision costs one transaction a review (or one file a rejection) instead of a silent merge or a duplicate.
- The marker `SLS` is fixed for ever. Because ownership is "begins with `SLS`" (S-14), an Other Import Path that
  happened to write identifiers beginning with `SLS` would be taken for Slussen: its transactions would be Slussen
  Unsupported Format and the Bank Transactions resembling them would go to review, also as Transfer Legs, whose whole
  Transfer then waits, never be uploaded without the Operator's decision. That is the safe direction. The live tests against a throwaway YNAB Budget (ADR-005 §7, E-10) check that YNAB's own
  Bank import writes identifiers of another form.

## Alternatives

- **The Predecessor App's format.** Rejected by the specification (C-014, FR-043).
- **Readable identifiers (Source Key plus Stable Reference in clear).** Rejected: Stable References can be longer than
  36 characters, and an identifier in clear would put a Data Provider's transaction identifier into the Budget App.
- **A random identifier stored at first sight.** Rejected: a new installation could not derive it again, which FR-074
  requires ("also after a reinstall").
- **Hex digest.** Rejected: 4 bits per character leave fewer bits within the length YNAB accepts than base32.
- **Shortening identifiers to each Budget App's limit.** Rejected: it would change identity whenever the Budget App
  or its limit changes.
- **Distinguishing an absent from an empty payee or memo.** Rejected: Sources do not keep that distinction stable, and
  it would create a second identity for the same transaction.
- **Normalising Stable References like payees.** Rejected (B-08): a Stable Reference is an outside identifier whose
  equivalence under normalisation nobody promises; normalising it merged two references before collision detection
  could see them.
- **Recognising ownership by the full format.** Rejected (B-10, S-14): a malformed or future-format identifier of
  Slussen's would become Other Import Path.
- **A digits-only fingerprint.** Rejected (B-09): it merges numbers that differ in a letter.

## AIUP guidance

- AIUP's skills say what an identifier must achieve (the business rule) and leave how to the stack; no skill covers a
  hash protocol, test vectors or versioning of a derived identifier. This ADR is project-made.
- The entity model skill allows no attribute for "protocol version"; the version is part of the identifier's text
  instead of an attribute, so `docs/entity_model.md` needs no change.

## Review findings addressed

- A-06 (all of it): §1 marker and format, §2 canonical encoding, §3 test vectors, §4 single computation and no
  recomputation, §5 collision detection and durable occurrence allocation, §6 the length limit without changing
  existing identifiers.
- NFR-020 (the Operator's uniqueness instruction at the architecture gate, after the review): §7 (the bar of at least
  122 bits or uniqueness by construction, the Import Identifier's 130 bits shown against it, and every identifier
  Slussen generates with how it is generated and its bits); §5 collision detection unchanged.
- B-08: §2 (Stable References opaque, no normalisation; malformed Unicode becomes an Unreadable Transaction), §3 (the
  reviewer's pair as vectors with two identifiers).
- B-09: §7 row and §8 (fingerprint keeps letters, scoped by Data Provider and Bank).
- B-10 (with S-14): §1 (ownership = begins with `SLS`; supported format a separate test; Slussen Unsupported Format,
  never Other Import Path), Consequences.
- C-01 (final check, with S-16): §1 and the Consequences (the review precedes Transfer eligibility; the whole Transfer
  waits for the Review Decision on that Transfer Leg).
- A-02 in part: §6 and the Consequences make the identifier independent of the Budget App, which the refusal of a
  reused Import Identifier (ADR-001 §4.2, ADR-007 §6) depends on.
