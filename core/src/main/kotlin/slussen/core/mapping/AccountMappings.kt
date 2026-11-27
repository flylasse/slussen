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
import slussen.core.port.IdentificationStored
import slussen.core.port.MappingStored
import slussen.core.port.NewIdentification
import slussen.core.port.OtherSideStored
import slussen.core.port.StoredOfferedBankAccount
import slussen.core.port.SentThrough
import slussen.core.port.StartedBy
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.core.transfer.TransferDetection
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

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
 * given again from stored data ([Comparison.statusesAgainAfter], BR-015). Confirming and identifying a Bank Account
 * (steps 2-7, A1-A3, A6, A7, A10) from the stored Offered Bank Accounts, and moving, storing and removing an Account
 * Mapping (A8, A11), each with the locks of BR-003 and BR-007 read from the Sent Records, which the storage checks again
 * in its own transaction; a move or a removal is followed by the statuses given again, an identification by none.
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
    /** Step 6: the time a confirmed identification is recorded with (`identified_at`). */
    private val clock: Clock,
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
                // UC-009 BR-015: After a change of a Bank Account type Slussen detects the Transfers among the Bank Transactions not yet uploaded again from stored data; the change and the Comparison marker are stored together, so that no Upload Plan is prepared before it has.
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
            // A14 step 4: stored, and said to be stored with the Transfers still to detect when the conclusions could not be stored now.
            is OperationResult.Ran -> when {
                result.value.change == TypeAndBankOutcome.Stored && !result.value.statusesGiven -> TypeAndBankOutcome.StoredNotDetectedYet
                else -> result.value.change
            }
            is OperationResult.Refused -> TypeAndBankOutcome.Unavailable(result.reason)
        }
    }

    // UC-009 BR-015: The change and the new statuses are stored so that, when Slussen stops between the two, no Upload Plan is prepared before the statuses are given again.
    /**
     * One change of the Bank Account [bankAccountId] that BR-015 follows with statuses given again: admitted by the
     * coordinator, stored by [change] (O-13, the last step that can fail), recorded in the Decision Journal with the text
     * [change] gives for it, and concluded from stored data within the same operation.
     */
    private suspend fun changed(bankAccountId: Long, change: suspend (StoredBankAccount) -> Pair<MappingChangeOutcome, String?>): MappingChangeOutcome {
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = comparison.statusesAgainAfter(OperationKind.AccountMappingChange) { storedAndRecorded(bankAccountId, change) }
        return when (result) {
            // The outcome says whether the statuses were given again within the operation.
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

    // UC-005 BR-014: A Funded By or Transfer Partner is always a Budget Account that Slussen fetches, one that a Bank Account that is not a Retired Account is mapped to, never a Manually Kept Account.
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
            // UC-009 BR-008: A Funded By and a Transfer Partner are always a Budget Account that Slussen fetches; of the Budget the Bank Account is mapped into and other than its own Budget Account (UC-005 BR-014, entity model BANK_ACCOUNT).
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

    // ---- Steps 2-7, A1-A3, A6, A7, A10: confirming and identifying ---------------------------------------------------

    /** The Offered Bank Accounts of the Data Provider of [bankAccount] that can identify it: never a Left-Out Bank Account (BR-004). */
    private fun identifiableOffers(bankAccount: StoredBankAccount, snapshot: ConnectionReads.Snapshot): List<StoredOfferedBankAccount> =
        snapshot.offered.filter { offer ->
            offer.dataProviderId == bankAccount.dataProviderId &&
                // UC-009 BR-004: A Left-Out Bank Account is never proposed as a likely Bank Account, never offered to confirm or identify a Bank Account, and never identified as one.
                snapshot.leftOut.none { it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference }
        }

    private fun offeredView(offer: StoredOfferedBankAccount, bankAccount: StoredBankAccount, snapshot: ConnectionReads.Snapshot) = OfferedToIdentify(
        offeredBankAccountId = offer.id,
        bankName = offer.bankId?.let { snapshot.bankNames[it] },
        name = offer.name,
        kind = offer.bankAccountKind,
        currency = offer.currency,
        lastFourDigits = offer.lastFourDigits,
        current = offer.providerReference == bankAccount.providerReference,
    )

    /**
     * Steps 2-3, A6 steps 1-2, A7 step 1, A10: what the Bank Account [bankAccountId] is confirmed or identified from, or
     * null when no Bank Account has this id or its transactions come from Transaction Files, which no Data Provider
     * identifies.
     */
    suspend fun identification(bankAccountId: Long): IdentificationSheet? {
        // UC-009 BR-009: The Bank Account is confirmed or identified again from the Offered Bank Accounts stored at the last delivery, without contacting the Data Provider.
        val snapshot = reads.snapshot()
        val bankAccount = snapshot.bankAccounts.firstOrNull { it.id == bankAccountId && it.dataProviderId != null } ?: return null
        val offers = identifiableOffers(bankAccount, snapshot)
        // UC-009 BR-004: Slussen only proposes a likely Bank Account for the Operator to confirm; it never identifies one by a guess.
        val proposed = offers.firstOrNull { it.proposedBankAccountId == bankAccountId }
        return IdentificationSheet(
            bankAccountId = bankAccount.id,
            name = bankAccount.name,
            sourceName = reads.sourceNameOf(bankAccount),
            proposed = proposed?.let { offeredView(it, bankAccount, snapshot) },
            others = offers.filter { it.id != proposed?.id }.map { offeredView(it, bankAccount, snapshot) },
            sentThrough = store.sentThrough(bankAccountId),
        )
    }

    /**
     * Steps 4-7, A1, A6, A7: the Operator confirms the Offered Bank Account [offeredBankAccountId] as the Bank Account
     * [bankAccountId]; [confirmed] is the Operator's confirmation of the numbers A7 stated.
     */
    suspend fun identify(bankAccountId: Long, offeredBankAccountId: Long, confirmed: SentThrough? = null): IdentifyOutcome {
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = coordinator.run(OperationKind.AccountMappingChange, StartedBy.Operator) {
            var recorded = ""
            val outcome = unlessStorageFails<IdentifyOutcome>(IdentifyOutcome.NotStored) {
                val snapshot = reads.snapshot()
                val bankAccount = snapshot.bankAccounts.firstOrNull { it.id == bankAccountId } ?: return@run IdentifyOutcome.NotFound
                if (bankAccount.dataProviderId == null) return@run IdentifyOutcome.NotIdentifiable
                val offer = identifiableOffers(bankAccount, snapshot).firstOrNull { it.id == offeredBankAccountId } ?: return@run IdentifyOutcome.NotOffered
                if (offer.providerReference == bankAccount.providerReference && offer.bankAccountFingerprint == bankAccount.bankAccountFingerprint) {
                    return@run IdentifyOutcome.Unchanged
                }
                val identification = NewIdentification(offer.providerReference, offer.bankAccountFingerprint, offer.bankId, clock.instant())
                // The numbers as the storage checks them: no Upload runs while the coordinator holds this operation (ADR-007 §2).
                val through = store.sentThrough(bankAccountId)
                // UC-009 BR-007: Once a transaction sent through its Account Mapping was written, already present, not yet known or unknown, the Bank Account is identified again only after the Operator confirms their number; until then the current identification stays in use.
                when (val stored = store.identify(bankAccountId, identification, confirmed)) {
                    is IdentificationStored.ToConfirm -> IdentifyOutcome.ToConfirm(stored.sentThrough)
                    // UC-009 BR-004: Two Account Mappings never point to the same Bank Account: an Offered Bank Account another one uses is refused with its name (A2).
                    is IdentificationStored.AlreadyUsed -> IdentifyOutcome.AlreadyUsed(stored.bankAccountName)
                    IdentificationStored.LeftOut -> IdentifyOutcome.NotOffered
                    IdentificationStored.NotFound -> IdentifyOutcome.NotFound
                    IdentificationStored.Stored -> {
                        recorded = "Bank Account ${bankAccount.name} identified as the Offered Bank Account ${offer.name ?: "without a name"} of " +
                            "${reads.sourceNameOf(bankAccount)}, confirmed by the Operator" + identifiedStatement(through)
                        IdentifyOutcome.Identified
                    }
                }
            }
            // Step 7: the confirmation is recorded in the Decision Journal; it never names the Data Provider's reference (NFR-012).
            if (outcome == IdentifyOutcome.Identified) journal.record(JournalEventKind.AccountMappingChange, recorded)
            outcome
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> IdentifyOutcome.Unavailable(result.reason)
        }
    }

    /** A7 step 1, BR-007: the numbers a new identification records, as [through] counts them. */
    private fun identifiedStatement(through: SentThrough): String =
        (if (through.sent > 0) "; ${counted(through.sent)} sent through its Account Mapping stay as they are and are never sent again" else "") +
            (if (through.rejected > 0) "; ${through.rejected} the Budget App rejected are sent again at their next Upload" else "")

    /** A3: the Operator rejects the Offered Bank Account [offeredBankAccountId] proposed as the Bank Account [bankAccountId]. */
    suspend fun reject(bankAccountId: Long, offeredBankAccountId: Long): IdentifyOutcome {
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = coordinator.run(OperationKind.AccountMappingChange, StartedBy.Operator) {
            val snapshot = try {
                reads.snapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@run IdentifyOutcome.NotStored
            }
            val bankAccount = snapshot.bankAccounts.firstOrNull { it.id == bankAccountId } ?: return@run IdentifyOutcome.NotFound
            val proposal = identifiableOffers(bankAccount, snapshot).firstOrNull { it.id == offeredBankAccountId && it.proposedBankAccountId == bankAccountId }
                ?: return@run IdentifyOutcome.NotOffered
            // A3 steps 1-3: no identification is recorded, so the Bank Account's transactions stay out of use; only the rejection is recorded.
            journal.record(
                JournalEventKind.AccountMappingChange,
                "Bank Account ${bankAccount.name}: the proposed match ${proposal.name ?: "without a name"} rejected by the Operator; " +
                    "no identification recorded, its transactions stay out of use until it is identified",
            )
            IdentifyOutcome.Rejected
        }
        return when (result) {
            is OperationResult.Ran -> result.value
            is OperationResult.Refused -> IdentifyOutcome.Unavailable(result.reason)
        }
    }

    // ---- A8, A11: moving, storing and removing an Account Mapping ----------------------------------------------------------

    /**
     * A8 step 1: the Budget Accounts [bankAccount] can be mapped to: of the Budgets Slussen serves, receiving through no
     * Account Mapping, no Manually Kept Account (S-B-10, UC-013 BR-014) and in the Bank Account's currency.
     */
    private fun mappingChoices(bankAccount: StoredBankAccount, snapshot: ConnectionReads.Snapshot): List<MappingChoice> =
        // UC-009 BR-001: A Budget Account receives the transactions of at most one Bank Account, and a Manually Kept Account has no Account Mapping.
        reads.freeBudgetAccounts(snapshot).filter { it.budgetCurrency == bankAccount.currency }.map {
            MappingChoice(it.budgetAccountId, it.budgetName, it.name, it.balance)
        }

    private fun budgetAccountName(budgetAccountId: Long?, snapshot: ConnectionReads.Snapshot): String? =
        budgetAccountId?.let { id -> snapshot.budgets.flatMap { it.budgetAccounts }.firstOrNull { it.id == id }?.name }

    /** A8 steps 1 and 3, A11 step 1: where the Bank Account [bankAccountId] can be mapped and what a move or a removal states; null when there is no such Bank Account. */
    suspend fun moveSheet(bankAccountId: Long): MoveSheet? {
        val snapshot = reads.snapshot()
        val bankAccount = snapshot.bankAccounts.firstOrNull { it.id == bankAccountId } ?: return null
        return MoveSheet(
            bankAccountId = bankAccount.id,
            name = bankAccount.name,
            currentBudgetAccountName = budgetAccountName(bankAccount.mapping?.budgetAccountId, snapshot),
            choices = mappingChoices(bankAccount, snapshot),
            sentThrough = store.sentThrough(bankAccountId),
        )
    }

    /**
     * A8 steps 2-5: the Operator maps the Bank Account [bankAccountId] to [budgetAccountId], moving its Account Mapping
     * or storing a new one when it has none; [confirmed] is the Operator's confirmation of the numbers step 3 stated.
     */
    suspend fun move(bankAccountId: Long, budgetAccountId: Long, confirmed: SentThrough? = null): MoveOutcome = moved(bankAccountId) { bankAccount, snapshot ->
        val from = bankAccount.mapping?.budgetAccountId
        val to = budgetAccountName(budgetAccountId, snapshot)
        // The numbers as the storage checks them: no Upload runs while the coordinator holds this operation (ADR-007 §2).
        val through = store.sentThrough(bankAccountId)
        when {
            from == budgetAccountId -> MoveOutcome.Unchanged to null
            mappingChoices(bankAccount, snapshot).none { it.budgetAccountId == budgetAccountId } || to == null -> MoveOutcome.NotOffered to null
            // UC-009 BR-003: Once a transaction sent through the Account Mapping was written, already present, not yet known or unknown, it is moved only by an action the Operator confirms, after Slussen stated their number.
            from != null -> when (val stored = store.move(bankAccountId, budgetAccountId, confirmed)) {
                MappingStored.Stored -> moveStored to "Bank Account ${bankAccount.name}: Account Mapping moved from Budget Account " +
                    "${budgetAccountName(from, snapshot)} to Budget Account $to" + statement(through, budgetAccountName(from, snapshot), "go to Budget Account $to at their next Upload")
                else -> refusedMove(stored)
            }
            // A8 step 3: a Bank Account that has no Account Mapping is mapped again without a question.
            else -> when (val stored = store.mapAgain(bankAccountId, budgetAccountId)) {
                MappingStored.Stored -> moveStored to "Bank Account ${bankAccount.name} mapped to Budget Account $to" +
                    rejectedStatement(through, "go to Budget Account $to at their next Upload")
                else -> refusedMove(stored)
            }
        }
    }

    /** A11 steps 1-4: the Operator removes the Account Mapping of [bankAccountId]; [confirmed] is the confirmation of the numbers step 1 stated. */
    suspend fun remove(bankAccountId: Long, confirmed: SentThrough? = null): MoveOutcome = moved(bankAccountId) { bankAccount, snapshot ->
        val from = bankAccount.mapping?.budgetAccountId ?: return@moved MoveOutcome.NotMapped to null
        val through = store.sentThrough(bankAccountId)
        // UC-009 BR-003: Once a transaction sent through the Account Mapping was written, already present, not yet known or unknown, it is removed only by an action the Operator confirms; the rejected ones wait until the Bank Account is mapped again.
        when (val stored = store.remove(bankAccountId, confirmed)) {
            MappingStored.Stored -> moveStored to "Bank Account ${bankAccount.name}: Account Mapping to Budget Account ${budgetAccountName(from, snapshot)} removed; " +
                "its identification and history are kept" + statement(through, budgetAccountName(from, snapshot), "wait until the Bank Account is mapped again")
            else -> refusedMove(stored)
        }
    }

    private fun refusedMove(stored: MappingStored): Pair<MoveOutcome, String?> = when (stored) {
        is MappingStored.ToConfirm -> MoveOutcome.ToConfirm(stored.sentThrough)
        MappingStored.NotFound -> MoveOutcome.NotFound
        MappingStored.NotAllowed, MappingStored.Stored -> MoveOutcome.NotOffered
    } to null

    /**
     * A8 step 5, A11 step 4: the number recorded with the change, as [through] counts them: the transactions written
     * through the Account Mapping, which stay in [stayIn], and the rejected ones, which [rejectedThen].
     */
    private fun statement(through: SentThrough, stayIn: String?, rejectedThen: String): String {
        val written = through.sent
        val stays = if (written > 0) {
            "; ${counted(written)} written through it ${if (written == 1) "stays" else "stay"} in Budget Account $stayIn and " +
                "${if (written == 1) "is" else "are"} never sent again; the Operator moves or deletes them in the Budget App"
        } else {
            "; no transaction written through it"
        }
        return stays + rejectedStatement(through, rejectedThen)
    }

    private fun rejectedStatement(through: SentThrough, rejectedThen: String): String =
        if (through.rejected > 0) "; ${through.rejected} the Budget App rejected $rejectedThen" else ""

    // UC-009 BR-015: After moving, storing or removing an Account Mapping, the statuses are given again and the Transfers detected again from stored data; the change and the Comparison marker are stored together, so that no Upload Plan is prepared before they are when Slussen stops between the two.
    /**
     * One move, new Account Mapping or removal of the Bank Account [bankAccountId], which BR-015 follows with statuses
     * given again: admitted by the coordinator, stored by [change] (O-13, the last step that can fail), recorded in the
     * Decision Journal with the text [change] gives for it, and concluded from stored data within the same operation.
     */
    private suspend fun moved(bankAccountId: Long, change: suspend (StoredBankAccount, ConnectionReads.Snapshot) -> Pair<MoveOutcome, String?>): MoveOutcome {
        // UC-009 BR-012: Like every change of Manage Account Mappings, it is not possible while an Upload Plan awaits confirmation or an Upload is written.
        val result = comparison.statusesAgainAfter(OperationKind.AccountMappingChange) {
            // NFR-023: O-13 is the last step that can fail here, so "not stored" is only ever said of a change that was not stored.
            val (outcome, recorded) = unlessStorageFails<Pair<MoveOutcome, String?>>(MoveOutcome.NotStored to null) {
                val snapshot = reads.snapshot()
                val bankAccount = snapshot.bankAccounts.firstOrNull { it.id == bankAccountId } ?: return@unlessStorageFails MoveOutcome.NotFound to null
                change(bankAccount, snapshot)
            }
            // A8 step 5, A11 step 4: the change is recorded, with its number, before the statuses are given again.
            if (outcome is MoveOutcome.Stored && recorded != null) journal.record(JournalEventKind.AccountMappingChange, recorded)
            outcome
        }
        return when (result) {
            is OperationResult.Ran -> when (val outcome = result.value.change) {
                // The outcome says whether the statuses were given again within the operation.
                is MoveOutcome.Stored -> outcome.copy(statusesGiven = result.value.statusesGiven)
                else -> outcome
            }
            is OperationResult.Refused -> MoveOutcome.Unavailable(result.reason)
        }
    }

    private val moveStored = MoveOutcome.Stored(statusesGiven = true)

    private fun counted(n: Int): String = if (n == 1) "1 transaction" else "$n transactions"

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
