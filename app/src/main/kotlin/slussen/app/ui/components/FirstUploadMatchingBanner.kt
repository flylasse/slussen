package slussen.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.theme.SlussenColors
import slussen.core.firstupload.FirstUploadMatchingTone

/**
 * UC-008 A8, A9, BR-006, BR-009 (`overview.html#first-added`, `#recovery`, `#damaged-critical`,
 * `budget.html#recovery-blocked`, `first-upload-matching.html`): the Blocked Reason [title], the [explanation], one
 * reason line per cause ([reasons]), the action [action], shown in [tone]: plain information or a caution, never an
 * error. [buildNote] says why the action is unavailable while the Comparison (UC-003) is not built (O-P2-09).
 */
data class FirstUploadMatchingUi(
    val title: String,
    val explanation: String,
    val reasons: List<String>,
    val tone: FirstUploadMatchingTone,
    val action: String,
    val actionAvailable: Boolean,
    val buildNote: String?,
)

/** UC-008 BR-006 (`overview.html#recovery`): "Uploads wait for First Upload Matching" on a Budget, in its tone. */
data class FirstUploadMatchingChip(val text: String, val tone: FirstUploadMatchingTone)

/** UC-008 BR-009: blue for plain information, amber for a caution; never red. */
fun toneColours(tone: FirstUploadMatchingTone): Pair<Color, Color> = when (tone) {
    FirstUploadMatchingTone.Information -> SlussenColors.BlueBackground to SlussenColors.Blue
    FirstUploadMatchingTone.Caution -> SlussenColors.AmberBackground to SlussenColors.Amber
}

/** UC-008 A8, BR-009: the First Upload Matching banner with its explanation, reason lines and action. */
@Composable
fun FirstUploadMatchingBanner(ui: FirstUploadMatchingUi, onStart: () -> Unit) {
    val (background, accent) = toneColours(ui.tone)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(background, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(ui.title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(ui.explanation, color = SlussenColors.Text2, fontSize = 12.sp)
        ui.reasons.forEach { Text(it, color = SlussenColors.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (ui.buildNote != null) Text(ui.buildNote, color = SlussenColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = onStart, enabled = ui.actionAvailable, modifier = Modifier.padding(top = 4.dp)) { Text(ui.action) }
    }
}
