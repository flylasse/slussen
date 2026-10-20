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
     * O-15 Mark (UC-013 A9) and O-13 (UC-009 A16): sets or clears `budget_account.manually_kept`; setting it is refused
     * while the Budget Account receives through an Account Mapping.
     */
    suspend fun setManuallyKept(budgetAccountId: Long, manuallyKept: Boolean): KeptOutcome
}

/** O-13 `MappingStore` (ADR-002 §3): the changes of UC-009, each one transaction on `slussen.db`. */
interface MappingStore {
    /**
     * UC-009 A14: the Bank Account type and the Bank (stored first when it is a new one) of [bankAccountId], clearing
     * its Funded By when [clearFundedBy] and its Transfer Partner when [clearTransferPartner], in one transaction.
     * It changes no Account Mapping, identification, `provider_bank_id` or Sent Record (UC-009 BR-012).
     */
    suspend fun changeTypeAndBank(
        bankAccountId: Long,
        type: BankAccountType,
        bank: BankChoice,
        clearFundedBy: Boolean,
        clearTransferPartner: Boolean,
    )

    /** UC-009 A15: deletes one `left_out_bank_account` and returns it, or null when there is none (`MappingStore.bringBack`). */
    suspend fun bringBack(leftOutBankAccountId: Long): StoredLeftOutBankAccount?

    /**
     * UC-009 A16: sets or clears `budget_account.manually_kept`, refused while it receives through an Account Mapping
     * (`MappingStore.setManuallyKept`).
     */
    suspend fun setManuallyKept(budgetAccountId: Long, manuallyKept: Boolean): KeptOutcome
}
