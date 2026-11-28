package slussen.core.consent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import slussen.core.connection.proposedMatchesOf
import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.identity.BankAccountFingerprint
import slussen.core.identity.ConsentStateValue
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredDataProvider
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.AnswerRejection
import slussen.core.port.ConnectionStore
import slussen.core.port.ConsentStore
import slussen.core.port.ConsumedAnswer
import slussen.core.port.FetchStore
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.core.port.NewOfferedBankAccount
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.RandomSource
import slussen.core.port.ReplacedConsent
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StoredBank
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredConsentRequest
import slussen.core.request.ServiceRequestContext
import slussen.core.request.requestLimitFreesUpAt
import slussen.core.request.requestToWaitUntil
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestTimeout
import slussen.pluginapi.value.Amount
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What the Bank Consent operations write to the log: each outcome by its constant, never a value of the consent (BR-006). */
enum class BankConsentEvent : LogEvent {
    BanksListed,
    ConsentRequestStarted,
    ConsentRequestNotStarted,
    AnswerNotAccepted,
    BankConsentNotGranted,
    BankConsentStored,
    ConsentRequestAbandoned,
    BankConsentRevoked,
}

/**
 * Grant Bank Consent (UC-007), `:core` `consent` (ADR-001 §7): choosing among the Banks a Data Provider reaches (BR-010),
 * starting a Consent Request with a state the core makes and of which it stores only the digest (steps 2-4, BR-002,
 * BR-011), completing it from the answer the Operator returns with or pastes (steps 6-10), renewing (BR-003), revoking
 * (A7, BR-007) and abandoning (A10), and what the Data Provider's page shows (BR-008). Each is one operation of kind
 * [OperationKind.BankConsent] on the coordinator's lane (ADR-007 §2), which it holds only for its own requests and
 * storage, never while the Operator is at the Bank. Every request goes through the gate, as kind Other with the
 * Operator present (ADR-001 §4.0). Nothing here names a Data Provider (Konsist K-04): the plug-in's capability does the
 * rest.
 *
 * The Bank's row of `slussen.db` and the Bank Consent of `device.db` cannot share a transaction (ADR-002 §3 O-10): the
 * completion consumes the answer, completes at the Data Provider, finds or creates the Bank's row, then stores the Bank
 * Consent once; a Bank's row it created is removed again when the Bank Consent is then not stored, and
 * [LeftBehindBanks] removes one an ended process left at the next start.
 */
class BankConsents(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val consents: ConsentStore,
    private val connections: ConnectionStore,
    private val offered: OfferedBankAccountStore,
    private val fetches: FetchStore,
    private val providerRequests: ProviderRequestStore,
    private val coordinator: OperationCoordinator,
    private val settings: OperatorSettingsStore,
    private val clock: Clock,
    private val random: RandomSource,
    /** The application's own return address, `<application id>://consent`, formed from the build's application id (BR-011). */
    private val applicationReturnAddress: String,
    private val log: SlussenLog,
) {
    private val leftBehind = LeftBehindBanks(connections, consents, offered)

    /** The Bank Consent operations of [provider], which its part declares exactly when it needs a Bank Consent (ADR-001 §4.1). */
    private fun capabilityOf(provider: RegisteredDataProvider): BankConsentCapability {
        require(provider.stored.needsBankConsent) { "The Data Provider ${provider.stored.name} needs no Bank Consent (UC-007 BR-008)." }
        return requireNotNull(provider.plugin.bankConsent) {
            "The Data Provider ${provider.stored.name} needs a Bank Consent and declares no Bank Consent operations (ADR-001 §4.1)."
        }
    }

    // UC-007 BR-011: The return address is the one registered with the Data Provider's credentials, or Slussen's own when none is registered there.
    private suspend fun returnAddressOf(provider: RegisteredDataProvider): String =
        capabilityOf(provider).registeredReturnAddress(credentials.stored(provider.key))?.trim()?.takeIf { it.isNotEmpty() }
            ?: applicationReturnAddress

    /** The exact return address Slussen sends for [service], to register with the Data Provider (BR-011). Sends nothing. */
    suspend fun returnAddress(service: ServiceKey): String = returnAddressOf(plugins.dataProvider(service))

    /** UC-014 BR-005: without accepted credentials no Data Provider is contacted. */
    private suspend fun unavailable(provider: RegisteredDataProvider): ConsentUnavailable? = when (credentials.status(provider.key)) {
        is CredentialStatus.Accepted -> null
        CredentialStatus.Missing -> ConsentUnavailable.CredentialsMissing
        CredentialStatus.Refused -> ConsentUnavailable.CredentialsRefused
    }

    /** The request context of a Bank Consent request at the Bank [bankId] (null: none, or a Bank without a row yet). */
    private suspend fun context(provider: RegisteredDataProvider, permit: OperationPermit, bankId: Long?): RequestContext =
        ServiceRequestContext(
            gate = ConsentGate(credentials.gate(provider.key, permit), permit, provider.stored.id, bankId, fetches, clock),
            timeout = RequestTimeout(Duration.ofSeconds(settings.current().requestTimeoutSeconds.toLong())),
            credential = credentials.stored(provider.key),
        )

    /** A9 step 2: what held the request back, with the time it ends (UC-001 A3, A5). */
    private suspend fun heldBack(provider: RegisteredDataProvider, bankId: Long?, reason: RefusalReason): HeldBack {
        val now = clock.instant()
        return when (reason) {
            RefusalReason.RequestLimitReached ->
                HeldBack(HeldBackBy.RequestLimitReached, requestLimitFreesUpAt(providerRequests, provider.stored, now) ?: now)
            RefusalReason.BackOff -> {
                val backOffs = fetches.backOffsOf(provider.stored.id)
                val until = listOfNotNull(backOffs.requestToWaitUntil(now), bankId?.let { backOffs.bankWaitUntil(it, now) }).maxOrNull()
                HeldBack(HeldBackBy.WaitingForProvider, until ?: now)
            }
            RefusalReason.UnattendedAccessLimitReached, RefusalReason.OperationEnded -> HeldBack(HeldBackBy.NotSent, now)
        }
    }

    /** Runs [block] as one Bank Consent operation; [paused] answers a refusal of the coordinator (ADR-007 §2). */
    private suspend fun <T> operation(paused: (ConsentUnavailable) -> T, block: suspend (OperationPermit) -> T): T =
        when (val result = coordinator.run(OperationKind.BankConsent, StartedBy.Operator, block)) {
            is OperationResult.Ran -> result.value
            // UC-006 BR-005: while an Upload Plan awaits confirmation or an Upload is written, no Bank Consent operation runs.
            is OperationResult.Refused -> paused(ConsentUnavailable.Paused(result.reason))
        }

    private fun logged(event: BankConsentEvent, vararg facts: LogFact) = log.event(event, *facts)

    /** The Bank named by [reference] (its `BANK.name`), when Slussen has a row for it. */
    private suspend fun bankOf(reference: String): StoredBank? = connections.banks().firstOrNull { it.name == reference }

    // ---- Choosing the Bank ------------------------------------------------------------------------------------------

    /**
     * Step 1, BR-010: the Banks the Data Provider [service] lists for [country], an ISO 3166 two-letter code, as one
     * request through the gate; nothing is stored. A Bank whose reference Slussen cannot store is left out.
     */
    suspend fun banks(service: ServiceKey, country: String): BanksOutcome {
        // UC-007 BR-010: The Operator chooses the Bank from the Banks the Data Provider lists for one country.
        if (!COUNTRY.matches(country)) return BanksOutcome.CountryNotValid
        val provider = plugins.dataProvider(service)
        val capability = capabilityOf(provider)
        return operation<BanksOutcome>(BanksOutcome::NotAvailable) { permit ->
            unavailable(provider)?.let { return@operation BanksOutcome.NotAvailable(it) }
            permit.workingOn(provider.stored.name)
            // UC-007 BR-010: The list is asked of the Data Provider each time the choice opens or the country changes, as one request through the request gate, and is not stored.
            when (val list = capability.banks(country, context(provider, permit, bankId = null))) {
                is BankList.Listed -> BanksOutcome.Listed(country, list.banks.filter { usable(it.bankReference) }.distinctBy { it.bankReference })
                    .also { logged(BankConsentEvent.BanksListed, LogFact.Count(it.banks.size.toLong())) }
                is BankList.Refused -> {
                    if (list.kind == ConsentFailureKind.CredentialsRefused) credentials.markRefused(provider.key)
                    BanksOutcome.Refused(list.kind)
                }
                // A8 step 1: listing that fails stores nothing; the Operator can try again.
                is BankList.TransientFailure -> BanksOutcome.NotReached(list.kind, clock.instant())
                is BankList.StoppedByGate -> BanksOutcome.HeldBackByGate(heldBack(provider, null, list.reason))
            }
        }
    }

    /** A Bank reference Slussen can store as `CONSENT_REQUEST.bank_reference` and `BANK.name` (entity model). */
    private fun usable(reference: String): Boolean = reference.isNotBlank() && reference.length <= BANK_REFERENCE_MAX_LENGTH

    // ---- Starting ---------------------------------------------------------------------------------------------------

    /**
     * Steps 2-4: starts a Consent Request for the Bank [bankReference] with the Data Provider [service]: the core makes
     * the state, the Data Provider hands over the way to the Bank's Strong Customer Authentication, and the Consent
     * Request is stored with the digest of the state, in place of every earlier one of the Bank. [browserAvailable] is
     * whether the device has an app that can open that way (A11).
     */
    suspend fun start(service: ServiceKey, bankReference: String, browserAvailable: Boolean): StartOutcome {
        // A11 of UC-007: without a browser no Consent Request is started and nothing is sent.
        if (!browserAvailable) return StartOutcome.NoBrowser
        if (!usable(bankReference)) return StartOutcome.BankReferenceNotUsable
        val provider = plugins.dataProvider(service)
        val capability = capabilityOf(provider)
        return operation<StartOutcome>(StartOutcome::NotAvailable) { permit ->
            unavailable(provider)?.let { return@operation StartOutcome.NotAvailable(it) }
            permit.workingOn(provider.stored.name)
            startOnLane(provider, capability, bankReference, permit)
        }.also { outcome ->
            logged(if (outcome is StartOutcome.Started) BankConsentEvent.ConsentRequestStarted else BankConsentEvent.ConsentRequestNotStarted)
        }
    }

    /**
     * BR-003, BR-010: renews the Bank Consent of the Bank [bankId], or grants one for a Bank Slussen knows: the Bank is the
     * one of the Bank Consent, so no choice is needed, and its reference is its `BANK.name`.
     */
    suspend fun renew(service: ServiceKey, bankId: Long, browserAvailable: Boolean): StartOutcome {
        val dataProviderId = plugins.dataProvider(service).stored.id
        val bank = connections.banks().firstOrNull { it.id == bankId } ?: return StartOutcome.NotABankOfTheDataProvider
        // UC-007 BR-010: Renewing needs no choice: the Bank is the one of the Bank Consent, or the one the Data Provider names for one of its Bank Accounts.
        val ofTheDataProvider = consents.bankConsents().any { it.bankId == bankId && it.dataProviderId == dataProviderId } ||
            connections.bankAccounts().any {
                it.providerBankId == bankId && (it.dataProviderId == dataProviderId || it.balanceDataProviderId == dataProviderId)
            }
        if (!ofTheDataProvider) return StartOutcome.NotABankOfTheDataProvider
        return start(service, bank.name, browserAvailable)
    }

    private suspend fun startOnLane(
        provider: RegisteredDataProvider,
        capability: BankConsentCapability,
        bankReference: String,
        permit: OperationPermit,
    ): StartOutcome {
        val returnAddress = returnAddressOf(provider)
        // UC-007 BR-002: A Consent Request is valid for at most the Consent Request lifetime, from its start.
        val startedAt = clock.instant()
        val expiresAt = startedAt.plus(Duration.ofMinutes(settings.current().consentRequestMinutes.toLong()))
        val state = ConsentStateValue.newState(random)
        val bankId = bankOf(bankReference)?.id
        return when (val started = capability.startConsentRequest(bankReference, returnAddress, state, context(provider, permit, bankId))) {
            is ConsentStart.Started -> {
                // UC-007 BR-002: only the most recent Consent Request of a Bank can be answered: storing it makes every earlier one unanswerable.
                val stored = storeOrNull {
                    consents.startRequest(
                        NewConsentRequest(bankReference, provider.stored.id, startedAt, expiresAt, ConsentStateValue.digestOf(state), started.handle),
                    )
                } ?: return StartOutcome.NotStored(bankReference)
                StartOutcome.Started(stored.id, bankReference, expiresAt, ConsentAddress(started.consentAddress), returnAddress)
            }
            // UC-007 BR-011: a refusal of the return address names the address Slussen sent, to register with the Data Provider.
            is ConsentStart.Refused -> {
                if (started.kind == ConsentFailureKind.CredentialsRefused) credentials.markRefused(provider.key)
                StartOutcome.Refused(bankReference, started.kind, returnAddress.takeIf { started.kind == ConsentFailureKind.ReturnAddressRefused })
            }
            is ConsentStart.TransientFailure -> StartOutcome.NotReached(bankReference, started.kind, clock.instant())
            is ConsentStart.StoppedByGate -> StartOutcome.HeldBackByGate(bankReference, heldBack(provider, bankId, started.reason))
        }
    }

    // ---- Completing -------------------------------------------------------------------------------------------------

    /**
     * Steps 6-10: completes the Consent Request that [answer] answers, returned at the return address or pasted by the
     * Operator, which are checked alike (BR-011). The answer has exactly one outcome, decided by O-10's checks in one
     * transaction: accepted only for the most recent Consent Request of its Bank, within its lifetime counted to the
     * moment it arrived, and not answered before (BR-002).
     */
    suspend fun complete(answer: ConsentAnswer): CompletionOutcome {
        val arrivedAt = clock.instant()
        return operation<CompletionOutcome>(CompletionOutcome::NotAvailable) { permit -> completeOnLane(answer, arrivedAt, permit) }
            .also { outcome ->
                when (outcome) {
                    is CompletionOutcome.Granted -> logged(BankConsentEvent.BankConsentStored)
                    is CompletionOutcome.NotAccepted -> logged(BankConsentEvent.AnswerNotAccepted, LogFact.Value(outcome.reason))
                    else -> logged(BankConsentEvent.BankConsentNotGranted)
                }
            }
    }

    private suspend fun completeOnLane(answer: ConsentAnswer, arrivedAt: Instant, permit: OperationPermit): CompletionOutcome {
        var stateFound = false
        for (provider in plugins.dataProviders.filter { it.stored.needsBankConsent }) {
            // The plug-in reads the state from the answer and sends nothing; the core digests it (ADR-001 §4.1).
            val state = capabilityOf(provider).stateOf(answer) ?: continue
            stateFound = true
            val digest = ConsentStateValue.digestOf(state)
            // The Consent Request of the digest names its Data Provider: that one's credentials and operations are used.
            val request = storeOrNull { consents.requestWithDigest(digest) ?: NONE } ?: return CompletionOutcome.NotStored(bankReference = null)
            if (request === NONE) continue
            val owner = plugins.dataProviders.firstOrNull { it.stored.id == request.dataProviderId } ?: continue
            // UC-007 BR-002: An answer that arrives while its Data Provider's credentials are missing or refused is left unconsumed; its lifetime still bounds it.
            unavailable(owner)?.let { return CompletionOutcome.NotAvailable(it) }
            // UC-007 BR-002: A Consent Request is accepted at most once, within its lifetime, and only when it is the most recent of its Bank.
            val consumed = storeOrNull { consents.consumeAnswer(digest, arrivedAt) } ?: return CompletionOutcome.NotStored(bankReference = null)
            when (consumed) {
                is ConsumedAnswer.Rejected -> if (consumed.reason != AnswerRejection.NoSuchRequest) return CompletionOutcome.NotAccepted(notAccepted(consumed.reason))
                is ConsumedAnswer.Accepted -> {
                    permit.workingOn(owner.stored.name)
                    return completeAccepted(owner, capabilityOf(owner), answer, consumed, permit)
                }
            }
        }
        // A2 of UC-007: an answer whose state no part can read cannot be read; one whose state matches no Consent Request answers none of this installation.
        return CompletionOutcome.NotAccepted(if (stateFound) AnswerNotAccepted.NoSuchRequest else AnswerNotAccepted.Unreadable)
    }

    private fun notAccepted(reason: AnswerRejection): AnswerNotAccepted = when (reason) {
        AnswerRejection.NoSuchRequest -> AnswerNotAccepted.NoSuchRequest
        AnswerRejection.NotTheMostRecent -> AnswerNotAccepted.NotTheMostRecent
        AnswerRejection.LifetimeEnded -> AnswerNotAccepted.LifetimeEnded
        AnswerRejection.AlreadyAnswered -> AnswerNotAccepted.AlreadyAnswered
    }

    private suspend fun completeAccepted(
        provider: RegisteredDataProvider,
        capability: BankConsentCapability,
        answer: ConsentAnswer,
        accepted: ConsumedAnswer.Accepted,
        permit: OperationPermit,
    ): CompletionOutcome {
        val request = accepted.request
        val reference = request.bankReference
        val bankId = bankOf(reference)?.id
        val completion = try {
            capability.completeConsent(answer, accepted.providerHandle, context(provider, permit, bankId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A8 step 2 of UC-007: a plug-in that throws (ADR-001 §4.0 forbids it) is a completion that did not reach Slussen.
            ConsentCompletion.TransientFailure(FailureKind.ConnectionLost)
        }
        return when (completion) {
            is ConsentCompletion.Completed -> granted(provider, capability, request, completion, permit)
            // A1 of UC-007: the Operator cancelled or the Bank refused; no Bank Consent is stored and the earlier one stays.
            ConsentCompletion.NotCompleted -> CompletionOutcome.NotGranted(reference)
            is ConsentCompletion.NotConfirmed -> when (completion.kind) {
                ConsentFailureKind.AnswerNotUnderstood -> CompletionOutcome.NotAccepted(AnswerNotAccepted.Unreadable)
                else -> {
                    if (completion.kind == ConsentFailureKind.CredentialsRefused) credentials.markRefused(provider.key)
                    CompletionOutcome.NotConfirmed(reference, completion.kind)
                }
            }
            is ConsentCompletion.TransientFailure -> CompletionOutcome.MayHaveBeenGranted(reference, completion.kind, clock.instant())
            is ConsentCompletion.StoppedByGate -> CompletionOutcome.HeldBackByGate(reference, heldBack(provider, bankId, completion.reason))
        }
    }

    /**
     * Steps 8-9 after the Data Provider completed: the Bank's row is found or created, then the Bank Consent and its
     * Bank Session are stored once; a row this attempt created is removed again when the Bank Consent is not stored,
     * whatever stopped it. Then the Offered Bank Accounts of the Bank are kept, and for a renewal the earlier Bank
     * Session is ended (BR-003).
     */
    private suspend fun granted(
        provider: RegisteredDataProvider,
        capability: BankConsentCapability,
        request: StoredConsentRequest,
        completion: ConsentCompletion.Completed,
        permit: OperationPermit,
    ): CompletionOutcome {
        val reference = request.bankReference
        val grantedAt = clock.instant()
        // UC-007 BR-004: The expiry date is the one the Bank or Data Provider reports; one that is not after the grant cannot be read.
        if (!completion.expiresAt.isAfter(grantedAt)) return CompletionOutcome.MayHaveBeenGranted(reference, FailureKind.UnreadableAnswer, grantedAt)
        var created: Long? = null
        try {
            val known = bankOf(reference)
            val bank = known ?: connections.bankNamed(reference).also { created = it.id }
            val earlier = consents.bankConsents().firstOrNull { it.bankId == bank.id && it.dataProviderId == provider.stored.id }
                ?.let { consent -> consents.bankSessions().firstOrNull { it.bankConsentId == consent.id }?.session }
            // UC-007 BR-003: Renewing replaces the earlier Bank Consent of the Bank and its Bank Session.
            val replaced = consents.replaceConsent(request.id, GrantedBankConsent(bank.id, grantedAt, completion.expiresAt, completion.session, grantedAt))
            if (replaced is ReplacedConsent.Refused) {
                created?.let { leftBehind.removeIfUnused(it) }
                return CompletionOutcome.NotStored(reference)
            }
            created = null
            val coverage = storeOrNull { keep(provider, bank, completion.bankAccounts) }
            return CompletionOutcome.Granted(reference, completion.expiresAt, coverage, endEarlier(provider, capability, earlier, bank.id, permit))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { created?.let { removeQuietly(it) } }
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: storage that fails stores nothing of the Bank Consent and leaves no Bank behind.
            created?.let { removeQuietly(it) }
            return CompletionOutcome.NotStored(reference)
        }
    }

    /** Removes the Bank [bankId] this attempt created; when that fails too, the next start removes it ([LeftBehindBanks.run]). */
    private suspend fun removeQuietly(bankId: Long) {
        storeOrNull { leftBehind.removeIfUnused(bankId) }
    }

    // UC-007 BR-003: Once the new Bank Consent is stored, Slussen asks the Data Provider to end the earlier Bank Session; when that fails or is not confirmed, the renewal stands.
    private suspend fun endEarlier(
        provider: RegisteredDataProvider,
        capability: BankConsentCapability,
        earlier: ConsentSession?,
        bankId: Long,
        permit: OperationPermit,
    ): EarlierBankConsent {
        earlier ?: return EarlierBankConsent.None
        val ended = try {
            capability.revoke(earlier, context(provider, permit, bankId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RevokeResult.NotConfirmed
        }
        return if (ended == RevokeResult.Ended) EarlierBankConsent.Ended else EarlierBankConsent.MayStayAtBank
    }

    /**
     * Step 9: the Bank Accounts [delivered] with the Bank Consent of [bank] are kept as the Offered Bank Accounts of that
     * Bank in place of its earlier ones (O-2, UC-009 BR-009), and each one the Operator added is identified by its lasting
     * reference only; anything else waits for the Operator (A5, A6; UC-009 BR-004; UC-013 BR-011, BR-012). No Bank Account
     * is changed here.
     */
    private suspend fun keep(provider: RegisteredDataProvider, bank: StoredBank, delivered: List<DeliveredBankAccount>): Coverage {
        val dataProvider = provider.stored
        val offeredAt = clock.instant()
        // UC-007 BR-009: The Bank Consent covers the Bank Accounts the Data Provider names this Bank for; one it names another Bank for is not kept with it.
        val ofBank = delivered.filter {
            (it.bankReference == null || it.bankReference == bank.name) && it.providerReference.isNotBlank() &&
                it.providerReference.length <= PROVIDER_REFERENCE_MAX_LENGTH
        }
        // UC-009 BR-004: a reference that two delivered Bank Accounts share identifies neither; neither is kept or identified.
        val shared = ofBank.groupingBy { it.providerReference }.eachCount().filterValues { it > 1 }.keys
        // UC-009 BR-004: a lasting reference the Data Provider already offers under another of its Banks would be a guess under this one: it is not kept here, and Slussen stops for it (A6).
        val elsewhere = offered.offered().filter { it.dataProviderId == dataProvider.id && it.bankId != bank.id }.map { it.providerReference }.toSet()
        val offeredElsewhere = ofBank.filter { it.providerReference in elsewhere && it.providerReference !in shared }
        val usable = ofBank.filter { it.providerReference !in shared && it.providerReference !in elsewhere }
        val references = usable.map { it.providerReference }.toSet()
        val leftOut = connections.leftOutBankAccounts().filter { it.dataProviderId == dataProvider.id }.map { it.providerReference }.toSet()
        val kept = usable.map { offeredOf(provider, bank, it, offeredAt, it.providerReference in leftOut) }
        val bankAccounts = connections.bankAccounts()
        val added = bankAccounts.filter { it.dataProviderId == dataProvider.id }
        val proposals = proposedMatchesOf(added, kept, leftOut)
        // UC-009 BR-009: The Offered Bank Accounts of one Bank through the Data Provider are replaced by those its Bank Consent delivered.
        offered.replaceOffered(dataProvider.id, bank.id, kept.map { it.copy(proposedBankAccountId = proposals[it.providerReference]) })

        fun referenceOf(bankAccount: StoredBankAccount): String? =
            if (bankAccount.dataProviderId == dataProvider.id) bankAccount.providerReference else bankAccount.balanceProviderReference
        val ofDataProvider = bankAccounts.filter { !it.retired && (it.dataProviderId == dataProvider.id || it.balanceDataProviderId == dataProvider.id) }
        val addedReferences = ofDataProvider.mapNotNull(::referenceOf).toSet()
        // A6 of UC-007: two added Bank Accounts possible for one covered Bank Account, by their number, stop for both.
        val ambiguous = kept.filter { it.providerReference !in addedReferences && it.providerReference !in leftOut && it.providerReference !in proposals }
            .associate { offer -> offer.providerReference to added.filter { offer.bankAccountFingerprint != null && it.bankAccountFingerprint == offer.bankAccountFingerprint } }
            .filterValues { it.size > 1 }
        // UC-009 BR-004: Slussen uses a Bank Account's transactions only when it identifies the Bank Account with certainty: by the reference its Data Provider gives it, here at the Bank of the Bank Consent.
        val identified = ofDataProvider.filter { referenceOf(it) in references && it.providerBankId == bank.id }
        val notIdentified = (
            ofDataProvider.filter {
                referenceOf(it) in shared || (referenceOf(it) in references && it.providerBankId != bank.id) ||
                    (referenceOf(it) in elsewhere && it.providerBankId == bank.id)
            } + ambiguous.values.flatten()
            ).distinctBy { it.id }
        val elsewhereNotAdded = offeredElsewhere.filter { delivery -> ofDataProvider.none { referenceOf(it) == delivery.providerReference } }
        val proposedIds = proposals.values.toSet()
        val noLongerDelivered = ofDataProvider.filter {
            it.providerBankId == bank.id && referenceOf(it) !in references && it.id !in proposedIds && it !in notIdentified
        }
        return Coverage(
            delivered = ofBank.size,
            identified = identified.map { it.name },
            toConfirm = kept.mapNotNull { offer ->
                val match = proposals[offer.providerReference]?.let { id -> added.firstOrNull { it.id == id } } ?: return@mapNotNull null
                ProposedMatch(offer.name, offer.lastFourDigits, match.name)
            },
            notIdentified = notIdentified.map { it.name } + elsewhereNotAdded.map { it.name.orEmpty() },
            // UC-013 BR-012: every Offered Bank Account that is neither added nor left out is imported by default and to map.
            toMap = kept.count {
                it.providerReference !in leftOut && it.providerReference !in addedReferences && it.providerReference !in proposals &&
                    it.providerReference !in ambiguous
            },
            // UC-013 BR-011: a Left-Out Bank Account stays not imported until the Operator imports it again.
            leftOut = kept.count { it.providerReference in leftOut },
            noLongerDelivered = noLongerDelivered.map { it.name },
        )
    }

    private fun offeredOf(
        provider: RegisteredDataProvider,
        bank: StoredBank,
        delivered: DeliveredBankAccount,
        offeredAt: Instant,
        leftOut: Boolean,
    ): NewOfferedBankAccount = NewOfferedBankAccount(
        bankId = bank.id,
        providerReference = delivered.providerReference,
        bankAccountFingerprint = delivered.number?.let { BankAccountFingerprint.of(provider.stored.sourceKey, bank.name, it) },
        name = delivered.name?.take(NAME_MAX_LENGTH),
        bankAccountKind = delivered.kind?.take(KIND_MAX_LENGTH),
        currency = delivered.currency,
        // UC-013 BR-011: A Left-Out Bank Account is never read: no balance of it is stored.
        balance = if (leftOut) null else balanceOf(delivered.result),
        lastFourDigits = delivered.number?.let { BankAccountFingerprint.lastFourDigits(it) },
        proposedBankAccountId = null,
        offeredAt = offeredAt,
    )

    private fun balanceOf(result: BankAccountResult): Amount? = when (result) {
        is BankAccountResult.Read -> result.balance
        is BankAccountResult.NotRead -> result.balance.takeIf { result.cause == NotReadCause.NotSelected }
        is BankAccountResult.Failed, BankAccountResult.SignInNeeded -> null
    }

    // ---- Abandoning and revoking ------------------------------------------------------------------------------------

    /** A10: the Operator cancels while Slussen waits for the result: the Consent Request [requestId] is deleted, so no answer to it is accepted. */
    suspend fun abandon(requestId: Long): AbandonOutcome = operation<AbandonOutcome>(AbandonOutcome::NotAvailable) {
        // UC-007 BR-002: an abandoned Consent Request is deleted, so that no answer to it can be accepted.
        when (storeOrNull { consents.abandonRequest(requestId) }) {
            true -> AbandonOutcome.Abandoned.also { logged(BankConsentEvent.ConsentRequestAbandoned) }
            false -> AbandonOutcome.NothingToAbandon
            null -> AbandonOutcome.NotStored
        }
    }

    /**
     * A7 steps 3-5, after the Operator confirmed: asks the Data Provider [service] to end the Bank Consent of the Bank
     * [bankId], then deletes it with its Bank Session and every Consent Request of the Bank, whatever the Data Provider
     * answered (BR-007); without credentials or when the gate holds the request back, nothing is sent and it is deleted
     * all the same (A9 step 3).
     */
    suspend fun revoke(service: ServiceKey, bankId: Long): RevokeOutcome {
        val provider = plugins.dataProvider(service)
        val capability = capabilityOf(provider)
        return operation<RevokeOutcome>(RevokeOutcome::NotAvailable) { permit ->
            val consent = consents.bankConsents().firstOrNull { it.bankId == bankId && it.dataProviderId == provider.stored.id }
                ?: return@operation RevokeOutcome.NothingToRevoke
            val bankName = connections.banks().firstOrNull { it.id == bankId }?.name.orEmpty()
            val session = consents.bankSessions().firstOrNull { it.bankConsentId == consent.id }?.session
            permit.workingOn(provider.stored.name)
            var contacted = false
            var endedAtDataProvider = false
            var endedAtBank = false
            var heldBack: HeldBack? = null
            if (session != null && unavailable(provider) == null) {
                val ended = try {
                    capability.revoke(session, context(provider, permit, bankId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A8 step 3 of UC-007: a plug-in that throws (ADR-001 §4.0 forbids it) is an end the Data Provider did not confirm.
                    RevokeResult.NotConfirmed
                }
                when (ended) {
                    RevokeResult.Ended -> { contacted = true; endedAtDataProvider = true; endedAtBank = true }
                    RevokeResult.EndedAtDataProvider -> { contacted = true; endedAtDataProvider = true }
                    // A8 step 3 of UC-007: an end the Data Provider does not confirm is a revocation it did not confirm.
                    RevokeResult.NotConfirmed -> contacted = true
                    is RevokeResult.StoppedByGate -> heldBack = heldBack(provider, bankId, ended.reason)
                }
            }
            // UC-007 BR-007: Revoking deletes the Bank Consent, its Bank Session and every Consent Request of the Bank, whether or not the Data Provider confirms the end.
            storeOrNull { consents.revoke(bankId, provider.stored.id, bankName) } ?: return@operation RevokeOutcome.NotStored(endedAtDataProvider)
            logged(BankConsentEvent.BankConsentRevoked)
            RevokeOutcome.Revoked(bankName, endedAtBank, contacted, heldBack)
        }
    }

    // ---- Reads ------------------------------------------------------------------------------------------------------

    /**
     * ADR-007 §3: whether a Consent Request is unanswered and within its lifetime, so that no Upload Plan is prepared
     * until it is answered, abandoned (A10) or its lifetime has ended. Reads stored data only.
     */
    suspend fun holdsBackUploadPlan(): Boolean = waitingAt(clock.instant()).isNotEmpty()

    private suspend fun waitingAt(now: Instant): List<StoredConsentRequest> =
        consents.consentRequests().filter { !it.answered && !now.isAfter(it.expiresAt) }

    /**
     * BR-008, BR-011: the Bank Consents section of the page of the Data Provider [service], from stored data only: the
     * return address to register, each Bank Consent with its status, expiry and the Bank Accounts it covers (BR-009),
     * and the Consent Requests that wait for their answer.
     */
    suspend fun page(service: ServiceKey): BankConsentsPage {
        val provider = plugins.dataProvider(service)
        val returnAddress = returnAddressOf(provider)
        val now = clock.instant()
        val lead = Duration.ofDays(settings.current().consentWarningDays.toLong())
        val banks = connections.banks().associateBy { it.id }
        val bankAccounts = connections.bankAccounts()
        val dataProviderId = provider.stored.id
        val lines = consents.bankConsents().filter { it.dataProviderId == dataProviderId }.map { consent ->
            val state = when {
                !consent.expiredFrom.isAfter(now) -> BankConsentState.Expired
                !now.isBefore(consent.expiredFrom.minus(lead)) -> BankConsentState.Expiring
                else -> BankConsentState.Valid
            }
            // UC-007 BR-009: A Bank Consent covers every Bank Account its Data Provider reads and names this Bank for, whatever Bank the Operator gave it.
            val covered = bankAccounts.filter {
                !it.retired && it.providerBankId == consent.bankId && (it.dataProviderId == dataProviderId || it.balanceDataProviderId == dataProviderId)
            }
            BankConsentLine(
                consent.bankId, banks[consent.bankId]?.name.orEmpty(), state, consent.expiresAt, consent.expiredFrom, consent.refusedByBank,
                covered.map { it.name },
            )
        }
        val waiting = waitingAt(now).filter { it.dataProviderId == dataProviderId }.map { WaitingRequest(it.id, it.bankReference, it.expiresAt) }
        return BankConsentsPage(returnAddress, returnAddress == applicationReturnAddress, lines, waiting)
    }

    /** Runs one storage operation and gives null when the stored data throws (NFR-023); cancellation is passed on. */
    private suspend fun <T : Any> storeOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

    private companion object {
        /** Stands for "no Consent Request has this digest" inside [storeOrNull], which gives null for a failure. */
        val NONE = StoredConsentRequest(0, "", 0, Instant.EPOCH, Instant.EPOCH, answered = true)

        /** An ISO 3166 two-letter country code (BR-010). */
        val COUNTRY = Regex("[A-Z]{2}")

        /** `BANK.name` and `CONSENT_REQUEST.bank_reference` (entity model): 100 characters. */
        const val BANK_REFERENCE_MAX_LENGTH = 100

        /** `OFFERED_BANK_ACCOUNT.provider_reference` (entity model): 200 characters. */
        const val PROVIDER_REFERENCE_MAX_LENGTH = 200

        /** `OFFERED_BANK_ACCOUNT.name` (entity model). */
        const val NAME_MAX_LENGTH = 100

        /** `OFFERED_BANK_ACCOUNT.bank_account_kind` (entity model). */
        const val KIND_MAX_LENGTH = 50
    }
}
