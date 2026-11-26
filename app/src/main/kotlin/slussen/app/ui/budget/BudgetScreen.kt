package slussen.app.ui.budget

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.BlockedReasonLine
import slussen.app.ui.components.BudgetAppLine
import slussen.app.ui.components.FirstUploadMatchingBanner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.NavigationLine
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.Texts
import slussen.app.ui.transfers.TransfersTexts
import slussen.app.ui.theme.SlussenColors

/**
 * One Budget's workspace (UC-008 steps 4-8, A2-A4, A6; `docs/mockups/budget.html`): its Sources with Source Health and
 * Freshness apart, its Bank Accounts with the Source Health and Freshness of their last Fetch Result and the Account
 * Balance next to the Budget Account's, every Blocked Reason with its remedy, First Upload Matching while it is open
 * (A8, BR-009, `#recovery-blocked`), and what this build does not show yet (P9-15).
 */
@Composable
fun BudgetScreen(
    ui: BudgetUi,
    onRemedy: (RemedyAction) -> Unit,
    onStartFirstUploadMatching: () -> Unit,
    onReadAgain: () -> Unit = {},
    onBankAccount: (Long) -> Unit = {},
    onTransfers: () -> Unit = {},
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.title, ui.subtitle)
            // UC-008 BR-009: First Upload Matching is shown as information or a caution, never in an error colour (`budget.html#recovery-blocked`).
            ui.firstUploadMatching?.let { FirstUploadMatchingBanner(it, onStartFirstUploadMatching) }
            // FR-040, NFR-023 (`budget.html`, the states of Read again): the line of the Budget App, whose Read again answers on it.
            BudgetAppLine(ui.budgetAppLine, onReadAgain, onRemedy)
            Muted(ui.counts)
            // A1 step 2 of UC-003: statuses never shown as current when the read since failed.
            ui.statuses?.let { Muted(it) }
            ui.notComparedYet?.let { Muted(it) }
            ui.readAgainNote?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
            if (ui.note != null) Muted(ui.note)
            // Step 1 of UC-005 (`budget.html` → `transfers.html#list`): the entry to the Transfers, whose counts agree with its list (UX-27).
            SlussenCard { NavigationLine(TransfersTexts.OPEN_TRANSFERS, ui.transfers, onTransfers) }
            SectionTitle("Sources")
        }
        items(ui.sources) { source ->
            SlussenCard {
                Text(source.name, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(source.kind)
                Muted(source.health)
                Muted(source.freshness)
                source.counts?.let { Muted(it) }
                source.blocked.forEach { BlockedReasonLine(it.text, it.action, onRemedy, it.advice) }
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
                // A21 of UC-001 (`bank-account.html#balance-other-currency`): both currencies, nothing converted.
                // A2 of UC-008 (UX-24): an outdated Account Balance names its remedy.
                account.balanceRemedy?.let { Muted(it) }
                account.balanceNotice?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
                account.blocked.forEach { BlockedReasonLine(it.text, it.action, onRemedy, it.advice) }
                // Step 5 of UC-008 (`bank-account.html`): the way to the Bank Account's own screen with its Bank Transactions.
                NavigationLine(Texts.OPEN_BANK_TRANSACTIONS, null) { onBankAccount(account.bankAccountId) }
            }
        }
    }
}
