package slussen.app.ui.consent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.Muted
import slussen.app.ui.components.RunningText
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.pluginapi.provider.BankChoice

/** What the Operator does in the Bank Consents part of a Data Provider's page (UC-007). */
class BankConsentActions(
    val onGrant: () -> Unit,
    val onRenew: (Long) -> Unit,
    val onRevoke: (Long) -> Unit,
    val onConfirmRevoke: () -> Unit,
    val onCancelRevoke: () -> Unit,
    val onCountries: () -> Unit,
    val onCountry: (String) -> Unit,
    val onCancelCountries: () -> Unit,
    val onSearch: (String) -> Unit,
    val onBank: (BankChoice) -> Unit,
    val onCancelChooser: () -> Unit,
    val onOpenMyBank: () -> Unit,
    val onCancelSheet: () -> Unit,
    val onPaste: (String) -> Unit,
    val onUsePasted: () -> Unit,
    val onCancelWaiting: (Long) -> Unit,
    val onAct: (ConsentResultUi, ResultAction) -> Unit,
    val onAccountMappings: () -> Unit,
)

fun bankConsentActionsOf(vm: BankConsentsViewModel, onAccountMappings: () -> Unit) = BankConsentActions(
    onGrant = vm::openChooser,
    onRenew = vm::renew,
    onRevoke = vm::requestRevoke,
    onConfirmRevoke = vm::confirmRevoke,
    onCancelRevoke = vm::cancelRevoke,
    onCountries = vm::openCountries,
    onCountry = vm::chooseCountry,
    onCancelCountries = vm::cancelCountries,
    onSearch = vm::search,
    onBank = vm::chooseBank,
    onCancelChooser = vm::cancelChooser,
    onOpenMyBank = vm::openMyBank,
    onCancelSheet = vm::cancelSheet,
    onPaste = vm::paste,
    onUsePasted = vm::usePasted,
    onCancelWaiting = vm::cancelWaiting,
    onAct = vm::act,
    onAccountMappings = onAccountMappings,
)

/**
 * The Bank Consents of a Data Provider on its page (UC-007 BR-008, BR-011; `docs/mockups/consent.html`): the return
 * address to register with Copy, each Bank Consent with Renew and Revoke, the chooser of the Bank, the sheet with Open
 * my Bank, the Consent Requests that wait with the paste field and Cancel, and each answer at the control it follows
 * (NFR-023). The address of a returned answer is never shown.
 */
@Composable
fun BankConsentsSection(ui: BankConsentsUi, actions: BankConsentActions) {
    val clipboard = LocalClipboardManager.current
    SectionTitle(ui.heading)
    ui.result?.let { ResultCard(it, actions, onCopy = { address -> clipboard.setText(AnnotatedString(address)) }) }
    ui.completing?.let { RunningText(it, running = true) }
    ui.waiting.forEach { waiting -> WaitingCard(waiting, ui.completing != null, actions) }
    SlussenCard {
        ui.unavailable?.let { Muted(it) }
        ui.emptyText?.let { Text(it, color = SlussenColors.Text, fontSize = 13.sp) }
        ui.lines.forEach { line -> ConsentLine(line, actions) }
        // UC-007 BR-011: the exact address Slussen sends, to register with the Data Provider, with the action that copies it.
        Text(ui.returnAddressTitle, color = SlussenColors.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Row {
            Text(ui.returnAddress, color = SlussenColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f).testTag("return-address"))
            TextButton(onClick = { clipboard.setText(AnnotatedString(ui.returnAddress)) }) { Text("Copy") }
        }
        ui.grantAction?.let { action -> OutlinedButton(onClick = actions.onGrant, modifier = Modifier.testTag("grant-bank-consent")) { Text(action) } }
    }
    ui.chooser?.let { ChooserCard(it, actions) }
    ui.sheet?.let { SheetCard(it, actions, onCopy = { address -> clipboard.setText(AnnotatedString(address)) }) }
    ui.revokeQuestion?.let { question ->
        AlertDialog(
            onDismissRequest = actions.onCancelRevoke,
            title = { Text(question.title) },
            text = {
                Column {
                    Text(question.text)
                    Muted(question.kept)
                }
            },
            confirmButton = { TextButton(onClick = actions.onConfirmRevoke) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = actions.onCancelRevoke) { Text("Cancel") } },
        )
    }
    ui.chooser?.countries?.let { countries ->
        AlertDialog(
            onDismissRequest = actions.onCancelCountries,
            title = { Text("Country") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(countries) { country ->
                        Text(
                            "${country.code} · ${country.name}",
                            modifier = Modifier.clickable { actions.onCountry(country.code) }.padding(vertical = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = actions.onCancelCountries) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConsentLine(line: ConsentLineUi, actions: BankConsentActions) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(line.bankName, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(line.status, color = if (line.status.startsWith("Bank Consent expired")) SlussenColors.Amber else SlussenColors.Text2, fontSize = 12.sp)
        if (line.detail.isNotEmpty()) Muted(line.detail)
        line.answer?.let { answer -> answer.lines.forEach { Text(it, color = colourOf(answer.kind), fontSize = 12.sp) } }
        Row {
            line.renewAction?.let { action -> TextButton(onClick = { actions.onRenew(line.bankId) }, enabled = !line.revoking) { Text(action) } }
            if (line.canRevoke) {
                TextButton(onClick = { actions.onRevoke(line.bankId) }, enabled = !line.revoking) {
                    RunningText(if (line.revoking) "Revoking…" else "Revoke", line.revoking)
                }
            }
        }
    }
}

@Composable
private fun ChooserCard(chooser: ChooserUi, actions: BankConsentActions) {
    SlussenCard(modifier = Modifier.testTag("choose-bank"), border = SlussenColors.Teal) {
        Text(chooser.title, color = SlussenColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Muted(chooser.subtitle)
        Row {
            Text("Country", color = SlussenColors.Text2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onCountries) { Text("${chooser.country} ›") }
        }
        OutlinedTextField(
            value = chooser.search,
            onValueChange = actions.onSearch,
            modifier = Modifier.testTag("bank-search"),
            singleLine = true,
            label = { Text("Search by name") },
        )
        chooser.listing?.let { RunningText(it, running = true) }
        chooser.answer?.let { ResultCard(it, actions, onCopy = {}) }
        chooser.banks.forEach { bank ->
            Text(bank.name, color = SlussenColors.Teal, fontSize = 14.sp, modifier = Modifier.clickable { actions.onBank(bank) }.padding(vertical = 6.dp))
        }
        chooser.note?.let { Muted(it) }
        TextButton(onClick = actions.onCancelChooser) { Text("Cancel") }
    }
}

@Composable
private fun SheetCard(sheet: SheetUi, actions: BankConsentActions, onCopy: (String) -> Unit) {
    SlussenCard(modifier = Modifier.testTag("consent-sheet"), border = SlussenColors.Teal) {
        Text(sheet.title, color = SlussenColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Muted(sheet.subtitle)
        sheet.steps.forEach { Text(it, color = SlussenColors.Text, fontSize = 13.sp) }
        sheet.note?.let { Text(it, color = SlussenColors.Amber, fontSize = 12.sp) }
        // NFR-023: Open my Bank shows that it runs before anything is sent; its answer is below it.
        Row {
            Button(onClick = actions.onOpenMyBank, enabled = !sheet.opening) { RunningText(sheet.openAction, sheet.opening) }
            TextButton(onClick = actions.onCancelSheet, enabled = !sheet.opening) { Text("Cancel") }
        }
        sheet.answer?.let { ResultCard(it, actions, onCopy) }
    }
}

@Composable
private fun WaitingCard(waiting: WaitingCardUi, completing: Boolean, actions: BankConsentActions) {
    SlussenCard(modifier = Modifier.testTag("consent-waiting"), border = SlussenColors.Teal) {
        Text(waiting.title, color = SlussenColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        RunningText(waiting.until, running = true)
        waiting.steps.forEach { Text(it, color = SlussenColors.Text, fontSize = 13.sp) }
        Muted(waiting.pasteNote)
        // UC-007 BR-011: the field into which the Operator pastes; Slussen never reads the clipboard itself, and the field is not saved.
        OutlinedTextField(
            value = waiting.pasted.text,
            onValueChange = actions.onPaste,
            modifier = Modifier.testTag("paste-address"),
            singleLine = true,
            label = { Text("Paste the address") },
            placeholder = { Text(waiting.pastePlaceholder) },
        )
        Row {
            Button(onClick = actions.onUsePasted, enabled = !completing && waiting.pasted.text.isNotBlank()) { Text("Use this address") }
            TextButton(onClick = { actions.onCancelWaiting(waiting.requestId) }, enabled = !waiting.cancelling) {
                RunningText(if (waiting.cancelling) "Cancelling…" else "Cancel", waiting.cancelling)
            }
        }
    }
}

@Composable
private fun ResultCard(result: ConsentResultUi, actions: BankConsentActions, onCopy: (String) -> Unit) {
    SlussenCard(modifier = Modifier.testTag("consent-answer"), border = colourOf(result.kind)) {
        Text(result.title, color = colourOf(result.kind), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        result.lines.forEach { Text(it, color = SlussenColors.Text, fontSize = 12.sp) }
        Row {
            result.actions.forEach { action ->
                TextButton(
                    onClick = {
                        when (action) {
                            ResultAction.CopyAddress -> result.address?.let(onCopy)
                            ResultAction.AccountMappings -> actions.onAccountMappings()
                            else -> actions.onAct(result, action)
                        }
                    },
                ) { Text(textOf(action)) }
            }
        }
    }
}

private fun textOf(action: ResultAction): String = when (action) {
    ResultAction.Done -> "Done"
    ResultAction.TryAgain -> "Try again"
    ResultAction.StartAgain -> "Start again"
    ResultAction.CopyAddress -> "Copy the address"
    ResultAction.AccountMappings -> "Account Mappings"
    ResultAction.AnotherBank -> "Another Bank"
}

private fun colourOf(kind: AnswerKind) = when (kind) {
    AnswerKind.Done -> SlussenColors.Green
    AnswerKind.Unchanged -> SlussenColors.Text2
    AnswerKind.Failed -> SlussenColors.Amber
}
