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
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * One Budget's workspace (UC-008 steps 4-8, A2-A4, A6; `docs/mockups/budget.html`): its Sources with Source Health and
 * Freshness apart, its Bank Accounts with the balance comparison, and every Blocked Reason with its remedy.
 */
@Composable
fun BudgetScreen(ui: BudgetUi, onRemedy: (RemedyAction) -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.title, ui.subtitle)
            Muted(ui.counts)
            SectionTitle("Sources")
        }
        items(ui.sources) { source ->
            SlussenCard {
                Text(source.name, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(source.kind)
                Muted(source.health)
                Muted(source.freshness)
                Muted(source.counts)
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
