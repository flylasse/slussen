package slussen.persistence.main

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import slussen.core.fetch.BankTransactionIntake
import slussen.core.identity.ImportIdentifierInput
import slussen.core.port.BankChoice
import slussen.core.port.DeliveredTransactions
import slussen.core.port.DeliveryStored
import slussen.core.port.DataProviderRegistration
import slussen.core.port.StoredRequestLimit
import java.time.Instant

@Dao
abstract class OperatorSettingsDao {
    @Query("SELECT * FROM operator_settings ORDER BY id LIMIT 1")
    abstract suspend fun current(): OperatorSettingsEntity?

    @Query("SELECT COUNT(*) FROM operator_settings")
    abstract suspend fun count(): Int

    @Insert
    abstract suspend fun insert(settings: OperatorSettingsEntity)
}

@Dao
abstract class PluginRegistryDao {
    @Query("SELECT * FROM data_provider ORDER BY id")
    abstract suspend fun dataProviders(): List<DataProviderEntity>

    @Query("SELECT * FROM file_format ORDER BY id")
    abstract suspend fun fileFormats(): List<FileFormatEntity>

    @Query("SELECT * FROM data_provider WHERE source_key = :sourceKey")
    abstract suspend fun dataProviderBySourceKey(sourceKey: String): DataProviderEntity?

    @Query("SELECT * FROM file_format WHERE source_key = :sourceKey")
    abstract suspend fun fileFormatBySourceKey(sourceKey: String): FileFormatEntity?

    @Insert
    abstract suspend fun insert(dataProvider: DataProviderEntity): Long

    @Update
    abstract suspend fun update(dataProvider: DataProviderEntity)

    @Insert
    abstract suspend fun insert(fileFormat: FileFormatEntity): Long

    @Update
    abstract suspend fun update(fileFormat: FileFormatEntity)

    /** The Request Limit of a Data Provider that publishes none, an Operator Setting (UC-010 BR-002, A11; O-13). */
    // UC-001 BR-007: A Request Limit the Data Provider publishes is never changed by the Operator's setting.
    @Query("UPDATE data_provider SET request_limit = :requests WHERE id = :id AND request_limit_published = 0")
    abstract suspend fun storeRequestLimit(id: Long, requests: Int)

    /**
     * O-11 (ADR-002 §2, §3): every Data Provider and File Format of the build, matched by `source_key`, in one
     * transaction. A new Data Provider gets its declared default Fetch Cooldown, an existing one keeps the Operator's;
     * the Request Limit follows `DataProviderRegistration.requestLimitAtStart` (S-31). Nothing is deleted.
     */
    @Transaction
    open suspend fun reconcile(
        dataProviders: List<DataProviderRegistration>,
        fileFormats: List<FileFormatEntity>,
    ): Pair<List<DataProviderEntity>, List<FileFormatEntity>> {
        val providers = dataProviders.map { declared ->
            val stored = dataProviderBySourceKey(declared.sourceKey)
            val limit = declared.requestLimitAtStart(stored?.let { StoredRequestLimit(it.requestLimit, it.requestLimitPublished) })
            if (stored == null) {
                DataProviderEntity(
                    name = declared.name,
                    sourceKey = declared.sourceKey,
                    stableReference = declared.givesStableReference,
                    bankConsentRequired = declared.needsBankConsent,
                    fetchCooldownMinutes = declared.defaultFetchCooldownMinutes,
                    requestLimit = limit.requests,
                    requestLimitPublished = limit.published,
                ).let { it.copy(id = insert(it)) }
            } else {
                stored.copy(
                    name = declared.name,
                    stableReference = declared.givesStableReference,
                    bankConsentRequired = declared.needsBankConsent,
                    requestLimit = limit.requests,
                    requestLimitPublished = limit.published,
                ).also { update(it) }
            }
        }
        val formats = fileFormats.map { declared ->
            val stored = fileFormatBySourceKey(declared.sourceKey)
            if (stored == null) declared.copy(id = insert(declared)) else stored.copy(name = declared.name).also { update(it) }
        }
        return providers to formats
    }
}

/** A Budget Account with the Budget it belongs to, for the reads of UC-013 and UC-008. */
data class MappingEntry(val bankAccountId: Long, val bankAccountName: String, val budgetId: Long)

@Dao
abstract class ConnectionDao {
    @Query("SELECT * FROM budget ORDER BY id")
    abstract suspend fun budgets(): List<BudgetEntity>

    @Query("SELECT * FROM budget_account ORDER BY id")
    abstract suspend fun budgetAccounts(): List<BudgetAccountEntity>

    @Query("SELECT * FROM bank ORDER BY id")
    abstract suspend fun banks(): List<BankEntity>

    @Query("SELECT * FROM bank_account ORDER BY id")
    abstract suspend fun bankAccounts(): List<BankAccountEntity>

    @Query("SELECT * FROM account_mapping ORDER BY id")
    abstract suspend fun mappings(): List<AccountMappingEntity>

    @Query("SELECT * FROM budget WHERE budget_app_reference = :reference")
    abstract suspend fun budgetByReference(reference: String): BudgetEntity?

    @Query("SELECT * FROM budget_account WHERE budget_app_reference = :reference")
    abstract suspend fun budgetAccountByReference(reference: String): BudgetAccountEntity?

    @Query("SELECT * FROM budget_account WHERE id = :id")
    abstract suspend fun budgetAccount(id: Long): BudgetAccountEntity?

    @Query("SELECT * FROM budget WHERE id = :id")
    abstract suspend fun budget(id: Long): BudgetEntity?

    @Query("SELECT * FROM bank WHERE name = :name")
    abstract suspend fun bankByName(name: String): BankEntity?

    @Query(
        "SELECT ba.id AS bankAccountId, ba.name AS bankAccountName, bu.budget_id AS budgetId FROM account_mapping am " +
            "JOIN bank_account ba ON ba.id = am.bank_account_id " +
            "JOIN budget_account bu ON bu.id = am.budget_account_id ORDER BY ba.id",
    )
    abstract suspend fun mappingEntries(): List<MappingEntry>

    @Query("SELECT * FROM account_mapping WHERE budget_account_id = :budgetAccountId")
    abstract suspend fun mappingInto(budgetAccountId: Long): AccountMappingEntity?

    @Query("SELECT * FROM bank_account WHERE id = :id")
    abstract suspend fun bankAccount(id: Long): BankAccountEntity?

    @Insert
    abstract suspend fun insert(budget: BudgetEntity): Long

    @Update
    abstract suspend fun update(budget: BudgetEntity)

    @Insert
    abstract suspend fun insert(budgetAccount: BudgetAccountEntity): Long

    @Update
    abstract suspend fun update(budgetAccount: BudgetAccountEntity)

    @Insert
    abstract suspend fun insert(bank: BankEntity): Long

    @Insert
    abstract suspend fun insert(bankAccount: BankAccountEntity): Long

    @Insert
    abstract suspend fun insert(mapping: AccountMappingEntity): Long

    @Query("UPDATE budget SET served = 0 WHERE budget_app_reference NOT IN (:chosen)")
    abstract suspend fun stopServingAllBut(chosen: List<String>)

    @Query(
        "UPDATE bank_account SET bank_account_type = :type, bank_id = :bankId, " +
            "funded_by_budget_account_id = CASE WHEN :clearFundedBy THEN NULL ELSE funded_by_budget_account_id END, " +
            "transfer_partner_budget_account_id = CASE WHEN :clearTransferPartner THEN NULL ELSE transfer_partner_budget_account_id END " +
            "WHERE id = :id",
    )
    abstract suspend fun typeAndBank(id: Long, type: String, bankId: Long, clearFundedBy: Boolean, clearTransferPartner: Boolean)

    /**
     * O-13 (ADR-002 §3), UC-009 A14: the Bank Account type and the Bank, a new Bank stored first, clearing a Funded By or
     * Transfer Partner as asked, in one transaction. No Account Mapping, identification or Sent Record is touched.
     */
    @Transaction
    open suspend fun changeTypeAndBank(id: Long, type: String, bank: BankChoice, clearFundedBy: Boolean, clearTransferPartner: Boolean) {
        typeAndBank(id, type, bankIdOf(bank), clearFundedBy, clearTransferPartner)
    }

    private suspend fun bankIdOf(bank: BankChoice): Long = when (bank) {
        is BankChoice.Known -> bank.bankId
        is BankChoice.Named -> bankNamed(bank.name).id
    }

    /** A Budget stored for one listed Budget, with its Budget Accounts and their balances read at [readAt]. */
    data class ListedBudget(val budget: BudgetEntity, val budgetAccounts: List<BudgetAccountEntity>)

    /**
     * O-15 Serve (ADR-002 §3): every chosen Budget stored and served with its Budget Accounts and balances, every other
     * one not served, in one transaction; refused, changing nothing, when a Budget that stops being served has a mapped
     * Bank Account. Returns the name of that Budget and its mapped Bank Accounts, or null when stored.
     */
    @Transaction
    open suspend fun serve(listed: List<ListedBudget>, chosen: Set<String>, readAt: Instant): Pair<String, List<String>>? {
        val mapped = mappingEntries()
        // UC-013 BR-001: A Budget stops being served only when no Bank Account is mapped into it.
        budgets().filter { it.served && it.budgetAppReference !in chosen }.forEach { budget ->
            val names = mapped.filter { it.budgetId == budget.id }.map { it.bankAccountName }
            if (names.isNotEmpty()) return budget.name to names
        }
        stopServingAllBut(chosen.toList())
        listed.filter { it.budget.budgetAppReference in chosen }.forEach { (budget, accounts) ->
            val stored = budgetByReference(budget.budgetAppReference)
            val budgetId = if (stored == null) {
                insert(budget.copy(served = true))
            } else {
                update(stored.copy(name = budget.name, currency = budget.currency, served = true))
                stored.id
            }
            accounts.forEach { account ->
                val existing = budgetAccountByReference(account.budgetAppReference)
                if (existing == null) {
                    insert(account.copy(budgetId = budgetId, balanceReadAt = readAt))
                } else {
                    update(existing.copy(name = account.name, balance = account.balance, balanceReadAt = readAt))
                }
            }
        }
        return null
    }

    /** The outcome of [add]: the new Bank Account's id, or why it was refused. */
    sealed interface Added {
        data class Stored(val bankAccountId: Long) : Added

        data class AlreadyMapped(val bankAccountName: String) : Added

        data class CurrencyDiffers(val budgetCurrency: String) : Added

        data object LeftOut : Added

        data object ManuallyKept : Added
    }

    /**
     * O-15 Add (ADR-002 §3): the new `bank_account` with its Bank, stored first when it is new, and its
     * `account_mapping`, in one transaction, deleting the `left_out_bank_account` of the Offered Bank Account chosen for
     * its balance; refused, storing nothing, when the Budget Account already receives through an Account Mapping, is a
     * Manually Kept Account or its Budget has another currency, and when the Bank Account's own reference is left out.
     * [bankAccount]'s `bank_id` is replaced by the one of [bank].
     */
    @Transaction
    open suspend fun add(bankAccount: BankAccountEntity, bank: BankChoice, budgetAccountId: Long): Added {
        // UC-013 BR-002: A Budget Account that receives through an Account Mapping is not paired again.
        mappingInto(budgetAccountId)?.let { mapping ->
            return Added.AlreadyMapped(bankAccount(mapping.bankAccountId)?.name.orEmpty())
        }
        val budgetAccount = checkNotNull(budgetAccount(budgetAccountId))
        val budget = budget(budgetAccount.budgetId)!!
        // UC-003 BR-013: No amount is ever converted; the Budget has the Bank Account's currency.
        if (budget.currency != bankAccount.currency) return Added.CurrencyDiffers(budget.currency)
        // UC-013 BR-014: A Manually Kept Account is never paired.
        if (budgetAccount.manuallyKept) return Added.ManuallyKept
        val ownProvider = bankAccount.dataProviderId
        val ownReference = bankAccount.providerReference
        // UC-013 BR-011: A Bank Account whose reference is left out is never added; it is brought back first.
        if (ownProvider != null && ownReference != null && leftOutOf(ownProvider, ownReference) != null) return Added.LeftOut
        val id = insert(bankAccount.copy(bankId = bankIdOf(bank)))
        insert(AccountMappingEntity(bankAccountId = id, budgetAccountId = budgetAccountId, firstSentAt = null))
        val balanceProvider = bankAccount.balanceDataProviderId
        val balanceReference = bankAccount.balanceProviderReference
        // UC-013 BR-006: Choosing a Left-Out Bank Account for a File Format's balance brings it back.
        if (balanceProvider != null && balanceReference != null) deleteLeftOut(balanceProvider, balanceReference)
        return Added.Stored(id)
    }

    @Query("SELECT * FROM left_out_bank_account ORDER BY id")
    abstract suspend fun leftOut(): List<LeftOutBankAccountEntity>

    @Query("SELECT * FROM left_out_bank_account WHERE id = :id")
    abstract suspend fun leftOut(id: Long): LeftOutBankAccountEntity?

    @Query("SELECT * FROM left_out_bank_account WHERE data_provider_id = :dataProviderId AND provider_reference = :providerReference")
    abstract suspend fun leftOutOf(dataProviderId: Long, providerReference: String): LeftOutBankAccountEntity?

    @Query(
        "SELECT COUNT(*) FROM bank_account WHERE (data_provider_id = :dataProviderId AND provider_reference = :providerReference) " +
            "OR (balance_data_provider_id = :dataProviderId AND balance_provider_reference = :providerReference)",
    )
    abstract suspend fun bankAccountsWith(dataProviderId: Long, providerReference: String): Int

    @Insert
    abstract suspend fun insertLeftOut(rows: List<LeftOutBankAccountEntity>)

    @Query("DELETE FROM left_out_bank_account WHERE id = :id")
    abstract suspend fun deleteLeftOut(id: Long)

    @Query("DELETE FROM left_out_bank_account WHERE data_provider_id = :dataProviderId AND provider_reference = :providerReference")
    abstract suspend fun deleteLeftOut(dataProviderId: Long, providerReference: String)

    @Query("UPDATE budget_account SET manually_kept = :manuallyKept WHERE id = :id")
    abstract suspend fun storeManuallyKept(id: Long, manuallyKept: Boolean)

    /**
     * O-15 Leave out (ADR-002 §3): one `left_out_bank_account` for each of [rows], all or none, in one transaction;
     * refused, storing nothing, when one is already left out or is the reference or balance reference of a Bank Account.
     */
    @Transaction
    open suspend fun leaveOut(rows: List<LeftOutBankAccountEntity>): Boolean {
        if (rows.map { it.dataProviderId to it.providerReference }.distinct().size != rows.size) return false
        // UC-013 BR-011: A Bank Account that is added is never left out, and a reference is left out at most once.
        if (rows.any { leftOutOf(it.dataProviderId, it.providerReference) != null || bankAccountsWith(it.dataProviderId, it.providerReference) > 0 }) {
            return false
        }
        insertLeftOut(rows)
        return true
    }

    /** O-15 Bring back (UC-013 A10; UC-009 A15 leads there): deletes one `left_out_bank_account` and returns it. */
    @Transaction
    open suspend fun bringBack(id: Long): LeftOutBankAccountEntity? = leftOut(id)?.also { deleteLeftOut(id) }

    /** The outcome of [setManuallyKept]. */
    sealed interface Kept {
        data object Stored : Kept

        data class StillReceives(val bankAccountName: String) : Kept

        data object NotFound : Kept
    }

    /**
     * O-15 Mark (UC-013 A9; UC-009 A16 leads there): sets or clears `budget_account.manually_kept` in one transaction;
     * setting it is refused while the Budget Account receives through an Account Mapping.
     */
    @Transaction
    open suspend fun setManuallyKept(id: Long, manuallyKept: Boolean): Kept {
        budgetAccount(id) ?: return Kept.NotFound
        // UC-009 BR-001: A Manually Kept Account has no Account Mapping.
        if (manuallyKept) mappingInto(id)?.let { return Kept.StillReceives(bankAccount(it.bankAccountId)?.name.orEmpty()) }
        storeManuallyKept(id, manuallyKept)
        return Kept.Stored
    }

    /** The Bank named [name], stored when it is new. */
    @Transaction
    open suspend fun bankNamed(name: String): BankEntity =
        bankByName(name) ?: BankEntity(name = name, country = null).let { it.copy(id = insert(it)) }
}

@Dao
abstract class ProviderRequestDao {
    @Query("SELECT COUNT(*) FROM provider_request WHERE data_provider_id = :dataProviderId AND sent_at > :windowStart")
    abstract suspend fun countSince(dataProviderId: Long, windowStart: Instant): Int

    @Query("SELECT * FROM provider_request WHERE sent_at > :since ORDER BY sent_at")
    abstract suspend fun sentSince(since: Instant): List<ProviderRequestEntity>

    @Insert
    abstract suspend fun insert(request: ProviderRequestEntity): Long

    /** Counts the requests of the window and stores the new one only below the Request Limit, in one transaction. */
    @Transaction
    open suspend fun admitWithinLimit(request: ProviderRequestEntity, windowStart: Instant, requestLimit: Int): Boolean {
        // UC-001 BR-007: A Data Provider gets at most its Request Limit of requests in any rolling 24 hours.
        if (countSince(request.dataProviderId, windowStart) >= requestLimit) return false
        insert(request)
        return true
    }
}

@Dao
abstract class FetchDao {
    @Query("SELECT * FROM fetch ORDER BY started_at DESC, id DESC")
    abstract suspend fun recent(): List<FetchEntity>

    @Query("SELECT * FROM fetch WHERE data_provider_id = :dataProviderId ORDER BY started_at DESC, id DESC")
    abstract suspend fun of(dataProviderId: Long): List<FetchEntity>

    @Insert
    protected abstract suspend fun insert(fetch: FetchEntity): Long

    @Query("DELETE FROM fetch WHERE id NOT IN (SELECT id FROM fetch ORDER BY started_at DESC, id DESC LIMIT :keep)")
    protected abstract suspend fun keepMostRecent(keep: Int)

    @Query("DELETE FROM fetch_result WHERE fetch_id NOT IN (SELECT id FROM fetch ORDER BY started_at DESC, id DESC LIMIT :keep)")
    protected abstract suspend fun keepResultsOfMostRecent(keep: Int)

    /**
     * The new Fetch and the housekeeping of `OPERATOR_SETTINGS.fetch_history_size`, in one transaction: a Fetch that is
     * no longer among the most recent goes with its Fetch Results. Returns the id of the new Fetch.
     */
    @Transaction
    open suspend fun record(fetch: FetchEntity, keep: Int): Long {
        val id = insert(fetch)
        keepResultsOfMostRecent(keep)
        keepMostRecent(keep)
        return id
    }

    @Query("SELECT * FROM fetch_result ORDER BY fetch_id, bank_account_id")
    abstract suspend fun results(): List<FetchResultEntity>

    @Query("SELECT * FROM fetch_result WHERE fetch_id IN (SELECT id FROM fetch WHERE data_provider_id = :dataProviderId) ORDER BY fetch_id, bank_account_id")
    abstract suspend fun resultsOf(dataProviderId: Long): List<FetchResultEntity>

    @Query("SELECT * FROM account_balance ORDER BY bank_account_id")
    abstract suspend fun balances(): List<AccountBalanceEntity>

    @Insert
    protected abstract suspend fun insertResult(result: FetchResultEntity)

    @Query("DELETE FROM account_balance WHERE bank_account_id = :bankAccountId")
    protected abstract suspend fun deleteBalanceOf(bankAccountId: Long)

    @Insert
    protected abstract suspend fun insertBalance(balance: AccountBalanceEntity)

    @Query("SELECT * FROM bank_transaction WHERE bank_account_id = :bankAccountId ORDER BY id")
    abstract suspend fun bankTransactionsOf(bankAccountId: Long): List<BankTransactionEntity>

    @Query("SELECT * FROM unreadable_transaction WHERE bank_account_id = :bankAccountId ORDER BY id")
    abstract suspend fun unreadableTransactionsOf(bankAccountId: Long): List<UnreadableTransactionEntity>

    @Insert
    protected abstract suspend fun insertBankTransactions(transactions: List<BankTransactionEntity>)

    @Update
    protected abstract suspend fun updateBankTransactions(transactions: List<BankTransactionEntity>)

    @Insert
    protected abstract suspend fun insertUnreadableTransactions(transactions: List<UnreadableTransactionEntity>)

    /** ADR-002 §3, ADR-007 §6: a change of the Bank Transactions raises the version an Upload Plan depends on. */
    @Query("UPDATE coordinator_state SET state_version = state_version + 1 WHERE id = 1")
    protected abstract suspend fun raiseStateVersion()

    /**
     * O-1 (ADR-002 §3): for one identified Bank Account, all or nothing, its Fetch Result, its Account Balance in place
     * of the stored one when one is given, and, when [delivered] holds its transactions, the Bank Transactions and
     * Unreadable Transactions the core's intake rules decide from what is stored now: new ones with their
     * `occurrence_number` and Import Identifier ([identify], ADR-008 §4, §5), known ones with changed content, and the
     * Unreadable Transactions beyond the stored ones (UC-001 steps 7-8, 11, A8, A17, BR-016).
     */
    @Transaction
    open suspend fun storeBankAccountDelivery(
        result: FetchResultEntity,
        balance: AccountBalanceEntity?,
        delivered: DeliveredTransactions?,
        identify: (ImportIdentifierInput) -> String,
    ): DeliveryStored {
        val bankAccountId = result.bankAccountId
        insertResult(result)
        if (balance != null) {
            deleteBalanceOf(balance.bankAccountId)
            insertBalance(balance)
        }
        if (delivered == null) return DeliveryStored()
        val plan = BankTransactionIntake.plan(
            bankTransactionsOf(bankAccountId).map { it.stored() },
            unreadableTransactionsOf(bankAccountId).map { it.stored() },
            delivered,
            identify,
        )
        updateBankTransactions(plan.changed.map { it.entity() })
        insertBankTransactions(plan.inserts.map { it.entity(bankAccountId) })
        insertUnreadableTransactions(plan.unreadable.map { it.entity(bankAccountId, delivered.receivedAt) })
        if (plan.inserts.isNotEmpty() || plan.changed.isNotEmpty()) raiseStateVersion()
        return plan.stored
    }

    @Query("SELECT * FROM back_off ORDER BY id")
    abstract suspend fun backOffs(): List<BackOffEntity>

    @Query("SELECT * FROM back_off WHERE data_provider_id = :dataProviderId ORDER BY id")
    abstract suspend fun backOffsOf(dataProviderId: Long): List<BackOffEntity>

    @Insert
    abstract suspend fun insertBackOff(backOff: BackOffEntity)
}

@Dao
abstract class FirstUploadMatchingDao {
    @Query("SELECT * FROM recovery_check WHERE confirmed_at IS NULL ORDER BY id")
    abstract suspend fun openChecks(): List<FirstUploadMatchingEntity>

    @Insert
    abstract suspend fun insert(check: FirstUploadMatchingEntity): Long
}

@Dao
abstract class CoordinatorDao {
    @Query("SELECT * FROM coordinator_state WHERE id = 1")
    abstract suspend fun state(): CoordinatorStateEntity?

    @Insert
    abstract suspend fun insert(state: CoordinatorStateEntity)

    @Query("UPDATE coordinator_state SET mode = :mode, upload_plan_id = NULL WHERE id = 1")
    abstract suspend fun resetMode(mode: String)
}
