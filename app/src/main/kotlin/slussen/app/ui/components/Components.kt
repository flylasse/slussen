package slussen.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.theme.SlussenColors

/** A card of the mockups: surface, thin border, rounded corners. */
@Composable
fun SlussenCard(modifier: Modifier = Modifier, border: Color = SlussenColors.Line, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(SlussenColors.Surface, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A banner of the mockups: amber for warnings, red for blocked Uploads, blue for information. */
@Composable
fun Banner(title: String, detail: String?, background: Color = SlussenColors.AmberBackground, accent: Color = SlussenColors.Amber) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(background, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (detail != null) Text(detail, color = SlussenColors.Text2, fontSize = 12.sp)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = SlussenColors.Text3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun Muted(text: String) {
    Text(text, color = SlussenColors.Text2, fontSize = 12.sp)
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹", fontSize = 20.sp, color = SlussenColors.Text2) }
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlussenColors.Text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = SlussenColors.Text2)
        }
    }
}

/** A Blocked Reason with its remedy (UC-008 BR-006, A6): available remedies start their use case. */
@Composable
fun BlockedReasonLine(text: String, action: RemedyAction?, onRemedy: (RemedyAction) -> Unit) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(text, color = SlussenColors.Amber, fontSize = 12.sp)
        if (action != null) {
            OutlinedButton(onClick = { onRemedy(action) }, enabled = action.available) { Text(action.title) }
            if (action.note != null) Text(action.note, color = SlussenColors.Text3, fontSize = 11.sp)
        }
    }
}

/** A line that opens something when tapped. */
@Composable
fun NavigationLine(title: String, detail: String?, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        Text(title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, color = SlussenColors.Text2, fontSize = 12.sp)
    }
}
