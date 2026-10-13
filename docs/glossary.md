# Glossary

| Term | Definition | Avoid |
|---|---|---|
| Operator | The one person who builds, configures and runs Slussen on their own phone and owns the household's budgets in the Budget App. Each installation has exactly one operator. | end user, client |
| Adopting Developer | A developer who clones the public repository and builds Slussen for their own household, and then becomes the operator of that build. | contributor, integrator |
| Budget App | The external budgeting application that is the system of record for the household's transactions and that Slussen writes to. This build supports one Budget App; it is replaceable by design. | destination, ledger, budgeting app, target |
| YNAB | The Budget App supported in this build. The name is used only where a rule depends on YNAB's own behaviour; everywhere else the generic term Budget App is used. | |
| Budget | One workspace in the Budget App with a single currency, holding Budget Accounts. The operator may have several budgets in different currencies. | |
| Budget Account | An account inside a Budget in the Budget App, which receives the transactions of exactly one Bank Account. | destination account, ledger account |
| Manually Kept Account | A Budget Account that no source exposes and that the operator maintains by hand in the Budget App; Slussen does not write to it. | |
| Data Provider | An external online service from which Slussen fetches transactions and balances of the operator's bank accounts. This build has two: SimpleFIN and Enable Banking. Data Providers are extensible by design. | feed provider, aggregator, bank API, source provider |
| File Format | A supported layout of a transaction file downloaded from a bank, which Slussen can import. Each File Format declares whether its rows carry a Stable Reference. This build supports one. | CSV format, export type |
| Source | Where a transaction comes from: a Data Provider or an imported Transaction File. | origin |
| Transaction File | A file of transactions that the operator downloaded from a bank and imports into Slussen for an account that has no usable transaction feed. | CSV, export file |
| Bank | A financial institution that holds one or more of the operator's Bank Accounts and is reached through a Data Provider or a Transaction File. | institution, ASPSP |
| Bank Account | One of the operator's (or a household member's) accounts at a Bank, as exposed by a Source. | source account, feed account |
| Account Mapping | The configured link from one Bank Account to one Budget Account. It is locked once a transaction has been uploaded through it. | binding, account link |
| Retired Account | A Bank Account whose Account Mapping the operator has switched off. It is neither fetched nor uploaded, keeps its identity and history, and can be restored. | deleted account, tombstone |
| Bank Consent | The bank's authorization, granted by the operator through the bank's Strong Customer Authentication, that lets Slussen read a Bank Account through a Data Provider until it expires. | authorization, consent token |
| Strong Customer Authentication | The bank's own identity check (for example BankID) that the account holder completes to grant or renew a Bank Consent. | SCA, login |
| Fetch | An operator-started read of transactions and balances from one Source. A fetch never writes to the Budget App. | sync, pull, refresh |
| Bank Transaction | One booked transaction of a Bank Account as delivered by a Source. | row, bank row |
| Stable Reference | An identifier a Source gives a Bank Transaction that stays the same every time the transaction is delivered. | transaction id |
| Typed Entry | A transaction the operator entered by hand in the Budget App. | manual entry, user-entered transaction, manual transaction |
| Import | A transaction in the Budget App that was created by an import path (Slussen or another importer) and carries that path's import identifier. | imported row |
| Other Import Path | Any importer other than Slussen that writes to the Budget App, including the Budget App's own bank import. | external importer, other tool |
| Comparison | Slussen's check of each Bank Transaction against what the Budget App already holds, including what was deleted or merged there, that gives the transaction its Status. | reconciliation, classification, verdict |
| Transaction Status | The result of the Comparison for one Bank Transaction, for example new, already in the Budget App, match for a Typed Entry, possible Import by another path, deleted in the Budget App, merged in the Budget App, deferred, too old, or in the future. | label |
| Eligible Transaction | A Bank Transaction that will be uploaded when the operator confirms the Upload Plan: a new transaction or a match for a Typed Entry, after the operator's decisions. | |
| Review Decision | The operator's choice for a doubtful Bank Transaction: already present, import anyway, defer, or re-upload one deleted in the Budget App. A Review Decision can be changed until upload. | resolution, override |
| Upload Plan | The reviewed statement of exactly what Slussen will write: Budget, Budget Accounts, number of transactions, total inflow and total outflow, and its expiry. | batch, plan of record |
| Upload | Slussen's write of the transactions of a confirmed Upload Plan to the Budget App. | push, post |
| Upload Window | The bounded date range inside which a Bank Transaction may be uploaded; transactions outside it are shown as too old or in the future. | age gate |
| Sent Record | Slussen's own durable record of every transaction it has sent to the Budget App, which survives a reinstall and is the basis of exactly-once. | upload log |
| Transfer | A movement of money between two of the operator's accounts in the same Budget and currency, such as a card payment or a move between bank accounts. | internal transfer, transfer pair |
| Transfer Leg | One of the two sides of a Transfer, in one Bank Account: the outflow side or the inflow side. | half |
| Lonely Transfer | A Transfer of which only one Transfer Leg has arrived from its Source so far. | one-sided transfer, orphan leg |
| Transfer Counterpart | The other side of a Transfer that the Budget App creates itself when Slussen uploads one Transfer Leg as a linked transfer. | mirror, auto side |
| Guessed Transfer | A Transfer that Slussen proposes but cannot pair unambiguously, which needs the operator's confirmation. | suspect, uncertain pair |
| Account Balance | The balance of a Bank Account as reported by its Source, shown next to the Budget Account's balance in the Budget App. | |
| Source Health | The outcome of the last Fetch of a Source or Bank Account, shown separately from how old its data is. | status dot |
| Freshness | How old the data from a Source is, measured from the time of the last successful Fetch. | staleness |
| Blocked Reason | The reason Slussen cannot fetch from or upload to an account, shown together with the action that removes it. | error state |
| Back-off | A period during which Slussen does not contact a Data Provider or Bank because the provider asked it to wait or a transient failure occurred. | throttling |
| Fetch Cooldown | A period after a successful Fetch of a Source during which a new Fetch of that Source is served from what was already fetched, without contacting the Source. | |
| Decision Journal | Slussen's on-device record of what it fetched, what it concluded, what the operator overrode, and what later happened to each Import in the Budget App. | audit log |
| Journal Export | A file the operator saves outside the app that holds the Decision Journal together with the Budget App's current transactions, for review outside the app. | dump |
| Detailed Logging | An operator-switched mode in which diagnostic logs on the device may contain transaction data and account numbers for troubleshooting. Credentials and bank sessions are never logged, even then. | debug mode, verbose logging |
| Configuration Template | A file in the repository that shows an adopting developer which accounts, credentials and settings a build needs and where to get them, without holding real values. | sample config, example config |
| Build Configuration | The operator's own accounts, Account Mappings and service credentials, kept outside the repository and included in the app when it is built. | secrets file, local config |
| Predecessor App | The private Android app that Slussen replaces on the operator's phone. | old app, legacy app |
