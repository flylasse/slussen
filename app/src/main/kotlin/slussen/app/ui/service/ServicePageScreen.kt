package slussen.app.ui.service

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AccountLine
import slussen.app.ui.components.AnswerLine
import slussen.app.ui.components.RunningText
import slussen.app.ui.components.SettingDialog
import slussen.app.ui.components.SettingEditorUi
import slussen.app.ui.components.Banner
import slussen.app.ui.components.FetchButton
import slussen.app.ui.components.FetchResultLine
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.pluginapi.credential.FieldKind

/** What the Operator does with the Budgets, the Budget Accounts and the Offered Bank Accounts on a service's page (UC-013). */
class ConnectActions(
    val onChooseBudgets: () -> Unit,
    val onToggleBudget: (String) -> Unit,
    val onServeBudgets: () -> Unit,
    val onCancelChoosing: () -> Unit,
    val onReceives: (Long, Boolean) -> Unit,
    val onSwitchImport: (OfferedLineUi, Boolean) -> Unit,
    val onUndo: () -> Unit,
    val onMap: () -> Unit,
    val onDismissSnack: () -> Unit,
)

/**
 * The Fetch Cooldown and the Request Limit on a Data Provider's page, each changed in its dialog (UC-010 steps 2-6, A1,
 * A10, A11; `connections.html#fetch-cooldown`, `#request-limit`).
 */
class PageSettingActions(
    val onEditFetchCooldown: () -> Unit,
    val onFetchCooldownValue: (String) -> Unit,
    val onSaveFetchCooldown: (String) -> Unit,
    val onResetFetchCooldown: () -> Unit,
    val onCancelFetchCooldown: () -> Unit,
    val onRequestLimitValue: (String) -> Unit,
)

/**
 * The page of one service (UC-014, UC-010 BR-010, UC-013; `docs/mockups/connections.html`): status, credentials with the
 * form its part declares, and what else belongs to it. For the Budget App the Budgets in use, their choice and, under
 * each, its Budget Accounts with "Receives from Slussen" (UC-013 steps 2-4, A9); for a Data Provider its Fetch (UC-001,
 * A16 with its confirmation of a warning), its Fetch Cooldown and Request Limit (UC-010 A11) and its Offered Bank Accounts
 * with an import switch each (UC-013 steps 5-7, A3, A6, A10, A11). The Bank Consents come with UC-007. Every action
 * answers at its own control (NFR-023, UX-D4): the check of credentials at Save (the checking and check-refused states
 * of a Data Provider's page), a refusal at a Fetch on the page (its refused state), a change of the Fetch Cooldown or
 * the Request Limit at its line (`#fetch-cooldown-saved`, `#local-action-states`).
 */
@Composable
fun ServicePageScreen(
    ui: ServicePageUi,
    onBack: () -> Unit,
    onStartEntering: () -> Unit,
    onCancelEntering: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onRequestRemoval: () -> Unit,
    onCancelRemoval: () -> Unit,
    onConfirmRemoval: () -> Unit,
    connect: ConnectActions,
    onEditRequestLimit: () -> Unit,
    onSaveRequestLimit: (String) -> Unit,
    onResetRequestLimit: () -> Unit,
    onCancelRequestLimit: () -> Unit,
    settings: PageSettingActions,
    onFetch: () -> Unit,
    onConfirmFetch: () -> Unit,
    onCancelFetch: () -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.name, ui.kind, onBack)
            Muted(ui.status)
            if (ui.blocked != null) Banner(ui.blocked, ui.blockedDetail)
            if (ui.message != null) Banner(ui.message, null, SlussenColors.Surface2, SlussenColors.Text)
            SectionTitle("Credentials")
            SlussenCard {
                Muted(ui.credentials)
                if (ui.entering) {
                    ui.fields.forEach { field -> CredentialFieldInput(field, values[field.key].orEmpty(), !ui.checking) { values[field.key] = it } }
                    // A1 of UC-014: the answer of the check, at the field it answers.
                    ui.credentialAnswer?.let { AnswerLine(it) }
                    Row {
                        // NFR-023: Save shows that the service is being asked, before any answer; Cancel waits.
                        Button(onClick = { onSave(values.toMap()) }, enabled = !ui.checking) {
                            RunningText(if (ui.checking) "Checking with ${ui.name}…" else ui.saveAction, ui.checking)
                        }
                        TextButton(onClick = {
                            values.clear()
                            onCancelEntering()
                        }, enabled = !ui.checking) { Text("Cancel") }
                    }
                    ui.earlierCredentialsNote?.let { Muted(it) }
                } else {
                    ui.credentialAnswer?.let { AnswerLine(it) }
                    Row {
                        OutlinedButton(onClick = onStartEntering) { Text(ui.enterAction) }
                        if (ui.canRemove) TextButton(onClick = onRequestRemoval) { Text("Remove") }
                    }
                }
            }
            val snack = ui.snack
            if (snack != null) SnackLine(snack, connect)
            if (ui.fetchCooldown != null) {
                SectionTitle("Fetch")
                SlussenCard {
                    val fetchButton = ui.fetchButton
                    if (fetchButton != null) {
                        // P9-16: the button shows the Fetch while it runs, and the result of each tap below it.
                        FetchButton(fetchButton, onFetch)
                        if (ui.canFetch) Muted("Starts a Fetch of this Data Provider, with you present")
                        ui.fetchResult?.let { FetchResultLine(it) }
                    }
                    // P9-13: the Fetch is shown unavailable, with the reason in plain words.
                    if (ui.fetchUnavailable != null) Muted(ui.fetchUnavailable)
                    // NFR-023 (UX-22): each setting's line shows its saved value and the answer of its last change.
                    Text("Fetch Cooldown", fontSize = 13.sp)
                    Muted(ui.fetchCooldown)
                    ui.fetchCooldownAnswer?.let { AnswerLine(it) }
                    if (ui.fetchCooldownEditable) {
                        OutlinedButton(onClick = settings.onEditFetchCooldown, modifier = Modifier.testTag("change-fetch-cooldown")) { Text("Change") }
                    }
                    Text("Request Limit", fontSize = 13.sp)
                    Muted(ui.requestLimit.orEmpty())
                    ui.requestLimitAnswer?.let { AnswerLine(it) }
                    if (ui.requestLimitEditable) {
                        OutlinedButton(onClick = onEditRequestLimit, modifier = Modifier.testTag("change-request-limit")) { Text("Change") }
                    }
                }
            }
            ui.offered?.let { offered -> OfferedSection(offered, connect) }
            ui.budgets?.let { budgets -> BudgetsSection(budgets, connect) }
            if (ui.uploadAsClearedShown) {
                SectionTitle("Upload")
                SlussenCard {
                    Text("Upload as Cleared", fontSize = 13.sp)
                    Muted("On by default · changing it comes with Adjust Settings")
                }
            }
        }
    }
    val editor = ui.requestLimitEditor
    if (editor != null) {
        SettingDialog(
            editor = SettingEditorUi(
                title = "Request Limit · ${ui.name}",
                fieldName = "New value · default ${editor.defaultRequests}",
                value = editor.value,
                explanation = editor.hint,
                error = editor.error,
                saving = editor.saving,
                canReset = true,
            ),
            onValueChange = settings.onRequestLimitValue,
            onSave = { onSaveRequestLimit(editor.value) },
            onReset = onResetRequestLimit,
            onCancel = onCancelRequestLimit,
            notes = listOfNotNull(editor.unattendedNote),
        )
    }
    val cooldownEditor = ui.fetchCooldownEditor
    if (cooldownEditor != null) {
        SettingDialog(
            editor = cooldownEditor,
            onValueChange = settings.onFetchCooldownValue,
            onSave = { settings.onSaveFetchCooldown(cooldownEditor.value) },
            onReset = settings.onResetFetchCooldown,
            onCancel = settings.onCancelFetchCooldown,
        )
    }
    val warning = ui.fetchWarning
    if (warning != null) {
        AlertDialog(
            onDismissRequest = onCancelFetch,
            title = { Text(warning.title) },
            text = {
                Column {
                    Text(warning.text)
                    Muted(warning.risk)
                }
            },
            confirmButton = { TextButton(onClick = onConfirmFetch) { Text("Fetch") } },
            dismissButton = { TextButton(onClick = onCancelFetch) { Text("Cancel") } },
        )
    }
    if (ui.removalQuestion != null) {
        AlertDialog(
            onDismissRequest = onCancelRemoval,
            title = { Text("Remove credentials?") },
            text = { Text(ui.removalQuestion) },
            confirmButton = { TextButton(onClick = onConfirmRemoval) { Text("Remove") } },
            dismissButton = { TextButton(onClick = onCancelRemoval) { Text("Cancel") } },
        )
    }
}

/** One declared field: text, secret (hidden while typed) or a file chosen with the device's file picker (UC-014 BR-002). */
@Composable
private fun CredentialFieldInput(field: FieldUi, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    when (field.kind) {
        FieldKind.File -> {
            val context = LocalContext.current
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { onValue(it.readBytes().toString(Charsets.UTF_8)) }
                }
            }
            Muted(field.description)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled) {
                Text(if (value.isEmpty()) "Choose file" else "File chosen")
            }
        }
        FieldKind.Text, FieldKind.Secret -> OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.testTag("field-${field.key}"),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(field.description) },
            supportingText = { Text(field.description) },
            visualTransformation = if (field.kind == FieldKind.Secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

/** What the last switch did, with Undo or Map (`connections.html#switch-off`, `#switch-on`, `#kept-switch`). */
@Composable
private fun SnackLine(snack: SnackUi, connect: ConnectActions) {
    SlussenCard(border = SlussenColors.Teal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(snack.text, color = SlussenColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            when (snack.action) {
                SnackAction.Undo -> TextButton(onClick = connect.onUndo) { Text("Undo") }
                SnackAction.Map -> TextButton(onClick = connect.onMap) { Text("Map") }
                null -> TextButton(onClick = connect.onDismissSnack) { Text("OK") }
            }
        }
    }
}

/** UC-013 steps 5-7, A3, A6, A10, A11 (`connections.html`: a Data Provider's page, `#first-fetch`, `#consent-first`, `#nothing-new`). */
@Composable
private fun OfferedSection(offered: OfferedSectionUi, connect: ConnectActions) {
    SectionTitle(offered.heading)
    if (offered.nothingNew != null) Banner(offered.nothingNew, null, SlussenColors.Surface2, SlussenColors.Text)
    if (offered.emptyText != null) SlussenCard { Muted(offered.emptyText) }
    offered.banks.forEach { bank ->
        if (bank.name.isNotEmpty()) Text(bank.name, color = SlussenColors.Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        SlussenCard { bank.rows.forEach { line -> OfferedLine(line, connect) } }
    }
    if (offered.notDelivered.isNotEmpty()) {
        Text("Not in the latest delivery", color = SlussenColors.Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        SlussenCard { offered.notDelivered.forEach { line -> OfferedLine(line, connect) } }
    }
    if (offered.footnote != null) Muted(offered.footnote)
}

@Composable
private fun OfferedLine(line: OfferedLineUi, connect: ConnectActions) {
    AccountLine(
        title = line.title,
        detail = line.detail,
        balance = line.balance,
        switchOn = line.importOn,
        onSwitch = line.importOn?.let { { on: Boolean -> connect.onSwitchImport(line, on) } },
        trailing = line.trailing,
        switchTag = "import-${line.offeredBankAccountId ?: line.leftOutBankAccountId}",
    )
}

/** UC-013 steps 2-4, A2, A7, A9 (`connections.html`: the Budget App's page, `#choose-budgets-first`, `#kept-switch`). */
@Composable
private fun BudgetsSection(budgets: BudgetsSectionUi, connect: ConnectActions) {
    val choosing = budgets.choosing
    if (choosing != null) {
        SectionTitle("Choose the Budgets Slussen serves")
        SlussenCard {
            choosing.forEach { budget ->
                Row(modifier = Modifier.clickable { connect.onToggleBudget(budget.reference) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = budget.chosen, onCheckedChange = { connect.onToggleBudget(budget.reference) })
                    Text("${budget.name} · ${budget.currency}", fontSize = 13.sp)
                }
            }
            Row {
                Button(onClick = connect.onServeBudgets) { Text("Serve the ticked Budgets") }
                TextButton(onClick = connect.onCancelChoosing) { Text("Cancel") }
            }
        }
        return
    }
    SectionTitle("Budgets in use · ${budgets.inUse.size}")
    SlussenCard {
        if (budgets.inUse.isNotEmpty()) Text(budgets.inUse.joinToString(", "), fontSize = 13.sp)
        if (budgets.note != null) Muted(budgets.note)
        OutlinedButton(onClick = connect.onChooseBudgets, enabled = budgets.note == null) { Text("Choose Budgets") }
    }
    if (budgets.budgetAccounts.isNotEmpty()) {
        SectionTitle("Budget Accounts · switch off the ones you keep by hand")
        budgets.budgetAccounts.forEach { group ->
            Text(group.title, color = SlussenColors.Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            SlussenCard {
                group.lines.forEach { line ->
                    AccountLine(
                        title = line.name,
                        detail = line.detail,
                        balance = line.balance,
                        switchOn = line.receives,
                        onSwitch = line.receives?.let { { on: Boolean -> connect.onReceives(line.budgetAccountId, on) } },
                        trailing = if (line.receives == null) "Account Mapping" else null,
                        switchTag = "receives-${line.budgetAccountId}",
                    )
                }
            }
        }
    }
}
