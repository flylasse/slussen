package slussen.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import slussen.app.ui.components.SettingDialog
import slussen.app.ui.components.RunningText
import slussen.core.port.NumberSetting
import slussen.app.ui.components.NavigationLine
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/** What the Operator Settings on Settings do when tapped (UC-010 steps 2-4, A1, A5, A6). */
data class SettingsActions(
    val onEdit: (NumberSetting) -> Unit = {},
    val onValue: (String) -> Unit = {},
    val onSave: (String) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onTapDetailedLogging: () -> Unit = {},
    val onConfirmDetailedLogging: () -> Unit = {},
    val onCancelDetailedLogging: () -> Unit = {},
)

/**
 * Settings (`docs/mockups/settings.html#first-run`, `#list`, `#list-2`, `#to-map`): at its top, with no level between,
 * the services and File Formats of this build, each with its status and opening its page, then the Account Mappings,
 * available or with why not yet (UC-014 step 1, BR-007, UC-010 BR-010, UC-013 step 1, BR-008, BR-015); Recent Fetches;
 * the Operator Settings that apply to everything in the sections of the mockup, each changed in its dialog or at its
 * switch and answering at its own line (UC-010 steps 1-6, A1, A5, A6, NFR-023; `#out-of-range`, `#saved`,
 * `#saved-read-owed`, `#detailed-logging`, `#fetch-schedule`, `#fetch-period-edit` to `#fetch-period-failed`); and the
 * version of the app in "This app" (UC-010 step 1, BR-011).
 */
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    onEntry: (EntryPage) -> Unit,
    onAccountMappings: () -> Unit,
    onRecentFetches: () -> Unit,
    actions: SettingsActions = SettingsActions(),
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Settings", "Operator Settings · stored on this device and in its backups")
            SectionTitle("Services and Account Mappings")
        }
        items(ui.entries) { entry ->
            SlussenCard(modifier = Modifier.clickable { onEntry(entry.page) }) { EntryItem(entry.name, entry.kind, entry.status, entry.isNext) }
        }
        item {
            val mappings = ui.accountMappings
            SlussenCard(modifier = if (mappings.available) Modifier.clickable(onClick = onAccountMappings) else Modifier) {
                EntryItem("Account Mappings", "Bank Accounts → Budget Accounts", mappings.status, mappings.isNext, enabled = mappings.available)
            }
            SectionTitle("Fetches")
            SlussenCard { NavigationLine("Recent Fetches", "Source, time, who started it, outcome", onRecentFetches) }
            ui.notReadAgain?.let { Muted(it) }
        }
        items(ui.sections) { section ->
            SectionTitle(section.title)
            SlussenCard {
                section.items.forEach { item ->
                    when (item) {
                        is SettingItemUi.Number -> {
                            val line = item.line
                            SettingLine(
                                line.name, line.hint, line.value,
                                Modifier.clickable { actions.onEdit(line.setting) }.testTag(line.tag),
                            )
                            line.answer?.let { AnswerLine(it) }
                        }
                        is SettingItemUi.Switch -> {
                            val line = item.line
                            SettingLine(
                                line.name, line.hint, line.pending ?: if (line.on) "On" else "Off",
                                Modifier.clickable(enabled = line.pending == null, onClick = actions.onTapDetailedLogging).testTag(line.tag),
                            )
                            line.answer?.let { AnswerLine(it) }
                        }
                        is SettingItemUi.Shown -> SettingLine(item.line.name, item.line.hint, item.line.value)
                    }
                }
                section.note?.let { Muted(it) }
            }
        }
        item {
            SectionTitle("This app")
            SlussenCard {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Version", color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
                        Muted("The version name of this build; a debug build says so")
                    }
                    Text(ui.version, color = SlussenColors.Text)
                }
            }
        }
    }
    val open = ui.numberEditor
    if (open != null) {
        SettingDialog(
            editor = open.editor,
            onValueChange = actions.onValue,
            onSave = { actions.onSave(open.editor.value) },
            onReset = null,
            onCancel = actions.onCancel,
        )
    }
    val statement = ui.detailedLoggingStatement
    if (statement != null) {
        AlertDialog(
            onDismissRequest = { if (!statement.saving) actions.onCancelDetailedLogging() },
            title = { Text(statement.title) },
            text = { Text(statement.text) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmDetailedLogging, enabled = !statement.saving, modifier = Modifier.testTag("confirm-detailed-logging")) {
                    RunningText(statement.confirm, statement.saving)
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelDetailedLogging, enabled = !statement.saving) { Text("Cancel") } },
        )
    }
}

/** One Operator Setting: its name and what it is on the left, its value right-aligned. */
@Composable
private fun SettingLine(name: String, hint: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(hint, color = SlussenColors.Text3, fontSize = 11.sp)
        }
        Text(value, color = SlussenColors.Teal, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** One entry: its name and kind on the left, its status right-aligned, highlighted when it is what comes next. */
@Composable
private fun EntryItem(name: String, kind: String, status: String, isNext: Boolean, enabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = if (enabled) SlussenColors.Text else SlussenColors.Text3, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(kind, color = SlussenColors.Text2, fontSize = 11.sp)
        }
        Text(
            status,
            color = if (isNext) SlussenColors.Teal else SlussenColors.Text2,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
        )
        if (enabled) Text(" ›", color = SlussenColors.Text3, fontSize = 14.sp)
    }
}
