package slussen.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerLine
import slussen.app.ui.components.AnswerUi
import slussen.app.ui.components.Muted
import slussen.app.ui.components.RunningText
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/** What the row and the dialog of Start Over do (UC-010 A12 steps 1-2). */
data class StartOverActions(
    val onOpen: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onConfirm: () -> Unit = {},
)

/** The row of Start Over (`settings.html#list-2`), its state while it runs (`#start-over-running`) and its answer (`#start-over-unavailable`, `#start-over-failed`). */
@Composable
internal fun StartOverControl(ui: StartOverUi, actions: StartOverActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(enabled = ui.enabled && ui.running == null, onClick = actions.onOpen).testTag("start-over"),
    ) {
        Text(StartOverTexts.ENTRY, color = SlussenColors.Red, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(StartOverTexts.ENTRY_HINT, color = SlussenColors.Text3, fontSize = 11.sp)
    }
    ui.running?.let {
        RunningText(it, running = true)
        Muted(StartOverTexts.NOTHING_DELETED_YET)
    }
    ui.answer?.let { AnswerLine(AnswerUi(it, AnswerKind.Unchanged)) }
}

/** The statement and its confirmation (`#start-over`), as clearing the Decision Journal asks: Cancel, or the red action that names what it does. */
@Composable
internal fun StartOverDialog(dialog: StartOverDialogUi, actions: StartOverActions) {
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = { Text(dialog.title) },
        text = { Text(dialog.text) },
        confirmButton = {
            TextButton(onClick = actions.onConfirm, modifier = Modifier.testTag("confirm-start-over")) {
                Text(dialog.confirm, color = SlussenColors.Red)
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancel) { Text("Cancel") } },
    )
}

/** The card of the new installation after Start Over (`#started-over`), with OK. */
@Composable
internal fun StartedOverCard(card: StartedOverCardUi, onClose: () -> Unit) {
    SlussenCard(border = SlussenColors.Amber) {
        Text(card.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
        Text(card.text, color = SlussenColors.Text2, fontSize = 12.sp)
        TextButton(onClick = onClose, modifier = Modifier.testTag("close-started-over")) { Text("OK") }
    }
}
