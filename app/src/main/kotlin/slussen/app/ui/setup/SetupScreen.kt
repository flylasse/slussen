package slussen.app.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.Banner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.ServiceKey

/**
 * Set up Slussen (`docs/mockups/setup.html`): ① credentials (UC-014), ② Budgets, ③ Bank Accounts (UC-013), in the order
 * of UC-013 BR-008.
 */
@Composable
fun SetupScreen(
    ui: SetupUi,
    onBack: () -> Unit,
    onStep: (SetupStep) -> Unit,
    onCredentials: (ServiceKey) -> Unit,
    onToggleBudget: (String) -> Unit,
    onSaveBudgets: () -> Unit,
    onSelectOffered: (Long) -> Unit,
    onPair: (Long) -> Unit,
    onSaveNaming: (String, String) -> Unit,
    onAddFromFiles: (Long, String, Long, String) -> Unit,
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Set up Slussen", "Service credentials, Budgets and Bank Accounts, entered in the app", onBack)
            Row {
                SetupStep.entries.forEachIndexed { index, step ->
                    FilterChip(
                        selected = ui.step == step,
                        onClick = { onStep(step) },
                        label = { Text("${index + 1} ${step.name.replace("BankAccounts", "Bank Accounts")}") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            if (ui.message != null) Banner(ui.message, null)
        }
        when (ui.step) {
            SetupStep.Credentials -> {
                items(ui.credentialLines) { line ->
                    SlussenCard(modifier = Modifier.clickable { onCredentials(line.service) }) {
                        Text("${line.name} · ${line.kind}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                        Muted(line.status)
                    }
                }
                item {
                    Button(onClick = { onStep(SetupStep.Budgets) }) { Text("Continue to Budgets") }
                    if (!ui.budgetAppAccepted) Muted("Budgets can be chosen once the Budget App's credentials are accepted.")
                }
            }
            SetupStep.Budgets -> {
                items(ui.budgets) { budget ->
                    Row(
                        modifier = Modifier.clickable { onToggleBudget(budget.reference) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = budget.chosen, onCheckedChange = { onToggleBudget(budget.reference) })
                        Text("${budget.name} · ${budget.currency}")
                    }
                }
                if (ui.budgets.isNotEmpty()) item { Button(onClick = onSaveBudgets) { Text("Continue") } }
            }
            SetupStep.BankAccounts -> bankAccountsStep(ui, onSelectOffered, onPair, onSaveNaming, onAddFromFiles)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bankAccountsStep(
    ui: SetupUi,
    onSelectOffered: (Long) -> Unit,
    onPair: (Long) -> Unit,
    onSaveNaming: (String, String) -> Unit,
    onAddFromFiles: (Long, String, Long, String) -> Unit,
) {
    val naming = ui.naming
    if (naming != null) {
        item { NamingCard(naming, onSaveNaming) }
        return
    }
    items(ui.sources) { source ->
        SectionTitle("${source.name} · Offered Bank Accounts")
        if (source.remedy != null) Muted(source.remedy)
        source.offered.forEach { offered ->
            SlussenCard(
                modifier = Modifier.clickable { onSelectOffered(offered.offeredBankAccountId) },
                border = if (ui.selectedOffered == offered.offeredBankAccountId) SlussenColors.Teal else SlussenColors.Line,
            ) {
                Text("${offered.title} ${offered.balance}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                Muted(offered.detail)
            }
        }
        source.proposedMatches.forEach { Muted(it) }
    }
    if (ui.selectedOffered != null) {
        item { SectionTitle("Budget Account for the chosen Bank Account") }
        items(ui.freeBudgetAccounts) { free ->
            SlussenCard(modifier = Modifier.clickable { onPair(free.budgetAccountId) }) {
                Text("${free.title} ${free.balance}", color = SlussenColors.Text)
                Muted(free.detail)
            }
        }
    }
    if (ui.fileFormats.isNotEmpty() && ui.freeBudgetAccounts.isNotEmpty()) {
        item { FileFormatCard(ui, onAddFromFiles) }
    }
}

/** Steps 9-10: accept or edit the proposed name and set an optional Start Date (UC-013 BR-004, BR-005). */
@Composable
private fun NamingCard(naming: NamingUi, onSave: (String, String) -> Unit) {
    var name by remember(naming.bankAccountId) { mutableStateOf(naming.proposedName) }
    var startDate by remember(naming.bankAccountId) { mutableStateOf("") }
    SlussenCard {
        Text("Name", fontWeight = FontWeight.SemiBold)
        Muted("Proposed ${naming.proposedName}")
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        Text("Start Date · optional")
        Muted("Nothing before a Start Date is offered for Upload")
        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, singleLine = true, placeholder = { Text("2026-09-01") })
        Button(onClick = { onSave(name, startDate) }) { Text("Save") }
    }
}

/** A4: a Bank Account whose transactions come from Transaction Files (UC-013 BR-006). */
@Composable
private fun FileFormatCard(ui: SetupUi, onAdd: (Long, String, Long, String) -> Unit) {
    var bank by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var budgetAccount by remember { mutableStateOf<Long?>(null) }
    val format = ui.fileFormats.first()
    SlussenCard {
        Text("Add a file-imported Bank Account", fontWeight = FontWeight.SemiBold)
        Muted("File Format ${format.second}")
        OutlinedTextField(value = bank, onValueChange = { bank = it }, singleLine = true, placeholder = { Text("Bank") })
        ui.freeBudgetAccounts.forEach { free ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = budgetAccount == free.budgetAccountId, onCheckedChange = { budgetAccount = free.budgetAccountId })
                Text("${free.title} · ${free.detail}")
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") })
        OutlinedButton(
            onClick = { budgetAccount?.let { onAdd(format.first, bank, it, name) } },
            enabled = budgetAccount != null,
        ) { Text("Add") }
    }
}
