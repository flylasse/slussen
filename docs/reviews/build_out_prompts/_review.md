# Build-out run: standing rules for every reviewer

You review one phase of `docs/build_out_plan.md` in /home/user/slussen (branch `build-out`). The phase's changes are uncommitted in the working tree: `git status --short` and `git diff` show them, and new files are untracked. You are read-only: change no file, run no Gradle (the orchestrator's gate is running in the same tree), run no git command that changes state.

Read `AGENTS.md`, `docs/build_out_plan.md` §2 and §6, `docs/reviews/build_out_prompts/_common.md`, the phase file named in your prompt, and the use case rules and mockup states the changed code names in its markers. Then read every changed and new file.

Look for, in this order:
1. Behaviour that contradicts a business rule, a flow or the operator's answers in §6; a rule the phase file lists that has no code or no test.
2. Anything that could put a duplicate into the Budget App, write without the Operator's confirmation, lose a stored item, or show the Operator something false.
3. Storage: an operation that is not all or nothing, a query that is wrong on real SQLite, a schema or Migration that does not match (only where the phase touches storage).
4. A screen action without its state at its own control, a failure that would crash instead of answer (NFR-023), a ViewModel call that can throw uncaught.
5. Tests that cannot fail, that assert the implementation instead of the rule, or instrumented tests whose set-up cannot produce what they assert (they are not run here, so read them closely).
6. A marker that names a rule the code below it does not implement.

Report at most 40 lines: findings ranked most serious first, each with file and line, the rule it breaks, and a concrete case (input or state, then the wrong result). Mark each CONFIRMED (you traced it in the code) or PLAUSIBLE. Say "no findings" for a category rather than inventing one. Do not report style, naming or anything the gate already checks.
