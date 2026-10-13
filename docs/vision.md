# Vision: Slussen

## Mission

Slussen brings one household's bank transactions into YNAB, from a phone, without manual entry and without
duplicates. The operator keeps the household's money in several YNAB budgets in different currencies, fed by
US cards and bank accounts, a US health savings account, and Swedish bank accounts. Without Slussen, each
transaction is typed in by hand, or comes in through import paths that post without review and often link a
transfer to the wrong account.

Slussen fetches transactions and balances from the operator's banks and compares each transaction with what YNAB
already holds. It shows the operator, per transaction, whether it is new, already in YNAB, a match for an entry
the operator typed, or possibly brought in by another import path. It writes only after the operator confirms,
and only the transactions that are new. The whole fetch, review and upload cycle runs on the phone, because that
is where the bank identification apps that European banks require for consent (Strong Customer Authentication)
live.

Slussen is not a budgeting tool and does not categorise, plan or report. YNAB stays the system of record: Slussen
adds transactions to it and otherwise leaves it alone.

Slussen is the successor to the Android app first built as "YNAB Import" (repository `ynab-import-android`) and
renamed Slussen while in use. This rebuild keeps that app's purpose and its hard-won safety rules. The rebuild
starts from a specification instead of from the code.

## Target users

- **The operator** is the one person who runs Slussen on their own phone and owns the household's YNAB budgets.
  They need every bank transaction in the right YNAB account exactly once, with as little typing and checking as
  possible. They also need to see quickly when something needs their decision, such as a doubtful match, a
  guessed transfer, or a bank that needs re-authorization.
- **There is no second user role.** Other household members' accounts are imported, but those people do not use
  Slussen. Nobody else installs it: each installation is built and configured for one operator.

## Goals

- **Exactly once.** Slussen creates zero duplicate transactions in YNAB, even after a retry, timeout, crash,
  reinstall, or re-mapping of an account. It silently drops zero transactions it is responsible for. A
  collision with a transaction created by another import path is shown to the operator for a decision and is
  never resolved silently.
- **Nothing is written without confirmation.** Every write to YNAB follows an explicit confirmation of a plan
  that states the budget, the accounts, the number of transactions, and the total inflow and outflow. Fetching
  and comparing never write.
- **One import path per account.** Every operator account that has a transaction feed or a file export is
  imported through Slussen, not typed in by hand and not fed by a second importer in parallel. An account no
  source exposes stays a manual YNAB account.
- **Transfers land linked.** A transfer between two of the operator's accounts in the same budget, such as a
  card payment or a move between bank accounts, arrives in YNAB as one linked transfer. The operator then does
  not have to re-link it in YNAB.
- **Bank access is spent carefully.** Slussen never contacts a bank it knows will refuse the request, such as one
  with an expired authorization. It stays inside the regulatory limit on unattended access to each account.
- **A clean takeover.** Replacing the predecessor app on the operator's phone creates no duplicates of
  transactions the predecessor already uploaded.

## Scope

### In scope

- Fetching transactions and balances from the operator's banks through two online sources. The first is a US
  account aggregator. The second is European open banking, which needs the operator's consent per bank.
- Getting and renewing that bank consent on the phone, with the bank's own identification app, and warning the
  operator before a consent expires.
- Importing a downloaded transaction file for an account that has no usable transaction feed (today, the
  health savings account), while its balance still comes from the online feed.
- Comparing each fetched transaction with what YNAB already holds, including what was deleted or merged in YNAB.
  The result is a clear status for each transaction.
- Letting the operator resolve doubtful cases: confirm that a transaction is already present, import it anyway,
  defer it, or deliberately re-upload one that was deleted in YNAB. Each decision can be changed until upload.
- Uploading only on explicit confirmation of a reviewed plan, per budget or per account.
- Detecting transfers between the operator's own accounts in the same budget and currency, and posting them as
  one linked transfer. This includes a transfer whose other side has not arrived yet. The operator turns the
  feature on and off and reviews guessed pairings.
- Mapping each bank account to one YNAB budget and account. Once an account has uploaded, that mapping is
  locked, and an account can be retired and restored without losing its history.
- Working with several YNAB budgets in different currencies, each as its own workspace.
- Showing the health and freshness of each source and account, and the reason anything is blocked, together with
  the action that unblocks it.
- Keeping a decision journal: what Slussen fetched, what it concluded, what the operator overrode, and what later
  happened to each import in YNAB. The operator can export it, together with YNAB's current data, for review
  outside the app.
- Keeping the operator's account identities and decisions across a reinstall of the app.

### Out of scope

- Budgeting, categorising, payee or category management, and editing transactions in YNAB. The one exception is
  marking the other side of a transfer that Slussen itself created as cleared.
- Writing to YNAB without the operator's review. This also excludes fetching or uploading automatically, in the
  background, or on a schedule, and notifications.
- Budgeting applications other than YNAB.
- More bank sources than the two online sources and the one file format above, such as a further aggregator, or
  importing other file formats, images or voice.
- Use by more than one operator from one installation, public distribution through an app store, and any server
  or hosted component, including remote access and sync between devices.
- Transfers between budgets or currencies, and any currency conversion. Such movements stay ordinary transactions
  on both sides.
- Correcting what other tools or YNAB's own importer already wrote to YNAB, such as a wrongly linked transfer.
- Adding a new bank account to Slussen from inside the app. The operator's accounts are part of the build's
  configuration.
- Moving an account's already-uploaded history to a different YNAB account.
- Analysing the decision journal inside the app. Analysis happens on the exported file.

## Constraints

- **Android phone, on the device.** Slussen runs natively on the operator's Android phone, because bank consent
  in Europe needs the bank's identification app on that same device. Everything Slussen stores stays on the
  device.
- **Private build for one operator.** Each installation is built for one operator, with that operator's
  accounts and service credentials included at build time. It is installed directly on the phone and must never
  be published, because the credentials can be extracted from the installed app.
- **Banking regulation (PSD2).** European banks allow an application without the account holder present four
  accesses per account in 24 hours, counted separately for transactions and balances. A bank consent expires
  (about 90 days) and can only be renewed by the account holder with the bank's identification app.
- **External services.** The sources are SimpleFIN (US aggregator) and Enable Banking (European open banking),
  and the destination is the YNAB API. Their rules are fixed facts that Slussen must work with, not change. In
  particular, YNAB matches an import to a typed entry with the same account and amount within a date window, it
  never matches two imports to each other, and it keeps an import identifier locked even after the transaction
  is deleted.
- **Proof against the real destination.** Any guarantee that depends on how YNAB behaves is shown by a test
  against a real, throwaway YNAB budget before uploads that rely on it are trusted.
- **Sensitive data.** Transaction data, account numbers, credentials and bank sessions are never written to logs,
  and bank sessions are never included in device backups.
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
