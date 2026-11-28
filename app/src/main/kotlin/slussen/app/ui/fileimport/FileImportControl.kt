package slussen.app.ui.fileimport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AnswerLine
import slussen.app.ui.components.Muted
import slussen.app.ui.components.RunningText
import slussen.app.ui.theme.SlussenColors

/** What the control of Import a Transaction File does when tapped, wired once by `SlussenScreens`. */
class FileImportActions(
    val onImport: () -> Unit,
    val onChoose: (ImportTarget) -> Unit,
    val onCancelChoice: () -> Unit,
    val onWithdraw: () -> Unit,
    val onAccountMappings: () -> Unit,
)

/**
 * The control of Import a Transaction File at one place (`docs/mockups/import-file.html`): the button, which from the tap
 * on shows the step that runs (`#choose`, reading, waiting with Withdraw, importing), the choice of the Bank Account when
 * the File Format has two or more in the Budget (S-B-34), and the answer in the same place: imported with the Bank
 * Transactions taken in and their Transaction Statuses (`#imported`, `#told-apart`), cancelled (`#cancel`), rejected
 * (`#rejected`, `#no-importable`, `#collision`), a Retired Account with the way to the Account Mappings (`#retired`), or
 * a failure (NFR-023). [showButton] false shows only the state and the answer of an import started there.
 */
@Composable
fun FileImportControl(control: FileImportControlUi, actions: FileImportActions, showButton: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (showButton) {
            Button(onClick = actions.onImport, enabled = control.enabled, modifier = Modifier.testTag("file-import")) {
                RunningText(control.label, running = control.running)
            }
        } else if (control.running) {
            RunningText(control.label, running = true)
        }
        control.forBankAccount?.let { Muted(it) }
        control.choice?.let { choice ->
            Text(FileImportTexts.WHICH_BANK_ACCOUNT, color = SlussenColors.Text2, fontSize = 12.sp)
            choice.forEach { bankAccount ->
                OutlinedButton(onClick = { actions.onChoose(bankAccount) }, modifier = Modifier.testTag("file-import-for-${bankAccount.bankAccountId}")) {
                    Text(bankAccount.name)
                }
            }
            TextButton(onClick = actions.onCancelChoice) { Text(FileImportTexts.CANCEL) }
        }
        // ADR-007 §1: an import that waits for the lane behind another operation can be withdrawn.
        if (control.canWithdraw) {
            TextButton(onClick = actions.onWithdraw, modifier = Modifier.testTag("file-import-withdraw")) { Text(FileImportTexts.WITHDRAW) }
        }
        control.answer?.let { AnswerLine(it) }
        // A5 of UC-002: a Retired Account must be restored first, in the Account Mappings (UC-009).
        if (control.toAccountMappings) {
            TextButton(onClick = actions.onAccountMappings) { Text(FileImportTexts.ACCOUNT_MAPPINGS) }
        }
        control.lines.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(line.day, color = SlussenColors.Text3, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Text(line.text, color = SlussenColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(line.amount, color = SlussenColors.Text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                Text(line.status, color = SlussenColors.Text2, fontSize = 12.sp)
            }
        }
    }
}
