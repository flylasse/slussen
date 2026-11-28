package slussen.app.ui.bankaccount

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.components.BlockedReasonLine
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.ReviewChoice
import slussen.core.status.TransactionKey

/** What the Operator does in the Review Decision part of a line's sheet (UC-004). */
class ReviewActions(
    val onChoose: (ReviewChoice) -> Unit = {},
    val onChooseTransaction: (String) -> Unit = {},
    val onCancelChoosing: () -> Unit = {},
    val onReUpload: (Boolean) -> Unit = {},
    val onClear: () -> Unit = {},
)

/** The Review Decision actions of [viewModel], as the screen calls them. */
fun reviewActionsOf(viewModel: BankAccountViewModel) = ReviewActions(
    onChoose = viewModel::choose,
    onChooseTransaction = viewModel::chooseTransaction,
    onCancelChoosing = viewModel::cancelChoosing,
    onReUpload = viewModel::switchReUpload,
    onClear = viewModel::clearDecision,
)

/**
 * One Bank Account (UC-008 steps 5-8; `docs/mockups/bank-account.html`): Source Health, Freshness and the balances apart,
 * its Blocked Reasons with their remedies, when its statuses were given, the Funded By or Transfer Partner, and its Bank
 * Transactions newest first under week headings, each with its Transaction Status and the reason for it; a tap opens the
 * details of a line with its Review Decision (UC-004, `docs/mockups/review-decision.html`), and "What the statuses
 * mean" explains each status this build gives.
 */
@Composable
fun BankAccountScreen(
    state: BankAccountState,
    onBack: () -> Unit,
    onOpen: (TransactionKey) -> Unit,
    onStatuses: () -> Unit,
    onClose: () -> Unit,
    onRemedy: (RemedyAction) -> Unit = {},
    onTransfer: (Long) -> Unit = {},
    review: ReviewActions = ReviewActions(),
) {
    val ui = state.ui
    if (ui == null) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            ScreenTitle("Bank Account", onBack = onBack)
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
        }
        return
    }
    // System back closes an open sheet first, before it leaves the Bank Account.
    BackHandler(enabled = ui.sheet != null || ui.statusesSheet != null) { onClose() }
    ui.sheet?.let { sheet ->
        TransactionSheet(ui.title, sheet, onClose, onTransfer, review)
        return
    }
    ui.statusesSheet?.let { sheet ->
        StatusesSheet(sheet, onClose)
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.title, ui.placement, onBack = onBack)
            // NFR-023: a read that failed is said here; the screen keeps what it showed.
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            SlussenCard {
                Text(ui.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(ui.kind)
                ui.placementNote?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
                ui.health?.let { Muted(it) }
                ui.freshness?.let { Muted(it) }
                // A21 of UC-001 (`#balance-other-currency`): both currencies, nothing converted.
                ui.balanceNotice?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
                ui.balance?.let { Text(it, color = SlussenColors.Text, fontSize = 12.sp) }
                ui.balanceRemedy?.let { Muted(it) }
                ui.blocked.forEach { BlockedReasonLine(it.text, it.action, onRemedy, it.advice) }
                ui.statuses?.let { Muted(it) }
                ui.notComparedYet?.let { Muted(it) }
                ui.readAgainNote?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
                Muted(listOfNotNull(ui.startDate, ui.otherSide).joinToString(" · "))
            }
            Muted(ui.note)
            TextButton(onClick = onStatuses) { Text(BankAccountTexts.WHAT_THE_STATUSES_MEAN) }
            ui.nothingStored?.let { SlussenCard { Text(it, color = SlussenColors.Text2, fontSize = 12.sp) } }
            ui.covers?.let { Muted(it) }
        }
        ui.weeks.forEach { week ->
            item(key = "heading-${week.heading}") { SectionTitle(week.heading) }
            items(week.lines, key = { "${it.key.unreadable}-${it.key.id}" }) { line -> TransactionLineView(line, onOpen) }
        }
    }
}

/** One line of the list; a tap opens its read-only details. */
@Composable
private fun TransactionLineView(line: TransactionLineUi, onOpen: (TransactionKey) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(line.key) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(line.date, color = SlussenColors.Text3, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(line.payee, color = SlussenColors.Text, fontSize = 13.sp)
            Text(line.reason, color = SlussenColors.Text2, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(line.amount, color = SlussenColors.Text, fontSize = 13.sp)
            Text(line.status, color = if (line.eligible) SlussenColors.Teal else SlussenColors.Text2, fontSize = 11.sp)
        }
    }
}

/** The details of one line, with its Review Decision and Close. */
@Composable
private fun TransactionSheet(bankAccountName: String, sheet: TransactionSheetUi, onClose: () -> Unit, onTransfer: (Long) -> Unit, actions: ReviewActions) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(bankAccountName, onBack = onClose)
            SlussenCard {
                Text(sheet.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(sheet.subtitle)
                sheet.details.forEach { detail ->
                    Text(detail.name, color = SlussenColors.Text3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(detail.text, color = SlussenColors.Text, fontSize = 12.sp)
                }
                Muted(sheet.closing)
                // Step 1 of UC-005: from a Transfer Leg to its Transfer, with both sides.
                sheet.transferLeg?.let { leg -> TextButton(onClick = { onTransfer(leg) }, enabled = sheet.canLeave) { Text(slussen.app.ui.transfers.TransfersTexts.OPEN_ITS_TRANSFER) } }
            }
            // NFR-023: a Review Decision that could not be read is said here.
            sheet.reviewNotRead?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            sheet.review?.let { ReviewPart(it, actions) }
            // NFR-023 (review B6 F1): the sheet stays open while its decision is on its way; the Review Decision part says why.
            OutlinedButton(onClick = onClose, enabled = sheet.canLeave) { Text("Close") }
        }
    }
}

/**
 * UC-004 (`review-decision.html`): why it is doubtful, its Transfer, the current decision or the lock, the choices with
 * what each does, Re-upload and clearing; the Budget App Transaction Already Present names; and at the control what it
 * is doing and its answer (NFR-023). While a decision is on its way no control takes a second tap.
 */
@Composable
private fun ReviewPart(review: ReviewUi, actions: ReviewActions) {
    SlussenCard {
        Text("Review Decision", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
        review.lines.forEach { line ->
            Text(line.name, color = SlussenColors.Text3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Text(line.text, color = if (line.name == "Locked") SlussenColors.Amber else SlussenColors.Text, fontSize = 12.sp)
        }
        val chooser = review.chooser
        if (chooser != null) {
            Text(chooser.question, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            chooser.options.forEach { option ->
                Text(option.text, color = SlussenColors.Text, fontSize = 12.sp)
                Button(onClick = { actions.onChooseTransaction(option.reference) }, enabled = review.enabled) { Text(chooser.confirm) }
            }
            TextButton(onClick = actions.onCancelChoosing, enabled = review.enabled) { Text("Cancel") }
        } else {
            review.choices.forEach { choice ->
                Column(Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { choice.choice?.let(actions.onChoose) }, enabled = review.enabled, modifier = Modifier.fillMaxWidth()) { Text(choice.title) }
                    Muted(choice.effect)
                }
            }
            review.reUpload?.let { reUpload ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(reUpload.text, color = SlussenColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = reUpload.on, onCheckedChange = actions.onReUpload, enabled = review.enabled)
                }
            }
            review.clear?.let { clear ->
                Column(Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = actions.onClear, enabled = review.enabled) { Text(clear.title) }
                    Muted(clear.effect)
                }
            }
        }
        // NFR-023: the state of the control before anything is stored, and its answer, in the same place.
        review.pending?.let { Text(it, color = SlussenColors.Text2, fontSize = 12.sp) }
        review.answer?.let { Text(it, color = SlussenColors.Teal, fontSize = 12.sp) }
        Muted(review.closing)
    }
}

/** "What the statuses mean", with Close. */
@Composable
private fun StatusesSheet(sheet: StatusesSheetUi, onClose: () -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(sheet.title, sheet.subtitle, onBack = onClose)
            Muted(sheet.intro)
        }
        items(sheet.statuses) { status ->
            SlussenCard {
                Text(status.status, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Text(status.meaning, color = SlussenColors.Text2, fontSize = 12.sp)
                Text("Now: ${status.now}", color = SlussenColors.Text3, fontSize = 11.sp)
            }
        }
        item {
            Muted(sheet.closing)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}
