package slussen.core.mapping

import slussen.core.operation.OperationRefusal
import slussen.core.port.SentThrough
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode

/**
 * UC-009 step 3, A6 step 2: one Offered Bank Account of the Bank Account's Source, as the Source last delivered it, with
 * its Bank, name, kind, currency and the last four digits of its number. [current] says that it is the one the Bank
 * Account is identified with now.
 */
data class OfferedToIdentify(
    val offeredBankAccountId: Long,
    val bankName: String?,
    val name: String?,
    val kind: String?,
    val currency: CurrencyCode?,
    val lastFourDigits: String?,
    val current: Boolean,
)

/**
 * UC-009 steps 2-3, A6, A7, A10: what the Operator confirms or identifies a Bank Account from, read from the stored
 * Offered Bank Accounts without contacting the Source (BR-009): the [proposed] match, when there is one, and the
 * [others] of the Source that are not Left-Out Bank Accounts (BR-004). [sentThrough] is what was sent through its Account
 * Mapping, which A7 states before a new identification is stored (BR-007).
 */
data class IdentificationSheet(
    val bankAccountId: Long,
    val name: String,
    val sourceName: String,
    val proposed: OfferedToIdentify?,
    val others: List<OfferedToIdentify>,
    val sentThrough: SentThrough,
) {
    /**
     * A10: no Offered Bank Account of the Source is stored, for example after a reinstall: the Bank Account can be
     * identified after the next Fetch of its Data Provider or the next grant of its Bank Consent, and its current
     * identification stays in use.
     */
    val noneStored: Boolean get() = proposed == null && others.isEmpty()
}

/** The outcome of confirming or identifying a Bank Account (UC-009 steps 4-7, A1-A3, A6, A7). */
sealed interface IdentifyOutcome {
    /** Steps 6-7: the identification is recorded, so that its transactions are used from the next Fetch on, and journaled. */
    data object Identified : IdentifyOutcome

    /** The Bank Account is identified with this Offered Bank Account already: nothing was stored or recorded. */
    data object Unchanged : IdentifyOutcome

    /**
     * A7, BR-007: something sent through its Account Mapping was written; nothing is stored until the Operator confirms
     * [sentThrough], these numbers, and asks again with them.
     */
    data class ToConfirm(val sentThrough: SentThrough) : IdentifyOutcome

    /** A2: another Account Mapping already uses the Offered Bank Account, that of [bankAccountName]; nothing was stored. */
    data class AlreadyUsed(val bankAccountName: String) : IdentifyOutcome

    /**
     * The Offered Bank Account is not one this Bank Account can be identified with: it is no longer stored, belongs to
     * another Source, or is a Left-Out Bank Account (BR-004); for a rejection, it is not the proposed match. Nothing was
     * stored.
     */
    data object NotOffered : IdentifyOutcome

    /** A Bank Account of a File Format: its transactions come from Transaction Files and no Data Provider identifies it. */
    data object NotIdentifiable : IdentifyOutcome

    /**
     * A3: the proposal is rejected: no identification is recorded, the Bank Account's transactions stay out of use with
     * the Blocked Reason "unrecognised Bank Account", and the rejection is journaled.
     */
    data object Rejected : IdentifyOutcome

    /** No Bank Account has this id. */
    data object NotFound : IdentifyOutcome

    /** The stored data could not be read or written: nothing was stored. */
    data object NotStored : IdentifyOutcome

    /** UC-006 BR-005: an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : IdentifyOutcome
}

/** A8 step 1: one Budget Account the Bank Account can be mapped to. */
data class MappingChoice(val budgetAccountId: Long, val budgetName: String, val budgetAccountName: String, val balance: Amount?)

/**
 * A8 steps 1 and 3, A11 step 1: the Budget Accounts the Bank Account [name] can be mapped to ([choices]), the one it
 * is mapped to now ([currentBudgetAccountName], null without an Account Mapping), and what was sent through its Account
 * Mapping ([sentThrough]), which a move or a removal states and asks the Operator to confirm first when something of it
 * was written (BR-003).
 */
data class MoveSheet(
    val bankAccountId: Long,
    val name: String,
    val currentBudgetAccountName: String?,
    val choices: List<MappingChoice>,
    val sentThrough: SentThrough,
)

/** The outcome of moving, storing or removing an Account Mapping (UC-009 A8, A11). */
sealed interface MoveOutcome {
    /**
     * A8 step 5, A11 steps 3-4: stored and journaled. [statusesGiven] says that the Transaction Statuses were given again
     * and the Transfers detected again from stored data (BR-015); false means that they could not be now: the Comparison
     * marker stored with the change keeps every Upload Plan from being prepared until they are.
     */
    data class Stored(val statusesGiven: Boolean) : MoveOutcome

    /**
     * A8 step 3, A11 step 1, BR-003: something sent through the Account Mapping was written; nothing is stored until the
     * Operator confirms [sentThrough], these numbers, and asks again with them.
     */
    data class ToConfirm(val sentThrough: SentThrough) : MoveOutcome

    /** The Bank Account is mapped to this Budget Account already: nothing was stored or recorded. */
    data object Unchanged : MoveOutcome

    /** A8 step 1: the Budget Account is not one this Bank Account can be mapped to; nothing was stored. */
    data object NotOffered : MoveOutcome

    /** A11: the Bank Account has no Account Mapping to remove; nothing was stored. */
    data object NotMapped : MoveOutcome

    /** No Bank Account has this id. */
    data object NotFound : MoveOutcome

    /** The stored data could not be read or written: nothing was stored. */
    data object NotStored : MoveOutcome

    /** UC-006 BR-005: an Upload Plan awaits confirmation or an Upload is written. */
    data class Unavailable(val reason: OperationRefusal) : MoveOutcome
}
