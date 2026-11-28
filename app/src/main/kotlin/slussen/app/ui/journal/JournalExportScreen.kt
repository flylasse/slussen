package slussen.app.ui.journal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AnswerLine
import slussen.app.ui.components.Muted
import slussen.app.ui.components.RunningText
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * Export the Decision Journal (`docs/mockups/journal-export.html`; UC-011): the codes switch and Choose where to save…
 * (`#choose`), the export's state at that control from the tap on (`#writing`), and its answer there: complete with the
 * number of entries and the latest ones (`#complete`), from the stored copy (`#stored-copy`), no place chosen
 * (`#cancelled`), failed (`#failed`), and nothing to export yet (`#empty`). [onChoose] opens Android's save dialog.
 */
@Composable
fun JournalExportScreen(
    ui: JournalExportUi,
    onBack: () -> Unit,
    onCodes: (Boolean) -> Unit,
    onChoose: () -> Unit,
    onWithdraw: () -> Unit = {},
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
        ScreenTitle(JournalExportTexts.TITLE, onBack = onBack)
        SlussenCard {
            Text(JournalExportTexts.HEADING, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Muted(ui.intro)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(JournalExportTexts.CODES, color = SlussenColors.Text, fontSize = 13.sp)
                    Text(JournalExportTexts.CODES_HINT, color = SlussenColors.Text3, fontSize = 11.sp)
                }
                Switch(
                    checked = ui.codes,
                    onCheckedChange = onCodes,
                    enabled = ui.writing == null,
                    modifier = Modifier.testTag("export-codes"),
                )
            }
            ui.nothingToExport?.let { AnswerLine(it) }
            OutlinedButton(onClick = onChoose, enabled = ui.chooseEnabled, modifier = Modifier.testTag("export-choose")) {
                RunningText(ui.writing?.text ?: JournalExportTexts.CHOOSE, running = ui.writing != null)
            }
            ui.writing?.let { Muted(it.place) }
            // ADR-007 §1: an export that waits for the lane behind another operation can be withdrawn.
            if (ui.canWithdraw) {
                TextButton(onClick = onWithdraw, modifier = Modifier.testTag("export-withdraw")) { Text(JournalExportTexts.WITHDRAW) }
            }
            ui.answer?.let { AnswerLine(it) }
        }
        if (ui.latest.isNotEmpty()) {
            SectionTitle(JournalExportTexts.LATEST)
            SlussenCard { ui.latest.forEach { Text(it, color = SlussenColors.Text2, fontSize = 12.sp) } }
        }
    }
}
