package slussen.persistence.main

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import slussen.core.fetch.BankTransactionIntake
import slussen.core.identity.ImportIdentifierInput
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.DeliveredTransactions
import slussen.core.port.DeliveryStored
import slussen.core.port.DataProviderRegistration
import slussen.core.port.StoredRequestLimit
import slussen.core.port.IdentificationStored
import slussen.core.port.MappingStored
import slussen.core.port.NewIdentification
import slussen.core.port.SentRecordOutcome
import slussen.core.port.SentThrough
import slussen.core.port.StoredSentRecord
import slussen.core.port.TransferDecision
import slussen.persistence.valueOfText
import java.time.Instant
import java.time.LocalDate

@Dao
abstract class OperatorSettingsDao : StateVersionDao {
    @Query("SELECT * FROM operator_settings ORDER BY id LIMIT 1")
    abstract suspend fun current(): OperatorSettingsEntity?

    @Query("SELECT COUNT(*) FROM operator_settings")
    abstract suspend fun count(): Int

    @Insert
    abstract suspend fun insert(settings: OperatorSettingsEntity)

    @Query("UPDATE operator_settings SET fetch_period_days = :days")
    protected abstract suspend fun updateFetchPeriod(days: Int): Int

    /**
     * The fetch period, an Operator Setting (UC-010 steps 2-4; O-13): one update of the one record, which raises
     * `state_version` in the same transaction, as every change of an Operator Setting does (ADR-007 §6 step 2).
     */
    @Transaction
    open suspend fun storeFetchPeriod(days: Int) {
        if (updateFetchPeriod(days) > 0) raiseStateVersion()
    }
}

@Dao
abstract class PluginRegistryDao : StateVersionDao {
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

    // UC-001 BR-007: A Request Limit the Data Provider publishes is never changed by the Operator's setting.
    @Query("UPDATE data_provider SET request_limit = :requests WHERE id = :id AND request_limit_published = 0")
    protected abstract suspend fun updateRequestLimit(id: Long, requests: Int): Int

    @Query("UPDATE data_provider SET fetch_cooldown_minutes = :minutes WHERE id = :id")
    protected abstract suspend fun updateFetchCooldown(id: Long, minutes: Int): Int

    /**
     * The Request Limit of a Data Provider that publishes none, an Operator Setting (UC-010 BR-002, A11; O-13); a stored
     * change raises `state_version` in the same transaction (ADR-007 §6 step 2), a published limit is left as it is.
     */
    @Transaction
    open suspend fun storeRequestLimit(id: Long, requests: Int) {
        if (updateRequestLimit(id, requests) > 0) raiseStateVersion()
    }

    /**
     * The Fetch Cooldown of a Data Provider, an Operator Setting (UC-010 BR-002, A10; O-13); a stored change raises
     * `state_version` in the same transaction (ADR-007 §6 step 2).
     */
    @Transaction
    open suspend fun storeFetchCooldown(id: Long, minutes: Int) {
        if (updateFetchCooldown(id, minutes) > 0) raiseStateVersion()
    }

    /**
     * O-11 (ADR-002 §2, §3): every Data Provider and File Format of the build, matched by `source_key`, in one
     * transaction. A new Data Provider gets its declared default Fetch Cooldown, an existing one keeps the Operator's;
     * the Request Limit follows `DataProviderRegistration.requestLimitAtStart` (S-31). Nothing is deleted. It runs at a
     * start only, before any operation is admitted and with no Upload Plan open (ADR-007 §8), so it leaves
     * `state_version` as it is.
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

/** One `sent_record` with the Bank Account of its Bank Transaction, as UC-009 BR-003 counts it. */
data class SentRecordOfBankAccount(
    val bankAccountId: Long,
    val bankTransaction: Long,
    val budgetAccountId: Long,
    val sentAsTransfer: Boolean,
    val outcome: String,
) {
    /** The core's view of the record; an unknown `Values:` text is unreadable data. */
    fun stored() = StoredSentRecord(
        storedBankTransaction = bankTransaction,
        budgetAccountId = budgetAccountId,
        sentAsTransfer = sentAsTransfer,
        outcome = valueOfText<SentRecordOutcome>(outcome, "slussen.db") { it.text },
    )
}

@Dao
abstract class ConnectionDao : StateVersionDao {
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
     * The Comparison marker (ADR-007 §3a), set inside the transaction of a change that UC-009 BR-015 follows with statuses
     * given again from stored data, so that no Upload Plan is prepared before O-5 has given them (ADR-002 §3 O-13).
     */
    @Query("UPDATE coordinator_state SET comparison_pending = 1 WHERE id = 1")
    protected abstract suspend fun markComparisonPending()

    /** What O-13 does with every change the statuses depend on: `state_version` raised (ADR-007 §6 step 2) and the Comparison marker set (§3a). */
    private suspend fun storedWithStatusesToGive() {
        raiseStateVersion()
        markComparisonPending()
    }

    /**
     * O-13 (ADR-002 §3), UC-009 A14: the Bank Account type and the Bank, a new Bank stored first, clearing a Funded By or
     * Transfer Partner as asked, in one transaction, which raises `state_version` (ADR-007 §6 step 2) and sets the
     * Comparison marker, since the Transfers are detected again after it (UC-009 BR-015). No Account Mapping,
     * identification or Sent Record is touched.
     */
    @Transaction
    open suspend fun changeTypeAndBank(id: Long, type: String, bank: BankChoice, clearFundedBy: Boolean, clearTransferPartner: Boolean) {
        typeAndBank(id, type, bankIdOf(bank), clearFundedBy, clearTransferPartner)
        storedWithStatusesToGive()
    }

    @Query("UPDATE bank_account SET retired = :retired WHERE id = :id AND retired != :retired")
    protected abstract suspend fun updateRetired(id: Long, retired: Boolean): Int

    /**
     * O-13, UC-009 A4, A5: marks the Bank Account as a Retired Account or restores it, in one transaction with
     * `state_version` and the Comparison marker; false, with nothing changed, when there is none or it already is so. Only
     * `retired` is written: the Account Mapping, the identification, the Start Date and the history stay (UC-009 BR-005).
     */
    @Transaction
    open suspend fun setRetired(id: Long, retired: Boolean): Boolean {
        if (updateRetired(id, retired) == 0) return false
        storedWithStatusesToGive()
        return true
    }

    @Query("UPDATE bank_account SET name = :name WHERE id = :id")
    protected abstract suspend fun updateName(id: Long, name: String): Int

    /**
     * O-13, UC-009 A12: the name and nothing else, a single update that raises `state_version` in the same transaction;
     * no Transaction Status depends on a name, so the Comparison marker is left as it is. False when there is none.
     */
    @Transaction
    open suspend fun rename(id: Long, name: String): Boolean {
        if (updateName(id, name) == 0) return false
        raiseStateVersion()
        return true
    }

    @Query("UPDATE bank_account SET start_date = :startDate WHERE id = :id")
    protected abstract suspend fun updateStartDate(id: Long, startDate: LocalDate?): Int

    /**
     * O-13, UC-009 A13: the Start Date, null to clear it, a single update in one transaction with `state_version` and the
     * Comparison marker. False when there is none.
     */
    @Transaction
    open suspend fun setStartDate(id: Long, startDate: LocalDate?): Boolean {
        if (updateStartDate(id, startDate) == 0) return false
        storedWithStatusesToGive()
        return true
    }

    @Query("SELECT * FROM account_mapping WHERE bank_account_id = :bankAccountId")
    abstract suspend fun mappingOf(bankAccountId: Long): AccountMappingEntity?

    @Query("UPDATE bank_account SET funded_by_budget_account_id = :budgetAccountId WHERE id = :id")
    protected abstract suspend fun updateFundedBy(id: Long, budgetAccountId: Long?): Int

    @Query("UPDATE bank_account SET transfer_partner_budget_account_id = :budgetAccountId WHERE id = :id")
    protected abstract suspend fun updateTransferPartner(id: Long, budgetAccountId: Long?): Int

    /** The outcome of [setOtherSide]. */
    enum class OtherSide { Stored, NotAllowed, NotFound }

    /**
     * O-13 (ADR-002 §3), UC-009 A9: the Funded By ([fundedBy]) or the Transfer Partner of the Bank Account, null to clear
     * it, in one transaction with `state_version` and the Comparison marker. Refused, storing nothing, when the value
     * does not fit the Bank Account type, and unless the Budget Account is in the Budget of the Bank
     * Account's own Account Mapping, differs from its own Budget Account, is no Manually Kept Account and receives
     * through an Account Mapping of a Bank Account that is not a Retired Account (entity model `BANK_ACCOUNT`).
     */
    @Transaction
    open suspend fun setOtherSide(id: Long, fundedBy: Boolean, budgetAccountId: Long?): OtherSide {
        val account = bankAccount(id) ?: return OtherSide.NotFound
        // UC-009 BR-008: A credit card Bank Account can have a Funded By and every other Bank Account a Transfer Partner.
        if ((account.bankAccountType == BankAccountType.CreditCard.text) != fundedBy) return OtherSide.NotAllowed
        if (budgetAccountId != null) {
            val own = mappingOf(id)?.budgetAccountId ?: return OtherSide.NotAllowed
            val ownBudget = budgetAccount(own)?.budgetId
            val chosen = budgetAccount(budgetAccountId) ?: return OtherSide.NotAllowed
            val fetchedFor = mappingInto(budgetAccountId)?.let { bankAccount(it.bankAccountId) }
            // UC-005 BR-014: A Funded By or Transfer Partner is a Budget Account that Slussen fetches, one a Bank Account that is not a Retired Account is mapped to, never a Manually Kept Account; entity model BANK_ACCOUNT: of the same Budget as the Bank Account's own Account Mapping and not its own Budget Account.
            if (budgetAccountId == own || chosen.budgetId != ownBudget || chosen.manuallyKept || fetchedFor == null || fetchedFor.retired) {
                return OtherSide.NotAllowed
            }
        }
        if (fundedBy) updateFundedBy(id, budgetAccountId) else updateTransferPartner(id, budgetAccountId)
        storedWithStatusesToGive()
        return OtherSide.Stored
    }

    // ---- UC-009 identification and moves (build-out phase B5) ----------------------------------------------------------

    @Query(
        "SELECT bt.bank_account_id AS bankAccountId, sr.bank_transaction_id AS bankTransaction, sr.budget_account_id AS budgetAccountId, " +
            "sr.sent_as_transfer AS sentAsTransfer, sr.outcome AS outcome FROM sent_record sr " +
            "JOIN bank_transaction bt ON bt.id = sr.bank_transaction_id ORDER BY sr.id",
    )
    abstract suspend fun sentRecordsByBankAccount(): List<SentRecordOfBankAccount>

    @Query(
        "SELECT bt.bank_account_id AS bankAccountId, sr.bank_transaction_id AS bankTransaction, sr.budget_account_id AS budgetAccountId, " +
            "sr.sent_as_transfer AS sentAsTransfer, sr.outcome AS outcome FROM sent_record sr " +
            "JOIN bank_transaction bt ON bt.id = sr.bank_transaction_id WHERE bt.bank_account_id = :bankAccountId ORDER BY sr.id",
    )
    protected abstract suspend fun sentRecordsOfBankAccount(bankAccountId: Long): List<SentRecordOfBankAccount>

    /** UC-009 BR-003: what was sent through the Account Mapping of [bankAccountId], counted by the core's rule ([SentThrough.of]). */
    @Transaction
    open suspend fun sentThrough(bankAccountId: Long): SentThrough {
        if (bankAccount(bankAccountId) == null) return SentThrough.NONE
        return SentThrough.of(sentRecordsOfBankAccount(bankAccountId).map { it.stored() }, mappingOf(bankAccountId)?.budgetAccountId)
    }

    /** UC-009 BR-007: what a new identification of [bankAccountId] states: its written sends to any Budget Account, mapped or not. */
    @Transaction
    open suspend fun sentForIdentification(bankAccountId: Long): SentThrough {
        if (bankAccount(bankAccountId) == null) return SentThrough.NONE
        return SentThrough.of(sentRecordsOfBankAccount(bankAccountId).map { it.stored() }, budgetAccountId = null)
    }

    /**
     * Entity model `ACCOUNT_MAPPING`: what blocks a move, a removal or a new identification of [bankAccountId] inside the
     * caller's transaction: the count as it is now, while something sent through the Account Mapping was written and
     * [confirmed] is not that count; null when nothing blocks it.
     */
    private suspend fun unconfirmed(bankAccountId: Long, confirmed: SentThrough?): SentThrough? =
        sentThrough(bankAccountId).takeIf { it.needsConfirmation && it != confirmed }

    @Query(
        "UPDATE bank_account SET provider_reference = :providerReference, bank_account_fingerprint = :fingerprint, " +
            "provider_bank_id = :providerBankId, identified_at = :identifiedAt WHERE id = :id",
    )
    protected abstract suspend fun updateIdentification(id: Long, providerReference: String, fingerprint: String?, providerBankId: Long?, identifiedAt: Instant)

    /**
     * O-13 (ADR-002 §3), UC-009 steps 4-6, A1, A6, A7: the new identification with `identified_at`, in one transaction
     * that raises `state_version`; no Transaction Status depends on it, so the Comparison marker is left as it is.
     * Refused, storing nothing, for a Bank Account of a File Format, when another Bank Account of the Data Provider has
     * the reference or the fingerprint or reports its balance, when the reference is left out, and, when the
     * identification changes, while a written send is not confirmed with its numbers.
     */
    @Transaction
    open suspend fun identify(id: Long, identification: NewIdentification, confirmed: SentThrough?): IdentificationStored {
        val account = bankAccount(id) ?: return IdentificationStored.NotFound
        val provider = account.dataProviderId ?: return IdentificationStored.NotFound
        val reference = identification.providerReference
        val fingerprint = identification.bankAccountFingerprint
        // UC-009 BR-004: Two Account Mappings never point to the same Bank Account: a reference or fingerprint another Bank Account of the Data Provider has, or reports its balance with, identifies no second one.
        val usedBy = bankAccounts().filter { it.id != id }.firstOrNull { other ->
            (other.dataProviderId == provider && (other.providerReference == reference || (fingerprint != null && other.bankAccountFingerprint == fingerprint))) ||
                (other.balanceDataProviderId == provider && other.balanceProviderReference == reference)
        }
        if (usedBy != null) return IdentificationStored.AlreadyUsed(usedBy.name)
        // UC-009 BR-004: A Left-Out Bank Account is never identified as a Bank Account until the Operator brings it back.
        if (leftOutOf(provider, reference) != null) return IdentificationStored.LeftOut
        val changes = account.providerReference != reference || account.bankAccountFingerprint != fingerprint
        // UC-009 BR-007: Once a transaction sent through its Account Mapping was written, already present, not yet known or unknown, the Bank Account is identified again only with the Operator's confirmation of their number; every written send of the Bank Account counts, to any Budget Account (the conservative reading).
        if (changes) sentForIdentification(id).takeIf { it.needsConfirmation && it != confirmed }?.let { return IdentificationStored.ToConfirm(it) }
        updateIdentification(id, reference, fingerprint, identification.providerBankId, identification.identifiedAt)
        raiseStateVersion()
        return IdentificationStored.Stored
    }

    /** UC-009 A8 step 1, UC-013 BR-014: the Budget of [budgetAccountId] when [account] can be mapped to it: served, in its currency, receiving through no Account Mapping, no Manually Kept Account. */
    private suspend fun mappableBudget(account: BankAccountEntity, budgetAccountId: Long): BudgetEntity? {
        val budgetAccount = budgetAccount(budgetAccountId) ?: return null
        val budget = budget(budgetAccount.budgetId) ?: return null
        // UC-009 BR-001: A Budget Account receives through at most one Account Mapping, and a Manually Kept Account through none.
        return budget.takeIf { it.served && it.currency == account.currency && !budgetAccount.manuallyKept && mappingInto(budgetAccountId) == null }
    }

    @Query("UPDATE account_mapping SET budget_account_id = :budgetAccountId, first_sent_at = NULL WHERE bank_account_id = :bankAccountId")
    protected abstract suspend fun updateMapping(bankAccountId: Long, budgetAccountId: Long)

    @Query("DELETE FROM account_mapping WHERE bank_account_id = :bankAccountId")
    protected abstract suspend fun deleteMapping(bankAccountId: Long)

    @Query("UPDATE bank_transaction SET status = NULL WHERE bank_account_id = :bankAccountId")
    protected abstract suspend fun emptyStatuses(bankAccountId: Long)

    /** The Transfers without a Transfer Decision with a Transfer Leg of [bankAccountId], none of whose Transfer Legs was uploaded. */
    @Query(
        "SELECT t.id FROM transfer t WHERE t.decision = :proposed " +
            "AND EXISTS (SELECT 1 FROM bank_transaction bt WHERE bt.transfer_id = t.id AND bt.bank_account_id = :bankAccountId) " +
            "AND NOT EXISTS (SELECT 1 FROM bank_transaction bt JOIN sent_record sr ON sr.bank_transaction_id = bt.id " +
            "WHERE bt.transfer_id = t.id AND sr.outcome != :rejected)",
    )
    protected abstract suspend fun undecidedTransfersOf(bankAccountId: Long, proposed: String, rejected: String): List<Long>

    @Query("UPDATE bank_transaction SET transfer_id = NULL WHERE transfer_id IN (:transferIds)")
    protected abstract suspend fun releaseLegsOf(transferIds: List<Long>)

    @Query("DELETE FROM transfer WHERE id IN (:transferIds) AND decision = :proposed")
    protected abstract suspend fun deleteUndecided(transferIds: List<Long>, proposed: String)

    /**
     * Inside the caller's transaction: every Transfer without a Transfer Decision with a Transfer Leg of [bankAccountId]
     * and no uploaded Transfer Leg is removed, with its Transfer Legs released; one the Operator decided on is left to
     * O-5 (UC-005 BR-015).
     */
    private suspend fun releaseUndecided(bankAccountId: Long) {
        // UC-009 BR-015: A Bank Account that lost its Account Mapping, or is not compared yet in the Budget it is mapped into now, is in no Transfer that Slussen proposes; a Transfer the Operator decided on is kept or dropped as UC-005 BR-015 says.
        val ids = undecidedTransfersOf(bankAccountId, TransferDecision.Proposed.text, SentRecordOutcome.Rejected.text)
        if (ids.isEmpty()) return
        releaseLegsOf(ids)
        deleteUndecided(ids, TransferDecision.Proposed.text)
    }

    /** The Bank Transactions of [bankAccountId] not compared yet, and the Transfers it proposed among them released. */
    private suspend fun notComparedYet(bankAccountId: Long) {
        emptyStatuses(bankAccountId)
        releaseUndecided(bankAccountId)
    }

    /**
     * O-13, UC-009 A8: the Account Mapping moved to [budgetAccountId] with `first_sent_at` emptied, in one transaction
     * with `state_version` and the Comparison marker; when the new Budget Account is in another Budget, the Bank
     * Transactions of the Bank Account are not compared yet. Refused, storing nothing, without an Account Mapping, for a
     * Budget Account it cannot be mapped to, and while a written send is not confirmed with its numbers. No Sent Record
     * is touched.
     */
    @Transaction
    open suspend fun move(bankAccountId: Long, budgetAccountId: Long, confirmed: SentThrough?): MappingStored {
        val account = bankAccount(bankAccountId) ?: return MappingStored.NotFound
        val mapping = mappingOf(bankAccountId) ?: return MappingStored.NotAllowed
        val budget = mappableBudget(account, budgetAccountId) ?: return MappingStored.NotAllowed
        // UC-009 BR-003: Once a transaction sent through the Account Mapping was written, already present, not yet known or unknown, it is moved only by the Operator's confirmed action, which states their number.
        unconfirmed(bankAccountId, confirmed)?.let { return MappingStored.ToConfirm(it) }
        // UC-009 BR-015: The statuses are given again by the Budget the Bank Account is mapped into now; while that Budget is not read yet its Bank Transactions are not compared yet, so none keeps a status another Budget gave it.
        if (budgetAccount(mapping.budgetAccountId)?.budgetId != budget.id) notComparedYet(bankAccountId)
        updateMapping(bankAccountId, budgetAccountId)
        storedWithStatusesToGive()
        return MappingStored.Stored
    }

    /**
     * O-13, UC-009 A8 for a Bank Account without an Account Mapping: a new one with `first_sent_at` empty, without a
     * confirmation, in one transaction with `state_version`, the Comparison marker and its Bank Transactions not
     * compared yet. Refused, storing nothing, when it has one, and for a Budget Account a move refuses.
     */
    @Transaction
    open suspend fun mapAgain(bankAccountId: Long, budgetAccountId: Long): MappingStored {
        val account = bankAccount(bankAccountId) ?: return MappingStored.NotFound
        if (mappingOf(bankAccountId) != null || mappableBudget(account, budgetAccountId) == null) return MappingStored.NotAllowed
        // UC-009 BR-015: The statuses are given again by the Budget the Bank Account is mapped into now; while that Budget is not read yet its Bank Transactions are not compared yet, so none keeps a status given before the removal.
        notComparedYet(bankAccountId)
        insert(AccountMappingEntity(bankAccountId = bankAccountId, budgetAccountId = budgetAccountId, firstSentAt = null))
        storedWithStatusesToGive()
        return MappingStored.Stored
    }

    /**
     * O-13, UC-009 A11: the Account Mapping removed, in one transaction with `state_version` and the Comparison marker;
     * the Bank Account, its identification, its Bank Transactions and every Sent Record stay. Refused, storing nothing,
     * without an Account Mapping and while a written send is not confirmed with its numbers.
     */
    @Transaction
    open suspend fun remove(bankAccountId: Long, confirmed: SentThrough?): MappingStored {
        if (bankAccount(bankAccountId) == null) return MappingStored.NotFound
        if (mappingOf(bankAccountId) == null) return MappingStored.NotAllowed
        // UC-009 BR-003: Once a transaction sent through the Account Mapping was written, already present, not yet known or unknown, it is removed only by the Operator's confirmed action, which states their number.
        unconfirmed(bankAccountId, confirmed)?.let { return MappingStored.ToConfirm(it) }
        releaseUndecided(bankAccountId)
        deleteMapping(bankAccountId)
        storedWithStatusesToGive()
        return MappingStored.Stored
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
     * Bank Account. Returns the name of that Budget and its mapped Bank Accounts, or null when stored. What is stored
     * raises `state_version` in the same transaction (ADR-007 §6 step 2); a refusal does not.
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
        raiseStateVersion()
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
     * [bankAccount]'s `bank_id` is replaced by the one of [bank]. What is stored raises `state_version` in the same
     * transaction (ADR-007 §6 step 2); a refusal does not.
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
        raiseStateVersion()
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
     * What is stored raises `state_version` in the same transaction (ADR-007 §6 step 2).
     */
    @Transaction
    open suspend fun leaveOut(rows: List<LeftOutBankAccountEntity>): Boolean {
        if (rows.map { it.dataProviderId to it.providerReference }.distinct().size != rows.size) return false
        // UC-013 BR-011: A Bank Account that is added is never left out, and a reference is left out at most once.
        if (rows.any { leftOutOf(it.dataProviderId, it.providerReference) != null || bankAccountsWith(it.dataProviderId, it.providerReference) > 0 }) {
            return false
        }
        insertLeftOut(rows)
        raiseStateVersion()
        return true
    }

    /**
     * O-15 Bring back (UC-013 A10; UC-009 A15 leads there): deletes one `left_out_bank_account` and returns it, raising
     * `state_version` in the same transaction (ADR-007 §6 step 2); null, with nothing changed, when there is none.
     */
    @Transaction
    open suspend fun bringBack(id: Long): LeftOutBankAccountEntity? = leftOut(id)?.also {
        deleteLeftOut(id)
        raiseStateVersion()
    }

    /** The outcome of [setManuallyKept]. */
    sealed interface Kept {
        data object Stored : Kept

        data class StillReceives(val bankAccountName: String) : Kept

        data object NotFound : Kept
    }

    /**
     * O-15 Mark (UC-013 A9; UC-009 A16 leads there): sets or clears `budget_account.manually_kept` in one transaction;
     * setting it is refused while the Budget Account receives through an Account Mapping. What is stored raises
     * `state_version` in the same transaction (ADR-007 §6 step 2).
     */
    @Transaction
    open suspend fun setManuallyKept(id: Long, manuallyKept: Boolean): Kept {
        budgetAccount(id) ?: return Kept.NotFound
        // UC-009 BR-001: A Manually Kept Account has no Account Mapping.
        if (manuallyKept) mappingInto(id)?.let { return Kept.StillReceives(bankAccount(it.bankAccountId)?.name.orEmpty()) }
        storeManuallyKept(id, manuallyKept)
        raiseStateVersion()
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
abstract class FetchDao : StateVersionDao {
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

    /** The stored Bank Transactions that are a Transfer Leg (`transfer_id` filled), of every Bank Account. */
    @Query("SELECT * FROM bank_transaction WHERE transfer_id IS NOT NULL ORDER BY id")
    abstract suspend fun transferLegs(): List<BankTransactionEntity>

    /** The stored Transfers, for the counts: one the Operator decided on is in no Upload Plan while a side is a Retired Account (UC-005 BR-015). */
    @Query("SELECT * FROM transfer ORDER BY id")
    abstract suspend fun transfers(): List<TransferEntity>

    @Insert
    protected abstract suspend fun insertBankTransactions(transactions: List<BankTransactionEntity>)

    @Update
    protected abstract suspend fun updateBankTransactions(transactions: List<BankTransactionEntity>)

    @Insert
    protected abstract suspend fun insertUnreadableTransactions(transactions: List<UnreadableTransactionEntity>)

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
        // ADR-002 §3, ADR-007 §6: a change of the Bank Transactions raises the version an Upload Plan depends on.
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
abstract class CoordinatorDao : StateVersionDao {
    @Query("SELECT * FROM coordinator_state WHERE id = 1")
    abstract suspend fun state(): CoordinatorStateEntity?

    @Insert
    abstract suspend fun insert(state: CoordinatorStateEntity)

    @Query("UPDATE coordinator_state SET mode = :mode, upload_plan_id = NULL WHERE id = 1")
    abstract suspend fun resetMode(mode: String)

    /** The version of everything an Upload Plan was prepared from (ADR-007 §6 step 2), null before the first start. */
    @Query("SELECT state_version FROM coordinator_state WHERE id = 1")
    abstract suspend fun stateVersion(): Long?
}

/**
 * The stored copy of the Budget App (`budget_app_transaction`), the read cursor (`budget_read_state`) and the Transaction
 * Statuses a Comparison gives (`bank_transaction.status`), with the Comparison-pending marker of `coordinator_state`
 * (ADR-002 §3 O-4, O-5; ADR-007 §3a).
 */
@Dao
abstract class BudgetAppCopyDao {
    @Query("SELECT change_cursor FROM budget_read_state WHERE budget_id = :budgetId")
    abstract suspend fun cursorOf(budgetId: Long): String?

    @Query("UPDATE budget_read_state SET period_read_owed = 1 WHERE budget_id = :budgetId")
    abstract suspend fun owePeriodRead(budgetId: Long)

    @Query("SELECT period_read_owed FROM budget_read_state WHERE budget_id = :budgetId")
    abstract suspend fun periodReadOwed(budgetId: Long): Boolean?

    @Query("SELECT * FROM budget_app_transaction WHERE budget_account_id IN (SELECT id FROM budget_account WHERE budget_id = :budgetId) ORDER BY id")
    abstract suspend fun copyOf(budgetId: Long): List<BudgetAppTransactionEntity>

    @Query("SELECT * FROM budget_app_transaction WHERE budget_app_reference = :reference")
    abstract suspend fun byReference(reference: String): BudgetAppTransactionEntity?

    @Insert
    abstract suspend fun insert(transaction: BudgetAppTransactionEntity): Long

    @Update
    abstract suspend fun update(transaction: BudgetAppTransactionEntity)

    @Query("SELECT * FROM budget_account WHERE budget_id = :budgetId ORDER BY id")
    abstract suspend fun budgetAccountsOf(budgetId: Long): List<BudgetAccountEntity>

    @Query("UPDATE budget_account SET balance = :balance, balance_read_at = :readAt WHERE id = :id")
    abstract suspend fun storeBalance(id: Long, balance: Long, readAt: Instant)

    @Query("UPDATE budget SET last_read_at = :readAt WHERE id = :budgetId")
    abstract suspend fun storeLastReadAt(budgetId: Long, readAt: Instant)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun storeCursor(state: BudgetReadStateEntity)

    @Query("UPDATE coordinator_state SET comparison_pending = :pending, state_version = state_version + 1 WHERE id = 1")
    abstract suspend fun storeComparisonPending(pending: Boolean)

    @Query("SELECT comparison_pending FROM coordinator_state WHERE id = 1")
    abstract suspend fun comparisonPending(): Boolean?

    @Query("UPDATE bank_transaction SET status = :status WHERE id = :id")
    abstract suspend fun storeStatus(id: Long, status: String)

    /** The stored Transfers that a Bank Transaction of [bankAccountId] is a Transfer Leg of. */
    @Query(
        "SELECT * FROM transfer WHERE id IN " +
            "(SELECT transfer_id FROM bank_transaction WHERE bank_account_id = :bankAccountId AND transfer_id IS NOT NULL) ORDER BY id",
    )
    abstract suspend fun transfersOf(bankAccountId: Long): List<TransferEntity>

    @Query("SELECT * FROM transfer ORDER BY id")
    abstract suspend fun transfers(): List<TransferEntity>

    @Query("SELECT * FROM transfer_description ORDER BY id")
    abstract suspend fun transferDescriptions(): List<TransferDescriptionEntity>

    @Insert
    abstract suspend fun insertTransfer(transfer: TransferEntity): Long

    /** What a Comparison detected of a stored Transfer, only while its decision is [proposed]: O-5 never changes a decided one. */
    @Query(
        "UPDATE transfer SET outflow_budget_account_id = :outflowBudgetAccountId, inflow_budget_account_id = :inflowBudgetAccountId, " +
            "amount = :amount, transfer_type = :transferType, guessed = :guessed WHERE id = :id AND decision = :proposed",
    )
    abstract suspend fun updateProposedTransfer(
        id: Long,
        outflowBudgetAccountId: Long,
        inflowBudgetAccountId: Long,
        amount: Long,
        transferType: String,
        guessed: Boolean,
        proposed: String,
    )

    @Query("DELETE FROM transfer WHERE id = :id AND decision = :proposed")
    abstract suspend fun deleteProposedTransfer(id: Long, proposed: String)

    @Query("UPDATE bank_transaction SET transfer_id = :transferId WHERE id IN (:legs)")
    abstract suspend fun storeTransferOf(legs: List<Long>, transferId: Long)

    /** Releases the Transfer Legs of [transferId] that are not among [legs]: they are ordinary Bank Transactions again. */
    @Query("UPDATE bank_transaction SET transfer_id = NULL WHERE transfer_id = :transferId AND id NOT IN (:legs)")
    abstract suspend fun releaseOtherLegs(transferId: Long, legs: List<Long>)

    /** Releases every Transfer Leg of [transferId]. */
    @Query("UPDATE bank_transaction SET transfer_id = NULL WHERE transfer_id = :transferId")
    abstract suspend fun releaseLegs(transferId: Long)

    /**
     * Every Transfer Leg of the Transfers that a Bank Transaction of [bankAccountId] is a Transfer Leg of, also one in
     * another Bank Account, so that a Transfer is always read with all its Transfer Legs (UC-005 BR-015).
     */
    @Query(
        "SELECT * FROM bank_transaction WHERE transfer_id IN " +
            "(SELECT transfer_id FROM bank_transaction WHERE bank_account_id = :bankAccountId AND transfer_id IS NOT NULL) ORDER BY id",
    )
    abstract suspend fun legsOfTransfersOf(bankAccountId: Long): List<BankTransactionEntity>

    /**
     * The stored Transfers whose decision is not [proposed] and that name a Budget Account of [budgetId] as a side: a
     * Transfer the Operator decided on is read also when no Transfer Leg of it lies in a mapped Bank Account (UC-005 BR-015).
     */
    @Query(
        "SELECT * FROM transfer WHERE decision != :proposed AND (" +
            "inflow_budget_account_id IN (SELECT id FROM budget_account WHERE budget_id = :budgetId) OR " +
            "outflow_budget_account_id IN (SELECT id FROM budget_account WHERE budget_id = :budgetId)) ORDER BY id",
    )
    abstract suspend fun decidedTransfersOf(budgetId: Long, proposed: String): List<TransferEntity>

    /** Every Transfer Leg of the Transfer [transferId]. */
    @Query("SELECT * FROM bank_transaction WHERE transfer_id = :transferId ORDER BY id")
    abstract suspend fun legsOf(transferId: Long): List<BankTransactionEntity>

    /** The Sent Records of the Bank Transactions of [bankAccountId] (UC-005 BR-008). */
    @Query("SELECT * FROM sent_record WHERE bank_transaction_id IN (SELECT id FROM bank_transaction WHERE bank_account_id = :bankAccountId) ORDER BY id")
    abstract suspend fun sentRecordsOf(bankAccountId: Long): List<SentRecordEntity>

    /** The Sent Records of the Bank Transaction [bankTransaction]. */
    @Query("SELECT * FROM sent_record WHERE bank_transaction_id = :bankTransaction ORDER BY id")
    abstract suspend fun sentRecordsOfTransaction(bankTransaction: Long): List<SentRecordEntity>

    @Query("SELECT * FROM transfer WHERE id = :id")
    abstract suspend fun transfer(id: Long): TransferEntity?

    /** A Transfer with the Operator's Transfer Decision, in place of the stored one with its `id` (O-13); returns how many were changed, 0 or 1. */
    @Update
    abstract suspend fun updateTransfer(transfer: TransferEntity): Int

    /** Removes the Transfer [id] whatever its decision: one whose Transfer Decision Slussen drops (UC-005 BR-015); returns how many were removed. */
    @Query("DELETE FROM transfer WHERE id = :id")
    abstract suspend fun deleteTransfer(id: Long): Int

    /** Removes the Transfer [id] only while its decision is [proposed]; returns how many were removed, 0 or 1. */
    @Query("DELETE FROM transfer WHERE id = :id AND decision = :proposed")
    abstract suspend fun deleteTransferWithoutDecision(id: Long, proposed: String): Int

    @Query("SELECT COUNT(*) FROM bank_transaction WHERE id IN (:ids)")
    abstract suspend fun countBankTransactions(ids: List<Long>): Int

    /** Sets the Funded By of the Bank Account [id] (UC-005 A3 step 4); returns how many were changed, 0 or 1. */
    @Query("UPDATE bank_account SET funded_by_budget_account_id = :budgetAccountId WHERE id = :id")
    abstract suspend fun storeFundedBy(id: Long, budgetAccountId: Long): Int

    /** Sets the Transfer Partner of the Bank Account [id] (UC-005 A3 step 4); returns how many were changed, 0 or 1. */
    @Query("UPDATE bank_account SET transfer_partner_budget_account_id = :budgetAccountId WHERE id = :id")
    abstract suspend fun storeTransferPartner(id: Long, budgetAccountId: Long): Int
}
