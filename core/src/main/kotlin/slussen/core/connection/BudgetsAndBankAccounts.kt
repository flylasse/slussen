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
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.NewBankAccount
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
 * Connect Budgets and Bank Accounts (UC-013), `:core` `connection` (ADR-001 §7): the served Budgets, pairing an Offered
 * Bank Account with a free Budget Account of the same currency, the generated identifier, the proposed name and the
 * Start Date, and Bank Accounts of a File Format.
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

    suspend fun pairing(): Pairing {
        val budgets = store.budgets()
        val bankAccounts = store.bankAccounts()
        val banks = store.banks().associateBy { it.id }
        val offeredAll = offered.offered()
        val mapped = bankAccounts.mapNotNull { it.mapping?.budgetAccountId }.toSet()
        // UC-013 BR-002: Only Budget Accounts of served Budgets that receive through no Account Mapping are offered for pairing.
        val free = budgets.filter { it.served }.flatMap { budget ->
            budget.budgetAccounts.filter { !it.manuallyKept && it.id !in mapped }.map {
                FreeBudgetAccount(it.id, budget.name, budget.currency, it.name, it.balance)
            }
        }
        val sources = plugins.dataProviders.mapNotNull { provider ->
            val ofProvider = offeredAll.filter { it.dataProviderId == provider.stored.id }
            val hasCredentials = credentials.status(provider.key) != CredentialStatus.Missing
            if (!hasCredentials && ofProvider.isEmpty()) return@mapNotNull null
            val added = ofProvider.filter { isAdded(it, bankAccounts) }
            // UC-009 BR-004: An Offered Bank Account that is probably an added Bank Account is proposed for confirmation, never guessed.
            val proposed = ofProvider.filter { it.proposedBankAccountId != null && it !in added }
            val toAdd = ofProvider.filter { it !in added && it !in proposed }
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
        return Pairing(sources, free)
    }

    private fun isAdded(offer: StoredOfferedBankAccount, bankAccounts: List<StoredBankAccount>): Boolean =
        bankAccounts.any { it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference }

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

    suspend fun pair(offeredBankAccountId: Long, budgetAccountId: Long): PairOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            val offeredForAdding = pairing().sources.flatMap { it.offered }.any { it.offeredBankAccountId == offeredBankAccountId }
            val offer = offered.offered().firstOrNull { it.id == offeredBankAccountId }
            if (offer == null || !offeredForAdding) return@run PairOutcome.NotOffered
            val mappedInto = budgetAccountFor(budgetAccountId) ?: return@run PairOutcome.NotOffered
            refusal(mappedInto, offer.currency, offer.name ?: offer.bankAccountKind.orEmpty())?.let { return@run it }
            val bankId = offer.bankId ?: return@run PairOutcome.BankNotKnown
            val provider = plugins.dataProviders.single { it.stored.id == offer.dataProviderId }
            add(
                NewBankAccount(
                    generatedIdentifier = GeneratedIdentifiers.bankAccountIdentifier(random),
                    bankId = bankId,
                    dataProviderId = offer.dataProviderId,
                    fileFormatId = null,
                    balanceDataProviderId = null,
                    balanceProviderReference = null,
                    name = proposedName(offer, provider.stored.name),
                    currency = requireNotNull(offer.currency),
                    type = typeOf(offer.bankAccountKind),
                    // UC-013 BR-003: Slussen records how the Source identifies the Bank Account.
                    providerReference = offer.providerReference,
                    bankAccountFingerprint = offer.bankAccountFingerprint,
                    identifiedAt = clock.instant(),
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

    /** A Budget Account of a served Budget that is not a Manually Kept Account, with its Budget. */
    private suspend fun budgetAccountFor(budgetAccountId: Long): Pair<StoredBudget, StoredBudgetAccount>? {
        val budget = store.budgets().firstOrNull { b -> b.served && b.budgetAccounts.any { it.id == budgetAccountId } }
            ?: return null
        val account = budget.budgetAccounts.single { it.id == budgetAccountId }
        return if (account.manuallyKept) null else budget to account
    }

    /** Step 7: the Budget Account receives through no other Account Mapping and its Budget has the Bank Account's currency. */
    private suspend fun refusal(
        mappedInto: Pair<StoredBudget, StoredBudgetAccount>,
        currency: CurrencyCode?,
        bankAccountName: String,
    ): PairOutcome? {
        val (budget, account) = mappedInto
        // UC-013 BR-002: A Budget Account that receives through an Account Mapping is not offered for pairing.
        val mappedBy = store.bankAccounts().firstOrNull { it.mapping?.budgetAccountId == account.id }
        if (mappedBy != null) return PairOutcome.BudgetAccountAlreadyMapped(account.name, mappedBy.name)
        if (currency == null) return PairOutcome.CurrencyNotKnown
        // UC-003 BR-013: No amount is ever converted; a Bank Account is paired only with a Budget Account of its own currency.
        if (currency != budget.currency) {
            return PairOutcome.CurrencyDiffers(account.name, budget.currency, bankAccountName, currency)
        }
        return null
    }

    private suspend fun add(bankAccount: NewBankAccount, mappedInto: Pair<StoredBudget, StoredBudgetAccount>): PairOutcome =
        when (val outcome = store.addBankAccount(bankAccount)) {
            is AddOutcome.Added -> PairOutcome.Added(outcome.bankAccountId, bankAccount.generatedIdentifier, bankAccount.name)
            is AddOutcome.BudgetAccountAlreadyMapped ->
                PairOutcome.BudgetAccountAlreadyMapped(mappedInto.second.name, outcome.bankAccountName)
            is AddOutcome.CurrencyDiffers ->
                PairOutcome.CurrencyDiffers(mappedInto.second.name, outcome.budgetCurrency, bankAccount.name, bankAccount.currency)
        }

    // UC-013 BR-004: The proposed name is the Bank Account's name or product with the last four digits of its number.
    private fun proposedName(offer: StoredOfferedBankAccount, sourceName: String): String {
        val described = offer.name ?: offer.bankAccountKind ?: sourceName
        val digits = offer.lastFourDigits?.let { "••$it" }
        return listOfNotNull(described, digits).joinToString(" ").take(NAME_MAX_LENGTH)
    }

    /**
     * `BANK_ACCOUNT.bank_account_type` from the kind the Data Provider delivers. No use case states this mapping; it is
     * an open question of Phase 7.
     */
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

    suspend fun addFromTransactionFiles(
        fileFormatId: Long,
        bankName: String,
        balanceOfferedBankAccountId: Long?,
        budgetAccountId: Long,
        name: String,
    ): PairOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            // UC-013 BR-006: Slussen proposes no name from a file; the Operator enters it.
            val entered = validName(name) ?: return@run PairOutcome.NameNotValid
            if (plugins.fileFormats.none { it.id == fileFormatId } || bankName.isBlank()) return@run PairOutcome.NotOffered
            val mappedInto = budgetAccountFor(budgetAccountId) ?: return@run PairOutcome.NotOffered
            val balanceOffer = balanceOfferedBankAccountId?.let { id ->
                offered.offered().firstOrNull { it.id == id } ?: return@run PairOutcome.NotOffered
            }
            val currency = balanceOffer?.currency ?: mappedInto.first.currency
            refusal(mappedInto, currency, entered)?.let { return@run it }
            add(
                NewBankAccount(
                    generatedIdentifier = GeneratedIdentifiers.bankAccountIdentifier(random),
                    bankId = store.bankNamed(bankName.trim()).id,
                    dataProviderId = null,
                    fileFormatId = fileFormatId,
                    // UC-013 BR-006: A Bank Account of a File Format takes its balance from the Offered Bank Account the Operator chose.
                    balanceDataProviderId = balanceOffer?.dataProviderId,
                    balanceProviderReference = balanceOffer?.providerReference,
                    name = entered,
                    currency = currency,
                    type = typeOf(balanceOffer?.bankAccountKind),
                    providerReference = null,
                    bankAccountFingerprint = null,
                    identifiedAt = clock.instant(),
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

    private fun validName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && it.length <= NAME_MAX_LENGTH }

    suspend fun completeAddition(bankAccountId: Long, name: String, startDate: LocalDate?): AdditionOutcome {
        val result = coordinator.run(OperationKind.BudgetAndBankAccountChange, StartedBy.Operator) {
            // UC-013 BR-004: The Operator accepts or edits the proposed name; the name never identifies the Bank Account.
            val accepted = validName(name) ?: return@run AdditionOutcome.NameNotValid
            // UC-013 BR-005: The Start Date is optional and set in the app.
            store.nameAndStartDate(bankAccountId, accepted, startDate)
            val bankAccount = store.bankAccounts().single { it.id == bankAccountId }
            val mappedInto = store.budgets().flatMap { b -> b.budgetAccounts.map { b to it } }
                .single { it.second.id == bankAccount.mapping?.budgetAccountId }
            journal.record(
                JournalEventKind.AccountMappingChange,
                "Bank Account $accepted added, mapped to Budget Account ${mappedInto.second.name} of Budget ${mappedInto.first.name}" +
                    (startDate?.let { ", Start Date $it" } ?: ", no Start Date"),
            )
            AdditionOutcome.Stored
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> AdditionOutcome.Unavailable(result.reason)
        }
    }

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
    }
}
