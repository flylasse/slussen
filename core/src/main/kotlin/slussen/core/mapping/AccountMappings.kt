package slussen.core.mapping

import slussen.core.comparison.Comparison
import slussen.core.connection.AddedBankAccount
import slussen.core.connection.BankAccountToMap
import slussen.core.connection.ConnectionReads
import slussen.core.connection.KeptBudgetAccount
import slussen.core.connection.KnownBank
import slussen.core.connection.LeftOutBankAccount
import slussen.core.connection.OtherSideShown
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.OperationResult
import slussen.core.operation.unlessStorageFails
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.MappingStore
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OtherSideKind
import slussen.core.port.OtherSideStored
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.core.transfer.TransferDetection
import slussen.pluginapi.value.CurrencyCode
import java.time.LocalDate

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
    /** Step 4: stored, recorded in the Decision Journal, and the Transfers detected again from stored data (BR-015). */
    data object Stored : TypeAndBankOutcome

    /**
     * Step 4: stored and recorded, but the Transfers could not be detected again now. The Comparison marker stored with
     * the change keeps every Upload Plan from being prepared until they are, at the next start or with the next
     * Comparison (BR-015).
     */
    data object StoredNotDetectedYet : TypeAndBankOutcome

    /** The stored data could not be read or written: nothing was stored. */
    data object NotStored : TypeAndBankOutcome

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
 * The outcome of a change of one Bank Account: retiring and restoring (UC-009 A4, A5), its Funded By or Transfer Partner
 * (A9), its name (A12) and its Start Date (A13).
 */
sealed interface MappingChangeOutcome {
    /**
     * Stored and recorded in the Decision Journal. [statusesGiven] says that the Transaction Statuses were given again
     * and the Transfers detected again from stored data (BR-015); false means that they could not be now: the Comparison
     * marker stored with the change keeps every Upload Plan from being prepared until they are, at the next start or
     * with the next Comparison. A new name is followed by no statuses (A12), so it is always true for one.
     */
    data class Stored(val statusesGiven: Boolean) : MappingChangeOutcome

    /** The Bank Account already is as asked: nothing was stored or recorded. */
    data object Unchanged : MappingChangeOutcome

    /** A9: the Budget Account is not one that can be the Funded By or Transfer Partner of this Bank Account (UC-005 BR-014); nothing was stored. */
    data object NotOffered : MappingChangeOutcome

    /** A12: the name is empty or longer than `BANK_ACCOUNT.name` allows (100); nothing was stored. */
    data object NameNotValid : MappingChangeOutcome

    /** No Bank Account has this id. */
    data object NotFound : MappingChangeOutcome

    /** The stored data could not be read or written: nothing was stored. */
    data object NotStored : MappingChangeOutcome

    /** UC-006 BR-005: an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : MappingChangeOutcome
}

/** A9 step 1: one Budget Account that can be chosen, with the Bank Account Slussen fetches for it. */
data class OtherSideChoice(val budgetAccountId: Long, val budgetAccountName: String, val bankAccountName: String)

/**
 * A9 step 1: for the Bank Account [name], whether the value is its Funded By or its Transfer Partner ([kind]), the value
 * it has now and whether it still applies ([current]), and the Budget Accounts of the same Budget, other than its own,
 * that Slussen fetches ([choices], UC-005 BR-014). Clearing is always possible, also of a value that no longer applies.
 */
data class OtherSideSheet(
    val bankAccountId: Long,
    val name: String,
    val kind: OtherSideKind,
    val current: OtherSideShown,
    val choices: List<OtherSideChoice>,
)

/** The Bank Accounts mapped into one served Budget (UC-009 step 1). */
data class MappedBudget(val budgetId: Long, val name: String, val currency: CurrencyCode, val bankAccounts: List<AddedBankAccount>)

/**
 * UC-009 step 1, BR-013, BR-014: what the Account Mappings list, in this order: the Bank Accounts to map, one line each;
 * the Bank Accounts by the Budget they are mapped into, those not mapped and the Retired Accounts; then, read-only, the
 * Left-Out Bank Accounts as not imported and the Manually Kept Accounts as kept by hand.
 */
data class MappingsList(
    val toMap: List<BankAccountToMap>,
    val byBudget: List<MappedBudget>,
    val notMapped: List<AddedBankAccount>,
    val retired: List<AddedBankAccount>,
    val leftOut: List<LeftOutBankAccount>,
    val manuallyKept: List<KeptBudgetAccount>,
)

/**
 * Manage Account Mappings (UC-009), `:core` `mapping` (ADR-001 §7). Built so far: the list of step 1 in the order of
 * BR-014, each Bank Account with its Funded By or Transfer Partner and whether it applies, with the Left-Out Bank
 * Accounts and the Manually Kept Accounts as read-only lists that lead to the Data Provider's and the Budget App's
 * pages, where they are brought back and marked (A15, A16, BR-013; UC-013 A9, A10); A4 and A5, retiring and restoring;
 * A9, the Funded By and the Transfer Partner; A12, the name; A13, the Start Date; A14, the Bank Account type and the
 * Bank (BR-012). Each change is one storage operation (O-13, ADR-002 §3) admitted by the operation coordinator and
 * recorded in the Decision Journal, and every one but the name is followed, within the same operation, by the statuses
 * given again from stored data ([Comparison.statusesAgainAfter], BR-015). Confirming and identifying a Bank Account and
 * moving, storing and removing an Account Mapping (steps 2-7, A1-A3, A6-A8, A10, A11) come with the phase that builds them.
 */
class AccountMappings(
    private val plugins: RegisteredPlugins,
    private val connections: ConnectionStore,
    private val store: MappingStore,
    private val offered: OfferedBankAccountStore,
    private val coordinator: OperationCoordinator,
    private val journal: DecisionJournal,
    /** BR-015: gives the statuses again after a change, by the Comparison's own conclusions. */
    private val comparison: Comparison,
) {
    private val reads = ConnectionReads(plugins, connections, offered)

    /** Step 1, BR-013, BR-014: the Account Mappings in their order. */
    suspend fun list(): MappingsList {
        val snapshot = reads.snapshot()
        val served = snapshot.budgets.filter { it.served }
        val added = snapshot.bankAccounts.map { it to reads.added(it, snapshot) }
        val budgetOf = served.flatMap { budget -> budget.budgetAccounts.map { it.id to budget } }.toMap()
        // UC-009 BR-014: The Account Mappings list the Bank Accounts to map first, then the Bank Accounts by Budget, those not mapped and the Retired Accounts, then the two read-only lists.
        return MappingsList(
            toMap = reads.toMap(snapshot),
            byBudget = served.mapNotNull { budget ->
                val into = added.filter { (stored, _) -> !stored.retired && budgetOf[stored.mapping?.budgetAccountId]?.id == budget.id }.map { it.second }
                if (into.isEmpty()) null else MappedBudget(budget.id, budget.name, budget.currency, into)
            },
            notMapped = added.filter { (stored, _) -> !stored.retired && budgetOf[stored.mapping?.budgetAccountId] == null }.map { it.second },
            retired = added.filter { (stored, _) -> stored.retired }.map { it.second },
            // UC-009 BR-013: The Left-Out Bank Accounts and the Manually Kept Accounts are listed apart from the Bank Accounts, read-only.
            leftOut = snapshot.leftOut.sortedBy { it.dataProviderId }.map(reads::leftOutView),
            manuallyKept = served.flatMap { budget ->
                budget.budgetAccounts.filter { it.manuallyKept }.map { KeptBudgetAccount(it.id, it.name, budget.name, budget.currency) }
            },
        )
    }

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
        val result = comparison.statusesAgainAfter(OperationKind.AccountMappingChange) {
            var recorded = ""
            // NFR-023: O-13 is the last step that can fail here, so "not stored" is only ever said of a change that was not stored.
            val outcome = unlessStorageFails<TypeAndBankOutcome>(TypeAndBankOutcome.NotStored) {
                val bankAccount = connections.bankAccounts().firstOrNull { it.id == bankAccountId } ?: return@statusesAgainAfter TypeAndBankOutcome.NotFound
                val banks = connections.banks()
                val chosen = when (bank) {
                    is BankChoice.Known -> banks.firstOrNull { it.id == bank.bankId }?.let { bank }
                    is BankChoice.Named -> bank.name.trim().takeIf { it.isNotEmpty() && it.length <= BANK_NAME_MAX_LENGTH }?.let { BankChoice.Named(it) }
                } ?: return@statusesAgainAfter TypeAndBankOutcome.BankNotChosen
                val cleared = cleared(bankAccount, type, budgetAccountNames())
                // UC-009 BR-012: The change asks for none of the confirmations of BR-003 and BR-007; only a Funded By or Transfer Partner the new type clears is stated first (A14 step 3).
                if (cleared != null && !clearingConfirmed) return@statusesAgainAfter TypeAndBankOutcome.ClearingToConfirm(cleared)
                val oldBank = banks.firstOrNull { it.id == bankAccount.bankId }?.name.orEmpty()
                val newBank = when (chosen) {
                    is BankChoice.Known -> banks.firstOrNull { it.id == chosen.bankId }?.name.orEmpty()
                    is BankChoice.Named -> chosen.name
                }
                recorded = "Bank Account ${bankAccount.name}: Bank Account type ${bankAccount.type.text} to ${type.text}, " +
                    "Bank $oldBank to $newBank" + (cleared?.let { "; ${linkName(it.link)} ${it.budgetAccountName} cleared" } ?: "")
                store.changeTypeAndBank(
                    bankAccountId,
                    type,
                    chosen,
                    clearFundedBy = cleared?.link == LinkedBudgetAccount.FundedBy,
                    clearTransferPartner = cleared?.link == LinkedBudgetAccount.TransferPartner,
                )
                TypeAndBankOutcome.Stored
            }
            // A14 step 4: the old and the new values are recorded before the Transfers are detected again.
            if (outcome == TypeAndBankOutcome.Stored) journal.record(JournalEventKind.AccountMappingChange, recorded)
            outcome
        }
        return when (result) {
            // UC-009 BR-015: After a change of a Bank Account type Slussen detects the Transfers among the Bank Transactions not yet uploaded again; when it could not, the change is stored and no Upload Plan is prepared before it has.
            is OperationResult.Ran -> when {
                result.value.change == TypeAndBankOutcome.Stored && !result.value.statusesGiven -> TypeAndBankOutcome.StoredNotDetectedYet
                else -> result.value.change
            }
            is OperationResult.Refused -> TypeAndBankOutcome.Unavailable(result.reason)
        }
    }

    /**
     * One change of the Bank Account [bankAccountId] that BR-015 follows with statuses given again: admitted by the
     * coordinator, stored by [change] (O-13, the last step that can fail), recorded in the Decision Journal with the text
     * [change] gives for it, and concluded from stored data within the same operation.
     */
    private suspend fun changed(bankAccountId: Long, change: suspend (StoredBankAccount) -> Pair<MappingChangeOutcome, String?>): MappingChangeOutcome {
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = comparison.statusesAgainAfter(OperationKind.AccountMappingChange) { storedAndRecorded(bankAccountId, change) }
        return when (result) {
            // UC-009 BR-015: The change and the new statuses are stored so that, when Slussen stops between the two, no Upload Plan is prepared before the statuses are given again.
            is OperationResult.Ran -> when (val outcome = result.value.change) {
                is MappingChangeOutcome.Stored -> outcome.copy(statusesGiven = result.value.statusesGiven)
                else -> outcome
            }
            is OperationResult.Refused -> MappingChangeOutcome.Unavailable(result.reason)
        }
    }

    /**
     * Within an operation: [change] for the stored Bank Account [bankAccountId], then its Decision Journal entry when it
     * stored. NFR-023: a storage that cannot be read or written answers [MappingChangeOutcome.NotStored]; the storage
     * operation is the last step of [change] that can fail, so that is only ever said of a change that was not stored.
     */
    private suspend fun storedAndRecorded(bankAccountId: Long, change: suspend (StoredBankAccount) -> Pair<MappingChangeOutcome, String?>): MappingChangeOutcome {
        val (outcome, recorded) = unlessStorageFails<Pair<MappingChangeOutcome, String?>>(MappingChangeOutcome.NotStored to null) {
            val bankAccount = connections.bankAccounts().firstOrNull { it.id == bankAccountId } ?: return MappingChangeOutcome.NotFound
            change(bankAccount)
        }
        // Postconditions of UC-009: every change is recorded in the Decision Journal, unless it is switched off; recording never blocks the change.
        if (outcome is MappingChangeOutcome.Stored && recorded != null) journal.record(JournalEventKind.AccountMappingChange, recorded)
        return outcome
    }

    private val storedNow = MappingChangeOutcome.Stored(statusesGiven = true)

    /** A4: the Operator retires the Bank Account [bankAccountId]. */
    suspend fun retire(bankAccountId: Long): MappingChangeOutcome = changed(bankAccountId) { bankAccount ->
        when {
            bankAccount.retired -> MappingChangeOutcome.Unchanged to null
            // UC-009 BR-005: A Retired Account is neither fetched, compared, matched nor uploaded; it keeps its identification, Account Mapping, Start Date and history.
            store.setRetired(bankAccountId, retired = true) ->
                storedNow to "Bank Account ${bankAccount.name} retired: no longer fetched, compared, matched or uploaded; " +
                    "its identification, Account Mapping, Start Date and history are kept"
            else -> MappingChangeOutcome.NotFound to null
        }
    }

    /** A5: the Operator restores the Retired Account [bankAccountId]. */
    suspend fun restore(bankAccountId: Long): MappingChangeOutcome = changed(bankAccountId) { bankAccount ->
        when {
            !bankAccount.retired -> MappingChangeOutcome.Unchanged to null
            // UC-009 BR-005: A Retired Account can be restored at any time, with its identification, Account Mapping, Start Date and history.
            store.setRetired(bankAccountId, retired = false) ->
                storedNow to "Bank Account ${bankAccount.name} restored with its identification, Account Mapping, Start Date and history: " +
                    "fetched, compared and uploaded again"
            else -> MappingChangeOutcome.NotFound to null
        }
    }

    /** A13: the Operator sets, changes or clears ([startDate] null) the Start Date of the Bank Account [bankAccountId]. */
    suspend fun setStartDate(bankAccountId: Long, startDate: LocalDate?): MappingChangeOutcome = changed(bankAccountId) { bankAccount ->
        when {
            bankAccount.startDate == startDate -> MappingChangeOutcome.Unchanged to null
            store.setStartDate(bankAccountId, startDate) ->
                storedNow to "Bank Account ${bankAccount.name}: Start Date changed from ${bankAccount.startDate ?: "none"} to ${startDate ?: "none"}"
            else -> MappingChangeOutcome.NotFound to null
        }
    }

    /** A9 step 1: the Funded By or the Transfer Partner of the Bank Account [bankAccountId] and what can be chosen; null when no Bank Account has this id. */
    suspend fun otherSide(bankAccountId: Long): OtherSideSheet? {
        val bankAccounts = connections.bankAccounts()
        val bankAccount = bankAccounts.firstOrNull { it.id == bankAccountId } ?: return null
        val budgets = connections.budgets()
        val current = reads.otherSide(bankAccount, budgets, bankAccounts)
        val names = budgets.flatMap { it.budgetAccounts }.associate { it.id to it.name }
        return OtherSideSheet(
            bankAccountId = bankAccount.id,
            name = bankAccount.name,
            kind = current.kind,
            current = current,
            choices = offeredAsOtherSide(bankAccount, budgets, bankAccounts).map { (budgetAccountId, fetchedFor) ->
                OtherSideChoice(budgetAccountId, names[budgetAccountId].orEmpty(), fetchedFor.name)
            },
        )
    }

    // UC-009 BR-008: A Funded By and a Transfer Partner are always a Budget Account that Slussen fetches (UC-005 BR-014).
    /**
     * A9 step 1: the Budget Accounts of the Budget [bankAccount] is mapped into, other than its own, that Slussen
     * fetches, each with the Bank Account mapped to it, in the Budget's order: those the Transfer detection takes as a
     * side of a Transfer ([TransferDetection.sidesOf]), so never a Manually Kept Account, one no Bank Account is mapped
     * to or one only a Retired Account is mapped to. None while the Bank Account has no Account Mapping.
     */
    private fun offeredAsOtherSide(bankAccount: StoredBankAccount, budgets: List<StoredBudget>, bankAccounts: List<StoredBankAccount>): List<Pair<Long, StoredBankAccount>> {
        val own = bankAccount.mapping?.budgetAccountId ?: return emptyList()
        val budget = budgets.firstOrNull { candidate -> candidate.budgetAccounts.any { it.id == own } } ?: return emptyList()
        val sides = TransferDetection.sidesOf(budget, bankAccounts)
        return budget.budgetAccounts.filter { it.id != own }.mapNotNull { account -> sides[account.id]?.let { account.id to it } }
    }

    /**
     * A9 steps 2-4: the Operator chooses [budgetAccountId] as the Funded By of the credit card Bank Account
     * [bankAccountId], or as the Transfer Partner of another Bank Account, or clears the value with null.
     */
    suspend fun setOtherSide(bankAccountId: Long, budgetAccountId: Long?): MappingChangeOutcome = changed(bankAccountId) { bankAccount ->
        val bankAccounts = connections.bankAccounts()
        val budgets = connections.budgets()
        val current = reads.otherSide(bankAccount, budgets, bankAccounts)
        val names = budgets.flatMap { it.budgetAccounts }.associate { it.id to it.name }
        fun named(id: Long?) = id?.let { "Budget Account ${names[it].orEmpty()}" } ?: "none"
        when {
            current.budgetAccountId == budgetAccountId -> MappingChangeOutcome.Unchanged to null
            // UC-009 BR-008: A Funded By and a Transfer Partner are always a Budget Account that Slussen fetches, of the same Budget and other than the Bank Account's own.
            budgetAccountId != null && offeredAsOtherSide(bankAccount, budgets, bankAccounts).none { it.first == budgetAccountId } ->
                MappingChangeOutcome.NotOffered to null
            else -> when (store.setOtherSide(bankAccountId, current.kind, budgetAccountId)) {
                // A9 step 4: the old and the new value are recorded in the Decision Journal.
                OtherSideStored.Stored ->
                    storedNow to "Bank Account ${bankAccount.name}: ${current.kind.text} changed from ${named(current.budgetAccountId)} to ${named(budgetAccountId)}"
                OtherSideStored.NotAllowed -> MappingChangeOutcome.NotOffered to null
                OtherSideStored.NotFound -> MappingChangeOutcome.NotFound to null
            }
        }
    }

    /** A12: the Operator edits the name of the Bank Account [bankAccountId]. */
    suspend fun rename(bankAccountId: Long, name: String): MappingChangeOutcome {
        val newName = name.trim()
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = coordinator.run(OperationKind.AccountMappingChange, StartedBy.Operator) {
            storedAndRecorded(bankAccountId) { bankAccount ->
                when {
                    newName.isEmpty() || newName.length > BANK_ACCOUNT_NAME_MAX_LENGTH -> MappingChangeOutcome.NameNotValid to null
                    newName == bankAccount.name -> MappingChangeOutcome.Unchanged to null
                    // UC-009 BR-011: The Operator can edit the name at any time; it never identifies the Bank Account, so the new name is stored and nothing else changes.
                    store.rename(bankAccountId, newName) -> storedNow to "Bank Account ${bankAccount.name} renamed to $newName"
                    else -> MappingChangeOutcome.NotFound to null
                }
            }
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> MappingChangeOutcome.Unavailable(result.reason)
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

        /** `BANK_ACCOUNT.name` holds at most 100 characters (entity model). */
        const val BANK_ACCOUNT_NAME_MAX_LENGTH = 100
    }
}
