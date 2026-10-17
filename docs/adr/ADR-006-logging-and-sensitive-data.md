# ADR-006: Logging and Sensitive Data

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); re-check pending (S-23/S-24).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review and the specification change S-02; revised for S-23/S-24 on 2026-10-16, pending an external check;
later revisions at the gate are listed under "Review findings addressed".

## Context

- NFR-007, UC-010 BR-005: with Detailed Logging off, device logs contain 0 transaction amounts, payees, memos or
  Bank Account numbers.
- NFR-008 (changed by S-02), UC-007 BR-006, UC-010 BR-005, `BANK_SESSION`: in every logging mode, device logs contain
  0 credentials, Bank Consent data, Bank Sessions, authentication codes or one-time values.
- FR-069, UC-010 A5: the Operator can switch on Detailed Logging, which may include transaction data and Bank Account
  numbers; those logs stay on the device.
- NFR-009 (changed), vision *Sensitive data*: logs and Bank Sessions are never in a device backup or device-to-device
  transfer.
- NFR-012, UC-011 BR-003: the Decision Journal holds no credentials, Bank Consent data, Bank Account numbers,
  identifiers that Data Providers give Bank Accounts, or Data Provider error texts. `OFFERED_BANK_ACCOUNT`: an Offered
  Bank Account never appears in the Decision Journal.
- NFR-021, UC-014 BR-003, `SERVICE_CREDENTIAL` (S-24): service credentials are entered in the app and are never in a
  built app, a device backup or transfer, a Journal Export, the Decision Journal or a log, in any logging mode.
  UC-014 step 5: a change of credentials is journaled without any credential.
- ADR-003 §7: no value of the Build Configuration (since S-24 only the signing setup) is ever printed.
- Evidence: the bootstrap plan records (D-21) that the Predecessor App logged Bank Account identifiers at INFO level,
  and says construction must not port that behaviour.
- The Phase 2 review found that the free-text event name of the Phase 1 `SlussenLog` bypassed the typed facts
  (A-17), that plug-ins could not reach a logging port that lived in `:core` (A-18), and that the import-based rules
  were claimed to prove more than they can (A-19).

## Decision

### 1. One logging contract, in `:plugin-api`

`slussen.pluginapi.diagnostic.SlussenLog`, used by `:core` and every plug-in (which receive it from
`CompositionRoot`, ADR-001 §5) and implemented once in `:app`:

```kotlin
interface SlussenLog {
    /** Always written. Only a LogEvent constant and LogFact values can be passed. */
    fun event(event: LogEvent, vararg facts: LogFact)
    /** Written only while Detailed Logging is on; the lambda is not run otherwise. */
    fun detail(event: LogEvent, message: () -> String)
}

/** Implemented only by enum classes (L-06). The adapter writes the enum class's simple name and the constant's name. */
interface LogEvent

sealed interface LogFact {
    data class Count(val value: Long) : LogFact
    data class Elapsed(val value: java.time.Duration) : LogFact
    data class At(val value: java.time.Instant) : LogFact
    data class Value(val value: Enum<*>) : LogFact          // a Transaction Status, a Fetch outcome, a Blocked Reason, a RefusalReason, ...
    data class RecordId(val kind: RecordKind, val id: Long) : LogFact   // an internal record id
}
```

- **There is no `String` parameter on the always-written path** (A-17). An event is a constant of an enum class that
  implements `LogEvent` (for example `enum class FetchEvent : LogEvent { FetchStarted, FetchFinished }` in `:core`, or
  one per plug-in in its own module), so its name is code, not data. The adapter writes
  `<enum class simple name>.<constant name>` and, for `LogFact.Value`, the constant's `name`, never `toString()`.
  `log.event(bankTransaction.payee)` does not compile.
- `LogFact` is sealed, so no other module can add a fact. It has no constructor for free text, amounts, payees, memos,
  Bank Account numbers or their last four digits.
- `detail` may carry transaction data and Bank Account numbers (FR-069). Its message is free text, which is why it is
  written only in Detailed Logging and why §2 still applies to it.
- A plug-in that needs to explain a Data Provider's or the Budget App's failure while troubleshooting does it through
  its injected `SlussenLog.detail`, after removing credentials and session values (A-18). The default-mode event it
  writes is an enum constant with `LogFact.Value` of a closed reason.

### 2. What is never logged, in any mode

Service credentials (`SERVICE_CREDENTIAL.secret`, its plaintext, the input the Operator typed or chose, a SimpleFIN
setup token and the access credential it is exchanged for, an Enable Banking private key file), Bank Consent data, Bank
Sessions (`BANK_SESSION.session_reference`), Consent Request `state` values and the plug-in's consent handle,
authentication codes, one-time values and the values of `build-config/local/`. They are held only
in `Secret` or `ConsentSession` (ADR-001 §4), whose `toString()` returns a fixed mask. Reading their content requires
the opt-in annotation `@SensitiveApi` (Kotlin `@RequiresOptIn`):

- `Secret.reveal()` and `ConsentSession.reveal()` are annotated `@SensitiveApi`.
- Only plug-in modules (to send a request or check a credential) and `:persistence` (to encrypt a service credential,
  or store a Bank Session or consent handle) may opt in,
  and a file that opts in may not reference `SlussenLog` (L-04). B-06 (ADR-001 §6.3) checks the calls of `reveal()` on
  bytecode.

### 3. Where logs go

- `slussen.app.platform.log.LogcatLog` implements `SlussenLog`. It formats each record and passes it to a `LogSink`
  (a function of priority, tag and text). The production sink calls `android.util.Log.println` with the tag
  `Slussen`; tests use a recording sink, so they see exactly the text that would reach logcat. It reads Detailed
  Logging from the Operator Settings as a cached value that follows every change.
- Slussen writes no log file. Logcat stays on the device and is never part of a backup or transfer, so NFR-009
  holds for logs without a backup rule.
- No crash reporter and no analytics library is used (C-002: no server or hosted component).

### 4. Errors and outside texts

- An exception is logged by its class name and an event constant only, never by its message: a message from a
  library or a Data Provider can contain an address with credentials or an identifier a Data Provider gives a Bank
  Account.
- A Data Provider's error text never leaves the plug-in through its results (ADR-001 §4.1). The one outside text that
  reaches the Operator is the Budget App's reason for a rejection (UC-006 A9, ADR-001 §4.2.4), which is shown on screen
  and never journaled. In Detailed Logging a plug-in may pass an error text to `SlussenLog.detail`, after removing
  credentials and session values (§1).

### 5. Enforcement (Konsist rule K-13 of ADR-001, with the bytecode rules B-02 and B-06)

| ID | Rule |
|---|---|
| L-01 | `android.util.Log` is imported, or named fully qualified, only in `slussen.app.platform.log`; B-02 checks the resolved references. |
| L-02 | No production code calls `println`, `print`, `System.out`, `System.err` or `printStackTrace`; B-02 checks the resolved references. |
| L-03 | No file imports or names `okhttp3.logging` (an HTTP logging interceptor would print headers with credentials). |
| L-04 | `@OptIn(SensitiveApi::class)` appears only in plug-in modules and `:persistence`, and never in a file that references `SlussenLog`; B-06 checks who calls `reveal()`. |
| L-05 | No call of `SlussenLog.detail` builds its message from `.message`, `.localizedMessage` or `.stackTraceToString()` of a `Throwable`. |
| L-06 | Every class that implements `LogEvent` is an `enum class` without a constructor parameter or property of type `String`. |

What these rules do not prove (A-19): a `detail` message assembled through a helper that returns a revealed `Secret`
as plain text in a file without the opt-in is not excluded statically, and neither is any other indirection through
allowed code. The type of `event` (§1) closes the default-mode path completely; the planted-value tests of §6 are the
line behind the `detail` path.

### 6. Tests with planted values, through the real adapter

JVM tests run `LogcatLog` with a recording `LogSink` (§3), so the assertions are about what would reach logcat, not
about a fake:

- the use case flows of `:core` against fakes, and every plug-in's contract tests (ADR-001 §4.4), with invented
  service credentials (typed input, setup tokens, exchanged credentials, key files), Bank Sessions, consent handles,
  `state` values, amounts, payees, memos and Bank Account numbers that are easy to search for, placed in credential
  input, recorded answers and errors;
- with Detailed Logging off, no planted amount, payee, memo, Bank Account number or its last four digits appears
  (NFR-007);
- with Detailed Logging on, no planted credential, Bank Session, handle, `state`, code or Build Configuration value
  appears (NFR-008), and the Decision Journal entries of UC-014 (credentials changed or removed) and every Journal
  Export hold none of them (NFR-012, NFR-021);
- a Gradle TestKit test of `build-logic/` runs the Build Configuration tasks on a fixture with planted values and
  checks that no value appears in any output, at every log level (ADR-003 §7); `NoCredentialInAppTest` checks that none
  enters a built app (ADR-003 §4).

## Consequences

- A developer cannot log a payee by accident in the default mode: there is no parameter that takes it.
- Every log event is a named constant, so the set of things Slussen can log by default is a list in the code.
- Troubleshooting a Data Provider needs Detailed Logging and logcat access (for example through `adb`) on the
  Operator's own phone; plug-ins can now write those details because the contract is in `:plugin-api`.
- The `detail` path stays free text by design (FR-069); its secrecy rests on the opt-in, L-04, B-06 and the
  planted-value tests, not on its type.

## Alternatives

- **A logging library (Timber, SLF4J with an Android backend).** Rejected: the problem is what may be logged, not how;
  a library adds levels that do not map to the two modes of the specification.
- **Log files in `noBackupFilesDir` for later export.** Rejected: no requirement asks for exportable logs; files would
  need size limits and backup rules of their own.
- **Masking by pattern at the logging sink.** Rejected: patterns miss values; types do not.
- **A free-text event name checked by a Konsist rule.** Rejected (A-17): a rule cannot see what a string holds; an
  enum constant cannot hold data.
- **Keeping `SlussenLog` in `:core` and giving plug-ins no logging.** Rejected (A-18): troubleshooting a Data
  Provider needs its details in Detailed Logging.

## AIUP guidance

- No AIUP skill covers logging. NFR-007, NFR-008 and FR-069 come from the vision; how they are enforced in code is
  project-made.

## Review findings addressed

- A-17: §1 (closed event type, sealed facts, no string on the always-written path), §6 (planted values in names and
  payloads, through the real adapter and the build's output).
- A-18: §1 (the contract is in `:plugin-api`; plug-ins receive it), §4.
- A-19 in part: §5 (L-01, L-02 and L-04 backed by bytecode rules; what the rules do not prove).
- S-02: §2 (Bank Sessions never logged).
- S-23/S-24 (2026-10-16, re-check pending): Context (NFR-021, UC-014); §2 (service credentials, their typed input, the
  SimpleFIN setup token and exchanged credential and the Enable Banking key file never logged or journaled;
  `:persistence` may opt in to encrypt them; the encrypted storage itself is ADR-002 §2a); §6 (planted credentials in
  credential input, the UC-014 journal entries and Journal Exports checked).
