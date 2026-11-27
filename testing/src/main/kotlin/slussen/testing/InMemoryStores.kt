package slussen.testing

import slussen.core.comparison.EligibleTransactions
import slussen.core.fetch.BankTransactionIntake
import slussen.core.identity.ImportIdentifierInput
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.AddOutcome
import slussen.core.port.DeliveryStored
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredUnreadableTransaction
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.port.BankAccountDelivery
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
import slussen.core.port.IdentificationStored
import slussen.core.port.KeptOutcome
import slussen.core.port.MappingStored
import slussen.core.port.NewIdentification
import slussen.core.port.SentRecordOutcome
import slussen.core.port.SentThrough
import slussen.core.port.TransferDecision
import slussen.core.port.StoredSentRecord
import slussen.core.port.MappingStore
import slussen.core.port.NewBackOff
import slussen.core.port.NewBankAccount
import slussen.core.port.NewFetch
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.NewOfferedBankAccount
import slussen.core.port.NewProviderRequest
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OperatorSettings
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.NumberSetting
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.core.port.OtherSideKind
import slussen.core.port.OtherSideStored
import slussen.core.port.PluginRegistryStore
import slussen.core.port.ProviderRequestStore
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.FirstUploadMatchingStore
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
import slussen.core.port.StoredFetchResult
import slussen.core.port.StoredFileFormat
import slussen.core.port.StoredLeftOutBankAccount
import slussen.core.port.StoredOfferedBankAccount
import slussen.core.port.StoredProviderRequest
import slussen.core.port.StoredFirstUploadMatching
import slussen.core.port.StoredRequestLimit
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransfer
import slussen.core.port.StoredTransactionFile
import slussen.core.port.UnreadablePart
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory stand-in for `coordinator_state.state_version` (ADR-002 §2): the version of everything an Upload Plan was
 * prepared from, which every storage operation that changes one of those things raises (ADR-007 §6 step 2). The fakes of
 * one test share one, as the tables of `slussen.db` share the one record; `StateVersionScenario` holds the fakes and the
 * real storage to the same operations.
 */
class InMemoryStateVersion {
    var value: Long = 0
        private set

    fun raise() {
        value++
    }
}

/**
 * In-memory fake of [OperatorSettingsStore]; starts with every default. A test holds a change with [beforeStore], to see
 * it while it is being stored, makes it fail with [failsToStore], as a storage that cannot be written would, and makes
 * the read fail with [failsToRead]. Like O-13 in `slussen.db` it writes what follows a change with it, all or nothing:
 * the Comparison marker of [connections], or `period_read_owed` in [copies] for each served Budget that has a cursor.
 */
class InMemoryOperatorSettingsStore(
    var settings: OperatorSettings = OperatorSettings.DEFAULT,
    val stateVersion: InMemoryStateVersion = InMemoryStateVersion(),
    private val connections: InMemoryConnectionStore? = null,
    private val copies: InMemoryBudgetAppCopyStore? = null,
) : OperatorSettingsStore {
    var beforeStore: suspend () -> Unit = {}
    var failsToStore: Boolean = false

    /** Makes every read of the Operator Settings fail, as a storage that cannot be read would. */
    var failsToRead: Boolean = false

    override suspend fun current(): OperatorSettings {
        if (failsToRead) throw IllegalStateException("The invented storage of this test cannot be read.")
        return settings
    }

    override suspend fun store(change: SettingChange) {
        beforeStore()
        if (failsToStore) throw IllegalStateException("The invented storage of this test cannot be written.")
        when (change.followUp) {
            SettingFollowUp.None -> Unit
            SettingFollowUp.StatusesAgain -> checkNotNull(connections) { "This fake was made without the Comparison marker." }.comparisonPending = true
            SettingFollowUp.PeriodReadOwed -> {
                val copy = checkNotNull(copies) { "This fake was made without the stored copy." }
                checkNotNull(connections) { "This fake was made without the Budgets." }.budgetList
                    .filter { it.served && it.id in copy.cursors }
                    .forEach { copy.periodOwed += it.id }
            }
        }
        settings = change.value.appliedTo(settings)
        stateVersion.raise()
    }

    /** The fetch period stored as Settings stores it when it is not longer than the read period (O-13). */
    suspend fun storeFetchPeriod(days: Int) = store(SettingChange(SettingValue.Number(NumberSetting.FetchPeriod, days), SettingFollowUp.None))
}

/** In-memory fake of [CoordinatorStore]; a test sets the mode, and makes its read fail with [failsToRead]. */
class InMemoryCoordinatorStore(var mode: CoordinatorMode = CoordinatorMode.Normal) : CoordinatorStore {
    var failsToRead: Boolean = false

    override suspend fun mode(): CoordinatorMode {
        if (failsToRead) throw IllegalStateException("The invented storage of this test cannot be read.")
        return mode
    }
}

/** In-memory fake of the Decision Journal recorder: keeps every entry it was given. */
class InMemoryDecisionJournal : DecisionJournalRecorder {
    private val recorded = mutableListOf<JournalEntry>()

    val entries: List<JournalEntry> get() = recorded.toList()

    override fun record(entry: JournalEntry) {
        recorded += entry
    }
}

/**
 * In-memory fake of [PluginRegistryStore] with the matching rules of O-11, and of [DataProviderSettingsStore]. A test
 * holds a change of a setting with [beforeStore] and makes it fail with [failsToStore].
 */
class InMemoryPluginRegistryStore(val stateVersion: InMemoryStateVersion = InMemoryStateVersion()) : PluginRegistryStore, DataProviderSettingsStore {
    val dataProviders = mutableListOf<StoredDataProvider>()
    val fileFormats = mutableListOf<StoredFileFormat>()
    var beforeStore: suspend () -> Unit = {}
    var failsToStore: Boolean = false
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
        beforeStore()
        if (failsToStore) throw IllegalStateException("The invented storage of this test cannot be written.")
        // UC-001 BR-007: A Request Limit the Data Provider publishes is never changed by the Operator's setting.
        val changes = dataProviders.any { it.id == dataProviderId && !it.requestLimitPublished }
        dataProviders.replaceAll { if (it.id == dataProviderId && !it.requestLimitPublished) it.copy(requestLimit = requests) else it }
        if (changes) stateVersion.raise()
    }

    override suspend fun storeFetchCooldown(dataProviderId: Long, minutes: Int) {
        beforeStore()
        if (failsToStore) throw IllegalStateException("The invented storage of this test cannot be written.")
        val changes = dataProviders.any { it.id == dataProviderId }
        dataProviders.replaceAll { if (it.id == dataProviderId) it.copy(fetchCooldownMinutes = minutes) else it }
        if (changes) stateVersion.raise()
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

/** In-memory fake of [OfferedBankAccountStore]; a test makes its read fail with [failsToRead]. */
class InMemoryOfferedBankAccountStore : OfferedBankAccountStore {
    val stored = mutableListOf<StoredOfferedBankAccount>()
    private var nextId = 1L
    var failsToRead: Boolean = false

    override suspend fun offered(): List<StoredOfferedBankAccount> {
        if (failsToRead) throw IllegalStateException("The invented storage of this test cannot be read.")
        return stored.toList()
    }

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

/**
 * In-memory fake of [ConnectionStore] with the refusals of O-15, all or nothing like the real one, with the Left-Out
 * Bank Accounts and Manually Kept Accounts of S-38, and of [MappingStore] (O-13) with its refusals and the Comparison
 * marker it sets ([comparisonPending]). A test holds a switch of a Left-Out Bank Account or a Manually Kept Account, or a
 * change of UC-009, with [beforeStore], to see it while it is being stored, and makes it fail with [failsToStore], as a
 * storage that cannot be written would.
 */
class InMemoryConnectionStore(val stateVersion: InMemoryStateVersion = InMemoryStateVersion()) : ConnectionStore, MappingStore {
    var beforeStore: suspend () -> Unit = {}
    var failsToStore: Boolean = false

    /**
     * In-memory stand-in for `coordinator_state.comparison_pending` (ADR-007 §3a), which the tables of `slussen.db` share:
     * O-4 and the changes of O-13 that are followed by statuses given again set it, O-5 clears it
     * ([InMemoryBudgetAppCopyStore.pending] is this marker).
     */
    var comparisonPending: Boolean = false

    private suspend fun storing() {
        beforeStore()
        if (failsToStore) throw IllegalStateException("The invented storage of this test cannot be written.")
    }

    val budgetList = mutableListOf<StoredBudget>()
    val bankList = mutableListOf<StoredBank>()
    val bankAccountList = mutableListOf<StoredBankAccount>()
    val leftOutList = mutableListOf<StoredLeftOutBankAccount>()
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
        stateVersion.raise()
        return ServeOutcome.Served
    }

    override suspend fun addBankAccount(bankAccount: NewBankAccount): AddOutcome {
        val mappedBy = bankAccountList.firstOrNull { it.mapping?.budgetAccountId == bankAccount.budgetAccountId }
        if (mappedBy != null) return AddOutcome.BudgetAccountAlreadyMapped(mappedBy.name)
        val budget = budgetList.first { b -> b.budgetAccounts.any { it.id == bankAccount.budgetAccountId } }
        if (budget.currency != bankAccount.currency) return AddOutcome.CurrencyDiffers(budget.currency)
        if (budget.budgetAccounts.single { it.id == bankAccount.budgetAccountId }.manuallyKept) return AddOutcome.ManuallyKept
        val own = bankAccount.dataProviderId to bankAccount.providerReference
        if (leftOutList.any { it.dataProviderId to it.providerReference == own }) return AddOutcome.LeftOut
        leftOutList.removeAll { it.dataProviderId == bankAccount.balanceDataProviderId && it.providerReference == bankAccount.balanceProviderReference }
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
        stateVersion.raise()
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
        storing()
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
        storedWithStatusesToGive()
    }

    /** As O-13 does in its transaction: `state_version` raised (ADR-007 §6 step 2) and the Comparison marker set (§3a). */
    private fun storedWithStatusesToGive() {
        stateVersion.raise()
        comparisonPending = true
    }

    override suspend fun setRetired(bankAccountId: Long, retired: Boolean): Boolean {
        storing()
        if (bankAccountList.none { it.id == bankAccountId && it.retired != retired }) return false
        bankAccountList.replaceAll { if (it.id == bankAccountId) it.copy(retired = retired) else it }
        storedWithStatusesToGive()
        return true
    }

    override suspend fun rename(bankAccountId: Long, name: String): Boolean {
        storing()
        if (bankAccountList.none { it.id == bankAccountId }) return false
        bankAccountList.replaceAll { if (it.id == bankAccountId) it.copy(name = name) else it }
        stateVersion.raise()
        return true
    }

    override suspend fun setStartDate(bankAccountId: Long, startDate: LocalDate?): Boolean {
        storing()
        if (bankAccountList.none { it.id == bankAccountId }) return false
        bankAccountList.replaceAll { if (it.id == bankAccountId) it.copy(startDate = startDate) else it }
        storedWithStatusesToGive()
        return true
    }

    override suspend fun setOtherSide(bankAccountId: Long, kind: OtherSideKind, budgetAccountId: Long?): OtherSideStored {
        storing()
        val bankAccount = bankAccountList.firstOrNull { it.id == bankAccountId } ?: return OtherSideStored.NotFound
        // Entity model `BANK_ACCOUNT`: a Funded By only for a Credit Card, a Transfer Partner only for another Bank Account.
        if ((bankAccount.type == BankAccountType.CreditCard) != (kind == OtherSideKind.FundedBy)) return OtherSideStored.NotAllowed
        if (budgetAccountId != null) {
            // Entity model `BANK_ACCOUNT`: in the Budget of its own Account Mapping, not its own Budget Account, never a Manually Kept Account, fed by a Bank Account that is not a Retired Account.
            val own = bankAccount.mapping?.budgetAccountId ?: return OtherSideStored.NotAllowed
            val budget = budgetList.firstOrNull { candidate -> candidate.budgetAccounts.any { it.id == own } } ?: return OtherSideStored.NotAllowed
            val chosen = budget.budgetAccounts.firstOrNull { it.id == budgetAccountId } ?: return OtherSideStored.NotAllowed
            val fetched = bankAccountList.any { it.mapping?.budgetAccountId == budgetAccountId && !it.retired }
            if (budgetAccountId == own || chosen.manuallyKept || !fetched) return OtherSideStored.NotAllowed
        }
        bankAccountList.replaceAll { account ->
            when {
                account.id != bankAccountId -> account
                kind == OtherSideKind.FundedBy -> account.copy(fundedByBudgetAccountId = budgetAccountId)
                else -> account.copy(transferPartnerBudgetAccountId = budgetAccountId)
            }
        }
        storedWithStatusesToGive()
        return OtherSideStored.Stored
    }

    /**
     * The Sent Records a test plants (`sent_record`): the Uploads that write them come with their own phases (UC-006).
     * [InMemoryBudgetAppCopyStore.sentRecords] is this list, so that the Comparison and the locks of O-13 read the same.
     */
    val sentRecords = mutableListOf<StoredSentRecord>()

    /**
     * The stored Bank Transactions (`bank_transaction`), which the [InMemoryImportStatusStore] of the same slice holds:
     * the Sent Records are counted for the Bank Account of their Bank Transaction, and a move empties their statuses.
     */
    internal var transactionStore: InMemoryImportStatusStore? = null

    private fun sentRecordsOf(bankAccountId: Long): List<StoredSentRecord> {
        val own = transactionStore?.bankTransactions.orEmpty().filter { it.bankAccountId == bankAccountId }.map { it.id }.toSet()
        return sentRecords.filter { it.storedBankTransaction in own }
    }

    private fun sentThroughNow(bankAccount: StoredBankAccount): SentThrough =
        SentThrough.of(sentRecordsOf(bankAccount.id), bankAccount.mapping?.budgetAccountId)

    override suspend fun sentThroughMappings(): Map<Long, SentThrough> =
        bankAccountList.filter { sentRecordsOf(it.id).isNotEmpty() }.associate { it.id to sentThroughNow(it) }

    override suspend fun sentThrough(bankAccountId: Long): SentThrough =
        bankAccountList.firstOrNull { it.id == bankAccountId }?.let(::sentThroughNow) ?: SentThrough.NONE

    override suspend fun sentForIdentification(bankAccountId: Long): SentThrough =
        if (bankAccountList.any { it.id == bankAccountId }) SentThrough.of(sentRecordsOf(bankAccountId), budgetAccountId = null) else SentThrough.NONE

    /** Entity model `ACCOUNT_MAPPING`: while something sent through it was written, only with the Operator's confirmation of the numbers as they are now. */
    private fun unconfirmed(bankAccount: StoredBankAccount, confirmed: SentThrough?): SentThrough? =
        sentThroughNow(bankAccount).takeIf { it.needsConfirmation && it != confirmed }

    /** A new identification counts the written sends of the Bank Account to any Budget Account (UC-009 BR-007). */
    private fun unconfirmedIdentification(bankAccount: StoredBankAccount, confirmed: SentThrough?): SentThrough? =
        SentThrough.of(sentRecordsOf(bankAccount.id), budgetAccountId = null).takeIf { it.needsConfirmation && it != confirmed }

    override suspend fun identify(bankAccountId: Long, identification: NewIdentification, confirmed: SentThrough?): IdentificationStored {
        storing()
        val bankAccount = bankAccountList.firstOrNull { it.id == bankAccountId && it.dataProviderId != null } ?: return IdentificationStored.NotFound
        val provider = bankAccount.dataProviderId
        val reference = identification.providerReference
        val fingerprint = identification.bankAccountFingerprint
        // Entity model `BANK_ACCOUNT`: two Bank Accounts of the same Data Provider never have the same reference or fingerprint; a balance source is never identified as another.
        val usedBy = bankAccountList.filter { it.id != bankAccountId }.firstOrNull { other ->
            (other.dataProviderId == provider && (other.providerReference == reference || (fingerprint != null && other.bankAccountFingerprint == fingerprint))) ||
                (other.balanceDataProviderId == provider && other.balanceProviderReference == reference)
        }
        if (usedBy != null) return IdentificationStored.AlreadyUsed(usedBy.name)
        if (leftOutList.any { it.dataProviderId == provider && it.providerReference == reference }) return IdentificationStored.LeftOut
        val changes = bankAccount.providerReference != reference || bankAccount.bankAccountFingerprint != fingerprint
        if (changes) unconfirmedIdentification(bankAccount, confirmed)?.let { return IdentificationStored.ToConfirm(it) }
        bankAccountList.replaceAll {
            if (it.id != bankAccountId) {
                it
            } else {
                it.copy(
                    providerReference = reference,
                    bankAccountFingerprint = fingerprint,
                    providerBankId = identification.providerBankId,
                    identifiedAt = identification.identifiedAt,
                )
            }
        }
        stateVersion.raise()
        return IdentificationStored.Stored
    }

    /** UC-009 A8 step 1, UC-013 BR-014: of a served Budget in [bankAccount]'s currency, receiving through no Account Mapping, no Manually Kept Account. */
    private fun mappable(bankAccount: StoredBankAccount, budgetAccountId: Long): StoredBudget? {
        val budget = budgetList.firstOrNull { candidate -> candidate.budgetAccounts.any { it.id == budgetAccountId } } ?: return null
        val account = budget.budgetAccounts.single { it.id == budgetAccountId }
        val free = bankAccountList.none { it.mapping?.budgetAccountId == budgetAccountId }
        return budget.takeIf { it.served && it.currency == bankAccount.currency && !account.manuallyKept && free }
    }

    private fun budgetOf(budgetAccountId: Long?): Long? =
        budgetList.firstOrNull { budget -> budget.budgetAccounts.any { it.id == budgetAccountId } }?.id

    /** As O-13 does: a Bank Account mapped into another Budget is not compared there yet, so no status the old one gave it stays. */
    private fun notComparedYet(bankAccountId: Long) {
        transactionStore?.bankTransactions?.replaceAll { if (it.bankAccountId == bankAccountId) it.copy(status = null) else it }
        released(bankAccountId)
    }

    /**
     * As O-13 does: every Transfer without a Transfer Decision with a Transfer Leg of [bankAccountId], none of whose
     * Transfer Legs was uploaded, is removed and its Transfer Legs are in no Transfer; one the Operator decided on is
     * left to O-5 (UC-005 BR-015).
     */
    private fun released(bankAccountId: Long) {
        val store = transactionStore ?: return
        val uploaded = sentRecords.filter { it.outcome != SentRecordOutcome.Rejected }.map { it.storedBankTransaction }.toSet()
        val legsOf = store.bankTransactions.filter { it.transferId != null }.groupBy { it.transferId }
        val releasedIds = store.transfers
            .filter { it.decision == TransferDecision.Proposed }
            .filter { transfer -> legsOf[transfer.id].orEmpty().let { legs -> legs.any { it.bankAccountId == bankAccountId } && legs.none { it.id in uploaded } } }
            .map { it.id }
            .toSet()
        if (releasedIds.isEmpty()) return
        store.bankTransactions.replaceAll { if (it.transferId in releasedIds) it.copy(transferId = null) else it }
        store.transfers.removeAll { it.id in releasedIds }
    }

    override suspend fun move(bankAccountId: Long, budgetAccountId: Long, confirmed: SentThrough?): MappingStored {
        storing()
        val bankAccount = bankAccountList.firstOrNull { it.id == bankAccountId } ?: return MappingStored.NotFound
        val mapping = bankAccount.mapping ?: return MappingStored.NotAllowed
        val budget = mappable(bankAccount, budgetAccountId) ?: return MappingStored.NotAllowed
        unconfirmed(bankAccount, confirmed)?.let { return MappingStored.ToConfirm(it) }
        if (budgetOf(mapping.budgetAccountId) != budget.id) notComparedYet(bankAccountId)
        bankAccountList.replaceAll { if (it.id == bankAccountId) it.copy(mapping = mapping.copy(budgetAccountId = budgetAccountId, firstSentAt = null)) else it }
        storedWithStatusesToGive()
        return MappingStored.Stored
    }

    override suspend fun mapAgain(bankAccountId: Long, budgetAccountId: Long): MappingStored {
        storing()
        val bankAccount = bankAccountList.firstOrNull { it.id == bankAccountId } ?: return MappingStored.NotFound
        if (bankAccount.mapping != null || mappable(bankAccount, budgetAccountId) == null) return MappingStored.NotAllowed
        notComparedYet(bankAccountId)
        bankAccountList.replaceAll { if (it.id == bankAccountId) it.copy(mapping = StoredAccountMapping(nextId++, bankAccountId, budgetAccountId, firstSentAt = null)) else it }
        storedWithStatusesToGive()
        return MappingStored.Stored
    }

    override suspend fun remove(bankAccountId: Long, confirmed: SentThrough?): MappingStored {
        storing()
        val bankAccount = bankAccountList.firstOrNull { it.id == bankAccountId } ?: return MappingStored.NotFound
        if (bankAccount.mapping == null) return MappingStored.NotAllowed
        unconfirmed(bankAccount, confirmed)?.let { return MappingStored.ToConfirm(it) }
        released(bankAccountId)
        bankAccountList.replaceAll { if (it.id == bankAccountId) it.copy(mapping = null) else it }
        storedWithStatusesToGive()
        return MappingStored.Stored
    }

    /** Sets a Funded By or Transfer Partner as UC-009 BR-008 will, for the tests of UC-009 A14. */
    fun link(bankAccountId: Long, fundedBy: Long? = null, transferPartner: Long? = null) {
        bankAccountList.replaceAll {
            if (it.id == bankAccountId) it.copy(fundedByBudgetAccountId = fundedBy, transferPartnerBudgetAccountId = transferPartner) else it
        }
    }

    override suspend fun bankNamed(name: String): StoredBank =
        bankList.firstOrNull { it.name == name } ?: StoredBank(nextId++, name, country = null).also { bankList += it }

    /** Makes the read of the Left-Out Bank Accounts fail, as a storage that cannot be read would. */
    var failsToReadLeftOut: Boolean = false

    override suspend fun leftOutBankAccounts(): List<StoredLeftOutBankAccount> {
        if (failsToReadLeftOut) throw IllegalStateException("The invented storage of this test cannot be read.")
        return leftOutList.toList()
    }

    override suspend fun leaveOut(leftOut: List<NewLeftOutBankAccount>): Boolean {
        storing()
        val taken = leftOutList.map { it.dataProviderId to it.providerReference } + bankAccountList.flatMap {
            listOf(it.dataProviderId to it.providerReference, it.balanceDataProviderId to it.balanceProviderReference)
        }
        val asked = leftOut.map { it.dataProviderId to it.providerReference }
        if (asked.any { it in taken } || asked.distinct().size != asked.size) return false
        leftOut.forEach { leftOutList += StoredLeftOutBankAccount(nextId++, it.dataProviderId, it.providerReference, it.name, it.leftOutAt) }
        stateVersion.raise()
        return true
    }

    override suspend fun bringBack(leftOutBankAccountId: Long): StoredLeftOutBankAccount? {
        storing()
        return leftOutList.firstOrNull { it.id == leftOutBankAccountId }?.also {
            leftOutList.remove(it)
            stateVersion.raise()
        }
    }

    override suspend fun setManuallyKept(budgetAccountId: Long, manuallyKept: Boolean): KeptOutcome {
        storing()
        if (budgetList.none { budget -> budget.budgetAccounts.any { it.id == budgetAccountId } }) return KeptOutcome.NotFound
        val mappedBy = bankAccountList.firstOrNull { it.mapping?.budgetAccountId == budgetAccountId }
        if (manuallyKept && mappedBy != null) return KeptOutcome.StillReceives(mappedBy.name)
        budgetList.replaceAll { budget ->
            budget.copy(budgetAccounts = budget.budgetAccounts.map { if (it.id == budgetAccountId) it.copy(manuallyKept = manuallyKept) else it })
        }
        stateVersion.raise()
        return KeptOutcome.Stored
    }
}

/** In-memory fake of [FirstUploadMatchingStore]. */
class InMemoryFirstUploadMatchingStore : FirstUploadMatchingStore {
    val checks = mutableListOf<StoredFirstUploadMatching>()

    fun open(cause: FirstUploadMatchingCause, at: Instant) {
        checks += StoredFirstUploadMatching(checks.size + 1L, cause, at, comparedAt = null, confirmedAt = null)
    }

    override suspend fun openChecks(): List<StoredFirstUploadMatching> = checks.filter { it.confirmedAt == null }
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
 * [FetchStore] of UC-001, which writes the same Fetches, Fetch Results, Account Balances and Back-offs the import status
 * reads, and the Bank Transactions and Unreadable Transactions, all or nothing like the real one (O-1). [identify]
 * computes the Import Identifiers; a test passes one with a test-only digest to plant a collision (ADR-008 §5).
 */
class InMemoryImportStatusStore(
    private val identify: (ImportIdentifierInput) -> String = ImportIdentifierProtocol::importIdentifier,
    /** The Bank Accounts and Budgets the counts of the stored Bank Transactions are judged by (UC-003 BR-014). */
    private val connections: ConnectionStore? = null,
    /** The version O-1 raises when it stores a new or changed Bank Transaction: the one of [connections] when it is a fake. */
    val stateVersion: InMemoryStateVersion = (connections as? InMemoryConnectionStore)?.stateVersion ?: InMemoryStateVersion(),
) : ImportStatusStore, FetchStore {
    val bankTransactions = mutableListOf<StoredBankTransaction>()
    val unreadableTransactions = mutableListOf<StoredUnreadableTransaction>()

    init {
        (connections as? InMemoryConnectionStore)?.transactionStore = this
    }

    /** The stored Transfers, which [InMemoryBudgetAppCopyStore] writes and the counts read (`transfer`). */
    val transfers = mutableListOf<StoredTransfer>()
    private var nextStoredId = 1L
    val fetches = mutableListOf<StoredFetch>()
    val balances = mutableListOf<StoredAccountBalance>()
    val consents = mutableListOf<StoredBankConsent>()
    val backOffList = mutableListOf<StoredBackOff>()
    val files = mutableListOf<StoredTransactionFile>()
    val counts = mutableListOf<StoredTransactionCounts>()

    /** Makes every read of the import status fail, as a storage that cannot be read would (NFR-023). */
    var failsToRead: Boolean = false

    override suspend fun recentFetches(): List<StoredFetch> {
        if (failsToRead) throw IllegalStateException("The invented storage of this test cannot be read.")
        return fetches.sortedByDescending { it.startedAt }
    }

    override suspend fun accountBalances(): List<StoredAccountBalance> = balances.toList()

    override suspend fun bankConsents(): List<StoredBankConsent> = consents.toList()

    override suspend fun backOffs(): List<StoredBackOff> = backOffList.toList()

    override suspend fun latestTransactionFiles(): List<StoredTransactionFile> = files.toList()

    /**
     * The [counts] a test set, and for every other Bank Account with stored Bank Transactions or Unreadable Transactions
     * the counts the core's rules give them (UC-003 BR-014, UC-008 BR-003).
     */
    override suspend fun transactionCounts(): List<StoredTransactionCounts> {
        val store = connections ?: return counts.toList()
        val budgets = store.budgets()
        val computed = store.bankAccounts()
            .filter { account -> counts.none { it.bankAccountId == account.id } }
            .filter { account -> bankTransactions.any { it.bankAccountId == account.id } || unreadableTransactions.any { it.bankAccountId == account.id } }
            .map { account ->
                val budget = budgets.firstOrNull { budget -> budget.budgetAccounts.any { it.id == account.mapping?.budgetAccountId } }
                val transferLegs = bankTransactions.filter { it.transferId != null }
                EligibleTransactions.countsOf(
                    account, budget, bankTransactionsOf(account.id), unreadableTransactionsOf(account.id), transferLegs,
                    withRetiredSide = EligibleTransactions.withARetiredSide(transfers, transferLegs, store.bankAccounts()),
                )
            }
        return counts.toList() + computed
    }

    private var nextFetchId = 1000L

    override suspend fun fetchesOf(dataProviderId: Long): List<StoredFetch> =
        recentFetches().filter { it.dataProviderId == dataProviderId }

    override suspend fun recordFetch(fetch: NewFetch, keep: Int): Long {
        val id = nextFetchId++
        fetches += StoredFetch(id, fetch.dataProviderId, fetch.startedAt, fetch.startedBy, fetch.outcome, emptyList())
        val kept = fetches.sortedWith(compareByDescending<StoredFetch> { it.startedAt }.thenByDescending { it.id }).take(keep)
        fetches.retainAll(kept)
        return id
    }

    /**
     * O-1: the Bank Transactions and Unreadable Transactions of one Bank Account as the core's intake rules decide them,
     * its Fetch Result and, when given, its Account Balance in place of the stored one; nothing of it when a check fails.
     */
    override suspend fun storeBankAccountDelivery(delivery: BankAccountDelivery): DeliveryStored {
        val index = fetches.indexOfFirst { it.id == delivery.fetchId }
        require(index >= 0) { "No Fetch ${delivery.fetchId}: a Fetch Result belongs to a recorded Fetch (FETCH_RESULT.fetch_id)" }
        val fetch = fetches[index]
        require(fetch.results.none { it.bankAccountId == delivery.bankAccountId }) { "One Fetch Result per Fetch and Bank Account" }
        val plan = delivery.transactions?.let { delivered ->
            BankTransactionIntake.plan(
                bankTransactionsOf(delivery.bankAccountId),
                unreadableTransactionsOf(delivery.bankAccountId),
                delivered,
                identify,
            )
        }
        fetches[index] = fetch.copy(results = fetch.results + StoredFetchResult(delivery.bankAccountId, delivery.outcome, delivery.transactionCount))
        delivery.balance?.let { balance ->
            balances.removeAll { it.bankAccountId == delivery.bankAccountId }
            balances += StoredAccountBalance(delivery.bankAccountId, balance.amount, balance.currency, balance.reportedAt)
        }
        if (plan == null) return DeliveryStored()
        if (plan.inserts.isNotEmpty() || plan.changed.isNotEmpty()) stateVersion.raise()
        plan.changed.forEach { changed -> bankTransactions.replaceAll { if (it.id == changed.id) changed else it } }
        plan.inserts.forEach {
            bankTransactions += StoredBankTransaction(
                id = nextStoredId++,
                bankAccountId = delivery.bankAccountId,
                stableReference = it.stableReference,
                referenceKind = it.referenceKind,
                occurrenceNumber = it.occurrenceNumber,
                importIdentifier = it.importIdentifier,
                bookedDate = it.bookedDate,
                amount = it.amount,
                currency = it.currency,
                payee = it.payee,
                memo = it.memo,
                firstSeenAt = it.firstSeenAt,
                status = null,
                transferId = null,
            )
        }
        plan.unreadable.forEach {
            unreadableTransactions += StoredUnreadableTransaction(
                id = nextStoredId++,
                bankAccountId = delivery.bankAccountId,
                bookedDate = it.bookedDate,
                amount = it.amount,
                currency = it.currency,
                payee = it.payee,
                memo = it.memo,
                reason = it.reason.text,
                firstSeenAt = delivery.transactions!!.receivedAt,
                status = UnreadableTransactionStatus.NeedsReview,
            )
        }
        return plan.stored
    }

    override suspend fun bankTransactionsOf(bankAccountId: Long): List<StoredBankTransaction> =
        bankTransactions.filter { it.bankAccountId == bankAccountId }

    override suspend fun unreadableTransactionsOf(bankAccountId: Long): List<StoredUnreadableTransaction> =
        unreadableTransactions.filter { it.bankAccountId == bankAccountId }

    override suspend fun backOffsOf(dataProviderId: Long): List<StoredBackOff> = backOffList.filter { it.dataProviderId == dataProviderId }

    override suspend fun startBackOff(backOff: NewBackOff) {
        backOffList += StoredBackOff(backOff.dataProviderId, backOff.bankId, backOff.cause, backOff.startsAt, backOff.endsAt)
    }
}
