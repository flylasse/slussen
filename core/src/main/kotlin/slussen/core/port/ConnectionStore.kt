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

/** How the Source identifies a Bank Account that is added, and the rest of its row (UC-013 steps 8-10, A4). */
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
     * Start Date, and its `account_mapping`, in one transaction; refused, storing nothing, when the Budget Account
     * already receives through an Account Mapping or its Budget has another currency (UC-013 step 10, A5).
     */
    suspend fun addBankAccount(bankAccount: NewBankAccount): AddOutcome

    /** The Bank named [name], stored when it is new (`BANK.name` is unique). */
    suspend fun bankNamed(name: String): StoredBank
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
}
