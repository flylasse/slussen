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
 * Transfer Partner) sets `coordinator_state.comparison_pending` in the same transaction, so that no Upload Plan is
 * prepared before the conclusions follow through O-5, within the same operation or at the next start (ADR-007 §3a).
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
}
