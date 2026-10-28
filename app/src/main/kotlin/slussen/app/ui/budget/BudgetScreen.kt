package slussen.app.ui.budget

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.BlockedReasonLine
import slussen.app.ui.components.FirstUploadMatchingBanner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * One Budget's workspace (UC-008 steps 4-8, A2-A4, A6; `docs/mockups/budget.html`): its Sources with Source Health and
 * Freshness apart, its Bank Accounts with the Source Health and Freshness of their last Fetch Result and the Account
 * Balance next to the Budget Account's, every Blocked Reason with its remedy, First Upload Matching while it is open
 * (A8, BR-009, `#recovery-blocked`), and what this build does not show yet (P9-15).
 */
@Composable
fun BudgetScreen(ui: BudgetUi, onRemedy: (RemedyAction) -> Unit, onStartFirstUploadMatching: () -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.title, ui.subtitle)
            // UC-008 BR-009: First Upload Matching is shown as information or a caution, never in an error colour (`budget.html#recovery-blocked`).
            ui.firstUploadMatching?.let { FirstUploadMatchingBanner(it, onStartFirstUploadMatching) }
            Muted(ui.counts)
            if (ui.note != null) Muted(ui.note)
            SectionTitle("Sources")
        }
        items(ui.sources) { source ->
            SlussenCard {
                Text(source.name, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(source.kind)
                Muted(source.health)
                Muted(source.freshness)
                source.counts?.let { Muted(it) }
                source.blocked.forEach { BlockedReasonLine(it.text, it.action, onRemedy) }
            }
        }
        item { SectionTitle("Bank Accounts") }
        items(ui.bankAccounts) { account ->
            SlussenCard {
                Text(account.name, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted("${account.type} · ${account.counts}")
                Muted(account.health)
                Muted(account.freshness)
                Text(account.balance, color = SlussenColors.Text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                account.blocked.forEach { BlockedReasonLine(it.text, it.action, onRemedy) }
            }
        }
    }
}
