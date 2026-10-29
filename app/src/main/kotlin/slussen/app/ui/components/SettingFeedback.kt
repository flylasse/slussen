package slussen.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.theme.SlussenColors

/** How an action that ended is marked at its control (NFR-023; `settings.html#fetch-period-saved`, `connections.html#local-action-states`). */
enum class AnswerKind {
    /** Stored or accepted: the control shows the new value. */
    Done,

    /** Nothing changed: cancelled, or held back before anything was sent. */
    Unchanged,

    /** Not stored, refused or not reached: the previous value stays. */
    Failed,
}

/** The answer of an action at the control that started it (NFR-023): [text] marked as [kind], with [detail] for the next action. */
data class AnswerUi(val text: String, val kind: AnswerKind, val detail: String? = null)

/**
 * The dialog that changes one Operator Setting (UC-010 steps 2-4, A1, A10; `settings.html#fetch-period-edit`,
 * `connections.html#fetch-cooldown`): the value as typed, why a value was refused at the field (A1), and Save showing
 * that it runs before anything is stored (NFR-023).
 */
data class SettingEditorUi(
    val title: String,
    val fieldName: String,
    val value: String,
    val explanation: String,
    /** A1: what is wrong, what to enter and the value that stays; Save waits for a changed value. */
    val error: String? = null,
    val saving: Boolean = false,
    /** A10, A11: the setting can go back to the default its part declares. */
    val canReset: Boolean = false,
)

/** The answer of an action, marked, in the place the action started. */
@Composable
fun AnswerLine(answer: AnswerUi) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mark(answer.kind), color = colour(answer.kind), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(answer.text, color = colour(answer.kind), fontSize = 12.sp)
        }
        if (answer.detail != null) Muted(answer.detail)
    }
}

/** Save with its running state: "Saving…" with progress, disabled, while the value is being stored (NFR-023). */
@Composable
fun RunningText(text: String, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = SlussenColors.Blue)
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

/**
 * The dialog of [editor]: the field, the explanation, the refusal at the field, and Save, Reset to default and Cancel.
 * While the value is being stored, Save shows "Saving…" and nothing else can be tapped.
 */
@Composable
fun SettingDialog(
    editor: SettingEditorUi,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: (() -> Unit)?,
    onCancel: () -> Unit,
    notes: List<String> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = { if (!editor.saving) onCancel() },
        title = { Text(editor.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.value,
                    onValueChange = onValueChange,
                    modifier = Modifier.testTag("setting-value"),
                    singleLine = true,
                    enabled = !editor.saving,
                    isError = editor.error != null,
                    placeholder = { Text(editor.fieldName) },
                    supportingText = { Text(editor.error ?: editor.fieldName) },
                )
                Muted(editor.explanation)
                notes.forEach { Muted(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !editor.saving && editor.error == null) {
                RunningText(if (editor.saving) "Saving…" else "Save", editor.saving)
            }
        },
        dismissButton = {
            Row {
                if (onReset != null && editor.canReset) TextButton(onClick = onReset, enabled = !editor.saving) { Text("Reset to default") }
                TextButton(onClick = onCancel, enabled = !editor.saving) { Text("Cancel") }
            }
        },
    )
}

private fun mark(kind: AnswerKind): String = when (kind) {
    AnswerKind.Done -> "✓"
    AnswerKind.Unchanged -> "•"
    AnswerKind.Failed -> "!"
}

private fun colour(kind: AnswerKind): Color = when (kind) {
    AnswerKind.Done -> SlussenColors.Teal
    AnswerKind.Unchanged -> SlussenColors.Text2
    AnswerKind.Failed -> SlussenColors.Red
}
