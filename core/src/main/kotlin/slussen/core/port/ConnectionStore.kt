package slussen.core.port

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/** `BUDGET` (entity model) with its Budget Accounts, as stored. */
data class StoredBudget(
    val id: Long,
    val budgetAppReference: String,
    val name: String,
    val currency: CurrencyCode,
    val lastReadAt: Instant?,
    val served: Boolean,
    val budgetAccounts: List<StoredBudgetAccount>,
)

/** `BUDGET_ACCOUNT` (entity model) as stored. */
data class StoredBudgetAccount(
    val id: Long,
    val budgetId: Long,
    val budgetAppReference: String,
    val name: String,
    val manuallyKept: Boolean,
    val balance: Amount?,
    val balanceReadAt: Instant?,
)

/** `BANK` (entity model) as stored. */
data class StoredBank(val id: Long, val name: String, val country: String?)

/** The `Values:` of `BANK_ACCOUNT.bank_account_type`. */
enum class BankAccountType(val text: String) {
    Checking("Checking"),
    Savings("Savings"),
    CreditCard("Credit Card"),
    Other("Other"),
}

/** `BANK_ACCOUNT` (entity model) with its `ACCOUNT_MAPPING`, as stored. */
data class StoredBankAccount(
    val id: Long,
    val generatedIdentifier: String,
    /** The Bank the Operator confirmed or chose: it names and groups the Bank Account only (UC-007 BR-009). */
    val bankId: Long,
    /**
     * `provider_bank_id` (S-33): the Bank the Data Provider reading the Bank Account names for it, taken from the
     * Offered Bank Account when it is added or identified; never changed by the Operator's Bank (UC-009 BR-012).
     */
    val providerBankId: Long?,
    val dataProviderId: Long?,
    val fileFormatId: Long?,
    val balanceDataProviderId: Long?,
    val name: String,
    val currency: CurrencyCode,
    val type: BankAccountType,
    val startDate: LocalDate?,
    val providerReference: String?,
    val bankAccountFingerprint: String?,
    val balanceProviderReference: String?,
    val identifiedAt: Instant,
    val retired: Boolean,
    /** `funded_by_budget_account_id`: only a Credit Card has one (entity model `BANK_ACCOUNT` constraints). */
    val fundedByBudgetAccountId: Long?,
    /** `transfer_partner_budget_account_id`: only a Bank Account that is not a Credit Card has one. */
    val transferPartnerBudgetAccountId: Long?,
    val mapping: StoredAccountMapping?,
)

/** `ACCOUNT_MAPPING` (entity model) as stored. */
data class StoredAccountMapping(val id: Long, val bankAccountId: Long, val budgetAccountId: Long, val firstSentAt: Instant?)

/** One Budget as the Budget App listed it, with the Budget Accounts and balances read at the same time. */
data class BudgetToServe(
    val budgetAppReference: String,
    val name: String,
    val currency: CurrencyCode,
    val budgetAccounts: List<BudgetAccountToStore>,
)

data class BudgetAccountToStore(val budgetAppReference: String, val name: String, val balance: Amount)

/** The outcome of O-15 `serveBudgets`. */
sealed interface ServeOutcome {
    data object Served : ServeOutcome

    /** UC-013 A2: nothing changed; [bankAccountNames] are the Bank Accounts mapped into [budgetName]. */
    data class StillMapped(val budgetName: String, val bankAccountNames: List<String>) : ServeOutcome
}

/** The Bank of a Bank Account: one Slussen already knows, or a new one the Operator names (UC-013 BR-009, UC-009 BR-012). */
sealed interface BankChoice {
    data class Known(val bankId: Long) : BankChoice

    /** A Bank by its name; stored when no Bank of that name is known yet (`BANK.name` is unique). */
    data class Named(val name: String) : BankChoice
}

/** How the Source identifies a Bank Account that is added, and the rest of its row (UC-013 steps 11-13, A4). */
data class NewBankAccount(
    val generatedIdentifier: String,
    val bank: BankChoice,
    /** The `bank_id` of the Offered Bank Account the Bank Account is added with, or null (S-33, UC-013 BR-009). */
    val providerBankId: Long?,
    val dataProviderId: Long?,
    val fileFormatId: Long?,
    val balanceDataProviderId: Long?,
    val balanceProviderReference: String?,
    val name: String,
    val currency: CurrencyCode,
    val type: BankAccountType,
    val providerReference: String?,
    val bankAccountFingerprint: String?,
    val identifiedAt: Instant,
    val startDate: LocalDate?,
    val budgetAccountId: Long,
)

/** The outcome of O-15 `addBankAccount`. */
sealed interface AddOutcome {
    data class Added(val bankAccountId: Long) : AddOutcome

    /** UC-013 A5: the Budget Account already receives through the Account Mapping of [bankAccountName]. */
    data class BudgetAccountAlreadyMapped(val bankAccountName: String) : AddOutcome

    /** UC-013 A5: the Budget Account's Budget has another currency than the Bank Account. */
    data class CurrencyDiffers(val budgetCurrency: CurrencyCode) : AddOutcome

    /** UC-013 BR-011: the Data Provider's reference of the Bank Account is left out; a Left-Out one is never added. */
    data object LeftOut : AddOutcome

    /** UC-013 BR-014: the Budget Account is a Manually Kept Account, never paired. */
    data object ManuallyKept : AddOutcome
}

/** `LEFT_OUT_BANK_ACCOUNT` (entity model) as stored in `slussen.db` (UC-013 BR-011). */
data class StoredLeftOutBankAccount(
    val id: Long,
    val dataProviderId: Long,
    val providerReference: String,
    /** The Offered Bank Account's name or product, with its last four digits when it does not end with them (UC-013 BR-004). */
    val name: String?,
    val leftOutAt: Instant,
)

/** One Offered Bank Account whose import the Operator switched off, to remember as a Left-Out Bank Account (UC-013 steps 6-7). */
data class NewLeftOutBankAccount(val dataProviderId: Long, val providerReference: String, val name: String?, val leftOutAt: Instant)

/** The outcome of marking or unmarking a Manually Kept Account in storage (O-15, O-13). */
sealed interface KeptOutcome {
    data object Stored : KeptOutcome

    /** UC-009 BR-001: refused; the Budget Account receives through the Account Mapping of [bankAccountName]. */
    data class StillReceives(val bankAccountName: String) : KeptOutcome

    /** No Budget Account has this id. */
    data object NotFound : KeptOutcome
}

/**
 * The storage of UC-013 (ADR-002 §3, O-15) and the reads it needs. Each method is one transaction on `slussen.db`.
 */
interface ConnectionStore {
    suspend fun budgets(): List<StoredBudget>

    suspend fun banks(): List<StoredBank>

    suspend fun bankAccounts(): List<StoredBankAccount>

    /**
     * O-15 Serve: stores each Budget of [chosen] from [listed] with its Budget Accounts and their balances read at
     * [readAt], sets `served` for exactly the chosen Budgets, and refuses, changing nothing, when a Budget that stops
     * being served has a mapped Bank Account (UC-013 A2).
     */
    suspend fun serveBudgets(listed: List<BudgetToServe>, chosen: Set<String>, readAt: Instant): ServeOutcome

    /**
     * O-15 Add: the new `bank_account` with its name, Bank Account type, Bank (stored first when it is a new one) and
     * Start Date, and its `account_mapping`, in one transaction, deleting the `left_out_bank_account` of the Offered Bank
     * Account chosen for a File Format's balance (UC-013 BR-006); refused, storing nothing, when the Budget Account
     * already receives through an Account Mapping, its Budget has another currency (UC-013 step 12, A5) or it is a
     * Manually Kept Account (BR-014), and when the Bank Account's own reference is left out (BR-011).
     */
    suspend fun addBankAccount(bankAccount: NewBankAccount): AddOutcome

    /** The Bank named [name], stored when it is new (`BANK.name` is unique). */
    suspend fun bankNamed(name: String): StoredBank

    /** The Left-Out Bank Accounts of every Data Provider (UC-013 BR-011). */
    suspend fun leftOutBankAccounts(): List<StoredLeftOutBankAccount>

    /**
     * O-15 Leave out: one `left_out_bank_account` for each of [leftOut], all or none (UC-013 steps 6-7, A12). Refused,
     * storing nothing, when one is already left out or is the reference, or the balance reference, of a Bank Account of
     * that Data Provider (entity model `LEFT_OUT_BANK_ACCOUNT` constraints); returns whether they were stored.
     */
    suspend fun leaveOut(leftOut: List<NewLeftOutBankAccount>): Boolean

    /** O-15 Bring back: deletes one `left_out_bank_account` and returns it, or null when there is none (UC-013 A10). */
    suspend fun bringBack(leftOutBankAccountId: Long): StoredLeftOutBankAccount?

    /**
     * O-15 Mark and unmark (UC-013 A9; UC-009 A16 leads there): sets or clears `budget_account.manually_kept`; setting it is refused
     * while the Budget Account receives through an Account Mapping.
     */
    suspend fun setManuallyKept(budgetAccountId: Long, manuallyKept: Boolean): KeptOutcome

    /**
     * UC-009 step 1, BR-003: for each Bank Account with a Sent Record of one of its Bank Transactions, what was sent
     * through its Account Mapping, counted by [SentThrough.of] from the `sent_record` rows of its Bank Transactions; a
     * Bank Account none of whose Bank Transactions has a Sent Record is not in the map.
     */
    suspend fun sentThroughMappings(): Map<Long, SentThrough>
}

/**
 * UC-009 BR-003, BR-007: what was sent through the Account Mapping of one Bank Account, counted in Bank Transactions, as
 * a move, a removal or a new identification states it. [sent] are those with a Sent Record whose outcome is Written,
 * Already Present, Not Yet Known or Unknown in the Budget Account the Account Mapping names now, also one sent through
 * an earlier Account Mapping of the Bank Account to that Budget Account; they stay where they are and are never sent
 * again. [rejected] are the others that the Budget App rejected: nothing of them was written, and their next send goes to
 * the Budget Account of the current Account Mapping (UC-006 BR-020).
 */
data class SentThrough(val sent: Int, val rejected: Int) {
    // UC-009 BR-003: Once a transaction sent through the Account Mapping has the outcome Written, Already Present, Not Yet Known or Unknown, it is moved or removed only by an action the Operator confirms; while every one was rejected, without that confirmation.
    /** Whether a move or a removal of the Account Mapping, or a new identification of its Bank Account, needs the Operator's confirmation. */
    val needsConfirmation: Boolean get() = sent > 0

    companion object {
        val NONE = SentThrough(sent = 0, rejected = 0)

        // UC-009 BR-003: A transaction counts as sent through the Account Mapping when it was sent from the Bank Account to the Budget Account the Account Mapping now names, also through an earlier Account Mapping of the Bank Account to that Budget Account; a rejected one is counted apart.
        /**
         * The count for one Bank Account from [records], the Sent Records of its own Bank Transactions, for the Budget
         * Account [budgetAccountId] its Account Mapping names, as a move or a removal states it. With null, every Budget
         * Account it sent to counts: what a new identification states, mapped or not, so that it is never stored
         * without the confirmation while something the Bank Account sent was written anywhere (the conservative reading
         * of BR-007 the orchestrator chose after the review of build-out phase B5).
         */
        fun of(records: List<StoredSentRecord>, budgetAccountId: Long?): SentThrough {
            val byTransaction = records.groupBy { it.storedBankTransaction }
            val sent = byTransaction.filterValues { sends ->
                sends.any { it.outcome != SentRecordOutcome.Rejected && (budgetAccountId == null || it.budgetAccountId == budgetAccountId) }
            }.keys
            val rejected = byTransaction.filter { (transaction, sends) -> transaction !in sent && sends.any { it.outcome == SentRecordOutcome.Rejected } }
            return SentThrough(sent.size, rejected.size)
        }
    }
}

/**
 * How the Source identifies a Bank Account from now on (UC-009 step 6): the reference and the fingerprint of the
 * Offered Bank Account the Operator confirmed, with the Bank that Data Provider names for it (`provider_bank_id`, S-33),
 * and the time of the confirmation (`identified_at`).
 */
data class NewIdentification(
    val providerReference: String,
    val bankAccountFingerprint: String?,
    val providerBankId: Long?,
    val identifiedAt: Instant,
)

/** What O-13 did with a new identification of a Bank Account (UC-009 steps 4-6, A1, A2, A6, A7). */
sealed interface IdentificationStored {
    /** Stored with `identified_at`. */
    data object Stored : IdentificationStored

    /**
     * BR-007: refused, nothing stored: something sent through the Account Mapping was written, and the call carried no
     * confirmation of [sentThrough], the numbers as they are now.
     */
    data class ToConfirm(val sentThrough: SentThrough) : IdentificationStored

    /** A2, BR-004: refused, nothing stored: the Bank Account [bankAccountName] is identified by that reference or fingerprint already. */
    data class AlreadyUsed(val bankAccountName: String) : IdentificationStored

    /** BR-004: refused, nothing stored: the reference is a Left-Out Bank Account's (UC-013 BR-011). */
    data object LeftOut : IdentificationStored

    /** Refused, nothing stored: no Bank Account has this id, or it is one of a File Format, which no Data Provider identifies. */
    data object NotFound : IdentificationStored
}

/** What O-13 did with a move, a new Account Mapping or a removal (UC-009 A8, A11). */
sealed interface MappingStored {
    /** Stored. */
    data object Stored : MappingStored

    /**
     * BR-003: refused, nothing stored: something sent through the Account Mapping was written, and the call carried no
     * confirmation of [sentThrough], the numbers as they are now.
     */
    data class ToConfirm(val sentThrough: SentThrough) : MappingStored

    /**
     * Refused, nothing stored: the Budget Account is not one the Bank Account can be mapped to (of a served Budget in
     * its currency, receiving through no Account Mapping, not a Manually Kept Account: A8 step 1, UC-013 BR-014), or the
     * Bank Account has an Account Mapping where a new one is asked for, or none where one is moved or removed.
     */
    data object NotAllowed : MappingStored

    /** No Bank Account has this id. */
    data object NotFound : MappingStored
}

/** What O-13 did with a Funded By or a Transfer Partner (ADR-002 §3; UC-009 A9, BR-008). */
enum class OtherSideStored {
    /** Stored, or cleared. */
    Stored,

    /** Refused, nothing stored: the entity model's `BANK_ACCOUNT` constraints do not allow this value (UC-005 BR-014). */
    NotAllowed,

    /** No Bank Account has this id. */
    NotFound,
}

/**
 * O-13 `MappingStore` (ADR-002 §3): the changes of UC-009, each one transaction on `slussen.db` that raises
 * `coordinator_state.state_version` when it stores something (ADR-007 §6 step 2). A change that UC-009 BR-015 follows with
 * statuses given again and Transfers detected again (the type, retiring and restoring, the Start Date, a Funded By or
 * Transfer Partner, moving, storing or removing an Account Mapping) sets `coordinator_state.comparison_pending` in the
 * same transaction, so that no Upload Plan is prepared before the conclusions follow through O-5, within the same
 * operation or at the next start (ADR-007 §3a). A move, a removal and a new identification check the locks of UC-009
 * BR-003 and BR-007 against the `sent_record` rows inside their own transaction.
 */
interface MappingStore {
    /**
     * UC-009 A14: the Bank Account type and the Bank (stored first when it is a new one) of [bankAccountId], clearing
     * its Funded By when [clearFundedBy] and its Transfer Partner when [clearTransferPartner], in one transaction, with
     * the Comparison marker. It changes no Account Mapping, identification, `provider_bank_id` or Sent Record (UC-009
     * BR-012).
     */
    suspend fun changeTypeAndBank(
        bankAccountId: Long,
        type: BankAccountType,
        bank: BankChoice,
        clearFundedBy: Boolean,
        clearTransferPartner: Boolean,
    )

    /**
     * UC-009 A4, A5: marks [bankAccountId] as a Retired Account, or restores it, with the Comparison marker, and returns
     * whether it stored that; false, with nothing changed, when no Bank Account has this id or it already is so. It
     * changes nothing else: the identification, the Account Mapping, the Start Date and the history are kept (BR-005).
     */
    suspend fun setRetired(bankAccountId: Long, retired: Boolean): Boolean

    /**
     * UC-009 A12: the name of [bankAccountId] and nothing else, without the Comparison marker, since no Transaction
     * Status depends on a name (BR-011); returns whether a Bank Account has this id.
     */
    suspend fun rename(bankAccountId: Long, name: String): Boolean

    /**
     * UC-009 A13: the Start Date of [bankAccountId], null to clear it, with the Comparison marker; returns whether a Bank
     * Account has this id.
     */
    suspend fun setStartDate(bankAccountId: Long, startDate: LocalDate?): Boolean

    /**
     * UC-009 A9: the Funded By or the Transfer Partner ([kind]) of [bankAccountId], null to clear it, with the Comparison
     * marker. Refused, storing nothing, when [kind] does not fit the Bank Account type (a Funded By belongs only to a
     * credit card, a Transfer Partner only to another Bank Account), and unless [budgetAccountId] is in the Budget of the
     * Bank Account's own Account Mapping, differs from its own Budget Account, is no Manually Kept Account and receives
     * through an Account Mapping of a Bank Account that is not a Retired Account (UC-005 BR-014, entity model
     * `BANK_ACCOUNT` constraints).
     */
    suspend fun setOtherSide(bankAccountId: Long, kind: OtherSideKind, budgetAccountId: Long?): OtherSideStored

    /** BR-003: what was sent through the Account Mapping of [bankAccountId] ([SentThrough.of] for its Budget Account); none when there is no such Bank Account. */
    suspend fun sentThrough(bankAccountId: Long): SentThrough

    /**
     * BR-007: what a new identification of [bankAccountId] states: its written sends to any Budget Account, mapped or
     * not ([SentThrough.of] with no Budget Account); none when there is no such Bank Account.
     */
    suspend fun sentForIdentification(bankAccountId: Long): SentThrough

    /**
     * UC-009 steps 4-6, A1, A6, A7: records [identification] as how the Source identifies [bankAccountId], with
     * `identified_at`, in one transaction that raises `state_version`; no Transaction Status depends on it, so the
     * Comparison marker is left as it is (BR-015 names no identification). Refused, storing nothing, when another Bank
     * Account of the same Data Provider has that reference or fingerprint, or reports its balance (A2, BR-004), when the
     * reference is left out (BR-004), and, when the reference or the fingerprint changes, while
     * [SentThrough.needsConfirmation] holds for [sentForIdentification] and [confirmed] is not that count as it is now
     * (BR-007, entity model `ACCOUNT_MAPPING`). The Account Mapping,
     * `first_sent_at` and every Sent Record stay.
     */
    suspend fun identify(bankAccountId: Long, identification: NewIdentification, confirmed: SentThrough?): IdentificationStored

    /**
     * UC-009 A8: moves the Account Mapping of [bankAccountId] to [budgetAccountId] with `first_sent_at` emptied, in one
     * transaction that raises `state_version` and sets the Comparison marker (BR-015). When the new Budget Account is in
     * another Budget, the Bank Transactions of the Bank Account are not compared yet (their status emptied), so that none
     * keeps a status the old Budget gave it until the new one is read (BR-015, UC-003 A1). Refused, storing nothing,
     * when the Bank Account has no Account Mapping, unless the Budget Account is of a served Budget in the Bank Account's
     * currency, receives through no Account Mapping and is no Manually Kept Account, and while
     * [SentThrough.needsConfirmation] holds and [confirmed] is not the count as it is now (BR-003). Every Sent Record
     * keeps its Budget Account.
     */
    suspend fun move(bankAccountId: Long, budgetAccountId: Long, confirmed: SentThrough?): MappingStored

    /**
     * UC-009 A8 for a Bank Account without an Account Mapping (A11): a new Account Mapping to [budgetAccountId] with
     * `first_sent_at` empty, without a confirmation, in one transaction with `state_version`, the Comparison marker and
     * the statuses of its Bank Transactions emptied (not compared yet), as in [move]: they were given before the
     * Account Mapping was removed and are given again only by the Budget it is mapped into now. Refused, storing
     * nothing, when the Bank Account has an Account Mapping, and for a Budget Account a move refuses.
     */
    suspend fun mapAgain(bankAccountId: Long, budgetAccountId: Long): MappingStored

    /**
     * UC-009 A11: removes the Account Mapping of [bankAccountId], in one transaction with `state_version` and the
     * Comparison marker; the Bank Account keeps its identification, its Bank Transactions with their statuses and every
     * Sent Record. Refused, storing nothing, when it has none, and while [SentThrough.needsConfirmation] holds and
     * [confirmed] is not the count as it is now (BR-003).
     */
    suspend fun remove(bankAccountId: Long, confirmed: SentThrough?): MappingStored
}
