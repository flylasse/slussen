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
 * Connect Budgets and Bank Accounts (UC-013), `:core` `connection` (ADR-001 §7): the served Budgets and, under each, its
 * Budget Accounts with the switch "Receives from Slussen" (BR-014, A9); each Data Provider's Offered Bank Accounts,
 * imported by default, a Left-Out Bank Account recorded at once when its import is switched off and brought back when it
 * is switched on (BR-011, BR-012, A10); the Bank Accounts to map, one at a time with the proposed Budget Account
 * (BR-013); pairing with a free Budget Account of the same currency, the generated identifier, the proposed name and the
 * Start Date; and Bank Accounts of a File Format, added and mapped on its page (A4, BR-006).
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
    private val reads = ConnectionReads(plugins, store, offered)

    /** Step 4, BR-014: each served Budget with its Budget Accounts, each with "Receives from Slussen" or its Account Mapping. */
    suspend fun servedBudgetAccounts(): List<ServedBudgetOnPage> {
        val snapshot = reads.snapshot()
        val mappedFrom = snapshot.bankAccounts.mapNotNull { bankAccount -> bankAccount.mapping?.let { it.budgetAccountId to bankAccount.name } }.toMap()
        return snapshot.budgets.filter { it.served }.map { budget ->
            ServedBudgetOnPage(
                budgetId = budget.id,
                name = budget.name,
                currency = budget.currency,
                budgetAccounts = budget.budgetAccounts.map { account ->
                    val mapping = mappedFrom[account.id]
                    BudgetAccountOnPage(
                        budgetAccountId = account.id,
                        name = account.name,
                        balance = account.balance,
                        // UC-013 BR-014: A Budget Account that receives through an Account Mapping shows it instead of a switch; every other one receives from Slussen unless it is a Manually Kept Account.
                        receivesFromSlussen = if (mapping != null) null else !account.manuallyKept,
                        mappedFrom = mapping,
                    )
                },
            )
        }
    }

    /** A9, BR-014: "Receives from Slussen" switched off marks the Budget Account as a Manually Kept Account; on unmarks it. */
    suspend fun setReceivesFromSlussen(budgetAccountId: Long, receives: Boolean): KeepOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            // UC-013 BR-014: Switched off, the Budget Account is marked as a Manually Kept Account; switched on again, it is unmarked and offered for pairing again.
            apart.setManuallyKept(store.budgets(), budgetAccountId, manuallyKept = !receives, store::setManuallyKept)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> KeepOutcome.Unavailable(result.reason)
        }
    }

    /**
     * Steps 7-8, BR-012: for each Source the Bank Accounts to map, imported by default and neither added nor left out,
     * the proposed matches of A6 apart, the free Budget Accounts, and the Left-Out Bank Accounts (BR-011).
     */
    suspend fun pairing(): Pairing {
        val snapshot = reads.snapshot()
        val sources = plugins.dataProviders.mapNotNull { provider ->
            val ofProvider = snapshot.offered.filter { it.dataProviderId == provider.stored.id }
            val hasCredentials = credentials.status(provider.key) != CredentialStatus.Missing
            if (!hasCredentials && ofProvider.isEmpty()) return@mapNotNull null
            val states = ofProvider.map { it to reads.stateOf(it, snapshot) }
            SourcePairing(
                dataProviderId = provider.stored.id,
                name = provider.stored.name,
                offered = states.filter { it.second == ImportState.ToMap }.map { reads.view(it.first, snapshot) },
                proposedMatches = states.mapNotNull { (offer, state) ->
                    (state as? ImportState.ProbablyAdded)?.let { ProposedMatch(reads.view(offer, snapshot), it.bankAccountId, it.bankAccountName) }
                },
                remedy = remedyOf(provider.stored.needsBankConsent, ofProvider.isEmpty()),
            )
        }
        return Pairing(
            sources = sources,
            freeBudgetAccounts = reads.freeBudgetAccounts(snapshot),
            leftOut = snapshot.leftOut.map(reads::leftOutView),
        )
    }

    /** A3: a Fetch, or for a Data Provider that needs one a Bank Consent, while it offers nothing. */
    private fun remedyOf(needsBankConsent: Boolean, nothingStored: Boolean): OfferRemedy? = when {
        !nothingStored -> null
        needsBankConsent -> OfferRemedy.GrantBankConsent
        else -> OfferRemedy.StartFetch
    }

    /** Step 8, BR-012, UC-009 BR-014: the Bank Accounts to map of every Data Provider, one line each. */
    suspend fun toMap(): List<BankAccountToMap> = reads.toMap(reads.snapshot())

    /** Step 5, A3, A6, A11, BR-012: the Offered Bank Accounts of one Data Provider with how each stands. */
    suspend fun offeredOf(dataProviderId: Long): OfferedOfProvider {
        val provider = plugins.dataProviders.single { it.stored.id == dataProviderId }
        val snapshot = reads.snapshot()
        val ofProvider = snapshot.offered.filter { it.dataProviderId == dataProviderId }
        val shown = ofProvider.map { OfferedOnPage(reads.view(it, snapshot), reads.stateOf(it, snapshot)) }
        val delivered = ofProvider.map { it.providerReference }.toSet()
        return OfferedOfProvider(
            dataProviderId = dataProviderId,
            name = provider.stored.name,
            offered = shown,
            // UC-013 BR-011: A Left-Out Bank Account is shown as not imported among its Data Provider's Offered Bank Accounts, also when the latest delivery does not hold it (A10 step 2).
            leftOutNotDelivered = snapshot.leftOut.filter { it.dataProviderId == dataProviderId && it.providerReference !in delivered }.map(reads::leftOutView),
            remedy = remedyOf(provider.stored.needsBankConsent, ofProvider.isEmpty()),
            nothingNew = shown.isNotEmpty() && shown.none { it.state == ImportState.ToMap },
        )
    }

    /** Steps 6-7, BR-011, BR-012: the import of one Offered Bank Account switched off makes it a Left-Out Bank Account at once. */
    suspend fun switchImportOff(offeredBankAccountId: Long): ImportSwitchedOff {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            val snapshot = reads.snapshot()
            val offer = snapshot.offered.firstOrNull { it.id == offeredBankAccountId } ?: return@run ImportSwitchedOff.NoSwitch
            // UC-013 BR-012: Only an imported Offered Bank Account has a switch that leaves it out; an added one, a proposed match, a balance source or one already left out has none.
            if (reads.stateOf(offer, snapshot) != ImportState.ToMap) return@run ImportSwitchedOff.NoSwitch
            val name = proposedName(offer, reads.sourceName(offer.dataProviderId))
            // UC-013 BR-012: Switching the import off makes it a Left-Out Bank Account at once, with no confirmation and no statement of numbers.
            val stored = store.leaveOut(listOf(NewLeftOutBankAccount(offer.dataProviderId, offer.providerReference, name, clock.instant())))
            if (!stored) return@run ImportSwitchedOff.NoSwitch
            // UC-013 BR-011: A Left-Out Bank Account is remembered by its Data Provider and reference and recorded by its name, never by the reference.
            apart.recordLeftOut(name, offer.dataProviderId)
            val leftOut = store.leftOutBankAccounts().single { it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference }
            ImportSwitchedOff.LeftOut(leftOut.id, name)
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> ImportSwitchedOff.Unavailable(result.reason)
        }
    }

    /** Step 10: the free Budget Accounts of the served Budgets (BR-002, BR-013), also for a File Format's Bank Account (A4). */
    suspend fun freeBudgetAccounts(): List<FreeBudgetAccount> = reads.freeBudgetAccounts(reads.snapshot())

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

    /** A10: the import of a Left-Out Bank Account switched on again brings it back (BR-011). */
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
        val snapshot = reads.snapshot()
        val leftOutKeys = snapshot.leftOut.map { it.dataProviderId to it.providerReference }.toSet()
        // UC-013 BR-006: The Offered Bank Accounts offered for the balance include the Left-Out ones.
        return snapshot.offered.map { offer ->
            BalanceSource(reads.view(offer, snapshot), reads.sourceName(offer.dataProviderId), (offer.dataProviderId to offer.providerReference) in leftOutKeys)
        }
    }

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

    // UC-013 BR-004: The proposed name is the name or product, followed by the last four digits only when it does not already end with them.
    private fun proposedName(offer: StoredOfferedBankAccount, sourceName: String): String {
        val described = offer.name ?: offer.bankAccountKind ?: sourceName
        val digits = offer.lastFourDigits?.takeUnless { described.trimEnd().endsWith(it) }?.let { "••$it" }
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

    /** A4 step 1, BR-006: a File Format's page, with its Bank Accounts and whether one can be added (a served Budget is needed). */
    suspend fun fileFormatPage(fileFormatId: Long): FileFormatPage {
        val format = plugins.fileFormats.single { it.id == fileFormatId }
        val snapshot = reads.snapshot()
        val sources = balanceSources()
        val free = reads.freeBudgetAccounts(snapshot)
        val banks = knownBanks()
        return FileFormatPage(
            fileFormatId = fileFormatId,
            name = format.name,
            bankAccounts = snapshot.bankAccounts.filter { it.fileFormatId == fileFormatId }.map { bankAccount ->
                FileFormatBankAccount(
                    bankAccountId = bankAccount.id,
                    name = bankAccount.name,
                    bankName = snapshot.bankNames[bankAccount.bankId].orEmpty(),
                    type = bankAccount.type,
                    currency = bankAccount.currency,
                    balanceFrom = sources.firstOrNull { source ->
                        val offer = snapshot.offered.single { it.id == source.offered.offeredBankAccountId }
                        offer.dataProviderId == bankAccount.balanceDataProviderId && offer.providerReference == bankAccount.balanceProviderReference
                    },
                    budgetAccountName = reads.added(bankAccount, snapshot).budgetAccountName,
                )
            },
            // UC-013 BR-006: Adding a Bank Account of a File Format needs a served Budget, since it is mapped as it is added.
            canAdd = snapshot.budgets.any { it.served },
            freeBudgetAccounts = free,
            balanceSources = sources,
            types = BankAccountType.entries,
            knownBanks = banks,
        )
    }

    /**
     * BR-010: the currency proposed for a Bank Account of a File Format: that of the Offered Bank Account that reports its
     * balance when it states one, otherwise that of the chosen Budget Account; null while neither is chosen.
     */
    suspend fun proposedFileFormatCurrency(budgetAccountId: Long?, balanceOfferedBankAccountId: Long?): CurrencyCode? {
        // UC-013 BR-010: Proposed is the currency of the Offered Bank Account that reports the balance when it states one, else that of the chosen Budget Account.
        val balanceCurrency = balanceOfferedBankAccountId?.let { id -> offered.offered().firstOrNull { it.id == id }?.currency }
        return balanceCurrency ?: budgetAccountId?.let { budgetAccountFor(it)?.first?.currency }
    }

    /** A4: a Bank Account of a File Format, with the File Format, Bank Account type, Bank and name the Operator chose (BR-006, BR-009). */
    suspend fun addFromTransactionFiles(
        fileFormatId: Long,
        type: BankAccountType?,
        bank: BankChoice?,
        balanceOfferedBankAccountId: Long?,
        budgetAccountId: Long,
        name: String,
        startDate: LocalDate?,
        currency: CurrencyCode?,
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
            // UC-013 BR-010: A Bank Account of a File Format has the currency the Operator states; none is added without one.
            val stated = currency ?: return@run PairOutcome.CurrencyNotStated
            refusal(mappedInto, stated, entered)?.let { return@run it }
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
                    currency = stated,
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
        val snapshot = reads.snapshot()
        return snapshot.bankAccounts.map { reads.added(it, snapshot) }
    }

    private companion object {
        /** `BANK_ACCOUNT.name` holds at most 100 characters (entity model). */
        const val NAME_MAX_LENGTH = 100

        /** `BANK.name` holds at most 100 characters (entity model). */
        const val BANK_NAME_MAX_LENGTH = 100
    }
}
