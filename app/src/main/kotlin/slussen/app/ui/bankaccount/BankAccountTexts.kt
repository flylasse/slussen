package slussen.app.ui.bankaccount

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferType
import slussen.core.status.BankAccountPlacement
import slussen.core.status.BankAccountTransactionsView
import slussen.core.status.BudgetAppEntry
import slussen.core.status.NothingStored
import slussen.core.status.OtherSideKind
import slussen.core.status.OtherSideSetting
import slussen.core.status.StatusBoundaries
import slussen.core.status.StatusReason
import slussen.core.status.TransactionLine
import slussen.core.status.TransactionWeek
import slussen.core.status.TransferShown
import slussen.core.status.WeekKind
import slussen.pluginapi.value.Amount
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The texts of one Bank Account's screen (UC-008 step 5, `docs/mockups/bank-account.html`), in Slussen's glossary terms:
 * each Transaction Status with the reason the core derived for it, and the read-only details of a line.
 */
object BankAccountTexts {
    /** NFR-023: the stored transactions could not be read; said on the screen, never a crash. */
    const val NOT_READ =
        "The Bank Transactions stored on this device could not be read; nothing was changed. Open the Bank Account again to see them."

    /** The closing line of every sheet while Review Decisions, Transfer Decisions and Uploads are not built. */
    const val NO_ACTION_NOW = "No action is needed now."

    /** `bank-account.html#statuses-this-build`: what the list reaches from every Bank Account. */
    const val WHAT_THE_STATUSES_MEAN = "What the statuses mean"

    // ---- The header (steps 5-8) ---------------------------------------------------------------------------------------

    /** Where the Bank Account stands: its Budget and Budget Account, its Bank and its Source; or not mapped, or retired. */
    fun placement(view: BankAccountTransactionsView): String = when (val placement = view.placement) {
        is BankAccountPlacement.Mapped ->
            listOfNotNull("${placement.budgetName} → Budget Account ${placement.budgetAccountName}", view.bankName, view.sourceName)
                .filter { it.isNotEmpty() }.joinToString(" · ")
        BankAccountPlacement.NotMapped -> "Not mapped"
        is BankAccountPlacement.Retired -> listOfNotNull(placement.budgetName, "Retired Account").joinToString(" · ")
    }

    /** UC-009 A4, A11 (`bank-account.html#unmapped`, `#retired`): what a Bank Account outside the Comparison keeps. */
    fun placementNote(placement: BankAccountPlacement): String? = when (placement) {
        is BankAccountPlacement.Mapped -> null
        BankAccountPlacement.NotMapped ->
            "Not mapped · history kept · not compared or offered for Upload. Map it to a Budget Account in Account Mappings in Settings."
        is BankAccountPlacement.Retired -> "Retired Account · history kept · not compared or offered for Upload."
    }

    /** UC-005 BR-006, BR-014: the Funded By or the Transfer Partner, read-only, as not set, set, or no longer applying. */
    fun otherSide(setting: OtherSideSetting): String {
        val name = if (setting.kind == OtherSideKind.FundedBy) "Funded By" else "Transfer Partner"
        return when {
            setting.budgetAccountName == null -> "$name: not set · set in a later version"
            setting.applies -> "$name: ${setting.budgetAccountName}"
            else -> "$name: ${setting.budgetAccountName} · no longer applies, since no Bank Account that Slussen fetches in this Budget's currency is mapped to it"
        }
    }

    fun startDate(date: LocalDate?, formats: Formats): String? = date?.let { "Start Date ${formats.date(it)}" }

    /** The header of the list: the dates it actually covers, oldest to newest (survey §5 guess 6). */
    fun covers(range: ClosedRange<LocalDate>?, formats: Formats): String? =
        range?.let { "Newest first · ${formats.date(it.start)} to ${formats.date(it.endInclusive)}" }

    /** UX-17, UX-D2: the heading of one week, never a filter. */
    fun heading(week: TransactionWeek, formats: Formats, today: LocalDate): String = when (week.kind) {
        WeekKind.ThisWeek -> "This week · from ${week.from!!.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${formats.day(week.from!!, today)}"
        WeekKind.LastWeek -> "Last week · ${formats.day(week.from!!, today)} to ${formats.day(week.to!!, today)}"
        WeekKind.Earlier -> "Earlier · ${formats.day(week.from!!, today)} to ${formats.day(week.to!!, today)}"
        WeekKind.WithoutDate -> "Without a readable date"
    }

    /** `bank-account.html#fetched-empty`: no transaction at all, said so that it is not mistaken for a failure. */
    fun nothingStored(nothing: NothingStored, formats: Formats): String = when (nothing) {
        is NothingStored.FetchedNone ->
            "No Bank Transactions: the Fetch of ${formats.dateTime(nothing.fetchedAt)} by ${nothing.dataProviderName ?: "the Data Provider"} " +
                "succeeded and delivered no booked Bank Transaction with an amount other than zero for this Bank Account. A pending " +
                "purchase appears once it is booked and a later Fetch brings it in. Nothing is wrong and no action is needed; fetch " +
                "again for fresher data."
        NothingStored.NoneKept -> "No Bank Transactions are kept for this Bank Account, and none is fetched for it."
        NothingStored.NotFetchedYet ->
            "No Bank Transactions yet: no Fetch of this Bank Account has succeeded. A successful Fetch brings them in."
        is NothingStored.NoneImported -> nothing.importedAt?.let {
            "No Bank Transactions: the Transaction File imported ${formats.dateTime(it)} held none for this Bank Account. Nothing is wrong."
        } ?: "No Bank Transactions yet: no Transaction File of this Bank Account has been imported."
    }

    // ---- One line of the list -----------------------------------------------------------------------------------------

    /** The status a line shows: the Transaction Status under its glossary name, or not compared yet (S-T-08). */
    fun status(line: TransactionLine): String = line.status?.text ?: "Not compared yet"

    fun payee(line: TransactionLine): String =
        if (line.key.unreadable) "Unreadable Transaction · ${line.payee ?: line.memo ?: "no description"}" else line.payee ?: line.memo ?: "No description"

    fun amount(amount: Amount?, formats: Formats): String = amount?.let { formats.difference(it) } ?: "—"

    fun date(line: TransactionLine, formats: Formats, today: LocalDate): String = line.bookedDate?.let { formats.day(it, today) } ?: "—"

    /** The reason of one line, short, as the list shows it (`bank-account.html#checking-this-build`). */
    fun reason(line: TransactionLine, view: BankAccountTransactionsView, formats: Formats, today: LocalDate): String {
        val budgetApp = view.budgetAppName
        fun day(date: LocalDate) = formats.day(date, today)
        return when (val reason = line.reason) {
            StatusReason.NotComparedYet -> "Stored since the last successful read of $budgetApp; compared at the next read"
            StatusReason.NotComparedAnyMore -> "Kept as it was; this Bank Account is not compared or offered for Upload"
            StatusReason.NoMatch -> "No match in $budgetApp at the latest Comparison"
            is StatusReason.CurrencyMismatch ->
                "Shown, but not eligible while the Bank Account is blocked: ${reason.bankAccountCurrency.code} Bank Account, " +
                    "${reason.budgetCurrency.code} Budget"
            is StatusReason.OtherSideDidNotArriveInTime ->
                (if (reason.proposed) "No other side arrived from ${reason.otherBudgetAccountName}, which Slussen proposed," else "Its other side, from ${reason.otherBudgetAccountName}, did not arrive") +
                    " within the Lonely Transfer grace period (${reason.graceDays} days, an Operator Setting, until ${day(reason.lastDay)}): " +
                    "now an ordinary Bank Transaction"
            is StatusReason.TypedEntries -> "A possible pairing with your Typed Entry; nothing paired or written yet"
            is StatusReason.HeldInBudgetApp -> "$budgetApp already holds it under Slussen's own Import Identifier"
            is StatusReason.DeletedInBudgetApp -> "Deleted in $budgetApp under its Import Identifier; not sent again"
            is StatusReason.MergedInBudgetApp ->
                "Merged in $budgetApp" + (reason.into?.let { " into your Typed Entry ${entry(it, formats, today)}" } ?: "") + "; not sent again"
            is StatusReason.ResemblesOtherImport ->
                "Resembles an Import by another importer" + (reason.imports.firstOrNull()?.let { " in ${it.budgetAccountName}" } ?: "") +
                    "; not proof of a duplicate"
            is StatusReason.ResemblesUnsupportedSlussenImport -> "Resembles a Slussen Import in a form this version does not support"
            is StatusReason.NoStableReference -> if (reason.identical > 0) {
                "No Stable Reference: same date, amount and text as ${Texts.count(reason.identical, "other", "others")}, so Slussen cannot tell them apart"
            } else {
                "No Stable Reference, so Slussen cannot recognise it again for sure"
            }
            is StatusReason.Unreadable -> "Slussen could not read it (${reason.reason}); never uploaded"
            is StatusReason.BeforeStartDate -> "Before the Start Date ${formats.date(reason.startDate)} of this Bank Account"
            is StatusReason.TooOld ->
                "Before the ${if (reason.fileUploadWindow) "file upload window" else "Upload Window"}, which starts ${day(reason.windowStartsOn)}"
            StatusReason.InTheFuture -> "Dated after today; the Comparison looks at it again once its date comes"
            is StatusReason.TransferLeg -> transfer(reason.transfer, line, formats, today)
            is StatusReason.GuessedTransfer -> guessed(reason, line, formats, today)
            is StatusReason.AsStored -> "As the latest Comparison left it; the next Comparison looks at it again"
        }
    }

    private fun typeOf(type: TransferType): String = type.text

    /** UC-005 BR-010, A4: one Transfer with its other side, and which side carries it in an Upload Plan (UC-006 BR-009). */
    private fun transfer(transfer: TransferShown, line: TransactionLine, formats: Formats, today: LocalDate): String {
        val outflow = (line.amount?.thousandths ?: 0) < 0
        val other = transfer.otherLeg
        // UC-005 BR-010: while a Transfer Leg keeps its status from an earlier rule, or is doubtful (BR-013), the whole Transfer is in no Upload Plan.
        val keptOut = transfer.keptOutBy?.takeUnless { it.status == TransactionStatus.GuessedTransfer }?.let { by ->
            val side = if (by.byThisSide) "this side" else "the other side"
            when (by.status) {
                TransactionStatus.NeedsReview, TransactionStatus.PossibleImportByOtherPath ->
                    "$side is ${by.status.text}, so the whole Transfer waits for its Review Decision"
                else -> "$side is ${by.status.text}, so the whole Transfer is in no Upload Plan"
            }
        }
        return if (other != null) {
            "One ${typeOf(transfer.type)} with ${other.bankAccountName} ${formats.day(other.bookedDate, today)} ${formats.difference(other.amount)}; " +
                (keptOut ?: if (transfer.writesIt) "this side would carry it" else "the other side would carry it, so this one is not counted as Eligible")
        } else {
            val expectedBy = transfer.expectedBy!!
            "Lonely Transfer ${if (outflow) "to" else "from"} ${transfer.otherBudgetAccountName}; " + when {
                keptOut != null -> keptOut
                // UC-005 BR-005: once the grace period has ended, the next Comparison makes it an ordinary Bank Transaction.
                expectedBy.isBefore(today) -> "its grace period ended ${formats.day(expectedBy, today)}; the next Comparison looks at it again"
                else -> "its other side is expected by ${formats.day(expectedBy, today)}"
            }
        }
    }

    /** UC-005 BR-003, A8: why a Transfer is guessed, and what it proposes. */
    private fun guessed(reason: StatusReason.GuessedTransfer, line: TransactionLine, formats: Formats, today: LocalDate): String {
        val transfer = reason.transfer
            ?: return "Card Payment; its paying side is not known, since ${reason.cardName ?: "the credit card"} has no Funded By"
        val other = transfer.otherLeg
        val side = if ((line.amount?.thousandths ?: 0) < 0) "paying side" else "other side"
        return if (other != null) {
            "${typeOf(transfer.type)} Slussen cannot pair for certain; proposed with ${other.bankAccountName} " +
                "${formats.day(other.bookedDate, today)} ${formats.difference(other.amount)}"
        } else {
            "${typeOf(transfer.type)}; proposed $side: ${transfer.otherBudgetAccountName}"
        }
    }

    private fun entry(entry: BudgetAppEntry, formats: Formats, today: LocalDate): String =
        listOfNotNull(entry.payee, formats.day(entry.date, today), formats.difference(entry.amount)).joinToString(" ")

    private fun entryIn(entry: BudgetAppEntry, formats: Formats): String =
        listOfNotNull(entry.payee, formats.date(entry.date), formats.difference(entry.amount)).joinToString(" ") +
            " in Budget Account ${entry.budgetAccountName}"

    // ---- The sheet of one line (`#tx-match-this-build`, `#tx-possible-import-this-build`, `#tx-unreadable-this-build`) --

    /** The read-only details of one line: what it was compared with, and that no action is needed now. */
    fun sheet(line: TransactionLine, view: BankAccountTransactionsView, formats: Formats, today: LocalDate): TransactionSheetUi {
        val budgetApp = view.budgetAppName
        val currency = line.currency?.code?.let { " $it" }.orEmpty()
        val title = "${payee(line)} · ${amount(line.amount, formats)}$currency"
        val subtitle = listOfNotNull(line.bookedDate?.let { formats.date(it) }, view.name, status(line)).joinToString(" · ")
        val details = when (val reason = line.reason) {
            is StatusReason.TypedEntries -> listOf(
                DetailLine("What it means", "$budgetApp holds a Typed Entry of the same amount within the Matching Date Window (${reason.matchingDateWindowDays} days, a rule of $budgetApp)"),
                DetailLine(
                    "Typed Entries with the same amount",
                    "${reason.entries.size}" + reason.entries.joinToString("") { " · ${entryIn(it, formats)}" },
                ),
                DetailLine("Paired?", "Not yet. $budgetApp pairs them when this Bank Transaction is uploaded, which comes in a later version."),
                DetailLine("Written to $budgetApp", "Nothing: this version writes nothing"),
            )
            is StatusReason.ResemblesOtherImport -> listOf(
                DetailLine(
                    "Resembles",
                    reason.imports.joinToString(" · ") { "${entryIn(it, formats)}, imported by another importer" }
                        .ifEmpty { "An Import by another importer at the latest Comparison" },
                ),
                DetailLine("Is it the same?", "Maybe. It may be this transaction already in $budgetApp, or a second one of the same amount."),
                DetailLine("Later", "Review Decisions come in a later version: Already Present, Import Anyway or Defer"),
            )
            is StatusReason.ResemblesUnsupportedSlussenImport -> listOf(
                DetailLine("Resembles", reason.imports.joinToString(" · ") { "${entryIn(it, formats)}, with Slussen's marker in a form this version does not support" }),
                DetailLine("Why it waits", "Slussen cannot tell whether that Import is this Bank Transaction, so it never uploads it without your decision."),
                DetailLine("Later", "Review Decisions come in a later version: Import Anyway or Defer"),
            )
            is StatusReason.Unreadable -> listOfNotNull(
                DetailLine("Read", listOfNotNull(line.amount?.let { "Amount ${formats.amount(it)}" }, line.payee?.let { "description $it" }).joinToString(" · ").ifEmpty { "Nothing" }),
                DetailLine("Not read", reason.reason),
                DetailLine("Fetched", formats.dateTime(line.firstSeenAt)),
                DetailLine("Uploaded", "Never"),
                DetailLine("Later", "Review Decisions come in a later version; an Unreadable Transaction can then only be deferred"),
            )
            else -> listOfNotNull(
                DetailLine("Why", reason(line, view, formats, today)),
                DetailLine("What it means", line.status?.let { meaning(it, view.boundaries, budgetApp, formats, today) } ?: notComparedYetMeaning(budgetApp)),
                reasonEntry(reason, formats)?.let { DetailLine("In $budgetApp", it) },
                // UC-005 BR-010: a Transfer Leg that an earlier rule gave its status stays a Transfer Leg of its Transfer.
                line.transfer?.takeUnless { reason is StatusReason.TransferLeg || reason is StatusReason.GuessedTransfer }
                    ?.let { DetailLine("Transfer", transfer(it, line, formats, today)) },
                DetailLine("Eligible", if (line.eligible) "Yes, for a later Upload Plan; nothing is uploaded in this version" else "No"),
            )
        }
        return TransactionSheetUi(title, subtitle, details, "It stays visible with its reason. Nothing is uploaded, and no decision is needed now.")
    }

    private fun reasonEntry(reason: StatusReason, formats: Formats): String? = when (reason) {
        is StatusReason.HeldInBudgetApp -> reason.entry?.let { entryIn(it, formats) }
        is StatusReason.DeletedInBudgetApp -> reason.entry?.let { "${entryIn(it, formats)}, deleted" }
        is StatusReason.MergedInBudgetApp -> reason.into?.let { "Merged into ${entryIn(it, formats)}" }
        else -> null
    }

    private fun notComparedYetMeaning(budgetApp: String) =
        "It was stored after the last successful read of $budgetApp, so it has no Transaction Status yet and is counted neither as " +
            "Eligible nor as waiting for a decision. Read $budgetApp again to compare it."

    // ---- What the statuses mean (`#statuses-this-build`) -------------------------------------------------------------

    /**
     * What [status] means for this Bank Account, with the boundaries the rules use (UC-003 BR-003, BR-006, BR-011, BR-018,
     * UC-005 BR-010), and that no action is needed now.
     */
    fun meaning(status: TransactionStatus, boundaries: StatusBoundaries, budgetApp: String, formats: Formats, today: LocalDate): String = when (status) {
        TransactionStatus.New -> "The latest Comparison found nothing in $budgetApp that matches it. A later Upload Plan would hold it."
        TransactionStatus.MatchForTypedEntry ->
            "$budgetApp holds a Typed Entry of the same amount within the Matching Date Window (${boundaries.matchingDateWindowDays} days, " +
                "a rule of $budgetApp). A possible pairing, not a done one: $budgetApp pairs them when it is uploaded."
        TransactionStatus.AlreadyInBudgetApp -> "$budgetApp already holds it under Slussen's own Import Identifier; it is never sent again."
        TransactionStatus.PossibleImportByOtherPath ->
            "It resembles an Import by another importer, such as $budgetApp's own bank import. A resemblance, not proof; it is " +
                "never uploaded without your Review Decision, which comes in a later version."
        TransactionStatus.NeedsReview ->
            "It has no Stable Reference, it resembles a Slussen Import in a form this version does not support, or it is an " +
                "Unreadable Transaction. It stays visible with its reason; Review Decisions come in a later version."
        TransactionStatus.TransferLeg -> "One side of a Transfer between two of your Bank Accounts. Only one side would carry it in an Upload Plan."
        TransactionStatus.GuessedTransfer -> "A Transfer Slussen proposes but cannot pair for certain. Confirming or correcting it comes in a later version."
        TransactionStatus.DeletedInBudgetApp -> "$budgetApp holds its Import Identifier only as deleted; it is not sent again."
        TransactionStatus.MergedInBudgetApp -> "$budgetApp merged it into a Typed Entry; it is not sent again."
        TransactionStatus.TooOld ->
            "Dated before the ${if (boundaries.fileUploadWindow) "file upload window" else "Upload Window"}, which starts " +
                "${formats.day(boundaries.uploadWindowStartsOn, today)} (the last ${boundaries.uploadWindowDays} days, an Operator Setting). " +
                "Fetched because the fetch period (${boundaries.fetchPeriodDays} days) reaches further back; not offered for Upload."
        TransactionStatus.BeforeStartDate ->
            "Dated before the Start Date of its Bank Account" + (boundaries.startDate?.let { ", ${formats.date(it)}" } ?: "") + ". Never offered for Upload."
        TransactionStatus.InTheFuture -> "Dated after today. A later Comparison looks at it again once its date comes."
        else -> "As the latest Comparison left it."
    }

    /** What to do now about [status] while Review Decisions, Transfer Decisions and Uploads are not built. */
    private fun now(status: TransactionStatus): String = when (status) {
        TransactionStatus.PossibleImportByOtherPath, TransactionStatus.NeedsReview ->
            "no decision is needed; Review Decisions come in a later version."
        TransactionStatus.GuessedTransfer -> "no decision is needed; confirming or correcting it comes in a later version."
        else -> "no action is needed."
    }

    /** `#statuses-this-build`: every Transaction Status this build can give, with what it means for this Bank Account. */
    fun statusesSheet(view: BankAccountTransactionsView, statusesAsOf: String?, formats: Formats, today: LocalDate): StatusesSheetUi =
        StatusesSheetUi(
            title = "Transaction Statuses",
            subtitle = listOfNotNull(view.name, statusesAsOf).joinToString(" · "),
            intro = "The fetch period decides which Bank Transactions are fetched; the Upload Window and the Start Date decide " +
                "which could be uploaded, so a longer fetch period brings in more Too Old ones, never more Eligible ones.",
            statuses = slussen.core.status.BankAccountTransactions.GIVEN_BY_THIS_BUILD.map { status ->
                StatusMeaningUi(status.text, meaning(status, view.boundaries, view.budgetAppName, formats, today), now(status))
            },
            closing = "Nothing is written to ${view.budgetAppName} in this version.",
        )
}
