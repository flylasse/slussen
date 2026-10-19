package slussen.app.ui.bankaccounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.Banner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.setup.BankChooser
import slussen.app.ui.setup.TypeChooser
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice

/**
 * Bank Accounts and Account Mappings (`docs/mockups/account-mappings.html`): each Bank Account → Budget Account by
 * Budget, the action that adds one (UC-013), and on each Bank Account its Bank Account type and Bank, changed in the
 * sheet of `#type-and-bank` (UC-009 A14). The other changes of Manage Account Mappings come with the slice that builds
 * UC-009.
 */
@Composable
fun BankAccountsScreen(
    ui: BankAccountsUi,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenTypeAndBank: (Long) -> Unit,
    onSaveTypeAndBank: (BankAccountType, BankChoice) -> Unit,
    onConfirmClearing: () -> Unit,
    onCancelClearing: () -> Unit,
    onCancelTypeAndBank: () -> Unit,
) {
    val sheet = ui.typeAndBank
    if (sheet != null) {
        TypeAndBankSheet(sheet, ui.message, onSaveTypeAndBank, onCancelTypeAndBank)
        if (sheet.clearingQuestion != null) {
            AlertDialog(
                onDismissRequest = onCancelClearing,
                title = { Text("Clear it?") },
                text = { Text(sheet.clearingQuestion) },
                confirmButton = { TextButton(onClick = onConfirmClearing) { Text("Save") } },
                dismissButton = { TextButton(onClick = onCancelClearing) { Text("Cancel") } },
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Bank Accounts and Account Mappings", "Bank Account → Budget Account", onBack)
            if (ui.message != null) Banner(ui.message, null, SlussenColors.Surface2, SlussenColors.Text)
        }
        if (ui.groups.isEmpty()) item { Muted("No Bank Account added yet") }
        ui.groups.forEach { group ->
            item { SectionTitle(group.title) }
            items(group.lines) { line ->
                SlussenCard(modifier = Modifier.clickable { onOpenTypeAndBank(line.bankAccountId) }) {
                    Text("${line.bankAccount} → ${line.budgetAccount}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                    Muted(line.detail)
                    if (line.note != null) Muted(line.note)
                    Muted("Bank Account type and Bank…")
                }
            }
        }
        item {
            Button(onClick = onAdd, modifier = Modifier.padding(vertical = 8.dp)) { Text("+ Add Bank Account") }
            Muted("Renaming, Start Dates, moving and retiring come with Manage Account Mappings")
        }
    }
}

/** UC-009 A14 steps 1-2: the four types and the Banks Slussen knows, or a new one named (BR-012). */
@Composable
private fun TypeAndBankSheet(
    sheet: TypeAndBankUi,
    message: String?,
    onSave: (BankAccountType, BankChoice) -> Unit,
    onCancel: () -> Unit,
) {
    var type by remember(sheet.bankAccountId) { mutableStateOf(sheet.type) }
    var bank by remember(sheet.bankAccountId) { mutableStateOf<BankChoice>(BankChoice.Known(sheet.bank.bankId)) }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Bank Account type and Bank", sheet.title, onCancel)
            if (message != null) Banner(message, null)
            SlussenCard {
                TypeChooser(sheet.typeOptions.map { it.value }, type) { type = it }
                sheet.typeOptions.filter { it.text != it.value.text }.forEach { Muted(it.text) }
                BankChooser(sheet.bankOptions.map { it.value }, bank) { bank = it }
                Muted(sheet.note)
                Row {
                    Button(onClick = { onSave(type, bank) }) { Text("Save") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}
