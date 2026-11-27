package slussen.core.transfer

import slussen.core.journal.DecisionJournal
import slussen.core.port.DroppedTransferDecision
import slussen.core.port.JournalEventKind
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredTransfer
import slussen.core.port.TransferDecision
import slussen.pluginapi.value.Amount
import kotlin.math.abs

/**
 * What the Decision Journal says of a Transfer and of a Transfer Decision (UC-005 BR-011, BR-015; UC-011 BR-001), in
 * Slussen's own words: Budget Accounts, the amount and the decision, never a payee or memo (UC-011 BR-003).
 */
internal object TransferJournal {
    private const val THOUSANDTHS = 1000L

    private fun amountText(amount: Amount): String {
        val thousandths = abs(amount.thousandths)
        return "${thousandths / THOUSANDTHS}.${(thousandths % THOUSANDTHS).toString().padStart(3, '0')}"
    }

    /** One Transfer by its kind, amount, two Budget Accounts and how many of its Transfer Legs have arrived. */
    fun describe(transfer: StoredTransfer, legs: Int, budgetAccountNames: Map<Long, String>): String {
        val from = transfer.outflowBudgetAccountId?.let { "Budget Account ${budgetAccountNames[it].orEmpty()}" } ?: "no proposed Budget Account"
        val to = "Budget Account ${budgetAccountNames[transfer.inflowBudgetAccountId].orEmpty()}"
        val arrived = if (legs == 2) "both Transfer Legs arrived" else "one Transfer Leg arrived"
        val guessed = if (transfer.guessed) ", a Guessed Transfer" else ""
        return "${transfer.type.text} of ${amountText(transfer.amount)} from $from to $to, $arrived$guessed"
    }

    // UC-005 BR-011: Every Transfer Decision and every change to it is recorded once for each Transfer Leg of the Transfer, which the entry names by its Bank Account and its Import Identifier.
    /** One Operator Decision entry with [details] for each of [legs]; a Transfer Leg whose Bank Account is not named is dropped and counted by the journal. */
    suspend fun recordForEachLeg(journal: DecisionJournal, details: String, legs: List<StoredBankTransaction>, bankAccountNames: Map<Long, String>) {
        legs.forEach { leg ->
            journal.record(JournalEventKind.OperatorDecision, details, leg.importIdentifier, bankAccountNames[leg.bankAccountId].orEmpty())
        }
    }

    // UC-005 BR-011: Every Transfer Decision that Slussen drops is recorded with its reason, once for each Transfer Leg of the Transfer.
    /** The Decision Journal entries of the Transfer Decisions a Comparison dropped (UC-005 BR-015). */
    suspend fun recordDropped(
        journal: DecisionJournal,
        dropped: List<DroppedTransferDecision>,
        bankAccountNames: Map<Long, String>,
        budgetAccountNames: Map<Long, String>,
    ) {
        dropped.filter { it.transfer.decision != TransferDecision.Proposed }.forEach { drop ->
            val details = "Slussen dropped the Transfer Decision ${drop.transfer.decision.text} because ${drop.reason.text}; " +
                "the Transfer was: ${describe(drop.transfer, drop.legs.size, budgetAccountNames)}; its Bank Transactions are detected again"
            recordForEachLeg(journal, details, drop.legs, bankAccountNames)
        }
    }
}
