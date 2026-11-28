package slussen.core.transfer

import slussen.core.comparison.Comparison
import slussen.core.comparison.ComparisonWindows
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationResult
import slussen.core.operation.unlessStorageFails
import slussen.core.port.ComparisonStore
import slussen.core.port.ConnectionStore
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredBudget
import slussen.core.port.TransferDecided
import slussen.core.port.TransferDecision
import slussen.core.port.TransferRuling
import slussen.core.port.TransferStore
import java.time.Clock
import java.time.LocalDate

/**
 * Review Transfers (UC-005), the Transfer Decisions, `:core` `transfer` (ADR-001 §7): the sheet of one selected Transfer,
 * built from stored data only (steps 2-3, A4, A7-A9), and Confirm, Split, Hold and choosing another Budget Account, each
 * one storage operation with its effect (O-13, ADR-002 §3), admitted through the operation coordinator also while an
 * Upload Plan awaits confirmation (ADR-007 §2), followed within the same operation by the conclusions from stored data
 * (O-5, ADR-007 §3a; [Comparison.statusesAgainAfter]), and recorded in the Decision Journal (BR-011). The rules are [TransferDecisionRules], [TransferRules]
 * and the Comparison's own. A Transfer is selected through one of its Transfer Legs, so that a Guessed Transfer for which
 * Slussen proposes no paying Budget Account, which has no stored Transfer, is selected like any other (A8). What waits
 * for the Uploads: the replacement of an open Upload Plan (UC-006 A3), the Sent Records the lock reads (BR-008 is built
 * and reads them; none is stored yet), A6 and BR-009.
 */
class TransferDecisions(
    private val connections: ConnectionStore,
    private val transfers: TransferStore,
    private val comparisons: ComparisonStore,
    private val comparison: Comparison,
    private val settings: OperatorSettingsStore,
    private val journal: DecisionJournal,
    private val clock: Clock,
    /** The Budget App's Matching Date Window, declared by its part (C-009). */
    private val matchingDateWindowDays: Int,
) {
    private suspend fun windows(): ComparisonWindows =
        ComparisonWindows.of(settings.current(), matchingDateWindowDays, LocalDate.ofInstant(clock.instant(), clock.zone))

    /** The served Budget the Bank Account of the Bank Transaction [storedBankTransaction] is mapped into, or null. */
    private suspend fun budgetOf(storedBankTransaction: StoredBankTransaction): StoredBudget? {
        val bankAccount = connections.bankAccounts().firstOrNull { it.id == storedBankTransaction.bankAccountId }
        return connections.budgets().firstOrNull { budget -> budget.served && budget.budgetAccounts.any { it.id == bankAccount?.mapping?.budgetAccountId } }
    }

    /** The stored Bank Transaction [storedBankTransaction] with the served Budget it is compared in, or null when there is none. */
    private suspend fun located(storedBankTransaction: Long): Pair<StoredBankTransaction, StoredBudget?>? {
        val transaction = transfers.bankTransaction(storedBankTransaction) ?: return null
        return transaction to budgetOf(transaction)
    }

    // UC-008 BR-001: The sheet is shown only from stored data; nothing contacts a Source, a Bank or the Budget App.
    /**
     * Steps 2-3, A4, A7-A9: the sheet of the Transfer the Bank Transaction [storedBankTransaction] is a Transfer Leg of,
     * or null when it is a Transfer Leg of none as the stored data stand. It stores nothing.
     */
    suspend fun sheet(storedBankTransaction: Long): TransferSheet? {
        val (_, budget) = located(storedBankTransaction) ?: return null
        if (budget == null) return null
        val data = comparisons.comparisonDataOf(setOf(budget.id))
        val selected = (TransferDecisionRules.selected(storedBankTransaction, data, windows()) as? TransferRuled.Stands)?.selected ?: return null
        val names = budget.budgetAccounts.associate { it.id to it.name }
        val bankAccounts = data.bankAccounts.associateBy { it.id }
        val open = !selected.locked && selected.waitsFor == null
        return TransferSheet(
            type = selected.transfer.type,
            amount = selected.transfer.amount,
            outflowBudgetAccountName = selected.transfer.outflowBudgetAccountId?.let { names[it].orEmpty() },
            inflowBudgetAccountName = names[selected.transfer.inflowBudgetAccountId].orEmpty(),
            legs = selected.legs.map { leg ->
                val bankAccount = bankAccounts[leg.bankAccountId]
                TransferLegShown(leg.id, bankAccount?.name.orEmpty(), names[bankAccount?.mapping?.budgetAccountId].orEmpty(), leg.bookedDate, leg.amount, leg.payee)
            },
            guessed = selected.transfer.guessed,
            decision = selected.transfer.decision,
            locked = selected.locked,
            waitsForReviewDecision = selected.waitsFor?.id.takeIf { !selected.locked },
            // A8 step 2: without a Budget Account on its paying side there is nothing to confirm.
            canConfirm = open && selected.transfer.outflowBudgetAccountId != null,
            // A9 step 2: while a Transfer Leg waits for a Review Decision the Operator can still split the Transfer.
            canSplit = !selected.locked,
            canHold = open,
            offered = if (open) {
                selected.offered.map { id ->
                    OfferedBudgetAccount(id, names[id].orEmpty(), proposed = selected.otherSide == id, proposesKeeping = TransferDecisionRules.proposesKeeping(selected, id))
                }
            } else {
                emptyList()
            },
            staysWith = selected.stays.id,
            keptAs = selected.keptAs,
        )
    }

    /**
     * Steps 4-6, A1-A4, A8-A10: the Operator decides [choice] for the Transfer the Bank Transaction
     * [storedBankTransaction] is a Transfer Leg of: one operation, admitted by the coordinator, ruled and stored by O-13
     * all or nothing with its effect, recorded in the Decision Journal, then concluded from stored data (O-5).
     */
    suspend fun decide(storedBankTransaction: Long, choice: TransferChoice): TransferOutcome {
        // UC-005 BR-016: Slussen detects Transfers again for a Transfer Leg the choice released, from stored data and before any Upload Plan is prepared, also when it stops in between.
        val result = comparison.statusesAgainAfter(OperationKind.TransferDecision) { store(storedBankTransaction, choice) }
        return when (result) {
            // ADR-007 §3a: when the conclusions could not be given now, the decision is stored and the Comparison marker keeps every Upload Plan from being prepared until they are, at the next start at the latest.
            is OperationResult.Ran -> when (val outcome = result.value.change) {
                is TransferOutcome.Stored -> outcome.copy(detectedAgain = result.value.statusesGiven)
                else -> outcome
            }
            // ADR-007 §2: admitted also while an Upload Plan awaits confirmation, refused while an Upload is written.
            is OperationResult.Refused -> TransferOutcome.Unavailable(result.reason)
        }
    }

    /** O-13 for one Transfer Decision within its operation, with its Decision Journal entries; nothing is stored when the rule refuses or the storage fails. */
    private suspend fun store(storedBankTransaction: Long, choice: TransferChoice): TransferOutcome {
        var bankAccountNames = emptyMap<Long, String>()
        var budgetAccountNames = emptyMap<Long, String>()
        // NFR-023: O-13 is the last step that can fail here, so "not stored" is only ever said of a decision that was not stored.
        val decided = unlessStorageFails<TransferDecided<RuledTransferOutcome>?>(null) {
            val (_, budget) = located(storedBankTransaction) ?: return TransferOutcome.NotFound
            if (budget == null) return TransferOutcome.NotDecidable(TransferUndecidable.BankAccountNotCompared)
            val windows = windows()
            bankAccountNames = connections.bankAccounts().associate { it.id to it.name }
            budgetAccountNames = budget.budgetAccounts.associate { it.id to it.name }
            val now = clock.instant()
            transfers.decide(setOf(budget.id)) { data ->
                // UC-003 BR-016: Until that read of the Budget no Transfer Decision is taken on its Transfers (UC-005 A11); a Transfer lies in one Budget, so one check covers both its Transfer Legs.
                if (budget.id in data.periodReadOwed) {
                    return@decide TransferRuling(null, RuledTransferOutcome(TransferOutcome.NotDecidable(TransferUndecidable.PeriodReadOwed)))
                }
                val (write, ruled) = TransferDecisionRules.decide(storedBankTransaction, choice, data, windows, now)
                TransferRuling(write, ruled)
            }
        } ?: return TransferOutcome.NotStored
        val stored = decided.answer.outcome as? TransferOutcome.Stored ?: return decided.answer.outcome
        decided.answer.facts?.let { facts -> journalDecision(facts, bankAccountNames, budgetAccountNames) }
        journal.recordStatusChanges(decided.changes, bankAccountNames)
        return stored
    }

    // UC-005 BR-011: Every Transfer Decision and every change to it is recorded in the Decision Journal together with the Transfer Slussen had proposed, once for each Transfer Leg of the Transfer.
    /** Step 6: the Decision Journal entries of one stored Transfer Decision; recording never delays or blocks the decision. */
    private suspend fun journalDecision(facts: TransferDecisionFacts, bankAccountNames: Map<Long, String>, budgetAccountNames: Map<Long, String>) {
        val had = TransferJournal.describe(facts.before, facts.legsBefore.size, budgetAccountNames)
        val what = if (facts.before.decision == TransferDecision.Proposed) {
            "Transfer Decision ${facts.after.decision.text}; the Transfer Slussen had proposed: $had"
        } else {
            "Transfer Decision changed from ${facts.before.decision.text} to ${facts.after.decision.text}; the Transfer as the Operator had decided it: $had"
        }
        val now = if (facts.after.decision == TransferDecision.BudgetAccountChosen) {
            "; the Transfer now: ${TransferJournal.describe(facts.after, facts.legsAfter.size, budgetAccountNames)}"
        } else {
            ""
        }
        // A Transfer Leg the choice released belonged to the Transfer the decision is about, so it is named too.
        TransferJournal.recordForEachLeg(journal, what + now, (facts.legsBefore + facts.legsAfter).distinctBy { it.id }.sortedBy { it.id }, bankAccountNames)
        // UC-005 BR-006: Every change of a Funded By or Transfer Partner is recorded in the Decision Journal.
        facts.kept?.let { kept ->
            val before = facts.keptBefore?.let { "it was Budget Account ${budgetAccountNames[it].orEmpty()}" } ?: "it had none"
            journal.record(
                JournalEventKind.AccountMappingChange,
                "Bank Account ${bankAccountNames[kept.bankAccountId].orEmpty()}: ${kept.kind.text} set to Budget Account " +
                    "${budgetAccountNames[kept.budgetAccountId].orEmpty()}, kept from a Transfer Decision; $before",
            )
        }
    }
}
