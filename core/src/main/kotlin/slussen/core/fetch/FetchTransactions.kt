package slussen.core.fetch

import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.identity.BankAccountFingerprint
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredDataProvider
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountDelivery
import slussen.core.port.DeliveredTransaction
import slussen.core.port.DeliveredTransactions
import slussen.core.port.DeliveryStored
import slussen.core.port.JournalEventKind
import slussen.core.port.NewUnreadableTransaction
import slussen.core.port.Reception
import slussen.core.port.TransactionField
import slussen.core.port.UnreadableTransactionReason
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.FetchStore
import slussen.core.port.NewBackOff
import slussen.core.port.NewAccountBalance
import slussen.core.port.NewFetch
import slussen.core.port.NewOfferedBankAccount
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredDataProvider
import slussen.core.request.requestLimitFreesUpAt
import slussen.core.request.requestLimitWarningAt
import slussen.core.request.requestToWaitUntil
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankAccountToRead
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ProviderTransaction
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.provider.UnreadableReason
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Fetch Transactions (UC-001), `:core` `fetch` (ADR-001 §7): the request gate (Back-off, Fetch Cooldown, Request Limit;
 * A1-A3, A16), the Fetch with the Operator present of the transactions and balance of every selected Bank Account
 * (step 4), the Offered Bank Accounts with their balance (step 6), the identification of the Bank Accounts the Operator
 * added (step 6, A10, A18, BR-011), the booked, non-zero Bank Transactions and the Unreadable Transactions of each
 * (steps 7-8, A17, BR-002, BR-003, BR-016), its Account Balance in its own currency (step 8, A21, BR-011), the Decision
 * Journal (step 9), the Back-offs a Data Provider asks for (A5, A15), and the Fetch record with its outcome and the Fetch
 * Result of each Bank Account it selected (step 11, A2, A6-A8, A19, A20). Step 10 (the Comparison) and the Scheduled
 * Fetch (A12) come with later slices.
 */
class FetchTransactions(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val connections: ConnectionStore,
    private val offered: OfferedBankAccountStore,
    private val fetches: FetchStore,
    private val providerRequests: ProviderRequestStore,
    private val coordinator: OperationCoordinator,
    private val settings: OperatorSettingsStore,
    private val journal: DecisionJournal,
    private val clock: Clock,
    /** UC-001 A21: the balances reported in another currency, which the import status shows (UC-008). */
    private val notices: FetchNotices = FetchNotices(),
) {
    /** A Fetch Cooldown that holds: from the last successful Fetch at [lastFetchAt] to [endsAt]. */
    private data class Cooldown(val lastFetchAt: Instant, val endsAt: Instant)

    /** A Bank Account selected at step 3 with what to read of it. */
    private data class Selected(val bankAccount: StoredBankAccount, val read: BankAccountToRead)

    // UC-001 BR-001: Slussen contacts a Data Provider only in a Fetch the Operator starts, never when the app is opened.
    /** Step 1: the Operator starts a Fetch of the Data Provider [service]; [confirmedDespiteWarning] answers A16. */
    suspend fun fetch(service: ServiceKey, confirmedDespiteWarning: Boolean = false): FetchStart =
        admitted { permit -> listOf(fetchOne(plugins.dataProvider(service), permit, confirmedDespiteWarning)) }

    /** A11: the Operator starts a Fetch of all Data Providers in use, each in turn under its own gate (BR-010). */
    suspend fun fetchAll(): FetchStart = admitted { permit ->
        val bankAccounts = connections.bankAccounts()
        plugins.dataProviders.filter { inUse(it, bankAccounts) }.map { provider ->
            // UC-001 BR-010: A failure, a Back-off, a Fetch Cooldown or a reached Request Limit of one Data Provider leaves the Fetch of every other Data Provider unaffected.
            fetchOne(provider, permit, confirmedDespiteWarning = false)
        }
    }

    private suspend fun admitted(block: suspend (OperationPermit) -> List<FetchReport>): FetchStart =
        // UC-006 BR-005: While an Upload Plan awaits confirmation or an Upload is written, no Fetch the Operator starts runs.
        when (val result = coordinator.run(OperationKind.Fetch, StartedBy.Operator, block)) {
            is OperationResult.Ran -> FetchStart.Ran(result.value)
            is OperationResult.Refused -> FetchStart.Unavailable(result.reason)
        }

    /** UC-014 BR-007: a Data Provider is in use when its credentials are entered or it has a Bank Account. */
    private suspend fun inUse(provider: RegisteredDataProvider, bankAccounts: List<StoredBankAccount>): Boolean =
        credentials.status(provider.key) != CredentialStatus.Missing ||
            bankAccounts.any { it.dataProviderId == provider.stored.id || it.balanceDataProviderId == provider.stored.id }

    private suspend fun fetchOne(provider: RegisteredDataProvider, permit: OperationPermit, confirmedDespiteWarning: Boolean): FetchReport {
        val key = provider.key
        val stored = provider.stored
        val name = stored.name
        // P9-16, ADR-007 §1: the screens show which Data Provider the running Fetch reads now.
        permit.workingOn(name)
        // UC-014 BR-005: Without entered credentials the Data Provider is not contacted; credentials missing is shown.
        val credential = credentials.stored(key) ?: return FetchReport.CredentialsMissing(key, name)
        // UC-014 BR-005: A Data Provider that refused Slussen's credentials is not contacted until new ones are accepted.
        if (refusedCredentials(provider)) return FetchReport.CredentialsRefused(key, name)
        // UC-001 BR-008: No Bank is contacted without a valid Bank Consent; the Bank Sessions come with Grant Bank Consent (UC-007).
        if (stored.needsBankConsent) return FetchReport.NeedsBankConsent(key, name)
        val startedAt = clock.instant()
        val backOffs = fetches.backOffsOf(stored.id)
        // UC-001 BR-004: While a Back-off with cause Request To Wait holds, the Data Provider is not contacted, whoever starts the Fetch.
        backOffs.requestToWaitUntil(startedAt)?.let { until ->
            record(stored, startedAt, FetchOutcome.WaitingForBackOff)
            return FetchReport.WaitingForProvider(key, name, until)
        }
        val selected = selected(stored)
        val cooldown = cooldownOf(stored, startedAt)
        val toRead = if (cooldown == null) selected else notReadSinceIdentified(stored, selected)
        if (cooldown != null && toRead.isEmpty()) {
            record(stored, startedAt, FetchOutcome.ServedFromFetchCooldown)
            return FetchReport.ServedFromFetchCooldown(key, name, cooldown.lastFetchAt, cooldown.endsAt, read = null, requestLimitFreesUpAt = null)
        }
        // UC-001 BR-007: A Data Provider gets at most its Request Limit of requests in the preceding 24 hours, also within the Fetch Cooldown.
        requestLimitFreesUpAt(providerRequests, stored, startedAt)?.let { freesUpAt ->
            if (cooldown != null) {
                record(stored, startedAt, FetchOutcome.ServedFromFetchCooldown)
                return FetchReport.ServedFromFetchCooldown(key, name, cooldown.lastFetchAt, cooldown.endsAt, read = null, requestLimitFreesUpAt = freesUpAt)
            }
            record(stored, startedAt, FetchOutcome.RequestLimitReached)
            return FetchReport.RequestLimitReached(key, name, freesUpAt)
        }
        // UC-001 BR-015: During a Request Limit warning a Fetch the Operator starts is made only after the Operator has seen the warning and confirmed.
        val warning = backOffs.requestLimitWarningAt(startedAt)
        if (warning != null && !confirmedDespiteWarning) {
            return FetchReport.WarningToConfirm(key, name, RequestLimitWarningShown(warning.startsAt, warning.endsAt))
        }
        val response = provider.plugin.fetch(request(toRead, startedAt), credentials.context(key, permit, credential))
        return answered(provider, response, toRead, startedAt, cooldown, backOffs)
    }

    /**
     * Step 4: what is asked of the Data Provider, with the Operator present, for the fetch period: the transactions and
     * the balance of each selected Bank Account, only the balance of one whose transactions come from Transaction Files
     * (see [selected]).
     */
    private suspend fun request(toRead: List<Selected>, now: Instant): FetchRequest {
        val today = LocalDate.ofInstant(now, clock.zone)
        return FetchRequest(
            // Step 4: with no Bank Account to read, the Bank Accounts the Data Provider offers are asked for, with their balances.
            bankAccounts = if (toRead.isEmpty()) {
                BankAccountSelection.AllForIdentification(ReadKind.Balance)
            } else {
                BankAccountSelection.Selected(toRead.map { it.read })
            },
            // UC-001 BR-009: A Fetch asks for the transactions of the fetch period, an Operator Setting.
            periodFrom = today.minusDays(settings.current().fetchPeriodDays.toLong()),
            periodTo = today,
            // UC-001 BR-012: A Fetch the Operator starts is made with the Operator present.
            presence = OperatorPresence.OperatorPresent,
            sessions = emptyMap(),
        )
    }

    /**
     * Step 3: every Bank Account the Operator added for the Data Provider, and, for its balance only, every one whose
     * transactions come from Transaction Files and whose balance it reports, that has an Account Mapping and is not a
     * Retired Account.
     */
    private suspend fun selected(stored: StoredDataProvider): List<Selected> = connections.bankAccounts()
        .filter { it.mapping != null && !it.retired }
        .mapNotNull { bankAccount ->
            val ownReference = bankAccount.providerReference
            val balanceReference = bankAccount.balanceProviderReference
            when {
                // Step 4: the transactions and the balance of each Bank Account the Operator added for the Data Provider.
                bankAccount.dataProviderId == stored.id && ownReference != null ->
                    Selected(bankAccount, BankAccountToRead(ownReference, ReadKind.TransactionsAndBalance))
                // UC-001 BR-011: A Bank Account whose transactions come from Transaction Files is read for its balance only, from the Data Provider the Operator chose for it.
                bankAccount.balanceDataProviderId == stored.id && balanceReference != null ->
                    Selected(bankAccount, BankAccountToRead(balanceReference, ReadKind.Balance))
                else -> null
            }
        }

    // UC-001 BR-006: Within the Fetch Cooldown a Bank Account is read until it has a Fetch Result Fetched since it was added or identified again.
    /**
     * A2: of the [selected] Bank Accounts, those not read since the Operator added them or confirmed or changed their
     * identification (`BANK_ACCOUNT.identified_at`), that is, without a Fetch Result Fetched of a Fetch started since then
     * (S-T-02, issue #9). A read that failed, was not delivered or needed a sign-in does not count.
     */
    private suspend fun notReadSinceIdentified(stored: StoredDataProvider, selected: List<Selected>): List<Selected> {
        val fetchesOf = fetches.fetchesOf(stored.id)
        return selected.filter { candidate ->
            val bankAccount = candidate.bankAccount
            fetchesOf.none { fetch ->
                !fetch.startedAt.isBefore(bankAccount.identifiedAt) &&
                    fetch.results.any { it.bankAccountId == bankAccount.id && it.outcome == FetchResultOutcome.Fetched }
            }
        }
    }

    /** A2: the Fetch Cooldown that holds at [now] after the last successful Fetch, or null. */
    private suspend fun cooldownOf(stored: StoredDataProvider, now: Instant): Cooldown? {
        // UC-001 BR-006: A Fetch Cooldown of 0 is switched off; otherwise it is the Operator's value, whose default the part declares.
        if (stored.fetchCooldownMinutes == 0) return null
        val last = fetches.fetchesOf(stored.id).firstOrNull { it.outcome == FetchOutcome.Fetched } ?: return null
        // UC-001 BR-006: A Fetch that delivered no Bank Account does not start a Fetch Cooldown.
        val delivered = offered.offered().any { it.dataProviderId == stored.id && !it.offeredAt.isBefore(last.startedAt) }
        if (!delivered) return null
        val endsAt = last.startedAt.plus(Duration.ofMinutes(stored.fetchCooldownMinutes.toLong()))
        return if (now.isBefore(endsAt)) Cooldown(last.startedAt, endsAt) else null
    }

    private suspend fun answered(
        provider: RegisteredDataProvider,
        response: FetchResponse,
        toRead: List<Selected>,
        startedAt: Instant,
        cooldown: Cooldown?,
        backOffs: List<StoredBackOff>,
    ): FetchReport {
        val key = provider.key
        val stored = provider.stored
        val name = stored.name
        // Per-Bank outcomes (A5 for one Bank, A9) concern only Data Providers that need a Bank Consent, which come with UC-007.
        return when (val outcome = response.sourceOutcome) {
            SourceOutcome.Completed, is SourceOutcome.AskedToWait, SourceOutcome.RequestLimitWarning -> {
                val waitingUntil = (outcome as? SourceOutcome.AskedToWait)?.let { startWait(stored, it.until, startedAt) }
                val warning = if (outcome == SourceOutcome.RequestLimitWarning) startWarning(stored, backOffs, startedAt) else null
                if (waitingUntil != null && response.bankAccounts.isEmpty()) {
                    record(stored, startedAt, FetchOutcome.WaitingForBackOff)
                    return FetchReport.WaitingForProvider(key, name, waitingUntil)
                }
                val answeredAt = clock.instant()
                // UC-009 BR-004: a reference that two delivered Bank Accounts share identifies neither; neither is kept or identified.
                val shared = response.bankAccounts.groupingBy { it.providerReference }.eachCount().filterValues { it > 1 }.keys
                val usable = response.bankAccounts.filter { it.providerReference !in shared }
                val kept = keep(provider, usable, toRead, startedAt, waitingUntil, warning)
                    .copy(sharedReference = response.bankAccounts.size - usable.size)
                // UC-001 BR-006: A read within the Fetch Cooldown is recorded as served from it, so it neither starts nor extends it.
                val fetchId = record(stored, startedAt, if (cooldown == null) FetchOutcome.Fetched else FetchOutcome.ServedFromFetchCooldown)
                // A2 step 3: a Fetch Served From Fetch Cooldown carries the outcome of each Bank Account it read.
                val fetched = storeDeliveries(fetchId, stored, usable, shared, toRead, startedAt, answeredAt, waiting = waitingUntil != null).let { outcomes ->
                    kept.copy(
                        notDelivered = outcomes.notDelivered,
                        signInNeeded = outcomes.signInNeeded,
                        balancesInOtherCurrency = outcomes.balancesInOtherCurrency,
                    )
                }
                if (cooldown == null) {
                    fetched
                } else {
                    FetchReport.ServedFromFetchCooldown(key, name, cooldown.lastFetchAt, cooldown.endsAt, read = fetched, requestLimitFreesUpAt = null)
                }
            }
            // UC-001 BR-005: A timeout, an outage or an unreadable answer starts no Back-off; the Operator can fetch again at any time.
            is SourceOutcome.TransientFailure -> {
                record(stored, startedAt, FetchOutcome.Failed)
                FetchReport.Failed(key, name, outcome.kind, startedAt)
            }
            // UC-014 BR-005: A Data Provider that refuses Slussen's credentials is not contacted until new ones are accepted.
            SourceOutcome.CredentialsRefused -> {
                credentials.markRefused(key)
                record(stored, startedAt, FetchOutcome.CredentialsRefused)
                FetchReport.CredentialsRefused(key, name)
            }
            is SourceOutcome.StoppedByGate -> stoppedByGate(stored, key, outcome.reason, startedAt)
        }
    }

    /** The gate refused the request after step 2 had let it through: recorded as A1 or A3 would record it. */
    private suspend fun stoppedByGate(stored: StoredDataProvider, key: ServiceKey, reason: RefusalReason, startedAt: Instant): FetchReport =
        when (reason) {
            RefusalReason.BackOff -> {
                record(stored, startedAt, FetchOutcome.WaitingForBackOff)
                FetchReport.WaitingForProvider(key, stored.name, fetches.backOffsOf(stored.id).requestToWaitUntil(startedAt) ?: startedAt)
            }
            RefusalReason.RequestLimitReached -> {
                record(stored, startedAt, FetchOutcome.RequestLimitReached)
                FetchReport.RequestLimitReached(key, stored.name, requestLimitFreesUpAt(providerRequests, stored, startedAt) ?: startedAt)
            }
            RefusalReason.UnattendedAccessLimitReached, RefusalReason.OperationEnded -> FetchReport.NotSent(key, stored.name, reason)
        }

    /** A5: the Back-off with cause Request To Wait for the whole Data Provider, until [until] or for the default wait. */
    private suspend fun startWait(stored: StoredDataProvider, until: Instant?, now: Instant): Instant {
        // UC-001 BR-004: A request to wait lasts the period the Data Provider names, or the default wait, an Operator Setting, when it names none.
        val endsAt = until?.takeIf { it.isAfter(now) } ?: now.plus(Duration.ofHours(settings.current().defaultWaitHours.toLong()))
        fetches.startBackOff(NewBackOff(stored.id, null, BackOffCause.RequestToWait, now, endsAt))
        return endsAt
    }

    /** A15: the Back-off with cause Request Limit Warning for the Request Limit warning pause from the first warning. */
    private suspend fun startWarning(stored: StoredDataProvider, backOffs: List<StoredBackOff>, now: Instant): RequestLimitWarningShown {
        backOffs.requestLimitWarningAt(now)?.let { return RequestLimitWarningShown(it.startsAt, it.endsAt) }
        // UC-001 BR-015: A Request Limit warning starts a Back-off that lasts the Request Limit warning pause, an Operator Setting, from the first warning.
        val endsAt = now.plus(Duration.ofHours(settings.current().requestLimitWarningHours.toLong()))
        fetches.startBackOff(NewBackOff(stored.id, null, BackOffCause.RequestLimitWarning, now, endsAt))
        return RequestLimitWarningShown(now, endsAt)
    }

    /** Step 6: the delivered Bank Accounts kept as Offered Bank Accounts, and the selected ones identified. */
    private suspend fun keep(
        provider: RegisteredDataProvider,
        delivered: List<DeliveredBankAccount>,
        toRead: List<Selected>,
        startedAt: Instant,
        waitingUntil: Instant?,
        warning: RequestLimitWarningShown?,
    ): FetchReport.Fetched {
        val stored = provider.stored
        val offeredAt = clock.instant()
        val leftOut = connections.leftOutBankAccounts().filter { it.dataProviderId == stored.id }.map { it.providerReference }.toSet()
        // UC-009 BR-009: The Offered Bank Accounts of a Data Provider are replaced by those of its latest delivery, of the number only the last four digits and the fingerprint.
        offered.replaceOffered(stored.id, null, delivered.map { offeredOf(stored, it, offeredAt, it.providerReference in leftOut) })
        val references = delivered.map { it.providerReference }.toSet()
        // UC-001 BR-011: A Bank Account is identified with certainty by the identifier its Data Provider gives it, a file-imported one by the identifier its balance Data Provider gives it.
        val identified = toRead.filter { it.read.providerReference in references }.map { it.bankAccount.name }
        val failedReferences = delivered.filter { it.result is BankAccountResult.Failed }.map { it.providerReference }.toSet()
        val added = connections.bankAccounts().flatMap { bankAccount ->
            listOfNotNull(
                bankAccount.providerReference?.takeIf { bankAccount.dataProviderId == stored.id },
                bankAccount.balanceProviderReference?.takeIf { bankAccount.balanceDataProviderId == stored.id },
            )
        }.toSet()
        return FetchReport.Fetched(
            service = provider.key,
            dataProviderName = stored.name,
            fetchedAt = startedAt,
            offered = delivered.size,
            identified = identified,
            // A18: a delivered Bank Account that matches none the Operator added is kept only as an Offered Bank Account;
            // a Left-Out Bank Account is not counted (UC-013 BR-011).
            notAdded = delivered.count { it.providerReference !in added && it.providerReference !in leftOut },
            waitingUntil = waitingUntil,
            warning = warning,
            // A8: the identified Bank Accounts the Data Provider reported a failure for.
            failed = toRead.filter { it.read.providerReference in failedReferences }.map { it.bankAccount.name },
        )
    }

    /** What [storeDeliveries] found that the Fetch reports beside its Fetch Results (A19, A20, A21). */
    private data class DeliveryOutcomes(
        val notDelivered: List<String>,
        val signInNeeded: List<String>,
        val balancesInOtherCurrency: List<BalanceInOtherCurrency>,
    )

    /**
     * Steps 7-9 and 11 for each selected Bank Account (O-1, one storage operation each): its Fetch Result for the Fetch
     * [fetchId]; for one the Data Provider read, its booked, non-zero Bank Transactions and its Unreadable Transactions
     * (not for one read for its balance only, BR-011), and the Account Balance the Data Provider reported, at the time it
     * says it reported it, or as at [answeredAt] when it gives none (S-T-04), when it is in the Bank Account's own
     * currency (A21); then the Decision Journal entries of what was received (step 9). A Bank Account whose reference two
     * delivered Bank Accounts share is Ambiguous Bank Account (A10), one the answer does not contain Not Delivered (A19),
     * one the Data Provider reported a failure for Failed (A8), one whose Bank's sign-in at the Data Provider is needed
     * Sign-In Needed (A20); each of them keeps its stored data. One the answer lists but did not read (A5) keeps its
     * stored data and gets no Fetch Result. Offered Bank Accounts that are not added store nothing (A18, S-35).
     */
    private suspend fun storeDeliveries(
        fetchId: Long,
        stored: StoredDataProvider,
        delivered: List<DeliveredBankAccount>,
        shared: Set<String>,
        toRead: List<Selected>,
        startedAt: Instant,
        answeredAt: Instant,
        waiting: Boolean,
    ): DeliveryOutcomes {
        val byReference = delivered.associateBy { it.providerReference }
        val notDelivered = mutableListOf<String>()
        val signInNeeded = mutableListOf<String>()
        val otherCurrency = mutableListOf<BalanceInOtherCurrency>()
        toRead.forEach { selected ->
            val bankAccount = selected.bankAccount
            val reference = selected.read.providerReference
            val one = byReference[reference]
            val delivery = when {
                // A10 step 3: two delivered Bank Accounts share the reference, so neither identifies this Bank Account.
                reference in shared -> BankAccountDelivery(fetchId, bankAccount.id, FetchResultOutcome.AmbiguousBankAccount, 0, balance = null)
                // A5 step 2: while the Data Provider asks Slussen to wait, a Bank Account it did not deliver is one the wait
                // affects: its stored data stays unchanged and it is not Not Delivered.
                one == null && waiting -> null
                // A19: the answer contains the selected Bank Account neither as delivered nor as failed or not read.
                one == null -> {
                    notDelivered += bankAccount.name
                    BankAccountDelivery(fetchId, bankAccount.id, FetchResultOutcome.NotDelivered, 0, balance = null)
                }
                else -> when (val result = one.result) {
                    is BankAccountResult.Read -> readDelivery(fetchId, stored, selected, one, result, startedAt, answeredAt, otherCurrency)
                    // A8: a failure for one Bank Account keeps its stored data and records the outcome Failed.
                    is BankAccountResult.Failed -> BankAccountDelivery(fetchId, bankAccount.id, FetchResultOutcome.Failed, 0, balance = null)
                    // A20: the Bank's sign-in at the Data Provider is needed: its stored data stays unchanged and no Back-off starts.
                    BankAccountResult.SignInNeeded -> {
                        signInNeeded += bankAccount.name
                        BankAccountDelivery(fetchId, bankAccount.id, FetchResultOutcome.SignInNeeded, 0, balance = null)
                    }
                    is BankAccountResult.NotRead -> null
                }
            }
            // UC-001 BR-010: A failure of one Bank Account leaves the others' storage unaffected: each is its own storage operation.
            val storedDelivery = delivery?.let { fetches.storeBankAccountDelivery(it) } ?: return@forEach
            journalReceived(bankAccount, storedDelivery)
        }
        return DeliveryOutcomes(notDelivered, signInNeeded, otherCurrency)
    }

    /** Steps 7-8, A21: what O-1 stores for a Bank Account the Data Provider read. */
    private fun readDelivery(
        fetchId: Long,
        stored: StoredDataProvider,
        selected: Selected,
        one: DeliveredBankAccount,
        result: BankAccountResult.Read,
        startedAt: Instant,
        answeredAt: Instant,
        otherCurrency: MutableList<BalanceInOtherCurrency>,
    ): BankAccountDelivery {
        val bankAccount = selected.bankAccount
        // UC-001 BR-011: A Bank Account whose transactions come from Transaction Files takes only its balance from the Data Provider.
        val transactions = if (selected.read.read == ReadKind.Balance) null else takenIn(stored, bankAccount.currency, result.transactions, answeredAt)
        val reportedIn = one.currency ?: bankAccount.currency
        val balance = result.balance?.let { amount ->
            // A21: a balance reported in another currency than the Bank Account's is not stored, and nothing is converted.
            if (reportedIn != bankAccount.currency) {
                BalanceInOtherCurrency(bankAccount.id, bankAccount.name, stored.name, reportedIn, bankAccount.currency, startedAt).let {
                    otherCurrency += it
                    notices.noteBalanceInOtherCurrency(it)
                }
                null
            } else {
                notices.balanceStored(bankAccount.id)
                // Step 8 (S-T-04): reported at the time the Data Provider gives, or the time the answer arrived.
                NewAccountBalance(amount, bankAccount.currency, result.balanceReportedAt ?: answeredAt)
            }
        }
        return BankAccountDelivery(
            fetchId = fetchId,
            bankAccountId = bankAccount.id,
            outcome = FetchResultOutcome.Fetched,
            transactionCount = transactions?.interpretable?.size ?: 0,
            // UC-001 BR-011: The Account Balance of a file-imported Bank Account is the one its balance Data Provider reports, stored like any other.
            balance = balance,
            transactions = transactions,
        )
    }

    /**
     * Step 7, A17: the delivered transactions Slussen takes in, in the order delivered: the booked ones with an amount
     * other than zero, and those it cannot interpret unless they are known to be pending.
     */
    private fun takenIn(
        stored: StoredDataProvider,
        currency: CurrencyCode,
        delivered: List<ProviderTransaction>,
        answeredAt: Instant,
    ): DeliveredTransactions {
        val (interpretable, otherCurrency) = delivered.filterIsInstance<ProviderTransaction.Interpretable>()
            // UC-001 BR-002: Only booked transactions are taken in; a pending one is left out until a later Fetch delivers it as booked.
            .filter { it.booked }
            // UC-001 BR-003: A transaction with an amount of zero is left out.
            .filter { it.amount.thousandths != 0L }
            .partition { it.currency == currency }
        val unreadable = delivered.filterIsInstance<ProviderTransaction.Uninterpretable>()
            // UC-001 BR-002: an item the Data Provider reports as pending is left out, also when it cannot be interpreted.
            .filter { it.booked != false }
            .map { NewUnreadableTransaction(it.bookedDate, it.amount, it.currency, it.payee, it.memo, reasonOf(it.reason)) }
        // UC-001 BR-016: A transaction in another currency than its Bank Account's is kept for review, never converted (S-T-16, A17).
        val inOtherCurrency = otherCurrency.map {
            NewUnreadableTransaction(it.bookedDate, it.amount, it.currency, it.payee, it.memo, UnreadableTransactionReason.OtherCurrency)
        }
        return DeliveredTransactions(
            stored.sourceKey,
            answeredAt,
            interpretable.map { DeliveredTransaction(it.stableReference, it.bookedDate, it.amount, it.currency, it.payee, it.memo) },
            unreadable + inOtherCurrency,
        )
    }

    private fun reasonOf(reason: UnreadableReason): UnreadableTransactionReason = when (reason) {
        UnreadableReason.MissingDate -> UnreadableTransactionReason.MissingDate
        UnreadableReason.UnreadableDate -> UnreadableTransactionReason.UnreadableDate
        UnreadableReason.MissingAmount -> UnreadableTransactionReason.MissingAmount
        UnreadableReason.UnreadableAmount -> UnreadableTransactionReason.UnreadableAmount
        UnreadableReason.MissingCurrency -> UnreadableTransactionReason.MissingCurrency
        UnreadableReason.UnreadableStructure -> UnreadableTransactionReason.UnreadableStructure
    }

    // UC-011 BR-001: Every Bank Transaction received for the first time or with changed content is recorded, naming its Bank Account and its Import Identifier.
    /**
     * Step 9: one Decision Journal entry per Bank Transaction [delivered] stored as received for the first time or with
     * changed content, and one per Import Identifier collision (ADR-008 §5). The details hold Slussen's own words, the
     * date and the amount, never a text the Data Provider delivered (NFR-012).
     */
    private suspend fun journalReceived(bankAccount: StoredBankAccount, delivered: DeliveryStored) {
        delivered.received.forEach { received ->
            val (kind, what) = when (received.reception) {
                Reception.FirstTime -> JournalEventKind.TransactionSeen to "Bank Transaction received for the first time"
                Reception.ChangedContent -> JournalEventKind.TransactionChanged to
                    "Bank Transaction received with changed content (${received.changed.joinToString(", ") { fieldName(it) }})"
            }
            val details = "$what: booked ${received.bookedDate}, ${amountText(received.amount)} ${received.currency.code}"
            journal.record(kind, details, received.importIdentifier, bankAccount.name)
        }
        delivered.collisions.forEach { importIdentifier ->
            journal.record(
                JournalEventKind.StatusChanged,
                "Delivered transaction kept as an Unreadable Transaction with the status Needs Review: Import Identifier collision",
                importIdentifier,
                bankAccount.name,
            )
        }
    }

    private fun fieldName(field: TransactionField): String = when (field) {
        TransactionField.BookedDate -> "booked date"
        TransactionField.Amount -> "amount"
        TransactionField.Currency -> "currency"
        TransactionField.Payee -> "payee"
        TransactionField.Memo -> "memo"
    }

    /** An amount as signed units with three decimals, the entity model's precision `12,3`: -64.180. */
    private fun amountText(amount: Amount): String {
        val thousandths = amount.thousandths
        val units = Math.abs(thousandths) / THOUSANDTHS
        val rest = (Math.abs(thousandths) % THOUSANDTHS).toString().padStart(3, '0')
        return "${if (thousandths < 0) "-" else ""}$units.$rest"
    }

    private suspend fun offeredOf(
        stored: StoredDataProvider,
        delivered: DeliveredBankAccount,
        offeredAt: Instant,
        leftOut: Boolean,
    ): NewOfferedBankAccount {
        val bankName = delivered.bankReference?.trim()?.take(BANK_NAME_MAX_LENGTH)?.takeIf { it.isNotEmpty() }
        return NewOfferedBankAccount(
            bankId = bankName?.let { connections.bankNamed(it).id },
            providerReference = delivered.providerReference,
            bankAccountFingerprint = delivered.number?.let { BankAccountFingerprint.of(stored.sourceKey, delivered.bankReference, it) },
            name = delivered.name?.take(NAME_MAX_LENGTH),
            bankAccountKind = delivered.kind?.take(KIND_MAX_LENGTH),
            currency = delivered.currency,
            // UC-013 BR-011: A Left-Out Bank Account is never read: no balance of it is stored, though it stays in the delivered list.
            balance = if (leftOut) null else balanceOf(delivered.result),
            lastFourDigits = delivered.number?.let { BankAccountFingerprint.lastFourDigits(it) },
            proposedBankAccountId = null,
            offeredAt = offeredAt,
        )
    }

    /**
     * The balance of an Offered Bank Account: from a Bank Account read, or the one the Data Provider gave with the list
     * for a Bank Account the request did not ask to read (S-35, ADR-001 §4.1), so the Offered Bank Accounts not yet
     * added keep their balance after another one was added (UC-013 step 5). Only an added Bank Account that was read
     * stores it as its Account Balance (step 8, [storeDeliveries]).
     */
    private fun balanceOf(result: BankAccountResult): Amount? = when (result) {
        is BankAccountResult.Read -> result.balance
        is BankAccountResult.NotRead -> result.balance.takeIf { result.cause == NotReadCause.NotSelected }
        is BankAccountResult.Failed -> null
        // A20: a Bank Account whose Bank's sign-in at the Data Provider is needed carries no balance.
        BankAccountResult.SignInNeeded -> null
    }

    /** UC-014 BR-005: refused in this process, or in the last Fetch after the credentials were last accepted. */
    private suspend fun refusedCredentials(provider: RegisteredDataProvider): Boolean {
        val status = credentials.status(provider.key)
        if (status == CredentialStatus.Refused) return true
        val acceptedAt = (status as? CredentialStatus.Accepted)?.acceptedAt ?: return false
        val last = fetches.fetchesOf(provider.stored.id).firstOrNull() ?: return false
        return last.outcome == FetchOutcome.CredentialsRefused && acceptedAt.isBefore(last.startedAt)
    }

    /** Step 11: the Fetch with its time, who started it and its outcome; only the most recent are kept (`FETCH`). */
    private suspend fun record(stored: StoredDataProvider, startedAt: Instant, outcome: FetchOutcome): Long =
        fetches.recordFetch(NewFetch(stored.id, startedAt, StartedBy.Operator, outcome), settings.current().fetchHistorySize)

    private companion object {
        /** `OFFERED_BANK_ACCOUNT.name` holds at most 100 characters (entity model). */
        const val NAME_MAX_LENGTH = 100

        /** `OFFERED_BANK_ACCOUNT.bank_account_kind` holds at most 50 characters (entity model). */
        const val KIND_MAX_LENGTH = 50

        /** `BANK.name` holds at most 100 characters (entity model). */
        const val BANK_NAME_MAX_LENGTH = 100

        /** Thousandths in one currency unit (ADR-001 §4 `Amount`). */
        const val THOUSANDTHS = 1000L
    }
}
