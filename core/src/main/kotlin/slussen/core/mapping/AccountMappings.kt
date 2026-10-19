package slussen.core.mapping

import slussen.core.connection.KnownBank
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.MappingStore
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankAccount

/** A Funded By or a Transfer Partner of a Bank Account (UC-005 BR-006, UC-009 BR-008). */
enum class LinkedBudgetAccount { FundedBy, TransferPartner }

/** A Funded By or Transfer Partner that a change of the Bank Account type clears (UC-009 A14 step 3). */
data class ClearedLink(val link: LinkedBudgetAccount, val budgetAccountName: String)

/** UC-009 A14 step 1: the current Bank Account type and Bank, the four types and the Banks Slussen knows (BR-012). */
data class TypeAndBank(
    val bankAccountId: Long,
    val name: String,
    val sourceName: String,
    val type: BankAccountType,
    val bank: KnownBank,
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    /** What choosing each type would clear, by type; a type that clears nothing is not listed. */
    val clearedBy: Map<BankAccountType, ClearedLink>,
)

/** The outcome of changing a Bank Account's type or Bank (UC-009 A14). */
sealed interface TypeAndBankOutcome {
    /** Step 4: stored and recorded in the Decision Journal. */
    data object Stored : TypeAndBankOutcome

    /** Step 3: the new type clears [cleared]; nothing is stored until the Operator saves again with the confirmation. */
    data class ClearingToConfirm(val cleared: ClearedLink) : TypeAndBankOutcome

    /** BR-012: a Bank is chosen from the known ones or named; an empty name is no Bank. */
    data object BankNotChosen : TypeAndBankOutcome

    /** No Bank Account has this id. */
    data object NotFound : TypeAndBankOutcome

    /** BR-012, UC-006 BR-005: an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : TypeAndBankOutcome
}

/**
 * Manage Account Mappings (UC-009), `:core` `mapping` (ADR-001 §7). This slice builds A14: changing the Bank Account type
 * or the Bank of a Bank Account (BR-012).
 */
class AccountMappings(
    private val plugins: RegisteredPlugins,
    private val connections: ConnectionStore,
    private val store: MappingStore,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
) {
    /** A14 step 1, or null when no Bank Account has this id. */
    suspend fun typeAndBank(bankAccountId: Long): TypeAndBank? {
        val bankAccount = connections.bankAccounts().firstOrNull { it.id == bankAccountId } ?: return null
        val banks = connections.banks().map { KnownBank(it.id, it.name) }
        val names = budgetAccountNames()
        return TypeAndBank(
            bankAccountId = bankAccount.id,
            name = bankAccount.name,
            sourceName = sourceName(bankAccount),
            type = bankAccount.type,
            bank = banks.single { it.bankId == bankAccount.bankId },
            // UC-009 BR-012: The Bank Account type is one of Checking, Savings, Credit Card or Other, the values of adding it (UC-013 BR-009).
            types = BankAccountType.entries,
            knownBanks = banks,
            clearedBy = BankAccountType.entries.mapNotNull { type -> cleared(bankAccount, type, names)?.let { type to it } }.toMap(),
        )
    }

    /** A14 steps 2-4: [clearingConfirmed] is the Operator's save after the statement of step 3. */
    suspend fun changeTypeAndBank(
        bankAccountId: Long,
        type: BankAccountType,
        bank: BankChoice,
        clearingConfirmed: Boolean = false,
    ): TypeAndBankOutcome {
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = coordinator.run(OperationKind.AccountMappingChange, StartedBy.Operator) {
            val bankAccount = connections.bankAccounts().firstOrNull { it.id == bankAccountId } ?: return@run TypeAndBankOutcome.NotFound
            val banks = connections.banks()
            val chosen = when (bank) {
                is BankChoice.Known -> banks.firstOrNull { it.id == bank.bankId }?.let { bank }
                is BankChoice.Named -> bank.name.trim().takeIf { it.isNotEmpty() && it.length <= BANK_NAME_MAX_LENGTH }?.let { BankChoice.Named(it) }
            } ?: return@run TypeAndBankOutcome.BankNotChosen
            val cleared = cleared(bankAccount, type, budgetAccountNames())
            // UC-009 BR-012: The change asks for none of the confirmations of BR-003 and BR-007; only a Funded By or Transfer Partner the new type clears is stated first (A14 step 3).
            if (cleared != null && !clearingConfirmed) return@run TypeAndBankOutcome.ClearingToConfirm(cleared)
            store.changeTypeAndBank(
                bankAccountId,
                type,
                chosen,
                clearFundedBy = cleared?.link == LinkedBudgetAccount.FundedBy,
                clearTransferPartner = cleared?.link == LinkedBudgetAccount.TransferPartner,
            )
            val oldBank = banks.single { it.id == bankAccount.bankId }.name
            val newBank = when (chosen) {
                is BankChoice.Known -> banks.single { it.id == chosen.bankId }.name
                is BankChoice.Named -> chosen.name
            }
            journal.record(
                JournalEventKind.AccountMappingChange,
                "Bank Account ${bankAccount.name}: Bank Account type ${bankAccount.type.text} to ${type.text}, " +
                    "Bank $oldBank to $newBank" + (cleared?.let { "; ${linkName(it.link)} ${it.budgetAccountName} cleared" } ?: ""),
            )
            TypeAndBankOutcome.Stored
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> TypeAndBankOutcome.Unavailable(result.reason)
        }
    }

    /**
     * What [type] would clear of [bankAccount]: a Funded By belongs only to a credit card and a Transfer Partner only to
     * another Bank Account (UC-005 BR-006, entity model `BANK_ACCOUNT` constraints).
     */
    private fun cleared(bankAccount: StoredBankAccount, type: BankAccountType, names: Map<Long, String>): ClearedLink? {
        val fundedBy = bankAccount.fundedByBudgetAccountId
        val partner = bankAccount.transferPartnerBudgetAccountId
        return when {
            type != BankAccountType.CreditCard && fundedBy != null ->
                ClearedLink(LinkedBudgetAccount.FundedBy, names[fundedBy].orEmpty())
            type == BankAccountType.CreditCard && partner != null ->
                ClearedLink(LinkedBudgetAccount.TransferPartner, names[partner].orEmpty())
            else -> null
        }
    }

    private fun linkName(link: LinkedBudgetAccount): String = when (link) {
        LinkedBudgetAccount.FundedBy -> "Funded By"
        LinkedBudgetAccount.TransferPartner -> "Transfer Partner"
    }

    private suspend fun budgetAccountNames(): Map<Long, String> =
        connections.budgets().flatMap { it.budgetAccounts }.associate { it.id to it.name }

    private fun sourceName(bankAccount: StoredBankAccount): String =
        bankAccount.dataProviderId?.let { id -> plugins.dataProviders.single { it.stored.id == id }.stored.name }
            ?: plugins.fileFormats.single { it.id == bankAccount.fileFormatId }.name

    private companion object {
        /** `BANK.name` holds at most 100 characters (entity model). */
        const val BANK_NAME_MAX_LENGTH = 100
    }
}
