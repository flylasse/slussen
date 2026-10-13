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

**Stage: AIUP Specification Bootstrapping.**  
The repository is currently mining the three-source baseline (code/tests, archived specs, and PR history) from `ynab-import-android` to establish the living `docs/` specification core before construction begins. See [`docs/bootstrap_aiup_spec_plan.md`](docs/bootstrap_aiup_spec_plan.md).
