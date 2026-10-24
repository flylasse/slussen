# Slussen

Bank → YNAB transaction importer for Android.

Clean, spec-driven rebuild and successor to [`flylasse/ynab-import-android`](https://github.com/flylasse/ynab-import-android).

## Purpose

Slussen imports Bank Transactions onto an Android device, reviews and reconciles them against YNAB, and posts only genuinely-new transactions to YNAB—natively on the device that holds the bank-authentication apps European Strong Customer Authentication (SCA) requires.

## Process & Specification Core

This repository follows Simon Martinelli's **AI Unified Process (AIUP)** combined with the **Centaur Methodology** operating discipline:

- **The living specification in `docs/` is the authoritative source of truth.**
- Code and tests are strictly derived from approved use cases.
- Execution plans (`docs/archive/`) provide transient, phased scaffolding and are archived upon initiative completion; specifications outlive all plans.

### Status

**Stage: first installable version (0.1.1).** Setting up Slussen in the app works end to end: own release build, service credentials, Budgets, Bank Accounts with their switches and Account Mappings, a SimpleFIN Fetch that stores each Bank Account's balance, and the import status. Fetching and comparing Bank Transactions, uploading, and Enable Banking's Bank Consents come with later slices. The closed construction kickoff plan, with its methodology evaluation log, is archived at [`docs/archive/construction_kickoff_plan.md`](docs/archive/construction_kickoff_plan.md).  
The living specification in `docs/` (vision, requirements, glossary, entity model and use cases UC-001..UC-014) was bootstrapped from the predecessor `ynab-import-android` with AIUP. The closed bootstrap plan, with its methodology evaluation log and what is owed to construction, is archived at [`docs/archive/bootstrap_aiup_spec_plan.md`](docs/archive/bootstrap_aiup_spec_plan.md).

## Set Up Your Own Slussen

This how-to is for an Adopting Developer who wants Slussen for their own household ([UC-012](docs/use_cases/UC-012-set-up-an-own-build.md)). The repository holds no signing value and no credential, and a built app holds no credential either. Your service credentials are entered in the app ([UC-014](docs/use_cases/UC-014-manage-service-credentials.md)), and your Budgets and Bank Accounts are connected in the app ([UC-013](docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md)). There is no configuration file or template to fill in; the only thing that comes from outside the app is the signing values of an own release build.

Every value below is a placeholder. Never commit a real one.

### 1. Get Slussen

Choose one:

- **Clone** this repository, or **fork** it on GitHub and clone your fork. Either is safe, because the repository holds no signing value or credential. A fork of this public repository is itself public, so never commit anything personal to it.
- **Install an APK someone else built** from this repository. It holds none of their credentials, so you need no build at all: go to [5. Install](#5-install) and [6. First run in the app](#6-first-run-in-the-app). Updates must then come from the same builder, because Android installs an update over an installation only when it is signed with the same key.

To build, you need JDK 17 and an Android SDK with the build tools for `targetSdk` 35. Point `ANDROID_HOME`, or `sdk.dir` in the ignored `local.properties`, at the Android SDK; without it the build stops at configuration and says so. To install over USB you need `adb` and a phone with USB debugging switched on.

### 2. Release or debug: why an own release build is recommended

Keep your data in an **own release build**, signed with your own key ([UC-012 BR-009](docs/use_cases/UC-012-set-up-an-own-build.md#br-009-the-operators-data-lives-in-an-own-release-build)):

- Android installs an update over an installation only when it is signed with the same key. A debug key belongs to one machine; when it is lost, the only way to update is to uninstall, which deletes the app's local data, including its history, Sent Records and credentials.
- A debug build can be debugged, so its private data can be read through a cable connection.

A **debug build** needs nothing: no signing value, no keystore. It is for trying Slussen out. It installs as `slussen.app.debug`, beside a release installation (`slussen.app`), and never replaces it or its data.

### 3. Signing values of an own release build

Only an own release build needs signing values ([UC-012 BR-001](docs/use_cases/UC-012-set-up-an-own-build.md#br-001-signing-values-outside-the-repository), [ADR-003 §1](docs/adr/ADR-003-build-configuration.md)). Skip this step for a debug build.

**Create a keystore once, outside the repository**, for example with the JDK's `keytool` (every value is a placeholder):

```sh
keytool -genkeypair -v -keystore /path/to/your-release.jks -alias your-key-alias -keyalg RSA -validity 10000
```

`-validity` is in days; Android's own signing guide asks for a key valid for at least 25 years, since every update must be signed with it.

Keep a backup of the keystore and its passwords outside the phone and outside the repository. A lost keystore means a new installation, never an update in place ([ADR-005 §6](docs/adr/ADR-005-toolchain-and-version.md)).

**The four Gradle properties:**

| Gradle property | Holds |
|---|---|
| `slussenReleaseStoreFile` | the absolute path of your release keystore, outside the repository |
| `slussenReleaseStorePassword` | the password of the keystore |
| `slussenReleaseKeyAlias` | the alias of the signing key in the keystore |
| `slussenReleaseKeyPassword` | the password of the signing key |

**Where they go**, outside the repository, in one of Gradle's standard places:

- **Your user's `~/.gradle/gradle.properties`** (on Windows `%USERPROFILE%\.gradle\gradle.properties`, or `gradle.properties` in `GRADLE_USER_HOME` when you set it). This is the recommended place:

  ```properties
  slussenReleaseStoreFile=/path/to/your-release.jks
  slussenReleaseStorePassword=your-store-password
  slussenReleaseKeyAlias=your-key-alias
  slussenReleaseKeyPassword=your-key-password
  ```

  On Windows, write the path with forward slashes (`C:/path/to/your-release.jks`); a properties file reads a backslash as an escape.

- **Environment variables** named `ORG_GRADLE_PROJECT_<property>`, for a build machine that sets them per run:

  ```sh
  # macOS or Linux
  export ORG_GRADLE_PROJECT_slussenReleaseStoreFile=/path/to/your-release.jks
  export ORG_GRADLE_PROJECT_slussenReleaseStorePassword=your-store-password
  export ORG_GRADLE_PROJECT_slussenReleaseKeyAlias=your-key-alias
  export ORG_GRADLE_PROJECT_slussenReleaseKeyPassword=your-key-password
  ```

  ```powershell
  # Windows PowerShell
  $env:ORG_GRADLE_PROJECT_slussenReleaseStoreFile = "C:/path/to/your-release.jks"
  $env:ORG_GRADLE_PROJECT_slussenReleaseStorePassword = "your-store-password"
  $env:ORG_GRADLE_PROJECT_slussenReleaseKeyAlias = "your-key-alias"
  $env:ORG_GRADLE_PROJECT_slussenReleaseKeyPassword = "your-key-password"
  ```

Never put them in the repository's own `gradle.properties` or in any file or folder of the working tree. Passing them on the command line (`-P…`) works but is not recommended: the passwords end up in your shell history.

**A release build without them fails clearly** ([UC-012 BR-002](docs/use_cases/UC-012-set-up-an-own-build.md#br-002-a-release-build-without-its-signing-values-fails-clearly)). The task `verifyReleaseSigning` runs before every release package. When a property is missing or empty, it names each missing one and where it goes, never its value, and produces no app; it never signs with another key. It also refuses a `slussenReleaseStoreFile` that is not an absolute path, names no readable file, or lies inside the repository working tree. To check your setup without building, run `:app:verifyReleaseSigning` (see the commands below).

### 4. Build

Run from the repository root, with `gradlew.bat` on Windows and `./gradlew` on macOS or Linux:

| Build | Windows | macOS or Linux | The APK |
|---|---|---|---|
| Own release build | `gradlew.bat :app:assembleRelease` | `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| Debug build | `gradlew.bat :app:assembleDebug` | `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Check the signing values only | `gradlew.bat :app:verifyReleaseSigning` | `./gradlew :app:verifyReleaseSigning` | none |
| Tests, optional | `gradlew.bat check` | `./gradlew check` | none |

A release package holds native code for `arm64-v8a` only, so it installs on a phone with a 64-bit ARM processor; a debug package keeps every ABI, for example for an `x86_64` emulator ([ADR-005 §5](docs/adr/ADR-005-toolchain-and-version.md)). `check` needs no signing values. The version name comes from `VERSION`; a later version built with the same key installs over your installation and keeps its data.

### 5. Install

Slussen needs Android 8.0 (API 26) or later. With the phone connected and USB debugging on:

```sh
adb install app/build/outputs/apk/release/app-release.apk
```

Use `adb install -r` with the same APK path to install a newer version over an existing installation. For a debug build, use the debug APK path. For an APK someone else built, use its path, or copy it to the phone and open it there, allowing installs from that source when Android asks.

### 6. First run in the app

On its first start, Slussen opens on the Overview with one card, **Set up Slussen in Settings**, naming the next thing to do ([UC-008 BR-011](docs/use_cases/UC-008-check-import-status.md#br-011-first-opening-leads-to-settings)). Everything is set up in **Settings**, which lists at its top each service of this build and **Account Mappings**, each with its status; work down from the top ([UC-013 BR-008](docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md#br-008-first-run-order)). Everything can be changed again later in the same place.

**The services' credentials.** Open each service's entry in Settings. Its page states which credentials to enter and where to get them ([UC-014 BR-002](docs/use_cases/UC-014-manage-service-credentials.md#br-002-what-each-part-asks-for)). In this build:

| Service | What you enter | Where you get it |
|---|---|---|
| YNAB (the Budget App) | a personal access token; Slussen checks it by listing your Budgets | YNAB's developer settings |
| SimpleFIN (a Data Provider) | a setup token; Slussen exchanges it once for its access credential and then discards it | SimpleFIN Bridge, New connection |
| Enable Banking (a Data Provider) | the application id and the private key file of your household's own registered Enable Banking application; you choose the key file with the phone's file picker | your own application registered with Enable Banking: each household registers its own, as Enable Banking requires |

Slussen checks each entry with its service before it stores it, and stores it only on the phone, encrypted with a key that never leaves the phone. Credentials are never in a device backup: after a reinstall or on a new phone, you enter them again. A check of a Data Provider's credentials counts against its Request Limit. Leave a Data Provider your household does not use as it is; it stays listed as not set up.

**Budgets and Budget Accounts (YNAB's page).** Once YNAB's token is accepted, choose the Budgets Slussen serves. Below each Budget, its Budget Accounts each have a **Receives from Slussen** switch, on by default; switch off the ones you keep by hand, such as cash, so that they are never offered for pairing.

**Bank Accounts (each Data Provider's page).** Before a Source offers anything, an Enable Banking Bank needs a Bank Consent ([UC-007](docs/use_cases/UC-007-grant-bank-consent.md)), and SimpleFIN a first Fetch ([UC-001](docs/use_cases/UC-001-fetch-transactions.md)). Each Bank Account it offers has an **Import** switch, on by default; switch off the ones you do not want imported. Switching one on again brings it back. Nothing is fetched for a Bank Account until it is mapped.

**Account Mappings.** Available once a Budget is served and a Bank Account is imported ([UC-013 BR-015](docs/use_cases/UC-013-connect-budgets-and-bank-accounts.md#br-015-when-the-account-mappings-are-available)). It lists the Bank Accounts to map first; map them one at a time. For each, choose its Budget Account from the free ones of its currency (Slussen proposes one when it is the only one), accept or edit the proposed name, confirm or change the proposed Bank Account type and Bank, and optionally set a Start Date. Set a Start Date when another import path, such as the Predecessor App, already fed the Budget Account, so that Slussen offers nothing from before it ([UC-012 BR-007](docs/use_cases/UC-012-set-up-an-own-build.md#br-007-start-date-when-taking-over-from-another-import-path)). Transactions of a mapped Bank Account are fetched and compared from the next Fetch on.

**A Bank Account of Transaction Files.** Open the File Format's entry in Settings (in this build, the HSA CSV export) and add the Bank Account there: its Bank Account type, its Bank, the Data Provider that reports its balance, and its Budget Account, in one go.

You are now the Operator of this installation. When a phone with Slussen leaves your control, revoke every credential entered in it at its service and replace it before Slussen is used again ([UC-014 BR-006](docs/use_cases/UC-014-manage-service-credentials.md#br-006-rotate-exposed-credentials)).
