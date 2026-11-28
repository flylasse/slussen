package slussen.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AnswerLine
import slussen.app.ui.components.Muted
import slussen.app.ui.components.RunningText
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.TransferDescriptionKind

/** What the Transfer Descriptions screen does when tapped (UC-010 A7, A8). */
data class DescriptionActions(
    val onAdd: (TransferDescriptionKind) -> Unit = {},
    val onEntry: (TransferDescriptionKind, String) -> Unit = { _, _ -> },
    val onChange: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onCancelChoice: () -> Unit = {},
    val onValue: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancelEditing: () -> Unit = {},
    val onRestore: (TransferDescriptionKind) -> Unit = {},
    val onConfirmRestore: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
)

/** Every action of [viewModel], as `SlussenScreens` wires them. */
fun descriptionActionsOf(viewModel: TransferDescriptionsViewModel) = DescriptionActions(
    onAdd = viewModel::startAdding,
    onEntry = viewModel::openEntry,
    onChange = viewModel::startChanging,
    onRemove = viewModel::remove,
    onCancelChoice = viewModel::cancelChoice,
    onValue = viewModel::edit,
    onSave = viewModel::save,
    onCancelEditing = viewModel::cancelEditing,
    onRestore = viewModel::askRestore,
    onConfirmRestore = viewModel::confirmRestore,
    onCancelRestore = viewModel::cancelRestore,
)

/**
 * The Transfer Descriptions (`docs/mockups/transfer-descriptions.html#lists`, `#edit`, `#restore`; UC-010 step 1, A7,
 * A8): the two lists in their order, the entries the Operator added marked, each list with Add entry and Restore the
 * default list, every answer at the list it changed (NFR-023). The entries are shown in code notation, as literal texts
 * as Banks write them.
 */
@Composable
fun TransferDescriptionsScreen(ui: TransferDescriptionsUi, onBack: () -> Unit, actions: DescriptionActions = DescriptionActions()) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Transfer Descriptions", "Which Bank Transactions Slussen proposes as Transfer Legs", onBack)
            ui.notReadAgain?.let { Muted(it) }
        }
        items(ui.lists) { list ->
            SectionTitle(list.title)
            SlussenCard {
                list.lines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { actions.onEntry(list.kind, line.text) }.padding(vertical = 6.dp).testTag(line.tag),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(line.text, color = SlussenColors.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (line.addedByYou) Text("Added by you", color = SlussenColors.Text3, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                        Text("⋯", color = SlussenColors.Text3)
                    }
                }
                list.emptyNote?.let { Muted(it) }
                list.answer?.let { AnswerLine(it) }
                Text(
                    "+ Add entry",
                    color = SlussenColors.Blue,
                    modifier = Modifier.fillMaxWidth().clickable { actions.onAdd(list.kind) }.padding(vertical = 6.dp).testTag("add-${list.kind.name}"),
                )
                Text(
                    "Restore the default list…",
                    color = SlussenColors.Blue,
                    modifier = Modifier.fillMaxWidth().clickable { actions.onRestore(list.kind) }.padding(vertical = 6.dp).testTag("restore-${list.kind.name}"),
                )
            }
        }
        item { Muted(ui.note) }
    }
    ui.choice?.let { choice ->
        AlertDialog(
            onDismissRequest = { if (!choice.removing) actions.onCancelChoice() },
            title = { Text(choice.entry, fontFamily = FontFamily.Monospace) },
            text = { Muted("Change the text of this entry, or remove it from the ${DescriptionTexts.listName(choice.kind)}.") },
            confirmButton = {
                Row {
                    TextButton(onClick = actions.onChange, enabled = !choice.removing) { Text("Change…") }
                    TextButton(onClick = actions.onRemove, enabled = !choice.removing, modifier = Modifier.testTag("remove-entry")) {
                        RunningText(if (choice.removing) "Removing…" else "Remove", choice.removing)
                    }
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelChoice, enabled = !choice.removing) { Text("Cancel") } },
        )
    }
    ui.editor?.let { editor ->
        AlertDialog(
            onDismissRequest = { if (!editor.saving) actions.onCancelEditing() },
            title = { Text(editor.title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editor.value,
                        onValueChange = actions.onValue,
                        modifier = Modifier.testTag("description-value"),
                        singleLine = true,
                        enabled = !editor.saving,
                        isError = editor.error != null,
                        placeholder = { Text("Text") },
                        supportingText = { Text(editor.error ?: DescriptionTexts.FIELD) },
                    )
                    Muted(DescriptionTexts.NOTE)
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onSave, enabled = !editor.saving && editor.error == null, modifier = Modifier.testTag("save-description")) {
                    RunningText(if (editor.saving) "Saving…" else "Save", editor.saving)
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelEditing, enabled = !editor.saving) { Text("Cancel") } },
        )
    }
    ui.restore?.let { restore ->
        AlertDialog(
            onDismissRequest = { if (!restore.saving) actions.onCancelRestore() },
            title = { Text(restore.title) },
            text = {
                Column {
                    Muted(restore.text)
                    Text(restore.defaults, color = SlussenColors.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmRestore, enabled = !restore.saving, modifier = Modifier.testTag("confirm-restore")) {
                    RunningText(if (restore.saving) "Restoring…" else "Restore", restore.saving)
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelRestore, enabled = !restore.saving) { Text("Cancel") } },
        )
    }
}
