# AIUP Marketplace snapshot (read-only)

This folder is a read-only copy of part of the AI Unified Process Marketplace,
<https://github.com/AI-Unified-Process/marketplace>, taken from one commit. It is **never edited here**: every file
except this README is byte for byte the Marketplace's own (`.gitattributes` keeps git from changing their line
endings). It lets cloud sessions and sub-agents, which cannot reach a clone outside the repository, read the skills
that AGENTS.md §2 names.

## Source

| | |
|---|---|
| Commit | `93e8d5926c2b5f46ebac60a49f7a61e0e0826858` |
| What it is | `aiup-core` 3.0.0: the merge of Marketplace PR #38 (`feat/layer-plugins`) into `main`, 2026-10-24 |
| Copied | 2026-11-25 |
| Files | `aiup-core/` (whole), `docs/workflow.md`, `LICENSE`, `NOTICE`: 685 files, each blob id checked against the commit |

This one commit is authoritative for everything Slussen reads from the Marketplace: the specification skills
(`requirements`, `entity-model`, `use-case-diagram`, `use-case-spec`, `spec-review` with `scripts/spec_lint.py`,
`reverse-engineer`), the `aiup-core` 3.0.0 construction skills (`use-case-test`, `use-case-implement`,
`use-case-coverage`, `agents/uc-coverage-layered.md`) and `docs/workflow.md`. Nothing else is copied: no other
Marketplace plug-in, not the book, not the predecessor repository.

## What it replaces

Until this snapshot, the skills were read from a clone at `C:\work\marketplace`:

- **The pin `05af8163838df704620ebe1121cd6019dbce276c`** for the specification skills, the linter and
  `docs/workflow.md`. It is the first parent of the merge, so the merge differs from it only by PR #38.
- **The branch `feat/layer-plugins`** of PR #38 for the `aiup-core` 3.0.0 construction skills, read while the PR was
  open. The last commit of it read here was `7429182afe01797765be3b9a6d1231b73fac3c1b`; four more commits followed
  before the merge.

Current `main` (`aiup-core` 3.21.4, at `0448f20a1c6a108a896e3115ce77f0f143646c61` on 2026-11-25) was not taken.
Moving to it is a later operator decision.

### Changes from the pin `05af816` in the specification skills

- `requirements`: unchanged.
- `entity-model`: binary content is a `String` with a `Binary content:` description; a model may be split by
  module (`## Modules` and `## Module: <name>` sections), kept when it is updated, never introduced by this skill.
- `use-case-diagram`: a diagram may be split by module (a `@startuml Modules` overview and one block per module),
  kept when it is updated, never introduced by this skill.
- `use-case-spec`: an optional `**Layers:**` line (`UI`, `API` or `Service`) names the entry layers of a use case
  that is not offered through every one (`SKILL.md`, `references/format-spec.md`, `references/use-case.md`);
  `scripts/validate_use_case.py` warns on a wrong value (`LAYERS_INVALID`, `LAYERS_EMPTY`, `LAYERS_DUPLICATE`). The
  pin's stray `scripts/__pycache__/validate_use_case.cpython-314.pyc` is gone.
- `spec-review`: hand-offs name `/use-case-coverage` beside `/coverage-check`; **`scripts/spec_lint.py` differs from
  the pin's**: it checks the `**Layers:**` line and models and diagrams split by module, with new self-tests.
- `reverse-engineer`: a new step identifies modules and writes the diagram and the entity model split by module;
  new `references/stack-signals.md`.

### Changes from `7429182` (the last branch commit read) to the merge

`/architecture` became `/stack`: `skills/architecture/` is `skills/stack/`, its `references/development.md`
template is `references/stack.md`, and `references/architecture-readme.md` is gone. `use-case-test`,
`use-case-implement`, `use-case-coverage`, `test-case`, `test-case-implement`, `test-case-coverage` and
`agents/uc-coverage-layered.md` now read `docs/architecture/stack.md` (Slussen's file is
`docs/architecture/development.md`: an operator decision, recorded in the active plan). `entity-model`,
`use-case-diagram`, `reverse-engineer` and `spec-review` (with `spec_lint.py`) gained the module split above;
`use-case-spec` says "stack file" for "Development View" (one line each in `SKILL.md` and
`references/format-spec.md`); `README.md` and `docs/workflow.md` follow the rename; and ten evaluation scenarios
(`evals/scenario-17` to `scenario-26`) were added or changed.

## Licence

Apache License 2.0 (`LICENSE`), with the attribution notice in `NOTICE` (Copyright 2025-2026 Simon Martinelli,
Martinelli LLC, and the AI Unified Process contributors). The licence allows copying and redistributing the files
when the licence and the `NOTICE` go with them, and changed files are marked; none is changed here.

## Refreshing

Moving to another commit is an operator decision, recorded in the active plan's methodology evaluation log. Then,
from the repository root, with a Marketplace clone that has the commit:

```sh
git rm -r -q vendor/aiup-marketplace/aiup-core vendor/aiup-marketplace/docs vendor/aiup-marketplace/LICENSE vendor/aiup-marketplace/NOTICE
git -C <clone> -c core.autocrlf=false -c core.eol=lf archive <commit> aiup-core docs/workflow.md LICENSE NOTICE \
  | tar -x -C vendor/aiup-marketplace
git add vendor/aiup-marketplace
```

`core.autocrlf=false` matters on Windows: without it `git archive` writes CRLF line endings and the copy no longer
matches the commit. Check the copy: every blob id in `git ls-files -s vendor/aiup-marketplace` (path without the
`vendor/aiup-marketplace/` prefix) must equal the one in `git -C <clone> ls-tree -r <commit>`. Then update this
README, AGENTS.md §2 if a path moved, and run the gate (`python tools/lint_specs.py --strict`).
