package slussen.testing

import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BackupSizeMonitor
import slussen.core.port.BudgetToServe
import slussen.core.port.ConnectionStore
import slussen.core.port.CoordinatorMode
import slussen.core.port.CoordinatorStore
import slussen.core.port.DataProviderRegistration
import slussen.core.port.DataProviderSettingsStore
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.FetchStore
import slussen.core.port.FileFormatRegistration
import slussen.core.port.ImportStatusStore
import slussen.core.port.JournalEntry
import slussen.core.port.MappingStore
import slussen.core.port.NewBackOff
import slussen.core.port.NewBankAccount
import slussen.core.port.NewFetch
import slussen.core.port.NewOfferedBankAccount
import slussen.core.port.NewProviderRequest
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OperatorSettings
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.PluginRegistryStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.RecoveryCause
import slussen.core.port.RecoveryStore
import slussen.core.port.RegisteredRows
import slussen.core.port.ServeOutcome
import slussen.core.port.StartKind
import slussen.core.port.StartState
import slussen.core.port.StoredAccountBalance
import slussen.core.port.StoredAccountMapping
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBank
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredBudget
import slussen.core.port.StoredBudgetAccount
import slussen.core.port.StoredDataProvider
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFileFormat
import slussen.core.port.StoredOfferedBankAccount
import slussen.core.port.StoredProviderRequest
import slussen.core.port.StoredRecoveryCheck
import slussen.core.port.StoredRequestLimit
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransactionFile
import slussen.core.port.UnreadablePart
import java.time.Instant

/** In-memory fake of [OperatorSettingsStore]; starts with every default. */
class InMemoryOperatorSettingsStore(var settings: OperatorSettings = OperatorSettings.DEFAULT) : OperatorSettingsStore {
    override suspend fun current(): OperatorSettings = settings
}

/** In-memory fake of [CoordinatorStore]; a test sets the mode. */
class InMemoryCoordinatorStore(var mode: CoordinatorMode = CoordinatorMode.Normal) : CoordinatorStore {
    override suspend fun mode(): CoordinatorMode = mode
}

/** In-memory fake of the Decision Journal recorder: keeps every entry it was given. */
class InMemoryDecisionJournal : DecisionJournalRecorder {
    private val recorded = mutableListOf<JournalEntry>()

    val entries: List<JournalEntry> get() = recorded.toList()

    override fun record(entry: JournalEntry) {
        recorded += entry
    }
}

/** In-memory fake of [PluginRegistryStore] with the matching rules of O-11, and of [DataProviderSettingsStore]. */
class InMemoryPluginRegistryStore : PluginRegistryStore, DataProviderSettingsStore {
    val dataProviders = mutableListOf<StoredDataProvider>()
    val fileFormats = mutableListOf<StoredFileFormat>()
    private var nextId = 1L

    override suspend fun reconcile(
        dataProviders: List<DataProviderRegistration>,
        fileFormats: List<FileFormatRegistration>,
    ): RegisteredRows {
        dataProviders.forEach { declared ->
            val index = this.dataProviders.indexOfFirst { it.sourceKey == declared.sourceKey }
            if (index < 0) {
                val limit = declared.requestLimitAtStart(stored = null)
                this.dataProviders += StoredDataProvider(
                    id = nextId++,
                    name = declared.name,
                    sourceKey = declared.sourceKey,
                    givesStableReference = declared.givesStableReference,
                    needsBankConsent = declared.needsBankConsent,
                    fetchCooldownMinutes = declared.defaultFetchCooldownMinutes,
                    requestLimit = limit.requests,
                    requestLimitPublished = limit.published,
                )
            } else {
                val stored = this.dataProviders[index]
                val limit = declared.requestLimitAtStart(StoredRequestLimit(stored.requestLimit, stored.requestLimitPublished))
                this.dataProviders[index] = stored.copy(
                    name = declared.name,
                    givesStableReference = declared.givesStableReference,
                    needsBankConsent = declared.needsBankConsent,
                    requestLimit = limit.requests,
                    requestLimitPublished = limit.published,
                )
            }
        }
        fileFormats.forEach { declared ->
            if (this.fileFormats.none { it.sourceKey == declared.sourceKey }) {
                this.fileFormats += StoredFileFormat(nextId++, declared.name, declared.sourceKey)
            }
        }
        return RegisteredRows(
            this.dataProviders.filter { stored -> dataProviders.any { it.sourceKey == stored.sourceKey } },
            this.fileFormats.filter { stored -> fileFormats.any { it.sourceKey == stored.sourceKey } },
        )
    }

    override suspend fun storeRequestLimit(dataProviderId: Long, requests: Int) {
        dataProviders.replaceAll { if (it.id == dataProviderId) it.copy(requestLimit = requests) else it }
    }
}

/** In-memory fake of [ProviderRequestStore]. */
class InMemoryProviderRequestStore : ProviderRequestStore {
    val requests = mutableListOf<StoredProviderRequest>()

    override suspend fun admitWithinLimit(request: NewProviderRequest, windowStart: Instant, requestLimit: Int): Boolean {
        val counted = requests.count { it.dataProviderId == request.dataProviderId && it.sentAt.isAfter(windowStart) }
        if (counted >= requestLimit) return false
        requests += StoredProviderRequest(
            request.dataProviderId,
            request.bankAccountId,
            request.kind,
            request.unattended,
            request.sentAt,
        )
        return true
    }

    override suspend fun sentSince(since: Instant): List<StoredProviderRequest> = requests.filter { it.sentAt.isAfter(since) }
}

/** In-memory fake of [OfferedBankAccountStore]. */
class InMemoryOfferedBankAccountStore : OfferedBankAccountStore {
    val stored = mutableListOf<StoredOfferedBankAccount>()
    private var nextId = 1L

    override suspend fun offered(): List<StoredOfferedBankAccount> = stored.toList()

    override suspend fun replaceOffered(dataProviderId: Long, bankId: Long?, delivered: List<NewOfferedBankAccount>) {
        stored.removeAll { it.dataProviderId == dataProviderId && (bankId == null || it.bankId == bankId) }
        delivered.forEach {
            stored += StoredOfferedBankAccount(
                id = nextId++,
                dataProviderId = dataProviderId,
                bankId = it.bankId,
                providerReference = it.providerReference,
                bankAccountFingerprint = it.bankAccountFingerprint,
                name = it.name,
                bankAccountKind = it.bankAccountKind,
                currency = it.currency,
                balance = it.balance,
                lastFourDigits = it.lastFourDigits,
                proposedBankAccountId = it.proposedBankAccountId,
                offeredAt = it.offeredAt,
            )
        }
    }
}

/** In-memory fake of [ConnectionStore] with the refusals of O-15, all or nothing like the real one, and of [MappingStore]. */
class InMemoryConnectionStore : ConnectionStore, MappingStore {
    val budgetList = mutableListOf<StoredBudget>()
    val bankList = mutableListOf<StoredBank>()
    val bankAccountList = mutableListOf<StoredBankAccount>()
    private var nextId = 100L

    override suspend fun budgets(): List<StoredBudget> = budgetList.toList()

    override suspend fun banks(): List<StoredBank> = bankList.toList()

    override suspend fun bankAccounts(): List<StoredBankAccount> = bankAccountList.toList()

    private fun mappedInto(budget: StoredBudget): List<StoredBankAccount> {
        val accountIds = budget.budgetAccounts.map { it.id }.toSet()
        return bankAccountList.filter { it.mapping?.budgetAccountId in accountIds }
    }

    override suspend fun serveBudgets(listed: List<BudgetToServe>, chosen: Set<String>, readAt: Instant): ServeOutcome {
        budgetList.filter { it.served && it.budgetAppReference !in chosen }.forEach { budget ->
            val mapped = mappedInto(budget)
            if (mapped.isNotEmpty()) return ServeOutcome.StillMapped(budget.name, mapped.map { it.name })
        }
        budgetList.replaceAll { if (it.budgetAppReference in chosen) it else it.copy(served = false) }
        listed.filter { it.budgetAppReference in chosen }.forEach { read ->
            val index = budgetList.indexOfFirst { it.budgetAppReference == read.budgetAppReference }
            val existing = budgetList.getOrNull(index)
            val budgetId = existing?.id ?: nextId++
            val accounts = existing?.budgetAccounts.orEmpty().toMutableList()
            read.budgetAccounts.forEach { account ->
                val at = accounts.indexOfFirst { it.budgetAppReference == account.budgetAppReference }
                val updated = StoredBudgetAccount(
                    id = accounts.getOrNull(at)?.id ?: nextId++,
                    budgetId = budgetId,
                    budgetAppReference = account.budgetAppReference,
                    name = account.name,
                    manuallyKept = accounts.getOrNull(at)?.manuallyKept ?: false,
                    balance = account.balance,
                    balanceReadAt = readAt,
                )
                if (at < 0) accounts += updated else accounts[at] = updated
            }
            val budget = StoredBudget(
                id = budgetId,
                budgetAppReference = read.budgetAppReference,
                name = read.name,
                currency = read.currency,
                lastReadAt = existing?.lastReadAt,
                served = true,
                budgetAccounts = accounts,
            )
            if (index < 0) budgetList += budget else budgetList[index] = budget
        }
        return ServeOutcome.Served
    }

    override suspend fun addBankAccount(bankAccount: NewBankAccount): AddOutcome {
        val mappedBy = bankAccountList.firstOrNull { it.mapping?.budgetAccountId == bankAccount.budgetAccountId }
        if (mappedBy != null) return AddOutcome.BudgetAccountAlreadyMapped(mappedBy.name)
        val budget = budgetList.first { b -> b.budgetAccounts.any { it.id == bankAccount.budgetAccountId } }
        if (budget.currency != bankAccount.currency) return AddOutcome.CurrencyDiffers(budget.currency)
        val bankId = bankIdOf(bankAccount.bank)
        val id = nextId++
        bankAccountList += StoredBankAccount(
            id = id,
            generatedIdentifier = bankAccount.generatedIdentifier,
            bankId = bankId,
            providerBankId = bankAccount.providerBankId,
            dataProviderId = bankAccount.dataProviderId,
            fileFormatId = bankAccount.fileFormatId,
            balanceDataProviderId = bankAccount.balanceDataProviderId,
            name = bankAccount.name,
            currency = bankAccount.currency,
            type = bankAccount.type,
            startDate = bankAccount.startDate,
            providerReference = bankAccount.providerReference,
            bankAccountFingerprint = bankAccount.bankAccountFingerprint,
            balanceProviderReference = bankAccount.balanceProviderReference,
            identifiedAt = bankAccount.identifiedAt,
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = StoredAccountMapping(nextId++, id, bankAccount.budgetAccountId, firstSentAt = null),
        )
        return AddOutcome.Added(id)
    }

    private suspend fun bankIdOf(bank: BankChoice): Long = when (bank) {
        is BankChoice.Known -> bankList.single { it.id == bank.bankId }.id
        is BankChoice.Named -> bankNamed(bank.name).id
    }

    override suspend fun changeTypeAndBank(
        bankAccountId: Long,
        type: BankAccountType,
        bank: BankChoice,
        clearFundedBy: Boolean,
        clearTransferPartner: Boolean,
    ) {
        val bankId = bankIdOf(bank)
        bankAccountList.replaceAll {
            if (it.id != bankAccountId) {
                it
            } else {
                it.copy(
                    type = type,
                    bankId = bankId,
                    fundedByBudgetAccountId = if (clearFundedBy) null else it.fundedByBudgetAccountId,
                    transferPartnerBudgetAccountId = if (clearTransferPartner) null else it.transferPartnerBudgetAccountId,
                )
            }
        }
    }

    /** Sets a Funded By or Transfer Partner as UC-009 BR-008 will, for the tests of UC-009 A14. */
    fun link(bankAccountId: Long, fundedBy: Long? = null, transferPartner: Long? = null) {
        bankAccountList.replaceAll {
            if (it.id == bankAccountId) it.copy(fundedByBudgetAccountId = fundedBy, transferPartnerBudgetAccountId = transferPartner) else it
        }
    }

    override suspend fun bankNamed(name: String): StoredBank =
        bankList.firstOrNull { it.name == name } ?: StoredBank(nextId++, name, country = null).also { bankList += it }
}

/** In-memory fake of [RecoveryStore]. */
class InMemoryRecoveryStore : RecoveryStore {
    val checks = mutableListOf<StoredRecoveryCheck>()

    fun open(cause: RecoveryCause, at: Instant) {
        checks += StoredRecoveryCheck(checks.size + 1L, cause, at, comparedAt = null, confirmedAt = null)
    }

    override suspend fun openChecks(): List<StoredRecoveryCheck> = checks.filter { it.confirmedAt == null }
}

/** A fixed [StartState]. */
class FixedStartState(
    override val startKind: StartKind = StartKind.Normal,
    override val unreadableParts: List<UnreadablePart> = emptyList(),
) : StartState

/** A fixed [BackupSizeMonitor]; [limitBytes] defaults to Android's 25 MB (C-015). */
class FixedBackupSizeMonitor(
    var bytes: Long = 0,
    override val limitBytes: Long = 25L * 1024 * 1024,
) : BackupSizeMonitor {
    override fun backedUpBytes(): Long = bytes
}

/**
 * In-memory fake of [ImportStatusStore], the data the slices of UC-001, UC-002, UC-003 and UC-007 store, and of the
 * [FetchStore] of UC-001, which writes the same Fetches and Back-offs the import status reads.
 */
class InMemoryImportStatusStore : ImportStatusStore, FetchStore {
    val fetches = mutableListOf<StoredFetch>()
    val balances = mutableListOf<StoredAccountBalance>()
    val consents = mutableListOf<StoredBankConsent>()
    val backOffList = mutableListOf<StoredBackOff>()
    val files = mutableListOf<StoredTransactionFile>()
    val counts = mutableListOf<StoredTransactionCounts>()

    override suspend fun recentFetches(): List<StoredFetch> = fetches.sortedByDescending { it.startedAt }

    override suspend fun accountBalances(): List<StoredAccountBalance> = balances.toList()

    override suspend fun bankConsents(): List<StoredBankConsent> = consents.toList()

    override suspend fun backOffs(): List<StoredBackOff> = backOffList.toList()

    override suspend fun latestTransactionFiles(): List<StoredTransactionFile> = files.toList()

    override suspend fun transactionCounts(): List<StoredTransactionCounts> = counts.toList()

    private var nextFetchId = 1000L

    override suspend fun fetchesOf(dataProviderId: Long): List<StoredFetch> =
        recentFetches().filter { it.dataProviderId == dataProviderId }

    override suspend fun recordFetch(fetch: NewFetch, keep: Int) {
        fetches += StoredFetch(nextFetchId++, fetch.dataProviderId, fetch.startedAt, fetch.startedBy, fetch.outcome, emptyList())
        val kept = fetches.sortedWith(compareByDescending<StoredFetch> { it.startedAt }.thenByDescending { it.id }).take(keep)
        fetches.retainAll(kept)
    }

    override suspend fun backOffsOf(dataProviderId: Long): List<StoredBackOff> = backOffList.filter { it.dataProviderId == dataProviderId }

    override suspend fun startBackOff(backOff: NewBackOff) {
        backOffList += StoredBackOff(backOff.dataProviderId, backOff.bankId, backOff.cause, backOff.startsAt, backOff.endsAt)
    }
}
