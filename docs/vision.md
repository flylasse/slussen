# Vision: Slussen

## Mission

Budgeting apps such as YNAB have a common failure point: reliable connectivity to banking sources, especially when a user has multiple, less common, or international financial institutions.  No connectivity = incomplete transaction data in your budgeting app = failure to do household budgeting. 

Slussen's mission is to close this gap as a phone app the user can rely on when they are on the go, always be sure the budgeting app is up to date with latest transactions from all sources, and where they are able to see all bank balances in one place for easy reconciliation.

Slussen brings one household's bank transactions into the target budgeting app, from a phone, without manual entry and without duplicates. The operator keeps the household's money in several YNAB budgets in different currencies, fed by
US cards and bank accounts, a US health savings account, and Swedish bank accounts. Without Slussen, each transaction is typed in by hand, imported from awkward CSV exports, or brought in through import paths that post without review and may link a transfer to the wrong account.

Slussen fetches transactions and balances from the operator's banks and compares each transaction with what the
budgeting app already holds. It shows the operator, per transaction, whether it is new, already in the budgeting app, a match for an entry the operator typed, or possibly brought in by another import path. It writes only after the operator confirms, and only the transactions that are new. The whole fetch, review and upload cycle runs on the phone. 

Slussen is not a budgeting tool and does not categorise, plan or report. The budgeting app stays the system of
record: Slussen adds transactions to it and otherwise leaves it alone. Today that app is YNAB, and it is the only
one Slussen supports, but Slussen is built so that the operator can move to a different budgeting app later.

Slussen is the successor to the Android app first built as "YNAB Import" (repository `ynab-import-android`) and
renamed Slussen while in use. This rebuild keeps that app's purpose and its hard-won safety rules. The rebuild
starts from a specification instead of from the code.

## Target users

- **The operator** is the one person who runs Slussen on their own phone and owns the household's budgets in the
  budgeting app. They need every bank transaction in the right budget account exactly once, with as little typing and checking as
  possible. They also need to see quickly when something needs their decision, such as a doubtful match, a
  guessed transfer, or a bank that needs re-authorization.
- **There is no second user role.** Other household members' accounts are imported, but those people do not use
  Slussen. Nobody else installs it: each installation is built and configured for one operator.

## Goals

- **Exactly once.** Slussen creates zero duplicate transactions in the budgeting app, even after a retry,
  timeout, crash, reinstall, or re-mapping of an account. It silently drops zero transactions it is responsible
  for. A collision with a transaction created by another import path is shown to the operator for a decision
  and is never resolved silently. Slussen owns this guarantee itself: it uses the budgeting app's own duplicate
  protection where that exists, but does not depend on it.
- **Nothing is written without confirmation.** Every write to the budgeting app follows an explicit confirmation of a plan
  that states the budget, the accounts, the number of transactions, and the total inflow and outflow. Fetching
  and comparing never write.
- **One import path per account.** Every operator account that has a transaction feed or a file export is
  imported through Slussen, not typed in by hand and not fed by a second importer in parallel. An account no
  source exposes stays a manually kept account in the budgeting app.
- **Transfers land linked.** A transfer between two of the operator's accounts in the same budget, such as a
  card payment or a move between bank accounts, arrives in the budgeting app as one linked transfer. The
  operator then does not have to re-link it there.
- **Bank access is spent carefully.** Slussen never contacts a bank it knows will refuse the request, such as one
  with an expired authorization. It stays inside the regulatory limit on unattended access to each account.
- **A clean takeover.** Replacing the predecessor app on the operator's phone creates no duplicates of
  transactions the predecessor already uploaded.
- **Shareability** - Slussen should aim to become a public repo where no personal data, bank information, or credentials are stored. A skilled developer with a similar budget feed problem should be able to clone the repo, understand how to extend it to their budget app instance, their financial accounts, feed providers, and should have a clear operating model for each 

## Scope

### In scope

- Fetching transactions and balances from the operator's banks through Data Providers. The first is SimpleFIN, a US
  account aggregator. The second is Enable Banking, European open banking, which needs the operator's consent per bank.
- Getting and renewing that bank consent on the phone, with the bank's own identification app, and warning the
  operator before a consent expires, including support for European banks' Strong Customer Authentication.
- Importing a downloaded transaction file for an account that has no usable transaction feed (American HSA account transactions, for instance, are not supported by SimpleFin), while still allowing balance to still come from the online feed, if supported. 
- Comparing each fetched transaction with what the budgeting app already holds, including what was deleted or
  merged there. The result is a clear status for each transaction.
- Letting the operator resolve doubtful cases: confirm that a transaction is already present, import it anyway,
  defer it, or deliberately re-upload one that was deleted in the budgeting app. Each decision can be changed until upload.
- Uploading only on explicit confirmation of a reviewed plan, per budget or per account.
- Detecting transfers between the operator's own accounts in the same budget and currency, and posting them as
  one linked transfer. This includes a transfer whose other side has not arrived yet. The operator turns the
  feature on and off and reviews guessed pairings.
- Mapping each bank account to one budget and account in the budgeting app. Once an account has uploaded, that
  mapping is locked, and an account can be retired and restored without losing its history.
- Working with several budgets in different currencies, each as its own workspace.
- Showing the health and freshness of each source and account, and the reason anything is blocked, together with
  the action that unblocks it.
- Keeping a decision journal: what Slussen fetched, what it concluded, what the operator overrode, and what later
  happened to each import in the budgeting app. The operator can export it, together with the budgeting app's
  current data, for review outside the app.
- Keeping the operator's account identities and decisions across a reinstall of the app.

### Out of scope

- Budgeting, categorising, payee or category management, and editing transactions in the budgeting app. The one
  exception is marking the other side of a transfer that Slussen itself created as cleared.
- Writing to the budgeting app without the operator's review. This also excludes fetching or uploading
  automatically, in the background, or on a schedule, and notifications.
- Supporting more than one budgeting app at a time, and any budgeting app other than YNAB in this rebuild.
  Moving to another app later is a goal of the design (see Constraints), not a delivered feature.
- More bank sources than the two Data Providers and the one file format above, such as a further aggregator, or
  importing other file formats, images or voice
- Use by more than one operator from one installation, public distribution through an app store, and any server
  or hosted component, including remote access and sync between devices.
- Transfers between budgets or currencies, and any currency conversion. Such movements stay ordinary transactions
  on both sides.
- Correcting what other tools or the budgeting app's own importer already wrote to it, such as a wrongly linked
  transfer.
- Adding a new bank account to Slussen from inside the app. The operator's accounts are part of the build's
  configuration.
- Moving an account's already-uploaded history to a different account in the budgeting app.
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
- **Data Providers.** The Data Providers in this build are SimpleFIN (US aggregator) and Enable Banking (European open banking),
  and the destination in this build is the YNAB API. Their rules are fixed facts that Slussen must work with, not change. In
  particular, YNAB matches an import to a typed entry with the same account and amount within a date window, it
  never matches two imports to each other, and it keeps an import identifier locked even after the transaction
  is deleted.
- **Extensible Data Providers** Establish a software architecture that allows for adding Data Providers other than SimpleFIN or Enable Banking.
- **Replaceable destination.** The budgeting app is one external destination. Slussen's rules are stated in its
  own terms, and everything that depends on one budgeting app's behaviour is kept separate, so that another app
  can replace YNAB without rewriting how Slussen fetches, compares, reviews and decides.
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
