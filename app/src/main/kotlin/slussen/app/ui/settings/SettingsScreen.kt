package slussen.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
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
import slussen.app.ui.components.NavigationLine
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors

/**
 * Settings (`docs/mockups/settings.html#first-run`, `#list`, `#to-map`, `#list-this-build`): at its top, with no level
 * between, the services and File Formats of this build, each with its status and opening its page, then the Account
 * Mappings, available or with why not yet (UC-014 step 1, BR-007, UC-010 BR-010, UC-013 step 1, BR-008, BR-015); Recent
 * Fetches; the fetch period, changed in its dialog and answering at its own line (UC-010 steps 1-4, 6, A1, NFR-023;
 * `#fetch-period-edit` to `#fetch-period-failed`), and the other Operator Settings with the values that apply; and the
 * version of the app in "This app" (UC-010 step 1, BR-011; `settings.html#list-2`).
 */
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    onEntry: (EntryPage) -> Unit,
    onAccountMappings: () -> Unit,
    onRecentFetches: () -> Unit,
    onEditFetchPeriod: () -> Unit = {},
    onFetchPeriodValue: (String) -> Unit = {},
    onSaveFetchPeriod: (String) -> Unit = {},
    onCancelFetchPeriod: () -> Unit = {},
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Settings", "Set up Slussen from the top")
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
        }
        item {
            SectionTitle("Fetches")
            SlussenCard { NavigationLine("Recent Fetches", "Source, time, who started it, outcome", onRecentFetches) }
            val fetchPeriod = ui.fetchPeriod
            if (fetchPeriod != null) {
                SectionTitle("Fetching")
                SlussenCard {
                    SettingLine("Fetch period", fetchPeriod.hint, fetchPeriod.value, Modifier.clickable(onClick = onEditFetchPeriod).testTag("fetch-period"))
                    fetchPeriod.answer?.let { AnswerLine(it) }
                }
            }
            if (ui.shownSettings.isNotEmpty()) {
                SectionTitle("Other Operator Settings")
                SlussenCard {
                    ui.shownSettings.forEach { SettingLine(it.name, it.hint, it.value) }
                    ui.shownSettingsNote?.let { Muted(it) }
                }
            }
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
    val editor = ui.fetchPeriodEditor
    if (editor != null) {
        SettingDialog(
            editor = editor,
            onValueChange = onFetchPeriodValue,
            onSave = { onSaveFetchPeriod(editor.value) },
            onReset = null,
            onCancel = onCancelFetchPeriod,
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
