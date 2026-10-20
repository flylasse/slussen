package slussen.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.theme.SlussenColors
import slussen.core.connection.KnownBank
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice

/**
 * One compact row inside a card (P9-03, the Data Provider pages of `connections.html`): the name and its detail on the left, taking
 * the room that is left, and the balance and then the switch or the trailing text right-aligned, so that neither
 * overflows the card nor covers the name.
 */
@Composable
fun AccountLine(
    title: String,
    detail: String?,
    balance: String?,
    switchOn: Boolean?,
    onSwitch: ((Boolean) -> Unit)?,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    switchTag: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, color = SlussenColors.Text2, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (trailing != null) Text(trailing, color = SlussenColors.Teal, fontSize = 11.sp)
        }
        if (!balance.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(balance, color = SlussenColors.Text, fontSize = 13.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.End)
        }
        if (switchOn != null && onSwitch != null) {
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = switchOn,
                onCheckedChange = onSwitch,
                modifier = if (switchTag != null) Modifier.testTag(switchTag) else Modifier,
            )
        }
    }
}

/**
 * The four Bank Account types (UC-013 BR-009, UC-009 BR-012) as chips that wrap as whole chips onto the next line and
 * never break a label (P9-07).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeChooser(types: List<BankAccountType>, chosen: BankAccountType?, onChoose: (BankAccountType) -> Unit) {
    Text("Bank Account type", fontSize = 12.sp, color = SlussenColors.Text2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        types.forEach { option ->
            FilterChip(
                selected = chosen == option,
                onClick = { onChoose(option) },
                label = { Text(option.text, maxLines = 1, softWrap = false, fontSize = 12.sp) },
            )
        }
    }
}

/**
 * The Bank as one choice (P9-08, UC-013 BR-009, `account-mappings.html#pair-bank`): a radio list of the Banks Slussen
 * knows, and "A new name" with its field; exactly one is chosen.
 */
@Composable
fun BankChooser(knownBanks: List<KnownBank>, chosen: BankChoice?, proposedBankId: Long? = null, onChoose: (BankChoice) -> Unit) {
    var newName by remember { mutableStateOf((chosen as? BankChoice.Named)?.name.orEmpty()) }
    Text("Bank · one Bank: choose a known one or name a new one", fontSize = 12.sp, color = SlussenColors.Text2)
    knownBanks.forEach { known ->
        val selected = chosen == BankChoice.Known(known.bankId)
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onChoose(BankChoice.Known(known.bankId)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = { onChoose(BankChoice.Known(known.bankId)) })
            Text(known.name + if (known.bankId == proposedBankId) " · named by the Data Provider" else "", fontSize = 13.sp)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChoose(BankChoice.Named(newName)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen is BankChoice.Named, onClick = { onChoose(BankChoice.Named(newName)) })
        OutlinedTextField(
            value = newName,
            onValueChange = {
                newName = it
                onChoose(BankChoice.Named(it))
            },
            singleLine = true,
            placeholder = { Text("A new name") },
            modifier = Modifier.weight(1f),
        )
    }
}
