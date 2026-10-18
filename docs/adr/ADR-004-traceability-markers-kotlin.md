# ADR-004: Traceability Markers for Kotlin and JUnit 5

## Status

Accepted (2026-10-15, by the Operator at the architecture gate); re-check pending (S-23/S-24/S-25).

History: proposed 2026-10-14, construction kickoff Phase 1; revised 2026-10-14 at the architecture gate after the
Phase 2 review: live tests and their credentials, device evidence; revised for S-23/S-24 on 2026-10-16 and for S-25 on
2026-10-18, pending an external check; later revisions at the gate are listed under "Review findings addressed".

## Context

AIUP's `docs/workflow.md` (Marketplace commit `05af816`), section *Traceability*, defines how code and tests point
back to the specification:

- **Implementation marker**, for every stack: a comment in the qualified form `// UC-005 BR-003: <the rule in one
  line>` directly above the method, query condition or validator that enforces the rule. Always qualified, because
  rule ids are numbered per use case. A rule enforced in several places gets a marker at each. A rule cited from
  another use case keeps that use case's id. The use case id itself needs no marker in production code; code is
  named after the use case's domain and the tests carry the id.
- **Test markers**, "in the form its test framework supports". The table there lists four stacks. The two JVM
  stacks use `@UseCase(id = "UC-001", scenario = "A1: …", businessRules = {"BR-003"})` in classes named
  `UC001<Name>Test`; journey tests are `TC001<Name>IT` with `@DisplayName("TC-001: …")` and `// Step <n>: <name>` per
  Flow row. Inside a test that carries its use case id, a rule is named by its bare id.
- **Glossary**: names in code follow its terms, never its Avoid synonyms (*Construction inputs*).
- **Coverage**: `aiup-vaadin-jooq` and `aiup-angular-jpa` ship a `uc-coverage` agent with a marker table for their
  stack; other stacks run the same check by hand "with the markers of the stack in question".

The `aiup-vaadin-jooq` `implement` skill (used as the pattern for Phase 7) adds: place the marker while implementing
the rule; a business rule without a marker is one still to implement.

There is no Android or Kotlin stack plugin. Slussen's tests run on JUnit 5 (Jupiter), as in the Predecessor App
(5.11.4), on the JVM for every module and on the device for instrumented tests (ADR-005).

## Decision

### 1. Implementation marker

Exactly the `workflow.md` form, as a Kotlin line comment:

```kotlin
// UC-006 BR-006: A transaction is recorded in the Sent Record before it is sent.
suspend fun upload(plan: ConfirmedUploadPlan) { … }
```

- Directly above the function, `if`/`when` branch, `require` or `check` call, or validator that enforces the rule.
  A KDoc block may stand between the marker and a function; nothing else may.
- Always `UC-XXX BR-YYY`, followed by a colon and the rule restated in one line in Slussen's glossary terms.
- A rule enforced in several places has a marker at each place. A rule cited from another use case keeps that use
  case's id (`UC-003 BR-017` inside `slussen.core.upload`).
- Markers are written in `:core` and in the plug-in modules where a rule lives there (for example the merge
  recognition of `:budgetapp-ynab`, UC-003 BR-005). `:persistence` carries a marker only where the storage itself
  enforces a rule (the confirmation transaction of ADR-002 §4 carries `// UC-006 BR-006:`; the start sequence of
  ADR-002 §6 carries `// UC-006 BR-017:`).
- Code is named after the domain of the use case, in packages such as `slussen.core.upload`, not after the use case
  id (ADR-001 §1).

### 2. Test markers

The annotation, in `testFixtures` of `:plugin-api` (ADR-001), so every module's tests can use it:

```kotlin
package slussen.pluginapi.testfixtures

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class UseCase(
    val id: String,                         // "UC-001"
    val scenario: String = "",              // "Main Success Scenario" or "A1: Data Provider Asked Slussen to Wait"
    val businessRules: Array<String> = [],  // bare ids: ["BR-004"]
)
```

Use case tests:

```kotlin
@DisplayName("UC-001: Fetch Transactions")
class UC001FetchTransactionsTest {

    @Test
    @UseCase(id = "UC-001", scenario = "A1: Data Provider Asked Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A1: a Back-off that has not ended keeps the Data Provider uncontacted")
    fun backOffNotEndedLeavesDataProviderUncontacted() { … }
}
```

| Kind of test | Class name | Where | Runs on |
|---|---|---|---|
| Use case rules and flows, against fakes of the ports and plug-ins | `UC001<Name>Test` | `:core` `src/test` | JVM |
| ViewModel of a use case's screen | `UC001<Name>ViewModelTest` | `:app` `src/test` | JVM |
| Persistence a use case relies on (for example ADR-002 §3, §4, §6, §8) | `UC006<Name>PersistenceIT` | `:persistence` `src/androidTest` | device or emulator |
| Screen of a use case | `UC008<Name>ScreenIT` | `:app` `src/androidTest` | device or emulator |
| Plug-in against its contract | `<Name>DataProviderTest`, `<Name>BudgetAppTest`, `<Name>FilePreprocessorTest`, extending the contract tests of ADR-001 §4.4 | the plug-in module's `src/test` | JVM |
| Plug-in against the real Budget App (C-011) | `<Name>BudgetAppLiveTest`, tagged `live` | the plug-in module's `src/test` | JVM, run by the Operator only |

Rules:

- `<Name>` is the use case name in PascalCase without spaces (`UC001FetchTransactionsTest`).
- Every test method of a `UC…` class carries `@UseCase` with the class's use case id. `scenario` is
  `"Main Success Scenario"` or the alternative flow as `"A<n>: <its title>"`; `businessRules` lists the bare ids the
  test proves. A plug-in test may carry `@UseCase` when it proves a rule that lives in the plug-in.
- A test of a requirement that the use case links (an NFR or a C on its `**Requirements:**` line) carries the
  use case's `@UseCase` and names the requirement first in its `@DisplayName`, for example
  `@DisplayName("NFR-016: a request gives up after the request timeout")`.
- Method names are camelCase without spaces or backticks, because Android before version 11 (API 30) does not allow
  spaces in names and `minSdk` is 26 (ADR-005); the readable text goes into `@DisplayName`.
- Journey tests (`TC-*`) do not exist yet: the project has no `docs/test_cases/` or `docs/processes/`. When one is
  added, it is `TC001<Name>IT` with `@DisplayName("TC-001: …")` on the class and `// Step <n>: <name>` above each
  Flow row's code, exactly as the `aiup-vaadin-jooq` entry of `workflow.md`.
- Tests against a real throwaway YNAB Budget (C-011) carry `@Tag("live")` and run only with `-PincludeLive`;
  `gradlew check` excludes them. Their credentials and the reference of the throwaway Budget come from the test-only
  Gradle properties of ADR-003 §4, set outside the repository in the Operator's `~/.gradle/gradle.properties` or as
  environment variables, read only by the test task run with `-PincludeLive` and never by a task that builds an app
  (O-P1-07; S-25). A live test that finds a property missing fails with a message naming it and where it goes,
  instead of passing silently. **Agents never run live tests**, never with `-PincludeLive`, and never read those properties; only the
  Operator runs them, and the results are recorded as evidence E-10 of ADR-005 §7, which gates the first slice that
  uploads (A-27).
- Each live test carries `@UseCase` with the use case and rule it proves (for example
  `@UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])` for the refusal of a reused
  Import Identifier), so a live result traces to the specification like any other test.

### 3. Checks (Konsist and plain JUnit 5, in `:architecture-tests`)

| ID | Check |
|---|---|
| T-01 | Every class whose name matches `UC\d{3}\w+(Test\|IT)` has `@UseCase` on each `@Test` method (or on the class), and every `id` equals the class's prefix written `UC-XXX`. |
| T-02 | Every `@UseCase.id` names an existing `docs/use_cases/UC-XXX-*.md`; every `businessRules` entry exists as a `### BR-YYY:` heading in that file; every `scenario` other than `Main Success Scenario` matches a `### A<n>: <title>` heading in that file. |
| T-03 | Every comment in production code that starts with `// UC-` has the form `// UC-\d{3} BR-\d{3}: .+` and names a rule heading that exists in the named use case file. A comment `// BR-\d{3}` without its use case fails. |
| T-04 | The next non-blank line after an implementation marker is code or a KDoc block, never another comment or a blank file end. |
| T-05 | No class, function, property or parameter name declared in `slussen..` uses a word from the Avoid column of `docs/glossary.md` as one of its camel-case words (for example `Sync`, `Binding`, `Resolution`, `Destination`). The test reads the glossary at run time. Every exception is listed in the test with its reason. |

These checks read `docs/` from the repository root, so a renamed rule or flow in the specification fails the build
until the tests and markers follow, which is AIUP's "change the specification first, then synchronize the code".

### 4. Coverage and requirement status

There is no `uc-coverage` agent for this stack. The coverage check of a use case is run by hand, or by an agent
given the `aiup-vaadin-jooq` agent definition (`agents/uc-coverage.md`) together with this ADR's marker table, as
`workflow.md` allows. The use case's `**Status:**` and the derived requirement statuses follow `workflow.md`
*Requirement status*; nothing in the build changes a status.

## Consequences

- `grep "UC-006 BR-006"` over the repository finds the specification, the enforcing code and the tests; that is the
  manual coverage check.
- The specification's headings become part of the build: renaming an alternative flow's title in a use case breaks
  T-02 until the tests are renamed. This is intended.
- T-05 makes the glossary's Avoid column a build rule for code names. Names from libraries (for example a type
  called `…Client` in an HTTP library) are not declarations in `slussen..` and are not checked.
- `@UseCase` lives in test fixtures of `:plugin-api`, so a plug-in can use it without depending on the core.

## Alternatives

- **JUnit 5 `@Tag("UC-001")` instead of an annotation.** Rejected as the main marker: a tag holds one string and cannot
  carry the scenario and rules that the JVM stacks of `workflow.md` carry. Tags stay available for filtering.
- **`@DisplayName` only, like the `describe('UC-001: …')` of the TypeScript stacks.** Rejected: free text cannot be
  checked as reliably as annotation attributes; it is kept for readable reports.
- **Kotest or another Kotlin test framework.** Rejected: JUnit 5 is what the Predecessor App used, what both JVM
  stack plugins use, and what Konsist documents.
- **Markers in KDoc (`@see UC-006 BR-006`).** Rejected: `workflow.md` prescribes a line comment of one form for every
  stack; one form keeps a plain text search complete.

## AIUP guidance

- `workflow.md` has no entry for Kotlin or Android; this ADR adds one in the form of the JVM entries. Where the JVM entries
  use Java array syntax (`{"BR-003"}`), Kotlin uses `["BR-003"]`.
- AIUP does not say where the `@UseCase` annotation is defined or what its retention is; the stack plugins assume
  it exists. It is defined here.
- AIUP names no test class suffix for instrumented tests on a device; `IT` is borrowed from the Playwright and
  integration tests of the JVM stacks.
- AIUP has no marker for an NFR or a constraint in a test, although the coverage audit counts each linked NFR and C
  as a unit; the `@DisplayName` convention of §2 fills that gap.
- AIUP has no marker for a rule of the entity model (its constraints), as opposed to a use case's business rule;
  such code gets no marker unless a business rule states the same constraint.
- There is no coverage agent for this stack; §4 follows `workflow.md`'s instruction for stacks without one.
- AIUP has no notion of a test that only the Operator may run (live tests against a real Budget App with private
  credentials); §2 defines it.

## Review findings addressed

- A-27 in part: §2 (live tests with their credential route, run only by the Operator, recorded as evidence that
  gates the first slice that uploads; ADR-005 §7 holds the full evidence list); the instrumented test classes of §2
  cover the persistence evidence of ADR-002.
- O-P1-07: §2 (live-test credentials in `build-config/local/live-test.properties`, tagged, never run by agents).
- A-21 in part: the T-rules run as Konsist rule K-14 with scope Test (ADR-001 §6.2).
- S-23/S-24 (2026-10-16, re-check pending): §2 (`live-test.properties` is a test-only file beside the Build
  Configuration, never read by an app build, so no credential reaches a built app; ADR-003 §4).
- S-25 (2026-10-18, re-check pending): §2 (the live-test credentials moved from
  `build-config/local/live-test.properties` to Gradle properties outside the repository, ADR-003 §4; read only by the
  live-test task).
