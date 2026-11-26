# Build-out run: standing rules for every builder

You build one phase of `docs/build_out_plan.md` in /home/user/slussen on the branch `build-out` (checked out). Your phase file in this folder says what to build; this file says how.

## Rules

- Do not commit, push, switch branches or stash; leave your changes in the working tree for the orchestrator.
- Change nothing under `vendor/` or `docs/`, unless your phase file says it writes the specification.
- Never read, print or write signing values or credentials; never run `-PincludeLive`; never build `release`. Tests use invented data and local test servers only (`.example` and `.invalid` names).
- Read `AGENTS.md` first, then `docs/build_out_plan.md` §2 and §6 (the operator's answers are binding), the survey your phase file names, and the use cases, ADR sections and mockups it names, in full.
- Follow `aiup-core`'s `use-case-test`, then `use-case-implement`, from `vendor/aiup-marketplace/aiup-core/skills/<skill>/SKILL.md`: tests first, and say which tests you saw fail first. `docs/architecture/stack.md` names the layers, commands and markers; ADR-004 the markers (`// UC-XXX BR-YYY: text` followed by code or KDoc; a `@UseCase` `scenario` is the whole heading of the flow, for example "A5: Other Side Not Arrived Within the Grace Period").
- **No operator is available. Do not stop to ask.** Where the specification is silent or two parts disagree, take the most conservative reading: never a duplicate in the Budget App, never a write the Operator did not confirm, nothing lost. Where a mockup and a use case disagree, the use case wins. Build it, and list each such choice under Assumed in your report with what another answer would change.
- If the specification is wrong in a way no conservative reading repairs, build what can be built, leave the rest out, and say so under Open. Do not edit `docs/` to make it fit.
- Every action on a screen shows its state at its own control before it sends anything, its result in the same place, and a failure as an answer at the control, never as a crash (NFR-023). A ViewModel catches what its call can throw.
- The names in code follow the glossary; its Avoid column is a build rule (Konsist T-05).

## Gate (run it before you report; it must pass)

```text
ANDROID_HOME=/opt/android-sdk sh ./gradlew --stop
ANDROID_HOME=/opt/android-sdk sh ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest :persistence:assembleDebugAndroidTest --rerun-tasks --continue
python3 tools/lint_specs.py --strict
python3 tools/check_project_rules.py
```

While you work, run single test classes (`ANDROID_HOME=/opt/android-sdk sh ./gradlew :core:test --tests '<class>' --console=plain -q`) and `:architecture-tests:test`. Instrumented tests (`src/androidTest`) are written and must compile; nobody can run them here.

## Report (under 50 lines)

Files changed; tests added, with their names; which failed first; the `uc-coverage-layered` matrix of the phase's units in short; the gate result, stated plainly, with any step that did not pass; Clarified / Assumed / Open; and any place the specification was unclear or wrong. Do not describe a failing gate as passing, and say what you did not finish.
