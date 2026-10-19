package slussen.app.ui.service

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.Banner
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.pluginapi.credential.FieldKind

/**
 * The page of one service (UC-014, UC-010 BR-010; `docs/mockups/connections.html`, `setup.html`): status, credentials
 * with the form its part declares, and what else belongs to it. The Request Limit of a Data Provider that publishes none
 * is changed here (UC-010 A11); the Fetch Cooldown is shown read-only until the slice that builds UC-010, the Bank
 * Consents come with UC-007.
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
    onChooseBudgets: () -> Unit,
    onEditRequestLimit: () -> Unit,
    onSaveRequestLimit: (String) -> Unit,
    onResetRequestLimit: () -> Unit,
    onCancelRequestLimit: () -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle(ui.name, ui.kind, onBack)
            Muted(ui.status)
            if (ui.blocked != null) Banner(ui.blocked, null)
            if (ui.message != null) Banner(ui.message, null, SlussenColors.Surface2, SlussenColors.Text)
            SectionTitle("Credentials")
            SlussenCard {
                Muted(ui.credentials)
                if (ui.entering) {
                    ui.fields.forEach { field -> CredentialFieldInput(field, values[field.key].orEmpty()) { values[field.key] = it } }
                    Row {
                        Button(onClick = { onSave(values.toMap()) }) { Text("Save") }
                        TextButton(onClick = {
                            values.clear()
                            onCancelEntering()
                        }) { Text("Cancel") }
                    }
                } else {
                    Row {
                        OutlinedButton(onClick = onStartEntering) { Text(if (ui.canRemove) "Replace" else "Enter credentials") }
                        if (ui.canRemove) TextButton(onClick = onRequestRemoval) { Text("Remove") }
                    }
                }
            }
            if (ui.fetchCooldown != null) {
                SectionTitle("Fetching")
                SlussenCard {
                    Text("Fetch Cooldown")
                    Muted(ui.fetchCooldown)
                    Text("Request Limit")
                    Muted(ui.requestLimit.orEmpty())
                    if (ui.requestLimitEditable) OutlinedButton(onClick = onEditRequestLimit) { Text("Change") }
                }
                SectionTitle("Bank Accounts · ${ui.bankAccounts.size}")
                SlussenCard {
                    if (ui.bankAccounts.isEmpty()) Muted("No Bank Account added yet")
                    ui.bankAccounts.forEach { Text(it) }
                }
            }
            if (ui.kind == "Budget App") {
                SectionTitle("Budgets in use · ${ui.budgetsInUse.size}")
                SlussenCard {
                    ui.budgetsInUse.forEach { Text(it) }
                    OutlinedButton(onClick = onChooseBudgets) { Text("Choose Budgets") }
                }
                if (ui.uploadAsClearedShown) {
                    SectionTitle("Upload")
                    SlussenCard {
                        Text("Upload as Cleared")
                        Muted("On by default · changing it comes with Adjust Settings")
                    }
                }
            }
        }
    }
    val editor = ui.requestLimitEditor
    if (editor != null) {
        var value by remember(editor) { mutableStateOf(editor.value) }
        AlertDialog(
            onDismissRequest = onCancelRequestLimit,
            title = { Text("Request Limit · ${ui.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        label = { Text("New value · default ${editor.defaultRequests}") },
                    )
                    Muted(editor.hint)
                    if (editor.unattendedNote != null) Muted(editor.unattendedNote)
                    if (ui.message != null) Muted(ui.message)
                }
            },
            confirmButton = { TextButton(onClick = { onSaveRequestLimit(value) }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = onResetRequestLimit) { Text("Reset to default") }
                    TextButton(onClick = onCancelRequestLimit) { Text("Cancel") }
                }
            },
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
private fun CredentialFieldInput(field: FieldUi, value: String, onValue: (String) -> Unit) {
    when (field.kind) {
        FieldKind.File -> {
            val context = LocalContext.current
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { onValue(it.readBytes().toString(Charsets.UTF_8)) }
                }
            }
            Muted(field.description)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(if (value.isEmpty()) "Choose file" else "File chosen")
            }
        }
        FieldKind.Text, FieldKind.Secret -> OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.testTag("field-${field.key}"),
            singleLine = true,
            placeholder = { Text(field.description) },
            supportingText = { Text(field.description) },
            visualTransformation = if (field.kind == FieldKind.Secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}
