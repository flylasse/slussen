package slussen.app.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

/** What the Operator does on the pairing steps of `setup.html` (UC-013 steps 5-15, A3, A4, A8-A13). */
class PairingActions(
    val onToggleBringIn: (Long) -> Unit,
    val onContinue: () -> Unit,
    val onBack: () -> Unit,
    val onConfirm: () -> Unit,
    val onChooseBudgetAccount: (Long) -> Unit,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onAskKeepByHand: (Long) -> Unit,
    val onCancelKeepByHand: () -> Unit,
    val onConfirmKeepByHand: () -> Unit,
    val onAddAndContinue: (String, BankAccountType?, BankChoice?, String, String) -> Unit,
    val onSkip: () -> Unit,
    val onDone: () -> Unit,
    val onToggleLeftOut: () -> Unit,
    val onBringBack: (Long) -> Unit,
    val onAddFromFiles: (FileAccountEntry) -> Unit,
    val onFetch: (ServiceKey) -> Unit,
)

/** The pairing actions of [viewModel], as the screen calls them. */
fun pairingActionsOf(viewModel: SetupViewModel) = PairingActions(
    onToggleBringIn = viewModel::toggleBringIn,
    onContinue = viewModel::continueBringIn,
    onBack = viewModel::backToChoice,
    onConfirm = viewModel::confirmBringIn,
    onChooseBudgetAccount = viewModel::chooseBudgetAccount,
    onOpenPicker = viewModel::openPicker,
    onClosePicker = viewModel::closePicker,
    onAskKeepByHand = viewModel::askKeepByHand,
    onCancelKeepByHand = viewModel::cancelKeepByHand,
    onConfirmKeepByHand = viewModel::confirmKeepByHand,
    onAddAndContinue = viewModel::addAndContinue,
    onSkip = viewModel::skipForNow,
    onDone = viewModel::done,
    onToggleLeftOut = viewModel::toggleLeftOut,
    onBringBack = viewModel::bringBack,
    onAddFromFiles = { entry ->
        viewModel.addFromTransactionFiles(
            entry.fileFormatId, entry.type, entry.bank, entry.balanceOfferedBankAccountId, entry.budgetAccountId, entry.name, entry.startDate,
        )
    },
    onFetch = viewModel::fetch,
)

/**
 * Set up Slussen (`docs/mockups/setup.html`): ① credentials (UC-014), ② Budgets, ③ Bank Accounts (UC-013), in the order
 * of UC-013 BR-008. The Bank Accounts step is the two pairing steps of S-38: choose what to bring in, then one Bank
 * Account at a time, then the summary.
 */
@Composable
fun SetupScreen(
    ui: SetupUi,
    onBack: () -> Unit,
    onStep: (SetupStep) -> Unit,
    onCredentials: (ServiceKey) -> Unit,
    onToggleBudget: (String) -> Unit,
    onSaveBudgets: () -> Unit,
    pairing: PairingActions,
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
            SetupStep.BankAccounts -> when (ui.stage) {
                PairingStage.Choose, PairingStage.Confirm -> chooseStep(ui, pairing)
                PairingStage.Card -> ui.card?.let { card -> item { BankAccountCard(card, pairing) } }
                PairingStage.Summary -> ui.summary?.let { summary -> item { SummaryCard(summary, pairing) } }
            }
        }
    }
}

/**
 * Steps 5-8 (`setup.html#pair`, `#pair-leave-out`, `#pair-none-chosen`, `#pair-not-imported`, `#pair-nothing-left`,
 * `#probably-added`): the Offered Bank Accounts grouped by Source and by Bank, none switched on, the action that states
 * both numbers, the statement before anything is left out, and Not imported below.
 */
private fun LazyListScope.chooseStep(ui: SetupUi, pairing: PairingActions) {
    item { Muted("Switch on each Bank Account to bring into Slussen. The ones left off are not imported.") }
    if (ui.nothingLeft != null) item { Banner(ui.nothingLeft, null, SlussenColors.Surface2, SlussenColors.Text) }
    items(ui.sources) { source ->
        SectionTitle(source.name)
        if (source.remedy != null) Muted(source.remedy)
        // A3 (`setup.html#consent-first`): the Fetch that offers the Bank Accounts of this Source (UC-001).
        if (source.fetchAction != null) OutlinedButton(onClick = { pairing.onFetch(source.service) }) { Text(source.fetchAction) }
        source.banks.forEach { bank ->
            if (bank.name.isNotEmpty()) Text(bank.name, color = SlussenColors.Text2, fontWeight = FontWeight.SemiBold)
            bank.offered.forEach { offered ->
                SlussenCard(
                    modifier = Modifier.clickable { pairing.onToggleBringIn(offered.offeredBankAccountId) },
                    border = if (offered.chosen) SlussenColors.Teal else SlussenColors.Line,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${offered.title} ${offered.balance}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                        Switch(checked = offered.chosen, onCheckedChange = { pairing.onToggleBringIn(offered.offeredBankAccountId) })
                    }
                    Muted(offered.detail)
                }
            }
        }
        source.proposedMatches.forEach { Muted(it) }
    }
    item {
        Text(
            (if (ui.leftOutOpen) "▾ " else "▸ ") + "Not imported · ${ui.leftOut.size} left out earlier · never read",
            modifier = Modifier.clickable(onClick = pairing.onToggleLeftOut).padding(vertical = 8.dp),
            color = SlussenColors.Text2,
        )
        if (ui.leftOutOpen) {
            ui.leftOut.forEach { line ->
                SlussenCard {
                    Text(line.title, color = SlussenColors.Text)
                    Muted(line.detail)
                    OutlinedButton(onClick = { pairing.onBringBack(line.leftOutBankAccountId) }) { Text("Bring back") }
                }
            }
        }
    }
    if (ui.sources.any { it.offered.isNotEmpty() }) {
        item { Button(onClick = pairing.onContinue, modifier = Modifier.padding(vertical = 8.dp)) { Text(ui.bringInAction) } }
    }
    if (ui.fileFormats.isNotEmpty() && ui.freeBudgetAccounts.isNotEmpty()) item { FileFormatCard(ui, pairing.onAddFromFiles) }
    val statement = ui.statement
    if (ui.stage == PairingStage.Confirm && statement != null) {
        item {
            AlertDialog(
                onDismissRequest = pairing.onBack,
                title = { Text(statement.question) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        statement.broughtIn?.let { Text(it) }
                        statement.leftOut?.let { Text(it) }
                    }
                },
                confirmButton = { TextButton(onClick = pairing.onConfirm) { Text(statement.confirmAction) } },
                dismissButton = { TextButton(onClick = pairing.onBack) { Text("Back") } },
            )
        }
    }
}

/**
 * Steps 9-14 (`setup.html#pair-name`, `#start-date`, `#pair-picker`, `#pair-kept-by-hand`, `#pair-skip`,
 * `#other-currency`, `#already-mapped`): one Bank Account at a time with its progress, the Budget Accounts of its
 * currency and the one proposed, Kept by hand, name, type, Bank, currency and Start Date, Add and continue, Skip for now.
 */
@Composable
private fun BankAccountCard(card: CardUi, pairing: PairingActions) {
    val key = card.offeredBankAccountId
    var name by remember(key) { mutableStateOf(card.proposedName) }
    var type by remember(key) { mutableStateOf<BankAccountType?>(card.proposedType) }
    var bank by remember(key) { mutableStateOf<BankChoice?>(card.proposedBank?.let { BankChoice.Known(it.bankId) }) }
    var startDate by remember(key) { mutableStateOf("") }
    var currency by remember(key, card.currency) { mutableStateOf(card.currency) }
    SlussenCard {
        Muted(card.progress)
        Text("${card.title} ${card.balance}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
        Muted(card.detail)
        Text("Budget Account", fontWeight = FontWeight.SemiBold)
        Muted(card.proposalNote)
        if (card.pickerOpen) {
            card.picker.forEach { group ->
                SectionTitle(group.title)
                group.budgetAccounts.forEach { line -> BudgetAccountLine(line, card.chosenBudgetAccountId, pairing, keepByHand = false) }
            }
            TextButton(onClick = pairing.onClosePicker) { Text("Back to the card") }
        } else {
            Muted(card.listedHeading)
            card.listed.forEach { line -> BudgetAccountLine(line, card.chosenBudgetAccountId, pairing, keepByHand = true) }
            TextButton(onClick = pairing.onOpenPicker) { Text("All free Budget Accounts, per Budget ›") }
        }
        Text("This Bank Account", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        Muted("Proposed ${card.proposedName}")
        TypeChooser(card.types, type) { type = it }
        BankChooser(card.knownBanks, bank) { bank = it }
        Muted(card.note)
        Text("Currency")
        if (card.currencyStatedBySource) {
            Muted("${card.currency} · ${card.currencyNote}")
        } else {
            OutlinedTextField(value = currency, onValueChange = { currency = it }, singleLine = true, label = { Text("Currency") })
            Muted(card.currencyNote)
        }
        Text("Start Date · optional")
        Muted("Nothing before a Start Date is offered for Upload")
        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, singleLine = true, placeholder = { Text("2026-09-01") })
        Row {
            Button(onClick = { pairing.onAddAndContinue(name, type, bank, startDate, currency) }) { Text("Add and continue") }
            TextButton(onClick = pairing.onSkip) { Text("Skip for now") }
        }
    }
    val question = card.keepQuestion
    if (question != null) {
        AlertDialog(
            onDismissRequest = pairing.onCancelKeepByHand,
            title = { Text(question.question) },
            text = { Text(question.detail) },
            confirmButton = { TextButton(onClick = pairing.onConfirmKeepByHand) { Text("Keep by hand") } },
            dismissButton = { TextButton(onClick = pairing.onCancelKeepByHand) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BudgetAccountLine(line: FreeBudgetAccountLine, chosen: Long?, pairing: PairingActions, keepByHand: Boolean) {
    SlussenCard(
        modifier = if (line.choosable) Modifier.clickable { pairing.onChooseBudgetAccount(line.budgetAccountId) } else Modifier,
        border = if (chosen == line.budgetAccountId) SlussenColors.Teal else SlussenColors.Line,
    ) {
        Text("${line.title} ${line.balance}", color = if (line.choosable) SlussenColors.Text else SlussenColors.Text3)
        Muted(line.detail)
        if (keepByHand) TextButton(onClick = { pairing.onAskKeepByHand(line.budgetAccountId) }) { Text("Kept by hand") }
    }
}

/** Step 15 (`setup.html#pair-summary`): added, skipped for now, left out. */
@Composable
private fun SummaryCard(summary: SummaryUi, pairing: PairingActions) {
    SlussenCard {
        Text("Bank Accounts added: ${summary.added.size}", fontWeight = FontWeight.SemiBold)
        summary.added.forEach { Muted(it) }
        if (summary.added.isNotEmpty()) Muted("Fetched and compared from the next Fetch")
        Text("Skipped for now: ${summary.skipped.size}", fontWeight = FontWeight.SemiBold)
        summary.skipped.forEach { Muted(it) }
        Text("Left out: ${summary.leftOut.size} · Not imported · never read", fontWeight = FontWeight.SemiBold)
        summary.leftOut.forEach { Muted(it) }
        Button(onClick = pairing.onDone) { Text("Done") }
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

/**
 * A4 (`setup.html#file-account`): a Bank Account whose transactions come from Transaction Files, its balance from any
 * Offered Bank Account, also a Left-Out one, which is then brought back (UC-013 BR-006, BR-009).
 */
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
        Text("+ Bank Account of a Transaction File", fontWeight = FontWeight.SemiBold)
        Muted("File Format ${format.second}")
        TypeChooser(ui.types, type) { type = it }
        BankChooser(ui.knownBanks, bank) { bank = it }
        Text("Balance from · optional")
        ui.balanceSources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = balanceFrom == source.offeredBankAccountId,
                    onCheckedChange = { balanceFrom = if (it) source.offeredBankAccountId else null },
                )
                Text(source.title)
            }
        }
        Muted("Balance from: any Offered Bank Account of the Data Provider, also one left out, which is then brought back")
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
