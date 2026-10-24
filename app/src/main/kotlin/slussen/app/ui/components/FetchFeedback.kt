package slussen.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slussen.app.ui.theme.SlussenColors

/**
 * The Fetch button with the state of its operation (P9-16, `budget.html#fetch-states`): ready, or "Fetching <Data
 * Provider>…" with progress and disabled while the Fetch runs; other actions wait for it (ADR-007 §1).
 */
data class FetchButtonUi(val text: String, val enabled: Boolean, val running: Boolean)

/** How the result of one tap is marked (`budget.html#fetch-states`). */
enum class FetchResultKind {
    /** "Fetched": the Data Provider was read. */
    Fetched,

    /** UC-001 A2: served from the last Fetch within the Fetch Cooldown. */
    Cooldown,

    /** Not fetched because something holds it back: a Back-off, the Request Limit, missing credentials, a refusal. */
    NotFetched,

    /** UC-001 A6, A7: the Data Provider did not answer usably or refused the credentials. */
    Failed,
}

/**
 * The short message for the result of one tap of a Fetch (P9-16): [text] marked as [kind], with [detail] for what else
 * the Fetch came to. [tap] counts the taps of the screen, so that the result of every tap is shown as new, also when
 * its text is the same as the one before.
 */
data class FetchResultUi(val text: String, val detail: String?, val kind: FetchResultKind, val tap: Int)

/** P9-16: the Fetch button; while its Fetch runs it shows the progress and cannot be tapped again. */
@Composable
fun FetchButton(ui: FetchButtonUi, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = ui.enabled) {
        if (ui.running) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SlussenColors.Blue)
            Spacer(Modifier.width(8.dp))
        }
        Text(ui.text)
    }
}

/** P9-16: the marked result of one tap; a new tap shows its result anew. */
@Composable
fun FetchResultLine(result: FetchResultUi) {
    key(result.tap) {
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(visibleState = shown, enter = fadeIn()) {
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mark(result.kind), color = colour(result.kind), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(result.text, color = colour(result.kind), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                if (result.detail != null) Muted(result.detail)
            }
        }
    }
}

private fun mark(kind: FetchResultKind): String = when (kind) {
    FetchResultKind.Fetched, FetchResultKind.Cooldown -> "✓"
    FetchResultKind.NotFetched -> "•"
    FetchResultKind.Failed -> "!"
}

private fun colour(kind: FetchResultKind): Color = when (kind) {
    FetchResultKind.Fetched -> SlussenColors.Green
    FetchResultKind.Cooldown -> SlussenColors.Teal
    FetchResultKind.NotFetched -> SlussenColors.Amber
    FetchResultKind.Failed -> SlussenColors.Red
}
