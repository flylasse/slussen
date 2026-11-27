package slussen.app.ui.mappings

import slussen.core.connection.AddedBankAccount
import slussen.core.connection.OtherSideShown
import slussen.core.mapping.IdentifyOutcome
import slussen.core.mapping.MappingChangeOutcome
import slussen.core.mapping.MoveOutcome
import slussen.core.mapping.OfferedToIdentify
import slussen.core.port.OtherSideKind
import slussen.core.port.SentThrough

/**
 * The texts of the changes of Manage Account Mappings (UC-009; `docs/mockups/account-mappings.html`), in Slussen's
 * glossary terms: each Bank Account's line, its sheet, what a move, a removal or a new identification states before
 * anything is stored, and every answer of the core at the control that asked.
 */
object AccountMappingsTexts {
    /** NFR-023: the answer when the stored data could not be read or written; nothing was stored. */
    const val NOT_STORED = "Not stored: Slussen could not use the data stored on this device · try again"

    /** UC-009 BR-015: what follows a change of A4, A5, A8, A11, A13 and A14. */
    const val STATUSES = "the Transaction Statuses are given again"

    /** NFR-023: the sheet of the Bank Account or an editor could not be read from stored data. */
    const val NOT_READ = "Not shown: Slussen could not read the data stored on this device; nothing was changed · try again"

    // UC-005 BR-014: A Funded By or Transfer Partner that no longer applies is kept and shown as no longer applying.
    /** Step 1: the Funded By of a credit card or the Transfer Partner of another Bank Account, and whether it no longer applies. */
    fun otherSide(shown: OtherSideShown?): String? = shown?.let {
        when {
            it.budgetAccountName == null -> "${it.kind.text}: not set"
            it.noLongerApplies -> "${it.kind.text}: ${it.budgetAccountName} · no longer applies"
            else -> "${it.kind.text}: ${it.budgetAccountName}"
        }
    }

    /** Step 1: whether transactions were sent through its Account Mapping, and whether it is identified. */
    fun state(account: AddedBankAccount): String = listOf(
        if (account.sentThrough) "Sent through" else "Nothing sent yet",
        when {
            account.waitsForConfirmation -> "Waiting for your confirmation"
            account.identified -> "identified"
            else -> "Not identified"
        },
    ).joinToString(" · ")

    /** The detail of the Funded By or Transfer Partner action in the sheet of the Bank Account. */
    fun otherSideDetail(shown: OtherSideShown?): String {
        val name = shown?.budgetAccountName ?: return "Not set"
        return if (shown.noLongerApplies) "$name · no longer applies" else name
    }

    private fun count(n: Int): String = if (n == 1) "1 transaction was" else "$n transactions were"

    private fun stays(n: Int): String = if (n == 1) "It stays there; Slussen won't send it again." else "They stay there; Slussen won't send them again."

    private fun moveThem(n: Int, budgetAppName: String): String = if (n == 1) "Move or delete it in $budgetAppName." else "Move or delete them in $budgetAppName."

    private fun more(n: Int): String = if (n == 1) "1 more was" else "$n more were"

    // UC-009 BR-003: Before a move or a removal after a written send, Slussen states how many were sent through it, that they stay, and the rejected ones apart.
    /** A8 step 3 (`#move-confirm`): what a move states before it is stored. */
    fun moveStatement(through: SentThrough, from: String, to: String, budgetAppName: String): List<String> = listOfNotNull(
        "${count(through.sent)} sent to $from in $budgetAppName. ${stays(through.sent)} ${moveThem(through.sent, budgetAppName)}".takeIf { through.sent > 0 },
        (
            "${more(through.rejected)} rejected by $budgetAppName and ${if (through.rejected == 1) "is" else "are"} not in $from. " +
                if (through.rejected == 1) "It goes to $to at its next Upload." else "They go to $to at their next Upload."
            ).takeIf { through.rejected > 0 },
    )

    /** A11 step 1 (`#remove`): what a removal states before it is stored. */
    fun removeStatement(through: SentThrough, name: String, from: String, budgetAppName: String): List<String> = listOfNotNull(
        "${count(through.sent)} sent to $from in $budgetAppName. ${stays(through.sent)} ${moveThem(through.sent, budgetAppName)}".takeIf { through.sent > 0 },
        ("${more(through.rejected)} rejected by $budgetAppName and ${if (through.rejected == 1) "is" else "are"} not in $from. " +
            "${if (through.rejected == 1) "It waits" else "They wait"} until $name is mapped again.").takeIf { through.rejected > 0 },
        "$name is kept, not mapped. Map it again with Move.",
    )

    // UC-009 BR-007: Before a new identification after a written send, Slussen states how many were sent, that they stay, and that the rejected ones are sent again.
    /** A7 step 1 (`#identify-after-sends`): what a new identification states before it is stored; the sends are counted to any Budget Account. */
    fun identifyStatement(through: SentThrough, name: String, budgetAppName: String): List<String> = listOfNotNull(
        "${if (through.sent == 1) "1 transaction was" else "${through.sent} transactions were"} sent from $name to $budgetAppName. ${stays(through.sent)}".takeIf { through.sent > 0 },
        ("${more(through.rejected)} rejected by $budgetAppName. ${if (through.rejected == 1) "It is" else "They are"} sent again at the next Upload.").takeIf { through.rejected > 0 },
    )

    /** BR-015: what follows a stored change, given now or when Slussen next starts or compares. */
    fun given(statusesGiven: Boolean, what: String = "the Transaction Statuses are given again"): String =
        if (statusesGiven) " · $what" else " · $what when Slussen next starts or compares; no Upload Plan is prepared before"

    /** The answer of a change of one Bank Account (A4, A5, A9, A12, A13); [stored] is what was stored, said with its name. */
    fun changeAnswer(outcome: MappingChangeOutcome, name: String, stored: String, follows: String?, kind: OtherSideKind? = null): String = when (outcome) {
        is MappingChangeOutcome.Stored -> "$name: $stored · stored" + (follows?.let { given(outcome.statusesGiven, it) } ?: "")
        MappingChangeOutcome.Unchanged -> "$name: unchanged; nothing was stored"
        MappingChangeOutcome.NotOffered -> "Not stored: that Budget Account cannot be the ${kind?.text ?: "Funded By or Transfer Partner"} of $name now; choose one of those shown"
        MappingChangeOutcome.NameNotValid -> "Not stored: a name is 1 to 100 characters"
        MappingChangeOutcome.NotFound -> "Not stored: this Bank Account is no longer stored on this device"
        MappingChangeOutcome.NotStored -> NOT_STORED
        is MappingChangeOutcome.Unavailable -> slussen.app.ui.Texts.refusal(outcome.reason)
    }

    /** The answer of a move, a new Account Mapping or a removal (A8, A11), but for the statement to confirm. */
    fun moveAnswer(outcome: MoveOutcome, name: String, stored: String, newBudgetAccount: String?): String = when (outcome) {
        is MoveOutcome.Stored -> "$name: $stored · stored" + given(outcome.statusesGiven)
        is MoveOutcome.ToConfirm -> ""
        MoveOutcome.Unchanged -> "$name is mapped to ${newBudgetAccount ?: "that Budget Account"} already; nothing was stored"
        MoveOutcome.NotOffered -> "Not stored: ${newBudgetAccount ?: "that Budget Account"} cannot receive $name now; choose one of those shown"
        MoveOutcome.NotMapped -> "Not stored: $name has no Account Mapping to remove"
        MoveOutcome.NotFound -> "Not stored: this Bank Account is no longer stored on this device"
        MoveOutcome.NotStored -> NOT_STORED
        is MoveOutcome.Unavailable -> slussen.app.ui.Texts.refusal(outcome.reason)
    }

    /** The answer of a confirmation, a new identification or a rejection (steps 4-7, A1-A3, A6, A7), but for the statement to confirm. */
    fun identifyAnswer(outcome: IdentifyOutcome, name: String): String = when (outcome) {
        IdentifyOutcome.Identified -> "$name identified · used from the next Fetch"
        IdentifyOutcome.Unchanged -> "$name is identified with that Bank Account already; nothing was stored"
        is IdentifyOutcome.ToConfirm -> ""
        // A2 of UC-009: refused, with the Account Mapping that already uses it.
        is IdentifyOutcome.AlreadyUsed -> "Not stored: already used by ${outcome.bankAccountName}; choose another"
        IdentifyOutcome.NotOffered -> "Not stored: that Offered Bank Account cannot identify $name now; choose one of those shown"
        IdentifyOutcome.NotIdentifiable -> "$name gets its transactions from Transaction Files; no Data Provider identifies it"
        // A3 of UC-009: no identification; its transactions stay out of use with the Blocked Reason unrecognised Bank Account.
        IdentifyOutcome.Rejected -> "$name: proposal rejected · not used until identified (Blocked Reason: unrecognised Bank Account)"
        IdentifyOutcome.NotFound -> "Not stored: this Bank Account is no longer stored on this device"
        IdentifyOutcome.NotStored -> NOT_STORED
        is IdentifyOutcome.Unavailable -> slussen.app.ui.Texts.refusal(outcome.reason)
    }

    /** One Offered Bank Account to identify with: its name and Bank, kind, currency and last four digits (step 3). */
    fun offeredTitle(offered: OfferedToIdentify): String = listOfNotNull(offered.name ?: offered.kind, offered.bankName).joinToString(" · ")

    fun offeredDetail(offered: OfferedToIdentify): String =
        listOfNotNull(offered.kind, offered.currency?.code, offered.lastFourDigits?.let { "•••• $it" }).joinToString(" · ")
}
