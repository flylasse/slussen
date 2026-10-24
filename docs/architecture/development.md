# Development View

**Question:** How is the code organized, built, and tested?  
**Artifacts:** this document  
**Notation:** Markdown  
**For the agent:** read before writing code. The Layers table says which layer skill builds and tests each
technical layer: `/use-case-implement` runs the Skill column bottom-up, `/use-case-test` runs the Test skill column from the
integration layer up, and `/use-case-coverage` searches for the markers in the last table.

**Last updated:** 2026-10-19

Derived from the Accepted ADRs: modules and dependencies from ADR-001 §1-§3, storage from ADR-002, test runner and
gates from ADR-005 §3, markers from ADR-004 §1-§2. On a conflict, the ADR wins and this view is corrected. Written by
hand from the template of `aiup-core` `skills/architecture/references/development.md` (Marketplace PR #38, branch
`feat/layer-plugins`, read while the PR is open; O-P2-38).

## Layers

| Layer       | Technology | Skill | Test skill | Location |
|-------------|------------|-------|------------|----------|
| Migration   | Room schema versions (`exportSchema`), each final once committed, and hand-written Room `Migration`s (ADR-002 §7) | – | – | `:persistence`, schemas in `persistence/schemas/` |
| Persistence | Room databases, DAOs and the storage ports O-1..O-16 of the core (ADR-001 §3, ADR-002 §3) | – | – | `:persistence`, `slussen.persistence.*` |
| Service     | Kotlin/JVM, the rules of every use case, the operation coordinator (ADR-007), on the plug-in contracts of `:plugin-api` | – | – | `:core`, `slussen.core.*` |
| API         | – | – | – | – |
| UI          | Jetpack Compose and ViewModels | – | – | `:app`, `slussen.app.ui.*` |
| E2E         | – | – | – | – |

No Android or Kotlin layer plugin exists in the Marketplace, so every Skill and Test skill is `–`. Following
`/use-case-implement` ("When a build layer's Skill column is `–` but the Technology column names a technology"),
each layer is built by the shared rules of that skill and the project's existing code, and its tests are written
by the shared rules of `/use-case-test` with the test kinds of ADR-004 §2. Slussen has no API layer (no remote
contract for a separate caller) and no E2E layer (no `docs/test_cases/`, ADR-004 §2).

**Integration layer:**

| Use case offered through | Tested in | Test skill |
|--------------------------|-----------|------------|
| UI                       | Service   | –          |
| Service                  | Service   | –          |

The entry layers are UI and Service. Service alone offers what no actor starts through a screen, such as the
Scheduled Fetch worker of UC-001 A12, which `:app` schedules and which calls the core. No use case has a
`**Layers:**` line yet, so every use case is tested in the first row: its use case tests are the
`UC<id><Name>Test` classes of `:core`, which exercise every step, alternative flow and business rule through the
core's entry functions; the ViewModel tests of `:app` test what the screen decides and that a rejection reaches the
Operator with the specified message, not the rule again.

**Deviation from the layer catalog, recorded:** the catalog makes a layer the integration layer only when its tests
run *in process, real database*. In the agent gate (`gradlew check`, ADR-005 §3) no Slussen test does:

- the ViewModel tests (`UC<id><Name>ViewModelTest`) run on the JVM against the fakes of `:testing`: *backend mocked*,
  so the UI row moves to Service, as the catalog's rule does for a UI whose tests mock the backend;
- the core tests (`UC<id><Name>Test`) run against the port fakes of `:testing`, so the catalog's "real database"
  condition is not met in the gate;
- real storage is proven by `UC<id><Name>PersistenceIT` and the screens by `UC<id><Name>ScreenIT`, both
  instrumented tests under `gradlew connectedCheck` on a device or emulator. They are outside the agent gate and run
  by the Operator as device evidence (ADR-005 §3, §7).

Robolectric, which would bring Room and Compose into the JVM gate, is rejected by ADR-005 (Alternatives: JUnit 4
only, and it cannot prove the platform SQLite of `minSdk`).

## Module layout

Hexagonal, multi-module; package root `slussen`, one package root per module (ADR-001 §1, §2):

- `:plugin-api` (`slussen.pluginapi`): the plug-in contracts and value types; its `testFixtures` hold the contract
  tests of ADR-001 §4.4 and the `@UseCase` annotation of ADR-004 §2.
- `:core` (`slussen.core`): the Service layer and its ports in `slussen.core.port`; tests in `core/src/test`.
- `:persistence` (`slussen.persistence`): the Migration and Persistence layers; JVM tests in `src/test`,
  instrumented tests in `src/androidTest`.
- `:app` (`slussen.app`): the UI layer in `slussen.app.ui`, the composition root, the Scheduled Fetch worker in
  `slussen.app.schedule`, the log adapter; ViewModel tests in `src/test`, screen tests in `src/androidTest`.
- `:testing` (`slussen.testing`): in-memory fakes of the core's ports and of the plug-in contracts, invented data.
- `:architecture-tests` (`slussen.architecture`): the Konsist, bytecode and traceability rules (ADR-001 §6,
  ADR-004 §3).

**Plug-in modules fit no catalog layer.** `:provider-*`, `:budgetapp-*` and `:fileformat-*` are outbound adapters
behind the contracts of `:plugin-api`: the Service layer calls them, never the other way round, and they never
depend on `:core` or on each other. Each is tested in its own `src/test` by extending the contract tests of
ADR-001 §4.4 (`<Name>DataProviderTest`, `<Name>BudgetAppTest`, `<Name>FilePreprocessorTest`); a rule that lives in
a plug-in carries its marker there (ADR-004 §1).

**Build logic fits no catalog layer either.** `build-logic/`, an included build, holds the convention plugins and
the release signing and no-credential checks of UC-012 (ADR-001 §1, ADR-003). It is tested by Gradle TestKit tests
in `build-logic/src/test`, which run in `gradlew check` (ADR-005 §3).

## Commands

| Purpose            | Command |
|--------------------|---------|
| Build              | `gradlew :app:assembleDebug` |
| Unit tests         | `gradlew check` |
| Integration tests  | `gradlew check` (core use case tests on the fakes of `:testing`); `gradlew connectedCheck` on a device or emulator for `…PersistenceIT` and `…ScreenIT`, run by the Operator only (ADR-005 §3) |
| End-to-end tests   | – |

Agents never run live tests (`-PincludeLive`) and never build `release` (ADR-004 §2, ADR-005 §3).

## Markers

The layer skills that would keep these entries do not exist for this stack, so this table is kept by hand from
ADR-004 §1-§2; on a conflict, ADR-004 wins. This waives the template's "do not edit by hand" rule.

| Layer       | Skill | Kind | Marker to grep for |
|-------------|-------|------|--------------------|
| All         | –     | Code | `// UC-XXX BR-YYY:` directly above the function, `if`/`when` branch, `require`/`check` call or validator that enforces the rule; a KDoc block may stand between (ADR-004 §1, checked by T-03, T-04) |
| All         | –     | Test | `@UseCase(id = "UC-XXX", scenario = "…", businessRules = ["BR-YYY"], requirements = ["NFR-ZZZ"])` on each `@Test` method or on the class (ADR-004 §2, checked by T-01, T-02); a linked NFR or C may instead be named first in `@DisplayName("NFR-ZZZ: …")` |
| Migration   | –     | Name | a Room `Migration` per schema version and its `MigrationTestHelper` test from every committed version in `persistence/src/androidTest`; the schema check by identity hash in `persistence/src/test`, run by `gradlew check` (ADR-002 §7) |
| Persistence | –     | Test | `UC<id><Name>PersistenceIT` in `persistence/src/androidTest` |
| Persistence | –     | Code | `// UC-XXX BR-YYY:` only where the storage itself enforces a rule (ADR-004 §1) |
| Service     | –     | Test | `UC<id><Name>Test` in `core/src/test` |
| Service     | –     | Code | `// UC-XXX BR-YYY:` in `:core`, and in a plug-in module where the rule lives there |
| UI          | –     | Test | `UC<id><Name>ViewModelTest` in `app/src/test`; `UC<id><Name>ScreenIT` in `app/src/androidTest` |
| Plug-ins    | –     | Test | `<Name>DataProviderTest`, `<Name>BudgetAppTest`, `<Name>FilePreprocessorTest`; `<Name>BudgetAppLiveTest` tagged `live`, run by the Operator only |
| Build logic | –     | Test | `@DisplayName("UC-012 …")` on the TestKit tests in `build-logic/src/test`, which do not see `@UseCase` |
