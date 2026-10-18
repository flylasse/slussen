# Vision: Slussen

## Mission

Budget Apps such as YNAB have a common failure point: reliable connectivity to banking sources, especially when a
household has multiple, less common, or international financial institutions. No connectivity means incomplete
transaction data in the Budget App, and that means household budgeting fails.

Slussen's mission is to close this gap with a phone app the Operator can rely on when they are on the go. They can
always see at a glance whether the Budget App is up to date with each Source, and what to do if it is not, and
they can see all Account Balances in one place for easy reconciliation.

Slussen brings one household's Bank Transactions into its Budget App, from a phone, without manual entry and
without duplicates. A household may keep its money in several Budgets in different currencies, fed by US
cards and Bank Accounts, a health savings account, foreign Bank Accounts and credit cards. Without Slussen, the user may need to visit each banking website in order to download transactions and reconcile balances, may resort to importing data from awkward CSV exports, or bringing in data through import paths that post without review and may link a Transfer to the wrong Budget Account. 

Slussen fetches transactions and balances from the Operator's Banks and compares each transaction with what the
Budget App already holds. It shows the Operator, per transaction, whether it is new, already in the Budget App, a
match for an entry the Operator typed, or possibly brought in by an Other Import Path, and aggregates Account Balances in one place. It writes only after the Operator confirms, and only transactions not already in the Budget App. A transaction that matches an entry the Operator typed is sent too, so that the Budget App can pair the two instead of holding both. The whole Fetch, review and Upload cycle runs on the
phone.

Slussen is not a budgeting tool and does not categorise, plan or report. The Budget App stays the system of
record: Slussen adds transactions to it and otherwise leaves it alone. This build supports one Budget App, YNAB,
and two Data Providers, SimpleFIN and Enable Banking. Slussen is built so that the Operator can move to a
different Budget App, and add other Data Providers, later.

Slussen is the successor to a private Android app first built as "YNAB Import" (`ynab-import-android`) and
renamed Slussen while in use. This rebuild keeps that app's purpose and its hard-won safety rules. The rebuild
starts from a specification instead of from the code. Replacing the Predecessor App is a fresh start: Slussen does not
continue its history, identities or Import Identifiers.

## Target users

- **The Operator** is the one person who runs Slussen on their own phone and owns the household's Budgets in the
  Budget App. They need every Bank Transaction in the right Budget Account exactly once, with as little
  typing and checking as possible. They also need to see quickly when something needs their decision, such as a
  doubtful match, a Guessed Transfer, or a Bank that needs a new Bank Consent.
- **The Adopting Developer** is a skilled developer with a similar problem who clones the public repository and
  builds Slussen for their own household, or installs an app someone else built. They need to understand how
  Slussen works, how to set it up in the app with their own credentials and Bank Accounts, and how to add the Data
  Provider, File Format or Budget App they need, without changing Slussen's core rules. They then become the
  Operator of their own installation.
- Other household members' Bank Accounts may be imported, but those people do not use Slussen. Each installation is
  built and configured for one Operator.

## Goals

- **Exactly once.** Slussen creates zero duplicate transactions in the Budget App, even after a retry, timeout,
  crash, reinstall, or re-mapping of a Bank Account. It silently drops zero transactions it is responsible for: a
  transaction it cannot reliably identify or read, or one whose counterpart was changed in the Budget App, goes to
  the Operator for review instead of being merged or lost. A collision with a transaction created by an Other Import
  Path is shown to the Operator for a decision and is never resolved silently. Slussen keeps its own durable record
  of what it has sent, which survives a reinstall. When it cannot know whether a transaction arrived, it relies on
  the Budget App refusing a duplicate, or asks the Operator. The guarantee covers what Slussen sends: another
  importer feeding the same Budget Account can still create duplicates, which is why each Bank Account has one
  import path.
- **Nothing is written without confirmation.** Every write to the Budget App follows an explicit confirmation of an
  Upload Plan that states the Budget, the Budget Accounts, the number of transactions, and the total inflow and
  outflow. What is written is exactly what was confirmed: if the Budget App has changed since the Upload Plan was
  shown, or the Upload Plan is too old, nothing is written and the Operator reviews again. Fetching and comparing
  never write.
- **Never guess a Bank Account.** If Slussen cannot be certain which Bank Account a feed belongs to, it stops and
  asks the Operator. It never silently sends one Bank Account's transactions to another Bank Account.
- **Truthful outcomes, isolated failures.** Slussen tells the Operator truthfully what was and was not written,
  including when the outcome of an Upload is unknown, and never shows an unconfirmed write as done. A failure in
  one Source or Bank Account never blocks or corrupts another.
- **One import path per Bank Account.** Every Bank Account of the Operator that has a transaction feed or a file
  export is imported through Slussen, not typed in by hand and not fed by a second importer in parallel. A Bank
  Account no Source exposes stays a Manually Kept Account in the Budget App.
- **Transfers land linked.** A Transfer between two of the Operator's Bank Accounts in the same Budget, such as a
  Card Payment or a move between Bank Accounts, arrives in the Budget App as one linked transfer. The Operator
  then does not have to re-link it there.
- **Bank access is spent carefully.** Slussen contacts a Bank when the Operator starts a Fetch, or in a Scheduled
  Fetch on a schedule the Operator controls: on by default, at most twice a day, and the Operator can reduce it or
  turn it off. A Scheduled Fetch only fetches; it never uploads. Slussen never contacts a Bank it knows will refuse
  the request, such as one with an expired Bank Consent. It honours a Bank's or Data Provider's instruction to
  wait, for every Fetch, also when the Operator retries, it holds back repeat Fetches for a while after a
  successful one, and it keeps Unattended Accesses within the Banks' limit.
- **Shareability.** Slussen is developed as a public repository that holds no personal data, Bank information or
  credentials. An Adopting Developer can build Slussen from it, or install an app someone else built, and set up
  their own Data Providers, Budget App and Bank Accounts in the app, with a clear operating model for each, without
  editing Slussen's core rules. Slussen explains itself by example: the defaults it ships, such as
  its Operator Settings and Transfer Descriptions, are the ones its first Operator uses in practice, so a new user
  sees what works and how to adapt it.

## Scope

### In scope

- Fetching transactions and balances from the Operator's Banks through Data Providers. In this build these are
  SimpleFIN, which reads Bank Accounts at US Banks, and Enable Banking, European open banking, which needs the
  Operator's Bank Consent per Bank.
- Fetching on a schedule the Operator controls, at most twice a day, and signalling the new transactions it finds
  with one silent, low-priority New Transaction Notification that carries their count, shown as a badge on the app
  icon.
- Getting and renewing that Bank Consent from the phone: the Operator authenticates once per Bank with the Bank's
  own Strong Customer Authentication (for example BankID), which authorizes Slussen on the phone until the consent
  expires. Whenever the Operator opens the app, a Bank Consent that will expire soon is flagged together with the
  action that renews it.
- Importing a downloaded Transaction File for a Bank Account that has no usable transaction feed (for instance, a US
  health savings account whose transactions SimpleFIN does not provide), while its balance can still come from a
  Data Provider where one supports it. Slussen defines one Standard Import Format; for each Bank's export format, a
  developer writes one File Pre-processor that converts the Bank's file into it, and nothing else in Slussen assumes
  a particular Bank or format. In this build one Bank's export format is supported.
- Comparing each fetched transaction with what the Budget App already holds, including what was deleted or
  merged there. The result is a clear Transaction Status for each transaction.
- Letting the Operator resolve doubtful cases: confirm that a transaction is already present, import it anyway,
  defer it, or deliberately re-upload one that was deleted in the Budget App. Each decision can be changed until
  Upload.
- Uploading only on explicit confirmation of a reviewed Upload Plan, per Budget or per Budget Account.
- Uploading only transactions inside a bounded date window. Transactions from Transaction Files, which are imported
  less often than Data Providers are fetched, have one wider window of their own. A transaction that is too old or
  dated in the future is shown to the Operator as such and never disappears silently, including one that ages out
  while it waits for the Operator's review.
- Detecting Transfers between the Operator's own Bank Accounts in the same Budget and currency, and posting them as
  one linked transfer. This includes a Transfer whose other side has not arrived yet. Transfer detection is
  always on, and the Operator reviews Guessed Transfers.
- Connecting Budgets and Bank Accounts in the app: choosing the Budgets Slussen serves and pairing each Bank Account
  a Source offers with one Budget Account. Moving a mapping after transactions were sent through it is a deliberate
  act the Operator confirms, and a Bank Account can be retired and restored without losing its history. Each Bank
  Account can have a Start Date, set in the app, before which nothing is offered for Upload; such a transaction is
  shown with its reason, never dropped silently.
- Working with several Budgets in different currencies, each as its own workspace.
- Showing each Account Balance next to its Budget Account's balance in the Budget App, in one place, so the Operator can
  reconcile them.
- Showing the Source Health and Freshness of each Source and Bank Account, and the Blocked Reason of anything blocked, together with
  the action that unblocks it.
- Keeping a Decision Journal: what Slussen fetched, what it concluded, what the Operator overrode, and what later
  happened to each Import in the Budget App. The Operator can export it, together with the Budget App's current
  data, for review outside the app.
- Keeping the Operator's transaction history, decisions and the Sent Record across a reinstall, a restore from a
  device backup and an upgrade of the same build. Bank Consents and service credentials do not survive a reinstall:
  the Operator grants each Bank Consent and enters each credential again. After a restore, Slussen checks the Budget App before it uploads again.

### Out of scope

- Budgeting, categorising, payee or category management, and editing transactions in the Budget App. The one
  exception is marking the other side of a Transfer that Slussen itself created as Cleared.
- Writing to the Budget App without the Operator's review. This also excludes uploading automatically, in the
  background, or on a schedule.
- Notifications other than the count of new transactions from a Scheduled Fetch. The warning about an expiring
  Bank Consent appears only when the Operator opens the app.
- Continuing the Predecessor App's history, identities or Import Identifiers. A transaction that looks like one
  an Other Import Path already sent, including the Predecessor App, is held for the Operator's decision.
- Supporting more than one Budget App at a time, and any Budget App other than YNAB in this build. Moving to
  another Budget App later is a goal of the design (see Constraints), not a delivered feature.
- Data Providers other than SimpleFIN and Enable Banking, and File Formats other than the one above, in this
  build, as well as importing images or voice. Adding Data Providers and File Formats later is a goal of the
  design (see Constraints), not a delivered feature.
- Use by more than one Operator from one installation, public distribution of the built app through an app
  store, and any server or hosted component, including remote access and sync between devices.
- Transfers between Budgets or currencies, and any currency conversion. Such movements stay ordinary transactions
  on both sides.
- Correcting what other tools or the Budget App's own importer already wrote to it, such as a wrongly linked
  Transfer.
- Moving a Bank Account's already-uploaded history to a different Budget Account.
- Analysing the Decision Journal inside the app. Analysis happens on the exported file.

## Constraints

- **Android phone, on the device.** Slussen runs natively on the Operator's Android phone, so that the Operator
  can fetch, review and upload wherever they are. Everything Slussen stores stays on the device.
- **One Operator per installation.** Each installation serves one Operator, who enters their service credentials
  in the app, where they stay on the device, encrypted. A built app holds no credentials, so it may be installed by
  others; only an own release build needs signing values, which its builder keeps outside the repository and never
  commits. The design keeps its concerns separated, so that serving one Operator
  does not rule out later features such as several Operators.
- **Banking regulation (PSD2).** European Banks allow an application without the Bank Account holder present four
  accesses per Bank Account in any 24 hours, counted separately for transactions and balances. A Fetch the Operator
  starts is made with the holder present and is not under this limit. A Bank Consent lasts until the expiry date
  the Bank or Data Provider reports (EU rules currently allow up to 180 days, Delegated Regulation (EU) 2022/2360)
  and can only be renewed by the Bank Account holder through the Bank's Strong Customer Authentication.
- **External services in this build.** The Data Providers are SimpleFIN (US Banks) and Enable Banking
  (European open banking), and the Budget App is YNAB, through its API. Their rules are fixed facts that Slussen
  must work with, not change. In particular, YNAB matches an Import to a Typed Entry with the same Budget Account and
  amount within a date window, it never matches two Imports to each other, and it keeps an Import Identifier
  locked even after the transaction is deleted.
- **Extensible Data Providers and File Formats.** A Data Provider or File Format other than those in this build can
  be added without changing how Slussen compares, reviews and decides. A File Format is added as one File
  Pre-processor into Slussen's Standard Import Format. Each one states whether it gives every transaction a Stable
  Reference; transactions without one are reviewed, never guessed.
- **Replaceable Budget App.** The Budget App is one external destination. Slussen's rules are stated in its own
  terms, and everything that depends on one Budget App's behaviour is kept separate, so that another Budget App
  can replace YNAB without rewriting how Slussen fetches, compares, reviews and decides.
- **Proof against the real Budget App.** Any guarantee that depends on how YNAB behaves is shown by a test
  against a real, throwaway YNAB Budget before Uploads that rely on it are trusted.
- **The Operator sets the pace.** Every waiting time, window and limit that Slussen itself chooses can be changed
  by the Operator, and ships with a sensible default. Only limits set by others (the law, a Bank, a Data Provider
  or the Budget App) are fixed, and Slussen says where they come from.
- **Sensitive data.** By default, logs contain no transaction data or Bank Account numbers. The Operator can switch
  on Detailed Logging that includes them, for troubleshooting; those logs stay on the device. Credentials and
  Bank Sessions are never written to logs, even then. Neither logs, credentials nor Bank Sessions are ever included
  in device backups. The repository, including its specifications, tests
  and fixtures, contains no real Bank Account names, Bank Account numbers, transactions or credentials.
- **Specification first.** Slussen is developed with the AI Unified Process: the specification in `docs/` is the
  source of truth, and the Predecessor App is evidence of intent, not a template to copy.

## Success measures

- The Operator uses Slussen as the only import path for every Bank Account a Source exposes and stops entering those
  transactions by hand. The Predecessor App is retired from the phone.
- The Decision Journal shows no Import sent by Slussen that the Operator later had to delete in YNAB as a
  duplicate, and no transaction that went missing.
- The Decision Journal shows few Imports that the Operator later re-linked as a Transfer in YNAB, and that count
  falls over time.
- The tests against a throwaway YNAB Budget pass for matching, deletion, merging and Transfer behaviour before
  any Upload relies on that behaviour.
- Every time the Operator opens the app while a Bank Consent is about to expire, they are warned, and no Bank
  access is spent on a request that Slussen knew would fail.
- The public repository and every built app contain no personal data or credentials. For each Data Provider and
  the Budget App in this build (SimpleFIN, Enable Banking, YNAB), the app shows the Operator which credentials to
  enter and where to get them.
- Adding a Data Provider, a File Format or a Budget App touches only that part, not the rules for comparing,
  reviewing and deciding.
