# Vision: Slussen

## Mission

Budget Apps such as YNAB have a common failure point: reliable connectivity to banking sources, especially when a
household has multiple, less common, or international financial institutions. No connectivity means incomplete
transaction data in the Budget App, and that means household budgeting fails.

Slussen's mission is to close this gap with a phone app the operator can rely on when they are on the go. They can
always see at a glance whether the Budget App is up to date with each source, and what to do if it is not, and
they can see all bank balances in one place for easy reconciliation.

Slussen brings one household's Bank Transactions into its Budget App, from a phone, without manual entry and
without duplicates. A household may keep its money in several budgets in different currencies, fed by US
cards and Bank Accounts, a health savings account, foreign Bank Accounts and credit cards. Without Slussen, the user may need to visit each banking website in order to download transactions and reconcile balances, may resort to importing data from awkward CSV exports, or bringing in data through import paths that post without review and may link a transfer to the wrong Budget Account. 

Slussen fetches transactions and balances from the operator's banks and compares each transaction with what the
Budget App already holds. It shows the operator, per transaction, whether it is new, already in the Budget App, a
match for an entry the operator typed, or possibly brought in by another import path, and aggregates Bank Account balances in one place. It writes only after the operator confirms, and only transactions not already in the Budget App. A transaction that matches an entry the operator typed is sent too, so that the Budget App can pair the two instead of holding both. The whole fetch, review and upload cycle runs on the
phone.

Slussen is not a budgeting tool and does not categorise, plan or report. The Budget App stays the system of
record: Slussen adds transactions to it and otherwise leaves it alone. This build supports one Budget App, YNAB,
and two Data Providers, SimpleFIN and Enable Banking. Slussen is built so that the operator can move to a
different Budget App, and add other Data Providers, later.

Slussen is the successor to a private Android app first built as "YNAB Import" (`ynab-import-android`) and
renamed Slussen while in use. This rebuild keeps that app's purpose and its hard-won safety rules. The rebuild
starts from a specification instead of from the code. Replacing the predecessor is a fresh start: Slussen does not
continue its history, identities or Import Identifiers.

## Target users

- **The operator** is the one person who runs Slussen on their own phone and owns the household's budgets in the
  Budget App. They need every Bank Transaction in the right Budget Account exactly once, with as little
  typing and checking as possible. They also need to see quickly when something needs their decision, such as a
  doubtful match, a Guessed Transfer, or a bank that needs re-authorization.
- **The Adopting Developer** is a skilled developer with a similar problem who clones the public repository and
  builds Slussen for their own household. They need to understand how Slussen works and how to configure it with
  their own Bank Accounts and credentials, and how to add the Data Provider, File Format or Budget App they need,
  without changing Slussen's core rules. They then become the operator of their own build.
- Other household members' Bank Accounts may be imported, but those people do not use Slussen. Each installation is
  built and configured for one operator.

## Goals

- **Exactly once.** Slussen creates zero duplicate transactions in the Budget App on its own, even after a retry,
  timeout, crash, reinstall, or re-mapping of a Bank Account. It silently drops zero transactions it is responsible
  for: a transaction it cannot reliably identify or cannot read, or one whose counterpart was changed in the Budget
  App, goes to the operator for review instead of being merged or lost. A collision with a transaction created by
  another import path is shown to the operator for a decision and is never resolved silently. Slussen keeps its own
  durable record of what it has sent, records each transaction there before sending it, and never sends again what
  the Budget App confirmed. One case it cannot settle alone: when the answer to an upload is lost, a request that
  never arrived and one that arrived look the same. Slussen then sends the transaction again on its own only to a
  Budget App that refuses a second transaction with the same Import Identifier, as YNAB does; with any other Budget
  App, the operator decides. After Slussen starts from an older copy of its data, such as its last good copy or a
  restored device backup, it uploads nothing until it has compared with the Budget App and the operator has
  confirmed, because what it sent after that copy was made is then protected only by the Budget App's refusal of a
  reused Import Identifier and by that Comparison. The guarantee covers what Slussen sends: another importer
  feeding the same Budget Account can still create duplicates, which is why each Bank Account has one import path.
- **Nothing is written without confirmation.** Every write to the Budget App follows an explicit confirmation of
  a plan that states the budget, the Budget Accounts, the number of transactions, and the total inflow and outflow.
  What is written is exactly what was confirmed: if the Budget App has changed since the plan was shown, or the
  plan is too old, nothing is written and the operator reviews again. Fetching and comparing never write.
- **Never guess a Bank Account.** If Slussen cannot be certain which Bank Account a feed belongs to, it stops and
  asks the operator. It never silently sends one Bank Account's transactions to another Bank Account.
- **Truthful outcomes, isolated failures.** Slussen tells the operator truthfully what was and was not written,
  including when the outcome of an upload is unknown, and never shows an unconfirmed write as done. A failure in
  one source or Bank Account never blocks or corrupts another.
- **One import path per Bank Account.** Every operator Bank Account that has a transaction feed or a file export is
  imported through Slussen, not typed in by hand and not fed by a second importer in parallel. A Bank Account no
  source exposes stays a Manually Kept Account in the Budget App.
- **Transfers land linked.** A transfer between two of the operator's Bank Accounts in the same budget, such as a
  Card Payment or a move between Bank Accounts, arrives in the Budget App as one linked transfer. The operator
  then does not have to re-link it there.
- **Bank access is spent carefully.** Slussen contacts a bank when the operator starts a fetch, or in a Scheduled
  Fetch on a schedule the operator controls: on by default, at most twice a day, and the operator can reduce it or
  turn it off. A Scheduled Fetch only fetches; it never uploads. Slussen never contacts a bank it knows will refuse
  the request, such as one with an expired authorization. It honours a bank's or Data Provider's instruction to
  wait, for every fetch, also when the operator retries, it holds back repeat fetches for a while after a
  successful one, and it keeps unattended accesses within the banks' limit.
- **Shareability.** Slussen is developed as a public repository that holds no personal data, bank information or
  credentials. An Adopting Developer can build Slussen from it with their own Bank Accounts, Data Providers and
  Budget App configuration, starting from the repository's Configuration Templates, and has a clear operating
  model for each, without editing Slussen's core rules. Slussen explains itself by example: the defaults it ships,
  such as its settings and Transfer Descriptions, are the ones its first operator uses in practice, so a new user
  sees what works and how to adapt it.

## Scope

### In scope

- Fetching transactions and balances from the operator's banks through Data Providers. In this build these are
  SimpleFIN, which reads Bank Accounts at US banks, and Enable Banking, European open banking, which needs the
  operator's consent per bank.
- Fetching on a schedule the operator controls, at most twice a day, and signalling the new transactions it finds
  with one silent, low-priority notification that carries their count, shown as a badge on the app icon.
- Getting and renewing that Bank Consent from the phone: the operator authenticates once per bank with the
  bank's own Strong Customer Authentication (for example BankID), which authorizes Slussen on the phone until the
  consent expires. Whenever the operator opens the app, a consent that will expire soon is flagged together with
  the action that renews it.
- Importing a downloaded Transaction File for a Bank Account that has no usable transaction feed (for instance, a US
  health savings account whose transactions SimpleFIN does not provide), while its balance can still come from a
  Data Provider where one supports it. Slussen defines one Standard Import Format; for each bank's export format,
  a developer writes one pre-processor that converts the bank's file into it, and nothing else in Slussen assumes a
  particular bank or format. In this build one bank's export format is supported.
- Comparing each fetched transaction with what the Budget App already holds, including what was deleted or
  merged there. The result is a clear status for each transaction.
- Letting the operator resolve doubtful cases: confirm that a transaction is already present, import it anyway,
  defer it, or deliberately re-upload one that was deleted in the Budget App. Each decision can be changed until
  upload.
- Uploading only on explicit confirmation of a reviewed plan, per budget or per Budget Account.
- Uploading only transactions inside a bounded date window. Transactions from Transaction Files, which are imported
  less often than Data Providers are fetched, have one wider window of their own. A transaction that is too old or
  dated in the future is shown to the operator as such and never disappears silently, including one that ages out
  while it waits for the operator's review.
- Detecting transfers between the operator's own Bank Accounts in the same budget and currency, and posting them as
  one linked transfer. This includes a transfer whose other side has not arrived yet. Transfer detection is
  always on, and the operator reviews guessed pairings.
- Mapping each Bank Account to one budget and Budget Account in the Budget App. Once a Bank Account has uploaded, that
  mapping is locked, and a Bank Account can be retired and restored without losing its history. Each Bank Account
  can have a Start Date in the build's configuration, before which nothing is offered for upload; such a
  transaction is shown with its reason, never dropped silently.
- Working with several budgets in different currencies, each as its own workspace.
- Showing each Bank Account's balance next to its Budget Account's balance in the Budget App, in one place, so the operator can
  reconcile them.
- Showing the health and freshness of each source and Bank Account, and the reason anything is blocked, together with
  the action that unblocks it.
- Keeping a Decision Journal: what Slussen fetched, what it concluded, what the operator overrode, and what later
  happened to each import in the Budget App. The operator can export it, together with the Budget App's current
  data, for review outside the app.
- Keeping the operator's transaction history, decisions and the record of what was sent across a reinstall, a
  restore from a device backup and an upgrade of the same build. Bank authorizations do not survive a reinstall:
  the operator authorizes each bank again. Slussen warns the operator when its backed-up data nears the size
  Android backs up, and after a restore it uploads nothing until the Comparison and confirmation described under
  Exactly once.

### Out of scope

- Budgeting, categorising, payee or category management, and editing transactions in the Budget App. The one
  exception is marking the other side of a transfer that Slussen itself created as cleared.
- Writing to the Budget App without the operator's review. This also excludes uploading automatically, in the
  background, or on a schedule.
- Notifications other than the count of new transactions from a Scheduled Fetch. The warning about an expiring
  Bank Consent appears only when the operator opens the app.
- Continuing the Predecessor App's history, identities or Import Identifiers. A transaction that looks like one
  another import path already sent, including the predecessor, is held for the operator's decision.
- Supporting more than one Budget App at a time, and any Budget App other than YNAB in this build. Moving to
  another Budget App later is a goal of the design (see Constraints), not a delivered feature.
- Data Providers other than SimpleFIN and Enable Banking, and File Formats other than the one above, in this
  build, as well as importing images or voice. Adding Data Providers and File Formats later is a goal of the
  design (see Constraints), not a delivered feature.
- Use by more than one operator from one installation, public distribution of the built app through an app
  store, and any server or hosted component, including remote access and sync between devices.
- Transfers between budgets or currencies, and any currency conversion. Such movements stay ordinary transactions
  on both sides.
- Correcting what other tools or the Budget App's own importer already wrote to it, such as a wrongly linked
  transfer.
- Adding a new Bank Account to Slussen from inside the app. The operator's Bank Accounts are part of the build's
  configuration.
- Moving a Bank Account's already-uploaded history to a different Budget Account.
- Analysing the Decision Journal inside the app. Analysis happens on the exported file.

## Constraints

- **Android phone, on the device.** Slussen runs natively on the operator's Android phone, so that the operator
  can fetch, review and upload wherever they are. Everything Slussen stores stays on the device.
- **Private build for one operator.** Each installation is built for one operator. That operator's Bank Accounts and
  service credentials come from configuration kept in a location the repository ignores and never committed, and are
  included at build time. The built app is installed directly on the phone and must never be published, because the credentials can be
  extracted from it. The design keeps its concerns separated, so that being built for one operator does not rule
  out later features such as several operators.
- **Banking regulation (PSD2).** European banks allow an application without the Bank Account holder present four
  accesses per Bank Account in any 24 hours, counted separately for transactions and balances. A fetch the operator
  starts is made with the holder present and is not under this limit. A Bank Consent lasts until the expiry date
  the bank or Data Provider reports (EU rules currently allow up to 180 days, Delegated Regulation (EU) 2022/2360)
  and can only be renewed by the Bank Account holder through the bank's Strong Customer Authentication.
- **External services in this build.** The Data Providers are SimpleFIN (US banks) and Enable Banking
  (European open banking), and the Budget App is YNAB, through its API. Their rules are fixed facts that Slussen
  must work with, not change. In particular, YNAB matches an import to a Typed Entry with the same Budget Account and
  amount within a date window, it never matches two imports to each other, and it keeps an Import Identifier
  locked even after the transaction is deleted.
- **Extensible Data Providers and File Formats.** A Data Provider or File Format other than those in this build
  can be added without changing how Slussen compares, reviews and decides. A File Format is added as one
  pre-processor into Slussen's Standard Import Format. Each one states whether it gives every transaction a Stable
  Reference; transactions without one are reviewed, never guessed.
- **Replaceable Budget App.** The Budget App is one external destination. Slussen's rules are stated in its own
  terms, and everything that depends on one Budget App's behaviour is kept separate, so that another Budget App
  can replace YNAB without rewriting how Slussen fetches, compares, reviews and decides.
- **Proof against the real Budget App.** Any guarantee that depends on how YNAB behaves is shown by a test
  against a real, throwaway YNAB budget before uploads that rely on it are trusted.
- **The operator sets the pace.** Every waiting time, window and limit that Slussen itself chooses can be changed
  by the operator, and ships with a sensible default. Only limits set by others (the law, a bank, a Data Provider
  or the Budget App) are fixed, and Slussen says where they come from.
- **Sensitive data.** By default, logs contain no transaction data or Bank Account numbers. The operator can switch
  on Detailed Logging that includes them, for troubleshooting; those logs stay on the device. Credentials and
  Bank Sessions are never written to logs, even then. Neither logs nor Bank Sessions are ever included in device
  backups. The repository, including its specifications, tests
  and fixtures, contains no real Bank Account names, Bank Account numbers, transactions or credentials.
- **Specification first.** Slussen is developed with the AI Unified Process: the specification in `docs/` is the
  source of truth, and the Predecessor App is evidence of intent, not a template to copy.

## Success measures

- The operator uses Slussen as the only import path for every Bank Account a source exposes and stops entering those
  transactions by hand. The Predecessor App is retired from the phone.
- The Decision Journal shows no import sent by Slussen that the operator later had to delete in YNAB as a
  duplicate, and no transaction that went missing.
- The Decision Journal shows few imports that the operator later re-linked as a transfer in YNAB, and that count
  falls over time.
- The tests against a throwaway YNAB budget pass for matching, deletion, merging and transfer behaviour before
  any upload relies on that behaviour.
- Every time the operator opens the app while a Bank Consent is about to expire, they are warned, and no bank
  access is spent on a request that Slussen knew would fail.
- The public repository contains no personal data or credentials. It provides a Configuration Template for each
  Data Provider and the Budget App in this build (SimpleFIN, Enable Banking, YNAB) that shows an Adopting
  Developer what to fill in and where to get it, without being a working configuration.
- Adding a Data Provider, a File Format or a Budget App touches only that part and the build's configuration,
  not the rules for comparing, reviewing and deciding.
