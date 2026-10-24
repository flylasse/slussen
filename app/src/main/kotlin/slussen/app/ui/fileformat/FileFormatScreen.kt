package slussen.app.ui.fileformat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AccountLine
import slussen.app.ui.components.BankChooser
import slussen.app.ui.components.Banner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.components.TypeChooser
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice

/**
 * A File Format's page (UC-013 A4, BR-006, BR-009, BR-010; `docs/mockups/connections.html`, the File Format
 * page, its state before a Budget is served and `#file-account`): its Bank Accounts, each already mapped, and the form that adds and maps one in one go.
 */
@Composable
fun FileFormatScreen(
    ui: FileFormatUi,
    onBack: () -> Unit,
    onOpenForm: () -> Unit,
    onCancelForm: () -> Unit,
    onChoose: (Long?, Long?) -> Unit,
    onAdd: (BankAccountType?, BankChoice?, String, String, String) -> Unit,
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.name, ui.kind, onBack)
            if (ui.message != null) Banner(ui.message, null, SlussenColors.Surface2, SlussenColors.Text)
            val form = ui.form
            if (form != null) {
                FileAccountForm(ui.name, form, onChoose, onAdd, onCancelForm)
                return@item
            }
            SectionTitle("Bank Accounts · ${ui.bankAccounts.size}")
            SlussenCard {
                if (ui.bankAccounts.isEmpty()) Muted("None yet")
                ui.bankAccounts.forEach { line -> AccountLine(line.title, line.detail, null, null, null, trailing = line.trailing) }
                OutlinedButton(onClick = onOpenForm, enabled = ui.canAdd) { Text("Add a Bank Account of this File Format") }
                if (ui.waitNote != null) Muted(ui.waitNote)
            }
            Muted(ui.footnote)
        }
    }
}

@Composable
private fun FileAccountForm(
    formatName: String,
    form: FileAccountFormUi,
    onChoose: (Long?, Long?) -> Unit,
    onAdd: (BankAccountType?, BankChoice?, String, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var type by remember { mutableStateOf<BankAccountType?>(null) }
    var bank by remember { mutableStateOf<BankChoice?>(null) }
    var name by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var currency by remember(form.currency) { mutableStateOf(form.currency) }
    SectionTitle("Add a Bank Account")
    SlussenCard {
        Muted("$formatName · Transaction Files · nothing is proposed from a file")
        TypeChooser(form.types, type) { type = it }
        BankChooser(form.knownBanks, bank) { bank = it }
        Text("Balance from · optional, an Offered Bank Account of a Data Provider", fontSize = 12.sp, color = SlussenColors.Text2)
        ChoiceItem("None", null, form.chosenBalanceId == null) { onChoose(form.chosenBudgetAccountId, null) }
        form.balanceSources.forEach { source ->
            ChoiceItem(source.title, source.detail, form.chosenBalanceId == source.offeredBankAccountId) {
                onChoose(form.chosenBudgetAccountId, source.offeredBankAccountId)
            }
        }
        Text("Budget Account · free Budget Accounts of the served Budgets", fontSize = 12.sp, color = SlussenColors.Text2)
        form.budgetAccounts.forEach { group ->
            Text(group.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlussenColors.Text2)
            group.budgetAccounts.forEach { free ->
                ChoiceItem("${free.title}  ${free.balance}", null, form.chosenBudgetAccountId == free.budgetAccountId) {
                    onChoose(free.budgetAccountId, form.chosenBalanceId)
                }
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = currency, onValueChange = { currency = it }, singleLine = true, label = { Text("Currency") })
        Muted(form.currencyNote)
        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, singleLine = true, label = { Text("Start Date · optional") }, placeholder = { Text("2026-09-01") })
        Row {
            Button(onClick = { onAdd(type, bank, name, currency, startDate) }) { Text("Add") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** One choice of a single-choice list. */
@Composable
private fun ChoiceItem(title: String, detail: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(title + (detail?.let { " · $it" } ?: ""), fontSize = 13.sp)
    }
}
