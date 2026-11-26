package slussen.app.ui.transfers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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

/**
 * The Transfers of one Budget (UC-005 step 1, A4, A5, A8, BR-010; `docs/mockups/transfers.html`, the permanent layout
 * of `#list`, `#selected`, `#lonely`, `#first-card-payment`, `#doubtful-leg` and `#no-transfers` without the decision
 * controls): each Transfer with its two Budget Accounts, amount and sides, Guessed Transfers flagged and counted; a tap
 * opens one Transfer read-only in place of the list, which Close and system back close again.
 */
@Composable
fun TransfersScreen(state: TransfersState, onBack: () -> Unit, onOpen: (Long) -> Unit, onClose: () -> Unit) {
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
        TransferSheet(ui.title, sheet, onClose)
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.title, ui.statuses, onBack = onBack)
            // NFR-023: a read that failed is said here; the screen keeps what it showed.
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            ui.notListed?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
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

/** One Transfer, read-only, with Close. */
@Composable
private fun TransferSheet(title: String, sheet: TransferSheetUi, onClose: () -> Unit) {
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
                Muted(sheet.closing)
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
