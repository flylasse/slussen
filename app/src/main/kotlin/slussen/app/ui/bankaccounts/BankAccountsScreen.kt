package slussen.app.ui.bankaccounts

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * Bank Accounts and Account Mappings (`docs/mockups/account-mappings.html`): each Bank Account → Budget Account by
 * Budget, and the action that adds one (UC-013). Changing a mapping comes with Manage Account Mappings (UC-009).
 */
@Composable
fun BankAccountsScreen(groups: List<BudgetGroup>, onBack: () -> Unit, onAdd: () -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item { ScreenTitle("Bank Accounts and Account Mappings", "Bank Account → Budget Account", onBack) }
        if (groups.isEmpty()) item { Muted("No Bank Account added yet") }
        groups.forEach { group ->
            item { SectionTitle(group.title) }
            items(group.lines) { line ->
                SlussenCard {
                    Text("${line.bankAccount} → ${line.budgetAccount}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                    Muted(line.detail)
                    if (line.note != null) Muted(line.note)
                }
            }
        }
        item {
            Button(onClick = onAdd, modifier = Modifier.padding(vertical = 8.dp)) { Text("+ Add Bank Account") }
            Muted("Renaming, Start Dates, moving and retiring come with Manage Account Mappings")
        }
    }
}
