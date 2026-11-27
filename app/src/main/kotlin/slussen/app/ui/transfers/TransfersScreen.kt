package slussen.app.ui.transfers

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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/** What the Operator does with the Transfer Decisions of a selected Transfer (UC-005 steps 4-6, A1-A4, A8-A10). */
class TransferDecisionActions(
    val onDecide: (TransferAction) -> Unit = {},
    val onOpenChooser: () -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onKeep: (Boolean) -> Unit = {},
    val onUse: () -> Unit = {},
    val onCancelChooser: () -> Unit = {},
    /** A9 step 2: to the Review Decision of the Transfer Leg that waits for it, on its Bank Account screen. */
    val onReviewLeg: (ReviewLegUi) -> Unit = {},
    /** A8 step 1: to Account Mappings, where a Funded By that no longer applies is changed or cleared (UC-009 A9). */
    val onAccountMappings: () -> Unit = {},
)

/** The Transfer Decision actions of [viewModel]; the two ways out of the screen are given by the navigation. */
fun transferDecisionActionsOf(viewModel: TransfersViewModel, onReviewLeg: (ReviewLegUi) -> Unit, onAccountMappings: () -> Unit) = TransferDecisionActions(
    onDecide = viewModel::decide,
    onOpenChooser = viewModel::openChooser,
    onSelect = viewModel::selectBudgetAccount,
    onKeep = viewModel::setKeep,
    onUse = viewModel::useChosen,
    onCancelChooser = viewModel::cancelChooser,
    onReviewLeg = onReviewLeg,
    onAccountMappings = onAccountMappings,
)

/**
 * The Transfers of one Budget (UC-005; `docs/mockups/transfers.html`, `transfer-budget-account.html`): each Transfer with
 * its two Budget Accounts, amount and sides, Guessed Transfers flagged and counted; a tap opens one Transfer in place of
 * the list with its Transfer Decisions, which Close and system back close again.
 */
@Composable
fun TransfersScreen(
    state: TransfersState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    decisions: TransferDecisionActions = TransferDecisionActions(),
) {
    val ui = state.ui
    if (ui == null) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            ScreenTitle("Transfers", onBack = onBack)
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
        }
        return
    }
    // System back closes an open Transfer first, before it leaves the Transfers.
    BackHandler(enabled = ui.sheet != null) { onClose() }
    ui.sheet?.let { sheet ->
        TransferSheet(ui.title, sheet, onClose, onOpen, decisions)
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.title, ui.statuses, onBack = onBack)
            // NFR-023: a read that failed is said here; the screen keeps what it showed.
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            ui.notListed?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            ui.answer?.let { Text(it, color = SlussenColors.Teal, fontSize = 12.sp) }
            ui.readAgainNote?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            ui.notComparedYet?.let { Muted(it) }
            SlussenCard {
                // Step 1 of UC-005: the number of Guessed Transfers of the Budget, with the counting unit of each count (UX-27).
                Text(ui.counts, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                ui.countsNote?.let { Muted(it) }
                Muted(ui.note)
            }
            ui.empty?.let { SlussenCard { Text(it, color = SlussenColors.Text2, fontSize = 12.sp) } }
        }
        items(ui.transfers, key = { "transfer-${it.key}" }) { card -> TransferCard(card, onOpen) }
        if (ui.noLongerTransfers.isNotEmpty()) {
            item { SectionTitle("No longer Transfers") }
            items(ui.noLongerTransfers) { line ->
                SlussenCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(line.title, color = SlussenColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(line.amount, color = SlussenColors.Text, fontSize = 13.sp)
                    }
                    Muted(line.reason)
                }
            }
        }
    }
}

/** One Transfer of the list; a tap opens it. */
@Composable
private fun TransferCard(card: TransferCardUi, onOpen: (Long) -> Unit) {
    SlussenCard(
        modifier = Modifier.clickable { onOpen(card.key) },
        border = if (card.guessed) SlussenColors.Amber else SlussenColors.Line,
    ) {
        Text(card.chips.joinToString(" · "), color = if (card.guessed) SlussenColors.Amber else SlussenColors.Text3, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Top) {
            Text(card.budgetAccounts, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(card.amount, color = SlussenColors.Text)
        }
        card.sides.forEach { Text(it, color = SlussenColors.Text2, fontSize = 12.sp) }
        Text(card.state, color = SlussenColors.Text3, fontSize = 11.sp)
    }
}

/** One Transfer with its Transfer Decisions, and Close. */
@Composable
private fun TransferSheet(title: String, sheet: TransferSheetUi, onClose: () -> Unit, onOpen: (Long) -> Unit, actions: TransferDecisionActions) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(title, onBack = onClose)
            SlussenCard {
                Text(sheet.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(sheet.subtitle)
                sheet.details.forEach { detail ->
                    Text(detail.name, color = SlussenColors.Text3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(detail.text, color = SlussenColors.Text, fontSize = 12.sp)
                }
            }
            // NFR-023: Transfer Decisions that could not be read are said here.
            sheet.decisionNotRead?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            sheet.decision?.let { DecisionPart(it, onOpen, actions) }
            Muted(sheet.closing)
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

/**
 * UC-005 steps 3-6, A1-A4, A7-A10: the Transfer Decision, the lock or the Transfer Leg it waits for, each decision with
 * what it does, choosing another Budget Account with "Keep this answer?" and its refusal, and at the control what it is
 * doing and its answer (NFR-023). While a decision is on its way no control takes a second tap.
 */
@Composable
private fun DecisionPart(decision: TransferDecisionUi, onOpen: (Long) -> Unit, actions: TransferDecisionActions) {
    SlussenCard {
        Text("Transfer Decision", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
        decision.lines.forEach { line ->
            Text(line.name, color = SlussenColors.Text3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Text(line.text, color = if (line.name == "Locked" || line.name == "Waits") SlussenColors.Amber else SlussenColors.Text, fontSize = 12.sp)
        }
        decision.reviewLeg?.let { leg -> OutlinedButton(onClick = { actions.onReviewLeg(leg) }, enabled = decision.enabled) { Text(leg.title) } }
        decision.changeFundedBy?.let { title ->
            OutlinedButton(onClick = actions.onAccountMappings) { Text(title) }
            Muted("In Account Mappings.")
        }
        val chooser = decision.chooser
        if (chooser != null) {
            Chooser(chooser, decision.enabled, onOpen, actions)
        } else {
            decision.actions.forEach { action ->
                Column(Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { action.action?.let(actions.onDecide) }, enabled = decision.enabled, modifier = Modifier.fillMaxWidth()) { Text(action.title) }
                    Muted(action.effect)
                }
            }
            decision.choose?.let { choose ->
                Column(Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = actions.onOpenChooser, enabled = decision.enabled, modifier = Modifier.fillMaxWidth()) { Text(choose.title) }
                    Muted(choose.effect)
                }
            }
        }
        // NFR-023: the state of the control before anything is stored, and its answer, in the same place.
        decision.pending?.let { Text(it, color = SlussenColors.Text2, fontSize = 12.sp) }
        decision.answer?.let { Text(it, color = SlussenColors.Teal, fontSize = 12.sp) }
    }
}

/** UC-005 A3, A8, A10 (`transfer-budget-account.html`): the Budget Accounts, "Keep this answer?", Use, and the refusal. */
@Composable
private fun Chooser(chooser: TransferChooserUi, enabled: Boolean, onOpen: (Long) -> Unit, actions: TransferDecisionActions) {
    Text(chooser.question, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Muted(chooser.subtitle)
    chooser.options.forEach { option ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { actions.onSelect(option.budgetAccountId) }) {
            RadioButton(selected = option.chosen, onClick = { actions.onSelect(option.budgetAccountId) }, enabled = enabled)
            Column {
                Text(option.name, color = SlussenColors.Text, fontSize = 13.sp)
                option.detail?.let { Muted(it) }
            }
        }
    }
    Text("Keep this answer?", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !chooser.keep, onClick = { actions.onKeep(false) }, enabled = enabled)
        Text(chooser.justThis, color = SlussenColors.Text, fontSize = 12.sp)
    }
    chooser.keepAnswer?.let { answer ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = chooser.keep, onClick = { actions.onKeep(true) }, enabled = enabled)
            Column {
                Text(answer, color = SlussenColors.Text, fontSize = 12.sp)
                Muted(chooser.keepEffect)
            }
        }
    }
    chooser.refused?.let { refused ->
        Text(refused.title, color = SlussenColors.Amber, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(refused.text, color = SlussenColors.Text, fontSize = 12.sp)
        refused.showTransferLeg?.let { leg -> TextButton(onClick = { onOpen(leg) }) { Text("Show that Transfer") } }
    }
    Row {
        chooser.use?.let { use -> Button(onClick = actions.onUse, enabled = enabled) { Text(use) } }
        TextButton(onClick = actions.onCancelChooser, enabled = enabled) { Text("Cancel") }
    }
}
