package slussen.app.ui.fetches

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.components.BlockedReasonLine
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * The recent Fetch outcomes (UC-008 A5, BR-008; `docs/mockups/fetch-history.html#list`, `#fetch-detail`,
 * `#fetch-detail-cooldown`, `#empty`): each Fetch with the outcome of each of its Bank Accounts and whether it still asks
 * something now; a tap opens one Fetch in place of the list, which Close and system back close again.
 */
@Composable
fun RecentFetchesScreen(
    state: RecentFetchesState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    onFetchAll: () -> Unit,
    onRemedy: (RemedyAction) -> Unit,
) {
    val ui = state.ui
    if (ui == null) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            ScreenTitle("Recent Fetches", FetchesTexts.SUBTITLE, onBack)
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
        }
        return
    }
    // System back closes an open Fetch first, before it leaves the Recent Fetches.
    BackHandler(enabled = ui.detail != null) { onClose() }
    ui.detail?.let { detail ->
        FetchDetail(detail, onClose, onRemedy)
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Recent Fetches", FetchesTexts.SUBTITLE, onBack)
            // NFR-023: a read that failed is said here; the screen keeps what it showed.
            state.failure?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            ui.notListed?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            if (ui.empty != null) {
                SlussenCard {
                    Text(ui.empty.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                    Muted(ui.empty.body)
                    OutlinedButton(onClick = onFetchAll, modifier = Modifier.fillMaxWidth()) { Text(ui.empty.action) }
                }
            } else {
                Muted(ui.note)
            }
        }
        items(ui.lines, key = { "fetch-${it.key}" }) { line -> FetchLine(line, onOpen) }
    }
}

/** One recent Fetch of the list. */
@Composable
private fun FetchLine(line: FetchLineUi, onOpen: (Long) -> Unit) {
    SlussenCard(modifier = if (line.opens) Modifier.clickable { onOpen(line.key) } else Modifier) {
        Text(line.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(line.outcome, color = SlussenColors.Text3, fontSize = 11.sp)
        line.summary?.let { Muted(it) }
        Text(line.need, color = if (line.asksNow) SlussenColors.Amber else SlussenColors.Text2, fontSize = 12.sp)
        if (line.opens) Text("Bank Accounts ›", color = SlussenColors.Text3, fontSize = 11.sp)
    }
}

/** One Fetch with the outcome of each Bank Account at the time, read-only, with Close. */
@Composable
private fun FetchDetail(detail: FetchDetailUi, onClose: () -> Unit, onRemedy: (RemedyAction) -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(detail.title, detail.subtitle, onClose)
            Text(detail.need, color = SlussenColors.Text2, fontSize = 12.sp)
        }
        items(detail.bankAccounts) { account ->
            SlussenCard {
                Text(account.name, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(account.result, color = SlussenColors.Text3, fontSize = 11.sp)
                account.note?.let { Muted(it) }
                account.asks?.let { BlockedReasonLine(it, account.action, onRemedy, account.advice) }
            }
        }
        item { OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(FetchesTexts.CLOSE) } }
    }
}
