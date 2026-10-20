package slussen.app.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.Banner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * The Overview tab (UC-008 steps 1-3, A1, A7, A8, A10; `docs/mockups/overview.html`): while no Bank Account is added,
 * only the card that leads to Settings (`#new-installation`, `#first-run-next`); otherwise the notices that appear at
 * every opening (the Recovery Check with what it holds back, P9-14), the Fetch of all Data Providers above the Budget
 * cards with one line on what a Fetch does in this build (UC-001 A11, with the confirmation of a warning, A16; P9-14),
 * the expiring Bank Consents, and every Budget with its counts and blocked states.
 */
@Composable
fun OverviewScreen(
    ui: OverviewUi,
    onBudget: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirmRecoveryCheck: () -> Unit,
    onRecentFetches: () -> Unit,
    onFetchAll: () -> Unit,
    onConfirmFetch: () -> Unit,
    onCancelFetch: () -> Unit,
) {
    val firstRun = ui.firstRun
    if (firstRun != null) {
        // UC-008 BR-011: While no Bank Account is added, the Overview shows nothing but the card that leads to Settings.
        LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
            item {
                ScreenTitle("Slussen", ui.heading)
                SlussenCard(border = SlussenColors.Teal) {
                    Text(firstRun.title, color = SlussenColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(firstRun.next, color = SlussenColors.Teal, fontSize = 13.sp)
                    if (firstRun.then != null) Muted(firstRun.then)
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
        }
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Slussen", ui.heading)
            Muted(ui.budgetAppRead)
        }
        items(ui.unreadableNotices) { Banner(it, null) }
        if (ui.recoveryCheck != null) {
            item {
                Banner(ui.recoveryCheck, ui.recoveryCause, SlussenColors.RedBackground, SlussenColors.Red)
                if (ui.recoveryNote != null) Muted(ui.recoveryNote)
                OutlinedButton(onClick = onConfirmRecoveryCheck) { Text("Recovery Check") }
            }
        }
        if (ui.message != null) item { Banner(ui.message, null, SlussenColors.Surface2, SlussenColors.Text) }
        if (ui.backupWarning != null) item { Banner(ui.backupWarning, null) }
        // P9-14: the Fetch action stands above the Budget cards, where it is seen without scrolling.
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Button(onClick = onFetchAll, enabled = ui.canFetch) { Text("Fetch all Data Providers") }
                if (ui.fetchNote != null) Muted(ui.fetchNote)
                ui.fetchMessages.forEach { Muted(it) }
            }
        }
        items(ui.expiringConsents) { Banner(it, ui.expiringConsentNote) }
        items(ui.budgets) { budget ->
            SlussenCard(modifier = Modifier.clickable { onBudget(budget.budgetId) }) {
                Row {
                    Text(budget.name, color = SlussenColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(budget.currency, color = SlussenColors.Text2, fontSize = 12.sp)
                }
                Muted(budget.counts)
                if (budget.blocked.isEmpty()) {
                    Text("Nothing blocked", color = SlussenColors.Green, fontSize = 12.sp)
                } else {
                    budget.blocked.forEach { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
                }
            }
        }
        item {
            OutlinedButton(onClick = onRecentFetches, modifier = Modifier.padding(vertical = 8.dp)) { Text("Recent Fetches") }
        }
    }
    val warning = ui.fetchWarning
    if (warning != null) {
        AlertDialog(
            onDismissRequest = onCancelFetch,
            title = { Text(warning.title) },
            text = {
                Column {
                    Text(warning.text)
                    Muted(warning.risk)
                }
            },
            confirmButton = { TextButton(onClick = onConfirmFetch) { Text("Fetch") } },
            dismissButton = { TextButton(onClick = onCancelFetch) { Text("Cancel") } },
        )
    }
}
