# ADR-006: Logging and Sensitive Data

## Status

Proposed (2026-10-14, construction kickoff Phase 1). The Operator sets it to Accepted at the architecture gate.

## Context

- NFR-007, UC-010 BR-005: with Detailed Logging off, device logs contain 0 transaction amounts, payees, memos or
  Bank Account numbers.
- NFR-008, UC-007 BR-006, UC-010 BR-005: in every logging mode, device logs contain 0 credentials, Bank Consent data,
  authentication codes or one-time values.
- FR-069, UC-010 A5: the Operator can switch on Detailed Logging, which may include transaction data and Bank Account
  numbers; those logs stay on the device.
- NFR-009, vision *Sensitive data*: logs are never in a device backup or device-to-device transfer.
- NFR-012, UC-011 BR-003: the Decision Journal holds no credentials, Bank Consent data, Bank Account numbers,
  identifiers that Data Providers give Bank Accounts, or Data Provider error texts.
- ADR-003 §7: no value of the Build Configuration is ever printed.
- Evidence: the bootstrap plan records (D-21) that the Predecessor App logged Bank Account identifiers at INFO level,
  and says construction must not port that behaviour.

## Decision

### 1. One logging port

`slussen.core.port.SlussenLog`, implemented once in `:app`:

```kotlin
interface SlussenLog {
    /** Always written. Only LogFact values can be passed. */
    fun event(name: String, vararg facts: LogFact)
    /** Written only while Detailed Logging is on; the lambda is not run otherwise. */
    fun detail(name: String, message: () -> String)
}
```

`LogFact` can hold only: a count, a duration, a time, an enumeration value of the entity model (a Transaction Status,
a Fetch outcome, a Blocked Reason), a Source Key, a Data Provider or File Format name, and an internal record id. It has
no constructor for free text, amounts, payees, memos or Bank Account numbers. That makes NFR-007 a property of the
type, not of discipline.

`detail` may carry transaction data and Bank Account numbers (FR-069).

### 2. What is never logged, in any mode

Credentials, Bank Consent sessions, Consent Request `state` values, authentication codes, one-time values and Build
Configuration values. They are held only in `Secret` or `ConsentSession` (ADR-001 §4), whose `toString()` returns a
fixed mask. Reading their content requires the opt-in annotation `@SensitiveApi` (Kotlin `@RequiresOptIn`):

- `Secret.reveal()` and `ConsentSession.reveal()` are annotated `@SensitiveApi`.
- Only plug-in modules (to send a request) and `:persistence` (to store a session) may opt in, and a file that opts in
  may not reference `SlussenLog` (L-04).

### 3. Where logs go

- `slussen.app.platform.log.LogcatLog` writes to Android's logcat with the tag `Slussen`. It reads Detailed Logging
  from the Operator Settings as a cached value that follows every change.
- Slussen writes no log file. Logcat stays on the device and is never part of a backup or transfer, so NFR-009
  holds for logs without a backup rule.
- No crash reporter and no analytics library is used (C-002: no server or hosted component).

### 4. Errors and outside texts

- An exception is logged by its class name and Slussen's event name only, never by its message: a message from a
  library or a Data Provider can contain an address with credentials or an identifier a Data Provider gives a Bank Account.
- A Data Provider's error text never leaves the plug-in (ADR-001 §4.1). The one outside text that reaches the
  Operator is the Budget App's reason for a rejection (UC-006 A9, ADR-001 §4.2), which is shown on screen and never
  journaled. In `detail` mode a plug-in may pass an error text to `SlussenLog.detail`, after removing credentials
  and session values.

### 5. Enforcement (Konsist rules, K-13 of ADR-001)

| ID | Rule |
|---|---|
| L-01 | `android.util.Log` is imported only in `slussen.app.platform.log`. |
| L-02 | No production code calls `println`, `print`, `System.out`, `System.err` or `printStackTrace`. |
| L-03 | No file imports `okhttp3.logging` (an HTTP logging interceptor would print headers with credentials). |
| L-04 | `@OptIn(SensitiveApi::class)` appears only in plug-in modules and `:persistence`, and never in a file that references `SlussenLog`. |
| L-05 | No call of `SlussenLog.event` or `SlussenLog.detail` passes `.message`, `.localizedMessage` or `.stackTraceToString()` of a `Throwable`. |

### 6. Tests with planted values

A JVM test in `:core` runs the use case flows against fakes, with invented credentials, consent sessions, amounts,
payees, memos and Bank Account numbers that are easy to search for, and a recording `SlussenLog`:

- With Detailed Logging off, no planted amount, payee, memo or Bank Account number appears (NFR-007).
- With Detailed Logging on, no planted credential, session, `state` or code appears (NFR-008).

The contract tests of ADR-001 §4.4 check the same for each plug-in's results.

## Consequences

- A developer cannot log a payee by accident in the default mode: there is no way to put it into a `LogFact`.
- Troubleshooting a Data Provider needs Detailed Logging and logcat access (for example through `adb`) on the
  Operator's own phone.
- L-04 and L-05 are text rules and can be worked around by indirection; the planted-value tests of §6 are the second
  line.

## Alternatives

- **A logging library (Timber, SLF4J with an Android backend).** Rejected: the problem is what may be logged, not how;
  a library adds levels that do not map to the two modes of the specification.
- **Log files in `noBackupFilesDir` for later export.** Rejected: no requirement asks for exportable logs; files would
  need size limits and backup rules of their own.
- **Masking by pattern at the logging sink.** Rejected: patterns miss values; types do not.

## AIUP guidance

- No AIUP skill covers logging. NFR-007, NFR-008 and FR-069 come from the vision; how they are enforced in code is
  project-made.
