package slussen.persistence

import slussen.core.port.AddOutcome
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetToServe
import slussen.core.port.ConnectionStore
import slussen.core.port.CoordinatorMode
import slussen.core.port.CoordinatorStore
import slussen.core.port.DataProviderRegistration
import slussen.core.port.DataProviderSettingsStore
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchStore
import slussen.core.port.FileFormatRegistration
import slussen.core.port.ImportStatusStore
import slussen.core.port.KeptOutcome
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
import slussen.core.port.PluginRegistryStore
import slussen.core.port.ProviderRequestKind
import slussen.core.port.ProviderRequestStore
import slussen.core.port.RecoveryCause
import slussen.core.port.RecoveryStore
import slussen.core.port.RegisteredRows
import slussen.core.port.ServeOutcome
import slussen.core.port.StartedBy
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
import slussen.core.port.StoredLeftOutBankAccount
import slussen.core.port.StoredOfferedBankAccount
import slussen.core.port.StoredProviderRequest
import slussen.core.port.StoredRecoveryCheck
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransactionFile
import slussen.persistence.device.OfferedBankAccountDao
import slussen.persistence.device.OfferedBankAccountEntity
import slussen.persistence.main.BackOffEntity
import slussen.persistence.main.BankAccountEntity
import slussen.persistence.main.BudgetAccountEntity
import slussen.persistence.main.BudgetEntity
import slussen.persistence.main.ConnectionDao
import slussen.persistence.main.CoordinatorDao
import slussen.persistence.main.FetchDao
import slussen.persistence.main.FetchEntity
import slussen.persistence.main.FileFormatEntity
import slussen.persistence.main.LeftOutBankAccountEntity
import slussen.persistence.main.OperatorSettingsDao
import slussen.persistence.main.OperatorSettingsEntity
import slussen.persistence.main.PluginRegistryDao
import slussen.persistence.main.ProviderRequestDao
import slussen.persistence.main.ProviderRequestEntity
import slussen.persistence.main.RecoveryDao
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

private const val MAIN_PART = "slussen.db"
private const val DEVICE_PART = "device.db"

/** A currency read back that is not three capital letters is unreadable data (ADR-002 §8). */
private fun currencyOf(text: String, part: String): CurrencyCode =
    try {
        CurrencyCode(text)
    } catch (e: IllegalArgumentException) {
        throw UnreadableStoredData(part)
    }

/** `OPERATOR_SETTINGS` in `slussen.db`; the one record starts with every default (entity model constraints). */
class RoomOperatorSettingsStore(private val dao: OperatorSettingsDao) : OperatorSettingsStore {
    override suspend fun current(): OperatorSettings = dao.current()?.let(::toSettings) ?: OperatorSettings.DEFAULT

    /** The default content of ADR-002 §8 step 3: one record with every default, when there is none. */
    suspend fun ensureDefault() {
        if (dao.count() == 0) dao.insert(toEntity(OperatorSettings.DEFAULT))
    }

    private fun toSettings(e: OperatorSettingsEntity) = OperatorSettings(
        uploadWindowDays = e.uploadWindowDays,
        fileUploadWindowDays = e.fileUploadWindowDays,
        fetchPeriodDays = e.fetchPeriodDays,
        defaultWaitHours = e.defaultWaitHours,
        requestLimitWarningHours = e.requestLimitWarningHours,
        requestTimeoutSeconds = e.requestTimeoutSeconds,
        uploadPlanMinutes = e.uploadPlanMinutes,
        consentRequestMinutes = e.consentRequestMinutes,
        fetchHistorySize = e.fetchHistorySize,
        scheduledFetchesPerDay = e.scheduledFetchesPerDay,
        transferPairingDays = e.transferPairingDays,
        lonelyTransferGraceDays = e.lonelyTransferGraceDays,
        outdatedBalanceHours = e.outdatedBalanceHours,
        consentWarningDays = e.consentWarningDays,
        journalOn = e.journalOn,
        journalSizeLimitMb = e.journalSizeLimitMb,
        journalWarningPercent = e.journalWarningPercent,
        backupWarningPercent = e.backupWarningPercent,
        detailedLogging = e.detailedLogging,
        uploadAsCleared = e.uploadAsCleared,
    )

    private fun toEntity(s: OperatorSettings) = OperatorSettingsEntity(
        uploadWindowDays = s.uploadWindowDays,
        fileUploadWindowDays = s.fileUploadWindowDays,
        fetchPeriodDays = s.fetchPeriodDays,
        defaultWaitHours = s.defaultWaitHours,
        requestLimitWarningHours = s.requestLimitWarningHours,
        requestTimeoutSeconds = s.requestTimeoutSeconds,
        uploadPlanMinutes = s.uploadPlanMinutes,
        consentRequestMinutes = s.consentRequestMinutes,
        fetchHistorySize = s.fetchHistorySize,
        scheduledFetchesPerDay = s.scheduledFetchesPerDay,
        transferPairingDays = s.transferPairingDays,
        lonelyTransferGraceDays = s.lonelyTransferGraceDays,
        outdatedBalanceHours = s.outdatedBalanceHours,
        consentWarningDays = s.consentWarningDays,
        journalOn = s.journalOn,
        journalSizeLimitMb = s.journalSizeLimitMb,
        journalWarningPercent = s.journalWarningPercent,
        backupWarningPercent = s.backupWarningPercent,
        detailedLogging = s.detailedLogging,
        uploadAsCleared = s.uploadAsCleared,
    )
}

/** O-11 and the Operator Settings kept per Data Provider (O-13 `SettingsStore`) in `slussen.db`. */
class RoomPluginRegistryStore(private val dao: PluginRegistryDao) : PluginRegistryStore, DataProviderSettingsStore {
    override suspend fun storeRequestLimit(dataProviderId: Long, requests: Int) {
        dao.storeRequestLimit(dataProviderId, requests)
    }

    override suspend fun reconcile(
        dataProviders: List<DataProviderRegistration>,
        fileFormats: List<FileFormatRegistration>,
    ): RegisteredRows {
        val (providers, formats) = dao.reconcile(
            dataProviders,
            fileFormats.map { FileFormatEntity(name = it.name, sourceKey = it.sourceKey) },
        )
        return RegisteredRows(
            providers.map {
                StoredDataProvider(
                    id = it.id,
                    name = it.name,
                    sourceKey = it.sourceKey,
                    givesStableReference = it.stableReference,
                    needsBankConsent = it.bankConsentRequired,
                    fetchCooldownMinutes = it.fetchCooldownMinutes,
                    requestLimit = it.requestLimit,
                    requestLimitPublished = it.requestLimitPublished,
                )
            },
            formats.map { StoredFileFormat(it.id, it.name, it.sourceKey) },
        )
    }
}

/** O-15 and the reads of UC-013 in `slussen.db`. */
class RoomConnectionStore(private val dao: ConnectionDao) : ConnectionStore {
    override suspend fun budgets(): List<StoredBudget> {
        val accounts = dao.budgetAccounts().groupBy { it.budgetId }
        return dao.budgets().map { budget ->
            StoredBudget(
                id = budget.id,
                budgetAppReference = budget.budgetAppReference,
                name = budget.name,
                currency = currencyOf(budget.currency, MAIN_PART),
                lastReadAt = budget.lastReadAt,
                served = budget.served,
                budgetAccounts = accounts[budget.id].orEmpty().map {
                    StoredBudgetAccount(
                        id = it.id,
                        budgetId = it.budgetId,
                        budgetAppReference = it.budgetAppReference,
                        name = it.name,
                        manuallyKept = it.manuallyKept,
                        balance = it.balance?.let(::Amount),
                        balanceReadAt = it.balanceReadAt,
                    )
                },
            )
        }
    }

    override suspend fun banks(): List<StoredBank> = dao.banks().map { StoredBank(it.id, it.name, it.country) }

    override suspend fun bankAccounts(): List<StoredBankAccount> {
        val mappings = dao.mappings().associateBy { it.bankAccountId }
        return dao.bankAccounts().map { e ->
            StoredBankAccount(
                id = e.id,
                generatedIdentifier = e.generatedIdentifier,
                bankId = e.bankId,
                providerBankId = e.providerBankId,
                dataProviderId = e.dataProviderId,
                fileFormatId = e.fileFormatId,
                balanceDataProviderId = e.balanceDataProviderId,
                name = e.name,
                currency = currencyOf(e.currency, MAIN_PART),
                type = valueOfText<BankAccountType>(e.bankAccountType, MAIN_PART) { it.text },
                startDate = e.startDate,
                providerReference = e.providerReference,
                bankAccountFingerprint = e.bankAccountFingerprint,
                balanceProviderReference = e.balanceProviderReference,
                identifiedAt = e.identifiedAt,
                retired = e.retired,
                fundedByBudgetAccountId = e.fundedByBudgetAccountId,
                transferPartnerBudgetAccountId = e.transferPartnerBudgetAccountId,
                mapping = mappings[e.id]?.let { StoredAccountMapping(it.id, it.bankAccountId, it.budgetAccountId, it.firstSentAt) },
            )
        }
    }

    override suspend fun serveBudgets(listed: List<BudgetToServe>, chosen: Set<String>, readAt: Instant): ServeOutcome {
        val refused = dao.serve(
            listed.map { budget ->
                ConnectionDao.ListedBudget(
                    BudgetEntity(
                        budgetAppReference = budget.budgetAppReference,
                        name = budget.name,
                        currency = budget.currency.code,
                        lastReadAt = null,
                        served = true,
                    ),
                    budget.budgetAccounts.map {
                        BudgetAccountEntity(
                            budgetId = 0,
                            budgetAppReference = it.budgetAppReference,
                            name = it.name,
                            manuallyKept = false,
                            balance = it.balance.thousandths,
                            balanceReadAt = readAt,
                        )
                    },
                )
            },
            chosen,
            readAt,
        )
        return refused?.let { (budget, bankAccounts) -> ServeOutcome.StillMapped(budget, bankAccounts) } ?: ServeOutcome.Served
    }

    override suspend fun addBankAccount(bankAccount: NewBankAccount): AddOutcome {
        val entity = BankAccountEntity(
            generatedIdentifier = bankAccount.generatedIdentifier,
            bankId = 0,
            providerBankId = bankAccount.providerBankId,
            dataProviderId = bankAccount.dataProviderId,
            fileFormatId = bankAccount.fileFormatId,
            balanceDataProviderId = bankAccount.balanceDataProviderId,
            name = bankAccount.name,
            currency = bankAccount.currency.code,
            bankAccountType = bankAccount.type.text,
            startDate = bankAccount.startDate,
            providerReference = bankAccount.providerReference,
            bankAccountFingerprint = bankAccount.bankAccountFingerprint,
            balanceProviderReference = bankAccount.balanceProviderReference,
            identifiedAt = bankAccount.identifiedAt,
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
        )
        return when (val added = dao.add(entity, bankAccount.bank, bankAccount.budgetAccountId)) {
            is ConnectionDao.Added.Stored -> AddOutcome.Added(added.bankAccountId)
            is ConnectionDao.Added.AlreadyMapped -> AddOutcome.BudgetAccountAlreadyMapped(added.bankAccountName)
            is ConnectionDao.Added.CurrencyDiffers -> AddOutcome.CurrencyDiffers(currencyOf(added.budgetCurrency, MAIN_PART))
            ConnectionDao.Added.LeftOut -> AddOutcome.LeftOut
            ConnectionDao.Added.ManuallyKept -> AddOutcome.ManuallyKept
        }
    }

    override suspend fun bankNamed(name: String): StoredBank = dao.bankNamed(name).let { StoredBank(it.id, it.name, it.country) }

    override suspend fun leftOutBankAccounts(): List<StoredLeftOutBankAccount> = dao.leftOut().map { it.stored() }

    override suspend fun leaveOut(leftOut: List<NewLeftOutBankAccount>): Boolean = dao.leaveOut(
        leftOut.map { LeftOutBankAccountEntity(dataProviderId = it.dataProviderId, providerReference = it.providerReference, name = it.name, leftOutAt = it.leftOutAt) },
    )

    override suspend fun bringBack(leftOutBankAccountId: Long): StoredLeftOutBankAccount? = dao.bringBack(leftOutBankAccountId)?.stored()

    override suspend fun setManuallyKept(budgetAccountId: Long, manuallyKept: Boolean): KeptOutcome =
        dao.setManuallyKept(budgetAccountId, manuallyKept).outcome()
}

private fun LeftOutBankAccountEntity.stored() = StoredLeftOutBankAccount(id, dataProviderId, providerReference, name, leftOutAt)

private fun ConnectionDao.Kept.outcome(): KeptOutcome = when (this) {
    ConnectionDao.Kept.Stored -> KeptOutcome.Stored
    is ConnectionDao.Kept.StillReceives -> KeptOutcome.StillReceives(bankAccountName)
    ConnectionDao.Kept.NotFound -> KeptOutcome.NotFound
}

/** O-13 `MappingStore` in `slussen.db`: the changes of UC-009 that this build makes. */
class RoomMappingStore(private val dao: ConnectionDao) : MappingStore {
    override suspend fun bringBack(leftOutBankAccountId: Long): StoredLeftOutBankAccount? = dao.bringBack(leftOutBankAccountId)?.stored()

    override suspend fun setManuallyKept(budgetAccountId: Long, manuallyKept: Boolean): KeptOutcome =
        dao.setManuallyKept(budgetAccountId, manuallyKept).outcome()

    override suspend fun changeTypeAndBank(
        bankAccountId: Long,
        type: BankAccountType,
        bank: BankChoice,
        clearFundedBy: Boolean,
        clearTransferPartner: Boolean,
    ) {
        dao.changeTypeAndBank(bankAccountId, type.text, bank, clearFundedBy, clearTransferPartner)
    }
}

/** The Offered Bank Accounts in `device.db` (O-2). */
class RoomOfferedBankAccountStore(private val dao: OfferedBankAccountDao) : OfferedBankAccountStore {
    override suspend fun offered(): List<StoredOfferedBankAccount> = dao.offered().map {
        StoredOfferedBankAccount(
            id = it.id,
            dataProviderId = it.dataProviderId,
            bankId = it.bankId,
            providerReference = it.providerReference,
            bankAccountFingerprint = it.bankAccountFingerprint,
            name = it.name,
            bankAccountKind = it.bankAccountKind,
            currency = it.currency?.let { code -> currencyOf(code, DEVICE_PART) },
            balance = it.balance?.let(::Amount),
            lastFourDigits = it.lastFourDigits,
            proposedBankAccountId = it.proposedBankAccountId,
            offeredAt = it.offeredAt,
        )
    }

    override suspend fun replaceOffered(dataProviderId: Long, bankId: Long?, delivered: List<NewOfferedBankAccount>) {
        dao.replace(
            dataProviderId,
            bankId,
            delivered.map {
                OfferedBankAccountEntity(
                    dataProviderId = dataProviderId,
                    bankId = it.bankId,
                    providerReference = it.providerReference,
                    bankAccountFingerprint = it.bankAccountFingerprint,
                    name = it.name,
                    bankAccountKind = it.bankAccountKind,
                    currency = it.currency?.code,
                    balance = it.balance?.thousandths,
                    lastFourDigits = it.lastFourDigits,
                    proposedBankAccountId = it.proposedBankAccountId,
                    offeredAt = it.offeredAt,
                )
            },
        )
    }
}

/** `PROVIDER_REQUEST` in `slussen.db`, which survives a restart (UC-001 BR-007). */
class RoomProviderRequestStore(private val dao: ProviderRequestDao) : ProviderRequestStore {
    override suspend fun admitWithinLimit(request: NewProviderRequest, windowStart: Instant, requestLimit: Int): Boolean =
        dao.admitWithinLimit(
            ProviderRequestEntity(
                dataProviderId = request.dataProviderId,
                bankAccountId = request.bankAccountId,
                requestKind = request.kind.text,
                unattended = request.unattended,
                sentAt = request.sentAt,
            ),
            windowStart,
            requestLimit,
        )

    override suspend fun sentSince(since: Instant): List<StoredProviderRequest> = dao.sentSince(since).map {
        StoredProviderRequest(
            it.dataProviderId,
            it.bankAccountId,
            valueOfText<ProviderRequestKind>(it.requestKind, MAIN_PART) { kind -> kind.text },
            it.unattended,
            it.sentAt,
        )
    }
}

/** `RECOVERY_CHECK` in `slussen.db`. */
class RoomRecoveryStore(private val dao: RecoveryDao) : RecoveryStore {
    override suspend fun openChecks(): List<StoredRecoveryCheck> = dao.openChecks().map {
        StoredRecoveryCheck(
            it.id,
            valueOfText<RecoveryCause>(it.cause, MAIN_PART) { cause -> cause.text },
            it.openedAt,
            it.comparedAt,
            it.confirmedAt,
        )
    }
}

/** `coordinator_state` in `slussen.db`. */
class RoomCoordinatorStore(private val dao: CoordinatorDao) : CoordinatorStore {
    override suspend fun mode(): CoordinatorMode =
        dao.state()?.let { state -> modeOf(state.mode) } ?: CoordinatorMode.Normal

    companion object {
        /** The texts of `coordinator_state.mode` (ADR-002 §2). */
        fun textOf(mode: CoordinatorMode): String = when (mode) {
            CoordinatorMode.Normal -> "Normal"
            CoordinatorMode.PlanOpen -> "Plan Open"
            CoordinatorMode.Uploading -> "Uploading"
        }

        fun modeOf(text: String): CoordinatorMode =
            CoordinatorMode.entries.firstOrNull { textOf(it) == text } ?: throw UnreadableStoredData(MAIN_PART)
    }
}

private fun FetchEntity.stored() = StoredFetch(
    id = id,
    dataProviderId = dataProviderId,
    startedAt = startedAt,
    startedBy = valueOfText<StartedBy>(startedBy, MAIN_PART) { it.text },
    outcome = valueOfText<FetchOutcome>(outcome, MAIN_PART) { it.text },
    results = emptyList(),
)

private fun BackOffEntity.stored() = StoredBackOff(
    dataProviderId = dataProviderId,
    bankId = bankId,
    cause = valueOfText<BackOffCause>(cause, MAIN_PART) { it.text },
    startsAt = startsAt,
    endsAt = endsAt,
)

/** `FETCH` and `BACK_OFF` in `slussen.db` (UC-001, ADR-002 §1). */
class RoomFetchStore(private val dao: FetchDao) : FetchStore {
    override suspend fun fetchesOf(dataProviderId: Long): List<StoredFetch> = dao.of(dataProviderId).map { it.stored() }

    override suspend fun recordFetch(fetch: NewFetch, keep: Int) = dao.record(
        FetchEntity(
            dataProviderId = fetch.dataProviderId,
            startedAt = fetch.startedAt,
            startedBy = fetch.startedBy.text,
            outcome = fetch.outcome.text,
        ),
        keep,
    )

    override suspend fun backOffsOf(dataProviderId: Long): List<StoredBackOff> = dao.backOffsOf(dataProviderId).map { it.stored() }

    override suspend fun startBackOff(backOff: NewBackOff) = dao.insertBackOff(
        BackOffEntity(
            dataProviderId = backOff.dataProviderId,
            bankId = backOff.bankId,
            cause = backOff.cause.text,
            startsAt = backOff.startsAt,
            endsAt = backOff.endsAt,
        ),
    )
}

/**
 * The import status data of UC-001, UC-002, UC-003 and UC-007. The Fetches and Back-offs of UC-001 are read from
 * `fetch` and `back_off`; the other tables (`fetch_result`, `account_balance`, `transaction_file`, `bank_transaction`,
 * `bank_consent`) come with the slices that build those use cases (ADR-002 §1), and until then each of their reads is
 * empty.
 */
class RoomImportStatusStore(private val fetches: FetchDao) : ImportStatusStore {
    override suspend fun recentFetches(): List<StoredFetch> = fetches.recent().map { it.stored() }

    override suspend fun accountBalances(): List<StoredAccountBalance> = emptyList()

    override suspend fun bankConsents(): List<StoredBankConsent> = emptyList()

    override suspend fun backOffs(): List<StoredBackOff> = fetches.backOffs().map { it.stored() }

    override suspend fun latestTransactionFiles(): List<StoredTransactionFile> = emptyList()

    override suspend fun transactionCounts(): List<StoredTransactionCounts> = emptyList()
}
