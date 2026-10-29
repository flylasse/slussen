package slussen.core.comparison

import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.BudgetAppTransactionToStore
import slussen.core.port.EnteredBy
import slussen.core.port.StoredBudgetAccount
import slussen.core.port.StoredBudgetAppTransaction
import slussen.pluginapi.budgetapp.BudgetAppTransactionRead
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ReadState

/**
 * What O-4 stores of a read (ADR-001 §4.2.3, ADR-002 §2, §3; UC-003 step 1, BR-020): every field as the Budget App
 * reported it, with `entered_by` decided by the core. Pure: the storage applies it inside its transaction to what it
 * holds at that moment.
 */
object BudgetAppCopy {
    // UC-003 BR-020: the stored copy keeps every field as the Budget App last reported it, with its state, merge target and linked Transfer.
    /**
     * The records O-4 upserts for the [read] transactions of one Budget, whose stored Budget Accounts are [budgetAccounts]
     * and whose stored copy is [stored]. A transaction of a Budget Account Slussen does not hold is not stored: the copy
     * belongs to a stored Budget Account (`BUDGET_APP_TRANSACTION.budget_account_id`).
     */
    fun toStore(
        read: List<BudgetAppTransactionRead>,
        budgetAccounts: List<StoredBudgetAccount>,
        stored: List<StoredBudgetAppTransaction>,
    ): List<BudgetAppTransactionToStore> {
        val accountIds = budgetAccounts.associate { it.budgetAppReference to it.id }
        val readById = read.associateBy { it.reference }
        val storedById = stored.associateBy { it.budgetAppReference }
        return read.mapNotNull { transaction ->
            val accountId = accountIds[transaction.budgetAccountReference] ?: return@mapNotNull null
            // ADR-001 §4.2.3: the other side of a linked transfer, as read now or as stored before.
            val linked = transaction.transferLink?.transactionReference?.let { other ->
                readById[other]?.let { enteredBy(it, linkedEnteredBy = null) } ?: storedById[other]?.enteredBy
            }
            val state = transaction.state
            BudgetAppTransactionToStore(
                budgetAccountId = accountId,
                budgetAppReference = transaction.reference,
                transactionDate = transaction.date,
                amount = transaction.amount,
                payee = transaction.payee,
                memo = transaction.memo,
                importIdentifier = transaction.importIdentifier,
                enteredBy = enteredBy(transaction, linked),
                transferLinkReference = transaction.transferLink?.transactionReference,
                mergedIntoReference = (state as? ReadState.Merged)?.intoReference,
                cleared = transaction.cleared,
                approved = transaction.approved,
                state = when (state) {
                    ReadState.Present -> BudgetAppTransactionState.Present
                    ReadState.Deleted -> BudgetAppTransactionState.Deleted
                    is ReadState.Merged -> BudgetAppTransactionState.Merged
                },
            )
        }
    }

    // UC-003 BR-008: An Import whose Import Identifier begins with Slussen's marker is Slussen's own, never an Import by an Other Import Path.
    /**
     * How [read] came into the Budget App (ADR-001 §4.2.3, ADR-008 §1, `BUDGET_APP_TRANSACTION.entered_by`): a transaction
     * whose Import Identifier begins with Slussen's marker is Slussen when its form is one this build produces and
     * Slussen Unsupported Format otherwise; another Import is Other Import Path; the other side of a linked transfer to a
     * Slussen transaction ([linkedEnteredBy]) is Transfer Counterpart; everything else is Typed Entry.
     */
    fun enteredBy(read: BudgetAppTransactionRead, linkedEnteredBy: EnteredBy?): EnteredBy {
        val identifier = read.importIdentifier
        return when {
            identifier != null && ImportIdentifierProtocol.isSlussens(identifier) ->
                if (ImportIdentifierProtocol.hasSupportedFormat(identifier)) EnteredBy.Slussen else EnteredBy.SlussenUnsupportedFormat
            read.entry == EntryKind.Imported -> EnteredBy.OtherImportPath
            read.entry == EntryKind.TransferSide && linkedEnteredBy == EnteredBy.Slussen -> EnteredBy.TransferCounterpart
            else -> EnteredBy.TypedEntry
        }
    }
}
