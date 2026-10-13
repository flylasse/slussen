# Vision: Slussen

## Mission

Budget Apps such as YNAB have a common failure point: reliable connectivity to banking sources, especially when a
household has multiple, less common, or international financial institutions. No connectivity means incomplete
transaction data in the Budget App, and that means household budgeting fails.

Slussen's mission is to close this gap with a phone app the operator can rely on when they are on the go. They can
always be sure the Budget App is up to date with the latest transactions from all sources, and they can see all
bank balances in one place for easy reconciliation.

Slussen brings one household's bank transactions into its Budget App, from a phone, without manual entry and
without duplicates. A household may keep its money in several budgets in different currencies, fed by US
cards and bank accounts, a health savings account, foreign bank accounts and credit cards. Without Slussen, the user may need to visit each banking website in order to download transactions and reconcile balances, may resort to importing data from awkward CSV exports, or bringing in data through import paths that post without review and may link a transfer to the wrong account. 

Slussen fetches transactions and balances from the operator's banks and compares each transaction with what the
Budget App already holds. It shows the operator, per transaction, whether it is new, already in the Budget App, a
match for an entry the operator typed, or possibly brought in by another import path, and aggregates account balances in one place. It writes only after the operator confirms, and only the transactions that are new. The whole fetch, review and upload cycle runs on the
phone.

Slussen is not a budgeting tool and does not categorise, plan or report. The Budget App stays the system of
record: Slussen adds transactions to it and otherwise leaves it alone. This build supports one Budget App, YNAB,
and two Data Providers, SimpleFIN and Enable Banking. Slussen is built so that the operator can move to a
different Budget App, and add other Data Providers, later.

Slussen is the successor to a private Android app first built as "YNAB Import" (`ynab-import-android`) and
renamed Slussen while in use. This rebuild keeps that app's purpose and its hard-won safety rules. The rebuild
starts from a specification instead of from the code.

## Target users

- **The operator** is the one person who runs Slussen on their own phone and owns the household's budgets in the
  Budget App. They need every bank transaction in the right Budget App account exactly once, with as little
  typing and checking as possible. They also need to see quickly when something needs their decision, such as a
  doubtful match, a guessed transfer, or a bank that needs re-authorization.
- **The adopting developer** is a skilled developer with a similar problem who clones the public repository and
  builds Slussen for their own household. They need to understand how Slussen works and how to configure it with
  their own accounts and credentials, and how to add the Data Provider, file format or Budget App they need,
  without changing Slussen's core rules. They then become the operator of their own build.
- Other household members' accounts may be imported, but those people do not use Slussen. Each installation is
  built and configured for one operator.

## Goals

- **Exactly once.** Slussen creates zero duplicate transactions in the Budget App, even after a retry, timeout,
  crash, reinstall, or re-mapping of an account. It silently drops zero transactions it is responsible for. A
  collision with a transaction created by another import path is shown to the operator for a decision and is
  never resolved silently. Slussen owns this guarantee itself: it uses the Budget App's own duplicate protection
  where that exists, but does not depend on it.
- **Nothing is written without confirmation.** Every write to the Budget App follows an explicit confirmation of
  a plan that states the budget, the accounts, the number of transactions, and the total inflow and outflow.
  Fetching and comparing never write.
- **One import path per account.** Every operator account that has a transaction feed or a file export is
  imported through Slussen, not typed in by hand and not fed by a second importer in parallel. An account no
  source exposes stays a manually kept account in the Budget App.
- **Transfers land linked.** A transfer between two of the operator's accounts in the same budget, such as a
  card payment or a move between bank accounts, arrives in the Budget App as one linked transfer. The operator
  then does not have to re-link it there.
- **Bank access is spent carefully.** Slussen never contacts a bank it knows will refuse the request, such as one
  with an expired authorization. It stays inside the regulatory limit on unattended access to each account.
- **A clean takeover.** Replacing the predecessor app on the operator's phone creates no duplicates of
  transactions the predecessor already uploaded.
- **Shareability.** Slussen is developed as a public repository that holds no personal data, bank information or
  credentials. An adopting developer can build Slussen from it with their own accounts, Data Providers and
  Budget App configuration, and has a clear operating model for each, without editing Slussen's core rules.

## Scope

### In scope

- Fetching transactions and balances from the operator's banks through Data Providers. In this build these are
  SimpleFIN, a US account aggregator, and Enable Banking, European open banking, which needs the operator's
  consent per bank.
- Getting and renewing that bank consent from the phone: the operator authenticates once per bank with the
  bank's own Strong Customer Authentication (for example BankID), which authorizes Slussen on the phone until the
  consent expires, and is warned before a consent expires.
- Importing a downloaded transaction file for an account that has no usable transaction feed (for instance, a US
  health savings account whose transactions SimpleFIN does not provide), while its balance can still come from a
  Data Provider where one supports it. In this build one file format is supported.
- Comparing each fetched transaction with what the Budget App already holds, including what was deleted or
  merged there. The result is a clear status for each transaction.
- Letting the operator resolve doubtful cases: confirm that a transaction is already present, import it anyway,
  defer it, or deliberately re-upload one that was deleted in the Budget App. Each decision can be changed until
  upload.
- Uploading only on explicit confirmation of a reviewed plan, per budget or per account.
- Detecting transfers between the operator's own accounts in the same budget and currency, and posting them as
  one linked transfer. This includes a transfer whose other side has not arrived yet. The operator turns the
  feature on and off and reviews guessed pairings.
- Mapping each bank account to one budget and account in the Budget App. Once an account has uploaded, that
  mapping is locked, and an account can be retired and restored without losing its history.
- Working with several budgets in different currencies, each as its own workspace.
- Showing each account's bank balance next to its balance in the Budget App, in one place, so the operator can
  reconcile them.
- Showing the health and freshness of each source and account, and the reason anything is blocked, together with
  the action that unblocks it.
- Keeping a decision journal: what Slussen fetched, what it concluded, what the operator overrode, and what later
  happened to each import in the Budget App. The operator can export it, together with the Budget App's current
  data, for review outside the app.
- Keeping the operator's account identities and decisions across a reinstall of the app.

### Out of scope

- Budgeting, categorising, payee or category management, and editing transactions in the Budget App. The one
  exception is marking the other side of a transfer that Slussen itself created as cleared.
- Writing to the Budget App without the operator's review. This also excludes fetching or uploading
  automatically, in the background, or on a schedule, and notifications.
- Supporting more than one Budget App at a time, and any Budget App other than YNAB in this build. Moving to
  another Budget App later is a goal of the design (see Constraints), not a delivered feature.
- Data Providers other than SimpleFIN and Enable Banking, and file formats other than the one above, in this
  build, as well as importing images or voice. Adding Data Providers and file formats later is a goal of the
  design (see Constraints), not a delivered feature.
- Use by more than one operator from one installation, public distribution of the built app through an app
  store, and any server or hosted component, including remote access and sync between devices.
- Transfers between budgets or currencies, and any currency conversion. Such movements stay ordinary transactions
  on both sides.
- Correcting what other tools or the Budget App's own importer already wrote to it, such as a wrongly linked
  transfer.
- Adding a new bank account to Slussen from inside the app. The operator's accounts are part of the build's
  configuration.
- Moving an account's already-uploaded history to a different account in the Budget App.
- Analysing the decision journal inside the app. Analysis happens on the exported file.

## Constraints

- **Android phone, on the device.** Slussen runs natively on the operator's Android phone, so that the operator
  can fetch, review and upload wherever they are. Everything Slussen stores stays on the device.
- **Private build for one operator.** Each installation is built for one operator. That operator's accounts and
  service credentials come from configuration kept outside the repository and are included at build time. The
  built app is installed directly on the phone and must never be published, because the credentials can be
  extracted from it.
- **Banking regulation (PSD2).** European banks allow an application without the account holder present four
  accesses per account in 24 hours, counted separately for transactions and balances. A bank consent expires
  (about 90 days) and can only be renewed by the account holder through the bank's Strong Customer
  Authentication.
- **External services in this build.** The Data Providers are SimpleFIN (US aggregator) and Enable Banking
  (European open banking), and the Budget App is YNAB, through its API. Their rules are fixed facts that Slussen
  must work with, not change. In particular, YNAB matches an import to a typed entry with the same account and
  amount within a date window, it never matches two imports to each other, and it keeps an import identifier
  locked even after the transaction is deleted.
- **Extensible Data Providers and file formats.** A Data Provider or file format other than those in this build
  can be added without changing how Slussen compares, reviews and decides.
- **Replaceable Budget App.** The Budget App is one external destination. Slussen's rules are stated in its own
  terms, and everything that depends on one Budget App's behaviour is kept separate, so that another Budget App
  can replace YNAB without rewriting how Slussen fetches, compares, reviews and decides.
- **Proof against the real Budget App.** Any guarantee that depends on how YNAB behaves is shown by a test
  against a real, throwaway YNAB budget before uploads that rely on it are trusted.
- **Sensitive data.** Transaction data, account numbers, credentials and bank sessions are never written to logs,
  and bank sessions are never included in device backups. The repository, including its specifications, tests
  and fixtures, contains no real account names, account numbers, transactions or credentials.
- **Specification first.** Slussen is developed with the AI Unified Process: the specification in `docs/` is the
  source of truth, and the predecessor app is evidence of intent, not a template to copy.

## Success measures

- The operator uses Slussen as the only import path for every account a source exposes and stops entering those
  transactions by hand. The predecessor app is retired from the phone.
- The decision journal shows no import that the operator later had to delete in YNAB as a duplicate, and no
  transaction that went missing.
- The decision journal shows few imports that the operator later re-linked as a transfer in YNAB, and that count
  falls over time.
- The tests against a throwaway YNAB budget pass for matching, deletion, merging and transfer behaviour before
  any upload relies on that behaviour.
- No bank consent lapses without the operator being warned first, and no bank access is spent on a request that
  Slussen knew would fail.
- The public repository contains no personal data or credentials, and a fresh clone builds with example
  configuration only.
- Adding a Data Provider, a file format or a Budget App touches only that part and the build's configuration,
  not the rules for comparing, reviewing and deciding.
