package slussen.app.ui.mappings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import slussen.core.port.ServiceKey

/** What the Operator does on the card of one Bank Account at a time (UC-013 steps 9-15, A8). */
class CardActions(
    val onChooseBudgetAccount: (Long) -> Unit,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onAddAndContinue: (String, BankAccountType?, BankChoice?, String, String) -> Unit,
    val onSkip: () -> Unit,
    val onDone: () -> Unit,
)

/** What the Operator does in the sheet of the Bank Account of one Bank Account and its editors (UC-009 step 2, A4-A13). */
class MappingChangeActions(
    val onOpenSheet: (Long) -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onAction: (MappingAction) -> Unit = {},
    val onSaveName: (String) -> Unit = {},
    val onSaveStartDate: (String) -> Unit = {},
    val onClearStartDate: () -> Unit = {},
    val onMoveTo: (Long) -> Unit = {},
    val onSaveOtherSide: (Long?) -> Unit = {},
    val onIdentifyWith: (Long) -> Unit = {},
    val onReject: () -> Unit = {},
    val onCancelEditor: () -> Unit = {},
    val onConfirmQuestion: () -> Unit = {},
    val onCancelQuestion: () -> Unit = {},
)

/** The sheet of a Bank Account actions of [viewModel], as the screen calls them. */
fun mappingChangeActionsOf(viewModel: AccountMappingsViewModel) = MappingChangeActions(
    onOpenSheet = viewModel::openSheet,
    onCloseSheet = viewModel::closeSheet,
    onAction = viewModel::startAction,
    onSaveName = viewModel::saveName,
    onSaveStartDate = viewModel::saveStartDate,
    onClearStartDate = viewModel::clearStartDate,
    onMoveTo = { viewModel.moveTo(it) },
    onSaveOtherSide = viewModel::saveOtherSide,
    onIdentifyWith = { viewModel.identifyWith(it) },
    onReject = viewModel::rejectProposal,
    onCancelEditor = viewModel::cancelEditor,
    onConfirmQuestion = viewModel::confirmQuestion,
    onCancelQuestion = viewModel::cancelQuestion,
)

/** The card actions of [viewModel], as the screen calls them. */
fun cardActionsOf(viewModel: AccountMappingsViewModel) = CardActions(
    onChooseBudgetAccount = viewModel::chooseBudgetAccount,
    onOpenPicker = viewModel::openPicker,
    onClosePicker = viewModel::closePicker,
    onAddAndContinue = viewModel::addAndContinue,
    onSkip = viewModel::skipForNow,
    onDone = viewModel::done,
)

/**
 * The Account Mappings (`docs/mockups/account-mappings.html#to-map`, `#map`, `#not-imported`, `#kept-by-hand`): the
 * Bank Accounts to map first, one line each, each opening the card of one Bank Account at a time (UC-013 steps 8-15);
 * then each Bank Account → Budget Account by Budget with its Funded By or Transfer Partner, whether anything was sent
 * through it and whether it is identified, each opening its sheet (`#sheet`) with every change of UC-009 (A4-A14);
 * then, only when there is one, Not imported and Kept by hand, read-only, leading to the Data Provider's and the Budget
 * App's pages (UC-009 A15, A16, BR-013, BR-014). There is no Add Bank Account and no Keep by hand button.
 */
@Composable
fun AccountMappingsScreen(
    ui: AccountMappingsUi,
    onBack: () -> Unit,
    onMapAll: () -> Unit,
    onMapOne: (Long) -> Unit,
    onService: (ServiceKey) -> Unit,
    card: CardActions,
    onOpenTypeAndBank: (Long) -> Unit,
    onSaveTypeAndBank: (BankAccountType, BankChoice) -> Unit,
    onConfirmClearing: () -> Unit,
    onCancelClearing: () -> Unit,
    onCancelTypeAndBank: () -> Unit,
    changes: MappingChangeActions = MappingChangeActions(),
) {
    // System back closes an open editor or sheet of a Bank Account first, before it leaves the Account Mappings.
    BackHandler(enabled = ui.editor != null || ui.bankAccountSheet != null) { if (ui.editor != null) changes.onCancelEditor() else changes.onCloseSheet() }
    ui.question?.let { question ->
        AlertDialog(
            onDismissRequest = changes.onCancelQuestion,
            title = { Text(question.title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    question.lines.forEach { Text(it) }
                    // NFR-023: the state and the answer of the confirmation, at the confirmation.
                    ui.pending?.let { Muted(it) }
                    ui.message?.let { Text(it, color = SlussenColors.Amber) }
                }
            },
            confirmButton = { TextButton(onClick = changes.onConfirmQuestion, enabled = ui.pending == null) { Text(question.confirm) } },
            dismissButton = { TextButton(onClick = changes.onCancelQuestion, enabled = ui.pending == null) { Text("Cancel") } },
        )
    }
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
    ui.editor?.let { editor ->
        Editor(editor, ui.message, ui.pending, changes)
        return
    }
    ui.bankAccountSheet?.let { bankAccountSheet ->
        BankAccountSheet(bankAccountSheet, ui.message, ui.pending, changes)
        return
    }
    when (ui.stage) {
        MappingsStage.Card -> ui.card?.let { BankAccountCard(it, ui.message, card, onBack) }
        MappingsStage.Summary -> ui.summary?.let { SummaryCard(it, card) }
        MappingsStage.List -> MappingsList(ui, onBack, onMapAll, onMapOne, onService, changes.onOpenSheet)
    }
}

/** UC-009 step 2 (`#sheet`): every action for one Bank Account, with Close. */
@Composable
private fun BankAccountSheet(sheet: BankAccountSheetUi, message: String?, pending: String?, changes: MappingChangeActions) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(sheet.title, sheet.subtitle, changes.onCloseSheet)
            pending?.let { Muted("$it ${AccountMappingsTexts.STAYS_OPEN}") }
            if (message != null) Banner(message, null)
            SlussenCard {
                sheet.actions.forEach { action ->
                    AccountLine(action.title, action.detail, null, null, null, modifier = Modifier.clickable(enabled = pending == null) { changes.onAction(action.action) })
                }
            }
            TextButton(onClick = changes.onCloseSheet, enabled = pending == null) { Text("Close") }
        }
    }
}

/** The editors of A6, A8, A9, A12 and A13, each answering at its own controls (NFR-023). */
@Composable
private fun Editor(editor: MappingEditorUi, message: String?, pending: String?, changes: MappingChangeActions) {
    val enabled = pending == null
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            when (editor) {
                is MappingEditorUi.Rename -> {
                    var name by remember(editor.bankAccountId) { mutableStateOf(editor.name) }
                    ScreenTitle("Rename", editor.detail, changes.onCancelEditor)
                    SlussenCard {
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        Muted("The name never identifies the Bank Account.")
                        Row {
                            Button(onClick = { changes.onSaveName(name) }, enabled = enabled) { Text("Save") }
                            TextButton(onClick = changes.onCancelEditor, enabled = enabled) { Text("Cancel") }
                        }
                    }
                }
                is MappingEditorUi.StartDate -> {
                    var date by remember(editor.bankAccountId) { mutableStateOf(editor.current) }
                    ScreenTitle("Start Date", editor.name, changes.onCancelEditor)
                    SlussenCard {
                        OutlinedTextField(value = date, onValueChange = { date = it }, singleLine = true, label = { Text("Start Date") }, placeholder = { Text("2026-09-01") })
                        Muted("Nothing before a Start Date is offered for Upload")
                        Row {
                            Button(onClick = { changes.onSaveStartDate(date) }, enabled = enabled) { Text("Save") }
                            TextButton(onClick = changes.onClearStartDate, enabled = enabled) { Text("Clear") }
                            TextButton(onClick = changes.onCancelEditor, enabled = enabled) { Text("Cancel") }
                        }
                    }
                }
                is MappingEditorUi.Move -> {
                    ScreenTitle(editor.title, editor.subtitle, changes.onCancelEditor)
                    SlussenCard {
                        if (editor.choices.isEmpty()) Muted("No Budget Account in its currency receives through no Account Mapping.")
                        editor.choices.forEach { choice ->
                            AccountLine(choice.title, choice.detail, null, null, null, modifier = Modifier.clickable(enabled = enabled) { changes.onMoveTo(choice.id) })
                        }
                        TextButton(onClick = changes.onCancelEditor, enabled = enabled) { Text("Cancel") }
                    }
                }
                is MappingEditorUi.OtherSide -> {
                    ScreenTitle(editor.title, "Now: ${editor.current}", changes.onCancelEditor)
                    SlussenCard {
                        Muted("Budget Accounts Slussen fetches")
                        editor.choices.forEach { choice ->
                            AccountLine(choice.title, choice.detail, null, null, null, modifier = Modifier.clickable(enabled = enabled) { changes.onSaveOtherSide(choice.id) })
                        }
                        Row {
                            TextButton(onClick = { changes.onSaveOtherSide(null) }, enabled = enabled) { Text("Clear") }
                            TextButton(onClick = changes.onCancelEditor, enabled = enabled) { Text("Cancel") }
                        }
                    }
                }
                is MappingEditorUi.Identify -> {
                    ScreenTitle(editor.title, editor.subtitle, changes.onCancelEditor)
                    SlussenCard {
                        editor.none?.let { Muted(it) }
                        editor.options.forEach { option ->
                            AccountLine(
                                listOfNotNull(option.title, option.tag).joinToString(" · "), option.detail, null, null, null,
                                modifier = Modifier.clickable(enabled = enabled) { changes.onIdentifyWith(option.id) },
                            )
                        }
                        Row {
                            if (editor.canReject) TextButton(onClick = changes.onReject, enabled = enabled) { Text("Not this one") }
                            TextButton(onClick = changes.onCancelEditor, enabled = enabled) { Text("Cancel") }
                        }
                    }
                }
            }
            // NFR-023: the state of the change from the tap on, and its answer, at the editor, which stays until the answer is in.
            pending?.let { Muted("$it ${AccountMappingsTexts.STAYS_OPEN}") }
            if (message != null) Banner(message, null)
        }
    }
}

@Composable
private fun MappingsList(
    ui: AccountMappingsUi,
    onBack: () -> Unit,
    onMapAll: () -> Unit,
    onMapOne: (Long) -> Unit,
    onService: (ServiceKey) -> Unit,
    onOpenSheet: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Account Mappings", "Bank Accounts → Budget Accounts", onBack)
            if (ui.message != null) Banner(ui.message, null, SlussenColors.Surface2, SlussenColors.Text)
        }
        if (ui.toMap.isNotEmpty()) {
            item { SectionTitle(ui.toMapTitle) }
            item {
                SlussenCard {
                    ui.toMap.forEach { line ->
                        AccountLine(
                            title = listOfNotNull(line.title, line.digits).joinToString(" "),
                            detail = line.detail,
                            balance = line.balance,
                            switchOn = null,
                            onSwitch = null,
                            modifier = Modifier.clickable { onMapOne(line.offeredBankAccountId) },
                        )
                    }
                    Button(onClick = onMapAll) { Text("Map all, one at a time") }
                }
            }
        }
        if (ui.groups.isEmpty() && ui.toMap.isEmpty()) item { Muted("No Bank Account added yet") }
        ui.groups.forEach { group ->
            item { SectionTitle(group.title) }
            items(group.lines) { line ->
                SlussenCard(modifier = Modifier.clickable { onOpenSheet(line.bankAccountId) }) {
                    Text("${line.bankAccount} → ${line.budgetAccount}", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Muted(line.detail)
                    // step 1 of UC-009: its Funded By or Transfer Partner, its Start Date, what was sent through it and whether it is identified.
                    Muted(listOfNotNull(line.otherSide, line.note).joinToString(" · "))
                    line.state?.let { Muted(it) }
                }
            }
        }
        // UC-009 BR-014: Not imported and Kept by hand are shown only when there is at least one (P9-06).
        if (ui.leftOut.isNotEmpty()) {
            item {
                SectionTitle(ui.leftOutTitle)
                SlussenCard {
                    ui.leftOut.forEach { line ->
                        AccountLine(line.title, null, null, null, null, trailing = null, modifier = Modifier.clickable { onService(line.service) })
                    }
                    Muted("Switch the import on again on the Data Provider's page.")
                }
            }
        }
        if (ui.kept.isNotEmpty()) {
            item {
                SectionTitle(ui.keptTitle)
                SlussenCard {
                    ui.kept.forEach { line ->
                        AccountLine(line.title, line.detail, null, null, null, modifier = Modifier.clickable { onService(ServiceKey.budgetApp) })
                    }
                    Muted("Switched off as not receiving from Slussen on the Budget App's page.")
                }
            }
        }
    }
}

/**
 * Steps 9-14 (`account-mappings.html#pair-name`, `#pair-start-date`, `#pair-bank`, `#pair-picker`, `#pair-skip`,
 * `#other-currency`, `#already-mapped`): one Bank Account at a time with its progress, the Budget Accounts of its currency
 * and the one proposed, name, type, Bank as one choice, currency and Start Date, Add and continue, Skip for now.
 */
@Composable
private fun BankAccountCard(card: CardUi, message: String?, actions: CardActions, onBack: () -> Unit) {
    val key = card.offeredBankAccountId
    var name by remember(key) { mutableStateOf(card.proposedName) }
    var type by remember(key) { mutableStateOf<BankAccountType?>(card.proposedType) }
    var bank by remember(key) { mutableStateOf<BankChoice?>(card.proposedBank?.let { BankChoice.Known(it.bankId) }) }
    var startDate by remember(key) { mutableStateOf("") }
    var currency by remember(key, card.currency) { mutableStateOf(card.currency) }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Account Mappings", "Map Bank Accounts · one at a time", onBack)
            if (message != null) Banner(message, null, SlussenColors.Surface2, SlussenColors.Text)
            SlussenCard {
                Muted(card.progress)
                AccountLine(card.title, card.detail, card.balance, null, null)
            }
            SectionTitle("Budget Account")
            SlussenCard {
                Muted(card.proposalNote)
                if (card.pickerOpen) {
                    card.picker.forEach { group ->
                        Text(group.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlussenColors.Text2)
                        group.budgetAccounts.forEach { line -> BudgetAccountLine(line, card.chosenBudgetAccountId, actions) }
                    }
                    TextButton(onClick = actions.onClosePicker) { Text("Back to the card") }
                } else {
                    Muted(card.listedHeading)
                    card.listed.forEach { line -> BudgetAccountLine(line, card.chosenBudgetAccountId, actions) }
                    TextButton(onClick = actions.onOpenPicker) { Text("All free Budget Accounts, per Budget ›") }
                }
            }
            SectionTitle("This Bank Account")
            SlussenCard {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Muted("Proposed ${card.proposedName}")
                TypeChooser(card.types, type) { type = it }
                BankChooser(card.knownBanks, bank, card.proposedBank?.bankId) { bank = it }
                Muted(card.note)
                if (card.currencyStatedBySource) {
                    Muted("Currency ${card.currency} · ${card.currencyNote}")
                } else {
                    OutlinedTextField(value = currency, onValueChange = { currency = it }, singleLine = true, label = { Text("Currency") })
                    Muted(card.currencyNote)
                }
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    singleLine = true,
                    label = { Text("Start Date · optional") },
                    placeholder = { Text("2026-09-01") },
                )
                Muted("Nothing before a Start Date is offered for Upload")
                Row {
                    Button(onClick = { actions.onAddAndContinue(name, type, bank, startDate, currency) }) { Text("Add and continue") }
                    TextButton(onClick = actions.onSkip) { Text("Skip for now") }
                }
            }
        }
    }
}

@Composable
private fun BudgetAccountLine(line: FreeBudgetAccountLine, chosen: Long?, actions: CardActions) {
    val isChosen = chosen == line.budgetAccountId
    AccountLine(
        title = (if (isChosen) "● " else "") + line.title,
        detail = line.detail + if (isChosen) " · chosen" else "",
        balance = line.balance,
        switchOn = null,
        onSwitch = null,
        modifier = if (line.choosable) Modifier.clickable { actions.onChooseBudgetAccount(line.budgetAccountId) } else Modifier,
    )
}

/** Step 15 (`account-mappings.html#pair-summary`): added and skipped for now. */
@Composable
private fun SummaryCard(summary: SummaryUi, actions: CardActions) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Account Mappings", "Map Bank Accounts · one at a time")
            SlussenCard {
                Text("Bank Accounts added: ${summary.added.size}", fontWeight = FontWeight.SemiBold)
                summary.added.forEach { Muted(it) }
                if (summary.added.isNotEmpty()) Muted("Fetched and compared from the next Fetch")
                Text("Skipped for now: ${summary.skipped.size}", fontWeight = FontWeight.SemiBold)
                summary.skipped.forEach { Muted(it) }
                Button(onClick = actions.onDone) { Text("Done") }
            }
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
