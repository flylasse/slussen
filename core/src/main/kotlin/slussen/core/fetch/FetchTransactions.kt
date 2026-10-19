package slussen.core.fetch

import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.identity.BankAccountFingerprint
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredDataProvider
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BackOffCause
import slussen.core.port.ConnectionStore
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchStore
import slussen.core.port.NewBackOff
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
import slussen.pluginapi.provider.OperatorPresence
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.RefusalReason
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Fetch Transactions (UC-001), `:core` `fetch` (ADR-001 §7), as far as the identification slice builds it (Phase 8a,
 * O-P2-45): the request gate (Back-off, Fetch Cooldown, Request Limit; A1-A3, A16), the Fetch with the Operator present,
 * the Offered Bank Accounts with their balance (step 6), the identification of the Bank Accounts the Operator added
 * (step 6, A18, BR-011), the Back-offs a Data Provider asks for (A5, A15), and the Fetch record with its outcome (step
 * 11, A6, A7). Steps 7-10 (Bank Transactions, Account Balances, Fetch Results, the Decision Journal, the Comparison)
 * and the Scheduled Fetch (A12) come with later slices; the transactions a Data Provider delivers are not used yet.
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
    private val clock: Clock,
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
        // UC-001 BR-006: Within the Fetch Cooldown only the Bank Accounts added or identified again since the last successful Fetch are read.
        val toRead = if (cooldown == null) selected else selected.filter { it.bankAccount.identifiedAt.isAfter(cooldown.lastFetchAt) }
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

    /** Step 4: what is asked of the Data Provider, with the Operator present, for the fetch period. */
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
                bankAccount.dataProviderId == stored.id && ownReference != null ->
                    Selected(bankAccount, BankAccountToRead(ownReference, ReadKind.TransactionsAndBalance))
                // UC-001 BR-011: A Bank Account whose transactions come from Transaction Files is read for its balance only, from the Data Provider the Operator chose for it.
                bankAccount.balanceDataProviderId == stored.id && balanceReference != null ->
                    Selected(bankAccount, BankAccountToRead(balanceReference, ReadKind.Balance))
                else -> null
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
                val fetched = keep(provider, response.bankAccounts, toRead, startedAt, waitingUntil, warning)
                if (cooldown == null) {
                    record(stored, startedAt, FetchOutcome.Fetched)
                    fetched
                } else {
                    // UC-001 BR-006: A read within the Fetch Cooldown is recorded as served from it, so it neither starts nor extends it.
                    record(stored, startedAt, FetchOutcome.ServedFromFetchCooldown)
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
        // UC-009 BR-009: The Offered Bank Accounts of a Data Provider are replaced by those of its latest delivery, of the number only the last four digits and the fingerprint.
        offered.replaceOffered(stored.id, null, delivered.map { offeredOf(stored, it, offeredAt) })
        val references = delivered.map { it.providerReference }.toSet()
        // UC-001 BR-011: A Bank Account is identified with certainty by the identifier its Data Provider gives it, a file-imported one by the identifier its balance Data Provider gives it.
        val identified = toRead.filter { it.read.providerReference in references }.map { it.bankAccount.name }
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
            // A18: a delivered Bank Account that matches none the Operator added is kept only as an Offered Bank Account.
            notAdded = delivered.count { it.providerReference !in added },
            waitingUntil = waitingUntil,
            warning = warning,
        )
    }

    private suspend fun offeredOf(stored: StoredDataProvider, delivered: DeliveredBankAccount, offeredAt: Instant): NewOfferedBankAccount {
        val bankName = delivered.bankReference?.trim()?.take(BANK_NAME_MAX_LENGTH)?.takeIf { it.isNotEmpty() }
        return NewOfferedBankAccount(
            bankId = bankName?.let { connections.bankNamed(it).id },
            providerReference = delivered.providerReference,
            bankAccountFingerprint = delivered.number?.let { BankAccountFingerprint.of(stored.sourceKey, delivered.bankReference, it) },
            name = delivered.name?.take(NAME_MAX_LENGTH),
            bankAccountKind = delivered.kind?.take(KIND_MAX_LENGTH),
            currency = delivered.currency,
            balance = (delivered.result as? BankAccountResult.Read)?.balance,
            lastFourDigits = delivered.number?.let { BankAccountFingerprint.lastFourDigits(it) },
            proposedBankAccountId = null,
            offeredAt = offeredAt,
        )
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
    private suspend fun record(stored: StoredDataProvider, startedAt: Instant, outcome: FetchOutcome) =
        fetches.recordFetch(NewFetch(stored.id, startedAt, StartedBy.Operator, outcome), settings.current().fetchHistorySize)

    private companion object {
        /** `OFFERED_BANK_ACCOUNT.name` holds at most 100 characters (entity model). */
        const val NAME_MAX_LENGTH = 100

        /** `OFFERED_BANK_ACCOUNT.bank_account_kind` holds at most 50 characters (entity model). */
        const val KIND_MAX_LENGTH = 50

        /** `BANK.name` holds at most 100 characters (entity model). */
        const val BANK_NAME_MAX_LENGTH = 100
    }
}
