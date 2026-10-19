package slussen.core.connection

import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.identity.GeneratedIdentifiers
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.NewBankAccount
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.RandomSource
import slussen.core.port.ServeOutcome
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAccount
import slussen.core.port.StoredOfferedBankAccount
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.LocalDate

/**
 * Connect Budgets and Bank Accounts (UC-013), `:core` `connection` (ADR-001 §7): the served Budgets; the two pairing
 * steps of S-38, choosing what to bring in with the numbers stated before the rest is left out (BR-011, BR-012), then one
 * Offered Bank Account at a time with the proposed Budget Account (BR-013); pairing it with a free Budget Account of the
 * same currency, the generated identifier, the proposed name and the Start Date; Manually Kept Accounts (BR-014),
 * Left-Out Bank Accounts brought back (A10); and Bank Accounts of a File Format.
 */
class BudgetsAndBankAccounts(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val store: ConnectionStore,
    private val offered: OfferedBankAccountStore,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
    private val random: RandomSource,
    private val clock: Clock,
) {
    // UC-013 BR-008: Setting up starts with the service credentials of the Budget App and of the Data Providers.
    suspend fun credentialSteps(): List<CredentialStep> = plugins.serviceKeys.map { service ->
        CredentialStep(service, plugins.nameOf(service), service == ServiceKey.budgetApp, credentials.status(service))
    }

    suspend fun readBudgets(): BudgetsRead {
        // UC-013 BR-008: Budgets are chosen once the Budget App's credentials are accepted; until then it is not contacted.
        val credential = credentials.stored(ServiceKey.budgetApp) ?: return BudgetsRead.BudgetAppCredentialsMissing
        // UC-014 BR-005: A service that refused Slussen's credentials is not contacted until new ones are accepted.
        if (credentials.status(ServiceKey.budgetApp) == CredentialStatus.Refused) return BudgetsRead.CredentialsRefused
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) { permit ->
            val context = credentials.context(ServiceKey.budgetApp, permit, credential)
            when (val read = plugins.budgetApp.reader.readBudgets(context)) {
                is ReadResult.Answered -> {
                    val served = store.budgets().filter { it.served }.map { it.budgetAppReference }.toSet()
                    BudgetsRead.Listed(
                        read.value.map { budget ->
                            ListedBudget(
                                budgetAppReference = budget.reference,
                                name = budget.name,
                                currency = budget.currency,
                                served = budget.reference in served,
                                budgetAccounts = budget.budgetAccounts.map {
                                    ListedBudgetAccount(it.reference, it.name, it.balance)
                                },
                            )
                        },
                    )
                }
                ReadResult.CredentialsRefused -> {
                    credentials.markRefused(ServiceKey.budgetApp)
                    BudgetsRead.CredentialsRefused
                }
                is ReadResult.TransientFailure -> BudgetsRead.CouldNotRead(read.kind)
                is ReadResult.StoppedByGate -> BudgetsRead.CouldNotRead(null)
            }
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> BudgetsRead.Unavailable(result.reason)
        }
    }

    suspend fun chooseBudgets(listed: List<ListedBudget>, chosen: Set<String>): BudgetChoice {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            stillMapped(chosen)?.let { return@run it }
            val toServe = listed.map { budget ->
                BudgetToServe(
                    budget.budgetAppReference,
                    budget.name,
                    budget.currency,
                    budget.budgetAccounts.map { BudgetAccountToStore(it.budgetAppReference, it.name, it.balance) },
                )
            }
            // UC-013 BR-001: Slussen serves only the Budgets the Operator chose from those the Budget App lists.
            when (val outcome = store.serveBudgets(toServe, chosen, clock.instant())) {
                ServeOutcome.Served -> {
                    val names = listed.filter { it.budgetAppReference in chosen }.joinToString(", ") { it.name }
                    journal.record(JournalEventKind.ConfigurationChange, "Budgets in use: $names")
                    BudgetChoice.Stored
                }
                is ServeOutcome.StillMapped -> BudgetChoice.StillMapped(outcome.budgetName, outcome.bankAccountNames)
            }
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> BudgetChoice.Unavailable(result.reason)
        }
    }

    private suspend fun stillMapped(chosen: Set<String>): BudgetChoice.StillMapped? {
        val bankAccounts = store.bankAccounts()
        store.budgets().filter { it.served && it.budgetAppReference !in chosen }.forEach { budget ->
            val accountIds = budget.budgetAccounts.map { it.id }.toSet()
            val mapped = bankAccounts.filter { it.mapping?.budgetAccountId in accountIds }
            // UC-013 BR-001: A Budget stops being served only when no Bank Account is mapped into it.
            if (mapped.isNotEmpty()) return BudgetChoice.StillMapped(budget.name, mapped.map { it.name })
        }
        return null
    }

    private val apart = LeftOutAndKept(plugins, offered, journal)

    /**
     * Step 5: for each Source the Offered Bank Accounts that are neither added nor left out, grouped by Bank through
     * their Bank's name (BR-002, BR-012), the proposed matches of A6 apart, the free Budget Accounts, and the Left-Out
     * Bank Accounts (BR-011).
     */
    suspend fun pairing(): Pairing {
        val budgets = store.budgets()
        val bankAccounts = store.bankAccounts()
        val banks = store.banks().associateBy { it.id }
        val offeredAll = offered.offered()
        val leftOut = store.leftOutBankAccounts()
        val leftOutKeys = leftOut.map { it.dataProviderId to it.providerReference }.toSet()
        val free = freeBudgetAccounts(budgets, bankAccounts)
        val sources = plugins.dataProviders.mapNotNull { provider ->
            val ofProvider = offeredAll.filter { it.dataProviderId == provider.stored.id }
            val hasCredentials = credentials.status(provider.key) != CredentialStatus.Missing
            if (!hasCredentials && ofProvider.isEmpty()) return@mapNotNull null
            // UC-013 BR-011: A Left-Out Bank Account is not offered for pairing and never proposed as the match of an added Bank Account.
            val undecided = ofProvider.filter { !isAdded(it, bankAccounts) && (it.dataProviderId to it.providerReference) !in leftOutKeys }
            // UC-009 BR-004: An Offered Bank Account that is probably an added Bank Account is proposed for confirmation, never guessed.
            val proposed = undecided.filter { it.proposedBankAccountId != null }
            val toAdd = undecided.filter { it !in proposed }
            SourcePairing(
                dataProviderId = provider.stored.id,
                name = provider.stored.name,
                offered = toAdd.map { view(it, banks[it.bankId]?.name) },
                proposedMatches = proposed.mapNotNull { offer ->
                    bankAccounts.firstOrNull { it.id == offer.proposedBankAccountId }?.let {
                        ProposedMatch(view(offer, banks[offer.bankId]?.name), it.id, it.name)
                    }
                },
                remedy = when {
                    ofProvider.isNotEmpty() -> null
                    provider.stored.needsBankConsent -> OfferRemedy.GrantBankConsent
                    else -> OfferRemedy.StartFetch
                },
            )
        }
        val stored = offeredAll.any { offer -> sources.any { it.dataProviderId == offer.dataProviderId } }
        return Pairing(
            sources = sources,
            freeBudgetAccounts = free,
            leftOut = leftOut.map(apart::view),
            nothingToBringIn = stored && sources.all { it.offered.isEmpty() },
        )
    }

    /** The free Budget Accounts: of a served Budget, receiving through no Account Mapping, not a Manually Kept Account. */
    private fun freeBudgetAccounts(budgets: List<StoredBudget>, bankAccounts: List<StoredBankAccount>): List<FreeBudgetAccount> {
        val mapped = bankAccounts.mapNotNull { it.mapping?.budgetAccountId }.toSet()
        // UC-013 BR-002: Only Budget Accounts of served Budgets that receive through no Account Mapping and are not Manually Kept Accounts are offered for pairing.
        return budgets.filter { it.served }.flatMap { budget ->
            budget.budgetAccounts.filter { !it.manuallyKept && it.id !in mapped }.map {
                FreeBudgetAccount(it.id, budget.name, budget.currency, it.name, it.balance)
            }
        }
    }

    /** An Offered Bank Account that is an added Bank Account, or reports the balance of one (`LEFT_OUT_BANK_ACCOUNT` constraints). */
    private fun isAdded(offer: StoredOfferedBankAccount, bankAccounts: List<StoredBankAccount>): Boolean = bankAccounts.any {
        (it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference) ||
            (it.balanceDataProviderId == offer.dataProviderId && it.balanceProviderReference == offer.providerReference)
    }

    /** Step 7, A12: how many of the shown Offered Bank Accounts are brought in and how many left out; nothing is stored. */
    suspend fun bringInStatement(chosen: Set<Long>): BringInStatement {
        val shown = pairing().sources.flatMap { it.offered }
        // UC-013 BR-012: Before anything is left out, Slussen states how many are brought in and how many are left out.
        return BringInStatement(shown.filter { it.offeredBankAccountId in chosen }, shown.filter { it.offeredBankAccountId !in chosen })
    }

    /**
     * Steps 8-9, A12: the Operator confirmed the numbers stated for the [shown] Offered Bank Accounts with [chosen] brought
     * in. Each shown one not chosen becomes a Left-Out Bank Account, all in one O-15 transaction, recorded by name; the
     * chosen ones are taken one at a time in the order shown.
     */
    suspend fun bringIn(shown: List<Long>, chosen: Set<Long>): BringIn {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            val sources = pairing().sources
            val current = sources.flatMap { source -> source.offered.map { it to source } }
            // UC-013 BR-012: Nothing is left out unless the Operator confirmed the numbers stated for exactly the Offered Bank Accounts shown.
            if (current.map { it.first.offeredBankAccountId }.toSet() != shown.toSet()) return@run BringIn.ShownChanged
            val stored = offered.offered().associateBy { it.id }
            val now = clock.instant()
            val leftOut = current.filter { (view, _) -> view.offeredBankAccountId !in chosen }.map { (view, source) ->
                val offer = stored.getValue(view.offeredBankAccountId)
                NewLeftOutBankAccount(offer.dataProviderId, offer.providerReference, proposedName(offer, source.name), now)
            }
            // UC-013 BR-011: Each shown Offered Bank Account not chosen is remembered by its Data Provider and reference as a Left-Out Bank Account.
            if (leftOut.isNotEmpty() && !store.leaveOut(leftOut)) return@run BringIn.ShownChanged
            leftOut.forEach { apart.recordLeftOut(it.name, it.dataProviderId) }
            BringIn.Confirmed(
                queue = current.map { it.first.offeredBankAccountId }.filter { it in chosen },
                leftOut = leftOut.size,
            )
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> BringIn.Unavailable(result.reason)
        }
    }

    /**
     * Step 10: the card of an Offered Bank Account that is offered for pairing, or null when it is added, left out or no
     * longer stored. Nothing is stored.
     */
    suspend fun card(offeredBankAccountId: Long): PairingCard? {
        val pairing = pairing()
        val source = pairing.sources.firstOrNull { s -> s.offered.any { it.offeredBankAccountId == offeredBankAccountId } } ?: return null
        val offer = offered.offered().single { it.id == offeredBankAccountId }
        val free = pairing.freeBudgetAccounts
        // UC-013 BR-013: Listed are the free Budget Accounts of its currency, or every free one when it states none; no balance or name is compared.
        val listed = offer.currency?.let { currency -> free.filter { it.budgetCurrency == currency } } ?: free
        val banks = knownBanks()
        return PairingCard(
            offered = source.offered.single { it.offeredBankAccountId == offeredBankAccountId },
            sourceName = source.name,
            listed = listed,
            // UC-013 BR-013: A Budget Account is proposed only when it is the only one listed.
            proposedBudgetAccountId = listed.singleOrNull()?.budgetAccountId,
            allFree = free,
            proposedName = proposedName(offer, source.name),
            proposedType = typeOf(offer.bankAccountKind),
            // UC-013 BR-009: The Bank proposed is the one the Data Provider names for the Bank Account, none when it names none.
            proposedBank = banks.firstOrNull { it.bankId == offer.bankId },
            types = BankAccountType.entries,
            knownBanks = banks,
            currency = offer.currency,
        )
    }

    /** A9: marks a free Budget Account as a Manually Kept Account, recorded in the Decision Journal (BR-014). */
    suspend fun markManuallyKept(budgetAccountId: Long): KeepOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            apart.setManuallyKept(store.budgets(), budgetAccountId, manuallyKept = true, store::setManuallyKept)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> KeepOutcome.Unavailable(result.reason)
        }
    }

    /** A10: brings a Left-Out Bank Account back (BR-011). */
    suspend fun bringBack(leftOutBankAccountId: Long): BroughtBack {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            apart.bringBack(leftOutBankAccountId, store::bringBack)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> BroughtBack.Unavailable(result.reason)
        }
    }

    /** A4: the Offered Bank Accounts that can report the balance of a Bank Account of a File Format, also the Left-Out ones (BR-006). */
    suspend fun balanceSources(): List<BalanceSource> {
        val banks = store.banks().associateBy { it.id }
        val leftOutKeys = store.leftOutBankAccounts().map { it.dataProviderId to it.providerReference }.toSet()
        // UC-013 BR-006: The Offered Bank Accounts offered for the balance include the Left-Out ones.
        return offered.offered().map { offer ->
            BalanceSource(view(offer, banks[offer.bankId]?.name), apart.sourceName(offer.dataProviderId), (offer.dataProviderId to offer.providerReference) in leftOutKeys)
        }
    }

    // UC-013 BR-002: Each Offered Bank Account is shown with its name or product, kind, currency, last four digits and balance.
    private fun view(offer: StoredOfferedBankAccount, bankName: String?) = OfferedForPairing(
        offeredBankAccountId = offer.id,
        nameOrProduct = offer.name,
        kind = offer.bankAccountKind,
        currency = offer.currency,
        lastFourDigits = offer.lastFourDigits,
        balance = offer.balance,
        bankName = bankName,
    )

    /** The Banks Slussen already knows, to choose from at step 11 and in A4 (BR-009). */
    suspend fun knownBanks(): List<KnownBank> = store.banks().map { KnownBank(it.id, it.name) }

    /** An Offered Bank Account that is offered for adding (step 5, A6, BR-011) with the Budget Account chosen for it (step 11). */
    private suspend fun paired(offeredBankAccountId: Long, budgetAccountId: Long): Pair<StoredOfferedBankAccount, Pair<StoredBudget, StoredBudgetAccount>>? {
        val offeredForAdding = pairing().sources.flatMap { it.offered }.any { it.offeredBankAccountId == offeredBankAccountId }
        val offer = offered.offered().firstOrNull { it.id == offeredBankAccountId }
        if (offer == null || !offeredForAdding) return null
        val mappedInto = budgetAccountFor(budgetAccountId) ?: return null
        return offer to mappedInto
    }

    /** Steps 10, 12: checks the pairing (step 12, A5) and proposes the name, Bank Account type and Bank; stores nothing. */
    suspend fun propose(offeredBankAccountId: Long, budgetAccountId: Long): PairOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            val (offer, mappedInto) = paired(offeredBankAccountId, budgetAccountId) ?: return@run PairOutcome.NotOffered
            refusal(mappedInto, offer.currency, offer.name ?: offer.bankAccountKind.orEmpty())?.let { return@run it }
            val provider = plugins.dataProviders.single { it.stored.id == offer.dataProviderId }
            val banks = knownBanks()
            PairOutcome.Proposed(
                AdditionProposal(
                    offeredBankAccountId = offer.id,
                    budgetAccountId = budgetAccountId,
                    proposedName = proposedName(offer, provider.stored.name),
                    proposedType = typeOf(offer.bankAccountKind),
                    // UC-013 BR-009: The Bank proposed is the one the Data Provider names for the Bank Account, none when it names none.
                    proposedBank = banks.firstOrNull { it.bankId == offer.bankId },
                    types = BankAccountType.entries,
                    knownBanks = banks,
                    // UC-013 BR-010: When the Offered Bank Account states no currency, the currency of the chosen Budget Account is proposed for the Operator to state.
                    proposedCurrency = offer.currency ?: mappedInto.first.currency,
                    currencyStatedBySource = offer.currency != null,
                ),
            )
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> PairOutcome.Unavailable(result.reason)
        }
    }

    /**
     * Steps 11-13: adds the Bank Account with the Budget Account, name, Bank Account type, Bank and optional Start Date
     * the Operator confirmed, and the [currency] the Operator states when the Offered Bank Account states none (BR-010).
     */
    suspend fun add(
        offeredBankAccountId: Long,
        budgetAccountId: Long,
        name: String,
        type: BankAccountType?,
        bank: BankChoice?,
        startDate: LocalDate?,
        currency: CurrencyCode? = null,
    ): PairOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            // UC-013 BR-004: The Operator accepts or edits the proposed name; the name never identifies the Bank Account.
            val accepted = validName(name) ?: return@run PairOutcome.NameNotValid
            confirmedTypeAndBank(type, bank)?.let { return@run it }
            val (offer, mappedInto) = paired(offeredBankAccountId, budgetAccountId) ?: return@run PairOutcome.NotOffered
            // UC-013 BR-010: The currency is the one the Offered Bank Account states, or else the one the Operator states; no Bank Account is added without one.
            val bankAccountCurrency = offer.currency ?: currency ?: return@run PairOutcome.CurrencyNotStated
            refusal(mappedInto, bankAccountCurrency, accepted)?.let { return@run it }
            add(
                NewBankAccount(
                    generatedIdentifier = GeneratedIdentifiers.bankAccountIdentifier(random),
                    bank = requireNotNull(bank),
                    // UC-013 BR-009: The Bank the Data Provider names for the Bank Account is kept apart from the Operator's Bank; it decides the Bank Consent that covers it.
                    providerBankId = offer.bankId,
                    dataProviderId = offer.dataProviderId,
                    fileFormatId = null,
                    balanceDataProviderId = null,
                    balanceProviderReference = null,
                    name = accepted,
                    currency = bankAccountCurrency,
                    type = requireNotNull(type),
                    // UC-013 BR-003: Slussen records how the Source identifies the Bank Account.
                    providerReference = offer.providerReference,
                    bankAccountFingerprint = offer.bankAccountFingerprint,
                    identifiedAt = clock.instant(),
                    // UC-013 BR-005: The Start Date is optional and set in the app.
                    startDate = startDate,
                    budgetAccountId = budgetAccountId,
                ),
                mappedInto,
            )
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> PairOutcome.Unavailable(result.reason)
        }
    }

    /**
     * Null when the Operator confirmed or chose a Bank Account type and a Bank: a known one, or a new one with a name;
     * otherwise why the Bank Account is not added.
     */
    private suspend fun confirmedTypeAndBank(type: BankAccountType?, bank: BankChoice?): PairOutcome? {
        // UC-013 BR-009: No Bank Account is added without a Bank Account type and a Bank.
        if (type == null) return PairOutcome.TypeNotChosen
        val chosen = when (bank) {
            null -> false
            is BankChoice.Known -> store.banks().any { it.id == bank.bankId }
            is BankChoice.Named -> bank.name.isNotBlank() && bank.name.trim().length <= BANK_NAME_MAX_LENGTH
        }
        return if (chosen) null else PairOutcome.BankNotChosen
    }

    /** A Budget Account of a served Budget that is not a Manually Kept Account, with its Budget (BR-002, BR-014). */
    private suspend fun budgetAccountFor(budgetAccountId: Long): Pair<StoredBudget, StoredBudgetAccount>? {
        val budget = store.budgets().firstOrNull { b -> b.served && b.budgetAccounts.any { it.id == budgetAccountId } }
            ?: return null
        val account = budget.budgetAccounts.single { it.id == budgetAccountId }
        return if (account.manuallyKept) null else budget to account
    }

    /** Step 12: the Budget Account receives through no other Account Mapping and its Budget has the Bank Account's currency. */
    private suspend fun refusal(
        mappedInto: Pair<StoredBudget, StoredBudgetAccount>,
        currency: CurrencyCode?,
        bankAccountName: String,
    ): PairOutcome? {
        val (budget, account) = mappedInto
        // UC-013 BR-002: A Budget Account that receives through an Account Mapping is not offered for pairing.
        val mappedBy = store.bankAccounts().firstOrNull { it.mapping?.budgetAccountId == account.id }
        if (mappedBy != null) return PairOutcome.BudgetAccountAlreadyMapped(account.name, mappedBy.name)
        // UC-013 BR-010: A currency the Offered Bank Account does not state is checked once the Operator states it at step 11.
        if (currency == null) return null
        // UC-003 BR-013: No amount is ever converted; a Bank Account is paired only with a Budget Account of its own currency.
        if (currency != budget.currency) {
            return PairOutcome.CurrencyDiffers(account.name, budget.currency, bankAccountName, currency)
        }
        return null
    }

    /** Step 13: one O-15 transaction, then the addition recorded in the Decision Journal. */
    private suspend fun add(bankAccount: NewBankAccount, mappedInto: Pair<StoredBudget, StoredBudgetAccount>): PairOutcome {
        val named = when (val bank = bankAccount.bank) {
            is BankChoice.Known -> bankAccount
            is BankChoice.Named -> bankAccount.copy(bank = BankChoice.Named(bank.name.trim()))
        }
        return when (val outcome = store.addBankAccount(named)) {
            is AddOutcome.Added -> {
                val bankName = store.banks().single { it.id == store.bankAccounts().single { a -> a.id == outcome.bankAccountId }.bankId }.name
                journal.record(
                    JournalEventKind.AccountMappingChange,
                    "Bank Account ${named.name} added, ${named.type.text} at $bankName, mapped to Budget Account " +
                        "${mappedInto.second.name} of Budget ${mappedInto.first.name}" +
                        (named.startDate?.let { ", Start Date $it" } ?: ", no Start Date"),
                )
                PairOutcome.Added(outcome.bankAccountId, named.generatedIdentifier, named.name)
            }
            is AddOutcome.BudgetAccountAlreadyMapped ->
                PairOutcome.BudgetAccountAlreadyMapped(mappedInto.second.name, outcome.bankAccountName)
            is AddOutcome.CurrencyDiffers ->
                PairOutcome.CurrencyDiffers(mappedInto.second.name, outcome.budgetCurrency, named.name, named.currency)
            AddOutcome.LeftOut, AddOutcome.ManuallyKept -> PairOutcome.NotOffered
        }
    }

    // UC-013 BR-004: The proposed name is the Bank Account's name or product with the last four digits of its number.
    private fun proposedName(offer: StoredOfferedBankAccount, sourceName: String): String {
        val described = offer.name ?: offer.bankAccountKind ?: sourceName
        val digits = offer.lastFourDigits?.let { "••$it" }
        return listOfNotNull(described, digits).joinToString(" ").take(NAME_MAX_LENGTH)
    }

    // UC-013 BR-009: The type proposed comes from the kind the Data Provider gives: credit card, savings or checking, else Other.
    private fun typeOf(kind: String?): BankAccountType {
        val text = kind.orEmpty().lowercase()
        return when {
            "credit" in text -> BankAccountType.CreditCard
            "saving" in text -> BankAccountType.Savings
            "checking" in text || "current" in text -> BankAccountType.Checking
            else -> BankAccountType.Other
        }
    }

    fun fileFormats(): List<FileFormatChoice> = plugins.fileFormats.map { FileFormatChoice(it.id, it.name) }

    /** A4: a Bank Account of a File Format, with the File Format, Bank Account type, Bank and name the Operator chose (BR-006, BR-009). */
    suspend fun addFromTransactionFiles(
        fileFormatId: Long,
        type: BankAccountType?,
        bank: BankChoice?,
        balanceOfferedBankAccountId: Long?,
        budgetAccountId: Long,
        name: String,
        startDate: LocalDate?,
    ): PairOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            // UC-013 BR-006: Slussen proposes no name, Bank Account type or Bank from a file; the Operator enters the name and chooses the others.
            val entered = validName(name) ?: return@run PairOutcome.NameNotValid
            confirmedTypeAndBank(type, bank)?.let { return@run it }
            if (plugins.fileFormats.none { it.id == fileFormatId }) return@run PairOutcome.NotOffered
            val mappedInto = budgetAccountFor(budgetAccountId) ?: return@run PairOutcome.NotOffered
            val balanceOffer = balanceOfferedBankAccountId?.let { id ->
                offered.offered().firstOrNull { it.id == id } ?: return@run PairOutcome.NotOffered
            }
            val currency = balanceOffer?.currency ?: mappedInto.first.currency
            refusal(mappedInto, currency, entered)?.let { return@run it }
            val leftOutBalance = balanceOffer?.let { offer ->
                store.leftOutBankAccounts().firstOrNull { it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference }
            }
            val added = add(
                NewBankAccount(
                    generatedIdentifier = GeneratedIdentifiers.bankAccountIdentifier(random),
                    bank = requireNotNull(bank),
                    // UC-013 BR-009: For the balance, the Bank the balance Data Provider names is kept apart from the Operator's Bank.
                    providerBankId = balanceOffer?.bankId,
                    dataProviderId = null,
                    fileFormatId = fileFormatId,
                    // UC-013 BR-006: A Bank Account of a File Format takes its balance from the Offered Bank Account the Operator chose.
                    balanceDataProviderId = balanceOffer?.dataProviderId,
                    balanceProviderReference = balanceOffer?.providerReference,
                    name = entered,
                    currency = currency,
                    type = requireNotNull(type),
                    providerReference = null,
                    bankAccountFingerprint = null,
                    identifiedAt = clock.instant(),
                    startDate = startDate,
                    budgetAccountId = budgetAccountId,
                ),
                mappedInto,
            )
            // UC-013 BR-006: Choosing a Left-Out Bank Account for the balance brings it back, recorded in the Decision Journal.
            if (added is PairOutcome.Added && leftOutBalance != null) apart.recordBroughtBack(leftOutBalance.name, leftOutBalance.dataProviderId)
            added
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> PairOutcome.Unavailable(result.reason)
        }
    }

    private fun validName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && it.length <= NAME_MAX_LENGTH }

    suspend fun addedBankAccounts(): List<AddedBankAccount> {
        val banks = store.banks().associateBy { it.id }
        val accounts = store.budgets().flatMap { b -> b.budgetAccounts.map { it.id to (b to it) } }.toMap()
        return store.bankAccounts().map { bankAccount ->
            val mappedInto = bankAccount.mapping?.let { accounts[it.budgetAccountId] }
            val sourceName = bankAccount.dataProviderId
                ?.let { id -> plugins.dataProviders.single { it.stored.id == id }.stored.name }
                ?: plugins.fileFormats.single { it.id == bankAccount.fileFormatId }.name
            AddedBankAccount(
                bankAccountId = bankAccount.id,
                name = bankAccount.name,
                bankName = banks[bankAccount.bankId]?.name.orEmpty(),
                sourceName = sourceName,
                type = bankAccount.type,
                budgetName = mappedInto?.first?.name,
                budgetAccountName = mappedInto?.second?.name,
                startDate = bankAccount.startDate,
                retired = bankAccount.retired,
            )
        }
    }

    private companion object {
        /** `BANK_ACCOUNT.name` holds at most 100 characters (entity model). */
        const val NAME_MAX_LENGTH = 100

        /** `BANK.name` holds at most 100 characters (entity model). */
        const val BANK_NAME_MAX_LENGTH = 100
    }
}
