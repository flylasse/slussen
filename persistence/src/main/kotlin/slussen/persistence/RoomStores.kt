package slussen.persistence

import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetToServe
import slussen.core.port.ConnectionStore
import slussen.core.port.CoordinatorMode
import slussen.core.port.CoordinatorStore
import slussen.core.port.DataProviderRegistration
import slussen.core.port.FileFormatRegistration
import slussen.core.port.ImportStatusStore
import slussen.core.port.NewBankAccount
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
import slussen.core.port.StoredTransactionCounts
import slussen.core.port.StoredTransactionFile
import slussen.persistence.device.OfferedBankAccountDao
import slussen.persistence.device.OfferedBankAccountEntity
import slussen.persistence.main.BankAccountEntity
import slussen.persistence.main.BudgetAccountEntity
import slussen.persistence.main.BudgetEntity
import slussen.persistence.main.ConnectionDao
import slussen.persistence.main.CoordinatorDao
import slussen.persistence.main.DataProviderEntity
import slussen.persistence.main.FileFormatEntity
import slussen.persistence.main.OperatorSettingsDao
import slussen.persistence.main.OperatorSettingsEntity
import slussen.persistence.main.PluginRegistryDao
import slussen.persistence.main.ProviderRequestDao
import slussen.persistence.main.ProviderRequestEntity
import slussen.persistence.main.RecoveryDao
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

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

/** O-11 in `slussen.db`. */
class RoomPluginRegistryStore(private val dao: PluginRegistryDao) : PluginRegistryStore {
    override suspend fun reconcile(
        dataProviders: List<DataProviderRegistration>,
        fileFormats: List<FileFormatRegistration>,
    ): RegisteredRows {
        val (providers, formats) = dao.reconcile(
            dataProviders.map {
                DataProviderEntity(
                    name = it.name,
                    sourceKey = it.sourceKey,
                    stableReference = it.givesStableReference,
                    bankConsentRequired = it.needsBankConsent,
                    fetchCooldownMinutes = it.defaultFetchCooldownMinutes,
                    requestLimit = it.requestLimit,
                )
            },
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
            bankId = bankAccount.bankId,
            dataProviderId = bankAccount.dataProviderId,
            fileFormatId = bankAccount.fileFormatId,
            balanceDataProviderId = bankAccount.balanceDataProviderId,
            name = bankAccount.name,
            currency = bankAccount.currency.code,
            bankAccountType = bankAccount.type.text,
            startDate = null,
            providerReference = bankAccount.providerReference,
            bankAccountFingerprint = bankAccount.bankAccountFingerprint,
            balanceProviderReference = bankAccount.balanceProviderReference,
            identifiedAt = bankAccount.identifiedAt,
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
        )
        return when (val added = dao.add(entity, bankAccount.budgetAccountId)) {
            is ConnectionDao.Added.Stored -> AddOutcome.Added(added.bankAccountId)
            is ConnectionDao.Added.AlreadyMapped -> AddOutcome.BudgetAccountAlreadyMapped(added.bankAccountName)
            is ConnectionDao.Added.CurrencyDiffers -> AddOutcome.CurrencyDiffers(currencyOf(added.budgetCurrency, MAIN_PART))
        }
    }

    override suspend fun nameAndStartDate(bankAccountId: Long, name: String, startDate: LocalDate?) {
        dao.nameAndStartDate(bankAccountId, name, startDate)
    }

    override suspend fun bankNamed(name: String): StoredBank = dao.bankNamed(name).let { StoredBank(it.id, it.name, it.country) }
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

/**
 * The import status data of UC-001, UC-002, UC-003 and UC-007. Their tables (`fetch`, `fetch_result`,
 * `account_balance`, `back_off`, `transaction_file`, `bank_transaction`, `bank_consent`) come with the slices that
 * build those use cases (ADR-002 §1); until then nothing of them is stored and each read is empty.
 */
class RoomImportStatusStore : ImportStatusStore {
    override suspend fun recentFetches(): List<StoredFetch> = emptyList()

    override suspend fun accountBalances(): List<StoredAccountBalance> = emptyList()

    override suspend fun bankConsents(): List<StoredBankConsent> = emptyList()

    override suspend fun backOffs(): List<StoredBackOff> = emptyList()

    override suspend fun latestTransactionFiles(): List<StoredTransactionFile> = emptyList()

    override suspend fun transactionCounts(): List<StoredTransactionCounts> = emptyList()
}
