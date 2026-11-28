package slussen.core.status

import slussen.core.comparison.ComparisonNotices
import slussen.core.credential.CredentialStatus
import slussen.core.credential.CredentialStatusReader
import slussen.core.fetch.FetchNotices
import slussen.core.firstupload.FirstUploadMatching
import slussen.core.firstupload.FirstUploadMatchingNotice
import slussen.core.firstupload.FirstUploadMatchingStart
import slussen.core.journal.DecisionJournal
import slussen.core.port.BackupSizeMonitor
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ImportStatusStore
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettings
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ProviderRequestKind
import slussen.core.port.ProviderRequestStore
import slussen.core.port.ServiceKey
import slussen.core.port.StartState
import slussen.core.port.StoredAccountBalance
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBank
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredBudget
import slussen.core.port.StoredDataProvider
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFileFormat
import slussen.core.port.StoredProviderRequest
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransactionFile
import slussen.core.port.TransactionFileOutcome
import slussen.core.settings.NextThing
import slussen.pluginapi.value.Amount
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Check Import Status (UC-008), `:core` `status`, with First Upload Matching from `:core` `firstupload` (ADR-001 §7): built only from stored data, never from a
 * Source or the Budget App (BR-001, Konsist K-08).
 */
class ImportStatus(
    val budgetAppName: String,
    /** The Data Provider rows of this build as they are now, with the Operator Settings changed since the start. */
    private val currentDataProviders: () -> List<StoredDataProvider>,
    private val fileFormats: List<StoredFileFormat>,
    private val connections: ConnectionStore,
    private val stored: ImportStatusStore,
    private val providerRequests: ProviderRequestStore,
    private val credentials: CredentialStatusReader,
    private val firstUploadMatching: FirstUploadMatching,
    private val startState: StartState,
    private val backupSize: BackupSizeMonitor,
    private val settings: OperatorSettingsStore,
    private val journal: DecisionJournal,
    private val clock: Clock,
    /** A10, BR-011: the next thing to do in setting up Slussen (UC-013 BR-008), read from the Settings read model. */
    private val firstRunNext: suspend () -> NextThing? = { null },
    /** UC-001 A21: what the last Fetch of each Bank Account found that nothing stored can show. */
    private val fetchNotices: FetchNotices = FetchNotices(),
    /** UC-003 A1 step 2: the read of each Budget that failed since its last successful read. */
    private val comparisonNotices: ComparisonNotices = ComparisonNotices(),
    /** UC-003 BR-016: whether a Budget owes a read of its read period (`budget_read_state.period_read_owed`). */
    private val periodReadOwed: suspend (Long) -> Boolean = { false },
) {
    private val dataProviders: List<StoredDataProvider> get() = currentDataProviders()

    /** Everything the import status shows, read once from storage (BR-001: stored data only). */
    private class Snapshot(
        val now: Instant,
        val settings: OperatorSettings,
        val budgets: List<StoredBudget>,
        val banks: Map<Long, StoredBank>,
        val bankAccounts: List<StoredBankAccount>,
        val fetches: List<StoredFetch>,
        val balances: List<StoredAccountBalance>,
        val consents: List<StoredBankConsent>,
        val backOffs: List<StoredBackOff>,
        val files: List<StoredTransactionFile>,
        val counts: Map<Long, StoredTransactionCounts>,
        val requests: List<StoredProviderRequest>,
        val firstUploadMatching: FirstUploadMatchingNotice?,
        val credentials: Map<ServiceKey, CredentialStatus>,
        val periodReadOwed: Set<Long>,
    )

    // UC-008 BR-001: Opening Slussen shows only stored data; it never contacts a Source, a Bank or the Budget App.
    private suspend fun snapshot(): Snapshot {
        val now = clock.instant()
        val services = listOf(ServiceKey.budgetApp) + dataProviders.map { ServiceKey.dataProvider(it.id) }
        val budgets = connections.budgets().filter { it.served }
        return Snapshot(
            now = now,
            settings = settings.current(),
            budgets = budgets,
            banks = connections.banks().associateBy { it.id },
            bankAccounts = connections.bankAccounts().filter { !it.retired && it.mapping != null },
            fetches = stored.recentFetches().sortedByDescending { it.startedAt },
            balances = stored.accountBalances(),
            consents = stored.bankConsents(),
            backOffs = stored.backOffs(),
            files = stored.latestTransactionFiles(),
            counts = stored.transactionCounts().associateBy { it.bankAccountId },
            requests = providerRequests.sentSince(now.minus(LIMIT_WINDOW)),
            firstUploadMatching = firstUploadMatching.notice(),
            credentials = services.associateWith { credentials.status(it) },
            periodReadOwed = budgets.filter { periodReadOwed(it.id) }.map { it.id }.toSet(),
        )
    }

    /**
     * UC-001 BR-014: the number of the New Transaction Notification, worked out from stored data at every start and
     * after every operation ([NewTransactionNumber]); it reads no Source and records nothing (BR-001).
     */
    suspend fun newTransactionNumber(): Int =
        NewTransactionNumber.of(connections.bankAccounts(), connections.budgets(), stored.transactionCounts())

    /** Steps 1-3, A10: the Operator opens Slussen; the start of the session is recorded in the Decision Journal. */
    suspend fun open(): Overview {
        journal.record(JournalEventKind.SessionStart, "Slussen opened")
        return overview()
    }

    /** Step 1-2 again within the same session, for example when the Operator returns to the overview. */
    suspend fun overview(): Overview {
        val snapshot = snapshot()
        val summaries = snapshot.budgets.map { summary(it, snapshot) }
        // UC-008 BR-011: While no Bank Account is added, opening Slussen shows only the card that leads to Settings and names the next thing to do.
        val noBankAccountAdded = connections.bankAccounts().isEmpty()
        return Overview(
            unreadable = startState.unreadableParts.map { UnreadableNotice(it.part, it.startedFromLastGoodCopy) },
            firstUploadMatching = snapshot.firstUploadMatching,
            backupWarning = backupWarning(snapshot),
            expiringBankConsents = expiringConsents(snapshot),
            budgets = summaries,
            noBankAccountAdded = noBankAccountAdded,
            budgetAppName = budgetAppName,
            firstRun = if (noBankAccountAdded) FirstRunCard(firstRunNext()) else null,
            budgetAppRead = budgetAppRead(summaries),
            fetchSchedule = snapshot.settings.scheduledFetchesPerDay,
        )
    }

    /** FR-040, UC-003 A1 step 2: the latest successful read of any Budget, and the Budgets whose read failed since. */
    private fun budgetAppRead(summaries: List<BudgetSummary>): BudgetAppRead {
        val failed = summaries.mapNotNull { summary ->
            when (val asOf = summary.statusesAsOf) {
                StatusesAsOf.NothingToCompare -> null
                is StatusesAsOf.NotReadYet -> asOf.failedRead
                is StatusesAsOf.LastRead -> asOf.failedRead
            }?.let { summary.name to it.failedAt }
        }
        return BudgetAppRead(
            lastReadAt = summaries.filter { it.statusesAsOf is StatusesAsOf.LastRead }.mapNotNull { it.lastReadAt }.maxOrNull(),
            notRead = failed.map { it.first },
            failedAt = failed.maxOfOrNull { it.second },
        )
    }

    suspend fun workspace(budgetId: Long): BudgetWorkspace {
        val snapshot = snapshot()
        val budget = snapshot.budgets.single { it.id == budgetId }
        // UC-008 BR-007: A Budget is shown as its own workspace with only its own Sources and Bank Accounts.
        val accounts = accountsOf(budget, snapshot)
        return BudgetWorkspace(
            summary = summary(budget, snapshot),
            sources = sourcesOf(accounts, snapshot),
            bankAccounts = accounts.map { bankAccountStatus(it, budget, snapshot) },
            firstUploadMatching = snapshot.firstUploadMatching,
            budgetAppName = budgetAppName,
        )
    }

    /** The summary of the served Budget [budgetId] (step 1), or null when it is not served. */
    suspend fun summaryOf(budgetId: Long): BudgetSummary? {
        val snapshot = snapshot()
        return snapshot.budgets.firstOrNull { it.id == budgetId }?.let { summary(it, snapshot) }
    }

    /**
     * Steps 5-8 for the one Bank Account [bankAccountId] (`bank-account.html`): its Budget's summary, its status and the
     * name of its Source, or null when it is not mapped into a served Budget or is a Retired Account.
     */
    suspend fun bankAccountInBudget(bankAccountId: Long): BankAccountInBudget? {
        val snapshot = snapshot()
        val bankAccount = snapshot.bankAccounts.firstOrNull { it.id == bankAccountId } ?: return null
        val budget = snapshot.budgets.firstOrNull { budget -> budget.budgetAccounts.any { it.id == bankAccount.mapping?.budgetAccountId } }
            ?: return null
        val sourceName = bankAccount.dataProviderId?.let(::dataProviderName)
            ?: fileFormats.firstOrNull { it.id == bankAccount.fileFormatId }?.name
        return BankAccountInBudget(summary(budget, snapshot), bankAccountStatus(bankAccount, budget, snapshot), sourceName)
    }

    /**
     * UC-008 A5: the recent Fetches with, for each, what it recorded for each Bank Account and whether it still asks
     * something of the Operator now, read from stored data only (BR-001).
     */
    suspend fun recentFetches(): RecentFetches {
        val snapshot = snapshot()
        val everyBankAccount = connections.bankAccounts().associateBy { it.id }
        val rules = FetchHistoryRules(
            now = snapshot.now,
            fetches = snapshot.fetches,
            accounts = everyBankAccount,
            backOffs = snapshot.backOffs,
            sourceName = ::dataProviderName,
            credentialsAcceptedAt = { (snapshot.credentials[ServiceKey.dataProvider(it)] as? CredentialStatus.Accepted)?.acceptedAt },
            accountBlocked = { account -> blockedNow(account, snapshot) },
            // A stored Fetch of a Data Provider this build no longer has gives no Blocked Reason, and does not make the list unreadable.
            sourceBlocked = { providerId, last -> dataProviders.firstOrNull { it.id == providerId }?.let { sourceBlocked(it, last, snapshot) }.orEmpty() },
        )
        // UC-008 BR-008: Slussen shows the most recent Fetches, as many as the Operator Setting keeps, with Source, time, who started them and outcome.
        val kept = snapshot.settings.fetchHistorySize
        return RecentFetches(kept, snapshot.fetches.take(kept).map(rules::entry))
    }

    /** The Blocked Reasons of [bankAccount] as its Budget shows them, or null when it is not fetched now: a Retired Account, unmapped, or in a Budget that is not served. */
    private fun blockedNow(bankAccount: StoredBankAccount, snapshot: Snapshot): List<Blocked>? {
        val fetched = snapshot.bankAccounts.firstOrNull { it.id == bankAccount.id } ?: return null
        val budget = snapshot.budgets.firstOrNull { budget -> budget.budgetAccounts.any { it.id == fetched.mapping?.budgetAccountId } } ?: return null
        return bankAccountBlocked(fetched, budget, snapshot)
    }

    /** A9: the Operator chooses "Start First Upload Matching" (BR-006, BR-009). */
    suspend fun startFirstUploadMatching(): FirstUploadMatchingStart = firstUploadMatching.start()

    private fun accountsOf(budget: StoredBudget, snapshot: Snapshot): List<StoredBankAccount> {
        val accountIds = budget.budgetAccounts.map { it.id }.toSet()
        return snapshot.bankAccounts.filter { it.mapping?.budgetAccountId in accountIds }
    }

    private fun dataProviderName(id: Long): String = dataProviders.firstOrNull { it.id == id }?.name.orEmpty()

    private fun summary(budget: StoredBudget, snapshot: Snapshot): BudgetSummary {
        val accounts = accountsOf(budget, snapshot)
        val blocked = sourcesOf(accounts, snapshot).flatMap { it.blocked } +
            accounts.flatMap { bankAccountBlocked(it, budget, snapshot) } +
            budgetBlocked(budget, snapshot)
        return BudgetSummary(
            budgetId = budget.id,
            name = budget.name,
            currency = budget.currency,
            notYetUploaded = notYetUploaded(accounts, snapshot),
            blocked = blocked,
            lastReadAt = budget.lastReadAt,
            statusesAsOf = statusesAsOf(budget, accounts),
            notComparedYet = accounts.sumOf { snapshot.counts[it.id]?.notComparedYet ?: 0 },
            budgetAppCredentials = credentialBlocked(snapshot.credentials.getValue(ServiceKey.budgetApp), budgetAppName)?.reason,
            periodReadOwed = budget.id in snapshot.periodReadOwed,
        )
    }

    /**
     * UC-003 A1 step 2: the statuses of a Budget come from its last successful read, shown with its time and with the read
     * that failed since; a Budget without a Bank Account to compare is not read at all.
     */
    private fun statusesAsOf(budget: StoredBudget, accounts: List<StoredBankAccount>): StatusesAsOf {
        if (accounts.isEmpty()) return StatusesAsOf.NothingToCompare
        val failed = comparisonNotices.failedRead(budget.id)
        val readAt = budget.lastReadAt ?: return StatusesAsOf.NotReadYet(failed)
        return StatusesAsOf.LastRead(readAt, failed?.takeIf { it.failedAt.isAfter(readAt) })
    }

    private fun budgetBlocked(budget: StoredBudget, snapshot: Snapshot): List<Blocked> = buildList {
        // UC-008 BR-006: While First Upload Matching is open, each Budget shows "Uploads wait for First Upload Matching" with the action "Start First Upload Matching".
        snapshot.firstUploadMatching?.let { open ->
            add(Blocked(BlockedReason.UploadsWaitForFirstUploadMatching, Remedy.StartFirstUploadMatching, open.openedAt, budget.name))
        }
        credentialBlocked(snapshot.credentials.getValue(ServiceKey.budgetApp), budgetAppName)?.let { add(it) }
    }

    // UC-014 BR-005: Refused or missing credentials are shown as a Blocked Reason with the action to enter them.
    private fun credentialBlocked(status: CredentialStatus, subject: String): Blocked? = when (status) {
        CredentialStatus.Missing -> Blocked(BlockedReason.CredentialsMissing, Remedy.EnterCredentials, null, subject)
        CredentialStatus.Refused -> Blocked(BlockedReason.CredentialsRefused, Remedy.EnterCredentials, null, subject)
        is CredentialStatus.Accepted -> null
    }

    // UC-008 BR-003: Next to Source Health and Freshness, the Bank Transactions not yet uploaded: Eligible and waiting for a decision.
    private fun notYetUploaded(accounts: List<StoredBankAccount>, snapshot: Snapshot): NotYetUploaded {
        val counts = accounts.mapNotNull { snapshot.counts[it.id] }
        return NotYetUploaded(counts.sumOf { it.eligible }, counts.sumOf { it.waitingForDecision })
    }

    private fun sourcesOf(accounts: List<StoredBankAccount>, snapshot: Snapshot): List<SourceStatus> {
        // UC-001 BR-011: the Data Provider that reports a file-imported Bank Account's balance is one of the Budget's Sources too.
        val providerIds = (accounts.mapNotNull { it.dataProviderId } + accounts.mapNotNull { it.balanceDataProviderId }).distinct()
        val providerSources = providerIds.map { id ->
            val provider = dataProviders.single { it.id == id }
            val fetches = snapshot.fetches.filter { it.dataProviderId == id }
            val last = fetches.firstOrNull()
            SourceStatus(
                name = provider.name,
                isFileFormat = false,
                health = last?.let { SourceHealth.FetchHealth(it.outcome, it.startedAt, it.startedBy) } ?: SourceHealth.NoneYet,
                freshness = fetches.firstOrNull { it.outcome == FetchOutcome.Fetched }?.startedAt,
                notYetUploaded = notYetUploaded(accounts.filter { it.dataProviderId == id }, snapshot),
                blocked = sourceBlocked(provider, last, snapshot),
                balanceOnly = accounts.none { it.dataProviderId == id },
            )
        }
        val fileSources = accounts.mapNotNull { it.fileFormatId }.distinct().map { id ->
            val ofFormat = accounts.filter { it.fileFormatId == id }
            val last = snapshot.files.filter { file -> ofFormat.any { it.id == file.bankAccountId } }.maxByOrNull { it.importedAt }
            SourceStatus(
                name = fileFormats.single { it.id == id }.name,
                isFileFormat = true,
                health = last?.let(::fileHealth) ?: SourceHealth.NoneYet,
                // UC-002 BR-006: The Freshness of a file-imported Bank Account is measured from the time of the last imported Transaction File.
                freshness = snapshot.files.filter { file -> file.outcome == TransactionFileOutcome.Imported && ofFormat.any { it.id == file.bankAccountId } }
                    .maxOfOrNull { it.importedAt },
                notYetUploaded = notYetUploaded(ofFormat, snapshot),
                blocked = emptyList(),
                fileFormatId = id,
            )
        }
        return providerSources + fileSources
    }

    private fun sourceBlocked(provider: StoredDataProvider, last: StoredFetch?, snapshot: Snapshot): List<Blocked> = buildList {
        val status = snapshot.credentials.getValue(ServiceKey.dataProvider(provider.id))
        val acceptedAt = (status as? CredentialStatus.Accepted)?.acceptedAt
        credentialBlocked(status, provider.name)?.let { add(it) }
        if (last != null && last.outcome == FetchOutcome.CredentialsRefused && acceptedAt != null && acceptedAt.isBefore(last.startedAt)) {
            add(Blocked(BlockedReason.CredentialsRefused, Remedy.EnterCredentials, null, provider.name))
        }
        // UC-008 BR-006: Waiting for the provider is shown with the time the wait ends.
        snapshot.backOffs.filter { it.dataProviderId == provider.id && it.bankId == null && it.endsAt.isAfter(snapshot.now) }
            .maxByOrNull { it.endsAt }
            ?.let { add(Blocked(BlockedReason.WaitingForProvider, Remedy.WaitUntilItEnds, it.endsAt, provider.name)) }
        // UC-008 BR-006: A failed Fetch is shown with the time of the failure and the action that fetches again.
        if (last != null && last.outcome == FetchOutcome.Failed) {
            add(Blocked(BlockedReason.FailedFetch, Remedy.FetchAgain, last.startedAt, provider.name))
        }
        val sent = snapshot.requests.filter { it.dataProviderId == provider.id }.map { it.sentAt }
        // UC-008 BR-006: A reached Request Limit is shown with the time the limit frees up.
        freesUpAt(sent, provider.requestLimit)?.let {
            add(Blocked(BlockedReason.RequestLimitReached, Remedy.WaitUntilItEnds, it, provider.name))
        }
    }

    /** When fewer than [limit] of [sent] lie in the preceding 24 hours again, or null when fewer already do. */
    private fun freesUpAt(sent: List<Instant>, limit: Int): Instant? {
        if (sent.size < limit) return null
        return sent.sorted()[sent.size - limit].plus(LIMIT_WINDOW)
    }

    private fun bankAccountStatus(bankAccount: StoredBankAccount, budget: StoredBudget, snapshot: Snapshot): BankAccountStatus {
        val counts = snapshot.counts[bankAccount.id]
        return BankAccountStatus(
            bankAccountId = bankAccount.id,
            name = bankAccount.name,
            type = bankAccount.type,
            health = health(bankAccount, snapshot),
            freshness = freshness(bankAccount, snapshot),
            notYetUploaded = NotYetUploaded(counts?.eligible ?: 0, counts?.waitingForDecision ?: 0),
            balance = balance(bankAccount, budget, snapshot),
            blocked = bankAccountBlocked(bankAccount, budget, snapshot),
            dataProviderId = bankAccount.dataProviderId,
            // A21 of UC-001: the balance the last Fetch reported in another currency, named with both currencies.
            balanceInOtherCurrency = fetchNotices.balanceInOtherCurrency(bankAccount.id),
            notComparedYet = counts?.notComparedYet ?: 0,
            fileFormatId = bankAccount.fileFormatId,
        )
    }

    private fun lastResult(bankAccount: StoredBankAccount, snapshot: Snapshot) =
        snapshot.fetches.asSequence()
            .filter { it.dataProviderId == bankAccount.dataProviderId }
            .mapNotNull { fetch -> fetch.results.firstOrNull { it.bankAccountId == bankAccount.id }?.let { fetch to it } }
            .firstOrNull()

    // UC-008 BR-003: Source Health shows only the outcome of the last Fetch, or of the last Transaction File import.
    private fun health(bankAccount: StoredBankAccount, snapshot: Snapshot): SourceHealth {
        if (bankAccount.fileFormatId != null) {
            val file = snapshot.files.filter { it.bankAccountId == bankAccount.id }.maxByOrNull { it.importedAt }
            return file?.let(::fileHealth) ?: SourceHealth.NoneYet
        }
        val (fetch, result) = lastResult(bankAccount, snapshot) ?: return SourceHealth.NoneYet
        return SourceHealth.BankAccountFetchHealth(result.outcome, fetch.startedAt, fetch.startedBy)
    }

    // UC-002 BR-006: For a Bank Account whose transactions come from Transaction Files, the Source Health is the outcome of the last Transaction File import (imported or rejected with its reason) with its time.
    private fun fileHealth(file: StoredTransactionFile) =
        SourceHealth.FileImportHealth(file.outcome, file.importedAt, file.transactionCount, file.rejectionReason)

    // UC-008 BR-003: Freshness shows only how old the data is; old data never changes the Source Health.
    private fun freshness(bankAccount: StoredBankAccount, snapshot: Snapshot): Instant? {
        if (bankAccount.fileFormatId != null) {
            return snapshot.files.filter { it.bankAccountId == bankAccount.id && it.outcome == TransactionFileOutcome.Imported }
                .maxOfOrNull { it.importedAt }
        }
        return snapshot.fetches.firstOrNull { fetch ->
            fetch.dataProviderId == bankAccount.dataProviderId &&
                fetch.results.any { it.bankAccountId == bankAccount.id && it.outcome == FetchResultOutcome.Fetched }
        }?.startedAt
    }

    private fun balance(bankAccount: StoredBankAccount, budget: StoredBudget, snapshot: Snapshot): BalanceComparison {
        // UC-008 BR-006: A Bank Account whose currency differs from its Budget's shows no difference (A3).
        if (bankAccount.currency != budget.currency) return BalanceComparison.CurrencyMismatch(bankAccount.currency, budget.currency)
        val budgetAccount = budget.budgetAccounts.single { it.id == bankAccount.mapping?.budgetAccountId }
        val accountBalance = snapshot.balances.filter { it.bankAccountId == bankAccount.id }.maxByOrNull { it.reportedAt }
        val budgetBalance = budgetAccount.balance
        val budgetReadAt = budgetAccount.balanceReadAt
        if (accountBalance == null || budgetBalance == null || budgetReadAt == null) {
            return BalanceComparison.NotKnown(
                accountBalance != null, budgetBalance != null, accountBalance?.amount, budgetBalance,
                accountBalanceReadAt = accountBalance?.reportedAt,
                budgetAccountBalanceReadAt = budgetReadAt.takeIf { budgetBalance != null },
            )
        }
        val limit = Duration.ofHours(snapshot.settings.outdatedBalanceHours.toLong())
        // UC-008 BR-004: An Account Balance older than the outdated-balance limit hides the difference and is marked outdated.
        if (Duration.between(accountBalance.reportedAt, snapshot.now) > limit) {
            // A2 of UC-008 (UX-24): the remedy of an outdated Account Balance is a Fetch of the Data Provider that reports it.
            val reportedBy = bankAccount.dataProviderId ?: bankAccount.balanceDataProviderId
            return BalanceComparison.Outdated(
                accountBalance.amount, budgetBalance, accountBalance.reportedAt,
                budgetAccountBalanceReadAt = budgetReadAt,
                dataProviderName = reportedBy?.let(::dataProviderName),
            )
        }
        // UC-008 BR-005: The difference is the Account Balance minus the Budget Account's balance, with the time each was read.
        return BalanceComparison.Compared(
            accountBalance = accountBalance.amount,
            budgetAccountBalance = budgetBalance,
            difference = Amount(accountBalance.amount.thousandths - budgetBalance.thousandths),
            accountBalanceReadAt = accountBalance.reportedAt,
            budgetAccountBalanceReadAt = budgetReadAt,
        )
    }

    private fun bankAccountBlocked(bankAccount: StoredBankAccount, budget: StoredBudget, snapshot: Snapshot): List<Blocked> =
        buildList {
            val subject = bankAccount.name
            val provider = dataProviders.firstOrNull { it.id == bankAccount.dataProviderId }
            if (provider != null && provider.needsBankConsent) {
                val consent = snapshot.consents.firstOrNull { it.bankId == bankAccount.bankId && it.dataProviderId == provider.id }
                // UC-008 BR-006: A missing or expired Bank Consent is shown with the action that grants it (UC-007).
                when {
                    consent == null -> add(Blocked(BlockedReason.MissingBankConsent, Remedy.GrantBankConsent, null, subject))
                    !consent.expiresAt.isAfter(snapshot.now) ->
                        add(Blocked(BlockedReason.ExpiredBankConsent, Remedy.GrantBankConsent, consent.expiresAt, subject))
                }
            }
            if (provider != null) {
                snapshot.backOffs.filter { it.dataProviderId == provider.id && it.bankId == bankAccount.bankId && it.endsAt.isAfter(snapshot.now) }
                    .maxByOrNull { it.endsAt }
                    ?.let { add(Blocked(BlockedReason.WaitingForProvider, Remedy.WaitUntilItEnds, it.endsAt, subject)) }
                lastResult(bankAccount, snapshot)?.let { (fetch, result) ->
                    resultBlocked(result.outcome, fetch, bankAccount, provider, snapshot)?.let { add(it) }
                }
            }
            // UC-008 BR-006: A currency mismatch is shown with the action that moves the Account Mapping (UC-009 A8).
            if (bankAccount.currency != budget.currency) {
                add(Blocked(BlockedReason.CurrencyMismatch, Remedy.MoveAccountMapping, null, subject))
            }
        }

    private fun resultBlocked(
        outcome: FetchResultOutcome,
        fetch: StoredFetch,
        bankAccount: StoredBankAccount,
        provider: StoredDataProvider,
        snapshot: Snapshot,
    ): Blocked? = when (outcome) {
        // UC-008 BR-006: An unrecognised Bank Account is shown with the action that confirms or identifies it (UC-009).
        FetchResultOutcome.NotRecognised, FetchResultOutcome.AmbiguousBankAccount ->
            Blocked(BlockedReason.UnrecognisedBankAccount, Remedy.IdentifyBankAccount, fetch.startedAt, bankAccount.name)
        FetchResultOutcome.Failed -> Blocked(BlockedReason.FailedFetch, Remedy.FetchAgain, fetch.startedAt, bankAccount.name)
        // UC-008 BR-006: A reached access limit is shown with the time the limit frees up.
        FetchResultOutcome.AccessLimitReached -> Blocked(
            BlockedReason.AccessLimitReached,
            Remedy.WaitUntilItEnds,
            accessFreesUpAt(bankAccount, snapshot),
            bankAccount.name,
        )
        // UC-008 BR-006: Not delivered by the Data Provider is shown with the time of the Fetch and the action that fetches again (UC-001 A19).
        FetchResultOutcome.NotDelivered ->
            Blocked(BlockedReason.NotDelivered, Remedy.FetchAgain, fetch.startedAt, bankAccount.name, provider.name)
        // UC-008 BR-006: The Bank's sign-in at the Data Provider is shown with the action to sign in there again and fetch again (UC-001 A20).
        FetchResultOutcome.SignInNeeded ->
            Blocked(BlockedReason.SignInNeeded, Remedy.SignInAtDataProvider, null, bankAccount.name, provider.name)
        FetchResultOutcome.Fetched, FetchResultOutcome.NotConsented -> null
    }

    /** The time the Unattended Access limit of C-006 frees up again for every kind of read that reached it. */
    private fun accessFreesUpAt(bankAccount: StoredBankAccount, snapshot: Snapshot): Instant? =
        listOf(ProviderRequestKind.Transactions, ProviderRequestKind.Balance).mapNotNull { kind ->
            val sent = snapshot.requests.filter { it.bankAccountId == bankAccount.id && it.unattended && it.kind == kind }
            freesUpAt(sent.map { it.sentAt }, UNATTENDED_ACCESS_LIMIT)
        }.maxOrNull()

    private fun backupWarning(snapshot: Snapshot): BackupWarning? {
        val bytes = backupSize.backedUpBytes()
        val limit = backupSize.limitBytes
        // UC-008 BR-010: From the backup warning threshold of Android's backup limit, every opening shows the size and the limit.
        return if (bytes * 100 >= limit * snapshot.settings.backupWarningPercent) BackupWarning(bytes, limit) else null
    }

    private fun expiringConsents(snapshot: Snapshot): List<ExpiringBankConsent> {
        val horizon = snapshot.now.plus(Duration.ofDays(snapshot.settings.consentWarningDays.toLong()))
        // UC-008 BR-002: Every opening shows each Bank Consent whose expiry lies within the consent warning lead time.
        return snapshot.consents
            .filter { it.expiresAt.isAfter(snapshot.now) && !it.expiresAt.isAfter(horizon) }
            .sortedBy { it.expiresAt }
            .map { ExpiringBankConsent(snapshot.banks[it.bankId]?.name.orEmpty(), dataProviderName(it.dataProviderId), it.expiresAt) }
    }

    private companion object {
        /**
         * The preceding 24 hours over which every request and access limit is counted, the window the Data Providers'
         * own limits and PSD2 use (NFR-018, C-006); not a Slussen choice.
         */
        val LIMIT_WINDOW: Duration = Duration.ofHours(24)

        /** At most four accesses per Bank Account in any 24 hours without the holder present, per kind of read (C-006). */
        const val UNATTENDED_ACCESS_LIMIT = 4
    }
}
