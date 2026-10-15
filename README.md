# Slussen

Bank → YNAB transaction importer for Android.

Clean, spec-driven rebuild and successor to [`flylasse/ynab-import-android`](https://github.com/flylasse/ynab-import-android).

## Purpose

Slussen imports bank transactions onto an Android device, reviews and reconciles them against YNAB, and posts only genuinely-new transactions to YNAB—natively on the device that holds the bank-authentication apps European Strong Customer Authentication (SCA) requires.

## Process & Specification Core

This repository follows Simon Martinelli's **AI Unified Process (AIUP)** combined with the **Centaur Methodology** operating discipline:

- **The living specification in `docs/` is the authoritative source of truth.**
- Code and tests are strictly derived from approved use cases.
- Execution plans (`docs/archive/`) provide transient, phased scaffolding and are archived upon initiative completion; specifications outlive all plans.

### Status

**Stage: specification approved; construction not started.**  
The living specification in `docs/` (vision, requirements, glossary, entity model and use cases UC-001..UC-012, all Approved) was bootstrapped from the predecessor `ynab-import-android` with AIUP. The closed bootstrap plan, with its methodology evaluation log and what is owed to construction, is archived at [`docs/archive/bootstrap_aiup_spec_plan.md`](docs/archive/bootstrap_aiup_spec_plan.md).
