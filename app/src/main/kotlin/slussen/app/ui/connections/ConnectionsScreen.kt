package slussen.app.ui.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.Muted
import slussen.app.ui.components.NavigationLine
import slussen.app.ui.components.ScreenTitle
import slussen.app.ui.components.SectionTitle
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.ServiceKey

/**
 * Settings: the general entries of this build and Connections, generated from the plug-ins' declarations (UC-014 step 1,
 * BR-007, UC-010 BR-010; `docs/mockups/connections.html`), and the version of the app in "This app" (UC-010 step 1,
 * BR-011; `docs/mockups/settings.html#list-2`).
 */
@Composable
fun SettingsScreen(
    ui: ConnectionsUi,
    onService: (ServiceKey) -> Unit,
    onBankAccounts: () -> Unit,
    onSetUp: () -> Unit,
) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item {
            ScreenTitle("Settings")
            SectionTitle("Connections · in use")
        }
        items(ui.inUse) { entry ->
            SlussenCard { NavigationLine("${entry.name} · ${entry.kind}", entry.status) { onService(entry.service) } }
        }
        if (ui.notSetUp.isNotEmpty()) item { SectionTitle("Not set up") }
        items(ui.notSetUp) { entry ->
            SlussenCard {
                Text(entry.name)
                Muted("${entry.kind} in this build")
                OutlinedButton(onClick = { onService(entry.service) }) { Text("Set up") }
            }
        }
        item {
            Muted("Each page is built from what its part declares.")
            SectionTitle("Bank Accounts")
            SlussenCard { NavigationLine("Bank Accounts and Account Mappings", "Add Bank Accounts") { onBankAccounts() } }
            SlussenCard { NavigationLine("Set up Slussen", "Credentials, Budgets, then Bank Accounts") { onSetUp() } }
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
}
