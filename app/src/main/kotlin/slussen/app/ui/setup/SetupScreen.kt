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
import slussen.app.ui.components.Banner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.core.connection.KnownBank
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
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
    onSaveNaming: (String, BankAccountType?, BankChoice?, String) -> Unit,
    onCancelNaming: () -> Unit,
    onAddFromFiles: (FileAccountEntry) -> Unit,
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
            SetupStep.BankAccounts -> bankAccountsStep(ui, onSelectOffered, onPair, onSaveNaming, onCancelNaming, onAddFromFiles)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bankAccountsStep(
    ui: SetupUi,
    onSelectOffered: (Long) -> Unit,
    onPair: (Long) -> Unit,
    onSaveNaming: (String, BankAccountType?, BankChoice?, String) -> Unit,
    onCancelNaming: () -> Unit,
    onAddFromFiles: (FileAccountEntry) -> Unit,
) {
    val naming = ui.naming
    if (naming != null) {
        item { NamingCard(naming, onSaveNaming, onCancelNaming) }
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

/**
 * Steps 8-9 (`setup.html#pair-name`): accept or edit the proposed name, confirm or change the Bank Account type and the
 * Bank, and set an optional Start Date (UC-013 BR-004, BR-005, BR-009).
 */
@Composable
private fun NamingCard(naming: NamingUi, onSave: (String, BankAccountType?, BankChoice?, String) -> Unit, onCancel: () -> Unit) {
    val key = naming.offeredBankAccountId to naming.budgetAccountId
    var name by remember(key) { mutableStateOf(naming.proposedName) }
    var type by remember(key) { mutableStateOf<BankAccountType?>(naming.proposedType) }
    var bank by remember(key) { mutableStateOf<BankChoice?>(naming.proposedBank?.let { BankChoice.Known(it.bankId) }) }
    var startDate by remember(key) { mutableStateOf("") }
    SlussenCard {
        Text("Name, Bank Account type and Bank", fontWeight = FontWeight.SemiBold)
        Muted(naming.title)
        Muted("Proposed ${naming.proposedName}")
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        TypeChooser(naming.types, type) { type = it }
        BankChooser(naming.knownBanks, bank) { bank = it }
        Muted(naming.note)
        Text("Start Date · optional")
        Muted("Nothing before a Start Date is offered for Upload")
        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, singleLine = true, placeholder = { Text("2026-09-01") })
        Row {
            Button(onClick = { onSave(name, type, bank, startDate) }) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** The four Bank Account types (UC-013 BR-009, UC-009 BR-012). */
@Composable
fun TypeChooser(types: List<BankAccountType>, chosen: BankAccountType?, onChoose: (BankAccountType) -> Unit) {
    Text("Bank Account type")
    Row {
        types.forEach { option ->
            FilterChip(
                selected = chosen == option,
                onClick = { onChoose(option) },
                label = { Text(option.text) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/** A Bank Slussen already knows, or a new one the Operator names (UC-013 BR-009, UC-009 BR-012). */
@Composable
fun BankChooser(knownBanks: List<KnownBank>, chosen: BankChoice?, onChoose: (BankChoice) -> Unit) {
    var newName by remember { mutableStateOf((chosen as? BankChoice.Named)?.name.orEmpty()) }
    Text("Bank")
    knownBanks.forEach { known ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = chosen == BankChoice.Known(known.bankId), onCheckedChange = { onChoose(BankChoice.Known(known.bankId)) })
            Text(known.name)
        }
    }
    OutlinedTextField(
        value = newName,
        onValueChange = {
            newName = it
            onChoose(BankChoice.Named(it))
        },
        singleLine = true,
        placeholder = { Text("Name a new Bank…") },
    )
}

/** What the Operator chose for a Bank Account of a File Format (UC-013 A4). */
data class FileAccountEntry(
    val fileFormatId: Long,
    val type: BankAccountType?,
    val bank: BankChoice?,
    val balanceOfferedBankAccountId: Long?,
    val budgetAccountId: Long,
    val name: String,
    val startDate: String,
)

/** A4 (`setup.html#file-account`): a Bank Account whose transactions come from Transaction Files (UC-013 BR-006, BR-009). */
@Composable
private fun FileFormatCard(ui: SetupUi, onAdd: (FileAccountEntry) -> Unit) {
    var type by remember { mutableStateOf<BankAccountType?>(null) }
    var bank by remember { mutableStateOf<BankChoice?>(null) }
    var name by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var budgetAccount by remember { mutableStateOf<Long?>(null) }
    var balanceFrom by remember { mutableStateOf<Long?>(null) }
    val format = ui.fileFormats.first()
    SlussenCard {
        Text("Add a file-imported Bank Account", fontWeight = FontWeight.SemiBold)
        Muted("File Format ${format.second}")
        TypeChooser(ui.types, type) { type = it }
        BankChooser(ui.knownBanks, bank) { bank = it }
        Text("Balance from · optional")
        ui.sources.forEach { source ->
            source.offered.forEach { offered ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = balanceFrom == offered.offeredBankAccountId,
                        onCheckedChange = { balanceFrom = if (it) offered.offeredBankAccountId else null },
                    )
                    Text("${source.name} · ${offered.title} ${offered.digits.orEmpty()}")
                }
            }
        }
        Text("Budget Account")
        ui.freeBudgetAccounts.forEach { free ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = budgetAccount == free.budgetAccountId, onCheckedChange = { budgetAccount = free.budgetAccountId })
                Text("${free.title} · ${free.detail}")
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") })
        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, singleLine = true, placeholder = { Text("Start Date · optional, 2026-09-01") })
        Muted("Nothing is proposed from a file: choose the type and the Bank and enter the name")
        OutlinedButton(
            onClick = { budgetAccount?.let { onAdd(FileAccountEntry(format.first, type, bank, balanceFrom, it, name, startDate)) } },
            enabled = budgetAccount != null,
        ) { Text("Add") }
    }
}
